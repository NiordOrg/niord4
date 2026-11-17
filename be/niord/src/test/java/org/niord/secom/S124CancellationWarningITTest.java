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
import org.niord.core.message.Message;
import org.niord.core.message.MessageKind;
import org.locationtech.jts.geom.Geometry;
import org.niord.model.message.Status;
import org.niord.s124.S124Service;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.SecomSubscription;
import org.niord.secom.util.S124Mrn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ITs for the SECOM side of the auto-created S-124 cancellation warnings (phase 2 of
 * {@code docs/s124-cancellation-plan.md}): cancelling a published NW of an auto-numbered series
 * mints a new, born-published cancellation warning, and the S-124 dataset pipeline treats it like
 * any other published message - a new ACTIVE dataset pushed to subscribers - while the cancelled
 * source's dataset is CANCELLED with a final push (existing behaviour, unchanged).
 *
 * <p>Like {@link S124DatasetPipelineITTest}, the tests are transactional (rolled back) and drive
 * the pipeline explicitly via {@link org.niord.secom.service.S124DatasetService#onMessageStatusChanged},
 * since the JMS listener runs in a separate transaction that cannot see uncommitted test data.
 * The minted cancellation warning takes the next current-year number of the {@code dma-nw}
 * series inside the test's transaction, so the number rolls back with the test (SERIES6). The
 * test's transaction holds that number's row until the rollback: a transaction of its own that
 * set or took the same number before then (as the capture and restore this class had did) would
 * wait for it for ever.
 */
class S124CancellationWarningITTest extends SecomITTest {

    @Autowired
    S124Service s124Service;

    /** The minted cancellation warning referencing the given message (in-transaction lookup) */
    private Message mintedCancellationWarning(String uid) {
        return em.createQuery("select m from Message m join m.references r "
                        + "where m.kind = :kind and r.messageId = :uid", Message.class)
                .setParameter("kind", MessageKind.CANCELLATION)
                .setParameter("uid", uid)
                .getResultStream().findFirst().orElse(null);
    }

    @Test
    @Transactional
    void cancellationWarningDatasetIsProducedAndPushedAlongsideTheFinalSourcePush() throws Exception {
        Message published = publishNw("secom-cancel-warning-" + uniq(), 55.5, 11.1);
        datasetService.onMessageStatusChanged(published.getUid());
        assertNotNull(datasetService.findByMessageUid(published.getUid()));

        // Subscribe after the initial production, so only the cancellation-related pushes count
        SecomSubscription template = new SecomSubscription();
        template.setDataProductType("S124");
        subscriptionService.subscribe(CLIENT_MRN, template);

        // The editorial cancel: cancels the source and mints the cancellation warning in one tx
        messageService.cancelMessage(published.getUid());

        Message cancellation = mintedCancellationWarning(published.getUid());
        assertNotNull(cancellation, "cancelling an auto-numbered NW series must mint a cancellation warning");
        assertEquals(Status.PUBLISHED, cancellation.getStatus());
        assertNotNull(cancellation.getNumber(), "the cancellation warning is numbered from the series");

        // Drive the pipeline for both messages, as the JMS listener would
        datasetService.onMessageStatusChanged(published.getUid());
        datasetService.onMessageStatusChanged(cancellation.getUid());

        // The source dataset is cancelled and its final GML pushed (existing behaviour, unchanged)
        S124Dataset sourceDataset = datasetService.findByMessageUid(published.getUid());
        assertEquals(S124Dataset.Status.CANCELLED, sourceDataset.getStatus());
        assertNotNull(sourceDataset.getValidTo(), "cancellation stamps the end of the validity window");

        // The cancellation warning gets its own ACTIVE dataset with the cancellation-only GML
        S124Dataset cancellationDataset = datasetService.findByMessageUid(cancellation.getUid());
        assertNotNull(cancellationDataset, "the cancellation warning must be eligible for the store");
        assertEquals(S124Dataset.Status.ACTIVE, cancellationDataset.getStatus());
        assertEquals(S124Mrn.of(cancellation, s124Service.country()), cancellationDataset.getMrn());
        assertEquals(cancellation.getPublishDateFrom(), cancellationDataset.getValidFrom());
        // The cancellation-only message carries no geometry of its own; its push vicinity is
        // aggregated purely from the referenced source's geometry, so the original subscribers are
        // reached. It must therefore equal the cancelled source's own point (published at 55.5, 11.1),
        // not merely be non-null.
        assertNull(cancellationDataset.getGeometry());
        Geometry sourceGeometry = sourceDataset.getGeometry();
        assertNotNull(sourceGeometry, "the source dataset carries the cancelled warning's own point");
        Geometry vicinity = datasetService.computeVicinityGeometry(cancellationDataset);
        assertNotNull(vicinity, "the push vicinity must aggregate the referenced source's geometry");
        assertTrue(vicinity.equalsTopo(sourceGeometry),
                "the cancellation's push vicinity must equal the cancelled source's geometry");
        // JtsConverter maps (lat, lon) -> Point(x=lon, y=lat); assert the actual (11.1, 55.5) point
        assertEquals(11.1, vicinity.getCentroid().getX(), 1e-6,
                "the vicinity must cover the source warning's longitude");
        assertEquals(55.5, vicinity.getCentroid().getY(), 1e-6,
                "the vicinity must cover the source warning's latitude");
        String cancellationGml = cancellationDataset.getCurrentVersion().getGml();
        assertTrue(cancellationGml.contains("referenceCategory code=\"1\""),
                "the published GML must carry the category-1 (Warning Cancellation) reference");
        assertTrue(cancellationGml.contains(S124Mrn.of(published, s124Service.country())),
                "the published GML must reference the cancelled warning's MRN");

        // Both the source's final GML and the new cancellation dataset were pushed to the subscriber
        assertEquals(2, uploadEntries().size(),
                "expected the final source push plus the cancellation-warning push");
    }

    @Test
    @Transactional
    void cancellingAMintedCancellationWarningOnlyPushesTheFinalGml() throws Exception {
        // The terminal rule at the SECOM layer: cancelling a minted cancellation warning mints
        // nothing further, so its dataset just gets the ordinary final (cancellation) push
        Message published = publishNw("secom-cancel-terminal-" + uniq(), 55.5, 11.1);
        datasetService.onMessageStatusChanged(published.getUid());

        messageService.cancelMessage(published.getUid());
        Message cancellation = mintedCancellationWarning(published.getUid());
        assertNotNull(cancellation, "cancelling the source mints the cancellation warning");
        datasetService.onMessageStatusChanged(published.getUid());
        datasetService.onMessageStatusChanged(cancellation.getUid());

        // Subscribe only now, so that exactly the pushes caused by the second cancel are counted
        SecomSubscription template = new SecomSubscription();
        template.setDataProductType("S124");
        subscriptionService.subscribe(CLIENT_MRN, template);

        messageService.cancelMessage(cancellation.getUid());

        assertNull(mintedCancellationWarning(cancellation.getUid()),
                "the terminal rule: a cancellation warning never begets another");

        datasetService.onMessageStatusChanged(cancellation.getUid());
        assertEquals(S124Dataset.Status.CANCELLED,
                datasetService.findByMessageUid(cancellation.getUid()).getStatus());
        assertEquals(1, uploadEntries().size(), "only the final (cancellation) GML is pushed");
    }
}
