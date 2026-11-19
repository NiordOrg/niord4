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

import org.hibernate.JDBCException;
import org.hibernate.Session;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the database-backed lookup and search methods of {@link UserService},
 * run against the seeded MySQL container of the {@link ITTest} infrastructure. No {@code User}
 * rows are seeded, so every test creates its own data with a globally-unique {@code it-user-...}
 * username token, keeping the tests isolated from each other and from any seed data.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the
 * shared seed database pristine and the tests independent.
 *
 * <p>Only the persistence/search methods that need no security context are covered here:
 * {@code findByUsername}, {@code findByEmail}, {@code searchUsers}, {@code findByUsernames},
 * {@code persistedUsers} and {@code allUsers}. The Keycloak-backed methods
 * ({@code addKeycloakUser}, {@code updateKeycloakUser}, {@code searchKeycloakUsers},
 * {@code getKeycloakGroups}, etc.) are intentionally not tested here — they require Keycloak admin
 * connectivity. The identity-dependent {@code currentUser()} get-or-create is covered by
 * {@link UserServiceCurrentUserITTest}, which injects built JWTs into the security context.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class UserServiceITTest extends ITTest {

    @Autowired
    UserService userService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached User template and persists it (does not flush). */
    private User persistUser(String username, String email, String firstName, String lastName, String language) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setLanguage(language);
        em.persist(user);
        return user;
    }

    // ------------------------------------------------------------------------------------------- //
    // findByUsername
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByUsernameReturnsPersistedUser() {
        User created = persistUser("it-user-findname", "it-user-findname@example.com",
                "Find", "Name", "en");
        em.flush();
        em.clear();

        User found = userService.findByUsername("it-user-findname");
        assertNotNull(found, "findByUsername must return the persisted user");
        assertEquals(created.getId(), found.getId());
        assertEquals("it-user-findname@example.com", found.getEmail());
    }

    @Test
    @Transactional
    void findByUsernameIsCaseInsensitive() {
        // The named query compares lower(u.username) = lower(:username), so a mixed-case stored
        // username is still found when the caller passes a lowercased name.
        persistUser("IT-User-Mixed", "it-user-mixed@example.com", "Mixed", "Case", "en");
        em.flush();
        em.clear();

        User found = userService.findByUsername("it-user-mixed");
        assertNotNull(found, "findByUsername must match regardless of stored username casing");
        assertEquals("IT-User-Mixed", found.getUsername());
    }

    @Test
    @Transactional
    void aSecondUserWhoseNameDiffersOnlyByCaseIsRefused() {
        // findByUsername expects one row for lower(username), so the database refuses a second one: the unique
        // index on lower(username) of V013. Before it, the unique constraint was on the column itself, this pair
        // was stored, and findByUsername then failed for both spellings.
        persistUser("it-user-case-pair", "it-user-case-pair@example.com", "Case", "Pair", "en");
        em.flush();

        persistUser("IT-User-Case-Pair", "it-user-case-pair-2@example.com", "Case", "Pair", "en");
        ConstraintViolationException refused = assertThrows(ConstraintViolationException.class, () -> em.flush());
        assertEquals("23505", refused.getSQLState(), "a unique violation: " + refused.getMessage());
        assertEquals("user_username_lower", refused.getConstraintName(), refused.getMessage());
    }

    @Test
    @Transactional
    void theCaseInsensitiveChangesetRefusesADatabaseThatHoldsSuchAPairAndNamesIt() throws IOException {
        // The schema as it was before V013, in this test's transaction only (PostgreSQL's DDL rolls back with it):
        // the unique constraint on the column, and a pair of names that differ only by case
        em.createNativeQuery("DROP INDEX public.user_username_lower").executeUpdate();
        em.createNativeQuery("ALTER TABLE public.\"User\" ADD CONSTRAINT user_username UNIQUE (username)")
                .executeUpdate();
        persistUser("it-user-old-pair", "it-user-old-pair@example.com", "Old", "Pair", "en");
        persistUser("IT-User-Old-Pair", "it-user-old-pair-2@example.com", "Old", "Pair", "en");
        em.flush();

        String changeset;
        try (InputStream in = getClass().getResourceAsStream(
                "/db/changelog/changes/V013__user_username_case_insensitive.sql")) {
            assertNotNull(in, "the changeset is on the class path");
            changeset = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // The file as Liquibase sends it with splitStatements:false: one text, its header lines SQL comments
        JDBCException refused = assertThrows(JDBCException.class, () -> em.unwrap(Session.class)
                .doWork(connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(changeset);
                    }
                }));
        String message = refused.getSQLException().getMessage();
        assertEquals("P0001", refused.getSQLState(), "the changeset's own refusal, not a failed index: " + message);
        assertTrue(message.contains("IT-User-Old-Pair") && message.contains("it-user-old-pair"),
                "it names the pair: " + message);
        assertTrue(message.contains("give the others another username by hand"), "it says what to do: " + message);
    }

    @Test
    @Transactional
    void findByUsernameReturnsNullForUnknownUsername() {
        assertNull(userService.findByUsername("it-user-no-such-user-xyz"),
                "findByUsername must return null for an unknown username");
    }

    // ------------------------------------------------------------------------------------------- //
    // findByEmail
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByEmailReturnsPersistedUser() {
        User created = persistUser("it-user-findemail", "it-user-findemail@example.com",
                "Find", "Email", "en");
        em.flush();
        em.clear();

        User found = userService.findByEmail("it-user-findemail@example.com");
        assertNotNull(found, "findByEmail must return the persisted user");
        assertEquals(created.getId(), found.getId());
    }

    @Test
    @Transactional
    void findByEmailIsCaseInsensitive() {
        // The named query compares lower(u.email) = lower(:email).
        persistUser("it-user-emailcase", "IT-User-EmailCase@Example.com", "Email", "Case", "en");
        em.flush();
        em.clear();

        User found = userService.findByEmail("it-user-emailcase@example.com");
        assertNotNull(found, "findByEmail must match regardless of stored email casing");
        assertEquals("it-user-emailcase", found.getUsername());
    }

    @Test
    @Transactional
    void findByEmailOfAnAddressTwoUsersShareReturnsTheFirstUser() {
        // The email column is not unique: two users may share an address, in any casing. That is
        // still "a user has this address", answered with the user created first, never an error.
        User first = persistUser("it-user-shared-a", "it-user-shared@example.com", "Shared", "A", "en");
        persistUser("it-user-shared-b", "IT-User-Shared@example.com", "Shared", "B", "en");
        em.flush();
        em.clear();

        User found = userService.findByEmail("it-user-shared@example.com");
        assertNotNull(found, "findByEmail must find a user when two users share the address");
        assertEquals(first.getId(), found.getId(), "of several users, the one created first is returned");
    }

    @Test
    @Transactional
    void findByEmailReturnsNullForUnknownEmail() {
        assertNull(userService.findByEmail("it-user-nobody@example.com"),
                "findByEmail must return null for an unknown email");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchUsers
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchUsersWithBlankNameReturnsEmptyList() {
        // A blank term short-circuits before touching the database.
        persistUser("it-user-blank", "it-user-blank@example.com", "Blank", "User", "en");
        em.flush();

        assertTrue(userService.searchUsers("   ").isEmpty(),
                "a blank search term must yield an empty list without a DB hit");
        assertTrue(userService.searchUsers("").isEmpty(),
                "an empty search term must yield an empty list without a DB hit");
    }

    @Test
    @Transactional
    void searchUsersMatchesByFirstNameSubstring() {
        persistUser("it-user-fnsearch", "it-user-fnsearch@example.com",
                "ZebulonFirstName", "Last", "en");
        em.flush();
        em.clear();

        List<User> result = userService.searchUsers("ZebulonFirst");
        assertEquals(1, result.size(), "the firstName substring must match exactly one user");
        assertEquals("it-user-fnsearch", result.get(0).getUsername());
    }

    @Test
    @Transactional
    void searchUsersMatchesByEmailSubstring() {
        // The term matches the email column even though it does not occur in any name field.
        persistUser("it-user-emsearch", "it-user-quxmail-unique@example.com",
                "No", "Match", "en");
        em.flush();
        em.clear();

        List<User> result = userService.searchUsers("quxmail-unique");
        assertEquals(1, result.size(), "the email substring must match exactly one user");
        assertEquals("it-user-emsearch", result.get(0).getUsername());
    }

    @Test
    @Transactional
    void searchUsersIsCaseInsensitive() {
        // The service lowercases the term, and the query lowercases the columns.
        persistUser("it-user-casesearch", "it-user-casesearch@example.com",
                "MixedCaseFirstName", "Last", "en");
        em.flush();
        em.clear();

        List<User> result = userService.searchUsers("MIXEDCASEFIRST");
        assertEquals(1, result.size(), "the search must be case-insensitive");
        assertEquals("it-user-casesearch", result.get(0).getUsername());
    }

    @Test
    @Transactional
    void searchUsersWithUnknownTermReturnsEmpty() {
        persistUser("it-user-unknownterm", "it-user-unknownterm@example.com",
                "Some", "Name", "en");
        em.flush();

        assertTrue(userService.searchUsers("it-user-no-such-term-zzz").isEmpty(),
                "an unknown term must yield an empty list");
    }

    @Test
    @Transactional
    void searchUsersSubstringMatchesMultipleUsers() {
        // Both users share a unique substring in their last name.
        persistUser("it-user-multi-1", "it-user-multi-1@example.com",
                "One", "SharedMultiToken", "en");
        persistUser("it-user-multi-2", "it-user-multi-2@example.com",
                "Two", "SharedMultiToken", "da");
        em.flush();
        em.clear();

        List<User> result = userService.searchUsers("SharedMultiToken");
        assertEquals(2, result.size(), "a substring matching two users must return both");
    }

    @Test
    @Transactional
    void searchUsersMatchesAPercentSignLiterally() {
        // Unescaped, "zq%wk" is the pattern %zq%wk% and matches both rows; as text it matches only the
        // name that contains "zq%wk".
        persistUser("it-user-pct-literal", "it-user-pct-literal@example.com", "Pct", "Zq%Wk-it", "en");
        persistUser("it-user-pct-other", "it-user-pct-other@example.com", "Pct", "ZqXXWk-it", "en");
        em.flush();
        em.clear();

        List<User> result = userService.searchUsers("zq%wk");
        assertEquals(List.of("it-user-pct-literal"), result.stream().map(User::getUsername).toList(),
                "'%' in the search term must match only a '%'");
    }

    @Test
    @Transactional
    void searchUsersMatchesAnUnderscoreLiterally() {
        persistUser("it-user-usc-literal", "it-user-usc-literal@example.com", "Usc", "Qv_Jx-it", "en");
        persistUser("it-user-usc-other", "it-user-usc-other@example.com", "Usc", "QvYJx-it", "en");
        em.flush();
        em.clear();

        List<User> result = userService.searchUsers("qv_jx");
        assertEquals(List.of("it-user-usc-literal"), result.stream().map(User::getUsername).toList(),
                "'_' in the search term must match only a '_'");
    }

    @Test
    @Transactional
    void searchUsersMatchesTheEscapeCharacterLiterally() {
        // '!' is the LIKE escape character of the query, so it must itself be escaped in the term
        persistUser("it-user-bang-literal", "it-user-bang-literal@example.com", "Bang", "Wq!%Zk-it", "en");
        persistUser("it-user-bang-other", "it-user-bang-other@example.com", "Bang", "WqXZk-it", "en");
        em.flush();
        em.clear();

        List<User> result = userService.searchUsers("wq!%zk");
        assertEquals(List.of("it-user-bang-literal"), result.stream().map(User::getUsername).toList());
    }

    @Test
    @Transactional
    void searchUsersWithATermBelowTheMinimumLengthReturnsEmpty() {
        persistUser("it-user-short", "it-user-short@example.com", "Xenia", "Short", "en");
        em.flush();
        em.clear();

        assertTrue(userService.searchUsers("x").isEmpty(),
                "a one-character term must match nothing, although a user's name contains it");
        assertTrue(userService.searchUsers(" x ").isEmpty(),
                "the minimum applies to the trimmed term");
        assertEquals(1, userService.searchUsers("xenia").size(),
                "the same user is found with a term of the minimum length or longer");
    }

    @Test
    @Transactional
    void searchUsersReturnsAtMostTheCapWhenMoreUsersMatch() {
        int matching = UserService.MAX_SEARCH_RESULTS + 5;
        for (int i = 0; i < matching; i++) {
            persistUser("it-user-cap-" + i, "it-user-cap-" + i + "@example.com", "Cap", "CapTokenYq-it", "en");
        }
        em.flush();
        em.clear();

        List<String> found = userService.searchUsers("CapTokenYq").stream().map(User::getUsername).toList();
        assertEquals(UserService.MAX_SEARCH_RESULTS, found.size(),
                "a search matching more users than the cap returns exactly the cap");
        // The capped page is the first users by user name, not whichever rows the database reads first
        // (here: the insertion order it-user-cap-0 .. -24, which sorts differently).
        List<String> firstByUsername = IntStream.range(0, matching)
                .mapToObj(i -> "it-user-cap-" + i)
                .sorted()
                .limit(UserService.MAX_SEARCH_RESULTS)
                .toList();
        assertEquals(firstByUsername, found, "the capped page is ordered by user name");
    }

    // ------------------------------------------------------------------------------------------- //
    // findByUsernames
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByUsernamesWithNullReturnsEmptyList() {
        assertTrue(userService.findByUsernames(null).isEmpty(),
                "a null username set must yield an empty list");
    }

    @Test
    @Transactional
    void findByUsernamesWithEmptySetReturnsEmptyList() {
        assertTrue(userService.findByUsernames(Collections.emptySet()).isEmpty(),
                "an empty username set must yield an empty list");
    }

    @Test
    @Transactional
    void findByUsernamesReturnsAllMatching() {
        // findByUsernames lower-cases the input usernames to match the named query
        // "lower(u.username) IN (:usernames)", so it is case-insensitive and works on PostgreSQL (whose IN
        // is case-sensitive). The earlier defect USR1 — the service UPPER-cased the bind values, so on a
        // case-sensitive database nothing matched — has been fixed; see docs/potential-errors.md (USR1).
        persistUser("it-user-byusernames-1", "it-user-byusernames-1@example.com",
                "ByUsers", "One", "en");
        persistUser("it-user-byusernames-2", "it-user-byusernames-2@example.com",
                "ByUsers", "Two", "da");
        em.flush();
        em.clear();

        // Both lower-cased and upper-cased inputs resolve the two users (the match is case-insensitive).
        Set<String> lowercaseNames = Set.of("it-user-byusernames-1", "it-user-byusernames-2");
        assertEquals(2, userService.findByUsernames(lowercaseNames).size(),
                "both persisted users must be returned for the lower-cased usernames");

        Set<String> uppercaseNames = Set.of("IT-USER-BYUSERNAMES-1", "IT-USER-BYUSERNAMES-2");
        assertEquals(2, userService.findByUsernames(uppercaseNames).size(),
                "both persisted users must be returned for the upper-cased usernames (case-insensitive)");

        // Sanity-check that the rows are also findable via the single-name query.
        assertNotNull(userService.findByUsername("it-user-byusernames-1"),
                "the first user must be persisted and findable via findByUsername");
        assertNotNull(userService.findByUsername("it-user-byusernames-2"),
                "the second user must be persisted and findable via findByUsername");
    }

    // ------------------------------------------------------------------------------------------- //
    // persistedUsers
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void persistedUsersDropsUnknownTemplates() {
        User existing = persistUser("it-user-persisted-known", "it-user-persisted-known@example.com",
                "Known", "User", "en");
        em.flush();
        em.clear();

        // Template list mixes an existing username with a non-existent one.
        User knownTemplate = new User();
        knownTemplate.setUsername("it-user-persisted-known");
        User unknownTemplate = new User();
        unknownTemplate.setUsername("it-user-persisted-unknown-xyz");

        List<User> result = userService.persistedUsers(Arrays.asList(knownTemplate, unknownTemplate));
        assertEquals(1, result.size(), "only the existing template must map to a persisted user");
        assertEquals(existing.getId(), result.get(0).getId(),
                "the resolved user must be the persisted row for the known username");
        assertEquals("it-user-persisted-known", result.get(0).getUsername());
    }

    // ------------------------------------------------------------------------------------------- //
    // allUsers
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void allUsersIncludesCreatedUsers() {
        persistUser("it-user-all-1", "it-user-all-1@example.com", "All", "One", "en");
        persistUser("it-user-all-2", "it-user-all-2@example.com", "All", "Two", "da");
        em.flush();

        List<User> all = userService.allUsers();
        long matching = all.stream()
                .filter(u -> u.getUsername() != null && u.getUsername().startsWith("it-user-all-"))
                .count();
        assertEquals(2L, matching, "both newly created users must be returned by allUsers");
    }
}
