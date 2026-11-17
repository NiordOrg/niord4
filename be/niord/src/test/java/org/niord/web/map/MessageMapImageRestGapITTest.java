/*
 * Copyright 2024 Danish Maritime Authority.
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

import io.restassured.response.Response;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.geojson.Feature;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.geojson.JtsConverter;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.repo.RepoCache;
import org.niord.core.repo.RepositoryService;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import javax.imageio.ImageIO;
import org.niord.core.repo.RepoStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the read endpoint {@link MessageMapImageRestService#getMessageMapImage}, complementing
 * {@link MessageMapImageRestServiceITTest} (the EDITOR write-endpoint authorization boundaries). They drive the
 * endpoint's decisions over committed messages, real repository rows and real files of the cache tree:
 *
 * <ul>
 *   <li>a custom thumbnail wins, and one outside the repository is ignored;</li>
 *   <li>the generated image named with the current portrayal version, drawn after the message last changed, is
 *   served as it is;</li>
 *   <li>an image drawn with an earlier portrayal (the name without a version here) after the message last
 *   changed is served at once with a short expiry, while exactly one background generation, which also reads
 *   where the tiles come from, draws the current one, which the next request gets; the name without a version is
 *   looked at first, and an image of another version is served when that one is missing or older;</li>
 *   <li>when no image shows the message as it is now, the image is drawn while the request waits, once for
 *   concurrent requests, and the superseded images are deleted after the cleanup delay;</li>
 *   <li>a message without geometry, a generator that fails and one that has nothing to draw give the
 *   placeholder image itself, a {@code 200} marked with {@link MessageMapImageRestService#IMAGE_KIND_HEADER}
 *   rather than a redirect; an unknown UID gives a {@code 404}.</li>
 * </ul>
 *
 * <p><b>The generator is stood in for.</b> The drawing has tests of its own, and
 * {@link MessageMapImageEndToEndITTest} drives the endpoint with the real generator; here
 * {@link MapImageRegenerator}'s generator is replaced, on the running bean, by a stand-in that counts its calls and
 * writes a small PNG stamped with the request's change time, as the real generator stamps its image. The endpoint
 * still asks the real generator for the file name, so the names are the production ones. The fields are restored
 * after each test; the Spring context stays the shared one.
 *
 * <p><b>Why committed data.</b> The endpoint reads the message in a transaction of its own, so it can only see
 * rows that have been committed. These tests therefore do NOT use {@code @Transactional}; they create each
 * message through {@link MessageService#createMessage(Message)} (which commits, since it runs outside an
 * enclosing transaction), track the generated id, and remove the row in {@link #cleanup()} in a fresh
 * transaction. Every test uses a unique {@code IT-} prefixed title. The generated images live in the cache tree
 * under the throwaway {@code niord.home} temp dir, the custom thumbnails are rows of the repository store, and
 * both are deleted in {@link #cleanup()}.
 *
 * <p>The {@code GET} endpoint is {@code @PermitAll}, so every request below is anonymous.
 * {@code redirects().follow(false)} keeps RestAssured from chasing the {@code 307} to a custom thumbnail so the
 * {@code Location} and {@code Expires} headers are observable.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageMapImageRestGapITTest extends ITTest {

    private static final String BASE = "/rest/message-map-image";

    @Autowired
    MessageService messageService;

    @Autowired
    RepositoryService repositoryService;

    @Autowired
    RepoStore store;

    @Autowired
    RepoCache repoCache;

    @Autowired
    MessageMapImageGenerator messageMapImageGenerator;

    @Autowired
    MapImageRegenerator mapImageRegenerator;

    @PersistenceContext
    EntityManager em;

    /** Committed message ids created by a test, removed in {@link #cleanup()}. */
    private final List<Integer> createdMessageIds = new ArrayList<>();

    /** Absolute repository paths created by a test, deleted best-effort after it. */
    private final List<Path> createdPaths = new ArrayList<>();

    /** Repository rows written by a test, deleted after it */
    private final List<String> createdRepoPaths = new ArrayList<>();

    /** The running regenerator bean, whose generator and cleanup delay the tests replace */
    private MapImageRegenerator regenerator;
    private MessageMapImageGenerator realGenerator;
    private Duration realCleanupDelay;

    /** Generations per image file */
    private final Map<Path, AtomicInteger> generations = new ConcurrentHashMap<>();

    /** What the stand-in generator does; by default it writes the image */
    private volatile Outcome outcome = Outcome.WRITE;

    /** When set, a generation waits for it to open */
    private volatile CountDownLatch gate;

    /** Opened when a generation starts */
    private final CountDownLatch generationStarted = new CountDownLatch(1);

    /** The threads that resolved a generation's tile source, and what they resolved */
    private final List<String> tilesResolvedOn = new CopyOnWriteArrayList<>();
    private final List<TileSource> tilesResolved = new CopyOnWriteArrayList<>();

    private enum Outcome { WRITE, NOTHING_TO_DRAW, FAIL }

    @BeforeEach
    void standInForTheGenerator() throws IOException {
        MessageMapImageGenerator standIn = mock(MessageMapImageGenerator.class);
        when(standIn.generate(any(), any())).thenAnswer(invocation -> {
            MapImageRequest request = invocation.getArgument(0);
            Path target = invocation.getArgument(1);
            generations.computeIfAbsent(target, t -> new AtomicInteger()).incrementAndGet();
            // As the real generator does, the tile source is read by the thread that draws the image
            tilesResolvedOn.add(Thread.currentThread().getName());
            tilesResolved.add(request.tiles().get());
            generationStarted.countDown();
            CountDownLatch waitFor = gate;
            if (waitFor != null && !waitFor.await(10, TimeUnit.SECONDS)) {
                throw new IOException("the test never opened the gate");
            }
            switch (outcome) {
                case NOTHING_TO_DRAW:
                    return false;
                case FAIL:
                    throw new TileUnavailableException("IT: the tiles are not available");
                default:
                    track(target);
                    Files.createDirectories(target.getParent());
                    ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", target.toFile());
                    Files.setLastModifiedTime(target, FileTime.fromMillis(request.updatedMillis()));
                    return true;
            }
        });

        regenerator = AopTestUtils.getTargetObject(mapImageRegenerator);
        realGenerator = regenerator.generator;
        realCleanupDelay = regenerator.cleanupDelay;
        regenerator.generator = standIn;
    }

    @AfterEach
    void cleanup() {
        CountDownLatch open = gate;
        if (open != null) {
            open.countDown();
        }
        regenerator.generator = realGenerator;
        regenerator.cleanupDelay = realCleanupDelay;

        for (Integer id : createdMessageIds) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                Message m = em.find(Message.class, id);
                if (m != null) {
                    em.remove(m);
                }
            });
        }
        createdMessageIds.clear();

        for (Path p : createdPaths) {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignored) {
                // best-effort cleanup only
            }
        }
        createdPaths.clear();

        for (String path : createdRepoPaths) {
            store.delete(path);
        }
        createdRepoPaths.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Builds a detached DRAFT NW message template with a single DETAILS part and an "en" title. */
    private Message newDraft(String title) {
        Message msg = new Message();
        // createMessage substitutes the persisted series looked up by id.
        msg.setMessageSeries(new MessageSeries("dma-nw"));
        msg.setType(Type.COASTAL_WARNING); // mainType is derived from the type
        msg.setStatus(Status.DRAFT);
        msg.setAutoTitle(false);

        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("<p>details</p>");
        msg.addPart(part);

        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /** Attaches a single-point geometry (lat/lon) to the first part, so {@code toGeoJson()} is non-empty. */
    private void addPointGeometry(Message msg, double lat, double lon) {
        FeatureCollection fc = new FeatureCollection();
        Feature f = new Feature();
        f.setGeometry(JtsConverter.toJtsPoint(lat, lon));
        fc.addFeature(f);
        msg.getParts().get(0).setGeometry(fc);
    }

    /** Creates a COMMITTED message (createMessage commits since it runs outside a tx) and tracks its id. */
    private Message createCommitted(Message template) throws Exception {
        Message created = messageService.createMessage(template);
        createdMessageIds.add(created.getId());
        return created;
    }

    /** A committed message with a point geometry. */
    private Message messageWithGeometry(String name) throws Exception {
        Message template = newDraft("IT-MAP-" + name + "-" + System.nanoTime());
        addPointGeometry(template, 56.0, 10.0);
        return createCommitted(template);
    }

    /** The message's change time as the endpoint reads it: the committed value. */
    private long committedUpdatedMillis(Message msg) {
        return Tx.requiringNew().call(() -> em.find(Message.class, msg.getId()).getUpdated().getTime());
    }

    /**
     * The message folder's place under the cache tree, where its generated images live, created on disk. The
     * individual files placed in it are tracked for deletion; the empty directory survives under the throwaway
     * {@code niord.home} temp root.
     */
    private Path messageRepoDir(Message msg) throws Exception {
        Path dir = repoCache.pathFor(msg.getRepoPath());
        Files.createDirectories(dir);
        return dir;
    }

    /** The generated image the endpoint serves for the message: named with the current portrayal version. */
    private Path currentImage(Message msg) throws Exception {
        return track(messageRepoDir(msg).resolve(messageMapImageGenerator.imageFileName()));
    }

    /** The name the generated images had before the portrayal version became part of it. */
    private Path unversionedImage(Message msg) throws Exception {
        return track(messageRepoDir(msg).resolve("map_" + imageSize() + ".png"));
    }

    /** The size in the current image's name, which the other images of the message's folder share. */
    private String imageSize() {
        Matcher name = MapImageFiles.GENERATED.matcher(messageMapImageGenerator.imageFileName());
        assertTrue(name.matches(), "precondition: the generator names its images map_<size>_<version>.png");
        return name.group(1);
    }

    /** Writes a file with the given bytes and modification time. */
    private static void seed(Path file, byte[] bytes, long modifiedMillis) throws IOException {
        Files.write(file, bytes);
        Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedMillis));
    }

    /** Registers an absolute file path for best-effort deletion after the test. */
    private Path track(Path absolutePath) {
        if (!createdPaths.contains(absolutePath)) {
            createdPaths.add(absolutePath);
        }
        return absolutePath;
    }

    /** Registers a repository row for deletion after the test. */
    private String trackRow(String repoPath) {
        createdRepoPaths.add(repoPath);
        return repoPath;
    }

    private int generationsOf(Path target) {
        AtomicInteger count = generations.get(target);
        return count == null ? 0 : count.get();
    }

    /** The endpoint's answer for the message, a redirect not followed */
    private static Response getImage(Message msg) {
        return given()
                .redirects().follow(false)
                .when().get(BASE + "/" + msg.getUid() + ".png")
                .then()
                .extract().response();
    }

    /** The placeholder image's bytes, as the backend serves its static file */
    private static byte[] placeholderFile() {
        return given()
                .when().get("/img/map_image_placeholder.png")
                .then().statusCode(200).contentType("image/png")
                .extract().asByteArray();
    }

    /** The seconds between the response's Date and Expires headers. */
    private static long expirySeconds(Response response) {
        String expires = response.getHeader("Expires");
        String date = response.getHeader("Date");
        assertNotNull(expires, "the response must carry an Expires header");
        assertNotNull(date, "the response must carry a Date header");
        return ZonedDateTime.parse(expires, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
                - ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out waiting until " + what);
            }
            Thread.sleep(20);
        }
    }

    /** A custom thumbnail, a repository row, is redirected to */
    private void assertRedirectsTo(String repoPath, Response response) {
        String expected = repositoryService.getRepoUri(repoPath);
        assertEquals(307, response.statusCode(), "expected a redirect to " + expected);
        String location = response.getHeader("Location");
        assertNotNull(location, "a 307 redirect must carry a Location header");
        assertTrue(location.endsWith(expected), "expected a redirect to " + expected + ", was: " + location);
    }


    /**
     * The placeholder is answered, not redirected to: a PNG marked as the placeholder, expiring as a current image
     * does
     */
    private static void assertPlaceholder(Response response) {
        assertEquals(200, response.statusCode(), "the placeholder is the endpoint's own answer, not a redirect");
        assertNull(response.getHeader("Location"));
        assertEquals("image/png", response.getContentType());
        assertEquals(MessageMapImageRestService.PLACEHOLDER,
                response.getHeader(MessageMapImageRestService.IMAGE_KIND_HEADER));
        long expiry = expirySeconds(response);
        assertTrue(expiry >= 590 && expiry <= 610, "the placeholder expires in 10 minutes, was " + expiry + " s");
        assertTrue(response.asByteArray().length > 0, "the placeholder has a body");
    }

    // ------------------------------------------------------------------------------------------- //
    // Custom thumbnails
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getMapImage_redirectsToExistingCustomThumbnailFile() throws Exception {
        // A message with geometry and a custom thumbnail: the thumbnail wins, and nothing is drawn
        Message msg = messageWithGeometry("thumb");

        String thumbnailPath = trackRow(msg.getRepoPath() + "/custom_thumb_it.png");
        store.write(thumbnailPath, new byte[]{(byte) 0x89, 'P', 'N', 'G'}, Instant.now());
        Tx.requiringNew().run(() -> {
            Message m = em.find(Message.class, msg.getId());
            m.setThumbnailPath(thumbnailPath);
        });

        // getRepoUri produces "/rest/repo/file/<repo-relative path>"
        String expectedRepoUri = repositoryService.getRepoUri(thumbnailPath);
        assertTrue(expectedRepoUri.startsWith("/rest/repo/file/"),
                "precondition: the repo URI must be a repo-streaming URI, was: " + expectedRepoUri);

        Response response = getImage(msg);

        assertRedirectsTo(thumbnailPath, response);
        assertEquals(0, generationsOf(currentImage(msg)), "a custom thumbnail is served without drawing");
        assertFalse(Files.exists(currentImage(msg)));
    }

    @Test
    void getMapImage_servesTheGeneratedImageWhenTheCustomThumbnailIsMissing() throws Exception {
        Message msg = messageWithGeometry("thumb-missing");
        String thumbnailPath = msg.getRepoPath() + "/custom_thumb_gone.png";
        Tx.requiringNew().run(() -> em.find(Message.class, msg.getId()).setThumbnailPath(thumbnailPath));

        Path current = currentImage(msg);
        seed(current, "IT-CURRENT".getBytes(StandardCharsets.UTF_8), committedUpdatedMillis(msg));

        MapImageTestSupport.assertServes(current, getImage(msg));
        assertEquals(0, generationsOf(current));
    }

    @Test
    void getMapImage_ignoresACustomThumbnailPathOutsideTheRepository() throws Exception {
        Message msg = createCommitted(newDraft("IT-MAP-outside-" + System.nanoTime()));

        // A real row of the repository, named through a path that climbs out of the repository and back in
        String outside = trackRow("messages/it-map-outside-" + System.nanoTime() + ".png");
        store.write(outside, new byte[]{(byte) 0x89, 'P', 'N', 'G'}, Instant.now());
        String thumbnailPath = "../" + outside;
        Tx.requiringNew().run(() -> {
            Message m = em.find(Message.class, msg.getId());
            m.setThumbnailPath(thumbnailPath);
        });

        assertPlaceholder(getImage(msg));
    }

    // ------------------------------------------------------------------------------------------- //
    // Generated images
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getMapImage_withGeometryAndFreshMapFileServesItWithoutOverwriting() throws Exception {
        Message msg = messageWithGeometry("fresh");

        // The current image, drawn for the message as it is now (its time the message's change time), holding
        // sentinel bytes that any regeneration would change
        Path current = currentImage(msg);
        byte[] sentinel = "IT-FRESH-MAP-IMAGE-SENTINEL".getBytes(StandardCharsets.UTF_8);
        seed(current, sentinel, committedUpdatedMillis(msg));
        long mtimeBefore = Files.getLastModifiedTime(current).toMillis();

        Response response = getImage(msg);

        MapImageTestSupport.assertServes(current, response);
        long expiry = expirySeconds(response);
        assertTrue(expiry >= 590 && expiry <= 610, "the current image expires in 10 minutes, was " + expiry + " s");
        assertFalse(regenerator.inFlight(current), "a current image starts no generation");
        assertEquals(0, generationsOf(current));
        assertArrayEquals(sentinel, Files.readAllBytes(current), "a fresh map image must be served verbatim");
        assertEquals(mtimeBefore, Files.getLastModifiedTime(current).toMillis(),
                "a fresh map image must not be rewritten");
    }

    @Test
    void getMapImage_servesTheSupersededImageAndDrawsTheCurrentOneInTheBackground() throws Exception {
        Message msg = messageWithGeometry("superseded");
        long updated = committedUpdatedMillis(msg);

        // The image under the name without a version, drawn for the message as it is now
        Path unversioned = unversionedImage(msg);
        seed(unversioned, "IT-UNVERSIONED".getBytes(StandardCharsets.UTF_8), updated);
        Path current = currentImage(msg);

        Response first = getImage(msg);

        MapImageTestSupport.assertServes(unversioned, first);
        long expiry = expirySeconds(first);
        assertTrue(expiry >= 55 && expiry <= 65, "the superseded image expires in a minute, was " + expiry + " s");

        awaitTrue("the background generation ends", () -> !regenerator.inFlight(current) && Files.exists(current));
        assertEquals(1, generationsOf(current), "exactly one background generation");
        assertEquals(updated, Files.getLastModifiedTime(current).toMillis(),
                "the image carries the message's change time");
        assertEquals(1, tilesResolvedOn.size());
        assertTrue(tilesResolvedOn.getFirst().startsWith("map-image-"),
                "the background generation reads the tile source, not the request: " + tilesResolvedOn);
        assertNotNull(tilesResolved.getFirst().urlTemplate(), "the settings' tile source");

        Response second = getImage(msg);

        MapImageTestSupport.assertServes(current, second);
        long secondExpiry = expirySeconds(second);
        assertTrue(secondExpiry >= 590 && secondExpiry <= 610, "was " + secondExpiry + " s");
        assertEquals(1, generationsOf(current), "the current image is served without drawing again");
        assertTrue(Files.exists(unversioned), "the superseded image outlives the responses that served it");
    }

    /**
     * When the image under the name without a version is not the message's as it is now, the folder is looked
     * through: an image of another version drawn for the message as it is now is served while the current one is
     * drawn
     */
    @Test
    void getMapImage_servesAFreshImageOfAnotherVersionWhenTheUnversionedOneIsOutdated() throws Exception {
        Message msg = messageWithGeometry("other-version");
        long updated = committedUpdatedMillis(msg);

        Path unversioned = unversionedImage(msg);
        seed(unversioned, "IT-OUTDATED".getBytes(StandardCharsets.UTF_8), updated - 60_000L);
        Path olderVersion = track(messageRepoDir(msg).resolve("map_" + imageSize() + "_000000000000.png"));
        seed(olderVersion, "IT-OLDER-VERSION".getBytes(StandardCharsets.UTF_8), updated - 1_000L);
        Path otherVersion = track(messageRepoDir(msg).resolve("map_" + imageSize() + "_111111111111.png"));
        seed(otherVersion, "IT-OTHER-VERSION".getBytes(StandardCharsets.UTF_8), updated);
        Path current = currentImage(msg);

        Response response = getImage(msg);

        MapImageTestSupport.assertServes(otherVersion, response);
        long expiry = expirySeconds(response);
        assertTrue(expiry >= 55 && expiry <= 65, "the superseded image expires in a minute, was " + expiry + " s");
        awaitTrue("the background generation ends", () -> !regenerator.inFlight(current) && Files.exists(current));
        assertEquals(1, generationsOf(current));
    }

    /** The unversioned image is served when it shows the message as it is now, whatever else the folder holds */
    @Test
    void getMapImage_servesAFreshUnversionedImageBeforeAnyOtherVersion() throws Exception {
        Message msg = messageWithGeometry("unversioned-first");
        long updated = committedUpdatedMillis(msg);

        Path unversioned = unversionedImage(msg);
        seed(unversioned, "IT-UNVERSIONED".getBytes(StandardCharsets.UTF_8), updated);
        Path otherVersion = track(messageRepoDir(msg).resolve("map_" + imageSize() + "_111111111111.png"));
        seed(otherVersion, "IT-OTHER-VERSION".getBytes(StandardCharsets.UTF_8), updated);
        Path current = currentImage(msg);

        MapImageTestSupport.assertServes(unversioned, getImage(msg));
        awaitTrue("the background generation ends", () -> !regenerator.inFlight(current) && Files.exists(current));
    }

    @Test
    void getMapImage_drawsTheImageWhileTheRequestWaitsWhenTheMessageChangedAfterEveryImage() throws Exception {
        Message msg = messageWithGeometry("edited");
        long updated = committedUpdatedMillis(msg);

        Path unversioned = unversionedImage(msg);
        seed(unversioned, "IT-OUTDATED".getBytes(StandardCharsets.UTF_8), updated - 60_000L);
        Path current = currentImage(msg);

        Response response = getImage(msg);

        MapImageTestSupport.assertServes(current, response);
        assertTrue(Files.exists(current), "the image was drawn before the response");
        assertEquals(1, generationsOf(current));
        assertEquals(updated, Files.getLastModifiedTime(current).toMillis());
    }

    @Test
    void getMapImage_drawsAMissingImageOnceForConcurrentRequests() throws Exception {
        Message msg = messageWithGeometry("concurrent");
        Path current = currentImage(msg);
        gate = new CountDownLatch(1);

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + BASE + "/" + msg.getUid() + ".png")).GET().build();

        CompletableFuture<HttpResponse<Void>> first = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        assertTrue(generationStarted.await(15, TimeUnit.SECONDS), "the first request draws the image");
        CompletableFuture<HttpResponse<Void>> second = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        // Give the second request time to reach the generation in flight; arriving later, it would find the
        // image drawn and pass as well, so the pause can only hide a failure, never cause one
        Thread.sleep(500);
        gate.countDown();

        for (CompletableFuture<HttpResponse<Void>> pending : List.of(first, second)) {
            HttpResponse<Void> response = pending.get(15, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(), "the drawn image is served to both requests");
            assertEquals("image/png", response.headers().firstValue("Content-Type").orElse(""));
        }
        assertEquals(1, generationsOf(current), "concurrent requests share one generation");
    }

    @Test
    void getMapImage_deletesTheSupersededImagesAfterTheCleanupDelay() throws Exception {
        regenerator.cleanupDelay = Duration.ofMillis(200);
        Message msg = messageWithGeometry("cleanup");
        long updated = committedUpdatedMillis(msg);

        Path unversioned = unversionedImage(msg);
        seed(unversioned, "IT-OUTDATED".getBytes(StandardCharsets.UTF_8), updated - 60_000L);
        Path otherVersion = track(messageRepoDir(msg).resolve("map_" + imageSize() + "_000000000000.png"));
        seed(otherVersion, "IT-OTHER-VERSION".getBytes(StandardCharsets.UTF_8), updated - 60_000L);
        Path customThumbnail = track(messageRepoDir(msg).resolve("custom_thumb_" + imageSize() + ".png"));
        seed(customThumbnail, "IT-CUSTOM".getBytes(StandardCharsets.UTF_8), updated - 60_000L);
        Path current = currentImage(msg);

        MapImageTestSupport.assertServes(current, getImage(msg));

        awaitTrue("the superseded images are deleted",
                () -> !Files.exists(unversioned) && !Files.exists(otherVersion));
        assertTrue(Files.exists(current));
        assertTrue(Files.exists(customThumbnail), "a custom thumbnail is not a generated image");
    }

    // ------------------------------------------------------------------------------------------- //
    // Placeholder and 404
    // ------------------------------------------------------------------------------------------- //

    /**
     * The placeholder loads wherever the endpoint does: its answer is the image itself, the one the static file
     * holds, so a front that forwards only {@code /rest} (the dev server's proxy) shows it too
     */
    @Test
    void getMapImage_withoutGeometryOrThumbnailAnswersThePlaceholderImageItself() throws Exception {
        Message msg = createCommitted(newDraft("IT-MAP-placeholder-" + System.nanoTime()));

        Response response = getImage(msg);

        assertPlaceholder(response);
        byte[] png = response.asByteArray();
        assertArrayEquals(placeholderFile(), png, "the answer is the static placeholder file's image");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "the placeholder is a PNG");
        assertEquals(0, generations.size(), "nothing is drawn for a message without a geometry");
    }

    @Test
    void getMapImage_givesThePlaceholderAndNoFileWhenTheImageCannotBeMade() throws Exception {
        Message msg = messageWithGeometry("failing");
        Path current = currentImage(msg);
        outcome = Outcome.FAIL;

        assertPlaceholder(getImage(msg));

        assertEquals(1, generationsOf(current));
        assertFalse(Files.exists(current));
        assertFalse(regenerator.inFlight(current));
    }

    @Test
    void getMapImage_givesThePlaceholderWhenThereIsNothingToDraw() throws Exception {
        Message msg = messageWithGeometry("nothing");
        Path current = currentImage(msg);
        outcome = Outcome.NOTHING_TO_DRAW;

        assertPlaceholder(getImage(msg));

        assertEquals(1, generationsOf(current));
        assertFalse(Files.exists(current));
    }

    @Test
    void getMapImage_unknownUidAnswers404WithABody() {
        String uid = "IT-NO-SUCH-UID-" + System.nanoTime();

        String body = given()
                .redirects().follow(false)
                .when().get(BASE + "/" + uid + ".png")
                .then().statusCode(404)
                .extract().asString();

        assertEquals("Message map image not found for message: " + uid, body);
        assertTrue(generations.isEmpty(), "nothing is drawn for an unknown message");
    }
}
