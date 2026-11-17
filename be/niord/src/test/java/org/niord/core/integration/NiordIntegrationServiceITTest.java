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

package org.niord.core.integration;

import org.springframework.transaction.annotation.Transactional;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link NiordIntegrationService}, run against the seeded MySQL container of the
 * {@link ITTest} infrastructure.
 *
 * <p>The create / update / delete service methods are {@code @Transactional} (default REQUIRED), so
 * when called from a {@link TestTransaction}-wrapped test they join the test transaction and every
 * write is rolled back at the end of the test. Those tests assert persisted state by forcing a flush,
 * clearing the persistence context and reloading with {@link EntityManager#find}.
 *
 * <p>The {@code getPendingNiordIntegrations} named-query test reads {@code current_timestamp} on the
 * database, so it persists rows directly through the entity manager (still inside the rolled-back test
 * transaction) and asserts membership by id rather than by list identity, keeping it isolated from any
 * other integration rows that may exist in the shared seed database.
 *
 * <p>The happy path of {@code processNiordIntegration} is intentionally NOT exercised: it delegates to
 * the remote-HTTP {@code NiordIntegrationExecutionService}, which cannot be driven from an integration
 * test without a live remote Niord server — documented in docs/untestable-code.md. Only its
 * unknown-id guard is asserted here.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class NiordIntegrationServiceITTest extends ITTest {

    @Autowired
    NiordIntegrationService service;

    @PersistenceContext
    EntityManager em;

    /** Ids of rows committed outside the test transaction, cleaned up best-effort in @AfterEach. */
    private final List<Integer> committedIds = new ArrayList<>();

    @AfterEach
    void cleanupCommittedRows() {
        for (Integer id : committedIds) {
            try {
                Tx.requiringNew().run(() -> {
                    NiordIntegration row = em.find(NiordIntegration.class, id);
                    if (row != null) {
                        em.remove(row);
                    }
                });
            } catch (Exception ignore) {
                // best-effort cleanup
            }
        }
        committedIds.clear();
    }

    /** Builds a detached, minimal NiordIntegration template with a unique url and one mapping. */
    private NiordIntegration newTemplate(long n) {
        NiordIntegration integration = new NiordIntegration();
        integration.setUrl("http://it-niord-" + n);
        integration.setActive(true);
        MessageSeriesMapping mapping = new MessageSeriesMapping();
        mapping.setSourceSeriesId("src-" + n);
        mapping.setTargetSeriesId("tgt-" + n);
        integration.addMessageSeriesMapping(mapping);
        return integration;
    }

    // ------------------------------------------------------------------------------------------- //
    // createNiordIntegration
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createNiordIntegrationPersistsRowWithGeneratedIdAndMappings() {
        long n = System.nanoTime();
        NiordIntegration created = service.createNiordIntegration(newTemplate(n));

        Integer id = created.getId();
        assertNotNull(id, "createNiordIntegration must assign a generated id");

        em.flush();
        em.clear();

        NiordIntegration reloaded = em.find(NiordIntegration.class, id);
        assertNotNull(reloaded, "the integration row must exist in the database");
        assertEquals("http://it-niord-" + n, reloaded.getUrl());
        assertTrue(reloaded.isActive(), "the created integration must be active");

        assertEquals(1, reloaded.getMessageSeriesMappings().size(),
                "the single message series mapping must have been cascaded to the database");
        MessageSeriesMapping mapping = reloaded.getMessageSeriesMappings().get(0);
        assertEquals("src-" + n, mapping.getSourceSeriesId());
        assertEquals("tgt-" + n, mapping.getTargetSeriesId());
        assertEquals(reloaded.getId(), mapping.getNiordIntegration().getId(),
                "the mapping must point back to its owning integration");
    }

    @Test
    @Transactional
    void createNiordIntegrationComputesNextScheduledExecutionAboutSixtyMinutesAhead() {
        long n = System.nanoTime();

        // The service adds MINUTES_BETWEEN_EXECUTIONS (60) minutes plus up to 60 random seconds.
        Calendar lower = Calendar.getInstance();
        lower.add(Calendar.MINUTE, 59);
        Calendar upper = Calendar.getInstance();
        upper.add(Calendar.MINUTE, 62);

        NiordIntegration created = service.createNiordIntegration(newTemplate(n));
        Date next = created.getNextScheduledExecution();

        assertNotNull(next, "createNiordIntegration must compute a next scheduled execution");
        assertTrue(next.after(lower.getTime()),
                "next execution must be at least ~59 minutes in the future, was " + next);
        assertTrue(next.before(upper.getTime()),
                "next execution must be at most ~62 minutes in the future, was " + next);
    }

    @Test
    @Transactional
    void createNiordIntegrationRejectsTemplateThatAlreadyHasAnId() {
        long n = System.nanoTime();
        NiordIntegration template = newTemplate(n);
        template.setId(123456);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createNiordIntegration(template));
        assertEquals("Cannot create existing Niord integration", ex.getMessage());
    }

    @Test
    @Transactional
    void createNiordIntegrationRejectsNullTemplate() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createNiordIntegration(null));
        // INT2 fixed: a null template now reports a distinct, accurate message (it is not an
        // "existing" integration), separate from the persisted-template rejection above.
        assertEquals("Niord integration must not be null", ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // updateNiordIntegration
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateNiordIntegrationPersistsChangedFieldsAndReplacesMappings() {
        long n = System.nanoTime();
        NiordIntegration created = service.createNiordIntegration(newTemplate(n));
        Integer id = created.getId();
        Date originalNext = created.getNextScheduledExecution();
        em.flush();
        em.clear();

        // Build a detached update template carrying the same id but new field values and new mappings.
        NiordIntegration template = new NiordIntegration();
        template.setId(id);
        template.setUrl("http://it-niord-updated-" + n);
        template.setActive(false);
        template.setAssignNewUids(true);
        template.setCreateBaseData(true);
        MessageSeriesMapping m1 = new MessageSeriesMapping();
        m1.setSourceSeriesId("new-src-a-" + n);
        m1.setTargetSeriesId("new-tgt-a-" + n);
        template.addMessageSeriesMapping(m1);
        MessageSeriesMapping m2 = new MessageSeriesMapping();
        m2.setSourceSeriesId("new-src-b-" + n);
        m2.setTargetSeriesId("new-tgt-b-" + n);
        template.addMessageSeriesMapping(m2);

        service.updateNiordIntegration(template);
        em.flush();
        em.clear();

        NiordIntegration reloaded = em.find(NiordIntegration.class, id);
        assertNotNull(reloaded);
        assertEquals("http://it-niord-updated-" + n, reloaded.getUrl());
        assertFalse(reloaded.isActive(), "active flag must be updated");
        assertTrue(reloaded.isAssignNewUids(), "assignNewUids flag must be updated");
        assertTrue(reloaded.isCreateBaseData(), "createBaseData flag must be updated");

        // The original single mapping must have been cleared and the two new ones persisted.
        assertEquals(2, reloaded.getMessageSeriesMappings().size(),
                "the original mappings must be replaced by the two new ones");
        Set<String> sources = reloaded.getMessageSeriesMappings().stream()
                .map(MessageSeriesMapping::getSourceSeriesId)
                .collect(Collectors.toSet());
        assertEquals(Set.of("new-src-a-" + n, "new-src-b-" + n), sources);
        assertFalse(sources.contains("src-" + n),
                "the original mapping must no longer be present");

        // The service comment states nextScheduledExecution is NOT updated.
        assertEquals(originalNext, reloaded.getNextScheduledExecution(),
                "update must leave nextScheduledExecution unchanged");
    }

    @Test
    @Transactional
    void updateNiordIntegrationThrowsForNonExistingId() {
        NiordIntegration template = new NiordIntegration();
        template.setId(987654);
        template.setUrl("http://it-niord-missing-" + System.nanoTime());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateNiordIntegration(template));
        assertTrue(ex.getMessage().contains("987654"),
                "the error must name the unknown integration id, was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteNiordIntegration
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteNiordIntegrationRemovesRowAndReturnsTrue() {
        long n = System.nanoTime();
        Integer id = service.createNiordIntegration(newTemplate(n)).getId();
        em.flush();

        assertTrue(service.deleteNiordIntegration(id),
                "deleting an existing integration must return true");
        em.flush();
        em.clear();

        assertNull(em.find(NiordIntegration.class, id),
                "the deleted integration row must be gone from the database");
    }

    @Test
    @Transactional
    void deleteNiordIntegrationReturnsFalseForUnknownId() {
        assertFalse(service.deleteNiordIntegration(424242),
                "deleting a non-existing integration must return false");
    }

    // ------------------------------------------------------------------------------------------- //
    // findById
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByIdReturnsCreatedIntegration() {
        long n = System.nanoTime();
        Integer id = service.createNiordIntegration(newTemplate(n)).getId();
        em.flush();

        NiordIntegration found = service.findById(id);
        assertNotNull(found);
        assertEquals("http://it-niord-" + n, found.getUrl());
    }

    @Test
    @Transactional
    void findByIdReturnsNullForUnknownId() {
        assertNull(service.findById(525252),
                "findById must return null for an unknown id");
    }

    // ------------------------------------------------------------------------------------------- //
    // getAllNiordIntegrations
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getAllNiordIntegrationsContainsCreatedIntegration() {
        long n = System.nanoTime();
        Integer id = service.createNiordIntegration(newTemplate(n)).getId();
        em.flush();

        Set<Integer> ids = service.getAllNiordIntegrations().stream()
                .map(NiordIntegration::getId)
                .collect(Collectors.toSet());
        assertTrue(ids.contains(id),
                "getAllNiordIntegrations must include the freshly created integration");
    }

    // ------------------------------------------------------------------------------------------- //
    // getPendingNiordIntegrations
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void freshlyCreatedIntegrationIsNotPending() {
        long n = System.nanoTime();
        // A freshly created integration has nextScheduledExecution ~60 minutes in the future.
        Integer id = service.createNiordIntegration(newTemplate(n)).getId();
        em.flush();

        Set<Integer> pendingIds = service.getPendingNiordIntegrations().stream()
                .map(NiordIntegration::getId)
                .collect(Collectors.toSet());
        assertFalse(pendingIds.contains(id),
                "an integration scheduled in the future must NOT be pending");
    }

    @Test
    @Transactional
    void activeIntegrationWithNullNextScheduledExecutionIsPending() {
        long n = System.nanoTime();
        // Persist directly so we control nextScheduledExecution = null (the service always sets it).
        NiordIntegration integration = new NiordIntegration();
        integration.setUrl("http://it-niord-pending-null-" + n);
        integration.setActive(true);
        integration.setNextScheduledExecution(null);
        em.persist(integration);
        em.flush();
        Integer id = integration.getId();
        assertNotNull(id);

        Set<Integer> pendingIds = service.getPendingNiordIntegrations().stream()
                .map(NiordIntegration::getId)
                .collect(Collectors.toSet());
        assertTrue(pendingIds.contains(id),
                "an active integration with a null nextScheduledExecution must be pending");
    }

    @Test
    @Transactional
    void activeIntegrationWithPastNextScheduledExecutionIsPending() {
        long n = System.nanoTime();
        NiordIntegration integration = new NiordIntegration();
        integration.setUrl("http://it-niord-pending-past-" + n);
        integration.setActive(true);
        // Several days in the past -> due. (A large offset is used so the DB-side comparison against
        // current_timestamp is robust against any timezone skew between the JVM and the MySQL
        // container; a 1-hour offset is not enough to beat a multi-hour skew.)
        integration.setNextScheduledExecution(new Date(System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000L));
        em.persist(integration);
        em.flush();
        Integer id = integration.getId();

        Set<Integer> pendingIds = service.getPendingNiordIntegrations().stream()
                .map(NiordIntegration::getId)
                .collect(Collectors.toSet());
        assertTrue(pendingIds.contains(id),
                "an active integration whose next execution is in the past must be pending");
    }

    @Test
    @Transactional
    void inactiveIntegrationWithNullNextScheduledExecutionIsNotPending() {
        long n = System.nanoTime();
        NiordIntegration integration = new NiordIntegration();
        integration.setUrl("http://it-niord-inactive-" + n);
        integration.setActive(false);
        integration.setNextScheduledExecution(null);
        em.persist(integration);
        em.flush();
        Integer id = integration.getId();

        Set<Integer> pendingIds = service.getPendingNiordIntegrations().stream()
                .map(NiordIntegration::getId)
                .collect(Collectors.toSet());
        assertFalse(pendingIds.contains(id),
                "an inactive integration must never be pending, even with a null next execution");
    }

    // ------------------------------------------------------------------------------------------- //
    // processNiordIntegration — only the unknown-id guard (happy path is remote-HTTP, untestable)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void processNiordIntegrationThrowsForUnknownId() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.processNiordIntegration(636363));
        assertTrue(ex.getMessage().contains("No Niord Integration with ID"),
                "the error must report the missing integration, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("636363"),
                "the error must name the unknown id, was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // processNextPendingNiordIntegration - the @Scheduled(cron="51 */1 * * * ?") poll, invoked directly
    //
    // The package-private scheduled method picks the first pending integration and processes it. The remote
    // import (NiordIntegrationExecutionService over HTTP) cannot succeed in a test, but processNiordIntegration
    // swallows that failure and STILL advances nextScheduledExecution — that reschedule is the observable
    // effect asserted here. processNiordIntegration is @Transactional (REQUIRED), so it joins the test
    // transaction and every write is rolled back. The URL points at a closed local port so the remote hop
    // fails fast. The seed contains no integration rows, so the persisted one is the only pending candidate.
    // Only the cron firing time itself is not asserted. See docs/untestable-code.md / docs/framework-coupled-tests.md.
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void processNextPendingProcessesAPendingIntegrationAndAdvancesItsSchedule() {
        long n = System.nanoTime();
        // Active + a multi-day-past next execution => pending (a large offset beats JVM/MySQL timezone skew
        // on the DB-side current_timestamp comparison, as in the getPendingNiordIntegrations tests above).
        NiordIntegration integration = new NiordIntegration();
        integration.setUrl("http://localhost:1/niord-" + n);   // closed port -> remote import fails fast
        integration.setActive(true);
        integration.setNextScheduledExecution(new Date(System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000L));
        em.persist(integration);
        em.flush();
        Integer id = integration.getId();

        assertTrue(service.getPendingNiordIntegrations().stream().anyMatch(x -> x.getId().equals(id)),
                "precondition: the active, past-due integration must be pending");

        service.processNextPendingNiordIntegration();
        em.flush();
        em.clear();

        NiordIntegration reloaded = service.findById(id);
        assertNotNull(reloaded, "the integration row must still exist after processing");
        assertTrue(reloaded.getNextScheduledExecution().after(new Date()),
                "processing a pending integration must advance its nextScheduledExecution into the future, "
                        + "even though the remote import failed and was swallowed");
    }

    @Test
    @Transactional
    void processNextPendingLeavesAnInactiveIntegrationUntouched() {
        long n = System.nanoTime();
        NiordIntegration integration = new NiordIntegration();
        integration.setUrl("http://localhost:1/niord-inactive-" + n);
        integration.setActive(false);                                   // inactive => never pending
        integration.setNextScheduledExecution(new Date(System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000L));
        em.persist(integration);
        em.flush();
        Integer id = integration.getId();

        service.processNextPendingNiordIntegration();
        em.flush();
        em.clear();

        NiordIntegration reloaded = service.findById(id);
        assertTrue(reloaded.getNextScheduledExecution().before(new Date()),
                "an inactive integration must not be processed, so its past nextScheduledExecution is NOT advanced");
    }
}
