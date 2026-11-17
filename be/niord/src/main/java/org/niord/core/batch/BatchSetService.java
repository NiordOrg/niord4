/*
 * Copyright 2016 Danish Maritime Authority.
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

package org.niord.core.batch;

import org.springframework.stereotype.Service;

import tools.jackson.core.type.TypeReference;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.batch.vo.BatchSetVo;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.util.JsonUtils;
import org.niord.core.util.MdcContext;
import org.niord.core.util.ZipUtils;
import org.slf4j.Logger;

import jakarta.inject.Inject;
import java.util.UUID;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A <i>batch set</i> is a zipped archive that contains the following files:
 * <ul>
 *     <li>batch-set.json: Contains an array of BatchSetVo instances, each representing
 *              a batch job</li>
 *     <li>The batch job data files referenced in the batch-set.json file</li>
 * </ul>
 * <p>
 * A batch set is uploaded on the Admin -> Batch Jobs page, and nowhere else: no folder on the server starts one
 * (docs/code-data-config-plan.md D1). It may name only the jobs of {@link #BATCH_SET_JOBS}; a set that names any
 * other, or whose item carries the property {@value BootstrapRun#PROPERTY} (the bootstrap runner's own token, §7.2),
 * is refused whole, before anything is scheduled. The script-resource and dictionary imports run from their own
 * pages, where the sysadmin sees a preview first, the settings import from the Settings page, and script-executor
 * only through {@code POST /rest/batch/execute-javascript}. A fresh database gets the import jobs from the bootstrap, which runs
 * them itself, on a database whose marker reads pending ({@link org.niord.core.bootstrap.BootstrapRunner#ACCEPTED_JOBS}).
 */
@Service
@SuppressWarnings("unused")
public class BatchSetService {

    /**
     * The jobs a batch set may run. Not the script-resource and dictionary imports, whose own pages show what they
     * would change first, not the settings import, which runs from the Settings page, and not script-executor, which
     * runs code.
     */
    public static final List<String> BATCH_SET_JOBS = List.of(
            "domain-import",
            "category-import",
            "param-type-import",
            "mailing-list-import",
            "report-import",
            "promulgation-type-import",
            "chart-import",
            "area-import",
            "source-import",
            "publication-category-import",
            "publication-import",
            "publication-series-import",
            "message-import",
            "msg-archive-import");

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(BatchSetService.class);

    @Inject
    BatchService batchService;

    /**
     * Reads the batch set specification from the given folder
     * @param folder the folder
     * @return the batch set specification
     */
    private BatchSetSpecification readBatchSetFromFolder(Path folder) throws Exception {

        if (!Files.isDirectory(folder)) {
            throw new Exception("Non-existing batch set folder " + folder);
        }

        // Read the batch-set.json file
        Path batchSetFile = folder.resolve("batch-set.json");
        if (!Files.isRegularFile(batchSetFile)) {
            throw new Exception("Batch set did not contain a batch-set.json file");
        }

        List<BatchSetVo> batchSetItems = JsonUtils.readJson(
                new TypeReference<List<BatchSetVo>>() {},
                batchSetFile);

        return new BatchSetSpecification(folder, batchSetItems);
    }

    /** Called to start a batch set execution from a zipped batch set archive **/
    @SuppressWarnings("all")
    public void extractAndExecuteBatchSetArchive(InputStream inputStream, StringBuilder txt) throws Exception {

        // Create a work folder the batch expiry reaches: the ten-day sweep walks batch-jobs/<job>/execution/
        // and nothing else, so the archive is unpacked under a job folder of its own, where it goes after ten days
        // as an execution's files do
        Path folder = batchService.getBatchJobRoot()
                .resolve("batch-set").resolve("execution").resolve(UUID.randomUUID().toString());
        Files.createDirectories(folder);

        // Unzip the archive. Every entry must land inside the folder (ZipUtils.resolveEntry fails otherwise).
        log.info("Unzipping batch set zip archive to folder " + folder);
        //get the zip file content
        ZipInputStream zis = new ZipInputStream(inputStream);
        ZipEntry entry = zis.getNextEntry();
        while (entry != null) {
            File entryDestination = ZipUtils.resolveEntry(folder, entry.getName()).toFile();
            if (entry.isDirectory()) {
                entryDestination.mkdirs();
            } else {
                entryDestination.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(entryDestination)) {
                    IOUtils.copy(zis, out);
                }
            }
            entry = zis.getNextEntry();
        }
        zis.closeEntry();

