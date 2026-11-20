/*
 * Copyright 2016 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.niord.core.message;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.jpa.HibernateHints;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.StandardBasicTypes;
import org.niord.core.NiordApp;
import org.niord.core.service.BaseService;
import org.niord.core.settings.annotation.Setting;
import org.niord.model.message.Status;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static org.niord.core.settings.Setting.Type.Boolean;

import jakarta.inject.Inject;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.Query;
import jakarta.persistence.TemporalType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A PostgreSQL full-text-search index over all messages, the alternative to {@link MessageLuceneIndex}.
 * <p>
 * It maintains a denormalized {@code message_search_text(message_id, lang, search_tsv, msg_updated)}
 * table. The searchable text is assembled per language by {@link MessageSearchTextBuilder} (the same
 * field-walk the Lucene index uses, HTML stripped) and stored as a PostgreSQL {@code tsvector}
 * ({@code to_tsvector(<config>, ...)}); a GIN index on {@code search_tsv} makes the match fast. Queries use
 * {@code websearch_to_tsquery} — Google-style syntax (space = AND, {@code "quoted phrase"}, {@code or},
 * {@code -excluded}) — matched with the {@code @@} operator. The text-search configuration is chosen per
 * language (English / Danish stemming, or {@code simple} otherwise), so search understands word stems
 * (e.g. "harbours" matches "harbour") and ignores common stop words.
 * <p>
 * The table is owned by Liquibase (changeset {@code V002}, created in every deployment) and maintained
 * every minute, symmetric to {@link MessageLuceneIndex}, so the index is always present and fresh. The
 * {@code disableLucene} setting (default {@code false} = Lucene) only selects which index a free-text SEARCH
 * reads; the long-term goal is to remove Lucene entirely. See docs/lucene-postgres-toggle.md.
 * <p>
 * Behaviour differs from the Lucene backend &mdash; this is true full-text search, not the old
 * substring/wildcard matching: matching is on whole-word STEMS (no infix or wildcard matching), the default
 * operator is AND (Google-style) rather than OR, stop words are ignored, and accent handling follows the
 * chosen text-search configuration. The index-exclusion rules ({@code DELETED} status,
 * {@code excludeFromMessageIndex} series) are unchanged. The full list of divergences is in
 * docs/lucene-postgres-toggle.md.
 */
@Component
@SuppressWarnings("unused")
public class MessagePostgresIndex extends BaseService {

    /** The setting key that, when {@code true}, selects this PostgreSQL backend over Lucene. */
    public static final String DISABLE_LUCENE_SETTING = "disableLucene";

    /** The declaration of {@link #DISABLE_LUCENE_SETTING}: {@code false}, the Lucene index */
    public static final org.niord.core.settings.Setting DISABLE_LUCENE =
            new org.niord.core.settings.Setting(DISABLE_LUCENE_SETTING, false)
                    .description("Use the experimental PostgreSQL free-text message search instead of the Lucene index")
                    .editable(true);

    static final int POSTGRES_MAX_INDEX_COUNT = 5000;

    /** The search table, which no entity maps */
    static final String SEARCH_TABLE = "message_search_text";

