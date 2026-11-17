--liquibase formatted sql
--changeset niord:001-baseline-schema splitStatements:true endDelimiter:;
--validCheckSum: 9:ac5891e50cfedc4d905d8f8690591ef9
-- The checksum above is the one a database built before November 2026 holds for this changeset, and it
-- stays valid there. Until then the changeset also wrote the comment on the postgis extension, which
-- only the extension's owner may write. The application's database user owns its database and is not a
-- superuser (docs/database-user-plan.md), and the extension belongs to whoever created it, so that
-- statement stopped the first start on a new database. It changed nothing in the schema and is gone.
-- The extension itself is created by a superuser before the first start: the Postgres image and the
-- chart's template database carry it, and a hosted database is ordered with it. CREATE EXTENSION IF NOT
-- EXISTS below is then a no-operation.
-- Baseline PostgreSQL 18 + PostGIS schema for Niord, generated from the Hibernate-emitted schema
-- (regenerated via niord-dk/scripts/regen-test-seed-postgres.sh, schema-only). Integration tests
-- load the full seed dump instead and disable Liquibase; this changeset bootstraps real deployments.
-- Sequences start at their default (setval lines stripped): a fresh deployment imports base data
-- which then advances them.

--
-- PostgreSQL database dump
--

-- Dumped from database version 18.1 (Debian 18.1-1.pgdg13+2)
-- Dumped by pg_dump version 18.1 (Debian 18.1-1.pgdg13+2)

--
-- Name: postgis; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;

--
-- Name: Area; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Area" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    active boolean NOT NULL,
    lineage character varying(256),
    "siblingSortOrder" double precision DEFAULT 0.0,
    "treeSortOrder" integer DEFAULT 0,
    geometry public.geometry,
    "legacyId" character varying(255),
    "messageSorting" smallint,
    mrn character varying(255),
    "originAngle" integer,
    "originLatitude" real,
    "originLongitude" real,
    type smallint,
    parent_id integer,
    CONSTRAINT "Area_messageSorting_check" CHECK ((("messageSorting" >= 0) AND ("messageSorting" <= 5))),
    CONSTRAINT "Area_type_check" CHECK (((type >= 0) AND (type <= 1)))
);

--
-- Name: AreaDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."AreaDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    entity_id integer NOT NULL
);

--
-- Name: AreaDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."AreaDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Area_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Area_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Area_editorFields; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Area_editorFields" (
    "Area_id" integer NOT NULL,
    "editorFields" character varying(255)
);

--
-- Name: AtonNode; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."AtonNode" (
    id integer NOT NULL,
    changeset integer NOT NULL,
    geometry public.geometry NOT NULL,
    lat double precision NOT NULL,
    lon double precision NOT NULL,
    "timestamp" timestamp(6) without time zone,
    uid integer NOT NULL,
    "user" character varying(255),
    version integer NOT NULL,
    visible boolean NOT NULL
);

--
-- Name: AtonNode_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."AtonNode_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: AtonTag; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."AtonTag" (
    id integer NOT NULL,
    k character varying(255) NOT NULL,
    v character varying(255),
    "atonNode_id" integer NOT NULL
);

--
-- Name: AtonTag_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."AtonTag_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Attachment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Attachment" (
    id integer NOT NULL,
    display character varying(255),
    "fileName" character varying(255),
    "fileSize" bigint NOT NULL,
    height character varying(255),
    "indexNo" integer NOT NULL,
    path character varying(255),
    type character varying(255),
    width character varying(255),
    message_id integer NOT NULL,
    CONSTRAINT "Attachment_display_check" CHECK (((display)::text = ANY ((ARRAY['ABOVE'::character varying, 'BELOW'::character varying, 'SEPARATE_PAGE'::character varying])::text[])))
);

--
-- Name: AttachmentDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."AttachmentDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    caption character varying(1000),
    entity_id integer NOT NULL
);

--
-- Name: AttachmentDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."AttachmentDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Attachment_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Attachment_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: AudioMessagePromulgation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."AudioMessagePromulgation" (
    text text,
    id integer NOT NULL
);

--
-- Name: BaseMessagePromulgation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."BaseMessagePromulgation" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    promulgate boolean NOT NULL,
    message_id integer NOT NULL,
    type_id integer NOT NULL
);

--
-- Name: BaseMessagePromulgation_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."BaseMessagePromulgation_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: BatchData; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."BatchData" (
    id integer NOT NULL,
    created timestamp(6) without time zone NOT NULL,
    "dataFileName" character varying(255),
    "instanceId" bigint NOT NULL,
    "jobName" character varying(255) NOT NULL,
    "jobNo" bigint NOT NULL,
    progress integer,
    properties text,
    domain_id integer,
    user_id integer
);

--
-- Name: BatchData_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."BatchData_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Category" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    active boolean NOT NULL,
    lineage character varying(256),
    "siblingSortOrder" double precision DEFAULT 0.0,
    "treeSortOrder" integer DEFAULT 0,
    "atonFilter" character varying(255),
    "legacyId" character varying(255),
    "messageId" character varying(255),
    mrn character varying(255),
    type character varying(255),
    parent_id integer,
    CONSTRAINT "Category_type_check" CHECK (((type)::text = ANY ((ARRAY['CATEGORY'::character varying, 'TEMPLATE'::character varying])::text[])))
);

--
-- Name: CategoryDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."CategoryDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    entity_id integer NOT NULL
);

--
-- Name: CategoryDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."CategoryDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Category_Domain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Category_Domain" (
    "Category_id" integer NOT NULL,
    domains_id integer NOT NULL
);

--
-- Name: Category_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Category_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Category_TemplateParam; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Category_TemplateParam" (
    "Category_id" integer NOT NULL,
    "templateParams_id" integer NOT NULL,
    "indexNo" integer NOT NULL
);

--
-- Name: Category_editorFields; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Category_editorFields" (
    "Category_id" integer NOT NULL,
    "editorFields" character varying(255)
);

--
-- Name: Category_scriptResourcePaths; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Category_scriptResourcePaths" (
    "Category_id" integer NOT NULL,
    "scriptResourcePaths" character varying(255),
    "indexNo" integer NOT NULL
);

--
-- Name: Category_stdTemplateFields; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Category_stdTemplateFields" (
    "Category_id" integer NOT NULL,
    "stdTemplateFields" character varying(255)
);

--
-- Name: Chart; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Chart" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    active boolean NOT NULL,
    "chartNumber" character varying(255) NOT NULL,
    geometry public.geometry,
    "horizontalDatum" character varying(255),
    "internationalNumber" integer,
    "legacyId" character varying(255),
    name character varying(255),
    scale integer
);

--
-- Name: Chart_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Chart_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Comment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Comment" (
    id integer NOT NULL,
    "acknowledgeDate" timestamp(6) without time zone,
    comment text,
    created timestamp(6) without time zone NOT NULL,
    "acknowledgedBy_id" integer,
    message_id integer NOT NULL,
    user_id integer NOT NULL
);

--
-- Name: Comment_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Comment_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Comment_emailAddresses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Comment_emailAddresses" (
    "Comment_id" integer NOT NULL,
    "emailAddresses" character varying(255)
);

--
-- Name: Contact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Contact" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    email character varying(255) NOT NULL,
    language character varying(255),
    name character varying(255)
);

--
-- Name: Contact_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Contact_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: DateInterval; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DateInterval" (
    id integer NOT NULL,
    "allDay" boolean,
    "fromDate" timestamp(6) without time zone,
    "indexNo" integer NOT NULL,
    "toDate" timestamp(6) without time zone,
    "messagePart_id" integer NOT NULL
);

--
-- Name: DateInterval_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."DateInterval_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Dictionary; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Dictionary" (
    id integer NOT NULL,
    name character varying(255) NOT NULL
);

--
-- Name: DictionaryEntry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DictionaryEntry" (
    id integer NOT NULL,
    "atonFilter" character varying(255),
    dict_key character varying(255) NOT NULL,
    dictionary_id integer NOT NULL
);

--
-- Name: DictionaryEntryDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DictionaryEntryDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    "longValue" character varying(1000),
    value character varying(1000) NOT NULL,
    entity_id integer NOT NULL
);

--
-- Name: DictionaryEntryDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."DictionaryEntryDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: DictionaryEntry_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."DictionaryEntry_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Dictionary_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Dictionary_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Domain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Domain" (
    id integer NOT NULL,
    active boolean NOT NULL,
    atons boolean,
    color character varying(255),
    "domainId" character varying(255) NOT NULL,
    latitude real,
    longitude real,
    "messageSortOrder" character varying(255),
    name character varying(255) NOT NULL,
    publish boolean,
    "sortOrder" integer,
    templates boolean,
    "timeZone" character varying(255),
    "zoomLevel" integer,
    "firingSchedule_id" integer
);

--
-- Name: Domain_Area; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Domain_Area" (
    "Domain_id" integer NOT NULL,
    areas_id integer NOT NULL
);

--
-- Name: Domain_Category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Domain_Category" (
    "Domain_id" integer NOT NULL,
    categories_id integer NOT NULL
);

--
-- Name: Domain_MessageSeries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Domain_MessageSeries" (
    "Domain_id" integer NOT NULL,
    "messageSeries_id" integer NOT NULL
);

--
-- Name: Domain_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Domain_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Feature; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Feature" (
    id integer NOT NULL,
    geometry public.geometry NOT NULL,
    "indexNo" integer DEFAULT 0 NOT NULL,
    properties text,
    uid character varying(255),
    "featureCollection_id" integer NOT NULL
);

--
-- Name: FeatureCollection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."FeatureCollection" (
    id integer NOT NULL,
    uid character varying(255)
);

--
-- Name: FeatureCollection_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."FeatureCollection_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Feature_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Feature_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: FiringPeriod; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."FiringPeriod" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    "fromDate" timestamp(6) without time zone NOT NULL,
    "legacyId" character varying(255),
    "toDate" timestamp(6) without time zone NOT NULL,
    area_id integer NOT NULL
);

--
-- Name: FiringPeriod_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."FiringPeriod_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: FiringSchedule; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."FiringSchedule" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    active boolean NOT NULL,
    "scheduleDays" integer,
    domain_id integer NOT NULL,
    "targetDomain_id" integer NOT NULL,
    "targetMessageSeries_id" integer NOT NULL
);

--
-- Name: FiringSchedule_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."FiringSchedule_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: FmReport; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."FmReport" (
    id integer NOT NULL,
    name character varying(255) NOT NULL,
    params text,
    properties text,
    "reportId" character varying(255) NOT NULL,
    "sortOrder" integer,
    "templatePath" character varying(255) NOT NULL
);

--
-- Name: FmReport_Domain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."FmReport_Domain" (
    "FmReport_id" integer NOT NULL,
    domains_id integer NOT NULL
);

--
-- Name: FmReport_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."FmReport_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MailingList; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MailingList" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    active boolean NOT NULL,
    "mailingListId" character varying(255) NOT NULL
);

--
-- Name: MailingListDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MailingListDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    description character varying(255),
    name character varying(255) NOT NULL,
    entity_id integer NOT NULL
);

--
-- Name: MailingListDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MailingListDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MailingListTrigger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MailingListTrigger" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    "messageFilter" character varying(255),
    "messageQuery" character varying(255),
    "nextScheduledExecution" timestamp(6) without time zone,
    "publicReport" boolean,
    "scheduleType" character varying(255),
    "scheduledExecutionTime" character varying(255),
    "scheduledExecutionTimeZone" character varying(255),
    type character varying(255) NOT NULL,
    "mailingList_id" integer NOT NULL,
    CONSTRAINT "MailingListTrigger_scheduleType_check" CHECK ((("scheduleType")::text = ANY ((ARRAY['DAILY'::character varying, 'MONDAY'::character varying, 'TUESDAY'::character varying, 'WEDNESDAY'::character varying, 'THURSDAY'::character varying, 'FRIDAY'::character varying, 'SATURDAY'::character varying, 'SUNDAY'::character varying])::text[]))),
    CONSTRAINT "MailingListTrigger_type_check" CHECK (((type)::text = ANY ((ARRAY['STATUS_CHANGE'::character varying, 'SCHEDULED'::character varying])::text[])))
);

