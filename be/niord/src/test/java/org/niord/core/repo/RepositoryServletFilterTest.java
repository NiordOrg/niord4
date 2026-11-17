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
package org.niord.core.repo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jboss.resteasy.spi.UnhandledException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RepositoryServletFilter}, which proxies the filter chain but silently
 * swallows the specific {@link UnhandledException} that wraps a "Broken pipe" {@link IOException}
 * (raised when a client - e.g. Safari streaming repository videos - closes the connection early),
 * while re-throwing every other {@code UnhandledException} unchanged.
 *
 * <p>The collaborators are isolated as Mockito stubs: the request/response are mocks, and the
 * {@code chain} is a mock whose {@code doFilter} is made to throw the exact exception variant under
 * test via {@code doThrow(...)}. The contract verified is purely the catch-branch decision: only a
 * cause that is an {@code IOException} with the message {@code "Broken pipe"} is swallowed; any
 * other cause type, or any other IOException message, must propagate the original exception.
 */
public class RepositoryServletFilterTest {

    private RepositoryServletFilter filter;
    private HttpServletRequest req;
    private HttpServletResponse res;
    private FilterChain chain;

    @BeforeEach
    public void setUp() {
        filter = new RepositoryServletFilter();
        filter.log = LoggerFactory.getLogger("repository-filter-test");

        req = mock(HttpServletRequest.class);
        res = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
    }

    @Test
    public void normalChainProceedsWithoutThrowing() throws Exception {
        // A chain that completes normally must pass straight through.
        filter.doFilter(req, res, chain);

        // The request is forwarded down the chain exactly once with the same request/response.
        verify(chain).doFilter(req, res);
    }

    @Test
    public void brokenPipeUnhandledExceptionIsSwallowed() throws Exception {
        // The exact swallow condition: UnhandledException whose cause is an IOException("Broken pipe").
        UnhandledException brokenPipe = new UnhandledException(new IOException("Broken pipe"));
        doThrow(brokenPipe).when(chain).doFilter(any(), any());
        // The filter reads the request URI on the swallow path for its trace log.
        when(req.getRequestURI()).thenReturn("/rest/repo/video/clip.mp4");

        // No exception must escape: the broken-pipe noise is intentionally suppressed.
        filter.doFilter(req, res, chain);

        // The chain was still invoked (the exception came from it, not before).
        verify(chain).doFilter(req, res);
    }

    @Test
    public void otherIOExceptionMessageIsRethrown() throws Exception {
        // Same wrapper type, but a different IOException message -> must NOT be swallowed.
        UnhandledException diskFull = new UnhandledException(new IOException("disk full"));
        doThrow(diskFull).when(chain).doFilter(any(), any());

        try {
            filter.doFilter(req, res, chain);
            fail("Expected the UnhandledException with a non-broken-pipe IOException cause to propagate");
        } catch (UnhandledException ex) {
            // The original exception instance must propagate unchanged.
            assertSame(diskFull, ex);
        }

        verify(chain).doFilter(req, res);
    }

    @Test
    public void nonIOExceptionCauseIsRethrown() throws Exception {
        // The wrapped cause is not an IOException at all -> must NOT be swallowed.
        UnhandledException runtimeWrapped = new UnhandledException(new RuntimeException("x"));
        doThrow(runtimeWrapped).when(chain).doFilter(any(), any());

        try {
            filter.doFilter(req, res, chain);
            fail("Expected the UnhandledException with a non-IOException cause to propagate");
        } catch (UnhandledException ex) {
            // The original exception instance must propagate unchanged.
            assertSame(runtimeWrapped, ex);
        }

        verify(chain).doFilter(req, res);
    }
}
