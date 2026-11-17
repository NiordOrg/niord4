/*
 * Copyright 2017 Danish Maritime Authority.
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
package org.niord.core.promulgation;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.test.ITTest;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link PromulgationTypeService} persistence behaviour, run against the seeded
 * MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>The seed database (see {@code niord/src/test/resources/db/niord-test-seed.sql}) contains exactly
 * three promulgation types — {@code navtex} (priority 1), {@code audio} (priority 50) and {@code twitter}
 * (priority 100), all active. These tests read those rows and additionally create their own
 * {@code it-}-prefixed types, which do not occur in the seed data.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the shared
 * seed database pristine and the tests independent. Because every test rolls back, unique-constraint
 * collisions on {@code typeId} between runs are not a concern.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class PromulgationTypeServiceITTest extends ITTest {

    @Autowired
    PromulgationTypeService promulgationTypeService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached {@link PromulgationType} from a {@link PromulgationTypeVo} with the given fields. */
    private PromulgationType newType(String typeId, String serviceId, String name, int priority,
                                     Boolean active, PromulgationType.Requirement requirement) {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId(typeId);
        vo.setServiceId(serviceId);
        vo.setName(name);
        vo.setPriority(priority);
        vo.setActive(active);
        vo.setRequirement(requirement);
        // A non-blank language keeps updatePromulgationType from nulling it via defaultIfBlank.
        vo.setLanguage("en");
        return new PromulgationType(vo);
    }

    // ------------------------------------------------------------------------------------------- //
    // getAll / getActive
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getAllReturnsSeededTypesOrderedByPriorityAscending() {
        List<String> typeIds = promulgationTypeService.getAll().stream()
                .map(PromulgationType::getTypeId)
                .collect(Collectors.toList());

        assertTrue(typeIds.contains("navtex"), "getAll must include the seeded 'navtex' type");
        assertTrue(typeIds.contains("audio"), "getAll must include the seeded 'audio' type");
        assertTrue(typeIds.contains("twitter"), "getAll must include the seeded 'twitter' type");

        // Priorities are 1 < 50 < 100, so the ordering must be navtex, audio, twitter.
        assertTrue(typeIds.indexOf("navtex") < typeIds.indexOf("audio"),
                "navtex (priority 1) must order before audio (priority 50)");
        assertTrue(typeIds.indexOf("audio") < typeIds.indexOf("twitter"),
                "audio (priority 50) must order before twitter (priority 100)");
    }

    @Test
    @Transactional
    void getActiveReturnsOnlyActiveTypesAndIncludesNavtex() {
        List<PromulgationType> active = promulgationTypeService.getActive();

        assertTrue(active.stream().allMatch(PromulgationType::isActive),
                "getActive must only return active promulgation types");
        assertTrue(active.stream().anyMatch(pt -> "navtex".equals(pt.getTypeId())),
                "the seeded active 'navtex' type must be returned by getActive");
    }

    // ------------------------------------------------------------------------------------------- //
    // getPromulgationType
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getPromulgationTypeResolvesSeededTypeAndReturnsNullForUnknown() {
        PromulgationType navtex = promulgationTypeService.getPromulgationType("navtex");
        assertNotNull(navtex, "the seeded 'navtex' type must be resolvable by typeId");
        assertEquals("NAVTEX", navtex.getName());
        assertEquals("navtex", navtex.getServiceId());

        assertNull(promulgationTypeService.getPromulgationType("does-not-exist"),
                "an unknown typeId must resolve to null");
    }

    @Test
    @Transactional
    void getPromulgationTypeThrowsAFailedFlushInsteadOfAnsweringNull() {
        // A pending insert that breaks the unique typeId constraint: the lookup's query flushes it
        // first, and the failure must reach the caller rather than read as "not found"
        em.persist(newType("navtex", "navtex", "Duplicate NAVTEX", 1,
                Boolean.TRUE, PromulgationType.Requirement.OPTIONAL));

        assertThrows(PersistenceException.class,
                () -> promulgationTypeService.getPromulgationType("navtex"),
                "a failed flush during the lookup must be thrown, not answered with null");
    }

    // ------------------------------------------------------------------------------------------- //
    // createPromulgationType
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createPromulgationTypePersistsAllFields() {
        PromulgationType created = promulgationTypeService.createPromulgationType(
                newType("it-create-type", "navtex", "IT Create", 7,
                        Boolean.TRUE, PromulgationType.Requirement.OPTIONAL));
        Integer id = created.getId();
        assertNotNull(id, "createPromulgationType must assign a generated id");

        em.flush();
        em.clear();

        PromulgationType reloaded = em.find(PromulgationType.class, id);
        assertNotNull(reloaded, "the promulgation type row must exist in the database");
        assertEquals("it-create-type", reloaded.getTypeId());
        assertEquals("IT Create", reloaded.getName());
        assertEquals(7, reloaded.getPriority());
        assertTrue(reloaded.isActive(), "the created type was flagged active");
        assertEquals(PromulgationType.Requirement.OPTIONAL, reloaded.getRequirement());

        assertNotNull(promulgationTypeService.getPromulgationType("it-create-type"),
                "the created type must also be resolvable by typeId");
    }

    @Test
    @Transactional
    void createPromulgationTypeRejectsDuplicateTypeId() {
        // 'navtex' is already seeded, so creating another type with the same typeId must be rejected.
        PromulgationType duplicate = newType("navtex", "navtex", "Duplicate NAVTEX", 1,
                Boolean.TRUE, PromulgationType.Requirement.OPTIONAL);

        assertThrows(IllegalArgumentException.class,
                () -> promulgationTypeService.createPromulgationType(duplicate),
                "creating a promulgation type with an existing typeId must throw");
    }

    // ------------------------------------------------------------------------------------------- //
    // updatePromulgationType
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updatePromulgationTypePersistsChangesButKeepsServiceId() {
        PromulgationType created = promulgationTypeService.createPromulgationType(
                newType("it-update-type", "navtex", "Before", 5,
                        Boolean.TRUE, PromulgationType.Requirement.OPTIONAL));
        Integer id = created.getId();
        em.flush();

        // updatePromulgationType matches by typeId and copies name/priority etc., but never serviceId.
        promulgationTypeService.updatePromulgationType(
                newType("it-update-type", "audio", "After", 9,
                        Boolean.TRUE, PromulgationType.Requirement.MANDATORY));

        em.flush();
        em.clear();

        PromulgationType reloaded = em.find(PromulgationType.class, id);
        assertNotNull(reloaded, "the updated type must still exist under its original id");
        assertEquals("After", reloaded.getName(), "the name must be updated");
        assertEquals(9, reloaded.getPriority(), "the priority must be updated");
        assertEquals(PromulgationType.Requirement.MANDATORY, reloaded.getRequirement(),
                "the requirement must be updated");
        assertEquals("navtex", reloaded.getServiceId(),
                "updatePromulgationType must not change the serviceId");
    }

    @Test
    @Transactional
    void updatePromulgationTypeRejectsUnknownTypeId() {
        PromulgationType unknown = newType("nope-not-here", "navtex", "Ghost", 3,
                Boolean.TRUE, PromulgationType.Requirement.OPTIONAL);

        assertThrows(IllegalArgumentException.class,
                () -> promulgationTypeService.updatePromulgationType(unknown),
                "updating a non-existing promulgation type must throw");
    }

    // ------------------------------------------------------------------------------------------- //
    // deletePromulgationType
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deletePromulgationTypeRemovesRowAndReportsOutcome() {
        PromulgationType created = promulgationTypeService.createPromulgationType(
                newType("it-delete-type", "navtex", "IT Delete", 4,
                        Boolean.TRUE, PromulgationType.Requirement.OPTIONAL));
        Integer id = created.getId();
        em.flush();

        assertTrue(promulgationTypeService.deletePromulgationType("it-delete-type"),
                "deleting an existing promulgation type must return true");

        em.flush();
        em.clear();

        assertNull(em.find(PromulgationType.class, id),
                "the deleted promulgation type row must be gone from the database");

        assertFalse(promulgationTypeService.deletePromulgationType("nope-not-here"),
                "deleting a non-existing promulgation type must return false");
    }
}
