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
package org.niord.secom.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the checks {@link SecomOutboundClient} applies to the peer endpoints the MCP
 * service registry returns, before a signed delivery goes out: the endpoint syntax
 * ({@code validatePeerEndpoint} - https only, no user-info, query or fragment) and the address
 * the host resolves to ({@code requirePublicAddresses} - no loopback, private or link-local
 * address). Both are relaxed by the {@code secomAllowInsecureEndpoints} test-environment flag.
 * Only address literals are resolved here, so the tests need no DNS.
 */
class SecomOutboundClientEndpointTest {

    /***************************************/
    /** Endpoint syntax                   **/
    /***************************************/

    @Test
    void anHttpsEndpointIsAccepted() {
        URI uri = SecomOutboundClient.validatePeerEndpoint("https://peer.example.org/api/secom", false);
        assertEquals("peer.example.org", uri.getHost());
        assertEquals("/api/secom", uri.getPath());

        // Surrounding whitespace from the registry entry is tolerated, and the scheme is case-insensitive
        assertEquals("peer.example.org",
                SecomOutboundClient.validatePeerEndpoint(" HTTPS://peer.example.org:8443/secom/ ", false).getHost());
    }

    @Test
    void anHttpEndpointIsRefusedUnlessInsecureEndpointsAreAllowed() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SecomOutboundClient.validatePeerEndpoint("http://peer.example.org/api/secom", false));
        assertTrue(refused.getMessage().contains("https"), refused.getMessage());

        // The test-environment flag lets the in-cluster rehearsal push over plain http
        assertEquals("peer.example.org",
                SecomOutboundClient.validatePeerEndpoint("http://peer.example.org/api/secom", true).getHost());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://peer.example.org/secom", "file:///etc/passwd", "peer.example.org/secom",
            "/api/secom", "", "https:///no-host", "https://peer example.org/"})
    void aNonHttpOrMalformedEndpointIsRefusedEvenWhenInsecureEndpointsAreAllowed(String endpoint) {
        assertThrows(IllegalArgumentException.class, () -> SecomOutboundClient.validatePeerEndpoint(endpoint, true));
    }

    @Test
    void aNullEndpointIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SecomOutboundClient.validatePeerEndpoint(null, true));
    }

    @Test
    void userInfoIsRefusedWithoutEchoingIt() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SecomOutboundClient.validatePeerEndpoint("https://user:secret@peer.example.org/secom", false));
        assertFalse(refused.getMessage().contains("secret"), "the credential must not reach the logs: " + refused.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> SecomOutboundClient.validatePeerEndpoint("http://user@peer.example.org/secom", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://peer.example.org/secom?x=", "https://peer.example.org/secom?",
            "https://peer.example.org/secom#fragment", "https://peer.example.org#"})
    void aQueryOrFragmentIsRefused(String endpoint) {
        // The SECOM paths are appended to the endpoint as a string: a query or fragment would
        // decide which path of the peer the signed request reaches
        assertThrows(IllegalArgumentException.class, () -> SecomOutboundClient.validatePeerEndpoint(endpoint, false));
        assertThrows(IllegalArgumentException.class, () -> SecomOutboundClient.validatePeerEndpoint(endpoint, true));
    }

    /***************************************/
    /** Resolved addresses                **/
    /***************************************/

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/secom",          // loopback
            "https://127.1.2.3:8443/secom",     // the whole 127/8
            "https://10.0.0.5/secom",           // private
            "https://172.16.4.4/secom",         // private
            "https://192.168.1.1/secom",        // private
            "https://169.254.169.254/latest",   // link-local, the cloud metadata service
            "https://0.0.0.0/secom",            // wildcard
            "https://[::1]/secom",              // IPv6 loopback
            "https://[fd00::1]/secom",          // IPv6 unique local
            "https://[::ffff:10.1.2.3]/secom"   // IPv4-mapped private
    })
    void aNonPublicAddressIsRefusedUnlessInsecureEndpointsAreAllowed(String endpoint) {
        URI uri = SecomOutboundClient.validatePeerEndpoint(endpoint, false);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> SecomOutboundClient.requirePublicAddresses(uri, false));
        assertTrue(refused.getMessage().contains("non-public address"), refused.getMessage());

        assertDoesNotThrow(() -> SecomOutboundClient.requirePublicAddresses(uri, true));
    }

    @Test
    void aPublicAddressIsAccepted() {
        assertDoesNotThrow(() -> SecomOutboundClient.requirePublicAddresses(
                URI.create("https://93.184.215.14/api/secom"), false));
        assertDoesNotThrow(() -> SecomOutboundClient.requirePublicAddresses(
                URI.create("https://[2606:4700:4700::1111]/api/secom"), false));
    }
}
