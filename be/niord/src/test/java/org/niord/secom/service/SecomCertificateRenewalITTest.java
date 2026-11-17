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

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
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
 * ITs for the unattended certificate renewal (identity plan T3) against a stub MIR
 * ({@link HttpStub}) whose responder signs the app's actual CSR with an ephemeral in-test CA
 * ({@link TestCertificateAuthority}) - the committed test root's private key is deliberately
 * not committed, so honest chains over fresh keys cannot come from the fixtures. Boots
 * against temp-file copies of the service keystore, the robot credential (client-one
 * doubling as the robot) and a truststore extended with the ephemeral CA, so this class
 * forms its own cached application context (the {@code SecomStoreWatchITTest} precedent).
 * The renewal cron is disabled by the {@link SecomITTest} base; tests drive
 * {@code renew(now)} explicitly and every store is restored afterwards.
 */
class SecomCertificateRenewalITTest extends SecomITTest {

    static final String ORG_MRN = "urn:mrn:mcp:org:mcc:test";
    static final String SERVICE_MRN = "urn:mrn:mcp:service:mcc:test:instance:niord-test";
    /** client-one doubles as the robot credential */
    static final String ROBOT_MRN = CLIENT_MRN;
    static final String MINTED_ROBOT_MRN = "urn:mrn:mcp:user:mcc:test:renewal-robot";

    static final TestCertificateAuthority CA = TestCertificateAuthority.create("Niord Renewal Test CA");
    static final HttpStub MIR = startMir();
    static final Path SERVICE_KEYSTORE_FILE = stagedStore("renewal-service-keystore", "/secom-test/keystore-test.p12");
    static final Path ROBOT_KEYSTORE_FILE = stagedStore("renewal-robot-keystore", "/secom-test/client-one.p12");
    /** The committed truststore plus the ephemeral CA, so honestly issued chains anchor */
    static final Path TRUSTSTORE_FILE = CA.stagedTruststore("renewal-truststore");

    static HttpStub startMir() {
        try {
            return HttpStub.start();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start the stub MIR", e);
        }
    }

    @DynamicPropertySource
    static void renewalProperties(DynamicPropertyRegistry registry) {
        // Overrides the classpath store locations inherited from SecomITTest's @TestPropertySource
        registry.add("niord.secom.keystore.location", () -> "file:" + SERVICE_KEYSTORE_FILE);
        registry.add("niord.secom.truststore.location", () -> "file:" + TRUSTSTORE_FILE);
        registry.add("niord.secom.renewal.mir-url", MIR::baseUrl);
        registry.add("niord.secom.renewal.org-mrn", () -> ORG_MRN);
        // service-mrn / user-mrn stay blank: the asserted MIR URLs double as derivation proof
        registry.add("niord.secom.renewal.keystore.location", () -> "file:" + ROBOT_KEYSTORE_FILE);
        registry.add("niord.secom.renewal.keystore.password", () -> "changeit");
        registry.add("niord.secom.renewal.timeout-seconds", () -> "2");
    }

    @Autowired
    SecomCertificateRenewalService renewalService;

    @Autowired
    McpSecurityService pki;

    @BeforeEach
    void stageMir() throws Exception {
        // Install the fast default responder first, then drain: the stub is single-threaded,
        // so a responder a previous test left sleeping (the timeout test) would otherwise
        // stall - and time out - this test's first renewal call
        MIR.respond(SecomCertificateRenewalITTest::issueFromCsr);
        drainMir();
        MIR.reset();
    }

    /** Blocks until the single-threaded stub has worked through any handlers left by a previous test */
    static void drainMir() throws Exception {
        HttpClient probe = HttpClient.newBuilder().build();
        probe.send(HttpRequest.newBuilder().uri(URI.create(MIR.baseUrl() + "/drain")).GET().build(),
                HttpResponse.BodyHandlers.discarding());
    }

