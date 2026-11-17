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
package org.niord.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.DomainService;
import org.niord.core.util.MdcContext;
import org.niord.test.CountryBuildGroup;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NiordDomainFilter}: the domain parking around the request and the
 * security-critical role augmentation.
 *
 * <p>The role-extraction gate, {@code domainRoles(Jwt, String)}, walks
 * {@code resource_access -> [domain] -> roles} of the <em>verified</em> token and is the
 * "fail closed / no privilege escalation" contract: whenever any link in that chain is missing it
 * returns an empty list so that no roles are added to the identity. The fail-closed matrix mirrors
 * the tests of the (deleted) {@code NiordDomainSecurityIdentityAugmentor}, which decoded the raw
 * {@code Authorization} header instead of reading the verified {@link Jwt}.
 *
 * <p>The filter-level tests drive {@code doFilter(...)} with mock servlet plumbing and assert the
 * observable outcomes: the domain ID parked on (and removed from) the request thread via
 * {@link DomainService}, and the {@link JwtAuthenticationToken} in the {@link SecurityContextHolder}
 * re-decorated with exactly the token's roles on the named domain — and only when
 * {@link DomainService#isActiveDomain(String)} confirms that the header names a stored, active domain.
 * The mocked {@code DomainService} answers {@code false} unless a test says otherwise, so each positive
 * case stubs the domain as active.
 */
@CountryBuildGroup
public class NiordDomainFilterTest {

    private static final String NW = "niord-client-nw";
    private static final String NM = "niord-client-nm";

    private final DomainService domainService = mock(DomainService.class);
    private final NiordDomainFilter filter = new NiordDomainFilter(domainService);

    @AfterEach
    public void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    public void clearMdc() {
        MDC.clear();
    }

    /** Builds a (verified-by-construction) JWT carrying the given claims. */
    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("the-token")
                .header("alg", "none")
                .subject("alice")
                .claim("preferred_username", "alice");
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static Jwt jwtWithResourceAccess(Object resourceAccess) {
        return jwt(Map.of("resource_access", resourceAccess));
    }

    // ------------------------------------------------------------------------------------------------
    // domainRoles(...) positive path: roles present for the requested domain
    // ------------------------------------------------------------------------------------------------

    @Test
    public void domainRoles_returnsRolesForRequestedDomain_inArrayOrder() {
        Jwt token = jwtWithResourceAccess(Map.of(NW, Map.of("roles", List.of("user", "editor"))));

        // Exact contents AND order must match the claim's array.
        assertEquals(List.of("user", "editor"), NiordDomainFilter.domainRoles(token, NW));
    }

    @Test
    public void domainRoles_picksOnlyTheRequestedDomain_whenMultipleClientsPresent() {
        Jwt token = jwtWithResourceAccess(Map.of(
                NW, Map.of("roles", List.of("user")),
                NM, Map.of("roles", List.of("editor", "admin"))));

        assertEquals(List.of("user"), NiordDomainFilter.domainRoles(token, NW),
                "must read roles of the requested client only");
        assertEquals(List.of("editor", "admin"), NiordDomainFilter.domainRoles(token, NM),
                "requesting the other client returns that client's roles");
    }

    // ------------------------------------------------------------------------------------------------
    // domainRoles(...) fail-closed path: anything missing -> empty list, no escalation
    // ------------------------------------------------------------------------------------------------

    @Test
    public void domainRoles_returnsEmpty_whenRequestedDomainIsAbsent() {
        Jwt token = jwtWithResourceAccess(Map.of(NW, Map.of("roles", List.of("user", "editor"))));

        assertTrue(NiordDomainFilter.domainRoles(token, NM).isEmpty(),
                "naming an absent domain must not grant roles");
    }

    @Test
    public void domainRoles_returnsEmpty_whenResourceAccessMissing() {
        Jwt token = jwt(Map.of("realm_access", Map.of("roles", List.of("sysadmin"))));

        assertTrue(NiordDomainFilter.domainRoles(token, NW).isEmpty(),
                "missing resource_access must grant no roles");
    }

    @Test
    public void domainRoles_returnsEmpty_whenDomainPresentButHasNoRolesKey() {
        Jwt token = jwtWithResourceAccess(Map.of(NW, Map.of("something-else", true)));

        assertTrue(NiordDomainFilter.domainRoles(token, NW).isEmpty(),
                "a domain entry without a roles key must grant no roles");
    }

    @Test
    public void domainRoles_returnsEmpty_whenRolesArrayIsEmpty() {
        Jwt token = jwtWithResourceAccess(Map.of(NW, Map.of("roles", List.of())));

        assertTrue(NiordDomainFilter.domainRoles(token, NW).isEmpty(),
                "an empty roles array yields no roles");
    }

    @Test
    public void domainRoles_returnsEmpty_whenResourceAccessIsEmptyObject() {
        Jwt token = jwtWithResourceAccess(Map.of());

        assertTrue(NiordDomainFilter.domainRoles(token, NW).isEmpty(),
                "empty resource_access grants no roles");
    }

    @Test
    public void domainRoles_failsClosed_onNonObjectShapes() {
        // resource_access is not an object at all
        assertTrue(NiordDomainFilter.domainRoles(
                jwtWithResourceAccess("garbage"), NW).isEmpty(),
                "a non-object resource_access claim grants no roles");
        // the domain entry is not an object
        assertTrue(NiordDomainFilter.domainRoles(
                jwtWithResourceAccess(Map.of(NW, "garbage")), NW).isEmpty(),
                "a non-object domain entry grants no roles");
        // roles is not an array
        assertTrue(NiordDomainFilter.domainRoles(
                jwtWithResourceAccess(Map.of(NW, Map.of("roles", "editor"))), NW).isEmpty(),
                "a non-array roles entry grants no roles");
    }

    // ------------------------------------------------------------------------------------------------
    // Domain parking around the request
    // ------------------------------------------------------------------------------------------------

    @Test
    public void parksDomainDuringTheRequest_andRemovesItAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", NW);

        FilterChain chain = (req, res) -> {
            // While the chain (and thus the REST layer) runs, the domain is parked...
            verify(domainService).setDomainForCurrentThread(NW);
            // ...and not yet removed
            verify(domainService, never()).removeDomainForCurrentThread();
        };
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(domainService).removeDomainForCurrentThread();
    }

    @Test
    public void removesDomain_evenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", NW);

        FilterChain chain = (req, res) -> {
            throw new ServletException("boom");
        };
        assertThrows(ServletException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(), chain));

        verify(domainService).setDomainForCurrentThread(NW);
        verify(domainService).removeDomainForCurrentThread();
    }

    @Test
    public void withoutDomainHeader_noDomainIsParked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // An unresolved domain is never pushed onto the thread...
        verify(domainService, never()).setDomainForCurrentThread(any());
        // ...the request still proceeds down the chain...
        assertSame(request, chain.getRequest());
        // ...and the finally-block cleanup is unconditional.
        verify(domainService).removeDomainForCurrentThread();
    }

    @Test
    public void blankDomainHeader_noDomainIsParked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", "  ");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // A blank domain id is treated like "unresolved" -> never set, chain still runs, cleanup fires
        verify(domainService, never()).setDomainForCurrentThread(any());
        assertSame(request, chain.getRequest());
        verify(domainService).removeDomainForCurrentThread();
    }

    @Test
    public void parksDomainForAnonymousCallers_withoutAugmenting() throws Exception {
        // Public endpoints (e.g. message search) resolve the current domain for anonymous callers too,
        // so parking must not require an authentication.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", NW);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(domainService).setDomainForCurrentThread(NW);
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "no authentication must be conjured up for an anonymous caller");
    }

    // ------------------------------------------------------------------------------------------------
    // Log correlation: the domain in the MDC
    // ------------------------------------------------------------------------------------------------

    @Test
    public void putsTheDomainInTheMdcDuringTheRequest_andRemovesItAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", NW);
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(MdcContext.DOMAIN)));

        assertEquals(NW, seen.get(), "log lines written during the request must name the tenant");
        assertNull(MDC.get(MdcContext.DOMAIN),
                "the domain must not leak onto the next request served by this thread");
    }

    @Test
    public void removesTheDomainFromTheMdc_evenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", NW);

        assertThrows(ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("boom"); }));

        assertNull(MDC.get(MdcContext.DOMAIN), "cleanup must happen on the failure path too");
    }

    /**
     * {@code DomainResolver} returns the {@code NiordDomain} header verbatim, so without a check
     * here the header would be copied into every log line — the same log injection
     * {@link RequestIdFilter} guards against, through a different door.
     *
     * <p>Note what does <em>not</em> change: the domain is still parked, and goes through the same
     * role augmentation as any other header value. The validation is a logging concern and is
     * confined to logging; it deliberately does not alter domain resolution.
     */
    @Test
    public void keepsAnInjectionShapedDomainOutOfTheMdc_withoutChangingResolution() throws Exception {
        String forged = "nw" + (char) 13 + (char) 10 + "ERROR  Deleted all messages";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", forged);
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(MdcContext.DOMAIN)));

        assertNull(seen.get(), "an unsafe domain is omitted from the log, not sanitised into it");
        verify(domainService).setDomainForCurrentThread(forged);
        verify(domainService).removeDomainForCurrentThread();
    }

    @Test
    public void withoutDomainHeader_noDomainIsPutInTheMdc() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("sentinel");

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(MdcContext.DOMAIN)));

        assertNull(seen.get());
    }

    // ------------------------------------------------------------------------------------------------
    // Role augmentation of the authenticated JWT
    // ------------------------------------------------------------------------------------------------

    /** Runs the filter and returns the authentication visible to the downstream chain. */
    private Authentication authenticationSeenDownstream(MockHttpServletRequest request) throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication());
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen.get();
    }

    private static Set<String> authorityNames(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    @Test
    public void augmentsJwtAuthentication_withTheRolesOfTheNamedDomain() throws Exception {
        when(domainService.isActiveDomain(NW)).thenReturn(true);
        Jwt token = jwtWithResourceAccess(Map.of(NW, Map.of("roles", List.of("editor", "user"))));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                token, List.of(new SimpleGrantedAuthority("editor")), "alice");
        auth.setDetails("the-details");
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", NW);
        Authentication downstream = authenticationSeenDownstream(request);

        // The base identity is re-decorated with the union of its own and the domain's roles
        assertEquals(Set.of("editor", "user"), authorityNames(downstream));
        JwtAuthenticationToken augmented = (JwtAuthenticationToken) downstream;
        assertSame(token, augmented.getToken(), "the verified token must be carried over");
        assertEquals("alice", augmented.getName(), "the principal name must be preserved");
        assertEquals("the-details", augmented.getDetails(), "the details must be preserved");
    }

    @Test
    public void namingAnUnknownDomain_leavesTheAuthenticationUntouched() throws Exception {
        Jwt token = jwtWithResourceAccess(Map.of(NW, Map.of("roles", List.of("editor", "user"))));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                token, List.of(new SimpleGrantedAuthority("editor")), "alice");
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", "no-such-domain-xyz");
        Authentication downstream = authenticationSeenDownstream(request);

        // Fail closed: the very same authentication instance, no roles added — and, since the token
        // holds no roles on the named client, no domain lookup either
        assertSame(auth, downstream);
        verify(domainService, never()).isActiveDomain(any());
    }

    @Test
    public void domainRolesAlreadyHeld_leaveTheAuthenticationUntouched() throws Exception {
        // The named domain adds nothing the identity does not already have -> no re-decoration
        when(domainService.isActiveDomain(NW)).thenReturn(true);
        Jwt token = jwtWithResourceAccess(Map.of(NW, Map.of("roles", List.of("editor"))));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                token, List.of(new SimpleGrantedAuthority("editor")), "alice");
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", NW);

        assertSame(auth, authenticationSeenDownstream(request));
    }

    @Test
    public void withoutDomainHeader_theAuthenticationIsNeverAugmented() throws Exception {
        Jwt token = jwtWithResourceAccess(Map.of(NW, Map.of("roles", List.of("editor", "user"))));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                token, List.of(new SimpleGrantedAuthority("editor")), "alice");
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertSame(auth, authenticationSeenDownstream(new MockHttpServletRequest()),
                "no NiordDomain header -> no augmentation");
        verify(domainService, never()).isActiveDomain(any());
    }

    // ------------------------------------------------------------------------------------------------
    // Only an existing, active Niord domain's roles are added
    // ------------------------------------------------------------------------------------------------

    @Test
    public void rolesOnAClientThatIsNotADomain_areNotAdded() throws Exception {
        // A token can carry roles on Keycloak clients that are not Niord domains - the SPA client defines
        // user/editor/admin/sysadmin of its own. Naming such a client must add nothing.
        Jwt token = jwtWithResourceAccess(Map.of("niord-web", Map.of("roles", List.of("user", "admin"))));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                token, List.of(new SimpleGrantedAuthority("editor")), "alice");
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", "niord-web");
        Authentication downstream = authenticationSeenDownstream(request);

        assertSame(auth, downstream);
        assertEquals(Set.of("editor"), authorityNames(downstream));
        verify(domainService).isActiveDomain("niord-web");
    }

    @Test
    public void rolesOnAnInactiveDomain_areNotAdded() throws Exception {
        // A deactivated (or deleted) domain keeps its Keycloak client and role mappings; its roles must
        // stop counting once Niord no longer treats it as an active domain.
        when(domainService.isActiveDomain(NM)).thenReturn(false);
        Jwt token = jwtWithResourceAccess(Map.of(NM, Map.of("roles", List.of("admin", "user"))));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                token, List.of(new SimpleGrantedAuthority("editor")), "alice");
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", NM);
        Authentication downstream = authenticationSeenDownstream(request);

        assertSame(auth, downstream);
        assertEquals(Set.of("editor"), authorityNames(downstream));
    }

    @Test
    public void rolesOnAnActiveDomain_areAdded() throws Exception {
        // The same token and header as the inactive case, with the domain active: the roles are added.
        when(domainService.isActiveDomain(NM)).thenReturn(true);
        Jwt token = jwtWithResourceAccess(Map.of(NM, Map.of("roles", List.of("admin", "user"))));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(
                token, List.of(new SimpleGrantedAuthority("editor")), "alice");
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", NM);

        assertEquals(Set.of("editor", "admin", "user"), authorityNames(authenticationSeenDownstream(request)));
    }

    @Test
    public void nonJwtAuthentication_isLeftUntouched() throws Exception {
        // E.g. an anonymous or test authentication: the filter only augments verified bearer tokens
        TestingAuthenticationToken auth = new TestingAuthenticationToken("alice", "pwd", "editor");
        SecurityContextHolder.getContext().setAuthentication(auth);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("NiordDomain", NW);
        Authentication downstream = authenticationSeenDownstream(request);

        assertSame(auth, downstream);
        assertEquals(Set.of("editor"), authorityNames(downstream), "no roles may be minted");
    }
}
