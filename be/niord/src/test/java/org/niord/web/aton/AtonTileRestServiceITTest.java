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

import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Integration tests for {@link org.niord.web.aton.AtonTileRestService}: the HTTP contract of the
 * {@code /rest/aton-tiles/{z}/{x}/{y}.png} OpenStreetMap-style tile endpoint.
 *
 * <p><b>Authorization model under test.</b> The service is {@code @PermitAll}, so the single
 * {@code GET} handler is public: an anonymous caller (no token, no {@code NiordDomain} header) reaches
 * the handler and receives {@code 200}. There is therefore no tier oracle to exercise here; the
 * meaningful behavior is the rendered tile itself, not an authorization decision.
 *
 * <p><b>Tile oracle.</b> The {@code AtonNode} table is empty in the seed, so for every requested
 * {@code z/x/y} the bounds search returns no positions and the handler streams the blank 256x256
 * PNG ({@code /blank_256.png} copied into the tile cache folder). Each request must therefore return
 * {@code 200} with content type {@code image/png} and a non-empty body. The blank-tile branch streams
 * the file <em>without</em> an {@code ETag} (see {@code streamBlankTile}), so repeated requests for the
 * same blank tile are not 304-cached and both return a full {@code 200 image/png} body — distinct from
 * the rendered-tile branch which would attach an {@code EntityTag}.
 *
 * <p><b>Tile range.</b> Only tiles of the pyramid are served: zoom 0 to
 * {@link AtonTileRestService#MAX_ZOOM}, and at zoom z the columns and rows 0 to 2^z - 1. Any other
 * {@code z/x/y} is refused with {@code 400} before the handler searches, caches or writes anything,
 * so an out-of-range tile leaves no blank-tile cache entry and no {@code <z>} folder in the tile cache.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class AtonTileRestServiceITTest extends ITTest {

    private static final String TILES = "/rest/aton-tiles/";

    @Autowired
    AtonTileRestService tileService;

    @Autowired
    AtonBlankTileCache blankTileCache;

    /** Builds the tile path {@code /rest/aton-tiles/{z}/{x}/{y}.png}. */
    private static String tile(int z, int x, int y) {
        return TILES + z + "/" + x + "/" + y + ".png";
    }

    /** The cache file the handler would use for the tile, which is also its blank-tile cache key. */
    private Path tileFile(int z, int x, int y) {
        return tileService.tileFolder()
                .resolve(String.valueOf(z))
                .resolve(String.valueOf(x))
                .resolve(y + ".png");
    }

    /** Requests a tile outside the served pyramid: 400, and nothing cached or written for it. */
    private void assertRefused(int z, int x, int y) {
        given()
                .when().get(tile(z, x, y))
                .then().statusCode(400);

        Path file = tileFile(z, x, y);
        assertFalse(blankTileCache.getCache().containsKey(file.toString()),
                "a refused tile must not be cached as a blank tile: " + file);
        assertFalse(Files.exists(tileService.tileFolder().resolve(String.valueOf(z))),
                "a refused tile must not create a zoom folder: z=" + z);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /{z}/{x}/{y}.png — no security annotation -> anonymous reaches the handler. The AtoN table
    // is empty, so the blank 256x256 PNG is streamed for any tile: 200 + image/png + non-empty body.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void streamTile_anonymousReturnsNonEmptyPngForBlankTile() {
        byte[] body = given()
                .when().get(tile(2, 2, 1))
                .then().statusCode(200).contentType("image/png")
                .extract().asByteArray();

        // A real 256x256 PNG was streamed (the empty-AtoN blank tile), not an empty body.
        org.hamcrest.MatcherAssert.assertThat(body.length, greaterThan(0));
    }

    @Test
    void streamTile_anonymousReturnsPngForDeepZoomTile() {
        given()
                .when().get(tile(5, 16, 10))
                .then().statusCode(200).contentType("image/png");
    }

    @Test
    void streamTile_anonymousReturnsPngForRootTile() {
        // z=0/x=0/y=0 is the single world-spanning tile; still blank with an empty AtoN table.
        given()
                .when().get(tile(0, 0, 0))
                .then().statusCode(200).contentType("image/png");
    }

    @Test
    void streamTile_blankTileCarriesNoEtagAndConditionalRequestIsNot304() {
        // The blank-tile branch streams the file without attaching an EntityTag, so the first
        // response carries no ETag header at all...
        String etag = given()
                .when().get(tile(3, 4, 5))
                .then().statusCode(200).contentType("image/png")
                .extract().header("ETag");
        org.hamcrest.MatcherAssert.assertThat(etag, org.hamcrest.Matchers.nullValue());

        // ...and because there is no ETag to match, a second request that offers an If-None-Match
        // precondition is still served a full 200 image/png response, never a 304 Not Modified.
        given()
                .header("If-None-Match", "\"any-old-tag\"")
                .when().get(tile(3, 4, 5))
                .then().statusCode(200).contentType("image/png");
    }

    // ------------------------------------------------------------------------------------------- //
    // Tile range — anything outside zoom 0..MAX_ZOOM and columns/rows 0..2^z-1 is refused with 400
    // before the search, the blank-tile cache or the repository is touched.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void streamTile_negativeZoomIsRefused() {
        assertRefused(-1, 0, 0);
    }

    @Test
    void streamTile_zoomAboveMaxZoomIsRefused() {
        assertRefused(AtonTileRestService.MAX_ZOOM + 1, 0, 0);
    }

    @Test
    void streamTile_columnBeyondTheZoomLevelIsRefused() {
        // Zoom 7 has 2^7 = 128 columns, 0..127
        assertRefused(7, 128, 0);
    }

    @Test
    void streamTile_negativeColumnIsRefused() {
        assertRefused(9, -1, 0);
    }

    @Test
    void streamTile_negativeRowIsRefused() {
        assertRefused(11, 0, -1);
    }

    @Test
    void streamTile_rowBeyondTheZoomLevelIsRefused() {
        // Zoom 7 has 2^7 = 128 rows, 0..127
        assertRefused(7, 0, 128);
    }

    @Test
    void streamTile_lastTileOfTheDeepestZoomIsServed() {
        // The largest served coordinates: zoom MAX_ZOOM, column and row 2^MAX_ZOOM - 1.
        int last = (1 << AtonTileRestService.MAX_ZOOM) - 1;
        given()
                .when().get(tile(AtonTileRestService.MAX_ZOOM, last, last))
                .then().statusCode(200).contentType("image/png");
    }
}
