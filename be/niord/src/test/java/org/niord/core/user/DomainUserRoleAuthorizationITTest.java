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

import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.core.domain.DomainService;
import org.niord.test.ITTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security-decision integration tests for the identity- and role-aware methods of
 * {@link UserService} (and the {@link DomainService#domainsWithUserRole(String)} that delegates to
 * them), run against the seeded MySQL + Keycloak containers of the {@link ITTest} infrastructure.
 *
 * <p>{@code UserServiceITTest} deliberately leaves {@code isCallerInRole} and the
 * identity-dependent accessors untested (it documents them as "require a security identity"). This
 * class closes that gap by covering the two paths through those methods that <em>are</em> reachable
 * from a plain {@code @QuarkusTest} thread:
 *
 * <ol>
 *   <li><b>The anonymous-identity branch.</b> When {@code UserService} is injected and called
 *       directly (i.e. not from inside a REST request), the request-scoped {@code SecurityIdentity}
 *       is <em>anonymous</em> — the same state the existing {@code currentUser()} seam tests rely on
 *       (see {@code MessageSearchFilterMatrixITTest}). In that state {@code identity.getRoles()} is
 *       empty and {@code getPrincipal()} is not an {@code OidcJwtCallerPrincipal}, so the
 *       principal-derived accessors fall back to their documented null/empty results, and
 *       {@code getKeycloakDomainIdsForRole(...)} / {@code domainsWithUserRole(...)} return empty.</li>
 *   <li><b>The ticket fallback of {@code isCallerInRole}.</b> When the identity is anonymous but a
 *       {@link TicketService} ticket has been resolved onto the current thread, {@code isCallerInRole}
 *       answers from the ticket's roles. This is the exact production path used for non-Ajax download
 *       links (see {@code TicketService}). It is asserted as a positive/negative security decision:
 *       a ticket carrying {@code editor} grants {@code editor} but never {@code admin}.</li>
 * </ol>
 *
 * <h2>What is intentionally NOT asserted here, and why</h2>
 *
 * <p>The "authenticated bearer-token" half of {@code getKeycloakDomainIdsForRole}/{@code
 * domainsWithUserRole} — i.e. that for a {@code testeditor} token the set equals exactly the clients
 * whose {@code resource_access} carries the role ({@code niord-nw} + {@code
 * niord-client-master} for {@code editor}, only {@code niord-nw} for {@code user}, empty for
 * {@code sysadmin}) — cannot be asserted from this test setup. Those methods read the
 * request-scoped {@code OidcJwtCallerPrincipal}, which is only populated on a real REST request
 * thread, and no REST endpoint returns the computed domain-id set (its only consumers are a deep
 * batch-import flow and a log statement). Driving it through {@code MessageExportRestService} would
 * assert a downstream side effect, not the set itself. This is documented as framework-coupled — see
 * docs/framework-coupled-tests.md. The complementary REST-driven role hierarchy is already covered
 * by {@code RoleAuthorizationMatrixITTest} and {@code DomainAugmentorEdgeCaseITTest}.
 *
 * <p>State isolation: the only mutable per-test state is the {@link TicketService} thread-local,
 * cleared in {@link #clearTicketThreadLocal()}; tickets are random one-time handles so they never
 * collide. No database rows are written, so no transaction or cleanup is needed and every test runs
 * standalone and within the full suite.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class DomainUserRoleAuthorizationITTest extends ITTest {

    /** A domain seeded in the realm/db; the augmentor grants its role chain when named. */
    private static final String NW_DOMAIN = "niord-nw";

    @Autowired
    UserService userService;

    @Autowired
    CurrentUserContext currentUserContext;

    @Autowired
    DomainService domainService;

    @Autowired
    DomainLookupService domainLookupService;

    @Autowired
    TicketService ticketService;

    /**
     * The ticket fallback reads a {@link ThreadLocal} that survives between tests sharing a thread,
     * so it must be cleared after each test to keep the security decisions independent.
     */
    @AfterEach
    void clearTicketThreadLocal() {
        ticketService.removeTicketForCurrentThread();
    }

    /** Builds a detached {@link User} with only the username populated (all the ticket path reads). */
    private User newUser(String username) {
        User u = new User();
        u.setUsername(username);
        return u;
    }

    /** Issues a ticket tied to the given roles and resolves it onto the current thread. */
    private void resolveTicketWithRoles(String username, String... roles) {
        String ticket = ticketService.createTicket(null, newUser(username), roles);
        assertTrue(ticketService.resolveTicketForCurrentThread(ticket),
                "the freshly issued ticket must resolve onto the current thread");
    }

    // ------------------------------------------------------------------------------------------- //
    // isCallerInRole — ticket fallback (anonymous identity + a resolved ticket on the thread).
    // This is the security-critical behaviour the existing UserServiceITTest leaves uncovered.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void isCallerInRoleGrantsARoleCarriedByTheResolvedTicket() {
        resolveTicketWithRoles("it-role-editor", "editor");

        assertTrue(userService.isCallerInRole("editor"),
                "with an anonymous identity but a ticket carrying 'editor', the caller IS in role editor");
    }

    @Test
    void isCallerInRoleDeniesARoleNotCarriedByTheResolvedTicket() {
        resolveTicketWithRoles("it-role-editor-not-admin", "editor");

        assertFalse(userService.isCallerInRole("admin"),
                "a ticket carrying only 'editor' must NOT grant 'admin' — the security decision is per-role");
    }

    @Test
    void isCallerInRoleGrantsEachRoleOfAMultiRoleTicket() {
        resolveTicketWithRoles("it-role-multi", "editor", "admin");

        assertTrue(userService.isCallerInRole("editor"),
                "a multi-role ticket grants the first listed role");
        assertTrue(userService.isCallerInRole("admin"),
                "a multi-role ticket grants the second listed role too");
        assertFalse(userService.isCallerInRole("sysadmin"),
                "a multi-role ticket must not grant a role it does not list");
    }

    @Test
    void isCallerInRoleIsCaseSensitiveAgainstTheTicketRoles() {
        // The ticket fallback compares with List.contains(role), an exact (case-sensitive) match —
        // mirroring identity.hasRole(...). A differently-cased role name is therefore NOT granted.
        resolveTicketWithRoles("it-role-case", "editor");

        assertFalse(userService.isCallerInRole("EDITOR"),
                "the ticket fallback uses an exact, case-sensitive match, so 'EDITOR' != 'editor'");
    }

    @Test
    void isCallerInRoleDeniesEveryRoleWithNoIdentityAndNoTicket() {
        // Anonymous identity AND no ticket resolved on the thread -> the method returns false outright.
        assertFalse(userService.isCallerInRole("editor"),
                "with neither a security identity nor a ticket, no role may be granted");
        assertFalse(userService.isCallerInRole("user"),
                "the role-less default applies to every role, including the lowest tier");
        assertFalse(userService.isCallerInRole("sysadmin"),
                "the role-less default also denies the highest tier");
    }

    @Test
    void isCallerInRoleDeniesWhenTheResolvedTicketCarriesNoRoles() {
        // A ticket tied to no roles carries an empty role array, so isCallerInRole's
        // List.of().contains(role) is false for every role — a ticket only carries a role once
        // TicketRestService verified the issuing caller held it, so "no roles" means "never checked".
        resolveTicketWithRoles("it-role-none");

        assertFalse(userService.isCallerInRole("editor"),
                "a role-less ticket must not grant any role through isCallerInRole");
        assertFalse(userService.isCallerInRole("user"),
                "a role-less ticket grants no role at all via isCallerInRole");
    }

    @Test
    void isCallerInRoleFallsThroughToFalseWhenTicketHasABlankUser() {
        // The ticket fallback only consults the ticket roles when ticketData.getUser() is non-blank.
        // A ticket with roles but a blank/absent user therefore short-circuits to 'false', even for a
        // role the ticket lists. This guards against role-bearing-but-userless tickets.
        String ticket = ticketService.createTicket(null, newUser("   "), "editor");
        assertTrue(ticketService.resolveTicketForCurrentThread(ticket));

        assertFalse(userService.isCallerInRole("editor"),
                "a ticket whose user is blank must not grant a role even though the ticket lists it");
    }

    @Test
    void isCallerInRoleStopsGrantingAfterTheTicketThreadLocalIsCleared() {
        resolveTicketWithRoles("it-role-cleared", "editor");
        assertTrue(userService.isCallerInRole("editor"),
                "precondition: the resolved ticket grants editor");

        ticketService.removeTicketForCurrentThread();

        assertFalse(userService.isCallerInRole("editor"),
                "once the ticket thread-local is cleared the caller reverts to role-less");
    }

    // ------------------------------------------------------------------------------------------- //
    // currentUserRoles — anonymous identity exposes no roles.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void currentUserRolesIsEmptyForAnAnonymousIdentity() {
        // identity.getRoles() on the anonymous request-scoped identity is empty; the ticket fallback
        // does NOT feed currentUserRoles() (only isCallerInRole/currentUser consult the ticket).
        Set<String> roles = currentUserContext.currentUserRoles();
        assertNotNull(roles, "currentUserRoles must never be null");
        assertTrue(roles.isEmpty(),
                "an anonymous identity carries no roles, even when a ticket is resolved: " + roles);
    }

    @Test
    void currentUserRolesIgnoresAResolvedTicket() {
        // currentUserRoles reads only the SecurityIdentity, never the ticket — a resolved ticket with
        // roles must not leak into currentUserRoles(). This pins the seam between the two mechanisms.
        resolveTicketWithRoles("it-currentroles-ticket", "editor", "admin");

        assertTrue(currentUserContext.currentUserRoles().isEmpty(),
                "currentUserRoles must reflect only the (anonymous) identity, not the ticket roles");
    }

    // ------------------------------------------------------------------------------------------- //
    // Principal-derived accessors — anonymous identity has no OidcJwtCallerPrincipal.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void principalDerivedAccessorsAreNullOrEmptyForAnAnonymousIdentity() {
        // The anonymous identity's principal is not an OidcJwtCallerPrincipal, so each accessor hits
        // its documented null/empty fallback rather than throwing.
        assertNull(currentUserContext.getCallerPrincipal(),
                "an anonymous identity exposes no OidcJwtCallerPrincipal");
        assertNull(currentUserContext.getKeycloakAccessToken(),
                "with no Keycloak principal there is no raw access token");

        Map<String, Object> attrs = currentUserContext.getUserAttributes();
        assertNotNull(attrs, "getUserAttributes must return an (empty) map, never null");
        assertTrue(attrs.isEmpty(),
                "an anonymous identity carries no Keycloak claim attributes: " + attrs);
    }

    // ------------------------------------------------------------------------------------------- //
    // getKeycloakDomainIdsForRole / domainsWithUserRole — anonymous identity yields the empty set,
    // and domainsWithUserRole filters the real seeded domains down to that empty set.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getKeycloakDomainIdsForRoleIsEmptyForAnAnonymousIdentity() {
        // No caller principal -> the resource_access lookup short-circuits to the empty set for any role.
        assertTrue(currentUserContext.getKeycloakDomainIdsForRole("editor").isEmpty(),
                "an anonymous identity holds no role on any Keycloak client");
        assertTrue(currentUserContext.getKeycloakDomainIdsForRole("user").isEmpty(),
                "the empty result is independent of which role is queried");
        assertTrue(currentUserContext.getKeycloakDomainIdsForRole("sysadmin").isEmpty(),
                "even the highest tier yields no domains for an anonymous identity");
    }

    @Test
    void getKeycloakDomainIdsForRoleIgnoresAResolvedTicket() {
        // getKeycloakDomainIdsForRole reads only the OidcJwtCallerPrincipal, never the ticket — so a
        // resolved role-bearing ticket must not produce any domain ids.
        resolveTicketWithRoles("it-kcdomains-ticket", "editor");

        assertTrue(currentUserContext.getKeycloakDomainIdsForRole("editor").isEmpty(),
                "the Keycloak domain-id lookup is principal-only; a ticket grants no client domains");
    }

    @Test
    void domainsWithUserRoleIsEmptyForAnAnonymousIdentityDespiteSeededDomains() {
        // Precondition: the seed really does contain the niord-nw domain, so an empty result
        // proves the role filter excluded it (rather than there being no domains to filter at all).
        Domain seeded = domainLookupService.findByDomainId(NW_DOMAIN);
        assertNotNull(seeded, "seed data must provide the " + NW_DOMAIN + " domain");
        assertFalse(domainService.getDomains().isEmpty(),
                "precondition: there must be at least one active seeded domain to filter");

        List<Domain> editorDomains = domainService.domainsWithUserRole("editor");
        assertTrue(editorDomains.isEmpty(),
                "an anonymous identity has the role on no domain, so the filtered list is empty: "
                        + editorDomains);

        // And specifically it must not contain the seeded domain that does exist.
        assertFalse(editorDomains.stream().anyMatch(d -> NW_DOMAIN.equals(d.getDomainId())),
                "the seeded " + NW_DOMAIN + " domain must be filtered out for a role-less caller");
    }

    @Test
    void domainsWithUserRoleIsEmptyForEveryRoleForAnAnonymousIdentity() {
        assertEquals(0, domainService.domainsWithUserRole("user").size(),
                "no domains carry 'user' for an anonymous caller");
        assertEquals(0, domainService.domainsWithUserRole("admin").size(),
                "no domains carry 'admin' for an anonymous caller");
        assertEquals(0, domainService.domainsWithUserRole("sysadmin").size(),
                "no domains carry 'sysadmin' for an anonymous caller");
    }
}
