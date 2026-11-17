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
import jakarta.annotation.PostConstruct;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link KeystoreWriter} for the k8s deployment (identity plan T3b, phase 4): the renewed
 * store is PATCHed into its Secret through the Kubernetes API, authenticated with the pod's
 * mounted ServiceAccount token - the Secret stays the single source of truth across pod
 * restarts and replicas, and no private key ever lands on a data volume. The pod needs a
 * ServiceAccount bound to a Role with {@code get}/{@code patch} on exactly the SECOM Secrets
 * (the chart's {@code secom.renewal} values render it).
 * <p>
 * Store locations stay the mounted files the application <em>reads</em>
 * ({@code file:/run/secrets/...}); {@code niord.secom.renewal.k8s.secret-mounts} maps each
 * mount directory to its Secret name, and the file name is the Secret key. A completed write
 * is durable but {@link #deferredPickup() deferred}: the kubelet re-syncs the mount within
 * ~1 minute and the store watch (T2) then completes the in-memory pickup.
 * <p>
 * The API endpoint, token, CA bundle and namespace default to the standard in-cluster
 * conventions; each is overridable, and a plain {@code http:} API URL skips TLS entirely
 * (the IT stub path, mirroring {@link McpMirRestClient}).
 */
@Service
@Primary
@ConditionalOnProperty(name = {"niord.secom.enabled", "niord.secom.renewal.k8s.enabled"}, havingValue = "true")
public class KubernetesSecretKeystoreWriter implements KeystoreWriter {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSecretKeystoreWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Comma-separated {@code <mount-directory>=<secret-name>} pairs covering the writable store locations */
    @Value("${niord.secom.renewal.k8s.secret-mounts:}")
    String secretMounts;

    /** Kubernetes API base URL; blank = in-cluster discovery via KUBERNETES_SERVICE_HOST/PORT */
    @Value("${niord.secom.renewal.k8s.api-url:}")
    String apiUrl;

    /** Namespace of the SECOM Secrets; blank = the pod's own (the mounted serviceaccount file) */
    @Value("${niord.secom.renewal.k8s.namespace:}")
    String namespace;

    @Value("${niord.secom.renewal.k8s.token-file:/var/run/secrets/kubernetes.io/serviceaccount/token}")
    String tokenFile;

    @Value("${niord.secom.renewal.k8s.ca-file:/var/run/secrets/kubernetes.io/serviceaccount/ca.crt}")
    String caFile;

    @Value("${niord.secom.renewal.k8s.namespace-file:/var/run/secrets/kubernetes.io/serviceaccount/namespace}")
    String namespaceFile;

    @Value("${niord.secom.renewal.timeout-seconds:30}")
    int timeoutSeconds;

    private volatile Map<String, String> mounts;

    @PostConstruct
    void init() {
        // Configuration problems surface as renewal-run alert mails (the phase-3 doctrine
        // for every other renewal knob), never as a failed application start
        try {
            log.info("SECOM renewal persists keystores via the Kubernetes API: {}", mounts());
        } catch (RuntimeException e) {
            log.warn("SECOM renewal k8s backend is misconfigured (renewal runs will alert): {}", e.getMessage());
        }
    }

    /** The parsed mount mapping; a configuration problem throws here and becomes a renewal-run alert */
    private Map<String, String> mounts() {
        Map<String, String> resolved = mounts;
        if (resolved == null) {
            resolved = parseSecretMounts(secretMounts);
            if (resolved.isEmpty()) {
                throw new IllegalStateException("niord.secom.renewal.k8s.enabled is true but "
                        + "niord.secom.renewal.k8s.secret-mounts maps no directories");
            }
            mounts = resolved;
        }
        return resolved;
    }

    /** Parses {@code <mount-directory>=<secret-name>} pairs; malformed entries throw (alerted per run) */
    static Map<String, String> parseSecretMounts(String spec) {
        Map<String, String> mounts = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return mounts;
        }
        for (String raw : spec.split(",")) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            String dir = eq < 0 ? "" : entry.substring(0, eq).trim();
            String secret = eq < 0 ? "" : entry.substring(eq + 1).trim();
            if (dir.isEmpty() || secret.isEmpty()) {
                throw new IllegalArgumentException("Malformed niord.secom.renewal.k8s.secret-mounts entry '"
                        + entry + "' - expected <mount-directory>=<secret-name>");
            }
            mounts.put(FileKeystoreWriter.toPath(dir).normalize().toString(), secret);
        }
        return mounts;
    }

    /** {@inheritDoc} */
    @Override
    public boolean supports(String location) {
        return resolve(location) != null;
    }

    /** {@inheritDoc} */
    @Override
    public void write(String location, byte[] keystoreBytes) throws Exception {
        SecretEntry entry = resolve(location);
        if (entry == null) {
            throw new IllegalStateException("The store location " + location + " is not under a directory "
                    + "mapped by niord.secom.renewal.k8s.secret-mounts");
        }
        patchSecret(entry.secret(), Map.of(entry.key(), Base64.getEncoder().encodeToString(keystoreBytes)));
    }

    /** {@inheritDoc} - the write lands in the Secret; the mount the application reads converges via the kubelet */
    @Override
    public boolean deferredPickup() {
        return true;
    }

    private record SecretEntry(String secret, String key) {
    }

    /** Maps a store location onto its Secret name + key: parent directory lookup, file name = key */
    private SecretEntry resolve(String location) {
        if (!McpSecurityService.isFileBacked(location)) {
            return null;
        }
        Path path = FileKeystoreWriter.toPath(location).normalize();
        Path parent = path.getParent();
        if (parent == null || path.getFileName() == null) {
            return null;
        }
        String secret = mounts().get(parent.toString());
        return secret == null ? null : new SecretEntry(secret, path.getFileName().toString());
    }

    /**
     * PATCHes the named Secret's data entries (JSON merge patch: mentioned keys are replaced,
     * others untouched) and verifies the API's echo of the updated object actually carries the
     * new values. A 2xx response means the change is durable in etcd.
     */
    void patchSecret(String secretName, Map<String, String> base64Entries) throws Exception {
        String url = apiBase() + "/api/v1/namespaces/" + resolveNamespace() + "/secrets/" + secretName;

        ObjectNode patch = MAPPER.createObjectNode();
        ObjectNode data = patch.putObject("data");
        base64Entries.forEach(data::put);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/merge-patch+json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + readToken())
                .method("PATCH", HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(patch)))
                .build();
        log.info("PATCHing Kubernetes Secret '{}' ({})", secretName, String.join(", ", base64Entries.keySet()));
        HttpResponse<String> response = newClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Kubernetes API PATCH of Secret '" + secretName + "' failed with HTTP "
                    + response.statusCode() + ": " + CertificateRenewalUtils.abbreviate(response.body())
                    + (response.statusCode() == 401 || response.statusCode() == 403
                    ? " (the pod ServiceAccount needs get/patch on the SECOM Secrets)" : ""));
        }
        JsonNode echoed = MAPPER.readTree(response.body()).path("data");
        for (Map.Entry<String, String> entry : base64Entries.entrySet()) {
            if (!entry.getValue().equals(echoed.path(entry.getKey()).asText())) {
                throw new IllegalStateException("Kubernetes API PATCH of Secret '" + secretName
                        + "' did not persist the '" + entry.getKey() + "' entry");
            }
        }
    }

    /** The API server base URL: the configured override, or the standard in-cluster discovery env vars */
    String apiBase() {
        if (apiUrl != null && !apiUrl.isBlank()) {
            return StringUtils.removeEnd(apiUrl, "/");
        }
        String host = System.getenv("KUBERNETES_SERVICE_HOST");
        String port = System.getenv("KUBERNETES_SERVICE_PORT");
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Not running in a Kubernetes pod (KUBERNETES_SERVICE_HOST is unset) "
                    + "and niord.secom.renewal.k8s.api-url is not configured");
        }
        String hostPart = host.contains(":") ? "[" + host + "]" : host;
        return "https://" + hostPart + ":" + (port == null || port.isBlank() ? "443" : port);
    }

    private String resolveNamespace() throws Exception {
        if (namespace != null && !namespace.isBlank()) {
            return namespace.trim();
        }
        try {
            return Files.readString(Path.of(namespaceFile)).trim();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve the Secret namespace: niord.secom.renewal.k8s.namespace "
                    + "is not set and the ServiceAccount namespace file is unreadable: " + e.getMessage());
        }
    }

    /** Read freshly per call - in-cluster ServiceAccount tokens are projected and rotate */
    private String readToken() {
        try {
            return Files.readString(Path.of(tokenFile)).trim();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the ServiceAccount token at " + tokenFile
                    + ": " + e.getMessage());
        }
    }

    /** An HTTP client trusting exactly the API server CA bundle; plain {@code http:} skips TLS (IT stub path) */
    private HttpClient newClient() throws Exception {
        if (!apiBase().startsWith("https:")) {
            return HttpClient.newBuilder().build();
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        int i = 0;
        try (InputStream in = Files.newInputStream(Path.of(caFile))) {
            for (Certificate certificate : factory.generateCertificates(in)) {
                trust.setCertificateEntry("ca-" + (i++), certificate);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(context).build();
    }
}
