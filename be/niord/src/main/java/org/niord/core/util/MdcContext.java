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

import org.slf4j.MDC;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Carries the SLF4J {@link MDC} correlation context ({@code requestId}, {@code domain}) across
 * thread hand-offs, so a log line written on a worker thread still names the request or job it
 * belongs to. The keys are rendered on every log line by {@code logging.pattern.level} in
 * {@code config/niord-core-defaults.yml}.
 *
 * <p>Two hand-off shapes exist in Niord and they need different treatment:
 * <ul>
 *   <li><b>Inherit</b> — work submitted <em>from</em> a request or job thread ({@code @Async},
 *       {@link org.niord.core.repo.ThumbnailService}'s process pool, {@code ScheduledMailService}'s
 *       bounded SMTP pool, {@code TileFetcher}'s tile loads and disk cache writes,
 *       {@code MapImageRegenerator}'s background map images and its deferred deletion of superseded ones).
 *       The originating context already exists and must simply follow the task:
 *       {@link #wrap(Runnable)} / {@link #wrap(Callable)} / {@link #wrapAll(Collection)}.</li>
 *   <li><b>Originate</b> — work with no calling context at all ({@code @Scheduled} sweeps, and the
 *       start-up threads that prepare the map images: {@code PortrayalDefinition} reading the definition,
 *       {@code MessageMapImageGenerator} rasterising the symbols and drawing a trial image, {@code TileFetcher}
 *       building its HTTP client). There is nothing to inherit, so a fresh id is minted per execution:
 *       {@link #withFreshJobId(Runnable)}.</li>
 * </ul>
 *
 * <p>Every wrapper <em>restores</em> the worker thread's previous MDC in a {@code finally} rather
 * than clearing it. Pool threads are reused, so clearing would be correct only by accident and
 * leaking one task's context into the next task on the same thread is the failure this class exists
 * to prevent.
 *
 * <p>The {@code java.util.Timer} sites are covered the same way, by category rather than by
 * mechanism: {@code BatchSetService} schedules delayed batch-set items from a request thread and so
 * inherits ({@link #wrapRunnable}), while {@code MessageLuceneIndex} and {@code AtonDefaultsService}
 * schedule deferred start-up work and so originate ({@link #withFreshJobId}).
 *
 * <p>What is deliberately <em>not</em> covered: the standalone poll-loop executors in
 * {@code MailingListMessageListener}, {@code TwitterMessageListener} and
 * {@code SecomMessageStatusListener}, and JBeret's batch executor ({@code BatchConfig}). Those have
 * no calling context to inherit — each needs an id minted per received message or per job
 * execution, which is a separate change. See the comments at those sites.
 */
public final class MdcContext {

    /** MDC key for the request / job correlation id. Set by {@code RequestIdFilter} and this class. */
    public static final String REQUEST_ID = "requestId";

    /** MDC key for the Niord domain (Keycloak client). Set by {@code NiordDomainFilter}. */
    public static final String DOMAIN = "domain";

    private MdcContext() {
    }

    /** A correlation id for an incoming HTTP request that did not carry a usable one. */
    public static String newRequestId() {
        return shortId();
    }

    /**
     * A correlation id for background work that has no originating request. The {@code job-} prefix
     * makes background lines separable from request lines with a single grep.
     */
    public static String newJobId() {
        return "job-" + shortId();
    }

    /**
     * 12 hex characters (~2^48). Short enough to read and compare by eye in a local log, and wide
     * enough that a collision is not a realistic concern within any window someone would grep. Use a
     * full UUID instead if these ids ever start crossing into an external system.
     */
    private static String shortId() {
        // The dashes are stripped BEFORE truncating: the first 12 characters of a UUID's string form
        // are only 11 hex digits, because position 8 is a '-'.
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * Captures the calling thread's MDC now and replays it on whichever thread ends up running the
     * task. Call this on the <em>submitting</em> thread, not inside the task.
     *
     * <p>The {@code Runnable} form is {@link #wrapRunnable(Runnable)} rather than another
     * {@code wrap} overload: a lambda whose body is a method call satisfies both functional
     * interfaces, so {@code wrap(() -> doSomething())} would not compile against an overloaded pair.
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        return withContext(MDC.getCopyOfContextMap(), task);
    }

    /**
     * {@link #wrap(Callable)} for a {@link Runnable} — e.g. the body of a {@link java.util.TimerTask}
     * scheduled while serving a request. Separately named to avoid an ambiguous overload; see the
     * note on {@link #wrap(Callable)}.
     */
    public static Runnable wrapRunnable(Runnable task) {
        return withContext(MDC.getCopyOfContextMap(), task);
    }

    /**
     * {@link #wrap(Callable)} for a batch of tasks about to be handed to
     * {@link java.util.concurrent.ExecutorService#invokeAll(Collection)}. The context is captured
     * once, on the calling thread, and shared by every task — which is what is wanted: they are all
     * part of the same originating request or job.
     */
    public static <T> List<Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return tasks.stream()
                .map(task -> withContext(captured, task))
                .toList();
    }

    /**
     * Runs the task with a fresh {@link #newJobId() job id} in the MDC.
     *
     * <p>The id is minted when the task <em>runs</em>, not when it is wrapped, and that is not a
     * stylistic choice: {@code ThreadPoolTaskScheduler} decorates a {@code @Scheduled} task once,
     * at submission (see {@code decorateTaskIfNecessary}), so an id captured at wrap time would be
     * reused by every execution for the lifetime of the process — a correlation id that correlates
     * nothing, and one that still looks right in a log.
     */
    public static Runnable withFreshJobId(Runnable task) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            MDC.clear();
            MDC.put(REQUEST_ID, newJobId());
            try {
                task.run();
            } finally {
                apply(previous);
            }
        };
    }

    /** Installs {@code context} for the duration of the task, then restores the thread's own MDC. */
    public static Runnable withContext(Map<String, String> context, Runnable task) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            apply(context);
            try {
                task.run();
            } finally {
                apply(previous);
            }
        };
    }

    /** {@link #withContext(Map, Runnable)} for a {@link Callable}. */
    public static <T> Callable<T> withContext(Map<String, String> context, Callable<T> task) {
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            apply(context);
            try {
                return task.call();
            } finally {
                apply(previous);
            }
        };
    }

    /** Replaces the current thread's MDC wholesale; a null or empty context means "no context". */
    private static void apply(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
