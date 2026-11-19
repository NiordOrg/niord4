--liquibase formatted sql
--changeset niord:012-mailing-list-status-change splitStatements:true endDelimiter:;
-- The mailing lists' outbox of status changes (org.niord.core.mailinglist.MailingListStatusChangeOutbox): one row per
-- status change of a message, written in the status change's own transaction, so the duty to make its status-change
-- mails is committed with the status change, or rolled back with it. The JMS topic that announces the status change is
-- not stored and can lose it (a restart, a broker error, a listener subscribing again); the row cannot. The listener
-- (on the topic's prompt), a sweep every minute and one pass at start run the row's triggers and then delete the row,
-- so the table is empty but for the moments in between.
--
-- dueAt is when the row may next be taken: at first the status change's time; while a node runs the row's triggers,
-- the end of that node's lease, so no other node takes it; after a failure, a few minutes on, when it is tried
-- again. attempts counts the takes, lastError holds the last failure. settledTriggers holds the ids of the row's
-- triggers that are settled (their mails queued, or their failure recorded in the Mail Queue), each written in the
-- transaction of those mails or that record: a row is deleted only when every trigger is settled, and a later take
-- runs only the triggers still owed. Times are with time zone, so no node's JVM zone reads them. The identity id is
-- the order the rows are taken in; the table never holds sample rows.
--
-- Schema only, no data (D2). Once applied this changeset is never edited, its comments included: every database
-- records its checksum, which ChangelogChecksumPinTest pins.
CREATE TABLE public."MailingListStatusChange" (
    id bigint GENERATED ALWAYS AS IDENTITY,
    "messageUid" character varying(36) NOT NULL,
    status character varying(32) NOT NULL,
    created timestamp(6) with time zone NOT NULL,
    "dueAt" timestamp(6) with time zone NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    "lastError" text,
    "settledTriggers" integer[] DEFAULT '{}'::integer[] NOT NULL,
    CONSTRAINT "MailingListStatusChange_pkey" PRIMARY KEY (id)
);
CREATE INDEX "MailingListStatusChange_due_idx" ON public."MailingListStatusChange" ("dueAt", id);
