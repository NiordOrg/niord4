#!/usr/bin/env bash
#
# Regenerates the sample (be/niord/sample/, docs/fe-country-split-plan.md P9, docs/code-data-config-plan.md §8):
#   niord/sample/niord-sample.sql   the rows the sample adds on top of the base product's bootstrap, as a psql script;
#                                   the attachment files those rows name are rows of it too (the repository store)
# from its sources:
#   niord/sample/archive/           a bootstrap archive (batch-set.json, its data files, templates/), applied as the
#                                   second archive of one bootstrap run after the base product's niord/bootstrap/
#   niord/sample/messages/          messages.json, the sample messages, written for the sample and imported with the
#                                   message archive import as a sysadmin's batch set (statuses and uids kept), with
#                                   the attachments SampleTool draws
#
# It builds two scratch databases in the niord-db container, each through the real path: niord-app started with
# run.sh on the database (Liquibase builds the schema), then the bootstrap command (be/scripts/bootstrap.sh) as the
# local realm's sysadmin.
#   niord_sample_base  the base product's bootstrap only: the baseline;
#   niord_sample       the base product's bootstrap and the sample's archive in ONE run (the same-run rule adds the
#                      archive's Danish descriptions and domain links to the base entries), then the messages.
# It then compares the two through postgres_fdw: every row of the baseline must be in niord_sample unchanged (but for
# version, created, updated and the derived treeSortOrder), or the run fails ("the base differs between the two
# builds"). The rows of niord_sample that the baseline lacks are the sample. Build residue is never captured (users,
# batch data and the batch jobs' numbers, histories, the ledger, the marker, settings, the search index, Liquibase's
# tables); the message series' next numbers are, after the sample's own. A sample row in any
# other table outside the allow-list (SampleSafetyCheck.ALLOWED_TABLES) fails the run, naming the table, and so does
# a link the message import dropped (the rows of Message_Area, Message_Category, Message_Chart, Attachment, Reference
# and FeatureCollection against those messages.json names). The capture is normalised so that a rebuild on any day
# writes the same bytes: created, updated and nextScheduledExecution set to SAMPLE_EPOCH, a message's createdBy and
# lastUpdatedBy cleared, the geometry uids the import draws at random derived from the row ids.
#
# The SQL loads on any country's backend after the base product's bootstrap, whatever ids a country's first start
# and the bootstrap gave the base rows (docs/code-data-config-plan.md §8). The sample's own rows are renumbered from
# SAMPLE_FIRST_ID in every table, in the order of the ids the build gave them (a table without an id keeps the order
# of its rows as they were before), and every foreign key and the area and category lineage follow; a sample row
# below a base row in a tree fails the run. A reference to a base row is its natural key, a scalar subselect in one
# of SampleReferences' shapes (Dictionary by name, DictionaryEntry by dictionary and key, PromulgationType by
# typeId, Category by MRN or English name, TemplateParam by owner, position and paramId), which SampleTool writes
# into pg_dump's INSERTs; a base row of a table without a shape, or whose key does not resolve to exactly one row in
# niord_sample, fails the run. The SQL is pg_dump's data-only column inserts with triggers disabled per table, in
# one transaction, between a guard and the sequences' setval. The guard refuses unless the database is niord_sample
# or niord_sample_<cc>, its bootstrap marker reads applied, its Liquibase changesets are the recorded ones, no table
# the sample has ids in holds a row at or above SAMPLE_FIRST_ID nor has its sequence there, the sample's domains and
# message series are not there yet, and every base row the sample names, and every base parameter type its template
# parameters name, resolves by its natural key to exactly one row. A changeset refusal means the sample was made on
# another schema: regenerate it. Any other refusal means the database is not one clean run of the base product's
# bootstrap (an interrupted bootstrap, rows added since, or a country's archive posted beside the base product's):
# the cure is ./start-local.sh --sample --reset, and only a change to the base bootstrap, the schema or the entities
# needs a regeneration.
#
# Before anything is written over the committed files the candidate passes SampleSafetyCheck (the safety rules), and
# it is loaded with load-sample.sh into the baseline database renamed niord_sample: the schema check. A second load
# must answer "already loaded". Then the scratch databases and files are removed.
#
# Run it after a change to the base product's bootstrap, the schema (a Liquibase changeset), the entities, or the
# sample's sources; never edit the generated files. Two runs give the same bytes. It needs the docker-compose
# services (niord-db published on loopback only, the niord realm in Keycloak), a JDK 25 and no backend on the
# regeneration port. It touches no database but niord_sample and niord_sample_base, which it drops and creates
# again (a sample deployment of ./start-local.sh --sample is loaded again by its next run), and refuses while a
# client session is connected to either. They are dropped when the run ends, also when it fails, but only once step
# 1 has created them: a build or Maven failure before it leaves a developer's niord_sample as it was. Maven installs
# this checkout's niord-base into .local-run/sample-regen-m2 at the repository root, never into the shared
# ~/.m2/repository, from which it reads everything else. A checkout path or home with a space is refused: Maven's
# MAVEN_ARGS and the JVM arguments split at it.
#
# Usage:  JAVA_HOME=<a JDK 25> niord/scripts/regen-sample.sh
#         NIORD_SAMPLE_OUT=<dir> niord/scripts/regen-sample.sh    writes niord-sample.sql under <dir> instead of
#                                                                  over the committed file
#         niord/scripts/regen-sample.sh --check <sql>              runs only the safety check on the given file
#                                                                  (after an incremental test-compile)
#         NIORD_SAMPLE_REGEN_PORT=<port>                           the backend's port (default 8879)
#         NIORD_SAMPLE_KEEP=1                                       keeps the scratch databases and files when a
#                                                                  run fails, to look into them
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BE="$(cd "$SCRIPT_DIR/../.." && pwd)"
ROOT="$(cd "$BE/.." && pwd)"
# Maven's MAVEN_ARGS (-Dmaven.repo.local under the checkout and the home) and the JVM arguments are split at a space
for path in "$ROOT" "${HOME:-}" "${USERPROFILE:-}"; do
    case "$path" in
        *[[:space:]]*)
            echo "!! '$path' holds a space: Maven's MAVEN_ARGS and the JVM arguments split at it; use a checkout" \
                "and a home without one" >&2
            exit 1 ;;
    esac
done
cd "$BE"

SAMPLE_DIR="niord/sample"
BOOTSTRAP_DIR="niord/bootstrap"
WORK="$ROOT/.local-run/sample-regen"
PORT="${NIORD_SAMPLE_REGEN_PORT:-8879}"
SAMPLE_EPOCH="2026-10-22 00:00:00"
# The sample's first id in every table: SampleReferences.FIRST_ID, which SampleFolderTest holds this to
SAMPLE_FIRST_ID=1000001
SAMPLE_YEAR=2026
CONTAINER=niord-db
KEYCLOAK_TOKEN_URL="http://localhost:8080/auth/realms/niord/protocol/openid-connect/token"
CLASSPATH_FILE="niord/target/sample-classpath.txt"
ARGS_FILE="niord/target/sample-java.args"
# The sentinel line of the SQL: chosen once, read by check-no-sample.sh from the committed SQL
SENTINEL="-- niord-sample-sentinel: 7c4e1b9a2f6d48e3a05b93d1c8f27e64"
# Build residue: rows a build writes that are never part of the sample
RESIDUE="User BatchData MessageHistory ScriptResourceHistory DefaultsLedger BootstrapState databasechangelog
databasechangeloglock Setting spatial_ref_sys message_search_text"

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) WINDOWS=true ;;
    *) WINDOWS=false ;;
