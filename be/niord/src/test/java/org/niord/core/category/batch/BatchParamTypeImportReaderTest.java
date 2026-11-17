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
import org.niord.core.category.vo.CompositeParamTypeVo;
import org.niord.core.category.vo.ListParamTypeVo;
import org.niord.core.category.vo.ParamTypeVo;
import org.niord.test.batch.BatchReaderTestSupport;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link BatchParamTypeImportReader} — the parse/iterate/checkpoint half of the
 * parameter-type import batch job. Driven entirely without JBeret or a DB by sub-classing the reader,
 * wiring Mockito stubs into the protected {@code batchService}/{@code jobContext} fields and pointing
 * them at a temp JSON file. See {@link BatchReaderTestSupport}.
 *
 * <p>Unlike the area/category readers this one performs a <b>plain flat read</b>: no hierarchy is
 * flattened and no items are filtered, so every element of the JSON array is emitted in document order.
 * The interesting behaviour here is <b>polymorphic deserialization</b>: {@link ParamTypeVo} is an
 * abstract super-class carrying a Jackson {@code @JsonTypeInfo(... property = "type")} discriminator,
 * and the reader deserializes the file into a {@code List<ParamTypeVo>}. Each emitted item therefore
 * has to be reconstituted as the correct concrete subtype.
 *
 * <p><b>Why the fixtures embed an explicit {@code "type"} field.</b> The discriminator is supplied
 * in the source JSON (exactly as the production import format does, e.g. {@code "type" : "COMPOSITE"}).
 * The fixtures are hand-authored rather than produced by serializing built VOs because a plain Jackson
 * round-trip of these VOs does not reproduce the {@code "type"} property — so explicit discriminators
 * are the faithful representation of a real param-types.json file. The {@code "LIST"} and
 * {@code "COMPOSITE"} discriminators are used because they resolve to the value-object subtypes
 * ({@link ListParamTypeVo} / {@link CompositeParamTypeVo}).
 *
 * <p>Behaviour pinned here:
 * <ul>
 *   <li>A {@code "LIST"} entry deserializes to {@link ListParamTypeVo} and a {@code "COMPOSITE"}
 *       entry to {@link CompositeParamTypeVo}, with their {@code name} preserved.</li>
 *   <li>{@code readItem()} returns the items in document order and {@code null} once exhausted.</li>
 *   <li>{@code checkpointInfo()} reports the number of items read, and {@code open(checkpoint)}
 *       fast-forwards to that index so a restarted job resumes where it left off.</li>
 * </ul>
 */
public class BatchParamTypeImportReaderTest {

    /** A reader that silences the batch-folder logger and exposes a wiring hook for the stubs. */
    static class TestableReader extends BatchParamTypeImportReader {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchParamTypeImportReaderTest");
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

    /** A single-element array carrying the given discriminator and name. */
    private static String entry(String type, String name) {
        return "{\"type\":\"" + type + "\",\"name\":\"" + name + "\"}";
    }

    @Test
    public void emitsListParamTypeAsItsConcreteSubtype() throws Exception {
        TestableReader reader = reader("[" + entry("LIST", "buoy_function_list") + "]");
        reader.open(null);

        ParamTypeVo first = (ParamTypeVo) reader.readItem();
        assertSame(ListParamTypeVo.class, first.getClass(),
                "a LIST discriminator must reconstitute the list value-object subtype");
        assertEquals("buoy_function_list", first.getName(),
                "the param type name must survive deserialization");

        assertNull(reader.readItem(), "no further items after the single param type");
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo(), "one item read");
    }

