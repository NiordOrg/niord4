/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.web.api;

import org.springframework.transaction.annotation.Transactional;
import org.niord.test.Tx;
import io.restassured.http.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link ApiRestService}: the anonymous, {@code @PermitAll} public REST API
 * rooted at {@code /rest/public/v1}. These tests exercise the HTTP contract that external clients see
 * (status codes, JSON/XML bodies, {@code Content-Type}, query parameters) without sending any token,
 * because every endpoint is {@code @PermitAll}.
 *
 * <p>The most important behaviour verified here is the public visibility gate enforced by
 * {@link AbstractApiService}: {@code getMessage} returns a message only when
 * {@code message.getStatus().isPublic()} (PUBLISHED / CANCELLED / EXPIRED), and the
 * {@code /messages} list is forced to {@code statuses = {PUBLISHED}} when only message series (no
 * tags) are supplied. A DRAFT message must therefore never leak: it is absent from the list and
 * yields a 404 on the detail endpoint.
 *
 * <p>These tests need COMMITTED data: an HTTP request runs in the server's own transaction, which
 * {@code @Transactional} cannot roll back and which only sees committed rows. Messages are
 * therefore created/published through {@link MessageService} (whose {@code @Transactional} methods
 * commit when invoked outside an enclosing transaction) and removed again in {@link #cleanup()}
 * using the {@code Tx.requiringNew()} pattern (delete the {@code MessageHistory}
 * rows first, then {@code em.remove} the {@code Message}). Every message uses a globally-unique title
 * token so a test only ever asserts on rows it created and the tests stay independent of the seed
 * data and of each other. Publishes use a far-past {@code publishDateFrom} (year 2001) so the
 * current-year number sequence asserted by other ITs is undisturbed.
 *
 * <p>Area, publication and XSD assertions read the committed seed ({@code db/niord-test-seed.sql}):
 * the area with MRN {@code urn:mrn:iho:country:dk} is an active root with many active children. Its
 * numeric id is resolved at runtime via {@code seedAreaId()} because Postgres assigns its own ids.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class ApiRestServiceITTest extends ITTest {

    /**
     * Seeded active root area (Denmark) with many active children, identified by its stable MRN.
     * Postgres assigns its own per-entity id (the legacy MySQL id 1192 no longer applies), so the
     * numeric id is resolved at runtime via {@link #seedAreaId()} from this MRN business key.
     */
    private static final String SEED_AREA_MRN = "urn:mrn:iho:country:dk";

    @Autowired
    MessageService messageService;

    @PersistenceContext
    EntityManager em;

    private final List<Integer> createdMessageIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Integer id : createdMessageIds) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                Message m = em.find(Message.class, id);
                if (m != null) {
                    em.remove(m);
                }
            });
        }
        createdMessageIds.clear();
    }

    /** A globally-unique alphabetic token used in titles so each test asserts only on its own rows. */
    private String uniqueToken() {
        return "zzapi" + System.nanoTime();
    }

    /**
     * Resolves the committed numeric id of the seeded {@link #SEED_AREA_MRN} area. Read in a fresh
     * committed transaction ({@code REQUIRES_NEW}) so it sees the same seed rows the public HTTP
     * endpoint serves.
     */
    private int seedAreaId() {
        return Tx.requiringNew().call(() ->
                em.createQuery("select a.id from Area a where a.mrn = :mrn", Integer.class)
                        .setParameter("mrn", SEED_AREA_MRN)
                        .getSingleResult());
    }

    /**
     * A fixed PAST publish date. Publishing takes a number from the YEARLY sequence of the series inside
     * the publishing transaction, and this suite's publishes commit (in a committed {@code Tx}), so
     * their numbers stay taken; a far-past date keeps these publishes
     * on the year-2001 sequence so they never disturb the current-year number that other ITs assert.
     */
    private static Date pastPublishDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2001, java.util.Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** Builds a detached DRAFT message template in the given series. */
    private Message newDraft(String seriesId, Type type, String title) {
        Message msg = new Message();
        // createMessage substitutes the persisted series looked up by id.
        msg.setMessageSeries(new MessageSeries(seriesId));
        msg.setType(type); // mainType is derived from the type
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(pastPublishDate()); // keep publishes off the current-year number sequence

        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);

        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /** Creates a COMMITTED DRAFT message (createMessage commits since it runs outside a tx). */
    private Message createDraft(String seriesId, Type type, String title) throws Exception {
        Message created = messageService.createMessage(newDraft(seriesId, type, title));
        createdMessageIds.add(created.getId());
        return created;
    }

    /**
     * Creates a COMMITTED message and publishes it (DRAFT -> VERIFIED -> PUBLISHED). Publishing
     * assigns the message number and short ID from the message series. The walk to PUBLISHED runs in
     * ONE committed transaction: the auto-publish job only sees committed state, so the atomic walk
     * never exposes a committed VERIFIED + past-date row for it to seize.
     */
    private Message createPublished(String seriesId, Type type, String title) throws Exception {
        Message created = createDraft(seriesId, type, title);
        return Tx.requiringNew().call(() -> {
            messageService.updateStatus(created.getUid(), Status.VERIFIED);
            return messageService.updateStatus(created.getUid(), Status.PUBLISHED);
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /public/v1/messages — anonymous list, JSON array, PUBLISHED-only (DRAFT-leak gate)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void messagesEndpointReturnsJsonArrayForAnonymousCaller() throws Exception {
        // The /messages endpoint serializes a List<MessageVo>, so the JSON root is an array. Even with
        // a series filter that currently has a published message, the top-level type must be a list.
        createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .queryParam("messageSeries", "dma-nw")
                .queryParam("lang", "en")
                .when().get("/rest/public/v1/messages")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", instanceOf(List.class));
    }

    @Test
    void messagesEndpointContainsPublishedMessageOfRequestedSeries() throws Exception {
        // A PUBLISHED dma-nw message must appear in the dma-nw-filtered public list, identified by its
        // UID (MessageVo.id == message UID).
        String token = uniqueToken();
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + token);

        given()
                .queryParam("messageSeries", "dma-nw")
                .queryParam("lang", "en")
                .when().get("/rest/public/v1/messages")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", hasItem(published.getUid()));
    }

    @Test
    void messagesEndpointHidesDraftMessage() throws Exception {
        // DRAFT-leak gate: AbstractApiService.searchMessages forces statuses={PUBLISHED} when only a
        // series (no tags) is supplied, so a committed DRAFT in the same series must be absent.
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + uniqueToken());

        given()
                .queryParam("messageSeries", "dma-nw")
                .queryParam("lang", "en")
                .when().get("/rest/public/v1/messages")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", not(hasItem(draft.getUid())));
    }

    @Test
    void messagesEndpointSeparatesPublishedFromDraftInSameSeries() throws Exception {
        // Mix in one request: the published message is present, the draft is not. This pins the gate
        // even when both rows exist concurrently in the same committed series.
        String pubToken = uniqueToken();
        String draftToken = uniqueToken();
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + pubToken);
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + draftToken);

        given()
                .queryParam("messageSeries", "dma-nw")
                .queryParam("lang", "en")
                .when().get("/rest/public/v1/messages")
                .then().statusCode(200)
                .body("id", hasItem(published.getUid()))
                .body("id", not(hasItem(draft.getUid())));
    }

    @Test
    void messagesEndpointHasJsonUtf8ContentType() throws Exception {
        // The endpoint builds the response as APPLICATION_JSON_TYPE.withCharset("utf-8").
        createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        String contentType = given()
                .queryParam("messageSeries", "dma-nw")
                .when().get("/rest/public/v1/messages")
                .then().statusCode(200)
                .extract().header("Content-Type");

        // application/json;charset=UTF-8 (case-insensitive on the charset token across servers).
        org.junit.jupiter.api.Assertions.assertTrue(
                contentType.toLowerCase().contains("application/json")
                        && contentType.toLowerCase().contains("utf-8"),
                "messages Content-Type must be application/json with utf-8 charset, was: " + contentType);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /public/v1/message/{messageId} — detail of a public message
    // ------------------------------------------------------------------------------------------- //

    @Test
    void messageDetailReturnsPublishedMessageBody() throws Exception {
        String token = uniqueToken();
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + token);

        given()
                .queryParam("lang", "en")
                .when().get("/rest/public/v1/message/" + published.getUid())
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", is(published.getUid()))
                .body("shortId", is(published.getShortId()))
                .body("descs[0].title", containsString(token));
    }

    @Test
    void messageDetailResolvableByShortId() throws Exception {
        // resolveMessage falls back to short-id lookup, so the public detail endpoint accepts either
        // the UID or the assigned short ID.
        String token = uniqueToken();
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + token);

        given()
                .queryParam("lang", "en")
                .when().get("/rest/public/v1/message/" + published.getShortId())
                .then().statusCode(200)
                .body("id", is(published.getUid()))
                .body("shortId", is(published.getShortId()));
    }

    @Test
    void messageDetailHasJsonUtf8ContentType() throws Exception {
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        String contentType = given()
                .when().get("/rest/public/v1/message/" + published.getUid())
                .then().statusCode(200)
                .extract().header("Content-Type");

        org.junit.jupiter.api.Assertions.assertTrue(
                contentType.toLowerCase().contains("application/json")
                        && contentType.toLowerCase().contains("utf-8"),
                "message detail Content-Type must be application/json with utf-8 charset, was: " + contentType);
    }

    @Test
    void messageDetailForDraftReturns404WithBody() throws Exception {
        // DRAFT-leak gate on the detail endpoint: getMessage returns null for a non-public status, so
        // even a known UID yields a 404 carrying the "No message found" body.
        Message draft = createDraft("dma-nw", Type.COASTAL_WARNING, "Draft " + uniqueToken());

        given()
                .when().get("/rest/public/v1/message/" + draft.getUid())
                .then().statusCode(404)
                .body(containsString("No message found with ID: " + draft.getUid()));
    }

    @Test
    void messagePartsCarryTheirOrderInIndexNo() throws Exception {
        // MSG7: indexNo is the part's place in the message, published as the order of the parts. A
        // published warning with a DETAILS and a NOTE part reads 0 and 1, on the detail endpoint and in
        // the list, not 0 for every part.
        Message draft = newDraft("dma-nw", Type.COASTAL_WARNING, "Parts " + uniqueToken());
        MessagePart note = new MessagePart(MessagePartType.NOTE);
        note.createDesc("en").setDetails("a note");
        draft.addPart(note);
        Message created = messageService.createMessage(draft);
        createdMessageIds.add(created.getId());
        Message published = Tx.requiringNew().call(() -> {
            messageService.updateStatus(created.getUid(), Status.VERIFIED);
            return messageService.updateStatus(created.getUid(), Status.PUBLISHED);
        });

        io.restassured.path.json.JsonPath detail = given()
                .queryParam("lang", "en")
                .when().get("/rest/public/v1/message/" + published.getUid())
                .then().statusCode(200)
                .extract().jsonPath();
        org.junit.jupiter.api.Assertions.assertEquals(List.of("DETAILS", "NOTE"), detail.getList("parts.type"));
        org.junit.jupiter.api.Assertions.assertEquals(List.of(0, 1), detail.getList("parts.indexNo"));

        io.restassured.path.json.JsonPath list = given()
                .queryParam("messageSeries", "dma-nw")
                .queryParam("lang", "en")
                .when().get("/rest/public/v1/messages")
                .then().statusCode(200)
                .extract().jsonPath();
        org.junit.jupiter.api.Assertions.assertEquals(List.of(0, 1),
                list.getList("find { it.id == '" + published.getUid() + "' }.parts.indexNo"));
    }

    @Test
    void messageDetailForUnknownIdReturns404() {
        String unknown = "does-not-exist-" + System.nanoTime();

        given()
                .when().get("/rest/public/v1/message/" + unknown)
                .then().statusCode(404)
                .body(containsString("No message found with ID: " + unknown));
    }

    // ------------------------------------------------------------------------------------------- //
    // dateFormat — UNIX_EPOCH (number) vs ISO_8601 (string)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void dateFormatUnixEpochEncodesDatesAsNumbers() throws Exception {
        // The default/explicit UNIX_EPOCH ObjectMapper writes dates with WRITE_DATES_AS_TIMESTAMPS, so
        // publishDateFrom (set on the published message) serializes as a numeric epoch value.
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .queryParam("dateFormat", "UNIX_EPOCH")
                .when().get("/rest/public/v1/message/" + published.getUid())
                .then().statusCode(200)
                .body("publishDateFrom", instanceOf(Number.class));
    }

    @Test
    void dateFormatIso8601EncodesDatesAsStrings() throws Exception {
        // With ISO_8601 the ObjectMapper disables WRITE_DATES_AS_TIMESTAMPS, so the same field is an
        // ISO-8601 string instead of a number. This pins the contract that drives Swagger codegen
        // clients (which cannot parse epoch timestamps).
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .queryParam("dateFormat", "ISO_8601")
                .when().get("/rest/public/v1/message/" + published.getUid())
                .then().statusCode(200)
                .body("publishDateFrom", instanceOf(String.class));
    }

    // ------------------------------------------------------------------------------------------- //
    // externalize — accepted for both values; observed parts-vs-attachments prefix asymmetry noted
    // ------------------------------------------------------------------------------------------- //

    @Test
    void externalizeTrueIsAcceptedAndReturnsTheMessage() throws Exception {
        // With no embedded repo links, externalize has nothing to rewrite; the contract under test is
        // that the param is accepted and the message is still returned. (See the structured findings
        // for the parts-vs-attachments prefix asymmetry in ApiRestService.toMessageVo.)
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .queryParam("externalize", true)
                .when().get("/rest/public/v1/message/" + published.getUid())
                .then().statusCode(200)
                .body("id", is(published.getUid()));
    }

    @Test
    void externalizeFalseIsAcceptedAndReturnsTheMessage() throws Exception {
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        given()
                .queryParam("externalize", false)
                .when().get("/rest/public/v1/message/" + published.getUid())
                .then().statusCode(200)
                .body("id", is(published.getUid()));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /public/v1/xsd/{schemaFile}
    // ------------------------------------------------------------------------------------------- //

    @Test
    void xsdSchema2ReturnsXmlSchema() {
        assertXsdServedUnderItsOwnName("schema2.xsd");
    }

    @Test
    void xsdSchema1ReturnsXmlSchema() {
        // schema1.xsd is the imported (secondary) schema; assert the same contract as schema2.xsd so
        // both files are verified with equal rigor.
        assertXsdServedUnderItsOwnName("schema1.xsd");
    }

    /**
     * API4: a schema answers 200 with the {@code <xs:schema} body and {@code application/xml;charset=UTF-8},
     * and names itself in {@code Content-Disposition: inline; filename="<schemaFile>"}. Without a header of
     * its own, Spring's guard against reflected file downloads added {@code inline;filename=f.txt}, and a
     * browser saved the schema as {@code f.txt}.
     */
    private static void assertXsdServedUnderItsOwnName(String schemaFile) {
        io.restassured.response.ExtractableResponse<io.restassured.response.Response> answer = given()
                .when().get("/rest/public/v1/xsd/" + schemaFile)
                .then().statusCode(200)
                .body(containsString("<xs:schema"))
                .extract();

        String contentType = answer.header("Content-Type");
        org.junit.jupiter.api.Assertions.assertEquals("application/xml;charset=utf-8",
                contentType == null ? null : contentType.toLowerCase().replace(" ", ""),
                "the XSD endpoint must return application/xml;charset=UTF-8, was: " + contentType);
        org.junit.jupiter.api.Assertions.assertEquals("inline; filename=\"" + schemaFile + "\"",
                answer.header("Content-Disposition"),
                "the XSD must be served inline under its own name");
    }

    @Test
    void xsdUnknownSchemaFileReturns400() {
        // getMessageXsd now rejects any file other than schema1.xsd/schema2.xsd with
        // NiordWebException(.., 400), which Spring renders as an RFC 9457 ProblemDetail with HTTP 400 -
        // an invalid path argument is a client error, not a server error.
        given()
                .when().get("/rest/public/v1/xsd/bogus.xsd")
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /public/v1/area/{areaId} and /sub-areas
    // ------------------------------------------------------------------------------------------- //

    @Test
    void areaByNumericIdReturnsSeededArea() {
        int seedAreaId = seedAreaId();
        given()
                .queryParam("lang", "en")
                .when().get("/rest/public/v1/area/" + seedAreaId)
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", is(seedAreaId))
                .body("mrn", is(SEED_AREA_MRN));
    }

    @Test
    void areaByMrnResolvesToSameSeededArea() {
        // findByAreaId resolves a non-numeric id as an MRN, so the MRN and the numeric id must return
        // the same area.
        given()
                .when().get("/rest/public/v1/area/" + SEED_AREA_MRN)
                .then().statusCode(200)
                .body("id", is(seedAreaId()))
                .body("mrn", is(SEED_AREA_MRN));
    }

    @Test
    void unknownAreaReturns404WithBody() {
        String unknown = "no-such-area-" + System.nanoTime();

        given()
                .when().get("/rest/public/v1/area/" + unknown)
                .then().statusCode(404)
                .body(containsString("No area found with ID: " + unknown));
    }

    @Test
    void subAreasReturnsNonEmptyActiveChildArray() {
        // The seeded Denmark root (MRN urn:mrn:iho:country:dk) is active with many active children, so
        // /sub-areas returns a non-empty JSON array. Every returned sub-area must be active (subAreas
        // filters on Area::isActive).
        given()
                .queryParam("lang", "en")
                .when().get("/rest/public/v1/area/" + seedAreaId() + "/sub-areas")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", instanceOf(List.class))
                .body("size()", greaterThanOrEqualTo(1))
                .body("active", not(hasItem(false)));
    }

    @Test
    void subAreasForUnknownAreaReturns404() {
        String unknown = "no-such-area-" + System.nanoTime();

        given()
                .when().get("/rest/public/v1/area/" + unknown + "/sub-areas")
                .then().statusCode(404)
                .body(containsString("No area found with ID: " + unknown));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /public/v1/publications and /publication/{id}
    // ------------------------------------------------------------------------------------------- //

    @Test
    void publicationsReturnsJsonArrayForAnonymousCaller() {
        // searchPublications serializes a List<PublicationVo>; with no from/to it returns the currently
        // active publications. The contract under test is the anonymous 200 + array shape.
        given()
                .queryParam("lang", "en")
                .when().get("/rest/public/v1/publications")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", instanceOf(List.class));
    }

    @Test
    void unknownPublicationReturns404WithBody() {
        String unknown = "no-such-publication-" + System.nanoTime();

        given()
                .when().get("/rest/public/v1/publication/" + unknown)
                .then().statusCode(404)
                .body(containsString("No publication found with ID: " + unknown));
    }

    // ------------------------------------------------------------------------------------------- //
    // Sanity: a published message carries a non-blank UID and short ID (basis for the assertions above)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void publishedMessageHasUidAndShortId() throws Exception {
        Message published = createPublished("dma-nw", Type.COASTAL_WARNING, "Pub " + uniqueToken());

        assertNotNull(published.getUid(), "a published message must have a UID");
        assertNotNull(published.getShortId(), "publishing must assign a short ID");

        // And that UID is the id resolved over HTTP.
        given()
                .when().get("/rest/public/v1/message/" + published.getUid())
                .then().statusCode(200)
                .body("id", equalTo(published.getUid()));
    }
}
