#!/usr/bin/env bash
#
# Regenerates the integration suite's PostgreSQL fixture:
#   niord/src/test/resources/db/niord-test-seed.sql   (full schema + data; the reference)
#   niord/src/test/resources/db/niord-base-data.sql   (data only; loaded on top of the Liquibase schema by the IT
#                                                         suite, and by nothing else)
#
# The fixture is a dump of a database built the way a sample deployment is (docs/code-data-config-plan.md §7.4, §8):
#   * the base product's bootstrap  niord/bootstrap/ (zipped with the JDK's jar tool), applied through the bootstrap
#                                   runner by org.niord.test.SeedRegen, which starts the IT suite's own application
#                                   on a throwaway PostGIS container: Liquibase builds the schema from the production
#                                   master changelog (ddl-auto=validate, the real path: DefaultsLedger and
#                                   BootstrapState have no JPA entity), the first start writes the base product's
#                                   texts and settings, and the runner applies the archive with the same JBeret jobs
#                                   and waits as the bootstrap command (§7.2), then SeedRegen exits with the outcome;
#   * the sample                    niord/sample/niord-sample.sql (docs/fe-country-split-plan.md P9), loaded after it
#                                   by its own loader, niord/scripts/load-sample.sh, into the container's niord_sample.
# Run it after niord/scripts/regen-sample.sh whenever both are due: the sample's SQL refuses, naming regen-sample.sh,
# a schema it was not made on.
#
# The application's class path is niord/target/test-classes without its defaults/ (the stand-in da pack), its
# root-level *.properties and niord.json (the test-jar's own exclusions, niord/pom.xml), so that the first start writes
# the base product's texts and settings only, the same rows niord-app writes for regen-sample.sh: the fixture's base
# rows stay those of the base product
# (the integration tests' expectations rest on them). The sample itself would load beside the test tree's texts too
# (its ids start at 1,000,001 and it names the base rows by natural key). application.yml stays (the S-124 producer
# code DK00).
#
# The script then normalises created and updated to the sample's epoch (the bootstrap's rows carry their write
# time), replaces the sample's message uid prefix (the uids' common start up to their fourth dash) with the fixture's
# own in the messages' uid and repository paths, the attachments' paths and the other columns that may hold a uid
# (a reference's messageId, an issue member's or override's messageUid), keeping its first three hex digits, which
# name the repository folders (messages/5/a3/), orders every table with a primary key by it (CLUSTER, its mark on the
# table dropped again), and pg_dumps the result twice:
#
#   * the FULL dump (schema + data) — the human-readable reference for "what's in the fixture"; and
#   * a DATA-ONLY dump (--column-inserts --disable-triggers) — what the integration tests actually load.
#   Both leave out the rows a build writes that are not data (the same residue regen-sample.sh never captures): the
#   bootstrap marker, the ledger, Liquibase's tables, users, batch data and the batch jobs' numbers, histories,
#   settings and the search index. A database seeded from the fixture reads existing at its first start, Liquibase
#   keeps its own bookkeeping, the start-up writes the settings and adopts the texts as on any deployment's first
#   start, and a batch job's first run numbers it 1 (BatchService creates a missing counter). The id sequences of the
#   left-out tables (BatchData_SEQ, ScriptResourceHistory_SEQ) keep the value the build advanced them to, which is
#   harmless: at or past every id.
#
# Last, it runs the no-sample check (k8s/scripts/check-no-sample.sh) over the folder the dumps were written to, and
# fails on a finding. The fixture travels in niord-base's test-jar, which Backend CI and publish.yml check: the uid
# prefix is a marker the check knows the sample by, so the dumps carry the fixture's prefix instead, and no sample uid
# is written in this script.
#
# The integration tests load the data-only dump on top of the Liquibase-built schema as a final changeset —
# "Liquibase first, dump on top" (see the test resource db/changelog/changelog-with-seed.yaml). The data-only
# dump uses --disable-triggers because V001's circular / self-referential foreign keys (Area, Category,
# Domain<->FiringSchedule, Publication) are non-deferrable.
#
# No Keycloak is needed: the regen boot supplies a dummy jwk-set-uri (a JWK-set decoder is created
# lazily, never contacted during the bootstrap), so security wiring starts without an IdP.
#
# Run only when the base product's bootstrap, the sample, the JPA entities or the schema change: a changed
# niord-base-data.sql changes the checksum of the `seed-base-data` changeset, which only a fresh IT database applies
# (docs/test-infrastructure.md). Never edit the two files by hand; two runs give the same bytes. TestFixtureCurrentTest
# fails when the fixture's templates no longer match niord/bootstrap/templates/ and the sample's, or when the fixture
# carries the sample's sentinel line or uid prefix. Requires Docker + JDK 25.
#
# Usage:  JAVA_HOME=$(/usr/libexec/java_home -v 25) niord/scripts/regen-test-seed-postgres.sh
#         NIORD_SEED_OUT=/tmp/seed ...                  writes both dumps under that directory instead of
#                                                       over the committed files — to compare, not to commit.
#                                                       The directory must hold nothing but the two dumps:
#                                                       step 9 checks all of it, sub-folders included
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# Multi-arch (arm64 + amd64) PostGIS 3.6 / PostgreSQL 18. The official postgis/postgis:18-* images are
# amd64-only; imresamu/postgis is a faithful multi-arch rebuild, so this works on Apple Silicon and CI.
POSTGIS_IMAGE="imresamu/postgis:18-3.6"
if [ -n "${NIORD_SEED_OUT:-}" ]; then
  OUT_SQL="$NIORD_SEED_OUT/niord-test-seed.sql"
  OUT_DATA_SQL="$NIORD_SEED_OUT/niord-base-data.sql"
