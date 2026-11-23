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
package org.niord.web.wms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.settings.SettingChangedEvent;
import org.niord.web.wms.WmsTileCache.Source;
import org.niord.web.wms.WmsTileCache.Tile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WmsTileCache} on a temporary folder, with a clock the test moves and a volume whose free
 * space the test sets: how long a tile is served, what happens when the provider fails, that one tile is fetched
 * once, how the folder is kept under its cap, and the line the deployment's dashboard reads.
 */
class WmsTileCacheTest {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final long START = Instant.parse("2026-11-12T08:00:00Z").toEpochMilli();
    private static final long PLENTY = 10L * 1024 * 1024 * 1024;

    @TempDir
    Path folder;

    private final MovableClock clock = new MovableClock(START);
    private final AtomicLong freeSpace = new AtomicLong(PLENTY);

    /** A cache that serves a tile for 7 days and may take the given number of MB */
    private WmsTileCache cache(int maxMb) {
        return new WmsTileCache(folder, clock, freeSpace::get, 7, maxMb);
    }

    private static WmsTileKey key(int x) {
        return new WmsTileKey("wms.example.com", "0a1b2c3d", 8, x, 79);
    }

    /** A provider that answers every fetch with the given bytes and counts the fetches */
    private static final class Provider implements Callable<byte[]> {
        final AtomicInteger fetches = new AtomicInteger();
        volatile byte[] answer;
        volatile Exception failure;

        Provider(byte[] answer) {
            this.answer = answer;
        }

        @Override
        public byte[] call() throws Exception {
            fetches.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private static byte[] png(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    private List<Path> filesOnDisk() throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    // ------------------------------------------------------------------ freshness

    @Test
    void aTileIsFetchedOnceAndServedFromTheCacheWhileItIsFresh() throws Exception {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));

        Tile first = cache.get(key(136), provider);
        clock.advance(6 * DAY + 23 * 60 * 60 * 1000L);
        Tile second = cache.get(key(136), provider);

        assertEquals(Source.MISS, first.source());
        assertEquals(START, first.fetchedAt());
        assertEquals(Source.HIT, second.source());
        assertEquals(START, second.fetchedAt(), "a hit says when the tile came from the provider");
        assertArrayEquals(png(300), second.bytes());
        assertEquals(1, provider.fetches.get(), "the provider was asked once");
        assertEquals(List.of(folder.resolve(Path.of("wms.example.com", "0a1b2c3d", "8", "136", "79.png"))),
                filesOnDisk(), "one file, at the tile's place, and no temporary file left");
    }

    @Test
    void aTileOlderThanItsDaysIsFetchedAgain() throws Exception {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        cache.get(key(136), provider);

        clock.advance(7 * DAY);
        provider.answer = png(500);
        Tile refreshed = cache.get(key(136), provider);

        assertEquals(Source.MISS, refreshed.source());
        assertEquals(START + 7 * DAY, refreshed.fetchedAt());
        assertArrayEquals(png(500), refreshed.bytes());
        assertEquals(2, provider.fetches.get());
        assertArrayEquals(png(500), Files.readAllBytes(filesOnDisk().get(0)), "the file holds the new tile");
        assertEquals(Source.HIT, cache.get(key(136), provider).source(), "and is fresh again");
    }

    // ------------------------------------------------------------------ the provider fails

    @Test
    void anOlderCopyIsServedWhenTheProviderFailsUntilItIsThirtyDaysOld() {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        cache.get(key(136), provider);
        provider.failure = new IOException("connect timed out");

        clock.advance(8 * DAY);
        Tile stale = cache.get(key(136), provider);
        clock.advance(22 * DAY);
        Tile tooOld = cache.get(key(136), provider);

        assertEquals(Source.STALE, stale.source());
        assertEquals(START, stale.fetchedAt(), "the older copy keeps its own date");
        assertArrayEquals(png(300), stale.bytes());
        assertNull(tooOld, "a copy of thirty days is not served");
    }

    @Test
    void aTileWithNoCopyIsNotAnsweredWhenTheProviderFails() throws Exception {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        provider.failure = new IOException("HTTP 503");

        assertNull(cache.get(key(136), provider));
        assertEquals(List.of(), filesOnDisk(), "nothing is stored for a failed fetch");
    }