--
-- Name: MailingListTriggerDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MailingListTriggerDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    subject character varying(255) NOT NULL,
    entity_id integer NOT NULL
);

--
-- Name: MailingListTriggerDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MailingListTriggerDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MailingListTrigger_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MailingListTrigger_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MailingListTrigger_scriptResourcePaths; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MailingListTrigger_scriptResourcePaths" (
    "MailingListTrigger_id" integer CONSTRAINT "MailingListTrigger_scriptResourc_MailingListTrigger_id_not_null" NOT NULL,
    "scriptResourcePaths" character varying(255),
    "indexNo" integer NOT NULL
);

--
-- Name: MailingListTrigger_statusChanges; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MailingListTrigger_statusChanges" (
    "MailingListTrigger_id" integer NOT NULL,
    "statusChanges" character varying(255),
    CONSTRAINT "MailingListTrigger_statusChanges_statusChanges_check" CHECK ((("statusChanges")::text = ANY ((ARRAY['DRAFT'::character varying, 'VERIFIED'::character varying, 'PUBLISHED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying, 'DELETED'::character varying])::text[])))
);

--
-- Name: MailingList_Contact; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MailingList_Contact" (
    "MailingList_id" integer NOT NULL,
    contacts_id integer NOT NULL
);

--
-- Name: MailingList_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MailingList_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MailingList_User; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MailingList_User" (
    "MailingList_id" integer NOT NULL,
    users_id integer NOT NULL
);

--
-- Name: Message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Message" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    "areaSortOrder" double precision DEFAULT 0.0,
    "autoTitle" boolean NOT NULL,
    "eventDateFrom" timestamp(6) without time zone,
    "eventDateTo" timestamp(6) without time zone,
    "followUpDate" timestamp(6) without time zone,
    "hasGeometry" boolean NOT NULL,
    "horizontalDatum" character varying(255),
    "legacyId" character varying(255),
    "mainType" character varying(255) NOT NULL,
    number integer,
    "originalInformation" boolean,
    "publishDateFrom" timestamp(6) without time zone,
    "publishDateTo" timestamp(6) without time zone,
    "repoPath" character varying(128) NOT NULL,
    revision integer NOT NULL,
    "separatePage" boolean,
    "shortId" character varying(255),
    status character varying(255) NOT NULL,
    "thumbnailPath" character varying(255),
    type character varying(255) NOT NULL,
    uid character varying(36) NOT NULL,
    year integer,
    area_id integer,
    "createdBy_id" integer,
    "lastUpdatedBy_id" integer,
    "messageSeries_id" integer,
    CONSTRAINT "Message_mainType_check" CHECK ((("mainType")::text = ANY ((ARRAY['NW'::character varying, 'NM'::character varying])::text[]))),
    CONSTRAINT "Message_status_check" CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'VERIFIED'::character varying, 'PUBLISHED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying, 'DELETED'::character varying])::text[]))),
    CONSTRAINT "Message_type_check" CHECK (((type)::text = ANY ((ARRAY['PERMANENT_NOTICE'::character varying, 'TEMPORARY_NOTICE'::character varying, 'PRELIMINARY_NOTICE'::character varying, 'MISCELLANEOUS_NOTICE'::character varying, 'COASTAL_WARNING'::character varying, 'SUBAREA_WARNING'::character varying, 'NAVAREA_WARNING'::character varying, 'LOCAL_WARNING'::character varying])::text[])))
);

--
-- Name: MessageDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MessageDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    "internalPublication" character varying(2048),
    publication character varying(2048),
    source character varying(1024),
    title character varying(1024),
    vicinity character varying(255),
    entity_id integer NOT NULL
);

--
-- Name: MessageDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MessageDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MessageFilter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MessageFilter" (
    id integer NOT NULL,
    name character varying(255) NOT NULL,
    parameters character varying(255),
    user_id integer
);

--
-- Name: MessageFilter_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MessageFilter_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MessageHistory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MessageHistory" (
    id integer NOT NULL,
    created timestamp(6) without time zone NOT NULL,
    snapshot bytea,
    status character varying(255) NOT NULL,
    version integer NOT NULL,
    message_id integer NOT NULL,
    user_id integer,
    CONSTRAINT "MessageHistory_status_check" CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'VERIFIED'::character varying, 'PUBLISHED'::character varying, 'EXPIRED'::character varying, 'CANCELLED'::character varying, 'DELETED'::character varying])::text[])))
);

--
-- Name: MessageHistory_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MessageHistory_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MessagePart; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MessagePart" (
    id integer NOT NULL,
    "hideSubject" boolean,
    "indexNo" integer NOT NULL,
    type character varying(255) NOT NULL,
    geometry_id integer,
    message_id integer NOT NULL,
    CONSTRAINT "MessagePart_type_check" CHECK (((type)::text = ANY ((ARRAY['DETAILS'::character varying, 'TIME'::character varying, 'POSITIONS'::character varying, 'NOTE'::character varying, 'PROHIBITION'::character varying, 'SIGNALS'::character varying])::text[])))
);

--
-- Name: MessagePartDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MessagePartDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    details text,
    subject character varying(500),
    entity_id integer NOT NULL
);

--
-- Name: MessagePartDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MessagePartDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MessagePart_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MessagePart_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MessageSeries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MessageSeries" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    "excludeFromMessageIndex" boolean,
    "mainType" character varying(255) NOT NULL,
    "navtexFormat" character varying(255),
    "numberSequenceType" smallint NOT NULL,
    "seriesId" character varying(255) NOT NULL,
    "shortFormat" character varying(255),
    CONSTRAINT "MessageSeries_mainType_check" CHECK ((("mainType")::text = ANY ((ARRAY['NW'::character varying, 'NM'::character varying])::text[]))),
    CONSTRAINT "MessageSeries_numberSequenceType_check" CHECK ((("numberSequenceType" >= 0) AND ("numberSequenceType" <= 3)))
);

--
-- Name: MessageSeriesMapping; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MessageSeriesMapping" (
    id integer NOT NULL,
    "sourceSeriesId" character varying(255) NOT NULL,
    "targetSeriesId" character varying(255) NOT NULL,
    "niordIntegration_id" integer NOT NULL
);

--
-- Name: MessageSeriesMapping_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MessageSeriesMapping_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MessageSeries_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MessageSeries_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: MessageSeries_editorFields; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MessageSeries_editorFields" (
    "MessageSeries_id" integer NOT NULL,
    "editorFields" character varying(255)
);

--
-- Name: MessageSeries_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MessageSeries_types" (
    "MessageSeries_id" integer NOT NULL,
    types character varying(255),
    CONSTRAINT "MessageSeries_types_types_check" CHECK (((types)::text = ANY ((ARRAY['PERMANENT_NOTICE'::character varying, 'TEMPORARY_NOTICE'::character varying, 'PRELIMINARY_NOTICE'::character varying, 'MISCELLANEOUS_NOTICE'::character varying, 'COASTAL_WARNING'::character varying, 'SUBAREA_WARNING'::character varying, 'NAVAREA_WARNING'::character varying, 'LOCAL_WARNING'::character varying])::text[])))
);

--
-- Name: MessageTag; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MessageTag" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    "expiryDate" timestamp(6) without time zone,
    locked boolean NOT NULL,
    "messageCount" integer DEFAULT 0,
    name character varying(255) NOT NULL,
    "tagId" character varying(255) NOT NULL,
    type character varying(255) NOT NULL,
    domain_id integer,
    user_id integer,
    CONSTRAINT "MessageTag_type_check" CHECK (((type)::text = ANY ((ARRAY['PRIVATE'::character varying, 'DOMAIN'::character varying, 'PUBLIC'::character varying, 'TEMP'::character varying])::text[])))
);

--
-- Name: MessageTag_Message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MessageTag_Message" (
    tags_id integer NOT NULL,
    messages_id integer NOT NULL
);

--
-- Name: MessageTag_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."MessageTag_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Message_Area; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Message_Area" (
    "Message_id" integer NOT NULL,
    areas_id integer NOT NULL,
    "areas_ORDER" integer NOT NULL
);

--
-- Name: Message_Category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Message_Category" (
    "Message_id" integer NOT NULL,
    categories_id integer NOT NULL
);

--
-- Name: Message_Chart; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Message_Chart" (
    "Message_id" integer NOT NULL,
    charts_id integer NOT NULL,
    "charts_ORDER" integer NOT NULL
);

--
-- Name: Message_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Message_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: NavtexMessagePromulgation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."NavtexMessagePromulgation" (
    preamble character varying(255),
    priority character varying(255) NOT NULL,
    text text,
    id integer NOT NULL,
    CONSTRAINT "NavtexMessagePromulgation_priority_check" CHECK (((priority)::text = ANY ((ARRAY['NONE'::character varying, 'ROUTINE'::character varying, 'IMPORTANT'::character varying, 'VITAL'::character varying])::text[])))
);

--
-- Name: NavtexMessagePromulgation_NavtexTransmitter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."NavtexMessagePromulgation_NavtexTransmitter" (
    "NavtexMessagePromulgation_id" integer CONSTRAINT "NavtexMessagePromulgation_N_NavtexMessagePromulgation__not_null" NOT NULL,
    transmitters_id integer CONSTRAINT "NavtexMessagePromulgation_NavtexTransm_transmitters_id_not_null" NOT NULL
);

--
-- Name: NavtexTransmitter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."NavtexTransmitter" (
    id integer NOT NULL,
    active boolean NOT NULL,
    name character varying(255) NOT NULL,
    "promulgationType_id" integer NOT NULL
);

--
-- Name: NavtexTransmitter_Area; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."NavtexTransmitter_Area" (
    "NavtexTransmitter_id" integer NOT NULL,
    areas_id integer NOT NULL,
    "areas_ORDER" integer NOT NULL
);

--
-- Name: NavtexTransmitter_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."NavtexTransmitter_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: NiordIntegration; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."NiordIntegration" (
    id integer NOT NULL,
    active boolean NOT NULL,
    "assignNewUids" boolean NOT NULL,
    "createBaseData" boolean NOT NULL,
    "nextScheduledExecution" timestamp(6) without time zone,
    url character varying(255) NOT NULL
);

--
-- Name: NiordIntegration_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."NiordIntegration_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: ParamType; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ParamType" (
    "DTYPE" character varying(31) NOT NULL,
    id integer NOT NULL,
    name character varying(255),
    CONSTRAINT "ParamType_DTYPE_check" CHECK ((("DTYPE")::text = ANY ((ARRAY['CompositeParamType'::character varying, 'ListParamType'::character varying, 'StandardParamType'::character varying])::text[])))
);

--
-- Name: ParamType_DictionaryEntry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ParamType_DictionaryEntry" (
    "ListParamType_id" integer NOT NULL,
    values_id integer NOT NULL,
    "indexNo" integer NOT NULL
);

--
-- Name: ParamType_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."ParamType_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: ParamType_TemplateParam; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ParamType_TemplateParam" (
    "CompositeParamType_id" integer NOT NULL,
    "templateParams_id" integer NOT NULL,
    "indexNo" integer NOT NULL
);

