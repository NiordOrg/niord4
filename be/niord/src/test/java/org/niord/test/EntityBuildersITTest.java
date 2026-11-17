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
package org.niord.test;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.message.Message;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.user.User;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that exercise the shared entity builders ({@link UserTestFactory},
 * {@link DomainTestFactory}, {@link MessageTestFactory}) and the {@link TestClock} fixture helper by
 * persisting their output and reading it back from the seeded MySQL container. This proves the
 * builders produce valid, persistable entities (not just in-memory POJOs) so the rest of the suite can
 * rely on them.
 *
 * <p>Framework-coupled (Quarkus CDI + seeded DB) — see docs/framework-coupled-tests.md.
 */
class EntityBuildersITTest extends ITTest {

    @PersistenceContext
    EntityManager em;

    @Autowired
    MessageService messageService;

    @Test
    @Transactional
    void userBuilderProducesAPersistableUser() {
        User user = UserTestFactory.user("it-builder-user");
        em.persist(user);
        em.flush();
        Integer id = user.getId();
        em.clear();

        User found = em.find(User.class, id);
        assertNotNull(found, "the built user must persist");
        assertEquals("it-builder-user", found.getUsername());
        assertEquals("it-builder-user@test.niord", found.getEmail());
        assertEquals("Test", found.getFirstName());
        assertEquals("en", found.getLanguage());
    }

    @Test
    @Transactional
    void domainBuilderProducesAPersistableActiveDomain() {
        Domain domain = DomainTestFactory.domain("it-builder-domain", "Builder Domain");
        em.persist(domain);
        em.flush();
        Integer id = domain.getId();
        em.clear();

        Domain found = em.find(Domain.class, id);
        assertNotNull(found, "the built domain must persist");
        assertEquals("it-builder-domain", found.getDomainId());
        assertEquals("Builder Domain", found.getName());
        assertTrue(found.isActive(), "the builder produces an active domain");
    }

    @Test
    void seriesBuilderSetsIdAndMainType() {
        MessageSeries series = MessageTestFactory.series("it-builder-series", MainType.NM);
        assertEquals("it-builder-series", series.getSeriesId());
        assertEquals(MainType.NM, series.getMainType());
    }

    @Test
    @Transactional
    void messageBuilderProducesAPersistableDraftWithDetailsAndDeterministicPublishDate() throws Exception {
        Message created = messageService.createMessage(MessageTestFactory.draftNw("Builder NW title"));
        Integer id = created.getId();
        assertNotNull(id, "the built draft must be created");
        em.flush();
        em.clear();

        Message found = em.find(Message.class, id);
        assertNotNull(found, "the built draft must persist");
        assertEquals(Status.DRAFT, found.getStatus());
        assertEquals(Type.LOCAL_WARNING, found.getType());
        assertEquals("dma-nw", found.getMessageSeries().getSeriesId());

        // The DETAILS part survives the round-trip.
        MessagePart details = found.getParts().stream()
                .filter(p -> p.getType() == MessagePartType.DETAILS)
                .findFirst()
                .orElse(null);
        assertNotNull(details, "the built draft carries a DETAILS part");
        assertTrue(details.getDescs().get(0).getDetails().contains("details"));

        // The deterministic far-past publish date from TestClock is persisted verbatim.
        assertEquals(TestClock.pastDate(), found.getPublishDateFrom());
    }
}
