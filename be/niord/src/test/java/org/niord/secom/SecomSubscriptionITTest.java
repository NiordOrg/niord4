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

import io.restassured.http.ContentType;
import org.grad.secomv2.core.models.AcknowledgementObject;
import org.grad.secomv2.core.models.EnvelopeAckObject;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.SecomOutboxEntry;
import org.niord.secom.model.SecomSubscription;
import org.niord.secom.service.SecomSubscriptionService;
import org.niord.test.Tx;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level ITs for the SECOM v2 write plane: subscription lifecycle (create with honored
 * filters, dedupe, owner-checked removal, async notifications via the outbox) and
 * acknowledgements — which must carry a valid envelope signature (the SECOM signature
 * filter validates signed POST bodies) and may only touch the acknowledging node's own
 * transactions.
 */
class SecomSubscriptionITTest extends SecomITTest {

    /***************************************/
    /** Subscriptions                     **/
    /***************************************/

    @Test
    void subscribingPersistsTheRequestedFilters() {
        String subscriptionId = secomGiven()
                .contentType(ContentType.JSON)
                .body("""
                        { "envelope": {
                            "dataProductType": "S-124",
                            "productVersion": "2.0.0",
                            "geometry": "POLYGON((5 50, 15 50, 15 60, 5 60, 5 50))",
                            "subscriptionPeriodStart": "2026-01-01T00:00:00",
                            "subscriptionPeriodEnd": "2036-01-01T00:00:00"
                        }}""")
                .post("/api/secom/v2/subscription")
                .then()
                .statusCode(200)
                .body("subscriptionIdentifier", notNullValue())
                .extract().path("subscriptionIdentifier");

        SecomSubscription subscription = Tx.requiringNew().call(() -> {
            SecomSubscription s = subscriptionService.findByUuid(UUID.fromString(subscriptionId));
            assertEquals(CLIENT_MRN, s.getNode().getMrn());
            return s;
        });
        assertEquals("S124", subscription.getDataProductType());
        assertEquals("2.0.0", subscription.getProductVersion());
        assertEquals("POLYGON((5 50, 15 50, 15 60, 5 60, 5 50))", subscription.getWkt());
        assertNotNull(subscription.getGeometry(), "the WKT filter is parsed and persisted");
        assertNotNull(subscription.getPeriodStart());
        assertNotNull(subscription.getPeriodEnd());

        // The SUBSCRIPTION_CREATED notification is enqueued for async delivery, not sent inline
        List<SecomOutboxEntry> notifications = Tx.requiringNew().call(() ->
                outboxService.search(null, 10));
        assertEquals(1, notifications.size());
        assertEquals(SecomOutboxEntry.Operation.SUBSCRIPTION_NOTIFICATION, notifications.get(0).getOperation());
        assertEquals("SUBSCRIPTION_CREATED", notifications.get(0).getNotificationEvent());
        assertEquals(SecomOutboxEntry.Status.PENDING, notifications.get(0).getStatus());
    }

    @Test
    void identicalSubscriptionsAreDeduplicated() {
        String body = """
                { "envelope": { "dataProductType": "S-124" }}""";

        String first = secomGiven().contentType(ContentType.JSON).body(body)
                .post("/api/secom/v2/subscription")
                .then().statusCode(200).extract().path("subscriptionIdentifier");

        String second = secomGiven().contentType(ContentType.JSON).body(body)
                .post("/api/secom/v2/subscription")
                .then().statusCode(200).extract().path("subscriptionIdentifier");

        assertEquals(first, second, "byte-identical subscriptions return the existing identifier");

        // Every subscription request is confirmed with a SUBSCRIPTION_CREATED notification
        // (design §7.3.1) - the deduplicated second request too, so a consumer re-subscribing
        // after losing state still receives the confirmation callback it waits for
        List<SecomOutboxEntry> notifications = Tx.requiringNew().call(() -> outboxService.search(null, 10));
        assertEquals(2, notifications.size(), "both subscribe requests enqueue a confirmation");
        for (SecomOutboxEntry notification : notifications) {
            assertEquals(SecomOutboxEntry.Operation.SUBSCRIPTION_NOTIFICATION, notification.getOperation());
            assertEquals("SUBSCRIPTION_CREATED", notification.getNotificationEvent());
            assertEquals(first, notification.getSubscriptionUuid().toString());
        }
    }

