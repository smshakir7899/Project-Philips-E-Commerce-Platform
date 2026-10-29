#!/usr/bin/env bash
# =============================================================================================
# scripts/smoke-test.sh - end-to-end business journey through the API GATEWAY.
#
# Usage: scripts/smoke-test.sh [--quick] [--no-dlt] [--gateway URL] [--verbose] [--help]
#
#   --quick        skip the slow scenarios (tok_timeout late-success refund, DLT replay)
#   --no-dlt       skip only the dead-letter-topic (DLT) replay scenario
#   --gateway URL  gateway base URL (default: $GATEWAY_URL or http://localhost:8080)
#   --verbose      print every HTTP call (method, URL, status)
#   --help         show this help
#
# Scenarios (all calls go through the gateway, never directly to a service):
#   1 catalog      list/search products, cache miss-then-hit, stock availability
#   2 happy path   cart -> checkout tok_visa (201 CONFIRMED) -> idempotent replay (200) ->
#                  async: cart cleared, reservation COMMITTED, payment SUCCEEDED, notifications
#   3 declined     tok_declined -> 402 PAYMENT_DECLINED, order PAYMENT_FAILED, reservation RELEASED
#   4 stock        stock lowered after add-to-cart -> 409 INSUFFICIENT_STOCK (stock restored after)
#   5 invalid      inactive product 400, unknown product 404, empty cart 400
#   6 amount limit product 19 (> 5000 EUR) -> 402 (payment AMOUNT_LIMIT_EXCEEDED)
#   7 timeout      tok_timeout -> 503, then the late SUCCEEDED payment is REFUNDED   (slow)
#   8 DLT          simulated notification failure -> retries -> .DLT -> replay       (slow)
#   9 history      order history + tracking of the happy-path order
#
# Assumptions
#   * The whole stack is running (scripts/start.sh) and the gateway routes /api/** to services.
#   * Seed data (Flyway): products 1 and 2 active with ample stock; product 5 exists (~2 units);
#     product 19 price > 5000 EUR; product 20 inactive; product 999999 does not exist.
#   * Kafka consumers (inventory, cart, payment, notification) are running - async checks poll.
#   * Every run uses unique customer ids CUST-<epoch>-XXX, so runs never interfere.
#   * The stock of product 5 and the notification "simulated failures" list are restored on
#     exit, even when a step fails or the script is interrupted.
#
# Exit code: 0 when every executed check passed, 1 otherwise.
# =============================================================================================
# Predicates/trap handlers below are invoked indirectly (poll_until "$@", trap), hence:
# shellcheck disable=SC2329
set -euo pipefail

# shellcheck source=scripts/common.sh
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

QUICK=0
RUN_DLT=1
while [ $# -gt 0 ]; do
  case "$1" in
    --quick)   QUICK=1 ;;
    --no-dlt)  RUN_DLT=0 ;;
    --gateway) [ $# -ge 2 ] || die "--gateway needs a URL"; GATEWAY_URL="${2%/}"; shift ;;
    --verbose) VERBOSE=1 ;;
    -h|--help) print_header_help "$0"; exit 0 ;;
    *) die "Unknown option: $1 (see --help)" ;;
  esac
  shift
done
export VERBOSE="${VERBOSE:-}"

require_cmd curl jq

RUN_ID="$(date +%s)"
CUST_OK="CUST-$RUN_ID-OK"
CUST_DEC="CUST-$RUN_ID-DEC"
CUST_STK="CUST-$RUN_ID-STK"
CUST_INV="CUST-$RUN_ID-INV"
CUST_TMO="CUST-$RUN_ID-TMO"
CUST_AMT="CUST-$RUN_ID-AMT"
CUST_DLT="CUST-$RUN_ID-DLT"
API="$GATEWAY_URL/api"
ORDER_REGEX='^ORD-[0-9]{8}-[0-9A-F]{8}$'

HAPPY_ORDER=""        # set by the happy path, used by the history scenario