--
-- Name: PromulgationType; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PromulgationType" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    active boolean NOT NULL,
    language character varying(255),
    name character varying(255) NOT NULL,
    priority integer NOT NULL,
    requirement character varying(255),
    "serviceId" character varying(255) NOT NULL,
    "typeId" character varying(255) NOT NULL,
    CONSTRAINT "PromulgationType_requirement_check" CHECK (((requirement)::text = ANY ((ARRAY['OPTIONAL'::character varying, 'DEFAULT'::character varying, 'MANDATORY'::character varying])::text[])))
);

--
-- Name: PromulgationType_Domain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PromulgationType_Domain" (
    "PromulgationType_id" integer NOT NULL,
    domains_id integer NOT NULL
);

--
-- Name: PromulgationType_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PromulgationType_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: PromulgationType_messageTypes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PromulgationType_messageTypes" (
    "PromulgationType_id" integer NOT NULL,
    "messageTypes" character varying(255),
    CONSTRAINT "PromulgationType_messageTypes_messageTypes_check" CHECK ((("messageTypes")::text = ANY ((ARRAY['PERMANENT_NOTICE'::character varying, 'TEMPORARY_NOTICE'::character varying, 'PRELIMINARY_NOTICE'::character varying, 'MISCELLANEOUS_NOTICE'::character varying, 'COASTAL_WARNING'::character varying, 'SUBAREA_WARNING'::character varying, 'NAVAREA_WARNING'::character varying, 'LOCAL_WARNING'::character varying])::text[])))
);

--
-- Name: PromulgationType_scriptResourcePaths; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PromulgationType_scriptResourcePaths" (
    "PromulgationType_id" integer CONSTRAINT "PromulgationType_scriptResourcePat_PromulgationType_id_not_null" NOT NULL,
    "scriptResourcePaths" character varying(255),
    "indexNo" integer NOT NULL
);

--
-- Name: Publication; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Publication" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    edition integer,
    "languageSpecific" boolean NOT NULL,
    "mainType" character varying(255) NOT NULL,
    "messagePublication" character varying(255),
    "messageTagFilter" character varying(255),
    "messageTagFormat" character varying(255),
    "periodicalType" character varying(255),
    "printSettings" text,
    "publicationId" character varying(36) NOT NULL,
    "publishDateFrom" timestamp(6) without time zone,
    "publishDateTo" timestamp(6) without time zone,
    "repoPath" character varying(255),
    "reportParams" text,
    revision integer NOT NULL,
    status character varying(255) NOT NULL,
    type character varying(255) NOT NULL,
    category_id integer NOT NULL,
    domain_id integer,
    "messageTag_id" integer,
    template_id integer,
    CONSTRAINT "Publication_mainType_check" CHECK ((("mainType")::text = ANY ((ARRAY['PUBLICATION'::character varying, 'TEMPLATE'::character varying])::text[]))),
    CONSTRAINT "Publication_messagePublication_check" CHECK ((("messagePublication")::text = ANY ((ARRAY['NONE'::character varying, 'INTERNAL'::character varying, 'EXTERNAL'::character varying])::text[]))),
    CONSTRAINT "Publication_periodicalType_check" CHECK ((("periodicalType")::text = ANY ((ARRAY['DAILY'::character varying, 'WEEKLY'::character varying, 'MONTHLY'::character varying, 'YEARLY'::character varying])::text[]))),
    CONSTRAINT "Publication_status_check" CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'RECORDING'::character varying, 'ACTIVE'::character varying, 'INACTIVE'::character varying])::text[]))),
    CONSTRAINT "Publication_type_check" CHECK (((type)::text = ANY ((ARRAY['NONE'::character varying, 'LINK'::character varying, 'REPOSITORY'::character varying, 'MESSAGE_REPORT'::character varying])::text[])))
);

--
-- Name: PublicationCategory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PublicationCategory" (
    id integer NOT NULL,
    "categoryId" character varying(255) NOT NULL,
    priority integer NOT NULL,
    publish boolean NOT NULL
);

--
-- Name: PublicationCategoryDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PublicationCategoryDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    description character varying(255),
    name character varying(255) NOT NULL,
    entity_id integer NOT NULL
);

--
-- Name: PublicationCategoryDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PublicationCategoryDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: PublicationCategory_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PublicationCategory_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: PublicationDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PublicationDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    "fileName" character varying(255),
    link character varying(255),
    "messagePublicationFormat" character varying(255),
    title character varying(255) NOT NULL,
    "titleFormat" character varying(255),
    entity_id integer NOT NULL
);

--
-- Name: PublicationDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PublicationDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Publication_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Publication_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Reference; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Reference" (
    id integer NOT NULL,
    "indexNo" integer NOT NULL,
    "messageId" character varying(255) NOT NULL,
    type smallint NOT NULL,
    message_id integer NOT NULL,
    CONSTRAINT "Reference_type_check" CHECK (((type >= 0) AND (type <= 4)))
);

--
-- Name: ReferenceDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ReferenceDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    description character varying(512),
    entity_id integer NOT NULL
);

--
-- Name: ReferenceDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."ReferenceDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Reference_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Reference_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: SafetyNetArea; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."SafetyNetArea" (
    "DTYPE" character varying(31) NOT NULL,
    id integer NOT NULL,
    active boolean NOT NULL,
    "messageAreaPrefix" character varying(255),
    name character varying(255) NOT NULL,
    repetition smallint,
    "sortOrder" integer NOT NULL,
    center character varying(255),
    radius character varying(255),
    navarea character varying(255),
    "coastalArea" character varying(255),
    eastings character varying(255),
    northings character varying(255),
    "swCorner" character varying(255),
    "promulgationType_id" integer NOT NULL,
    CONSTRAINT "SafetyNetArea_DTYPE_check" CHECK ((("DTYPE")::text = ANY ((ARRAY['SafetyNetCoastalArea'::character varying, 'SafetyNetCircularArea'::character varying, 'SafetyNetNavareaArea'::character varying, 'SafetyNetRectangularArea'::character varying])::text[]))),
    CONSTRAINT "SafetyNetArea_coastalArea_check" CHECK ((("coastalArea")::text = ANY ((ARRAY['A'::character varying, 'B'::character varying, 'C'::character varying, 'D'::character varying, 'E'::character varying, 'F'::character varying, 'G'::character varying, 'H'::character varying, 'I'::character varying, 'J'::character varying, 'K'::character varying, 'L'::character varying, 'M'::character varying, 'N'::character varying, 'O'::character varying, 'P'::character varying, 'Q'::character varying, 'R'::character varying, 'S'::character varying, 'T'::character varying, 'U'::character varying, 'V'::character varying, 'W'::character varying, 'X'::character varying, 'Y'::character varying, 'Z'::character varying])::text[]))),
    CONSTRAINT "SafetyNetArea_navarea_check" CHECK (((navarea)::text = ANY ((ARRAY['I'::character varying, 'II'::character varying, 'III'::character varying, 'IV'::character varying, 'V'::character varying, 'VI'::character varying, 'VII'::character varying, 'VIII'::character varying, 'IX'::character varying, 'X'::character varying, 'XI'::character varying, 'XII'::character varying, 'XIII'::character varying, 'XIV'::character varying, 'XV'::character varying, 'XVI'::character varying, 'XVII'::character varying, 'XVIII'::character varying, 'XIX'::character varying, 'XX'::character varying, 'XXI'::character varying])::text[]))),
    CONSTRAINT "SafetyNetArea_repetition_check" CHECK (((repetition >= 0) AND (repetition <= 3)))
);

--
-- Name: SafetyNetArea_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."SafetyNetArea_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: SafetyNetMessagePromulgation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."SafetyNetMessagePromulgation" (
    priority character varying(255) NOT NULL,
    "safetyNetId" character varying(255),
    text text,
    id integer NOT NULL,
    area_id integer NOT NULL,
    CONSTRAINT "SafetyNetMessagePromulgation_priority_check" CHECK (((priority)::text = ANY ((ARRAY['SAFETY'::character varying, 'URGENCY'::character varying, 'DISTRESS'::character varying])::text[])))
);

--
-- Name: ScheduledMail; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ScheduledMail" (
    id integer NOT NULL,
    attempts integer NOT NULL,
    contents bytea NOT NULL,
    created timestamp(6) without time zone,
    "lastError" character varying(255),
    "sendDate" timestamp(6) without time zone,
    sender character varying(255),
    status character varying(255) NOT NULL,
    subject character varying(255) NOT NULL,
    CONSTRAINT "ScheduledMail_status_check" CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SENT'::character varying, 'ERROR'::character varying])::text[])))
);

--
-- Name: ScheduledMailRecipient; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ScheduledMailRecipient" (
    id integer NOT NULL,
    address character varying(255) NOT NULL,
    "recipientType" character varying(255) NOT NULL,
    mail_id integer NOT NULL,
    CONSTRAINT "ScheduledMailRecipient_recipientType_check" CHECK ((("recipientType")::text = ANY ((ARRAY['TO'::character varying, 'CC'::character varying, 'BCC'::character varying])::text[])))
);

--
-- Name: ScheduledMailRecipient_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."ScheduledMailRecipient_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: ScheduledMail_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."ScheduledMail_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: ScriptResource; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ScriptResource" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    content text,
    path character varying(255) NOT NULL,
    type character varying(255) NOT NULL,
    CONSTRAINT "ScriptResource_type_check" CHECK (((type)::text = ANY ((ARRAY['FM'::character varying, 'JS'::character varying])::text[])))
);

--
-- Name: ScriptResourceHistory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ScriptResourceHistory" (
    id integer NOT NULL,
    created timestamp(6) without time zone NOT NULL,
    snapshot bytea,
    version integer NOT NULL,
    resource_id integer NOT NULL,
    user_id integer
);

--
-- Name: ScriptResourceHistory_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."ScriptResourceHistory_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: ScriptResource_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."ScriptResource_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Sequence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Sequence" (
    name character varying(255) NOT NULL,
    "nextValue" bigint NOT NULL
);

--
-- Name: Setting; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Setting" (
    setting_key character varying(255) NOT NULL,
    cached boolean NOT NULL,
    description character varying(255),
    editable boolean NOT NULL,
    type character varying(255),
    setting_value character varying(2000),
    web boolean NOT NULL,
    CONSTRAINT "Setting_type_check" CHECK (((type)::text = ANY ((ARRAY['String'::character varying, 'Password'::character varying, 'Integer'::character varying, 'Long'::character varying, 'Float'::character varying, 'Double'::character varying, 'Date'::character varying, 'Boolean'::character varying, 'Path'::character varying, 'json'::character varying])::text[])))
);

--
-- Name: Source; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."Source" (
    id integer NOT NULL,
    active boolean NOT NULL
);

--
-- Name: SourceDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."SourceDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    abbreviation character varying(255),
    name character varying(255) NOT NULL,
    entity_id integer NOT NULL
);

--
-- Name: SourceDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."SourceDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: Source_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."Source_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: TemplateParam; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."TemplateParam" (
    id integer NOT NULL,
    "indexNo" integer NOT NULL,
    mandatory boolean NOT NULL,
    "paramId" character varying(255) NOT NULL,
    "positionList" boolean NOT NULL,
    type character varying(255) NOT NULL
);

--
-- Name: TemplateParamDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."TemplateParamDesc" (
    id integer NOT NULL,
    lang character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    entity_id integer NOT NULL
);

--
-- Name: TemplateParamDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."TemplateParamDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: TemplateParam_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."TemplateParam_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: TwitterMessagePromulgation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."TwitterMessagePromulgation" (
    tweet character varying(512),
    id integer NOT NULL
);

--
-- Name: TwitterSettings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."TwitterSettings" (
    id integer NOT NULL,
    "accessToken" character varying(255),
    "accessTokenSecret" character varying(255),
    "apiKey" character varying(255),
    "apiSecret" character varying(255),
    format character varying(255),
    "includeThumbnail" boolean,
    "promulgationType_id" integer NOT NULL
);

