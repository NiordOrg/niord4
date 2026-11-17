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
package org.niord.core.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MdcContext}, the helper that carries the correlation context onto the raw
 * {@code java.util.concurrent} pools Spring's {@code TaskDecorator} never sees — the thumbnail
 * process pool (submitted from a request thread) and the bounded SMTP pool in
 * {@code ScheduledMailService} (submitted from a scheduled sweep).
 */
public class MdcContextTest {

    private final AtomicReference<String> observedId = new AtomicReference<>();
    private final AtomicReference<String> observedDomain = new AtomicReference<>();

    @AfterEach
    public void clearMdc() {
        MDC.clear();
    }

    @Test
    public void wrappedCallableSeesTheSubmittingThreadsContextOnAnotherThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(MDC::clear).get();
            MDC.put(MdcContext.REQUEST_ID, "req-thumbnail");

            Callable<String> task = () -> MDC.get(MdcContext.REQUEST_ID);
            Future<String> result = pool.submit(MdcContext.wrap(task));

            assertEquals("req-thumbnail", result.get(),
                    "work done on a raw pool must stay attached to the request that asked for it");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void wrappedCallableRestoresTheWorkerThreadAfterwards() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(MDC::clear).get();
            MDC.put(MdcContext.REQUEST_ID, "req-first");

            pool.submit(MdcContext.wrap((Callable<String>) () -> "done")).get();

            String leaked = pool.submit(() -> MDC.get(MdcContext.REQUEST_ID)).get();
            assertNull(leaked, "the next task on this pooled thread must start clean");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void wrapAllGivesEveryTaskTheSameOriginatingContext() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            MDC.put(MdcContext.REQUEST_ID, "job-mail-sweep");

            List<Callable<String>> tasks = List.of(
                    () -> MDC.get(MdcContext.REQUEST_ID),
                    () -> MDC.get(MdcContext.REQUEST_ID),
                    () -> MDC.get(MdcContext.REQUEST_ID));

            for (Future<String> result : pool.invokeAll(MdcContext.wrapAll(tasks))) {
                assertEquals("job-mail-sweep", result.get(),
                        "every mail in the batch belongs to the sweep that scheduled it");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void wrappedRunnableRestoresTheContextWhenTheTaskThrows() {
        MDC.put(MdcContext.REQUEST_ID, "req-caller");

        // The task is typed explicitly: a lambda body that only throws fits both withContext
        // overloads, and javac resolves the ambiguity to Callable.
        Runnable task = () -> { throw new IllegalStateException("failed"); };
        Runnable boom = MdcContext.withContext(null, task);

        assertThrows(IllegalStateException.class, boom::run);
        assertEquals("req-caller", MDC.get(MdcContext.REQUEST_ID));
    }

    // ------------------------------------------------------------------------------------------------
    // wrapRunnable — the java.util.Timer sites (BatchSetService schedules delayed batch-set items
    // from the request thread; the task fires long after that request has returned)
    // ------------------------------------------------------------------------------------------------

    @Test
    public void wrapRunnableReplaysTheSchedulingThreadsContextOnATimerThread() throws Exception {
        MDC.put(MdcContext.REQUEST_ID, "req-batch-set");
        MDC.put(MdcContext.DOMAIN, "niord-client-nw");

        // Captured now, on the "request" thread ...
        Runnable body = MdcContext.wrapRunnable(() -> {
            observedId.set(MDC.get(MdcContext.REQUEST_ID));
            observedDomain.set(MDC.get(MdcContext.DOMAIN));
        });

        // ... and only run later, on a Timer thread, with the request long gone.
        MDC.clear();
        CountDownLatch fired = new CountDownLatch(1);
        Timer timer = new Timer();
        try {
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    body.run();
                    fired.countDown();
                }
            }, 1);
            assertTrue(fired.await(5, TimeUnit.SECONDS), "the timer task did not fire");
        } finally {
            timer.cancel();
        }

        assertEquals("req-batch-set", observedId.get(),
                "a delayed batch-set item must still name the request that scheduled it");
        assertEquals("niord-client-nw", observedDomain.get());
    }

    @Test
    public void wrapRunnableRestoresTheRunningThreadAfterwards() {
        MDC.put(MdcContext.REQUEST_ID, "req-outer");

        MdcContext.wrapRunnable(() -> MDC.put(MdcContext.REQUEST_ID, "req-inner")).run();

        assertEquals("req-outer", MDC.get(MdcContext.REQUEST_ID),
                "running the wrapped body must not corrupt the thread it runs on");
    }

    @Test
    public void withFreshJobIdGivesDeferredStartupWorkItsOwnId() {
        // MessageLuceneIndex and AtonDefaultsService schedule start-up work on a Timer with no
        // request to inherit from; each run gets its own job id rather than an empty req= field.
        MDC.clear();
        Runnable body = MdcContext.withFreshJobId(() -> observedId.set(MDC.get(MdcContext.REQUEST_ID)));

        body.run();
        String first = observedId.get();
        body.run();
        String second = observedId.get();

        assertTrue(first.startsWith("job-"), "expected a job id, got: " + first);
        assertTrue(second.startsWith("job-"));
        assertNotEquals(first, second, "each run is a separate job");
        assertNull(MDC.get(MdcContext.REQUEST_ID), "the job id must not outlive the job");
    }

    @Test
    public void generatedIdsAreDistinctAndJobIdsArePrefixed() {
        assertTrue(MdcContext.newJobId().startsWith("job-"));
        assertEquals(MdcContext.newRequestId().length(), 12);
        assertTrue(!MdcContext.newRequestId().equals(MdcContext.newRequestId()));
    }
}
