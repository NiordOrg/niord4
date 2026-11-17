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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.util.GlobalMercator;
import org.niord.test.http.HttpStub;
import org.niord.test.http.HttpStub.RecordedRequest;
import org.niord.test.http.HttpStub.StubResponse;
import org.niord.web.map.TileFetcher.Limits;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of {@link TileFetcher} against an {@link HttpStub} tile server that answers every
 * {@code /tiles/<z>/<x>/<y>.png} with a 256 x 256 PNG in a colour of its own ({@link #colourOf}), so the composed
 * background shows which tile landed where. The clock is the test's, so freshness and the circuit breaker's pause
 * are driven without waiting.
 */
public class TileFetcherTest {

    private static final String USER_AGENT = "Niord/4.0.0-TEST (+https://niord.example.org)";
    private static final Pattern TILE_PATH = Pattern.compile("/tiles/(\\d+)/(\\d+)/(\\d+)\\.png");
    private static final GlobalMercator MERCATOR = new GlobalMercator();
    private static final double ORIGIN_SHIFT = Math.PI * 6378137;

    @TempDir
    Path cacheDir;

    private HttpStub stub;
    private MutableClock clock;
    private final List<TileFetcher> fetchers = new ArrayList<>();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpStub.start();
        stub.respond(TileFetcherTest::tile);
        clock = new MutableClock(Instant.parse("2026-01-05T10:00:00Z"));
    }

    @AfterEach
    void stopAll() {
        fetchers.forEach(TileFetcher::shutdown);
        stub.close();
    }

    // ------------------------------------------------------------------------------------------------
    // Which tiles, and where they go
    // ------------------------------------------------------------------------------------------------

    /**
     * Copenhagen (55.6761 N, 12.5683 E) lies in OSM tile 10/547/320, three quarters across and just past halfway
     * down; a 256 px image centred there needs the 2 x 2 tiles 547-548 x 320-321 (XYZ rows count from the north),
     * and nothing else: no margin, no other zoom level.
     */
    @Test
    void requestsExactlyTheTilesThatIntersectTheImage() throws IOException {
        double lat = 55.6761, lon = 12.5683;
        int zoom = 10;
        // The slippy-map formula of the OSM wiki, independent of the code under test
        double n = 1 << zoom;
        double tileX = (lon + 180) / 360 * n;
        double tileY = (1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * n;
        assertEquals(547, (int) Math.floor(tileX));
        assertEquals(320, (int) Math.floor(tileY));
        Set<String> expected = new HashSet<>();
        for (int x = (int) Math.floor(tileX - 0.5); x <= (int) Math.floor(tileX + 0.5); x++) {
            for (int y = (int) Math.floor(tileY - 0.5); y <= (int) Math.floor(tileY + 0.5); y++) {
                expected.add("/tiles/10/" + x + "/" + y + ".png");
            }
        }
        assertEquals(Set.of("/tiles/10/547/320.png", "/tiles/10/548/320.png",
                "/tiles/10/547/321.png", "/tiles/10/548/321.png"), expected);

        double[] centre = MERCATOR.LatLonToMeters(lat, lon);
        BufferedImage image = fetcher().background(source(), centre[0], centre[1], zoom, 256, 256);

        assertEquals(256, image.getWidth());
        assertEquals(256, image.getHeight());
        assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
        assertEquals(expected, requestedPaths());
        assertEquals(4, stub.requestCount(), "each tile is requested once");
    }

    /** Centred on a tile corner, the four tiles fill the four quadrants, each at a whole-pixel offset. */
    @Test
    void placesEveryTileAtItsPixelOffset() throws IOException {
        double[] corner = northWestCornerOf(5, 17, 10);
        TileWindow window = TileWindow.centredOn(corner[0], corner[1], 5, 256, 256);

        BufferedImage image = fetcher().background(source(), window);

        assertEquals(128.0, window.imageX(corner[0]), 1e-6, "the centre is the image centre");
        assertEquals(128.0, window.imageY(corner[1]), 1e-6);
        assertPixel(image, 0, 0, colourOf(5, 16, 9));
        assertPixel(image, 127, 127, colourOf(5, 16, 9));
        assertPixel(image, 128, 0, colourOf(5, 17, 9));
        assertPixel(image, 255, 127, colourOf(5, 17, 9));
        assertPixel(image, 0, 128, colourOf(5, 16, 10));
        assertPixel(image, 128, 128, colourOf(5, 17, 10));
        assertPixel(image, 255, 255, colourOf(5, 17, 10));
    }

    /** At the antimeridian the columns wrap: the image shows the world's last column next to its first. */
    @Test
    void wrapsTileColumnsAtTheAntimeridian() throws IOException {
        BufferedImage image = fetcher().background(source(), ORIGIN_SHIFT, 0, 2, 256, 256);

        assertEquals(Set.of("/tiles/2/3/1.png", "/tiles/2/0/1.png", "/tiles/2/3/2.png", "/tiles/2/0/2.png"),
                requestedPaths());
        assertPixel(image, 0, 0, colourOf(2, 3, 1));
        assertPixel(image, 200, 0, colourOf(2, 0, 1));
        assertPixel(image, 200, 200, colourOf(2, 0, 2));
    }

    /** Beyond the poles there are no tiles: nothing is requested for those rows and they stay white. */
    @Test
    void leavesTheAreaBeyondThePolesWhite() throws IOException {
        double[] centre = MERCATOR.LatLonToMeters(85, 0);

        BufferedImage image = fetcher().background(source(), centre[0], centre[1], 0, 256, 256);

        assertEquals(Set.of("/tiles/0/0/0.png"), requestedPaths());
        assertPixel(image, 5, 5, Color.WHITE);
        assertPixel(image, 5, 127, Color.WHITE);
        assertPixel(image, 5, 128, colourOf(0, 0, 0));
    }

    @Test
    void rejectsAnImageSizeOrZoomOutsideTheSupportedRange() {
        TileFetcher fetcher = fetcher();
        assertThrows(IllegalArgumentException.class, () -> fetcher.background(source(), 0, 0, 5, 0, 256));
        assertThrows(IllegalArgumentException.class, () -> fetcher.background(source(), 0, 0, 5, 256, 4096));
        assertThrows(IllegalArgumentException.class, () -> fetcher.background(source(), 0, 0, -1, 256, 256));
        assertThrows(IllegalArgumentException.class, () -> fetcher.background(source(), Double.NaN, 0, 5, 256, 256));
        assertEquals(0, stub.requestCount());
    }

    // ------------------------------------------------------------------------------------------------
    // The caches
    // ------------------------------------------------------------------------------------------------

    /** A second image needs no request: from memory in the same process, from the disk cache after a restart. */
    @Test
    void servesRepeatedTilesFromMemoryAndFromTheDiskCache() throws IOException {
        double[] corner = northWestCornerOf(5, 17, 10);
        fetcher().background(source(), corner[0], corner[1], 5, 256, 256);
        assertEquals(4, stub.requestCount());

        TileFetcher sameProcess = fetchers.getFirst();
        sameProcess.background(source(), corner[0], corner[1], 5, 256, 256);
        assertEquals(4, stub.requestCount(), "memory hit: no request");

        BufferedImage afterRestart = fetcher().background(source(), corner[0], corner[1], 5, 256, 256);
        assertEquals(4, stub.requestCount(), "disk hit: no request");
        assertPixel(afterRestart, 0, 0, colourOf(5, 16, 9));
        assertPixel(afterRestart, 255, 255, colourOf(5, 17, 10));
    }

    /**
     * A fresh tile of the disk cache is read and decoded on the calling thread: only a tile that must come from the
     * host is handed to a virtual thread, where a file read now and then waits 100 ms or more
     */
    @Test
    void aFreshTileOfTheDiskCacheIsReadOnTheCallingThread() throws IOException {
        double[] corner = northWestCornerOf(5, 17, 10);
        TileFetcher first = fetcher();
        first.background(source(), corner[0], corner[1], 5, 256, 256);
        assertEquals(4, first.hostLoads(), "the four tiles come from the host, on virtual threads");

        TileFetcher afterRestart = fetcher();
        BufferedImage image = afterRestart.background(source(), corner[0], corner[1], 5, 256, 256);

        assertEquals(0, afterRestart.hostLoads(), "the disk cache is read without a virtual thread");
        assertEquals(4, stub.requestCount());
        assertPixel(image, 0, 0, colourOf(5, 16, 9));
        assertPixel(image, 255, 255, colourOf(5, 17, 10));
    }

    /**
     * The disk cache is written after the image has its tiles. Until a tile is on disk it is served from where it
     * waits for the write, so even without a memory cache the next image asks the host for nothing.
     */
    @Test
    void aTileOnItsWayToTheDiskCacheIsNotFetchedAgain() throws IOException {
        Limits d = Limits.DEFAULTS;
        TileFetcher noMemory = fetcher(new Limits(d.connectTimeout(), d.requestTimeout(), d.deadline(),
                d.requestsPerHost(), d.maxTileBytes(), d.minFreshness(), d.failuresToOpen(), d.openFor(), 0));
        double[] corner = northWestCornerOf(5, 17, 10);

        noMemory.background(source(), corner[0], corner[1], 5, 256, 256);
        noMemory.background(source(), corner[0], corner[1], 5, 256, 256);

        assertEquals(4, stub.requestCount(), "the second image takes the four tiles from the writes or the disk");
        assertEquals(8, cachedFiles().size(), "each tile and its meta end up on disk");
    }

    /** The disk cache holds the tile under host, template, z, x and y, with its meta beside it, and no leftovers. */
    @Test
    void storesTheTileAndItsMetaUnderTheHostFolder() throws IOException {
        stub.respond(req -> tile(req, Map.of("ETag", "\"abc\"")));
        double[] centre = centreOf(3, 4, 2);
        fetcher().background(source(), centre[0], centre[1], 3, 256, 256);
        settle();

        Path hostFolder = cacheDir.resolve("127.0.0.1_" + stub.port());
        assertTrue(Files.isDirectory(hostFolder));
        List<Path> files;
        try (Stream<Path> walk = Files.walk(hostFolder)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        assertEquals(2, files.size(), "the tile and its meta, no temporary file: " + files);
        Path tile = files.stream().filter(p -> p.toString().endsWith(".png")).findFirst().orElseThrow();
        assertTrue(tile.endsWith(Path.of("3", "4", "2.png")), tile.toString());
        assertTrue(Files.readString(TileDiskCache.metaFile(tile)).contains("etag=\"abc\""));
    }

    /**
     * A tile is fresh for seven days even when the host says a minute. After that it is revalidated with its
     * validators; a 304 keeps the cached bytes (the host now serves another colour, which must not show) and
     * starts another seven days.
     */
    @Test
    void revalidatesAStaleTileAndKeepsTheBytesOn304() throws IOException {
        Color[] served = {Color.ORANGE};
        String lastModified = "Mon, 05 Jan 2026 09:00:00 GMT";
        stub.respond(req -> "\"v1\"".equals(req.header("If-None-Match"))
                ? StubResponse.of(304, null, new byte[0], Map.of("ETag", "\"v1\""))
                : StubResponse.of(200, "image/png", png(256, served[0]),
                        Map.of("ETag", "\"v1\"", "Last-Modified", lastModified, "Cache-Control", "max-age=60")));
        double[] centre = centreOf(3, 4, 2);
        TileFetcher fetcher = fetcher();

        fetcher.background(source(), centre[0], centre[1], 3, 256, 256);
        assertEquals(1, stub.requestCount());
        assertNull(stub.lastRequest().header("If-None-Match"), "the first request is unconditional");
        settle();

        clock.advance(Duration.ofDays(6));
        fetcher.background(source(), centre[0], centre[1], 3, 256, 256);
        assertEquals(1, stub.requestCount(), "fresh for seven days, whatever max-age says");

        served[0] = Color.CYAN;
        clock.advance(Duration.ofDays(2));
        BufferedImage revalidated = fetcher.background(source(), centre[0], centre[1], 3, 256, 256);
        assertEquals(2, stub.requestCount());
        assertEquals("\"v1\"", stub.lastRequest().header("If-None-Match"));
        assertEquals(lastModified, stub.lastRequest().header("If-Modified-Since"));
        assertPixel(revalidated, 100, 100, Color.ORANGE);

        clock.advance(Duration.ofDays(6));
        fetcher().background(source(), centre[0], centre[1], 3, 256, 256);
        assertEquals(2, stub.requestCount(), "the 304 made the cached copy fresh for another seven days");

        clock.advance(Duration.ofDays(2));
        fetcher().background(source(), centre[0], centre[1], 3, 256, 256);
        assertEquals(3, stub.requestCount(), "and stale again after them");
    }

    /** A host that allows more than seven days is taken at its word. */
    @Test
    void honoursALongerMaxAge() throws IOException {
        stub.respond(req -> tile(req, Map.of("Cache-Control", "public, max-age=2592000")));
        double[] centre = centreOf(3, 4, 2);

        fetcher().background(source(), centre[0], centre[1], 3, 256, 256);
        clock.advance(Duration.ofDays(29));
        fetcher().background(source(), centre[0], centre[1], 3, 256, 256);
        assertEquals(1, stub.requestCount(), "fresh for the 30 days of max-age");

        clock.advance(Duration.ofDays(2));
        fetcher().background(source(), centre[0], centre[1], 3, 256, 256);
        assertEquals(2, stub.requestCount());
    }

    /** When revalidation fails, the stale tile still makes an image: an old tile beats the placeholder. */
    @Test
    void usesTheStaleTileWhenRevalidationFails() throws IOException {
        double[] centre = centreOf(3, 4, 2);
        fetcher().background(source(), centre[0], centre[1], 3, 256, 256);

        stub.respondStatus(503);
        clock.advance(Duration.ofDays(8));
        BufferedImage image = fetcher().background(source(), centre[0], centre[1], 3, 256, 256);

        assertEquals(2, stub.requestCount(), "the stale tile was revalidated");
        assertPixel(image, 10, 10, colourOf(3, 4, 2));
    }

    /**
     * The daily sweep removes the least recently fetched tiles, each with its meta, until the cache is within its
     * cap, and deletes temporary files an interrupted write left behind an hour or more ago.
     */
    @Test
    void evictionRemovesTheOldestTilesFirstDownToTheCap() throws IOException {
        Path folder = cacheDir.resolve("tile.example.org").resolve("0badcafe").resolve("7").resolve("64");
        Files.createDirectories(folder);
        Instant now = clock.instant();
        for (int y = 0; y < 5; y++) {
            // y = 0 is the oldest, fetched five hours ago
            FileTime fetched = FileTime.from(now.minus(Duration.ofHours(5 - y)));
            Path tile = Files.write(folder.resolve(y + ".png"), new byte[100]);
            Path meta = Files.write(folder.resolve(y + ".meta"), new byte[20]);
            Files.setLastModifiedTime(tile, fetched);
            Files.setLastModifiedTime(meta, fetched);
        }
        // Tile 1 was revalidated a moment ago: its meta is new, so it is among the most recently used
        Files.setLastModifiedTime(folder.resolve("1.meta"), FileTime.from(now.minusSeconds(1)));
        Path abandoned = Files.write(folder.resolve("3.png.123.tmp"), new byte[10]);
        Files.setLastModifiedTime(abandoned, FileTime.from(now.minus(Duration.ofHours(2))));
        Path writing = Files.write(folder.resolve("4.png.456.tmp"), new byte[10]);
        Files.setLastModifiedTime(writing, FileTime.from(now.minusSeconds(5)));

        int removed = fetcher().evict(cacheDir, 3 * 120);

        assertEquals(2, removed);
        assertFalse(Files.exists(folder.resolve("0.png")));
        assertFalse(Files.exists(folder.resolve("0.meta")));
        assertFalse(Files.exists(folder.resolve("2.png")));
        assertFalse(Files.exists(folder.resolve("2.meta")));
        for (int y : new int[] {1, 3, 4}) {
            assertTrue(Files.exists(folder.resolve(y + ".png")), "tile " + y + " stays");
            assertTrue(Files.exists(folder.resolve(y + ".meta")), "meta " + y + " stays");
        }
        assertFalse(Files.exists(abandoned), "an abandoned temporary file is deleted");
        assertTrue(Files.exists(writing), "a temporary file being written is left alone");

        assertEquals(0, fetchers.getFirst().evict(cacheDir, 3 * 120), "within the cap nothing more goes");
    }

    // ------------------------------------------------------------------------------------------------
    // What is refused
    // ------------------------------------------------------------------------------------------------

    /**
     * A response over 1 MB is refused before it is decoded: this one is a valid 256 x 256 PNG with padding after
     * its end, which a decoder would accept.
     */
    @Test
    void refusesAResponseOverOneMegabyteAndCachesNothing() throws IOException {
        byte[] png = png(256, Color.GREEN);
        byte[] padded = Arrays.copyOf(png, (int) TileFetcher.MAX_TILE_BYTES + 1);
        assertEquals(256, MapImageTestSupport.decode(padded).getWidth(), "the padded PNG itself decodes");
        stub.respondWith(StubResponse.of(200, "image/png", padded));

        double[] centre = centreOf(3, 4, 2);
        assertThrows(TileUnavailableException.class,
                () -> fetcher().background(source(), centre[0], centre[1], 3, 256, 256));
        assertEquals(List.of(), cachedFiles());
    }

    @Test
    void refusesATileThatIsNot256Square() throws IOException {
        stub.respondWith(StubResponse.bytes("image/png", png(128, Color.GREEN)));

        double[] centre = centreOf(3, 4, 2);
        TileUnavailableException e = assertThrows(TileUnavailableException.class,
                () -> fetcher().background(source(), centre[0], centre[1], 3, 256, 256));
        assertTrue(e.getMessage().contains("128 x 128"), e.getMessage());
        assertEquals(List.of(), cachedFiles());
    }

    @Test
    void refusesAResponseThatIsNotAnImage() throws IOException {
        stub.respondWith(StubResponse.text(200, "<html>Access blocked</html>"));

        double[] centre = centreOf(3, 4, 2);
        assertThrows(TileUnavailableException.class,
                () -> fetcher().background(source(), centre[0], centre[1], 3, 256, 256));
        assertEquals(List.of(), cachedFiles());
    }

    /** A redirect is a failure: the tile comes from the configured host or not at all. */
    @Test
    void doesNotFollowARedirect() {
        stub.respond(req -> req.path().startsWith("/tiles/")
                ? StubResponse.redirect(302, stub.baseUrl() + "/moved" + req.path())
                : tile(req));

        double[] centre = centreOf(3, 4, 2);
        assertThrows(TileUnavailableException.class,
                () -> fetcher().background(source(), centre[0], centre[1], 3, 256, 256));
        assertEquals(List.of("/tiles/3/4/2.png"), stub.requests().stream().map(RecordedRequest::path).toList());
    }

    // ------------------------------------------------------------------------------------------------
    // How the host is treated
    // ------------------------------------------------------------------------------------------------

    /** Every request identifies the application, and none asks the host to bypass its caches. */
    @Test
    void sendsTheUserAgentAndNoCacheBustingHeaders() throws IOException {
        double[] centre = centreOf(3, 4, 2);
        fetcher().background(source(), centre[0], centre[1], 3, 256, 256);

        RecordedRequest request = stub.lastRequest();
        assertEquals("GET", request.method());
        assertEquals(USER_AGENT, request.header("User-Agent"));
        assertNull(request.header("Cache-Control"));
        assertNull(request.header("Pragma"));
    }

    /**
     * Three consecutive failures open the host's circuit breaker: for two minutes an image fails at once, with no
     * request; after that the host is asked again and a success closes the breaker.
     */
    @Test
    void circuitBreakerOpensAfterThreeFailuresAndShortCircuitsForTwoMinutes() throws IOException {
        stub.respondStatus(500);
        TileFetcher fetcher = fetcher();
        double[] centre = centreOf(3, 4, 2);

        for (int i = 1; i <= 3; i++) {
            assertThrows(TileUnavailableException.class,
                    () -> fetcher.background(source(), centre[0], centre[1], 3, 256, 256));
            assertEquals(i, stub.requestCount());
        }

        long started = System.nanoTime();
        TileUnavailableException paused = assertThrows(TileUnavailableException.class,
                () -> fetcher.background(source(), centre[0], centre[1], 3, 256, 256));
        assertEquals(3, stub.requestCount(), "an open breaker sends nothing");
        assertTrue(paused.getMessage().contains("paused"), paused.getMessage());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000, "and fails at once");

        clock.advance(Duration.ofSeconds(119));
        assertThrows(TileUnavailableException.class,
                () -> fetcher.background(source(), centre[0], centre[1], 3, 256, 256));
        assertEquals(3, stub.requestCount(), "still paused just before the two minutes are up");

        stub.respond(TileFetcherTest::tile);
        clock.advance(Duration.ofSeconds(2));
        BufferedImage image = fetcher.background(source(), centre[0], centre[1], 3, 256, 256);
        assertEquals(4, stub.requestCount());
        assertPixel(image, 0, 0, colourOf(3, 4, 2));
    }

    /** A background that cannot be had within its deadline fails at the deadline, not at the host's pace. */
    @Test
    void failsWhenTheDeadlinePasses() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        stub.respond(req -> {
            await(release);
            return tile(req);
        });
        TileFetcher fetcher = fetcher(withDeadline(Duration.ofMillis(300)));
        double[] centre = centreOf(3, 4, 2);

        try {
            long started = System.nanoTime();
            TileUnavailableException e = assertThrows(TileUnavailableException.class,
                    () -> fetcher.background(source(), centre[0], centre[1], 3, 256, 256));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(millis >= 250 && millis < 2500, "failed after " + millis + " ms");
            assertTrue(e.getMessage().contains("300 ms"), e.getMessage());
        } finally {
            fetcher.shutdown();
            release.countDown();
        }
    }

    /** Images made at the same time share one fetch of a tile they both need. */
    @Test
    void concurrentImagesShareOneFetchOfTheSameTile() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        stub.respond(req -> {
            await(release);
            return tile(req);
        });
        TileFetcher fetcher = fetcher();
        double[] centre = centreOf(3, 4, 2);
        int callers = 6;
        CountDownLatch entered = new CountDownLatch(callers);
        ExecutorService callerThreads = Executors.newFixedThreadPool(callers);
        try {
            List<Future<BufferedImage>> images = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                images.add(callerThreads.submit(() -> {
                    entered.countDown();
                    return fetcher.background(source(), centre[0], centre[1], 3, 256, 256);
                }));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread.sleep(300);
            release.countDown();

            for (Future<BufferedImage> image : images) {
                assertPixel(image.get(10, TimeUnit.SECONDS), 0, 0, colourOf(3, 4, 2));
            }
            assertEquals(1, stub.requestCount());
        } finally {
            release.countDown();
            callerThreads.shutdownNow();
        }
    }

    /**
     * The HTTP client is built, and a tile exchange rehearsed on the loopback interface, on a thread of the fetcher's
     * own, and nothing of it reaches the tile host: the first request the host sees is the first tile.
     */
    @Test
    void theClientIsPreparedWithoutAnyRequestToTheTileHost() throws IOException {
        TileFetcher fetcher = fetcher();

        fetcher.awaitPrepared();

        assertEquals(0, stub.requestCount(), "preparing the client asks the tile host nothing");
        double[] centre = centreOf(3, 4, 2);
        BufferedImage image = fetcher.background(source(), centre[0], centre[1], 3, 256, 256);
        assertPixel(image, 128, 128, colourOf(3, 4, 2));
        assertEquals(1, stub.requestCount());
    }

    /** A tile request that comes while the client is still being built waits for it */
    @Test
    void aTileRequestRightAfterTheStartWaitsForTheClient() throws IOException {
        TileFetcher fetcher = fetcher();
        double[] centre = centreOf(3, 4, 2);

        BufferedImage image = fetcher.background(source(), centre[0], centre[1], 3, 256, 256);

        assertPixel(image, 0, 0, colourOf(3, 4, 2));
    }

    /** The composition draws each tile at its placement's offset, on white beyond the poles */
    @Test
    void composeDrawsEveryPlacementsTileAtItsOffset() {
        TileWindow window = TileWindow.centredOn(0, 0, 1, 256, 256);
        BufferedImage image = TileFetcher.compose(window, placement -> {
            BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = tile.createGraphics();
            g.setColor(colourOf(1, placement.x(), placement.y()));
            g.fillRect(0, 0, 256, 256);
            g.dispose();
            return tile;
        });

        assertEquals(256, image.getWidth());
        // Centred on (0, 0) at zoom 1, the four tiles meet in the middle of the image
        assertPixel(image, 64, 64, colourOf(1, 0, 0));
        assertPixel(image, 192, 64, colourOf(1, 1, 0));
        assertPixel(image, 64, 192, colourOf(1, 0, 1));
        assertPixel(image, 192, 192, colourOf(1, 1, 1));
    }

    @Test
    void tileSourceRefusesAnAddressWithoutPlaceholdersOrAUserAgent() {
        assertThrows(IllegalArgumentException.class,
                () -> new TileSource("https://tile.example.org/{z}/{x}.png", USER_AGENT, cacheDir));
        assertThrows(IllegalArgumentException.class,
                () -> new TileSource("ftp://tile.example.org/{z}/{x}/{y}.png", USER_AGENT, cacheDir));
        assertThrows(IllegalArgumentException.class,
                () -> new TileSource("https://{s}.tile.example.org/{z}/{x}/{y}.png", USER_AGENT, cacheDir));
        assertThrows(IllegalArgumentException.class,
                () -> new TileSource("https://tile.example.org/{z}/{x}/{y}.png", " ", cacheDir));
        assertEquals("tile.example.org",
                new TileSource("https://Tile.Example.org/{z}/{x}/{y}.png", USER_AGENT, cacheDir).host());
    }

    // ------------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------------

    private TileFetcher fetcher() {
        return fetcher(Limits.DEFAULTS);
    }

    /**
     * A new fetcher, as after a restart. It sees the disk cache as the earlier ones left it: their cache writes,
     * which run after the image has its tiles, are waited for first.
     */
    private TileFetcher fetcher(Limits limits) {
        settle();
        TileFetcher fetcher = new TileFetcher(limits, clock);
        fetchers.add(fetcher);
        return fetcher;
    }

    private static Limits withDeadline(Duration deadline) {
        Limits d = Limits.DEFAULTS;
        return new Limits(d.connectTimeout(), d.requestTimeout(), deadline, d.requestsPerHost(), d.maxTileBytes(),
                d.minFreshness(), d.failuresToOpen(), d.openFor(), d.memoryTiles());
    }

    private TileSource source() {
        return new TileSource(stub.baseUrl() + "/tiles/{z}/{x}/{y}.png", USER_AGENT, cacheDir);
    }

    private Set<String> requestedPaths() {
        return stub.requests().stream().map(RecordedRequest::path).collect(Collectors.toSet());
    }

    /** Waits for the disk cache writes every fetcher has started */
    private void settle() {
        fetchers.forEach(TileFetcher::awaitCacheWrites);
    }

    private List<Path> cachedFiles() throws IOException {
        settle();
        try (Stream<Path> walk = Files.walk(cacheDir)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    /** A colour per tile that differs between neighbouring tiles. */
    static Color colourOf(int z, int x, int y) {
        return new Color((x * 37 + 11) & 0xFF, (y * 53 + 7) & 0xFF, (z * 29 + 3) & 0xFF);
    }

    private static StubResponse tile(RecordedRequest request) {
        return tile(request, Map.of());
    }

    /** The tile of the request path, in its own colour, or a 404 for any other path. */
    private static StubResponse tile(RecordedRequest request, Map<String, String> headers) {
        Matcher m = TILE_PATH.matcher(request.path());
        if (!m.matches()) {
            return StubResponse.status(404);
        }
        Color colour = colourOf(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
        return StubResponse.of(200, "image/png", png(256, colour), headers);
    }

    private static byte[] png(int size, Color colour) {
        try {
            return MapImageTestSupport.solidPng(size, size, colour);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The EPSG:3857 point of the north-west corner of XYZ tile z/x/y. */
    private static double[] northWestCornerOf(int z, int x, int y) {
        double resolution = MERCATOR.Resolution(z);
        return new double[] {x * 256 * resolution - ORIGIN_SHIFT, ORIGIN_SHIFT - y * 256 * resolution};
    }

    /** The EPSG:3857 point of the centre of XYZ tile z/x/y: a 256 px image centred there needs only that tile. */
    private static double[] centreOf(int z, int x, int y) {
        double resolution = MERCATOR.Resolution(z);
        return new double[] {(x + 0.5) * 256 * resolution - ORIGIN_SHIFT, ORIGIN_SHIFT - (y + 0.5) * 256 * resolution};
    }

    private static void assertPixel(BufferedImage image, int x, int y, Color expected) {
        assertEquals(expected.getRGB() & 0xFFFFFF, image.getRGB(x, y) & 0xFFFFFF,
                "pixel " + x + "," + y + " is " + new Color(image.getRGB(x, y)) + ", expected " + expected);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
