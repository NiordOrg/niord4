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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.NiordApp;
import org.niord.model.message.Status;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Whether each message search index is complete, and the one line it logs when it becomes so, which a person who
 * prepares a database waits for (or for {@code GET /rest/monitor/message-index}). After a start or a re-index each index is filled again; the first
 * time it holds every message it logs one INFO line that names the index and the messages the fill indexed, also when
 * the fill found nothing to do, and the passes after that log nothing. The message lookup is stubbed and the
 * PostgreSQL index's state is driven directly, so no database is needed.
 */
class MessageIndexCompleteTest {

    @TempDir
    Path tmp;

    private final List<MessageLuceneIndex> indexes = new ArrayList<>();
    private final ListAppender<ILoggingEvent> lucene = new ListAppender<>();
    private final ListAppender<ILoggingEvent> postgres = new ListAppender<>();

    @BeforeEach
    void capture() {
        lucene.start();
        postgres.start();
        ((Logger) LoggerFactory.getLogger(MessageLuceneIndex.class)).addAppender(lucene);
        ((Logger) LoggerFactory.getLogger(MessagePostgresIndex.class)).addAppender(postgres);
    }

    @AfterEach
    void release() throws IOException {
        ((Logger) LoggerFactory.getLogger(MessageLuceneIndex.class)).detachAppender(lucene);
        ((Logger) LoggerFactory.getLogger(MessagePostgresIndex.class)).detachAppender(postgres);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        // An open reader would keep Windows from removing the folder
        for (MessageLuceneIndex index : indexes) {
            if (index.reader != null) {
                index.reader.close();
            }
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Lucene
    // ------------------------------------------------------------------------------------------- //

    /**
     * The fill after a start takes two passes here: the first may index one message, so it is not the last; the second
     * completes the index and logs the line with both messages. The passes after it find nothing new and log no second
     * line.
     */
    @Test
    void theLuceneIndexLogsOneLineWhenItBecomesCompleteAndNoneAfter() {
        MessageLuceneIndex index = restarted(List.of(message(31, "zzfirst", 2000), message(32, "zzsecond", 1000)));

        index.updateLuceneIndex(1);
        assertFalse(index.allMessagesIndexed(), "a pass that found as many messages as it may index is not the last");
        assertEquals(List.of(), completeLines(lucene), "no line before the index is complete");

        index.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT);
        assertTrue(index.allMessagesIndexed());
        assertEquals(List.of("Message search index complete: Lucene, 2 messages indexed in this fill"),
                completeLines(lucene));

        index.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT);
        index.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT);
        assertEquals(1, completeLines(lucene).size(), "a pass after that logs no second line: " + completeLines(lucene));
        assertEquals(Level.INFO, lucene.list.stream().filter(e -> e.getFormattedMessage().startsWith(LINE))
                .findFirst().orElseThrow().getLevel());
    }

    /**
     * The pass that completes the index logs its own line first and then the line that says the index is complete, so
     * a person who reads the log sees the pass before what it achieved
     */
    @Test
    void theLuceneLineComesAfterTheLineOfThePassThatCompletedTheIndex() {
        MessageLuceneIndex index = restarted(List.of(message(34, "zzorder", 1000)));

        index.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT);

        List<String> info = lucene.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Indexed ") || m.startsWith(LINE))
                .toList();
        assertEquals(2, info.size(), "the pass's line and the complete line: " + info);
        assertTrue(info.get(0).startsWith("Indexed 1 messages"), "the pass's line first: " + info);
        assertEquals("Message search index complete: Lucene, 1 messages indexed in this fill", info.get(1));
    }

    /** A start on a database without messages: the first pass finds nothing to do, and the line says so */
    @Test
    void theLuceneIndexLogsTheLineAlsoWhenTheFillFoundNothingToDo() {
        MessageLuceneIndex index = restarted(List.of());

        assertEquals(0, index.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT));

        assertTrue(index.allMessagesIndexed());
        assertEquals(List.of("Message search index complete: Lucene, 0 messages indexed in this fill"),
                completeLines(lucene));
    }

    /** A re-index empties the index and fills it again: one line again, counting only that fill */
    @Test
    void aLuceneReindexLogsTheLineOnceMore() throws Exception {
        MessageLuceneIndex index = restarted(List.of(message(33, "zzreindex", 1000)));
        index.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT);

        assertEquals(1, index.recreateIndex());

        assertTrue(index.allMessagesIndexed());
        assertEquals(List.of("Message search index complete: Lucene, 1 messages indexed in this fill",
                        "Message search index complete: Lucene, 1 messages indexed in this fill"),
                completeLines(lucene));
    }

    // ------------------------------------------------------------------------------------------- //
    // PostgreSQL
    // ------------------------------------------------------------------------------------------- //

    /** In a transaction, the pass that completes the table marks it complete when it commits, not before */
    @Test
    void thePostgresTableIsCompleteOnlyOnceThePassHasCommitted() {
        MessagePostgresIndex index = new MessagePostgresIndex();
        assertFalse(index.allMessagesIndexed(), "false after a start, until the first pass");

        TransactionSynchronizationManager.initSynchronization();
        index.passEnded(false, 5000);
        index.passEnded(true, 120);
        assertFalse(index.allMessagesIndexed(), "the pass's rows cannot be read yet");
        assertEquals(List.of(), completeLines(postgres));

        commit();
        assertTrue(index.allMessagesIndexed());
        assertEquals(List.of("Message search index complete: PostgreSQL, 5120 messages indexed in this fill"),
                completeLines(postgres));
    }

    /** A pass that rolls back marks nothing: the table stays incomplete until a pass commits */
    @Test
    void aPostgresPassThatRollsBackMarksNothing() {
        MessagePostgresIndex index = new MessagePostgresIndex();

        TransactionSynchronizationManager.initSynchronization();
        index.passEnded(true, 3);
        TransactionSynchronizationManager.clearSynchronization();

        assertFalse(index.allMessagesIndexed());
        assertEquals(List.of(), completeLines(postgres));
    }

    /** The passes after the table is complete log nothing; a re-index begins a fill that logs once more */
    @Test
    void thePostgresTableLogsOneLinePerFill() {
        MessagePostgresIndex index = new MessagePostgresIndex();
        index.passEnded(true, 0);
        index.passEnded(true, 2);
        index.passEnded(true, 0);
        assertEquals(List.of("Message search index complete: PostgreSQL, 0 messages indexed in this fill"),
                completeLines(postgres), "the first fill found nothing to do, and the passes after it log nothing");

        index.startFill();
        assertFalse(index.allMessagesIndexed(), "a re-index empties the table");
        index.passEnded(true, 7);
        assertTrue(index.allMessagesIndexed());
        assertEquals(List.of("Message search index complete: PostgreSQL, 0 messages indexed in this fill",
                "Message search index complete: PostgreSQL, 7 messages indexed in this fill"), completeLines(postgres));
    }

    /**
     * A pass that committed after a re-index began does not mark the table the re-index is filling as complete: the
     * pass releases the lock before its commit, so the re-index can start in between
     */
    @Test
    void aPassCommittedAfterAReindexBeganDoesNotCompleteTheReindex() {
        MessagePostgresIndex index = new MessagePostgresIndex();

        TransactionSynchronizationManager.initSynchronization();
        index.passEnded(true, 1);
        index.startFill();
        commit();

        assertFalse(index.allMessagesIndexed(), "the re-index has not committed");
        assertEquals(List.of(), completeLines(postgres));
    }

    // ------------------------------------------------------------------------------------------- //

    private static final String LINE = "Message search index complete:";

    private static List<String> completeLines(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(LINE))
                .toList();
    }

    /** Runs the synchronizations' after-commit callbacks, as a commit does, and ends the synchronization */
    private static void commit() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }

    /**
     * The restarted application: a new instance on an empty folder, started, whose deferred pass reaches a mock. Its
     * message lookup finds the given messages after the marker, oldest first, as the database does.
     */
    @SuppressWarnings("unchecked")
    private MessageLuceneIndex restarted(List<Message> messages) {
        MessageQueryService messageQueryService = mock(MessageQueryService.class);
        when(messageQueryService.findUpdatedMessagesAfter(any(), anyInt(), anyInt())).thenAnswer(invocation -> {
            Date after = invocation.getArgument(0);
            int afterId = invocation.getArgument(1);
            int max = invocation.getArgument(2);
            return messages.stream()
                    .filter(m -> m.getUpdated().after(after)
                            || (m.getUpdated().equals(after) && m.getId() > afterId))
                    .sorted((a, b) -> a.getUpdated().compareTo(b.getUpdated()))
                    .limit(max)
                    .toList();
        });
        NiordApp app = mock(NiordApp.class);
        when(app.getLanguages()).thenReturn(new String[] { "en" });
        when(app.getLanguage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        MessageLuceneIndex index = new MessageLuceneIndex();
        index.indexFolder = tmp.resolve("message-index");
        index.deleteOnStartup = true;
        index.includeDeletedMessages = false;
        index.messageQueryService = messageQueryService;
        index.app = app;
        ObjectProvider<MessageLuceneIndex> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(mock(MessageLuceneIndex.class));
        index.self = self;
        indexes.add(index);
        index.init();
        return index;
    }

    /** A published message updated the given number of milliseconds ago */
    private static Message message(int id, String titleToken, long ageMillis) {
        Message message = new Message();
        message.setId(id);
        message.setStatus(Status.PUBLISHED);
        message.setMessageSeries(new MessageSeries("index-complete"));
        message.setUpdated(new Date(System.currentTimeMillis() - ageMillis));
        message.createDesc("en").setTitle("Wreck " + titleToken);
        return message;
    }
}
