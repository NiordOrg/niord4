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

import org.niord.secom.util.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertPathBuilder;
import java.security.cert.Certificate;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Enumeration;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.TrustAnchor;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

/**
 * One-stop shop for everything MCP (Maritime Connectivity Platform) PKI related, used by the
 * SECOM integration: the service certificate + private key used to sign outgoing SECOM
 * payloads, and the truststore holding the CA certificates that remote SECOM client
 * certificates must chain to.
 * <p>
 * Store locations accept {@code classpath:}
 * and {@code file:} prefixes (bare absolute paths are treated as files), and the keystore
 * bytes may be base64-encoded (convenient for k8s secrets) — binary PKCS12 is auto-detected.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class McpSecurityService {

    private static final Logger log = LoggerFactory.getLogger(McpSecurityService.class);

    private final ResourceLoader resourceLoader;

    @Value("${niord.secom.keystore.location}")
    String keystoreLocation;

    @Value("${niord.secom.keystore.password}")
    String keystorePassword;

    @Value("${niord.secom.keystore.alias:1}")
    String keystoreAlias;

    @Value("${niord.secom.truststore.location}")
    String truststoreLocation;

    @Value("${niord.secom.truststore.password}")
    String truststorePassword;

    /** Optional truststore alias of the MCP root certificate; defaults to the first certificate entry */
    @Value("${niord.secom.truststore.root-alias:}")
    String truststoreRootAlias;

    /**
     * Immutable snapshot of the loaded MCP key material; swapped atomically on {@link #reload()}.
     * The source digest fingerprints the raw store bytes as read from their locations, letting
     * {@link #reloadIfChanged()} detect on-disk changes without re-parsing the stores.
     */
    record KeyMaterial(KeyStore keystore, KeyStore truststore, X509Certificate serviceCertificate,
                       List<X509Certificate> serviceCertificateChain,
                       X509Certificate rootCertificate, String rootAlias, PrivateKey privateKey,
                       String sourceDigest) {
    }

    private volatile KeyMaterial material;

    public McpSecurityService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void init() throws Exception {
        this.material = loadKeyMaterial();
        log.info("Loaded SECOM service certificate '{}' (root '{}')",
                material.serviceCertificate().getSubjectX500Principal().getName(),
                material.rootCertificate().getSubjectX500Principal().getName());
    }

    /** Loads and validates the configured keystore/truststore into an immutable snapshot */
    private KeyMaterial loadKeyMaterial() throws Exception {
        return loadKeyMaterial(readStore(keystoreLocation), readStore(truststoreLocation));
    }

    /** Parses and validates the given raw store bytes into an immutable snapshot */
    private KeyMaterial loadKeyMaterial(byte[] keystoreBytes, byte[] truststoreBytes) throws Exception {
        KeyStore keystore = parseStore(keystoreBytes, keystorePassword, keystoreLocation);
        KeyStore truststore = parseStore(truststoreBytes, truststorePassword, truststoreLocation);

        Certificate cert = keystore.getCertificate(keystoreAlias);
        if (!(cert instanceof X509Certificate x509)) {
            throw new IllegalStateException("No X.509 certificate with alias '" + keystoreAlias + "' in " + keystoreLocation);
        }
        PrivateKey privateKey = (PrivateKey) keystore.getKey(keystoreAlias, keystorePassword.toCharArray());
        if (privateKey == null) {
            throw new IllegalStateException("No private key with alias '" + keystoreAlias + "' in " + keystoreLocation);
        }

        RootEntry root = resolveRootCertificate(truststore);
        List<X509Certificate> chain = certificationPath(keystore, keystoreAlias, x509, truststore);
        return new KeyMaterial(keystore, truststore, x509, chain, root.certificate(), root.alias(), privateKey,
                sourceDigest(keystoreBytes, truststoreBytes));
    }

    /**
     * The service certificate's certification path, leaf first, as far as the configured stores
     * establish it: the chain stored with the keystore entry when there is one - the MIR issues
     * the leaf together with its issuing CA, and the renewal job stores what it was issued -
     * completed from the truststore otherwise, up to and including the self-signed root when
     * either store holds it. The S-124 exchange-set packaging carries the certificates between
     * the leaf and that root (S-100 Part 15, clause 15-8.7: the Data Server includes its Domain
     * Coordinator's certificate) and names the root as the scheme administrator. A path that
     * cannot be completed is returned as far as it goes, never failing the load: the leaf alone
     * still signs, and a consumer with the MCP chain installed still validates.
     */
    static List<X509Certificate> certificationPath(KeyStore keystore, String alias, X509Certificate leaf,
                                                   KeyStore truststore) throws KeyStoreException {
        List<X509Certificate> path = new ArrayList<>();
        Certificate[] stored = keystore.getCertificateChain(alias);
        if (stored != null) {
            for (Certificate certificate : stored) {
                if (certificate instanceof X509Certificate x509) {
                    path.add(x509);
                }
            }
        }
        if (path.isEmpty() || !path.get(0).equals(leaf)) {
            path.clear();
            path.add(leaf);
        }
        if (isSelfSigned(path.get(path.size() - 1))) {
            return List.copyOf(path);
        }
        // The keystore stops short of the root: complete the path from the truststore. Only its
        // self-signed entries anchor the search, so an intermediate stored as a trusted entry is
        // walked through rather than stopped at, and the builder checks each link's signature.
        try {
            Set<TrustAnchor> anchors = new HashSet<>();
            List<X509Certificate> candidates = new ArrayList<>(path);
            for (Enumeration<String> aliases = truststore.aliases(); aliases.hasMoreElements(); ) {
                if (truststore.getCertificate(aliases.nextElement()) instanceof X509Certificate x509) {
                    candidates.add(x509);
                    if (isSelfSigned(x509)) {
                        anchors.add(new TrustAnchor(x509, null));
                    }
                }
            }
            if (anchors.isEmpty()) {
                return List.copyOf(path);
            }
            X509CertSelector target = new X509CertSelector();
            target.setCertificate(leaf);
            PKIXBuilderParameters params = new PKIXBuilderParameters(anchors, target);
            params.setRevocationEnabled(false);
            params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(candidates)));
            PKIXCertPathBuilderResult result =
                    (PKIXCertPathBuilderResult) CertPathBuilder.getInstance("PKIX").build(params);
            List<X509Certificate> built = new ArrayList<>();
            for (Certificate certificate : result.getCertPath().getCertificates()) {
                built.add((X509Certificate) certificate);
            }
            built.add(result.getTrustAnchor().getTrustedCert());
            return List.copyOf(built);
        } catch (GeneralSecurityException e) {
            log.debug("Cannot complete the certification path of '{}' from the truststore: {}",
                    leaf.getSubjectX500Principal().getName(), e.getMessage());
            return List.copyOf(path);
        }
    }

    /** {@return whether the certificate is a root: it names itself as issuer and verifies under its own key} */
    public static boolean isSelfSigned(X509Certificate certificate) {
        if (!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            return false;
        }
        try {
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private record RootEntry(X509Certificate certificate, String alias) {
    }

    private RootEntry resolveRootCertificate(KeyStore truststore) throws Exception {
        if (truststoreRootAlias != null && !truststoreRootAlias.isBlank()) {
            Certificate cert = truststore.getCertificate(truststoreRootAlias);
            if (!(cert instanceof X509Certificate x509)) {
                throw new IllegalStateException("No X.509 certificate with alias '" + truststoreRootAlias + "' in " + truststoreLocation);
            }
            return new RootEntry(x509, truststoreRootAlias);
        }
        // Default: the first certificate entry in the truststore
        Enumeration<String> aliases = truststore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate cert = truststore.getCertificate(alias);
            if (cert instanceof X509Certificate x509) {
                return new RootEntry(x509, alias);
            }
        }
        throw new IllegalStateException("Truststore " + truststoreLocation + " contains no certificates");
    }

    /**
     * Re-reads the configured keystore/truststore locations and atomically swaps in the fresh
     * key material — certificate rotation without a restart. The locations and passwords stay
     * as configured, so this covers the k8s pattern of a secret updated in place; a password
     * change (or a {@code classpath:} store baked into the jar) still needs a restart. On any
     * load/validation failure the current material stays active and the exception propagates.
     * Serialized; readers are never blocked and always see a complete snapshot.
     */
    public synchronized void reload() throws Exception {
        KeyMaterial old = this.material;
        KeyMaterial fresh = loadKeyMaterial();   // throws -> the old material stays active
        this.material = fresh;
        log.info("Reloaded SECOM key material: '{}' (SHA-256 {}, notAfter {}) -> '{}' (SHA-256 {}, notAfter {})",
                old.serviceCertificate().getSubjectX500Principal().getName(),
                fingerprint(old.serviceCertificate()), old.serviceCertificate().getNotAfter(),
                fresh.serviceCertificate().getSubjectX500Principal().getName(),
                fingerprint(fresh.serviceCertificate()), fresh.serviceCertificate().getNotAfter());
    }

    /** Outcome of a {@link #reloadIfChanged()} poll that found changed stores and swapped them in */
    public record Rotation(X509Certificate previousCertificate, X509Certificate certificate) {
    }

    /**
     * Re-reads the configured stores and swaps them in only when their on-disk content differs
     * from the loaded material — the convergence seam behind the scheduled store-file watch
     * (identity plan T2). The comparison is on the raw bytes of <em>both</em> stores, so
     * truststore-only updates (a new peer CA) reload too. Returns {@code null} when nothing
     * changed; on any load/validation failure the current material stays active and the
     * exception propagates, exactly like {@link #reload()}.
     */
    public synchronized Rotation reloadIfChanged() throws Exception {
        byte[] keystoreBytes = readStore(keystoreLocation);
        byte[] truststoreBytes = readStore(truststoreLocation);
        KeyMaterial old = this.material;
        if (sourceDigest(keystoreBytes, truststoreBytes).equals(old.sourceDigest())) {
            return null;
        }
        KeyMaterial fresh = loadKeyMaterial(keystoreBytes, truststoreBytes);   // throws -> the old material stays active
        this.material = fresh;
        log.info("Auto-reloaded SECOM key material after store change: '{}' (SHA-256 {}, notAfter {}) -> '{}' (SHA-256 {}, notAfter {})",
                old.serviceCertificate().getSubjectX500Principal().getName(),
                fingerprint(old.serviceCertificate()), old.serviceCertificate().getNotAfter(),
                fresh.serviceCertificate().getSubjectX500Principal().getName(),
                fingerprint(fresh.serviceCertificate()), fresh.serviceCertificate().getNotAfter());
        return new Rotation(old.serviceCertificate(), fresh.serviceCertificate());
    }

    /** {@return whether at least one configured store is file-backed, i.e. can change on disk} */
    public boolean hasFileBackedStores() {
        return isFileBacked(keystoreLocation) || isFileBacked(truststoreLocation);
    }

    /** A location is file-backed when it is a {@code file:} URL or a bare absolute path */
    static boolean isFileBacked(String location) {
        return location != null && (location.startsWith("file:") || location.startsWith("/"));
    }

    /** Digest of the raw bytes of both stores; per-store digests are concatenated to avoid boundary ambiguity */
    private static String sourceDigest(byte[] keystoreBytes, byte[] truststoreBytes) {
        return Sha256.hex(keystoreBytes) + ":" + Sha256.hex(truststoreBytes);
    }

    /** {@return the SHA-256 fingerprint of the certificate as lower-case hex} */
    public static String fingerprint(X509Certificate certificate) {
        try {
            return Sha256.hex(certificate.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint certificate", e);
        }
    }

    /** Reads the raw bytes of the store at the given {@code classpath:}/{@code file:}/absolute-path location */
    private byte[] readStore(String location) throws Exception {
        String resourceLocation = location.startsWith("classpath:") || location.startsWith("file:")
                ? location
                : (location.startsWith("/") ? "file:" + location : "classpath:" + location);
        Resource resource = resourceLoader.getResource(resourceLocation);
        if (!resource.exists()) {
            throw new IllegalArgumentException("SECOM keystore not found at: " + location);
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** Parses the (possibly base64-encoded) PKCS12 store bytes read from the given location */
    private KeyStore parseStore(byte[] data, String password, String location) throws Exception {
        // k8s secrets are often base64 text; binary PKCS12 starts with an ASN.1 SEQUENCE (0x30 0x82)
        if (isBase64Encoded(data)) {
            log.info("Detected base64-encoded keystore at {}, decoding", location);
            data = Base64.getMimeDecoder().decode(new String(data, StandardCharsets.UTF_8).trim());
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data)) {
            ks.load(bis, password.toCharArray());
        }
        log.info("Loaded SECOM keystore from: {}", location);
        return ks;
    }

    private boolean isBase64Encoded(byte[] data) {
        if (data.length >= 2 && data[0] == 0x30 && (data[1] & 0xFF) == 0x82) {
            return false; // Binary PKCS12
        }
        return new String(data, StandardCharsets.US_ASCII).matches("^[A-Za-z0-9+/=\\s]+$");
    }

    /**
     * Loads an additional PKCS12 store (e.g. the certificate-renewal robot credential) with
     * the same location conventions ({@code classpath:}/{@code file:}/bare absolute path) and
     * base64 auto-detection as the configured SECOM stores.
     */
    public KeyStore loadExternalStore(String location, String password) throws Exception {
        return parseStore(readStore(location), password, location);
    }

    /** {@return the MCP service certificate of this Niord instance} */
    public X509Certificate serviceCertificate() {
        return material.serviceCertificate();
    }

    /**
     * {@return the service certificate's certification path, leaf first, up to and including the
     * self-signed root where the configured stores hold it} See {@link #certificationPath}.
     */
    public List<X509Certificate> serviceCertificateChain() {
        return material.serviceCertificateChain();
    }

    /** {@return the MCP root certificate} */
    public X509Certificate rootCertificate() {
        return material.rootCertificate();
    }

    /** {@return the truststore holding the trusted MCP CA certificates} */
    public KeyStore trustStore() {
        return material.truststore();
    }

    /** {@return the truststore alias of the MCP root certificate (used by the SECOM signature filter)} */
    public String trustStoreRootAlias() {
        return material.rootAlias();
    }

    /** One certificate entry of the truststore */
    public record TrustedCertificate(String alias, X509Certificate certificate, boolean root) {
    }

    /** {@return the certificate entries of the truststore, with the MCP root certificate flagged} */
    public List<TrustedCertificate> trustedCertificates() {
        KeyMaterial m = material;
        try {
            List<TrustedCertificate> result = new ArrayList<>();
            Enumeration<String> aliases = m.truststore().aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (m.truststore().getCertificate(alias) instanceof X509Certificate x509) {
                    result.add(new TrustedCertificate(alias, x509, alias.equals(m.rootAlias())));
                }
            }
            return result;
        } catch (KeyStoreException e) {
            throw new IllegalStateException("Cannot enumerate the truststore", e);
        }
    }

    /** {@return the keystore alias of the service certificate} */
    public String keystoreAlias() {
        return keystoreAlias;
    }

    /** Signs the payload with the service private key using the given JCA algorithm */
    public byte[] sign(String algorithm, byte[] payload) throws GeneralSecurityException {
        Signature sign = Signature.getInstance(algorithm);
        sign.initSign(material.privateKey());
        sign.update(payload);
        return sign.sign();
    }

    /**
     * Creates an outbound HTTP client that offers the service certificate as a TLS client
     * certificate (an Edition 2 peer ignores it: identity is the signed envelope).
     * Remote server certificates are validated against the MCP truststore, optionally with
     * soft-fail OCSP/CRL revocation checking (service design §3.3.1).
     *
     * @param trustAll        if true, remote server certificates are not validated at all
     *                        (test registries); implies no revocation checking
     * @param revocationCheck if true, remote server certificates are checked for revocation
     */
    public HttpClient newHttpClient(boolean trustAll, boolean revocationCheck) {
        try {
            // One volatile read: the client's key material and trust anchors stay consistent
            KeyMaterial m = material;
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(m.keystore(), keystorePassword.toCharArray());

            TrustManager tm = trustAll ? TRUST_ALL : serverTrustManager(m.truststore(), revocationCheck);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), new TrustManager[] { tm }, null);
            return HttpClient.newBuilder().sslContext(sslContext).build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SECOM HTTP client", e);
        }
    }

    /** Server trust manager over the given truststore, optionally with soft-fail OCSP/CRL revocation checking */
    static TrustManager serverTrustManager(KeyStore truststore, boolean revocationCheck) throws GeneralSecurityException {
        if (revocationCheck) {
            try {
                TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
                tmf.init(new CertPathTrustManagerParameters(revocationParameters(truststore)));
                return tmf.getTrustManagers()[0];
            } catch (Exception e) {
                // Setup problems (e.g. a truststore without trusted entries) must not become a
                // delivery outage - log and fall back to chain-only validation, mirroring the
                // inbound MrnCertificateFilter revocation stance. Chain trust still holds.
                log.warn("SECOM outbound revocation checking could not be set up: {}", e.getMessage());
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(truststore);
        return tmf.getTrustManagers()[0];
    }

    /** PKIX parameters with an OCSP-then-CRL-fallback, soft-fail revocation checker (design §3.3.1) */
    static PKIXBuilderParameters revocationParameters(KeyStore truststore) throws GeneralSecurityException {
        PKIXRevocationChecker checker = (PKIXRevocationChecker)
                CertPathBuilder.getInstance("PKIX").getRevocationChecker();
        // Soft-fail: network/availability problems reaching the OCSP responder or the CRL
        // distribution point do not block delivery - only a positive revocation does
        checker.setOptions(EnumSet.of(PKIXRevocationChecker.Option.SOFT_FAIL));
        PKIXBuilderParameters params = new PKIXBuilderParameters(truststore, new X509CertSelector());
        params.addCertPathChecker(checker);
        params.setRevocationEnabled(true);
        return params;
    }

    private static final X509ExtendedTrustManager TRUST_ALL = new X509ExtendedTrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };
}
