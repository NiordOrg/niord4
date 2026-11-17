#!/usr/bin/env bash
#
# Start a country's whole local stack with one command, from the root of the country repository:
#   - the shared containers (PostgreSQL/PostGIS niord-db, Keycloak niord-keycloak, MailHog niord-smtp)
#   - the country's backend (be/, spring-boot:run)   http://localhost:${BE_PORT:-8888}/rest
#   - the administration app (fe/, start:admin)      http://localhost:${FE_PORT:-4200}
#   - the public site (fe/, start:info)              http://localhost:${INFO_PORT:-4400}
#
#   ./start-local.sh                  the country's own bootstrap: the database niord_<cc>, created when it is
#                                     missing and bootstrapped once with the base product's archive and then
#                                     the country's be/bootstrap/, in one run; files in ~/.niord-local/niord_<cc>
#   ./start-local.sh --sample         the base product's sample instead: the database niord_sample_<cc>,
#                                     bootstrapped with the base product's archive alone, then the sample
#                                     loaded, with this country's settings and texts; files in
#                                     ~/.niord-local/niord_sample_<cc>.
#   ./start-local.sh --sample --reset ... after dropping the database niord_sample_<cc> (and nothing else):
#                                     after a base product bump that changed the sample, or a bootstrap that
#                                     failed
#   ./start-local.sh --help           this text
#
# The script is the same in every country (the kit's templates/start-local.sh, which init writes and a country
# copies again at a base product bump, never edited). It reads the country's values from its files: the country
# code and name from fe/niord-country.json, the base product version from be/pom.xml's <parent> (with the set-up
# stub's own rule, fe/setup.mjs) and the deployable from be/pom.xml's own <artifactId>. It fetches two packages of
# that version into .local-run/<version>/ with be/mvnw: org.niord:niord-local-run (the launcher library it
# sources, the bootstrap command, the sample and its loader, the compose file and the local realm) and
# org.niord:niord-bootstrap (the base product's bootstrap archive). A version is fetched once; delete its folder
# to fetch it again.
#
# The order: the arguments; the country's files; the prerequisites; the fetch; the ports; the set-up
# (node setup.mjs in fe/, before the backend's build, which reads the packs' backend texts from fe/.niord/packs/);
# the shared containers; the database; the backend; the bootstrap, posted only on a database whose marker reads
# pending (a database that holds data is never bootstrapped); then both frontends, and the closing lines.
# Ctrl+C stops the backend and both frontends, waits until their ports are free, and leaves the containers running.
#
# The database. niord_<cc> unless a file start-local.json at the repository root names another:
#   { "database": "<name>" }
# for a machine where niord_<cc> already holds data of another kind. The launcher marks every database it
# creates (a comment on the database) and refuses an existing one without its mark before any backend starts: a
# database it did not make is never touched, whatever a configuration names. It never names niord, postgres,
# template0, template1, template_postgis, keycloak, niord_sample or niord_sample_*. --sample's niord_sample_<cc>
# is the launcher's by its name, and --reset is its remedy. The backend's datasource is the launcher's own,
# whatever the country's application-local.yml names.
#
# The containers niord-db, niord-keycloak and niord-smtp are shared by every checkout and every country's
# launcher: a running one is never stopped, restarted or recreated (only a missing or stopped one is started),
# and while all three run no compose command is issued at all. Their data lives in named volumes.
#
# Prerequisites: a JDK 25 or later with bin/jar (JAVA_HOME, else ~/.jdks/temurin-25; java on the PATH is not
# read), Docker running, Node with npm, curl, and a GitHub Packages token with read:packages in
# ~/.m2/settings.xml under the server id github, as the backend's build needs it too.
#
# Environment overrides:
#   FE_PORT                the administration app's port (default 4200)
#   INFO_PORT              the public site's port (default 4400)
#   BE_PORT                the backend's port (default 8888)
#   NIORD_LOCAL_BASE_REPO  a scratch Maven repository that holds a local build of the pinned base product
#                          version: Maven reads it first and ~/.m2/repository after it (MAVEN_ARGS, which the
#                          fetch, the set-up and the backend all read), and the credential is not checked
# The checkout and the home folder need a path without whitespace: the JVM and Maven arguments split at a space.
#
# The backend runs in UTC (-Duser.timezone=UTC), in the development execution mode (-Dmode=development; both
# frontends show it with a ribbon), with the Niord home ~/.niord-local/<database> as niord.home and NIORD_HOME,
# and its message links pointing at the administration app this run starts (-DadminAppUrl).
#
# A local trial on this machine only: everything listens on loopback, and Keycloak runs in development mode with
# known passwords (sysadmin / sysadmin; the sample users admin, editor and user with their names). Never expose
# it. A country deploys with its image and the chart.
#
# Runs in Git Bash on Windows as well as on Linux and macOS.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT/.local-run"
BE_LOG="$LOG_DIR/backend.log"
ADMIN_LOG="$LOG_DIR/admin.log"
INFO_LOG="$LOG_DIR/info.log"
# The comment the launcher puts on a database it created, and the one it accepts on an existing one
MARK="created by start-local.sh, the country launcher"

