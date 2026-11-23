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

import org.grad.secomv2.core.models.enums.DigitalSignatureAlgorithmEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingChangedEvent;
import org.niord.core.settings.SettingsService;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.pki.NiordSecomProviders;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for how {@link SecomOutboundClient} follows a saved setting: whether a peer's server certificate is
 * checked for revocation ({@code secomRevocationCheck}) is read at each https exchange, and a save of the registry
 * URL drops the endpoints resolved from the registry before. No exchange leaves the test: the PKI's HTTP client is a
 * stand-in.
 */
class SecomOutboundClientSettingsTest {

    private static final String PEER = "urn:mrn:mcp:service:mcc:test:instance:peer";

    private static X509Certificate certificate;
    private static X509Certificate root;
    private static PrivateKey key;

    @BeforeAll
    static void loadTestIdentity() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = SecomOutboundClientSettingsTest.class.getResourceAsStream("/secom-test/keystore-test.p12")) {
            keyStore.load(in, "changeit".toCharArray());
        }
        certificate = (X509Certificate) keyStore.getCertificate("1");
        root = (X509Certificate) keyStore.getCertificateChain("1")[1];
        key = (PrivateKey) keyStore.getKey("1", "changeit".toCharArray());
    }

    @Test
    void theRevocationCheckSettingIsReadAtEachExchange() throws Exception {
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.get(any(Setting.class))).thenReturn(Boolean.TRUE);
        McpSecurityService pki = testPki();
        when(pki.newHttpClient(anyBoolean(), anyBoolean())).thenThrow(new IllegalStateException("no exchange here"));
        SecomOutboundClient client = client(pki, settingsService);

        assertEquals("no exchange here", assertThrows(IllegalStateException.class,
                () -> client.resolveEndpoint(PEER)).getMessage());
        verify(pki).newHttpClient(false, true);

        when(settingsService.get(any(Setting.class))).thenReturn(Boolean.FALSE);

        assertThrows(IllegalStateException.class, () -> client.resolveEndpoint(PEER));
        verify(pki).newHttpClient(false, false);
    }

    @Test
    void aSaveOfTheRegistryUrlDropsTheResolvedEndpoints() throws Exception {
        McpSecurityService pki = testPki();
        HttpClient httpClient = registryAnswering("{\"envelope\":{\"serviceInstance\":[{\"instanceId\":\"" + PEER
                + "\",\"endpointUri\":\"https://peer.example.org/secom/\"}]}}");
        when(pki.newHttpClient(anyBoolean(), anyBoolean())).thenReturn(httpClient);
        SecomOutboundClient client = client(pki, mock(SettingsService.class));

        assertEquals("https://peer.example.org/secom", client.resolveEndpoint(PEER));
        assertEquals("https://peer.example.org/secom", client.resolveEndpoint(PEER));
        client.settingChanged(new SettingChangedEvent("secomAllowInsecureEndpoints", true));
        assertEquals("https://peer.example.org/secom", client.resolveEndpoint(PEER));
        verify(pki, times(1)).newHttpClient(anyBoolean(), anyBoolean());

        client.settingChanged(new SettingChangedEvent("secomServiceRegistryUrl", false));

        assertEquals("https://peer.example.org/secom", client.resolveEndpoint(PEER));
        verify(pki, times(2)).newHttpClient(anyBoolean(), anyBoolean());
    }

    /** The client under test, on an https registry */
    private static SecomOutboundClient client(McpSecurityService pki, SettingsService settingsService) {
        SecomOutboundClient client = new SecomOutboundClient(pki,
                new NiordSecomProviders(pki, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA), settingsService);
        client.serviceRegistryUrl = "https://registry.example.org";
        return client;
    }

    /** A PKI stand-in that signs as the committed test identity */
    private static McpSecurityService testPki() throws Exception {
        McpSecurityService pki = mock(McpSecurityService.class);
        when(pki.keystoreAlias()).thenReturn("1");
        when(pki.serviceCertificate()).thenReturn(certificate);
        when(pki.rootCertificate()).thenReturn(root);
        when(pki.sign(anyString(), any(byte[].class))).thenAnswer(invocation -> {
            Signature signature = Signature.getInstance(invocation.getArgument(0));
            signature.initSign(key);
            signature.update((byte[]) invocation.getArgument(1));
            return signature.sign();
        });
        return pki;
    }

    /** An HTTP client stand-in whose every exchange answers 200 with the given body */
    @SuppressWarnings("unchecked")
    private static HttpClient registryAnswering(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        HttpClient httpClient = mock(HttpClient.class);
        doReturn(CompletableFuture.completedFuture(response)).when(httpClient).sendAsync(any(), any());
        return httpClient;
    }
}
