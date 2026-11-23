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

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsRefresher;
import org.niord.core.settings.SettingsService;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link org.niord.web.wms.WmsProxyServlet}: the HTTP contract and authorization
 * gate of the WMS tile proxy.
 *
 * <p><b>Endpoint shape.</b> Unlike the JAX-RS resources, this is a plain {@code @WebServlet} mapped at
 * {@code /wms/*} — it is <em>not</em> under the {@code /rest} base path, so requests target the bare path
 * {@code /wms/foo}. The servlet has no {@code @RolesAllowed} annotation; instead it enforces a
 * <em>programmatic</em> gate inside {@code doGet}: when the {@code wmsProtected} setting is {@code true}
 * (its default) and {@code request.getUserPrincipal() == null}, it sets status {@code 403} and writes an
 * HTML body containing "only available to authenticated users". Because the gate is programmatic rather
 * than container-enforced, an anonymous caller still reaches the handler and is rejected with {@code 403}
 * (never the container's {@code 401}). Any authenticated principal — regardless of role tier — passes the
 * gate, so both the lowest tier ({@code user}) and the highest ({@code sysadmin}) get through.
 *
 * <p><b>Blank-image fallback oracle.</b> Once an authenticated request passes the gate and no tile can be had,
 * the servlet answers the blank picture itself: status {@code 200}, {@code image/png} and the bytes of
 * {@code /img/blank.png}, with no {@code Location} header, and with the five-minute {@code Cache-Control} a blank
 * gets on the path without the tile cache (a tile gets a day). RestAssured is told not to
 * follow redirects, so a redirect would show as itself. The test settings name a provider that gives no tile, so
 * the answer comes from the failed fetch (WMS2 in be/docs/potential-errors.md).
 *
 * <p><b>Untestable area.</b> The actual outbound WMS fetch, the colour-masking transform, and the
 * binary-image response path cannot be exercised here because no upstream WMS provider is configured (and
 * adding one would require a live external service or an HTTP stub the test stack does not provide). Only
 * the authentication gate and the no-provider blank-image fallback are asserted.
 *
 * <p>The authenticated requests carry the {@code NiordDomain: niord-nw} header via {@link #as} to
 * mirror the other suites, though the servlet only inspects the presence of a principal, not its roles.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class WmsProxyServletITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    /** Bare servlet path — note: NOT under the {@code /rest} base path. */
    private static final String WMS = "/wms/foo";

    /** The blank picture, as the application serves it at /img/blank.png */
    private static final String BLANK_IMAGE = "META-INF/resources/img/blank.png";

    /** The setting the gate reads */
    private static final String PROTECTED = "wmsProtected";

    @Autowired
    SettingsService settingsService;

    @Autowired
    SettingsRefresher settingsRefresher;

    @PersistenceContext
    EntityManager em;

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    // ------------------------------------------------------------------------------------------- //
    // Programmatic auth gate: wmsProtected (default true) + no principal -> 403 with HTML message.
    // Anonymous reaches the handler, so the rejection is 403, not the container's 401.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anonymous_isForbiddenWith403AndAuthMessage() {
        given()
                .when().get(WMS + "?x=1")
                .then().statusCode(403)
                .body(containsString("only available to authenticated users"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Highest tier passes the gate; no tile to be had -> the blank image itself, no redirect.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sysadmin_passesGateAndGetsTheBlankImage() throws IOException {
        assertTheBlankImage(as(accessToken()).redirects().follow(false).when().get(WMS + "?x=1"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Lowest authenticated tier also passes the gate (the gate only checks for a principal, not a
    // role); no tile to be had -> the blank image itself, no redirect.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void user_passesGateAndGetsTheBlankImage() throws IOException {
        assertTheBlankImage(as(accessToken("user")).redirects().follow(false).when().get(WMS + "?x=1"));
    }

    /**
     * "No tile" answered directly: 200, a PNG, the blank picture's bytes and no Location header, which behind a
     * proxy could name another origin; a browser keeps it five minutes, in one {@code Cache-Control} header.
     */
    private static void assertTheBlankImage(Response response) throws IOException {
        response.then().statusCode(200)
                .contentType(containsString("image/png"))
                .header("Location", nullValue())
                .header("Cache-Control", "max-age=300");
        assertEquals(1, response.headers().getValues("Cache-Control").size(), "one Cache-Control header");
        byte[] blank;
        try (InputStream in = WmsProxyServletITTest.class.getClassLoader().getResourceAsStream(BLANK_IMAGE)) {
            assertNotNull(in, "the blank picture is on the class path");
            blank = in.readAllBytes();
        }
        assertArrayEquals(blank, response.asByteArray(), "the body is the blank picture");
    }

    // ------------------------------------------------------------------------------------------- //
    // A save of wmsProtected on the Settings page refreshes the servlet's field at once, though the
    // servlet is created only at the first request after start-up: the change applies to the next
    // request, without a restart. Every write commits, so each test puts the row back.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void switchingProtectionOffAndOnAppliesToTheNextRequest() throws IOException {
        Object original = rowValue(PROTECTED);
        // The servlet is created on its first request; this one makes sure it exists before the change
        given().when().get(WMS + "?x=1").then().statusCode(403);
        try {
            settingsService.set(PROTECTED, false);

            assertTheBlankImage(given().redirects().follow(false).when().get(WMS + "?x=1"));

            settingsService.set(PROTECTED, true);

            given().when().get(WMS + "?x=1")
                    .then().statusCode(403)
                    .body(containsString("only available to authenticated users"));
        } finally {
            writeRow(PROTECTED, original);
        }
    }

    /**
     * A row stored as the text "true", as some databases hold it (SETTINGS-8), protects; a text "False" opens. The
     * rows are written behind the cache, as another node's save is, and the field follows at the poll
     */
    @Test
    void aRowStoredAsTextIsReadByItsMeaning() throws IOException {
        Object original = rowValue(PROTECTED);
        given().when().get(WMS + "?x=1").then().statusCode(403);
        try {
            writeRow(PROTECTED, "true");
            given().when().get(WMS + "?x=1").then().statusCode(403);

            writeRow(PROTECTED, "False");
            assertTheBlankImage(given().redirects().follow(false).when().get(WMS + "?x=1"));
        } finally {
            writeRow(PROTECTED, original);
        }
    }

    private Object rowValue(String key) {
        return Tx.requiringNew().call(() -> em.find(Setting.class, key).getValue());
    }

    /**
     * Writes the row's value as it is, past the type check of the Settings page, and counts the change as another
     * node's save does; then runs this node's poll, which empties the cache and refreshes the fields
     */
    private void writeRow(String key, Object value) {
        Tx.requiringNew().run(() -> {
            em.find(Setting.class, key).setValue(value);
            Setting count = em.find(Setting.class, SettingsRefresher.CHANGE_COUNT.getKey());
            long before = count != null && count.getValue() instanceof Number n ? n.longValue() : 0L;
            settingsService.writeState(SettingsRefresher.CHANGE_COUNT, before + 1);
        });
        settingsRefresher.pollForChanges();
    }
}
