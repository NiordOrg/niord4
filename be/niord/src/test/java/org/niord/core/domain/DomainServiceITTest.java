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
package org.niord.core.domain;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

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
 * Integration tests for {@link DomainService} CRUD and lookup behaviour, run against the seeded MySQL
 * container of the {@link ITTest} infrastructure.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the shared
 * seed database pristine and the tests independent. Test domains use an {@code IT-domain-} domainId
 * prefix that does not occur in the seed data, so lookups stay isolated from the seeded clients.
 *
 * <p>Only the CRUD subset that does not require the thread-local current-domain / Keycloak security
 * context is exercised here: {@code createDomain(domain, false)}, {@code updateDomain},
 * {@code deleteDomain}, {@code findByDomainId}, {@code findByDomainIds} and
 * {@code getDomains(false, false)}. The Keycloak-coupled paths
 * ({@code currentDomain()}, {@code getDomains(true, true)}, {@code createDomain(..., true)}) are
 * documented in docs/untestable-code.md.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class DomainServiceITTest extends ITTest {

    @Autowired
    DomainService domainService;

    @Autowired
    DomainLookupService domainLookupService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached, minimal Domain template with the mandatory domainId and name. */
    private Domain newDomain(String domainId, String name) {
        Domain domain = new Domain();
        domain.setDomainId(domainId);
        domain.setName(name);
        return domain;
    }

    // ------------------------------------------------------------------------------------------- //
    // createDomain
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createDomainPersistsRowWithGeneratedIdAndActiveDefault() {
        Domain created = domainService.createDomain(newDomain("IT-domain-create", "IT Create"), false);
        Integer id = created.getId();
        assertNotNull(id, "createDomain must assign a generated id");

        em.flush();
        em.clear();

        Domain reloaded = em.find(Domain.class, id);
        assertNotNull(reloaded, "domain row must exist in the database");
        assertEquals("IT-domain-create", reloaded.getDomainId());
        assertEquals("IT Create", reloaded.getName());
        assertTrue(reloaded.isActive(), "a new domain must default to active");
    }

    @Test
    @Transactional
    void createDomainPersistsAllScalarFields() {
        Domain template = newDomain("IT-domain-fields", "IT Fields");
        template.setSortOrder(42);
        template.setTimeZone("Europe/Copenhagen");
        template.setLatitude(56.5f);
        template.setLongitude(10.5f);
        template.setZoomLevel(7);
        template.setMessageSortOrder("ID ASC");
        template.setColor("#abcdef");
        template.setPublish(Boolean.TRUE);
        template.setAtons(Boolean.TRUE);
        template.setTemplates(Boolean.FALSE);

        Integer id = domainService.createDomain(template, false).getId();
        em.flush();
        em.clear();

        Domain reloaded = em.find(Domain.class, id);
        assertEquals(Integer.valueOf(42), reloaded.getSortOrder());
        assertEquals("Europe/Copenhagen", reloaded.getTimeZone());
        assertEquals(56.5f, reloaded.getLatitude());
        assertEquals(10.5f, reloaded.getLongitude());
        assertEquals(Integer.valueOf(7), reloaded.getZoomLevel());
        assertEquals("ID ASC", reloaded.getMessageSortOrder());
        assertEquals("#abcdef", reloaded.getColor());
        assertEquals(Boolean.TRUE, reloaded.getPublish());
        assertEquals(Boolean.TRUE, reloaded.getAtons());
        assertEquals(Boolean.FALSE, reloaded.getTemplates());
    }

    @Test
    @Transactional
    void createDomainHonoursExplicitInactiveFlag() {
        Domain template = newDomain("IT-domain-inactive", "IT Inactive");
        template.setActive(false);

        Integer id = domainService.createDomain(template, false).getId();
        em.flush();
        em.clear();

        assertFalse(em.find(Domain.class, id).isActive(),
                "an explicitly inactive domain must be persisted as inactive");
    }

    @Test
    @Transactional
    void createDomainWithDuplicateDomainIdThrows() {
        // niord-nw is a stable seed domainId.
        Domain duplicate = newDomain("niord-nw", "IT Duplicate");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> domainService.createDomain(duplicate, false));
        assertTrue(ex.getMessage().contains("niord-nw"),
                "the error must name the duplicate domain id");
    }

    @Test
    @Transactional
    void createDomainWithDuplicateOfFreshlyCreatedDomainIdThrows() {
        domainService.createDomain(newDomain("IT-domain-dup", "IT Dup A"), false);
        em.flush();

        assertThrows(IllegalArgumentException.class,
                () -> domainService.createDomain(newDomain("IT-domain-dup", "IT Dup B"), false),
                "a second domain with the same domainId must be rejected before persistence");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateDomain
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateDomainPersistsAllChangedFields() {
        domainService.createDomain(newDomain("IT-domain-upd", "Before"), false);
        em.flush();

        Domain template = newDomain("IT-domain-upd", "After");
        template.setActive(false);
        template.setSortOrder(9);
        template.setTimeZone("UTC");
        template.setLatitude(1.0f);
        template.setLongitude(2.0f);
        template.setZoomLevel(3);
        template.setMessageSortOrder("AREA ASC");
        template.setColor("#123456");
        template.setPublish(Boolean.TRUE);
        template.setAtons(Boolean.TRUE);
        template.setTemplates(Boolean.TRUE);

        Domain updated = domainService.updateDomain(template);
        Integer id = updated.getId();
        em.flush();
        em.clear();

        Domain reloaded = em.find(Domain.class, id);
        assertEquals("After", reloaded.getName());
        assertFalse(reloaded.isActive());
        assertEquals(Integer.valueOf(9), reloaded.getSortOrder());
        assertEquals("UTC", reloaded.getTimeZone());
        assertEquals(1.0f, reloaded.getLatitude());
        assertEquals(2.0f, reloaded.getLongitude());
        assertEquals(Integer.valueOf(3), reloaded.getZoomLevel());
        assertEquals("AREA ASC", reloaded.getMessageSortOrder());
        assertEquals("#123456", reloaded.getColor());
        assertEquals(Boolean.TRUE, reloaded.getPublish());
        assertEquals(Boolean.TRUE, reloaded.getAtons());
        assertEquals(Boolean.TRUE, reloaded.getTemplates());
    }

    @Test
    @Transactional
    void updateDomainUpdatesExistingRowInsteadOfCreatingNew() {
        Integer originalId = domainService.createDomain(newDomain("IT-domain-same", "Original"), false)
                .getId();
        em.flush();

        Domain template = newDomain("IT-domain-same", "Renamed");
        Domain updated = domainService.updateDomain(template);

        assertEquals(originalId, updated.getId(),
                "update must mutate the existing row, keeping the same primary key");

        em.flush();
        em.clear();
        // Exactly one row must carry this domainId.
        long count = em.createNamedQuery("Domain.findByDomainId", Domain.class)
                .setParameter("domainId", "IT-domain-same")
                .getResultList().size();
        assertEquals(1L, count, "update must not introduce a duplicate row");
        assertEquals("Renamed", em.find(Domain.class, originalId).getName());
    }

    @Test
    @Transactional
    void updateDomainClearsNullableFields() {
        Domain template = newDomain("IT-domain-clear", "IT Clear");
        template.setColor("#ffffff");
        template.setMessageSortOrder("ID ASC");
        template.setSortOrder(5);
        Integer id = domainService.createDomain(template, false).getId();
        em.flush();

        // Updating with the nullable fields left null must blank them in the database.
        Domain cleared = newDomain("IT-domain-clear", "IT Clear");
        domainService.updateDomain(cleared);
        em.flush();
        em.clear();

        Domain reloaded = em.find(Domain.class, id);
        assertNull(reloaded.getColor());
        assertNull(reloaded.getMessageSortOrder());
        assertNull(reloaded.getSortOrder());
    }

    @Test
    @Transactional
    void updateDomainThrowsForUnknownDomainId() {
        Domain template = newDomain("IT-domain-missing", "IT Missing");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> domainService.updateDomain(template));
        assertTrue(ex.getMessage().contains("IT-domain-missing"),
                "the error must name the unknown domain id");
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteDomain
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteDomainRemovesRowAndReturnsTrue() {
        Integer id = domainService.createDomain(newDomain("IT-domain-del", "IT Delete"), false).getId();
        em.flush();

        assertTrue(domainService.deleteDomain("IT-domain-del"),
                "deleting an existing domain must return true");
        em.flush();
        em.clear();

        assertNull(em.find(Domain.class, id), "the deleted domain row must be gone from the database");
        assertNull(domainLookupService.findByDomainId("IT-domain-del"));
    }

    @Test
    @Transactional
    void deleteDomainReturnsFalseForUnknownDomainId() {
        assertFalse(domainService.deleteDomain("IT-domain-does-not-exist"),
                "deleting a non-existing domain must return false");
    }

    @Test
    @Transactional
    void deleteDomainReturnsFalseForNullDomainId() {
        assertFalse(domainService.deleteDomain(null),
                "deleting with a null domain id must return false rather than throw");
    }

    // ------------------------------------------------------------------------------------------- //
    // findByDomainId
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByDomainIdReturnsCreatedDomain() {
        domainService.createDomain(newDomain("IT-domain-find", "IT Find"), false);
        em.flush();

        Domain found = domainLookupService.findByDomainId("IT-domain-find");
        assertNotNull(found);
        assertEquals("IT Find", found.getName());
    }

    @Test
    @Transactional
    void findByDomainIdReadsStableSeedDomain() {
        // A clearly-stable seed read that documents the seed contract.
        Domain nw = domainLookupService.findByDomainId("niord-nw");
        assertNotNull(nw, "the seeded NW domain must be present");
        assertEquals("NW", nw.getName());
        assertTrue(nw.isActive());
    }

    @Test
    @Transactional
    void findByDomainIdReturnsNullForUnknownId() {
        assertNull(domainLookupService.findByDomainId("IT-domain-unknown"));
    }

    @Test
    @Transactional
    void findByDomainIdReturnsNullForNullAndBlank() {
        assertNull(domainLookupService.findByDomainId(null));
        assertNull(domainLookupService.findByDomainId(""));
    }

    /**
     * CATEGORY3: only "no such domain" answers null. A failure of the lookup query's auto-flush, here a new category
     * that names an unsaved domain, is thrown to the caller, not answered as "not found" while the transaction is left
     * to fail at its commit without a cause. The test transaction is rolled back.
     */
    @Test
    @Transactional
    void findByDomainIdThrowsAFailedFlushInsteadOfAnsweringNull() {
        org.niord.core.category.Category category = new org.niord.core.category.Category();
        category.setType(org.niord.core.category.CategoryType.CATEGORY);
        category.createDesc("en").setName("IT-domain-flush");
        category.getDomains().add(newDomain("IT-domain-unsaved", "IT Unsaved"));
        em.persist(category);

        assertThrows(RuntimeException.class, () -> domainLookupService.findByDomainId("niord-nw"));
    }

    // ------------------------------------------------------------------------------------------- //
    // findByDomainIds
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByDomainIdsReturnsMatchingSubset() {
        domainService.createDomain(newDomain("IT-domain-ids-1", "IT Ids 1"), false);
        domainService.createDomain(newDomain("IT-domain-ids-2", "IT Ids 2"), false);
        em.flush();

        List<Domain> result = domainLookupService.findByDomainIds(
                Set.of("IT-domain-ids-1", "IT-domain-ids-2"));
        Set<String> ids = result.stream().map(Domain::getDomainId).collect(Collectors.toSet());
        assertEquals(Set.of("IT-domain-ids-1", "IT-domain-ids-2"), ids);
    }

    @Test
    @Transactional
    void findByDomainIdsIgnoresUnknownIds() {
        domainService.createDomain(newDomain("IT-domain-ids-known", "IT Known"), false);
        em.flush();

        List<Domain> result = domainLookupService.findByDomainIds(
                Set.of("IT-domain-ids-known", "IT-domain-ids-absent"));
        assertEquals(1, result.size());
        assertEquals("IT-domain-ids-known", result.get(0).getDomainId());
    }

    @Test
    @Transactional
    void findByDomainIdsReturnsEmptyForNullOrEmptyInput() {
        assertTrue(domainLookupService.findByDomainIds(null).isEmpty(),
                "a null id set must yield an empty list, not a query");
        assertTrue(domainLookupService.findByDomainIds(Set.of()).isEmpty(),
                "an empty id set must yield an empty list, not a query");
    }

    // ------------------------------------------------------------------------------------------- //
    // getDomains(false, false)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getDomainsIncludesNewlyCreatedActiveDomain() {
        domainService.createDomain(newDomain("IT-domain-active-list", "IT Active List"), false);
        em.flush();

        Set<String> ids = domainService.getDomains(false, false).stream()
                .map(Domain::getDomainId)
                .collect(Collectors.toSet());
        assertTrue(ids.contains("IT-domain-active-list"),
                "an active domain must appear in the active-only listing");
        // The seed domains are active too, so the listing also contains the stable NW client.
        assertTrue(ids.contains("niord-nw"),
                "the seeded active NW domain must appear in the active-only listing");
    }

    @Test
    @Transactional
    void getDomainsExcludesInactiveDomains() {
        Domain inactive = newDomain("IT-domain-inactive-list", "IT Inactive List");
        inactive.setActive(false);
        domainService.createDomain(inactive, false);
        em.flush();

        Set<String> ids = domainService.getDomains(false, false).stream()
                .map(Domain::getDomainId)
                .collect(Collectors.toSet());
        assertFalse(ids.contains("IT-domain-inactive-list"),
                "an inactive domain must be excluded from getDomains(false, false)");
    }

    @Test
    @Transactional
    void getDomainsIsOrderedBySortOrderAscending() {
        // Two new domains with explicit, widely-separated sort orders.
        Domain low = newDomain("IT-domain-order-low", "IT Order Low");
        low.setSortOrder(1001);
        Domain high = newDomain("IT-domain-order-high", "IT Order High");
        high.setSortOrder(1002);
        // Create the higher one first to prove ordering is by sortOrder, not insertion order.
        domainService.createDomain(high, false);
        domainService.createDomain(low, false);
        em.flush();

        List<String> ordered = domainService.getDomains(false, false).stream()
                .map(Domain::getDomainId)
                .filter(id -> id.startsWith("IT-domain-order-"))
                .collect(Collectors.toList());
        assertEquals(List.of("IT-domain-order-low", "IT-domain-order-high"), ordered,
                "domains must be returned ordered by ascending sortOrder");
    }
}