# ---- Cleanup / restore (runs on every exit, including Ctrl-C) ---------------------------------
STOCK_RESTORE_NEEDED=0; STOCK5_SKU=""; STOCK5_QTY=""; STOCK5_LOW=""
SIM_RESTORE_NEEDED=0; SIM_ORIGINAL="[]"

restore_stock() {
  [ "$STOCK_RESTORE_NEEDED" = "1" ] || return 0
  http PUT "$API/inventory/5" "$(stock_body "$STOCK5_SKU" "$STOCK5_QTY" "$STOCK5_LOW")"
  if [ "$HTTP_STATUS" = "200" ]; then
    STOCK_RESTORE_NEEDED=0
    log_info "Restored product 5 stock to availableQuantity=$STOCK5_QTY"
  else
    log_warn "Could not restore product 5 stock (HTTP $HTTP_STATUS) - fix with: PUT $API/inventory/5 $(stock_body "$STOCK5_SKU" "$STOCK5_QTY" "$STOCK5_LOW")"
  fi
}

restore_sim_failures() {
  [ "$SIM_RESTORE_NEEDED" = "1" ] || return 0
  http PUT "$API/notifications/admin/simulated-failures" "$(jq -nc --argjson ids "$SIM_ORIGINAL" '{customerIds: $ids}')"
  if [ "$HTTP_STATUS" = "200" ]; then
    SIM_RESTORE_NEEDED=0
    log_info "Restored notification simulated-failures list to $SIM_ORIGINAL"
  else
    log_warn "Could not restore simulated-failures (HTTP $HTTP_STATUS) - check $API/notifications/admin/simulated-failures"
  fi
}

on_exit() {
  restore_stock || true
  restore_sim_failures || true
  rm -rf "$__HTTP_TMP"
}
# Replaces common.sh's EXIT trap, so the temp dir removal is repeated here (after the restores,
# which still need the temp dir for their HTTP calls).
trap on_exit EXIT

# ---- Request helpers --------------------------------------------------------------------------
stock_body() { jq -nc --arg sku "$1" --argjson q "$2" --argjson l "$3" '{sku:$sku, availableQuantity:$q, lowStockThreshold:$l}'; }

add_item() {   # add_item CUSTOMER PRODUCT_ID QTY
  http POST "$API/carts/$1/items" "$(jq -nc --argjson p "$2" --argjson q "$3" '{productId:$p, quantity:$q}')"
}

checkout_body() {   # checkout_body CUSTOMER CARD_TOKEN
  jq -nc --arg c "$1" --arg t "$2" '{
    customerId: $c, cardToken: $t,
    shippingAddress: {fullName: "Smoke Tester", line1: "High Tech Campus 52", city: "Eindhoven",
                      postalCode: "5656 AE", country: "NL"}}'
}

checkout() {   # checkout CUSTOMER CARD_TOKEN IDEMPOTENCY_KEY
  http POST "$API/orders/checkout" "$(checkout_body "$1" "$2")" -H "Idempotency-Key: $3"
}

# Order number from a success body (.orderNumber) or an error body (.details.orderNumber).
order_number_from_body() { jget '.orderNumber // .details.orderNumber // empty'; }

# poll_check NAME TIMEOUT INTERVAL fn [args...] -> PASS/FAIL with the last HTTP response on failure.
poll_check() {
  local name="$1" timeout="$2" interval="$3"; shift 3
  local start; start=$(date +%s)
  if poll_until "$timeout" "$interval" "$@"; then
    pass "$name" "after $(( $(date +%s) - start ))s, HTTP $HTTP_STATUS"; return 0
  fi
  fail "$name" "not reached within ${timeout}s; last HTTP $HTTP_STATUS: $(short_body)"
  return 1
}

