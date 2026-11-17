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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the {@link MessageKind} discriminator introduced by the S-124
 * cancellation-warning work (phase 1, see {@code docs/s124-cancellation-plan.md}), covering:
 * <ul>
 *     <li>persistence round-trip of the kind column and its WARNING default (incl. seeded rows
 *         backfilled by the Liquibase {@code V005__message_kind} DEFAULT)</li>
 *     <li>{@code createMessage} validation: system-managed kinds are NW-only</li>
 *     <li>the read-only guard: content edits of non-WARNING messages are rejected both at the
 *         service layer and with a 400 at the REST layer</li>
 * </ul>
 *
 * The tests create committed messages (no surrounding test transaction, so the rows are visible
 * to the REST layer) and clean up after themselves by uid.
 */
class MessageKindITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Builds a detached, un-persisted draft message template with one DETAILS part and an "en" title. */
    private Message newDraft(String seriesId, Type type, MessageKind kind, String title) {
        Message msg = new Message();
        MessageSeries series = new MessageSeries(seriesId); // createMessage substitutes the persisted one
        series.setMainType(type.getMainType());
        msg.setMessageSeries(series);
        msg.setType(type);                                  // mainType derived from type
        msg.setKind(kind);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /** Reloads the kind of a message by uid in a fresh transaction (bypasses any first-level cache). */
    private MessageKind loadKind(String uid) {
        return Tx.requiringNew().call(() ->
                em.createQuery("select m.kind from Message m where m.uid = :uid", MessageKind.class)
                        .setParameter("uid", uid)
                        .getSingleResult());
    }

    /** Deletes a committed test message (and its history rows) by uid. */
    private void deleteMessage(String uid) {
        Tx.requiringNew().run(() -> {
            em.createQuery("delete from MessageHistory mh where mh.message.uid = :uid")
                    .setParameter("uid", uid).executeUpdate();
            Object m = em.createQuery("select m from Message m where m.uid = :uid")
                    .setParameter("uid", uid).getResultStream().findFirst().orElse(null);
            if (m != null) {
                em.remove(m);
            }
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // Persistence
    // ------------------------------------------------------------------------------------------- //

    @Test
    void kindDefaultsToWarningAndIsPersisted() throws Exception {
        Message warning = messageService.createMessage(
                newDraft("dma-nw", Type.COASTAL_WARNING, null, "Kind default test"));
        Message cancellation = messageService.createMessage(
                newDraft("dma-nw", Type.COASTAL_WARNING, MessageKind.CANCELLATION, "Kind cancellation test"));
        try {
            // createMessage defaults a null kind to WARNING, and both values survive a DB round-trip
            assertEquals(MessageKind.WARNING, loadKind(warning.getUid()));
            assertEquals(MessageKind.CANCELLATION, loadKind(cancellation.getUid()));
        } finally {
            deleteMessage(warning.getUid());
            deleteMessage(cancellation.getUid());
        }
    }

    @Test
    void kindColumnHasWarningDefaultAndIsNotNull() {
        // Pins the V005 Liquibase migration: rows inserted without the column (pre-existing
        // deployments, the pg_dump seed) must be backfilled to WARNING, and null is rejected
        Object[] column = Tx.requiringNew().call(() ->
                (Object[]) em.createNativeQuery(
                                "select column_default, is_nullable from information_schema.columns "
                                        + "where table_name = 'Message' and column_name = 'kind'")
                        .getSingleResult());
        assertNotNull(column[0], "the kind column must declare a default");
        assertTrue(column[0].toString().contains("WARNING"), "unexpected default: " + column[0]);
        assertEquals("NO", column[1], "the kind column must be NOT NULL");
    }

    // ------------------------------------------------------------------------------------------- //
    // createMessage validation
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createMessageRejectsNonWarningKindForNmMessages() {
        Message nmCancellation = newDraft("dma-nm", Type.TEMPORARY_NOTICE, MessageKind.CANCELLATION, "Invalid kind");

        Exception ex = assertThrows(Exception.class, () -> messageService.createMessage(nmCancellation));
        assertTrue(ex.getMessage().contains("only valid for NW messages"),
                "unexpected error message: " + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // Read-only guard
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateMessageServiceRejectsEditsOfCancellationMessages() throws Exception {
        Message cancellation = messageService.createMessage(
                newDraft("dma-nw", Type.COASTAL_WARNING, MessageKind.CANCELLATION, "Read-only test"));
        try {
            Message edit = newDraft("dma-nw", Type.COASTAL_WARNING, MessageKind.CANCELLATION, "Edited title");
            edit.setUid(cancellation.getUid());
            edit.setStatus(Status.DRAFT);

            Exception ex = assertThrows(Exception.class, () -> messageService.updateMessage(edit));
            assertTrue(ex.getMessage().contains("cannot be edited"),
                    "unexpected error message: " + ex.getMessage());
        } finally {
            deleteMessage(cancellation.getUid());
        }
    }

    @Test
    void restCreateWithSystemManagedKindIsRejectedWith400() {
        // System-managed kinds are created by Niord itself (phase 2 auto-creation), never via the API
        String uid = org.niord.core.util.UidUtils.newUid();
        Map<String, Object> body = draftUpdateBody(uid);
        body.put("kind", "CANCELLATION");
        body.put("repoPath", org.niord.core.util.UidUtils.uidToHashedFolderPath("messages", uid));

        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(body)
                .when().post("/rest/messages/message")
                .then().statusCode(400);

        // Nothing was persisted
        Long count = Tx.requiringNew().call(() ->
                em.createQuery("select count(m) from Message m where m.uid = :uid", Long.class)
                        .setParameter("uid", uid)
                        .getSingleResult());
        assertEquals(0L, count);
    }

    @Test
    void restUpdateOfCancellationMessageIsRejectedWith400() throws Exception {
        Message cancellation = messageService.createMessage(
                newDraft("dma-nw", Type.COASTAL_WARNING, MessageKind.CANCELLATION, "REST read-only test"));
        String uid = cancellation.getUid();
        try {
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType("application/json")
                    .body(draftUpdateBody(uid))
                    .when().put("/rest/messages/message/" + uid)
                    .then().statusCode(400);

            // The edit was rejected before reaching the service - the persisted title is unchanged
            String title = Tx.requiringNew().call(() ->
                    em.createQuery("select d.title from MessageDesc d where d.entity.uid = :uid and d.lang = 'en'",
                                    String.class)
                            .setParameter("uid", uid)
                            .getSingleResult());
            assertEquals("REST read-only test", title);
        } finally {
            deleteMessage(uid);
        }
    }

    /** A minimal, otherwise-valid SystemMessageVo update body attempting to change the title. */
    private Map<String, Object> draftUpdateBody(String uid) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("id", uid);
        msg.put("revision", 1);
        msg.put("mainType", "NW");
        msg.put("type", "COASTAL_WARNING");
        msg.put("status", "DRAFT");
        msg.put("autoTitle", false);
        // The series carries its mainType: updateMessage validates the requested type against the
        // incoming (transient) series, so it must expose the main type
        msg.put("messageSeries", new HashMap<>(Map.of("seriesId", "dma-nw", "mainType", "NW")));
        Map<String, Object> titleDesc = new HashMap<>();
        titleDesc.put("lang", "en");
        titleDesc.put("title", "Maliciously edited title");
        msg.put("descs", new ArrayList<>(List.of(titleDesc)));
        return msg;
    }
}
