#!/usr/bin/env bash
#
# Start the whole local Niord stack with one command:
#   - the docker-compose services (PostgreSQL/PostGIS, Keycloak, MailHog)  be/docker-compose.yml
#   - the Spring Boot backend be/niord-app (the generic English application)  http://localhost:${BE_PORT:-8888}/rest
#   - the Angular dev server(s): the administration app (fe/ui-apps/admin)   http://localhost:${FE_PORT:-4100}
#     and/or the public site (fe/ui-apps/info)                                http://localhost:${INFO_PORT:-4400}
#
#   ./start-local.sh                 niord-app, database `niord`, files in ~/.niord-ng
#   ./start-local.sh --info          the PUBLIC site (fe/ui-apps/info) instead of the administration app,
#                                    on port 4400 + slot
#   ./start-local.sh --admin --info  BOTH frontends against the one backend: the administration app on
#                                    4100 + slot, the public site on 4400 + slot
#   ./start-local.sh --sample        the sample deployment in one command (below): database `niord_sample`,
#                                    files in ~/.niord-ng-sample, both frontends
#   ./start-local.sh --sample --reset
#                                    ... after dropping the database niord_sample (and nothing else, through
#                                    the library's sample_reset): after a git pull that changed
#                                    be/niord/sample, or a bootstrap that failed
#
# The backend builds the bare Liquibase schema (db.changelog-master.yaml), and a new database holds no data
# at all until it is bootstrapped, once. When the backend is up, the script asks it whether that is still to
# be done (GET /rest/bootstrap, as the local realm's sysadmin) and, on a new database, runs the bootstrap
# command (be/scripts/bootstrap.sh) with the base product's bootstrap: be/niord/bootstrap/, zipped into
# .local-run/bootstrap-1.zip. A database that already holds data, bootstrapped before or loaded by you,
# is never bootstrapped. The database is created in the niord-db container when it does not exist yet.
#
# --sample is the sample deployment (docs/fe-country-split-plan.md P9): the containers, an empty database
# niord_sample (created when missing), the backend, the base product's bootstrap, then the sample
# (be/niord/sample/: Danish domains, series, charts, areas, publications and messages titled "SAMPLE —")
# loaded with psql by be/niord/scripts/load-sample.sh, the backend started again so that its caches and
# message index see the sample, and only then both frontends. Re-running it on a loaded niord_sample starts
# it again (the sample's attachments are rows of the database, so nothing is copied into the Niord home); the
# sample is never loaded twice, so a be/niord/sample changed by a git pull needs
# --reset, which drops niord_sample first, through the library's sample_reset (a regeneration on this machine
# drops it itself). A bootstrap that failed or broke off is never run again on niord_sample: --reset is the remedy
# too.
# --sample implies --admin --info unless one of them is given (`--sample --info` is the public site alone,
# without an adminAppUrl, for the CSP run of fe/docs/testing.md). It refuses --fe-only, --own-db,
# any NIORD_DB but niord_sample and any NIORD_HOME_DIR but ~/.niord-ng-sample. The sample users are the
# local realm's sysadmin, admin, editor and user, each with its name as password. A local trial on this
# machine only: Keycloak runs in development mode with known passwords; never expose it. A country deploys
# with the chart.
#
# Several checkouts side by side (one git worktree per branch, see ./new-worktree.sh):
#   ./start-local.sh --fe-only          only the dev server, proxying /rest to a backend that already runs
#                                       (BE_PORT, default 8888) — all a frontend-only branch needs
#   ./start-local.sh --slot 2           a whole second stack: frontend 4102, backend 8890, the same
#                                       database and files as slot 0, its own message index and its own
#                                       Maven install target, so neither build overwrites the other's jar
#   ./start-local.sh --slot 2 --own-db  ... on a private copy of the database (${NIORD_DB}_slot2, copied
#                                       on first use) — for a branch that adds a Liquibase changeset
# A worktree made by ./new-worktree.sh records its slot in .local-run/slot, and --slot defaults to it. Two
# backends on one database both run the scheduled jobs; that is harmless locally but shows in the logs.
#
# Everything listens on loopback only. The containers niord-db, niord-keycloak and niord-smtp are shared by every
# checkout and every country's launcher: a running one is never stopped, restarted or recreated (only a missing or
# stopped one is started), and the realm is copied to ~/.niord-local/keycloak/ first, where the compose file mounts
# it from. Ctrl+C stops the backend and frontend and leaves the containers running; their data lives in named
# volumes, so it also survives `docker compose down`. Stop the containers with
#   docker compose -f be/docker-compose.yml stop
# and wipe every database + the Keycloak state with
#   docker compose -f be/docker-compose.yml down -v
#
# Environment overrides:
#   JAVA_HOME       a JDK 25+, not a JRE (default when unset, too old or a JRE: ~/.jdks/temurin-25); java
#                   on the PATH is not read
#   FE_PORT         administration dev server port (default 4100 + slot); with --info alone, the
#                   public site's port (default 4400 + slot)
#   INFO_PORT       public site's port when both frontends run (default 4400 + slot)
#   BE_PORT         backend port (default 8888 + slot; with --fe-only 8888, the backend to proxy to)
#   NIORD_DB        database name in the niord-db container (default niord; --sample takes niord_sample only)
#   NIORD_HOME_DIR  the backend's file-system root, `niord.home` (default ~/.niord-ng; --sample takes
#                   ~/.niord-ng-sample only). The checkout and the Niord home need a path without
#                   whitespace: the JVM and Maven arguments split at a space.
#                   Deliberately NOT the ~/.niord of the legacy Quarkus backend: its Lucene 9.5 message index
#                   there makes this backend (Lucene 9.12) fail at startup with "Could not load codec 'Lucene95'".
#
# The backend JVM runs in UTC (-Duser.timezone=UTC). The database stores timestamps as wall-clock values
# without a zone, converted with the JVM's default time zone, and the test/production data was written by a
# UTC JVM (all-day periods start at 22:00/23:00). The domains' time zone still formats times for display.
#
# The backend's message links (mails, tweets) and its root redirect point at the administration app this
# run starts (-DadminAppUrl); with --info alone the links fall back to the baseUri setting.
#
# The backend runs in the `development` execution mode (-Dmode=development), not the `test` the base settings
# file sets (the code default is `production`); both frontends label a mode other than production with a ribbon.
#
# Runs in Git Bash on Windows as well as on macOS/Linux.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The country-neutral functions, which a country's launcher sources too
source "$ROOT/be/scripts/local-run-lib.sh"
LOG_DIR="$ROOT/.local-run"
BE_LOG="$LOG_DIR/backend.log"
FE_LOG="$LOG_DIR/frontend.log"
INFO_LOG="$LOG_DIR/info.log"

