#!/usr/bin/env bash
# =============================================================================================
# verify.sh - end-to-end verification orchestrator. Runs the steps below in order and writes a
# report to verify-report-<timestamp>.txt in the repo root.
#
#   1 prereqs        scripts/check-prereqs.sh
#   2 build          scripts/build.sh --skip-tests  (maven package + docker compose build)  [--skip-build]
#   3 unit tests     ./mvnw -B test   (with --with-it: ./mvnw -B -Pintegration-tests verify) [--skip-tests]
#   4 compose up     scripts/start.sh                                                    [--skip-start]
#   5 health         scripts/health-check.sh
#   6 smoke          scripts/smoke-test.sh  (checkout success / failure journeys)       [--quick]
#   7 kafka/redis    consumer-group lag, Redis keyspace hits/misses, product cache key count
#   8 performance    perf/run-benchmark.sh if present                                    [--with-perf]
#   9 report         summary table + environment info -> verify-report-<timestamp>.txt
#
# Usage: scripts/verify.sh [--skip-build] [--skip-tests] [--with-it] [--skip-start] [--quick]
#                          [--with-perf] [--help]
#
# Rules: a step that did not run is reported SKIPPED (never PASS). If step 1, 2 or 4 fails the
# remaining dependent steps are SKIPPED ("blocked"). The stack is left running at the end
# (scripts/stop.sh to stop it). Exit status 1 if any step FAILED.
# Assumptions: product-service caches product details in Redis under keys "product:v1:*";
# consumer group ids = spring.application.name (+ notification-service-dlt).
# =============================================================================================
set -euo pipefail
# shellcheck source=scripts/common.sh
source "$(dirname "$0")/common.sh"

SKIP_BUILD=0; SKIP_TESTS=0; WITH_IT=0; SKIP_START=0; QUICK=0; WITH_PERF=0
for arg in "$@"; do
  case "$arg" in
    -h|--help) print_header_help "$0"; exit 0 ;;
    --skip-build) SKIP_BUILD=1 ;;
    --skip-tests) SKIP_TESTS=1 ;;
    --with-it) WITH_IT=1 ;;
    --skip-start) SKIP_START=1 ;;
    --quick) QUICK=1 ;;
    --with-perf) WITH_PERF=1 ;;
    *) die "Unknown option: $arg (see --help)" ;;
  esac
done

cd "$ROOT_DIR"
STAMP="$(date '+%Y%m%d-%H%M%S')"
REPORT="$ROOT_DIR/verify-report-$STAMP.txt"
LOG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/philips-verify.XXXXXX")"
if [ -x ./mvnw ]; then MVN=./mvnw; else MVN=mvn; fi

# Step bookkeeping in indexed arrays (bash 3.2 has no associative arrays).
STEP_IDS=(); STEP_NAMES=(); STEP_RESULTS=(); STEP_SECONDS=(); STEP_NOTES=()
BLOCKED=""   # set to a reason once a blocking step fails

record_step() {   # id name result seconds note
  STEP_IDS+=("$1"); STEP_NAMES+=("$2"); STEP_RESULTS+=("$3"); STEP_SECONDS+=("$4"); STEP_NOTES+=("${5:-}")
  case "$3" in
    PASS) pass "step $1 $2" "${4}s" ;;
    FAIL) fail "step $1 $2" "${5:-} (log: $LOG_DIR/step$1.log)" ;;
    SKIPPED) skip "step $1 $2" "${5:-}" ;;
  esac
}

# run_step ID NAME BLOCKING(0/1) cmd [args...] - streams output to console + step log.
run_step() {
  local id="$1" name="$2" blocking="$3"; shift 3
  log_section "Step $id: $name"
  if [ -n "$BLOCKED" ]; then record_step "$id" "$name" SKIPPED 0 "blocked: $BLOCKED"; return 0; fi
  local start rc
  start=$(date +%s)
  set +e
  "$@" 2>&1 | tee "$LOG_DIR/step$id.log"
  rc=${PIPESTATUS[0]}
  set -e
  if [ "$rc" -eq 0 ]; then
    record_step "$id" "$name" PASS "$(( $(date +%s) - start ))"
  else
    record_step "$id" "$name" FAIL "$(( $(date +%s) - start ))" "exit code $rc"
    if [ "$blocking" -eq 1 ]; then BLOCKED="step $id ($name) failed"; fi
  fi
}

