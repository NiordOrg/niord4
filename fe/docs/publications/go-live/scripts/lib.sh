#!/usr/bin/env bash
# Shared by the rehearsal scripts in this folder (GO-LIVE-PROCESS.md §3), which run
# in this order:
#
#   rehearsal-precheck.sh   steps 0-2, read-only
#   rehearsal-import.sh     steps 1-5: undo, BEFORE, dry run, import, import check, AFTER
#   rehearsal-activate.sh   step 7: activate every series in its OWNER domain
#   rehearsal-smoke.sh      step 8: the public list and a citation, from outside
#
# Nothing here is specific to one environment: the backend, the login server and
# the admin credentials come from the environment, and every response is written
# under $OUT so a loop leaves its evidence behind.
#
#   NIORD_API       the REST base, default the test backend
#   NIORD_AUTH      the Keycloak token endpoint, default the test realm
#   NIORD_USER      an admin user (sysadmin for the estate-wide calls)
#   NIORD_PASSWORD  its password -- never written into a script
#   OUT             where responses land, default a folder under the temp dir
#   REASON          the audited reason for the writes (activation), default dated
set -u

: "${NIORD_API:=https://niord.t-dma.dk/rest}"
: "${NIORD_AUTH:=https://login.t-dma.dk/auth/realms/niord/protocol/openid-connect/token}"
: "${NIORD_USER:?set NIORD_USER to an admin user}"
: "${NIORD_PASSWORD:?set NIORD_PASSWORD}"
: "${OUT:=${TMPDIR:-/tmp}/niord-rehearsal}"
: "${REASON:=Go-live rehearsal (GO-LIVE-PROCESS.md section 3), $(date +%F)}"
mkdir -p "$OUT"

# Git Bash on Windows: the PowerShell curl alias does not exist here, but the
# real binary is curl.exe and plain curl can be a shim that ignores -o.
CURL=curl
command -v curl.exe >/dev/null 2>&1 && CURL=curl.exe

A="$NIORD_API"

stamp() { date +%H:%M:%S; }

# An access token for NIORD_USER, written to $OUT/token.txt for the next script.
# Keycloak's niord-web client allows the password grant; the token lives 8 hours.
# Not in a realm the chart imported in production mode (be/k8s/README.md, "Keycloak in
# production mode"): switch on "Direct access grants" for niord-web there for the run.
fetch_token() {
  TOKEN=$($CURL -s -X POST "$NIORD_AUTH" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password" -d "client_id=niord-web" \
    -d "username=$NIORD_USER" -d "password=$NIORD_PASSWORD" \
    | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{try{process.stdout.write(JSON.parse(d).access_token||"")}catch(e){}})')
  [ -z "$TOKEN" ] && { echo "NO TOKEN for $NIORD_USER at $NIORD_AUTH"; exit 1; }
  echo "$TOKEN" > "$OUT/token.txt"
  H="Authorization: Bearer $TOKEN"
}

# Reading owners.txt: every loop below is written
#     while read -r DOM IDS || [ -n "${DOM:-}" ]; do ... done < "$OUT/owners.txt"
# and not a plain `while read`. read returns non-zero on a last line that has no
# trailing newline and a plain loop drops it -- which is how one loop activated
# twelve series and silently skipped the thirteenth.

# Snapshot the two public feeds as signatures (id + type per row), for diffing.
#
# The search call passes maxSize explicitly. /publications/search defaults to
# maxSize=100, and an estate of thousands snapshotted at the default compares one
# page against another -- ids can leave and enter the window without the diff
# saying so. High enough to hold the whole feed, so the count is the feed.
snap() { # label
  $CURL -s "$A/public/v1/publications?lang=da" -o "$OUT/feed-$1-public.json"
  $CURL -s "$A/publications/search?lang=da&maxSize=100000" -o "$OUT/feed-$1-search.json"
  grep -o '"publicationId":"[^"]*"\|"type":"[A-Z_]*"' "$OUT/feed-$1-public.json" | paste - - > "$OUT/sig-$1-public.txt"
  grep -o '"publicationId":"[^"]*"\|"type":"[A-Z_]*"' "$OUT/feed-$1-search.json" | paste - - > "$OUT/sig-$1-search.txt"
  echo "  snapshot $1: public $(wc -l < "$OUT/sig-$1-public.txt") entries, search $(wc -l < "$OUT/sig-$1-search.txt") entries"
}

# Compare two snapshots. The legacy search feed is known to swap adjacent rows of
# equal priority between calls, so a differing ORDER with the same id set is
# reported as such rather than as a change.
compare() { # a b
  for F in public search; do
    if [ ! -s "$OUT/sig-$1-$F.txt" ]; then
      echo "  $F feed: no $1 snapshot in $OUT -- run the earlier script first"; continue
    fi
    if diff -q "$OUT/sig-$1-$F.txt" "$OUT/sig-$2-$F.txt" >/dev/null; then
      echo "  $F feed: $1 == $2 (ids, order, types)"
    elif diff -q <(sort "$OUT/sig-$1-$F.txt") <(sort "$OUT/sig-$2-$F.txt") >/dev/null; then
      echo "  $F feed: $1 ~ $2 (same rows, adjacent order differs -- the legacy tie-order wobble)"
    else
      echo "  $F feed: $1 != $2"; diff "$OUT/sig-$1-$F.txt" "$OUT/sig-$2-$F.txt" | head -8
    fi
  done
}
