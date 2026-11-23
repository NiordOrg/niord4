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

import org.niord.core.mail.ScheduledMail;
import org.niord.core.mail.ScheduledMailRecipient;
import org.niord.core.mail.ScheduledMailRecipient.RecipientType;
import org.niord.core.service.BaseService;
import org.niord.core.settings.annotation.Setting;
import org.niord.secom.pki.McpSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;

import static org.niord.core.settings.Setting.Type;

/**
 * Watches the SECOM service certificate for approaching expiry and queues warning mails to
 * the {@code secomAlertEmails} recipients — MCP service certificates are short-lived
 * (~6 months), and an expired certificate silently stops all SECOM traffic. Phase 1 of
 * {@code docs/mcp-identity-certificates-plan.md} (T1); the matching days-to-expiry gauge
 * lives in the deployable module's {@code SecomMetrics}.
 * <p>
 * Mails start when the expiry is within {@code secomCertExpiryWarnDays} (default 60 — the
 * G1183 "renew 2 months before expiry" point), escalate at {@value #CRITICAL_DAYS} days and
 * again on expiry, and repeat every {@value #REMIND_EVERY_DAYS} days within an unchanged
 * tier. The dedup state is in-memory only: a restart re-sends at most one mail.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class SecomCertificateAlertService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(SecomCertificateAlertService.class);

    static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;
    /** Escalation threshold (days) */
    static final int CRITICAL_DAYS = 14;
    /** Re-mail cadence within an unchanged tier (days) */
    static final int REMIND_EVERY_DAYS = 7;

    @Setting(value = "secomCertExpiryWarnDays", defaultValue = "60", type = Type.Integer,
            description = "Days before SECOM service certificate expiry at which warning mails to the"
                    + " secomAlertEmails recipients begin.")
    volatile Integer warnDays;

    @Setting(value = "secomAlertEmails", defaultValue = "",
            description = "Comma-separated recipients of the SECOM alert mails: a delivery that fails for good, the"
                    + " service certificate nearing expiry, a certificate renewal done or failed, and the key or trust"
                    + " store reloaded or failing to reload (empty = no alerts).")
    volatile String alertEmails;

    private final McpSecurityService pki;
    private final SecomTxRunner txRunner;

    public SecomCertificateAlertService(McpSecurityService pki, SecomTxRunner txRunner) {
        this.pki = pki;
        this.txRunner = txRunner;
    }

    /** Alert severity, in escalation order */
    enum Tier { OK, WARN, CRITICAL, EXPIRED }

    /** The last mailed alert — in-memory dedup state; a restart at most re-sends one mail */
    record LastAlert(String fingerprint, Tier tier, Date mailedAt) { }

    volatile LastAlert lastAlert;

    /** Daily SECOM service-certificate expiry check */
    @Scheduled(cron = "${niord.secom.cert-expiry-cron:41 17 6 * * ?}")
    public void checkAndAlert() {
        try {
            check(new Date());
        } catch (Exception e) {
            log.error("SECOM certificate expiry check failed", e);
        }
    }

    /** Runs the expiry check against the given "now" and queues the alert mail when due */
    void check(Date now) {
        X509Certificate cert = pki.serviceCertificate();
        long days = daysToExpiry(cert.getNotAfter(), now);
        Tier tier = tier(days, warnDays);
        if (tier == Tier.OK) {
            lastAlert = null;
            return;
        }
        String fingerprint = McpSecurityService.fingerprint(cert);
        // The mail is committed in its own transaction BEFORE the dedup marker advances -
        // a commit failure must retry on the next run, not suppress alerts for the cadence
        if (shouldMail(lastAlert, fingerprint, tier, now)
                && txRunner.call(() -> queueMail(cert, days, tier))) {
            lastAlert = new LastAlert(fingerprint, tier, now);
        }
    }

    /** Whole days until notAfter; floor semantics: 0 = expires within 24h, negative = expired */
    public static long daysToExpiry(Date notAfter, Date now) {
        return Math.floorDiv(notAfter.getTime() - now.getTime(), MILLIS_PER_DAY);
    }

    /** The warn window gates WARN/CRITICAL (a shrunk window is honored); expiry always alerts */
    static Tier tier(long days, int warnDays) {
        if (days < 0) {
            return Tier.EXPIRED;
        }
        if (days > warnDays) {
            return Tier.OK;
        }
        return days <= CRITICAL_DAYS ? Tier.CRITICAL : Tier.WARN;
    }

    /**
     * Mails on the first alert, on a certificate change, on tier escalation, and every
     * {@value #REMIND_EVERY_DAYS} days within an unchanged tier. De-escalation without a
     * certificate change cannot occur (time only moves forward), so it needs no branch.
     */
    static boolean shouldMail(LastAlert last, String fingerprint, Tier tier, Date now) {
        if (last == null || !last.fingerprint().equals(fingerprint) || tier.ordinal() > last.tier().ordinal()) {
            return true;
        }
        return tier == last.tier()
                && now.getTime() - last.mailedAt().getTime() >= REMIND_EVERY_DAYS * MILLIS_PER_DAY;
    }

    /**
     * Queues the expiry alert mail to the configured {@code secomAlertEmails} recipients.
     * Disabled when the setting is empty (the default). An alert problem must never break
     * the scheduled run, so failures are logged and swallowed; returns whether the mail was
     * queued, so the dedup state only records mails that actually exist. Called through
     * {@link SecomTxRunner}, so the mail is durably committed before the caller advances
     * the dedup marker.
     */
    private boolean queueMail(X509Certificate cert, long days, Tier tier) {
        // Read once: a save may change it at any time
        String alertEmails = this.alertEmails;
        if (alertEmails == null || alertEmails.isBlank()) {
            return false;
        }
        try {
            String subject = switch (tier) {
                case WARN -> "SECOM service certificate expires in " + days + " days";
                case CRITICAL -> "URGENT: SECOM service certificate expires in " + days + " days";
                default -> "SECOM service certificate EXPIRED " + Math.abs(days) + " day(s) ago";
            };
            saveEntity(alertMail(subject,
                    "<p>The SECOM service certificate "
                    + (tier == Tier.EXPIRED ? "has expired - all SECOM traffic is rejected until it is rotated."
                            : "is approaching expiry and should be rotated.")
                    + "</p>"
                    + "<ul>"
                    + "<li>Certificate: " + escapeHtml(cert.getSubjectX500Principal().getName()) + "</li>"
                    + "<li>Fingerprint: " + McpSecurityService.fingerprint(cert) + "</li>"
                    + "<li>Expires: " + cert.getNotAfter() + "</li>"
                    + "<li>Days to expiry: " + days + "</li>"
                    + "</ul>"
                    + "<p>Obtain a new certificate with fresh keys and install the rotated keystore - "
                    + "it is picked up automatically within a minute (the SECOM sysadmin page also has a "
                    + "manual Reload) - see the sysadmin manual appendix on SECOM certificates.</p>",
                    alertEmails));
            log.info("Queued SECOM certificate expiry {} alert ({} days) to {}", tier, days, alertEmails);
            return true;
        } catch (Exception e) {
            log.error("Failed to queue the SECOM certificate expiry alert mail: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Builds the alert mail every SECOM service sends: the shared {@code <html><body>} wrapper
     * around the given body, addressed to the comma-separated {@code secomAlertEmails} recipients.
     * Callers keep their own "no recipients configured" guard, their own log line and their own
     * swallow-and-continue policy - only the mail construction is shared.
     */
    static ScheduledMail alertMail(String subject, String htmlBody, String recipients) throws IOException {
        ScheduledMail mail = new ScheduledMail();
        mail.setSubject(subject);
        mail.setHtmlContents("<html><body>" + htmlBody + "</body></html>");
        Arrays.stream(recipients.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(address -> mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, address)));
        return mail;
    }

    static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
