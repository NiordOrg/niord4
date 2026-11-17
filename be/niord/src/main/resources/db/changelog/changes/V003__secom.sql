--liquibase formatted sql
--changeset niord:003-secom splitStatements:true endDelimiter:;
-- SECOM/S-124 integration schema (docs/secom-design.md §4 +
-- docs/s124-cancellation-plan.md): remote peers, their subscriptions, the persisted S-124
-- dataset instances generated for published navigational warnings, the durable delivery
-- outbox, the SECOM runtime configuration (node-wide singleton + per-series exposure),
-- and the S-124-cancellation column on the core tables (Message kind). The SECOM tables
-- exist in every deployment but stay empty unless niord.secom.enabled=true; the core
-- column is always in use.
--
-- Rewritten in place pre-cutover (2026-07-09): absorbs the former V004 (outbox
-- containerType), V005 (message kind) and V006 (series cancellation warnings) changesets
-- and adds the config tables. Every existing database predates production cutover and is
-- rebuilt from scratch by Liquibase, so no upgrade path from the old changeset ids is kept.
--
-- Extended in place pre-cutover (2026-07-10): S124Dataset gains the persisted exchange-set
-- signature (signature/signedPayloadHash/signatureAlgorithm/signatureCertificate), the
-- discovery metadata captured at signing time (fileName/issueDate), and the cancelled
-- timestamp driving the 365-day fileless-cancellation window (S-124R010/R011).
--
-- Extended in place pre-cutover (2026-09-05): S124Dataset gains "discoveryMetadata", the
-- S100_DatasetDiscoveryMetadata entry published for the dataset, stored as XML. S-100 Part 17,
-- clause 17-4.4.1, builds a fileless cancellation by reproducing the original entry verbatim
-- apart from the issue date, so the entry has to outlive the exchange catalogue it shipped in;
-- the bindings library hands it over from S124ExchangeSetFactory.toExchangeSet(). Rows signed
-- before this column existed have it null and are excluded from cancellation announcements by
-- S124Dataset.findCancelledSince until the reconciliation sweep re-signs them.
--
-- Restructured in place pre-cutover (2026-09-10): the dataset content moves off the S124Dataset
-- row into immutable "S124DatasetVersion" rows. Every publish, update and cancellation of a
-- warning appends one version - the dataset file exactly as the exchange-set factory packaged and
-- signed it, its file name, the S100_DatasetDiscoveryMetadata entry published with it (carrying
-- the signature), the signature itself and the chain that made it - and the dataset row points at
-- the current one. A version is never modified: every exchange set ships it verbatim and a
-- fileless cancellation reproduces its entry, so there is no payload hash, no staleness check and
-- no re-signing after a certificate rotation (the chain travels with the record, S-100 Part 15,
-- clause 15-8.7). The former gml/fileName/issueDate/signature/signedPayloadHash/
-- signatureAlgorithm/signatureCertificate/discoveryMetadata columns are gone with it.
--
-- Reduced in place pre-cutover (2026-08-20): the per-series "issueCancellationWarnings"
-- activation column is gone. Cancellation warnings are no longer opt-in - cancelling a
-- published NW warning of an auto-numbered series always mints one - so there was nothing
-- left to configure. See docs/s124-cancellation-plan.md.
--
-- Extended in place pre-cutover (2026-09-10): S124SeriesConfig gains the per-series
-- language restriction (languages), and S124DatasetVersion records the languages a version
-- is spelled in (languages), so exchange sets declare their locales without parsing GML.

CREATE SEQUENCE public."SecomNode_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public."SecomNode" (
    id integer NOT NULL,
    mrn character varying(256) NOT NULL,
    "lastInteraction" timestamp(6) without time zone,
    created timestamp(6) without time zone NOT NULL
);

ALTER TABLE ONLY public."SecomNode"
    ADD CONSTRAINT "SecomNode_pkey" PRIMARY KEY (id);

