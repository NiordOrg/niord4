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

import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.parser.ImageRenderInfo;
import com.itextpdf.text.pdf.parser.PdfImageObject;
import com.itextpdf.text.pdf.parser.PdfReaderContentParser;
import com.itextpdf.text.pdf.parser.PdfTextExtractor;
import com.itextpdf.text.pdf.parser.RenderListener;
import com.itextpdf.text.pdf.parser.TextRenderInfo;
import io.restassured.response.Response;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.geojson.Feature;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.geojson.JtsConverter;
import org.niord.core.message.Message;
import org.niord.core.message.MessageService;
import org.niord.core.portrayal.PortrayalDefinition;
import org.niord.core.repo.RepoCache;
import org.niord.model.message.Status;
import org.niord.test.ITTest;
import org.niord.test.MessageTestFactory;
import org.niord.test.Tx;
import org.niord.test.http.HttpStub;
import org.niord.test.http.HttpStub.RecordedRequest;
import org.niord.test.http.HttpStub.StubResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The message map images end to end: {@code GET /rest/message-map-image/{uid}.png} over committed messages, drawn
 * by the real renderer ({@link MessageMapImageGenerator}: {@link MapImagePlanner}, {@link TileFetcher},
 * {@link MapImagePainter}) on the tiles of an {@link HttpStub} tile server on the loopback, and a message PDF that
 * shows such an image. {@link MessageMapImageRestGapITTest} drives the endpoint's decisions with a stand-in for the
 * generator, and the renderer's unit tests draw without the application; this test proves that the pieces meet:
 *
 * <ul>
 *   <li>a point message and an area message are drawn on the tile server's tiles with the portrayal's magenta at
 *   the centre of the image, into the file named with the running portrayal definition's image version, stamped
 *   with the message's change time, and the response serves that PNG;</li>
 *   <li>the tile server is asked for one to four tiles (the seeded image size, 256 pixels, spans at most two by
 *   two), each with the backend's {@code Niord/} User-Agent naming the configured contact, and the tiles are kept
 *   in the configured tile cache;</li>
 *   <li>the image is served again from its file, and another message at the same place is drawn from the cached
 *   tiles, without asking the tile server again;</li>
 *   <li>when the tile server fails, the request gets the placeholder image itself, and no file is
 *   written;</li>
 *   <li>a message PDF with map thumbnails embeds the drawn image and carries the OpenStreetMap credit.</li>
 * </ul>
 *
 * <p><b>Settings.</b> The tile address ({@code osmUrl}), the contact ({@code mapTileContact}), the tile cache
 * folder ({@code mapTileCachePath}) and the {@code baseUri} are read live on every request, and a system property of
 * the same name wins over the stored value ({@code SettingsService.get}). Each test sets the ones it needs as
 * system properties, with a path of its own on the tile server and a cache folder of its own, and restores them
 * afterwards: the shared Spring context and the setting rows stay as they were, no test is served another test's
 * tiles from the renderer's memory or disk, and the order of the tests does not matter. The tile server that fails
 * is a stub of its own: the renderer pauses a failing host for two minutes, and that must not be the host the other
 * tests use. No test reaches a public tile server.
 *
 * <p><b>Committed data.</b> The endpoint reads the message in a transaction of its own, so, as in
 * {@link MessageMapImageRestGapITTest}, the messages are committed through {@link MessageService#createMessage}
 * and removed afterwards, together with the images drawn for them and the test's tile cache folder.
 */
class MessageMapImageEndToEndITTest extends ITTest {

    private static final String BASE = "/rest/message-map-image";

    /** The contact the tile requests name, set for every test */
    private static final String CONTACT = "niord-it@example.org";

    /** OpenStreetMap's credit line, which a PDF showing a map image carries */
    private static final String CREDIT = "© OpenStreetMap contributors";

    /** The magenta of the portrayal's pens and symbols (IHO S-124 CHMGD) */
    private static final Color MAGENTA = new Color(0xC0, 0x45, 0xD1);

    /** How far a channel of a pixel may lie from the magenta and still count as magenta */
    private static final int TOLERANCE = 10;

    /**
     * The magenta pixels that show a symbol or a tag: an opaque symbol at the print sizes covers well over a
     * hundred pixels, anti-aliased edges not counted
     */
    private static final int MIN_MAGENTA_PIXELS = 20;

    /** The colour of the stub's tiles: OpenStreetMap's sea, which holds no magenta */
    private static final Color SEA = new Color(0xAA, 0xD3, 0xDF);

    /** A tile address as the tests configure it: a test's own path, then {z}/{x}/{y}.png */
    private static final Pattern TILE_PATH = Pattern.compile("/[^/]+/\\d+/\\d+/\\d+\\.png");

    private static HttpStub tileServer;
    private static HttpStub failingTileServer;

    @Autowired
    MessageService messageService;

    @Autowired
    RepoCache repoCache;

    @Autowired
    MessageMapImageGenerator messageMapImageGenerator;

    @Autowired
    MapImageRegenerator mapImageRegenerator;

    @Autowired
    TileFetcher tileFetcher;

    @Autowired
    PortrayalDefinition portrayal;

    @PersistenceContext
    EntityManager em;

    /** The test's own path on the tile servers, so the requests of one test are told from another's */
    private String tilePath;

    /** The test's own tile cache folder */
    private Path tileCacheDir;

    /** The system properties a test set, with the values they had before (null: none) */
    private final Map<String, String> replacedProperties = new LinkedHashMap<>();

    /** Committed message ids created by a test, removed after it */
    private final List<Integer> createdMessageIds = new ArrayList<>();

    /** Absolute cache-tree paths of the images drawn for a test, deleted after it */
    private final List<Path> createdPaths = new ArrayList<>();

    @BeforeAll
    static void startTileServers() throws IOException {
        byte[] tile = MapImageTestSupport.solidPng(TileWindow.TILE_SIZE, TileWindow.TILE_SIZE, SEA);
        tileServer = HttpStub.start();
        tileServer.respond(request -> TILE_PATH.matcher(request.path()).matches()
                ? StubResponse.bytes("image/png", tile)
                : StubResponse.status(404));
        failingTileServer = HttpStub.start();
        failingTileServer.respondStatus(500);
    }

    @AfterAll
    static void stopTileServers() {
        if (tileServer != null) {
            tileServer.close();
        }
        if (failingTileServer != null) {
            failingTileServer.close();
        }
    }

    @BeforeEach
    void pointTheTileSettingsAtTheStub() throws IOException {
        assertSame(messageMapImageGenerator, regenerator().generator,
                "precondition: the images are drawn by the real generator");
        tilePath = "it-" + UUID.randomUUID();
        tileCacheDir = Files.createTempDirectory("niord-it-tile-cache-");
        useTileServer(tileServer);
        setProperty("mapTileContact", CONTACT);
        setProperty("mapTileCachePath", tileCacheDir.toString());
    }

    @AfterEach
    void cleanup() throws IOException {
        replacedProperties.forEach((key, previous) -> {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        });
        replacedProperties.clear();

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
            } catch (IOException ignored) {
                // best-effort cleanup only
            }
        }
        createdPaths.clear();

        MapImageTestSupport.deleteRecursively(tileCacheDir);
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Sets a system property for the test, remembering the value it replaces */
    private void setProperty(String key, String value) {
        if (!replacedProperties.containsKey(key)) {
            replacedProperties.put(key, System.getProperty(key));
        }
        System.setProperty(key, value);
    }

    /** The running regenerator bean itself */
    private MapImageRegenerator regenerator() {
        return AopTestUtils.getTargetObject(mapImageRegenerator);
    }

    /** Points the base map's tile address at the given server, under the test's own path */
    private void useTileServer(HttpStub server) {
        setProperty("osmUrl", server.baseUrl() + "/" + tilePath + "/{z}/{x}/{y}.png");
    }

    /** A committed NW message whose first part has the given geometry */
    private Message committedMessage(String name, Geometry geometry) throws Exception {
        Message template = MessageTestFactory.draftNw("IT-MAP-E2E-" + name + "-" + System.nanoTime());
        FeatureCollection fc = new FeatureCollection();
        Feature feature = new Feature();
        feature.setGeometry(geometry);
        fc.addFeature(feature);
        template.getParts().getFirst().setGeometry(fc);
        Message created = messageService.createMessage(template);
        createdMessageIds.add(created.getId());
        return created;
    }

    /** A committed message at one position, the same one in every test */
    private Message pointMessage(String name) throws Exception {
        return committedMessage(name, JtsConverter.toJtsPoint(56.0, 10.0));
    }

    /** The message's change time as the endpoint reads it: the committed value */
    private long committedUpdatedMillis(Message msg) {
        return Tx.requiringNew().call(() -> em.find(Message.class, msg.getId()).getUpdated().getTime());
    }

    /** The size of the map images, as the running generator draws them */
    private int imageSize() {
        return messageMapImageGenerator.getMapImageSize();
    }

    /**
     * The image the endpoint draws for the message: in the message folder's place under the cache tree, named
     * with the image size and the running portrayal definition's image version. Tracked for deletion.
     */
    private Path currentImage(Message msg) {
        Path image = repoCache.pathFor(msg.getRepoPath())
                .resolve("map_" + imageSize() + "_" + portrayal.imageVersion() + ".png");
        if (!createdPaths.contains(image)) {
            createdPaths.add(image);
        }
        return image;
    }

    /** The endpoint's answer for the message: the image itself */
    private static Response getImage(Message msg) {
        return given()
                .redirects().follow(false)
                .when().get(BASE + "/" + msg.getUid() + ".png")
                .then().statusCode(200).contentType("image/png")
                .extract().response();
    }


    /** Decodes the PNG the response holds */
    private static BufferedImage fetchImage(Response response) throws IOException {
        BufferedImage image = MapImageTestSupport.decode(response.asByteArray());
        assertNotNull(image, "the file is an image");
        return image;
    }

    /** The requests the server received under the test's own path */
    private List<RecordedRequest> tileRequests(HttpStub server) {
        String prefix = "/" + tilePath + "/";
        return server.requests().stream().filter(r -> r.path().startsWith(prefix)).toList();
    }

    /**
     * Asserts that an image was drawn on one to four tiles of the tile server, each asked for once, with the
     * backend's User-Agent naming the configured contact
     */
    private void assertTilesFetchedForOneImage(List<RecordedRequest> requests) {
        assertTrue(requests.size() >= 1 && requests.size() <= 4,
                "an image spans one to four tiles, requested were: " + requests);
        assertEquals(requests.size(), requests.stream().map(RecordedRequest::path).distinct().count(),
                "each tile is requested once: " + requests);
        for (RecordedRequest request : requests) {
            String userAgent = request.header("User-Agent");
            assertNotNull(userAgent, "a tile request names its client");
            assertTrue(userAgent.startsWith("Niord/"), "the User-Agent names Niord, was: " + userAgent);
            assertTrue(userAgent.endsWith(" (+" + CONTACT + ")"),
                    "the User-Agent names the configured contact, was: " + userAgent);
        }
    }

    /** The tiles in the test's tile cache folder, once the cache writes started so far are done */
    private long cachedTiles() throws IOException {
        AopTestUtils.<TileFetcher>getTargetObject(tileFetcher).awaitCacheWrites();
        try (Stream<Path> files = Files.walk(tileCacheDir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(TileDiskCache.TILE_SUFFIX)).count();
        }
    }

    private static boolean isMagenta(int rgb) {
        Color c = new Color(rgb, false);
        return Math.abs(c.getRed() - MAGENTA.getRed()) <= TOLERANCE
                && Math.abs(c.getGreen() - MAGENTA.getGreen()) <= TOLERANCE
                && Math.abs(c.getBlue() - MAGENTA.getBlue()) <= TOLERANCE;
    }

    /**
     * Counts the magenta pixels inside or outside the image's central square, whose side is a quarter of the
     * image: room for the point symbol and the tag at the print sizes, which scale with the image
     */
    private static int magentaPixels(BufferedImage image, boolean nearTheCentre) {
        int half = image.getWidth() / 8;
        int cx = image.getWidth() / 2;
        int cy = image.getHeight() / 2;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                boolean central = Math.abs(x - cx) <= half && Math.abs(y - cy) <= half;
                if (central == nearTheCentre && isMagenta(image.getRGB(x, y))) {
                    count++;
                }
            }
        }
        return count;
    }

    private void assertIsTheMapImageOfAMessageAtTheCentre(BufferedImage image) {
        assertEquals(imageSize(), image.getWidth(), "the image is mapImageSize wide");
        assertEquals(imageSize(), image.getHeight(), "the image is mapImageSize high");
        int magenta = magentaPixels(image, true);
        assertTrue(magenta >= MIN_MAGENTA_PIXELS,
                "the portrayal's magenta is drawn at the centre of the image, magenta pixels: " + magenta);
        assertTrue(isSea(image.getRGB(0, 0)) && isSea(image.getRGB(image.getWidth() - 1, image.getHeight() - 1)),
                "the corners show the tile server's tiles");
    }

    private static boolean isSea(int rgb) {
        return (rgb & 0xFFFFFF) == (SEA.getRGB() & 0xFFFFFF);
    }

    // ------------------------------------------------------------------------------------------- //
    // Drawing
    // ------------------------------------------------------------------------------------------- //

    @Test
    void drawsAPointMessageOnTheTileServersTilesAndServesTheImage() throws Exception {
        Message msg = pointMessage("point");
        long updated = committedUpdatedMillis(msg);
        Path image = currentImage(msg);

        Response response = getImage(msg);

        MapImageTestSupport.assertServes(image, response);
        assertTrue(Files.exists(image), "the image was drawn before the response");
        assertEquals(updated, Files.getLastModifiedTime(image).toMillis(),
                "the image carries the message's change time");
        assertIsTheMapImageOfAMessageAtTheCentre(fetchImage(response));

        List<RecordedRequest> requests = tileRequests(tileServer);
        assertTilesFetchedForOneImage(requests);
        assertEquals(requests.size(), cachedTiles(), "every tile is kept in the configured tile cache");
        assertTrue(tileRequests(failingTileServer).isEmpty());
    }

    @Test
    void drawsAnAreaMessageWithItsOutlineAndItsTagAtTheCentre() throws Exception {
        Message msg = committedMessage("area", JtsConverter.toJtsExtent(55.9, 9.8, 56.1, 10.2));
        Path image = currentImage(msg);

        Response response = getImage(msg);

        MapImageTestSupport.assertServes(image, response);
        BufferedImage drawn = fetchImage(response);
        assertIsTheMapImageOfAMessageAtTheCentre(drawn);
        assertTrue(magentaPixels(drawn, false) >= MIN_MAGENTA_PIXELS,
                "the area's outline is drawn around the tag, magenta pixels: " + magentaPixels(drawn, false));
        assertTilesFetchedForOneImage(tileRequests(tileServer));
    }

    // ------------------------------------------------------------------------------------------- //
    // Serving again and cached tiles
    // ------------------------------------------------------------------------------------------- //

    @Test
    void servesTheImageAgainAndDrawsAMessageAtTheSamePlaceWithoutAskingTheTileServer() throws Exception {
        Message first = pointMessage("first");
        Path firstImage = currentImage(first);
        Response drawn = getImage(first);
        MapImageTestSupport.assertServes(firstImage, drawn);
        int requested = tileRequests(tileServer).size();
        assertTilesFetchedForOneImage(tileRequests(tileServer));
        byte[] bytes = Files.readAllBytes(firstImage);
        long modified = Files.getLastModifiedTime(firstImage).toMillis();

        Response again = getImage(first);

        assertArrayEquals(bytes, again.asByteArray(), "the same file is served again");
        assertEquals(requested, tileRequests(tileServer).size(), "serving the file asks for no tile");
        assertArrayEquals(bytes, Files.readAllBytes(firstImage), "the image is not drawn again");
        assertEquals(modified, Files.getLastModifiedTime(firstImage).toMillis());

        Message second = pointMessage("second");
        Path secondImage = currentImage(second);

        Response other = getImage(second);

        MapImageTestSupport.assertServes(secondImage, other);
        assertIsTheMapImageOfAMessageAtTheCentre(fetchImage(other));
        assertEquals(requested, tileRequests(tileServer).size(),
                "the message at the same place is drawn on the cached tiles");
    }

    // ------------------------------------------------------------------------------------------- //
    // A failing tile server
    // ------------------------------------------------------------------------------------------- //

    @Test
    void givesThePlaceholderAndWritesNoFileWhenTheTileServerFails() throws Exception {
        useTileServer(failingTileServer);
        Message msg = pointMessage("failing");
        Path image = currentImage(msg);

        // The placeholder is the endpoint's own answer, not a redirect
        byte[] png = given()
                .redirects().follow(false)
                .when().get(BASE + "/" + msg.getUid() + ".png")
                .then().statusCode(200).contentType("image/png")
                .header(MessageMapImageRestService.IMAGE_KIND_HEADER, MessageMapImageRestService.PLACEHOLDER)
                .extract().asByteArray();

        assertNotNull(MapImageTestSupport.decode(png), "the placeholder is an image");
        assertFalse(Files.exists(image), "no file is written without tiles");
        assertFalse(regenerator().inFlight(image), "no generation is left in flight");
        assertFalse(tileRequests(failingTileServer).isEmpty(), "the configured tile server was asked");
        assertTrue(tileRequests(tileServer).isEmpty(), "no other tile server was asked");
        assertEquals(0, cachedTiles(), "a failed tile is not cached");
    }

    // ------------------------------------------------------------------------------------------- //
    // A PDF that shows the image
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessagePdfWithMapThumbnailsEmbedsTheDrawnImageAndCreditsOpenStreetMap() throws Exception {
        // The PDF renderer loads images from the application's own origin, the baseUri: here the server under
        // test, so the renderer asks the endpoint for the map image as a browser would
        setProperty("baseUri", "http://localhost:" + port);
        Message msg = pointMessage("pdf");
        Tx.requiringNew().run(() -> em
                .createQuery("update Message m set m.status = :status where m.id = :id")
                .setParameter("status", Status.PUBLISHED)
                .setParameter("id", msg.getId())
                .executeUpdate());
        Path image = currentImage(msg);

        // Map thumbnails are printed for a caller the backend knows only
        byte[] pdf = given().auth().oauth2(accessToken())
                .queryParam("lang", "en")
                .queryParam("mapThumbnails", "true")
                .when().get("/rest/message-reports/message/" + msg.getUid() + ".pdf")
                .then().statusCode(200).contentType("application/pdf")
                .extract().asByteArray();

        assertTrue(Files.exists(image), "the PDF renderer's request drew the image");
        assertTilesFetchedForOneImage(tileRequests(tileServer));

        String text = pdfText(pdf);
        assertTrue(text.contains(CREDIT), "the PDF credits OpenStreetMap: " + text);

        List<BufferedImage> embedded = embeddedImages(pdf);
        assertTrue(embedded.stream().anyMatch(i -> i.getWidth() == imageSize() && i.getHeight() == imageSize()
                        && magentaPixels(i, true) >= MIN_MAGENTA_PIXELS),
                "the PDF embeds the drawn map image; its images are: " + embedded.stream()
                        .map(i -> i.getWidth() + "x" + i.getHeight()).collect(Collectors.joining(", ")));
    }

    /** The text of every page of the PDF */
    private static String pdfText(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(PdfTextExtractor.getTextFromPage(reader, page)).append('\n');
            }
            return text.toString();
        } finally {
            reader.close();
        }
    }

    /** The images drawn on the pages of the PDF, decoded */
    private static List<BufferedImage> embeddedImages(byte[] pdf) throws IOException {
        PdfReader reader = new PdfReader(pdf);
        try {
            List<BufferedImage> images = new ArrayList<>();
            PdfReaderContentParser parser = new PdfReaderContentParser(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                parser.processContent(page, new RenderListener() {
                    @Override public void beginTextBlock() { }
                    @Override public void endTextBlock() { }
                    @Override public void renderText(TextRenderInfo info) { }
                    @Override public void renderImage(ImageRenderInfo info) {
                        try {
                            PdfImageObject image = info.getImage();
                            BufferedImage decoded = image != null ? image.getBufferedImage() : null;
                            if (decoded != null) {
                                images.add(decoded);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                });
            }
            return images;
        } finally {
            reader.close();
        }
    }
}
