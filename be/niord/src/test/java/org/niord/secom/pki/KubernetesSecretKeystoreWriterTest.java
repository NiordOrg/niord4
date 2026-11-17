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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.test.http.HttpStub;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for the k8s Secret-PATCH persistence backend (identity plan T3b): the
 * mount-directory-to-Secret mapping, the merge-patch request against a stub API server
 * ({@link HttpStub} - the plain-http path the real backend shares), the echo verification,
 * the RBAC failure hint, and the PEM publish of the proxy listener material. The end-to-end
 * renewal flow over this backend runs in {@code SecomCertificateRenewalK8sITTest}.
 */
public class KubernetesSecretKeystoreWriterTest {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A configured writer against the given API URL; namespace fixed, token from a file */
    static KubernetesSecretKeystoreWriter writer(String secretMounts, String apiUrl, Path tokenFile) {
        KubernetesSecretKeystoreWriter writer = new KubernetesSecretKeystoreWriter();
        writer.secretMounts = secretMounts;
        writer.apiUrl = apiUrl;
        writer.namespace = "unit-ns";
        writer.tokenFile = tokenFile.toString();
        writer.timeoutSeconds = 2;
        writer.init();
        return writer;
    }

    static Path tokenFile(Path dir, String token) throws Exception {
        Path file = dir.resolve("token");
        Files.writeString(file, token + "\n");   // the trailing newline must be trimmed away
        return file;
    }

    /** The base64 value of a data entry in the recorded PATCH body */
    static String patchedEntry(HttpStub.RecordedRequest request, String key) throws Exception {
        return JSON.readTree(request.bodyString()).path("data").path(key).asText();
    }

    // ------------------------------------------------------------------------------------------- //

    @Test
    public void parsesAndNormalizesTheSecretMountPairs() {
        Map<String, String> mounts = KubernetesSecretKeystoreWriter.parseSecretMounts(
                "file:/run/secrets/secom=niord-secom-stores, /run/secrets/secom-renewal/=niord-secom-renewal");
        assertEquals(Map.of(
                "/run/secrets/secom", "niord-secom-stores",
                "/run/secrets/secom-renewal", "niord-secom-renewal"), mounts);
        assertTrue(KubernetesSecretKeystoreWriter.parseSecretMounts("  ").isEmpty());
        assertTrue(KubernetesSecretKeystoreWriter.parseSecretMounts(null).isEmpty());
    }

