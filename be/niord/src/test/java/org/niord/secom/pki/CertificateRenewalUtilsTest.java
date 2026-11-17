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

import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.io.InputStream;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.secom.pki.TestCertificateAuthority.daysFromNow;

/**
 * Unit tests for the pure renewal crypto ({@link CertificateRenewalUtils}): CSR round-trip,
 * chain parsing, the hard acceptance gates an issued chain must pass, the soft truststore
 * anchoring check, and the keystore assemble/verify round-trip. Uses the committed test PKI
 * fixtures plus an ephemeral {@link TestCertificateAuthority} (the committed root's private
 * key is deliberately not available to tests).
 */
public class CertificateRenewalUtilsTest {

    static final String SERVICE_MRN = "urn:mrn:mcp:service:mcc:test:instance:niord-test";
    static final String TEST_MRN = "urn:mrn:mcp:user:mcc:test:renewal-robot";
    static final X500Principal TEST_SUBJECT = new X500Principal("UID=" + TEST_MRN + ", CN=Renewal Robot");
    static final TestCertificateAuthority CA = TestCertificateAuthority.create("Renewal Utils Test CA");

    static KeyStore fixture(String resource) {
        try (InputStream in = CertificateRenewalUtilsTest.class.getResourceAsStream(resource)) {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(in, "changeit".toCharArray());
            return keystore;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load fixture " + resource, e);
        }
    }

    static X509Certificate serviceCert() throws Exception {
        return (X509Certificate) fixture("/secom-test/keystore-test.p12").getCertificate("1");
    }

    /** An honest issued chain: leaf over the given fresh key, subject = TEST_SUBJECT, anchored at CA */
    static List<X509Certificate> issuedChain(KeyPair freshKeys) throws Exception {
        String csr = CertificateRenewalUtils.buildCsrPem(TEST_SUBJECT, freshKeys);
        return CertificateRenewalUtils.parsePemChain(CA.signChainFromCsr(csr, daysFromNow(-1), daysFromNow(180)));
    }

