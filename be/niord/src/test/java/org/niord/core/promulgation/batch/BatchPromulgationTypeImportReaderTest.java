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
package org.niord.core.promulgation.batch;

import jakarta.batch.runtime.context.JobContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.batch.BatchService;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.PromulgationType.Requirement;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.util.JsonUtils;
import org.niord.test.batch.BatchReaderTestSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BatchPromulgationTypeImportReader} — the parse/iterate/checkpoint half of the
 * promulgation-type import batch job. Driven without JBeret or a DB by sub-classing the reader, wiring
 * Mockito stubs into the protected {@code batchService}/{@code jobContext} fields and pointing them at
 * a temp JSON file. See {@link BatchReaderTestSupport}.
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>The reader is a <b>flat</b> reader: it deserializes a {@code List<PromulgationTypeVo>} and
 *       emits each element verbatim, in file order, with no hierarchy flattening or filtering.</li>
 *   <li>{@code readItem()} returns the VO instances in order, preserving their fields
 *       ({@code typeId}, {@code name}, {@code language}, {@code requirement}), and {@code null} once
 *       exhausted.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, and {@code open(checkpoint)}
 *       fast-forwards to that index so a restarted job resumes where it left off.</li>
 * </ul>
 */
public class BatchPromulgationTypeImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchPromulgationTypeImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchPromulgationTypeImportReaderTest");
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

    /** Builds a promulgation type VO with the fields exercised by these tests. */
    private static PromulgationTypeVo type(String typeId, String name, String language,
                                           Requirement requirement) {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId(typeId);
        vo.setName(name);
        vo.setLanguage(language);
        vo.setRequirement(requirement);
        return vo;
    }

    /**
     * Serializes the given VOs to a temp JSON file (the on-disk format the reader deserializes) and
     * returns a reader wired against that file.
     */
    private TestableReader reader(List<PromulgationTypeVo> types) throws Exception {
        Path file = Files.createTempFile("batch-prom-type-", ".json");
        file.toFile().deleteOnExit();
        JsonUtils.writeJson(types, file);

        TestableReader reader = new TestableReader();
        reader.wire(BatchReaderTestSupport.batchService(7L, file),
                BatchReaderTestSupport.jobContext(7L));
        return reader;
    }

    @Test
    public void emitsTypeVerbatimWithAllFields() throws Exception {
        List<PromulgationTypeVo> types = new ArrayList<>();
        types.add(type("navtex", "NAVTEX", "en", Requirement.MANDATORY));

        TestableReader reader = reader(types);
        reader.open(null);

        PromulgationTypeVo first = (PromulgationTypeVo) reader.readItem();
        assertEquals("navtex", first.getTypeId(), "typeId round-trips through the data file");
        assertEquals("NAVTEX", first.getName(), "name round-trips through the data file");
        assertEquals("en", first.getLanguage(), "language round-trips through the data file");
        assertEquals(Requirement.MANDATORY, first.getRequirement(),
                "requirement enum round-trips through the data file");

        assertNull(reader.readItem(), "no further items after the single type");
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo(), "one item read");
    }

    @Test
    public void emitsTypesInFileOrder() throws Exception {
        List<PromulgationTypeVo> types = new ArrayList<>();
        types.add(type("navtex", "NAVTEX", "en", Requirement.MANDATORY));
        types.add(type("twitter", "Twitter", "en", Requirement.OPTIONAL));

        TestableReader reader = reader(types);
        reader.open(null);

        PromulgationTypeVo first = (PromulgationTypeVo) reader.readItem();
        assertEquals("navtex", first.getTypeId(), "first emitted type preserves file order");
        assertEquals("NAVTEX", first.getName());

        PromulgationTypeVo second = (PromulgationTypeVo) reader.readItem();
        assertEquals("twitter", second.getTypeId(), "second emitted type preserves file order");
        assertEquals("Twitter", second.getName());
        assertEquals(Requirement.OPTIONAL, second.getRequirement(),
                "requirement of the second type is preserved");

        assertNull(reader.readItem(), "no further items after the two types");
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo(), "two items read");
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        List<PromulgationTypeVo> types = new ArrayList<>();
        types.add(type("navtex", "NAVTEX", "en", Requirement.MANDATORY));
        types.add(type("twitter", "Twitter", "en", Requirement.OPTIONAL));

        TestableReader reader = reader(types);
        // Restart at index 1 -> the first item is skipped.
        reader.open(1);

        PromulgationTypeVo resumed = (PromulgationTypeVo) reader.readItem();
        assertEquals("twitter", resumed.getTypeId(), "checkpoint restart skips already-processed items");
        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo());
    }

    @Test
    public void checkpointAdvancesWithEachRead() throws Exception {
        List<PromulgationTypeVo> types = new ArrayList<>();
        types.add(type("navtex", "NAVTEX", "en", Requirement.DEFAULT));
        types.add(type("twitter", "Twitter", "en", Requirement.OPTIONAL));

        TestableReader reader = reader(types);
        reader.open(null);

        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo());
    }

    @Test
    public void emptyDataFileEmitsNothing() throws Exception {
        TestableReader reader = reader(new ArrayList<>());
        reader.open(null);

        assertNull(reader.readItem(), "an empty promulgation-type file yields no items");
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

    private static final String GIVEN_JSON = "[{\"typeId\":\"a\",\"active\":true,\"priority\":0},"
            + "{\"typeId\":\"b\",\"domains\":[{\"domainId\":\"d\"}]}]";

    @Test
    public void aJobOutsideTheLiveRunBindsAsBeforeWithNoGiven() throws Exception {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();
        registry.end(run);

        for (BatchData job : List.of(job(null), job(BootstrapRun.token(run, 1)))) {
            TestableReader reader = reader(GIVEN_JSON, registry, job);
            reader.open(null);

            PromulgationTypeVo a = (PromulgationTypeVo) reader.readItem();
            PromulgationTypeVo b = (PromulgationTypeVo) reader.readItem();
            assertEquals("a", a.getTypeId());
            assertEquals("b", b.getTypeId());
            assertNull(a.getGiven());
            assertNull(b.getGiven());
            assertNull(new PromulgationType(a).getGiven());
        }
    }

    @Test
    public void aJobOfTheLiveRunsFirstArchiveHasNoGiven() throws Exception {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();

        TestableReader reader = reader(GIVEN_JSON, registry, job(BootstrapRun.token(run, 0)));
        reader.open(null);

        assertNull(((PromulgationTypeVo) reader.readItem()).getGiven());
    }

    @Test
    public void aLaterArchivesJobOfTheLiveRunGivesEachItemItsGiven() throws Exception {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();

        TestableReader reader = reader(GIVEN_JSON, registry, job(BootstrapRun.token(run, 1)));
        reader.open(null);

        PromulgationTypeVo a = (PromulgationTypeVo) reader.readItem();
        PromulgationTypeVo b = (PromulgationTypeVo) reader.readItem();
        assertTrue(a.getGiven().has("active"), "active given at true");
        assertTrue(a.getGiven().has("priority"), "priority given at 0");
        assertFalse(b.getGiven().has("priority"));
        assertTrue(b.getGiven().has("domains"));
        assertTrue(new PromulgationType(a).getGiven().has("active"), "the entity carries the VO's Given");
    }
}
