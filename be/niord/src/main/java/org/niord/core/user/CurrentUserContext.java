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

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exposes the identity of the current caller, read straight from the Spring Security context:
 * the Keycloak JWT principal, the bearer access token, the granted roles and the Keycloak
 * resource-role claims.
 * <p>
 * This is a stateless, dependency-free leaf service. Services that only need to know
 * <em>who is calling</em> (e.g. {@link org.niord.core.keycloak.KeycloakIntegrationService} and
 * {@link org.niord.core.domain.DomainService}) depend on it, rather than on {@link UserService},
 * which keeps the dependency graph acyclic.
 */
@Service
public class CurrentUserContext {

    /** Returns the current Spring Security authentication, or null if unauthenticated */
    private Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** Returns true if there is no authenticated, non-anonymous caller */
    public boolean isAnonymous() {
        Authentication auth = currentAuthentication();
        return auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken;
    }

    /** Returns the granted authorities (roles) of the current caller */
    public Set<String> currentUserRoles() {
        Authentication auth = currentAuthentication();
        if (auth == null) {
            return Collections.emptySet();
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    /**
     * Returns the current Keycloak principal
     */
    public Jwt getCallerPrincipal() {
        Authentication auth = currentAuthentication();
        if (auth == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return jwt;
        }
        Object credentials = auth.getCredentials();
        if (credentials instanceof Jwt jwt) {
            return jwt;
        }
        return null;
    }

    /**
     * Returns the current Keycloak Access Token
     */
    public String getKeycloakAccessToken() {
        return Optional.ofNullable(getCallerPrincipal())
                .map(Jwt::getTokenValue)
                .orElse(null);
    }

    /**
     * Returns the user attributes, i.e. the "other claims" map of the current Keycloak principal
     */
    public Map<String, Object> getUserAttributes() {
        return Optional.ofNullable(getCallerPrincipal())
                .map(Jwt::getClaims)
                .orElseGet(() -> new HashMap<>());
    }

    /**
     * Returns all the Keycloak domain IDs where the current user has the given role
     *
     * @param role the role to check for
     * @return all the Keycloak domain IDs where the current user has the given role
     */
    @SuppressWarnings("unchecked")
    public Set<String> getKeycloakDomainIdsForRole(String role) {
        // The "resource_access" claim is exposed by Spring's Jwt as a plain nested Map:
        // resource_access -> <domain> -> roles[] contains <role>.
        return Optional.ofNullable(this.getCallerPrincipal())
                .map(Jwt::getClaims)
                .map(claims -> claims.get("resource_access"))
                .filter(Map.class::isInstance)
                .map(ra -> (Map<String, Object>) ra)
                .orElse(Collections.emptyMap())
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() instanceof Map
                        && ((Map<String, Object>) entry.getValue()).get("roles") instanceof Collection
                        && ((Collection<?>) ((Map<String, Object>) entry.getValue()).get("roles")).contains(role))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
}
