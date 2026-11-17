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
package org.niord.secom.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Runs a unit of SECOM work in its own committed transaction ({@code REQUIRES_NEW}).
 * <p>
 * The reconciliation sweep processes each message/dataset through this runner so a failure
 * aborts only that unit (a constraint the unit's write breaks, a failure to sign or publish the
 * new version), and the sweep goes on with the next. The dataset-create race against the JMS
 * status listener (SECOM1) no longer happens: every writer that can create a message's dataset
 * takes the message's dataset lock first ({@code S124DatasetService.lockByMessageUid}), so the
 * writers take turns. On PostgreSQL a constraint violation aborts the <em>whole</em> transaction
 * with no savepoint recovery, so recovering per unit requires a separate transaction; see the
 * USR2 precedent in {@link org.niord.core.user.UserCreationService}.
 * <p>
 * This must be a separate bean: Spring's {@code @Transactional} is proxy-based, so a
 * {@code REQUIRES_NEW} method invoked on {@code this} from within the calling class would
 * silently join the caller's transaction instead of starting its own.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
class SecomTxRunner {

    /** Runs the work in its own transaction, committed when this method returns */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T call(Supplier<T> work) {
        return work.get();
    }
}
