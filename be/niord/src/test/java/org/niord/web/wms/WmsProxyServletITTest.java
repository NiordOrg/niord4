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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

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
 * <p><b>Blank-image fallback oracle.</b> The test application configures no {@code wmsProvider}, so once
 * an authenticated request passes the gate the servlet finds a blank provider, skips the outbound WMS
 * fetch entirely, and {@code sendRedirect}s to {@link org.niord.web.wms.WmsProxyServlet#BLANK_IMAGE}
 * ({@code /img/blank.png}). RestAssured is told not to follow redirects so the {@code 302} status and the
 * {@code Location} header can be asserted directly.
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

    /** The blank-image target the servlet redirects to when no WMS provider is configured. */
    private static final String BLANK_IMAGE = "/img/blank.png";

    /** The setting the gate reads */
    private static final String PROTECTED = "wmsProtected";

    @Autowired
    SettingsService settingsService;

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
    // Highest tier passes the gate; no provider configured -> 302 redirect to the blank image.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sysadmin_passesGateAndRedirectsToBlankImage() {
        as(accessToken())
                .redirects().follow(false)
                .when().get(WMS + "?x=1")
                .then().statusCode(302)
                .header("Location", containsString(BLANK_IMAGE));
    }

    // ------------------------------------------------------------------------------------------- //
    // Lowest authenticated tier also passes the gate (the gate only checks for a principal, not a
    // role); no provider configured -> 302 redirect to the blank image.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void user_passesGateAndRedirectsToBlankImage() {
        as(accessToken("user"))
                .redirects().follow(false)
                .when().get(WMS + "?x=1")
                .then().statusCode(302)
                .header("Location", containsString(BLANK_IMAGE));
    }

    // ------------------------------------------------------------------------------------------- //
    // The gate reads wmsProtected live (SECOM-UI-1, L-019): a change on the Settings page applies to
    // the next request, without a restart. Every write commits, so each test puts the row back.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void switchingProtectionOffAndOnAppliesToTheNextRequest() {
        Object original = rowValue(PROTECTED);
        // The servlet is created on its first request; this one makes sure it exists before the change
        given().when().get(WMS + "?x=1").then().statusCode(403);
        try {
            settingsService.set(PROTECTED, false);

            given().redirects().follow(false)
                    .when().get(WMS + "?x=1")
                    .then().statusCode(302)
                    .header("Location", containsString(BLANK_IMAGE));

            settingsService.set(PROTECTED, true);

            given().when().get(WMS + "?x=1")
                    .then().statusCode(403)
                    .body(containsString("only available to authenticated users"));
        } finally {
            writeRow(PROTECTED, original);
        }
    }

    /** A row stored as the text "true", as some databases hold it (SETTINGS-8), protects; a text "False" opens */
    @Test
    void aRowStoredAsTextIsReadByItsMeaning() {
        Object original = rowValue(PROTECTED);
        given().when().get(WMS + "?x=1").then().statusCode(403);
        try {
            writeRow(PROTECTED, "true");
            given().when().get(WMS + "?x=1").then().statusCode(403);

            writeRow(PROTECTED, "False");
            given().redirects().follow(false)
                    .when().get(WMS + "?x=1")
                    .then().statusCode(302);
        } finally {
            writeRow(PROTECTED, original);
        }
    }

    private Object rowValue(String key) {
        return Tx.requiringNew().call(() -> em.find(Setting.class, key).getValue());
    }

    /** Writes the row's value as it is, past the type check of the Settings page, and empties the cache */
    private void writeRow(String key, Object value) {
        Tx.requiringNew().run(() -> em.find(Setting.class, key).setValue(value));
        settingsService.evictFromCache(key);
    }
}
