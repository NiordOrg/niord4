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

import org.apache.lucene.store.AlreadyClosedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.NiordApp;
import org.niord.model.message.Status;
import org.springframework.beans.factory.ObjectProvider;

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

    /** The restarted application: a new instance on the folder, started, whose deferred pass reaches a mock */
    @SuppressWarnings("unchecked")
    private MessageLuceneIndex restarted(Path folder, Message message) {
        MessageLuceneIndex after = index(folder, message);
        ObjectProvider<MessageLuceneIndex> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(mock(MessageLuceneIndex.class));
        after.self = self;
        after.init();
        return after;
    }

    /** An instance on the folder whose message lookup finds the given message, in English only */
    private MessageLuceneIndex index(Path folder, Message message) {
        MessageQueryService messageQueryService = mock(MessageQueryService.class);
        when(messageQueryService.findUpdatedMessagesAfter(any(), anyInt(), anyInt())).thenReturn(List.of(message));
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
