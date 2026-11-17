#!/usr/bin/env bash
#
# Starts the stack the user guides' screenshots are taken on: the base product (niord-app, the
# administration app and the public site) on the database niord_guides, the scrubbed copy of the
# test data that make-guides-db.sh makes, with the Niord home ~/.niord-local/niord_guides that
# make-guides-home.sh makes, on slot 6, so it runs beside a checkout's own stack:
#
#   administration app  http://localhost:4106
#   public site         http://localhost:4406
#   backend             http://localhost:8894/rest
#   Keycloak            http://localhost:8080/auth   (the shared container; console admin / admin)
#   MailHog             http://localhost:8025        (the shared container)
#
#   bash start-rig.sh          from Git Bash on Windows, or any bash; takes no arguments
#
# It starts the backend as ./start-local.sh --slot 6 would, on niord_guides with the Niord home
# ~/.niord-local/niord_guides, builds both frontends once in the `local` configuration (the one ng serve
# uses) into .local-run/guides-dist/, and serves those files with serve-built.mjs on 4106 and
# 4406, passing /rest to 8894. No dev server runs: on 2026-11-11 one died four times while pages
# were loaded from it, and the launcher then stopped the whole stack. Here a part that fails never
# stops another: the file server is started again (it serves the same files), a backend that
# stops answering is reported with the time. It refuses
#   - any other database, home or port (NIORD_DB, NIORD_HOME_DIR, FE_PORT, INFO_PORT, BE_PORT set
#     to something else);
#   - a missing niord_guides: start-local.sh would create it empty and bootstrap it, and the
#     guides would show the bare base product;
#   - a niord_guides that still holds an e-mail address outside example.org in the users, the
#     contacts, the mail recipients or the comments' notification addresses: the scrub has not run
#     (the four local logins may also hold <login>@niord.local, see below).
# It reads niord_guides only, and no other database at all. Ctrl+C stops the backend and the file
# server; the containers keep running. The terminal shows the rig's own lines only; the backend's
# log is .local-run/backend.log. A change under fe/ reaches the pictures only after the rig is
# started again.
#
# Set NIORD_CHECKOUT to the base product's checkout when this file lies outside it; inside it, the
# checkout is the first folder above that holds start-local.sh.
set -euo pipefail

GUIDES_DB=niord_guides
GUIDES_HOME="$HOME/.niord-local/$GUIDES_DB"
SLOT=6

die() {
    echo "start-rig: $*" >&2
    exit 2
}

case "${1:-}" in
    -h|--help) sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p}' "${BASH_SOURCE[0]}"; exit 0 ;;
    "") ;;
    *) die "takes no arguments (got '$1'): the database, the home and the slot are fixed." ;;
esac

[[ -z "${NIORD_DB:-}" || "$NIORD_DB" == "$GUIDES_DB" ]] \
    || die "runs on the database $GUIDES_DB only, not NIORD_DB=$NIORD_DB: unset it."
[[ -z "${NIORD_HOME_DIR:-}" || "$NIORD_HOME_DIR" == "$GUIDES_HOME" ]] \
    || die "runs with the Niord home $GUIDES_HOME only, not NIORD_HOME_DIR=$NIORD_HOME_DIR: unset it."
for port in FE_PORT INFO_PORT BE_PORT; do
    [[ -z "${!port:-}" ]] || die "$port is set (${!port}): the rig's ports follow slot $SLOT; unset it."
done

# The checkout: NIORD_CHECKOUT, or the first folder above this file that holds start-local.sh.
ROOT="${NIORD_CHECKOUT:-}"
if [[ -z "$ROOT" ]]; then
    dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    while [[ "$dir" != "/" && ! -f "$dir/start-local.sh" ]]; do dir="$(dirname "$dir")"; done
    [[ -f "$dir/start-local.sh" ]] && ROOT="$dir"
fi
[[ -n "$ROOT" && -f "$ROOT/start-local.sh" ]] \
    || die "cannot find start-local.sh: set NIORD_CHECKOUT to the base product's checkout."

command -v docker >/dev/null 2>&1 || die "docker not found on PATH."
docker info >/dev/null 2>&1 || die "Docker is not running: start Docker Desktop and retry."
[[ "$(docker inspect -f '{{.State.Running}}' niord-db 2>/dev/null || true)" == "true" ]] \
    || die "the container niord-db is not running: docker start niord-db (or run ./start-local.sh once)."

exists="$(docker exec niord-db psql -U niord -d postgres -Atc \
    "select 1 from pg_database where datname = '$GUIDES_DB'")" \
    || die "could not list the databases in niord-db."
