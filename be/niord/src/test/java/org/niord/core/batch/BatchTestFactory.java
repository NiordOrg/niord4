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

import jakarta.batch.runtime.BatchStatus;
import org.niord.core.batch.vo.BatchExecutionVo;

import java.util.Calendar;
import java.util.Date;

/**
 * Small builder helpers shared by the framework-neutral batch unit tests, so the individual test
 * classes stay focused on behaviour rather than object construction.
 */
final class BatchTestFactory {

    private BatchTestFactory() {
    }

    /** Builds a detached {@link BatchData} with the given coordinates and a fixed creation date. */
    static BatchData batchData(String jobName, long jobNo, Date created) {
        BatchData job = new BatchData();
        job.setJobName(jobName);
        job.setJobNo(jobNo);
        job.setCreated(created);
        return job;
    }

    /** A {@link Date} pinned to a specific calendar day at noon (avoids DST / midnight edge cases). */
    static Date dateOf(int year, int monthZeroBased, int day) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, monthZeroBased, day, 12, 0, 0);
        return cal.getTime();
    }

    /** Builds a {@link BatchExecutionVo} with the given status and start time. */
    static BatchExecutionVo execution(BatchStatus status, Date startTime) {
        BatchExecutionVo e = new BatchExecutionVo();
        e.setBatchStatus(status);
        e.setStartTime(startTime);
        return e;
    }
}
