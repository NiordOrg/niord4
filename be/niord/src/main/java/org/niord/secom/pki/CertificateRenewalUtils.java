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

import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure crypto helpers for the unattended certificate renewal (identity plan T3): fresh MCP
 * key pairs, the PKCS#10 CSR for the MIR {@code issue-new/csr} endpoint, and the verification
 * gates an issued chain must pass before it is assembled into a keystore and persisted.
 * All BouncyCastle (bcpkix) usage of the renewal feature is contained here.
 */
public final class CertificateRenewalUtils {

    /** MRN extraction from the subject UID attribute */
    private static final Pattern UID_PATTERN = Pattern.compile("(?:^|,)\\s*UID=([^,]+)");

    private CertificateRenewalUtils() {
    }

    /** {@return a fresh EC key pair on the MCP-mandated secp384r1 curve (G1183 §5.3)} */
    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return generator.generateKeyPair();
    }

    /**
     * Builds a PEM PKCS#10 CSR over the given key pair. The MIR ignores the CSR subject
     * (the certificate is built from registry data plus the CSR public key), so the subject
     * merely keeps registry logs readable - conventionally the current certificate's.
     */
    public static String buildCsrPem(X500Principal subject, KeyPair keyPair) throws Exception {
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic())
                .build(new JcaContentSignerBuilder("SHA384withECDSA").build(keyPair.getPrivate()));
        StringWriter pem = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(pem)) {
            writer.writeObject(csr);
        }
        return pem.toString();
    }

    /** Parses a PEM certificate chain (leaf first), rejecting empty or non-certificate content */
    public static List<X509Certificate> parsePemChain(String pem) throws CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs = factory.generateCertificates(
                new ByteArrayInputStream(pem == null ? new byte[0] : pem.getBytes(StandardCharsets.UTF_8)));
        if (certs.isEmpty()) {
            throw new CertificateException("The MIR response contains no certificates");
        }
        return certs.stream().map(X509Certificate.class::cast).toList();
    }

    /**
     * How far in the future an issued certificate's NotBefore may lie and still pass the
     * validity gate. A real MIR mints validity from <em>its</em> issuance instant, which sits
     * seconds after the "now" captured before the round-trip (plus any clock lead of the
     * registry) — observed live against the MCC demonstrator MIR. Bounded, so a certificate
     * dated genuinely in the future still fails.
     */
    static final long NOT_BEFORE_CLOCK_SKEW_MILLIS = 10 * 60 * 1000L;

    /**
     * The hard acceptance gates for an issued chain: non-empty, leaf built over the freshly
     * generated public key, EVERY certificate in the chain valid at "now" (an expired or
     * not-yet-valid intermediate would make peers reject the presented chain — and unlike
     * the soft truststore-anchoring check, a certificate's own validity window does not
     * depend on possibly-outdated local state; a NotBefore freshly minted up to
     * {@link #NOT_BEFORE_CLOCK_SKEW_MILLIS} ahead by the issuer's clock is tolerated),
     * subject UID carrying the expected MRN, and
     * every link signed by the next certificate. A chain failing any of these never reaches disk.
     */
    public static void verifyIssuedChain(List<X509Certificate> chain, PublicKey expectedKey,
                                         String expectedMrn, Date now) throws GeneralSecurityException {
        if (chain.isEmpty()) {
            throw new CertificateException("The issued certificate chain is empty");
        }
        X509Certificate leaf = chain.get(0);
        if (!Arrays.equals(leaf.getPublicKey().getEncoded(), expectedKey.getEncoded())) {
            throw new CertificateException("The issued certificate is not built over the freshly generated key pair");
        }
        for (X509Certificate certificate : chain) {
            // A freshly minted certificate begins at the ISSUER's clock, typically seconds
            // after our pre-round-trip "now" - such a certificate is checked at its own
            // NotBefore instead (bounded; anything dated further ahead still fails)
            Date notBefore = certificate.getNotBefore();
            Date checkAt = notBefore.after(now)
                    && notBefore.getTime() - now.getTime() <= NOT_BEFORE_CLOCK_SKEW_MILLIS
                    ? notBefore : now;
            try {
                certificate.checkValidity(checkAt);
            } catch (CertificateException e) {
                throw new CertificateException("Certificate '" + certificate.getSubjectX500Principal().getName()
                        + "' in the issued chain is not valid at renewal time: " + e.getMessage());
            }
        }
        String mrn = subjectMrn(leaf);
        if (!expectedMrn.equals(mrn)) {
            throw new CertificateException("The issued certificate subject UID '" + mrn
                    + "' does not match the expected MRN '" + expectedMrn + "'");
        }
        for (int i = 0; i < chain.size() - 1; i++) {
            chain.get(i).verify(chain.get(i + 1).getPublicKey());
        }
    }

    /**
     * Soft check: whether the issued chain anchors in the given truststore. Renewal proceeds
     * either way (with a warning) - an outdated local truststore, e.g. around a provider CA
     * rollover, must not brick the renewal that is needed exactly then. Revocation is not
     * checked; a seconds-old certificate has no meaningful revocation state. Never throws.
     */
    public static boolean chainAnchorsInTruststore(List<X509Certificate> chain, KeyStore truststore) {
        try {
            X509CertSelector leaf = new X509CertSelector();
            leaf.setCertificate(chain.get(0));
            PKIXBuilderParameters params = new PKIXBuilderParameters(truststore, leaf);
            params.setRevocationEnabled(false);
            params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(chain)));
            CertPathBuilder.getInstance("PKIX").build(params);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Assembles a PKCS12 keystore holding the key + chain at the given alias, mirroring the configured stores */
    public static byte[] assembleKeystore(String alias, String password, PrivateKey privateKey,
                                          List<X509Certificate> chain) throws GeneralSecurityException {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(null, null);
            keystore.setKeyEntry(alias, privateKey, password.toCharArray(), chain.toArray(new Certificate[0]));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            keystore.store(out, password.toCharArray());
            return out.toByteArray();
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new KeyStoreException("Cannot assemble the renewed keystore: " + e.getMessage(), e);
        }
    }

    /**
     * The pre-persist gate: the assembled bytes must parse back with the same password and
     * hold a key entry at the alias whose certificate matches the freshly generated key -
     * guaranteeing that any later reload of the persisted store succeeds.
     */
    public static void verifyKeystore(byte[] keystoreBytes, String alias, String password,
                                      PublicKey expectedKey) throws GeneralSecurityException {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(new ByteArrayInputStream(keystoreBytes), password.toCharArray());
            Certificate certificate = keystore.getCertificate(alias);
            Key key = keystore.getKey(alias, password.toCharArray());
            if (certificate == null || key == null) {
                throw new KeyStoreException("The assembled keystore holds no key entry at alias '" + alias + "'");
            }
            if (!Arrays.equals(certificate.getPublicKey().getEncoded(), expectedKey.getEncoded())) {
                throw new KeyStoreException("The assembled keystore certificate does not match the freshly generated key pair");
            }
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new KeyStoreException("The assembled keystore does not parse back: " + e.getMessage(), e);
        }
    }

    /** PEM-encodes the certificate chain, leaf first - the form the nginx {@code ssl_certificate} expects */
    public static String pemEncodeChain(List<X509Certificate> chain) throws Exception {
        StringWriter pem = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(pem)) {
            for (X509Certificate certificate : chain) {
                writer.writeObject(certificate);
            }
        }
        return pem.toString();
    }

    /** PEM-encodes the private key as unencrypted PKCS#8 - the form the nginx {@code ssl_certificate_key} expects */
    public static String pemEncodePrivateKey(PrivateKey privateKey) throws Exception {
        StringWriter pem = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(pem)) {
            writer.writeObject(new JcaPKCS8Generator(privateKey, null));
        }
        return pem.toString();
    }

    /** {@return the MRN in the certificate subject {@code UID} attribute, or null when absent} */
    public static String subjectMrn(X509Certificate certificate) {
        String dn = certificate.getSubjectX500Principal().getName();
        Matcher m = UID_PATTERN.matcher(dn);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Truncates a remote error body for logs/alerts - shared by the SECOM HTTP clients */
    public static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
