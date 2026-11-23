/*
 * Copyright 2016 Danish Maritime Authority.
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

import static org.niord.core.settings.Setting.Type.Boolean;
import static org.niord.core.settings.Setting.Type.Password;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.util.WebUtils;
import org.slf4j.Logger;

import jakarta.inject.Inject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Proxy WMS data
 *
 * The provider's image is decoded and answered as a PNG.
 *
 * The WMS provider and its access are the settings declared below, "wmsProvider", "wmsPassword" (the provider's
 * token), "wmsLayers" and "wmsProtected", set on the Settings page or, for a new deployment, in the server's
 * "${niord.home}/niord.json". The former "wmsLogin" and "wmsServiceName" are no longer read, and no catalog declares
 * them.
 *
 * When the tile cache is switched on ({@link WmsTileCache}, the "wmsCacheDays" setting), a request that has a user
 * and asks for a tile of a web map's grid ({@link WmsTileKey}) is answered from the cache, which fetches from the
 * provider what it lacks. Every other request - an anonymous one when "wmsProtected" is off, one for another
 * image size or projection - is proxied as it is without the cache.
 *
 * On either path a browser may keep a tile for a day, and the blank image that answers "no tile" for five minutes
 * ({@link #RETRY_TIMEOUT}): a blank means no tile just now, not that the place has none.
 *
 * A save of one of these settings takes effect at the next request: the save sets the fields again
 * ({@code SettingsRefresher}). A request reads the fields, once each, and no setting through the settings service,
 * so a tile, the most frequent request the backend has, costs the database nothing.
 */
@WebServlet(value = "/wms/*")
public class WmsProxyServlet extends HttpServlet {

    final static int        CACHE_TIMEOUT   =  24 * 60 * 60; // 24 hours
    /** How long a browser keeps an answer that is no fresh tile: the blank image, or the tile cache's older copy */
    static final int        RETRY_TIMEOUT   =  5 * 60; // 5 minutes
    /** The picture answered when there is no tile, as a resource on the class path */
    static final String     BLANK_IMAGE     = "META-INF/resources/img/blank.png";
    /** The response header that says where a tile of the tile cache came from: HIT, MISS or STALE */
    static final String     CACHE_HEADER    = "X-Niord-Cache";

    /** The GetMap parameters forwarded to the WMS provider - those a tiled map sends - besides LAYERS */
    static final Set<String> FORWARDED_PARAMS = Set.of(
            "SERVICE", "VERSION", "REQUEST", "FORMAT", "TRANSPARENT", "STYLES",
            "WIDTH", "HEIGHT", "BBOX", "CRS", "SRS");
    static final String     LAYERS          = "LAYERS";
    static final String     TOKEN           = "TOKEN";
    /** What the logs show in place of the WMS token */
    static final String     TOKEN_MASK      = "***";

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WmsProxyServlet.class);

    /** The bytes of {@link #BLANK_IMAGE}, read once; declared after the logger, which reading it may use */
    static final byte[] BLANK_PNG = readBlankImage();

        @Setting(value="wmsProvider", description="The WMS provider.")
    volatile String wmsProvider;

//    @Inject
//    @Setting(value="wmsServiceName", description="The WMS service name")
//    String wmsServiceName;

    // This field is are no longer used
