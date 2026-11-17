-- Drop every table the publications redesign created.
--
-- Run after a prod-to-test restore, before starting the application. Flyway then
-- recreates them from V1..V17, so the rebuild is part of what gets rehearsed.
--
-- The DELETE at the end is not optional: with V1..V17 still recorded as applied,
-- Flyway will not recreate the tables it just lost. Extend that version list when
-- a V18 is added.
--
-- Every DROP is IF EXISTS, so this is safe on a database that never had one of
-- them. ShadowDiffRun is the case that matters: V5 creates it and V17 drops it
-- again, so whether it is there depends on how far up the chain the restored
-- copy was built -- and a rebuild from V1 recreates and re-drops it either way.
--
-- Untouched: Publication, PublicationDesc, PublicationCategory,
-- PublicationCategoryDesc -- those are legacy and the restore provides them.

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS IssueMember;
DROP TABLE IF EXISTS IssueOverride;
DROP TABLE IF EXISTS IssueAuditEntry;
DROP TABLE IF EXISTS PublicationIssueDesc;
DROP TABLE IF EXISTS PublicationIssue;
DROP TABLE IF EXISTS PublicationSeriesDesc;
DROP TABLE IF EXISTS PublicationSeries_AvailableDomain;
DROP TABLE IF EXISTS PublicationSeries_languages;
DROP TABLE IF EXISTS PublicationSeries;
DROP TABLE IF EXISTS ShadowDiffRun;

SET FOREIGN_KEY_CHECKS = 1;

DELETE FROM flyway_schema_history
 WHERE type <> 'BASELINE'
   AND version IN ('1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13', '14', '15', '16', '17', '18', '19' );