SAMPLE=false
RESET=false
INFO=false
ADMIN=false
FE_ONLY=false
OWN_DB=false
SLOT=""
while (( $# )); do
    arg="$1"; shift
    case "$arg" in
        --sample) SAMPLE=true ;;
        --reset) RESET=true ;;
        --demo) echo "--demo was removed in P9: use --sample (the sample deployment)." >&2; exit 2 ;;
        --dk|--gl) echo "$arg was removed in P7: a country runs from its own repository: in SIFA-DK/niord-${arg#--}, ./start-local.sh starts its backend and frontend (its README)." >&2; exit 2 ;;
        --info) INFO=true ;;
        --admin) ADMIN=true ;;
        --fe-only) FE_ONLY=true ;;
        --own-db) OWN_DB=true ;;
        --slot) SLOT="${1:-}"; shift || true ;;
        --slot=*) SLOT="${arg#--slot=}" ;;
        -h|--help) sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p}' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "Unknown argument: $arg (see --help)" >&2; exit 2 ;;
    esac
done

if $RESET && ! $SAMPLE; then
    echo "--reset drops the sample's database and goes with --sample only." >&2
    exit 2
fi
# The sample is loaded into one loopback database, niord_sample, by this repository's backend (P9's safety rules).
if $SAMPLE; then
    if $FE_ONLY || $OWN_DB; then
        echo "--sample starts its own backend on the database niord_sample: it cannot be combined with --fe-only or --own-db." >&2
        exit 2
    fi
    if [[ -n "${NIORD_DB:-}" && "$NIORD_DB" != niord_sample ]]; then
        echo "--sample uses the database niord_sample only, not NIORD_DB=$NIORD_DB." >&2
        exit 2
    fi
    # The same for the Niord home: a NIORD_HOME_DIR exported for the plain run would have its message index and
    # its caches rebuilt from niord_sample and lend its niord.json to the sample run.
    if [[ -n "${NIORD_HOME_DIR:-}" && "$NIORD_HOME_DIR" != "$HOME/.niord-ng-sample" ]]; then
        echo "--sample uses the Niord home ~/.niord-ng-sample only, not NIORD_HOME_DIR=$NIORD_HOME_DIR: unset it." >&2
        exit 2
    fi
    NIORD_DB=niord_sample
    NIORD_HOME_DIR="$HOME/.niord-ng-sample"
    if ! $ADMIN && ! $INFO; then
        ADMIN=true
        INFO=true
    fi
