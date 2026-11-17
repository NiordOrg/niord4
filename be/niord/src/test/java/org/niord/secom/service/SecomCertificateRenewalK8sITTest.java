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
package org.niord.secom.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.ScheduledMail;
import org.niord.secom.SecomITTest;
import org.niord.secom.pki.CertificateRenewalUtils;
import org.niord.secom.pki.McpSecurityService;
import org.niord.test.Tx;
import org.niord.test.http.HttpStub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.niord.secom.pki.TestCertificateAuthority;
import static org.niord.secom.pki.TestCertificateAuthority.daysFromNow;

/**
 * ITs for the k8s Secret-PATCH renewal backend (identity plan T3b): the same stub-MIR renewal
 * flow as {@code SecomCertificateRenewalITTest}, but persisted through
 * {@code KubernetesSecretKeystoreWriter} against a second {@link HttpStub} playing the
 * Kubernetes API server (the plain-http path of the real client). Proves the deferred-pickup
 * contract end to end: the Secrets receive the renewed material and the proxy PEMs, the
 * mounted files stay untouched, the in-memory material keeps serving the old certificate,
 * and a simulated kubelet sync + store-watch poll ({@code reloadIfChanged}) completes the
 * rotation. Forms its own cached application context via {@code @DynamicPropertySource}.
 */
class SecomCertificateRenewalK8sITTest extends SecomITTest {

    static final String ORG_MRN = "urn:mrn:mcp:org:mcc:test";
    static final String SERVICE_MRN = "urn:mrn:mcp:service:mcc:test:instance:niord-test";
    /** client-one doubles as the robot credential */
    static final String ROBOT_MRN = CLIENT_MRN;

    static final String NAMESPACE = "niord-test";
    static final String STORES_SECRET = "niord-secom-stores";
    static final String RENEWAL_SECRET = "niord-secom-renewal";

    static final ObjectMapper JSON = new ObjectMapper();
    /** Distinct recipient: mail assertions filter on it, immune to other contexts' ScheduledMail rows */
    static final String RECIPIENT = "ops-k8s@example.com";
    static final TestCertificateAuthority CA = TestCertificateAuthority.create("Niord K8s Renewal Test CA");
    static final HttpStub MIR = startStub();
    static final HttpStub K8S = startStub();

    // The mount-directory-to-Secret mapping is per parent directory, so each store gets its
    // own temp directory - mirroring the two Secret volume mounts of the chart
    static final Path SERVICE_KEYSTORE_FILE = staged("k8s-secom-stores", "keystore.p12", "/secom-test/keystore-test.p12");
    static final Path ROBOT_KEYSTORE_FILE = staged("k8s-secom-renewal", "robot.p12", "/secom-test/client-one.p12");
    /** The committed truststore plus the ephemeral CA, so honestly issued chains anchor */
    static final Path TRUSTSTORE_FILE = CA.stagedTruststore("k8s-renewal-truststore");
    static final Path TOKEN_FILE = stagedToken();

    static HttpStub startStub() {
        try {
            return HttpStub.start();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start the stub server", e);
        }
    }

    /** Stages a classpath store under a fixed file name in its own temp directory (= Secret key) */
    static Path staged(String dirPrefix, String fileName, String resource) {
        try {
            Path dir = Files.createTempDirectory(dirPrefix);
            dir.toFile().deleteOnExit();
            Path file = dir.resolve(fileName);
            file.toFile().deleteOnExit();
            copyClasspathResource(resource, file);
            return file;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot stage " + resource, e);
        }
    }

    static Path stagedToken() {
        try {
            Path file = Files.createTempFile("k8s-renewal-token", ".txt");
            file.toFile().deleteOnExit();
            Files.writeString(file, "test-token\n");
            return file;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot stage the ServiceAccount token", e);
        }
    }

