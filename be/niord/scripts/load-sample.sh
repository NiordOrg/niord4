#!/usr/bin/env bash
#
# Loads the sample (be/niord/sample/, docs/fe-country-split-plan.md P9) into the loopback database niord_sample,
# or a country launcher's niord_sample_<cc>: niord-sample.sql with psql, in one transaction. The attachment files
# are rows of it (the repository store), so nothing is copied into the Niord home.
#
# Usage:  be/niord/scripts/load-sample.sh [--host <host>] [--db <name>] [--container <name>]
#
#   --host       where the database listens: 127.0.0.1 (the default), localhost or ::1 only
#   --db         the database: niord_sample (the default), or niord_sample_<cc> (a two-letter lower-case country
#                code, a country's launcher's), and no other
#   --home       accepted and ignored: the attachments are rows of the SQL since the repository moved into the
#                database, and a launcher of those days may still pass the folder
#   --container  the PostgreSQL container that holds it: niord-db (the default). Its one other caller,
#                regen-test-seed-postgres.sh, names its throwaway container, where it builds the integration suite's
#                fixture
#
# The sample is loaded after the base product's bootstrap, on a database the backend built and bootstrapped, while no
# backend runs on it: ./start-local.sh --sample does all of it. The safety rules of P9 hold here:
#   * it refuses (exit 1, naming the rule) unless the host is loopback, the container (niord-db, or --container's)
#     publishes its port 5432 on loopback only, and the database is niord_sample or niord_sample_<cc>. psql runs
#     inside the container;
#   * it writes no row when the database already holds the sample (exit 3): the domain niord-nw and a message
#     titled "SAMPLE —";
#   * the SQL refuses again by itself, before it inserts a row: unless the database is niord_sample or
#     niord_sample_<cc>, its bootstrap marker reads applied, its Liquibase changesets are the ones the sample was
#     made on, no table holds a row at or above the sample's first id 1,000,001 (nor has its sequence there), the
#     sample's own domains and message series are not there yet, and every base row the sample names resolves by
#     its natural key, once. A refusal names its reason and its remedy. Other changesets: the sample was made on
#     another schema, and is regenerated with be/niord/scripts/regen-sample.sh. A taken id range, a domain or
#     series of the sample's, or a base row that does not resolve: the database is not one clean run of the base
#     product's bootstrap (an interrupted bootstrap, or rows added since), and is recreated with
#     ./start-local.sh --sample --reset (from the repository root); the sample is regenerated only if the base
#     product's bootstrap, schema or entities changed since it was made.
# It never creates or drops a database. The argument rules are tested by LoadSampleScriptTest; the SQL's own
# refusals by SampleOnACountryBaseITTest and the regeneration's schema check (a load, then exit 3).
#
# Exit codes: 0 loaded, 1 refused or failed, 2 usage, 3 already loaded.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SAMPLE_DIR="$(cd "$SCRIPT_DIR/../sample" && pwd)"
SQL="$SAMPLE_DIR/niord-sample.sql"
CONTAINER=niord-db

usage() {
    sed -n '2,38p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

refuse() {
    echo "!! load-sample.sh refuses: $*" >&2
    exit 1
}

HOST=127.0.0.1
DB=niord_sample
while (( $# )); do
    case "$1" in
        --host) HOST="${2:-}"; shift 2 || usage ;;
        --db) DB="${2:-}"; shift 2 || usage ;;
        --home) shift 2 || usage ;;
        --container) CONTAINER="${2:-}"; shift 2 || usage ;;
        -h|--help) usage ;;
        *) echo "!! Unknown argument: $1" >&2; usage ;;
    esac
done
[ -n "$CONTAINER" ] || usage

# Rules on the arguments first, before any container is asked: a loopback host
case "$HOST" in
    127.0.0.1|localhost|::1) ;;
    *) refuse "the database host must be loopback (127.0.0.1, localhost or ::1), not $HOST" ;;
esac

# Rule: the database niord_sample, or niord_sample_<cc> with a two-letter lower-case (ASCII) country code
case "$DB" in
    niord_sample|niord_sample_[abcdefghijklmnopqrstuvwxyz][abcdefghijklmnopqrstuvwxyz]) ;;
    *) refuse "the sample is loaded only into the database niord_sample or niord_sample_<cc> (a two-letter" \
           "lower-case country code), not $DB" ;;
esac

# Rule: a container that publishes its port on loopback only
bindings="$(docker port "$CONTAINER" 5432/tcp 2>/dev/null)" \
    || refuse "the container $CONTAINER does not run, or publishes no port 5432"
[ -n "$bindings" ] || refuse "the container $CONTAINER publishes no port 5432"
while read -r binding; do
    case "$binding" in
        127.0.0.1:*|"[::1]:"*) ;;
        *) refuse "the container $CONTAINER publishes port 5432 on $binding, not on loopback only" ;;
    esac
done <<< "$bindings"

[ -f "$SQL" ] || { echo "!! $SQL is missing: regenerate it with be/niord/scripts/regen-sample.sh" >&2; exit 1; }

psql_value() {
    docker exec "$CONTAINER" psql -v ON_ERROR_STOP=1 -U niord -d "$DB" -tAc "$1"
}

# The sample is there when its domain and a message titled "SAMPLE —" are (chr(8212) is the dash)
loaded="$(psql_value "select exists (select 1 from \"Domain\" where \"domainId\" = 'niord-nw')
                          and exists (select 1 from \"MessageDesc\" where title like 'SAMPLE ' || chr(8212) || '%')")" \
    || { echo "!! $DB cannot be read: does it exist, and has a backend built its schema?" >&2; exit 1; }

if [ "$loaded" = t ]; then
    echo ">> $DB already holds the sample: nothing is loaded"
    exit 3
fi

echo ">> Loading $SQL into $DB"
docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -q -U niord -d "$DB" < "$SQL" >/dev/null \
    || { echo "!! The sample was not loaded (above): no row was written" >&2; exit 1; }
echo ">> The sample is loaded"
