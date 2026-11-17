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
import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link NavtexPromulgationService} transmitter handling, run against the
 * seeded MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>The seed database contains a NAVTEX {@link PromulgationType} with {@code typeId = "navtex"}
 * but no {@code NavtexTransmitter} rows, so every test creates its own transmitters attached to the
 * seeded type. The service resolves the type by id ({@code createTransmitter} looks it up via
 * {@code PromulgationTypeService}), so the template only needs to carry the {@code typeId}.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the
 * shared seed database pristine and the tests independent. Transmitter names use an {@code IT-TX-}
 * prefix that does not occur in the seed data. Because everything rolls back, unique-name
 * collisions across runs cannot occur; distinct names are still used within a single test.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class NavtexPromulgationServiceITTest extends ITTest {

    private static final String NAVTEX = "navtex";

    @Autowired
    NavtexPromulgationService navtexPromulgationService;

    @PersistenceContext
    EntityManager em;

    /**
     * Builds and persists a {@link NavtexTransmitter} attached to the seeded {@code navtex}
     * promulgation type. {@code createTransmitter} re-resolves the type by its {@code typeId}, so
     * the detached template only needs to carry that id.
     */
    private NavtexTransmitter createTransmitter(String name, boolean active) {
        NavtexTransmitter t = new NavtexTransmitter(name);
        t.setActive(active);
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(NAVTEX);
        t.setPromulgationType(new PromulgationType(typeVo));
        t.setAreas(new ArrayList<>());
        return navtexPromulgationService.createTransmitter(t);
    }

    /** Builds a detached update template for the given name and active flag. */
    private NavtexTransmitter updateTemplate(String name, boolean active) {
        NavtexTransmitter t = new NavtexTransmitter(name);
        t.setActive(active);
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(NAVTEX);
        t.setPromulgationType(new PromulgationType(typeVo));
        t.setAreas(new ArrayList<>());
        return t;
    }

    private List<String> transmitterNames(List<NavtexTransmitter> transmitters) {
        return transmitters.stream().map(NavtexTransmitter::getName).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------------------------- //
    // createTransmitter / findTransmitterByName
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createTransmitterPersistsRowResolvableByName() {
        NavtexTransmitter created = createTransmitter("IT-TX-1", true);
        assertNotNull(created.getId(), "createTransmitter must assign a generated id");

        em.flush();
        em.clear();

        // The row exists in the database and carries the values we set.
        NavtexTransmitter reloaded = em.find(NavtexTransmitter.class, created.getId());
        assertNotNull(reloaded, "transmitter row must exist in the database");
        assertEquals("IT-TX-1", reloaded.getName());
        assertTrue(reloaded.isActive());
        assertEquals(NAVTEX, reloaded.getPromulgationType().getTypeId(),
                "the transmitter must be attached to the seeded navtex type");

        // findTransmitterByName locates it by exact name.
        NavtexTransmitter found = navtexPromulgationService.findTransmitterByName(NAVTEX, "IT-TX-1");
        assertNotNull(found, "findTransmitterByName must locate the created transmitter");
        assertEquals("IT-TX-1", found.getName());
        assertTrue(found.isActive());
        assertEquals(NAVTEX, found.getPromulgationType().getTypeId());
    }

    @Test
    @Transactional
    void findTransmitterByNameIsCaseInsensitive() {
        NavtexTransmitter created = createTransmitter("IT-TX-1", true);
        em.flush();
        em.clear();

        // The named query lower()s both sides, so a lower-case lookup must still match.
        NavtexTransmitter found = navtexPromulgationService.findTransmitterByName(NAVTEX, "it-tx-1");
        assertNotNull(found, "findTransmitterByName must be case-insensitive");
        assertEquals(created.getId(), found.getId());
    }

    @Test
    @Transactional
    void findTransmitterByNameReturnsNullForUnknownName() {
        assertNull(navtexPromulgationService.findTransmitterByName(NAVTEX, "no-such-tx"),
                "findTransmitterByName must return null for an unknown name");
    }

    // ------------------------------------------------------------------------------------------- //
    // getTransmitters
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getTransmittersReturnsTheCreatedTransmitterForTheType() {
        createTransmitter("IT-TX-1", true);
        em.flush();
        em.clear();

        List<String> names = transmitterNames(navtexPromulgationService.getTransmitters(NAVTEX));
        assertTrue(names.contains("IT-TX-1"),
                "getTransmitters must include the transmitter created for the navtex type");
    }

    // ------------------------------------------------------------------------------------------- //
    // findTransmittersByAreas (only-active filter)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findTransmittersByAreasOnlyActiveExcludesInactiveTransmitters() {
        createTransmitter("IT-TX-ACTIVE", true);
        createTransmitter("IT-TX-INACTIVE", false);
        em.flush();
        em.clear();

        List<String> activeNames = transmitterNames(
                navtexPromulgationService.findTransmittersByAreas(NAVTEX, null, true));
        assertTrue(activeNames.contains("IT-TX-ACTIVE"),
                "only-active search must include the active transmitter");
        assertFalse(activeNames.contains("IT-TX-INACTIVE"),
                "only-active search must exclude the inactive transmitter");

        // getTransmitters does not filter on active, so it returns both.
        List<String> allNames = transmitterNames(navtexPromulgationService.getTransmitters(NAVTEX));
        assertTrue(allNames.contains("IT-TX-ACTIVE"));
        assertTrue(allNames.contains("IT-TX-INACTIVE"));
    }

    // ------------------------------------------------------------------------------------------- //
    // updateTransmitter
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateTransmitterPersistsTheNewActiveFlag() {
        NavtexTransmitter created = createTransmitter("IT-TX-UPD", true);
        Integer id = created.getId();
        em.flush();

        // Deactivate the transmitter via an update template carrying the same name.
        navtexPromulgationService.updateTransmitter(updateTemplate("IT-TX-UPD", false));

        em.flush();
        em.clear();

        NavtexTransmitter reloaded = em.find(NavtexTransmitter.class, id);
        assertNotNull(reloaded, "updateTransmitter must not delete the row");
        assertFalse(reloaded.isActive(), "updateTransmitter must persist the new active flag");
        assertFalse(navtexPromulgationService.findTransmitterByName(NAVTEX, "IT-TX-UPD").isActive());
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteTransmitter
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteTransmitterRemovesTheRowAndReportsSuccess() {
        NavtexTransmitter created = createTransmitter("IT-TX-DEL", true);
        Integer id = created.getId();
        em.flush();

        assertTrue(navtexPromulgationService.deleteTransmitter(NAVTEX, "IT-TX-DEL"),
                "deleteTransmitter must return true when a transmitter is removed");

        em.flush();
        em.clear();

        assertNull(em.find(NavtexTransmitter.class, id), "deleted transmitter row must be gone");
        assertNull(navtexPromulgationService.findTransmitterByName(NAVTEX, "IT-TX-DEL"),
                "the deleted transmitter must no longer be findable by name");
    }

    @Test
    @Transactional
    void deleteTransmitterReturnsFalseForUnknownName() {
        assertFalse(navtexPromulgationService.deleteTransmitter(NAVTEX, "no-such-tx"),
                "deleteTransmitter must return false for an unknown name");
    }
}
