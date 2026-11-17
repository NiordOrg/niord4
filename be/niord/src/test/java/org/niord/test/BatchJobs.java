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
package org.niord.test;

import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;

/**
 * Tiny, framework-neutral helper for driving JSR-352 batch jobs to completion in integration tests.
 *
 * <p>Niord starts its import batch jobs asynchronously through
 * {@link org.niord.core.batch.BatchService} (which delegates to a {@code JobOperator}). The operator
 * returns an execution id immediately while the reader/processor/writer chunk runs on a separate
 * thread. To assert the <em>terminal</em> outcome of a job — and the database state it produced —
 * tests must poll the operator until the execution reaches a terminal {@link BatchStatus}.
 *
 * <p>The helper is intentionally expressed against the standard {@link JobOperator} /
 * {@link BatchStatus} JSR-352 API (not any Quarkus-specific type), so it remains valid if the
 * underlying batch runtime changes during the later Spring migration.
 */
public final class BatchJobs {

    private BatchJobs() {
    }

    /** The four terminal JSR-352 batch statuses. */
    private static boolean isTerminal(BatchStatus s) {
        return s == BatchStatus.COMPLETED
                || s == BatchStatus.FAILED
                || s == BatchStatus.STOPPED
                || s == BatchStatus.ABANDONED;
    }

    /**
     * Polls the given execution until it reaches a terminal status or the timeout elapses.
     *
     * @param operator    the batch job operator (e.g. the injected {@code QuarkusJobOperator})
     * @param executionId the execution id returned by {@code BatchService.startBatchJob...}
     * @param timeoutMs   the maximum time to wait, in milliseconds
     * @return the terminal {@link BatchStatus}, or the last observed status if the timeout elapsed
     */
    public static BatchStatus awaitCompletion(JobOperator operator, long executionId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        BatchStatus status = operator.getJobExecution(executionId).getBatchStatus();
        while (!isTerminal(status) && System.currentTimeMillis() < deadline) {
            Thread.sleep(150);
            status = operator.getJobExecution(executionId).getBatchStatus();
        }
        return status;
    }

    /**
     * Convenience overload with a generous default 60-second timeout — comfortably longer than any
     * single-item import chunk takes against the test containers.
     */
    public static BatchStatus awaitCompletion(JobOperator operator, long executionId)
            throws InterruptedException {
        return awaitCompletion(operator, executionId, 60_000L);
    }
}