    @Test
    public void emitsCompositeParamTypeAsItsConcreteSubtype() throws Exception {
        TestableReader reader = reader("[" + entry("COMPOSITE", "buoy_by_function") + "]");
        reader.open(null);

        ParamTypeVo first = (ParamTypeVo) reader.readItem();
        assertSame(CompositeParamTypeVo.class, first.getClass(),
                "a COMPOSITE discriminator must reconstitute the composite value-object subtype");
        assertEquals("buoy_by_function", first.getName(),
                "the param type name must survive deserialization");

        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo());
    }

    @Test
    public void emitsPolymorphicParamTypesInDocumentOrder() throws Exception {
        // Two distinct subtypes in a fixed order: the reader must preserve both the order and the
        // per-element concrete type (no flattening, no filtering).
        String json = "[" + entry("LIST", "first_list") + ","
                + entry("COMPOSITE", "second_comp") + "]";

        TestableReader reader = reader(json);
        reader.open(null);

        ParamTypeVo first = (ParamTypeVo) reader.readItem();
        assertSame(ListParamTypeVo.class, first.getClass());
        assertEquals("first_list", first.getName());

        ParamTypeVo second = (ParamTypeVo) reader.readItem();
        assertSame(CompositeParamTypeVo.class, second.getClass());
        assertEquals("second_comp", second.getName());

        assertNull(reader.readItem(), "only the two declared items are emitted");
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo(), "two items read");
    }

    @Test
    public void checkpointAdvancesWithEachRead() throws Exception {
        String json = "[" + entry("LIST", "a") + "," + entry("COMPOSITE", "b") + "]";

        TestableReader reader = reader(json);
        reader.open(null);

        assertEquals(Integer.valueOf(0), (Integer) reader.checkpointInfo(), "nothing read yet");
        reader.readItem();
        assertEquals(Integer.valueOf(1), (Integer) reader.checkpointInfo());
        reader.readItem();
        assertEquals(Integer.valueOf(2), (Integer) reader.checkpointInfo());
    }

    @Test
    public void resumesFromCheckpoint() throws Exception {
        // Three items; restart at index 2 -> the first two are skipped and only the third is emitted.
        String json = "[" + entry("LIST", "a") + "," + entry("COMPOSITE", "b") + ","
                + entry("LIST", "c") + "]";

        TestableReader reader = reader(json);
        reader.open(2);

        ParamTypeVo resumed = (ParamTypeVo) reader.readItem();
        assertEquals("c", resumed.getName(), "checkpoint restart skips already-processed items");
        assertSame(ListParamTypeVo.class, resumed.getClass());

        assertNull(reader.readItem());
        assertEquals(Integer.valueOf(3), (Integer) reader.checkpointInfo());
    }

    @Test
    public void emptyDataFileEmitsNothing() throws Exception {
        TestableReader reader = reader("[]");
        reader.open(null);

        assertNull(reader.readItem(), "an empty param-types file yields no items");
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

    private static final String GIVEN_JSON = "[{\"type\":\"COMPOSITE\",\"name\":\"c\","
            + "\"templateParams\":[{\"paramId\":\"p\",\"mandatory\":false,"
            + "\"descs\":[{\"lang\":\"en\",\"name\":\"P\"}]}]},"
            + "{\"type\":\"LIST\",\"name\":\"l\",\"values\":[{\"key\":\"k1\"}]}]";

    @Test
    public void aJobOutsideTheLiveRunBindsAsBeforeWithNoGiven() throws Exception {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();
        registry.end(run);

        for (BatchData job : List.of(job(null), job(BootstrapRun.token(run, 1)))) {
            TestableReader reader = reader(GIVEN_JSON, registry, job);
            reader.open(null);

            ParamTypeVo composite = (ParamTypeVo) reader.readItem();
            ParamTypeVo list = (ParamTypeVo) reader.readItem();
            assertEquals(CompositeParamTypeVo.class, composite.getClass());
            assertEquals(ListParamTypeVo.class, list.getClass());
            assertEquals("c", composite.getName());
            assertNull(composite.getGiven());
            assertNull(list.getGiven());
            assertNull(composite.toEntity().getGiven());
        }
    }

    @Test
    public void aJobOfTheLiveRunsFirstArchiveHasNoGiven() throws Exception {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();

        TestableReader reader = reader(GIVEN_JSON, registry, job(BootstrapRun.token(run, 0)));
        reader.open(null);

        assertNull(((ParamTypeVo) reader.readItem()).getGiven());
    }

    @Test
    public void aLaterArchivesJobOfTheLiveRunGivesEachItemItsGiven() throws Exception {
        BootstrapRun registry = new BootstrapRun();
        String run = registry.begin();

        TestableReader reader = reader(GIVEN_JSON, registry, job(BootstrapRun.token(run, 1)));
        reader.open(null);

        ParamTypeVo composite = (ParamTypeVo) reader.readItem();
        ParamTypeVo list = (ParamTypeVo) reader.readItem();
        assertTrue(composite.getGiven().has("templateParams[p#0].mandatory"), "mandatory given at false");
        assertTrue(composite.getGiven().has("templateParams[p#0].descs[en]"));
        assertFalse(composite.getGiven().has("templateParams[p#0].type"));
        assertTrue(list.getGiven().has("values"));
        assertTrue(composite.toEntity().getGiven().has("templateParams"), "the entity carries the VO's Given");
    }
}
