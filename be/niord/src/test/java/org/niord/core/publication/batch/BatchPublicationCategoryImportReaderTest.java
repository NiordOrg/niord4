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
import org.niord.core.util.JsonUtils;
import org.niord.model.publication.PublicationCategoryDescVo;
import org.niord.model.publication.PublicationCategoryVo;
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
 * Unit tests for {@link BatchPublicationCategoryImportReader} — the parse/iterate/checkpoint half of the
 * publication-category import batch job. Driven entirely without JBeret or a DB by sub-classing the
 * reader, wiring Mockito stubs into the protected {@code batchService}/{@code jobContext} fields and
 * pointing them at a temp JSON file produced from real {@link PublicationCategoryVo} instances via
 * {@link JsonUtils#writeJson}. See {@link BatchReaderTestSupport}.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>The reader performs a <b>flat</b> read: every element of the JSON array is emitted unchanged,
 *       in array order, with no hierarchy flattening and no filtering.</li>
 *   <li>The emitted {@link PublicationCategoryVo} round-trips its {@code categoryId}, {@code priority}
 *       and localized {@code descs} through JSON.</li>
 *   <li>{@code readItem()} returns {@code null} once exhausted.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, advancing one per read, and
 *       {@code open(checkpoint)} fast-forwards to that index so a restarted job resumes mid-stream.</li>
 * </ul>
 */
public class BatchPublicationCategoryImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchPublicationCategoryImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchPublicationCategoryImportReaderTest");
        }

        void wire(BatchService batchService, JobContext jobContext) {
            this.batchService = batchService;
            this.jobContext = jobContext;
        }
    }

    /** Builds a category VO with an id, a priority and a single English description name. */
    private static PublicationCategoryVo category(String id, Integer priority, String enName) {
        PublicationCategoryVo cat = new PublicationCategoryVo();
        cat.setCategoryId(id);
        cat.setPriority(priority);
        PublicationCategoryDescVo desc = new PublicationCategoryDescVo();
        desc.setLang("en");
        desc.setName(enName);
        List<PublicationCategoryDescVo> descs = new ArrayList<>();
        descs.add(desc);
        cat.setDescs(descs);
        return cat;
    }

    /** Serializes the given VOs to a temp JSON file and wires a reader that serves it. */
    private TestableReader reader(List<PublicationCategoryVo> categories) throws Exception {
        Path file = Files.createTempFile("batch-pubcat-reader-", ".json");
        file.toFile().deleteOnExit();
        JsonUtils.writeJson(categories, file);

        TestableReader reader = new TestableReader();
        reader.wire(BatchReaderTestSupport.batchService(7L, file),
                BatchReaderTestSupport.jobContext(7L));
        return reader;
    }

    private static String name(PublicationCategoryVo cat) {
        return cat.getDescs().get(0).getName();
    }

    @Test
    public void emitsCategoriesInArrayOrderWithFieldsIntact() throws Exception {
        TestableReader reader = reader(Arrays.asList(
                category("dk-dma-publications", 50, "DMA publications"),
                category("dk-dma-charts", 20, "DMA charts")));
        reader.open(null);

        PublicationCategoryVo first = (PublicationCategoryVo) reader.readItem();
        assertEquals("dk-dma-publications", first.getCategoryId(), "first category id round-trips");
        assertEquals(Integer.valueOf(50), first.getPriority(), "first category priority round-trips");
        assertEquals("DMA publications", name(first), "first category English name round-trips");

        PublicationCategoryVo second = (PublicationCategoryVo) reader.readItem();
        assertEquals("dk-dma-charts", second.getCategoryId(), "flat read preserves array order");
        assertEquals(Integer.valueOf(20), second.getPriority());
        assertEquals("DMA charts", name(second));

        assertNull(reader.readItem(), "no further items after the two categories");
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo(), "two items read");
    }

    @Test
    public void readItemReturnsNullOnceExhausted() throws Exception {
        TestableReader reader = reader(Arrays.asList(
                category("only-one", 1, "Only")));
        reader.open(null);

        assertEquals("only-one", ((PublicationCategoryVo) reader.readItem()).getCategoryId());
        assertNull(reader.readItem(), "exhausted reader yields null");
        assertNull(reader.readItem(), "repeated reads past the end stay null");
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo());
    }

    @Test
    public void checkpointAdvancesWithEachRead() throws Exception {
        TestableReader reader = reader(Arrays.asList(
                category("a", 10, "A"),
                category("b", 20, "B")));
        reader.open(null);

        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo(), "no reads yet");
        reader.readItem();
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo(), "one read");
        reader.readItem();
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo(), "two reads");
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        TestableReader reader = reader(Arrays.asList(
                category("a", 10, "A"),
                category("b", 20, "B"),
                category("c", 30, "C")));
        // Restart at index 2 -> the first two categories are skipped.
        reader.open(2);

        PublicationCategoryVo resumed = (PublicationCategoryVo) reader.readItem();
        assertEquals("c", resumed.getCategoryId(), "checkpoint restart skips already-processed items");
        assertNull(reader.readItem(), "nothing remains after the resumed tail");
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void emptyDataFileEmitsNothing() throws Exception {
        TestableReader reader = reader(new ArrayList<>());
        reader.open(null);

        assertNull(reader.readItem(), "an empty category file yields no items");
        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
    }
}
