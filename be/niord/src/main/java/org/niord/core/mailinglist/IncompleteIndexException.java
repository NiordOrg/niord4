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
package org.niord.core.mailinglist;

import java.util.Date;

/**
 * A scheduled trigger's run waits for the free-text index: its search was answered while the index was still being
 * filled after the start or after a change of its settings, so its mail could list too few messages. The scheduler
 * postpones the trigger to
 * {@link #getRetryAt()}, a few minutes on and never after {@link #getWaitUntil()}, when the run makes its mail
 * whatever the index holds. It is not a failure: the scheduler records it apart from the failed runs.
 */
public class IncompleteIndexException extends Exception {

    private final Date retryAt;

    private final Date waitUntil;

    /**
     * @param retryAt when the trigger is tried again
     * @param waitUntil the end of the wait, when the trigger's mail is made at the latest
     */
    public IncompleteIndexException(Date retryAt, Date waitUntil) {
        super("The text search index is being built again after a restart or a change of its settings, so the message "
                + "query's search may miss messages; the mail is made once the index is complete, by "
                + waitUntil.toInstant()
                + " at the latest");
        this.retryAt = retryAt;
        this.waitUntil = waitUntil;
    }

    /** @return when the trigger is tried again */
    public Date getRetryAt() {
        return retryAt;
    }

    /** @return the end of the wait, when the trigger's mail is made at the latest */
    public Date getWaitUntil() {
        return waitUntil;
    }
}