else
  OUT_SQL="niord/src/test/resources/db/niord-test-seed.sql"
  OUT_DATA_SQL="niord/src/test/resources/db/niord-base-data.sql"
fi
DB_CONTAINER="niord-seed-pg"
DB_PORT="15432"
# The sample's database name, one of the two its loader and its SQL accept (niord_sample, a country launcher's
# niord_sample_<cc>). It lives only in the throwaway container.
DB_NAME="niord_sample"
APP_PORT="8889"
HOME_DIR="$REPO_ROOT/niord/target/niordhome-seed-pg"
APP_LOG="$REPO_ROOT/niord/target/niord-seed-pg-boot.log"
CLASSPATH_FILE="niord/target/seed-classpath.txt"
TEST_CLASSES="niord/target/seed-test-classes"
BOOTSTRAP_DIR="niord/bootstrap"
BOOTSTRAP_ZIP="niord/target/seed-bootstrap.zip"
SAMPLE_DIR="niord/sample"
SAMPLE_SQL="$SAMPLE_DIR/niord-sample.sql"
# The sample's epoch (regen-sample.sh's SAMPLE_EPOCH): every row's created and updated
EPOCH="2026-10-22 00:00:00"
# The rows a build writes that are not data, left out of both dumps (regen-sample.sh's RESIDUE)
RESIDUE="spatial_ref_sys BootstrapState DefaultsLedger databasechangelog databasechangeloglock User BatchData
MessageHistory ScriptResourceHistory Setting message_search_text"
# A marker on the java command line, so the cleanup finds this boot and nothing else.
MARKER="niord.seed-regen=true"
APP_PID=""

[ -f "$BOOTSTRAP_DIR/batch-set.json" ] || { echo "!! $BOOTSTRAP_DIR/batch-set.json is missing" >&2; exit 1; }
[ -f "$SAMPLE_SQL" ] || { echo "!! $SAMPLE_SQL is missing" >&2; exit 1; }
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
command -v "$JAVA" >/dev/null 2>&1 || { echo "!! java not found (set JAVA_HOME to a JDK 25)" >&2; exit 1; }
command -v "$JAR" >/dev/null 2>&1 || { echo "!! jar not found (set JAVA_HOME to a JDK 25)" >&2; exit 1; }

cleanup() {
  [ -n "$APP_PID" ] && kill "$APP_PID" >/dev/null 2>&1 || true
  pkill -f "$MARKER" >/dev/null 2>&1 || true
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$HOME_DIR"
}
trap cleanup EXIT

