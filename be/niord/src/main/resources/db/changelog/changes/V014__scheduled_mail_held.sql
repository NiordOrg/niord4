--liquibase formatted sql
--changeset niord:014-scheduled-mail-held splitStatements:true endDelimiter:;
-- The Mail Queue's held mails and Send again (org.niord.core.mail.ScheduledMail).
--
-- A mail the mail filter (the setting mailValidRecipients) kept from its recipients when the sender job handled it is
-- now stored as HELD instead of SENT: the filter was LOG, so nothing left the server, or a list of addresses that did
-- not name every recipient, so their copies went to the first listed address. The queue then tells a mail that
-- reached its recipients from one that did not. The baseline's check on the status column admits PENDING, SENT and ERROR only, and Hibernate's validation does not
-- read check constraints, so without the wider check the first held mail would fail in the database, not at start.
-- The constraint is dropped by its name, as every database is built by the baseline: a database where it has another
-- name fails here, at start.
--
-- A system administrator can send a held, sent or failed mail again; the two new columns record who did it last and
-- when (sentAgainBy, sentAgainAt), shown in the mail's details. Both are null for every existing row, and every
-- existing status passes the new check.
--
-- Schema only, no data (D2). Once applied this changeset is never edited, its comments included: every database
-- records its checksum, which ChangelogChecksumPinTest pins.
ALTER TABLE public."ScheduledMail" DROP CONSTRAINT "ScheduledMail_status_check";
ALTER TABLE public."ScheduledMail" ADD CONSTRAINT "ScheduledMail_status_check"
    CHECK (status IN ('PENDING', 'SENT', 'HELD', 'ERROR'));
ALTER TABLE public."ScheduledMail" ADD COLUMN "sentAgainAt" timestamp(6) without time zone;
ALTER TABLE public."ScheduledMail" ADD COLUMN "sentAgainBy" character varying(255);
