--liquibase formatted sql
--changeset niord:008-script-resource-history-source splitStatements:true endDelimiter:;
-- What wrote a script resource's history row (docs/code-data-config-plan.md §6.4;
-- org.niord.core.script.ScriptResourceHistory): import for the Script resources import and the bootstrap, which
-- runs the same import job, and editor for a create or an update on the Script resources page. Null for the rows
-- written before this column existed, which nothing fills in afterwards.
--
-- Schema only, no data (D2). Once applied this changeset is never edited, its comments included: every database
-- records its checksum, which ChangelogChecksumPinTest pins.
ALTER TABLE public."ScriptResourceHistory" ADD COLUMN source character varying(32);
