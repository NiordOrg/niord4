/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.web.aton;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.niord.core.repo.RepositoryService;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;

import javax.imageio.ImageIO;
import org.niord.core.repo.RepoStore;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the AtoN image endpoints, which turn OSM seamark tags into bitmaps:
 * {@link org.niord.web.aton.AtonIconRestService} ({@code /rest/aton-icon}) and
 * {@link org.niord.web.aton.AtonTileRestService} ({@code /rest/aton-tiles}).
 *
 * <p>Neither service is role-protected: both are {@code @PermitAll}. So all requests below run without
 * an auth token — the absence of a 401 is itself part of the behaviour under test.
 *
 * <p>The tile service writes into the repository file-system rooted at {@code niord.home}, which the
 * integration tests point at a temp dir, so tile generation works. Generated artefacts live inside
 * that dir and need no cleanup. The icon service writes nothing.
 *
 * <p>The covered HTTP contract:
 * <ul>
 *   <li>{@code POST /aton-icon/svg} renders an SVG document ({@code image/svg+xml}) from a posted
 *       {@link org.niord.core.aton.vo.AtonNodeVo} (tags serialized as a JSON map);</li>
 *   <li>{@code GET /aton-icon/overview} with a blank {@code seamark:type} returns a
 *       307 redirect to the static {@code /img/aton/aton.png}; with a type it answers the rendered
 *       60x90 PNG itself, and writes nothing to the repository (the {@code aton_icons} folder the
 *       icons were once cached in stays without a file for the request);</li>
 *   <li>{@code GET /aton-tiles/{z}/{x}/{y}.png} streams a 256x256 PNG tile; a tile with no AtoNs in
 *       its bounds is served as the blank 256x256 PNG (and cached), and a repeat request of the same
 *       blank tile is served again as a valid 256x256 PNG via the blank-tile cache path.</li>
 * </ul>
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class AtonImageRestServiceITTest extends ITTest {

    // A z=2 tile in the far south-west of the world map (open ocean, nowhere near the seeded
    // Danish AtoN domain), so its bounds contain no AtoNs and the tile is served blank. The blank
    // tile is still a fully-valid 256x256 PNG, so the dimension assertions hold regardless of what
    // (if any) AtoN rows exist far away.
    private static final String BLANK_TILE = "/rest/aton-tiles/2/0/0.png";

    @Autowired
    RepositoryService repositoryService;

    @Autowired
    RepoStore store;

    // ------------------------------------------------------------------------------------------- //
    // POST /aton-icon/svg : renders an SVG document from a seamark AtoN
    // ------------------------------------------------------------------------------------------- //

    @Test
    void postSvgForCardinalBuoyReturnsSvgDocument() {
        // A valid north-cardinal buoy, mirroring the tags built in AtonTest.createAtonNode().
        Map<String, Object> aton = buoyCardinalAton();

        Response resp = given()
                .contentType(ContentType.JSON)
                .body(aton)
                .queryParam("width", 100)
                .queryParam("height", 100)
                .queryParam("scale", 0.4)
                .when().post("/rest/aton-icon/svg")
                .then().statusCode(200)
                .extract().response();

        // @Produces("image/svg+xml")
        assertTrue(resp.getContentType() != null
                        && resp.getContentType().toLowerCase().contains("image/svg+xml"),
                "the SVG endpoint must produce image/svg+xml, was: " + resp.getContentType());

        // The body must be an SVG document (Batik's SVGGraphics2D streams an <svg ...> root element).
        String body = resp.asString();
        assertTrue(body.toLowerCase().contains("<svg"),
                "the response body must contain an <svg> root element, was: "
                        + body.substring(0, Math.min(body.length(), 200)));
    }

    @Test
    void postSvgForLightReturnsSvgDocument() {
        // A different, simpler seamark type (a light) exercising the same render path with other tags.
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("seamark:type", "light_minor");
        tags.put("seamark:light:colour", "red");
        tags.put("seamark:light:character", "Fl");

        Map<String, Object> aton = baseAton();
        aton.put("tags", tags);

        Response resp = given()
                .contentType(ContentType.JSON)
                .body(aton)
                .queryParam("width", 100)
                .queryParam("height", 100)
                .queryParam("scale", 0.4)
                .when().post("/rest/aton-icon/svg")
                .then().statusCode(200)
                .extract().response();

        assertTrue(resp.getContentType() != null
                        && resp.getContentType().toLowerCase().contains("image/svg+xml"),
                "the SVG endpoint must produce image/svg+xml, was: " + resp.getContentType());
        assertTrue(resp.asString().toLowerCase().contains("<svg"),
                "the response body must contain an <svg> root element");
    }

    @Test
    void postSvgUsesDefaultDimensionsWhenNoQueryParams() {
        // width/height default to 100 and scale to 0.4 (the @DefaultValue annotations). Omitting the
        // query params must still yield a valid SVG document with a 200 status.
        Map<String, Object> aton = buoyCardinalAton();

        Response resp = given()
                .contentType(ContentType.JSON)
                .body(aton)
                .when().post("/rest/aton-icon/svg")
                .then().statusCode(200)
                .extract().response();

        assertTrue(resp.getContentType() != null
                        && resp.getContentType().toLowerCase().contains("image/svg+xml"),
                "defaults must still produce image/svg+xml, was: " + resp.getContentType());
        assertTrue(resp.asString().toLowerCase().contains("<svg"),
                "defaults must still produce an <svg> document");
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /aton-icon/overview : 307 redirect for a blank type, the rendered png otherwise
    // ------------------------------------------------------------------------------------------- //

    @Test
    void overviewWithoutTypeRedirectsToStaticAtonPng() {
        // No seamark:type param -> StringUtils.isBlank(type) true -> temporaryRedirect to the static
        // /img/aton/aton.png. Disable redirect-following so we can assert the 307 and the Location.
        String location = given()
                .redirects().follow(false)
                .when().get("/rest/aton-icon/overview")
                .then().statusCode(307)
                .extract().header("Location");

        assertNotNull(location, "a 307 redirect must carry a Location header");
        assertTrue(location.contains("/img/aton/aton.png"),
                "a blank seamark:type must redirect to the static /img/aton/aton.png, was: " + location);
    }

    @Test
    void overviewWithTypeAnswersTheRenderedPngWithoutWritingIt() throws Exception {
        // With a seamark:type the service renders the 60x90 PNG in memory and answers it directly. It
        // used to write it under the 'aton_icons' repository folder, in a sub-folder named after each
        // parameter value, and redirect there; a light character of this test's own shows that no
        // such file was written for this request.
        String character = "it-" + UUID.randomUUID();

        Response resp = given()
                .redirects().follow(false)
                .queryParam("seamark:type", "light")
                .queryParam("seamark:light:colour", "red")
                .queryParam("seamark:light:character", character)
                .when().get("/rest/aton-icon/overview")
                .then().statusCode(200)
                .contentType("image/png")
                .extract().response();

        assertNull(resp.getHeader("Location"), "the icon is answered, not redirected to");
        BufferedImage img = readPng(resp.asByteArray());
        assertEquals(60, img.getWidth(), "an overview icon must be 60 pixels wide");
        assertEquals(90, img.getHeight(), "an overview icon must be 90 pixels high");

        assertTrue(store.listTree("aton_icons").stream()
                        .noneMatch(f -> f.path().contains(character)),
                "the overview icon must not be written to the repository");
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /aton-tiles/{z}/{x}/{y}.png : 256x256 PNG tiles (blank tile path)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void emptyTileReturnsBlank256x256Png() throws Exception {
        byte[] png = given()
                .when().get(BLANK_TILE)
                .then().statusCode(200)
                .contentType("image/png")
                .extract().asByteArray();

        BufferedImage img = readPng(png);
        assertEquals(256, img.getWidth(), "a tile must be 256 pixels wide");
        assertEquals(256, img.getHeight(), "a tile must be 256 pixels high");
    }

    @Test
    void repeatedRequestOfBlankTileStillReturns256x256Png() throws Exception {
        // First request populates the blank-tile cache (AtonBlankTileCache) for this tile path.
        given().when().get(BLANK_TILE)
                .then().statusCode(200)
                .contentType("image/png");

        // The second request takes the blankTileCache.containsKey(...) branch and streams the blank
        // tile again. It must still be a valid 256x256 PNG with a 200 status and image/png type.
        byte[] png = given()
                .when().get(BLANK_TILE)
                .then().statusCode(200)
                .contentType("image/png")
                .extract().asByteArray();

        BufferedImage img = readPng(png);
        assertEquals(256, img.getWidth(), "a cached blank tile must still be 256 pixels wide");
        assertEquals(256, img.getHeight(), "a cached blank tile must still be 256 pixels high");
    }

    @Test
    void distinctEmptyTileAlsoReturnsBlank256x256Png() throws Exception {
        // A second, distinct empty tile (different x/y) exercises a fresh, un-cached tile path and
        // must independently resolve to a valid 256x256 blank PNG.
        byte[] png = given()
                .when().get("/rest/aton-tiles/2/1/0.png")
                .then().statusCode(200)
                .contentType("image/png")
                .extract().asByteArray();

        BufferedImage img = readPng(png);
        assertEquals(256, img.getWidth(), "a distinct empty tile must be 256 pixels wide");
        assertEquals(256, img.getHeight(), "a distinct empty tile must be 256 pixels high");
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Reads PNG bytes into a BufferedImage, failing the test if the bytes are not a valid PNG. */
    private static BufferedImage readPng(byte[] png) throws Exception {
        assertTrue(png != null && png.length > 0, "the tile response body must contain image bytes");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img, "the response bytes must decode to a PNG image");
        return img;
    }

    /** A minimal AtonNodeVo skeleton (without tags) as a JSON-serializable map. */
    private static Map<String, Object> baseAton() {
        Map<String, Object> aton = new LinkedHashMap<>();
        aton.put("id", 672436827);
        aton.put("lat", 50.8070813);
        aton.put("lon", -1.2841124);
        aton.put("user", "malcolmh");
        aton.put("uid", 128186);
        aton.put("visible", true);
        aton.put("version", 11);
        aton.put("changeset", 9107813);
        aton.put("timestamp", 1314134556000L);
        return aton;
    }

    /**
     * Builds a north-cardinal buoy AtonNodeVo as a JSON map. The 'tags' object is deserialized into
     * an AtonTagVo[] by {@link org.niord.core.aton.vo.AtonTagJsonSerialization.Deserializer}.
     * Mirrors the tag set in {@code AtonTest.createAtonNode()}.
     */
    private static Map<String, Object> buoyCardinalAton() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("seamark:buoy_cardinal:category", "north");
        tags.put("seamark:buoy_cardinal:colour", "black;yellow");
        tags.put("seamark:buoy_cardinal:colour_pattern", "horizontal");
        tags.put("seamark:buoy_cardinal:shape", "pillar");
        tags.put("seamark:light:character", "VQ");
        tags.put("seamark:light:colour", "white");
        tags.put("seamark:name", "Calshot");
        tags.put("seamark:topmark:colour", "black");
        tags.put("seamark:topmark:shape", "2 cones up");
        tags.put("seamark:type", "buoy_cardinal");

        Map<String, Object> aton = baseAton();
        aton.put("tags", tags);
        return aton;
    }
}
