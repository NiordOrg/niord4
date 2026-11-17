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
package org.niord.core.tx;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.chart.Chart;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Characterization safety net pinning the <em>observable</em> transaction-rollback semantics of the
 * application (see {@code docs/persistence-modernization-plan.md} §4).
 *
 * <p>The application sets {@code rollbackOn} nowhere and {@code rollbackFor = Exception.class} only at the
 * six deliberate exceptions listed in §2.4 of that plan; every other transactional method relies on the
 * default rollback rule: roll back on {@link RuntimeException}/{@link Error} only, <em>commit</em> on
 * checked exceptions. That default is Spring's own and applies identically to Spring's
 * {@code @Transactional} and to {@code jakarta.transaction.Transactional} when Spring governs it — so the
 * two are interchangeable here and neither must be "made safe" by adding
 * {@code rollbackFor = Exception.class} (which would itself be a behavior change).
 *
 * <p>This test proves that observationally by exercising <strong>both</strong> annotations side by side and
 * asserting identical behavior:
 * <ul>
 *   <li>a checked exception thrown from a transactional method <strong>commits</strong> the row,</li>
 *   <li>a {@link RuntimeException} <strong>rolls back</strong> the row,</li>
 *   <li>a {@code REQUIRES_NEW} method <strong>commits independently</strong> of a rolling-back caller.</li>
 * </ul>
 * If anything ever changes the default rollback behavior, the corresponding assertions here break.
 *
 * <p>The fixtures persist {@link Chart} rows (unique {@code chartNumber}, no FKs) directly through a
 * container-managed {@link EntityManager}; every chart created with the {@link #PREFIX} marker is purged
 * after each test so the shared seeded database is left unchanged.
 */
@Import({
        TransactionRollbackSemanticsITTest.JtaTxBean.class,
        TransactionRollbackSemanticsITTest.SpringTxBean.class,
        TransactionRollbackSemanticsITTest.InnerTxBean.class,
        TransactionRollbackSemanticsITTest.OuterTxBean.class,
        TransactionRollbackSemanticsITTest.Probe.class,
})
class TransactionRollbackSemanticsITTest extends ITTest {

    /** Marker prefix so the cleanup query only ever touches rows this test created. */
    static final String PREFIX = "TXSEMIT-";

    @Autowired JtaTxBean jta;
    @Autowired SpringTxBean spring;
    @Autowired OuterTxBean outer;
    @Autowired Probe probe;

    @AfterEach
    void cleanup() {
        probe.deleteByPrefix(PREFIX + "%");
    }

    // ------------------------------------------------------------------
    // jakarta.transaction.Transactional (the CURRENT annotation)
    // ------------------------------------------------------------------

    @Test
    void jtaCommitsOnCheckedException() {
        String cn = PREFIX + "jta-checked";
        assertThrows(Exception.class, () -> jta.persistThenThrowChecked(cn));
        assertEquals(1, probe.count(cn),
                "jakarta.transaction.Transactional must COMMIT on a checked exception (Spring default rollback rule)");
    }

    @Test
    void jtaRollsBackOnRuntimeException() {
        String cn = PREFIX + "jta-runtime";
        assertThrows(IllegalStateException.class, () -> jta.persistThenThrowRuntime(cn));
        assertEquals(0, probe.count(cn),
                "jakarta.transaction.Transactional must ROLL BACK on a RuntimeException");
    }

    // ------------------------------------------------------------------
    // org.springframework.transaction.annotation.Transactional (the TARGET annotation)
    // ------------------------------------------------------------------

    @Test
    void springCommitsOnCheckedException() {
        String cn = PREFIX + "spring-checked";
        assertThrows(Exception.class, () -> spring.persistThenThrowChecked(cn));
        assertEquals(1, probe.count(cn),
                "Spring @Transactional must COMMIT on a checked exception (default rollback rule) — identical to JTA");
    }

    @Test
    void springRollsBackOnRuntimeException() {
        String cn = PREFIX + "spring-runtime";
        assertThrows(IllegalStateException.class, () -> spring.persistThenThrowRuntime(cn));
        assertEquals(0, probe.count(cn),
                "Spring @Transactional must ROLL BACK on a RuntimeException — identical to JTA");
    }

    // ------------------------------------------------------------------
    // REQUIRES_NEW commits independently of a rolling-back caller (both annotations)
    // ------------------------------------------------------------------

    @Test
    void jtaRequiresNewCommitsIndependentlyOfRollingBackCaller() {
        String outerCn = PREFIX + "jta-outer";
        String innerCn = PREFIX + "jta-inner";
        assertThrows(IllegalStateException.class, () -> outer.persistOuterThenInnerNewThenThrow(outerCn, innerCn, false));
        assertEquals(0, probe.count(outerCn), "outer REQUIRED tx must roll back");
        assertEquals(1, probe.count(innerCn), "inner jakarta REQUIRES_NEW tx must commit independently");
    }

    @Test
    void springRequiresNewCommitsIndependentlyOfRollingBackCaller() {
        String outerCn = PREFIX + "spring-outer";
        String innerCn = PREFIX + "spring-inner";
        assertThrows(IllegalStateException.class, () -> outer.persistOuterThenInnerNewThenThrow(outerCn, innerCn, true));
        assertEquals(0, probe.count(outerCn), "outer REQUIRED tx must roll back");
        assertEquals(1, probe.count(innerCn), "inner Spring REQUIRES_NEW tx must commit independently");
    }

    // ==================================================================
    // Fixtures — @Transactional methods MUST be public for proxy-based tx to apply.
    // ==================================================================

    /** Persists a chart, then throws — annotated with the CURRENT jakarta annotation. */
    static class JtaTxBean {
        @PersistenceContext EntityManager em;

        @jakarta.transaction.Transactional
        public void persistThenThrowChecked(String chartNumber) throws Exception {
            em.persist(new Chart(chartNumber, null));
            throw new Exception("checked — must COMMIT under the default (unchecked-only) rollback rule");
        }

        @jakarta.transaction.Transactional
        public void persistThenThrowRuntime(String chartNumber) {
            em.persist(new Chart(chartNumber, null));
            throw new IllegalStateException("runtime — must ROLL BACK");
        }
    }

    /** Identical behavior, annotated with the TARGET Spring annotation. */
    static class SpringTxBean {
        @PersistenceContext EntityManager em;

        @org.springframework.transaction.annotation.Transactional
        public void persistThenThrowChecked(String chartNumber) throws Exception {
            em.persist(new Chart(chartNumber, null));
            throw new Exception("checked — must COMMIT under the default (unchecked-only) rollback rule");
        }

        @org.springframework.transaction.annotation.Transactional
        public void persistThenThrowRuntime(String chartNumber) {
            em.persist(new Chart(chartNumber, null));
            throw new IllegalStateException("runtime — must ROLL BACK");
        }
    }

    /** Inner bean exposing both REQUIRES_NEW flavors; called across the proxy boundary by {@link OuterTxBean}. */
    static class InnerTxBean {
        @PersistenceContext EntityManager em;

        @jakarta.transaction.Transactional(jakarta.transaction.Transactional.TxType.REQUIRES_NEW)
        public void persistJtaRequiresNew(String chartNumber) {
            em.persist(new Chart(chartNumber, null));
        }

        @org.springframework.transaction.annotation.Transactional(propagation = Propagation.REQUIRES_NEW)
        public void persistSpringRequiresNew(String chartNumber) {
            em.persist(new Chart(chartNumber, null));
        }
    }

    /** Outer REQUIRED tx that writes, invokes an inner REQUIRES_NEW write, then rolls back via a runtime exception. */
    static class OuterTxBean {
        @PersistenceContext EntityManager em;
        private final InnerTxBean inner;

        OuterTxBean(InnerTxBean inner) {
            this.inner = inner;
        }

        @jakarta.transaction.Transactional
        public void persistOuterThenInnerNewThenThrow(String outerChartNumber, String innerChartNumber, boolean useSpring) {
            em.persist(new Chart(outerChartNumber, null));
            if (useSpring) {
                inner.persistSpringRequiresNew(innerChartNumber);
            } else {
                inner.persistJtaRequiresNew(innerChartNumber);
            }
            throw new IllegalStateException("force rollback of the outer REQUIRED transaction");
        }
    }

    /** Reads/cleans in independent committed transactions so it observes only durably-committed state. */
    static class Probe {
        @PersistenceContext EntityManager em;

        @org.springframework.transaction.annotation.Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
        public long count(String chartNumber) {
            return em.createQuery("select count(c) from Chart c where c.chartNumber = :cn", Long.class)
                    .setParameter("cn", chartNumber)
                    .getSingleResult();
        }

        @org.springframework.transaction.annotation.Transactional(propagation = Propagation.REQUIRES_NEW)
        public void deleteByPrefix(String like) {
            em.createQuery("delete from Chart c where c.chartNumber like :like")
                    .setParameter("like", like)
                    .executeUpdate();
        }
    }
}