    /** The honest stub-MIR responder: signs the received CSR with the ephemeral CA */
    static HttpStub.StubResponse issueFromCsr(HttpStub.RecordedRequest request) {
        if (!request.path().endsWith("/certificate/issue-new/csr")) {
            return HttpStub.StubResponse.status(404);
        }
        return HttpStub.StubResponse.text(200,
                CA.signChainFromCsr(request.bodyString(), daysFromNow(-1), daysFromNow(180)));
    }

    @AfterEach
    void restoreStoresAndSettings() throws Exception {
        copyClasspathResource("/secom-test/keystore-test.p12", SERVICE_KEYSTORE_FILE);
        copyClasspathResource("/secom-test/client-one.p12", ROBOT_KEYSTORE_FILE);
        pki.reload();
        // Reset the @Setting fields to their annotation defaults and clear the dedup marker
        SecomCertificateRenewalService target = AopTestUtils.getTargetObject(renewalService);
        target.renewalEnabled = false;
        target.windowDays = 60;
        target.alertEmails = "";
        target.lastFailure = null;
    }

    /** Enables the renewal with recipients and the given window on the un-proxied bean */
    private SecomCertificateRenewalService enable(int windowDays) {
        SecomCertificateRenewalService target = AopTestUtils.getTargetObject(renewalService);
        target.renewalEnabled = true;
        target.windowDays = windowDays;
        target.alertEmails = "ops@example.com";
        target.lastFailure = null;
        return target;
    }

    // ------------------------------------------------------------------------------------------- //

    @Test
    void renewalIsOffByDefault() throws Exception {
        byte[] serviceBefore = Files.readAllBytes(SERVICE_KEYSTORE_FILE);
        byte[] robotBefore = Files.readAllBytes(ROBOT_KEYSTORE_FILE);
        String fingerprintBefore = McpSecurityService.fingerprint(pki.serviceCertificate());
        long mailsBefore = committedMailCount();

        renewalService.renewDueCertificates();

        assertEquals(0, MIR.requestCount(), "a disabled renewal must not contact the MIR");
        assertArrayEquals(serviceBefore, Files.readAllBytes(SERVICE_KEYSTORE_FILE));
        assertArrayEquals(robotBefore, Files.readAllBytes(ROBOT_KEYSTORE_FILE));
        assertEquals(fingerprintBefore, McpSecurityService.fingerprint(pki.serviceCertificate()));
        assertEquals(mailsBefore, committedMailCount());
    }

    @Test
    void happyPathRenewsRobotThenService() throws Exception {
        enable(999_999);   // the 100-year fixtures are "due" only with an absurd window
        String serviceFingerprintBefore = McpSecurityService.fingerprint(pki.serviceCertificate());
        String robotFingerprintBefore = fingerprintAt(ROBOT_KEYSTORE_FILE);
        long mailsBefore = committedMailCount();

        renewalService.renew(new Date());

        // Robot strictly first; the URLs also prove both MRNs were derived from the cert subjects
        List<HttpStub.RecordedRequest> requests = MIR.requests();
        assertEquals(2, requests.size());
        assertEquals("/x509/api/org/" + ORG_MRN + "/user/" + ROBOT_MRN + "/certificate/issue-new/csr",
                requests.get(0).path(), "the robot credential renews first - it authenticates the service renewal");
        assertEquals("/x509/api/org/" + ORG_MRN + "/service/" + SERVICE_MRN + "/certificate/issue-new/csr",
                requests.get(1).path());
        assertTrue(requests.get(0).bodyString().contains("BEGIN CERTIFICATE REQUEST"), "the body is a PEM CSR");

        // Both stores rotated on disk with alias + password preserved; the live material followed
        String robotFingerprintAfter = fingerprintAt(ROBOT_KEYSTORE_FILE);
        String serviceFingerprintAfter = fingerprintAt(SERVICE_KEYSTORE_FILE);
        assertNotEquals(robotFingerprintBefore, robotFingerprintAfter);
        assertNotEquals(serviceFingerprintBefore, serviceFingerprintAfter);
        assertEquals(serviceFingerprintAfter, McpSecurityService.fingerprint(pki.serviceCertificate()),
                "the in-memory material serves the renewed certificate");
        assertEquals(ROBOT_MRN, CertificateRenewalUtils.subjectMrn(certAt(ROBOT_KEYSTORE_FILE)),
                "the renewed robot credential keeps its subject MRN");

        given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(200)
                .body("serviceCertificate.fingerprint", equalTo(serviceFingerprintAfter));
        // The SECOM protocol keeps serving under the renewed identity
        secomGiven().get("/api/secom/v2/capability").then().statusCode(200);

        assertEquals(mailsBefore + 2, committedMailCount(), "one confirmation per renewed credential");
        Tx.requiringNew().run(() -> {
            List<ScheduledMail> newest = newestMails(2);
            assertEquals("SECOM service certificate renewed automatically", newest.get(0).getSubject());
            assertEquals("SECOM renewal robot credential renewed automatically", newest.get(1).getSubject());
            assertEquals("ops@example.com", newest.get(0).getRecipients().get(0).getAddress());
            assertFalse(htmlOf(newest.get(0)).contains("does not anchor"),
                    "an anchoring chain must not carry the truststore caveat");
        });
        assertNull(AopTestUtils.<SecomCertificateRenewalService>getTargetObject(renewalService).lastFailure);
    }