    @Test
    public void misconfiguredMountsAlertAtUseNotAtBoot(@TempDir Path dir) throws Exception {
        for (String malformed : new String[] {"nonsense", "=niord-secom-stores", "/run/secrets/secom="}) {
            assertThrows(IllegalArgumentException.class,
                    () -> KubernetesSecretKeystoreWriter.parseSecretMounts(malformed), malformed);
        }
        // A misconfigured backend must not fail the application start (the renewal doctrine:
        // config problems become renewal-run alert mails) - it throws when used instead
        KubernetesSecretKeystoreWriter blank = writer("", "http://unused.invalid", tokenFile(dir, "t"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> blank.supports("/run/secrets/secom/keystore.p12"));
        assertTrue(e.getMessage().contains("maps no directories"), e.getMessage());

        KubernetesSecretKeystoreWriter malformed = writer("nonsense", "http://unused.invalid", tokenFile(dir, "t"));
        assertThrows(IllegalArgumentException.class,
                () -> malformed.write("/run/secrets/secom/keystore.p12", new byte[] {1}));
    }

    @Test
    public void supportsExactlyTheMappedDirectories(@TempDir Path dir) throws Exception {
        KubernetesSecretKeystoreWriter writer =
                writer("/mnt/stores=secret-a", "http://unused.invalid", tokenFile(dir, "t"));
        assertTrue(writer.supports("file:/mnt/stores/keystore.p12"));
        assertTrue(writer.supports("/mnt/stores/keystore.p12"));
        assertFalse(writer.supports("/mnt/stores/nested/keystore.p12"), "only the direct parent maps to a Secret");
        assertFalse(writer.supports("/mnt/elsewhere/keystore.p12"));
        assertFalse(writer.supports("classpath:secom-test/keystore-test.p12"));
        assertFalse(writer.supports(null));
    }

    @Test
    public void writesAreDeferredPickup(@TempDir Path dir) throws Exception {
        assertTrue(writer("/mnt=s", "http://unused.invalid", tokenFile(dir, "t")).deferredPickup(),
                "a Secret PATCH only reaches the mounted file after the kubelet sync");
    }

    @Test
    public void writePatchesTheMappedSecret(@TempDir Path dir) throws Exception {
        try (HttpStub api = HttpStub.start()) {
            api.respond(request -> HttpStub.StubResponse.json(request.bodyString()));   // a faithful API echo
            KubernetesSecretKeystoreWriter writer =
                    writer("/run/secrets/secom=niord-secom-stores", api.baseUrl(), tokenFile(dir, "s3cret"));
            byte[] keystoreBytes = {0x30, (byte) 0x82, 1, 2, 3};

            writer.write("file:/run/secrets/secom/keystore.p12", keystoreBytes);

            HttpStub.RecordedRequest request = api.lastRequest();
            assertEquals("PATCH", request.method());
            assertEquals("/api/v1/namespaces/unit-ns/secrets/niord-secom-stores", request.path());
            assertEquals("application/merge-patch+json", request.header("Content-Type"));
            assertEquals("Bearer s3cret", request.header("Authorization"), "token trimmed of the trailing newline");
            JsonNode data = JSON.readTree(request.bodyString()).path("data");
            assertEquals(1, data.size(), "the merge patch touches only the renewed key");
            assertArrayEquals(keystoreBytes, Base64.getDecoder().decode(data.path("keystore.p12").asText()));
        }
    }

    @Test
    public void theTokenIsReadFreshlyPerCall(@TempDir Path dir) throws Exception {
        try (HttpStub api = HttpStub.start()) {
            api.respond(request -> HttpStub.StubResponse.json(request.bodyString()));
            Path token = tokenFile(dir, "first");
            KubernetesSecretKeystoreWriter writer = writer("/mnt=s", api.baseUrl(), token);

            writer.write("/mnt/keystore.p12", new byte[] {1});
            Files.writeString(token, "rotated\n");   // projected ServiceAccount tokens rotate in place
            writer.write("/mnt/keystore.p12", new byte[] {2});

            assertEquals("Bearer first", api.requests().get(0).header("Authorization"));
            assertEquals("Bearer rotated", api.requests().get(1).header("Authorization"));
        }
    }

    @Test
    public void aForbiddenPatchCarriesTheRbacHint(@TempDir Path dir) throws Exception {
        try (HttpStub api = HttpStub.start()) {
            api.respondWith(HttpStub.StubResponse.text(403, "secrets \"niord-secom-stores\" is forbidden"));
            KubernetesSecretKeystoreWriter writer = writer("/mnt=s", api.baseUrl(), tokenFile(dir, "t"));

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> writer.write("/mnt/keystore.p12", new byte[] {1}));
            assertTrue(e.getMessage().contains("HTTP 403"), e.getMessage());
            assertTrue(e.getMessage().contains("ServiceAccount needs get/patch"),
                    "a 401/403 points at the Role, not at the MIR: " + e.getMessage());
        }
    }

    @Test
    public void anEchoMissingTheEntryFailsTheWrite(@TempDir Path dir) throws Exception {
        try (HttpStub api = HttpStub.start()) {
            api.respondJson("{\"kind\":\"Secret\",\"data\":{}}");   // 200, but the entry is not there
            KubernetesSecretKeystoreWriter writer = writer("/mnt=s", api.baseUrl(), tokenFile(dir, "t"));

            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> writer.write("/mnt/keystore.p12", new byte[] {1}));
            assertTrue(e.getMessage().contains("did not persist"), e.getMessage());
        }
    }

    @Test
    public void unmappedLocationsCannotBeWritten(@TempDir Path dir) throws Exception {
        KubernetesSecretKeystoreWriter writer =
                writer("/mnt=s", "http://unused.invalid", tokenFile(dir, "t"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> writer.write("/elsewhere/keystore.p12", new byte[] {1}));
        assertTrue(e.getMessage().contains("secret-mounts"), e.getMessage());
    }

    @Test
    public void theApiBaseComesFromTheOverrideOrInClusterDiscovery(@TempDir Path dir) throws Exception {
        KubernetesSecretKeystoreWriter writer = writer("/mnt=s", "http://api.example:6443/", tokenFile(dir, "t"));
        assertEquals("http://api.example:6443", writer.apiBase(), "trailing slash trimmed");

        // Outside a cluster the discovery env vars are absent - the writer must say so clearly
        assumeTrue(System.getenv("KUBERNETES_SERVICE_HOST") == null, "this test host is a Kubernetes pod");
        writer.apiUrl = "";
        IllegalStateException e = assertThrows(IllegalStateException.class, writer::apiBase);
        assertTrue(e.getMessage().contains("KUBERNETES_SERVICE_HOST"), e.getMessage());
    }
}
