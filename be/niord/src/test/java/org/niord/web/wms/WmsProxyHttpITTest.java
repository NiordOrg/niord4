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
package org.niord.web.wms;

import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.WmsProviderStub;
import org.niord.test.http.HttpStub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the <em>successful binary-image path</em> of
 * {@link org.niord.web.wms.WmsProxyServlet}: the branch that {@code WmsProxyServletITTest} explicitly
 * documents as untestable because no upstream WMS provider is configured in the shared application.
 *
 * <p>Here we close that gap. {@link WmsProviderStub} (started in {@code @BeforeEach}, closed in
 * {@code @AfterEach}, so it only affects this class) stands up a local {@link HttpStub} that serves a
 * real PNG and sets the {@code wmsProvider} / {@code wmsPassword} / {@code wmsLayers} system properties.
 * Because {@code SettingsService.get(...)} consults {@link System#getProperty(String)} before the
 * DB/default, the servlet's {@code @Setting}-injected {@code wmsProvider} resolves to the stub, so an
 * authenticated request makes the proxy perform a genuine outbound fetch, decode the upstream image,
 * and stream a re-encoded PNG straight back (no redirect).
 *
 * <p><b>Shared-context note.</b> In the Spring port the servlet is wired lazily and cached by
 * {@code WmsServletConfig.DelegatingWmsServlet}; because the {@code @SpringBootTest} context is shared
 * with {@code WmsProxyServletITTest}, {@link WmsProviderStub} resets that cached delegate on start and
 * stop so this suite is order-independent and never pollutes the no-provider blank-image fallback suite.
 *
 * <p><b>Endpoint shape.</b> Like {@code WmsProxyServletITTest}, this targets the bare servlet path
 * {@code /wms/*} — <em>not</em> under the {@code /rest} base path. {@code wmsProtected} defaults to
 * {@code true}, so the request must be authenticated; we mint a sysadmin token and send the
 * {@code NiordDomain: niord-nw} header to mirror the sibling suite (the servlet only inspects the
 * presence of a principal, not its roles).
 *
 * <p><b>Oracles.</b> (a) the response is {@code 200} (not the {@code 302} blank-image fallback), carries a
 * {@code Content-Type: image/png} header (WEB5) and a body that is a decodable image. (b) the upstream request the
 * proxy made carries the configured token ({@code TOKEN=tok-123}) and layers ({@code LAYERS=layerA}) that
 * the servlet appends to the query string, and no parameter a tile does not need. (The stub records the
 * decoded query, so how the values are encoded is {@code WmsProxyServletTest}'s to show.) (c) the tile cache,
 * through the real security chain: with {@code wmsCacheDays} set on the {@link WmsTileCache} bean a user's tile is
 * fetched from the provider once and then served from the cache, to any logged-in user and to nobody else; left at
 * its default the cache is off and every request goes to the provider.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class WmsProxyHttpITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    /** Bare servlet path — note: NOT under the {@code /rest} base path. */
    private static final String WMS = "/wms/foo";

    @Autowired
    ApplicationContext context;

    @Autowired
    WmsTileCache cache;

    /** The per-test WMS provider stub; points the servlet's {@code wmsProvider} at a local PNG. */
    private WmsProviderStub providerStub;

    @BeforeEach
    void startProviderStub() {
        providerStub = WmsProviderStub.start(context);
    }

    @AfterEach
    void stopProviderStub() {
        if (providerStub != null) {
            providerStub.close();
            providerStub = null;
        }
    }

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    // ------------------------------------------------------------------------------------------- //
    // (a) Happy path: authenticated request -> upstream fetch -> 200 with a decodable image body.
    //     NOT a 302 redirect (the provider IS configured, so the blank-image fallback is skipped).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void authenticated_returnsDecodableImage_notRedirect() throws Exception {
        byte[] body = as(accessToken())
                .redirects().follow(false)
                .when().get(WMS + "?BBOX=1,2,3,4&WIDTH=16&HEIGHT=16")
                .then().statusCode(200)
                .header("Content-Type", containsString("image/png"))
                .extract().asByteArray();

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(body));
        assertNotNull(image, "Response body should be a decodable image streamed back by the proxy");
    }

    // ------------------------------------------------------------------------------------------- //
    // (b) URL composition: the proxy appends the configured token and layers to the upstream request.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void upstreamRequest_carriesConfiguredTokenAndLayers() {
        as(accessToken())
                .redirects().follow(false)
                .when().get(WMS + "?BBOX=1,2,3,4&WIDTH=16&HEIGHT=16")
                .then().statusCode(200);

        HttpStub.RecordedRequest upstream = providerStub.stub().lastRequest();
        assertNotNull(upstream, "The proxy should have made an outbound request to the configured provider");

        String query = upstream.query();
        assertNotNull(query, "Upstream request should carry a query string");
        assertTrue(query.contains("TOKEN=tok-123"),
                "Proxy should append the configured wmsPassword as TOKEN, but query was: " + query);
        assertTrue(query.contains("LAYERS=layerA"),
                "Proxy should append the configured wmsLayers as LAYERS, but query was: " + query);
        assertEquals("GET", upstream.method(), "The proxy performs an upstream GET");
    }

    @Test
    void upstreamRequest_carriesTheTileParametersOnly() {
        as(accessToken())
                .redirects().follow(false)
                .when().get(WMS + "?BBOX=1,2,3,4&WIDTH=16&HEIGHT=16&EXTRA=x&TOKEN=caller&LAYERS=callerLayer")
                .then().statusCode(200);

        String query = providerStub.stub().lastRequest().query();
        assertTrue(query.contains("BBOX=1,2,3,4"), "the tile parameters are forwarded, but query was: " + query);
        assertTrue(!query.contains("EXTRA"), "a parameter a tile does not need is dropped, but query was: " + query);
        assertTrue(!query.contains("caller"), "the caller's token and layers are dropped, but query was: " + query);
        assertTrue(query.contains("TOKEN=tok-123") && query.contains("LAYERS=layerA"),
                "the configured token and layers are sent, but query was: " + query);
    }

    // ------------------------------------------------------------------------------------------- //
    // (c) The tile cache: a logged-in user's tile is fetched once and then served from the cache;
    //     an anonymous caller and a caller with a bad token get nothing of it.
    // ------------------------------------------------------------------------------------------- //

    /** The tile 8/136/79 of the Web Mercator grid, as a web map asks for it */
    private static final String TILE_BOX = "1252344.271424327,7514065.628545966,1408887.305352368,7670608.662474007";

    private static final String CACHE_HEADER = "X-Niord-Cache";

    /** The request a web map makes for one tile */
    private static RequestSpecification tile(RequestSpecification caller) {
        return caller.redirects().follow(false)
                .queryParam("SERVICE", "WMS").queryParam("VERSION", "1.3.0").queryParam("REQUEST", "GetMap")
                .queryParam("FORMAT", "image/png").queryParam("TRANSPARENT", "TRUE").queryParam("STYLES", "")
                .queryParam("WIDTH", "256").queryParam("HEIGHT", "256").queryParam("CRS", "EPSG:3857")
                .queryParam("BBOX", TILE_BOX);
    }

    private static byte[] png(int side) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    /** The folder the cache keeps this test's provider's tiles in: the stub's port names it */
    private Path providersFolder() {
        return cache.folder().resolve("127.0.0.1_" + providerStub.stub().port());
    }

    private void deleteProvidersFolder() throws IOException {
        Path folder = providersFolder();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> walk = Files.walk(folder)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    @Test
    void withTheCacheOn_aTileIsFetchedOnceAndServedToLoggedInUsersOnly() throws Exception {
        providerStub.stub().respondBytes("image/png", png(256));
        Integer days = cache.wmsCacheDays;
        cache.wmsCacheDays = 7;
        try {
            byte[] fetched = tile(as(accessToken()))
                    .when().get(WMS)
                    .then().statusCode(200)
                    .header(CACHE_HEADER, "MISS")
                    .header("Cache-Control", "private, max-age=86400")
                    .header("Content-Type", containsString("image/png"))
                    .extract().asByteArray();
            byte[] cached = tile(as(accessToken()))
                    .when().get(WMS)
                    .then().statusCode(200)
                    .header(CACHE_HEADER, "HIT")
                    .header("Cache-Control", "private, max-age=86400")
                    .extract().asByteArray();

            assertArrayEquals(fetched, cached, "the cached tile is the fetched one");
            assertEquals(256, ImageIO.read(new ByteArrayInputStream(cached)).getWidth());
            assertEquals(1, providerStub.stub().requestCount(), "the provider was asked once for the two requests");
            try (Stream<Path> walk = Files.walk(providersFolder())) {
                List<Path> files = walk.filter(Files::isRegularFile).toList();
                assertEquals(1, files.size(), "one tile on the volume: " + files);
                assertTrue(files.get(0).endsWith(Path.of("8", "136", "79.png")), "at its place: " + files.get(0));
            }

            // The lowest tier is a logged-in user too
            tile(as(accessToken("user")))
                    .when().get(WMS)
                    .then().statusCode(200).header(CACHE_HEADER, "HIT");

            // Nobody else gets the tile that now lies in the cache
            tile(given())
                    .when().get(WMS)
                    .then().statusCode(403)
                    .header(CACHE_HEADER, nullValue())
                    .body(containsString("only available to authenticated users"));
            tile(given().auth().oauth2("not-a-token"))
                    .when().get(WMS)
                    .then().statusCode(401)
                    .header(CACHE_HEADER, nullValue());
            assertEquals(1, providerStub.stub().requestCount(), "and nothing was fetched for them");
        } finally {
            cache.wmsCacheDays = days;
            deleteProvidersFolder();
        }
    }

    @Test
    void withTheCacheLeftOff_everyTileRequestGoesToTheProvider() throws Exception {
        providerStub.stub().respondBytes("image/png", png(256));

        for (int request = 0; request < 2; request++) {
            tile(as(accessToken()))
                    .when().get(WMS)
                    .then().statusCode(200)
                    .header(CACHE_HEADER, nullValue())
                    .header("Cache-Control", "max-age=86400")
                    .header("Content-Type", containsString("image/png"));
        }

        assertEquals(2, providerStub.stub().requestCount(), "the default: no cache");
        assertTrue(!Files.exists(providersFolder()), "and nothing on the volume");
    }
}
