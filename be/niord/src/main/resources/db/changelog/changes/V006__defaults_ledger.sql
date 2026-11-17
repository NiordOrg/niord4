--liquibase formatted sql
--changeset niord:006-defaults-ledger splitStatements:true endDelimiter:;
-- The ledger of what start-up added from the base product's defaults (docs/code-data-config-plan.md §4.2;
-- org.niord.core.defaults.DefaultsLedger). Start-up adds a default text key or translation a deployment lacks
-- and never changes one; this table remembers what it added, so a key or translation an operator deleted is
-- not added again at the next start.
--
-- A row is one (kind, key, dictionary, lang): kind TEXT for a dictionary text, SETTING for a setting key (from
-- C3), with '' as a SETTING row's dictionary and lang, since a primary-key column cannot be null. hash is the
-- sha-256 of the value added, or of a row already present that equalled the default when the ledger first saw
-- it. source is the layer the value came from (base, pack:<lang>, country, root), adopted for a default that was
-- already in the database, or deleted. A deleted key has the lang '*': a delete removes the key in every
-- language, and start-up adds it in none, a language added later included. "baseVersion" is the niord-base
-- version that wrote the row.
--
-- No sequence and no generated id: writing a row draws nothing from a shared sequence, and start-up writes its
-- rows in one batch. No foreign key to "Dictionary": the ledger outlives the rows it records. No unique index
-- is added to "DictionaryEntry" either: a deployment may already hold duplicate keys, and a repository change
-- never changes or deletes its rows (R-CDC1); two start-ups are serialised by an advisory lock instead.
--
-- Schema only, no data (D2). Once applied this changeset is never edited, its comments included: every
-- database records its checksum, which ChangelogChecksumPinTest pins.
CREATE TABLE public."DefaultsLedger" (
    kind character varying(255) NOT NULL,
    "key" character varying(255) NOT NULL,
    dictionary character varying(255) NOT NULL,
    lang character varying(255) NOT NULL,
    hash character varying(64),
    source character varying(255) NOT NULL,
    "baseVersion" character varying(255) NOT NULL,
    created timestamp(6) with time zone NOT NULL,
    CONSTRAINT "DefaultsLedger_pkey" PRIMARY KEY (kind, "key", dictionary, lang),
    CONSTRAINT "DefaultsLedger_kind_check" CHECK (((kind)::text = ANY ((ARRAY['TEXT'::character varying, 'SETTING'::character varying])::text[])))
);