fi

# The slot keeps side-by-side checkouts apart: it offsets the ports and names what a second backend must not
# share. Slot 0 is the plain single-checkout run.
if [[ -z "$SLOT" && -f "$LOG_DIR/slot" ]]; then
    SLOT="$(tr -d '[:space:]' <"$LOG_DIR/slot")"
fi
SLOT="${SLOT:-0}"
[[ "$SLOT" =~ ^[0-9]$ ]] || { echo "--slot must be a single digit 0-9, got '$SLOT'" >&2; exit 2; }
if $OWN_DB && { [[ "$SLOT" == 0 ]] || $FE_ONLY; }; then
    echo "--own-db needs a --slot above 0 and cannot be combined with --fe-only." >&2
    exit 2
fi
# The public site has a port range of its own, so it can run beside the administration app of the
# same slot — one number per worktree slot in each range. --admin alone is the default spelled out;
# --info alone runs the public site in the administration app's place; both run both.
BOTH=false
if $INFO && $ADMIN; then
    BOTH=true
    FE_PORT="${FE_PORT:-$((4100 + SLOT))}"
    INFO_PORT="${INFO_PORT:-$((4400 + SLOT))}"
elif $INFO; then
    FE_PORT="${FE_PORT:-$((4400 + SLOT))}"
    INFO_PORT="$FE_PORT"
else
    FE_PORT="${FE_PORT:-$((4100 + SLOT))}"
    INFO_PORT=""
fi
if $FE_ONLY; then
    BE_PORT="${BE_PORT:-8888}"
else
    BE_PORT="${BE_PORT:-$((8888 + SLOT))}"
fi
BE_URL="http://127.0.0.1:$BE_PORT/rest/settings/web"
# The dev-server proxy (fe/proxy.conf.mjs) reads the backend port from here.
export NIORD_BE_PORT="$BE_PORT"

# `$INFO && echo a || echo b` is safe under `set -e` inside a command substitution: the `||` branch
# always succeeds, so the substitution never carries a failing status out.
APP=niord-app
NIORD_DB="${NIORD_DB:-niord}"
NIORD_HOME_DIR="${NIORD_HOME_DIR:-$HOME/.niord-ng}"
FE_SCRIPT=$($INFO && ! $BOTH && echo start:info || echo start:local)
INFO_SCRIPT=start:info

BASE_DB="$NIORD_DB"
if $OWN_DB; then NIORD_DB="${NIORD_DB}_slot$SLOT"; fi

[[ "$NIORD_DB" =~ ^[a-z_][a-z0-9_]*$ ]] || die "NIORD_DB must be a plain lower-case identifier, got '$NIORD_DB'."

# The backend's JVM arguments and Maven's MAVEN_ARGS are split at every space, so a path with whitespace in
# them would break the run later with a misleading error.
if ! $FE_ONLY; then
    [[ "$ROOT" =~ [[:space:]] ]] && die "The checkout's path contains whitespace ($ROOT): the backend's JVM and Maven arguments split at a space. Clone it into a path without."
    [[ "$NIORD_HOME_DIR" =~ [[:space:]] ]] && die "NIORD_HOME_DIR contains whitespace ($NIORD_HOME_DIR): the backend's JVM arguments split at a space. Set it to a path without."
    if [[ "$SLOT" != 0 && "${USERPROFILE:-$HOME}" =~ [[:space:]] ]]; then
        die "The home folder's path contains whitespace (${USERPROFILE:-$HOME}): a slot's MAVEN_ARGS name the Maven repository under it and split at a space."
    fi
