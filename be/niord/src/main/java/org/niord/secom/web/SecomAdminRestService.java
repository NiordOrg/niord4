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
package org.niord.secom.web;

import jakarta.annotation.security.RolesAllowed;
import org.niord.core.settings.SettingsService;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.user.Roles;
import org.niord.core.web.NiordWebException;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.S124SeriesConfig;
import org.niord.secom.model.SecomNode;
import org.niord.secom.model.SecomOutboxEntry;
import org.niord.secom.model.SecomSubscription;
import org.niord.secom.pki.McpSecurityService;
import org.niord.secom.service.S124DatasetService;
import org.niord.secom.service.SecomCertificateAlertService;
import org.niord.secom.service.SecomCertificateRenewalService;
import org.niord.secom.service.SecomConfigService;
import org.niord.secom.service.SecomOutboxService;
import org.niord.secom.service.SecomSubscriptionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Sysadmin REST API for operating the SECOM integration: node status, the per-series
 * exposure configuration, subscriptions, dataset instances and the delivery outbox.
 * The scalar node-wide SECOM knobs are ordinary settings on the generic sysadmin
 * Settings page. Only registered when the SECOM integration is enabled
 * ({@code niord.secom.enabled=true}); disabled deployments 404.
 */
@RestController
@RequestMapping("/rest/secom")
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@Transactional
@RolesAllowed(Roles.SYSADMIN)
@SuppressWarnings("unused")
public class SecomAdminRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecomAdminRestService.class);

    /**
     * A legacy declaration nothing reads. It once signalled to the frontend that the SECOM
     * subsystem runs on this node (it auto-registers, with default true, only where this bean
     * exists). Since the S-124 opt-out (the owner, 2026-11-01) {@code /rest/settings/web} reports
     * {@code secomEnabled} computed from the running {@code niord.secom.enabled}
     * ({@code SettingsRestService.getWebSettings}), put over any stored row of this key, so the
     * row decides nothing. The declaration stays because settings are add-only: removing it would
     * change the catalogue. editable=false keeps it off the Settings page.
     */
    @Setting(value = "secomEnabled", defaultValue = "true",
            type = org.niord.core.settings.Setting.Type.Boolean, web = true, editable = false,
            description = "Not read: /rest/settings/web reports SECOM from niord.secom.enabled; kept because settings are add-only")
    Boolean secomEnabled;

    /**
     * Purely a UI affordance: when set, the admin page links the service certificate to this
     * page. The field only auto-registers the setting; {@link #status()} reads it through
     * {@link SettingsService} so an edit on the Settings page shows up without a restart.
     */
    @Setting(value = "secomMirPortalUrl", defaultValue = "",
            description = "URL of the MCP Identity Registry management-portal page holding this node's certificates, linked from the SECOM admin page (empty = no link)")
    String mirPortalUrl;

    private final McpSecurityService pki;
    private final S124DatasetService datasetService;
    private final SecomConfigService configService;
    private final SecomSubscriptionService subscriptionService;
    private final SecomOutboxService outboxService;
    private final SecomCertificateRenewalService renewalService;
    private final SettingsService settingsService;

    SecomAdminRestService(McpSecurityService pki, S124DatasetService datasetService,
                          SecomConfigService configService, SecomSubscriptionService subscriptionService,
                          SecomOutboxService outboxService, SecomCertificateRenewalService renewalService,
                          SettingsService settingsService) {
        this.pki = pki;
        this.datasetService = datasetService;
        this.configService = configService;
        this.subscriptionService = subscriptionService;
        this.outboxService = outboxService;
        this.renewalService = renewalService;
        this.settingsService = settingsService;
    }

    /***************************************/
    /** VOs                               **/
    /***************************************/

    public record CertificateVo(String subject, Date created, long ageDays, Date expires, long daysToExpiry,
                                String fingerprint) {
    }

    public record TrustedCertificateVo(String alias, String subject, Date created, Date expires, String fingerprint,
                                       boolean root) {
    }

    public record RenewalVo(boolean enabled, boolean configured, int windowDays, Date renewsAt, Long renewsInDays) {
    }

    public record StatusVo(boolean enabled, CertificateVo serviceCertificate, CertificateVo rootCertificate,
                           List<TrustedCertificateVo> trustedCertificates, RenewalVo renewal,
                           String mirPortalUrl, List<String> scopedSeries,
                           long datasets, long subscriptions, Map<String, Long> outboxByStatus) {
    }

    public record SecurityReloadVo(String serviceCertificateSubject, String serviceCertificateFingerprint,
                                   Date serviceCertificateExpires, String previousFingerprint) {
    }

    public record RenewedCredentialVo(String credential, String mrn, String previousFingerprint, String fingerprint,
                                      Date expires, boolean anchored, boolean pendingPickup) {
    }

    public record RenewalResultVo(List<RenewedCredentialVo> renewed, List<String> failures) {
    }

    /**
     * One series' SECOM configuration. {@code languages} is the restriction on the model
     * languages its S-124 datasets may carry; empty = no restriction. On a PUT the list is
     * replaced as sent - an absent or empty list clears the restriction.
     */
    public record SeriesConfigVo(String seriesId, boolean exposeOverSecom, List<String> languages) {
        static SeriesConfigVo of(S124SeriesConfig config) {
            return new SeriesConfigVo(config.getMessageSeries().getSeriesId(), config.isExposeOverSecom(),
                    config.getLanguages());
        }
    }

    public record NodeVo(Integer id, String mrn, Date lastInteraction, Date created) {
    }

    public record SubscriptionVo(UUID uuid, String nodeMrn, String dataProductType, String productVersion,
                                 String containerType, UUID dataReference, String wkt,
                                 Date periodStart, Date periodEnd, Date created) {
    }

    public record DatasetVo(Integer id, UUID uuid, String mrn, String messageUid, String messageShortId,
                            String status, Date validFrom, Date validTo, Date created, Date updated, int gmlSize) {
    }

    public record OutboxVo(Integer id, UUID transactionId, String nodeMrn, String operation, String notificationEvent,
                           UUID subscriptionUuid, String datasetMrn, String status, int attempts,
                           Date nextAttemptAt, String lastError, Date sentAt, Date ackedAt, Date openedAt,
                           String nackType, Date created) {
    }

    /***************************************/
    /** Status                            **/
    /***************************************/

    /** Returns the operational status of the SECOM integration */
    @GetMapping(value = "/status", produces = "application/json;charset=UTF-8")
    public StatusVo status() {
        // Counted over every outbox row, as the actuator gauges are; nothing deletes outbox rows
        Map<String, Long> outboxByStatus = outboxService.countByStatus();
        Date now = new Date();
        X509Certificate cert = pki.serviceCertificate();
        SecomCertificateRenewalService.RenewalInfo renewal = renewalService.renewalInfo();
        // The date the daily sweep starts renewing; meaningless while the unattended loop is not armed
        Date renewsAt = renewal.enabled() && renewal.configured()
                ? new Date(cert.getNotAfter().getTime() - TimeUnit.DAYS.toMillis(renewal.windowDays()))
                : null;
        return new StatusVo(
                true,
                toCertificateVo(cert, now),
                toCertificateVo(pki.rootCertificate(), now),
                pki.trustedCertificates().stream()
                        .map(t -> new TrustedCertificateVo(t.alias(),
                                t.certificate().getSubjectX500Principal().getName(),
                                t.certificate().getNotBefore(), t.certificate().getNotAfter(),
                                McpSecurityService.fingerprint(t.certificate()), t.root()))
                        .toList(),
                new RenewalVo(renewal.enabled(), renewal.configured(), renewal.windowDays(), renewsAt,
                        renewsAt != null ? SecomCertificateAlertService.daysToExpiry(renewsAt, now) : null),
                settingsService.getString("secomMirPortalUrl"),
                List.copyOf(datasetService.scopedSeriesIds()),
                datasetService.count(S124Dataset.class),
                subscriptionService.count(SecomSubscription.class),
                outboxByStatus);
    }

    private static CertificateVo toCertificateVo(X509Certificate cert, Date now) {
        return new CertificateVo(cert.getSubjectX500Principal().getName(), cert.getNotBefore(),
                // age = days from notBefore to now
                SecomCertificateAlertService.daysToExpiry(now, cert.getNotBefore()),
                cert.getNotAfter(),
                SecomCertificateAlertService.daysToExpiry(cert.getNotAfter(), now),
                McpSecurityService.fingerprint(cert));
    }

    /**
     * Re-reads the configured SECOM keystore/truststore and swaps them in without a restart
     * (certificate rotation). A failed reload keeps the current material active and returns 400.
     */
    @PostMapping(value = "/security/reload", produces = "application/json;charset=UTF-8")
    public SecurityReloadVo reloadSecurity() {
        log.info("Admin reloading SECOM key material");
        String previous = McpSecurityService.fingerprint(pki.serviceCertificate());
        try {
            pki.reload();
        } catch (Exception e) {
            throw new NiordWebException("SECOM key material reload failed - keeping current material: "
                    + e.getMessage(), 400);
        }
        X509Certificate cert = pki.serviceCertificate();
        return new SecurityReloadVo(cert.getSubjectX500Principal().getName(),
                McpSecurityService.fingerprint(cert), cert.getNotAfter(), previous);
    }

    /**
     * Forces an immediate renewal of the renewal-robot and service certificates against the
     * MCP Identity Registry, regardless of the expiry window and the {@code secomRenewalEnabled}
     * setting - the supervised first-renewal tool. Requires the {@code niord.secom.renewal.*}
     * configuration; a run that renews nothing returns 400 with the failures. Runs outside the
     * class-level transaction: the MIR round-trips must not hold a database connection (the
     * confirmation/alert mails commit through their own transactions).
     */
    @PostMapping(value = "/security/renew", produces = "application/json;charset=UTF-8")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RenewalResultVo renewCertificates() {
        log.info("Admin forcing SECOM certificate renewal");
        SecomCertificateRenewalService.RenewalRun run;
        try {
            run = renewalService.renewNow();
        } catch (IllegalStateException e) {
            throw new NiordWebException(e.getMessage(), 409);
        }
        if (run.renewed().isEmpty() && !run.failures().isEmpty()) {
            throw new NiordWebException("SECOM certificate renewal failed: "
                    + String.join("; ", run.failures()), 400);
        }
        return new RenewalResultVo(
                run.renewed().stream()
                        .map(r -> new RenewedCredentialVo(r.robot() ? "robot" : "service", r.mrn(),
                                r.previousFingerprint(), r.fingerprint(), r.notAfter(), r.anchored(),
                                r.pendingPickup()))
                        .toList(),
                run.failures());
    }

    /***************************************/
    /** Runtime configuration             **/
    /***************************************/

    /** Returns the per-series SECOM configuration rows */
    @GetMapping(value = "/series-config", produces = "application/json;charset=UTF-8")
    public List<SeriesConfigVo> seriesConfigs() {
        return configService.getSeriesConfigs().stream()
                .map(SeriesConfigVo::of)
                .toList();
    }

    /**
     * Creates or updates the SECOM configuration of the given message series. A language
     * outside the model languages is a 400 (the service's IllegalArgumentException).
     */
    @PutMapping(value = "/series-config/{seriesId}", produces = "application/json;charset=UTF-8")
    public SeriesConfigVo upsertSeriesConfig(@PathVariable("seriesId") String seriesId,
                                             @RequestBody SeriesConfigVo config) {
        log.info("Admin updating SECOM series config {}: exposeOverSecom={}, languages={}",
                seriesId, config.exposeOverSecom(), config.languages());
        S124SeriesConfig updated = configService.upsertSeriesConfig(seriesId, config.exposeOverSecom(),
                config.languages());
        return SeriesConfigVo.of(updated);
    }

    /** Removes the SECOM configuration of the given message series (no longer exposed over SECOM) */
    @DeleteMapping(value = "/series-config/{seriesId}", produces = "application/json;charset=UTF-8")
    public SeriesConfigVo deleteSeriesConfig(@PathVariable("seriesId") String seriesId) {
        log.info("Admin removing SECOM series config {}", seriesId);
        S124SeriesConfig removed = configService.deleteSeriesConfig(seriesId);
        return new SeriesConfigVo(seriesId, removed.isExposeOverSecom(), removed.getLanguages());
    }

    /***************************************/
    /** Nodes + subscriptions             **/
    /***************************************/

    /** Returns all known SECOM peer nodes */
    @GetMapping(value = "/nodes", produces = "application/json;charset=UTF-8")
    public List<NodeVo> nodes() {
        return subscriptionService.getAll(SecomNode.class).stream()
                .map(n -> new NodeVo(n.getId(), n.getMrn(), n.getLastInteraction(), n.getCreated()))
                .toList();
    }

    /** Returns all subscriptions */
    @GetMapping(value = "/subscriptions", produces = "application/json;charset=UTF-8")
    public List<SubscriptionVo> subscriptions() {
        return subscriptionService.getAll(SecomSubscription.class).stream()
                .map(s -> new SubscriptionVo(s.getUuid(), s.getNode().getMrn(), s.getDataProductType(),
                        s.getProductVersion(), s.getContainerType(), s.getDataReference(), s.getWkt(),
                        s.getPeriodStart(), s.getPeriodEnd(), s.getCreated()))
                .toList();
    }

    /** Removes a subscription and notifies the subscriber */
    @DeleteMapping(value = "/subscriptions/{uuid}", produces = "application/json;charset=UTF-8")
    public SubscriptionVo deleteSubscription(@PathVariable("uuid") UUID uuid) {
        log.info("Admin removing SECOM subscription {}", uuid);
        SecomSubscription s = subscriptionService.adminRemove(uuid);
        return new SubscriptionVo(s.getUuid(), s.getNode().getMrn(), s.getDataProductType(),
                s.getProductVersion(), s.getContainerType(), s.getDataReference(), s.getWkt(),
                s.getPeriodStart(), s.getPeriodEnd(), s.getCreated());
    }

    /***************************************/
    /** Datasets                          **/
    /***************************************/

    /** Returns all S-124 dataset instances */
    @GetMapping(value = "/datasets", produces = "application/json;charset=UTF-8")
    public List<DatasetVo> datasets() {
        return datasetService.getAll(S124Dataset.class).stream()
                .map(SecomAdminRestService::toDatasetVo)
                .toList();
    }

    /**
     * Looks up the dataset the request addresses. The check lives here rather than in
     * {@code S124DatasetService.findByUuid}, whose null return the SECOM Get search depends on.
     */
    private S124Dataset requireDataset(UUID uuid) {
        S124Dataset dataset = datasetService.findByUuid(uuid);
        if (dataset == null) {
            throw new IllegalArgumentException("Unknown dataset " + uuid);
        }
        return dataset;
    }

    /** Returns the dataset with the given SECOM dataReference */
    @GetMapping(value = "/datasets/{uuid}", produces = "application/json;charset=UTF-8")
    public DatasetVo dataset(@PathVariable("uuid") UUID uuid) {
        S124Dataset dataset = requireDataset(uuid);
        return toDatasetVo(dataset);
    }

    /** Returns the published GML of the dataset with the given SECOM dataReference - its current version */
    @GetMapping(value = "/datasets/{uuid}/gml", produces = "application/gml+xml;charset=UTF-8")
    public String datasetGml(@PathVariable("uuid") UUID uuid) {
        S124Dataset dataset = requireDataset(uuid);
        if (dataset.getCurrentVersion() == null) {
            throw new IllegalArgumentException("Dataset " + uuid + " has no published version");
        }
        return dataset.getCurrentVersion().getGml();
    }

    /** Re-enqueues the dataset to all matching subscriptions; returns the number of deliveries enqueued */
    @PostMapping(value = "/datasets/{uuid}/republish", produces = "application/json;charset=UTF-8")
    public int republishDataset(@PathVariable("uuid") UUID uuid) {
        S124Dataset dataset = requireDataset(uuid);
        List<SecomSubscription> matching = subscriptionService.findMatching(dataset, datasetService.computeVicinityGeometry(dataset));
        matching.forEach(s -> outboxService.enqueueUpload(s, dataset));
        log.info("Admin republished dataset {} to {} subscriptions", dataset.getMrn(), matching.size());
        return matching.size();
    }

    /** Runs the reconciliation sweep now (doubles as the backfill when first enabling SECOM) */
    @PostMapping(value = "/datasets/reconcile", produces = "application/json;charset=UTF-8")
    public S124DatasetService.ReconcileResult reconcile() {
        log.info("Admin triggered SECOM S-124 reconciliation");
        return datasetService.reconcile();
    }

    /***************************************/
    /** Outbox                            **/
    /***************************************/

    /** Returns recent outbox entries, optionally filtered by status */
    @GetMapping(value = "/outbox", produces = "application/json;charset=UTF-8")
    public List<OutboxVo> outbox(
            @RequestParam(name = "status", required = false) SecomOutboxEntry.Status status,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return outboxService.search(status, limit).stream()
                .map(o -> new OutboxVo(o.getId(), o.getTransactionId(), o.getNode().getMrn(), o.getOperation().name(),
                        o.getNotificationEvent(), o.getSubscriptionUuid(),
                        o.getDataset() != null ? o.getDataset().getMrn() : null,
                        o.getStatus().name(), o.getAttempts(), o.getNextAttemptAt(), o.getLastError(),
                        o.getSentAt(), o.getAckedAt(), o.getOpenedAt(), o.getNackType(), o.getCreated()))
                .toList();
    }

    /** Resets an outbox entry for immediate dispatch */
    @PostMapping(value = "/outbox/{id}/retry", produces = "application/json;charset=UTF-8")
    public int retryOutboxEntry(@PathVariable("id") Integer id) {
        log.info("Admin retrying SECOM outbox entry {}", id);
        return outboxService.retryNow(id).getId();
    }

    /** Cancels a pending outbox entry */
    @DeleteMapping(value = "/outbox/{id}", produces = "application/json;charset=UTF-8")
    public int cancelOutboxEntry(@PathVariable("id") Integer id) {
        log.info("Admin cancelling SECOM outbox entry {}", id);
        return outboxService.cancel(id).getId();
    }

    private static DatasetVo toDatasetVo(S124Dataset d) {
        return new DatasetVo(d.getId(), d.getUuid(), d.getMrn(), d.getMessage().getUid(),
                d.getMessage().getShortId(), d.getStatus().name(), d.getValidFrom(), d.getValidTo(),
                d.getCreated(), d.getUpdated(),
                d.getCurrentVersion() != null ? d.getCurrentVersion().gmlBytes().length : 0);
    }
}
