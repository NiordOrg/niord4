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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.grad.secomv2.core.models.EnvelopeGetFilterObject;
import org.grad.secomv2.core.models.enums.DigitalSignatureAlgorithmEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Pins the two interop accommodations {@link NiordSecomProviders#validateSignature} makes for
 * incoming signatures, both diagnosed against live peer traffic (AMSA's
 * {@code s124-secom-client}, 2026-09-01):
 * <ul>
 *   <li>the declared algorithm plus either 384-bit ECDSA variant is accepted — the filter
 *   envelopes cannot declare an algorithm, so the filter substitutes our own default, and
 *   S-100 Part 15 peers sign ECDSA-384-SHA2 while the GRAD reference stack signs SHA3-384;</li>
 *   <li>an envelope CSV is accepted without the CD3-deprecated trailing
 *   {@code envelopeSignatureReference} field the GRAD rendering still appends — but a data
 *   payload gets no such leeway, and the diagnostic rendering sweep never accepts.</li>
 * </ul>
 */
class NiordSecomProvidersSignatureTest {

    private static final byte[] CONTENT = "dataReference.0.S-124.2.0.0".getBytes(StandardCharsets.UTF_8);

    private static KeyPair keyPair;

    private static String[] certificatePem;

    private static NiordSecomProviders providers;

    @BeforeAll
    static void createSigner() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        keyPair = generator.generateKeyPair();

        X500Principal subject = new X500Principal("CN=signature interop test");
        Instant now = Instant.now();
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                subject, BigInteger.ONE, Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(1))), subject, keyPair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA384withECDSA").build(keyPair.getPrivate())));

        certificatePem = new String[] { Base64.getEncoder().encodeToString(certificate.getEncoded()) };

        // validateSignature never touches the PKI service - only signing does - so a stub keeps the test off disk
        providers = new NiordSecomProviders(mock(McpSecurityService.class), DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA);
    }

    /** {@return a signature over the given bytes produced with the given JCA algorithm} */
    private static byte[] sign(String jcaAlgorithm, byte[] content) throws Exception {
        Signature signature = Signature.getInstance(jcaAlgorithm);
        signature.initSign(keyPair.getPrivate());
        signature.update(content);
        return signature.sign();
    }

    // ------------------------------------------------------------------------------------------- //
    // Algorithm acceptance
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aSignatureIsVerifiedWithTheAlgorithmItsSenderDeclared() throws Exception {
        // What AMSA sends: ECDSA-384-SHA2, the algorithm S-100 Part 15 mandates
        byte[] signature = sign("SHA384withECDSA", CONTENT);

        assertTrue(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA,
                signature, CONTENT));
    }

    @Test
    void aSha2SignatureVerifiesEvenUnderTheFiltersSha3Fallback() throws Exception {
        // The regression this guards: the filter substitutes the node's own algorithm for
        // envelopes that cannot declare one, and verifying with only that rejected AMSA's
        // good SHA-2 signatures on /v2/object/search
        byte[] signature = sign("SHA384withECDSA", CONTENT);

        assertTrue(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA,
                signature, CONTENT));
    }

    @Test
    void algorithmsOutsideThe384FamilyAreNotBlindlyTried() throws Exception {
        // The accepted set is declared + the two 384-bit algorithms, nothing wider
        byte[] signature = sign("SHA256withECDSA", CONTENT);

        assertFalse(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA,
                signature, CONTENT));
    }

    @Test
    void aDeclared256AlgorithmIsStillHonoured() throws Exception {
        // A sender that genuinely declares something outside the 384 family still verifies under its declaration
        byte[] signature = sign("SHA256withECDSA", CONTENT);

        assertTrue(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA2_256_WITH_ECDSA,
                signature, CONTENT));
    }

    @Test
    void aSenderThatDeclaresNothingFallsBackToTheConfiguredAlgorithm() throws Exception {
        byte[] signature = sign("SHA3-384withECDSA", CONTENT);

        assertTrue(providers.validateSignature(certificatePem, null, signature, CONTENT));
        assertTrue(providers.validateSignature(certificatePem, signature, CONTENT));
    }

    @Test
    void aTamperedPayloadIsStillRejected() throws Exception {
        byte[] signature = sign("SHA384withECDSA", CONTENT);

        assertFalse(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA,
                signature, "dataReference.0.S-124.2.0.1".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void nothingPresentedIsNotAValidSignature() {
        assertFalse(providers.validateSignature(null, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA,
                new byte[] { 1 }, CONTENT));
        assertFalse(providers.validateSignature(new String[0], DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA,
                new byte[] { 1 }, CONTENT));
        assertFalse(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA,
                null, CONTENT));
        assertFalse(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA,
                new byte[] { 1 }, null));
    }

    // ------------------------------------------------------------------------------------------- //
    // CSV rendering acceptance
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aSignatureOmittingTheDeprecatedTrailingFieldVerifies() throws Exception {
        // The AMSA case: SHA-2-384 over a CSV whose envelope model post-dates the CD3
        // deprecation of envelopeSignatureReference, so it lacks the trailing empty column the
        // GRAD library still renders. A real envelope CSV always embeds the presented
        // certificate chain - the filter builds both from the same envelope field.
        String csvAsGradRendersIt = ".1.S-124......1.50." + Arrays.toString(certificatePem) + ".AB12CD.1756713600.";
        byte[] signature = sign("SHA384withECDSA",
                csvAsGradRendersIt.substring(0, csvAsGradRendersIt.length() - 1).getBytes(StandardCharsets.UTF_8));

        assertTrue(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA,
                signature, csvAsGradRendersIt.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theRealLibraryRenderingOfAGetFilterEnvelopeGetsTheTrailingFieldLeeway() throws Exception {
        // Same property proven against the library's actual CsvStringGenerator output rather
        // than a hand-built CSV: the envelope CSV ends with the empty envelopeSignatureReference
        // column and embeds the certificate array, so the discriminator recognises it
        EnvelopeGetFilterObject envelope = new EnvelopeGetFilterObject();
        envelope.setDataReference(UUID.fromString("2f2ea106-c433-49ab-8b3f-e69e01d44694"));
        envelope.setProductVersion("2.0.0");
        envelope.setEnvelopeSignatureCertificate(certificatePem);
        envelope.setEnvelopeRootCertificateThumbprint("AB12CD");
        envelope.setEnvelopeSignatureTime(Instant.ofEpochSecond(1756713600));

        String csv = envelope.getCsvString();
        assertTrue(csv.endsWith("."), "the GRAD rendering must still append the deprecated trailing field");
        assertTrue(csv.contains(Arrays.toString(certificatePem)), "the envelope CSV must embed the certificate chain");

        byte[] signedWithoutTrailingField = sign("SHA384withECDSA",
                csv.substring(0, csv.length() - 1).getBytes(StandardCharsets.UTF_8));

        assertTrue(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA,
                signedWithoutTrailingField, csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aModifiedDataPayloadDoesNotVerifyViaTheTrailingByteFallback() throws Exception {
        // The trailing-field leeway is for envelope CSVs only. This same method verifies data
        // signatures over raw payload bytes, and there a validly signed payload with one byte
        // appended must NOT verify - accepting it would authenticate a modified payload.
        // Payloads are recognised by not embedding the certificate chain.
        byte[] payload = "a perfectly ordinary signed payload that happens to end with a dot".getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign("SHA384withECDSA", payload);
        byte[] tampered = (new String(payload, StandardCharsets.UTF_8) + ".").getBytes(StandardCharsets.UTF_8);

        assertFalse(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA,
                signature, tampered));
    }

    // ------------------------------------------------------------------------------------------- //
    // Failure diagnostics
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aSignatureOverACsvRenderingVariantIsDiagnosticOnlyAndStillRejected() throws Exception {
        // The failure log reports when a signature would verify over an alternative CSV
        // rendering - here the certificate array without Arrays.toString brackets - but
        // reporting must never become accepting
        String contentWithBrackets = Arrays.toString(certificatePem) + ".AB12CD.1756713600.";
        byte[] signature = sign("SHA384withECDSA",
                (certificatePem[0] + ".AB12CD.1756713600.").getBytes(StandardCharsets.UTF_8));

        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            assertFalse(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA,
                    signature, contentWithBrackets.getBytes(StandardCharsets.UTF_8)));

            assertTrue(appender.list.stream().anyMatch(event ->
                            event.getFormattedMessage().contains("DIAGNOSTIC ONLY")
                                    && event.getFormattedMessage().contains("without brackets")),
                    "the log must report the rendering variant the signature would have verified under");
        } finally {
            detachLogAppender(appender);
        }
    }

    @Test
    void aFailedDataSignatureDoesNotLeakThePayloadIntoTheLog() throws Exception {
        // The content of a failed data signature is a decoded - possibly decrypted - payload.
        // The failure log identifies it by length and digest; the payload bytes themselves
        // must never appear.
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            byte[] signature = sign("SHA384withECDSA", CONTENT);
            assertFalse(providers.validateSignature(certificatePem, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA,
                    signature, "TOP-SECRET-DECRYPTED-PAYLOAD.".getBytes(StandardCharsets.UTF_8)));

            assertNotEquals(0, appender.list.size(), "the failure must be logged");
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("TOP-SECRET")),
                    "the payload bytes must not appear in the log");
        } finally {
            detachLogAppender(appender);
        }
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(NiordSecomProviders.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(NiordSecomProviders.class);
        logger.detachAppender(appender);
    }
}