# Until the library is sourced, which defines the same
die() { echo "!! $*" >&2; exit 1; }

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) WINDOWS=true ;;
    *) WINDOWS=false ;;
esac
# A path the JVM and Maven understand (C:/Users/... on Windows, unchanged elsewhere)
to_native() { if $WINDOWS; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# --- arguments ---------------------------------------------------------------------------------------

SAMPLE=false
RESET=false
while (( $# )); do
    arg="$1"; shift
    case "$arg" in
        --sample) SAMPLE=true ;;
        --reset) RESET=true ;;
        -h|--help) sed -n '2,/^set -euo/{/^set -euo/d;s/^# \{0,1\}//;p;}' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "Unknown argument: $arg (see --help)" >&2; exit 2 ;;
    esac
done
if $RESET && ! $SAMPLE; then
    echo "--reset drops the sample's database and goes with --sample only." >&2
    exit 2
fi

FE_PORT="${FE_PORT:-4200}"
INFO_PORT="${INFO_PORT:-4400}"
BE_PORT="${BE_PORT:-8888}"
for name in FE_PORT INFO_PORT BE_PORT; do
    value="${!name}"
    if [[ ! "$value" =~ ^[0-9]{4,5}$ ]] || (( 10#$value < 1024 || 10#$value > 65535 )); then
        echo "$name must be a port number from 1024 to 65535, got '$value'." >&2
        exit 2
    fi
    printf -v "$name" '%d' "$((10#$value))"
done
if [[ "$FE_PORT" == "$INFO_PORT" || "$FE_PORT" == "$BE_PORT" || "$INFO_PORT" == "$BE_PORT" ]]; then
    echo "FE_PORT, INFO_PORT and BE_PORT must be three different ports, got $FE_PORT, $INFO_PORT and $BE_PORT." >&2
    exit 2
fi

# --- the country's files -----------------------------------------------------------------------------

command -v node >/dev/null 2>&1 || die "node not found on PATH: the launcher reads the country's files with it, and the set-up runs on it."

# Prints, one a line: the country code, the base product version, the deployable's artifactId, the database,
# whether Maven's settings.xml has the GitHub Packages server (server, none or missing), the repository root and
# Maven's home folder as paths the JVM reads (C:/... on Windows, where Maven's home is USERPROFILE), and the
# country's name; or fails naming the file. Run in the repository root. One node for all of it: a process start
# is slow on a Windows workstation.
read -r -d '' READ_COUNTRY <<'JS' || true
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const fail = (message) => {
  console.error(`!! ${message}`);
  process.exit(1);
};
const read = (file) => {
  if (!fs.existsSync(file))
    fail(`${file} does not exist: ./start-local.sh runs from the root of a country repository`);
  return fs.readFileSync(file, 'utf8');
};

let manifest;
try {
  manifest = JSON.parse(read('fe/niord-country.json'));
} catch (error) {
  fail(`fe/niord-country.json: ${error.message}`);
}
const cc = manifest?.country;
if (typeof cc !== 'string' || !/^[a-z]{2}$/.test(cc))
  fail(`fe/niord-country.json's country "${cc}" is not a country code, its ISO 3166-1 alpha-2 code, two letters a-z`);
const name = manifest.name;
if (typeof name !== 'string' || !name.trim() || /[\u0000-\u001f]/.test(name))
  fail("fe/niord-country.json has no name, the country's name on one line");

// The base product version by the set-up stub's own rule; the stub does nothing when it is imported.
read('fe/setup.mjs');
let stub;
try {
  stub = await import(pathToFileURL(path.resolve('fe', 'setup.mjs')).href);
} catch (error) {
  fail(`fe/setup.mjs could not be read: ${error.message}`);
}
if (typeof stub.parentVersion !== 'function')
  fail('fe/setup.mjs has no parentVersion: copy .niord/kit/templates/setup.mjs over it');
const pom = read('be/pom.xml');
let version;
try {
  version = stub.parentVersion(pom);
} catch (error) {
  fail(error.message.replaceAll('../be/pom.xml', 'be/pom.xml'));
}

// The deployable: the pom's own <artifactId>, a child of <project>, comments left out.
const text = pom.replace(/<!--[\s\S]*?-->/g, '');
let depth = 0;
let artifactId;
for (const tag of text.matchAll(/<(\/?)([A-Za-z_][\w.:-]*)[^>]*?(\/?)>/g)) {
  if (tag[1]) depth--;
  else if (tag[3]) continue;
  else if (depth === 1 && tag[2] === 'artifactId') {
    artifactId = text.slice(tag.index + tag[0].length).split('<')[0].trim();
    break;
  } else depth++;
}
if (!/^[A-Za-z0-9_.-]+$/.test(artifactId ?? ''))
  fail('be/pom.xml has no <artifactId> of its own, beside its <parent>');

let database = `niord_${cc}`;
if (fs.existsSync('start-local.json')) {
  let own;
  try {
    own = JSON.parse(fs.readFileSync('start-local.json', 'utf8'));
  } catch (error) {
    fail(`start-local.json: ${error.message}`);
  }
  if (own === null || typeof own !== 'object' || Array.isArray(own) || Object.keys(own).join() !== 'database')
    fail('start-local.json is an object with one key, the database: { "database": "<name>" }');
  database = own.database;
  if (typeof database !== 'string' || !/^[a-z_][a-z0-9_]{0,62}$/.test(database))
    fail(`start-local.json's database "${database}" is not a plain lower-case identifier`);
  const refused = ['niord', 'postgres', 'template0', 'template1', 'template_postgis', 'keycloak', 'niord_sample'];
  if (refused.includes(database) || database.startsWith('niord_sample_'))
    fail(
      `start-local.json names ${database}, which the launcher never uses: not niord, postgres, a template, keycloak, nor the sample's niord_sample or niord_sample_<cc>`,
    );
}

// The credential is checked with the prerequisites, after the JDK, curl and npm.
const native = (file) => file.replaceAll('\\', '/');
const userHome = native(os.homedir());
const settings = path.join(userHome, '.m2', 'settings.xml');
let credential = 'missing';
if (fs.existsSync(settings)) {
  const xml = fs.readFileSync(settings, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
  credential = /<server>(?:(?!<\/server>)[\s\S])*<id>\s*github\s*<\/id>/.test(xml) ? 'server' : 'none';
}
console.log([cc, version, artifactId, database, credential, native(process.cwd()), userHome, name].join('\n'));
JS

values="$(cd "$ROOT" && node --input-type=module -e "$READ_COUNTRY")" || exit 1
{ read -r CC; read -r VERSION; read -r ARTIFACT; read -r DB; read -r CREDENTIAL; read -r ROOT_NATIVE; read -r USER_HOME_NATIVE; read -r NAME; } <<< "$values"

if $SAMPLE; then
    SAMPLE_DB="niord_sample_$CC"
    RUN_DB="$SAMPLE_DB"
else
    RUN_DB="$DB"
fi

# The backend's JVM arguments and MAVEN_ARGS are split at every space
[[ "$ROOT" =~ [[:space:]] ]] && die "The checkout's path contains whitespace ($ROOT): the backend's JVM and Maven arguments split at a space. Clone it into a path without."
[[ "$HOME" =~ [[:space:]] ]] && die "The home folder's path contains whitespace ($HOME): the Niord home under it is a JVM argument, which splits at a space."

# --- prerequisites -----------------------------------------------------------------------------------

# The library has the same check (use_jdk25), but it is not fetched yet, and the fetch unpacks with bin/jar.
jdk_25() {
    local major
    [[ -x "$1/bin/java" && -x "$1/bin/jar" ]] || return 1
    major="$("$1/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
    (( ${major:-0} >= 25 ))
}
if [[ -z "${JAVA_HOME:-}" ]] || ! jdk_25 "$JAVA_HOME"; then
    jdk_25 "$HOME/.jdks/temurin-25" || die "No JDK 25+ found: set JAVA_HOME to a JDK 25 (a JRE has no bin/jar; java on the PATH is not read), or unpack Temurin 25 into $HOME/.jdks/temurin-25."
    JAVA_HOME="$(to_native "$HOME/.jdks/temurin-25")"
fi
export JAVA_HOME

command -v curl >/dev/null 2>&1 || die "curl not found on PATH."
command -v npm >/dev/null 2>&1 || die "npm not found on PATH: it comes with Node."

if [[ -n "${NIORD_LOCAL_BASE_REPO:-}" ]]; then
    [[ -d "$NIORD_LOCAL_BASE_REPO" ]] || die "NIORD_LOCAL_BASE_REPO=$NIORD_LOCAL_BASE_REPO is not a folder: it names a scratch Maven repository that holds a local build of the base product $VERSION."
    scratch="$(to_native "$(cd "$NIORD_LOCAL_BASE_REPO" && pwd)")"
    [[ "$scratch$USER_HOME_NATIVE" =~ [[:space:]] ]] && die "NIORD_LOCAL_BASE_REPO and the home folder need paths without whitespace: MAVEN_ARGS splits at a space."
    # Read by every Maven run below: the fetch, the set-up's and the backend's
    export MAVEN_ARGS="${MAVEN_ARGS:+$MAVEN_ARGS }-Dmaven.repo.local=$scratch -Dmaven.repo.local.tail=$USER_HOME_NATIVE/.m2/repository"
    echo ">> NIORD_LOCAL_BASE_REPO: Maven reads $scratch first, then ~/.m2/repository; the GitHub Packages credential is not checked"
else
    settings="$USER_HOME_NATIVE/.m2/settings.xml"
    case "$CREDENTIAL" in
        server) ;;
        none) die "$settings has no <server> with <id>github</id>: GitHub Packages needs a token with read:packages there (the kit's README, Running it locally), or NIORD_LOCAL_BASE_REPO a scratch Maven repository that holds a local build of the base product $VERSION." ;;
        *) die "No $settings: GitHub Packages needs a token with read:packages there, in a <server> with <id>github</id> (the kit's README, Running it locally), or NIORD_LOCAL_BASE_REPO a scratch Maven repository that holds a local build of the base product $VERSION." ;;
    esac