    @DynamicPropertySource
    static void renewalProperties(DynamicPropertyRegistry registry) {
        // Overrides the classpath store locations inherited from SecomITTest's @TestPropertySource
        registry.add("niord.secom.keystore.location", () -> "file:" + SERVICE_KEYSTORE_FILE);
        registry.add("niord.secom.truststore.location", () -> "file:" + TRUSTSTORE_FILE);
        registry.add("niord.secom.renewal.mir-url", MIR::baseUrl);
        registry.add("niord.secom.renewal.org-mrn", () -> ORG_MRN);
        registry.add("niord.secom.renewal.keystore.location", () -> "file:" + ROBOT_KEYSTORE_FILE);
        registry.add("niord.secom.renewal.keystore.password", () -> "changeit");
        registry.add("niord.secom.renewal.timeout-seconds", () -> "2");
        // The k8s Secret-PATCH backend against the stub API server (plain http, like the stub MIR)
        registry.add("niord.secom.renewal.k8s.enabled", () -> "true");
        registry.add("niord.secom.renewal.k8s.api-url", K8S::baseUrl);
        registry.add("niord.secom.renewal.k8s.namespace", () -> NAMESPACE);
        registry.add("niord.secom.renewal.k8s.token-file", () -> TOKEN_FILE.toString());
        registry.add("niord.secom.renewal.k8s.secret-mounts", () ->
                SERVICE_KEYSTORE_FILE.getParent() + "=" + STORES_SECRET + ","
                        + ROBOT_KEYSTORE_FILE.getParent() + "=" + RENEWAL_SECRET);
    }

    @Autowired
    SecomCertificateRenewalService renewalService;

    @Autowired
    McpSecurityService pki;

    @BeforeEach
    void stageStubs() {
        MIR.respond(SecomCertificateRenewalK8sITTest::issueFromCsr);
        MIR.reset();
        K8S.respond(SecomCertificateRenewalK8sITTest::patchEcho);
        K8S.reset();
    }

    /** The honest stub-MIR responder: signs the received CSR with the ephemeral CA */
    static HttpStub.StubResponse issueFromCsr(HttpStub.RecordedRequest request) {
        if (!request.path().endsWith("/certificate/issue-new/csr")) {
            return HttpStub.StubResponse.status(404);
        }
        return HttpStub.StubResponse.text(200,
                CA.signChainFromCsr(request.bodyString(), daysFromNow(-1), daysFromNow(180)));
    }

    /** The honest stub-API responder: a PATCHed Secret is echoed back with its updated data */
    static HttpStub.StubResponse patchEcho(HttpStub.RecordedRequest request) {
        if (!"PATCH".equals(request.method())
                || !request.path().startsWith("/api/v1/namespaces/" + NAMESPACE + "/secrets/")) {
            return HttpStub.StubResponse.status(404);
        }
        return HttpStub.StubResponse.json(request.bodyString());
    }

    @AfterEach
    void restoreStoresAndSettings() throws Exception {
        copyClasspathResource("/secom-test/keystore-test.p12", SERVICE_KEYSTORE_FILE);
        copyClasspathResource("/secom-test/client-one.p12", ROBOT_KEYSTORE_FILE);
        pki.reload();
        SecomCertificateRenewalService target = AopTestUtils.getTargetObject(renewalService);
        target.renewalEnabled = false;
        target.windowDays = 60;
        target.alertEmails = "";
        target.lastFailure = null;
        // The restored stores hold the certificates the tests rotated away - clear the
        // deferred-pickup markers so the next test's renewal is not paused as "stalled"
        target.pendingPickups.clear();
    }

    /** Enables the renewal with recipients and the given window on the un-proxied bean */
    private SecomCertificateRenewalService enable(int windowDays) {
        SecomCertificateRenewalService target = AopTestUtils.getTargetObject(renewalService);
        target.renewalEnabled = true;
        target.windowDays = windowDays;
        target.alertEmails = RECIPIENT;
        target.lastFailure = null;
        return target;
    }

    // ------------------------------------------------------------------------------------------- //

