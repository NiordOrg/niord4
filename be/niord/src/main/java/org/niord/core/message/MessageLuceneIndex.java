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

import org.springframework.stereotype.Component;

import static org.niord.core.settings.Setting.Type.Boolean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.niord.core.util.MdcContext;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.complexPhrase.ComplexPhraseQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.niord.core.NiordApp;
import org.niord.core.service.BaseService;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.util.LuceneUtils;
import org.niord.model.message.Status;
import org.slf4j.Logger;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.CompletableFuture;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Lucene index used for free-text searching all messages.
 * <p>
 * The index will initially index all messages, and subsequently check every minute
 * for changed message to add or update in the index.
 * <p>
 * Note to self: Using "Hibernate Search" for message (as for AtoNs), was ruled out because it would
 * be too complex to index all related entities by language.
 */
@Component
@SuppressWarnings("unused")
public class MessageLuceneIndex extends BaseService {

    final static String LUCENE_ID_FIELD             = "id";
    final static String LUCENE_SEARCH_FIELD         = "message";
    final static String LUCENE_LAST_UPDATED_TIME    = "lastUpdatedTime";
    final static String LUCENE_LAST_UPDATED_ID      = "lastUpdatedId";
    final static int LUCENE_MAX_INDEX_COUNT         = 5000;
    final static int LUCENE_OPTIMIZE_INDEX_COUNT    = 5000;
    final static int LUCENE_MAX_NUM_SEGMENTS        = 4;

        @Setting(value="messageIndexPath", defaultValue="${niord.home}/message-index",
            description="The message Lucene index directory. Read at start-up.")
    Path indexFolder;

        @Setting(value = "messageIndexDeleteOnStartup", defaultValue = "true", type = Boolean,
            description = "Whether the message lucene index is re-created for each restart or not. Read at start-up.")
    Boolean deleteOnStartup;

