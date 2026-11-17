/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.core.publication.batch;

import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchService;
import org.niord.core.publication.vo.SystemPublicationVo;
import org.niord.core.util.JsonUtils;
import org.niord.test.batch.BatchReaderTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link BatchPublicationImportReader} — the parse/iterate/checkpoint half of the
 * publication import batch job. Driven entirely without JBeret or a DB by sub-classing the reader,
 * wiring Mockito stubs into the protected {@code batchService}/{@code jobContext} fields and pointing
 * them at a temp JSON file. See {@link BatchReaderTestSupport}.
 *
 * <p>Unlike the area reader, this reader performs a straight <b>flat list read</b>: it deserializes a
 * JSON array of {@link SystemPublicationVo} and emits each element verbatim, in order, with no
 * hierarchy flattening and no filtering.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>{@code readItem()} returns the publications in file order and {@code null} once exhausted.</li>
 *   <li>The emitted VOs survive the JSON round-trip with their {@code publicationId} and desc intact.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, and {@code open(checkpoint)}
 *       fast-forwards to that index so a restarted job resumes where it left off.</li>
 *   <li>An empty data file yields no items.</li>
 * </ul>
 */
public class BatchPublicationImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchPublicationImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchPublicationImportReaderTest");
        }

        void wire(BatchService batchService, JobContext jobContext) {
            this.batchService = batchService;
            this.jobContext = jobContext;
        }
    }

    /** Builds a publication VO with the given id and a single English title (the round-trip oracle). */
    private static SystemPublicationVo publication(String publicationId, String title) {
        SystemPublicationVo pub = new SystemPublicationVo();
        pub.setPublicationId(publicationId);
        pub.createDesc("en").setTitle(title);
        return pub;
    }

    /** Serializes the given VOs to a temp JSON file the reader can deserialize, and wires a reader at it. */
    private TestableReader reader(SystemPublicationVo... publications) throws Exception {
        List<SystemPublicationVo> list = new ArrayList<>();
        for (SystemPublicationVo p : publications) {
            list.add(p);
        }
        Path file = Files.createTempFile("batch-publication-reader-", ".json");
        file.toFile().deleteOnExit();
        JsonUtils.writeJson(list, file);

        TestableReader reader = new TestableReader();
        reader.wire(BatchReaderTestSupport.batchService(7L, file),
                BatchReaderTestSupport.jobContext(7L));
        return reader;
    }

    private static String id(Object item) {
        return ((SystemPublicationVo) item).getPublicationId();
    }

    private static String title(Object item) {
        return ((SystemPublicationVo) item).getDescs().get(0).getTitle();
    }

    @Test
    public void emitsPublicationsInFileOrder() throws Exception {
        TestableReader reader = reader(
                publication("pub-a", "Publication A"),
                publication("pub-b", "Publication B"));
        reader.open(null);

        Object first = reader.readItem();
        assertEquals("pub-a", id(first), "first item is the first publication in the file");
        assertEquals("Publication A", title(first), "its desc title survives the JSON round-trip");

        Object second = reader.readItem();
        assertEquals("pub-b", id(second), "second item is the second publication in the file");
        assertEquals("Publication B", title(second), "its desc title survives the JSON round-trip");

        assertNull(reader.readItem(), "no further items after the two publications");
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo(), "two items read");
    }

    @Test
    public void emitsEachPublicationVerbatimWithoutFlatteningOrFiltering() throws Exception {
        // Three distinct publications -> all three are emitted, none dropped, none synthesized.
        TestableReader reader = reader(
                publication("dk-harbour-pilot", "Den danske Havnelods"),
                publication("dk-tide-tables", "Tidevandstabeller"),
                publication("dk-light-list", "Fyrliste"));
        reader.open(null);

        assertEquals("dk-harbour-pilot", id(reader.readItem()));
        assertEquals("dk-tide-tables", id(reader.readItem()));
        assertEquals("dk-light-list", id(reader.readItem()));
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo(),
                "all three publications read, none filtered or flattened");
    }

    @Test
    public void checkpointAdvancesWithEachRead() throws Exception {
        TestableReader reader = reader(
                publication("pub-a", "Publication A"),
                publication("pub-b", "Publication B"));
        reader.open(null);

        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo(), "nothing read yet");
        reader.readItem();
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo(), "one read");
        reader.readItem();
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo(), "two read");
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        TestableReader reader = reader(
                publication("pub-a", "Publication A"),
                publication("pub-b", "Publication B"),
                publication("pub-c", "Publication C"));
        // Restart at index 2 -> the first two publications are skipped.
        reader.open(2);

        Object item = reader.readItem();
        assertEquals("pub-c", id(item), "checkpoint restart skips already-processed items");
        assertNull(reader.readItem(), "only the tail publication remains after the restart");
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo(),
                "count reflects the full file size, not just the resumed tail");
    }

    @Test
    public void emptyDataFileEmitsNothing() throws Exception {
        TestableReader reader = reader();
        reader.open(null);

        assertNull(reader.readItem(), "an empty publication file yields no items");
        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
    }
}