    @Test
    void renewalPatchesTheSecretsAndLeavesTheMountsToTheKubelet() throws Exception {
        enable(999_999);   // the 100-year fixtures are "due" only with an absurd window
        byte[] serviceMountBefore = Files.readAllBytes(SERVICE_KEYSTORE_FILE);
        byte[] robotMountBefore = Files.readAllBytes(ROBOT_KEYSTORE_FILE);
        String serviceFingerprintBefore = McpSecurityService.fingerprint(pki.serviceCertificate());
        String robotFingerprintBefore = McpSecurityService.fingerprint(certOf(robotMountBefore));
        long mailsBefore = committedMailCountForRecipient();

        renewalService.renew(new Date());

        // Robot Secret first (it authenticates the service renewal), then the stores
        List<HttpStub.RecordedRequest> patches = K8S.requests();
        assertEquals(2, patches.size());
        assertEquals("/api/v1/namespaces/" + NAMESPACE + "/secrets/" + RENEWAL_SECRET, patches.get(0).path());
        assertEquals("/api/v1/namespaces/" + NAMESPACE + "/secrets/" + STORES_SECRET, patches.get(1).path());
        assertEquals("PATCH", patches.get(0).method());
        assertEquals("application/merge-patch+json", patches.get(0).header("Content-Type"));
        assertEquals("Bearer test-token", patches.get(0).header("Authorization"));

        // Each PATCH is a single-key merge on the mounted file's name; the payloads are the
        // renewed, loadable stores with alias and password preserved
        byte[] renewedRobot = singleDataEntry(patches.get(0), "robot.p12");
        byte[] renewedService = singleDataEntry(patches.get(1), "keystore.p12");
        assertEquals(ROBOT_MRN, CertificateRenewalUtils.subjectMrn(certOf(renewedRobot)));
        assertEquals(SERVICE_MRN, CertificateRenewalUtils.subjectMrn(certOf(renewedService)));
        assertNotEquals(robotFingerprintBefore, McpSecurityService.fingerprint(certOf(renewedRobot)));
        String renewedFingerprint = McpSecurityService.fingerprint(certOf(renewedService));
        assertNotEquals(serviceFingerprintBefore, renewedFingerprint);

        // Deferred pickup: the mounted files and the in-memory material are untouched for now
        assertArrayEquals(serviceMountBefore, Files.readAllBytes(SERVICE_KEYSTORE_FILE));
        assertArrayEquals(robotMountBefore, Files.readAllBytes(ROBOT_KEYSTORE_FILE));
        assertEquals(serviceFingerprintBefore, McpSecurityService.fingerprint(pki.serviceCertificate()),
                "the in-memory material serves the old certificate until the store watch converges");

        assertEquals(mailsBefore + 2, committedMailCountForRecipient(), "one confirmation per renewed credential");
        Tx.requiringNew().run(() -> {
            List<ScheduledMail> newest = newestMails(2);
            assertEquals("SECOM service certificate renewed automatically", newest.get(0).getSubject());
            assertEquals("SECOM renewal robot credential renewed automatically", newest.get(1).getSubject());
            assertTrue(htmlOf(newest.get(0)).contains("Secret propagation"),
                    "the confirmation explains the deferred pickup");
            assertFalse(htmlOf(newest.get(0)).contains("does not anchor"),
                    "an anchoring chain must not carry the truststore caveat");
        });
        assertNull(AopTestUtils.<SecomCertificateRenewalService>getTargetObject(renewalService).lastFailure);

        // The kubelet syncs the Secret into the mount; the next store-watch poll completes the rotation
        Files.write(SERVICE_KEYSTORE_FILE, renewedService);
        assertNotNull(pki.reloadIfChanged(), "the store watch sees the synced mount as a rotation");
        assertEquals(renewedFingerprint, McpSecurityService.fingerprint(pki.serviceCertificate()),
                "after the simulated kubelet sync the renewed certificate is live");
    }

