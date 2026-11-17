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

package org.niord.core.domain;

import org.niord.core.service.BaseService;
import jakarta.persistence.NoResultException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Read-only lookups of {@link Domain} entities by their public domain ID.
 * <p>
 * Extracted as a dependency-free leaf (only the {@code EntityManager} inherited from
 * {@link BaseService}) so that lower-level services — e.g.
 * {@link org.niord.core.message.MessageSeriesService} — can resolve a domain without depending on
 * {@link DomainService}, which in turn depends on them. This keeps the bean graph acyclic.
 */
@Service
public class DomainLookupService extends BaseService {

    /**
     * Returns the domain with the given domainId
     * <p>
     * Only "no such domain" answers null. Any other failure, such as the query's auto-flush failing on the caller's
     * unsaved changes, is thrown: such a failure has already marked the transaction rollback-only, and a null here
     * would only move it to the commit, without its cause.
     *
     * @param domainId the domainId
     * @return the domain with the given domainId, or null if not found
     */
    public Domain findByDomainId(String domainId) {
        try {
            return em
                    .createNamedQuery("Domain.findByDomainId", Domain.class)
                    .setParameter("domainId", domainId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Returns the domains with the given domainId's
     * @param domainIds the domain IDs
     * @return the domains with the given domainId's
     */
    public List<Domain> findByDomainIds(Set<String> domainIds) {
        if (domainIds == null || domainIds.isEmpty()) {
            return Collections.emptyList();
        }
        return em
                .createNamedQuery("Domain.findByDomainIds", Domain.class)
                .setParameter("domainIds", domainIds)
                .getResultList();
    }
}
