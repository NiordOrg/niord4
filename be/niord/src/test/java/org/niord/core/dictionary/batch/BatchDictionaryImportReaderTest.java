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
package org.niord.core.dictionary.batch;

import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchService;
import org.niord.core.dictionary.vo.DictionaryEntryVo;
import org.niord.core.dictionary.vo.ExportedDictionaryVo;
import org.niord.core.util.JsonUtils;
import org.niord.test.batch.BatchReaderTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link BatchDictionaryImportReader} — the parse/iterate/checkpoint half of the
 * dictionary import batch job. Driven entirely without JBeret or a DB by sub-classing the reader,
 * wiring Mockito stubs into the protected {@code batchService}/{@code jobContext} fields and
 * pointing them at a temp JSON file. See {@link BatchReaderTestSupport}.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>The reader performs a <b>flat</b> read of {@link ExportedDictionaryVo} items — no hierarchy
 *       flattening and no filtering, so every dictionary in the file is emitted in order. A null entry
 *       is the one exception: it is refused and skipped, since a null read would end the step.</li>
 *   <li>The deserialized VO round-trips its {@code name} and nested {@code entries} (with their
 *       {@code key}s) faithfully.</li>
 *   <li>{@code readItem()} returns items in order and {@code null} once exhausted.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, and {@code open(checkpoint)}
 *       fast-forwards to that index so a restarted job resumes where it left off.</li>
 * </ul>
 */
public class BatchDictionaryImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchDictionaryImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchDictionaryImportReaderTest");
        }

        void wire(BatchService batchService, JobContext jobContext) {
            this.batchService = batchService;
            this.jobContext = jobContext;
        }
    }

    /** Builds a reader serving the given JSON string from a temp file. */
    private TestableReader readerFor(Path file) {
        TestableReader reader = new TestableReader();
        reader.wire(BatchReaderTestSupport.batchService(7L, file),
                BatchReaderTestSupport.jobContext(7L));
        return reader;
    }

    private TestableReader readerFor(String json) throws Exception {
        return readerFor(BatchReaderTestSupport.tempJson(json));
    }

    /** Builds an ExportedDictionaryVo with the given name and entry keys. */
    private static ExportedDictionaryVo dict(String name, String... entryKeys) {
        ExportedDictionaryVo vo = new ExportedDictionaryVo();
        vo.setName(name);
        List<DictionaryEntryVo> entries = new ArrayList<>();
        for (String key : entryKeys) {
            DictionaryEntryVo entry = new DictionaryEntryVo();
            entry.setKey(key);
            entries.add(entry);
        }
        vo.setEntries(entries);
        return vo;
    }

    /** Serializes the dictionaries to a temp JSON file via the production JsonUtils writer. */
    private static Path writeDicts(ExportedDictionaryVo... dicts) throws Exception {
        Path p = Files.createTempFile("batch-reader-dict-", ".json");
        p.toFile().deleteOnExit();
        JsonUtils.writeJson(Arrays.asList(dicts), p);
        return p;
    }

    @Test
    public void emitsSingleDictionaryWithNameAndEntries() throws Exception {
        // A dictionary that serializes via the production writer must round-trip through the reader
        // with its name and entry keys intact, in declaration order.
        Path file = writeDicts(dict("template", "position.east", "position.west"));

        TestableReader reader = readerFor(file);
        reader.open(null);

        ExportedDictionaryVo first = (ExportedDictionaryVo) reader.readItem();
        assertEquals("template", first.getName(), "the dictionary name should round-trip");
        assertEquals(2, first.getEntries().size(), "both entries should be preserved");
        assertEquals("position.east", first.getEntries().get(0).getKey());
        assertEquals("position.west", first.getEntries().get(1).getKey());

        assertNull(reader.readItem(), "no further items after the single dictionary");
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo(), "one item read");
    }

    @Test
    public void emitsAllDictionariesFlatInOrder() throws Exception {
        // The reader does no filtering or hierarchy flattening: every top-level dictionary is emitted
        // exactly once, in file order.
        Path file = writeDicts(dict("first"), dict("second"), dict("third"));

        TestableReader reader = readerFor(file);
        reader.open(null);

        assertEquals("first", ((ExportedDictionaryVo) reader.readItem()).getName());
        assertEquals("second", ((ExportedDictionaryVo) reader.readItem()).getName());
        assertEquals("third", ((ExportedDictionaryVo) reader.readItem()).getName());
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        Path file = writeDicts(dict("first"), dict("second"), dict("third"));

        TestableReader reader = readerFor(file);
        // Restart at index 2 -> the first two dictionaries are skipped.
        reader.open(2);

        assertEquals("third",
                ((ExportedDictionaryVo) reader.readItem()).getName(), "checkpoint restart skips already-processed items");
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void checkpointAdvancesWithEachRead() throws Exception {
        Path file = writeDicts(dict("alpha"), dict("beta"));

        TestableReader reader = readerFor(file);
        reader.open(null);

        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo(), "nothing read yet");
        reader.readItem();
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo());
    }

    @Test
    public void skipsANullEntryAndReadsOn() throws Exception {
        // JSR-352 ends the step on a null read, so a null entry must not reach the job as one: the
        // dictionaries after it are still read, as the preview reports them.
        TestableReader reader = readerFor("[{\"name\":\"first\"},null,{\"name\":\"third\"}]");
        reader.open(null);

        assertEquals("first", ((ExportedDictionaryVo) reader.readItem()).getName());
        assertEquals("third", ((ExportedDictionaryVo) reader.readItem()).getName(), "the null entry is skipped");
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void emptyDataFileEmitsNothing() throws Exception {
        TestableReader reader = readerFor("[]");
        reader.open(null);

        assertNull(reader.readItem(), "an empty dictionary file yields no items");
        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
    }

    @Test
    public void parsesEntryDescsFromRawJson() throws Exception {
        // Mirrors the documented file format: an entry with localized descs. The reader must surface
        // the localized values so a downstream importer can persist them.
        String json = "[{\"name\":\"locations\",\"entries\":["
                + "{\"key\":\"position.east\",\"descs\":["
                + "{\"lang\":\"da\",\"value\":\"øst\"},{\"lang\":\"en\",\"value\":\"East\"}]}]}]";

        TestableReader reader = readerFor(json);
        reader.open(null);

        ExportedDictionaryVo vo = (ExportedDictionaryVo) reader.readItem();
        assertEquals("locations", vo.getName());
        assertEquals(1, vo.getEntries().size());
        DictionaryEntryVo entry = vo.getEntries().get(0);
        assertEquals("position.east", entry.getKey());
        assertEquals(2, entry.getDescs().size(), "two localized descs should be parsed");
        assertEquals("da", entry.getDescs().get(0).getLang());
        assertEquals("øst", entry.getDescs().get(0).getValue());
        assertEquals("en", entry.getDescs().get(1).getLang());
        assertEquals("East", entry.getDescs().get(1).getValue());

        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo());
    }
}
