#!/usr/bin/env bash
# make-guides-db.sh: makes niord_guides, the database the user-guide screenshots are taken on: a copy of the local
# Danish test data (niord_test) with the personal data removed (scrub.sql), checked (check.sql), prepared
# (prepare.sql), its English-language texts made English (english.sql, and english-history.mjs for the history
# snapshots) and described (report.sql, dict-diff.js);
# check-names.sh, the check of the staff names and the persons' addresses, runs inside the container;
# english-history.mjs also replaces the persons' addresses and the addresses of a person's form in the history
# snapshots and checks that none is left; check.sql checks the addresses of a person's form in the texts.
# Default-deny (2026-11-14): an address outside example.org that is neither on scrub.sql's functional list nor made
# of scrub-lists.mjs's role words only is replaced too (scrub.sql, english-history.mjs) and checked (check.sql, run
# again on the finished copy, and english-history.mjs).
#
#   bash make-guides-db.sh            (Git Bash)
#   "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" make-guides-db.sh    (PowerShell or cmd, Git not on the PATH)
#
# The rule: niord_test is never modified. It is only read, by pg_dump, inside the niord-db container, the way the
# launcher's --own-db copies a database (start-local.sh). Everything happens in niord_guides, which is dropped and made
# again on every run, so a second run gives the same result as the first. The only target accepted is niord_guides.
set -euo pipefail
# Git Bash would rewrite arguments that look like paths before docker sees them
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

SOURCE_DB=niord_test
TARGET_DB="${1:-niord_guides}"
CONTAINER=niord-db
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$HERE/logs"
# The base product's English bundle the dictionary rows are compared with (read only)
EN_BUNDLE="${EN_BUNDLE:-$HERE/../../languages/en/i18n/admin/en.json}"

die() { echo "!! $*" >&2; exit 1; }

[[ "$TARGET_DB" == "niord_guides" ]] || die "This script makes niord_guides and nothing else; refusing the target '$TARGET_DB'."
[[ "$SOURCE_DB" != "$TARGET_DB" ]] || die "The source and the target are the same database."

# psql in the container, stopping at the first error; the SQL comes on stdin
psql_db() { local db="$1"; shift; docker exec -i "$CONTAINER" psql -X -U niord -d "$db" -v ON_ERROR_STOP=1 "$@"; }
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