    @Test
    void renewNowIsSysadminOnly() {
        given().post("/rest/secom/security/renew").then().statusCode(401);
        given().auth().oauth2(accessToken("editor"))
                .post("/rest/secom/security/renew").then().statusCode(403);
        given().auth().oauth2(accessToken("admin"))
                .post("/rest/secom/security/renew").then().statusCode(403);
        assertEquals(0, MIR.requestCount(), "rejected calls must not reach the MIR");
    }

    @Test
    void renewNowRenewsBothIgnoringWindowAndDisabledSetting() throws Exception {
        // Renewal disabled + the 100-year fixtures outside any window: the scheduled path is a no-op
        AopTestUtils.<SecomCertificateRenewalService>getTargetObject(renewalService).alertEmails = "ops@example.com";
        renewalService.renewDueCertificates();
        assertEquals(0, MIR.requestCount(), "neither the setting nor the window trigger a scheduled renewal");

        String serviceFingerprintBefore = McpSecurityService.fingerprint(pki.serviceCertificate());
        String robotFingerprintBefore = fingerprintAt(ROBOT_KEYSTORE_FILE);
        long mailsBefore = committedMailCount();

        String renewedServiceFingerprint = given().auth().oauth2(accessToken())
                .post("/rest/secom/security/renew")
                .then()
                .statusCode(200)
                .body("renewed.credential", contains("robot", "service"))
                .body("renewed[0].mrn", equalTo(ROBOT_MRN))
                .body("renewed[0].previousFingerprint", equalTo(robotFingerprintBefore))
                .body("renewed[1].mrn", equalTo(SERVICE_MRN))
                .body("renewed[1].previousFingerprint", equalTo(serviceFingerprintBefore))
                .body("renewed[1].pendingPickup", equalTo(false))
                .body("failures", empty())
                .extract().path("renewed[1].fingerprint");

        // Both credentials rotated for real - on disk, in memory, and on the status endpoint
        assertEquals(2, MIR.requestCount());
        assertNotEquals(robotFingerprintBefore, fingerprintAt(ROBOT_KEYSTORE_FILE));
        assertEquals(renewedServiceFingerprint, fingerprintAt(SERVICE_KEYSTORE_FILE));
        assertEquals(renewedServiceFingerprint, McpSecurityService.fingerprint(pki.serviceCertificate()));
        given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(200)
                .body("serviceCertificate.fingerprint", equalTo(renewedServiceFingerprint));
        secomGiven().get("/api/secom/v2/capability").then().statusCode(200);
        assertEquals(mailsBefore + 2, committedMailCount(), "one confirmation per renewed credential");
    }