# Predicates used with poll_until (return 0 when the condition holds).
p_cart_empty()          { http GET "$API/carts/$1"; [ "$HTTP_STATUS" = "200" ] && [ "$(jget '.items | length')" = "0" ]; }
p_reservation_status()  { http GET "$API/inventory/reservations/$1"; [ "$HTTP_STATUS" = "200" ] && [ "$(jget '.status')" = "$2" ]; }
p_payment_status()      { http GET "$API/payments/order/$1"; [ "$HTTP_STATUS" = "200" ] && [ "$(jget '.status')" = "$2" ]; }
p_notifications_have()  {   # p_notifications_have ORDER TYPE [TYPE...]
  local on="$1"; shift
  http GET "$API/notifications/order/$on"
  [ "$HTTP_STATUS" = "200" ] || return 1
  printf '%s' "$HTTP_BODY" | jq -e '[.[].type] as $t | all($ARGS.positional[]; . as $x | any($t[]; . == $x))' --args "$@" >/dev/null 2>&1
}
DLT_EVENTS="[]"
p_failed_events_for() {   # p_failed_events_for ORDER MIN_COUNT
  http GET "$API/notifications/admin/failed-events?status=NEW"
  [ "$HTTP_STATUS" = "200" ] || return 1
  DLT_EVENTS="$(printf '%s' "$HTTP_BODY" | jq -c --arg on "$1" '[.[] | select(.messageKey == $on)]' 2>/dev/null || echo '[]')"
  [ "$(jget 'length' "$DLT_EVENTS")" -ge "$2" ] 2>/dev/null
}

# ---- 0. Precheck ------------------------------------------------------------------------------
precheck() {
  log_section "0. Precheck - gateway $GATEWAY_URL"
  http GET "$GATEWAY_URL/actuator/health"
  if [ "$HTTP_STATUS" = "200" ] && [ "$(jget '.status')" = "UP" ]; then
    pass "Gateway /actuator/health is UP" "HTTP 200"
    return 0
  fi
  fail "Gateway /actuator/health is UP" "HTTP $HTTP_STATUS: $(short_body)"
  log_error "Gateway not reachable/UP. Start the stack first: scripts/start.sh (then scripts/health-check.sh)"
  print_summary "Smoke test summary" || true
  exit 1
}

# ---- 1. Catalog -------------------------------------------------------------------------------
scenario_catalog() {
  log_section "1. Catalog, cache and availability"
  http GET "$API/products?page=0&size=5"
  if check_status "List products (page 0, size 5)" 200; then
    check_json "Product list is not empty" '.totalElements > 0 and (.content | length) > 0' || true
  fi

  http GET "$API/products/1"
  local word=""
  if [ "$HTTP_STATUS" = "200" ]; then word="$(jget '.name | split(" ") | .[0]')"; fi
  if [ -n "$word" ]; then
    http GET "$API/products?q=$(jq -rn --arg s "$word" '$s | @uri')&page=0&size=5"
    if check_status "Search products q='$word'" 200; then
      check_json "Search returns results" '(.content | length) > 0' || true
    fi
  else
    skip "Search products" "GET /api/products/1 returned HTTP $HTTP_STATUS"
  fi

  # Cache: flush -> first detail read is a MISS (DB + populate Redis) -> second read is a HIT.
  http GET "$API/products/admin/cache/stats"
  if [ "$HTTP_STATUS" != "200" ]; then
    fail "Read cache stats" "HTTP $HTTP_STATUS: $(short_body)"
  elif [ "$(jget '.enabled')" != "true" ]; then
    skip "Cache miss-then-hit" "cache disabled (PRODUCT_CACHE_ENABLED=false)"
  else
    http DELETE "$API/products/admin/cache"
    if check_status "Flush product cache" 204 200; then
      http GET "$API/products/admin/cache/stats"
      local h0 m0 h1 m1
      h0="$(jget '.hits // 0')"; m0="$(jget '.misses // 0')"
      http GET "$API/products/1"; check_status "Product 1 detail (1st read, expect cache miss)" 200 || true
      http GET "$API/products/1"; check_status "Product 1 detail (2nd read, expect cache hit)" 200 || true
      http GET "$API/products/admin/cache/stats"
      h1="$(jget '.hits // 0')"; m1="$(jget '.misses // 0')"
      local dh=$((h1 - h0)) dm=$((m1 - m0))
      log_info "Cache delta: misses +$dm, hits +$dh (hitRatio now $(jget '.hitRatio'))"
      if [ "$dm" -ge 1 ] && [ "$dh" -ge 1 ]; then
        pass "Cache miss then hit" "misses +$dm, hits +$dh"
      else
        fail "Cache miss then hit" "expected misses>=+1 and hits>=+1, got misses +$dm, hits +$dh"
      fi
    else
      skip "Cache miss-then-hit" "flush failed"
    fi
  fi

  http GET "$API/inventory/1/availability?quantity=1"
  if check_status "Availability of product 1 (qty 1)" 200; then
    check_json "Product 1 is available" '.available == true' || true
  fi
}