fi

# Docker last, so that a refusal above makes no docker call
command -v docker >/dev/null 2>&1 || die "docker not found on PATH."
docker info >/dev/null 2>&1 || die "Docker is not running — start Docker Desktop and retry."

# --- the base product's packages ---------------------------------------------------------------------

# Fetched once per version: a published version never changes (a local build of the same version again needs
# its folder deleted). The output folder is absolute, since Maven resolves a relative one against be/.
RUN_DIR="$LOG_DIR/$VERSION"
LOG_DIR_NATIVE="$ROOT_NATIVE/.local-run"
RUN_DIR_NATIVE="$LOG_DIR_NATIVE/$VERSION"
LIBRARY="$RUN_DIR/local-run/scripts/local-run-lib.sh"
BOOTSTRAP_ZIP="$RUN_DIR/niord-bootstrap-$VERSION.zip"
if [[ ! -f "$LIBRARY" || ! -f "$BOOTSTRAP_ZIP" ]]; then
    rm -rf "$RUN_DIR/local-run" "$RUN_DIR/local-run.tmp"
    mkdir -p "$RUN_DIR/local-run.tmp"
    for artifact in niord-local-run niord-bootstrap; do
        echo ">> Fetching org.niord:$artifact:$VERSION:zip into .local-run/$VERSION/"
        ( cd "$ROOT/be" && ./mvnw -B -q dependency:copy "-Dartifact=org.niord:$artifact:$VERSION:zip" \
              "-DoutputDirectory=$RUN_DIR_NATIVE" ) \
            || die "Maven could not fetch org.niord:$artifact:$VERSION:zip (above). GitHub Packages needs a token with read:packages in ~/.m2/settings.xml under the server id github (or NIORD_LOCAL_BASE_REPO a scratch repository with a local build of $VERSION), and a base product version older than the launcher's first carries no niord-local-run: bump be/pom.xml's <parent>."
    done
    # Unpacked into a folder of its own first, so an unpack that broke off is never taken for a whole one
    ( cd "$RUN_DIR/local-run.tmp" && "$JAVA_HOME/bin/jar" xf "$RUN_DIR_NATIVE/niord-local-run-$VERSION.zip" ) \
        || die "niord-local-run-$VERSION.zip could not be unpacked (above)."
    [[ -f "$RUN_DIR/local-run.tmp/scripts/local-run-lib.sh" ]] \
        || die "org.niord:niord-local-run:$VERSION:zip holds no scripts/local-run-lib.sh."
    mv "$RUN_DIR/local-run.tmp" "$RUN_DIR/local-run"
