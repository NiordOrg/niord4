--liquibase formatted sql
--changeset niord:007-bootstrap-state splitStatements:true endDelimiter:;
-- The bootstrap marker (docs/code-data-config-plan.md §7.2; org.niord.core.bootstrap.BootstrapMarker). One row,
-- id 1, written once at the first start of a version that has this table: pending when the database held no
-- domain, message or area, existing otherwise. The bootstrap runner moves pending (or failed) to in-progress
-- and then to applied or failed; it accepts no other state, so a database is bootstrapped at most once and a
-- database that held data never.
--
-- hash is the SHA-256 over the hashes of the run's archives, in order; error names the job, its file and its
-- status when a run failed; "baseVersion" is the niord-base version that last wrote the row.
--
-- Created empty: schema only, no data (D2). The row is written by the application, never by a changeset. Once
-- applied this changeset is never edited, its comments included: every database records its checksum, which
-- ChangelogChecksumPinTest pins.
CREATE TABLE public."BootstrapState" (
    id integer NOT NULL,
    state character varying(32) NOT NULL,
    hash character varying(64),
    error text,
    "baseVersion" character varying(255) NOT NULL,
    updated timestamp(6) with time zone NOT NULL,
    CONSTRAINT "BootstrapState_pkey" PRIMARY KEY (id),
    CONSTRAINT "BootstrapState_id_check" CHECK ((id = 1)),
    CONSTRAINT "BootstrapState_state_check" CHECK (((state)::text = ANY ((ARRAY['pending'::character varying, 'existing'::character varying, 'in-progress'::character varying, 'applied'::character varying, 'failed'::character varying])::text[])))
);
