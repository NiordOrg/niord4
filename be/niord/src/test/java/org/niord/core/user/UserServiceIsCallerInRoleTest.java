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
package org.niord.core.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserService#isCallerInRole(String)} on the ticket-authenticated branch (TICKET1).
 *
 * <p>A ticket issued with no {@code role} parameter (the common download-link flow) has
 * {@code TicketData.roles == null}. The original code ran {@code Arrays.asList((String[]) null)} which
 * threw a {@link NullPointerException} → HTTP 500. The null-guard makes a role-less ticket grant no role
 * rather than crashing — a ticket only carries a role once {@code TicketRestService} verified the issuing
 * caller held it, so "no roles" means "never checked". These tests use Mockito doubles for the injected
 * collaborators (no container).
 */
class UserServiceIsCallerInRoleTest {

    private UserService serviceWithTicket(TicketService.TicketData ticketData) {
        UserService svc = new UserService();
        CurrentUserContext ctx = mock(CurrentUserContext.class);
        when(ctx.isAnonymous()).thenReturn(true);
        TicketService ticketService = mock(TicketService.class);
        when(ticketService.getTicketDataForCurrentThread()).thenReturn(ticketData);
        svc.currentUserContext = ctx;
        svc.ticketService = ticketService;
        return svc;
    }

    @Test
    void roleLessTicketWithNullRolesGrantsNoRoleInsteadOfNpe() {
        TicketService.TicketData td = mock(TicketService.TicketData.class);
        when(td.getUser()).thenReturn("bob");
        when(td.getRoles()).thenReturn(null); // role-less ticket

        UserService svc = serviceWithTicket(td);

        // Before the fix this threw NullPointerException (HTTP 500); now it returns false.
        assertFalse(svc.isCallerInRole("editor"),
                "a role-less ticket must not NPE and must grant no role");
    }

    @Test
    void ticketWithMatchingRoleStillGrantsThatRoleOnly() {
        TicketService.TicketData td = mock(TicketService.TicketData.class);
        when(td.getUser()).thenReturn("bob");
        when(td.getRoles()).thenReturn(new String[]{"editor"});

        UserService svc = serviceWithTicket(td);

        assertTrue(svc.isCallerInRole("editor"), "the ticket's own role is granted");
        assertFalse(svc.isCallerInRole("admin"), "a role the ticket does not carry is not granted");
    }
}
