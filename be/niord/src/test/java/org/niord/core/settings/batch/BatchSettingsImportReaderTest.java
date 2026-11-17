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
package org.niord.core.settings.batch;

import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchService;
import org.niord.core.settings.Setting;
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
 * Unit tests for {@link BatchSettingsImportReader} — the parse/iterate/checkpoint half of the
 * settings import batch job. Driven without JBeret or a DB by sub-classing the reader, wiring
 * Mockito stubs into the protected {@code batchService}/{@code jobContext} fields and pointing
 * them at a temp JSON file written from real {@link Setting} objects. See
 * {@link BatchReaderTestSupport}.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>The reader reads a <b>flat</b> list of {@link Setting} entities (no hierarchy flattening
 *       or filtering) and emits them in file order.</li>
 *   <li>{@code readItem()} round-trips the persisted {@code key}/{@code value} and returns
 *       {@code null} once the list is exhausted.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, and {@code open(checkpoint)}
 *       fast-forwards to that index so a restarted job resumes where it left off.</li>
 *   <li>Crossing the every-10-items progress boundary still emits every item (the
 *       {@code updateProgress} call is a harmless no-op via the stubbed batch service).</li>
 * </ul>
 */
public class BatchSettingsImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchSettingsImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchSettingsImportReaderTest");
        }

        void wire(BatchService batchService, JobContext jobContext) {
            this.batchService = batchService;
            this.jobContext = jobContext;
        }
    }

    /** Writes the given settings to a temp JSON file and returns a reader wired to serve it. */
    private TestableReader reader(List<Setting> settings) throws Exception {
        Path file = Files.createTempFile("batch-settings-reader-", ".json");
        file.toFile().deleteOnExit();
        JsonUtils.writeJson(settings, file);

        TestableReader reader = new TestableReader();
        reader.wire(BatchReaderTestSupport.batchService(7L, file),
                BatchReaderTestSupport.jobContext(7L));
        return reader;
    }

    private static Setting setting(String key, Object value) {
        return new Setting(key, value);
    }

    @Test
    public void emitsSettingsInOrderWithRoundTrippedFields() throws Exception {
        List<Setting> data = new ArrayList<>();
        data.add(setting("baseUri", "http://localhost:8080"));
        data.add(setting("siteName", "Niord"));
        data.add(setting("color", "blue"));

        TestableReader reader = reader(data);
        reader.open(null);

        Setting first = (Setting) reader.readItem();
        assertEquals("baseUri", first.getKey());
        assertEquals("http://localhost:8080", first.getValue());

        Setting second = (Setting) reader.readItem();
        assertEquals("siteName", second.getKey());
        assertEquals("Niord", second.getValue());

        Setting third = (Setting) reader.readItem();
        assertEquals("color", third.getKey());
        assertEquals("blue", third.getValue());

        assertNull(reader.readItem(), "no further items after the last setting");
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo(), "three items read");
    }

    @Test
    public void readsSettingsAsAFlatListWithoutFiltering() throws Exception {
        // Settings have no hierarchy: every entry in the file must be emitted, in order, 1:1.
        List<Setting> data = new ArrayList<>();
        data.add(setting("a", "1"));
        data.add(setting("b", "2"));

        TestableReader reader = reader(data);
        reader.open(null);

        assertEquals("a", ((Setting) reader.readItem()).getKey());
        assertEquals("b", ((Setting) reader.readItem()).getKey());
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo());
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        List<Setting> data = new ArrayList<>();
        data.add(setting("a", "1"));
        data.add(setting("b", "2"));
        data.add(setting("c", "3"));

        TestableReader reader = reader(data);
        // Restart at index 2 -> the first two settings are skipped.
        reader.open(2);

        Setting resumed = (Setting) reader.readItem();
        assertEquals("c", resumed.getKey(), "checkpoint restart skips already-processed items");
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void checkpointAdvancesWithEachRead() throws Exception {
        List<Setting> data = new ArrayList<>();
        data.add(setting("a", "1"));
        data.add(setting("b", "2"));

        TestableReader reader = reader(data);
        reader.open(null);

        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo());
    }

    @Test
    public void emitsEveryItemAcrossTheProgressUpdateBoundary() throws Exception {
        // 12 settings exercises the "every 10 items" progress branch (indices 0 and 10) without
        // dropping any item; the stubbed batchService.updateBatchJobProgress is a no-op.
        List<Setting> data = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            data.add(setting("key" + i, "val" + i));
        }

        TestableReader reader = reader(data);
        reader.open(null);

        for (int i = 0; i < 12; i++) {
            Setting s = (Setting) reader.readItem();
            assertEquals("key" + i, s.getKey(), "item at index " + i);
            assertEquals("val" + i, s.getValue(), "value at index " + i);
        }
        assertNull(reader.readItem(), "exhausted after all 12 settings");
        assertEquals(Integer.valueOf(12), (Integer) reader.checkpointInfo());
    }

    @Test
    public void emptyDataFileEmitsNothing() throws Exception {
        TestableReader reader = reader(new ArrayList<>());
        reader.open(null);

        assertNull(reader.readItem(), "an empty settings file yields no items");
        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
    }
}
