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

import org.niord.core.service.BaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the local {@link User} row that mirrors an authenticated Keycloak principal, in its own
 * short transaction.
 *
 * <p>{@code UserService.currentUser()} is a get-or-create over the unique {@code username} column.
 * Performing the create in a {@code REQUIRES_NEW} transaction (rather than the caller's) makes the
 * get-or-create safe against races: a duplicate insert — a concurrent first request for the same
 * user, or a second get-or-create within the same request — aborts only <em>this</em> transaction,
 * which the caller recovers from by re-reading the winner's committed row. Inline in the caller's
 * transaction there is no such recovery: on PostgreSQL the constraint violation aborts the whole
 * transaction, and the request fails at commit with an opaque HTTP 500.
 *
 * <p>Deliberate consequence: once created, the row survives a later rollback of the caller's
 * transaction. That is acceptable — and arguably more correct — because the row carries no
 * privileges of its own; it merely mirrors a user that Keycloak has already authenticated.
 */
@Service
public class UserCreationService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(UserCreationService.class);

    /**
     * Persists — and, on return, commits — a new user for the given Keycloak principal.
     *
     * @param principal the authenticated Keycloak principal to mirror
     * @throws jakarta.persistence.PersistenceException if the user already exists (the caller lost
     *         a creation race and should re-read the winner's row); only this transaction is rolled back
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createUser(Jwt principal) {
        User user = new User(principal);
        em.persist(user);
        // Flush here so a duplicate username surfaces inside this transaction (aborting only it),
        // not at the outer transaction's commit
        em.flush();
        log.info("Created new user " + user);
    }
}
