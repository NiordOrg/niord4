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

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.grad.secomv2.core.exceptions.SecomNotAuthorisedException;
import org.niord.secom.pki.CertificateRenewalUtils;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.service.SecomSubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Authenticates every SECOM request from the remote node's MCP certificate: this filter
 * parses it, validates it against the MCP truststore including a soft-fail OCSP/CRL
 * revocation check (service design §3.3.1), extracts the node MRN from the subject
 * {@code UID} attribute, and exposes it as the {@link #MRN_PROPERTY} request property.
 * Requests without a valid certificate are rejected.
 * <p>
 * Where the certificate comes from: today the {@code X-Secom-Cert} header, which the
 * chart's former nginx TLS listener set from the handshake. SECOM Edition 2 (IEC CDV
 * 63173-2, clause 4.3.3) dropped the TLS client certificate: the certificate travels in
 * the request's signed envelope (Table 5), and the chart's proxy no longer sets the
 * header. The header is therefore only trusted when the deployment says so
 * ({@code niord.secom.trust-cert-header=true}: a TLS-terminating proxy that verifies the
 * client certificate and sets the header, and nothing else reaching the app); with the
 * default {@code false} the header is ignored whoever sent it, and every request but ping
 * is refused until this filter reads the envelope (docs/s124-secom-spec-gap-analysis.md
 * §7). The guard is in the app on purpose: a proxy that strips the header protects only
 * the paths that pass through it.
 * <p>
 * Exception: {@code GET /v2/ping} is served without a certificate — the spec (§4.1.7) wants
 * a status check "without sending a valid message", and registries typically ping
 * unauthenticated. The ping response carries no per-node data.
 * <p>
 * Accepted header encodings: base64 DER (Caddy), PEM, and URL-encoded PEM
 * (nginx {@code $ssl_client_escaped_cert}).
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class MrnCertificateFilter implements ContainerRequestFilter {

    public static final String CERT_HEADER = "X-Secom-Cert";

    /** Request property (= servlet request attribute) holding the authenticated MRN */
    public static final String MRN_PROPERTY = "niord.secom.mrn";

    private static final Logger log = LoggerFactory.getLogger(MrnCertificateFilter.class);

    private final McpSecurityService pki;
    private final SecomSubscriptionService subscriptionService;
    private final boolean trustCertHeader;
    private final BooleanSupplier validateClientChain;
    private final BooleanSupplier revocationCheck;

    /** A filter whose chain validation and revocation check are fixed */
    public MrnCertificateFilter(McpSecurityService pki, SecomSubscriptionService subscriptionService,
                                boolean trustCertHeader, boolean validateClientChain, boolean revocationCheck) {
        this(pki, subscriptionService, trustCertHeader, () -> validateClientChain, () -> revocationCheck);
    }

    /**
     * A filter that asks at each request whether to validate the chain and check for revocation (the
     * {@code secomValidateClientChain} and {@code secomRevocationCheck} settings), so a save applies to the next
     * request
     */
    public MrnCertificateFilter(McpSecurityService pki, SecomSubscriptionService subscriptionService,
                                boolean trustCertHeader, BooleanSupplier validateClientChain,
                                BooleanSupplier revocationCheck) {
        this.pki = pki;
        this.subscriptionService = subscriptionService;
        this.trustCertHeader = trustCertHeader;
        this.validateClientChain = validateClientChain;
        this.revocationCheck = revocationCheck;
    }

    /** {@inheritDoc} */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        // An untrusted header is not read at all: whatever it holds, the request carries no identity
        String header = trustCertHeader ? requestContext.getHeaderString(CERT_HEADER) : null;
        if (header == null || header.isBlank()) {
            if (isUnauthenticatedPing(requestContext)) {
                return;
            }
            throw new SecomNotAuthorisedException(trustCertHeader
                    ? "A client certificate is required (missing " + CERT_HEADER + " header)"
                    : "Peer identity cannot be established: the " + CERT_HEADER + " header is not trusted in "
                    + "this deployment (niord.secom.trust-cert-header), and the signed envelope is not read yet");
        }

        X509Certificate certificate;
        try {
            certificate = parseCertificate(header);
        } catch (Exception e) {
            throw new SecomNotAuthorisedException("Could not parse client certificate: " + e.getMessage());
        }

        if (validateClientChain.getAsBoolean()) {
            validateAgainstTrustStore(certificate);
        }

        String mrn = CertificateRenewalUtils.subjectMrn(certificate);
        if (mrn == null || mrn.isBlank()) {
            throw new SecomNotAuthorisedException("Client certificate carries no UID (MRN) attribute");
        }

        requestContext.setProperty(MRN_PROPERTY, mrn);

        // Record the interaction (feeds /v2/ping); never fail the request over it
        try {
            subscriptionService.touchNode(mrn);
        } catch (Exception e) {
            log.warn("Failed to record SECOM interaction for {}: {}", mrn, e.getMessage());
        }
    }

    /** Returns true for {@code GET /v2/ping} — the only SECOM endpoint served without a certificate */
    private static boolean isUnauthenticatedPing(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        return "GET".equals(requestContext.getMethod())
                && ("v2/ping".equals(path) || "/v2/ping".equals(path) || "v2/ping/".equals(path) || "/v2/ping/".equals(path));
    }

    /** Parses the certificate header: URL-encoded PEM, plain PEM, or base64 DER */
    static X509Certificate parseCertificate(String header) throws Exception {
        String value = header.trim();
        if (value.contains("%")) {
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        // Strip PEM armour if present
        value = value.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(value);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
    }

    /**
     * Validates the client certificate: within its validity window, and issued by one of the
     * CA certificates in the MCP truststore. NB: only the leaf is checked, and its signature
     * directly against each truststore entry - no chain is walked - so the truststore must
     * contain the issuing CA(s), not just the MCP root.
     * <p>
     * When revocation checking is enabled, the certificate is additionally checked against
     * the OCSP responder (falling back to CRLs) referenced from the certificate itself —
     * soft-fail, so an unreachable responder does not reject the peer, only a positive
     * revocation does (service design §3.3.1).
     */
    private void validateAgainstTrustStore(X509Certificate certificate) {
        try {
            certificate.checkValidity();
        } catch (Exception e) {
            throw new SecomNotAuthorisedException("Client certificate is not valid: " + e.getMessage());
        }

        boolean trusted = false;
        try {
            KeyStore trustStore = pki.trustStore();
            Enumeration<String> aliases = trustStore.aliases();
            while (aliases.hasMoreElements() && !trusted) {
                if (trustStore.getCertificate(aliases.nextElement()) instanceof X509Certificate ca) {
                    try {
                        certificate.verify(ca.getPublicKey());
                        trusted = true;
                    } catch (Exception ignored) {
                        // Not issued by this CA - try the next one
                    }
                }
            }
        } catch (Exception e) {
            throw new SecomNotAuthorisedException("Client certificate validation failed: " + e.getMessage());
        }
        if (!trusted) {
            throw new SecomNotAuthorisedException("Client certificate is not issued by a trusted MCP CA");
        }

        if (revocationCheck.getAsBoolean()) {
            checkRevocation(certificate);
        }
    }

    /** PKIX-validates the leaf certificate with a soft-fail OCSP/CRL revocation checker */
    private void checkRevocation(X509Certificate certificate) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            CertPath path = factory.generateCertPath(List.of(certificate));

            CertPathValidator validator = CertPathValidator.getInstance("PKIX");
            PKIXRevocationChecker checker = (PKIXRevocationChecker) validator.getRevocationChecker();
            // Soft-fail: network/availability problems reaching the OCSP responder or CRL
            // distribution point do not reject the peer - only a positive revocation does
            checker.setOptions(EnumSet.of(PKIXRevocationChecker.Option.SOFT_FAIL));

            PKIXParameters params = new PKIXParameters(pki.trustStore());
            params.addCertPathChecker(checker);
            params.setRevocationEnabled(true);

            validator.validate(path, params);
        } catch (SecomNotAuthorisedException e) {
            throw e;
        } catch (java.security.cert.CertPathValidatorException e) {
            throw new SecomNotAuthorisedException("Client certificate rejected by revocation check: " + e.getMessage());
        } catch (Exception e) {
            // Setup problems (e.g. a truststore without trusted entries) must not turn into
            // an authentication bypass or a hard outage - log and continue, chain trust holds
            log.warn("SECOM revocation check could not run: {}", e.getMessage());
        }
    }
}