//    @Inject
//    @Setting(value="wmsLogin", description="The WMS user name")
//    String wmsLogin;

    /**
     * For historic reasons this field is called password. Sometime around October 2023, Dataforsyningen transitioned to a
     * token based system instead of username/password based system. To ease the transition we decided to reuse the password
     * field, instead of introducing a new field.
     */
        @Setting(value="wmsPassword", description="The WMS Token.", type = Password)
    volatile String wmsPassword;

        @Setting(value="wmsLayers", description="The WMS layers.")
    volatile String wmsLayers;

    /**
     * Whether the tiles require a signed-in user. Like the other settings of the servlet, a save on the Settings page
     * refreshes the field at once ({@code SettingsRefresher}); a request reads the field and nothing else. A value
     * that is missing protects ({@link #isProtected(Object)}).
     */
        @Setting(value="wmsProtected",
            description="Whether the WMS map tiles (/wms/*) require a signed-in user",
            defaultValue = "true",
            web = true,
            type = Boolean)
    volatile Boolean wmsProtected;

    @Inject
    WmsTileCache cache;

    /**
     * Main GET method
     * <p>
     * Without the tile cache, a browser may keep the provider's tile for a day ({@link #CACHE_TIMEOUT}), and the
     * blank image, answered when no provider is set or its fetch fails or gives no image, for five minutes
     * ({@link #RETRY_TIMEOUT}), so the next look at the place asks again. Both carry {@code Cache-Control: max-age},
     * {@code Last-Modified} and {@code Expires}, one value each.
     *
     * @param request servlet request
     * @param response servlet response
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {

        // Each setting is read once, here, and the request goes on with these values: a save on the Settings page
        // refreshes the fields at any moment, and the tile's name, the query sent to the provider and the line logged
        // must be of one and the same provider, token and layers
        String provider = wmsProvider;
        String token = wmsPassword;
        String layers = wmsLayers;

        // Enforce "wmsProtected" flag. If set, only authenticated users can load the tiles.
        // On the client level, the tiles are loaded through the host's HttpClient in protected
        // mode (see fe/shared-map/layers/ol-wms-layer.ts), so its interceptors pass the
        // credentials along
        if (isProtected(wmsProtected) && request.getUserPrincipal() == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().println("<html><body><p>WMS service only available to authenticated users</p></body></html>");
            return;
        }

        // A tile of a web map's grid that a user asks for comes from the tile cache, when that is switched on
        if (cache != null && cache.enabled() && StringUtils.isNotBlank(provider)) {
            Map<String, String> forwarded = forwardedParams(request.getParameterMap(), layers);
            WmsTileKey key = request.getUserPrincipal() == null
                    ? null
                    : WmsTileKey.of(provider, forwarded).orElse(null);
            if (key != null) {
                serveFromCache(request, response, key, forwarded, provider, token, StringUtils.isNotBlank(layers));
                return;
            }
            cache.bypassed();
        }

        // The cache headers are set once the answer is known: a day for a tile, five minutes for the blank image.
        // Check that the WMS provider has been defined using system properties
        if (StringUtils.isBlank(provider)) {
            keepFor(response, RETRY_TIMEOUT);
            sendBlankImage(response);
            return;
        }

        Map<String, String[]> paramMap = request.getParameterMap();
        String url = provider + "?" + buildUpstreamQuery(paramMap, token, layers);
        // What the logs get: the same request with the token masked
        String loggedUrl = provider + "?" + buildUpstreamQuery(paramMap,
                StringUtils.isBlank(token) ? null : TOKEN_MASK, layers);

        log.trace("Loading image " + loggedUrl);
        try {
            BufferedImage image = fetchImage(url);
            if (image != null) {
                // Cache for a day
                WebUtils.cache(response, CACHE_TIMEOUT);
                response.setContentType("image/png");
                OutputStream out = response.getOutputStream();
                ImageIO.write(image, "png", out);
                image.flush();
                out.close();
                return;
            }
        } catch (Exception e) {
            // The exception text of a failed request names the URL, token included, so it is
            // logged redacted and without the stack trace
            log.warn("Failed loading WMS image for URL " + loggedUrl + ": " + redact(e, token));
        }

        // Fall back to return a blank image, kept for five minutes. Should writing a tile have failed after its
        // day-long headers were set, these replace them
        try {
            keepFor(response, RETRY_TIMEOUT);
            sendBlankImage(response);
        } catch (Exception e) {
            log.warn("Failed returning blank image for URL " + loggedUrl + ": " + redact(e, token));
        }
    }

    /**
     * Whether a value of the {@code wmsProtected} setting protects the tiles. Only {@code false}, as a boolean or
     * as a text in any case, switches the protection off; anything else protects, null and a stored text
     * {@code "true"} included. So a value that is missing or cannot be read fails safe.
     *
     * @param value the setting's value
     * @return whether the tiles require a signed-in user
     */
    static boolean isProtected(Object value) {
        if (java.lang.Boolean.FALSE.equals(value)) {
            return false;
        }
        return !(value instanceof String text && "false".equalsIgnoreCase(text.trim()));
    }

    /**
     * Answers a tile request from the tile cache, which fetches the tile from the provider when it has no fresh
     * copy. Only called for a request that has a user.
     * <p>
     * A browser may keep a fresh tile for a day, as it may keep a proxied one, and an older copy served because the
     * provider fails for five minutes; no cache between the browser and here may keep either
     * ({@code Cache-Control: private}). {@code Last-Modified} is when the tile was fetched from the provider, and a
     * browser that already has that copy is answered 304. When there is no tile at all the answer is the blank
     * image, kept for five minutes, so the next look at the place asks again.
     *
     * @param request the request
     * @param response the response
     * @param key the tile
     * @param forwarded the parameters to send to the provider
     * @param provider the provider, as the request read it
     * @param token the provider's token, as the request read it
     * @param fixedLayers whether the {@code wmsLayers} setting, as the request read it, fixes the layers
     */
    private void serveFromCache(HttpServletRequest request, HttpServletResponse response, WmsTileKey key,
                                Map<String, String> forwarded, String provider, String token, boolean fixedLayers)
            throws IOException {
        String url = provider + "?" + upstreamQuery(forwarded, token, fixedLayers);
        // What the logs get: the same request with the token masked
        String loggedUrl = provider + "?" + upstreamQuery(forwarded,
                StringUtils.isBlank(token) ? null : TOKEN_MASK, fixedLayers);

        WmsTileCache.Tile tile = cache.get(key, () -> {
            log.trace("Loading image " + loggedUrl);
            try {
                return fetchTile(url);
            } catch (Exception e) {
                // The exception text of a failed request names the URL, token included, so it is
                // logged redacted and without the stack trace
                log.warn("Failed loading WMS image for URL " + loggedUrl + ": " + redact(e, token));
                throw e;
            }
        });

        if (tile == null) {
            response.setHeader("Cache-Control", "private, max-age=" + RETRY_TIMEOUT);
            try {
                sendBlankImage(response);
            } catch (Exception e) {
                log.warn("Failed returning blank image for URL " + loggedUrl + ": " + redact(e, token));
            }
            return;
        }

        boolean fresh = tile.source() != WmsTileCache.Source.STALE;
        response.setHeader("Cache-Control", "private, max-age=" + (fresh ? CACHE_TIMEOUT : RETRY_TIMEOUT));
        response.setDateHeader("Last-Modified", tile.fetchedAt());
        response.setHeader(CACHE_HEADER, tile.source().name());
        if (hasCopyOf(request, tile)) {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return;
        }
        response.setContentType("image/png");
        response.setContentLength(tile.bytes().length);
        OutputStream out = response.getOutputStream();
        out.write(tile.bytes());
        out.close();
    }

    /**
     * Sets the cache headers of the path without the tile cache, in the form {@link WebUtils#cache} gives a tile
     * ({@code Cache-Control: max-age}, {@code Last-Modified} and {@code Expires}), but replacing each header rather
     * than adding to it, so a response that already had a tile's day-long headers ends with one value of each.
     *
     * @param response the response
     * @param seconds how long a browser may keep the answer
     */
    static void keepFor(HttpServletResponse response, int seconds) {
        long now = System.currentTimeMillis();
        response.setHeader("Cache-Control", "max-age=" + seconds);
        response.setDateHeader("Last-Modified", now);
        response.setDateHeader("Expires", now + seconds * 1000L);
    }

    /**
     * Answers "no tile": status 200 and the blank picture's bytes, on the request's own address. No redirect, so
     * behind a proxy no other origin is named, and a loader that fetches the tile itself gets the picture at once.
     * The headers already set on the response, its {@code Cache-Control} among them, are left as they are.
     *
     * @param response the response
     */
    static void sendBlankImage(HttpServletResponse response) throws IOException {
        // Whatever a failed answer had put in the buffer is dropped, as the redirect dropped it
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("image/png");
        response.setContentLength(BLANK_PNG.length);
        OutputStream out = response.getOutputStream();
        out.write(BLANK_PNG);
        out.close();
    }

    /**
     * Reads the blank picture from the class path. Should it be missing, a transparent PNG of one pixel, which is
     * what the file holds, is made in its place, so the servlet still starts.
     */
    private static byte[] readBlankImage() {
        try (InputStream in = WmsProxyServlet.class.getClassLoader().getResourceAsStream(BLANK_IMAGE)) {
            if (in != null) {
                return in.readAllBytes();
            }
            log.warn("The blank image " + BLANK_IMAGE + " is not on the class path; a transparent pixel is used");
        } catch (IOException e) {
            log.warn("Could not read the blank image " + BLANK_IMAGE + "; a transparent pixel is used: " + e);
        }
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", png);
            return png.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not make a blank PNG", e);
        }
    }

    /** Whether the browser says, with {@code If-Modified-Since}, that it holds this copy of the tile or a later one */
    private static boolean hasCopyOf(HttpServletRequest request, WmsTileCache.Tile tile) {
        long since;
        try {
            since = request.getDateHeader("If-Modified-Since");
        } catch (IllegalArgumentException e) {
            return false;
        }
        // An HTTP date has whole seconds
        return since > 0 && tile.fetchedAt() / 1000 <= since / 1000;
    }

    /**
     * Fetches an image from the WMS provider.
     *
     * @param url the request, token included
     * @return the image, or null when the provider's answer is no image
     */
    private static BufferedImage fetchImage(String url) throws IOException {
        URLConnection con = connectionTo(url);
        try (InputStream in = con.getInputStream()) {
            return ImageIO.read(in);
        }
    }

    private static URLConnection connectionTo(String url) throws IOException {
        URL urlUrl = new URL(url);
        URLConnection con = urlUrl.openConnection();
        con.setConnectTimeout(20 * 1000);
        con.setReadTimeout(20 * 1000);
        return con;
    }

    /**
     * Whether a status of the provider refuses this one request, as a 400 for a style it does not have does, and
     * says nothing about the provider itself. The statuses that do say the provider, or this deployment's access
     * to it, is failing are left out: 401 and 403 (the token), 408 and 429 (it is overloaded or throttles us).
     */
    static boolean refusesTheRequest(int status) {
        return status >= 400 && status < 500 && status != 401 && status != 403 && status != 408 && status != 429;
    }

    /**
     * Fetches a tile from the WMS provider for the tile cache: the image, as the PNG this servlet answers with.
     *
     * @param url the request, token included
     * @return the PNG
     * @throws WmsTileCache.NotATileException when the provider refuses the request or its answer is no image of
     *         a tile's size: the caller gets no tile, and the provider is not taken to be failing, so a caller
     *         who asks for what the provider does not have cannot pause it for everybody
     */
    private static byte[] fetchTile(String url) throws IOException {
        URLConnection con = connectionTo(url);
        if (con instanceof HttpURLConnection http && refusesTheRequest(http.getResponseCode())) {
            int status = http.getResponseCode();
            InputStream refusal = http.getErrorStream();
            if (refusal != null) {
                refusal.close();
            }
            throw new WmsTileCache.NotATileException("the provider refused the request: HTTP " + status);
        }
        BufferedImage image;
        try (InputStream in = con.getInputStream()) {
            image = ImageIO.read(in);
        }
        if (image == null) {
            throw new WmsTileCache.NotATileException("the provider's answer is not an image");
        }
        try {
            if (image.getWidth() != WmsTileKey.TILE_SIZE || image.getHeight() != WmsTileKey.TILE_SIZE) {
                throw new WmsTileCache.NotATileException("the provider's image is " + image.getWidth() + " x "
                        + image.getHeight() + " px, not " + WmsTileKey.TILE_SIZE + " x " + WmsTileKey.TILE_SIZE);
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            // In memory: the default would write every tile to a temporary file first
            try (ImageOutputStream out = new MemoryCacheImageOutputStream(png)) {
                if (!ImageIO.write(image, "png", out)) {
                    throw new IOException("No PNG writer is available");
                }
            }
            return png.toByteArray();
        } finally {
            image.flush();
        }
    }

    /**
     * Returns the query string sent to the WMS provider.
     * <p>
     * Only the GetMap parameters a tiled map sends are forwarded, under their upper-case names; any other
     * parameter, a {@code TOKEN} among them, is dropped. {@code LAYERS} is forwarded only when no
     * {@code wmsLayers} setting fixes the layers. The token and the configured layers are appended last.
     * Every value is URL-encoded (see {@link #encodeValue(String)}), so an {@code &}, {@code =} or
     * {@code #} in a value stays inside that value: it can neither add nor replace a parameter, nor cut
     * the appended ones off.
     *
     * @param params the request parameters
     * @param token the WMS token, or null for none
     * @param layers the configured layers, or null to forward the caller's
     * @return the query string, without the leading "?"
     */
    static String buildUpstreamQuery(Map<String, String[]> params, String token, String layers) {
        return upstreamQuery(forwardedParams(params, layers), token, StringUtils.isNotBlank(layers));
    }

    /**
     * Returns the parameters sent to the WMS provider, by upper-case name, without the token: the GetMap
     * parameters of the request that a tiled map sends, the first value of each, and the configured layers, last,
     * when the {@code wmsLayers} setting fixes them. The tile cache names a tile by these, so a tile is stored
     * under the name of exactly what the provider was asked for.
     *
     * @param params the request parameters
     * @param layers the configured layers, or null to forward the caller's
     * @return the parameters, in the order they are sent
     */
    static Map<String, String> forwardedParams(Map<String, String[]> params, String layers) {
        boolean fixedLayers = StringUtils.isNotBlank(layers);
        Map<String, String> forwarded = new LinkedHashMap<>();
        params.forEach((key, values) -> {
            String name = key == null ? "" : key.toUpperCase(Locale.ROOT);
            boolean allowed = FORWARDED_PARAMS.contains(name) || (!fixedLayers && LAYERS.equals(name));
            if (allowed && values != null && values.length > 0 && values[0] != null) {
                forwarded.putIfAbsent(name, values[0]);
            }
        });
        if (fixedLayers) {
            forwarded.put(LAYERS, layers);
        }
        return forwarded;
    }

    /**
     * Returns the query string of the forwarded parameters and the token. The token goes after the request's
     * parameters and before the configured layers.
     *
     * @param forwarded the forwarded parameters, see {@link #forwardedParams}
     * @param token the WMS token, or null for none
     * @param fixedLayers whether the layers are the configured ones
     * @return the query string, without the leading "?"
     */
    static String upstreamQuery(Map<String, String> forwarded, String token, boolean fixedLayers) {
        Map<String, String> query = new LinkedHashMap<>(forwarded);
        String layers = fixedLayers ? query.remove(LAYERS) : null;
        if (StringUtils.isNotBlank(token)) {
            query.put(TOKEN, token);
        }
        if (layers != null) {
            query.put(LAYERS, layers);
        }
        return query.entrySet().stream()
                .map(p -> p.getKey() + "=" + encodeValue(p.getValue()))
                .collect(Collectors.joining("&"));
    }

    /**
     * URL-encodes a forwarded value, but leaves {@code ,}, {@code :} and {@code /} as they are. Those are
     * legal in a query, carry no meaning to its parsing, and are what the WMS values themselves are made
     * of ({@code BBOX=1,2,3,4}, {@code CRS=EPSG:3857}, {@code FORMAT=image/png}, a comma-separated
     * {@code LAYERS}), so the provider gets them as a map has always sent them. Everything that
     * structures a query or is decoded into something else - {@code &}, {@code =}, {@code #}, {@code +},
     * {@code %} - is encoded.
     *
     * @param value the value
     * @return the encoded value
     */
    static String encodeValue(String value) {
        // The encoder writes '%' only at the start of an escape, so these replace its own escapes only
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("%2C", ",")
                .replace("%3A", ":")
                .replace("%2F", "/");
    }

    /** The exception's class and message, with the request's token masked wherever it appears */
    private static String redact(Exception e, String token) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (StringUtils.isNotBlank(token)) {
            text = text.replace(token, TOKEN_MASK)
                    .replace(encodeValue(token), TOKEN_MASK)
                    .replace(URLEncoder.encode(token, StandardCharsets.UTF_8), TOKEN_MASK);
        }
        return text;
    }

}