--
-- Name: TwitterSettings_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."TwitterSettings_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: User; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."User" (
    id integer NOT NULL,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    version integer,
    email character varying(255),
    "firstName" character varying(255),
    language character varying(255),
    "lastName" character varying(255),
    username character varying(255) NOT NULL
);

--
-- Name: User_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."User_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Data for Name: Area; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: AreaDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Area_editorFields; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: AtonNode; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: AtonTag; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Attachment; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: AttachmentDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: AudioMessagePromulgation; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: BaseMessagePromulgation; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: BatchData; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Category; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: CategoryDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Category_Domain; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Category_TemplateParam; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Category_editorFields; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Category_scriptResourcePaths; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Category_stdTemplateFields; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Chart; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Comment; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Comment_emailAddresses; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Contact; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: DateInterval; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Dictionary; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: DictionaryEntry; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: DictionaryEntryDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Domain; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Domain_Area; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Domain_Category; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Domain_MessageSeries; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Feature; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: FeatureCollection; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: FiringPeriod; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: FiringSchedule; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: FmReport; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: FmReport_Domain; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MailingList; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MailingListDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MailingListTrigger; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MailingListTriggerDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MailingListTrigger_scriptResourcePaths; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MailingListTrigger_statusChanges; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MailingList_Contact; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MailingList_User; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Message; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MessageDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MessageFilter; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MessageHistory; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MessagePart; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MessagePartDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MessageSeries; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MessageSeriesMapping; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MessageSeries_editorFields; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MessageSeries_types; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MessageTag; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: MessageTag_Message; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Message_Area; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Message_Category; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Message_Chart; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: NavtexMessagePromulgation; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: NavtexMessagePromulgation_NavtexTransmitter; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: NavtexTransmitter; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: NavtexTransmitter_Area; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: NiordIntegration; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: ParamType; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: ParamType_DictionaryEntry; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: ParamType_TemplateParam; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: PromulgationType; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: PromulgationType_Domain; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: PromulgationType_messageTypes; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: PromulgationType_scriptResourcePaths; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Publication; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: PublicationCategory; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: PublicationCategoryDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: PublicationDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Reference; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: ReferenceDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: SafetyNetArea; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: SafetyNetMessagePromulgation; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: ScheduledMail; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: ScheduledMailRecipient; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: ScriptResource; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: ScriptResourceHistory; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Sequence; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Setting; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: Source; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: SourceDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: TemplateParam; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: TemplateParamDesc; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: TwitterMessagePromulgation; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: TwitterSettings; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Data for Name: User; Type: TABLE DATA; Schema: public; Owner: -
--

--
-- Name: AreaDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Area_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: AtonNode_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: AtonTag_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: AttachmentDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Attachment_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: BaseMessagePromulgation_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: BatchData_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: CategoryDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Category_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Chart_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Comment_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Contact_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: DateInterval_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: DictionaryEntryDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: DictionaryEntry_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Dictionary_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Domain_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: FeatureCollection_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Feature_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: FiringPeriod_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: FiringSchedule_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: FmReport_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MailingListDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MailingListTriggerDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MailingListTrigger_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MailingList_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MessageDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MessageFilter_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MessageHistory_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MessagePartDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MessagePart_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MessageSeriesMapping_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MessageSeries_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: MessageTag_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Message_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: NavtexTransmitter_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: NiordIntegration_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: ParamType_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: PromulgationType_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: PublicationCategoryDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: PublicationCategory_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: PublicationDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Publication_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: ReferenceDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Reference_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: SafetyNetArea_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: ScheduledMailRecipient_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: ScheduledMail_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: ScriptResourceHistory_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: ScriptResource_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: SourceDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: Source_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: TemplateParamDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: TemplateParam_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: TwitterSettings_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: User_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

--
-- Name: AreaDesc AreaDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AreaDesc"
    ADD CONSTRAINT "AreaDesc_pkey" PRIMARY KEY (id);

--
-- Name: Area Area_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Area"
    ADD CONSTRAINT "Area_pkey" PRIMARY KEY (id);

--
-- Name: AtonNode AtonNode_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AtonNode"
    ADD CONSTRAINT "AtonNode_pkey" PRIMARY KEY (id);

--
-- Name: AtonTag AtonTag_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AtonTag"
    ADD CONSTRAINT "AtonTag_pkey" PRIMARY KEY (id);

--
-- Name: AttachmentDesc AttachmentDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AttachmentDesc"
    ADD CONSTRAINT "AttachmentDesc_pkey" PRIMARY KEY (id);

--
-- Name: Attachment Attachment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Attachment"
    ADD CONSTRAINT "Attachment_pkey" PRIMARY KEY (id);

--
-- Name: AudioMessagePromulgation AudioMessagePromulgation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AudioMessagePromulgation"
    ADD CONSTRAINT "AudioMessagePromulgation_pkey" PRIMARY KEY (id);

--
-- Name: BaseMessagePromulgation BaseMessagePromulgation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BaseMessagePromulgation"
    ADD CONSTRAINT "BaseMessagePromulgation_pkey" PRIMARY KEY (id);

--
-- Name: BatchData BatchData_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BatchData"
    ADD CONSTRAINT "BatchData_pkey" PRIMARY KEY (id);

--
-- Name: CategoryDesc CategoryDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."CategoryDesc"
    ADD CONSTRAINT "CategoryDesc_pkey" PRIMARY KEY (id);

--
-- Name: Category_TemplateParam Category_TemplateParam_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category_TemplateParam"
    ADD CONSTRAINT "Category_TemplateParam_pkey" PRIMARY KEY ("Category_id", "indexNo");

--
-- Name: Category Category_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category"
    ADD CONSTRAINT "Category_pkey" PRIMARY KEY (id);

--
-- Name: Category_scriptResourcePaths Category_scriptResourcePaths_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category_scriptResourcePaths"
    ADD CONSTRAINT "Category_scriptResourcePaths_pkey" PRIMARY KEY ("Category_id", "indexNo");

--
-- Name: Chart Chart_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Chart"
    ADD CONSTRAINT "Chart_pkey" PRIMARY KEY (id);

--
-- Name: Comment Comment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Comment"
    ADD CONSTRAINT "Comment_pkey" PRIMARY KEY (id);

--
-- Name: Contact Contact_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT "Contact_pkey" PRIMARY KEY (id);

--
-- Name: DateInterval DateInterval_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DateInterval"
    ADD CONSTRAINT "DateInterval_pkey" PRIMARY KEY (id);

--
-- Name: DictionaryEntryDesc DictionaryEntryDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DictionaryEntryDesc"
    ADD CONSTRAINT "DictionaryEntryDesc_pkey" PRIMARY KEY (id);

--
-- Name: DictionaryEntry DictionaryEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DictionaryEntry"
    ADD CONSTRAINT "DictionaryEntry_pkey" PRIMARY KEY (id);

--
-- Name: Dictionary Dictionary_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Dictionary"
    ADD CONSTRAINT "Dictionary_pkey" PRIMARY KEY (id);

--
-- Name: Domain Domain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT "Domain_pkey" PRIMARY KEY (id);

--
-- Name: FeatureCollection FeatureCollection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FeatureCollection"
    ADD CONSTRAINT "FeatureCollection_pkey" PRIMARY KEY (id);

--
-- Name: Feature Feature_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Feature"
    ADD CONSTRAINT "Feature_pkey" PRIMARY KEY (id);

--
-- Name: FiringPeriod FiringPeriod_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FiringPeriod"
    ADD CONSTRAINT "FiringPeriod_pkey" PRIMARY KEY (id);

--
-- Name: FiringSchedule FiringSchedule_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FiringSchedule"
    ADD CONSTRAINT "FiringSchedule_pkey" PRIMARY KEY (id);

--
-- Name: FmReport FmReport_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FmReport"
    ADD CONSTRAINT "FmReport_pkey" PRIMARY KEY (id);

--
-- Name: MailingListDesc MailingListDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListDesc"
    ADD CONSTRAINT "MailingListDesc_pkey" PRIMARY KEY (id);

--
-- Name: MailingListTriggerDesc MailingListTriggerDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListTriggerDesc"
    ADD CONSTRAINT "MailingListTriggerDesc_pkey" PRIMARY KEY (id);

--
-- Name: MailingListTrigger MailingListTrigger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListTrigger"
    ADD CONSTRAINT "MailingListTrigger_pkey" PRIMARY KEY (id);

--
-- Name: MailingListTrigger_scriptResourcePaths MailingListTrigger_scriptResourcePaths_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListTrigger_scriptResourcePaths"
    ADD CONSTRAINT "MailingListTrigger_scriptResourcePaths_pkey" PRIMARY KEY ("MailingListTrigger_id", "indexNo");

--
-- Name: MailingList MailingList_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingList"
    ADD CONSTRAINT "MailingList_pkey" PRIMARY KEY (id);

--
-- Name: MessageDesc MessageDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageDesc"
    ADD CONSTRAINT "MessageDesc_pkey" PRIMARY KEY (id);

--
-- Name: MessageFilter MessageFilter_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageFilter"
    ADD CONSTRAINT "MessageFilter_pkey" PRIMARY KEY (id);

--
-- Name: MessageHistory MessageHistory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageHistory"
    ADD CONSTRAINT "MessageHistory_pkey" PRIMARY KEY (id);

--
-- Name: MessagePartDesc MessagePartDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessagePartDesc"
    ADD CONSTRAINT "MessagePartDesc_pkey" PRIMARY KEY (id);

--
-- Name: MessagePart MessagePart_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessagePart"
    ADD CONSTRAINT "MessagePart_pkey" PRIMARY KEY (id);

--
-- Name: MessageSeriesMapping MessageSeriesMapping_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageSeriesMapping"
    ADD CONSTRAINT "MessageSeriesMapping_pkey" PRIMARY KEY (id);

--
-- Name: MessageSeries MessageSeries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageSeries"
    ADD CONSTRAINT "MessageSeries_pkey" PRIMARY KEY (id);

--
-- Name: MessageTag MessageTag_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageTag"
    ADD CONSTRAINT "MessageTag_pkey" PRIMARY KEY (id);

--
-- Name: Message_Area Message_Area_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message_Area"
    ADD CONSTRAINT "Message_Area_pkey" PRIMARY KEY ("Message_id", "areas_ORDER");

--
-- Name: Message_Chart Message_Chart_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message_Chart"
    ADD CONSTRAINT "Message_Chart_pkey" PRIMARY KEY ("Message_id", "charts_ORDER");

--
-- Name: Message Message_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message"
    ADD CONSTRAINT "Message_pkey" PRIMARY KEY (id);

--
-- Name: NavtexMessagePromulgation NavtexMessagePromulgation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."NavtexMessagePromulgation"
    ADD CONSTRAINT "NavtexMessagePromulgation_pkey" PRIMARY KEY (id);

--
-- Name: NavtexTransmitter_Area NavtexTransmitter_Area_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."NavtexTransmitter_Area"
    ADD CONSTRAINT "NavtexTransmitter_Area_pkey" PRIMARY KEY ("NavtexTransmitter_id", "areas_ORDER");

--
-- Name: NavtexTransmitter NavtexTransmitter_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."NavtexTransmitter"
    ADD CONSTRAINT "NavtexTransmitter_pkey" PRIMARY KEY (id);

--
-- Name: NiordIntegration NiordIntegration_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."NiordIntegration"
    ADD CONSTRAINT "NiordIntegration_pkey" PRIMARY KEY (id);