esac
native_path() { if $WINDOWS; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
command -v "$JAVA" >/dev/null 2>&1 || { echo "!! java not found (set JAVA_HOME to a JDK 25)" >&2; exit 1; }
command -v "$JAR" >/dev/null 2>&1 || { echo "!! jar not found (set JAVA_HOME to a JDK 25)" >&2; exit 1; }

die() { echo "!! $*" >&2; exit 1; }

# Maven installs this checkout's niord-base (run.sh) into a local repository of the regeneration's own and reads
# everything else from the shared ~/.m2/repository (Maven's local-repository tail), as a ../../../start-local.sh slot
# does: installed into the shared one, another checkout whose run.sh stamp is newer than its sources would run this
# checkout's niord-base. NIORD_M2_REPO tells run.sh where the installed jar is. The repository outlives a run, so
# the next one installs only what changed.
SHARED_M2="$(native_path "${USERPROFILE:-$HOME}")/.m2/repository"
export NIORD_M2_REPO
NIORD_M2_REPO="$(native_path "$ROOT/.local-run/sample-regen-m2")"
export MAVEN_ARGS="${MAVEN_ARGS:-} -Dmaven.repo.local=$NIORD_M2_REPO -Dmaven.repo.local.tail=$SHARED_M2"

# The test classpath, built once, for SampleTool (as regen-test-seed-postgres.sh builds it for SeedRegen). On Windows
# the class path is longer than a command line may be, so it goes to java in an argument file.
build_classpath() {
    echo ">> Compiling niord with its tests, and resolving the test classpath ..."
    ./mvnw -q -ntp -pl niord test-compile
    ./mvnw -q -ntp -pl niord dependency:build-classpath -Dmdep.includeScope=test \
        -Dmdep.outputFile="target/sample-classpath.txt"
    [ -s "$CLASSPATH_FILE" ] || die "$CLASSPATH_FILE was not written"
    local cp
    if $WINDOWS; then
        cp="$(cygpath -m "$BE/niord/target/test-classes");$(cygpath -m "$BE/niord/target/classes");$(tr '\\' '/' < "$CLASSPATH_FILE")"
    else
        cp="$BE/niord/target/test-classes:$BE/niord/target/classes:$(cat "$CLASSPATH_FILE")"
    fi
    printf -- '-cp\n"%s"\n' "$cp" > "$ARGS_FILE"
}

sample_tool() {
    "$JAVA" -Dstdout.encoding=UTF-8 "@$ARGS_FILE" org.niord.test.sample.SampleTool "$@"
}

# --check <sql>: the safety check alone
if [ "${1:-}" = "--check" ]; then
    [ $# -eq 2 ] || die "Usage: regen-sample.sh --check <sql>"
    sql="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
    # Always compiled first (incrementally), so the check never applies the rules of an older build
    build_classpath
    sample_tool check "$(native_path "$sql")"
    exit $?
fi
[ $# -eq 0 ] || die "Unknown argument: $1 (see the header)"

# --- the services, and the refusals ----------------------------------------------------------------------------

psql_db() {
    local db="$1"
    shift
    docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -q -U niord -d "$db" "$@"
}

# The shared containers, through the launcher library: a running one is never touched, and only a missing or stopped
# one is started (docker compose up --no-recreate). The realm is staged first, because the compose file mounts it from
# ~/.niord-local/keycloak/. In a subshell, so that the library's kill_tree, port_in_use and stop_backend do not
# replace this script's own, which are shaped differently.
( source "$BE/scripts/local-run-lib.sh" && start_services docker-compose.yml keycloak/niord-realm-local.json )
ready_deadline=$((SECONDS + 120))
until docker exec "$CONTAINER" pg_isready -U niord -d postgres >/dev/null 2>&1; do
    [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" = true ] \
        || die "$CONTAINER is not running (docker logs $CONTAINER says why)"
    (( SECONDS < ready_deadline )) || die "$CONTAINER is not ready after 120 s (docker logs $CONTAINER says why)"
    sleep 2
done

bindings="$(docker port "$CONTAINER" 5432/tcp)"
while read -r binding; do
    case "$binding" in
        127.0.0.1:*|"[::1]:"*) ;;
        *) die "$CONTAINER publishes port 5432 on $binding: the sample is built on a loopback database only" ;;
    esac
done <<< "$bindings"
no_sessions() {
    local sessions
    sessions="$(docker exec "$CONTAINER" psql -U niord -d postgres -tAc \
        "select count(*) from pg_stat_activity where datname in ('niord_sample', 'niord_sample_base')
         and backend_type = 'client backend'")"
    [ "$sessions" = 0 ] || die "a session is connected to niord_sample or niord_sample_base (a backend, or psql): stop it first"
}
no_sessions
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
    die "port $PORT is in use: stop what listens there, or set NIORD_SAMPLE_REGEN_PORT"
fi

# --- process handling --------------------------------------------------------------------------------------------

BE_PID=""

# Stop a background job and everything it started (run.sh -> Maven JVM -> forked app JVM)
kill_tree() {
    local pid="$1"
    [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null || return 0
    if $WINDOWS; then
        local winpid
        winpid=$(cat "/proc/$pid/winpid" 2>/dev/null || true)
        [[ -n "$winpid" ]] && taskkill //PID "$winpid" //T //F >/dev/null 2>&1 || true
        kill "$pid" 2>/dev/null || true
    else
        local child
        for child in $(pgrep -P "$pid" 2>/dev/null || true); do
            kill_tree "$child"
        done
        kill -TERM "$pid" 2>/dev/null || true
    fi
}

port_in_use() {
    if $WINDOWS; then
        netstat -ano | grep "LISTENING" | grep -qE ":$PORT[[:space:]]"
    else
        (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null
    fi
}

# Stops the backend and waits until its port is free; on Windows whatever still listens there is stopped too. With
# --no-die (the trap's), a port still held after 60 s is printed and answered 1 instead of ending the script.
stop_backend() {
    local fatal=true
    [ "${1:-}" = --no-die ] && fatal=false
    kill_tree "$BE_PID"
    BE_PID=""
    local deadline=$((SECONDS + 60)) pid
    while port_in_use; do
        if $WINDOWS && (( SECONDS > deadline - 45 )); then
            for pid in $(netstat -ano | grep "LISTENING" | grep -E ":$PORT[[:space:]]" | awk '{ print $NF }' | sort -u); do
                taskkill //PID "$pid" //T //F >/dev/null 2>&1 || true
            done
        fi
        if (( SECONDS >= deadline )); then
            $fatal && die "the backend still holds port $PORT after 60 s"
            echo "!! the backend still holds port $PORT after 60 s: stop it by hand" >&2
            return 1
        fi
        sleep 1
    done
}

# Set once step 1 has created the scratch databases: before it, the trap leaves niord_sample as it found it
DATABASES_CREATED=false

cleanup() {
    local code=$?
    trap - EXIT
    stop_backend --no-die || true
    if [ "$code" != 0 ] && [ -n "${NIORD_SAMPLE_KEEP:-}" ]; then
        echo ">> NIORD_SAMPLE_KEEP: niord_sample, niord_sample_base and $WORK are kept; drop them when done" >&2
        exit "$code"
    fi
    if $DATABASES_CREATED; then
        for db in niord_sample niord_sample_base; do
            docker exec "$CONTAINER" dropdb -U niord --if-exists "$db" >/dev/null 2>&1 \
                || echo "!! $db could not be dropped (a session is still connected?): drop it by hand" >&2
        done
    fi
    rm -rf "$WORK"
    exit "$code"
}
trap cleanup EXIT

# Prints the string field $1 of the JSON in the file $2, its escapes left as they are
json_string() {
    tr -d '\n' < "$2" | sed -nE 's/.*"'"$1"'" *: *"(([^"\\]|\\.)*)".*/\1/p'
}

sysadmin_token() {
    local body="$WORK/token.json"
    curl -s -m 30 -o "$body" "$KEYCLOAK_TOKEN_URL" -d grant_type=password -d client_id=niord-web \
        -d username=sysadmin -d password=sysadmin || die "no answer from $KEYCLOAK_TOKEN_URL"
    local token
    token="$(json_string access_token "$body")"
    [ -n "$token" ] || die "no sysadmin token from $KEYCLOAK_TOKEN_URL: $(cat "$body")"
    printf '%s' "$token"
}

# curl as the sysadmin: the token goes to curl as a config on standard input, never in an argument
curl_sysadmin() {
    curl -K - "$@" <<< "header = \"Authorization: Bearer $TOKEN\""
}

# Starts niord-app on the database $1 with the Niord home $2 and waits until it answers
start_backend() {
    local db="$1" home="$2" log="$WORK/backend-$1.log"
    local jvm="-Duser.timezone=UTC -Dserver.port=$PORT -Dniord.home=$(native_path "$home")"
    jvm="$jvm -Dspring.datasource.url=jdbc:postgresql://localhost:5432/$db -Dmode=development"
    jvm="$jvm -Dniord.secom.enabled=false -Dspring.docker.compose.enabled=false"
    echo ">> Starting niord-app on $db (port $PORT) — tail -f $log"
    ( exec ./run.sh "-Dspring-boot.run.jvmArguments=$jvm" ) > "$log" 2>&1 &
    BE_PID=$!
    local start=$SECONDS code
    while :; do
        code=$(curl -s -o /dev/null -m 3 -w '%{http_code}' "http://127.0.0.1:$PORT/rest/settings/web" || true)
        [ "$code" = 200 ] && break
        if [ "$code" = 000 ] && ! kill -0 "$BE_PID" 2>/dev/null; then
            tail -n 40 "$log" >&2
            die "the backend exited during startup (above; $log)"
        fi
        (( SECONDS - start < 900 )) || die "the backend did not come up within 900 s ($log)"
        sleep 3
    done
    echo "   up after $((SECONDS - start)) s"
}

# Waits until GET /rest/bootstrap answers, then posts the archives. The endpoint answers 503 until every start-up
# listener has run (the marker, the default texts and the settings included), and this loop asks again until it
# answers: the base ids rest on the bootstrap running after the start-up's own writes
bootstrap() {
    local body="$WORK/bootstrap.json" deadline=$((SECONDS + 120)) state
    TOKEN="$(sysadmin_token)"
    until curl_sysadmin -s -m 30 -o "$body" "http://127.0.0.1:$PORT/rest/bootstrap" \
            && state="$(json_string state "$body")" && [ -n "$state" ]; do
        (( SECONDS < deadline )) || die "GET /rest/bootstrap gave no state within 120 s (the backend is still starting?)"
        sleep 2
    done
    [ "$state" = pending ] || die "the new database's marker reads $state, not pending"
    NIORD_TOKEN="$TOKEN" scripts/bootstrap.sh "http://127.0.0.1:$PORT" "$@"
}

# Recomputes the area and category trees' sort order now rather than at the hourly job, so every build captures it
recompute_tree_sort_order() {
    TOKEN="$(sysadmin_token)"
    for tree in areas categories; do
        curl_sysadmin -s -f -m 120 -o /dev/null -X PUT "http://127.0.0.1:$PORT/rest/$tree/recompute-tree-sort-order" \
            || die "PUT /rest/$tree/recompute-tree-sort-order failed"
    done
}

# Imports the sample's messages with the message archive import (msg-archive-import), run as a sysadmin's batch set,
# and waits for the batch job. The batch set gives the job the properties POST /rest/message-io/import would; the
# script took this route while that endpoint failed on every upload (fixed since), and keeps it.
import_messages() {
    local stage="$WORK/messages-archive" set="$WORK/messages-batch-set" zip="$WORK/sample-messages-set.zip"
    local body="$WORK/import.txt" series
    rm -rf "$stage" "$set" "$zip"
    mkdir -p "$stage" "$set"
    cp "$SAMPLE_DIR/messages/messages.json" "$stage/messages.json"
    sample_tool attachments "$(native_path "$BE/$SAMPLE_DIR/messages/messages.json")" "$(native_path "$stage")"
    "$JAR" --create --no-manifest --file "$(native_path "$set/sample-messages.zip")" -C "$(native_path "$stage")" .
    # The import keeps the messages' statuses and uids, and each message names its own series: one of the sample's
    # six, which the endpoint would have listed as the sysadmin's (validMessageSeries). seriesId is the fallback the
    # import requires.
    series="$(sed -nE 's/.*"seriesId": *"([^"]+)".*/"\1"/p' "$SAMPLE_DIR/archive/domains.json" | paste -sd, -)"
    printf '[{"jobName": "msg-archive-import", "fileName": "sample-messages.zip", "properties": {%s}}]\n' \
        "\"seriesId\": \"dma-nw\", \"preserveStatus\": true, \"assignNewUids\": false, \"assignDefaultSeries\": false, \"createBaseData\": false, \"validMessageSeries\": [$series]" \
        > "$set/batch-set.json"
    "$JAR" --create --no-manifest --file "$(native_path "$zip")" -C "$(native_path "$set")" .
    TOKEN="$(sysadmin_token)"
    curl_sysadmin -s -m 120 -o "$body" -w '%{http_code}\n' \
        -F "file=@$(native_path "$zip");filename=sample-messages-set.zip;type=application/zip" \
        "http://127.0.0.1:$PORT/rest/batch/execute-batch-set" > "$WORK/import.status"
    [ "$(cat "$WORK/import.status")" = 200 ] || die "the batch set was refused: $(cat "$body")"
    ! grep -q "Error" "$body" || die "the batch set was refused: $(cat "$body")"
    echo "   $(tr -d '\n' < "$body")"
    local deadline=$((SECONDS + 600)) status
    while :; do
        curl_sysadmin -s -m 30 -o "$WORK/instances.json" \
            "http://127.0.0.1:$PORT/rest/batch/msg-archive-import/instances?page=0&pageSize=1" || true
        status="$(json_string batchStatus "$WORK/instances.json")"
        case "$status" in
            COMPLETED) break ;;
            FAILED|STOPPED|ABANDONED) die "the message import ended $status (see $WORK/backend-niord_sample.log)" ;;
        esac
        (( SECONDS < deadline )) || die "the message import did not end within 600 s"
        sleep 3
    done
    local count
    count="$(psql_db niord_sample -tAc 'select count(*) from "Message"')"
    local expected
    expected="$(grep -c '"repoPath"' "$SAMPLE_DIR/messages/messages.json")"
    [ "$count" = "$expected" ] || die "$count messages were imported, not the $expected of messages.json"
    # The import drops an area, category or chart it cannot resolve (createBaseData false): every link is checked.
    # SampleTool's lines are captured first: a failure inside a here-string would not stop the script
    local counts lines table n rows
    counts="$(sample_tool counts "$(native_path "$BE/$SAMPLE_DIR/messages/messages.json")" | tr -d '\r')" \
        || die "SampleTool counts failed (above)"
    lines="$(printf '%s\n' "$counts" | grep -c . || true)"
    [ "$lines" = 6 ] || die "SampleTool counts gave $lines tables, not the 6 link tables"
    while read -r table n; do
        [ -n "$table" ] || continue
        rows="$(docker exec "$CONTAINER" psql -v ON_ERROR_STOP=1 -q -U niord -d niord_sample -tA \
            -c "select count(*) from public.\"$table\"" | tr -d '\r')"
        [ "$rows" = "$n" ] || die "$rows rows of \"$table\" were imported, not the $n that messages.json names: the" \
            "import dropped what it could not resolve (an area, category or chart the archive lacks?)"
    done <<< "$counts"
    echo "   $count messages imported, with every area, category, chart, attachment, reference and geometry"
    # The next numbers of the yearly series, after the sample's own
    local series next
    for series in dma-nw dma-nm; do
        next="$(psql_db niord_sample -tAc "select coalesce(max(m.number), 0) + 1 from \"Message\" m
            join \"MessageSeries\" s on s.id = m.\"messageSeries_id\" where s.\"seriesId\" = '$series' and m.year = $SAMPLE_YEAR")"
        curl_sysadmin -s -f -m 30 -o /dev/null -X PUT -H 'Content-Type: application/json;charset=UTF-8' \
            -d "$next" "http://127.0.0.1:$PORT/rest/message-series/series/$series/number/$SAMPLE_YEAR" \
            || die "the next number of $series could not be set"
    done
}

# The capture, run in niord_sample after the temp tables residue, allowed and params: the baseline through
# postgres_fdw as schema base; every row of it checked unchanged in public; the rows public adds staged, normalised,
# renumbered from the first id, then written ordered into schema capture, which pg_dump dumps; the guard's data and
# the base rows' natural keys into schema regen_out.
capture_sql() {
    cat <<'SQL'
DROP SCHEMA IF EXISTS base CASCADE;
DROP SCHEMA IF EXISTS stage CASCADE;
DROP SCHEMA IF EXISTS capture CASCADE;
DROP SCHEMA IF EXISTS regen_out CASCADE;
DROP SCHEMA IF EXISTS regen CASCADE;
CREATE SCHEMA regen;
CREATE SCHEMA base;
CREATE SCHEMA stage;
CREATE SCHEMA capture;
CREATE SCHEMA regen_out;
CREATE EXTENSION postgres_fdw SCHEMA regen;
CREATE SERVER regen_base FOREIGN DATA WRAPPER postgres_fdw OPTIONS (host 'localhost', dbname 'niord_sample_base');
CREATE USER MAPPING FOR CURRENT_USER SERVER regen_base OPTIONS (user 'niord', password 'niord');
IMPORT FOREIGN SCHEMA public FROM SERVER regen_base INTO base;
CREATE TABLE regen.idmap (relname text, old bigint, new bigint, PRIMARY KEY (relname, old));
-- The tables the sample has ids in; the base rows its foreign keys name, the columns that hold them, their keys
CREATE TABLE regen_out.ids (relname text PRIMARY KEY);
CREATE TABLE regen_out.refs (parent text, id bigint);
CREATE TABLE regen_out.columns (child text, col text, parent text);
CREATE TABLE regen_out.keys (parent text, id bigint, shape text, parts text[]);

DO $capture$
DECLARE
    -- The columns a base row may differ in between the two builds: the bookkeeping, and the trees' derived order
    ex CONSTANT text := '''{version,created,updated,treeSortOrder}''::text[]';
    epoch CONSTANT timestamp := (SELECT p.epoch FROM params p);
    first_id CONSTANT bigint := (SELECT p.first_id FROM params p);
    t record;
    fk record;
    col text;
    cols text;
    names text;
    has_id boolean;
    n bigint;
    -- Rows of a table that are build residue: the batch jobs' numbers among the message series' numbers
    keep text;
BEGIN
    FOR t IN SELECT c.relname FROM pg_class c
             WHERE c.relnamespace = 'public'::regnamespace AND c.relkind = 'r'
               AND c.relname NOT IN (SELECT name FROM residue)
             ORDER BY 1 LOOP
        has_id := EXISTS (SELECT 1 FROM information_schema.columns
                          WHERE table_schema = 'public' AND table_name = t.relname AND column_name = 'id');
        keep := CASE t.relname WHEN 'Sequence' THEN '{a}.name NOT LIKE ''BATCH\_JOB\_%''' ELSE 'true' END;
        -- Every row of the baseline is in niord_sample, unchanged
        IF has_id THEN
            EXECUTE format('SELECT count(*) FROM base.%1$I b WHERE NOT EXISTS (SELECT 1 FROM public.%1$I s '
                           'WHERE s.id = b.id AND (to_jsonb(s) - %2$s) = (to_jsonb(b) - %2$s))', t.relname, ex) INTO n;
        ELSE
            EXECUTE format('SELECT count(*) FROM (SELECT to_jsonb(b) - %2$s FROM base.%1$I b WHERE %3$s '
                           'EXCEPT SELECT to_jsonb(s) - %2$s FROM public.%1$I s WHERE %4$s) x',
                           t.relname, ex, replace(keep, '{a}', 'b'), replace(keep, '{a}', 's')) INTO n;
        END IF;
        IF n > 0 THEN
            RAISE EXCEPTION 'the base differs between the two builds: % row(s) of "%" in niord_sample_base are not in niord_sample as they are there',
                n, t.relname;
        END IF;
        -- The rows niord_sample adds
        IF has_id THEN
            EXECUTE format('CREATE TABLE stage.%1$I AS SELECT s.* FROM public.%1$I s '
                           'WHERE NOT EXISTS (SELECT 1 FROM base.%1$I b WHERE b.id = s.id)', t.relname);
        ELSE
            EXECUTE format('CREATE TABLE stage.%1$I AS SELECT s.* FROM public.%1$I s WHERE %3$s AND NOT EXISTS '
                           '(SELECT 1 FROM base.%1$I b WHERE (to_jsonb(b) - %2$s) = (to_jsonb(s) - %2$s))',
                           t.relname, ex, replace(keep, '{a}', 's'));
        END IF;
        EXECUTE format('SELECT count(*) FROM stage.%I', t.relname) INTO n;
        IF n = 0 THEN
            EXECUTE format('DROP TABLE stage.%I', t.relname);
            CONTINUE;
        END IF;
        IF t.relname NOT IN (SELECT name FROM allowed) THEN
            RAISE EXCEPTION 'the sample has % row(s) in "%", a table outside the allow-list (SampleSafetyCheck.ALLOWED_TABLES)',
                n, t.relname;
        END IF;
        FOREACH col IN ARRAY ARRAY['created', 'updated', 'nextScheduledExecution'] LOOP
            IF EXISTS (SELECT 1 FROM information_schema.columns
                       WHERE table_schema = 'stage' AND table_name = t.relname AND column_name = col) THEN
                EXECUTE format('UPDATE stage.%1$I SET %2$I = $1 WHERE %2$I IS NOT NULL', t.relname, col) USING epoch;
            END IF;
        END LOOP;
    END LOOP;

    -- Normalised: no user; the area sort order without the random millionth AreaService adds to it, rounded to
    -- a thousandth; the uids the imports draw at random (geometries, publications) derived from the row ids
    IF to_regclass('stage."Message"') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM stage."Message"
                   WHERE abs(abs("areaSortOrder" * 1000) - floor(abs("areaSortOrder" * 1000)) - 0.5) < 0.002) THEN
            RAISE EXCEPTION 'the area sort order of message % lies within a millionth of a rounding boundary, so two builds would round it apart: move its geometry a little',
                (SELECT string_agg(uid, ', ') FROM stage."Message"
                 WHERE abs(abs("areaSortOrder" * 1000) - floor(abs("areaSortOrder" * 1000)) - 0.5) < 0.002);
        END IF;
        UPDATE stage."Message" SET "createdBy_id" = NULL, "lastUpdatedBy_id" = NULL,
                                   "areaSortOrder" = round("areaSortOrder"::numeric, 3)::double precision;
    END IF;
    IF to_regclass('stage."Publication"') IS NOT NULL THEN
        UPDATE stage."Publication" SET "publicationId" = md5('niord-sample/Publication/' || id)::uuid::text;
        UPDATE stage."Publication" SET "repoPath" = 'publications/' || substr("publicationId", 1, 1) || '/'
                                                    || substr("publicationId", 2, 2) || '/' || "publicationId";
    END IF;
    IF to_regclass('stage."FeatureCollection"') IS NOT NULL THEN
        UPDATE stage."FeatureCollection" SET uid = md5('niord-sample/FeatureCollection/' || id)::uuid::text
         WHERE uid IS NOT NULL;
    END IF;
    IF to_regclass('stage."Feature"') IS NOT NULL THEN
        UPDATE stage."Feature" SET uid = md5('niord-sample/Feature/' || id)::uuid::text WHERE uid IS NOT NULL;
    END IF;

    -- The attachments, as the import wrote them under the messages' folders. A row under temp/ is the import's own
    -- archive, which the job deletes when it ends, or an edit session; no other folder is the sample's
    IF to_regclass('stage."RepoFile"') IS NOT NULL THEN
        DELETE FROM stage."RepoFile" WHERE path NOT LIKE 'messages/%';
        IF EXISTS (SELECT 1 FROM stage."RepoFile" WHERE expires IS NOT NULL) THEN
            RAISE EXCEPTION 'a repository row of the sample expires: %',
                (SELECT string_agg(path, ', ') FROM stage."RepoFile" WHERE expires IS NOT NULL);
        END IF;
    END IF;

    -- Renumbered from the first id, in every table with an id, in the order of the ids the build gave the rows; a
    -- table without one keeps the order of its rows as they were (their text, read now). The md5-derived uids above
    -- stay those of the build's ids.
    FOR t IN SELECT c.relname FROM pg_class c WHERE c.relnamespace = 'stage'::regnamespace AND c.relkind = 'r'
             ORDER BY 1 LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'stage' AND table_name = t.relname AND column_name = 'id') THEN
            IF EXISTS (SELECT 1 FROM pg_constraint k
                       JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = k.conkey[1]
                       WHERE k.contype = 'f' AND k.conrelid = format('public.%I', t.relname)::regclass
                         AND a.attname = 'id') THEN
                RAISE EXCEPTION 'the id of "%" is a foreign key (a joined subclass): the renumbering gives each table its own ids',
                    t.relname;
            END IF;
            EXECUTE format('INSERT INTO regen.idmap SELECT %L, id, $1 - 1 + row_number() OVER (ORDER BY id) '
                           'FROM stage.%I', t.relname, t.relname) USING first_id;
            INSERT INTO regen_out.ids VALUES (t.relname);
        ELSE
            EXECUTE format('ALTER TABLE stage.%I ADD COLUMN regen_order text', t.relname);
            EXECUTE format('UPDATE stage.%I x SET regen_order = (to_jsonb(x) - ''regen_order'')::text', t.relname);
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM regen.idmap WHERE old >= first_id) THEN
        RAISE EXCEPTION 'the build gave sample rows ids at or above the first id %: raise SAMPLE_FIRST_ID', first_id;
    END IF;
    -- The trees' lineage ('/14/15/'), id by id; a sample row below a base row cannot be named by key there
    FOR t IN SELECT c.table_name AS relname FROM information_schema.columns c
             WHERE c.table_schema = 'stage' AND c.column_name = 'lineage' ORDER BY 1 LOOP
        EXECUTE format('SELECT string_agg(DISTINCT p.v, '', '') FROM stage.%1$I x, '
                       'unnest(string_to_array(trim(both ''/'' from x.lineage), ''/'')) p(v) '
                       'WHERE NOT EXISTS (SELECT 1 FROM regen.idmap m WHERE m.relname = %1$L AND m.old::text = p.v)',
                       t.relname) INTO names;
        IF names IS NOT NULL THEN
            RAISE EXCEPTION 'a sample row of "%" lies below a base row (its lineage names the base ids %): the renumbering cannot name it',
                t.relname, names;
        END IF;
        EXECUTE format('UPDATE stage.%1$I x SET lineage = ''/'' || (SELECT string_agg(m.new::text, ''/'' ORDER BY p.n) '
                       'FROM unnest(string_to_array(trim(both ''/'' from x.lineage), ''/'')) WITH ORDINALITY p(v, n) '
                       'JOIN regen.idmap m ON m.relname = %1$L AND m.old::text = p.v) || ''/'' '
                       'WHERE x.lineage IS NOT NULL', t.relname);
    END LOOP;
    -- Every foreign key to a sample row, then the ids themselves; a key to a base row stays, for SampleTool
    FOR fk IN SELECT DISTINCT cl.relname AS child, a.attname AS col, pl.relname AS parent
              FROM pg_constraint k
              JOIN pg_class cl ON cl.oid = k.conrelid
              JOIN pg_class pl ON pl.oid = k.confrelid
              JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = k.conkey[1]
              JOIN pg_attribute pa ON pa.attrelid = k.confrelid AND pa.attnum = k.confkey[1]
              WHERE k.contype = 'f' AND k.connamespace = 'public'::regnamespace AND pa.attname = 'id'
              ORDER BY 1, 2, 3 LOOP
        IF to_regclass(format('stage.%I', fk.child)) IS NOT NULL AND to_regclass(format('stage.%I', fk.parent)) IS NOT NULL THEN
            EXECUTE format('UPDATE stage.%1$I c SET %2$I = m.new FROM regen.idmap m '
                           'WHERE m.relname = %3$L AND m.old = c.%2$I', fk.child, fk.col, fk.parent);
        END IF;
    END LOOP;
    FOR t IN SELECT relname FROM regen_out.ids ORDER BY 1 LOOP
        EXECUTE format('UPDATE stage.%1$I x SET id = m.new FROM regen.idmap m WHERE m.relname = %1$L AND m.old = x.id',
                       t.relname);
    END LOOP;

    -- Written ordered by id, or in the order kept above
    FOR t IN SELECT c.relname FROM pg_class c WHERE c.relnamespace = 'stage'::regnamespace AND c.relkind = 'r'
             ORDER BY 1 LOOP
        IF t.relname IN (SELECT relname FROM regen_out.ids) THEN
            EXECUTE format('CREATE TABLE capture.%1$I AS SELECT * FROM stage.%1$I ORDER BY id', t.relname);
        ELSE
            SELECT string_agg(format('%I', c.column_name), ', ' ORDER BY c.ordinal_position) INTO cols
              FROM information_schema.columns c
             WHERE c.table_schema = 'stage' AND c.table_name = t.relname AND c.column_name <> 'regen_order';
            EXECUTE format('CREATE TABLE capture.%1$I AS SELECT %2$s FROM stage.%1$I ORDER BY regen_order',
                           t.relname, cols);
        END IF;
    END LOOP;

    -- The base rows the sample's foreign keys point at (a value no sample row has), and every foreign-key column
    FOR fk IN SELECT DISTINCT cl.relname AS child, a.attname AS col, pl.relname AS parent
              FROM pg_constraint k
              JOIN pg_class cl ON cl.oid = k.conrelid
              JOIN pg_class pl ON pl.oid = k.confrelid
              JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = k.conkey[1]
              JOIN pg_attribute pa ON pa.attrelid = k.confrelid AND pa.attnum = k.confkey[1]
              WHERE k.contype = 'f' AND k.connamespace = 'public'::regnamespace AND pa.attname = 'id'
              ORDER BY 1, 2, 3 LOOP
        IF to_regclass(format('capture.%I', fk.child)) IS NULL THEN
            CONTINUE;
        END IF;
        INSERT INTO regen_out.columns VALUES (fk.child, fk.col, fk.parent);
        IF to_regclass(format('capture.%I', fk.parent)) IS NOT NULL THEN
            EXECUTE format('INSERT INTO regen_out.refs SELECT DISTINCT %L, c.%I::bigint FROM capture.%I c '
                           'WHERE c.%I IS NOT NULL AND NOT EXISTS (SELECT 1 FROM capture.%I p WHERE p.id = c.%I)',
                           fk.parent, fk.col, fk.child, fk.col, fk.parent, fk.col);
        ELSE
            EXECUTE format('INSERT INTO regen_out.refs SELECT DISTINCT %L, c.%I::bigint FROM capture.%I c '
                           'WHERE c.%I IS NOT NULL', fk.parent, fk.col, fk.child, fk.col);
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM regen_out.refs WHERE parent IN (SELECT name FROM residue)) THEN
        RAISE EXCEPTION 'the sample refers to build residue: %',
            (SELECT string_agg(DISTINCT parent, ', ') FROM regen_out.refs WHERE parent IN (SELECT name FROM residue));
    END IF;
    IF EXISTS (SELECT 1 FROM regen_out.refs WHERE id >= first_id) THEN
        RAISE EXCEPTION 'a foreign key of the sample names a row of % at or above the first id that the sample does not hold',
            (SELECT string_agg(DISTINCT parent, ', ') FROM regen_out.refs WHERE id >= first_id);
    END IF;

    -- Each base row's natural key, in SampleReferences' shapes (shape codes as its keys file reads them)
    INSERT INTO regen_out.keys
    SELECT r.parent, r.id, k.shape, k.parts FROM (SELECT DISTINCT parent, id FROM regen_out.refs) r
    JOIN LATERAL (
        SELECT 'name'::text, ARRAY[e.name::text] FROM public."Dictionary" e WHERE r.parent = 'Dictionary' AND e.id = r.id
        UNION ALL
        SELECT 'key', ARRAY[d.name::text, e.dict_key::text] FROM public."DictionaryEntry" e
          JOIN public."Dictionary" d ON d.id = e.dictionary_id WHERE r.parent = 'DictionaryEntry' AND e.id = r.id
        UNION ALL
        SELECT 'typeId', ARRAY[e."typeId"::text] FROM public."PromulgationType" e
         WHERE r.parent = 'PromulgationType' AND e.id = r.id
        UNION ALL
        SELECT 'mrn', ARRAY[e.mrn::text] FROM public."Category" e
         WHERE r.parent = 'Category' AND e.id = r.id AND e.mrn IS NOT NULL
        UNION ALL
        SELECT 'name', ARRAY[d.name::text] FROM public."Category" e
          JOIN public."CategoryDesc" d ON d.entity_id = e.id AND d.lang = 'en'
         WHERE r.parent = 'Category' AND e.id = r.id AND e.mrn IS NULL
        UNION ALL
        SELECT 'category-mrn', ARRAY[c.mrn::text, o."indexNo"::text, e."paramId"::text] FROM public."TemplateParam" e
          JOIN public."Category_TemplateParam" o ON o."templateParams_id" = e.id
          JOIN public."Category" c ON c.id = o."Category_id"
         WHERE r.parent = 'TemplateParam' AND e.id = r.id AND c.mrn IS NOT NULL
        UNION ALL
        SELECT 'category', ARRAY[d.name::text, o."indexNo"::text, e."paramId"::text] FROM public."TemplateParam" e
          JOIN public."Category_TemplateParam" o ON o."templateParams_id" = e.id
          JOIN public."Category" c ON c.id = o."Category_id"
          JOIN public."CategoryDesc" d ON d.entity_id = c.id AND d.lang = 'en'
         WHERE r.parent = 'TemplateParam' AND e.id = r.id AND c.mrn IS NULL
        UNION ALL
        SELECT 'type', ARRAY[p.name::text, o."indexNo"::text, e."paramId"::text] FROM public."TemplateParam" e
          JOIN public."ParamType_TemplateParam" o ON o."templateParams_id" = e.id
          JOIN public."ParamType" p ON p.id = o."CompositeParamType_id"
         WHERE r.parent = 'TemplateParam' AND e.id = r.id
    ) k(shape, parts) ON true;
    SELECT string_agg(format('"%s" %s', r.parent, r.id), ', ' ORDER BY r.parent, r.id) INTO names
      FROM (SELECT DISTINCT parent, id FROM regen_out.refs) r
     WHERE (SELECT count(*) FROM regen_out.keys k WHERE k.parent = r.parent AND k.id = r.id) <> 1;
    IF names IS NOT NULL THEN
        RAISE EXCEPTION 'the sample refers to base rows with no natural key in SampleReferences'' shapes, or more than one (add the shape to SampleReferences and to this capture): %',
            names;
    END IF;
    IF EXISTS (SELECT 1 FROM regen_out.keys k
               WHERE array_position(k.parts, NULL) IS NOT NULL
                  OR EXISTS (SELECT 1 FROM unnest(k.parts) p(v) WHERE p.v ~ '[\t\n\r]')) THEN
        RAISE EXCEPTION 'a natural key of a base row the sample refers to is empty or holds a tab or a line break';
    END IF;
    -- The base parameter types the sample's template parameters name by name, which the guard checks too
    IF to_regclass('capture."TemplateParam"') IS NOT NULL THEN
        INSERT INTO regen_out.keys SELECT 'ParamType', b.id, 'name', ARRAY[b.name::text] FROM base."ParamType" b
         WHERE b.name IN (SELECT type FROM capture."TemplateParam");
    END IF;
