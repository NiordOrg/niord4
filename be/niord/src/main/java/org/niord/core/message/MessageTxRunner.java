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
package org.niord.core.message;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs one unit of message work in a transaction of its own ({@code REQUIRES_NEW}), committed when the unit returns
 * and rolled back when it throws, whether the exception is checked or not.
 * <p>
 * The unattended runs use it to change one message at a time: the publication and expiry sweeps of
 * {@link ScheduledMessageService} run each message's status change as a unit, so a message that cannot be changed
 * rolls back alone, with its status, number, history entry and promulgation changes, and every other message of the
 * run is committed and broadcast. The number is taken inside the unit's transaction
 * ({@code SequenceService.takeNextValue}), so a unit that rolls back gives it back to its series, and the next
 * publication in the series takes it. The hourly firing-exercise run of
 * {@code FiringExerciseService} reads each schedule as a unit, and creates an area's new exercise and cancels the one
 * it replaces as one unit, so a failed replacement leaves the current exercise in force. The
 * broadcast of a status change is sent after the commit of the unit's own transaction.
 * <p>
 * A checked exception rolls the unit back too ({@code rollbackFor = Exception.class}), and reaches the caller
 * unchanged. A method the unit calls that joins its transaction need not roll back for checked exceptions itself.
 * <p>
 * This must be a bean of its own: Spring's {@code @Transactional} is proxy-based, so a {@code REQUIRES_NEW} method
 * called on {@code this} from the calling class would join the caller's transaction instead of starting its own. The
 * same reason, and the same shape, as {@code PublicationTxRunner} and {@code SecomTxRunner}.
 */
@Service
public class MessageTxRunner {

    /**
     * A unit of work that may throw a checked exception.
     *
     * @param <T> the result of the unit
     */
    @FunctionalInterface
    public interface Work<T> {

        /**
         * Does the work.
         *
         * @return the result
         * @throws Exception when the work fails; the unit's transaction is then rolled back
         */
        T call() throws Exception;
    }

    /**
     * Runs the work in a transaction of its own, committed when the work returns and rolled back when it throws.
     *
     * @param work the work
     * @param <T>  the result of the work
     * @return the result of the work
     * @throws Exception what the work threw, after its transaction was rolled back
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public <T> T call(Work<T> work) throws Exception {
        return work.call();
    }
}
