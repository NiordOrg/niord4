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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServlet;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.niord.test.WmsProviderStub;
import org.niord.test.http.HttpStub;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.jpa.JpaTransactionManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * and stream a re-encoded PNG straight back.
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
 * <p><b>Oracles.</b> (a) the response is {@code 200}, carries a {@code Content-Type: image/png} header (WEB5) and a
 * body that is the provider's image, 16 pixels wide, not the one-pixel blank picture the fallback answers. (b) the upstream request the
 * proxy made carries the configured token ({@code TOKEN=tok-123}) and layers ({@code LAYERS=layerA}) that
 * the servlet appends to the query string, and no parameter a tile does not need. (The stub records the
 * decoded query, so how the values are encoded is {@code WmsProxyServletTest}'s to show.) (c) the tile cache,
 * through the real security chain: with {@code wmsCacheDays} set on the {@link WmsTileCache} bean a user's tile is
 * fetched from the provider once and then served from the cache, to any logged-in user and to nobody else; left at
 * its default the cache is off and every request goes to the provider. (d) a provider saved on the Settings page
 * serves the next tile request, and ends the pause the old provider's failures had started. (e) a tile from the
 * cache costs the servlet no database work: no statement, no connection taken from the pool and no transaction, so
 * the request reads no setting from the database.
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

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    MeterRegistry meterRegistry;

    @PersistenceContext
    EntityManager em;

    /** The setting that names the provider */
    private static final String PROVIDER = "wmsProvider";

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
    //     The provider's image, not the blank picture (the provider IS configured and answers).
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
        assertEquals(16, image.getWidth(), "the provider's image, not the one-pixel blank picture");
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

    /** The parameters of the request a web map makes for one tile */
    private static final Map<String, String> TILE_PARAMS = tileParams();

    private static Map<String, String> tileParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("SERVICE", "WMS");
        params.put("VERSION", "1.3.0");
        params.put("REQUEST", "GetMap");
        params.put("FORMAT", "image/png");
        params.put("TRANSPARENT", "TRUE");
        params.put("STYLES", "");
        params.put("WIDTH", "256");
        params.put("HEIGHT", "256");
        params.put("CRS", "EPSG:3857");
        params.put("BBOX", TILE_BOX);
        return params;
    }

    /** The request a web map makes for one tile */
    private static RequestSpecification tile(RequestSpecification caller) {
        return caller.redirects().follow(false).queryParams(TILE_PARAMS);
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
        deleteFolder(providersFolder());
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

    // ------------------------------------------------------------------------------------------- //
    // (d) A save on the Settings page: the next tile request uses the saved provider, and a pause
    //     after the old provider's failures is over.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aProviderSavedOnTheSettingsPageServesTheNextTileAndEndsThePause() throws Exception {
        providerStub.stub().respondStatus(503);
        Integer days = cache.wmsCacheDays;
        cache.wmsCacheDays = 7;
        Object original = rowValue(PROVIDER);
        HttpStub saved = HttpStub.start();
        saved.respondBytes("image/png", png(256));
        try {
            for (int request = 0; request < WmsTileCache.FAILURES_TO_PAUSE + 1; request++) {
                tile(as(accessToken()))
                        .when().get(WMS)
                        .then().statusCode(200)
                        .header("Cache-Control", "private, max-age=" + WmsProxyServlet.RETRY_TIMEOUT);
            }
            assertEquals(WmsTileCache.FAILURES_TO_PAUSE, providerStub.stub().requestCount(),
                    "precondition: the failing provider is paused");

            // The stub's system property would win over the saved row, as an override does
            System.clearProperty(PROVIDER);
            put(PROVIDER, saved.baseUrl());

            tile(as(accessToken()))
                    .when().get(WMS)
                    .then().statusCode(200)
                    .header(CACHE_HEADER, "MISS")
                    .header("Cache-Control", "private, max-age=86400");
            assertEquals(1, saved.requestCount(), "the next tile request asked the saved provider");
            assertEquals(WmsTileCache.FAILURES_TO_PAUSE, providerStub.stub().requestCount(), "not the old one");
            String query = saved.lastRequest().query();
            assertTrue(query.contains("TOKEN=tok-123") && query.contains("LAYERS=layerA"),
                    "with the token and layers as they were, but query was: " + query);
        } finally {
            put(PROVIDER, original);
            System.setProperty(PROVIDER, providerStub.stub().baseUrl());
            cache.wmsCacheDays = days;
            deleteProvidersFolder();
            deleteFolder(cache.folder().resolve("127.0.0.1_" + saved.port()));
            saved.close();
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // (e) A tile served from the cache costs the database nothing: no statement, no connection
    //     from the pool, no transaction. A request reads the servlet's fields, which a save sets,
    //     and no setting through the settings service. Measured on the servlet, the way the
    //     container hands it a request once the filters have run: the filters are not the tile's,
    //     and one of them opens a transaction on every request (DOMAIN4 in
    //     be/docs/potential-errors.md).
    // ------------------------------------------------------------------------------------------- //

    /** What the database did in a while: statements, connections and transactions */
    private record DatabaseUse(long statements, long connections, long poolConnections, long transactions) {

        DatabaseUse minus(DatabaseUse before) {
            return new DatabaseUse(statements - before.statements, connections - before.connections,
                    poolConnections - before.poolConnections, transactions - before.transactions);
        }

        long total() {
            return statements + connections + poolConnections + transactions;
        }
    }

    /**
     * The database use so far: Hibernate's statements, connections and transactions, and the connections taken from
     * the connection pool by anyone (its acquisition timer)
     */
    private DatabaseUse databaseUse(Statistics statistics) {
        Collection<Timer> acquisitions = meterRegistry.find("hikaricp.connections.acquire").timers();
        assertFalse(acquisitions.isEmpty(), "the pool's acquisitions are measured");
        long poolConnections = acquisitions.stream().mapToLong(Timer::count).sum();
        return new DatabaseUse(statistics.getPrepareStatementCount(), statistics.getConnectCount(), poolConnections,
                statistics.getTransactionCount());
    }

    @Test
    void aTileFromTheCacheCostsTheDatabaseNothing() throws Exception {
        providerStub.stub().respondBytes("image/png", png(256));
        Integer days = cache.wmsCacheDays;
        cache.wmsCacheDays = 7;
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean statisticsWereOn = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        Logger transactionLog = (Logger) LoggerFactory.getLogger(JpaTransactionManager.class);
        Level transactionLevel = transactionLog.getLevel();
        ListAppender<ILoggingEvent> transactions = new ListAppender<>();
        transactions.start();
        transactionLog.addAppender(transactions);
        transactionLog.setLevel(Level.DEBUG);
        try {
            // Over HTTP: the servlet is made at its first request, and the tile is fetched into the cache
            tile(as(accessToken())).when().get(WMS).then().statusCode(200).header(CACHE_HEADER, "MISS");
            HttpServlet wmsServlet = (HttpServlet) context
                    .getBean("wmsProxyServletRegistration", ServletRegistrationBean.class).getServlet();

            // The measures do count: a read of the settings table is one statement on one connection
            DatabaseUse before = databaseUse(statistics);
            Tx.requiringNew().call(() -> em.createQuery("select count(s) from Setting s").getSingleResult());
            DatabaseUse aRead = databaseUse(statistics).minus(before);
            assertTrue(aRead.statements() >= 1 && aRead.connections() >= 1 && aRead.poolConnections() >= 1
                    && aRead.transactions() >= 1, "the measures see a read: " + aRead);

            // The scheduled jobs of the application share the database and the measures; they can only add to a
            // count, so the least of a few measurements is the tile's own. The transactions begun meanwhile are
            // logged, so a failure names them
            DatabaseUse least = null;
            List<String> begun = List.of();
            for (int attempt = 0; attempt < 5 && (least == null || least.total() > 0); attempt++) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", WMS);
                request.setUserPrincipal(() -> "user");
                TILE_PARAMS.forEach(request::addParameter);
                MockHttpServletResponse response = new MockHttpServletResponse();
                transactions.list.clear();
                DatabaseUse start = databaseUse(statistics);
                wmsServlet.service(request, response);
                DatabaseUse used = databaseUse(statistics).minus(start);
                assertEquals(200, response.getStatus());
                assertEquals("HIT", response.getHeader(CACHE_HEADER), "the tile came from the cache");
                if (least == null || used.total() < least.total()) {
                    least = used;
                    begun = transactions.list.stream().map(ILoggingEvent::getFormattedMessage)
                            .filter(m -> m.startsWith("Creating new transaction")).toList();
                }
            }
            assertEquals(new DatabaseUse(0, 0, 0, 0), least,
                    "a tile from the cache: no statement, no connection, no transaction, for a setting or else;"
                            + " transactions begun meanwhile: " + begun);
        } finally {
            transactionLog.detachAppender(transactions);
            transactionLog.setLevel(transactionLevel);
            statistics.setStatisticsEnabled(statisticsWereOn);
            cache.wmsCacheDays = days;
            deleteProvidersFolder();
        }
    }

    // ------------------------------------------------------------------------------------------- //

    /** Saves the value through the Settings page's endpoint, as a sysadmin */
    private void put(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("value", value);
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/settings/setting/" + key)
                .then().statusCode(200);
    }

    private Object rowValue(String key) {
        return Tx.requiringNew().call(() -> em.find(Setting.class, key).getValue());
    }

    private static void deleteFolder(Path folder) throws IOException {
        if (Files.isDirectory(folder)) {
            try (Stream<Path> walk = Files.walk(folder)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }
}
