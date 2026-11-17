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
package org.niord.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.niord.core.util.MdcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;
import java.util.regex.Pattern;

/**
 * Gives every HTTP request a correlation id, so the log lines it produces — across Keycloak token
 * validation, domain resolution, the database, and the SECOM outbox — can be pulled out of the log
 * as one story. The id is placed in the SLF4J {@link MDC} under {@link MdcContext#REQUEST_ID}, which
 * {@code logging.pattern.level} renders as {@code req=...} on every line.
 *
 * <h2>Contract</h2>
 * <table>
 *   <caption>How the inbound {@value #HEADER} header is treated</caption>
 *   <tr><th>Inbound</th><th>Effective id</th></tr>
 *   <tr><td>absent</td><td>generated</td></tr>
 *   <tr><td>one value matching {@code [A-Za-z0-9._-]{1,64}}</td><td>that value, verbatim</td></tr>
 *   <tr><td>one value failing that pattern</td><td>generated (the inbound value is dropped)</td></tr>
 *   <tr><td>more than one value</td><td>generated (all inbound values are dropped)</td></tr>
 * </table>
 *
 * <p>The effective id is always returned to the caller in the {@value #HEADER} response header.
 *
 * <p>A request is <em>never</em> rejected over this header. A malformed correlation id is a logging
 * concern, not an authorization one; answering 400 because a debugging header was wrong would turn
 * an observability feature into an availability risk.
 *
 * <h2>Why the validation matters</h2>
 * The inbound value is copied into every log line this request produces and echoed in a response
 * header. Without the pattern check, {@code X-Request-Id: a\r\nERROR  forged entry} writes attacker
 * chosen lines into the log sink, and an unbounded value bloats every line. The id is used for
 * logging only — it never influences authorization, caching or routing.
 *
 * <p>Registered at {@link org.springframework.core.Ordered#HIGHEST_PRECEDENCE} by
 * {@code CorrelationConfig}, ahead of every other filter and of the Spring Security chain, so
 * requests rejected with 401/403 carry an id too — which is when one is most wanted.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    /** Request and response header carrying the correlation id. */
    public static final String HEADER = "X-Request-Id";

    /** Log-safe and bounded: no CR/LF, no control characters, no unbounded growth. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    /** {@inheritDoc} */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String requestId = effectiveRequestId(request);
        MDC.put(MdcContext.REQUEST_ID, requestId);

        // Set on the response BEFORE the chain runs, never after. OncePerRequestFilter does not
        // re-run on the ERROR dispatch (shouldNotFilterErrorDispatch() is true by default), so an
        // id added after doFilter would be missing from exactly the failed responses that need it.
        response.setHeader(HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // remove(), not clear(): this filter owns exactly one key, and clearing would discard
            // keys (domain) that filters further down the chain parked on the same thread.
            MDC.remove(MdcContext.REQUEST_ID);
        }
    }

    /**
     * Resolves the id to use for this request, per the contract documented on the class.
     * Package-private so the contract can be tested directly, header by header.
     */
    static String effectiveRequestId(HttpServletRequest request) {

        // getHeaders(), not getHeader(): getHeader() silently returns the first of several values.
        // Two values means two hops each stamped an id and they disagree about which trace this is
        // — joining the wrong parent trace is a worse outcome than starting a new one.
        Enumeration<String> values = request.getHeaders(HEADER);
        if (values == null || !values.hasMoreElements()) {
            return MdcContext.newRequestId();
        }

        String inbound = values.nextElement();
        if (values.hasMoreElements()) {
            logDiscarded("more than one value");
            return MdcContext.newRequestId();
        }

        if (!SAFE_ID.matcher(inbound).matches()) {
            logDiscarded("value is not log-safe");
            return MdcContext.newRequestId();
        }

        return inbound;
    }

    /**
     * Note what was discarded, never the discarded value itself — logging a rejected header would
     * perform the very injection the rejection prevents.
     */
    private static void logDiscarded(String reason) {
        log.debug("Ignoring inbound {} header ({}); generating a new correlation id", HEADER, reason);
    }
}
