-- check.sql: the self-check make-guides-db.sh runs after scrub.sql. It fails (psql exits non-zero, the script stops)
-- when a column scrub.sql scrubbed still holds an e-mail address outside example.org, when a secret is not empty,
-- when a notice with a private contact still names it or keeps its history, when an address of a person's form
-- (scrub.sql's guides.scrub_shape) outside example.org is left in any text, JSON or search column, when an address
-- default-deny does not keep (neither on the functional list nor made of role words only; scrub.sql) is left in one,
-- or when one of the four local Keycloak logins has no "User" row. Run with -v functional=... -v role_words=...
-- (make-guides-db.sh passes scrub-lists.mjs's lists; without them the check fails). The staff names and initials, and the persons' addresses in every text column, are
-- checked by check-names.sh, which needs niord_test; the addresses in the history snapshots by english-history.mjs.

SET client_encoding = 'UTF8';
\pset footer off

CREATE FUNCTION pg_temp.foreign_mails(t text) RETURNS bigint LANGUAGE sql IMMUTABLE AS $$
    SELECT count(*)
    FROM regexp_matches(coalesce(t, ''), '[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,})', 'g') m
    WHERE lower(m[1]) <> 'example.org'
$$;

-- An address of a person's form (scrub.sql's guides.scrub_shape) outside example.org, in any column of the copy that
-- can hold text (public and guides; a search row by its words), also with the at-sign written as an entity or
-- URL-encoded, which scrub.sql's rule does not replace, so the check is the stricter one. Counts per column only. A
-- copy without guides.scrub_shape fails here (scrub.sql writes it).
CREATE TEMP TABLE g_shape_hit (tbl text, col text, n bigint);
DO $$
DECLARE
    c record;
    n bigint;
    pat text := (SELECT '(?<![a-z0-9._%+-])((?:' || form || ')@[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,})(?![a-z0-9-])'
                 FROM guides.scrub_shape);
BEGIN
    IF pat IS NULL THEN
        RAISE EXCEPTION 'self-check failed: guides.scrub_shape holds no form (scrub.sql writes it)';
    END IF;
    FOR c IN
        SELECT col.table_schema AS s, col.table_name AS t, col.column_name AS c, col.data_type AS dt
        FROM information_schema.columns col
        JOIN information_schema.tables tb ON tb.table_schema = col.table_schema AND tb.table_name = col.table_name
        WHERE col.table_schema IN ('public', 'guides') AND tb.table_type = 'BASE TABLE'
          AND col.data_type IN ('text', 'character varying', 'json', 'jsonb', 'tsvector')
    LOOP
        IF c.dt = 'tsvector' THEN
            EXECUTE format('SELECT count(*) FROM %I.%I x WHERE EXISTS (SELECT 1 FROM unnest(tsvector_to_array(x.%I)) w '
                           || 'WHERE w ~* $1 AND split_part(lower(w), ''@'', 2) <> ''example.org'')', c.s, c.t, c.c)
                INTO n USING pat;
        ELSE
            EXECUTE format('SELECT count(*) FROM %I.%I x WHERE x.%I::text ~* ''@|&#0*64;|&#x0*40;|&commat;|%%40|\\u0040'''
                           || ' AND EXISTS (SELECT 1 FROM regexp_matches(regexp_replace(lower(x.%I::text),'
                           || ' ''&#0*64;|&#x0*40;|&commat;|%%40|\\u0040'', ''@'', ''g''), $1, ''g'') m'
                           || ' WHERE split_part(m[1], ''@'', 2) <> ''example.org'')', c.s, c.t, c.c, c.c)
                INTO n USING pat;
        END IF;
        IF n > 0 THEN
            INSERT INTO g_shape_hit (tbl, col, n) VALUES (c.s || '.' || c.t, c.c, n);
        END IF;
    END LOOP;
END $$;

-- Default-deny (scrub.sql, "An address default-deny does not keep"): an address outside example.org that is neither
-- on the functional list nor made of role words only, in any column of the copy that can hold text (public and
-- guides; a search row by its words), also with the at-sign written as an entity or URL-encoded. The lists come from
-- make-guides-db.sh (-v functional=..., -v role_words=..., from scrub-lists.mjs), the pattern from
-- guides.scrub_address (scrub.sql writes it); without them the check fails. Counts per column only.
CREATE TEMP TABLE g_keep_login AS SELECT DISTINCT unnest(string_to_array(:'functional', '|')) AS login;
CREATE TEMP TABLE g_keep_word AS SELECT DISTINCT unnest(string_to_array(:'role_words', '|')) AS word;
CREATE FUNCTION pg_temp.kept(a text) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT split_part(a, '@', 2) = 'example.org'
        OR split_part(a, '@', 1) IN (SELECT login FROM g_keep_login)
        OR coalesce((SELECT bool_and(w IN (SELECT word FROM g_keep_word)) AND count(*) > 0
                     FROM unnest(regexp_split_to_array(split_part(a, '@', 1), '[._0-9-]+')) w WHERE w <> ''), false)
$$;
CREATE TEMP TABLE g_deny_hit (tbl text, col text, n bigint);
DO $$
DECLARE
    c record;
    n bigint;
    pat text := (SELECT pattern FROM guides.scrub_address);
BEGIN
    IF pat IS NULL OR NOT EXISTS (SELECT 1 FROM g_keep_login WHERE login <> '')
       OR NOT EXISTS (SELECT 1 FROM g_keep_word WHERE word <> '') THEN
        RAISE EXCEPTION 'self-check failed: guides.scrub_address, -v functional or -v role_words is missing';
    END IF;
    FOR c IN
        SELECT col.table_schema AS s, col.table_name AS t, col.column_name AS c, col.data_type AS dt
        FROM information_schema.columns col
        JOIN information_schema.tables tb ON tb.table_schema = col.table_schema AND tb.table_name = col.table_name
        WHERE col.table_schema IN ('public', 'guides') AND tb.table_type = 'BASE TABLE'
          AND col.data_type IN ('text', 'character varying', 'json', 'jsonb', 'tsvector')
    LOOP
        IF c.dt = 'tsvector' THEN
            EXECUTE format('SELECT count(*) FROM %I.%I x WHERE x.%I::text LIKE ''%%@%%'' AND EXISTS (SELECT 1 FROM '
                           || 'regexp_matches(array_to_string(tsvector_to_array(x.%I), '' ''), $1, ''gi'') m '
                           || 'WHERE NOT pg_temp.kept(lower(m[1])))', c.s, c.t, c.c, c.c)
                INTO n USING pat;
        ELSE
            EXECUTE format('SELECT count(*) FROM %I.%I x WHERE x.%I::text ~* ''@|&#0*64;|&#x0*40;|&commat;|%%40|\\u0040'''
                           || ' AND EXISTS (SELECT 1 FROM regexp_matches(regexp_replace(lower(x.%I::text),'
                           || ' ''&#0*64;|&#x0*40;|&commat;|%%40|\\u0040'', ''@'', ''g''), $1, ''g'') m'
                           || ' WHERE NOT pg_temp.kept(m[1]))', c.s, c.t, c.c, c.c)
                INTO n USING pat;
        END IF;
        IF n > 0 THEN
            INSERT INTO g_deny_hit (tbl, col, n) VALUES (c.s || '.' || c.t, c.c, n);
        END IF;
    END LOOP;
END $$;

CREATE TEMP TABLE g_check AS
SELECT 'User (email, username, names)' AS columns, count(*) AS bad FROM public."User"
    WHERE pg_temp.foreign_mails(concat_ws(' ', email, username, "firstName", "lastName")) > 0
       OR email IS NULL OR email !~* '@example\.org$'
UNION ALL
SELECT 'Contact (email, name)', count(*) FROM public."Contact"
    WHERE pg_temp.foreign_mails(concat_ws(' ', email, name)) > 0 OR email !~* '@example\.org$'
UNION ALL
SELECT 'Comment.comment', count(*) FROM public."Comment" WHERE pg_temp.foreign_mails(comment) > 0
UNION ALL
SELECT 'Comment_emailAddresses', count(*) FROM public."Comment_emailAddresses"
    WHERE pg_temp.foreign_mails("emailAddresses") > 0 OR "emailAddresses" !~* '@example\.org$'
UNION ALL
SELECT 'MessageFilter.name', count(*) FROM public."MessageFilter" WHERE pg_temp.foreign_mails(name) > 0
UNION ALL
SELECT 'MessageTag.name', count(*) FROM public."MessageTag" WHERE pg_temp.foreign_mails(name) > 0
UNION ALL
SELECT 'ScheduledMail + recipients (any row)',
       (SELECT count(*) FROM public."ScheduledMail") + (SELECT count(*) FROM public."ScheduledMailRecipient")
UNION ALL
SELECT 'Setting (secrets: not empty)', count(*) FROM public."Setting"
    WHERE (type = 'Password' OR setting_key IN ('wmsLogin', 'wmsPassword', 'legacyNwDbUser', 'legacyNwDbUrl',
                                                'legacyNwDbPassword', 'pdfEncryptionPassword', 'authServerRealmKey'))
      AND setting_value IS DISTINCT FROM '""'
UNION ALL
SELECT 'TwitterSettings (keys: not empty)', count(*) FROM public."TwitterSettings"
    WHERE coalesce("accessToken", "accessTokenSecret", "apiKey", "apiSecret") IS NOT NULL
UNION ALL
SELECT 'IssueOverride.reason, IssueMember.reasonNote, PublicationIssue.retiredReason, IssueAuditEntry.actorLabel',
       (SELECT count(*) FROM public."IssueOverride" WHERE pg_temp.foreign_mails(reason) > 0)
     + (SELECT count(*) FROM public."IssueMember" WHERE pg_temp.foreign_mails("reasonNote") > 0)
     + (SELECT count(*) FROM public."PublicationIssue" WHERE pg_temp.foreign_mails("retiredReason") > 0)
     + (SELECT count(*) FROM public."IssueAuditEntry" WHERE pg_temp.foreign_mails("actorLabel") > 0)
UNION ALL
SELECT 'NiordIntegration.url', count(*) FROM public."NiordIntegration" WHERE pg_temp.foreign_mails(url) > 0
UNION ALL
-- the invented addresses english-history.mjs puts into the history snapshots for a person's (scrub.sql's
-- guides.scrub_mail): example.org only; at least one row, as niord_test has users with an address
SELECT 'guides.scrub_mail (invented addresses only, not empty)', count(*) FILTER (WHERE repl !~* '^[^@]+@example\.org$')
     + CASE WHEN count(*) = 0 THEN 1 ELSE 0 END