    @Test
    void forbiddenSecretPatchAlertsWithTheRbacHintNotTheMirHint() throws Exception {
        SecomCertificateRenewalService target = enable(999_999);
        K8S.respondWith(HttpStub.StubResponse.text(403, "secrets is forbidden: User cannot patch resource"));
        byte[] serviceMountBefore = Files.readAllBytes(SERVICE_KEYSTORE_FILE);
        String fingerprintBefore = McpSecurityService.fingerprint(pki.serviceCertificate());
        long mailsBefore = committedMailCountForRecipient();

        renewalService.renew(new Date());

        assertEquals(2, MIR.requestCount(), "both credentials were issued before their writes failed");
        assertArrayEquals(serviceMountBefore, Files.readAllBytes(SERVICE_KEYSTORE_FILE));
        assertEquals(fingerprintBefore, McpSecurityService.fingerprint(pki.serviceCertificate()));
        assertEquals(mailsBefore + 1, committedMailCountForRecipient(), "one failure alert for the run");
        Tx.requiringNew().run(() -> {
            ScheduledMail mail = newestMails(1).get(0);
            assertTrue(mail.getSubject().contains("FAILED"), "unexpected subject: " + mail.getSubject());
            assertTrue(htmlOf(mail).contains("ServiceAccount needs get/patch"),
                    "a Secret-PATCH 403 points at the Role");
            assertFalse(htmlOf(mail).contains("SERVICE_ADMIN"),
                    "the MIR role hint must not fire for a Kubernetes RBAC problem");
        });
        assertNotNull(target.lastFailure);

        // Recovery: the RBAC is fixed - both credentials renew and the dedup clears
        K8S.respond(SecomCertificateRenewalK8sITTest::patchEcho);
        renewalService.renew(new Date());
        assertEquals(mailsBefore + 3, committedMailCountForRecipient(), "two success confirmations after recovery");
        assertNull(target.lastFailure);
    }

    @Test
    void anEchoMissingTheEntryFailsTheRenewalBeforeAnythingIsTrusted() throws Exception {
        enable(999_999);
        K8S.respondJson("{\"kind\":\"Secret\",\"data\":{}}");   // 200, but the entry did not land
        byte[] serviceMountBefore = Files.readAllBytes(SERVICE_KEYSTORE_FILE);
        long mailsBefore = committedMailCountForRecipient();

        renewalService.renew(new Date());

        assertArrayEquals(serviceMountBefore, Files.readAllBytes(SERVICE_KEYSTORE_FILE));
        assertEquals(mailsBefore + 1, committedMailCountForRecipient());
        Tx.requiringNew().run(() -> assertTrue(htmlOf(newestMails(1).get(0)).contains("did not persist"),
                "the alert names the unpersisted entry"));
    }

    // ------------------------------------------------------------------------------------------- //

    /** Asserts the PATCH body carries exactly one data entry under the given key and returns its bytes */
    static byte[] singleDataEntry(HttpStub.RecordedRequest request, String key) throws Exception {
        JsonNode data = JSON.readTree(request.bodyString()).path("data");
        assertEquals(1, data.size(), "a single-key merge patch, but got: " + data);
        assertTrue(data.has(key), "expected data entry '" + key + "' in: " + data);
        return Base64.getDecoder().decode(data.path(key).asText());
    }

    static X509Certificate certOf(byte[] p12) {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(new ByteArrayInputStream(p12), "changeit".toCharArray());
            return (X509Certificate) keystore.getCertificate("1");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse the keystore bytes", e);
        }
    }

    private long committedMailCountForRecipient() {
        return Tx.requiringNew().call(() ->
                em.createQuery("select count(m) from ScheduledMail m join m.recipients r where r.address = :address",
                                Long.class)
                        .setParameter("address", RECIPIENT).getSingleResult());
    }

    private List<ScheduledMail> newestMails(int count) {
        return em.createQuery("select m from ScheduledMail m join m.recipients r where r.address = :address "
                                + "order by m.id desc", ScheduledMail.class)
                .setParameter("address", RECIPIENT)
                .setMaxResults(count).getResultList();
    }
}
