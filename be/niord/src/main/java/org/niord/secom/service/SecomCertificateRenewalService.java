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

import org.apache.commons.lang3.StringUtils;
import org.niord.core.service.BaseService;
import org.niord.core.settings.annotation.Setting;
import org.niord.secom.pki.CertificateRenewalUtils;
import org.niord.secom.pki.KeystoreWriter;
import org.niord.secom.pki.McpMirClient;
import org.niord.secom.pki.McpSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.niord.core.settings.Setting.Type;
import static org.niord.secom.service.SecomCertificateAlertService.alertMail;
import static org.niord.secom.service.SecomCertificateAlertService.daysToExpiry;
import static org.niord.secom.service.SecomCertificateAlertService.escapeHtml;

/**
 * Unattended renewal of the SECOM/MCP certificates against the MCP Identity Registry -
 * identity plan T3, killing gap G2 (the recurring manual issuance ceremony). Daily, when the
 * service certificate (or the renewal robot credential) is within
 * {@code secomRenewalWindowDays} of expiry: generate a fresh EC P-384 key pair, send a
 * PKCS#10 CSR to the MIR ({@code issue-new/csr}, authenticated with the robot-user mTLS
 * credential), verify the issued chain, assemble a PKCS12 keystore with the configured alias
 * and password, persist it through the {@link KeystoreWriter} backend, hot-reload (or, for a
 * deferred-pickup backend like the k8s Secret PATCH, leave the pickup to the store watch),
 * and mail a confirmation to the {@code secomAlertEmails} recipients.
 * <p>
 * Default-off twice over: the SECOM beans require {@code niord.secom.enabled}, and every run
 * additionally checks the {@code secomRenewalEnabled} setting (default false). Nothing is
 * mutated until an issued chain has passed the hard gates (fresh-key match, validity, MRN
 * match, chain links) and the assembled bytes are proven loadable; failures leave the current
 * material serving, alert once per distinct failure (commit-before-marker, the T1/T2 rule)
 * and retry on the next run, while the T1 expiry alerts keep escalating independently. The
 * robot credential renews strictly before the service certificate - it is the credential
 * that authenticates the service renewal.
 * <p>
 * Deliberately non-transactional ({@code NOT_SUPPORTED}, overriding the inherited
 * {@link BaseService} class-level {@code @Transactional}): a renewal run holds no database
 * work of its own - only slow MIR/Kubernetes network I/O that must not pin a connection -
 * and every mail is committed through its own {@link SecomTxRunner} transaction.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@SuppressWarnings("unused")
public class SecomCertificateRenewalService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(SecomCertificateRenewalService.class);

    @Setting(value = "secomRenewalEnabled", defaultValue = "false", type = Type.Boolean,
            description = "Enables unattended SECOM certificate renewal via the MCP Identity Registry. Needs niord.secom.renewal.mir-url, "
                    + ".org-mrn, .keystore.location and .keystore.password (optional .service-mrn/.user-mrn default to the certificate UIDs)."
                    + " Read at start-up.")
    Boolean renewalEnabled;

    @Setting(value = "secomRenewalWindowDays", defaultValue = "60", type = Type.Integer,
            description = "Days before expiry at which the SECOM service and renewal-robot certificates are renewed"
                    + " automatically. Read at start-up.")
    Integer windowDays;

    @Setting(value = "secomAlertEmails", defaultValue = "",
            description = "Comma-separated recipients alerted by mail when a SECOM delivery fails terminally"
                    + " (empty = no alerts). Read at start-up.")
    String alertEmails;

    // The renewed service keystore is written to the location McpSecurityService loads from;
    // that service deliberately exposes no location/password getters, hence the own bindings
    @Value("${niord.secom.keystore.location}")
    String keystoreLocation;

    @Value("${niord.secom.keystore.password}")
    String keystorePassword;

    @Value("${niord.secom.keystore.alias:1}")
    String keystoreAlias;

    @Value("${niord.secom.renewal.mir-url:}")
    String mirUrl;

    @Value("${niord.secom.renewal.org-mrn:}")
    String orgMrn;

    /** Blank = derived from the service certificate subject UID */
    @Value("${niord.secom.renewal.service-mrn:}")
    String serviceMrn;

    /** Blank = derived from the robot certificate subject UID */
    @Value("${niord.secom.renewal.user-mrn:}")
    String userMrn;

    @Value("${niord.secom.renewal.keystore.location:}")
    String robotKeystoreLocation;

    @Value("${niord.secom.renewal.keystore.password:}")
    String robotKeystorePassword;

    @Value("${niord.secom.renewal.keystore.alias:1}")
    String robotKeystoreAlias;

    private final McpSecurityService pki;
    private final McpMirClient mirClient;
    private final KeystoreWriter keystoreWriter;
    private final SecomTxRunner txRunner;

    /** The last renewal failure - in-memory dedup so a persistent failure alerts once, not daily */
    volatile String lastFailure;

    /**
     * Fingerprints rotated away by a deferred-pickup (k8s) renewal, keyed by store location.
     * If a later run still finds that certificate at the location, the renewed material never
     * became visible (a wrong secret-mounts mapping, a dead store watch) - re-issuing daily
     * would silently mint MIR identities forever, so the renewal pauses and alerts instead.
     */
    final Map<String, String> pendingPickups = new ConcurrentHashMap<>();

    public SecomCertificateRenewalService(McpSecurityService pki, McpMirClient mirClient,
                                          KeystoreWriter keystoreWriter, SecomTxRunner txRunner) {
        this.pki = pki;
        this.mirClient = mirClient;
        this.keystoreWriter = keystoreWriter;
        this.txRunner = txRunner;
    }

    /** Resolved renewal configuration for one run */
    record RenewalContext(X509Certificate robotCertificate, String userMrn, String serviceMrn) {
    }

    /** A completed renewal, for the confirmation mail and the forced-renewal response */
    public record Renewed(boolean robot, String mrn, String previousFingerprint, String fingerprint,
                          Date notAfter, boolean anchored, boolean pendingPickup) {
    }

    /** The outcome of one renewal run, for the forced-renewal REST endpoint */
    public record RenewalRun(List<Renewed> renewed, List<String> failures) {
    }

    /** Snapshot of the unattended-renewal setup, for the admin status endpoint */
    public record RenewalInfo(boolean enabled, boolean configured, int windowDays) {
    }

    /**
     * {@return whether unattended renewal is enabled and its niord.secom.renewal.* configuration
     * is present, and the renewal window}. The required-key checks mirror {@link #resolveContext()}.
     */
    public RenewalInfo renewalInfo() {
        boolean configured = StringUtils.isNoneBlank(mirUrl, orgMrn, robotKeystoreLocation, robotKeystorePassword);
        return new RenewalInfo(Boolean.TRUE.equals(renewalEnabled), configured, windowDays);
    }

    /** Guards against a forced run overlapping the scheduled one (or a double-click) */
    private final AtomicBoolean runInProgress = new AtomicBoolean();

    /** Daily renewal check; scheduled before the expiry check so a renewal pre-empts that day's warning */
    @Scheduled(cron = "${niord.secom.renewal-cron:13 27 5 * * ?}")
    public void renewDueCertificates() {
        try {
            renew(new Date());
        } catch (Exception e) {
            log.error("SECOM certificate renewal run failed", e);
        }
    }

    /** Runs the scheduled renewal check against the given "now"; a no-op unless the renewal is enabled */
    RenewalRun renew(Date now) {
        if (!Boolean.TRUE.equals(renewalEnabled)) {
            return new RenewalRun(List.of(), List.of());
        }
        return run(now, false);
    }

    /**
     * Forces a renewal of both the robot and the service credential right now - the sysadmin
     * "Renew Now" action, and the tool for the supervised first renewal against a real MIR.
     * Ignores the expiry window and the {@code secomRenewalEnabled} setting (so the renewal
     * can be exercised before the unattended loop is armed), but requires the
     * {@code niord.secom.renewal.*} configuration and passes through exactly the verification
     * gates, alert mails and pickup guards of a scheduled renewal. Every call mints new
     * certificates at the identity registry - deliberate use only.
     */
    public RenewalRun renewNow() {
        log.info("Forced SECOM certificate renewal requested");
        return run(new Date(), true);
    }

    /** One renewal run; the robot credential renews strictly first, forced runs treat both credentials as due */
    private RenewalRun run(Date now, boolean force) {
        if (!runInProgress.compareAndSet(false, true)) {
            throw new IllegalStateException("A SECOM certificate renewal run is already in progress");
        }
        try {
            return doRun(now, force);
        } finally {
            runInProgress.set(false);
        }
    }

    private RenewalRun doRun(Date now, boolean force) {
        List<String> failures = new ArrayList<>();
        List<Renewed> renewed = new ArrayList<>();

        RenewalContext ctx = null;
        try {
            ctx = resolveContext();
        } catch (Exception e) {
            failures.add(message(e));
        }

        if (ctx != null) {
            if ((force || daysToExpiry(ctx.robotCertificate().getNotAfter(), now) <= windowDays)
                    && !pickupPending(robotKeystoreLocation, ctx.robotCertificate(),
                    "Robot credential " + ctx.userMrn(), failures)) {
                try {
                    renewed.add(renewCredential(true, ctx, now));
                } catch (Exception e) {
                    log.error("SECOM renewal: robot credential renewal failed - the service renewal still runs "
                            + "(inside the window the current robot credential remains valid)", e);
                    failures.add("Robot credential " + ctx.userMrn() + ": " + message(e));
                }
            }
            if ((force || daysToExpiry(pki.serviceCertificate().getNotAfter(), now) <= windowDays)
                    && !pickupPending(keystoreLocation, pki.serviceCertificate(),
                    "Service certificate " + ctx.serviceMrn(), failures)) {
                try {
                    renewed.add(renewCredential(false, ctx, now));
                } catch (Exception e) {
                    log.error("SECOM renewal: service certificate renewal failed", e);
                    failures.add("Service certificate " + ctx.serviceMrn() + ": " + message(e));
                }
            }
        }

        // Success confirmations are best-effort - one-shot events (the T2 rotation-mail stance)
        for (Renewed outcome : renewed) {
            try {
                txRunner.call(() -> queueSuccessMail(outcome));
            } catch (Exception e) {
                log.error("Failed to commit the SECOM renewal confirmation mail: {}", e.getMessage());
            }
        }

        RenewalRun outcome = new RenewalRun(List.copyOf(renewed), List.copyOf(failures));
        if (failures.isEmpty()) {
            lastFailure = null;
            return outcome;
        }
        String failure = String.join("; ", failures);
        if (failure.equals(lastFailure)) {
            log.debug("SECOM certificate renewal still failing: {}", failure);
            return outcome;
        }
        log.error("SECOM certificate renewal failed: {}", failure);
        // The dedup marker only advances once the alert mail is committed (or no recipients
        // are configured) - a failed commit must retry on the next run, not suppress the
        // alert (the SecomStoreWatchService precedent)
        if (failureAlerted(failure)) {
            lastFailure = failure;
        }
        return outcome;
    }

    /**
     * Whether a previous deferred-pickup renewal of the credential at the location never
     * became visible: the currently served certificate is still the one that renewal rotated
     * away. Re-issuing daily would silently mint MIR identities forever, so the caller skips
     * the renewal and this alerts (deduped like every other failure) until the pickup
     * converges or the configuration is fixed. A converged pickup clears the marker.
     */
    private boolean pickupPending(String location, X509Certificate current, String credential,
                                  List<String> failures) {
        String rotatedAway = pendingPickups.get(location);
        if (rotatedAway == null) {
            return false;
        }
        if (!rotatedAway.equals(McpSecurityService.fingerprint(current))) {
            pendingPickups.remove(location);
            return false;
        }
        log.error("SECOM renewal: the renewal of {} on a previous run never became visible at {} "
                + "- re-issuing is paused until the pickup converges", credential, location);
        failures.add(credential + ": a previous renewal never became visible at " + location
                + " - the running instance still serves the certificate that was renewed away. Check the "
                + "niord.secom.renewal.k8s.secret-mounts mapping (and the store watch); re-issuing is "
                + "paused until the renewed keystore reaches this location");
        return true;
    }

    /**
     * Validates the renewal configuration and resolves the effective MRNs. Any problem here
     * fails the whole run before any MIR traffic - a partially configured renewal must alert,
     * not half-execute.
     */
    private RenewalContext resolveContext() throws Exception {
        List<String> problems = new ArrayList<>();
        if (StringUtils.isBlank(mirUrl)) {
            problems.add("niord.secom.renewal.mir-url is not configured");
        }
        if (StringUtils.isBlank(orgMrn)) {
            problems.add("niord.secom.renewal.org-mrn is not configured");
        }
        if (StringUtils.isBlank(robotKeystoreLocation)) {
            problems.add("niord.secom.renewal.keystore.location is not configured");
        }
        if (StringUtils.isBlank(robotKeystorePassword)) {
            problems.add("niord.secom.renewal.keystore.password is not configured");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems));
        }
        if (!keystoreWriter.supports(keystoreLocation)) {
            throw new IllegalStateException("The service keystore location is not writable by the renewal backend: "
                    + keystoreLocation);
        }
        if (!keystoreWriter.supports(robotKeystoreLocation)) {
            throw new IllegalStateException("The robot keystore location is not writable by the renewal backend: "
                    + robotKeystoreLocation);
        }

        KeyStore robotStore = pki.loadExternalStore(robotKeystoreLocation, robotKeystorePassword);
        X509Certificate robotCert = (X509Certificate) robotStore.getCertificate(robotKeystoreAlias);
        if (robotCert == null) {
            throw new IllegalStateException("No certificate at alias '" + robotKeystoreAlias
                    + "' in the robot keystore " + robotKeystoreLocation);
        }

        String effectiveUserMrn = StringUtils.isNotBlank(userMrn) ? userMrn : CertificateRenewalUtils.subjectMrn(robotCert);
        if (StringUtils.isBlank(effectiveUserMrn)) {
            throw new IllegalStateException("Cannot resolve the robot user MRN: the robot certificate subject "
                    + "has no UID and niord.secom.renewal.user-mrn is not set");
        }
        String effectiveServiceMrn = StringUtils.isNotBlank(serviceMrn) ? serviceMrn
                : CertificateRenewalUtils.subjectMrn(pki.serviceCertificate());
        if (StringUtils.isBlank(effectiveServiceMrn)) {
            throw new IllegalStateException("Cannot resolve the service MRN: the service certificate subject "
                    + "has no UID and niord.secom.renewal.service-mrn is not set");
        }
        return new RenewalContext(robotCert, effectiveUserMrn, effectiveServiceMrn);
    }

    /**
     * Renews one credential end to end. Ordered so nothing is mutated until the issued chain
     * has passed the hard verification gates and the assembled keystore is proven loadable;
     * the atomic write is the only mutating step. Should the concluding reload check still
     * fail, the on-disk store already holds the pre-verified renewed material and the store
     * watch (T2) converges within a minute - the failure mail says so.
     */
    private Renewed renewCredential(boolean robot, RenewalContext ctx, Date now) throws Exception {
        String mrn = robot ? ctx.userMrn() : ctx.serviceMrn();
        X509Certificate currentCert = robot ? ctx.robotCertificate() : pki.serviceCertificate();
        String location = robot ? robotKeystoreLocation : keystoreLocation;
        String alias = robot ? robotKeystoreAlias : keystoreAlias;
        String password = robot ? robotKeystorePassword : keystorePassword;
        String previousFingerprint = McpSecurityService.fingerprint(currentCert);

        KeyPair freshKeys = CertificateRenewalUtils.generateKeyPair();
        String csrPem = CertificateRenewalUtils.buildCsrPem(currentCert.getSubjectX500Principal(), freshKeys);
        String chainPem = robot
                ? mirClient.issueUserCertificate(orgMrn, mrn, csrPem)
                : mirClient.issueServiceCertificate(orgMrn, mrn, csrPem);

        List<X509Certificate> chain = CertificateRenewalUtils.parsePemChain(chainPem);
        CertificateRenewalUtils.verifyIssuedChain(chain, freshKeys.getPublic(), mrn, now);
        boolean anchored = CertificateRenewalUtils.chainAnchorsInTruststore(chain, pki.trustStore());
        if (!anchored) {
            log.warn("SECOM renewal: the chain issued for {} does not anchor in the configured truststore", mrn);
        }

        byte[] keystoreBytes = CertificateRenewalUtils.assembleKeystore(alias, password, freshKeys.getPrivate(), chain);
        CertificateRenewalUtils.verifyKeystore(keystoreBytes, alias, password, freshKeys.getPublic());
        keystoreWriter.write(location, keystoreBytes);

        X509Certificate liveCert;
        if (keystoreWriter.deferredPickup()) {
            // The authoritative store (the k8s Secret) is updated, but the location this
            // instance reads is a kubelet-synced mount that converges within a minute or
            // two, after which the store watch (T2) completes the in-memory pickup - there
            // is nothing to read back yet. The marker lets the next run verify the pickup
            // actually happened instead of silently re-issuing forever.
            pendingPickups.put(location, previousFingerprint);
            liveCert = chain.get(0);
        } else if (robot) {
            // Prove the persisted bytes are the renewed credential (read back from the location)
            liveCert = (X509Certificate) pki.loadExternalStore(location, password).getCertificate(alias);
        } else {
            pki.reload();
            liveCert = pki.serviceCertificate();
        }
        String fingerprint = liveCert == null ? null : McpSecurityService.fingerprint(liveCert);
        if (fingerprint == null || fingerprint.equals(previousFingerprint)) {
            throw new IllegalStateException("The renewed keystore was persisted to " + location
                    + " but the subsequent reload still serves the previous certificate - the store watch "
                    + "or a manual reload will pick the renewed material up");
        }
        log.info("SECOM renewal: renewed the {} certificate {} - fingerprint {} -> {}, expires {}",
                robot ? "robot" : "service", mrn, previousFingerprint, fingerprint, liveCert.getNotAfter());
        return new Renewed(robot, mrn, previousFingerprint, fingerprint, liveCert.getNotAfter(), anchored,
                keystoreWriter.deferredPickup());
    }

    /** Queues the renewal confirmation mail; see {@link #queueMail(String, String)} for the recipient handling */
    private boolean queueSuccessMail(Renewed renewal) {
        return queueMail(renewal.robot()
                        ? "SECOM renewal robot credential renewed automatically"
                        : "SECOM service certificate renewed automatically",
                "<p>The MCP Identity Registry issued a new certificate with fresh keys, and the renewed "
                        + "keystore is persisted" + (renewal.pendingPickup() ? "" : " and live")
                        + ". This mail is a confirmation - no action is needed.</p>"
                        + "<ul>"
                        + "<li>MRN: " + escapeHtml(renewal.mrn()) + "</li>"
                        + "<li>Previous fingerprint: " + renewal.previousFingerprint() + "</li>"
                        + "<li>Fingerprint: " + renewal.fingerprint() + "</li>"
                        + "<li>Expires: " + renewal.notAfter() + "</li>"
                        + "</ul>"
                        + (!renewal.pendingPickup() ? ""
                        : renewal.robot()
                        ? "<p>The renewed material lands in the Kubernetes Secret; the mounted credential "
                        + "converges within a couple of minutes (Secret propagation) and the next renewal "
                        + "run verifies the pickup - a stalled pickup is alerted.</p>"
                        : "<p>The renewed material lands in the Kubernetes Secret; the running instance picks "
                        + "it up automatically within a couple of minutes (Secret propagation plus the store "
                        + "watch), which sends its own rotation confirmation. If that mail never arrives, "
                        + "check the niord.secom.renewal.k8s.secret-mounts mapping - the next renewal run "
                        + "also detects and alerts a stalled pickup.</p>")
                        + (renewal.robot() ? ""
                        : "<p>Stored S-124 dataset signatures are refreshed by the next reconciliation sweep.</p>")
                        + (renewal.anchored() ? ""
                        : "<p>NOTE: the issued chain does not anchor in the configured truststore. Update the "
                        + "truststore with the current provider chain - see the sysadmin manual appendix "
                        + "on SECOM certificates.</p>"));
    }

    /** Queues the renewal-failure alert mail; the current material keeps serving meanwhile */
    private boolean queueFailureMail(String failure) {
        // Only a 401/403 from the MIR itself points at the robot role mapping - the k8s
        // backend raises its own 403 hint (a Secret-RBAC problem, not a MIR one). Matched
        // per joined-failure segment, so a MIR 500 on one credential plus a k8s 403 on the
        // other cannot combine into a false hint.
        boolean roleHint = Arrays.stream(failure.split("; "))
                .anyMatch(f -> f.contains("MIR POST")
                        && (f.contains("HTTP 401") || f.contains("HTTP 403")));
        return queueMail("SECOM certificate renewal FAILED - will retry next run",
                "<p>The unattended SECOM certificate renewal failed. The current key material keeps "
                        + "serving, the renewal retries on the next scheduled run, and the certificate-expiry "
                        + "alerts keep escalating independently.</p>"
                        + "<ul><li>Error: " + escapeHtml(failure) + "</li></ul>"
                        + (roleHint
                        ? "<p>An HTTP 401/403 from the MIR usually means the robot user's permissions do not "
                        + "map to the SERVICE_ADMIN role - see the sysadmin manual appendix on SECOM certificates.</p>"
                        : "")
                        + "<p>This alert repeats only if the failure changes; the application log has one "
                        + "entry per failed run.</p>");
    }

    /**
     * Queues the failure alert in its own committed transaction. Returns whether the failure
     * is fully handled: the mail exists durably, or there are no recipients to alert (the
     * default) - in which case there is nothing to retry and the single error log above is
     * the record. A queue/commit failure returns false, so the caller leaves the dedup
     * marker untouched and the alert is retried on the next run.
     */
    private boolean failureAlerted(String failure) {
        if (StringUtils.isBlank(alertEmails)) {
            return true;
        }
        try {
            return txRunner.call(() -> queueFailureMail(failure));
        } catch (Exception e) {
            log.error("Failed to commit the SECOM renewal failure alert: {}", e.getMessage());
            return false;
        }
    }

    private boolean queueMail(String subject, String htmlBody) {
        if (StringUtils.isBlank(alertEmails)) {
            return false;
        }
        try {
            saveEntity(alertMail(subject, htmlBody, alertEmails));
            log.info("Queued SECOM renewal mail '{}' to {}", subject, alertEmails);
            return true;
        } catch (Exception e) {
            log.error("Failed to queue the SECOM renewal mail: {}", e.getMessage());
            return false;
        }
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }
}