    @Test
    void threeFailuresInARowPauseTheProviderForTwoMinutes() {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        provider.failure = new IOException("connect timed out");

        for (int x = 0; x < 3; x++) {
            assertNull(cache.get(key(x), provider));
        }
        assertEquals(3, provider.fetches.get());

        // Paused: nothing reaches the provider, whichever tile is asked for
        clock.advance(119_000);
        assertNull(cache.get(key(3), provider));
        assertNull(cache.get(key(0), provider));
        assertEquals(3, provider.fetches.get(), "a paused provider is not asked");

        // The pause over, the next request asks; its failure pauses the provider at once
        clock.advance(1_000);
        assertNull(cache.get(key(4), provider));
        assertNull(cache.get(key(5), provider));
        assertEquals(4, provider.fetches.get(), "one request after the pause, then paused again");

        // And an answer ends it
        clock.advance(120_000);
        provider.failure = null;
        assertEquals(Source.MISS, cache.get(key(6), provider).source());
        assertEquals(Source.MISS, cache.get(key(7), provider).source());
        assertEquals(6, provider.fetches.get());
    }

    @Test
    void afterAPauseOneRequestTriesTheProviderAndTheOthersDoNotAsk() throws Exception {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        provider.failure = new IOException("connect timed out");
        for (int x = 0; x < 3; x++) {
            cache.get(key(x), provider);
        }
        clock.advance(WmsTileCache.PAUSE.toMillis());

        CountDownLatch trying = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Callable<byte[]> slowProvider = () -> {
            trying.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return png(300);
        };
        ExecutorService requests = Executors.newSingleThreadExecutor();
        try {
            Future<Tile> first = requests.submit(() -> cache.get(key(10), slowProvider));
            assertTrue(trying.await(10, TimeUnit.SECONDS), "the first request after the pause is at the provider");

            assertNull(cache.get(key(11), provider), "a second request does not ask while the first one tries");
            assertEquals(3, provider.fetches.get());

            release.countDown();
            assertEquals(Source.MISS, first.get(10, TimeUnit.SECONDS).source());
            provider.failure = null;
            assertEquals(Source.MISS, cache.get(key(11), provider).source(), "the provider answered: all ask again");
        } finally {
            release.countDown();
            requests.shutdownNow();
        }
    }

    @Test
    void aSuccessBetweenFailuresStartsTheCountAgain() {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));

        for (int round = 0; round < 3; round++) {
            provider.failure = new IOException("HTTP 500");
            assertNull(cache.get(key(round * 3), provider));
            assertNull(cache.get(key(round * 3 + 1), provider));
            provider.failure = null;
            assertNotNull(cache.get(key(round * 3 + 2), provider));
        }

