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
import org.grad.secomv2.core.models.enums.DigitalSignatureAlgorithmEnum;
import org.grad.secomv2.core.utils.SecomPemUtils;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link McpSecurityService} (PKCS12 loading incl. the base64-tolerant path
 * used with k8s secrets, SHA3-384/ECDSA signing) and the {@link NiordSecomProviders} plugged
 * into SECOMLib, using the committed test PKI under {@code secom-test/}.
 */
public class McpSecurityServiceTest {

    static McpSecurityService service(String keystoreLocation) throws Exception {
        return service(keystoreLocation, "classpath:secom-test/truststore-test.p12");
    }

    static McpSecurityService service(String keystoreLocation, String truststoreLocation) throws Exception {
        McpSecurityService pki = new McpSecurityService(new DefaultResourceLoader());
        pki.keystoreLocation = keystoreLocation;
        pki.keystorePassword = "changeit";
        pki.keystoreAlias = "1";
        pki.truststoreLocation = truststoreLocation;
        pki.truststorePassword = "changeit";
        pki.truststoreRootAlias = "";
        pki.init();
        return pki;
    }

    /** Writes the given PKCS12 bytes to a temp file and returns its {@code file:} location */
    private static String stagedStore(byte[] bytes) throws Exception {
        Path file = Files.createTempFile("mcp-chain", ".p12");
        file.toFile().deleteOnExit();
        Files.write(file, bytes);
        return "file:" + file;
    }

    /** A keystore holding the identity's key with exactly the given certificates as its chain */
    private static String stagedKeystore(TestCertificateAuthority.Identity identity, List<X509Certificate> chain)
            throws Exception {
        return stagedStore(CertificateRenewalUtils.assembleKeystore("1", "changeit", identity.key(), chain));
    }

    // ------------------------------------------------------------------------------------------- //
    // Certification path (S-100 Part 15 clause 15-8.7: the exchange set carries the issuing chain)
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void certificationPathOfTheCommittedKeystoreRunsFromTheLeafToItsRoot() throws Exception {
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");

        List<X509Certificate> path = pki.serviceCertificateChain();

        // The PKI script packs the service key with its certificate and the root, nothing between
        assertEquals(2, path.size());
        assertEquals(pki.serviceCertificate(), path.get(0), "leaf first");
        assertEquals("CN=Niord Test MCP Root", path.get(1).getSubjectX500Principal().getName());
        assertTrue(McpSecurityService.isSelfSigned(path.get(1)));
        assertFalse(McpSecurityService.isSelfSigned(path.get(0)));
    }

    @Test
    public void certificationPathThroughAnIntermediateIsTakenFromTheKeystoreEntry() throws Exception {
        // The MIR issues the leaf with its issuing CA and the renewal job stores what was issued,
        // so the keystore entry itself carries the chain the exchange set must ship
        TestCertificateAuthority root = TestCertificateAuthority.create("Test MCP Root");
        TestCertificateAuthority intermediate = root.issueIntermediate("Test MCP Identity Registry");
        TestCertificateAuthority.Identity identity = intermediate.mintLeaf("urn:mrn:mcp:service:mcc:test:instance:chain",
                TestCertificateAuthority.daysFromNow(-1), TestCertificateAuthority.daysFromNow(365));

        McpSecurityService pki = service(stagedKeystore(identity, identity.chain()),
                "file:" + root.stagedTruststore("mcp-chain-trust"));

        assertEquals(List.of(identity.certificate(), intermediate.certificate(), root.certificate()),
                pki.serviceCertificateChain());
    }

