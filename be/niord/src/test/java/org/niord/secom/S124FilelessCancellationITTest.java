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

import org.grad.eNav.s100.utils.S100ExchangeSetUtils;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageKind;
import org.niord.model.message.Status;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.S124DatasetVersion;
import org.niord.secom.service.S124DatasetService;
import org.niord.secom.service.S124ExchangeSetService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import dk.dma.niord.s100.catalog._5_2.S100DatasetDiscoveryMetadata;
import dk.dma.niord.s100.catalog._5_2.S100Purpose;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ITs for the S-100 Part 17 clause 17-4.4.1 fileless dataset cancellations and the immutable
 * published versions behind them (S-124R010/R011): publishing signs the dataset once, as a
 * version every served exchange set ships verbatim under the same signature; cancelling appends
 * the final version, stamps the 365-day window, and every subsequently built exchange set
 * announces the cancelled dataset as a {@code purpose=cancellation} discovery-metadata entry
 * reusing that version's signature — including the exchange set of the auto-minted cancellation
 * warning itself.
 * <p>
 * The tests are transactional (rolled back) and drive the pipeline explicitly via
 * {@link S124DatasetService#onMessageStatusChanged}, since the JMS listener runs in a
 * separate transaction that cannot see uncommitted test data. The numbers the tests take,
 * the auto-minted cancellation warning's current-year one included, are taken inside the
 * test's transaction and roll back with it (SERIES6); the test's transaction holds their rows
 * until then, so nothing here sets a number in a transaction of its own.
 */
class S124FilelessCancellationITTest extends SecomITTest {

    @Autowired
    S124ExchangeSetService exchangeSetService;

    /**
     * How the catalogue spells a dataset's {@code fileName}: the path the archive packages it
     * under, relative to {@code CATALOG.XML} (S-100 Part 17, clause 17-4.2), as the bindings emit
     * as of 0.3.1. The persisted {@code S124DatasetVersion.fileName} stays the bare name.
     */
    private static final String CATALOGUE_FILE_URI_PREFIX = "file:/S-124/DATASET_FILES/";

    private S124Dataset produceDataset(Message published) {
        datasetService.onMessageStatusChanged(published.getUid());
        return datasetService.findByMessageUid(published.getUid());
    }

    // ------------------------------------------------------------------------------------------- //
    // Persisted signature
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void publishingSignsTheDatasetOnceAndExchangeSetsShipThatVersionVerbatim() throws Exception {
        Message published = publishNw("secom-sign-" + uniq(), 55.5, 11.1);
        S124Dataset dataset = produceDataset(published);

        // Publishing wrote the signed version: bytes, entry, signature and the chain that made it
        S124DatasetVersion version = dataset.getCurrentVersion();
        assertNotNull(version, "publishing must publish a version");
        assertNotNull(version.getSignature(), "publishing must sign the dataset");
        assertNotNull(version.getDiscoveryMetadata());
        assertFalse(version.certificatePems().isEmpty(), "the chain that signed it is part of the record");
        // The S-100 Part 17 clause 17-4.3 name the factory published - the header's own
        // datasetFileIdentifier: 124, the producer code, the alphanumeric short id, .GML
        assertEquals("124DK00" + published.getShortId().replaceAll("[^A-Za-z0-9]", "") + ".GML",
                version.getFileName(),
                "the persisted file name is the name the factory published for the served single-dataset sets");

        // Two independently built exchange sets ship the SAME bytes under the SAME persisted
        // signature (a fresh ECDSA signature would differ per build), under the persisted file name
        byte[] zip1 = exchangeSetService.buildExchangeSet(dataset);
        byte[] zip2 = exchangeSetService.buildExchangeSet(dataset);
        String file = "S100_ROOT/S-124/DATASET_FILES/" + version.getFileName();
        assertArrayEquals(version.gmlBytes(), unzip(zip1).get(file), "the version's bytes ship verbatim");
        assertArrayEquals(version.gmlBytes(), unzip(zip2).get(file));
        S100DatasetDiscoveryMetadata first = soleEntry(zip1);
        S100DatasetDiscoveryMetadata second = soleEntry(zip2);
        assertEquals(CATALOGUE_FILE_URI_PREFIX + version.getFileName(), first.getFileName());
        assertArrayEquals(version.getSignature(), signatureBytes(first));
        assertArrayEquals(version.getSignature(), signatureBytes(second));
    }

    // The reconcile steady-state test lives in S124DatasetPipelineITTest: the sweep's
    // per-message transactions require committed data, and this class restores the message
    // number sequence after each test, which is only safe while every message rolls back.

    // ------------------------------------------------------------------------------------------- //
    // Fileless cancellation entries
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void cancellingEmitsAFilelessCancellationInEveryLaterExchangeSet() throws Exception {
        Message cancelledMsg = publishNw("secom-fileless-" + uniq(), 55.5, 11.1);
        Message activeMsg = publishNw("secom-active-" + uniq(), 56.5, 12.1);
        S124Dataset cancelledDataset = produceDataset(cancelledMsg);
        S124Dataset activeDataset = produceDataset(activeMsg);
        S124DatasetVersion originalVersion = cancelledDataset.getCurrentVersion();
        byte[] originalSignature = originalVersion.getSignature();

        messageService.updateStatus(cancelledMsg.getUid(), Status.CANCELLED);
        datasetService.onMessageStatusChanged(cancelledMsg.getUid());

        cancelledDataset = datasetService.findByMessageUid(cancelledMsg.getUid());
        assertEquals(S124Dataset.Status.CANCELLED, cancelledDataset.getStatus());
        assertNotNull(cancelledDataset.getCancelled(), "cancelling stamps the 365-day window timestamp");
        // The final GML (carrying the in-band cancellation) is a new, signed version; the
        // version consumers hold is untouched - the history is immutable
        S124DatasetVersion finalVersion = cancelledDataset.getCurrentVersion();
        assertNotEquals(originalVersion.getId(), finalVersion.getId(), "cancelling appends the final version");
        assertEquals(List.of(originalVersion.getId(), finalVersion.getId()),
                versionsOf(cancelledDataset).stream().map(S124DatasetVersion::getId).toList());
        assertArrayEquals(originalSignature, versionsOf(cancelledDataset).get(0).getSignature(),
                "the published version keeps the signature it was published with");
        assertEquals(originalVersion.getFileName(), finalVersion.getFileName(), "the file name is version-independent");

        // The exchange set of the still-active dataset announces the cancellation, fileless
        byte[] zip = exchangeSetService.buildExchangeSet(activeDataset);
        Map<String, byte[]> entries = unzip(zip);
        assertTrue(entries.containsKey("S100_ROOT/S-124/DATASET_FILES/" + activeDataset.getCurrentVersion().getFileName()));
        assertEquals(1, entries.keySet().stream()
                        .filter(n -> n.startsWith("S100_ROOT/S-124/DATASET_FILES/") && n.endsWith(".GML")).count(),
                "the cancelled dataset must not ship a file");

        List<S100DatasetDiscoveryMetadata> metadata = catalogueEntries(zip);
        assertEquals(2, metadata.size());
        S100DatasetDiscoveryMetadata cancellation = metadata.stream()
                .filter(m -> m.getPurpose() == S100Purpose.CANCELLATION)
                .findFirst().orElseThrow(() -> new AssertionError("no cancellation entry in the catalogue"));
        assertEquals(CATALOGUE_FILE_URI_PREFIX + finalVersion.getFileName(), cancellation.getFileName());
        assertEquals(cancelledDataset.getMrn(), cancellation.getDatasetID());
        // Clause 17-4.4.1 reproduces the original entry "with the exception of the issueDate",
        // which is the date of the cancellation itself
        assertEquals(cancelledDataset.getCancelled().toInstant().atZone(ZoneOffset.UTC).toLocalDate(),
                cancellation.getIssueDate());
        assertArrayEquals(finalVersion.getSignature(), signatureBytes(cancellation),
                "the cancellation reuses the final version's (persisted) signature");

        // The exchange set FOR the cancelled dataset itself ships it as a file and does not
        // simultaneously announce it fileless
        List<S100DatasetDiscoveryMetadata> ownSet = catalogueEntries(
                exchangeSetService.buildExchangeSet(datasetService.findByMessageUid(cancelledMsg.getUid())));
        assertEquals(1, ownSet.size(), "a shipped dataset is never also announced as its own cancellation");
        assertEquals(S100Purpose.NEW_DATASET, ownSet.get(0).getPurpose());
    }

    @Test
    @Transactional
    void cancellationsAgeOutOfThe365DayWindow() throws Exception {
        Message cancelledMsg = publishNw("secom-aged-" + uniq(), 55.5, 11.1);
        Message activeMsg = publishNw("secom-active2-" + uniq(), 56.5, 12.1);
        produceDataset(cancelledMsg);
        S124Dataset activeDataset = produceDataset(activeMsg);

        messageService.updateStatus(cancelledMsg.getUid(), Status.CANCELLED);
        datasetService.onMessageStatusChanged(cancelledMsg.getUid());

        S124Dataset cancelledDataset = datasetService.findByMessageUid(cancelledMsg.getUid());
        assertEquals(2, catalogueEntries(exchangeSetService.buildExchangeSet(activeDataset)).size(),
                "a fresh cancellation is announced");

        // Age the cancellation beyond the window
        cancelledDataset.setCancelled(Date.from(Instant.now().minus(Duration.ofDays(366))));
        em.flush();

        List<S100DatasetDiscoveryMetadata> metadata =
                catalogueEntries(exchangeSetService.buildExchangeSet(activeDataset));
        assertEquals(1, metadata.size(), "cancellations older than 365 days are no longer announced");
        assertEquals(S100Purpose.NEW_DATASET, metadata.get(0).getPurpose());
    }

    @Test
    @Transactional
    void theMintedCancellationWarningsOwnExchangeSetAnnouncesTheCancelledDataset() throws Exception {
        // The full S-124 §9.3 method-2 loop: the editorial cancel mints a born-published
        // cancellation warning; its own dataset's exchange set must carry the fileless
        // cancellation entry of the dataset it cancels (R010/R011)
        Message published = publishNw("secom-mint-fileless-" + uniq(), 55.5, 11.1);
        S124Dataset sourceDataset = produceDataset(published);

        messageService.cancelMessage(published.getUid());

        Message minted = em.createQuery("select m from Message m join m.references r "
                        + "where m.kind = :kind and r.messageId = :uid", Message.class)
                .setParameter("kind", MessageKind.CANCELLATION)
                .setParameter("uid", published.getUid())
                .getResultStream().findFirst().orElse(null);
        assertNotNull(minted, "cancelling an auto-numbered NW series must mint a cancellation warning");

        // Drive the pipeline for both messages, as the JMS listener would
        datasetService.onMessageStatusChanged(published.getUid());
        datasetService.onMessageStatusChanged(minted.getUid());

        S124Dataset mintedDataset = datasetService.findByMessageUid(minted.getUid());
        assertNotNull(mintedDataset, "the cancellation warning gets its own ACTIVE dataset");

        List<S100DatasetDiscoveryMetadata> metadata =
                catalogueEntries(exchangeSetService.buildExchangeSet(mintedDataset));
        assertEquals(2, metadata.size(),
                "the cancellation warning's exchange set carries its own dataset plus the fileless cancellation");
        S100DatasetDiscoveryMetadata cancellation = metadata.stream()
                .filter(m -> m.getPurpose() == S100Purpose.CANCELLATION)
                .findFirst().orElseThrow(() -> new AssertionError("no cancellation entry in the catalogue"));
        assertEquals(sourceDataset.getMrn(), cancellation.getDatasetID());

        S124Dataset cancelledSource = datasetService.findByMessageUid(published.getUid());
        assertArrayEquals(cancelledSource.getCurrentVersion().getSignature(), signatureBytes(cancellation));
    }

    /***************************************/
    /** Helpers                           **/
    /***************************************/

    private static List<S100DatasetDiscoveryMetadata> catalogueEntries(byte[] zip) throws Exception {
        String catalogXml = new String(unzip(zip).get("S100_ROOT/CATALOG.XML"), StandardCharsets.UTF_8);
        return S100ExchangeSetUtils.unmarshallS100ExchangeSetCatalogue(catalogXml)
                .getDatasetDiscoveryMetadata().getS100DatasetDiscoveryMetadatas();
    }

    private static S100DatasetDiscoveryMetadata soleEntry(byte[] zip) {
        try {
            List<S100DatasetDiscoveryMetadata> metadata = catalogueEntries(zip);
            assertEquals(1, metadata.size());
            return metadata.get(0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** The per-dataset signature bytes of a catalogue discovery-metadata entry */
    private static byte[] signatureBytes(S100DatasetDiscoveryMetadata entry) {
        assertEquals(1, entry.getDigitalSignatureValues().size());
        return entry.getDigitalSignatureValues().get(0).getS100SEDigitalSignature().getValue().getValue();
    }
}
