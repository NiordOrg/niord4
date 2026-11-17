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
package org.niord.web;

import org.springframework.transaction.annotation.Transactional;
import org.niord.test.Tx;
import io.restassured.http.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessageHistory;
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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Integration tests for the batch / multi-message status endpoints of {@link MessageRestService},
 * complementing the single-message coverage in {@code MessageRestServiceITTest}.
 *
 * <p>The focus is on:
 * <ul>
 *     <li>{@code PUT /update-statuses}: the multi-message status update. The resource validates
 *         editing access for <em>every</em> id <b>before</b> performing <em>any</em> update
 *         (an access-check-first loop), so an unknown / wrong-domain id aborts the whole batch and
 *         leaves the otherwise-valid message untouched in the database. The list is one transaction: a
 *         refused transition later in the list answers 409 naming the message and rolls back every earlier
 *         item, and an item without a status is refused with 400 before any change (MSG5).</li>
 *     <li>{@code PUT /message/{id}/status?cancelReferencedMessages=true}: when a message that carries
 *         a {@code cancelsReferencedMessage()} reference to a published message is published, that
 *         referenced message is transitioned to {@code CANCELLED} while the publisher becomes
 *         {@code PUBLISHED}. A message referenced twice is cancelled once (MSG4).</li>
 *     <li>{@code PUT /message/{id}}: the path-id / body-id mismatch guard returns {@code 400} and
 *         mutates nothing.</li>
 *     <li>{@code PUT /change-area-sort-order}: a well-formed reorder of two real messages succeeds
 *         (2xx) and the targeted message's area sort order changes in the database.</li>
 * </ul>
 *
 * <p>As in the sibling test, HTTP requests run in the server's own transaction (which
 * {@code @Transactional} cannot roll back), so each test that needs a message creates a COMMITTED
 * row via {@link MessageService} using the seeded {@code dma-nw} series (owned by the
 * {@code niord-nw} domain), records its uid, and removes it — together with the non-cascaded
 * {@link MessageHistory} rows — in {@link #cleanup()} inside a fresh {@link Tx}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageRestStatusBatchITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @PersistenceContext
    EntityManager em;

    /** UIDs of messages created during a test; removed (with their history) in {@link #cleanup()}. */
    private final List<String> createdUids = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String uid : createdUids) {
            try {
                Tx.requiringNew().run(() -> {
                    Message msg = messageQueryService.findByUid(uid);
                    if (msg != null) {
                        // MessageHistory is not cascaded from Message, so delete those rows first.
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

    /**
     * A fixed PAST publish date. Publishing takes a message number from the YEARLY sequence of the series
     * inside the publishing transaction, and this suite's publishes commit (over REST, or in a committed
     * {@link Tx}), so their numbers stay taken; stamping a far-past date
     * keeps these publishes on the year-2001 sequence so they never pollute the current-year number
     * that {@code MessageSeriesRestServiceITTest} asserts is 1. {@code updateStatus} keeps a non-null,
     * non-future publish date.
     */
    private static Date pastPublishDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2001, java.util.Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /**
     * A fixed FUTURE publish date (year 2999, the idiom {@code ScheduledMessageServiceITTest} uses) for
     * fixtures this suite never publishes itself. {@code ScheduledMessageService.checkForPublishableMessages}
     * runs once a minute during the IT suite and system-publishes every committed VERIFIED message whose
     * {@code publishDateFrom} is in the past — which would flip a fixture out from under an assertion that
     * it is still VERIFIED. A future date is unreachable by that search. See the "background schedulers"
     * landmine in docs/test-authoring-guide.md.
     */
    private static Date futurePublishDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2999, java.util.Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /**
     * Builds a detached draft message for the {@code dma-nw} series with the given NW type/title.
     * The caller picks the publish date: {@link #pastPublishDate()} for fixtures that will be published
     * (keeping their numbers off the current-year sequence), {@link #futurePublishDate()} for fixtures
     * that must stay unpublished (keeping the auto-publish job away from them).
     */
    private Message newDraft(Type type, String title, Date publishFrom) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nw")); // createMessage substitutes the persisted one
        msg.setType(type);                                 // mainType is derived from the type
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(publishFrom);

        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);

        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /**
     * Creates a committed draft message and tracks its uid for cleanup. Returns the uid.
     * These fixtures are never published by this suite (they stay DRAFT, or are taken to VERIFIED over
     * REST and asserted there), so they get a future publish date and the auto-publish job cannot move them.
     */
    private String createCommittedDraft(String title) {
        return createCommittedDraft(title, futurePublishDate());
    }

    /** Creates a committed draft message with an explicit publish date. Returns the uid. */
    private String createCommittedDraft(String title, Date publishFrom) {
        String uid = Tx.requiringNew().call(() ->
                messageService.createMessage(newDraft(Type.COASTAL_WARNING, title, publishFrom)).getUid());
        createdUids.add(uid);
        return uid;
    }

    /** Creates a committed message and advances it to PUBLISHED (so it is publicly viewable). */
    private String createCommittedPublished(String title) {
        // Published here, so the past date applies: it keeps the minted number off the current-year sequence.
        String uid = createCommittedDraft(title, pastPublishDate());
        // One committed transaction for VERIFIED -> PUBLISHED: the auto-publish job only sees committed
        // state, so the atomic walk never exposes a committed VERIFIED + past-date row for it to seize.
        Tx.requiringNew().run(() -> {
            messageService.updateStatus(uid, Status.VERIFIED);
            messageService.updateStatus(uid, Status.PUBLISHED);
        });
        return uid;
    }

    /**
     * Creates a committed DRAFT message in the {@code dma-nw} series that carries a
     * {@code cancelsReferencedMessage()} reference ({@link ReferenceType#CANCELLATION}) to the
     * message with the given uid. Tracks the new uid for cleanup and returns it.
     */
    private String createCommittedDraftReferencing(String title, String referencedUid) {
        String uid = Tx.requiringNew().call(() -> {
            // This fixture is published over REST by its tests, so it takes the past date.
            Message msg = newDraft(Type.COASTAL_WARNING, title, pastPublishDate());
            Reference ref = new Reference();
            ref.setMessageId(referencedUid);
            ref.setType(ReferenceType.CANCELLATION); // a member of cancelsReferencedMessage()
            msg.addReference(ref);
            return messageService.createMessage(msg).getUid();
        });
        createdUids.add(uid);
        return uid;
    }

    /** Reloads a message's status from the database inside a fresh transaction. */
    private Status statusOf(String uid) {
        return Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getStatus());
    }

    /** Reloads a message's area sort order from the database inside a fresh transaction. */
    private double areaSortOrderOf(String uid) {
        return Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getAreaSortOrder());
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /update-statuses — happy path: a whole batch is updated
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateStatusesBatchVerifiesAllMessages() {
        String uid1 = createCommittedDraft("IT batch verify a");
        String uid2 = createCommittedDraft("IT batch verify b");

        // Both messages are DRAFT; the batch transitions both to VERIFIED in one call.
        String body = "[{\"messageId\":\"" + uid1 + "\",\"status\":\"VERIFIED\"},"
                + "{\"messageId\":\"" + uid2 + "\",\"status\":\"VERIFIED\"}]";

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/messages/update-statuses")
                .then().statusCode(200)
                .body("size()", is(2))
                .body("status", is(List.of("VERIFIED", "VERIFIED")));

        // Verify the persisted state of BOTH messages, not just the response echo.
        Assertions.assertEquals(Status.VERIFIED, statusOf(uid1),
                "first message in the batch must be committed as VERIFIED");
        Assertions.assertEquals(Status.VERIFIED, statusOf(uid2),
                "second message in the batch must be committed as VERIFIED");
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /update-statuses — access-check-first: an unknown id aborts the whole batch
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateStatusesBatchWithUnknownIdAbortsWholeBatchAndLeavesValidMessageUntouched() {
        String validUid = createCommittedDraft("IT batch abort valid");
        String unknownUid = "IT-no-such-message-" + System.nanoTime();

        // The resource validates editing access for EVERY id up front. findByUid returns null for the
        // unknown id, so checkMessageEditingAccess(null, ...) throws 403 before any update runs.
        String body = "[{\"messageId\":\"" + validUid + "\",\"status\":\"VERIFIED\"},"
                + "{\"messageId\":\"" + unknownUid + "\",\"status\":\"VERIFIED\"}]";

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/messages/update-statuses")
                .then().statusCode(403);

        // The valid message must be UNCHANGED — the access check ran before any updateStatus call.
        Assertions.assertEquals(Status.DRAFT, statusOf(validUid),
                "a batch that fails its up-front access check must not mutate any message");
    }

    // ------------------------------------------------------------------------------------------- //
    // MSG5: a refused item rolls the whole request back, and the answer says which
    // ------------------------------------------------------------------------------------------- //

    /** The number of history rows of a message. */
    private int historyCount(String uid) {
        return Tx.requiringNew().call(() -> em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                .setParameter("messageId", messageQueryService.findByUid(uid).getId())
                .getResultList()
                .size());
    }

    /**
     * MSG5. Three drafts, [B to VERIFIED, A to EXPIRED, C to VERIFIED]: A's transition is refused. The request is
     * one transaction, so B's change rolls back with it: 409 whose detail names A, all three still DRAFT, and no
     * history row added. Before the fix the answer was 500 and B was committed VERIFIED, with its history row.
     */
    @Test
    void aBulkChangeWithARefusedTransitionAnswers409AndChangesNothing() {
        String a = createCommittedDraft("IT bulk refused A");
        String b = createCommittedDraft("IT bulk refused B");
        String c = createCommittedDraft("IT bulk refused C");
        int historyA = historyCount(a);
        int historyB = historyCount(b);
        int historyC = historyCount(c);

        String body = "[{\"messageId\":\"" + b + "\",\"status\":\"VERIFIED\"},"
                + "{\"messageId\":\"" + a + "\",\"status\":\"EXPIRED\"},"
                + "{\"messageId\":\"" + c + "\",\"status\":\"VERIFIED\"}]";

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/messages/update-statuses")
                .then().statusCode(409)
                .body("detail", containsString("Invalid status transition DRAFT -> EXPIRED"))
                .body("detail", containsString(a));

        Assertions.assertEquals(Status.DRAFT, statusOf(a));
        Assertions.assertEquals(Status.DRAFT, statusOf(b), "B's change before the refused item is rolled back");
        Assertions.assertEquals(Status.DRAFT, statusOf(c));
        Assertions.assertEquals(historyA, historyCount(a));
        Assertions.assertEquals(historyB, historyCount(b), "no history row of B's rolled-back change");
        Assertions.assertEquals(historyC, historyCount(c));
    }

    /**
     * MSG5. A bulk item without a status is refused with 400 before any change, and the item before it is not
     * changed. Before the fix it was refused as "DRAFT -> null", a 500, after the earlier items were changed.
     */
    @Test
    void aBulkItemWithoutAStatusAnswers400AndChangesNothing() {
        String a = createCommittedDraft("IT bulk no status A");
        String b = createCommittedDraft("IT bulk no status B");

        String body = "[{\"messageId\":\"" + a + "\",\"status\":\"VERIFIED\"},"
                + "{\"messageId\":\"" + b + "\"}]";

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/messages/update-statuses")
                .then().statusCode(400)
                .body("detail", containsString(b));

        Assertions.assertEquals(Status.DRAFT, statusOf(a), "the item before the refused one is not changed");
        Assertions.assertEquals(Status.DRAFT, statusOf(b));
    }

    /**
     * MSG5. A single refused transition ({@code PUT .../message/{id}/status}, DRAFT to PUBLISHED) answers 409 whose
     * detail names the transition, and changes nothing. Before the fix it answered 500.
     */
    @Test
    void aRefusedSingleTransitionAnswers409() {
        String uid = createCommittedDraft("IT single refused");

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("PUBLISHED")
                .when().put("/rest/messages/message/" + uid + "/status")
                .then().statusCode(409)
                .body("detail", containsString("Invalid status transition DRAFT -> PUBLISHED"));

        Assertions.assertEquals(Status.DRAFT, statusOf(uid));
    }

    @Test
    void updateStatusesBatchWithoutDomainIsForbiddenAndChangesNothing() {
        String uid1 = createCommittedDraft("IT batch no-domain a");
        String uid2 = createCommittedDraft("IT batch no-domain b");

        // CHANGE_STATUS needs a current domain; without the NiordDomain header the domain is null and
        // checkMessageEditingAccess rejects the very first id with 403, before any update.
        String body = "[{\"messageId\":\"" + uid1 + "\",\"status\":\"VERIFIED\"},"
                + "{\"messageId\":\"" + uid2 + "\",\"status\":\"VERIFIED\"}]";

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/messages/update-statuses")
                .then().statusCode(403);

        Assertions.assertEquals(Status.DRAFT, statusOf(uid1));
        Assertions.assertEquals(Status.DRAFT, statusOf(uid2));
    }

    @Test
    void updateStatusesBatchWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body("[{\"messageId\":\"some-uid\",\"status\":\"VERIFIED\"}]")
                .when().put("/rest/messages/update-statuses")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /message/{id}/status?cancelReferencedMessages=true — cancels referenced published messages
    // ------------------------------------------------------------------------------------------- //

    @Test
    void publishWithCancelReferencedCancelsTheReferencedPublishedMessage() {
        // The referenced ("victim") message must already be PUBLISHED, since
        // getReferencedMessages(..., PUBLISHED) filters by that status.
        String victimUid = createCommittedPublished("IT cancel-ref victim");

        // The publisher references the victim with a CANCELLATION (a cancelsReferencedMessage type)
        // and is brought to VERIFIED so it can be PUBLISHED over REST. It sits committed at VERIFIED
        // with a past date until that call lands — safe only because ITTest unschedules the
        // auto-publish cron.
        String publisherUid = createCommittedDraftReferencing("IT cancel-ref publisher", victimUid);
        Tx.requiringNew().call(() -> messageService.updateStatus(publisherUid, Status.VERIFIED));

        // Publish the publisher with cancelReferencedMessages=true.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .queryParam("cancelReferencedMessages", true)
                .body("PUBLISHED")
                .when().put("/rest/messages/message/" + publisherUid + "/status")
                .then().statusCode(200)
                .body("status", is("PUBLISHED"));

        // The publisher is PUBLISHED and the referenced victim has been CANCELLED — verify both in DB.
        Assertions.assertEquals(Status.PUBLISHED, statusOf(publisherUid),
                "the publishing message must end up PUBLISHED");
        Assertions.assertEquals(Status.CANCELLED, statusOf(victimUid),
                "the cancels-referenced published message must be transitioned to CANCELLED");
    }

    /**
     * MSG4. A publisher that references the same published message twice, a CANCELLATION by its UID and an UPDATE by
     * its short id, published with the flag: the message is cancelled once, and the publish answers 200 with the
     * publisher PUBLISHED and the referenced message CANCELLED. Without removing the duplicate, the second
     * cancellation was refused as CANCELLED -> CANCELLED, and the request answered 409 and published nothing.
     */
    @Test
    void publishWithCancelReferencedCancelsAMessageReferencedTwiceOnce() {
        String victimUid = createCommittedPublished("IT cancel-ref twice victim");
        String victimShortId = Tx.requiringNew().call(() -> messageQueryService.findByUid(victimUid).getShortId());
        Assertions.assertNotNull(victimShortId, "a published dma-nw message has a short id");

        String publisherUid = Tx.requiringNew().call(() -> {
            Message msg = newDraft(Type.COASTAL_WARNING, "IT cancel-ref twice publisher", pastPublishDate());
            Reference byUid = new Reference();
            byUid.setMessageId(victimUid);
            byUid.setType(ReferenceType.CANCELLATION);
            msg.addReference(byUid);
            Reference byShortId = new Reference();
            byShortId.setMessageId(victimShortId);
            byShortId.setType(ReferenceType.UPDATE);
            msg.addReference(byShortId);
            return messageService.createMessage(msg).getUid();
        });
        createdUids.add(publisherUid);
        Tx.requiringNew().call(() -> messageService.updateStatus(publisherUid, Status.VERIFIED));

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .queryParam("cancelReferencedMessages", true)
                .body("PUBLISHED")
                .when().put("/rest/messages/message/" + publisherUid + "/status")
                .then().statusCode(200)
                .body("status", is("PUBLISHED"));

        Assertions.assertEquals(Status.PUBLISHED, statusOf(publisherUid));
        Assertions.assertEquals(Status.CANCELLED, statusOf(victimUid),
                "the message referenced twice is cancelled, once");
    }

    @Test
    void publishWithoutCancelReferencedLeavesReferencedMessagePublished() {
        // Same setup, but WITHOUT the cancelReferencedMessages flag — the victim must stay PUBLISHED.
        String victimUid = createCommittedPublished("IT no-cancel-ref victim");
        // Same committed-VERIFIED-until-the-REST-call window as the test above — same safety net.
        String publisherUid = createCommittedDraftReferencing("IT no-cancel-ref publisher", victimUid);
        Tx.requiringNew().call(() -> messageService.updateStatus(publisherUid, Status.VERIFIED));

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("PUBLISHED")
                .when().put("/rest/messages/message/" + publisherUid + "/status")
                .then().statusCode(200)
                .body("status", is("PUBLISHED"));

        Assertions.assertEquals(Status.PUBLISHED, statusOf(publisherUid));
        Assertions.assertEquals(Status.PUBLISHED, statusOf(victimUid),
                "without the cancel flag the referenced message must remain PUBLISHED");
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /message/{id} — path-id / body-id mismatch guard
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateMessageWithBodyIdDifferentFromPathIs400AndDoesNotMutate() {
        String uid = createCommittedDraft("IT update mismatch");

        // The body carries a DIFFERENT id than the path, so updateMessage throws 400 before any
        // persistence; the message's title/status must be unchanged. We deliberately set a DRAFT
        // status in the body to prove nothing was applied.
        String body = "{\"id\":\"some-other-id\",\"status\":\"VERIFIED\"}";

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/messages/message/" + uid)
                .then().statusCode(400);

        // The targeted message is untouched: still a DRAFT with its original title. The status is a
        // scalar, but getDesc("en") walks the lazy 'descs' collection, so we read BOTH inside a fresh
        // transaction (initializing the collection while the session is open) and return the scalars,
        // never a detached entity.
        Status reloadedStatus = Tx.requiringNew().call(() ->
                messageQueryService.findByUid(uid).getStatus());
        String reloadedTitle = Tx.requiringNew().call(() ->
                messageQueryService.findByUid(uid).getDesc("en").getTitle());
        Assertions.assertEquals(Status.DRAFT, reloadedStatus,
                "a path/body id mismatch must not mutate the targeted message");
        Assertions.assertEquals("IT update mismatch", reloadedTitle,
                "a path/body id mismatch must leave the title unchanged");
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /change-area-sort-order — valid reorder of two real messages
    // ------------------------------------------------------------------------------------------- //

    @Test
    void changeAreaSortOrderWithValidAfterIdSucceedsAndReorders() {
        String movedUid = createCommittedDraft("IT sort moved");
        String anchorUid = createCommittedDraft("IT sort anchor");

        double anchorSortOrder = areaSortOrderOf(anchorUid);

        // Moving 'moved' after 'anchor' sets its area sort order to anchorSortOrder + 1.
        String body = "{\"id\":\"" + movedUid + "\",\"afterId\":\"" + anchorUid + "\"}";

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/messages/change-area-sort-order")
                .then().statusCode(greaterThanOrEqualTo(200)).statusCode(lessThan(300));

        // Verify the persisted area sort order changed as specified by changeAreaSortOrder().
        Assertions.assertEquals(anchorSortOrder + 1, areaSortOrderOf(movedUid), 0.0001,
                "moving a message after another must set its area sort order to anchor + 1");
    }

    @Test
    void changeAreaSortOrderWithUnknownIdIsForbidden() {
        // SECAUD-8 (fixed): the reordered id no longer resolves to a message, so the new editing-access
        // check (checkMessageEditingAccess on the resolved id) rejects it with 403 instead of leaking a
        // 5xx from the unguarded service call.
        String body = "{\"id\":\"IT-no-such-" + System.nanoTime()
                + "\",\"afterId\":\"IT-no-such-after-" + System.nanoTime() + "\"}";

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/messages/change-area-sort-order")
                .then().statusCode(403);
    }

    @Test
    void changeAreaSortOrderWithoutDomainIsForbiddenAndChangesNothing() {
        // SECAUD-8 (fixed): reordering now requires editing access to the targeted message. Without a
        // NiordDomain header the current domain is null, so checkMessageEditingAccess rejects with 403
        // before the sort order is touched — an editor can no longer reorder a message outside its domain.
        String movedUid = createCommittedDraft("IT sort no-domain moved");
        String anchorUid = createCommittedDraft("IT sort no-domain anchor");
        double before = areaSortOrderOf(movedUid);

        String body = "{\"id\":\"" + movedUid + "\",\"afterId\":\"" + anchorUid + "\"}";

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/messages/change-area-sort-order")
                .then().statusCode(403);

        Assertions.assertEquals(before, areaSortOrderOf(movedUid), 0.0001,
                "a forbidden reorder must not mutate the message's area sort order");
    }
}
