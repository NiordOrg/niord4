/*
 * Copyright 2026 Danish Maritime Authority.
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

import jakarta.persistence.PersistenceException;
import org.apache.lucene.store.AlreadyClosedException;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.niord.core.NiordApp;
import org.niord.model.message.Status;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The window after a start in which the free-text index is filled again (be/docs/potential-errors.md, LUCENE-7). With
 * {@code messageIndexDeleteOnStartup} on, the default, the start commits an empty index that can be read, and the
 * passes fill it again 5,000 messages a minute. A search in that window answers from what is indexed so far, without
 * an error, while {@link MessageLuceneIndex#allMessagesIndexed()} says the index is not complete; the search service
 * marks such an answer ({@code MessageSearchIndexIncompleteITTest}). These tests hold the index to saying so until a
 * pass has made every message searchable. The message lookup and the languages are stubbed, so no database is needed.
 */
class MessageLuceneIndexStartWindowTest {

    @TempDir
    Path tmp;

    private final List<MessageLuceneIndex> indexes = new ArrayList<>();

    @AfterEach
    void closeReaders() throws IOException {
        // An open reader would keep Windows from removing the folder
        for (MessageLuceneIndex index : indexes) {
            if (index.reader != null) {
                index.reader.close();
            }
        }
    }

    /**
     * A message indexed before a restart is not found by a search right after it, and the search does not fail: it
     * answers an empty list, and the index says it is not complete, which is what the search service marks its
     * answer by. The owner's decision: the search keeps answering in this window, and the editors are told.
     */
    @Test
    void aSearchRightAfterAStartAnswersFromTheEmptiedIndexWhileTheIndexSaysItIsNotComplete() throws Exception {
        Path folder = tmp.resolve("message-index");
        MessageLuceneIndex before = index(folder, message(21, "zzbeforerestart"));
        assertEquals(1, before.recreateIndex(), "precondition: the message is indexed before the restart");
        assertTrue(before.allMessagesIndexed(), "precondition: a re-index ends complete");
        assertEquals(List.of(21L), before.searchIndex("zzbeforerestart", "en", 10));
        before.reader.close();
        before.reader = null;

        MessageLuceneIndex after = restarted(folder, message(21, "zzbeforerestart"));

        assertFalse(after.allMessagesIndexed(), "the index is being filled again");
        assertEquals(List.of(), after.searchIndex("zzbeforerestart", "en", 10),
                "the search answers from the emptied index, without an error");
    }

    /**
     * The first pass after the start indexes the message again; with fewer messages than a pass may index, the
     * index is then complete, and the message is found again.
     */
    @Test
    void theIndexIsCompleteOnceAPassHasIndexedEveryMessage() throws Exception {
        Path folder = tmp.resolve("message-index");
        MessageLuceneIndex after = restarted(folder, message(22, "zzrefilled"));
        assertFalse(after.allMessagesIndexed(), "precondition: being filled");

        assertEquals(1, after.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT));

