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

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;

/**
 * Fetches the resources a document the backend renders refers to - the images and style sheets of a
 * mail or a PDF - from the application's own origin only.
 * <p>
 * Those documents carry markup the backend does not control: message details, comments and report
 * parameters. Resolving every {@code <img>} and {@code <link>} in them would let that markup make the
 * backend read local files ({@code file:}, {@code jar:}) or request any address its network position
 * reaches. The documents only ever need the backend's own files - the template style sheets, the logos
 * and flags, the repository and the map images - which it serves under its {@code baseUri} setting, so
 * everything else is refused.
 * <p>
 * A URL is the application's own when it is an http or https URL without user info whose scheme, host
 * and port equal those of the base URI. The check is made on the very {@link URL} the connection is
 * opened from, and redirects are not followed by the connection itself: each hop is checked before it
 * is requested, and at most {@link #MAX_REDIRECTS} are followed.
 * <p>
 * A redirect may in addition switch between http and https on the base URI's host, as long as both the
 * base URI and the target use their scheme's default port (see {@link #isRedirectTarget(String, String)}).
 * The application builds the {@code Location} of its own redirects from the request it received, and
 * behind a proxy that ends TLS that request is plain http; a proxy may also upgrade an http base URI to
 * https. The host stays the configured one, so no other address becomes reachable this way.
 */
public final class SameOriginFetch {

    /** The most redirects followed for one resource */
    public static final int MAX_REDIRECTS = 5;

    static final int CONNECT_TIMEOUT_MS = 10_000;
    static final int READ_TIMEOUT_MS = 15_000;

    private SameOriginFetch() {
    }

    /**
     * Returns whether the URL is one of the application's own: an http or https URL without user info,
     * on the scheme, host and port of the base URI.
     *
     * @param url the URL to check
     * @param baseUri the application's base URI
     * @return whether the URL is on the application's own origin
     */
    public static boolean isSameOrigin(String url, String baseUri) {
        try {
            return isSameOrigin(parse(url), parse(baseUri));
        } catch (MalformedURLException e) {
            return false;
        }
    }

    /**
     * Returns whether a redirect may lead to the URL: when it is on the application's own origin, or on
     * the base URI's host with the other of http and https, both the base URI and the URL on their
     * scheme's default port.
     *
     * @param url the redirect target to check
     * @param baseUri the application's base URI
     * @return whether a redirect to the URL is followed
     */
    static boolean isRedirectTarget(String url, String baseUri) {
        try {
            return isRedirectTarget(parse(url), parse(baseUri));
        } catch (MalformedURLException e) {
            return false;
        }
    }

    /**
     * Opens the URL, which must be on the origin of the base URI, and follows the redirects that stay
     * on that origin.
     *
     * @param url the URL to open
     * @param baseUri the application's base URI
     * @return the connection to the final resource, already answered
     * @throws IOException if the URL, or a redirect, leaves the origin, or the request fails
     */
    public static HttpURLConnection open(String url, String baseUri) throws IOException {
        return followRedirects(connect(url, baseUri), baseUri);
    }

    /**
     * Returns a connection to the URL, which must be on the origin of the base URI. The connection is
     * not yet made, does not follow redirects by itself and has connect and read timeouts.
     *
     * @param url the URL to connect to
     * @param baseUri the application's base URI
     * @return the unconnected connection
     * @throws IOException if the URL is not on the origin of the base URI
     */
    public static HttpURLConnection connect(String url, String baseUri) throws IOException {
        return connect(url, baseUri, false);
    }

    private static HttpURLConnection connect(String url, String baseUri, boolean redirect) throws IOException {
        URL target = parse(url);
        URL base;
        try {
            base = parse(baseUri);
        } catch (MalformedURLException e) {
            throw new IOException("Not fetched, the base URI is not a URL: " + describe(target));
        }
        if (redirect ? !isRedirectTarget(target, base) : !isSameOrigin(target, base)) {
            throw new IOException("Not fetched, not on the application's own origin: " + describe(target));
        }
        URLConnection connection = target.openConnection();
        if (!(connection instanceof HttpURLConnection http)) {
            throw new IOException("Not fetched, not an HTTP connection: " + describe(target));
        }
        http.setInstanceFollowRedirects(false);
        http.setConnectTimeout(CONNECT_TIMEOUT_MS);
        http.setReadTimeout(READ_TIMEOUT_MS);
        http.setRequestProperty("Accept", "*/*");
        return http;
    }

    /**
     * Follows the redirects the connection answers with, as long as each one stays on the origin of the
     * base URI - or on its host with the other of http and https (see
     * {@link #isRedirectTarget(String, String)}) - and at most {@link #MAX_REDIRECTS} of them.
     *
     * @param connection a connection made by {@link #connect(String, String)}
     * @param baseUri the application's base URI
     * @return the connection to the final resource, already answered
     * @throws IOException if a redirect leaves the origin, there are too many, or a request fails
     */
    public static HttpURLConnection followRedirects(HttpURLConnection connection, String baseUri) throws IOException {
        HttpURLConnection current = connection;
        for (int redirects = 0; ; redirects++) {
            int status = current.getResponseCode();
            if (!isRedirect(status)) {
                return current;
            }
            URL from = current.getURL();
            String location = current.getHeaderField("Location");
            current.disconnect();
            if (StringUtils.isBlank(location)) {
                throw new IOException("Redirect " + status + " without a location from " + describe(from));
            }
            if (redirects >= MAX_REDIRECTS) {
                throw new IOException("More than " + MAX_REDIRECTS + " redirects from " + describe(from));
            }
            current = connect(resolve(from, location), baseUri, true);
        }
    }

    @SuppressWarnings("deprecation")    // resolved the way the connection would have resolved it
    private static String resolve(URL from, String location) throws MalformedURLException {
        return new URL(from, location.trim()).toExternalForm();
    }

    /** Returns whether the status is a redirect that carries a location */
    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307
                || status == 308;
    }

    private static boolean isSameOrigin(URL url, URL base) {
        return isOnBaseHost(url, base)
                && url.getProtocol().equalsIgnoreCase(base.getProtocol())
                && effectivePort(url) == effectivePort(base);
    }

    private static boolean isRedirectTarget(URL url, URL base) {
        return isSameOrigin(url, base)
                || (isOnBaseHost(url, base)
                        && isWebScheme(base)
                        && effectivePort(url) == url.getDefaultPort()
                        && effectivePort(base) == base.getDefaultPort());
    }

    /** An http or https URL without user info on the host of the base URI */
    private static boolean isOnBaseHost(URL url, URL base) {
        return isWebScheme(url)
                && url.getUserInfo() == null
                && StringUtils.isNotBlank(url.getHost())
                && url.getHost().equalsIgnoreCase(base.getHost());
    }

    private static boolean isWebScheme(URL url) {
        String scheme = url.getProtocol().toLowerCase(Locale.ROOT);
        return "http".equals(scheme) || "https".equals(scheme);
    }

    private static int effectivePort(URL url) {
        return url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
    }

    @SuppressWarnings("deprecation")    // the lenient parser the connection itself is opened from
    private static URL parse(String url) throws MalformedURLException {
        if (StringUtils.isBlank(url)) {
            throw new MalformedURLException("No URL");
        }
        return new URL(url.trim());
    }

    /** The URL without its user info, query and fragment, for messages that end up in logs */
    private static String describe(URL url) {
        return url.getProtocol() + ":" + (url.getHost() != null && !url.getHost().isEmpty() ? "//" + url.getHost() : "")
                + (url.getPort() != -1 ? ":" + url.getPort() : "") + url.getPath();
    }
}
