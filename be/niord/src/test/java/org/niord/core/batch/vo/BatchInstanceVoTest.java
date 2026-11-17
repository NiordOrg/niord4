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
package org.niord.core.batch.vo;

import jakarta.batch.runtime.BatchStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for {@link BatchInstanceVo#updateExecutions()}: the ordering and the
 * action-flag refinement applied across the executions of a single batch instance.
 *
 * <p>Rules under test:
 * <ul>
 *   <li>executions are sorted most-recent-first by start time; null start times sort last</li>
 *   <li>each execution's individual flags are computed ({@link BatchExecutionVo#updateFlags()})</li>
 *   <li>only the latest (first, post-sort) execution may remain restartable</li>
 *   <li>if any execution is still running (stoppable), none of them is abandonable</li>
 * </ul>
 */
public class BatchInstanceVoTest {

    private static Date date(int year, int monthZeroBased, int day) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, monthZeroBased, day, 12, 0, 0);
        return cal.getTime();
    }

    private static BatchExecutionVo exec(BatchStatus status, Date start) {
        BatchExecutionVo e = new BatchExecutionVo();
        e.setBatchStatus(status);
        e.setStartTime(start);
        return e;
    }

    private static BatchInstanceVo instanceWith(BatchExecutionVo... executions) {
        BatchInstanceVo instance = new BatchInstanceVo();
        instance.setExecutions(new java.util.ArrayList<>(Arrays.asList(executions)));
        return instance;
    }

    // ------------------------------------------------------------------------------------------- //
    // Ordering
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void sortsExecutionsMostRecentFirst() {
        BatchExecutionVo oldest = exec(BatchStatus.COMPLETED, date(2020, Calendar.JANUARY, 1));
        BatchExecutionVo middle = exec(BatchStatus.COMPLETED, date(2020, Calendar.JUNE, 1));
        BatchExecutionVo newest = exec(BatchStatus.COMPLETED, date(2020, Calendar.DECEMBER, 1));

        BatchInstanceVo instance = instanceWith(oldest, newest, middle);
        instance.updateExecutions();

        List<BatchExecutionVo> sorted = instance.getExecutions();
        assertSame(newest, sorted.get(0));
        assertSame(middle, sorted.get(1));
        assertSame(oldest, sorted.get(2));
    }

    @Test
    public void executionsWithNullStartTimeSortLast() {
        BatchExecutionVo started = exec(BatchStatus.COMPLETED, date(2020, Calendar.MARCH, 1));
        BatchExecutionVo notStarted = exec(BatchStatus.STARTING, null);

        BatchInstanceVo instance = instanceWith(notStarted, started);
        instance.updateExecutions();

        assertSame(started, instance.getExecutions().get(0), "execution with a start time comes first");
        assertSame(notStarted, instance.getExecutions().get(1), "null start time sorts last");
    }

    @Test
    public void emptyExecutionsListIsHandledGracefully() {
        BatchInstanceVo instance = instanceWith();
        instance.updateExecutions();
        assertTrue(instance.getExecutions().isEmpty());
    }

    // ------------------------------------------------------------------------------------------- //
    // Only the latest execution stays restartable
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void onlyLatestFailedExecutionRemainsRestartable() {
        // Both executions are FAILED, so each is individually restartable; after updateExecutions only
        // the most recent one keeps that flag.
        BatchExecutionVo older = exec(BatchStatus.FAILED, date(2020, Calendar.JANUARY, 1));
        BatchExecutionVo newer = exec(BatchStatus.FAILED, date(2020, Calendar.FEBRUARY, 1));

        BatchInstanceVo instance = instanceWith(older, newer);
        instance.updateExecutions();

        assertSame(newer, instance.getExecutions().get(0));
        assertTrue(instance.getExecutions().get(0).isRestartable(), "latest execution stays restartable");
        assertFalse(instance.getExecutions().get(1).isRestartable(), "older execution is no longer restartable");
    }

    @Test
    public void singleFailedExecutionIsRestartableAndAbandonable() {
        BatchExecutionVo only = exec(BatchStatus.FAILED, date(2020, Calendar.JANUARY, 1));
        BatchInstanceVo instance = instanceWith(only);

        instance.updateExecutions();

        assertTrue(only.isRestartable());
        assertTrue(only.isAbandonable());
        assertFalse(only.isStoppable());
    }

    // ------------------------------------------------------------------------------------------- //
    // A running execution blocks abandonment of all executions
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void runningExecutionBlocksAbandonmentOfAll() {
        // The newer execution is STARTED (stoppable); the older one FAILED would normally be abandonable.
        // Because a running execution exists, no execution may be abandoned.
        BatchExecutionVo failed = exec(BatchStatus.FAILED, date(2020, Calendar.JANUARY, 1));
        BatchExecutionVo running = exec(BatchStatus.STARTED, date(2020, Calendar.FEBRUARY, 1));

        BatchInstanceVo instance = instanceWith(failed, running);
        instance.updateExecutions();

        assertTrue(running.isStoppable(), "the running execution is stoppable");
        assertFalse(running.isAbandonable(), "running execution is not abandonable");
        assertFalse(failed.isAbandonable(), "older failed execution is also not abandonable while one runs");
    }

    @Test
    public void abandonableWhenNoExecutionIsRunning() {
        BatchExecutionVo failed = exec(BatchStatus.FAILED, date(2020, Calendar.FEBRUARY, 1));
        BatchExecutionVo completed = exec(BatchStatus.COMPLETED, date(2020, Calendar.JANUARY, 1));

        BatchInstanceVo instance = instanceWith(failed, completed);
        instance.updateExecutions();

        assertSame(failed, instance.getExecutions().get(0));
        assertTrue(instance.getExecutions().get(0).isAbandonable(),
                "with no running execution the latest failed one stays abandonable");
    }
}