echo ">> [1/9] Compiling niord with its tests, and resolving the test classpath ..."
# test-compile, not package: the application is booted from target/classes + a copy of target/test-classes, with
# the test-scope dependencies — which is where the deployment-tier pieces (the PostgreSQL driver, the
# Liquibase starter, the embedded Artemis broker) live in niord's pom. The classpath file is what
# `java -cp` below reads.
# clean first: the resources plugin never deletes an output whose source is gone, so a stale target/ would feed the
# first start a text or setting the committed tree does not have, and the dumps would hold rows no checkout writes.
./mvnw -q -ntp -pl niord clean test-compile
./mvnw -q -ntp -pl niord dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="target/seed-classpath.txt"
[ -s "$CLASSPATH_FILE" ] || { echo "!! $CLASSPATH_FILE was not written" >&2; exit 1; }
# The test classes without the stand-in pack, the root-level texts and niord.json (see the header)
rm -rf "$TEST_CLASSES"
cp -R niord/target/test-classes "$TEST_CLASSES"
rm -f "$TEST_CLASSES"/*.properties "$TEST_CLASSES/niord.json"
rm -rf "${TEST_CLASSES:?}/defaults"
CP="$REPO_ROOT/$TEST_CLASSES:$REPO_ROOT/niord/target/classes:$(cat "$CLASSPATH_FILE")"
# The class path goes to java in an argument file: on Windows (Git Bash) it is longer than a command line may
# be. There the two folders are written as Windows paths with forward slashes (an argument file reads a
# backslash as an escape) and joined with ';', as the classpath Maven wrote already is.
ARGS_FILE="niord/target/seed-java.args"
if command -v cygpath >/dev/null 2>&1; then
  CP_NATIVE="$(cygpath -m "$REPO_ROOT/$TEST_CLASSES");$(cygpath -m "$REPO_ROOT/niord/target/classes");$(tr '\\' '/' < "$CLASSPATH_FILE")"
else
  CP_NATIVE="$CP"
fi
printf -- '-cp\n"%s"\n' "$CP_NATIVE" > "$ARGS_FILE"

echo ">> [2/9] Starting throwaway PostGIS ($POSTGIS_IMAGE) on loopback port $DB_PORT ..."
# Published on loopback only, as the sample's loader demands of the container it loads into.
docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$DB_CONTAINER" \
  -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER=niord -e POSTGRES_PASSWORD=niord \
  -p "127.0.0.1:${DB_PORT}:5432" "$POSTGIS_IMAGE" >/dev/null
# pg_isready answers during the image's init too (its temporary server), so wait for a query to the database after
until docker exec "$DB_CONTAINER" pg_isready -U niord -d "$DB_NAME" >/dev/null 2>&1; do sleep 2; done
until docker exec "$DB_CONTAINER" psql -U niord -d "$DB_NAME" -tAc "select 1" >/dev/null 2>&1; do sleep 2; done
# The postgis image already installs the extension in POSTGRES_DB on init; this is a tolerant belt-and-
# suspenders (|| true: ignore the harmless "already exists" race with the image's own init script).
docker exec "$DB_CONTAINER" psql -U niord -d "$DB_NAME" -c "CREATE EXTENSION IF NOT EXISTS postgis;" >/dev/null 2>&1 || true
echo "   PostGIS ready."

echo ">> [3/9] Preparing an empty niord.home and zipping $BOOTSTRAP_DIR ..."
# Empty on purpose: the run starts clean, with no batch-job files or repository content left by an earlier one.
rm -rf "$HOME_DIR"
mkdir -p "$HOME_DIR"
rm -f "$BOOTSTRAP_ZIP"
"$JAR" --create --no-manifest --file "$BOOTSTRAP_ZIP" -C "$BOOTSTRAP_DIR" .

echo ">> [4/9] Starting the application and bootstrapping the new database (SeedRegen) ..."
# NIORD_HOME must be a system property / env var (not just the spring `niord.home` property): SettingsService
# resolves the ${NIORD_HOME} token from there, else its expander live-locks (the documented S1 landmine).
# SeedRegen applies the archive in one run, and exits 0 when the bootstrap was applied.
# -Dniord.home, a SYSTEM property beside the Spring argument: the @Setting defaults "${niord.home}/repo",
# ".../publication-archive", ".../message-index" are expanded at bean creation, and the expander reads system
# properties and the environment, not Spring's. Without it the paths collapse to /repo etc. and the start
# aborts on the unsafe publication root (PublicationPathService).
# -Duser.timezone=UTC, as regen-sample.sh starts niord-app: a run writes the same rows on any machine.
mkdir -p "$(dirname "$APP_LOG")"
"$JAVA" "-D$MARKER" -Duser.timezone=UTC -DNIORD_HOME="$HOME_DIR" -Dniord.home="$HOME_DIR" \
  "@$ARGS_FILE" org.niord.test.SeedRegen "$REPO_ROOT/$BOOTSTRAP_ZIP" \
  --server.port="$APP_PORT" \
  --spring.datasource.url="jdbc:postgresql://localhost:${DB_PORT}/${DB_NAME}" \
  --spring.datasource.username=niord --spring.datasource.password=niord \
  --spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/.well-known/jwks.json \
  --niord.home="$HOME_DIR" \
  > "$APP_LOG" 2>&1 &
APP_PID=$!

echo "   waiting for the bootstrap to end ..."
# SeedRegen exits with the outcome once the runner has waited for every job, so there is no settle loop.
# Hard-fail rather than fall through: dumping a database the bootstrap did not finish would overwrite the
# fixture with a partial one.
if wait "$APP_PID"; then
  APP_PID=""
  echo "   $(grep -m1 'SeedRegen: bootstrap' "$APP_LOG" || echo 'bootstrap applied')"
else
  APP_PID=""
  echo "!! the bootstrap was not applied; NOT dumping - see $APP_LOG"; tail -80 "$APP_LOG"; exit 1
fi

echo ">> [5/9] Pruning unused PostGIS sub-extensions (tiger/topology/fuzzystrmatch) ..."
# The postgis image installs the full PostGIS suite, but Niord only needs the core `postgis` extension
# (geometry type + ST_* functions). pg_dump emits non-idempotent `CREATE SCHEMA tiger/topology`, which
# collide with the test image's pre-installed suite and abort the fixture load. Drop them so the dumped fixture
# contains only the core postgis extension + the public Niord schema, and loads cleanly anywhere.
docker exec -i "$DB_CONTAINER" psql -U niord -d "$DB_NAME" >/dev/null 2>&1 <<'SQL' || true
DROP EXTENSION IF EXISTS postgis_tiger_geocoder CASCADE;
DROP EXTENSION IF EXISTS postgis_topology CASCADE;
DROP EXTENSION IF EXISTS fuzzystrmatch CASCADE;
DROP SCHEMA IF EXISTS tiger CASCADE;
DROP SCHEMA IF EXISTS tiger_data CASCADE;
DROP SCHEMA IF EXISTS topology CASCADE;
SQL

echo ">> [6/9] Loading the sample (load-sample.sh) ..."
# Its own loader, with every rule it has: a loopback container, the database niord_sample, and the SQL's guard,
# which checks that the sample's id range is free and the base rows it names resolve by natural key. Anything but 0
# (loaded) stops the run.
if ! niord/scripts/load-sample.sh --container "$DB_CONTAINER"; then
  echo "!! the sample was not loaded (above); NOT dumping. A refusal that names regen-sample.sh: run it first." >&2
  exit 1
fi

echo ">> [6b/9] Checking the modelLanguages Setting ..."
# The first start adds modelLanguages from the base product's settings file. Before C3b a read of a missing
# setting wrote it with a NULL value; since C3b no read writes a setting, so a NULL or missing row here is a
# regression of the start-up, and the regen stops rather than dump it. (The Setting rows are not dumped: each IT
# start writes them from its own class path.)
model_languages=$(docker exec -i "$DB_CONTAINER" psql -U niord -d "$DB_NAME" -tA -c \
    "SELECT setting_value FROM \"Setting\" WHERE setting_key = 'modelLanguages' AND setting_value IS NOT NULL")
if [ -z "$model_languages" ]; then
  echo "!! The modelLanguages Setting is NULL or missing after the first start: a start-up regression." >&2
  exit 1
fi
echo "   modelLanguages = $model_languages"

echo ">> [7/9] Normalising: created and updated at $EPOCH, the message uid prefix, the batch jobs' numbers, every table in key order ..."
# The sample's message uid prefix, read from the database: the uids' common start up to their fourth dash, the marker
# by which the no-sample check knows a copy of the sample. The fixture's prefix keeps its first three hex digits, so
# the repository folders a uid names (messages/<1>/<2-3>/<uid>) stay as they are.
sample_prefix=$(docker exec "$DB_CONTAINER" psql -v ON_ERROR_STOP=1 -U niord -d "$DB_NAME" -tAc \
    "SELECT DISTINCT left(uid, 24) FROM \"Message\"")
if [ "$(printf '%s\n' "$sample_prefix" | grep -c .)" != 1 ] \
    || ! printf '%s' "$sample_prefix" | grep -qE '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-$'; then
  echo "!! The messages' uids do not share one prefix up to their fourth dash; NOT dumping:" >&2
  printf '%s\n' "$sample_prefix" >&2
  exit 1
fi
fixture_prefix="${sample_prefix:0:3}f1c70-0000-4000-8000-"
if [ "$fixture_prefix" = "$sample_prefix" ]; then
  echo "!! The sample's uid prefix is the fixture's own; NOT dumping" >&2
  exit 1
fi
# The bootstrap's rows (Category, PromulgationType, ScriptResource, ...) carry their write time; the sample's are
# at its epoch already. The uid prefix is replaced in every column that can hold a message's uid as text: its uid,
# its repository and thumbnail paths, its attachments' paths, a reference's messageId (a short id or a uid) and an
# issue member's or override's messageUid. The last three hold none in today's sample, so their updates change
# nothing. The S-124 dataset rows reach their message by its id and derive their uuid from the MRN, so they carry no
# uid. A sample that writes a uid into another column fails step 9's check; that column is then added here.
# Then each table with a primary key is rewritten in key order, so the dumps' row order does not depend on where an
# update left a row.
docker exec -i "$DB_CONTAINER" psql -v ON_ERROR_STOP=1 -q -U niord -d "$DB_NAME" \
    -v sample_prefix="$sample_prefix" -v fixture_prefix="$fixture_prefix" <<SQL
UPDATE public."Message" SET uid = replace(uid, :'sample_prefix', :'fixture_prefix'),
    "repoPath" = replace("repoPath", :'sample_prefix', :'fixture_prefix'),
    "thumbnailPath" = replace("thumbnailPath", :'sample_prefix', :'fixture_prefix');
UPDATE public."Attachment" SET path = replace(path, :'sample_prefix', :'fixture_prefix');
UPDATE public."RepoFile" SET path = replace(path, :'sample_prefix', :'fixture_prefix'),
    folder = replace(folder, :'sample_prefix', :'fixture_prefix');
UPDATE public."Reference" SET "messageId" = replace("messageId", :'sample_prefix', :'fixture_prefix')
    WHERE strpos("messageId", :'sample_prefix') > 0;
UPDATE public."IssueMember" SET "messageUid" = replace("messageUid", :'sample_prefix', :'fixture_prefix')
    WHERE strpos("messageUid", :'sample_prefix') > 0;
UPDATE public."IssueOverride" SET "messageUid" = replace("messageUid", :'sample_prefix', :'fixture_prefix')
    WHERE strpos("messageUid", :'sample_prefix') > 0;
-- The bootstrap's batch-job counters are residue, as regen-sample.sh filters them; a job's first run creates its own
DELETE FROM public."Sequence" WHERE name LIKE 'BATCH\_JOB\_%';
DO \$normalise\$
DECLARE
    epoch CONSTANT timestamp := timestamp '$EPOCH';
    t record;
BEGIN
    FOR t IN SELECT c.table_name FROM information_schema.columns c
             WHERE c.table_schema = 'public' AND c.column_name = 'created'
               AND EXISTS (SELECT 1 FROM information_schema.columns u WHERE u.table_schema = 'public'
                           AND u.table_name = c.table_name AND u.column_name = 'updated')
             ORDER BY 1 LOOP
        EXECUTE format('UPDATE public.%I SET created = CASE WHEN created IS NULL THEN NULL ELSE \$1 END, '
                       'updated = CASE WHEN updated IS NULL THEN NULL ELSE \$1 END '
                       'WHERE created <> \$1 OR updated <> \$1', t.table_name) USING epoch;
    END LOOP;
    FOR t IN SELECT tc.relname, ic.relname AS index FROM pg_index i
             JOIN pg_class tc ON tc.oid = i.indrelid
             JOIN pg_class ic ON ic.oid = i.indexrelid
             WHERE i.indisprimary AND tc.relnamespace = 'public'::regnamespace AND tc.relkind = 'r'
             ORDER BY 1 LOOP
        EXECUTE format('CLUSTER public.%I USING %I', t.relname, t.index);
        -- The rows keep their order; the catalog forgets the index CLUSTER marked, so the full dump carries no
        -- ALTER TABLE ... CLUSTER ON line the production schema (V001) has no equivalent of
        EXECUTE format('ALTER TABLE public.%I SET WITHOUT CLUSTER', t.relname);
    END LOOP;
END
\$normalise\$;
SQL

# --exclude-table-data for each residue table, for both dumps
EXCLUDES=()
for t in $RESIDUE; do
  EXCLUDES+=("--exclude-table-data=public.\"$t\"")
done

echo ">> [8/9] Dumping FULL schema + data -> $OUT_SQL"
mkdir -p "$(dirname "$OUT_SQL")"
# The grep filter strips pg_dump 18's \restrict/\unrestrict lines, whose key is drawn at random on every run, and
# the server's and pg_dump's version banner, so that a rebuild of the floating image tag changes no byte.
docker exec "$DB_CONTAINER" pg_dump -U niord --no-owner --no-privileges "${EXCLUDES[@]}" "$DB_NAME" \
  | grep -vE '^-- (Dumped from|Dumped by) ' \
  | grep -vE "^\\\\(un)?restrict " \
  | grep -vE "^SELECT pg_catalog\.set_config\('search_path'" \
  > "$OUT_SQL"

echo "   Done. $(grep -c '^CREATE TABLE' "$OUT_SQL") tables, $(wc -l < "$OUT_SQL") lines, $(du -h "$OUT_SQL" | cut -f1)."

echo ">> [8/9] Dumping DATA-ONLY (--column-inserts --disable-triggers) -> $OUT_DATA_SQL"
mkdir -p "$(dirname "$OUT_DATA_SQL")"
# Loaded on top of the Liquibase-built schema by the integration tests only (see changelog-with-seed.yaml).
#  --exclude-table-data the residue (see the header): a database seeded from the dump writes the marker, the
#                      ledger, Liquibase's tables and the settings itself, and reads existing at its first start.
#  --schema=public   : Niord lives entirely in public; this excludes the PostGIS image's tiger/topology
#                      schemas (their loader_* tables hold data V001 has no schema for).
#  --disable-triggers: brackets each table's data with ALTER TABLE ... DISABLE/ENABLE TRIGGER ALL so the
#                      non-deferrable circular/self-referential FKs in V001 accept the bulk load.
#  --column-inserts  : explicit column lists — robust if V001's column order is ever hand-edited.
# The grep filter strips the two pg_dump emissions Liquibase/JDBC cannot execute: the psql-only
# `\restrict`/`\unrestrict` meta-commands, and the `search_path=''` set_config (which would break
# Liquibase's own DATABASECHANGELOG bookkeeping). Every INSERT is schema-qualified, so it needs neither. It also
# strips the version banner, as above: the dump is the seed-base-data changeset's body, so a banner line would
# change its checksum on a rebuild of the image tag that changed no data.
docker exec "$DB_CONTAINER" pg_dump -U niord --data-only --column-inserts --disable-triggers \
  --no-owner --no-privileges --schema=public "${EXCLUDES[@]}" "$DB_NAME" \
  | grep -vE '^-- (Dumped from|Dumped by) ' \
  | grep -vE "^\\\\(un)?restrict " \
  | grep -vE "^SELECT pg_catalog\.set_config\('search_path'" \
  > "$OUT_DATA_SQL"

echo "   Done. $(grep -c '^INSERT' "$OUT_DATA_SQL") inserts, $(grep -c 'setval' "$OUT_DATA_SQL") setvals, $(wc -l < "$OUT_DATA_SQL") lines, $(du -h "$OUT_DATA_SQL" | cut -f1)."

echo ">> [9/9] Checking the dumps' folder for the sample (check-no-sample.sh) ..."
# The check Backend CI and publish.yml run over niord-base's test-jar, which carries the data-only dump: a finding
# here is one there. The committed folder also holds the test changelog, which carries no sample either. With
# NIORD_SEED_OUT the check walks that whole folder, which is why it must hold the two dumps only.
if ! k8s/scripts/check-no-sample.sh "$SAMPLE_DIR" "$(dirname "$OUT_DATA_SQL")"; then
  echo "!! The folder of the dumps carries the sample (above): do not commit them. A finding in one of the two" \
    "dumps means step 7's normalisation missed a marker; a finding in another file of that folder is that file's." >&2
  exit 1
fi

if [ -n "${NIORD_SEED_OUT:-}" ]; then
  echo ">> The fixture was written under $NIORD_SEED_OUT (NIORD_SEED_OUT): $(basename "$OUT_SQL") and $(basename "$OUT_DATA_SQL"); the committed files are untouched."
else
  echo ">> The fixture was regenerated. Review the diff and commit $OUT_SQL and $OUT_DATA_SQL."
fi
