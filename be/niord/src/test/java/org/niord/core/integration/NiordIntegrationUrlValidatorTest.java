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
package org.niord.core.integration;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.ProtocolException;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.protocol.HttpCoreContext;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-1 unit tests for {@link NiordIntegrationUrlValidator}, the SSRF guard on the Niord integration
 * importer.
 *
 * <p>The guard is deliberately split in two, and the split is what these tests pin. The syntax check
 * runs when an integration point is created or updated and must NOT resolve DNS — an integration point
 * is routinely defined before the remote server exists, and the integration tests themselves configure
 * hosts that never resolve. The address check instead lives in the {@code DnsResolver} handed to the
 * HTTP client, so the addresses it approves are the very ones the client connects to: no check-to-connect
 * window (DNS rebinding), no parse differential between checker and connector, and every redirect hop
 * checked like the original request.
 *
 * <p>Framework-neutral: no Spring, no container, no network — {@code InetAddress.getByName} parses
 * literals without a lookup, and the resolver is driven with literals only.
 */
public class NiordIntegrationUrlValidatorTest {

    /** The syntax check accepts the shapes an admin legitimately configures. */
    @Test
    public void validateUrl_acceptsHttpAndHttpsHosts() {
        NiordIntegrationUrlValidator.validateUrl("http://niord.example.com");
        NiordIntegrationUrlValidator.validateUrl("https://niord.example.com:8443/niord");
        // A host that does not resolve is still valid: the syntax check must never touch DNS, or an
        // integration point could not be defined before the remote server exists.
        NiordIntegrationUrlValidator.validateUrl("http://it-rest-12345");
    }