FROM guides.scrub_mail
UNION ALL
-- the notices whose private contact scrub.sql replaces (its g_private_contact list): the contact is Arktisk Kommando
-- and no history snapshot is left
SELECT 'GL-027-22: contact other than Arktisk Kommando, or a history row',
       (SELECT count(*) FROM public."Message" m
        JOIN public."MessagePart" p ON p.message_id = m.id
        JOIN public."MessagePartDesc" pd ON pd.entity_id = p.id
        WHERE m."shortId" IN ('GL-027-22')
          AND pd.details ~* '(HENVENDELSE TIL|IF FOUND CONTACT)\s*(<br\s*/?>)?\s*(?!ARKTISK KOMMANDO|JOINT ARCTIC COMMAND)[^<\s]')
     + (SELECT count(*) FROM public."MessageHistory" h JOIN public."Message" m ON m.id = h.message_id
        WHERE m."shortId" IN ('GL-027-22'))
UNION ALL
-- an address of a person's form outside example.org (scrub.sql's guides.scrub_shape), in any text, JSON or search
-- column (the history snapshots: english-history.mjs)
SELECT 'an address of a person''s form: in any text, JSON or search column', (SELECT coalesce(sum(n), 0) FROM g_shape_hit)
UNION ALL
-- default-deny: an address outside example.org that is neither on the functional list nor made of role words only
-- (the history snapshots: english-history.mjs)
SELECT 'an address default-deny does not keep: in any text, JSON or search column',
       (SELECT coalesce(sum(n), 0) FROM g_deny_hit);

