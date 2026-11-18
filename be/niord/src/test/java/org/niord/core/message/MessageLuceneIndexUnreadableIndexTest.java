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

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.NiordApp;
import org.niord.model.message.Status;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An index folder that this Lucene version cannot open: one written by another Lucene version (a codec it does not
 * know, as after a major upgrade on a kept volume) or a damaged one. Deleting the index, at start-up or by a recreate,
 * removes its files and starts an empty index, which is then rebuilt from the database; the start-up never fails on
 * it. The message lookup and the languages are stubbed, so no database is needed.
 */
class MessageLuceneIndexUnreadableIndexTest {

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

    @Test
    void anIndexOfAnUnknownCodecIsRemovedAndRebuiltByARecreate() throws Exception {
        Path folder = tmp.resolve("message-index");
        indexOfAnUnknownCodec(folder);
        try (FSDirectory directory = FSDirectory.open(folder)) {
            assertThrows(IllegalArgumentException.class, () -> new IndexWriter(directory, new IndexWriterConfig()),
                    "precondition: this Lucene version cannot open the folder's index");
        }

        MessageLuceneIndex index = index(folder, message(11, "zzunknowncodec"));

        assertEquals(1, index.recreateIndex(), "the recreate indexes the message into a new index");
        assertEquals(List.of(11L), index.searchIndex("zzunknowncodec", "en", 10));
    }

    @Test
    void aDamagedIndexIsRemovedAndRebuiltByARecreate() throws Exception {
        Path folder = tmp.resolve("message-index");
        Files.createDirectories(folder);
        byte[] garbage = new byte[256];
        new Random(42).nextBytes(garbage);
        Files.write(folder.resolve("segments_1"), garbage);

        MessageLuceneIndex index = index(folder, message(12, "zzdamagedindex"));

        assertEquals(1, index.recreateIndex(), "the recreate indexes the message into a new index");
        assertEquals(List.of(12L), index.searchIndex("zzdamagedindex", "en", 10));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theStartUpOnAnIndexOfAnUnknownCodecStartsAnEmptyIndex() throws Exception {
        Path folder = tmp.resolve("message-index");
        indexOfAnUnknownCodec(folder);

        MessageLuceneIndex index = index(folder, message(13, "zzstartup"));
        // The deferred start-up pass, five seconds later, reaches a mock and does nothing
        ObjectProvider<MessageLuceneIndex> self = mock(ObjectProvider.class);
        when(self.getObject()).thenReturn(mock(MessageLuceneIndex.class));
        index.self = self;

        index.init();

        try (FSDirectory directory = FSDirectory.open(folder);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(0, reader.numDocs(), "the folder now holds an empty index of this version");
        }
    }

    /**
     * A folder whose lock another writer holds (as a second backend on the same Niord home would) holds an index in
     * use, not an unreadable one: the recreate fails on the lock and removes nothing.
     */
    @Test
    void anIndexAnotherWriterHoldsIsLeftAsItIs() throws Exception {
        Path folder = tmp.resolve("message-index");
        try (FSDirectory directory = FSDirectory.open(folder);
             IndexWriter other = new IndexWriter(directory, new IndexWriterConfig())) {
            Document doc = new Document();
            doc.add(new StringField("id", "1", Field.Store.YES));
            other.addDocument(doc);
            other.commit();

            MessageLuceneIndex index = index(folder, message(14, "zzlocked"));

            assertThrows(LockObtainFailedException.class, index::recreateIndex);
        }

        try (FSDirectory directory = FSDirectory.open(folder);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(1, reader.numDocs(), "the other writer's index is still there");
        }
    }

    /**
     * Writes an index with one document into the folder, then renames the codec its commit names to one that no
     * codec has, as an index written by another Lucene version names a codec this version does not know
     */
    private static void indexOfAnUnknownCodec(Path folder) throws IOException {
        try (FSDirectory directory = FSDirectory.open(folder);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            Document doc = new Document();
            doc.add(new StringField("id", "1", Field.Store.YES));
            writer.addDocument(doc);
            writer.commit();
        }

        Path segments;
        try (Stream<Path> files = Files.list(folder)) {
            segments = files.filter(f -> f.getFileName().toString().startsWith("segments_")).findFirst().orElseThrow();
        }
        String codec = Codec.getDefault().getName();
        String unknown = codec.substring(0, codec.length() - 3) + "000";
        byte[] bytes = Files.readAllBytes(segments);
        byte[] name = codec.getBytes(StandardCharsets.UTF_8);
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i + name.length <= bytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < name.length && match; j++) {
                match = bytes[i + j] == name[j];
            }
            if (match) {
                at.add(i);
            }
        }
        assertEquals(1, at.size(), "the commit names the codec once");
        byte[] replacement = unknown.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(replacement, 0, bytes, at.get(0), replacement.length);

        // The footer's checksum made right again, as in a real index: the last eight bytes are the CRC32 of every byte
        // before them, big-endian. Without it the commit fails its checksum first (CorruptIndexException)
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 8);
        ByteBuffer.wrap(bytes, bytes.length - 8, 8).putLong(crc.getValue());
        Files.write(segments, bytes);
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
        message.setMessageSeries(new MessageSeries("lucene-unreadable"));
        message.setUpdated(new Date());
        message.createDesc("en").setTitle("Wreck " + titleToken);
        return message;
    }
}