# ---- 2. Happy path ----------------------------------------------------------------------------
scenario_happy() {
  log_section "2. Happy path checkout ($CUST_OK, tok_visa)"
  http DELETE "$API/carts/$CUST_OK"
  check_status "Clear cart" 204 || true

  add_item "$CUST_OK" 1 1
  check_status "Add product 1 x1 to cart" 200 || { skip "Happy path checkout" "cart add failed"; return 0; }
  add_item "$CUST_OK" 2 1
  check_status "Add product 2 x1 to cart" 200 || { skip "Happy path checkout" "cart add failed"; return 0; }
  http PUT "$API/carts/$CUST_OK/items/1" '{"quantity":2}'
  if check_status "Update product 1 quantity to 2" 200; then
    check_json "Cart totalQuantity is 3" '.totalQuantity == 3' || true
    log_info "Cart subtotal: $(jget '.subtotal') $(jget '.currency')"
  fi

  local key; key="$(gen_uuid)"
  checkout "$CUST_OK" tok_visa "$key"
  if ! check_status "Checkout (Idempotency-Key $key)" 201; then
    skip "Idempotent replay + async checks" "checkout failed"; return 0
  fi
  check_json "Order status is CONFIRMED" '.status == "CONFIRMED"' || true
  local on; on="$(jget '.orderNumber')"
  if printf '%s' "$on" | grep -Eq "$ORDER_REGEX"; then
    pass "Order number format" "$on"
  else
    fail "Order number format" "'$on' does not match $ORDER_REGEX"
  fi
  [ -n "$on" ] || { skip "Idempotent replay + async checks" "no orderNumber"; return 0; }
  HAPPY_ORDER="$on"
  log_info "Order $on total $(jget '.totalAmount') $(jget '.currency'), payment $(jget '.paymentReference')"

  checkout "$CUST_OK" tok_visa "$key"
  if check_status "Replay checkout with same Idempotency-Key" 200; then
    local replayed; replayed="$(header_value Idempotent-Replayed)"
    if [ "$replayed" = "true" ]; then pass "Header Idempotent-Replayed: true"
    else fail "Header Idempotent-Replayed: true" "header value was '$replayed'"; fi
    check_json "Replay returns the same order" ".orderNumber == \"$on\"" || true
  fi

  poll_check "Cart cleared by order-confirmed consumer" 30 2 p_cart_empty "$CUST_OK" || true
  poll_check "Inventory reservation COMMITTED" 30 2 p_reservation_status "$on" COMMITTED || true
  poll_check "Payment SUCCEEDED" 10 2 p_payment_status "$on" SUCCEEDED || true
  poll_check "Notifications ORDER_PLACED + ORDER_CONFIRMED + PAYMENT_SUCCEEDED" 30 2 \
    p_notifications_have "$on" ORDER_PLACED ORDER_CONFIRMED PAYMENT_SUCCEEDED || true
}