    @Test
    void aNodeCannotHoldMoreSubscriptionsThanTheCap() {
        // The service bean is a transactional proxy - the setting field lives on the target, and
        // is package-private to the service package, hence the reflective access
        SecomSubscriptionService target = AopTestUtils.getTargetObject(subscriptionService);
        Object originalCap = ReflectionTestUtils.getField(target, "maxSubscriptionsPerNode");
        try {
            ReflectionTestUtils.setField(target, "maxSubscriptionsPerNode", 2);
            String first = subscribeWithin("POLYGON((5 50, 15 50, 15 60, 5 60, 5 50))", 200);
            subscribeWithin("POLYGON((6 50, 15 50, 15 60, 6 60, 6 50))", 200);

            // A third, different subscription is refused with a SECOM validation error
            secomGiven().contentType(ContentType.JSON)
                    .body(subscriptionBody("POLYGON((7 50, 15 50, 15 60, 7 60, 7 50))"))
                    .post("/api/secom/v2/subscription")
                    .then()
                    .statusCode(400);

            // ... while re-sending one the node already holds still confirms it
            assertEquals(first, subscribeWithin("POLYGON((5 50, 15 50, 15 60, 5 60, 5 50))", 200),
                    "an identical request is deduplicated before the cap applies");

            Tx.requiringNew().run(() -> assertEquals(2, em.createQuery(
                            "select count(s) from SecomSubscription s where s.node.mrn = :mrn", Long.class)
                    .setParameter("mrn", CLIENT_MRN)
                    .getSingleResult(), "the refused request stored nothing"));
        } finally {
            ReflectionTestUtils.setField(target, "maxSubscriptionsPerNode", originalCap);
        }
    }

    /** {@return the body of an S-124 subscription request for the given area} */
    private static String subscriptionBody(String wkt) {
        return """
                { "envelope": { "dataProductType": "S-124", "geometry": "%s" }}""".formatted(wkt);
    }

    /** Subscribes the test client to the given area, expecting the given status; returns the identifier */
    private String subscribeWithin(String wkt, int expectedStatus) {
        return secomGiven().contentType(ContentType.JSON)
                .body(subscriptionBody(wkt))
                .post("/api/secom/v2/subscription")
                .then().statusCode(expectedStatus)
                .extract().path("subscriptionIdentifier");
    }

    @Test
    void anUnspecifiedContainerTypeDeduplicatesAgainstTheExplicitDefault() {
        // Regression: an unspecified containerType is delivered as the design-default
        // exchange set, so it must dedupe against a subscription that spells the default
        // explicitly - previously the raw null-vs-"S100_ExchangeSet" comparison created two
        // subscriptions pushing identical payloads to the same node
        String implicit = secomGiven().contentType(ContentType.JSON)
                .body("""
                        { "envelope": { "dataProductType": "S-124" }}""")
                .post("/api/secom/v2/subscription")
                .then().statusCode(200).extract().path("subscriptionIdentifier");

        String explicit = secomGiven().contentType(ContentType.JSON)
                .body("""
                        { "envelope": { "dataProductType": "S-124", "containerType": 1 }}""")
                .post("/api/secom/v2/subscription")
                .then().statusCode(200).extract().path("subscriptionIdentifier");

        assertEquals(implicit, explicit,
                "the default container and the explicit S100_ExchangeSet are the same subscription");

        // The persisted row carries the effective container, not null
        Tx.requiringNew().run(() -> assertEquals("S100_ExchangeSet",
                subscriptionService.findByUuid(UUID.fromString(implicit)).getContainerType()));
    }

    @Test
    void invalidSubscriptionRequestsAreRejected() {
        // No envelope
        secomGiven().contentType(ContentType.JSON).body("{}")
                .post("/api/secom/v2/subscription")
                .then().statusCode(400);

        // Unparseable WKT
        secomGiven().contentType(ContentType.JSON)
                .body("""
                        { "envelope": { "geometry": "NOT-WKT" }}""")
                .post("/api/secom/v2/subscription")
                .then().statusCode(400);

        // UNLOCODE filtering is not supported
        secomGiven().contentType(ContentType.JSON)
                .body("""
                        { "envelope": { "unlocode": "DKCPH" }}""")
                .post("/api/secom/v2/subscription")
                .then().statusCode(400);

        // containerType NONE (wire value 2) is invalid
        secomGiven().contentType(ContentType.JSON)
                .body("""
                        { "envelope": { "containerType": 2 }}""")
                .post("/api/secom/v2/subscription")
                .then().statusCode(400);

        // An unsupported productVersion is rejected loudly rather than accepted-but-never-
        // pushed (the push matcher only serves 2.0.0; the spec's own §6.5.4 example says
        // "2.2.0" - docs/s124-spec-wg-feedback.md item 1)
        secomGiven().contentType(ContentType.JSON)
                .body("""
                        { "envelope": { "dataProductType": "S-124", "productVersion": "2.2.0" }}""")
                .post("/api/secom/v2/subscription")
                .then().statusCode(400);
    }

