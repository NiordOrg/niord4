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
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.util.MdcContext;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RequestIdFilter}, i.e. for the correlation-id contract documented on that
 * class: which inbound {@code X-Request-Id} values are honoured, which are replaced, that the
 * effective id always comes back on the response — including on failed responses — and that the MDC
 * key never outlives the request on a (reused) container thread.
 *
 * <p>The header validation is the security-relevant part. The inbound value is copied into every log
 * line the request produces, so a value containing CR/LF would let a caller write arbitrary lines
 * into the log sink. These tests pin that such values are dropped rather than sanitised in place,
 * and that the request itself still succeeds — the header is a debugging aid, and refusing to serve
 * a request over one would trade an observability feature for an availability risk.
 *
 * <p>Mock servlet plumbing only, no container: the filter has no dependencies.
 */
public class RequestIdFilterTest {

    /** Matches the generated form: {@link MdcContext#newRequestId()} emits 12 hex characters. */
    private static final String GENERATED = "[0-9a-f]{12}";

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    public void clearMdc() {
        MDC.clear();
    }

    /** A chain that records the MDC value visible to downstream filters and handlers. */
    private static FilterChain capturingChain(AtomicReference<String> seen) {
        return (req, res) -> seen.set(MDC.get(MdcContext.REQUEST_ID));
    }