--
-- Name: ParamType_DictionaryEntry ParamType_DictionaryEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ParamType_DictionaryEntry"
    ADD CONSTRAINT "ParamType_DictionaryEntry_pkey" PRIMARY KEY ("ListParamType_id", "indexNo");

--
-- Name: ParamType_TemplateParam ParamType_TemplateParam_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ParamType_TemplateParam"
    ADD CONSTRAINT "ParamType_TemplateParam_pkey" PRIMARY KEY ("CompositeParamType_id", "indexNo");

--
-- Name: ParamType ParamType_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ParamType"
    ADD CONSTRAINT "ParamType_pkey" PRIMARY KEY (id);

--
-- Name: PromulgationType PromulgationType_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PromulgationType"
    ADD CONSTRAINT "PromulgationType_pkey" PRIMARY KEY (id);

--
-- Name: PromulgationType_scriptResourcePaths PromulgationType_scriptResourcePaths_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PromulgationType_scriptResourcePaths"
    ADD CONSTRAINT "PromulgationType_scriptResourcePaths_pkey" PRIMARY KEY ("PromulgationType_id", "indexNo");

--
-- Name: PublicationCategoryDesc PublicationCategoryDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationCategoryDesc"
    ADD CONSTRAINT "PublicationCategoryDesc_pkey" PRIMARY KEY (id);

--
-- Name: PublicationCategory PublicationCategory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationCategory"
    ADD CONSTRAINT "PublicationCategory_pkey" PRIMARY KEY (id);

--
-- Name: PublicationDesc PublicationDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationDesc"
    ADD CONSTRAINT "PublicationDesc_pkey" PRIMARY KEY (id);

--
-- Name: Publication Publication_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Publication"
    ADD CONSTRAINT "Publication_pkey" PRIMARY KEY (id);

--
-- Name: ReferenceDesc ReferenceDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReferenceDesc"
    ADD CONSTRAINT "ReferenceDesc_pkey" PRIMARY KEY (id);

--
-- Name: Reference Reference_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Reference"
    ADD CONSTRAINT "Reference_pkey" PRIMARY KEY (id);

--
-- Name: SafetyNetArea SafetyNetArea_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SafetyNetArea"
    ADD CONSTRAINT "SafetyNetArea_pkey" PRIMARY KEY (id);

--
-- Name: SafetyNetMessagePromulgation SafetyNetMessagePromulgation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SafetyNetMessagePromulgation"
    ADD CONSTRAINT "SafetyNetMessagePromulgation_pkey" PRIMARY KEY (id);

--
-- Name: ScheduledMailRecipient ScheduledMailRecipient_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ScheduledMailRecipient"
    ADD CONSTRAINT "ScheduledMailRecipient_pkey" PRIMARY KEY (id);

--
-- Name: ScheduledMail ScheduledMail_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ScheduledMail"
    ADD CONSTRAINT "ScheduledMail_pkey" PRIMARY KEY (id);

--
-- Name: ScriptResourceHistory ScriptResourceHistory_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ScriptResourceHistory"
    ADD CONSTRAINT "ScriptResourceHistory_pkey" PRIMARY KEY (id);

--
-- Name: ScriptResource ScriptResource_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ScriptResource"
    ADD CONSTRAINT "ScriptResource_pkey" PRIMARY KEY (id);

--
-- Name: Setting Setting_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Setting"
    ADD CONSTRAINT "Setting_pkey" PRIMARY KEY (setting_key);

--
-- Name: SourceDesc SourceDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SourceDesc"
    ADD CONSTRAINT "SourceDesc_pkey" PRIMARY KEY (id);

--
-- Name: Source Source_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Source"
    ADD CONSTRAINT "Source_pkey" PRIMARY KEY (id);

--
-- Name: TemplateParamDesc TemplateParamDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."TemplateParamDesc"
    ADD CONSTRAINT "TemplateParamDesc_pkey" PRIMARY KEY (id);

--
-- Name: TemplateParam TemplateParam_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."TemplateParam"
    ADD CONSTRAINT "TemplateParam_pkey" PRIMARY KEY (id);

--
-- Name: TwitterMessagePromulgation TwitterMessagePromulgation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."TwitterMessagePromulgation"
    ADD CONSTRAINT "TwitterMessagePromulgation_pkey" PRIMARY KEY (id);

--
-- Name: TwitterSettings TwitterSettings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."TwitterSettings"
    ADD CONSTRAINT "TwitterSettings_pkey" PRIMARY KEY (id);

--
-- Name: MessagePart UK2g7pt9qmowq963ia6xiyyy75x; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessagePart"
    ADD CONSTRAINT "UK2g7pt9qmowq963ia6xiyyy75x" UNIQUE (geometry_id);

--
-- Name: DictionaryEntryDesc UK3bcuq11gitgbqox3r6ysr9g15; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DictionaryEntryDesc"
    ADD CONSTRAINT "UK3bcuq11gitgbqox3r6ysr9g15" UNIQUE (lang, entity_id);

--
-- Name: Message UK3vywnosnkxnktg8cdq4yquiel; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message"
    ADD CONSTRAINT "UK3vywnosnkxnktg8cdq4yquiel" UNIQUE ("repoPath");

--
-- Name: PublicationCategoryDesc UK42bsys4n5oxq426hwrumfbj47; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationCategoryDesc"
    ADD CONSTRAINT "UK42bsys4n5oxq426hwrumfbj47" UNIQUE (lang, entity_id);

--
-- Name: ParamType_TemplateParam UK477vtqrix1u7hx9u2f0pn1hhk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ParamType_TemplateParam"
    ADD CONSTRAINT "UK477vtqrix1u7hx9u2f0pn1hhk" UNIQUE ("templateParams_id");

--
-- Name: PromulgationType UK5pd25o5devf1h7nhiacfdhb6s; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PromulgationType"
    ADD CONSTRAINT "UK5pd25o5devf1h7nhiacfdhb6s" UNIQUE ("typeId");

--
-- Name: MailingListTriggerDesc UK5rscojbpyibg6j7yxigi210b1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListTriggerDesc"
    ADD CONSTRAINT "UK5rscojbpyibg6j7yxigi210b1" UNIQUE (lang, entity_id);

--
-- Name: ScriptResource UK6m94jlgq2dob1t3v1hvkaam7; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ScriptResource"
    ADD CONSTRAINT "UK6m94jlgq2dob1t3v1hvkaam7" UNIQUE (path);

--
-- Name: MessageDesc UK6nkpdt5yqj65m0dbtokn82gdk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageDesc"
    ADD CONSTRAINT "UK6nkpdt5yqj65m0dbtokn82gdk" UNIQUE (lang, entity_id);

--
-- Name: CategoryDesc UK79f3bv7kgbctos8vytey719sh; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."CategoryDesc"
    ADD CONSTRAINT "UK79f3bv7kgbctos8vytey719sh" UNIQUE (lang, entity_id);

--
-- Name: Dictionary UK9hm4hn2om6o6tndjv778lmxu8; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Dictionary"
    ADD CONSTRAINT "UK9hm4hn2om6o6tndjv778lmxu8" UNIQUE (name);

--
-- Name: FiringSchedule UK9kywha2p8xmlv4s6jsmgyjmu9; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FiringSchedule"
    ADD CONSTRAINT "UK9kywha2p8xmlv4s6jsmgyjmu9" UNIQUE (domain_id);

--
-- Name: PublicationDesc UKahelkfcqdowtsbfejfnghhq8i; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationDesc"
    ADD CONSTRAINT "UKahelkfcqdowtsbfejfnghhq8i" UNIQUE (lang, entity_id);

--
-- Name: TemplateParamDesc UKcffyw4ta1uc6571bbpqse5xp2; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."TemplateParamDesc"
    ADD CONSTRAINT "UKcffyw4ta1uc6571bbpqse5xp2" UNIQUE (lang, entity_id);

--
-- Name: AttachmentDesc UKe1k5rwk50uq7pgjdlcrt35wrv; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AttachmentDesc"
    ADD CONSTRAINT "UKe1k5rwk50uq7pgjdlcrt35wrv" UNIQUE (lang, entity_id);

--
-- Name: AreaDesc UKe6cypowmvwtywg4ws3sahe6b9; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AreaDesc"
    ADD CONSTRAINT "UKe6cypowmvwtywg4ws3sahe6b9" UNIQUE (lang, entity_id);

--
-- Name: ParamType UKeu7m0tuk9ta1a8xn383wkypxs; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ParamType"
    ADD CONSTRAINT "UKeu7m0tuk9ta1a8xn383wkypxs" UNIQUE (name);

--
-- Name: MailingListDesc UKf2iiw2rrh64w0eigom6ki4oh9; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListDesc"
    ADD CONSTRAINT "UKf2iiw2rrh64w0eigom6ki4oh9" UNIQUE (lang, entity_id);

--
-- Name: MessageFilter UKfavv2qx2l5is2g7ngnq91cj83; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageFilter"
    ADD CONSTRAINT "UKfavv2qx2l5is2g7ngnq91cj83" UNIQUE (name, user_id);

--
-- Name: NavtexTransmitter UKflpiqm1uma8qy8eg47dbnhkbc; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."NavtexTransmitter"
    ADD CONSTRAINT "UKflpiqm1uma8qy8eg47dbnhkbc" UNIQUE (name);

--
-- Name: Domain UKg3oj4clyxuhi77adpybsagfhi; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT "UKg3oj4clyxuhi77adpybsagfhi" UNIQUE ("firingSchedule_id");

--
-- Name: SafetyNetArea UKh9ts85w0gaqv9vgoipvaakobc; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SafetyNetArea"
    ADD CONSTRAINT "UKh9ts85w0gaqv9vgoipvaakobc" UNIQUE (name);

--
-- Name: Publication UKhlw3cb7u4ds5iqrcc1ynfb45l; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Publication"
    ADD CONSTRAINT "UKhlw3cb7u4ds5iqrcc1ynfb45l" UNIQUE ("publicationId");

--
-- Name: MailingList UKi9h3ypfy68iawo0dxtstl0d79; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingList"
    ADD CONSTRAINT "UKi9h3ypfy68iawo0dxtstl0d79" UNIQUE ("mailingListId");

--
-- Name: PublicationCategory UKj958ud6pipr5m1obhloi39dwn; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationCategory"
    ADD CONSTRAINT "UKj958ud6pipr5m1obhloi39dwn" UNIQUE ("categoryId");

--
-- Name: MessagePartDesc UKkdagykk5s2fhsn8hhym7byfgj; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessagePartDesc"
    ADD CONSTRAINT "UKkdagykk5s2fhsn8hhym7byfgj" UNIQUE (lang, entity_id);

--
-- Name: MessageTag UKm25na8uv3b0h30y26edir90fo; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageTag"
    ADD CONSTRAINT "UKm25na8uv3b0h30y26edir90fo" UNIQUE ("tagId");

--
-- Name: Message UKn9pfiw9qwbcqrf4fg2sok0sid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message"
    ADD CONSTRAINT "UKn9pfiw9qwbcqrf4fg2sok0sid" UNIQUE (uid);

--
-- Name: FmReport UKnpe4nja7vv2020frekbljmh03; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FmReport"
    ADD CONSTRAINT "UKnpe4nja7vv2020frekbljmh03" UNIQUE ("reportId");

--
-- Name: Category_TemplateParam UKnvjjhp7o2301h7w3tolfj3igl; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category_TemplateParam"
    ADD CONSTRAINT "UKnvjjhp7o2301h7w3tolfj3igl" UNIQUE ("templateParams_id");

--
-- Name: SourceDesc UKnww669xgvx5wjvtaqa4wny2jy; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SourceDesc"
    ADD CONSTRAINT "UKnww669xgvx5wjvtaqa4wny2jy" UNIQUE (lang, entity_id);

