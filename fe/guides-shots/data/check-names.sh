#!/bin/sh
# check-names.sh: the second self-check make-guides-db.sh runs after scrub.sql, INSIDE the niord-db container:
#   docker exec -i niord-db sh -s < check-names.sh
# It reads the staff words (initials and names, by the same rule as scrub.sql's g_staff_word) and the private contact
# scrub.sql replaces from niord_test, read-only, into shell variables, so they never leave the container and are never
# written to disk or printed. It then fails (exit 1) when niord_guides still holds
#   - a staff word in a tag name or a saved filter's name or parameters (case as written, letter boundaries),
#   - staff initials in a title or subject of a never-public message,
#   - a never-public message whose title or subject is only staff words, "test", "diverse" and the country name,
#   - the private contact's text in any message part, or its words in the notice's search row.
set -eu

WORDS_SQL=$(cat <<'SQL'
CREATE TEMP TABLE g_functional_login (login text PRIMARY KEY);
INSERT INTO g_functional_login (login) VALUES ('sysadmin'), ('admin'), ('editor'), ('user'), ('ako'), ('vagts'), ('vrag');
CREATE TEMP TABLE g_staff_word AS
WITH addr AS (
    SELECT coalesce(substring(a FROM '<([^>]*)>'), a) AS mail, btrim(substring(a FROM '^([^<]*)<')) AS display
    FROM (SELECT "emailAddresses" AS a FROM public."Comment_emailAddresses" WHERE "emailAddresses" IS NOT NULL) x)
SELECT DISTINCT w, kind FROM (
    SELECT upper(split_part(mail, '@', 1)) AS w, 'initials' AS kind FROM addr
    UNION ALL
    SELECT upper(username), 'initials' FROM public."User"
    UNION ALL
    SELECT regexp_split_to_table(concat_ws(' ', "firstName", "lastName"), '[^[:alpha:]]+'), 'name' FROM public."User"
    WHERE lower(username) NOT IN (SELECT login FROM g_functional_login)
    UNION ALL
    SELECT regexp_split_to_table(display, '[^[:alpha:]]+'), 'name' FROM addr) x
WHERE (kind = 'initials' AND w ~ '^[[:alpha:]]{2,5}$' AND lower(w) NOT IN (SELECT login FROM g_functional_login))
   OR (kind = 'name' AND length(w) >= 2 AND lower(w) NOT IN ('test', 'user', 'local'));
SQL
)

INITIALS=$(printf '%s\nSELECT string_agg(w, %s) FROM g_staff_word WHERE kind = %s;\n' "$WORDS_SQL" "'|'" "'initials'" \
    | psql -X -q -U niord -d niord_test -At -v ON_ERROR_STOP=1)
NAMES=$(printf '%s\nSELECT string_agg(w, %s) FROM g_staff_word WHERE kind = %s;\n' "$WORDS_SQL" "'|'" "'name'" \
    | psql -X -q -U niord -d niord_test -At -v ON_ERROR_STOP=1)
CONTACT=$(psql -X -q -U niord -d niord_test -At -v ON_ERROR_STOP=1 <<'SQL'
SELECT coalesce(string_agg(DISTINCT t, '|'), '') FROM (
    SELECT btrim((regexp_match(pd.details, '(?:HENVENDELSE TIL|IF FOUND CONTACT)\s*(?:<br\s*/?>)?\s*([^<]*)', 'i'))[1]) AS t
    FROM public."Message" m
    JOIN public."MessagePart" p ON p.message_id = m.id
    JOIN public."MessagePartDesc" pd ON pd.entity_id = p.id
    WHERE m."shortId" IN ('GL-027-22')) x
WHERE t <> '';
SQL
)

[ -n "$INITIALS" ] && [ -n "$NAMES" ] || { echo "check-names: no staff words read from niord_test" >&2; exit 1; }

psql -X -q -U niord -d niord_guides -v ON_ERROR_STOP=1 \
     -v initials="$INITIALS" -v names="$NAMES" -v contact="$CONTACT" <<'SQL'
\pset footer off
CREATE TEMP TABLE sw AS
SELECT unnest(string_to_array(:'initials', '|')) AS w, 'initials' AS kind
UNION ALL
SELECT unnest(string_to_array(:'names', '|')), 'name';
CREATE TEMP TABLE ct AS SELECT unnest(string_to_array(:'contact', '|')) AS t;

