/*
 * Copyright 2024 Danish Maritime Authority.
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
import org.niord.core.user.User;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Persistence round-trip fidelity tests for column types that differ between MySQL&nbsp;8 and
 * PostgreSQL, guarding the planned MySQL&nbsp;-&gt;&nbsp;PostgreSQL migration.
 *
 * <p>These pin the <em>actual value</em> surviving a write/read cycle — not merely that a row is
 * non-null — for the type families the migration changes:
 * <ul>
 *   <li><b>{@code bit(1)} -&gt; {@code boolean}</b>: an explicit {@code false} must round-trip as
 *       {@code false}, not {@code null} or {@code true} (35 {@code bit(1)} columns in the schema).</li>
 *   <li><b>{@code datetime(6)} -&gt; {@code timestamp(6)}</b>: sub-second (millisecond) precision must
 *       survive.</li>
 *   <li><b>{@code @Enumerated(STRING)} inline ENUM -&gt; {@code varchar}/{@code CREATE TYPE}</b>: enum
 *       values must round-trip by value across the {@code MainType}/{@code Type}/{@code Status}
 *       families.</li>
 *   <li><b>{@code longtext}/{@code @Lob} -&gt; {@code text}</b>: a large (100&nbsp;KB) UTF-8 body must
 *       round-trip byte-identical (MySQL {@code TEXT} truncates at 64&nbsp;KB; {@code longtext} and
 *       Postgres {@code text} do not).</li>
 * </ul>
 *
 * <p>Every assertion is migration-agnostic: it pins the observable persisted value, which must be
 * identical on MySQL today and PostgreSQL after. Tests use direct {@link EntityManager} persistence
 * (no service-layer sanitization) and reload after {@code flush()/clear()} so the assertions read the
 * database, not the first-level cache. Each test rolls back via {@link Transactional}.
 *
 * <p>This is a framework-coupled test (boots the application context for JPA persistence) — see
 * docs/framework-coupled-tests.md.
 */
class PersistenceFidelityITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @PersistenceContext
    EntityManager em;

    private Message newDraft(Type type, String title) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nw"));
        msg.setType(type);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        msg.createDesc("en").setTitle(title);
        return msg;
    }

    private Message create(Message draft) {
        try {
            return messageService.createMessage(draft);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private User persistUser() {
        User u = new User();
        u.setUsername("fidelity-user-" + UUID.randomUUID());
        u.setFirstName("F");
        u.setLastName("L");
        em.persist(u);
        return u;
    }

    /** Persists a Comment (with a host message + user) carrying the given body and created timestamp. */
    private Comment persistComment(String body, Date created) {
        Message host = create(newDraft(Type.COASTAL_WARNING, "comment-host-" + UUID.randomUUID()));
        Comment c = new Comment();
        c.setMessage(host);
        c.setUser(persistUser());
        c.setComment(body);
        c.setCreated(created);
        em.persist(c);
        return c;
    }

    // ------------------------------------------------------------------------------------------- //
    // bit(1) -> boolean: explicit false survives as false (not null, not true)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void nullableBooleanFalseRoundTripsAsFalseNotNullOrTrue() {
        Message created = create(newDraft(Type.COASTAL_WARNING, "bool"));
        Integer id = created.getId();

        // Set the nullable Boolean column directly on the managed entity, then flush/clear so the value
        // is read back from the bit(1) column rather than the first-level cache.
        created.setOriginalInformation(false);
        em.flush();
        em.clear();
        assertEquals(Boolean.FALSE, em.find(Message.class, id).getOriginalInformation(),
                "an explicit false on a bit(1) column must round-trip as false, not null or true");

        Message managed = em.find(Message.class, id);
        managed.setOriginalInformation(true);
        em.flush();
        em.clear();
        assertEquals(Boolean.TRUE, em.find(Message.class, id).getOriginalInformation(),
                "an explicit true on a bit(1) column must round-trip as true");
    }

    // ------------------------------------------------------------------------------------------- //
    // @Enumerated(STRING): enum values round-trip by value
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void enumsRoundTripByValueAcrossTypeMainTypeAndStatus() {
        Message coastal = create(newDraft(Type.COASTAL_WARNING, "enum-coastal"));
        Message local = create(newDraft(Type.LOCAL_WARNING, "enum-local"));
        Integer coastalId = coastal.getId();
        Integer localId = local.getId();

        em.flush();
        em.clear();

        Message reloadedCoastal = em.find(Message.class, coastalId);
        assertEquals(Type.COASTAL_WARNING, reloadedCoastal.getType(), "Type enum must round-trip by value");
        assertEquals(MainType.NW, reloadedCoastal.getMainType(),
                "the derived MainType enum must round-trip by value");
        assertEquals(Status.DRAFT, reloadedCoastal.getStatus(), "Status enum must round-trip (DRAFT)");

        Message reloadedLocal = em.find(Message.class, localId);
        assertEquals(Type.LOCAL_WARNING, reloadedLocal.getType(),
                "a different Type enum value must round-trip distinctly");
    }

    @Test
    @Transactional
    void statusEnumRoundTripsAfterStatusChange() {
        Message draft = create(newDraft(Type.COASTAL_WARNING, "status-verified"));
        Integer id = draft.getId();
        try {
            messageService.updateStatus(draft.getUid(), Status.VERIFIED);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        em.flush();
        em.clear();
        assertEquals(Status.VERIFIED, em.find(Message.class, id).getStatus(),
                "a changed Status enum must round-trip as the new value (VERIFIED)");
    }

    // ------------------------------------------------------------------------------------------- //
    // longtext / @Lob -> text: large UTF-8 body round-trips byte-identical
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void largeUtf8TextRoundTripsByteIdentical() {
        // ~100 KB of mixed ASCII + Unicode + newlines + angle brackets + quotes, well beyond MySQL's
        // 64 KB TEXT limit (the column is an @Lob) and exercising Postgres' unlimited text. Persisted
        // directly via the EntityManager, so no service-layer HTML sanitization alters the bytes.
        StringBuilder sb = new StringBuilder();
        String unit = "Sø-opmærksomhed ⚓ \"quote\" <tag> \n\t— 0123456789 ";
        while (sb.length() < 100_000) {
            sb.append(unit);
        }
        String big = sb.toString();

        Comment c = persistComment(big, new Date());
        Integer id = c.getId();

        em.flush();
        em.clear();

        Comment reloaded = em.find(Comment.class, id);
        assertNotNull(reloaded, "the comment must be reloadable");
        assertNotNull(reloaded.getComment(), "the large text body must be persisted");
        assertEquals(big.length(), reloaded.getComment().length(),
                "the full large text length must survive (no 64 KB TEXT truncation)");
        assertEquals(big, reloaded.getComment(), "the large UTF-8 body must round-trip byte-identical");
    }

    // ------------------------------------------------------------------------------------------- //
    // datetime(6) -> timestamp(6): millisecond precision survives
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void timestampRoundTripsWithMillisecondPrecision() {
        // A non-zero millisecond component so a coarsening to whole seconds would be detected. Comment's
        // @PrePersist only defaults `created` when null, so the explicit value is preserved.
        long millis = 1_600_000_000_123L;   // 2020-09-13T12:26:40.123Z

        Comment c = persistComment("timestamp fidelity", new Date(millis));
        Integer id = c.getId();

        em.flush();
        em.clear();

        Comment reloaded = em.find(Comment.class, id);
        assertNotNull(reloaded, "the comment must be reloadable");
        assertNotNull(reloaded.getCreated(), "the created timestamp must be persisted");
        assertEquals(millis, reloaded.getCreated().getTime(),
                "a datetime(6)/timestamp(6) column must preserve millisecond precision on round-trip");
    }
}
