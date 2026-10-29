#!/usr/bin/env bash
# =============================================================================================
# start.sh - starts the whole platform with Docker Compose in dependency order and waits for
# every component to be healthy before starting the next layer:
#
#   1. infrastructure  mysql redis zookeeper kafka minio kafka-ui  (+ one-shot kafka-init, minio-init)
#   2. config-server   (8888)  - every client fetches its config at startup (fail-fast)
#   3. discovery-server(8761)  - Eureka
#   4. business services 8081-8086 (started together, waited for one by one)
#   5. api-gateway     (8080)
#
# Usage: scripts/start.sh [--infra-only] [--build] [--timeout SECONDS] [--help]
#   --infra-only   only layer 1 (then run the services from the IDE with localhost defaults)
#   --build        rebuild images first (docker compose up --build); jars must exist
#   --timeout N    max seconds to wait per component (default 180)
#
# Assumptions: jars were built (scripts/build.sh or ./mvnw -B package -DskipTests) unless
# images already exist; .env exists (copied from .env.example automatically if missing);
# every Spring Boot app exposes /actuator/health.
# =============================================================================================
set -euo pipefail
# shellcheck source=scripts/common.sh
source "$(dirname "$0")/common.sh"

INFRA_ONLY=0; BUILD_FLAG=(); TIMEOUT=180
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) print_header_help "$0"; exit 0 ;;
    --infra-only) INFRA_ONLY=1 ;;
    --build) BUILD_FLAG=(--build) ;;
    --timeout) shift; TIMEOUT="${1:?--timeout needs a value}" ;;
    *) die "Unknown option: $1 (see --help)" ;;
  esac
  shift
done

require_cmd docker curl jq
cd "$ROOT_DIR"

if [ ! -f .env ]; then
  cp .env.example .env
  log_warn "No .env found - created it from .env.example (local defaults)."
fi

# Fail early with a helpful message instead of a cryptic "COPY failed: target/... not found".
if [ "$INFRA_ONLY" -eq 0 ]; then
  for s in $PLATFORM_SERVICES; do
    m="$(svc_name "$s")"
    img="philips-ecommerce/$m:${IMAGE_TAG:-local}"
    if [ ! -f "$m/target/$m.jar" ] && ! docker image inspect "$img" >/dev/null 2>&1; then
      die "Neither $m/target/$m.jar nor image $img exists. Run scripts/build.sh first."
    fi
  done
fi

fail_with_diagnostics() { diagnose "$1"; die "$1 did not become healthy - see diagnostics above"; }

# ---- 1. Infrastructure ---------------------------------------------------------------------
log_section "1/5 Infrastructure"
compose up -d mysql redis zookeeper kafka minio kafka-ui
for svc in mysql redis zookeeper kafka minio; do
  wait_for_container_healthy "$svc" "$TIMEOUT" || fail_with_diagnostics "$svc"
done
# One-shot init containers: `up` (not -d) would attach; with -d we poll until they exit 0.
compose up -d kafka-init minio-init
for svc in kafka-init minio-init; do
  wait_for_container_exit "$svc" "$TIMEOUT" || fail_with_diagnostics "$svc"
done
wait_for_container_healthy kafka-ui "$TIMEOUT" || log_warn "kafka-ui not running (optional, continuing)"

print_infra_urls() {
  log_section "Infrastructure URLs"
  cat <<EOF
  MySQL          localhost:3306   (user ${MYSQL_USER:-philips}, schemas philips_*)
  Redis          localhost:6379
  Kafka          localhost:9092   (containers: kafka:29092)
  Kafka UI       http://localhost:8090
  MinIO S3 API   http://localhost:9000   bucket ${S3_BUCKET:-philips-product-images}
  MinIO console  http://localhost:9001   (credentials: MINIO_ROOT_USER / MINIO_ROOT_PASSWORD in .env)
EOF
}

if [ "$INFRA_ONLY" -eq 1 ]; then
  print_infra_urls
  log_info "Infrastructure is up. Start the services from your IDE (they default to localhost)."
  exit 0
fi

# ---- 2./3. Config + discovery ------------------------------------------------------------
log_section "2/5 config-server"
compose up -d "${BUILD_FLAG[@]+"${BUILD_FLAG[@]}"}" config-server
wait_for_health config-server "http://$SERVICE_HOST:8888/actuator/health" "$TIMEOUT" || fail_with_diagnostics config-server

log_section "3/5 discovery-server"
compose up -d "${BUILD_FLAG[@]+"${BUILD_FLAG[@]}"}" discovery-server
wait_for_health discovery-server "http://$SERVICE_HOST:8761/actuator/health" "$TIMEOUT" || fail_with_diagnostics discovery-server

# ---- 4. Business services -----------------------------------------------------------------
log_section "4/5 Business services"
names=""
for s in $BUSINESS_SERVICES; do names="$names $(svc_name "$s")"; done
# shellcheck disable=SC2086  # word splitting of $names is intended
compose up -d "${BUILD_FLAG[@]+"${BUILD_FLAG[@]}"}" $names
for s in $BUSINESS_SERVICES; do
  n="$(svc_name "$s")"; p="$(svc_port "$s")"
  wait_for_health "$n" "http://$SERVICE_HOST:$p/actuator/health" "$TIMEOUT" || fail_with_diagnostics "$n"
done

# ---- 5. Gateway ------------------------------------------------------------------------------
log_section "5/5 api-gateway"
compose up -d "${BUILD_FLAG[@]+"${BUILD_FLAG[@]}"}" api-gateway
wait_for_health api-gateway "http://$SERVICE_HOST:8080/actuator/health" "$TIMEOUT" || fail_with_diagnostics api-gateway

compose ps

print_infra_urls
log_section "Application URLs"
cat <<EOF
  API gateway         $GATEWAY_URL   (single entry point: /api/products, /api/carts, /api/orders, ...)
  Eureka dashboard    http://localhost:8761
  Config server       http://localhost:8888/product-service/default
EOF
for s in $BUSINESS_SERVICES; do
  printf '  %-20s http://localhost:%s/swagger-ui.html\n' "$(svc_name "$s") Swagger" "$(svc_port "$s")"
done
cat <<EOF

  Next: scripts/health-check.sh   then   scripts/smoke-test.sh
  Note: Eureka registration of the gateway's routes can take ~30 s after startup; a 503 from the
        gateway right after start usually means "service not yet in the gateway's registry cache".
EOF
