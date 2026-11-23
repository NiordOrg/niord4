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

import jakarta.inject.Inject;
import org.niord.core.repo.RepoCache;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.util.MdcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import static org.niord.core.settings.Setting.Type.Integer;

/**
 * The sea-chart tiles the {@link WmsProxyServlet} has fetched from the WMS provider, kept on the application's
 * volume so that the next map that shows a place does not ask the provider again.
 *
 * <p><b>Who gets a cached tile.</b> Nothing serves this folder but the servlet, and the servlet comes here only for
 * a request that has a user. That holds whatever the {@code wmsProtected} setting says: switching it off opens the
 * proxy to anonymous callers, never the cache.
 *
 * <p><b>The files.</b> {@code <niord.home>/wms-tiles/<host>/<variant>/<z>/<x>/<y>.png} ({@link WmsTileKey}), each
 * the PNG the servlet answers with. A file's modification time is when the tile was fetched. Every file is written
 * to a temporary sibling and moved into place, so a reader never sees half a tile. The volume is a cache that
 * nothing backs up: a tile that is missing is fetched again.
 *
 * <p><b>Freshness.</b> A tile is served from here for {@code wmsCacheDays} days. After that the provider is asked
 * again, on the thread of the request that wants the tile; a WMS answer carries nothing to revalidate with, so it
 * is fetched whole. Requests for a tile that is being fetched wait for that fetch instead of starting their own:
 * a map cancels and repeats its tile requests at every pan.
 *
 * <p><b>When the provider fails.</b> The older copy is served, as long as it is younger than {@link #MAX_STALE}; a
 * chart some days behind is better than none. With {@code wmsCacheDays} at 30 or more there is no such copy, since
 * a tile is fresh until it is older than that. Without a copy the caller gets no tile. After
 * {@value #FAILURES_TO_PAUSE} failed fetches in a row the provider is not asked for {@link #PAUSE}, so an outage
 * costs each request a file read and not a network timeout; then one request tries the provider, and its failure
 * pauses it anew, its success ends the pause. An answer that is no tile ({@link NotATileException}) is no failure
 * of the provider.
 *
 * <p><b>The bound.</b> The volume is shared with the search index and the batch files, so the folder is capped at
 * {@code wmsCacheMaxMb}. A file counts for at least {@link #MIN_CHARGE} towards the cap: an open-sea tile is a few
 * hundred bytes, and a volume has a fixed number of files it can hold, by default one for every 16 KB of its size,
 * so counting this way keeps the folder's share of the volume's files within its share of the bytes. A write that
 * takes the folder past the cap starts a sweep, one at a time and off the request's thread, that deletes the least
 * recently fetched tiles down to {@link #SWEEP_TO} of the cap. The first write after a start starts one too, which
 * counts the folder; until it has, the cap waits, and no request waits for the count. Nothing is written, and no
 * folder made, while the volume has less than {@link #MIN_FREE_BYTES} free. A nightly sweep counts the folder
 * again and deletes the tiles older than {@link #MAX_STALE}, or than {@code wmsCacheDays} when that is longer, the
 * temporary files an interrupted write left, and the folders that have stood empty for an hour, so the folders of
 * tiles long gone do not use up the volume's files either.
 *
 * <p><b>Measuring it.</b> While the cache is on, one line an hour is logged at INFO ({@link #line()}): the
 * folder's size and what was answered since the line before. Its form is fixed, because the deployment's
 * dashboard reads it.
 */
@Component
public class WmsTileCache {

    private static final Logger log = LoggerFactory.getLogger(WmsTileCache.class);

    /** The folder under the home that holds the tiles: a cache on the application's volume */
    static final String CACHE_FOLDER = "wms-tiles";

    static final String TEMP_SUFFIX = ".tmp";

    /** What a file counts for at least towards the cap: 16 KB */
    static final long MIN_CHARGE = 16 * 1024;