skip_step() { log_section "Step $1: $2"; record_step "$1" "$2" SKIPPED 0 "$3"; }

# ---- Step 7 implementation ----------------------------------------------------------------
kafka_redis_checks() {
  local rc=0 out lag groups
  # Consumer lag = log-end offset - committed offset per partition. After the smoke test the
  # consumers should have caught up (lag 0). Poll briefly because listeners commit per record.
  echo "--- Kafka consumer groups (kafka-consumer-groups --describe --all-groups) ---"
  local attempt=0
  while true; do
    out="$(compose exec -T kafka kafka-consumer-groups --bootstrap-server kafka:29092 --describe --all-groups 2>/dev/null | tr -d '\r' || true)"
    # Columns: GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
    lag="$(printf '%s\n' "$out" | awk '$1!="GROUP" && NF>=6 && $6 ~ /^[0-9]+$/ {s+=$6} END {print s+0}')"
    attempt=$((attempt + 1))
    if [ "$lag" -eq 0 ] || [ "$attempt" -ge 10 ]; then break; fi
    sleep 3
  done
  printf '%s\n' "$out" | awk 'NF>0' | head -60
  groups="$(printf '%s\n' "$out" | awk '$1!="GROUP" && NF>=6 {print $1}' | sort -u | tr '\n' ' ')"
  echo "groups: $groups"
  for g in inventory-service cart-service payment-service notification-service notification-service-dlt; do
    case " $groups " in
      *" $g "*) echo "PASS consumer group $g present" ;;
      *) echo "FAIL consumer group $g missing"; rc=1 ;;
    esac
  done
  if [ "$lag" -eq 0 ]; then echo "PASS total consumer lag = 0"; else echo "FAIL total consumer lag = $lag after ~30s"; rc=1; fi

  echo "--- Redis (product cache) ---"
  local stats hits misses keys
  stats="$(compose exec -T redis redis-cli INFO stats 2>/dev/null | tr -d '\r' || true)"
  hits="$(printf '%s\n' "$stats" | awk -F: '$1=="keyspace_hits" {print $2}')"
  misses="$(printf '%s\n' "$stats" | awk -F: '$1=="keyspace_misses" {print $2}')"
  hits="${hits:-0}"; misses="${misses:-0}"
  if [ $((hits + misses)) -gt 0 ]; then
    echo "keyspace_hits=$hits keyspace_misses=$misses hit_ratio=$(awk -v h="$hits" -v m="$misses" 'BEGIN {printf "%.2f", h/(h+m)}')"
  else
    echo "keyspace_hits=0 keyspace_misses=0"
  fi
  # --scan iterates with SCAN (non-blocking) instead of KEYS (blocks Redis on big keyspaces).
  keys="$(compose exec -T redis redis-cli --scan --pattern 'product:v1:*' 2>/dev/null | tr -d '\r' | awk 'NF' | wc -l | tr -d ' ')"
  echo "cached product keys (product:v1:*): $keys"
  if [ "${PRODUCT_CACHE_ENABLED:-true}" = "true" ]; then
    if [ "$hits" -gt 0 ]; then echo "PASS redis keyspace hits > 0"; else echo "FAIL no Redis hits - is the product cache used?"; rc=1; fi
    if [ "$keys" -gt 0 ]; then echo "PASS product cache keys present"; else echo "FAIL no product:v1:* keys"; rc=1; fi
  else
    echo "SKIP cache assertions (PRODUCT_CACHE_ENABLED=false)"
  fi
  return "$rc"
}

# ---- Steps ----------------------------------------------------------------------------------
PREREQ_ARGS=()
if [ -n "$(compose ps -q 2>/dev/null || true)" ] || [ "$SKIP_START" -eq 1 ]; then
  PREREQ_ARGS=(--no-ports)   # our own running containers hold the ports
fi
run_step 1 "prerequisites" 1 "$SCRIPTS_DIR/check-prereqs.sh" "${PREREQ_ARGS[@]+"${PREREQ_ARGS[@]}"}"