--
-- Name: MessageSeries UKpjpnujmcdrb2kwoykpmeqf84k; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageSeries"
    ADD CONSTRAINT "UKpjpnujmcdrb2kwoykpmeqf84k" UNIQUE ("seriesId");

--
-- Name: ReferenceDesc UKtdtevuifjncm3g2klfix7ub9u; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReferenceDesc"
    ADD CONSTRAINT "UKtdtevuifjncm3g2klfix7ub9u" UNIQUE (lang, entity_id);

--
-- Name: User User_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."User"
    ADD CONSTRAINT "User_pkey" PRIMARY KEY (id);

--
-- Name: Chart chart_chart_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Chart"
    ADD CONSTRAINT chart_chart_number UNIQUE ("chartNumber");

--
-- Name: Contact contact_email; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Contact"
    ADD CONSTRAINT contact_email UNIQUE (email);

--
-- Name: Domain domain_domain_id; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT domain_domain_id UNIQUE ("domainId");

--
-- Name: FeatureCollection feature_collection_uid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FeatureCollection"
    ADD CONSTRAINT feature_collection_uid UNIQUE (uid);

--
-- Name: Feature feature_uid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Feature"
    ADD CONSTRAINT feature_uid UNIQUE (uid);

--
-- Name: Sequence sequence_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Sequence"
    ADD CONSTRAINT sequence_name PRIMARY KEY (name);

--
-- Name: User user_username; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."User"
    ADD CONSTRAINT user_username UNIQUE (username);

--
-- Name: aton_tag_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX aton_tag_k ON public."AtonTag" USING btree (k);

--
-- Name: aton_tag_v; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX aton_tag_v ON public."AtonTag" USING btree (v);

--
-- Name: batch_data_instance_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX batch_data_instance_id ON public."BatchData" USING btree ("instanceId");

--
-- Name: firing_period_from_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX firing_period_from_date ON public."FiringPeriod" USING btree ("fromDate");

--
-- Name: firing_period_to_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX firing_period_to_date ON public."FiringPeriod" USING btree ("toDate");

--
-- Name: message_legacy_id_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_legacy_id_k ON public."Message" USING btree ("legacyId");

--
-- Name: message_main_type_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_main_type_k ON public."Message" USING btree ("mainType");

--
-- Name: message_status_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_status_k ON public."Message" USING btree (status);

--
-- Name: message_tag_id_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_tag_id_k ON public."MessageTag" USING btree ("tagId");

--
-- Name: message_tag_type_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_tag_type_k ON public."MessageTag" USING btree (type);

--
-- Name: message_type_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_type_k ON public."Message" USING btree (type);

--
-- Name: message_uid_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_uid_k ON public."Message" USING btree (uid);

--
-- Name: publication_id_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX publication_id_k ON public."Publication" USING btree ("publicationId");

--
-- Name: publication_main_type_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX publication_main_type_k ON public."Publication" USING btree ("mainType");

--
-- Name: publication_status_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX publication_status_k ON public."Publication" USING btree (status);

--
-- Name: publication_type_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX publication_type_k ON public."Publication" USING btree (type);

--
-- Name: reference_message_id_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX reference_message_id_k ON public."Reference" USING btree ("messageId");

--
-- Name: scheduled_mail_send_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX scheduled_mail_send_date ON public."ScheduledMail" USING btree ("sendDate");

--
-- Name: scheduled_mail_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX scheduled_mail_status ON public."ScheduledMail" USING btree (status);

--
-- Name: aton_node_geom; Type: INDEX; Schema: public; Owner: -
-- PG7: PostGIS GIST index so AtoN spatial searches (ST_Within on AtonNode.geometry) use a spatial
-- index instead of a sequential scan. Postgres does not create spatial indexes implicitly.
--

CREATE INDEX aton_node_geom ON public."AtonNode" USING gist (geometry);

--
-- Name: feature_geom; Type: INDEX; Schema: public; Owner: -
-- PG7: PostGIS GIST index so message extent searches (ST_Intersects on Feature.geometry) use a
-- spatial index instead of a sequential scan.
--

CREATE INDEX feature_geom ON public."Feature" USING gist (geometry);

--
-- Name: Category_stdTemplateFields FK10gv4jc86if84eajgljysgy48; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category_stdTemplateFields"
    ADD CONSTRAINT "FK10gv4jc86if84eajgljysgy48" FOREIGN KEY ("Category_id") REFERENCES public."Category"(id);

--
-- Name: MessagePart FK16ok94kha925dve5s5eblv8vf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessagePart"
    ADD CONSTRAINT "FK16ok94kha925dve5s5eblv8vf" FOREIGN KEY (geometry_id) REFERENCES public."FeatureCollection"(id);

--
-- Name: NavtexTransmitter_Area FK1bbijt2mapcqt8sj3qtwty2rj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."NavtexTransmitter_Area"
    ADD CONSTRAINT "FK1bbijt2mapcqt8sj3qtwty2rj" FOREIGN KEY ("NavtexTransmitter_id") REFERENCES public."NavtexTransmitter"(id);

--
-- Name: AtonTag FK1g0oj8oexgpuavd6g5u5xvqar; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AtonTag"
    ADD CONSTRAINT "FK1g0oj8oexgpuavd6g5u5xvqar" FOREIGN KEY ("atonNode_id") REFERENCES public."AtonNode"(id);

--
-- Name: PublicationCategoryDesc FK2c69fsmt1mqi6i39vypc3jm9x; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationCategoryDesc"
    ADD CONSTRAINT "FK2c69fsmt1mqi6i39vypc3jm9x" FOREIGN KEY (entity_id) REFERENCES public."PublicationCategory"(id);

--
-- Name: AttachmentDesc FK2qfremrg24ipwowle31u8cg10; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AttachmentDesc"
    ADD CONSTRAINT "FK2qfremrg24ipwowle31u8cg10" FOREIGN KEY (entity_id) REFERENCES public."Attachment"(id);

--
-- Name: DictionaryEntry FK314qqo39qjye8l3b6mt2uc1ej; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DictionaryEntry"
    ADD CONSTRAINT "FK314qqo39qjye8l3b6mt2uc1ej" FOREIGN KEY (dictionary_id) REFERENCES public."Dictionary"(id);

--
-- Name: BaseMessagePromulgation FK370v5r9luxfm01xw0u5xf8pei; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BaseMessagePromulgation"
    ADD CONSTRAINT "FK370v5r9luxfm01xw0u5xf8pei" FOREIGN KEY (message_id) REFERENCES public."Message"(id);

--
-- Name: Category_Domain FK3au2h0qsphomsc15k66w2gt6s; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category_Domain"
    ADD CONSTRAINT "FK3au2h0qsphomsc15k66w2gt6s" FOREIGN KEY (domains_id) REFERENCES public."Domain"(id);

--
-- Name: TwitterSettings FK3b2wg8m7mnscm5oulldflo5t3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."TwitterSettings"
    ADD CONSTRAINT "FK3b2wg8m7mnscm5oulldflo5t3" FOREIGN KEY ("promulgationType_id") REFERENCES public."PromulgationType"(id);

--
-- Name: PromulgationType_messageTypes FK3cldsbr0e4v5qa49091vxywxk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PromulgationType_messageTypes"
    ADD CONSTRAINT "FK3cldsbr0e4v5qa49091vxywxk" FOREIGN KEY ("PromulgationType_id") REFERENCES public."PromulgationType"(id);

--
-- Name: Message_Chart FK3dhjl6lvvr6np34wb5ohumvkf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message_Chart"
    ADD CONSTRAINT "FK3dhjl6lvvr6np34wb5ohumvkf" FOREIGN KEY (charts_id) REFERENCES public."Chart"(id);

--
-- Name: NavtexTransmitter_Area FK3fglsjdhjutyi50k71cxlyq53; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."NavtexTransmitter_Area"
    ADD CONSTRAINT "FK3fglsjdhjutyi50k71cxlyq53" FOREIGN KEY (areas_id) REFERENCES public."Area"(id);

--
-- Name: PublicationDesc FK3g301h8xh0ikan53mm8qaagmh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationDesc"
    ADD CONSTRAINT "FK3g301h8xh0ikan53mm8qaagmh" FOREIGN KEY (entity_id) REFERENCES public."Publication"(id);

--
-- Name: PromulgationType_scriptResourcePaths FK3wyns9mftuvmehwjthw4aylle; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PromulgationType_scriptResourcePaths"
    ADD CONSTRAINT "FK3wyns9mftuvmehwjthw4aylle" FOREIGN KEY ("PromulgationType_id") REFERENCES public."PromulgationType"(id);

--
-- Name: MessageTag_Message FK42apm0oo9y7u0ctj1vxlp5sph; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageTag_Message"
    ADD CONSTRAINT "FK42apm0oo9y7u0ctj1vxlp5sph" FOREIGN KEY (messages_id) REFERENCES public."Message"(id);

--
-- Name: Area_editorFields FK4437dondu1g30sw68tw10fs6n; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Area_editorFields"
    ADD CONSTRAINT "FK4437dondu1g30sw68tw10fs6n" FOREIGN KEY ("Area_id") REFERENCES public."Area"(id);

--
-- Name: NavtexMessagePromulgation_NavtexTransmitter FK461kln5mguuk54m02ex89d5sx; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."NavtexMessagePromulgation_NavtexTransmitter"
    ADD CONSTRAINT "FK461kln5mguuk54m02ex89d5sx" FOREIGN KEY ("NavtexMessagePromulgation_id") REFERENCES public."NavtexMessagePromulgation"(id);

--
-- Name: ParamType_TemplateParam FK4arj3ype6gcjhm1ljdl4y3fr0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ParamType_TemplateParam"
    ADD CONSTRAINT "FK4arj3ype6gcjhm1ljdl4y3fr0" FOREIGN KEY ("templateParams_id") REFERENCES public."TemplateParam"(id);

--
-- Name: MessageFilter FK4t1b8pwj7bowstlx0i2yqtf0d; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageFilter"
    ADD CONSTRAINT "FK4t1b8pwj7bowstlx0i2yqtf0d" FOREIGN KEY (user_id) REFERENCES public."User"(id);

--
-- Name: FiringSchedule FK5rixdwh8aiios9t0vmw4pvxgg; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FiringSchedule"
    ADD CONSTRAINT "FK5rixdwh8aiios9t0vmw4pvxgg" FOREIGN KEY ("targetMessageSeries_id") REFERENCES public."MessageSeries"(id);

--
-- Name: NavtexTransmitter FK5yd26r62u5p82ltm9njnlo31l; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."NavtexTransmitter"
    ADD CONSTRAINT "FK5yd26r62u5p82ltm9njnlo31l" FOREIGN KEY ("promulgationType_id") REFERENCES public."PromulgationType"(id);

--
-- Name: Domain_Category FK5yqyglex84a7v51bu5dn5l6aw; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_Category"
    ADD CONSTRAINT "FK5yqyglex84a7v51bu5dn5l6aw" FOREIGN KEY ("Domain_id") REFERENCES public."Domain"(id);

--
-- Name: FmReport_Domain FK61rhwsycad8tu30isd47marq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FmReport_Domain"
    ADD CONSTRAINT "FK61rhwsycad8tu30isd47marq" FOREIGN KEY ("FmReport_id") REFERENCES public."FmReport"(id);

--
-- Name: Reference FK62ofexfnmotaqrc45qgbamt42; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Reference"
    ADD CONSTRAINT "FK62ofexfnmotaqrc45qgbamt42" FOREIGN KEY (message_id) REFERENCES public."Message"(id);