fi

# The country-neutral functions of a local run: the shared containers, the database, the process handling, the
# bootstrap and the sample. Its contract: BE_PID, BE_PORT, BE_URL, BE_LOG and start_backend, below.
# shellcheck source=/dev/null
source "$LIBRARY"
use_jdk25

BE_PID=""
ADMIN_PID=""
INFO_PID=""
BE_URL="http://127.0.0.1:$BE_PORT/rest/settings/web"
ADMIN_URL="http://localhost:$FE_PORT/"
INFO_URL="http://localhost:$INFO_PORT/"

port_in_use "$BE_PORT" && die "Port $BE_PORT is already in use — is a backend still running? Pick another with BE_PORT=<port> ./start-local.sh"
port_in_use "$FE_PORT" && die "Port $FE_PORT is already in use — pick another with FE_PORT=<port> ./start-local.sh"
port_in_use "$INFO_PORT" && die "Port $INFO_PORT is already in use — pick another with INFO_PORT=<port> ./start-local.sh"

# One Niord home per database: its repository files and its message index belong to that database. Not ~/.niord,
# where the legacy backend's message index makes this backend fail at start-up. The folder is made when the
# backend starts, so a refused database gets none.
NIORD_HOME_DIR="$HOME/.niord-local/$RUN_DB"
NIORD_HOME_NATIVE="$(native_path "$NIORD_HOME_DIR")"
# The country's settings set niord.home from it
export NIORD_HOME="$NIORD_HOME_NATIVE"