        assertEquals(9, provider.fetches.get(), "two failures in a row never pause the provider");
    }

    @Test
    void anAnswerThatIsNoTileIsNoFailureOfTheProvider() throws Exception {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        provider.failure = new WmsTileCache.NotATileException("the provider's answer is not an image");

        for (int x = 0; x < 5; x++) {
            assertNull(cache.get(key(x), provider));
        }

        assertEquals(5, provider.fetches.get(), "the provider is asked every time: it is not paused");
        assertEquals(List.of(), filesOnDisk());
    }

    @Test
    void aPausedProviderStillLeavesTheOlderCopy() {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        cache.get(key(136), provider);
        clock.advance(8 * DAY);
        provider.failure = new IOException("connect timed out");
        for (int x = 0; x < 3; x++) {
            cache.get(key(x), provider);
        }
        int asked = provider.fetches.get();

        Tile stale = cache.get(key(136), provider);

        assertEquals(Source.STALE, stale.source());
        assertEquals(asked, provider.fetches.get(), "served without asking the paused provider");
    }

    /** Pauses the cache's provider: three failures in a row, on tiles from the given column on */
    private static void pause(WmsTileCache cache, Provider provider, int fromX) {
        provider.failure = new IOException("HTTP 401");
        for (int x = fromX; x < fromX + 3; x++) {
            assertNull(cache.get(key(x), provider));
        }
        provider.failure = null;
    }

    @Test
    void aSaveOfTheProviderOrItsTokenEndsThePause() {
        int fromX = 0;
        for (String setting : List.of("wmsProvider", "wmsPassword")) {
            WmsTileCache cache = cache(200);
            Provider provider = new Provider(png(300));
            pause(cache, provider, fromX);
            assertNull(cache.get(key(fromX + 3), provider), "precondition: paused");
            assertEquals(3, provider.fetches.get());

            // The servlet's field holds the saved value; here, or on another node, whose poll found the save
            cache.settingChanged(new SettingChangedEvent(setting, fromX > 0));

            assertEquals(Source.MISS, cache.get(key(fromX + 3), provider).source(),
                    "the next request asks the provider after a save of " + setting);
            assertEquals(4, provider.fetches.get());
            fromX += 10;
        }
    }

    @Test
    void aSaveOfAnotherSettingLeavesThePause() {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        pause(cache, provider, 0);

        for (String setting : List.of("wmsLayers", "wmsCacheDays", "wmsProtected", "mailSender")) {
            cache.settingChanged(new SettingChangedEvent(setting, true));
        }

        assertNull(cache.get(key(3), provider));
        assertEquals(3, provider.fetches.get(), "still paused: the provider and its access are as they were");
    }

    @Test
    void aSaveOfTheProviderStartsTheCountOfFailuresAgain() {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        provider.failure = new IOException("HTTP 401");
        assertNull(cache.get(key(0), provider));
        assertNull(cache.get(key(1), provider));

        cache.settingChanged(new SettingChangedEvent("wmsPassword", true));
        assertNull(cache.get(key(2), provider));
        assertNull(cache.get(key(3), provider));
        assertNull(cache.get(key(4), provider));

        assertEquals(5, provider.fetches.get(), "the old token's two failures do not count towards a pause");
    }

    // ------------------------------------------------------------------ one fetch per tile

    @Test
    void aSecondRequestForATileBeingFetchedWaitsForThatFetch() throws Exception {
        WmsTileCache cache = cache(200);
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        Callable<byte[]> slowProvider = () -> {
            fetches.incrementAndGet();
            fetching.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return png(300);
        };

        ExecutorService requests = Executors.newFixedThreadPool(2);
        try {
            Future<Tile> first = requests.submit(() -> cache.get(key(136), slowProvider));
            assertTrue(fetching.await(10, TimeUnit.SECONDS), "the first request is at the provider");
            Thread[] second = new Thread[1];
            Future<Tile> waiting = requests.submit(() -> {
                second[0] = Thread.currentThread();
                return cache.get(key(136), slowProvider);
            });
            awaitWaiting(second);
            release.countDown();

            assertEquals(Source.MISS, first.get(10, TimeUnit.SECONDS).source());
            Tile joined = waiting.get(10, TimeUnit.SECONDS);
            assertEquals(Source.MISS, joined.source(), "the waiting request gets the tile the first one fetched");
            assertArrayEquals(png(300), joined.bytes());
            assertEquals(1, fetches.get(), "one fetch for the two requests");
            assertEquals("WMS tile cache: bytes=300 charged=16384 files=1 cap=209715200"
                    + " hits=0 misses=2 stale=0 failed=0 bypassed=0 evicted=0", cache.line(),
                    "both are answered with the tile fetched just now: one fetch, two misses");
        } finally {
            release.countDown();
            requests.shutdownNow();
        }
    }

    /** Waits until the thread, once it has started, is parked waiting for the other request's fetch */
    private static void awaitWaiting(Thread[] thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Thread t = thread[0];
            if (t != null && (t.getState() == Thread.State.TIMED_WAITING || t.getState() == Thread.State.WAITING)) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the second request never waited for the first one's fetch");
    }

    // ------------------------------------------------------------------ the bound

    @Test
    void aSmallTileCountsSixteenKilobytesTowardsTheCap() {
        assertEquals(16 * 1024, WmsTileCache.charge(1));
        assertEquals(16 * 1024, WmsTileCache.charge(16 * 1024));
        assertEquals(16 * 1024 + 1, WmsTileCache.charge(16 * 1024 + 1));
        assertEquals(40_000, WmsTileCache.charge(40_000));
    }

    @Test
    void aWritePastTheCapRemovesTheOldestTilesDownToNineTenths() throws Exception {
        // 1 MB holds 64 tiles of a few hundred bytes, at 16 KB each
        WmsTileCache cache = cache(1);
        Provider provider = new Provider(png(300));
        for (int x = 0; x < 64; x++) {
            cache.get(key(x), provider);
            clock.advance(1_000);
        }
        assertEquals(64, filesOnDisk().size(), "at the cap, not past it: nothing is removed");

        cache.get(key(64), provider);

        // 65 tiles are 1,064,960 charged bytes; nine tenths of the cap is 943,718, which 57 tiles fit under
        List<String> columns = filesOnDisk().stream().map(p -> p.getParent().getFileName().toString()).toList();
        assertEquals(57, columns.size());
        for (int x = 0; x < 8; x++) {
            assertFalse(columns.contains(Integer.toString(x)), "the oldest tiles go first: column " + x);
        }
        assertTrue(columns.contains("8") && columns.contains("64"), "the newer tiles stay");
        assertEquals("WMS tile cache: bytes=17100 charged=933888 files=57 cap=1048576"
                + " hits=0 misses=65 stale=0 failed=0 bypassed=0 evicted=8", cache.line());
    }

    @Test
    void aLargeTileCountsItsOwnSize() throws Exception {
        WmsTileCache cache = cache(1);
        Provider provider = new Provider(png(400_000));

        cache.get(key(0), provider);
        clock.advance(1_000);
        cache.get(key(1), provider);
        clock.advance(1_000);
        cache.get(key(2), provider);

        // Three tiles are 1,200,000 bytes, over the cap of 1,048,576: the oldest goes, and 800,000 is under 943,718
        assertEquals(2, filesOnDisk().size());
        assertEquals("WMS tile cache: bytes=800000 charged=800000 files=2 cap=1048576"
                + " hits=0 misses=3 stale=0 failed=0 bypassed=0 evicted=1", cache.line());
    }

    @Test
    void aTileFetchedAgainIsCountedOnce() throws Exception {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        cache.get(key(136), provider);
        clock.advance(7 * DAY);
        provider.answer = png(20_000);

        cache.get(key(136), provider);

        assertEquals("WMS tile cache: bytes=20000 charged=20000 files=1 cap=209715200"
                + " hits=0 misses=2 stale=0 failed=0 bypassed=0 evicted=0", cache.line());
    }

    @Test
    void nothingIsWrittenWhileTheVolumeIsNearlyFull() throws Exception {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        freeSpace.set(WmsTileCache.MIN_FREE_BYTES - 1);

        Tile first = cache.get(key(136), provider);
        Tile second = cache.get(key(136), provider);

        assertEquals(Source.MISS, first.source(), "the tile is still answered with");
        assertEquals(Source.MISS, second.source());
        assertEquals(2, provider.fetches.get(), "and fetched again, since it could not be kept");
        assertEquals(List.of(), filesOnDisk());
        try (Stream<Path> made = Files.list(folder)) {
            assertEquals(List.of(), made.toList(), "not even a folder is made on a volume that is nearly full");
        }

        freeSpace.set(WmsTileCache.MIN_FREE_BYTES);
        cache.get(key(136), provider);
        assertEquals(1, filesOnDisk().size(), "with room again, the tile is kept");
    }

    @Test
    void theNightlySweepDeletesOldTilesAndAbandonedTemporaryFiles() throws Exception {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        cache.get(key(1), provider);
        clock.advance(29 * DAY);
        cache.get(key(2), provider);
        Path old = filesOnDisk().get(0);
        Path abandoned = Files.write(old.resolveSibling("79.png.123.tmp"), png(10));
        Files.setLastModifiedTime(abandoned, FileTime.fromMillis(clock.millis() - 2 * 60 * 60 * 1000L));
        Path beingWritten = Files.write(old.resolveSibling("79.png.456.tmp"), png(10));
        Files.setLastModifiedTime(beingWritten, FileTime.fromMillis(clock.millis()));
        Path stranger = Files.write(folder.resolve("README.txt"), png(10));

        clock.advance(DAY);
        Files.setLastModifiedTime(beingWritten, FileTime.fromMillis(clock.millis() - 60_000));
        cache.sweepNightly();

        assertFalse(Files.exists(old), "a tile fetched thirty days ago is deleted");
        assertFalse(Files.exists(abandoned), "a temporary file hours old was left by an interrupted write");
        assertTrue(Files.exists(beingWritten), "a temporary file a minute old may still be written");
        assertTrue(Files.exists(stranger), "a file that is no tile is left alone");
        assertEquals("WMS tile cache: bytes=300 charged=16384 files=1 cap=209715200"
                + " hits=0 misses=2 stale=0 failed=0 bypassed=0 evicted=0", cache.line(),
                "age is no eviction: evicted counts what the cap removed");
    }

    @Test
    void aTileIsKeptWhileItIsFreshWhenItsDaysAreMoreThanThirty() throws Exception {
        WmsTileCache cache = new WmsTileCache(folder, clock, freeSpace::get, 60, 200);
        Provider provider = new Provider(png(300));
        cache.get(key(136), provider);

        clock.advance(40 * DAY);
        cache.sweepNightly();
        Tile kept = cache.get(key(136), provider);

        assertEquals(Source.HIT, kept.source(), "a tile of forty days is fresh for sixty, and no sweep deletes it");
        assertEquals(1, provider.fetches.get());

        clock.advance(20 * DAY);
        cache.sweepNightly();
        assertEquals(List.of(), filesOnDisk(), "at sixty days it is deleted");
    }

    @Test
    void theNightlySweepRemovesTheFoldersThatHaveStoodEmptyForAnHour() throws Exception {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        cache.get(key(1), provider);
        Path gone = filesOnDisk().get(0).getParent();
        clock.advance(31 * DAY);
        cache.get(key(2), provider);
        Path kept = filesOnDisk().stream().filter(f -> !f.startsWith(gone)).findFirst().orElseThrow().getParent();
        Path fresh = Files.createDirectories(kept.resolveSibling("99"));

        // A folder's own date is the file system's, not the test's clock: each night, date the folders as two
        // hours old by that clock, the one a write has just made as new
        for (int night = 0; night < 3; night++) {
            try (Stream<Path> walk = Files.walk(folder)) {
                for (Path dir : walk.filter(Files::isDirectory).toList()) {
                    Files.setLastModifiedTime(dir, FileTime.fromMillis(clock.millis() - 2 * 60 * 60 * 1000L));
                }
            }
            Files.setLastModifiedTime(fresh, FileTime.fromMillis(clock.millis()));
            cache.sweepNightly();
        }

        assertFalse(Files.exists(gone), "the folder of a tile that was deleted for its age");
        assertTrue(Files.exists(kept.resolve("79.png")), "a folder with a tile stays, with the tile");
        assertTrue(Files.exists(fresh), "an empty folder a write has just made stays for its tile");
        assertTrue(Files.isDirectory(folder), "and the cache's own folder stays");
    }

    @Test
    void aSweepForTheCapLeavesEmptyFoldersToTheNightlyOne() throws Exception {
        WmsTileCache cache = cache(200);
        Path empty = Files.createDirectories(folder.resolve("wms.example.com").resolve("0a1b2c3d").resolve("8").resolve("7"));
        Files.setLastModifiedTime(empty, FileTime.fromMillis(clock.millis() - 2 * 60 * 60 * 1000L));

        cache.sweep(false);

        assertTrue(Files.exists(empty));
    }

    @Test
    void aCacheStartedOnAFolderWithTilesCountsThem() throws Exception {
        Provider provider = new Provider(png(300));
        WmsTileCache before = cache(200);
        before.get(key(1), provider);
        before.get(key(2), provider);

        WmsTileCache restarted = cache(200);

        assertEquals("WMS tile cache: bytes=600 charged=32768 files=2 cap=209715200"
                + " hits=0 misses=0 stale=0 failed=0 bypassed=0 evicted=0", restarted.line());
        assertEquals(Source.HIT, restarted.get(key(1), provider).source(), "and serves them");
    }

    @Test
    void theFirstWriteAfterAStartLeavesTheCountAndTheCapToASweepOffItsThread() throws Exception {
        // 70 tiles from before the start, more than the 64 that a cap of 1 MB holds
        Provider provider = new Provider(png(300));
        WmsTileCache before = cache(200);
        for (int x = 0; x < 70; x++) {
            before.get(key(x), provider);
            clock.advance(1_000);
        }
        List<Runnable> sweeps = new ArrayList<>();
        WmsTileCache restarted = new WmsTileCache(folder, clock, freeSpace::get, 7, 1, sweeps::add);

        Tile first = restarted.get(key(70), provider);
        restarted.get(key(71), provider);

        assertEquals(Source.MISS, first.source());
        assertEquals(72, filesOnDisk().size(), "both tiles are written, and nothing is removed on their threads");
        assertEquals(1, sweeps.size(), "the first write hands one sweep off, which counts the folder");

        sweeps.get(0).run();

        // 72 tiles at 16 KB each; nine tenths of the cap fits 57, so the 15 oldest go
        List<String> columns = filesOnDisk().stream().map(p -> p.getParent().getFileName().toString()).toList();
        assertEquals(57, columns.size());
        assertFalse(columns.contains("14"), "the oldest tiles go first");
        assertTrue(columns.contains("15") && columns.contains("71"), "the newer tiles stay");
        assertEquals("WMS tile cache: bytes=17100 charged=933888 files=57 cap=1048576"
                + " hits=0 misses=2 stale=0 failed=0 bypassed=0 evicted=15", restarted.line());
    }

    // ------------------------------------------------------------------ the line and the switch

    @Test
    void theLineCountsWhatWasAnsweredSinceTheLineBefore() throws Exception {
        WmsTileCache cache = cache(200);
        Provider provider = new Provider(png(300));
        cache.get(key(1), provider);                       // miss
        cache.get(key(1), provider);                       // hit
        cache.get(key(1), provider);                       // hit
        cache.bypassed();
        clock.advance(8 * DAY);
        provider.failure = new IOException("HTTP 502");
        cache.get(key(1), provider);                       // stale
        cache.get(key(2), provider);                       // failed

        assertEquals("WMS tile cache: bytes=300 charged=16384 files=1 cap=209715200"
                + " hits=2 misses=1 stale=1 failed=1 bypassed=1 evicted=0", cache.line());
        assertEquals("WMS tile cache: bytes=300 charged=16384 files=1 cap=209715200"
                + " hits=0 misses=0 stale=0 failed=0 bypassed=0 evicted=0", cache.line(),
                "the next line starts from zero; the folder's size stays");
    }

    @Test
    void theHourlyLineOfAnEmptyCacheReadsZero() throws Exception {
        assertEquals("WMS tile cache: bytes=0 charged=0 files=0 cap=209715200"
                + " hits=0 misses=0 stale=0 failed=0 bypassed=0 evicted=0", cache(200).line());
        assertEquals(List.of(), filesOnDisk(), "counting an empty cache creates nothing");
    }

    @Test
    void theCacheIsOnWhenItHasDaysAndSpace() {
        assertTrue(new WmsTileCache(folder, clock, freeSpace::get, 7, 200).enabled());
        assertFalse(new WmsTileCache(folder, clock, freeSpace::get, 0, 200).enabled(), "0 days: the default, off");
        assertFalse(new WmsTileCache(folder, clock, freeSpace::get, -1, 200).enabled());
        assertFalse(new WmsTileCache(folder, clock, freeSpace::get, 7, 0).enabled(), "no space to cache in");

        WmsTileCache unset = new WmsTileCache(folder, clock, freeSpace::get, 7, 200);
        unset.wmsCacheDays = null;
        assertFalse(unset.enabled(), "a setting that resolves to nothing switches the cache off");
    }

    /** A clock the test moves */
    private static final class MovableClock extends Clock {
        private final AtomicLong millis;

        MovableClock(long millis) {
            this.millis = new AtomicLong(millis);
        }

        void advance(long by) {
            millis.addAndGet(by);
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