ALTER TABLE ONLY public."SecomNode"
    ADD CONSTRAINT "SecomNode_mrn_key" UNIQUE (mrn);


CREATE SEQUENCE public."SecomSubscription_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public."SecomSubscription" (
    id integer NOT NULL,
    uuid uuid NOT NULL,
    node_id integer NOT NULL,
    "dataProductType" character varying(32) NOT NULL,
    "productVersion" character varying(32),
    "containerType" character varying(32),
    "dataReference" uuid,
    geometry public.geometry,
    wkt text,
    "periodStart" timestamp(6) without time zone,
    "periodEnd" timestamp(6) without time zone,
    created timestamp(6) without time zone NOT NULL
);

ALTER TABLE ONLY public."SecomSubscription"
    ADD CONSTRAINT "SecomSubscription_pkey" PRIMARY KEY (id);

ALTER TABLE ONLY public."SecomSubscription"
    ADD CONSTRAINT "SecomSubscription_uuid_key" UNIQUE (uuid);

ALTER TABLE ONLY public."SecomSubscription"
    ADD CONSTRAINT "SecomSubscription_node_fkey" FOREIGN KEY (node_id) REFERENCES public."SecomNode"(id);

CREATE INDEX secom_subscription_node ON public."SecomSubscription" (node_id);


CREATE SEQUENCE public."S124Dataset_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public."S124Dataset" (
    id integer NOT NULL,
    uuid uuid NOT NULL,
    mrn character varying(256) NOT NULL,
    message_id integer NOT NULL,
    status character varying(32) NOT NULL,
    current_version_id integer,
    geometry public.geometry,
    "validFrom" timestamp(6) without time zone,
    "validTo" timestamp(6) without time zone,
    cancelled timestamp(6) without time zone,
    created timestamp(6) without time zone NOT NULL,
    updated timestamp(6) without time zone NOT NULL
);

ALTER TABLE ONLY public."S124Dataset"
    ADD CONSTRAINT "S124Dataset_pkey" PRIMARY KEY (id);

ALTER TABLE ONLY public."S124Dataset"
    ADD CONSTRAINT "S124Dataset_uuid_key" UNIQUE (uuid);

ALTER TABLE ONLY public."S124Dataset"
    ADD CONSTRAINT "S124Dataset_mrn_key" UNIQUE (mrn);

ALTER TABLE ONLY public."S124Dataset"
    ADD CONSTRAINT "S124Dataset_message_fkey" FOREIGN KEY (message_id) REFERENCES public."Message"(id);

CREATE INDEX s124_dataset_message ON public."S124Dataset" (message_id);
CREATE INDEX s124_dataset_status ON public."S124Dataset" (status);


-- The immutable published versions of a dataset (see the header): one row per publish, update
-- and cancellation, written once by the exchange-set build that signs it. The dataset row's
-- current_version_id points at the latest; the two tables reference each other, so the dataset's
-- foreign key is added after this table exists and the pointer is nullable in the schema only.
CREATE SEQUENCE public."S124DatasetVersion_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public."S124DatasetVersion" (
    id integer NOT NULL,
    dataset_id integer NOT NULL,
    gml text NOT NULL,
    "fileName" character varying(128) NOT NULL,
    "datasetId" character varying(256),
    "datasetTitle" character varying(256),
    "discoveryMetadata" text NOT NULL,
    signature bytea NOT NULL,
    "signingCertificates" text NOT NULL,
    -- Comma-separated model-language codes the file is spelled in, primary first
    languages character varying(64) NOT NULL,
    created timestamp(6) without time zone NOT NULL
);

ALTER TABLE ONLY public."S124DatasetVersion"
    ADD CONSTRAINT "S124DatasetVersion_pkey" PRIMARY KEY (id);

ALTER TABLE ONLY public."S124DatasetVersion"
    ADD CONSTRAINT "S124DatasetVersion_dataset_fkey" FOREIGN KEY (dataset_id) REFERENCES public."S124Dataset"(id);

