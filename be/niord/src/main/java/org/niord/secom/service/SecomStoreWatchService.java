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

import org.niord.core.service.BaseService;
import org.niord.core.settings.annotation.Setting;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.pki.McpSecurityService.Rotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.cert.X509Certificate;

import static org.niord.secom.service.SecomCertificateAlertService.alertMail;
import static org.niord.secom.service.SecomCertificateAlertService.escapeHtml;

/**
 * Watches the on-disk SECOM keystore/truststore and hot-reloads the MCP key material when
 * their content changes — the convergence seam that makes any rotation mechanism (a k8s
 * secret updated in place, an external agent, the planned renewal job) take effect without
 * anyone calling the manual reload endpoint. Phase 2 of
 * {@code docs/mcp-identity-certificates-plan.md} (T2); the manual REST endpoint and
 * sysadmin-page button stay as the immediate, synchronously-verifiable path.
 * <p>
 * Runs every minute ({@code niord.secom.store-watch-cron}; {@code -} disables) and delegates
 * the byte-compare + atomic swap to {@link McpSecurityService#reloadIfChanged()}, so a failed
 * load keeps the current material serving. Reloads are confirmed by mail to the
 * {@code secomAlertEmails} recipients, and load failures alert once per distinct failure
 * rather than once per minute: the in-memory dedup marker advances only once the alert mail
 * is durably committed (or no recipients are configured), so a mail that cannot be queued is
 * retried on the next poll. {@code classpath:} stores cannot change on disk and are skipped
 * (restart-only).
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class SecomStoreWatchService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(SecomStoreWatchService.class);

    @Setting(value = "secomAlertEmails", defaultValue = "",
            description = "Comma-separated recipients of the SECOM alert mails: a delivery that fails for good, the"
                    + " service certificate nearing expiry, a certificate renewal done or failed, and the key or trust"
                    + " store reloaded or failing to reload (empty = no alerts).")
    volatile String alertEmails;

    private final McpSecurityService pki;
    private final SecomTxRunner txRunner;

    /** The last poll failure — in-memory dedup so a persistently broken store alerts once, not once per minute */
    volatile String lastFailure;

    public SecomStoreWatchService(McpSecurityService pki, SecomTxRunner txRunner) {
        this.pki = pki;
        this.txRunner = txRunner;
    }

    /** Scheduled store-file watch: reloads and alerts when the on-disk stores have changed */
    @Scheduled(cron = "${niord.secom.store-watch-cron:47 * * * * ?}")
    public void watch() {
        if (!pki.hasFileBackedStores()) {
            return;   // classpath: stores cannot be updated in place - restart-only, as documented
        }
        Rotation rotation;
        try {
            rotation = pki.reloadIfChanged();
        } catch (Exception e) {
            String failure = String.valueOf(e.getMessage());
            if (failure.equals(lastFailure)) {
                log.debug("SECOM store watch: store files still failing to load: {}", failure);
                return;
            }
            log.error("SECOM store watch: reloading the changed store files failed - keeping the current material", e);
            // The dedup marker only advances once the alert mail is committed (or no
            // recipients are configured) - a failed commit must retry on the next poll,
            // not suppress the alert (the SecomCertificateAlertService precedent)
            if (failureAlerted(failure)) {
                lastFailure = failure;
            }
            return;
        }
        lastFailure = null;
        if (rotation != null) {
            // Reload details are already logged by the PKI service; the confirmation mail is
            // best-effort - a rotation is a one-shot event with nothing to re-derive a retry from
            try {
                txRunner.call(() -> queueRotationMail(rotation));
            } catch (Exception e) {
                log.error("Failed to commit the SECOM reload confirmation mail: {}", e.getMessage());
            }
        }
    }

    /**
     * Queues the failure alert in its own committed transaction. Returns whether the failure
     * is fully handled: the mail exists durably, or there are no recipients to alert (the
     * default) - in which case there is nothing to retry and the single error log above is
     * the record. A queue/commit failure returns false, so the caller leaves the dedup
     * marker untouched and the alert is retried on the next poll.
     */
    private boolean failureAlerted(String failure) {
        if (alertEmails == null || alertEmails.isBlank()) {
            return true;
        }
        try {
            return txRunner.call(() -> queueFailureMail(failure));
        } catch (Exception e) {
            log.error("Failed to commit the SECOM store watch failure alert: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Queues the reload confirmation mail to the configured {@code secomAlertEmails}
     * recipients; disabled when the setting is empty (the default). An alert problem must
     * never break the scheduled run, so failures are logged and swallowed.
     */
    private boolean queueRotationMail(Rotation rotation) {
        String previousFingerprint = McpSecurityService.fingerprint(rotation.previousCertificate());
        String fingerprint = McpSecurityService.fingerprint(rotation.certificate());
        boolean certificateRotated = !fingerprint.equals(previousFingerprint);
        X509Certificate cert = rotation.certificate();
        return queueMail(certificateRotated
                        ? "SECOM service certificate rotated (auto-reload)"
                        : "SECOM key material reloaded (truststore updated)",
                "<p>The SECOM store files changed on disk and the key material was reloaded automatically. "
                        + "This mail is a confirmation - no action is needed.</p>"
                        + "<ul>"
                        + "<li>Certificate: " + escapeHtml(cert.getSubjectX500Principal().getName()) + "</li>"
                        + (certificateRotated ? "<li>Previous fingerprint: " + previousFingerprint + "</li>" : "")
                        + "<li>Fingerprint: " + fingerprint
                        + (certificateRotated ? "" : " (unchanged)") + "</li>"
                        + "<li>Expires: " + cert.getNotAfter() + "</li>"
                        + "</ul>"
                        + (certificateRotated
                        ? "<p>Exchange sets built from now on are catalogued under the new certificate. "
                                + "Published S-124 dataset versions keep the signatures they were published "
                                + "with and are served together with the certificate that made them.</p>"
                        : ""));
    }

    /** Queues the reload-failure alert mail; same recipient handling as the confirmation mail */
    private boolean queueFailureMail(String failure) {
        return queueMail("SECOM store reload FAILED - previous key material still active",
                "<p>The on-disk SECOM keystore/truststore could not be loaded. The previously "
                        + "loaded key material remains active and SECOM keeps serving with it, but a "
                        + "rotation you may just have rolled out has NOT taken effect.</p>"
                        + "<ul><li>Error: " + escapeHtml(failure) + "</li></ul>"
                        + "<p>Fix the store files (a changed store password requires a restart) - see the "
                        + "sysadmin manual appendix on SECOM certificates. This alert repeats only if the "
                        + "failure changes; the application log has one entry per failed check.</p>");
    }

    private boolean queueMail(String subject, String htmlBody) {
        if (alertEmails == null || alertEmails.isBlank()) {
            return false;
        }
        try {
            saveEntity(alertMail(subject, htmlBody, alertEmails));
            log.info("Queued SECOM store watch mail '{}' to {}", subject, alertEmails);
            return true;
        } catch (Exception e) {
            log.error("Failed to queue the SECOM store watch mail: {}", e.getMessage());
            return false;
        }
    }
}
