--
-- PostgreSQL database dump
--



SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: postgis; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;


--
-- Name: EXTENSION postgis; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION postgis IS 'PostGIS geometry and geography spatial types and functions';


SET default_tablespace = '';

SET default_table_access_method = heap;

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
    "indexNo" integer DEFAULT 0 NOT NULL,
    path character varying(255),
    type character varying(255),
    width character varying(255),
    message_id integer NOT NULL,
    CONSTRAINT "Attachment_display_check" CHECK (((display)::text = ANY (ARRAY[('ABOVE'::character varying)::text, ('BELOW'::character varying)::text, ('SEPARATE_PAGE'::character varying)::text])))
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
-- Name: BootstrapState; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."BootstrapState" (
    id integer NOT NULL,
    state character varying(32) NOT NULL,
    hash character varying(64),
    error text,
    "baseVersion" character varying(255) NOT NULL,
    updated timestamp(6) with time zone NOT NULL,
    CONSTRAINT "BootstrapState_id_check" CHECK ((id = 1)),
    CONSTRAINT "BootstrapState_state_check" CHECK (((state)::text = ANY (ARRAY[('pending'::character varying)::text, ('existing'::character varying)::text, ('in-progress'::character varying)::text, ('applied'::character varying)::text, ('failed'::character varying)::text])))
);


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
    CONSTRAINT "Category_type_check" CHECK (((type)::text = ANY (ARRAY[('CATEGORY'::character varying)::text, ('TEMPLATE'::character varying)::text])))
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
    "indexNo" integer DEFAULT 0 NOT NULL,
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
-- Name: DefaultsLedger; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."DefaultsLedger" (
    kind character varying(255) NOT NULL,
    key character varying(255) NOT NULL,
    dictionary character varying(255) NOT NULL,
    lang character varying(255) NOT NULL,
    hash character varying(64),
    source character varying(255) NOT NULL,
    "baseVersion" character varying(255) NOT NULL,
    created timestamp(6) with time zone NOT NULL,
    CONSTRAINT "DefaultsLedger_kind_check" CHECK (((kind)::text = ANY (ARRAY[('TEXT'::character varying)::text, ('SETTING'::character varying)::text])))
);


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
-- Name: IssueAuditEntry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."IssueAuditEntry" (
    id integer NOT NULL,
    issue_id integer,
    series_id integer,
    user_id integer,
    created timestamp(6) without time zone NOT NULL,
    "archivePath" character varying(512),
    reason character varying(512),
    action character varying(255) NOT NULL,
    "actorKind" character varying(255) NOT NULL,
    "actorLabel" character varying(255),
    detail text,
    CONSTRAINT "IssueAuditEntry_actorKind_check" CHECK ((("actorKind")::text = ANY (ARRAY[('USER'::character varying)::text, ('SYSTEM'::character varying)::text, ('IMPORT'::character varying)::text])))
);


--
-- Name: IssueAuditEntry_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."IssueAuditEntry_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: IssueMember; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."IssueMember" (
    id integer NOT NULL,
    issue_id integer NOT NULL,
    message_id integer,
    override_id integer,
    "sortIndex" integer NOT NULL,
    "frozenPublishDateFrom" timestamp(6) without time zone,
    "frozenPublishDateTo" timestamp(6) without time zone,
    "messageUid" character varying(36) NOT NULL,
    "sourceIssuePublicId" character varying(36),
    "reasonNote" character varying(512),
    "frozenMainType" character varying(255) NOT NULL,
    "frozenShortId" character varying(255),
    "frozenStatus" character varying(255) NOT NULL,
    "frozenType" character varying(255) NOT NULL,
    source character varying(255) NOT NULL,
    CONSTRAINT "IssueMember_source_check" CHECK (((source)::text = ANY (ARRAY[('CRITERIA'::character varying)::text, ('OVERRIDE_INCLUDE'::character varying)::text, ('IMPORTED'::character varying)::text, ('COMPILED'::character varying)::text])))
);


--
-- Name: IssueMember_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."IssueMember_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: IssueOverride; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."IssueOverride" (
    "appliedAtPublish" boolean,
    author_id integer NOT NULL,
    id integer NOT NULL,
    issue_id integer NOT NULL,
    message_id integer,
    version integer,
    created timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    "messageUid" character varying(36) NOT NULL,
    reason character varying(512) NOT NULL,
    kind character varying(255) NOT NULL,
    CONSTRAINT "IssueOverride_kind_check" CHECK (((kind)::text = ANY (ARRAY[('INCLUDE'::character varying)::text, ('EXCLUDE'::character varying)::text])))
);


--
-- Name: IssueOverride_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."IssueOverride_SEQ"
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
-- Name: MailingListStatusChange; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."MailingListStatusChange" (
    id bigint NOT NULL,
    "messageUid" character varying(36) NOT NULL,
    status character varying(32) NOT NULL,
    created timestamp(6) with time zone NOT NULL,
    "dueAt" timestamp(6) with time zone NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    "lastError" text,
    "settledTriggers" integer[] DEFAULT '{}'::integer[] NOT NULL
);


--
-- Name: MailingListStatusChange_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public."MailingListStatusChange" ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public."MailingListStatusChange_id_seq"
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


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
    CONSTRAINT "MailingListTrigger_scheduleType_check" CHECK ((("scheduleType")::text = ANY (ARRAY[('DAILY'::character varying)::text, ('MONDAY'::character varying)::text, ('TUESDAY'::character varying)::text, ('WEDNESDAY'::character varying)::text, ('THURSDAY'::character varying)::text, ('FRIDAY'::character varying)::text, ('SATURDAY'::character varying)::text, ('SUNDAY'::character varying)::text]))),
    CONSTRAINT "MailingListTrigger_type_check" CHECK (((type)::text = ANY (ARRAY[('STATUS_CHANGE'::character varying)::text, ('SCHEDULED'::character varying)::text])))
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
    CONSTRAINT "MailingListTrigger_statusChanges_statusChanges_check" CHECK ((("statusChanges")::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('VERIFIED'::character varying)::text, ('PUBLISHED'::character varying)::text, ('EXPIRED'::character varying)::text, ('CANCELLED'::character varying)::text, ('DELETED'::character varying)::text])))
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
    kind character varying(32) DEFAULT 'WARNING'::character varying NOT NULL,
    CONSTRAINT "Message_mainType_check" CHECK ((("mainType")::text = ANY (ARRAY[('NW'::character varying)::text, ('NM'::character varying)::text]))),
    CONSTRAINT "Message_status_check" CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('VERIFIED'::character varying)::text, ('PUBLISHED'::character varying)::text, ('EXPIRED'::character varying)::text, ('CANCELLED'::character varying)::text, ('DELETED'::character varying)::text]))),
    CONSTRAINT "Message_type_check" CHECK (((type)::text = ANY (ARRAY[('PERMANENT_NOTICE'::character varying)::text, ('TEMPORARY_NOTICE'::character varying)::text, ('PRELIMINARY_NOTICE'::character varying)::text, ('MISCELLANEOUS_NOTICE'::character varying)::text, ('COASTAL_WARNING'::character varying)::text, ('SUBAREA_WARNING'::character varying)::text, ('NAVAREA_WARNING'::character varying)::text, ('LOCAL_WARNING'::character varying)::text])))
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
    parameters text,
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
    CONSTRAINT "MessageHistory_status_check" CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('VERIFIED'::character varying)::text, ('PUBLISHED'::character varying)::text, ('EXPIRED'::character varying)::text, ('CANCELLED'::character varying)::text, ('DELETED'::character varying)::text])))
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
    "indexNo" integer DEFAULT 0 NOT NULL,
    type character varying(255) NOT NULL,
    geometry_id integer,
    message_id integer NOT NULL,
    CONSTRAINT "MessagePart_type_check" CHECK (((type)::text = ANY (ARRAY[('DETAILS'::character varying)::text, ('TIME'::character varying)::text, ('POSITIONS'::character varying)::text, ('NOTE'::character varying)::text, ('PROHIBITION'::character varying)::text, ('SIGNALS'::character varying)::text])))
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
    CONSTRAINT "MessageSeries_mainType_check" CHECK ((("mainType")::text = ANY (ARRAY[('NW'::character varying)::text, ('NM'::character varying)::text]))),
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
    CONSTRAINT "MessageSeries_types_types_check" CHECK (((types)::text = ANY (ARRAY[('PERMANENT_NOTICE'::character varying)::text, ('TEMPORARY_NOTICE'::character varying)::text, ('PRELIMINARY_NOTICE'::character varying)::text, ('MISCELLANEOUS_NOTICE'::character varying)::text, ('COASTAL_WARNING'::character varying)::text, ('SUBAREA_WARNING'::character varying)::text, ('NAVAREA_WARNING'::character varying)::text, ('LOCAL_WARNING'::character varying)::text])))
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
    CONSTRAINT "MessageTag_type_check" CHECK (((type)::text = ANY (ARRAY[('PRIVATE'::character varying)::text, ('DOMAIN'::character varying)::text, ('PUBLIC'::character varying)::text, ('TEMP'::character varying)::text])))
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
    CONSTRAINT "NavtexMessagePromulgation_priority_check" CHECK (((priority)::text = ANY (ARRAY[('NONE'::character varying)::text, ('ROUTINE'::character varying)::text, ('IMPORTANT'::character varying)::text, ('VITAL'::character varying)::text])))
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
    CONSTRAINT "ParamType_DTYPE_check" CHECK ((("DTYPE")::text = ANY (ARRAY[('CompositeParamType'::character varying)::text, ('ListParamType'::character varying)::text, ('StandardParamType'::character varying)::text])))
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
    CONSTRAINT "PromulgationType_requirement_check" CHECK (((requirement)::text = ANY (ARRAY[('OPTIONAL'::character varying)::text, ('DEFAULT'::character varying)::text, ('MANDATORY'::character varying)::text])))
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
    CONSTRAINT "PromulgationType_messageTypes_messageTypes_check" CHECK ((("messageTypes")::text = ANY (ARRAY[('PERMANENT_NOTICE'::character varying)::text, ('TEMPORARY_NOTICE'::character varying)::text, ('PRELIMINARY_NOTICE'::character varying)::text, ('MISCELLANEOUS_NOTICE'::character varying)::text, ('COASTAL_WARNING'::character varying)::text, ('SUBAREA_WARNING'::character varying)::text, ('NAVAREA_WARNING'::character varying)::text, ('LOCAL_WARNING'::character varying)::text])))
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
    CONSTRAINT "Publication_mainType_check" CHECK ((("mainType")::text = ANY (ARRAY[('PUBLICATION'::character varying)::text, ('TEMPLATE'::character varying)::text]))),
    CONSTRAINT "Publication_messagePublication_check" CHECK ((("messagePublication")::text = ANY (ARRAY[('NONE'::character varying)::text, ('INTERNAL'::character varying)::text, ('EXTERNAL'::character varying)::text]))),
    CONSTRAINT "Publication_periodicalType_check" CHECK ((("periodicalType")::text = ANY (ARRAY[('DAILY'::character varying)::text, ('WEEKLY'::character varying)::text, ('MONTHLY'::character varying)::text, ('YEARLY'::character varying)::text]))),
    CONSTRAINT "Publication_status_check" CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('RECORDING'::character varying)::text, ('ACTIVE'::character varying)::text, ('INACTIVE'::character varying)::text]))),
    CONSTRAINT "Publication_type_check" CHECK (((type)::text = ANY (ARRAY[('NONE'::character varying)::text, ('LINK'::character varying)::text, ('REPOSITORY'::character varying)::text, ('MESSAGE_REPORT'::character varying)::text])))
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
-- Name: PublicationIssue; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PublicationIssue" (
    "cutoffReconstructed" boolean NOT NULL,
    id integer NOT NULL,
    "memberCount" integer NOT NULL,
    "publishedBy_id" integer,
    "retiredBy_id" integer,
    series_id integer NOT NULL,
    "snapshotAliveAtCutoff" boolean,
    supersedes_id integer,
    version integer,
    week integer,
    "weekTo" integer,
    year integer,
    created timestamp(6) without time zone,
    "cutoffStampedAt" timestamp(6) without time zone,
    "intervalFrom" timestamp(6) without time zone,
    "intervalTo" timestamp(6) without time zone,
    "publicFrom" timestamp(6) without time zone,
    "publicTo" timestamp(6) without time zone,
    "publishedAt" timestamp(6) without time zone,
    "retiredAt" timestamp(6) without time zone,
    "snapshotFrozenAt" timestamp(6) without time zone,
    "snapshotIntervalFrom" timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    "workingSince" timestamp(6) without time zone,
    "workingAction" character varying(16),
    "snapshotSortBy" character varying(32),
    "legacyPublicationId" character varying(36),
    "publicId" character varying(36) NOT NULL,
    edition character varying(64),
    "reportId" character varying(64),
    "snapshotSourceSeriesId" character varying(64),
    "weekLabel" character varying(64),
    "yearLabel" character varying(64),
    "repoPath" character varying(128) NOT NULL,
    "retiredReason" character varying(512),
    "criteriaOverride" text,
    "criteriaSnapshot" text,
    "cutoffSource" character varying(255),
    "intervalFromSource" character varying(255),
    "intervalToSource" character varying(255),
    "membershipProvenance" character varying(255),
    "membershipProvenanceNote" text,
    "publicWindowSource" character varying(255) NOT NULL,
    "reportParams" text,
    "snapshotAreaIds" text,
    "snapshotCategoryIds" text,
    "snapshotChartNumbers" text,
    "snapshotDomainId" character varying(255),
    "snapshotMainTypes" character varying(255),
    "snapshotSeriesIds" text,
    "snapshotSortOrder" character varying(255),
    "snapshotSourceIssueIds" text,
    "snapshotTimeRelation" character varying(255),
    status character varying(255) NOT NULL,
    CONSTRAINT "PublicationIssue_intervalFromSource_check" CHECK ((("intervalFromSource")::text = ANY (ARRAY[('STAMPED'::character varying)::text, ('NOMINAL'::character varying)::text, ('RECOVERED'::character varying)::text, ('MANUAL'::character varying)::text]))),
    CONSTRAINT "PublicationIssue_membershipProvenance_check" CHECK ((("membershipProvenance")::text = ANY (ARRAY[('EXACT'::character varying)::text, ('EXPLAINED_DIFF'::character varying)::text, ('UNION_SNAPSHOT'::character varying)::text, ('NO_MEMBERSHIP'::character varying)::text, ('IMPORTED'::character varying)::text, ('COMPILED'::character varying)::text]))),
    CONSTRAINT "PublicationIssue_publicWindowSource_check" CHECK ((("publicWindowSource")::text = ANY (ARRAY[('DERIVED'::character varying)::text, ('MANUAL'::character varying)::text]))),
    CONSTRAINT "PublicationIssue_status_check" CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('PUBLISHED'::character varying)::text, ('RETIRED'::character varying)::text])))
);


--
-- Name: PublicationIssueDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PublicationIssueDesc" (
    entity_id integer,
    "fileNameOverridden" boolean NOT NULL,
    "fileSize" integer,
    "fileSourceSticky" boolean NOT NULL,
    id integer NOT NULL,
    "nameOverridden" boolean NOT NULL,
    "replacedBy_id" integer,
    "fileGeneratedAt" timestamp(6) without time zone,
    "replacedAt" timestamp(6) without time zone,
    "fileHash" character varying(64),
    "filePath" character varying(512),
    "messageReferenceFormat" character varying(512),
    link character varying(1024),
    "fileName" character varying(255),
    "fileSource" character varying(255),
    lang character varying(255),
    name character varying(255) NOT NULL,
    CONSTRAINT "PublicationIssueDesc_fileSource_check" CHECK ((("fileSource")::text = ANY (ARRAY[('GENERATED'::character varying)::text, ('UPLOADED'::character varying)::text])))
);


--
-- Name: PublicationIssueDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PublicationIssueDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: PublicationIssue_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PublicationIssue_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: PublicationSeries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PublicationSeries" (
    "aliveAtCutoff" boolean,
    category_id integer NOT NULL,
    domain_id integer NOT NULL,
    id integer NOT NULL,
    "languageSpecific" boolean NOT NULL,
    "mapThumbnails" boolean,
    "nominalCutoffDayOfMonth" integer,
    "nominalCutoffMonth" integer,
    "sortOrder" integer,
    "sourceSeries_id" integer,
    version integer,
    "nominalCutoffTime" character varying(5),
    created timestamp(6) without time zone,
    "firstIssueStartsAt" timestamp(6) without time zone,
    updated timestamp(6) without time zone,
    "messageSortBy" character varying(32),
    "legacyTemplateId" character varying(36),
    "nominalCutoffTimeZone" character varying(64),
    "reportId" character varying(64),
    "seriesId" character varying(64) NOT NULL,
    availability character varying(255) NOT NULL,
    cadence character varying(255) NOT NULL,
    "contentMode" character varying(255) NOT NULL,
    criteria text,
    "cutoffDefault" character varying(255) NOT NULL,
    "importSource" character varying(255),
    kind character varying(255) NOT NULL,
    "messagePublication" character varying(255) NOT NULL,
    "messageSortOrder" character varying(255),
    "nextIssueCreation" character varying(255) NOT NULL,
    "nominalCutoffDay" character varying(255),
    "numberingScheme" character varying(255) NOT NULL,
    "pageOrientation" character varying(255),
    "pageSize" character varying(255),
    "releaseMode" character varying(255) NOT NULL,
    "reportParams" text,
    status character varying(255) NOT NULL,
    "timeRelation" character varying(255),
    CONSTRAINT "PublicationSeries_availability_check" CHECK (((availability)::text = ANY (ARRAY[('OWNER_ONLY'::character varying)::text, ('SELECTED_DOMAINS'::character varying)::text, ('ALL_DOMAINS'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_cadence_check" CHECK (((cadence)::text = ANY (ARRAY[('NONE'::character varying)::text, ('DAILY'::character varying)::text, ('WEEKLY'::character varying)::text, ('MONTHLY'::character varying)::text, ('YEARLY'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_contentMode_check" CHECK ((("contentMode")::text = ANY (ARRAY[('GENERATED_FROM_QUERY'::character varying)::text, ('UPLOADED_FILE'::character varying)::text, ('EXTERNAL_LINK'::character varying)::text, ('NONE'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_cutoffDefault_check" CHECK ((("cutoffDefault")::text = ANY (ARRAY[('RELEASE_MOMENT'::character varying)::text, ('PERIOD_START'::character varying)::text, ('PERIOD_END'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_kind_check" CHECK (((kind)::text = ANY (ARRAY[('SCHEDULED'::character varying)::text, ('UNSCHEDULED'::character varying)::text, ('ONE_OFF'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_messagePublication_check" CHECK ((("messagePublication")::text = ANY (ARRAY[('NONE'::character varying)::text, ('INTERNAL'::character varying)::text, ('EXTERNAL'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_messageSortOrder_check" CHECK ((("messageSortOrder")::text = ANY (ARRAY[('ASC'::character varying)::text, ('DESC'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_nextIssueCreation_check" CHECK ((("nextIssueCreation")::text = ANY (ARRAY[('AUTO_ON_PUBLISH'::character varying)::text, ('MANUAL'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_nominalCutoffDay_check" CHECK ((("nominalCutoffDay")::text = ANY (ARRAY[('MONDAY'::character varying)::text, ('TUESDAY'::character varying)::text, ('WEDNESDAY'::character varying)::text, ('THURSDAY'::character varying)::text, ('FRIDAY'::character varying)::text, ('SATURDAY'::character varying)::text, ('SUNDAY'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_numberingScheme_check" CHECK ((("numberingScheme")::text = ANY (ARRAY[('ISO_WEEK_YEAR'::character varying)::text, ('YEAR_EDITION'::character varying)::text, ('MONTH_YEAR'::character varying)::text, ('EDITION_SEQUENCE'::character varying)::text, ('NONE'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_pageOrientation_check" CHECK ((("pageOrientation")::text = ANY (ARRAY[('PORTRAIT'::character varying)::text, ('LANDSCAPE'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_pageSize_check" CHECK ((("pageSize")::text = ANY (ARRAY[('A3'::character varying)::text, ('A4'::character varying)::text, ('A5'::character varying)::text, ('B4'::character varying)::text, ('B5'::character varying)::text, ('LETTER'::character varying)::text, ('LEGAL'::character varying)::text, ('LEDGER'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_releaseMode_check" CHECK ((("releaseMode")::text = ANY (ARRAY[('MANUAL_GATE'::character varying)::text, ('AUTO_RELEASE'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_status_check" CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('ACTIVE'::character varying)::text, ('RETIRED'::character varying)::text]))),
    CONSTRAINT "PublicationSeries_timeRelation_check" CHECK ((("timeRelation")::text = ANY (ARRAY[('PUBLISHED_IN_INTERVAL'::character varying)::text, ('IN_FORCE_AT_CUTOFF'::character varying)::text, ('COMPILED_FROM_SOURCE'::character varying)::text])))
);


--
-- Name: PublicationSeriesDesc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PublicationSeriesDesc" (
    entity_id integer,
    id integer NOT NULL,
    "messageReferenceFormat" character varying(512),
    "linkPattern" character varying(1024),
    "fileNamePattern" character varying(255),
    lang character varying(255),
    name character varying(255) NOT NULL,
    "nameSuggestionPattern" character varying(255)
);


--
-- Name: PublicationSeriesDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PublicationSeriesDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: PublicationSeries_AvailableDomain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PublicationSeries_AvailableDomain" (
    domain_id integer NOT NULL,
    series_id integer NOT NULL
);


--
-- Name: PublicationSeries_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PublicationSeries_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: PublicationSeries_languages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PublicationSeries_languages" (
    "PublicationSeries_id" integer NOT NULL,
    "indexNo" integer NOT NULL,
    languages character varying(255)
);


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
    "indexNo" integer DEFAULT 0 NOT NULL,
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
-- Name: RepoFile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."RepoFile" (
    path character varying(1024) NOT NULL COLLATE pg_catalog."C",
    folder character varying(1024) NOT NULL,
    name character varying(255) NOT NULL,
    size bigint NOT NULL,
    sha256 character varying(64),
    created timestamp(6) with time zone NOT NULL,
    updated timestamp(6) with time zone NOT NULL,
    expires timestamp(6) with time zone,
    data bytea NOT NULL
);
ALTER TABLE ONLY public."RepoFile" ALTER COLUMN data SET STORAGE EXTERNAL;


--
-- Name: S124Dataset; Type: TABLE; Schema: public; Owner: -
--

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


--
-- Name: S124DatasetVersion; Type: TABLE; Schema: public; Owner: -
--

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
    languages character varying(64) NOT NULL,
    created timestamp(6) without time zone NOT NULL
);


--
-- Name: S124DatasetVersion_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."S124DatasetVersion_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: S124Dataset_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."S124Dataset_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: S124SeriesConfig; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."S124SeriesConfig" (
    id integer NOT NULL,
    series_id integer NOT NULL,
    "exposeOverSecom" boolean NOT NULL,
    languages character varying(64)
);


--
-- Name: S124SeriesConfig_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."S124SeriesConfig_SEQ"
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
    CONSTRAINT "SafetyNetArea_DTYPE_check" CHECK ((("DTYPE")::text = ANY (ARRAY[('SafetyNetCoastalArea'::character varying)::text, ('SafetyNetCircularArea'::character varying)::text, ('SafetyNetNavareaArea'::character varying)::text, ('SafetyNetRectangularArea'::character varying)::text]))),
    CONSTRAINT "SafetyNetArea_coastalArea_check" CHECK ((("coastalArea")::text = ANY (ARRAY[('A'::character varying)::text, ('B'::character varying)::text, ('C'::character varying)::text, ('D'::character varying)::text, ('E'::character varying)::text, ('F'::character varying)::text, ('G'::character varying)::text, ('H'::character varying)::text, ('I'::character varying)::text, ('J'::character varying)::text, ('K'::character varying)::text, ('L'::character varying)::text, ('M'::character varying)::text, ('N'::character varying)::text, ('O'::character varying)::text, ('P'::character varying)::text, ('Q'::character varying)::text, ('R'::character varying)::text, ('S'::character varying)::text, ('T'::character varying)::text, ('U'::character varying)::text, ('V'::character varying)::text, ('W'::character varying)::text, ('X'::character varying)::text, ('Y'::character varying)::text, ('Z'::character varying)::text]))),
    CONSTRAINT "SafetyNetArea_navarea_check" CHECK (((navarea)::text = ANY (ARRAY[('I'::character varying)::text, ('II'::character varying)::text, ('III'::character varying)::text, ('IV'::character varying)::text, ('V'::character varying)::text, ('VI'::character varying)::text, ('VII'::character varying)::text, ('VIII'::character varying)::text, ('IX'::character varying)::text, ('X'::character varying)::text, ('XI'::character varying)::text, ('XII'::character varying)::text, ('XIII'::character varying)::text, ('XIV'::character varying)::text, ('XV'::character varying)::text, ('XVI'::character varying)::text, ('XVII'::character varying)::text, ('XVIII'::character varying)::text, ('XIX'::character varying)::text, ('XX'::character varying)::text, ('XXI'::character varying)::text]))),
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
    CONSTRAINT "SafetyNetMessagePromulgation_priority_check" CHECK (((priority)::text = ANY (ARRAY[('SAFETY'::character varying)::text, ('URGENCY'::character varying)::text, ('DISTRESS'::character varying)::text])))
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
    "sentAgainAt" timestamp(6) without time zone,
    "sentAgainBy" character varying(255),
    CONSTRAINT "ScheduledMail_status_check" CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SENT'::character varying, 'HELD'::character varying, 'ERROR'::character varying])::text[])))
);


--
-- Name: ScheduledMailRecipient; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ScheduledMailRecipient" (
    id integer NOT NULL,
    address character varying(255) NOT NULL,
    "recipientType" character varying(255) NOT NULL,
    mail_id integer NOT NULL,
    CONSTRAINT "ScheduledMailRecipient_recipientType_check" CHECK ((("recipientType")::text = ANY (ARRAY[('TO'::character varying)::text, ('CC'::character varying)::text, ('BCC'::character varying)::text])))
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
    CONSTRAINT "ScriptResource_type_check" CHECK (((type)::text = ANY (ARRAY[('FM'::character varying)::text, ('JS'::character varying)::text])))
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
    user_id integer,
    source character varying(32)
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
-- Name: SecomNode; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."SecomNode" (
    id integer NOT NULL,
    mrn character varying(256) NOT NULL,
    "lastInteraction" timestamp(6) without time zone,
    created timestamp(6) without time zone NOT NULL
);


--
-- Name: SecomNode_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."SecomNode_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: SecomOutboxEntry; Type: TABLE; Schema: public; Owner: -
--

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


--
-- Name: SecomOutboxEntry_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."SecomOutboxEntry_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: SecomSubscription; Type: TABLE; Schema: public; Owner: -
--

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


--
-- Name: SecomSubscription_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."SecomSubscription_SEQ"
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
    CONSTRAINT "Setting_type_check" CHECK (((type)::text = ANY (ARRAY[('String'::character varying)::text, ('Password'::character varying)::text, ('Integer'::character varying)::text, ('Long'::character varying)::text, ('Float'::character varying)::text, ('Double'::character varying)::text, ('Date'::character varying)::text, ('Boolean'::character varying)::text, ('Path'::character varying)::text, ('json'::character varying)::text])))
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
-- Name: databasechangelog; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.databasechangelog (
    id character varying(255) NOT NULL,
    author character varying(255) NOT NULL,
    filename character varying(255) NOT NULL,
    dateexecuted timestamp without time zone NOT NULL,
    orderexecuted integer NOT NULL,
    exectype character varying(10) NOT NULL,
    md5sum character varying(35),
    description character varying(255),
    comments character varying(255),
    tag character varying(255),
    liquibase character varying(20),
    contexts character varying(255),
    labels character varying(255),
    deployment_id character varying(10)
);


--
-- Name: databasechangeloglock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.databasechangeloglock (
    id integer NOT NULL,
    locked boolean NOT NULL,
    lockgranted timestamp without time zone,
    lockedby character varying(255)
);


--
-- Name: message_search_text; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message_search_text (
    message_id bigint NOT NULL,
    lang character varying(8) NOT NULL,
    search_tsv tsvector,
    msg_updated timestamp without time zone
);


--
-- Data for Name: Area; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Area" (id, created, updated, version, active, lineage, "siblingSortOrder", "treeSortOrder", geometry, "legacyId", "messageSorting", mrn, "originAngle", "originLatitude", "originLongitude", type, parent_id) FROM stdin;
1000001	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/	0	1	0106000000020000000103000000010000008E00000080A48CE839D72240B09B1E237F6A4B40408BA04376D72240B8E005B1766A4B4000F24BCF84DB224040464662DD694B4020FF3DE0D0DE224078668908DE694B4040BACDD250E6224048ACE56BA26A4B40815BF6934DEB224070753121B46A4B40407ECBB012ED22402833B8FC736A4B4020B7D023B3FE2240080692E2A96B4B406014E535232B234084A1576E6F714B40A0681B29A22E2340702A45A486714B40C147C26D56412340B844F4F995704B40606CE964B8352340300F897D6D6D4B40C13BEEEC0F7B2340E82994BA61694B4020F5BC6799C923408028409DBD6B4B40DFE92837EC1A24403493C6DAF2614B40C08617F35C6624407875C56130614B402043CA7A2A6F2440E4916201C5604B40A00232B80989244090499380485B4B40400E8F203C9D2440B00DA2434D564B40FFD0D04933B524404CDC81ACFA504B40205EB2D30ABF244040CB11A4654F4B4080A2D3B60BE82440E829924C9A4C4B40E1BAA9F53C10254068227B10C4494B4020527396B2392540ACD9817587464B4020AFACFA7D51254018CAC9FE98454B4041048E71B676254088819295EE454B40C059386749992540E082A8724F4A4B40C03A0F54B1BE25403074198F364F4B40FF8670B05DCE254028963AB980514B40C0859ACD5502264070E8E96DFD514B40601F58C0CA252640E414713590514B40C0E8DB243450264010C14A088D4F4B4040C39630306A264094F2D9F5E34D4B40E0D1B12A92842640F0038266FB4A4B4040C2139B0BA4264090F4A8E6DC484B409FE0792858C42640C092E2B813444B4020FB34D077E2264048F779E7AB3F4B400056DC137F06274018D40F3B6A3B4B406046FB1D5F29274008CD0131F2364B4060C979A667452740507F416843324B409FB01F71AE5727407871408ABB2E4B40807B5CFDD3562740704BE0809D2E4B4040BF0053EAE02740C0A5B73ADE2E4B40806E5F7CDB38284010F0401189344B40C04BC77B59E22840008DB104F3574B404051AAE2657F2940B04CCADAAF614B4060D1AB1D9FDD294068AEF2C2A56A4B401F3951733B4B2A403867CBC6FE804B40E1963231DC1B2A40D039ACCE3C884B4081C4CB61649629409C49BC2541954B400049E9BD245A294074AEECE5EB9F4B4040AB87E183462940F099D36F63A74B400075494414482940E0159CEF17AB4B40209101EA17482940E0159CEF17AB4B40E0F9882305382940F4CFE3CF2EB74B406073E9D49F6D2940887CF32F8ABE4B4020BB287AE074294080B7B24427C54B408073C8A5A8C22940F0864C7D89CE4B40E0B9C758C1C92940F0B36B9160D24B40001E676005B129400056BE0973D94B40C0F60360346A29401CF52327CEE94B402075414521672940DC7B5C4E9BEA4B40E04B6B9EE664294050633D8088EB4B40C0C38910A064294008E5F3E0E7EB4B40A0902B3C02642940E8F17D0946EC4B40A0426BB5EF62294008C69692A3ED4B4080072B675261294008EDDDA528EF4B4060EBC499EA5F294010FE3DCCECEF4B4060E48B35DD5C2940C85425F75FF14B40406BCA64245B2940B8AFDCD522F24B406069841E34592940087D3CA3D6F24B4060B805E84A532940D815353023F44B402072C7AA274E294058867E618AF54B4020BDE118AB4B294074019EAA3EF64B4000D28941B64A2940E8106D71D4F64B4080FAAC65444B29406079AC60EEF84B4020598834484D2940B0578E864FFB4B40E1ED8623FE4D29407045166171FE4B4060ED27F1264E294020164DAEBC004C40A00BAAF4B04F294008BC4CE79F024C4021252F721C50294088D347226C034C40405E082052502940909D57AC49044C40203BC6F40850294098EF6C3E17054C40BF4005D1A44C2940305987A36B064C404045A550974929400CA8C6713E074C40C04C62D1F3422940F00F1F3B1C084C40804A25F5CB3B294040F16DAB2D094C40E0CAE5887A2A2940540A85CBD40B4C40C069246AEB1F29406C7F35AE4F0D4C4021BB04210515294088DE3798AF0E4C402140E76B1F0C2940188F8768BB0F4C40E0FB4078080229404C2CFEF6C2104C40003A65453FFC2840AC04E73370114C4020F5862B02F728407CC7DD1829124C4000F3C08343F02840407457CFF9124C40411A059D1DEA2840600C0D7AA5134C40C0B4C85A69E42840BC4B468E67144C40C0ADA74935E0284088A9A6F139154C40A0C5C37655DC2840AC56681825164C40E0BF641D6DD828400442A7C4CE164C40E0FF7FC4F8D0284090957E80FD174C408064266ACCC628401811FFFCB3194C400045201048C32840D888EE33501A4C40C06C5EC376BF2840B886BD16151B4C40002D079BB6BC284030E2254C591B4C4080585E154F2C2840F8AB0EC0D3264C4020AFBBB80E4B2840B0212F7313414C40006D37B7C5CB2640A87CE39987B94C400089B4AE4D182640A80BC34A7CE84C40A025CAE30B152540AC402B7200114D40206FD472B60E2440685B57E364214D4080350BBEAD0E244070E19B6E65214D40A0E6BC5791C322403009D48B6FFE4C404087008720C62140501DE3AB19D94C40C02DD971F6E920405C191B9412CF4C407FBDA37084ED1F40B8383215B6BF4C408027C94FB7BD1B40DCAA894A52964C40C07E168DAD20144090E19F29A64B4C40FF61521E0BFD0940980156FD000B4C40FF7356E8DCC90A40A07ADED993F54B4040FD10588FFE10402C67BDBFD1E24B40C184CD7F96FE1240CCD593BCA4B34B408083D946349814404C17D300E99F4B4080311AB59F9B154050C0F51CE99F4B4040C56BD89BFE16404CF8AB5B58C14B40C066987F99341E40F85693305E954B4060814D29F19920407009736718884B40E0605076989A2040E411DB6B2E884B40A00B7BDF9AA62040FC271A39C5874B4000D9F771CCC6204068AB7AE2C1884B40E04855C4FDF12040BC91531DED864B40208E593DFAF42040E05ADBEB0F864B40201BF7F0A9F12040E8B9331D3E834B40C05AD82EC80021406C2C4B88FA7F4B4060E3D7AF3E0921404080D2DE0D7F4B4080C870B6651D21403060036E127F4B4000A8D607011C21400029EB83E1754B40E00CE64C664521406C27B74BAD744B4000F1C4CA6D582140488FE200A0744B40C05966C5FB6921402CBF3C6C7B744B40009E60253DD72240F0B2CA127E6A4B4080A48CE839D72240B09B1E237F6A4B40010300000001000000190000002079CC1FD78130405CA9CB8F63AD4B40C0ED2BB1E0773040A8A6CB9F85A84B40D0EF18B15E6430406878D3CA109F4B4050FD0A13F251304030AC946B789A4B40C0E168159D443040B0EE82A228964B4030B3CBCEDA3830401CE10B057F914B40B02A867B4A02304058840BE1E7794B4060BA8E2738DE2F40303E027546714B4040206C92CE752F401862217FD85A4B4040E6692BBC2B2F4064AE4830BB534B40408BACB614EA2E408845D9B6034F4B404075A7E095992E4060FFB7F7C84A4B401FA24C08F5362E4000C918E5BA474B40C018238993282E402048C19A5D474B40A0E104C228E42D40C83CA12DA0464B40E10362C3E7962D40A05F9A105D454B4061645CC771452D4020FAC1D58E444B40A0C462A67DD32C409095D93930544B409F659BFD7CD32C4008CD3221EC674B401F437DC8E4572C40F43FB30AEC674B40202659BC95FC2B40103F9E81447B4B40202B99F994FC2B40E8B7E1AA447B4B4040FBBE7DF2EA2C4040300A99E3A74B40204FA26A63152E40880F446770D84B402079CC1FD78130405CA9CB8F63AD4B40	\N	\N	urn:mrn:iho:country:dk	\N	\N	\N	0	\N
1000002	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000002/	10	2	010600000002000000010300000001000000870000007A46F0F8DD0A2440C83F6C23E2634B40600C502794D8244088B849E479684B40E0F56CE628D82440604F74CA146A4B40FFEF515EAFCE2440C06B274F7B6B4B40A08511D73E042540E8CEF42EF86C4B400053D1626D082540287B58D03D6C4B40DF092FE2B70A254070BBCB100A6C4B40A04980C0210B2540EC0F9EBA066C4B4020CFAE36570C254090099BE8106C4B40A06693A0180D254098C938FF1C6C4B400031D58FF70D25405869EA93216C4B40A0FA7F0A840E2540E895DDF9296C4B40E0BF5415150F2540305F81FC2D6C4B40C0FC3ED2960F254040FA83743D6C4B40E071CC5E2810254068E420794B6C4B4080199DCB8B102540ACE85E13516C4B40C07A9529A41125408408AEB5616C4B4080A569ED09132540145D3AFA606C4B4060DF0F4C29312540649959E6556B4B40BF61FA89C3312540506EA5BB516B4B40E09B7C4DEB322540D8A3EA70D76A4B404008764D5F3325407C463685C56A4B401F9FCF220955254094C9F99A59684B40612669107C5E2540EC128883F6694B4000D66F42FB622540D09C195D5B5C4B4081C55869FCFF2540683D7B4F21624B40A048A3BB510F26407039A5C390624B40A0B76B6FDB1C2640301851B7CC604B40E0BB57E5F64C264010DBE1DFC25E4B4021E81D32DB862640482B29AA505C4B405FABEC7EBFC02640F0106A4C29544B40E1D3CBCC25EB2640382AAECC49504B404039E14445FC26405C72B26024534B401F024F27C721274028226B9696554B401FC7D50ACB3727406807FE5267564B40E03B152441502740184ED92333564B4060C90473296B274028226B9696554B4080B43B3C3578274078DB8FC5CA554B40418E8B562D81274040CA8E7B5D544B40800E0E127C912740F0106A4C29544B4000266FC2B5942740700724AD6D554B404021C93A8999274020713B139E524B4041001F33859D2740687419014C524B4040FD9F98FC9D274010CDE82635524B40A0BFD8DB63C2274030F942AFDC514B406088440490CA27407043C337D4524B4060232900FECA27402CFC98CAF3524B40C03AD45E60CB2740E0A78CE782514B4060857C9350DD2740804E87093B4D4B4000E5EDFE34E2274064E5A9A4944A4B407FD9AA5C5CEA2740182C8575604A4B4080445F6A19E72740D0D3FA741F524B4080445F6A19E727406807FE5267564B40DFA974E238F82740F871047B1C5C4B403FA8BC4B191C2840488CC3D843644B40C0CCB49A01372840404D7D5823684B40E11F2067B3382840407BB141F0694B402051D516344A2840E84CDD79486B4B4040C7DB59DA542840F0E7730CCD6A4B40C06C28F47B602840D0C4999E316A4B4040BA22F6DE73284088FF80E33F6B4B40A09664409D762840A8FA0992F76D4B40FFB34011446A2840D4CFCA79E3704B4060D694EA5667284098C4D143D7734B40FFB48FD898842840687DB101D5784B40E0003B7118AB2840A868B793517C4B4020F60F3144D92840A868B793517C4B40C003823B7B04294010BF6AEB7B7A4B40E057B004871129407057B649E47A4B40A0721D48B61029404CDB00F2B97C4B40400921D118F0284084814D2E6A814B40E0F123BB28A92840C466E0EA3A824B4001863715F0932840802D3F758E834B4080E1EDB35D882840601EF236B9864B40E02FC3B965892840A8ED73AA6C874B40E088A33667942840C0209AFAB28C4B40C02B07368B89284058D932DEAB904B40E0326161AC462840008BBB0290964B40A0A89FFD69432840484323CBCE904B407F60C0E6AA4028406041A691A38D4B40C015E6E4153D284020F3A4338D8D4B40E05B5C80092E2840D0675BC8418E4B40C1484BE03417284030F5116AD98D4B40A0C8C824E6062840B8BF37E37A8F4B403F2453914CFC274060C7CC3326934B40E16C3EB87F112840B820D2116E974B4080E721DE3036284048BF89FD72984B4060C171F8283F2840C0C0B2C0819B4B40C024CA628B53284038B746C7BF9D4B40C09DF5F11C9C28403C4C92B902A14B40807B2C0B93B42840307800479FA14B404085B7164CD02840B84226C040A34B40413FA185A1D628402086924078A54B4000BD821154DD28406047952EEBA44B40000341C7E1E8284060AD5CDA36A54B408DF6F65F10482940B85836B80EAB4B4040AB87E183462940F099D36F63A74B400049E9BD245A294074AEECE5EB9F4B4081C4CB61649629409C49BC2541954B40E1963231DC1B2A40D039ACCE3C884B401F3951733B4B2A403867CBC6FE804B4060D1AB1D9FDD294068AEF2C2A56A4B404051AAE2657F2940B04CCADAAF614B40C04BC77B59E22840008DB104F3574B40806E5F7CDB38284010F0401189344B4040BF0053EAE02740C0A5B73ADE2E4B40807B5CFDD3562740704BE0809D2E4B409FB01F71AE5727407871408ABB2E4B4060C979A667452740507F416843324B406046FB1D5F29274008CD0131F2364B400056DC137F06274018D40F3B6A3B4B4020FB34D077E2264048F779E7AB3F4B409FE0792858C42640C092E2B813444B4040C2139B0BA4264090F4A8E6DC484B40E0D1B12A92842640F0038266FB4A4B4040C39630306A264094F2D9F5E34D4B40C0E8DB243450264010C14A088D4F4B40601F58C0CA252640E414713590514B40C0859ACD5502264070E8E96DFD514B40FF8670B05DCE254028963AB980514B40C03A0F54B1BE25403074198F364F4B40C059386749992540E082A8724F4A4B4041048E71B676254088819295EE454B4020AFACFA7D51254018CAC9FE98454B4020527396B2392540ACD9817587464B40E1BAA9F53C10254068227B10C4494B4080A2D3B60BE82440E829924C9A4C4B40205EB2D30ABF244040CB11A4654F4B40FFD0D04933B524404CDC81ACFA504B40400E8F203C9D2440B00DA2434D564B40A00232B80989244090499380485B4B402043CA7A2A6F2440E4916201C5604B40C08617F35C6624407875C56130614B40DFE92837EC1A24403493C6DAF2614B407A46F0F8DD0A2440C83F6C23E2634B40010300000002000000190000002079CC1FD78130405CA9CB8F63AD4B40C0ED2BB1E0773040A8A6CB9F85A84B40D0EF18B15E6430406878D3CA109F4B4050FD0A13F251304030AC946B789A4B40C0E168159D443040B0EE82A228964B4030B3CBCEDA3830401CE10B057F914B40B02A867B4A02304058840BE1E7794B4060BA8E2738DE2F40303E027546714B4040206C92CE752F401862217FD85A4B4040E6692BBC2B2F4064AE4830BB534B40408BACB614EA2E408845D9B6034F4B404075A7E095992E4060FFB7F7C84A4B401FA24C08F5362E4000C918E5BA474B40C018238993282E402048C19A5D474B40A0E104C228E42D40C83CA12DA0464B40E10362C3E7962D40A05F9A105D454B4061645CC771452D4020FAC1D58E444B40A0C462A67DD32C409095D93930544B409F659BFD7CD32C4008CD3221EC674B401F437DC8E4572C40F43FB30AEC674B40202659BC95FC2B40103F9E81447B4B40202B99F994FC2B40E8B7E1AA447B4B4040FBBE7DF2EA2C4040300A99E3A74B40204FA26A63152E40880F446770D84B402079CC1FD78130405CA9CB8F63AD4B4008000000603E29ACB77C2D40E8BDF60EAF984B406022A11EBC712D400098E64E178D4B40C0302B8BF7F72D402C6FB03FA2834B403FDC7BF08E272E401872D68E7F814B40013CACD5F63F2E40208B38CD9D8E4B408081BA11AEDA2D4008AAE6A976974B405F46335B218A2D407895B671F19E4B40603E29ACB77C2D40E8BDF60EAF984B40	\N	4	\N	180	54.5	12	\N	1000001
1000003	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000002/1000003/	0	2	0103000000010000000500000068226C787A352C405555555555754B40610D2A84E8532B405555555555754B40610D2A84E8532B4066F7E461A18A4B40E5174B7EB1042C409A99999999894B4068226C787A352C405555555555754B40	\N	\N	urn:mrn:iho:fa:dk:es-d-140	\N	\N	\N	1	1000002
1000004	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000002/1000004/	1	2	010300000001000000050000004D3AB7F62BAA2F40CC13F56F84AA4B40D069039D36802E4051DA1B7C61864B404341168F787F2E40F9A067B3EABF4B408D03C2617CAB2F4003BF33C7DFBF4B404D3AB7F62BAA2F40CC13F56F84AA4B40	\N	\N	urn:mrn:iho:fa:dk:es-d-138	\N	\N	\N	1	1000002
1000005	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000002/1000005/	2	2	010300000001000000050000008F2E497855AA2F40B253994F43754B402FB8F9593F802E407E1D386744754B402FB8F9593F802E40CAC342AD69864B404D3AB7F62BAA2F40CC13F56F84AA4B408F2E497855AA2F40B253994F43754B40	\N	\N	urn:mrn:iho:fa:dk:es-d-139	\N	\N	\N	1	1000002
1000006	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000002/1000006/	3	2	01030000000100000007000000EB98E0C13C212E402A15671F3E774B40A2B437F8C2D42D40A142883E32744B40A97F2394D18B2D4095F9341464794B4027E7B153997F2D40330E0887F1814B40C545DB1EAAC62D40D93A4BA3DC844B40671F3EC3AE102E4033585EDF74804B40EB98E0C13C212E402A15671F3E774B40	\N	\N	urn:mrn:iho:fa:dk:ek-d-395	\N	\N	\N	1	1000002
1000007	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000002/1000007/	4	2	0103000000010000000600000068226C787AF52D403D0AD7A3707D4B403C4ED1915CCE2D40A142883E327C4B401B2FDD2406B12D40B921B3A01D814B40D0B02B40EED52D405CFB154522834B409AE3EFF11CE42D40339F86822C824B4068226C787AF52D403D0AD7A3707D4B40	\N	\N	urn:mrn:iho:fa:dk:ek-d-396	\N	\N	\N	1	1000002
1000008	2026-10-22 00:00:00	2026-10-22 00:00:00	3	t	/1000001/1000002/1000008/	31	2	01030000000100000006000000ABAAAAAAAA2A25407777777777574B40986E1283C02A254003BF33C7DF4B4B406666666666D62440A089B0E1E9514B401BE8B4814E9B24400000000000604B405555555555D524400000000000604B40ABAAAAAAAA2A25407777777777574B40	\N	\N	urn:mrn:iho:fa:dk:ek-d-371	\N	\N	\N	1	1000002
1000009	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000009/	20	3	0103000000010000004D00000000C0BB8DB4D4284028D91BD0A0A54B404085B7164CD0284058160A4D9CAB4B40E01BBB9FAEAF28402CA5E9B142AE4B407F9B0E16AE522840705FAC8143B54B4001256DADA65A2840CCA7AF3D6CC34B40C067818A6B6D284010C461BF60C54B40E07D73CB83982840782F842AE2CD4B40807C06EB37D1284090692C2249D14B40202A90973DF32840B08346C78DD34B40004C6A33111C294008B5C03AA8D84B40004C6A33111C29403029112A76DF4B40008710D194FE2840101D2A7C2CF24B40008710D194FE2840688B06BE1CFA4B40A057528ECC19294008F1B47BC0034C409FF056DA05D82840E830D12005064C4020FE3A81FA9328403882D9E6410C4C4020F38222F2822840ACA6D259EA094C40204B1847918328405848CB7EA60C4C40DF3E6562DE8B2840148A97BB57104C40EC5B9D97C8BA2840C884569C801B4C40002D079BB6BC284030E2254C591B4C40C06C5EC376BF2840B886BD16151B4C400045201048C32840D888EE33501A4C408064266ACCC628401811FFFCB3194C40E0FF7FC4F8D0284090957E80FD174C40E0BF641D6DD828400442A7C4CE164C40A0C5C37655DC2840AC56681825164C40C0ADA74935E0284088A9A6F139154C40C0B4C85A69E42840BC4B468E67144C40411A059D1DEA2840600C0D7AA5134C4000F3C08343F02840407457CFF9124C4020F5862B02F728407CC7DD1829124C40003A65453FFC2840AC04E73370114C40E0FB4078080229404C2CFEF6C2104C402140E76B1F0C2940188F8768BB0F4C4021BB04210515294088DE3798AF0E4C40C069246AEB1F29406C7F35AE4F0D4C40E0CAE5887A2A2940540A85CBD40B4C40804A25F5CB3B294040F16DAB2D094C40C04C62D1F3422940F00F1F3B1C084C404045A550974929400CA8C6713E074C40BF4005D1A44C2940305987A36B064C40203BC6F40850294098EF6C3E17054C40405E082052502940909D57AC49044C4021252F721C50294088D347226C034C40A00BAAF4B04F294008BC4CE79F024C4060ED27F1264E294020164DAEBC004C40E1ED8623FE4D29407045166171FE4B4020598834484D2940B0578E864FFB4B4080FAAC65444B29406079AC60EEF84B4000D28941B64A2940E8106D71D4F64B4020BDE118AB4B294074019EAA3EF64B402072C7AA274E294058867E618AF54B4060B805E84A532940D815353023F44B406069841E34592940087D3CA3D6F24B40406BCA64245B2940B8AFDCD522F24B4060E48B35DD5C2940C85425F75FF14B4060EBC499EA5F294010FE3DCCECEF4B4080072B675261294008EDDDA528EF4B40A0426BB5EF62294008C69692A3ED4B40A0902B3C02642940E8F17D0946EC4B40C0C38910A064294008E5F3E0E7EB4B40E04B6B9EE664294050633D8088EB4B402075414521672940DC7B5C4E9BEA4B40C0F60360346A29401CF52327CEE94B40001E676005B129400056BE0973D94B40E0B9C758C1C92940F0B36B9160D24B408073C8A5A8C22940F0864C7D89CE4B4020BB287AE074294080B7B24427C54B406073E9D49F6D2940887CF32F8ABE4B40E0F9882305382940F4CFE3CF2EB74B40209101EA17482940E0159CEF17AB4B400075494414482940E0159CEF17AB4B408DF6F65F10482940B85836B80EAB4B40000341C7E1E8284060AD5CDA36A54B4000BD821154DD28406047952EEBA44B4000C0BB8DB4D4284028D91BD0A0A54B40	\N	0	\N	\N	\N	\N	\N	1000001
1000010	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000010/	30	4	0103000000010000007A000000004A7CA2768B28400006503374794B40C043231CD68228407057B649E47A4B4000FF1EF4207D28407057B649E47A4B408099097C016C28407057B649E47A4B406045DBB2F55E284010BF6AEB7B7A4B40C0FA327E054D2840BC0546BC477A4B40A06B8B98FD5528408020B3FF76794B406056BDEA6B422840E8ECAF212F754B40C08F7E7048502840504EF8352A744B400075112D195128401832622FEC714B408084DEF2C3682840C41F80FF66724B40FFB34011446A2840D4CFCA79E3704B40A09664409D762840A8FA0992F76D4B4040BA22F6DE73284088FF80E33F6B4B40C06C28F47B602840D0C4999E316A4B4040C7DB59DA542840F0E7730CCD6A4B402051D516344A2840E84CDD79486B4B40C09C64C26746284030D260C7FD6A4B401FDCDE3B583E2840F45FA7659F6C4B404097DA13A3382840A43BCE28AE6F4B40A0E17DD1F5292840F8F4F257E26F4B4020BD85820D0F284098673CB64A704B4060DF4E6997F6274080CAAD8D54724B40A16EF64E9FED274028D242DEFF754B40A06A0F5021DE274018FEB06B9C764B40E0250B286CD82740B0DBAED7C1734B4000F6CF36ABC527400095D306F6734B40606CC7E640BA2740082A1FF938774B40806623DA05AE2740D0D9D72EAB794B40A0F787CDCAA1274018FEB06B9C764B400003CB6FA3992740B870FAC904774B40A09DB5F7838827408020B3FF76794B4000A9F8995C802740F8558D86D5774B40604D6E2DF68A2740788B670D34764B406023D7487084274070F61B1BF1724B402068DB70258A2740B80F609B116F4B4040E85D2C749A2740D4B78380D86D4B40E006B26E21A92740C04EA61B326B4B4020C2AD466CA32740A0DA33FABA674B400020671B31CC2740F032BEFAFB5F4B404070AEE5BEC92740C8F54E23F25D4B40A0D5C35DDEDA2740482B29AA505C4B4080EA8C94D2CD27403C8B48FB3C584B40A0103D7ADAC42740509E20EEC0534B40E0B286A932C92740BC354EE2AA524B40A0BFD8DB63C2274030F942AFDC514B4040FD9F98FC9D274010CDE82635524B4041001F33859D2740687419014C524B404021C93A8999274020713B139E524B40A113A1769796274000B5781B55544B4000981662E69C27409883B3AA91544B40A0623CDB879E2740A8EC900F38574B4080A35904BF94274058FFBA1CB45B4B4080A35904BF9427400407506D5F5F4B40C1770A8919B22740B04D2B3E2B5F4B40A03201EAC68B2740CC56E951E5654B40003E448C9F83274028A559735C694B4040CFA87F64772740C822388E956A4B40E1BF7EDE0D70274034453A22706D4B40E0D060168453274038DA8514B3704B409F80194CF6552740F45FA7659F6C4B40A050DE5A3543274018DC5CBDC96A4B40E03B152441502740A8197A7ADB634B40BF4CF75BB7332740809DC422B1654B4000544E8874FB2640EC935829EF674B406005BF5406DA2640A0A682366B6C4B4020D73BFA64A32640504EF8352A744B40BF84C5C77872264090F44472DA784B4080417936E348264058E343286D774B40C0985B457243264020AB400CF8754B4060C83750EA3B264040C03ACB82794B40217B93E65C3C2640A0032BF17A7A4B40C0857303C14426405855BC1F64874B40C0A8B04F894A264018A0020784924B40208AE900904B264064289C5469934B40E03FF624F74E2640C8F24403BD954B40204635C42F552640A8C87B5152984B4080D59345D654264038CB6B1311A04B4080E3B7A44746264040DD742980A94B4040604C44094D2640F8364164E9A94B40809F337B366E264074B2F5F584AD4B40BF84C5C778722640C877526197AA4B4001CF6885CB6326405081BE5A59A84B40400ECE17E37D26403818E1F5B2A54B40603865FC68842640307800479FA14B404068A0ED29972640E85EBCC67EA54B40206C87ECA7A626406894964DDDA34B40C073E38F028F26400CA6457D529C4B4060FDEBDF6C9A26405C286762199B4B404101D3DEEAA92640488BD839239D4B408159ED1D12E726409844FD68579D4B40C0E5294D7846274030176618AC994B404034B980E6672740B8EC204E1E9C4B40418E8B562D812740C0B51D04B19A4B40A09DB5F78388274058931B70D6974B4080B43B3C35782740402A3E0B30954B40E0DC1A8A9BA22740A0B7F4ACC7944B40A0211FB250A82740388A5D5C1C914B40212D625429A0274074DAA426AA8E4B4060B8223BB387274080AE36990D8E4B4001D9338B1D932740B8FE7D639B8B4B409F8CD3BF0DA52740C8C77A192E8A4B40005D9D45EAB22740B89DE334A8834B4080445F6A19E7274070ADBBBB06824B4020BD85820D0F284088C093AE8A7D4B4000B24757D2372840409CBA7199804B403F300D05644B2840F0E2954265804B4040D63A2F1D3228403C29C32D29894B40E0C6108EC62A284010B8A292CF8B4B40C0CCB49A013728404C9D354FA08C4B4080AAEBB3774F284010B8A292CF8B4B4080AAEBB3774F2840D0675BC8418E4B40C0F9F34CB341284090B3FAA1D48E4B40A0A89FFD69432840484323CBCE904B40E0326161AC462840008BBB0290964B40C02B07368B89284058D932DEAB904B40E088A33667942840C0209AFAB28C4B40E02FC3B965892840A8ED73AA6C874B4080E1EDB35D882840601EF236B9864B407F86B77B77942840A4B0AB6669834B4040B6D5C888BB284048066EB4CE7D4B40004A7CA2768B28400006503374794B40	\N	2	\N	\N	\N	\N	\N	1000001
1000011	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000011/	40	5	0103000000010000005F00000000F86A3846082640408560181ADD4B40E047FD62A70F2640D0A6835FA4DA4B40806D9FA4AC2B2640E4A9C4985BD84B4080364ECB8E3E2640C89620BBFCD64B4080364ECB8E3E2640508E7607F1D34B40C102C61ECD262640F460BB1655D34B40000A849D624026409CABCE53E5D04B40C096A0A57C5426406C88C7D1DECB4B40A01FC33AC058264080B78C7D04C54B40215CF00C945A264078A729D95CC14B40DF7F1515AE6E2640D803AA751EBB4B40E08F78B955722640603B19D54FB74B4080EAEE9A8D73264050A3845ED4B14B4080214074AB60264068A6C5978BAF4B40002EE432EA6B2640583862FD44AD4B4040604C44094D2640F8364164E9A94B4080E3B7A44746264040DD742980A94B4080D59345D654264038CB6B1311A04B40204635C42F552640A8C87B5152984B40E03FF624F74E2640C8F24403BD954B40208AE900904B264064289C5469934B40C0A8B04F894A264018A0020784924B40C0857303C14426405855BC1F64874B40217B93E65C3C2640A0032BF17A7A4B4060C83750EA3B264040C03ACB82794B40E0CBEE576E422640FCE9A14B72764B40605D3C000528264098C76455EA744B40401DB06E66192640B0CAA58EA1724B401FC81586E42C2640E0D459FD366E4B4040C1ED99BD4A26406CCCAF492B6B4B40E094236C914C2640088F91B4E7664B4020B8B2E13C292640E0B7D4ED9E644B40C027371A58082640908A19FD02644B4040A50C2EB301264050A3C7C42D624B4081C55869FCFF2540683D7B4F21624B4000D66F42FB622540D09C195D5B5C4B40A0EFB00F0662254044825541415F4B40207810B71A73254050609F451E614B405FD6E5571D812540B8AD207F09694B4080E648FCC4842540C0BD8323B16C4B40DFCDDBE5169A2540409AA9644E744B408003E1CBC3B92540B0337559F77E4B4061AE46E341CD2540702680B1AA854B40606792657CDC254074CB369316914B40607CA0BC5FBA2540689E4EDFD6834B40DFCDDBE5169A2540547E8896877C4B40C09DB2F81F8F2540547E8896877C4B4020DEB6E7E089254020B0969B657B4B40C08EF28CB08B254050C9B89D0E7D4B40A03333F1168B2540ACE329928C7D4B4060F66B39378A2540C00A7B3DCC7D4B40E0EAA814826F254000FB9BFCC8834B402198B6A8E46B2540786228F720844B4081EA0ECC4B6A254040E8DD1BAD864B407F170AAD8E6A2540A0717BCBDA864B40206E2E1553692540D8EE3A6247874B40A05D19634669254040E2551853874B4020AE8944226A2540E43F109F82874B400074075B336C25408802EC2C05884B40809B278D8768254084A69E3F0C8A4B4040EFED21D76E254058AB704AC7894B40C1E2E93CFA772540D027B72FD88A4B4040C8FFEC608525409056A99EA1904B4060088C7EFF932540F82B5CAA609A4B40406185264C8D2540A01B26C95CA34B40803D601E32792540282711B61FA44B40803D601E32792540F8942B75B6A64B404071E8CAF390254008A58E195EAA4B40802DFD798A752540C497997111B14B405F795CAA8B4025402099D0EF3EB64B40DF631D0D2E282540A858712144B44B4000C1542EB219254038B18FD496B54B404069C83678122540D8E5D29A94B94B4001F892DA4C222540D80E99A15ABB4B40600F27309D3B254050F169CF5EBC4B40E0E8B05D0247254098B34E3406BC4B40E053C5DDEB52254028F9DB824BBC4B407FE8F5BA54652540B0DE111D5BC04B40E053C5DDEB522540C4FED765AAC74B4080AE57EE8B452540800DA2D137C94B4080AD9E896C422540C07BEC4073CC4B400039BCC2B03E2540846A0293FBCD4B40C1F8DA9B2E38254014A43E0802CE4B40609C70515B3525405007FC98D1CD4B40DFAED2BC67322540540D1AB80BCE4B400059292DF13025408078592A27CE4B4040909985223025406C099B9C42CE4B4080958236852E2540E880436636CF4B40DF656A59912E25405074FDF271CF4B40808F72658DBC254018FA7CDB26DF4B40E04DFD510EBD254038A3BB9824DF4B40E09541225EBE2540043AE0E81ADF4B4020B94C399EC52540C88A69FFB3DE4B404049716A74F5254000EB3E5B62DE4B4000F86A3846082640408560181ADD4B40	\N	0	\N	\N	\N	\N	\N	1000001
1000012	2026-10-22 00:00:00	2026-10-22 00:00:00	3	t	/1000001/1000012/	50	6	010300000001000000440000000155F1741E0425405016AC48F76C4B4060B3DBFCF9FF24408C6BB7F5846E4B409F283C2ECBF32440F85FCC08C26D4B4000157ACA58E32440F85FCC08C26D4B405F0F9ED1A2CE2440388A46C0A6704B40E02988213CC12440A87E5BD3E36F4B408042F537EAAB2440947B1A9A2C724B4080B097EA25862440A013AF10A8774B407F1005CBC2842440D848F29942774B40804F080B916A244014EBC906167C4B403FF7CA7580692440FCE679D5627C4B40E0BA0489E8262440782664EF9E894B40BFF5255872262440A8619A84B3894B40A06977D3232F2440441A6E5DBD8B4B401F6C7B534230244080BE1428268B4B4080DAB398EC41244084822D11108C4B405F8519B06A55244080BE1428268B4B40A0F351F514672440606F8933B18A4B40A0F351F51467244030DDA3F2478D4B404060A8B4DF73244030A1BCDB318E4B40E0F7E1FA59AE24407C72CA6C68884B40A0749B5ECCBE2440B014135279894B40804F2A6341DD24409439248F09874B40DFCF42867EFA244070EA989A94864B4000B9B7F5AF142540702680B1AA854B40E02CCC33103B2540087EB5592B894B40402DA44FED682540F038650BD4894B400074075B336C25408802EC2C05884B4020AE8944226A2540E43F109F82874B40A05D19634669254040E2551853874B40206E2E1553692540D8EE3A6247874B407F170AAD8E6A2540A0717BCBDA864B4081EA0ECC4B6A254040E8DD1BAD864B402198B6A8E46B2540786228F720844B40E0EAA814826F254000FB9BFCC8834B4060F66B39378A2540C00A7B3DCC7D4B40A03333F1168B2540ACE329928C7D4B40C08EF28CB08B254050C9B89D0E7D4B4060051C56798A25409C821F56F17B4B40A06D890B29842540A82312B54F7B4B4020CF27D987672540F0B8382F70764B40003884257C64254098C76455EA744B40409659C67E72254060E93459C3744B409F5BA92D96782540B0068DA5B7714B40803D601E327925405C15B9CB31704B40DF39015F676C2540A4F62901106E4B40E017BEDC2C5D25408C6BB7F5846E4B409FE91129215A254018630D42796B4B40800DE980BC4D2540D8D10406E0684B404008764D5F3325407C463685C56A4B40E09B7C4DEB322540D8A3EA70D76A4B40BF61FA89C3312540506EA5BB516B4B4060DF0F4C29312540649959E6556B4B4080A569ED09132540145D3AFA606C4B40C07A9529A41125408408AEB5616C4B4080199DCB8B102540ACE85E13516C4B40E071CC5E2810254068E420794B6C4B40C0FC3ED2960F254040FA83743D6C4B40E0BF5415150F2540305F81FC2D6C4B40A0FA7F0A840E2540E895DDF9296C4B400031D58FF70D25405869EA93216C4B40A06693A0180D254098C938FF1C6C4B4020CFAE36570C254090099BE8106C4B40A04980C0210B2540EC0F9EBA066C4B40DF092FE2B70A254070BBCB100A6C4B400053D1626D082540287B58D03D6C4B40A08511D73E042540E8CEF42EF86C4B400155F1741E0425405016AC48F76C4B40	\N	2	\N	\N	\N	\N	\N	1000001
1000013	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000013/	60	7	0103000000020000007200000000D9BD59A65A23406024A72837DD4B40BF046DDADBC5234090F27380B6D94B40607CE0D706F92340E81F2F7152DA4B40A055B7B2F8F92340D0657139BDDB4B40201FC59E150A2440F896E4805DDA4B40BF6428B7850E2440B89470B17AD94B4080C32C77101024401018AE251CD94B40C01D858BDF532440505B2D61C1D24B40E0384542B95424400C7DA21884D24B4020C16AA6275D244068F9C2EAE3D04B40218A3D26E55D2440C04DB6563BD04B40603E5A09CE5D2440685D771BDDCF4B40A0CACC9A925D2440E84360178BCF4B40006B650EBE5D2440681AFF9E15CF4B408099C6A8CB5D24404000D43AD5CE4B4061C52877BB5D244098D208AAC4CE4B40C10C10376D5E244050F4E4FA74CD4B40200B261F375F244098482C4053CB4B40C0609993246224406CE7AB1956CA4B40208A44BA025524402809F305CAC94B40C0E9654E7630244020BDA84A0CC74B400084377BD2052440DCCE15AA3DC34B400110233D72DF2340F80D3EFA0AC04B40E09DF084F1BD23405077E001BDBF4B40801F559B9FA82340906573A28BC34B404088B1E793A523401C35777838C54B40E00916FE4190234090298C8B75C44B40218FD9D3BA87234030B086DF1BC14B40005FB0E6C37C2340A0E082096FBF4B4060161AE31E872340A05851379BBD4B40C094B5CC709C234098D01F65C7BB4B40201351B6C2B1234014805DF224B64B40E17D2A3CA2B623406015846C45B14B40C0B47B15C0A32340548D529A71AF4B4080FAE39F14C723407854ACBC12AE4B4001E0F94F7BD4234070CC7AEA3EAC4B40801AAAE863CE234008BBE0C769A34B4080B32F224FD62340F8AA7D23C29F4B40A0AC073628F42340C080036CDD9C4B408079B0CFE0162440B470A0C735994B4020BF185A353A244098E52DBCAA994B40A0B491AE434B24409099E300ED964B40E19B89E4895D2440A08CC195FC904B4040B8F06D0E582440602660C7018F4B40209F5211E6322440404FA300B98C4B408052C29A6A2D2440404FA300B98C4B407FCCD433A32E24406011FEDD6C8B4B40A0A7870349272440A8619A84B3894B40C06C6634BF272440782664EF9E894B4020A92C21576A2440FCE679D5627C4B4060016AB6676B244014EBC906167C4B40A0576EE3FB9C2440B05B2FB3F3724B40C0A7CE91FFD82440604F74CA146A4B4040BEB1D26AD9244088B849E479684B400BF7088CFF0A24409C1BE817DE634B4020F5BC6799C923408028409DBD6B4B40C13BEEEC0F7B2340E82994BA61694B40606CE964B8352340300F897D6D6D4B40C147C26D56412340B844F4F995704B40A0681B29A22E2340702A45A486714B406014E535232B234084A1576E6F714B4020B7D023B3FE2240080692E2A96B4B40407ECBB012ED22402833B8FC736A4B40815BF6934DEB224070753121B46A4B4040BACDD250E6224048ACE56BA26A4B4020FF3DE0D0DE224078668908DE694B4000F24BCF84DB224040464662DD694B40408BA04376D72240B8E005B1766A4B4080A48CE839D72240B09B1E237F6A4B40009E60253DD72240F0B2CA127E6A4B403F12C42BC8D12240340C1851A46A4B40A0F063976BD3224044B7A716976D4B40E060191601ED2240EC4D050FE56D4B4041CAA6A86F2423402845881E7D774B40C0786B7FB844234088FA74E1EC794B4020F983A2F5612340D017CD2DE1764B40C09AAE01F35323401C710C91EB724B402060FE680A5A23401461A9EC436F4B40E13C6FF35E7D2340B8ABBC29D46C4B40BF4397DF855F234070DAAE989D724B4060A08AFAA86823407426F9535B754B40A00EC33F537A23407426F9535B754B4060D0B3E79F732340E02730D2887A4B4060FACF95662F23407CCBAF35C7804B40806478D5CBF9224020DADB5B417F4B4060495D3FB8CC224028620D2E15814B4020A4D320F0CD2240C0058D9153874B40A094A1C2C20423409473A750EA894B40607B03669ADF224078347F001D8D4B40403B77D4FBD02240F838F7B701904B40E01E104B77D6224000495A5CA9934B407F961EFCAD0C23409828C1D6FD984B40A15A227054452340846167B45C9A4B40E09831C8074C234000764210E9A04B401F65A91B46342340F829F8542B9E4B40003A2BE18A03234084ADB16F1A9D4B400081DF5E50F4224000B22927FF9F4B40A0A681A05510234008FE73E2BCA24B40800A333A0E332340ECFA32A905A54B40804F6A7EE81B2340580CCDCBDAAD4B4000251D8AA725234070B4C4E6FDB64B4060C5FBF5334A2340D02DCA9257BA4B40803A5C27053E234028E3B655C7BC4B4060FF7A48A2092340B0667070B6BB4B406024EC432DEB22402C6BE8279BBE4B401F9A7DBB781923409C30388FB2C44B403FA535ADE442234020780A938BC44B404029AD8FEC6C23409C30388FB2C44B40201BC7243071234048D7F82BA8C84B40007DFB84FD8B2340E42E2ED428CC4B40A00EC33F537A234038E41A9798CE4B402123A050BC1D2340AC319CD083D64B4060FF7A48A2092340E4A7604326DC4B4000D9BD59A65A23406024A72837DD4B401400000080FCE081A3A92340689E4EDFD6834B40A0A9960F697E2340B827CD5B3F884B4040A1395AF16D2340C043D8FD9E824B40E1B678F74E862340107193EE3A834B4060BC54F0049B2340D8CE4A092A824B4060653DCE97A62340B0337559F77E4B40A0C58FA885BC2340B0AB4387237D4B40A0E7D22AC0CB234070F997FD6A784B40A0D5F24C2DC02340F87C51185A774B40E0A16AA06BA82340C0DA083349764B40408C2B030E902340A89BE0E27B794B4000908AC2D89C23404C5EC24D38754B40C13C6D1342B8234060E93459C3744B4040EB31EA8AD82340948B7D3ED4754B40206FA9CC9202244004702FAD69714B408036766D95102440401278927A724B40C0BB39430E082440948B7D3ED4754B40400BF832DADF234060CAD251457F4B40DF25E28273D2234060CAD251457F4B4080FCE081A3A92340689E4EDFD6834B40	\N	0	\N	\N	\N	\N	\N	1000001
1000014	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000013/1000014/	5	7	0103000000010000000B0000002DB29DEFA76623407C3C07A9EE944B40029A081B9E5E234024B9FC87F4934B407E678EBFC77323404D158C4AEA904B40D0D556EC2F6B23407ED60FC48C8F4B403C73FC3D9E3323406A721E3B958D4B405A643BDF4F0D23409CA223B9FC934B409EEFA7C64B272340DA1B7C6132954B409E5E29CB10272340A69BC420B0964B40686CC2D0FD3F23402F0250B2C2964B409CC420B072482340575BB1BFEC964B402DB29DEFA76623407C3C07A9EE944B40	\N	\N	urn:mrn:iho:fa:dk:ek-r-44	\N	\N	\N	1	1000013
1000015	2026-10-22 00:00:00	2026-10-22 00:00:00	3	t	/1000001/1000013/1000015/	6	7	010300000001000000070000001FAA16FEF9932340B29DEFA7C6CB4B40EEEBC03923AA2340ABF400032ECD4B402A3A92CB7FA82340A857CA32C4C94B40B30C71AC8B9B2340B7F62B8A44CA4B407DD0B359F5992340984C158C4ACA4B401973D712F2912340C7040FE689CA4B401FAA16FEF9932340B29DEFA7C6CB4B40	\N	\N	urn:mrn:iho:fa:dk:14	\N	\N	\N	1	1000013
1000016	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/	70	8	010300000001000000E3000000C11D697D8583284010C3A5A2720C4C403FC668A6466B2840B85B46E20C0C4C40017537F4BB342840186DA6C634074C40808DCDAAA9E82740ECE704DC4FFF4B40C051F20B2BBC2740786D0EFB70FC4B40802DAB3452BE27400C1F67C077FA4B404012076152FB2740B8735BA156FD4B400073F93F2210284070A43CC5FAFC4B40004EE2FFD02728402C3D950B09F24B40E010678F61262840B89E8B6DEFEB4B40A0A3C1A05E4228405C812D8BD6DE4B40E0D21BB6793A28400088E265F8D44B4020671699672B2840F06EAECC61D14B402007F422100128400C51B49B6ED44B40DF5E43FE06DB274020BF444A20D04B40A00894F400D62740D8DC0A6175D24B40DF5E43FE06DB27400475C758A9D74B40A0F19D0F04F727409CBEA8FB45E04B40C0B352361C0B28406C13D1F6C2DD4B409F439F2A0718284050B85B5560DF4B40C0B352361C0B284034BE40C709EB4B404012076152FB2740E81235A8E8ED4B404012076152FB2740D86D762FADF14B4060446F937F0228400CABF19F1CF34B404077A72E25F42740B812018E4AF34B4021453FFCF7EC27403C1F9BDA15F54B40E02CDBCBD9D32740D0F4065D57F64B40C03AFC262EDD2740F4184E3430F44B40003A2CBEB5F22740A00CE80103ED4B40A0B1148282E6274024A50C2E73E74B4020DA09485EC827404437B0995FE64B4060F02FC4E2BC27407037E4B3FDE04B40803BCC8FA6C72740684AFFC04CDE4B40E07539E303BA27404C68F9F13FDB4B40817917698EB3274028D03CFA0BD64B40C018258ABE9E274028AC293DD1D24B40E07AB73A7F88274028AC293DD1D24B40E0484F0852812740B0B8C3899CD44B406077D9B4F48E2740600DB86A7BD74B406077D9B4F48E2740D087AE4B5ADA4B40E091AD1F7C67274030E97093A2D94B40C01B652DA0482740C0BEDC15E4DA4B40A1023194094527406C13D1F6C2DD4B40613CCE7EEE4C27405C812D8BD6DE4B4020F67D84D6752740F056990D18E04B40E09FCE7AD070274040139DDC24E34B40009DC05DBE612740BC06039059E14B40607A1958D6382740E08DC7D7A1E04B40C064C344CA2E2740E4B1DA94DCE34B400059E09D0F4A27400081C5569AE94B405F0CA4005B6A2740B0B1A67A3EE94B4000C41552A96E2740945631D9DBEA4B4080F71D56C74A274078E8A02A2AEF4B4000F43FD03C51274088EEB9B671F54B40A03BFE1576622740288DF76E29F64B40DF3A2EADFD7727403C1F9BDA15F54B4040AA11509A8027400C1F67C077FA4B408030EBD733682740A0A470DF98F74B40E03FAC0479462740288DF76E29F64B40806593AD42192740288DF76E29F64B40806593AD4219274004BE0CAD6BF04B40A095BD2BD6FB2640A8E8D444C8E94B4020B091965DD4264078742B0ACFE74B40FFA47E581BDA2640D0E8085F66E44B40807FC93521A226406826EC0312DB4B4020238545547126404C68F9F13FDB4B400078C603393C26402044B21A67DD4B4080C6C9D48F22264058C0B1924FDB4B404049716A74F5254000EB3E5B62DE4B4020B94C399EC52540C88A69FFB3DE4B40E09541225EBE2540043AE0E81ADF4B40E04DFD510EBD254038A3BB9824DF4B40808F72658DBC254018FA7CDB26DF4B40DF656A59912E25405074FDF271CF4B4080958236852E2540E880436636CF4B4040909985223025406C099B9C42CE4B400059292DF13025408078592A27CE4B40DFAED2BC67322540540D1AB80BCE4B40609C70515B3525405007FC98D1CD4B40C1F8DA9B2E38254014A43E0802CE4B400039BCC2B03E2540846A0293FBCD4B4080AD9E896C422540C07BEC4073CC4B4000BD23C3274D25401CFE3EA957C14B4041D69DEC3B3D2540B435EC48CCBC4B40C085923646322540C0FCB8E4D5BC4B40201167277E1E25409091D42BADBB4B4040E51E1A2F0D2540541E6BB322BB4B4040FA344FF7FE2440587D7A7B36BB4B4000BF5571BF0125400006C1D1F8B84B40E1A8964713F52440A02773A045B74B40C056859154D82440681319F0CEB64B40E02A3D8405C72440408B06DF6AB54B40A1631607BBCA2440AC5E024DCAB24B4060B98A7E9ABF2440AC5E024DCAB24B40C1A3019BF7BA24400094DA862FB54B406035C89EE9BF2440681319F0CEB64B4040DD4DCC87D12440D0B70A710BB84B4060EB8736D2DE244028BED8BD4DBE4B4040AB9CA295EE24405029BD7676BF4B4080D1654B05FA24407C18640F50C04B4000683805FCF12440B0E257908CC14B407F6A3E607EEB244048F22DCAF1C34B40C0900709EEF62440D86013CC6AC64B40202461B987E22440403F61FD1DC84B40E03F53B9CDB82440F89468D80ECC4B40C061FA63FC9C244088DE9AC2EACE4B4020A0524B91A6244098570BDF47CA4B40FF30A6A0A89824406485929EA9C94B40DF35B256AD8B24409C3ADD860CCA4B4000D2037583892440F86FB5C071CC4B40E05AD20A397C2440F8355910FBCB4B405F8A7DD9A8652440D06B658FBECA4B400127E878CA612440286954062CCA4B404059C473605E244098482C4053CB4B40E05AAE8B965D244050F4E4FA74CD4B408013C7CBE45C244098D208AAC4CE4B40A0E764FDF45C24404000D43AD5CE4B4020B90363E75C2440681AFF9E15CF4B40C0186BEFBB5C2440E84360178BCF4B40818CF85DF75C2440685D771BDDCF4B4040D8DB7A0E5D2440C04DB6563BD04B40400F09FB505C244068F9C2EAE3D04B400087E396E25324400C7DA21884D24B40DF6B23E008532440505B2D61C1D24B40A011CBCB390F24401018AE251CD94B40E0B2C60BAF0D2440B89470B17AD94B403F6D63F33E092440F896E4805DDA4B40C02CE43A5BF52340284522A90FDC4B40614065395F082440C02CB2DFCBDF4B40A065B2732B01244090592F86F2E24B40017619B245092440BCE162A5FFE64B40407392D1F4D52340D0A5FC3406E94B4040EE4B1258AE23404477C36246EB4B408059B40325B02340B4EB17EC06F24B4000A2569B76EC2340A839A1C6B3F24B402112B75E612324407854FB0227F34B4080BEBB49974524401047CE64EDF24B40208217224C5A2440C4488A9086ED4B40C0B7CB9A325B2440D86128A160F34B4060C3B31BB57A2440D0C1D4E5C0F64B40A08B8EB1017824403084B2492EFE4B403F27166F99602440E07D00521B014C40802026C034762440A0F806D3EE044C40A05A59F6B25F2440883E228AEF0D4C4061350CBCE666244030AA3F41F0164C4040AC5C2E36882440245FF006241E4C400069C85786B22440B097DE1FEB264C4060FE75553DE2244010A50BBE24274C40E107EDE4F2FF24402814A1CC57254C40A1CDB9AE7416254020027E62A4224C40608C1DB50BF62440C451C368EA1D4C402067D07A3FFD2440F42446C2C31A4C402021B5C33EF424408805F6B9D6174C40A02A2C53F4112540B82D7D9483184C4040FA0C87292B2540C451C368EA1D4C4000C0D950AB41254050D500BC7D1F4C409F34B9D12D612540D8033A8E3D1D4C40C0912B76AD5C2540E80000EE5C154C40C01FD3D57B70254080F3D24F23154C404055874E6271254074F60CF0031D4C40FFC966CFE4902540E81CD89E17234C40C071ECFC82CA2540B8FE050B722D4C40804C9FC2B6D12540D83BEAEFB2384C40C08F339966A7254094597ECC06414C40E1A882632C4F2540D0F09AB5E63F4C4020C4421FBFF82440B0BD6B17AD3F4C40803314DF9FB1244020EFDE894D454C407F02DF2351992440542989CEAD484C4080D1A9680281244088475B62533E4C405F48FADA51A224409CF9D187A63D4C405F48FADA51A22440500DB11DF33A4C40BF308DFE4E7E24404CFB8DB33F384C4060221E9D7B2B2440E8ED601506384C407FABCD2A2C0A2440F4B1FAA40C3A4C4080F1E8E12C132440D890EE70863C4C40E05CCAE77F6124401488B79EC63E4C403F27166F9960244034B3781954474C40802026C034762440D0FA4FFCED4A4C402023ADA085A92440406FA485AE514C4040DD91E984A024405845617F68564C40FF6AC034CD67244038CF50CA0E554C407F0D38A1C93A244098DC7D6848554C4020B9AD88F5DE23405C90B0B9344F4C404103320E0A9F2340C09DDD576E4F4C405F77FB9F088D23402CBD2D605B524C408198DF0BC1CE234038CF50CA0E554C40A1C22418ABFC2340907F0BC4C8594C401F8B78C27D4624408022991F495E4C402141F43C69862440C8B9B508295D4C4001F967949B7B2440107FCD0C776C4C4060664177357F2440486E28170B774C408017C51F038A2440E0B5FFF9A47A4C4040149B36B3782440A8EC8A0CF77A4C408185FE1658972440D40768AE557D4C40E0D412DC5C9B2440A0FCD738697D4C40E0D20E1FDBCA2440980982DED3794C4040EE8D75FFCF244040A022AA577B4C40C02DCA46C9A924404C9040AC8C7E4C4060C41057D3A92440AC545108997E4C408031A184C4A9244078A28604C67E4C404045FA0689A7244080CB6FAD83804C4021FF7F226AAB2440045B34F065804C400037EAF3E1AD2440C07C762320814C4060C3EAE8E8BA244090355071008A4C400013B8BC0CD62440BC139F545E954C407F96FDC7FDE12440F0C04ABC7E994C40807B867A3E0B2540604F4692E99D4C4000F8406F4DFF2440280116D867AA4C40009804596300254050F4C034BDB14C407F72CB90280A2540E8FFF48A5AB94C40A08F72B11BCA24406C2554AD1BC84C400013B8BC0CD62440A88CAC8D15CE4C407FBD3D219EF62440F8B1A55B37D54C407F990BEAC81E2540187790FF0BDC4C4040E9D8BDEC392540E03F42D983DE4C400184DF32192625409C75AE7EE5DD4C40A07EEF0EA73525400462C49E73DF4C4020BACB5FD03D2540588AABA4F7DF4C40D8EC5CB16F112640E876AF3590E94C400089B4AE4D182640A80BC34A7CE84C40006D37B7C5CB2640A87CE39987B94C4020AFBBB80E4B2840B0212F7313414C4080585E154F2C2840F8AB0EC0D3264C40EC5B9D97C8BA2840C884569C801B4C40DF3E6562DE8B2840148A97BB57104C40204B1847918328405848CB7EA60C4C40C11D697D8583284010C3A5A2720C4C40	\N	4	\N	310	56.5	11.5	\N	1000001
1000017	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000017/	7	8	01030000000100000006000000E099E3EFF1EC2740FCF3472BD1284C40C92F96FC6289274040388C6F151D4C40B3C54809D46527404F1BE8B4811E4C40DA40A70D74FA2640C364AA60542E4C400E74DA40A72D2740968DE1C46A3A4C40E099E3EFF1EC2740FCF3472BD1284C40	\N	\N	urn:mrn:iho:fa:dk:ek-d-352	\N	\N	\N	1	1000016
1000018	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000018/	8	8	01030000000100000006000000CE88D2DEE01B28402F277B5E041C4C404D5FE2A26DFF27406E0F550BFF184C40C92F96FC6289274040388C6F151D4C40E099E3EFF1EC2740FCF3472BD1284C4085EB51B81E0528404CED32E9DC264C40CE88D2DEE01B28402F277B5E041C4C40	\N	\N	urn:mrn:iho:fa:dk:ek-d-353	\N	\N	\N	1	1000016
1000019	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000019/	9	8	010300000001000000070000005917B7D100EE274024B9FC87F4EB4B40E00865F4B6BC2740D200DE0209EA4B40BAFFB5A9A7AC27400AD7A3703DF24B404DA938FBF0B92740DF2A62EB2CF54B4099E0C13C51DF2740E130BE55C4F24B40FAC8C014F8ED2740B3C54809D4ED4B405917B7D100EE274024B9FC87F4EB4B40	\N	\N	urn:mrn:iho:fa:dk:ek-r-18	\N	\N	\N	1	1000016
1000020	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000020/	10	8	01030000000100000006000000D8A69EF2D5AB27403A6DA0D306DA4B40637FD93D79982740B7D100DE02D94B401D7F8FE720952740E17A14AE47D94B4047B921B3A09D27407BCA57EF5DDC4B400612143FC6AC274042CF66D5E7DA4B40D8A69EF2D5AB27403A6DA0D306DA4B40	\N	\N	urn:mrn:iho:fa:dk:11	\N	\N	\N	1	1000016
1000021	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000021/	11	8	010300000001000000060000005E70F3B37E9026406E0F550BFF004C40C4D6591AE576264018BAFFB5A9F34B406F8104C58F412640C9C014F89DF54B40A70D74DA40272640935F2CF9C5024C40E6F87B3C07592640C364AA60540E4C405E70F3B37E9026406E0F550BFF004C40	\N	\N	urn:mrn:iho:fa:dk:ek-r-11	\N	\N	\N	1	1000016
1000022	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000022/	12	8	0103000000010000000700000002BC051214CF2640A79EF2D57B034C401A2CAF6F3ACC2640EFEEEEEEEEFE4B405E70F3B37E9026406E0F550BFF004C409F17012859712640E586CC8276084C404D5FE2A26D7F26400D05593CE2094C403096FC62C9AF2640857CD0B359094C4002BC051214CF2640A79EF2D57B034C40	\N	\N	urn:mrn:iho:fa:dk:ek-r-12	\N	\N	\N	1	1000016
1000023	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000023/	13	8	010300000001000000060000000E74DA40A70D2740B8AF03E78C044C40D5E76A2BF607274011809215D6FC4B405E70F3B37E9026406E0F550BFF004C40E6F87B3C07592640C364AA60540E4C40D5E76A2BF6C72640DAD12509AF164C400E74DA40A70D2740B8AF03E78C044C40	\N	\N	urn:mrn:iho:fa:dk:ek-r-13	\N	\N	\N	1	1000016
1000024	2026-10-22 00:00:00	2026-10-22 00:00:00	3	t	/1000001/1000016/1000024/	14	8	01030000000100000007000000809215D6A0322740D475BB7165074C40F8098D4D182A274018BAFFB5A9FB4B405E70F3B37E9026406E0F550BFF004C40E6F87B3C07592640C364AA60540E4C40D5E76A2BF6C72640DAD12509AF164C406F8104C58FE12640C364AA6054164C40809215D6A0322740D475BB7165074C40	\N	\N	urn:mrn:iho:fa:dk:ek-r-14	\N	\N	\N	1	1000016
1000025	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000025/	15	8	01030000000100000005000000E6F87B3C07592640C364AA60540E4C40A70D74DA40272640935F2CF9C5024C403C4ED1915C0E26404CED32E9DC0E4C4082BD9CEC79412640A810A28F0C144C40E6F87B3C07592640C364AA60540E4C40	\N	\N	urn:mrn:iho:fa:dk:ek-d-350	\N	\N	\N	1	1000016
1000026	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000026/	16	8	01030000000100000005000000D5E76A2BF6C72640DAD12509AF164C40AD44B3C548592640D8F0F44A590E4C4082BD9CEC79412640A810A28F0C144C401F85EB51B85E2640D7A3703D0A174C40D5E76A2BF6C72640DAD12509AF164C40	\N	\N	urn:mrn:iho:fa:dk:ek-d-351	\N	\N	\N	1	1000016
1000027	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000027/	17	8	01030000000100000007000000A0D3063A6DE02440D4063A6DA0B74B40E6AE25E483DE24408716D9CEF7B74B4028A3B765ADDE2440BB02E45E73B94B402AF36928C8E224405EBA490C02BB4B406EEA295FBDE724409FCDAACFD5BA4B4076E09C11A5ED244026C286A757BA4B40A0D3063A6DE02440D4063A6DA0B74B40	\N	\N	urn:mrn:iho:fa:dk:13	\N	\N	\N	1	1000016
1000028	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000028/	18	8	010300000001000000060000001A2CAF6F3A0C2540F6285C8FC2454C4086EE7F6DEAD924408F2E497855424C409DEC791180D224408F2E497855424C406666666666C62440C3F5285C8F4A4C4026BF58F28B052540032EB5CBA4474C401A2CAF6F3A0C2540F6285C8FC2454C40	\N	\N	urn:mrn:iho:fa:dk:ek-r-45	\N	\N	\N	1	1000016
1000029	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000029/	28	8	01030000000100000005000000FBA6C31D828D2640992A1895D4E54B40B88AD83A4B93264090A0F831E6DE4B40BCBBBBBBBB3B26408FC2F5285CDF4B403D0AD7A3703D26408C94405DB7E74B40FBA6C31D828D2640992A1895D4E54B40	\N	\N	urn:mrn:iho:fa:dk:ek-r-15	\N	\N	\N	1	1000016
1000030	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000030/	29	8	01030000000100000006000000A52C431CEB922640E3C798BB96E04B40B88AD83A4B93264090A0F831E6DE4B402CF9C5925F0C2640A4DFBE0E9CDF4B40AE47E17A140E264093CEADFD8AE64B403C4ED1915C6E2640AE47E17A14E64B40A52C431CEB922640E3C798BB96E04B40	\N	\N	urn:mrn:iho:fa:dk:ek-r-16	\N	\N	\N	1	1000016
1000031	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000031/	32	8	0103000000010000000A000000E00865F4B6BC2740D200DE0209EA4B40381DEE10EC8527402859610D2AE84B40619EA87F23742740A69BC420B0F24B40D0FA819871902740BD77C1CDCFF64B40728A8EE4F2BF2740ED0DBE3099F64B404DA938FBF0B92740DF2A62EB2CF54B4099E0C13C51DF2740E130BE55C4F24B40FAC8C014F8ED2740B3C54809D4ED4B405917B7D100EE274024B9FC87F4EB4B40E00865F4B6BC2740D200DE0209EA4B40	\N	\N	urn:mrn:iho:fa:dk:ek-r-17	\N	\N	\N	1	1000016
1000032	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000016/1000032/	33	8	010300000001000000090000003B4C5D6E7F9026407698BADCFE004C40C3D4E5F607592640CCED0F32540E4C405813CF8A46822640B1E4174B7E114C40CDCCCCCCCCCC26400000000000204C4033333333333327400000000000204C4096FC62C92F962740C3F5285C8F024C407DD2277DD24727401032547698FA4B403B4C5D6E7F9026407698BADCFE004C403B4C5D6E7F9026407698BADCFE004C40	\N	\N	urn:mrn:iho:fa:dk:ek-r-19	\N	\N	\N	1	1000016
1000033	2026-10-22 00:00:00	2026-10-22 00:00:00	3	t	/1000001/1000016/1000033/	34	8	0103000000010000000600000032547698BA9C2640E17A14AE47094C40F707192A3BCC2640EFEEEEEEEEFE4B40602CF9C5927F264036F1AC6824F84B40CCED0F32545626401BE8B4814E034C4032547698BA9C2640E17A14AE47094C4032547698BA9C2640E17A14AE47094C40	\N	\N	urn:mrn:iho:fa:dk:ek-r-20	\N	\N	\N	1	1000016
1000034	2026-10-22 00:00:00	2026-10-22 00:00:00	3	t	/1000001/1000034/	80	9	0103000000010000006F000000401AF26F9AA724401866701A76804C408031A184C4A9244078A28604C67E4C4060C41057D3A92440AC545108997E4C40C02DCA46C9A924404C9040AC8C7E4C4040EE8D75FFCF244040A022AA577B4C40E0D20E1FDBCA2440980982DED3794C40E0D412DC5C9B2440A0FCD738697D4C408185FE1658972440D40768AE557D4C40A0D1E081C47F244018FAA75B837B4C4020DD3B30A1782440B8C6A281497C4C4040767403D561244070B1A2736F7C4C40E0F150A94C022440D8C278AFD5874C40A0FE888779DE2340E4F3CE9E94844C40C097C15AADC72340A08CFB4006854C40E0C93201159A234058E9AC8B65834C401FFCA3A77C6C2340006CEBA73F834C40C0BA1543EB4B2340F8DD9BB6B17B4C4080C74D2118282340589855AAD4734C40203D7DFF44042340282E9B612C794C40806941DE6112234080D7F9E7C47D4C4040FAE7094EC522401074C5431A7F4C40201E1A41239D2240601B4219537D4C4080A70C145F6D22402CF5D1BB0E764C40C0550F6D07692240C0E1CE8ACA724C40008B8E353A7822403042EC77A16E4C40A0FE8D40336B22404CEC2CDD51634C400049D78EDA8C224014439263EF604C408047D0FD746E2240F01109CAE0584C40201E1A41239D2240DC28C7DEF75A4C400085E16DEFB32240ECF788FB7C564C40A098A462FEA72240305A7EB5EA4C4C4000675CFE64A0224090FCC0766C484C4000E3EDB0A5C92240B08182FFEB494C4000E3EDB0A5C92240C0D6A86B93454C4060B1A54C0CC222408C585FF2B9424C40C031DD353291224080E604BE79404C4060508BE78A6F224048A446EB5F474C40A01C13B0BD7E224038B2F7F1F64D4C4040DB844B2C5E2240403288421D4E4C406074BD1E60472240883ADD60854A4C400048F93F433922402876F114B84B4C40E08780136F3B224020D74F04D3474C40E0C937BACE262240EC0FE24420454C40E0ABB24A4413224000B6FA8C39474C408088A9553D062240F0753208114D4C40008AB0E6A2242240AC9B1E233F544C40802B7B611E442240E8CCC93A855A4C40002A74D0B825224028B02F0278634C400005644A4CFA21407CB3DAB72B634C40A06399CFD0DA214024081246B9624C4020C2CE5455BB21400036BF85E85C4C4000C9596B37D32140105150BA4F5C4C4040F51D4A54E12140A8F683035F5A4C4080A320A3FCDC214020F242196E584C4060EE923372C92140081079F615574C406012C56A47A12140F8980ABC56564C40207F395F5E7C2140EC1EBFE2694E4C40C0FBF3536D70214094C4EE4FAA4D4C406036F7A11C792140C88AAE66F84A4C40409DBECEE88F2140D8F7404986474C40A0F9C38039872140E0726525ED434C40C0B93CAD0D852140902E777F9F3D4C40C0F2386A576F21400CAE3F4AEB3A4C4060EAA6C20F39214078339CD2C43A4C40201E1FFADC292140C873540D793D4C40A01C1869770B2140A0C3D0E56B3C4C4020E2141BC802214068744AF1C53D4C40C09AD9EEEB1D21408C585FF2B9424C40C0911E05D61C214020F169F02C464C4080655A26B90E2140B83816C0C6434C4060924747A4E72040EC0FE24420454C4020E136CC30AF204048A446EB5F474C40A0FDB4AA55A42040B0C003E2F9444C4080E8EA24E1912040989D4DCDB9454C4080CA65B5567E204028B04D58124A4C4080036272A0682040785194BB774C4C4000299B3ADB5E204040FE0EBC4F4F4C4040275CFA5D592040707CE0DCBF4F4C40A0962549F659204038A9C1D1A1524C4000E43B54FA6D2040E825ED1DE0584C40A0AFD7AA136D2040004650CFD75A4C40801FFDBF826C204050F40EC4ED5A4C40A0F6FCA0966C2040384FA625645C4C40608E870D0271204028F8EA738D5C4C4000660B101E73204078BB496CF45C4C4020A510BEB3742040C88A2173445E4C40A08123F8147B20409890504C815D4C4080C1AACB407D20407CB3DAB72B634C406013A8729881204010CAC5FC82644C402023EE72909A20407C2B3B9205634C4040F60052A5C12040107889ECAE574C4060924747A4E72040E8CCC93A855A4C4000C48FAB3DEF2040D8E9978D30604C4060924747A4E72040E89DA241CF644C40A0E31BAC2D212140C876C935CE6B4C40C0B62E8B424821409888442F107A4C408084BDE4DA752140704117C38B7B4C40E0EC8BA20CAB2140606103876F804C40604C9F7628DF214060E3256536824C4080A62EC5C7192240D459EB0052854C4040B444F28B492240CCE427175B864C40C07394DC917C2240380B867095804C4000C568411BB62240B4288E9EC3854C40A07E0B64D6242340D8C278AFD5874C40A1639416174E234048EB4BB0E48D4C40C0B4687BA08723400046E7A11B904C40602946D530CE2340B8EA8706598A4C40C191149362032440146E0AE2BE8D4C4021F8B27D604F244010B8765B0F864C408060813B928424405068339D49804C40401AF26F9AA724401866701A76804C40	\N	2	\N	\N	\N	\N	\N	1000001
1000035	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000035/	90	10	0103000000010000001E000000A0A647F5CB272540D08480F810DE4C40C143A7C1161A254038FC1BDF97DD4C401FEB1E04DDFD2440904C26921BDA4C40E0390E8969C52440CC5D9931B1D24C4040BC7545C37D2440B87FEC02D9CB4C40004D1C71AF30244038608CF213C94C40206AC391A2F023407884F34639C94C40E0FA69BD8EA32340E014FA262EBA4C40619848C7A732234088935CB4B69D4C40E0F67D4C2C132340381D94ACDA984C40200D9A563DBB2240C05B8F4B27904C4000F4E35F66182240A0E6C2EDB58F4C4020B14E6A6FD921408871B7BEA48D4C400074663CC35E21407867C95DB08A4C404064203CCB452140F803DF20598D4C40208DC50EE1352140D01DAB9E108C4C40E02518E5D12D214034469984008F4C40802ED57ECBF82040AC718A1C8F964C407628DE4311BC1F4028E90946CFBD4C407FBDA37084ED1F40B8383215B6BF4C40C02DD971F6E920405C191B9412CF4C404087008720C62140501DE3AB19D94C40A0E6BC5791C322403009D48B6FFE4C4080350BBEAD0E244070E19B6E65214D40206FD472B60E2440685B57E364214D40A025CAE30B152540AC402B7200114D40D8EC5CB16F112640E876AF3590E94C4020BACB5FD03D2540588AABA4F7DF4C40A07EEF0EA73525400462C49E73DF4C40A0A647F5CB272540D08480F810DE4C40	\N	0	\N	\N	\N	\N	\N	1000001
1000036	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000035/1000036/	19	10	01030000000100000005000000C139234A7BC32540C364AA6054064D40D22509AF4AFF2440C364AA6054064D403C4ED1915C6E2440073A6DA0D32E4D403174FF6B53FF2440C56A06CBEB3F4D40C139234A7BC32540C364AA6054064D40	\N	\N	urn:mrn:iho:fa:dk:ek-d-389	\N	\N	\N	1	1000035
1000037	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000035/1000037/	20	10	010300000001000000060000002BD16C3152022340CCA4736BBF964C40C286A757CAB22240AACCA7A120974C4005593CE27DB22240C601E130BE994C405E70F3B37EF0224059F28B25BFA04C40A0648535A80023405A3F10330EA04C402BD16C3152022340CCA4736BBF964C40	\N	\N	urn:mrn:iho:fa:dk:ek-r-48	\N	\N	\N	1	1000035
1000038	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000035/1000038/	21	10	010300000001000000060000002BD16C3152022340CCA4736BBF964C408EE1C46A067B2240D475BB7165974C408EE1C46A067B22404CED32E9DC9E4C409FF2D57B17CC2240E586CC8276A84C40D22509AF4AFF2240E586CC8276A84C402BD16C3152022340CCA4736BBF964C40	\N	\N	urn:mrn:iho:fa:dk:ek-d-370	\N	\N	\N	1	1000035
1000039	2026-10-22 00:00:00	2026-10-22 00:00:00	2	f	/1000001/1000035/1000039/	30	10	\N	\N	\N	urn:mrn:iho:fa:dk:ek-r-47	\N	\N	\N	1	1000035
1000040	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000040/	100	11	01030000000100000047000000714631152E622140641918AF8B744B4000F1C4CA6D582140488FE200A0744B40E00CE64C664521406C27B74BAD744B4000A8D607011C21400029EB83E1754B4080C870B6651D21403060036E127F4B4060E3D7AF3E0921404080D2DE0D7F4B40C05AD82EC80021406C2C4B88FA7F4B40201BF7F0A9F12040E8B9331D3E834B40208E593DFAF42040E05ADBEB0F864B40E04855C4FDF12040BC91531DED864B4000D9F771CCC6204068AB7AE2C1884B40A00B7BDF9AA62040FC271A39C5874B40E0605076989A2040E411DB6B2E884B4060814D29F19920407009736718884B40C066987F99341E40F85693305E954B4040C56BD89BFE16404CF8AB5B58C14B4080311AB59F9B154050C0F51CE99F4B408083D946349814404C17D300E99F4B40C184CD7F96FE1240CCD593BCA4B34B4040FD10588FFE10402C67BDBFD1E24B40FF7356E8DCC90A40A07ADED993F54B40FF61521E0BFD0940980156FD000B4C40C07E168DAD20144090E19F29A64B4C408027C94FB7BD1B40DCAA894A52964C407628DE4311BC1F4028E90946CFBD4C40802ED57ECBF82040AC718A1C8F964C40E02518E5D12D214034469984008F4C4000C6EE2132372140580A60BB958B4C40E0F47AB3AAC4204084BE47ACAE764C4020D5E719E4A920408435833CDE6F4C4060D64D15CB7B2040781BA1FA22644C4000660B101E73204078BB496CF45C4C40608E870D0271204028F8EA738D5C4C40A0F6FCA0966C2040384FA625645C4C40801FFDBF826C204050F40EC4ED5A4C40A0AFD7AA136D2040004650CFD75A4C4000E43B54FA6D2040E825ED1DE0584C40A0962549F659204038A9C1D1A1524C40C0116E376357204010338B5525464C40E0D6EA1A49572040C0E346C4A6454C4060CD61A8D7482040009178F839444C40804F79A9154C204020BA3EB82A3D4C40E04FD2B2A4672040686C700E16354C40E0C347405C8F204058248B40A42B4C406076D8C3E59920406029E41A842A4C40E0BF71476FA4204028A4A41093264C40A0545ABEAF892040E8BF51A914234C40C06899AA534F20407839489C96274C40E0BE662BC251204078007584C5244C40208ABEA6FA432040B86E221E3C114C40802EADB6FD722040202276BF2D0E4C4080C8CF4B67B1204028C1F979B8074C40A0F419CC36B220402807268358004C404064605772D32040D06C291D75F64B40A0B5A5CCD5B32040084C6A7F5FEE4B40802EADB6FD722040F06F54043EE94B40A04BA3B028612040241EC71E40E14B40A0D72D2371392040485140DDF7C84B406041D7358F702040ACA418975FC44B4060AC95B5BF6F2040B409611CB9CB4B400085D340FB902040088520F879CE4B40A0F419CC36B220408823284E93CA4B4080386FE031EE2040681976730CBE4B40A03D50F5CB2B21406884A99317BC4B40A0079303F255214050F6AAC3C1B54B40C098FE8AD46B21403C261511AEAA4B406064AF0F9C7921407852933E7F954B406094CF8858652140E054846B53874B4040A7F907EA622140C07391DD6D7D4B4040E66D074B61214074D751C321774B40714631152E622140641918AF8B744B40	\N	0	\N	\N	\N	\N	\N	1000001
1000041	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000040/1000041/	22	11	010300000001000000070000000E74DA40A75D2040499D8026C2E64B408BD83A4BA34C20407F4591C851E34B40D328376416242040D14706A6C0E74B40CA9E1701282920408613AB192CEF4B408F9DCA7C1A4A204095F9341464F14B40D2DBB256C75420403230057E67EE4B400E74DA40A75D2040499D8026C2E64B40	\N	\N	urn:mrn:iho:fa:dk:15	\N	\N	\N	1	1000040
1000042	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000040/1000042/	23	11	010300000001000000090000003333333333332040ABAAAAAAAACA4B4087A757CA32342040B4EDA16AE1C74B401BC389D50CF61F40B5814E1BE8C44B40B8F9593F10531F403096FC62C9C74B40C7958DE1C44A1F407AA2FE8D50CE4B40B097933D2FA21F40C84E653E0DDD4B40C4F856115B072040CD16232550DF4B40EFEEEEEEEE3E2040FE8A2291A3CE4B403333333333332040ABAAAAAAAACA4B40	\N	\N	urn:mrn:iho:fa:dk:ek-r-33	\N	\N	\N	1	1000040
1000043	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000040/1000043/	24	11	0103000000010000000800000087A757CA32342040B4EDA16AE1C74B401BC389D50CF61F40B5814E1BE8C44B40B8F9593F10531F403096FC62C9C74B40C7958DE1C44A1F407AA2FE8D50CE4B40B097933D2FA21F40C84E653E0DDD4B40C4F856115B072040CD16232550DF4B40EFEEEEEEEE3E2040FE8A2291A3CE4B4087A757CA32342040B4EDA16AE1C74B40	\N	\N	urn:mrn:iho:fa:dk:ek-d-380	\N	\N	\N	1	1000040
1000044	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000040/1000044/	25	11	0103000000010000000800000087A757CA32342040B4EDA16AE1C74B401BC389D50CF61F40B5814E1BE8C44B40B8F9593F10531F403096FC62C9C74B40C7958DE1C44A1F407AA2FE8D50CE4B40B097933D2FA21F40C84E653E0DDD4B40C4F856115B072040CD16232550DF4B40EFEEEEEEEE3E2040FE8A2291A3CE4B4087A757CA32342040B4EDA16AE1C74B40	\N	\N	urn:mrn:iho:fa:dk:ek-d-381	\N	\N	\N	1	1000040
1000045	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000001/1000040/1000045/	25.5	11	0103000000010000000600000026BF58F28BE52040304FD4BF11A24B4014AE47E17A542140304FD4BF11A24B4014AE47E17A5421405C8FC2F528944B4026BF58F28BE520405C8FC2F528944B4026BF58F28BE52040304FD4BF11A24B4026BF58F28BE52040304FD4BF11A24B40	\N	\N	urn:mrn:iho:fa:dk:ek-r-53	\N	\N	\N	1	1000040
1000046	2026-10-22 00:00:00	2026-10-22 00:00:00	3	f	/1000001/1000040/1000046/	26	11	01030000000100000008000000E63FA4DFBE2E21408FE720D59D9C4B40E63FA4DFBE2E21402B1895D409984B400250B2C21A042140A835CD3B4E994B404D3AB7F62BFA20402DD7C89BE9974B40BC96900F7AF62040DDDAAF2812954B402F6EA301BCE5204040388C6F15954B40B3C54809D4E520402C8A448E9A9C4B40E63FA4DFBE2E21408FE720D59D9C4B40	\N	\N	urn:mrn:iho:fa:dk:ek-r-38	\N	\N	\N	1	1000040
1000047	2026-10-22 00:00:00	2026-10-22 00:00:00	3	t	/1000001/1000040/1000047/	27	11	01030000000100000006000000ABAAAAAAAAAA20408988888888884B4098B868DB432520400D05593CE2814B40E3361AC05B90204007A9EEA498AA4B40388C6F15B1E5204007A9EEA498AA4B40388C6F15B1E52040A142883E32944B40ABAAAAAAAAAA20408988888888884B40	\N	\N	urn:mrn:iho:fa:dk:ek-d-373	\N	\N	\N	1	1000040
1000048	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000048/	5	12	\N	\N	\N	urn:mrn:iho:country:fo	\N	\N	\N	0	\N
1000049	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000049/	6	13	\N	\N	\N	urn:mrn:iho:country:gl	\N	\N	\N	0	\N
1000050	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000049/1000050/	0	14	\N	\N	1	\N	\N	\N	\N	\N	1000049
1000051	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000049/1000051/	0	15	\N	\N	0	\N	\N	\N	\N	\N	1000049
1000052	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000052/	10	16	\N	\N	\N	urn:mrn:iho:country:se	\N	\N	\N	0	\N
1000053	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000053/	20	17	\N	\N	\N	urn:mrn:iho:country:no	\N	\N	\N	0	\N
1000054	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000054/	30	18	\N	\N	\N	urn:mrn:iho:country:uk	\N	\N	\N	0	\N
1000055	2026-10-22 00:00:00	2026-10-22 00:00:00	2	t	/1000055/	50	19	\N	\N	\N	urn:mrn:iho:country:de	\N	\N	\N	0	\N
\.


--
-- Data for Name: AreaDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."AreaDesc" (id, lang, name, entity_id) FROM stdin;
1000001	da	Danmark	1000001
1000002	en	Denmark	1000001
1000003	da	Østersøen	1000002
1000004	en	The Baltic Sea	1000002
1000005	en	ES D 140 Bornholm W.	1000003
1000006	da	ES D 140 Bornholm W.	1000003
1000007	en	ES D 138 Bornholm N.	1000004
1000008	da	ES D 138 Bornholm N.	1000004
1000009	en	ES D 139 Bornholm E.	1000005
1000010	da	ES D 139 Bornholm E.	1000005
1000011	en	EK D 395 Raghammer	1000006
1000012	da	EK D 395 Raghammer Odde	1000006
1000013	en	EK D 396 Hullebaek	1000007
1000014	da	EK D 396 Hullebæk	1000007
1000015	en	EK D 371 Marstal Bugt	1000008
1000016	da	EK D 371 Marstal Bugt	1000008
1000017	da	Sundet	1000009
1000018	en	The Sound	1000009
1000019	da	Smålandsfarvandet	1000010
1000020	en	The Waters South of Zealand	1000010
1000021	da	Storebælt	1000011
1000022	en	The Great Belt	1000011
1000023	da	Farvandet syd for Fyn	1000012
1000024	en	Waters South of Funen	1000012
1000025	da	Lillebælt	1000013
1000026	en	The Little Belt	1000013
1000027	en	EK R 44 Halk	1000014
1000028	da	EK R 44 Halk	1000014
1000029	en	14 Hyby Faelled	1000015
1000030	da	14 Hyby Fælled	1000015
1000031	da	Kattegat	1000016
1000032	en	Kattegat	1000016
1000033	en	EK D 352 Lysegrund N.	1000017
1000034	da	EK D 352 Lysegrund N	1000017
1000035	en	EK D 353 Lysegrund S.	1000018
1000036	da	EK D 353 Lysegrund S	1000018
1000037	en	EK R 18 Jaegerspris	1000019
1000038	da	EK R 18 Jægerspris	1000019
1000039	en	11 Bredetved	1000020
1000040	da	11 Bredetved	1000020
1000041	en	EK R 11 Sjaellands Odde W.	1000021
1000042	da	EK R 11 Sjællands Odde W.	1000021
1000043	en	EK R 12 Gniben	1000022
1000044	da	EK R 12 Gniben	1000022
1000045	en	EK R 13 Sjaellands Rev	1000023
1000046	da	EK R 13 Sjællands Rev	1000023
1000047	en	EK R 14 Sjaellands Odde E.	1000024
1000048	da	EK R 14 Sjællands Odde E.	1000024
1000049	en	EK D 350 Yderflak	1000025
1000050	da	EK D 350 Yderflak	1000025
1000051	en	EK D 351 Schultz Grund	1000026
1000052	da	EK D 351 Schultz Grund	1000026
1000053	en	13 Seden	1000027
1000054	da	13 Seden	1000027
1000055	en	EK R 45 Hevring	1000028
1000056	da	EK R 45 Hevring	1000028
1000057	en	EK R 15 Sejeroe E.	1000029
1000058	da	EK R 15 Sejerø E.	1000029
1000059	en	EK R 16 Sejeroe W.	1000030
1000060	da	EK R 16 Sejerø W.	1000030
1000061	en	EK R 17 Isefjord	1000031
1000062	da	EK R 17 Isefjord	1000031
1000063	en	EK R 19 MULTEX	1000032
1000064	da	EK R 19 MULTEX	1000032
1000065	en	EK R 20 Yderby	1000033
1000066	da	EK R 20 Yderby	1000033
1000067	da	Limfjorden	1000034
1000068	en	The Liim Fiord	1000034
1000069	da	Skagerrak	1000035
1000070	en	Skagerrak	1000035
1000071	en	EK D 389 Skagen N.	1000036
1000072	da	EK D 389 Skagen N	1000036
1000073	en	EK R 48 Tranum S.	1000037
1000074	da	EK R 48 Tranum S.	1000037
1000075	en	EK D 370 Blokhus	1000038
1000076	da	EK D 370 Blokhus	1000038
1000077	en	EK R 47 Tranum N.	1000039
1000078	da	EK R 47 Tranum N.	1000039
1000079	da	Nordsøen	1000040
1000080	en	The North Sea	1000040
1000081	en	15 Nymindegab	1000041
1000082	da	15 Nymindegab	1000041
1000083	en	EK R 33 Vejers	1000042
1000084	da	EK R 33 Vejers	1000042
1000085	en	EK D 380 Kallesmærsk E	1000043
1000086	da	EK D 380 Kallesmærsk E	1000043
1000087	en	EK D 381 Kallesmærsk W	1000044
1000088	da	EK D 381 Kallesmærsk W	1000044
1000089	da	EK R 53 Juvre	1000045
1000090	en	EK R 53 Juvre	1000045
1000091	en	EK R 38 Roemoe E.	1000046
1000092	da	EK R 38 Rømø E.	1000046
1000093	en	EK D 373 Roemoe W.	1000047
1000094	da	EK D 373 Rømø W	1000047
1000095	da	Færøerne	1000048
1000096	en	Faroe Islands	1000048
1000097	da	Grønland	1000049
1000098	en	Greenland	1000049
1000099	da	W-kyst	1000050
1000100	en	W-coast	1000050
1000101	da	E-kyst	1000051
1000102	en	E-coast	1000051
1000103	da	Sverige	1000052
1000104	en	Sweden	1000052
1000105	da	Norge	1000053
1000106	en	Norway	1000053
1000107	da	UK	1000054
1000108	en	UK	1000054
1000109	da	Tyskland	1000055
1000110	en	Germany	1000055
\.


--
-- Data for Name: Area_editorFields; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Area_editorFields" ("Area_id", "editorFields") FROM stdin;
\.


--
-- Data for Name: AtonNode; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."AtonNode" (id, changeset, geometry, lat, lon, "timestamp", uid, "user", version, visible) FROM stdin;
\.


--
-- Data for Name: AtonTag; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."AtonTag" (id, k, v, "atonNode_id") FROM stdin;
\.


--
-- Data for Name: Attachment; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Attachment" (id, display, "fileName", "fileSize", height, "indexNo", path, type, width, message_id) FROM stdin;
1000001	\N	sample-wreck-sketch.png	32298	\N	0	messages/5/a3/5a3f1c70-0000-4000-8000-000000000002/1/sample-wreck-sketch.png	image/png	\N	1000002
1000002	\N	sample-light-damaged.png	32298	\N	0	messages/5/a3/5a3f1c70-0000-4000-8000-000000000017/1/sample-light-damaged.png	image/png	\N	1000017
1000003	\N	sample-firing-area.png	32298	\N	0	messages/5/a3/5a3f1c70-0000-4000-8000-000000000028/1/sample-firing-area.png	image/png	\N	1000028
1000004	\N	sample-annex-overview.png	32298	\N	0	messages/5/a3/5a3f1c70-0000-4000-8000-000000000033/1/sample-annex-overview.png	image/png	\N	1000033
\.


--
-- Data for Name: AttachmentDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."AttachmentDesc" (id, lang, caption, entity_id) FROM stdin;
1000001	en	Sketch of the sample wreck (generated)	1000001
1000002	da	Skitse af eksempelvraget (genereret)	1000001
1000003	en	Sketch of the damaged sample light (generated)	1000002
1000004	da	Skitse af det beskadigede eksempelfyr (genereret)	1000002
1000005	en	Overview of the sample firing area (generated)	1000003
1000006	da	Oversigt over eksempelskydeområdet (genereret)	1000003
1000007	en	Overview of the sample annex (generated)	1000004
1000008	da	Oversigt over eksempelbilaget (genereret)	1000004
\.


--
-- Data for Name: AudioMessagePromulgation; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."AudioMessagePromulgation" (text, id) FROM stdin;
\.


--
-- Data for Name: BaseMessagePromulgation; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."BaseMessagePromulgation" (id, created, updated, version, promulgate, message_id, type_id) FROM stdin;
\.


--
-- Data for Name: Category; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Category" (id, created, updated, version, active, lineage, "siblingSortOrder", "treeSortOrder", "atonFilter", "legacyId", "messageId", mrn, type, parent_id) FROM stdin;
1	2026-10-22 00:00:00	2026-10-22 00:00:00	5	t	/1/	-10	0	aton.kv('seamark:type', 'light.*')	\N	\N	\N	CATEGORY	\N
2	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/1/2/	0	0	\N	\N	\N	\N	TEMPLATE	1
3	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/1/3/	0	0	\N	\N	\N	\N	TEMPLATE	1
4	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/1/4/	0	0	\N	\N	\N	\N	TEMPLATE	1
5	2026-10-22 00:00:00	2026-10-22 00:00:00	13	t	/5/	-5	0	aton.kv('seamark:type', 'buoy.*') && aton.k('seamark:light.*')	\N	\N	\N	CATEGORY	\N
6	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/5/6/	1	0	\N	\N	\N	\N	TEMPLATE	5
7	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/5/7/	1.5	0	\N	\N	\N	\N	TEMPLATE	5
8	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/5/8/	2	0	\N	\N	\N	\N	TEMPLATE	5
9	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/5/9/	4	0	\N	\N	\N	\N	TEMPLATE	5
10	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/5/10/	4.5	0	\N	\N	\N	\N	TEMPLATE	5
11	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/5/11/	4.75	0		\N	\N	\N	TEMPLATE	5
12	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/5/12/	5	0	\N	\N	\N	\N	TEMPLATE	5
13	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/13/	17	0	\N	\N	\N	\N	CATEGORY	\N
14	2026-10-22 00:00:00	2026-10-22 00:00:00	9	t	/14/	0	0	aton.kv('seamark:type', 'buoy.*') && !aton.k('seamark:light.*')	\N	\N	\N	CATEGORY	\N
15	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/14/15/	-20	0	\N	\N	\N	\N	TEMPLATE	14
16	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/14/16/	-15	0	\N	\N	\N	\N	TEMPLATE	14
17	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/14/17/	-10	0	\N	\N	\N	\N	TEMPLATE	14
18	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/14/18/	0	0		\N	\N	\N	TEMPLATE	14
19	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/14/19/	2	0	\N	\N	\N	\N	TEMPLATE	14
20	2026-10-22 00:00:00	2026-10-22 00:00:00	5	t	/20/	0	0	aton.kv('seamark:type', 'beacon.*')	\N	\N	\N	CATEGORY	\N
21	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/20/21/	-20	0	\N	\N	\N	\N	TEMPLATE	20
22	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/20/22/	-15	0	\N	\N	\N	\N	TEMPLATE	20
23	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/20/23/	-10	0	\N	\N	\N	\N	TEMPLATE	20
24	2026-10-22 00:00:00	2026-10-22 00:00:00	27	t	/24/	1.5	0	\N	\N	\N	\N	CATEGORY	\N
25	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/25/	0	0	\N	\N	\N	\N	TEMPLATE	24
26	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/26/	0	0	\N	\N	\N	\N	TEMPLATE	24
27	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/27/	0	0	\N	\N	\N	\N	TEMPLATE	24
28	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/28/	0	0	\N	\N	\N	\N	TEMPLATE	24
29	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/29/	2	0	\N	\N	\N	\N	TEMPLATE	24
30	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/30/	3	0	\N	\N	\N	\N	TEMPLATE	24
31	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/31/	4	0	\N	\N	\N	\N	TEMPLATE	24
32	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/32/	6	0	\N	\N	\N	\N	TEMPLATE	24
33	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/33/	6.1	0	\N	\N	\N	\N	TEMPLATE	24
34	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/34/	6.25	0	aton.kv('seamark:type', 'light.*')	\N	\N	\N	TEMPLATE	24
35	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/35/	6.5	0	\N	\N	\N	\N	TEMPLATE	24
36	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/36/	11.5	0	\N	\N	\N	\N	TEMPLATE	24
37	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/37/	16.5	0	\N	\N	\N	\N	TEMPLATE	24
38	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/24/38/	26.5	0	\N	\N	\N	\N	TEMPLATE	24
39	2026-10-22 00:00:00	2026-10-22 00:00:00	3	t	/39/	3.5	0	\N	\N	\N	\N	CATEGORY	\N
40	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/39/40/	0	0	\N	\N	\N	\N	TEMPLATE	39
41	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/39/41/	0	0	aton.kv('seamark:type', 'buoy.*|beacon.*')	\N	\N	\N	TEMPLATE	39
42	2026-10-22 00:00:00	2026-10-22 00:00:00	5	t	/42/	4	0	\N	\N	\N	\N	CATEGORY	\N
43	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/42/43/	0	0	aton.kv('seamark:radar_transponder:category', 'racon')	\N	\N	\N	TEMPLATE	42
44	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/42/44/	0	0	aton.kv('radio_transponder:AIS', 'yes')	\N	\N	\N	TEMPLATE	42
45	2026-10-22 00:00:00	2026-10-22 00:00:00	1	t	/42/45/	0	0	aton.kv('seamark:radio_station:category', 'dgps')	\N	\N	\N	TEMPLATE	42
\.


--
-- Data for Name: CategoryDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."CategoryDesc" (id, lang, name, entity_id) FROM stdin;
1	en	Light	1
2	en	Light unlit	2
3	en	Light unreliable	3
4	en	Light damaged	4
5	en	Light buoy	5
6	en	Light buoy missing	6
7	en	Light buoy off station	7
8	en	Light buoy unlit	8
9	en	Light buoy unreliable	9
10	en	Light buoy established	10
11	en	Light buoy replaced	11
12	en	Light buoy withdrawn	12
13	en	Firing Exercises	13
14	en	Buoy	14
15	en	Buoy missing	15
16	en	Buoy off station	16
17	en	Buoy established	17
18	en	Buoy replaced	18
19	en	Buoy withdrawn	19
20	en	Beacon	20
21	en	Beacon missing	21
22	en	Beacon damaged	22
23	en	Beacon partly obscured	23
24	en	Obstruction	24
25	en	Wreck	25
26	en	Uncharted obstruction	26
27	en	Reduced depth	27
28	en	Salvage operations	28
29	en	Hydrographic survey	29
30	en	Cable operations	30
31	en	Underwater survey	31
32	en	Diving operations	32
33	en	Bridge closed for passage	33
34	en	Light destroyed	34
35	en	Exercise	35
36	en	Mine	36
37	en	Torpedo	37
38	en	Grenade	38
39	en	Drifting object	39
40	en	Object adrift	40
41	en	Buoy adrift	41
42	en	Radio navigation	42
43	en	RACON	43
44	en	AIS	44
45	en	DGPS	45
1000001	da	Fyr	1
1000002	da	Fyr slukket	2
1000003	da	Fyr uregelmæssig	3
1000004	da	Fyr ødelagt	4
1000005	da	Lystønde	5
1000006	da	Lystønde forsvundet	6
1000007	da	Lystønde ikke på plads og i orden	7
1000008	da	Lystønde slukket	8
1000009	da	Lystønde uregelmæssig	9
1000010	da	Lystønde etableret	10
1000011	da	Lystønde erstattet	11
1000012	da	Lystønde inddraget	12
1000013	da	Dagsømærke	14
1000014	da	Dagsømærke forsvundet	15
1000015	da	Dagsømærke ikke på plads og i orden	16
1000016	da	Dagsømærke etableret	17
1000017	da	Dagsømærke erstattet	18
1000018	da	Dagsømærke inddraget	19
1000019	da	Båke	20
1000020	da	Båke forsvundet	21
1000021	da	Båke ødelagt	22
1000022	da	Båke delvis skjult	23
1000023	da	Sejladshindring	24
1000024	da	Ikke kortlagt sejladshindring	26
1000025	da	Vrag	25
1000026	da	Reduceret vanddybde	27
1000027	da	Bjærgningsarbejde	28
1000028	da	Søopmåling	29
1000029	da	Kabelarbejde	30
1000030	da	Undervandsundersøgelse	31
1000031	da	Dykkerundersøgelse	32
1000032	da	Bro lukket for passage	33
1000033	da	Fyr væltet	34
1000034	da	Øvelse	35
1000035	da	Mine	36
1000036	da	Torpedo	37
1000037	da	Granat	38
1000038	da	Drivende genstand	39
1000039	da	Drivende objekt	40
1000040	da	Drivende sømærke	41
1000041	da	Radionavigation	42
1000042	da	RACON	43
1000043	da	AIS	44
1000044	da	DGPS	45
1000045	da	Skydeøvelser	13
\.


--
-- Data for Name: Category_Domain; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Category_Domain" ("Category_id", domains_id) FROM stdin;
10	1000001
11	1000001
12	1000001
15	1000001
16	1000001
17	1000001
18	1000001
19	1000001
2	1000001
21	1000001
22	1000001
23	1000001
25	1000001
26	1000001
27	1000001
28	1000001
29	1000001
3	1000001
30	1000001
31	1000001
32	1000001
33	1000001
34	1000001
35	1000001
36	1000001
37	1000001
38	1000001
4	1000001
40	1000001
41	1000001
43	1000001
44	1000001
45	1000001
6	1000001
7	1000001
8	1000001
9	1000001
\.


--
-- Data for Name: Category_TemplateParam; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Category_TemplateParam" ("Category_id", "templateParams_id", "indexNo") FROM stdin;
2	1	0
3	2	0
4	3	0
6	4	0
7	5	0
8	6	0
9	7	0
10	8	0
11	9	0
12	10	0
12	11	1
15	12	0
16	13	0
17	14	0
18	15	0
19	16	0
19	17	1
21	18	0
22	19	0
23	20	0
25	21	0
26	22	0
27	23	0
28	24	0
33	25	0
34	26	0
35	27	0
40	28	0
40	29	1
41	30	0
41	31	1
43	32	0
44	33	0
45	1000001	0
\.


--
-- Data for Name: Category_editorFields; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Category_editorFields" ("Category_id", "editorFields") FROM stdin;
13	prohibition
13	signals
\.


--
-- Data for Name: Category_scriptResourcePaths; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Category_scriptResourcePaths" ("Category_id", "scriptResourcePaths", "indexNo") FROM stdin;
2	templates/tmpl/light-unlit.ftl	0
2	templates/tmpl/compute-affected-radius.js	1
3	templates/tmpl/light-unreliable.ftl	0
3	templates/tmpl/compute-affected-radius.js	1
4	templates/tmpl/light-damaged.ftl	0
4	templates/tmpl/compute-affected-radius.js	1
6	templates/tmpl/light-buoy-missing.ftl	0
6	templates/tmpl/compute-affected-radius.js	1
7	templates/tmpl/light-buoy-off-station.ftl	0
7	templates/tmpl/compute-affected-radius.js	1
8	templates/tmpl/light-buoy-unlit.ftl	0
8	templates/tmpl/compute-affected-radius.js	1
9	templates/tmpl/light-buoy-unreliable.ftl	0
9	templates/tmpl/compute-affected-radius.js	1
10	templates/tmpl/light-buoy-established.ftl	0
10	templates/tmpl/compute-affected-radius.js	1
11	templates/tmpl/light-buoy-replaced.ftl	0
11	templates/tmpl/compute-affected-radius.js	1
12	templates/tmpl/light-buoy-withdrawn.ftl	0
12	templates/tmpl/compute-affected-radius.js	1
15	templates/tmpl/buoy-missing.ftl	0
16	templates/tmpl/buoy-off-station.ftl	0
17	templates/tmpl/buoy-established.ftl	0
18	templates/tmpl/buoy-replaced.ftl	0
19	templates/tmpl/buoy-withdrawn.ftl	0
21	templates/tmpl/beacon-missing.ftl	0
22	templates/tmpl/beacon-damaged.ftl	0
23	templates/tmpl/beacon-partly-obscured.ftl	0
25	templates/tmpl/wreck.ftl	0
26	templates/tmpl/uncharted-obstruction.ftl	0
27	templates/tmpl/reduced-depth.ftl	0
28	templates/tmpl/salvage-operations.ftl	0
29	templates/tmpl/hydrographic-survey.ftl	0
30	templates/tmpl/cable-opetations.ftl	0
31	templates/tmpl/underwater-survey.ftl	0
32	templates/tmpl/diving-operations.ftl	0
33	templates/tmpl/bridge-closed.ftl	0
34	templates/tmpl/light-destroyed.ftl	0
35	templates/tmpl/exercise.ftl	0
36	templates/tmpl/mine.ftl	0
37	templates/tmpl/torpedo.ftl	0
38	templates/tmpl/grenade.ftl	0
40	templates/tmpl/object-adrift.js	0
40	templates/tmpl/object-adrift.ftl	1
41	templates/tmpl/object-adrift.js	0
41	templates/tmpl/object-adrift.ftl	1
43	templates/tmpl/radio-navigation.ftl	0
44	templates/tmpl/radio-navigation.ftl	0
45	templates/tmpl/dgps.js	0
45	templates/tmpl/dgps.ftl	1
\.


--
-- Data for Name: Category_stdTemplateFields; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Category_stdTemplateFields" ("Category_id", "stdTemplateFields") FROM stdin;
2	areas
2	type
2	promulgation
2	positions
3	areas
3	type
3	promulgation
3	positions
4	type
4	promulgation
4	areas
4	positions
6	type
6	promulgation
6	areas
6	positions
7	type
7	promulgation
7	areas
7	positions
8	type
8	promulgation
8	areas
8	positions
9	type
9	promulgation
9	areas
9	positions
10	type
10	promulgation
10	areas
10	positions
11	type
11	promulgation
11	areas
11	positions
12	type
12	promulgation
12	areas
12	positions
15	areas
15	type
15	promulgation
15	positions
16	areas
16	type
16	promulgation
16	positions
17	type
17	promulgation
17	areas
17	positions
18	type
18	promulgation
18	areas
18	positions
19	areas
19	type
19	promulgation
19	positions
21	areas
21	type
21	promulgation
21	positions
22	areas
22	type
22	promulgation
22	positions
23	type
23	promulgation
23	areas
23	positions
25	type
25	promulgation
25	areas
25	positions
25	markings
26	type
26	promulgation
26	areas
26	positions
26	markings
27	type
27	promulgation
27	areas
27	positions
28	type
28	promulgation
28	areas
28	positions
28	event_dates
28	work_vessel
29	type
29	promulgation
29	areas
29	positions
29	event_dates
29	work_vessel
30	type
30	promulgation
30	areas
30	positions
30	event_dates
30	work_vessel
31	type
31	promulgation
31	areas
31	positions
31	event_dates
31	work_vessel
32	type
32	promulgation
32	areas
32	positions
32	event_dates
32	work_vessel
33	type
33	promulgation
33	areas
33	positions
34	type
34	promulgation
34	areas
34	positions
34	markings
35	type
35	promulgation
35	areas
35	positions
35	event_dates
36	type
36	promulgation
36	areas
36	positions
37	type
37	promulgation
37	areas
37	positions
38	type
38	promulgation
38	areas
38	positions
40	type
40	promulgation
40	positions
40	areas
41	type
41	promulgation
41	positions
41	areas
43	type
43	promulgation
43	areas
43	positions
44	type
44	promulgation
44	areas
44	positions
45	type
45	promulgation
45	event_dates
\.


--
-- Data for Name: Chart; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Chart" (id, created, updated, version, active, "chartNumber", geometry, "horizontalDatum", "internationalNumber", "legacyId", name, scale) FROM stdin;
1000001	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	D	\N	WGS84	\N	\N	Danmark med søgrænser	850000
1000002	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	C	\N	WGS84	\N	\N	Danmark med omgivende farvande	850000
1000003	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	198	0103000000010000000500000000000000000028407417F6EEEE5E4B409C2AA588888829407417F6EEEE5E4B409C2AA588888829408CE8091111B14B4000000000000028408CE8091111B14B4000000000000028407417F6EEEE5E4B40	WGS84	\N	\N	Østersøen, Fakse Bugt og Hjelm Bugt	75000
1000004	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	197	01030000000100000005000000CDCCCCCCCC4C274059B5D6DDDD2D4B409A9999999999294059B5D6DDDD2D4B409A99999999992940F34E707777674B40CDCCCCCCCC4C2740F34E707777674B40CDCCCCCCCC4C274059B5D6DDDD2D4B40	WGS84	\N	\N	Østersøen, Gedser Rev og Kadetrenden	75000
1000005	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	196	01030000000100000005000000DBF97E6ABC5425400E2DB29DEF2F4B40AD82DE3C517F27400E2DB29DEF2F4B40AD82DE3C517F274010DC169F61674B40DBF97E6ABC54254010DC169F61674B40DBF97E6ABC5425400E2DB29DEF2F4B40	WGS84	\N	\N	Femer Bælt	75000
1000006	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	195	0103000000010000000500000069F77155555523406666666666364B4036C43E2222A225406666666666364B4036C43E2222A2254055920A9D36704B4069F771555555234055920A9D36704B4069F77155555523406666666666364B40	WGS84	\N	\N	Østersøen, Vestlige del	75000
1000007	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	190	010300000001000000050000000000000000002840CFF753E3A58B4B40AD82DE3C51FF2840CFF753E3A58B4B40AD82DE3C51FF2840F579F78D50A64B400000000000002840F579F78D50A64B400000000000002840CFF753E3A58B4B40	WGS84	\N	\N	Fakse Bugt	50000
1000008	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	189	01030000000100000005000000FD6EF41011912B403333333333734B400000000000802E403333333333734B400000000000802E4041E4C2BBBBBB4B40FD6EF41011912B4041E4C2BBBBBB4B40FD6EF41011912B403333333333734B40	WGS84	1336	\N	Bornholmsgat	100000
1000009	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	188	0103000000010000000500000097088EAAAA2A2A40DA7D5C5555354B40B5FBB8AAAAAA3040DA7D5C5555354B40B5FBB8AAAAAA30402682A3AAAAEA4B4097088EAAAA2A2A402682A3AAAAEA4B4097088EAAAA2A2A40DA7D5C5555354B40	WGS84	\N	\N	Østersøen, Gedser Rev - Christiansø	250000
1000010	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	172	0103000000010000000500000048E17A14AE4725403333333333734B407B14AE47E17A25403333333333734B407B14AE47E17A254011D45BC92F7E4B4048E17A14AE47254011D45BC92F7E4B4048E17A14AE4725403333333333734B40	WGS84	\N	\N	Rudkøbing Løb	15000
1000011	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	171	0103000000010000000500000026CB83D3061A2540295C8FC2F5804B40D412654E1B682540295C8FC2F5804B40D412654E1B6825401BABFF396D884B4026CB83D3061A25401BABFF396D884B4026CB83D3061A2540295C8FC2F5804B40	WGS84	\N	\N	Svendborg Sund	10000
1000012	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	164	0103000000010000000500000003910BEFEE2E2740FD2514E8B4914B40DA347C2CF9852740FD2514E8B4914B40DA347C2CF98527403D0AD7A3709D4B4003910BEFEE2E27403D0AD7A3709D4B4003910BEFEE2E2740FD2514E8B4914B40	WGS84	\N	\N	Karrebæk Fjord	16000
1000013	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	163	01030000000100000005000000871FD6FAA6432740101856B71B534B4036C43E2222E22740101856B71B534B4036C43E2222E2274085EB51B81E754B40871FD6FAA643274085EB51B81E754B40871FD6FAA6432740101856B71B534B40	WGS84	\N	\N	Guldborg Sund	30000
1000014	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	162	0103000000010000000500000042C669A938BB27406666666666664B406666666666A628406666666666664B406666666666A628404C378941607D4B4042C669A938BB27404C378941607D4B4042C669A938BB27406666666666664B40	WGS84	\N	\N	Smålandsfarvandet, Sydøstlige del, Grønsund	30000
1000015	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	161	0103000000010000000500000003910BEFEEAE2740A74A2922227A4B409A99999999992840A74A2922227A4B409A99999999992840295C8FC2F5904B4003910BEFEEAE2740295C8FC2F5904B4003910BEFEEAE2740A74A2922227A4B40	WGS84	\N	\N	Smålandsfarvandet, Nordøstlige del, Bøgestrømmen	30000
1000016	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	160	010300000001000000050000003D0AD7A3703D26404F117559AB5B4B407A4FAB091ECC27404F117559AB5B4B407A4FAB091ECC2740430F4AD294AA4B403D0AD7A3703D2640430F4AD294AA4B403D0AD7A3703D26404F117559AB5B4B40	WGS84	\N	\N	Smålandsfarvandet, Vestlige del	75000
1000017	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	159	0103000000010000000500000069F7715555D52240AE47E17A147E4B409A99999999992340AE47E17A147E4B409A999999999923408CE8091111914B4069F7715555D522408CE8091111914B4069F7715555D52240AE47E17A147E4B40	WGS84	1374	\N	Åbenrå Fjord	25000
1000018	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	158	0103000000010000000500000075F99CDC6BEE2240F34E707777B74B40AB920A5B9AB22340F34E707777B74B40AB920A5B9AB223400AD7A3703DCA4B4075F99CDC6BEE22400AD7A3703DCA4B4075F99CDC6BEE2240F34E707777B74B40	WGS84	1376	\N	Snævringen og Kolding Fjord	25000
1000019	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	157	01030000000100000005000000FD6EF4101111234059B5D6DDDDCD4B4069F7715555D5234059B5D6DDDDCD4B4069F7715555D52340CDCCCCCCCCDC4B40FD6EF41011112340CDCCCCCCCCDC4B40FD6EF4101111234059B5D6DDDDCD4B40	WGS84	\N	\N	Vejle Fjord	25000
1000020	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	155	010300000001000000050000004F271396FC6223407EEE995F2C694B40CDCCCCCCCCCC23407EEE995F2C694B40CDCCCCCCCCCC2340AB6DF562C97F4B404F271396FC622340AB6DF562C97F4B404F271396FC6223407EEE995F2C694B40	WGS84	\N	\N	Als Sund og Sønderborg Bugt	20000
1000021	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	154	01030000000100000005000000CDCCCCCCCCCC22406A40527EB1644B4000000000008023406A40527EB1644B4000000000008023409D7385B1E4774B40CDCCCCCCCCCC22409D7385B1E4774B40CDCCCCCCCCCC22406A40527EB1644B40	WGS84	\N	\N	Flensborg Fjord	25000
1000022	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	153	01030000000100000005000000E17A14AE47F122401755F10CBB9E4B401E1F6779A26E23401755F10CBB9E4B401E1F6779A26E2340FD5856D4BFA54B40E17A14AE47F12240FD5856D4BFA54B40E17A14AE47F122401755F10CBB9E4B40	WGS84	\N	\N	Haderslev Fjord	25000
1000023	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	152	01030000000100000005000000D05DD8BBBB3B2340A74A292222624B409C2AA58888882540A74A292222624B409C2AA5888888254096BFAD814E9B4B40D05DD8BBBB3B234096BFAD814E9B4B40D05DD8BBBB3B2340A74A292222624B40	WGS84	1373	\N	Lillebælt, Sydlige del & Farvandet syd for Fyn	75000
1000024	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	151	01030000000100000005000000CDCCCCCCCCCC2240225FD769037D4B4069F7715555552440225FD769037D4B4069F77155555524408CE8091111D14B40CDCCCCCCCCCC22408CE8091111D14B40CDCCCCCCCCCC2240225FD769037D4B40	WGS84	1375	\N	Lillebælt, nordlige del	75000
1000025	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	145	0103000000010000000500000030A22744448425400000000000D04B409C2AA588884826400000000000D04B409C2AA58888482640188833F9C5E24B4030A2274444842540188833F9C5E24B4030A22744448425400000000000D04B40	WGS84	1371	\N	Kalundborg Fjord	25000
1000026	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	144	01030000000100000005000000FD0F76AB63C22540C04E7F304F644B4032CDAE5A1D532640C04E7F304F644B4032CDAE5A1D532640188833F9C5724B40FD0F76AB63C22540188833F9C5724B40FD0F76AB63C22540C04E7F304F644B40	WGS84	\N	\N	Nakskov Fjord	20000
1000027	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	143	01030000000100000005000000CDCCCCCCCC4C25407417F6EEEE8E4B4069F7715555D526407417F6EEEE8E4B4069F7715555D52640CDCCCCCCCCB44B40CDCCCCCCCC4C2540CDCCCCCCCCB44B40CDCCCCCCCC4C25407417F6EEEE8E4B40	WGS84	1369	\N	Storebælt, Sprogø - Langeland	50000
1000028	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	142	01030000000100000005000000CDCCCCCCCC4C2540DA7D5C5555554B406EACFEE7B4C12640DA7D5C5555554B406EACFEE7B4C1264011D45BC92FA64B40CDCCCCCCCC4C254011D45BC92FA64B40CDCCCCCCCC4C2540DA7D5C5555554B40	WGS84	1368	\N	Storebælt, Sydlige del	75000
1000029	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	141	01030000000100000005000000DBF97E6ABC142540894160E5D09A4B409C2AA58888882640894160E5D09A4B409C2AA58888882640430F4AD294EA4B40DBF97E6ABC142540430F4AD294EA4B40DBF97E6ABC142540894160E5D09A4B40	WGS84	1370	\N	Storebælt, Nordlige del	75000
1000030	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	134	010300000001000000050000002DC89E563412294055920A9D36D04B4002B6074A9F54294055920A9D36D04B4002B6074A9F542940AE47E17A14DE4B402DC89E5634122940AE47E17A14DE4B402DC89E563412294055920A9D36D04B40	WGS84	1334	\N	Københavns Havn	12500
1000031	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	133	01030000000100000005000000DBF97E6ABCD428400E2DB29DEFBF4B40DA93FA91A6542A400E2DB29DEFBF4B40DA93FA91A6542A406891ED7C3FE54B40DBF97E6ABCD428406891ED7C3FE54B40DBF97E6ABCD428400E2DB29DEFBF4B40	WGS84	1333	\N	Sundet, midterste del	50000
1000032	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	132	0103000000010000000500000064D55A77773728406A40527EB1A44B406666666666662A406A40527EB1A44B406666666666662A40B121CD925FDC4B4064D55A7777372840B121CD925FDC4B4064D55A77773728406A40527EB1A44B40	WGS84	1332	\N	Sundet, Sydlige del	75000
1000033	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	131	01030000000100000005000000925301184B7E28409D7385B1E4D74B40F39750A0D3062A409D7385B1E4D74B40F39750A0D3062A40F34E707777274C40925301184B7E2840F34E707777274C40925301184B7E28409D7385B1E4D74B40	WGS84	1331	\N	Sundet, Nordlige del	75000
1000034	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	129	0103000000010000000500000041FA60F80CBB2640E5D022DBF9F64B40742D942B40EE2840E5D022DBF9F64B40742D942B40EE2840DBF97E6ABC2C4C4041FA60F80CBB2640DBF97E6ABC2C4C4041FA60F80CBB2640E5D022DBF9F64B40	WGS84	\N	\N	Kattegat, Sydøstlige del	75000
1000035	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	128	01030000000100000005000000DA347C2CF985254052B81E85EBD94B40189ED1351712274052B81E85EBD94B40189ED1351712274095C76857802C4C40DA347C2CF985254095C76857802C4C40DA347C2CF985254052B81E85EBD94B40	WGS84	1379	\N	Samsø Bælt	75000
1000036	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	127	0103000000010000000500000066666666666624407417F6EEEE0E4C40EEE2C30D749A24407417F6EEEE0E4C40EEE2C30D749A24409D7385B1E4174C4066666666666624409D7385B1E4174C4066666666666624407417F6EEEE0E4C40	WGS84	1372	\N	Århus Havn	10000
1000037	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	124	01030000000100000005000000D868737BCDA5254041E4C2BBBB2B4C407A4FAB091ECC274041E4C2BBBB2B4C407A4FAB091ECC2740355EBA490C624C40D868737BCDA52540355EBA490C624C40D868737BCDA5254041E4C2BBBB2B4C40	WGS84	\N	\N	Kattegat, Syd for Anholt	75000
1000038	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	123	0103000000010000000500000036C43E2222A224406666666666864C4003910BEFEEEE26406666666666864C4003910BEFEEEE2640BF1B3D4444BC4C4036C43E2222A22440BF1B3D4444BC4C4036C43E2222A224406666666666864C40	WGS84	\N	\N	Kattegat, Læsø Rende	75000
1000039	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	122	0103000000010000000500000056172ADE4C7F2440E22979AFB93C4C4032EFAB5193032640E22979AFB93C4C4032EFAB5193032640F697DD9387894C4056172ADE4C7F2440F697DD9387894C4056172ADE4C7F2440E22979AFB93C4C40	WGS84	\N	\N	Ålborg Bugt	75000
1000040	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	118	01030000000100000005000000DBF97E6ABCD427409A15BCAE00D14B40DA93FA91A65428409A15BCAE00D14B40DA93FA91A65428405BE05DF4B6EC4B40DBF97E6ABCD427405BE05DF4B6EC4B40DBF97E6ABCD427409A15BCAE00D14B40	WGS84	\N	\N	Roskilde Fjord, Frederikssund - Roskilde	25000
1000041	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	117	01030000000100000005000000E17A14AE47A12740430F4AD294EA4B406666666666262840430F4AD294EA4B4066666666662628408E139127EAFF4B40E17A14AE47A127408E139127EAFF4B40E17A14AE47A12740430F4AD294EA4B40	WGS84	\N	\N	Roskilde Fjord, Lynæs - Frederikssund	25000
1000042	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	116	01030000000100000005000000A3AB3ACC133527408716D9CEF7D34B40B75982AD8E0928408716D9CEF7D34B40B75982AD8E09284055920A9D36004C40A3AB3ACC1335274055920A9D36004C40A3AB3ACC133527408716D9CEF7D34B40	WGS84	\N	\N	Isefjorden	40000
1000043	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	115	010300000001000000050000000000000000C024400AD7A3703DB24B4097088EAAAA2A25400AD7A3703DB24B4097088EAAAA2A25400DB18F8888C84B400000000000C024400DB18F8888C84B400000000000C024400AD7A3703DB24B40	WGS84	\N	\N	Odense Fjord	20000
1000044	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	114	01030000000100000005000000CDCCCCCCCC0C23400000000000C04B4069F77155555525400000000000C04B4069F7715555552540F34E707777F74B40CDCCCCCCCC0C2340F34E707777F74B40CDCCCCCCCC0C23400000000000C04B40	WGS84	1377	\N	Farvandet nord for Fyn	75000
1000045	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	113	01030000000100000005000000C9DC427830AF234023DBF97E6AE44B404E6210583934244023DBF97E6AE44B404E62105839342440355EBA490CF24B40C9DC427830AF2340355EBA490CF24B40C9DC427830AF234023DBF97E6AE44B40	WGS84	\N	\N	Horsens Fjord	25000
1000046	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	112	01030000000100000005000000C3F5285C8F422440D0A6B8E417DB4B40173F53D069C32540D0A6B8E417DB4B40173F53D069C3254041E4C2BBBB2B4C40C3F5285C8F42244041E4C2BBBB2B4C40C3F5285C8F422440D0A6B8E417DB4B40	WGS84	1380	\N	Århus Bugt	75000
1000047	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	111	01030000000100000005000000CDCCCCCCCC0C2440B54499D3063A4C40CA3BC1DDDDDD2440B54499D3063A4C40CA3BC1DDDDDD24400000000000504C40CDCCCCCCCC0C24400000000000504C40CDCCCCCCCC0C2440B54499D3063A4C40	WGS84	1381	\N	Randers Fjord	30000
1000048	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	110	01030000000100000005000000B1D8EC69031D2440BF1B3D4444544C4030A2274444042540BF1B3D4444544C4030A2274444042540821166A0D35E4C40B1D8EC69031D2440821166A0D35E4C40B1D8EC69031D2440BF1B3D4444544C40	WGS84	\N	\N	Mariager Fjord	30000
1000049	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	109	0103000000010000000500000077BE9F1A2F5D21404260E5D022434C40742D942B40AE22404260E5D022434C40742D942B40AE2240F853E3A59B844C4077BE9F1A2F5D2140F853E3A59B844C4077BE9F1A2F5D21404260E5D022434C40	WGS84	1449	\N	Limfjorden, Mors - Løgstør	65000
1000050	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	108	01030000000100000005000000D412654E1B4820402682A3AAAA3A4C4014AE47E17A9421402682A3AAAA3A4C4014AE47E17A9421404FDE326DA07B4C40D412654E1B4820404FDE326DA07B4C40D412654E1B4820402682A3AAAA3A4C40	WGS84	1448	\N	Limfjorden, Thyborøn - Mors	65000
1000051	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	107	0103000000010000000500000048E17A14AEC723404FDE326DA0834C407EA5B936D02924404FDE326DA0834C407EA5B936D02924406A40527EB18C4C4048E17A14AEC723406A40527EB18C4C4048E17A14AEC723404FDE326DA0834C40	WGS84	1383	\N	Limfjorden, Ålborg Havn	13000
1000052	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	106	01030000000100000005000000074698814EDB2340A74A2922227A4C40F4635951FFC62440A74A2922227A4C40F4635951FFC624408E139127EA8F4C40074698814EDB23408E139127EA8F4C40074698814EDB2340A74A2922227A4C40	WGS84	1382	\N	Limfjorden, Hals - Ålborg	30000
1000053	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	105	010300000001000000050000007883A258F24B2240B54499D3067A4C403333333333332340B54499D3067A4C4033333333333323401BABFF396D884C407883A258F24B22401BABFF396D884C407883A258F24B2240B54499D3067A4C40	WGS84	1450	\N	Limfjorden, Ålborg - Løgstør	30000
1000054	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	104	0103000000010000000500000097088EAAAAAA26402682A3AAAAFA4A40CDCCCCCCCCCC2A402682A3AAAAFA4A40CDCCCCCCCCCC2A4059B5D6DDDDDD4B4097088EAAAAAA264059B5D6DDDDDD4B4097088EAAAAAA26402682A3AAAAFA4A40	WGS84	\N	\N	Østersøen, Femern Bælt-Sundet	200000
1000055	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	103	01030000000100000005000000FD6EF41011D122402682A3AAAAFA4A403333333333F326402682A3AAAAFA4A403333333333F3264059B5D6DDDDDD4B40FD6EF41011D1224059B5D6DDDDDD4B40FD6EF41011D122402682A3AAAAFA4A40	WGS84	1303	\N	Bælthavet	200000
1000056	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	102	01030000000100000005000000CDCCCCCCCCCC23408CE8091111D14B403333333333F329408CE8091111D14B403333333333F32940DA7D5C5555654C40CDCCCCCCCCCC2340DA7D5C5555654C40CDCCCCCCCCCC23408CE8091111D14B40	WGS84	1302	\N	Kattegat, Sydlige del	200000
1000057	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	101	01030000000100000005000000D05DD8BBBBBB23409A99999999594C4036C43E2222E229409A99999999594C4036C43E2222E229409A99999999E94C40D05DD8BBBBBB23409A99999999E94C40D05DD8BBBBBB23409A99999999594C40	WGS84	1301	\N	Kattegat, Nordlige del	200000
1000058	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	100	0103000000010000000500000000000000000024409A99999999D94B4097088EAAAA2A2A409A99999999D94B4097088EAAAA2A2A400000000000004D4000000000000024400000000000004D4000000000000024409A99999999D94B40	WGS84	\N	\N	Kattegat	300000
1000059	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	99	01030000000100000005000000448B6CE7FB292040DCA8E36B2EE44B40AD82DE3C51FF2040DCA8E36B2EE44B40AD82DE3C51FF2040A975B038FB104C40448B6CE7FB292040A975B038FB104C40448B6CE7FB292040DCA8E36B2EE44B40	WGS84	\N	\N	Ringkøbing Fjord	50000
1000060	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	95	010300000001000000050000003C699F96385B20404260E5D022B34B40B98409C467F820404260E5D022B34B40B98409C467F82040355EBA490CC24B403C699F96385B2040355EBA490CC24B403C699F96385B20404260E5D022B34B40	WGS84	1451	\N	Grådyb	20000
1000061	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	94	01030000000100000005000000FADDE82122221B40DA7D5C5555754B400000000000802140DA7D5C5555754B400000000000802140F34E707777D74B40FADDE82122221B40F34E707777D74B40FADDE82122221B40DA7D5C5555754B40	WGS84	1411	\N	Horns Rev	130000
1000062	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	93	01030000000100000005000000B30A7AF344FD07408E139127EA7F4B40DA93FA91A65422408E139127EA7F4B40DA93FA91A654224028AD2AC183994C40B30A7AF344FD074028AD2AC183994C40B30A7AF344FD07408E139127EA7F4B40	WGS84	1044	\N	Jyllands Vestkyst	375000
1000063	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	92	01030000000100000005000000D2EEE3AAAAAA18402682A3AAAA8A4C4000000000000028402682A3AAAA8A4C400000000000002840DA7D5C5555954D40D2EEE3AAAAAA1840DA7D5C5555954D40D2EEE3AAAAAA18402682A3AAAA8A4C40	WGS84	1300	\N	Skagerrak	360000
1000064	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	61	01030000000100000005000000918EFED9878F1D4028AD2AC183A94B40B4C876BE9FFA204028AD2AC183A94B40B4C876BE9FFA2040476558FF46E04B40918EFED9878F1D40476558FF46E04B40918EFED9878F1D4028AD2AC183A94B40	WGS84	\N	\N	Blåvands Huk - Fanø	75000
1000065	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	60	01030000000100000005000000DBF97E6ABC1420408244A88CDE6E4B40448B6CE7FB8921408244A88CDE6E4B40448B6CE7FB892140022B8716D9BE4B40DBF97E6ABC142040022B8716D9BE4B40DBF97E6ABC1420408244A88CDE6E4B40	WGS84	\N	\N	Nordsøen, Fanø - Sylt	75000
1000066	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	G	01030000000100000005000000B4C876BE9F4A45C021EE03F344B55340B4C876BE9F4A45C04DB3AB56C7B44A408CE8091111314DC04DB3AB56C7B44A408CE8091111314DC021EE03F344B55340B4C876BE9F4A45C021EE03F344B55340	WGS84	\N	\N	Kalaallit Nunaat - Imartallu avatangiisui (Grønland med omgivende farvande) Greenland with Surrounding Waters	4000000
1000067	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1000	0103000000010000000500000000000000008042C046F484888828544000000000008042C00000000000004C407927B8BBBB0356C00000000000004C407927B8BBBB0356C046F484888828544000000000008042C046F4848888285440	Ukendt/Unknown	\N	\N	Grønlands Vestkyst	2500000
1000068	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1001	01030000000100000005000000DA7D5C55555540C00000000000405040DA7D5C55555540C00000000000004C400000000000804DC00000000000004C400000000000804DC00000000000405040DA7D5C55555540C00000000000405040	Qornoq 1927	\N	\N	Grønlands Sydkyst. Kap Poul Løvenørn - Kap Farvel - Godthåb	1500000
1000069	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1100	01030000000100000005000000CAF2E0B4816E45C0430F4AD294AA4E40CAF2E0B4816E45C003DAEB174B2E4D40B121CD925FCC49C003DAEB174B2E4D40B121CD925FCC49C0430F4AD294AA4E40CAF2E0B4816E45C0430F4AD294AA4E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Kap Farvel - Arsuk	400000
1000070	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1103	0103000000010000000500000000000000006045C0CDCCCCCCCC2C4E4000000000006045C02682A3AAAACA4D4041E4C2BBBB7B46C02682A3AAAACA4D4041E4C2BBBB7B46C0CDCCCCCCCC2C4E4000000000006045C0CDCCCCCCCC2C4E40	Qornoq 1927	\N	\N	Kap Farvel området. Prins Chr. Sund - Frederiksdal	125000
1000071	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1113	01030000000100000005000000BCF870039D8646C05F69AE0D74AA4E40BCF870039D8646C0939CE140A73D4E4059B5D6DDDD1D47C0939CE140A73D4E4059B5D6DDDD1D47C05F69AE0D74AA4E40BCF870039D8646C05F69AE0D74AA4E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Igdlukasik - Sydprøven	80000
1000072	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1114	0103000000010000000500000003DAEB174BFE46C01F85EB51B88E4E4003DAEB174BFE46C0E17A14AE47214E40A19651F28B9547C0E17A14AE47214E40A19651F28B9547C01F85EB51B88E4E4003DAEB174BFE46C01F85EB51B88E4E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Sydprøven - Julianehåb	80000
1000073	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1115	01030000000100000005000000B81E85EB516847C0E55400C6927F4E40B81E85EB516847C0A19651F28B354E4048E17A14AE4748C0A19651F28B354E4048E17A14AE4748C0E55400C6927F4E40B81E85EB516847C0E55400C6927F4E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Julianehåb – Narssarssuaq og Igaliko Fjord	80000
1000074	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1116	0103000000010000000500000000000000000047C00000000000804E4000000000000047C0BF1B3D4444444E40DA7D5C5555B547C0BF1B3D4444444E40DA7D5C5555B547C00000000000804E4000000000000047C00000000000804E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Julianehåb - Mågeløb	80000
1000075	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1117	0103000000010000000500000000000000008047C00000000000804E4000000000008047C0BF1B3D4444444E400DB18F88884848C0BF1B3D4444444E400DB18F88884848C00000000000804E4000000000008047C00000000000804E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Mågeløb - Kobberminebugt	80000
1000076	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1118	010300000001000000050000002682A3AAAAEA47C07417F6EEEEAE4E402682A3AAAAEA47C03333333333734E400000000000A048C03333333333734E400000000000A048C07417F6EEEEAE4E402682A3AAAAEA47C07417F6EEEEAE4E40	Qornoq 1927	\N	\N	Kobbermindebugt - Tôrnârssuk Løb	80000
1000077	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1130	010300000001000000050000001BABFF396D6045C0BCF870039D164E401BABFF396D6045C0D4C98425BFF04D4055920A9D36D045C0D4C98425BFF04D4055920A9D36D045C0BCF870039D164E401BABFF396D6045C0BCF870039D164E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Frederiksdal	35000
1000078	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1131	010300000001000000050000009A999999991946C0B81E85EB51104E409A999999991946C05F69AE0D74EA4D40638C7A4E1B8846C05F69AE0D74EA4D40638C7A4E1B8846C0B81E85EB51104E409A999999991946C0B81E85EB51104E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Nanortalik	40000
1000079	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1132	0103000000010000000500000000000000000047C0F34E707777674E4000000000000047C09A99999999494E4041E4C2BBBB5B47C09A99999999494E4041E4C2BBBB5B47C0F34E707777674E4000000000000047C0F34E707777674E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Julianehåb	40000
1000080	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1133	01030000000100000005000000690D1092A69446C00730FA44FD1B4E40690D1092A69446C0C6CF1474DA104E40B29DEFA7C6A346C0C6CF1474DA104E40B29DEFA7C6A346C00730FA44FD1B4E40690D1092A69446C00730FA44FD1B4E40	WGS84	\N	\N	Grønlands Vestkyst. Nanortalik - Qeqertasussuk (Nanortalik - Thomsen Ø)	10000
1000081	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1145	010300000001000000050000002682A3AAAAEA47C0BF1B3D4444844E402682A3AAAAEA47C0EEBC22320B664E40BF1B3D44444448C0EEBC22320B664E40BF1B3D44444448C0BF1B3D4444844E402682A3AAAAEA47C0BF1B3D4444844E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Kobberminebugt	40000
1000082	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1146	010300000001000000050000002682A3AAAA0A48C00000000000A04E402682A3AAAA0A48C0A74A292222824E4066666666666648C0A74A292222824E4066666666666648C00000000000A04E402682A3AAAA0A48C00000000000A04E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Arsuk Fjord	40000
1000083	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1150	01030000000100000005000000BF1B3D44441445C06666666666664E40BF1B3D44441445C07417F6EEEECE4D400000000000A047C07417F6EEEECE4D400000000000A047C06666666666664E40BF1B3D44441445C06666666666664E40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Havneplaner	\N
1000084	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1151	01030000000100000005000000DA7D5C5555AD46C02682A3AAAAAA4E40DA7D5C5555AD46C02682A3AAAA4A4E409A999999999947C02682A3AAAA4A4E409A999999999947C02682A3AAAAAA4E40DA7D5C5555AD46C02682A3AAAAAA4E40	WG84	\N	\N	Umiarsualiviit assingi (Havneplaner / Harbour plans)	\N
1000085	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1152	01030000000100000005000000BF1B3D4444E447C0CDCCCCCCCCAC4E40BF1B3D4444E447C0D35A0321FA4C4E4000000000007048C0D35A0321FA4C4E4000000000007048C0CDCCCCCCCCAC4E40BF1B3D4444E447C0CDCCCCCCCCAC4E40	Qornoq 1927	\N	\N	Grønlands Vestkyst, Havneplaner	\N
1000086	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1160	01030000000100000005000000AFF6457C86B546C041E4C2BBBB534E40AFF6457C86B546C0FF509BFE8D404E4066EA4351FF2647C0FF509BFE8D404E4066EA4351FF2647C041E4C2BBBB534E40AFF6457C86B546C041E4C2BBBB534E40	WGS84	\N	\N	Qaarsup Ikerasaa - Paggivik	25000
1000087	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1161	01030000000100000005000000AB6DF562C93F47C0F34E707777674E40AB6DF562C93F47C0143225CC13554E4048E17A14AE7747C0143225CC13554E4048E17A14AE7747C0F34E707777674E40AB6DF562C93F47C0F34E707777674E40	WGS84	\N	\N	Simiutaq - Qarmat	25000
1000088	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1162	0103000000010000000500000034AF55489A7247C03059471BE8644E4034AF55489A7247C0EE7C3F355E524E40D122DBF97EAA47C0EE7C3F355E524E40D122DBF97EAA47C03059471BE8644E4034AF55489A7247C03059471BE8644E40	WGS84	\N	\N	Qarmat - Naajat (Qarmat - Mågeløb)	25000
1000089	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1163	0103000000010000000500000034AF55489A7247C0CBA145B6F3754E4034AF55489A7247C06B3897A87F634E40D122DBF97EAA47C06B3897A87F634E40D122DBF97EAA47C0CBA145B6F3754E4034AF55489A7247C0CBA145B6F3754E40	WGS84	\N	\N	Kuannit Saavat - Qoornoq - Akulleq	25000
1000090	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1164	01030000000100000005000000C2790647289B47C0DBF97E6ABC6C4E40C2790647289B47C009283F6FCB5A4E40DEA02896FCD247C009283F6FCB5A4E40DEA02896FCD247C0DBF97E6ABC6C4E40C2790647289B47C0DBF97E6ABC6C4E40	WGS84	\N	\N	Naajat - Ikerasassuaq (Mågeløb - Ikerasassuaq)	25000
1000091	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1165	010300000001000000050000003938A66157D047C08CE8091111714E403938A66157D047C0578A4FC7045F4E4055920A9D360848C0578A4FC7045F4E4055920A9D360848C08CE8091111714E403938A66157D047C08CE8091111714E40	WGS84	\N	\N	Ikerasassuaq. Niviukkat Umiarsuliviat - Torsukattaap Ninninnera (Aurora Havn - Knækket)	25000
1000092	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1166	01030000000100000005000000CDCCCCCCCC0448C08CE8091111714E40CDCCCCCCCC0448C0ADCBBE65AD5E4E40E9263108AC3C48C0ADCBBE65AD5E4E40E9263108AC3C48C08CE8091111714E40CDCCCCCCCC0448C08CE8091111714E40	WGS84	\N	\N	Torsukattak - Kitsissut (Torsukattak - Ydre Kitsissut)	25000
1000093	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1167	01030000000100000005000000846F2FA3B7E547C046B6F3FDD4884E40846F2FA3B7E547C0760F3B19BD6D4E406C6BD9948A0B48C0760F3B19BD6D4E406C6BD9948A0B48C046B6F3FDD4884E40846F2FA3B7E547C046B6F3FDD4884E40	WGS84	\N	\N	Itilliatsiaq - Qipisaqqu (Nyboes Kanal - Qipisaqqu)	25000
1000094	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1200	01030000000100000005000000DA7D5C5555D547C0F34E707777B74F40DA7D5C5555D547C00000000000804E40DA7D5C5555B54BC00000000000804E40DA7D5C5555B54BC0F34E707777B74F40DA7D5C5555D547C0F34E707777B74F40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Arsuk - Grædefjord	400000
1000095	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1210	01030000000100000005000000BBC52E17925E48C085EB51B81E254F40BBC52E17925E48C014AE47E17AB44E4000000000000049C014AE47E17AB44E4000000000000049C085EB51B81E254F40BBC52E17925E48C085EB51B81E254F40	Qornoq 1927	\N	\N	Tôrnârssuk Løb - Frederikshåb	80000
1000096	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1211	01030000000100000005000000DA7D5C5555B548C0BF1B3D4444444F40DA7D5C5555B548C02682A3AAAAEA4E4041E4C2BBBB3B49C02682A3AAAAEA4E4041E4C2BBBB3B49C0BF1B3D4444444F40DA7D5C5555B548C0BF1B3D4444444F40	Qornoq 1927	\N	\N	Frederikshåb – Frederikshåb Isblink	80000
1000097	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1212	010300000001000000050000009A99999999F948C02682A3AAAA924F409A99999999F948C041E4C2BBBB3B4F400DB18F88889849C041E4C2BBBB3B4F400DB18F88889849C02682A3AAAA924F409A99999999F948C02682A3AAAA924F40	WGS84	\N	\N	Sioqqap Sermia – Qeqertarsuatsiaat (Frederikshåb Isblink – Fiskenæsset)	80000
1000098	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1213	010300000001000000050000008CE80911115149C041E4C2BBBBDB4F408CE80911115149C059B5D6DDDD7D4F400000000000E049C059B5D6DDDD7D4F400000000000E049C041E4C2BBBBDB4F408CE80911115149C041E4C2BBBBDB4F40	WGS84	\N	\N	Qeqertarsuatsiaat – Kangerluarsoruseq (Fiskenæsset – Kangerluarsoruseq)	80000
1000099	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1230	010300000001000000050000007417F6EEEEAE48C02682A3AAAA0A4F407417F6EEEEAE48C041E4C2BBBBEB4E407417F6EEEE0E49C041E4C2BBBBEB4E407417F6EEEE0E49C02682A3AAAA0A4F407417F6EEEEAE48C02682A3AAAA0A4F40	Qornoq 1927	\N	\N	Frederikshåb	40000
1000100	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1231	01030000000100000005000000EE7C3F355E4A49C09EEFA7C64B974F40EE7C3F355E4A49C098EA3498277A4F405334419A52A949C098EA3498277A4F405334419A52A949C09EEFA7C64B974F40EE7C3F355E4A49C09EEFA7C64B974F40	WGS84	\N	\N	Qeqertarsuatsiaat (Fiskenæsset)	40000
1000101	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1250	01030000000100000005000000A4703D0AD7D348C0A74A292222624F40A4703D0AD7D348C0783AC22F96FC4E401BABFF396D5049C0783AC22F96FC4E401BABFF396D5049C0A74A292222624F40A4703D0AD7D348C0A74A292222624F40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Havneplaner	\N
1000102	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1251	010300000001000000050000000000000000C047C03333333333934F400000000000C047C00000000000A04E40DA7D5C55557549C00000000000A04E40DA7D5C55557549C03333333333934F400000000000C047C03333333333934F40	Qornoq 1927	\N	\N	Grønlands Vestkyst. Havneplaner	\N
1000103	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1300	0103000000010000000500000003DAEB174B5648C013C151555575504003DAEB174B5648C06666666666A64F401C1A813E32544DC06666666666A64F401C1A813E32544DC013C151555575504003DAEB174B5648C013C1515555755040	Qornoq 1927	\N	\N	Grædefjord – Hamborgerland	400000
1000104	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1310	0103000000010000000500000059B5D6DDDDA549C0B034D7063A0D504059B5D6DDDDA549C0B81E85EB51C04F40B81E85EB51304AC0B81E85EB51C04F40B81E85EB51304AC0B034D7063A0D504059B5D6DDDDA549C0B034D7063A0D5040	WGS84	\N	\N	Kangerluarsoruseq – Nuuk (Kangerluarsoruseq – Godthåb)	80000
1000105	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1311	010300000001000000050000007B14AE47E1C248C00AD7A3703D3A50407B14AE47E1C248C03333333333035040E55400C6926F49C03333333333035040E55400C6926F49C00AD7A3703D3A50407B14AE47E1C248C00AD7A3703D3A5040	WGS84	\N	\N	Nuup Kangerlua - Kangilleq (Godthåbsfjord, E-lige del) Nuup Kangerlua, Eastern Part	100000
1000106	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1312	01030000000100000005000000A74A2922225A49C00AD7A3703D3A5040A74A2922225A49C03333333333035040821166A0D3064AC03333333333035040821166A0D3064AC00AD7A3703D3A5040A74A2922225A49C00AD7A3703D3A5040		\N	\N	Nuup Kangerlua - Killeq (Godthåbsfjord, W-lige del)	100000
1000107	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1313	01030000000100000005000000B54499D3060A4AC0C74B378941285040B54499D3060A4AC033333333330350402682A3AAAA6A4AC033333333330350402682A3AAAA6A4AC0C74B378941285040B54499D3060A4AC0C74B378941285040	WGS84	\N	\N	Nuuk – Atammik (Godthåb – Atammik)	100000
1000108	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1314	010300000001000000050000004BBB662CF9F549C000000000006850404BBB662CF9F549C0352029BF58325040DEA02896FCA24AC0352029BF58325040DEA02896FCA24AC000000000006850404BBB662CF9F549C00000000000685040	WGS84	\N	\N	Atammik - Sermersuut (Atammik - Hamborgerland)	100000
1000109	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1330	010300000001000000050000004BBB662CF9A549C0638C7A4E1BE84F404BBB662CF9A549C0FD2514E8B4C14F40DA7D5C5555254AC0FD2514E8B4C14F40DA7D5C5555254AC0638C7A4E1BE84F404BBB662CF9A549C0638C7A4E1BE84F40	WGS84	\N	\N	Kangerluarsorutsimut Pulammagiaa (Indsejlingen til Kangerluarsoruseq) Approaches to Kangerluarsoruseq	40000
1000110	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1331	010300000001000000050000000AD7A3703DCA49C0F715521BE80C50400AD7A3703DCA49C0EC51B81E85FB4F4055920A9D36304AC0EC51B81E85FB4F4055920A9D36304AC0F715521BE80C50400AD7A3703DCA49C0F715521BE80C5040	WGS84	\N	\N	Nuuk (Godthåb)	40000
1000111	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1333	010300000001000000050000004990DF1520C749C054A51411114150404990DF1520C749C02072E1DDDD255040939CE140A71D4AC02072E1DDDD255040939CE140A71D4AC054A51411114150404990DF1520C749C054A5141111415040	WGS84	\N	\N	Niaqunngunaq (Fiskefjord)	40000
1000112	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1335	01030000000100000005000000A19651F28B554AC023DBF97E6A6C5040A19651F28B554AC02072E1DDDD51504007FDB758F2AB4AC02072E1DDDD51504007FDB758F2AB4AC023DBF97E6A6C5040A19651F28B554AC023DBF97E6A6C5040	WGS84	\N	\N	Maniitsoq (Sukkertoppen)	40000
1000113	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1350	01030000000100000005000000BF1B3D4444BC49C096BFAD814EDB4F40BF1B3D4444BC49C0A19651F28BD54F4001AF640172CF49C0A19651F28BD54F4001AF640172CF49C096BFAD814EDB4F40BF1B3D4444BC49C096BFAD814EDB4F40	WGS84	\N	\N	Orsiivik (Polaroil)	7000
1000114	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1351	010300000001000000050000000DB18F8888C849C02072E1DDDD0D50400DB18F8888C849C041E4C2BBBBFB4F401FF46C567D1A4AC041E4C2BBBBFB4F401FF46C567D1A4AC02072E1DDDD0D50400DB18F8888C849C02072E1DDDD0D5040	WGS84	\N	\N	Umiarsualiviit assingi (Havneplaner) Harbour Plans	\N
1000115	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1352	010300000001000000050000007EBB577321B949C000000000006050407EBB577321B949C07927B8BBBB2B50402682A3AAAA8A4AC07927B8BBBB2B50402682A3AAAA8A4AC000000000006050407EBB577321B949C00000000000605040	WGS84	\N	\N	Umiarsualiviit assingi (Havneplaner) Harbour Plans	\N
1000116	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1353	010300000001000000050000004DB3AB56C7D449C06891ED7C3F0D50404DB3AB56C7D449C056D09B27EA0750406815CB67D8E549C056D09B27EA0750406815CB67D8E549C06891ED7C3F0D50404DB3AB56C7D449C06891ED7C3F0D5040	WGS84	\N	\N	Aqissersiorfik – Nuuk (Rypeø – Godthåb)	10000
1000117	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1400	010300000001000000050000007417F6EEEEAE48C013C15155551551407417F6EEEEAE48C02072E1DDDD5D5040DA7D5C5555554EC02072E1DDDD5D5040DA7D5C5555554EC013C15155551551407417F6EEEEAE48C013C1515555155140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Hamborgerland – Rifkol	400000
1000118	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1410	01030000000100000005000000B54499D306424AC033F5A1A87F9B5040B54499D306424AC054213726786050404BBB662CF9054BC054213726786050404BBB662CF9054BC033F5A1A87F9B5040B54499D306424AC033F5A1A87F9B5040	WGS84	\N	\N	Grønlands Vestkyst, Hamborgerland - Uummannaarsussuaq	100000
1000119	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1411	010300000001000000050000000DB18F88884049C0F3BF43EE35BB50400DB18F88884049C087D84744449450406666666666664AC087D84744449450406666666666664AC0F3BF43EE35BB50400DB18F88884049C0F3BF43EE35BB5040	WGS84	\N	\N	Grønlands Vestkyst, Søndre Strømfjord, Serminnguaq - Kangerlussuup Umiarsualivia	100000
1000120	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1412	01030000000100000005000000225FD769031D4AC0D7A3703D0ABF5040225FD769031D4AC08ED5FF9C36985040AD987C79A23E4BC08ED5FF9C36985040AD987C79A23E4BC0D7A3703D0ABF5040225FD769031D4AC0D7A3703D0ABF5040	WGS84	\N	\N	Grønlands Vestkyst, Inussuttusup Tunua - Sisimiut (Anders Olsen Sund - Holsteinborg)	100000
1000121	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1415	010300000001000000050000006666666666A64AC09A99999999E950406666666666A64AC07927B8BBBBBB50400DB18F8888484BC07927B8BBBBBB50400DB18F8888484BC09A99999999E950406666666666A64AC09A99999999E95040	Qornoq 1927	\N	\N	Holsteinsborg – Færinge Nordhavn	80000
1000122	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1416	010300000001000000050000000000000000804AC000000000001051400000000000804AC087D8474444E450400000000000204BC087D8474444E450400000000000204BC000000000001051400000000000804AC00000000000105140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Færinge Nordhavn – Tugtulik	80000
1000123	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1430	010300000001000000050000001283C0CAA1AD4AC0F6285C8FC2C550401283C0CAA1AD4AC0F43B66039DB25040D81F9352713E4BC0F43B66039DB25040D81F9352713E4BC0F6285C8FC2C550401283C0CAA1AD4AC0F6285C8FC2C55040	WGS84	\N	\N	Grønlands Vestkyst, Sisimiut (Holsteinsborg)	50000
1000124	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1450	01030000000100000005000000560E2DB29DA74AC05C51316B75B05040560E2DB29DA74AC06F50144B7E695040C3F5285C8FE24AC06F50144B7E695040C3F5285C8FE24AC05C51316B75B05040560E2DB29DA74AC05C51316B75B05040	WGS84	\N	\N	Grønlands Vestkyst, Havneplaner	\N
1000125	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1451	010300000001000000050000002682A3AAAA6A4AC013C15155550551402682A3AAAA6A4AC013C1515555F550406666666666864BC013C1515555F550406666666666864BC013C15155550551402682A3AAAA6A4AC013C1515555055140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Havneplaner	\N
1000126	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1461	01030000000100000005000000B54499D306AA4AC08903CF5A1D875040B54499D306AA4AC0AAF1D24D62B850405EBA490C02DB4AC0AAF1D24D62B850405EBA490C02DB4AC08903CF5A1D875040B54499D306AA4AC08903CF5A1D875040	WGS84	\N	\N	Grønlands vestkyst, Indsejlingen til Søndre Strømfjord	25000
1000127	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1462	010300000001000000050000006666666666C64AC0B0726891EDA050406666666666C64AC02072E1DDDD8D5040E877CC063AED4AC02072E1DDDD8D5040E877CC063AED4AC0B0726891EDA050406666666666C64AC0B0726891EDA05040		\N	\N	Naajatuut - Uummannaarsussuaq & Inussuttusup Tunua (Anders Olsen Sund)	25000
1000128	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1500	0103000000010000000500000000000000008048C0ED3EAEAAAA9A514000000000008048C0ED3EAEAAAAFA5040DA7D5C5555954DC0ED3EAEAAAAFA5040DA7D5C5555954DC0ED3EAEAAAA9A514000000000008048C0ED3EAEAAAA9A5140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Rifkol – Hareø	400000
1000129	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1510	01030000000100000005000000295C8FC2F5484AC085EB51B81E455140295C8FC2F5484AC0C10833D0690B5140017C2215671F4BC0C10833D0690B5140017C2215671F4BC085EB51B81E455140295C8FC2F5484AC085EB51B81E455140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Tugtulik – Egedesminde	80000
1000130	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1511	01030000000100000005000000DA7D5C5555554AC046F4848888585140DA7D5C5555554AC0ED3EAEAAAA2A51402682A3AAAA0A4BC0ED3EAEAAAA2A51402682A3AAAA0A4BC046F4848888585140DA7D5C5555554AC046F4848888585140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Egedesminde – Godhavn	80000
1000131	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1512	0103000000010000000500000088C53DD0695349C085EB51B81E45514088C53DD0695349C0B25F5E1D131E5140E3A59BC420904AC0B25F5E1D131E5140E3A59BC420904AC085EB51B81E45514088C53DD0695349C085EB51B81E455140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Egedesminde – Christianshåb	80000
1000132	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1513	010300000001000000050000008D976E1283E048C000000000006851408D976E1283E048C0E08D1E222242514029CB10C7BA1C4AC0E08D1E222242514029CB10C7BA1C4AC000000000006851408D976E1283E048C00000000000685140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Christianshåb – Jakobshavn	80000
1000133	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1530	010300000001000000050000007417F6EEEE5E4AC098ACA30D743251407417F6EEEE5E4AC0CDCCCCCCCC245140F6285C8FC2CD4AC0CDCCCCCCCC245140F6285C8FC2CD4AC098ACA30D743251407417F6EEEE5E4AC098ACA30D74325140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Egedesminde	35000
1000134	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1550	010300000001000000050000000000000000004AC0E08D1E22226251400000000000004AC0AC5AEBEEEE0E514041E4C2BBBB3B4BC0AC5AEBEEEE0E514041E4C2BBBB3B4BC0E08D1E22226251400000000000004AC0E08D1E2222625140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Havneplaner	\N
1000135	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1551	010300000001000000050000000DB18F88880849C000000000006051400DB18F88880849C0000000000020514059B5D6DDDD3D4BC0000000000020514059B5D6DDDD3D4BC000000000006051400DB18F88880849C00000000000605140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Havneplaner	\N
1000136	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1552	010300000001000000050000009A99999999F948C07927B8BBBB9B51409A99999999F948C087D84744444451400000000000A04BC087D84744444451400000000000A04BC07927B8BBBB9B51409A99999999F948C07927B8BBBB9B5140	Qornoq 1927	\N	\N	Grønlands Vestkyst. Havneplaner	\N
1000137	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1600	010300000001000000050000001980782394114AC0736891ED7C0353401980782394114AC0B6F3FDD478F95140E17A14AE47314FC0B6F3FDD478F95140E17A14AE47314FC0736891ED7C0353401980782394114AC0736891ED7C035340	Qornoq 1927	\N	\N	Grønlands Vestkyst. Hareø – Prøven	400000
1000138	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1610	0103000000010000000500000040B180CFB0734BC08FC2F5285C47524040B180CFB0734BC052F6AF0F9F0D524060E5D022DB794CC052F6AF0F9F0D524060E5D022DB794CC08FC2F5285C47524040B180CFB0734BC08FC2F5285C475240	Qornoq 1927	\N	\N	Grønlands Vestkyst. Umanak – Mârmorilik	80000
1000139	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1650	0103000000010000000500000059B5D6DDDD3D49C013C151555595524059B5D6DDDD3D49C00000000000A051403333333333F34CC00000000000A051403333333333F34CC013C151555595524059B5D6DDDD3D49C013C1515555955240	WGS84	\N	\N	Umiarsualiviit assingi (Havneplaner) Harbour Plans	\N
1000140	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1700	01030000000100000005000000203450532A664DC000C26E754C445340203450532A664DC0AAB341C3AE84524015ECD86B2E2453C0AAB341C3AE84524015ECD86B2E2453C000C26E754C445340203450532A664DC000C26E754C445340	Qornoq 1927	\N	\N	Grønlands Vestkyst. Prøven – Holms Ø	400000
1000141	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	1710	010300000001000000050000008CE8091111B14BC02072E1DDDD3D52408CE8091111B14BC0AC5AEBEEEE0E52406666666666864CC0AC5AEBEEEE0E52406666666666864CC02072E1DDDD3D52408CE8091111B14BC02072E1DDDD3D5240	Qornoq 1927	\N	\N	Grønlands Vestkyst. Prøven – Upernavik	80000
1000142	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2000	010300000001000000050000006C887D44444418C046F48488882854406C887D44444418C00000000000004C4000000000008046C00000000000004C4000000000008046C046F48488882854406C887D44444418C046F4848888285440	\N	\N	\N	Grønlands Østkyst	2500000
1000143	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2100	01030000000100000005000000BDF0B52D6BCD42C0308C8907F3644F40BDF0B52D6BCD42C003DAEB174B2E4D40C3F5285C8FDA45C003DAEB174B2E4D40C3F5285C8FDA45C0308C8907F3644F40BDF0B52D6BCD42C0308C8907F3644F40	\N	\N	\N	Grønlands Østkyst. Kap Farvel – Kap Herluf Trolle	400000
1000144	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2130	01030000000100000005000000F6285C8FC25545C008AC1C5A641B4E40F6285C8FC25545C0B121CD925FF44D408CE8091111C945C0B121CD925FF44D408CE8091111C945C008AC1C5A641B4E40F6285C8FC25545C008AC1C5A641B4E40	\N	\N	\N	Prins Christian Sund, E-lige indsejling	40000
1000145	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2200	010300000001000000050000004FDE326DA03342C0D9CEF753E3B550404FDE326DA03342C08ABD82FA374A4F40E9AA0EF3449545C08ABD82FA374A4F40E9AA0EF3449545C0D9CEF753E3B550404FDE326DA03342C0D9CEF753E3B55040	\N	\N	\N	Kap Herluf Trolle – Kap Poul Løvenørn	400000
1000146	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2250	01030000000100000005000000F34E7077777741C0A74A292222A24F40F34E7077777741C07417F6EEEEAE4E400000000000A045C07417F6EEEEAE4E400000000000A045C0A74A292222A24F40F34E7077777741C0A74A292222A24F40	\N	\N	\N	Grønlands Østkyst. Havneplaner	\N
1000147	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2300	01030000000100000005000000E9F3EE1BA1AC3BC01283C0CAA1D55040E9F3EE1BA1AC3BC026F37621691A50402682A3AAAA1E43C026F37621691A50402682A3AAAA1E43C01283C0CAA1D55040E9F3EE1BA1AC3BC01283C0CAA1D55040	\N	\N	\N	Kap Poul Løvenørn – Kap Gustav Holm	400000
1000148	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2310	010300000001000000050000006DF49CBA937E42C09DE0B443B07D50406DF49CBA937E42C000000000005050400DB18F88881843C000000000005050400DB18F88881843C09DE0B443B07D50406DF49CBA937E42C09DE0B443B07D5040	\N	\N	\N	Kap Tycho Brahe – Kap Dan	80000
1000149	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2350	010300000001000000050000001243DDCDF46943C085EB51B81E6150401243DDCDF46943C09A999999995D50409A999999997943C09A999999995D50409A999999997943C085EB51B81E6150401243DDCDF46943C085EB51B81E615040	\N	\N	\N	Orssuiagtivaq	10000
1000150	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2351	0103000000010000000500000059B5D6DDDD7D42C046F484888878504059B5D6DDDD7D42C02072E1DDDD5D504059B5D6DDDDDD42C02072E1DDDD5D504059B5D6DDDDDD42C046F484888878504059B5D6DDDD7D42C046F4848888785040	\N	\N	\N	Grønlands Østkyst. Havneplaner	\N
1000151	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2400	01030000000100000005000000C1CAA145B60336C0C3F5285C8F625140C1CAA145B60336C054A5141111A9504065B70165F4BE40C054A5141111A9504065B70165F4BE40C0C3F5285C8F625140C1CAA145B60336C0C3F5285C8F625140	\N	\N	\N	Grønlands Østkyst. Kap Gustav Holm – Kap Vedel	400000
1000152	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2500	01030000000100000005000000273108AC1CEA30C046B6F3FDD4085240273108AC1CEA30C0AC5AEBEEEE4E51406666666666E63DC0AC5AEBEEEE4E51406666666666E63DC046B6F3FDD4085240273108AC1CEA30C046B6F3FDD4085240	\N	\N	\N	Grønlands Østkyst. Kap Garde – Scoresbysund	400000
1000153	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2600	010300000001000000050000000EC72DC5D9C72BC02BC5A76382B352400EC72DC5D9C72BC064FD4DC5D9F751404A0C022B87063DC064FD4DC5D9F751404A0C022B87063DC02BC5A76382B352400EC72DC5D9C72BC02BC5A76382B35240	\N	\N	\N	Grønlands Østkyst. Kap Brewster – Kap Simpson	400000
1000154	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2650	01030000000100000005000000CDCCCCCCCCCC34C013C1515555D55140CDCCCCCCCCCC34C00000000000C050402682A3AAAAAA40C00000000000C050402682A3AAAAAA40C013C1515555D55140CDCCCCCCCCCC34C013C1515555D55140	\N	\N	\N	Grønlands Østkyst. Havneplaner	\N
1000155	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2701	0103000000010000000500000000000000000034C013C151555565524000000000000034C013C15155550552400000000000803BC013C15155550552400000000000803BC013C151555565524000000000000034C013C1515555655240	\N	\N	\N	Grønlands Østkyst. Kap Simpson – Hold with Hope	250000
1000156	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2702	0103000000010000000500000000000000000031C013C1515555E5524000000000000031C0ED3EAEAAAA5A52404B044755559536C0ED3EAEAAAA5A52404B044755559536C013C1515555E5524000000000000031C013C1515555E55240	\N	\N	\N	Grønlands Østkyst. Hold With Hope – Shannon	250000
1000157	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2730	01030000000100000005000000B5FBB8AAAA2A36C0722A0063C9375240B5FBB8AAAA2A36C0ED3EAEAAAA22524033333333333337C0ED3EAEAAAA22524033333333333337C0722A0063C9375240B5FBB8AAAA2A36C0722A0063C9375240	\N	\N	\N	Grønlands Østkyst. Vega Sund, E-lige del	50000
1000158	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2750	010300000001000000050000009676CD58F27B32C0BE6189A4293153409676CD58F27B32C0ACDEC8D9873752401B621F11115139C0ACDEC8D9873752401B621F11115139C0BE6189A4293153409676CD58F27B32C0BE6189A429315340	\N	\N	\N	Grønlands Østkyst. Havneplaner	\N
1000159	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	2801	0103000000010000000500000097088EAAAAAA2FC013C151555565534097088EAAAAAA2FC013C1515555D5524000000000008036C013C1515555D5524000000000008036C013C151555565534097088EAAAAAA2FC013C1515555655340	\N	\N	\N	Grønlands Østkyst. Shannon – Skærfjorden	250000
1000160	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	3000	0103000000010000000500000067D3F0B1E4170CC00000000000A4554067D3F0B1E4170CC0D7A3703D0A13544091AFEBB4811E55C0D7A3703D0A13544091AFEBB4811E55C00000000000A4554067D3F0B1E4170CC00000000000A45540	WGS84	\N	\N	Grønlands Nordkyst. Thule - Danmarkshavn	500000
1000161	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	3100	010300000001000000050000000000000000004CC000000000003053400000000000004CC013C151555595524000000000008051C013C151555595524000000000008051C000000000003053400000000000004CC00000000000305340	\N	\N	\N	Grønlands Vestkyst. Holms Ø – Saunders Ø	400000
1000162	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	3130	01030000000100000005000000CDCCCCCCCC2C51C0BA0B7B7777275340CDCCCCCCCC2C51C0BA0B7B777717534054A51411119151C0BA0B7B777717534054A51411119151C0BA0B7B7777275340CDCCCCCCCC2C51C0BA0B7B7777275340	\N	\N	\N	Grønlands Vestkyst. North Star Bugt	40000
1000163	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	3200	0103000000010000000500000000000000004050C0000000000080534000000000004050C03333333333E3524000000000000054C03333333333E3524000000000000054C0000000000080534000000000004050C00000000000805340	Ukendt/Unknown	\N	\N	Grønlands Vestkyst. Kap York – Kap Chalon	400000
1000164	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	3210	0103000000010000000500000013C15155553551C013C151555565534013C15155553551C0666666666646534066666666660652C0666666666646534066666666660652C013C151555565534013C15155553551C013C1515555655340	Ukendt/Unknown	\N	\N	Grønlands Vestkyst. Kap Radcliff – Kap Cleveland	80000
1000165	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	F	01030000000100000005000000D810FB8888880AC0CDCCCCCCCC7C5040D810FB8888880AC041E4C2BBBBBB4D40B26AADBBBBBB31C041E4C2BBBBBB4D40B26AADBBBBBB31C0CDCCCCCCCC7C5040D810FB8888880AC0CDCCCCCCCC7C5040	WGS84	\N	\N	Føroyar viđ Havøkjum (Færøerne med maritime zoner) Faroe Islands with Maritime Zones	1150000
1000166	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	80	01030000000100000005000000F6285C8FC2F511C08CE8091111B14F40F6285C8FC2F511C0DA7D5C5555F54D409A99999999D922C0DA7D5C5555F54D409A99999999D922C08CE8091111B14F40F6285C8FC2F511C08CE8091111B14F40	WGS84	1011	\N	Sjóøkiđ viđ Føroyar (Farvandene omkring Færøerne) Waters around the Faroe Islands	400000
1000167	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	81	01030000000100000005000000062217DEDDDD16C059B5D6DDDD5D4F40062217DEDDDD16C0A74A292222824E4036C43E22226220C0A74A292222824E4036C43E22226220C059B5D6DDDD5D4F40062217DEDDDD16C059B5D6DDDD5D4F40	WGS84	\N	\N	Føroyar (Færøerne), Faroe Islands	200000
1000168	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	82	01030000000100000005000000FADDE821222218C0DA7D5C5555354F40FADDE821222218C041E4C2BBBBEB4E40FADDE82122221FC041E4C2BBBBEB4E40FADDE82122221FC0DA7D5C5555354F40FADDE821222218C0DA7D5C5555354F40	WGS84	\N	\N	Føroyar, Norđari partur (Færøerne, N-lige del) Faroe Islands Northern Part	100000
1000169	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	83	01030000000100000005000000FADDE821222218C08CE8091111F14E40FADDE821222218C06666666666A64E40FADDE82122221FC06666666666A64E40FADDE82122221FC08CE8091111F14E40FADDE821222218C08CE8091111F14E40	WGS84	\N	\N	Føroyar, Sunnari partur (Færøerne, S-lige del) Faroe Islands Southern Part	100000
1000170	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	84	010300000001000000050000006C887D4444441BC02682A3AAAA2A4F406C887D4444441BC00000000000004F40062217DEDDDD1EC00000000000004F40062217DEDDDD1EC02682A3AAAA2A4F406C887D4444441BC02682A3AAAA2A4F40	WGS84	\N	\N	Føroyar. Havnir og akkerspláss, útnyrđingsøkiđ (Havne og ankerpladser, NW-lige del) Harbours and Anchorages, Northwestern Part	\N
1000171	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	85	01030000000100000005000000C7AAB5EEEEEE18C03333333333334F40C7AAB5EEEEEE18C06666666666064F400000000000001CC06666666666064F400000000000001CC03333333333334F40C7AAB5EEEEEE18C03333333333334F40	WGS84	\N	\N	Føroyar. Havnir og akkerspláss, landnyrđingsøkiđ (Havne og ankerpladser, NE-lige del) Harbours and Anchorages, Northeastern Part	\N
1000172	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	86	01030000000100000005000000FADDE82122221AC00DB18F8888084F40FADDE82122221AC07417F6EEEEAE4E402E111C5555551CC07417F6EEEEAE4E402E111C5555551CC00DB18F8888084F40FADDE82122221AC00DB18F8888084F40	WGS84	\N	\N	Føroyar. Havnir og akkerspláss, suđurøkiđ (Havne og ankerpladser, S-lige del) Harbours and Anchorages, Southern Part	\N
\.


--
-- Data for Name: Comment; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Comment" (id, "acknowledgeDate", comment, created, "acknowledgedBy_id", message_id, user_id) FROM stdin;
\.


--
-- Data for Name: Comment_emailAddresses; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Comment_emailAddresses" ("Comment_id", "emailAddresses") FROM stdin;
\.


--
-- Data for Name: Contact; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Contact" (id, created, updated, version, email, language, name) FROM stdin;
\.


--
-- Data for Name: DateInterval; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."DateInterval" (id, "allDay", "fromDate", "indexNo", "toDate", "messagePart_id") FROM stdin;
1000001	f	2026-09-02 08:00:00	0	\N	1000001
1000002	f	2026-09-08 08:00:00	0	\N	1000002
1000003	f	2026-09-16 06:00:00	0	2026-12-18 15:00:00	1000003
1000004	f	2026-10-01 08:00:00	0	\N	1000004
1000005	f	2026-10-06 08:00:00	0	\N	1000005
1000006	f	2026-10-13 05:00:00	0	2026-11-27 16:00:00	1000006
1000007	f	2026-08-03 08:00:00	0	2026-08-28 08:00:00	1000010
1000008	f	2026-08-10 08:00:00	0	\N	1000011
1000009	f	2026-10-20 06:00:00	0	2026-10-30 14:00:00	1000013
1000010	f	2026-09-04 08:00:00	0	\N	1000015
1000011	f	2026-09-11 08:00:00	0	\N	1000016
1000012	f	2026-09-18 08:00:00	0	\N	1000017
1000013	f	2026-09-25 08:00:00	0	\N	1000018
1000014	f	2026-11-02 08:00:00	0	2026-12-11 08:00:00	1000019
1000015	f	2026-10-19 08:00:00	0	2026-11-06 08:00:00	1000020
1000016	f	2026-07-31 08:00:00	0	2026-08-25 08:00:00	1000024
1000017	f	2026-08-07 08:00:00	0	\N	1000025
1000018	f	2026-09-01 08:00:00	0	\N	1000027
1000019	f	2026-09-01 08:00:00	0	\N	1000028
1000020	f	2026-09-01 08:00:00	0	\N	1000029
1000021	f	2026-09-01 08:00:00	0	\N	1000031
1000022	f	2026-09-01 08:00:00	0	\N	1000033
\.


--
-- Data for Name: Dictionary; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Dictionary" (id, name) FROM stdin;
1	web
2	message
3	pdf
4	mail
5	template
6	aton
\.


--
-- Data for Name: DictionaryEntry; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."DictionaryEntry" (id, "atonFilter", dict_key, dictionary_id) FROM stdin;
1	\N	front_page.brand	1
2	\N	publication_type.LINK	1
3	\N	msg.show_positions	1
4	\N	term.format	1
5	\N	menu.action.select.all	1
6	\N	front_page.welcome	1
7	\N	menu.action.mail	1
8	\N	publication_type.REPOSITORY	1
9	\N	editor.section.message_part	1
10	\N	promulgation.confirm.generate	1
11	\N	template.execute	1
12	\N	template.markings.type.other_markings	1
13	\N	promulgation.undefined	1
14	\N	menu.action.export_selection	1
15	\N	editor.recently_edited	1
16	\N	msg.hide_attachments	1
17	\N	term.contact	1
18	\N	template.markings.bearing	1
19	\N	editor.status.publish_msg	1
20	\N	term.and	1
21	\N	menu.filter.category	1
22	\N	menu.filter.save	1
23	\N	reports.schedule	1
24	\N	menu.schedule	1
25	\N	menu.filter.area	1
26	\N	menu.filter.chart	1
27	\N	editor.save_msg	1
28	\N	menu.filter.promulgationType	1
29	\N	menu.action.import	1
30	\N	msg.hide_internal_publications	1
31	\N	menu.filter.comments	1
32	\N	menu.filter.kind	1
33	\N	reports.title	1
34	\N	view_mode.edit	1
35	\N	view_mode.history	1
36	\N	template.vessel.guard_vessels_in_area	1
37	\N	editor.status.delete_msg	1
38	\N	menu.admin	1
39	\N	msg.no_pos_messages	1
40	\N	template.categories	1
41	\N	template.vessel.title	1
42	\N	editor.new_nw_template	1
43	\N	editor.section.misc	1
44	\N	lang.en	1
45	\N	term.add_selected	1
46	\N	term.close	1
47	\N	promulgation.confirm.generate_all	1
48	\N	template.caution.min_dist	1
49	\N	menu.action.tag.remove_selection	1
50	\N	menu.filter.date	1
51	\N	editor.clear	1
52	\N	menu.logout	1
53	\N	menu.atons	1
54	\N	editor.hide_subject	1
55	\N	editor.status.verify_msg	1
56	\N	menu.brand	1
57	\N	term.add	1
58	\N	menu.action.fields.more	1
59	\N	view_mode.details	1
60	\N	report.execute	1
61	\N	menu.action.copy	1
62	\N	template.markings.type.unmarked	1
63	\N	menu.filter.reference	1
64	\N	editor.section.preamble	1
65	\N	template.markings.type	1
66	\N	term.generate	1
67	\N	template.selected_categories	1
68	\N	term.call_sign	1
69	\N	menu.filter.user	1
70	\N	template.aton.type	1
71	\N	msg.show_attachments	1
72	\N	term.result	1
73	\N	front_page.version_error	1
74	\N	term.priority	1
75	\N	msg.filter.all	1
76	\N	msg.list.load_more	1
77	\N	view_mode.comments	1
78	\N	footer.copyright	1
79	\N	menu.action.tag.add	1
80	\N	view_mode.status	1
81	\N	term.new_window	1
82	\N	template.markings.light_char	1
83	\N	menu.messages	1
84	\N	template.general	1
85	\N	editor.save_verified	1
86	\N	source.date_format	1
87	\N	view_mode.table	1
88	\N	term.remove_selected	1
89	\N	term.help	1
90	\N	menu.action	1
91	\N	template.vessel.contact	1
92	\N	editor.new_nw	1
93	\N	editor.new_nm_template	1
94	\N	menu.filter.clear	1
95	\N	reports.report	1
96	\N	editor.back_to_list	1
97	\N	editor.status.copy_msg	1
98	\N	editor.status.cancel_msg	1
99	\N	editor.new_nm	1
100	\N	term.remove_all	1
101	\N	footer.link	1
102	\N	menu.action.tag.add_selection	1
103	\N	template.markings.type.bouys	1
104	\N	template.select_category	1
105	\N	template.vessel.guard_vessels	1
106	\N	menu.action.tag.add_named	1
107	\N	msg.hide_positions	1
108	\N	footer.cookies	1
109	\N	menu.action.print	1
110	\N	menu.action.message.compare	1
111	\N	view_mode.selection	1
112	\N	term.generate_all	1
113	\N	menu.action.report	1
114	\N	menu.action.edit	1
115	\N	publication_type.NONE	1
116	\N	menu.action.fields.expand	1
117	\N	msg.show_internal_publications	1
118	\N	menu.action.print.selection	1
119	\N	editor.reload_msg	1
120	\N	template.parent_category	1
121	\N	menu.editor	1
122	\N	menu.filter.tag	1
123	\N	menu.action.update_status	1
124	\N	msg.hide_time	1
125	\N	menu.filter.publication	1
126	\N	template.sample_message	1
127	\N	term.name	1
128	\N	template.markings.title	1
129	\N	term.color	1
130	\N	menu.filter.text	1
131	\N	template.markings.dist	1
132	\N	menu.action.fields.collapse	1
133	\N	menu.action.export	1
134	\N	menu.action.select.none	1
135	\N	menu.filter.messageSeries	1
136	\N	editor.auto_generate	1
137	\N	publication_type.MESSAGE_REPORT	1
138	\N	menu.filter.type	1
139	\N	view_mode.map	1
140	\N	menu.sysadmin	1
141	\N	template.caution.title	1
142	\N	menu.filter.status	1
143	\N	editor.status.publish	1
144	\N	term.cancel	1
145	\N	view_mode.grid	1
146	\N	footer.disclaimer	1
147	\N	menu.filter	1
148	\N	msg.copy.include_reference	1
149	\N	menu.filter.domain	1
150	\N	menu.action.sort_area	1
151	\N	menu.action.tag.remove_named	1
152	\N	msg.show_time	1
153	\N	menu.filter.aton	1
154	\N	template.select_template	1
155	\N	menu.login	1
156	\N	msg.filter.reset	1
157	\N	msg.time.time_format	2
158	\N	msg.field.publish_date	2
159	\N	msg.firing_exercises.subject	2
160	\N	msg.field.part_types	2
161	\N	msg.type.PERMANENT_NOTICE	2
162	\N	msg.field.title	2
163	\N	msg.field.id	2
164	\N	msg.field.thumbnail	2
165	\N	msg.reference.reference	2
166	\N	msg.type.LOCAL_WARNING	2
167	\N	msg.field.categories	2
168	\N	msg.field.subject	2
169	\N	msg.reference.repetition	2
170	\N	msg.status.EXPIRED	2
171	\N	msg.field.publication	2
172	\N	msg.field.source	2
173	\N	msg.field.charts	2
174	\N	msg.attachment.title	2
175	\N	msg.status.PUBLISHED	2
176	\N	msg.type.SUBAREA_WARNING	2
177	\N	msg.type.MISCELLANEOUS_NOTICE	2
178	\N	msg.status.DRAFT	2
179	\N	msg.status.CANCELLED	2
180	\N	msg.type.PRELIMINARY_NOTICE	2
181	\N	msg.field.status	2
182	\N	msg.type.COASTAL_WARNING	2
183	\N	msg.field.event_dates	2
184	\N	msg.cancellation.title	2
185	\N	msg.field.type	2
186	\N	msg.time.date_time_format	2
187	\N	msg.status.VERIFIED	2
188	\N	msg.time.to_date	2
189	\N	msg.kind.CANCELLATION	2
190	\N	msg.field.attachments	2
191	\N	msg.field.time	2
192	\N	msg.field.signals	2
193	\N	msg.field.follow_up_date	2
194	\N	msg.reference.repetition_new_time	2
195	\N	msg.reference.update	2
196	\N	msg.field.orig_info	2
197	\N	msg.time.date_format	2
198	\N	msg.field.areas	2
199	\N	msg.field.prohibition	2
200	\N	msg.reference.cancellation	2
201	\N	msg.field.description	2
202	\N	msg.time.from_date	2
203	\N	msg.type.NAVAREA_WARNING	2
204	\N	msg.time.until_further_notice	2
205	\N	msg.field.references	2
206	\N	msg.type.TEMPORARY_NOTICE	2
207	\N	msg.area.general	2
208	\N	msg.field.details	2
209	\N	msg.field.created_date	2
210	\N	msg.field.publish_from_date	2
211	\N	msg.field.updated_date	2
212	\N	msg.field.promulgation	2
213	\N	msg.field.note	2
214	\N	msg.field.published	2
215	\N	msg.field.layout	2
216	\N	msg.field.positions	2
217	\N	msg.kind.WARNING	2
218	\N	msg.status.DELETED	2
219	\N	pdf.fa.subtitle	3
220	\N	pdf.nm_annex.nm_ref	3
221	\N	pdf.nm_tp	3
222	\N	pdf.accumulated.period	3
223	\N	pdf.nm_almanac.subtitle	3
224	\N	pdf.accumulated.no_messages	3
225	\N	pdf.fa.title	3
226	\N	pdf.volume	3
227	\N	pdf.nw	3
228	\N	pdf.accumulated.cutoff	3
229	\N	pdf.details.title	3
230	\N	pdf.accumulated.title	3
231	\N	pdf.week	3
232	\N	pdf.nm	3
233	\N	pdf.list.title	3
234	\N	pdf.nm_almanac.desc	3
235	\N	pdf.page	3
236	\N	pdf.fa	3
237	\N	pdf.accumulated.added_by_hand	3
238	\N	pdf.misc_nm	3
239	\N	pdf.nm_abbrev	3
240	\N	pdf.nm_almanac.title	3
241	\N	pdf.toc	3
242	\N	pdf.translation	3
243	\N	mail.navtex.cancel	4
244	\N	mail.date_format.long	4
245	\N	mail.safetynet.cancel	4
246	\N	mail.date_format.short	4
247	\N	mail.dear.user	4
248	\N	mail.field.warning	4
249	\N	mail.navwarn.cancel	4
250	\N	mail.active_nav_warnings	4
251	\N	mail.greetings.best.regards	4
252	\N	mail.comment.subject	4
253	\N	mail.field.date	4
254	\N	navwarn.date.until_further_notice	5
255	\N	position.between_pos	5
256	\N	position.in_area	5
257	\N	navwarn.date.time_interval_prefix	5
258	\N	term.and	5
259	\N	position.in_pos	5
260	\N	cancellation.this_message	5
261	\N	position.between_positions	5
262	\N	position.south	5
263	\N	navwarn.date.from	5
264	\N	position.in_position	5
265	\N	position.west	5
266	\N	position.east	5
267	\N	position.in_positions	5
268	\N	navwarn.date.until	5
269	\N	navwarn.date.html_day_suffix	5
270	\N	position.deg	5
271	\N	cancellation.message	5
272	\N	navwarn.date.day_suffix	5
273	\N	position.min	5
274	\N	navwarn.date.time_prefix	5
275	\N	navwarn.date.to	5
276	\N	position.north	5
277	\N	navwarn.date.day_prefix	5
278	aton.kv('seamark:buoy_lateral:category', 'port') && aton.k('seamark:light.*')	aton.type.light_buoy.red	6
279	aton.kv('seamark:type', 'light.*')	aton.type.light	6
280	aton.kv('seamark:type', 'buoy_special_purpose') && !aton.k('seamark:light.*')	aton.function.buoy.special	6
281	aton.kv('seamark:type', 'beacon.*')	aton.function.beacon.cable	6
282	\N	marking.color.yellow	6
283	aton.kv('seamark:type', 'buoy_special_purpose') && aton.k('seamark:light.*')	aton.type.light_buoy.yellow	6
284	aton.kv('seamark:buoy_cardinal:category', 'south') && aton.k('seamark:light.*')	aton.function.light_buoy.south_cardinal	6
285	\N	vessel.type.vessel	6
286	\N	aton.bearing.nw	6
287	aton.kv('seamark:buoy_lateral:category', 'starboard') && !aton.k('seamark:light.*')	aton.type.buoy.green	6
288	aton.kv('seamark:type', 'buoy_emergency_wreck') && aton.k('seamark:light.*')	aton.type.light_buoy.blue_yellow	6
289	aton.kv('seamark:type', 'buoy_isolated_danger') && !aton.k('seamark:light.*')	aton.function.buoy.isolated_danger	6
290	\N	obstruction.type.obstruction	6
291	\N	object.adrift.logs	6
292	\N	marking.color.orange	6
293	\N	aton.bearing.e	6
294	aton.kv('seamark:buoy_cardinal:category', 'east') && !aton.k('seamark:light.*')	aton.function.buoy.east_cardinal	6
295	aton.kv('seamark:buoy_lateral:category', 'preferred_channel_starboard') && !aton.k('seamark:light.*')	aton.function.buoy.starboard_bifurcation	6
296	\N	object.adrift.dinghy	6
297	\N	aton.bearing.ne	6
298	\N	salvage.type.submarine	6
299	\N	aton.bearing.w	6
300	\N	object.adrift.container	6
301	aton.kv('seamark:type', 'beacon.*')	aton.function.beacon	6
302	\N	vessel.type.sailing_vessel	6
303	\N	aton.bearing.s	6
304	aton.kv('seamark:type', 'buoy_emergency_wreck') && aton.k('seamark:light.*')	aton.function.light_buoy.emergency_wreck	6
305	aton.kv('seamark:type', 'beacon.*')	aton.function.beacon.front_rear	6
306	\N	aton.bearing.n	6
307	aton.kv('seamark:type', 'buoy.*')	aton.function.aton	6
308	aton.kv('seamark:buoy_lateral:category', 'preferred_channel_port') && !aton.k('seamark:light.*')	aton.function.buoy.port_bifurcation	6
309	aton.kv('seamark:type', 'beacon.*')	aton.function.beacon.front	6
310	aton.kv('seamark:buoy_cardinal:category', 'east') && !aton.k('seamark:light.*')	aton.type.buoy.black_yellow_black	6
311	\N	exercise.type.mine_laying	6
312	aton.kv('seamark:buoy_cardinal:category', 'south') && aton.k('seamark:light.*')	aton.type.light_buoy.yellow_black	6
313	\N	salvage.type.wind_mill	6
314	\N	object.adrift.shooting_target	6
315	\N	exercise.type.torpedo	6
316	\N	obstruction.type.ground	6
317	\N	exercise.type.mine_laying_sweeping	6
318	\N	object.adrift.fish_farm	6
319	aton.kv('seamark:type', 'beacon.*')	aton.function.beacon.rear	6
320	aton.kv('seamark:type', 'buoy_isolated_danger') && !aton.k('seamark:light.*')	aton.type.buoy.black_red	6
321	aton.kv('seamark:buoy_cardinal:category', 'north') && !aton.k('seamark:light.*')	aton.function.buoy.north_cardinal	6
322	aton.kv('seamark:buoy_cardinal:category', 'north') && !aton.k('seamark:light.*')	aton.type.buoy.black_yellow	6
323	\N	obstruction.report.observed	6
324	\N	marking.type.fender	6
325	\N	radio_navigation.status.unreliable	6
326	aton.kv('seamark:type', 'buoy.*') && !aton.k('seamark:light.*')	aton.function.buoy	6
327	\N	salvage.type.wreck	6
328	aton.kv('seamark:buoy_lateral:category', 'preferred_channel_port') && !aton.k('seamark:light.*')	aton.function.light_buoy.port_bifurcation	6
329	\N	marking.color.blue	6
330	aton.kv('seamark:buoy_lateral:category', 'preferred_channel_port') && !aton.k('seamark:light.*')	aton.type.buoy.port_bifurcation	6
331	aton.kv('seamark:buoy_lateral:category', 'preferred_channel_port') && aton.k('seamark:light.*')	aton.type.light_buoy.port_bifurcation	6
332	\N	marking.color.red	6
333	\N	object.adrift.boat	6
334	\N	aton.withdrawn.temporarily	6
335	aton.kv('seamark:buoy_lateral:category', 'starboard') && !aton.k('seamark:light.*')	aton.function.buoy.starboard_hand	6
336	\N	aton.type.light.post	6
337	\N	object.adrift.fishing_vessel	6
338	\N	vessel.type.fishing_vessel	6
339	aton.kv('seamark:light:category', 'rear')	aton.type.light.rear	6
340	\N	marking.color.white	6
341	\N	aton.bearing.se	6
342	\N	aton.type.light.mole	6
343	aton.kv('seamark:type', 'buoy_safe_water') && aton.k('seamark:light.*')	aton.function.light_buoy.safe_water	6
344	\N	object.adrift.lifeboat	6
345	\N	marking.type.balloon	6
346	aton.kv('seamark:light:category', 'front|rear')	aton.type.light.front_rear	6
347	aton.kv('seamark:buoy_cardinal:category', 'south') && !aton.k('seamark:light.*')	aton.function.buoy.south_cardinal	6
348	aton.kv('seamark:type', 'buoy_isolated_danger') && aton.k('seamark:light.*')	aton.type.light_buoy.black_red	6
349	\N	salvage.type.platform	6
350	\N	obstruction.type.stone	6
351	aton.kv('seamark:type', 'buoy_safe_water') && !aton.k('seamark:light.*')	aton.function.buoy.safe_water	6
352	aton.kv('seamark:light:category', 'front')	aton.type.light.front	6
353	aton.kv('seamark:buoy_cardinal:category', 'west') && !aton.k('seamark:light.*')	aton.function.buoy.west_cardinal	6
354	aton.kv('seamark:buoy_cardinal:category', 'north') && aton.k('seamark:light.*')	aton.type.light_buoy.black_yellow	6
355	\N	obstruction.report.reported	6
356	aton.kv('seamark:buoy_lateral:category', 'port') && !aton.k('seamark:light.*')	aton.function.buoy.port_hand	6
357	aton.kv('seamark:type', 'buoy.*') && aton.k('seamark:light.*')	aton.function.light_buoy	6
358	aton.kv('seamark:buoy_cardinal:category', 'west') && !aton.k('seamark:light.*')	aton.type.buoy.yellow_black_yellow	6
359	aton.kv('seamark:buoy_cardinal:category', 'east') && aton.k('seamark:light.*')	aton.function.light_buoy.east_cardinal	6
360	\N	exercise.type.missile	6
361	\N	object.adrift.object	6
362	aton.kv('seamark:buoy_cardinal:category', 'east') && aton.k('seamark:light.*')	aton.type.light_buoy.black_yellow_black	6
363	aton.kv('seamark:buoy_cardinal:category', 'west') && aton.k('seamark:light.*')	aton.type.light_buoy.yellow_black_yellow	6
364	\N	radio_navigation.status.inoperative	6
365	aton.kv('seamark:type', 'buoy_special_purpose') && !aton.k('seamark:light.*')	aton.type.buoy.yellow	6
366	aton.kv('seamark:buoy_cardinal:category', 'west') && aton.k('seamark:light.*')	aton.function.light_buoy.west_cardinal	6
367	aton.kv('seamark:buoy_lateral:category', 'starboard') && aton.k('seamark:light.*')	aton.function.light_buoy.starboard_hand	6
368	aton.kv('seamark:buoy_lateral:category', 'preferred_channel_starboard') && !aton.k('seamark:light.*')	aton.type.buoy.starboard_bifurcation	6
369	aton.kv('seamark:buoy_lateral:category', 'port') && aton.k('seamark:light.*')	aton.function.light_buoy.port_hand	6
370	\N	exercise.type.military	6
371	\N	marking.type.container	6
372	aton.kv('seamark:buoy_lateral:category', 'preferred_channel_starboard') && !aton.k('seamark:light.*')	aton.function.light_buoy.starboard_bifurcation	6
373	aton.kv('seamark:light:.*:category', 'directional')	aton.type.light.sector	6
374	\N	object.adrift.life_raft	6
375	aton.kv('seamark:buoy_cardinal:category', 'south') && !aton.k('seamark:light.*')	aton.type.buoy.yellow_black	6
376	aton.kv('seamark:buoy_lateral:category', 'port') && !aton.k('seamark:light.*')	aton.type.buoy.red	6
377	\N	aton.withdrawn.permanently	6
378	aton.kv('seamark:type', 'buoy_safe_water') && aton.k('seamark:light.*')	aton.type.light_buoy.red_white	6
379	aton.kv('seamark:type', 'buoy_safe_water') && !aton.k('seamark:light.*')	aton.type.buoy.red_white	6
380	aton.kv('seamark:buoy_cardinal:category', 'north') && aton.k('seamark:light.*')	aton.function.light_buoy.north_cardinal	6
381	aton.kv('seamark:type', 'buoy_special_purpose') && aton.k('seamark:light.*')	aton.function.light_buoy.special	6
382	aton.kv('seamark:type', 'buoy_isolated_danger') && aton.k('seamark:light.*')	aton.function.light_buoy.isolated_danger	6
383	\N	object.adrift.fishing_net	6
384	aton.kv('seamark:buoy_lateral:category', 'starboard') && aton.k('seamark:light.*')	aton.type.light_buoy.green	6
385	\N	object.adrift.fishing_stakes	6
386	\N	object.adrift.sailboat	6
387	aton.kv('seamark:buoy_lateral:category', 'preferred_channel_starboard') && aton.k('seamark:light.*')	aton.type.light_buoy.starboard_bifurcation	6
388	\N	aton.bearing.sw	6
1000001	\N	radio_navigation.dgps.skagen	6
1000002	\N	radio_navigation.dgps.torshavn	6
1000003	\N	radio_navigation.dgps.hammer_odde	6
1000004	\N	radio_navigation.dgps.blåvand	6
\.


--
-- Data for Name: DictionaryEntryDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."DictionaryEntryDesc" (id, lang, "longValue", value, entity_id) FROM stdin;
1	en	\N	Nautical Information Directory	1
2	en	\N	Link	2
3	en	\N	Show positions	3
4	en	\N	Format	4
5	en	\N	Select all	5
6	en	\N	Welcome to the	6
7	en	\N	E-mail selection...	7
8	en	\N	Uploaded file	8
9	en	\N	Part {{part}}	9
10	en	\N	Generate a proposal for the '{{type}}' promulgation?	10
11	en	\N	Execute Templates	11
12	en	\N	Other markings	12
13	en	\N	Promulgation undefined	13
14	en	\N	Export selection...	14
15	en	\N	Recently edited drafts	15
16	en	\N	Hide attachments	16
17	en	\N	Contact	17
18	en	\N	Bearing	18
19	en	\N	Publish Message	19
20	en	\N	and	20
21	en	\N	Category	21
22	en	\N	Save Filter...	22
23	en	\N	Schedule	23
24	en	\N	Schedule	24
25	en	\N	Area	25
26	en	\N	Chart	26
27	en	\N	Save Message	27
28	en	\N	Promulgation	28
29	en	\N	Import...	29
30	en	\N	Hide internal	30
31	en	\N	Comments	31
32	en	\N	Kind	32
33	en	\N	Execute Mailing List Report	33
34	en	\N	Edit	34
35	en	\N	History	35
36	en	\N	Guard vessels in area	36
37	en	\N	Delete Message	37
38	en	\N	Admin	38
39	en	\N	Additional Messages	39
40	en	\N	Categories	40
41	en	\N	Work Vessel	41
42	en	\N	New NW from Template	42
43	en	\N	Miscellaneous	43
44	en	\N	English	44
45	en	\N	Add Selected	45
46	en	\N	Close	46
47	en	\N	Generate a proposal for all active promulgations?	47
48	en	\N	Minimum dist. for mariners	48
49	en	\N	Remove selection from tag...	49
50	en	\N	Date	50
51	en	\N	Clear	51
52	en	\N	Logout	52
53	en	\N	AtoNs	53
54	en	\N	Hide subject	54
55	en	\N	Verify Message	55
56	en	\N	Niord	56
57	en	\N	Add	57
58	en	\N	More fields:	58
59	en	\N	Details	59
60	en	\N	Execute	60
61	en	\N	Copy...	61
62	en	\N	Unmarked	62
63	en	\N	Reference	63
64	en	\N	Preamble	64
65	en	\N	Markings used	65
66	en	\N	Generate	66
67	en	\N	Selected Categories	67
68	en	\N	Call Sign	68
69	en	\N	User	69
70	en	\N	Type	70
71	en	\N	Show attachments	71
72	en	\N	Result	72
73	en	\N	Please refresh web-browser	73
74	en	\N	Priority	74
75	en	\N	All	75
76	en	\N	Load {{fromMessageNo}} - {{toMessageNo}} of {{totalMessageNo}} messages	76
77	en	\N	Comments	77
78	en	\N	&copy; 2017 EfficienSea 2	78
79	en	\N	Add to tag...	79
80	en	\N	Status	80
81	en	\N	New Window	81
82	en	\N	Light Char.	82
83	en	\N	Messages	83
84	en	\N	General	84
85	en	\N	When saving a verified message, it becomes a draft message. Proceed?	85
86	en	\N	D MMMM YYYY	86
87	en	\N	List	87
88	en	\N	Remove Selected	88
89	en	\N	help	89
90	en	\N	Action	90
91	en	\N	Contact vessel on VHF channel 16 + channel	91
92	en	\N	New NW	92
93	en	\N	New NM from Template	93
94	en	\N	Clear Filter	94
95	en	\N	Report	95
96	en	\N	Back to list	96
97	en	\N	Copy Message	97
98	en	\N	Cancel Message	98
99	en	\N	New NM	99
100	en	\N	Remove All	100
101	en	\N	http://efficiensea2.org	101
102	en	\N	Add selection to tag...	102
103	en	\N	Buoys	103
104	en	\N	Select Category	104
105	en	\N	Guard Vessels	105
106	en	\N	Add to "{{tag}}"	106
107	en	\N	Hide positions	107
108	en	\N	Cookies	108
109	en	\N	Print...	109
110	en	\N	Compare...	110
111	en	\N	Selection	111
112	en	\N	Generate All	112
113	en	\N	Report...	113
114	en	\N	Edit...	114
115	en	\N	None	115
116	en	\N	Expand fields	116
117	en	\N	Show internal	117
118	en	\N	Print selection...	118
119	en	\N	Reload Message	119
120	en	\N	Parent Category	120
121	en	\N	Editor	121
122	en	\N	Tag	122
123	en	\N	Update selection status...	123
124	en	\N	Hide time	124
125	en	\N	Publication	125
126	en	\N	Example 	126
127	en	\N	Name	127
128	en	\N	Markings	128
129	en	\N	Color	129
130	en	\N	Text	130
131	en	\N	Dist. (m)	131
132	en	\N	Collapse fields	132
133	en	\N	Export...	133
134	en	\N	Clear selection	134
135	en	\N	Message Series	135
136	en	\N	Auto generate	136
137	en	\N	Report	137
138	en	\N	Type	138
139	en	\N	Map	139
140	en	\N	Sysadmin	140
141	en	\N	Caution	141
142	en	\N	Status	142
143	en	\N	Publish	143
144	en	\N	Cancel	144
145	en	\N	Grid	145
146	en	\N	Disclaimer	146
147	en	\N	Filter	147
148	en	\N	Include reference to copied message	148
149	en	\N	Domain	149
150	en	\N	Sort area...	150
151	en	\N	Remove from "{{tag}}"	151
152	en	\N	Show time	152
153	en	\N	AtoN	153
154	en	\N	Select Template	154
155	en	\N	Login	155
156	en	\N	Reset	156
157	en	\N	HH:mm	157
158	en	\N	Publish date	158
159	en	\N	Firing exercises. Warning.	159
160	en	\N	Part Types	160
161	en	\N	Perm.	161
162	en	\N	Title	162
163	en	\N	ID	163
164	en	\N	Thumbnail	164
165	en	\N	(reference)	165
166	en	\N	Local	166
167	en	\N	Categories	167
168	en	\N	Subject	168
169	en	\N	(repetition)	169
170	en	\N	Expired	170
171	en	\N	Publication	171
172	en	\N	Source	172
173	en	\N	Charts	173
174	en	\N	Attachment for message {0}	174
175	en	\N	Published	175
176	en	\N	Subarea	176
177	en	\N	Misc.	177
178	en	\N	Draft	178
179	en	\N	Cancelled	179
180	en	\N	Prelim.	180
181	en	\N	Status	181
182	en	\N	Coastal	182
183	en	\N	Event dates	183
184	en	\N	Cancellation of {0}	184
185	en	\N	Type	185
186	en	\N	d MMMM yyyy, 'hours' HH:mm	186
187	en	\N	Verified	187
188	en	\N	Until {{toDate}}	188
189	en	\N	Cancellations	189
190	en	\N	Attachments	190
191	en	\N	Time	191
192	en	\N	Signals	192
193	en	\N	Follow-up date	193
194	en	\N	(repetition with new time)	194
195	en	\N	(updated repetition)	195
196	en	\N	Org. Information	196
197	en	\N	d MMMM yyyy	197
198	en	\N	Areas	198
199	en	\N	Prohibition	199
200	en	\N	(cancelled)	200
201	en	\N	Description	201
202	en	\N	From {{fromDate}}	202
203	en	\N	NAVAREA	203
204	en	\N	Until further notice	204
205	en	\N	References	205
206	en	\N	Temp.	206
207	en	\N	General Messages	207
208	en	\N	Details	208
209	en	\N	Created date	209
210	en	\N	Publish from	210
211	en	\N	Last updated	211
212	en	\N	Promulgation	212
213	en	\N	Note	213
214	en	\N	Published	214
215	en	\N	Layout	215
216	en	\N	Positions	216
217	en	\N	Warnings	217
218	en	\N	Deleted	218
219	en	\N	Annex to Notices to Mariners	219
220	en	\N	Annex to Notices to Mariners {0}	220
221	en	\N	Preliminary (P) and Temporary (T) Notices to Mariners	221
222	en	\N	Published	222
223	en	\N	Annex to Notices to Mariners	223
224	en	\N	No messages are in this issue.	224
225	en	\N	Firing Practice Areas at Sea	225
226	en	\N	Volume {0}	226
227	en	\N	Navigational Warnings	227
228	en	\N	Cut-off	228
229	en	\N	Message	229
230	en	\N	Accumulated Notices to Mariners	230
231	en	\N	Week	231
232	en	\N	Notices to Mariners	232
233	en	\N	Message List	233
234	en	\N	General information for the waters these notices cover	234
235	en	\N	page	235
236	en	\N	Firing Practice Areas	236
237	en	\N	Added by hand	237
238	en	\N	Announcements, etc.	238
239	en	\N	NtM	239
240	en	\N	NtM Almanac	240
241	en	\N	Table of Contents	241
242	en	\N	Translation	242
243	en	\N	CANCEL NAV WARN	243
244	en	\N	d. MMMM yyyy	244
245	en	\N	CANCEL NAV WARN	245
246	en	\N	dd-MM-yyyy	246
247	en	\N	Dear {0}	247
248	en	\N	Warning	248
249	en	\N	Cancel navigational warning	249
250	en	\N	Active Navigational Warnings	250
251	en	\N	Kind regards,<br>{0}	251
252	en	\N	Comment from	252
253	en	\N	Date	253
254	en	\N	until further notice	254
255	en	\N	between pos.	255
256	en	\N	in the area	256
257	en	\N		257
258	en	\N	and	258
259	en	\N	in pos.	259
260	en	\N	Cancel this message {0}	260
261	en	\N	between positions	261
262	en	\N	South	262
263	en	\N	from	263
264	en	\N	in position	264
265	en	\N	West	265
266	en	\N	East	266
267	en	\N	in positions	267
268	en	\N	until	268
269	en	\N	<sup>{{suffix}}</sup>	269
270	en	\N	degrees	270
271	en	\N	Cancel message {0}	271
272	en	\N	th,st,nd,rd,th,th,th,th,th,th,th,th,th,th,th,th,th,th,th,th,th,st,nd,rd,th,th,th,th,th,th,th,st	272
273	en	\N	min.	273
274	en	\N	at	274
275	en	\N	to	275
276	en	\N	North	276
277	en	\N	the	277
278	en	a red light buoy	red light buoy	278
279	en	the light	light	279
280	en	the special buoy	special buoy	280
281	en	the cable beacon	cable beacon	281
282	en	\N	yellow	282
283	en	a yellow light buoy	yellow light buoy	283
284	en	the south cardinal light buoy	south cardinal light buoy	284
285	en	a vessel	vessel	285
286	en	North West	NW	286
287	en	a green buoy	green buoy	287
288	en	a blue-yellow light buoy	blue-yellow light buoy	288
289	en	the isolated danger buoy	isolated danger buoy	289
290	en	the obstruction	obstruction	290
291	en	logs	logs	291
292	en	\N	orange	292
293	en	East	E	293
294	en	the east cardinal buoy	east cardinal buoy	294
295	en	the preferred channel to starboard hand buoy	preferred channel to starboard hand buoy	295
296	en	a dinghy	dinghy	296
297	en	North East	NE	297
298	en	the submarine	submarine	298
299	en	west	W	299
300	en	a container	container	300
301	en	the beacon	beacon	301
302	en	a sailing vessel	sailing vessel	302
303	en	South	S	303
304	en	the emergency wreck light buoy	emergency wreck light buoy	304
305	en	the front and rear beacon	front and rear beacon	305
306	en	North	N	306
307	en	the buoy	buoy	307
308	en	the preferred channel to port hand buoy	preferred channel to port hand buoy	308
309	en	the front beacon	front beacon	309
310	en	a black-yellow-black buoy	black-yellow-black buoy	310
311	en	a mine laying exercise	mine laying exercise	311
312	en	a yellow-black light buoy	yellow-black light buoy	312
313	en	the wind mill	wind mill	313
314	en	a shooting target	shooting target	314
315	en	a torpedo exercise	torpedo exercise	315
316	en	the ground	ground	316
317	en	a mine laying and mine sweeping exercise	mine laying and mine sweeping exercise	317
318	en	a fish farm	fish farm	318
319	en	the rear beacon	rear beacon	319
320	en	a black-red buoy	black-red buoy	320
321	en	the north cardinal buoy	north cardinal buoy	321
322	en	a black-yellow buoy	black-yellow buoy	322
323	en	\N	observed	323
324	en	a {{color}} fender	fender	324
325	en	is unreliable	unreliable	325
326	en	the buoy	buoy	326
327	en	the wreck	wreck	327
328	en	the preferred channel to port hand light buoy	preferred channel to port hand light buoy	328
329	en	\N	blue	329
330	en	a red-green-red buoy	red-green-red buoy	330
331	en	a red-green-red light buoy	red-green-red light buoy	331
332	en	\N	red	332
333	en	a boat	boat	333
334	en	\N	temporarily	334
335	en	the starboard hand buoy	starboard hand buoy	335
336	en	the light post	light post	336
337	en	a fishing vessel	fishing vessel	337
338	en	a fishing vessel	fishing vessel	338
339	en	the rear light	rear light	339
340	en	\N	white	340
341	en	South East	SE	341
342	en	the mole head light	mole head light	342
343	en	the safe water light buoy	safe water light buoy	343
344	en	a lifeboat	lifeboat	344
345	en	a {{color}} balloon	balloon	345
346	en	the front and rear light	front and rear light	346
347	en	the south cardinal buoy	south cardinal buoy	347
348	en	a black-red light buoy	black-red light buoy	348
349	en	the platform	platform	349
350	en	the stone	stone	350
351	en	the safe water buoy	safe water buoy	351
352	en	the front light	front light	352
353	en	the west cardinal buoy	west cardinal buoy	353
354	en	a black-yellow light buoy	black-yellow light buoy	354
355	en	\N	reported	355
356	en	the port hand buoy	port hand buoy	356
357	en	the light buoy	light buoy	357
358	en	a yellow-black-yellow buoy	yellow-black-yellow buoy	358
359	en	the east cardinal light buoy	east cardinal light buoy	359
360	en	a missile exercise	missile exercise	360
361	en	an object	object	361
362	en	a black-yellow-black light buoy	black-yellow-black light buoy	362
363	en	a yellow-black-yellow light buoy	yellow-black-yellow light buoy	363
364	en	is inoperative	inoperative	364
365	en	a yellow buoy	yellow buoy	365
366	en	the west cardinal light buoy	west cardinal light buoy	366
367	en	the starboard hand light buoy	starboard hand light buoy	367
368	en	a green-red-green buoy	green-red-green buoy	368
369	en	the port hand light buoy	port hand light buoy	369
370	en	a military exercise	military exercise	370
371	en	a {{color}} container	container	371
372	en	the preferred channel to starboard hand light buoy	preferred channel to starboard hand light buoy	372
373	en	the sector light	sector light	373
374	en	a life raft	life raft	374
375	en	a yellow-black buoy	yellow-black buoy	375
376	en	a red buoy	red buoy	376
377	en	\N	permanently	377
378	en	a red-white bouy	red-white bouy	378
379	en	a red-white bouy	red-white bouy	379
380	en	the north cardinal light buoy	north cardinal light buoy	380
381	en	the special light buoy	special light buoy	381
382	en	the isolated danger light buoy	isolated danger light buoy	382
383	en	a fishing net	fishing net	383
384	en	a green light buoy	green light buoy	384
385	en	fishing stakes	fishing stakes	385
386	en	a sailboat	sailboat	386
387	en	a green-red-green light buoy	green-red-green light buoy	387
388	en	South West	SW	388
1000001	da	en rød lystønde	rød lystønde	278
1000002	da	fyret	fyr	279
1000003	da	specialafmærkningen	specialafmærkning	280
1000004	da	kabelbåken	kabelbåke	281
1000005	da	\N	gul	282
1000006	da	en gul lystønde	gul lystønde	283
1000007	da	\N	Skagen	1000001
1000008	en	\N	Skagen	1000001
1000009	da	sydkardinalen	sydkardinal	284
1000010	da	et skib	skib	285
1000011	da	nordvest	NW	286
1000012	da	et grønt dagsømærke	grønt dagsømærke	287
1000013	da	en blå-gul lystønde	blå-gul lystønde	288
1000014	da	den isolerede fareafmærkning	isoleret fareafmærkning	289
1000015	da	sejladshindringen	sejladshindring	290
1000016	da	tømmer	tømmer	291
1000017	da	\N	orange	292
1000018	da	øst	E	293
1000019	da	østkardinalen	østkardinal	294
1000020	da	den styrbord skillepunktsafmærkning	styrbord skillepunktsafmærkning	295
1000021	da	en jolle	jolle	296
1000022	da	nordøst	NE	297
1000023	da	ubåden	ubåd	298
1000024	da	\N	Torshavn	1000002
1000025	en	\N	Torshavn	1000002
1000026	da	Vest	W	299
1000027	da	en container	container	300
1000028	da	båken	båke	301
1000029	da	et sejlskib	sejlskib	302
1000030	da	syd	S	303
1000031	da	vragafmærkningen	vragafmærkning	304
1000032	da	for- og bagbåken	for- og bagbåke	305
1000033	da	nord	N	306
1000034	da	den flydende afmærkning	flydende afmærkning	307
1000035	da	den bagbord skillepunktsafmærkning	bagbord skillepunktsafmærkning	308
1000036	da	forbåken	forbåke	309
1000037	da	et sort-gult-sort dagsømærke	sort-gult-sort dagsømærke	310
1000038	da	en minelægningsøvelse	minelægningsøvelse	311
1000039	da	en gul-sort lystønde	gul-sort lystønde	312
1000040	da	vindmøllen	vindmølle	313
1000041	da	et skydemål	skydemål	314
1000042	da	en torpedoøvelse	torpedoøvelse	315
1000043	da	grunden	grund	316
1000044	da	en minelægnings- og minerydningsøvelse	minelægnings- og minerydningsøvelse	317
1000045	da	et havbrug	havbrug	318
1000046	da	bagbåken	bagbåke	319
1000047	da	et sort-rødt dagsømærke	sort-rødt dagsømærke	320
1000048	da	nordkardinalen	nordkardinal	321
1000049	da	et sort-gult dagsømærke	sort-gult dagsømærke	322
1000050	da	\N	observeret	323
1000051	da	\N	Hammer Odde	1000003
1000052	en	\N	Hammer Odde	1000003
1000053	da	en {{color}} fender	fender	324
1000054	da	er uregelmæssig	uregelmæssig	325
1000055	da	dagsømærket	dagsømærke	326
1000056	da	vraget	vrag	327
1000057	da	den bagbord skillepunktsafmærkning	bagbord skillepunktsafmærkning	328
1000058	da	\N	blå	329
1000059	da	et rødt-grønt-rødt dagsømærke	rødt-grønt-rødt dagsømærke	330
1000060	da	en rød-grøn-rød lystønde	rød-grøn-rød lystønde	331
1000061	da	\N	rød	332
1000062	da	en båd	båd	333
1000063	da	\N	midlertidigt	334
1000064	da	den styrbord sideafmærkning	styrbord sideafmærkning	335
1000065	da	fyrpælen	fyrpæl	336
1000066	da	et fiskeskib	fiskeskib	337
1000067	da	et fiskeskib	fiskeskib	338
1000068	da	bagfyret	bagfyr	339
1000069	da	\N	hvid	340
1000070	da	sydøst	SE	341
1000071	da	molefyret	molefyr	342
1000072	da	midtfarvandsafmærkningen	midtfarvandsafmærkning	343
1000073	da	en redningsbåd	redningsbåd	344
1000074	da	en {{color}} blåser	blåser	345
1000075	da	for- og bagfyret	for- og bagfyr	346
1000076	da	sydkardinalen	sydkardinal	347
1000077	da	en sort-rød lystønde	sort-rød lystønde	348
1000078	da	platformen	platform	349
1000079	da	stenen	sten	350
1000080	da	midtfarvandsafmærkningen	midtfarvandsafmærkning	351
1000081	da	forfyret	forfyr	352
1000082	da	vestkardinalen	vestkardinal	353
1000083	da	en sort-gul lystønde	sort-gul lystønde	354
1000084	da	\N	rapporteret	355
1000085	da	den bagbord sideafmærkning	bagbord sideafmærkning	356
1000086	da	lystønden	lystønde	357
1000087	da	et gult-sort-gult dagsømærke	gult-sort-gult dagsømærke	358
1000088	da	østkardinalen	østkardinal	359
1000089	da	en missiløvelse	missiløvelse	360
1000090	da	En genstand	genstand	361
1000091	da	\N	Blåvand	1000004
1000092	en	\N	Blåvand	1000004
1000093	da	en sort-gul-sort lystønde	sort-gul-sort lystønde	362
1000094	da	en gul-sort-gul lystønde	gul-sort-gul lystønde	363
1000095	da	er ude af drift	ude af drift	364
1000096	da	et gult dagsømærke	gult dagsømærke	365
1000097	da	vestkardinalen	vestkardinal	366
1000098	da	den styrbord sideafmærkning	styrbord sideafmærkning	367
1000099	da	et grønt-rødt-grønt dagsømærke	grønt-rødt-grønt dagsømærke	368
1000100	da	den bagbord sideafmærkning	bagbord sideafmærkning	369
1000101	da	en militærøvelse	militærøvelse	370
1000102	da	en {{color}} dunk	dunk	371
1000103	da	den styrbord skillepunktsafmærkning	styrbord skillepunktsafmærkning	372
1000104	da	vinkelfyret	vinkelfyr	373
1000105	da	en redningsflåde	redningsflåde	374
1000106	da	et gult-sort dagsømærke	gult-sort dagsømærke	375
1000107	da	et rødt dagsømærke	rødt dagsømærke	376
1000108	da	\N	permanent	377
1000109	da	en rød-hvidt lystønde	rød-hvidt lystønde	378
1000110	da	et rødt-hvidt dagsømærke	rødt-hvidt dagsømærke	379
1000111	da	nordkardinalen	nordkardinal	380
1000112	da	specialafmærkningen	specialafmærkning	381
1000113	da	den isolerede fareafmærkning	isoleret fareafmærkning	382
1000114	da	et fiskenet	fiskenet	383
1000115	da	en grøn lystønde	grøn lystønde	384
1000116	da	bundgarnspæle	bundgarnspæle	385
1000117	da	en sejlbåd	sejlbåd	386
1000118	da	en grøn-rød-grøn lystønde	grøn-rød-grøn lystønde	387
1000119	da	sydvest	SW	388
\.


--
-- Data for Name: Domain; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Domain" (id, active, atons, color, "domainId", latitude, longitude, "messageSortOrder", name, publish, "sortOrder", templates, "timeZone", "zoomLevel", "firingSchedule_id") FROM stdin;
1	t	\N	\N	niord-client-master	\N	\N	\N	Master	\N	\N	\N	\N	\N	\N
1000001	t	\N	\N	niord-nw	\N	\N	\N	NW	t	1	t	Europe/Copenhagen	\N	\N
1000002	t	\N	\N	niord-nm	\N	\N	\N	NM	t	2	\N	Europe/Copenhagen	\N	\N
1000003	t	\N	\N	niord-fa	\N	\N	\N	Firing Areas	\N	3	\N	Europe/Copenhagen	\N	\N
1000004	t	\N	\N	niord-almanac	\N	\N	ID ASC	NM Almanac	f	4	\N	Europe/Copenhagen	\N	\N
1000005	t	\N	\N	niord-annex	\N	\N	ID ASC	NM Annex	f	5	\N	Europe/Copenhagen	\N	\N
\.


--
-- Data for Name: Domain_Area; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Domain_Area" ("Domain_id", areas_id) FROM stdin;
\.


--
-- Data for Name: Domain_Category; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Domain_Category" ("Domain_id", categories_id) FROM stdin;
\.


--
-- Data for Name: Domain_MessageSeries; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Domain_MessageSeries" ("Domain_id", "messageSeries_id") FROM stdin;
1000001	1000001
1000001	1000002
1000002	1000003
1000003	1000004
1000004	1000005
1000005	1000006
\.


--
-- Data for Name: Feature; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Feature" (id, geometry, "indexNo", properties, uid, "featureCollection_id") FROM stdin;
1000001	0101000020E61000009A999999991927406666666666664C40	0	{}	17ff5f6c-ff27-202b-e3c5-61b7c5f1fdd8	1000001
1000002	0101000020E6100000CDCCCCCCCCCC25400AD7A3703DAA4B40	0	{}	c0d5fce9-515e-96b0-ba4a-81b02ae04be3	1000002
1000003	0101000020E610000052B81E85EB5129409A99999999D94B40	0	{}	16c07d18-b738-b65f-d45c-e6d2575bf865	1000003
1000004	0101000020E61000009A99999999992D403333333333B34B40	0	{}	af7e6fb7-7e42-709e-f4e2-da58029504d9	1000004
1000005	0101000020E610000033333333333325400000000000E04C40	0	{}	952dcf8d-39b4-f41d-c162-df366faa90eb	1000005
1000006	0102000020E610000002000000713D0AD7A37023400000000000C04B4085EB51B81E8523403D0AD7A370BD4B40	0	{}	3c012fc7-c03c-f736-b912-3483042694e7	1000006
1000007	0101000020E6100000CDCCCCCCCCCC24409A99999999794B40	0	{}	12f5b268-c6fe-201b-c6e4-c13bcd45ecd0	1000007
1000008	0103000020E610000001000000050000009A99999999992040A4703D0AD7634C40C3F5285C8FC22040A4703D0AD7634C40C3F5285C8FC220401F85EB51B85E4C409A999999999920401F85EB51B85E4C409A99999999992040A4703D0AD7634C40	0	{}	981084d1-71cb-5dd1-6a98-f11a5a7011e0	1000008
1000009	0103000020E610000001000000050000006666666666661E408FC2F5285CCF4B403333333333331F408FC2F5285CCF4B403333333333331F40C3F5285C8FC24B406666666666661E40C3F5285C8FC24B406666666666661E408FC2F5285CCF4B40	0	{}	35386283-5339-4b47-74a7-1b900de8991a	1000009
1000010	0101000020E61000003D0AD7A3703D27403D0AD7A3707D4B40	0	{}	60583282-61eb-8f30-9418-b07b21fcdefc	1000010
1000011	0101000020E61000009A99999999992840F6285C8FC2154C40	0	{}	cbb6aba6-628f-d879-bda9-f7eb3ae18aa8	1000011
1000012	0103000020E61000000100000005000000CDCCCCCCCCCC25409A99999999994B409A999999991926409A99999999994B409A999999991926408FC2F5285C8F4B40CDCCCCCCCCCC25408FC2F5285C8F4B40CDCCCCCCCCCC25409A99999999994B40	0	{}	79c48fff-cebd-f5cd-a29e-bc6350597c2f	1000012
1000013	0101000020E6100000713D0AD7A33029406666666666D64B40	0	{}	705c4d51-0f64-c487-e391-612cb40dc6b7	1000013
1000014	0101000020E61000001F85EB51B8DE2340D7A3703D0A874C40	0	{}	7a3bad40-da2f-6c26-34bb-058ffd95d250	1000014
1000015	0101000020E61000003333333333B325403D0AD7A370FD4B40	0	{}	270cd43b-54a0-8abd-1aef-56a297a9c2ae	1000015
1000016	0101000020E61000006666666666E625409A99999999794B40	0	{}	6cb1e767-f1a3-0ea8-5ece-f8eddc7b7172	1000016
1000017	0101000020E61000003D0AD7A3703D2940A4703D0AD7034C40	0	{}	1fc7d51f-1151-f8f9-6326-34aed275227a	1000017
1000018	0103000020E610000001000000050000009A999999991925408FC2F5285CCF4C403D0AD7A3703D25408FC2F5285CCF4C403D0AD7A3703D25400AD7A3703DCA4C409A999999991925400AD7A3703DCA4C409A999999991925408FC2F5285CCF4C40	0	{}	044c0063-401a-5c0f-f310-7a68b80663be	1000018
1000019	0103000020E610000001000000050000003333333333B323405C8FC2F5289C4B406666666666E623405C8FC2F5289C4B406666666666E6234014AE47E17A944B403333333333B3234014AE47E17A944B403333333333B323405C8FC2F5289C4B40	0	{}	7c0b2381-7eba-e5cf-e2c5-e8217f74cb88	1000019
1000020	0101000020E6100000CDCCCCCCCC4C2840CDCCCCCCCC6C4B40	0	{}	e8c8767c-c0c5-3dad-6735-c0ff9e381861	1000020
1000021	0101000020E61000009A999999991928408FC2F5285C4F4B40	0	{}	a433df04-b4fd-35ec-2d30-837c9ca1bb36	1000021
1000022	0103000020E610000001000000050000006666666666661F403D0AD7A370BD4B4000000000000020403D0AD7A370BD4B4000000000000020403333333333B34B406666666666661F403333333333B34B406666666666661F403D0AD7A370BD4B40	0	{}	ba3086ba-32b2-9951-ceac-3f147d980e82	1000022
1000023	0101000020E61000003D0AD7A3703D25406666666666864B40	0	{}	d88b30e1-e943-f1d8-2ff9-e6c1b3c7972e	1000023
1000024	0101000020E6100000B81E85EB517826409A99999999994B40	0	{}	ca2b667d-93e2-b932-3fcd-a2e762178476	1000024
1000025	0101000020E61000009A999999991922409A99999999794C40	0	{}	4105538c-0134-f65b-dd92-ca347eaba6dc	1000025
1000026	0101000020E610000066666666666625405C8FC2F5289C4C40	0	{}	e6dd9e4e-2dfc-b47e-e970-5685b604e962	1000026
1000027	0103000020E6100000010000000500000066666666666626403D0AD7A370FD4B40A4703D0AD7A326403D0AD7A370FD4B40A4703D0AD7A32640F6285C8FC2F54B406666666666662640F6285C8FC2F54B4066666666666626403D0AD7A370FD4B40	0	{}	e73b006a-c87f-5063-04a7-8567ec94d0ed	1000027
1000028	0103000020E61000000100000005000000CDCCCCCCCCCC2440CDCCCCCCCCEC4C403D0AD7A3703D2540CDCCCCCCCCEC4C403D0AD7A3703D2540A4703D0AD7E34C40CDCCCCCCCCCC2440A4703D0AD7E34C40CDCCCCCCCCCC2440CDCCCCCCCCEC4C40	0	{}	3edcd701-05d6-a29c-8132-bda7590ca1f6	1000028
1000029	0103000020E61000000100000005000000F6285C8FC2F52440AE47E17A146E4B403333333333332540AE47E17A146E4B4033333333333325406666666666664B40F6285C8FC2F524406666666666664B40F6285C8FC2F52440AE47E17A146E4B40	0	{}	2254760d-5ab7-ec4c-4197-5e523bd730c3	1000029
1000030	0103000020E61000000100000005000000CDCCCCCCCCCC1F4052B81E85EBD14B40295C8FC2F528204052B81E85EBD14B40295C8FC2F52820400AD7A3703DCA4B40CDCCCCCCCCCC1F400AD7A3703DCA4B40CDCCCCCCCCCC1F4052B81E85EBD14B40	0	{}	fcd107e9-449e-0607-f6a9-7162877d48a1	1000030
\.


--
-- Data for Name: FeatureCollection; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."FeatureCollection" (id, uid) FROM stdin;
1000001	8cc6e791-6cec-4af2-4b02-230a21b28f68
1000002	7cef840c-5a50-17d9-ffaf-9da4c45dd5e7
1000003	c320dac2-74ac-6bf4-9f25-aba35cb8bf01
1000004	389a0014-d603-90c8-673b-e41a1822c410
1000005	2e02af88-6572-83d7-d806-b727d5a8d4bc
1000006	c309796a-0c70-9b29-9129-8dcab488c9ed
1000007	f05440cf-dc8f-996b-6bde-b994d7e60084
1000008	b4407bc4-77a4-ed00-c6af-6a340ec10da2
1000009	4bdb01c7-cbd3-dc74-3fbe-1edcad617df7
1000010	2ff2cbb6-7a9f-c293-07ad-489350761f7f
1000011	e037dbea-bc97-e750-2d28-51ac4de61617
1000012	15352aeb-312c-fc39-32c7-92b07756c618
1000013	62a60f37-75dd-9964-cb30-f66c5c5f8b35
1000014	8943f7ea-ea37-2735-ba1b-4d1b04444495
1000015	5f97e9af-22d9-a67b-29b3-a8431d861182
1000016	4ad49915-1536-4689-cc33-2003c2ea3236
1000017	962b837d-9a1f-f36b-a02f-1335cb85c4d5
1000018	623f0b2a-3227-ffdb-cb05-93c57b8f6731
1000019	28d48f60-af5e-3fc3-1aa3-91b4cc9f3380
1000020	b3750079-43cf-d170-46fc-cd4dcb1e4b0f
1000021	0797e832-449a-02a4-d823-8aa8f0d143f5
1000022	04f99a2a-b3ca-4b40-3ebe-5f7aafb6c4b0
1000023	7f4d70ea-1851-706d-f2ea-c78382fdf33d
1000024	9e627666-0366-e57d-5271-2874ba58d9d4
1000025	44d34088-a733-f095-770a-bac230429e6f
1000026	9d5bc871-fa7e-b09c-022a-86c62933ff8a
1000027	34affca8-4089-cf2e-e41f-d9204d3c84fa
1000028	7abaff4f-188b-1ba3-cc39-16ae450d15f0
1000029	f06e10b8-994f-47bf-0198-ce3bb4251a36
1000030	df93d8d4-a24c-22a9-7319-97298cbe2dda
\.


--
-- Data for Name: FiringPeriod; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."FiringPeriod" (id, created, updated, version, "fromDate", "legacyId", "toDate", area_id) FROM stdin;
\.


--
-- Data for Name: FiringSchedule; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."FiringSchedule" (id, created, updated, version, active, "scheduleDays", domain_id, "targetDomain_id", "targetMessageSeries_id") FROM stdin;
\.


--
-- Data for Name: FmReport; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."FmReport" (id, name, params, properties, "reportId", "sortOrder", "templatePath") FROM stdin;
1	Standard	{}	{}	standard	0	/templates/messages/message-list-pdf.ftl
2	Draft	{}	{"draft":true}	draft	1	/templates/messages/message-list-pdf.ftl
3	Public	{}	{"frontPage":false}	public	2	/templates/messages/message-list-pdf.ftl
\.


--
-- Data for Name: FmReport_Domain; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."FmReport_Domain" ("FmReport_id", domains_id) FROM stdin;
\.


--
-- Data for Name: IssueAuditEntry; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."IssueAuditEntry" (id, issue_id, series_id, user_id, created, "archivePath", reason, action, "actorKind", "actorLabel", detail) FROM stdin;
\.


--
-- Data for Name: IssueMember; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."IssueMember" (id, issue_id, message_id, override_id, "sortIndex", "frozenPublishDateFrom", "frozenPublishDateTo", "messageUid", "sourceIssuePublicId", "reasonNote", "frozenMainType", "frozenShortId", "frozenStatus", "frozenType", source) FROM stdin;
\.


--
-- Data for Name: IssueOverride; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."IssueOverride" ("appliedAtPublish", author_id, id, issue_id, message_id, version, created, updated, "messageUid", reason, kind) FROM stdin;
\.


--
-- Data for Name: MailingList; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MailingList" (id, created, updated, version, active, "mailingListId") FROM stdin;
1000001	2026-10-22 00:00:00	2026-10-22 00:00:00	0	f	audio-broadcast
1000002	2026-10-22 00:00:00	2026-10-22 00:00:00	0	f	navtex-baltico
1000003	2026-10-22 00:00:00	2026-10-22 00:00:00	0	f	navtex-rogaland
1000004	2026-10-22 00:00:00	2026-10-22 00:00:00	0	f	navwarn-overview
1000005	2026-10-22 00:00:00	2026-10-22 00:00:00	0	f	navwarn-update
\.


--
-- Data for Name: MailingListDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MailingListDesc" (id, lang, description, name, entity_id) FROM stdin;
1000001	da	Farvandsefterretninger og skydeøvelser til oplæsning på DR	Farvandsefterretninger til oplæsning	1000001
1000002	en	Navigational Warnings and Firing Exercises for voice broadcast on Danish Radio	Navigational Warnings for Voice Broadcast	1000001
1000003	da	Mailliste for NAVTEX Baltico	NAVTEX Baltico	1000002
1000004	en	Mailing list of NAVTEX Baltico	NAVTEX Baltico	1000002
1000005	da	Mailliste for NAVTEX Rogaland	NAVTEX Rogaland	1000003
1000006	en	Mailing list of NAVTEX Rogaland	NAVTEX Rogaland	1000003
1000007	da	Liste over aktive danske navigationsadvarsler	Aktive navigationsadvarsler	1000004
1000008	en	List of active Danish coastal warnings	Active coastal warnings	1000004
1000009	da	Mailliste for danske navigationsadvarsler	Navwarn opdateringer	1000005
1000010	en	Mailing list for Danish coastal warnings	Navwarn updates	1000005
\.


--
-- Data for Name: MailingListStatusChange; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MailingListStatusChange" (id, "messageUid", status, created, "dueAt", attempts, "lastError", "settledTriggers") FROM stdin;
\.


--
-- Data for Name: MailingListTrigger; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MailingListTrigger" (id, created, updated, version, "messageFilter", "messageQuery", "nextScheduledExecution", "publicReport", "scheduleType", "scheduledExecutionTime", "scheduledExecutionTimeZone", type, "mailingList_id") FROM stdin;
1000001	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	messageSeries=dma-nw&messageSeries=dma-nw-local&status=PUBLISHED&promulgationType=audio	2026-10-22 00:00:00	\N	DAILY	15:55	Europe/Copenhagen	SCHEDULED	1000001
1000002	2026-10-22 00:00:00	2026-10-22 00:00:00	0	msg.promulgation('navtex').promulgate && msg.promulgation('navtex').useTransmitter('Baltico')	\N	\N	\N	\N	\N	\N	STATUS_CHANGE	1000002
1000003	2026-10-22 00:00:00	2026-10-22 00:00:00	0	msg.promulgation('navtex').promulgate && msg.promulgation('navtex').useTransmitter('Baltico')	\N	\N	\N	\N	\N	\N	STATUS_CHANGE	1000002
1000004	2026-10-22 00:00:00	2026-10-22 00:00:00	0	msg.promulgation('navtex').promulgate && msg.promulgation('navtex').useTransmitter('Rogaland')	\N	\N	\N	\N	\N	\N	STATUS_CHANGE	1000003
1000005	2026-10-22 00:00:00	2026-10-22 00:00:00	0	msg.promulgation('navtex').promulgate && msg.promulgation('navtex').useTransmitter('Rogaland')	\N	\N	\N	\N	\N	\N	STATUS_CHANGE	1000003
1000006	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	messageSeries=dma-nw&status=PUBLISHED&type=COASTAL_WARNING&sortBy=ID	2026-10-22 00:00:00	\N	DAILY	07:30	Europe/Copenhagen	SCHEDULED	1000004
1000007	2026-10-22 00:00:00	2026-10-22 00:00:00	0	msg.messageSeries.seriesId == 'dma-nw' && msg.type == 'COASTAL_WARNING'	\N	\N	\N	\N	\N	\N	STATUS_CHANGE	1000005
1000008	2026-10-22 00:00:00	2026-10-22 00:00:00	0	msg.messageSeries.seriesId == 'dma-nw' && msg.type == 'COASTAL_WARNING'	\N	\N	\N	\N	\N	\N	STATUS_CHANGE	1000005
\.


--
-- Data for Name: MailingListTriggerDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MailingListTriggerDesc" (id, lang, subject, entity_id) FROM stdin;
1000001	da	Farvandsefterretninger til oplæsning ${date-short}	1000001
1000002	en	CANCEL DANISH NAV WARN ${number-year-id}	1000002
1000003	en	DANISH NAV WARN ${number-year-id}	1000003
1000004	en	CANCEL DANISH NAV WARN ${number-year-id}	1000004
1000005	en	DANISH NAV WARN ${number-year-id}	1000005
1000006	da	Aktive navigationsadvarsler	1000006
1000007	en	Active navigational warnings	1000006
1000008	da	Annullér Dansk navigationsadvarsel ${short-id}	1000007
1000009	en	Cancel Danish Navigational Warning ${short-id}	1000007
1000010	da	Dansk navigationsadvarsel ${short-id}	1000008
1000011	en	Danish Navigational Warning ${short-id}	1000008
\.


--
-- Data for Name: MailingListTrigger_scriptResourcePaths; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MailingListTrigger_scriptResourcePaths" ("MailingListTrigger_id", "scriptResourcePaths", "indexNo") FROM stdin;
1000001	templates/mailinglist/fetch-firing-exercises.js	0
1000001	templates/mailinglist/audio-broadcast.ftl	1
1000002	templates/mailinglist/cancel-navtex.ftl	0
1000003	templates/mailinglist/publish-navtex.ftl	0
1000004	templates/mailinglist/cancel-navtex.ftl	0
1000005	templates/mailinglist/publish-navtex.ftl	0
1000006	templates/mailinglist/active-navwarns.ftl	0
1000007	templates/mailinglist/cancel-navwarn.ftl	0
1000008	templates/mailinglist/publish-navwarn.ftl	0
\.


--
-- Data for Name: MailingListTrigger_statusChanges; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MailingListTrigger_statusChanges" ("MailingListTrigger_id", "statusChanges") FROM stdin;
1000002	CANCELLED
1000004	CANCELLED
1000007	CANCELLED
1000003	PUBLISHED
1000005	PUBLISHED
1000008	PUBLISHED
\.


--
-- Data for Name: MailingList_Contact; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MailingList_Contact" ("MailingList_id", contacts_id) FROM stdin;
\.


--
-- Data for Name: MailingList_User; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MailingList_User" ("MailingList_id", users_id) FROM stdin;
\.


--
-- Data for Name: Message; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Message" (id, created, updated, version, "areaSortOrder", "autoTitle", "eventDateFrom", "eventDateTo", "followUpDate", "hasGeometry", "horizontalDatum", "legacyId", "mainType", number, "originalInformation", "publishDateFrom", "publishDateTo", "repoPath", revision, "separatePage", "shortId", status, "thumbnailPath", type, uid, year, area_id, "createdBy_id", "lastUpdatedBy_id", "messageSeries_id", kind) FROM stdin;
1000001	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-134.786	f	2026-09-02 08:00:00	\N	\N	t	WGS84	\N	NW	1	f	2026-09-02 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000001	1	\N	NW-001-26	PUBLISHED	\N	COASTAL_WARNING	5a3f1c70-0000-4000-8000-000000000001	2026	1000016	\N	\N	1000001	WARNING
1000002	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-55.33	f	2026-09-08 08:00:00	\N	\N	t	WGS84	\N	NW	2	f	2026-09-09 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000002	1	\N	NW-002-26	PUBLISHED	\N	COASTAL_WARNING	5a3f1c70-0000-4000-8000-000000000002	2026	1000011	\N	\N	1000001	WARNING
1000003	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-55.7	f	2026-09-16 06:00:00	2026-12-18 15:00:00	\N	t	WGS84	\N	NW	3	f	2026-09-15 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000003	1	\N	NW-003-26	PUBLISHED	\N	LOCAL_WARNING	5a3f1c70-0000-4000-8000-000000000003	2026	1000009	\N	\N	1000001	WARNING
1000004	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-210.377	f	2026-10-01 08:00:00	\N	\N	t	WGS84	\N	NW	4	f	2026-10-01 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000004	1	\N	NW-004-26	PUBLISHED	\N	COASTAL_WARNING	5a3f1c70-0000-4000-8000-000000000004	2026	1000002	\N	\N	1000001	WARNING
1000005	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-57.75	f	2026-10-06 08:00:00	\N	\N	t	WGS84	\N	NW	5	f	2026-10-06 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000005	1	\N	NW-005-26	PUBLISHED	\N	COASTAL_WARNING	5a3f1c70-0000-4000-8000-000000000005	2026	1000035	\N	\N	1000001	WARNING
1000006	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-55.49	f	2026-10-13 05:00:00	2026-11-27 16:00:00	\N	t	WGS84	\N	NW	6	f	2026-10-12 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000006	1	\N	NW-006-26	PUBLISHED	\N	COASTAL_WARNING	5a3f1c70-0000-4000-8000-000000000006	2026	1000013	\N	\N	1000001	WARNING
1000007	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-10.4	f	\N	\N	\N	t	WGS84	\N	NW	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000007	1	\N	\N	DRAFT	\N	COASTAL_WARNING	5a3f1c70-0000-4000-8000-000000000007	\N	1000012	\N	\N	1000001	WARNING
1000008	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-8.34	f	\N	\N	\N	t	WGS84	\N	NW	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000008	1	\N	\N	DRAFT	\N	LOCAL_WARNING	5a3f1c70-0000-4000-8000-000000000008	\N	1000034	\N	\N	1000001	WARNING
1000009	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-55.57	f	\N	\N	\N	t	WGS84	\N	NW	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000009	1	\N	\N	VERIFIED	\N	COASTAL_WARNING	5a3f1c70-0000-4000-8000-000000000009	\N	1000040	\N	\N	1000001	WARNING
1000010	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-11.62	f	2026-08-03 08:00:00	2026-08-28 08:00:00	\N	t	WGS84	\N	NW	7	f	2026-08-03 08:00:00	2026-08-28 08:00:00	messages/5/a3/5a3f1c70-0000-4000-8000-000000000010	1	\N	NW-007-26	EXPIRED	\N	COASTAL_WARNING	5a3f1c70-0000-4000-8000-000000000010	2026	1000010	\N	\N	1000001	WARNING
1000011	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-13.679	f	2026-08-10 08:00:00	\N	\N	t	WGS84	\N	NW	8	f	2026-08-10 08:00:00	2026-08-21 08:00:00	messages/5/a3/5a3f1c70-0000-4000-8000-000000000011	1	\N	NW-008-26	CANCELLED	\N	COASTAL_WARNING	5a3f1c70-0000-4000-8000-000000000011	2026	1000016	\N	\N	1000001	WARNING
1000012	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-55.16	f	\N	\N	\N	t	WGS84	\N	NW	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000012	1	\N	\N	DELETED	\N	COASTAL_WARNING	5a3f1c70-0000-4000-8000-000000000012	\N	1000011	\N	\N	1000001	WARNING
1000013	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-55.675	f	2026-10-20 06:00:00	2026-10-30 14:00:00	\N	t	WGS84	\N	NW	\N	f	2026-10-14 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000013	1	\N	\N	PUBLISHED	\N	LOCAL_WARNING	5a3f1c70-0000-4000-8000-000000000013	2026	1000009	\N	\N	1000002	WARNING
1000014	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-9.935	f	\N	\N	\N	t	WGS84	\N	NW	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000014	1	\N	\N	DRAFT	\N	LOCAL_WARNING	5a3f1c70-0000-4000-8000-000000000014	\N	1000034	\N	\N	1000002	WARNING
1000015	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-284.944	f	2026-09-04 08:00:00	\N	\N	t	WGS84	\N	NM	1	f	2026-09-04 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000015	1	\N	NM-001-26	PUBLISHED	\N	PERMANENT_NOTICE	5a3f1c70-0000-4000-8000-000000000015	2026	1000016	\N	\N	1000003	WARNING
1000016	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-54.95	f	2026-09-11 08:00:00	\N	\N	t	WGS84	\N	NM	2	f	2026-09-11 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000016	1	\N	NM-002-26	PUBLISHED	\N	PERMANENT_NOTICE	5a3f1c70-0000-4000-8000-000000000016	2026	1000011	\N	\N	1000003	WARNING
1000017	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-56.03	f	2026-09-18 08:00:00	\N	\N	t	WGS84	\N	NM	3	f	2026-09-18 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000017	1	\N	NM-003-26	PUBLISHED	\N	TEMPORARY_NOTICE	5a3f1c70-0000-4000-8000-000000000017	2026	1000009	\N	\N	1000003	WARNING
1000018	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-57.6	f	2026-09-25 08:00:00	\N	\N	t	WGS84	\N	NM	4	f	2026-09-25 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000018	1	\N	NM-004-26	PUBLISHED	\N	TEMPORARY_NOTICE	5a3f1c70-0000-4000-8000-000000000018	2026	1000035	\N	\N	1000003	WARNING
1000019	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-55.19	f	2026-11-02 08:00:00	2026-12-11 08:00:00	\N	t	WGS84	\N	NM	5	f	2026-10-02 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000019	1	\N	NM-005-26	PUBLISHED	\N	PRELIMINARY_NOTICE	5a3f1c70-0000-4000-8000-000000000019	2026	1000013	\N	\N	1000003	WARNING
1000020	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-12.15	f	2026-10-19 08:00:00	2026-11-06 08:00:00	\N	t	WGS84	\N	NM	6	f	2026-10-09 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000020	1	\N	NM-006-26	PUBLISHED	\N	MISCELLANEOUS_NOTICE	5a3f1c70-0000-4000-8000-000000000020	2026	1000010	\N	\N	1000003	WARNING
1000021	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-256.438	f	\N	\N	\N	t	WGS84	\N	NM	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000021	1	\N	\N	DRAFT	\N	PERMANENT_NOTICE	5a3f1c70-0000-4000-8000-000000000021	\N	1000002	\N	\N	1000003	WARNING
1000022	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-55.44	f	\N	\N	\N	t	WGS84	\N	NM	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000022	1	\N	\N	DRAFT	\N	PRELIMINARY_NOTICE	5a3f1c70-0000-4000-8000-000000000022	\N	1000040	\N	\N	1000003	WARNING
1000023	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-10.62	f	\N	\N	\N	t	WGS84	\N	NM	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000023	1	\N	\N	VERIFIED	\N	TEMPORARY_NOTICE	5a3f1c70-0000-4000-8000-000000000023	\N	1000012	\N	\N	1000003	WARNING
1000024	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-11.235	f	2026-07-31 08:00:00	2026-08-25 08:00:00	\N	t	WGS84	\N	NM	7	f	2026-07-31 08:00:00	2026-08-25 08:00:00	messages/5/a3/5a3f1c70-0000-4000-8000-000000000024	1	\N	NM-007-26	EXPIRED	\N	TEMPORARY_NOTICE	5a3f1c70-0000-4000-8000-000000000024	2026	1000010	\N	\N	1000003	WARNING
1000025	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-9.05	f	2026-08-07 08:00:00	\N	\N	t	WGS84	\N	NM	8	f	2026-08-07 08:00:00	2026-09-01 08:00:00	messages/5/a3/5a3f1c70-0000-4000-8000-000000000025	1	\N	NM-008-26	CANCELLED	\N	PERMANENT_NOTICE	5a3f1c70-0000-4000-8000-000000000025	2026	1000034	\N	\N	1000003	WARNING
1000026	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-170.941	f	\N	\N	\N	t	WGS84	\N	NM	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000026	1	\N	\N	DELETED	\N	MISCELLANEOUS_NOTICE	5a3f1c70-0000-4000-8000-000000000026	\N	1000016	\N	\N	1000003	WARNING
1000027	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-306.264	f	2026-09-01 08:00:00	\N	\N	t	WGS84	\N	NM	\N	f	2026-09-01 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000027	1	\N	FA-001-26	PUBLISHED	\N	MISCELLANEOUS_NOTICE	5a3f1c70-0000-4000-8000-000000000027	2026	1000021	\N	\N	1000004	WARNING
1000028	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-57.815	f	2026-09-01 08:00:00	\N	\N	t	WGS84	\N	NM	\N	f	2026-09-01 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000028	1	\N	FA-002-26	PUBLISHED	\N	MISCELLANEOUS_NOTICE	5a3f1c70-0000-4000-8000-000000000028	2026	1000036	\N	\N	1000004	WARNING
1000029	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-338.059	f	2026-09-01 08:00:00	\N	\N	t	WGS84	\N	NM	\N	f	2026-09-01 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000029	1	\N	FA-003-26	PUBLISHED	\N	MISCELLANEOUS_NOTICE	5a3f1c70-0000-4000-8000-000000000029	2026	1000008	\N	\N	1000004	WARNING
1000030	2026-10-22 00:00:00	2026-10-22 00:00:00	0	-55.61	f	\N	\N	\N	t	WGS84	\N	NM	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000030	1	\N	\N	DRAFT	\N	MISCELLANEOUS_NOTICE	5a3f1c70-0000-4000-8000-000000000030	\N	1000042	\N	\N	1000004	WARNING
1000031	2026-10-22 00:00:00	2026-10-22 00:00:00	0	0	f	2026-09-01 08:00:00	\N	\N	f	WGS84	\N	NM	1	f	2026-09-01 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000031	1	\N	A/1 2026	PUBLISHED	\N	MISCELLANEOUS_NOTICE	5a3f1c70-0000-4000-8000-000000000031	2026	1000001	\N	\N	1000005	WARNING
1000032	2026-10-22 00:00:00	2026-10-22 00:00:00	0	0	f	\N	\N	\N	f	WGS84	\N	NM	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000032	1	\N	\N	DRAFT	\N	MISCELLANEOUS_NOTICE	5a3f1c70-0000-4000-8000-000000000032	\N	1000001	\N	\N	1000005	WARNING
1000033	2026-10-22 00:00:00	2026-10-22 00:00:00	0	0	f	2026-09-01 08:00:00	\N	\N	f	WGS84	\N	NM	1	f	2026-09-01 08:00:00	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000033	1	\N	B/1 2026	PUBLISHED	\N	MISCELLANEOUS_NOTICE	5a3f1c70-0000-4000-8000-000000000033	2026	1000001	\N	\N	1000006	WARNING
1000034	2026-10-22 00:00:00	2026-10-22 00:00:00	0	0	f	\N	\N	\N	f	WGS84	\N	NM	\N	f	\N	\N	messages/5/a3/5a3f1c70-0000-4000-8000-000000000034	1	\N	\N	DRAFT	\N	MISCELLANEOUS_NOTICE	5a3f1c70-0000-4000-8000-000000000034	\N	1000001	\N	\N	1000006	WARNING
\.


--
-- Data for Name: MessageDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MessageDesc" (id, lang, "internalPublication", publication, source, title, vicinity, entity_id) FROM stdin;
1000001	en	\N	\N	\N	SAMPLE — Kattegat. North of Anholt. Light buoy SAMPLE-1 unlit.	North of Anholt	1000001
1000002	da	\N	\N	\N	SAMPLE — Kattegat. Nord for Anholt. Lystønde SAMPLE-1 slukket.	Nord for Anholt	1000001
1000003	en	\N	\N	\N	SAMPLE — The Great Belt. West of Sprogø. Wreck.	West of Sprogø	1000002
1000004	da	\N	\N	\N	SAMPLE — Storebælt. Vest for Sprogø. Vrag.	Vest for Sprogø	1000002
1000005	en	\N	\N	\N	SAMPLE — The Sound. Off Hellerup. Diving operations.	Off Hellerup	1000003
1000006	da	\N	\N	\N	SAMPLE — Sundet. Ud for Hellerup. Dykkerarbejde.	Ud for Hellerup	1000003
1000007	en	\N	\N	\N	SAMPLE — The Baltic Sea. North of Bornholm. Container adrift.	North of Bornholm	1000004
1000008	da	\N	\N	\N	SAMPLE — Østersøen. Nord for Bornholm. Container i drift.	Nord for Bornholm	1000004
1000009	en	\N	\N	\N	SAMPLE — Skagerrak. Off Skagen. RACON SAMPLE-2 out of service.	Off Skagen	1000005
1000010	da	\N	\N	\N	SAMPLE — Skagerrak. Ud for Skagen. RACON SAMPLE-2 ude af drift.	Ud for Skagen	1000005
1000011	en	\N	\N	\N	SAMPLE — The Little Belt. South of Middelfart. Cable operations.	South of Middelfart	1000006
1000012	da	\N	\N	\N	SAMPLE — Lillebælt. Syd for Middelfart. Kabelarbejde.	Syd for Middelfart	1000006
1000013	en	\N	\N	\N	SAMPLE — Waters South of Funen. Off Ærø. Buoy SAMPLE-3 missing.	Off Ærø	1000007
1000014	da	\N	\N	\N	SAMPLE — Farvandet syd for Fyn. Ud for Ærø. Tønde SAMPLE-3 savnet.	Ud for Ærø	1000007
1000015	en	\N	\N	\N	SAMPLE — The Liim Fiord. Off Thyborøn. Hydrographic survey.	Off Thyborøn	1000008
1000016	da	\N	\N	\N	SAMPLE — Limfjorden. Ud for Thyborøn. Opmåling.	Ud for Thyborøn	1000008
1000017	en	\N	\N	\N	SAMPLE — The North Sea. West of Horns Rev. Underwater survey.	West of Horns Rev	1000009
1000018	da	\N	\N	\N	SAMPLE — Nordsøen. Vest for Horns Rev. Havbundsundersøgelse.	Vest for Horns Rev	1000009
1000019	en	\N	\N	\N	SAMPLE — The Waters South of Zealand. Salvage operations.	South of Zealand	1000010
1000020	da	\N	\N	\N	SAMPLE — Smålandsfarvandet. Bjærgningsarbejde.	Syd for Sjælland	1000010
1000021	en	\N	\N	\N	SAMPLE — Kattegat. Off Gilleleje. Light SAMPLE-4 unreliable.	Off Gilleleje	1000011
1000022	da	\N	\N	\N	SAMPLE — Kattegat. Ud for Gilleleje. Fyr SAMPLE-4 upålideligt.	Ud for Gilleleje	1000011
1000023	en	\N	\N	\N	SAMPLE — The Great Belt. Naval exercise.	Southern part	1000012
1000024	da	\N	\N	\N	SAMPLE — Storebælt. Flådeøvelse.	Sydlige del	1000012
1000025	en	\N	\N	\N	SAMPLE — The Sound. Copenhagen. Bridge SAMPLE-5 closed for passage.	Copenhagen	1000013
1000026	da	\N	\N	\N	SAMPLE — Sundet. København. Bro SAMPLE-5 lukket for gennemsejling.	København	1000013
1000027	en	\N	\N	\N	SAMPLE — The Liim Fiord. Off Aalborg. Buoy SAMPLE-6 off station.	Off Aalborg	1000014
1000028	da	\N	\N	\N	SAMPLE — Limfjorden. Ud for Aalborg. Tønde SAMPLE-6 ude af position.	Ud for Aalborg	1000014
1000029	en	\N	\N	\N	SAMPLE — Kattegat. Samsø Belt. Light buoy SAMPLE-7 established.	Samsø Belt	1000015
1000030	da	\N	\N	\N	SAMPLE — Kattegat. Samsø Bælt. Lystønde SAMPLE-7 udlagt.	Samsø Bælt	1000015
1000031	en	\N	\N	\N	SAMPLE — The Great Belt. Buoy SAMPLE-8 withdrawn.	East of Langeland	1000016
1000032	da	\N	\N	\N	SAMPLE — Storebælt. Tønde SAMPLE-8 inddraget.	Øst for Langeland	1000016
1000033	en	\N	\N	\N	SAMPLE — The Sound. Off Helsingør. Light SAMPLE-9 damaged.	Off Helsingør	1000017
1000034	da	\N	\N	\N	SAMPLE — Sundet. Ud for Helsingør. Fyr SAMPLE-9 beskadiget.	Ud for Helsingør	1000017
1000035	en	\N	\N	\N	SAMPLE — Skagerrak. Off Ålbæk. Reduced depth.	Off Ålbæk	1000018
1000036	da	\N	\N	\N	SAMPLE — Skagerrak. Ud for Ålbæk. Mindre vanddybde.	Ud for Ålbæk	1000018
1000037	en	\N	\N	\N	SAMPLE — The Little Belt. Southern part. Planned hydrographic survey.	Southern part	1000019
1000038	da	\N	\N	\N	SAMPLE — Lillebælt. Sydlige del. Planlagt opmåling.	Sydlige del	1000019
1000039	en	\N	\N	\N	SAMPLE — The Waters South of Zealand. AIS test transmissions.	Grønsund	1000020
1000040	da	\N	\N	\N	SAMPLE — Smålandsfarvandet. AIS-testudsendelser.	Grønsund	1000020
1000041	en	\N	\N	\N	SAMPLE — The Baltic Sea. Off Gedser. Beacon SAMPLE-10 damaged.	Off Gedser	1000021
1000042	da	\N	\N	\N	SAMPLE — Østersøen. Ud for Gedser. Båke SAMPLE-10 beskadiget.	Ud for Gedser	1000021
1000043	en	\N	\N	\N	SAMPLE — The North Sea. Horns Rev. Planned seabed survey.	Horns Rev	1000022
1000044	da	\N	\N	\N	SAMPLE — Nordsøen. Horns Rev. Planlagt havbundsundersøgelse.	Horns Rev	1000022
1000045	en	\N	\N	\N	SAMPLE — Waters South of Funen. Svendborg Sound. Light buoy SAMPLE-11 replaced.	Svendborg Sound	1000023
1000046	da	\N	\N	\N	SAMPLE — Farvandet syd for Fyn. Svendborg Sund. Lystønde SAMPLE-11 erstattet.	Svendborg Sund	1000023
1000047	en	\N	\N	\N	SAMPLE — The Waters South of Zealand. Buoy SAMPLE-12 off station.	Agersø Sound	1000024
1000048	da	\N	\N	\N	SAMPLE — Smålandsfarvandet. Tønde SAMPLE-12 ude af position.	Agersø Sund	1000024
1000049	en	\N	\N	\N	SAMPLE — The Liim Fiord. Løgstør Broad. Beacon SAMPLE-13 missing.	Løgstør Broad	1000025
1000050	da	\N	\N	\N	SAMPLE — Limfjorden. Løgstør Bredning. Båke SAMPLE-13 savnet.	Løgstør Bredning	1000025
1000051	en	\N	\N	\N	SAMPLE — Kattegat. Læsø Channel. Uncharted obstruction.	Læsø Channel	1000026
1000052	da	\N	\N	\N	SAMPLE — Kattegat. Læsø Rende. Ukortlagt hindring.	Læsø Rende	1000026
1000053	en	\N	\N	\N	SAMPLE — EK R 11 Sjællands Odde W. Firing exercises.	EK R 11 Sjællands Odde W.	1000027
1000054	da	\N	\N	\N	SAMPLE — EK R 11 Sjællands Odde W. Skydeøvelser.	EK R 11 Sjællands Odde W.	1000027
1000055	en	\N	\N	\N	SAMPLE — EK D 389 Skagen N. Firing exercises.	EK D 389 Skagen N.	1000028
1000056	da	\N	\N	\N	SAMPLE — EK D 389 Skagen N. Skydeøvelser.	EK D 389 Skagen N.	1000028
1000057	en	\N	\N	\N	SAMPLE — EK D 371 Marstal Bugt. Firing exercises.	EK D 371 Marstal Bugt	1000029
1000058	da	\N	\N	\N	SAMPLE — EK D 371 Marstal Bugt. Skydeøvelser.	EK D 371 Marstal Bugt	1000029
1000059	en	\N	\N	\N	SAMPLE — EK R 33 Vejers. Firing exercises.	EK R 33 Vejers	1000030
1000060	da	\N	\N	\N	SAMPLE — EK R 33 Vejers. Skydeøvelser.	EK R 33 Vejers	1000030
1000061	en	\N	\N	\N	SAMPLE — Almanac. Abbreviations used in the notices.	Danish waters	1000031
1000062	da	\N	\N	\N	SAMPLE — Almanak. Forkortelser i efterretningerne.	Danske farvande	1000031
1000063	en	\N	\N	\N	SAMPLE — Almanac. Reporting a hazard to navigation.	Danish waters	1000032
1000064	da	\N	\N	\N	SAMPLE — Almanak. Indberetning af fare for sejladsen.	Danske farvande	1000032
1000065	en	\N	\N	\N	SAMPLE — Annex. Overview of the sample firing areas.	Danish waters	1000033
1000066	da	\N	\N	\N	SAMPLE — Bilag. Oversigt over eksemplets skydeområder.	Danske farvande	1000033
1000067	en	\N	\N	\N	SAMPLE — Annex. Explanation of the chart corrections.	Danish waters	1000034
1000068	da	\N	\N	\N	SAMPLE — Bilag. Forklaring til søkortrettelserne.	Danske farvande	1000034
\.


--
-- Data for Name: MessageFilter; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MessageFilter" (id, name, parameters, user_id) FROM stdin;
\.


--
-- Data for Name: MessagePart; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MessagePart" (id, "hideSubject", "indexNo", type, geometry_id, message_id) FROM stdin;
1000001	t	0	DETAILS	1000001	1000001
1000002	t	0	DETAILS	1000002	1000002
1000003	t	0	DETAILS	1000003	1000003
1000004	t	0	DETAILS	1000004	1000004
1000005	t	0	DETAILS	1000005	1000005
1000006	t	0	DETAILS	1000006	1000006
1000007	t	0	DETAILS	1000007	1000007
1000008	t	0	DETAILS	1000008	1000008
1000009	t	0	DETAILS	1000009	1000009
1000010	t	0	DETAILS	1000010	1000010
1000011	t	0	DETAILS	1000011	1000011
1000012	t	0	DETAILS	1000012	1000012
1000013	t	0	DETAILS	1000013	1000013
1000014	t	0	DETAILS	1000014	1000014
1000015	t	0	DETAILS	1000015	1000015
1000016	t	0	DETAILS	1000016	1000016
1000017	t	0	DETAILS	1000017	1000017
1000018	t	0	DETAILS	1000018	1000018
1000019	t	0	DETAILS	1000019	1000019
1000020	t	0	DETAILS	1000020	1000020
1000021	t	0	DETAILS	1000021	1000021
1000022	t	0	DETAILS	1000022	1000022
1000023	t	0	DETAILS	1000023	1000023
1000024	t	0	DETAILS	1000024	1000024
1000025	t	0	DETAILS	1000025	1000025
1000026	t	0	DETAILS	1000026	1000026
1000027	t	0	DETAILS	1000027	1000027
1000028	t	0	DETAILS	1000028	1000028
1000029	t	0	DETAILS	1000029	1000029
1000030	t	0	DETAILS	1000030	1000030
1000031	t	0	DETAILS	\N	1000031
1000032	t	0	DETAILS	\N	1000032
1000033	t	0	DETAILS	\N	1000033
1000034	t	0	DETAILS	\N	1000034
\.


--
-- Data for Name: MessagePartDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MessagePartDesc" (id, lang, details, subject, entity_id) FROM stdin;
1000001	en	<p>The west cardinal light buoy SAMPLE-1 in pos. 56° 48.0'N - 011° 33.0'E is unlit. This is sample content for trying Niord, not a real notice.</p>	Light buoy unlit	1000001
1000002	da	<p>Vestkardinal-lystønden SAMPLE-1 på pos. 56° 48,0'N - 011° 33,0'E er slukket. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Lystønde slukket	1000001
1000003	en	<p>A wreck with a least depth of 9 metres is reported in pos. 55° 19.8'N - 010° 54.0'E. Mariners are advised to keep well clear. This is sample content for trying Niord, not a real notice.</p>	Wreck	1000002
1000004	da	<p>Et vrag med en mindste dybde på 9 meter er meldt på pos. 55° 19,8'N - 010° 54,0'E. Skibsfarten opfordres til at holde godt klar. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Vrag	1000002
1000005	en	<p>Diving operations take place on weekdays from 0700 to 1600 in pos. 55° 42.0'N - 012° 39.6'E. The diving vessel shows the prescribed signals. Passing vessels are requested to keep clear and reduce speed. This is sample content for trying Niord, not a real notice.</p>	Diving operations	1000003
1000006	da	<p>Der udføres dykkerarbejde på hverdage fra kl. 0700 til 1600 på pos. 55° 42,0'N - 012° 39,6'E. Dykkerfartøjet viser de foreskrevne signaler. Passerende skibe anmodes om at holde klar og sænke farten. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Dykkerarbejde	1000003
1000007	en	<p>An empty container was reported adrift in pos. 55° 24.0'N - 014° 48.0'E. Mariners are requested to keep a sharp lookout. This is sample content for trying Niord, not a real notice.</p>	Object adrift	1000004
1000008	da	<p>En tom container er meldt i drift på pos. 55° 24,0'N - 014° 48,0'E. Skibsfarten anmodes om at holde skarpt udkig. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Genstand i drift	1000004
1000009	en	<p>The RACON on the light buoy SAMPLE-2 in pos. 57° 45.0'N - 010° 36.0'E is out of service. This is sample content for trying Niord, not a real notice.</p>	RACON out of service	1000005
1000010	da	<p>RACON'en på lystønden SAMPLE-2 på pos. 57° 45,0'N - 010° 36,0'E er ude af drift. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	RACON ude af drift	1000005
1000011	en	<p>Cable laying takes place between pos. 55° 30.0'N - 009° 43.2'E and pos. 55° 28.8'N - 009° 45.6'E. The cable vessel is restricted in her ability to manoeuvre. This is sample content for trying Niord, not a real notice.</p>	Cable operations	1000006
1000012	da	<p>Der udlægges kabel mellem pos. 55° 30,0'N - 009° 43,2'E og pos. 55° 28,8'N - 009° 45,6'E. Kabelfartøjet er begrænset i sin manøvreevne. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Kabelarbejde	1000006
1000013	en	<p>The green buoy SAMPLE-3 in pos. 54° 57.0'N - 010° 24.0'E is missing. This is sample content for trying Niord, not a real notice.</p>	Buoy missing	1000007
1000014	da	<p>Den grønne tønde SAMPLE-3 på pos. 54° 57,0'N - 010° 24,0'E er savnet. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Tønde savnet	1000007
1000015	en	<p>A survey vessel carries out hydrographic survey in the area shown. Passing vessels are requested to give it a wide berth. This is sample content for trying Niord, not a real notice.</p>	Hydrographic survey	1000008
1000016	da	<p>Et opmålingsfartøj udfører opmåling i det viste område. Passerende skibe anmodes om at give god plads. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Opmåling	1000008
1000017	en	<p>Seabed survey with towed equipment takes place in the area shown. The survey vessel is restricted in her ability to manoeuvre. This is sample content for trying Niord, not a real notice.</p>	Underwater survey	1000009
1000018	da	<p>Der udføres havbundsundersøgelse med slæbt udstyr i det viste område. Fartøjet er begrænset i sin manøvreevne. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Havbundsundersøgelse	1000009
1000019	en	<p>Salvage operations took place in pos. 54° 58.8'N - 011° 37.2'E. This is sample content for trying Niord, not a real notice.</p>	Salvage operations	1000010
1000020	da	<p>Der blev udført bjærgningsarbejde på pos. 54° 58,8'N - 011° 37,2'E. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Bjærgningsarbejde	1000010
1000021	en	<p>The light SAMPLE-4 in pos. 56° 10.2'N - 012° 18.0'E was unreliable. The warning is cancelled. This is sample content for trying Niord, not a real notice.</p>	Light unreliable	1000011
1000022	da	<p>Fyret SAMPLE-4 på pos. 56° 10,2'N - 012° 18,0'E var upålideligt. Advarslen er annulleret. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Fyr upålideligt	1000011
1000023	en	<p>A naval exercise was planned in the area shown. The draft was deleted. This is sample content for trying Niord, not a real notice.</p>	Exercise	1000012
1000024	da	<p>En flådeøvelse var planlagt i det viste område. Udkastet blev slettet. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Øvelse	1000012
1000025	en	<p>The bascule bridge SAMPLE-5 in pos. 55° 40.5'N - 012° 35.7'E is closed for passage on weekdays from 0700 to 1500 because of maintenance. This is sample content for trying Niord, not a real notice.</p>	Bridge closed for passage	1000013
1000026	da	<p>Klapbroen SAMPLE-5 på pos. 55° 40,5'N - 012° 35,7'E er lukket for gennemsejling på hverdage fra kl. 0700 til 1500 på grund af vedligeholdelse. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Bro lukket for gennemsejling	1000013
1000027	en	<p>The red buoy SAMPLE-6 is reported off station in pos. 57° 03.3'N - 009° 56.1'E. This is sample content for trying Niord, not a real notice.</p>	Buoy off station	1000014
1000028	da	<p>Den røde tønde SAMPLE-6 er meldt ude af position på pos. 57° 03,3'N - 009° 56,1'E. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Tønde ude af position	1000014
1000029	en	<p>A north cardinal light buoy, SAMPLE-7, Q, has been established in pos. 55° 58.8'N - 010° 51.0'E. This is sample content for trying Niord, not a real notice.</p>	Light buoy established	1000015
1000030	da	<p>En nordkardinal-lystønde, SAMPLE-7, Q, er udlagt på pos. 55° 58,8'N - 010° 51,0'E. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Lystønde udlagt	1000015
1000031	en	<p>The red buoy SAMPLE-8 in pos. 54° 57.0'N - 010° 57.0'E has been permanently withdrawn. This is sample content for trying Niord, not a real notice.</p>	Buoy withdrawn	1000016
1000032	da	<p>Den røde tønde SAMPLE-8 på pos. 54° 57,0'N - 010° 57,0'E er inddraget permanent. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Tønde inddraget	1000016
1000033	en	<p>The light structure SAMPLE-9 in pos. 56° 01.8'N - 012° 37.2'E is damaged. The light shows with reduced range until repaired. This is sample content for trying Niord, not a real notice.</p>	Light damaged	1000017
1000034	da	<p>Fyrkonstruktionen SAMPLE-9 på pos. 56° 01,8'N - 012° 37,2'E er beskadiget. Fyret viser med nedsat lysvidde, indtil det er repareret. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Fyr beskadiget	1000017
1000035	en	<p>A depth of 6.2 metres, less than charted, has been found in the area shown. This is sample content for trying Niord, not a real notice.</p>	Reduced depth	1000018
1000036	da	<p>Der er fundet en vanddybde på 6,2 meter, mindre end angivet i søkortet, i det viste område. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Mindre vanddybde	1000018
1000037	en	<p>A hydrographic survey of the area shown is planned for November and December, after the cable operations of NW-006-26. This is sample content for trying Niord, not a real notice.</p>	Planned hydrographic survey	1000019
1000038	da	<p>En opmåling af det viste område er planlagt i november og december, efter kabelarbejdet i NW-006-26. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Planlagt opmåling	1000019
1000039	en	<p>A temporary AIS station in pos. 54° 51.0'N - 012° 09.0'E transmits test messages. The messages may be disregarded. This is sample content for trying Niord, not a real notice.</p>	AIS test transmissions	1000020
1000040	da	<p>En midlertidig AIS-station på pos. 54° 51,0'N - 012° 09,0'E udsender testmeddelelser. Meddelelserne kan ses bort fra. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	AIS-testudsendelser	1000020
1000041	en	<p>The beacon SAMPLE-10 in pos. 54° 37.2'N - 012° 03.0'E is damaged. This is sample content for trying Niord, not a real notice.</p>	Beacon damaged	1000021
1000042	da	<p>Båken SAMPLE-10 på pos. 54° 37,2'N - 012° 03,0'E er beskadiget. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Båke beskadiget	1000021
1000043	en	<p>A seabed survey of the area shown is planned for the spring. This is sample content for trying Niord, not a real notice.</p>	Planned seabed survey	1000022
1000044	da	<p>En havbundsundersøgelse af det viste område er planlagt i foråret. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Planlagt havbundsundersøgelse	1000022
1000045	en	<p>The light buoy SAMPLE-11 in pos. 55° 03.0'N - 010° 37.2'E has been temporarily replaced by an unlit buoy. This is sample content for trying Niord, not a real notice.</p>	Light buoy replaced	1000023
1000046	da	<p>Lystønden SAMPLE-11 på pos. 55° 03,0'N - 010° 37,2'E er midlertidigt erstattet af en tønde uden lys. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Lystønde erstattet	1000023
1000047	en	<p>The buoy SAMPLE-12 was off station in pos. 55° 12.0'N - 011° 14.1'E. It has been put back. This is sample content for trying Niord, not a real notice.</p>	Buoy off station	1000024
1000048	da	<p>Tønden SAMPLE-12 var ude af position på pos. 55° 12,0'N - 011° 14,1'E. Den er lagt på plads igen. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Tønde ude af position	1000024
1000049	en	<p>The beacon SAMPLE-13 in pos. 56° 57.0'N - 009° 03.0'E was reported missing. The notice is cancelled. This is sample content for trying Niord, not a real notice.</p>	Beacon missing	1000025
1000050	da	<p>Båken SAMPLE-13 på pos. 56° 57,0'N - 009° 03,0'E blev meldt savnet. Efterretningen er annulleret. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Båke savnet	1000025
1000051	en	<p>An uncharted obstruction was reported in pos. 57° 13.2'N - 010° 42.0'E. The draft was deleted. This is sample content for trying Niord, not a real notice.</p>	Uncharted obstruction	1000026
1000052	da	<p>En ukortlagt hindring blev meldt på pos. 57° 13,2'N - 010° 42,0'E. Udkastet blev slettet. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Ukortlagt hindring	1000026
1000053	en	<p>Firing exercises take place in the firing area EK R 11 Sjællands Odde W. on the days announced. The area is shown on the map. Ships are requested to keep clear while the warning flags are hoisted. This is sample content for trying Niord, not a real notice.</p>	Firing exercises	1000027
1000054	da	<p>Der afholdes skydeøvelser i skydeområdet EK R 11 Sjællands Odde W. på de bekendtgjorte dage. Området er vist på kortet. Skibe anmodes om at holde klar, mens advarselsflagene er hejst. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Skydeøvelser	1000027
1000055	en	<p>Firing exercises take place in the firing area EK D 389 Skagen N. on the days announced. The area is shown on the map. Ships are requested to keep clear while the warning flags are hoisted. This is sample content for trying Niord, not a real notice.</p>	Firing exercises	1000028
1000056	da	<p>Der afholdes skydeøvelser i skydeområdet EK D 389 Skagen N. på de bekendtgjorte dage. Området er vist på kortet. Skibe anmodes om at holde klar, mens advarselsflagene er hejst. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Skydeøvelser	1000028
1000057	en	<p>Firing exercises take place in the firing area EK D 371 Marstal Bugt on the days announced. The area is shown on the map. Ships are requested to keep clear while the warning flags are hoisted. This is sample content for trying Niord, not a real notice.</p>	Firing exercises	1000029
1000058	da	<p>Der afholdes skydeøvelser i skydeområdet EK D 371 Marstal Bugt på de bekendtgjorte dage. Området er vist på kortet. Skibe anmodes om at holde klar, mens advarselsflagene er hejst. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Skydeøvelser	1000029
1000059	en	<p>Firing exercises take place in the firing area EK R 33 Vejers on the days announced. The area is shown on the map. Ships are requested to keep clear while the warning flags are hoisted. This is sample content for trying Niord, not a real notice.</p>	Firing exercises	1000030
1000060	da	<p>Der afholdes skydeøvelser i skydeområdet EK R 33 Vejers på de bekendtgjorte dage. Området er vist på kortet. Skibe anmodes om at holde klar, mens advarselsflagene er hejst. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Skydeøvelser	1000030
1000061	en	<p>Fl = flashing, Q = quick flashing, Oc = occulting, Iso = isophase, F = fixed. The list is an example of almanac content. This is sample content for trying Niord, not a real notice.</p>	Abbreviations	1000031
1000062	da	<p>Fl = blink, Q = hurtigblink, Oc = formørkelser, Iso = isofase, F = fast. Listen er et eksempel på almanakindhold. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Forkortelser	1000031
1000063	en	<p>Mariners who observe a hazard to navigation are asked to report it to the nearest coast radio station. The text is an example of almanac content. This is sample content for trying Niord, not a real notice.</p>	Reporting a hazard	1000032
1000064	da	<p>Søfarende, der observerer en fare for sejladsen, bedes melde den til nærmeste kystradiostation. Teksten er et eksempel på almanakindhold. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Indberetning af fare	1000032
1000065	en	<p>The attached overview shows where the sample's firing areas lie. The annex is an example of annex content. This is sample content for trying Niord, not a real notice.</p>	Firing areas	1000033
1000066	da	<p>Den vedhæftede oversigt viser, hvor eksemplets skydeområder ligger. Bilaget er et eksempel på bilagsindhold. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Skydeområder	1000033
1000067	en	<p>The annex explains how the chart corrections of the notices are to be read. The text is an example of annex content. This is sample content for trying Niord, not a real notice.</p>	Chart corrections	1000034
1000068	da	<p>Bilaget forklarer, hvordan efterretningernes søkortrettelser skal læses. Teksten er et eksempel på bilagsindhold. Dette er eksempelindhold til at prøve Niord, ikke en rigtig efterretning.</p>	Søkortrettelser	1000034
\.


--
-- Data for Name: MessageSeries; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MessageSeries" (id, created, updated, version, "excludeFromMessageIndex", "mainType", "navtexFormat", "numberSequenceType", "seriesId", "shortFormat") FROM stdin;
1000001	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	NW	${publish-date}\nDANISH NAV WARN ${number-year-id}	0	dma-nw	NW-${number-3-digits}-${year-2-digits}
1000002	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	NW	\N	3	dma-nw-local	\N
1000003	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	NM	\N	0	dma-nm	NM-${number-3-digits}-${year-2-digits}
1000004	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	NM	\N	2	dma-fa	\N
1000005	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	NM	\N	2	dma-nm-almanac	A/${number} ${year}
1000006	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	NM	\N	2	dma-nm-annex	B/${number} ${year}
\.


--
-- Data for Name: MessageSeriesMapping; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MessageSeriesMapping" (id, "sourceSeriesId", "targetSeriesId", "niordIntegration_id") FROM stdin;
\.


--
-- Data for Name: MessageSeries_editorFields; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MessageSeries_editorFields" ("MessageSeries_id", "editorFields") FROM stdin;
\.


--
-- Data for Name: MessageSeries_types; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MessageSeries_types" ("MessageSeries_id", types) FROM stdin;
\.


--
-- Data for Name: MessageTag; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MessageTag" (id, created, updated, version, "expiryDate", locked, "messageCount", name, "tagId", type, domain_id, user_id) FROM stdin;
\.


--
-- Data for Name: MessageTag_Message; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."MessageTag_Message" (tags_id, messages_id) FROM stdin;
\.


--
-- Data for Name: Message_Area; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Message_Area" ("Message_id", areas_id, "areas_ORDER") FROM stdin;
1000001	1000016	0
1000002	1000011	0
1000003	1000009	0
1000004	1000002	0
1000005	1000035	0
1000006	1000013	0
1000007	1000012	0
1000008	1000034	0
1000009	1000040	0
1000010	1000010	0
1000011	1000016	0
1000012	1000011	0
1000013	1000009	0
1000014	1000034	0
1000015	1000016	0
1000016	1000011	0
1000017	1000009	0
1000018	1000035	0
1000019	1000013	0
1000020	1000010	0
1000021	1000002	0
1000022	1000040	0
1000023	1000012	0
1000024	1000010	0
1000025	1000034	0
1000026	1000016	0
1000027	1000021	0
1000028	1000036	0
1000029	1000008	0
1000030	1000042	0
1000031	1000001	0
1000032	1000001	0
1000033	1000001	0
1000034	1000001	0
\.


--
-- Data for Name: Message_Category; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Message_Category" ("Message_id", categories_id) FROM stdin;
1000010	28
1000011	3
1000012	35
1000013	33
1000014	16
1000015	10
1000016	19
1000017	4
1000018	27
1000019	29
1000001	8
1000020	44
1000021	22
1000022	31
1000023	11
1000024	16
1000025	21
1000026	26
1000027	13
1000028	13
1000029	13
1000002	25
1000030	13
1000003	32
1000004	40
1000005	43
1000006	30
1000007	15
1000008	29
1000009	31
\.


--
-- Data for Name: Message_Chart; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Message_Chart" ("Message_id", charts_id, "charts_ORDER") FROM stdin;
1000001	1000058	0
1000001	1000037	1
1000002	1000029	0
1000002	1000027	1
1000003	1000033	0
1000003	1000031	1
1000004	1000008	0
1000005	1000063	0
1000006	1000024	0
1000007	1000023	0
1000008	1000050	0
1000009	1000062	0
1000010	1000016	0
1000011	1000034	0
1000012	1000028	0
1000013	1000030	0
1000014	1000052	0
1000015	1000056	0
1000015	1000035	1
1000016	1000027	0
1000017	1000033	0
1000018	1000063	0
1000019	1000023	0
1000020	1000015	0
1000021	1000009	0
1000022	1000061	0
1000023	1000011	0
1000024	1000016	0
1000025	1000049	0
1000026	1000038	0
1000027	1000034	0
1000028	1000063	0
1000029	1000023	0
1000030	1000062	0
\.


--
-- Data for Name: NavtexMessagePromulgation; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."NavtexMessagePromulgation" (preamble, priority, text, id) FROM stdin;
\.


--
-- Data for Name: NavtexMessagePromulgation_NavtexTransmitter; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."NavtexMessagePromulgation_NavtexTransmitter" ("NavtexMessagePromulgation_id", transmitters_id) FROM stdin;
\.


--
-- Data for Name: NavtexTransmitter; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."NavtexTransmitter" (id, active, name, "promulgationType_id") FROM stdin;
\.


--
-- Data for Name: NavtexTransmitter_Area; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."NavtexTransmitter_Area" ("NavtexTransmitter_id", areas_id, "areas_ORDER") FROM stdin;
\.


--
-- Data for Name: NiordIntegration; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."NiordIntegration" (id, active, "assignNewUids", "createBaseData", "nextScheduledExecution", url) FROM stdin;
\.


--
-- Data for Name: ParamType; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."ParamType" ("DTYPE", id, name) FROM stdin;
CompositeParamType	1	ais
CompositeParamType	2	buoy_by_function
CompositeParamType	3	buoy_by_type
CompositeParamType	4	buoy_replacement
ListParamType	5	buoy_function_list
ListParamType	6	buoy_type_list
ListParamType	7	beacon_function_list
CompositeParamType	8	light
CompositeParamType	9	light_buoy_by_function
CompositeParamType	10	light_buoy_by_type
CompositeParamType	11	light_buoy_replacement
CompositeParamType	12	beacon_by_function
ListParamType	13	light_buoy_function_list
ListParamType	14	light_buoy_type_list
ListParamType	15	light_list
ListParamType	16	marking_bearing
ListParamType	17	marking_type_list
ListParamType	18	other_marking_color_list
ListParamType	19	other_marking_type_list
ListParamType	20	object_adrift_list
CompositeParamType	21	racon
ListParamType	22	radio_navigation_aton_list
ListParamType	23	radio_navigation_status_list
CompositeParamType	24	reduced_depth
ListParamType	25	vessel_type_list
ListParamType	26	salvage_type_list
ListParamType	27	exercise_type_list
CompositeParamType	28	wreck
ListParamType	29	obstruction_type_list
ListParamType	30	obstruction_report_list
ListParamType	31	aton_withdrawn_duration
CompositeParamType	32	obstruction
CompositeParamType	1000001	dgps
ListParamType	1000002	dgps_station_list
\.


--
-- Data for Name: ParamType_DictionaryEntry; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."ParamType_DictionaryEntry" ("ListParamType_id", values_id, "indexNo") FROM stdin;
5	321	0
5	347	1
5	294	2
5	353	3
5	356	4
5	335	5
5	295	6
5	308	7
5	351	8
5	280	9
5	289	10
5	326	11
5	307	12
6	322	0
6	375	1
6	310	2
6	358	3
6	287	4
6	376	5
6	368	6
6	330	7
6	379	8
6	365	9
6	320	10
7	301	0
7	309	1
7	319	2
7	281	3
7	305	4
13	380	0
13	284	1
13	359	2
13	366	3
13	369	4
13	367	5
13	328	6
13	372	7
13	343	8
13	381	9
13	382	10
13	304	11
13	357	12
13	307	13
14	354	0
14	312	1
14	362	2
14	363	3
14	384	4
14	278	5
14	331	6
14	387	7
14	378	8
14	283	9
14	348	10
14	288	11
15	279	0
15	352	1
15	339	2
15	346	3
15	373	4
15	342	5
15	336	6
16	306	0
16	293	1
16	303	2
16	299	3
16	297	4
16	341	5
16	388	6
16	286	7
17	322	0
17	375	1
17	310	2
17	358	3
17	287	4
17	376	5
17	368	6
17	330	7
17	379	8
17	365	9
17	320	10
17	354	11
17	312	12
17	362	13
17	363	14
17	384	15
17	278	16
17	387	17
17	331	18
17	378	19
17	283	20
17	348	21
17	288	22
18	332	0
18	292	1
18	282	2
18	329	3
18	340	4
19	345	0
19	324	1
19	371	2
20	361	0
20	333	1
20	344	2
20	374	3
20	386	4
20	296	5
20	337	6
20	314	7
20	318	8
20	383	9
20	385	10
20	291	11
20	300	12
22	279	0
22	321	1
22	347	2
22	294	3
22	353	4
22	356	5
22	335	6
22	308	7
22	295	8
22	351	9
22	289	10
22	280	11
22	380	12
22	284	13
22	359	14
22	366	15
22	369	16
22	367	17
22	328	18
22	372	19
22	343	20
22	382	21
22	381	22
22	304	23
23	325	0
23	364	1
25	285	0
25	338	1
25	302	2
26	327	0
26	298	1
26	349	2
26	313	3
27	370	0
27	360	1
27	315	2
27	311	3
27	317	4
29	290	0
29	316	1
29	350	2
30	323	0
30	355	1
31	377	0
31	334	1
1000002	1000001	0
1000002	1000004	1
1000002	1000003	2
\.


--
-- Data for Name: ParamType_TemplateParam; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."ParamType_TemplateParam" ("CompositeParamType_id", "templateParams_id", "indexNo") FROM stdin;
1	34	0
1	35	1
1	36	2
2	37	0
2	38	1
3	39	0
3	40	1
4	41	0
4	42	1
4	43	2
8	44	0
8	45	1
9	46	0
9	47	1
10	48	0
10	49	1
10	50	2
11	51	0
11	52	1
11	53	2
11	54	3
12	55	0
12	56	1
21	57	0
21	58	1
21	59	2
24	60	0
24	61	1
28	62	0
28	63	1
28	64	2
32	65	0
32	66	1
32	67	2
32	68	3
1000001	1000002	0
1000001	1000003	1
\.


--
-- Data for Name: PromulgationType; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PromulgationType" (id, created, updated, version, active, language, name, priority, requirement, "serviceId", "typeId") FROM stdin;
1	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	en	NAVTEX	1	\N	navtex	navtex
2	2026-10-22 00:00:00	2026-10-22 00:00:00	0	f	en	Twitter	100	\N	twitter	twitter
3	2026-10-22 00:00:00	2026-10-22 00:00:00	0	t	en	Audio	50	\N	audio	audio
\.


--
-- Data for Name: PromulgationType_Domain; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PromulgationType_Domain" ("PromulgationType_id", domains_id) FROM stdin;
1	1000001
2	1000001
3	1000001
2	1000002
\.


--
-- Data for Name: PromulgationType_messageTypes; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PromulgationType_messageTypes" ("PromulgationType_id", "messageTypes") FROM stdin;
\.


--
-- Data for Name: PromulgationType_scriptResourcePaths; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PromulgationType_scriptResourcePaths" ("PromulgationType_id", "scriptResourcePaths", "indexNo") FROM stdin;
\.


--
-- Data for Name: Publication; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Publication" (id, created, updated, version, edition, "languageSpecific", "mainType", "messagePublication", "messageTagFilter", "messageTagFormat", "periodicalType", "printSettings", "publicationId", "publishDateFrom", "publishDateTo", "repoPath", "reportParams", revision, status, type, category_id, domain_id, "messageTag_id", template_id) FROM stdin;
1000001	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	f	PUBLICATION	EXTERNAL	\N	\N	\N	{}	ef66576d-cd45-e3ba-cc38-566fc8b95c3c	2016-01-01 00:00:00	\N	publications/e/f6/ef66576d-cd45-e3ba-cc38-566fc8b95c3c	{}	0	ACTIVE	LINK	1000001	\N	\N	\N
1000002	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	t	PUBLICATION	EXTERNAL	\N	\N	\N	{}	9cbd7e19-4c03-bde8-3709-ac38c109e660	2016-01-01 00:00:00	\N	publications/9/cb/9cbd7e19-4c03-bde8-3709-ac38c109e660	{}	0	ACTIVE	LINK	1000002	\N	\N	\N
1000003	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	t	PUBLICATION	EXTERNAL	\N	\N	\N	{}	d19aa22c-680e-aa00-ae06-f5cb03ab156a	2016-01-01 00:00:00	\N	publications/d/19/d19aa22c-680e-aa00-ae06-f5cb03ab156a	{}	0	ACTIVE	LINK	1000002	\N	\N	\N
1000004	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	f	PUBLICATION	INTERNAL	\N	\N	\N	{}	5c5430a7-df38-89fb-a9be-69db1d1db008	2016-01-01 00:00:00	\N	publications/5/c5/5c5430a7-df38-89fb-a9be-69db1d1db008	{}	0	ACTIVE	LINK	1000003	\N	\N	\N
1000005	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	f	PUBLICATION	INTERNAL	\N	\N	\N	{}	0ac02970-3c78-c29f-e074-fdccf9372cf6	2016-01-01 00:00:00	\N	publications/0/ac/0ac02970-3c78-c29f-e074-fdccf9372cf6	{}	0	ACTIVE	NONE	1000003	\N	\N	\N
1000006	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	f	PUBLICATION	INTERNAL	\N	\N	\N	{}	e4ce491d-803e-fb33-04e0-53d34b04a1e4	2016-01-01 00:00:00	\N	publications/e/4c/e4ce491d-803e-fb33-04e0-53d34b04a1e4	{}	0	ACTIVE	NONE	1000003	\N	\N	\N
1000007	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	t	TEMPLATE	NONE	\N	nm-w${week-2-digits}-${year}	WEEKLY	{"report":"nm-report","pageSize":"A4","pageOrientation":"portrait","mapThumbnails":false}	22c1d529-1ddb-dfe9-10b3-6c29a6d3efff	\N	\N	publications/2/2c/22c1d529-1ddb-dfe9-10b3-6c29a6d3efff	{"year":"${year}","week":"${week}"}	0	ACTIVE	MESSAGE_REPORT	1000004	1000002	\N	\N
1000008	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\N	t	TEMPLATE	NONE	(msg.type == Type.TEMPORARY_NOTICE || msg.type == Type.PRELIMINARY_NOTICE) && msg.status == Status.PUBLISHED	nm-tp-w${week-2-digits}-${year}	WEEKLY	{"report":"nm-tp-report","pageSize":"A4","pageOrientation":"portrait","mapThumbnails":false}	2be7fafe-a5b5-3254-5eb9-1ffc421cd31b	\N	\N	publications/2/be/2be7fafe-a5b5-3254-5eb9-1ffc421cd31b	{"year":"${year}","week":"${week}"}	0	ACTIVE	MESSAGE_REPORT	1000004	1000002	\N	\N
\.


--
-- Data for Name: PublicationCategory; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PublicationCategory" (id, "categoryId", priority, publish) FROM stdin;
1000001	dk-external-publications	100	f
1000002	dk-dma-publications	50	t
1000003	dk-dma-internal-publications	60	f
1000004	dk-dma-weekly-nm-publications	10	t
\.


--
-- Data for Name: PublicationCategoryDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PublicationCategoryDesc" (id, lang, description, name, entity_id) FROM stdin;
1000001	da	\N	Eksterne publikationer	1000001
1000002	en	\N	External publications	1000001
1000003	da	\N	Søfartsstyrelsens publikationer	1000002
1000004	en	\N	Danish Maritime Authority publications	1000002
1000005	da	\N	Søfartsstyrelsens interne publikationer	1000003
1000006	en	\N	Danish Maritime Authority internal publications	1000003
1000007	da	\N	Ugentlige Efterretninger for Søfarende	1000004
1000008	en	\N	Weekly Notices to Mariners	1000004
\.


--
-- Data for Name: PublicationDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PublicationDesc" (id, lang, "fileName", link, "messagePublicationFormat", title, "titleFormat", entity_id) FROM stdin;
1000001	da	\N	https://example.org/sample/harbour-pilot	www.danskehavnelods.dk	www.danskehavnelods.dk	\N	1000001
1000002	en	\N	https://example.org/sample/harbour-pilot	www.danskehavnelods.dk	www.danskehavnelods.dk	\N	1000001
1000003	da	\N	https://example.org/sample/list-of-lights-da.pdf	Dansk Fyrliste, nr. ${parameters}	Dansk Fyrliste 2015	\N	1000002
1000004	en	\N	https://example.org/sample/list-of-lights-en.pdf	Danish List of Lights (Dansk Fyrliste) no ${parameters}	Danish List of Lights 2015	\N	1000002
1000005	da	\N	https://example.org/sample/firing-practice-areas-da.pdf	Oversigt over forsvarets skydepladser 2016, punkt ${parameters}	Skydepladser 2016	\N	1000003
1000006	en	\N	https://example.org/sample/firing-practice-areas-en.pdf	Danish List of Firing Practice Areas, section ${parameters}	Firing Practice Areas	\N	1000003
1000007	da	\N	\N	J.nr. ${parameters}	Journalnummer	\N	1000004
1000008	en	\N	\N	J. no. ${parameters}	Journal number	\N	1000004
1000009	da	\N	\N	VragID ${parameters}	Vragliste	\N	1000005
1000010	en	\N	\N	Wreck ID ${parameters}	List of wrecks	\N	1000005
1000011	da	\N	\N	AFM, nr. ${parameters}	Afmærkninger	\N	1000006
1000012	en	\N	\N	AtoN no ${parameters}	Aids to Navigation	\N	1000006
1000013	da	NtM-Uge-${week}-${year}.pdf	\N	\N	Ugentlig EfS	EfS uge ${week} - ${year}	1000007
1000014	en	EfS-Week-${week}-${year}.pdf	\N	\N	Week NtM	NtM Week ${week} - ${year}	1000007
1000015	da	NtM-TP-uge-${week}-${year}.pdf	\N	\N	Ugentlig EfS T&P	Aktive EfS T&P uge ${week} - ${year}	1000008
1000016	en	EfS-TP-Week-${week}-${year}.pdf	\N	\N	Weekly NtM T&P	Active NtM T&P Week ${week} - ${year}	1000008
\.


--
-- Data for Name: PublicationIssue; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PublicationIssue" ("cutoffReconstructed", id, "memberCount", "publishedBy_id", "retiredBy_id", series_id, "snapshotAliveAtCutoff", supersedes_id, version, week, "weekTo", year, created, "cutoffStampedAt", "intervalFrom", "intervalTo", "publicFrom", "publicTo", "publishedAt", "retiredAt", "snapshotFrozenAt", "snapshotIntervalFrom", updated, "workingSince", "workingAction", "snapshotSortBy", "legacyPublicationId", "publicId", edition, "reportId", "snapshotSourceSeriesId", "weekLabel", "yearLabel", "repoPath", "retiredReason", "criteriaOverride", "criteriaSnapshot", "cutoffSource", "intervalFromSource", "intervalToSource", "membershipProvenance", "membershipProvenanceNote", "publicWindowSource", "reportParams", "snapshotAreaIds", "snapshotCategoryIds", "snapshotChartNumbers", "snapshotDomainId", "snapshotMainTypes", "snapshotSeriesIds", "snapshotSortOrder", "snapshotSourceIssueIds", "snapshotTimeRelation", status) FROM stdin;
\.


--
-- Data for Name: PublicationIssueDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PublicationIssueDesc" (entity_id, "fileNameOverridden", "fileSize", "fileSourceSticky", id, "nameOverridden", "replacedBy_id", "fileGeneratedAt", "replacedAt", "fileHash", "filePath", "messageReferenceFormat", link, "fileName", "fileSource", lang, name) FROM stdin;
\.


--
-- Data for Name: PublicationSeries; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PublicationSeries" ("aliveAtCutoff", category_id, domain_id, id, "languageSpecific", "mapThumbnails", "nominalCutoffDayOfMonth", "nominalCutoffMonth", "sortOrder", "sourceSeries_id", version, "nominalCutoffTime", created, "firstIssueStartsAt", updated, "messageSortBy", "legacyTemplateId", "nominalCutoffTimeZone", "reportId", "seriesId", availability, cadence, "contentMode", criteria, "cutoffDefault", "importSource", kind, "messagePublication", "messageSortOrder", "nextIssueCreation", "nominalCutoffDay", "numberingScheme", "pageOrientation", "pageSize", "releaseMode", "reportParams", status, "timeRelation") FROM stdin;
\.


--
-- Data for Name: PublicationSeriesDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PublicationSeriesDesc" (entity_id, id, "messageReferenceFormat", "linkPattern", "fileNamePattern", lang, name, "nameSuggestionPattern") FROM stdin;
\.


--
-- Data for Name: PublicationSeries_AvailableDomain; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PublicationSeries_AvailableDomain" (domain_id, series_id) FROM stdin;
\.


--
-- Data for Name: PublicationSeries_languages; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."PublicationSeries_languages" ("PublicationSeries_id", "indexNo", languages) FROM stdin;
\.


--
-- Data for Name: Reference; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Reference" (id, "indexNo", "messageId", type, message_id) FROM stdin;
1000001	0	NW-006-26	0	1000019
\.


--
-- Data for Name: ReferenceDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."ReferenceDesc" (id, lang, description, entity_id) FROM stdin;
1000001	en	the cable operations	1000001
1000002	da	kabelarbejdet	1000001
\.


--
-- Data for Name: RepoFile; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."RepoFile" (path, folder, name, size, sha256, created, updated, expires, data) FROM stdin;
messages/5/a3/5a3f1c70-0000-4000-8000-000000000002/1/sample-wreck-sketch.png	messages/5/a3/5a3f1c70-0000-4000-8000-000000000002/1	sample-wreck-sketch.png	32298	709be1eb52cf87edb49560ed7c5062a90706a0060873b6396790dc59026fd3ae	2026-10-22 00:00:00+00	2026-10-22 00:00:00+00	\N	\\x89504e470d0a1a0a0000000d4948445200000140000000c804030000006b2a259800000012504c5445d6e9f5e8dfc41f3b57b4cfe2c0392b2c6e496a707f5700007dd349444154780101c87d3782002222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222220022222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222002222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000000004444444444444440000000000444440000000000000004444400000444444444444444444440000000000444440000000000000000000000000444444444444444444444444400000000000000000000000003000000000000000000044444000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000000000444444444444444000000000044444000000000000000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444444440000000000000000000000000300000000000000000004444400000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000000000044444444444444400000000004444400000000000000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444444444000000000000000000000000030000000000000000000444440000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000000004444444444444440000000000444440000000000000004444400000444444444444444444440000000000444440000000000000000000000000444444444444444444444444400000000000000000000000003000000000000000000044444000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000000000444444444444444000000000044444000000000000000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444444440000000000000000000000000300000000000000000004444400000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444444444000004444444444000004444400000000000000044444000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000044444444440000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444444444400000444444444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000004444444444000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444444440000044444444440000044444000000000000000444440000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000444444444400000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444444444000004444444444000004444400000000000000044444000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000044444444440000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444444444400000444444444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000004444444444000000000000000300000000000000000000000000000000000222200222233333333333333333333333333333333333344444333333333333333333333333344444333333333333333444443333344444333334444433333444443333344444333333333333333444443333344444333333333333333333333333344444333333333333333333333333333333333333333333333333333333333333333334444433333333333333333333333333333333333333333333333333322220022220000000000000000000000000000000000004444400000000000000000000000004444400000300000000044444000004444400000444440000044444000004444400000000000000044444000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000444440000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000000000000000444440000030000000004444400000444440000044444000004444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000044444000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000000000000044444000003000000000444440000044444000004444400000444440000044444000000000000000444440000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000004444400000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000000000000004444400000300000000044444000004444400000444440000044444000004444400000000000000044444000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000444440000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000444444444444444444444444400000444440000044444000004444400000444444444444444444440000000000444440000000000000000000000000444444444444444444440000000000000000000000000000003000000000000000000044444000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444444444444444444444440000044444000004444400000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444000000000000000000000000000000300000000000000000004444400000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444444444444444444444444000004444400000444440000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444400000000000000000000000000000030000000000000000000444440000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000444444444444444444444444400000444440000044444000004444400000444444444444444444440000000000444440000000000000000000000000444444444444444444440000000000000000000000000000003000000000000000000044444000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444444444444444444444440000044444000004444400000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444000000000000000000000000000000300000000000000000004444400000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000444440000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000044444000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000004444400000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000444440000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000044444000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000004444400000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000444440000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000044444000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000004444400000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000444440000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444444444444444444444444400000444444444444444444444444400000000000000000000000003000000000000004444444444444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444444444444444444444440000044444444444444444444444440000000000000000000000000300000000000000444444444444444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444444444444444444444444000004444444444444444444444444000000000000000000000000030000000000000044444444444444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444444444444444444444444400000444444444444444444444444400000000000000000000000003000000000000004444444444444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444444444444444444444440000044444444444444444444444440000000000000000000000000300000000000000444444444444444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022223333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333332222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000050000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000005555555000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000055555555555000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000055555555555550003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000005555555555555000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000005555555555555550030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000555555555555555003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000055555555555555500300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000055555555555555555030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000555555555555555003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000055555555555555500300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000005555555555555550030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000055555555555550003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000005555555555555000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000055555555555000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000055555550000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000005000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000100000010030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000001000000100000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000100000011000001103000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000110000011000001000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000011100001110000110300010000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000011100001110000110000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000001111000111100011130001000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030011000001111000111100011100000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000001100000111110011111001111000110000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003001111000111110011111001110000100000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000111100011111101111110111110011100000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300111110011111101111110111100011000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000011111001111111111111111111001110000100000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030011111111111111111111111111001100000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000001111111111111111111111111110111100010000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000011103001111111111111111111111111100111000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000001000000111111111111111111111111111111111001100000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000001111100111111111111111111111111111011110001030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000111100011111111111111111111111111111111100110000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000111111111111111111111111111111111111111000103000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000011111111111111111111111111111111111111111011100000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000011111111111111111111111111111111111111110011300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000001111111111111111111111111111111111111111101111000100000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000001111111111111111111111111111111111111111001130000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000111111111111111111111111111111111111111111111100010000000000000000000003000000000000000000000000000000000000000300000000000000000000000000001111000111111111111111111111111111111111111111110111000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000011111111111111111111111111111111111111111111111001100000000000000000000300000000000000000000000000000000000000030000000000000000000000000000111111111111111111111111111111111111111111111111111110001000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003011111001111111111111111111111111111111111111111111111100110000000000000000000030000000000000000000000000000000000000003000000000000000000000000000011111111111111111111111111111111111111111111111111111000100000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000301111111111111111111111111111111111111111111111111111111011100000000000000000003000000000000000000000000000000000000000300000000000000000000000000001111111111111111111111111111111111111111111111111111110011000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030111111111111111111111111111111111111111111111111111111101110000000000000000000300000000000000000000000000000000000000030000000000000000000000000000111111111111111111111111111111111111111111111111111111001100000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003011111111111111111111111111111111111111111111111111111111111100010000000000000030000000000000000000000000000000000000003000000000000000000000000000011111111111111111111111111111111111111111111111111111110111000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000301111111111111111111111111111111111111111111111111111111111110001000000000000003000000000000000000000000000000000000000300000000000000000000011111111111111111111111111111111111111111111111111111111111111011100000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000001000030111111111111111111111111111111111111111111111111111111111111100110000000000000300000000000000000000000000000000000000030000000000000000000001111111111111111111111111111111111111111111111111111111111111111111000100000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000111111011111111111111111111111111111111111111111111111111111111111110011000000000000030000000000000000000000000000000000000003000000000000000000000111111111111111111111111111111111111111111111111111111111111111111100011000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000011111111111111111111111111111111111111111111111111111111111111111111101110000000000003000000000000000000000000000000000000000300000000000000000000011111111111111111111111111111111111111111111111111111111111111111111001100000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000001111111111111111111111111111111111111111111111111111111111111111111111111100010000000300000000000000000000000000000000000000030000000000000000000001111111111111111111111111111111111111111111111111111111111111111111100111000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000111111111111111111111111111111111111111111111111111111111111111111111111110001000000030000000000000000000000000000000000000003000000000000001111100111111111111111111111111111111111111111111111111111111111111111111111011100000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000011111111111111111111111111111111111111111111111111111111111111111111111111100110000003000000000000000000000000000000000000000300000000000000111111111111111111111111111111111111111111111111111111111111111111111111111101111000100000000000000000000300000000000000000000000000000000000222200222200000000000000000000000011111101111111111111111111111111111111111111111111111111111111111111111111111111110011000000300000000000000000000000000000000000000030000000000000011111111111111111111111111111111111111111111111111111111111111111111111111111111100010000000000000000000030000000000000000000000000000000000022220022220000000000000000000000001111111111111111111111111111111111111111111111111111111111111111111111111111111111101110000030000000000000000000000000000000000000003000000000000001111111111111111111111111111111111111111111111111111111111111111111111111111111111001100000000000000000003000000000000000000000000000000000012222002222000000000000000000000000111111111111111111111111111111111111111111111111111111111111111111111111111111111110111000003000000000000000000000000000000000000000300000000000000111111111111111111111111111111111111111111111111111111111111111111111111111111111100110000000000000000000300000000000000000000000000000000001222200222233333333333333333333333311111111111111111111111111111111111111111111111111111111111111111111111111111111111111113331333333333333333333333333333333333333333333333333133333311111111111111111111111111111111111111111111111111111111111111111111111111111111111311133333333333333333333333333333333333333333333333333333122220022220000000000000000000000001111111111111111111111111111111111111111111111111111111111111111111111111111111111111111000130000000000000000000000000000000000000003000000011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101110000000000000000003000000000000000000000000000000000012222002222000000000000000001100000111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110011000000000000000000000000000000000000000300000001111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111100010000000000000300000000000000000000000000000000001222200222200000000000000000111111011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111001100000000000000000000000000000000000000030000000111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110001000000000000030000000000000000000000000000000000122220022220000000000000000011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110111000000000000000000000000000000000000003000000011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111100110000000000003000000000000000000000000000111100012222002222000000000000000001111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110001000000000000000000000000000000000311000001111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011100000000000300000000000000000000000000011111111222200222200000000001000000111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111000110000000000000000000000000000000031111100111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101110000100000030000000000000000000000000001111111122220022220000000000111000011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110011000000000000000000000000000000003111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111100010000003000000000000000000001000000111111112222002222000000000011111101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111001110000100000000000000000000000000311111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110001100000300000000000000000000111000011111111222200222200010000001111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110111000010000000000000000000011000031111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111100111000010000000000000000000011111001111111122220022220001100000111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110001100000100000010000001111003111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011100001000000100000010000001111111111111112222002222000111100011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111100111000011000001100000111111311111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101111000110000011000001110000111111111111111222200222200011111101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110011110001110000111000011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110011100001110000111100011111111111111122220022220001111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101111000111100011111001111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111001111000111100011111101111111111111112222002222100111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110011111001111110111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110111110011111001111111111111111111111222200222211011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101111110111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101111110111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222220022222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222002222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222279632c062829030000000049454e44ae426082
messages/5/a3/5a3f1c70-0000-4000-8000-000000000017/1/sample-light-damaged.png	messages/5/a3/5a3f1c70-0000-4000-8000-000000000017/1	sample-light-damaged.png	32298	9c668b05008a9dce8a2ef6cb559263b4a993cf4eeba6de241cb37de5185a7202	2026-10-22 00:00:00+00	2026-10-22 00:00:00+00	\N	\\x89504e470d0a1a0a0000000d4948445200000140000000c804030000006b2a259800000012504c5445d6e9f5e8dfc41f3b57b4cfe2c0392b2c6e496a707f5700007dd349444154780101c87d378200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222220022222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222002222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000000000444444444444444000000000044444000000000000000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444444440000000000000000000000000300000000000000444444444444444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000000000044444444444444400000000004444400000000000000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444444444000000000000000000000000030000000000000044444444444444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000000004444444444444440000000000444440000000000000004444400000444444444444444444440000000000444440000000000000000000000000444444444444444444444444400000000000000000000000003000000000000004444444444444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000000000444444444444444000000000044444000000000000000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444444440000000000000000000000000300000000000000444444444444444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000000000044444444444444400000000004444400000000000000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444444444000000000000000000000000030000000000000044444444444444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444444444400000444444444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000444440000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444444440000044444444440000044444000000000000000444440000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000044444000000000000000444440000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444444444000004444444444000004444400000000000000044444000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000004444400000000000000044444000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444444444400000444444444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000444440000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444444440000044444444440000044444000000000000000444440000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000044444000000000000000444440000030000000000000000000000000000000000022220022223333333333333333333333333333333333334444433333333333333333333333334444433333333333333344444333334444433333444443333344444333334444433333333333333344444333334444433333333333333333333333334444433333333333333333333333333333333333333333333333333333333333333333333333333344444333333333333333333333333333333333333333332222002222000000000000000000000000000000000000444440000000000000000000000000444440000030000000004444400000444440000044444000004444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000000000000044444000003000000000444440000044444000004444400000444440000044444000000000000000444440000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000000000000000444440000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000000000000004444400000300000000044444000004444400000444440000044444000004444400000000000000044444000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000000000000044444000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000000000000000444440000030000000004444400000444440000044444000004444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444444444444444444444440000044444000004444400000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444000000000000000000000000000000300000000000000000000000044444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444444444444444444444444000004444400000444440000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444400000000000000000000000000000030000000000000000000000004444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000444444444444444444444444400000444440000044444000004444400000444444444444444444440000000000444440000000000000000000000000444444444444444444440000000000000000000000000000003000000000000000000000000444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444444444444444444444440000044444000004444400000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444000000000000000000000000000000300000000000000000000000044444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444444444444444444444444000004444400000444440000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444400000000000000000000000000000030000000000000000000000004444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000044444000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000004444400000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000444440000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000044444000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000004444400000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000044444000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000004444400000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000444440000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000044444000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000004444400000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444444444444444444444440000044444444444444444444444440000000000000000000000000300000000044444444444444444444444440000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444444444444444444444444000004444444444444444444444444000000000000000000000000030000000004444444444444444444444444000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444444444444444444444444400000444444444444444444444444400000000000000000000000003000000000444444444444444444444444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444444444444444444444440000044444444444444444444444440000000000000000000000000300000000044444444444444444444444440000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444444444444444444444444000004444444444444444444444444000000000000000000000000030000000004444444444444444444444444000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000500000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000055555550000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000555555555550000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000555555555555500000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000055555555555550000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000055555555555555500000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000005555555555555550000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000555555555555555000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000555555555555555550000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000005555555555555550000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000555555555555555000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000055555555555555500000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000555555555555500000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000055555555555550000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000555555555550000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000555555500000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000050000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222233333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333322220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000010000001000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000010000001000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000001000100100013010000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000001001000100100010010000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000012222002222000000000000000000000100110010011001301000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000001000100110010011001001000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000001222200222200000000000000000100010011001101100110110010010000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000110011011001101100100100000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000100122220022220000000000000000011001101110110111011011001001000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000010011001101110110111011011001000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000100010012222002222000000000000001001100110111011111101111110110100000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000310001001110111111011111101101100100100030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000011001101222200222200000000000000100111011111111111111111111011011000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000031000110111011111111111111111111010010003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000001100111122220022220000000000100011011101111111111111111111101101100100000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003110011111111111111111111111111101101100300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000100111011112222002222000000000011001111111111111111111111111111111111010010000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000010311101111111111111111111111111110110110010000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000100011011111111222200222200000000001100111111111111111111111111111111111101101000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000001031110111111111111111111111111111111111001000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000010001111111111122220022220000000100111011111111111111111111111111111111110110110000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000001000113111111111111111111111111111111111111110100100000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000001100111111111112222002222000000011011111111111111111111111111111111111111111011000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000110011111111111111111111111111111111111111111011010000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000111011111111111222200222200010001101111111111111111111111111111111111111111111100100000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000011001111111111111111111111111111111111111111101101100000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000010011111111111111122220022220001100111111111111111111111111111111111111111111111111010010000000000000000300000000000000000000000000000000000000030000000000000000000000000000001001110111111111111111111111111111111111111111111110110000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000001101111111111111112222002222000111011111111111111111111111111111111111111111111111101101000000000000000030000000000000000000000000000000000000003000000000000000000000000000000100111111111111111111111111111111111111111111111111111001000000000000000000000000000000003000000000000000000000000000000000000000300000000000001000111111111111111111222200222200011101111111111111111111111111111111111111111111111110110100000000000000003000000000000000000000000000000000000000300000000000000000000000000000011011111111111111111111111111111111111111111111111111110100100000000000000000000000000000300000000000000000000000000000000000000030000000000000110011111111111111111122220022221001111111111111111111111111111111111111111111111111111111011000000000000000300000000000000000000000000000000000000030000000000000000000000000010001111111111111111111111111111111111111111111111111111111011010000000000000000000000000000030000000000000000000000000000000000000003000000000000011101111111111111111112222002222110111111111111111111111111111111111111111111111111111111111100100000000000030000000000000000000000000000000000000003000000000000000000000000001100111111111111111111111111111111111111111111111111111111101101000000000000000000000000000003000000000000000000000000000000000000000300000000001001111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111110010000000000003000000000000000000000000000000000000000300000000000000000000000000111011111111111111111111111111111111111111111111111111111111110110000000000000000000000000000300000000000000000000000000000000000000030000000000110111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111101001000000000300000000000000000000000000000000000000030000000000000000000000010011111111111111111111111111111111111111111111111111111111111111111000000000000000000000000000030000000000000000000000000000000000000003000000100011011111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111110110100000000030000000000000000000000000000000000000003000000000000000000000001001111111111111111111111111111111111111111111111111111111111111111100100100000000000000000000003000000000000000000000000000000000000000300000010001111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111011011000000003000000000000000000000000000000000000000300000000000000000001000110111111111111111111111111111111111111111111111111111111111111111111010010000000000000000000000300000000000000000000000000000000000000030000001100111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111101100000000300000000000000000000000000000000000000030000000000000000000110011111111111111111111111111111111111111111111111111111111111111111111101101000000000000000000000030000000000000000000000000000000000000003000100111011111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111110010000030000000000000000000000000000000000000003000000000000000000011101111111111111111111111111111111111111111111111111111111111111111111110110110000000000000000000003000000000000000000000000000000000000000300011011111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111101001003000000000000000000000000000000000000000300000000000000001001110111111111111111111111111111111111111111111111111111111111111111111111111111000000000000000000000300000000000000000000000000000000000000030001101111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111110110100300000000000000000000000000000000000000030000000000000000100111111111111111111111111111111111111111111111111111111111111111111111111111111100100000000000000000030000000000000000000000000000000000000001000111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111011010030000000000000000000000000000000000000003000000000000000011011111111111111111111111111111111111111111111111111111111111111111111111111111111010010000000000000003000000000000000000000000000000000000000110011111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111101103000000000000000000000000000000000000000300000000000010001111111111111111111111111111111111111111111111111111111111111111111111111111111111101101000000000000000300000000000000000000000000000000000000011101111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111113313333333333333333333333333333333333333333333333333331133111111111111111111111111111111111111111111111111111111111111111111111111111111111113113133333333333333333333333333333333333333333333333333331331111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111031000000000000000000000000000000000000003000000000000111011111111111111111111111111111111111111111111111111111111111111111111111111111111111111011000000000000003000000000000000000000000000000000000110111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111113100100000000000000000000000000000000000300000000010011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111100100000000000300000000000000000000000000000000100011011111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111311010000000000000000000000000000000000030000000001101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110010010000000030000000000000000000000000000000011001111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111131101100000000000000000000000000000000003000001000110111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101001000000003000000000000000000000000000000001100111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111110110010000000000000000000000000000000300000110011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110110100000000300000000000000000000000000000100111011111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111001001000000000000000000000000000030000011101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011000000030000000000000000000000000100010011111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111110100100000000000000000000000000003001001110111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101100100103000000000000000000000000010001101111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111011010000000000000000000000000001300110111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110010010300000000000000000000000001100111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111101101100100100000000000000000000110011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101101030000000000000000001000100111011111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110010010000001000000100010011001111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110110113010010000001000000100011011101111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101101100100100010010001001110111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011301001000100100010011001101111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110110110010011001001100110111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110110110010011001001100111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011011001101100110111011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011011001101100110111011111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110110111011011101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101101110110111011011111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011111101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011111101111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222002222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222220022222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222bc8c7efa04c7df5d0000000049454e44ae426082
messages/5/a3/5a3f1c70-0000-4000-8000-000000000028/1/sample-firing-area.png	messages/5/a3/5a3f1c70-0000-4000-8000-000000000028/1	sample-firing-area.png	32298	95ecaa7b565844ec0b3e1d3db82a8ff15f55c68b5e3ab214b91f4f70aef16f8d	2026-10-22 00:00:00+00	2026-10-22 00:00:00+00	\N	\\x89504e470d0a1a0a0000000d4948445200000140000000c804030000006b2a259800000012504c5445d6e9f5e8dfc41f3b57b4cfe2c0392b2c6e496a707f5700007dd349444154780101c87d378200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222220022222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222002222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000000000444444444444444000000000044444000000000000000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444444440000000000000000000000000300000000000000444444444444444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000000000044444444444444400000000004444400000000000000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444444444000000000000000000000000030000000000000044444444444444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000000004444444444444440000000000444440000000000000004444400000444444444444444444440000000000444440000000000000000000000000444444444444444444444444400000000000000000000000003000000000000004444444444444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000000000444444444444444000000000044444000000000000000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444444440000000000000000000000000300000000000000444444444444444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000000000044444444444444400000000004444400000000000000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444444444000000000000000000000000030000000000000044444444444444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444444444400000444444444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000444440000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444444440000044444444440000044444000000000000000444440000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000044444000000000000000444440000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444444444000004444444444000004444400000000000000044444000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000004444400000000000000044444000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444444444400000444444444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000444440000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444444440000044444444440000044444000000000000000444440000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000044444000000000000000444440000030000000000000000000000000000000000022220022223333333333333333333333333333333333334444433333333333333333333333334444433333333333333344444333334444433333444443333344444333334444433333333333333344444333334444433333333333333333333333334444433333333333333333333333333333333333333333333333333333333333333333333333333344444333333333333333333333333333333333333333332222002222000000000000000000000000000000000000444440000000000000000000000000444440000030000000004444400000444440000044444000004444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000000000000044444000003000000000444440000044444000004444400000444440000044444000000000000000444440000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000000000000000444440000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000000000000004444400000300000000044444000004444400000444440000044444000004444400000000000000044444000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000000000000044444000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000000000000000444440000030000000004444400000444440000044444000004444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444444444444444444444440000044444000004444400000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444000000000000000000000000000000300000000000000000004444444444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444444444444444444444444000004444400000444440000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444400000000000000000000000000000030000000000000000000444444444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000444444444444444444444444400000444440000044444000004444400000444444444444444444440000000000444440000000000000000000000000444444444444444444440000000000000000000000000000003000000000000000000044444444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444444444444444444444440000044444000004444400000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444000000000000000000000000000000300000000000000000004444444444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444444444444444444444444000004444400000444440000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444400000000000000000000000000000030000000000000000000444444444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000000000000000444440000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000000000000044444000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000000000000000444440000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000004444400000000000000044444000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000444440000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000044444000000000000000444440000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000004444400000000000000044444000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000444440000000000000004444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444444444444444444444440000044444444444444444444444440000000000000000000000000300000000000000444444444444444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444444444444444444444444000004444444444444444444444444000000000000000000000000030000000000000044444444444444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444444444444444444444444400000444444444444444444444444400000000000000000000000003000000000000004444444444444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444444444444444444444440000044444444444444444444444440000000000000000000000000300000000000000444444444444444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444444444444444444444444000004444444444444444444444444000000000000000000000000030000000000000044444444444444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000050000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000005555555000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000055555555555000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300055555555555550000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030005555555555555000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003005555555555555550000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300555555555555555000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030055555555555555500000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003055555555555555555000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300555555555555555000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030055555555555555500000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003005555555555555550000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300055555555555550000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030005555555555555000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000055555555555000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000055555550000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000005000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222233333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333322220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000001000000100000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000010000001000000000000000000000000000022220022220001000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000001000010100001010000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000001000010100001010000000000000000000002222002222010100000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000100101010010101000010100000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000010100101010010101000000000000000000000222200222201010000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000001010010101101010110101010000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000100001011010101101010100001010000000000000022220022220101001010100000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000010000101101011110101111010101000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000010010101101011110101111010101000000000000002222002222111110101010000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000001001011110111111011111101010100000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000101101011110111111011111101010100000000000000222200222211111010101000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300010100101111111111111111111111111010131000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000010110111111111111111111110101010000000000000022220022221111101010100000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030001011011111111111111111111111111101013100000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000100101111011111111111111111111111111101010000000002222002222111111111111010000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000101101111111111111111111111111110101310000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000010010111111111111111111111111111111110101010000000222200222211111111111101010100000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000001301011111111111111111111111111111111111111000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000101101111111111111111111111111111111111010101000000022220022221111111111110101010000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000130101111111111111111111111111111111111111110100000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000010110111111111111111111111111111111111111010100000002222002222111111111111010101000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000001011011111111111111111111111111111111111111111010101000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000001011011111111111111111111111111111111111111111000000222200222211111111111111111100000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000101101111111111111111111111111111111111111111101010100000000000000000000000000000003000000000000000000000000000000000000000300000000000000000010010111111111111111111111111111111111111111111111101010022220022221111111111111111111010000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000011111111111111111111111111111111111111111111111101010000000000000000000000000000000300000000000000000000000000000000000000030000000000000000001001011111111111111111111111111111111111111111111110101012222002222111111111111111111101010000000000000300000000000000000000000000000000000000030000000000000000000000000000000100101111111111111111111111111111111111111111111111111111000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000110111111111111111111111111111111111111111111111111010101222200222211111111111111111110101010000000000030000000000000000000000000000000000000003000000000000000000000000000000010010111111111111111111111111111111111111111111111111111110100000000000000000000000000003000000000000000000000000000000000000000300000000000000001011011111111111111111111111111111111111111111111111111010122220022221111111111111111111010101000000000003000000000000000000000000000000000000000300000000000000000000000000000001101111111111111111111111111111111111111111111111111111111010100000000000000000000000000300000000000000000000000000000000000000030000000000000000101101111111111111111111111111111111111111111111111111111112222002222111111111111111111111110100000000000300000000000000000000000000000000000000030000000000000000000000000000010110111111111111111111111111111111111111111111111111111111101010100000000000000000000000030000000000000000000000000000000000000003000000000000001011111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111110000000000030000000000000000000000000000000000000003000000000000000000000000000001011111111111111111111111111111111111111111111111111111111110101010000000000000000000000003000000000000000000000000000000000000000300000000000100101111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111101000000003000000000000000000000000000000000000000300000000000000000000000010000111111111111111111111111111111111111111111111111111111111111111101000000000000000000000000300000000000000000000000000000000000000030000000000010010111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111110101010000300000000000000000000000000000000000000030000000000000000000000001001011111111111111111111111111111111111111111111111111111111111111111100000000000000000000000030000000000000000000000000000000000000003000000000101101111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111010101000030000000000000000000000000000000000000003000000000000000000000000100101111111111111111111111111111111111111111111111111111111111111111111010100000000000000000003000000000000000000000000000000000000000300000000010110111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111010100003000000000000000000000000000000000000000300000000000000000000001011011111111111111111111111111111111111111111111111111111111111111111111101010100000000000000000300000000000000000000000000000000000000030000000101011111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111110000300000000000000000000000000000000000000030000000000000000000000101101111111111111111111111111111111111111111111111111111111111111111111110101010000000000000000030000000000000000000000000000000000000003000010010111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111101030000000000000000000000000000000000000003000000000000000000000011111111111111111111111111111111111111111111111111111111111111111111111111011101000000000000000003000000000000000000000000000000000000000300001001011111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111110101000000000000000000000000000000000000000300000000000000000100101111111111111111111111111111111111111111111111111111111111111111111111111111111100000000000000000300000000000000000000000000000000000000030000110111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111010101000000000000000000000000000000000000030000000000000000010010111111111111111111111111111111111111111111111111111111111111111111111111111111111010000000000000030000000000000000000000000000000000000003001011011111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111101010100000000000000000000000000000000000003000000000000000001101111111111111111111111111111111111111111111111111111111111111111111111111111111111101010000000000003000000000000000000000000000000000000000300101111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111313333333333333333333333333333333333333333333333333333313113111111111111111111111111111111111111111111111111111111111111111111111111111111111113131313333333333333333333333333333333333333333333333333331311111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111000000000000000000000000000000000000030000000000000001011111111111111111111111111111111111111111111111111111111111111111111111111111111111111010101000000000030000000000000000000000000000000000000103101111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111110100000000000000000000000000000000003000000000010010111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110100000000003000000000000000000000000000000000000010310111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111010101000000000000000000000000000000300000000001001011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111000000000300000000000000000000000000000000000101131111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111101010100000000000000000000000000000030000000000100101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101010000030000000000000000000000000000000000010113111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111101010000000000000000000000000000003000000001011011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110101010003000000000000000000000000000000100001111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111000000000000000000000000000000300000000101101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111010101000300000000000000000000000000000010010111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111110101010000000000000000000000030001001011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111010100030000000000000000000000000000101001011111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111010101000000000000000000000003000100101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111013101000000000000000000000000010110111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111101010100000000000000000000100301011011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101310100000000000000000000100101011011111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111010010101000000100000010030101101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110131010000001000000100001010010111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111101010100001010000101001011110111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111013101001010100001010000101101011111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111110101010010101001010110101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110101010010101001010110111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111010111101010110101011011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111010101101010110101111011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111110101111010111101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101011110101111010111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111011111101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011111101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222002222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222220022222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222c36984ab58fa2b640000000049454e44ae426082
messages/5/a3/5a3f1c70-0000-4000-8000-000000000033/1/sample-annex-overview.png	messages/5/a3/5a3f1c70-0000-4000-8000-000000000033/1	sample-annex-overview.png	32298	643c6280f2c9703fd37a1d7d6c923f2108b1dca04e6b0d2de26edd0421e92ff3	2026-10-22 00:00:00+00	2026-10-22 00:00:00+00	\N	\\x89504e470d0a1a0a0000000d4948445200000140000000c804030000006b2a259800000012504c5445d6e9f5e8dfc41f3b57b4cfe2c0392b2c6e496a707f5700007dd349444154780101c87d378200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222220022222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222002222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000000000444444444444444000000000044444000000000000000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444444440000000000000000000000000300000000000000000000000044444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000000000044444444444444400000000004444400000000000000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444444444000000000000000000000000030000000000000000000000004444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000000004444444444444440000000000444440000000000000004444400000444444444444444444440000000000444440000000000000000000000000444444444444444444444444400000000000000000000000003000000000000000000000000444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000000000444444444444444000000000044444000000000000000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444444440000000000000000000000000300000000000000000000000044444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000000000044444444444444400000000004444400000000000000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444444444000000000000000000000000030000000000000000000000004444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444444444400000444444444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000044444444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444444440000044444444440000044444000000000000000444440000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000004444444444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444444444000004444444444000004444400000000000000044444000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000444444444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444444444400000444444444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000044444444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444444440000044444444440000044444000000000000000444440000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000004444444444000000000030000000000000000000000000000000000022220022223333333333333333333333333333333333334444433333333333333333333333334444433333333333333344444333334444433333444443333344444333334444433333333333333344444333334444433333333333333333333333334444433333333333333333333333333333333333333333333333333333333333344444333334444433333333333333333333333333333333333333333333332222002222000000000000000000000000000000000000444440000000000000000000000000444440000030000000004444400000444440000044444000004444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000004444400000444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000000000000044444000003000000000444440000044444000004444400000444440000044444000000000000000444440000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000444440000044444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000000000000004444400000300000000044444000004444400000444440000044444000004444400000000000000044444000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000044444000004444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000000000000000444440000030000000004444400000444440000044444000004444400000444440000000000000004444400000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000004444400000444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444444444444444444444440000044444000004444400000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444000000000000000000000000000000300000000044444000000000044444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444444444444444444444444000004444400000444440000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444400000000000000000000000000000030000000004444400000000004444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000444444444444444444444444400000444440000044444000004444400000444444444444444444440000000000444440000000000000000000000000444444444444444444440000000000000000000000000000003000000000444440000000000444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444444444444444444444440000044444000004444400000444440000044444444444444444444000000000044444000000000000000000000000044444444444444444444000000000000000000000000000000300000000044444000000000044444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444444444444444444444444000004444400000444440000044444000004444444444444444444400000000004444400000000000000000000000004444444444444444444400000000000000000000000000000030000000004444400000000004444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000444444444444444444444444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000044444444444444444444444440000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000004444444444444444444444444000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000444444444444444444444444400000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000044444444444444444444444440000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000000004444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000000000444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000044444000000000000000444440000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444000000000000000000000000044444000003000000000000000000000000000000000000000300000000000000000000000044444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000004444400000000000000044444000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444400000000000000000000000004444400000300000000000000000000000000000000000000030000000000000000000000004444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000444440000000000000004444400000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444440000000000000000000000000444440000030000000000000000000000000000000000000003000000000000000000000000444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444444444444444444444440000044444444444444444444444440000000000000000000000000300000000000000000000000044444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444444444444444444444444000004444444444444444444444444000000000000000000000000030000000000000000000000004444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300004444444444444440000000000444440000030000000004444400000444440000000000000004444400000444440000000000000000000000000444444444444444444444444400000444444444444444444444444400000000000000000000000003000000000000000000000000444440000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000444444444444444000000000044444000003000000000444440000044444000000000000000444440000044444000000000000000000000000044444444444444444444444440000044444444444444444444444440000000000000000000000000300000000000000000000000044444000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000044444444444444400000000004444400000300000000044444000004444400000000000000044444000004444400000000000000000000000004444444444444444444444444000004444444444444444444444444000000000000000000000000030000000000000000000000004444400000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000005000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000555555500000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000005555555555500000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000005555555555555000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000555555555555500000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000555555555555555000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000055555555555555500000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000005555555555555550000000000003000000000000000000000000000000000002222002222333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333335555555555555555533333333333333333333333333333333333333333333333222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000055555555555555500000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000005555555555555550000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000555555555555555000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000005555555555555000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000555555555555500000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000005555555555500000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000005555555000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000500000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222233333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333333322220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000001000000100000010000000300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300001000000100000000000000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000101000010100001000000030000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000101000010100001000000000000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000010101001010100101000003000000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000000000101000010101001010100100000000000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000010101001010101101010110100001300000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000000000010101011010101101010010100000000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300000000001010101111010111101011010100130000000000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000000000001010101111010111101011010000100000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030000000000101010111111011111101111010013100000000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000000010101101010111111011111101101010010000000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000000000010101011111111111111111111011310000000000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000000101010111111111111111111110111101001000000000000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300010101011111111111111111111111111101131010010000000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000000000010101011111111111111111111111111101101000000000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000030001010101111111111111111111111111111111101001000000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000000000001010101111111111111111111111111110110100000000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000000003000101010111111111111111111111111111111110100100000000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000000000001111111111111111111111111111111111111110100100000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000000300010111111111111111111111111111111111111110110100000000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000001010111111111111111111111111111111111111111010010000003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000000031011111111111111111111111111111111111111111011010000000000000000000000000000000300000000000000000000000000000000000000030000000000000000000000010101011111111111111111111111111111111111111111011000000300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000001013101111111111111111111111111111111111111111111111010000000000000000000000000000030000000000000000000000000000000000000003000000000000000000000001010101111111111111111111111111111111111111111101101000030000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000000101310111111111111111111111111111111111111111111111101001000000000000000000000000003000000000000000000000000000000000000000300000000000000000000000101111111111111111111111111111111111111111111110110100003000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000000010131011111111111111111111111111111111111111111111110100100000000000000000000000000300000000000000000000000000000000000000030000000000000000000000111111111111111111111111111111111111111111111111111110100300000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000000000001011111111111111111111111111111111111111111111111111110110100000000000000000000000030000000000000000000000000000000000000003000000000000000000001011111111111111111111111111111111111111111111111111111010010000000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000000000001111111111111111111111111111111111111111111111111111111011010000000000000000000000003000000000000000000000000000000000000000300000000000000001010101111111111111111111111111111111111111111111111111111111001000000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000000010111111111111111111111111111111111111111111111111111111111111000000000000000000000000300000000000000000000000000000000000000030000000000000000101010111111111111111111111111111111111111111111111111111111101101000000000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000010101011111111111111111111111111111111111111111111111111111111111101000000000000000000000030000000000000000000000000000000000000003000000000000000010101111111111111111111111111111111111111111111111111111111110110100000000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000000001010101111111111111111111111111111111111111111111111111111111111110100100000000000000000003000000000000000000000000000000000000000300000000000000001111111111111111111111111111111111111111111111111111111111111111010000000000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000000000000101011111111111111111111111111111111111111111111111111111111111111110010100000000000000000300000000000000000000000000000000000000030000000000000001111111111111111111111111111111111111111111111111111111111111111111010010000000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000000000010111111111111111111111111111111111111111111111111111111111111111111011010000000000000000030000000000000000000000000000000000000003000000000101010111111111111111111111111111111111111111111111111111111111111111111101001000000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000000001011111111111111111111111111111111111111111111111111111111111111111111101101000000000000000003000000000000000000000000000000000000000300000000010101011111111111111111111111111111111111111111111111111111111111111111111101101000000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000001010101111111111111111111111111111111111111111111111111111111111111111111111111101000000000000000300000000000000000000000000000000000000030000000001010101111111111111111111111111111111111111111111111111111111111111111111110110100000000000000000000000000222200222200000000000000000000000000000000000030000000000000000000000101010111111111111111111111111111111111111111111111111111111111111111111111111110100100000000000030000000000000000000000000000000000000003000000000111111111111111111111111111111111111111111111111111111111111111111111111111011010000000000000000000000000022220022220000000000000000000000000000000000003000000000000000000000010101111111111111111111111111111111111111111111111111111111111111111111111111111110010000000000003000000000000000000000000000000000000000300000000111111111111111111111111111111111111111111111111111111111111111111111111111111111010000000000000000000000002222002222000000000000000000000000000000000000300000000000000000000001011111111111111111111111111111111111111111111111111111111111111111111111111111111011010000000000300000000000000000000000000000000000000030000001011111111111111111111111111111111111111111111111111111111111111111111111111111111101001000000000000000000000222200222210010000000000000000000000000000000030000000000000000000001111111111111111111111111111111111111111111111111111111111111111111111111111111111101101000000000030000000000000000000000000000000000000003001010101111111111111111111111111111111111111111111111111111111111111111111111111111111111100100000000000000000000022220022221331333333333333333333333333333333333333333333333333333313111111111111111111111111111111111111111111111111111111111111111111111111111111111111111133333333333333333333333333333333333333333333333333333131313111111111111111111111111111111111111111111111111111111111111111111111111111111111113113133333333333333333332222002222100100000000000000000000000000000000300000000000000010101011111111111111111111111111111111111111111111111111111111111111111111111111111111111111110100100000300000000000000000000000000000000000000030010101111111111111111111111111111111111111111111111111111111111111111111111111111111111111011010000000000000000000222200222210110100000000000000000000000000000030000000000000001010101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111010010000030000000000000000000000000000000000000003001111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111000000000000000000022220022221011010000000000000000000000000000003000000000000000101011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011000003000000000000000000000000000000000000000101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101001000000000000002222002222111111010010000000000000000000000000300000000000000011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101101000300000000000000000000000000000000000101010111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110100100000000000000222200222211111101001000000000000000000000000030000000000101011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110110100030000000000000000000000000000000000010101011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110110100000000000022220022221111110100101000000000000000000000003000000001010101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110103100000000000000000000000000000000001010101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011010000000000002222002222111111110110100000000000000000000000300000000101010111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111010310000000000000000000000000000000000101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101101010010000000222200222211111111011010000100000000000000000030000000010101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011010000000000000000000000000101010111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101001000000022220022221111111111111010010000000000000000003010101011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101101000010000000000000000000010101011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110100101000002222002222111111111111101001010000100000010000301010101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110111101001000000100000010000001010101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110110100001222200222211111111111111101101010010100001010030101010111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110100101000010100001010101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011010100122220022221111111111111110111101001010100101013110101011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111110110101001010100101010111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111010112222002222111111111111111111110101101010110101311111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011110101101010110101011111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011222200222211111111111111111111110111101011110131111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111010111101011111101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111101122220022221111111111111111111111011111101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111011111101111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022221111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111112222002222111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111222200222211111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111122220022222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222002222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222200222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222220022222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222222a8b26a9f5552238b0000000049454e44ae426082
\.


--
-- Data for Name: S124Dataset; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."S124Dataset" (id, uuid, mrn, message_id, status, current_version_id, geometry, "validFrom", "validTo", cancelled, created, updated) FROM stdin;
\.


--
-- Data for Name: S124DatasetVersion; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."S124DatasetVersion" (id, dataset_id, gml, "fileName", "datasetId", "datasetTitle", "discoveryMetadata", signature, "signingCertificates", languages, created) FROM stdin;
\.


--
-- Data for Name: S124SeriesConfig; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."S124SeriesConfig" (id, series_id, "exposeOverSecom", languages) FROM stdin;
\.


--
-- Data for Name: SafetyNetArea; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."SafetyNetArea" ("DTYPE", id, active, "messageAreaPrefix", name, repetition, "sortOrder", center, radius, navarea, "coastalArea", eastings, northings, "swCorner", "promulgationType_id") FROM stdin;
\.


--
-- Data for Name: SafetyNetMessagePromulgation; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."SafetyNetMessagePromulgation" (priority, "safetyNetId", text, id, area_id) FROM stdin;
\.


--
-- Data for Name: ScheduledMail; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."ScheduledMail" (id, attempts, contents, created, "lastError", "sendDate", sender, status, subject, "sentAgainAt", "sentAgainBy") FROM stdin;
\.


--
-- Data for Name: ScheduledMailRecipient; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."ScheduledMailRecipient" (id, address, "recipientType", mail_id) FROM stdin;
\.


--
-- Data for Name: ScriptResource; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."ScriptResource" (id, created, updated, version, content, path, type) FROM stdin;
1	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#macro formatLightCharacterPhase phase multiple=false >\n    <#assign flash=multiple?then('flashes', 'flash') >\n    <#switch phase>\n        <#case "F">fixed light<#break>\n        <#case "Fl">${flash}<#break>\n        <#case "FFl">fixed light with ${flash}<#break>\n        <#case "LFl">long ${flash}<#break>\n        <#case "Q">quick ${flash}<#break>\n        <#case "VQ">very quick ${flash}<#break>\n        <#case "IQ">interrupted quick ${flash}<#break>\n        <#case "IVQ">interrupted very quick ${flash}<#break>\n        <#case "UQ">ultra quick ${flash}<#break>\n        <#case "IUQ">interrupted ultra quick ${flash}<#break>\n        <#case "Iso">isophase light<#break>\n        <#case "Oc">${multiple?then('occultings', 'occulting')}<#break>\n        <#case "Al">alternating<#break>\n        <#case "Mo">morse code<#break>\n    </#switch>\n</#macro>\n\n\n<#macro formatLightCharacterColor col>\n    <#switch col>\n        <#case "W">white<#break>\n        <#case "G">green<#break>\n        <#case "R">red<#break>\n        <#case "Y">yellow<#break>\n        <#case "B">blue<#break>\n        <#case "Am">amber<#break>\n    </#switch>\n</#macro>\n\n\n<#macro formatlightGroup lightGroup>\n    <#assign multiple=lightGroup.grouped />\n\n    <#if lightGroup.phase == 'Mo'>\n        morse code ${lightGroup.morseCode}\n\n    <#elseif lightGroup.phase == 'Al'>\n        alternating\n        <#if lightGroup.groupSpec?has_content>\n            <#list lightGroup.groupSpec as blinks>\n                ${blinks} <#if blinks_has_next> + </#if>\n            </#list>\n        </#if>\n\n        <#if lightGroup.colors?has_content>\n            <#list lightGroup.colors as col>\n                <#if !col?is_first && col?is_last> and <#elseif !col?is_first>, </#if>\n                <@formatLightCharacterColor col=col/>\n            </#list>\n            flashes\n        </#if>\n\n    <#elseif lightGroup.phase == 'Oc'>\n\n        <#if lightGroup.colors?has_content>\n            <#list lightGroup.colors as col>\n                <#if !col?is_first && col?is_last> and <#elseif !col?is_first>, </#if>\n                <@formatLightCharacterColor col=col/>\n            </#list> light with\n        </#if>\n\n        occulting\n        <#if lightGroup.groupSpec?has_content>\n            <#list lightGroup.groupSpec as blinks>\n            ${blinks} <#if blinks_has_next> + </#if>\n            </#list>\n        </#if>\n\n    <#else>\n        <#if lightGroup.groupSpec?has_content>\n            <#list lightGroup.groupSpec as blinks>\n                ${blinks} <#if blinks_has_next> + </#if>\n            </#list>\n        </#if>\n\n        <#if lightGroup.colors?has_content>\n            <#list lightGroup.colors as col>\n                <#if !col?is_first && col?is_last> and <#elseif !col?is_first>, </#if>\n                <@formatLightCharacterColor col=col/>\n            </#list>\n        </#if>\n\n        <@formatLightCharacterPhase phase=lightGroup.phase multiple=multiple />\n    </#if>\n</#macro>\n\n\n<#macro formatlightCharacter lightModel>\n\n    <#if lightModel.lightGroups??>\n        <#list lightModel.lightGroups as lightGroup>\n            <@formatlightGroup lightGroup=lightGroup /><#if lightGroup_has_next> followed by </#if>\n        </#list>\n\n        <#if lightModel.period??>\n             <#if lightModel.period == 1>every second<#else>every ${lightModel.period?c} seconds</#if>\n        </#if>\n\n        <#if lightModel.elevation??>\n            , ${lightModel.elevation?c} <#if lightModel.elevation == 1>meter<#else>meters</#if>\n        </#if>\n\n        <#if lightModel.range??>\n            , ${lightModel.range?c} <#if lightModel.range == 1>nautical mile<#else>nautical miles</#if>\n        </#if>\n\n    </#if>\n\n</#macro>\n\n<@formatlightCharacter lightModel=lightModel/>\n	templates/aton/light-character.ftl	FM
2	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#macro formatTelephonyCode code separator="-">\n    <#assign result=""/>\n    <#list 0..<code?length as i>\n        <#if !(i?is_first)>\n            <#assign result=result + separator/>\n        </#if>\n        <#assign result=result + formatTelephonyCharacter(code?substring(i, i+1)?upper_case)/>\n    </#list>\n    ${result}\n</#macro>\n\n<#function formatTelephonyCharacter code>\n    <#assign result=""/>\n    <#switch code>\n        <#case "A"><#assign result="Alpha"/><#break>\n        <#case "B"><#assign result="Bravo"/><#break>\n        <#case "C"><#assign result="Charlie"/><#break>\n        <#case "D"><#assign result="Delta"/><#break>\n        <#case "E"><#assign result="Echo"/><#break>\n        <#case "F"><#assign result="Foxtrot"/><#break>\n        <#case "G"><#assign result="Golf"/><#break>\n        <#case "H"><#assign result="Hotel"/><#break>\n        <#case "I"><#assign result="India"/><#break>\n        <#case "J"><#assign result="Juliet"/><#break>\n        <#case "K"><#assign result="Kilo"/><#break>\n        <#case "L"><#assign result="Lima"/><#break>\n        <#case "M"><#assign result="Mike"/><#break>\n        <#case "N"><#assign result="November"/><#break>\n        <#case "O"><#assign result="Oscar"/><#break>\n        <#case "P"><#assign result="Papa"/><#break>\n        <#case "Q"><#assign result="Quebec"/><#break>\n        <#case "R"><#assign result="Romeo"/><#break>\n        <#case "S"><#assign result="Sierra"/><#break>\n        <#case "T"><#assign result="Tango"/><#break>\n        <#case "U"><#assign result="Uniform"/><#break>\n        <#case "V"><#assign result="Victor"/><#break>\n        <#case "W"><#assign result="Whiskey"/><#break>\n        <#case "X"><#assign result="Xray"/><#break>\n        <#case "Y"><#assign result="Yankee"/><#break>\n        <#case "Z"><#assign result="Zulu"/><#break>\n        <#case "1"><#assign result="One"/><#break>\n        <#case "2"><#assign result="Two"/><#break>\n        <#case "3"><#assign result="Three"/><#break>\n        <#case "4"><#assign result="Four"/><#break>\n        <#case "5"><#assign result="Five"/><#break>\n        <#case "6"><#assign result="Six"/><#break>\n        <#case "7"><#assign result="Seven"/><#break>\n        <#case "8"><#assign result="Eight"/><#break>\n        <#case "9"><#assign result="Nine"/><#break>\n        <#case "0"><#assign result="Zero"/><#break>\n    </#switch>\n    <#return result />\n</#function>\n\n<@formatTelephonyCode code=code!""/>\n	templates/aton/telephony-codes.ftl	FM
3	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#assign formatPos = "org.niord.core.script.directive.LatLonDirective"?new()>\n<#assign trailingDot = "org.niord.core.script.directive.TrailingDotDirective"?new()>\n\n<#if geometry?has_content>\n    <ul class="positions">\n        <#list geometry as feature>\n            <#if feature.coordinates?has_content>\n                <#list feature.coordinates as coord>\n                    <li>\n                        <@trailingDot>\n                            <@formatPos lat=coord.coordinates[1] lon=coord.coordinates[0] format=format/><#if coord.name?has_content>, ${coord.name}</#if>\n                        </@trailingDot>\n                    </li>\n                </#list>\n            </#if>\n        </#list>\n    </ul>\n</#if>\n	templates/geometry/bullet-list.ftl	FM
4	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#assign formatPos = "org.niord.core.script.directive.LatLonDirective"?new()>\n\n<#function nameColumn coordinates>\n    <#list coordinates as coord>\n        <#if coord.name?has_content>\n            <#return true/>\n        </#if>\n    </#list>\n    <#return false/>\n</#function>\n\n<#function coordinates geometry>\n    <#assign coords = []>\n    <#list geometry as feature>\n        <#list feature.coordinates as coord>\n            <#assign coords = coords + [coord]>\n        </#list>\n    </#list>\n    <#return coords/>\n</#function>\n\n\n<#if geometry?has_content>\n    <#assign coords = coordinates(geometry)>\n    <#if coords?has_content && coords?size % 2 == 0>\n\n        <#assign fromCoords = coords?chunk(coords?size / 2)[0]>\n        <#assign toCoords = coords?chunk(coords?size / 2)[1]>\n        <#assign fromName = nameColumn(fromCoords)>\n        <#assign toName = nameColumn(toCoords)>\n        <table class="position-table">\n            <#list fromCoords as fromCoord>\n                <tr>\n                    <!-- From position -->\n                    <td class="pos-index">${fromCoord.index})</td>\n                    <td class="pos-col"><@formatPos lat=fromCoord.coordinates[1] lon=fromCoord.coordinates[0] format=format /></td>\n                    <#if fromName>\n                        <td><#if fromCoord.name?has_content>${fromCoord.name}</#if></td>\n                    </#if>\n\n                    <!-- To position -->\n                    <#assign toCoord = toCoords[fromCoord?index]!>\n                    <td class="pos-index"><#if toCoord?has_content>${toCoord.index})</#if></td>\n                    <td class="pos-col"><#if toCoord?has_content><@formatPos lon=toCoord.coordinates[0] lat=toCoord.coordinates[1] format=format /></#if></td>\n                    <#if toName>\n                        <td><#if toCoord?has_content && toCoord.name?has_content>${toCoord.name}</#if></td>\n                    </#if>\n                </tr>\n            </#list>\n        </table>\n    </#if>\n</#if>\n	templates/geometry/from-to-table.ftl	FM
5	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#assign formatPos = "org.niord.core.script.directive.LatLonDirective"?new()>\n<#assign trailingDot = "org.niord.core.script.directive.TrailingDotDirective"?new()>\n\n<#function nameColumn geometry>\n    <#list geometry as feature>\n        <#if feature.hasCoordinateName>\n            <#return true/>\n        </#if>\n    </#list>\n    <#return false/>\n</#function>\n\n\n<#if geometry?has_content>\n    <#assign hasName = nameColumn(geometry)>\n    <table class="positions">\n        <#list geometry as feature>\n            <#if feature.name?has_content>\n                <tr><th colspan="${hasName?then(3,2)}">${feature.name}</th></tr>\n            </#if>\n            <#if feature.coordinates?has_content>\n                <#list feature.coordinates as coord>\n                    <tr>\n                        <td nowrap>${coord.index})</td>\n                        <td nowrap><@formatPos lat=coord.coordinates[1] lon=coord.coordinates[0] format=format/><#if !coord.name?has_content>.</#if></td>\n                        <#if hasName>\n                            <td><@trailingDot><#if coord.name?has_content>${coord.name}</#if></@trailingDot></td>\n                        </#if>\n                    </tr>\n                </#list>\n            </#if>\n        </#list>\n    </table>\n</#if>\n	templates/geometry/numbered-list.ftl	FM
6	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#assign formatPos = "org.niord.core.script.directive.LatLonDirective"?new()>\n\n<#function nameColumn geometry>\n    <#list geometry as feature>\n        <#if feature.hasCoordinateName>\n            <#return true/>\n        </#if>\n    </#list>\n    <#return false/>\n</#function>\n\n\n<#if geometry?has_content>\n    <#assign hasName = nameColumn(geometry)>\n    <table class="position-table">\n        <#list geometry as feature>\n            <#if feature.coordinates?has_content>\n                <#list feature.coordinates as coord>\n                    <tr>\n                        <td class="pos-index">${coord.index})</td>\n                        <td class="pos-col"><@formatPos lat=coord.coordinates[1] lon=coord.coordinates[0] format=format /></td>\n                        <#if hasName>\n                            <td><#if coord.name?has_content>${coord.name}</#if></td>\n                        </#if>\n                    </tr>\n                </#list>\n            </#if>\n        </#list>\n    </table>\n</#if>\n	templates/geometry/table.ftl	FM
7	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#assign formatPos = "org.niord.core.script.directive.LatLonDirective"?new()>\n\n<#if geometry?has_content>\n    <#list geometry as feature>\n        <#if feature.coordinates?has_content>\n            <#list feature.coordinates as coord>\n                <@formatPos lat=coord.coordinates[1] lon=coord.coordinates[0] format=format /><#if coord?has_next>,&nbsp;</#if>\n            </#list>\n        </#if>\n        <#if feature?has_next>,&nbsp;</#if>\n    </#list>\n</#if>\n	templates/geometry/text.ftl	FM
8	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n\n<#include "../messages/message-support.ftl"/>\n\n<html>\n<head>\n    <link rel="stylesheet" type="text/css" href="/css/templates/mail.css">\n\n    <style>\n        table.nw-table td {\n            vertical-align: top;\n            border-bottom: 1px solid #ddd;\n            padding: 2mm;\n        }\n        table.nw-table th {\n            text-align: left;\n            border-bottom: 1px solid lightgray;\n            padding: 1mm;\n        }\n    </style>\n</head>\n<body>\n\n<h1>${text('mail.active_nav_warnings')}</h1>\n\n<table class="nw-table">\n  <tr>\n    <th>${text('mail.field.date')}</th>\n    <th>${text('msg.field.id')}</th>\n    <th>${text('msg.field.areas')}</th>\n    <th>${text('mail.field.warning')}</th>\n  </tr>\n  <#list messages as msg>\n    <#assign msgDesc=descForLang(msg, language)!>\n    <tr>\n        <td nowrap>${msg.publishDateFrom?string['dd-MM-yyyy']}</td>\n        <td nowrap>\n            <#if msg.shortId?has_content>${msg.shortId}</#if>\n        </td>\n        <td>\n            <#if msg.areas?has_content>\n                <@areaLineage area=msg.areas[0] />\n                <#if msgDesc?? && msgDesc.vicinity?has_content>\n                    - ${msgDesc.vicinity}\n                </#if>\n            </#if>\n        </td>\n        <td>\n            <#if msg.parts?has_content>\n                <#list msg.parts as part>\n                    <#assign partDesc=descForLang(part, language)!>\n                    <#if partDesc?? && partDesc.details?has_content>\n                        <@line>${partDesc.details}</@line><br>\n                    </#if>\n                </#list>\n            </#if>\n        </td>\n    </tr>\n  </#list>\n</table>\n\n</body>\n</html>\n\n	templates/mailinglist/active-navwarns.ftl	FM
9	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#include "../messages/message-support.ftl"/>\n\n<#if message??>\n    ${text('mail.navtex.cancel')}\n    <@renderNumberYearId message=message defaultValue=message.shortId!''></@renderNumberYearId>\n</#if>\n	templates/mailinglist/cancel-navtex.ftl	FM
10	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#if message??>\n    ${text('mail.navwarn.cancel')} ${message.shortId!''}\n</#if>\n	templates/mailinglist/cancel-navwarn.ftl	FM
11	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#include "../messages/message-support.ftl"/>\n\n<#include "safetynet-intro.ftl">\n\n<#if message??>\n    <div>\n        ${text('mail.safetynet.cancel')}\n        <#if message.shortId?has_content>${message.shortId}</#if>\n    </div>\n\n    <#assign safetynet=promulgation(message, 'safetynet')!>\n    <#if safetynet?? && safetynet.safetyNetId?has_content>\n        <div style="margin-top: 20px">\n            SafetyNET ID: ${safetynet.safetyNetId?html}\n        </div>\n    </#if>\n</#if>\n	templates/mailinglist/cancel-safetynet.ftl	FM
12	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#include "../messages/message-support.ftl"/>\n\n<#assign navtex=promulgation(message, 'navtex')!>\n\n<#if navtex??>\n    <#if navtex.preamble?has_content><@txtToHtml text=navtex.preamble/><br></#if>\n    <#if navtex.text?has_content><@txtToHtml text=navtex.text/></#if>\n    <#if navtex.priority?? && navtex.priority != 'NONE'>\n      <p>&nbsp;</p>\n      <p>\n        PRIORITY<br>\n        ${navtex.priority}\n      </p>\n    </#if>\n</#if>\n	templates/mailinglist/publish-navtex.ftl	FM
13	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#include "../messages/message-support.ftl"/>\n\n<#setting time_zone="GMT+0000">\n\n<#macro renderAreas area lang=language>\n    <#if area??>\n        <#if area.parent??>\n            <@renderAreas area=area.parent lang=lang/>\n        </#if>\n        <#assign areaDesc=descForLang(area, lang)!>\n        <#if areaDesc?? && areaDesc.name?has_content>\n            <@trailingDot>${areaDesc.name}</@trailingDot>\n        </#if>\n    </#if>\n</#macro>\n\n<#macro renderNavWarnDetails msg lang=language >\n\n    <#assign msgDesc=descForLang(msg, lang)!>\n\n    <#if msg.publishDateFrom??>\n        <div>${msg.publishDateFrom?string['dd MM yyyy HH:mm']} UTC</div>\n    </#if>\n\n    <#if msg.areas?has_content>\n        <div>\n            <@renderAreas area=msg.areas[0] lang=lang></@renderAreas>\n            <#if msgDesc?? && msgDesc.vicinity?has_content>\n                <@trailingDot>${msgDesc.vicinity}</@trailingDot>\n            </#if>\n        </div>\n    </#if>\n\n    <#-- A cancellation warning has no parts: its title names the warning it cancels -->\n    <#if msg.kind?? && msg.kind == 'CANCELLATION' && msgDesc?? && msgDesc.title?has_content>\n        <div><@trailingDot>${msgDesc.title}</@trailingDot></div>\n    </#if>\n\n    <#if msg.parts?has_content>\n        <#list msg.parts as part>\n            <#assign partDesc=descForLang(part, lang)!>\n            <#if partDesc?? && partDesc.details?has_content>\n                <div class="message-details">\n                    <@line>${partDesc.details}</@line>\n                </div>\n            </#if>\n        </#list>\n    </#if>\n\n</#macro>\n	templates/mailinglist/publish-navwarn-common.ftl	FM
14	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#include "publish-navwarn-common.ftl"/>\n\n<html>\n<head>\n    <link rel="stylesheet" type="text/css" href="/css/templates/mail.css">\n\n    <style>\n        .message-details p {\n            padding: 0;\n            margin: 0;\n        }\n    </style>\n</head>\n<body>\n\n    <div>\n        Navigational Warning <#if message.shortId?has_content>no. ${message.shortId}</#if>\n    </div>\n    <@renderNavWarnDetails msg=message lang=language></@renderNavWarnDetails>\n\n</body>\n</html>\n	templates/mailinglist/publish-navwarn.ftl	FM
15	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#include "../messages/message-support.ftl"/>\n\n<style>\n    .safetynetTable td, .safetynetTable th {\n        border: 1px solid lightgray;\n        padding: 5px;\n    }\n</style>\n\n<#assign safetynet=promulgation(message, 'safetynet')!>\n<#assign areaType = (params.area.class.name)!''/>\n\n<#macro renderAreas area lang=language>\n    <#if area??>\n        <#if area.parent??>\n            <@renderAreas area=area.parent lang=lang/> -\n        </#if>\n        <#assign areaDesc=descForLang(area, lang)!>\n        <#if areaDesc?? && areaDesc.name?has_content>\n            ${areaDesc.name}\n        </#if>\n    </#if>\n</#macro>\n\n<#include "safetynet-intro.ftl">\n\n<p>\n    The following navigational warning should be promulgated via SafetyNET.<br>\n    The format is aligned with the Inmarsat Maritime Safety Server (MSS) interface.\n</p>\n\n<#if safetynet??>\n<table class="safetynetTable">\n    <tr>\n        <th>1</th>\n        <th>MSI Type</th>\n        <td>Navigational</td>\n    </tr>\n    <#if message.type == 'COASTAL_WARNING'>\n        <tr>\n            <th>2</th>\n            <th>Service Code</th>\n            <td>13 - Coastal Warnings</td>\n        </tr>\n    <#elseif message.type == 'NAVAREA_WARNING'>\n        <tr>\n            <th>2</th>\n            <th>Service Code</th>\n            <td>51 - NAVAREA Warnings</td>\n        </tr>\n    </#if>\n    <tr>\n        <th>3</th>\n        <th>Priority</th>\n        <td>\n            <#if safetynet.priority?has_content>\n                ${safetynet.priority?capitalize}\n            </#if>\n        </td>\n    </tr>\n    <#if params.area?? && areaType?ends_with('SafetyNetCircularAreaVo')>\n        <tr>\n            <th rowspan="3">4</th>\n            <th>Area Type</th>\n            <td>Circular</td>\n        </tr>\n        <tr>\n            <th>Center</th>\n            <td>${params.area.center!''}</td>\n        </tr>\n        <tr>\n            <th>Radius (NM)</th>\n            <td>${params.area.radius!''}</td>\n        </tr>\n    <#elseif params.area?? && areaType?ends_with('SafetyNetRectangularAreaVo')>\n        <tr>\n            <th rowspan="4">4</th>\n            <th>Area Type</th>\n            <td>Rectangular</td>\n        </tr>\n        <tr>\n            <th>SW Corner</th>\n            <td>${params.area.swCorner!''}</td>\n        </tr>\n        <tr>\n            <th>Northings</th>\n            <td>${params.area.northings!''}</td>\n        </tr>\n        <tr>\n            <th>Eastings</th>\n            <td>${params.area.eastings!''}</td>\n        </tr>\n    <#elseif params.area?? && areaType?ends_with('SafetyNetCoastalAreaVo')>\n        <tr>\n            <th rowspan="4">4</th>\n            <th>Area Type</th>\n            <td>Other</td>\n        </tr>\n        <tr>\n            <th>Area Definition</th>\n            <td>Coastal Area</td>\n        </tr>\n        <tr>\n            <th>Area</th>\n            <td>${params.area.navareaDescription!''} - Area ${params.area.coastalArea!''}</td>\n        </tr>\n        <tr>\n            <th>Subject</th>\n            <td>A - Navigational warnings</td>\n        </tr>\n    <#elseif params.area?? && areaType?ends_with('SafetyNetNavareaAreaVo')>\n        <tr>\n            <th rowspan="3">4</th>\n            <th>Area Type</th>\n            <td>Other</td>\n        </tr>\n        <tr>\n            <th>Area Definition</th>\n            <td>NAVAREA</td>\n        </tr>\n        <tr>\n            <th>Area</th>\n            <td>${params.area.navareaDescription!''}</td>\n        </tr>\n    <#else>\n        <tr>\n            <th rowspan="1">4</th>\n            <th>Area Type</th>\n            <td></td>\n        </tr>\n    </#if>\n    <tr>\n        <th rowspan="2">5</th>\n        <th>Start Date</th>\n        <td></td>\n    </tr>\n    <tr>\n        <th>Repetition Code</th>\n        <td>\n            <#if params.area?? && params.area.repetition??>\n                ${params.area.repetitionDescription}\n            <#else>\n                01 - Transmit once\n            </#if>\n        </td>\n    </tr>\n    <tr>\n        <th>6</th>\n        <th>MSI Contents</th>\n        <td>\n            <#if params.area?? && params.area.messageAreaPrefix?has_content>\n                <div>\n                    <@line case="upper">\n                        ${params.area.messageAreaPrefix}\n                    </@line>\n                </div>\n            </#if>\n            <#if message.areas?has_content>\n                <div>\n                    <@line case="upper">\n                        <@renderAreas area=message.areas[0] lang='en'></@renderAreas>\n                        <#assign msgDesc=descForLang(message, lang)!>\n                        <#if msgDesc?? && msgDesc.vicinity?has_content>\n                            - ${msgDesc.vicinity}\n                        </#if>\n                    </@line>\n                </div>\n            </#if>\n            <div>\n                <@txtToHtml text=safetynet.text/>\n            </div>\n        </td>\n    </tr>\n</table>\n\n</#if>\n	templates/mailinglist/publish-safetynet.ftl	FM
16	2026-10-22 00:00:00	2026-10-22 00:00:00	0	/*\n * Copyright 2017 Danish Maritime Authority.\n *\n * Licensed under the Apache License, Version 2.0 (the "License");\n * you may not use this file except in compliance with the License.\n * You may obtain a copy of the License at\n *\n *       http://www.apache.org/licenses/LICENSE-2.0\n *\n * Unless required by applicable law or agreed to in writing, software\n * distributed under the License is distributed on an "AS IS" BASIS,\n * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n * See the License for the specific language governing permissions and\n * limitations under the License.\n */\n\nvar safetynet = message.promulgation('safetynet');\nif (safetynet !== null && safetynet.areaName !== null) {\n    var safetynetService = CdiUtils.getBean(org.niord.core.promulgation.SafetyNetPromulgationService.class);\n    var area = safetynetService.findAreaByName(\n        safetynet.type.typeId,\n        safetynet.areaName);\n    if (area !== null) {\n        params.put('area', area.toVo(org.niord.model.DataFilter.get().fields(org.niord.model.DataFilter.DETAILS)));\n    }\n}\n	templates/mailinglist/publish-safetynet.js	JS
17	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#-- introduction: this deployment's text goes here -->\n	templates/mailinglist/safetynet-intro.ftl	FM
18	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#-- introduction: this deployment's text goes here -->\n	templates/messages/fa-list-pdf-intro.ftl	FM
19	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "message-support.ftl"/>\n\n<html>\n<head>\n\n    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>\n    <title>${text("pdf.fa.title")}</title>\n\n    <@pageSizeStyle />\n    <link rel="stylesheet" type="text/css" href="/css/templates/pdf.css">\n    <link rel="stylesheet" type="text/css" href="/css/templates/common.css">\n\n    <style type="text/css" media="all">\n        .intro {\n            margin-top: 1cm;\n            font-size: 12px;\n        }\n        .intro table {\n            margin: 0 auto;\n            font-size: 12px;\n            text-align: left;\n        }\n        .intro table th {\n            width: 2cm;\n            font-weight: normal;\n        }\n    </style>\n\n</head>\n<body>\n\n\n<@renderDefaultHeaderAndFooter headerText="${text('pdf.fa.title')}"/>\n\n<h1>${text("pdf.fa.title")}</h1>\n\n<table class="first-page-info-line" style="margin-top: 10mm">\n    <tr>\n        <td>\n            ${.now?string['dd. MMMM yyyy']}\n            <#if edition?? && edition != '1'> - Version ${edition}</#if>\n        </td>\n        <td align="right">${text['pdf.fa.subtitle']}</td>\n    </tr>\n</table>\n\n<div class="intro">\n  <#include "fa-list-pdf-intro.ftl">\n</div>\n\n<div class="page-break">&nbsp;</div>\n\n<@renderMessageList messages=messages areaHeadings=areaHeadings/>\n\n</body>\n</html>\n	templates/messages/fa-list-pdf.ftl	FM
20	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#if frontPage!true>\n    <div class="first-page-footer">\n    </div>\n</#if>\n\n<div class="footer">\n    <div style="text-align: center"><img src="/img/company-logo.png" style="height: 1cm"></div>\n</div>\n	templates/messages/message-list-pdf-footer.ftl	FM
21	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "message-support.ftl"/>\n\n<html>\n<head>\n\n    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>\n    <title>${text("pdf.list.title")}</title>\n\n    <@pageSizeStyle frontPage=frontPage!true/>\n    <link rel="stylesheet" type="text/css" href="/css/templates/pdf.css">\n    <link rel="stylesheet" type="text/css" href="/css/templates/common.css">\n\n</head>\n<body>\n\n\n<@renderDefaultHeaderAndFooter headerText="Generated ${.now?datetime}" frontPage=frontPage!true/>\n\n\n<#if frontPage!true>\n\n    <h1>${text("pdf.list.title")}</h1>\n\n    <div style="margin: 1cm 0">\n        <table border="0">\n            <tr>\n                <th align="right" valign="top" nowrap>Generated: &nbsp;</th>\n                <td>${.now?datetime}</td>\n            </tr>\n            <tr>\n                <th align="right" valign="top" nowrap>Criteria: &nbsp;</th>\n                <td><small>${searchCriteria}</small></td>\n            </tr>\n            <tr>\n                <th align="right" valign="top" nowrap>Result: &nbsp;</th>\n                <td>${messages?size} messages</td>\n            </tr>\n        </table>\n    </div>\n\n    <@renderTOC messages=messages areaHeadings=areaHeadings />\n\n    <div class="page-break">&nbsp;</div>\n\n</#if>\n\n<@renderMessageList messages=messages areaHeadings=areaHeadings/>\n\n</body>\n</html>\n	templates/messages/message-list-pdf.ftl	FM
22	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "message-support.ftl"/>\n\n<html>\n<head>\n    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>\n\n    <link rel="stylesheet" type="text/css" href="/css/templates/mail.css">\n    <link rel="stylesheet" type="text/css" href="/css/templates/common.css">\n</head>\n<body>\n\n<h3>${text("mail.dear.user", mailTo)}</h3>\n\n<p style="margin-top:20px">\n    ${mailMessage}\n</p>\n\n<p style="margin-top:20px; margin-bottom: 40px">\n    ${text("mail.greetings.best.regards", mailSender)}\n</p>\n\n<hr>\n\n<@renderMessageList messages=messages areaHeadings=false />\n\n</body>\n</html>\n	templates/messages/message-mail.ftl	FM
23	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#assign line = 'org.niord.core.script.directive.LineDirective'?new()>\n<#assign formatPos = "org.niord.core.script.directive.LatLonDirective"?new()>\n<#assign formatDateInterval = "org.niord.core.script.directive.DateIntervalDirective"?new()>\n<#assign txtToHtml = "org.niord.core.script.directive.TextToHtmlDirective"?new()>\n<#assign trailingDot = "org.niord.core.script.directive.TrailingDotDirective"?new()>\n\n<#-- ***************************************  -->\n<#-- Parametrized message styles             -->\n<#-- ***************************************  -->\n<#macro pageSizeStyle frontPage=true>\n    <style type="text/css" media="all">\n\n        @page {\n            size: ${pageSize!"A4"} ${pageOrientation!"portrait"};\n        }\n\n    <#if frontPage>\n        /** First page rules **/\n        @page :first {\n            margin: 1.5cm 1.0cm;\n            @top-center {\n                content: element(first-page-header);\n            }\n            @bottom-center {\n                content: element(first-page-footer);\n            }\n        }\n    </#if>\n\n    </style>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders the default headers and footers  -->\n<#-- ***************************************  -->\n<#macro renderDefaultHeaderAndFooter headerText frontPage=true>\n    <#if frontPage>\n        <div class="first-page-header">\n        </div>\n    </#if>\n    <div class="header">\n        <span style="float: left">${headerText}</span>\n        <span style="float: right">${text("pdf.page")} <span id="pagenumber"></span></span>\n        &nbsp;\n    </div>\n\n    <#include "message-list-pdf-footer.ftl">\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders the parent-first area lineage    -->\n<#-- ***************************************  -->\n<#macro areaLineage area>\n    <#if area??>\n        <#if area.parent??>\n            <@areaLineage area=area.parent /> -\n        </#if>\n        <#if area.descs?has_content>${area.descs[0].name}</#if>\n    </#if>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Returns the two root-most area headings  -->\n<#-- ***************************************  -->\n<#function areaHeading area="area" >\n    <#if area?? && (!area.parent?? || (area.parent?? && !area.parent.parent??))>\n        <#return area/>\n    </#if>\n    <#if area?? >\n        <#return areaHeading(area.parent) />\n    </#if>\n    <#return NULL/>\n</#function>\n\n\n<#-- ***************************************  -->\n<#-- Returns if the attachment has the given type  -->\n<#-- ***************************************  -->\n<#function includeAttachment att type >\n    <#return att.display?has_content && att.display == type && att.type?has_content && att.type?starts_with('image')/>\n</#function>\n\n\n<#-- ***************************************  -->\n<#-- Renders an attachment  -->\n<#-- ***************************************  -->\n<#macro renderAttachment att >\n    <#assign imageStyle='max-width: 100%;' />\n    <#if att.width?has_content && att.height?has_content>\n        <#assign imageStyle="max-width: 100%; width: " + att.width + "; height: " + att.height />\n    <#elseif att.width?has_content>\n        <#assign imageStyle="max-width: 100%; width: " + att.width + "; " />\n    <#elseif att.height?has_content>\n        <#assign imageStyle="max-width: 100%; height: " + att.height + "; " />\n    </#if>\n\n    <div class="attachment">\n        <div>\n            <img src="/rest/repo/file/${att.path}" style="${imageStyle}">\n        </div>\n        <#if att.descs?has_content && att.descs[0].caption?has_content>\n            <div style="margin: 1mm; font-style: italic">${att.descs[0].caption}</div>\n        </#if>\n    </div>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders all nessage attachments of the given type    -->\n<#-- ***************************************  -->\n<#macro renderMessageAttachments msg type>\n    <#if msg.attachments?has_content>\n        <#list msg.attachments as att>\n            <#if includeAttachment(att, type)>\n                <@renderAttachment att=att />\n            </#if>\n        </#list>\n    </#if>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders all separate-page attachments    -->\n<#-- ***************************************  -->\n<#macro renderSeparatePageAttachments msg>\n    <#if msg.attachments?has_content>\n        <#list msg.attachments as att>\n            <#if includeAttachment(att, 'SEPARATE_PAGE')>\n            <div class="separate-attachment-page">\n                <#assign messageId=msg.id />\n                <#if msg.shortId?has_content>\n                    <#assign messageId=msg.shortId />\n                </#if>\n                <div style="margin: 1mm">\n                    <h4 id="${messageId}">${text("msg.attachment.title", messageId)}</h4>\n                </div>\n                <@renderAttachment att=att />\n            </div>\n            </#if>\n        </#list>\n    </#if>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders the TOC for area-headings        -->\n<#-- ***************************************  -->\n<#macro renderTOC messages areaHeadings prefix="">\n    <#if areaHeadings>\n        <div>\n            <h2>${text("pdf.toc")}</h2>\n            <@renderTOCEntries messages=messages prefix=prefix />\n        </div>\n    </#if>\n</#macro>\n\n\n<#macro renderTOCEntries messages prefix="">\n    <#assign tocAreaHeadingId=-9999999 />\n    <ol class='toc'>\n        <#list messages as msg>\n            <#if msg.areas?has_content>\n                <#assign area=areaHeading(msg.areas[0]) />\n                <#if area?? && area.id != tocAreaHeadingId>\n                    <#assign tocAreaHeadingId=area.id />\n                    <li><a href='#${prefix}${tocAreaHeadingId?c}'><@areaLineage area=areaHeading(area) /></a></li>\n                </#if>\n            <#elseif  tocAreaHeadingId == -9999999>\n                <li><a href='#${prefix}${tocAreaHeadingId?c}'>${text("msg.area.general")}</a></li>\n                <#assign tocAreaHeadingId=-9999998 />\n            </#if>\n        </#list>\n    </ol>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Returns the desc for the given language  -->\n<#-- ***************************************  -->\n<#function descForLang entity lang=language >\n    <#if entity.descs?has_content>\n        <#list entity.descs as desc>\n            <#if desc.lang?? && desc.lang == lang>\n                <#return desc />\n            </#if>\n        </#list>\n    </#if>\n    <#if entity.descs?has_content>\n        <#return entity.descs[0] />\n    </#if>\n</#function>\n\n\n<#-- ***************************************  -->\n<#-- Returns the given promulgation           -->\n<#-- ***************************************  -->\n<#function promulgation msg type >\n    <#if msg.promulgations?has_content>\n        <#list msg.promulgations as p>\n            <#if p.type?? && p.type.typeId == type>\n                <#return p />\n            </#if>\n        </#list>\n    </#if>\n</#function>\n\n\n<#-- ***************************************  -->\n<#-- Renders a language flag if wrong lang    -->\n<#-- ***************************************  -->\n<#macro renderLangFlag desc lang=language  draft=draft!false >\n    <#if draft == true && desc?? && desc.lang != lang>\n        <img src="/img/flags/${desc.lang}.png" class="lang-flag"/>\n    </#if>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders the number and year of a         -->\n<#-- as "number-3-digits/year-2-digits"       -->\n<#-- ***************************************  -->\n<#macro renderNumberYearId message defaultValue="" >\n    <#if message?? && message.year?? && message.number??>\n        ${message.number?string['000']}/${(message.year % 100)?string['00']}\n    <#else>\n        ${defaultValue}\n    </#if>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders the message source + publ. date  -->\n<#-- ***************************************  -->\n<#function renderSource desc msg >\n    <#assign source=""/>\n    <#if desc?? && desc.source?has_content>\n        <#assign source=source + desc.source/>\n    </#if>\n    <#if desc?? && desc.source?has_content && msg.publishDateFrom??>\n        <#assign source=source?remove_ending(".") + ". "/>\n    </#if>\n    <#if msg.publishDateFrom??>\n        <#assign source=source + text('msg.field.published') + ' ' + msg.publishDateFrom?string(text('msg.time.date_format'))/>\n    </#if>\n    <#return source/>\n</#function>\n\n\n<#-- ***************************************  -->\n<#-- Renders a message part                   -->\n<#-- ***************************************  -->\n<#macro renderMessagePart part lang=language draft=draft!false >\n    <#assign partDesc=descForLang(part, lang)!>\n    <#if !(part.hideSubject!false) && partDesc?? && partDesc.subject??>\n        <p>\n            <strong>${partDesc.subject}</strong>\n            <@renderLangFlag desc=partDesc lang=lang draft=draft/>\n        </p>\n    </#if>\n    <#if partDesc?? && partDesc.details??>\n        <div>\n            <@line>${partDesc.details}</@line>\n        </div>\n    </#if>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders a message                        -->\n<#-- ***************************************  -->\n<#macro renderMessage msg lang=language draft=draft!false link=link!false >\n\n    <#assign msgDesc=descForLang(msg, lang)!>\n\n    <div style="width: 100%;">\n\n    <#-- Attachments to display above message -->\n    <@renderMessageAttachments msg=msg type="ABOVE" />\n\n\n    <#-- Title line -->\n    <#if msg.originalInformation?has_content && msg.originalInformation>\n        <div class="avoid-break-after original-information">*</div>\n    </#if>\n\n    <#if msg.shortId?has_content>\n        <div class="avoid-break-before-after">\n            <span class="label-message-id">${msg.shortId}</span>\n            <#if msg.status == 'CANCELLED' || (msg.status == 'EXPIRED' && msg.type != 'PERMANENT_NOTICE')>\n                <span class="label-status">${text("msg.status." + msg.status)}</span>\n            </#if>\n            <#if msg.mainType == 'NM' && msg.type??>\n                <#switch msg.type>\n                    <#case 'TEMPORARY_NOTICE'>(T)<#break>\n                    <#case 'PRELIMINARY_NOTICE'>(P)<#break>\n                </#switch>\n            </#if>\n        </div>\n    </#if>\n\n    <#if msgDesc?has_content && msgDesc.title?has_content>\n        <div class="avoid-break-before-after" id="msg_${msg.id}">\n            <strong>\n                <#if link == true>\n                    <a href="${adminAppUrl}/messages?messageId=${msg.id?url('UTF-8')}" target="_blank" id="msg_${msg.id}">\n                        ${msgDesc.title}\n                    </a>\n                <#else>\n                    ${msgDesc.title}\n                </#if>\n            </strong>\n            <@renderLangFlag desc=msgDesc lang=lang />\n        </div>\n    </#if>\n\n\n    <table class="field-table">\n\n        <#-- Type line (drafts only) -->\n        <#if draft == true>\n            <tr>\n                <td class="field-name">${text("msg.field.type")}</td>\n                <td class="field-value">\n                    ${text("msg.type." + msg.type)} ${msg.mainType}\n                </td>\n            </tr>\n        </#if>\n\n        <#-- Status (drafts only) -->\n        <#if draft == true>\n            <tr>\n                <td class="field-name">${text("msg.field.status")}</td>\n                <td class="field-value">\n                    ${msg.status?lower_case}\n                </td>\n            </tr>\n        </#if>\n\n        <#-- Reference lines -->\n        <#if msg.references?has_content>\n            <tr>\n                <td class="field-name">\n                    ${text("msg.field.references")}\n                </td>\n                <td class="field-value">\n                    <#list msg.references as ref>\n                        <#assign refDesc=descForLang(ref, lang)!>\n                        <div>\n                            <@trailingDot>\n                                <#if link == true>\n                                    <a href="${adminAppUrl}/messages?messageId=${ref.messageId?url('UTF-8')}" target="_blank" id="msg_${ref.messageId}">\n                                        ${ref.messageId}\n                                    </a>\n                                <#else>\n                                    ${ref.messageId}\n                                </#if>\n\n                                <#if ref.type == 'REPETITION'>\n                                    - ${text("msg.reference.repetition")}\n                                <#elseif ref.type == 'REPETITION_NEW_TIME'>\n                                    - ${text("msg.reference.repetition_new_time")}\n                                <#elseif ref.type == 'CANCELLATION'>\n                                    - ${text("msg.reference.cancellation")}\n                                <#elseif ref.type == 'UPDATE'>\n                                    - ${text("msg.reference.update")}\n                                </#if>\n\n                                <#if refDesc?has_content && refDesc.description??>\n                                    - ${refDesc.description}\n                                </#if>\n                            </@trailingDot>\n                        </div>\n                    </#list>\n                </td>\n            </tr>\n        </#if>\n\n\n        <#-- Details line -->\n        <#if msg.parts?has_content>\n            <#list msg.parts as part>\n                <tr style="page-break-inside: auto;">\n                    <td class="field-name">\n                        <#if part?is_first || part.type != msg.parts[part?index - 1].type>\n                            ${text("msg.field." + part.type?lower_case)}\n                        </#if>\n                    </td>\n                    <td class="field-value message-description" style="page-break-inside: auto;">\n                        <@renderMessagePart part=part lang=lang draft=draft/>\n                    </td>\n                </tr>\n            </#list>\n        </#if>\n\n\n        <#-- Charts line -->\n        <#if msg.charts?has_content>\n            <tr>\n                <td class="field-name">${text("msg.field.charts")}</td>\n                <td class="field-value">\n                    <@trailingDot>\n                        <#list msg.charts as chart>\n                          ${chart.chartNumber}<#if chart.internationalNumber?has_content> (INT ${chart.internationalNumber?c})</#if><#if chart_has_next>,</#if>\n                        </#list>\n                    </@trailingDot>\n                </td>\n            </tr>\n        </#if>\n\n        <#-- Publication line -->\n        <#if msgDesc?has_content && msgDesc.publication?has_content>\n            <tr>\n                <td class="field-name">${text("msg.field.publication")}</td>\n                <td class="field-value">\n                ${msgDesc.publication!""}\n                </td>\n            </tr>\n        </#if>\n\n        <#-- Source line -->\n        <#assign source=renderSource(msgDesc, msg)/>\n        <#if source?has_content>\n            <tr>\n                <td class="field-value" align="right" colspan="2">\n                    (${source!""})\n                </td>\n            </tr>\n        </#if>\n\n    </table>\n\n    <#-- Attachments to display below message -->\n    <@renderMessageAttachments msg=msg type="BELOW" />\n\n    </div>\n\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders a list of messages               -->\n<#-- ***************************************  -->\n<#macro renderMessageList messages areaHeadings=true prefix="" draft=draft!false link=link!false>\n\n    <#assign areaHeadingId=-9999999 />\n\n    <table class="message-table">\n        <#-- Layout row for fixed-layout table -->\n        <tr>\n            <#if mapThumbnails!true>\n                <#assign colspan=2 />\n                <td width="140"></td>\n                <td width="*"></td>\n            <#else>\n                <#assign colspan=1 />\n                <td width="100%"></td>\n            </#if>\n        </tr>\n\n        <#list messages as msg>\n\n            <#if msg.areas?has_content>\n                <#assign area=areaHeading(msg.areas[0]) />\n                <#if areaHeadings && area?? && area.id != areaHeadingId>\n                    <#assign areaHeadingId=area.id />\n                    <tr class="table-header-row">\n                        <td colspan="${colspan}" class="table-header"><h4 id="${prefix}${areaHeadingId?c}"><@areaLineage area=areaHeading(area) /></h4></td>\n                    </tr>\n                </#if>\n            <#elseif  areaHeadings && areaHeadingId == -9999999>\n                <tr class="table-header-row">\n                    <td colspan="${colspan}" class="table-header"><h4 id="${prefix}${areaHeadingId?c}">${text("msg.area.general")}</h4></td>\n                </tr>\n                <#assign areaHeadingId=-9999998 />\n            </#if>\n            <#if separatePageIds?? && separatePageIds?seq_contains(msg.id)>\n                <#assign msgClass="break-before" />\n            <#else>\n                <#assign msgClass="" />\n            </#if>\n            <tr class="${msgClass}">\n                <#if mapThumbnails!true>\n                    <td class="table-image">\n                        <img src="/rest/message-map-image/${msg.id}.png" width="120" height="120"/>\n                    </td>\n                </#if>\n                <td class="table-item">\n                    <#if draft>\n                        <#list languages as lang>\n                            <#if lang?is_first>\n                                <#-- A language no dictionary names (no lang.<code>) is named by its code, as the administration app does -->\n                                <div class="lang-header-first">\n                                    <img src="/img/flags/${lang}.png" height="12"/>&nbsp;<#attempt>${text('lang.' + lang)}<#recover>${lang?upper_case}</#attempt> ${text('pdf.translation')}\n                                </div>\n                            <#else>\n                                <div class="lang-header">\n                                    <img src="/img/flags/${lang}.png" height="12"/>&nbsp;<#attempt>${text('lang.' + lang)}<#recover>${lang?upper_case}</#attempt> ${text('pdf.translation')}\n                                </div>\n                            </#if>\n                            <@renderMessage msg=msg lang=lang draft=draft link=link/>\n                        </#list>\n                    <#else>\n                        <@renderMessage msg=msg lang=language draft=draft link=link/>\n                    </#if>\n                </td>\n            </tr>\n        </#list>\n    </table>\n\n    <#list messages as msg>\n        <@renderSeparatePageAttachments msg=msg />\n    </#list>\n\n</#macro>\n	templates/messages/message-support.ftl	FM
24	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#-- introduction: this deployment's text goes here -->\n	templates/messages/nm-accumulated-report-pdf-intro.ftl	FM
25	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "message-support.ftl"/>\n\n<#--\n    The accumulated notices: a year drawn as the weeks it was compiled from.\n\n    The document is sectioned rather than flat, and the sections arrive in the\n    model as "groups" -- each one a source issue, its numbers, its content period and the\n    ids of the messages it printed, in the order that issue printed them. The ids\n    partition the ordered "messages" list rather than duplicating it, so a section\n    can never print something the list does not contain.\n\n    A section whose id list is empty is a week that printed nothing, and it is\n    still listed and still printed -- with its heading and one sentence in place\n    of the tables. The sections are the record of what the year covered, and a\n    year that quietly prints fifty-one of its fifty-two weeks is a document\n    claiming a week was never covered.\n\n    A model with no groups still renders: the flat list under one heading. That is\n    what a preview of an unsourced issue, or any other report pointed at this\n    template, produces.\n-->\n\n<#-- A cut-off is an instant, and which DAY it falls on is answered by the zone the\n     publication's own desk keeps -- never by the renderer's, which on an unattended\n     release is whatever zone the container happens to run in. -->\n\n<#if timeZone?has_content>\n    <#setting time_zone=timeZone>\n</#if>\n\n<#--\n    The messages of one section.\n\n    A section that was handed its own members uses them. A section that was only\n    named its ids picks them out of the ordered list, which is what a model built\n    by hand -- the report editor, a fixture -- hands over, and costs one scan of\n    the whole list per section.\n-->\n<#function messagesOfGroup group>\n    <#if group.messages??>\n        <#return group.messages />\n    </#if>\n    <#return messages?filter(msg -> group.messageIds?seq_contains(msg.id)) />\n</#function>\n\n<#-- The first and last printed number of a set of messages, where they have any. -->\n<#macro numberRange msgs>\n    <#local minNo = -1 />\n    <#local maxNo = -1 />\n    <#list msgs as msg>\n        <#if msg.number??>\n            <#if minNo == -1 || msg.number lt minNo><#local minNo = msg.number /></#if>\n            <#if maxNo == -1 || msg.number gt maxNo><#local maxNo = msg.number /></#if>\n        </#if>\n    </#list>\n    <#if minNo != -1>${minNo?c} - ${maxNo?c}</#if>\n</#macro>\n\n<#-- What a section is called: the source issue's own name, or what it is instead. -->\n<#macro groupTitle group>\n    <#compress>\n        <#if group.manual>\n            ${text('pdf.accumulated.added_by_hand')}\n        <#elseif group.name?has_content>\n            ${group.name}\n        <#elseif group.week??>\n            ${text('pdf.week')} ${group.week?c}\n        <#else>\n            ${text('pdf.nm')}\n        </#if>\n    </#compress>\n</#macro>\n\n<#--\n    One section's messages, split the way the weekly splits its own: the notices\n    proper with area headings, then the announcements under their own heading.\n\n    Split by ?filter rather than by appending to a list one message at a time.\n    Appending builds a sequence out of a chain of concatenations, one link per\n    message, and reading the n'th element walks the chain -- so printing the list\n    afterwards costs the square of its length. On a section of twenty that is\n    nothing; on the flat list a one-off or an unsourced preview produces, where\n    the whole document is one "section" of three thousand, it was the largest\n    single term in the time to render.\n-->\n<#macro renderGroupBody msgs prefix>\n    <#local nmMsgs = msgs?filter(msg -> msg.type != 'MISCELLANEOUS_NOTICE') />\n    <#local miscMsgs = msgs?filter(msg -> msg.type == 'MISCELLANEOUS_NOTICE') />\n\n    <#if nmMsgs?has_content>\n        <@renderMessageList messages=nmMsgs areaHeadings=areaHeadings prefix=prefix/>\n    </#if>\n\n    <#if miscMsgs?has_content>\n        <table width="100%">\n            <tr>\n                <td class="table-header"><h4 id="${prefix}misc">${text('pdf.misc_nm')}</h4></td>\n            </tr>\n        </table>\n        <@renderMessageList messages=miscMsgs areaHeadings=false prefix="${prefix}misc"/>\n    </#if>\n</#macro>\n\n\n<html>\n<head>\n\n    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>\n    <title>${text("pdf.accumulated.title")}</title>\n\n    <@pageSizeStyle />\n    <link rel="stylesheet" type="text/css" href="/css/templates/pdf.css">\n    <link rel="stylesheet" type="text/css" href="/css/templates/common.css">\n\n    <style type="text/css" media="all">\n        .nm-toc {\n            margin-top: 1cm;\n        }\n        .toc {\n            font-size: 12px;\n        }\n        .intro {\n            margin-top: 2cm;\n            font-size: 12px;\n            page-break-after:always;\n        }\n        .intro table {\n            margin: 0 auto;\n            font-size: 12px;\n            text-align: left;\n        }\n        .intro table th {\n            width: 2cm;\n            font-weight: normal;\n        }\n        .year-header {\n            font-size: 24px;\n            text-align: right;\n            margin-top: 1cm;\n            margin-bottom: 1mm;\n        }\n        .group-header {\n            margin-top: 8mm;\n            page-break-after: avoid;\n        }\n        .group-header h3 {\n            margin-bottom: 1mm;\n        }\n        .group-info-line {\n            font-size: 11px;\n            border-bottom: 1px solid #999999;\n            padding-bottom: 1mm;\n        }\n        /* The sentence a section with nothing in it carries: the intro's text\n           size, and none of its cover-page spacing or page break. */\n        .group-empty {\n            font-size: 12px;\n            margin-top: 4mm;\n        }\n    </style>\n\n</head>\n<body>\n\n<#-- Headers and footers -->\n<@renderDefaultHeaderAndFooter headerText="${text('pdf.accumulated.title')} ${year!''}"/>\n\n\n<#--\n    First page. ${year} is the year LABEL and prints exactly as it was typed:\n    free text, in letters as readily as in digits. The volume is counted from\n    ${yearNumber}, the year the numbering derived -- never from the label, which\n    is not arithmetic to do, and never from the clock, which would stamp the year\n    of printing on a document of a year that has been over for months. An issue\n    whose numbering derived no year prints no volume line at all.\n-->\n<h1>${text("pdf.accumulated.title")}</h1>\n\n<div class="year-header">\n    ${year!""}\n</div>\n<table class="first-page-info-line">\n    <tr>\n        <#-- The weekly prints the day it was made here. This one cannot: an annual\n             is routinely rendered again years later, and a printing date would be\n             the only part of the document that was not about its own year. -->\n        <td width="25%" align="left">\n            <#if edition?? && edition != '1'>Version ${edition}</#if>\n        </td>\n        <td width="25%" align="center">ISSN ${ISSN!""}</td>\n        <td width="25%" align="center">\n            <#if yearNumber??>${text('pdf.volume', (yearNumber - 1884))}</#if>\n        </td>\n        <td width="25%" align="right"><@numberRange msgs=messages /></td>\n    </tr>\n</table>\n\n<#if groups?has_content>\n    <div class="nm-toc">\n        <h2>${text("pdf.toc")}</h2>\n        <ol class='toc'>\n            <#list groups as group>\n                <#-- Every section, the empty ones included: the contents list what\n                     the year covered, and a week missing from it reads as a week\n                     that was never covered at all. -->\n                <li><a href='#group_${group?index}'><@groupTitle group=group /></a></li>\n            </#list>\n        </ol>\n    </div>\n</#if>\n\n\n<div class="intro">\n    <#include "nm-accumulated-report-pdf-intro.ftl">\n</div>\n\n\n<#if groups?has_content>\n\n    <#list groups as group>\n        <#assign groupMessages = messagesOfGroup(group) />\n\n        <#-- EVERY section is printed, including one nothing was filed under. The\n             sections are the record of what the year covered, so a week that\n             printed nothing still gets its heading and says so in a sentence; a\n             year that quietly prints fifty-one of its fifty-two weeks is a\n             document claiming a week was never covered. -->\n\n        <#if !group?is_first>\n            <div class="page-break"></div>\n        </#if>\n\n        <div class="group-header">\n            <h3 id="group_${group?index}"><@groupTitle group=group /></h3>\n            <table class="group-info-line" width="100%">\n                <tr>\n                    <td width="40%" align="left">\n                        <#if !group.manual && group.week??>\n                            ${text('pdf.week')} ${group.week?c}<#if group.weekTo?? && group.weekTo != group.week> - ${group.weekTo?c}</#if><#if group.year??>, ${group.year?c}</#if>\n                        </#if>\n                    </td>\n                    <td width="30%" align="center">\n                        <#-- The stretch the week's notices were published in, which is what a\n                             reader of the year looks a week up by. A source with no period\n                             start -- an in-force edition is a single instant -- has its\n                             cut-off alone. ?datetime because a Date arrives without a\n                             declared kind. -->\n                        <#if group.cutoff?? && group.intervalFrom??>\n                            ${text('pdf.accumulated.period')}: ${group.intervalFrom?datetime?string["dd. MMMM yyyy"]} - ${group.cutoff?datetime?string["dd. MMMM yyyy"]}\n                        <#elseif group.cutoff??>\n                            ${text('pdf.accumulated.cutoff')}: ${group.cutoff?datetime?string["dd. MMMM yyyy"]}\n                        </#if>\n                    </td>\n                    <#-- No range where there are no numbers to range over. -->\n                    <td width="30%" align="right"><#if groupMessages?has_content><@numberRange msgs=groupMessages /></#if></td>\n                </tr>\n            </table>\n        </div>\n\n        <#if groupMessages?has_content>\n            <@renderGroupBody msgs=groupMessages prefix="g${group?index}"/>\n        <#else>\n            <p class="group-empty">${text('pdf.accumulated.no_messages')}</p>\n        </#if>\n    </#list>\n\n<#elseif messages?has_content>\n\n    <#-- No sections: the ordered list, whole, exactly as the weekly prints one. -->\n    <div class="group-header">\n        <h3>${text('pdf.nm')}</h3>\n    </div>\n    <@renderGroupBody msgs=messages prefix="nm"/>\n\n</#if>\n\n</body>\n</html>\n	templates/messages/nm-accumulated-report-pdf.ftl	FM
26	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#-- introduction: this deployment's text goes here -->\n	templates/messages/nm-almanac-report-pdf-intro.ftl	FM
27	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "message-support.ftl"/>\n\n<html>\n<head>\n\n    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>\n    <title>${text("pdf.list.title")}</title>\n\n    <@pageSizeStyle />\n    <link rel="stylesheet" type="text/css" href="/css/templates/pdf.css">\n    <link rel="stylesheet" type="text/css" href="/css/templates/common.css">\n\n    <style type="text/css" media="all">\n        table.contact-info {\n            margin-bottom: 5mm;\n            font-size: 11px;\n        }\n        table.contact-info tr td {\n            padding: 3mm;\n        }\n    </style>\n\n</head>\n<body>\n\n<@renderDefaultHeaderAndFooter headerText="${text('pdf.nm_almanac.title')} - ${text('pdf.nm_almanac.subtitle')}"/>\n\n<h1 style="margin-top: 5mm">${text("pdf.nm_almanac.title")}</h1>\n\n<h5>${text("pdf.nm_almanac.desc")}</h5>\n\n<table class="first-page-info-line" style="margin-top: 5mm">\n    <tr>\n        <td>\n            ${.now?string['dd. MMMM yyyy']}\n            <#if edition?? && edition != '1'> - Version ${edition}</#if>\n        </td>\n        <td align="right">${text("pdf.nm_almanac.subtitle")}</td>\n    </tr>\n</table>\n\n<div>\n  <#include "nm-almanac-report-pdf-intro.ftl">\n</div>\n\n<div>\n    <h2>${text("pdf.toc")}</h2>\n    <table width="100%" cellpadding="1" cellspacing="0">\n        <#list messages as msg>\n            <#if msg.descs?has_content && msg.descs[0].title?has_content>\n                <tr>\n                    <td style="width: 2cm" valign="top">\n                        <a href='#msg_${msg.id}'><#if msg.shortId?has_content>${msg.shortId}</#if></a>\n                    </td>\n                    <td width="*">\n                        <div class="toc"><a href='#msg_${msg.id}'>${msg.descs[0].title}</a></div>\n                    </td>\n                </tr>\n            </#if>\n        </#list>\n    </table>\n</div>\n\n\n<div class="page-break">&nbsp;</div>\n\n<@renderMessageList messages=messages areaHeadings=areaHeadings/>\n\n</body>\n</html>\n	templates/messages/nm-almanac-report-pdf.ftl	FM
28	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "message-support.ftl"/>\n\n<html>\n<head>\n\n    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>\n    <title>${text("pdf.list.title")}</title>\n\n    <@pageSizeStyle frontPage=true/>\n    <link rel="stylesheet" type="text/css" href="/css/templates/pdf.css">\n    <link rel="stylesheet" type="text/css" href="/css/templates/common.css">\n\n</head>\n<body>\n\n\n<#assign msg=messages[0] />\n\n<#-- Construct the title -->\n<#assign title="" />\n<#if msg.descs?has_content && msg.descs[0].title?has_content>\n    <#assign title=msg.descs[0].title />\n</#if>\n<#if msg.parts?has_content>\n    <#list msg.parts as part>\n        <#if part.type == 'DETAILS' && part.descs?has_content && part.descs[0].subject?has_content>\n            <#assign title=part.descs[0].subject />\n        </#if>\n    </#list>\n</#if>\n<#assign title=title?remove_ending(".") />\n\n<@renderDefaultHeaderAndFooter headerText="${text('pdf.nm_annex.nm_ref', .vars['NtM-ref']!'')}" frontPage=true/>\n\n<h1 style="margin-top: 5mm">${title}</h1>\n\n<table class="first-page-info-line" style="margin: 1cm 0">\n    <tr>\n        <td>\n            ${.now?string['dd. MMMM yyyy']}\n            <#if edition?? && edition != '1'> - Version ${edition}</#if>\n        </td>\n        <td align="right">\n            ${text("pdf.nm_annex.nm_ref", .vars['NtM-ref']!"")}\n        </td>\n    </tr>\n</table>\n\n<@renderMessageList messages=messages areaHeadings=false/>\n\n</body>\n</html>\n	templates/messages/nm-annex-report-pdf.ftl	FM
29	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#-- introduction: this deployment's text goes here -->\n	templates/messages/nm-report-pdf-intro.ftl	FM
30	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "message-support.ftl"/>\n\n<#--\n    Split by ?filter rather than by appending to a list one message at a time.\n    Appending builds a sequence out of a chain of concatenations, one link per\n    message, and reading the n'th element walks the chain -- so printing the list\n    afterwards costs the square of its length.\n-->\n<#assign nmMessages = messages?filter(msg -> msg.type != 'MISCELLANEOUS_NOTICE') />\n<#assign miscMessages = messages?filter(msg -> msg.type == 'MISCELLANEOUS_NOTICE') />\n<#assign minMessageNo = 9999999 />\n<#assign maxMessageNo = -9999999 />\n<#list messages as msg>\n    <#if msg.number?? && msg.number lt minMessageNo >\n        <#assign minMessageNo = msg.number />\n    </#if>\n    <#if msg.number?? && msg.number gt maxMessageNo >\n        <#assign maxMessageNo = msg.number />\n    </#if>\n</#list>\n\n\n<html>\n<head>\n\n    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>\n    <title>${text("pdf.list.title")}</title>\n\n    <@pageSizeStyle />\n    <link rel="stylesheet" type="text/css" href="/css/templates/pdf.css">\n    <link rel="stylesheet" type="text/css" href="/css/templates/common.css">\n\n    <style type="text/css" media="all">\n        .nm-toc {\n            margin-top: 1cm;\n        }\n        .toc {\n            font-size: 12px;\n        }\n        .intro {\n            margin-top: 2cm;\n            font-size: 12px;\n            page-break-after:always;\n        }\n        .intro table {\n            margin: 0 auto;\n            font-size: 12px;\n            text-align: left;\n        }\n        .intro table th {\n            width: 2cm;\n            font-weight: normal;\n        }\n        .week-year-header {\n            font-size: 24px;\n            text-align: right;\n            margin-top: 1cm;\n            margin-bottom: 1mm;\n        }\n    </style>\n\n</head>\n<body>\n\n<#-- Headers and footers -->\n<@renderDefaultHeaderAndFooter headerText="${text('pdf.nm')} - ${text('pdf.week')} ${week!''}, ${year!''}"/>\n\n\n<#-- First page -->\n<h1>${text("pdf.nm")}</h1>\n\n<div class="week-year-header">\n    ${text("pdf.week")} ${week!""}, ${year!""}\n</div>\n<table class="first-page-info-line">\n    <tr>\n        <td width="25%" align="left">\n            ${.now?string["dd. MMMM yyyy"]}\n            <#if edition?? && edition != '1'> - Version ${edition}</#if>\n        </td>\n        <td width="25%" align="center">ISSN ${ISSN!""}</td>\n        <#-- The volume is counted from ${yearNumber}, the year the numbering\n             derived, and not from the clock: a December issue amended in January,\n             and any re-render years later, would otherwise print the volume of the\n             year it was printed in rather than of the year it is the issue for.\n             The legacy print dialog renders this same template with no issue\n             behind it, so where no year was derived the clock still answers. -->\n        <td width="25%" align="center"><#if yearNumber??>${text('pdf.volume', (yearNumber - 1884))}<#else>${text('pdf.volume', (.now?string('yyyy')?number - 1884))}</#if></td>\n        <td width="25%" align="right">\n            <#if minMessageNo != 9999999 && maxMessageNo != -9999999>\n                ${minMessageNo?c} - ${maxMessageNo?c}\n            </#if>\n        </td>\n    </tr>\n</table>\n\n<div class="nm-toc">\n    <h2>${text("pdf.toc")}</h2>\n    <ol class='toc'>\n\n        <#assign tocAreaHeadingId=-9999999 />\n        <#list nmMessages as msg>\n            <#if msg.areas?has_content>\n                <#assign area=areaHeading(msg.areas[0]) />\n                <#if area?? && area.id != tocAreaHeadingId>\n                    <#assign tocAreaHeadingId=area.id />\n                    <li><a href='#nm${tocAreaHeadingId?c}'><@areaLineage area=areaHeading(area) /></a></li>\n                </#if>\n            <#elseif  tocAreaHeadingId == -9999999>\n                <li><a href='#nm${tocAreaHeadingId?c}'>${text("msg.area.general")}</a></li>\n                <#assign tocAreaHeadingId=-9999998 />\n            </#if>\n        </#list>\n\n        <#if miscMessages?has_content>\n            <li>\n                <div class="toc"><a href='#misc'>${text('pdf.misc_nm')}</a></div>\n            </li>\n        </#if>\n    </ol>\n</div>\n\n\n<div class="intro">\n    <#include "nm-report-pdf-intro.ftl">\n</div>\n\n\n<#-- Permanent and T&P messages -->\n<#if nmMessages?has_content>\n    <@renderMessageList messages=nmMessages areaHeadings=areaHeadings prefix="nm"/>\n</#if>\n\n<#-- Add page break -->\n<#if nmMessages?has_content && miscMessages?has_content>\n    <div class="page-break"></div>\n</#if>\n\n<#-- Misc messages -->\n<#if miscMessages?has_content>\n    <table width="100%">\n        <tr>\n            <td class="table-header"><h4 id="misc">${text('pdf.misc_nm')}</h4></td>\n        </tr>\n    </table>\n    <@renderMessageList messages=miscMessages areaHeadings=false/>\n</#if>\n\n</body>\n</html>\n	templates/messages/nm-report-pdf.ftl	FM
31	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "message-support.ftl"/>\n\n<#--\n    Split by ?filter rather than by appending to a list one message at a time.\n    Appending builds a sequence out of a chain of concatenations, one link per\n    message, and reading the n'th element walks the chain -- so printing the list\n    afterwards costs the square of its length, which on a list of a whole year's\n    temporary and preliminary notices is most of the time to render it.\n-->\n<#assign tpMessages = messages?filter(msg ->\n        msg.type == 'PRELIMINARY_NOTICE' || msg.type == 'TEMPORARY_NOTICE') />\n\n\n<html>\n<head>\n\n    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>\n    <title>${text("pdf.list.title")}</title>\n\n    <@pageSizeStyle />\n    <link rel="stylesheet" type="text/css" href="/css/templates/pdf.css">\n    <link rel="stylesheet" type="text/css" href="/css/templates/common.css">\n\n    <style type="text/css" media="all">\n        td.table-item {\n            vertical-align: top;\n            width: 100%;\n            padding: 5px 0;\n        }\n        .week-year-header {\n            font-size: 24px;\n            text-align: right;\n            margin-top: 1cm;\n            margin-bottom: 1mm;\n        }\n    </style>\n\n</head>\n<body>\n\n<@renderDefaultHeaderAndFooter headerText="${text('pdf.nm_tp')} - ${text('pdf.week')} ${week!''}, ${year!''}"/>\n\n<#-- First page -->\n<h1>${text("pdf.nm_tp")}</h1>\n\n<div class="week-year-header">\n    ${text("pdf.week")} ${week!""}, ${year!""}\n</div>\n\n<table class="first-page-info-line" style="margin-bottom: 1cm">\n    <tr>\n        <td width="50%" align="left">\n            ${.now?string["dd. MMMM yyyy"]}\n            <#if edition?? && edition != '1'> - Version ${edition}</#if>\n        </td>\n        <td width="50%" align="right">\n            ${text("pdf.nm_abbrev")}  ${week!""} ${year!""}\n        </td>\n    </tr>\n</table>\n\n\n<#-- T&P messages -->\n<#if tpMessages?has_content>\n\n    <#assign areaHeadingId=-9999999 />\n\n     <table class="message-table">\n\n         <#-- Define column widths (fixed table layout) -->\n         <tr>\n             <td style="width: 2.5cm"></td>\n             <td style="width: 0.5cm"></td>\n             <td width="*"></td>\n         </tr>\n\n        <#list tpMessages as msg>\n\n            <#if msg.areas?has_content>\n                <#assign area=areaHeading(msg.areas[0]) />\n                <#if areaHeadings && area?? && area.id != areaHeadingId>\n                    <#assign areaHeadingId=area.id />\n                    <tr class="table-header-row">\n                        <td colspan="3" class="table-item">\n                            <h4 id="${areaHeadingId?c}"><@areaLineage area=areaHeading(area) /></h4>\n                        </td>\n                    </tr>\n                </#if>\n            <#elseif  areaHeadings && areaHeadingId == -9999999>\n                <tr class="table-header-row">\n                    <td colspan="3" class="table-header"><h4 id="${areaHeadingId?c}">${text("msg.area.general")}</h4></td>\n                </tr>\n                <#assign areaHeadingId=-9999998 />\n            </#if>\n            <tr>\n                <td class="table-item">\n                    <#if msg.shortId?has_content>\n                        ${msg.shortId}\n                    </#if>\n                </td>\n                <td class="table-item">\n                    <#if msg.type == 'PRELIMINARY_NOTICE'>\n                        (P)\n                    <#elseif msg.type == 'TEMPORARY_NOTICE'>\n                        (T)\n                    </#if>\n                </td>\n                <td class="table-item">\n                    <#if msg.descs?has_content && msg.descs[0].title?has_content>\n                        ${msg.descs[0].title}\n                    </#if>\n                </td>\n            </tr>\n        </#list>\n    </table>\n\n</#if>\n\n\n</body>\n</html>\n	templates/messages/nm-tp-report-pdf.ftl	FM
32	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n\n<#macro aton defaultSubjectFields=true enDefaultName="" enDetails="" enNavtex="">\n\n    <#if defaultSubjectFields>\n        <@defaultSubjectFieldTemplates/>\n    </#if>\n\n    <field-template field="part.getDesc('en').details" format="html">\n        <#list params.positions![] as pos>\n            <@renderAtonType atonParams=pos defaultName="${enDefaultName}" format="long" lang="en"/>\n            <@renderPositionList geomParam=pos lang="en"/> ${enDetails}.<br>\n        </#list>\n    </field-template>\n\n    <#if promulgate('navtex')>\n        <field-template field="message.promulgation('navtex').text" update="append">\n            <#list params.positions![] as pos>\n                <@line format="navtex">\n                    <@renderAtonType atonParams=pos defaultName="${enDefaultName}" format="short" lang="en"/>\n                    <@renderPositionList geomParam=pos format="navtex" lang="en"/> ${enNavtex}.\n                </@line>\n            </#list>\n        </field-template>\n    </#if>\n\n    <#if promulgate('safetynet')>\n        <field-template field="message.promulgation('safetynet').text" update="append">\n            <#list params.positions![] as pos>\n                <@line format="navtex">\n                    <@renderAtonType atonParams=pos defaultName="${enDefaultName}" format="short" lang="en"/>\n                    <@renderPositionList geomParam=pos format="navtex" lang="en"/> ${enNavtex}.\n                </@line>\n            </#list>\n        </field-template>\n    </#if>\n\n</#macro>\n	templates/tmpl/aton-common.ftl	FM
33	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n\n<#macro renderAtonReplacementType atonParams defaultName format='long' lang='en'>\n    <#if atonParams?has_content && atonParams.aton_replacement?has_content>\n        <#assign desc=descForLang(atonParams.aton_replacement, lang)!>\n        <#if desc?? && format == 'long'>\n            ${desc.longValue}\n        <#elseif desc??>\n            ${desc.value}\n        <#else>\n            ${defaultName}\n        </#if>\n    <#else>\n        ${defaultName}\n    </#if>\n</#macro>\n\n\n<#macro atonReplaced enDefaultName="" enDefaultReplacementName="">\n\n    <@defaultSubjectFieldTemplates/>\n\n    <field-template field="part.getDesc('en').details" format="html">\n        <#list params.positions![] as pos>\n            <@renderAtonType atonParams=pos defaultName="${enDefaultName}" format="long" lang="en"/>\n            <@renderPositionList geomParam=pos lang="en"/> has been replaced with\n            <@renderAtonReplacementType atonParams=pos defaultName="${enDefaultReplacementName}" format="long" lang="en"/>\n            <#if pos.aton_light?has_content>\n                showing\n                <@lightCharacterFormat light=pos.aton_light format="verbose" lang="en"/>\n            </#if>.<br>\n        </#list>\n    </field-template>\n\n    <#if promulgate('navtex')>\n        <field-template field="message.promulgation('navtex').text" update="append">\n            <#list params.positions![] as pos>\n                <@line format="navtex">\n                    <@renderAtonType atonParams=pos defaultName="${enDefaultName}" format="short" lang="en"/>\n                    <@renderPositionList geomParam=pos format="navtex" lang="en"/> REPLACED WITH\n                    <@renderAtonReplacementType atonParams=pos defaultName="${enDefaultReplacementName}" format="navtex" lang="en"/>\n                    <#if pos.aton_light?has_content>\n                        showing\n                        <@lightCharacterFormat light=pos.aton_light format="normal" lang="en"/>\n                    </#if>.\n                </@line>\n            </#list>\n        </field-template>\n    </#if>\n\n    <#if promulgate('safetynet')>\n        <field-template field="message.promulgation('safetynet').text" update="append">\n            <#list params.positions![] as pos>\n                <@line format="navtex">\n                    <@renderAtonType atonParams=pos defaultName="${enDefaultName}" format="short" lang="en"/>\n                    <@renderPositionList geomParam=pos format="navtex" lang="en"/> REPLACED WITH\n                    <@renderAtonReplacementType atonParams=pos defaultName="${enDefaultReplacementName}" format="navtex" lang="en"/>\n                    <#if pos.aton_light?has_content>\n                        showing\n                        <@lightCharacterFormat light=pos.aton_light format="normal" lang="en"/>\n                    </#if>.\n                </@line>\n            </#list>\n        </field-template>\n    </#if>\n\n</#macro>\n	templates/tmpl/aton-replacement-common.ftl	FM
34	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The beacon"\n    enDetails="is damaged"\n    enNavtex="DAMAGED"\n    />\n	templates/tmpl/beacon-damaged.ftl	FM
35	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The beacon"\n    enDetails="is missing"\n    enNavtex="MISSING"\n    />\n	templates/tmpl/beacon-missing.ftl	FM
36	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The beacon"\n    enDetails="is partly obscured"\n    enNavtex="PARTLY OBSCURED"\n    />\n	templates/tmpl/beacon-partly-obscured.ftl	FM
37	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n\n<@defaultSubjectFieldTemplates/>\n\n<field-template field="part.getDesc('en').details" format="html">\n    The bridge\n    <#if params.bridge_name?has_content>\n        <@quote text=params.bridge_name format="angular"/>\n    </#if>\n    <@renderPositionList geomParam=part lang="en"/>\n    is closed for passage that requires opening of the bridge.\n</field-template>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <@line format="navtex">\n            BRIDGE\n            <#if params.bridge_name?has_content>\n                ${params.bridge_name}\n            </#if>\n            <@renderPositionList geomParam=part format="navtex" lang="en"/>\n            CLOSED FOR PASSAGE THAT REQUIRES OPENING OF BRIDGE.\n        </@line>\n    </field-template>\n</#if>\n\n<#if promulgate('safetynet')>\n    <field-template field="message.promulgation('safetynet').text" update="append">\n        <@line format="navtex">\n            BRIDGE\n            <#if params.bridge_name?has_content>\n                ${params.bridge_name}\n            </#if>\n            <@renderPositionList geomParam=part format="navtex" lang="en"/>\n            CLOSED FOR PASSAGE THAT REQUIRES OPENING OF BRIDGE.\n        </@line>\n    </field-template>\n</#if>\n	templates/tmpl/bridge-closed.ftl	FM
38	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n\n<@defaultSubjectFieldTemplates/>\n\n<field-template field="part.getDesc('en').details" format="html">\n    <#list params.positions as pos>\n        <@renderAtonType atonParams=pos defaultName="A buoy" format="long" lang="en"/>\n        has been established <@renderPositionList geomParam=pos lang="en"/>.<br>\n    </#list>\n</field-template>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <#list params.positions as pos>\n            <@line format="navtex">\n                <@renderAtonType atonParams=pos defaultName="A buoy" format="short" lang="en"/>\n                ESTABLISHED <@renderPositionList geomParam=pos format="navtex" lang="en"/>.\n            </@line>\n        </#list>\n    </field-template>\n</#if>\n\n<#if promulgate('safetynet')>\n    <field-template field="message.promulgation('safetynet').text" update="append">\n        <#list params.positions as pos>\n            <@line format="navtex">\n                <@renderAtonType atonParams=pos defaultName="A buoy" format="short" lang="en"/>\n                ESTABLISHED <@renderPositionList geomParam=pos format="navtex" lang="en"/>.\n            </@line>\n        </#list>\n    </field-template>\n</#if>\n	templates/tmpl/buoy-established.ftl	FM
39	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The buoy"\n    enDetails="is missing"\n    enNavtex="MISSING"\n    />\n	templates/tmpl/buoy-missing.ftl	FM
40	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The buoy"\n    enDetails="is off station"\n    enNavtex="OFF STATION"\n    />\n	templates/tmpl/buoy-off-station.ftl	FM
41	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-replacement-common.ftl"/>\n\n<@atonReplaced\n    enDefaultName="The buoy"\n    enDefaultReplacementName="a buoy"\n    />\n	templates/tmpl/buoy-replaced.ftl	FM
42	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<#assign durationEn=(params.duration??)?then(getListValue(params.duration, '', 'normal', 'en'), '')/>\n\n<@aton\n    enDefaultName="The buoy"\n    enDetails="has been ${durationEn} withdrawn"\n    enNavtex="${durationEn} WITHDRAWN"\n    />\n	templates/tmpl/buoy-withdrawn.ftl	FM
43	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "obstruction-work.ftl"/>\n\n<@obstructionWork\n    enDetails="cable operations are carried out"\n    enNavtex="CABLE OPERATION" />\n	templates/tmpl/cable-opetations.ftl	FM
44	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#-- ***************************************  -->\n<#-- Global directives                        -->\n<#-- ***************************************  -->\n<#assign formatPos = "org.niord.core.script.directive.LatLonDirective"?new()>\n<#assign line = 'org.niord.core.script.directive.LineDirective'?new()>\n<#assign quote = 'org.niord.core.script.directive.QuoteDirective'?new()>\n<#assign trailingDot = "org.niord.core.script.directive.TrailingDotDirective"?new()>\n<#assign debug = 'org.niord.core.script.directive.DebugDirective'?new()>\n<#assign dateIntervalFormat = 'org.niord.core.script.directive.DateIntervalDirective'?new()>\n<#assign navtexDateFormat = 'org.niord.core.script.directive.NavtexDateFormatDirective'?new()>\n<#assign lightCharacterFormat = 'org.niord.core.script.directive.LightCharacterDirective'?new()>\n<#assign callSignFormat = 'org.niord.core.script.directive.CallSignDirective'?new()>\n<#assign posDecimals = (params.posDecimals!'1')?number> <#-- 1 decimals in position formatting -->\n\n<#-- ***************************************  -->\n<#-- Returns the desc for the given language  -->\n<#-- ***************************************  -->\n<#function descForLang entity lang >\n    <#if entity.descs?has_content>\n        <#list entity.descs as desc>\n            <#if desc.lang?? && desc.lang == lang>\n                <#return desc />\n            </#if>\n        </#list>\n    </#if>\n    <#if entity.descs?has_content>\n        <#return entity.descs[0] />\n    </#if>\n</#function>\n\n\n<#-- ***************************************  -->\n<#-- Renders the parent-first area lineage    -->\n<#-- ***************************************  -->\n<#macro renderAreaLineage area lang="en">\n    <#if area??>\n        <#if area.parent??>\n            <@renderAreaLineage area=area.parent lang=lang />\n        </#if>\n        <#assign areaDesc=descForLang(area, lang)!>\n        <#if areaDesc?? && areaDesc.name?has_content >\n            <@trailingDot>${areaDesc.name}</@trailingDot>\n        </#if>\n    </#if>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders the message title line           -->\n<#-- ***************************************  -->\n<#macro renderTitleLine message lang="en">\n    <#if message??>\n        <#if message.areas?has_content>\n            <@renderAreaLineage area=message.areas[0] lang=lang />\n        </#if>\n        <#assign msgDesc=descForLang(message, lang)!>\n        <#if msgDesc?? && msgDesc.vicinity?has_content>\n            <@trailingDot>${msgDesc.vicinity}</@trailingDot>\n        </#if>\n        <#if message.parts?has_content>\n            <#list message.parts as part>\n                <#assign partDesc=descForLang(part, lang)!>\n                <#if partDesc?? && partDesc.subject?has_content>\n                    <@trailingDot>${partDesc.subject}</@trailingDot>\n                </#if>\n            </#list>\n        </#if>\n    </#if>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Returns the two root-most area headings  -->\n<#-- ***************************************  -->\n<#function areaHeading area="area" >\n    <#if area?? && (!area.parent?? || (area.parent?? && !area.parent.parent??))>\n        <#return area/>\n    </#if>\n    <#if area?? >\n        <#return areaHeading(area.parent) />\n    </#if>\n    <#return NULL/>\n</#function>\n\n\n<#-- ***************************************  -->\n<#-- Renders default suject fields of the     -->\n<#-- first details message part as the name   -->\n<#-- of the current template                  -->\n<#-- ***************************************  -->\n<#macro defaultMessageSubjectFieldTemplates>\n    <#list languages as lang>\n        <#assign desc=descForLang(template, lang)!>\n        <#if desc?? && desc.name?has_content>\n            <field-template field="message.part('DETAILS').getDesc('${lang}').subject" format="text">\n                ${desc.name}\n            </field-template>\n        </#if>\n    </#list>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders default suject fields of the     -->\n<#-- current message part as the name of the  -->\n<#-- current template                         -->\n<#-- ***************************************  -->\n<#macro defaultSubjectFieldTemplates>\n    <#list languages as lang>\n        <#assign desc=descForLang(template, lang)!>\n        <#if desc?? && desc.name?has_content>\n            <field-template field="part.getDesc('${lang}').subject" format="text">\n                ${desc.name}\n            </field-template>\n        </#if>\n    </#list>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Serializes the message geometry param    -->\n<#-- into a list of positions                 -->\n<#-- ***************************************  -->\n<#function toPositions geomParam >\n    <#assign positions = []/>\n    <#if geomParam?? && geomParam.geometry?has_content>\n        <#list geomParam.geometry as feature>\n            <#if feature.coordinates?has_content>\n                <#list feature.coordinates as coord>\n                    <#assign positions = positions + [ coord ]/>\n                </#list>\n            </#if>\n        </#list>\n    </#if>\n    <#return positions/>\n</#function>\n\n\n<#-- ***************************************  -->\n<#-- Returns if the geometry param defines    -->\n<#-- multiple positions                       -->\n<#-- ***************************************  -->\n<#function multiplePositions geomParam >\n    <#assign positions = toPositions(geomParam) />\n    <#return positions?size gt 1/>\n</#function>\n\n\n<#-- ***************************************  -->\n<#-- Returns if the geometry represents       -->\n<#-- an area, i.e. defined by a polygon       -->\n<#-- ***************************************  -->\n<#function isArea geomParam >\n    <#if geomParam?? && geomParam.geometry?has_content>\n        <#list geomParam.geometry as feature>\n            <#if feature.geometryType?has_content && (feature.geometryType == 'Polygon' || feature.geometryType == 'MultiPolygon')>\n                <#return true/>\n            </#if>\n        </#list>\n    </#if>\n    <#return false/>\n</#function>\n\n\n<#-- ***************************************  -->\n<#-- Renders the geometry parameter           -->\n<#-- as a list of positions                   -->\n<#-- ***************************************  -->\n<#macro renderPositionList geomParam format="dec" plural=false lang='en'>\n    <#setting locale=lang>\n    <#assign positions=toPositions(geomParam)/>\n    <#assign area=isArea(geomParam)/>\n\n    <#if positions?size gt 1>\n        <#if plural>\n            <#switch format>\n                <#case 'audio'>${text('position.in_positions')}<#break>\n                <#case 'navtex'><#break>\n                <#default>${text('position.in_pos')}\n            </#switch>\n        <#else>\n            <#if area>${text('position.in_area')}</#if>\n            <#switch format>\n                <#case 'audio'>${text('position.between_positions')}<#break>\n                <#case 'navtex'><#break>\n                <#default>${text('position.between_pos')}\n            </#switch>\n        </#if>\n    <#elseif positions?size == 1>\n        <#switch format>\n            <#case 'audio'>${text('position.in_position')}<#break>\n            <#case 'navtex'><#break>\n            <#default>${text('position.in_pos')}\n        </#switch>\n    </#if>\n\n    <#list positions as pos>\n        <@formatPos lat=pos.coordinates[1] lon=pos.coordinates[0] format=format decimals=posDecimals/>\n        <#if pos_has_next> ${text('term.and')} </#if>\n    </#list>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders the date                         -->\n<#-- ***************************************  -->\n<#macro renderDate date format="html" lang="en" tz="">\n    <#setting locale=lang>\n    <@dateIntervalFormat date=date format=format tz=tz/>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders the date interval                -->\n<#-- ***************************************  -->\n<#macro renderDateInterval dateInterval format="html" lang="en" tz="">\n    <#setting locale=lang>\n    <@dateIntervalFormat dateInterval=dateInterval format=format tz=tz/>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders the date intervals                -->\n<#-- ***************************************  -->\n<#macro renderDateIntervals dateIntervals format="html" lang="en" tz="" capFirst=false>\n    <#setting locale=lang>\n    <#if dateIntervals?has_content>\n        <@dateIntervalFormat dateIntervals=dateIntervals format=format tz=tz capFirst=capFirst/>\n    </#if>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Returns the given list value             -->\n<#-- ***************************************  -->\n<#function getListValue value defaultValue format='long' lang='en'>\n    <#if value?has_content>\n        <#assign desc=descForLang(value, lang)!>\n        <#if desc?? && format == 'long'>\n            <#return desc.longValue/>\n        <#elseif desc??>\n            <#return desc.value/>\n        <#elseif defaultValue>\n            <#return defaultValue/>\n        </#if>\n    </#if>\n    <#return defaultValue/>\n</#function>\n\n\n<#-- ***************************************  -->\n<#-- Renders the given list value             -->\n<#-- ***************************************  -->\n<#macro renderListValue value defaultValue format='long' lang='en'>\n    ${getListValue(value, defaultValue, format, lang)}\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Renders the AtoN given by the aton_type  -->\n<#-- parameter; capFirst=false where the type -->\n<#-- is printed mid-sentence                  -->\n<#-- ***************************************  -->\n<#macro renderAtonType atonParams defaultName format='long' lang='en' capFirst=true>\n    <#if atonParams?has_content && atonParams.aton_type?has_content>\n        <#assign desc=descForLang(atonParams.aton_type, lang)!>\n        <#if desc?? && format == 'long'>\n            ${capFirst?then(desc.longValue?cap_first, desc.longValue)}\n        <#elseif desc??>\n            ${capFirst?then(desc.value?cap_first, desc.value)}\n        <#else>\n            ${defaultName}\n        </#if>\n    <#else>\n        ${defaultName}\n    </#if>\n    <#if atonParams?has_content && atonParams.aton_name?has_content>\n        ${atonParams.aton_name}\n    </#if>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Returns whether to promulgate the given  -->\n<#-- promulgation type or not                 -->\n<#-- ***************************************  -->\n<#function promulgate type="" >\n    <#if message.promulgations?has_content>\n        <#list message.promulgations as p>\n            <#if p.type?? && p.type.typeId == type>\n                <#return p.promulgate/>\n            </#if>\n        </#list>\n    </#if>\n    <#return false/>\n</#function>\n	templates/tmpl/common.ftl	FM
45	2026-10-22 00:00:00	2026-10-22 00:00:00	0	/*\n * Copyright 2017 Danish Maritime Authority.\n *\n * Licensed under the Apache License, Version 2.0 (the "License");\n * you may not use this file except in compliance with the License.\n * You may obtain a copy of the License at\n *\n *       http://www.apache.org/licenses/LICENSE-2.0\n *\n * Unless required by applicable law or agreed to in writing, software\n * distributed under the License is distributed on an "AS IS" BASIS,\n * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n * See the License for the specific language governing permissions and\n * limitations under the License.\n */\n\n// PORT: messageService was previously supplied as a script binding by JsResourceService; it is now\n// resolved via CdiUtils (the same service-locator the other helpers use) so JsResourceService no longer\n// needs a compile-time dependency on MessageService.\nvar messageService = CdiUtils.getBean(org.niord.core.message.MessageService.class);\n\n/**\n * Adjusts various message fields, such as the title field if auto-title is turned on\n */\nfunction adjustMessage() {\n    // Update auto-title fields, etc.\n    messageService.adjustMessage(message, AdjustmentType.TITLE);\n\n    // If there is only one DETAILS message part, hide the subject\n    var detailParts = message.parts(MessagePartType.DETAILS);\n    if (detailParts.length == 1) {\n        detailParts[0].hideSubject = true;\n    }\n}\n\n\n/**\n * Adjusts the areas of the message from the associated geometry\n */\nfunction adjustAreas() {\n    // If message areas are undefined, compute them from the message geometry.\n    messageService.adjustMessage(message, AdjustmentType.AREAS);\n}\n\n\n/**\n * Updates the promulgate-state of the message promulgations\n */\nfunction updatePromulgations() {\n    if (message.promulgations) {\n        for (var p = 0; p < message.promulgations.length; p++) {\n            var promulgation = message.promulgations[p];\n            promulgation.promulgate = promulgation.promulgationDataDefined();\n        }\n    }\n}\n\n\n/**\n * Resets all Geometry IDs. Can be used if, say, the parts of a message is\n * copied to a new message. Subsequently reset the IDs of the copied geometry\n */\nfunction resetGeometryIds(msg) {\n    if (msg && msg.parts) {\n        for (var x = 0; x < msg.parts.length; x++) {\n            var part = msg.parts[x];\n            if (part.geometry) {\n                part.geometry.id = null;\n                if (part.geometry.features) {\n                    for (var y = 0; y < part.geometry.features.length; y++) {\n                        part.geometry.features[y].id = null;\n                    }\n                }\n            }\n        }\n    }\n    return msg;\n}\n\n\n/**\n * Returns the description entity from the list with the given language.\n * Returns null if not found;\n */\nfunction descForLang(descs, lang) {\n    if (descs !== undefined && lang !== undefined) {\n        for (var x = 0; x < descs.length; x++) {\n            if (descs[x].lang == lang) {\n                return descs[x];\n            }\n        }\n    }\n    return null;\n}\n\n\n/**\n * Copies the specified field from one list of description entities\n * to another\n */\nfunction copyDescField(fromDescs, toDescs, field) {\n    // Copy "vicinity" back to the message\n    if (fromDescs !== undefined && toDescs !== undefined && field !== undefined) {\n        for (var x = 0; x < fromDescs.length; x++) {\n            var fromDesc = fromDescs[x];\n            var toDesc = descForLang(toDescs, fromDesc.lang);\n            if (toDesc !== undefined) {\n                toDesc[field] = fromDesc[field];\n            }\n        }\n    }\n}\n	templates/tmpl/common.js	JS
46	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n\n/*\n * Copyright 2017 Danish Maritime Authority.\n *\n * Licensed under the Apache License, Version 2.0 (the "License");\n * you may not use this file except in compliance with the License.\n * You may obtain a copy of the License at\n *\n *       http://www.apache.org/licenses/LICENSE-2.0\n *\n * Unless required by applicable law or agreed to in writing, software\n * distributed under the License is distributed on an "AS IS" BASIS,\n * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n * See the License for the specific language governing permissions and\n * limitations under the License.\n */\n\n// Add an affected-radius buffer to the geometry\n\norg.niord.core.geojson.GeoJsonUtils.addAtonAffectedRadius(\n    part.geometry,\n    "affected"\n);\n\n	templates/tmpl/compute-affected-radius.js	JS
47	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "obstruction-work.ftl"/>\n\n<@obstructionWork\n    enDetails="diving operations are carried out"\n    enNavtex="DIVING OPERATIONS" />\n	templates/tmpl/diving-operations.ftl	FM
48	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "obstruction-work.ftl"/>\n\n<@obstructionWork\n    enDetails="drilling operations are carried out"\n    enNavtex="DRILLING OPERATION" />\n	templates/tmpl/drilling-opetations.ftl	FM
49	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n\n<#macro renderSubject value lang='en'>\n    <#if value?has_content>\n        <#assign desc=descForLang(value, lang)!>\n        <#if desc??>\n            ${desc.value?cap_first}\n        </#if>\n    </#if>\n</#macro>\n\n\n<#if params.exercise_type??>\n    <#list languages as lang>\n        <field-template field="part.getDesc('${lang}').subject" format="text">\n            <@renderSubject value=params.exercise_type lang=lang/>\n        </field-template>\n    </#list>\n</#if>\n\n<field-template field="part.getDesc('en').details" format="html">\n    <#assign exerciseText=(params.exercise_type??)?then(getListValue(params.exercise_type, 'an exercise', 'long', 'en'), 'an exercise')/>\n    <#if part.eventDates?has_content>\n        <@renderDateIntervals dateIntervals=part.eventDates lang="en" capFirst=true/>\n        ${exerciseText}\n    <#else>\n        ${exerciseText?cap_first}\n    </#if>\n    will take place\n    <@renderPositionList geomParam=part lang="en"/>.\n    Guard vessels will be in the area listening on VHF CH 16.\n    Mariners are requested to pass with caution.\n</field-template>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <@line format="navtex">\n            <@renderDateIntervals dateIntervals=part.eventDates lang="en" format="navtex"/>\n            <#if params.exercise_type??>\n                <@renderListValue value=params.exercise_type lang="en" defaultValue="" format="navtex"/>\n            </#if>\n            <@renderPositionList geomParam=part lang="en" format="navtex"/>.\n            Guard vessels in area listening VHF CH 16.\n            MARINERS REQUESTED TO PASS WITH CAUTION.\n        </@line>\n    </field-template>\n</#if>\n\n<#if promulgate('safetynet')>\n    <field-template field="message.promulgation('safetynet').text" update="append">\n        <@line format="navtex">\n            <@renderDateIntervals dateIntervals=part.eventDates lang="en" format="navtex"/>\n            <#if params.exercise_type??>\n                <@renderListValue value=params.exercise_type lang="en" defaultValue="" format="navtex"/>\n            </#if>\n            <@renderPositionList geomParam=part lang="en" format="navtex"/>.\n            Guard vessels in area listening VHF CH 16.\n            MARINERS REQUESTED TO PASS WITH CAUTION.\n        </@line>\n    </field-template>\n</#if>\n	templates/tmpl/exercise.ftl	FM
50	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "obstruction-found.ftl"/>\n\n<@obstructionFound\n    enDetails="A grenade|The grenade|Grenades|The grenades"\n    enNavtex="GRENADE|GRENADE|GRENADES|GRENADES"\n    />\n	templates/tmpl/grenade.ftl	FM
51	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "obstruction-work.ftl"/>\n\n<@obstructionWork\n    enDetails="hydrographic survey is carried out"\n    enNavtex="HYDROGRAPHIC SURVEY" />\n	templates/tmpl/hydrographic-survey.ftl	FM
52	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n\n<@defaultSubjectFieldTemplates/>\n\n<field-template field="part.getDesc('en').details" format="html">\n    <#list params.positions as pos>\n        <@renderAtonType atonParams=pos defaultName="A light buoy" format="long" lang="en"/>\n        <#if pos.aton_light?has_content>\n            showing\n            <@lightCharacterFormat light=pos.aton_light format="verbose" lang="en"/>\n        </#if>\n        has been established <@renderPositionList geomParam=pos lang="en"/>.<br>\n    </#list>\n</field-template>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <#list params.positions as pos>\n            <@line format="navtex">\n                <@renderAtonType atonParams=pos defaultName="A light buoy" format="short" lang="en"/>\n                <#if pos.aton_light?has_content>\n                    SHOWING\n                    <@lightCharacterFormat light=pos.aton_light format="normal" lang="en"/>\n                </#if>\n                ESTABLISHED <@renderPositionList geomParam=pos format="navtex" lang="en"/>.\n            </@line>\n        </#list>\n    </field-template>\n</#if>\n\n<#if promulgate('safetynet')>\n    <field-template field="message.promulgation('safetynet').text" update="append">\n        <#list params.positions as pos>\n            <@line format="navtex">\n                <@renderAtonType atonParams=pos defaultName="A light buoy" format="short" lang="en"/>\n                <#if pos.aton_light?has_content>\n                    SHOWING\n                    <@lightCharacterFormat light=pos.aton_light format="normal" lang="en"/>\n                </#if>\n                ESTABLISHED <@renderPositionList geomParam=pos format="navtex" lang="en"/>.\n            </@line>\n        </#list>\n    </field-template>\n</#if>\n	templates/tmpl/light-buoy-established.ftl	FM
53	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The light buoy"\n    enDetails="is missing"\n    enNavtex="MISSING"\n    />\n	templates/tmpl/light-buoy-missing.ftl	FM
54	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The light buoy"\n    enDetails="is off station"\n    enNavtex="OFF STATION"\n    />\n	templates/tmpl/light-buoy-off-station.ftl	FM
55	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-replacement-common.ftl"/>\n\n<@atonReplaced\n    enDefaultName="The light buoy"\n    enDefaultReplacementName="a buoy"\n    />\n	templates/tmpl/light-buoy-replaced.ftl	FM
56	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The light buoy"\n    enDetails="is unlit"\n    enNavtex="UNLIT"\n    />\n	templates/tmpl/light-buoy-unlit.ftl	FM
57	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The light buoy"\n    enDetails="is unreliable"\n    enNavtex="UNRELIABLE"\n    />\n	templates/tmpl/light-buoy-unreliable.ftl	FM
58	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<#assign durationEn=(params.duration??)?then(getListValue(params.duration, '', 'normal', 'en'), '')/>\n\n<@aton\n    enDefaultName="The light buoy"\n    enDetails="has been ${durationEn} withdrawn"\n    enNavtex="${durationEn} WITHDRAWN"\n    />\n	templates/tmpl/light-buoy-withdrawn.ftl	FM
59	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The light"\n    enDetails="is damaged"\n    enNavtex="DAMAGED"\n    />\n	templates/tmpl/light-damaged.ftl	FM
60	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n<#include "markings.ftl"/>\n\n<#macro renderLightName lightParam defaultName="">\n    <#if lightParam?? && lightParam.aton_name?has_content>\n        ${lightParam.aton_name}\n    <#else>\n       ${defaultName}\n    </#if>\n</#macro>\n\n<@defaultSubjectFieldTemplates/>\n\n<field-template field="part.getDesc('en').details" format="html">\n    <#list params.positions as pos>\n        The light <@renderLightName lightParam=pos/> <@renderPositionList geomParam=pos lang="en"/>\n        has been destroyed and makes an obstruction.\n    </#list>\n    The foundation of the light is\n    <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="details" unmarkedText="unmarked"/><br>\n    Mariners are advised to keep well clear.\n</field-template>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <@line format="navtex">\n            <#list params.positions as pos>\n                LIGHT <@renderLightName lightParam=pos/> <@renderPositionList geomParam=pos format="navtex" lang="en"/>\n                DESTROYED.\n            </#list>\n            FOUNDATION OF LIGHT\n            <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="navtex"  unmarkedText="UNMARKED"/>\n        </@line>\n        <@line format="navtex">\n            MARINERS ADVISED TO KEEP CLEAR.\n        </@line>\n    </field-template>\n</#if>\n\n<#if promulgate('safetynet')>\n    <field-template field="message.promulgation('safetynet').text" update="append">\n        <@line format="navtex">\n            <#list params.positions as pos>\n                LIGHT <@renderLightName lightParam=pos/> <@renderPositionList geomParam=pos format="navtex" lang="en"/>\n                DESTROYED.\n            </#list>\n            FOUNDATION OF LIGHT\n            <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="navtex"  unmarkedText="UNMARKED"/>\n        </@line>\n        <@line format="navtex">\n            MARINERS ADVISED TO KEEP CLEAR.\n        </@line>\n    </field-template>\n</#if>\n	templates/tmpl/light-destroyed.ftl	FM
61	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The light"\n    enDetails="is unlit"\n    enNavtex="UNLIT"\n    />\n	templates/tmpl/light-unlit.ftl	FM
62	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "aton-common.ftl"/>\n\n<@aton\n    enDefaultName="The light"\n    enDetails="is unreliable"\n    enNavtex="UNRELIABLE"\n    />\n	templates/tmpl/light-unreliable.ftl	FM
63	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#-- ***************************************  -->\n<#-- Renders the list of markings             -->\n<#-- ***************************************  -->\n\n<#macro renderMarkingType marking format='long' lang='en'>\n    <#assign markingType=(marking.type??)?then(getListValue(marking.type, '', format, lang), '')/>\n    <#assign markingColor=(marking.color??)?then(getListValue(marking.color, '', 'normal', lang), '')/>\n    ${markingType?replace('{{color}}', markingColor)}\n</#macro>\n\n\n<#macro renderMarkings markings markingType="buoy" lang="en" format="details" unmarkedText="">\n    <#assign lightFormat=(format == 'navtex')?then('normal','verbose')/>\n    <#assign valueFormat=(format == 'navtex')?then('normal','long')/>\n    <#assign bearingFormat=(format == 'audio')?then('long','normal')/>\n    <#if markings?has_content>\n        ${(markingType == 'buoy')?then('buoyed', 'marked')} with\n        <#list markings as marking>\n            <#if format == 'navtex'>${(marking.color??)?then(getListValue(marking.color, '', 'normal', lang), '')}</#if>\n            <@renderMarkingType marking=marking format=valueFormat lang=lang/>\n            <#if marking.lightCharacter?has_content>\n                showing\n                <@lightCharacterFormat light=marking.lightCharacter format=lightFormat  lang="en"/>\n            </#if>\n            <#-- ?c: no grouping comma, which in NAVTEX reads as a decimal sign; "of the position" only with a\n                 distance over 0 or a bearing, not after a distance of 0 that prints nothing (TMPL14) -->\n            <#if marking.distance?? && marking.distance gt 0>approx. ${marking.distance?c}m</#if>\n            <#if marking.bearing??>\n                <@renderListValue value=marking.bearing defaultValue="" format=bearingFormat lang=lang/>\n            </#if>\n            <#if (marking.distance?? && marking.distance gt 0) || marking.bearing??>of the position</#if>\n            <#if marking?has_next> and <#else>.</#if>\n        </#list>\n    <#else>\n        ${unmarkedText}.\n    </#if>\n</#macro>\n	templates/tmpl/markings.ftl	FM
64	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "obstruction-found.ftl"/>\n\n<@obstructionFound\n    enDetails="A mine|The mine|Mines|The mines"\n    enNavtex="MINE|MINE|MINES|MINES"\n    />\n	templates/tmpl/mine.ftl	FM
65	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n\n<#assign areaLineage = 'org.niord.core.script.directive.AreaLineageDirective'?new()>\n\n\n<#-- ***************************************  -->\n<#-- Generates the NAVTEX header line         -->\n<#-- ***************************************  -->\n<#macro navtexHeader lang="en">\n    <@areaLineage message=message lang=lang format="navtex"/>\n</#macro>\n\n\n<#-- ***************************************  -->\n<#-- Generates the NAVTEX footer lines        -->\n<#-- ***************************************  -->\n<#macro navtexFooter>\n    <#if message.references?has_content>\n        <#list message.references as ref>\n            <#if ref.type == 'CANCELLATION'>\n                <@line format="navtex">\n                    ${text('cancellation.message',ref.messageId)}.\n                </@line>\n            </#if>\n        </#list>\n    </#if>\n\n    <#if message.publishDateTo?has_content>\n        <@line format="navtex">\n            <#assign cancelDate><@navtexDateFormat date=message.publishDateTo /></#assign>\n            ${text('cancellation.this_message',cancelDate)}.\n        </@line>\n    </#if>\n</#macro>\n\n\n<#if promulgation.text?has_content>\n    <#setting locale='en'>\n    <field-template field="promulgation.text">\n<@navtexHeader/>\n\n${promulgation.text}\n\n<@navtexFooter/>\n    </field-template>\n</#if>\n	templates/tmpl/navtex.ftl	FM
66	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n<#include "markings.ftl"/>\n\n<#macro renderObject defaultName format='long' lang='en' capFirst=false>\n    <#assign result=defaultName!''/>\n    <#if params.object?has_content>\n        <#assign desc=descForLang(params.object, lang)!>\n        <#if desc?? && format == 'long'>\n            <#assign result=desc.longValue/>\n        <#elseif desc??>\n            <#assign result=desc.value/>\n        </#if>\n    </#if>\n    <#if capFirst>\n        <#assign result=result?cap_first/>\n    </#if>\n    ${result}\n</#macro>\n\n<#-- The list values whose English name is plural; a deployment that adds its own adds their keys here -->\n<#assign pluralObjects=['object.adrift.logs', 'object.adrift.fishing_stakes']/>\n<#assign objectVerb=(params.object?? && params.object.key?? && pluralObjects?seq_contains(params.object.key))?then('have', 'has')/>\n\n\n<#-- The subject is one line (TMPL15): renderObject prints its name on a line of its own, so it is captured and trimmed -->\n<#assign objectName><@renderObject defaultName="Object" format="short" lang="en" capFirst=true/></#assign>\n<field-template field="part.getDesc('en').subject" format="text">\n    ${objectName?trim} adrift\n</field-template>\n\n<field-template field="part.getDesc('en').details" format="html">\n    <@renderObject defaultName="an object" format="long" lang="en" capFirst=true/>\n    ${objectVerb} been observed adrift <@renderPositionList geomParam=part lang="en"/>\n    <#if params.date??><@renderDate date=params.date lang="en" tz="UTC"/></#if>.\n    <#if params.cancelDate??>\n        <p>Cancel this warning <@renderDate date=params.cancelDate lang="en" tz="UTC"/>.</p>\n    </#if>\n</field-template>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <@line format="navtex">\n            <@renderObject defaultName="an object" format="short" lang="en" capFirst=true/>\n            ADRIFT <@renderPositionList geomParam=part format="navtex" lang="en"/>\n            <#if params.date??><@renderDate date=params.date lang="en" format="navtex"/></#if>.\n        </@line>\n    </field-template>\n</#if>\n\n<#if promulgate('safetynet')>\n    <field-template field="message.promulgation('safetynet').text" update="append">\n        <@line format="navtex">\n            <@renderObject defaultName="an object" format="short" lang="en" capFirst=true/>\n            ADRIFT <@renderPositionList geomParam=part format="navtex" lang="en"/>\n            <#if params.date??><@renderDate date=params.date lang="en" format="navtex"/></#if>.\n        </@line>\n    </field-template>\n</#if>\n	templates/tmpl/object-adrift.ftl	FM
67	2026-10-22 00:00:00	2026-10-22 00:00:00	0	/*\n * Copyright 2017 Danish Maritime Authority.\n *\n * Licensed under the Apache License, Version 2.0 (the "License");\n * you may not use this file except in compliance with the License.\n * You may obtain a copy of the License at\n *\n *       http://www.apache.org/licenses/LICENSE-2.0\n *\n * Unless required by applicable law or agreed to in writing, software\n * distributed under the License is distributed on an "AS IS" BASIS,\n * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n * See the License for the specific language governing permissions and\n * limitations under the License.\n */\n\n// If an observation date is defined, define the publish date interval\n// and event date interval based on the observation date\nif (params.get('date') !== null) {\n    var date = new java.util.Date(params.get('date'));\n\n    // Cancel message 12 hours after observation\n    var offset = 12 * 60 * 60 * 1000;\n    var cancelDate = new java.util.Date(params.get('date') + offset);\n    params.put('cancelDate', cancelDate);\n\n    // Update publication dates - assume manual publication, so, ignore start date\n    //message.publishDateFrom = date;\n    message.publishDateTo = cancelDate;\n\n    // Update event dates\n    var part = message.part(MessagePartType.DETAILS);\n    if (part !== undefined) {\n        var di = new org.niord.model.message.DateIntervalVo();\n        di.allDay = false;\n        di.fromDate = date;\n        di.toDate = cancelDate;\n        part.eventDates = new java.util.ArrayList();\n        part.eventDates.add(di);\n    }\n}\n	templates/tmpl/object-adrift.js	JS
68	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n\n<#macro obstructionFound enDetails="" enNavtex="">\n\n    <#assign enObj=enDetails?split("|")/>\n    <#assign navtexObj=enNavtex?split("|")/>\n    <#assign offset=multiplePositions(part)?then(2,0)/>\n    <#assign verb=multiplePositions(part)?then('have', 'has')/>\n\n    <@defaultSubjectFieldTemplates/>\n\n    <field-template field="part.getDesc('en').details" format="html">\n        ${enObj[0+offset]?cap_first} ${verb} been reported\n        <@renderPositionList geomParam=part lang="en" plural=true/>.\n        ${enObj[1+offset]?cap_first} may be dangerous.<br>\n        Mariners are advised to keep well clear.\n    </field-template>\n\n    <#if promulgate('navtex')>\n        <field-template field="message.promulgation('navtex').text" update="append">\n            <@line format="navtex">\n                ${navtexObj[0+offset]} REPORTED FOUND\n                <@renderPositionList geomParam=part lang="en" format="navtex" plural=true/>.\n                ${navtexObj[1+offset]} MAY BE DANGEROUS.\n            </@line>\n            <@line format="navtex">\n                MARINERS ADVISED TO KEEP CLEAR.\n            </@line>\n        </field-template>\n    </#if>\n\n    <#if promulgate('safetynet')>\n        <field-template field="message.promulgation('safetynet').text" update="append">\n            <@line format="navtex">\n                ${navtexObj[0+offset]} REPORTED FOUND\n                <@renderPositionList geomParam=part lang="en" format="navtex" plural=true/>.\n                ${navtexObj[1+offset]} MAY BE DANGEROUS.\n            </@line>\n            <@line format="navtex">\n                MARINERS ADVISED TO KEEP CLEAR.\n            </@line>\n        </field-template>\n    </#if>\n\n</#macro>\n	templates/tmpl/obstruction-found.ftl	FM
69	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n<#include "work-vessel.ftl"/>\n\n<#macro obstructionWork enDetails="" enNavtex="">\n\n    <@defaultSubjectFieldTemplates/>\n\n    <field-template field="part.getDesc('en').details" format="html">\n        <#if part.eventDates?has_content>\n            <@renderDateIntervals dateIntervals=part.eventDates lang="en" capFirst=true/>\n            ${enDetails}\n        <#else>\n            ${enDetails?cap_first}\n        </#if>\n        <@renderPositionList geomParam=part lang="en"/>.\n        <@renderWorkVessel vessel=params.vessel! lang="en" format="details"/>\n    </field-template>\n\n    <#if promulgate('navtex')>\n        <field-template field="message.promulgation('navtex').text" update="append">\n            <@line format="navtex">\n                <@renderDateIntervals dateIntervals=part.eventDates lang="en" format="navtex"/>\n                ${enNavtex}\n                <@renderPositionList geomParam=part lang="en" format="navtex"/>.\n                <@renderWorkVessel vessel=params.vessel! lang="en" format="navtex"/>\n            </@line>\n        </field-template>\n    </#if>\n\n    <#if promulgate('safetynet')>\n        <field-template field="message.promulgation('safetynet').text" update="append">\n            <@line format="navtex">\n                <@renderDateIntervals dateIntervals=part.eventDates lang="en" format="navtex"/>\n                ${enNavtex}\n                <@renderPositionList geomParam=part lang="en" format="navtex"/>.\n                <@renderWorkVessel vessel=params.vessel! lang="en" format="navtex"/>\n            </@line>\n        </field-template>\n    </#if>\n\n</#macro>\n	templates/tmpl/obstruction-work.ftl	FM
70	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n\n<#-- The template serves the RACON and the AIS category. The category it runs for decides, by its own AIS parameter\n     (type or id 'ais'), not whether the optional AIS status was filled in (TMPL9); a category without one falls\n     back to the status. Null-safe: a parameter without a type or an id must not fail the template. -->\n<#assign aisCategory = template?? && template.templateParams?? && template.templateParams?filter(p -> ((p.type)!'') == 'ais' || ((p.paramId)!'') == 'ais')?has_content/>\n\n<#function computeRadioType statusParam >\n    <#if aisCategory || statusParam.ais_status??>\n        <#return 'AIS' />\n    </#if>\n    <#return 'RACON' />\n</#function>\n\n<#macro renderStatus statusParam format='long' lang='en'>\n    <#assign type=computeRadioType(statusParam)/>\n    <#if type == 'AIS'>\n        <#assign status=statusParam.ais_status! />\n    <#else>\n        <#assign status=statusParam.racon_status! />\n    </#if>\n    <#if status??>\n        <#assign desc=descForLang(status, lang)!>\n        <#if desc?? && desc.longValue?has_content && format == 'long'>\n            ${desc.longValue}\n        <#elseif desc?? && desc.value?has_content>\n            ${desc.value}\n        </#if>\n    </#if>\n</#macro>\n\n<#-- The subject is one line (TMPL15): the positions are joined by a space, and the line break the status macro\n     prints before the full stop is taken out by the field template's clean-up of space before a full stop. The #if\n     keeps the subject of a warning without a position as empty as it was. -->\n<field-template field="part.getDesc('en').subject" format="text">\n    <#if (params.positions![])?has_content>\n    <#list params.positions as pos>${computeRadioType(pos)} <@renderStatus statusParam=pos format="normal" lang="en"/>.<#sep> </#list>\n    </#if>\n</field-template>\n\n<field-template field="part.getDesc('en').details" format="html">\n    <#list params.positions![] as pos>\n        ${computeRadioType(pos)} on\n        <@renderAtonType atonParams=pos defaultName="AtoN" format="long" lang="en" capFirst=false/>\n        <@renderPositionList geomParam=pos lang="en"/>\n        <@renderStatus statusParam=pos format="long" lang="en"/>.<br>\n    </#list>\n</field-template>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <#list params.positions![] as pos>\n            <@line format="navtex">\n                ${computeRadioType(pos)} on\n                <@renderAtonType atonParams=pos defaultName="AtoN" format="short" lang="en"/>\n                <@renderPositionList geomParam=pos format="navtex" lang="en"/>\n                <@renderStatus statusParam=pos format="normal" lang="en"/>.\n            </@line>\n        </#list>\n    </field-template>\n</#if>\n\n<#if promulgate('safetynet')>\n    <field-template field="message.promulgation('safetynet').text" update="append">\n        <#list params.positions![] as pos>\n            <@line format="navtex">\n                ${computeRadioType(pos)} on\n                <@renderAtonType atonParams=pos defaultName="AtoN" format="short" lang="en"/>\n                <@renderPositionList geomParam=pos format="navtex" lang="en"/>\n                <@renderStatus statusParam=pos format="normal" lang="en"/>.\n            </@line>\n        </#list>\n    </field-template>\n</#if>\n	templates/tmpl/radio-navigation.ftl	FM
71	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n\n<#-- The water depth as it is printed, or empty when none was entered (TMPL8). Missing and an empty or blank text\n     count as not entered; a number prints without a thousands separator, a text as it was typed (without ?c,\n     which would quote it). -->\n<#assign waterDepth = ''/>\n<#if params.water_depth?has_content>\n    <#assign waterDepth = params.water_depth?is_number?then(params.water_depth?c, params.water_depth?string?trim)/>\n</#if>\n\n<@defaultSubjectFieldTemplates/>\n\n<field-template field="part.getDesc('en').details" format="html">\n    <#setting locale="en">\n    <#if waterDepth?has_content>Water depths down to ${waterDepth}m<#else>Reduced water depths</#if>\n    have been observed\n    <@renderPositionList geomParam=part lang="en"/>\n    <#if params.locality?has_content>in the entrance to ${params.locality}</#if>.<br>\n    Mariners are advised to keep well clear.\n</field-template>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <#setting locale="en">\n        <@line format="navtex">\n            <#if waterDepth?has_content>Water depths down to ${waterDepth}M<#else>REDUCED WATER DEPTHS</#if> OBSERVED\n            <@renderPositionList geomParam=part format="navtex" lang="en"/>\n            <#if params.locality?has_content>IN ENTRANCE TO ${params.locality}</#if>.\n        </@line>\n        <@line format="navtex">\n            MARINERS ADVISED TO KEEP CLEAR.\n        </@line>\n    </field-template>\n</#if>\n\n<#if promulgate('safetynet')>\n    <field-template field="message.promulgation('safetynet').text" update="append">\n        <#setting locale="en">\n        <@line format="navtex">\n            <#if waterDepth?has_content>Water depths down to ${waterDepth}M<#else>REDUCED WATER DEPTHS</#if> OBSERVED\n            <@renderPositionList geomParam=part format="navtex" lang="en"/>\n            <#if params.locality?has_content>IN ENTRANCE TO ${params.locality}</#if>.\n        </@line>\n        <@line format="navtex">\n            MARINERS ADVISED TO KEEP CLEAR.\n        </@line>\n    </field-template>\n</#if>\n	templates/tmpl/reduced-depth.ftl	FM
72	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n\n\n<#-- ***************************************  -->\n<#-- Generates the SafetyNET footer lines     -->\n<#-- ***************************************  -->\n<#macro safetynetFooter>\n    <#if message.publishDateTo?has_content>\n        <@line format="navtex">\n            <#assign cancelDate><@navtexDateFormat date=message.publishDateTo /></#assign>\n            ${text('cancellation.this_message',cancelDate)}.\n        </@line>\n    </#if>\n</#macro>\n\n\n<#if promulgation.text?has_content>\n    <#setting locale='en'>\n    <field-template field="promulgation.text">\n\n${promulgation.text}\n\n<@safetynetFooter/>\n    </field-template>\n</#if>\n	templates/tmpl/safetynet.ftl	FM
73	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n<#include "work-vessel.ftl"/>\n\n<@defaultSubjectFieldTemplates/>\n\n<field-template field="part.getDesc('en').details" format="html">\n    <#if part.eventDates?has_content>\n        <@renderDateIntervals dateIntervals=part.eventDates lang="en" capFirst=true/>\n        salvage operations are carried out\n    <#else>\n        Salvage operations are carried out\n    </#if>\n    <#if params.salvage_type??>\n        on <@renderListValue value=params.salvage_type defaultValue="" lang="en"/>\n    </#if>\n    <@renderPositionList geomParam=part lang="en"/>.\n    <@renderWorkVessel vessel=params.vessel! lang="en" format="details"/>\n</field-template>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <@line format="navtex">\n            <@renderDateIntervals dateIntervals=part.eventDates lang="en" format="navtex"/>\n            SALVAGE OPERATION\n            <#if params.salvage_type??>\n                ON <@renderListValue value=params.salvage_type lang="en" defaultValue="" format="navtex"/>\n            </#if>\n            <@renderPositionList geomParam=part lang="en" format="navtex"/>.\n            <@renderWorkVessel vessel=params.vessel! lang="en" format="navtex"/>\n        </@line>\n    </field-template>\n</#if>\n\n<#if promulgate('safetynet')>\n    <field-template field="message.promulgation('safetynet').text" update="append">\n        <@line format="navtex">\n            <@renderDateIntervals dateIntervals=part.eventDates lang="en" format="navtex"/>\n            SALVAGE OPERATION\n            <#if params.salvage_type??>\n                ON <@renderListValue value=params.salvage_type lang="en" defaultValue="" format="navtex"/>\n            </#if>\n            <@renderPositionList geomParam=part lang="en" format="navtex"/>.\n            <@renderWorkVessel vessel=params.vessel! lang="en" format="navtex"/>\n        </@line>\n    </field-template>\n</#if>\n	templates/tmpl/salvage-operations.ftl	FM
74	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "obstruction-found.ftl"/>\n\n<@obstructionFound\n    enDetails="A torpedo|The torpedo|Torpedoes|The torpedoes"\n    enNavtex="TORPEDO|TORPEDO|TORPEDOES|TORPEDOES"\n    />\n	templates/tmpl/torpedo.ftl	FM
75	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<field-template field="message.promulgation('twitter').tweet" format="text">\n    ${r"${title:en}"}\n</field-template>\n\n<field-template field="message.promulgation('twitter').promulgate" format="boolean">\n    true\n</field-template>\n\n	templates/tmpl/twitter.ftl	FM
76	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n<#include "markings.ftl"/>\n\n<#-- A number prints without a grouping comma, which in NAVTEX reads as a decimal sign ("1,250M"); a text as it was\n     typed (?c would quote it) (TMPL14) -->\n<#function plainNumber value>\n    <#return value?is_number?then(value?c, value?string)/>\n</#function>\n\n<#macro renderObstructionType defaultValue='obstruction' format='normal' lang='en' capFirst=false>\n    <#assign obstructionType=(params.obstruction_type??)?then(getListValue(params.obstruction_type, defaultValue, format, lang), defaultValue) />\n    <#if capFirst>\n        <#assign obstructionType=obstructionType?cap_first/>\n    </#if>\n    ${obstructionType}\n</#macro>\n\n<#macro renderObstructionReport defaultValue='reported' lang='en'>\n    <#if params.obstruction_report??>\n        <@renderListValue value=params.obstruction_report defaultValue=defaultValue format="normal" lang=lang />\n    <#else>\n        ${defaultValue}\n    </#if>\n</#macro>\n\n\n<field-template field="part.getDesc('en').subject" format="text">\n    Uncharted <@renderObstructionType defaultValue="Obstruction" lang="en" capFirst=true/>\n</field-template>\n\n<field-template field="part.getDesc('en').details" format="html">\n    An uncharted <@renderObstructionType defaultValue="obstruction" lang="en"/>\n    is <@renderObstructionReport defaultValue="reported" lang="en" />\n    <@renderPositionList geomParam=part lang="en"/>.\n    <#if params.obstruction_visible!false>\n        <@renderObstructionType defaultValue="The obstruction" format="long" lang="en" capFirst=true/> is visible above the sea surface.\n    <#else>\n        The depth above <@renderObstructionType defaultValue="the obstruction" format="long" lang="en"/> is\n        <#if params.obstruction_depth??>${plainNumber(params.obstruction_depth)}m.<#else>unknown.</#if>\n    </#if>\n    <@renderObstructionType defaultValue="The obstruction" format="long" lang="en" capFirst=true/>\n    is <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="details" unmarkedText="unmarked"/><br>\n    Mariners are advised to keep well clear.\n</field-template>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <@line format="navtex">\n            UNCHARTED <@renderObstructionType defaultValue="OBSTRUCTION" lang="en"/>\n            <@renderObstructionReport defaultValue="REPORTED" lang="en" />\n            <@renderPositionList geomParam=part format="navtex" lang="en"/>.\n        </@line>\n        <@line format="navtex">\n            <#if params.obstruction_visible!false>\n                <@renderObstructionType defaultValue="OBSTRUCTION" lang="en" /> VISIBLE ABOVE SEA SURFACE.\n            <#else>\n                DEPTH ABOVE <@renderObstructionType defaultValue="OBSTRUCTION" lang="en"/>\n                <#if params.obstruction_depth??>${plainNumber(params.obstruction_depth)}M.<#else>UNKNOWN.</#if>\n            </#if>\n        </@line>\n        <@line format="navtex">\n            <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="navtex"  unmarkedText="UNMARKED"/>\n        </@line>\n        <@line format="navtex">\n            MARINERS ADVISED TO KEEP CLEAR.\n        </@line>\n    </field-template>\n</#if>\n\n<#if promulgate('safetynet')>\n    <field-template field="message.promulgation('safetynet').text" update="append">\n        <@line format="navtex">\n            UNCHARTED <@renderObstructionType defaultValue="OBSTRUCTION" lang="en"/>\n            <@renderObstructionReport defaultValue="REPORTED" lang="en" />\n            <@renderPositionList geomParam=part format="navtex" lang="en"/>.\n        </@line>\n        <@line format="navtex">\n            <#if params.obstruction_visible!false>\n                <@renderObstructionType defaultValue="OBSTRUCTION" lang="en" /> VISIBLE ABOVE SEA SURFACE.\n            <#else>\n                DEPTH ABOVE <@renderObstructionType defaultValue="OBSTRUCTION" lang="en"/>\n                <#if params.obstruction_depth??>${plainNumber(params.obstruction_depth)}M.<#else>UNKNOWN.</#if>\n            </#if>\n        </@line>\n        <@line format="navtex">\n            <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="navtex"  unmarkedText="UNMARKED"/>\n        </@line>\n        <@line format="navtex">\n            MARINERS ADVISED TO KEEP CLEAR.\n        </@line>\n    </field-template>\n</#if>\n	templates/tmpl/uncharted-obstruction.ftl	FM
77	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "obstruction-work.ftl"/>\n\n<@obstructionWork\n    enDetails="underwater survey is carried out"\n    enNavtex="UNDERWATER SURVEY" />\n\n	templates/tmpl/underwater-survey.ftl	FM
78	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n/*\n * Copyright 2017 Danish Maritime Authority.\n *\n * Licensed under the Apache License, Version 2.0 (the "License");\n * you may not use this file except in compliance with the License.\n * You may obtain a copy of the License at\n *\n *       http://www.apache.org/licenses/LICENSE-2.0\n *\n * Unless required by applicable law or agreed to in writing, software\n * distributed under the License is distributed on an "AS IS" BASIS,\n * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n * See the License for the specific language governing permissions and\n * limitations under the License.\n */\n\nload('niord:templates/tmpl/common.js');\n\nadjustAreas();\n	templates/tmpl/update-areas.js	JS
79	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#-- ***************************************  -->\n<#-- Renders information about a work vessel  -->\n<#-- ***************************************  -->\n\n<#macro renderWorkVessel vessel lang="en" format="details">\n    <#setting locale=lang>\n\n    <#if vessel?has_content && (vessel.name?has_content || vessel.callSign?has_content)>\n\n        <#assign csFormat=(format == 'audio')?then('verbose','normal')/>\n\n        <#if lang == "en">\n\n            <#if vessel.name?has_content>\n                <#if format == 'navtex'>\n                    By <@quote text=vessel.name/>\n                <#elseif format == 'audio'>\n                    Work is carried out by <@quote text=vessel.name/>\n                <#else>\n                    Work is carried out by <@quote text=vessel.name format="angular"/>\n                </#if>\n            <#elseif format == 'navtex'>\n                By vessel with\n            <#else>\n                Work is carried out by the vessel with\n            </#if>\n            <#if vessel.callSign?has_content>call sign <@callSignFormat callSign=vessel.callSign format=csFormat/></#if>.\n            <#if vessel.guardVessels!false>\n                <#if format == 'navtex'>\n                    Guard vessels in area.\n                <#else>\n                    Guard vessels will be in the area.\n                </#if>\n            </#if>\n            <#if vessel.contact!false>\n                <#if format == 'navtex'>\n                    Listening VHF CH 16\n                    <#if vessel.channel?has_content>and ${vessel.channel}</#if>.\n                <#else>\n                    The <#if vessel.guardVessels!false>vessels are<#else>vessel is</#if>\n                    listening on VHF channel 16\n                    <#if vessel.channel?has_content>and ${vessel.channel}</#if>.\n                </#if>\n            </#if>\n        </#if>\n    </#if>\n\n    <#-- The minimum distance without a grouping comma, which in NAVTEX reads as a decimal sign ("1,000M"); a text as\n         it was typed (?c would quote it) (TMPL14) -->\n    <#if vessel?has_content && vessel.minDist?has_content>\n        <#local minDist=vessel.minDist?is_number?then(vessel.minDist?c, vessel.minDist?string)/>\n    </#if>\n    <#if format == 'navtex'>\n        MARINERS REQUESTED TO PASS WITH CAUTION\n        <#if vessel.minDist?has_content>\n            KEEPING MINIMUM DISTANCE ${minDist}${(vessel.minDistType!'m')?upper_case}\n        </#if>.\n    <#else>\n        Mariners are requested to pass with caution\n        <#if vessel.minDist?has_content>\n            and keep a minimum distance of ${minDist}${(vessel.minDistType!'m')}\n        </#if>.\n    </#if>\n</#macro>\n	templates/tmpl/work-vessel.ftl	FM
80	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n<#include "markings.ftl"/>\n\n<#-- A number prints without a grouping comma, which in NAVTEX reads as a decimal sign ("1,250M"); a text as it was\n     typed (?c would quote it) (TMPL14) -->\n<#function plainNumber value>\n    <#return value?is_number?then(value?c, value?string)/>\n</#function>\n\n<#macro renderVesselType defaultName format='long' lang='en'>\n    <#if params.wreck_type?has_content>\n        <#assign desc=descForLang(params.wreck_type, lang)!>\n        <#if desc?? && format == 'long'>\n            ${desc.longValue?cap_first}\n        <#elseif desc??>\n            ${desc.value?cap_first}\n        <#else>\n            ${defaultName?cap_first}\n        </#if>\n    <#else>\n        ${defaultName?cap_first}\n    </#if>\n</#macro>\n\n<field-template field="part.getDesc('en').subject" format="text">\n    <#switch params.markingType!''>\n        <#case 'marking'>Marked Wreck<#break>\n        <#case 'buoy'>Buoyed Wreck<#break>\n        <#default>Unmarked Wreck\n    </#switch>\n</field-template>\n\n<field-template field="part.getDesc('en').details" format="html">\n    <@renderVesselType defaultName="a vessel" format="long" lang="en"/>\n    has sunk <@renderPositionList geomParam=part lang="en"/>.\n    <#if params.wreck_visible!false>\n        The wreck is visible above the sea surface.\n    <#else>\n        The depth above the wreck is <#if params.wreck_depth??>${plainNumber(params.wreck_depth)}m.<#else>unknown.</#if>\n    </#if>\n    The wreck is <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="details" unmarkedText="unmarked"/><br>\n    Mariners are advised to keep well clear.\n</field-template>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <@line format="navtex">\n            <@renderVesselType defaultName="A VESSEL" format="short" lang="en"/>\n            SUNK <@renderPositionList geomParam=part format="navtex" lang="en"/>.\n            <#if params.wreck_visible!false>\n                WRECK VISIBLE ABOVE SEA SURFACE.\n            <#else>\n                DEPTH ABOVE WRECK <#if params.wreck_depth??>${plainNumber(params.wreck_depth)}M.<#else>UNKNOWN.</#if>\n            </#if>\n            WRECK <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="navtex"  unmarkedText="UNMARKED"/>\n        </@line>\n        <@line format="navtex">\n            MARINERS ADVISED TO KEEP CLEAR.\n        </@line>\n    </field-template>\n</#if>\n\n<#if promulgate('safetynet')>\n    <field-template field="message.promulgation('safetynet').text" update="append">\n        <@line format="navtex">\n            <@renderVesselType defaultName="A VESSEL" format="short" lang="en"/>\n            SUNK <@renderPositionList geomParam=part format="navtex" lang="en"/>.\n            <#if params.wreck_visible!false>\n                WRECK VISIBLE ABOVE SEA SURFACE.\n            <#else>\n                DEPTH ABOVE WRECK <#if params.wreck_depth??>${plainNumber(params.wreck_depth)}M.<#else>UNKNOWN.</#if>\n            </#if>\n            WRECK <@renderMarkings markings=params.markings! markingType=params.markingType! lang="en" format="navtex"  unmarkedText="UNMARKED"/>\n        </@line>\n        <@line format="navtex">\n            MARINERS ADVISED TO KEEP CLEAR.\n        </@line>\n    </field-template>\n</#if>\n	templates/tmpl/wreck.ftl	FM
1000001	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n<#include "../messages/message-support.ftl"/>\n\n<#setting time_zone="Europe/Copenhagen">\n\n<html>\n<head>\n    <link rel="stylesheet" type="text/css" href="/css/templates/mail.css">\n</head>\n<body>\n\n<h1>\n    Danske farvandsefterretninger ${.now?string["EEEEEEE 'den' d. MMMMMMMMM yyyy"]}\n</h1>\n\n<h2>\n    Her er Forsvarets Operationscenter med dagens farvandsefterretninger\n</h2>\n\n<#list messages as msg>\n    <#assign audio=promulgation(msg, 'audio')!>\n    <#if audio?? && audio.text?has_content>\n    <p>\n        <#if msg.areas?has_content>\n            <u>\n                <@areaLineage area=msg.areas[0] />\n                <#assign msgDesc=descForLang(msg, language)!>\n                <#if msgDesc?? && msgDesc.vicinity?has_content>\n                    - ${msgDesc.vicinity}\n                </#if>\n            </u>\n            <br>\n        </#if>\n        <@txtToHtml text=audio.text/>\n    </p>\n    </#if>\n</#list>\n\n<#if params['firingExercises']?? && params['firingExercisesDate']??>\n<h2>\n    Der afholdes i morgen\n    ${params['firingExercisesDate']?string["EEEEEEE 'den' d. MMMMMMMMM yyyy"]}\n    følgende skydeøvelser:\n</h2>\n\n    <#list params['firingExercises'] as fe>\n    <p>\n        Ved <@areaLineage area=fe.area />\n        <#list fe.times as t>\n            <#if !t?is_first>og igen</#if>\n            fra kl ${t.fromDate?string["HHmm"]}\n            til kl ${t.toDate?string["HHmm"]}<#if t?is_last>.</#if>\n        </#list>\n    </p>\n    </#list>\n</#if>\n\n<p>\n    Det var farvandsefterretningerne.\n</p>\n\n</body>\n</html>\n	templates/mailinglist/audio-broadcast.ftl	FM
1000002	2026-10-22 00:00:00	2026-10-22 00:00:00	0	/*\n * Copyright 2017 Danish Maritime Authority.\n *\n * Licensed under the Apache License, Version 2.0 (the "License");\n * you may not use this file except in compliance with the License.\n * You may obtain a copy of the License at\n *\n *       http://www.apache.org/licenses/LICENSE-2.0\n *\n * Unless required by applicable law or agreed to in writing, software\n * distributed under the License is distributed on an "AS IS" BASIS,\n * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n * See the License for the specific language governing permissions and\n * limitations under the License.\n */\n\n\n\nvar messageService = CdiUtils.getBean(org.niord.core.message.MessageSearchService.class);\n\nvar date = java.util.Calendar.getInstance();\ndate.add(java.util.Calendar.DAY_OF_YEAR, 1);\nparams.put('firingExercisesDate', date.getTime());\n\nvar timeZone = java.util.TimeZone.getTimeZone('Europe/Copenhagen');\nvar fromDate = org.niord.core.util.TimeUtils.resetTime(date.getTime(), timeZone);\nvar toDate = org.niord.core.util.TimeUtils.endOfDay(date.getTime(), timeZone);\n\nvar seriesIds = java.util.Collections.singleton('dma-fe');\nvar statuses = java.util.Collections.singleton(org.niord.model.message.Status.PUBLISHED);\nvar searchParams = new org.niord.core.message.MessageSearchParams();\nsearchParams.seriesIds(seriesIds)\n    .statuses(statuses)\n    .dateType(org.niord.core.message.MessageSearchParams$DateType.EVENT_DATE)\n    .from(fromDate)\n    .to(toDate);\n\n// searchOrFail, not search: a failed search fails the trigger, which the scheduler postpones, where search would\n// answer it with an empty list and the mail would tell its readers that there are no firing exercises tomorrow\nvar searchResult = messageService.searchOrFail(searchParams);\n\nvar areaFilter = org.niord.model.DataFilter.get().fields('DETAILS').lang(language);\n\nvar result = new java.util.ArrayList();\nfor (var x = 0; x < searchResult.size; x++) {\n    var fe = searchResult.data[x];\n    var data = new java.util.HashMap();\n    if (fe.areas === undefined || fe.areas.length === 0) {\n        continue;\n    }\n    data.put('area', fe.areas[0].toVo(org.niord.model.message.AreaVo.class, areaFilter));\n\n    var times = new java.util.ArrayList();\n    for (var p = 0; p < fe.parts.length; p++) {\n        var part = fe.parts[p];\n        if (part.eventDates && part.eventDates.length > 0) {\n            for (var d = 0; d < part.eventDates.length; d++) {\n                var evtDate = part.eventDates[d];\n                if (evtDate.fromDate !== undefined && evtDate.toDate !== undefined &&\n                    evtDate.fromDate.before(toDate) &&\n                    evtDate.toDate.after(fromDate)) {\n                    times.add(evtDate.toVo());\n                }\n            }\n        }\n    }\n    if (times.isEmpty()) {\n        continue;\n    }\n\n    data.put('times', times);\n    result.add(data);\n}\n\nparams.put("firingExercises", result);\n\n	templates/mailinglist/fetch-firing-exercises.js	JS
1000003	2026-10-22 00:00:00	2026-10-22 00:00:00	0	<#include "common.ftl"/>\n\n<#macro renderStatus format='long' lang='en'>\n    <#if params.dgps_status?has_content>\n        <#assign desc=descForLang(params.dgps_status, lang)!>\n        <#if desc?? && format == 'long'>\n        ${desc.longValue}\n        <#elseif desc??>\n        ${desc.value}\n        </#if>\n    </#if>\n</#macro>\n\n<#macro renderStationName format='long' lang='en'>\n    <#if params.dgps_station?has_content>\n        <#assign desc=descForLang(params.dgps_station, lang)!>\n        <#if desc??>\n        ${desc.value}\n        </#if>\n    </#if>\n</#macro>\n\n<#if params.dgps_station?? && params.dgps_station.key??>\n    <#assign key=params.dgps_station.key>\n\n<#if languages?seq_contains('da')>\n<field-template field="part.getDesc('da').subject" format="text">\n    DGPS <@renderStatus format="normal" lang="da"/>\n</field-template>\n</#if>\n\n<field-template field="part.getDesc('en').subject" format="text">\n    DGPS <@renderStatus format="normal" lang="en"/>\n</field-template>\n\n<#if languages?seq_contains('da')>\n<field-template field="part.getDesc('da').details" format="html">\n    <#setting locale='da'>\n    <@renderStationName format="long" lang="da"/> DGPS-station\n    på pos. <@formatPos lat=params.station.coordinates[1] lon=params.station.coordinates[0] format='dec-1' />\n    og frekvens ${params.station.frequency?string["0.0"]} kHz\n    <@renderStatus format="long" lang="da"/>\n    <#if part.eventDates?has_content && part.eventDates[0].fromDate??>\n        <@renderDateIntervals dateIntervals=part.eventDates lang="da"/>\n    </#if>.\n</field-template>\n</#if>\n\n<field-template field="part.getDesc('en').details" format="html">\n    <#setting locale='en'>\n    <@renderStationName format="long" lang="en"/> DGPS-station\n    in pos. <@formatPos lat=params.station.coordinates[1] lon=params.station.coordinates[0] format='dec-1' />\n    and frequency ${params.station.frequency?string["0.0"]} kHz\n    <@renderStatus format="long" lang="en"/>\n    <#if part.eventDates?has_content && part.eventDates[0].fromDate??>\n        <@renderDateIntervals dateIntervals=part.eventDates lang="en" tz="UTC"/>\n    </#if>.\n</field-template>\n\n<#if promulgate('audio')>\n    <field-template field="message.promulgation('audio').text" update="append">\n        <#setting locale='da'>\n        <@line>\n            <@renderStationName format="long" lang="da"/> DGPS-station\n            på pos. <@formatPos lat=params.station.coordinates[1] lon=params.station.coordinates[0] format='audio' />\n            og frekvens ${params.station.frequency?string["0.0"]} kHz\n            <@renderStatus format="long" lang="da"/>\n            <#if part.eventDates?has_content && part.eventDates[0].fromDate??>\n                <@renderDateIntervals dateIntervals=part.eventDates format="plain" lang="da"/>\n            </#if>.\n        </@line>\n    </field-template>\n</#if>\n\n<#if promulgate('navtex')>\n    <field-template field="message.promulgation('navtex').text" update="append">\n        <#setting locale='en'>\n        <@line format="navtex">\n            DGPS-station <@renderStationName format="long" lang="en"/>\n            ${params.station.frequency?string["0.0"]} kHz\n            <@formatPos lat=params.station.coordinates[1] lon=params.station.coordinates[0] format='navtex' />\n            <@renderStatus format="normal" lang="en"/>\n            <#if part.eventDates?has_content && part.eventDates[0].fromDate??>\n                <@renderDateIntervals dateIntervals=part.eventDates format="navtex" lang="en"/>\n            </#if>.\n        </@line>\n    </field-template>\n</#if>\n\n</#if>\n	templates/tmpl/dgps.ftl	FM
1000004	2026-10-22 00:00:00	2026-10-22 00:00:00	0	\n/*\n * Copyright 2017 Danish Maritime Authority.\n *\n * Licensed under the Apache License, Version 2.0 (the "License");\n * you may not use this file except in compliance with the License.\n * You may obtain a copy of the License at\n *\n *       http://www.apache.org/licenses/LICENSE-2.0\n *\n * Unless required by applicable law or agreed to in writing, software\n * distributed under the License is distributed on an "AS IS" BASIS,\n * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n * See the License for the specific language governing permissions and\n * limitations under the License.\n */\n\nload('niord:templates/tmpl/common.js');\n\nmessage.areas.clear();\nvar areaService = CdiUtils.getBean(org.niord.core.area.AreaService.class);\nvar dk = areaService.findByName('Danmark', 'da', null);\nif (dk) {\n    var dkVo = dk.toVo(\n        org.niord.model.message.AreaVo.class,\n        org.niord.model.DataFilter.get());\n    message.areas.add(dkVo);\n}\n\nvar dgpsStation = {\n    'radio_navigation.dgps.skagen' :  {\n        frequency: 296.0,\n        coordinates: Java.to([ 10.59512710571289, 57.74877166748047],"double[]"),\n        transmitters: [ 'Baltico', 'Rogaland' ]\n    },\n    'radio_navigation.dgps.blåvand' :  {\n        frequency: 290.0,\n        coordinates: Java.to([ 8.08320140838623, 55.5578498840332],"double[]"),\n        transmitters: [ 'Baltico', 'Rogaland' ]\n    },\n    'radio_navigation.dgps.hammer_odde' :  {\n        frequency: 289.5,\n        coordinates: Java.to([ 14.773825645446777, 55.298221588134766],"double[]"),\n        transmitters: [ 'Baltico' ]\n    },\n    'radio_navigation.dgps.torshavn' :  {\n        frequency: 287.5,\n        coordinates: Java.to([ -6.837833404541016, 62.02050018310547],"double[]"),\n        transmitters: [ 'Rogaland' ]\n    }\n};\n\n// Update the geometry of the message part\nif (params.dgps_station\n    && params.dgps_station.key) {\n    var station = dgpsStation[params.dgps_station.key];\n    if (station !== undefined) {\n        part.geometry.features.length = 0;\n\n        var feature = new org.niord.model.geojson.FeatureVo();\n        var pt = new org.niord.model.geojson.PointVo();\n        pt.coordinates = station.coordinates;\n        feature.geometry = pt;\n        part.geometry.features = Java.to([ feature ], "org.niord.model.geojson.FeatureVo[]");\n\n        var navtex = message.promulgation('navtex');\n        if (navtex !== null) {\n            navtex.promulgate = true;\n            for (var t = 0; t < station.transmitters.length; t++) {\n                var transmitter = station.transmitters[t];\n                if (navtex.transmitters !== undefined && navtex.transmitters.containsKey(transmitter)) {\n                    navtex.transmitters.put(transmitter, true);\n                }\n            }\n        }\n\n        params.put('station', station);\n    }\n}\n	templates/tmpl/dgps.js	JS
\.


--
-- Data for Name: SecomNode; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."SecomNode" (id, mrn, "lastInteraction", created) FROM stdin;
\.


--
-- Data for Name: SecomOutboxEntry; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."SecomOutboxEntry" (id, "transactionId", node_id, "subscriptionUuid", operation, "notificationEvent", dataset_id, payload, "containerType", status, attempts, "nextAttemptAt", "lastError", "sentAt", "ackedAt", "openedAt", "nackType", created) FROM stdin;
\.


--
-- Data for Name: SecomSubscription; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."SecomSubscription" (id, uuid, node_id, "dataProductType", "productVersion", "containerType", "dataReference", geometry, wkt, "periodStart", "periodEnd", created) FROM stdin;
\.


--
-- Data for Name: Sequence; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Sequence" (name, "nextValue") FROM stdin;
MESSAGE_SERIES_NUMBER_dma-nm_2026	9
MESSAGE_SERIES_NUMBER_dma-nw_2026	9
\.


--
-- Data for Name: Source; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."Source" (id, active) FROM stdin;
1000001	t
1000002	t
1000003	t
1000004	t
1000005	t
1000006	t
1000007	t
1000008	t
1000009	t
1000010	t
1000011	t
1000012	t
1000013	t
1000014	t
1000015	t
1000016	t
1000017	t
1000018	t
1000019	t
1000020	t
1000021	t
1000022	t
1000023	t
1000024	t
1000025	t
1000026	t
1000027	t
1000028	t
1000029	t
\.


--
-- Data for Name: SourceDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."SourceDesc" (id, lang, abbreviation, name, entity_id) FROM stdin;
1000001	da	AKO	Arktisk Kommando	1000001
1000002	en	JACMD	Joint Arctic Command	1000001
1000003	da	BSH	Bundesamt für Seeschifffahrt und Hydrographie	1000002
1000004	en	BSH	Federal Maritime and Hydrographic Agency, Germany	1000002
1000005	da	DMI	Danmarks Meteorologiske Institut	1000003
1000006	en	DMI	The Danish Meteorological Institute	1000003
1000007	da	DMU	Danmarks Miljøundersøgelser	1000004
1000008	en	DMU	The National Environmental Research Institute, Denmark	1000004
1000009	da	DS	Dansk Sejlunion	1000005
1000010	en	DS	Danish Sailing Association	1000005
1000011	da	DTU	Danmarks Tekniske Universitet	1000006
1000012	en	DTU	Technical University of Denmark	1000006
1000013	da	ENS	Energistyrelsen	1000007
1000014	en	ENS	The Danish Energy Agency	1000007
1000015	da	FES	Forsvarsministeriets Ejendomsstyrelse	1000008
1000016	en	DDEO	Danish Defence Estates and Infrastructure Organisation	1000008
1000017	da	FLV	Flyvevåbnet	1000009
1000018	en	RDAF	Royal Danish Air Force	1000009
1000019	da	FRC	Forsvarets Operationscenter	1000010
1000020	en	JOC	Joint Operation Center	1000010
1000021	da	FST	Flyverstaben	1000011
1000022	en	AIR STAFF DC DNK	Air Staff Defence Command Denmark	1000011
1000023	da	GST	Geodatastyrelsen	1000012
1000024	en	GST	Danish Geodata Agency	1000012
1000025	da	IMO	International Maritime Organization	1000013
1000026	en	IMO	International Maritime Organization	1000013
1000027	da	JPL	Jægersprislejren	1000014
1000028	en	JPL	Camp Jægerspris, Denmark	1000014
1000029	da	JRCC	Joint Rescue Coordination Centre	1000015
1000030	en	JRCC	Joint Rescue Coordination Centre, Denmark	1000015
1000031	da	KDI	Kystdirektoratet	1000016
1000032	en	KDI	Danish Coastal Authority	1000016
1000033	da	LV	Landsverkfrøðingurin	1000017
1000034	en	LV	Faroese Office of Public Works	1000017
1000035	da	MAS	Maritime Assistance Service	1000018
1000036	en	MAS	Maritime Assistance Service	1000018
1000037	da	MVH	Marinehjemmeværnet	1000019
1000038	en	MHV	Danish Home Guard	1000019
1000039	da	MRCC	Maritime Rescue Coordination Centre	1000020
1000040	en	MRCC	Maritime Rescue Coordination Center	1000020
1000041	da	MST	Marinestaben	1000021
1000042	en	NAVAL STAFF DNK	Naval Staff DC Denmark	1000021
1000043	da	NST	Naturstyrelsen	1000022
1000044	en	NST	Danish Nature Agency	1000022
1000045	da	OKSBL	Oksbøllejren	1000023
1000046	en	OKSBL	Camp Oksbøl, Shooting Range Area, Denmark	1000023
1000047	da	SFS	Søfartsstyrelsen	1000024
1000048	en	DMA	The Danish Maritime Authority	1000024
1000049	da	SVN	Søværnet	1000025
1000050	en	RDN	Royal Danish Navy	1000025
1000051	da	TBST	Trafik- og Byggestyrelsen	1000026
1000052	en	TBST	Danish Transport and Construction Agency	1000026
1000053	da	VBC	Søværnets Skole - Center for Våben	1000027
1000054	en	NAVWEAPONCEN DNK	Danish Naval Weapon Center	1000027
1000055	da	VD	Vejdirektoratet	1000028
1000056	en	VD	The Danish Road Directorate	1000028
1000057	da	VFK	Værnsfælles Forsvarskommando	1000029
1000058	en	DC DNK	Defence Command Denmark	1000029
\.


--
-- Data for Name: TemplateParam; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."TemplateParam" (id, "indexNo", mandatory, "paramId", "positionList", type) FROM stdin;
1	0	f	aton	t	light
2	0	f	aton	t	light
3	0	f	aton	t	light
4	0	f	aton	t	light_buoy_by_function
5	0	f	aton	t	light_buoy_by_function
6	0	f	aton	t	light_buoy_by_function
7	0	f	aton	t	light_buoy_by_function
8	0	f	aton	t	light_buoy_by_type
9	0	f	aton	t	light_buoy_replacement
10	0	f	aton	t	light_buoy_by_function
11	0	f	duration	f	aton_withdrawn_duration
12	0	f	aton	t	buoy_by_function
13	0	f	aton	t	buoy_by_function
14	0	f	aton	t	buoy_by_type
15	0	f	aton	t	buoy_replacement
16	0	f	aton	t	buoy_by_function
17	0	f	duration	f	aton_withdrawn_duration
18	0	f	aton	t	beacon_by_function
19	0	f	aton	t	beacon_by_function
20	0	f	aton	t	beacon_by_function
21	0	f	wreck	f	wreck
22	0	f	obstruction	f	obstruction
23	0	f	reduced_depth	f	reduced_depth
24	0	t	salvage_type	f	salvage_type_list
25	0	f	bridge_name	f	text
26	0	f	aton_name	t	aton_name
27	0	t	exercise_type	f	exercise_type_list
28	0	t	date	f	date
29	0	t	object	f	object_adrift_list
30	0	t	date	f	date
31	0	t	object	f	marking_type_list
32	0	f	racon	t	racon
33	0	f	ais	t	ais
34	0	f	aton_type	f	radio_navigation_aton_list
35	0	f	aton_name	f	aton_name
36	0	t	ais_status	f	radio_navigation_status_list
37	0	t	aton_type	f	buoy_function_list
38	0	f	aton_name	f	aton_name
39	0	t	aton_type	f	buoy_type_list
40	0	f	aton_name	f	aton_name
41	0	t	aton_type	f	buoy_function_list
42	0	f	aton_name	f	aton_name
43	0	t	aton_replacement	f	marking_type_list
44	0	t	aton_type	f	light_list
45	0	f	aton_name	f	aton_name
46	0	t	aton_type	f	light_buoy_function_list
47	0	f	aton_name	f	aton_name
48	0	t	aton_type	f	light_buoy_type_list
49	0	f	aton_name	f	aton_name
50	0	f	aton_light	f	light_character
51	0	t	aton_type	f	light_buoy_function_list
52	0	f	aton_name	f	aton_name
53	0	t	aton_replacement	f	marking_type_list
54	0	f	aton_light	f	light_character
55	0	t	aton_type	f	beacon_function_list
56	0	f	aton_name	f	aton_name
57	0	f	aton_type	f	radio_navigation_aton_list
58	0	f	aton_name	f	aton_name
59	0	t	racon_status	f	radio_navigation_status_list
60	0	t	water_depth	f	number
61	0	f	locality	f	text
62	0	t	wreck_type	f	vessel_type_list
63	0	f	wreck_depth	f	number
64	0	f	wreck_visible	f	boolean
65	0	t	obstruction_type	f	obstruction_type_list
66	0	t	obstruction_report	f	obstruction_report_list
67	0	f	obstruction_depth	f	number
68	0	f	obstruction_visible	f	boolean
1000001	0	f	dgps	f	dgps
1000002	0	t	dgps_station	f	dgps_station_list
1000003	0	t	dgps_status	f	radio_navigation_status_list
\.


--
-- Data for Name: TemplateParamDesc; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."TemplateParamDesc" (id, lang, name, entity_id) FROM stdin;
1	en	Light	1
2	en	Light	2
3	en	Light	3
4	en	Light Buoy	4
5	en	Light Buoy	5
6	en	Light buoy	6
7	en	Light Buoy	7
8	en	Light Buoy	8
9	en	Light Buoy	9
10	en	Light Buoy	10
11	en	Duration	11
12	en	Buoy	12
13	en	Buoy	13
14	en	Buoy	14
15	en	Buoy	15
16	en	Buoy	16
17	en	Duration	17
18	en	Beacon	18
19	en	Beacon	19
20	en	Beacon	20
21	en	Wreck	21
22	en	Obstruction	22
23	en	Reduced Depth	23
24	en	Salvage type	24
25	en	Bridge name	25
26	en	Light Name	26
27	en	Exercise type	27
28	en	Observed	28
29	en	Object	29
30	en	Observed	30
31	en	Buoy	31
32	en	RACON	32
33	en	AIS	33
34	en	AtoN Type	34
35	en	AtoN name	35
36	en	Status	36
37	en	Buoy Type	37
38	en	Buoy Name	38
39	en	Buoy Type	39
40	en	Buoy Name	40
41	en	Buoy Type	41
42	en	Buoy Name	42
43	en	Replaced by	43
44	en	Light Type	44
45	en	Light Name	45
46	en	Light Buoy Type	46
47	en	Light Buoy Name	47
48	en	Light Buoy Type	48
49	en	Light Buoy Name	49
50	en	Light Character	50
51	en	Light Buoy Type	51
52	en	Light Buoy Name	52
53	en	Replaced by	53
54	en	Light Character	54
55	en	Beacon Type	55
56	en	Beacon Name	56
57	en	AtoN Type	57
58	en	AtoN name	58
59	en	Status	59
60	en	Water Depth (m)	60
61	en	Entrance to	61
62	en	Vessel Type	62
63	en	Depth over wreck (m)	63
64	en	Visible above sea surface	64
65	en	Obstruction Type	65
66	en	Reporting Type	66
67	en	Depth over obstruction (m)	67
68	en	Visible above sea surface	68
1000001	da	Fyr	1
1000002	da	Fyr	2
1000003	da	Fyr	3
1000004	da	Lystønde	4
1000005	da	Lystønde	5
1000006	da	Lystønde	6
1000007	da	Lystønde	7
1000008	da	Lystønde	8
1000009	da	Lystønde	9
1000010	da	Lystønde	10
1000011	da	Varighed	11
1000012	da	Dagsømærke	12
1000013	da	Dagsømærke	13
1000014	da	Dagsømærke	14
1000015	da	Dagsømærke	15
1000016	da	Dagsømærke	16
1000017	da	Varighed	17
1000018	da	Båke	18
1000019	da	Båke	19
1000020	da	Båke	20
1000021	da	Sejladshindring	22
1000022	da	Vrag	21
1000023	da	Vanddybde	23
1000024	da	Bjærningstype	24
1000025	da	Bronavn	25
1000026	da	Fyrnavn	26
1000027	da	Øvelsestype	27
1000028	da	Observeret	28
1000029	da	Genstand	29
1000030	da	Observeret	30
1000031	da	Sømærke	31
1000032	da	RACON	32
1000033	da	AIS	33
1000034	da	DGPS	1000001
1000035	en	DGPS	1000001
1000036	da	Afmærk. type	34
1000037	da	Afmærk. navn	35
1000038	da	Status	36
1000039	da	Dagsømærke-type	37
1000040	da	Dagsømærke-navn	38
1000041	da	Dagsømærke-type	39
1000042	da	Dagsømærke-navn	40
1000043	da	Dagsømærke-type	41
1000044	da	Dagsømærke-navn	42
1000045	da	Erstattet med	43
1000046	da	DGPS Station	1000002
1000047	en	DGPS Station	1000002
1000048	da	Status	1000003
1000049	en	Status	1000003
1000050	da	Fyr-type	44
1000051	da	Fyr-navn	45
1000052	da	Lystønde-type	46
1000053	da	Lystønde-navn	47
1000054	da	Lystønde-type	48
1000055	da	Lystønde-navn	49
1000056	da	Lyskarakter	50
1000057	da	Lystønde-type	51
1000058	da	Lystønde-navn	52
1000059	da	Erstattet med	53
1000060	da	Lyskarakter	54
1000061	da	Båke-type	55
1000062	da	Båke-navn	56
1000063	da	Afmærk. type	57
1000064	da	Afmærk. navn	58
1000065	da	Status	59
1000066	da	Vanddybde (m)	60
1000067	da	Indsejlingen til	61
1000068	da	Skibstype	62
1000069	da	Dybde over vrag (m)	63
1000070	da	Synlig over havoverflade	64
1000071	da	Sejladshindringstype	65
1000072	da	Rapportingstype	66
1000073	da	Dybde over sejladshinding (m)	67
1000074	da	Synlig over havoverflade	68
\.


--
-- Data for Name: TwitterMessagePromulgation; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."TwitterMessagePromulgation" (tweet, id) FROM stdin;
\.


--
-- Data for Name: TwitterSettings; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public."TwitterSettings" (id, "accessToken", "accessTokenSecret", "apiKey", "apiSecret", format, "includeThumbnail", "promulgationType_id") FROM stdin;
\.


--
-- Name: AreaDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."AreaDesc_SEQ"', 1000110, true);


--
-- Name: Area_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Area_SEQ"', 1000055, true);


--
-- Name: AtonNode_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."AtonNode_SEQ"', 1, false);


--
-- Name: AtonTag_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."AtonTag_SEQ"', 1, false);


--
-- Name: AttachmentDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."AttachmentDesc_SEQ"', 1000008, true);


--
-- Name: Attachment_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Attachment_SEQ"', 1000004, true);


--
-- Name: BaseMessagePromulgation_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."BaseMessagePromulgation_SEQ"', 1, false);


--
-- Name: BatchData_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."BatchData_SEQ"', 51, true);


--
-- Name: CategoryDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."CategoryDesc_SEQ"', 1000045, true);


--
-- Name: Category_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Category_SEQ"', 51, true);


--
-- Name: Chart_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Chart_SEQ"', 1000172, true);


--
-- Name: Comment_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Comment_SEQ"', 1, false);


--
-- Name: Contact_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Contact_SEQ"', 1, false);


--
-- Name: DateInterval_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."DateInterval_SEQ"', 1000022, true);


--
-- Name: DictionaryEntryDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."DictionaryEntryDesc_SEQ"', 1000119, true);


--
-- Name: DictionaryEntry_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."DictionaryEntry_SEQ"', 1000004, true);


--
-- Name: Dictionary_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Dictionary_SEQ"', 51, true);


--
-- Name: Domain_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Domain_SEQ"', 1000005, true);


--
-- Name: FeatureCollection_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."FeatureCollection_SEQ"', 1000030, true);


--
-- Name: Feature_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Feature_SEQ"', 1000030, true);


--
-- Name: FiringPeriod_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."FiringPeriod_SEQ"', 1, false);


--
-- Name: FiringSchedule_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."FiringSchedule_SEQ"', 1, false);


--
-- Name: FmReport_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."FmReport_SEQ"', 51, true);


--
-- Name: IssueAuditEntry_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."IssueAuditEntry_SEQ"', 1, false);


--
-- Name: IssueMember_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."IssueMember_SEQ"', 1, false);


--
-- Name: IssueOverride_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."IssueOverride_SEQ"', 1, false);


--
-- Name: MailingListDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MailingListDesc_SEQ"', 1000010, true);


--
-- Name: MailingListStatusChange_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MailingListStatusChange_id_seq"', 1, false);


--
-- Name: MailingListTriggerDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MailingListTriggerDesc_SEQ"', 1000011, true);


--
-- Name: MailingListTrigger_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MailingListTrigger_SEQ"', 1000008, true);


--
-- Name: MailingList_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MailingList_SEQ"', 1000005, true);


--
-- Name: MessageDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MessageDesc_SEQ"', 1000068, true);


--
-- Name: MessageFilter_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MessageFilter_SEQ"', 1, false);


--
-- Name: MessageHistory_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MessageHistory_SEQ"', 1, false);


--
-- Name: MessagePartDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MessagePartDesc_SEQ"', 1000068, true);


--
-- Name: MessagePart_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MessagePart_SEQ"', 1000034, true);


--
-- Name: MessageSeriesMapping_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MessageSeriesMapping_SEQ"', 1, false);


--
-- Name: MessageSeries_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MessageSeries_SEQ"', 1000006, true);


--
-- Name: MessageTag_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."MessageTag_SEQ"', 1, false);


--
-- Name: Message_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Message_SEQ"', 1000034, true);


--
-- Name: NavtexTransmitter_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."NavtexTransmitter_SEQ"', 1, false);


--
-- Name: NiordIntegration_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."NiordIntegration_SEQ"', 1, false);


--
-- Name: ParamType_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."ParamType_SEQ"', 1000002, true);


--
-- Name: PromulgationType_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."PromulgationType_SEQ"', 51, true);


--
-- Name: PublicationCategoryDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."PublicationCategoryDesc_SEQ"', 1000008, true);


--
-- Name: PublicationCategory_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."PublicationCategory_SEQ"', 1000004, true);


--
-- Name: PublicationDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."PublicationDesc_SEQ"', 1000016, true);


--
-- Name: PublicationIssueDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."PublicationIssueDesc_SEQ"', 1, false);


--
-- Name: PublicationIssue_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."PublicationIssue_SEQ"', 1, false);


--
-- Name: PublicationSeriesDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."PublicationSeriesDesc_SEQ"', 1, false);


--
-- Name: PublicationSeries_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."PublicationSeries_SEQ"', 1, false);


--
-- Name: Publication_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Publication_SEQ"', 1000008, true);


--
-- Name: ReferenceDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."ReferenceDesc_SEQ"', 1000002, true);


--
-- Name: Reference_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Reference_SEQ"', 1000001, true);


--
-- Name: S124DatasetVersion_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."S124DatasetVersion_SEQ"', 1, false);


--
-- Name: S124Dataset_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."S124Dataset_SEQ"', 1, false);


--
-- Name: S124SeriesConfig_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."S124SeriesConfig_SEQ"', 1, false);


--
-- Name: SafetyNetArea_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."SafetyNetArea_SEQ"', 1, false);


--
-- Name: ScheduledMailRecipient_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."ScheduledMailRecipient_SEQ"', 1, false);


--
-- Name: ScheduledMail_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."ScheduledMail_SEQ"', 1, false);


--
-- Name: ScriptResourceHistory_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."ScriptResourceHistory_SEQ"', 101, true);


--
-- Name: ScriptResource_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."ScriptResource_SEQ"', 1000004, true);


--
-- Name: SecomNode_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."SecomNode_SEQ"', 1, false);


--
-- Name: SecomOutboxEntry_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."SecomOutboxEntry_SEQ"', 1, false);


--
-- Name: SecomSubscription_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."SecomSubscription_SEQ"', 1, false);


--
-- Name: SourceDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."SourceDesc_SEQ"', 1000058, true);


--
-- Name: Source_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."Source_SEQ"', 1000029, true);


--
-- Name: TemplateParamDesc_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."TemplateParamDesc_SEQ"', 1000074, true);


--
-- Name: TemplateParam_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."TemplateParam_SEQ"', 1000003, true);


--
-- Name: TwitterSettings_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."TwitterSettings_SEQ"', 1, false);


--
-- Name: User_SEQ; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public."User_SEQ"', 1, false);


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
-- Name: BootstrapState BootstrapState_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."BootstrapState"
    ADD CONSTRAINT "BootstrapState_pkey" PRIMARY KEY (id);


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
-- Name: DefaultsLedger DefaultsLedger_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."DefaultsLedger"
    ADD CONSTRAINT "DefaultsLedger_pkey" PRIMARY KEY (kind, key, dictionary, lang);


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
-- Name: IssueAuditEntry IssueAuditEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueAuditEntry"
    ADD CONSTRAINT "IssueAuditEntry_pkey" PRIMARY KEY (id);


--
-- Name: IssueMember IssueMember_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueMember"
    ADD CONSTRAINT "IssueMember_pkey" PRIMARY KEY (id);


--
-- Name: IssueOverride IssueOverride_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueOverride"
    ADD CONSTRAINT "IssueOverride_pkey" PRIMARY KEY (id);


--
-- Name: MailingListDesc MailingListDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListDesc"
    ADD CONSTRAINT "MailingListDesc_pkey" PRIMARY KEY (id);


--
-- Name: MailingListStatusChange MailingListStatusChange_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MailingListStatusChange"
    ADD CONSTRAINT "MailingListStatusChange_pkey" PRIMARY KEY (id);


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
-- Name: PublicationIssueDesc PublicationIssueDesc_lang_entity_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssueDesc"
    ADD CONSTRAINT "PublicationIssueDesc_lang_entity_id_key" UNIQUE (lang, entity_id);


--
-- Name: PublicationIssueDesc PublicationIssueDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssueDesc"
    ADD CONSTRAINT "PublicationIssueDesc_pkey" PRIMARY KEY (id);


--
-- Name: PublicationIssue PublicationIssue_legacyPublicationId_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "PublicationIssue_legacyPublicationId_key" UNIQUE ("legacyPublicationId");


--
-- Name: PublicationIssue PublicationIssue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "PublicationIssue_pkey" PRIMARY KEY (id);


--
-- Name: PublicationIssue PublicationIssue_publicId_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "PublicationIssue_publicId_key" UNIQUE ("publicId");


--
-- Name: PublicationSeriesDesc PublicationSeriesDesc_lang_entity_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeriesDesc"
    ADD CONSTRAINT "PublicationSeriesDesc_lang_entity_id_key" UNIQUE (lang, entity_id);


--
-- Name: PublicationSeriesDesc PublicationSeriesDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeriesDesc"
    ADD CONSTRAINT "PublicationSeriesDesc_pkey" PRIMARY KEY (id);


--
-- Name: PublicationSeries_AvailableDomain PublicationSeries_AvailableDomain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries_AvailableDomain"
    ADD CONSTRAINT "PublicationSeries_AvailableDomain_pkey" PRIMARY KEY (series_id, domain_id);


--
-- Name: PublicationSeries_languages PublicationSeries_languages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries_languages"
    ADD CONSTRAINT "PublicationSeries_languages_pkey" PRIMARY KEY ("PublicationSeries_id", "indexNo");


--
-- Name: PublicationSeries PublicationSeries_legacyTemplateId_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "PublicationSeries_legacyTemplateId_key" UNIQUE ("legacyTemplateId");


--
-- Name: PublicationSeries PublicationSeries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "PublicationSeries_pkey" PRIMARY KEY (id);


--
-- Name: PublicationSeries PublicationSeries_seriesId_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "PublicationSeries_seriesId_key" UNIQUE ("seriesId");


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
-- Name: RepoFile RepoFile_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."RepoFile"
    ADD CONSTRAINT "RepoFile_pkey" PRIMARY KEY (path);


--
-- Name: S124DatasetVersion S124DatasetVersion_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."S124DatasetVersion"
    ADD CONSTRAINT "S124DatasetVersion_pkey" PRIMARY KEY (id);


--
-- Name: S124Dataset S124Dataset_mrn_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."S124Dataset"
    ADD CONSTRAINT "S124Dataset_mrn_key" UNIQUE (mrn);


--
-- Name: S124Dataset S124Dataset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."S124Dataset"
    ADD CONSTRAINT "S124Dataset_pkey" PRIMARY KEY (id);


--
-- Name: S124Dataset S124Dataset_uuid_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."S124Dataset"
    ADD CONSTRAINT "S124Dataset_uuid_key" UNIQUE (uuid);


--
-- Name: S124SeriesConfig S124SeriesConfig_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."S124SeriesConfig"
    ADD CONSTRAINT "S124SeriesConfig_pkey" PRIMARY KEY (id);


--
-- Name: S124SeriesConfig S124SeriesConfig_series_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."S124SeriesConfig"
    ADD CONSTRAINT "S124SeriesConfig_series_key" UNIQUE (series_id);


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
-- Name: SecomNode SecomNode_mrn_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SecomNode"
    ADD CONSTRAINT "SecomNode_mrn_key" UNIQUE (mrn);


--
-- Name: SecomNode SecomNode_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SecomNode"
    ADD CONSTRAINT "SecomNode_pkey" PRIMARY KEY (id);


--
-- Name: SecomOutboxEntry SecomOutboxEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SecomOutboxEntry"
    ADD CONSTRAINT "SecomOutboxEntry_pkey" PRIMARY KEY (id);


--
-- Name: SecomOutboxEntry SecomOutboxEntry_transactionId_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SecomOutboxEntry"
    ADD CONSTRAINT "SecomOutboxEntry_transactionId_key" UNIQUE ("transactionId");


--
-- Name: SecomSubscription SecomSubscription_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SecomSubscription"
    ADD CONSTRAINT "SecomSubscription_pkey" PRIMARY KEY (id);


--
-- Name: SecomSubscription SecomSubscription_uuid_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SecomSubscription"
    ADD CONSTRAINT "SecomSubscription_uuid_key" UNIQUE (uuid);


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
-- Name: IssueMember UK_issue_member_issue_uid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueMember"
    ADD CONSTRAINT "UK_issue_member_issue_uid" UNIQUE (issue_id, "messageUid");


--
-- Name: IssueOverride UK_issue_override_issue_uid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueOverride"
    ADD CONSTRAINT "UK_issue_override_issue_uid" UNIQUE (issue_id, "messageUid");


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
-- Name: databasechangeloglock databasechangeloglock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.databasechangeloglock
    ADD CONSTRAINT databasechangeloglock_pkey PRIMARY KEY (id);


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
-- Name: message_search_text message_search_text_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_search_text
    ADD CONSTRAINT message_search_text_pkey PRIMARY KEY (message_id, lang);


--
-- Name: Sequence sequence_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Sequence"
    ADD CONSTRAINT sequence_name PRIMARY KEY (name);


--
-- Name: MailingListStatusChange_due_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "MailingListStatusChange_due_idx" ON public."MailingListStatusChange" USING btree ("dueAt", id);


--
-- Name: RepoFile_expires_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "RepoFile_expires_idx" ON public."RepoFile" USING btree (expires) WHERE (expires IS NOT NULL);


--
-- Name: RepoFile_folder_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX "RepoFile_folder_idx" ON public."RepoFile" USING btree (folder);


--
-- Name: aton_node_geom; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX aton_node_geom ON public."AtonNode" USING gist (geometry);


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
-- Name: feature_geom; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX feature_geom ON public."Feature" USING gist (geometry);


--
-- Name: firing_period_from_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX firing_period_from_date ON public."FiringPeriod" USING btree ("fromDate");


--
-- Name: firing_period_to_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX firing_period_to_date ON public."FiringPeriod" USING btree ("toDate");


--
-- Name: issue_member_issue_sort_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX issue_member_issue_sort_k ON public."IssueMember" USING btree (issue_id, "sortIndex");


--
-- Name: issue_member_source_issue_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX issue_member_source_issue_k ON public."IssueMember" USING btree ("sourceIssuePublicId");


--
-- Name: issue_member_uid_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX issue_member_uid_k ON public."IssueMember" USING btree ("messageUid");


--
-- Name: issue_override_uid_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX issue_override_uid_k ON public."IssueOverride" USING btree ("messageUid");


--
-- Name: message_legacy_id_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_legacy_id_k ON public."Message" USING btree ("legacyId");


--
-- Name: message_main_type_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_main_type_k ON public."Message" USING btree ("mainType");


--
-- Name: message_search_text_tsv_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX message_search_text_tsv_idx ON public.message_search_text USING gin (search_tsv);


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
-- Name: pub_series_source_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX pub_series_source_k ON public."PublicationSeries" USING btree ("sourceSeries_id");


--
-- Name: publication_id_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX publication_id_k ON public."Publication" USING btree ("publicationId");


--
-- Name: publication_issue_series_status_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX publication_issue_series_status_k ON public."PublicationIssue" USING btree (series_id, status);


--
-- Name: publication_issue_status_public_from_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX publication_issue_status_public_from_k ON public."PublicationIssue" USING btree (status, "publicFrom");


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
-- Name: s124_dataset_message; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX s124_dataset_message ON public."S124Dataset" USING btree (message_id);


--
-- Name: s124_dataset_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX s124_dataset_status ON public."S124Dataset" USING btree (status);


--
-- Name: s124_dataset_version_dataset; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX s124_dataset_version_dataset ON public."S124DatasetVersion" USING btree (dataset_id);


--
-- Name: scheduled_mail_send_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX scheduled_mail_send_date ON public."ScheduledMail" USING btree ("sendDate");


--
-- Name: scheduled_mail_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX scheduled_mail_status ON public."ScheduledMail" USING btree (status);


--
-- Name: secom_outbox_node; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX secom_outbox_node ON public."SecomOutboxEntry" USING btree (node_id);


--
-- Name: secom_outbox_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX secom_outbox_status ON public."SecomOutboxEntry" USING btree (status, "nextAttemptAt");


--
-- Name: secom_subscription_node; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX secom_subscription_node ON public."SecomSubscription" USING btree (node_id);


--
-- Name: user_username_lower; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX user_username_lower ON public."User" USING btree (lower((username)::text));


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
-- Name: PublicationSeries FK1jyqgntj2p9agug9sv2uks9td; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "FK1jyqgntj2p9agug9sv2uks9td" FOREIGN KEY (category_id) REFERENCES public."PublicationCategory"(id);


--
-- Name: PublicationSeries_languages FK26wanwvqt302qhcqkjjxevrhf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries_languages"
    ADD CONSTRAINT "FK26wanwvqt302qhcqkjjxevrhf" FOREIGN KEY ("PublicationSeries_id") REFERENCES public."PublicationSeries"(id);


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
-- Name: PublicationIssue FK3byv55vdym4gm9xkej0p4vko5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "FK3byv55vdym4gm9xkej0p4vko5" FOREIGN KEY ("publishedBy_id") REFERENCES public."User"(id);


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
-- Name: IssueMember FK4pb9lphk0jefvi25m347aisxj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueMember"
    ADD CONSTRAINT "FK4pb9lphk0jefvi25m347aisxj" FOREIGN KEY (message_id) REFERENCES public."Message"(id);


--
-- Name: MessageFilter FK4t1b8pwj7bowstlx0i2yqtf0d; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."MessageFilter"
    ADD CONSTRAINT "FK4t1b8pwj7bowstlx0i2yqtf0d" FOREIGN KEY (user_id) REFERENCES public."User"(id);


--
-- Name: PublicationIssue FK541rqo4pgqakr2cn55pu3wa3t; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "FK541rqo4pgqakr2cn55pu3wa3t" FOREIGN KEY (series_id) REFERENCES public."PublicationSeries"(id);


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
-- Name: PublicationSeriesDesc FK6ht1fjhdw5rfkg222aujmgobj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeriesDesc"
    ADD CONSTRAINT "FK6ht1fjhdw5rfkg222aujmgobj" FOREIGN KEY (entity_id) REFERENCES public."PublicationSeries"(id);


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
-- Name: PublicationSeries_AvailableDomain FK_pub_series_available_domain_domain; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries_AvailableDomain"
    ADD CONSTRAINT "FK_pub_series_available_domain_domain" FOREIGN KEY (domain_id) REFERENCES public."Domain"(id) ON DELETE CASCADE;


--
-- Name: PublicationSeries_AvailableDomain FK_pub_series_available_domain_series; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries_AvailableDomain"
    ADD CONSTRAINT "FK_pub_series_available_domain_series" FOREIGN KEY (series_id) REFERENCES public."PublicationSeries"(id);


--
-- Name: PublicationSeries FK_pub_series_source_series; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "FK_pub_series_source_series" FOREIGN KEY ("sourceSeries_id") REFERENCES public."PublicationSeries"(id);


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
-- Name: IssueOverride FKat2r069xg53ggxwgpb93anegs; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueOverride"
    ADD CONSTRAINT "FKat2r069xg53ggxwgpb93anegs" FOREIGN KEY (message_id) REFERENCES public."Message"(id);


--
-- Name: ScriptResourceHistory FKawbsao8kv2ky4rkiob78mtu8i; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."ScriptResourceHistory"
    ADD CONSTRAINT "FKawbsao8kv2ky4rkiob78mtu8i" FOREIGN KEY (user_id) REFERENCES public."User"(id);


--
-- Name: IssueAuditEntry FKaybj44ry4iyuiq6v4rlurfjja; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueAuditEntry"
    ADD CONSTRAINT "FKaybj44ry4iyuiq6v4rlurfjja" FOREIGN KEY (issue_id) REFERENCES public."PublicationIssue"(id);


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
-- Name: PublicationIssueDesc FKce6a81g3w4o47jvg0gru4fc6e; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssueDesc"
    ADD CONSTRAINT "FKce6a81g3w4o47jvg0gru4fc6e" FOREIGN KEY (entity_id) REFERENCES public."PublicationIssue"(id);


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
-- Name: IssueAuditEntry FKdhmyebeihgwxdk54l6ejr54b3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueAuditEntry"
    ADD CONSTRAINT "FKdhmyebeihgwxdk54l6ejr54b3" FOREIGN KEY (user_id) REFERENCES public."User"(id);


--
-- Name: Message_Area FKdwo44aikrx5davbwv5ool9ri7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Message_Area"
    ADD CONSTRAINT "FKdwo44aikrx5davbwv5ool9ri7" FOREIGN KEY ("Message_id") REFERENCES public."Message"(id);


--
-- Name: PublicationIssue FKenagt3ieoiv8aify21x27sgvw; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "FKenagt3ieoiv8aify21x27sgvw" FOREIGN KEY ("retiredBy_id") REFERENCES public."User"(id);


--
-- Name: Domain_Category FKexyqxhk9co2fd0q88qyy4m72a; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."Domain_Category"
    ADD CONSTRAINT "FKexyqxhk9co2fd0q88qyy4m72a" FOREIGN KEY (categories_id) REFERENCES public."Category"(id);


--
-- Name: IssueMember FKf2w5e2c4nseds7tx23xitcceh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueMember"
    ADD CONSTRAINT "FKf2w5e2c4nseds7tx23xitcceh" FOREIGN KEY (issue_id) REFERENCES public."PublicationIssue"(id);


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
-- Name: PublicationIssue FKgipwxtphm1gd687gpkh545fs; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "FKgipwxtphm1gd687gpkh545fs" FOREIGN KEY (supersedes_id) REFERENCES public."PublicationIssue"(id);


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
-- Name: IssueMember FKi73654iekssaluaymrr1yvk6s; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueMember"
    ADD CONSTRAINT "FKi73654iekssaluaymrr1yvk6s" FOREIGN KEY (override_id) REFERENCES public."IssueOverride"(id);


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
-- Name: IssueAuditEntry FKl25t0hmt1u1sls6f5epxcfwdq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueAuditEntry"
    ADD CONSTRAINT "FKl25t0hmt1u1sls6f5epxcfwdq" FOREIGN KEY (series_id) REFERENCES public."PublicationSeries"(id);


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
-- Name: PublicationSeries FKmjvl4nynaphacv4tlsd1ec5gs; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "FKmjvl4nynaphacv4tlsd1ec5gs" FOREIGN KEY (domain_id) REFERENCES public."Domain"(id);


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
-- Name: IssueOverride FKnc0w80uck53xck3asumomp1f7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueOverride"
    ADD CONSTRAINT "FKnc0w80uck53xck3asumomp1f7" FOREIGN KEY (issue_id) REFERENCES public."PublicationIssue"(id);


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
-- Name: IssueOverride FKpk6pgvtxqyi7wk2h6u3mxccfk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueOverride"
    ADD CONSTRAINT "FKpk6pgvtxqyi7wk2h6u3mxccfk" FOREIGN KEY (author_id) REFERENCES public."User"(id);


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
-- Name: PublicationIssueDesc FKte8jrotfgfcxv16j748lqt9wo; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssueDesc"
    ADD CONSTRAINT "FKte8jrotfgfcxv16j748lqt9wo" FOREIGN KEY ("replacedBy_id") REFERENCES public."User"(id);


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
-- Name: S124DatasetVersion S124DatasetVersion_dataset_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."S124DatasetVersion"
    ADD CONSTRAINT "S124DatasetVersion_dataset_fkey" FOREIGN KEY (dataset_id) REFERENCES public."S124Dataset"(id);


--
-- Name: S124Dataset S124Dataset_current_version_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."S124Dataset"
    ADD CONSTRAINT "S124Dataset_current_version_fkey" FOREIGN KEY (current_version_id) REFERENCES public."S124DatasetVersion"(id);


--
-- Name: S124Dataset S124Dataset_message_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."S124Dataset"
    ADD CONSTRAINT "S124Dataset_message_fkey" FOREIGN KEY (message_id) REFERENCES public."Message"(id);


--
-- Name: S124SeriesConfig S124SeriesConfig_series_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."S124SeriesConfig"
    ADD CONSTRAINT "S124SeriesConfig_series_fkey" FOREIGN KEY (series_id) REFERENCES public."MessageSeries"(id);


--
-- Name: SecomOutboxEntry SecomOutboxEntry_dataset_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SecomOutboxEntry"
    ADD CONSTRAINT "SecomOutboxEntry_dataset_fkey" FOREIGN KEY (dataset_id) REFERENCES public."S124Dataset"(id);


--
-- Name: SecomOutboxEntry SecomOutboxEntry_node_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SecomOutboxEntry"
    ADD CONSTRAINT "SecomOutboxEntry_node_fkey" FOREIGN KEY (node_id) REFERENCES public."SecomNode"(id);


--
-- Name: SecomSubscription SecomSubscription_node_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."SecomSubscription"
    ADD CONSTRAINT "SecomSubscription_node_fkey" FOREIGN KEY (node_id) REFERENCES public."SecomNode"(id);


--
-- PostgreSQL database dump complete
--


