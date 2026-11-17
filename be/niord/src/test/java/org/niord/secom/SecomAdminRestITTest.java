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

import org.grad.secomv2.core.models.enums.SubscriptionEventEnum;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.SecomOutboxEntry;
import org.niord.secom.model.SecomSubscription;
import org.niord.test.Tx;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ITs for the sysadmin SECOM API at {@code /rest/secom}: the role matrix (sysadmin only)
 * and the operational endpoints — status, runtime configuration (node-wide singleton +
 * per-series exposure), subscriptions, datasets (incl. GML download and republish),
 * reconcile trigger, and outbox retry/cancel.
 */
class SecomAdminRestITTest extends SecomITTest {

    /** Commits a published message + dataset and returns the dataset */
    private S124Dataset committedDataset() {
        return Tx.requiringNew().call(() -> {
            Message published = publishNw("secom-admin-" + uniq(), 55.5, 11.1);
            datasetService.onMessageStatusChanged(published.getUid());
            return datasetService.findByMessageUid(published.getUid());
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // Role matrix - every /rest/secom endpoint is sysadmin-only
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anonymousCallersAreRejected() {
        given().get("/rest/secom/status").then().statusCode(401);
        given().get("/rest/secom/datasets").then().statusCode(401);
        given().get("/rest/secom/outbox").then().statusCode(401);
        given().get("/rest/secom/series-config").then().statusCode(401);
    }

    @Test
    void nonSysadminRolesAreRejected() {
        given().auth().oauth2(accessToken("editor"))
                .get("/rest/secom/status").then().statusCode(403);
        given().auth().oauth2(accessToken("admin"))
                .get("/rest/secom/status").then().statusCode(403);
        given().auth().oauth2(accessToken("admin"))
                .get("/rest/secom/series-config").then().statusCode(403);
        given().auth().oauth2(accessToken("editor"))
                .delete("/rest/secom/series-config/dma-nw").then().statusCode(403);
    }

    @Test
    void sysadminIsAllowed() {
        given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(200)
                .body("enabled", equalTo(true))
                .body("serviceCertificate.subject", notNullValue())
                .body("serviceCertificate.created", notNullValue())
                .body("serviceCertificate.expires", notNullValue())
                .body("serviceCertificate.fingerprint", notNullValue())
                .body("rootCertificate.subject", notNullValue())
                .body("scopedSeries", hasSize(1))
                .body("scopedSeries[0]", equalTo("dma-nw"));
    }

    @Test
    void statusExposesCertificateAndRenewalDetails() {
        var json = given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(200)
                // The unattended renewal is not armed (or configured) in the IT environment
                .body("renewal.enabled", equalTo(false))
                .body("renewal.configured", equalTo(false))
                .body("renewal.renewsAt", nullValue())
                .body("renewal.renewsInDays", nullValue())
                .body("renewal.windowDays", equalTo(60))
                .body("serviceCertificate.ageDays", greaterThanOrEqualTo(0))
                .body("mirPortalUrl", equalTo(""))
                .body("trustedCertificates", hasSize(greaterThanOrEqualTo(1)))
                .body("trustedCertificates.findAll { it.root }", hasSize(1))
                .extract().jsonPath();

        // The root-flagged truststore entry is the root certificate itself
        assertEquals(json.getString("rootCertificate.fingerprint"),
                json.getString("trustedCertificates.find { it.root }.fingerprint"));
        assertNotNull(json.getString("trustedCertificates[0].alias"));
        assertNotNull(json.getString("trustedCertificates[0].subject"));
        assertNotNull(json.getString("trustedCertificates[0].expires"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Runtime configuration
    // ------------------------------------------------------------------------------------------- //

    @Test
    void seriesConfigCrudDrivesTheScopedSeries() {
        // The base setup exposes the seeded dma-nw series
        given().auth().oauth2(accessToken())
                .get("/rest/secom/series-config")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].seriesId", equalTo("dma-nw"))
                .body("[0].exposeOverSecom", equalTo(true));

        given().auth().oauth2(accessToken())
                .contentType("application/json")
                .body(Map.of("seriesId", "dma-nm", "exposeOverSecom", true))
                .put("/rest/secom/series-config/dma-nm")
                .then()
                .statusCode(200)
                .body("seriesId", equalTo("dma-nm"))
                .body("exposeOverSecom", equalTo(true))
                .body("languages", empty());

        given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(200)
                .body("scopedSeries", hasSize(2));

        given().auth().oauth2(accessToken())
                .delete("/rest/secom/series-config/dma-nm")
                .then()
                .statusCode(200)
                .body("seriesId", equalTo("dma-nm"));

        given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(200)
                .body("scopedSeries", hasSize(1))
                .body("scopedSeries[0]", equalTo("dma-nw"));

        Tx.requiringNew().run(() ->
                assertNull(secomConfigService.findSeriesConfig("dma-nm"), "the config row is deleted"));
    }

    @Test
    void seriesConfigCarriesTheLanguageRestriction() {
        // Stored canonically: model-language order ("da", "en" in the seed), lower-cased
        given().auth().oauth2(accessToken())
                .contentType("application/json")
                .body(Map.of("seriesId", "dma-nw", "exposeOverSecom", true, "languages", List.of("EN", "da")))
                .put("/rest/secom/series-config/dma-nw")
                .then()
                .statusCode(200)
                .body("exposeOverSecom", equalTo(true))
                .body("languages", contains("da", "en"));

        given().auth().oauth2(accessToken())
                .get("/rest/secom/series-config")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].seriesId", equalTo("dma-nw"))
                .body("[0].languages", contains("da", "en"));

        // A language outside the model languages is a 400 and leaves the row as it was
        given().auth().oauth2(accessToken())
                .contentType("application/json")
                .body(Map.of("seriesId", "dma-nw", "exposeOverSecom", true, "languages", List.of("fr")))
                .put("/rest/secom/series-config/dma-nw")
                .then()
                .statusCode(400);

        Tx.requiringNew().run(() ->
                assertEquals(List.of("da", "en"), secomConfigService.findSeriesConfig("dma-nw").getLanguages()));

        // A PUT without languages clears the restriction; the exposure flag travels with it
        given().auth().oauth2(accessToken())
                .contentType("application/json")
                .body(Map.of("seriesId", "dma-nw", "exposeOverSecom", false))
                .put("/rest/secom/series-config/dma-nw")
                .then()
                .statusCode(200)
                .body("exposeOverSecom", equalTo(false))
                .body("languages", empty());

        given().auth().oauth2(accessToken())
                .delete("/rest/secom/series-config/dma-nw")
                .then()
                .statusCode(200)
                .body("seriesId", equalTo("dma-nw"))
                .body("languages", empty());
    }

    // ------------------------------------------------------------------------------------------- //
    // Datasets
    // ------------------------------------------------------------------------------------------- //

    @Test
    void datasetsCanBeListedFetchedAndDownloaded() {
        S124Dataset dataset = committedDataset();

        given().auth().oauth2(accessToken())
                .get("/rest/secom/datasets")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].uuid", equalTo(dataset.getUuid().toString()))
                .body("[0].mrn", equalTo(dataset.getMrn()))
                .body("[0].status", equalTo("ACTIVE"))
                .body("[0].gmlSize", greaterThanOrEqualTo(1));

        given().auth().oauth2(accessToken())
                .get("/rest/secom/datasets/{uuid}", dataset.getUuid().toString())
                .then()
                .statusCode(200)
                .body("mrn", equalTo(dataset.getMrn()));

        String gml = given().auth().oauth2(accessToken())
                .get("/rest/secom/datasets/{uuid}/gml", dataset.getUuid().toString())
                .then()
                .statusCode(200)
                .extract().asString();
        assertEquals(dataset.getCurrentVersion().getGml(), gml, "the GML download returns the exact published bytes");
    }

    @Test
    void republishEnqueuesToMatchingSubscriptions() {
        S124Dataset dataset = committedDataset();
        Tx.requiringNew().run(() -> {
            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            subscriptionService.subscribe(CLIENT_MRN, template);
        });

        given().auth().oauth2(accessToken())
                .post("/rest/secom/datasets/{uuid}/republish", dataset.getUuid().toString())
                .then()
                .statusCode(200)
                .body(equalTo("1"));

        Tx.requiringNew().run(() -> {
            long uploads = outboxService.search(null, 10).stream()
                    .filter(o -> o.getOperation() == SecomOutboxEntry.Operation.UPLOAD)
                    .count();
            assertEquals(1, uploads);
        });
    }

    @Test
    void republishOfACorruptPublishedRecordIsAServerFault() {
        S124Dataset dataset = committedDataset();
        Tx.requiringNew().run(() -> {
            // Corrupt the published entry behind the service's back: the exchange-set build fails
            // reading the version's record, a server fault (500), not a client-state conflict
            // (409, which RestExceptionStatusAdvice gives an IllegalStateException)
            datasetService.findByUuid(dataset.getUuid()).getCurrentVersion().setDiscoveryMetadata("<not-the-entry/>");
            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            subscriptionService.subscribe(CLIENT_MRN, template);
        });

        given().auth().oauth2(accessToken())
                .post("/rest/secom/datasets/{uuid}/republish", dataset.getUuid().toString())
                .then()
                .statusCode(500);

        Tx.requiringNew().run(() -> {
            long uploads = outboxService.search(null, 10).stream()
                    .filter(o -> o.getOperation() == SecomOutboxEntry.Operation.UPLOAD)
                    .count();
            assertEquals(0, uploads, "a failed build enqueues nothing");
        });
    }

    @Test
    void reconcileEndpointBackfillsPublishedMessages() throws Exception {
        Message published = Tx.requiringNew().call(() -> publishNw("secom-admin-reconcile-" + uniq(), 55.5, 11.1));

        // The committed publish fires the real JMS listener, which creates the dataset row
        // concurrently with this test. Wait for it to settle and remove the row, so the
        // reconcile below is deterministically the one backfilling it (racing the listener
        // is safe - see reconcileEndpointSurvivesRacingTheStatusListener - but resolves as
        // "unchanged" rather than "created" when the listener wins)
        assertNotNull(awaitDatasetForMessage(published.getUid()), "the listener creates the dataset");
        Tx.requiringNew().run(() -> deleteDatasetsOfMessages(List.of(published.getUid())));

        given().auth().oauth2(accessToken())
                .post("/rest/secom/datasets/reconcile")
                .then()
                .statusCode(200)
                .body("created", greaterThanOrEqualTo(1));
    }

    @Test
    void reconcileEndpointSurvivesRacingTheStatusListener() {
        // The original SECOM1 shape: the committed publish fires the real JMS listener, and
        // the immediate admin reconcile runs alongside it on creating the same dataset row.
        // The two creators take turns on the message's dataset lock, so the second finds the
        // row the first committed; every interleaving must answer 200 and leave exactly one row
        Message published = Tx.requiringNew().call(() -> publishNw("secom-admin-race-" + uniq(), 55.5, 11.1));

        given().auth().oauth2(accessToken())
                .post("/rest/secom/datasets/reconcile")
                .then()
                .statusCode(200);

        Tx.requiringNew().run(() -> {
            Long rows = em.createQuery("select count(d) from S124Dataset d where d.message.uid = :uid", Long.class)
                    .setParameter("uid", published.getUid())
                    .getSingleResult();
            assertEquals(1L, rows, "every interleaving must leave exactly one dataset row");
            assertEquals(S124Dataset.Status.ACTIVE,
                    datasetService.findByMessageUid(published.getUid()).getStatus());
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // Subscriptions + outbox operations
    // ------------------------------------------------------------------------------------------- //

    @Test
    void adminCanRemoveSubscriptionsAndManageTheOutbox() {
        UUID subscriptionUuid = Tx.requiringNew().call(() -> {
            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            return subscriptionService.subscribe(CLIENT_MRN, template).getUuid();
        });

        given().auth().oauth2(accessToken())
                .get("/rest/secom/subscriptions")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].uuid", equalTo(subscriptionUuid.toString()))
                .body("[0].nodeMrn", equalTo(CLIENT_MRN));

        given().auth().oauth2(accessToken())
                .delete("/rest/secom/subscriptions/{uuid}", subscriptionUuid.toString())
                .then()
                .statusCode(200);
        Tx.requiringNew().run(() ->
                assertNull(subscriptionService.findByUuid(subscriptionUuid), "the subscription is removed"));

        // The subscribe/remove notifications are now PENDING outbox entries; retry + cancel one
        Integer entryId = given().auth().oauth2(accessToken())
                .get("/rest/secom/outbox")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].status", equalTo("PENDING"))
                .extract().path("[0].id");

        given().auth().oauth2(accessToken())
                .post("/rest/secom/outbox/{id}/retry", entryId)
                .then()
                .statusCode(200);
        assertEquals(SecomOutboxEntry.Status.PENDING, outboxEntry(entryId).getStatus(), "retry resets the entry for dispatch");

        given().auth().oauth2(accessToken())
                .delete("/rest/secom/outbox/{id}", entryId)
                .then()
                .statusCode(200);
        assertEquals(SecomOutboxEntry.Status.CANCELLED, outboxEntry(entryId).getStatus());

        // A cancelled entry is neither cancelled again nor reopened by a retry
        given().auth().oauth2(accessToken())
                .delete("/rest/secom/outbox/{id}", entryId)
                .then()
                .statusCode(409);
        given().auth().oauth2(accessToken())
                .post("/rest/secom/outbox/{id}/retry", entryId)
                .then()
                .statusCode(409);
        assertEquals(SecomOutboxEntry.Status.CANCELLED, outboxEntry(entryId).getStatus(), "the cancellation stands");

        given().auth().oauth2(accessToken())
                .get("/rest/secom/nodes")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    void outboxRetryAndCancelAnswer409ForAStatusTheyDoNotApplyTo() {
        List<Integer> ids = Tx.requiringNew().call(() -> {
            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            SecomSubscription subscription = subscriptionService.subscribe(CLIENT_MRN, template);
            return List.of(
                    outboxService.enqueueNotification(subscription, SubscriptionEventEnum.SUBSCRIPTION_CREATED).getId(),
                    outboxService.enqueueNotification(subscription, SubscriptionEventEnum.SUBSCRIPTION_CREATED).getId());
        });
        Integer acknowledged = ids.get(0);
        Integer failed = ids.get(1);
        Tx.requiringNew().run(() -> {
            em.createQuery("update SecomOutboxEntry o set o.status = :status, o.attempts = 1 where o.id = :id")
                    .setParameter("status", SecomOutboxEntry.Status.ACKED).setParameter("id", acknowledged)
                    .executeUpdate();
            // Failed by a NACK after the last of its attempts
            em.createQuery("update SecomOutboxEntry o set o.status = :status, o.attempts = 8, "
                            + "o.lastError = 'NACK: XML_SCHEMA_VALIDATION_ERROR', o.nackType = 'XML_SCHEMA_VALIDATION_ERROR' "
                            + "where o.id = :id")
                    .setParameter("status", SecomOutboxEntry.Status.FAILED).setParameter("id", failed)
                    .executeUpdate();
        });

        // An acknowledged entry is not sent again under its transaction id (republish the dataset instead)
        given().auth().oauth2(accessToken())
                .post("/rest/secom/outbox/{id}/retry", acknowledged)
                .then()
                .statusCode(409)
                .body("detail", containsString("only a PENDING or FAILED entry can be retried"));
        assertEquals(SecomOutboxEntry.Status.ACKED, outboxEntry(acknowledged).getStatus());
        given().auth().oauth2(accessToken())
                .delete("/rest/secom/outbox/{id}", acknowledged)
                .then()
                .statusCode(409);

        // A failed entry is retried, never cancelled
        given().auth().oauth2(accessToken())
                .delete("/rest/secom/outbox/{id}", failed)
                .then()
                .statusCode(409)
                .body("detail", containsString("only a PENDING entry can be cancelled"));
        assertEquals(SecomOutboxEntry.Status.FAILED, outboxEntry(failed).getStatus());

        given().auth().oauth2(accessToken())
                .post("/rest/secom/outbox/{id}/retry", failed)
                .then()
                .statusCode(200);
        SecomOutboxEntry retried = outboxEntry(failed);
        assertEquals(SecomOutboxEntry.Status.PENDING, retried.getStatus());
        assertEquals(0, retried.getAttempts(), "a retry gives a fresh schedule");
        assertNull(retried.getLastError());
        assertNull(retried.getNackType());

        // An unknown id stays a 400
        given().auth().oauth2(accessToken())
                .post("/rest/secom/outbox/{id}/retry", -1)
                .then()
                .statusCode(400);
    }

    /**
     * The overview's outbox counts cover every row (SECOM4). Before, they were grouped from the
     * newest 1000 rows only, so an older FAILED entry dropped out of the overview once a thousand
     * newer rows stood in front of it, while the actuator gauges still counted it.
     */
    @Test
    void theOverviewCountsEveryOutboxRowByStatus() {
        Tx.requiringNew().run(() -> {
            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            SecomSubscription subscription = subscriptionService.subscribe(CLIENT_MRN, template);
            // The subscription's notification is the oldest row; it failed long ago
            em.createQuery("update SecomOutboxEntry o set o.status = :status, o.attempts = 8")
                    .setParameter("status", SecomOutboxEntry.Status.FAILED)
                    .executeUpdate();
            // A thousand newer rows stand in front of it
            for (int i = 0; i < 1000; i++) {
                persistOutboxRow(subscription, SecomOutboxEntry.Status.ACKED);
                if (i % 200 == 199) {
                    em.flush();
                    em.clear();
                    subscription = subscriptionService.findByUuid(subscription.getUuid());
                }
            }
            for (SecomOutboxEntry.Status status : List.of(SecomOutboxEntry.Status.PENDING,
                    SecomOutboxEntry.Status.SENT, SecomOutboxEntry.Status.CANCELLED)) {
                persistOutboxRow(subscription, status);
            }
        });

        Map<String, Long> expected = Map.of("FAILED", 1L, "ACKED", 1000L, "PENDING", 1L, "SENT", 1L,
                "CANCELLED", 1L);
        Map<String, Long> groupedOverEveryRow = Tx.requiringNew().call(() -> {
            Map<String, Long> counts = new HashMap<>();
            for (Object[] row : em.createQuery(
                    "select o.status, count(o) from SecomOutboxEntry o group by o.status", Object[].class)
                    .getResultList()) {
                counts.put(((SecomOutboxEntry.Status) row[0]).name(), (Long) row[1]);
            }
            return counts;
        });
        assertEquals(expected, groupedOverEveryRow, "the fixture holds the rows it means to");

        Map<String, Object> body = given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(200)
                .extract().jsonPath().getMap("outboxByStatus");
        Map<String, Long> overview = new HashMap<>();
        body.forEach((status, count) -> overview.put(status, ((Number) count).longValue()));
        assertEquals(groupedOverEveryRow, overview, "the overview counts every outbox row, the oldest FAILED one too");
    }

    /** Persists one notification row of the given status for the subscription's node */
    private void persistOutboxRow(SecomSubscription subscription, SecomOutboxEntry.Status status) {
        SecomOutboxEntry entry = new SecomOutboxEntry();
        entry.setNode(subscription.getNode());
        entry.setSubscriptionUuid(subscription.getUuid());
        entry.setOperation(SecomOutboxEntry.Operation.SUBSCRIPTION_NOTIFICATION);
        entry.setNotificationEvent(SubscriptionEventEnum.SUBSCRIPTION_CREATED.name());
        entry.setStatus(status);
        em.persist(entry);
    }

    /** {@return the committed outbox entry with the given id} */
    private SecomOutboxEntry outboxEntry(Integer id) {
        return Tx.requiringNew().call(() -> em.find(SecomOutboxEntry.class, id));
    }
}
