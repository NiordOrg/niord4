-- The missing-path report (docs/code-data-config-plan.md §6.6), generated from MissingPathReport.sql():
-- every template or script path that data or the code names and that has no ScriptResource row, and
-- every report id with no FmReport row, built-in or named by a publication series or issue. It only
-- reads; run it before an upgrade with
--   psql -d <database> -f missing-paths.sql
WITH RECURSIVE
required(path) AS (SELECT unnest(CAST(ARRAY['templates/aton/light-character.ftl', 'templates/aton/telephony-codes.ftl', 'templates/geometry/bullet-list.ftl', 'templates/geometry/from-to-table.ftl', 'templates/geometry/numbered-list.ftl', 'templates/geometry/table.ftl', 'templates/geometry/text.ftl', 'templates/messages/message-list-pdf-footer.ftl', 'templates/messages/message-list-pdf.ftl', 'templates/messages/message-mail.ftl', 'templates/messages/message-support.ftl'] AS text[]))),
expected(path) AS (SELECT unnest(CAST(ARRAY['templates/aton/light-character.ftl', 'templates/aton/telephony-codes.ftl', 'templates/geometry/bullet-list.ftl', 'templates/geometry/from-to-table.ftl', 'templates/geometry/numbered-list.ftl', 'templates/geometry/table.ftl', 'templates/geometry/text.ftl', 'templates/mailinglist/active-navwarns.ftl', 'templates/mailinglist/cancel-navtex.ftl', 'templates/mailinglist/cancel-navwarn.ftl', 'templates/mailinglist/cancel-safetynet.ftl', 'templates/mailinglist/publish-navtex.ftl', 'templates/mailinglist/publish-navwarn-common.ftl', 'templates/mailinglist/publish-navwarn.ftl', 'templates/mailinglist/publish-safetynet.ftl', 'templates/mailinglist/publish-safetynet.js', 'templates/mailinglist/safetynet-intro.ftl', 'templates/messages/fa-list-pdf-intro.ftl', 'templates/messages/fa-list-pdf.ftl', 'templates/messages/message-list-pdf-footer.ftl', 'templates/messages/message-list-pdf.ftl', 'templates/messages/message-mail.ftl', 'templates/messages/message-support.ftl', 'templates/messages/nm-accumulated-report-pdf-intro.ftl', 'templates/messages/nm-accumulated-report-pdf.ftl', 'templates/messages/nm-almanac-report-pdf-intro.ftl', 'templates/messages/nm-almanac-report-pdf.ftl', 'templates/messages/nm-annex-report-pdf.ftl', 'templates/messages/nm-report-pdf-intro.ftl', 'templates/messages/nm-report-pdf.ftl', 'templates/messages/nm-tp-report-pdf.ftl', 'templates/tmpl/aton-common.ftl', 'templates/tmpl/aton-replacement-common.ftl', 'templates/tmpl/beacon-damaged.ftl', 'templates/tmpl/beacon-missing.ftl', 'templates/tmpl/beacon-partly-obscured.ftl', 'templates/tmpl/bridge-closed.ftl', 'templates/tmpl/buoy-established.ftl', 'templates/tmpl/buoy-missing.ftl', 'templates/tmpl/buoy-off-station.ftl', 'templates/tmpl/buoy-replaced.ftl', 'templates/tmpl/buoy-withdrawn.ftl', 'templates/tmpl/cable-opetations.ftl', 'templates/tmpl/common.ftl', 'templates/tmpl/common.js', 'templates/tmpl/compute-affected-radius.js', 'templates/tmpl/diving-operations.ftl', 'templates/tmpl/drilling-opetations.ftl', 'templates/tmpl/exercise.ftl', 'templates/tmpl/grenade.ftl', 'templates/tmpl/hydrographic-survey.ftl', 'templates/tmpl/light-buoy-established.ftl', 'templates/tmpl/light-buoy-missing.ftl', 'templates/tmpl/light-buoy-off-station.ftl', 'templates/tmpl/light-buoy-replaced.ftl', 'templates/tmpl/light-buoy-unlit.ftl', 'templates/tmpl/light-buoy-unreliable.ftl', 'templates/tmpl/light-buoy-withdrawn.ftl', 'templates/tmpl/light-damaged.ftl', 'templates/tmpl/light-destroyed.ftl', 'templates/tmpl/light-unlit.ftl', 'templates/tmpl/light-unreliable.ftl', 'templates/tmpl/markings.ftl', 'templates/tmpl/mine.ftl', 'templates/tmpl/navtex.ftl', 'templates/tmpl/object-adrift.ftl', 'templates/tmpl/object-adrift.js', 'templates/tmpl/obstruction-found.ftl', 'templates/tmpl/obstruction-work.ftl', 'templates/tmpl/radio-navigation.ftl', 'templates/tmpl/reduced-depth.ftl', 'templates/tmpl/safetynet.ftl', 'templates/tmpl/salvage-operations.ftl', 'templates/tmpl/torpedo.ftl', 'templates/tmpl/twitter.ftl', 'templates/tmpl/uncharted-obstruction.ftl', 'templates/tmpl/underwater-survey.ftl', 'templates/tmpl/update-areas.js', 'templates/tmpl/work-vessel.ftl', 'templates/tmpl/wreck.ftl'] AS text[]))),
report_ids(id) AS (SELECT unnest(CAST(ARRAY['draft', 'public', 'standard'] AS text[]))),
refs(source, target) AS (
    SELECT r.path, m[1]
    FROM public."ScriptResource" r CROSS JOIN LATERAL regexp_matches(r.content, '<#(?:include|import)\s+["'']([^"''$]+)["'']', 'g') AS m
    WHERE r.type = 'FM'
),
resolved(path, named_by) AS (
    SELECT regexp_replace(CASE WHEN target LIKE '/%' THEN target
                               ELSE regexp_replace(source, '[^/]*$', '') || target END,
                          '(^|/)\./', '\1', 'g'),
           'include in ' || source
    FROM refs
    UNION ALL
    SELECT regexp_replace(path, '(^|/)(?!\.\./)[^/]+/\.\./', '\1'), named_by FROM resolved WHERE path ~ '(^|/)(?!\.\./)[^/]+/\.\./'
),
named(path, named_by) AS (
    SELECT path, 'code: RequiredTemplates' FROM required
    UNION ALL
    SELECT "scriptResourcePaths", 'Category ' || "Category_id" FROM public."Category_scriptResourcePaths"
    UNION ALL
    SELECT "scriptResourcePaths", 'MailingListTrigger ' || "MailingListTrigger_id"
    FROM public."MailingListTrigger_scriptResourcePaths"
    UNION ALL
    SELECT "scriptResourcePaths", 'PromulgationType ' || "PromulgationType_id"
    FROM public."PromulgationType_scriptResourcePaths"
    UNION ALL
    SELECT "templatePath", 'FmReport ' || "reportId" FROM public."FmReport"
    UNION ALL
    SELECT path, named_by FROM resolved WHERE path !~ '(^|/)(?!\.\./)[^/]+/\.\./'
    UNION ALL
    SELECT m[1], 'load in ' || r.path
    FROM public."ScriptResource" r CROSS JOIN LATERAL regexp_matches(r.content, 'load\(\s*["'']niord:([^"'']+)["'']\s*\)', 'g') AS m
    WHERE r.type = 'JS'
),
normalized(path, named_by) AS (
    SELECT CASE WHEN path LIKE '/%' THEN substr(path, 2) ELSE path END, named_by
    FROM named WHERE path <> ''
),
with_variants(path, named_by) AS (
    SELECT path, named_by FROM normalized
    UNION ALL
    SELECT e.path, 'variant of ' || regexp_replace(e.path, '^(.*)_[a-z]{2}\.ftl$', '\1.ftl')
    FROM expected e
    WHERE e.path ~ '^(.*)_[a-z]{2}\.ftl$'
      AND EXISTS (SELECT 1 FROM normalized n WHERE n.path = regexp_replace(e.path, '^(.*)_[a-z]{2}\.ftl$', '\1.ftl'))
),
report_names(id, named_by) AS (
    SELECT id, 'code: FmReportService.BUILT_IN_REPORT_IDS' FROM report_ids
    UNION ALL
    SELECT "reportId", 'PublicationSeries ' || "seriesId" FROM public."PublicationSeries" WHERE "reportId" <> ''
    UNION ALL
    SELECT "reportId", 'PublicationIssue ' || "publicId" FROM public."PublicationIssue" WHERE "reportId" <> ''
)
SELECT kind, path, named_by FROM (
    SELECT 'TEMPLATE' AS kind, w.path,
           string_agg(DISTINCT w.named_by COLLATE "C", '; ' ORDER BY w.named_by COLLATE "C") AS named_by
    FROM with_variants w
    WHERE NOT EXISTS (SELECT 1 FROM public."ScriptResource" s WHERE s.path = w.path)
    GROUP BY w.path
    UNION ALL
    SELECT 'REPORT', r.id, string_agg(DISTINCT r.named_by COLLATE "C", '; ' ORDER BY r.named_by COLLATE "C")
    FROM report_names r
    WHERE NOT EXISTS (SELECT 1 FROM public."FmReport" f WHERE f."reportId" = r.id)
    GROUP BY r.id
) missing
ORDER BY CASE kind WHEN 'TEMPLATE' THEN 0 ELSE 1 END, path COLLATE "C";
