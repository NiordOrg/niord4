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

import org.grad.secomv2.core.base.DigitalSignatureCertificate;
import org.grad.secomv2.core.base.SecomCertificateProvider;
import org.grad.secomv2.core.base.SecomSignatureProvider;
import org.grad.secomv2.core.base.SecomTrustStoreProvider;
import org.grad.secomv2.core.models.enums.DigitalSignatureAlgorithmEnum;
import org.grad.secomv2.core.utils.SecomPemUtils;
import org.niord.secom.util.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * SECOMLib provider implementations backed by {@link McpSecurityService}. Plugged into the
 * SECOM writer interceptor / signature filter (server side) and the outbound sender, they
 * make all SECOM payload + envelope signatures use the MCP service certificate.
 * <p>
 * The outgoing signature algorithm is configurable (the {@code secomSignatureAlgorithm}
 * setting, read at each signature) and defaults to the S-124 service design mandate (v0.995 §3.3.3),
 * ECDSA-384-SHA2; legacy peers on the GRAD reference stack use SHA3-384.
 * <p>
 * Incoming signatures are accepted with two accommodations, both diagnosed against live
 * peer traffic (AMSA's {@code s124-secom-client}, 2026-09-01):
 * <ul>
 *   <li><b>Algorithm.</b> The declared algorithm plus either 384-bit ECDSA variant. The
 *   algorithm the filter hands us cannot be trusted to be the sender's: only
 *   exchange-metadata-bearing envelopes can declare one, the filter envelopes
 *   ({@code EnvelopeGetFilterObject}, {@code EnvelopeAckObject}, ...) have no field for it,
 *   so {@code SecomSignatureFilter} silently substitutes our own default. S-100 Part 15
 *   mandates ECDSA-384-SHA2 while the GRAD reference stack signs SHA3-384, so both are in
 *   circulation and the only interoperable verification is to accept either. The signature
 *   must still verify against the presented certificate over the same bytes; which of two
 *   strong hashes it used grants nothing.</li>
 *   <li><b>Rendering.</b> An envelope CSV is accepted in two renderings: exactly as the
 *   library computed it, and without the CD3-deprecated {@code envelopeSignatureReference}
 *   trailing field. The library's {@code CsvStringGenerator} still appends that field to
 *   every envelope CSV, but it is {@code @JsonIgnore} so an incoming request can never
 *   populate it — it is always a trailing empty column that exists only in the GRAD
 *   rendering, and a sender whose envelope model post-dates the deprecation signs the same
 *   values without it. Dropping a single trailing separator from otherwise-identical bytes
 *   authenticates the same envelope fields. Data signatures — the filter routes those
 *   through the same method — get no such leeway: their content must match the signed bytes
 *   exactly, since anything else would authenticate a modified payload.</li>
 * </ul>
 * When nothing verifies, the exact CSV that was checked is logged, plus any
 * algorithm/rendering combination that <em>would</em> have verified — diagnostic only, never
 * accepted. What this node <em>produces</em> takes no part in any of this: outgoing messages
 * are signed with the configured algorithm over the library's own rendering.
 */
public class NiordSecomProviders implements SecomCertificateProvider, SecomSignatureProvider, SecomTrustStoreProvider {

    private static final Logger log = LoggerFactory.getLogger(NiordSecomProviders.class);

    private final McpSecurityService pki;
    private final Supplier<DigitalSignatureAlgorithmEnum> signatureAlgorithm;

    /** Providers that sign with the given algorithm */
    public NiordSecomProviders(McpSecurityService pki, DigitalSignatureAlgorithmEnum signatureAlgorithm) {
        this(pki, () -> signatureAlgorithm);
    }

    /**
     * Providers that ask for the algorithm at each signature, so a saved {@code secomSignatureAlgorithm} applies to
     * the next outgoing message
     */
    public NiordSecomProviders(McpSecurityService pki, Supplier<DigitalSignatureAlgorithmEnum> signatureAlgorithm) {
        this.pki = pki;
        this.signatureAlgorithm = signatureAlgorithm;
    }

    /** {@inheritDoc} */
    @Override
    public DigitalSignatureCertificate getDigitalSignatureCertificate() {
        DigitalSignatureCertificate cert = new DigitalSignatureCertificate();
        cert.setCertificateAlias(new String[] { pki.keystoreAlias() });
        cert.setCertificate(new X509Certificate[] { pki.serviceCertificate() });
        cert.setPublicKey(pki.serviceCertificate().getPublicKey());
        cert.setRootCertificate(pki.rootCertificate());
        return cert;
    }

    /** {@inheritDoc} */
    @Override
    public DigitalSignatureAlgorithmEnum getSignatureAlgorithm() {
        return signatureAlgorithm.get();
    }

    /** {@inheritDoc} */
    @Override
    public byte[] generateSignature(DigitalSignatureCertificate signatureCertificate, byte[] payload) {
        try {
            return pki.sign(getSignatureAlgorithm().getValue(), payload);
        } catch (Exception e) {
            log.error("Failed to sign outgoing SECOM message", e);
            throw new SecurityException(e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * Verifies with the declared algorithm and, failing that, with either 384-bit ECDSA
     * algorithm, over both accepted CSV renderings — see the class javadoc for why each
     * accommodation exists and why neither weakens what a signature authenticates.
     */
    @Override
    public boolean validateSignature(String[] signatureCertificates, DigitalSignatureAlgorithmEnum algorithm,
                                     byte[] signature, byte[] content) {
        if (signatureCertificates == null || signatureCertificates.length == 0 || signature == null || content == null) {
            return false;
        }
        DigitalSignatureAlgorithmEnum declared = algorithm != null ? algorithm : getSignatureAlgorithm();

        // The first certificate in the chain is the signer
        X509Certificate cert;
        try {
            cert = SecomPemUtils.getCertFromPem(signatureCertificates[0]);
        } catch (Exception e) {
            log.warn("Failed to read the certificate of an incoming SECOM message: {}", e.getMessage());
            return false;
        }

        Set<DigitalSignatureAlgorithmEnum> accepted = new LinkedHashSet<>();
        accepted.add(declared);
        accepted.add(DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA);
        accepted.add(DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA);

        Map<String, byte[]> renderings = new LinkedHashMap<>();
        renderings.put("the CSV as this library renders it", content);
        // The trailing-field rendering is for envelope CSVs only. This same method also verifies
        // data signatures over raw payload bytes (the filter's second call), where accepting
        // content that differs from the signed bytes - even by one appended byte - would
        // authenticate a modified payload. An envelope CSV provably embeds the presented
        // certificate chain in the library's Arrays.toString rendering (the filter builds both
        // arguments from the same envelope field), so its presence distinguishes the two; a
        // payload only contains that rendering if its signer deliberately put it there.
        if (content.length > 0 && content[content.length - 1] == '.'
                && new String(content, StandardCharsets.UTF_8).contains(Arrays.toString(signatureCertificates))) {
            renderings.put("the CSV without the deprecated trailing envelopeSignatureReference field",
                    Arrays.copyOf(content, content.length - 1));
        }

        for (Map.Entry<String, byte[]> rendering : renderings.entrySet()) {
            for (DigitalSignatureAlgorithmEnum candidate : accepted) {
                if (verifies(cert, candidate, signature, rendering.getValue())) {
                    if (candidate != declared || rendering.getValue() != content) {
                        log.info("A SECOM signature from {} verified with {} over {}, not the {} over the library rendering the filter assumed",
                                cert.getSubjectX500Principal(), candidate.getValue(), rendering.getKey(), declared.getValue());
                    }
                    return true;
                }
            }
        }

        logVerificationFailure(cert, signatureCertificates, signature, content, accepted);
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * secom-v2 0.1.0 made the algorithm-carrying overload a default method that delegates here,
     * so this is the fallback for a sender that declared no algorithm at all.
     */
    @Override
    public boolean validateSignature(String[] signatureCertificates, byte[] signature, byte[] content) {
        return validateSignature(signatureCertificates, getSignatureAlgorithm(), signature, content);
    }

    /**
     * Logs everything needed to diagnose a signature that did not verify. For an envelope
     * signature that is the exact CSV we computed — which is the half of the comparison we own;
     * the other half is whatever the sender actually signed — and any algorithm/CSV rendering
     * under which the signature <em>would</em> have verified. The renderings cover the
     * Java-specific choices in the library's {@code CsvStringGenerator} that another
     * implementation would plausibly make differently: {@code Arrays.toString} putting
     * {@code [...]} around the certificate array, and the CD3-deprecated
     * {@code envelopeSignatureReference} contributing a trailing empty field. Those variants are
     * log-only; they never make a signature acceptable.
     * <p>
     * A failed <em>data</em> signature — recognised, as in {@code validateSignature}, by the
     * content not embedding the presented certificate chain — is a decoded, possibly decrypted,
     * payload that must not end up in the log; it is reported by length and digest only, and the
     * CSV rendering sweep does not apply to it.
     */
    private void logVerificationFailure(X509Certificate cert, String[] signatureCertificates, byte[] signature,
                                        byte[] content, Set<DigitalSignatureAlgorithmEnum> accepted) {
        String csv = new String(content, StandardCharsets.UTF_8);
        var algorithms = accepted.stream().map(DigitalSignatureAlgorithmEnum::getValue).toList();
        if (!csv.contains(Arrays.toString(signatureCertificates))) {
            log.warn("A SECOM data signature from {} over {} bytes (SHA-256 {}) did not verify with any of {}",
                    cert.getSubjectX500Principal(), content.length, sha256Hex(content), algorithms);
            return;
        }
        log.warn("A SECOM signature from {} did not verify with any of {}. The CSV this server computed and checked it against was\n>>>{}<<<",
                cert.getSubjectX500Principal(), algorithms, csv);

        Map<String, String> variants = new LinkedHashMap<>();
        variants.put("the CSV as computed", csv);
        if (csv.endsWith(".")) {
            variants.put("the CSV without the deprecated trailing envelopeSignatureReference field",
                    csv.substring(0, csv.length() - 1));
        }
        if (csv.contains("[")) {
            String stripped = csv.replace("[", "").replace("]", "");
            variants.put("the CSV without brackets around the certificate array", stripped);
            if (stripped.endsWith(".")) {
                variants.put("the CSV without brackets and without the trailing field",
                        stripped.substring(0, stripped.length() - 1));
            }
        }
        for (Map.Entry<String, String> variant : variants.entrySet()) {
            for (DigitalSignatureAlgorithmEnum candidate : DigitalSignatureAlgorithmEnum.values()) {
                // The DSA and CVC-ECDSA algorithm names are not resolvable in a standard JCA, and no MCP peer uses them
                if (candidate == DigitalSignatureAlgorithmEnum.DSA || candidate == DigitalSignatureAlgorithmEnum.CVC_ECDSA) {
                    continue;
                }
                if (verifies(cert, candidate, signature, variant.getValue().getBytes(StandardCharsets.UTF_8))) {
                    log.warn("DIAGNOSTIC ONLY: the failed SECOM signature from {} WOULD verify with {} over {}",
                            cert.getSubjectX500Principal(), candidate.getValue(), variant.getKey());
                }
            }
        }
    }

    /** Verifies the signature over the content with the given algorithm; false on any failure */
    private static boolean verifies(X509Certificate cert, DigitalSignatureAlgorithmEnum algorithm,
                                    byte[] signature, byte[] content) {
        try {
            Signature verification = Signature.getInstance(algorithm.getValue());
            verification.initVerify(cert);
            verification.update(content);
            return verification.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /** {@return the SHA-256 digest of the given bytes as hex, identifying a payload without disclosing it} */
    private static String sha256Hex(byte[] bytes) {
        try {
            return Sha256.hex(bytes);
        } catch (Exception e) {
            return "unavailable";
        }
    }

    /** {@inheritDoc} */
    @Override
    public KeyStore getTrustStore() {
        return pki.trustStore();
    }

    /**
     * {@inheritDoc}
     *
     * Must match the actual truststore alias - the interface default ("root") makes the
     * SECOM signature filter fail its root-thumbprint check on every signed request.
     */
    @Override
    public String getCARootCertificateAlias() {
        return pki.trustStoreRootAlias();
    }
}