    @Test
    public void certificationPathIsCompletedFromTheTruststoreWhenTheKeystoreHoldsTheLeafAlone() throws Exception {
        // A hand-installed keystore may carry the leaf only; the intermediate then has to come
        // from the truststore, which the SECOM filter needs it in anyway. Only self-signed entries
        // anchor the search, so the intermediate is walked through, not stopped at
        TestCertificateAuthority root = TestCertificateAuthority.create("Test MCP Root");
        TestCertificateAuthority intermediate = root.issueIntermediate("Test MCP Identity Registry");
        TestCertificateAuthority.Identity identity = intermediate.mintLeaf("urn:mrn:mcp:service:mcc:test:instance:leaf",
                TestCertificateAuthority.daysFromNow(-1), TestCertificateAuthority.daysFromNow(365));

        McpSecurityService pki = service(stagedKeystore(identity, List.of(identity.certificate())),
                "file:" + intermediate.stagedTruststore("mcp-leaf-only-trust"));

        assertEquals(List.of(identity.certificate(), intermediate.certificate(), root.certificate()),
                pki.serviceCertificateChain());
    }

    @Test
    public void certificationPathStaysAtTheLeafWhenNothingAnchorsIt() throws Exception {
        // Neither store knows the issuer: the path is the leaf, and loading still succeeds - the
        // leaf signs, and a consumer with the chain installed still validates
        TestCertificateAuthority root = TestCertificateAuthority.create("Unrelated Root");
        TestCertificateAuthority.Identity identity = root.issueIntermediate("Unrelated Intermediate")
                .mintLeaf("urn:mrn:mcp:service:mcc:test:instance:orphan",
                        TestCertificateAuthority.daysFromNow(-1), TestCertificateAuthority.daysFromNow(365));

        McpSecurityService pki = service(stagedKeystore(identity, List.of(identity.certificate())));

        assertEquals(List.of(identity.certificate()), pki.serviceCertificateChain());
        assertEquals(identity.certificate(), pki.serviceCertificate());
    }

    @Test
    public void loadsBinaryKeystoreAndResolvesCertificates() throws Exception {
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");

        assertTrue(pki.serviceCertificate().getSubjectX500Principal().getName()
                .contains("urn:mrn:mcp:service:mcc:test:instance:niord-test"));
        assertEquals("CN=Niord Test MCP Root", pki.rootCertificate().getSubjectX500Principal().getName());
        assertNotNull(pki.trustStore());
    }

    @Test
    public void loadsBase64EncodedKeystore() throws Exception {
        // k8s secrets often hold the store as base64 text - the loader must auto-detect it
        McpSecurityService pki = service("classpath:secom-test/keystore-test-base64.txt");
        assertTrue(pki.serviceCertificate().getSubjectX500Principal().getName()
                .contains("urn:mrn:mcp:service:mcc:test:instance:niord-test"));
    }