    @Test
    void renewNowSurfacesMirFailuresAs400() throws Exception {
        AopTestUtils.<SecomCertificateRenewalService>getTargetObject(renewalService).alertEmails = "ops@example.com";
        MIR.respondStatus(403);
        byte[] serviceBefore = Files.readAllBytes(SERVICE_KEYSTORE_FILE);
        byte[] robotBefore = Files.readAllBytes(ROBOT_KEYSTORE_FILE);
        String fingerprintBefore = McpSecurityService.fingerprint(pki.serviceCertificate());
        long mailsBefore = committedMailCount();

        given().auth().oauth2(accessToken())
                .post("/rest/secom/security/renew")
                .then()
                .statusCode(400)
                .body("detail", containsString("HTTP 403"));

        assertArrayEquals(serviceBefore, Files.readAllBytes(SERVICE_KEYSTORE_FILE));
        assertArrayEquals(robotBefore, Files.readAllBytes(ROBOT_KEYSTORE_FILE));
        assertEquals(fingerprintBefore, McpSecurityService.fingerprint(pki.serviceCertificate()));
        assertEquals(mailsBefore + 1, committedMailCount(), "the forced failure alerts like a scheduled one");
        assertNotNull(AopTestUtils.<SecomCertificateRenewalService>getTargetObject(renewalService).lastFailure);
    }

    @Test
    void robotSelfRenewalOnlyWhenTheServiceIsNotDue() throws Exception {
        // A 30-day robot identity (within the default 60-day window); the 100-year service cert is not due
        Files.write(ROBOT_KEYSTORE_FILE, CA.mintIdentity(MINTED_ROBOT_MRN, daysFromNow(-1), daysFromNow(30),
                "1", "changeit"));
        enable(60);
        byte[] serviceBefore = Files.readAllBytes(SERVICE_KEYSTORE_FILE);
        String serviceFingerprintBefore = McpSecurityService.fingerprint(pki.serviceCertificate());
        long mailsBefore = committedMailCount();

        renewalService.renew(new Date());

        assertEquals(1, MIR.requestCount(), "only the robot credential is due");
        assertEquals("/x509/api/org/" + ORG_MRN + "/user/" + MINTED_ROBOT_MRN + "/certificate/issue-new/csr",
                MIR.lastRequest().path());
        assertArrayEquals(serviceBefore, Files.readAllBytes(SERVICE_KEYSTORE_FILE), "the service keystore is untouched");
        assertEquals(serviceFingerprintBefore, McpSecurityService.fingerprint(pki.serviceCertificate()));
        assertEquals(MINTED_ROBOT_MRN, CertificateRenewalUtils.subjectMrn(certAt(ROBOT_KEYSTORE_FILE)));
        assertTrue(certAt(ROBOT_KEYSTORE_FILE).getNotAfter().after(daysFromNow(100)),
                "the robot credential was rotated to the freshly issued ~180-day certificate");
        assertEquals(mailsBefore + 1, committedMailCount());
        Tx.requiringNew().run(() ->
                assertEquals("SECOM renewal robot credential renewed automatically", newestMails(1).get(0).getSubject()));
    }

    @Test
    void renewalToleratesAMirMintedNotBefore() throws Exception {
        // The real MIR mints NotBefore at its own issuance instant - seconds AFTER the "now"
        // the renewal run captured (observed live against the MCC demonstrator, 2026-07-14)
        enable(999_999);
        MIR.respond(request -> request.path().endsWith("/certificate/issue-new/csr")
                ? HttpStub.StubResponse.text(200, CA.signChainFromCsr(request.bodyString(),
                        new Date(System.currentTimeMillis() + 30_000), daysFromNow(180)))
                : HttpStub.StubResponse.status(404));
        String fingerprintBefore = McpSecurityService.fingerprint(pki.serviceCertificate());

        renewalService.renew(new Date());

        assertNotEquals(fingerprintBefore, McpSecurityService.fingerprint(pki.serviceCertificate()),
                "a NotBefore minted seconds ahead by the MIR must not fail the renewal");
        assertNull(AopTestUtils.<SecomCertificateRenewalService>getTargetObject(renewalService).lastFailure);
    }

