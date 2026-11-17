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
package org.niord.core.conf;

import org.junit.jupiter.api.Test;
import org.niord.test.CountryBuildGroup;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link SecurityConfig#frameOptionsWriter()}: which responses a page of this origin may frame.
 *
 * <p>Only the web app's silent-SSO page may be framed, and only by this origin: keycloak-js loads it in a
 * hidden iframe of the app. Every other response keeps Spring Security's default {@code DENY}, the API and
 * the app's own pages included. The header is sent on every response, but on the app's HTML documents
 * browsers apply the Content-Security-Policy's {@code frame-ancestors 'self'} ({@code WebAppHeadersFilter})
 * instead, so the app's own origin may frame them, which is what the silent-SSO page needs. Every HTML the
 * backend serves from user or country data is sandboxed (the repository's files, the report and mail previews,
 * {@code /assets/legal/} and {@code /assets/branding/}; a PDF is served from the repository without it), so
 * none of it can abuse that; the app's own pages, Keycloak and a country's build of the app are trusted.
 * {@code WebAppITTest} proves the same over HTTP.
 */
@CountryBuildGroup
class SecurityConfigFrameOptionsTest {

    private static String frameOptions(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        SecurityConfig.frameOptionsWriter().writeHeaders(request, response);
        return response.getHeader("X-Frame-Options");
    }

    @Test
    void theSilentSsoPageMayBeFramedByItsOwnOrigin() {
        assertEquals("SAMEORIGIN", frameOptions("/silent-check-sso.html"));
    }

    @Test
    void everythingElseMayNotBeFramed() {
        for (String path : new String[] {
                "/", "/index.html", "/messages", "/rest/public/v1/messages", "/silent-check-sso.js",
                "/silent-check-sso.html/x", "/sub/silent-check-sso.html"}) {
            assertEquals("DENY", frameOptions(path), path);
        }
    }
}
