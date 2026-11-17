/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core.publication.series;

import jakarta.persistence.EntityManager;

import java.util.Collection;
import java.util.List;

/**
 * Deletes fixture series, and everything that hangs off them, in foreign-key order.
 *
 * A bulk DELETE goes straight to the database and cascades nothing, so the children go first:
 * each issue's members, overrides, audit entries and descs, then the issues, the series' own
 * audit entries and descs, and the series last. The series' collection tables -- its languages
 * and its availability list -- need no statement of their own, because Hibernate's bulk delete of
 * an entity clears the collection tables the entity owns.
 *
 * Joins the caller's transaction. A fixture written through {@code Tx.requiringNew()} needs a
 * committed one as well, or its rows outlive the test.
 */
public final class SeededSeries {

    private SeededSeries() {
    }

    /** Deletes the series with these database ids, and their issues with them. */
    public static void delete(EntityManager em, Collection<Integer> seriesIds) {
        if (seriesIds.isEmpty()) {
            return;
        }
        List<Integer> issueIds = em.createQuery(
                        "SELECT i.id FROM PublicationIssue i WHERE i.series.id IN :series", Integer.class)
                .setParameter("series", seriesIds).getResultList();
        if (!issueIds.isEmpty()) {
            em.createQuery("DELETE FROM IssueMember m WHERE m.issue.id IN :issues")
                    .setParameter("issues", issueIds).executeUpdate();
            em.createQuery("DELETE FROM IssueOverride o WHERE o.issue.id IN :issues")
                    .setParameter("issues", issueIds).executeUpdate();
            em.createQuery("DELETE FROM IssueAuditEntry a WHERE a.issue.id IN :issues")
                    .setParameter("issues", issueIds).executeUpdate();
            em.createQuery("DELETE FROM PublicationIssueDesc d WHERE d.entity.id IN :issues")
                    .setParameter("issues", issueIds).executeUpdate();
        }
        em.createQuery("DELETE FROM IssueAuditEntry a WHERE a.series.id IN :series")
                .setParameter("series", seriesIds).executeUpdate();
        em.createQuery("DELETE FROM PublicationIssue i WHERE i.series.id IN :series")
                .setParameter("series", seriesIds).executeUpdate();
        em.createQuery("DELETE FROM PublicationSeriesDesc d WHERE d.entity.id IN :series")
                .setParameter("series", seriesIds).executeUpdate();
        em.createQuery("DELETE FROM PublicationSeries s WHERE s.id IN :series")
                .setParameter("series", seriesIds).executeUpdate();
    }
}
