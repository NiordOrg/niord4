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
package org.niord.core.area.batch;

import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;
import org.niord.core.area.vo.SystemAreaVo;
import org.niord.core.batch.BatchService;
import org.niord.model.message.AreaVo;
import org.niord.test.batch.BatchReaderTestSupport;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link BatchAreaImportReader} — the parse/iterate/checkpoint half of the area import
 * batch job. Driven entirely without JBeret or a DB by sub-classing the reader, wiring Mockito stubs
 * into the protected {@code batchService}/{@code jobContext} fields and pointing them at a temp JSON
 * file. See {@link BatchReaderTestSupport}.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>The reader flattens the area sibling/children hierarchy into a flat list, and <b>only leaf
 *       areas are emitted</b> (non-leaf parents are dropped because a leaf re-creates its ancestry).</li>
 *   <li>{@code readItem()} returns items in order and {@code null} once exhausted.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, and {@code open(checkpoint)}
 *       fast-forwards to that index so a restarted job resumes where it left off.</li>
 * </ul>
 */
public class BatchAreaImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchAreaImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchAreaImportReaderTest");
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

    private static String name(AreaVo area) {
        return area.getDescs().get(0).getName();
    }

    @Test
    public void emitsOnlyLeafAreasFromHierarchy() throws Exception {
        // One root ("Parent") with a single child ("Child"). The parent is a non-leaf and must be
        // dropped; only the leaf "Child" is emitted, with its parent reference wired up.
        String json = "[{\"descs\":[{\"lang\":\"en\",\"name\":\"Parent\"}],"
                + "\"children\":[{\"descs\":[{\"lang\":\"en\",\"name\":\"Child\"}]}]}]";

        TestableReader reader = reader(json);
        reader.open(null);

        SystemAreaVo first = (SystemAreaVo) reader.readItem();
        assertEquals("Child", name(first), "only the leaf area should be emitted");
        assertEquals("Parent", name(first.getParent()),
                "the leaf's parent reference should be the flattened root");

        assertNull(reader.readItem(), "no further items after the single leaf");
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo(), "one item read");
    }

    @Test
    public void emitsFlatAreasInOrder() throws Exception {
        String json = "[{\"descs\":[{\"lang\":\"en\",\"name\":\"A\"}]},"
                + "{\"descs\":[{\"lang\":\"en\",\"name\":\"B\"}]},"
                + "{\"descs\":[{\"lang\":\"en\",\"name\":\"C\"}]}]";

        TestableReader reader = reader(json);
        reader.open(null);

        assertEquals("A", name((SystemAreaVo) reader.readItem()));
        assertEquals("B", name((SystemAreaVo) reader.readItem()));
        assertEquals("C", name((SystemAreaVo) reader.readItem()));
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        String json = "[{\"descs\":[{\"lang\":\"en\",\"name\":\"A\"}]},"
                + "{\"descs\":[{\"lang\":\"en\",\"name\":\"B\"}]},"
                + "{\"descs\":[{\"lang\":\"en\",\"name\":\"C\"}]}]";

        TestableReader reader = reader(json);
        // Restart at index 2 -> the first two items are skipped.
        reader.open(2);

        assertEquals("C",
                name((SystemAreaVo) reader.readItem()), "checkpoint restart skips already-processed items");
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void checkpointAdvancesWithEachRead() throws Exception {
        String json = "[{\"descs\":[{\"lang\":\"en\",\"name\":\"A\"}]},"
                + "{\"descs\":[{\"lang\":\"en\",\"name\":\"B\"}]}]";

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

        assertNull(reader.readItem(), "an empty area file yields no items");
        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
    }
}
