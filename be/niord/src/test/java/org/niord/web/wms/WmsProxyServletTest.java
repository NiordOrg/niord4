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
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.niord.core.settings.SettingsService;
import org.niord.test.http.HttpStub;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link WmsProxyServlet} {@code wmsProtected} security gate (WEB4).
 *
 * <p>The gate reads the {@code wmsProtected} setting through {@link SettingsService} on every request, so a change
 * on the Settings page applies at once (SECOM-UI-1, L-019); the tests hand the servlet a mocked
 * {@link SettingsService} ({@link #protect}). The setting has a {@code "true"} default. If it ever resolves to
 * {@code null} (explicit null override / misconfiguration), the auto-unbox in the original
 * {@code if (wmsProtected && ...)} would throw a {@code NullPointerException} — HTTP 500 — and, worse, a
 * security gate would fail by <em>crashing</em> rather than defaulting to the safe (protected) value. The
 * hardened guard treats {@code null} as protected while leaving the shipped {@code true}/{@code false} behaviour
 * unchanged; only {@code false}, as a boolean or as a text in any case, opens the tiles, so a row stored as the
 * text {@code "true"} protects, and a setting that cannot be read protects too. These tests pin the cases at the
 * top of {@code doGet}, using Mockito servlet doubles (no container).
 *
 * <p>They also pin the query sent to the WMS provider ({@link WmsProxyServlet#buildUpstreamQuery}): only
 * the tile parameters are forwarded, every value is encoded, and the token and configured layers cannot be
 * replaced or cut off. And, with a logback {@link ListAppender} on the servlet's logger and an
 * {@link HttpStub} as a failing provider, that the WMS token never reaches a log line.
 *
 * <p>The last group pins the tile cache from the servlet's side, with a {@link WmsTileCache} on a temporary folder
 * and an {@link HttpStub} as the provider: a user's tile is fetched once and then answered from the cache; an
 * anonymous caller never gets a cached tile, whether the proxy is protected or not; and with the cache off, or for
 * a request that is no tile, the servlet answers as it did before the cache existed.
 */
class WmsProxyServletTest {

    private HttpServletRequest anonymousRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getUserPrincipal()).thenReturn(null);
        return req;
    }

    private HttpServletResponse responseWithWriter() throws Exception {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return resp;
    }

    @Test
    void nullProtectedFlagFailsSafeAndForbidsAnonymous() throws Exception {
        WmsProxyServlet servlet = new WmsProxyServlet();
        protect(servlet, null); // misconfiguration / explicit null override

        HttpServletRequest req = anonymousRequest();
        HttpServletResponse resp = responseWithWriter();

        // Before the fix this NPE'd on the auto-unbox (HTTP 500); now null means PROTECTED.
        assertDoesNotThrow(() -> servlet.doGet(req, resp),
                "a null wmsProtected flag must not NPE the auto-unbox");
        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
        // The gate returns before proxying, so the request is never inspected for WMS parameters.
        verify(req, never()).getParameterMap();
    }

    @Test
    void trueProtectedFlagForbidsAnonymous() throws Exception {
        WmsProxyServlet servlet = new WmsProxyServlet();
        protect(servlet, Boolean.TRUE); // the shipped default

        HttpServletRequest req = anonymousRequest();
        HttpServletResponse resp = responseWithWriter();

        servlet.doGet(req, resp);
        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void falseProtectedFlagDoesNotForbidAnonymous() throws Exception {
        WmsProxyServlet servlet = new WmsProxyServlet();
        protect(servlet, Boolean.FALSE); // gate explicitly disabled

        HttpServletRequest req = anonymousRequest();
        HttpServletResponse resp = responseWithWriter();

        // The gate does not fire; with a blank wmsProvider the servlet redirects to the blank image and
        // never issues the 403 the protected branch would.
        servlet.doGet(req, resp);
        verify(resp, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    /** Gives the servlet a settings service whose {@code wmsProtected} reads as the given value */
    private static void protect(WmsProxyServlet servlet, Object value) {
        SettingsService settings = mock(SettingsService.class);
        when(settings.get(WmsProxyServlet.PROTECTED_KEY)).thenReturn(value);
        servlet.settingsService = settings;
    }

    @Test
    void theSettingIsReadOnEveryRequest() throws Exception {
        WmsProxyServlet servlet = new WmsProxyServlet();
        SettingsService settings = mock(SettingsService.class);
        when(settings.get(WmsProxyServlet.PROTECTED_KEY)).thenReturn(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE);
        servlet.settingsService = settings;

        HttpServletResponse protectedAnswer = responseWithWriter();
        servlet.doGet(anonymousRequest(), protectedAnswer);
        HttpServletResponse openAnswer = responseWithWriter();
        servlet.doGet(anonymousRequest(), openAnswer);
        HttpServletResponse protectedAgain = responseWithWriter();
        servlet.doGet(anonymousRequest(), protectedAgain);

        verify(protectedAnswer).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(openAnswer, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(protectedAgain).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(settings, times(3)).get(WmsProxyServlet.PROTECTED_KEY);
    }

    @Test
    void aSettingThatCannotBeReadForbidsAnonymous() throws Exception {
        WmsProxyServlet servlet = new WmsProxyServlet();
        SettingsService settings = mock(SettingsService.class);
        when(settings.get(WmsProxyServlet.PROTECTED_KEY)).thenThrow(new IllegalStateException("database down"));
        servlet.settingsService = settings;

        HttpServletRequest req = anonymousRequest();
        HttpServletResponse resp = responseWithWriter();
        assertDoesNotThrow(() -> servlet.doGet(req, resp), "an unreadable setting must not fail the request");
        verify(resp).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void onlyFalseOpensTheTiles() {
        assertFalse(WmsProxyServlet.isProtected(Boolean.FALSE));
        for (String off : new String[] { "false", "FALSE", "False", " false " }) {
            assertFalse(WmsProxyServlet.isProtected(off), "'" + off + "'");
        }
        // A row stored as the text "true" (SETTINGS-8), a missing value and anything unclear protect
        for (Object on : new Object[] { Boolean.TRUE, "true", "TRUE", null, "", "no", "off", "0", 0 }) {
            assertTrue(WmsProxyServlet.isProtected(on), "'" + on + "'");
        }
    }

    @Test
    void aStoredTextTrueForbidsAnonymousAndATextFalseDoesNot() throws Exception {
        WmsProxyServlet servlet = new WmsProxyServlet();
        protect(servlet, "true");
        HttpServletResponse protectedAnswer = responseWithWriter();
        servlet.doGet(anonymousRequest(), protectedAnswer);
        verify(protectedAnswer).setStatus(HttpServletResponse.SC_FORBIDDEN);

        protect(servlet, "False");
        HttpServletResponse openAnswer = responseWithWriter();
        servlet.doGet(anonymousRequest(), openAnswer);
        verify(openAnswer, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    // ------------------------------------------------------------------ the upstream query

    /** Request parameters in the order given, as the servlet container hands them over */
    private static Map<String, String[]> params(String... keyValues) {
        Map<String, String[]> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put(keyValues[i], new String[] { keyValues[i + 1] });
        }
        return params;
    }

    /** The upstream query as the provider decodes it: name -> values, in order */
    private static Map<String, List<String>> decoded(String query) {
        Map<String, List<String>> decoded = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            String[] nameValue = pair.split("=", 2);
            decoded.computeIfAbsent(URLDecoder.decode(nameValue[0], StandardCharsets.UTF_8), k -> new ArrayList<>())
                    .add(nameValue.length > 1 ? URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8) : "");
        }
        return decoded;
    }

    @Test
    void theTileParametersAreForwardedEncodedUnderTheirUpperCaseNames() {
        String query = WmsProxyServlet.buildUpstreamQuery(params(
                "service", "WMS", "VERSION", "1.3.0", "REQUEST", "GetMap", "FORMAT", "image/png",
                "TRANSPARENT", "TRUE", "STYLES", "", "WIDTH", "256", "HEIGHT", "256",
                "CRS", "EPSG:3857", "BBOX", "1,2,3,4", "LAYERS", "cells,roads"), null, null);

        // The list separators and punctuation of the WMS values go out as a map sends them
        assertEquals("SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&FORMAT=image/png&TRANSPARENT=TRUE&STYLES="
                + "&WIDTH=256&HEIGHT=256&CRS=EPSG:3857&BBOX=1,2,3,4&LAYERS=cells,roads", query);
    }

    @Test
    void whatStructuresAQueryIsEncodedInAValue() {
        assertEquals("a%26b%3Dc%23d%2Be%25f+g%3Fh", WmsProxyServlet.encodeValue("a&b=c#d+e%f g?h"));
        // A literal escape in a value is encoded, not taken for one of the kept characters
        assertEquals("%252C%253A%252F", WmsProxyServlet.encodeValue("%2C%3A%2F"));
        assertEquals("1,2:3/4", WmsProxyServlet.encodeValue("1,2:3/4"));
    }

    @Test
    void anEncodedSeparatorStaysInsideItsValue() {
        // Decoded by the container, these values would add a LAYERS parameter and cut the token off
        String query = WmsProxyServlet.buildUpstreamQuery(params(
                "BBOX", "1,2,3,4&LAYERS=hidden", "FORMAT", "image/png#", "WIDTH", "256=1"), "tok", "layerA");

        Map<String, List<String>> upstream = decoded(query);
        assertEquals(List.of("1,2,3,4&LAYERS=hidden"), upstream.get("BBOX"));
        assertEquals(List.of("image/png#"), upstream.get("FORMAT"));
        assertEquals(List.of("256=1"), upstream.get("WIDTH"));
        assertEquals(List.of("layerA"), upstream.get("LAYERS"), "one LAYERS, the configured one");
        assertEquals(List.of("tok"), upstream.get("TOKEN"), "the token is not cut off");
        assertFalse(query.contains("#"));
    }

    @Test
    void anythingElseAndACallersTokenAreDropped() {
        String query = WmsProxyServlet.buildUpstreamQuery(params(
                "BBOX", "1,2,3,4", "TOKEN", "caller-token", "token", "caller-token", "SLD", "http://example.com/x.sld",
                "EXCEPTIONS", "INIMAGE", "x", "1"), "tok", null);

        Map<String, List<String>> upstream = decoded(query);
        assertEquals(List.of("BBOX", "TOKEN"), new ArrayList<>(upstream.keySet()));
        assertEquals(List.of("tok"), upstream.get("TOKEN"));
    }

    @Test
    void theConfiguredLayersReplaceTheCallers() {
        Map<String, List<String>> upstream = decoded(WmsProxyServlet.buildUpstreamQuery(
                params("layers", "internal", "LAYERS", "other", "BBOX", "1,2,3,4"), null, "layerA"));

        assertEquals(List.of("layerA"), upstream.get("LAYERS"));
        assertFalse(upstream.containsKey("TOKEN"), "no token configured, none sent");
    }

    @Test
    void withoutConfiguredLayersTheCallersAreForwarded() {
        Map<String, List<String>> upstream = decoded(WmsProxyServlet.buildUpstreamQuery(
                params("layers", "cells", "BBOX", "1,2,3,4"), "tok", ""));

        assertEquals(List.of("cells"), upstream.get("LAYERS"));
    }

    // ------------------------------------------------------------------ the token stays out of the logs

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(WmsProxyServlet.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        return appender;
    }

    private static void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(WmsProxyServlet.class);
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    private static void assertNoEventCarries(ListAppender<ILoggingEvent> appender, String secret) {
        for (ILoggingEvent event : appender.list) {
            assertFalse(event.getFormattedMessage().contains(secret), "logged: " + event.getFormattedMessage());
            IThrowableProxy thrown = event.getThrowableProxy();
            while (thrown != null) {
                assertFalse(String.valueOf(thrown.getMessage()).contains(secret), "logged: " + thrown.getMessage());
                thrown = thrown.getCause();
            }
        }
    }

    /** Proxies one tile request, anonymous and unprotected, to the given provider */
    private void proxyOneTile(String provider) throws Exception {
        WmsProxyServlet servlet = new WmsProxyServlet();
        protect(servlet, Boolean.FALSE);
        servlet.wmsProvider = provider;
        servlet.wmsPassword = "tok-secret";
        HttpServletRequest req = anonymousRequest();
        when(req.getParameterMap()).thenReturn(params("BBOX", "1,2,3,4", "WIDTH", "256", "HEIGHT", "256"));

        servlet.doGet(req, responseWithWriter());
    }

    @Test
    void aFailingProviderDoesNotWriteTheTokenToTheLog() throws Exception {
        for (int status : new int[] { 401, 404, 500 }) {
            try (HttpStub provider = HttpStub.start()) {
                provider.respondStatus(status);
                ListAppender<ILoggingEvent> appender = attachLogAppender();
                try {
                    proxyOneTile(provider.baseUrl() + "/wms");

                    assertEquals("tok-secret", decoded(provider.lastRequest().query()).get("TOKEN").get(0),
                            "the provider still gets the token");
                    assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN),
                            "the failure is logged (" + status + ")");
                    assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("TOKEN=***")),
                            "the logged URL shows a token is sent (" + status + ")");
                    assertNoEventCarries(appender, "tok-secret");
                } finally {
                    detachLogAppender(appender);
                }
            }
        }
    }

    @Test
    void anUnusableProviderDoesNotWriteTheTokenToTheLog() throws Exception {
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            proxyOneTile("unknown-scheme://wms.example.com/wms");

            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN));
            assertNoEventCarries(appender, "tok-secret");
        } finally {
            detachLogAppender(appender);
        }
    }

    // ------------------------------------------------------------------ the tile cache

    @TempDir
    Path cacheFolder;

    /** The tile 8/136/79 of the Web Mercator grid, as a web map asks for it */
    private static final String TILE_BOX = "1252344.271424327,7514065.628545966,1408887.305352368,7670608.662474007";

    private static Map<String, String[]> tileParams() {
        return params("SERVICE", "WMS", "VERSION", "1.3.0", "REQUEST", "GetMap", "FORMAT", "image/png",
                "STYLES", "", "TRANSPARENT", "TRUE", "LAYERS", "cells", "WIDTH", "256", "HEIGHT", "256",
                "CRS", "EPSG:3857", "BBOX", TILE_BOX);
    }

    private static HttpServletRequest userRequest(Map<String, String[]> params) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getUserPrincipal()).thenReturn(() -> "editor");
        when(req.getParameterMap()).thenReturn(params);
        when(req.getDateHeader("If-Modified-Since")).thenReturn(-1L);
        return req;
    }

    private HttpServletRequest anonymousRequest(Map<String, String[]> params) {
        HttpServletRequest req = anonymousRequest();
        when(req.getParameterMap()).thenReturn(params);
        when(req.getDateHeader("If-Modified-Since")).thenReturn(-1L);
        return req;
    }

    /** A response double that keeps the body written to it */
    private static final class Answer {
        final HttpServletResponse response = mock(HttpServletResponse.class);
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        Answer() throws IOException {
            when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
            when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
                @Override
                public void write(int b) {
                    body.write(b);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }
            });
        }
    }

    private static byte[] png(int side) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }

    /** A protected servlet whose tile cache keeps a tile for the given number of days; 0 switches the cache off */
    private WmsProxyServlet servletWithCache(HttpStub provider, int days) {
        WmsProxyServlet servlet = new WmsProxyServlet();
        protect(servlet, Boolean.TRUE);
        servlet.wmsProvider = provider.baseUrl() + "/wms";
        servlet.wmsPassword = "tok-secret";
        servlet.cache = new WmsTileCache(cacheFolder, Clock.systemUTC(), () -> Long.MAX_VALUE, days, 200);
        return servlet;
    }

    private List<Path> cachedFiles() throws IOException {
        try (Stream<Path> walk = Files.walk(cacheFolder)) {
            return walk.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    void aUsersTileIsFetchedOnceAndThenAnsweredFromTheCache() throws Exception {
        try (HttpStub provider = HttpStub.start()) {
            provider.respondBytes("image/png", png(256));
            WmsProxyServlet servlet = servletWithCache(provider, 7);

            Answer first = new Answer();
            servlet.doGet(userRequest(tileParams()), first.response);
            Answer second = new Answer();
            servlet.doGet(userRequest(tileParams()), second.response);

            verify(first.response).setHeader(WmsProxyServlet.CACHE_HEADER, "MISS");
            verify(second.response).setHeader(WmsProxyServlet.CACHE_HEADER, "HIT");
            for (Answer answer : List.of(first, second)) {
                verify(answer.response).setContentType("image/png");
                verify(answer.response).setContentLength(answer.body.size());
                verify(answer.response).setHeader("Cache-Control", "private, max-age=86400");
                verify(answer.response).setDateHeader(eq("Last-Modified"), anyLong());
                verify(answer.response, never()).addHeader(eq("Cache-Control"), anyString());
            }
            BufferedImage tile = ImageIO.read(new ByteArrayInputStream(first.body.toByteArray()));
            assertEquals(256, tile.getWidth(), "the answer is the provider's tile");
            assertArrayEquals(first.body.toByteArray(), second.body.toByteArray(), "a hit is the bytes of the miss");
            assertEquals(1, provider.requestCount(), "the provider was asked once");
            assertEquals(1, cachedFiles().size());
        }
    }

    @Test
    void theProvidersTokenIsInNoCachedPath() throws Exception {
        try (HttpStub provider = HttpStub.start()) {
            provider.respondBytes("image/png", png(256));
            WmsProxyServlet servlet = servletWithCache(provider, 7);

            servlet.doGet(userRequest(tileParams()), new Answer().response);

            assertEquals("tok-secret", decoded(provider.lastRequest().query()).get("TOKEN").get(0),
                    "the provider still gets the token");
            Path cached = cachedFiles().get(0);
            assertFalse(cached.toString().contains("tok-secret"), "the token names no file: " + cached);
            String place = cacheFolder.relativize(cached).toString().replace('\\', '/');
            assertTrue(place.matches("127\\.0\\.0\\.1_" + provider.port() + "/[0-9a-f]{32}/8/136/79\\.png"),
                    "host and port, variant, zoom, column, row: " + place);
        }
    }

    @Test
    void anAnonymousCallerIsForbiddenAndGetsNoCachedTile() throws Exception {
        try (HttpStub provider = HttpStub.start()) {
            provider.respondBytes("image/png", png(256));
            WmsProxyServlet servlet = servletWithCache(provider, 7);
            servlet.doGet(userRequest(tileParams()), new Answer().response);

            Answer anonymous = new Answer();
            servlet.doGet(anonymousRequest(tileParams()), anonymous.response);

            verify(anonymous.response).setStatus(HttpServletResponse.SC_FORBIDDEN);
            verify(anonymous.response, never()).getOutputStream();
            verify(anonymous.response, never()).setHeader(eq(WmsProxyServlet.CACHE_HEADER), anyString());
            assertEquals(0, anonymous.body.size(), "no tile for an anonymous caller, cached or not");
            assertEquals(1, provider.requestCount(), "and nothing was fetched for one");
            assertTrue(servlet.cache.line().contains(" hits=0 misses=1 "), "the cache was not read: "
                    + "only the user's request reached it");
        }
    }

    @Test
    void anAnonymousCallerOfAnOpenProxyIsProxiedWithoutTheCache() throws Exception {
        try (HttpStub provider = HttpStub.start()) {
            provider.respondBytes("image/png", png(256));
            WmsProxyServlet servlet = servletWithCache(provider, 7);
            protect(servlet, Boolean.FALSE);
            servlet.doGet(userRequest(tileParams()), new Answer().response);
            long cachedAt = Files.getLastModifiedTime(cachedFiles().get(0)).toMillis();

            Answer anonymous = new Answer();
            servlet.doGet(anonymousRequest(tileParams()), anonymous.response);

            // The tile is in the cache, and the anonymous caller's answer still comes from the provider
            assertEquals(2, provider.requestCount(), "the provider is asked for the anonymous caller");
            verify(anonymous.response, never()).setHeader(eq(WmsProxyServlet.CACHE_HEADER), anyString());
            verify(anonymous.response).addHeader("Cache-Control", "max-age=86400");
            assertEquals(256, ImageIO.read(new ByteArrayInputStream(anonymous.body.toByteArray())).getWidth());
            assertEquals(cachedAt, Files.getLastModifiedTime(cachedFiles().get(0)).toMillis(),
                    "the cached tile was not written again");
            assertTrue(servlet.cache.line().contains(" files=1 cap=209715200 hits=0 misses=1 stale=0 failed=0"
                    + " bypassed=1 "), "the cache was neither read nor written for the anonymous caller");
        }
    }

    @Test
    void withTheCacheOffAUsersTileIsProxiedAsBefore() throws Exception {
        try (HttpStub provider = HttpStub.start()) {
            provider.respondBytes("image/png", png(256));
            WmsProxyServlet servlet = servletWithCache(provider, 0);

            Answer first = new Answer();
            servlet.doGet(userRequest(tileParams()), first.response);
            Answer second = new Answer();
            servlet.doGet(userRequest(tileParams()), second.response);

            assertEquals(2, provider.requestCount(), "every request goes to the provider");
            for (Answer answer : List.of(first, second)) {
                verify(answer.response).addHeader("Cache-Control", "max-age=86400");
                verify(answer.response).setContentType("image/png");
                verify(answer.response, never()).setHeader(anyString(), anyString());
                verify(answer.response, never()).setContentLength(anyInt());
                assertEquals(256, ImageIO.read(new ByteArrayInputStream(answer.body.toByteArray())).getWidth());
            }
            assertEquals(List.of(), cachedFiles(), "nothing is cached");
        }
    }

    @Test
    void aRequestThatIsNoTileIsProxiedWithoutTheCache() throws Exception {
        try (HttpStub provider = HttpStub.start()) {
            provider.respondBytes("image/png", png(512));
            WmsProxyServlet servlet = servletWithCache(provider, 7);
            Map<String, String[]> params = tileParams();
            params.put("WIDTH", new String[] { "512" });
            params.put("HEIGHT", new String[] { "512" });

            Answer answer = new Answer();
            servlet.doGet(userRequest(params), answer.response);
            servlet.doGet(userRequest(params), new Answer().response);

            assertEquals(2, provider.requestCount());
            verify(answer.response).addHeader("Cache-Control", "max-age=86400");
            verify(answer.response, never()).setHeader(eq(WmsProxyServlet.CACHE_HEADER), anyString());
            assertEquals(512, ImageIO.read(new ByteArrayInputStream(answer.body.toByteArray())).getWidth());
            assertEquals(List.of(), cachedFiles());
            assertTrue(servlet.cache.line().contains(" bypassed=2 "));
        }
    }

    @Test
    void aBrowserThatHoldsTheTileIsAnswered304() throws Exception {
        try (HttpStub provider = HttpStub.start()) {
            provider.respondBytes("image/png", png(256));
            WmsProxyServlet servlet = servletWithCache(provider, 7);
            Answer first = new Answer();
            servlet.doGet(userRequest(tileParams()), first.response);
            ArgumentCaptor<Long> fetchedAt = ArgumentCaptor.forClass(Long.class);
            verify(first.response).setDateHeader(eq("Last-Modified"), fetchedAt.capture());

            // A browser sends the date back in whole seconds
            HttpServletRequest revalidation = userRequest(tileParams());
            when(revalidation.getDateHeader("If-Modified-Since")).thenReturn(fetchedAt.getValue() / 1000 * 1000);
            Answer notModified = new Answer();
            servlet.doGet(revalidation, notModified.response);

            HttpServletRequest olderCopy = userRequest(tileParams());
            when(olderCopy.getDateHeader("If-Modified-Since")).thenReturn(fetchedAt.getValue() - 5_000);
            Answer modified = new Answer();
            servlet.doGet(olderCopy, modified.response);

            verify(notModified.response).setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            verify(notModified.response).setHeader("Cache-Control", "private, max-age=86400");
            verify(notModified.response, never()).getOutputStream();
            verify(modified.response, never()).setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            assertArrayEquals(first.body.toByteArray(), modified.body.toByteArray(),
                    "a browser with an older copy gets the tile");
            assertEquals(1, provider.requestCount());
        }
    }

    @Test
    void whenTheProviderFailsAndThereIsNoCopyTheBlankImageIsKeptFiveMinutes() throws Exception {
        try (HttpStub provider = HttpStub.start()) {
            provider.respondStatus(500);
            WmsProxyServlet servlet = servletWithCache(provider, 7);
            ListAppender<ILoggingEvent> appender = attachLogAppender();
            try {
                Answer answer = new Answer();
                servlet.doGet(userRequest(tileParams()), answer.response);

                verify(answer.response).sendRedirect(WmsProxyServlet.BLANK_IMAGE);
                verify(answer.response).setHeader("Cache-Control", "private, max-age=300");
                verify(answer.response, never()).addHeader(eq("Cache-Control"), anyString());
                verify(answer.response, never()).setHeader(eq(WmsProxyServlet.CACHE_HEADER), anyString());
                assertEquals(List.of(), cachedFiles(), "a failed fetch stores nothing");
                assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("TOKEN=***")), "the failure is logged, the token masked");
                assertNoEventCarries(appender, "tok-secret");
            } finally {
                detachLogAppender(appender);
            }
        }
    }

    @Test
    void aRequestTheProviderRefusesDoesNotPauseTheProviderForEverybody() throws Exception {
        try (HttpStub provider = HttpStub.start()) {
            provider.respondStatus(400);
            WmsProxyServlet servlet = servletWithCache(provider, 7);
            Map<String, String[]> unknownStyle = tileParams();
            unknownStyle.put("STYLES", new String[] { "nosuch" });

            for (int request = 0; request < 4; request++) {
                Answer refused = new Answer();
                servlet.doGet(userRequest(unknownStyle), refused.response);
                verify(refused.response).sendRedirect(WmsProxyServlet.BLANK_IMAGE);
            }
            provider.respondBytes("image/png", png(256));
            Answer tile = new Answer();
            servlet.doGet(userRequest(tileParams()), tile.response);

            verify(tile.response).setHeader(WmsProxyServlet.CACHE_HEADER, "MISS");
            assertEquals(5, provider.requestCount(), "the provider is asked every time: it was never paused");
            assertEquals(1, cachedFiles().size(), "and only the tile it answered is cached");
        }
    }

    @Test
    void aProviderThatFailsThreeTimesIsNotAskedForAWhile() throws Exception {
        try (HttpStub provider = HttpStub.start()) {
            provider.respondStatus(503);
            WmsProxyServlet servlet = servletWithCache(provider, 7);
            for (int request = 0; request < 3; request++) {
                servlet.doGet(userRequest(tileParams()), new Answer().response);
            }
            provider.respondBytes("image/png", png(256));

            Answer paused = new Answer();
            servlet.doGet(userRequest(tileParams()), paused.response);

            verify(paused.response).sendRedirect(WmsProxyServlet.BLANK_IMAGE);
            assertEquals(3, provider.requestCount(), "the fourth request did not reach the provider");
        }
    }

    @Test
    void onlyAStatusAboutTheRequestItselfIsARefusal() {
        for (int status : new int[] { 400, 404, 405, 410, 414, 422 }) {
            assertTrue(WmsProxyServlet.refusesTheRequest(status), "HTTP " + status);
        }
        for (int status : new int[] { 200, 302, 401, 403, 408, 429, 500, 502, 503 }) {
            assertFalse(WmsProxyServlet.refusesTheRequest(status), "HTTP " + status
                    + " says something about the provider or the access to it, or is no error");
        }
    }

    @Test
    void anAnswerOfAnotherSizeIsNotCached() throws Exception {
        try (HttpStub provider = HttpStub.start()) {
            provider.respondBytes("image/png", png(16));
            WmsProxyServlet servlet = servletWithCache(provider, 7);

            Answer answer = new Answer();
            servlet.doGet(userRequest(tileParams()), answer.response);

            verify(answer.response).sendRedirect(WmsProxyServlet.BLANK_IMAGE);
            assertEquals(0, answer.body.size());
            assertEquals(List.of(), cachedFiles(), "an image that is no tile is not stored under a tile's name");
        }
    }
}
