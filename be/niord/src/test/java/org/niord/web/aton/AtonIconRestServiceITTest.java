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
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link AtonIconRestService}: the HTTP contract of the {@code /rest/aton-icon}
 * endpoints, which render Aid-to-Navigation (AtoN) icons.
 *
 * <p><b>Authorization model under test.</b> {@code AtonIconRestService} is {@code @PermitAll}, so both
 * endpoints ({@code POST /svg} and {@code GET /overview}) are fully anonymous: an unauthenticated caller
 * reaches the handler and receives a normal {@code 2xx}/{@code 3xx} response, never a {@code 401} or
 * {@code 403}. No {@code NiordDomain} header and no bearer token are required, so these tests issue bare
 * {@code given()} requests.
 *
 * <p><b>Behavior oracle — {@code POST /svg}.</b> Consumes {@code application/json} (an
 * {@code AtonNodeVo}) and produces {@code image/svg+xml}; it renders the AtoN as inline SVG and returns
 * {@code 200} with the SVG document in the body (the {@code <svg} root element). The optional
 * {@code width}/{@code height}/{@code scale} query params default to {@code 100}/{@code 100}/{@code 0.4}
 * and tune the rendered geometry within 1 to {@link AtonIconRestService#MAX_SVG_SIZE} pixels and a scale
 * in (0, {@link AtonIconRestService#MAX_SVG_SCALE}]; a value outside those bounds is refused with
 * {@code 400}.
 *
 * <p><b>Behavior oracle — {@code GET /overview}.</b> With no {@code seamark:type} parameter it issues an
 * HTTP {@code 307} temporary redirect to the static placeholder {@code /img/aton/aton.png}, asserted with
 * {@code redirects().follow(false)} so the {@code Location} header is observable. With a
 * {@code seamark:type} it renders the 60x90 PNG in memory and answers it directly ({@code 200},
 * {@code image/png}, a {@code Cache-Control} lifetime). Nothing is written to the repository: the icons
 * were once cached there in an {@code aton_icons} folder, one file for each combination of parameter
 * values a caller chose. Each test that renders an icon uses a value of its own, so it can tell that no
 * file of its own appeared there, whatever earlier runs left behind.
 *
 * <p>Neither endpoint persists anything, so no {@code @AfterEach} cleanup is required.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class AtonIconRestServiceITTest extends ITTest {

    private static final String SVG      = "/rest/aton-icon/svg";
    private static final String OVERVIEW = "/rest/aton-icon/overview";

    /** The repository folder the overview icons were once cached in */
    private static final String FORMER_ICON_FOLDER = "aton_icons";

    @Autowired
    RepositoryService repositoryService;

    @Autowired
    RepoStore store;

    /** Whether any repository file whose path holds the given value exists in the former icon folder */
    private boolean iconFolderHolds(String value) {
        return store.listTree(FORMER_ICON_FOLDER).stream()
                .anyMatch(f -> f.path().contains(value));
    }

    /** Decodes the PNG body of an overview answer, failing the test if it is not one */
    private static BufferedImage png(byte[] body) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(body));
        assertNotNull(image, "the body must decode to a PNG image");
        return image;
    }

    /** A minimal, valid AtoN body: a black cardinal buoy. */
    private static Map<String, Object> cardinalBuoyBody() {
        return Map.of(
                "lat", 56.0,
                "lon", 11.0,
                "user", "it",
                "tags", Map.of(
                        "seamark:type", "buoy_cardinal",
                        "seamark:buoy_cardinal:colour", "black"));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /svg — anonymous, Consumes JSON, Produces image/svg+xml. Returns 200 + an SVG document.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void svg_anonymousRendersSvgDocumentWith200() {
        Response response = given()
                .contentType(ContentType.JSON)
                .body(cardinalBuoyBody())
                .when()
                .post(SVG)
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(response.getContentType(), containsString("svg"));
        assertThat(response.asString(), containsString("<svg"));
    }

    @Test
    void svg_honoursWidthHeightScaleQueryParamsWith200() {
        // Overriding the rendering geometry must still produce a valid SVG document.
        Response response = given()
                .contentType(ContentType.JSON)
                .queryParam("width", 120)
                .queryParam("height", 120)
                .queryParam("scale", 0.5)
                .body(cardinalBuoyBody())
                .when()
                .post(SVG)
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(response.getContentType(), containsString("svg"));
        assertThat(response.asString(), containsString("svg"));
    }

    @Test
    void svg_rendersAtTheLargestAllowedSize() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("width", AtonIconRestService.MAX_SVG_SIZE)
                .queryParam("height", AtonIconRestService.MAX_SVG_SIZE)
                .queryParam("scale", AtonIconRestService.MAX_SVG_SCALE)
                .body(cardinalBuoyBody())
                .when()
                .post(SVG)
                .then()
                .statusCode(200)
                .body(containsString("<svg"));
    }

    @Test
    void svg_refusesAWidthOrHeightOutsideTheAllowedSize() {
        int[][] sizes = {
                { 0, 100 }, { 100, 0 }, { -1, 100 }, { 100, -1 },
                { AtonIconRestService.MAX_SVG_SIZE + 1, 100 }, { 100, AtonIconRestService.MAX_SVG_SIZE + 1 }
        };
        for (int[] size : sizes) {
            given()
                    .contentType(ContentType.JSON)
                    .queryParam("width", size[0])
                    .queryParam("height", size[1])
                    .body(cardinalBuoyBody())
                    .when()
                    .post(SVG)
                    .then()
                    .statusCode(400);
        }
    }

    @Test
    void svg_refusesAScaleOutsideTheAllowedRange() {
        for (String scale : new String[] { "0", "-0.4", "NaN", "Infinity", "10.5" }) {
            given()
                    .contentType(ContentType.JSON)
                    .queryParam("scale", scale)
                    .body(cardinalBuoyBody())
                    .when()
                    .post(SVG)
                    .then()
                    .statusCode(400);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /overview — anonymous. Without a type: a 307 redirect (follow(false) keeps the Location
    // observable). With a type: the PNG itself, rendered in memory, nothing written to the repository.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void overview_withoutTypeRedirectsToStaticPlaceholderPng() {
        given()
                .redirects().follow(false)
                .when()
                .get(OVERVIEW)
                .then()
                .statusCode(307)
                // The Location must be an ABSOLUTE URL (JAX-RS absolutized it; a relative value would be
                // resolved by the client against the request URI and 404). No "../" / "//" artefacts.
                .header("Location", startsWith("http"))
                .header("Location", endsWith("/img/aton/aton.png"))
                .header("Location", not(containsString("..")));
    }

    @Test
    void overview_withSeamarkTypeAnswersTheRenderedPngAndWritesNothing() throws IOException {
        // A light colour of this test's own: the file the icon used to be cached in would have been
        // named after it, so its absence shows that nothing was written for this request.
        String colour = "it-" + UUID.randomUUID();

        Response response = given()
                .redirects().follow(false)
                .queryParam("seamark:type", "light_minor")
                .queryParam("seamark:light:colour", colour)
                .when()
                .get(OVERVIEW)
                .then()
                .statusCode(200)
                .contentType("image/png")
                .header("Cache-Control", containsString("max-age=" + AtonIconRestService.OVERVIEW_ICON_TTL_HOURS * 3600))
                .header("Location", nullValue())
                .extract().response();

        BufferedImage image = png(response.asByteArray());
        assertEquals(AtonIconRestService.OVERVIEW_ICON_WIDTH, image.getWidth());
        assertEquals(AtonIconRestService.OVERVIEW_ICON_HEIGHT, image.getHeight());
        assertFalse(iconFolderHolds(colour), "the overview icon must not be written to the repository");
    }

    @Test
    void overview_everyParameterCombinationIsRenderedWithoutWritingAFile() throws IOException {
        // Each new combination of values used to add folders and a file to the repository; now each
        // one is only rendered.
        String run = "it-" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            given()
                    .queryParam("seamark:type", "buoy_cardinal")
                    .queryParam("seamark:buoy_cardinal:category", "north")
                    .queryParam("seamark:topmark:shape", run + "-" + i)
                    .when()
                    .get(OVERVIEW)
                    .then()
                    .statusCode(200)
                    .contentType("image/png");
        }
        assertFalse(iconFolderHolds(run), "no parameter combination may create repository files");
    }

    @Test
    void overview_dotDotSeamarkValueRendersWithoutWritingAnything() throws IOException {
        // SECAUD-10 held a crafted ".." value inside the icon folder with a path check. The icon is no
        // longer written anywhere, so such a value is only a tag value of the rendered AtoN: the answer
        // is a PNG, and no icon file appears where the ".." would have led (the repository root).
        byte[] body = given()
                .redirects().follow(false)
                .queryParam("seamark:type", "..")
                .when()
                .get(OVERVIEW)
                .then()
                .statusCode(200)
                .contentType("image/png")
                .extract().asByteArray();

        png(body);
        String climbedTo = "aton_icon_" + AtonIconRestService.OVERVIEW_ICON_WIDTH + "x"
                + AtonIconRestService.OVERVIEW_ICON_HEIGHT + ".png";
        assertFalse(store.exists(climbedTo),
                "no icon may be written outside the former icon folder");
    }
}
