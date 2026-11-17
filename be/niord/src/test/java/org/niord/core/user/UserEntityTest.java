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
import org.niord.core.user.vo.UserVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link User} entity's pure logic: the {@code copyUser} value-object copy,
 * the {@code User(UserVo)} constructor that delegates to it, the {@code toVo} round-trip, the
 * blank-aware {@code getName} composition (driven by
 * {@code org.apache.commons.lang3.StringUtils.isNotBlank}) and the {@code toString} rendering.
 *
 * <p>These are framework-neutral plain JUnit 4 tests — no DB, no Quarkus, no mocks. They assert
 * concrete field values and the exact composed name strings so they survive a future Spring port.
 *
 * <p>The Keycloak/OIDC-coupled methods {@code copyToken(OidcJwtCallerPrincipal)} and
 * {@code userChanged(OidcJwtCallerPrincipal)} are deliberately NOT exercised here: they require a
 * Quarkus {@code io.quarkus.oidc.runtime.OidcJwtCallerPrincipal}, which cannot reasonably be
 * constructed in a plain unit test, and are documented separately as untestable in this tier.
 */
public class UserEntityTest {

    /** Builds a fully-populated value object (including the Keycloak-only fields). */
    private UserVo fullVo() {
        UserVo vo = new UserVo();
        vo.setUsername("jdoe");
        vo.setEmail("jdoe@example.dk");
        vo.setFirstName("John");
        vo.setLastName("Doe");
        vo.setLanguage("en");
        // Keycloak-only fields are set on the source VO to prove copyUser ignores them.
        vo.setKeycloakId("kc-123");
        vo.setKeycloakPassword("secret");
        return vo;
    }

    // --------------------------------------------------------------------------------------------- //
    // copyUser
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void copyUserCopiesAllFiveBusinessFields() {
        UserVo vo = fullVo();

        User user = new User();
        user.copyUser(vo);

        assertEquals("jdoe", user.getUsername());
        assertEquals("jdoe@example.dk", user.getEmail());
        assertEquals("John", user.getFirstName());
        assertEquals("Doe", user.getLastName());
        assertEquals("en", user.getLanguage());
    }

    @Test
    public void copyUserCopiesNullFieldsAsNull() {
        UserVo vo = new UserVo();
        vo.setUsername("only-username");
        // email/firstName/lastName/language left null

        User user = new User();
        user.copyUser(vo);

        assertEquals("only-username", user.getUsername());
        assertNull(user.getEmail());
        assertNull(user.getFirstName());
        assertNull(user.getLastName());
        assertNull(user.getLanguage());
    }

    // --------------------------------------------------------------------------------------------- //
    // User(UserVo) constructor — delegates to copyUser
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void constructorFromVoPopulatesAllFiveFields() {
        UserVo vo = fullVo();

        User user = new User(vo);

        assertEquals("jdoe", user.getUsername());
        assertEquals("jdoe@example.dk", user.getEmail());
        assertEquals("John", user.getFirstName());
        assertEquals("Doe", user.getLastName());
        assertEquals("en", user.getLanguage());
    }

    // --------------------------------------------------------------------------------------------- //
    // toVo round-trip
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toVoRoundTripsBusinessFieldsAndLeavesKeycloakFieldsUntouched() {
        UserVo source = fullVo();
        User user = new User(source);

        UserVo vo = user.toVo();

        // The five business fields round-trip exactly.
        assertEquals("jdoe", vo.getUsername());
        assertEquals("jdoe@example.dk", vo.getEmail());
        assertEquals("John", vo.getFirstName());
        assertEquals("Doe", vo.getLastName());
        assertEquals("en", vo.getLanguage());

        // toVo builds a fresh VO and never touches the Keycloak-only fields, so they stay null
        // even though the source VO had them set.
        assertNull(vo.getKeycloakId());
        assertNull(vo.getKeycloakPassword());
        assertNull(vo.getKeycloakActions());
    }

    // --------------------------------------------------------------------------------------------- //
    // getName — blank-aware composition
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void getNameJoinsFirstAndLastWithSingleSpace() {
        User user = new User();
        user.setUsername("jdoe");
        user.setFirstName("John");
        user.setLastName("Doe");

        assertEquals("John Doe", user.getName());
    }

    @Test
    public void getNameUsesFirstNameOnlyWhenLastNameMissing() {
        User user = new User();
        user.setUsername("jdoe");
        user.setFirstName("John");
        // lastName null

        assertEquals("John", user.getName());
    }

    @Test
    public void getNameUsesLastNameOnlyWhenFirstNameMissing() {
        User user = new User();
        user.setUsername("jdoe");
        user.setLastName("Doe");
        // firstName null

        assertEquals("Doe", user.getName());
    }

    @Test
    public void getNameFallsBackToUsernameWhenBothNamesNull() {
        User user = new User();
        user.setUsername("jdoe");
        // firstName and lastName both null

        assertEquals("jdoe", user.getName());
    }

    @Test
    public void getNameFallsBackToUsernameWhenBothNamesAreWhitespace() {
        User user = new User();
        user.setUsername("jdoe");
        user.setFirstName("   ");
        user.setLastName("   ");

        // isNotBlank is false for both whitespace-only values, so nothing is appended
        // and the composition falls back to the username.
        assertEquals("jdoe", user.getName());
    }

    @Test
    public void getNameOmitsTrailingSpaceWhenLastNameIsWhitespace() {
        User user = new User();
        user.setUsername("jdoe");
        user.setFirstName("John");
        user.setLastName("   ");

        // isNotBlank(lastName) is false, so neither the separating space nor the last name is
        // appended — the result is "John" with no trailing space.
        assertEquals("John", user.getName());
    }

    @Test
    public void getNameUsesLastNameWhenFirstNameIsWhitespace() {
        User user = new User();
        user.setUsername("jdoe");
        user.setFirstName("   ");
        user.setLastName("Doe");

        // isNotBlank(firstName) is false so nothing is appended for it; the builder is still empty
        // when the last name is appended, so no leading space is added.
        assertEquals("Doe", user.getName());
    }

    // --------------------------------------------------------------------------------------------- //
    // toString
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void toStringContainsUsername() {
        User user = new User();
        user.setUsername("jdoe");

        assertTrue(user.toString().contains("username='jdoe'"),
                "toString should render the username: " + user);
    }
}