# ---- 3. Declined ------------------------------------------------------------------------------
scenario_declined() {
  log_section "3. Payment declined ($CUST_DEC, tok_declined)"
  add_item "$CUST_DEC" 1 1
  check_status "Add product 1 x1 to cart" 200 || { skip "Declined checkout" "cart add failed"; return 0; }

  checkout "$CUST_DEC" tok_declined "$(gen_uuid)"
  check_status "Checkout returns 402" 402 || { skip "Declined follow-up checks" "unexpected checkout result"; return 0; }
  check_json "Error code PAYMENT_DECLINED" '.code == "PAYMENT_DECLINED"' || true
  local on; on="$(order_number_from_body)"
  [ -n "$on" ] || { fail "details.orderNumber present" "$(short_body)"; return 0; }
  pass "details.orderNumber present" "$on"

  http GET "$API/orders/$on"
  if check_status "Get declined order" 200; then
    check_json "Order status PAYMENT_FAILED" '.status == "PAYMENT_FAILED"' || true
  fi
  http GET "$API/payments/order/$on"
  if check_status "Get declined payment" 200; then
    check_json "Payment FAILED with CARD_DECLINED" '.status == "FAILED" and .failureReason == "CARD_DECLINED"' || true
  fi
  poll_check "Inventory reservation RELEASED" 30 2 p_reservation_status "$on" RELEASED || true
  poll_check "Notification ORDER_CANCELLED" 30 2 p_notifications_have "$on" ORDER_CANCELLED || true
}

# ---- 4. Insufficient stock --------------------------------------------------------------------
scenario_stock() {
  log_section "4. Insufficient stock ($CUST_STK, product 5)"
  http GET "$API/inventory/5"
  check_status "Read inventory of product 5" 200 || { skip "Insufficient stock scenario" "no inventory for product 5"; return 0; }
  STOCK5_SKU="$(jget '.sku')"; STOCK5_QTY="$(jget '.availableQuantity')"; STOCK5_LOW="$(jget '.lowStockThreshold')"
  log_info "Product 5 ($STOCK5_SKU): available=$STOCK5_QTY, lowStockThreshold=$STOCK5_LOW"
  STOCK_RESTORE_NEEDED=1     # from now on the EXIT trap restores the original values

  http PUT "$API/inventory/5" "$(stock_body "$STOCK5_SKU" 2 "$STOCK5_LOW")"
  check_status "Set product 5 stock to 2" 200 || { skip "Insufficient stock checkout" "stock update failed"; restore_stock; return 0; }
  add_item "$CUST_STK" 5 2
  check_status "Add product 5 x2 to cart" 200 || { skip "Insufficient stock checkout" "cart add failed"; restore_stock; return 0; }
  http PUT "$API/inventory/5" "$(stock_body "$STOCK5_SKU" 1 "$STOCK5_LOW")"
  check_status "Lower product 5 stock to 1 (after add-to-cart)" 200 || { skip "Insufficient stock checkout" "stock update failed"; restore_stock; return 0; }

  checkout "$CUST_STK" tok_visa "$(gen_uuid)"
  if check_status "Checkout returns 409" 409; then
    check_json "Error code INSUFFICIENT_STOCK" '.code == "INSUFFICIENT_STOCK"' || true
    local on; on="$(order_number_from_body)"
    if [ -n "$on" ]; then
      http GET "$API/orders/$on"
      if check_status "Get cancelled order $on" 200; then
        check_json "Order status CANCELLED" '.status == "CANCELLED"' || true
      fi
    else
      skip "Order stored as CANCELLED" "no details.orderNumber in the 409 body"
    fi
  fi
  restore_stock
}

# ---- 5. Invalid products ----------------------------------------------------------------------
scenario_invalid() {
  log_section "5. Invalid products and empty cart ($CUST_INV)"
  add_item "$CUST_INV" 20 1
  if check_status "Add inactive product 20 returns 400" 400; then
    check_json "Error code PRODUCT_INACTIVE" '.code == "PRODUCT_INACTIVE"' || true
  fi
  add_item "$CUST_INV" 999999 1
  if check_status "Add unknown product 999999 returns 404" 404; then
    check_json "Error code PRODUCT_NOT_FOUND" '.code == "PRODUCT_NOT_FOUND"' || true
  fi
  http GET "$API/products/999999"
  if check_status "GET unknown product returns 404" 404; then
    check_json "Error body has code + correlationId" '.code == "PRODUCT_NOT_FOUND" and (.correlationId // "" | length) > 0' || true
  fi
  checkout "$CUST_INV" tok_visa "$(gen_uuid)"
  if check_status "Checkout with empty cart returns 400" 400; then
    check_json "Error code EMPTY_CART" '.code == "EMPTY_CART"' || true
  fi
}