# --- set-up ------------------------------------------------------------------------------------------

# Before any Maven build of the backend: the build reads the packs' backend texts from fe/.niord/packs/, which
# the set-up stages. A refusal stops here, before any container or backend.
echo ">> Set-up (node setup.mjs in fe/)"
( cd "$ROOT/fe" && node setup.mjs ) || die "Set-up refused (above): nothing was started. Fix what it names, then run ./start-local.sh again."
if [[ ! -f "$ROOT/fe/node_modules/.package-lock.json" || "$ROOT/fe/package-lock.json" -nt "$ROOT/fe/node_modules/.package-lock.json" ]]; then
    echo ">> Installing the frontend dependencies (npm ci) ..."
    ( cd "$ROOT/fe" && npm ci ) || die "npm ci failed (above)."
fi

# --- process handling --------------------------------------------------------------------------------

# Waits up to 60 s for the port $1 to be free. It was free at the start, so a listener is this run's; on Windows
# the shell behind a job can be gone while java.exe or node.exe serve on, so that listener is stopped by its PID.
free_port() {
    local port="$1" deadline=$((SECONDS + 60)) pid
    while port_in_use "$port"; do
        if $WINDOWS && (( SECONDS > deadline - 55 )); then
            for pid in $(netstat -ano | grep "LISTENING" | grep -E ":$port[[:space:]]" | awk '{ print $NF }' | sort -u); do
                taskkill //PID "$pid" //T //F >/dev/null 2>&1 || true
            done
        fi
        if (( SECONDS >= deadline )); then
            echo "!! Port $port is still in use after 60 s: stop what listens there by hand." >&2
            return 0
        fi
        sleep 1
    done
}

