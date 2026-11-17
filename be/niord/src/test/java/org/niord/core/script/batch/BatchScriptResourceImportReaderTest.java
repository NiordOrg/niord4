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
package org.niord.core.script.batch;

import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchService;
import org.niord.core.script.ScriptResource;
import org.niord.core.script.vo.ScriptResourceVo;
import org.niord.test.batch.BatchReaderTestSupport;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link BatchScriptResourceImportReader}, driven without JBeret or a database as
 * {@code BatchDictionaryImportReaderTest} drives the dictionary reader (see {@link BatchReaderTestSupport}).
 */
public class BatchScriptResourceImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchScriptResourceImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchScriptResourceImportReaderTest");
        }

        void wire(BatchService batchService, JobContext jobContext) {
            this.batchService = batchService;
            this.jobContext = jobContext;
        }
    }

    private TestableReader readerFor(String json) throws Exception {
        TestableReader reader = new TestableReader();
        reader.wire(BatchReaderTestSupport.batchService(7L, BatchReaderTestSupport.tempJson(json)),
                BatchReaderTestSupport.jobContext(7L));
        return reader;
    }

    @Test
    public void emitsTheResourcesInFileOrder() throws Exception {
        TestableReader reader = readerFor("[{\"type\":\"FM\",\"path\":\"templates/a.ftl\",\"content\":\"a\"},"
                + "{\"type\":\"JS\",\"path\":\"templates/b.js\",\"content\":\"1;\"}]");
        reader.open(null);

        ScriptResourceVo first = (ScriptResourceVo) reader.readItem();
        assertEquals("templates/a.ftl", first.getPath());
        assertEquals(ScriptResource.Type.FM, first.getType());
        assertEquals("a", first.getContent());
        assertEquals("templates/b.js", ((ScriptResourceVo) reader.readItem()).getPath());
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo());
    }

    @Test
    public void skipsANullEntryAndReadsOn() throws Exception {
        // JSR-352 ends the step on a null read, so a null entry must not reach the job as one: the
        // resources after it are still read, as the preview reports them.
        TestableReader reader = readerFor("[{\"type\":\"FM\",\"path\":\"templates/a.ftl\",\"content\":\"a\"},"
                + "null,{\"type\":\"FM\",\"path\":\"templates/c.ftl\",\"content\":\"c\"}]");
        reader.open(null);

        assertEquals("templates/a.ftl", ((ScriptResourceVo) reader.readItem()).getPath());
        assertEquals("templates/c.ftl", ((ScriptResourceVo) reader.readItem()).getPath(),
                "the null entry is skipped");
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        TestableReader reader = readerFor("[{\"type\":\"FM\",\"path\":\"templates/a.ftl\",\"content\":\"a\"},"
                + "{\"type\":\"FM\",\"path\":\"templates/b.ftl\",\"content\":\"b\"}]");
        reader.open(1);

        assertEquals("templates/b.ftl", ((ScriptResourceVo) reader.readItem()).getPath());
        assertNull(reader.readItem());
    }
}
