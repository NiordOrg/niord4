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
package org.niord.core.user;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.test.ITTest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link TicketService}, run against the {@link ITTest} infrastructure.
 *
 * <p>Unlike most Niord services, {@code TicketService} stores its state in an in-memory Infinispan
 * cache (not the database), so these tests do not use {@code @Transactional}. State isolation is
 * instead achieved by:
 * <ul>
 *   <li>using a brand-new random ticket per {@code createTicket} call (so cache entries never
 *       collide between tests), and</li>
 *   <li>clearing the per-thread {@code TicketData} in an {@link AfterEach} hook, so a resolved
 *       ticket from one test cannot leak into the next.</li>
 * </ul>
 *
 * <p>The seeded domains (e.g. {@code niord-nw}) are stable reads used to obtain a real
 * {@link Domain}; users are not seeded, so a detached {@link User} is constructed in-memory — this
 * is sufficient because {@code createTicket} only reads {@link User#getUsername()}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class TicketServiceITTest extends ITTest {

    @Autowired
    TicketService ticketService;

    @Autowired
    DomainLookupService domainLookupService;

    /** Builds a detached User with only the username populated (the only field createTicket reads). */
    private User newUser(String username) {
        User user = new User();
        user.setUsername(username);
        return user;
    }

    /**
     * The per-thread ticket data lives in a ThreadLocal that survives between tests on the same
     * thread, so always clear it after each test to keep the tests independent.
     */
    @AfterEach
    void clearThreadLocal() {
        ticketService.removeTicketForCurrentThread();
    }

    // ------------------------------------------------------------------------------------------- //
    // createTicket
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createTicketReturnsNonBlankUniqueTickets() {
        Domain domain = domainLookupService.findByDomainId("niord-nw");
        assertNotNull(domain, "seed data must provide the niord-nw domain");

        String ticket1 = ticketService.createTicket(domain, newUser("IT-user-1"));
        String ticket2 = ticketService.createTicket(domain, newUser("IT-user-1"));

        assertNotNull(ticket1);
        assertFalse(ticket1.isBlank(), "a ticket must be a non-blank handle");
        assertNotEquals(ticket1, ticket2, "each createTicket call must mint a unique ticket");
    }

    @Test
    void resolveTicketExposesDomainUserAndRoles() {
        Domain domain = domainLookupService.findByDomainId("niord-nw");
        String ticket = ticketService.createTicket(domain, newUser("IT-resolve-user"), "editor", "admin");

        assertTrue(ticketService.resolveTicketForCurrentThread(ticket),
                "a freshly issued ticket must resolve");

        TicketService.TicketData data = ticketService.getTicketDataForCurrentThread();
        assertNotNull(data, "resolving a valid ticket must populate the thread-local ticket data");
        assertEquals("niord-nw", data.getDomain(), "ticket data must carry the domain id");
        assertEquals("IT-resolve-user", data.getUser(), "ticket data must carry the username");
        assertArrayEquals(new String[]{"editor", "admin"}, data.getRoles(),
                "ticket data must carry the roles in the order supplied");
    }

    @Test
    void createTicketStoresDomainIdNotDomainObject() {
        // The TicketData persists the domain *id*, not a reference to the entity, so a different
        // Domain instance with the same id is indistinguishable.
        Domain nm = domainLookupService.findByDomainId("niord-nm");
        String ticket = ticketService.createTicket(nm, newUser("IT-domid-user"));

        assertTrue(ticketService.resolveTicketForCurrentThread(ticket));
        assertEquals("niord-nm", ticketService.getTicketDataForCurrentThread().getDomain());
    }

    @Test
    void createTicketWithNoRolesStoresEmptyRoleArray() {
        Domain domain = domainLookupService.findByDomainId("niord-nw");
        String ticket = ticketService.createTicket(domain, newUser("IT-noroles-user"));

        assertTrue(ticketService.resolveTicketForCurrentThread(ticket));
        String[] roles = ticketService.getTicketDataForCurrentThread().getRoles();
        assertNotNull(roles, "no roles must yield an empty array, not null");
        assertEquals(0, roles.length, "no roles supplied must produce an empty role array");
    }

    @Test
    void createTicketWithNullDomainStoresNullDomainId() {
        String ticket = ticketService.createTicket(null, newUser("IT-nulldomain-user"));

        assertTrue(ticketService.resolveTicketForCurrentThread(ticket));
        TicketService.TicketData data = ticketService.getTicketDataForCurrentThread();
        assertNull(data.getDomain(), "a null domain must store a null domain id");
        assertEquals("IT-nulldomain-user", data.getUser());
    }

    @Test
    void createTicketWithNullUserStoresNullUsername() {
        Domain domain = domainLookupService.findByDomainId("niord-nw");
        String ticket = ticketService.createTicket(domain, null);

        assertTrue(ticketService.resolveTicketForCurrentThread(ticket));
        TicketService.TicketData data = ticketService.getTicketDataForCurrentThread();
        assertNull(data.getUser(), "a null user must store a null username");
        assertEquals("niord-nw", data.getDomain());
    }

    @Test
    void createTicketWithNullDomainAndUserStoresBothNull() {
        // Cast the first null to Domain so the (Domain, User, String...) overload is chosen
        // unambiguously over the varargs-only createTicket(String...).
        String ticket = ticketService.createTicket((Domain) null, null, "viewer");

        assertTrue(ticketService.resolveTicketForCurrentThread(ticket));
        TicketService.TicketData data = ticketService.getTicketDataForCurrentThread();
        assertNull(data.getDomain());
        assertNull(data.getUser());
        assertArrayEquals(new String[]{"viewer"}, data.getRoles(),
                "roles must still be retained even when domain and user are null");
    }

    // ------------------------------------------------------------------------------------------- //
    // resolveTicketForCurrentThread - one-time use & validation
    // ------------------------------------------------------------------------------------------- //

    @Test
    void resolvingTheSameTicketTwiceFailsTheSecondTime() {
        Domain domain = domainLookupService.findByDomainId("niord-nw");
        String ticket = ticketService.createTicket(domain, newUser("IT-onetime-user"), "editor");

        assertTrue(ticketService.resolveTicketForCurrentThread(ticket),
                "the first resolve of a valid ticket must succeed");

        // The ticket is one-time-use: it is removed from the cache on the first resolve.
        assertFalse(ticketService.resolveTicketForCurrentThread(ticket),
                "a ticket may only be resolved once");
    }

    @Test
    void secondResolveDoesNotOverwriteThreadLocalWithNull() {
        // A failed (second) resolve returns false, but it must NOT clear the thread-local data set
        // by the successful first resolve - resolveTicketForCurrentThread only sets the thread-local
        // when a ticket is actually found.
        Domain domain = domainLookupService.findByDomainId("niord-nw");
        String ticket = ticketService.createTicket(domain, newUser("IT-keep-user"), "editor");

        assertTrue(ticketService.resolveTicketForCurrentThread(ticket));
        assertFalse(ticketService.resolveTicketForCurrentThread(ticket));

        TicketService.TicketData data = ticketService.getTicketDataForCurrentThread();
        assertNotNull(data, "the failed re-resolve must leave the original ticket data intact");
        assertEquals("IT-keep-user", data.getUser());
    }

    @Test
    void resolveUnknownTicketReturnsFalseAndSetsNoData() {
        assertFalse(ticketService.resolveTicketForCurrentThread("IT-this-ticket-was-never-issued"),
                "an unknown ticket must not resolve");
        assertNull(ticketService.getTicketDataForCurrentThread(),
                "a failed resolve must leave the thread-local empty");
    }

    @Test
    void resolveNullTicketReturnsFalse() {
        assertFalse(ticketService.resolveTicketForCurrentThread(null));
        assertNull(ticketService.getTicketDataForCurrentThread());
    }

    @Test
    void resolveBlankTicketReturnsFalse() {
        assertFalse(ticketService.resolveTicketForCurrentThread("   "),
                "a blank ticket is treated as no ticket");
        assertNull(ticketService.getTicketDataForCurrentThread());
    }

    @Test
    void resolvingOneTicketDoesNotConsumeAnother() {
        Domain domain = domainLookupService.findByDomainId("niord-nw");
        String ticketA = ticketService.createTicket(domain, newUser("IT-multi-A"), "editor");
        String ticketB = ticketService.createTicket(domain, newUser("IT-multi-B"), "admin");

        // Resolving A must not invalidate B.
        assertTrue(ticketService.resolveTicketForCurrentThread(ticketA));
        assertEquals("IT-multi-A", ticketService.getTicketDataForCurrentThread().getUser());

        assertTrue(ticketService.resolveTicketForCurrentThread(ticketB),
                "resolving one ticket must not consume an unrelated ticket");
        assertEquals("IT-multi-B", ticketService.getTicketDataForCurrentThread().getUser());
    }

    @Test
    void resolvingASecondTicketReplacesTheThreadLocalData() {
        Domain domain = domainLookupService.findByDomainId("niord-nw");
        String ticketA = ticketService.createTicket(domain, newUser("IT-replace-A"), "editor");
        String ticketB = ticketService.createTicket(domain, newUser("IT-replace-B"), "admin");

        assertTrue(ticketService.resolveTicketForCurrentThread(ticketA));
        assertTrue(ticketService.resolveTicketForCurrentThread(ticketB));

        // The most recently resolved ticket wins for the thread-local data.
        assertEquals("IT-replace-B", ticketService.getTicketDataForCurrentThread().getUser());
    }

    // ------------------------------------------------------------------------------------------- //
    // getTicketDataForCurrentThread / removeTicketForCurrentThread
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getTicketDataIsNullBeforeAnyResolve() {
        assertNull(ticketService.getTicketDataForCurrentThread(),
                "with no ticket resolved on this thread, the ticket data must be null");
    }

    @Test
    void removeTicketClearsTheThreadLocalData() {
        Domain domain = domainLookupService.findByDomainId("niord-nw");
        String ticket = ticketService.createTicket(domain, newUser("IT-remove-user"), "editor");
        assertTrue(ticketService.resolveTicketForCurrentThread(ticket));
        assertNotNull(ticketService.getTicketDataForCurrentThread());

        ticketService.removeTicketForCurrentThread();

        assertNull(ticketService.getTicketDataForCurrentThread(),
                "removeTicketForCurrentThread must clear the resolved ticket data");
    }

    @Test
    void removeTicketIsIdempotentWhenNoTicketResolved() {
        // Calling remove without a prior resolve must not throw and must leave the state empty.
        ticketService.removeTicketForCurrentThread();
        assertNull(ticketService.getTicketDataForCurrentThread());
    }

}
