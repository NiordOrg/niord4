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
package org.niord.secom.pki;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.time.Duration;

/**
 * {@link McpMirClient} against the reference MIR REST API:
 * {@code POST {mir-url}/x509/api/org/{orgMrn}/service/{serviceMrn}/certificate/issue-new/csr}
 * (and the {@code /user/} twin), body = PEM PKCS#10 CSR, response = PEM certificate chain.
 * The call is authenticated with mTLS using the dedicated renewal <em>robot-user</em>
 * credential - the MIR grants a service's own certificate no roles, so a service cannot renew
 * itself (identity plan §4.4); the robot user's permissions map to {@code SERVICE_ADMIN}.
 * <p>
 * The robot store is read freshly per call, so a just-renewed robot credential is presented
 * immediately. The MIR API endpoint serves a publicly trusted TLS certificate, so the server
 * is validated against the JDK default trust anchors, NOT the SECOM truststore. Plain
 * {@code http:} URLs skip TLS entirely (the IT stub path, mirroring SecomOutboundClient).
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
public class McpMirRestClient implements McpMirClient {

    private static final Logger log = LoggerFactory.getLogger(McpMirRestClient.class);

    @Value("${niord.secom.renewal.mir-url:}")
    String mirUrl;

    @Value("${niord.secom.renewal.keystore.location:}")
    String robotKeystoreLocation;

    @Value("${niord.secom.renewal.keystore.password:}")
    String robotKeystorePassword;

    @Value("${niord.secom.renewal.keystore.alias:1}")
    String robotKeystoreAlias;

    @Value("${niord.secom.renewal.timeout-seconds:30}")
    int timeoutSeconds;

    private final McpSecurityService pki;

    public McpMirRestClient(McpSecurityService pki) {
        this.pki = pki;
    }

    /** {@inheritDoc} */
    @Override
    public String issueServiceCertificate(String orgMrn, String serviceMrn, String csrPem) throws Exception {
        return issue("/x509/api/org/" + orgMrn + "/service/" + serviceMrn + "/certificate/issue-new/csr", csrPem);
    }

    /** {@inheritDoc} */
    @Override
    public String issueUserCertificate(String orgMrn, String userMrn, String csrPem) throws Exception {
        return issue("/x509/api/org/" + orgMrn + "/user/" + userMrn + "/certificate/issue-new/csr", csrPem);
    }

    private String issue(String path, String csrPem) throws Exception {
        String base = StringUtils.removeEnd(mirUrl, "/");
        String url = base + path;
        HttpClient client = url.startsWith("https:")
                ? HttpClient.newBuilder().sslContext(robotSslContext()).build()
                : HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "text/plain")
                .header("Accept", "application/pem-certificate-chain, text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(csrPem))
                .build();
        log.info("Requesting MIR certificate issuance: POST {}", url);
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("MIR POST " + url + " failed with HTTP " + response.statusCode()
                    + ": " + CertificateRenewalUtils.abbreviate(response.body()));
        }
        return response.body();
    }

    /** mTLS context presenting the robot credential at the configured alias; server trust = the JDK default anchors */
    private SSLContext robotSslContext() throws Exception {
        KeyStore robotStore = pki.loadExternalStore(robotKeystoreLocation, robotKeystorePassword);
        return mirSslContext(robotStore, robotKeystoreAlias, robotKeystorePassword.toCharArray());
    }

    static SSLContext mirSslContext(KeyStore robotStore, String alias, char[] password) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(singleEntry(robotStore, alias, password), password);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);   // the JDK default trust anchors
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    /**
     * Reduces the robot store to ONLY the configured alias entry: the default key manager
     * picks an arbitrary suitable entry from a multi-entry store, which could present a
     * different identity than the alias the renewal service validated and derived the
     * robot MRN from.
     */
    static KeyStore singleEntry(KeyStore robotStore, String alias, char[] password) throws Exception {
        Key key = robotStore.getKey(alias, password);
        Certificate[] chain = robotStore.getCertificateChain(alias);
        if (!(key instanceof PrivateKey privateKey) || chain == null || chain.length == 0) {
            throw new IllegalStateException("No private-key entry at alias '" + alias
                    + "' in the renewal robot keystore");
        }
        KeyStore single = KeyStore.getInstance("PKCS12");
        single.load(null, null);
        single.setKeyEntry(alias, privateKey, password, chain);
        return single;
    }

}