    /**
     * The oldest copy served when the provider fails, and the age at which a sweep deletes a tile unless
     * {@code wmsCacheDays} is longer ({@link #keepMillis()})
     */
    static final Duration MAX_STALE = Duration.ofDays(30);

    /** No tile is written while the volume has less free space than this: 256 MB */
    static final long MIN_FREE_BYTES = 256L * 1024 * 1024;

    /** A sweep the cap starts shrinks the folder to this share of the cap */
    static final double SWEEP_TO = 0.9;

    /** The failed fetches in a row that pause the provider */
    static final int FAILURES_TO_PAUSE = 3;

    /** How long a paused provider is not asked */
    static final Duration PAUSE = Duration.ofMinutes(2);

    /** How long a request waits for another request's fetch of the same tile: a little more than a fetch takes */
    static final Duration FETCH_WAIT = Duration.ofSeconds(45);

    /** A temporary file this old was left by an interrupted write and is deleted by the sweep */
    static final long ABANDONED_TEMP_MILLIS = 60L * 60 * 1000;

    /** Where a tile an answer carries came from */
    enum Source {
        /** From the cache, fetched less than {@code wmsCacheDays} days ago */
        HIT,
        /** From the provider, now: fetched for this request, or for the one it waited for */
        MISS,
        /** From the cache, older than {@code wmsCacheDays} days, because the provider could not be asked */
        STALE
    }

    /**
     * A tile to answer with.
     *
     * @param bytes     the PNG
     * @param fetchedAt when it came from the provider (epoch milliseconds)
     * @param source    where this answer has it from
     */
    record Tile(byte[] bytes, long fetchedAt, Source source) {
    }

    /**
     * The provider answered a tile request with something that is no 256 x 256 image, an error document for one.
     * The request gets no tile, and the answer does not count towards the provider's pause: the provider is
     * there, and other tiles may be fine.
     */
    static final class NotATileException extends IOException {
        NotATileException(String message) {
            super(message);
        }
    }

    @Inject
    RepoCache repoCache;

    @Setting(value = "wmsCacheDays", defaultValue = "0", type = Integer,
            description = "Days the backend keeps a sea-chart (WMS) tile on its volume before it asks the provider"
                    + " again; cached tiles go to logged-in users only. 0: no cache. Set it only when the provider's"
                    + " licence allows a cache.")
    volatile Integer wmsCacheDays;

    @Setting(value = "wmsCacheMaxMb", defaultValue = "200", type = Integer,
            description = "The most space, in MB, the sea-chart (WMS) tile cache takes on the application's volume;"
                    + " a tile counts for at least 16 KB, and the oldest go first.")
    volatile Integer wmsCacheMaxMb;

    private final Clock clock;
    private final Path folder;
    private final LongSupplier usableSpace;
    private final Executor sweeper;

    private final Map<WmsTileKey, CompletableFuture<Tile>> inFlight = new ConcurrentHashMap<>();

    // The folder as last counted, kept current by every write and sweep
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong charged = new AtomicLong();
    private final AtomicLong files = new AtomicLong();
    private volatile boolean counted;
    private final AtomicBoolean sweeping = new AtomicBoolean();

    // What was answered since the last line
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder stale = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder bypassed = new LongAdder();
    private final LongAdder evicted = new LongAdder();

    // The provider's pause, under its own lock: a sweep may hold the sweep's for seconds
    private final Object pauseLock = new Object();
    private final Object sweepLock = new Object();
    private int consecutiveFailures;
    private boolean paused;
    private long pausedUntil;

    private volatile long lastFullWarning;

    WmsTileCache() {
        this.clock = Clock.systemUTC();
        this.folder = null;
        this.usableSpace = null;
        // A sweep walks the whole folder: never on the thread of the request whose write started it
        this.sweeper = task -> Thread.ofVirtual().name("wms-tile-sweep").start(MdcContext.withFreshJobId(task));
    }