END
$capture$;
SQL
}

# --- 0. the tool, the archives, the scratch databases ----------------------------------------------------------

t0=$SECONDS
rm -rf "$WORK"
mkdir -p "$WORK/home-base" "$WORK/home-sample" "$WORK/out"
build_classpath

BASE_ZIP="$WORK/niord-bootstrap.zip"
SAMPLE_ZIP="$WORK/niord-sample-archive.zip"
"$JAR" --create --no-manifest --file "$(native_path "$BASE_ZIP")" -C "$(native_path "$BE/$BOOTSTRAP_DIR")" .
"$JAR" --create --no-manifest --file "$(native_path "$SAMPLE_ZIP")" -C "$(native_path "$BE/$SAMPLE_DIR/archive")" .

echo ">> [1/8] Creating the scratch databases niord_sample_base and niord_sample"
no_sessions
DATABASES_CREATED=true
for db in niord_sample niord_sample_base; do
    docker exec "$CONTAINER" dropdb -U niord --if-exists "$db"
    docker exec "$CONTAINER" createdb -U niord -T template_postgis "$db"
done

echo ">> [2/8] Building niord_sample_base: the base product's bootstrap"
start_backend niord_sample_base "$WORK/home-base"
bootstrap "$(native_path "$BASE_ZIP")"
recompute_tree_sort_order
stop_backend

