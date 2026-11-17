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

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CurrentTransactionUserResolver} - the {@link org.niord.model.DataFilter.UserResolver}
 * that injects the current caller principal/role into a {@code DataFilter}.
 *
 * <p>The resolver is a thin, security-relevant delegate over {@link UserService}: it forwards
 * {@code getPrincipal()} / {@code isUserInRole(role)} to the user service, but swallows ANY exception
 * and falls back to {@code null} / {@code false}. These tests pin both the happy delegation and the
 * fail-closed swallow behaviour, because that fallback is the security-meaningful branch: an error
 * while resolving the caller must never throw out of a {@code DataFilter} projection, and must never
 * grant a role.</p>
 *
 * <p>{@link UserService} is mocked (Mockito is a declared test-scope dependency of the {@code niord}
 * module precisely for stubbing container-owned collaborators). The tests are otherwise
 * framework-neutral: the resolver itself is a plain object with a constructor-injected dependency, so
 * these assertions survive a Quarkus -&gt; Spring migration unchanged.</p>
 *
 * <p>PORT: {@code UserService.getCallerPrincipal()} now returns a Spring Security
 * {@link Jwt} (was a Quarkus {@code OidcJwtCallerPrincipal}, which itself implemented
 * {@link Principal}). The resolver consequently no longer returns the principal verbatim; it wraps the
 * JWT in a {@link Principal} whose {@code getName()} yields the {@code preferred_username} claim (the
 * same value the old {@code OidcJwtCallerPrincipal.getName()} exposed). The happy-path assertion is
 * therefore expressed on the resolved name rather than object identity, preserving the original intent:
 * the resolver forwards exactly the identity the user service supplies.</p>
 */
public class CurrentTransactionUserResolverTest {

    /** A real, container-free Spring Security JWT carrying the {@code preferred_username} claim. */
    private static Jwt jwt(String preferredUsername) {
        return Jwt.withTokenValue("tok")
                .header("alg", "none")
                .claim("preferred_username", preferredUsername)
                .build();
    }

    /* ---------------------------------------------------------------- */
    /* getPrincipal()                                                    */
    /* ---------------------------------------------------------------- */

    @Test
    public void getPrincipalReturnsTheCallerPrincipalFromContext() {
        UserService userService = mock(UserService.class);
        CurrentUserContext currentUserContext = mock(CurrentUserContext.class);
        when(currentUserContext.getCallerPrincipal()).thenReturn(jwt("alice"));

        CurrentTransactionUserResolver resolver = new CurrentTransactionUserResolver(userService, currentUserContext);

        // PORT: the resolver now wraps the JWT in a Principal whose name is the preferred_username
        // claim, so identity (assertSame) no longer holds. Assert the forwarded identity instead.
        Principal resolved = resolver.getPrincipal();
        assertNotNull(resolved,
                "the resolver must return a principal when the context supplies one");
        assertEquals("alice", resolved.getName(),
                "the resolved principal must carry the caller's preferred_username");
        verify(currentUserContext).getCallerPrincipal();
    }

    @Test
    public void getPrincipalReturnsNullWhenContextReturnsNull() {
        UserService userService = mock(UserService.class);
        CurrentUserContext currentUserContext = mock(CurrentUserContext.class);
        when(currentUserContext.getCallerPrincipal()).thenReturn(null);

        CurrentTransactionUserResolver resolver = new CurrentTransactionUserResolver(userService, currentUserContext);

        assertNull(resolver.getPrincipal(), "a null caller principal must propagate as null");
    }

    @Test
    public void getPrincipalSwallowsExceptionAndReturnsNull() {
        UserService userService = mock(UserService.class);
        CurrentUserContext currentUserContext = mock(CurrentUserContext.class);
        when(currentUserContext.getCallerPrincipal()).thenThrow(new RuntimeException("no security context"));

        CurrentTransactionUserResolver resolver = new CurrentTransactionUserResolver(userService, currentUserContext);

        // The resolver must NOT propagate the exception - it fails closed to null.
        assertNull(resolver.getPrincipal(),
                "an exception while resolving the principal must be swallowed (null)");
    }

    /* ---------------------------------------------------------------- */
    /* isUserInRole(role)                                               */
    /* ---------------------------------------------------------------- */

    @Test
    public void isUserInRoleDelegatesAndReturnsTrueForGrantedRole() {
        UserService userService = mock(UserService.class);
        CurrentUserContext currentUserContext = mock(CurrentUserContext.class);
        when(userService.isCallerInRole("sysadmin")).thenReturn(true);

        CurrentTransactionUserResolver resolver = new CurrentTransactionUserResolver(userService, currentUserContext);

        assertTrue(resolver.isUserInRole("sysadmin"), "a granted role must be reported true");
        // The exact role string is forwarded unchanged.
        verify(userService).isCallerInRole("sysadmin");
    }

    @Test
    public void isUserInRoleReturnsFalseForUngrantedRole() {
        UserService userService = mock(UserService.class);
        CurrentUserContext currentUserContext = mock(CurrentUserContext.class);
        when(userService.isCallerInRole("admin")).thenReturn(false);

        CurrentTransactionUserResolver resolver = new CurrentTransactionUserResolver(userService, currentUserContext);

        assertFalse(resolver.isUserInRole("admin"), "an ungranted role must be reported false");
    }

    @Test
    public void isUserInRoleSwallowsExceptionAndReturnsFalse() {
        UserService userService = mock(UserService.class);
        CurrentUserContext currentUserContext = mock(CurrentUserContext.class);
        doThrow(new RuntimeException("no security context"))
                .when(userService).isCallerInRole("user");

        CurrentTransactionUserResolver resolver = new CurrentTransactionUserResolver(userService, currentUserContext);

        // Fail-closed: an error resolving the role must deny (false), never grant or throw.
        assertFalse(resolver.isUserInRole("user"),
                "an exception while resolving the role must be swallowed (deny)");
    }
}