--
-- Name: Publication FK66i5glxfp78an1y3hu4la72oa; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Publication"
    ADD CONSTRAINT "FK66i5glxfp78an1y3hu4la72oa" FOREIGN KEY ("messageTag_id") REFERENCES public."MessageTag"(id);

--
-- Name: Message FK68ano2lcbpre1nslqmin17vkd; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message"
    ADD CONSTRAINT "FK68ano2lcbpre1nslqmin17vkd" FOREIGN KEY ("createdBy_id") REFERENCES public."User"(id);

--
-- Name: Message FK6dmmu9e0mwjwifxqb0ansfdh5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message"
    ADD CONSTRAINT "FK6dmmu9e0mwjwifxqb0ansfdh5" FOREIGN KEY ("lastUpdatedBy_id") REFERENCES public."User"(id);

--
-- Name: MessageSeries_editorFields FK6nk9wtjcwmui72240jkc6cbvj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageSeries_editorFields"
    ADD CONSTRAINT "FK6nk9wtjcwmui72240jkc6cbvj" FOREIGN KEY ("MessageSeries_id") REFERENCES public."MessageSeries"(id);

--
-- Name: MessagePartDesc FK6uwwh4qc529xflv93e7q1a33a; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessagePartDesc"
    ADD CONSTRAINT "FK6uwwh4qc529xflv93e7q1a33a" FOREIGN KEY (entity_id) REFERENCES public."MessagePart"(id);

--
-- Name: PromulgationType_Domain FK6viva5obdjbf46f1rw98wsxj3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PromulgationType_Domain"
    ADD CONSTRAINT "FK6viva5obdjbf46f1rw98wsxj3" FOREIGN KEY (domains_id) REFERENCES public."Domain"(id);

--
-- Name: Comment FK77t8jphhpgmro87x05ep9s4nj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Comment"
    ADD CONSTRAINT "FK77t8jphhpgmro87x05ep9s4nj" FOREIGN KEY (message_id) REFERENCES public."Message"(id);

--
-- Name: PromulgationType_Domain FK7l15fdk8362se5ua1cbolosvw; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PromulgationType_Domain"
    ADD CONSTRAINT "FK7l15fdk8362se5ua1cbolosvw" FOREIGN KEY ("PromulgationType_id") REFERENCES public."PromulgationType"(id);

--
-- Name: Message_Category FK7qvi853vuf9coy1ccawhssa03; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message_Category"
    ADD CONSTRAINT "FK7qvi853vuf9coy1ccawhssa03" FOREIGN KEY (categories_id) REFERENCES public."Category"(id);

--
-- Name: BatchData FK7v4e24e3jrbmfos0ui58cm0w; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BatchData"
    ADD CONSTRAINT "FK7v4e24e3jrbmfos0ui58cm0w" FOREIGN KEY (user_id) REFERENCES public."User"(id);

--
-- Name: SafetyNetMessagePromulgation FK7vhuk53i86mim6p2qr7e8kyww; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SafetyNetMessagePromulgation"
    ADD CONSTRAINT "FK7vhuk53i86mim6p2qr7e8kyww" FOREIGN KEY (id) REFERENCES public."BaseMessagePromulgation"(id);

--
-- Name: FiringSchedule FK8hi2q43pgah4egsvdr3q3fhoy; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FiringSchedule"
    ADD CONSTRAINT "FK8hi2q43pgah4egsvdr3q3fhoy" FOREIGN KEY ("targetDomain_id") REFERENCES public."Domain"(id);

--
-- Name: Attachment FK8k63le47l4r5atsco7nsvqsr4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Attachment"
    ADD CONSTRAINT "FK8k63le47l4r5atsco7nsvqsr4" FOREIGN KEY (message_id) REFERENCES public."Message"(id);

--
-- Name: ReferenceDesc FK9aalnk0q5cjge4m45guieiov4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ReferenceDesc"
    ADD CONSTRAINT "FK9aalnk0q5cjge4m45guieiov4" FOREIGN KEY (entity_id) REFERENCES public."Reference"(id);

--
-- Name: AreaDesc FK9qy6raspym9thicdsoss8dapp; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AreaDesc"
    ADD CONSTRAINT "FK9qy6raspym9thicdsoss8dapp" FOREIGN KEY (entity_id) REFERENCES public."Area"(id);

--
-- Name: DateInterval FKadesv730fjttjo9vugfue8uva; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DateInterval"
    ADD CONSTRAINT "FKadesv730fjttjo9vugfue8uva" FOREIGN KEY ("messagePart_id") REFERENCES public."MessagePart"(id);

--
-- Name: Comment_emailAddresses FKaijfib1e4755c14eb6v2rmjmh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Comment_emailAddresses"
    ADD CONSTRAINT "FKaijfib1e4755c14eb6v2rmjmh" FOREIGN KEY ("Comment_id") REFERENCES public."Comment"(id);

--
-- Name: SourceDesc FKakat2feqr3rm7kcglm1fc0jkh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SourceDesc"
    ADD CONSTRAINT "FKakat2feqr3rm7kcglm1fc0jkh" FOREIGN KEY (entity_id) REFERENCES public."Source"(id);

--
-- Name: Category_editorFields FKam3qjd8vwla7ysj4buanitj80; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category_editorFields"
    ADD CONSTRAINT "FKam3qjd8vwla7ysj4buanitj80" FOREIGN KEY ("Category_id") REFERENCES public."Category"(id);

--
-- Name: MailingList_Contact FKaqfgbh45v8fqop28l2ylpbp8c; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingList_Contact"
    ADD CONSTRAINT "FKaqfgbh45v8fqop28l2ylpbp8c" FOREIGN KEY (contacts_id) REFERENCES public."Contact"(id);

--
-- Name: ScriptResourceHistory FKawbsao8kv2ky4rkiob78mtu8i; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ScriptResourceHistory"
    ADD CONSTRAINT "FKawbsao8kv2ky4rkiob78mtu8i" FOREIGN KEY (user_id) REFERENCES public."User"(id);

--
-- Name: MessageTag_Message FKb7ib7c9ahgkxgsxrrr7q4qmt8; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageTag_Message"
    ADD CONSTRAINT "FKb7ib7c9ahgkxgsxrrr7q4qmt8" FOREIGN KEY (tags_id) REFERENCES public."MessageTag"(id);

--
-- Name: MailingListTrigger_scriptResourcePaths FKbnw1oqyljlv3svdoji17erkf6; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListTrigger_scriptResourcePaths"
    ADD CONSTRAINT "FKbnw1oqyljlv3svdoji17erkf6" FOREIGN KEY ("MailingListTrigger_id") REFERENCES public."MailingListTrigger"(id);

--
-- Name: MailingListDesc FKbuax0frn1dncqli805tvshm8v; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListDesc"
    ADD CONSTRAINT "FKbuax0frn1dncqli805tvshm8v" FOREIGN KEY (entity_id) REFERENCES public."MailingList"(id);

--
-- Name: BatchData FKc1c8nq836oqbvumb65qb93691; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BatchData"
    ADD CONSTRAINT "FKc1c8nq836oqbvumb65qb93691" FOREIGN KEY (domain_id) REFERENCES public."Domain"(id);

--
-- Name: Domain_Area FKcffqo03k4a4xmfmgdvyqvlx1v; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_Area"
    ADD CONSTRAINT "FKcffqo03k4a4xmfmgdvyqvlx1v" FOREIGN KEY (areas_id) REFERENCES public."Area"(id);

--
-- Name: SafetyNetArea FKcron6hk5ckn1flqw9k9lo0un; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SafetyNetArea"
    ADD CONSTRAINT "FKcron6hk5ckn1flqw9k9lo0un" FOREIGN KEY ("promulgationType_id") REFERENCES public."PromulgationType"(id);

--
-- Name: Category_TemplateParam FKd2xteua7fnasbc07umjxbttd3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category_TemplateParam"
    ADD CONSTRAINT "FKd2xteua7fnasbc07umjxbttd3" FOREIGN KEY ("Category_id") REFERENCES public."Category"(id);

--
-- Name: Message_Area FKdwo44aikrx5davbwv5ool9ri7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message_Area"
    ADD CONSTRAINT "FKdwo44aikrx5davbwv5ool9ri7" FOREIGN KEY ("Message_id") REFERENCES public."Message"(id);

--
-- Name: Domain_Category FKexyqxhk9co2fd0q88qyy4m72a; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_Category"
    ADD CONSTRAINT "FKexyqxhk9co2fd0q88qyy4m72a" FOREIGN KEY (categories_id) REFERENCES public."Category"(id);

--
-- Name: Category_scriptResourcePaths FKfakp3dkfd71nhvhwwaw0c49fq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category_scriptResourcePaths"
    ADD CONSTRAINT "FKfakp3dkfd71nhvhwwaw0c49fq" FOREIGN KEY ("Category_id") REFERENCES public."Category"(id);

--
-- Name: CategoryDesc FKfdmhl3fc0h7bdnrsku22kcd9y; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."CategoryDesc"
    ADD CONSTRAINT "FKfdmhl3fc0h7bdnrsku22kcd9y" FOREIGN KEY (entity_id) REFERENCES public."Category"(id);

--
-- Name: MessageSeriesMapping FKfkn2ne1fn8q7jl1klvcks2n0f; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageSeriesMapping"
    ADD CONSTRAINT "FKfkn2ne1fn8q7jl1klvcks2n0f" FOREIGN KEY ("niordIntegration_id") REFERENCES public."NiordIntegration"(id);

--
-- Name: Publication FKfo3rfjwnag1vsq2x6bw4fups2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Publication"
    ADD CONSTRAINT "FKfo3rfjwnag1vsq2x6bw4fups2" FOREIGN KEY (category_id) REFERENCES public."PublicationCategory"(id);

--
-- Name: FmReport_Domain FKfumwkkkl8a5d491tf7cq04ed9; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FmReport_Domain"
    ADD CONSTRAINT "FKfumwkkkl8a5d491tf7cq04ed9" FOREIGN KEY (domains_id) REFERENCES public."Domain"(id);

--
-- Name: Message_Area FKfwuoil3u5bpdwofj0no0i68qr; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message_Area"
    ADD CONSTRAINT "FKfwuoil3u5bpdwofj0no0i68qr" FOREIGN KEY (areas_id) REFERENCES public."Area"(id);

--
-- Name: ScriptResourceHistory FKfy5cnibqrlm9ln3arkgsc9d6w; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ScriptResourceHistory"
    ADD CONSTRAINT "FKfy5cnibqrlm9ln3arkgsc9d6w" FOREIGN KEY (resource_id) REFERENCES public."ScriptResource"(id);

--
-- Name: AudioMessagePromulgation FKgi1xxjodys313s0nrqg2d9106; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."AudioMessagePromulgation"
    ADD CONSTRAINT "FKgi1xxjodys313s0nrqg2d9106" FOREIGN KEY (id) REFERENCES public."BaseMessagePromulgation"(id);

--
-- Name: MessageHistory FKgyfqmev0nsitjt9e3jhc88qpo; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageHistory"
    ADD CONSTRAINT "FKgyfqmev0nsitjt9e3jhc88qpo" FOREIGN KEY (message_id) REFERENCES public."Message"(id);

--
-- Name: NavtexMessagePromulgation_NavtexTransmitter FKh8x4xb27t0t7xtnrvqouctikp; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."NavtexMessagePromulgation_NavtexTransmitter"
    ADD CONSTRAINT "FKh8x4xb27t0t7xtnrvqouctikp" FOREIGN KEY (transmitters_id) REFERENCES public."NavtexTransmitter"(id);

--
-- Name: FiringSchedule FKhf6pjksbfdt5y2erkgnobtvx5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FiringSchedule"
    ADD CONSTRAINT "FKhf6pjksbfdt5y2erkgnobtvx5" FOREIGN KEY (domain_id) REFERENCES public."Domain"(id);

