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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.model.geojson.FeatureCollectionVo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MapImageRegenerator}: one generation per file, the bounded background queue, the deferred deletion of
 * superseded images and the shutdown. The generator is a stand-in that writes a few bytes stamped with the
 * request's change time, as the real one stamps its image, and that can be held at a gate.
 */
class MapImageRegeneratorTest {

    private static final long UPDATED = 1_700_000_000_000L;

    @TempDir
    Path folder;

    private MapImageRegenerator regenerator;

    /** Generations per target file */
    private final Map<Path, AtomicInteger> calls = new ConcurrentHashMap<>();

    /** Targets whose generation waits for {@link #gate} */
    private final Set<Path> gated = ConcurrentHashMap.newKeySet();

    private final CountDownLatch gate = new CountDownLatch(1);

    /** Opened when a gated generation has started */
    private final CountDownLatch gatedStarted = new CountDownLatch(1);

    /** What the stand-in generator does besides counting; by default it writes the image */
    private final AtomicReference<Outcome> outcomeOfGenerate = new AtomicReference<>(Outcome.WRITE);

    private enum Outcome { WRITE, NOTHING_TO_DRAW, FAIL }

    @AfterEach
    void shutDown() {
        gate.countDown();
        if (regenerator != null) {
            regenerator.shutdown();
        }
    }

    private MapImageRegenerator regenerator(int threads, int queueCapacity) throws IOException {
        MessageMapImageGenerator generator = mock(MessageMapImageGenerator.class);
        when(generator.generate(any(), any())).thenAnswer(invocation -> {
            MapImageRequest request = invocation.getArgument(0);
            Path target = invocation.getArgument(1);
            calls.computeIfAbsent(target, t -> new AtomicInteger()).incrementAndGet();
            if (gated.contains(target)) {
                gatedStarted.countDown();
                if (!gate.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("the gate was never opened");
                }
            }
            switch (outcomeOfGenerate.get()) {
                case NOTHING_TO_DRAW:
                    return false;
                case FAIL:
                    throw new TileUnavailableException("no tiles");
                default:
                    Files.createDirectories(target.getParent());
                    Files.write(target, "png".getBytes(StandardCharsets.US_ASCII));
                    Files.setLastModifiedTime(target, FileTime.fromMillis(request.updatedMillis()));
                    return true;
            }
        });
        regenerator = new MapImageRegenerator(threads, queueCapacity);
        regenerator.generator = generator;
        return regenerator;
    }

    private static MapImageRequest request(long updatedMillis) {
        return new MapImageRequest(new FeatureCollectionVo[0], "NW", "COASTAL_WARNING", updatedMillis, 256, null);
    }

