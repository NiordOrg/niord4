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
package org.niord.core.message;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.UnexpectedRollbackException;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fault injection on the lookup the delivery paths share: {@code MessageQueryService.findByUid}, which answers null
 * only when no message has the uid (a {@code NoResultException}). The error hunt of 2026-11-12, wave 2 (track
 * C-tests), running the {@code findByUid} part of wave 1's C7 (filed as PROM14's note; the pattern is PORT-14's).
 */
class MessageLookupFaultITTest extends ITTest {

    @Autowired
    MessageQueryService messageQueryService;

    @PersistenceContext
    EntityManager em;

    /**
     * PROM14. The fixture warning NW-001-26 exists. When its lookup's query fails (here the transaction's earlier
     * statement failed, so PostgreSQL refuses every later one, as it does after a lock or statement timeout or a
     * lost connection), {@code findByUid} lets the failure propagate as a {@code PersistenceException}, so its
     * callers do not act on "not found" (the public {@code messageDetails} 404, a status-change trigger's "Invalid
     * message", the archive import treating the message as new). A uid that names no message still answers null.
     */
    @Test
    void aFailedLookupOfAnExistingWarningPropagatesTheFailure() {
        String uid = Tx.requiringNew().call(() -> em.createQuery(
                        "select m.uid from Message m where m.shortId = 'NW-001-26'", String.class)
                .getSingleResult());
        assertNotNull(Tx.requiringNew().call(() -> messageQueryService.findByUid(uid)),
                "control: the fixture warning is found");
        assertNull(Tx.requiringNew().call(() -> messageQueryService.findByUid("IT-no-such-uid")),
                "control: a uid that names no message answers null");

        AtomicReference<Message> found = new AtomicReference<>(new Message());
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<Exception> lookupFailure = new AtomicReference<>();
        assertThrows(UnexpectedRollbackException.class, () -> Tx.requiringNew().run(() -> {
            try {
                em.createNativeQuery("select * from \"IT_no_such_table\"").getResultList();
            } catch (Exception e) {
                failure.set(e);
            }
            try {
                found.set(messageQueryService.findByUid(uid));
            } catch (Exception e) {
                lookupFailure.set(e);
            }
        }), "the transaction was doomed by the failed statement");

        assertTrue(failure.get() != null, "precondition: the injected statement failed");
        assertInstanceOf(PersistenceException.class, lookupFailure.get(),
                "the failed lookup of an existing warning propagates the failure: " + lookupFailure.get());
        assertNotNull(found.get(), "the failed lookup did not answer null (the reference keeps its initial value)");
    }
}
