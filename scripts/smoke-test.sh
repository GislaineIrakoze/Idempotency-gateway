#!/usr/bin/env sh
set -eu

BASE_URL="${BASE_URL:-http://localhost:8080}"
KEY="smoke-test-$(date +%Y%m%d%H%M%S)"
BODY='{"amount": 100, "currency": "RWF"}'
CHANGED_BODY='{"amount": 500, "currency": "RWF"}'

echo "First request should take about 2 seconds..."
curl -i -X POST "$BASE_URL/process-payment" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d "$BODY"

echo
echo "Duplicate request should replay immediately..."
curl -i -X POST "$BASE_URL/process-payment" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d "$BODY"

echo
echo "Different payload with same key should return 409..."
curl -i -X POST "$BASE_URL/process-payment" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $KEY" \
  -d "$CHANGED_BODY"

