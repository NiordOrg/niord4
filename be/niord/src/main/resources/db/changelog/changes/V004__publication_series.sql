--liquibase formatted sql
--changeset niord:004-publication-series splitStatements:true endDelimiter:;
--
-- Publications redesign: the series/issue model that replaces the flat legacy "Publication" row.
--
-- One changeset expressing the NET schema, cut from the DDL Hibernate 7 generates for the ported entities
-- (org.niord.core.publication.series) against PostgreSQL, in the style of V001. The Quarkus deployment
-- carried this model as a Flyway history (V1-V19, MySQL); replaying that history into a database that has
-- never held any of it buys nothing, so the reshapes are collapsed. Three facts from that history worth
-- keeping: (1) the public list is the new model unconditionally -- there is no "publicAuthority" switch and
-- there never was one here; (2) a "ShadowDiffRun" table once existed as evidence for a decision that is no
-- longer taken; (3) "PublicationSeries" gets no index beyond its keys because it holds a few dozen rows --
-- index status if that ever stops being true.
--
-- Enums are character varying(255) plus a named CHECK constraint (V001's rendering); adding a constant to
-- a checked enum needs a changeset that drops and recreates the CHECK. Three columns are deliberately NOT
-- checked, as their entity mappings pin: "IssueAuditEntry".action (a new AuditAction constant must not need
-- a migration), "PublicationIssue"."intervalToSource" and "PublicationIssue"."snapshotTimeRelation".
-- Ids come from one sequence per entity (INCREMENT BY 50), like every other entity in this schema.
-- "PublicationSeries_AvailableDomain".domain_id is the schema's only ON DELETE CASCADE: deleting a domain
-- removes it from every availability list that named it, while the series side stays RESTRICT so a stray
-- DELETE cannot take the sharing with it.
--
-- Column nullability follows the entity mappings as Hibernate renders them with bean validation off
-- (the persist behaviour this application has always had); NOT NULL therefore marks nullable=false columns only.
--

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
-- Name: PublicationSeriesDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PublicationSeriesDesc_SEQ"
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
-- Name: PublicationIssueDesc_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."PublicationIssueDesc_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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
-- Name: IssueOverride_SEQ; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public."IssueOverride_SEQ"
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

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
    CONSTRAINT "PublicationSeries_availability_check" CHECK (((availability)::text = ANY ((ARRAY['OWNER_ONLY'::character varying, 'SELECTED_DOMAINS'::character varying, 'ALL_DOMAINS'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_cadence_check" CHECK (((cadence)::text = ANY ((ARRAY['NONE'::character varying, 'DAILY'::character varying, 'WEEKLY'::character varying, 'MONTHLY'::character varying, 'YEARLY'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_contentMode_check" CHECK ((("contentMode")::text = ANY ((ARRAY['GENERATED_FROM_QUERY'::character varying, 'UPLOADED_FILE'::character varying, 'EXTERNAL_LINK'::character varying, 'NONE'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_cutoffDefault_check" CHECK ((("cutoffDefault")::text = ANY ((ARRAY['RELEASE_MOMENT'::character varying, 'PERIOD_START'::character varying, 'PERIOD_END'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_kind_check" CHECK (((kind)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'UNSCHEDULED'::character varying, 'ONE_OFF'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_messagePublication_check" CHECK ((("messagePublication")::text = ANY ((ARRAY['NONE'::character varying, 'INTERNAL'::character varying, 'EXTERNAL'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_messageSortOrder_check" CHECK ((("messageSortOrder")::text = ANY ((ARRAY['ASC'::character varying, 'DESC'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_nextIssueCreation_check" CHECK ((("nextIssueCreation")::text = ANY ((ARRAY['AUTO_ON_PUBLISH'::character varying, 'MANUAL'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_nominalCutoffDay_check" CHECK ((("nominalCutoffDay")::text = ANY ((ARRAY['MONDAY'::character varying, 'TUESDAY'::character varying, 'WEDNESDAY'::character varying, 'THURSDAY'::character varying, 'FRIDAY'::character varying, 'SATURDAY'::character varying, 'SUNDAY'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_numberingScheme_check" CHECK ((("numberingScheme")::text = ANY ((ARRAY['ISO_WEEK_YEAR'::character varying, 'YEAR_EDITION'::character varying, 'MONTH_YEAR'::character varying, 'EDITION_SEQUENCE'::character varying, 'NONE'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_pageOrientation_check" CHECK ((("pageOrientation")::text = ANY ((ARRAY['PORTRAIT'::character varying, 'LANDSCAPE'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_pageSize_check" CHECK ((("pageSize")::text = ANY ((ARRAY['A3'::character varying, 'A4'::character varying, 'A5'::character varying, 'B4'::character varying, 'B5'::character varying, 'LETTER'::character varying, 'LEGAL'::character varying, 'LEDGER'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_releaseMode_check" CHECK ((("releaseMode")::text = ANY ((ARRAY['MANUAL_GATE'::character varying, 'AUTO_RELEASE'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_status_check" CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'ACTIVE'::character varying, 'RETIRED'::character varying])::text[]))),
    CONSTRAINT "PublicationSeries_timeRelation_check" CHECK ((("timeRelation")::text = ANY ((ARRAY['PUBLISHED_IN_INTERVAL'::character varying, 'IN_FORCE_AT_CUTOFF'::character varying, 'COMPILED_FROM_SOURCE'::character varying])::text[])))
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
-- Name: PublicationSeries_languages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PublicationSeries_languages" (
    "PublicationSeries_id" integer NOT NULL,
    "indexNo" integer NOT NULL,
    languages character varying(255)
);

--
-- Name: PublicationSeries_AvailableDomain; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."PublicationSeries_AvailableDomain" (
    domain_id integer NOT NULL,
    series_id integer NOT NULL
);

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
    CONSTRAINT "PublicationIssue_intervalFromSource_check" CHECK ((("intervalFromSource")::text = ANY ((ARRAY['STAMPED'::character varying, 'NOMINAL'::character varying, 'RECOVERED'::character varying, 'MANUAL'::character varying])::text[]))),
    CONSTRAINT "PublicationIssue_membershipProvenance_check" CHECK ((("membershipProvenance")::text = ANY ((ARRAY['EXACT'::character varying, 'EXPLAINED_DIFF'::character varying, 'UNION_SNAPSHOT'::character varying, 'NO_MEMBERSHIP'::character varying, 'IMPORTED'::character varying, 'COMPILED'::character varying])::text[]))),
    CONSTRAINT "PublicationIssue_publicWindowSource_check" CHECK ((("publicWindowSource")::text = ANY ((ARRAY['DERIVED'::character varying, 'MANUAL'::character varying])::text[]))),
    CONSTRAINT "PublicationIssue_status_check" CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'PUBLISHED'::character varying, 'RETIRED'::character varying])::text[])))
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
    CONSTRAINT "PublicationIssueDesc_fileSource_check" CHECK ((("fileSource")::text = ANY ((ARRAY['GENERATED'::character varying, 'UPLOADED'::character varying])::text[])))
);

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
    CONSTRAINT "IssueMember_source_check" CHECK (((source)::text = ANY ((ARRAY['CRITERIA'::character varying, 'OVERRIDE_INCLUDE'::character varying, 'IMPORTED'::character varying, 'COMPILED'::character varying])::text[])))
);

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
    CONSTRAINT "IssueOverride_kind_check" CHECK (((kind)::text = ANY ((ARRAY['INCLUDE'::character varying, 'EXCLUDE'::character varying])::text[])))
);

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
    CONSTRAINT "IssueAuditEntry_actorKind_check" CHECK ((("actorKind")::text = ANY ((ARRAY['USER'::character varying, 'SYSTEM'::character varying, 'IMPORT'::character varying])::text[])))
);

--
-- Name: PublicationSeries PublicationSeries_legacyTemplateId_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "PublicationSeries_legacyTemplateId_key" UNIQUE ("legacyTemplateId");

--
-- Name: PublicationSeries PublicationSeries_seriesId_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "PublicationSeries_seriesId_key" UNIQUE ("seriesId");

--
-- Name: PublicationSeries PublicationSeries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "PublicationSeries_pkey" PRIMARY KEY (id);

--
-- Name: PublicationSeriesDesc PublicationSeriesDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeriesDesc"
    ADD CONSTRAINT "PublicationSeriesDesc_pkey" PRIMARY KEY (id);

--
-- Name: PublicationSeriesDesc PublicationSeriesDesc_lang_entity_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeriesDesc"
    ADD CONSTRAINT "PublicationSeriesDesc_lang_entity_id_key" UNIQUE (lang, entity_id);

--
-- Name: PublicationSeries_languages PublicationSeries_languages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries_languages"
    ADD CONSTRAINT "PublicationSeries_languages_pkey" PRIMARY KEY ("PublicationSeries_id", "indexNo");

--
-- Name: PublicationIssue PublicationIssue_legacyPublicationId_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "PublicationIssue_legacyPublicationId_key" UNIQUE ("legacyPublicationId");

--
-- Name: PublicationIssue PublicationIssue_publicId_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "PublicationIssue_publicId_key" UNIQUE ("publicId");

--
-- Name: PublicationIssue PublicationIssue_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "PublicationIssue_pkey" PRIMARY KEY (id);

--
-- Name: PublicationIssueDesc PublicationIssueDesc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssueDesc"
    ADD CONSTRAINT "PublicationIssueDesc_pkey" PRIMARY KEY (id);

--
-- Name: PublicationIssueDesc PublicationIssueDesc_lang_entity_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssueDesc"
    ADD CONSTRAINT "PublicationIssueDesc_lang_entity_id_key" UNIQUE (lang, entity_id);

--
-- Name: IssueMember IssueMember_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueMember"
    ADD CONSTRAINT "IssueMember_pkey" PRIMARY KEY (id);

--
-- Name: IssueMember UK_issue_member_issue_uid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueMember"
    ADD CONSTRAINT "UK_issue_member_issue_uid" UNIQUE (issue_id, "messageUid");

--
-- Name: IssueOverride IssueOverride_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueOverride"
    ADD CONSTRAINT "IssueOverride_pkey" PRIMARY KEY (id);

--
-- Name: IssueOverride UK_issue_override_issue_uid; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueOverride"
    ADD CONSTRAINT "UK_issue_override_issue_uid" UNIQUE (issue_id, "messageUid");

--
-- Name: IssueAuditEntry IssueAuditEntry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueAuditEntry"
    ADD CONSTRAINT "IssueAuditEntry_pkey" PRIMARY KEY (id);

--
-- Name: PublicationSeries_AvailableDomain PublicationSeries_AvailableDomain_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries_AvailableDomain"
    ADD CONSTRAINT "PublicationSeries_AvailableDomain_pkey" PRIMARY KEY (series_id, domain_id);

--
-- Name: issue_member_issue_sort_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX issue_member_issue_sort_k ON public."IssueMember" USING btree (issue_id, "sortIndex");

--
-- Name: issue_member_uid_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX issue_member_uid_k ON public."IssueMember" USING btree ("messageUid");

--
-- Name: issue_member_source_issue_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX issue_member_source_issue_k ON public."IssueMember" USING btree ("sourceIssuePublicId");

--
-- Name: issue_override_uid_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX issue_override_uid_k ON public."IssueOverride" USING btree ("messageUid");

--
-- Name: publication_issue_series_status_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX publication_issue_series_status_k ON public."PublicationIssue" USING btree (series_id, status);

--
-- Name: publication_issue_status_public_from_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX publication_issue_status_public_from_k ON public."PublicationIssue" USING btree (status, "publicFrom");

--
-- Name: pub_series_source_k; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX pub_series_source_k ON public."PublicationSeries" USING btree ("sourceSeries_id");

--
-- Name: IssueAuditEntry FKaybj44ry4iyuiq6v4rlurfjja; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueAuditEntry"
    ADD CONSTRAINT "FKaybj44ry4iyuiq6v4rlurfjja" FOREIGN KEY (issue_id) REFERENCES public."PublicationIssue"(id);

--
-- Name: IssueAuditEntry FKl25t0hmt1u1sls6f5epxcfwdq; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueAuditEntry"
    ADD CONSTRAINT "FKl25t0hmt1u1sls6f5epxcfwdq" FOREIGN KEY (series_id) REFERENCES public."PublicationSeries"(id);

--
-- Name: IssueAuditEntry FKdhmyebeihgwxdk54l6ejr54b3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueAuditEntry"
    ADD CONSTRAINT "FKdhmyebeihgwxdk54l6ejr54b3" FOREIGN KEY (user_id) REFERENCES public."User"(id);

--
-- Name: IssueMember FKf2w5e2c4nseds7tx23xitcceh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueMember"
    ADD CONSTRAINT "FKf2w5e2c4nseds7tx23xitcceh" FOREIGN KEY (issue_id) REFERENCES public."PublicationIssue"(id);

--
-- Name: IssueMember FK4pb9lphk0jefvi25m347aisxj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueMember"
    ADD CONSTRAINT "FK4pb9lphk0jefvi25m347aisxj" FOREIGN KEY (message_id) REFERENCES public."Message"(id);

--
-- Name: IssueMember FKi73654iekssaluaymrr1yvk6s; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueMember"
    ADD CONSTRAINT "FKi73654iekssaluaymrr1yvk6s" FOREIGN KEY (override_id) REFERENCES public."IssueOverride"(id);

--
-- Name: IssueOverride FKpk6pgvtxqyi7wk2h6u3mxccfk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueOverride"
    ADD CONSTRAINT "FKpk6pgvtxqyi7wk2h6u3mxccfk" FOREIGN KEY (author_id) REFERENCES public."User"(id);

--
-- Name: IssueOverride FKnc0w80uck53xck3asumomp1f7; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueOverride"
    ADD CONSTRAINT "FKnc0w80uck53xck3asumomp1f7" FOREIGN KEY (issue_id) REFERENCES public."PublicationIssue"(id);

--
-- Name: IssueOverride FKat2r069xg53ggxwgpb93anegs; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."IssueOverride"
    ADD CONSTRAINT "FKat2r069xg53ggxwgpb93anegs" FOREIGN KEY (message_id) REFERENCES public."Message"(id);

--
-- Name: PublicationIssue FK3byv55vdym4gm9xkej0p4vko5; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "FK3byv55vdym4gm9xkej0p4vko5" FOREIGN KEY ("publishedBy_id") REFERENCES public."User"(id);

--
-- Name: PublicationIssue FKenagt3ieoiv8aify21x27sgvw; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "FKenagt3ieoiv8aify21x27sgvw" FOREIGN KEY ("retiredBy_id") REFERENCES public."User"(id);

--
-- Name: PublicationIssue FK541rqo4pgqakr2cn55pu3wa3t; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "FK541rqo4pgqakr2cn55pu3wa3t" FOREIGN KEY (series_id) REFERENCES public."PublicationSeries"(id);

--
-- Name: PublicationIssue FKgipwxtphm1gd687gpkh545fs; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssue"
    ADD CONSTRAINT "FKgipwxtphm1gd687gpkh545fs" FOREIGN KEY (supersedes_id) REFERENCES public."PublicationIssue"(id);

--
-- Name: PublicationIssueDesc FKce6a81g3w4o47jvg0gru4fc6e; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssueDesc"
    ADD CONSTRAINT "FKce6a81g3w4o47jvg0gru4fc6e" FOREIGN KEY (entity_id) REFERENCES public."PublicationIssue"(id);

--
-- Name: PublicationIssueDesc FKte8jrotfgfcxv16j748lqt9wo; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationIssueDesc"
    ADD CONSTRAINT "FKte8jrotfgfcxv16j748lqt9wo" FOREIGN KEY ("replacedBy_id") REFERENCES public."User"(id);

--
-- Name: PublicationSeries FK1jyqgntj2p9agug9sv2uks9td; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "FK1jyqgntj2p9agug9sv2uks9td" FOREIGN KEY (category_id) REFERENCES public."PublicationCategory"(id);

--
-- Name: PublicationSeries FKmjvl4nynaphacv4tlsd1ec5gs; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "FKmjvl4nynaphacv4tlsd1ec5gs" FOREIGN KEY (domain_id) REFERENCES public."Domain"(id);

--
-- Name: PublicationSeries FK_pub_series_source_series; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries"
    ADD CONSTRAINT "FK_pub_series_source_series" FOREIGN KEY ("sourceSeries_id") REFERENCES public."PublicationSeries"(id);

--
-- Name: PublicationSeriesDesc FK6ht1fjhdw5rfkg222aujmgobj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeriesDesc"
    ADD CONSTRAINT "FK6ht1fjhdw5rfkg222aujmgobj" FOREIGN KEY (entity_id) REFERENCES public."PublicationSeries"(id);

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
-- Name: PublicationSeries_languages FK26wanwvqt302qhcqkjjxevrhf; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public."PublicationSeries_languages"
    ADD CONSTRAINT "FK26wanwvqt302qhcqkjjxevrhf" FOREIGN KEY ("PublicationSeries_id") REFERENCES public."PublicationSeries"(id);