    @Test
    public void missingKeystoreFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> service("classpath:secom-test/no-such-store.p12"));
    }

    @Test
    public void signaturesVerifyAgainstTheServiceCertificate() throws Exception {
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");
        byte[] payload = "SECOM payload".getBytes(StandardCharsets.UTF_8);

        byte[] signature = pki.sign("SHA3-384withECDSA", payload);

        Signature verifier = Signature.getInstance("SHA3-384withECDSA");
        verifier.initVerify(pki.serviceCertificate().getPublicKey());
        verifier.update(payload);
        assertTrue(verifier.verify(signature), "signature must verify against the service certificate");
    }

    @Test
    public void providersSignAndValidateRoundTrip() throws Exception {
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");
        NiordSecomProviders providers = new NiordSecomProviders(pki, DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA);

        assertEquals(DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA, providers.getSignatureAlgorithm());

        DigitalSignatureCertificate certificate = providers.getDigitalSignatureCertificate();
        assertEquals(pki.serviceCertificate(), certificate.getCertificate()[0]);
        assertEquals(pki.rootCertificate(), certificate.getRootCertificate());

        byte[] payload = "roundtrip".getBytes(StandardCharsets.UTF_8);
        byte[] signature = providers.generateSignature(certificate, payload);

        String pem = SecomPemUtils.getMinifiedPemFromCert(pki.serviceCertificate());
        assertTrue(providers.validateSignature(new String[] { pem }, signature, payload));
        assertFalse(providers.validateSignature(new String[] { pem }, signature, "tampered".getBytes(StandardCharsets.UTF_8)),
                "a tampered payload must not validate");
        assertFalse(providers.validateSignature(null, signature, payload), "no certificate chain -> invalid");
    }

    @Test
    public void incomingSignaturesAreAcceptedInEither384BitVariant() throws Exception {
        // The peer fleet is split between the spec's ECDSA-384-SHA2 and legacy SHA3-384
        // while the switch rolls out; the provider SPI does not expose the declared
        // algorithm, so validation must accept both regardless of the configured one
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");
        NiordSecomProviders sha2Node = new NiordSecomProviders(pki, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA);
        NiordSecomProviders sha3Node = new NiordSecomProviders(pki, DigitalSignatureAlgorithmEnum.SHA3_384_WITH_ECDSA);
        String pem = SecomPemUtils.getMinifiedPemFromCert(pki.serviceCertificate());
        byte[] payload = "cross algorithm".getBytes(StandardCharsets.UTF_8);

        byte[] sha3Signature = sha3Node.generateSignature(sha3Node.getDigitalSignatureCertificate(), payload);
        assertTrue(sha2Node.validateSignature(new String[] { pem }, sha3Signature, payload),
                "a SHA2-configured node must accept a legacy SHA3-384 signature");

        byte[] sha2Signature = sha2Node.generateSignature(sha2Node.getDigitalSignatureCertificate(), payload);
        assertTrue(sha3Node.validateSignature(new String[] { pem }, sha2Signature, payload),
                "a SHA3-configured node must accept a spec SHA2-384 signature");

        assertFalse(sha2Node.validateSignature(new String[] { pem }, sha3Signature,
                        "tampered".getBytes(StandardCharsets.UTF_8)),
                "tampered content must fail both algorithm attempts");
    }

    /***************************************/
    /** Outbound TLS revocation checking  **/
    /***************************************/

    /** Loads the committed test keystore directly (the service keeps its copy private) */
    static KeyStore testKeystore() throws Exception {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = McpSecurityServiceTest.class.getResourceAsStream("/secom-test/keystore-test.p12")) {
            keystore.load(in, "changeit".toCharArray());
        }
        return keystore;
    }

    static X509Certificate[] serviceChain() throws Exception {
        return Arrays.stream(testKeystore().getCertificateChain("1"))
                .map(X509Certificate.class::cast)
                .toArray(X509Certificate[]::new);
    }

    static X509Certificate[] untrustedChain() throws Exception {
        try (InputStream in = McpSecurityServiceTest.class.getResourceAsStream("/secom-test/untrusted-client.pem")) {
            return new X509Certificate[] {
                    (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in)
            };
        }
    }

    @Test
    public void revocationParametersUseSoftFailOcspWithCrlFallback() throws Exception {
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");

        PKIXBuilderParameters params = McpSecurityService.revocationParameters(pki.trustStore());

        List<PKIXRevocationChecker> checkers = params.getCertPathCheckers().stream()
                .filter(PKIXRevocationChecker.class::isInstance)
                .map(PKIXRevocationChecker.class::cast)
                .toList();
        assertEquals(1, checkers.size(), "exactly one revocation checker is installed");
        // SOFT_FAIL only: no NO_FALLBACK (so OCSP falls back to CRLs) and no PREFER_CRLS
        assertEquals(EnumSet.of(PKIXRevocationChecker.Option.SOFT_FAIL), checkers.get(0).getOptions());
        assertTrue(params.isRevocationEnabled());
    }

    @Test
    public void outboundTrustManagerAcceptsTheMcpChainDespiteAnUnreachableOcspResponder() throws Exception {
        // The test certificates carry an OCSP AIA pointing at a closed port (see
        // generate-test-pki.sh), so every validation hits a live network failure - the
        // soft-fail configuration must tolerate it and trust the chain anyway
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");

        X509TrustManager tm = (X509TrustManager) McpSecurityService.serverTrustManager(pki.trustStore(), true);

        tm.checkServerTrusted(serviceChain(), "ECDHE_ECDSA");
    }

    @Test
    public void outboundTrustManagerRejectsUntrustedChains() throws Exception {
        // Revocation checking must never weaken chain trust - a certificate from a foreign
        // root is rejected with the checker both on and off
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");

        X509TrustManager checking = (X509TrustManager) McpSecurityService.serverTrustManager(pki.trustStore(), true);
        X509TrustManager plain = (X509TrustManager) McpSecurityService.serverTrustManager(pki.trustStore(), false);

        assertThrows(CertificateException.class, () -> checking.checkServerTrusted(untrustedChain(), "ECDHE_ECDSA"));
        assertThrows(CertificateException.class, () -> plain.checkServerTrusted(untrustedChain(), "ECDHE_ECDSA"));
    }

    @Test
    public void revocationSetupFailureFallsBackToChainOnlyValidation() throws Exception {
        // A truststore without trust anchors makes the PKIX revocation setup fail; that must
        // degrade to plain chain validation (log-and-proceed), which is still fail-secure -
        // NOT to trust-all, and not to a hard failure
        KeyStore empty = KeyStore.getInstance("PKCS12");
        empty.load(null, null);

        X509TrustManager tm = (X509TrustManager) McpSecurityService.serverTrustManager(empty, true);

        assertNotNull(tm, "setup failure must fall back, not throw");
        // The exact exception type is a JDK detail (a no-anchor PKIX manager throws a wrapped
        // RuntimeException rather than CertificateException) - rejecting is what matters
        assertThrows(Exception.class, () -> tm.checkServerTrusted(serviceChain(), "ECDHE_ECDSA"),
                "the fallback trust manager holds no anchors, so it must still reject");
    }

    @Test
    public void newHttpClientBuildsForAllFlagCombinations() throws Exception {
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");

        assertNotNull(pki.newHttpClient(true, true));
        assertNotNull(pki.newHttpClient(false, true));
        assertNotNull(pki.newHttpClient(false, false));
    }

    /***************************************/
    /** Keystore reload (rotation seam)   **/
    /***************************************/

    @Test
    public void reloadSwapsTheServiceIdentity() throws Exception {
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");
        String before = McpSecurityService.fingerprint(pki.serviceCertificate());

        // client-one is a second same-root identity committed with the test PKI - the
        // ready-made "rotated" keystore (same alias, same password)
        pki.keystoreLocation = "classpath:secom-test/client-one.p12";
        pki.reload();

        assertTrue(pki.serviceCertificate().getSubjectX500Principal().getName().contains("client-one"));
        assertNotEquals(before, McpSecurityService.fingerprint(pki.serviceCertificate()));

        // A fresh signature verifies against the NEW certificate - key and cert swap together
        byte[] payload = "post rotation".getBytes(StandardCharsets.UTF_8);
        byte[] signature = pki.sign("SHA3-384withECDSA", payload);
        Signature verifier = Signature.getInstance("SHA3-384withECDSA");
        verifier.initVerify(pki.serviceCertificate().getPublicKey());
        verifier.update(payload);
        assertTrue(verifier.verify(signature));

        // The truststore side is untouched by a keystore rotation
        assertEquals("CN=Niord Test MCP Root", pki.rootCertificate().getSubjectX500Principal().getName());
        assertNotNull(pki.trustStore());
    }

    @Test
    public void failedReloadKeepsTheCurrentMaterial() throws Exception {
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");
        String before = McpSecurityService.fingerprint(pki.serviceCertificate());

        pki.keystoreLocation = "classpath:secom-test/no-such-store.p12";
        assertThrows(IllegalArgumentException.class, pki::reload, "a missing store fails the reload");
        assertEquals(before, McpSecurityService.fingerprint(pki.serviceCertificate()), "the old material stays active");

        pki.keystoreLocation = "classpath:secom-test/keystore-test.p12";
        pki.keystorePassword = "wrong";
        assertThrows(Exception.class, pki::reload, "a bad password fails the reload");
        assertEquals(before, McpSecurityService.fingerprint(pki.serviceCertificate()), "the old material stays active");

        // ... and the retained key still signs
        assertNotNull(pki.sign("SHA3-384withECDSA", "still serving".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void fingerprintIsStableSha256Hex() throws Exception {
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");

        String fp = McpSecurityService.fingerprint(pki.serviceCertificate());
        assertTrue(fp.matches("[0-9a-f]{64}"), "SHA-256 as lower-case hex: " + fp);
        assertEquals(fp, McpSecurityService.fingerprint(pki.serviceCertificate()), "deterministic");

        X509Certificate other = (X509Certificate) testClientStore().getCertificate("1");
        assertNotEquals(fp, McpSecurityService.fingerprint(other), "different certificates differ");
    }

    /** Loads the committed client-one keystore (the "rotated" test identity) */
    static KeyStore testClientStore() throws Exception {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = McpSecurityServiceTest.class.getResourceAsStream("/secom-test/client-one.p12")) {
            keystore.load(in, "changeit".toCharArray());
        }
        return keystore;
    }

    /***************************************/
    /** Store-file watch (auto-reload)    **/
    /***************************************/

    /** Copies the given committed test store to a temp file, so the tests can overwrite it in place */
    static Path tempStore(String resource) throws Exception {
        Path file = Files.createTempFile("secom-watch-", ".p12");
        file.toFile().deleteOnExit();
        copyResource(resource, file);
        return file;
    }

    static void copyResource(String resource, Path target) throws Exception {
        try (InputStream in = McpSecurityServiceTest.class.getResourceAsStream(resource)) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    public void reloadIfChangedIsANoOpWhileTheStoresAreUnchanged() throws Exception {
        McpSecurityService pki = service("file:" + tempStore("/secom-test/keystore-test.p12"),
                "file:" + tempStore("/secom-test/truststore-test.p12"));
        String before = McpSecurityService.fingerprint(pki.serviceCertificate());

        assertNull(pki.reloadIfChanged(), "unchanged stores must not reload");
        assertEquals(before, McpSecurityService.fingerprint(pki.serviceCertificate()));
    }

    @Test
    public void reloadIfChangedSwapsOnAKeystoreRotation() throws Exception {
        Path keystoreFile = tempStore("/secom-test/keystore-test.p12");
        McpSecurityService pki = service("file:" + keystoreFile,
                "file:" + tempStore("/secom-test/truststore-test.p12"));
        String before = McpSecurityService.fingerprint(pki.serviceCertificate());

        // Overwrite the keystore file in place - the k8s secret-propagation pattern
        copyResource("/secom-test/client-one.p12", keystoreFile);
        McpSecurityService.Rotation rotation = pki.reloadIfChanged();

        assertNotNull(rotation, "a changed keystore must reload");
        assertEquals(before, McpSecurityService.fingerprint(rotation.previousCertificate()));
        assertNotEquals(before, McpSecurityService.fingerprint(rotation.certificate()));
        assertEquals(McpSecurityService.fingerprint(rotation.certificate()),
                McpSecurityService.fingerprint(pki.serviceCertificate()), "the swapped-in identity is serving");
        assertNull(pki.reloadIfChanged(), "the rotation converges - the next poll is a no-op");
    }

    @Test
    public void reloadIfChangedDetectsATruststoreOnlyUpdate() throws Exception {
        Path truststoreFile = tempStore("/secom-test/truststore-test.p12");
        McpSecurityService pki = service("file:" + tempStore("/secom-test/keystore-test.p12"),
                "file:" + truststoreFile);
        String before = McpSecurityService.fingerprint(pki.serviceCertificate());
        assertFalse(pki.trustStore().containsAlias("extra-peer-ca"));

        // Add a peer CA to the truststore file (the pre-cutover trust-exchange operation)
        KeyStore truststore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(truststoreFile)) {
            truststore.load(in, "changeit".toCharArray());
        }
        truststore.setCertificateEntry("extra-peer-ca", testClientStore().getCertificate("1"));
        try (OutputStream out = Files.newOutputStream(truststoreFile)) {
            truststore.store(out, "changeit".toCharArray());
        }
        McpSecurityService.Rotation rotation = pki.reloadIfChanged();

        assertNotNull(rotation, "a truststore-only change must reload too");
        assertEquals(before, McpSecurityService.fingerprint(rotation.certificate()),
                "the service identity is unchanged");
        assertTrue(pki.trustStore().containsAlias("extra-peer-ca"), "the new trust anchor is live");
    }

    @Test
    public void reloadIfChangedKeepsTheCurrentMaterialWhenTheChangedStoreIsBroken() throws Exception {
        Path keystoreFile = tempStore("/secom-test/keystore-test.p12");
        McpSecurityService pki = service("file:" + keystoreFile,
                "file:" + tempStore("/secom-test/truststore-test.p12"));
        String before = McpSecurityService.fingerprint(pki.serviceCertificate());

        Files.write(keystoreFile, "not a keystore".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, pki::reloadIfChanged, "a corrupt store fails the reload");
        assertEquals(before, McpSecurityService.fingerprint(pki.serviceCertificate()), "the old material stays active");
        assertNotNull(pki.sign("SHA3-384withECDSA", "still serving".getBytes(StandardCharsets.UTF_8)));

        // Restoring the original content converges without a swap (bytes match the loaded material)
        copyResource("/secom-test/keystore-test.p12", keystoreFile);
        assertNull(pki.reloadIfChanged());
    }

    @Test
    public void fileBackedStoreDetection() throws Exception {
        assertTrue(McpSecurityService.isFileBacked("file:/run/secrets/secom/keystore.p12"));
        assertTrue(McpSecurityService.isFileBacked("/run/secrets/secom/keystore.p12"));
        assertFalse(McpSecurityService.isFileBacked("classpath:secom-test/keystore-test.p12"));
        assertFalse(McpSecurityService.isFileBacked("secom-test/keystore-test.p12"));
        assertFalse(McpSecurityService.isFileBacked(null));

        McpSecurityService classpathOnly = service("classpath:secom-test/keystore-test.p12");
        assertFalse(classpathOnly.hasFileBackedStores(), "classpath-only stores are restart-only");

        McpSecurityService fileBacked = service("file:" + tempStore("/secom-test/keystore-test.p12"));
        assertTrue(fileBacked.hasFileBackedStores(), "one file-backed store makes the node watchable");
    }

    @Test
    public void providersSupportTheSpecMandatedSha2Algorithm() throws Exception {
        // The S-124 service design (v0.995 §3.3.3) mandates ECDSA-384-SHA2; the algorithm is
        // configurable, so a node switched to it must sign and validate consistently
        McpSecurityService pki = service("classpath:secom-test/keystore-test.p12");
        NiordSecomProviders providers = new NiordSecomProviders(pki, DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA);

        assertEquals(DigitalSignatureAlgorithmEnum.SHA2_384_WITH_ECDSA, providers.getSignatureAlgorithm());

        byte[] payload = "spec algorithm".getBytes(StandardCharsets.UTF_8);
        byte[] signature = providers.generateSignature(providers.getDigitalSignatureCertificate(), payload);

        String pem = SecomPemUtils.getMinifiedPemFromCert(pki.serviceCertificate());
        assertTrue(providers.validateSignature(new String[] { pem }, signature, payload));
        assertFalse(providers.validateSignature(new String[] { pem }, signature, "tampered".getBytes(StandardCharsets.UTF_8)),
                "a tampered payload must not validate under SHA2-384 either");
    }
}
