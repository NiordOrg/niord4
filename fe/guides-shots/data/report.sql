-- report.sql: what make-guides-db.sh prints about the finished niord_guides (read-only): the texts of messages that
-- were never public scanned for addresses and phone numbers, the language settings, and the data facts the
-- screenshot work needs.

SET client_encoding = 'UTF8';
\pset footer off

CREATE FUNCTION pg_temp.mails(t text) RETURNS bigint LANGUAGE sql IMMUTABLE AS $$
    SELECT count(*) FROM regexp_matches(coalesce(t, ''), '[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}', 'g')
$$;
-- A phone number: a Danish or Greenlandic international number, or digits after tel./tlf./phone/mobil
CREATE FUNCTION pg_temp.phones(t text) RETURNS bigint LANGUAGE sql IMMUTABLE AS $$
    SELECT count(*) FROM regexp_matches(coalesce(t, ''),
        '(\+45|\+299|0045)\s?[0-9]{2}\s?[0-9]{2}\s?[0-9]{2}\s?[0-9]{0,2}|\m(tlf|tel|telefon|phone|mobil|mobile)\.?:?\s*(\+?[0-9][0-9 ]{5,})', 'gi')
$$;

\echo
\echo '== Step 4: texts of messages that were never public (DRAFT, VERIFIED, DELETED, IMPORTED): not rewritten, counted'
WITH np AS (SELECT id, status FROM public."Message" WHERE status IN ('DRAFT', 'VERIFIED', 'DELETED', 'IMPORTED')),
t AS (
    SELECT np.id, np.status, concat_ws(' ', d.title, d.vicinity, d.source, d.publication, d."internalPublication") AS txt
    FROM np JOIN public."MessageDesc" d ON d.entity_id = np.id
    UNION ALL
    SELECT np.id, np.status, concat_ws(' ', pd.subject, pd.details)
    FROM np JOIN public."MessagePart" p ON p.message_id = np.id JOIN public."MessagePartDesc" pd ON pd.entity_id = p.id
    UNION ALL
    SELECT np.id, np.status, concat_ws(' ', n.text, a.text, tw.tweet)
    FROM np JOIN public."BaseMessagePromulgation" b ON b.message_id = np.id
    LEFT JOIN public."NavtexMessagePromulgation" n ON n.id = b.id
    LEFT JOIN public."AudioMessagePromulgation" a ON a.id = b.id
    LEFT JOIN public."TwitterMessagePromulgation" tw ON tw.id = b.id
    UNION ALL
    SELECT np.id, np.status, ad.caption
    FROM np JOIN public."Attachment" at ON at.message_id = np.id JOIN public."AttachmentDesc" ad ON ad.entity_id = at.id
    UNION ALL
    SELECT np.id, np.status, rd.description
    FROM np JOIN public."Reference" r ON r.message_id = np.id JOIN public."ReferenceDesc" rd ON rd.entity_id = r.id),
c AS (SELECT id, status, sum(pg_temp.mails(txt)) AS mails, sum(pg_temp.phones(txt)) AS phones FROM t GROUP BY id, status)
SELECT status, (SELECT count(*) FROM np WHERE np.status = c.status) AS messages,
       count(*) FILTER (WHERE mails > 0) AS with_email, sum(mails) AS email_addresses,
       count(*) FILTER (WHERE phones > 0) AS with_phone, sum(phones) AS phone_numbers
FROM c GROUP BY status ORDER BY status;

\echo
\echo '== The internal publication field of every message (never shown publicly), all statuses'
SELECT count(*) FILTER (WHERE coalesce("internalPublication", '') <> '') AS filled,
       count(*) FILTER (WHERE pg_temp.mails("internalPublication") > 0) AS with_email,
       count(*) FILTER (WHERE pg_temp.phones("internalPublication") > 0) AS with_phone
FROM public."MessageDesc";

\echo
\echo '== Step 5: language and documentation settings'
SELECT setting_key, setting_value FROM public."Setting"
WHERE setting_key IN ('defaultLanguage', 'infoDefaultLanguage', 'modelLanguages', 'siteLanguages', 'infoSiteLanguages',
                      'numeralLanguages', 'documentationUrl', 'mailValidRecipients', 'mailSender', 'country', 'organisation', 'mode')
ORDER BY setting_key;

\echo
\echo '== Facts: messages per main type and status'
SELECT "mainType", status, count(*) FROM public."Message" GROUP BY 1, 2 ORDER BY 1, 2;

\echo
\echo '== Facts: messages per type'
SELECT "mainType", type, count(*) FROM public."Message" GROUP BY 1, 2 ORDER BY 1, 2;

\echo
\echo '== Facts: domains'
SELECT d."domainId", d.name, d.active, d.publish, d."timeZone",
       (SELECT string_agg(ms."seriesId", ', ' ORDER BY ms."seriesId") FROM public."Domain_MessageSeries" dm
        JOIN public."MessageSeries" ms ON ms.id = dm."messageSeries_id" WHERE dm."Domain_id" = d.id) AS series
FROM public."Domain" d ORDER BY d."sortOrder";

\echo
\echo '== Facts: message series'
SELECT ms."seriesId", ms."mainType", ms."numberSequenceType", ms."shortFormat",
       (SELECT count(*) FROM public."Message" m WHERE m."messageSeries_id" = ms.id) AS messages