    private static MockHttpServletRequest request(String... requestIdHeaders) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/messages");
        for (String value : requestIdHeaders) {
            request.addHeader(RequestIdFilter.HEADER, value);
        }
        return request;
    }

    // ------------------------------------------------------------------------------------------------
    // The contract: which inbound value wins
    // ------------------------------------------------------------------------------------------------

    @Test
    public void generatesAnIdWhenNoHeaderIsPresent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request(), response, capturingChain(seen));

        assertNotNull(seen.get(), "an id must be visible to the rest of the chain");
        assertTrue(seen.get().matches(GENERATED), "generated ids are 12 hex characters: " + seen.get());
        assertEquals(seen.get(), response.getHeader(RequestIdFilter.HEADER),
                "the effective id must be echoed to the caller");
    }

    @Test
    public void keepsAWellFormedInboundIdVerbatim() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request("edge-proxy.7f3a_B99"), response, capturingChain(seen));

        assertEquals("edge-proxy.7f3a_B99", seen.get(), "a caller-supplied trace must be joined, not replaced");
        assertEquals("edge-proxy.7f3a_B99", response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    public void acceptsAnIdOfExactlyTheMaximumLength() throws Exception {
        String maxLength = "a".repeat(64);
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request(maxLength), new MockHttpServletResponse(), capturingChain(seen));

        assertEquals(maxLength, seen.get(), "64 characters is inside the accepted range");
    }

    // ------------------------------------------------------------------------------------------------
    // Rejected inbound values — the log-injection guard
    // ------------------------------------------------------------------------------------------------

    @Test
    public void replacesAnIdCarryingCrLf() throws Exception {
        // Were this echoed into the log pattern verbatim, the caller would be writing their own
        // ERROR lines into Niord's log.
        String forged = "a" + (char) 13 + (char) 10 + "ERROR req=a Deleted all messages";
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request(forged), response, capturingChain(seen));

        assertNotEquals(forged, seen.get());
        assertTrue(seen.get().matches(GENERATED), "must fall back to a generated id");
        assertEquals(seen.get(), response.getHeader(RequestIdFilter.HEADER),
                "the forged value must not be reflected back to the caller either");
    }

    @Test
    public void replacesAnIdThatIsTooLong() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request("a".repeat(65)), new MockHttpServletResponse(), capturingChain(seen));

        assertTrue(seen.get().matches(GENERATED), "65 characters is outside the accepted range");
    }

    @Test
    public void replacesAnIdContainingSeparatorsOrControlCharacters() throws Exception {
        String[] hostile = {
                "a b",                          // a space would split the field in the log pattern
                "",                             // present but empty
                " ",
                "a" + (char) 9 + "b",           // tab
                "a" + (char) 10 + "b",          // bare LF
                "a|b",
                "req=other"
        };

        for (String value : hostile) {
            AtomicReference<String> seen = new AtomicReference<>();

            filter.doFilter(request(value), new MockHttpServletResponse(), capturingChain(seen));

            assertTrue(seen.get().matches(GENERATED),
                    "must reject a hostile value of length " + value.length() + " and generate instead");
        }
    }

    @Test
    public void replacesTheIdWhenMoreThanOneHeaderValueIsPresent() throws Exception {
        // Two hops each stamped an id: they disagree about which trace this is, and joining the
        // wrong parent trace is a worse outcome than starting a new one.
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request("first-trace", "second-trace"), new MockHttpServletResponse(),
                capturingChain(seen));

        assertNotEquals("first-trace", seen.get(), "must not silently pick the first of several");
        assertNotEquals("second-trace", seen.get());
        assertTrue(seen.get().matches(GENERATED));
    }

    @Test
    public void neverRejectsTheRequestOverAMalformedHeader() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertDoesNotThrow(() ->
                filter.doFilter(request("a" + (char) 13 + (char) 10 + "forged"), response, (req, res) -> { }));

        assertEquals(200, response.getStatus(),
                "a bad debugging header must not turn into a failed request");
    }

    // ------------------------------------------------------------------------------------------------
    // The response header survives failures
    // ------------------------------------------------------------------------------------------------

    @Test
    public void echoesTheIdEvenWhenTheChainThrows() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain boom = (req, res) -> { throw new IllegalStateException("downstream failure"); };

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request("trace-99"), response, boom));

        // The header is set BEFORE the chain runs precisely so this holds: OncePerRequestFilter does
        // not re-run on the ERROR dispatch, so an id added afterwards would be missing from exactly
        // the responses someone is trying to debug.
        assertEquals("trace-99", response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    public void echoesTheIdEvenWhenTheChainSendsAnError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> ((HttpServletResponse) res).sendError(500, "nope");

        filter.doFilter(request("trace-500"), response, failing);

        assertEquals(500, response.getStatus());
        assertEquals("trace-500", response.getHeader(RequestIdFilter.HEADER));
    }

    // ------------------------------------------------------------------------------------------------
    // MDC lifecycle — container threads are pooled and reused
    // ------------------------------------------------------------------------------------------------

    @Test
    public void removesTheMdcKeyAfterTheRequestCompletes() throws Exception {
        filter.doFilter(request("trace-1"), new MockHttpServletResponse(), (req, res) -> { });

        assertNull(MDC.get(MdcContext.REQUEST_ID),
                "the id must not leak onto the next request served by this thread");
    }

    @Test
    public void removesTheMdcKeyWhenTheChainThrows() {
        FilterChain boom = (req, res) -> { throw new IllegalStateException("downstream failure"); };

        assertThrows(IllegalStateException.class,
                () -> filter.doFilter(request("trace-2"), new MockHttpServletResponse(), boom));

        assertNull(MDC.get(MdcContext.REQUEST_ID), "cleanup must happen on the failure path too");
    }

    @Test
    public void leavesOtherMdcKeysAlone() throws Exception {
        // The filter owns exactly one key. Clearing the whole MDC would discard the domain that
        // NiordDomainFilter parks on the same thread further down the chain.
        MDC.put(MdcContext.DOMAIN, "niord-client-nw");

        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> { });

        assertEquals("niord-client-nw", MDC.get(MdcContext.DOMAIN));
    }

    @Test
    public void generatesADistinctIdPerRequest() throws Exception {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        filter.doFilter(request(), new MockHttpServletResponse(), capturingChain(first));
        filter.doFilter(request(), new MockHttpServletResponse(), capturingChain(second));

        assertNotEquals(first.get(), second.get(), "ids must not repeat across requests");
    }
}
