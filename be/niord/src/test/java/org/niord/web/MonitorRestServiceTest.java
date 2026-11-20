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
package org.niord.web;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.niord.core.message.MessageLuceneIndex;
import org.niord.core.message.MessagePostgresIndex;
import org.niord.core.web.NiordWebException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link MonitorRestService#db()} error-handling (SECAUD-11), and for the answer of
 * {@link MonitorRestService#messageIndex()}.
 *
 * <p>The endpoint is {@code @PermitAll}, so anyone (unauthenticated) can call {@code GET /rest/monitor/db}.
 * The original catch echoed {@code ex.getMessage()} — the raw JDBC exception text, which leaks the
 * connection URL/host/DB-user/SQLSTATE — into the RFC 9457 ProblemDetail body. The hardened version logs
 * the full exception server-side and returns only a generic {@code "Error accessing database"} detail.
 * These tests use a Mockito {@link EntityManager} double (no container).
 */
class MonitorRestServiceTest {

    /** A realistic, secret-bearing JDBC failure message that must NOT reach the caller. */
    private static final String LEAKY_JDBC_MESSAGE =
            "jdbc:postgresql://db-host:5432/niord user=niord_app password=s3cret [SQLSTATE 08006]";

    @Test
    void dbSanitizesTheDatabaseExceptionInsteadOfLeakingIt() {
        MonitorRestService svc = new MonitorRestService();
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery("select 1")).thenReturn(query);
        when(query.getSingleResult()).thenThrow(new RuntimeException(LEAKY_JDBC_MESSAGE));
        svc.em = em;

        NiordWebException ex = assertThrows(NiordWebException.class, svc::db);

        // The generic detail is returned; the raw JDBC message is NOT echoed.
        assertEquals("Error accessing database", ex.getReason());
        assertEquals(500, ex.getStatusCode().value());
        assertFalse(ex.getReason().contains("jdbc"), "JDBC URL must not leak to the caller");
        assertFalse(ex.getReason().contains("password"), "DB credentials must not leak to the caller");
        assertFalse(ex.getReason().contains("SQLSTATE"), "SQLSTATE must not leak to the caller");
    }

    @Test
    void dbReturnsSuccessWhenTheQuerySucceeds() {
        MonitorRestService svc = new MonitorRestService();
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery("select 1")).thenReturn(query);
        when(query.getSingleResult()).thenReturn(1);
        svc.em = em;

        assertEquals("success", svc.db());
    }

    /** The message-index answer: each index's own flag, and complete only when both are */
    @Test
    void messageIndexIsCompleteOnlyWhenBothIndexesAre() {
        MonitorRestService svc = new MonitorRestService();
        svc.messageLuceneIndex = mock(MessageLuceneIndex.class);
        svc.messagePostgresIndex = mock(MessagePostgresIndex.class);

        for (boolean lucene : new boolean[] {false, true}) {
            for (boolean postgres : new boolean[] {false, true}) {
                when(svc.messageLuceneIndex.allMessagesIndexed()).thenReturn(lucene);
                when(svc.messagePostgresIndex.allMessagesIndexed()).thenReturn(postgres);
                assertEquals(new MonitorRestService.MessageIndexState(lucene, postgres, lucene && postgres),
                        svc.messageIndex(), "lucene " + lucene + ", postgres " + postgres);
            }
        }
    }
}
