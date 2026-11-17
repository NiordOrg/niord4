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
package org.niord.core.domain;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DomainResolver} and its two nested implementations
 * {@link DomainResolver.RequestHeaderDomainResolver} and
 * {@link DomainResolver.RequestSubDomainResolver}.
 *
 * <p>The resolver determines the current Niord domain (Keycloak client) from an incoming request.
 * After the Spring port the resolver exposes a single {@code resolveDomain(HttpServletRequest)}
 * overload (the old Keycloak {@code HttpFacade.Request} / Vert.x overloads were removed), so these
 * tests drive the real header-lookup logic and the real URI-parsing regexes through that overload.
 * A Mockito mock of {@link HttpServletRequest} carries the test data: only {@code getHeader} and
 * {@code getRequestURI} are stubbed — the sub-domain resolver feeds {@code getRequestURI()} into the
 * regexes, so the full URI under test is mapped onto that getter.
 *
 * <p>The sub-domain resolver uses two regexes:
 * <pre>
 *   serverPattern    = ^https?://(?&lt;server&gt;[^/]+)/.*$   (CASE_INSENSITIVE)
 *   subDomainPattern = ^(?&lt;subdomain&gt;[^.]+)\..*$         (CASE_INSENSITIVE)
 * </pre>
 * The oracles below are computed by tracing these two regexes by hand.
 */
public class DomainResolverTest {

    // ---------------------------------------------------------------------
    // newInstance() / ServiceLoader wiring
    // ---------------------------------------------------------------------

    @Test
    public void newInstanceReturnsHeaderResolverFromServiceLoader() {
        DomainResolver resolver = DomainResolver.newInstance();

        // META-INF/services wires DomainResolver$RequestHeaderDomainResolver as the implementation.
        assertTrue(
                resolver instanceof DomainResolver.RequestHeaderDomainResolver,
                "newInstance() should return a RequestHeaderDomainResolver, was: "
                        + (resolver == null ? "null" : resolver.getClass().getName()));
    }

    @Test
    public void newInstanceResolvesDomainFromTheNiordDomainHeader() {
        DomainResolver resolver = DomainResolver.newInstance();
        HttpServletRequest request = requestWithHeader("NiordDomain", "niord-client-nw");

        // The wired (header) resolver reads the "NiordDomain" header, ignoring the URI.
        assertEquals("niord-client-nw", resolver.resolveDomain(request));
    }

    @Test
    public void newInstanceReturnsAFreshInstanceEachCall() {
        DomainResolver first = DomainResolver.newInstance();
        DomainResolver second = DomainResolver.newInstance();

        // ServiceLoader.load(...).iterator().next() creates a new instance per call.
        assertTrue(first != second, "each newInstance() call should create a distinct instance");
    }

    // ---------------------------------------------------------------------
    // RequestHeaderDomainResolver
    // ---------------------------------------------------------------------

    @Test
    public void headerResolverReturnsNiordDomainHeaderValue() {
        DomainResolver resolver = new DomainResolver.RequestHeaderDomainResolver();
        HttpServletRequest request = requestWithHeader("NiordDomain", "niord-client-nw");

        assertEquals("niord-client-nw", resolver.resolveDomain(request));
    }

    @Test
    public void headerResolverReturnsNullWhenNiordDomainHeaderMissing() {
        DomainResolver resolver = new DomainResolver.RequestHeaderDomainResolver();
        // The mock only answers the "NiordDomain" header; here we set a different header so it is absent.
        HttpServletRequest request = requestWithHeader("Some-Other-Header", "value");

        assertNull(resolver.resolveDomain(request));
    }

    @Test
    public void headerResolverIgnoresTheRequestUri() {
        DomainResolver resolver = new DomainResolver.RequestHeaderDomainResolver();
        // A sub-domain URI that must NOT be used by the header resolver.
        HttpServletRequest request =
                requestWithHeaderAndUri("NiordDomain", "niord-client-master", "https://nw.niord.org/some/path");

        // Header resolver must use the header value, not the sub-domain from the URI.
        assertEquals("niord-client-master", resolver.resolveDomain(request));
    }

    // ---------------------------------------------------------------------
    // RequestSubDomainResolver - positive cases
    // ---------------------------------------------------------------------

    @Test
    public void subDomainResolverExtractsLeadingSubDomain() {
        DomainResolver resolver = new DomainResolver.RequestSubDomainResolver();
        HttpServletRequest request = requestWithUri("https://nw.niord.org/some/path");

        // server = "nw.niord.org" -> subdomain = "nw"
        assertEquals("nw", resolver.resolveDomain(request));
    }

