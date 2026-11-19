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
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessageHistory;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.message.MessageTag;
import org.niord.core.message.MessageTagService;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Integration tests for {@link MessageRestService}: the HTTP contract and, above all, the
 * authorization matrix of the {@code /rest/messages} endpoints.
 *
 * <p>The endpoints carry a mix of declarative security ({@code @PermitAll},
 * {@code @RolesAllowed(USER|EDITOR|ADMIN)}) and an imperative, domain-scoped access check
 * ({@code checkMessageEditingAccess}/{@code checkMessageViewingAccess}). The tests verify the full
 * chain:
 * <ul>
 *     <li>an unauthenticated caller is rejected with {@code 401} on every protected endpoint;</li>
 *     <li>a bearer token with the {@code user} role but no {@code NiordDomain} header is rejected
 *         with {@code 403} on {@code @RolesAllowed(USER)} endpoints (the current domain is needed to
 *         resolve viewing/editing access), and is admitted with {@code 200} once the
 *         {@code NiordDomain: niord-nw} header pins a domain that owns the message's series;
 *         </li>
 *     <li>a bare token (sysadmin, hence editor/admin) drives the editor/admin endpoints with no
 *         extra header;</li>
 *     <li>the {@code @PermitAll} public {@code getMessage} returns the message only when it is
 *         public (published/cancelled/expired), {@code 403} for a non-public draft - also one in a PUBLIC
 *         tag, which shares a draft with the user role of any domain only - and {@code 204} (null body)
 *         for an unknown id;</li>
 *     <li>the edit history of a message, public or not, is read from a domain that owns its series
 *         only;</li>
 *     <li>a one-time {@code ?ticket=} opens a draft only in the domain the ticket was issued for: a
 *         {@code NiordDomain} header naming another domain neither widens it nor, through the ticket
 *         endpoint, re-binds it;</li>
 *     <li>the {@code DRAFT -> VERIFIED -> PUBLISHED} status flow succeeds over REST while an illegal
 *         {@code DRAFT -> PUBLISHED} transition surfaces as a server error.</li>
 * </ul>
 *
 * <p>A real message id is required for the read/status paths. Because HTTP requests run in the
 * server's own transaction (which {@code @Transactional} cannot roll back), each test that needs a
 * message creates a COMMITTED row via {@link MessageService} (using the seeded {@code dma-nw} series,
 * which the {@code niord-nw} domain owns), records its uid, and removes it — together with the
 * non-cascaded {@link MessageHistory} rows — in {@link #cleanup()} inside a fresh
 * {@link Tx}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageRestServiceITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @PersistenceContext
    EntityManager em;

    @Autowired
    MessageTagService messageTagService;

    /** UIDs of messages created during a test; removed (with their history) in {@link #cleanup()}. */
    private final List<String> createdUids = new ArrayList<>();

    /** IDs of tags created during a test; removed in {@link #cleanup()} before the messages they hold. */
    private final List<String> createdTagIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String tagId : createdTagIds) {
            try {
                messageTagService.clearMessageTag(tagId);
                messageTagService.deleteMessageTag(tagId);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdTagIds.clear();

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

    /** Builds a detached draft message for the {@code dma-nw} series with the given NW type/title. */
    private Message newDraft(Type type, String title) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nw")); // createMessage substitutes the persisted one
        msg.setType(type);                                 // mainType is derived from the type
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(pastPublishDate());         // keep publishes off the current-year sequence

        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);

        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /** Creates a committed draft message and tracks its uid for cleanup. Returns the uid. */
    private String createCommittedDraft(String title) {
        String uid = Tx.requiringNew().call(() ->
                messageService.createMessage(newDraft(Type.COASTAL_WARNING, title)).getUid());
        createdUids.add(uid);
        return uid;
    }

    /**
     * Puts the committed message into a committed PUBLIC tag with no owner and no domain - the kind the
     * publication code creates - and tracks the tag for cleanup. Called outside a transaction, the
     * {@code @Transactional} service methods commit.
     */
    private void putInCommittedPublicTag(String uid) {
        MessageTag template = new MessageTag();
        template.setType(MessageTagType.PUBLIC);
        template.setName("IT-REST-Msg-PublicTag-" + System.nanoTime());
        MessageTag tag = messageTagService.createMessageTag(template);
        createdTagIds.add(tag.getTagId());
        messageTagService.addMessageToTag(tag.getTagId(), List.of(uid));
    }

    /** Creates a committed message and advances it to PUBLISHED (so it is publicly viewable). */
    private String createCommittedPublished(String title) {
        String uid = createCommittedDraft(title);
        // One committed transaction for VERIFIED -> PUBLISHED: the auto-publish job only sees committed
        // state, so the atomic walk never exposes a committed VERIFIED + past-date row for it to seize.
        Tx.requiringNew().run(() -> {
            messageService.updateStatus(uid, Status.VERIFIED);
            messageService.updateStatus(uid, Status.PUBLISHED);
        });
        return uid;
    }

    // ------------------------------------------------------------------------------------------- //
    // 401 — unauthenticated callers are rejected on every protected endpoint
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createMessageWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/rest/messages/message")
                .then().statusCode(401);
    }

    @Test
    void updateMessageWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body("{\"id\":\"some-uid\"}")
                .when().put("/rest/messages/message/some-uid")
                .then().statusCode(401);
    }

    @Test
    void updateStatusWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body("VERIFIED")
                .when().put("/rest/messages/message/some-uid/status")
                .then().statusCode(401);
    }

    @Test
    void recomputeAreaSortOrderWithoutTokenIsUnauthorized() {
        given().when().put("/rest/messages/recompute-area-sort-order")
                .then().statusCode(401);
    }

    @Test
    void changeAreaSortOrderWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body("{\"id\":\"a\",\"afterId\":\"b\"}")
                .when().put("/rest/messages/change-area-sort-order")
                .then().statusCode(401);
    }

    @Test
    void editableMessageWithoutTokenIsUnauthorized() {
        given().when().get("/rest/messages/editable-message/some-uid")
                .then().statusCode(401);
    }

    @Test
    void messageHistoryWithoutTokenIsUnauthorized() {
        given().when().get("/rest/messages/message/some-uid/history")
                .then().statusCode(401);
    }

    @Test
    void newMessageTemplateWithoutTokenIsUnauthorized() {
        given().queryParam("mainType", "NW")
                .when().get("/rest/messages/new-message-template")
                .then().statusCode(401);
    }

    @Test
    void recentlyEditedDraftsWithoutTokenIsUnauthorized() {
        given().when().get("/rest/messages/recently-edited-drafts")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // USER-role matrix on GET /editable-message/{id} (domain-scoped viewing access)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void editableMessageWithBareTokenButNoDomainIsForbidden() {
        String uid = createCommittedDraft("IT editable no-domain");

        // @RolesAllowed(USER) is satisfied via the domain header, which is absent here; the current
        // domain is null inside checkMessageEditingAccess, which rejects the request with 403.
        given().auth().oauth2(accessToken())
                .when().get("/rest/messages/editable-message/" + uid)
                .then().statusCode(403);
    }

    /**
     * WEB13. The two handlers that read the current domain themselves answer a call without a {@code NiordDomain}
     * header 403, as {@code checkMessageEditingAccess} does. A sysadmin's token passes their role check without the
     * header. FAILS on the pre-fix code, which answered 500 with a {@code NullPointerException} ("because "domain" is
     * null") and its stack in the log.
     */
    @Test
    void theHandlersThatReadTheDomainAnswer403WithoutADomainHeader() {
        given().auth().oauth2(accessToken("sysadmin"))
                .queryParam("mainType", "NW")
                .when().get("/rest/messages/new-message-template")
                .then().statusCode(403);
        given().auth().oauth2(accessToken("sysadmin"))
                .when().put("/rest/messages/recompute-area-sort-order")
                .then().statusCode(403);

        // Control: with the header the template is made
        given().auth().oauth2(accessToken("sysadmin"))
                .header("NiordDomain", "niord-nw")
                .queryParam("mainType", "NW")
                .when().get("/rest/messages/new-message-template")
                .then().statusCode(200)
                .body("mainType", is("NW"));
    }

    @Test
    void editableMessageWithBareTokenAndDomainReturnsTheMessage() {
        String uid = createCommittedDraft("IT editable with-domain");

        // niord-nw owns the dma-nw series, so the domain check passes and the system message
        // is returned. The VO id equals the message uid.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/editable-message/" + uid)
                .then().statusCode(200)
                .body("id", is(uid))
                .body("status", is("DRAFT"))
                .body("mainType", is("NW"));
    }

    @Test
    void editableMessageWithWrongDomainIsForbidden() {
        String uid = createCommittedDraft("IT editable wrong-domain");

        // niord-nm does not own the dma-nw series, so domain.containsMessageSeries() fails -> 403.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nm")
                .when().get("/rest/messages/editable-message/" + uid)
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // USER-role matrix on GET /message/{id}/history
    // ------------------------------------------------------------------------------------------- //

    @Test
    void messageHistoryWithBareTokenButNoDomainIsForbidden() {
        String uid = createCommittedDraft("IT history no-domain");

        // The bare sysadmin token passes @RolesAllowed(USER) on its base roles, but the history takes the
        // domain gate of an edit: without a NiordDomain there is no current domain, so the request is 403.
        given().auth().oauth2(accessToken())
                .when().get("/rest/messages/message/" + uid + "/history")
                .then().statusCode(403);
    }

    @Test
    void messageHistoryWithBareTokenAndDomainReturnsHistory() {
        String uid = createCommittedDraft("IT history with-domain");

        // createMessage records one MessageHistory row, so the history endpoint returns a non-empty
        // list once the user is admitted.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/message/" + uid + "/history")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    void messageHistoryWithWrongDomainIsForbidden() {
        String uid = createCommittedDraft("IT history wrong-domain");

        // SECAUD-7 (fixed): getMessageHistory runs the domain gate of getSystemMessage. niord-nm
        // carries the user role but does not own the dma-nw draft's series, so the edit history — which
        // used to leak to any USER who could resolve the UID — is rejected with 403.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nm")
                .when().get("/rest/messages/message/" + uid + "/history")
                .then().statusCode(403);
    }

    @Test
    void messageHistoryOfPublishedMessageWithWrongDomainIsForbidden() {
        String uid = createCommittedPublished("IT history published wrong-domain");

        // A published message is public, but its history is not: it holds the earlier draft versions and
        // the editors' names. niord-nm does not own the dma-nw series -> 403.
        // FAILS on the pre-fix code (the public status admitted any USER of any domain: 200).
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nm")
                .when().get("/rest/messages/message/" + uid + "/history")
                .then().statusCode(403);
    }

    @Test
    void messageHistoryOfPublishedMessageInOwningDomainReturnsIt() {
        String uid = createCommittedPublished("IT history published own-domain");

        // The owning domain keeps reading the history of its published messages.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/message/" + uid + "/history")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    void recentlyEditedDraftsWithBareTokenAndDomainReturnsOk() {
        // No User row is seeded, so getMostRecentlyEditedDrafts() returns an empty list, but the
        // endpoint itself is reachable (200) once the user/domain are resolved.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/recently-edited-drafts")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // EDITOR / ADMIN endpoints with a bare token (no extra header required)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void newMessageTemplateAsEditorReturnsDraftTemplate() {
        // newTemplateMessage(NW) yields a DRAFT, auto-title template; the current domain must own an
        // NW series, which niord-nw does (via the NiordDomain header).
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .queryParam("mainType", "NW")
                .when().get("/rest/messages/new-message-template")
                .then().statusCode(200)
                .body("mainType", is("NW"))
                .body("status", is("DRAFT"))
                .body("autoTitle", is(true));
    }

    @Test
    void newMessageTemplateForMainTypeNotInDomainIsForbidden() {
        // niord-nw owns only NW series, so requesting an NM template yields the explicit 403.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .queryParam("mainType", "NM")
                .when().get("/rest/messages/new-message-template")
                .then().statusCode(403);
    }

    @Test
    void recomputeAreaSortOrderAsAdminSucceeds() {
        // ADMIN-only endpoint; the bare sysadmin token is admitted and the void method returns 2xx.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().put("/rest/messages/recompute-area-sort-order")
                .then().statusCode(greaterThanOrEqualTo(200)).statusCode(lessThan(300));
    }

    @Test
    void changeAreaSortOrderWithMissingFieldsReturns400() {
        // EDITOR endpoint; a body without before/after ids triggers the explicit 400 in the resource.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("{\"id\":\"some-uid\"}")
                .when().put("/rest/messages/change-area-sort-order")
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // @PermitAll GET /message/{id} — public viewing access rules
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getPublishedMessageWithoutTokenReturnsIt() {
        String uid = createCommittedPublished("IT public published");

        // A published message is public, so the @PermitAll endpoint returns it with no token at all.
        given()
                .when().get("/rest/messages/message/" + uid)
                .then().statusCode(200)
                .body("id", is(uid))
                .body("status", is("PUBLISHED"))
                .body("mainType", is("NW"));
    }

    @Test
    void getDraftMessageWithoutTokenIsForbidden() {
        String uid = createCommittedDraft("IT public draft");

        // A draft is not public and there is no domain/tag granting access -> checkMessageViewingAccess
        // throws 403.
        given()
                .when().get("/rest/messages/message/" + uid)
                .then().statusCode(403);
    }

    @Test
    void getUnknownMessageReturnsNoContent() {
        // resolveMessage() returns null for an unknown id; getMessage returns null, which JAX-RS
        // serializes as an empty 204 No Content response (no 404).
        given()
                .when().get("/rest/messages/message/IT-no-such-message-" + System.nanoTime())
                .then().statusCode(204);
    }

    @Test
    void getDraftMessageInOwningDomainAsUserReturnsIt() {
        String uid = createCommittedDraft("IT draft in-domain");

        // Although the draft is not public, the current domain (niord-nw) owns its series and
        // the caller has the user role, so checkMessageViewingAccess grants access.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/message/" + uid)
                .then().statusCode(200)
                .body("id", is(uid))
                .body("status", is("DRAFT"));
    }

    @Test
    void getDraftInPublicTagWithoutTokenIsForbidden() {
        String uid = createCommittedDraft("IT draft public-tag anonymous");
        putInCommittedPublicTag(uid);

        // A PUBLIC tag shares a draft with staff; it does not publish it. Without the user role only the
        // public statuses are readable, tag or no tag.
        // FAILS on the pre-fix code (any PUBLIC tag opened the draft to everyone: 200).
        given()
                .when().get("/rest/messages/message/" + uid)
                .then().statusCode(403);

        // Naming the owning domain in the header does not stand in for the user role.
        given()
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/message/" + uid)
                .then().statusCode(403);
    }

    @Test
    void getDraftInPublicTagAsUserOfAnotherDomainReturnsIt() {
        String uid = createCommittedDraft("IT draft public-tag cross-domain");
        putInCommittedPublicTag(uid);

        // niord-nm does not own the dma-nw series, but the caller holds the user role there, so the
        // PUBLIC tag still shares the draft across domains (viewing-access rule 3).
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nm")
                .when().get("/rest/messages/message/" + uid)
                .then().statusCode(200)
                .body("id", is(uid))
                .body("status", is("DRAFT"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Ticket-authenticated GET /message/{id} — a ticket's roles count only in the ticket's domain
    // ------------------------------------------------------------------------------------------- //

    /**
     * Mints a one-time {@code role=user} ticket as the sysadmin, with the given domain in the
     * {@code NiordDomain} header: the ticket is bound to that domain, and 'user' was checked there.
     */
    private String userTicketFor(String domain) {
        return given().auth().oauth2(accessToken())
                .header("NiordDomain", domain)
                .queryParam("role", "user")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();
    }

    @Test
    void aUserTicketForTheOwningDomainOpensTheDraft() {
        String uid = createCommittedDraft("IT ticket own-domain");
        String ticket = userTicketFor("niord-nw");

        // The ticket is the only credential; its domain owns the draft's series and it carries 'user'.
        given()
                .queryParam("ticket", ticket)
                .when().get("/rest/messages/message/" + uid)
                .then().statusCode(200)
                .body("id", is(uid));
    }

    @Test
    void aUserTicketForAnotherDomainCannotOpenTheDraftThroughTheHeader() {
        String uid = createCommittedDraft("IT ticket header-mismatch");
        String ticket = userTicketFor("niord-nm");

        // 'user' was checked in niord-nm, which does not own dma-nw. Naming the owning domain in
        // the header must not pair the ticket's roles with that domain: the ticket's domain wins -> 403.
        given()
                .header("NiordDomain", "niord-nw")
                .queryParam("ticket", ticket)
                .when().get("/rest/messages/message/" + uid)
                .then().statusCode(403);
    }

    @Test
    void aTicketsOwnDomainWinsOverADifferentHeader() {
        String uid = createCommittedDraft("IT ticket header-ignored");
        String ticket = userTicketFor("niord-nw");

        // The converse: a header naming a domain that does not own the series changes nothing either.
        given()
                .header("NiordDomain", "niord-nm")
                .queryParam("ticket", ticket)
                .when().get("/rest/messages/message/" + uid)
                .then().statusCode(200)
                .body("id", is(uid));
    }

    @Test
    void aTicketCannotBeExchangedForOneBoundToTheHeadersDomain() {
        String uid = createCommittedDraft("IT ticket re-mint");
        String nmTicket = userTicketFor("niord-nm");

        // A ticket may be exchanged for a new one - it carries 'user', so the role check passes - but the
        // new ticket is bound to the presented ticket's domain, not to the domain the header names...
        String exchanged = given()
                .header("NiordDomain", "niord-nw")
                .queryParam("ticket", nmTicket)
                .queryParam("role", "user")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        // ...so it still cannot open a draft of the other domain.
        given()
                .queryParam("ticket", exchanged)
                .when().get("/rest/messages/message/" + uid)
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /message/{id}/status — status transitions over REST
    // ------------------------------------------------------------------------------------------- //

    @Test
    void publishFlowOverRestVerifiesThenPublishes() {
        String uid = createCommittedDraft("IT status publish-flow");

        // Between the two REST calls below the message sits committed at VERIFIED with a past
        // publishDateFrom — safe only because ITTest unschedules the auto-publish cron.

        // DRAFT -> VERIFIED
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("VERIFIED")
                .when().put("/rest/messages/message/" + uid + "/status")
                .then().statusCode(200)
                .body("status", is("VERIFIED"));

        // VERIFIED -> PUBLISHED
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("PUBLISHED")
                .when().put("/rest/messages/message/" + uid + "/status")
                .then().statusCode(200)
                .body("status", is("PUBLISHED"));

        // Verify the persisted state rather than only the response echo.
        Message reloaded = Tx.requiringNew().call(() -> messageQueryService.findByUid(uid));
        org.junit.jupiter.api.Assertions.assertEquals(Status.PUBLISHED, reloaded.getStatus(),
                "the published status must be committed to the database");
    }

    @Test
    void illegalStatusTransitionOverRestIsRefusedWith409() {
        String uid = createCommittedDraft("IT status illegal");

        // DRAFT -> PUBLISHED is not a valid transition; updateStatus refuses it with an IllegalStateException,
        // answered 409 (MSG5; it was a 500).
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("PUBLISHED")
                .when().put("/rest/messages/message/" + uid + "/status")
                .then().statusCode(409);

        // The status must be unchanged in the database.
        Message reloaded = Tx.requiringNew().call(() -> messageQueryService.findByUid(uid));
        org.junit.jupiter.api.Assertions.assertEquals(Status.DRAFT, reloaded.getStatus(),
                "a rejected transition must leave the message status untouched");
    }

    @Test
    void changeStatusWithBareTokenButNoDomainIsForbidden() {
        String uid = createCommittedDraft("IT status no-domain");

        // CHANGE_STATUS requires editing access, which needs a current domain; without the NiordDomain
        // header the domain is null and the request is rejected with 403 (not a 5xx transition error).
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body("VERIFIED")
                .when().put("/rest/messages/message/" + uid + "/status")
                .then().statusCode(403);

        // And the status is unchanged.
        Message reloaded = Tx.requiringNew().call(() -> messageQueryService.findByUid(uid));
        org.junit.jupiter.api.Assertions.assertEquals(Status.DRAFT, reloaded.getStatus());
    }
}