    /** Non-http schemes, credentials and a missing host are rejected — each with its own message. */
    @Test
    public void validateUrl_rejectsForeignSchemeUserInfoAndMissingHost() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> NiordIntegrationUrlValidator.validateUrl("file:///etc/passwd"))
                .getMessage().contains("http or https"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> NiordIntegrationUrlValidator.validateUrl("http://user:pw@niord.example.com"))
                .getMessage().contains("user info"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> NiordIntegrationUrlValidator.validateUrl("http:///rest"))
                .getMessage().contains("host name"));
        assertThrows(IllegalArgumentException.class, () -> NiordIntegrationUrlValidator.validateUrl(null));
        assertThrows(IllegalArgumentException.class, () -> NiordIntegrationUrlValidator.validateUrl("  "));
    }

    /**
     * A query or fragment on the base URL is rejected, because the endpoint path is appended to it as a
     * raw string. {@code "https://peer/x?a=b"} composes to a URL whose path is only {@code /x}, with
     * {@code /rest/public/v1/messages} swallowed into the query; {@code "https://peer#f"} composes to
     * one with an EMPTY path, so the fetch would hit the peer's root and parse whatever it returned.
     * Both were accepted before — the composed URL no longer matched the validated one.
     */
    @Test
    public void validateUrl_rejectsQueryAndFragment() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> NiordIntegrationUrlValidator.validateUrl("https://peer.example.com/x?a=b"))
                .getMessage().contains("query or fragment"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> NiordIntegrationUrlValidator.validateUrl("https://peer.example.com#frag"))
                .getMessage().contains("query or fragment"));
        // An empty-but-present query ("?") and fragment ("#") count too - getRawQuery()/getRawFragment()
        // return "" rather than null, and both still break the concatenation.
        assertThrows(IllegalArgumentException.class,
                () -> NiordIntegrationUrlValidator.validateUrl("https://peer.example.com?"));
        assertThrows(IllegalArgumentException.class,
                () -> NiordIntegrationUrlValidator.validateUrl("https://peer.example.com#"));

        // A path is still fine - it is what the endpoint path legitimately appends to.
        NiordIntegrationUrlValidator.validateUrl("https://peer.example.com/niord");
    }

    /**
     * A refusal names no part of the URL that can carry a credential: an address that is not hierarchical has no
     * user info to the parser, and a query may hold a password, so neither refusal repeats the URL.
     */
    @Test
    public void validateUrl_refusalsDoNotRepeatACredentialTheUrlCarries() {
        String scheme = assertThrows(IllegalArgumentException.class,
                () -> NiordIntegrationUrlValidator.validateUrl("ftp:user:xyzzy@peer.example.com")).getMessage();
        assertTrue(scheme.contains("http or https"), scheme);
        assertFalse(scheme.contains("xyzzy"), scheme);

        String query = assertThrows(IllegalArgumentException.class,
                () -> NiordIntegrationUrlValidator.validateUrl("https://peer.example.com/?password=xyzzy"))
                .getMessage();
        assertTrue(query.contains("query or fragment"), query);
        assertFalse(query.contains("xyzzy"), query);
    }

    /**
     * A trailing slash stays ACCEPTED. It composes to "//rest/public/v1/messages", but Apache
     * HttpClient normalizes that before the request reaches the wire (pinned end-to-end by
     * {@code NiordIntegrationExecutionServiceHttpTest}), so it needs no rejection and no trimming.
     */
    @Test
    public void validateUrl_acceptsATrailingSlash() {
        NiordIntegrationUrlValidator.validateUrl("https://peer.example.com/");
        NiordIntegrationUrlValidator.validateUrl("https://peer.example.com/niord/");
    }

    /**
     * Every non-public range is recognised — including the forms that hide behind a notation. The
     * decimal ("2130706433"), short ("127.1") and IPv4-mapped-IPv6 ("::ffff:127.0.0.1") spellings of
     * loopback need no handling of their own precisely because the check runs on a parsed
     * {@link InetAddress}; asserting them here is what pins that property.
     */
    @Test
    public void isNonPublicAddress_coversLoopbackPrivateLinkLocalAndMulticast() throws Exception {
        for (String host : new String[]{"127.0.0.1", "2130706433", "127.1", "::1", "::ffff:127.0.0.1",
                "0.0.0.0", "10.1.2.3", "192.168.1.1", "172.16.0.1", "169.254.169.254", "fd00::1",
                "fe80::1", "224.0.0.1"}) {
            assertTrue(NiordIntegrationUrlValidator.isNonPublicAddress(InetAddress.getByName(host)), host);
        }
        assertFalse(NiordIntegrationUrlValidator.isNonPublicAddress(InetAddress.getByName("8.8.8.8")),
                "a public address must not be blocked");
    }

    /**
     * The IPv6 notations that wrap an arbitrary IPv4 address. The JDK collapses {@code ::ffff:x} to an
     * {@code Inet4Address} — so the ordinary predicates catch it — but NOT the NAT64 well-known prefix
     * {@code 64:ff9b::/96} or 6to4's {@code 2002::/16}, which stay {@code Inet6Address}. Without the
     * explicit unwrapping, {@code 64:ff9b::7f00:1} would reach 127.0.0.1 unchecked.
     */
    @Test
    public void isNonPublicAddress_unwrapsNat64And6to4WrappedIpv4() throws Exception {
        for (String host : new String[]{
                "64:ff9b::7f00:1",      // NAT64 wrapping 127.0.0.1
                "64:ff9b::a00:1",       // NAT64 wrapping 10.0.0.1
                "64:ff9b::a9fe:a9fe",   // NAT64 wrapping 169.254.169.254 (cloud metadata)
                "2002:a00:1::1",        // 6to4 wrapping 10.0.0.1
                "2002:7f00:1::1"}) {    // 6to4 wrapping 127.0.0.1
            assertTrue(NiordIntegrationUrlValidator.isNonPublicAddress(InetAddress.getByName(host)), host);
        }
        // The same wrappers around a PUBLIC address stay reachable - the unwrapping must not blanket-block
        assertFalse(NiordIntegrationUrlValidator.isNonPublicAddress(InetAddress.getByName("64:ff9b::808:808")),
                "NAT64 wrapping 8.8.8.8 is a public destination");
    }

    /**
     * Reserved IPv4 ranges {@link InetAddress}'s own predicates do not cover. 0.0.0.0/8 matters most:
     * Linux routes {@code 0.1.2.3} to loopback, so it is a working SSRF vector that
     * {@code isAnyLocalAddress()} — which only matches the exact 0.0.0.0 — lets through.
     */
    @Test
    public void isNonPublicAddress_coversTheReservedIpv4RangesTheJdkPredicatesMiss() throws Exception {
        for (String host : new String[]{
                "0.1.2.3",          // 0.0.0.0/8 - routed to loopback on Linux
                "100.64.0.1",       // carrier-grade NAT
                "192.0.0.170",      // IETF protocol assignments
                "198.18.0.1",       // benchmarking
                "240.0.0.1"}) {     // reserved
            assertTrue(NiordIntegrationUrlValidator.isNonPublicAddress(InetAddress.getByName(host)), host);
        }
        // Neighbouring public addresses must stay reachable - the ranges must not be over-wide.
        // Note 240.0.0.0/4 has no public lower neighbour: 224.0.0.0/4 below it is multicast, which
        // isMulticastAddress() already blocks.
        for (String host : new String[]{"1.1.1.1", "100.63.255.255", "100.128.0.1", "192.0.1.1",
                "198.17.255.255", "198.20.0.1"}) {
            assertFalse(NiordIntegrationUrlValidator.isNonPublicAddress(InetAddress.getByName(host)), host);
        }
    }

    /** The resolver blocks a non-public address, and passes it through when private hosts are allowed. */
    @Test
    public void dnsResolver_blocksLoopbackUnlessPrivateHostsAreAllowed() throws Exception {
        UnknownHostException ex = assertThrows(UnknownHostException.class,
                () -> NiordIntegrationUrlValidator.dnsResolver(false).resolve("127.0.0.1"));
        assertTrue(ex.getMessage().contains("non-public"), ex.getMessage());

        assertEquals(1, NiordIntegrationUrlValidator.dnsResolver(true).resolve("127.0.0.1").length,
                "with niordIntegrationAllowPrivateHosts on, the address is resolved as usual");
    }

    /**
     * INT8. No refusal names a credential that stands in the URL, whatever else is wrong with it: the message is
     * returned to the caller and logged. A foreign scheme was refused first, with the whole URL.
     */
    @Test
    public void validateUrl_namesNoCredential_inAnyRefusal() {
        for (String url : List.of(
                "ftp://user:secret@peer.example.org",
                "http://user:secret@peer.example.org/x?a=b",
                "http://user:secret@peer_host.example.org",
                "http://user:secret@peer .example.org")) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> NiordIntegrationUrlValidator.validateUrl(url), url);
            assertFalse(refused.getMessage().contains("secret"), url + ": " + refused.getMessage());
        }
        assertEquals("http://peer.example.org", NiordIntegrationUrlValidator.withoutUserInfo(
                "http://user:secret@peer.example.org"));
    }

    /** INT8. A fetch that starts on https follows no redirect to http; the other redirects are followed. */
    @Test
    public void redirectStrategy_refusesAHopFromHttpsToHttp() throws Exception {
        RedirectStrategy strategy = NiordIntegrationUrlValidator.redirectStrategy();

        ProtocolException refused = assertThrows(ProtocolException.class, () -> strategy.getRedirect(
                new HttpGet("https://peer.example.org/rest/public/v1/messages"),
                redirectTo("http://peer.example.org/rest/public/v1/messages"), context("https")));
        assertTrue(refused.getMessage().contains("https to http"), refused.getMessage());

        assertEquals(URI.create("https://other.example.org/rest/public/v1/messages"), strategy.getRedirect(
                new HttpGet("https://peer.example.org/rest/public/v1/messages"),
                redirectTo("https://other.example.org/rest/public/v1/messages"), context("https")).getURI());
        assertEquals(URI.create("https://peer.example.org/rest/public/v1/messages"), strategy.getRedirect(
                new HttpGet("http://peer.example.org/rest/public/v1/messages"),
                redirectTo("https://peer.example.org/rest/public/v1/messages"), context("http")).getURI());
        assertEquals("https", strategy.getRedirect(new HttpGet("https://peer.example.org/rest"),
                redirectTo("/niord/rest"), context("https")).getURI().getScheme(), "a relative location stays on https");
    }

    private static HttpResponse redirectTo(String location) {
        HttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, 302, "Found");
        response.setHeader("Location", location);
        return response;
    }

    private static HttpClientContext context(String scheme) {
        HttpClientContext context = HttpClientContext.create();
        context.setAttribute(HttpCoreContext.HTTP_TARGET_HOST, new HttpHost("peer.example.org", -1, scheme));
        return context;
    }
}