        @Setting(value = "messageIndexIncludeDeletedMessages", defaultValue = "false", type = Boolean,
            description = "Whether the message index should include deleted messages or not. Read at start-up.")
    Boolean includeDeletedMessages;

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageLuceneIndex.class);

    @Inject
    MessageQueryService messageQueryService;

    @Inject
    NiordApp app;

    /**
     * This bean through its proxy. The deferred start-up pass runs on a {@code java.util.Timer} thread and calls
     * {@link #updateLuceneIndex()} through it: called on {@code this}, past the proxy, the method's
     * {@code @Transactional} does not apply, the messages arrive detached, and the first lazy association
     * the indexing reads fails.
     */
    @Inject
    ObjectProvider<MessageLuceneIndex> self;

    /**
     * One writer lock per index folder, shared by every instance in the JVM. Lucene allows one
     * {@link IndexWriter} per folder and, inside one JVM, refuses a second one at once
     * ("Lock held by this virtual machine") instead of waiting for it. Production runs one application
     * context and so one instance, but the integration-test JVM keeps several cached contexts alive, each
     * with its own instance, start-up pass and schedule on the same index folder, and a lock per instance
     * would not keep their writers apart.
     */
    private static final ConcurrentMap<Path, ReentrantLock> WRITER_LOCKS = new ConcurrentHashMap<>();

    DirectoryReader reader;
    int optimizeIndexCount = 0;

    /**
     * Whether the index holds every message: set by the first pass that finds fewer messages after the marker than it
     * may index, once its commit can be read, and cleared when the index is deleted. Read by the searches, on other
     * threads than the passes.
     */
    volatile boolean allMessagesIndexed;

    /**
     * The messages whose {@code updated} time lies in the look-back window behind the marker
     * ({@link MessageQueryService#INDEX_LOOK_BACK}) and that this instance indexed (or left out) as they are now:
     * id to {@code updated} time. A pass indexes a message of the window only when it is not here with the same
     * time, so the window is not indexed again every minute while the marker stands still. The map starts empty,
     * so after a restart that keeps the index the first pass indexes the window once. Kept apart from the
     * index: a stale entry can only skip a message of the window, and a pass that finds the index wiped starts
     * from the first message again.
     */
    final Map<Integer, Instant> indexedInWindow = new ConcurrentHashMap<>();

    /**
     * Returns the lock that every writer on the given index folder takes: the same lock for every
     * instance in the JVM, however the folder is spelled.
     *
     * @param indexFolder the index folder
     * @return the writer lock of the folder
     */
    static ReentrantLock writerLock(Path indexFolder) {
        return WRITER_LOCKS.computeIfAbsent(indexFolder.toAbsolutePath().normalize(), folder -> new ReentrantLock());
    }

    /**
     * Initialize the index
     */
    @EventListener(ApplicationReadyEvent.class)
    void init() {
        // Create the lucene index directory
        if (!Files.exists(indexFolder)) {
            try {
                Files.createDirectories(indexFolder);
            } catch (IOException e) {
                log.error("Error creating index dir " + indexFolder, e);
            }
        }

        // Check if we need to delete the old index on start-up
        if (deleteOnStartup) {
            ReentrantLock lock = writerLock(indexFolder);
            lock.lock();
            try {
                deleteIndex();
            } catch (IOException | RuntimeException e) {
                // A search index never stops the application from starting
                log.error("Failed re-creating the index on startup", e);
            } finally {
                lock.unlock();
            }
        }

        // Wait 5 seconds before initializing the message index
        new Timer().schedule(
                new TimerTask() {
                    // Deferred start-up work on a java.util.Timer thread: no request to inherit from,
                    // so mint a job id rather than logging these lines with an empty req= field.
                    private final Runnable body = MdcContext.withFreshJobId(MessageLuceneIndex.this::startUpPass);

                    @Override
                    public void run() {
                        body.run();
                    }
                },
                5000
        );
    }

    /**
     * The deferred start-up pass, which {@link #init()} schedules on a {@code java.util.Timer} thread: the
     * index update through the proxy, so that it runs in its transaction. Looking up the bean and starting the
     * transaction can fail too (a shutdown within the delay); that is logged here, since an exception leaving a
     * timer task reaches stderr only.
     */
    void startUpPass() {
        try {
            self.getObject().updateLuceneIndex();
        } catch (RuntimeException ex) {
            log.error("Error updating Lucene index: " + ex.getMessage(), ex);
        }
    }

    /**
     * Clean up Lucene index
     */
    @PreDestroy
    private void closeIndex() {
        closeReader();
    }

    /**
     * Called when the service starts up and then every minute to update the Lucene index
     * <p>
     * Note to self: It's tempting to use here. However, that would lock search access
     * to the index while it is being updated, and we really do not want that.
     */
    @Transactional
    @Scheduled(cron="38 */1 * * * ?")
    // PORT: dropped CDI @ActivateRequestContext — no Spring drop-in; the scheduled
    // method runs in its own thread and the @Transactional already opens a JPA
    // context. Re-evaluate request-scope activation needs during the IT stage.
    void updateLuceneIndex() {
        ReentrantLock lock = writerLock(indexFolder);
        lock.lock();
        try {
            updateLuceneIndex(LUCENE_MAX_INDEX_COUNT);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the analyzer to use.
     * <p>
     * Something seems to have changed in Lucene. In earlier versions (e.g. 4.6), you could
     * use the StandardAnalyzer and quoted phrase searches including stop words (e.g. "the").<br>
     * However, in the current version of Lucene, it does not seem to work properly.
     * Use ClassicAnalyzer instead.
     *
     * @return the analyzer to use.
     */
    private Analyzer getAnalyzer() {
        return new StandardAnalyzer();
    }

    /**
     * Returns if all messages have been indexed. False from a start that deleted the index (or a re-index) until a
     * pass has indexed every message again: a search in that window answers from the messages indexed so far, which
     * {@code MessageSearchService} marks on its result.
     *
     * @return if all messages have been indexed
     */
    public boolean allMessagesIndexed() {
        return allMessagesIndexed;
    }

    /**
     * Returns the language specific language field
     * @param language the language
     * @return the language specific language field
     */
    private String searchField(String language) {
        return LUCENE_SEARCH_FIELD + "_" + app.getLanguage(language);
    }

    /**
     * Returns the messages after the given marker, in the order of their {@code updated} time and id
     * @param marker the time and id of the last message indexed
     * @param maxCount the max number of messages to return
     * @return the updated messages
     */
    private List<Message> findUpdatedMessages(Marker marker, int maxCount) {
        return messageQueryService.findUpdatedMessagesAfter(
                Timestamp.from(marker.updated()), marker.messageId(), maxCount);
    }

    /**
     * Returns the messages of the look-back window behind the marker ({@link MessageQueryService#INDEX_LOOK_BACK})
     * that this instance has not indexed as they are now: a message committed after a pass read past its
     * {@code updated} time (LUCENE-6), or saved again with a time inside the window. The window is read as
     * stamps, a page at a time, and at most {@code maxCount} messages are returned.
     *
     * @param marker   the marker the pass started from
     * @param maxCount the max number of messages to return
     * @return the messages to index again, oldest first
     */
    private List<Message> findMissedMessages(Marker marker, int maxCount) {
        if (marker.equals(Marker.NONE)) {
            return List.of();
        }
        Timestamp to = Timestamp.from(marker.updated());
        Timestamp after = Timestamp.from(marker.updated().minus(MessageQueryService.INDEX_LOOK_BACK));
        int afterId = Integer.MAX_VALUE;
        Set<Integer> ids = new LinkedHashSet<>();
        while (ids.size() < maxCount) {
            List<MessageQueryService.UpdatedStamp> page = messageQueryService.findUpdatedStampsBetween(
                    after, afterId, to, marker.messageId(), maxCount);
            for (MessageQueryService.UpdatedStamp stamp : page) {
                if (!stamp.updated().toInstant().equals(indexedInWindow.get(stamp.id()))) {
                    ids.add(stamp.id());
                    if (ids.size() == maxCount) {
                        break;
                    }
                }
            }
            if (page.size() < maxCount) {
                break;
            }
            MessageQueryService.UpdatedStamp last = page.get(page.size() - 1);
            after = Timestamp.from(last.updated().toInstant());
            afterId = last.id();
        }
        return messageQueryService.findByIds(ids);
    }

    /**
     * Records the messages a pass indexed whose time lies in the window behind the new marker, and forgets
     * those that the window has left
     *
     * @param marker   the marker the pass committed
     * @param messages the messages the pass indexed (or left out)
     */
    private void rememberIndexed(Marker marker, List<Message> messages) {
        Instant windowStart = marker.updated().minus(MessageQueryService.INDEX_LOOK_BACK);
        indexedInWindow.values().removeIf(updated -> !updated.isAfter(windowStart));
        for (Message message : messages) {
            Instant updated = message.getUpdated().toInstant();
            if (updated.isAfter(windowStart)) {
                indexedInWindow.put(message.getId(), updated);
            }
        }
    }

    /**
     * Adds the given message to the given document
     *
     * @param doc the document to add the message to
     * @param message the message to add
     */
    private void addMessageToDocument(Document doc, Message message) {
        // For each supported language, update a search field. The set of searchable values is defined
        // once in MessageSearchTextBuilder and shared with the PostgreSQL backend so both index the same
        // text; here each visited value is added as a separate tokenized Lucene field (unchanged behavior).
        for (String language : app.getLanguages()) {
            String searchField = searchField(language);
            MessageSearchTextBuilder.forEachSearchValue(message, language,
                    value -> addPhraseSearchField(doc, searchField, value));

            // TODO
            // Add geometry
            //if (message.getGeometry() != null) {
            //}
        }
    }

    /**
     * Creates and returns a Lucene writer
     */
    private IndexWriter getNewWriter() throws IOException {
        try {
            return openWriter();
        } catch (IOException | RuntimeException ex) {
            log.error("Failed to create message Lucene Index in folder " + indexFolder, ex);
            throw ex;
        }
    }

    /**
     * Opens a writer on the index folder, and logs nothing: when it fails, the folder's directory is closed, so
     * that the folder's files can be removed (on Windows too) by a caller that recovers. A failure to close it is
     * added to the exception as a suppressed one, so that it does not hide why the writer could not be opened.
     */
    private IndexWriter openWriter() throws IOException {

        IndexWriterConfig iwc = new IndexWriterConfig(getAnalyzer());
        // Add new documents to an existing index:
        iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);

        Directory dir = FSDirectory.open(indexFolder);
        try {
            return new IndexWriter(dir, iwc);
        } catch (IOException | RuntimeException ex) {
            try {
                dir.close();
            } catch (IOException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    /**
     * Opens a writer on the index folder for {@link #deleteIndex()}. An index this Lucene version cannot open
     * (written by another version, whose codec it does not know, or damaged) is exactly what deleting the index
     * should cure: its files are removed, with one WARN that names the folder, and an empty index is opened instead.
     * The index is a cache, rebuilt from the database. The caller holds the folder's {@link #writerLock(Path) writer
     * lock}, so no other writer of this JVM is open on the folder; a writer of another process (two backends on one
     * Niord home) holds the folder's own lock, and its index is in use, not unreadable: that failure is passed on,
     * and nothing is removed.
     */
    private IndexWriter openWriterDiscardingUnreadable() throws IOException {
        try {
            return openWriter();
        } catch (LockObtainFailedException e) {
            throw e;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("The message index in {} cannot be opened ({}); removing its files, the index is rebuilt from "
                    + "the database", indexFolder, e.toString());
            closeReader();
            try (Stream<Path> files = Files.list(indexFolder)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Files.deleteIfExists(file);
                }
            }
            return getNewWriter();
        }
    }

    /**
     * Returns the cached index reader, or creates one if none is defined
     * @return the shared index reader
     */
    private DirectoryReader getIndexReader() throws IOException {
        if (reader == null) {
           try {
                reader = DirectoryReader.open(FSDirectory.open(indexFolder));
            } catch (IOException ex) {
                log.error("Failed to open Lucene Index in folder " + indexFolder);
                throw ex;
            }
        }
        return reader;
    }

    /**
     * Closes the given writer
     * @param writer the writer to close
     */
    private void closeWriter(IndexWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.warn("Error closing writer");
            }
        }
    }

    /**
     * Closes the current reader
     */
    private void closeReader() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                log.warn("Error closing reader");
            }
            reader = null;
        }
    }

    /**
     * Refreshes the current reader from the given writer
     *
     * @param writer the index writer
     */
    private void refreshReader(IndexWriter writer) throws IOException {
        closeReader();
        reader = DirectoryReader.open(writer, true, true);
    }

    /**
     * Call this to re-index the message index completely
     */
    @Async
    public CompletableFuture<Integer> recreateIndexAsync() throws IOException {
        int updateCount = recreateIndex();
        return CompletableFuture.completedFuture(updateCount);
    }

    /**
     * Call this to re-index the message index completely. It runs the index pass over every message, so a message
     * that cannot be indexed is logged and skipped as in every pass, and the messages after it are indexed.
     */
    public int recreateIndex() throws IOException {
        ReentrantLock lock = writerLock(indexFolder);
        lock.lock();
        try {
            // delete the old index
            deleteIndex();

            // Update all messages
            return updateLuceneIndex(Integer.MAX_VALUE);

        } finally {
            lock.unlock();
        }
    }

    /**
     * Deletes the current index. The caller holds the folder's {@link #writerLock(Path) writer lock}.
     */
    private void deleteIndex() throws IOException {
        // From here until a pass has indexed every message again, a search answers from part of them
        allMessagesIndexed = false;

        // Delete the index
        IndexWriter writer = null;
        try {
            writer = openWriterDiscardingUnreadable();
            writer.deleteAll();
            writer.setLiveCommitData(new HashMap<String, String>().entrySet());
            writer.commit();
        } finally {
            closeWriter(writer);
            closeReader();
        }
        indexedInWindow.clear();
        log.info(String.format("Lucene index deleted, the marker is %s", getMarker()));

    }

    /**
     * The incremental marker: the {@code updated} time and the id of the last message a pass indexed. The next
     * pass reads the messages after both, so a pass that ends among messages sharing one {@code updated} time
     * leaves the rest of them for the next pass.
     *
     * @param updated   the {@code updated} time of the message, to the microsecond the database stores
     * @param messageId the id of the message
     */
    record Marker(Instant updated, int messageId) {

        /** The marker of an empty index, before every message */
        static final Marker NONE = new Marker(Instant.EPOCH, 0);
    }

    /**
     * Returns the incremental marker of the index's last commit, or {@link Marker#NONE} when the commit has none.
     * An index written before the marker had the id (whose only key was {@code lastUpdate}) has none, and is
     * filled again from the first message.
     *
     * @return the incremental marker
     */
    Marker getMarker() {
        try {
            Map<String, String> userData = getIndexReader().getIndexCommit().getUserData();
            if (userData.containsKey(LUCENE_LAST_UPDATED_TIME) && userData.containsKey(LUCENE_LAST_UPDATED_ID)) {
                return new Marker(Instant.parse(userData.get(LUCENE_LAST_UPDATED_TIME)),
                        Integer.parseInt(userData.get(LUCENE_LAST_UPDATED_ID)));
            }
        } catch (Exception e) {
            log.debug("Could not get the marker from the index reader");
        }
        return Marker.NONE;
    }

    /**
     * Sets the incremental marker that the writer's next commit records. The time is written in full
     * ({@code Instant.toString()}): the database stores microseconds, and a marker cut to the millisecond lies
     * before the messages it names, so the next pass would read them again from the first of their time.
     *
     * @param marker the incremental marker
     * @param writer the index writer
     */
    private void setMarker(Marker marker, IndexWriter writer) {
        Map<String,String> userData = new HashMap<>();
        userData.put(LUCENE_LAST_UPDATED_TIME, marker.updated().toString());
        userData.put(LUCENE_LAST_UPDATED_ID, String.valueOf(marker.messageId()));
        writer.setLiveCommitData(userData.entrySet());
    }

    /**
     * Updates the Lucene index with the messages after the marker, in the order of their {@code updated} time
     * and id, and with the messages of the look-back window behind the marker that this instance has not indexed
     * as they are now ({@link #findMissedMessages}). The caller holds the folder's {@link #writerLock(Path) writer
     * lock}.
     * <p>
     * The marker moves only with the messages after it, never back. A message after the marker that cannot be
     * indexed is logged as an ERROR with its id and skipped: the marker moves past it, and it is indexed when it is
     * saved again (LUCENE-5). A message of the window that cannot be indexed is logged and left for the next pass.
     * Neither changes what the pass does with the other messages. A failure of the database while a message after
     * the marker is read ({@link #isDatabaseFault}) is not skipped: the pass fails, nothing is committed, and the
     * next pass reads the same messages again.
     *
     * @param maxIndexCount max number of messages to index at a time, after the marker and in the window each
     * @return the number of updates
     */
    int updateLuceneIndex(int maxIndexCount) {

        Marker marker = getMarker();

        long t0 = System.currentTimeMillis();
        log.debug(String.format("Indexing at most %d changed messages after %s", maxIndexCount, marker));

        IndexWriter writer = null;
        try {
            // Find the messages after the marker, and those of the window behind it that a pass missed
            List<Message> updatedMessages = findUpdatedMessages(marker, maxIndexCount);
            List<Message> missedMessages = findMissedMessages(marker, maxIndexCount);
            log.debug(String.format("Found %d messages to index, and %d missed ones behind the marker",
                    updatedMessages.size(), missedMessages.size()));
            // Fewer messages after the marker than a pass may index: once this pass's commit can be read, the
            // index holds every message. Not before it, so a search in between is not taken for a complete one
            boolean completesTheIndex = updatedMessages.size() < maxIndexCount;
            if (updatedMessages.isEmpty() && missedMessages.isEmpty()) {
                allMessagesIndexed = true;
                return 0;
            }

            // Create a new index writer
            writer = getNewWriter();

            // Update the index with the changes, each message on its own: one that fails is logged with its id and
            // skipped, and the marker moves past it, so it does not hold back the messages after it (LUCENE-5). One
            // whose document cannot be built keeps the document an earlier pass gave it, if any; one that Lucene
            // refuses when it is added has none, since its earlier document is deleted first. Either is indexed
            // again when it is saved again, which gives it a later updated time. It is remembered with the indexed
            // ones below, so the window behind the marker does not try it again every minute. A failure of the
            // database or of the writer is not the message's fault and fails the pass as a whole (see
            // isDatabaseFault): the marker stays, and the next pass tries the batch again. The re-index
            // (recreateIndex) runs this same pass
            List<Message> handled = new ArrayList<>(updatedMessages);
            int skipped = 0;
            for (Message message : updatedMessages) {
                try {
                    indexMessage(writer, message);
                } catch (AlreadyClosedException ex) {
                    // The writer is gone (a disk failure): no later message can be indexed, the pass fails as a whole
                    throw ex;
                } catch (RuntimeException ex) {
                    if (isDatabaseFault(ex)) {
                        // Every later message of the batch would fail the same way and be skipped for good
                        throw ex;
                    }
                    skipped++;
                    log.error("Error indexing message " + message.getId() + ", skipped until it is saved again: "
                            + ex.getMessage(), ex);
                }
            }

            // Then the missed messages, each on its own: one that fails is tried again by the next pass
            int indexedBehind = 0;
            for (Message message : missedMessages) {
                try {
                    indexMessage(writer, message);
                    handled.add(message);
                    indexedBehind++;
                } catch (RuntimeException ex) {
                    log.warn("Error indexing message " + message.getId() + " behind the marker: " + ex.getMessage(),
                            ex);
                }
            }

            // The marker moves to the last message after it, the newest in the order the messages were read
            Marker newMarker = marker;
            if (!updatedMessages.isEmpty()) {
                Message last = updatedMessages.get(updatedMessages.size() - 1);
                newMarker = new Marker(last.getUpdated().toInstant(), last.getId());
            }
            setMarker(newMarker, writer);

            // Commit the changes
            writer.commit();
            rememberIndexed(newMarker, handled);

            // Re-open the reader from the writer
            refreshReader(writer);
            if (completesTheIndex) {
                allMessagesIndexed = true;
            }

            // Check if we need to optimize the index
            int indexed = handled.size() - skipped;
            optimizeIndexCount += indexed;
            if (optimizeIndexCount > LUCENE_OPTIMIZE_INDEX_COUNT) {
                writer.forceMerge(LUCENE_MAX_NUM_SEGMENTS);
                optimizeIndexCount = 0;
            }

            log.info("Indexed " + indexed + " messages (" + indexedBehind + " behind the marker"
                    + (skipped > 0 ? ", " + skipped + " skipped" : "") + ") in "
                    + (System.currentTimeMillis() - t0) + " ms");

            return indexed;
        } catch (Exception ex) {
            log.error("Error updating Lucene index: " + ex.getMessage(), ex);
            return 0;
        } finally {
            closeWriter(writer);
        }
    }

    /**
     * Whether a failure while a message was indexed came from the database rather than from the message: a
     * {@link PersistenceException} (Hibernate's exceptions among them, such as a lazy relation that cannot be
     * loaded), a {@link DataAccessException} or an {@link SQLException}, anywhere in the cause chain. The pass reads
     * each message's relations lazily, in its one transaction, so a dropped connection, a pool timeout or an SQL
     * error after which PostgreSQL refuses every later statement of the transaction would fail every later message
     * of the batch too. Such a failure is not skipped like a message that cannot be indexed (LUCENE-5): the pass
     * fails as before, and the next pass tries the same messages again.
     *
     * @param ex the failure
     * @return whether it came from the database
     */
    static boolean isDatabaseFault(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = ex; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof PersistenceException || cause instanceof DataAccessException
                    || cause instanceof SQLException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Indexes the given message by deleting and adding the document. The document is built first, so a message
     * whose values cannot be read throws before its earlier document is deleted.
     *
     * @param message the message to index
     */
    private void indexMessage(IndexWriter writer, Message message) {
        Document doc = shouldAddMessage(message) ? messageDocument(message) : null;
        // First delete the message
        deleteMessageFromIndex(writer, message);
        // Then add the message
        if (doc != null) {
            addMessageToIndex(writer, message, doc);
        }
    }

    /**
     * By default, add all eligible messages.
     * @param message the message to check
     * @return whether to add the message to the index
     * @noinspection all
     */
    private boolean shouldAddMessage(Message message) {
        if (message.getMessageSeries().getExcludeFromMessageIndex() != null &&
                message.getMessageSeries().getExcludeFromMessageIndex()) {
            return false;
        }
        return includeDeletedMessages || message.getStatus() != Status.DELETED;
    }

    /**
     * Deletes the given message from the index
     *
     * @param message the message to delete
     */
    private void deleteMessageFromIndex(IndexWriter writer, Message message) {
        try {
            Term idTerm = new Term(LUCENE_ID_FIELD, message.getId().toString());
            writer.deleteDocuments(idTerm);
        } catch (IOException e) {
            log.debug("Error deleting message " + message.getId());
        }
    }

    /**
     * Builds the document of the given message
     *
     * @param message the message
     * @return its document
     */
    private Document messageDocument(Message message) {
        Document doc = new Document();

        // ID field
        doc.add(new StringField(LUCENE_ID_FIELD, message.getId().toString(), Field.Store.YES));

        // Add the message specific fields
        addMessageToDocument(doc, message);
        return doc;
    }

    /**
     * Adds the given message's document to the index
     *
     * @param message the message to add
     * @param doc     its document
     */
    private void addMessageToIndex(IndexWriter writer, Message message, Document doc) {
        // Add the document to the index
        try {
            writer.addDocument(doc);
        } catch (IOException ex) {
            log.error("Error adding message " + message.getId() + " to the Lucene index: " + ex.getMessage(), ex);
        }
    }

    /**
     * If the given value is not null, it is added to the search index
     *
     * @param doc the document to add the field value to
     * @param obj the value to add
     */
    private void addPhraseSearchField(Document doc, String field, Object obj) {
        if (obj != null) {
            String str = (obj instanceof String) ? (String)obj : obj.toString();
            if (StringUtils.isNotBlank(str)) {
                doc.add(new PhraseSearchLuceneField(field, str));
            }
        }
    }

    /**
     * If the given value is not null, it is added to the search index
     *
     * @param doc the document to add the field value to
     * @param obj the value to add
     * @param store the store value of the field
     */
    private void addStringSearchField(Document doc, String field, Object obj, Field.Store store) {
        if (obj != null) {
            String str = (obj instanceof String) ? (String)obj : obj.toString();
            if (StringUtils.isNotBlank(str)) {
                doc.add(new StringField(field, str, store));
            }
        }
    }

    /**
     * Performs a search in the index and returns the ids of matching messages
     *
     * @param freeTextSearch the search string
     * @param language the language to search
     * @param maxHits the max number of hits to return
     * @return the matching ids
     */
    public List<Long> searchIndex(String freeTextSearch, String language, int maxHits) throws IOException, ParseException {
        return searchIndex(freeTextSearch, language, maxHits, false);
    }

    /**
     * Performs a search in the index and returns the ids of matching messages
     *
     * @param freeTextSearch the search string
     * @param language the language to search
     * @param maxHits the max number of hits to return
     * @param literal whether the search string is a list of plain words rather than query syntax.
     *                Set for callers without an identity: wildcard, regular-expression, fuzzy,
     *                range, boost, phrase and boolean syntax is then matched as text, so no query
     *                can force a scan of the whole term dictionary
     * @return the matching ids
     */
    public List<Long> searchIndex(String freeTextSearch, String language, int maxHits, boolean literal)
            throws IOException, ParseException {

        Query query;
        if (StringUtils.isNotBlank(freeTextSearch)) {
            // Normalize query text. A literal query is lower-cased (the analyzer lower-cases the index
            // anyway) so AND/OR/NOT are words, and escaped so every other operator is a character.
            freeTextSearch = literal
                    ? QueryParser.escape(LuceneUtils.normalize(freeTextSearch).toLowerCase(Locale.ROOT))
                    : LuceneUtils.normalizeQuery(freeTextSearch);
            String field = searchField(language);

            // Create a query parser with "or" operator as the default
            QueryParser parser = new ComplexPhraseQueryParser(
                    field,
                    getAnalyzer());
            parser.setDefaultOperator(QueryParser.OR_OPERATOR);
            parser.setAllowLeadingWildcard(!literal); // NB: Expensive!
            query = parser.parse(freeTextSearch);

        } else {
            query = new MatchAllDocsQuery();
        }

        // Perform the search and collect the ids
        IndexSearcher searcher = new IndexSearcher(getIndexReader());
        TopDocs results = searcher.search(query, maxHits);

        List<Long> ids = new ArrayList<>();
        for (ScoreDoc hit : results.scoreDocs) {
            Document d = searcher.doc(hit.doc);
            ids.add(Long.valueOf(d.get(LUCENE_ID_FIELD)));
        }
        return ids;
    }

    /**
     * A Lucene field that stores positional information
     * in order to support phrase searches (quoted search terms).
     *
     * Also, the text value is normalized, i.e. accented chars are
     * replaced with non-accented versions.
     */
    private static class PhraseSearchLuceneField extends Field {

        /* Indexed, tokenized, not stored. */
        public static final FieldType TYPE_NOT_STORED = new FieldType();

        /* Indexed, tokenized, stored. */
        public static final FieldType TYPE_STORED = new FieldType();

        static {
            TYPE_NOT_STORED.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
            TYPE_NOT_STORED.setTokenized(true);
            TYPE_NOT_STORED.setStoreTermVectors(true);
            TYPE_NOT_STORED.setStoreTermVectorPositions(true);
            TYPE_NOT_STORED.freeze();

            TYPE_STORED.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
            TYPE_STORED.setTokenized(true);
            TYPE_STORED.setStored(true);
            TYPE_STORED.setStoreTermVectors(true);
            TYPE_STORED.setStoreTermVectorPositions(true);
            TYPE_STORED.freeze();
        }

        /** Creates a new TextField with String value. */
        public PhraseSearchLuceneField(String field, String value) {
            super(field, LuceneUtils.normalize(value), TYPE_NOT_STORED);
        }
    }
}
