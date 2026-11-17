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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the race-safe get-or-create contract of {@link UserService#currentUser()}.
 *
 * <p>The defect: {@code currentUser()} created the missing {@code User} mirror row inline in the
 * caller's transaction, so a duplicate insert — a concurrent first request for the same user, or a
 * second get-or-create within one request — surfaced as a duplicate-key failure at <em>commit</em>,
 * after the handler had already succeeded (HTTP 500; observed on Linux CI, run 28861114852). The fix
 * creates the row in a short {@code REQUIRES_NEW} transaction ({@link UserCreationService}): a lost
 * race aborts only that transaction and the caller re-reads the winner's committed row.
 *
 * <p>The tests run without {@code @Transactional} test rollback because the creation deliberately
 * commits ({@code REQUIRES_NEW}); created rows are deleted explicitly afterwards. Identities are
 * fabricated per test with fresh usernames, injected as authenticated JWTs into the (thread-local)
 * {@link SecurityContextHolder} — the same claims a Keycloak token carries.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class UserServiceCurrentUserITTest extends ITTest {

    @Autowired
    UserService userService;

    @Autowired
    UserCreationService userCreationService;

    @PersistenceContext
    EntityManager em;

    private final List<String> createdUsernames = new ArrayList<>();

    private String freshUsername() {
        String username = "it-currentuser-" + System.nanoTime();
        createdUsernames.add(username);
        return username;
    }

    /** Builds a JWT carrying the claims {@code User.copyToken(...)} reads. */
    private static Jwt jwt(String username) {
        return Jwt.withTokenValue("it-token-" + username)
                .header("alg", "none")
                .subject(username)
                .claim("preferred_username", username)
                .claim("given_name", "Current")
                .claim("family_name", "UserIT")
                .claim("email", username + "@example.com")
                .build();
    }

    /** Authenticates the current thread as the given (fresh) username. */
    private static void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt(username), List.of(new SimpleGrantedAuthority("user")), username));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        Tx.requiringNew().run(() -> createdUsernames.forEach(username ->
                em.createQuery("delete from User u where u.username = :username")
                        .setParameter("username", username)
                        .executeUpdate()));
    }

    private long committedRowCount(String username) {
        return Tx.requiringNew().call(() ->
                em.createQuery("select count(u) from User u where u.username = :username", Long.class)
                        .setParameter("username", username)
                        .getSingleResult());
    }

    // ------------------------------------------------------------------------------------------- //
    // Get-or-create within one transaction
    // ------------------------------------------------------------------------------------------- //

    @Test
    void currentUserCreatesTheMirrorRowOnceAndReusesItWithinOneTransaction() {
        String username = freshUsername();
        authenticateAs(username);

        Tx.requiringNew().run(() -> {
            User first = userService.currentUser();
            assertNotNull(first, "the first call must create the mirror row");
            assertEquals(username, first.getUsername());
            assertNotNull(first.getId(), "the created row must be committed and carry an id");

            // A second get-or-create in the SAME transaction (the CI failure had two such calls in
            // one request) must resolve the created row, never insert a second one
            User second = userService.currentUser();
            assertEquals(first.getId(), second.getId(), "the second call must reuse the created row");
        });

        assertEquals(1, committedRowCount(username), "exactly one committed row must exist");
    }

    // ------------------------------------------------------------------------------------------- //
    // Lost creation race — only the creation transaction aborts
    // ------------------------------------------------------------------------------------------- //

    @Test
    void lostCreationRaceAbortsOnlyTheCreationTransactionAndTheRowStaysUsable() {
        String username = freshUsername();

        // The "winner" commits the row...
        userCreationService.createUser(jwt(username));
        assertEquals(1, committedRowCount(username));

        // ...so the "loser"'s create fails — in its own REQUIRES_NEW transaction, as a
        // PersistenceException the get-or-create recovers from (nothing else is poisoned)
        assertThrows(PersistenceException.class, () -> userCreationService.createUser(jwt(username)),
                "a duplicate username must abort the creation transaction");

        // The winner's row is intact and currentUser() resolves it
        authenticateAs(username);
        User user = userService.currentUser();
        assertNotNull(user, "currentUser must resolve the winner's row after a lost race");
        assertEquals(username, user.getUsername());
        assertEquals(1, committedRowCount(username), "the lost race must not add a second row");
    }

    // ------------------------------------------------------------------------------------------- //
    // Concurrent first requests — the USR2 scenario across threads
    // ------------------------------------------------------------------------------------------- //

    @Test
    void concurrentFirstRequestsForTheSameNewUserBothSucceedWithASingleRow() throws Exception {
        String username = freshUsername();

        // Two "requests" (own thread, own security context, own transaction — as two servlet
        // requests would be) race to get-or-create the same brand-new user. Before the fix the
        // loser failed at commit with a duplicate key (HTTP 500); now both must succeed and agree.
        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        Callable<Integer> firstRequest = () -> {
            authenticateAs(username);
            try {
                barrier.await(10, TimeUnit.SECONDS);
                return Tx.requiringNew().call(() -> userService.currentUser().getId());
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = pool.invokeAll(List.of(firstRequest, firstRequest));
            Integer firstId = results.get(0).get(30, TimeUnit.SECONDS);
            Integer secondId = results.get(1).get(30, TimeUnit.SECONDS);

            assertNotNull(firstId);
            assertEquals(firstId, secondId, "both racing requests must resolve the same user row");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, committedRowCount(username), "the race must produce exactly one committed row");
    }
}
