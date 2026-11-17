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
package org.niord.core.domain.batch;

import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchService;
import org.niord.core.domain.vo.DomainVo;
import org.niord.test.batch.BatchReaderTestSupport;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link BatchDomainImportReader} — the parse/iterate/checkpoint half of the domain
 * import batch job. Driven entirely without JBeret or a DB by sub-classing the reader, wiring Mockito
 * stubs into the protected {@code batchService}/{@code jobContext} fields and pointing them at a temp
 * JSON file. See {@link BatchReaderTestSupport}.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>The reader deserialises a flat {@code domains.json} into {@link DomainVo}s and emits them
 *       one-by-one in file order (no hierarchy flattening, no filtering — every entry is emitted).</li>
 *   <li>{@code readItem()} returns items in order and {@code null} once exhausted.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, and {@code open(checkpoint)}
 *       fast-forwards to that index so a restarted job resumes where it left off.</li>
 * </ul>
 */
public class BatchDomainImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchDomainImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchDomainImportReaderTest");
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

    @Test
    public void emitsDomainsInOrderWithFields() throws Exception {
        // Two domains; each entry carries domainId + name. The reader emits both, in file order,
        // preserving both fields verbatim.
        String json = "[{\"domainId\":\"niord-client-nw\",\"name\":\"NW domain\"},"
                + "{\"domainId\":\"niord-client-nm\",\"name\":\"NM domain\"}]";

        TestableReader reader = reader(json);
        reader.open(null);

        DomainVo first = (DomainVo) reader.readItem();
        assertEquals("niord-client-nw", first.getDomainId(), "first domain id preserved");
        assertEquals("NW domain", first.getName(), "first domain name preserved");

        DomainVo second = (DomainVo) reader.readItem();
        assertEquals("niord-client-nm", second.getDomainId(), "second domain id preserved");
        assertEquals("NM domain", second.getName(), "second domain name preserved");

        assertNull(reader.readItem(), "no further items after the two domains");
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo(), "two items read");
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        String json = "[{\"domainId\":\"a\",\"name\":\"A\"},"
                + "{\"domainId\":\"b\",\"name\":\"B\"},"
                + "{\"domainId\":\"c\",\"name\":\"C\"}]";

        TestableReader reader = reader(json);
        // Restart at index 2 -> the first two domains are skipped.
        reader.open(2);

        DomainVo resumed = (DomainVo) reader.readItem();
        assertEquals("c", resumed.getDomainId(), "checkpoint restart skips already-processed domains");
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void checkpointAdvancesWithEachRead() throws Exception {
        String json = "[{\"domainId\":\"a\",\"name\":\"A\"},"
                + "{\"domainId\":\"b\",\"name\":\"B\"}]";

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

        assertNull(reader.readItem(), "an empty domain file yields no items");
        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
    }
}
