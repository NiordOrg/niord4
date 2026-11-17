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

import jakarta.persistence.LockModeType;
import org.hibernate.Session;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.niord.core.geojson.JtsConverter;
import org.niord.core.message.Message;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageService;
import org.niord.core.message.Reference;
import org.niord.core.service.BaseService;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.s124.S124Service;
import org.niord.s124.mapper.S124Mapper;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.S124SeriesConfig;
import org.niord.secom.model.S124DatasetVersion;
import org.niord.secom.util.MrnToUuid;
import org.niord.secom.util.S124Mrn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;



/**
 * Owns the S-124 dataset store: generates and publishes a dataset version for every eligible
 * published navigational warning (via the {@link S124Service} generator, called in-process, no
 * HTTP hop), cancels the dataset when the message is cancelled/expired, and answers the queries
 * behind SECOM Get/GetSummary.
 * <p>
 * Every publish, update and cancellation that changes the dataset file appends an immutable
 * {@link S124DatasetVersion} - signed once, in the exchange-set build that packages it (see
 * {@link S124ExchangeSetService#publish}) - and moves the dataset row's pointer to it; the file
 * a regeneration yields unchanged appends nothing and pushes nothing. A version that cannot be
 * signed is not written at all: the unit's transaction fails and the reconciliation sweep
 * retries it.
 * <p>
 * A scheduled reconciliation sweep diffs eligible messages against dataset rows. It is the
 * safety net for the fire-and-forget JMS status events (missed events while the application
 * is down) and doubles as the backfill when the integration is first enabled. Each
 * message/dataset is reconciled in its own committed transaction ({@link SecomTxRunner}), so
 * a concurrent writer or a single bad message aborts only that unit, never the whole sweep.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class S124DatasetService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(S124DatasetService.class);

    /** The product version advertised in the SECOM capability and used for subscription matching */
    public static final String S124_PRODUCT_VERSION = "2.0.0";

    /**
     * The first key of the message dataset locks (two-key advisory locks, a key space apart from
     * the one-key locks such as the defaults ledger's); the second key is the message UID's hash
     */
    static final int DATASET_LOCK_SPACE = 0x5124;

    private final S124Service s124Service;
    private final MessageQueryService messageQueryService;
    private final MessageService messageService;
    private final SecomSubscriptionService subscriptionService;
    private final SecomOutboxService outboxService;
    private final SecomConfigService configService;
    private final S124ExchangeSetService exchangeSetService;
    private final SecomTxRunner txRunner;

    public S124DatasetService(S124Service s124Service, MessageQueryService messageQueryService,
                              MessageService messageService, SecomSubscriptionService subscriptionService,
                              SecomOutboxService outboxService, SecomConfigService configService,
                              S124ExchangeSetService exchangeSetService, SecomTxRunner txRunner) {
        this.s124Service = s124Service;
        this.messageQueryService = messageQueryService;
        this.messageService = messageService;
        this.subscriptionService = subscriptionService;
        this.outboxService = outboxService;
        this.configService = configService;
        this.exchangeSetService = exchangeSetService;
        this.txRunner = txRunner;
    }

    /***************************************/
    /** Eligibility                       **/
    /***************************************/

    /** Returns the message series IDs in SECOM scope (the series with an exposeOverSecom config row) */
    public Set<String> scopedSeriesIds() {
        return configService.exposedSeriesIds();
    }

    /** Returns if the message is one that should be exposed as an S-124 dataset when published */
    public boolean isEligible(Message message) {
        return message != null
                && message.getMainType() == MainType.NW
                && message.getNumber() != null
                && message.getMessageSeries() != null
                && scopedSeriesIds().contains(message.getMessageSeries().getSeriesId());
    }

    /***************************************/
    /** Dataset production                **/
    /***************************************/

    /**
     * Reacts to a message status change (called from the JMS listener and the reconciliation
     * sweep): creates/updates the dataset when the message is published, cancels it when the
     * message leaves the published state. Changes are pushed to matching subscribers via the
     * outbox. Idempotent - unchanged GML enqueues nothing.
     */
    @Transactional
    public void onMessageStatusChanged(String messageUid) {
        // Lock the dataset row, THEN read the message. Status events arrive after the message
        // transaction commits (the JMS3 fix), but other writers still run concurrently: the
        // reconciliation sweep, a second status event. Under READ COMMITTED each query sees the
        // latest committed row at execution time, so with this ordering the message read is
        // always at least as fresh as the dataset read (the reverse order allowed a stale
        // PUBLISHED message + a fresh CANCELLED dataset). The lock makes the writers of one
        // dataset take turns: without it, a writer that read the dataset and the PUBLISHED
        // message just before a cancel committed went on to write its ACTIVE row over the
        // committed CANCELLED one (no version column guards the row), and the cancelled warning
        // was served and pushed as in force again (seen in a CI run). When there is no row
        // yet, the message's dataset lock (see lockByMessageUid) makes a writer that is creating it
        // and a writer that finds none take turns as well.
        S124Dataset dataset = lockByMessageUid(messageUid);
        Message message = messageQueryService.findByUid(messageUid);
        if (message == null) {
            return;
        }

        if (message.getStatus() == Status.PUBLISHED && isEligible(message)) {
            upsertDataset(message, dataset);
        } else if (dataset != null && dataset.getStatus() == S124Dataset.Status.ACTIVE
                && (message.getStatus() == Status.CANCELLED || message.getStatus() == Status.EXPIRED)) {
            cancelDataset(message, dataset);
        }
    }

    /**
     * The languages the message's dataset is generated in: the model languages under the
     * series' restriction ({@link S124SeriesConfig#getLanguages()}), English first when it is
     * among them. The generator keeps the ones the message has content in.
     */
    private List<String> languagesFor(Message message) {
        S124SeriesConfig config = message.getMessageSeries() == null
                ? null
                : configService.findSeriesConfig(message.getMessageSeries().getSeriesId());
        return s124Service.languagesFor(null, config == null ? List.of() : config.getLanguages());
    }

    /**
     * Creates or updates the dataset for a published, eligible message; enqueues pushes on change.
     * Returns false when the message's S-124 GML could not be generated: nothing is written then.
     */
    private boolean upsertDataset(Message message, S124Dataset dataset) {
        S124Mapper.Generated generated;
        try {
            generated = s124Service.generate(message, languagesFor(message), S124Mapper.GenerationType.FINAL);
        } catch (Exception e) {
            log.error("Failed to generate S-124 GML for message {}: {}", message.getUid(), e.getMessage());
            return false;
        }

        boolean isNew = dataset == null;
        if (isNew) {
            dataset = new S124Dataset();
            dataset.setMessage(message);
            dataset.setMrn(S124Mrn.of(message, s124Service.country()));
            dataset.setUuid(MrnToUuid.fromMrn(dataset.getMrn()));
        } else if (dataset.getStatus() == S124Dataset.Status.ACTIVE && dataset.hasCurrentContent(generated.xml())) {
            // Unchanged - nothing to publish or push
            return true;
        }

        dataset.setStatus(S124Dataset.Status.ACTIVE);
        dataset.setGeometry(computeGeometry(message));
        dataset.setValidFrom(message.getPublishDateFrom());
        dataset.setValidTo(message.getPublishDateTo());
        dataset.setCancelled(null);
        dataset = publishVersion(dataset, generated);

        log.info("{} S-124 dataset {} for message {}", isNew ? "Created" : "Updated", dataset.getMrn(), message.getUid());
        enqueueToMatchingSubscriptions(dataset);
        return true;
    }

    /** Marks the dataset cancelled, publishes the final GML version if it can be generated, and pushes it */
    private void cancelDataset(Message message, S124Dataset dataset) {
        dataset.setStatus(S124Dataset.Status.CANCELLED);
        if (dataset.getValidTo() == null) {
            dataset.setValidTo(new Date());
        }
        dataset.setCancelled(new Date());

        // The final version carries the in-band cancellation; when it cannot be generated the
        // last published version stays current and is what the cancellation announces
        S124Mapper.Generated finalGml = null;
        try {
            S124Mapper.Generated generated = s124Service.generate(message, languagesFor(message),
                    S124Mapper.GenerationType.FINAL);
            if (!dataset.hasCurrentContent(generated.xml())) {
                finalGml = generated;
            }
        } catch (Exception e) {
            log.warn("Keeping the last published version of cancelled dataset {}: {}", dataset.getMrn(), e.getMessage());
        }
        dataset = finalGml != null ? publishVersion(dataset, finalGml) : saveEntity(dataset);

        log.info("Cancelled S-124 dataset {} for message {}", dataset.getMrn(), message.getUid());
        enqueueToMatchingSubscriptions(dataset);

        // The referenced data object has ceased - subscriptions pinned to it terminate
        // automatically (service design §6.5.4). The final push above is already enqueued.
        subscriptionService.removeForDataReference(dataset.getUuid());
    }

    /**
     * Appends the immutable published version of the given typed dataset and makes it the
     * dataset's current one (see {@link S124ExchangeSetService#publish}). The version is signed
     * inside the one exchange-set build that packages it, so a signing failure propagates and
     * the whole write rolls back - nothing is ever stored half-published. The dataset row is
     * saved first because the version references it, and touched so its {@code updated} stamp
     * (the GetSummary modification date and the ping timestamp) moves with the content even
     * when no other column changed.
     */
    private S124Dataset publishVersion(S124Dataset dataset, S124Mapper.Generated generated) {
        S124DatasetVersion version = exchangeSetService.publish(generated.dataset(), generated.languages());
        dataset.setUpdated(new Date());
        dataset = saveEntity(dataset);
        version.setDataset(dataset);
        dataset.setCurrentVersion(saveEntity(version));
        return dataset;
    }

    /** Enqueues one outbox upload per subscription matching the dataset's vicinity */
    private void enqueueToMatchingSubscriptions(S124Dataset dataset) {
        Geometry vicinity = computeVicinityGeometry(dataset);
        subscriptionService.findMatching(dataset, vicinity).forEach(sub -> outboxService.enqueueUpload(sub, dataset));
    }

    /**
     * Computes the dataset's <i>message vicinity</i> (service spec §6.3.1): the dataset's own
     * geometry unioned with the geometries of all messages its message references. This makes
     * e.g. a cancellation covering a different area than the warning it cancels still reach
     * the subscribers who received the original warning. Returns null when neither the
     * message nor any referenced message is geotagged.
     */
    public Geometry computeVicinityGeometry(S124Dataset dataset) {
        List<Geometry> geometries = new ArrayList<>();
        if (dataset.getGeometry() != null) {
            geometries.add(dataset.getGeometry());
        }
        List<Reference> references = dataset.getMessage().getReferences();
        if (references != null) {
            for (Reference reference : references) {
                if (reference.getMessageId() == null) {
                    continue;
                }
                try {
                    Message referenced = messageService.resolveMessage(reference.getMessageId());
                    if (referenced != null) {
                        Geometry geometry = computeGeometry(referenced);
                        if (geometry != null) {
                            geometries.add(geometry);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to resolve reference {} for vicinity of {}: {}",
                            reference.getMessageId(), dataset.getMrn(), e.getMessage());
                }
            }
        }
        return geometries.isEmpty() ? null : UnaryUnionOp.union(geometries);
    }

    /** Computes the aggregate JTS geometry of the message features (null if the message has none) */
    public static Geometry computeGeometry(Message message) {
        List<Geometry> geometries = Arrays.stream(message.toGeoJson())
                .map(FeatureCollectionVo::getFeatures)
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                .map(FeatureVo::getGeometry)
                .map(JtsConverter::toJts)
                .filter(Objects::nonNull)
                .toList();
        return geometries.isEmpty() ? null : UnaryUnionOp.union(geometries);
    }

    /***************************************/
    /** Reconciliation                    **/
    /***************************************/

    /**
     * Result of a reconciliation sweep; {@code failed} counts the units the sweep could not complete: the eligible
     * messages whose S-124 GML could not be generated, which get no dataset written; the eligible messages whose unit
     * threw on its run and on its one re-run; and the stale datasets whose cancellation threw, which stay ACTIVE. The
     * next sweep tries each of them again.
     */
    public record ReconcileResult(int created, int updated, int cancelled, int unchanged, int failed) {
    }

    /**
     * Scheduled reconciliation: creates datasets missing for eligible published messages,
     * publishes a new version where the regenerated file differs from the current one, and
     * cancels datasets whose message is no longer published.
     */
    @Scheduled(cron = "${niord.secom.reconcile-cron:11 2/5 * * * ?}")
    @Transactional
    public void periodicReconcile() {
        try {
            ReconcileResult result = reconcile();
            if (result.created() + result.updated() + result.cancelled() + result.failed() > 0) {
                log.info("SECOM S-124 reconciliation: {}", result);
            }
        } catch (Exception e) {
            log.error("SECOM S-124 reconciliation failed", e);
        }
    }

    /** Outcome of one message's reconciliation unit */
    private enum ReconcileOutcome { CREATED, UPDATED, UNCHANGED, SKIPPED, FAILED }

    /**
     * Diffs eligible published messages against dataset rows; returns the changes made.
     * <p>
     * Each message/dataset is processed in its own committed transaction ({@link SecomTxRunner}),
     * so the sweep survives per-unit failures. An eligible unit does not race a concurrent create
     * of its dataset: every writer that can create a message's dataset (the eligible units, the
     * JMS status listener) takes the message's dataset lock first ({@link #lockByMessageUid}), so
     * those writers take turns and the second one finds the row the first committed (SECOM1's
     * create race, closed by this). The cancellation units take only the row lock
     * ({@link #cancelIfStale}); they never create a row. An eligible unit that fails is re-run
     * once, since such a failure may pass, and is logged and counted failed if it fails again; a
     * cancellation unit that fails is logged and counted failed. The sweep is idempotent, so the
     * next run retries what failed.
     */
    @Transactional
    public ReconcileResult reconcile() {
        int created = 0, updated = 0, cancelled = 0, unchanged = 0, failed = 0;

        // Eligible published messages -> ensure a current ACTIVE dataset exists.
        // Collect scalar UIDs only: each unit re-reads the entities in its own transaction.
        Set<String> seriesIds = scopedSeriesIds();
        List<String> eligibleUids = seriesIds.isEmpty()
                ? List.of()
                : em.createQuery("SELECT m.uid FROM Message m where m.status = :status and m.mainType = :mainType "
                        + " and m.number is not null and m.messageSeries.seriesId in :seriesIds", String.class)
                    .setParameter("status", Status.PUBLISHED)
                    .setParameter("mainType", MainType.NW)
                    .setParameter("seriesIds", seriesIds)
                    .getResultList();

        for (String uid : eligibleUids) {
            ReconcileOutcome outcome;
            try {
                outcome = txRunner.call(() -> reconcileEligible(uid));
            } catch (RuntimeException e) {
                // Not the dataset-create race against the JMS status listener (SECOM1): the writers
                // that can create a message's dataset take turns on its dataset lock, so none of
                // them inserts a row another has committed. Only this unit's transaction rolled
                // back; the single re-run stays for any other failure of a unit, which may pass on
                // a second try
                log.info("Reconciliation of {} failed ({}); re-running once", uid, e.getMessage());
                try {
                    outcome = txRunner.call(() -> reconcileEligible(uid));
                } catch (RuntimeException e2) {
                    log.error("Reconciliation of message {} failed; sweep continues", uid, e2);
                    failed++;
                    continue;
                }
            }
            switch (outcome) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
                case FAILED -> failed++;
                case SKIPPED -> { }
            }
        }

        // ACTIVE datasets whose message is no longer published-and-eligible -> cancel, one unit each
        List<Integer> activeIds = em.createQuery(
                "SELECT d.id FROM S124Dataset d where d.status = :status order by d.id asc", Integer.class)
                .setParameter("status", S124Dataset.Status.ACTIVE)
                .getResultList();
        for (Integer id : activeIds) {
            try {
                if (txRunner.call(() -> cancelIfStale(id))) {
                    cancelled++;
                }
            } catch (RuntimeException e) {
                log.error("Cancellation sweep of dataset {} failed; sweep continues", id, e);
                failed++;
            }
        }

        return new ReconcileResult(created, updated, cancelled, unchanged, failed);
    }

    /**
     * One eligible message's reconciliation; runs in its own transaction (via
     * {@link SecomTxRunner}). Re-reads the entities and re-checks the state, since the sweep
     * coordinator's snapshot may be stale by the time this unit runs.
     */
    private ReconcileOutcome reconcileEligible(String uid) {
        // The dataset locked before the message is read, see onMessageStatusChanged; the message's
        // dataset lock is held also when there is no row, so a cancel's writer waits for the create
        S124Dataset dataset = lockByMessageUid(uid);
        Message message = messageQueryService.findByUid(uid);
        if (message == null || message.getStatus() != Status.PUBLISHED || !isEligible(message)) {
            return ReconcileOutcome.SKIPPED;
        }
        if (dataset == null) {
            if (!upsertDataset(message, null)) {
                return ReconcileOutcome.FAILED;
            }
            // Flush so a failure of the insert (a constraint the new row breaks) surfaces inside
            // this unit, where it aborts only this unit's transaction, not at commit (the USR2
            // idiom). No concurrent writer creates the same row: they wait on the message's
            // dataset lock (lockByMessageUid)
            em.flush();
            return ReconcileOutcome.CREATED;
        }
        S124DatasetVersion version = dataset.getCurrentVersion();
        S124Dataset.Status status = dataset.getStatus();
        if (!upsertDataset(message, dataset)) {
            return ReconcileOutcome.FAILED;
        }
        return (status != S124Dataset.Status.ACTIVE || !Objects.equals(version, dataset.getCurrentVersion()))
                ? ReconcileOutcome.UPDATED
                : ReconcileOutcome.UNCHANGED;
    }

    /** Cancels the dataset if its message is no longer published-and-eligible; own transaction */
    private boolean cancelIfStale(Integer datasetId) {
        // Locked before its message is read, see onMessageStatusChanged
        S124Dataset dataset = em.find(S124Dataset.class, datasetId, LockModeType.PESSIMISTIC_WRITE);
        if (dataset == null || dataset.getStatus() != S124Dataset.Status.ACTIVE) {
            return false;   // raced away or already cancelled
        }
        Message message = dataset.getMessage();
        if (message.getStatus() == Status.PUBLISHED && isEligible(message)) {
            return false;   // (re-)published since the sweep collected it
        }
        cancelDataset(message, dataset);
        return true;
    }

    /***************************************/
    /** Queries                           **/
    /***************************************/

    /** Returns the dataset for the given message UID, or null if none exists */
    public S124Dataset findByMessageUid(String messageUid) {
        return getSingleOrNull(em.createNamedQuery("S124Dataset.findByMessageUid", S124Dataset.class)
                .setParameter("uid", messageUid)
                .getResultList());
    }

    /**
     * Returns the dataset of the given message locked until the caller's transaction ends
     * (a PESSIMISTIC_WRITE row lock), or null if none exists. Every writer of a dataset row takes
     * it through here or {@link #cancelIfStale} before it reads the message, so a writer that
     * waited reads the row and the message as the previous writer committed them. The callers run
     * in transactions of their own, where the row is not loaded yet, so the lock reads it afresh.
     * <p>
     * A row lock needs a row. So the message's dataset lock (a transaction-level advisory lock on
     * the message UID) comes first, and the row is looked up only once it is held. Without it, a
     * sweep unit that found no row and was generating the dataset of a published warning, while
     * the warning was cancelled, committed a new ACTIVE row after the cancel's writer had found no
     * row and done nothing: the cancelled warning was served and pushed as in force until the
     * sweep's cancellation pass. Now the cancel's writer waits for the unit and cancels its row.
     * The lock is held only by these writers. A cancel itself never waits for it, nor a reader.
     */
    private S124Dataset lockByMessageUid(String messageUid) {
        lockMessageDatasets(messageUid);
        Integer id = getSingleOrNull(em.createQuery(
                        "SELECT d.id FROM S124Dataset d where d.message.uid = :uid", Integer.class)
                .setParameter("uid", messageUid)
                .getResultList());
        // A row deleted in between is no row: the caller creates one if the message is published
        return id == null ? null : em.find(S124Dataset.class, id, LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * Takes the message's dataset lock until the caller's transaction ends: a transaction-level
     * advisory lock keyed on {@link #DATASET_LOCK_SPACE} and the hash of the message UID. A hash
     * shared by two messages only makes their writers take turns. The lookup that follows is a
     * statement of its own, so under READ COMMITTED it sees what the previous holder committed.
     */
    private void lockMessageDatasets(String messageUid) {
        em.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
                ps.setInt(1, DATASET_LOCK_SPACE);
                ps.setInt(2, messageUid.hashCode());
                ps.execute();
            }
        });
    }

    /** Returns the dataset with the given SECOM dataReference, or null if none exists */
    public S124Dataset findByUuid(UUID uuid) {
        return getSingleOrNull(em.createNamedQuery("S124Dataset.findByUuid", S124Dataset.class)
                .setParameter("uuid", uuid)
                .getResultList());
    }

    /**
     * The query behind SECOM Get/GetSummary: all datasets matching the given filters.
     * Dataset volumes are small (the active NW inventory), so geometry and validity
     * filtering happen in Java on the loaded rows.
     * <p>
     * Per the service spec (§4.1.3/§4.1.4), only active warnings are returned when the
     * consumer does not specify a temporal extent. A dataReference lookup is exempt, so a
     * known (possibly cancelled) dataset stays individually retrievable (use case 2).
     *
     * @param dataReference optional SECOM dataReference filter
     * @param geometry      optional JTS geometry filter (intersects)
     * @param validFrom     optional validity window start
     * @param validTo       optional validity window end
     */
    public List<S124Dataset> search(UUID dataReference, Geometry geometry, Date validFrom, Date validTo) {
        // dataReference narrows the candidate set but must not bypass the other filters -
        // a dataset outside the requested area/time window stays excluded
        List<S124Dataset> candidates;
        if (dataReference != null) {
            S124Dataset dataset = findByUuid(dataReference);
            candidates = dataset == null ? List.of() : List.of(dataset);
        } else {
            candidates = em.createNamedQuery("S124Dataset.findAll", S124Dataset.class).getResultList();
        }

        boolean activeOnly = dataReference == null && validFrom == null && validTo == null;

        List<S124Dataset> result = new ArrayList<>();
        for (S124Dataset dataset : candidates) {
            if (activeOnly && dataset.getStatus() != S124Dataset.Status.ACTIVE) {
                continue;
            }
            if (geometry != null && (dataset.getGeometry() == null || !geometry.intersects(dataset.getGeometry()))) {
                continue;
            }
            // Overlap of [validFrom, validTo] with the dataset validity window
            if (validFrom != null && dataset.getValidTo() != null && dataset.getValidTo().before(validFrom)) {
                continue;
            }
            if (validTo != null && dataset.getValidFrom() != null && dataset.getValidFrom().after(validTo)) {
                continue;
            }
            result.add(dataset);
        }
        return result;
    }

    /** Returns when the dataset store last changed (feeds the SECOM ping timestamp), or null if empty */
    public Date lastUpdated() {
        return em.createQuery("SELECT max(d.updated) FROM S124Dataset d", Date.class).getSingleResult();
    }
}
