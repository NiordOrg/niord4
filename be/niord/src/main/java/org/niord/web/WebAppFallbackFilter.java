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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Answers a browser's navigation to one of the web app's own addresses with the app's {@code index.html}.
 * <p>
 * The administration web app routes in the browser: {@code /messages}, {@code /editor/...} and
 * {@code /admin/...} exist only inside it. A reload, a bookmark or a message link sends such a path to the
 * server, which has no file for it. This filter forwards the request to {@value #INDEX}, and the app routes
 * it once it has loaded.
 * <p>
 * Only a navigation is forwarded: a GET or HEAD whose {@code Accept} header asks for HTML, for a path whose
 * last segment has no file extension, outside the server's own prefixes — the REST API ({@code /rest}),
 * SECOM ({@code /api}), the WMS proxy ({@code /wms}), Keycloak ({@code /auth}), the server-side assets
 * ({@code /img}, {@code /css}) and the app's own folders ({@code /assets}, {@code /media}). Everything else
 * passes on untouched, so an unknown API path or a missing file still answers 404.
 * <p>
 * The root ({@code /}) is the one exception: a GET or HEAD for it gets the app whatever it accepts. The root
 * is never an API path or a file, and a client that types the bare hostname is not always a browser:
 * {@code curl} sends {@code Accept: *}{@code /*}, and a monitoring probe often sends no {@code Accept} at all.
 * Both got 404 while the site was fine (devops-shared, open issue 10). The old Niord's nginx served the app
 * at {@code /}, and the operator's external check expected 200 there.
 * <p>
 * Registered by {@link org.niord.core.conf.WebAppConfig}, and only when a deployment serves the web app.
 */
public class WebAppFallbackFilter extends OncePerRequestFilter {

    /** The page every navigation is forwarded to */
    public static final String INDEX = "/index.html";

    /** Path prefixes the server answers itself, never the web app */
    private static final List<String> SERVER_PREFIXES =
            List.of("/rest", "/api", "/wms", "/auth", "/img", "/css", "/assets", "/media");

    /** {@inheritDoc} */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isNavigation(request)) {
            request.getRequestDispatcher(INDEX).forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Returns if the request is a browser's navigation to an address of the web app, or any client's GET or HEAD
     * for the root
     * @param request the request
     * @return if the request is to get the web app's index page
     */
    static boolean isNavigation(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        String path = StringUtils.removeStart(request.getRequestURI(), request.getContextPath());
        if ("/".equals(path)) {
            return true;
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || !accept.contains(MediaType.TEXT_HTML_VALUE)) {
            return false;
        }
        for (String prefix : SERVER_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return false;
            }
        }
        return !path.substring(path.lastIndexOf('/') + 1).contains(".");
    }
}
