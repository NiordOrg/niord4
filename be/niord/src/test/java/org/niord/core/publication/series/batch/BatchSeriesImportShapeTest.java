/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core.publication.series.batch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the series import may and may not do to a series that already exists.
 *
 * Two failures, and they have opposite shapes. Nulling the category is a
 * CONSTRAINT violation raised inside a batch flush, which covers a whole chunk --
 * so one document missing an optional field killed the ten items around it, and
 * the log named a Java property rather than the file. Skipping validation is the
 * quiet one: a document could store a release mode the system cannot honour or a
 * report parameter the issue supplies, and the series looked imported until
 * somebody tried to activate it, at which point the refusal named a field nobody
 * had typed.
 *
 * WHY THIS IS A SOURCE ASSERTION. The processor is a CDI item handler over three
 * injected services and a shared database. Reaching it means standing up a batch
 * job against an estate that other work is using at the same time, to assert two
 * branches; reading the branches is the cheaper trade and it fails for the same
 * reason a behavioural test would.
 */
public class BatchSeriesImportShapeTest {

    private static final Path PROCESSOR = Paths.get("src/main/java/org/niord/core/publication/"
            + "series/batch/BatchPublicationSeriesImportProcessor.java");

    @Test
    public void anabsentCategoryDoesNotClearTheOneTheSeriesHas() throws IOException {
        String src = read();
        assertFalse(src.contains("series.updateFromVo(vo);\n        series.setCategory(category);"),
                "the category is set unconditionally. A document that omits categoryId then writes "
                        + "null into a NOT NULL column, and the violation surfaces at the batch "
                        + "flush -- taking the whole chunk with it, named after a Java property.");
        assertTrue(src.contains("if (category != null) {"),
                "the conditional that keeps an existing category is gone");
        assertTrue(src.contains("} else if (series.getCategory() == null) {"),
                "a NEW series with no category has nowhere to go and must be dropped with a "
                        + "sentence, rather than taking its neighbours down at the flush");
    }

    @Test
    public void theImportRunsTheSameHardRulesTheSavesRun() throws IOException {
        String src = read();
        assertTrue(src.contains("SeriesValidator.hardRules(series)"),
                "the import does not apply the hard rules. A draft may be incomplete; it may not "
                        + "be wrong -- and a file written against another installation is exactly "
                        + "where a wrong one comes from.");
        assertTrue(src.contains("seriesService.danglingOperandsOf(series)"),
                "the import does not ask C-4. Every REST save refuses an operand that names nothing, "
                        + "and a file from another installation is where a misspelt one comes from "
                        + "(PUBSERIES1).");
        assertTrue(src.contains("seriesService.activationErrorsOf(series)"),
                "an ACTIVE series may not be edited into incompleteness by a document either; the "
                        + "update endpoint refuses that, with the operands resolved, and the import "
                        + "inherits nothing");
        // The resolver reads a domain operand's lazy message-series collection, which
        // needs a transaction; this processor runs on the batch thread without one, so
        // the operand questions go through the transactional service, never the static
        // calls with an injected resolver (a valid domain operand failed the job).
        assertFalse(src.contains("SeriesValidator.danglingOperands("),
                "C-4 is asked outside a transaction: a valid 'domain' operand throws "
                        + "LazyInitializationException on the batch thread and fails the job");
        assertFalse(src.contains("SeriesValidator.validateForActivation("),
                "the activation rules are asked outside a transaction: a valid 'domain' operand "
                        + "throws LazyInitializationException on the batch thread and fails the job");
    }

    /** An imported document never activates a series, whatever it says. */
    @Test
    public void theStatusIsStillPinned() throws IOException {
        String src = read();
        assertTrue(src.contains("SeriesStatus keep = existing == null ? SeriesStatus.DRAFT"),
                "the status is no longer pinned. Activation validates against every series rule, "
                        + "and a file that could set it routes around all of them.");
        assertTrue(src.contains("series.setStatus(keep);"));
    }

    /**
     * A named source series is resolved, and a missing one drops the row.
     *
     * The failure it guards is quiet: a compilation whose source is not in this
     * installation resolves nothing, so it would import looking configured and
     * publish empty issues. The category precedent is the same shape and is
     * asserted the same way, one line above the field it protects.
     */
    @Test
    public void amissingSourceSeriesDropsTheRowRatherThanImportingAnEmptyCompilation()
            throws IOException {
        String src = read();
        assertTrue(src.contains("seriesService.findBySeriesId(vo.getSourceSeriesId().trim())"),
                "the source series is not resolved, so sourceSeriesId in a file reaches nothing");
        assertTrue(src.contains("import the source first"),
                "a source this installation does not have must drop the row with a sentence that "
                        + "says what to do about it, not fail at the flush");
        assertTrue(src.contains("series.setSourceSeries(source);"),
                "the resolved source never reaches the series");
    }

    /**
     * The domain guard runs on every row, before the row changes anything.
     *
     * The decision itself is pinned by BatchPublicationSeriesImportDomainTest; this
     * pins that processItem asks it, against the domain the job was started from,
     * and does so before the document is applied and before the owner is written.
     */
    @Test
    public void theDomainGuardRunsBeforeTheRowIsApplied() throws IOException {
        String src = read();
        int guard = src.indexOf("domainRefusal(existing, domain, job == null ? null : job.getDomain())");
        assertTrue(guard >= 0,
                "processItem no longer asks the domain guard with the job's domain, so a file could "
                        + "create, overwrite or take over another domain's series");
        int apply = src.indexOf("series.updateFromVo(vo);");
        int owner = src.indexOf("series.setDomain(domain);");
        assertTrue(apply > guard && owner > guard,
                "the domain guard runs after the document is applied to the series; a refused row "
                        + "must not change anything");
    }

    private static String read() throws IOException {
        assertTrue(Files.isRegularFile(PROCESSOR),
                "missing " + PROCESSOR + " -- this test reads source, so a move breaks it silently "
                        + "otherwise");
        return Files.readString(PROCESSOR, StandardCharsets.UTF_8);
    }
}