cleanup() {
    trap - INT TERM EXIT
    echo
    echo ">> Stopping the frontends and the backend ..."
    kill_tree "$ADMIN_PID"
    kill_tree "$INFO_PID"
    kill_tree "$BE_PID"
    local port job
    for port in "$BE_PORT" "$FE_PORT" "$INFO_PORT"; do
        free_port "$port"
    done
    # The log followers
    for job in $(jobs -p); do kill "$job" 2>/dev/null || true; done
    echo ">> Stopped. The shared containers niord-db, niord-keycloak and niord-smtp keep running."
}
trap cleanup EXIT
# A trap handler returns to the interrupted loop unless it exits itself.
trap 'cleanup; exit 130' INT TERM

# --- the containers and the database -----------------------------------------------------------------

echo ">> Checking the shared containers (niord-db, niord-keycloak, niord-smtp)"
start_services "$RUN_DIR/local-run/docker-compose.yml" "$RUN_DIR/local-run/keycloak/niord-realm-local.json"

if $RESET; then
    # The name is built from the country code, never taken from the environment
    sample_reset "niord_sample_$CC"
fi

if $SAMPLE; then
    ensure_database "$SAMPLE_DB"
else
    # A database the launcher did not make is never touched: one that exists without its mark is refused.
    mark="$(docker exec niord-db psql -U niord -d postgres -tAc "select coalesce(shobj_description(oid, 'pg_database'), '-') from pg_database where datname = '$DB'")" \
        || die "niord-db could not be asked about the database $DB (above)."
    mark="${mark%$'\r'}"
    if [[ -n "$mark" && "$mark" != "$MARK" ]]; then
        die "The database $DB exists and holds data this launcher did not make (it has no mark of start-local.sh), so it is never touched. Name another database for this launcher in start-local.json at the repository root: { \"database\": \"<name>\" }, or, when you made $DB yourself and no longer need it, drop it (docker exec niord-db dropdb -U niord $DB): the next run creates and marks it."
    fi
    ensure_database "$DB"
    if $DATABASE_CREATED; then
        docker exec niord-db psql -q -U niord -d postgres -c "comment on database $DB is '$MARK'" >/dev/null \
            || die "Database $DB was created but could not be marked (above); drop it with docker exec niord-db dropdb -U niord $DB and run ./start-local.sh again."
    fi
fi

# --- the backend -------------------------------------------------------------------------------------

JVM_ARGS="-Duser.timezone=UTC -Dserver.port=$BE_PORT -Dniord.home=$NIORD_HOME_NATIVE -Dspring.datasource.url=jdbc:postgresql://localhost:5432/$RUN_DB -Dmode=development -DadminAppUrl=http://localhost:$FE_PORT"
# The sample sends nothing out: SECOM's outbox dispatch is off, and mail goes to the loopback MailHog.
if $SAMPLE; then
    JVM_ARGS="$JVM_ARGS -Dniord.secom.enabled=false"
fi

# The log is started afresh below; a second start (--sample's, after the load) appends to it.
start_backend() {
    ( cd "$ROOT/be" && exec ./mvnw -B spring-boot:run -Dspring-boot.run.profiles=local "-Dspring-boot.run.jvmArguments=$JVM_ARGS" ) >>"$BE_LOG" 2>&1 &
    BE_PID=$!
}

echo ">> Starting the backend $ARTIFACT on database $RUN_DB — the first run also builds it"
mkdir -p "$NIORD_HOME_DIR"
: >"$BE_LOG"
start_backend
wait_for "backend" "$BE_PID" "$BE_URL" 900 "$BE_LOG"
echo ">> Backend is up"

# --- the bootstrap -----------------------------------------------------------------------------------

