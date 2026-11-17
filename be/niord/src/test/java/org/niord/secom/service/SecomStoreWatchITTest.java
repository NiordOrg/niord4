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
import org.junit.jupiter.api.Test;
import org.niord.core.mail.ScheduledMail;
import org.niord.secom.SecomITTest;
import org.niord.secom.pki.McpSecurityService;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ITs for the scheduled store-file watch (mcp-identity-certificates-plan.md T2): an
 * in-place keystore rotation is picked up and hot-reloaded on the next poll with a
 * confirmation mail to the {@code secomAlertEmails} recipients, a broken store keeps the
 * previous material serving and alerts once (not once per poll), and an unchanged store
 * stays silent. The scheduled cron is disabled by the {@link SecomITTest} base, so the
 * tests drive {@code watch()} explicitly.
 * <p>
 * Boots against a keystore copied to a temp <em>file</em> (a {@code classpath:} store cannot
 * be overwritten), so this class forms its own cached application context, like
 * {@code SecomSecurityReloadITTest}. Each test restores the original keystore afterwards.
 */
class SecomStoreWatchITTest extends SecomITTest {

    /** The temp keystore file the app boots from; overwritten by the rotation tests */
    static final Path KEYSTORE_FILE = stagedStore("secom-watch-keystore", "/secom-test/keystore-test.p12");

    @DynamicPropertySource
    static void keystoreLocation(DynamicPropertyRegistry registry) {
        // Overrides the classpath location inherited from SecomITTest's @TestPropertySource
        registry.add("niord.secom.keystore.location", () -> "file:" + KEYSTORE_FILE);
    }

    @Autowired
    SecomStoreWatchService watchService;

    @Autowired
    McpSecurityService pki;

    @AfterEach
    void restoreOriginalKeystore() throws Exception {
        copyClasspathResource("/secom-test/keystore-test.p12", KEYSTORE_FILE);
        pki.reload();
        AopTestUtils.<SecomStoreWatchService>getTargetObject(watchService).lastFailure = null;
    }

    // ------------------------------------------------------------------------------------------- //

    @Test
    void watchReloadsARotatedKeystoreAndMailsTheConfirmation() {
        SecomStoreWatchService target = AopTestUtils.getTargetObject(watchService);
        String originalAlertEmails = target.alertEmails;
        try {
            target.alertEmails = "ops@example.com, watch@example.com";
            target.lastFailure = null;
            String originalFp = McpSecurityService.fingerprint(loadCert("/secom-test/keystore-test.p12"));
            String rotatedFp = McpSecurityService.fingerprint(loadCert("/secom-test/client-one.p12"));
            long mailsBefore = committedMailCount();

            // Unchanged stores: the poll must neither reload nor mail
            watchService.watch();
            assertEquals(mailsBefore, committedMailCount(), "an unchanged store queues no mail");
            given().auth().oauth2(accessToken())
                    .get("/rest/secom/status")
                    .then()
                    .statusCode(200)
                    .body("serviceCertificate.fingerprint", equalTo(originalFp));

            // Rotate: overwrite the keystore file in place (the k8s secret-update pattern)
            copyClasspathResource("/secom-test/client-one.p12", KEYSTORE_FILE);
            watchService.watch();

            given().auth().oauth2(accessToken())
                    .get("/rest/secom/status")
                    .then()
                    .statusCode(200)
                    .body("serviceCertificate.fingerprint", equalTo(rotatedFp));
            assertEquals(mailsBefore + 1, committedMailCount(), "the rotation queues one confirmation mail");
            Tx.requiringNew().run(() -> {
                ScheduledMail mail = newestMail();
                assertNotNull(mail);
                assertEquals("SECOM service certificate rotated (auto-reload)", mail.getSubject());
                assertEquals(2, mail.getRecipients().size(), "one recipient per configured address");
                assertEquals("ops@example.com", mail.getRecipients().get(0).getAddress());
                assertEquals("watch@example.com", mail.getRecipients().get(1).getAddress());
            });

            // The SECOM protocol keeps serving under the new identity (truststore unchanged)
            secomGiven().get("/api/secom/v2/capability").then().statusCode(200);

            // The rotation converges: the next poll neither reloads nor re-mails
            watchService.watch();
            assertEquals(mailsBefore + 1, committedMailCount(), "a converged rotation does not re-mail");
        } finally {
            target.alertEmails = originalAlertEmails;
        }
    }

    @Test
    void brokenStoreKeepsServingAndAlertsOncePerFailure() throws Exception {
        SecomStoreWatchService target = AopTestUtils.getTargetObject(watchService);
        String originalAlertEmails = target.alertEmails;
        try {
            target.alertEmails = "ops@example.com";
            target.lastFailure = null;
            String originalFp = McpSecurityService.fingerprint(loadCert("/secom-test/keystore-test.p12"));
            long mailsBefore = committedMailCount();

            Files.write(KEYSTORE_FILE, "not a keystore".getBytes());
            watchService.watch();

            // The previous material keeps serving...
            given().auth().oauth2(accessToken())
                    .get("/rest/secom/status")
                    .then()
                    .statusCode(200)
                    .body("serviceCertificate.fingerprint", equalTo(originalFp));
            given().get("/api/secom/v2/ping").then().statusCode(200);
            // ... and exactly one failure alert is queued
            assertEquals(mailsBefore + 1, committedMailCount(), "a broken store queues one failure alert");
            Tx.requiringNew().run(() -> {
                ScheduledMail mail = newestMail();
                assertNotNull(mail);
                assertTrue(mail.getSubject().contains("FAILED"), "unexpected subject: " + mail.getSubject());
            });
            assertNotNull(target.lastFailure, "the failure is recorded for dedup");

            watchService.watch();
            assertEquals(mailsBefore + 1, committedMailCount(), "the same failure does not alert again");

            // Restoring the loaded material converges silently and clears the failure state
            copyClasspathResource("/secom-test/keystore-test.p12", KEYSTORE_FILE);
            watchService.watch();
            assertNull(target.lastFailure, "a successful poll clears the failure dedup");
            assertEquals(mailsBefore + 1, committedMailCount(),
                    "restoring the already-loaded material is not a rotation");
        } finally {
            target.alertEmails = originalAlertEmails;
        }
    }

    @Test
    void blankRecipientsStillReloadButQueueNoMail() {
        SecomStoreWatchService target = AopTestUtils.getTargetObject(watchService);
        String originalAlertEmails = target.alertEmails;
        try {
            target.alertEmails = "";
            target.lastFailure = null;
            String rotatedFp = McpSecurityService.fingerprint(loadCert("/secom-test/client-one.p12"));
            long mailsBefore = committedMailCount();

            copyClasspathResource("/secom-test/client-one.p12", KEYSTORE_FILE);
            watchService.watch();

            given().auth().oauth2(accessToken())
                    .get("/rest/secom/status")
                    .then()
                    .statusCode(200)
                    .body("serviceCertificate.fingerprint", equalTo(rotatedFp));
            assertEquals(mailsBefore, committedMailCount(),
                    "with the (default) empty secomAlertEmails setting the reload still happens, silently");
        } finally {
            target.alertEmails = originalAlertEmails;
        }
    }

    private ScheduledMail newestMail() {
        return em.createQuery("select m from ScheduledMail m order by m.id desc", ScheduledMail.class)
                .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }
}