    @Test
    void supportedProductVersionsAreAcceptedAndNormalized() {
        // The served edition is accepted as a filter...
        String versioned = secomGiven().contentType(ContentType.JSON)
                .body("""
                        { "envelope": { "dataProductType": "S-124", "productVersion": "2.0.0" }}""")
                .post("/api/secom/v2/subscription")
                .then().statusCode(200).extract().path("subscriptionIdentifier");
        Tx.requiringNew().run(() -> assertEquals("2.0.0",
                subscriptionService.findByUuid(UUID.fromString(versioned)).getProductVersion()));

        // ... and a blank filter is normalized to "no filter" (a stored "" would never match)
        String blank = secomGiven().contentType(ContentType.JSON)
                .body("""
                        { "envelope": { "dataProductType": "S-124", "productVersion": " " }}""")
                .post("/api/secom/v2/subscription")
                .then().statusCode(200).extract().path("subscriptionIdentifier");
        Tx.requiringNew().run(() -> assertNull(
                subscriptionService.findByUuid(UUID.fromString(blank)).getProductVersion()));
    }

    @Test
    void subscriptionsMayChooseTheContainerType() {
        // The design-mandated exchange-set container (wire value 1)...
        String exchangeSet = secomGiven().contentType(ContentType.JSON)
                .body("""
                        { "envelope": { "containerType": 1 }}""")
                .post("/api/secom/v2/subscription")
                .then().statusCode(200).extract().path("subscriptionIdentifier");

        // ...and the bare-dataset container (wire value 0) for legacy peers
        String bareDataset = secomGiven().contentType(ContentType.JSON)
                .body("""
                        { "envelope": { "containerType": 0 }}""")
                .post("/api/secom/v2/subscription")
                .then().statusCode(200).extract().path("subscriptionIdentifier");

        Tx.requiringNew().run(() -> {
            assertEquals("S100_ExchangeSet",
                    subscriptionService.findByUuid(UUID.fromString(exchangeSet)).getContainerType());
            assertEquals("S100_DataSet",
                    subscriptionService.findByUuid(UUID.fromString(bareDataset)).getContainerType());
        });
    }

    @Test
    void removingASubscriptionIsOwnerChecked() {
        // Created by another node directly in the store
        UUID foreign = Tx.requiringNew().call(() -> {
            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            return subscriptionService.subscribe("urn:mrn:mcp:service:mcc:test:instance:someone-else", template).getUuid();
        });

        secomGiven()
                .queryParam("subscriptionIdentifier", foreign.toString())
                .delete("/api/secom/v2/subscription")
                .then()
                .statusCode(404);

        // Own subscription: removable, with a SUBSCRIPTION_REMOVED notification enqueued
        String own = secomGiven().contentType(ContentType.JSON)
                .body("""
                        { "envelope": { "dataProductType": "S-124" }}""")
                .post("/api/secom/v2/subscription")
                .then().statusCode(200).extract().path("subscriptionIdentifier");

        secomGiven()
                .queryParam("subscriptionIdentifier", own)
                .delete("/api/secom/v2/subscription")
                .then()
                .statusCode(200)
                .body("message", equalTo("Subscription " + own + " removed"));

        Tx.requiringNew().run(() -> {
            assertNull(subscriptionService.findByUuid(UUID.fromString(own)), "the subscription row is gone");
            long removedNotifications = outboxService.search(null, 10).stream()
                    .filter(o -> "SUBSCRIPTION_REMOVED".equals(o.getNotificationEvent()))
                    .count();
            assertEquals(1, removedNotifications);
        });

        // Removing it again -> not found
        secomGiven()
                .queryParam("subscriptionIdentifier", own)
                .delete("/api/secom/v2/subscription")
                .then()
                .statusCode(404);
    }

