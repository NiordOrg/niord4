/*
 * Copyright 2017 Danish Maritime Authority.
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
package org.niord.core.report.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.report.FmReport;
import org.niord.core.report.vo.FmReportVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Framework-neutral unit tests for {@link BatchReportImportProcessor#processItem(Object)}.
 *
 * <p>The processor is the conversion step of the report import batch job: it casts each batch item to a
 * {@link FmReportVo} and constructs the corresponding {@link FmReport} entity via the
 * {@code FmReport(FmReportVo)} constructor (which delegates to {@code updateReport}). The CDI-injected
 * infrastructure inherited from {@code AbstractItemHandler} (job context, logger bridge, etc.) is never
 * touched by {@code processItem}, so the bean is exercised here as a plain object via
 * {@code new BatchReportImportProcessor()} — mirroring the {@code BatchPromulgationTypeImportProcessorTest}
 * approach. No broker, no database, no Quarkus container is involved.
 *
 * <p>The {@code domains} field is intentionally left {@code null} on the value object: that exercises the
 * {@code report.getDomains() != null} guard in {@code updateReport} without needing the {@code Domain}
 * entity's CDI-backed collaborators.
 *
 * <p>These tests pin two things: (1) the value-object-to-entity field mapping performed by the processor —
 * including the contents of the {@code properties} and {@code params} maps — and (2) the documented
 * unchecked cast — a non-{@code FmReportVo} item surfaces as a {@link ClassCastException} rather than being
 * silently skipped.
 */
public class BatchReportImportProcessorTest {

    private BatchReportImportProcessor processor;

    @BeforeEach
    public void setUp() {
        processor = new BatchReportImportProcessor();
    }

    /**
     * A fully-populated {@link FmReportVo} item is converted into an {@link FmReport} entity that carries
     * the value object's scalar fields verbatim and copies the {@code properties} and {@code params} map
     * entries into the entity's own maps.
     */
    @Test
    public void processItemWithFmReportVo_copiesFieldsIntoEntity() throws Exception {
        FmReportVo vo = new FmReportVo();
        vo.setReportId("standard");
        vo.setName("Standard report");
        vo.setSortOrder(7);
        vo.setTemplatePath("/templates/standard.ftl");
        vo.getProperties().put("pageSize", "A4");
        vo.getParams().put("includeAttachments", Boolean.TRUE);

        Object result = processor.processItem(vo);

        assertTrue(result instanceof FmReport, "processItem must return an FmReport entity");

        FmReport report = (FmReport) result;
        assertEquals("standard", report.getReportId(), "reportId must be copied from the VO");
        assertEquals("Standard report", report.getName(), "name must be copied from the VO");
        assertEquals(Integer.valueOf(7), report.getSortOrder(), "sortOrder must be copied from the VO");
        assertEquals("/templates/standard.ftl", report.getTemplatePath(),
                "templatePath must be copied from the VO");

        assertEquals("A4", report.getProperties().get("pageSize"),
                "properties entry must be copied into the entity map");
        assertEquals(Boolean.TRUE, report.getParams().get("includeAttachments"),
                "params entry must be copied into the entity map");

        // domains was left null on the VO, so the updateReport guard leaves the entity's default empty list.
        assertTrue(report.getDomains().isEmpty(),
                "null VO domains must leave the entity's default empty domain list");
        assertEquals(1, report.getParams().size(),
                "only the single supplied param must be copied; no extras");
    }

    /**
     * The processor performs an unchecked cast to {@link FmReportVo}. Feeding it an item of an unrelated
     * type fails fast with a {@link ClassCastException} rather than producing a malformed entity.
     */
    @Test
    public void processItemWithWrongType_throwsClassCastException() throws Exception {
        try {
            processor.processItem("not a vo");
            fail("Expected ClassCastException when item is not an FmReportVo");
        } catch (ClassCastException expected) {
            // Expected: documents the unchecked (FmReportVo) cast in processItem.
        }
    }
}