--
-- Name: Feature FKhqki1pjc5j17s7n4py0s1jfjl; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Feature"
    ADD CONSTRAINT "FKhqki1pjc5j17s7n4py0s1jfjl" FOREIGN KEY ("featureCollection_id") REFERENCES public."FeatureCollection"(id);

--
-- Name: MailingList_Contact FKi6dgbhrl2kbvfduc837vxjwdh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingList_Contact"
    ADD CONSTRAINT "FKi6dgbhrl2kbvfduc837vxjwdh" FOREIGN KEY ("MailingList_id") REFERENCES public."MailingList"(id);

--
-- Name: MailingList_User FKic5g1t951qmaydiclwy4drkma; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingList_User"
    ADD CONSTRAINT "FKic5g1t951qmaydiclwy4drkma" FOREIGN KEY ("MailingList_id") REFERENCES public."MailingList"(id);

--
-- Name: ParamType_TemplateParam FKjxwroomkagk95s7jac656bx26; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ParamType_TemplateParam"
    ADD CONSTRAINT "FKjxwroomkagk95s7jac656bx26" FOREIGN KEY ("CompositeParamType_id") REFERENCES public."ParamType"(id);

--
-- Name: MessageTag FKkdn1daqr4lylvt14petu6nxnq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageTag"
    ADD CONSTRAINT "FKkdn1daqr4lylvt14petu6nxnq" FOREIGN KEY (domain_id) REFERENCES public."Domain"(id);

--
-- Name: Comment FKkf1pjmbmty20wa12ub4lb9ao; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Comment"
    ADD CONSTRAINT "FKkf1pjmbmty20wa12ub4lb9ao" FOREIGN KEY (user_id) REFERENCES public."User"(id);

--
-- Name: Area FKkrq0e4nut9fwr51wmu8fejt35; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Area"
    ADD CONSTRAINT "FKkrq0e4nut9fwr51wmu8fejt35" FOREIGN KEY (parent_id) REFERENCES public."Area"(id);

--
-- Name: NavtexMessagePromulgation FKlfxinvcwp8b2ukmn2h9rbj9en; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."NavtexMessagePromulgation"
    ADD CONSTRAINT "FKlfxinvcwp8b2ukmn2h9rbj9en" FOREIGN KEY (id) REFERENCES public."BaseMessagePromulgation"(id);

--
-- Name: MessageTag FKlqfh8rvyr75o13o92etmh063b; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageTag"
    ADD CONSTRAINT "FKlqfh8rvyr75o13o92etmh063b" FOREIGN KEY (user_id) REFERENCES public."User"(id);

--
-- Name: MessageSeries_types FKlt7quw4ckqe9haqnkjpd60lxh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageSeries_types"
    ADD CONSTRAINT "FKlt7quw4ckqe9haqnkjpd60lxh" FOREIGN KEY ("MessageSeries_id") REFERENCES public."MessageSeries"(id);

--
-- Name: Domain FKlwy27fsfinut521iquxtqtspo; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain"
    ADD CONSTRAINT "FKlwy27fsfinut521iquxtqtspo" FOREIGN KEY ("firingSchedule_id") REFERENCES public."FiringSchedule"(id);

--
-- Name: DictionaryEntryDesc FKm2bkt2ixv8hmep8oq3y7ihiw3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DictionaryEntryDesc"
    ADD CONSTRAINT "FKm2bkt2ixv8hmep8oq3y7ihiw3" FOREIGN KEY (entity_id) REFERENCES public."DictionaryEntry"(id);

--
-- Name: TwitterMessagePromulgation FKm4l7c4xcrdmpf82kf3784xwaw; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."TwitterMessagePromulgation"
    ADD CONSTRAINT "FKm4l7c4xcrdmpf82kf3784xwaw" FOREIGN KEY (id) REFERENCES public."BaseMessagePromulgation"(id);

--
-- Name: Domain_MessageSeries FKmjltymhv55g27ichu9qh33scw; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_MessageSeries"
    ADD CONSTRAINT "FKmjltymhv55g27ichu9qh33scw" FOREIGN KEY ("messageSeries_id") REFERENCES public."MessageSeries"(id);

--
-- Name: Category FKmldq7ld9vlaucx4ydae920ela; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category"
    ADD CONSTRAINT "FKmldq7ld9vlaucx4ydae920ela" FOREIGN KEY (parent_id) REFERENCES public."Category"(id);

--
-- Name: Message FKms0gt5cp786qulibjorwbyije; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message"
    ADD CONSTRAINT "FKms0gt5cp786qulibjorwbyije" FOREIGN KEY (area_id) REFERENCES public."Area"(id);

--
-- Name: Comment FKmtnb0lv4x7x08584ii3wcg61v; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Comment"
    ADD CONSTRAINT "FKmtnb0lv4x7x08584ii3wcg61v" FOREIGN KEY ("acknowledgedBy_id") REFERENCES public."User"(id);

--
-- Name: MessageHistory FKn0yk7dh92ax5huce5mar5k3nt; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageHistory"
    ADD CONSTRAINT "FKn0yk7dh92ax5huce5mar5k3nt" FOREIGN KEY (user_id) REFERENCES public."User"(id);

--
-- Name: Message_Category FKn6o1ay9y64kbjrsgg8j382i4v; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message_Category"
    ADD CONSTRAINT "FKn6o1ay9y64kbjrsgg8j382i4v" FOREIGN KEY ("Message_id") REFERENCES public."Message"(id);

--
-- Name: FiringPeriod FKncwbopfp0wbs764ydo0d77dhp; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."FiringPeriod"
    ADD CONSTRAINT "FKncwbopfp0wbs764ydo0d77dhp" FOREIGN KEY (area_id) REFERENCES public."Area"(id);

--
-- Name: Category_TemplateParam FKndqf17wjxs5y4e8ap4g0uj6f9; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category_TemplateParam"
    ADD CONSTRAINT "FKndqf17wjxs5y4e8ap4g0uj6f9" FOREIGN KEY ("templateParams_id") REFERENCES public."TemplateParam"(id);

--
-- Name: MailingListTrigger_statusChanges FKnrpcq08ygw31c1gcgbbk90ckr; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListTrigger_statusChanges"
    ADD CONSTRAINT "FKnrpcq08ygw31c1gcgbbk90ckr" FOREIGN KEY ("MailingListTrigger_id") REFERENCES public."MailingListTrigger"(id);

--
-- Name: ParamType_DictionaryEntry FKo00k3x3auii5cg61dgrww5jbl; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ParamType_DictionaryEntry"
    ADD CONSTRAINT "FKo00k3x3auii5cg61dgrww5jbl" FOREIGN KEY (values_id) REFERENCES public."DictionaryEntry"(id);

--
-- Name: MessageDesc FKp23l5pvecwyfariniglaym951; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageDesc"
    ADD CONSTRAINT "FKp23l5pvecwyfariniglaym951" FOREIGN KEY (entity_id) REFERENCES public."Message"(id);

--
-- Name: BaseMessagePromulgation FKp7dde1up0593gbh5u3wupyhkc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BaseMessagePromulgation"
    ADD CONSTRAINT "FKp7dde1up0593gbh5u3wupyhkc" FOREIGN KEY (type_id) REFERENCES public."PromulgationType"(id);

--
-- Name: Domain_Area FKph2inv7gn742dcbwkfjh327o2; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_Area"
    ADD CONSTRAINT "FKph2inv7gn742dcbwkfjh327o2" FOREIGN KEY ("Domain_id") REFERENCES public."Domain"(id);

--
-- Name: MailingListTrigger FKpwbqemls6n5jf514i5mwltena; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListTrigger"
    ADD CONSTRAINT "FKpwbqemls6n5jf514i5mwltena" FOREIGN KEY ("mailingList_id") REFERENCES public."MailingList"(id);

--
-- Name: Message_Chart FKpyyxsmkmv4jd5bgbk6d40kljh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message_Chart"
    ADD CONSTRAINT "FKpyyxsmkmv4jd5bgbk6d40kljh" FOREIGN KEY ("Message_id") REFERENCES public."Message"(id);

--
-- Name: Publication FKqkwbpchlbvd5ollks8bjyoxke; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Publication"
    ADD CONSTRAINT "FKqkwbpchlbvd5ollks8bjyoxke" FOREIGN KEY (domain_id) REFERENCES public."Domain"(id);

--
-- Name: MessagePart FKqwbeti2mafht3kifo0w5bkb9w; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessagePart"
    ADD CONSTRAINT "FKqwbeti2mafht3kifo0w5bkb9w" FOREIGN KEY (message_id) REFERENCES public."Message"(id);

--
-- Name: ScheduledMailRecipient FKr8f49j5wbrpmcg54iduc9oco0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ScheduledMailRecipient"
    ADD CONSTRAINT "FKr8f49j5wbrpmcg54iduc9oco0" FOREIGN KEY (mail_id) REFERENCES public."ScheduledMail"(id);

--
-- Name: SafetyNetMessagePromulgation FKr9e0bqbc53ha6qlh0sa5am49s; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SafetyNetMessagePromulgation"
    ADD CONSTRAINT "FKr9e0bqbc53ha6qlh0sa5am49s" FOREIGN KEY (area_id) REFERENCES public."SafetyNetArea"(id);

--
-- Name: Domain_MessageSeries FKrfhw97q0bqgrcd805rg0vabwc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_MessageSeries"
    ADD CONSTRAINT "FKrfhw97q0bqgrcd805rg0vabwc" FOREIGN KEY ("Domain_id") REFERENCES public."Domain"(id);

--
-- Name: ParamType_DictionaryEntry FKrx4sf4qm30fli3xhmtng1yo2l; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ParamType_DictionaryEntry"
    ADD CONSTRAINT "FKrx4sf4qm30fli3xhmtng1yo2l" FOREIGN KEY ("ListParamType_id") REFERENCES public."ParamType"(id);

--
-- Name: Category_Domain FKs72o4016k5iwyrrlfo9cb4c93; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Category_Domain"
    ADD CONSTRAINT "FKs72o4016k5iwyrrlfo9cb4c93" FOREIGN KEY ("Category_id") REFERENCES public."Category"(id);

--
-- Name: MailingList_User FKssudefm5034ardlfftd2xjhdf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingList_User"
    ADD CONSTRAINT "FKssudefm5034ardlfftd2xjhdf" FOREIGN KEY (users_id) REFERENCES public."User"(id);

--
-- Name: Message FKsu0vo0vi2te4rsc82tnm4ee40; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message"
    ADD CONSTRAINT "FKsu0vo0vi2te4rsc82tnm4ee40" FOREIGN KEY ("messageSeries_id") REFERENCES public."MessageSeries"(id);

--
-- Name: MailingListTriggerDesc FKtlrykqebhk65tle306f006044; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListTriggerDesc"
    ADD CONSTRAINT "FKtlrykqebhk65tle306f006044" FOREIGN KEY (entity_id) REFERENCES public."MailingListTrigger"(id);

--
-- Name: TemplateParamDesc FKtno5jsfoap7pxeaiyaq56e1qd; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."TemplateParamDesc"
    ADD CONSTRAINT "FKtno5jsfoap7pxeaiyaq56e1qd" FOREIGN KEY (entity_id) REFERENCES public."TemplateParam"(id);

--
-- Name: Publication FKygqquydehygqhfka1x2cx0kd; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Publication"
    ADD CONSTRAINT "FKygqquydehygqhfka1x2cx0kd" FOREIGN KEY (template_id) REFERENCES public."Publication"(id);

--
-- PostgreSQL database dump complete
--
