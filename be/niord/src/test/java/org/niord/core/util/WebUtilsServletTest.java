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
package org.niord.core.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the servlet/RESTEasy-dependent helpers in {@link WebUtils} that the sibling
 * {@link WebUtilsTest} deliberately leaves uncovered:
 * {@link WebUtils#getWebAppUrl(HttpServletRequest, String...)},
 * {@link WebUtils#getServletUrl(HttpServletRequest, String...)},
 * {@link WebUtils#getCookie(HttpServletRequest, String)},
 * {@link WebUtils#getCookieValue(HttpServletRequest, String)},
 * {@link WebUtils#nocache(HttpServletResponse)},
 * {@link WebUtils#cache(HttpServletResponse, int)},
 * {@link WebUtils#readRequestBody(HttpServletRequest)} and the package-protected
 * {@code getFileName(MultivaluedMap)}.
 *
 * <p>These tests stay framework-neutral by stubbing the container-owned servlet interfaces with
 * Mockito mocks rather than starting a container, and assert the observable behaviour: the composed
 * URLs, the matched cookie, the exact response headers (captured via {@link ArgumentCaptor}) and the
 * concatenated request body. The class lives in the same package as {@link WebUtils} so it can reach
 * the package-protected {@code getFileName}.
 */
public class WebUtilsServletTest {

    // ------------------------------------------------------------------
    // getWebAppUrl(HttpServletRequest, String...)
    // ------------------------------------------------------------------

    @Test
    public void getWebAppUrlOmitsPortForHttpPort80() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("niord.dk");
        when(request.getServerPort()).thenReturn(80);
        when(request.getContextPath()).thenReturn("/niord");

        assertEquals("http://niord.dk/niord", WebUtils.getWebAppUrl(request));
    }

    @Test
    public void getWebAppUrlOmitsPortForHttpsPort443() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("niord.dk");
        when(request.getServerPort()).thenReturn(443);
        when(request.getContextPath()).thenReturn("/niord");

        assertEquals("https://niord.dk/niord", WebUtils.getWebAppUrl(request));
    }

    @Test
    public void getWebAppUrlAppendsNonStandardPort() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getContextPath()).thenReturn("");

        assertEquals("http://localhost:8080", WebUtils.getWebAppUrl(request));
    }

    @Test
    public void getWebAppUrlAppendsVarargsInOrder() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getContextPath()).thenReturn("/niord");

        assertEquals("http://localhost:8080/niord/rest/messages",
                WebUtils.getWebAppUrl(request, "/rest", "/messages"));
    }

    // ------------------------------------------------------------------
    // getServletUrl(HttpServletRequest, String...)
    // ------------------------------------------------------------------

    @Test
    public void getServletUrlPrefixesServletPathThenAppends() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);
        when(request.getContextPath()).thenReturn("/niord");
        when(request.getServletPath()).thenReturn("/rest");

        // servletPath ("/rest") is inserted before the supplied varargs ("/details").
        assertEquals("http://localhost:8080/niord/rest/details",
                WebUtils.getServletUrl(request, "/details"));
    }

    @Test
    public void getServletUrlWithNoAppendsUsesOnlyServletPath() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getScheme()).thenReturn("https");
        when(request.getServerName()).thenReturn("niord.dk");
        when(request.getServerPort()).thenReturn(443);
        when(request.getContextPath()).thenReturn("/niord");
        when(request.getServletPath()).thenReturn("/rest");

        assertEquals("https://niord.dk/niord/rest", WebUtils.getServletUrl(request));
    }

    // ------------------------------------------------------------------
    // getCookie(HttpServletRequest, String)
    // ------------------------------------------------------------------

    @Test
    public void getCookieReturnsMatchingCookie() {
        Cookie wanted = new Cookie("JSESSIONID", "abc123");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie("locale", "en"), wanted, new Cookie("theme", "dark")});

        assertSame(wanted, WebUtils.getCookie(request, "JSESSIONID"));
    }

    @Test
    public void getCookieReturnsNullWhenNoCookieMatches() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie("locale", "en"), new Cookie("theme", "dark")});

        assertNull(WebUtils.getCookie(request, "JSESSIONID"));
    }

    @Test
    public void getCookieReturnsNullWhenCookiesAreNull() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(null);

        assertNull(WebUtils.getCookie(request, "JSESSIONID"));
    }

    // ------------------------------------------------------------------
    // getCookieValue(HttpServletRequest, String)
    // ------------------------------------------------------------------

    @Test
    public void getCookieValueReturnsValueOfMatchingCookie() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{
                new Cookie("locale", "da"), new Cookie("JSESSIONID", "abc123")});

        assertEquals("abc123", WebUtils.getCookieValue(request, "JSESSIONID"));
    }

    @Test
    public void getCookieValueReturnsNullWhenNoCookieMatches() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("locale", "da")});

        assertNull(WebUtils.getCookieValue(request, "JSESSIONID"));
    }

    // ------------------------------------------------------------------
    // nocache(HttpServletResponse)
    // ------------------------------------------------------------------

    @Test
    public void nocacheSetsNoStoreNoCacheAndZeroExpires() {
        HttpServletResponse response = mock(HttpServletResponse.class);

        HttpServletResponse returned = WebUtils.nocache(response);

        // The method returns the very same response it was given.
        assertSame(response, returned);
        // Both Cache-Control directives survive: setHeader("no-cache") then addHeader("no-store").
        verify(response).setHeader("Cache-Control", "no-cache");
        verify(response).addHeader("Cache-Control", "no-store");
        verify(response).setHeader("Pragma", "no-cache");
        verify(response).setDateHeader("Expires", 0L);
    }

    // ------------------------------------------------------------------
    // cache(HttpServletResponse, int)
    // ------------------------------------------------------------------

    @Test
    public void cacheSetsMaxAgeLastModifiedAndExpiresRelativeToNow() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        int seconds = 3600;

        long before = System.currentTimeMillis();
        HttpServletResponse returned = WebUtils.cache(response, seconds);
        long after = System.currentTimeMillis();

        assertSame(response, returned);

        // max-age header carries the literal seconds value.
        verify(response).addHeader("Cache-Control", "max-age=" + seconds);

        // Capture the two date headers and assert their exact relationship.
        ArgumentCaptor<Long> lastModified = ArgumentCaptor.forClass(Long.class);
        verify(response).setDateHeader(org.mockito.ArgumentMatchers.eq("Last-Modified"), lastModified.capture());
        ArgumentCaptor<Long> expires = ArgumentCaptor.forClass(Long.class);
        verify(response).setDateHeader(org.mockito.ArgumentMatchers.eq("Expires"), expires.capture());

        long now = lastModified.getValue();
        // 'now' was sampled inside cache() between our before/after fences.
        org.junit.jupiter.api.Assertions.assertTrue(now >= before && now <= after,
                "Last-Modified must equal the in-method now timestamp");
        // Expires is exactly now + seconds*1000.
        assertEquals(now + seconds * 1000L, (long) expires.getValue());
    }

    // ------------------------------------------------------------------
    // readRequestBody(HttpServletRequest)
    // ------------------------------------------------------------------

    @Test
    public void readRequestBodyConcatenatesLinesEachTerminatedByNewline() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        BufferedReader reader = new BufferedReader(new StringReader("line1\nline2\nline3"));
        when(request.getReader()).thenReturn(reader);

        // Every line read (including the last, which had no trailing newline) gets a '\n' appended.
        assertEquals("line1\nline2\nline3\n", WebUtils.readRequestBody(request));
    }

    @Test
    public void readRequestBodyReturnsEmptyStringForEmptyBody() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        BufferedReader reader = new BufferedReader(new StringReader(""));
        when(request.getReader()).thenReturn(reader);

        assertEquals("", WebUtils.readRequestBody(request));
    }

    // NB: the getFileName(MultivaluedMap)/getMultipartInput* RESTEasy multipart helpers were removed in the
    // Jersey->Spring-MVC migration (replaced by the MultipartHttpServletRequest-based WebUtils.getMultipartFiles
    // / getMultipartFormParams, covered by the integration tests), so their unit tests were removed here.
}