[[ "$exists" == "1" ]] \
    || die "the database $GUIDES_DB does not exist: make it first with make-guides-db.sh (start-local.sh would create it empty and bootstrap it)."

# Personal data the scrub replaces: every e-mail address is an example.org one afterwards. A column
# that is gone (a newer schema) fails the query, and the rig refuses rather than guesses. The four
# local logins are the exception: a sign-in copies the address the shared Keycloak holds into the
# user's row, and a Keycloak that imported an older local realm holds <login>@niord.local, an
# invented address of the same kind. A mail the rig's stack sent keeps its recipients as
# "Name <address>", and the address inside the brackets is the one checked.
foreign="$(docker exec -i niord-db psql -U niord -d "$GUIDES_DB" -At -v ON_ERROR_STOP=1 <<'SQL'
select
    (select count(*) from "User"
        where coalesce(email, '') <> '' and email not ilike '%@example.org'
          and not (username in ('sysadmin', 'admin', 'editor', 'user')
                   and lower(email) = username || '@niord.local'))
  + (select count(*) from "Contact"
        where coalesce(email, '') <> '' and email not ilike '%@example.org')
  + (select count(*) from "ScheduledMailRecipient"
        where coalesce(address, '') <> ''
          and regexp_replace(address, '^.*<([^>]*)>\s*$', '\1') not ilike '%@example.org')
  + (select count(*) from "Comment_emailAddresses"
        where coalesce("emailAddresses", '') <> '' and "emailAddresses" not ilike '%@example.org');
SQL
)" || die "could not read the e-mail columns of $GUIDES_DB (a changed schema?): check the scrub before starting."
[[ "$foreign" == "0" ]] \
    || die "$GUIDES_DB still holds $foreign e-mail address(es) outside example.org: run make-guides-db.sh again."

cat <<EOF

  The user guides' stack, slot $SLOT, database $GUIDES_DB, Niord home $GUIDES_HOME
    Administration app  http://localhost:$((4100 + SLOT))
    Public site         http://localhost:$((4400 + SLOT))
    Backend             http://localhost:$((8888 + SLOT))/rest
    Keycloak            http://localhost:8080/auth   admin console: admin / admin
    MailHog             http://localhost:8025
  Once the rig prints "The rig is up", take the pictures from fe/ in a second terminal:
    npm run shots:pilot        the pilot, into fe/guides-shots/.pilot/
    npm run shots              every manual's pictures, into guides/<manual>/images/

EOF

cd "$ROOT"
# The launcher library, which a launcher of its own sources: use_jdk25, start_services, port_in_use,
# serving, wait_for, kill_tree, native_path. It defines a die of its own, so the rig's is defined
# again after it.
source be/scripts/local-run-lib.sh
die() {
    echo "start-rig: $*" >&2
    exit 2
}

[[ "$ROOT" =~ [[:space:]] || "$GUIDES_HOME" =~ [[:space:]] || "${USERPROFILE:-$HOME}" =~ [[:space:]] ]] \
    && die "the checkout, the Niord home and the home folder need paths without whitespace: the backend's JVM and Maven arguments split at a space."
command -v npx >/dev/null 2>&1 || die "npx not found on PATH."
command -v curl >/dev/null 2>&1 || die "curl not found on PATH."
[[ -d "$ROOT/fe/node_modules" ]] || die "fe/node_modules is missing: run npm ci in fe/ first."

LOG_DIR="$ROOT/.local-run" # gitignored, as for start-local.sh
DIST="$LOG_DIR/guides-dist"
BE_PORT=$((8888 + SLOT))
FE_PORT=$((4100 + SLOT))
INFO_PORT=$((4400 + SLOT))
BE_URL="http://127.0.0.1:$BE_PORT/rest/settings/web"
BE_LOG="$LOG_DIR/backend.log"
WEB_LOG="$LOG_DIR/guides-web.log"
BUILD_LOG="$LOG_DIR/guides-build.log"
mkdir -p "$LOG_DIR" "$GUIDES_HOME"
HOME_NATIVE="$(native_path "$GUIDES_HOME")"

use_jdk25
start_services "$ROOT/be/docker-compose.yml" "$ROOT/be/keycloak/niord-realm-local.json"
for port in "$BE_PORT" "$FE_PORT" "$INFO_PORT"; do
    port_in_use "$port" && die "port $port is in use: stop the rig, or whatever listens there, first."
done