started=$SECONDS
command -v docker >/dev/null 2>&1 || die "docker is not on the PATH."
NODE="$(command -v node 2>/dev/null || true)"
[[ -n "$NODE" ]] || { [[ -x "/c/Program Files/nodejs/node.exe" ]] && NODE="/c/Program Files/nodejs/node.exe"; }
[[ -n "$NODE" ]] || die "node is not on the PATH: it reads scrub.sql's lists for check-names.sh, and english-history.mjs needs it."
# scrub.sql's lists for check-names.sh, which runs in the container and cannot read this folder (scrub-lists.mjs reads
# them from scrub.sql, so they are written there only)
FUNCTIONAL_LIST="$("$NODE" "$(winpath "$HERE/scrub-lists.mjs")" functional)"
PRIVATE_MAIL_LIST="$("$NODE" "$(winpath "$HERE/scrub-lists.mjs")" private-mail)"
PRIVATE_CONTACT_LIST="$("$NODE" "$(winpath "$HERE/scrub-lists.mjs")" private-contact)"
# default-deny's role words, for scrub.sql and check.sql (scrub-lists.mjs keeps them)
ROLE_WORDS_LIST="$("$NODE" "$(winpath "$HERE/scrub-lists.mjs")" role-words)"
[[ -n "$FUNCTIONAL_LIST" && -n "$ROLE_WORDS_LIST" ]] || die "scrub-lists.mjs gave no functional list or no role words."
[[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" == "true" ]] \
    || die "The container $CONTAINER is not running (start the shared containers first, e.g. ./start-local.sh)."
[[ "$(docker exec "$CONTAINER" psql -X -U niord -d postgres -tAc "select 1 from pg_database where datname = '$SOURCE_DB'")" == "1" ]] \
    || die "The source database $SOURCE_DB does not exist in $CONTAINER."

echo ">> Dropping and recreating $TARGET_DB (template_postgis, like the launcher's ensure_database)"
# --force ends the sessions of a backend still running on the old copy
docker exec "$CONTAINER" dropdb -U niord --if-exists --force "$TARGET_DB"
docker exec "$CONTAINER" createdb -U niord -T template_postgis "$TARGET_DB"

echo ">> Copying $SOURCE_DB into $TARGET_DB (pg_dump | psql inside the container; $SOURCE_DB is only read)"
mkdir -p "$LOG_DIR"
t0=$SECONDS
docker exec "$CONTAINER" sh -c "pg_dump -U niord -d $SOURCE_DB | psql -q -X -U niord -d $TARGET_DB >/dev/null" \
    2>"$LOG_DIR/copy-errors.log" || true
# The PostGIS template already holds the extensions' schemas, so their CREATE statements fail harmlessly (the launcher
# says the same); anything else stops the run.
if grep -v -E 'already exists' "$LOG_DIR/copy-errors.log" | grep -q -i -E 'error|fatal'; then
    grep -v -E 'already exists' "$LOG_DIR/copy-errors.log" | grep -i -E -m 20 'error|fatal' >&2
    die "The copy failed (all messages: $LOG_DIR/copy-errors.log)."
fi
echo "   copied in $((SECONDS - t0)) s; $(grep -c -i 'already exists' "$LOG_DIR/copy-errors.log" || true) harmless 'already exists' errors"

# The copy is complete when the big tables have the source's row counts
count_sql='select (select count(*) from public."Message")||'"'"'/'"'"'||(select count(*) from public."MessageHistory")||'"'"'/'"'"'||(select count(*) from public."User")'
src_counts="$(docker exec "$CONTAINER" psql -X -U niord -d "$SOURCE_DB" -tAc "$count_sql")"
dst_counts="$(docker exec "$CONTAINER" psql -X -U niord -d "$TARGET_DB" -tAc "$count_sql")"
[[ "$src_counts" == "$dst_counts" ]] || die "The copy is incomplete: Message/MessageHistory/User $dst_counts against $src_counts."
echo "   Message/MessageHistory/User rows: $dst_counts, as in $SOURCE_DB"

echo ">> Scrubbing $TARGET_DB (scrub.sql, one transaction)"
psql_db "$TARGET_DB" --single-transaction -q -v role_words="$ROLE_WORDS_LIST" -f - <"$HERE/scrub.sql"

echo ">> Self-check (check.sql)"
psql_db "$TARGET_DB" -q -v functional="$FUNCTIONAL_LIST" -v role_words="$ROLE_WORDS_LIST" -f - <"$HERE/check.sql" \
    || die "The self-check failed: $TARGET_DB still holds personal data in a scrubbed column."

echo ">> Self-check of the staff names, initials and addresses (check-names.sh, inside the container: the words and addresses are read from $SOURCE_DB and never leave it)"
docker exec -i -e GUIDES_FUNCTIONAL="$FUNCTIONAL_LIST" -e GUIDES_PRIVATE_MAIL="$PRIVATE_MAIL_LIST" \
    -e GUIDES_PRIVATE_CONTACT="$PRIVATE_CONTACT_LIST" "$CONTAINER" sh -s <"$HERE/check-names.sh" \
    || die "The staff-name self-check failed: $TARGET_DB still holds a colleague's name, initials or address, or the private contact."

echo ">> Preparing $TARGET_DB for the screenshots (prepare.sql, one transaction)"
psql_db "$TARGET_DB" --single-transaction -q -At -f - <"$HERE/prepare.sql"

# prepare.sql deleted the batch job records, so the execution folders an earlier copy's runs left in the guides home
# go too: the job numbers start again on a new copy, and a new run would append its log to an old run's file (seen
# 2026-11-12: one script's log held two executions). Only the guides home, and only its batch-jobs folder.
GUIDES_HOME="${GUIDES_HOME:-$HOME/.niord-ng-guides}"
if [[ "$(basename "$GUIDES_HOME")" == ".niord-ng-guides" && -d "$GUIDES_HOME/batch-jobs" ]]; then
    echo ">> Removing the batch job folders of an earlier copy ($GUIDES_HOME/batch-jobs)"
    rm -rf -- "$GUIDES_HOME/batch-jobs"
fi

echo ">> Making the English-language texts English (english.sql, one transaction)"
psql_db "$TARGET_DB" --single-transaction -q -At -f - <"$HERE/english.sql"

echo ">> Making the message history snapshots English and taking the persons' addresses out of them (english-history.mjs, ~1.5 min; gzip, which SQL cannot rewrite)"
[[ -n "$NODE" ]] || die "node is not on the PATH: the history snapshots would keep their Danish entries and the persons' addresses."
GUIDES_DB_CONTAINER="$CONTAINER" GUIDES_SOURCE_DB="$SOURCE_DB" "$NODE" "$(winpath "$HERE/english-history.mjs")" \
    || die "english-history.mjs failed, a snapshot of a live message still holds a Danish English text (named above): add a rule to english.sql, or a snapshot still holds a person's address (rows named above): see scrub.sql."

# check.sql once more, on the finished copy: prepare.sql and english.sql write texts after the first run
echo ">> Self-check of the finished copy (check.sql again, after prepare.sql, english.sql and english-history.mjs)"
psql_db "$TARGET_DB" -q -v functional="$FUNCTIONAL_LIST" -v role_words="$ROLE_WORDS_LIST" -f - <"$HERE/check.sql" \
    || die "The self-check of the finished copy failed: a step after the scrub wrote personal data into $TARGET_DB."

echo ">> Report (report.sql)"
psql_db "$TARGET_DB" -q -f - <"$HERE/report.sql"

echo
echo "== Step 5: the web and message dictionaries' English rows against the base product's bundle"
if [[ -z "$NODE" ]]; then
    echo "   (skipped: node is not on the PATH)"
elif [[ ! -f "$EN_BUNDLE" ]]; then
    echo "   (skipped: no bundle at $EN_BUNDLE; set EN_BUNDLE)"
else
    psql_db "$TARGET_DB" -q -At -f - <<'SQL' | "$NODE" "$(winpath "$HERE/dict-diff.js")" "$(winpath "$EN_BUNDLE")"
SELECT coalesce(json_agg(json_build_object(
           'd', d.name, 'k', e.dict_key, 'v', ed.value,
           'a', (l.hash IS NOT NULL AND l.hash = encode(sha256(convert_to(ed.value, 'UTF8')), 'hex')))
         ORDER BY d.name, e.dict_key), '[]')
FROM public."DictionaryEntry" e
JOIN public."Dictionary" d ON d.id = e.dictionary_id
JOIN public."DictionaryEntryDesc" ed ON ed.entity_id = e.id AND ed.lang = 'en'
LEFT JOIN public."DefaultsLedger" l ON l.kind = 'TEXT' AND l.dictionary = d.name AND l.key = e.dict_key AND l.lang = 'en'
WHERE d.name IN ('web', 'message') AND coalesce(btrim(ed.value), '') <> '';
SQL
fi

echo
echo ">> $TARGET_DB is ready ($((SECONDS - started)) s). $SOURCE_DB was only read."
