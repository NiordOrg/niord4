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

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessageHistory;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessagePublishGate;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageService;
import org.niord.core.promulgation.NavtexMessagePromulgation;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * MSG10: the two status endpoints, {@code PUT /rest/messages/message/{id}/status} and
 * {@code PUT /rest/messages/update-statuses}, hold VERIFIED and PUBLISHED to the editor's own Verify and Publish
 * ({@link MessagePublishGate}). A message without a title, or with a MANDATORY promulgation type not ticked, is
 * refused VERIFIED and PUBLISHED; a message whose publish-from date is still ahead is refused PUBLISHED. The refusal
 * is a 409 before anything changes, whose ProblemDetail property {@code refusals} lists
 * {@code [{ messageId, shortId?, reasons: [{ code, types?, date? }] }]}, the shape the Update Statuses dialog reads.
 * The bulk endpoint asks every item first and names every refused one in one answer.
 *
 * <p>Before the fix both endpoints checked access and the transition only: a title-less draft went to VERIFIED and
 * PUBLISHED, a mandatory channel off was ignored, and a scheduled message was published at once with its number
 * taken.
 *
 * <p>Not gated: the other target statuses, a transition {@code MessageService.updateStatus} refuses anyway (its own
 * 409 stays), and {@code MessageService.updateStatus} itself (the scheduler, the firing exercises, the imports).
 *
 * <p>The messages are committed in the seeded {@code dma-nw} series ({@code niord-nw}) and removed with their
 * history in {@link #cleanup()}. A message this class publishes has a far-past publish date, so its number is taken
 * from the year-2001 sequence, never the current year's (the one case that would publish a scheduled message if
 * the gate let it through gives the current year's next number back). The seed's promulgation types have no requirement; the case
 * of a MANDATORY type makes the seeded NAVTEX type MANDATORY for its own run and gives it its requirement back.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessagePublishGateITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @Autowired
    PromulgationTypeService promulgationTypeService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @PersistenceContext
    EntityManager em;

    /** UIDs of messages created during a test; removed (with their history) in {@link #cleanup()} */
    private final List<String> createdUids = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String uid : createdUids) {
            try {
                Tx.requiringNew().run(() -> {
                    Message msg = messageQueryService.findByUid(uid);
                    if (msg != null) {
                        em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                                .setParameter("messageId", msg.getId())
                                .getResultList()
                                .forEach(em::remove);
                        em.remove(em.contains(msg) ? msg : em.merge(msg));
                    }
                });
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdUids.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    private static Date date(int year) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** A far-past publish date: a publish takes its number from the year-2001 sequence */
    private static Date past() {
        return date(2001);
    }

    /** A publish date still ahead, out of the auto-publish job's reach */
    private static Date future() {
        return date(2999);
    }

    /** Creates a committed DRAFT with the English title given (none when null) and tracks it for cleanup */
    private String draft(String title, Date publishFrom) {
        String uid = Tx.requiringNew().call(() -> {
            Message msg = new Message();
            msg.setMessageSeries(new MessageSeries("dma-nw"));
            msg.setType(Type.COASTAL_WARNING);
            msg.setAutoTitle(false);
            msg.setPublishDateFrom(publishFrom);
            MessagePart part = new MessagePart(MessagePartType.DETAILS);
            MessagePartDesc pd = part.createDesc("en");
            pd.setSubject("subject");
            pd.setDetails("details");
            msg.addPart(part);
            MessageDesc desc = msg.createDesc("en");
            desc.setTitle(title);
            desc.setVicinity("IT gate vicinity");
            return messageService.createMessage(msg).getUid();
        });
        createdUids.add(uid);
        return uid;
    }

    /** Creates a committed message and takes it to VERIFIED through the service, which the gate never binds */
    private String verified(String title, Date publishFrom) {
        String uid = draft(title, publishFrom);
        Tx.requiringNew().call(() -> messageService.updateStatus(uid, Status.VERIFIED));
        return uid;
    }

    private ValidatableResponse putStatus(String uid, String status) {
        return given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(status)
                .when().put("/rest/messages/message/" + uid + "/status")
                .then();
    }

    private ValidatableResponse putStatuses(String... uidsAndStatuses) {
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < uidsAndStatuses.length; i += 2) {
            body.append(i == 0 ? "" : ",").append("{\"messageId\":\"").append(uidsAndStatuses[i])
                    .append("\",\"status\":\"").append(uidsAndStatuses[i + 1]).append("\"}");
        }
        return given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(body.append("]").toString())
                .when().put("/rest/messages/update-statuses")
                .then();
    }

    private Status statusOf(String uid) {
        return Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getStatus());
    }

    private Integer numberOf(String uid) {
        return Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getNumber());
    }

    private int historyCount(String uid) {
        return Tx.requiringNew().call(() -> em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                .setParameter("messageId", messageQueryService.findByUid(uid).getId())
                .getResultList()
                .size());
    }

    /** The date of a refusal's reason, written as an ISO string or as epoch milliseconds */
    private static Instant instant(Object written) {
        if (written instanceof Number millis) {
            return Instant.ofEpochMilli(millis.longValue());
        }
        if (written instanceof String text) {
            return OffsetDateTime.parse(text).toInstant();
        }
        return fail("no date in the reason: " + written);
    }

    // ------------------------------------------------------------------------------------------- //
    // The single endpoint
    // ------------------------------------------------------------------------------------------- //

    /** A draft without a title is refused VERIFIED: 409 with NO_TITLE, and it stays DRAFT */
    @Test
    void aDraftWithoutATitleIsRefusedVerifiedWith409() {
        String uid = draft(null, future());
        int history = historyCount(uid);

        putStatus(uid, "VERIFIED")
                .statusCode(409)
                .body("detail", containsString("it has no title"))
                .body("refusals.messageId", contains(uid))
                .body("refusals[0].reasons.code", contains("NO_TITLE"));

        assertEquals(Status.DRAFT, statusOf(uid));
        assertEquals(history, historyCount(uid), "no history entry of a refused change");
    }

    /** A title of white space only counts as none */
    @Test
    void aTitleOfWhiteSpaceOnlyIsNoTitle() {
        String uid = draft("   ", future());

        putStatus(uid, "VERIFIED")
                .statusCode(409)
                .body("refusals[0].reasons.code", contains("NO_TITLE"));

        assertEquals(Status.DRAFT, statusOf(uid));
    }

    /**
     * A VERIFIED message whose publish-from date is still ahead is refused PUBLISHED: 409 with SCHEDULED and the
     * date, and it stays VERIFIED without a number. Before the fix it was published at once, its date set to now.
     */
    @Test
    void aScheduledMessageIsRefusedPublishedWith409AndKeepsNoNumber() {
        String uid = verified("IT gate scheduled", future());

        Object date = putStatus(uid, "PUBLISHED")
                .statusCode(409)
                .body("detail", containsString("scheduled for publication"))
                .body("refusals.messageId", contains(uid))
                .body("refusals[0].reasons.code", contains("SCHEDULED"))
                .extract().path("refusals[0].reasons[0].date");

        assertEquals(future().toInstant(), instant(date), "the reason gives the publish-from date");
        assertEquals(Status.VERIFIED, statusOf(uid));
        assertNull(numberOf(uid), "no number is taken");
    }

    /** VERIFIED is how a message is scheduled: a titled draft with a publish-from date ahead may be verified */
    @Test
    void aScheduledDraftWithATitleMayBeVerified() {
        String uid = draft("IT gate verify scheduled", future());

        putStatus(uid, "VERIFIED")
                .statusCode(200)
                .body("status", is("VERIFIED"));

        assertEquals(Status.VERIFIED, statusOf(uid));
    }

    /** A titled VERIFIED message whose publish-from date has passed is published */
    @Test
    void aCompleteMessageIsPublished() {
        String uid = verified("IT gate publish", past());

        putStatus(uid, "PUBLISHED")
                .statusCode(200)
                .body("status", is("PUBLISHED"));

        assertEquals(Status.PUBLISHED, statusOf(uid));
    }

    /**
     * A transition {@code updateStatus} refuses keeps its own 409, whatever the message holds: DRAFT to PUBLISHED of
     * a draft without a title names the transition and carries no refusals.
     */
    @Test
    void anInvalidTransitionKeepsItsOwn409() {
        String uid = draft(null, future());

        putStatus(uid, "PUBLISHED")
                .statusCode(409)
                .body("detail", containsString("Invalid status transition DRAFT -> PUBLISHED"))
                .body("refusals", nullValue());

        assertEquals(Status.DRAFT, statusOf(uid));
    }

    /**
     * Only VERIFIED and PUBLISHED are gated, and {@code MessageService.updateStatus} is not: a title-less message
     * verified through the service goes back to DRAFT over REST.
     */
    @Test
    void theServiceAndTheOtherTargetsAreNotGated() {
        String uid = verified(null, future());
        assertEquals(Status.VERIFIED, statusOf(uid), "the service verifies a message without a title");

        putStatus(uid, "DRAFT")
                .statusCode(200)
                .body("status", is("DRAFT"));

        assertEquals(Status.DRAFT, statusOf(uid));
    }

    /**
     * A MANDATORY promulgation type that is not ticked refuses VERIFIED: 409 with MANDATORY_PROMULGATION_OFF and the
     * type's name. The same message with the type ticked is verified. The seed's NAVTEX type is made MANDATORY for
     * the case's run only.
     */
    @Test
    void aMandatoryPromulgationNotTickedIsRefusedAndOneTickedPasses() {
        String off = draft("IT gate navtex off", future());
        String on = draft("IT gate navtex on", future());
        Tx.requiringNew().run(() -> {
            Message m = messageQueryService.findByUid(on);
            NavtexMessagePromulgation navtex = new NavtexMessagePromulgation();
            navtex.setType(promulgationTypeService.getPromulgationType("navtex"));
            navtex.setPromulgate(true);
            navtex.setText("IT gate NAVTEX text");
            m.addPromulgation(navtex);
            em.persist(navtex);
        });

        PromulgationType.Requirement seeded = Tx.requiringNew().call(() ->
                promulgationTypeService.getPromulgationType("navtex").getRequirement());
        Tx.requiringNew().run(() -> promulgationTypeService.getPromulgationType("navtex")
                .setRequirement(PromulgationType.Requirement.MANDATORY));
        try {
            putStatus(off, "VERIFIED")
                    .statusCode(409)
                    .body("detail", containsString("its mandatory promulgation NAVTEX is not ticked"))
                    .body("refusals.messageId", contains(off))
                    .body("refusals[0].reasons.code", contains("MANDATORY_PROMULGATION_OFF"))
                    .body("refusals[0].reasons[0].types", contains("NAVTEX"));
            assertEquals(Status.DRAFT, statusOf(off));

            putStatus(on, "VERIFIED")
                    .statusCode(200)
                    .body("status", is("VERIFIED"));
            assertEquals(Status.VERIFIED, statusOf(on));
        } finally {
            Tx.requiringNew().run(() -> promulgationTypeService.getPromulgationType("navtex")
                    .setRequirement(seeded));
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The bulk endpoint
    // ------------------------------------------------------------------------------------------- //

    /**
     * The bulk endpoint asks every item before anything changes: [A without a title to VERIFIED, B complete to
     * VERIFIED, C scheduled to PUBLISHED] answers one 409 naming A and C with their reasons, in the order sent, and
     * no message is changed, B included.
     */
    @Test
    void aBulkChangeNamesEveryRefusedItemAndChangesNothing() {
        String a = draft(null, future());
        String b = draft("IT gate bulk B", future());
        String c = verified("IT gate bulk C", future());
        int historyB = historyCount(b);

        Object date = putStatuses(a, "VERIFIED", b, "VERIFIED", c, "PUBLISHED")
                .statusCode(409)
                .body("detail", containsString("No status was changed"))
                .body("detail", containsString(a))
                .body("detail", containsString(c))
                .body("refusals.messageId", contains(a, c))
                .body("refusals[0].reasons.code", contains("NO_TITLE"))
                .body("refusals[1].reasons.code", contains("SCHEDULED"))
                .extract().path("refusals[1].reasons[0].date");

        assertEquals(future().toInstant(), instant(date));
        assertEquals(Status.DRAFT, statusOf(a));
        assertEquals(Status.DRAFT, statusOf(b), "the item the gate let pass is not changed either");
        assertEquals(historyB, historyCount(b));
        assertEquals(Status.VERIFIED, statusOf(c));
        assertNull(numberOf(c));
    }

    /**
     * The bulk endpoint judges each item from the status the earlier items of the list give the message, not from
     * the stored one: [A to VERIFIED, A to PUBLISHED] of a titled draft whose publish-from date is ahead is a publish
     * of a scheduled message, refused with 409 naming A with SCHEDULED, and A stays DRAFT without a number. Before
     * the repair the second item was judged as DRAFT to PUBLISHED, which the gate does not ask about, and A was
     * published at once with a number of the current year; the current year's next number is given back in case
     * that happens again.
     */
    @Test
    void aBulkChangeNamingAMessageTwiceIsJudgedFromTheStatusTheListGivesIt() {
        String a = draft("IT gate bulk twice", future());
        int history = historyCount(a);
        int year = Tx.requiringNew().call(() ->
                messageSeriesService.currentYear(messageSeriesService.findBySeriesId("dma-nw")));
        Integer nextNumber = Tx.requiringNew().call(() -> messageSeriesService.getNextMessageNumber("dma-nw", year));
        try {
            Object date = putStatuses(a, "VERIFIED", a, "PUBLISHED")
                    .statusCode(409)
                    .body("detail", containsString("No status was changed"))
                    .body("refusals.messageId", contains(a))
                    .body("refusals[0].reasons.code", contains("SCHEDULED"))
                    .extract().path("refusals[0].reasons[0].date");

            assertEquals(future().toInstant(), instant(date));
            assertEquals(Status.DRAFT, statusOf(a));
            assertNull(numberOf(a), "no number is taken");
            assertEquals(history, historyCount(a), "no history entry of a refused change");
        } finally {
            Tx.requiringNew().run(() -> messageSeriesService.setNextMessageNumber("dma-nw", year, nextNumber));
        }
    }

    /**
     * A list that names a message twice and that the gate lets pass goes through as before: a complete VERIFIED
     * message sent as [A to DRAFT, A to VERIFIED] is judged DRAFT to VERIFIED for its second item and ends VERIFIED.
     */
    @Test
    void aBulkChangeNamingAMessageTwiceThatTheGateLetsPassGoesThrough() {
        String a = verified("IT gate bulk back and forth", future());

        putStatuses(a, "DRAFT", a, "VERIFIED")
                .statusCode(200)
                .body("status", contains("DRAFT", "VERIFIED"));

        assertEquals(Status.VERIFIED, statusOf(a));
    }

    /** A bulk change the gate lets pass goes through: a complete draft verified and a complete message published */
    @Test
    void aBulkChangeOfCompleteMessagesGoesThrough() {
        String a = draft("IT gate bulk verify", future());
        String b = verified("IT gate bulk publish", past());

        putStatuses(a, "VERIFIED", b, "PUBLISHED")
                .statusCode(200)
                .body("status", contains("VERIFIED", "PUBLISHED"));

        assertEquals(Status.VERIFIED, statusOf(a));
        assertEquals(Status.PUBLISHED, statusOf(b));
    }
}
