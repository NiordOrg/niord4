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

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A tiny in-test certificate authority for the certificate-renewal tests: the committed test
 * PKI deliberately does not include the root CA private key ({@code generate-test-pki.sh}
 * deletes it), so tests that must sign a leaf over a freshly generated CSR key - the honest
 * stub-MIR behavior - mint their own ephemeral CA instead. secp384r1/SHA-384, like the
 * committed fixtures, with the same closed-port OCSP pointer (soft-fail posture).
 * <p>
 * A CA can issue a subordinate CA ({@link #issueIntermediate}), standing in for the MCP identity
 * registry's intermediate; the identities it then mints carry the whole chain up to the root,
 * the way the MIR issues them.
 * <p>
 * The single copy shared by the pki unit tests and the certificate-renewal ITs.
 */
public class TestCertificateAuthority {

    private static final AtomicLong SERIALS = new AtomicLong(System.currentTimeMillis());

    private final KeyPair caKeys;
    private final X509Certificate caCertificate;
    private final X500Name caName;
    /** The CAs above this one, nearest first; empty for a root */
    private final List<X509Certificate> issuers;

    private TestCertificateAuthority(KeyPair caKeys, X509Certificate caCertificate, X500Name caName,
                                     List<X509Certificate> issuers) {
        this.caKeys = caKeys;
        this.caCertificate = caCertificate;
        this.caName = caName;
        this.issuers = List.copyOf(issuers);
    }

    /** Creates a self-signed CA with the given common name */
    public static TestCertificateAuthority create(String cn) {
        return create(cn, daysFromNow(-1), daysFromNow(3650));
    }

    /** Creates a self-signed CA with the given validity - e.g. an already-expired CA for negative tests */
    public static TestCertificateAuthority create(String cn, Date notBefore, Date notAfter) {
        try {
            KeyPair keys = CertificateRenewalUtils.generateKeyPair();
            X500Name name = new X500Name("CN=" + cn);
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name, nextSerial(), notBefore, notAfter, name, keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            return new TestCertificateAuthority(keys, toX509(builder.build(signer(keys))), name, List.of());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create the test CA", e);
        }
    }

    /**
     * Issues a subordinate CA signed by this one - the shape of the MCP identity registry's
     * intermediate under the MCP root. Identities the subordinate mints chain through it to this CA.
     */
    public TestCertificateAuthority issueIntermediate(String cn) {
        try {
            KeyPair keys = CertificateRenewalUtils.generateKeyPair();
            X500Name name = new X500Name("CN=" + cn);
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    caName, nextSerial(), daysFromNow(-1), daysFromNow(3650), name, keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            return new TestCertificateAuthority(keys, toX509(builder.build(signer(caKeys))), name, chain());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot issue the intermediate CA", e);
        }
    }

    /** {@return this CA's certificate followed by those of the CAs above it, up to the root} */
    public List<X509Certificate> chain() {
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(caCertificate);
        chain.addAll(issuers);
        return chain;
    }

    /** {@return the CA certificate, e.g. for staging a truststore that anchors the issued chains} */
    public X509Certificate certificate() {
        return caCertificate;
    }

    /**
     * Stages the committed test truststore plus this CA as a temp file, so a renewal test can
     * boot against a truststore that anchors both the shipped fixtures and the chains this CA
     * issues. The prefix keeps each test class on its own file.
     */
    public Path stagedTruststore(String prefix) {
        try {
            KeyStore truststore = KeyStore.getInstance("PKCS12");
            try (InputStream in = TestCertificateAuthority.class.getResourceAsStream("/secom-test/truststore-test.p12")) {
                truststore.load(in, "changeit".toCharArray());
            }
            List<X509Certificate> chain = chain();
            for (int i = 0; i < chain.size(); i++) {
                truststore.setCertificateEntry(i == 0 ? "renewal-test-ca" : "renewal-test-ca-" + i, chain.get(i));
            }
            Path file = Files.createTempFile(prefix, ".p12");
            file.toFile().deleteOnExit();
            try (OutputStream out = Files.newOutputStream(file)) {
                truststore.store(out, "changeit".toCharArray());
            }
            return file;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot stage the renewal truststore", e);
        }
    }

    /** Signs a leaf over the public key + subject of the received CSR (the honest MIR behavior); returns leaf+CA PEM */
    public String signChainFromCsr(String csrPem, Date notBefore, Date notAfter) {
        try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
            PKCS10CertificationRequest csr = (PKCS10CertificationRequest) parser.readObject();
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    caName, nextSerial(), notBefore, notAfter, csr.getSubject(), csr.getSubjectPublicKeyInfo());
            addLeafExtensions(builder);
            return pem(toX509(builder.build(signer(caKeys)))) + pemChain();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign the CSR", e);
        }
    }

    /** A well-formed chain deliberately built over a key that is NOT the CSR's (negative tests) */
    public String signChainForOtherKey(String mrn) {
        try {
            KeyPair other = CertificateRenewalUtils.generateKeyPair();
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    caName, nextSerial(), daysFromNow(-1), daysFromNow(180),
                    new X500Name("UID=" + mrn + ",CN=Wrong Key"), other.getPublic());
            addLeafExtensions(builder);
            return pem(toX509(builder.build(signer(caKeys)))) + pemChain();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign the wrong-key chain", e);
        }
    }

    /** A minted end-entity identity: its key, its certificate and the CA chain above it */
    public record Identity(PrivateKey key, X509Certificate certificate, List<X509Certificate> issuers) {

        /** {@return the certificate followed by its issuers - the chain a keystore entry stores} */
        public List<X509Certificate> chain() {
            List<X509Certificate> chain = new ArrayList<>();
            chain.add(certificate);
            chain.addAll(issuers);
            return chain;
        }
    }

    /** Mints a fresh key + leaf signed by this CA; a null MRN yields a UID-less subject */
    public Identity mintLeaf(String mrn, Date notBefore, Date notAfter) {
        try {
            KeyPair keys = CertificateRenewalUtils.generateKeyPair();
            X500Name subject = new X500Name((mrn != null ? "UID=" + mrn + "," : "") + "CN=Renewal Test Identity");
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    caName, nextSerial(), notBefore, notAfter, subject, keys.getPublic());
            addLeafExtensions(builder);
            return new Identity(keys.getPrivate(), toX509(builder.build(signer(caKeys))), chain());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot mint the test identity", e);
        }
    }

    /** Mints a complete PKCS12 identity (fresh key + leaf + the CA chain); a null MRN yields a UID-less subject */
    public byte[] mintIdentity(String mrn, Date notBefore, Date notAfter, String alias, String password) {
        try {
            Identity identity = mintLeaf(mrn, notBefore, notAfter);
            return CertificateRenewalUtils.assembleKeystore(alias, password, identity.key(), identity.chain());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot mint the test identity", e);
        }
    }

    public static Date daysFromNow(int days) {
        return new Date(System.currentTimeMillis() + days * 24L * 60 * 60 * 1000);
    }

    private static void addLeafExtensions(X509v3CertificateBuilder builder) throws Exception {
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        // OCSP pointer at a closed port, matching the committed fixtures' soft-fail posture
        builder.addExtension(Extension.authorityInfoAccess, false, new AuthorityInformationAccess(
                new AccessDescription(AccessDescription.id_ad_ocsp,
                        new GeneralName(GeneralName.uniformResourceIdentifier, "http://127.0.0.1:9/ocsp"))));
    }

    private static ContentSigner signer(KeyPair keys) throws Exception {
        return new JcaContentSignerBuilder("SHA384withECDSA").build(keys.getPrivate());
    }

    private static X509Certificate toX509(X509CertificateHolder holder) throws Exception {
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    /** The PEM of this CA and every CA above it, in chain order */
    private String pemChain() {
        StringBuilder out = new StringBuilder();
        for (X509Certificate certificate : chain()) {
            out.append(pem(certificate));
        }
        return out.toString();
    }

    private static String pem(X509Certificate certificate) {
        try {
            StringWriter out = new StringWriter();
            try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
                writer.writeObject(certificate);
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot PEM-encode the certificate", e);
        }
    }

    private static BigInteger nextSerial() {
        return BigInteger.valueOf(SERIALS.incrementAndGet());
    }
}
