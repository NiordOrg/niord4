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
package org.niord.secom.rest;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.grad.secomv2.core.exceptions.SecomNotAuthorisedException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.niord.secom.SecomConfiguration;
import org.niord.secom.pki.CertificateRenewalUtils;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.service.SecomSubscriptionService;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MrnCertificateFilter}: the header is only a source of identity when
 * the deployment vouches for it ({@code niord.secom.trust-cert-header}, off by default), and
 * the certificate parsing + MRN extraction behind it - all three header encodings used in the
 * wild must parse (base64 DER as sent by Caddy, plain PEM, and the URL-encoded PEM of nginx's
 * {@code $ssl_client_escaped_cert}). The MRN itself is extracted by
 * {@link CertificateRenewalUtils#subjectMrn}, which the filter delegates to. Whether the chain is validated and
 * checked for revocation is read at each request.
 */
public class MrnCertificateFilterTest {

    static X509Certificate serviceCert;
    static String pem;

    @BeforeAll
    static void loadTestCertificate() throws Exception {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = MrnCertificateFilterTest.class.getResourceAsStream("/secom-test/keystore-test.p12")) {
            keystore.load(in, "changeit".toCharArray());
        }
        serviceCert = (X509Certificate) keystore.getCertificate("1");
        pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(serviceCert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    @Test
    public void parsesBase64DerHeader() throws Exception {
        String header = Base64.getEncoder().encodeToString(serviceCert.getEncoded());
        assertEquals(serviceCert, MrnCertificateFilter.parseCertificate(header));
    }

    @Test
    public void parsesPemHeader() throws Exception {
        assertEquals(serviceCert, MrnCertificateFilter.parseCertificate(pem));
    }

    @Test
    public void parsesUrlEncodedPemHeader() throws Exception {
        // nginx forwards $ssl_client_escaped_cert = the URL-encoded PEM
        String header = URLEncoder.encode(pem, StandardCharsets.UTF_8);
        assertEquals(serviceCert, MrnCertificateFilter.parseCertificate(header));
    }

    @Test
    public void garbageHeaderIsRejected() {
        assertThrows(Exception.class, () -> MrnCertificateFilter.parseCertificate("not-a-certificate!"));
    }

    @Test
    public void extractsTheMrnFromTheSubjectUid() {
        assertEquals("urn:mrn:mcp:service:mcc:test:instance:niord-test",
                CertificateRenewalUtils.subjectMrn(serviceCert));
    }

    // ------------------------------------------------------------------------------------------- //
    // The header is worth nothing unless the deployment says otherwise
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void aValidHeaderIsIgnoredUnlessTheDeploymentTrustsIt() {
        // Chain validation off: the header would authenticate on its own if it were read at all
        MrnCertificateFilter filter = new MrnCertificateFilter(null, null, false, false, false);
        ContainerRequestContext request = request("POST", "v2/subscription", pem);

        SecomNotAuthorisedException e = assertThrows(SecomNotAuthorisedException.class, () -> filter.filter(request));

        assertTrue(e.getMessage().contains("trust-cert-header"), e.getMessage());
        verify(request, never()).setProperty(Mockito.eq(MrnCertificateFilter.MRN_PROPERTY), Mockito.any());
    }

    @Test
    public void pingStaysOpenWhileTheHeaderIsNotTrusted() {
        MrnCertificateFilter filter = new MrnCertificateFilter(null, null, false, false, false);

        filter.filter(request("GET", "v2/ping", null));
        filter.filter(request("GET", "/v2/ping/", "forged"));
    }

    @Test
    public void aTrustedHeaderAuthenticatesThePeer() {
        SecomSubscriptionService subscriptions = Mockito.mock(SecomSubscriptionService.class);
        MrnCertificateFilter filter = new MrnCertificateFilter(null, subscriptions, true, false, false);
        ContainerRequestContext request = request("POST", "v2/subscription", pem);

        filter.filter(request);

        verify(request).setProperty(MrnCertificateFilter.MRN_PROPERTY, "urn:mrn:mcp:service:mcc:test:instance:niord-test");
        verify(subscriptions).touchNode("urn:mrn:mcp:service:mcc:test:instance:niord-test");
    }

    @Test
    public void aTrustedButMissingHeaderIsStillRejected() {
        MrnCertificateFilter filter = new MrnCertificateFilter(null, null, true, false, false);

        SecomNotAuthorisedException e = assertThrows(SecomNotAuthorisedException.class,
                () -> filter.filter(request("GET", "v2/capability", null)));
        assertTrue(e.getMessage().contains("missing"), e.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // The two checks are read at each request: a save applies to the next one
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void aSavedChainValidationSettingAppliesToTheNextRequest() throws Exception {
        SettingsService settingsService = Mockito.mock(SettingsService.class);
        when(settingsService.get(Mockito.any(Setting.class))).thenReturn(Boolean.FALSE);
        McpSecurityService pki = Mockito.mock(McpSecurityService.class);
        KeyStore emptyTrustStore = KeyStore.getInstance("PKCS12");
        emptyTrustStore.load(null, null);
        when(pki.trustStore()).thenReturn(emptyTrustStore);
        MrnCertificateFilter filter = new SecomConfiguration().mrnCertificateFilter(pki,
                Mockito.mock(SecomSubscriptionService.class), settingsService, true);

        // Not validated: a certificate no truststore entry issued authenticates
        filter.filter(request("POST", "v2/subscription", pem));

        when(settingsService.get(Mockito.any(Setting.class))).thenReturn(Boolean.TRUE);

        SecomNotAuthorisedException e = assertThrows(SecomNotAuthorisedException.class,
                () -> filter.filter(request("POST", "v2/subscription", pem)));
        assertTrue(e.getMessage().contains("not issued by a trusted MCP CA"), e.getMessage());
    }

    @Test
    public void theRevocationCheckSettingIsReadAtEachValidatedRequest() throws Exception {
        McpSecurityService pki = Mockito.mock(McpSecurityService.class);
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = MrnCertificateFilterTest.class.getResourceAsStream("/secom-test/truststore-test.p12")) {
            trustStore.load(in, "changeit".toCharArray());
        }
        when(pki.trustStore()).thenReturn(trustStore);
        AtomicInteger asked = new AtomicInteger();
        MrnCertificateFilter filter = new MrnCertificateFilter(pki, Mockito.mock(SecomSubscriptionService.class), true,
                () -> true, () -> asked.incrementAndGet() < 0);

        filter.filter(request("POST", "v2/subscription", pem));
        filter.filter(request("POST", "v2/subscription", pem));

        assertEquals(2, asked.get(), "asked once per request, not once when the filter was made");
    }

    static ContainerRequestContext request(String method, String path, String certHeader) {
        ContainerRequestContext request = Mockito.mock(ContainerRequestContext.class);
        UriInfo uriInfo = Mockito.mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        when(request.getUriInfo()).thenReturn(uriInfo);
        when(request.getMethod()).thenReturn(method);
        when(request.getHeaderString(MrnCertificateFilter.CERT_HEADER)).thenReturn(certHeader);
        return request;
    }
}
