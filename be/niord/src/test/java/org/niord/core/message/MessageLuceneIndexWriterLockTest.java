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

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.NiordApp;
import org.niord.model.message.Status;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The writer lock of {@link MessageLuceneIndex}: one per index folder, shared by every instance in the JVM.
 * <p>
 * Lucene allows one {@code IndexWriter} per folder and, inside one JVM, refuses a second one at once
 * ({@code LockObtainFailedException: Lock held by this virtual machine}) instead of waiting for it. The
 * integration-test JVM keeps several cached application contexts alive, each with its own instance, start-up
 * pass and schedule on the same {@code ${niord.home}/message-index}. While the lock was per instance, the
 * start-up pass of one context and a {@code recreateIndex()} in another met in that folder and failed
 * {@code MessageLuceneIndexITTest} now and then. Here two instances share a temporary folder, with the message
 * lookup and the languages stubbed, so the meeting is staged without a database or a clock: the first instance
 * is held inside its writer while the second recreates the index.
 */
class MessageLuceneIndexWriterLockTest {

    /** A safety net for a broken run, never a condition of the outcome */
    private static final long TIMEOUT_SECONDS = 30;

    @TempDir
    Path tmp;

    private final List<MessageLuceneIndex> indexes = new ArrayList<>();

    @AfterEach
    void closeReaders() throws IOException {
        // What @PreDestroy does in a context; an open reader would also keep Windows from removing the folder
        for (MessageLuceneIndex index : indexes) {
            if (index.reader != null) {
                index.reader.close();
            }
        }
    }

    @Test
    void everySpellingOfAFolderSharesOneLock() {
        Path folder = tmp.resolve("message-index");
        ReentrantLock lock = MessageLuceneIndex.writerLock(folder);

        assertSame(lock, MessageLuceneIndex.writerLock(tmp.resolve("other/../message-index")));
        assertSame(lock, MessageLuceneIndex.writerLock(tmp.resolve("./message-index")));
        assertNotSame(lock, MessageLuceneIndex.writerLock(tmp.resolve("another-index")),
                "another folder has a lock of its own");

        // A relative folder is the folder it names in the working directory
        Path relative = Path.of("message-index-" + System.nanoTime());
        assertSame(MessageLuceneIndex.writerLock(relative.toAbsolutePath()), MessageLuceneIndex.writerLock(relative));
    }

    @Test
    void aRecreateWaitsForAnotherInstancesWriterOnTheSameFolder() throws Exception {
        Path folder = tmp.resolve("message-index");
        emptyIndex(folder);
        Message message = message(7, "zzsharedfolder");

        // The first instance - another context's start-up pass or schedule - stops inside its writer
        CountDownLatch firstHoldsWriter = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        MessageLuceneIndex first = index(folder, message, () -> {
            firstHoldsWriter.countDown();
            await(releaseFirst);
        });
        MessageLuceneIndex second = index(folder, message, () -> { });

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> update = threads.submit(() -> first.updateLuceneIndex());
            assertTrue(firstHoldsWriter.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the first instance opened its writer");

            // The second instance - a test in this context - recreates the index meanwhile
            Future<Integer> recreate = threads.submit(second::recreateIndex);
            ReentrantLock lock = MessageLuceneIndex.writerLock(folder);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (!lock.hasQueuedThreads() && !recreate.isDone() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            if (recreate.isDone()) {
                recreate.get();     // surfaces how it ended, e.g. LockObtainFailedException
            }
            assertTrue(lock.hasQueuedThreads(), "the recreate waits for the folder's writer lock");

            releaseFirst.countDown();
            update.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            int indexed = recreate.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(1, indexed, "once the other writer is closed the recreate indexes the message");
        } finally {
            releaseFirst.countDown();
            threads.shutdownNow();
        }

        assertEquals(List.of(7L), second.searchIndex("zzsharedfolder", "en", 10));
    }

    /**
     * An instance on the folder whose message lookup finds the given message and whose language lookup,
     * made while the writer is open, runs the given hook first
     */
    private MessageLuceneIndex index(Path folder, Message message, Runnable whileWriting) {
        MessageQueryService messageQueryService = mock(MessageQueryService.class);
        when(messageQueryService.findUpdatedMessagesAfter(any(), anyInt(), anyInt())).thenReturn(List.of(message));
        NiordApp app = mock(NiordApp.class);
        when(app.getLanguages()).thenAnswer(invocation -> {
            whileWriting.run();
            return new String[] { "en" };
        });
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
        message.setMessageSeries(new MessageSeries("lucene-lock"));
        message.setUpdated(new Date());
        message.createDesc("en").setTitle("Wreck " + titleToken);
        return message;
    }

    /** Creates an empty index in the folder, as a previous start leaves it */
    private static void emptyIndex(Path folder) throws IOException {
        try (FSDirectory directory = FSDirectory.open(folder);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.commit();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the first instance");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
