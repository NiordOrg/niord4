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
package org.niord.web.map;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.niord.core.util.MdcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes the map images of messages through {@link MessageMapImageGenerator}: on the calling thread when the caller
 * waits for the image ({@link #generateNow}), or on a small pool of its own when an image drawn with an earlier
 * portrayal is served meanwhile ({@link #refresh}).
 *
 * <h2>One generation per file</h2>
 * The administration app's list views, a PDF of many messages and the languages of one publication issue ask for
 * the same image at the same time. A caller that finds a generation of the same file in flight waits for it
 * instead of drawing the image again; one that finds it still queued runs it itself, so no caller waits behind
 * the queue. Only a generation for an earlier version of the message is not shared: the caller lets it finish and
 * then draws the image again. A generation first looks at the file, and writes nothing when the file already
 * holds the image of the message's version.
 *
 * <h2>Background generation</h2>
 * {@link #refresh} queues a generation for {@value #THREADS} threads with room for {@value #QUEUE_CAPACITY}
 * waiting ones. When the queue is full the generation is skipped (logged at DEBUG): the older image goes on being
 * served, and the next request for it asks again. The work never runs on Spring's task executor ({@code @Async}),
 * whose queue is unbounded and shared with the PDF downloads.
 *
 * <p>A background generation starts when no map image request is being served ({@link #requestStarted()},
 * {@link #requestEnded()}), or at the latest {@value #YIELD_LIMIT_MILLIS} ms after it was queued. A list view that
 * asks for a page of images at once, each of which starts a background generation after a portrayal change, gets
 * its page first and the redrawn images after it; a PDF, whose engine asks for one image after another, lets each
 * generation start between two of its requests. A request that waits for its image never waits for this: it
 * draws a generation that has not started itself (see above).
 *
 * <h2>Superseded images</h2>
 * After a generation, the other generated images of the same size in the folder (see {@link MapImageFiles}) are
 * deleted {@value #CLEANUP_DELAY_MINUTES} minutes later: longer than a browser may go on following the endpoint's
 * redirect to one of them (10 minutes) plus the time it may cache the file itself (the repository's 5 minutes). A
 * deletion lost to a restart is made after the next generation of that image.
 *
 * <h2>Threads</h2>
 * Both executors belong to this bean and run platform daemon threads. Every task carries the MDC of the thread
 * that submitted it ({@link MdcContext#wrapRunnable}), so the log lines of a background generation name the
 * request that asked for it. Both are shut down in {@link #shutdown()}.
 */
@Component
public class MapImageRegenerator {

    /** The threads drawing images in the background */
    static final int THREADS = 2;

    /** The background generations that may wait for a thread; more are skipped */
    static final int QUEUE_CAPACITY = 256;

    /** How long a superseded image is kept after its successor was drawn */
    static final long CLEANUP_DELAY_MINUTES = 16;

    /**
     * How long a background generation waits for the map image requests being served to end, at most, counted from
     * when it was queued
     */
    static final long YIELD_LIMIT_MILLIS = 2_000;

    /**
     * How long a caller waits for the generations in flight, in all, before it fails and gets the placeholder. The
     * generator gives up on the tiles after 8 s, so this only bounds a generation that hangs.
     */
    static final Duration JOIN_TIMEOUT = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(MapImageRegenerator.class);

    @Inject
    volatile MessageMapImageGenerator generator;

    /** How long a superseded image is kept; a test shortens it */
    volatile Duration cleanupDelay = Duration.ofMinutes(CLEANUP_DELAY_MINUTES);

    /** How long a background generation waits for the map image requests to end, at most; a test changes it */
    volatile Duration yieldLimit = Duration.ofMillis(YIELD_LIMIT_MILLIS);

    /** How long a caller waits for the generations in flight, in all; a test shortens it */
    volatile Duration joinTimeout = JOIN_TIMEOUT;

    private final ThreadPoolExecutor pool;
    private final ScheduledThreadPoolExecutor cleaner;
    private final Map<Path, Job> inFlight = new ConcurrentHashMap<>();
    private final Set<Path> pendingCleanups = ConcurrentHashMap.newKeySet();

    /** The map image requests being served, and the monitor a background generation waits on for none */
    private final AtomicInteger serving = new AtomicInteger();
    private final Object noRequests = new Object();

    MapImageRegenerator() {
        this(THREADS, QUEUE_CAPACITY);
    }

    MapImageRegenerator(int threads, int queueCapacity) {
        // A fixed pool, as Executors.newFixedThreadPool, but with a bounded queue that refuses the excess, and
        // threads that end after a minute without work: the pool is busy only after a portrayal change.
        // Every task is wrapped with MdcContext where it is submitted.
        this.pool = new ThreadPoolExecutor(threads, threads, 1L, TimeUnit.MINUTES,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("map-image-", 0).daemon(true).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(true);
        this.cleaner = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("map-image-cleanup-", 0).daemon(true).factory());
        this.cleaner.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
        cleaner.shutdownNow();
        // A generation that will now never start is left to whoever waits for it
        inFlight.forEach((target, job) -> {
            if (job.claim(null) != null) {
                abandon(target, job);
            }
        });
        try {
            pool.awaitTermination(2, TimeUnit.SECONDS);
            cleaner.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Draws the image of the request into the target file on the calling thread, or waits for the generation of
     * the same file in flight.
     *
     * @param request the message's geometry, type and change time, and where the tiles come from
     * @param target  the image file
     * @return whether the file now holds the image; false when the message has nothing to draw
     * @throws IOException when the image cannot be made, or the generations in flight take longer than
     *                     {@link #JOIN_TIMEOUT} in all — a generation for an earlier version of the message that
     *                     hangs included, which would otherwise hold the file, and the caller, for ever
     */
    public boolean generateNow(MapImageRequest request, Path target) throws IOException {
        long deadline = System.nanoTime() + joinTimeout.toNanos();
        while (true) {
            Job candidate = new Job(request);
            Job job = inFlight.putIfAbsent(target, candidate);
            if (job == null) {
                job = candidate;
            }
            MapImageRequest claimed = job.claim(request);
            if (claimed != null) {
                run(job, claimed, target);
            } else if (job.updatedMillis() < request.updatedMillis()) {
                // In flight for an earlier version of the message: let it finish, then draw this one
                awaitEnd(job, target, deadline);
                continue;
            }
            Boolean generated = await(job, target, deadline);
            if (generated != null) {
                return generated;
            }
            // The generation was abandoned before it started: draw the image here
        }
    }

    /**
     * Queues the generation of the image in the background, unless one for the same file is in flight or the
     * queue is full. Returns at once.
     *
     * @param request the message's geometry, type and change time, and where the tiles come from
     * @param target  the image file
     */
    public void refresh(MapImageRequest request, Path target) {
        Job job = new Job(request);
        Job running = inFlight.putIfAbsent(target, job);
        if (running != null) {
            running.offer(request);
            log.debug("The map image {} is being made already", target);
            return;
        }
        long queued = System.nanoTime();
        try {
            pool.execute(MdcContext.wrapRunnable(() -> {
                try {
                    awaitNoRequests(queued);
                } catch (InterruptedException e) {
                    // Shutting down: the generation is left to whoever waits for it
                    Thread.currentThread().interrupt();
                    if (job.claim(null) != null) {
                        abandon(target, job);
                    }
                    return;
                }
                MapImageRequest claimed = job.claim(null);
                if (claimed != null) {
                    run(job, claimed, target);
                    logBackgroundFailure(job, target);
                }
            }));
        } catch (RejectedExecutionException e) {
            if (job.claim(null) != null) {
                abandon(target, job);
            }
            log.debug("The map image queue is full or shut down; {} is left to a later request", target);
        }
    }

    /**
     * Returns whether a generation of the file is queued or running.
     * @param target the image file
     * @return whether a generation of it is in flight
     */
    boolean inFlight(Path target) {
        return inFlight.containsKey(target);
    }

    /**
     * Counts a map image request that is being served, until {@link #requestEnded()}: while there is one, a
     * background generation waits (see the class comment).
     */
    public void requestStarted() {
        serving.incrementAndGet();
    }

    /** Ends a map image request counted by {@link #requestStarted()} */
    public void requestEnded() {
        if (serving.decrementAndGet() <= 0) {
            synchronized (noRequests) {
                noRequests.notifyAll();
            }
        }
    }

    /**
     * Waits while map image requests are being served, but at most until {@link #yieldLimit} after the
     * generation was queued
     */
    private void awaitNoRequests(long queuedNanos) throws InterruptedException {
        long deadline = queuedNanos + yieldLimit.toNanos();
        synchronized (noRequests) {
            while (serving.get() > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return;
                }
                TimeUnit.NANOSECONDS.timedWait(noRequests, left);
            }
        }
    }

    /** Draws the image, unless the file already holds it, and completes the job with the outcome */
    private void run(Job job, MapImageRequest request, Path target) {
        Boolean generated = null;
        Throwable failure = null;
        try {
            if (MapImageFiles.modifiedMillis(target) == request.updatedMillis()) {
                generated = true;
            } else {
                generated = generator.generate(request, target);
                if (generated) {
                    scheduleCleanup(target);
                }
            }
        } catch (Throwable t) {
            failure = t;
        } finally {
            inFlight.remove(target, job);
            if (failure != null) {
                job.result.completeExceptionally(failure);
            } else {
                job.result.complete(generated);
            }
        }
    }

    /** Leaves a job that never started to its waiters, who draw the image themselves */
    private void abandon(Path target, Job job) {
        inFlight.remove(target, job);
        job.result.complete(null);
    }

    private static void logBackgroundFailure(Job job, Path target) {
        job.result.exceptionally(failure -> {
            log.warn("Could not make the map image {} in the background: {}", target, failure.toString());
            return null;
        });
    }

    /**
     * Waits for the job's outcome, until the deadline.
     * @return whether the file holds the image, or null when the job was abandoned before it started
     */
    private Boolean await(Job job, Path target, long deadlineNanos) throws IOException {
        try {
            return job.result.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(cause);
        } catch (TimeoutException e) {
            throw notMadeInTime(target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for the map image " + target);
        }
    }

    /**
     * Waits for the job to end, whatever its outcome — which is not the caller's: the caller draws the image
     * itself next. A job that has not ended by the deadline fails the caller, as {@link #await} does: it still
     * holds the file, so waiting for it again would wait for ever.
     */
    private void awaitEnd(Job job, Path target, long deadlineNanos) throws IOException {
        try {
            job.result.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            // Failed: the caller draws the image itself
        } catch (TimeoutException e) {
            throw notMadeInTime(target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for the map image " + target);
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    private IOException notMadeInTime(Path target) {
        return new IOException("The map image " + target + " was not made within " + joinTimeout.toMillis()
                + " ms");
    }

    /** Deletes the images the target supersedes after the cleanup delay, once per target at a time */
    private void scheduleCleanup(Path target) {
        if (!pendingCleanups.add(target)) {
            return;
        }
        try {
            cleaner.schedule(MdcContext.wrapRunnable(() -> {
                pendingCleanups.remove(target);
                deleteSuperseded(target);
            }), cleanupDelay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            pendingCleanups.remove(target);
            log.debug("Shutting down: the images {} supersedes stay until its next generation", target);
        }
    }

    /** Deletes the other generated images of the target's size in its folder, while the target exists */
    void deleteSuperseded(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try {
            for (Path superseded : MapImageFiles.others(target)) {
                try {
                    if (Files.deleteIfExists(superseded)) {
                        log.debug("Deleted the superseded map image {}", superseded);
                    }
                } catch (IOException e) {
                    log.warn("Could not delete the superseded map image {}: {}", superseded, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("Could not list the map images next to {}: {}", target, e.toString());
        }
    }

    /**
     * One generation of one file. It is started once, by whichever thread claims it first: a pool thread, or a
     * caller that would otherwise wait for it in the queue. Until then a newer request for the same file replaces
     * the one it was created with.
     */
    private static final class Job {

        final CompletableFuture<Boolean> result = new CompletableFuture<>();

        private MapImageRequest request;
        private boolean started;

        Job(MapImageRequest request) {
            this.request = request;
        }

        /**
         * Claims the job for the calling thread, taking the given request first when it is newer.
         * @return the request to draw, or null when another thread has claimed the job
         */
        synchronized MapImageRequest claim(MapImageRequest newer) {
            if (started) {
                return null;
            }
            started = true;
            take(newer);
            return request;
        }

        /** Takes the given request when the job has not started and the request is newer */
        synchronized void offer(MapImageRequest newer) {
            if (!started) {
                take(newer);
            }
        }

        synchronized long updatedMillis() {
            return request.updatedMillis();
        }

        private void take(MapImageRequest newer) {
            if (newer != null && newer.updatedMillis() > request.updatedMillis()) {
                request = newer;
            }
        }
    }
}