    /**
     * Sentinel {@code lang} value for the marker ("tombstone") row written for a message that is
     * processed but not indexed (DELETED / excluded series). It records the message's {@code msg_updated}
     * so the incremental marker advances past it — exactly as the Lucene index advances its
     * marker over excluded messages. Searches always query a real (non-empty) language code,
     * so these rows are never returned.
     */
    static final String TOMBSTONE_LANG = "";

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessagePostgresIndex.class);

    @Setting(value = "messageIndexIncludeDeletedMessages", defaultValue = "false", type = Boolean,
            description = "Whether the message index should include deleted messages or not. Read at start-up.")
    Boolean includeDeletedMessages;

    @Inject
    MessageQueryService messageQueryService;

    @Inject
    NiordApp app;

    /** Taken by every writer of the table; package-private so a test can keep the scheduled pass out */
    final ReentrantLock lock = new ReentrantLock();

    /**
     * Whether the table holds every message: set once a pass that found fewer messages after the marker than it may
     * index has committed, and cleared by a re-index. In memory only, so false after a start until the first pass.
     * Read on other threads than the passes ({@code GET /rest/monitor/message-index}).
     */
    volatile boolean allMessagesIndexed;

    /**
     * The messages the passes indexed since the start or the last re-index: the count the line that says the table is
     * complete gives ({@link #markComplete(int)}). Written by the passes, under {@link #lock}.
     */
    volatile int indexedInFill;

    /**
     * Counts the re-indexes, so that a pass's commit that ends after a re-index has begun does not mark the table the
     * re-index is filling as complete
     */
    volatile int fill;

    /**
     * Called every minute to incrementally maintain the PostgreSQL search table. It always runs (symmetric
     * to the Lucene index), so the table stays fresh whether or not {@code disableLucene} currently routes
     * searches to it; the incremental marker persists in the table, so it resumes correctly after a restart.
     *
     * @return the number of messages (re-)indexed; the scheduler ignores it
     */
    @Transactional
    @Scheduled(cron = "48 */1 * * * ?")
    int updatePostgresIndex() {
        lock.lock();
        try {
            return updateIndex(POSTGRES_MAX_INDEX_COUNT);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns if all messages have been indexed: false after a start until the first pass has committed, and from a
     * re-index until it has committed
     */
    public boolean allMessagesIndexed() {
        return allMessagesIndexed;
    }

    /**
     * Counts a pass's messages into the fill and, when the pass completes the table, marks it complete once the
     * pass's transaction has committed (at once when no transaction runs), so the table is never reported complete
     * while its rows cannot yet be read. The caller holds {@link #lock}.
     *
     * @param completesTheIndex whether the pass found fewer messages after the marker than it may index
     * @param indexed           the messages the pass indexed
     */
    void passEnded(boolean completesTheIndex, int indexed) {
        indexedInFill += indexed;
        if (!completesTheIndex) {
            return;
        }
        int passFill = fill;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    markComplete(passFill);
                }
            });
        } else {
            markComplete(passFill);
        }
    }

    /** Begins a new fill (a re-index): the table is not complete until a pass of this fill has committed */
    synchronized void startFill() {
        fill++;
        allMessagesIndexed = false;
        indexedInFill = 0;
    }

    /**
     * Marks the table complete, unless a re-index has begun since the pass that completed it. The first time after a
     * start or a re-index, it logs one INFO line that names the index and the messages this fill indexed, also when the
     * fill found nothing to do, so a person who prepares a database can see in the log when the searches answer in
     * full; the passes after that log nothing.
     *
     * @param passFill the fill the completing pass belonged to
     */
    synchronized void markComplete(int passFill) {
        if (passFill == fill && !allMessagesIndexed) {
            allMessagesIndexed = true;
            log.info("Message search index complete: PostgreSQL, " + indexedInFill + " messages indexed in this fill");
        }
    }

    /**
     * Rebuilds the entire PostgreSQL search table and returns the number of indexed messages.
     * Unlike the scheduled maintainer this is unconditional, so it can prime the table right after the
     * backend is enabled (and is the entry point the integration tests drive).
     */
    @Transactional
    public int recreateIndex() {
        lock.lock();
        try {
            searchTableStatement("delete from message_search_text").executeUpdate();
            startFill();
            return updateIndex(Integer.MAX_VALUE);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Incrementally indexes the messages after the most recently indexed one, in the order of their
     * {@code updated} time and id, and the messages of the look-back window behind the marker
     * ({@link MessageQueryService#INDEX_LOOK_BACK}) whose rows do not hold their {@code updated} time
     * ({@link #findMissedMessages}). The caller holds {@link #lock} and a transaction.
     * <p>
     * The rows of a missed message hold a time before the marker, so the marker never moves back. A missed
     * message whose search text cannot be built is logged and left for the next pass, so it does not change
     * what the pass does with the messages after the marker.
     *
     * @param maxIndexCount the max number of messages to index in one pass, after the marker and in the window each
     * @return the number of messages (re-)indexed
     */
    int updateIndex(int maxIndexCount) {
        Marker marker = getMarker();
        long t0 = System.currentTimeMillis();

        List<Message> updatedMessages = messageQueryService.findUpdatedMessagesAfter(
                marker.updated(), marker.messageId(), maxIndexCount);

        // Fewer messages after the marker than a pass may index: once this pass has committed, the table holds every
        // message
        boolean completesTheIndex = updatedMessages.size() < maxIndexCount;

        List<Message> missedMessages = findMissedMessages(marker, maxIndexCount);
        if (updatedMessages.isEmpty() && missedMessages.isEmpty()) {
            passEnded(completesTheIndex, 0);
            return 0;
        }

        for (Message message : updatedMessages) {
            indexMessage(message, searchTexts(message));
        }

        // Then the missed messages, each on its own: one whose text cannot be built is tried again by the next pass
        int indexed = updatedMessages.size();
        for (Message message : missedMessages) {
            Map<String, String> texts;
            try {
                texts = searchTexts(message);
            } catch (RuntimeException ex) {
                log.warn("Error indexing message " + message.getId() + " behind the marker (PostgreSQL): "
                        + ex.getMessage(), ex);
                continue;
            }
            indexMessage(message, texts);
            indexed++;
        }

        log.info("Indexed " + indexed + " messages (" + (indexed - updatedMessages.size())
                + " behind the marker) (PostgreSQL) in " + (System.currentTimeMillis() - t0) + " ms");
        passEnded(completesTheIndex, indexed);
        return indexed;
    }

    /**
     * Returns the messages of the look-back window behind the marker whose rows do not hold their
     * {@code updated} time: a message committed after a pass read past its time (LUCENE-6), or saved again with
     * a time inside the window. Exact, since the rows record the time they were built from, so a pass after the
     * window was indexed finds nothing; at most {@code maxCount}.
     *
     * @param marker   the marker the pass started from
     * @param maxCount the max number of messages to return
     * @return the messages to index again, oldest first
     */
    private List<Message> findMissedMessages(Marker marker, int maxCount) {
        if (marker.equals(Marker.NONE)) {
            return List.of();
        }
        Timestamp after = Timestamp.from(marker.updated().toInstant().minus(MessageQueryService.INDEX_LOOK_BACK));
        List<?> rows = searchTableStatement(
                "select m.id from public.\"Message\" m "
                        + "where m.updated > ? and (m.updated < ? or (m.updated = ? and m.id <= ?)) "
                        + "and not exists (select 1 from message_search_text s "
                        + "where s.message_id = m.id and s.msg_updated = m.updated) "
                        + "order by m.updated, m.id limit ?")
                .setParameter(1, after, TemporalType.TIMESTAMP)
                .setParameter(2, marker.updated(), TemporalType.TIMESTAMP)
                .setParameter(3, marker.updated(), TemporalType.TIMESTAMP)
                .setParameter(4, marker.messageId())
                .setParameter(5, maxCount)
                .getResultList();
        List<Integer> ids = new ArrayList<>(rows.size());
        for (Object row : rows) {
            ids.add(((Number) row).intValue());
        }
        return messageQueryService.findByIds(ids);
    }

    /**
     * Builds the search text of each supported language of the given message, or returns {@code null} when the
     * message is not indexed (DELETED / excluded series). Mirrors {@code MessageLuceneIndex.shouldAddMessage}.
     *
     * @param message the message
     * @return the text by language code, or {@code null}
     */
    private Map<String, String> searchTexts(Message message) {
        if (!shouldAddMessage(message)) {
            return null;
        }
        Map<String, String> texts = new LinkedHashMap<>();
        for (String language : app.getLanguages()) {
            texts.put(app.getLanguage(language), MessageSearchTextBuilder.buildSearchText(message, language));
        }
        return texts;
    }

    /**
     * Re-indexes a single message: removes its rows and inserts one {@code tsvector} row per supported language,
     * or one marker row when it is not indexed. Mirrors {@code MessageLuceneIndex.indexMessage}.
     *
     * @param message the message
     * @param texts   its search text by language code, or {@code null} when it is not indexed
     */
    private void indexMessage(Message message, Map<String, String> texts) {
        searchTableStatement("delete from message_search_text where message_id = ?")
                .setParameter(1, message.getId())
                .executeUpdate();

        if (texts != null) {
            for (Map.Entry<String, String> entry : texts.entrySet()) {
                String lang = entry.getKey();
                String text = entry.getValue();
                searchTableStatement(
                        "insert into message_search_text (message_id, lang, search_tsv, msg_updated) "
                                + "values (?, ?, to_tsvector(cast(? as regconfig), ?), ?)")
                        .setParameter(1, message.getId())
                        .setParameter(2, lang)
                        .setParameter(3, tsConfig(lang))
                        .setParameter(4, text)
                        .setParameter(5, message.getUpdated(), TemporalType.TIMESTAMP)
                        .executeUpdate();
            }
        } else {
            // The message is excluded from search (DELETED / excludeFromMessageIndex series). Keep a single
            // marker row so the incremental marker (getMarker) still advances past it,
            // matching how MessageLuceneIndex advances its marker over excluded messages. The sentinel lang
            // is never queried, so the message stays absent from results.
            searchTableStatement(
                    "insert into message_search_text (message_id, lang, search_tsv, msg_updated) "
                            + "values (?, ?, null, ?)")
                    .setParameter(1, message.getId())
                    .setParameter(2, TOMBSTONE_LANG)
                    .setParameter(3, message.getUpdated(), TemporalType.TIMESTAMP)
                    .executeUpdate();
        }
    }

    /**
     * Determines whether the message should be indexed. Identical to the Lucene backend: messages whose
     * series is flagged {@code excludeFromMessageIndex} are never indexed, and {@code DELETED} messages
     * are excluded unless {@code messageIndexIncludeDeletedMessages} is set.
     */
    private boolean shouldAddMessage(Message message) {
        if (message.getMessageSeries().getExcludeFromMessageIndex() != null &&
                message.getMessageSeries().getExcludeFromMessageIndex()) {
            return false;
        }
        return includeDeletedMessages || message.getStatus() != Status.DELETED;
    }

    /**
     * The incremental marker: the {@code updated} time and the id of the last message a pass processed.
     *
     * @param updated   the {@code updated} time of the message, as its rows store it, to the microsecond
     * @param messageId the id of the message
     */
    record Marker(Date updated, int messageId) {

        /** The marker of an empty table, before every message */
        static final Marker NONE = new Marker(new Date(0), 0);
    }

    /**
     * Returns the incremental marker: the {@code msg_updated} time and id of the last row in the order the
     * passes read messages ({@code updated} time, then id), or {@link Marker#NONE} when the table is empty.
     * Marker (tombstone) rows for excluded messages count, so the marker advances past excluded messages
     * (like the Lucene index).
     * <p>
     * The time is read as the type it was written with ({@code java.util.Date}, the JDBC timestamp), so it
     * comes back as the same instant. Read untyped, Hibernate 7 returns a {@code LocalDateTime}
     * ({@code hibernate.query.native.prefer_jdbc_datetime_types} is off by default), which the marker once did
     * not accept: every pass then started from the epoch and rewrote the same first 5,000 messages. It stays the
     * {@code java.sql.Timestamp} it is read as, with the microseconds the column stores. Copied into a plain
     * {@code Date}, it was cut to the millisecond and lay before the messages it named, so when a pass ended among
     * messages sharing a time with microseconds (one the database writes, {@code now()}), the next pass read them
     * again from the first, and the rest of them never.
     */
    Marker getMarker() {
        List<?> rows = searchTableStatement(
                "select msg_updated, message_id from message_search_text where msg_updated is not null "
                        + "order by msg_updated desc, message_id desc limit 1")
                .unwrap(NativeQuery.class)
                .addScalar("msg_updated", StandardBasicTypes.TIMESTAMP)
                .addScalar("message_id", StandardBasicTypes.INTEGER)
                .getResultList();
        if (rows.isEmpty()) {
            return Marker.NONE;
        }
        Object[] row = (Object[]) rows.get(0);
        return new Marker((Date) row[0], (Integer) row[1]);
    }

    /**
     * Returns a native statement on the search table that names its table and does not flush the session
     * first. No entity maps the table, so nothing waiting in the session can change what the statement reads
     * or writes. Left to the defaults, Hibernate flushes the whole session before every native statement
     * that names no table, dirty-checking every entity loaded so far, and an update that names no table
     * drops every entity's second-level cache region. A pass loads all its messages and then writes two or
     * three statements for each, so those flushes made a pass of 5,000 messages take minutes. The look-back
     * query also reads the message table; a pass changes no message, so nothing waiting in the session can
     * change what it reads either.
     *
     * @param sql the statement
     * @return the statement as a query
     */
    private Query searchTableStatement(String sql) {
        return em.createNativeQuery(sql)
                .setHint(HibernateHints.HINT_NATIVE_SPACES, SEARCH_TABLE)
                .setFlushMode(FlushModeType.COMMIT);
    }

    /**
     * Performs a full-text search and returns the ids of matching messages, ordered by full-text relevance
     * (best match first via {@code ts_rank}). Same id-only contract as {@code MessageLuceneIndex.searchIndex}
     * (whose ids are likewise in relevance/score order); {@code MessageService} preserves this order when the
     * search has no explicit sort.
     *
     * @param freeTextSearch the search string (Google-style {@code websearch_to_tsquery} syntax)
     * @param language       the language to search
     * @param maxHits        the max number of hits to return
     * @return the matching message ids
     */
    @Transactional
    public List<Long> searchIndex(String freeTextSearch, String language, int maxHits) {
        String lang = app.getLanguage(language);
        List<Object> binds = new ArrayList<>();
        binds.add(lang);

        String sql;
        if (StringUtils.isBlank(freeTextSearch)) {
            // Mirrors Lucene's MatchAllDocsQuery for a blank query (tombstone rows use a sentinel lang and
            // are therefore excluded by the lang filter).
            sql = "select message_id from message_search_text where lang = ?";
        } else {
            // Google-style full-text query; the same text-search config used to build the tsvector parses it.
            // Results are ordered by full-text relevance (best match first); MessageService preserves this
            // order when no explicit sort is chosen. message_id is a deterministic tie-breaker.
            String config = tsConfig(lang);
            sql = "select message_id from message_search_text "
                    + "where lang = ? and search_tsv @@ websearch_to_tsquery(cast(? as regconfig), ?) "
                    + "order by ts_rank(search_tsv, websearch_to_tsquery(cast(? as regconfig), ?)) desc, message_id desc";
            binds.add(config);
            binds.add(freeTextSearch);
            binds.add(config);
            binds.add(freeTextSearch);
        }
        sql += " limit ?";
        binds.add(maxHits);

        Query query = em.createNativeQuery(sql);
        for (int i = 0; i < binds.size(); i++) {
            query.setParameter(i + 1, binds.get(i));
        }

        List<?> rows = query.getResultList();
        List<Long> ids = new ArrayList<>(rows.size());
        for (Object row : rows) {
            ids.add(((Number) row).longValue());
        }
        return ids;
    }

    /**
     * Maps a Niord language code to a built-in PostgreSQL text-search configuration. {@code english} and
     * {@code danish} give proper stemming + stop words; anything else falls back to {@code simple}
     * (tokenize + lowercase only — no stemming/stop-words, but always available).
     */
    private static String tsConfig(String lang) {
        if (lang == null) {
            return "simple";
        }
        String l = lang.toLowerCase(Locale.ROOT);
        if (l.startsWith("en")) {
            return "english";
        }
        if (l.startsWith("da")) {
            return "danish";
        }
        return "simple";
    }
}