        assertTrue(after.allMessagesIndexed(), "a pass that found fewer messages than it may index completes it");
        assertEquals(List.of(22L), after.searchIndex("zzrefilled", "en", 10));
    }

    /**
     * A pass that fails before its commit (here its writer is closed under it, as after a disk failure, while it
     * indexes a message) leaves the index marked as not complete. The mark used to be set when the pass had found its
     * messages, before it indexed them, so a search in between, or after a failed pass, was taken for one over a
     * complete index.
     */
    @Test
    void aPassThatFailsBeforeItsCommitLeavesTheIndexMarkedIncomplete() throws Exception {
        Path folder = tmp.resolve("message-index");
        Message closedUnderIt = new Message() {
            @Override
            public MessageSeries getMessageSeries() {
                throw new AlreadyClosedException("the writer is closed");
            }
        };
        closedUnderIt.setId(23);
        closedUnderIt.setStatus(Status.PUBLISHED);
        closedUnderIt.setUpdated(new Date());
        closedUnderIt.createDesc("en").setTitle("Wreck zzclosed");
        MessageLuceneIndex after = restarted(folder, closedUnderIt);

        assertEquals(0, after.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT), "the pass fails");

        assertFalse(after.allMessagesIndexed(), "and the index is still not complete");
    }

    /**
     * A message that cannot be indexed (here its series cannot be read) does not fail the pass: it is logged and
     * skipped (LUCENE-5), the pass commits, and the refill after the start ends as it would without it. The skipped
     * message is not searchable until it is saved again; the ERROR in the log says so, not the editors' list.
     */
    @Test
    void aSkippedMessageDoesNotHoldTheIndexIncomplete() throws Exception {
        Path folder = tmp.resolve("message-index");
        Message unreadable = message(24, "zzunreadable");
        unreadable.setMessageSeries(null);
        MessageLuceneIndex after = restarted(folder, unreadable);

        assertEquals(0, after.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT), "nothing is indexed");

        assertTrue(after.allMessagesIndexed(), "the refill ends, the skipped message aside");
        assertEquals(List.of(), after.searchIndex("zzunreadable", "en", 10));
    }

    /** Failures of the database, as the lazy reads of a message in the pass's transaction meet them */
    static Stream<Arguments> databaseFaults() {
        return Stream.of(
                Arguments.of("a persistence failure", (Supplier<RuntimeException>) () ->
                        new PersistenceException("the connection was lost")),
                Arguments.of("a Hibernate failure of the connection", (Supplier<RuntimeException>) () ->
                        new JDBCConnectionException("could not load", new SQLException("connection refused"))),
                Arguments.of("a Spring data-access failure", (Supplier<RuntimeException>) () ->
                        new DataAccessResourceFailureException("no connection from the pool")),
                Arguments.of("an SQL failure further down the cause chain", (Supplier<RuntimeException>) () ->
                        new IllegalStateException("could not read",
                                new SQLException("current transaction is aborted"))));
    }

    /**
     * A failure of the database while the first message is read is not that message's fault, and is not skipped as
     * one that cannot be indexed (LUCENE-5): in the pass's one transaction, every later message would fail the same
     * way, be skipped for good, and the refill be taken for complete. The pass fails instead, nothing is committed,
     * the marker stays, and the index is still not complete; once the database answers again, the next pass indexes
     * every message of the batch.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("databaseFaults")
    void aDatabaseFailureFailsThePassAndTheNextPassIndexesTheBatch(String what, Supplier<RuntimeException> fault)
            throws Exception {
        Path folder = tmp.resolve("message-index");
        AtomicBoolean databaseDown = new AtomicBoolean(true);
        Message first = new Message() {
            @Override
            public MessageSeries getMessageSeries() {
                if (databaseDown.get()) {
                    throw fault.get();
                }
                return super.getMessageSeries();
            }
        };
        first.setId(25);
        first.setStatus(Status.PUBLISHED);
        first.setMessageSeries(new MessageSeries("lucene-start-window"));
        first.setUpdated(new Date(System.currentTimeMillis() - 1000));
        first.createDesc("en").setTitle("Wreck zzdbfirst");
        Message second = message(26, "zzdbsecond");
        MessageLuceneIndex after = restarted(folder, List.of(first, second));

        assertEquals(0, after.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT), "the pass fails");

        assertFalse(after.allMessagesIndexed(), "the index is still not complete");
        assertEquals(MessageLuceneIndex.Marker.NONE, after.getMarker(), "the marker does not move");
        assertEquals(List.of(), after.searchIndex("zzdbsecond", "en", 10), "nothing of the batch is committed");

        databaseDown.set(false);
        assertEquals(2, after.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT),
                "the next pass indexes the whole batch");
        assertTrue(after.allMessagesIndexed());
        assertEquals(List.of(25L), after.searchIndex("zzdbfirst", "en", 10));
        assertEquals(List.of(26L), after.searchIndex("zzdbsecond", "en", 10));
        assertEquals(26, after.getMarker().messageId(), "the marker moves to the last message");
    }

    /** The restarted application: a new instance on the folder, started, whose deferred pass reaches a mock */
    private MessageLuceneIndex restarted(Path folder, Message message) {
        return restarted(folder, List.of(message));
    }

    /** The restarted application, whose message lookup finds the given messages */
    @SuppressWarnings("unchecked")
    private MessageLuceneIndex restarted(Path folder, List<Message> messages) {
        MessageLuceneIndex after = index(folder, messages);
        ObjectProvider<MessageLuceneIndex> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(mock(MessageLuceneIndex.class));
        after.self = self;
        after.init();
        return after;
    }

    /** An instance on the folder whose message lookup finds the given message, in English only */
    private MessageLuceneIndex index(Path folder, Message message) {
        return index(folder, List.of(message));
    }

    /** An instance on the folder whose message lookup finds the given messages, in English only */
    private MessageLuceneIndex index(Path folder, List<Message> messages) {
        MessageQueryService messageQueryService = mock(MessageQueryService.class);
        when(messageQueryService.findUpdatedMessagesAfter(any(), anyInt(), anyInt())).thenReturn(messages);
        NiordApp app = mock(NiordApp.class);
        when(app.getLanguages()).thenReturn(new String[] { "en" });
        when(app.getLanguage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        MessageLuceneIndex index = new MessageLuceneIndex();
        index.indexFolder = folder;
        index.deleteOnStartup = true;
        index.includeDeletedMessages = false;
        index.messageQueryService = messageQueryService;
        index.app = app;
        indexes.add(index);
        return index;
    }

    private static Message message(int id, String titleToken) {
        Message message = new Message();
        message.setId(id);
        message.setStatus(Status.PUBLISHED);
        message.setMessageSeries(new MessageSeries("lucene-start-window"));
        message.setUpdated(new Date());
        message.createDesc("en").setTitle("Wreck " + titleToken);
        return message;
    }
}