fi

# --- prerequisites -----------------------------------------------------------------------------------

$FE_ONLY || use_jdk25

if ! $FE_ONLY; then
    command -v docker >/dev/null 2>&1 || die "docker not found on PATH."
    docker info >/dev/null 2>&1 || die "Docker is not running — start Docker Desktop and retry."
fi
command -v npm >/dev/null 2>&1 || die "npm not found on PATH."
command -v curl >/dev/null 2>&1 || die "curl not found on PATH."

if $FE_ONLY; then
    port_in_use "$BE_PORT" || echo "!! Nothing listens on port $BE_PORT yet — /rest calls fail until a backend runs there." >&2
else
    port_in_use "$BE_PORT" && die "Port $BE_PORT is already in use — is a backend still running? (another checkout: --slot <n> or --fe-only)"
fi
port_in_use "$FE_PORT" && die "Port $FE_PORT is already in use — pick another with --slot <n> or FE_PORT=<port> $0"
$BOTH && port_in_use "$INFO_PORT" && die "Port $INFO_PORT is already in use — pick another with --slot <n> or INFO_PORT=<port> $0"

mkdir -p "$NIORD_HOME_DIR" "$LOG_DIR"
NIORD_HOME_NATIVE="$(native_path "$NIORD_HOME_DIR")"

# The backend installs org.niord.base:niord-base:${revision} before it runs — so two checkouts sharing ~/.m2
# would run each other's code. A slot installs into a repository of its own and reads everything else from
# the shared one (Maven's local-repository tail).
# NIORD_M2_REPO tells be/run.sh and the install check below where the installed jar is.
if [[ "$SLOT" != 0 ]] && ! $FE_ONLY; then
    SHARED_M2="$(native_path "${USERPROFILE:-$HOME}")/.m2/repository"
    export NIORD_M2_REPO
    NIORD_M2_REPO="$(native_path "$LOG_DIR")/m2"
    export MAVEN_ARGS="${MAVEN_ARGS:-} -Dmaven.repo.local=$NIORD_M2_REPO -Dmaven.repo.local.tail=$SHARED_M2"
fi

if [[ ! -d "$ROOT/fe/node_modules" ]]; then
    echo ">> Installing frontend dependencies (npm ci) ..."
    (cd "$ROOT/fe" && npm ci)
fi

# --- process handling --------------------------------------------------------------------------------

BE_PID=""
FE_PID=""
INFO_PID=""

cleanup() {
    trap - INT TERM EXIT
    echo
    echo ">> Stopping frontend and backend ..."
    kill_tree "$FE_PID"
    kill_tree "$INFO_PID"
    kill_tree "$BE_PID"
    # The log followers.
    local job
    for job in $(jobs -p); do kill "$job" 2>/dev/null || true; done
    echo ">> Stopped. The containers keep running: docker compose -f be/docker-compose.yml stop"
}
trap cleanup EXIT
# A trap handler returns to the interrupted loop unless it exits itself.
trap 'cleanup; exit 130' INT TERM

FE_URL="http://localhost:$FE_PORT/"
INFO_URL=""
$BOTH && INFO_URL="http://localhost:$INFO_PORT/"

# --- backends ----------------------------------------------------------------------------------------

JVM_ARGS="-Duser.timezone=UTC -Dserver.port=$BE_PORT -Dniord.home=$NIORD_HOME_NATIVE -Dspring.datasource.url=jdbc:postgresql://localhost:5432/$NIORD_DB"
# The execution mode: a system property wins over the stored setting, in the backend and in the web settings
# both frontends read their deployment ribbon from.
JVM_ARGS="$JVM_ARGS -Dmode=development"
# The message index is rebuilt from the database at startup and DELETED first, so a second backend on the same
# niord.home gets a folder of its own; everything else under niord.home (the repository files) is shared.
# A system property wins over the stored setting.
if [[ "$SLOT" != 0 ]]; then
    JVM_ARGS="$JVM_ARGS -DmessageIndexPath=$NIORD_HOME_NATIVE/message-index-slot$SLOT"