# The backend as ./start-local.sh --slot 6 starts it (its MAVEN_ARGS and JVM_ARGS lines; keep the
# two in step): its own Maven install target, its own message index, UTC, the development mode,
# links to the administration app.
export NIORD_M2_REPO
NIORD_M2_REPO="$(native_path "$LOG_DIR")/m2"
export MAVEN_ARGS="${MAVEN_ARGS:-} -Dmaven.repo.local=$NIORD_M2_REPO -Dmaven.repo.local.tail=$(native_path "${USERPROFILE:-$HOME}")/.m2/repository"
JVM_ARGS="-Duser.timezone=UTC -Dserver.port=$BE_PORT -Dniord.home=$HOME_NATIVE"
JVM_ARGS+=" -Dspring.datasource.url=jdbc:postgresql://localhost:5432/$GUIDES_DB -Dmode=development"
JVM_ARGS+=" -DmessageIndexPath=$HOME_NATIVE/message-index-slot$SLOT -DadminAppUrl=http://localhost:$FE_PORT"

BE_PID=""
WEB_PID=""
stop_all() {
    trap - INT TERM EXIT
    echo ">> Stopping the rig's backend and file server ..."
    kill_tree "$WEB_PID"
    kill_tree "$BE_PID"
    echo ">> Stopped. The containers keep running."
}
trap stop_all EXIT
# A trap handler returns to the interrupted loop unless it exits itself.
trap 'stop_all; exit 130' INT TERM

echo ">> $(date +%T) Starting the backend on $GUIDES_DB (log: $BE_LOG)"
(cd be && exec ./run.sh "-Dspring-boot.run.jvmArguments=$JVM_ARGS") >"$BE_LOG" 2>&1 &
BE_PID=$!

# Both frontends are built once, in the local configuration ng serve uses (unoptimised, development
# mode, environment.local.ts), while the backend starts. The pictures are taken of these files:
# nothing that changes under fe/ during the run reaches them.
echo ">> $(date +%T) Building the administration app and the public site (log: $BUILD_LOG)"
rm -rf "$DIST"
(
    cd fe && export NG_CLI_ANALYTICS=false \
        && node ../country-kit/stage-languages.mjs --app admin \
        && npx ng build niord-app --configuration local --output-path "$(native_path "$DIST/admin")" \
        && node ../country-kit/stage-languages.mjs --app info \
        && npx ng build niord-info --configuration local --output-path "$(native_path "$DIST/info")"
) >"$BUILD_LOG" 2>&1 || {
    tail -n 40 "$BUILD_LOG" >&2
    die "a frontend build failed (above; $BUILD_LOG)."
}

start_web() {
    node fe/guides-shots/serve-built.mjs "$(native_path "$DIST/admin/browser")" "$FE_PORT" \
        "$(native_path "$DIST/info/browser")" "$INFO_PORT" "$BE_PORT" >>"$WEB_LOG" 2>&1 &
    WEB_PID=$!
}
: >"$WEB_LOG"
start_web
wait_for "administration app" "$WEB_PID" "http://localhost:$FE_PORT/" 30 "$WEB_LOG"
wait_for "public site" "$WEB_PID" "http://localhost:$INFO_PORT/" 30 "$WEB_LOG"
wait_for "backend" "$BE_PID" "$BE_URL" 900 "$BE_LOG"
echo ">> $(date +%T) The rig is up: http://localhost:$FE_PORT, http://localhost:$INFO_PORT, http://localhost:$BE_PORT/rest"
echo "   Logs: $BE_LOG, $WEB_LOG. Take the pictures from fe/ in a second terminal."

# The watch. Nothing is ever stopped because another part failed. A file server that fails three
# polls in a row is started again: it serves the same files, so no picture changes. A backend that
# fails three polls is reported with the time and left alone; the pictures taken meanwhile fail and
# are taken again with `npm run shots -- --last-failed`.
down_web=0
down_be=0
while :; do
    sleep 3
    if serving "http://localhost:$FE_PORT/" && serving "http://localhost:$INFO_PORT/"; then
        down_web=0
    else
        down_web=$((down_web + 1))
    fi
    if ((down_web >= 3)); then
        echo "!! $(date +%T) the file server stopped answering: starting it again (log: $WEB_LOG)" >&2
        kill_tree "$WEB_PID"
        start_web
        down_web=0
    fi
    if serving "$BE_URL"; then
        if ((down_be >= 3)); then echo ">> $(date +%T) the backend answers again"; fi
        down_be=0
    else
        down_be=$((down_be + 1))
        if ((down_be == 3)); then echo "!! $(date +%T) the backend stopped answering: see $BE_LOG" >&2; fi
    fi
done
