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
package org.niord.core.conf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.util.MdcContext;
import org.niord.core.web.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskDecorator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CorrelationConfig#mdcTaskDecorator()} — the single {@link TaskDecorator}
 * that Spring Boot applies to <em>both</em> the {@code @Async} executor and the {@code @Scheduled}
 * scheduler, and which therefore has to tell the two apart at runtime.
 *
 * <p>The decorator has two failure modes that a naive implementation ships and that a happy-path
 * test would not notice:
 * <ul>
 *   <li><b>A job id minted at decoration time instead of at run time.</b>
 *       {@code ThreadPoolTaskScheduler} decorates a scheduled task once, when it is submitted, so
 *       such an id is reused by every execution for the lifetime of the process. The log would look
 *       correct and correlate nothing. Pinned by
 *       {@link #mintsADistinctJobIdForEveryRunOfTheSameDecoratedTask()}.</li>
 *   <li><b>Clearing rather than restoring the worker's MDC.</b> Pool threads are reused, so one
 *       task's context leaking into the next task on the same thread attributes log lines to the
 *       wrong request. Pinned by {@link #doesNotLeakContextOntoAReusedWorkerThread()} and
 *       {@link #restoresThePreviousContextEvenWhenTheTaskThrows()}.</li>
 * </ul>
 */
public class CorrelationConfigTest {

    private final TaskDecorator decorator = new CorrelationConfig().mdcTaskDecorator();

    @AfterEach
    public void clearMdc() {
        MDC.clear();
    }

    // ------------------------------------------------------------------------------------------------
    // Inherit: work submitted from a thread that already has a correlation context (@Async)
    // ------------------------------------------------------------------------------------------------

    @Test
    public void inheritsTheSubmittingThreadsCorrelationContext() {
        MDC.put(MdcContext.REQUEST_ID, "req-abc123");
        MDC.put(MdcContext.DOMAIN, "niord-client-nw");
        AtomicReference<String> seenId = new AtomicReference<>();
        AtomicReference<String> seenDomain = new AtomicReference<>();

        Runnable decorated = decorator.decorate(() -> {
            seenId.set(MDC.get(MdcContext.REQUEST_ID));
            seenDomain.set(MDC.get(MdcContext.DOMAIN));
        });
        MDC.clear();  // the submitting thread has since moved on; the capture already happened
        decorated.run();

        assertEquals("req-abc123", seenId.get(), "async work must stay attached to its originating request");
        assertEquals("niord-client-nw", seenDomain.get(), "the whole context follows, not just the id");
    }

    @Test
    public void leavesTheSubmittingThreadsOwnContextUntouched() {
        MDC.put(MdcContext.REQUEST_ID, "req-caller");

        decorator.decorate(() -> MDC.put(MdcContext.REQUEST_ID, "req-overwritten")).run();

        assertEquals("req-caller", MDC.get(MdcContext.REQUEST_ID),
                "running a decorated task must not corrupt the caller's own context");
    }

    // ------------------------------------------------------------------------------------------------
    // Originate: work with no calling context at all (@Scheduled)
    // ------------------------------------------------------------------------------------------------

    @Test
    public void mintsAJobIdWhenThereIsNoOriginatingRequest() {
        MDC.clear();
        AtomicReference<String> seen = new AtomicReference<>();

        decorator.decorate(() -> seen.set(MDC.get(MdcContext.REQUEST_ID))).run();

        assertTrue(seen.get() != null && seen.get().startsWith("job-"),
                "scheduled work gets its own id, prefixed so it is separable from request work: " + seen.get());
    }

    /**
     * The regression guard for the scheduler's decoration model: {@code ThreadPoolTaskScheduler}
     * decorates once at submission, so the id has to be created inside the returned Runnable. If it
     * were captured outside, every execution of a {@code @Scheduled} method for the whole life of
     * the process would share one id.
     */
    @Test
    public void mintsADistinctJobIdForEveryRunOfTheSameDecoratedTask() {
        MDC.clear();
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        Runnable decoratedOnce = decorator.decorate(() -> {
            String id = MDC.get(MdcContext.REQUEST_ID);
            if (!first.compareAndSet(null, id)) {
                second.set(id);
            }
        });

        decoratedOnce.run();
        decoratedOnce.run();

        assertTrue(first.get().startsWith("job-"));
        assertTrue(second.get().startsWith("job-"));
        assertNotEquals(first.get(), second.get(),
                "each execution of a scheduled task is a separate job and needs its own id");
    }

    // ------------------------------------------------------------------------------------------------
    // Cleanup on reused pool threads
    // ------------------------------------------------------------------------------------------------

    @Test
    public void doesNotLeakContextOntoAReusedWorkerThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Start from a known-empty MDC on the worker thread itself.
            pool.submit(MDC::clear).get();

            MDC.put(MdcContext.REQUEST_ID, "req-from-a-request");
            pool.submit(decorator.decorate(() -> { })).get();

            AtomicReference<String> leaked = new AtomicReference<>("not-run");
            pool.submit(() -> leaked.set(MDC.get(MdcContext.REQUEST_ID))).get();

            assertNull(leaked.get(),
                    "the next task on this thread must not inherit the previous task's request");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void restoresThePreviousContextEvenWhenTheTaskThrows() {
        MDC.put(MdcContext.REQUEST_ID, "req-caller");

        Runnable boom = decorator.decorate(() -> { throw new IllegalStateException("task failed"); });

        assertThrows(IllegalStateException.class, boom::run);
        assertEquals("req-caller", MDC.get(MdcContext.REQUEST_ID),
                "cleanup must happen on the failure path too");
    }

    @Test
    public void restoresAnEmptyContextRatherThanKeepingTheJobId() {
        MDC.clear();

        decorator.decorate(() -> { }).run();

        assertNull(MDC.get(MdcContext.REQUEST_ID),
                "a job id must not survive the job that owned it");
    }

    // ------------------------------------------------------------------------------------------------
    // Filter registration
    // ------------------------------------------------------------------------------------------------

    /**
     * The order matters twice over: ahead of every other Niord filter, so each one logs with an id,
     * and ahead of the Spring Security chain (registered at {@code -100}) so requests rejected with
     * 401/403 — the ones most worth tracing — carry an id in their logs and their response.
     */
    @Test
    public void registersTheRequestIdFilterAheadOfEveryOtherFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new CorrelationConfig().requestIdFilter();

        assertEquals(Ordered.HIGHEST_PRECEDENCE, registration.getOrder());
        assertTrue(registration.getUrlPatterns().contains("/*"), "must apply to every request");
        assertTrue(registration.getFilter() instanceof RequestIdFilter);
    }
}
