--liquibase formatted sql
--changeset niord:009-repo-files splitStatements:true endDelimiter:;
-- The repository files (docs/repo-files-in-database-plan.md §4.1; org.niord.core.repo.RepoStore): every file the
-- repository used to keep on the application's volume under repo/ (message attachments and embedded images, custom
-- map thumbnails, publication files, the edit sessions under temp/) and the archive of superseded publication
-- editions (archive/), as rows. Thumbnails, generated map images and previews are not rows: they are a cache on the
-- volume, made again on a miss.
--
-- One table, no entity: RepoStore reads and writes it through JDBC. path is the repository path as it was on disk
-- (decoded, forward slashes, no leading slash), which is also the path in the file's URL and in the rows that name
-- it, so nothing else in the schema changes. It is the primary key under the C collation, which serves the prefix
-- LIKE the folder operations use (a copy, a delete, a listing of an edit session's revision folder). folder is the
-- parent path, for the listing. size, created and updated are the file's; updated feeds the ETag and is compared with
-- the cached thumbnail's time. sha256 is the import's check and nothing else. expires is set on a temp row only
-- (created plus a day), and the hourly sweep deletes what has expired. The bytes sit in data, stored out of line and
-- uncompressed (EXTERNAL): a query that does not name data never reads it, and a file is read whole, in one
-- query (a file is a few MB at most; RepoStore caps a row at 50 MB). No surrogate id: the sample's regeneration
-- captures a table with CREATE TABLE AS, which drops an identity, and resets only Hibernate's "<table>_SEQ"
-- sequences.
--
-- Schema only, no data (D2). Once applied this changeset is never edited, its comments included: every database
-- records its checksum, which ChangelogChecksumPinTest pins.
CREATE TABLE public."RepoFile" (
    path character varying(1024) COLLATE "C" NOT NULL,
    folder character varying(1024) NOT NULL,
    name character varying(255) NOT NULL,
    size bigint NOT NULL,
    sha256 character varying(64),
    created timestamp(6) with time zone NOT NULL,
    updated timestamp(6) with time zone NOT NULL,
    expires timestamp(6) with time zone,
    data bytea NOT NULL,
    CONSTRAINT "RepoFile_pkey" PRIMARY KEY (path)
);
ALTER TABLE public."RepoFile" ALTER COLUMN data SET STORAGE EXTERNAL;
CREATE INDEX "RepoFile_folder_idx" ON public."RepoFile" (folder);
CREATE INDEX "RepoFile_expires_idx" ON public."RepoFile" (expires) WHERE expires IS NOT NULL;