# ---- 6. Amount limit --------------------------------------------------------------------------
scenario_amount_limit() {
  log_section "6. Amount limit ($CUST_AMT, product 19)"
  http GET "$API/products/19"
  if [ "$HTTP_STATUS" != "200" ]; then skip "Amount limit scenario" "product 19 not found (HTTP $HTTP_STATUS)"; return 0; fi
  if [ "$(jget '.price > 5000 and .active')" != "true" ]; then
    skip "Amount limit scenario" "product 19 price $(jget '.price') not > 5000 or inactive"; return 0
  fi
  add_item "$CUST_AMT" 19 1
  check_status "Add product 19 x1 to cart (subtotal $(jget '.subtotal // empty'))" 200 || { skip "Amount limit checkout" "cart add failed"; return 0; }
  checkout "$CUST_AMT" tok_visa "$(gen_uuid)"
  check_status "Checkout above 5000 EUR returns 402" 402 || return 0
  check_json "Error code PAYMENT_DECLINED" '.code == "PAYMENT_DECLINED"' || true
  local on; on="$(order_number_from_body)"
  [ -n "$on" ] || { skip "Amount limit follow-up" "no details.orderNumber"; return 0; }
  http GET "$API/orders/$on"
  log_info "Order $on status=$(jget '.status') failureReason=$(jget '.failureReason')"
  http GET "$API/payments/order/$on"
  if check_status "Get payment of order $on" 200; then
    check_json "Payment failureReason AMOUNT_LIMIT_EXCEEDED" '.failureReason == "AMOUNT_LIMIT_EXCEEDED"' || true
  fi
}

# ---- 7. Payment timeout (slow) ----------------------------------------------------------------
scenario_timeout() {
  log_section "7. Payment timeout + late-success refund ($CUST_TMO, tok_timeout) [slow]"
  add_item "$CUST_TMO" 1 1
  check_status "Add product 1 x1 to cart" 200 || { skip "Timeout checkout" "cart add failed"; return 0; }

  local saved_timeout="$HTTP_TIMEOUT"
  HTTP_TIMEOUT=30   # payment sleeps 6s; order-service gives up after its 3s read timeout (+ retries)
  checkout "$CUST_TMO" tok_timeout "$(gen_uuid)"
  HTTP_TIMEOUT="$saved_timeout"
  check_status "Checkout returns 503" 503 || { skip "Timeout follow-up checks" "unexpected checkout result"; return 0; }
  check_json "Error code PAYMENT_SERVICE_UNAVAILABLE" '.code == "PAYMENT_SERVICE_UNAVAILABLE"' || true
  local on; on="$(order_number_from_body)"
  [ -n "$on" ] || { fail "details.orderNumber present" "$(short_body)"; return 0; }

  http GET "$API/orders/$on"
  if check_status "Get timed-out order $on" 200; then
    check_json "Order status PAYMENT_FAILED" '.status == "PAYMENT_FAILED"' || true
  fi
  log_step "Waiting for the late SUCCEEDED payment to be refunded (compensation via order-cancelled)"
  poll_check "Late payment REFUNDED" 60 3 p_payment_status "$on" REFUNDED || true
  poll_check "Inventory reservation RELEASED" 30 2 p_reservation_status "$on" RELEASED || true
}

