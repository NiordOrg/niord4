/*
 * Copyright 2026 Danish Emergency Management Agency.
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
package org.niord.core.publication.series;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Runs a unit of publications work in its own committed transaction ({@code REQUIRES_NEW}).
 * <p>
 * This is where the upstream {@code QuarkusTransaction.requiringNew()} calls land. A unit
 * that opens its own transaction does so because it must not inherit the caller's -- the
 * legacy import suppresses the ambient one entirely and writes the whole archive through
 * a transaction of its own, and a publish must not lose its work to a caller's rollback.
 * <p>
 * This must be a separate bean: Spring's {@code @Transactional} is proxy-based, so a
 * {@code REQUIRES_NEW} method invoked on {@code this} from within the calling class would
 * silently join the caller's transaction instead of starting its own -- which is exactly
 * the failure the callers here are guarding against, and it would be invisible until an
 * estate-scale write failed on the way out. Same reason, and the same shape, as
 * org.niord.secom.service.SecomTxRunner, which this is modelled on.
 */
@Service
public class PublicationTxRunner {

    /** The legacy import's budget, in seconds: its transaction's timeout (see {@link #callWithImportBudget}) */
    public static final int IMPORT_TIMEOUT_SECONDS = 1800;

    /** Runs the work in its own transaction, committed when this method returns */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T call(Supplier<T> work) {
        return work.get();
    }

    /** Runs the work in its own transaction, committed when this method returns */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void run(Runnable work) {
        work.run();
    }

    /**
     * The legacy import's budget, kept explicit.
     *
     * There is no ambient transaction timeout in this application -- nothing sets
     * {@code spring.transaction.default-timeout} -- so this ceiling is not raising
     * anything today. It is stated anyway because the import is genuinely long
     * (measured at 784s against the previous deployment; on PostgreSQL timed once,
     * on a workstation, well inside this ceiling, and never on a cluster), so a
     * global default introduced later must not silently cut it short. 30 minutes
     * is far more than it needs, and the cost of a generous ceiling is nothing at
     * all.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = IMPORT_TIMEOUT_SECONDS)
    public <T> T callWithImportBudget(Supplier<T> work) {
        return work.get();
    }
}
