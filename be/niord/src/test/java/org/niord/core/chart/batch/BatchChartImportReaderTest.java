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
package org.niord.core.chart.batch;

import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchService;
import org.niord.core.chart.vo.SystemChartVo;
import org.niord.test.batch.BatchReaderTestSupport;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BatchChartImportReader} — the parse/iterate/checkpoint half of the chart import
 * batch job. Driven entirely without JBeret or a DB by sub-classing the reader, wiring Mockito stubs
 * into the protected {@code batchService}/{@code jobContext} fields and pointing them at a temp JSON
 * file. See {@link BatchReaderTestSupport}.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>The reader is a plain flat read: every element of the {@code charts.json} array is emitted as a
 *       {@link SystemChartVo}, in file order, with no hierarchy flattening and no filtering.</li>
 *   <li>The JSON fields (chartNumber/internationalNumber/scale/name/active/horizontalDatum) are
 *       deserialized onto the emitted VO.</li>
 *   <li>{@code readItem()} returns items in order and {@code null} once exhausted.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, and {@code open(checkpoint)}
 *       fast-forwards to that index so a restarted job resumes where it left off.</li>
 * </ul>
 */
public class BatchChartImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchChartImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchChartImportReaderTest");
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
    public void deserializesChartFieldsOntoEmittedVo() throws Exception {
        // A single, fully-populated chart. Each JSON property must round-trip onto the emitted VO.
        String json = "[{"
                + "\"chartNumber\":\"198\","
                + "\"internationalNumber\":1234,"
                + "\"name\":\"Faekse Bugt\","
                + "\"scale\":75000,"
                + "\"active\":false,"
                + "\"horizontalDatum\":\"WGS84\""
                + "}]";

        TestableReader reader = reader(json);
        reader.open(null);

        SystemChartVo chart = (SystemChartVo) reader.readItem();
        assertEquals("198", chart.getChartNumber());
        assertEquals(Integer.valueOf(1234), chart.getInternationalNumber());
        assertEquals("Faekse Bugt", chart.getName());
        assertEquals(Integer.valueOf(75000), chart.getScale());
        assertEquals("WGS84", chart.getHorizontalDatum());
        assertFalse(chart.isActive(), "active should be deserialized from the JSON");

        assertNull(reader.readItem(), "no further items after the single chart");
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo(), "one item read");
    }

    @Test
    public void emitsFlatChartsInFileOrder() throws Exception {
        String json = "[{\"chartNumber\":\"101\",\"scale\":100000},"
                + "{\"chartNumber\":\"102\",\"scale\":200000},"
                + "{\"chartNumber\":\"103\",\"scale\":300000}]";

        TestableReader reader = reader(json);
        reader.open(null);

        SystemChartVo a = (SystemChartVo) reader.readItem();
        assertEquals("101", a.getChartNumber());
        assertEquals(Integer.valueOf(100000), a.getScale());

        SystemChartVo b = (SystemChartVo) reader.readItem();
        assertEquals("102", b.getChartNumber());
        assertEquals(Integer.valueOf(200000), b.getScale());

        SystemChartVo c = (SystemChartVo) reader.readItem();
        assertEquals("103", c.getChartNumber());
        assertEquals(Integer.valueOf(300000), c.getScale());

        assertNull(reader.readItem(), "exhausted reader yields null");
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        String json = "[{\"chartNumber\":\"101\"},"
                + "{\"chartNumber\":\"102\"},"
                + "{\"chartNumber\":\"103\"}]";

        TestableReader reader = reader(json);
        // Restart at index 2 -> the first two items are skipped.
        reader.open(2);

        SystemChartVo c = (SystemChartVo) reader.readItem();
        assertEquals("103", c.getChartNumber(), "checkpoint restart skips already-processed items");
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void checkpointAdvancesWithEachRead() throws Exception {
        String json = "[{\"chartNumber\":\"101\"},"
                + "{\"chartNumber\":\"102\"}]";

        TestableReader reader = reader(json);
        reader.open(null);

        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo());
    }

    @Test
    public void defaultsActiveToTrueWhenAbsent() throws Exception {
        // The VO field defaults to active=true; absence of the JSON property must not flip it.
        String json = "[{\"chartNumber\":\"500\"}]";

        TestableReader reader = reader(json);
        reader.open(null);

        SystemChartVo chart = (SystemChartVo) reader.readItem();
        assertTrue(chart.isActive(), "active defaults to true when omitted from JSON");
        assertNull(chart.getInternationalNumber(), "internationalNumber is null when omitted");
        assertNull(chart.getScale(), "scale is null when omitted");
    }

    @Test
    public void emptyDataFileEmitsNothing() throws Exception {
        TestableReader reader = reader("[]");
        reader.open(null);

        assertNull(reader.readItem(), "an empty chart file yields no items");
        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
    }
}
