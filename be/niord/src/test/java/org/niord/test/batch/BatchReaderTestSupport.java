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
package org.niord.test.batch;

import jakarta.batch.runtime.context.JobContext;
import org.niord.core.batch.BatchService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared scaffolding for unit-testing the JSR-352 batch <em>readers</em> (the parse/iterate/checkpoint
 * half of the import jobs) in isolation, i.e. without a running JBeret runtime or a database.
 *
 * <p>The readers all extend {@code org.niord.core.batch.AbstractItemHandler} and, in {@code open()},
 * fetch their data file via {@code batchService.getBatchJobDataFile(jobContext.getInstanceId())}.
 * Both collaborators are protected {@code @Inject} fields on the handler, so a same-package test
 * sub-class can wire Mockito stubs into them. The file-based job {@code getLog()} is overridden by
 * each test sub-class to a quiet logger so no batch-job log folder is touched.
 *
 * <p>This helper centralises the three reusable pieces: writing the JSON data file to a temp path,
 * a {@link BatchService} stub that serves that file, and a {@link JobContext} stub with a fixed
 * instance id. It is intentionally framework-neutral apart from Mockito (declared test-scope in this
 * module specifically to stub container-owned types such as {@link JobContext}).
 */
public final class BatchReaderTestSupport {

    private BatchReaderTestSupport() {
    }

    /** A {@code java.util.logging.Logger} with no handlers, so reader log calls are silent no-ops. */
    public static Logger quietLogger(String name) {
        Logger log = Logger.getLogger(name);
        log.setUseParentHandlers(false);
        return log;
    }

    /** Writes the given JSON to a temp file that the batch reader can deserialize. */
    public static Path tempJson(String json) throws IOException {
        Path p = Files.createTempFile("batch-reader-", ".json");
        Files.write(p, json.getBytes(StandardCharsets.UTF_8));
        p.toFile().deleteOnExit();
        return p;
    }

    /**
     * A {@link BatchService} mock whose {@code getBatchJobDataFile(instanceId)} returns the given
     * data file and whose progress update is a harmless no-op.
     */
    public static BatchService batchService(long instanceId, Path dataFile) {
        BatchService batchService = mock(BatchService.class);
        when(batchService.getBatchJobDataFile(instanceId)).thenReturn(dataFile);
        doNothing().when(batchService).updateBatchJobProgress(anyLong(), anyInt());
        return batchService;
    }

    /** A {@link JobContext} mock reporting the given batch-job instance id. */
    public static JobContext jobContext(long instanceId) {
        JobContext jobContext = mock(JobContext.class);
        when(jobContext.getInstanceId()).thenReturn(instanceId);
        return jobContext;
    }
}
