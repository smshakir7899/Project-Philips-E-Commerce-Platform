#!/usr/bin/env bash
# =====================================================================================================
# Runs the five scenarios of the product-service performance experiment against the docker compose stack
# and produces perf/results/<timestamp>/{S1..S5}.json + REPORT.md.
#
#   S1  baseline code + V2 indexes DROPPED + no cache
#   S2  baseline code + V2 indexes         + no cache          (S1 -> S2 = indexing)
#   S3  optimized code + indexes + cache disabled              (S2 -> S3 = query / JPA optimization)
#   S4  optimized code + indexes + cache COLD (flush, no warmup)
#   S5  optimized code + indexes + cache WARM (flush, warmup)  (S3 -> S5 = caching)
#
# Every scenario starts from a freshly recreated product-service container (same JVM state), runs the same
# deterministic request list (same --seed) with the same concurrency. Modes are switched with environment
# variables that docker-compose.yml must pass through to product-service:
#     PRODUCT_QUERY_MODE: ${PRODUCT_QUERY_MODE:-optimized}
#     PRODUCT_CACHE_ENABLED: ${PRODUCT_CACHE_ENABLED:-true}
# The script verifies the active mode through /api/products/admin/perf/stats, so a compose file that does
# not pass them through fails loudly instead of producing a meaningless report.
#
# Usage: perf/run-benchmark.sh [--skip-seed] [--rows 50000] [--requests 2000] [--concurrency 16]
#                              [--warmup 500] [--seed 42] [--base-url http://localhost:8081]
#                              [--scenarios "S1 S2 S3 S4 S5"]
# Requires: docker compose stack running (mysql, redis, config-server, discovery-server, product-service),
#           python3, curl. Bash 3.2 compatible (macOS default).
# =====================================================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

BASE_URL="${BASE_URL:-http://localhost:8081}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-root_pwd}"
MYSQL_DATABASE="philips_product"
ROWS=50000
REQUESTS=2000
CONCURRENCY=16
WARMUP=500
SEED=42
SKIP_SEED=false
SCENARIOS="S1 S2 S3 S4 S5"
HEALTH_TIMEOUT_SECONDS=240

usage() { sed -n '2,25p' "$0"; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-seed) SKIP_SEED=true; shift ;;
    --rows) ROWS="$2"; shift 2 ;;
    --requests) REQUESTS="$2"; shift 2 ;;
    --concurrency) CONCURRENCY="$2"; shift 2 ;;
    --warmup) WARMUP="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --scenarios) SCENARIOS="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "unknown option $1"; usage ;;
  esac
done

ID_MIN=100000
ID_MAX=$((ID_MIN + ROWS - 1))
OUT_DIR="${SCRIPT_DIR}/results/$(date +%Y%m%d-%H%M%S)"
mkdir -p "${OUT_DIR}"
cd "${REPO_ROOT}"

log() { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

mysql_exec() {
  # MYSQL_PWD instead of -p<password>: keeps the password out of the process list and avoids the CLI warning
  docker compose exec -T -e MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql mysql -uroot "${MYSQL_DATABASE}" "$@"
}

restore_indexes() {
  log "Restoring V2 indexes (create-indexes.sql is idempotent)"
  mysql_exec < "${SCRIPT_DIR}/sql/create-indexes.sql" > /dev/null || echo "WARN: index restore failed - run perf/sql/create-indexes.sql manually"
}

wait_for_health() {
  local waited=0
  until curl -fsS "${BASE_URL}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; do
    if [ "${waited}" -ge "${HEALTH_TIMEOUT_SECONDS}" ]; then
      echo "product-service did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s"; docker compose logs --tail 80 product-service; exit 1
    fi
    sleep 3; waited=$((waited + 3))
  done
  echo "product-service UP after ~${waited}s"
}

# recreate_service <query-mode> <cache-enabled>
recreate_service() {
  log "Recreating product-service: PRODUCT_QUERY_MODE=$1 PRODUCT_CACHE_ENABLED=$2"
  PRODUCT_QUERY_MODE="$1" PRODUCT_CACHE_ENABLED="$2" \
    docker compose up -d --no-deps --force-recreate product-service
  wait_for_health
  local stats
  stats="$(curl -fsS "${BASE_URL}/api/products/admin/perf/stats")"
  python3 - "$1" "$2" "${stats}" <<'PY'
import json, sys
mode, cache, stats = sys.argv[1], sys.argv[2] == "true", json.loads(sys.argv[3])
if stats["queryMode"] != mode or bool(stats["cacheEnabled"]) != cache:
    sys.exit("MODE MISMATCH: expected mode=%s cache=%s but service reports mode=%s cache=%s - does "
             "docker-compose.yml pass PRODUCT_QUERY_MODE / PRODUCT_CACHE_ENABLED to product-service?"
             % (mode, cache, stats["queryMode"], stats["cacheEnabled"]))
print("verified: queryMode=%s cacheEnabled=%s" % (stats["queryMode"], stats["cacheEnabled"]))
PY
}

# bench <scenario> <description> <warmup> [extra bench.py args]
bench() {
  local name="$1" description="$2" warmup="$3"; shift 3
  log "Running ${name}: ${description}"
  python3 "${SCRIPT_DIR}/bench.py" --base-url "${BASE_URL}" --scenario "${name}" --description "${description}" \
    --requests "${REQUESTS}" --concurrency "${CONCURRENCY}" --warmup "${warmup}" --seed "${SEED}" \
    --id-min "${ID_MIN}" --id-max "${ID_MAX}" --mysql-docker --out "${OUT_DIR}/${name}.json" "$@"
}

wants() { case " ${SCENARIOS} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

log "Results -> ${OUT_DIR}"
echo "rows=${ROWS} requests=${REQUESTS} concurrency=${CONCURRENCY} warmup=${WARMUP} seed=${SEED} scenarios='${SCENARIOS}'"
echo "NOTE: stop other business services (order/payment outbox pollers etc.) for clean MySQL status deltas."

if [ "${SKIP_SEED}" = false ]; then
  log "Seeding ${ROWS} benchmark products (idempotent)"
  { echo "SET @bench_rows = ${ROWS};"; cat "${SCRIPT_DIR}/sql/seed-benchmark-data.sql"; } | mysql_exec
fi

trap restore_indexes EXIT

if wants S1; then
  log "Dropping V2 indexes for S1"
  mysql_exec < "${SCRIPT_DIR}/sql/drop-indexes.sql"
  recreate_service baseline false
  bench S1 "baseline code, V2 indexes DROPPED, no cache" "${WARMUP}"
fi

restore_indexes

if wants S2; then
  recreate_service baseline false
  bench S2 "baseline code, V2 indexes, no cache" "${WARMUP}"
fi

if wants S3; then
  recreate_service optimized false
  bench S3 "optimized code, V2 indexes, cache disabled" "${WARMUP}"
fi

if wants S4; then
  recreate_service optimized true
  bench S4 "optimized code, V2 indexes, cache COLD (flushed, no warmup)" 0 --flush-cache
fi

if wants S5; then
  recreate_service optimized true
  bench S5 "optimized code, V2 indexes, cache WARM (warmup pass first)" "${WARMUP}" --flush-cache
fi

log "Restoring default mode (optimized, cache enabled)"
recreate_service optimized true

python3 "${SCRIPT_DIR}/report.py" "${OUT_DIR}" > /dev/null
log "Done. Report: ${OUT_DIR}/REPORT.md"
