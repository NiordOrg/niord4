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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.NiordApp;
import org.niord.core.settings.SettingChangedEvent;
import org.niord.model.message.Status;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A change of {@code messageIndexIncludeDeletedMessages} builds the message search indexes again
 * ({@link MessageIndexRebuilder}): the Lucene index on every node, deleted and filled again with the warning shown
 * meanwhile; the shared PostgreSQL table once, on the node that saved the setting. Each pass reads the setting once.
 * The message lookup is stubbed, so no database is needed; the integration test of a save is
 * {@code MessageIndexRebuildITTest}.
 */
class MessageIndexRebuildTest {

    @TempDir
    Path tmp;

    private final List<MessageLuceneIndex> indexes = new ArrayList<>();

    @AfterEach
    void release() throws IOException {
        // An open reader would keep Windows from removing the folder
        for (MessageLuceneIndex index : indexes) {
            if (index.reader != null) {
                index.reader.close();
            }
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The Lucene index
    // ------------------------------------------------------------------------------------------- //

    /**
     * After the setting changed, the index is deleted, so a search is marked as answered from part of the messages
     * until the pass that follows has indexed them again by the new value: the deleted message is then found
     */
    @Test
    void aRebuildDeletesTheIndexAndFillsItAgainByTheNewValue() throws Exception {
        List<Boolean> completeWhenThePassStarted = new ArrayList<>();
        MessageLuceneIndex index = started(List.of(message(41, "zzkept", Status.PUBLISHED, 2000),
                message(42, "zzgone", Status.DELETED, 1000)), completeWhenThePassStarted);
        index.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT);
        assertTrue(index.allMessagesIndexed());
        assertEquals(List.of(), index.searchIndex("zzgone", "en", 10), "left out while the setting is off");

        assertEquals(Instant.EPOCH, index.rebuildStartedAt(), "no rebuild since the start");
        index.includeDeletedMessages = true; // as the refresh after the save sets it
        Instant beforeRebuild = Instant.now();
        index.rebuildIndex();

        assertFalse(index.rebuildStartedAt().isBefore(beforeRebuild),
                "the rebuild's start is noted, for the mailing lists' wait for the index");
        assertEquals(List.of(false), completeWhenThePassStarted,
                "the index was deleted before the pass: a search meanwhile is marked incomplete");
        assertTrue(index.allMessagesIndexed(), "the pass indexed every message again");
        assertEquals(List.of(42L), index.searchIndex("zzgone", "en", 10), "indexed by the new value");
        assertEquals(List.of(41L), index.searchIndex("zzkept", "en", 10));
    }

    /**
     * A pass reads the setting once, at its start: a save while it runs does not index some of its messages by the
     * old value and the rest by the new one. Here the save lands while the first of two deleted messages is looked at
     */
    @Test
    void aPassReadsTheSettingOnce() throws Exception {
        MessageLuceneIndex[] holder = new MessageLuceneIndex[1];
        Message first = new Message() {
            @Override
            public Status getStatus() {
                holder[0].includeDeletedMessages = true; // saved while the pass runs
                return super.getStatus();
            }
        };
        fill(first, 51, "zzfirst", Status.DELETED, 2000);
        MessageLuceneIndex index = started(List.of(first, message(52, "zzsecond", Status.DELETED, 1000)), null);
        holder[0] = index;

        index.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT);

        assertEquals(List.of(), index.searchIndex("zzfirst", "en", 10));
        assertEquals(List.of(), index.searchIndex("zzsecond", "en", 10),
                "the second message is judged by the value the pass read, as the first was");
    }

    // ------------------------------------------------------------------------------------------- //
    // Which index is built again where
    // ------------------------------------------------------------------------------------------- //

    /** On the node that saved the setting both are built again: its own Lucene index and the shared table */
    @Test
    void theSavingNodeBuildsItsLuceneIndexAndTheSharedTable() {
        MessageIndexRebuilder rebuilder = rebuilder();

        rebuilder.settingChanged(new SettingChangedEvent(MessageIndexRebuilder.INCLUDE_DELETED, true));

        verify(rebuilder.luceneIndex).rebuildIndex();
        verify(rebuilder.postgresIndex).recreateIndex();
    }

