#!/usr/bin/env bash
# =============================================================================================
# health-check.sh - one-shot health report of the running platform.
#
# Usage: scripts/health-check.sh [--infra-only] [--help]
#   --infra-only   only MySQL / Redis / Kafka / MinIO checks (services started from the IDE)
#
# Checks
#   * every Spring Boot app: GET http://localhost:<port>/actuator/health -> {"status":"UP"}
#   * gateway: its own /actuator/health, plus ONE cheap, always-200 API call per business service
#     THROUGH the gateway (proves route + Eureka lookup + service; the gateway does not proxy
#     the services' actuator endpoints)
#   * Eureka: http://localhost:8761/eureka/apps (Accept: application/json) lists all 7 client
#     apps with at least one instance UP (config-server does not register)
#   * Kafka: 5 topics + 5 .DLT topics exist (kafka-topics inside the kafka container)
#   * Redis: PING -> PONG;  MySQL: the 6 philips_* schemas exist;  MinIO: live + bucket exists
# Exit status 1 if any check fails.
# =============================================================================================
set -euo pipefail
# shellcheck source=scripts/common.sh
source "$(dirname "$0")/common.sh"

INFRA_ONLY=0
for arg in "$@"; do
  case "$arg" in
    -h|--help) print_header_help "$0"; exit 0 ;;
    --infra-only) INFRA_ONLY=1 ;;
    *) die "Unknown option: $arg (see --help)" ;;
  esac
done
require_cmd docker curl jq
cd "$ROOT_DIR"

# ---- Infrastructure --------------------------------------------------------------------------
log_section "Infrastructure"

if out="$(compose exec -T redis redis-cli ping 2>&1)" && [ "$(printf '%s' "$out" | tr -d '\r')" = "PONG" ]; then
  pass "redis PING" "PONG"
else
  fail "redis PING" "$out"; diagnose redis
fi

# MYSQL_PWD avoids the "password on the command line" warning; $MYSQL_ROOT_PASSWORD is the
# container's own env var (single quotes => expanded inside the container).
# shellcheck disable=SC2016
if dbs="$(compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e "SHOW DATABASES"' 2>&1)"; then
  missing=""
  for db in philips_product philips_inventory philips_cart philips_order philips_payment philips_notification; do
    printf '%s\n' "$dbs" | tr -d '\r' | grep -qx "$db" || missing="$missing $db"
  done
  if [ -z "$missing" ]; then pass "mysql schemas philips_* (6)"; else fail "mysql schemas" "missing:$missing"; fi
else
  fail "mysql reachable" "$(printf '%s' "$dbs" | tail -3)"; diagnose mysql
fi

if topics="$(compose exec -T kafka kafka-topics --bootstrap-server kafka:29092 --list 2>&1)"; then
  missing=""
  for t in $KAFKA_TOPICS; do
    for name in "$t" "$t.DLT"; do
      printf '%s\n' "$topics" | tr -d '\r' | grep -qx "$name" || missing="$missing $name"
    done
  done
  if [ -z "$missing" ]; then pass "kafka topics (5 + 5 .DLT)"; else fail "kafka topics" "missing:$missing (did kafka-init run?)"; fi
else
  fail "kafka reachable" "$(printf '%s' "$topics" | tail -3)"; diagnose kafka
fi

http GET "http://localhost:9000/minio/health/live"
check_status "minio live" 200 || true
BUCKET="${S3_BUCKET:-philips-product-images}"
# Anonymous "download" policy allows GET on objects, not listing; a HEAD on a missing key returns
# 404 (bucket exists) vs NoSuchBucket. We ask MinIO itself instead, via a throw-away mc container.
if compose run --rm --no-deps -T --entrypoint sh minio-init -c \
     "mc alias set local http://minio:9000 \"\$MINIO_ROOT_USER\" \"\$MINIO_ROOT_PASSWORD\" >/dev/null && mc ls local/$BUCKET >/dev/null" >/dev/null 2>&1; then
  pass "minio bucket $BUCKET exists"
else
  fail "minio bucket $BUCKET exists" "run: docker compose up minio-init"
fi

if [ "$INFRA_ONLY" -eq 1 ]; then
  print_summary "Health check (infrastructure)"; exit $?
fi

# ---- Services (direct) -------------------------------------------------------------------
log_section "Spring Boot applications (direct /actuator/health)"
for s in $PLATFORM_SERVICES; do
  n="$(svc_name "$s")"; p="$(svc_port "$s")"
  http GET "http://$SERVICE_HOST:$p/actuator/health"
  if [ "$HTTP_STATUS" = "200" ] && [ "$(jget '.status')" = "UP" ]; then
    # Show DOWN sub-components even when the aggregate is UP (show-details: always).
    down="$(jget '[.components // {} | to_entries[] | select(.value.status != "UP") | .key] | join(",")')"
    pass "$n :$p UP" "${down:+non-UP components: $down}"
  else
    fail "$n :$p health" "HTTP $HTTP_STATUS $(short_body)"
  fi
done

# ---- Through the gateway ------------------------------------------------------------------
log_section "Through the gateway ($GATEWAY_URL)"
http GET "$GATEWAY_URL/actuator/health"
if [ "$HTTP_STATUS" = "200" ] && [ "$(jget '.status')" = "UP" ]; then pass "api-gateway UP"; else fail "api-gateway health" "HTTP $HTTP_STATUS $(short_body)"; fi

# name|path - each returns 200 on an empty or seeded database.
for route in \
  "product-service|/api/products?page=0&size=1" \
  "inventory-service|/api/inventory?productIds=1" \
  "cart-service|/api/carts/HEALTH-CHECK" \
  "order-service|/api/orders?customerId=HEALTH-CHECK&page=0&size=1" \
  "payment-service|/api/payments/admin/outbox?status=FAILED" \
  "notification-service|/api/notifications/admin/failed-events?status=NEW"; do
  n="${route%%|*}"; path="${route#*|}"
  http GET "$GATEWAY_URL$path"
  check_status "gateway -> $n ($path)" 200 || true
done

# ---- Eureka ---------------------------------------------------------------------------------
log_section "Eureka registry"
http GET "$EUREKA_BASE_URL/eureka/apps"
if [ "$HTTP_STATUS" = "200" ]; then
  # Eureka's JSON collapses single-element lists into objects -> normalise to arrays first.
  registered="$(jget '[.applications.application | if type=="array" then .[] else . end
                       | select(([.instance] | flatten | map(select(.status=="UP")) | length) > 0)
                       | .name] | join(" ")')"
  for app in $EUREKA_APPS; do
    case " $registered " in
      *" $app "*) pass "eureka: $app UP" ;;
      *) fail "eureka: $app registered + UP" "registered UP apps: ${registered:-none}" ;;
    esac
  done
else
  fail "eureka /eureka/apps" "HTTP $HTTP_STATUS $(short_body)"
fi

print_summary "Health check"
