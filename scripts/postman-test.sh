#!/usr/bin/env bash
# =============================================================================================
# scripts/postman-test.sh - runs the Postman collection (postman/*.json) headlessly with newman.
#
# Usage: scripts/postman-test.sh [--gateway URL] [--help]
#
# Assumptions
#   * The stack is running (scripts/start.sh) and the gateway answers on http://localhost:8080.
#   * Node.js with npx is installed; newman 6 is fetched on the fly by `npx` (no global install).
#   * The collection uses postman/local.postman_environment.json and the sample image
#     postman/sample-product-image.png (resolved through --working-dir postman).
# Exit status: newman's exit status (non-zero if any request or assertion failed).
# =============================================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATEWAY="${GATEWAY_URL:-http://localhost:8080}"

while [ $# -gt 0 ]; do
  case "$1" in
    --gateway) GATEWAY="$2"; shift 2 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if ! command -v npx >/dev/null 2>&1; then
  echo "npx (Node.js) not found - install Node.js 18+ or import the collection into the Postman app instead." >&2
  exit 2
fi
if ! curl -fsS "$GATEWAY/actuator/health" >/dev/null 2>&1; then
  echo "gateway $GATEWAY is not healthy - start the stack first (scripts/start.sh)" >&2
  exit 2
fi

cd "$ROOT/postman"
exec npx --yes newman@6 run philips-ecommerce.postman_collection.json \
  -e local.postman_environment.json \
  --env-var "gatewayUrl=$GATEWAY" \
  --working-dir . \
  --delay-request 100