if [ "$SKIP_BUILD" -eq 1 ]; then skip_step 2 "build" "--skip-build"
else run_step 2 "build (package + images)" 1 "$SCRIPTS_DIR/build.sh" --skip-tests; fi

if [ "$SKIP_TESTS" -eq 1 ]; then skip_step 3 "unit tests" "--skip-tests"
elif [ "$WITH_IT" -eq 1 ]; then run_step 3 "unit + integration tests" 0 "$MVN" -B -ntp -Pintegration-tests verify
else run_step 3 "unit tests" 0 "$MVN" -B -ntp test; fi

if [ "$SKIP_START" -eq 1 ]; then skip_step 4 "docker compose up" "--skip-start (using the running stack)"
else run_step 4 "docker compose up (start.sh)" 1 "$SCRIPTS_DIR/start.sh"; fi

run_step 5 "health check" 0 "$SCRIPTS_DIR/health-check.sh"

SMOKE_ARGS=()
if [ "$QUICK" -eq 1 ]; then SMOKE_ARGS=(--quick); fi
if [ -x "$SCRIPTS_DIR/smoke-test.sh" ]; then
  run_step 6 "smoke test (checkout success/failure)" 0 "$SCRIPTS_DIR/smoke-test.sh" "${SMOKE_ARGS[@]+"${SMOKE_ARGS[@]}"}"
else
  skip_step 6 "smoke test" "scripts/smoke-test.sh missing"
fi

run_step 7 "kafka lag + redis cache" 0 kafka_redis_checks

if [ "$WITH_PERF" -eq 0 ]; then skip_step 8 "performance" "use --with-perf"
elif [ -x "$ROOT_DIR/perf/run-benchmark.sh" ]; then run_step 8 "performance (perf/run-benchmark.sh)" 0 "$ROOT_DIR/perf/run-benchmark.sh"
else skip_step 8 "performance" "perf/run-benchmark.sh not found / not executable"; fi

# ---- Step 9: report -------------------------------------------------------------------------
log_section "Step 9: report"
ESC=$'\033'
{
  echo "Philips e-commerce verification report - $STAMP"
  echo "host: $(uname -srm)   docker: $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo n/a)"
  echo "java: $(java -version 2>&1 | head -1 || echo n/a)"
  echo "git:  $(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo 'not a git repo')"
  echo "options: skip-build=$SKIP_BUILD skip-tests=$SKIP_TESTS with-it=$WITH_IT skip-start=$SKIP_START quick=$QUICK with-perf=$WITH_PERF"
  echo
  printf '%-4s %-42s %-8s %6s  %s\n' STEP NAME RESULT SECS NOTE
  i=0
  while [ "$i" -lt "${#STEP_IDS[@]}" ]; do
    printf '%-4s %-42s %-8s %6s  %s\n' "${STEP_IDS[$i]}" "${STEP_NAMES[$i]}" "${STEP_RESULTS[$i]}" "${STEP_SECONDS[$i]}" "${STEP_NOTES[$i]}"
    i=$((i + 1))
  done
  echo
  # Detail: PASS/FAIL/SKIP lines printed by the child scripts, plus the tail of failed steps.
  i=0
  while [ "$i" -lt "${#STEP_IDS[@]}" ]; do
    f="$LOG_DIR/step${STEP_IDS[$i]}.log"
    if [ -f "$f" ]; then
      echo "----- step ${STEP_IDS[$i]} ${STEP_NAMES[$i]} -----"
      # strip ANSI colours (BSD sed has no \x escapes, hence the literal ESC in $ESC)
      sed "s/${ESC}\[[0-9;]*m//g" "$f" | grep -E '\[(PASS|FAIL|SKIP)\]|^(PASS|FAIL|SKIP) |RESULT:|lag|keyspace|cached product' || true
      if [ "${STEP_RESULTS[$i]}" = "FAIL" ]; then
        echo "  (last 40 lines)"; sed "s/${ESC}\[[0-9;]*m//g" "$f" | tail -40 | sed 's/^/  | /'
      fi
    fi
    i=$((i + 1))
  done
} > "$REPORT"
log_info "Report written to $REPORT"
log_info "Full step logs: $LOG_DIR"

print_summary "Verification summary"