    /***************************************/
    /** Acknowledgements                  **/
    /***************************************/

    /** Commits an outbox entry in SENT state owned by the given MRN and returns its transaction id */
    private UUID committedSentUpload(String nodeMrn) {
        return Tx.requiringNew().call(() -> {
            Message published = publishNw("secom-ack-" + uniq(), 55.5, 11.1);
            datasetService.onMessageStatusChanged(published.getUid());
            S124Dataset dataset = datasetService.findByMessageUid(published.getUid());

            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            SecomSubscription subscription = subscriptionService.subscribe(nodeMrn, template);

            SecomOutboxEntry entry = outboxService.enqueueUpload(subscription, dataset);
            outboxService.markSent(entry.getId());
            return entry.getTransactionId();
        });
    }

    /** Builds a signed acknowledgement request body for the given transaction */
    private String signedAck(UUID transactionId) throws Exception {
        EnvelopeAckObject envelope = new EnvelopeAckObject();
        envelope.setCreatedAt(Instant.now());
        envelope.setTransactionIdentifier(transactionId);
        envelope.setAckType(org.grad.secomv2.core.models.enums.AckTypeEnum.DELIVERED_ACK);

        AcknowledgementObject ack = new AcknowledgementObject();
        ack.setEnvelope(envelope);

        ClientSigner signer = clientSigner();
        ack.signEnvelope(signer, signer);
        return SECOM_MAPPER.writeValueAsString(ack);
    }

    @Test
    void aSignedAcknowledgementMarksTheTransactionAcked() throws Exception {
        UUID transactionId = committedSentUpload(CLIENT_MRN);

        secomGiven()
                .contentType(ContentType.JSON)
                .body(signedAck(transactionId))
                .post("/api/secom/v2/acknowledgement")
                .then()
                .statusCode(200)
                .body("message", notNullValue());

        Tx.requiringNew().run(() -> {
            SecomOutboxEntry entry = outboxService.search(null, 10).stream()
                    .filter(o -> o.getTransactionId().equals(transactionId))
                    .findFirst().orElseThrow();
            assertEquals(SecomOutboxEntry.Status.ACKED, entry.getStatus());
            assertNotNull(entry.getAckedAt());
        });
    }

    @Test
    void acknowledgementsForForeignTransactionsAreRejected() throws Exception {
        // The transaction belongs to another node - the client must not be able to ack it.
        // NB: the SECOM v2 acknowledgement interface maps SecomNotFoundException to 400
        // (unlike Get, which maps it to 404).
        UUID transactionId = committedSentUpload("urn:mrn:mcp:service:mcc:test:instance:someone-else");

        secomGiven()
                .contentType(ContentType.JSON)
                .body(signedAck(transactionId))
                .post("/api/secom/v2/acknowledgement")
                .then()
                .statusCode(400);

        // The foreign transaction state is untouched
        Tx.requiringNew().run(() -> {
            SecomOutboxEntry entry = outboxService.search(null, 10).stream()
                    .filter(o -> o.getTransactionId().equals(transactionId))
                    .findFirst().orElseThrow();
            assertEquals(SecomOutboxEntry.Status.SENT, entry.getStatus());
        });

        // And an unknown transaction is equally rejected
        secomGiven()
                .contentType(ContentType.JSON)
                .body(signedAck(UUID.randomUUID()))
                .post("/api/secom/v2/acknowledgement")
                .then()
                .statusCode(400);
    }

    @Test
    void tamperedAcknowledgementSignaturesAreRejected() throws Exception {
        UUID transactionId = committedSentUpload(CLIENT_MRN);

        // Corrupt the envelope after signing - the SECOM signature filter must reject it
        String tampered = signedAck(transactionId)
                .replace(transactionId.toString(), UUID.randomUUID().toString());

        int status = secomGiven()
                .contentType(ContentType.JSON)
                .body(tampered)
                .post("/api/secom/v2/acknowledgement")
                .then()
                .extract().statusCode();
        assertTrue(status >= 400, "a tampered envelope signature must not be accepted, got " + status);

        Tx.requiringNew().run(() -> {
            SecomOutboxEntry entry = outboxService.search(null, 10).stream()
                    .filter(o -> o.getTransactionId().equals(transactionId))
                    .findFirst().orElseThrow();
            assertEquals(SecomOutboxEntry.Status.SENT, entry.getStatus(), "the transaction state must be unchanged");
        });
    }
}
