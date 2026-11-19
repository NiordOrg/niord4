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
package org.niord.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.restassured.http.ContentType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageHistory;
import org.niord.core.message.MessageLuceneIndex;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.message.Reference;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.ReferenceType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fault injection at the REST edges of the delivery paths: what a duty officer or a public client is told when a
 * step behind the endpoint does not do what was asked: the cascade-cancel across domains, the public API's malformed
 * wkt (API3 in be/docs/potential-errors.md), the editor's search given a query or an index it cannot read, the UTM
 * import's unreadable line, and the message endpoints given an incomplete body.
 *
 * <p>The messages are committed with {@link Tx} (an HTTP request sees committed rows only) and removed afterwards.
 */
class DeliveryFaultRestITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @Autowired
    MessageLuceneIndex messageLuceneIndex;

    @PersistenceContext
    EntityManager em;

    private final List<String> createdUids = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // The publisher first: it references the victim
        for (int i = createdUids.size() - 1; i >= 0; i--) {
            String uid = createdUids.get(i);
            try {
                Tx.requiringNew().run(() -> {
                    Message msg = messageQueryService.findByUid(uid);
                    if (msg != null) {
                        em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                                .setParameter("messageId", msg.getId())
                                .getResultList()
                                .forEach(em::remove);
                        em.remove(msg);
                    }
                });
            } catch (Exception ignored) {
                // best effort
            }
        }
        createdUids.clear();
    }

    private static Date pastPublishDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2001, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    private Message newDraft(String seriesId, Type type, String title) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries(seriesId));
        msg.setType(type);
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(pastPublishDate());
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        msg.createDesc("en").setTitle(title);
        return msg;
    }

    private String createPublished(String seriesId, Type type, String title) {
        String uid = Tx.requiringNew().call(() -> {
            Message m = messageService.createMessage(newDraft(seriesId, type, title));
            messageService.updateStatus(m.getUid(), Status.VERIFIED);
            messageService.updateStatus(m.getUid(), Status.PUBLISHED);
            return m.getUid();
        });
        createdUids.add(uid);
        return uid;
    }

    private String createVerifiedCancelling(String seriesId, Type type, String title, String referencedUid) {
        String uid = Tx.requiringNew().call(() -> {
            Message msg = newDraft(seriesId, type, title);
            Reference ref = new Reference();
            ref.setMessageId(referencedUid);
            ref.setType(ReferenceType.CANCELLATION);
            msg.addReference(ref);
            Message m = messageService.createMessage(msg);
            messageService.updateStatus(m.getUid(), Status.VERIFIED);
            return m.getUid();
        });
        createdUids.add(uid);
        return uid;
    }

    private Status statusOf(String uid) {
        return Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getStatus());
    }

    // ------------------------------------------------------------------------------------------- //
    // Cascade-cancel across domains
    // ------------------------------------------------------------------------------------------- //

    /**
     * An NM of the NM domain cancels (a CANCELLATION reference) a published NW of the NW domain. The publish
     * dialog's list ({@code GET /referenced-messages/{id}}, filtered by viewing access, which any public message
     * passes) offers the NW under "Also cancel the referenced messages"; the editor publishes with
     * {@code cancelReferencedMessages=true} in the NM domain. The NM domain cannot change the NW's status, so the
     * publish is refused before anything changes: 409, whose detail names the NW and says what to do. The NM stays
     * VERIFIED and the NW PUBLISHED.
     *
     * <p>Before the fix the cascade left the NW out without a word and answered 200: the NM was published, the NW
     * stayed in force beside it, and the frontend toasted "Message published".
     */
    @Test
    void publishingWithCancelReferencedRefusesAReferencedWarningOfAnotherDomain() {
        long n = System.nanoTime();
        String victim = createPublished("dma-nw", Type.COASTAL_WARNING, "IT msg4 victim " + n);
        String victimShortId = Tx.requiringNew().call(() -> messageQueryService.findByUid(victim).getShortId());
        String publisher = createVerifiedCancelling("dma-nm", Type.TEMPORARY_NOTICE, "IT msg4 publisher " + n, victim);

        // The publish dialog lists the NW of the other domain as one to cancel
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nm")
                .queryParam("status", "PUBLISHED")
                .queryParam("referenceType", "CANCELLATION")
                .when().get("/rest/messages/referenced-messages/" + publisher)
                .then().statusCode(200)
                .body("id", hasItem(victim));

        // Publish with "Also cancel the referenced messages" ticked
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nm")
                .contentType(ContentType.JSON)
                .queryParam("cancelReferencedMessages", true)
                .body("PUBLISHED")
                .when().put("/rest/messages/message/" + publisher + "/status")
                .then().statusCode(409)
                .body("detail", containsString(victimShortId))
                .body("detail", containsString("another domain"));

        assertEquals(Status.VERIFIED, statusOf(publisher), "the refused publisher is not published");
        assertEquals(Status.PUBLISHED, statusOf(victim), "the referenced NW of the other domain is untouched");

        // Without "Also cancel", the same publish goes through and leaves the NW alone
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nm")
                .contentType(ContentType.JSON)
                .body("PUBLISHED")
                .when().put("/rest/messages/message/" + publisher + "/status")
                .then().statusCode(200)
                .body("status", equalTo("PUBLISHED"));
        assertEquals(Status.PUBLISHED, statusOf(victim));
    }

    // ------------------------------------------------------------------------------------------- //
    // An incomplete body is a client error, not a server error
    // ------------------------------------------------------------------------------------------- //

    private long messageCount() {
        return Tx.requiringNew().call(() ->
                em.createQuery("select count(m) from Message m", Long.class).getSingleResult());
    }

    /**
     * {@code POST /rest/messages/message} with an empty body, and with a series of the caller's domain but no
     * status, answers 400 naming the missing field, and stores nothing. Before the fix both reached a
     * {@code NullPointerException} in the access check and answered 500.
     */
    @Test
    void aMessageCreateWithoutASeriesOrAStatusAnswers400AndStoresNothing() {
        long before = messageCount();

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/rest/messages/message")
                .then().statusCode(400)
                .body("detail", containsString("message series"));

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("{\"messageSeries\":{\"seriesId\":\"dma-nw\"},\"type\":\"COASTAL_WARNING\"}")
                .when().post("/rest/messages/message")
                .then().statusCode(400)
                .body("detail", containsString("status"));

        assertEquals(before, messageCount(), "nothing is stored");
    }

    /**
     * {@code PUT /rest/messages/message/{id}} with only the id answers 400 and leaves the message as it was.
     * Before the fix it answered 500 (a {@code NullPointerException} on the body's missing series).
     */
    @Test
    void aMessageUpdateWithOnlyItsIdAnswers400AndChangesNothing() {
        String uid = Tx.requiringNew().call(() -> messageService.createMessage(newDraft("dma-nw",
                Type.COASTAL_WARNING, "IT update id only " + System.nanoTime())).getUid());
        createdUids.add(uid);

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("{\"id\":\"" + uid + "\"}")
                .when().put("/rest/messages/message/" + uid)
                .then().statusCode(400)
                .body("detail", containsString("message series"));

        assertEquals(Status.DRAFT, statusOf(uid));
    }

    /**
     * {@code POST /rest/messages/update-message-publications} for a known publication with a message that has
     * no {@code descs} answers 400. Before the fix it answered 500 (a {@code NullPointerException} on the descs).
     */
    @Test
    void updateMessagePublicationsWithoutDescsAnswers400() {
        String publicationId = given()
                .queryParam("messagePublication", "EXTERNAL")
                .when().get("/rest/publications/search")
                .then().statusCode(200)
                .extract().path("data[0].publicationId");

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .queryParam("publicationId", publicationId)
                .body("{\"id\":\"IT-update-msgpub-" + System.nanoTime() + "\"}")
                .when().post("/rest/messages/update-message-publications")
                .then().statusCode(400);
    }

    /**
     * {@code POST /rest/messages/format-message-geometry} with a plain template name that names no geometry
     * template answers 404 naming it. Before the fix the catch-all answered 500. The caller's mistake is not logged
     * at ERROR (TMPL19): the template builder used to log every template it could not load at ERROR with the stack,
     * one per anonymous request.
     */
    @Test
    void aGeometryTemplateThatDoesNotExistAnswers404() {
        String name = "no-such-template-" + System.nanoTime();
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            given().contentType(ContentType.JSON)
                    .queryParam("template", name)
                    .body("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},"
                            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[10.5,56.0]}}]}")
                    .when().post("/rest/messages/format-message-geometry")
                    .then().statusCode(404)
                    .body(containsString(name));
        } finally {
            logger.detachAppender(appender);
        }
        List<String> errors = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains(name))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertEquals(List.of(), errors, "an unknown template name is the caller's mistake, not logged at ERROR");
    }

    // ------------------------------------------------------------------------------------------- //
    // API3: the public API's malformed wkt
    // ------------------------------------------------------------------------------------------- //

    /**
     * API3. {@code GET /rest/public/v1/messages?wkt=POLYGON((1 2} (a truncated WKT): {@code MessageSearchParams.extent}
     * swallows the {@code ParseException}, no extent is applied, and the answer is 200 with every published message of
     * the series, as if all of them lay in the client's area. The positioned fixture warning NW-001-26 (off Denmark) is
     * in that answer, while a valid polygon in the Southern Ocean leaves it out.
     *
     * <p>What is wrong: a client cannot tell a bad query from a real answer and receives warnings for positions it
     * did not ask about. The right assertion: 400. (The v1 API is frozen, so the fix changes its contract.)
     */
    @Test
    void aMalformedWktIsIgnoredAndAnswersEveryMessage_documentsBug() {
        given().queryParam("messageSeries", "dma-nw").queryParam("lang", "en")
                .when().get("/rest/public/v1/messages")
                .then().statusCode(200)
                .body("shortId", hasItem("NW-001-26"));

        given().queryParam("messageSeries", "dma-nw").queryParam("lang", "en")
                .queryParam("wkt", "POLYGON((-10 -80, 10 -80, 10 -70, -10 -70, -10 -80))")
                .when().get("/rest/public/v1/messages")
                .then().statusCode(200)
                .body("shortId", not(hasItem("NW-001-26")));

        given().queryParam("messageSeries", "dma-nw").queryParam("lang", "en")
                .queryParam("wkt", "POLYGON((1 2")
                .when().get("/rest/public/v1/messages")
                .then().statusCode(200)
                .body("shortId", hasItem("NW-001-26"));
    }

    /**
     * API3, the case the record left unchecked ("may throw IllegalArgumentException past the catch and give a 5xx"):
     * a WKT that parses into an invalid geometry (a self-intersecting "bow tie" over 0-10 E, 0-10 N, far from every
     * fixture warning) is applied as given. The answer is 200 and leaves the warning off Denmark out, and no search
     * error is logged: no 5xx, no swallowed failure. A regression guard; the record's guess does not hold.
     */
    @Test
    void aSelfIntersectingWktPolygonIsAppliedAsGivenWithoutAServerError() {
        // The public API's search lets its failures through, so a failed search would answer a 5xx here, not this
        // 200. The log of the search is checked as well: nothing at WARN or above.
        var log = org.niord.test.JmsFaults.captureLog(org.niord.core.message.MessageSearchService.class);
        try {
            given().queryParam("messageSeries", "dma-nw").queryParam("lang", "en")
                    .queryParam("wkt", "POLYGON((0 0, 10 10, 10 0, 0 10, 0 0))")
                    .when().get("/rest/public/v1/messages")
                    .then().statusCode(200)
                    .body("shortId", not(hasItem("NW-001-26")));
        } finally {
            org.niord.test.JmsFaults.stopCapture(org.niord.core.message.MessageSearchService.class, log);
        }
        assertEquals(List.of(), org.niord.test.JmsFaults.warningsAndErrors(log),
                "the search logged no failure: the polygon was applied, not swallowed");
    }

    // ------------------------------------------------------------------------------------------- //
    // Who may change a status
    // ------------------------------------------------------------------------------------------- //

    /**
     * A signed-in user who is not an editor may not publish a warning: {@code PUT /message/{id}/status} answers 403
     * and the warning stays VERIFIED. The pins of this class send every status change as an administrator, so a
     * status change opened to every user passed them.
     */
    @Test
    void aStatusChangeIsRefusedToAUserWhoIsNotAnEditorAndChangesNothing() {
        String uid = Tx.requiringNew().call(() -> {
            Message m = messageService.createMessage(newDraft("dma-nw", Type.COASTAL_WARNING,
                    "IT status by a user " + System.nanoTime()));
            messageService.updateStatus(m.getUid(), Status.VERIFIED);
            return m.getUid();
        });
        createdUids.add(uid);

        given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("PUBLISHED")
                .when().put("/rest/messages/message/" + uid + "/status")
                .then().statusCode(403);
        assertEquals(Status.VERIFIED, statusOf(uid), "the refused publication changed nothing");
    }

    // ------------------------------------------------------------------------------------------- //
    // The UTM import's unreadable line, as the editor's dialog receives it
    // ------------------------------------------------------------------------------------------- //

    /**
     * The UTM import, at the endpoint the geometry import dialog calls. Three UTM lines, the middle one
     * unreadable ({@code GARBAGE}): {@code POST /rest/messages/parse-utm} refuses the whole text and answers no
     * geometry (the endpoint's null, an empty 204), so the dialog shows its invalid highlight and Import stays
     * disabled, instead of answering a MultiPoint of the two readable lines. {@code PlainTextConverterGridTest}
     * tests the converter (the line named in the exception, blank lines, a decimal comma).
     */
    @Test
    void theUtmEndpointRefusesATextWithAnUnreadableLine() {
        String text = "32 V 624726.15 6207884.60\nGARBAGE\n32 V 624900.00 6208000.00";
        String body = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(java.util.Map.of("geometryText", text))
                .when().post("/rest/messages/parse-utm")
                .then().statusCode(204)
                .extract().asString();
        assertEquals("", body, "no geometry for a text with an unreadable line");
    }

    // ------------------------------------------------------------------------------------------- //
    // The editor's search: a query that cannot be read, and an index that cannot be read
    // ------------------------------------------------------------------------------------------- //

    /**
     * The editor's search with a free-text query the Lucene parser cannot read (an unbalanced quote) answers 400,
     * so the editor sees an error rather than "no messages" for a query that never ran.
     */
    @Test
    void anUnbalancedQuoteInTheEditorsSearchAnswersInvalidQuery() throws Exception {
        rebuildTheIndexAndAwaitTheFixture();

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .queryParam("query", "nw \"")
                .queryParam("maxSize", 10)
                .when().get("/rest/messages/search")
                .then().statusCode(400);
    }

    /**
     * The editor's search while the free-text index cannot be read (here its folder is swapped for an empty one, so
     * opening the reader throws {@code IndexNotFoundException}) answers a server error, not 200 with no messages: a
     * duty officer checking whether a warning exists is not told that it does not.
     */
    @Test
    void aFreeTextSearchWhileTheIndexIsUnreadableAnswersAnErrorNotNoMessages() throws Exception {
        rebuildTheIndexAndAwaitTheFixture();

        // The shared index's own pass (MessageLuceneIndex.updateLuceneIndex, second 38 of every minute) must not run
        // while its folder is swapped: it would fill the empty folder, and the shared index would miss what it indexed
        Object index = AopTestUtils.getUltimateTargetObject(messageLuceneIndex);
        awayFromSecond(38);
        Object sharedFolder = ReflectionTestUtils.getField(index, "indexFolder");
        Object sharedReader = ReflectionTestUtils.getField(index, "reader");
        Path emptyFolder = Files.createTempDirectory("niord-it-index-down");
        ReflectionTestUtils.setField(index, "indexFolder", emptyFolder);
        ReflectionTestUtils.setField(index, "reader", null);
        try {
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .queryParam("query", "nw")
                    .queryParam("maxSize", 10)
                    .when().get("/rest/messages/search")
                    .then().statusCode(greaterThanOrEqualTo(500));
        } finally {
            ReflectionTestUtils.setField(index, "indexFolder", sharedFolder);
            ReflectionTestUtils.setField(index, "reader", sharedReader);
        }
    }

    /**
     * Rebuilds the free-text index from the committed rows, as MessageSearchRestServiceITTest does, and waits until
     * "nw" (a token of every NW short id, NW-001-26 and on) finds the fixture's NW warnings. The index folder is shared
     * by every application context of the run, each context empties it when it starts (MessageLuceneIndex.init), and a
     * context closed within the five seconds before its start-up pass leaves it empty.
     */
    private void rebuildTheIndexAndAwaitTheFixture() throws Exception {
        messageLuceneIndex.recreateIndex();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(90))
                .pollInterval(java.time.Duration.ofSeconds(2))
                .until(() -> editorSearchTotal("nw") > 0);
    }

    /**
     * Waits until a per-minute job that runs at the given second of the minute is at least 20 seconds away and its
     * last run was due at least five seconds ago. A narrowing of the window, not a lock.
     */
    private static void awayFromSecond(int cronSecond) throws InterruptedException {
        while (true) {
            int sinceRun = Math.floorMod(java.time.LocalTime.now().getSecond() - cronSecond, 60);
            if (sinceRun >= 5 && sinceRun <= 40) {
                return;
            }
            Thread.sleep(250);
        }
    }

    private int editorSearchTotal(String query) {
        return given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .queryParam("query", query)
                .queryParam("maxSize", 10)
                .when().get("/rest/messages/search")
                .then().statusCode(200)
                .extract().path("total");
    }
}
