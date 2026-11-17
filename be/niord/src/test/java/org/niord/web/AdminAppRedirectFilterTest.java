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
package org.niord.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminAppRedirectFilter}, which answers a GET or HEAD for the backend's root with a
 * {@code 302} to the {@code adminAppUrl} setting.
 *
 * <p>The contract has two sides, and both are pinned. A request for the root is redirected, with the
 * query string carried along and the configured URL normalised to end in exactly one slash. Everything
 * else passes on to the chain untouched: every other path and method, the root when no administration
 * web app is configured, and the root when the configured URL names the request's own origin, where the
 * redirect would loop. A request for any other path does not even read the setting.
 *
 * <p>Mock servlet plumbing only, no container: the filter's single dependency is a mocked {@link NiordApp}.
 */
public class AdminAppRedirectFilterTest {

    private AdminAppRedirectFilter filter;
    private NiordApp app;

    @BeforeEach
    public void setUp() {
        app = mock(NiordApp.class);
        filter = new AdminAppRedirectFilter();
        filter.app = app;
    }

    /** A request that came in on http://localhost:8888 */
    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServerName("localhost");
        request.setServerPort(8888);
        return request;
    }

    /** Runs the filter and returns the response, asserting that it redirected rather than passed on */
    private MockHttpServletResponse redirected(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNull(chain.getRequest(), "a redirected request must not reach the rest of the chain");
        assertEquals(302, response.getStatus());
        return response;
    }

    /** Runs the filter and asserts that the request passed on to the chain untouched */
    private void passedOn(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNotNull(chain.getRequest(), "the request must pass on to the rest of the chain");
        assertEquals(200, response.getStatus(), "the filter must leave the response alone");
        assertNull(response.getHeader("Location"));
    }

    // ------------------------------------------------------------------------------------------- //
    // The root is redirected to the administration web app
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void redirectsTheRootToTheAdminApp() throws Exception {
        when(app.getAdminAppUrl()).thenReturn("http://localhost:4100");

        MockHttpServletResponse response = redirected(request("GET", "/"));

        assertEquals("http://localhost:4100/", response.getHeader("Location"));
    }

    @Test
    public void carriesTheQueryStringAlong() throws Exception {
        when(app.getAdminAppUrl()).thenReturn("https://admin.example.org");
        MockHttpServletRequest request = request("GET", "/");
        request.setQueryString("lang=da&x=%20y");

        MockHttpServletResponse response = redirected(request);

        assertEquals("https://admin.example.org/?lang=da&x=%20y", response.getHeader("Location"));
    }

    @Test
    public void endsTheLocationInExactlyOneSlash() throws Exception {
        when(app.getAdminAppUrl()).thenReturn(" https://admin.example.org/niord// ");

        MockHttpServletResponse response = redirected(request("GET", "/"));

        assertEquals("https://admin.example.org/niord/", response.getHeader("Location"));
    }

    @Test
    public void redirectsAHeadRequestToo() throws Exception {
        when(app.getAdminAppUrl()).thenReturn("https://admin.example.org");

        MockHttpServletResponse response = redirected(request("HEAD", "/"));

        assertEquals("https://admin.example.org/", response.getHeader("Location"));
    }

    @Test
    public void redirectsTheRootOfTheContext() throws Exception {
        when(app.getAdminAppUrl()).thenReturn("https://admin.example.org");
        MockHttpServletRequest request = request("GET", "/niord/");
        request.setContextPath("/niord");

        MockHttpServletResponse response = redirected(request);

        assertEquals("https://admin.example.org/", response.getHeader("Location"));
    }

    @Test
    public void anotherPortOnTheSameHostIsAnotherOrigin() throws Exception {
        when(app.getAdminAppUrl()).thenReturn("http://localhost");

        MockHttpServletResponse response = redirected(request("GET", "/"));

        assertEquals("http://localhost/", response.getHeader("Location"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Everything else passes on
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void passesOnWhenNoAdminAppIsConfigured() throws Exception {
        for (String adminAppUrl : new String[]{null, "", "  ", "/"}) {
            when(app.getAdminAppUrl()).thenReturn(adminAppUrl);
            passedOn(request("GET", "/"));
        }
    }

    @Test
    public void passesOnEveryOtherPathWithoutReadingTheSetting() throws Exception {
        for (String uri : new String[]{"/rest/messages", "/img/company-logo.png", "/messages", "/index.html", "//"}) {
            passedOn(request("GET", uri));
        }
        verifyNoInteractions(app);
    }

    @Test
    public void passesOnEveryOtherMethod() throws Exception {
        when(app.getAdminAppUrl()).thenReturn("https://admin.example.org");
        for (String method : new String[]{"POST", "PUT", "DELETE", "OPTIONS"}) {
            passedOn(request(method, "/"));
        }
    }

    /** Redirecting the root to the backend's own origin would bring the browser straight back */
    @Test
    public void passesOnWhenTheAdminAppUrlNamesTheRequestsOwnOrigin() throws Exception {
        for (String adminAppUrl : new String[]{
                "http://localhost:8888", "http://LOCALHOST:8888/", "HTTP://localhost:8888/admin"}) {
            when(app.getAdminAppUrl()).thenReturn(adminAppUrl);
            passedOn(request("GET", "/"));
        }
    }

    @Test
    public void theDefaultPortOfTheSchemeIsTheSameOrigin() throws Exception {
        when(app.getAdminAppUrl()).thenReturn("https://niord.example.org/");
        MockHttpServletRequest request = request("GET", "/");
        request.setScheme("https");
        request.setServerName("niord.example.org");
        request.setServerPort(443);

        passedOn(request);
    }

    /** A URL without a host resolves against the request, so it names the request's own origin */
    @Test
    public void passesOnWhenTheAdminAppUrlHasNoHostOrDoesNotParse() throws Exception {
        for (String adminAppUrl : new String[]{"/admin", "admin.example.org", "http://exa mple.org"}) {
            when(app.getAdminAppUrl()).thenReturn(adminAppUrl);
            passedOn(request("GET", "/"));
        }
    }
}