echo ">> [3/8] Building niord_sample: the base product's bootstrap and the sample's archive in one run, the messages"
start_backend niord_sample "$WORK/home-sample"
bootstrap "$(native_path "$BASE_ZIP")" "$(native_path "$SAMPLE_ZIP")"
import_messages
recompute_tree_sort_order
stop_backend

echo ">> [4/8] Capturing the rows niord_sample adds to niord_sample_base"
allowed="$(sample_tool tables | tr -d '\r')"
{
    echo "SET client_min_messages = warning;"
    echo "CREATE TEMP TABLE residue (name text PRIMARY KEY);"
    for t in $RESIDUE; do echo "INSERT INTO residue VALUES ('$t');"; done
    echo "CREATE TEMP TABLE allowed (name text PRIMARY KEY);"
    for t in $allowed; do echo "INSERT INTO allowed VALUES ('$t');"; done
    echo "CREATE TEMP TABLE params AS SELECT timestamp '$SAMPLE_EPOCH' AS epoch, $SAMPLE_FIRST_ID::bigint AS first_id;"
    capture_sql
} | psql_db niord_sample || die "the capture failed (above)"

echo ">> [5/8] Writing the SQL"
CANDIDATE="$WORK/out/niord-sample.sql"
# A value from niord_sample. Not psql_db, whose docker exec -i would read the loops' standard input
q() { docker exec "$CONTAINER" psql -v ON_ERROR_STOP=1 -q -U niord -d niord_sample -tA -c "$1" | tr -d '\r'; }
# Every value is written to a file first, so a failing query or SampleTool stops the run (set -e sees no failure
# inside a here-document's $(...))
q "select parent || E'\t' || id || E'\t' || shape || E'\t' || array_to_string(parts, E'\t') from regen_out.keys
   order by parent, id" > "$WORK/keys.tsv"
