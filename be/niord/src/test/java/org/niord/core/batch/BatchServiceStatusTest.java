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
package org.niord.core.batch;

import jakarta.batch.operations.JobOperator;
import jakarta.batch.operations.NoSuchJobException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.vo.BatchStatusVo;
import org.niord.core.batch.vo.BatchTypeVo;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BatchService#getStatus()}: a job's card counts its instances even when the engine cannot answer its running
 * executions. JBeret keeps its job definitions in a soft-reference cache, and once it has dropped a job's,
 * {@code getRunningExecutions} throws {@link NoSuchJobException} for a job with no running execution, while
 * {@code getJobInstanceCount} still answers the instances it holds. The cache cannot be steered from an integration
 * test, so the engine is a Mockito double here, and so are the job names' query.
 */
class BatchServiceStatusTest {

    @Test
    void theInstanceCountIsReadWhenTheRunningExecutionsAreNot() {
        EntityManager em = mock(EntityManager.class);
        Query jobNames = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(jobNames);
        when(jobNames.getResultList()).thenReturn(List.of("repo-import"));
        JobOperator jobOperator = mock(JobOperator.class);
        when(jobOperator.getRunningExecutions("repo-import")).thenThrow(new NoSuchJobException("repo-import"));
        when(jobOperator.getJobInstanceCount("repo-import")).thenReturn(3);

        BatchService batchService = new BatchService();
        ReflectionTestUtils.setField(batchService, "em", em);
        batchService.jobOperator = jobOperator;

        BatchStatusVo status = batchService.getStatus();

        assertEquals(1, status.getTypes().size());
        BatchTypeVo type = status.getTypes().get(0);
        assertEquals("repo-import", type.getName());
        assertEquals(3, type.getInstanceCount(), "the card counts the job's instances");
        assertEquals(0, type.getRunningExecutions());
        assertEquals(0, status.getRunningExecutions());
    }
}
