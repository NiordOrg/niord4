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
package org.niord.core.domain.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.area.AreaService;
import org.niord.core.batch.BatchData;
import org.niord.core.category.CategoryService;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.core.domain.vo.DomainVo;
import org.niord.core.message.MessageSeriesService;
import org.niord.test.ITTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for {@link BatchDomainImportProcessor#processItem(Object)}, exercised against the seeded
 * MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>The processor is normally instantiated and driven by the JBeret batch runtime, which injects its CDI service
 * dependencies, the per-instance {@link BatchData} ({@code job}) and a {@code FileHandler}-backed logger that writes
 * into the batch job folder. To test the business logic without that runtime, this suite uses a {@link
 * TestableProcessor} subclass declared in the same package: it overrides {@code getLog()} to return a parent-less
 * no-op {@link java.util.logging.Logger} (so the {@code FileHandler} that targets a non-existent batch folder is
 * never opened) and exposes a {@code wire(...)} method that assigns the real CDI service beans and a hand-built
 * {@code BatchData} directly into the package-private/protected fields. This test lives in the processor's package
 * {@code org.niord.core.domain.batch}, so those fields are reachable on the classpath.
 *
 * <p>Every test is wrapped in {@link TestTransaction}: any service writes join the test transaction and are rolled
 * back, keeping the shared seed database pristine and the tests independent and repeatable.
 *
 * <p>Behaviour covered: the existing-domain branch (an already-seeded {@code domainId} is ignored and {@code null}
 * is returned); the new-domain branch (an unknown {@code domainId} yields a transient {@link Domain} that carries the
 * imported id and is not yet persisted); and the unchecked-cast contract (a wrong-type item throws
 * {@link ClassCastException}).
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchDomainImportProcessorITTest extends ITTest {

    @Autowired
    DomainLookupService domainLookupService;

    @Autowired
    AreaService areaService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchDomainImportProcessor} that can be driven outside the JBeret batch runtime.
     *
     * <p>It replaces the batch-folder {@code FileHandler} logger with a parent-less no-op logger (avoiding any file
     * I/O into a non-existent batch job folder) and lets the test inject the real service beans and the {@code job}
     * batch data directly. All assigned fields are package-private/protected and reachable from this same package.
     */
    static class TestableProcessor extends BatchDomainImportProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-domain");

        {
            noop.setUseParentHandlers(false);
        }

        @Override
        protected java.util.logging.Logger getLog() {
            return noop;
        }

        void wire(DomainLookupService ds, AreaService as, CategoryService cs, MessageSeriesService mss, BatchData j) {
            this.domainLookupService = ds;
            this.areaService = as;
            this.categoryService = cs;
            this.messageSeriesService = mss;
            this.job = j;
        }
    }

    /** Wires a fresh {@link TestableProcessor} with the injected beans and a hand-built {@link BatchData}. */
    private TestableProcessor newProcessor() {
        TestableProcessor processor = new TestableProcessor();
        processor.wire(domainLookupService, areaService, categoryService, messageSeriesService, new BatchData());
        return processor;
    }

    // ------------------------------------------------------------------------------------------- //
    // Existing-domain branch: already-seeded domain is ignored
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void ignoresExistingDomainReturningNull() throws Exception {
        // The seed contains the domain "niord-nw" - confirm the precondition, then import the same id.
        assertNotNull(domainLookupService.findByDomainId("niord-nw"),
                "precondition: the seeded domain 'niord-nw' must exist");

        DomainVo vo = new DomainVo("niord-nw");
        vo.setName("NW (re-imported)");

        Object result = newProcessor().processItem(vo);

        assertNull(result,
                "importing a domain whose domainId already exists must be ignored and return null");
    }

    // ------------------------------------------------------------------------------------------- //
    // New-domain branch: unknown domain becomes a transient Domain
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void buildsTransientDomainForNewDomainId() throws Exception {
        // A domainId that does not exist in the seed data, so the processor must take the "new domain" branch.
        String domainId = "IT-DOM-" + UUID.randomUUID();
        assertNull(domainLookupService.findByDomainId(domainId),
                "precondition: the unique domainId must not already exist in the seed");

        DomainVo vo = new DomainVo(domainId);
        vo.setName("IT new domain");
        // No areas/categories/message series -> no resolution against the DB is required.

        Object result = newProcessor().processItem(vo);

        assertNotNull(result, "an unknown domainId must yield a new transient Domain, not null");
        Domain domain = assertInstanceOf(Domain.class, result);
        assertEquals(domainId, domain.getDomainId(),
                "the returned Domain must carry the imported domainId");
        assertNull(domain.getId(),
                "the processor only builds the Domain; it must not be persisted yet (id still null)");

        // It must genuinely be absent from the database - the processor does not write it.
        em.flush();
        em.clear();
        assertNull(domainLookupService.findByDomainId(domainId),
                "the new domain must not have been persisted by processItem");
    }

    // ------------------------------------------------------------------------------------------- //
    // Unchecked-cast contract
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void throwsClassCastExceptionForWrongType() {
        // processItem performs an unchecked cast to DomainVo - a foreign type must fail fast.
        assertThrows(ClassCastException.class, () -> newProcessor().processItem("not-a-domain-vo"));
    }

    /**
     * Minimal local helper mirroring the JUnit 5 {@code assertInstanceOf} contract (kept inline to avoid relying on
     * a specific JUnit patch version): asserts the value is an instance of {@code type} and returns it cast.
     */
    private static <T> T assertInstanceOf(Class<T> type, Object value) {
        assertNotNull(value, "expected a non-null " + type.getSimpleName());
        if (!type.isInstance(value)) {
            throw new AssertionError("expected instance of " + type.getName()
                    + " but was " + value.getClass().getName());
        }
        return type.cast(value);
    }
}
