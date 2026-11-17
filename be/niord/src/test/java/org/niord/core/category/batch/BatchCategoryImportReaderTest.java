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
package org.niord.core.category.batch;

import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.batch.BatchService;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.category.vo.SystemCategoryVo;
import org.niord.model.message.CategoryVo;
import org.niord.test.batch.BatchReaderTestSupport;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BatchCategoryImportReader} — the parse/iterate/checkpoint half of the category
 * import batch job. Driven entirely without JBeret or a DB by sub-classing the reader, wiring Mockito
 * stubs into the protected {@code batchService}/{@code jobContext} fields and pointing them at a temp
 * JSON file. See {@link BatchReaderTestSupport}.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>The reader flattens the category sibling/children hierarchy into a flat list, and <b>only leaf
 *       categories are emitted</b> (non-leaf parents are dropped because a leaf re-creates its ancestry).</li>
 *   <li>The flattened leaf carries a {@code parent} reference back to its (also flattened) ancestor.</li>
 *   <li>{@code readItem()} returns items in order and {@code null} once exhausted.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, and {@code open(checkpoint)}
 *       fast-forwards to that index so a restarted job resumes where it left off.</li>
 * </ul>
 */
public class BatchCategoryImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchCategoryImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchCategoryImportReaderTest");
        }

        void wire(BatchService batchService, JobContext jobContext) {
            this.batchService = batchService;
            this.jobContext = jobContext;
        }

        void wire(BatchService batchService, JobContext jobContext, BootstrapRun bootstrapRun, BatchData job) {
            wire(batchService, jobContext);
            this.bootstrapRun = bootstrapRun;
            this.job = job;
        }
    }

    private TestableReader reader(String json) throws Exception {
        Path file = BatchReaderTestSupport.tempJson(json);
        TestableReader reader = new TestableReader();
        reader.wire(BatchReaderTestSupport.batchService(7L, file),
                BatchReaderTestSupport.jobContext(7L));
        return reader;
    }

    /** {@code getParent()} returns the base {@link CategoryVo} type, so this helper takes that. */
    private static String name(CategoryVo category) {
        return category.getDescs().get(0).getName();
    }

    @Test
    public void emitsOnlyLeafCategoriesFromHierarchy() throws Exception {
        // One root ("Parent") with a single child ("Child"). The parent is a non-leaf and must be
        // dropped; only the leaf "Child" is emitted, with its parent reference wired up.
        String json = "[{\"descs\":[{\"lang\":\"en\",\"name\":\"Parent\"}],"
                + "\"children\":[{\"descs\":[{\"lang\":\"en\",\"name\":\"Child\"}]}]}]";

        TestableReader reader = reader(json);
        reader.open(null);

        SystemCategoryVo first = (SystemCategoryVo) reader.readItem();
        assertEquals("Child", name(first), "only the leaf category should be emitted");
        assertEquals("Parent", name(first.getParent()),
                "the leaf's parent reference should be the flattened root");

        assertNull(reader.readItem(), "no further items after the single leaf");
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo(), "one item read");
    }

    @Test
    public void emitsAllLeavesOfADeeperHierarchy() throws Exception {
        // Parent -> [Mid] -> [Leaf]. Both Parent and Mid are non-leaf and dropped; only the single
        // deepest leaf is emitted, and its parent chain points back up through the flattened nodes.
        String json = "[{\"descs\":[{\"lang\":\"en\",\"name\":\"Parent\"}],"
                + "\"children\":[{\"descs\":[{\"lang\":\"en\",\"name\":\"Mid\"}],"
                + "\"children\":[{\"descs\":[{\"lang\":\"en\",\"name\":\"Leaf\"}]}]}]}]";

        TestableReader reader = reader(json);
        reader.open(null);

        SystemCategoryVo leaf = (SystemCategoryVo) reader.readItem();
        assertEquals("Leaf", name(leaf), "only the deepest leaf is emitted");
        assertEquals("Mid", name(leaf.getParent()), "the leaf's parent is the mid-level node");
        assertEquals("Parent", name(leaf.getParent().getParent()),
                "the mid-level node's parent is the root");

        assertNull(reader.readItem(), "no further items after the single leaf");
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo());
    }

    @Test
    public void emitsFlatCategoriesInOrder() throws Exception {
        // No children anywhere -> every root is itself a leaf and is emitted in document order.
        String json = "[{\"descs\":[{\"lang\":\"en\",\"name\":\"A\"}]},"
                + "{\"descs\":[{\"lang\":\"en\",\"name\":\"B\"}]},"
                + "{\"descs\":[{\"lang\":\"en\",\"name\":\"C\"}]}]";

        TestableReader reader = reader(json);
        reader.open(null);

        assertEquals("A", name((SystemCategoryVo) reader.readItem()));
        assertEquals("B", name((SystemCategoryVo) reader.readItem()));
        assertEquals("C", name((SystemCategoryVo) reader.readItem()));
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void flattenedLeafHasItsIdReset() throws Exception {
        // The reader nulls out every node's id during flattening so imported categories are created
        // afresh rather than colliding with existing ids.
        String json = "[{\"id\":42,\"descs\":[{\"lang\":\"en\",\"name\":\"A\"}]}]";

        TestableReader reader = reader(json);
        reader.open(null);

        SystemCategoryVo first = (SystemCategoryVo) reader.readItem();
        assertNull(first.getId(), "the supplied id should be cleared on import");
        assertEquals("A", name(first));
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        String json = "[{\"descs\":[{\"lang\":\"en\",\"name\":\"A\"}]},"
                + "{\"descs\":[{\"lang\":\"en\",\"name\":\"B\"}]},"
                + "{\"descs\":[{\"lang\":\"en\",\"name\":\"C\"}]}]";

        TestableReader reader = reader(json);
        // Restart at index 2 -> the first two items are skipped.
        reader.open(2);

        assertEquals("C", name((SystemCategoryVo) reader.readItem()),
                "checkpoint restart skips already-processed items");
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

        assertNull(reader.readItem(), "an empty category file yields no items");
        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
    }

    // ------------------------------------------------------------------------------------------- //
    // Which fields an item gives: only for a later archive's job of the live bootstrap run
    // ------------------------------------------------------------------------------------------- //

    /** A job whose properties carry the token, or none */
    private static BatchData job(String token) {
        BatchData job = new BatchData();
        if (token != null) {
            job.getProperties().put(BootstrapRun.PROPERTY, token);
        }
        return job;
    }

    private TestableReader reader(String json, BootstrapRun run, BatchData job) throws Exception {
        Path file = BatchReaderTestSupport.tempJson(json);
        TestableReader reader = new TestableReader();
        reader.wire(BatchReaderTestSupport.batchService(7L, file), BatchReaderTestSupport.jobContext(7L), run, job);
        return reader;
    }

    private static final String GIVEN_JSON = "[{\"active\":true,\"siblingSortOrder\":2.0,"
            + "\"descs\":[{\"lang\":\"en\",\"name\":\"Parent\"}],"
            + "\"children\":[{\"descs\":[{\"lang\":\"en\",\"name\":\"Child\"}],"
            + "\"templateParams\":[{\"paramId\":\"p\",\"mandatory\":false}]}]},"
            + "{\"active\":false,\"descs\":[{\"lang\":\"en\",\"name\":\"Flat\"}]}]";

    @Test
    public void aJobOutsideTheLiveRunBindsAsBeforeWithNoGiven() throws Exception {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();
        registry.end(run);

        for (BatchData job : List.of(job(null), job(BootstrapRun.token(run, 1)))) {
            TestableReader reader = reader(GIVEN_JSON, registry, job);
            reader.open(null);

            SystemCategoryVo child = (SystemCategoryVo) reader.readItem();
            SystemCategoryVo flat = (SystemCategoryVo) reader.readItem();
            assertEquals("Child", name(child));
            assertEquals("Parent", name(child.getParent()));
            assertEquals("Flat", name(flat));
            assertNull(child.getGiven());
            assertNull(((SystemCategoryVo) child.getParent()).getGiven());
            assertNull(flat.getGiven());
            assertNull(reader.readItem());
        }
    }

    @Test
    public void aJobOfTheLiveRunsFirstArchiveHasNoGiven() throws Exception {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();

        TestableReader reader = reader(GIVEN_JSON, registry, job(BootstrapRun.token(run, 0)));
        reader.open(null);

        assertNull(((SystemCategoryVo) reader.readItem()).getGiven());
    }

    @Test
    public void aLaterArchivesJobOfTheLiveRunGivesEveryLeafAndParentItsGiven() throws Exception {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();

        TestableReader reader = reader(GIVEN_JSON, registry, job(BootstrapRun.token(run, 1)));
        reader.open(null);

        SystemCategoryVo child = (SystemCategoryVo) reader.readItem();
        SystemCategoryVo flat = (SystemCategoryVo) reader.readItem();
        SystemCategoryVo parent = (SystemCategoryVo) child.getParent();
        assertNotNull(child.getGiven());
        assertNotNull(parent.getGiven());
        assertTrue(parent.getGiven().has("active"), "active given at true, the import's default");
        assertTrue(parent.getGiven().has("siblingSortOrder"));
        assertFalse(child.getGiven().has("active"), "the child leaves active out");
        assertTrue(child.getGiven().has("descs[en]"));
        assertTrue(child.getGiven().has("templateParams[p#0].mandatory"), "mandatory given at false");
        assertTrue(flat.getGiven().has("active"), "active given false");
        assertEquals("Flat", name(flat));
    }
}
