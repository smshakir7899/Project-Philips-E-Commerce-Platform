#!/usr/bin/env bash
# Variables defined here are consumed by the scripts that source this file:
# shellcheck disable=SC2034
# =============================================================================================
# scripts/common.sh - shared helpers, SOURCED by the other scripts (never executed directly).
#
#   source "$(dirname "$0")/common.sh"
#
# Assumptions / portability notes
#   * Must work with the bash 3.2 that ships with macOS: no associative arrays (declare -A),
#     no mapfile/readarray, no ${var,,}. Lists of services are "name:port" strings instead.
#   * Needs curl and jq on the PATH (check-prereqs.sh verifies this).
#   * Every service exposes Spring Boot Actuator at /actuator/health returning {"status":"UP"}.
#   * Colors are disabled automatically when stdout is not a terminal or NO_COLOR is set.
#
# Main helpers
#   log_info/log_warn/log_error/log_step/log_section   pretty logging
#   pass/fail/skip "<name>" ["detail"]                  record a check result (see print_summary)
#   http METHOD URL [JSON_BODY] [extra curl args...]    sets HTTP_STATUS, HTTP_BODY, HTTP_HEADERS_FILE
#   header_value "Header-Name"                          value of a response header from the last http call
#   jget '<jq filter>' ["json"]                         jq -r on HTTP_BODY (or the given json)
#   check_status "<name>" <expected...>                 pass/fail on the last http call's status
#   wait_for_url URL TIMEOUT_S [NAME]                   poll until HTTP 2xx
#   wait_for_health NAME URL TIMEOUT_S                  poll until actuator status == UP
#   poll_until TIMEOUT_S INTERVAL_S cmd [args...]       retry a command/function until it succeeds
#   compose ...                                         docker compose with the repo's compose file
#   diagnose SERVICE                                    docker compose ps + last 80 log lines
# =============================================================================================

# Guard against double sourcing.
if [ -n "${__PHILIPS_COMMON_SH:-}" ]; then return 0; fi
__PHILIPS_COMMON_SH=1

# ---- Locations --------------------------------------------------------------------------------
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPTS_DIR/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"