    /**
     * Another node, whose poll found the save, builds its own Lucene index; the table it shares with the saving node
     * is built once, there
     */
    @Test
    void anotherNodeBuildsOnlyItsOwnLuceneIndex() {
        MessageIndexRebuilder rebuilder = rebuilder();

        rebuilder.settingChanged(new SettingChangedEvent(MessageIndexRebuilder.INCLUDE_DELETED, false));

        verify(rebuilder.luceneIndex).rebuildIndex();
        verify(rebuilder.postgresIndex, never()).recreateIndex();
    }

    /** Another setting builds nothing */
    @Test
    void anotherSettingBuildsNothing() {
        MessageIndexRebuilder rebuilder = rebuilder();

        rebuilder.settingChanged(new SettingChangedEvent("messageIndexDeleteOnStartup", true));
        rebuilder.settingChanged(new SettingChangedEvent("mailSender", true));

        verifyNoInteractions(rebuilder.luceneIndex, rebuilder.postgresIndex);
    }

    /** A Lucene index that cannot be built again does not keep the table from being built */
    @Test
    void aFailedLuceneRebuildStillBuildsTheTable() {
        MessageIndexRebuilder rebuilder = rebuilder();
        doThrow(new IllegalStateException("disk full")).when(rebuilder.luceneIndex).rebuildIndex();

        rebuilder.settingChanged(new SettingChangedEvent(MessageIndexRebuilder.INCLUDE_DELETED, true));

        verify(rebuilder.postgresIndex).recreateIndex();
    }

    // ------------------------------------------------------------------------------------------- //

    /** A rebuilder whose work runs on the calling thread, over two mocked indexes */
    private static MessageIndexRebuilder rebuilder() {
        MessageIndexRebuilder rebuilder = new MessageIndexRebuilder();
        rebuilder.shutdown();
        rebuilder.luceneIndex = mock(MessageLuceneIndex.class);
        rebuilder.postgresIndex = mock(MessagePostgresIndex.class);
        rebuilder.executor = Runnable::run;
        return rebuilder;
    }

    /**
     * A started index on an empty folder with the setting off, whose message lookup finds the given messages after the
     * marker, oldest first, as the database does. Its proxy is itself, so a rebuild runs its pass here; when a list is
     * given, each pass that runs through the proxy first records whether the index was complete.
     */
    @SuppressWarnings("unchecked")
    private MessageLuceneIndex started(List<Message> messages, List<Boolean> completeWhenThePassStarted)
            throws IOException {
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
        index.deleteOnStartup = false;
        index.includeDeletedMessages = false;
        index.messageQueryService = messageQueryService;
        index.app = app;
        Files.createDirectories(index.indexFolder);
        MessageLuceneIndex proxy = mock(MessageLuceneIndex.class);
        doAnswer(invocation -> {
            if (completeWhenThePassStarted != null) {
                completeWhenThePassStarted.add(index.allMessagesIndexed());
            }
            index.updateLuceneIndex();
            return null;
        }).when(proxy).updateLuceneIndex();
        ObjectProvider<MessageLuceneIndex> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(proxy);
        index.self = self;
        indexes.add(index);
        return index;
    }

    /** A message of the given status, updated the given number of milliseconds ago */
    private static Message message(int id, String titleToken, Status status, long ageMillis) {
        return fill(new Message(), id, titleToken, status, ageMillis);
    }

    private static Message fill(Message message, int id, String titleToken, Status status, long ageMillis) {
        message.setId(id);
        message.setStatus(status);
        message.setMessageSeries(new MessageSeries("index-rebuild"));
        message.setUpdated(new Date(System.currentTimeMillis() - ageMillis));
        message.createDesc("en").setTitle("Wreck " + titleToken);
        return message;
    }
}