# ---- 8. DLT (slow) ----------------------------------------------------------------------------
scenario_dlt() {
  log_section "8. Retry + dead-letter topic + replay ($CUST_DLT) [slow]"
  http GET "$API/notifications/admin/simulated-failures"
  check_status "Read simulated failures" 200 || { skip "DLT scenario" "admin endpoint unavailable"; return 0; }
  SIM_ORIGINAL="$(jget '(.customerIds // .) | if type == "array" then . else [] end' | jq -c . 2>/dev/null || echo '[]')"
  [ -n "$SIM_ORIGINAL" ] || SIM_ORIGINAL="[]"
  SIM_RESTORE_NEEDED=1
  http PUT "$API/notifications/admin/simulated-failures" \
    "$(jq -nc --argjson ids "$SIM_ORIGINAL" --arg c "$CUST_DLT" '{customerIds: ($ids + [$c] | unique)}')"
  check_status "Enable simulated notification failure for $CUST_DLT" 200 || { skip "DLT scenario" "could not set failure"; restore_sim_failures; return 0; }

  add_item "$CUST_DLT" 1 1
  check_status "Add product 1 x1 to cart" 200 || { skip "DLT checkout" "cart add failed"; restore_sim_failures; return 0; }
  checkout "$CUST_DLT" tok_visa "$(gen_uuid)"
  check_status "Checkout returns 201" 201 || { skip "DLT follow-up checks" "checkout failed"; restore_sim_failures; return 0; }
  local on; on="$(jget '.orderNumber')"

  log_step "Waiting for notification-service retries (3 x 1s) and the .DLT listener"
  if ! poll_check "Failed events (status NEW) recorded for $on" 45 3 p_failed_events_for "$on" 1; then
    restore_sim_failures; return 0
  fi
  # order-created, order-confirmed and payment-processed should all dead-letter; give them a moment.
  poll_until 20 3 p_failed_events_for "$on" 3 || true
  log_info "Dead-lettered topics: $(jget '[.[].originalTopic] | join(", ")' "$DLT_EVENTS")"

  restore_sim_failures
  if [ "$SIM_RESTORE_NEEDED" = "0" ]; then pass "Removed simulated failure for $CUST_DLT"
  else fail "Removed simulated failure for $CUST_DLT" "restore PUT failed"; return 0; fi

  local ids id; ids="$(jget '.[].id' "$DLT_EVENTS")"
  for id in $ids; do
    http POST "$API/notifications/admin/failed-events/$id/replay"
    check_status "Replay failed event $id" 200 || true
  done
  poll_check "Notification ORDER_PLACED appears after replay" 30 2 p_notifications_have "$on" ORDER_PLACED || true
  http GET "$API/notifications/order/$on"
  log_info "Notifications for $on after replay: $(jget 'length') ($(jget '[.[].type] | join(", ")'))"
}

# ---- 9. History -------------------------------------------------------------------------------
scenario_history() {
  log_section "9. Order history and tracking ($CUST_OK)"
  if [ -z "$HAPPY_ORDER" ]; then skip "Order history and tracking" "no happy-path order"; return 0; fi
  http GET "$API/orders?customerId=$CUST_OK&page=0&size=10"
  if check_status "Order history of $CUST_OK" 200; then
    check_json "History contains the order (newest first)" ".totalElements >= 1 and .content[0].orderNumber == \"$HAPPY_ORDER\"" || true
  fi
  http GET "$API/orders/$HAPPY_ORDER/tracking"
  if check_status "Tracking of $HAPPY_ORDER" 200; then
    check_json "Tracking currentStatus CONFIRMED with CONFIRMED in history" \
      '.currentStatus == "CONFIRMED" and any(.history[]; .toStatus == "CONFIRMED")' || true
    log_info "Status history: $(jget '[.history[] | "\(.fromStatus // "start")->\(.toStatus)"] | join(", ")')"
  fi
}

# ---- Main -------------------------------------------------------------------------------------
log_info "Smoke test run $RUN_ID against $GATEWAY_URL (quick=$QUICK, dlt=$RUN_DLT)"
precheck
scenario_catalog
scenario_happy
scenario_declined
scenario_stock
scenario_invalid
scenario_amount_limit
if [ "$QUICK" = "1" ]; then
  log_section "7. Payment timeout [slow]"; skip "Payment timeout + late refund" "--quick"
else
  scenario_timeout
fi
if [ "$QUICK" = "1" ] || [ "$RUN_DLT" = "0" ]; then
  log_section "8. DLT [slow]"; skip "DLT retry + replay" "--quick/--no-dlt"
else
  scenario_dlt
fi
scenario_history

if print_summary "Smoke test summary"; then
  exit 0
fi
log_warn "Some checks failed. Next steps: scripts/health-check.sh, then docker compose logs <service> (e.g. order-service)"
exit 1