# Posted only on a database whose marker reads pending (bootstrap_database); a bootstrap that failed or broke off
# is never run again on the same database, and the remedy is the database recreated empty.
if $SAMPLE; then
    recreate="./start-local.sh --sample --reset starts $SAMPLE_DB afresh"
    bootstrap_database "http://127.0.0.1:$BE_PORT" "$SAMPLE_DB" "$recreate" "$BE_LOG" "$LOG_DIR" "$BOOTSTRAP_ZIP"
    sample_load "$RUN_DIR/local-run/scripts/load-sample.sh" "$SAMPLE_DB" "$NIORD_HOME_NATIVE"
else
    recreate="stop the backend, then docker exec niord-db dropdb -U niord $DB; the next ./start-local.sh creates it empty and bootstraps it"
    archives=("$BOOTSTRAP_ZIP")
    if [[ -f "$ROOT/be/bootstrap/batch-set.json" ]]; then
        archives+=("$ROOT/be/bootstrap")
    else
        echo ">> be/bootstrap/ has no batch-set.json yet: the base product's archive alone"
    fi
    bootstrap_database "http://127.0.0.1:$BE_PORT" "$DB" "$recreate" "$BE_LOG" "$LOG_DIR" "${archives[@]}"
fi

# --- the frontends -----------------------------------------------------------------------------------

# Once the database is bootstrapped, so neither shows a database half made. The dev-server proxy
# (proxy.conf.mjs) reads the backend's port from NIORD_BE_PORT; the last --port given wins over a script's own.
export NIORD_BE_PORT="$BE_PORT"
echo ">> Starting the administration app on port $FE_PORT and the public site on port $INFO_PORT"
( cd "$ROOT/fe" && exec npm run start:admin -- --configuration local --port "$FE_PORT" ) >"$ADMIN_LOG" 2>&1 &
ADMIN_PID=$!
( cd "$ROOT/fe" && exec npm run start:info -- --port "$INFO_PORT" ) >"$INFO_LOG" 2>&1 &
INFO_PID=$!
wait_for "administration app" "$ADMIN_PID" "$ADMIN_URL" 300 "$ADMIN_LOG"
echo ">> The administration app is up"
wait_for "public site" "$INFO_PID" "$INFO_URL" 300 "$INFO_LOG"
echo ">> The public site is up"

cat <<EOF

  $NAME is running (loopback only)
    Application  $ARTIFACT on the base product $VERSION
    Admin app    http://localhost:$FE_PORT          log in as sysadmin / sysadmin
    Public site  http://localhost:$INFO_PORT
    Backend      http://localhost:$BE_PORT/rest
    Keycloak     http://localhost:8080/auth     admin console: admin / admin
    MailHog      http://localhost:8025
    Database     $RUN_DB  (psql: docker exec -it niord-db psql -U niord -d $RUN_DB)
    Niord home   $NIORD_HOME_NATIVE
    Logs         $LOG_DIR_NATIVE/backend.log
                 $LOG_DIR_NATIVE/admin.log
                 $LOG_DIR_NATIVE/info.log

EOF
if $SAMPLE; then
    cat <<EOF
  The sample: the base product's bootstrap and its sample, with this country's settings and texts; the
  country's own bootstrap is the plain ./start-local.sh.
    Sample logins  sysadmin / sysadmin, admin / admin, editor / editor, user / user

EOF
fi
cat <<EOF
  A local trial on this machine only: Keycloak runs in development mode with known passwords.
  Never expose it. A country deploys with its image and the chart.

  Following the logs (Artemis audit lines hidden). Ctrl+C stops the backend and both frontends.

EOF

tail -n 0 -F "$BE_LOG" 2>/dev/null | grep --line-buffered -v 'activemq.audit' | awk '{ print "[be] " $0; fflush() }' &
tail -n 0 -F "$ADMIN_LOG" 2>/dev/null | awk '{ print "[admin] " $0; fflush() }' &
tail -n 0 -F "$INFO_LOG" 2>/dev/null | awk '{ print "[info] " $0; fflush() }' &

# Watch the ports, not the PIDs, and stop once one of the servers stopped answering (watch_servers).
watch_servers backend "$BE_URL" "$BE_LOG" "administration app" "$ADMIN_URL" "$ADMIN_LOG" "public site" "$INFO_URL" "$INFO_LOG"
exit 1
