-- What the screenshots need of the copy that is no matter of personal data. Applied by make-guides-db.sh after
-- the scrub and its checks, in one transaction; running it again changes nothing.

-- A promulgation type whose service the base product does not have would show on every promulgation screen
-- with nothing behind it: the base product's services are audio, navtex, safetynet and twitter.
UPDATE public."PromulgationType"
   SET active = false
 WHERE active
   AND "serviceId" NOT IN ('audio', 'navtex', 'safetynet', 'twitter');

-- Mails are sent, so that the mail queue and a received mail can be shown. The local stack's SMTP server is the
-- loopback mail catcher, and after the scrub every recipient is an invented one on example.org.
UPDATE public."Setting"
   SET setting_value = '"ALL"'
 WHERE setting_key = 'mailValidRecipients'
   AND setting_value IS DISTINCT FROM '"ALL"';

-- The backend's own address, which the rig's slot fixes (start-rig.sh: slot 6, backend on 8894). The copy keeps the
-- test data's slot-0 address, and a mail is built by fetching its style sheet from there: with nothing listening, every
-- mail fails ("Connection refused") and stays in the queue. Message links in mails and reports use it too.
UPDATE public."Setting"
   SET setting_value = '"http://localhost:8894"'
 WHERE setting_key = 'baseUri'
   AND setting_value IS DISTINCT FROM '"http://localhost:8894"';

-- The PostgreSQL search table stays under its own name. An earlier version of this file renamed it to
-- message_search_text_guides_off, to stop an indexer that never finished; that indexer fault was fixed on 2026-11-12,
-- and with the table renamed every index pass failed at its first query, an ERROR line in the backend log once a
-- minute. A copy an earlier run left so gets the table back under its own name, only when no table holds that name;
-- the indexer brings it up to date from the backend's next start (5,000 messages a pass). The copy searches through Lucene
-- (disableLucene is false), so no picture reads the table either way.
DO $$
BEGIN
    IF to_regclass('public.message_search_text_guides_off') IS NOT NULL
       AND to_regclass('public.message_search_text') IS NULL THEN
        ALTER TABLE public.message_search_text_guides_off RENAME TO message_search_text;
        PERFORM set_config('guides.search_table_renamed_back', 'yes', false);
    END IF;
END $$;

-- The Lucene index is kept between starts. The test data deletes and rebuilds it at every start, about ten
-- minutes in which a text search answers from half an index and a picture of a search can differ.
UPDATE public."Setting"
   SET setting_value = 'false'
 WHERE setting_key = 'messageIndexDeleteOnStartup'
   AND setting_value IS DISTINCT FROM 'false';

-- The firing periods are moved eight weeks on, whole weeks so that every period keeps its weekday. The test data's
-- newest period ends on 1 October 2026 and the pictures are taken at the rig's fixed instant in November
-- (support/env.ts, GUIDES_NOW), where the schedule would be empty. Only while the newest period still ends before
-- November, so a second run moves nothing.
UPDATE public."FiringPeriod"
   SET "fromDate" = "fromDate" + interval '56 days',
       "toDate" = "toDate" + interval '56 days'
 WHERE (SELECT max("toDate") FROM public."FiringPeriod") < timestamp '2026-11-01';

-- The batch job records of the test data are deleted: they record runs on other servers, whose execution folders are
-- not in the guides home (make-guides-home.sh leaves the batch job data out). No table refers to BatchData; the job
-- numbers come from the Sequence table (BATCH_JOB_<name>), which stays. With the rows gone, the repository import the
-- rig runs after the first start is instance 1 and the only row. On a copy a backend has already run on, this also
-- deletes the records of the rig's own runs (their executions stay listed by the running backend, without file name and
-- user); apply it while no job runs.
DELETE FROM public."BatchData";

-- The promulgation type 'baleen' is a country's type for a service the base product does not have (no entry in the
-- Add Promulgation Type menu): it leaves the copy with everything that hangs on it. In niord_test it has one domain
-- link (niord-nw) and no message promulgation, settings, transmitter, area, message type or script path; the deletes
-- cover each of them all the same, children first.
DELETE FROM public."AudioMessagePromulgation" WHERE id IN
    (SELECT b.id FROM public."BaseMessagePromulgation" b
       JOIN public."PromulgationType" t ON t.id = b.type_id WHERE t."typeId" = 'baleen');
