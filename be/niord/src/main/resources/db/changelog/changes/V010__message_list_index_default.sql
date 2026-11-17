--liquibase formatted sql
--changeset niord:010-message-list-index-default splitStatements:true endDelimiter:;
-- The order column "indexNo" of a message's parts, references and attachments and of a part's event dates gets the
-- default 0, as "Feature"."indexNo" has had since the baseline. Each is the @OrderColumn of a list
-- (org.niord.core.message: Message.parts, references and attachments, MessagePart.eventDates), and the element maps
-- the column read-only, so Hibernate leaves it out of the INSERT and writes the list position by a following
-- UPDATE; the default fills the NOT NULL column in between. Before, a writable copy of the column on the element
-- stored every element with 0, and a reload kept one element per list.
--
-- Schema only, no data (D2): the rows already stored keep their values. Once applied this changeset is never edited,
-- its comments included: every database records its checksum, which ChangelogChecksumPinTest pins.
ALTER TABLE public."MessagePart" ALTER COLUMN "indexNo" SET DEFAULT 0;
ALTER TABLE public."DateInterval" ALTER COLUMN "indexNo" SET DEFAULT 0;
ALTER TABLE public."Reference" ALTER COLUMN "indexNo" SET DEFAULT 0;
ALTER TABLE public."Attachment" ALTER COLUMN "indexNo" SET DEFAULT 0;
