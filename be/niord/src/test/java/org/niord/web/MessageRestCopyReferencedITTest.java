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
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.commons.lang3.StringUtils;
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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for the copy/reference paths of {@link MessageRestService}: the
 * {@code GET /rest/messages/copy-message-template/{id}} and
 * {@code GET /rest/messages/referenced-messages/{id}} endpoints.
 *
 * <p>Both endpoints are {@code @RolesAllowed(EDITOR)} and resolve the current domain
 * ({@code domainService.currentDomain()}), so the tests drive them with the seeded {@code testeditor}
 * identity plus a {@code NiordDomain: niord-nw} header (the domain that owns the {@code dma-nw}
 * series). The verified behaviour is:
 * <ul>
 *     <li><b>copy-message-template</b> turns any source message into a fresh DRAFT template: a brand new
 *         id is assigned, the {@code shortId}/{@code number}/publish dates are reset, and the status is
 *         forced to {@code DRAFT}. When a {@code referenceType} query param is supplied, a single
 *         reference of that type pointing back at the source (its shortId, or uid when it has no
 *         shortId) is added to the template; without it, no reference is added.</li>
 *     <li>copying an <em>unknown</em> message resolves to {@code null}, which JAX-RS serialises as an
 *         empty {@code 204 No Content}.</li>
 *     <li><b>referenced-messages</b> returns the value objects of the messages a given source message
 *         references; an unknown source yields a {@code 200} empty list rather than an error.</li>
 *     <li>both endpoints reject an unauthenticated caller with {@code 401}.</li>
 * </ul>
 *
 * <p>HTTP requests run in the server's own transaction, which {@code @Transactional} cannot roll
 * back, so every test that needs persisted data creates COMMITTED rows via {@link MessageService}
 * (using the seeded {@code dma-nw} series, owned by {@code niord-nw}), records their uids, and
 * removes them — together with their non-cascaded {@link MessageHistory} rows — in {@link #cleanup()}
 * inside a fresh {@link Tx}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageRestCopyReferencedITTest extends ITTest {

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
    // Helpers (mirrored from MessageRestServiceITTest)
    // ------------------------------------------------------------------------------------------- //

    /**
     * A fixed PAST publish date. Publishing takes a message number from the YEARLY sequence of the series
     * inside the publishing transaction, and a publish here commits (in a committed {@link Tx}), so its
     * number stays taken; stamping a far-past date
     * keeps these publishes on the year-2001 sequence so they never pollute the current-year number
     * that other suites assert is 1.
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

    /**
     * Creates a committed draft that holds a single committed REFERENCE pointing at the given target
     * message (by uid). Returns the source uid.
     */
    private String createCommittedDraftReferencing(String title, String targetUid, ReferenceType type) {
        String uid = Tx.requiringNew().call(() -> {
            Message source = messageService.createMessage(newDraft(Type.COASTAL_WARNING, title));
            Reference ref = new Reference();
            ref.setMessageId(targetUid);
            ref.setType(type);
            source.addReference(ref);
            messageService.saveMessage(source);
            return source.getUid();
        });
        createdUids.add(uid);
        return uid;
    }

    /**
     * Creates a committed source that references the given target and is advanced to PUBLISHED, so the
     * source itself is publicly viewable (passes the source-access check in {@code getReferencedMessages})
     * while the target's viewability is whatever the target's own status/domain/tag dictate. Returns the
     * source uid.
     */
    private String createCommittedPublishedReferencing(String title, String targetUid, ReferenceType type) {
        String uid = createCommittedDraftReferencing(title, targetUid, type);
        // Atomic VERIFIED -> PUBLISHED, for the same reason as createCommittedPublished above.
        Tx.requiringNew().run(() -> {
            messageService.updateStatus(uid, Status.VERIFIED);
            messageService.updateStatus(uid, Status.PUBLISHED);
        });
        return uid;
    }

    /** Reloads a message and returns the reference messageId the copy endpoint would back-reference. */
    private String expectedBackReference(String uid) {
        return Tx.requiringNew().call(() -> {
            Message msg = messageQueryService.findByUid(uid);
            return StringUtils.isNotBlank(msg.getShortId()) ? msg.getShortId() : msg.getUid();
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /copy-message-template/{id} — turns a source message into a DRAFT template
    // ------------------------------------------------------------------------------------------- //

    @Test
    void copyPublishedWithRepetitionReferenceReturnsDraftTemplateWithBackReference() {
        String sourceUid = createCommittedPublished("IT copy repetition source");

        // The back-reference uses the published message's shortId when present, else its uid.
        String expectedRef = expectedBackReference(sourceUid);

        given().auth().oauth2(accessToken("editor"))
                .header("NiordDomain", "niord-nw")
                .queryParam("referenceType", "REPETITION")
                .when().get("/rest/messages/copy-message-template/" + sourceUid)
                .then().statusCode(200)
                // a brand-new template, not the source itself
                .body("id", notNullValue())
                .body("id", not(is(sourceUid)))
                // copy is reset to an unpublished DRAFT
                .body("status", is("DRAFT"))
                .body("mainType", is("NW"))
                .body("shortId", nullValue())
                .body("number", nullValue())
                // a single REPETITION reference back to the source
                .body("references.size()", is(1))
                .body("references[0].type", is("REPETITION"))
                .body("references[0].messageId", is(expectedRef));
    }

    @Test
    void copyDraftWithoutReferenceTypeReturnsDraftTemplateWithNoReference() {
        String sourceUid = createCommittedDraft("IT copy no-ref source");

        given().auth().oauth2(accessToken("editor"))
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/copy-message-template/" + sourceUid)
                .then().statusCode(200)
                .body("id", notNullValue())
                .body("id", not(is(sourceUid)))
                .body("status", is("DRAFT"))
                .body("mainType", is("NW"))
                .body("shortId", nullValue())
                .body("number", nullValue())
                // no referenceType query param => no back-reference is added
                .body("references", nullValue());
    }

    @Test
    void copyUnknownMessageReturnsNoContent() {
        // resolveMessage() returns null for an unknown id; copyMessageTemplate returns null, which
        // JAX-RS serialises as an empty 204 No Content response (not a 404).
        given().auth().oauth2(accessToken("editor"))
                .header("NiordDomain", "niord-nw")
                .queryParam("referenceType", "REPETITION")
                .when().get("/rest/messages/copy-message-template/IT-no-such-message-" + System.nanoTime())
                .then().statusCode(204);
    }

    @Test
    void copyMessageTemplateWithoutTokenIsUnauthorized() {
        // @RolesAllowed(EDITOR): an unauthenticated caller is rejected before any resolution.
        given()
                .when().get("/rest/messages/copy-message-template/some-uid")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /referenced-messages/{id} — value objects of the messages a source references
    // ------------------------------------------------------------------------------------------- //

    @Test
    void referencedMessagesReturnsTheReferencedMessage() {
        // A published target so it is publicly viewable, referenced by a committed source draft.
        String targetUid = createCommittedPublished("IT referenced target");
        String sourceUid = createCommittedDraftReferencing(
                "IT referencing source", targetUid, ReferenceType.REFERENCE);

        given().auth().oauth2(accessToken("editor"))
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/referenced-messages/" + sourceUid)
                .then().statusCode(200)
                // exactly one referenced message, and it is the target we pointed at
                .body("size()", is(1))
                .body("[0].id", is(targetUid))
                .body("[0].status", is("PUBLISHED"));
    }

    @Test
    void referencedMessagesForUnknownMessageReturnsEmptyList() {
        // resolveMessage() returns null for an unknown id; the endpoint returns an empty list (200),
        // not a 204/404/error.
        given().auth().oauth2(accessToken("editor"))
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/referenced-messages/IT-no-such-message-" + System.nanoTime())
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void referencedMessagesWithoutTokenIsUnauthorized() {
        // @RolesAllowed(EDITOR): an unauthenticated caller is rejected with 401.
        given()
                .when().get("/rest/messages/referenced-messages/some-uid")
                .then().statusCode(401);
    }

    /**
     * Verifies that {@code getReferencedMessages} drops any
     * referenced message the caller cannot view. Its stream filter now checks each iterated referenced
     * message ({@code messageViewingAccess(msg)}) rather than the already-validated <em>source</em> message
     * (the former {@code messageViewingAccess(message)} that made the predicate constant {@code true} and
     * the access filter a no-op) — see {@code MessageRestService.java}.
     *
     * <p>Scenario: a PUBLISHED source (publicly viewable, so the source-access check passes) references a
     * DRAFT target. The request omits the {@code NiordDomain} header, so {@code domainService.currentDomain()}
     * is {@code null} (DomainService.java:64-82) and the DRAFT target fails every branch of
     * {@code checkMessageViewingAccess} (not public, no domain match, no PUBLIC tag) — i.e. this editor
     * genuinely has no viewing access to it.
     *
     * <p>Correct behaviour: the non-viewable draft is excluded, so the returned list is empty. (Before the
     * MR1 fix this test pinned the buggy {@code size()==1} leak; it now asserts {@code size()==0}.)
     */
    @Test
    void referencedMessagesFiltersOutNonViewableReferencedMessages() {
        // A DRAFT target: not public, and (no NiordDomain header below) in no resolvable domain => not viewable.
        String hiddenDraftUid = createCommittedDraft("IT non-viewable referenced draft");
        // A PUBLISHED source that references it: publicly viewable, so the source-access check passes.
        String sourceUid = createCommittedPublishedReferencing(
                "IT published source referencing a hidden draft", hiddenDraftUid, ReferenceType.REFERENCE);

        given().auth().oauth2(accessToken("editor"))
                // deliberately NO NiordDomain header => currentDomain() is null => draft is not viewable
                .when().get("/rest/messages/referenced-messages/" + sourceUid)
                .then().statusCode(200)
                // MR1 fix: the per-reference access filter excludes the non-viewable DRAFT target, so the
                // editor (who has no viewing right to it) gets an empty list rather than the leaked draft.
                .body("size()", is(0));
    }
}