    @Test
    void mir403KeepsEverythingAndAlertsOncePerFailure() throws Exception {
        SecomCertificateRenewalService target = enable(999_999);
        MIR.respondStatus(403);
        byte[] serviceBefore = Files.readAllBytes(SERVICE_KEYSTORE_FILE);
        byte[] robotBefore = Files.readAllBytes(ROBOT_KEYSTORE_FILE);
        String fingerprintBefore = McpSecurityService.fingerprint(pki.serviceCertificate());
        long mailsBefore = committedMailCount();

        renewalService.renew(new Date());

        assertArrayEquals(serviceBefore, Files.readAllBytes(SERVICE_KEYSTORE_FILE));
        assertArrayEquals(robotBefore, Files.readAllBytes(ROBOT_KEYSTORE_FILE));
        assertEquals(fingerprintBefore, McpSecurityService.fingerprint(pki.serviceCertificate()));
        assertEquals(mailsBefore + 1, committedMailCount(), "one failure alert for the run");
        Tx.requiringNew().run(() -> {
            ScheduledMail mail = newestMails(1).get(0);
            assertTrue(mail.getSubject().contains("FAILED"), "unexpected subject: " + mail.getSubject());
            assertTrue(htmlOf(mail).contains("HTTP 403"));
            assertTrue(htmlOf(mail).contains("SERVICE_ADMIN"), "the 403 mail carries the role hint");
        });
        assertNotNull(target.lastFailure);

        renewalService.renew(new Date());
        assertEquals(mailsBefore + 1, committedMailCount(), "the same failure does not re-alert");

        // Recovery: the MIR works again - both credentials renew and the dedup clears
        MIR.respond(SecomCertificateRenewalITTest::issueFromCsr);
        renewalService.renew(new Date());
        assertEquals(mailsBefore + 3, committedMailCount(), "two success confirmations after recovery");
        assertNull(target.lastFailure);
    }

    @Test
    void mir5xxAlertsPerDistinctFailure() throws Exception {
        enable(999_999);
        MIR.respondWith(HttpStub.StubResponse.text(500, "registry down"));
        long mailsBefore = committedMailCount();

        renewalService.renew(new Date());
        assertEquals(mailsBefore + 1, committedMailCount());

        renewalService.renew(new Date());
        assertEquals(mailsBefore + 1, committedMailCount(), "the unchanged failure stays deduped");

        MIR.respondWith(HttpStub.StubResponse.text(500, "still down, but differently"));
        renewalService.renew(new Date());
        assertEquals(mailsBefore + 2, committedMailCount(), "a distinct failure alerts again");
    }

    @Test
    void malformedOrWrongKeyChainsAreRejectedBeforePersist() throws Exception {
        SecomCertificateRenewalService target = enable(999_999);
        byte[] serviceBefore = Files.readAllBytes(SERVICE_KEYSTORE_FILE);
        byte[] robotBefore = Files.readAllBytes(ROBOT_KEYSTORE_FILE);
        long mailsBefore = committedMailCount();

        // Not a certificate chain at all
        MIR.respondWith(HttpStub.StubResponse.text(200, "this is not a pem chain"));
        renewalService.renew(new Date());
        assertArrayEquals(serviceBefore, Files.readAllBytes(SERVICE_KEYSTORE_FILE));
        assertArrayEquals(robotBefore, Files.readAllBytes(ROBOT_KEYSTORE_FILE));
        assertEquals(mailsBefore + 1, committedMailCount());

        // A well-formed chain signed over a key that is NOT the CSR's fresh key
        target.lastFailure = null;
        MIR.respond(request -> HttpStub.StubResponse.text(200, CA.signChainForOtherKey(ROBOT_MRN)));
        renewalService.renew(new Date());
        assertArrayEquals(serviceBefore, Files.readAllBytes(SERVICE_KEYSTORE_FILE));
        assertArrayEquals(robotBefore, Files.readAllBytes(ROBOT_KEYSTORE_FILE));
        assertEquals(mailsBefore + 2, committedMailCount());
        Tx.requiringNew().run(() -> assertTrue(htmlOf(newestMails(1).get(0)).contains("freshly generated key pair"),
                "the failure names the fresh-key gate"));
    }

