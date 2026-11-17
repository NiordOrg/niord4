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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for {@link BatchExecutionVo#updateFlags()}: which of the
 * stoppable / restartable / abandonable action flags are enabled for each {@link BatchStatus}.
 *
 * <p>Rules under test:
 * <ul>
 *   <li>stoppable    = STARTED or STARTING</li>
 *   <li>restartable  = FAILED or STOPPED</li>
 *   <li>abandonable  = restartable (FAILED or STOPPED)</li>
 * </ul>
 */
public class BatchExecutionVoTest {

    private static BatchExecutionVo flagsFor(BatchStatus status) {
        BatchExecutionVo e = new BatchExecutionVo();
        e.setBatchStatus(status);
        e.updateFlags();
        return e;
    }

    @Test
    public void startedIsStoppableOnly() {
        BatchExecutionVo e = flagsFor(BatchStatus.STARTED);
        assertTrue(e.isStoppable());
        assertFalse(e.isRestartable());
        assertFalse(e.isAbandonable());
    }

    @Test
    public void startingIsStoppableOnly() {
        BatchExecutionVo e = flagsFor(BatchStatus.STARTING);
        assertTrue(e.isStoppable());
        assertFalse(e.isRestartable());
        assertFalse(e.isAbandonable());
    }

    @Test
    public void failedIsRestartableAndAbandonable() {
        BatchExecutionVo e = flagsFor(BatchStatus.FAILED);
        assertFalse(e.isStoppable());
        assertTrue(e.isRestartable());
        assertTrue(e.isAbandonable());
    }

    @Test
    public void stoppedIsRestartableAndAbandonable() {
        BatchExecutionVo e = flagsFor(BatchStatus.STOPPED);
        assertFalse(e.isStoppable());
        assertTrue(e.isRestartable());
        assertTrue(e.isAbandonable());
    }

    @Test
    public void completedHasNoActions() {
        BatchExecutionVo e = flagsFor(BatchStatus.COMPLETED);
        assertFalse(e.isStoppable());
        assertFalse(e.isRestartable());
        assertFalse(e.isAbandonable());
    }

    @Test
    public void abandonedHasNoActions() {
        BatchExecutionVo e = flagsFor(BatchStatus.ABANDONED);
        assertFalse(e.isStoppable());
        assertFalse(e.isRestartable());
        assertFalse(e.isAbandonable());
    }

    @Test
    public void stoppingHasNoActions() {
        // STOPPING is a transitional state matched by none of the branches.
        BatchExecutionVo e = flagsFor(BatchStatus.STOPPING);
        assertFalse(e.isStoppable());
        assertFalse(e.isRestartable());
        assertFalse(e.isAbandonable());
    }

    @Test
    public void updateFlagsRecomputesFromStaleFlags() {
        // Flags must reflect the current status even if previously set to the opposite value.
        BatchExecutionVo e = new BatchExecutionVo();
        e.setStoppable(true);
        e.setRestartable(true);
        e.setAbandonable(true);
        e.setBatchStatus(BatchStatus.COMPLETED);

        e.updateFlags();

        assertFalse(e.isStoppable());
        assertFalse(e.isRestartable());
        assertFalse(e.isAbandonable());
    }
}