fi
# Message links in the backend's mails and tweets, and its root redirect, go to the administration app when
# this run starts one (the adminAppUrl setting; --info alone starts the public site in its place).
if ! $INFO || $BOTH; then
    JVM_ARGS="$JVM_ARGS -DadminAppUrl=http://localhost:$FE_PORT"
fi
# The sample deployment sends nothing out: SECOM's outbox dispatch is off, spelled out rather than left to the
# default, and mail already goes to the loopback MailHog (niord-app's application-local.yml).
if $SAMPLE; then
    JVM_ARGS="$JVM_ARGS -Dniord.secom.enabled=false"
fi

# niord-app: be/run.sh does the install + run (and its classpath swap lets ./reload.sh hot-restart edits).
# Its `local` profile uses the bare master changelog; the data comes from the bootstrap below.
start_app_backend() {
    exec ./run.sh "-Dspring-boot.run.jvmArguments=$JVM_ARGS"
}
# The log is started afresh by the run, and a second start (--sample's, after the load) appends to it.
start_backend() {
    ( cd "$ROOT/be" && start_app_backend ) >>"$BE_LOG" 2>&1 &
    BE_PID=$!
}

# --- bootstrap ---------------------------------------------------------------------------------------

# bootstrap_database (be/scripts/local-run-lib.sh) posts the base product's bootstrap, be/niord/bootstrap/ zipped
# into .local-run/bootstrap-1.zip, only on a database whose marker reads pending, and names this remedy for one
# that failed or broke off: the database recreated empty. Under --sample that is ./start-local.sh --sample --reset;
# otherwise the backend stopped and the database dropped, which the next start creates empty and bootstraps. The
# script never drops a database itself, apart from --reset's niord_sample (through sample_reset).
if $SAMPLE; then
    recreate="./start-local.sh --sample --reset starts niord_sample afresh"
else
    recreate="stop the backend, then docker exec niord-db dropdb -U niord $NIORD_DB; the next ./start-local.sh creates it empty and bootstraps it"
fi

# --- start -------------------------------------------------------------------------------------------

# The containers are shared by every checkout and every country's launcher, so a running one is never stopped,
# restarted or recreated: start_services (be/scripts/local-run-lib.sh) issues no compose command while all three
# run and starts only a missing or stopped one, after copying the realm to the one path the compose file mounts.
# An edited docker-compose.yml therefore reaches a running container only by a hand `docker compose up -d <service>`.
if ! $FE_ONLY; then

echo ">> Checking the shared containers (niord-db, niord-keycloak, niord-smtp)"
start_services "$ROOT/be/docker-compose.yml" "$ROOT/be/keycloak/niord-realm-local.json"

# --reset drops the sample's database and no other, through sample_reset (be/scripts/local-run-lib.sh): the name is
# spelled out, never taken from NIORD_DB.
if $RESET; then
    sample_reset niord_sample
fi

ensure_database "$NIORD_DB"
if $DATABASE_CREATED && $OWN_DB; then
    # Dump and restore rather than `createdb -T $BASE_DB`: a template copy is refused while another
    # backend is connected to the source database.
    echo ">> Copying $BASE_DB into $NIORD_DB (well under a minute for the Danish test data; two 'schema already exists' errors come from the PostGIS template)"
    docker exec niord-db sh -c "pg_dump -U niord -d $BASE_DB | psql -q -U niord -d $NIORD_DB >/dev/null"
fi

echo ">> Starting $APP backend on database $NIORD_DB — the first run also builds (~5 min)"
: >"$BE_LOG"
start_backend

fi

