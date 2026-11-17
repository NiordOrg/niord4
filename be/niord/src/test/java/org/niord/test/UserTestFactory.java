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
package org.niord.test;

import org.niord.core.user.User;

/**
 * Shared builder for detached {@link User} entities in integration tests.
 *
 * <p>Mirrors the ad-hoc {@code new User(); setUsername(...); ...} blocks duplicated across the
 * existing user/ticket/message ITs. The returned entity is <em>detached</em> — callers persist it via
 * {@code em.persist(..)} or the relevant service. Follows the static-factory style of the existing
 * {@code AreaTestFactory}; framework-neutral (no Quarkus types) so it survives the Spring migration.
 */
public final class UserTestFactory {

    private UserTestFactory() {
    }

    /**
     * A detached user with a derived e-mail ({@code <username>@test.niord}), names and English
     * language. Use a unique username per test to keep committed-data tests isolated.
     */
    public static User user(String username) {
        return user(username, username + "@test.niord", "Test", username, "en");
    }

    /** A detached user with all identity fields specified explicitly. */
    public static User user(String username, String email, String firstName, String lastName, String language) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setLanguage(language);
        return user;
    }
}
