-- check.sql: the self-check make-guides-db.sh runs after scrub.sql. It fails (psql exits non-zero, the script stops)
-- when a column scrub.sql scrubbed still holds an e-mail address outside example.org, when a secret is not empty,
-- when a notice with a private contact still names it or keeps its history, or when one of the four local Keycloak
-- logins has no "User" row. The staff names and initials are checked by check-names.sh, which needs niord_test.

SET client_encoding = 'UTF8';
\pset footer off

CREATE FUNCTION pg_temp.foreign_mails(t text) RETURNS bigint LANGUAGE sql IMMUTABLE AS $$
    SELECT count(*)
    FROM regexp_matches(coalesce(t, ''), '[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,})', 'g') m
    WHERE lower(m[1]) <> 'example.org'
$$;

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
-- the notices whose private contact scrub.sql replaces (its g_private_contact list): the contact is Arktisk Kommando
-- and no history snapshot is left
SELECT 'GL-027-22: contact other than Arktisk Kommando, or a history row',
       (SELECT count(*) FROM public."Message" m
        JOIN public."MessagePart" p ON p.message_id = m.id
        JOIN public."MessagePartDesc" pd ON pd.entity_id = p.id
        WHERE m."shortId" IN ('GL-027-22')
          AND pd.details ~* '(HENVENDELSE TIL|IF FOUND CONTACT)\s*(<br\s*/?>)?\s*(?!ARKTISK KOMMANDO|JOINT ARCTIC COMMAND)[^<\s]')
     + (SELECT count(*) FROM public."MessageHistory" h JOIN public."Message" m ON m.id = h.message_id
        WHERE m."shortId" IN ('GL-027-22'));

\echo
\echo '== Self-check: values left with an e-mail address outside example.org, a secret, a private contact (must all be 0)'
SELECT columns, bad FROM g_check;

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
        RAISE EXCEPTION 'self-check failed: % value(s) in the scrubbed columns still hold an address outside example.org, a secret, a queued mail or a private contact', bad;
    END IF;
    IF logins <> 4 THEN
        RAISE EXCEPTION 'self-check failed: only % of the four local logins have a "User" row', logins;
    END IF;
END $$;

\echo 'Self-check passed.'
