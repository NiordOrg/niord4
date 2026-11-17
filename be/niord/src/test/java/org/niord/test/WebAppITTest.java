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
package org.niord.test;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.niord.core.settings.SettingsService;
import org.niord.web.WebAppContentSecurityPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The web app contract: with {@code niord.web-app.dir} set, the backend serves the administration web app
 * at the root of its origin, next to the API and the server-side assets.
 * <p>
 * The directory is a small fixture ({@code src/test/resources/web-app-fixture/}) in the shape of an Angular
 * production build: an {@code index.html}, a content-hashed {@code main-*.js}, the silent-SSO page and an
 * {@code assets/} folder. The test pins what a browser relies on:
 * <ul>
 *   <li>a navigation to the root or to one of the app's own addresses gets {@code index.html}, and so does any
 *   client that asks for the root, whatever it accepts;</li>
 *   <li>the build's files are served, the hashed ones cached for a year as immutable and the rest
 *   revalidated on every use;</li>
 *   <li>the silent-SSO page may be framed by the app's own origin, and by no other; every other response
 *   carries DENY, though on the app's HTML documents the policy's frame-ancestors 'self' is what browsers
 *   apply;</li>
 *   <li>the app's HTML documents carry the web app's Content-Security-Policy, once, and nothing else does;
 *   a legal document or a branding file a country supplies is sandboxed and never sniffed;</li>
 *   <li>the API, the server-side assets and a missing file answer as they do without a web app.</li>
 * </ul>
 * Setting the property gives this class an application context of its own; {@link DirtiesContext} closes
 * it after the class, so the shared context the rest of the suite runs on never serves a web app.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebAppITTest extends ITTest {

    /** What a browser sends when it navigates */
    private static final String BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    /** A line only the fixture's index page carries */
    private static final String INDEX_MARKER = "niord-web-app-fixture index";

    @Autowired
    SettingsService settingsService;

    @Autowired
    Environment environment;

    @DynamicPropertySource
    static void webAppDir(DynamicPropertyRegistry registry) {
        registry.add("niord.web-app.dir", WebAppITTest::fixtureDir);
    }

    private static String fixtureDir() {
        try {
            return Path.of(Objects.requireNonNull(
                    WebAppITTest.class.getResource("/web-app-fixture/index.html")).toURI())
                    .getParent().toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Navigations get the app
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theRootServesTheApp() {
        Response response = navigate("/");

        assertIndexPage(response, "/");
    }

    @Test
    void theRootServesTheAppToAClientThatIsNoBrowser() {
        // curl sends */*; a monitoring probe of the bare hostname must get 200, as the old Niord's root gave
        // the operator's external check (devops-shared, open issue 10).
        Response response = given().accept("*/*").redirects().follow(false).when().get("/");

        assertIndexPage(response, "/ for curl");
        assertFalse(response.headers().getValues("Content-Security-Policy").isEmpty(),
                "the page the root gets is the app's HTML document, with its policy");
    }

    @Test
    void theAppsOwnAddressesServeTheApp() {
        assertIndexPage(navigate("/messages"), "/messages");
        assertIndexPage(navigate("/editor/edit/1234"), "/editor/edit/1234");
    }

    @Test
    void aMessageLinkServesTheApp() {
        Response response = given().accept(BROWSER_ACCEPT).redirects().follow(false)
                .when().get("/messages?messageId=NW-001-26");

        assertIndexPage(response, "a message link");
    }

    @Test
    void aRequestThatDoesNotAcceptHtmlGetsNoPage() {
        Response response = given().accept("application/json").redirects().follow(false)
                .when().get("/messages");

        assertEquals(404, response.statusCode(), "only a navigation may get the app's index page");
        assertFalse(response.asString().contains(INDEX_MARKER));
    }

    // ------------------------------------------------------------------------------------------- //
    // The build's files
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aHashedFileIsCachedForAYearAsImmutable() {
        Response response = given().when().get("/main-FIXTURE1.js");

        assertEquals(200, response.statusCode());
        assertTrue(response.asString().contains("niord-web-app-fixture main"));
        String cacheControl = response.header("Cache-Control");
        assertTrue(cacheControl.contains("max-age=31536000") && cacheControl.contains("immutable"),
                "a content-hashed file never changes, was Cache-Control: " + cacheControl);
    }

    @Test
    void theIndexPageIsRevalidatedOnEveryUse() {
        Response response = given().when().get("/index.html");

        assertEquals(200, response.statusCode());
        assertTrue(response.asString().contains(INDEX_MARKER));
        assertEquals("no-cache", response.header("Cache-Control"),
                "a new release must reach the browser at once");
    }

    @Test
    void anAssetIsServedAndRevalidated() {
        Response response = given().when().get("/assets/i18n/en.json");

        assertEquals(200, response.statusCode());
        assertTrue(response.asString().contains("niord-web-app-fixture en"));
        assertEquals("no-cache", response.header("Cache-Control"));
    }

    @Test
    void theSilentSsoPageMayBeFramedByItsOwnOrigin() {
        Response response = given().accept(BROWSER_ACCEPT).when().get("/silent-check-sso.html");

        assertEquals(200, response.statusCode());
        assertTrue(response.asString().contains("niord-web-app-fixture silent-check-sso"));
        assertEquals("SAMEORIGIN", response.header("X-Frame-Options"),
                "keycloak-js loads the page in an iframe of the app; another origin must not frame it");
    }

    @Test
    void aMissingFileAnswers404() {
        assertEquals(404, given().when().get("/chunk-MISSING1.js").statusCode());
        assertEquals(404, given().when().get("/missing.html").statusCode());
        assertEquals(404, given().when().get("/assets/i18n/xx.json").statusCode());
    }

    // ------------------------------------------------------------------------------------------- //
    // The security headers
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theAppsHtmlDocumentsCarryThePolicyOnce() {
        String expected = new WebAppContentSecurityPolicy(() -> settingsService,
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri")).current();

        for (String path : new String[] {"/", "/messages", "/index.html", "/silent-check-sso.html"}) {
            Response response = navigate(path);

            assertEquals(200, response.statusCode(), path);
            assertEquals(List.of(expected), response.headers().getValues("Content-Security-Policy"), path);
        }
    }

    @Test
    void nothingElseCarriesThePolicy() {
        assertNoPolicy(given().accept("application/json").when().get("/rest/public/v1/messages"),
                "/rest/public/v1/messages");
        assertNoPolicy(given().when().get("/main-FIXTURE1.js"), "/main-FIXTURE1.js");
        assertNoPolicy(given().when().get("/assets/i18n/en.json"), "/assets/i18n/en.json");
    }

    @Test
    void aCountrysFilesAreSandboxedAndNeverSniffed() {
        for (String path : new String[] {"/assets/legal/disclaimer.en.html", "/assets/branding/en.json"}) {
            Response response = given().accept(BROWSER_ACCEPT).redirects().follow(false).when().get(path);

            assertEquals(200, response.statusCode(), path);
            assertEquals(List.of("sandbox"), response.headers().getValues("Content-Security-Policy"),
                    path + " is a country's data, never code of this application");
            assertEquals(List.of("nosniff"), response.headers().getValues("X-Content-Type-Options"), path);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The server's own surfaces are unaffected
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theApiIsUnaffected() {
        Response response = given().accept("application/json").when().get("/rest/public/v1/messages");

        assertEquals(200, response.statusCode());
        assertTrue(response.contentType().startsWith("application/json"), "was " + response.contentType());
        assertEquals("DENY", response.header("X-Frame-Options"), "only the silent-SSO page may be framed");
    }

    @Test
    void anApiPathOpenedInTheBrowserGetsNoPage() {
        Response response = given().accept(BROWSER_ACCEPT).redirects().follow(false)
                .when().get("/rest/no-such-endpoint");

        assertEquals(404, response.statusCode());
        assertFalse(response.asString().contains(INDEX_MARKER));
    }

    @Test
    void theServerSideAssetsAreStillServed() {
        Response response = given().when().get("/img/blank.png");

        assertEquals(200, response.statusCode());
        assertTrue(response.contentType().startsWith("image/png"), "was " + response.contentType());
    }

    private static Response navigate(String path) {
        return given().accept(BROWSER_ACCEPT).redirects().follow(false).when().get(path);
    }

    private static void assertNoPolicy(Response response, String what) {
        assertEquals(200, response.statusCode(), what);
        assertEquals(List.of(), response.headers().getValues("Content-Security-Policy"),
                what + " is not an HTML document of the app");
    }

    private static void assertIndexPage(Response response, String what) {
        assertEquals(200, response.statusCode(), what + " must be served");
        assertTrue(response.contentType().startsWith("text/html"), what + " was " + response.contentType());
        assertTrue(response.asString().contains(INDEX_MARKER), what + " must get the app's index page");
        assertEquals("no-cache", response.header("Cache-Control"));
        assertEquals("DENY", response.header("X-Frame-Options"), "only the silent-SSO page may be framed");
    }
}
