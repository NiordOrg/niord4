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
package org.niord.secom;

import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.area.AreaType;
import org.niord.core.message.Message;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.s124.S124Service;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.S124DatasetVersion;
import org.niord.secom.model.SecomOutboxEntry;
import org.niord.secom.model.SecomSubscription;
import org.niord.secom.service.S124DatasetService;
import org.niord.secom.util.MrnToUuid;
import org.niord.secom.util.S124Mrn;
import org.niord.test.MessageTestFactory;
import org.niord.test.Tx;
import org.niord.test.xml.XmlAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ITs for the S-124 dataset pipeline: publish/update/cancel of eligible messages must
 * produce, refresh and cancel dataset rows with stable identity, honor the series scoping,
 * and enqueue outbox deliveries only to subscriptions whose filters match.
 * <p>
 * The pipeline tests are transactional (rolled back): the JMS status events fired by
 * {@code MessageService.updateStatus} are processed by the SECOM listener in a separate
 * transaction that cannot see this uncommitted data, so the pipeline is driven explicitly
 * via {@link S124DatasetService#onMessageStatusChanged}. The reconciliation tests instead
 * run on committed data ({@code Tx.requiringNew()} + settling the real listener), because
 * the sweep processes each message in its own transaction, which cannot see uncommitted
 * test rows either.
 */
class S124DatasetPipelineITTest extends SecomITTest {

    @Autowired
    AreaService areaService;

    @Autowired
    S124Service s124Service;

    private S124Dataset produceDataset(Message published) {
        datasetService.onMessageStatusChanged(published.getUid());
        return datasetService.findByMessageUid(published.getUid());
    }

    /** A persisted root area with a geometry (an extent in the Danish waters) */
    private Area geotaggedArea(String name) {
        Area area = new Area();
        area.setType(AreaType.COUNTRY);
        area.checkCreateDesc("en").setName(name);
        area.setGeometry(org.niord.core.geojson.JtsConverter.toJtsExtent(54.0, 8.0, 58.0, 14.0));
        Area created = areaService.createArea(area, null);
        em.flush();
        return created;
    }

    private SecomSubscription subscribe(SecomSubscription template) {
        template.setDataProductType("S124");
        return subscriptionService.subscribe(CLIENT_MRN, template);
    }

    /** The committed UPLOAD outbox entry count (the reconciliation tests run on committed data) */
    private long committedUploadCount() {
        return Tx.requiringNew().call(() -> (long) uploadEntries().size());
    }

    // ------------------------------------------------------------------------------------------- //
    // Dataset production
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void publishingAnEligibleNwCreatesADataset() {
        Message published = publishNw("secom-pipe-" + uniq(), 55.5, 11.1);

        S124Dataset dataset = produceDataset(published);

        assertNotNull(dataset, "a dataset row must be created for a published, scoped NW");
        assertEquals(S124Dataset.Status.ACTIVE, dataset.getStatus());
        assertEquals(S124Mrn.of(published, s124Service.country()), dataset.getMrn());
        assertEquals(MrnToUuid.fromMrn(dataset.getMrn()), dataset.getUuid(), "dataReference is derived from the MRN");
        assertNotNull(dataset.getGeometry(), "the dataset geometry is aggregated from the message features");
        assertEquals(published.getPublishDateFrom(), dataset.getValidFrom());
        S124DatasetVersion version = dataset.getCurrentVersion();
        assertNotNull(version, "publishing writes the dataset's first version");
        assertTrue(version.getGml().contains("http://www.iho.int/S124/gml/2.0"),
                "the published GML is an S-124 v2.0.0 dataset");
        assertTrue(version.getGml().contains(dataset.getMrn()),
                "the GML interoperabilityIdentifier must carry the same MRN as the dataset row");
        assertEquals(published.getShortId(), version.getDatasetId(), "the gml:id GetSummary reports, captured at publish");
        assertEquals("Niord S-124 Dataset", version.getDatasetTitle());
        assertEquals(List.of(version), versionsOf(dataset), "the first publication is the whole history");
    }

    @Test
    @Transactional
    void productionIsIdempotentForUnchangedMessages() {
        Message published = publishNw("secom-idem-" + uniq(), 55.5, 11.1);
        subscribe(new SecomSubscription());

        datasetService.onMessageStatusChanged(published.getUid());
        assertEquals(1, uploadEntries().size(), "first production pushes to the matching subscription");

        datasetService.onMessageStatusChanged(published.getUid());
        assertEquals(1, uploadEntries().size(), "unchanged GML must not enqueue again");
    }

    @Test
    @Transactional
    void cancellingTheMessageCancelsTheDatasetAndPushesTheFinalGml() throws Exception {
        Message published = publishNw("secom-cancel-" + uniq(), 55.5, 11.1);
        S124Dataset dataset = produceDataset(published);
        UUID uuid = dataset.getUuid();
        subscribe(new SecomSubscription());

        messageService.updateStatus(published.getUid(), Status.CANCELLED);
        datasetService.onMessageStatusChanged(published.getUid());

        S124Dataset cancelled = datasetService.findByUuid(uuid);
        assertEquals(S124Dataset.Status.CANCELLED, cancelled.getStatus());
        assertNotNull(cancelled.getValidTo(), "cancellation stamps the end of the validity window");
        assertEquals(1, uploadEntries().size(), "the final (cancellation) dataset is pushed to subscribers");
    }

    @Test
    @Transactional
    void datasetsCarryEveryModelLanguageUnlessTheSeriesRestrictsThem() {
        Message published = publishNw("secom-lang-" + uniq(), 55.5, 11.1, draft -> {
            draft.createDesc("da").setTitle("Dansk titel");
            draft.getParts().get(0).createDesc("da").setDetails("<p>danske detaljer</p>");
        });

        // No restriction: both model languages the message has content in, English first
        S124Dataset dataset = produceDataset(published);
        S124DatasetVersion first = dataset.getCurrentVersion();
        assertEquals(List.of("en", "da"), first.getLanguages());
        assertTrue(first.getGml().contains("danske detaljer"), "the Danish part text is carried");
        assertTrue(first.getGml().contains("Dansk titel"), "the Danish title is carried");
        XmlAssert.parse(first.getGml()).assertElementText("datasetLanguage", "eng");

        // Restricting the series to English narrows the dataset; the changed bytes are a new
        // version, exactly as an edit would be
        secomConfigService.upsertSeriesConfig("dma-nw", true, List.of("en"));
        datasetService.onMessageStatusChanged(published.getUid());
        S124Dataset narrowed = datasetService.findByMessageUid(published.getUid());
        S124DatasetVersion second = narrowed.getCurrentVersion();
        assertEquals(List.of("en"), second.getLanguages());
        assertFalse(second.getGml().contains("danske detaljer"), "nothing Danish in an English-only series");
        assertFalse(second.getGml().contains("Dansk titel"));
        assertEquals(2, versionsOf(narrowed).size(), "narrowing the languages re-issues the dataset");

        // The same restriction again changes nothing
        datasetService.onMessageStatusChanged(published.getUid());
        assertEquals(2, versionsOf(datasetService.findByMessageUid(published.getUid())).size());
    }

    @Test
    @Transactional
    void messagesOutsideTheScopedSeriesAreIgnored() {
        secomConfigService.upsertSeriesConfig("dma-nw", false, List.of());
        secomConfigService.upsertSeriesConfig("dma-nm", true, List.of());
        Message published = publishNw("secom-scope-" + uniq(), 55.5, 11.1);

        assertNull(produceDataset(published), "messages of series not exposed over SECOM must not become datasets");
    }

    @Test
    @Transactional
    void nmMessagesAreNeverEligible() throws Exception {
        Message nmDraft = MessageTestFactory.draft("dma-nm", Type.PERMANENT_NOTICE, "secom-nm-" + uniq());
        Message created = messageService.createMessage(nmDraft);
        secomConfigService.upsertSeriesConfig("dma-nm", true, List.of());

        messageService.updateStatus(created.getUid(), Status.VERIFIED);
        Message published = messageService.updateStatus(created.getUid(), Status.PUBLISHED);
        datasetService.onMessageStatusChanged(published.getUid());

        assertNull(datasetService.findByMessageUid(published.getUid()), "S-124 only covers NW messages");
    }

    // ------------------------------------------------------------------------------------------- //
    // Subscription matching
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void pushesHonorTheSubscriptionFilters() {
        // Geometry filter: subscription area far away from the message -> no push
        SecomSubscription farAway = new SecomSubscription();
        farAway.setGeometry(org.niord.core.geojson.JtsConverter.toJtsExtent(-40.0, -40.0, -39.0, -39.0));
        subscribe(farAway);

        // Version filter mismatch -> no push
        SecomSubscription wrongVersion = new SecomSubscription();
        wrongVersion.setProductVersion("9.9.9");
        subscribe(wrongVersion);

        // dataReference filter for a different dataset -> no push
        SecomSubscription otherReference = new SecomSubscription();
        otherReference.setDataReference(UUID.randomUUID());
        subscribe(otherReference);

        // Overlapping geometry + matching version -> push
        SecomSubscription matching = new SecomSubscription();
        matching.setGeometry(org.niord.core.geojson.JtsConverter.toJtsExtent(50.0, 5.0, 60.0, 15.0));
        matching.setProductVersion(S124DatasetService.S124_PRODUCT_VERSION);
        SecomSubscription match = subscribe(matching);

        Message published = publishNw("secom-match-" + uniq(), 55.5, 11.1);
        datasetService.onMessageStatusChanged(published.getUid());

        List<SecomOutboxEntry> uploads = uploadEntries();
        assertEquals(1, uploads.size(), "only the matching subscription receives the dataset");
        assertEquals(match.getUuid(), uploads.get(0).getSubscriptionUuid());
        assertEquals(SecomOutboxEntry.Status.PENDING, uploads.get(0).getStatus());
        assertNotNull(uploads.get(0).getPayload(), "the outbox snapshots the exact pushed payload");
    }

    @Test
    @Transactional
    void pushedPayloadsFollowTheSubscriptionContainerType() {
        // No container type -> normalized to the design-mandated exchange set at creation,
        // so a later subscription spelling the default explicitly dedupes against it
        // instead of double-pushing identical payloads
        SecomSubscription defaulted = subscribe(new SecomSubscription());
        assertEquals("S100_ExchangeSet", defaulted.getContainerType(),
                "the persisted subscription carries the effective container");
        SecomSubscription exchangeSetTemplate = new SecomSubscription();
        exchangeSetTemplate.setContainerType("S100_ExchangeSet");
        assertEquals(defaulted.getUuid(), subscribe(exchangeSetTemplate).getUuid(),
                "the explicit default is the same subscription");

        // Legacy bare-dataset container -> a distinct subscription with the exact stored GML
        SecomSubscription bareTemplate = new SecomSubscription();
        bareTemplate.setContainerType("S100_DataSet");
        SecomSubscription bare = subscribe(bareTemplate);

        Message published = publishNw("secom-container-" + uniq(), 55.5, 11.1);
        S124Dataset dataset = produceDataset(published);

        List<SecomOutboxEntry> uploads = uploadEntries();
        assertEquals(2, uploads.size(), "one push per (deduplicated) subscription");

        for (SecomOutboxEntry upload : uploads) {
            byte[] payload = upload.getPayload();
            if (upload.getSubscriptionUuid().equals(bare.getUuid())) {
                assertEquals("S100_DataSet", upload.getContainerType());
                assertEquals(dataset.getCurrentVersion().getGml(),
                        new String(payload, java.nio.charset.StandardCharsets.UTF_8),
                        "bare-dataset pushes snapshot the exact published GML");
            } else {
                assertEquals(defaulted.getUuid(), upload.getSubscriptionUuid());
                assertEquals("S100_ExchangeSet", upload.getContainerType());
                assertTrue(payload.length > 2 && payload[0] == 'P' && payload[1] == 'K',
                        "exchange-set pushes snapshot a ZIP archive");
            }
        }
    }

    @Test
    @Transactional
    void nonGeotaggedDatasetsIgnoreTheGeometryCriterion() throws Exception {
        // A geometry-filtered subscription must still receive datasets that are not geotagged -
        // the design (§6.5.4) says the criterion "shall be ignored for that dataset"
        SecomSubscription geoFiltered = new SecomSubscription();
        geoFiltered.setGeometry(org.niord.core.geojson.JtsConverter.toJtsExtent(50.0, 5.0, 60.0, 15.0));
        subscribe(geoFiltered);

        // No positions of its own, only an area of applicability: the dataset row is not geotagged
        // (features only), while the GML takes the extent S-124 clause 12.2.2 requires of every
        // distributed dataset from the area - the two are separate readings of "geometry"
        Message draft = MessageTestFactory.draftNw("secom-nogeo-" + uniq());
        draft.getAreas().add(geotaggedArea("secom-nogeo-area-" + uniq()));
        Message created = messageService.createMessage(draft);
        messageService.updateStatus(created.getUid(), Status.VERIFIED);
        Message published = messageService.updateStatus(created.getUid(), Status.PUBLISHED);

        S124Dataset dataset = produceDataset(published);
        assertNotNull(dataset, "a dataset is produced for the non-geotagged message");
        assertNull(dataset.getGeometry(), "the message carries no features, so the dataset is not geotagged");
        assertEquals(1, uploadEntries().size(), "the geometry criterion is ignored for non-geotagged datasets");
    }

    @Test
    @Transactional
    void pushesCoverTheMessageVicinityOfReferencedMessages() {
        // Subscriber for the area around the ORIGINAL warning only
        SecomSubscription aroundOriginal = new SecomSubscription();
        aroundOriginal.setGeometry(org.niord.core.geojson.JtsConverter.toJtsExtent(50.0, 5.0, 60.0, 15.0));
        SecomSubscription subscription = subscribe(aroundOriginal);

        Message original = publishNw("secom-vicinity-orig-" + uniq(), 55.5, 11.1);
        datasetService.onMessageStatusChanged(original.getUid());
        assertEquals(1, uploadEntries().size(), "the original warning reaches the area subscriber");

        // A follow-up warning far outside the subscription area, referencing (cancelling) the
        // original - per the spec's Message Vicinity rule (§6.3.1) it must still be delivered
        Message followUp = publishNw("secom-vicinity-cancel-" + uniq(), -39.5, -39.5);
        org.niord.core.message.Reference reference = new org.niord.core.message.Reference();
        reference.setMessageId(original.getUid());
        reference.setType(org.niord.model.message.ReferenceType.CANCELLATION);
        followUp.addReference(reference);

        datasetService.onMessageStatusChanged(followUp.getUid());

        List<SecomOutboxEntry> uploads = uploadEntries();
        assertEquals(2, uploads.size(), "the far-away follow-up intersects the vicinity of the referenced warning");
        assertTrue(uploads.stream().allMatch(u -> u.getSubscriptionUuid().equals(subscription.getUuid())));
    }

    // ------------------------------------------------------------------------------------------- //
    // Subscription lifecycle (provider-initiated removals)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void cancellingADatasetTerminatesSubscriptionsPinnedToIt() throws Exception {
        Message published = publishNw("secom-dataref-" + uniq(), 55.5, 11.1);

        // The dataReference is deterministic (derived from the MRN), so the subscription can
        // be pinned before the dataset row is first produced
        SecomSubscription pinned = new SecomSubscription();
        pinned.setDataReference(MrnToUuid.fromMrn(S124Mrn.of(published, s124Service.country())));
        SecomSubscription subscription = subscribe(pinned);

        datasetService.onMessageStatusChanged(published.getUid());
        assertEquals(1, uploadEntries().size(), "the pinned subscription receives the dataset");

        messageService.updateStatus(published.getUid(), Status.CANCELLED);
        datasetService.onMessageStatusChanged(published.getUid());

        assertEquals(2, uploadEntries().size(), "the final (cancellation) push is enqueued first");
        assertNull(subscriptionService.findByUuid(subscription.getUuid()),
                "a dataReference subscription terminates when its dataset ceases (design §6.5.4)");
        assertTrue(outboxService.search(null, 100).stream()
                        .anyMatch(o -> o.getOperation() == SecomOutboxEntry.Operation.SUBSCRIPTION_NOTIFICATION
                                && "SUBSCRIPTION_REMOVED".equals(o.getNotificationEvent())
                                && subscription.getUuid().equals(o.getSubscriptionUuid())),
                "the subscriber is notified of the provider-initiated removal");
    }

    @Test
    @Transactional
    void expiredSubscriptionsAreRemovedAndNotified() {
        SecomSubscription expired = new SecomSubscription();
        expired.setPeriodEnd(new Date(System.currentTimeMillis() - 60_000));
        SecomSubscription subscription = subscribe(expired);

        subscriptionService.removeExpiredSubscriptions();

        assertNull(subscriptionService.findByUuid(subscription.getUuid()),
                "a subscription past its subscriptionPeriodEnd is removed, not just skipped");
        assertTrue(outboxService.search(null, 100).stream()
                        .anyMatch(o -> o.getOperation() == SecomOutboxEntry.Operation.SUBSCRIPTION_NOTIFICATION
                                && "SUBSCRIPTION_REMOVED".equals(o.getNotificationEvent())
                                && subscription.getUuid().equals(o.getSubscriptionUuid())),
                "the subscriber is notified of the expiry removal");
    }

    // ------------------------------------------------------------------------------------------- //
    // Reconciliation
    // ------------------------------------------------------------------------------------------- //

    @Test
    void reconcileCreatesMissingDatasetsAndIsIdempotent() throws Exception {
        Message published1 = Tx.requiringNew().call(() -> publishNw("secom-rec1-" + uniq(), 55.5, 11.1));
        Message published2 = Tx.requiringNew().call(() -> publishNw("secom-rec2-" + uniq(), 56.5, 12.1));
        assertNotNull(awaitDatasetForMessage(published1.getUid()));
        assertNotNull(awaitDatasetForMessage(published2.getUid()));
        Tx.requiringNew().run(() -> deleteDatasetsOfMessages(List.of(published1.getUid(), published2.getUid())));

        // The sweep is global: published messages committed by other tests are backfilled too,
        // so assert on this test's own messages plus the at-least count
        S124DatasetService.ReconcileResult first = datasetService.reconcile();
        assertTrue(first.created() >= 2, "reconcile backfills datasets for all published, scoped NWs, got " + first);

        S124DatasetService.ReconcileResult second = datasetService.reconcile();
        assertEquals(0, second.created() + second.updated() + second.cancelled(), "reconcile is idempotent");
        assertEquals(0, second.failed(), "every fixture NW generates: " + second);
        assertTrue(second.unchanged() >= 2);

        Tx.requiringNew().run(() -> {
            assertNotNull(datasetService.findByMessageUid(published1.getUid()));
            assertNotNull(datasetService.findByMessageUid(published2.getUid()));
        });
    }

    /**
     * The fixture's sample NW-001-26 names charts and gets a dataset like any NW: no affected chart publication is
     * written, since S-124 2.0's chartAffected requires an editionDate after the chartNumber, which a Niord chart
     * lacks, and affectedChartPublications a language, which the mapper never set (S124Test; before, every NW
     * naming a chart failed the schema and got none), and the sweep counts no message failed. The message is found
     * by its short id: its uid is the fixture's, not the sample's, and no uid is written here, since this class
     * travels in niord-base's test-jar, which the no-sample check reads.
     */
    @Test
    void theSampleNwNamingAChartGetsADataset() {
        String sampleUid = Tx.requiringNew().call(() ->
                em.createQuery("select m.uid from Message m where m.shortId = :shortId", String.class)
                        .setParameter("shortId", "NW-001-26")
                        .getSingleResult());

        S124DatasetService.ReconcileResult result = datasetService.reconcile();

        assertEquals(0, result.failed(), "every fixture NW generates, the chart-naming ones too: " + result);
        Tx.requiringNew().run(() -> {
            S124Dataset dataset = datasetService.findByMessageUid(sampleUid);
            assertNotNull(dataset, "the sample's chart-naming NW has a dataset");
            assertEquals(S124Dataset.Status.ACTIVE, dataset.getStatus());
        });
    }

    @Test
    void reconcileCancelsDatasetsWhoseMessageLeftThePublishedState() throws Exception {
        Message published = Tx.requiringNew().call(() -> publishNw("secom-rec-cancel-" + uniq(), 55.5, 11.1));
        S124Dataset dataset = awaitDatasetForMessage(published.getUid());
        assertNotNull(dataset);
        assertEquals(S124Dataset.Status.ACTIVE, dataset.getStatus());

        // Cancel the message directly in the database - as if the JMS event had been lost -
        // so the reconcile sweep is the only thing that can cancel the dataset
        Tx.requiringNew().run(() ->
                em.createQuery("update Message m set m.status = :status where m.uid = :uid")
                        .setParameter("status", Status.CANCELLED)
                        .setParameter("uid", published.getUid())
                        .executeUpdate());

        S124DatasetService.ReconcileResult result = datasetService.reconcile();
        assertEquals(1, result.cancelled(),
                "only this dataset is a cancellation candidate - other tests' backfills stay published");
        Tx.requiringNew().run(() ->
                assertEquals(S124Dataset.Status.CANCELLED, datasetService.findByUuid(dataset.getUuid()).getStatus()));
    }

    @Test
    void reconcileSurvivesConcurrentDatasetCreate() throws Exception {
        // The SECOM1 shape distilled: two creators - the JMS listener's shape on a second
        // thread, the reconcile sweep on this one - target the same missing dataset row.
        // They take turns on the message's dataset lock, so the second finds the row the
        // first committed; whatever the interleaving, exactly one row must be left
        Message published = Tx.requiringNew().call(() -> publishNw("secom-race-" + uniq(), 55.5, 11.1));
        assertNotNull(awaitDatasetForMessage(published.getUid()));
        Tx.requiringNew().run(() -> deleteDatasetsOfMessages(List.of(published.getUid())));

        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread listener = new Thread(() -> {
            try {
                barrier.await();
                // The listener's exact shape; when the sweep's unit holds the message's dataset
                // lock it waits, then finds the sweep's committed row (or it goes first and
                // creates the row the sweep then finds)
                datasetService.onMessageStatusChanged(published.getUid());
            } catch (Throwable ignored) {
                // the real listener logs a failure and leaves it to the sweep; the row count
                // below is what this test checks
            }
        });
        listener.start();

        barrier.await();
        datasetService.reconcile();   // must not throw, whatever the interleaving
        listener.join(10_000);
        assertFalse(listener.isAlive(), "the concurrent creator must finish");

        Tx.requiringNew().run(() -> {
            Long rows = em.createQuery("select count(d) from S124Dataset d where d.message.uid = :uid", Long.class)
                    .setParameter("uid", published.getUid())
                    .getSingleResult();
            assertEquals(1L, rows, "exactly one dataset row survives the race");
            assertEquals(S124Dataset.Status.ACTIVE,
                    datasetService.findByMessageUid(published.getUid()).getStatus());
        });
    }

    @Test
    void reconcileLeavesAnUnchangedDatasetsVersionAloneAndPushesNothing() throws Exception {
        // Committed variant of the pipeline (the sweep's per-message transactions cannot see
        // uncommitted rows). The subscription is pinned to this dataset's dataReference, so
        // the sweep's backfills of other tests' lingering messages cannot enqueue to it
        Message published = Tx.requiringNew().call(() -> publishNw("secom-steady-" + uniq(), 55.5, 11.1));
        assertNotNull(awaitDatasetForMessage(published.getUid()));
        Tx.requiringNew().run(() -> deleteDatasetsOfMessages(List.of(published.getUid())));

        Tx.requiringNew().run(() -> {
            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            template.setDataReference(MrnToUuid.fromMrn(S124Mrn.of(published, s124Service.country())));
            subscriptionService.subscribe(CLIENT_MRN, template);
        });
        Tx.requiringNew().run(() -> datasetService.onMessageStatusChanged(published.getUid()));
        assertEquals(1, committedUploadCount(), "recreating the dataset pushes to the pinned subscription");
        Integer versionId = Tx.requiringNew().call(() ->
                datasetService.findByMessageUid(published.getUid()).getCurrentVersion().getId());

        S124DatasetService.ReconcileResult result = datasetService.reconcile();

        // A regeneration that yields the published bytes again is not a new version: the
        // signed record stays as it is, and nothing is re-pushed
        Tx.requiringNew().run(() -> {
            S124Dataset dataset = datasetService.findByMessageUid(published.getUid());
            assertEquals(versionId, dataset.getCurrentVersion().getId(), "the current version is untouched");
            assertEquals(1, versionsOf(dataset).size(), "no version is appended for unchanged content");
        });
        assertTrue(result.unchanged() >= 1, "unchanged content counts as unchanged");
        assertEquals(1, committedUploadCount(), "unchanged content must not re-push");
    }

    @Test
    @Transactional
    void reconcileDoesNothingWithAnEmptySeriesScope() {
        publishNw("secom-noscope-" + uniq(), 55.5, 11.1);
        secomConfigService.deleteSeriesConfig("dma-nw");

        S124DatasetService.ReconcileResult result = datasetService.reconcile();
        assertEquals(0, result.created(), "no exposed series exposes nothing (safe default)");
    }
}
