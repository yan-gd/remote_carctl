#!/usr/bin/env bash
# Post-deployment checks for the exp3 console behind nginx.
# Usage: bash deploy/smoke-test.sh [domain]
#   HTTPS_BASE / HTTP_BASE / INSECURE=1 can override the targets for local tests.
set -u

DOMAIN="${1:-agilex.yoyoyan.cn}"
HTTP_BASE="${HTTP_BASE:-http://${DOMAIN}}"
HTTPS_BASE="${HTTPS_BASE:-https://${DOMAIN}}"
CURL=(curl -sS --noproxy '*' --max-time 20)
if [ "${INSECURE:-0}" = "1" ]; then
  CURL+=(-k)
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail=0

pass() { printf 'PASS %s\n' "$1"; }
bad() { printf 'FAIL %s\n' "$1"; fail=1; }

# 1. HTTP -> HTTPS
http_code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "${HTTP_BASE}/" || echo 000)"
http_location="$("${CURL[@]}" -o /dev/null -w '%{redirect_url}' "${HTTP_BASE}/" || true)"
case "$http_code" in
  301|302|307|308)
    case "$http_location" in
      https://*) pass "HTTP redirects to HTTPS ($http_code)" ;;
      *) bad "HTTP redirect Location is not HTTPS: $http_location" ;;
    esac
    ;;
  *) bad "HTTP did not redirect to HTTPS (HTTP $http_code)" ;;
esac

# 2. HTTPS page
page_code="$("${CURL[@]}" -o "$TMP/index.html" -w '%{http_code}' "${HTTPS_BASE}/" || echo 000)"
if [ "$page_code" = "200" ] && grep -qi '<html' "$TMP/index.html"; then
  pass "HTTPS page returns 200 HTML"
else
  bad "HTTPS page failed (HTTP $page_code)"
fi

# 3. Security headers
headers="$("${CURL[@]}" -D - -o /dev/null "${HTTPS_BASE}/" || true)"
for header in Strict-Transport-Security Content-Security-Policy X-Content-Type-Options X-Frame-Options; do
  if printf '%s' "$headers" | grep -qi "^${header}:"; then
    pass "header $header"
  else
    bad "header $header missing"
  fi
done

# 4. API proxy without a token must be JSON 401, not HTML or 502
api_code="$("${CURL[@]}" -o "$TMP/api.json" -w '%{http_code}' "${HTTPS_BASE}/api/v1/robot/status" || echo 000)"
if [ "$api_code" = "401" ] && grep -q '"code"' "$TMP/api.json"; then
  pass "API proxy returns JSON 401"
else
  bad "API proxy returned HTTP $api_code (expected JSON 401)"
fi

# 5. Swagger blocked in production
swagger_code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "${HTTPS_BASE}/swagger-ui.html" || echo 000)"
if [ "$swagger_code" = "404" ]; then
  pass "Swagger is blocked (404)"
else
  bad "Swagger returned HTTP $swagger_code (expected 404)"
fi

if [ "$fail" -eq 0 ]; then
  printf '\nSMOKE TEST PASS: %s\n' "$HTTPS_BASE"
else
  printf '\nSMOKE TEST FAILED: check nginx, TLS and backend\n'
fi
exit "$fail"