    @Test
    void slowMirTimesOutWithoutTouchingTheStores() throws Exception {
        enable(999_999);
        MIR.respond(request -> {
            try {
                Thread.sleep(4000);   // the client gives up after the configured 2s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HttpStub.StubResponse.status(200);
        });
        byte[] serviceBefore = Files.readAllBytes(SERVICE_KEYSTORE_FILE);
        byte[] robotBefore = Files.readAllBytes(ROBOT_KEYSTORE_FILE);
        long mailsBefore = committedMailCount();

        renewalService.renew(new Date());

        assertArrayEquals(serviceBefore, Files.readAllBytes(SERVICE_KEYSTORE_FILE));
        assertArrayEquals(robotBefore, Files.readAllBytes(ROBOT_KEYSTORE_FILE));
        assertEquals(mailsBefore + 1, committedMailCount(), "a timeout is a normal, alerted failure");
        Tx.requiringNew().run(() ->
                assertTrue(newestMails(1).get(0).getSubject().contains("FAILED")));
    }

    @Test
    void chainFromAnUnknownCaStillRenewsWithATruststoreWarning() throws Exception {
        enable(999_999);
        TestCertificateAuthority unknownCa = TestCertificateAuthority.create("Unknown Provider CA");
        MIR.respond(request -> request.path().endsWith("/certificate/issue-new/csr")
                ? HttpStub.StubResponse.text(200,
                        unknownCa.signChainFromCsr(request.bodyString(), daysFromNow(-1), daysFromNow(180)))
                : HttpStub.StubResponse.status(404));
        String fingerprintBefore = McpSecurityService.fingerprint(pki.serviceCertificate());
        long mailsBefore = committedMailCount();

        renewalService.renew(new Date());

        assertNotEquals(fingerprintBefore, McpSecurityService.fingerprint(pki.serviceCertificate()),
                "the truststore anchoring check is soft and must not block the renewal");
        assertEquals(mailsBefore + 2, committedMailCount());
        Tx.requiringNew().run(() -> {
            ScheduledMail serviceMail = newestMails(2).stream()
                    .filter(m -> "SECOM service certificate renewed automatically".equals(m.getSubject()))
                    .findFirst().orElse(null);
            assertNotNull(serviceMail, "the service renewal queued its confirmation");
            assertTrue(htmlOf(serviceMail).contains("does not anchor"),
                    "the confirmation mail carries the truststore caveat");
        });
        assertNull(AopTestUtils.<SecomCertificateRenewalService>getTargetObject(renewalService).lastFailure);
    }

    @Test
    void underivableRobotMrnAlertsOnceWithoutMirTraffic() throws Exception {
        // A robot identity whose subject carries no UID - the user MRN cannot be derived
        Files.write(ROBOT_KEYSTORE_FILE, CA.mintIdentity(null, daysFromNow(-1), daysFromNow(30), "1", "changeit"));
        enable(999_999);
        long mailsBefore = committedMailCount();

        renewalService.renew(new Date());

        assertEquals(0, MIR.requestCount(), "configuration problems fail the run before any MIR traffic");
        assertEquals(mailsBefore + 1, committedMailCount());
        Tx.requiringNew().run(() -> assertTrue(htmlOf(newestMails(1).get(0)).contains("user-mrn"),
                "the alert names the missing configuration"));

        renewalService.renew(new Date());
        assertEquals(mailsBefore + 1, committedMailCount(), "the unchanged misconfiguration stays deduped");
    }

    // ------------------------------------------------------------------------------------------- //

    static X509Certificate certAt(Path store) {
        try {
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) {
                keystore.load(in, "changeit".toCharArray());
            }
            return (X509Certificate) keystore.getCertificate("1");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + store, e);
        }
    }

    static String fingerprintAt(Path store) {
        return McpSecurityService.fingerprint(certAt(store));
    }

    private List<ScheduledMail> newestMails(int count) {
        return em.createQuery("select m from ScheduledMail m order by m.id desc", ScheduledMail.class)
                .setMaxResults(count).getResultList();
    }
}
