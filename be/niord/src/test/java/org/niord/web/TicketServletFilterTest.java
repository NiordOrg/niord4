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
package org.niord.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.user.TicketService;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketServletFilter}, which inspects the {@code ticket} request parameter
 * and, when {@link TicketService#resolveTicketForCurrentThread(String)} accepts it, binds the
 * resolved ticket data (domain, user, roles) to a thread local for the duration of the request,
 * removing it again in a {@code finally} once the chain has run.
 *
 * <p>{@code TicketService} is a Mockito stub so each branch can be asserted exactly: the parameter
 * is always read and passed to the resolver; on a positive resolution the chain proceeds and the
 * thread local is cleared afterwards (even if the chain throws); on a negative resolution the chain
 * still proceeds but the thread local is never touched. The request/response/chain are stubs so the
 * test never needs a running servlet container.
 */
public class TicketServletFilterTest {

    private TicketServletFilter filter;
    private TicketService ticketService;

    @BeforeEach
    public void setUp() {
        filter = new TicketServletFilter();
        filter.log = LoggerFactory.getLogger("ticket-test");
        ticketService = mock(TicketService.class);
        filter.ticketService = ticketService;
    }

    private HttpServletRequest request(String ticket) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getParameter("ticket")).thenReturn(ticket);
        // Used only on the resolved path, in the log line; stub it so that path never NPEs.
        when(req.getRequestURI()).thenReturn("/rest/some/endpoint");
        return req;
    }

    @Test
    public void resolvedTicketProceedsThenClearsThreadLocal() throws Exception {
        HttpServletRequest req = request("good-ticket");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(ticketService.resolveTicketForCurrentThread(any())).thenReturn(true);

        filter.doFilter(req, res, chain);

        // The ticket parameter must be read and handed to the resolver.
        verify(req).getParameter(eq("ticket"));
        verify(ticketService).resolveTicketForCurrentThread(eq("good-ticket"));
        // Request continues down the chain...
        verify(chain).doFilter(req, res);
        // ...and the resolved thread local is removed afterwards.
        verify(ticketService).removeTicketForCurrentThread();
    }

    @Test
    public void unresolvedTicketProceedsButNeverClearsThreadLocal() throws Exception {
        HttpServletRequest req = request(null);
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(ticketService.resolveTicketForCurrentThread(any())).thenReturn(false);

        filter.doFilter(req, res, chain);

        verify(req).getParameter(eq("ticket"));
        verify(ticketService).resolveTicketForCurrentThread(eq(null));
        // The chain still runs even with no ticket...
        verify(chain).doFilter(req, res);
        // ...but nothing was bound, so nothing is removed.
        verify(ticketService, never()).removeTicketForCurrentThread();
    }

    @Test
    public void resolvedTicketIsClearedEvenWhenChainThrows() throws Exception {
        HttpServletRequest req = request("good-ticket");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(ticketService.resolveTicketForCurrentThread(any())).thenReturn(true);
        ServletException boom = new ServletException("downstream failure");
        doThrow(boom).when(chain).doFilter(any(), any());

        try {
            filter.doFilter(req, res, chain);
            fail("expected the downstream ServletException to propagate");
        } catch (ServletException ex) {
            // The exact exception from the chain must surface to the caller.
            assertSame(boom, ex);
        }

        // The finally block must still clear the resolved thread local on the failure path.
        verify(ticketService).removeTicketForCurrentThread();
    }
}
