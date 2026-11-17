#!/usr/bin/env bash
#
# Bootstraps a new Niord database, once: posts the bootstrap archives to the backend's bootstrap command
# (POST /rest/bootstrap, SYSADMIN) and waits until the marker reads applied or failed
# (docs/code-data-config-plan.md §7.2, D4).
#
# Usage:  NIORD_TOKEN=<a sysadmin's access token> be/scripts/bootstrap.sh <base-url> <archive.zip>...
#
#   <base-url>     the backend, e.g. http://127.0.0.1:8888 (the script adds /rest/bootstrap)
#   <archive.zip>  the archives, applied in the order given: the base product's bootstrap first
#                  (be/niord/bootstrap/, zipped: jar --create --no-manifest --file b.zip -C be/niord/bootstrap .),
#                  then a country's (or, in be/niord/scripts/regen-sample.sh, the sample's archive). The command
#                  does not check the order: within the run a later archive's values replace an earlier one's, so a
#                  country's archive sent first would have its values replaced by the base product's.
#
# The token is read from NIORD_TOKEN, never from an argument, and handed to curl on its standard input, so it is
# in neither the script's nor curl's arguments, and not in a shell history. The command answers 503 while the
# backend is still starting (until every start-up listener has run, the default texts and settings included); the
# script posts again every 3 seconds, for at most 120 seconds, then exits 1. A bootstrap runs once, on a new, empty
# database: the command refuses at once, and the script exits 1, when the marker reads existing, applied, failed or
# in-progress (409, the state named), when the database holds data (409, the first table with a row named), when an
# archive breaks a rule or a later archive restates a domainId an earlier one gives, other than with its domainId and
# active flag only (400), or when the upload is larger than the server's multipart limit (413). It applies the jobs
# in the background; the script asks for the marker every few seconds until it reads applied (exit 0) or failed
# (exit 1, with the error), for at most NIORD_BOOTSTRAP_TIMEOUT seconds (default 1800), and prints the runner's note
# while the marker reads in-progress (a job past its deadline, asked to stop). A failed bootstrap, or one that broke
# off, is never retried on the same database: recreate the database empty, start the backend, and run the same
# command with the same archives.
#
set -euo pipefail

usage() {
    sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

[ $# -ge 2 ] || usage
BASE_URL="${1%/}"
shift
URL="$BASE_URL/rest/bootstrap"
TIMEOUT="${NIORD_BOOTSTRAP_TIMEOUT:-1800}"

if [ -z "${NIORD_TOKEN:-}" ]; then
    echo "!! NIORD_TOKEN is not set: the bootstrap command needs a sysadmin's access token" >&2
    exit 2
fi

FORM=()
for archive in "$@"; do
    [ -f "$archive" ] || { echo "!! No such archive: $archive" >&2; exit 2; }
    FORM+=(-F "archive=@$archive;type=application/zip")
done

BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

# Prints the "state" (or "detail", "error") string of the JSON answer in $BODY, its escapes left as they are
json_string() {
    tr -d '\n' < "$BODY" | sed -nE 's/.*"'"$1"'" *: *"(([^"\\]|\\.)*)".*/\1/p'
}

# Runs curl with the token's header read from standard input (a curl config), never from curl's arguments
curl_authed() {
    curl -K - "$@" <<< "header = \"Authorization: Bearer $NIORD_TOKEN\""
}

echo ">> Posting $# archive(s) to $URL"
started=$((SECONDS + 120))
waiting=false
while :; do
    status=$(curl_authed -sS -o "$BODY" -w '%{http_code}' "${FORM[@]}" "$URL") || {
        echo "!! $URL could not be reached" >&2
        exit 1
    }
    # 503: the backend is still starting, and the command waits until every start-up listener has run
    [ "$status" = 503 ] || break
    if (( SECONDS >= started )); then
        echo "!! Refused (503) for 120 s: the backend is still starting; post again once it has started" >&2
        exit 1
    fi
    $waiting || echo ">> The backend is still starting (503): waiting"
    waiting=true
    sleep 3
done
case "$status" in
    202) echo ">> Accepted: the jobs run in the background" ;;
    400|409) echo "!! Refused ($status): $(json_string detail)" >&2; exit 1 ;;
    413) echo "!! Refused (413): an archive is larger than the server's multipart limit" \
              "(spring.servlet.multipart.max-file-size and max-request-size, 50 MB in niord-base's defaults):" \
              "raise both in the deployment's configuration, and the body limit of its proxy" >&2; exit 1 ;;
    401|403) echo "!! Refused ($status): the token is not a sysadmin's, or has expired" >&2; exit 1 ;;
    *) echo "!! Unexpected answer $status: $(cat "$BODY")" >&2; exit 1 ;;
esac

deadline=$((SECONDS + TIMEOUT))
last=""
note=""
while :; do
    status=$(curl_authed -sS -o "$BODY" -w '%{http_code}' "$URL") || status=000
    state=$(json_string state)
    if [ "$status" = 200 ] && [ "$state" != "$last" ]; then
        echo "   marker: $state"
        last="$state"
    fi
    # While in-progress, the error field holds the runner's note (a job past its deadline, asked to stop)
    if [ "$status" = 200 ] && [ "$state" = in-progress ]; then
        current=$(json_string error)
        if [ -n "$current" ] && [ "$current" != "$note" ]; then
            echo "   note: $current"
            note="$current"
        fi
    fi
    case "$state" in
        applied) echo ">> Bootstrap applied"; exit 0 ;;
        failed) echo "!! Bootstrap failed: $(json_string error)" >&2; exit 1 ;;
    esac
    if [ "$status" = 401 ]; then
        echo "!! The token expired while the bootstrap ran; read the outcome with GET $URL" >&2
        exit 1
    fi
    if (( SECONDS >= deadline )); then
        echo "!! The bootstrap did not end within $TIMEOUT s (marker: ${state:-unknown}); read it with GET $URL" >&2
        exit 1
    fi
    sleep 3
done