    /**
     * A cache outside Spring, for tests: the folder, the clock and the volume's free space are given, the two
     * settings are set here, and a sweep runs on the thread that starts it.
     */
    WmsTileCache(Path folder, Clock clock, LongSupplier usableSpace, int days, int maxMb) {
        this(folder, clock, usableSpace, days, maxMb, Runnable::run);
    }

    /** The same, with each sweep handed to the given executor */
    WmsTileCache(Path folder, Clock clock, LongSupplier usableSpace, int days, int maxMb, Executor sweeper) {
        this.clock = clock;
        this.folder = folder;
        this.usableSpace = usableSpace;
        this.sweeper = sweeper;
        this.wmsCacheDays = days;
        this.wmsCacheMaxMb = maxMb;
    }

    /** Whether the cache is switched on: {@code wmsCacheDays} and {@code wmsCacheMaxMb} are both above 0 */
    boolean enabled() {
        return wmsCacheDays != null && wmsCacheDays > 0 && capBytes() > 0;
    }

    /** The folder of the tiles, which need not exist yet */
    Path folder() {
        return folder != null ? folder : repoCache.home().resolve(CACHE_FOLDER);
    }

    private long capBytes() {
        return wmsCacheMaxMb == null ? 0 : Math.max(0, wmsCacheMaxMb) * 1024L * 1024;
    }

    private long maxAgeMillis() {
        return Duration.ofDays(wmsCacheDays).toMillis();
    }

    /**
     * The age at which a sweep deletes a tile: {@link #MAX_STALE}, or {@code wmsCacheDays} when that is longer, so
     * that no tile is deleted while it is fresh. It holds whether or not the cache is on.
     */
    private long keepMillis() {
        long days = wmsCacheDays == null ? 0 : Math.max(0, wmsCacheDays);
        return Math.max(MAX_STALE.toMillis(), Duration.ofDays(days).toMillis());
    }

    /**
     * Returns the tile: the cached copy while it is fresh, else what {@code fetch} gets from the provider, else the
     * older copy when the provider fails or is paused.
     *
     * @param key   the tile
     * @param fetch gets the tile from the provider as the PNG to answer with, and throws when it cannot: a
     *              {@link NotATileException} when the provider answered with something else than a tile, any other
     *              exception when the provider failed. It logs its own failure, since only the caller knows how to
     *              keep the provider's token out of the text
     * @return the tile, or null when there is none to answer with
     */
    Tile get(WmsTileKey key, Callable<byte[]> fetch) {
        Path file = key.file(folder());
        Tile cached = read(file);
        if (cached != null && clock.millis() - cached.fetchedAt() < maxAgeMillis()) {
            hits.increment();
            return cached;
        }

        Tile fetched = fetchOnce(key, file, fetch);
        if (fetched != null) {
            (fetched.source() == Source.HIT ? hits : misses).increment();
            return fetched;
        }
        if (cached != null && clock.millis() - cached.fetchedAt() < MAX_STALE.toMillis()) {
            stale.increment();
            return new Tile(cached.bytes(), cached.fetchedAt(), Source.STALE);
        }
        failed.increment();
        return null;
    }

    /** Counts a request that was proxied without the cache while the cache is on */
    void bypassed() {
        bypassed.increment();
    }

    /**
     * Fetches the tile unless another request is fetching it, in which case this one waits for that fetch.
     *
     * @return the tile, or null when the provider failed, is paused, or the other request's fetch took too long
     */
    private Tile fetchOnce(WmsTileKey key, Path file, Callable<byte[]> fetch) {
        CompletableFuture<Tile> mine = new CompletableFuture<>();
        CompletableFuture<Tile> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return running.get(FETCH_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (ExecutionException | TimeoutException e) {
                return null;
            }
        }

        Tile tile = null;
        try {
            tile = fetchAndStore(file, fetch);
        } finally {
            // The fetch leaves the map before it completes, so no request that comes after it joins a fetch that
            // is over: after a success the tile is in its file, after a failure the next request asks again
            inFlight.remove(key, mine);
            mine.complete(tile);
        }
        return tile;
    }

