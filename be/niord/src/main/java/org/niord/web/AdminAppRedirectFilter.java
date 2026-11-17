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

import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.springframework.http.HttpHeaders;

import jakarta.inject.Inject;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Redirects a request for the backend's root ({@code /}) to the administration web app named by the
 * {@code adminAppUrl} setting.
 * <p>
 * The backend serves the REST API under {@code /rest}, the WMS proxy under {@code /wms} and the server-side
 * assets under {@code /img} and {@code /css/templates}. The administration web app is served either at this
 * root by the backend itself ({@link org.niord.core.conf.WebAppConfig}), where nothing needs redirecting, or
 * from another origin, which {@code adminAppUrl} names. A hash-routed message link,
 * {@code <baseUri>/#/message/<id>} (see {@link org.niord.core.message.MessageLinks}), reaches this root
 * whenever {@code baseUri} is the backend's own URL. The browser keeps the {@code #/message/<id>} fragment
 * across the redirect, and the administration web app forwards it to the message.
 * <p>
 * Only a GET or HEAD for exactly {@code /} is redirected: {@code 302 Found} to the configured URL plus
 * {@code /} and the original query string. Every other request passes on untouched, and so does the root
 * when {@code adminAppUrl} is empty or names the origin the request came in on, where the redirect would
 * come straight back.
 */
public class AdminAppRedirectFilter implements Filter {

    @Inject
    NiordApp app;

    /** {@inheritDoc} */
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    /** {@inheritDoc} */
    @Override
    public void destroy() {
    }

    /**
     * Main filter method
     * @param req the request
     * @param res the response
     * @param chain the filter chain
     */
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {

        String location = redirectLocation((HttpServletRequest) req);
        if (location != null) {
            HttpServletResponse response = (HttpServletResponse) res;
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader(HttpHeaders.LOCATION, location);
            return;
        }

        // Proceed with the request
        chain.doFilter(req, res);
    }

    /**
     * Returns where to redirect the request, or null if it passes on
     * @param request the request
     * @return where to redirect the request, or null if it passes on
     */
    private String redirectLocation(HttpServletRequest request) {
        if (!isRootRequest(request)) {
            return null;
        }

        String adminAppUrl = StringUtils.stripEnd(StringUtils.trim(app.getAdminAppUrl()), "/");
        if (StringUtils.isBlank(adminAppUrl) || !isOtherOrigin(adminAppUrl, request)) {
            return null;
        }

        String query = request.getQueryString();
        return adminAppUrl + "/" + (StringUtils.isEmpty(query) ? "" : "?" + query);
    }

    /** Returns if the request is a GET or HEAD for exactly the root of the context */
    private static boolean isRootRequest(HttpServletRequest request) {
        String method = request.getMethod();
        return ("GET".equals(method) || "HEAD".equals(method))
                && "/".equals(StringUtils.removeStart(request.getRequestURI(), request.getContextPath()));
    }

    /**
     * Returns if the URL names an origin (scheme, host and port) other than the one the request came in on.
     * A URL without a host resolves against the request's own origin, and one that does not parse names none.
     */
    private static boolean isOtherOrigin(String url, HttpServletRequest request) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return false;
        }
        return !uri.getScheme().equalsIgnoreCase(request.getScheme())
                || !uri.getHost().equalsIgnoreCase(request.getServerName())
                || port(uri.getScheme(), uri.getPort()) != port(request.getScheme(), request.getServerPort());
    }

    /** Returns the port, or the scheme's default port when none is given */
    private static int port(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
