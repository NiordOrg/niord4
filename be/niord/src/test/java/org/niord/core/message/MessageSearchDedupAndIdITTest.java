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
import org.niord.core.area.Area;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two cross-cutting migration-safety checks for {@link MessageService} persistence/search:
 *
 * <ol>
 *   <li><b>Join de-duplication</b> — a message matched through several joined rows (e.g. it belongs to
 *       two areas, both requested) must appear <em>exactly once</em> in the result. The criteria query
 *       relies on {@code .distinct(true)}; MySQL and PostgreSQL differ in how {@code DISTINCT} interacts
 *       with multi-join Cartesian products, so this pins the de-dup contract end-to-end.</li>
 *   <li><b>Generated-id invariants</b> — a freshly persisted entity must get a unique id strictly
 *       greater than every existing id of that entity. This is deliberately framed to be
 *       <em>strategy-agnostic</em>: it holds both under the current single shared {@code hibernate_sequence}
 *       and under the per-entity {@code IDENTITY}/{@code SEQUENCE} strategy planned for Postgres — what it
 *       guards against is a migrated sequence seeded too low (which would collide with seeded rows). It
 *       does NOT assert a fixed numeric floor, because that floor is strategy-specific.</li>
 * </ol>
 *
 * <p>Migration-agnostic and run in a rolled-back {@link Transactional} transaction. Result assertions
 * are restricted to ids each test created.
 *
 * <p>This is a framework-coupled test (boots the application context for JPA persistence/search) — see
 * docs/framework-coupled-tests.md.
 */
class MessageSearchDedupAndIdITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSearchService messageSearchService;

    @PersistenceContext
    EntityManager em;

    private Message newDraft(String title) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nw"));
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        part.createDesc("en").setSubject("subject");
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

    /** Persists a bare top-level area with a self-referencing lineage (mirrors MessageSearchFilterMatrixITTest). */
    private Area persistArea() {
        Area a = new Area();
        em.persist(a);
        em.flush();                       // assigns the id
        a.setLineage("/" + a.getId() + "/");
        em.flush();
        return a;
    }

    // ------------------------------------------------------------------------------------------- //
    // Join de-duplication (.distinct)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void messageInTwoRequestedAreasIsReturnedExactlyOnce() {
        Area area1 = persistArea();
        Area area2 = persistArea();

        Message msg = newDraft("dedup-two-areas");
        msg.setAreas(List.of(area1, area2));
        Message created = create(msg);

        List<Integer> ids = messageSearchService.search(new MessageSearchParams()
                        .seriesIds(Set.of("dma-nw"))
                        .areaIds(Set.of(String.valueOf(area1.getId()), String.valueOf(area2.getId()))))
                .getData().stream()
                .map(Message::getId)
                .collect(Collectors.toList());

        long occurrences = ids.stream().filter(id -> id.equals(created.getId())).count();
        assertEquals(1L, occurrences,
                "a message belonging to both requested areas must appear exactly once (the join is de-duplicated)");
    }

    // ------------------------------------------------------------------------------------------- //
    // Generated-id invariants (strategy-agnostic)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void newlyPersistedEntitiesGetUniqueIdsAboveEveryExistingId() {
        int maxExisting = ((Number) em.createQuery("select coalesce(max(a.id), 0) from Area a")
                .getSingleResult()).intValue();

        Area a1 = persistArea();
        Area a2 = persistArea();
        Area a3 = persistArea();

        List<Integer> ids = List.of(a1.getId(), a2.getId(), a3.getId());

        for (Integer id : ids) {
            assertTrue(id != null && id > maxExisting,
                    "a generated id (" + id + ") must be strictly greater than every pre-existing id ("
                            + maxExisting + ") — guards a migrated sequence seeded too low");
        }
        assertEquals(3, Set.copyOf(ids).size(), "generated ids must be unique");
    }
}
