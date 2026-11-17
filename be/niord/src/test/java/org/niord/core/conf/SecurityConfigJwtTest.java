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
package org.niord.core.conf;

import org.junit.jupiter.api.Test;
import org.niord.test.CountryBuildGroup;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the two JWT decisions of {@link SecurityConfig}: which roles of a verified token become
 * Spring authorities ({@link SecurityConfig#jwtAuthConverter()}), and which clients' tokens are accepted
 * at all ({@link SecurityConfig#azpValidator(java.util.Collection)}, the check behind the
 * {@code authorizedPartyValidator} bean).
 *
 * <p>The tokens are built directly, the way {@code NiordDomainFilterTest} builds them; signature, expiry
 * and issuer are the resource server's business and are not what is under test here.
 * {@code JwtAuthorizedPartyITTest} proves over HTTP that the validator is part of the running decoder.
 */
@CountryBuildGroup
class SecurityConfigJwtTest {

    private static final String BASE_CLIENT = "niord-client-master";

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("the-token")
                .header("alg", "none")
                .subject("alice");
        claims.forEach(builder::claim);
        return builder.build();
    }

    /**
     * The role authorities the converter grants for the given token. Spring Security also adds a
     * {@code FACTOR_BEARER} authority recording how the caller authenticated; it names no role and is
     * left out.
     */
    private static Set<String> authorities(Jwt jwt) {
        SecurityConfig config = new SecurityConfig();
        config.baseClient = BASE_CLIENT;
        return config.jwtAuthConverter().convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> !a.startsWith("FACTOR_"))
                .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------------------------------- //
    // Authorities
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theBaseClientsRolesBecomeAuthorities() {
        Jwt token = jwt(Map.of("resource_access",
                Map.of(BASE_CLIENT, Map.of("roles", List.of("sysadmin", "admin", "editor")))));

        assertEquals(Set.of("sysadmin", "admin", "editor"), authorities(token));
    }

    @Test
    void aRealmRoleGrantsNothing() {
        // A realm role is shared by every client of the realm; one named like a Niord role must not
        // grant that role.
        Jwt token = jwt(Map.of("realm_access", Map.of("roles", List.of("sysadmin", "offline_access"))));

        assertEquals(Set.of(), authorities(token));
    }

    @Test
    void onlyTheBaseClientsRolesCountWhenRealmAndOtherClientRolesAreAlsoPresent() {
        Jwt token = jwt(Map.of(
                "realm_access", Map.of("roles", List.of("sysadmin")),
                "resource_access", Map.of(
                        BASE_CLIENT, Map.of("roles", List.of("editor")),
                        "niord-client-nw", Map.of("roles", List.of("user", "admin")))));

        // Domain-client roles are added per request by the NiordDomainFilter, never from the bare token
        assertEquals(Set.of("editor"), authorities(token));
    }

    // ------------------------------------------------------------------------------------------- //
    // Authorized party (azp)
    // ------------------------------------------------------------------------------------------- //

    private static final OAuth2TokenValidator<Jwt> VALIDATOR =
            SecurityConfig.azpValidator(List.of("niord-web", " " + BASE_CLIENT + " "));

    @Test
    void aTokenIssuedToAnAllowedClientIsAccepted() {
        assertFalse(VALIDATOR.validate(jwt(Map.of("azp", "niord-web"))).hasErrors());
        assertFalse(VALIDATOR.validate(jwt(Map.of("azp", BASE_CLIENT))).hasErrors(),
                "configured names are trimmed");
    }

    @Test
    void aTokenIssuedToAnotherClientOfTheRealmIsRejected() {
        assertTrue(VALIDATOR.validate(jwt(Map.of("azp", "admin-cli"))).hasErrors());
        assertTrue(VALIDATOR.validate(jwt(Map.of("azp", "NIORD-WEB"))).hasErrors(),
                "client ids are compared exactly");
    }

    @Test
    void aTokenWithoutAnAuthorizedPartyIsRejected() {
        assertTrue(VALIDATOR.validate(jwt(Map.of("preferred_username", "alice"))).hasErrors());
    }

    @Test
    void blankConfiguredNamesAdmitNothing() {
        // "niord-web,," or a trailing comma must not turn into an entry that matches an empty azp
        OAuth2TokenValidator<Jwt> validator = SecurityConfig.azpValidator(List.of("niord-web", "", "  "));

        assertTrue(validator.validate(jwt(Map.of("azp", ""))).hasErrors());
        assertFalse(validator.validate(jwt(Map.of("azp", "niord-web"))).hasErrors());
    }
}