FROM public."MessageSeries" ms ORDER BY 1;

\echo
\echo '== Facts: publication series and issues (the new model)'
SELECT ps."seriesId", ps.kind, ps.status, ps.cadence, ps.availability,
       (SELECT count(*) FROM public."PublicationIssue" pi WHERE pi.series_id = ps.id) AS issues,
       (SELECT string_agg(DISTINCT pi.status, ',') FROM public."PublicationIssue" pi WHERE pi.series_id = ps.id) AS issue_statuses
FROM public."PublicationSeries" ps ORDER BY ps."sortOrder", ps."seriesId";
SELECT status, count(*) AS issues FROM public."PublicationIssue" GROUP BY 1 ORDER BY 1;
SELECT count(*) AS legacy_publications FROM public."Publication";

\echo
\echo '== Facts: areas, firing areas and firing periods'
SELECT CASE type WHEN '0' THEN 'COUNTRY' WHEN '1' THEN 'FIRING_AREA' ELSE 'plain' END AS area_type, count(*)
FROM public."Area" GROUP BY 1 ORDER BY 1;
SELECT count(*) AS firing_periods, count(DISTINCT area_id) AS areas_with_periods, min("fromDate")::date AS first,
       max("toDate")::date AS last, count(*) FILTER (WHERE "toDate" >= now()) AS current_or_future
FROM public."FiringPeriod";
SELECT count(*) AS firing_schedules, count(*) FILTER (WHERE active) AS active FROM public."FiringSchedule";

\echo
\echo '== Facts: mailing lists and their recipients'
SELECT ml."mailingListId", ml.active,
       (SELECT count(*) FROM public."MailingList_Contact" mc WHERE mc."MailingList_id" = ml.id) AS contacts,
       (SELECT count(*) FROM public."MailingList_User" mu WHERE mu."MailingList_id" = ml.id) AS users,
       (SELECT count(*) FROM public."MailingListTrigger" t WHERE t."mailingList_id" = ml.id) AS triggers
FROM public."MailingList" ml ORDER BY ml.id;

\echo
\echo '== Facts: counts'
SELECT 'users' AS what, count(*) FROM public."User"
UNION ALL SELECT 'contacts', count(*) FROM public."Contact"
UNION ALL SELECT 'comments', count(*) FROM public."Comment"
UNION ALL SELECT 'comments acknowledged', count(*) FROM public."Comment" WHERE "acknowledgedBy_id" IS NOT NULL
UNION ALL SELECT 'message history rows', count(*) FROM public."MessageHistory"
UNION ALL SELECT 'script resource history rows', count(*) FROM public."ScriptResourceHistory"
UNION ALL SELECT 'issue audit entries', count(*) FROM public."IssueAuditEntry"
UNION ALL SELECT 'queued mails', count(*) FROM public."ScheduledMail"
UNION ALL SELECT 'batch job rows', count(*) FROM public."BatchData"
UNION ALL SELECT 'message tags', count(*) FROM public."MessageTag"
UNION ALL SELECT 'saved message filters', count(*) FROM public."MessageFilter"
UNION ALL SELECT 'attachments', count(*) FROM public."Attachment"
UNION ALL SELECT 'charts', count(*) FROM public."Chart"
UNION ALL SELECT 'categories', count(*) FROM public."Category"
UNION ALL SELECT 'sources', count(*) FROM public."Source"
UNION ALL SELECT 'script resources', count(*) FROM public."ScriptResource"
UNION ALL SELECT 'liquibase changesets applied', count(*) FROM public.databasechangelog;

\echo
\echo '== Facts: Liquibase changesets applied to the copy'
SELECT orderexecuted, id FROM public.databasechangelog ORDER BY orderexecuted;

\echo
\echo '== Fingerprint of the scrubbed tables (the same on every run while niord_test is unchanged)'
SELECT md5(concat_ws('|',
    (SELECT string_agg(concat_ws(',', id, username, "firstName", "lastName", email), ';' ORDER BY id) FROM public."User"),
    (SELECT string_agg(concat_ws(',', id, name, email, language), ';' ORDER BY id) FROM public."Contact"),
    (SELECT string_agg(concat_ws(',', id, user_id, comment), ';' ORDER BY id) FROM public."Comment"),
    (SELECT string_agg(concat_ws(',', "Comment_id", "emailAddresses"), ';' ORDER BY "Comment_id", "emailAddresses") FROM public."Comment_emailAddresses"),
    (SELECT string_agg(concat_ws(',', id, name), ';' ORDER BY id) FROM public."MessageFilter"),
    (SELECT string_agg(concat_ws(',', id, name), ';' ORDER BY id) FROM public."MessageTag"),
    (SELECT string_agg(concat_ws(',', "MailingList_id", contacts_id), ';' ORDER BY "MailingList_id", contacts_id) FROM public."MailingList_Contact"),
    (SELECT string_agg(concat_ws(',', "MailingList_id", users_id), ';' ORDER BY "MailingList_id", users_id) FROM public."MailingList_User"),
    (SELECT string_agg(concat_ws(',', setting_key, setting_value), ';' ORDER BY setting_key) FROM public."Setting")
)) AS fingerprint;
