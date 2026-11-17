--liquibase formatted sql
--changeset niord:002-message-search-text splitStatements:true endDelimiter:;
-- Search index backing the PostgreSQL full-text MESSAGE search (MessagePostgresIndex), selected at runtime
-- by the `disableLucene` setting. This is a derived / rebuildable search artifact: the per-language
-- searchable text is assembled in Java (MessageSearchTextBuilder, HTML stripped) and stored as a PostgreSQL
-- tsvector via to_tsvector(<config>, ...); MessagePostgresIndex's scheduled maintainer / recreateIndex()
-- (re)populates it. It is therefore NOT base data — created empty here, carrying no base-seed rows and no
-- foreign key to "Message" (orphan rows are cleaned up by reindexing). The GIN index on the tsvector backs
-- the `search_tsv @@ websearch_to_tsquery(...)` match. tsvector / to_tsvector / websearch_to_tsquery are
-- built into PostgreSQL, so no extension is required.
CREATE TABLE IF NOT EXISTS message_search_text (
    message_id  bigint     NOT NULL,
    lang        varchar(8) NOT NULL,
    search_tsv  tsvector,
    msg_updated timestamp,
    PRIMARY KEY (message_id, lang)
);

CREATE INDEX IF NOT EXISTS message_search_text_tsv_idx
    ON message_search_text USING gin (search_tsv);