        // Execute the batch set, once every job it names is one a batch set may run
        BatchSetSpecification batchSetSpec = readBatchSetFromFolder(folder);
        checkJobs(batchSetSpec);
        executeBatchSet(batchSetSpec, txt);
    }

    /**
     * Refuses the whole batch set when an item names a job outside {@link #BATCH_SET_JOBS}, or carries the property
     * {@value BootstrapRun#PROPERTY}, the bootstrap runner's own token, which an archive item may not carry either
     * ({@code BootstrapArchive})
     * @param batchSetSpec the batch set specification
     */
    private void checkJobs(BatchSetSpecification batchSetSpec) {
        for (BatchSetVo item : batchSetSpec.getBatchSetItems()) {
            String job = item == null ? null : item.getJobName();
            // List.of(...).contains(null) throws, so a nameless item is refused before the lookup
            if (StringUtils.isBlank(job)) {
                throw new IllegalArgumentException("The batch set names a job without a name, so nothing was "
                        + "scheduled. A batch set may run " + String.join(", ", BATCH_SET_JOBS) + ".");
            }
            if (item.getProperties() != null && item.getProperties().containsKey(BootstrapRun.PROPERTY)) {
                throw new IllegalArgumentException("The batch set gives the job '" + job + "' the property '"
                        + BootstrapRun.PROPERTY + "', the bootstrap runner's own token, so nothing was scheduled.");
            }
            if (!BATCH_SET_JOBS.contains(job)) {
                throw new IllegalArgumentException("The batch set names the job '" + job + "', which a batch set "
                        + "may not run, so nothing was scheduled. script-resource-import and dictionary-import run "
                        + "only from their own pages, with the preview, or in the bootstrap of a fresh database; "
                        + "settings-import only from the Settings page; script-executor only through "
                        + "execute-javascript. A batch set may run "
                        + String.join(", ", BATCH_SET_JOBS) + ".");
            }
        }
    }

    /**
     * Starts executing a batch set by scheduling each batch job of the specification
     * @param batchSetSpec the batch set specification
     */
    private void executeBatchSet(BatchSetSpecification batchSetSpec, StringBuilder txt) {
        txt = txt != null ? txt : new StringBuilder();

        for (BatchSetVo batchSetItem : batchSetSpec.getBatchSetItems()) {

            BatchSetExecution execution = new BatchSetExecution(batchSetSpec, batchSetItem);
            long delay = batchSetItem.getDelay();

            txt.append("Scheduling batch job ")
                    .append(batchSetItem.getJobName())
                    .append(" in ")
                    .append(delay)
                    .append(" ms\n");

            // java.util.Timer is neither Spring-managed nor reached by the MDC TaskDecorator, and this
            // runs on the POST /rest/batch/execute-batch-set request thread. Capture the correlation
            // context now: by the time the task fires (delay ms later) the request is long gone, so
            // without this the log lines for a failing batch-set item name no request at all.
            Runnable item = MdcContext.wrapRunnable(() -> executeBatchSetItem(execution));
            new java.util.Timer().schedule(
                    new TimerTask() {
                        @Override
                        public void run() {
                            item.run();
                        }
                    },
                    delay
            );
        }
    }

    /**
     * Called in order to execute a batch set item
     */
    private void executeBatchSetItem(BatchSetExecution batchSetExecution) {
        String batchJobName = batchSetExecution.getBatchSetItem().getJobName();
        String batchFileName = batchSetExecution.getBatchSetItem().getFileName();
        if (StringUtils.isBlank(batchFileName)) {
            log.error("Batch set item " + batchJobName + " names no batch file");
            return;
        }
        // The file name comes from the manifest, so it must name a file inside the batch set folder
        Path file;
        try {
            file = ZipUtils.resolveEntry(batchSetExecution.getBatchSetSpec().getFolder(), batchFileName);
        } catch (IOException e) {
            log.error("Batch file for batch set item " + batchJobName + " is not inside the batch set: " + batchFileName);
            return;
        }
        if (!Files.isRegularFile(file)) {
            log.error("Batch file for batch set item " + batchJobName + " did not exist: " + file);
            return;
        }
        Map<String, Object> properties = batchSetExecution.getBatchSetItem().getProperties() != null
                ? batchSetExecution.getBatchSetItem().getProperties()
                : new HashMap<>();

        try (InputStream in = new FileInputStream(file.toFile())) {
            // The job keeps the bare file name: its data file lies directly in its own execution folder
            batchService.startBatchJobWithDataFile(
                    batchJobName,
                    in,
                    file.getFileName().toString(),
                    properties);
        } catch (Exception e) {
            log.error("Error executing batch set item " + batchJobName + " from file " + file);
        }
    }

    /***************************************/
    /** Helper classes                    **/
    /***************************************/

    /** Defines the batch set to execute **/
    public static class BatchSetSpecification implements Serializable {
        Path folder;
        List<BatchSetVo> batchSetItems = new ArrayList<>();

        public BatchSetSpecification() {
        }

        public BatchSetSpecification(Path folder, List<BatchSetVo> batchSetItems) {
            this.folder = folder;
            this.batchSetItems = batchSetItems;
        }

        public Path getFolder() {
            return folder;
        }

        public void setFolder(Path folder) {
            this.folder = folder;
        }

        public List<BatchSetVo> getBatchSetItems() {
            return batchSetItems;
        }

        public void setBatchSetItems(List<BatchSetVo> batchSetItems) {
            this.batchSetItems = batchSetItems;
        }
    }

    /** Used for collecting information needed to execute a batch set item **/
    public static class BatchSetExecution implements Serializable {
        BatchSetSpecification batchSetSpec;
        BatchSetVo batchSetItem;

        public BatchSetExecution() {
        }

        public BatchSetExecution(BatchSetSpecification batchSetSpec, BatchSetVo batchSetItem) {
            this.batchSetSpec = batchSetSpec;
            this.batchSetItem = batchSetItem;
        }

        public BatchSetSpecification getBatchSetSpec() {
            return batchSetSpec;
        }

        public void setBatchSetSpec(BatchSetSpecification batchSetSpec) {
            this.batchSetSpec = batchSetSpec;
        }

        public BatchSetVo getBatchSetItem() {
            return batchSetItem;
        }

        public void setBatchSetItem(BatchSetVo batchSetItem) {
            this.batchSetItem = batchSetItem;
        }
    }

}
