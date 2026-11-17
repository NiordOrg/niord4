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
package org.niord.core.util;

import org.junit.jupiter.api.Test;
import org.niord.test.http.HttpStub;
import org.niord.test.http.HttpStub.StubResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-1 tests for {@link SameOriginFetch}: which URLs count as the application's own, and that a fetch
 * never leaves that origin, neither on the first request nor through a redirect. Two {@link HttpStub}s on
 * different ports play the application's own origin and some other host.
 */
class SameOriginFetchTest {

    private static final String BASE = "https://niord.example.com";

    // ------------------------------------------------------------------ isSameOrigin

    @Test
    void theApplicationsOwnUrlsAreItsOwn() {
        assertTrue(SameOriginFetch.isSameOrigin("https://niord.example.com/css/templates/pdf.css", BASE));
        assertTrue(SameOriginFetch.isSameOrigin("https://niord.example.com:443/img/logo.png", BASE));
        assertTrue(SameOriginFetch.isSameOrigin("https://NIORD.example.com/rest/repo/file/a.png?x=1", BASE + "/"));
        assertTrue(SameOriginFetch.isSameOrigin("HTTPS://niord.example.com/", BASE));
        assertTrue(SameOriginFetch.isSameOrigin("http://localhost:8080/img/x.png", "http://localhost:8080"));
    }

    @Test
    void anotherSchemeHostOrPortIsNotItsOwn() {
        assertFalse(SameOriginFetch.isSameOrigin("http://niord.example.com/img/x.png", BASE), "other scheme");
        assertFalse(SameOriginFetch.isSameOrigin("https://other.example.com/img/x.png", BASE), "other host");
        assertFalse(SameOriginFetch.isSameOrigin("https://niord.example.com.other.net/x.png", BASE), "suffixed host");
        assertFalse(SameOriginFetch.isSameOrigin("https://niord.example.com:8443/img/x.png", BASE), "other port");
        assertFalse(SameOriginFetch.isSameOrigin("http://localhost:8081/x.png", "http://localhost:8080"), "other port");
        assertFalse(SameOriginFetch.isSameOrigin("http://169.254.169.254/latest/meta-data/", BASE), "internal address");
    }

    @Test
    void otherUrlKindsAreNeverItsOwn() {
        assertFalse(SameOriginFetch.isSameOrigin("file:///etc/passwd", BASE));
        assertFalse(SameOriginFetch.isSameOrigin("file:///etc/passwd", "file:///"));
        assertFalse(SameOriginFetch.isSameOrigin("jar:https://niord.example.com/a.jar!/x.png", BASE));
        assertFalse(SameOriginFetch.isSameOrigin("ftp://niord.example.com/x.png", "ftp://niord.example.com"));
        assertFalse(SameOriginFetch.isSameOrigin("data:image/png;base64,AAAA", BASE));
        assertFalse(SameOriginFetch.isSameOrigin("https://user:pw@niord.example.com/x.png", BASE), "user info");
        assertFalse(SameOriginFetch.isSameOrigin("/img/x.png", BASE), "relative");
        assertFalse(SameOriginFetch.isSameOrigin("", BASE));
        assertFalse(SameOriginFetch.isSameOrigin(null, BASE));
    }

    @Test
    void withoutAUsableBaseUriNothingIsItsOwn() {
        assertFalse(SameOriginFetch.isSameOrigin("https://niord.example.com/x.png", ""));
        assertFalse(SameOriginFetch.isSameOrigin("https://niord.example.com/x.png", null));
        assertFalse(SameOriginFetch.isSameOrigin("https://niord.example.com/x.png", "/"));
    }

    // ------------------------------------------------------------------ isRedirectTarget

    @Test
    void aRedirectMayStayOnTheOrigin() {
        assertTrue(SameOriginFetch.isRedirectTarget("https://niord.example.com/img/map_image_placeholder.png", BASE));
        assertTrue(SameOriginFetch.isRedirectTarget("http://localhost:8080/img/x.png", "http://localhost:8080"));
    }

    @Test
    void aRedirectMaySwitchBetweenHttpAndHttpsOnTheBaseHostsDefaultPorts() {
        // Behind a proxy that ends TLS, the backend builds its own redirects with http and port 80
        assertTrue(SameOriginFetch.isRedirectTarget("http://niord.example.com/img/map_image_placeholder.png", BASE));
        assertTrue(SameOriginFetch.isRedirectTarget("http://niord.example.com:80/img/x.png", BASE + ":443"));
        // A proxy upgrades an http base URI to https
        assertTrue(SameOriginFetch.isRedirectTarget("https://niord.example.com/css/a.css", "http://niord.example.com"));
    }