    @Test
    public void generateKeyPairUsesSecp384r1() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        assertEquals("EC", keys.getPublic().getAlgorithm());
        assertEquals(384, ((ECPublicKey) keys.getPublic()).getParams().getCurve().getField().getFieldSize());
    }

    @Test
    public void csrRoundTripsSubjectKeyAndSignature() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        String pem = CertificateRenewalUtils.buildCsrPem(TEST_SUBJECT, keys);
        assertTrue(pem.contains("BEGIN CERTIFICATE REQUEST"));

        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            PKCS10CertificationRequest csr = (PKCS10CertificationRequest) parser.readObject();
            assertTrue(csr.getSubject().toString().contains(TEST_MRN), "the CSR carries the subject UID");
            assertArrayEquals(keys.getPublic().getEncoded(), csr.getSubjectPublicKeyInfo().getEncoded(),
                    "the CSR carries the fresh public key");
            assertTrue(csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(keys.getPublic())),
                    "the CSR is self-signed with the fresh private key");
        }
    }

    @Test
    public void parsePemChainHandlesChainsAndRejectsGarbage() throws Exception {
        List<X509Certificate> chain = issuedChain(CertificateRenewalUtils.generateKeyPair());
        assertEquals(2, chain.size(), "leaf + CA");
        assertEquals(TEST_MRN, CertificateRenewalUtils.subjectMrn(chain.get(0)), "leaf first, subject preserved");

        assertThrows(CertificateException.class, () -> CertificateRenewalUtils.parsePemChain("this is not a pem chain"));
        assertThrows(CertificateException.class, () -> CertificateRenewalUtils.parsePemChain(""));
        assertThrows(CertificateException.class, () -> CertificateRenewalUtils.parsePemChain(null));
    }

    @Test
    public void verifyIssuedChainAcceptsAnHonestChain() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        CertificateRenewalUtils.verifyIssuedChain(issuedChain(keys), keys.getPublic(), TEST_MRN, new Date());
    }

    @Test
    public void verifyIssuedChainRejectsAChainOverAForeignKey() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        KeyPair other = CertificateRenewalUtils.generateKeyPair();
        GeneralSecurityException e = assertThrows(GeneralSecurityException.class, () ->
                CertificateRenewalUtils.verifyIssuedChain(issuedChain(keys), other.getPublic(), TEST_MRN, new Date()));
        assertTrue(e.getMessage().contains("freshly generated key pair"), "unexpected: " + e.getMessage());
    }

    @Test
    public void verifyIssuedChainRejectsAWrongMrn() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        GeneralSecurityException e = assertThrows(GeneralSecurityException.class, () ->
                CertificateRenewalUtils.verifyIssuedChain(issuedChain(keys), keys.getPublic(),
                        "urn:mrn:mcp:user:mcc:test:someone-else", new Date()));
        assertTrue(e.getMessage().contains("does not match the expected MRN"), "unexpected: " + e.getMessage());
    }

    @Test
    public void verifyIssuedChainToleratesAFreshlyMintedNotBefore() throws Exception {
        // A real MIR mints NotBefore at ITS issuance instant - seconds AFTER the "now"
        // captured before the round-trip (observed live against the MCC demonstrator MIR)
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        String csr = CertificateRenewalUtils.buildCsrPem(TEST_SUBJECT, keys);
        List<X509Certificate> fresh = CertificateRenewalUtils.parsePemChain(
                CA.signChainFromCsr(csr, new Date(System.currentTimeMillis() + 30_000), daysFromNow(180)));
        CertificateRenewalUtils.verifyIssuedChain(fresh, keys.getPublic(), TEST_MRN, new Date());
    }

    @Test
    public void verifyIssuedChainRejectsANotBeforeBeyondTheSkewAllowance() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        String csr = CertificateRenewalUtils.buildCsrPem(TEST_SUBJECT, keys);
        List<X509Certificate> future = CertificateRenewalUtils.parsePemChain(
                CA.signChainFromCsr(csr,
                        new Date(System.currentTimeMillis() + CertificateRenewalUtils.NOT_BEFORE_CLOCK_SKEW_MILLIS + 60_000),
                        daysFromNow(180)));
        GeneralSecurityException e = assertThrows(GeneralSecurityException.class, () ->
                CertificateRenewalUtils.verifyIssuedChain(future, keys.getPublic(), TEST_MRN, new Date()));
        assertTrue(e.getMessage().contains("not valid at renewal time"), "unexpected: " + e.getMessage());
    }

    @Test
    public void verifyIssuedChainRejectsAnExpiredLeaf() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        String csr = CertificateRenewalUtils.buildCsrPem(TEST_SUBJECT, keys);
        List<X509Certificate> expired = CertificateRenewalUtils.parsePemChain(
                CA.signChainFromCsr(csr, daysFromNow(-10), daysFromNow(-1)));
        assertThrows(GeneralSecurityException.class, () ->
                CertificateRenewalUtils.verifyIssuedChain(expired, keys.getPublic(), TEST_MRN, new Date()));
    }

    @Test
    public void verifyIssuedChainRejectsAnExpiredIntermediate() throws Exception {
        // A valid leaf signed by an EXPIRED CA: peers doing full PKIX validation would reject
        // the presented chain, so the hard gate must catch it - the soft anchoring warning
        // is not enough
        TestCertificateAuthority expiredCa = TestCertificateAuthority.create("Expired CA",
                daysFromNow(-3650), daysFromNow(-1));
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        String csr = CertificateRenewalUtils.buildCsrPem(TEST_SUBJECT, keys);
        List<X509Certificate> chain = CertificateRenewalUtils.parsePemChain(
                expiredCa.signChainFromCsr(csr, daysFromNow(-1), daysFromNow(180)));

        GeneralSecurityException e = assertThrows(GeneralSecurityException.class, () ->
                CertificateRenewalUtils.verifyIssuedChain(chain, keys.getPublic(), TEST_MRN, new Date()));
        assertTrue(e.getMessage().contains("not valid at renewal time"), "unexpected: " + e.getMessage());
        assertTrue(e.getMessage().contains("Expired CA"), "the failure names the offending certificate");
    }

    @Test
    public void verifyIssuedChainRejectsABrokenLink() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        TestCertificateAuthority otherCa = TestCertificateAuthority.create("Some Other CA");
        // The leaf is signed by CA, but the presented chain claims otherCa as its issuer
        List<X509Certificate> forged = List.of(issuedChain(keys).get(0), otherCa.certificate());
        assertThrows(GeneralSecurityException.class, () ->
                CertificateRenewalUtils.verifyIssuedChain(forged, keys.getPublic(), TEST_MRN, new Date()));
    }

    @Test
    public void chainAnchoringIsASoftExactCheck() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        List<X509Certificate> chain = issuedChain(keys);

        KeyStore anchoring = KeyStore.getInstance("PKCS12");
        anchoring.load(null, null);
        anchoring.setCertificateEntry("ca", CA.certificate());
        assertTrue(CertificateRenewalUtils.chainAnchorsInTruststore(chain, anchoring));

        // The committed truststore does not contain the ephemeral CA
        assertFalse(CertificateRenewalUtils.chainAnchorsInTruststore(chain,
                fixture("/secom-test/truststore-test.p12")));

        // Never throws - not even on an empty chain
        assertFalse(CertificateRenewalUtils.chainAnchorsInTruststore(List.of(), anchoring));
    }

    @Test
    public void assembledKeystoreRoundTripsAndGuardsMismatches() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        List<X509Certificate> chain = issuedChain(keys);
        byte[] p12 = CertificateRenewalUtils.assembleKeystore("1", "changeit", keys.getPrivate(), chain);

        CertificateRenewalUtils.verifyKeystore(p12, "1", "changeit", keys.getPublic());

        KeyStore parsed = KeyStore.getInstance("PKCS12");
        parsed.load(new java.io.ByteArrayInputStream(p12), "changeit".toCharArray());
        assertEquals(2, parsed.getCertificateChain("1").length, "the whole chain is stored with the key entry");

        KeyPair other = CertificateRenewalUtils.generateKeyPair();
        GeneralSecurityException mismatch = assertThrows(GeneralSecurityException.class, () ->
                CertificateRenewalUtils.verifyKeystore(p12, "1", "changeit", other.getPublic()));
        assertTrue(mismatch.getMessage().contains("does not match"), "unexpected: " + mismatch.getMessage());

        GeneralSecurityException wrongAlias = assertThrows(GeneralSecurityException.class, () ->
                CertificateRenewalUtils.verifyKeystore(p12, "2", "changeit", keys.getPublic()));
        assertTrue(wrongAlias.getMessage().contains("no key entry"), "unexpected: " + wrongAlias.getMessage());

        assertThrows(GeneralSecurityException.class, () ->
                CertificateRenewalUtils.verifyKeystore(p12, "1", "wrong-password", keys.getPublic()));
    }

    @Test
    public void subjectMrnExtractsTheUidOrReturnsNull() throws Exception {
        assertEquals(SERVICE_MRN, CertificateRenewalUtils.subjectMrn(serviceCert()));
        assertNull(CertificateRenewalUtils.subjectMrn(CA.certificate()), "a CN-only subject has no MRN");
    }

    @Test
    public void pemEncodedChainRoundTrips() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();
        List<X509Certificate> chain = issuedChain(keys);

        String pem = CertificateRenewalUtils.pemEncodeChain(chain);
        List<X509Certificate> parsed = CertificateRenewalUtils.parsePemChain(pem);

        assertEquals(chain.size(), parsed.size());
        for (int i = 0; i < chain.size(); i++) {
            assertArrayEquals(chain.get(i).getEncoded(), parsed.get(i).getEncoded(),
                    "certificate " + i + " survives the PEM round trip in order");
        }
    }

    @Test
    public void pemEncodedPrivateKeyIsUnencryptedPkcs8() throws Exception {
        KeyPair keys = CertificateRenewalUtils.generateKeyPair();

        String pem = CertificateRenewalUtils.pemEncodePrivateKey(keys.getPrivate());

        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----"),
                "nginx needs plain PKCS#8, not an encrypted or SEC1 form: " + pem.substring(0, 40));
        byte[] der = java.util.Base64.getMimeDecoder().decode(pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", ""));
        assertArrayEquals(keys.getPrivate().getEncoded(),
                java.security.KeyFactory.getInstance("EC")
                        .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der)).getEncoded(),
                "the PEM body is the exact PKCS#8 encoding of the key");
    }
}