# Load .env (same file docker compose uses) so the scripts see IMAGE_TAG, S3_BUCKET,
# PRODUCT_CACHE_ENABLED, ... It only contains simple KEY=value lines (see .env.example).
if [ -f "$ROOT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT_DIR/.env"
  set +a
fi

# ---- Endpoints (override via environment, e.g. GATEWAY_URL=http://ec2-host:8080) -------------
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
EUREKA_BASE_URL="${EUREKA_BASE_URL:-http://localhost:8761}"
SERVICE_HOST="${SERVICE_HOST:-localhost}"
HTTP_TIMEOUT="${HTTP_TIMEOUT:-15}"     # seconds per HTTP request (tok_timeout needs > 6s)

# name:port of every Spring Boot application, in startup order (see docs/CONTRACTS.md §1/§3).
PLATFORM_SERVICES="config-server:8888 discovery-server:8761 product-service:8081 inventory-service:8082 cart-service:8083 order-service:8084 payment-service:8085 notification-service:8086 api-gateway:8080"
# Business services only (the ones behind the gateway).
BUSINESS_SERVICES="product-service:8081 inventory-service:8082 cart-service:8083 order-service:8084 payment-service:8085 notification-service:8086"
# Applications that must appear in Eureka (config-server does not register).
EUREKA_APPS="API-GATEWAY PRODUCT-SERVICE INVENTORY-SERVICE CART-SERVICE ORDER-SERVICE PAYMENT-SERVICE NOTIFICATION-SERVICE"
# Infrastructure containers started by start.sh / used from the IDE.
INFRA_SERVICES="mysql redis zookeeper kafka kafka-init minio minio-init kafka-ui"
KAFKA_TOPICS="order-created order-confirmed order-cancelled payment-processed payment-refunded"

svc_name() { echo "${1%%:*}"; }   # "product-service:8081" -> product-service
svc_port() { echo "${1##*:}"; }   # "product-service:8081" -> 8081

# ---- Colors -----------------------------------------------------------------------------------
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_RED=$'\033[0;31m'; C_GREEN=$'\033[0;32m'; C_YELLOW=$'\033[0;33m'
  C_BLUE=$'\033[0;34m'; C_CYAN=$'\033[0;36m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'; C_NC=$'\033[0m'
else
  C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_CYAN=""; C_BOLD=""; C_DIM=""; C_NC=""
fi

ts() { date '+%H:%M:%S'; }
log_info()    { printf '%s %s[INFO]%s  %s\n' "$(ts)" "$C_BLUE" "$C_NC" "$*"; }
log_warn()    { printf '%s %s[WARN]%s  %s\n' "$(ts)" "$C_YELLOW" "$C_NC" "$*" >&2; }
log_error()   { printf '%s %s[ERROR]%s %s\n' "$(ts)" "$C_RED" "$C_NC" "$*" >&2; }
log_step()    { printf '%s %s==>%s %s\n' "$(ts)" "$C_CYAN" "$C_NC" "$*"; }
log_section() { printf '\n%s%s=== %s ===%s\n' "$C_BOLD" "$C_BLUE" "$*" "$C_NC"; }
die()         { log_error "$*"; exit 1; }

# ---- Result bookkeeping -----------------------------------------------------------------------
PASS_COUNT=0; FAIL_COUNT=0; SKIP_COUNT=0
RESULT_LINES=""   # newline-separated "STATUS|name|detail" (bash 3.2 friendly)

_record() { RESULT_LINES="${RESULT_LINES}${1}|${2}|${3:-}
"; }
pass() { PASS_COUNT=$((PASS_COUNT + 1)); _record PASS "$1" "${2:-}"
         printf '  %s[PASS]%s %s%s\n' "$C_GREEN" "$C_NC" "$1" "${2:+ ${C_DIM}($2)${C_NC}}"; }
fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); _record FAIL "$1" "${2:-}"
         printf '  %s[FAIL]%s %s\n' "$C_RED" "$C_NC" "$1"
         if [ -n "${2:-}" ]; then printf '         %s%s%s\n' "$C_DIM" "$2" "$C_NC"; fi; }
skip() { SKIP_COUNT=$((SKIP_COUNT + 1)); _record SKIP "$1" "${2:-}"
         printf '  %s[SKIP]%s %s%s\n' "$C_YELLOW" "$C_NC" "$1" "${2:+ ($2)}"; }

# Prints a summary table; returns 1 if any check failed (use as the script's exit status).
print_summary() {
  local title="${1:-Summary}"
  log_section "$title"
  printf '%s\n' "$RESULT_LINES" | while IFS='|' read -r status name detail; do
    [ -z "$status" ] && continue
    case "$status" in
      PASS) printf '  %s%-5s%s %s\n' "$C_GREEN" "$status" "$C_NC" "$name" ;;
      FAIL) printf '  %s%-5s%s %s %s\n' "$C_RED" "$status" "$C_NC" "$name" "${detail:+- $detail}" ;;
      SKIP) printf '  %s%-5s%s %s %s\n' "$C_YELLOW" "$status" "$C_NC" "$name" "${detail:+- $detail}" ;;
    esac
  done
  printf '\n  %sPASS: %d%s   %sFAIL: %d%s   %sSKIP: %d%s\n' \
    "$C_GREEN" "$PASS_COUNT" "$C_NC" "$C_RED" "$FAIL_COUNT" "$C_NC" "$C_YELLOW" "$SKIP_COUNT" "$C_NC"
  if [ "$FAIL_COUNT" -gt 0 ]; then
    printf '  %s%sRESULT: FAILED%s\n' "$C_BOLD" "$C_RED" "$C_NC"; return 1
  fi
  printf '  %s%sRESULT: PASSED%s\n' "$C_BOLD" "$C_GREEN" "$C_NC"; return 0
}

# ---- Commands ---------------------------------------------------------------------------------
have_cmd()    { command -v "$1" >/dev/null 2>&1; }
require_cmd() { local c; for c in "$@"; do have_cmd "$c" || die "Required command '$c' not found (run scripts/check-prereqs.sh)"; done; }