    @Test
    void aRedirectToAnyOtherPlaceIsNotFollowed() {
        assertFalse(SameOriginFetch.isRedirectTarget("http://other.example.com/x.png", BASE), "other host");
        assertFalse(SameOriginFetch.isRedirectTarget("https://other.example.com/x.png", BASE), "other host");
        assertFalse(SameOriginFetch.isRedirectTarget("http://niord.example.com:8080/x.png", BASE), "other port");
        assertFalse(SameOriginFetch.isRedirectTarget("https://niord.example.com:8443/x.png", BASE), "other port");
        assertFalse(SameOriginFetch.isRedirectTarget("https://localhost/x.png", "http://localhost:8080"),
                "a base URI off the default port switches no scheme");
        assertFalse(SameOriginFetch.isRedirectTarget("http://localhost:443/x.png", "https://localhost"),
                "the other scheme on this scheme's port");
        assertFalse(SameOriginFetch.isRedirectTarget("http://user@niord.example.com/x.png", BASE), "user info");
        assertFalse(SameOriginFetch.isRedirectTarget("ftp://niord.example.com/x.png", BASE));
        assertFalse(SameOriginFetch.isRedirectTarget("file:///etc/passwd", BASE));
        assertFalse(SameOriginFetch.isRedirectTarget("jar:http://niord.example.com/a.jar!/x.png", BASE));
        assertFalse(SameOriginFetch.isRedirectTarget("http://niord.example.com/x.png", "ftp://niord.example.com"));
        assertFalse(SameOriginFetch.isRedirectTarget("http://niord.example.com/x.png", null));
    }

    @Test
    void theFirstRequestSwitchesNoScheme() {
        // Only a redirect the application answers with may switch; a URL in a document may not
        assertThrows(IOException.class, () -> SameOriginFetch.connect("http://niord.example.com/img/x.png", BASE));
    }

    // ------------------------------------------------------------------ open

    @Test
    void anOwnUrlIsFetched() throws Exception {
        try (HttpStub own = HttpStub.start()) {
            own.respondBytes("text/css", "p{}".getBytes(StandardCharsets.UTF_8));

            HttpURLConnection connection = SameOriginFetch.open(own.baseUrl() + "/css/a.css", own.baseUrl());

            assertEquals(200, connection.getResponseCode());
            assertEquals("p{}", read(connection));
            assertEquals("/css/a.css", own.lastRequest().path());
        }
    }

    @Test
    void anotherOriginIsRefusedBeforeAnyRequest() throws Exception {
        try (HttpStub own = HttpStub.start(); HttpStub other = HttpStub.start()) {
            assertThrows(IOException.class, () -> SameOriginFetch.open(other.baseUrl() + "/secret", own.baseUrl()));
            assertThrows(IOException.class, () -> SameOriginFetch.open("file:///etc/passwd", own.baseUrl()));

            assertEquals(0, other.requestCount());
            assertEquals(0, own.requestCount());
        }
    }

    @Test
    void aRedirectWithinTheOriginIsFollowed() throws Exception {
        try (HttpStub own = HttpStub.start()) {
            own.respond(req -> req.path().equals("/rest/message-map-image/1.png")
                    ? StubResponse.redirect(302, "/img/map_image_placeholder.png")
                    : StubResponse.bytes("image/png", new byte[] { 7 }));

            HttpURLConnection connection = SameOriginFetch.open(own.baseUrl() + "/rest/message-map-image/1.png", own.baseUrl());

            assertEquals(200, connection.getResponseCode());
            assertEquals(2, own.requestCount());
            assertEquals("/img/map_image_placeholder.png", own.lastRequest().path());
        }
    }

    @Test
    void aRedirectToAnotherOriginIsNotFollowed() throws Exception {
        try (HttpStub own = HttpStub.start(); HttpStub other = HttpStub.start()) {
            own.respondWith(StubResponse.redirect(302, other.baseUrl() + "/internal"));

            assertThrows(IOException.class, () -> SameOriginFetch.open(own.baseUrl() + "/a.png", own.baseUrl()));

            assertEquals(1, own.requestCount());
            assertEquals(0, other.requestCount(), "the other origin must never be asked");
        }
    }

    @Test
    void aRedirectSwitchingSchemeOffTheDefaultPortsIsNotFollowed() throws Exception {
        try (HttpStub own = HttpStub.start()) {
            String https = own.baseUrl().replace("http://", "https://");
            own.respondWith(StubResponse.redirect(302, https + "/img/x.png"));

            IOException refused = assertThrows(IOException.class,
                    () -> SameOriginFetch.open(own.baseUrl() + "/a.png", own.baseUrl()));

            assertTrue(refused.getMessage().startsWith("Not fetched"), refused.getMessage());
            assertEquals(1, own.requestCount());
        }
    }

    @Test
    void redirectsAreBounded() throws Exception {
        try (HttpStub own = HttpStub.start()) {
            own.respondWith(StubResponse.redirect(302, "/loop"));

            assertThrows(IOException.class, () -> SameOriginFetch.open(own.baseUrl() + "/loop", own.baseUrl()));

            assertEquals(SameOriginFetch.MAX_REDIRECTS + 1, own.requestCount());
        }
    }

    private static String read(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
