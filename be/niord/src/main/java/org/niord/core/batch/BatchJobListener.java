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

import org.jberet.runtime.AbstractStepExecution;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Handler;

import org.slf4j.Logger;

import jakarta.batch.api.listener.AbstractJobListener;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchRuntime;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.StepExecution;
import jakarta.batch.runtime.context.JobContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * A listener used for setting up and removing batch job log files.
 * The listener should be added to all Niord batch jobs.
 * <p>
 * Configure using:
 * <pre>
 *  &lt;listeners&gt;
 *      &lt;listener ref="batchJobListener"/&gt;
 *  &lt;/listeners&gt;
 * </pre>
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchJobListener")
public class BatchJobListener extends AbstractJobListener {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(BatchJobListener.class);

    /** The log file, in the job's folder, that says how a job that did not complete ended */
    static final String END_LOG = "BatchJobLog.txt";

    @Inject
    JobContext jobContext;

    @Inject
    BatchService batchService;

    /**
     * Fetch the BatchData from the job operator properties, if they exist
     * @param executionId the execution id
     * @return the BatchData from the job operator properties
     */
    private BatchData getBatchData(long executionId) {
        JobOperator jobOperator = BatchRuntime.getJobOperator();
        Properties properties = jobOperator.getParameters(executionId);
        return  properties != null
                ? (BatchData) properties.get(BatchService.BATCH_JOB_ENTITY)
                : null;
    }

    /** {@inheritDoc} */
    @Override
    public void beforeJob() throws Exception {
        BatchData job = getBatchData(jobContext.getExecutionId());

        // Create a map holding the logs
        Map<String, java.util.logging.Logger> logs = new HashMap<>();
        jobContext.setTransientUserData(logs);

        // When a job is started, the instanceId will be null and the job must be persisted
        // Restarts will have a well-defined instanceId and be persisted already
        if (job != null) {
            job.setInstanceId(jobContext.getInstanceId());
            job = batchService.saveBatchJob(job);
            log.info("Batch job started for " + job.getJobName() + " and instanceId " + job.getInstanceId());
        } else {
            job = batchService.findByInstanceId(jobContext.getInstanceId());
            log.info("Batch job re-started for " + job.getJobName() + " and instanceId " + job.getInstanceId());
        }
    }

    /** {@inheritDoc} */
    @Override
    public void afterJob() throws Exception {

        // A job that did not complete says how it ended, and why, in its own log: the logs of its reader, processor
        // and writer hold only what they write themselves, so the reason would otherwise be in the server log alone
        recordEnd();

        // Close all the batch logs - otherwise the will keep a lock on the log files.
        @SuppressWarnings("unchecked")
        Map<String, java.util.logging.Logger> logs = (Map<String, java.util.logging.Logger>)jobContext.getTransientUserData();
        if (logs != null && !logs.isEmpty()) {
            for (Map.Entry<String, java.util.logging.Logger> batchLog : logs.entrySet()) {
                try {
                    for (Handler handler : batchLog.getValue().getHandlers()) {
                        handler.close();
                    }

                } catch (Exception ignored) {
                }
                //log.debug("Closed log for batch item handler " + batchLog.getKey());
            }
        }
    }

    /**
     * Appends to {@value #END_LOG} in the job's folder, which the Batch Jobs page lists with the job's other logs, how
     * a job that did not complete ended, and the exception of each step that failed. Recording never fails the
     * listener: a failure to record goes to the server log.
     */
    private void recordEnd() {
        try {
            BatchStatus status = jobContext.getBatchStatus();
            if (status == BatchStatus.COMPLETED) {
                return;
            }
            BatchData job = batchService.findByInstanceId(jobContext.getInstanceId());
            if (job == null) {
                return;
            }
            StringBuilder text = new StringBuilder("The job ended " + status + " (exit status "
                    + jobContext.getExitStatus() + ")" + System.lineSeparator());
            List<StepExecution> steps = BatchRuntime.getJobOperator().getStepExecutions(jobContext.getExecutionId());
            for (StepExecution step : steps == null ? List.<StepExecution>of() : steps) {
                if (step instanceof AbstractStepExecution execution && execution.getException() != null) {
                    Exception e = execution.getException();
                    text.append("Step ").append(step.getStepName()).append(": ").append(e.getClass().getSimpleName())
                            .append(": ").append(e.getMessage()).append(System.lineSeparator());
                }
            }
            Path folder = batchService.computeBatchJobPath(job.computeBatchJobFolderPath());
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(END_LOG), text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("Could not record the end of batch job instance " + jobContext.getInstanceId(), e);
        }
    }

}