DELETE FROM public."NavtexMessagePromulgation_NavtexTransmitter" WHERE "NavtexMessagePromulgation_id" IN
    (SELECT b.id FROM public."BaseMessagePromulgation" b
       JOIN public."PromulgationType" t ON t.id = b.type_id WHERE t."typeId" = 'baleen');
DELETE FROM public."NavtexMessagePromulgation" WHERE id IN
    (SELECT b.id FROM public."BaseMessagePromulgation" b
       JOIN public."PromulgationType" t ON t.id = b.type_id WHERE t."typeId" = 'baleen');
DELETE FROM public."SafetyNetMessagePromulgation" WHERE id IN
    (SELECT b.id FROM public."BaseMessagePromulgation" b
       JOIN public."PromulgationType" t ON t.id = b.type_id WHERE t."typeId" = 'baleen');
DELETE FROM public."TwitterMessagePromulgation" WHERE id IN
    (SELECT b.id FROM public."BaseMessagePromulgation" b
       JOIN public."PromulgationType" t ON t.id = b.type_id WHERE t."typeId" = 'baleen');
DELETE FROM public."BaseMessagePromulgation" WHERE type_id IN
    (SELECT id FROM public."PromulgationType" WHERE "typeId" = 'baleen');
DELETE FROM public."PromulgationType_Domain" WHERE "PromulgationType_id" IN
    (SELECT id FROM public."PromulgationType" WHERE "typeId" = 'baleen');
DELETE FROM public."PromulgationType_messageTypes" WHERE "PromulgationType_id" IN
    (SELECT id FROM public."PromulgationType" WHERE "typeId" = 'baleen');
DELETE FROM public."PromulgationType_scriptResourcePaths" WHERE "PromulgationType_id" IN
    (SELECT id FROM public."PromulgationType" WHERE "typeId" = 'baleen');
DELETE FROM public."TwitterSettings" WHERE "promulgationType_id" IN
    (SELECT id FROM public."PromulgationType" WHERE "typeId" = 'baleen');
DELETE FROM public."NavtexTransmitter" WHERE "promulgationType_id" IN
    (SELECT id FROM public."PromulgationType" WHERE "typeId" = 'baleen');
DELETE FROM public."SafetyNetArea" WHERE "promulgationType_id" IN
    (SELECT id FROM public."PromulgationType" WHERE "typeId" = 'baleen');
DELETE FROM public."PromulgationType" WHERE "typeId" = 'baleen';