start_frontends() {
    echo ">> Starting frontend on port $FE_PORT ($FE_SCRIPT)"
    ( cd "$ROOT/fe" && exec npm run "$FE_SCRIPT" -- --port "$FE_PORT" ) >"$FE_LOG" 2>&1 &
    FE_PID=$!
    if $BOTH; then
        echo ">> Starting the public site on port $INFO_PORT ($INFO_SCRIPT)"
        ( cd "$ROOT/fe" && exec npm run "$INFO_SCRIPT" -- --port "$INFO_PORT" ) >"$INFO_LOG" 2>&1 &
        INFO_PID=$!
    fi
}
wait_for_frontends() {
    wait_for "frontend" "$FE_PID" "$FE_URL" 300 "$FE_LOG"
    echo ">> Frontend is up"
    if $BOTH; then
        wait_for "public site" "$INFO_PID" "$INFO_URL" 300 "$INFO_LOG"
        echo ">> Public site is up"
    fi
}

if $SAMPLE; then
    # The frontends start once the sample is in, so nothing writes to niord_sample between the bootstrap and
    # the load.
    wait_for "backend" "$BE_PID" "$BE_URL" 900 "$BE_LOG"
    echo ">> Backend is up"
    bootstrap_database "http://127.0.0.1:$BE_PORT" "$NIORD_DB" "$recreate" "$BE_LOG" "$LOG_DIR" "$ROOT/be/niord/bootstrap"
    sample_load "$ROOT/be/niord/scripts/load-sample.sh" niord_sample "$NIORD_HOME_NATIVE"
    start_frontends
    wait_for_frontends
else
    # The frontends build while the backend starts.
    start_frontends
    wait_for_frontends
    if ! $FE_ONLY; then
        wait_for "backend" "$BE_PID" "$BE_URL" 900 "$BE_LOG"
        echo ">> Backend is up"
        bootstrap_database "http://127.0.0.1:$BE_PORT" "$NIORD_DB" "$recreate" "$BE_LOG" "$LOG_DIR" "$ROOT/be/niord/bootstrap"
    fi
fi

cat <<EOF

  Niord is running (loopback only) — slot $SLOT$($FE_ONLY && echo ', frontend only')
    Application $APP
    Frontend    http://localhost:$FE_PORT          $($INFO && ! $BOTH && echo 'the public site' || echo 'log in as sysadmin / sysadmin')
$($BOTH && echo "    Public site http://localhost:$INFO_PORT")
    Backend     http://localhost:$BE_PORT/rest$($FE_ONLY && echo '     (not started here)')
    Keycloak    http://localhost:8080/auth     admin console: admin / admin
    MailHog     http://localhost:8025
    Database    $NIORD_DB  (psql: docker exec -it niord-db psql -U niord -d $NIORD_DB)
    Niord home  $NIORD_HOME_NATIVE
    Logs        $(native_path "$BE_LOG")
                $(native_path "$FE_LOG")$($BOTH && printf '\n                %s' "$(native_path "$INFO_LOG")")

EOF
if $SAMPLE; then
    cat <<EOF
  The sample deployment: Danish sample content, every message titled "SAMPLE —" (the Danish texts show
  once a sysadmin adds da to modelLanguages on the Settings page and reloads the page).
    Sample logins  sysadmin / sysadmin, admin / admin, editor / editor, user / user
  A local trial on this machine only: Keycloak runs in development mode with known passwords.
  Never expose it. A country deploys with the chart.

EOF
fi
cat <<EOF
  Following both logs (Artemis audit lines hidden). Ctrl+C stops backend + frontend.

EOF

tail -n 0 -F "$BE_LOG" 2>/dev/null | grep --line-buffered -v 'activemq.audit' | awk '{ print "[be] " $0; fflush() }' &
tail -n 0 -F "$FE_LOG" 2>/dev/null | awk '{ print "[fe] " $0; fflush() }' &
# A pipeline of its own rather than `$BOTH && …  &`, whose background subshell would leave the tail behind on Ctrl+C.
if $BOTH; then
    tail -n 0 -F "$INFO_LOG" 2>/dev/null | awk '{ print "[info] " $0; fflush() }' &
fi

# Watch the ports, not the PIDs, and stop once one of the servers stopped answering (watch_servers).
servers=()
$FE_ONLY || servers+=(backend "$BE_URL" "$BE_LOG")
servers+=(frontend "$FE_URL" "$FE_LOG")
if $BOTH; then
    servers+=("public site" "$INFO_URL" "$INFO_LOG")
fi
watch_servers "${servers[@]}"
exit 1