\echo
\echo '== Self-check: values left with an e-mail address outside example.org, a secret, a private contact (must all be 0)'
SELECT columns, bad FROM g_check;
SELECT tbl AS "table", col AS "column", n AS "rows with an address of a person's form" FROM g_shape_hit ORDER BY 1, 2;
SELECT tbl AS "table", col AS "column", n AS "rows with an address default-deny does not keep" FROM g_deny_hit ORDER BY 1, 2;

\echo
\echo '== The local Keycloak logins and their rows (roles come from the realm, not from the database)'
SELECT username, "firstName", "lastName", email FROM public."User"
WHERE username IN ('sysadmin', 'admin', 'editor', 'user') ORDER BY username;

DO $$
DECLARE
    bad bigint := (SELECT sum(bad) FROM g_check);
    logins int := (SELECT count(*) FROM public."User" WHERE username IN ('sysadmin', 'admin', 'editor', 'user'));
BEGIN
    IF bad > 0 THEN
        RAISE EXCEPTION 'self-check failed: % value(s) in the scrubbed columns still hold an address outside example.org, a secret, a queued mail, a private contact, an address of a person''s form or an address default-deny does not keep', bad;
    END IF;
    IF logins <> 4 THEN
        RAISE EXCEPTION 'self-check failed: only % of the four local logins have a "User" row', logins;
    END IF;
END $$;

\echo 'Self-check passed.'