compose() { docker compose -f "$COMPOSE_FILE" --project-directory "$ROOT_DIR" "$@"; }

# Shows container states and the last 80 log lines of a service - called when something fails.
diagnose() {
  local svc="$1"
  log_warn "Diagnostics for '$svc':"
  compose ps -a 2>&1 | sed 's/^/    /' >&2 || true
  printf '    %s--- last 80 log lines of %s ---%s\n' "$C_DIM" "$svc" "$C_NC" >&2
  compose logs --no-color --tail 80 "$svc" 2>&1 | sed 's/^/    /' >&2 || true
}

# Portable UUID v4 (macOS/Linux): uuidgen -> /proc -> python3.
gen_uuid() {
  if have_cmd uuidgen; then uuidgen | tr '[:upper:]' '[:lower:]'
  elif [ -r /proc/sys/kernel/random/uuid ]; then cat /proc/sys/kernel/random/uuid
  else python3 -c 'import uuid; print(uuid.uuid4())'; fi
}

# ---- HTTP -------------------------------------------------------------------------------------
HTTP_STATUS=""; HTTP_BODY=""
__HTTP_TMP="$(mktemp -d "${TMPDIR:-/tmp}/philips-http.XXXXXX")"
HTTP_HEADERS_FILE="$__HTTP_TMP/headers"
trap 'rm -rf "$__HTTP_TMP"' EXIT

# http METHOD URL [BODY] [extra curl args...]
#   BODY is sent as application/json when non-empty. Extra args go straight to curl, e.g.
#   http POST "$url" "$json" -H "Idempotency-Key: $key"
# Sets HTTP_STATUS ("000" on connection error/timeout), HTTP_BODY and HTTP_HEADERS_FILE.
# Always returns 0 so callers under `set -e` can inspect the status themselves.
http() {
  local method="$1" url="$2" body="${3:-}"
  shift 2; [ $# -gt 0 ] && shift
  local args
  args=(-s -S -X "$method" -o "$__HTTP_TMP/body" -D "$HTTP_HEADERS_FILE" -w '%{http_code}'
        --max-time "$HTTP_TIMEOUT" -H 'Accept: application/json')
  if [ -n "$body" ]; then args+=(-H 'Content-Type: application/json' --data "$body"); fi
  : > "$__HTTP_TMP/body"; : > "$HTTP_HEADERS_FILE"
  HTTP_STATUS="$(curl "${args[@]}" "$@" "$url" 2>"$__HTTP_TMP/err")" || true
  [ -z "$HTTP_STATUS" ] && HTTP_STATUS="000"
  HTTP_BODY="$(cat "$__HTTP_TMP/body")"
  if [ "$HTTP_STATUS" = "000" ]; then HTTP_BODY="curl error: $(cat "$__HTTP_TMP/err")"; fi
  if [ -n "${VERBOSE:-}" ]; then
    printf '    %s%s %s -> %s%s\n' "$C_DIM" "$method" "$url" "$HTTP_STATUS" "$C_NC" >&2
  fi
  return 0
}

# Case-insensitive response header lookup from the last http() call (CR stripped).
header_value() {
  grep -i "^$1:" "$HTTP_HEADERS_FILE" 2>/dev/null | tail -1 | cut -d: -f2- | tr -d '\r' | sed 's/^ *//'
}

# jq -r helper. jget '.status'  or  jget '.items | length' "$someJson". Prints "" on invalid JSON.
jget() {
  local filter="$1" json="${2-$HTTP_BODY}"
  printf '%s' "$json" | jq -r "$filter" 2>/dev/null || true
}

# First 300 chars of the body on one line - used in failure messages.
short_body() { printf '%s' "${1-$HTTP_BODY}" | tr '\n' ' ' | cut -c1-300; }

# check_status "step name" 200 [201 ...] -> pass/fail using HTTP_STATUS of the last call.
check_status() {
  local name="$1"; shift
  local code
  for code in "$@"; do
    if [ "$HTTP_STATUS" = "$code" ]; then pass "$name" "HTTP $HTTP_STATUS"; return 0; fi
  done
  fail "$name" "expected HTTP $* but got $HTTP_STATUS: $(short_body)"
  return 1
}

# check_json "step name" '<jq boolean expression>' -> pass/fail on HTTP_BODY.
check_json() {
  local name="$1" expr="$2"
  if printf '%s' "$HTTP_BODY" | jq -e "$expr" >/dev/null 2>&1; then pass "$name"; return 0; fi
  fail "$name" "jq '$expr' was false for HTTP $HTTP_STATUS: $(short_body)"
  return 1
}

# ---- Waiting ----------------------------------------------------------------------------------
# poll_until TIMEOUT INTERVAL cmd [args...]: re-runs cmd until it returns 0 or TIMEOUT seconds elapse.
poll_until() {
  local timeout="$1" interval="$2"; shift 2
  local start now
  start=$(date +%s)
  while true; do
    if "$@"; then return 0; fi
    now=$(date +%s)
    if [ $((now - start)) -ge "$timeout" ]; then return 1; fi
    sleep "$interval"
  done
}

_url_ok() { http GET "$1"; case "$HTTP_STATUS" in 2??) return 0 ;; esac; return 1; }
_health_up() { http GET "$1"; [ "$HTTP_STATUS" = "200" ] && [ "$(jget '.status')" = "UP" ]; }

