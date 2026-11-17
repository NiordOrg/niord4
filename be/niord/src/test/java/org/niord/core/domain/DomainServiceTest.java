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
package org.niord.core.domain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.user.CurrentUserContext;
import org.niord.core.user.TicketService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the two request-context decisions of {@link DomainService}: which domain a request is
 * in ({@link DomainService#currentDomain()}), and which header values name a domain whose roles may be
 * added to a caller ({@link DomainService#isActiveDomain(String)}).
 *
 * <p>{@code currentDomain()} is the one method every domain-scoped access check reads — message viewing,
 * the domain-user gates of the message search, the message export and report, and the domain a new
 * ticket is bound to. A bearer caller's domain comes from the {@code NiordDomain} header, which the
 * {@code NiordDomainFilter} pairs with the roles the token holds on that domain. A caller whose only
 * credential is a resolved ticket holds the roles the ticket was issued with, checked against the
 * ticket's domain; for it the ticket's domain must win over the header, or a header naming another
 * domain would pair that domain with roles never checked there.
 *
 * <p>The collaborators are Mockito doubles (no container), in the style of
 * {@code UserServiceIsCallerInRoleTest}. The header is parked on the thread-local exactly as the filter
 * parks it, and removed after each test.
 */
class DomainServiceTest {

    private static final String TICKET_DOMAIN = "niord-client-nm";
    private static final String HEADER_DOMAIN = "niord-client-nw";

    private final Domain ticketDomain = domain(TICKET_DOMAIN, true);
    private final Domain headerDomain = domain(HEADER_DOMAIN, true);

    private DomainService service;
    private CurrentUserContext currentUserContext;
    private TicketService ticketService;

    private static Domain domain(String domainId, boolean active) {
        Domain domain = new Domain();
        domain.setDomainId(domainId);
        domain.setActive(active);
        return domain;
    }

    @BeforeEach
    void setUp() {
        service = new DomainService();
        currentUserContext = mock(CurrentUserContext.class);
        ticketService = mock(TicketService.class);
        DomainLookupService lookup = mock(DomainLookupService.class);
        when(lookup.findByDomainId(TICKET_DOMAIN)).thenReturn(ticketDomain);
        when(lookup.findByDomainId(HEADER_DOMAIN)).thenReturn(headerDomain);
        when(lookup.findByDomainId("niord-client-inactive")).thenReturn(domain("niord-client-inactive", false));
        service.currentUserContext = currentUserContext;
        service.ticketService = ticketService;
        service.domainLookupService = lookup;
    }

    @AfterEach
    void clearParkedDomain() {
        service.removeDomainForCurrentThread();
    }

    /** A resolved ticket issued for the given domain. */
    private void ticketFor(String domainId) {
        TicketService.TicketData ticket = mock(TicketService.TicketData.class);
        when(ticket.getDomain()).thenReturn(domainId);
        when(ticketService.getTicketDataForCurrentThread()).thenReturn(ticket);
    }

    private void header(String domainId) {
        service.setDomainForCurrentThread(domainId);
    }

    // ------------------------------------------------------------------------------------------- //
    // currentDomain()
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aTicketOnlyCallerIsInTheTicketsDomainWhateverTheHeaderSays() {
        when(currentUserContext.isAnonymous()).thenReturn(true);
        ticketFor(TICKET_DOMAIN);
        header(HEADER_DOMAIN);

        assertSame(ticketDomain, service.currentDomain(),
                "the ticket's roles were checked against the ticket's domain, so that domain must win");
    }

    @Test
    void aTicketOnlyCallerWhoseTicketNamesNoDomainIsInNoDomainWhateverTheHeaderSays() {
        // A ticket issued without a domain carries roles checked against no domain: the header must not
        // supply one.
        when(currentUserContext.isAnonymous()).thenReturn(true);
        ticketFor(null);
        header(HEADER_DOMAIN);

        assertNull(service.currentDomain());
    }

    @Test
    void aBearerCallerIsInTheHeadersDomainEvenWithATicket() {
        // For a bearer caller the header and the roles on it arrive together (NiordDomainFilter), so the
        // header keeps its precedence.
        when(currentUserContext.isAnonymous()).thenReturn(false);
        ticketFor(TICKET_DOMAIN);
        header(HEADER_DOMAIN);

        assertSame(headerDomain, service.currentDomain());
    }

    @Test
    void aTicketWithoutAHeaderResolvesTheTicketsDomain() {
        when(currentUserContext.isAnonymous()).thenReturn(true);
        ticketFor(TICKET_DOMAIN);

        assertSame(ticketDomain, service.currentDomain());
    }

    @Test
    void aBearerCallerWithATicketButNoHeaderFallsBackToTheTicketsDomain() {
        when(currentUserContext.isAnonymous()).thenReturn(false);
        ticketFor(TICKET_DOMAIN);

        assertSame(ticketDomain, service.currentDomain());
    }

    @Test
    void aHeaderWithoutATicketResolvesTheHeadersDomain() {
        when(currentUserContext.isAnonymous()).thenReturn(true);
        header(HEADER_DOMAIN);

        assertSame(headerDomain, service.currentDomain());
    }

    @Test
    void neitherHeaderNorTicketResolvesNoDomain() {
        when(currentUserContext.isAnonymous()).thenReturn(true);

        assertNull(service.currentDomain());
    }

    // ------------------------------------------------------------------------------------------- //
    // isActiveDomain(String)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anActiveStoredDomainIsActive() {
        assertTrue(service.isActiveDomain(HEADER_DOMAIN));
    }

    @Test
    void anInactiveStoredDomainIsNotActive() {
        assertFalse(service.isActiveDomain("niord-client-inactive"),
                "a deactivated domain's Keycloak roles must no longer be added");
    }

    @Test
    void aClientThatIsNotADomainIsNotActive() {
        // e.g. the SPA client itself, which defines roles of its own
        assertFalse(service.isActiveDomain("niord-web"));
    }

    @Test
    void aBlankIdIsNotActive() {
        assertFalse(service.isActiveDomain(null));
        assertFalse(service.isActiveDomain("  "));
    }
}