    private Tile fetchAndStore(Path file, Callable<byte[]> fetch) {
        // Another request may have fetched the tile between this one's look at the file and its turn
        Tile again = read(file);
        long now = clock.millis();
        if (again != null && now - again.fetchedAt() < maxAgeMillis()) {
            return again;
        }
        if (!providerMayBeAsked(now)) {
            return null;
        }
        byte[] png;
        try {
            png = fetch.call();
        } catch (NotATileException e) {
            // The provider answered: this tile has no image, and the provider is not failing
            providerAnswered();
            return null;
        } catch (Exception e) {
            providerFailed(clock.millis());
            return null;
        }
        providerAnswered();
        long fetchedAt = clock.millis();
        write(file, png, fetchedAt);
        return new Tile(png, fetchedAt, Source.MISS);
    }

    // ------------------------------------------------------------------ the provider's pause

    /**
     * Whether a request may go to the provider now. While the provider is paused none may; when the pause is over
     * one request tries the provider, and until its fetch has answered or failed the others still do not ask.
     */
    private boolean providerMayBeAsked(long now) {
        synchronized (pauseLock) {
            if (!paused) {
                return true;
            }
            if (now < pausedUntil) {
                return false;
            }
            pausedUntil = now + FETCH_WAIT.toMillis();
            return true;
        }
    }

    private void providerAnswered() {
        synchronized (pauseLock) {
            consecutiveFailures = 0;
            if (paused) {
                paused = false;
                log.info("The WMS provider answers again; sea-chart tiles are fetched again");
            }
        }
    }

    private void providerFailed(long now) {
        synchronized (pauseLock) {
            consecutiveFailures++;
            if (paused) {
                // The request that tried the provider after a pause failed, or one from before the pause did
                pausedUntil = now + PAUSE.toMillis();
                log.debug("The WMS provider still fails; it is not asked for another {} s", PAUSE.toSeconds());
            } else if (consecutiveFailures >= FAILURES_TO_PAUSE) {
                paused = true;
                pausedUntil = now + PAUSE.toMillis();
                log.warn("The WMS provider failed {} times in a row; it is not asked for {} s at a time until it"
                        + " answers, and a sea-chart tile comes from the cache or is blank",
                        consecutiveFailures, PAUSE.toSeconds());
            }
        }
    }

    // ------------------------------------------------------------------ the files

    /** The cached tile, fresh or not, or null when there is none or it cannot be read */
    private static Tile read(Path file) {
        try {
            long fetchedAt = Files.getLastModifiedTime(file).toMillis();
            return new Tile(Files.readAllBytes(file), fetchedAt, Source.HIT);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            log.debug("Could not read the cached sea-chart tile {}: {}", file, e.toString());
            return null;
        }
    }

