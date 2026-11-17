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

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.ScheduledMail;
import org.niord.secom.SecomITTest;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.service.SecomCertificateAlertService.LastAlert;
import org.niord.secom.service.SecomCertificateAlertService.Tier;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import java.util.Date;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ITs for the certificate expiry alerting (mcp-identity-certificates-plan.md T1): warning
 * mails queue once the expiry is within the warn window and deduplicate within a tier,
 * expiry escalation mails immediately, the OK tier stays silent and clears the dedup state,
 * and the days-to-expiry surfaces on both the status endpoint and the Micrometer gauge.
 * The committed test certificate is ~100 years from expiry, so the WARN tier is reached by
 * widening the warn window and the EXPIRED tier by checking against an artificial "now".
 */
class SecomCertificateAlertITTest extends SecomITTest {

    @Autowired
    SecomCertificateAlertService alertService;

    @Autowired
    McpSecurityService pki;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void warnWindowQueuesOneMailAndDeduplicates() {
        SecomCertificateAlertService target = AopTestUtils.getTargetObject(alertService);
        Integer originalWarnDays = target.warnDays;
        String originalAlertEmails = target.alertEmails;
        try {
            target.warnDays = 999_999;
            target.alertEmails = "ops@example.com, watch@example.com";
            target.lastAlert = null;
            long mailsBefore = committedMailCount();

            alertService.checkAndAlert();

            assertEquals(mailsBefore + 1, committedMailCount(), "entering the warn window queues one mail");
            Tx.requiringNew().run(() -> {
                ScheduledMail alert = newestMail("SECOM service certificate expires in");
                assertNotNull(alert);
                assertTrue(alert.getSubject().contains("SECOM service certificate expires in"),
                        "unexpected subject: " + alert.getSubject());
                assertEquals(2, alert.getRecipients().size(), "one recipient per configured address");
                assertEquals("ops@example.com", alert.getRecipients().get(0).getAddress());
                assertEquals("watch@example.com", alert.getRecipients().get(1).getAddress());
            });
            assertNotNull(target.lastAlert, "the mailed alert is recorded for dedup");
            assertEquals(Tier.WARN, target.lastAlert.tier());

            alertService.checkAndAlert();
            assertEquals(mailsBefore + 1, committedMailCount(), "same tier within the cadence is deduplicated");
        } finally {
            target.warnDays = originalWarnDays;
            target.alertEmails = originalAlertEmails;
            target.lastAlert = null;
        }
    }

    @Test
    void expiryEscalationMailsImmediately() {
        SecomCertificateAlertService target = AopTestUtils.getTargetObject(alertService);
        Integer originalWarnDays = target.warnDays;
        String originalAlertEmails = target.alertEmails;
        try {
            target.alertEmails = "ops@example.com";
            Date afterExpiry = new Date(pki.serviceCertificate().getNotAfter().getTime()
                    + 2 * SecomCertificateAlertService.MILLIS_PER_DAY);
            // A WARN alert mailed only yesterday - the cadence alone would stay silent
            target.lastAlert = new LastAlert(McpSecurityService.fingerprint(pki.serviceCertificate()),
                    Tier.WARN, new Date(afterExpiry.getTime() - SecomCertificateAlertService.MILLIS_PER_DAY));
            long mailsBefore = committedMailCount();

            // No surrounding transaction - the service commits the mail in its own
            target.check(afterExpiry);

            assertEquals(mailsBefore + 1, committedMailCount(), "tier escalation beats the reminder cadence");
            Tx.requiringNew().run(() -> {
                ScheduledMail alert = newestMail("EXPIRED");
                assertNotNull(alert);
                assertTrue(alert.getSubject().contains("EXPIRED"), "unexpected subject: " + alert.getSubject());
            });
            assertEquals(Tier.EXPIRED, target.lastAlert.tier());
        } finally {
            target.warnDays = originalWarnDays;
            target.alertEmails = originalAlertEmails;
            target.lastAlert = null;
        }
    }

    @Test
    void okTierQueuesNothingAndClearsState() {
        SecomCertificateAlertService target = AopTestUtils.getTargetObject(alertService);
        Integer originalWarnDays = target.warnDays;
        String originalAlertEmails = target.alertEmails;
        try {
            // Default window (60 days) vs the ~100-year test certificate -> OK
            target.warnDays = 60;
            target.alertEmails = "ops@example.com";
            target.lastAlert = new LastAlert("stale-fingerprint", Tier.WARN, new Date(0));
            long mailsBefore = committedMailCount();

            alertService.checkAndAlert();

            assertEquals(mailsBefore, committedMailCount(), "a healthy certificate queues no mail");
            assertNull(target.lastAlert, "the OK tier clears the dedup state");
        } finally {
            target.warnDays = originalWarnDays;
            target.alertEmails = originalAlertEmails;
            target.lastAlert = null;
        }
    }

    @Test
    void blankRecipientsQueueNoMailAndRecordNoState() {
        SecomCertificateAlertService target = AopTestUtils.getTargetObject(alertService);
        Integer originalWarnDays = target.warnDays;
        String originalAlertEmails = target.alertEmails;
        try {
            target.warnDays = 999_999;
            target.alertEmails = "";
            target.lastAlert = null;
            long mailsBefore = committedMailCount();

            alertService.checkAndAlert();

            assertEquals(mailsBefore, committedMailCount(),
                    "with the (default) empty secomAlertEmails setting no alert mail is queued");
            assertNull(target.lastAlert, "only actually queued mails are recorded for dedup");
        } finally {
            target.warnDays = originalWarnDays;
            target.alertEmails = originalAlertEmails;
            target.lastAlert = null;
        }
    }

    @Test
    void statusExposesDaysToExpiryAndTheGaugeServesIt() {
        // The committed test certificate is valid ~100 years (~36000+ days)
        given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(200)
                .body("serviceCertificate.daysToExpiry", greaterThan(30_000));

        assertTrue(meterRegistry.get("niord.secom.certificate.days-to-expiry").gauge().value() > 30_000,
                "the supplier-based gauge serves the days-to-expiry without a refresh sweep");
    }

    /**
     * The newest mail whose subject contains the given fragment. The filter matters: ids come
     * from a pooled sequence and every cached Spring test context holds its own allocation
     * block, so a mail queued from this (early-created) context can get a LOWER id than rows
     * other SECOM IT classes queued earlier from later-created contexts - a bare
     * "order by id desc" may surface a stale mail from another test class.
     */
    private ScheduledMail newestMail(String subjectFragment) {
        return em.createQuery("select m from ScheduledMail m where m.subject like :pattern order by m.id desc",
                        ScheduledMail.class)
                .setParameter("pattern", "%" + subjectFragment + "%")
                .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }
}
