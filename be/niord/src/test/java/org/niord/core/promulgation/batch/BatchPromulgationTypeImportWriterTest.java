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
package org.niord.core.promulgation.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.test.batch.BatchReaderTestSupport;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The promulgation type import's writer outside the first bootstrap's rule (docs/code-data-config-plan.md §7.2): a
 * job without the bootstrap runner's token, a sysadmin's, and a job with the token of a run that has ended update a
 * type they find whole and never replace values in it; only a later archive's job of the live run does.
 */
class BatchPromulgationTypeImportWriterTest {

    /** A writer that silences the batch-folder logger and takes its collaborators by hand */
    static class TestableWriter extends BatchPromulgationTypeImportWriter {
        @Override
        protected Logger getLog() {
            return BatchReaderTestSupport.quietLogger("BatchPromulgationTypeImportWriterTest");
        }

        void wire(PromulgationTypeService service, BootstrapRun run, BatchData job) {
            this.promulgationTypeService = service;
            this.bootstrapRun = run;
            this.job = job;
        }
    }

    PromulgationTypeService service;
    BootstrapRun registry;
    PromulgationType found;

    @BeforeEach
    void setUp() {
        service = mock(PromulgationTypeService.class);
        registry = new BootstrapRun();
        found = new PromulgationType();
        found.setTypeId("it-type");
        when(service.getPromulgationType("it-type")).thenReturn(found);
        when(service.replaceValues(any(), any(), any())).thenReturn(
                new BootstrapRun.Additions("Promulgation type it-type", List.of(), List.of(), List.of()));
    }

    private static BatchData job(Object token) {
        BatchData job = new BatchData();
        if (token != null) {
            job.getProperties().putAll(Map.of(BootstrapRun.PROPERTY, token));
        }
        return job;
    }

    private static PromulgationType item() {
        PromulgationType type = new PromulgationType();
        type.setTypeId("it-type");
        return type;
    }

    private void write(BatchData job) throws Exception {
        TestableWriter writer = new TestableWriter();
        writer.wire(service, registry, job);
        writer.writeItems(List.of(item()));
    }

    /** Records the type as created by the run's first archive, as that archive's job would */
    private String aRunWhoseFirstArchiveCreatedTheType() {
        String run = registry.begin();
        registry.scopeOf(job(BootstrapRun.token(run, 0))).created(BootstrapRun.Kind.PROMULGATION_TYPE, "it-type");
        return run;
    }

    @Test
    void aJobWithoutTheTokenUpdatesWhole() throws Exception {
        aRunWhoseFirstArchiveCreatedTheType();

        write(job(null));

        verify(service).updatePromulgationType(any());
        verify(service, never()).replaceValues(any(), any(), any());
    }

    @Test
    void aJobWithTheEndedRunsTokenUpdatesWhole() throws Exception {
        String run = aRunWhoseFirstArchiveCreatedTheType();
        registry.end(run);

        write(job(BootstrapRun.token(run, 1)));

        verify(service).updatePromulgationType(any());
        verify(service, never()).replaceValues(any(), any(), any());
    }

    @Test
    void aLaterArchivesJobOfTheLiveRunReplacesValues() throws Exception {
        String run = aRunWhoseFirstArchiveCreatedTheType();

        write(job(BootstrapRun.token(run, 1)));

        verify(service).replaceValues(any(), any(), any());
        verify(service, never()).updatePromulgationType(any());
    }
}
