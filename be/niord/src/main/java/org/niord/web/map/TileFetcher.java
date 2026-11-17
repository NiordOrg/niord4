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
import org.niord.core.util.MdcContext;
import org.niord.web.map.TileDiskCache.Entry;
import org.niord.web.map.TileDiskCache.Meta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Composes the OpenStreetMap background of a server-made map image from raster tiles: the tiles that intersect
 * the image, fetched in parallel from the tile host or taken from a disk cache, drawn at whole-pixel offsets into
 * one RGB image.
 *
 * <h2>OpenStreetMap tile usage policy</h2>
 * This class is the only place the backend downloads map tiles, and it meets the obligations of the
 * OpenStreetMap Foundation's tile usage policy (operations.osmfoundation.org/policies/tiles) that fall on a
 * tile client:
 * <ul>
 *   <li><b>An identifying User-Agent.</b> Every request carries {@link TileSource#userAgent()}, which names the
 *       application, its version and a contact; the HTTP library's default is never sent.</li>
 *   <li><b>No cache-busting.</b> No {@code Cache-Control} or {@code Pragma} request header is sent.</li>
 *   <li><b>Local caching for at least seven days.</b> A tile is reused from the disk cache for seven days, or
 *       longer when the response's {@code Cache-Control: max-age} or {@code Expires} allows it. After that it is
 *       revalidated with {@code If-None-Match} / {@code If-Modified-Since}; a {@code 304} keeps the cached bytes.
 *       The cache is bounded (a daily sweep evicts the oldest tiles above {@value #MAX_CACHE_BYTES} bytes).</li>
 *   <li><b>Only the tiles an image needs.</b> Exactly the tiles that intersect the image rectangle, at the
 *       image's zoom level: no margin ring, no other zoom levels, no prefetching. Images are made on request, so
 *       nothing is downloaded in bulk.</li>
 *   <li><b>Modest, well-behaved load.</b> At most {@value #REQUESTS_PER_HOST} requests per host at a time (over
 *       HTTP/2 they share one connection); concurrent images share one fetch of the same tile; redirects are not
 *       followed; a host that fails three times in a row gets no requests for two minutes.</li>
 *   <li><b>No re-serving.</b> The cache folder lies outside the public repository folder and nothing serves it;
 *       tiles reach users only inside a composed map image. Showing that image with the OpenStreetMap credit is
 *       the caller's obligation.</li>
 * </ul>
 *
 * <h2>Failure contract</h2>
 * {@link #background} either returns the complete background or throws a {@link TileUnavailableException}: when a
 * tile cannot be had within {@value #DEADLINE_SECONDS} s (host down, paused by the circuit breaker, a response
 * that is not a 256 x 256 image or larger than {@value #MAX_TILE_BYTES} bytes, a redirect). The caller answers with
 * the placeholder and writes nothing. A tile whose cached copy is stale is still used when revalidating it fails:
 * an old tile is better than no image.
 *
 * <h2>Threads</h2>
 * A tile in memory or on its way to the disk cache is taken on the calling thread, and so is a fresh tile of the
 * disk cache: the file is read and decoded there. Only a tile that must be had from the host is loaded on a virtual
 * thread of an executor owned by this bean, carrying the MDC of the request that started it
 * ({@link MdcContext#wrapRunnable}), so the DEBUG line per tile names that request. The disk cache is never read on
 * a virtual thread: on the Windows workstations about one file read in fifty on a virtual thread waited 100 or
 * 200 ms, which the same read on a platform thread never did (the most was 1.4 ms), and a map image waits for its
 * slowest tile. The tile request is awaited on the virtual thread, which bounds the whole exchange (headers and
 * body) by the request timeout.
 * The {@link HttpClient} keeps its own default executor: it runs only the client's internal I/O completions and
 * no code of this class, so there is no MDC to carry. Both are shut down in {@link #shutdown()}.
 *
 * <p>The {@link HttpClient} is built on a virtual thread of the same executor, started when the bean is created
 * (with a job id of its own, {@link MdcContext#withFreshJobId}), not on the thread that creates the bean: building
 * it loads the HTTP client's classes and initialises the default TLS context, which would otherwise lengthen the
 * application's start. A tile request that comes before it is built waits for it. The same thread then runs one
 * tile exchange against a socket of this JVM on the loopback interface ({@link #rehearse}), so that the first
 * image after a start does not load the code of a tile exchange while it waits. No request leaves the machine.
 *
 * <p>A fetched tile is written to the disk cache on a virtual thread of its own, after the image that asked for it
 * has it: no image waits for the cache write (a temporary file and a move for the tile and for its meta, which on a
 * busy disk costs as much as the fetch). Until the write is done the tile is served from memory, so nothing
 * fetches it again meanwhile.
 *
 * <p>The cache folder is swept by {@link #sweepTileCaches()}, daily, never on the request path. It sweeps every
 * cache folder {@link #background} has used since start-up, plus any passed to
 * {@link #registerCacheDirectory(Path)}; a folder only grows through {@code background}, so nothing needs wiring.
 */
@Component
public class TileFetcher {

    private static final Logger log = LoggerFactory.getLogger(TileFetcher.class);

    static final int DEADLINE_SECONDS = 8;

    /**
     * The tile requests in flight per host. A page of the administration app's Details or Grid view asks for up to
     * 50 images at once, and an image of a place no image was drawn of for a week needs up to four tiles; a request
     * waiting here counts against its image's deadline. At 8, with tiles taking 250 ms, such a page took twice as
     * long as with the previous renderer, which drew up to ten images at once (one per database connection it held);
     * at 64 it takes two thirds of that time (be/docs/map-image-benchmark.md). Over HTTP/2 the requests share one
     * connection.
     */
    static final int REQUESTS_PER_HOST = 64;
    static final long MAX_TILE_BYTES = 1024 * 1024;

    /** The disk cache is swept down to this size (200 MB), per cache folder. */
    public static final long MAX_CACHE_BYTES = 200L * 1024 * 1024;

    /** The longest lifetime taken from a response's cache headers: a year. */
    static final long MAX_LIFETIME_SECONDS = 365L * 24 * 60 * 60;

    /** Painted where the image extends beyond the world's poles, where no tiles exist. */
    static final Color OUTSIDE_THE_WORLD = Color.WHITE;

    /**
     * The timing and size limits.
     *
     * @param connectTimeout  the TCP and TLS connect timeout
     * @param requestTimeout  the time one tile request may take, response body included
     * @param deadline        the time one background may take
     * @param requestsPerHost the requests in flight per host
     * @param maxTileBytes    the largest tile response accepted
     * @param minFreshness    how long a fetched tile is used without asking the host, at least
     * @param failuresToOpen  the consecutive failures that open a host's circuit breaker
     * @param openFor         how long an open circuit breaker keeps requests away from the host
     * @param memoryTiles     the decoded tiles kept in memory
     */
    record Limits(Duration connectTimeout, Duration requestTimeout, Duration deadline, int requestsPerHost,
                  long maxTileBytes, Duration minFreshness, int failuresToOpen, Duration openFor, int memoryTiles) {

        static final Limits DEFAULTS = new Limits(
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(DEADLINE_SECONDS),
                REQUESTS_PER_HOST, MAX_TILE_BYTES, Duration.ofDays(7), 3, Duration.ofMinutes(2), 64);
    }

    /** One tile of one tile source: the key of the in-flight fetches and of the memory cache. */
    record TileKey(String urlTemplate, int z, int x, int y) {
        @Override
        public String toString() {
            return z + "/" + x + "/" + y;
        }
    }

    private final Limits limits;
    private final Clock clock;
    private final CompletableFuture<HttpClient> client = new CompletableFuture<>();
    private final CompletableFuture<Void> rehearsed = new CompletableFuture<>();
    private final ExecutorService loader;
    private final MemoryTiles memory;
    private final Map<TileKey, CompletableFuture<BufferedImage>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> hostPermits = new ConcurrentHashMap<>();
    private final Map<String, HostCircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Set<Path> cacheDirectories = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<Void>> cacheWrites = ConcurrentHashMap.newKeySet();
    private final Map<TileKey, BufferedImage> writing = new ConcurrentHashMap<>();
    private final AtomicInteger hostLoads = new AtomicInteger();

    TileFetcher() {
        this(Limits.DEFAULTS, Clock.systemUTC());
    }

    TileFetcher(Limits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
        this.memory = new MemoryTiles(limits.memoryTiles());

        // One virtual thread per tile load; every task is wrapped with MdcContext where it is submitted
        this.loader = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("map-tile-", 0).factory());

        // The client is built, and a tile exchange rehearsed, off the thread that creates the bean (class comment)
        loader.execute(MdcContext.withFreshJobId(this::prepareClient));
    }

    @PreDestroy
    void shutdown() {
        loader.shutdownNow();
        try {
            loader.awaitTermination(2, TimeUnit.SECONDS);
            HttpClient built = client.isDone() && !client.isCompletedExceptionally() ? client.join() : null;
            if (built != null) {
                built.shutdownNow();
                built.awaitTermination(Duration.ofSeconds(2));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Builds the HTTP client, hands it over, and then rehearses a tile exchange with it */
    private void prepareClient() {
        long start = System.nanoTime();
        HttpClient built;
        try {
            built = buildClient(limits);
        } catch (Throwable t) {
            // Every tile request fails with this until a restart, instead of waiting for a client that never comes
            client.completeExceptionally(t);
            rehearsed.complete(null);
            log.error("Cannot build the HTTP client of the map tiles: {}", t.toString(), t);
            if (t instanceof Error error) {
                throw error;
            }
            return;
        }
        client.complete(built);
        long ready = System.nanoTime();
        try {
            rehearse(built);
            log.debug("Built the map tile HTTP client in {} ms and rehearsed a tile exchange in {} ms",
                    TimeUnit.NANOSECONDS.toMillis(ready - start), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ready));
        } catch (IOException | RuntimeException e) {
            // Only a head start: the first tile request loads what it needs itself
            log.debug("Could not rehearse a tile exchange on the loopback interface: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            rehearsed.complete(null);
        }
    }

    private static HttpClient buildClient(Limits limits) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(limits.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        // The default selector honours -Dhttps.proxyHost/-Dhttps.proxyPort/-Dhttp.nonProxyHosts
        ProxySelector proxy = ProxySelector.getDefault();
        if (proxy != null) {
            builder.proxy(proxy);
        }
        return builder.build();
    }

    /** The HTTP client, waiting for it while it is being built, at most as long as a tile request may take */
    private HttpClient client() throws IOException, InterruptedException {
        try {
            return client.get(limits.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new TileUnavailableException("The HTTP client of the map tiles could not be built: " + e.getCause(),
                    e.getCause());
        } catch (TimeoutException e) {
            throw new TileUnavailableException("The HTTP client of the map tiles is not built yet");
        }
    }

    /**
     * Runs one tile exchange against a server socket of this JVM on the loopback interface, which accepts this one
     * connection: the request is sent as a tile request is sent ({@link #send}), and the answer, a 256 x 256 PNG
     * with the headers a tile host sends, is read, measured and decoded as a tile is. It loads and links the code
     * that the first tile request would otherwise load while an image waits for it. Over {@code https}, the tile
     * hosts' scheme, that request still loads the TLS and HTTP/2 code itself; the rest is shared. Nothing is sent
     * when the JVM's proxy settings would send the loopback address to a proxy.
     */
    private void rehearse(HttpClient http) throws IOException, InterruptedException {
        byte[] png = blankTilePng();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            server.setSoTimeout((int) limits.requestTimeout().toMillis());
            String host = loopback instanceof Inet6Address
                    ? "[" + loopback.getHostAddress() + "]"
                    : loopback.getHostAddress();
            URI uri = URI.create("http://" + host + ":" + server.getLocalPort() + "/0/0/0.png");
            ProxySelector proxies = ProxySelector.getDefault();
            if (proxies != null && proxies.select(uri).stream().anyMatch(p -> p.type() != Proxy.Type.DIRECT)) {
                return;
            }
            loader.execute(MdcContext.wrapRunnable(() -> answerOnce(server, png)));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(limits.requestTimeout())
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("User-Agent", "Niord")
                    .build();
            HttpResponse<byte[]> response = send(http, request);
            if (response.statusCode() == 200) {
                decodeTile(response.body());
                meta(response.headers(), clock.millis(), Meta.NONE);
            }
        }
    }

    /** A blank tile as a PNG */
    static byte[] blankTilePng() throws IOException {
        BufferedImage tile = new BufferedImage(TileWindow.TILE_SIZE, TileWindow.TILE_SIZE, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        // In memory, never through ImageIO's file cache (see decodeTile)
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(png)) {
            if (!ImageIO.write(tile, "png", out)) {
                throw new IOException("No PNG writer is available");
            }
        }
        return png.toByteArray();
    }

    /** Answers the first connection to the server socket with the tile, as a tile host answers */
    private static void answerOnce(ServerSocket server, byte[] png) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(server.getSoTimeout());
            InputStream in = socket.getInputStream();
            // The request ends with an empty line; it has no body
            byte[] end = {'\r', '\n', '\r', '\n'};
            int matched = 0;
            while (matched < end.length) {
                int b = in.read();
                if (b < 0) {
                    return;
                }
                matched = b == end[matched] ? matched + 1 : (b == end[0] ? 1 : 0);
            }
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: image/png\r\n"
                    + "Content-Length: " + png.length + "\r\n"
                    + "Cache-Control: max-age=604800\r\n"
                    + "ETag: \"rehearsal\"\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(png);
            out.flush();
        } catch (IOException e) {
            log.debug("The rehearsal tile was not answered: {}", e.toString());
        }
    }

    /** Waits until the client is built and its rehearsal is over: for tests */
    void awaitPrepared() {
        rehearsed.join();
    }

    /** Returns how many tile loads were handed to a virtual thread to be had from a host: for tests */
    int hostLoads() {
        return hostLoads.get();
    }

    /**
     * The background of a {@code widthPx} x {@code heightPx} map image whose centre is the given EPSG:3857 point:
     * a {@link BufferedImage#TYPE_INT_RGB} image of exactly that size, see {@link TileWindow#centredOn}.
     *
     * @throws TileUnavailableException when a tile cannot be had in time; see the class comment
     */
    public BufferedImage background(TileSource source, double centreX3857, double centreY3857, int zoom,
                                    int widthPx, int heightPx) throws IOException {
        return background(source, TileWindow.centredOn(centreX3857, centreY3857, zoom, widthPx, heightPx));
    }

    /**
     * The background of the given window: a {@link BufferedImage#TYPE_INT_RGB} image of the window's size.
     *
     * @throws TileUnavailableException when a tile cannot be had in time; see the class comment
     */
    public BufferedImage background(TileSource source, TileWindow window) throws IOException {
        long deadline = System.nanoTime() + limits.deadline().toNanos();
        registerCacheDirectory(source.cacheDir());

        List<TileWindow.Placement> placements = window.placements();
        Map<TileKey, CompletableFuture<BufferedImage>> tiles = new LinkedHashMap<>();
        for (TileWindow.Placement placement : placements) {
            TileKey key = new TileKey(source.urlTemplate(), window.zoom(), placement.x(), placement.y());
            tiles.computeIfAbsent(key, k -> tile(source, k));
        }
        awaitAll(tiles.values(), deadline, source.host());

        return compose(window, placement -> tiles.get(
                new TileKey(source.urlTemplate(), window.zoom(), placement.x(), placement.y())).join());
    }

    /**
     * Draws the tiles of a window into one RGB image of the window's size, each at its whole-pixel offset, on the
     * colour of the area beyond the poles.
     *
     * @param window the window
     * @param tiles  the decoded tile of each placement of the window
     * @return the image
     */
    static BufferedImage compose(TileWindow window, Function<TileWindow.Placement, BufferedImage> tiles) {
        BufferedImage image = new BufferedImage(window.width(), window.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(OUTSIDE_THE_WORLD);
            g.fillRect(0, 0, window.width(), window.height());
            for (TileWindow.Placement placement : window.placements()) {
                g.drawImage(tiles.apply(placement), placement.dx(), placement.dy(), null);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Waits for every tile, failing as soon as one fails or the deadline passes. */
    private void awaitAll(Iterable<CompletableFuture<BufferedImage>> tiles, long deadline, String host)
            throws IOException {
        CompletableFuture<Void> firstFailure = new CompletableFuture<>();
        List<CompletableFuture<BufferedImage>> all = new ArrayList<>();
        for (CompletableFuture<BufferedImage> tile : tiles) {
            all.add(tile);
            tile.whenComplete((image, failure) -> {
                if (failure != null) {
                    firstFailure.completeExceptionally(failure);
                }
            });
        }
        CompletableFuture<Object> done = CompletableFuture.anyOf(
                CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)), firstFailure);
        try {
            done.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new TileUnavailableException("Map tiles from " + host + " were not available within "
                    + limits.deadline().toMillis() + " ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof TileUnavailableException unavailable
                    ? unavailable
                    : new TileUnavailableException("Map tiles from " + host + " are not available: " + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for map tiles");
        }
    }

    /**
     * The tile from memory, from the fetch of it in flight, from the disk cache when it is fresh there (read on the
     * calling thread), or from a new load from the host on a virtual thread.
     */
    private CompletableFuture<BufferedImage> tile(TileSource source, TileKey key) {
        BufferedImage hot = memory.fresh(key, clock.millis());
        if (hot != null) {
            log.debug("Tile {} from {}: memory", key, source.host());
            return CompletableFuture.completedFuture(hot);
        }
        CompletableFuture<BufferedImage> running = inFlight.get(key);
        if (running != null) {
            log.debug("Tile {} from {}: joins the fetch in flight", key, source.host());
            return running;
        }
        BufferedImage beingWritten = writing.get(key);
        if (beingWritten != null) {
            return CompletableFuture.completedFuture(beingWritten);
        }

        // The disk cache is read here, on the calling thread, never on a virtual thread (see the class comment)
        Path file = TileDiskCache.tileFile(source.cacheDir(), source, key.z(), key.x(), key.y());
        DiskTile cached = readDiskTile(file, key, source.host());
        if (cached != null && cached.entry().meta().isFresh(clock.millis())) {
            memory.put(key, cached.image(), cached.entry().meta().expiresAt());
            log.debug("Tile {} from {}: disk cache", key, source.host());
            return CompletableFuture.completedFuture(cached.image());
        }

        CompletableFuture<BufferedImage> mine = new CompletableFuture<>();
        running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            log.debug("Tile {} from {}: joins the fetch in flight", key, source.host());
            return running;
        }
        Runnable load = MdcContext.wrapRunnable(() -> {
            BufferedImage image = null;
            Throwable failure = null;
            try {
                image = fromHost(source, key, file, cached);
            } catch (Throwable t) {
                failure = t;
            }
            // The fetch leaves the in-flight map before it completes, so no request that comes after it joins a
            // fetch that has already finished: after a success the tile is in memory, after a failure the next
            // request asks the host again (and counts towards its circuit breaker)
            inFlight.remove(key, mine);
            if (failure == null) {
                mine.complete(image);
            } else {
                mine.completeExceptionally(failure);
            }
        });
        try {
            hostLoads.incrementAndGet();
            loader.execute(load);
        } catch (RejectedExecutionException e) {
            inFlight.remove(key, mine);
            mine.completeExceptionally(new TileUnavailableException("The tile loader is shut down"));
        }
        return mine;
    }

    /** A tile of the disk cache: the file's bytes and meta, and the tile decoded */
    private record DiskTile(Entry entry, BufferedImage image) {
    }

    /** The tile the disk cache holds, fresh or not, or null when it holds none or an unreadable one */
    private static DiskTile readDiskTile(Path file, TileKey key, String host) {
        Entry cached = readQuietly(file);
        if (cached == null) {
            return null;
        }
        try {
            return new DiskTile(cached, decodeTile(cached.bytes()));
        } catch (IOException e) {
            log.debug("Tile {} from {}: the cached copy is unreadable ({}), fetching it again",
                    key, host, e.getMessage());
            return null;
        }
    }

    /**
     * Loads one tile from the host, on a virtual thread: a tile another image has meanwhile put in memory or on its
     * way to the disk cache is taken from there; a stale disk copy is revalidated, and used when the host cannot be
     * asked or fails.
     */
    private BufferedImage fromHost(TileSource source, TileKey key, Path file, DiskTile disk)
            throws IOException, InterruptedException {
        long now = clock.millis();
        BufferedImage hot = memory.fresh(key, now);
        if (hot != null) {
            return hot;
        }
        BufferedImage beingWritten = writing.get(key);
        if (beingWritten != null) {
            return beingWritten;
        }

        String host = source.host();
        Entry cached = disk != null ? disk.entry() : null;
        BufferedImage cachedImage = disk != null ? disk.image() : null;

        HostCircuitBreaker breaker = breakers.computeIfAbsent(host,
                h -> new HostCircuitBreaker(h, limits.failuresToOpen(), limits.openFor().toMillis()));
        if (!breaker.allowsRequest(now)) {
            if (cachedImage != null) {
                log.debug("Tile {} from {}: host paused, using the stale cached copy", key, host);
                return cachedImage;
            }
            throw new TileUnavailableException("Tile host " + host + " is paused after repeated failures");
        }

        // Taken before the host's circuit breaker books anything: a client that is not built yet is no failure of
        // the host, which has not been asked
        HttpClient http = client();
        Semaphore permits = hostPermits.computeIfAbsent(host, h -> new Semaphore(limits.requestsPerHost()));
        if (!permits.tryAcquire(limits.deadline().toMillis(), TimeUnit.MILLISECONDS)) {
            throw new TileUnavailableException("Tile host " + host + " has too many requests in flight");
        }
        try {
            BufferedImage image = fetch(http, source, key, file, cached, cachedImage);
            breaker.recordSuccess();
            return image;
        } catch (IOException e) {
            breaker.recordFailure(clock.millis(), e);
            if (cachedImage != null) {
                log.debug("Tile {} from {}: revalidation failed ({}), using the stale cached copy",
                        key, host, e.toString());
                return cachedImage;
            }
            log.debug("Tile {} from {}: failed: {}", key, host, e.toString());
            throw e;
        } finally {
            permits.release();
        }
    }

    /** Requests a tile from the host, conditionally when a stale copy with validators is cached. */
    private BufferedImage fetch(HttpClient http, TileSource source, TileKey key, Path file, Entry cached,
            BufferedImage cachedImage) throws IOException, InterruptedException {
        URI uri = source.tileUri(key.z(), key.x(), key.y());
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(limits.requestTimeout())
                .header("User-Agent", source.userAgent());
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            // HTTP/2 is negotiated through TLS; over plain HTTP the h2c upgrade attempt would only cost time
            request.version(HttpClient.Version.HTTP_1_1);
        }
        boolean conditional = cached != null && cached.meta().hasValidators();
        if (conditional) {
            if (cached.meta().etag() != null) {
                request.header("If-None-Match", cached.meta().etag());
            }
            if (cached.meta().lastModified() != null) {
                request.header("If-Modified-Since", cached.meta().lastModified());
            }
        }

        long started = System.nanoTime();
        HttpResponse<byte[]> response = send(http, request.build());
        long now = clock.millis();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int status = response.statusCode();

        if (status == 304 && conditional) {
            Meta meta = meta(response.headers(), now, cached.meta());
            memory.put(key, cachedImage, meta.expiresAt());
            writeLater(key, cachedImage, () -> writeMetaQuietly(file, meta));
            log.debug("Tile {} from {}: not modified (304) in {} ms", key, source.host(), millis);
            return cachedImage;
        }
        if (status != 200) {
            // A redirect lands here too: they are never followed
            throw new TileUnavailableException("Tile " + key + " from " + source.host() + ": HTTP " + status);
        }
        byte[] bytes = response.body();
        BufferedImage image = decodeTile(bytes);
        Meta meta = meta(response.headers(), now, Meta.NONE);
        memory.put(key, image, meta.expiresAt());
        writeLater(key, image, () -> writeQuietly(file, bytes, meta));
        log.debug("Tile {} from {}: fetched ({} bytes) in {} ms", key, source.host(), bytes.length, millis);
        return image;
    }

    /** Sends a request and waits at most the request timeout for the whole response, body included. */
    private HttpResponse<byte[]> send(HttpClient http, HttpRequest request) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(
                request, BodyHandlers.limiting(BodyHandlers.ofByteArray(), limits.maxTileBytes()));
        try {
            return pending.get(limits.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new HttpTimeoutException("No complete response within " + limits.requestTimeout().toMillis() + " ms");
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException io ? io : new IOException(cause);
        }
    }

    /**
     * The meta of a response: its validators (a {@code 304} may omit them, then the previous ones stay) and a
     * freshness of the response's own lifetime, or the minimum freshness when that is longer.
     */
    private Meta meta(HttpHeaders headers, long now, Meta previous) {
        String etag = headers.firstValue("ETag").orElse(previous.etag());
        String lastModified = headers.firstValue("Last-Modified").orElse(previous.lastModified());
        long lifetime = Math.max(limits.minFreshness().toMillis(), responseLifetimeMillis(headers, now));
        return new Meta(etag, lastModified, now + lifetime, now);
    }

    /** {@code Cache-Control: max-age}, else {@code Expires} minus {@code Date}; 0 when neither says anything. */
    static long responseLifetimeMillis(HttpHeaders headers, long now) {
        for (String value : headers.allValues("Cache-Control")) {
            for (String directive : value.split(",")) {
                String d = directive.trim().toLowerCase(Locale.ROOT);
                if (d.startsWith("max-age=")) {
                    try {
                        long seconds = Long.parseLong(d.substring(8).replace("\"", "").trim());
                        return Math.clamp(seconds, 0, MAX_LIFETIME_SECONDS) * 1000;
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
        }
        Optional<Long> expires = headers.firstValue("Expires").map(TileFetcher::httpDate);
        if (expires.isPresent()) {
            long date = headers.firstValue("Date").map(TileFetcher::httpDate).orElse(now);
            return Math.clamp(expires.get() - date, 0, MAX_LIFETIME_SECONDS * 1000);
        }
        return 0;
    }

    /** An HTTP date in epoch milliseconds; an unparsable date (e.g. {@code Expires: 0}) counts as already past. */
    private static Long httpDate(String value) {
        try {
            return ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    /**
     * Decodes a tile in memory (never through ImageIO's file cache, whose global switch other code relies on),
     * refusing anything but a 256 x 256 image before its pixels are decoded.
     */
    static BufferedImage decodeTile(byte[] bytes) throws IOException {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new TileUnavailableException("The tile is not an image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width != TileWindow.TILE_SIZE || height != TileWindow.TILE_SIZE) {
                    throw new TileUnavailableException("The tile is " + width + " x " + height + " px, not "
                            + TileWindow.TILE_SIZE + " x " + TileWindow.TILE_SIZE);
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException e) {
            throw new TileUnavailableException("The tile could not be decoded: " + e, e);
        }
    }

    private static Entry readQuietly(Path file) {
        try {
            return TileDiskCache.read(file);
        } catch (IOException e) {
            log.debug("Could not read the cached tile {}: {}", file, e.toString());
            return null;
        }
    }

    /** A tile that cannot be cached is still used; the failure is logged, as it defeats the cache. */
    private static void writeQuietly(Path file, byte[] bytes, Meta meta) {
        try {
            TileDiskCache.write(file, bytes, meta);
        } catch (IOException e) {
            log.warn("Could not cache the map tile {}: {}", file, e.toString());
        }
    }

    /**
     * Runs a disk cache write on a virtual thread of its own. Until it is done, {@link #tile} and {@link #fromHost}
     * serve the tile from {@link #writing}, so no image fetches it again meanwhile, whatever the memory cache has
     * evicted. A write the
     * shut-down loader refuses is dropped: the tile is fetched again after the restart.
     */
    private void writeLater(TileKey key, BufferedImage image, Runnable write) {
        writing.put(key, image);
        CompletableFuture<Void> done = new CompletableFuture<>();
        cacheWrites.add(done);
        Runnable finish = () -> {
            writing.remove(key, image);
            cacheWrites.remove(done);
            done.complete(null);
        };
        try {
            loader.execute(MdcContext.wrapRunnable(() -> {
                try {
                    write.run();
                } finally {
                    finish.run();
                }
            }));
        } catch (RejectedExecutionException e) {
            finish.run();
        }
    }

    /** Waits until the disk cache writes started so far are done: for tests that look at the disk cache. */
    void awaitCacheWrites() {
        CompletableFuture.allOf(cacheWrites.toArray(CompletableFuture[]::new)).join();
    }

    private static void writeMetaQuietly(Path file, Meta meta) {
        try {
            TileDiskCache.writeMeta(file, meta);
        } catch (IOException e) {
            log.warn("Could not update the cached map tile {}: {}", file, e.toString());
        }
    }

    /**
     * Adds a folder to those the daily sweep keeps below {@link #MAX_CACHE_BYTES}. {@link #background} registers
     * the folder of every source it is called with; this is for a caller that wants a folder swept before its
     * first image.
     */
    public void registerCacheDirectory(Path cacheDir) {
        cacheDirectories.add(cacheDir.toAbsolutePath().normalize());
    }

    /**
     * Shrinks a tile cache folder to at most {@code maxBytes}, removing the least recently fetched or revalidated
     * tiles first, and deletes the temporary files an interrupted write left behind.
     *
     * @return the number of tiles removed
     */
    public int evict(Path cacheDir, long maxBytes) throws IOException {
        return TileDiskCache.evict(cacheDir, maxBytes, clock.millis());
    }

    /** Every night, keeps each known tile cache folder below {@link #MAX_CACHE_BYTES}. */
    @Scheduled(cron = "41 47 3 * * ?")
    void sweepTileCaches() {
        for (Path cacheDir : cacheDirectories) {
            try {
                evict(cacheDir, MAX_CACHE_BYTES);
            } catch (IOException | UncheckedIOException e) {
                log.warn("Could not sweep the tile cache {}: {}", cacheDir, e.toString());
            }
        }
    }

    /** The decoded tiles most recently used, each until its cached copy goes stale. */
    private static final class MemoryTiles {

        private record Hot(BufferedImage image, long expiresAt) {
        }

        private final int capacity;
        private final LinkedHashMap<TileKey, Hot> tiles;

        MemoryTiles(int capacity) {
            this.capacity = capacity;
            this.tiles = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<TileKey, Hot> eldest) {
                    return size() > MemoryTiles.this.capacity;
                }
            };
        }

        synchronized BufferedImage fresh(TileKey key, long now) {
            Hot hot = tiles.get(key);
            return hot != null && now < hot.expiresAt() ? hot.image() : null;
        }

        synchronized void put(TileKey key, BufferedImage image, long expiresAt) {
            if (capacity > 0) {
                tiles.put(key, new Hot(image, expiresAt));
            }
        }
    }
}
