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
import org.grad.secomv2.core.models.UploadObject;
import org.grad.secomv2.core.models.enums.AckTypeEnum;
import org.grad.secomv2.core.models.enums.NackTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.secom.SecomITTest;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.SecomOutboxEntry;
import org.niord.secom.model.SecomSubscription;
import org.niord.test.Tx;
import org.niord.test.http.HttpStub;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ITs for the outbox dispatcher: MSR endpoint resolution, delivery of signed uploads and
 * subscription notifications to the resolved peer (an {@link HttpStub} standing in for both
 * the service registry and the remote SECOM node), and the retry/backoff/terminal-FAILED
 * bookkeeping when the peer misbehaves. Also the checks on what the registry returns (only the
 * peer's own instance; https and public addresses unless {@code secomAllowInsecureEndpoints}),
 * the overall deadline on a delivery, and that an ended entry stays ended: one transaction
 * alerts at most once whatever the peer sends and in whatever order, and an admin's
 * cancellation stands. And the claims: two runs at once send each due entry once, a run passes
 * over an entry another run is claiming without waiting for it, and an entry whose run died
 * after the claim is due again when the claim's lease runs out. And the admin's retry and cancel:
 * each takes the row lock and applies only to the statuses it is for, and a retry gives a fresh
 * schedule.
 */
class SecomOutboxDispatchITTest extends SecomITTest {

    static final String PEER_MRN = "urn:mrn:mcp:service:mcc:test:instance:peer-node";

    @Autowired
    SecomOutboxDispatcher dispatcher;

    @Autowired
    SecomOutboundClient outboundClient;

    HttpStub stub;
    String originalRegistryUrl;
    boolean originalAllowInsecureEndpoints;

    @BeforeEach
    void startStub() throws Exception {
        stub = HttpStub.start();
        originalRegistryUrl = outboundClient.serviceRegistryUrl;
        outboundClient.serviceRegistryUrl = stub.baseUrl();
        // The stub peer is plain http on 127.0.0.1, which the default (production) posture
        // refuses - these tests run with the test-environment flag, except where a test turns
        // it off to prove the refusal
        originalAllowInsecureEndpoints = outboundClient.allowInsecureEndpoints;
        outboundClient.allowInsecureEndpoints = true;
        outboundClient.evictEndpoint(PEER_MRN);
        // The MSR search resolves the peer MRN to... the same stub
        respondRegistryAndPeer(200);
    }

    @AfterEach
    void stopStub() {
        outboundClient.serviceRegistryUrl = originalRegistryUrl;
        outboundClient.allowInsecureEndpoints = originalAllowInsecureEndpoints;
        outboundClient.evictEndpoint(PEER_MRN);
        stub.close();
    }

    /** Routes stub responses: MSR search -> peer endpoint; peer endpoints -> the given status */
    private void respondRegistryAndPeer(int peerStatus) {
        respondRegistryAndPeer(PEER_MRN, stub.baseUrl(), peerStatus);
    }

    /** Routes stub responses: MSR search -> one instance with the given id and endpoint; peer endpoints -> the given status */
    private void respondRegistryAndPeer(String instanceId, String endpointUri, int peerStatus) {
        respondRegistryAndPeer(instanceId, endpointUri, peerStatus, Duration.ZERO);
    }

    /** As {@link #respondRegistryAndPeer(String, String, int)}, the peer taking the given time over each answer */
    private void respondRegistryAndPeer(String instanceId, String endpointUri, int peerStatus, Duration peerDelay) {
        stub.respond(request -> {
            if (request.path().endsWith("/v2/searchService")) {
                return HttpStub.StubResponse.json("""
                        { "envelope": { "serviceInstance": [
                            { "instanceId": "%s", "endpointUri": "%s" }
                        ]}}""".formatted(instanceId, endpointUri));
            }
            try {
                Thread.sleep(peerDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return HttpStub.StubResponse.of(peerStatus, "application/json", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
    }

    /** Detached snapshot of a committed upload entry */
    record CommittedUpload(Integer entryId, UUID transactionId, UUID subscriptionUuid, String gml) {
    }

    /** Commits a dataset + subscription for the peer node and enqueues the upload */
    private CommittedUpload committedUploadEntry() {
        return committedUploadEntry("S100_DataSet");
    }

    /** Commits a dataset + subscription with the given container type and enqueues the upload */
    private CommittedUpload committedUploadEntry(String containerType) {
        return committedUploadEntries(containerType, 1).getFirst();
    }

    /** Commits a dataset + subscription with the given container type and enqueues the given number of uploads of it */
    private List<CommittedUpload> committedUploadEntries(String containerType, int count) {
        return Tx.requiringNew().call(() -> {
            Message published = publishNw("secom-dispatch-" + uniq(), 55.5, 11.1);
            datasetService.onMessageStatusChanged(published.getUid());
            S124Dataset dataset = datasetService.findByMessageUid(published.getUid());

            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            template.setContainerType(containerType);
            SecomSubscription subscription = subscriptionService.subscribe(PEER_MRN, template);

            // subscribe() enqueued a CREATED notification; drop it to observe the uploads alone
            // (only a PENDING entry can be cancelled; the ended ones are no longer due anyway)
            outboxService.search(null, 10).stream()
                    .filter(o -> o.getStatus() == SecomOutboxEntry.Status.PENDING)
                    .forEach(o -> outboxService.cancel(o.getId()));
            List<CommittedUpload> uploads = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                SecomOutboxEntry entry = outboxService.enqueueUpload(subscription, dataset);
                uploads.add(new CommittedUpload(entry.getId(), entry.getTransactionId(), subscription.getUuid(),
                        dataset.getCurrentVersion().getGml()));
            }
            return uploads;
        });
    }

    /** {@return the transaction identifiers of the uploads the peer received, in the order they arrived} */
    private List<UUID> uploadedTransactionIds() {
        return stub.requests().stream()
                .filter(request -> request.path().equals("/v2/object"))
                .map(request -> {
                    try {
                        return SECOM_MAPPER.readValue(request.bodyString(), UploadObject.class)
                                .getEnvelope().getTransactionIdentifier();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }

    private SecomOutboxEntry reload(UUID transactionId) {
        return Tx.requiringNew().call(() -> outboxService.search(null, 20).stream()
                .filter(o -> o.getTransactionId().equals(transactionId))
                .findFirst().orElseThrow());
    }

    /**
     * Makes the entry due now, as a backoff that has run out would, and changes nothing else: an
     * admin retry would also give the entry a fresh schedule (no attempts counted)
     */
    private void makeDueNow(Integer entryId) {
        Tx.requiringNew().run(() -> em.createQuery("update SecomOutboxEntry o set o.nextAttemptAt = :now where o.id = :id")
                .setParameter("now", new Date())
                .setParameter("id", entryId)
                .executeUpdate());
    }

    @Test
    void dispatchDeliversTheSignedUploadToTheResolvedPeer() throws Exception {
        // A legacy bare-dataset subscription - the wire payload is the exact stored GML
        CommittedUpload entry = committedUploadEntry();
        String gml = entry.gml();

        int dispatched = dispatcher.dispatchAllDue();
        assertEquals(1, dispatched);

        // MSR search first, then the upload POST to the resolved endpoint
        assertNotNull(stub.firstRequestForPath("/v2/searchService"), "the peer MRN is resolved via the MSR");
        HttpStub.RecordedRequest upload = stub.firstRequestForPath("/v2/object");
        assertNotNull(upload, "the upload must be POSTed to the peer's /v2/object");

        UploadObject uploadObject = SECOM_MAPPER.readValue(upload.bodyString(), UploadObject.class);
        assertEquals(entry.transactionId(), uploadObject.getEnvelope().getTransactionIdentifier());
        assertEquals(entry.subscriptionUuid(), uploadObject.getEnvelope().getSubscriptionIdentifier());
        assertEquals(org.grad.secomv2.core.models.enums.ContainerTypeEnum.S100_DataSet,
                uploadObject.getEnvelope().getContainerType(),
                "the envelope echoes the container the payload was snapshotted as");
        assertNotNull(uploadObject.getEnvelopeSignature(), "the upload envelope must be signed");
        assertNotNull(uploadObject.getEnvelope().getCallbackEndpoint(),
                "an upload requesting an ack must carry the callback endpoint (design 6.1.4)");
        assertTrue(uploadObject.getEnvelope().getCallbackEndpoint().toString().endsWith("/api/secom"),
                "the callback endpoint points at this node's SECOM base path");
        assertNotNull(uploadObject.getEnvelope().getExchangeMetadata().getDigitalSignatureValue().getDigitalSignature(),
                "the payload must be signed");

        // encodeData() base64-encodes the payload inside the (already base64) JSON byte[] field
        String encodedPayload = new String(uploadObject.getEnvelope().getData(), StandardCharsets.UTF_8);
        assertEquals(gml, new String(Base64.getDecoder().decode(encodedPayload), StandardCharsets.UTF_8),
                "the pushed payload is the exact stored GML");

        assertEquals(SecomOutboxEntry.Status.SENT, reload(entry.transactionId()).getStatus());
    }

    @Test
    void subscriptionsWithoutAContainerTypeReceiveExchangeSets() throws Exception {
        // The design-mandated default: uploads carry S-100 exchange sets (containerType 1)
        CommittedUpload entry = committedUploadEntry(null);

        assertEquals(1, dispatcher.dispatchAllDue());
        HttpStub.RecordedRequest upload = stub.firstRequestForPath("/v2/object");
        assertNotNull(upload, "the upload must be POSTed to the peer's /v2/object");

        UploadObject uploadObject = SECOM_MAPPER.readValue(upload.bodyString(), UploadObject.class);
        assertEquals(org.grad.secomv2.core.models.enums.ContainerTypeEnum.S100_ExchangeSet,
                uploadObject.getEnvelope().getContainerType());

        byte[] payload = Base64.getDecoder().decode(new String(uploadObject.getEnvelope().getData(), StandardCharsets.UTF_8));
        assertTrue(payload.length > 2 && payload[0] == 'P' && payload[1] == 'K',
                "the pushed payload is a Part 17 exchange-set ZIP");

        assertEquals(SecomOutboxEntry.Status.SENT, reload(entry.transactionId()).getStatus());
    }

    @Test
    void failedDeliveriesBackOffAndEventuallyFail() {
        CommittedUpload entry = committedUploadEntry();
        respondRegistryAndPeer(500);

        // The service bean is a transactional proxy - the field lives on the target
        SecomOutboxService target = org.springframework.test.util.AopTestUtils.getTargetObject(outboxService);
        Integer originalMaxAttempts = target.maxAttempts;
        try {
            target.maxAttempts = 2;

            assertEquals(1, dispatcher.dispatchAllDue());
            SecomOutboxEntry afterFirst = reload(entry.transactionId());
            assertEquals(SecomOutboxEntry.Status.PENDING, afterFirst.getStatus());
            assertEquals(1, afterFirst.getAttempts());
            assertNotNull(afterFirst.getLastError());
            assertTrue(afterFirst.getNextAttemptAt().after(new Date()), "the retry is scheduled with backoff");

            // Not due yet -> the dispatcher leaves it alone
            assertEquals(0, dispatcher.dispatchAllDue());

            // Its backoff runs out -> second failure hits maxAttempts -> FAILED
            makeDueNow(entry.entryId());
            assertEquals(1, dispatcher.dispatchAllDue());
            SecomOutboxEntry failed = reload(entry.transactionId());
            assertEquals(SecomOutboxEntry.Status.FAILED, failed.getStatus());
            assertEquals(2, failed.getAttempts());

            // FAILED entries are terminal until an admin retries them
            assertEquals(0, dispatcher.dispatchAllDue());
        } finally {
            target.maxAttempts = originalMaxAttempts;
        }
    }

    @Test
    void anAdminRetryGivesAFailedEntryAFreshSchedule() {
        CommittedUpload entry = committedUploadEntry();
        respondRegistryAndPeer(500);

        SecomOutboxService target = org.springframework.test.util.AopTestUtils.getTargetObject(outboxService);
        Integer originalMaxAttempts = target.maxAttempts;
        try {
            target.maxAttempts = 2;
            assertEquals(1, dispatcher.dispatchAllDue());
            makeDueNow(entry.entryId());
            assertEquals(1, dispatcher.dispatchAllDue());
            assertEquals(SecomOutboxEntry.Status.FAILED, reload(entry.transactionId()).getStatus(), "it ran out of attempts");

            Tx.requiringNew().run(() -> outboxService.retryNow(entry.entryId()));
            SecomOutboxEntry retried = reload(entry.transactionId());
            assertEquals(SecomOutboxEntry.Status.PENDING, retried.getStatus());
            assertEquals(0, retried.getAttempts(), "a retry starts the attempts again");
            assertNull(retried.getLastError(), "a retry clears the last error");
            assertTrue(!retried.getNextAttemptAt().after(new Date()), "a retry makes the entry due at once");

            // The peer is still down: the first attempt after the retry is the first of a new
            // schedule, with its backoff, not the last of the old one (FAILED at once, a second alert)
            assertEquals(1, dispatcher.dispatchAllDue());
            SecomOutboxEntry afterRetry = reload(entry.transactionId());
            assertEquals(SecomOutboxEntry.Status.PENDING, afterRetry.getStatus());
            assertEquals(1, afterRetry.getAttempts());
            assertTrue(afterRetry.getNextAttemptAt().after(new Date()), "the next attempt waits for its backoff");
        } finally {
            target.maxAttempts = originalMaxAttempts;
        }
    }

    @Test
    void anAdminRetryOfANackedEntryClearsTheNack() {
        CommittedUpload entry = committedUploadEntry();
        Tx.requiringNew().run(() -> outboxService.markSent(entry.entryId()));
        Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                PEER_MRN, entry.transactionId(), AckTypeEnum.ERROR, NackTypeEnum.XML_SCHEMA_VALIDATION_ERROR));
        SecomOutboxEntry nacked = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.FAILED, nacked.getStatus());
        assertEquals("XML_SCHEMA_VALIDATION_ERROR", nacked.getNackType());

        Tx.requiringNew().run(() -> outboxService.retryNow(entry.entryId()));

        SecomOutboxEntry retried = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.PENDING, retried.getStatus());
        assertEquals(0, retried.getAttempts());
        assertNull(retried.getLastError(), "the NACK's error is cleared");
        assertNull(retried.getNackType(), "the NACK's type is cleared, so a later success does not carry it");

        assertEquals(1, dispatcher.dispatchAllDue());
        SecomOutboxEntry sent = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.SENT, sent.getStatus());
        assertNull(sent.getNackType());
    }

    @Test
    void retryAndCancelRefuseAStatusTheyDoNotApplyTo() {
        // SENT: neither a retry (it would be sent again under its transaction id) nor a cancel
        CommittedUpload sent = committedUploadEntry();
        Tx.requiringNew().run(() -> outboxService.markSent(sent.entryId()));
        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> outboxService.retryNow(sent.entryId())));
        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> outboxService.cancel(sent.entryId())));
        SecomOutboxEntry sentAfter = reload(sent.transactionId());
        assertEquals(SecomOutboxEntry.Status.SENT, sentAfter.getStatus());
        assertEquals(1, sentAfter.getAttempts());

        // ACKED: no retry; the documented way to send a dataset again is to republish it
        Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                PEER_MRN, sent.transactionId(), AckTypeEnum.DELIVERED_ACK, null));
        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> outboxService.retryNow(sent.entryId())));
        assertEquals(SecomOutboxEntry.Status.ACKED, reload(sent.transactionId()).getStatus());

        // FAILED: retried, never cancelled
        CommittedUpload failed = committedUploadEntry();
        Tx.requiringNew().run(() -> outboxService.markSent(failed.entryId()));
        Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                PEER_MRN, failed.transactionId(), AckTypeEnum.ERROR, NackTypeEnum.XML_SCHEMA_VALIDATION_ERROR));
        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> outboxService.cancel(failed.entryId())));
        assertEquals(SecomOutboxEntry.Status.FAILED, reload(failed.transactionId()).getStatus());

        // CANCELLED: stays cancelled
        CommittedUpload cancelled = committedUploadEntry();
        Tx.requiringNew().run(() -> outboxService.cancel(cancelled.entryId()));
        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> outboxService.retryNow(cancelled.entryId())));
        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> outboxService.cancel(cancelled.entryId())));
        assertEquals(SecomOutboxEntry.Status.CANCELLED, reload(cancelled.transactionId()).getStatus());

        // An unknown id stays an argument error (400 over REST)
        assertThrows(IllegalArgumentException.class, () -> Tx.requiringNew().run(() -> outboxService.retryNow(-1)));
        assertThrows(IllegalArgumentException.class, () -> Tx.requiringNew().run(() -> outboxService.cancel(-1)));
    }

    @Test
    void aCancelWaitingOnTheDispatchBookkeepingSeesTheSentEntryAndIsRefused() throws Exception {
        CommittedUpload entry = committedUploadEntry();

        // The dispatch bookkeeping holds the row lock and has marked the entry SENT, not yet committed
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> bookkeeping = pool.submit(() -> Tx.requiringNew().run(() -> {
                SecomOutboxEntry held = em.find(SecomOutboxEntry.class, entry.entryId(), LockModeType.PESSIMISTIC_WRITE);
                held.setStatus(SecomOutboxEntry.Status.SENT);
                held.setSentAt(new Date());
                held.setAttempts(held.getAttempts() + 1);
                em.flush();
                locked.countDown();
                release.await(30, TimeUnit.SECONDS);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "the bookkeeping holds the row lock");

            // The admin's cancel starts while the row is locked. Read without the lock it would
            // see PENDING, and its write would turn the sent entry CANCELLED once the lock goes
            Future<?> cancel = pool.submit(() -> Tx.requiringNew().run(() -> outboxService.cancel(entry.entryId())));
            Thread.sleep(500);
            release.countDown();
            bookkeeping.get(10, TimeUnit.SECONDS);

            ExecutionException refused = assertThrows(ExecutionException.class, () -> cancel.get(10, TimeUnit.SECONDS),
                    "the cancel waits for the lock, sees the entry SENT and is refused");
            assertInstanceOf(IllegalStateException.class, refused.getCause());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the transactions must end");
        }

        assertEquals(SecomOutboxEntry.Status.SENT, reload(entry.transactionId()).getStatus(), "the sent entry stays SENT");
    }

    @Test
    void terminalFailuresAlertTheConfiguredRecipients() {
        CommittedUpload entry = committedUploadEntry();
        respondRegistryAndPeer(500);

        SecomOutboxService target = org.springframework.test.util.AopTestUtils.getTargetObject(outboxService);
        Integer originalMaxAttempts = target.maxAttempts;
        String originalAlertEmails = target.alertEmails;
        try {
            target.maxAttempts = 1;
            target.alertEmails = "ops@example.com, watch@example.com";

            assertEquals(1, dispatcher.dispatchAllDue());
            assertEquals(SecomOutboxEntry.Status.FAILED, reload(entry.transactionId()).getStatus());

            Tx.requiringNew().run(() -> {
                // Filtered by the peer MRN: ids come from a pooled sequence and each cached test
                // context holds its own allocation block, so a bare "order by id desc" may surface
                // a stale mail queued by another SECOM IT class (see SecomCertificateAlertITTest)
                org.niord.core.mail.ScheduledMail alert = em.createQuery(
                                "select m from ScheduledMail m where m.subject like :pattern order by m.id desc",
                                org.niord.core.mail.ScheduledMail.class)
                        .setParameter("pattern", "%" + PEER_MRN + "%")
                        .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
                assertNotNull(alert, "a terminal failure must queue an alert mail (S-124R008)");
                assertTrue(alert.getSubject().contains(PEER_MRN), "the subject names the peer: " + alert.getSubject());
                assertTrue(alert.getSubject().contains("FAILED"));
                assertEquals(2, alert.getRecipients().size(), "one recipient per configured address");
                assertEquals("ops@example.com", alert.getRecipients().get(0).getAddress());
                assertEquals("watch@example.com", alert.getRecipients().get(1).getAddress());
            });
        } finally {
            target.maxAttempts = originalMaxAttempts;
            target.alertEmails = originalAlertEmails;
        }
    }

    @Test
    void terminalFailuresWithoutConfiguredRecipientsQueueNoMail() {
        CommittedUpload entry = committedUploadEntry();
        respondRegistryAndPeer(500);

        SecomOutboxService target = org.springframework.test.util.AopTestUtils.getTargetObject(outboxService);
        Integer originalMaxAttempts = target.maxAttempts;
        try {
            target.maxAttempts = 1;
            long mailsBefore = committedMailCount();

            assertEquals(1, dispatcher.dispatchAllDue());
            assertEquals(SecomOutboxEntry.Status.FAILED, reload(entry.transactionId()).getStatus());

            assertEquals(mailsBefore, committedMailCount(),
                    "with the (default) empty secomAlertEmails setting no alert mail is queued");
        } finally {
            target.maxAttempts = originalMaxAttempts;
        }
    }

    @Test
    void anAcknowledgementRacingTheDispatchBookkeepingIsNotDowngraded() {
        // The remote node acknowledges on its own connection and can beat the dispatch
        // bookkeeping (its ack arrives between our upload POST returning and markSent)
        CommittedUpload entry = committedUploadEntry();
        Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                PEER_MRN, entry.transactionId(), AckTypeEnum.DELIVERED_ACK, null));

        Tx.requiringNew().run(() -> outboxService.markSent(entry.entryId()));

        SecomOutboxEntry after = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.ACKED, after.getStatus(), "a late markSent must not downgrade ACKED");
        assertNotNull(after.getAckedAt());
        assertNotNull(after.getSentAt(), "the dispatch itself is still recorded");
        assertEquals(1, after.getAttempts());
    }

    @Test
    void aNackRacingTheDispatchBookkeepingKeepsTheFailureRecorded() {
        CommittedUpload entry = committedUploadEntry();
        Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                PEER_MRN, entry.transactionId(), AckTypeEnum.ERROR, NackTypeEnum.XML_SCHEMA_VALIDATION_ERROR));

        Tx.requiringNew().run(() -> outboxService.markSent(entry.entryId()));

        SecomOutboxEntry after = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.FAILED, after.getStatus(), "a late markSent must not resurrect a NACKed entry");
        assertNotNull(after.getLastError(), "the NACK error is not wiped by the late bookkeeping");
    }

    @Test
    void outOfOrderAcknowledgementsKeepTheOpenedState() {
        CommittedUpload entry = committedUploadEntry();
        Tx.requiringNew().run(() -> outboxService.markSent(entry.entryId()));

        Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                PEER_MRN, entry.transactionId(), AckTypeEnum.OPENED_ACK, null));
        Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                PEER_MRN, entry.transactionId(), AckTypeEnum.DELIVERED_ACK, null));

        SecomOutboxEntry after = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.OPENED, after.getStatus(), "a late delivered-ack must not downgrade OPENED");
        assertNotNull(after.getOpenedAt());
        assertNotNull(after.getAckedAt(), "the delivered-ack timestamp is still recorded");
    }

    @Test
    void subscriptionNotificationsAreDeliveredSigned() throws Exception {
        Tx.requiringNew().run(() -> {
            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            subscriptionService.subscribe(PEER_MRN, template);
        });

        assertEquals(1, dispatcher.dispatchAllDue());

        HttpStub.RecordedRequest notification = stub.firstRequestForPath("/v2/subscription/notification");
        assertNotNull(notification, "the SUBSCRIPTION_CREATED notification is POSTed to the peer");
        var notificationObject = SECOM_MAPPER.readValue(notification.bodyString(),
                org.grad.secomv2.core.models.SubscriptionNotificationObject.class);
        assertEquals("SUBSCRIPTION_CREATED", notificationObject.getEnvelope().getEventEnum().name());
        assertNotNull(notificationObject.getEnvelopeSignature(), "the notification envelope must be signed");
    }

    @Test
    void unresolvableNodesCountAsFailedAttempts() {
        CommittedUpload entry = committedUploadEntry();
        // The registry answers with no instances
        stub.respond(request -> HttpStub.StubResponse.json("{ \"envelope\": { \"serviceInstance\": [] } }"));

        assertEquals(1, dispatcher.dispatchAllDue());

        SecomOutboxEntry after = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.PENDING, after.getStatus());
        assertEquals(1, after.getAttempts());
        assertTrue(after.getLastError().contains("not resolvable"), "the resolution failure is recorded");
    }

    /***************************************/
    /** Claims                            **/
    /***************************************/

    @Test
    void twoRunsAtOnceSendEachDueEntryOnce() throws Exception {
        List<CommittedUpload> entries = committedUploadEntries("S100_DataSet", 4);
        // A peer taking a moment over each answer: without a claim, both runs read all four
        // entries as due before the first of them is marked sent, and send each one twice
        respondRegistryAndPeer(PEER_MRN, stub.baseUrl(), 200, Duration.ofMillis(200));

        int runs = 2;
        CyclicBarrier startLine = new CyclicBarrier(runs);
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        int attempted = 0;
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < runs; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS); // release both runs together
                    return dispatcher.dispatchAllDue();
                }));
            }
            for (Future<Integer> future : futures) {
                attempted += future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the dispatch runs must end");
        }

        assertEquals(entries.stream().map(CommittedUpload::transactionId).sorted().toList(),
                uploadedTransactionIds().stream().sorted().toList(),
                "each due entry reaches the peer exactly once");
        assertEquals(entries.size(), attempted, "the two runs attempt each entry once between them");
        for (CommittedUpload entry : entries) {
            SecomOutboxEntry after = reload(entry.transactionId());
            assertEquals(SecomOutboxEntry.Status.SENT, after.getStatus());
            assertEquals(1, after.getAttempts(), "one attempt is recorded");
        }
    }

    @Test
    void aRunPassesOverAnEntryAnotherRunIsClaimingWithoutWaitingForIt() throws Exception {
        List<CommittedUpload> entries = committedUploadEntries("S100_DataSet", 2);
        CommittedUpload beingClaimed = entries.get(0);
        CommittedUpload free = entries.get(1);

        // Another run's claim transaction holds the first entry's row lock and has not committed
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService otherRun = Executors.newSingleThreadExecutor();
        try {
            Future<?> claim = otherRun.submit(() -> Tx.requiringNew().run(() -> {
                em.find(SecomOutboxEntry.class, beingClaimed.entryId(), LockModeType.PESSIMISTIC_WRITE);
                locked.countDown();
                release.await(30, TimeUnit.SECONDS);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "the other run holds the row lock");

            // FOR UPDATE without SKIP LOCKED would wait here for the other run to commit; a read
            // without a lock would send the entry the other run is about to send
            int dispatched = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> dispatcher.dispatchAllDue(),
                    "a run must pass over an entry another run is claiming, not wait for it");
            assertEquals(1, dispatched);
            assertEquals(List.of(free.transactionId()), uploadedTransactionIds(),
                    "only the entry no other run was claiming is sent");

            release.countDown();
            claim.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            otherRun.shutdownNow();
            assertTrue(otherRun.awaitTermination(10, TimeUnit.SECONDS), "the other run must end");
        }

        // The other run let go without claiming it, so the entry is untouched and still due
        SecomOutboxEntry passedOver = reload(beingClaimed.transactionId());
        assertEquals(SecomOutboxEntry.Status.PENDING, passedOver.getStatus());
        assertEquals(0, passedOver.getAttempts());
        assertEquals(1, dispatcher.dispatchAllDue());
        assertEquals(List.of(free.transactionId(), beingClaimed.transactionId()), uploadedTransactionIds());
    }

    @Test
    void aClaimedEntryIsNotDueToAnotherRunForLongerThanADeliveryCanTake() {
        CommittedUpload entry = committedUploadEntry();

        // One run claims the entry and is still delivering it when a second run starts
        long claimedAt = System.currentTimeMillis();
        SecomOutboxService.DispatchTask task = outboxService.claimNextDue(new Date()).orElseThrow();
        assertEquals(entry.entryId(), task.entryId());

        assertEquals(0, dispatcher.dispatchAllDue(), "an entry another run has claimed is not due");
        assertTrue(uploadedTransactionIds().isEmpty(), "the second run sends nothing");

        SecomOutboxEntry claimed = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.PENDING, claimed.getStatus(), "a claim is not an outcome");
        assertEquals(0, claimed.getAttempts(), "a claim is not an attempt");
        // The longest delivery: a registry search and the POST, each up to the client's deadline
        long longestDelivery = outboundClient.requestTimeout.multipliedBy(2).toMillis();
        assertTrue(claimed.getNextAttemptAt().getTime() - claimedAt > longestDelivery,
                "the lease outlasts the longest delivery, so no other run takes the entry while it is delivered: "
                        + claimed.getNextAttemptAt());
    }

    @Test
    void anEntryClaimedByARunThatNeverFinishedIsDueAgainAfterTheLease() throws Exception {
        CommittedUpload entry = committedUploadEntry();

        SecomOutboxService target = org.springframework.test.util.AopTestUtils.getTargetObject(outboxService);
        Duration originalLease = target.claimLease;
        try {
            target.claimLease = Duration.ofSeconds(2);

            // A run claims the entry and dies before it records an outcome (a pod killed mid-send)
            outboxService.claimNextDue(new Date()).orElseThrow();
            assertEquals(0, dispatcher.dispatchAllDue(), "the claim holds while its lease runs");

            // Nothing releases the claim: the entry falls due again when the lease runs out
            Date leaseEnd = reload(entry.transactionId()).getNextAttemptAt();
            Thread.sleep(Math.max(0, leaseEnd.getTime() - System.currentTimeMillis()) + 250);
            assertEquals(1, dispatcher.dispatchAllDue(), "the first run after the lease sends the entry");
        } finally {
            target.claimLease = originalLease;
        }

        assertEquals(List.of(entry.transactionId()), uploadedTransactionIds(), "the entry reached the peer once");
        SecomOutboxEntry after = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.SENT, after.getStatus());
        assertEquals(1, after.getAttempts());
    }

    /***************************************/
    /** Peer endpoint checks              **/
    /***************************************/

    @Test
    void aRegistryInstanceUnderAnotherMrnIsNotResolvable() {
        // The registry answers with a single instance - but not the peer's: its endpoint must
        // not receive the peer's deliveries (formerly the first hit was taken as a fallback)
        CommittedUpload entry = committedUploadEntry();
        respondRegistryAndPeer("urn:mrn:mcp:service:mcc:test:instance:someone-else", stub.baseUrl(), 200);

        assertEquals(1, dispatcher.dispatchAllDue());

        SecomOutboxEntry after = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.PENDING, after.getStatus());
        assertEquals(1, after.getAttempts());
        assertTrue(after.getLastError().contains("not resolvable"), "the resolution failure is recorded: " + after.getLastError());
        assertNull(stub.firstRequestForPath("/v2/object"), "nothing is delivered to another instance's endpoint");
    }

    @Test
    void theRegistryInstanceIdMatchesTheMrnCaseInsensitively() {
        CommittedUpload entry = committedUploadEntry();
        respondRegistryAndPeer(PEER_MRN.toUpperCase(java.util.Locale.ROOT), stub.baseUrl(), 200);

        assertEquals(1, dispatcher.dispatchAllDue());

        assertNotNull(stub.firstRequestForPath("/v2/object"), "an MRN spelled in another case is the same MRN");
        assertEquals(SecomOutboxEntry.Status.SENT, reload(entry.transactionId()).getStatus());
    }

    @Test
    void insecurePeerEndpointsAreRefusedWithoutTheTestEnvironmentFlag() {
        CommittedUpload entry = committedUploadEntry();
        outboundClient.allowInsecureEndpoints = false;

        // A plain-http endpoint is refused when it is resolved
        assertEquals(1, dispatcher.dispatchAllDue());
        SecomOutboxEntry afterHttp = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.PENDING, afterHttp.getStatus());
        assertEquals(1, afterHttp.getAttempts());
        assertTrue(afterHttp.getLastError().contains("Refused SECOM peer endpoint")
                && afterHttp.getLastError().contains("https"), "the refusal is recorded: " + afterHttp.getLastError());

        // An https endpoint on a loopback address is refused before anything is sent to it
        respondRegistryAndPeer(PEER_MRN, "https://127.0.0.1:" + stub.port(), 200);
        makeDueNow(entry.entryId());
        assertEquals(1, dispatcher.dispatchAllDue());
        SecomOutboxEntry afterLoopback = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.PENDING, afterLoopback.getStatus());
        assertEquals(2, afterLoopback.getAttempts());
        assertTrue(afterLoopback.getLastError().contains("non-public address"),
                "the refusal is recorded: " + afterLoopback.getLastError());

        assertNotNull(stub.firstRequestForPath("/v2/searchService"), "the registry itself was asked");
        assertNull(stub.firstRequestForPath("/v2/object"), "nothing was delivered to the refused endpoints");
    }

    /***************************************/
    /** Delivery deadline                 **/
    /***************************************/

    @Test
    void aPeerTricklingItsReplyIsCutOffAtTheDeadline() throws Exception {
        CommittedUpload entry = committedUploadEntry();
        Duration originalTimeout = outboundClient.requestTimeout;
        try (TricklingPeer peer = new TricklingPeer()) {
            respondRegistryAndPeer(PEER_MRN, peer.baseUrl(), 200);
            outboundClient.requestTimeout = Duration.ofSeconds(2);

            // Without an overall deadline the dispatcher would wait for the whole body - here
            // hours - and hold one of the four scheduler threads the @Scheduled jobs share
            int dispatched = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> dispatcher.dispatchAllDue(),
                    "a peer trickling its reply must not hold the dispatcher beyond the deadline");
            assertEquals(1, dispatched);
            assertEquals(1, peer.requestCount(), "the peer received the upload and answered with headers");
        } finally {
            outboundClient.requestTimeout = originalTimeout;
        }

        SecomOutboxEntry after = reload(entry.transactionId());
        assertEquals(SecomOutboxEntry.Status.PENDING, after.getStatus(), "a timeout is an ordinary failed attempt");
        assertEquals(1, after.getAttempts());
        assertTrue(after.getLastError().contains("HttpTimeoutException"), "the timeout is recorded: " + after.getLastError());
        assertTrue(after.getNextAttemptAt().after(new Date()), "the retry is scheduled with backoff");
    }

    /**
     * A raw-socket peer answering every request with its response headers at once and then
     * the body one byte at a time - a reply {@link HttpStub} cannot produce (it writes whole
     * bodies), and one that the request's header timeout does not bound.
     */
    static final class TricklingPeer implements AutoCloseable {

        private final ServerSocket server;
        private final AtomicInteger requests = new AtomicInteger();

        TricklingPeer() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(this::acceptLoop, "trickling-peer-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getLocalPort();
        }

        int requestCount() {
            return requests.get();
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread connection = new Thread(() -> trickle(socket), "trickling-peer-connection");
                    connection.setDaemon(true);
                    connection.start();
                } catch (IOException e) {
                    return;     // closed
                }
            }
        }

        private void trickle(Socket socket) {
            try (socket) {
                InputStream in = socket.getInputStream();
                int contentLength = contentLength(readHead(in));
                in.readNBytes(contentLength);
                requests.incrementAndGet();

                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        + "Content-Length: 100000\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                // One byte every 100 ms: the body would take hours. Ends when the client hangs up
                // (the write fails) or the peer is closed.
                while (!server.isClosed()) {
                    out.write(' ');
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (IOException | InterruptedException e) {
                // the client gave up on the exchange - expected
            }
        }

        /** Reads the request head, up to and including the blank line that ends it */
        private static String readHead(InputStream in) throws IOException {
            StringBuilder head = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                head.append((char) b);
                if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) {
                    break;
                }
            }
            return head.toString();
        }

        private static int contentLength(String head) {
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    return Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }
            return 0;
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    /***************************************/
    /** Acknowledgements of ended entries **/
    /***************************************/

    @Test
    void repeatedNacksOfOneTransactionQueueOneAlert() {
        CommittedUpload entry = committedUploadEntry();
        Tx.requiringNew().run(() -> outboxService.markSent(entry.entryId()));

        SecomOutboxService target = org.springframework.test.util.AopTestUtils.getTargetObject(outboxService);
        String originalAlertEmails = target.alertEmails;
        try {
            target.alertEmails = "ops@example.com";
            long alertsBefore = committedPeerAlertCount();

            // A peer repeating its NACK must not turn one failed transaction into a mail each time
            Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                    PEER_MRN, entry.transactionId(), AckTypeEnum.ERROR, NackTypeEnum.XML_SCHEMA_VALIDATION_ERROR));
            for (int i = 0; i < 3; i++) {
                Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                        PEER_MRN, entry.transactionId(), AckTypeEnum.ERROR, NackTypeEnum.UNKNOWN_DATA_TYPE_OR_VERSION));
            }

            assertEquals(alertsBefore + 1, committedPeerAlertCount(), "one failed transaction, one alert");
            SecomOutboxEntry after = reload(entry.transactionId());
            assertEquals(SecomOutboxEntry.Status.FAILED, after.getStatus());
            assertEquals("XML_SCHEMA_VALIDATION_ERROR", after.getNackType(), "the first NACK stays recorded");
        } finally {
            target.alertEmails = originalAlertEmails;
        }
    }

    @Test
    void aNackForACancelledEntryIsIgnored() {
        // An admin cancels the entry while its delivery is in flight; the delivery then succeeds
        // (only a PENDING entry can be cancelled) and the peer answers it
        CommittedUpload entry = committedUploadEntry();
        Tx.requiringNew().run(() -> outboxService.cancel(entry.entryId()));
        Tx.requiringNew().run(() -> outboxService.markSent(entry.entryId()));

        SecomOutboxService target = org.springframework.test.util.AopTestUtils.getTargetObject(outboxService);
        String originalAlertEmails = target.alertEmails;
        try {
            target.alertEmails = "ops@example.com";
            long alertsBefore = committedPeerAlertCount();

            Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                    PEER_MRN, entry.transactionId(), AckTypeEnum.ERROR, NackTypeEnum.XML_SCHEMA_VALIDATION_ERROR));

            assertEquals(alertsBefore, committedPeerAlertCount(), "an entry an admin cancelled raises no alert");
            SecomOutboxEntry after = reload(entry.transactionId());
            assertEquals(SecomOutboxEntry.Status.CANCELLED, after.getStatus());
            assertNull(after.getNackType());
        } finally {
            target.alertEmails = originalAlertEmails;
        }
    }

    @Test
    void acknowledgementsBetweenNacksDoNotReopenAFailedTransaction() {
        CommittedUpload entry = committedUploadEntry();
        Tx.requiringNew().run(() -> outboxService.markSent(entry.entryId()));

        SecomOutboxService target = org.springframework.test.util.AopTestUtils.getTargetObject(outboxService);
        String originalAlertEmails = target.alertEmails;
        try {
            target.alertEmails = "ops@example.com";
            long alertsBefore = committedPeerAlertCount();

            // A positive acknowledgement between two NACKs must not move the failed entry back
            // to ACKED/OPENED, where the next NACK would count as a new failure
            Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                    PEER_MRN, entry.transactionId(), AckTypeEnum.ERROR, NackTypeEnum.XML_SCHEMA_VALIDATION_ERROR));
            Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                    PEER_MRN, entry.transactionId(), AckTypeEnum.DELIVERED_ACK, null));
            Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                    PEER_MRN, entry.transactionId(), AckTypeEnum.ERROR, NackTypeEnum.UNKNOWN_DATA_TYPE_OR_VERSION));
            Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                    PEER_MRN, entry.transactionId(), AckTypeEnum.OPENED_ACK, null));
            Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                    PEER_MRN, entry.transactionId(), AckTypeEnum.ERROR, NackTypeEnum.UNKNOWN_DATA_TYPE_OR_VERSION));

            assertEquals(alertsBefore + 1, committedPeerAlertCount(), "one failed transaction, one alert");
            SecomOutboxEntry after = reload(entry.transactionId());
            assertEquals(SecomOutboxEntry.Status.FAILED, after.getStatus(), "an acknowledgement does not reopen a failed entry");
            assertEquals("XML_SCHEMA_VALIDATION_ERROR", after.getNackType(), "the first NACK stays recorded");
            assertNotNull(after.getAckedAt(), "the acknowledgement time is still recorded");
            assertNotNull(after.getOpenedAt(), "the opened time is still recorded");
        } finally {
            target.alertEmails = originalAlertEmails;
        }
    }

    @Test
    void acknowledgementsDoNotUndoACancellation() {
        // Cancelled while its delivery was in flight, which then succeeded (as above)
        CommittedUpload entry = committedUploadEntry();
        Tx.requiringNew().run(() -> outboxService.cancel(entry.entryId()));
        Tx.requiringNew().run(() -> outboxService.markSent(entry.entryId()));

        SecomOutboxService target = org.springframework.test.util.AopTestUtils.getTargetObject(outboxService);
        String originalAlertEmails = target.alertEmails;
        try {
            target.alertEmails = "ops@example.com";
            long alertsBefore = committedPeerAlertCount();

            Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                    PEER_MRN, entry.transactionId(), AckTypeEnum.DELIVERED_ACK, null));
            Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                    PEER_MRN, entry.transactionId(), AckTypeEnum.OPENED_ACK, null));
            Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                    PEER_MRN, entry.transactionId(), AckTypeEnum.ERROR, NackTypeEnum.XML_SCHEMA_VALIDATION_ERROR));

            assertEquals(alertsBefore, committedPeerAlertCount(), "an entry an admin cancelled raises no alert");
            SecomOutboxEntry after = reload(entry.transactionId());
            assertEquals(SecomOutboxEntry.Status.CANCELLED, after.getStatus(), "the admin's cancellation stands");
            assertNotNull(after.getAckedAt(), "the acknowledgement time is still recorded");
            assertNull(after.getNackType());
        } finally {
            target.alertEmails = originalAlertEmails;
        }
    }

    @Test
    void aFailedAttemptEndingAfterANackRaisesNoSecondAlert() {
        // The peer NACKs on its own connection while our final delivery attempt is still in
        // flight, and that attempt then fails too (an error reply or the deadline)
        CommittedUpload entry = committedUploadEntry();

        SecomOutboxService target = org.springframework.test.util.AopTestUtils.getTargetObject(outboxService);
        Integer originalMaxAttempts = target.maxAttempts;
        String originalAlertEmails = target.alertEmails;
        try {
            target.maxAttempts = 1;
            target.alertEmails = "ops@example.com";
            long alertsBefore = committedPeerAlertCount();

            Tx.requiringNew().run(() -> outboxService.applyAcknowledgement(
                    PEER_MRN, entry.transactionId(), AckTypeEnum.ERROR, NackTypeEnum.XML_SCHEMA_VALIDATION_ERROR));
            Tx.requiringNew().run(() -> outboxService.markFailedAttempt(entry.entryId(), "peer answered 500"));

            assertEquals(alertsBefore + 1, committedPeerAlertCount(), "one failed transaction, one alert");
            SecomOutboxEntry after = reload(entry.transactionId());
            assertEquals(SecomOutboxEntry.Status.FAILED, after.getStatus());
            assertEquals(1, after.getAttempts(), "the attempt itself is still counted");
            assertEquals("NACK: XML_SCHEMA_VALIDATION_ERROR", after.getLastError(), "the NACK stays the recorded error");
        } finally {
            target.maxAttempts = originalMaxAttempts;
            target.alertEmails = originalAlertEmails;
        }
    }

    @Test
    void aFailedAttemptEndingAfterACancellationKeepsTheCancellation() {
        CommittedUpload entry = committedUploadEntry();

        SecomOutboxService target = org.springframework.test.util.AopTestUtils.getTargetObject(outboxService);
        Integer originalMaxAttempts = target.maxAttempts;
        String originalAlertEmails = target.alertEmails;
        try {
            target.maxAttempts = 1;
            target.alertEmails = "ops@example.com";
            long alertsBefore = committedPeerAlertCount();

            // An admin cancels the entry while its final attempt is in flight
            Tx.requiringNew().run(() -> outboxService.cancel(entry.entryId()));
            Tx.requiringNew().run(() -> outboxService.markFailedAttempt(entry.entryId(), "peer answered 500"));

            assertEquals(alertsBefore, committedPeerAlertCount(), "a cancelled entry raises no alert");
            assertEquals(SecomOutboxEntry.Status.CANCELLED, reload(entry.transactionId()).getStatus(),
                    "the admin's cancellation stands");
        } finally {
            target.maxAttempts = originalMaxAttempts;
            target.alertEmails = originalAlertEmails;
        }
    }

    /** {@return the number of committed alert mails naming the peer} */
    private long committedPeerAlertCount() {
        return Tx.requiringNew().call(() -> em.createQuery(
                        "select count(m) from ScheduledMail m where m.subject like :pattern", Long.class)
                .setParameter("pattern", "%" + PEER_MRN + "%")
                .getSingleResult());
    }
}
