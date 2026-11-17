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
package org.niord.core.message.batch;

import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.batch.BatchService;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.util.JsonUtils;
import org.niord.model.message.MainType;
import org.niord.model.message.MessageDescVo;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.batch.BatchReaderTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link BatchMessageImportReader} — the parse/iterate/checkpoint half of the message
 * import batch job. Driven entirely without JBeret or a DB by sub-classing the reader, wiring Mockito
 * stubs into the protected {@code batchService}/{@code jobContext} fields plus a hand-built
 * {@code job} ({@link BatchData}) carrying the {@code seriesId} job property, and pointing the stubbed
 * {@link BatchService} at a temp JSON file. See {@link BatchReaderTestSupport}.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>{@code open()} requires a non-blank {@code seriesId} batch property and throws otherwise.</li>
 *   <li>The reader emits the {@link SystemMessageVo} entries from messages.json verbatim and in order,
 *       i.e. it does <b>not</b> mutate {@code status} or the message series — that is the processor's
 *       job, not the reader's.</li>
 *   <li>{@code readItem()} returns items in order and {@code null} once exhausted.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, and {@code open(checkpoint)}
 *       fast-forwards to that index so a restarted job resumes where it left off.</li>
 * </ul>
 */
public class BatchMessageImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchMessageImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchMessageImportReaderTest");
        }

        void wire(BatchService batchService, JobContext jobContext, BatchData job) {
            this.batchService = batchService;
            this.jobContext = jobContext;
            this.job = job;
        }
    }

    /** A job carrying the given seriesId batch property (or none if {@code seriesId} is null). */
    private static BatchData jobWithSeriesId(String seriesId) {
        BatchData job = new BatchData();
        if (seriesId != null) {
            job.getProperties().put("seriesId", seriesId);
        }
        return job;
    }

    /** Builds a SystemMessageVo with the given attributes and an English title. */
    private static SystemMessageVo message(MainType mainType, Type type, Status status, String title) {
        SystemMessageVo msg = new SystemMessageVo();
        msg.setMainType(mainType);
        msg.setType(type);
        msg.setStatus(status);
        MessageDescVo desc = new MessageDescVo();
        desc.setLang("en");
        desc.setTitle(title);
        List<MessageDescVo> descs = new ArrayList<>();
        descs.add(desc);
        msg.setDescs(descs);
        return msg;
    }

    /** Serializes the messages to a temp JSON file and wires a reader at it with the given seriesId. */
    private TestableReader reader(String seriesId, List<SystemMessageVo> messages) throws Exception {
        Path file = Files.createTempFile("batch-message-reader-", ".json");
        file.toFile().deleteOnExit();
        JsonUtils.writeJson(messages, file);

        TestableReader reader = new TestableReader();
        reader.wire(BatchReaderTestSupport.batchService(7L, file),
                BatchReaderTestSupport.jobContext(7L),
                jobWithSeriesId(seriesId));
        return reader;
    }

    private static String title(SystemMessageVo msg) {
        return msg.getDescs().get(0).getTitle();
    }

    @Test
    public void emitsMessagesInOrderVerbatim() throws Exception {
        List<SystemMessageVo> messages = new ArrayList<>();
        messages.add(message(MainType.NW, Type.COASTAL_WARNING, Status.PUBLISHED, "First"));
        messages.add(message(MainType.NM, Type.PERMANENT_NOTICE, Status.VERIFIED, "Second"));

        TestableReader reader = reader("dma-nw", messages);
        reader.open(null);

        SystemMessageVo first = (SystemMessageVo) reader.readItem();
        assertEquals("First", title(first), "first message emitted in file order");
        assertEquals(MainType.NW, first.getMainType());
        assertEquals(Type.COASTAL_WARNING, first.getType());
        assertEquals(Status.PUBLISHED, first.getStatus(),
                "reader must NOT mutate the status (that is the processor's job)");

        SystemMessageVo second = (SystemMessageVo) reader.readItem();
        assertEquals("Second", title(second), "second message emitted in file order");
        assertEquals(MainType.NM, second.getMainType());
        assertEquals(Type.PERMANENT_NOTICE, second.getType());
        assertEquals(Status.VERIFIED, second.getStatus());

        assertNull(reader.readItem(), "no further items after the two messages");
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo(), "two items read");
    }

    @Test
    public void readerDoesNotAssignMessageSeries() throws Exception {
        // The reader only validates the seriesId property exists; it does not stamp the series onto
        // the emitted messages. Pin that: the seriesId from the job must not leak into the VO.
        List<SystemMessageVo> messages = new ArrayList<>();
        messages.add(message(MainType.NW, Type.LOCAL_WARNING, Status.DRAFT, "Only"));

        TestableReader reader = reader("dma-nw", messages);
        reader.open(null);

        SystemMessageVo msg = (SystemMessageVo) reader.readItem();
        assertNull(msg.getMessageSeries(), "reader leaves the message series untouched");
        assertNull(reader.readItem());
    }

    @Test
    public void checkpointAdvancesWithEachRead() throws Exception {
        List<SystemMessageVo> messages = new ArrayList<>();
        messages.add(message(MainType.NW, Type.COASTAL_WARNING, Status.DRAFT, "A"));
        messages.add(message(MainType.NW, Type.COASTAL_WARNING, Status.DRAFT, "B"));

        TestableReader reader = reader("dma-nw", messages);
        reader.open(null);

        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo());
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        List<SystemMessageVo> messages = new ArrayList<>();
        messages.add(message(MainType.NW, Type.COASTAL_WARNING, Status.DRAFT, "A"));
        messages.add(message(MainType.NW, Type.COASTAL_WARNING, Status.DRAFT, "B"));
        messages.add(message(MainType.NW, Type.COASTAL_WARNING, Status.DRAFT, "C"));

        TestableReader reader = reader("dma-nw", messages);
        // Restart at index 2 -> the first two items are skipped.
        reader.open(2);

        assertEquals("C", title((SystemMessageVo) reader.readItem()),
                "checkpoint restart skips already-processed items");
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void emptyDataFileEmitsNothing() throws Exception {
        TestableReader reader = reader("dma-nw", new ArrayList<>());
        reader.open(null);

        assertNull(reader.readItem(), "an empty message file yields no items");
        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
    }

    @Test
    public void openThrowsWhenSeriesIdMissing() throws Exception {
        List<SystemMessageVo> messages = new ArrayList<>();
        messages.add(message(MainType.NW, Type.COASTAL_WARNING, Status.DRAFT, "A"));

        // No seriesId property at all on the job.
        TestableReader reader = reader(null, messages);

        try {
            reader.open(null);
            fail("open() must reject a job without a seriesId property");
        } catch (Exception e) {
            assertEquals("Missing seriesId batch property", e.getMessage());
        }
    }

    @Test
    public void openThrowsWhenSeriesIdBlank() throws Exception {
        List<SystemMessageVo> messages = new ArrayList<>();
        messages.add(message(MainType.NW, Type.COASTAL_WARNING, Status.DRAFT, "A"));

        // Present-but-blank seriesId is treated the same as missing.
        TestableReader reader = reader("   ", messages);

        try {
            reader.open(null);
            fail("open() must reject a blank seriesId property");
        } catch (Exception e) {
            assertEquals("Missing seriesId batch property", e.getMessage());
        }
    }

    @Test
    public void readItemReturnsTheSameInstanceHeldInternally() throws Exception {
        // The reader hands out the deserialized VO instances directly (it does not defensively copy),
        // so two reads of distinct items must return distinct, in-order instances.
        List<SystemMessageVo> messages = new ArrayList<>();
        messages.add(message(MainType.NW, Type.COASTAL_WARNING, Status.DRAFT, "A"));
        messages.add(message(MainType.NW, Type.COASTAL_WARNING, Status.DRAFT, "B"));

        TestableReader reader = reader("dma-nw", messages);
        reader.open(null);

        SystemMessageVo a = (SystemMessageVo) reader.readItem();
        SystemMessageVo b = (SystemMessageVo) reader.readItem();
        assertTrue(a != b, "distinct items must be distinct instances");
        assertNull(reader.readItem(),
                "re-reading is not supported; once consumed the cursor only moves forward");
        assertEquals("A", title(a));
        assertEquals("B", title(b));
    }
}