    @Test
    public void subDomainResolverHandlesHttpAndTrailingSlashOnlyPath() {
        DomainResolver resolver = new DomainResolver.RequestSubDomainResolver();
        HttpServletRequest request = requestWithUri("http://nm.niord.org/");

        // serverPattern requires a "/" after the server; a bare "/" satisfies ".*". server = "nm.niord.org" -> "nm"
        assertEquals("nm", resolver.resolveDomain(request));
    }

    @Test
    public void subDomainResolverTreatsFirstDotSegmentAsSubDomainEvenForTwoLabelHost() {
        DomainResolver resolver = new DomainResolver.RequestSubDomainResolver();
        HttpServletRequest request = requestWithUri("https://niord.org/x");

        // server = "niord.org"; subDomainPattern ^[^.]+\..*$ matches with subdomain = "niord".
        // There is no notion of a registrable domain here: the first label before the first dot wins.
        assertEquals("niord", resolver.resolveDomain(request));
    }

    @Test
    public void subDomainResolverIsCaseInsensitiveButPreservesOriginalCase() {
        DomainResolver resolver = new DomainResolver.RequestSubDomainResolver();
        HttpServletRequest request = requestWithUri("HTTPS://NW.NIORD.ORG/x");

        // Both patterns are CASE_INSENSITIVE so the upper-case scheme/host match, but the regex only
        // *matches* - it does not lower-case. The captured sub-domain keeps its original case: "NW".
        assertEquals("NW", resolver.resolveDomain(request));
    }

    @Test
    public void subDomainResolverIgnoresPortInServerWhenADotIsPresent() {
        DomainResolver resolver = new DomainResolver.RequestSubDomainResolver();
        HttpServletRequest request = requestWithUri("https://nw.niord.org:8080/x");

        // server = "nw.niord.org:8080"; subdomain stops at the first dot -> "nw" (port is in the tail).
        assertEquals("nw", resolver.resolveDomain(request));
    }

    // ---------------------------------------------------------------------
    // RequestSubDomainResolver - negative / edge cases
    // ---------------------------------------------------------------------

    @Test
    public void subDomainResolverReturnsNullForNonHttpScheme() {
        DomainResolver resolver = new DomainResolver.RequestSubDomainResolver();
        HttpServletRequest request = requestWithUri("ftp://nw.niord.org/x");

        // serverPattern anchors on ^https?:// so an ftp scheme never matches.
        assertNull(resolver.resolveDomain(request));
    }

    @Test
    public void subDomainResolverReturnsNullForNonUrlString() {
        DomainResolver resolver = new DomainResolver.RequestSubDomainResolver();
        HttpServletRequest request = requestWithUri("notaurl");

        // No scheme / no path separator -> serverPattern does not match -> null.
        assertNull(resolver.resolveDomain(request));
    }

    @Test
    public void subDomainResolverReturnsNullWhenServerHasNoDot() {
        DomainResolver resolver = new DomainResolver.RequestSubDomainResolver();
        HttpServletRequest request = requestWithUri("http://localhost:8080/api");

        // server = "localhost:8080" has no '.', so subDomainPattern (which requires a literal dot) fails.
        assertNull(resolver.resolveDomain(request));
    }

    @Test
    public void subDomainResolverReturnsNullWhenServerHasNoTrailingPath() {
        DomainResolver resolver = new DomainResolver.RequestSubDomainResolver();
        HttpServletRequest request = requestWithUri("https://nw.niord.org");

        // serverPattern requires "/.*$" AFTER the server; without any path separator it does not match.
        assertNull(resolver.resolveDomain(request));
    }

    // =====================================================================
    // Mockito-based HttpServletRequest stubs. Only getHeader / getRequestURI
    // carry test data; everything else returns Mockito's default so the real
    // resolver logic is exercised against the Spring servlet API.
    // =====================================================================

    /** A request whose getRequestURI() returns the given URI (header lookups return null). */
    private static HttpServletRequest requestWithUri(String uri) {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    /** A request that answers only the given header name; any other header lookup returns null. */
    private static HttpServletRequest requestWithHeader(String name, String value) {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getHeader(name)).thenReturn(value);
        return request;
    }

    /** A request that answers the given header name and exposes the given URI via getRequestURI(). */
    private static HttpServletRequest requestWithHeaderAndUri(String name, String value, String uri) {
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getHeader(name)).thenReturn(value);
        Mockito.when(request.getRequestURI()).thenReturn(uri);
        return request;
    }
}
