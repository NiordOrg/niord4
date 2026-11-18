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
package org.niord.core.message;

import io.restassured.http.ContentType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.ReferenceType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.DeliveryFixtures;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.niord.test.xml.XmlAssert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the auto-created S-124 cancellation warnings (phase 2 of
 * {@code docs/s124-cancellation-plan.md}). Every editorial cancellation of a published NW warning
 * in an auto-numbered series (REST status update, single or bulk) mints exactly one new,
 * born-published CANCELLATION message numbered from the series sequence - there is no opt-out -
 * while all system cancel/expire paths (the publish-time {@code cancelReferencedMessages}
 * cascade, the expiry sweeper) never mint by construction.
 *
 * <p>REST-driven tests need committed rows (HTTP requests run in the server's own transaction),
 * so they create committed messages and remove them - minted cancellation warnings included -
 * in {@link #cleanup()}. The minted cancellation warning always takes the next number of the
 * <em>current</em> year (its publish date is "now"), and a REST test commits it; the tests therefore
 * capture the current-year sequence up front and restore it in {@link #cleanup()} (a test with a
 * transaction of its own takes the number inside it, which rolls it back), keeping
 * {@code MessageSeriesRestServiceITTest}'s "next number is 1" assertion (and the shared seed)
 * intact.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class MessageCancellationWarningITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @Autowired
    MessageQueryService messageQueryService;

    @Autowired
    ScheduledMessageService scheduledMessageService;

    @Autowired
    ApplicationContext context;

    @PersistenceContext
    EntityManager em;

    /** UIDs of source messages created during a test; removed (with any minted cancellation warnings) in cleanup */
    private final List<String> createdUids = new ArrayList<>();

    private int currentYear;
    private Integer nextNumberBefore;

    @BeforeEach
    void captureNumberSequence() {
        currentYear = Calendar.getInstance().get(Calendar.YEAR);
        // The minted cancellation warning takes the next current-year number; capture it for restore
        nextNumberBefore = Tx.requiringNew().call(() ->
                messageSeriesService.getNextMessageNumber("dma-nw", currentYear));
    }

    @AfterEach
    void cleanup() {
        // Delete the minted cancellation warnings first (they reference the sources), then the sources
        for (String uid : new ArrayList<>(createdUids)) {
            cancellationWarningUidsFor(uid).forEach(this::deleteMessage);
            deleteMessage(uid);
        }
        createdUids.clear();

        // A test with a transaction of its own took its numbers inside it, and they roll back with it. Its
        // transaction holds the number's row until then, so a restore in a transaction of its own would wait for
        // ever: only the committed (REST) tests restore the number.
        if (!TestTransaction.isActive()) {
            Tx.requiringNew().run(() ->
                    messageSeriesService.setNextMessageNumber("dma-nw", currentYear, nextNumberBefore));
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /**
     * A fixed PAST publish date (year 2001) for the source messages, so publishing them assigns
     * numbers off the year-2001 sequence and never pollutes the current-year sequence (the same
     * convention as the other message ITs; the minted cancellation warning is the one deliberate
     * exception, handled by the capture/restore in setup/cleanup).
     */
    private static Date pastPublishDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2001, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** Builds a detached draft message for the dma-nw series with one DETAILS part and an "en" title */
    private Message newDraft(String title) {
        return newDraft("dma-nw", title);
    }

    /** Builds a detached draft message for the given series with one DETAILS part and an "en" title */
    private Message newDraft(String seriesId, String title) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries(seriesId)); // createMessage substitutes the persisted one
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(pastPublishDate());
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /** Creates a committed PUBLISHED dma-nw message and tracks it for cleanup. Returns the uid. */
    private String createCommittedPublished(String title) {
        return createCommittedPublished("dma-nw", title);
    }

    /** Creates a committed PUBLISHED message in the given series and tracks it for cleanup. Returns the uid. */
    private String createCommittedPublished(String seriesId, String title) {
        String uid = Tx.requiringNew().call(() ->
                messageService.createMessage(newDraft(seriesId, title)).getUid());
        createdUids.add(uid);
        // One committed transaction for VERIFIED -> PUBLISHED: the auto-publish job only sees committed
        // state, so the atomic walk never exposes a committed VERIFIED + past-date row for it to seize.
        Tx.requiringNew().run(() -> {
            messageService.updateStatus(uid, Status.VERIFIED);
            messageService.updateStatus(uid, Status.PUBLISHED);
        });
        return uid;
    }

    /** In-transaction: the CANCELLATION-kind messages referencing the given message (uses the active tx) */
    private List<Message> mintedInTx(String uid) {
        return em.createQuery("select m from Message m join m.references r "
                        + "where m.kind = :kind and r.messageId = :uid", Message.class)
                .setParameter("kind", MessageKind.CANCELLATION)
                .setParameter("uid", uid)
                .getResultList();
    }

    /** The uids of committed CANCELLATION-kind messages referencing the given message */
    private List<String> cancellationWarningUidsFor(String uid) {
        return Tx.requiringNew().call(() ->
                em.createQuery("select m.uid from Message m join m.references r "
                                + "where m.kind = :kind and r.messageId = :uid", String.class)
                        .setParameter("kind", MessageKind.CANCELLATION)
                        .setParameter("uid", uid)
                        .getResultList());
    }

    /** Deletes a committed message (and its non-cascaded history rows) by uid */
    private void deleteMessage(String uid) {
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
            // best-effort cleanup
        }
    }

    /** Cancels the given message over REST as the sysadmin editor and asserts a 200 response */
    private void restCancel(String uid) {
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("CANCELLED")
                .when().put("/rest/messages/message/" + uid + "/status")
                .then().statusCode(200)
                .body("status", is("CANCELLED"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Editorial REST cancellation - minting
    // ------------------------------------------------------------------------------------------- //

    @Test
    void restCancelMintsExactlyOnePublishedCancellationWarning() {
        String uid = createCommittedPublished("IT cancellation-warning mint");
        String sourceShortId = Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getShortId());
        int expectedNumber = nextNumberBefore;

        restCancel(uid);

        // The source is cancelled...
        assertEquals(Status.CANCELLED,
                Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getStatus()));

        // ...and exactly one cancellation warning was minted, with the plan's field shape
        List<String> minted = cancellationWarningUidsFor(uid);
        assertEquals(1, minted.size(), "exactly one cancellation warning must be minted");

        Tx.requiringNew().run(() -> {
            Message c = messageQueryService.findByUid(minted.get(0));
            assertEquals(MessageKind.CANCELLATION, c.getKind());
            assertEquals(Status.PUBLISHED, c.getStatus(), "the cancellation warning is born published");
            assertEquals("dma-nw", c.getMessageSeries().getSeriesId());
            assertEquals(Type.COASTAL_WARNING, c.getType(), "the scope is copied from the source");

            // Numbered from the current-year series sequence, short ID from the series format
            assertEquals(expectedNumber, c.getNumber().intValue());
            assertEquals(String.format("NW-%03d-%02d", expectedNumber, currentYear % 100), c.getShortId());
            assertEquals(currentYear, c.year.intValue()); // package-visible; no public accessor

            assertNotNull(c.getPublishDateFrom());
            assertNull(c.getPublishDateTo(), "in force until manually retired");
            assertTrue(c.getParts().isEmpty(), "cancellation-only shape: no message parts");
            assertEquals(Boolean.TRUE, c.getOriginalInformation());
            assertFalse(c.isAutoTitle());

            // One CANCELLATION reference pointing at the source by UID
            assertEquals(1, c.getReferences().size());
            assertEquals(uid, c.getReferences().get(0).getMessageId());
            assertEquals(ReferenceType.CANCELLATION, c.getReferences().get(0).getType());

            // Explicit titles for both app languages, resolved from the message dictionary
            assertEquals("Cancellation of " + sourceShortId, c.getDesc("en").getTitle());
            assertEquals("Annullering af " + sourceShortId, c.getDesc("da").getTitle());

            // Editorial provenance: the cancelling user is recorded as the creator
            assertNotNull(c.getCreatedBy(), "the minted message must record the cancelling user");
        });
    }

    @Test
    void mintedCancellationWarningIsServedAsS124GmlAnonymously() {
        String uid = createCommittedPublished("IT cancellation-warning s124");
        String sourceShortId = Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getShortId());

        restCancel(uid);
        String cancellationUid = cancellationWarningUidsFor(uid).get(0);
        String cancellationShortId = Tx.requiringNew().call(() ->
                messageQueryService.findByUid(cancellationUid).getShortId());

        // The cancellation warning is an ordinary published NW: the public S-124 endpoint serves
        // its GML anonymously, by short ID (the spec's series + number + year identity)
        String gml = given()
                .when().get("/rest/S-124/messages/" + cancellationShortId)
                .then().statusCode(200)
                .extract().asString();

        XmlAssert.parse(gml)
                .assertRoot("Dataset")
                .assertRootAttr("id", cancellationShortId)
                .assertElementCount("References", 1)
                .assertNoElement("NavwarnPart")
                .assertNoElement("cancellationDate")
                .assertElementTextContains("navwarnTitle", "Cancellation of " + sourceShortId)
                .assertElementText("noMessageOnHand", "false");

        assertTrue(gml.contains("referenceCategory code=\"1\""),
                "the reference must carry referenceCategory 1 (Warning Cancellation)");
        // xx: the base product's neutral country, which the integration suite resolves
        assertTrue(gml.contains("urn:mrn:iho:nw:xx:" + sourceShortId.toLowerCase()),
                "the References feature must point at the cancelled warning's MRN");

        // The cancelled source itself also stays retrievable (public status), and its S-124 GML now
        // carries the in-band cancellationDate (S-124 Ed. 2.0.0 self-cancellation) stamped by the
        // cancellation - rendered from the source's publishDateTo, distinct from its 2001 publicationTime.
        String sourceGml = given().when().get("/rest/S-124/messages/" + uid)
                .then().statusCode(200)
                .extract().asString();

        Date cancelledAt = Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getPublishDateTo());
        assertNotNull(cancelledAt, "cancelling the source must stamp its publishDateTo");

        String renderedCancellationDate = XmlAssert.parse(sourceGml)
                .assertHasElement("cancellationDate")
                .firstText("cancellationDate");
        assertEquals(cancelledAt.toInstant().truncatedTo(ChronoUnit.SECONDS),
                OffsetDateTime.parse(renderedCancellationDate).toInstant().truncatedTo(ChronoUnit.SECONDS),
                "the source GML's cancellationDate must render the cancellation timestamp (publishDateTo)");
    }

    @Test
    void bulkStatusUpdateCancellationAlsoMints() {
        String uid = createCommittedPublished("IT cancellation-warning bulk");

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("[{\"messageId\":\"" + uid + "\",\"status\":\"CANCELLED\"}]")
                .when().put("/rest/messages/update-statuses")
                .then().statusCode(200)
                .body("[0].status", is("CANCELLED"));

        assertEquals(1, cancellationWarningUidsFor(uid).size(),
                "the bulk endpoint is an editorial cancel path and must mint too");
    }

    // ------------------------------------------------------------------------------------------- //
    // No minting for series that cannot number one / for non-editorial paths
    // ------------------------------------------------------------------------------------------- //

    @Test
    void cancellingInANonAutoNumberedSeriesMintsNothing() {
        // dma-nw-local is a seeded NW series with NONE numbering, so there is no next number for a
        // cancellation warning to take: the cancel goes through and simply mints nothing
        String uid = createCommittedPublished("dma-nw-local", "IT cancellation-warning unnumbered");
        restCancel(uid);

        assertEquals(Status.CANCELLED,
                Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getStatus()));
        assertTrue(cancellationWarningUidsFor(uid).isEmpty(),
                "a series that cannot number a cancellation warning must never mint");
    }

    @Test
    void cancelReferencedMessagesCascadeNeverMints() {
        // Method 3 of the spec: the message being published is itself the "new dataset with
        // cancellation" - auto-minting in the publish cascade would issue a duplicate cancellation
        // NW per cancelled reference, so the cascade goes through updateStatus, never cancelMessage
        String referencedUid = createCommittedPublished("IT cascade referenced");

        String cancellingUid = Tx.requiringNew().call(() -> {
            Message draft = newDraft("IT cascade cancelling");
            Reference reference = new Reference();
            reference.setMessageId(referencedUid);
            reference.setType(ReferenceType.CANCELLATION);
            draft.addReference(reference);
            return messageService.createMessage(draft).getUid();
        });
        createdUids.add(cancellingUid);
        // The cancelling message sits committed at VERIFIED (past-dated) until the REST call below
        // publishes it — safe only because ITTest unschedules the auto-publish cron.
        Tx.requiringNew().call(() -> messageService.updateStatus(cancellingUid, Status.VERIFIED));

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body("PUBLISHED")
                .when().put("/rest/messages/message/" + cancellingUid + "/status?cancelReferencedMessages=true")
                .then().statusCode(200)
                .body("status", is("PUBLISHED"));

        // The referenced message was cancelled by the cascade - but nothing was minted for it,
        // even though an editorial cancel of the same message would have minted
        assertEquals(Status.CANCELLED,
                Tx.requiringNew().call(() -> messageQueryService.findByUid(referencedUid).getStatus()));
        assertTrue(cancellationWarningUidsFor(referencedUid).isEmpty(),
                "the publish-time cancellation cascade must never mint cancellation warnings");
    }

    @Test
    void expirySweeperNeverMints() {
        // Expiry is the spec's method 1 (self-cancellation via the in-band cancellationDate);
        // the sweeper goes through updateStatus(EXPIRED) and must never mint - even though an
        // editorial cancel would. The sweeper expires each message in a transaction of its own
        // (SCHED2), so the fixture is committed, in an auto-numbered NW series and a domain of its
        // own (removed by the fixtures), and the sweep is narrowed to that domain.
        DeliveryFixtures fixtures = new DeliveryFixtures(context, em);
        try {
            String n = DeliveryFixtures.uniq();
            String series = fixtures.createYearlyNwSeries("it-mcw-" + n, "MCW");
            String domain = fixtures.createDomain("it-mcw-d-" + n, series);
            String uid = fixtures.published(series, "IT sweeper no-mint",
                    new Date(System.currentTimeMillis() - 3600_000L));
            assertTrue(Tx.requiringNew().call(() ->
                            messageService.willIssueCancellationWarning(messageQueryService.findByUid(uid))),
                    "precondition: an editorial cancel of this message would mint");

            assertTrue(scheduledMessageService.expireDueMessages(Set.of(domain)).isEmpty(), "no expiry failed");

            assertEquals(Status.EXPIRED, fixtures.statusOf(uid), "the sweeper must expire the overdue message");
            assertTrue(cancellationWarningUidsFor(uid).isEmpty(),
                    "the expiry sweeper must never mint cancellation warnings");
        } finally {
            fixtures.removeAll();
        }
    }

    @Test
    void anonymousCancellationIsRejected() {
        String uid = createCommittedPublished("IT cancellation-warning anon");

        given().contentType(ContentType.JSON)
                .body("CANCELLED")
                .when().put("/rest/messages/message/" + uid + "/status")
                .then().statusCode(401);

        assertEquals(Status.PUBLISHED,
                Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getStatus()));
        assertTrue(cancellationWarningUidsFor(uid).isEmpty());
    }

    // ------------------------------------------------------------------------------------------- //
    // Editor payload - the autoCancellationWarning announcement (phase 4)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void editableMessageAnnouncesTheAutoCancellationWarning() {
        String uid = createCommittedPublished("IT cancellation-warning editor note");

        // Auto-numbered NW series: the editor payload announces that cancelling will mint, so the
        // cancel dialog can show its "a cancellation warning will be issued" note
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/editable-message/" + uid)
                .then().statusCode(200)
                .body("kind", is("WARNING"))
                .body("autoCancellationWarning", is(true));

        // A series that cannot number a cancellation warning: no announcement
        String unnumberedUid = createCommittedPublished("dma-nw-local", "IT cancellation-warning no note");
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/editable-message/" + unnumberedUid)
                .then().statusCode(200)
                .body("autoCancellationWarning", is(false));
    }

    @Test
    void editableCancellationWarningItselfIsNotAnnounced() {
        // The minted CANCELLATION message never announces a further auto-cancellation
        // (terminal rule), even though it sits in the same auto-numbered NW series
        String uid = createCommittedPublished("IT cancellation-warning terminal note");
        restCancel(uid);
        String cancellationUid = cancellationWarningUidsFor(uid).get(0);

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/editable-message/" + cancellationUid)
                .then().statusCode(200)
                .body("kind", is("CANCELLATION"))
                .body("autoCancellationWarning", is(false));
    }

    @Test
    void copyOfACancellationWarningIsAnOrdinaryDraftWarning() {
        // A copy is the start of a new editorial message, which the editor saves as a WARNING;
        // a copy that kept the CANCELLATION kind could never be saved
        String uid = createCommittedPublished("IT cancellation-warning copy");
        restCancel(uid);
        String cancellationUid = cancellationWarningUidsFor(uid).get(0);

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/copy-message-template/" + cancellationUid)
                .then().statusCode(200)
                .body("kind", is("WARNING"))
                .body("status", is("DRAFT"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Terminal rule - cancelling a cancellation warning itself (driven through cancelMessage)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void cancelMessageOnACancellationWarningIsAllowedButMintsNothing() throws Exception {
        // Publish a source and cancel it via the service, minting one born-published cancellation warning.
        Message draft = messageService.createMessage(newDraft("IT terminal-rule source"));
        messageService.updateStatus(draft.getUid(), Status.VERIFIED);
        messageService.updateStatus(draft.getUid(), Status.PUBLISHED);

        Message cancelledSource = messageService.cancelMessage(draft.getUid());
        assertEquals(Status.CANCELLED, cancelledSource.getStatus());

        List<Message> minted = mintedInTx(draft.getUid());
        assertEquals(1, minted.size(), "cancelling the source mints exactly one cancellation warning");
        Message cancellationWarning = minted.get(0);
        assertEquals(MessageKind.CANCELLATION, cancellationWarning.getKind());
        assertEquals(Status.PUBLISHED, cancellationWarning.getStatus(), "the cancellation warning is born published");

        // The terminal rule holds at the predicate: a CANCELLATION message never begets another, even
        // though it sits in the same auto-numbered NW series. (Proven here to establish the setup is
        // non-trivial: were the predicate true, cancelMessage below would mint.)
        assertFalse(messageService.willIssueCancellationWarning(cancellationWarning),
                "the terminal rule: willIssueCancellationWarning must be false for a CANCELLATION message");

        // Cancelling the cancellation warning ITSELF is a valid status change (PUBLISHED -> CANCELLED):
        // status changes stay allowed on a CANCELLATION message (the read-only kind guard applies to
        // content edits, not status transitions).
        Message cancelledWarning = messageService.cancelMessage(cancellationWarning.getUid());
        assertEquals(Status.CANCELLED, cancelledWarning.getStatus(),
                "status changes stay allowed on a CANCELLATION message");

        // Driven through cancelMessage - not merely the pure predicate - the terminal rule mints nothing.
        assertTrue(mintedInTx(cancellationWarning.getUid()).isEmpty(),
                "cancelling a cancellation warning must mint no further cancellation warning (terminal rule)");
        assertEquals(1, mintedInTx(draft.getUid()).size(),
                "the source keeps exactly its one original cancellation warning - no extra was minted");
    }

    @Test
    void restCancellingAMintedCancellationWarningIsTerminalAndAllowed() {
        String uid = createCommittedPublished("IT terminal-rule rest source");
        restCancel(uid); // cancels the source, minting one cancellation warning

        List<String> minted = cancellationWarningUidsFor(uid);
        assertEquals(1, minted.size(), "cancelling the source over REST mints one cancellation warning");
        String cancellationUid = minted.get(0);

        // Cancelling the minted CANCELLATION message over the same editorial REST status path returns 200
        // and CANCELLED - status changes stay allowed on a CANCELLATION message (restCancel asserts both).
        restCancel(cancellationUid);
        assertEquals(Status.CANCELLED,
                Tx.requiringNew().call(() -> messageQueryService.findByUid(cancellationUid).getStatus()),
                "status changes stay allowed on a CANCELLATION message");

        // Routed through cancelMessage() the terminal rule mints nothing further.
        assertTrue(cancellationWarningUidsFor(cancellationUid).isEmpty(),
                "cancelling a cancellation warning over REST must not mint another (terminal rule)");
        assertEquals(1, cancellationWarningUidsFor(uid).size(),
                "the source keeps exactly its one original cancellation warning");
    }
}
