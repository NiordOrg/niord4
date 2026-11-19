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
 * filled after the start, so its mail could list too few messages. The scheduler postpones the trigger as it does one
 * whose run failed, to no later than {@link #getRetryBy()}, when the run makes its mail whatever the index holds.
 */
public class IncompleteIndexException extends Exception {

    private final Date retryBy;

    /**
     * @param retryBy the end of the wait, when the trigger is run again at the latest
     */
    public IncompleteIndexException(Date retryBy) {
        super("The text search index is being built again after a restart, so the message query's search may miss "
                + "messages; the trigger is run again by " + retryBy.toInstant() + ", and its mail is made then");
        this.retryBy = retryBy;
    }

    /** @return the end of the wait, when the trigger is run again at the latest */
    public Date getRetryBy() {
        return retryBy;
    }
}
