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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the lookup/listing methods of {@link DomainService} that the sibling
 * {@code DomainServiceITTest} does not cover: {@code getPublishedDomains()},
 * {@code persistedDomains(List)} and the {@code includeInactive} branch of
 * {@code getDomains(includeInactive, keycloakState)}.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the
 * shared seed database pristine and the tests independent. Test domains use an {@code IT-domain-}
 * domainId prefix that does not occur in the seed data, so lookups stay isolated from the seeded
 * clients, and assertions filter on that prefix to remain robust against the seed contents.
 *
 * <p>{@code keycloakState} is always passed as {@code false}, so the Keycloak-coupled branch of
 * {@code getDomains} is intentionally skipped; that path is documented in
 * docs/untestable-code.md / docs/framework-coupled-tests.md.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class DomainServiceGapITTest extends ITTest {

    @Autowired
    DomainService domainService;

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
    // getPublishedDomains
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getPublishedDomainsIncludesPublishedActiveDomain() {
        Domain published = newDomain("IT-domain-pub-yes", "IT Pub Yes");
        published.setPublish(Boolean.TRUE);
        published.setActive(true);
        domainService.createDomain(published, false);
        em.flush();
        em.clear();

        Set<String> ids = domainService.getPublishedDomains().stream()
                .map(Domain::getDomainId)
                .collect(Collectors.toSet());
        assertTrue(ids.contains("IT-domain-pub-yes"),
                "a publish=true & active=true domain must appear in getPublishedDomains()");
    }

    @Test
    @Transactional
    void getPublishedDomainsExcludesPublishedButInactiveDomain() {
        Domain inactive = newDomain("IT-domain-pub-inactive", "IT Pub Inactive");
        inactive.setPublish(Boolean.TRUE);
        inactive.setActive(false);
        domainService.createDomain(inactive, false);
        em.flush();
        em.clear();

        Set<String> ids = domainService.getPublishedDomains().stream()
                .map(Domain::getDomainId)
                .collect(Collectors.toSet());
        assertFalse(ids.contains("IT-domain-pub-inactive"),
                "a publish=true but active=false domain must be excluded from getPublishedDomains()");
    }

    @Test
    @Transactional
    void getPublishedDomainsExcludesUnpublishedDomains() {
        // publish == false
        Domain unpublished = newDomain("IT-domain-pub-no", "IT Pub No");
        unpublished.setPublish(Boolean.FALSE);
        unpublished.setActive(true);
        domainService.createDomain(unpublished, false);

        // publish == null (the default)
        Domain nullPublish = newDomain("IT-domain-pub-null", "IT Pub Null");
        nullPublish.setActive(true);
        domainService.createDomain(nullPublish, false);
        em.flush();
        em.clear();

        Set<String> ids = domainService.getPublishedDomains().stream()
                .map(Domain::getDomainId)
                .collect(Collectors.toSet());
        assertFalse(ids.contains("IT-domain-pub-no"),
                "a publish=false domain must be excluded from getPublishedDomains()");
        assertFalse(ids.contains("IT-domain-pub-null"),
                "a publish=null domain must be excluded from getPublishedDomains()");
    }

    @Test
    @Transactional
    void getPublishedDomainsIsOrderedBySortOrderAscending() {
        // Two published+active domains created in reverse sortOrder to prove the named query orders.
        Domain high = newDomain("IT-domain-pub-order-high", "IT Pub Order High");
        high.setPublish(Boolean.TRUE);
        high.setActive(true);
        high.setSortOrder(2002);

        Domain low = newDomain("IT-domain-pub-order-low", "IT Pub Order Low");
        low.setPublish(Boolean.TRUE);
        low.setActive(true);
        low.setSortOrder(2001);

        // Create the higher one first to prove ordering is by sortOrder, not insertion order.
        domainService.createDomain(high, false);
        domainService.createDomain(low, false);
        em.flush();
        em.clear();

        List<String> ordered = domainService.getPublishedDomains().stream()
                .map(Domain::getDomainId)
                .filter(id -> id.startsWith("IT-domain-pub-order-"))
                .collect(Collectors.toList());
        assertEquals(List.of("IT-domain-pub-order-low", "IT-domain-pub-order-high"), ordered,
                "published domains must be returned ordered by ascending sortOrder");
    }

    // ------------------------------------------------------------------------------------------- //
    // persistedDomains
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void persistedDomainsReturnsOnlyManagedRowsForKnownIdsAndDropsUnknown() {
        // One persisted domain whose domainId the templates will reference, plus an unknown id.
        Domain known = newDomain("IT-domain-persisted-known", "IT Persisted Known");
        Integer knownId = domainService.createDomain(known, false).getId();
        em.flush();
        em.clear();

        // Detached templates: one matches the persisted row, one references a non-existent id.
        Domain knownTemplate = newDomain("IT-domain-persisted-known", "ignored name");
        Domain unknownTemplate = newDomain("IT-domain-persisted-unknown", "ignored name");

        List<Domain> result = domainService.persistedDomains(
                List.of(knownTemplate, unknownTemplate));

        assertEquals(1, result.size(),
                "persistedDomains must drop templates whose domainId is not persisted");
        Domain resolved = result.get(0);
        assertEquals("IT-domain-persisted-known", resolved.getDomainId());
        // It must be the managed persisted row (matching id + persisted name), not the template.
        assertEquals(knownId, resolved.getId(),
                "the returned domain must be the managed persisted row, not the detached template");
        assertEquals("IT Persisted Known", resolved.getName(),
                "the returned domain must carry the persisted name, not the template name");
    }

    @Test
    @Transactional
    void persistedDomainsResolvesSeededDomainAndDropsUnknown() {
        // niord-nw is a stable seed domainId; the second id does not exist.
        Domain seedTemplate = newDomain("niord-nw", "ignored");
        Domain unknownTemplate = newDomain("IT-domain-persisted-absent", "ignored");

        List<Domain> result = domainService.persistedDomains(
                List.of(seedTemplate, unknownTemplate));

        assertEquals(1, result.size(),
                "only the persisted seed domain must be resolved");
        Domain resolved = result.get(0);
        assertEquals("niord-nw", resolved.getDomainId());
        assertEquals("NW", resolved.getName(),
                "the returned seed domain must carry its persisted name");
        assertNotNull(resolved.getId(), "a resolved persisted domain must have a database id");
    }

    @Test
    @Transactional
    void persistedDomainsReturnsEmptyWhenNoneMatch() {
        List<Domain> result = domainService.persistedDomains(List.of(
                newDomain("IT-domain-none-1", "ignored"),
                newDomain("IT-domain-none-2", "ignored")));
        assertTrue(result.isEmpty(),
                "persistedDomains must return an empty list when no template id is persisted");
    }

    @Test
    @Transactional
    void persistedDomainsReturnsEmptyForEmptyInput() {
        assertTrue(domainService.persistedDomains(List.of()).isEmpty(),
                "an empty template list must produce an empty result");
    }

    // ------------------------------------------------------------------------------------------- //
    // getDomains(includeInactive, false) -- includeInactive flag selects the right named query
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getDomainsWithIncludeInactiveTrueListsInactiveDomain() {
        Domain inactive = newDomain("IT-domain-flag-inactive", "IT Flag Inactive");
        inactive.setActive(false);
        domainService.createDomain(inactive, false);
        em.flush();
        em.clear();

        Set<String> ids = domainService.getDomains(true, false).stream()
                .map(Domain::getDomainId)
                .collect(Collectors.toSet());
        assertTrue(ids.contains("IT-domain-flag-inactive"),
                "getDomains(true, false) must include an inactive domain via Domain.findAll");
    }

    @Test
    @Transactional
    void getDomainsWithIncludeInactiveFalseExcludesInactiveDomain() {
        Domain inactive = newDomain("IT-domain-flag-active-only", "IT Flag Active Only");
        inactive.setActive(false);
        domainService.createDomain(inactive, false);
        em.flush();
        em.clear();

        Set<String> ids = domainService.getDomains(false, false).stream()
                .map(Domain::getDomainId)
                .collect(Collectors.toSet());
        assertFalse(ids.contains("IT-domain-flag-active-only"),
                "getDomains(false, false) must exclude an inactive domain via Domain.findActive");
    }

    @Test
    @Transactional
    void getDomainsIncludeInactiveFlagDistinguishesActiveAndInactiveDomains() {
        Domain active = newDomain("IT-domain-flag-both-active", "IT Flag Both Active");
        active.setActive(true);
        domainService.createDomain(active, false);

        Domain inactive = newDomain("IT-domain-flag-both-inactive", "IT Flag Both Inactive");
        inactive.setActive(false);
        domainService.createDomain(inactive, false);
        em.flush();
        em.clear();

        Set<String> all = domainService.getDomains(true, false).stream()
                .map(Domain::getDomainId)
                .collect(Collectors.toSet());
        Set<String> activeOnly = domainService.getDomains(false, false).stream()
                .map(Domain::getDomainId)
                .collect(Collectors.toSet());

        // The active domain appears in both listings.
        assertTrue(all.contains("IT-domain-flag-both-active"));
        assertTrue(activeOnly.contains("IT-domain-flag-both-active"));
        // The inactive domain appears only when includeInactive == true.
        assertTrue(all.contains("IT-domain-flag-both-inactive"));
        assertFalse(activeOnly.contains("IT-domain-flag-both-inactive"),
                "the inactive domain must be visible only with includeInactive == true");
    }

    @Test
    @Transactional
    void getDomainsWithKeycloakStateFalseLeavesInKeycloakUnset() {
        Domain created = newDomain("IT-domain-flag-no-kc", "IT Flag No KC");
        created.setActive(true);
        domainService.createDomain(created, false);
        em.flush();
        em.clear();

        // With keycloakState == false the service must not call Keycloak, so inKeycloak stays null.
        Domain resolved = domainService.getDomains(true, false).stream()
                .filter(d -> "IT-domain-flag-no-kc".equals(d.getDomainId()))
                .findFirst()
                .orElseThrow();
        assertEquals(null, resolved.getInKeycloak(),
                "keycloakState == false must skip the Keycloak call, leaving inKeycloak unset");
    }
}