CREATE TEMP TABLE g_check AS
SELECT 'MessageTag.name: a staff word' AS what,
       (SELECT count(DISTINCT t.id) FROM public."MessageTag" t
        JOIN sw ON t.name ~ ('(^|[^[:alpha:]])' || sw.w || '([^[:alpha:]]|$)')) AS bad
UNION ALL
SELECT 'MessageFilter.name, parameters: a staff word',
       (SELECT count(DISTINCT f.id) FROM public."MessageFilter" f
        JOIN sw ON concat_ws(' ', f.name, f.parameters) ~ ('(^|[^[:alpha:]])' || sw.w || '([^[:alpha:]]|$)'))
UNION ALL
SELECT 'never-public title or subject: staff initials',
       (SELECT count(*) FROM (
            SELECT d.title AS t FROM public."Message" m JOIN public."MessageDesc" d ON d.entity_id = m.id
            WHERE m."publishDateFrom" IS NULL
            UNION ALL
            SELECT pd.subject FROM public."Message" m JOIN public."MessagePart" p ON p.message_id = m.id
            JOIN public."MessagePartDesc" pd ON pd.entity_id = p.id
            WHERE m."publishDateFrom" IS NULL) x
        WHERE EXISTS (SELECT 1 FROM sw WHERE sw.kind = 'initials'
                      AND x.t ~ ('(^|[^[:alpha:]])' || sw.w || '([^[:alpha:]]|$)')))
UNION ALL
SELECT 'never-public test message named after a colleague',
       (WITH np AS (SELECT id FROM public."Message"
                    WHERE "publishDateFrom" IS NULL AND status IN ('DRAFT', 'VERIFIED', 'DELETED')),
        txt AS (SELECT np.id, d.title AS t FROM np JOIN public."MessageDesc" d ON d.entity_id = np.id
                UNION ALL
                SELECT np.id, pd.subject FROM np JOIN public."MessagePart" p ON p.message_id = np.id
                JOIN public."MessagePartDesc" pd ON pd.entity_id = p.id),
        tok AS (SELECT txt.id, txt.t, lower(x) AS x
                FROM txt, regexp_split_to_table(coalesce(txt.t, ''), '[^[:alpha:]]+') x WHERE x <> ''),
        hit AS (SELECT id FROM tok GROUP BY id, t
                HAVING bool_and(x IN ('test', 'diverse', 'danmark', 'denmark') OR x IN (SELECT lower(w) FROM sw))
                   AND bool_or(x IN (SELECT lower(w) FROM sw)))
        SELECT count(DISTINCT id) FROM hit)
UNION ALL
SELECT 'the private contact: in a message part, or in its search row',
       (SELECT count(*) FROM public."MessagePartDesc" pd JOIN ct ON position(upper(ct.t) IN upper(pd.details)) > 0)
     + (SELECT count(*) FROM public.message_search_text s JOIN public."Message" m ON m.id = s.message_id, ct
        WHERE m."shortId" IN ('GL-027-22') AND s.search_tsv IS NOT NULL
          AND tsvector_to_array(s.search_tsv) && tsvector_to_array(to_tsvector(
                (CASE WHEN s.lang LIKE 'en%' THEN 'english' WHEN s.lang LIKE 'da%' THEN 'danish' ELSE 'simple' END)::regconfig,
                ct.t)));

\echo
\echo '== Self-check: staff names and initials, the private contact (must all be 0; the words come from niord_test)'
SELECT what, bad FROM g_check;
SELECT (SELECT count(*) FROM sw WHERE kind = 'initials') AS initials_checked,
       (SELECT count(*) FROM sw WHERE kind = 'name') AS names_checked,
       (SELECT count(*) FROM ct) AS contact_texts_checked;

DO $$
DECLARE
    bad bigint := (SELECT sum(bad) FROM g_check);
BEGIN
    IF bad > 0 THEN
        RAISE EXCEPTION 'self-check failed: % value(s) still hold a staff name or initials, or the private contact', bad;
    END IF;
END $$;
\echo 'Staff-name self-check passed.'
SQL