    /**
     * Stores a tile. One that cannot be stored is still answered with; the failure is logged, as it defeats the
     * cache.
     */
    private void write(Path file, byte[] png, long fetchedAt) {
        try {
            // The first write after a start has the folder counted, by a sweep off this thread; until the count is
            // done, the totals hold only the writes since the start, and the cap waits for it
            if (!counted) {
                startSweep();
            }
            // The free space is asked of the cache's own folder, which is made for it; the tile's folders only after
            Path root = Files.createDirectories(folder());
            long free = usableSpace != null ? usableSpace.getAsLong() : root.toFile().getUsableSpace();
            if (free < MIN_FREE_BYTES) {
                warnFull(free);
                return;
            }
            Files.createDirectories(file.getParent());
            long replaced = sizeOf(file);
            Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString() + ".", TEMP_SUFFIX);
            try {
                Files.write(temp, png);
                Files.setLastModifiedTime(temp, FileTime.fromMillis(fetchedAt));
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            if (replaced < 0) {
                files.incrementAndGet();
                bytes.addAndGet(png.length);
                charged.addAndGet(charge(png.length));
            } else {
                bytes.addAndGet(png.length - replaced);
                charged.addAndGet(charge(png.length) - charge(replaced));
            }
            if (counted && charged.get() > capBytes()) {
                startSweep();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not cache the sea-chart tile {}: {}", file, e.toString());
        }
    }

    /** The file's size, or -1 when there is none */
    private static long sizeOf(Path file) throws IOException {
        try {
            return Files.size(file);
        } catch (NoSuchFileException e) {
            return -1;
        }
    }

    /** What a file of the given size counts for towards the cap */
    static long charge(long size) {
        return Math.max(size, MIN_CHARGE);
    }

    /** Says at most once an hour that the volume is too full to cache on */
    private void warnFull(long free) {
        long now = clock.millis();
        if (now - lastFullWarning >= Duration.ofHours(1).toMillis()) {
            lastFullWarning = now;
            log.warn("The volume has {} MB free, under the {} MB the sea-chart tile cache leaves alone: tiles are"
                    + " not cached until there is room", free / (1024 * 1024), MIN_FREE_BYTES / (1024 * 1024));
        }
    }

    // ------------------------------------------------------------------ the sweep

    /** Starts a sweep unless one is running */
    private void startSweep() {
        if (!sweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            sweeper.execute(() -> {
                try {
                    sweep(false);
                } catch (IOException | RuntimeException e) {
                    log.warn("Could not sweep the sea-chart tile cache {}: {}", folder(), e.toString());
                } finally {
                    sweeping.set(false);
                }
            });
        } catch (RuntimeException e) {
            sweeping.set(false);
            log.warn("Could not start a sweep of the sea-chart tile cache: {}", e.toString());
        }
    }

    /**
     * Counts the folder unless a sweep has since the start: the hourly line may come before the first write, and
     * waits for a count that is running.
     */
    private void ensureCounted() throws IOException {
        if (!counted) {
            synchronized (sweepLock) {
                if (!counted) {
                    sweepLocked(false);
                }
            }
        }
    }

    /**
     * Counts the folder and shrinks it: deletes the temporary files an interrupted write left and the tiles past the
     * age a tile is kept ({@link #keepMillis()}), and then, when the rest is over the cap, the least recently
     * fetched tiles down to {@link #SWEEP_TO} of the cap. The totals are set to what is left. A tile written while
     * the sweep walks the folder may be missing from them until the next sweep.
     *
     * @param tidy whether the folders that have stood empty for an hour are removed too, as the nightly sweep does
     * @return the number of tiles the cap removed
     */
    int sweep(boolean tidy) throws IOException {
        synchronized (sweepLock) {
            return sweepLocked(tidy);
        }
    }

    private int sweepLocked(boolean tidy) throws IOException {
        Path root = folder();
        long now = clock.millis();
        long keep = keepMillis();
        List<SweptTile> tiles = new ArrayList<>();
        List<Path> folders = new ArrayList<>();
        if (Files.isDirectory(root)) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (!attributes.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = file.getFileName().toString();
                    long modified = attributes.lastModifiedTime().toMillis();
                    if (name.endsWith(TEMP_SUFFIX)) {
                        if (now - modified > ABANDONED_TEMP_MILLIS) {
                            delete(file);
                        }
                    } else if (name.endsWith(WmsTileKey.TILE_SUFFIX)
                            && !(now - modified >= keep && delete(file))) {
                        tiles.add(new SweptTile(file, attributes.size(), modified));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // A file that went while the folder was walked: a temporary file moved into place, a tile
                    // another request replaced. The walk goes on without it
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path folder, IOException e) {
                    // A folder comes after everything in it, so the list has the deepest folders first
                    if (!folder.equals(root)) {
                        folders.add(folder);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        long total = 0;
        long size = 0;
        for (SweptTile tile : tiles) {
            total += charge(tile.size());
            size += tile.size();
        }

        int removed = 0;
        long kept = tiles.size();
        long cap = capBytes();
        if (total > cap) {
            long target = (long) (cap * SWEEP_TO);
            tiles.sort(Comparator.comparingLong(SweptTile::modified));
            for (SweptTile tile : tiles) {
                if (total <= target) {
                    break;
                }
                if (delete(tile.file())) {
                    total -= charge(tile.size());
                    size -= tile.size();
                    kept--;
                    removed++;
                }
            }
            evicted.add(removed);
            log.info("Sea-chart tile cache {}: over its cap of {} MB, removed the {} oldest tiles", root,
                    cap / (1024 * 1024), removed);
        }
        if (tidy) {
            folders.forEach(folder -> deleteIfLongEmpty(folder, now));
        }
        bytes.set(size);
        charged.set(total);
        files.set(kept);
        counted = true;
        return removed;
    }

    /**
     * Removes a folder that is empty and was last changed over an hour ago. A folder a write has just made for its
     * tile is younger than that, so no write loses its folder to a sweep; a folder this sweep has just emptied is
     * too, and goes at a later sweep.
     */
    private static void deleteIfLongEmpty(Path folder, long now) {
        try {
            if (now - Files.getLastModifiedTime(folder).toMillis() > ABANDONED_TEMP_MILLIS) {
                Files.delete(folder);
            }
        } catch (IOException e) {
            // Not empty, or gone: either way it is not this sweep's to remove
        }
    }

    private record SweptTile(Path file, long size, long modified) {
    }

    /** Deletes a file; one that cannot be deleted now is left for the next sweep */
    private static boolean delete(Path file) {
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            log.debug("Could not delete the cached sea-chart tile file {}: {}", file, e.toString());
            return false;
        }
    }

    /** Every night: counts the folder again and deletes what is too old, whether or not the cache is on */
    @Scheduled(cron = "17 52 3 * * ?")
    void sweepNightly() {
        try {
            if (Files.isDirectory(folder())) {
                sweep(true);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not sweep the sea-chart tile cache: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ the hourly line

    /** Every hour while the cache is on: the folder's size and what was answered since the line before */
    @Scheduled(cron = "0 7 * * * ?")
    void logLine() {
        try {
            if (enabled()) {
                log.info(line());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not report on the sea-chart tile cache: {}", e.toString());
        }
    }

    /**
     * The line, and a new start for its counters. The numbers are {@code key=value} pairs after a fixed prefix:
     * <ul>
     *     <li>{@code bytes}: the tiles' sizes together;</li>
     *     <li>{@code charged}: what they count for towards the cap, {@link #MIN_CHARGE} a tile at least;</li>
     *     <li>{@code files}: the number of tiles;</li>
     *     <li>{@code cap}: the cap in bytes;</li>
     *     <li>and since the line before: {@code hits} (answered from the cache), {@code misses} (answered with a
     *     tile fetched from the provider just now, for this request or for the one it waited for: one fetch can
     *     answer several requests, so this is no count of the requests to the provider), {@code stale} (an older
     *     copy, the provider failing), {@code failed} (no tile at all), {@code bypassed} (proxied without the
     *     cache) and {@code evicted} (tiles the cap removed; above 0 the cap is too small to show how much space
     *     the tiles need).</li>
     * </ul>
     * Every request the cache answers is one of {@code hits}, {@code misses}, {@code stale} and {@code failed}.
     */
    String line() throws IOException {
        ensureCounted();
        return "WMS tile cache: bytes=" + bytes.get()
                + " charged=" + charged.get()
                + " files=" + files.get()
                + " cap=" + capBytes()
                + " hits=" + hits.sumThenReset()
                + " misses=" + misses.sumThenReset()
                + " stale=" + stale.sumThenReset()
                + " failed=" + failed.sumThenReset()
                + " bypassed=" + bypassed.sumThenReset()
                + " evicted=" + evicted.sumThenReset();
    }
}