-- The Sysadmin menu's Niord Integration entry, which the sysadmin manual describes, shows when the setting
-- adminIntegrationPageEnabled is true and the backend sends it to the browser (GET /rest/settings/web lists the rows
-- with web = true). The test data's row is an older declaration's (web and editable false), and the backend never
-- changes an existing row, so the row takes the base product's declaration (FrontendSettings: web, editable) and the
-- value true (a Boolean setting's value is the JSON text true). A copy without the row gets it.
INSERT INTO public."Setting" (setting_key, cached, description, editable, type, setting_value, web)
VALUES ('adminIntegrationPageEnabled', true, 'Shows the Integration section of the administration app', true,
        'Boolean', 'true', true)
ON CONFLICT (setting_key) DO NOTHING;
UPDATE public."Setting"
   SET setting_value = 'true', web = true, editable = true
 WHERE setting_key = 'adminIntegrationPageEnabled'
   AND (setting_value IS DISTINCT FROM 'true' OR NOT web OR NOT editable);

-- The example rows of the sysadmin manual's pictures, which the copy does not have, named so a reader of the data
-- knows where they came from, inactive and in no domain, so no editor ever meets them: a SafetyNET promulgation type
-- with a NAVAREA and a circular area, a Twitter type without settings (the copy's own Twitter type has settings the
-- backend cannot return), and a Niord integration. The specs make the same rows through the REST interface when they
-- are missing (support/sysadmin-1.ts, support/sysadmin-2.ts); these are found there by their typeId, area name and URL.
-- The ids come from each table's own sequence, as the backend's would (each call takes a block of 50 the backend then
-- never hands out).
INSERT INTO public."PromulgationType"
       (id, created, updated, version, active, language, name, priority, requirement, "serviceId", "typeId")
SELECT nextval('public."PromulgationType_SEQ"'), now(), now(), 0, false, 'en', v.name, 900, 'OPTIONAL', v.service_id,
       v.type_id
  FROM (VALUES ('guide-example-safetynet', 'safetynet', 'Guide example SafetyNET'),
               ('guide-example-twitter', 'twitter', 'Guide example Twitter')) AS v (type_id, service_id, name)
 WHERE NOT EXISTS (SELECT 1 FROM public."PromulgationType" t WHERE t."typeId" = v.type_id);

-- The areas as the Areas dialog makes them: repetition is stored by its place in the list (0 = REPETITION_01,
-- 1 = REPETITION_11), the circle's centre as "Lat Lon" in whole degrees and its radius in nautical miles.
INSERT INTO public."SafetyNetArea"
       ("DTYPE", id, active, "messageAreaPrefix", name, repetition, "sortOrder", center, radius, navarea,
        "promulgationType_id")
SELECT v.dtype, nextval('public."SafetyNetArea_SEQ"'), true, v.prefix, v.name, v.repetition, v.sort_order, v.center,
       v.radius, v.navarea, t.id
  FROM (VALUES ('SafetyNetNavareaArea', 'Guide example NAVAREA I', NULL, 1, 1, NULL, NULL, 'I'),
               ('SafetyNetCircularArea', 'Guide example circle', 'SKAGERRAK', 0, 2, '57 10', '25', NULL))
       AS v (dtype, name, prefix, repetition, sort_order, center, radius, navarea)
  JOIN public."PromulgationType" t ON t."typeId" = 'guide-example-safetynet'
 WHERE NOT EXISTS (SELECT 1 FROM public."SafetyNetArea" a WHERE a.name = v.name);

-- The integration with one series mapping, inactive: the scheduler and Execute pass it by.
WITH integration AS (
    INSERT INTO public."NiordIntegration" (id, active, "assignNewUids", "createBaseData", url)
    SELECT nextval('public."NiordIntegration_SEQ"'), false, true, false, 'https://niord.example.org'
     WHERE NOT EXISTS (SELECT 1 FROM public."NiordIntegration" WHERE url = 'https://niord.example.org')
    RETURNING id)
INSERT INTO public."MessageSeriesMapping" (id, "sourceSeriesId", "targetSeriesId", "niordIntegration_id")
SELECT nextval('public."MessageSeriesMapping_SEQ"'), 'guide-example-nw', 'dma-nw', id
  FROM integration;

-- The publications (after the admin manual's checks, 2026-11-12). Every series' "Series activated" entry carries the
-- reason a local rehearsal of the test data's go-live gave it, which the series' history shows: a neutral reason.
UPDATE public."IssueAuditEntry"
   SET reason = 'Series set up'
 WHERE action = 'SERIES_ACTIVATED'
   AND reason = 'Go-live rehearsal on the local DK stack, 2026-09-15';

-- Accumulated Yearly NtM holds seven open issues at once (2020 to 2026, beside the published 2019): the legacy import
-- opened one for every year the old system had no annual, and no desk runs a series that way. All but the newest open
-- issue are removed, each only when nothing hangs on it but its desc rows and the audit entry of the import that made
-- it: no stored member, no override, no issue that supersedes it, no audit entry of any other kind. In niord_test that
-- is 2020 to 2025 (0 stored members each; the count the list shows for an open compilation is resolved when read). The
-- newest open issue (2026) stays. Its checklist still warns INTERVAL_CHAINED, "predecessor stamped 2019-12-31 23:59":
-- the published 2019 issue is the one before it either way, as it was with the six open ones between them (only a
-- published issue is a predecessor), and the series has no issue for 2020 to 2025.
WITH dropped AS (
    SELECT i.id
      FROM public."PublicationIssue" i
      JOIN public."PublicationSeries" s ON s.id = i.series_id
     WHERE s."seriesId" = 'accumulated-yearly-ntm'
       AND i.status = 'OPEN'
       AND coalesce(i."memberCount", 0) = 0
       AND i."intervalFrom" < (SELECT max(o."intervalFrom") FROM public."PublicationIssue" o
                                 WHERE o.series_id = i.series_id AND o.status = 'OPEN')
       AND NOT EXISTS (SELECT 1 FROM public."IssueMember" m WHERE m.issue_id = i.id)
       AND NOT EXISTS (SELECT 1 FROM public."IssueOverride" o WHERE o.issue_id = i.id)
       AND NOT EXISTS (SELECT 1 FROM public."PublicationIssue" x WHERE x.supersedes_id = i.id)
       AND NOT EXISTS (SELECT 1 FROM public."IssueAuditEntry" a WHERE a.issue_id = i.id AND a.action <> 'IMPORTED')),
audit AS (DELETE FROM public."IssueAuditEntry" WHERE issue_id IN (SELECT id FROM dropped) RETURNING 1),
descs AS (DELETE FROM public."PublicationIssueDesc" WHERE entity_id IN (SELECT id FROM dropped) RETURNING 1)
DELETE FROM public."PublicationIssue" WHERE id IN (SELECT id FROM dropped);

-- The publication category "Notices to Mariners" has no description in any language, so the category editor's
-- picture showed the field empty: one sentence, only where the English description is still empty.
UPDATE public."PublicationCategoryDesc" d
   SET description = 'The weekly Notices to Mariners with their list of temporary and preliminary notices, and the '
                     || 'yearly accumulated edition.'
  FROM public."PublicationCategory" c
 WHERE d.entity_id = c.id
   AND c."categoryId" = 'dk-dma-weekly-nm-publications'
   AND d.lang = 'en'
   AND coalesce(btrim(d.description), '') = '';

SELECT 'prepare: firing periods until ' || max("toDate")::date
  FROM public."FiringPeriod";
SELECT 'prepare: messageIndexDeleteOnStartup = ' || setting_value
  FROM public."Setting"
 WHERE setting_key = 'messageIndexDeleteOnStartup';
SELECT 'prepare: active promulgation types: ' || string_agg("typeId", ', ' ORDER BY priority)
  FROM public."PromulgationType"
 WHERE active;
SELECT 'prepare: mailValidRecipients = ' || setting_value
  FROM public."Setting"
 WHERE setting_key = 'mailValidRecipients';
SELECT 'prepare: baseUri = ' || setting_value
  FROM public."Setting"
 WHERE setting_key = 'baseUri';
SELECT 'prepare: PostgreSQL search table '
       || CASE WHEN to_regclass('public.message_search_text') IS NULL THEN 'missing'
               WHEN current_setting('guides.search_table_renamed_back', true) = 'yes' THEN 'renamed back'
               ELSE 'on' END;
SELECT 'prepare: batch job records: ' || count(*)
  FROM public."BatchData";
SELECT 'prepare: promulgation types: ' || string_agg("typeId", ', ' ORDER BY priority, "typeId")
  FROM public."PromulgationType";
SELECT 'prepare: adminIntegrationPageEnabled = ' || setting_value || CASE WHEN web THEN ' (web)' ELSE ' (not web)' END
  FROM public."Setting"
 WHERE setting_key = 'adminIntegrationPageEnabled';
SELECT 'prepare: example SafetyNET areas: ' || string_agg(name, ', ' ORDER BY "sortOrder")
  FROM public."SafetyNetArea"
 WHERE name LIKE 'Guide example %';
SELECT 'prepare: example Niord integration: ' || i.url || ', ' || string_agg(m."sourceSeriesId" || ' -> '
                                                                           || m."targetSeriesId", ', ')
  FROM public."NiordIntegration" i
  JOIN public."MessageSeriesMapping" m ON m."niordIntegration_id" = i.id
 WHERE i.url = 'https://niord.example.org'
 GROUP BY i.url;
SELECT 'prepare: series activated with the reason "Series set up": ' || count(*)
  FROM public."IssueAuditEntry"
 WHERE action = 'SERIES_ACTIVATED' AND reason = 'Series set up';
SELECT 'prepare: open issues of Accumulated Yearly NtM: ' || coalesce(string_agg(i.year::text, ', ' ORDER BY i.year), 'none')
  FROM public."PublicationIssue" i
  JOIN public."PublicationSeries" s ON s.id = i.series_id
 WHERE s."seriesId" = 'accumulated-yearly-ntm' AND i.status = 'OPEN';
SELECT 'prepare: description of the publication category Notices to Mariners: ' || coalesce(d.description, '(none)')
  FROM public."PublicationCategoryDesc" d
  JOIN public."PublicationCategory" c ON c.id = d.entity_id
 WHERE c."categoryId" = 'dk-dma-weekly-nm-publications' AND d.lang = 'en';