q "select child || E'\t' || col || E'\t' || parent from regen_out.columns order by child, col" > "$WORK/columns.tsv"
q "select relname || E'\t' || (to_regclass(format('public.%I', relname || '_SEQ')) is not null)
   from regen_out.ids order by relname" > "$WORK/ids.tsv"
changesets="$(q "select string_agg(id, ',' order by id) from public.databasechangelog")"
[ -n "$changesets" ] || die "no Liquibase changesets in niord_sample"
domains="$(q "select string_agg(quote_literal(\"domainId\"), ', ' order by \"domainId\") from capture.\"Domain\"")"
series="$(q "select string_agg(quote_literal(\"seriesId\"), ', ' order by \"seriesId\") from capture.\"MessageSeries\"")"
[ -n "$domains" ] && [ -n "$series" ] || die "the sample holds no domain or no message series"
sample_tool guard-keys "$(native_path "$WORK/keys.tsv")" > "$WORK/guard-keys.sql" \
    || die "SampleTool guard-keys failed (above)"
# Each key resolves to exactly one row in niord_sample, the sample's own rows beside the base's: so the
# references resolve while the sample's rows are inserted, and on the base the guard reads before them
{
    echo 'DO $check$'
    echo 'DECLARE'
    echo "    unclean CONSTANT text := 'in niord_sample, where the sample is made';"
    echo '    missing text;'
    echo 'BEGIN'
    cat "$WORK/guard-keys.sql"
    echo 'END'
    echo '$check$;'
} | psql_db niord_sample || die "a base row the sample names does not resolve by its natural key to exactly one row (above)"
docker exec "$CONTAINER" pg_dump -U niord --data-only --column-inserts --disable-triggers --no-owner \
    --no-privileges --schema=capture niord_sample \
    | tr -d '\r' \
    | grep -vE '^\\(un)?restrict ' \
    | grep -vE "^SELECT pg_catalog\.set_config\('search_path'" \
    | grep -vE '^-- (Dumped from|Dumped by) ' \
    | sed -E 's/^(INSERT INTO|ALTER TABLE) capture\./\1 public./; s/Schema: capture;/Schema: public;/' \
    > "$WORK/dump.sql"
sample_tool references "$(native_path "$WORK/keys.tsv")" "$(native_path "$WORK/columns.tsv")" \
    < "$WORK/dump.sql" > "$WORK/dump-references.sql" || die "SampleTool references failed (above)"
{
    cat <<EOF
-- The sample (be/niord/sample/README.md, docs/fe-country-split-plan.md P9): the rows it adds on top of the base
-- product's bootstrap, loaded only into the loopback database niord_sample, or a country launcher's
-- niord_sample_<cc>, by be/niord/scripts/load-sample.sh. Its own ids start at $SAMPLE_FIRST_ID in every table, and
-- it names the base rows by natural key, so it loads on any country's backend after the base product's bootstrap.
-- Generated by be/niord/scripts/regen-sample.sh, never edited: regenerate it after a change to the base product's
-- bootstrap, the schema or the sample's sources.
$SENTINEL

\\set ON_ERROR_STOP on

BEGIN;

-- The guard: the sample's id range is free, its domains and series are not there yet, and the base rows it names
-- resolve by natural key, so it loads on the base product's bootstrap whatever ids a country's first start took
DO \$guard\$
DECLARE
    stale CONSTANT text := 'regenerate the sample with be/niord/scripts/regen-sample.sh';
    unclean CONSTANT text := 'the database is not one clean run of the base product''s bootstrap (an interrupted bootstrap, or rows added since): recreate it with ./start-local.sh --sample --reset; regenerate the sample with be/niord/scripts/regen-sample.sh only if the base product''s bootstrap, schema or entities changed since the sample was made';
    taken text;
    missing text;
BEGIN
    IF current_database() !~ '^niord_sample(_[a-z]{2})?\$' THEN
        RAISE EXCEPTION 'niord-sample: the sample is loaded only into niord_sample or niord_sample_<cc>, not %', current_database();
    END IF;
    IF to_regclass('public."BootstrapState"') IS NULL THEN
        RAISE EXCEPTION 'niord-sample: % has no Niord schema: the backend builds it, and bootstraps the database, first (./start-local.sh --sample)', current_database();
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public."BootstrapState" WHERE state = 'applied') THEN
        RAISE EXCEPTION 'niord-sample: the bootstrap marker of % does not read applied: the sample is loaded after the base product''s bootstrap (./start-local.sh --sample)', current_database();
    END IF;
    IF (SELECT string_agg(id, ',' ORDER BY id) FROM public.databasechangelog)
            IS DISTINCT FROM '$changesets' THEN
        RAISE EXCEPTION 'niord-sample: the schema''s Liquibase changesets are not the ones the sample was made on; %', stale;
    END IF;
EOF
    while IFS=$'\t' read -r table seq; do
        [ -n "$table" ] || continue
        if [ "$seq" = true ]; then
            taken="EXISTS (SELECT 1 FROM public.\"$table\" WHERE id >= $SAMPLE_FIRST_ID) OR (SELECT last_value FROM public.\"${table}_SEQ\") >= $SAMPLE_FIRST_ID"
        else
            taken="EXISTS (SELECT 1 FROM public.\"$table\" WHERE id >= $SAMPLE_FIRST_ID)"
        fi
        cat <<EOF
    IF $taken THEN
        RAISE EXCEPTION 'niord-sample: "$table" already holds a row, or its sequence is, where the sample''s go (id >= $SAMPLE_FIRST_ID); %', unclean;
    END IF;
EOF
    done < "$WORK/ids.tsv"
    cat <<EOF
    taken := (SELECT string_agg(x, ', ' ORDER BY x) FROM (
        SELECT "domainId" FROM public."Domain" WHERE "domainId" IN ($domains)
        UNION ALL SELECT "seriesId" FROM public."MessageSeries" WHERE "seriesId" IN ($series)) s(x));
    IF taken IS NOT NULL THEN
        RAISE EXCEPTION 'niord-sample: the database already holds domains or message series the sample brings (%): a --sample run posts the base product''s archive only; %', taken, unclean;
    END IF;
EOF
    cat "$WORK/guard-keys.sql"
    cat <<EOF
END
\$guard\$;

EOF
    cat "$WORK/dump-references.sql"
    echo "-- The sequences: Hibernate hands out ids above the sample's"
    while IFS=$'\t' read -r table seq; do
        [ -n "$table" ] && [ "$seq" = true ] || continue
        echo "SELECT pg_catalog.setval('public.\"${table}_SEQ\"', greatest((SELECT last_value FROM public.\"${table}_SEQ\"), (SELECT max(id) FROM public.\"$table\")));"
    done < "$WORK/ids.tsv"
    echo
    echo "COMMIT;"
} > "$CANDIDATE"
echo "   $(grep -c '^INSERT' "$CANDIDATE") inserts, $(grep -c '^INSERT INTO public."RepoFile"' "$CANDIDATE") attachment rows"

echo ">> [6/8] Checking the safety rules"
sample_tool check "$(native_path "$CANDIDATE")" \
    || die "the candidate breaks the safety rules (above): nothing was written over the committed sample"

echo ">> [7/8] Loading it into the base-only build, renamed niord_sample: the schema check"
docker exec "$CONTAINER" dropdb -U niord niord_sample
docker exec "$CONTAINER" psql -U niord -d postgres -qc 'ALTER DATABASE niord_sample_base RENAME TO niord_sample'
# load-sample.sh reads the sample folder it lives beside, so the candidate is loaded from a copy of that layout
mkdir -p "$WORK/check/niord/sample" "$WORK/check/niord/scripts"
cp "$CANDIDATE" "$WORK/check/niord/sample/niord-sample.sql"
cp "$SCRIPT_DIR/load-sample.sh" "$WORK/check/niord/scripts/load-sample.sh"
"$WORK/check/niord/scripts/load-sample.sh" --host localhost --db niord_sample \
    || die "the candidate does not load on a database the base product's bootstrap built (above)"
set +e
"$WORK/check/niord/scripts/load-sample.sh" --host localhost --db niord_sample
code=$?
set -e
[ "$code" = 3 ] || die "a second load answered $code, not 3 (already loaded)"

echo ">> [8/8] Writing the sample"
if [ -n "${NIORD_SAMPLE_OUT:-}" ]; then
    DEST="$NIORD_SAMPLE_OUT"
else
    DEST="$BE/$SAMPLE_DIR"
fi
mkdir -p "$DEST"
cp "$CANDIDATE" "$DEST/niord-sample.sql"
echo ">> The sample was regenerated in $((SECONDS - t0)) s: $DEST/niord-sample.sql"
[ -n "${NIORD_SAMPLE_OUT:-}" ] || echo "   Review the diff (git status niord/sample) and commit it."