CREATE INDEX s124_dataset_version_dataset ON public."S124DatasetVersion" (dataset_id);

ALTER TABLE ONLY public."S124Dataset"
    ADD CONSTRAINT "S124Dataset_current_version_fkey" FOREIGN KEY (current_version_id) REFERENCES public."S124DatasetVersion"(id);


CREATE SEQUENCE public."SecomOutboxEntry_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

-- containerType: the container type of the snapshotted payload (S100_ExchangeSet or
-- S100_DataSet), decided from the subscription at enqueue time and echoed in the upload
-- envelope (was V004)
CREATE TABLE public."SecomOutboxEntry" (
    id integer NOT NULL,
    "transactionId" uuid NOT NULL,
    node_id integer NOT NULL,
    "subscriptionUuid" uuid,
    operation character varying(32) NOT NULL,
    "notificationEvent" character varying(64),
    dataset_id integer,
    payload bytea,
    "containerType" character varying(32),
    status character varying(32) NOT NULL,
    attempts integer NOT NULL,
    "nextAttemptAt" timestamp(6) without time zone,
    "lastError" text,
    "sentAt" timestamp(6) without time zone,
    "ackedAt" timestamp(6) without time zone,
    "openedAt" timestamp(6) without time zone,
    "nackType" character varying(64),
    created timestamp(6) without time zone NOT NULL
);

ALTER TABLE ONLY public."SecomOutboxEntry"
    ADD CONSTRAINT "SecomOutboxEntry_pkey" PRIMARY KEY (id);

ALTER TABLE ONLY public."SecomOutboxEntry"
    ADD CONSTRAINT "SecomOutboxEntry_transactionId_key" UNIQUE ("transactionId");

ALTER TABLE ONLY public."SecomOutboxEntry"
    ADD CONSTRAINT "SecomOutboxEntry_node_fkey" FOREIGN KEY (node_id) REFERENCES public."SecomNode"(id);

ALTER TABLE ONLY public."SecomOutboxEntry"
    ADD CONSTRAINT "SecomOutboxEntry_dataset_fkey" FOREIGN KEY (dataset_id) REFERENCES public."S124Dataset"(id);

CREATE INDEX secom_outbox_status ON public."SecomOutboxEntry" (status, "nextAttemptAt");
CREATE INDEX secom_outbox_node ON public."SecomOutboxEntry" (node_id);


-- Per-message-series S-124/SECOM configuration: whether the published NW messages of the
-- series are exposed as S-124 datasets over SECOM. No row (or exposeOverSecom = false) =
-- the series is never published over SECOM - closed by default (replaces the old
-- secomS124Series setting)
CREATE SEQUENCE public."S124SeriesConfig_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE public."S124SeriesConfig" (
    id integer NOT NULL,
    series_id integer NOT NULL,
    "exposeOverSecom" boolean NOT NULL,
    -- Comma-separated model-language codes the series' S-124 datasets may carry, in
    -- model-language order; NULL = no restriction (every model language with content)
    languages character varying(64)
);

ALTER TABLE ONLY public."S124SeriesConfig"
    ADD CONSTRAINT "S124SeriesConfig_pkey" PRIMARY KEY (id);

ALTER TABLE ONLY public."S124SeriesConfig"
    ADD CONSTRAINT "S124SeriesConfig_series_key" UNIQUE (series_id);

ALTER TABLE ONLY public."S124SeriesConfig"
    ADD CONSTRAINT "S124SeriesConfig_series_fkey" FOREIGN KEY (series_id) REFERENCES public."MessageSeries"(id);


-- Message classification kind, orthogonal to the message type: WARNING (ordinary editorial
-- message - the default for all existing rows), CANCELLATION (auto-created S-124 cancellation
-- warning), BULLETIN (reserved for in-force bulletins). See docs/s124-cancellation-plan.md.
-- (was V005)
ALTER TABLE public."Message" ADD COLUMN kind character varying(32) NOT NULL DEFAULT 'WARNING';
