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

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sets the administration web app's own security headers:
 * <ul>
 *   <li>a legal document or a branding file ({@code /assets/legal/}, {@code /assets/branding/}) is data a
 *   country supplies, never code of this application, so it gets {@code Content-Security-Policy: sandbox}
 *   and {@code X-Content-Type-Options: nosniff}: opened on its own, it runs no script and has an opaque
 *   origin;</li>
 *   <li>the app's HTML documents get the policy of {@link WebAppContentSecurityPolicy}: every navigation the
 *   fallback forwards to {@code index.html} ({@link WebAppFallbackFilter#isNavigation}), and every top-level
 *   {@code .html} file of the build ({@code index.html}, {@code silent-check-sso.html});</li>
 *   <li>everything else passes untouched: the API, the WMS proxy, the bundles, the other assets.</li>
 * </ul>
 * The path is the servlet path and path info, which the container has decoded and normalised, compared
 * case-insensitively, so {@code /assets/LEGAL/} on a case-insensitive file system is caught too.
 * <p>
 * The headers are set before the rest of the chain runs: they survive the fallback's forward, which resets
 * the buffer but not the headers, and Spring Security's {@code nosniff} writer, which adds its header only
 * when it is absent, sends no second copy.
 * <p>
 * Registered by {@link org.niord.core.conf.WebAppConfig}, only when a deployment serves the web app, after
 * the Spring Security filter chain and before the fallback.
 */
public final class WebAppHeadersFilter extends OncePerRequestFilter {

    /** The policy of a file a country supplies */
    static final String SANDBOX = "sandbox";

    /** A top-level HTML file of the build */
    private static final Pattern TOP_LEVEL_HTML = Pattern.compile("^/[^/]+\\.html$");

    private final WebAppContentSecurityPolicy policy;

    /**
     * Creates the filter.
     * @param policy the policy of the app's HTML documents
     */
    public WebAppHeadersFilter(WebAppContentSecurityPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    /** {@inheritDoc} */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = (request.getServletPath() + Objects.toString(request.getPathInfo(), ""))
                .toLowerCase(Locale.ROOT);
        if (path.startsWith("/assets/legal/") || path.startsWith("/assets/branding/")) {
            response.setHeader("Content-Security-Policy", SANDBOX);
            response.setHeader("X-Content-Type-Options", "nosniff");
        } else if (WebAppFallbackFilter.isNavigation(request) || TOP_LEVEL_HTML.matcher(path).matches()) {
            response.setHeader("Content-Security-Policy", policy.current());
        }
        chain.doFilter(request, response);
    }
}