# wait_for_url URL TIMEOUT [NAME]
wait_for_url() {
  local url="$1" timeout="$2" name="${3:-$1}"
  log_step "Waiting up to ${timeout}s for $name ($url)"
  if poll_until "$timeout" 3 _url_ok "$url"; then log_info "$name is reachable"; return 0; fi
  log_error "$name not reachable after ${timeout}s (last HTTP $HTTP_STATUS)"; return 1
}

# wait_for_health NAME URL TIMEOUT - waits for {"status":"UP"}.
wait_for_health() {
  local name="$1" url="$2" timeout="$3"
  log_step "Waiting up to ${timeout}s for $name to be UP ($url)"
  if poll_until "$timeout" 3 _health_up "$url"; then log_info "$name is UP"; return 0; fi
  log_error "$name not UP after ${timeout}s (last HTTP $HTTP_STATUS: $(short_body))"; return 1
}

# wait_for_container_healthy SERVICE TIMEOUT - uses the Docker HEALTHCHECK status.
# One-shot init containers (kafka-init, minio-init) are considered OK once they exited with 0.
container_state() {
  local cid; cid="$(compose ps -a -q "$1" 2>/dev/null | head -1)"
  [ -z "$cid" ] && { echo "missing"; return; }
  docker inspect -f '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}/{{.State.ExitCode}}' "$cid" 2>/dev/null || echo "missing"
}
_container_ok() {
  case "$(container_state "$1")" in
    running/healthy/*|exited/none/0|running/none/*) return 0 ;;
  esac
  return 1
}
wait_for_container_healthy() {
  local svc="$1" timeout="$2"
  log_step "Waiting up to ${timeout}s for container '$svc' to be healthy"
  if poll_until "$timeout" 3 _container_ok "$svc"; then log_info "$svc: $(container_state "$svc")"; return 0; fi
  log_error "$svc not healthy after ${timeout}s (state: $(container_state "$svc"))"; return 1
}

# wait_for_container_exit SERVICE TIMEOUT - for one-shot containers (kafka-init, minio-init):
# waits until the container has exited; returns 0 only for exit code 0.
_container_exited() { case "$(container_state "$1")" in exited/*) return 0 ;; esac; return 1; }
wait_for_container_exit() {
  local svc="$1" timeout="$2"
  log_step "Waiting up to ${timeout}s for one-shot container '$svc' to finish"
  if ! poll_until "$timeout" 2 _container_exited "$svc"; then
    log_error "$svc still running after ${timeout}s"; return 1
  fi
  case "$(container_state "$svc")" in
    exited/*/0) log_info "$svc completed successfully"; return 0 ;;
  esac
  log_error "$svc failed (state: $(container_state "$svc"))"; return 1
}

# Usage helper: prints the comment block at the top of the calling script (lines starting "# ").
print_header_help() {
  sed -n '2,/^# =\{10,\}$/p' "$1" | sed -e '1d' -e '$d' -e 's/^# \{0,1\}//'
}
