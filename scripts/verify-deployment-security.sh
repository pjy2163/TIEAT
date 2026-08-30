#!/usr/bin/env bash
set -uo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: bash scripts/verify-deployment-security.sh https://service.example"
  exit 2
fi

base_url="${1%/}"
case "$base_url" in
  https://*/*|https://*\?*|https://*\#*|https://*@*)
    echo "FAIL: base URL must be a credential-free HTTPS origin without path, query, or fragment."
    exit 2
    ;;
  https://*)
    ;;
  *)
    echo "FAIL: base URL must use HTTPS."
    exit 2
    ;;
esac

if ! command -v curl >/dev/null 2>&1; then
  echo "FAIL: curl is required."
  exit 2
fi

evidence_dir="$(mktemp -d)"
trap 'rm -rf "$evidence_dir"' EXIT
failed=0

record_failure() {
  echo "FAIL: $1"
  failed=1
}

record_pass() {
  echo "PASS: $1"
}

header_value() {
  local header_file="$1"
  local header_name="$2"
  awk -v name="$header_name" '
    index(tolower($0), tolower(name) ":") == 1 {
      sub(/^[^:]+:[[:space:]]*/, "")
      sub(/\r$/, "")
      value = $0
    }
    END { print value }
  ' "$header_file"
}

fetch_headers() {
  local url="$1"
  local destination="$2"
  curl --silent --show-error --max-time 15 --dump-header "$destination" --output /dev/null "$url"
}

root_headers="$evidence_dir/root.headers"
if ! fetch_headers "$base_url/" "$root_headers"; then
  record_failure "HTTPS root response could not be collected"
else
  hsts="$(header_value "$root_headers" "Strict-Transport-Security")"
  csp="$(header_value "$root_headers" "Content-Security-Policy")"
  csp_report_only="$(header_value "$root_headers" "Content-Security-Policy-Report-Only")"
  referrer="$(header_value "$root_headers" "Referrer-Policy")"
  nosniff="$(header_value "$root_headers" "X-Content-Type-Options")"
  frame="$(header_value "$root_headers" "X-Frame-Options")"
  permissions="$(header_value "$root_headers" "Permissions-Policy")"

  [[ "$hsts" == *"max-age=31536000"* ]] && record_pass "HSTS is present" || record_failure "HSTS max-age=31536000 is missing"
  [[ "$csp" == *"default-src 'self'"* && "$csp" == *"object-src 'none'"* ]] \
    && record_pass "enforced CSP is present" || record_failure "enforced CSP is missing"
  [ -z "$csp_report_only" ] && record_pass "report-only CSP is not used" || record_failure "report-only CSP remains enabled"
  [ "$referrer" = "no-referrer" ] && record_pass "Referrer-Policy is no-referrer" || record_failure "Referrer-Policy is not no-referrer"
  [ "$nosniff" = "nosniff" ] && record_pass "nosniff is present" || record_failure "nosniff is missing"
  [ "$frame" = "DENY" ] && record_pass "framing is denied" || record_failure "X-Frame-Options DENY is missing"
  [[ "$permissions" == *"camera=()"* && "$permissions" == *"microphone=()"* && "$permissions" == *"geolocation=()"* ]] \
    && record_pass "Permissions-Policy disables unused sensors" || record_failure "Permissions-Policy is incomplete"
fi

csrf_headers="$evidence_dir/csrf.headers"
if ! fetch_headers "$base_url/api/v1/csrf" "$csrf_headers"; then
  record_failure "CSRF/session response could not be collected"
else
  session_cookie="$(awk '
    index(tolower($0), "set-cookie: tieat_session=") == 1 {
      sub(/^Set-Cookie:[[:space:]]*/, "")
      sub(/\r$/, "")
      print
      exit
    }
  ' "$csrf_headers")"
  if [ -z "$session_cookie" ]; then
    record_failure "TIEAT_SESSION cookie was not issued"
  else
    cookie_attributes=";${session_cookie#*;}"
    [[ "$cookie_attributes" == *"; Secure"* ]] && record_pass "session cookie is Secure" || record_failure "session cookie Secure is missing"
    [[ "$cookie_attributes" == *"; HttpOnly"* ]] && record_pass "session cookie is HttpOnly" || record_failure "session cookie HttpOnly is missing"
    [[ "$cookie_attributes" == *"; SameSite=Lax"* ]] && record_pass "session cookie SameSite is Lax" || record_failure "session cookie SameSite=Lax is missing"
    [[ "$cookie_attributes" == *"; Path=/"* ]] && record_pass "session cookie Path is root" || record_failure "session cookie Path=/ is missing"
  fi
fi

for protected_path in "/v3/api-docs" "/actuator/health" "/actuator/info"; do
  status_code="$(curl --silent --show-error --max-time 15 --output /dev/null --write-out '%{http_code}' "$base_url$protected_path")" || status_code="000"
  case "$status_code" in
    401|403|404)
      record_pass "$protected_path is not publicly readable"
      ;;
    *)
      record_failure "$protected_path returned public status $status_code"
      ;;
  esac
done

if [ "$failed" -ne 0 ]; then
  echo "Deployment security verification failed. No response body, cookie value, or QR token was printed."
  exit 1
fi

echo "Deployment security verification passed for the checked HTTP boundary."
echo "Azure IAM, secret binding, logs, alerts, backups, restore, and QR-token redaction still require separate evidence."