    private int callsFor(Path target) {
        AtomicInteger count = calls.get(target);
        return count == null ? 0 : count.get();
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting until " + what);
            }
            Thread.sleep(10);
        }
    }

    /** Waits until the thread blocks, which for these callers means waiting for a generation in flight */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        awaitTrue(thread.getName() + " waits", () -> thread.getState() == Thread.State.TIMED_WAITING
                || thread.getState() == Thread.State.WAITING);
    }

    /** Runs generateNow on a thread of its own, recording its outcome */
    private Thread generateNowInBackground(MapImageRequest request, Path target, AtomicReference<Object> outcome) {
        Thread thread = Thread.ofPlatform().name("caller-" + target.getFileName()).start(() -> {
            try {
                outcome.set(regenerator.generateNow(request, target));
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        return thread;
    }

    @Test
    void generateNowDrawsTheImageAndThenFindsItCurrent() throws Exception {
        regenerator(2, 8);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");

        assertTrue(regenerator.generateNow(request(UPDATED), target));
        assertTrue(regenerator.generateNow(request(UPDATED), target),
                "an image drawn for the message's version is current");

        assertEquals(1, callsFor(target), "the second call finds the file current and draws nothing");
        assertEquals(UPDATED, Files.getLastModifiedTime(target).toMillis());
        assertFalse(regenerator.inFlight(target));
    }

    @Test
    void generateNowReportsNothingToDraw() throws Exception {
        regenerator(2, 8);
        outcomeOfGenerate.set(Outcome.NOTHING_TO_DRAW);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");

        assertFalse(regenerator.generateNow(request(UPDATED), target));
        assertFalse(Files.exists(target));
        assertFalse(regenerator.inFlight(target));
    }

    @Test
    void generateNowPassesTheGeneratorsFailureOn() throws Exception {
        regenerator(2, 8);
        outcomeOfGenerate.set(Outcome.FAIL);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");

        assertThrows(TileUnavailableException.class, () -> regenerator.generateNow(request(UPDATED), target));
        assertFalse(regenerator.inFlight(target), "a failed generation leaves nothing in flight");
    }

    @Test
    void refreshDrawsOnceForRepeatedRequests() throws Exception {
        regenerator(2, 8);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");
        gated.add(target);

        regenerator.refresh(request(UPDATED), target);
        assertTrue(gatedStarted.await(10, TimeUnit.SECONDS));
        regenerator.refresh(request(UPDATED), target);
        regenerator.refresh(request(UPDATED), target);
        assertTrue(regenerator.inFlight(target));

        gate.countDown();
        awaitTrue("the generation ends", () -> !regenerator.inFlight(target));
        assertEquals(1, callsFor(target), "requests for a file in flight share its generation");
        assertTrue(Files.exists(target));
    }

    @Test
    void generateNowJoinsTheBackgroundGenerationOfTheSameFile() throws Exception {
        regenerator(2, 8);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");
        gated.add(target);

        regenerator.refresh(request(UPDATED), target);
        assertTrue(gatedStarted.await(10, TimeUnit.SECONDS));

        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread caller = generateNowInBackground(request(UPDATED), target, outcome);
        awaitBlocked(caller);
        gate.countDown();
        caller.join(10_000);

        assertEquals(Boolean.TRUE, outcome.get());
        assertEquals(1, callsFor(target), "the waiting request shares the background generation");
    }

    @Test
    void concurrentGenerateNowCallsShareOneGeneration() throws Exception {
        regenerator(2, 8);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");
        gated.add(target);

        AtomicReference<Object> first = new AtomicReference<>();
        AtomicReference<Object> second = new AtomicReference<>();
        Thread drawing = generateNowInBackground(request(UPDATED), target, first);
        assertTrue(gatedStarted.await(10, TimeUnit.SECONDS));
        Thread waiting = generateNowInBackground(request(UPDATED), target, second);
        awaitBlocked(waiting);
        gate.countDown();
        drawing.join(10_000);
        waiting.join(10_000);

        assertEquals(Boolean.TRUE, first.get());
        assertEquals(Boolean.TRUE, second.get());
        assertEquals(1, callsFor(target));
    }

    @Test
    void generateNowRunsAQueuedGenerationItselfInsteadOfWaitingBehindTheQueue() throws Exception {
        regenerator(1, 8);
        Path busy = folder.resolve("a").resolve("map_256_aaaaaaaaaaaa.png");
        Path queued = folder.resolve("b").resolve("map_256_aaaaaaaaaaaa.png");
        gated.add(busy);

        regenerator.refresh(request(UPDATED), busy);
        assertTrue(gatedStarted.await(10, TimeUnit.SECONDS), "the only thread is busy");
        regenerator.refresh(request(UPDATED), queued);
        assertTrue(regenerator.inFlight(queued));

        // The only thread is still held at the gate, so this returns only because it draws the image itself
        assertTrue(regenerator.generateNow(request(UPDATED), queued));
        assertEquals(1, callsFor(queued));
        assertFalse(regenerator.inFlight(queued));

        gate.countDown();
        awaitTrue("the busy generation ends", () -> !regenerator.inFlight(busy));
        Thread.sleep(100);
        assertEquals(1, callsFor(queued), "the queued task finds its generation done and draws nothing");
    }

    @Test
    void aGenerationForAnEarlierVersionOfTheMessageIsNotShared() throws Exception {
        regenerator(2, 8);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");
        gated.add(target);

        regenerator.refresh(request(UPDATED), target);
        assertTrue(gatedStarted.await(10, TimeUnit.SECONDS));

        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread caller = generateNowInBackground(request(UPDATED + 60_000), target, outcome);
        awaitBlocked(caller);
        gated.remove(target);
        gate.countDown();
        caller.join(10_000);

        assertEquals(Boolean.TRUE, outcome.get());
        assertEquals(2, callsFor(target), "the edited message is drawn again after the earlier generation");
        assertEquals(UPDATED + 60_000, Files.getLastModifiedTime(target).toMillis());
    }

    /**
     * A generation that hangs holds no request longer than the join timeout: neither one for the same version of
     * the message, which waits for its outcome, nor one for a newer version, which waits for it to end before it
     * draws — and which used to wait for it again and again. Both fail, and the endpoint answers with its
     * placeholder.
     */
    @Test
    void aHangingGenerationHoldsNoRequestLongerThanTheJoinTimeout() throws Exception {
        regenerator(2, 8);
        regenerator.joinTimeout = Duration.ofMillis(300);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");
        gated.add(target);

        regenerator.refresh(request(UPDATED), target);
        assertTrue(gatedStarted.await(10, TimeUnit.SECONDS));

        for (long updated : new long[] { UPDATED, UPDATED + 60_000 }) {
            long started = System.nanoTime();
            IOException timedOut = assertThrows(IOException.class,
                    () -> regenerator.generateNow(request(updated), target));
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(timedOut.getMessage().contains("was not made within 300 ms"), timedOut.getMessage());
            assertTrue(waited >= 250 && waited < 5_000, "the request waited " + waited + " ms");
        }
        assertEquals(1, callsFor(target), "nothing more is drawn while the hanging generation holds the file");
    }

    @Test
    void aFullQueueSkipsTheBackgroundGeneration() throws Exception {
        regenerator(1, 1);
        Path running = folder.resolve("a").resolve("map_256_aaaaaaaaaaaa.png");
        Path queued = folder.resolve("b").resolve("map_256_aaaaaaaaaaaa.png");
        Path skipped = folder.resolve("c").resolve("map_256_aaaaaaaaaaaa.png");
        gated.add(running);

        regenerator.refresh(request(UPDATED), running);
        assertTrue(gatedStarted.await(10, TimeUnit.SECONDS));
        regenerator.refresh(request(UPDATED), queued);
        regenerator.refresh(request(UPDATED), skipped);

        assertTrue(regenerator.inFlight(queued));
        assertFalse(regenerator.inFlight(skipped), "the queue is full, so the generation is skipped");

        gate.countDown();
        awaitTrue("the queue drains", () -> !regenerator.inFlight(running) && !regenerator.inFlight(queued));
        assertEquals(1, callsFor(queued));
        assertEquals(0, callsFor(skipped));

        // The next request asks again
        assertTrue(regenerator.generateNow(request(UPDATED), skipped));
        assertEquals(1, callsFor(skipped));
    }

    @Test
    void theImagesAGenerationSupersedesAreDeletedAfterTheDelay() throws Exception {
        regenerator(2, 8);
        regenerator.cleanupDelay = Duration.ofMillis(50);
        Path unversioned = Files.write(folder.resolve("map_256.png"), new byte[]{1});
        Path otherVersion = Files.write(folder.resolve("map_256_bbbbbbbbbbbb.png"), new byte[]{1});
        Path otherSize = Files.write(folder.resolve("map_512.png"), new byte[]{1});
        Path customThumbnail = Files.write(folder.resolve("custom_thumb_256.png"), new byte[]{1});
        Path attachment = Files.write(folder.resolve("map_256_notes.png"), new byte[]{1});
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");

        assertTrue(regenerator.generateNow(request(UPDATED), target));

        awaitTrue("the superseded images are deleted",
                () -> !Files.exists(unversioned) && !Files.exists(otherVersion));
        assertTrue(Files.exists(target));
        assertTrue(Files.exists(otherSize), "an image of another size is not superseded");
        assertTrue(Files.exists(customThumbnail), "a custom thumbnail is not a generated image");
        assertTrue(Files.exists(attachment), "a file that only looks similar is left alone");
    }

    @Test
    void nothingIsDeletedBeforeTheDelay() throws Exception {
        regenerator(2, 8);
        Path unversioned = Files.write(folder.resolve("map_256.png"), new byte[]{1});
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");

        assertTrue(regenerator.generateNow(request(UPDATED), target));

        assertTrue(Files.exists(unversioned),
                "a browser may still follow a redirect to the superseded image for a while");
        assertEquals(Duration.ofMinutes(16), regenerator.cleanupDelay);
    }

    @Test
    void aSupersededImageIsKeptWhenItsSuccessorIsGone() throws Exception {
        regenerator(2, 8);
        Path unversioned = Files.write(folder.resolve("map_256.png"), new byte[]{1});

        regenerator.deleteSuperseded(folder.resolve("map_256_aaaaaaaaaaaa.png"));

        assertTrue(Files.exists(unversioned));
    }

    /**
     * A background generation waits while map image requests are served, as when a list view loads a page of
     * images, and starts when the last of them ends
     */
    @Test
    void aBackgroundGenerationWaitsUntilNoMapImageRequestIsServed() throws Exception {
        regenerator(2, 8);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");

        regenerator.requestStarted();
        regenerator.requestStarted();
        regenerator.refresh(request(UPDATED), target);
        Thread.sleep(300);
        assertEquals(0, callsFor(target), "nothing is drawn while requests are served");
        assertTrue(regenerator.inFlight(target));

        regenerator.requestEnded();
        Thread.sleep(200);
        assertEquals(0, callsFor(target), "one request is still served");

        regenerator.requestEnded();
        awaitTrue("the generation ends", () -> callsFor(target) == 1 && !regenerator.inFlight(target));
        assertTrue(Files.exists(target));
    }

    /** Requests that never stop keep a background generation waiting only up to the yield limit */
    @Test
    void aBackgroundGenerationWaitsNoLongerThanTheYieldLimit() throws Exception {
        regenerator(2, 8);
        regenerator.yieldLimit = Duration.ofMillis(300);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");

        regenerator.requestStarted();
        try {
            long started = System.nanoTime();
            regenerator.refresh(request(UPDATED), target);
            awaitTrue("the generation ends", () -> callsFor(target) == 1 && !regenerator.inFlight(target));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(millis >= 250, "drawn after " + millis + " ms, before the yield limit");
        } finally {
            regenerator.requestEnded();
        }
        assertEquals(Duration.ofSeconds(2), Duration.ofMillis(MapImageRegenerator.YIELD_LIMIT_MILLIS));
    }

    /** A request that waits for its image draws a waiting background generation of it at once, itself */
    @Test
    void generateNowDoesNotWaitForTheYieldOfTheBackgroundGeneration() throws Exception {
        regenerator(2, 8);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");

        regenerator.requestStarted();
        try {
            regenerator.refresh(request(UPDATED), target);
            long started = System.nanoTime();
            assertTrue(regenerator.generateNow(request(UPDATED + 60_000), target));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000,
                    "the waiting request drew the image itself");
        } finally {
            regenerator.requestEnded();
        }
        assertEquals(1, callsFor(target), "the background generation it took over draws nothing more");
        assertEquals(UPDATED + 60_000, Files.getLastModifiedTime(target).toMillis());
    }

    /** A background generation still waiting at the shutdown is abandoned, not drawn */
    @Test
    void aGenerationWaitingForTheRequestsIsAbandonedAtTheShutdown() throws Exception {
        regenerator(2, 8);
        Path target = folder.resolve("map_256_aaaaaaaaaaaa.png");

        regenerator.requestStarted();
        regenerator.refresh(request(UPDATED), target);
        regenerator.shutdown();
        regenerator.requestEnded();

        Thread.sleep(200);
        assertFalse(regenerator.inFlight(target));
        assertEquals(0, callsFor(target));
    }

    @Test
    void shutdownLeavesQueuedGenerationsToTheirCallersAndRefusesNewOnes() throws Exception {
        regenerator(1, 8);
        Path running = folder.resolve("a").resolve("map_256_aaaaaaaaaaaa.png");
        Path queued = folder.resolve("b").resolve("map_256_aaaaaaaaaaaa.png");
        Path later = folder.resolve("c").resolve("map_256_aaaaaaaaaaaa.png");
        gated.add(running);

        regenerator.refresh(request(UPDATED), running);
        assertTrue(gatedStarted.await(10, TimeUnit.SECONDS));
        regenerator.refresh(request(UPDATED), queued);

        regenerator.shutdown();

        assertFalse(regenerator.inFlight(queued), "a generation that never started is abandoned");
        regenerator.refresh(request(UPDATED), later);
        assertFalse(regenerator.inFlight(later), "a shut-down pool takes no background generation");
        assertEquals(0, callsFor(queued));
        assertEquals(0, callsFor(later));

        // A request that waits for its image still gets it, drawn on its own thread
        assertTrue(regenerator.generateNow(request(UPDATED), later));
        assertEquals(1, callsFor(later));
    }
}
