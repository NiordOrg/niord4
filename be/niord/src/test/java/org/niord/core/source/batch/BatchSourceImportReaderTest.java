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
package org.niord.core.source.batch;

import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchService;
import org.niord.core.source.vo.SourceVo;
import org.niord.test.batch.BatchReaderTestSupport;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BatchSourceImportReader} — the parse/iterate/checkpoint half of the source
 * import batch job. Driven entirely without JBeret or a DB by sub-classing the reader, wiring Mockito
 * stubs into the protected {@code batchService}/{@code jobContext} fields and pointing them at a temp
 * JSON file. See {@link BatchReaderTestSupport}.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>The reader reads a <b>flat</b> list of sources (no hierarchy, no filtering) and emits every
 *       element in file order, including localized descriptions and the {@code active} flag.</li>
 *   <li>{@code readItem()} returns items in order and {@code null} once exhausted.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, and {@code open(checkpoint)}
 *       fast-forwards to that index so a restarted job resumes where it left off.</li>
 * </ul>
 */
public class BatchSourceImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchSourceImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchSourceImportReaderTest");
        }

        void wire(BatchService batchService, JobContext jobContext) {
            this.batchService = batchService;
            this.jobContext = jobContext;
        }
    }

    private TestableReader reader(String json) throws Exception {
        Path file = BatchReaderTestSupport.tempJson(json);
        TestableReader reader = new TestableReader();
        reader.wire(BatchReaderTestSupport.batchService(7L, file),
                BatchReaderTestSupport.jobContext(7L));
        return reader;
    }

    private static String name(SourceVo source) {
        return source.getDescs().get(0).getName();
    }

    private static String abbreviation(SourceVo source) {
        return source.getDescs().get(0).getAbbreviation();
    }

    @Test
    public void emitsSourceWithAllDescFieldsAndActiveFlag() throws Exception {
        // A single source with two localized descs and an explicit active flag. Every field must
        // round-trip from the JSON through the reader into the emitted VO.
        String json = "[{\"active\":true,\"descs\":["
                + "{\"lang\":\"da\",\"name\":\"Arktisk Kommando\",\"abbreviation\":\"AKO\"},"
                + "{\"lang\":\"en\",\"name\":\"Joint Arctic Command\",\"abbreviation\":\"JACMD\"}]}]";

        TestableReader reader = reader(json);
        reader.open(null);

        SourceVo source = (SourceVo) reader.readItem();
        assertTrue(source.isActive(), "the active flag should round-trip from the file");
        assertEquals(2, source.getDescs().size(), "two descriptions should be deserialized");
        assertEquals("da", source.getDescs().get(0).getLang());
        assertEquals("Arktisk Kommando", source.getDescs().get(0).getName());
        assertEquals("AKO", source.getDescs().get(0).getAbbreviation());
        assertEquals("en", source.getDescs().get(1).getLang());
        assertEquals("Joint Arctic Command", source.getDescs().get(1).getName());
        assertEquals("JACMD", source.getDescs().get(1).getAbbreviation());

        assertNull(reader.readItem(), "no further items after the single source");
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo(), "one item read");
    }

    @Test
    public void emitsFlatSourcesInOrder() throws Exception {
        // Three sources, distinct active flags, must come out flat and in file order.
        String json = "["
                + "{\"active\":true,\"descs\":[{\"lang\":\"en\",\"name\":\"Alpha\",\"abbreviation\":\"A\"}]},"
                + "{\"active\":false,\"descs\":[{\"lang\":\"en\",\"name\":\"Bravo\",\"abbreviation\":\"B\"}]},"
                + "{\"active\":true,\"descs\":[{\"lang\":\"en\",\"name\":\"Charlie\",\"abbreviation\":\"C\"}]}]";

        TestableReader reader = reader(json);
        reader.open(null);

        SourceVo first = (SourceVo) reader.readItem();
        assertEquals("Alpha", name(first));
        assertEquals("A", abbreviation(first));
        assertTrue(first.isActive());

        SourceVo second = (SourceVo) reader.readItem();
        assertEquals("Bravo", name(second));
        assertEquals("B", abbreviation(second));
        assertFalse(second.isActive(), "the inactive flag should be preserved");

        SourceVo third = (SourceVo) reader.readItem();
        assertEquals("Charlie", name(third));
        assertEquals("C", abbreviation(third));
        assertTrue(third.isActive());

        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        String json = "["
                + "{\"active\":true,\"descs\":[{\"lang\":\"en\",\"name\":\"Alpha\",\"abbreviation\":\"A\"}]},"
                + "{\"active\":true,\"descs\":[{\"lang\":\"en\",\"name\":\"Bravo\",\"abbreviation\":\"B\"}]},"
                + "{\"active\":true,\"descs\":[{\"lang\":\"en\",\"name\":\"Charlie\",\"abbreviation\":\"C\"}]}]";

        TestableReader reader = reader(json);
        // Restart at index 2 -> the first two sources are skipped.
        reader.open(2);

        assertEquals("Charlie",
                name((SourceVo) reader.readItem()), "checkpoint restart skips already-processed sources");
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void checkpointAdvancesWithEachRead() throws Exception {
        String json = "["
                + "{\"active\":true,\"descs\":[{\"lang\":\"en\",\"name\":\"Alpha\",\"abbreviation\":\"A\"}]},"
                + "{\"active\":true,\"descs\":[{\"lang\":\"en\",\"name\":\"Bravo\",\"abbreviation\":\"B\"}]}]";

        TestableReader reader = reader(json);
        reader.open(null);

        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo());
    }

    @Test
    public void emptyDataFileEmitsNothing() throws Exception {
        TestableReader reader = reader("[]");
        reader.open(null);

        assertNull(reader.readItem(), "an empty source file yields no items");
        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
    }
}
