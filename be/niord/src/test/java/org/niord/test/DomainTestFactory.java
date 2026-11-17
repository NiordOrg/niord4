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

import org.niord.core.domain.Domain;

/**
 * Shared builder for detached {@link Domain} entities in integration tests.
 *
 * <p>A Niord domain id is also a Keycloak client id. The returned entity is <em>detached</em> and
 * active by default; callers persist it via {@code em.persist(..)} or {@code DomainService}. Use a
 * domain id that does not collide with the seeded domains ({@code niord-client-master}/{@code -nw}/
 * {@code -nm}/{@code -fa}/{@code -almanac}/{@code -annex}).
 */
public final class DomainTestFactory {

    private DomainTestFactory() {
    }

    /** A detached, active domain with the given client/domain id and display name. */
    public static Domain domain(String domainId, String name) {
        Domain domain = new Domain();
        domain.setDomainId(domainId);
        domain.setName(name);
        domain.setActive(true);
        return domain;
    }

    /** A detached, active domain whose display name is derived from the id. */
    public static Domain domain(String domainId) {
        return domain(domainId, "Domain " + domainId);
    }
}
