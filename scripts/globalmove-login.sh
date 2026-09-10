#!/usr/bin/env bash
#
# globalmove-login.sh - one-time interactive helper to capture an
# authenticated The Global Move (globalmove.relocate.me) session.
#
# Passwordless magic-link flow. Sends a login email, prompts you to paste the
# link from that email, then prints shell-exportable cookie values.
#
# Usage:
#   scripts/globalmove-login.sh [email]
#   GLOBALMOVE_EMAIL=you@example.com scripts/globalmove-login.sh
#
set -euo pipefail

BASE_URL="https://globalmove.relocate.me"
LOGIN_URL="$BASE_URL/login"
MAGIC_LINK_URL="$BASE_URL/magic-link"
AUTH_SUCCESS_URL="$BASE_URL/jobs"

UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

DEFAULT_EMAIL="maurya.bitlegacy@gmail.com"
EMAIL="${1:-${GLOBALMOVE_EMAIL:-$DEFAULT_EMAIL}}"

if [[ "$EMAIL" == "-h" || "$EMAIL" == "--help" ]]; then
  printf 'Usage: %s [email]\n\n' "$0"
  printf '  email   Subscriber address (default: %s)\n' "$DEFAULT_EMAIL"
  printf '          Can also be set via GLOBALMOVE_EMAIL.\n'
  exit 0
fi

if [[ -z "${EMAIL// /}" ]]; then
  printf 'ERROR: no email supplied. Pass one as $1 or set GLOBALMOVE_EMAIL.\n' >&2
  exit 1
fi

TMPDIR_RUN="$(mktemp -d)"
COOKIE_JAR="$TMPDIR_RUN/cookies.txt"
LOGIN_HTML="$TMPDIR_RUN/login.html"
RESP_BODY="$TMPDIR_RUN/body.txt"
trap 'rm -rf "$TMPDIR_RUN"' EXIT

log()  { printf '%s\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# Read a cookie value from a Netscape cookie jar by name (last match wins).
get_cookie() {
  local name="$1"
  awk -F'\t' -v n="$name" '$6 == n { v = $7 } END { print v }' "$COOKIE_JAR"
}

url_decode() {
  local value="$1"
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import sys, urllib.parse; sys.stdout.write(urllib.parse.unquote(sys.argv[1]))' "$value"
  else
    printf '%b' "${value//%/\\x}"
  fi
}

log "The Global Move login helper"
log "  account: $EMAIL"
log ""

# --- Step 1: prime the session, obtain XSRF + session cookies ---------------
log "[1/4] Requesting $LOGIN_URL ..."
curl -sS -L \
  -A "$UA" \
  -H 'Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8' \
  -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
  -o "$LOGIN_HTML" \
  "$LOGIN_URL" || die "failed to reach $LOGIN_URL"

XSRF_COOKIE="$(get_cookie "XSRF-TOKEN")"
[[ -n "$XSRF_COOKIE" ]] || die "no XSRF-TOKEN cookie was set by $LOGIN_URL"
XSRF_HEADER="$(url_decode "$XSRF_COOKIE")"

# Inertia version is embedded in the login page; header is advisory, not fatal.
INERTIA_VERSION="$(grep -oE '"version":"[0-9a-f]{32}"' "$LOGIN_HTML" 2>/dev/null | head -1 | sed -E 's/.*"version":"([0-9a-f]{32})".*/\1/' || true)"
if [[ -z "$INERTIA_VERSION" ]]; then
  log "      note: could not scrape X-Inertia-Version; continuing without it"
fi

# --- Step 2: request the magic link ----------------------------------------
log "[2/4] Requesting magic link for $EMAIL ..."
HTTP_CODE="$(curl -sS \
  -A "$UA" \
  -X POST "$MAGIC_LINK_URL" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H 'X-Inertia: true' \
  ${INERTIA_VERSION:+-H "X-Inertia-Version: $INERTIA_VERSION"} \
  -H "X-XSRF-TOKEN: $XSRF_HEADER" \
  -H "Referer: $LOGIN_URL" \
  -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
  --data-raw "{\"email\":\"$EMAIL\"}" \
  -o "$RESP_BODY" \
  -w '%{http_code}' )" || die "magic-link request to $MAGIC_LINK_URL failed"

case "$HTTP_CODE" in
  302|303)
    log "      magic link sent. Check $EMAIL (arrives within seconds)."
    ;;
  419)
    die "CSRF check failed (HTTP 419). The XSRF cookie was likely not URL-decoded correctly."
    ;;
  *)
    log "      unexpected response body:"
    sed 's/^/        /' "$RESP_BODY" >&2 || true
    die "magic-link request returned HTTP $HTTP_CODE (expected 302)"
    ;;
esac

log ""
log "      Open the email and copy the full link that looks like:"
log "      $BASE_URL/magic-link/authenticate?email=...&expires=...&signature=..."
log ""
if ! read -r -p "Paste the magic link here: " MAGIC_URL; then
  die "no magic link provided (stdin closed)"
fi
[[ -n "${MAGIC_URL// /}" ]] || die "no magic link provided"

# --- Step 3: follow the magic link (establishes the authenticated session) --
log ""
log "[3/4] Following magic link ..."
RESULT="$(curl -sS -L --max-redirs 10 \
  -A "$UA" \
  -H 'Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8' \
  -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
  -o "$RESP_BODY" \
  -w '%{http_code} %{url_effective}' \
  "$MAGIC_URL")" || die "following the magic link failed"

FINAL_CODE="${RESULT%% *}"
FINAL_URL="${RESULT#* }"

if grep -q 'This magic link is invalid or has expired' "$RESP_BODY" 2>/dev/null; then
  die "magic link is invalid or has expired. Request a fresh link and run again."
fi

if [[ "$FINAL_CODE" != "200" || "$FINAL_URL" != "$AUTH_SUCCESS_URL"* ]]; then
  log "      final URL:  $FINAL_URL"
  log "      final code: $FINAL_CODE"
  die "authentication was not established (expected HTTP 200 at $AUTH_SUCCESS_URL)."
fi

log "      authenticated (landed on $FINAL_URL)"

# --- Step 4: emit the cookie values ----------------------------------------
SESSION_COOKIE="$(get_cookie "the-global-move-session")"
OUT_XSRF="$(get_cookie "XSRF-TOKEN")"
[[ -n "$SESSION_COOKIE" ]] || die "no the-global-move-session cookie found after authentication"
[[ -n "$OUT_XSRF" ]] || die "no XSRF-TOKEN cookie found after authentication"

log "[4/4] Done."
log ""
log "export GLOBALMOVE_SESSION_COOKIE='$SESSION_COOKIE'"
log "export GLOBALMOVE_XSRF_TOKEN='$OUT_XSRF'"
