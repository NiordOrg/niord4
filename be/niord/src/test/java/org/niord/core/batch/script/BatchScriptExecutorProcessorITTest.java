/*
 * Copyright 2017 Danish Maritime Authority.
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
package org.niord.core.batch.script;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link BatchScriptExecutorProcessor#processItem(Object)}, exercised against the seeded
 * MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>The processor is normally instantiated and driven by the JBeret batch runtime, which injects its
 * {@link EntityManager} dependency, the per-instance {@code BatchData} ({@code job}) and a {@code FileHandler}-backed
 * logger that writes into the batch job folder. To test the script-evaluation business logic without that runtime,
 * this suite uses a {@link TestableProcessor} subclass declared in the same package: it overrides {@code getLog()}
 * to return a parent-less no-op {@link java.util.logging.Logger} (so the {@code FileHandler} that targets a
 * non-existent batch folder is never opened) and exposes a {@code wire(...)} method that assigns the real CDI-managed
 * {@link EntityManager} directly into the package-private {@code em} field.
 *
 * <p>The behaviour under test: {@code processItem} casts the item to a {@code String} script, evaluates it with the
 * Nashorn engine (available on JDK 21 via the {@code org.openjdk.nashorn:nashorn-core} dependency), binds the
 * {@link EntityManager} as {@code "em"}, prefixes a {@code CdiUtils} {@code Java.type(...)} import, and returns the
 * script result; on a script error it logs and rethrows a wrapped {@link Exception}.
 *
 * <p>Every test is wrapped in {@link TestTransaction}: the read-only JPQL count joins the test transaction and any
 * state is rolled back, keeping the shared seed database pristine and the tests independent and repeatable.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchScriptExecutorProcessorITTest extends ITTest {

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchScriptExecutorProcessor} that can be driven outside the JBeret batch runtime.
     *
     * <p>It replaces the batch-folder {@code FileHandler} logger with a parent-less no-op logger (avoiding any file
     * I/O into a non-existent batch job folder) and lets the test inject the real {@link EntityManager} directly into
     * the package-private {@code em} field, reachable from this same package.
     */
    static class TestableProcessor extends BatchScriptExecutorProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-script");

        {
            noop.setUseParentHandlers(false);
        }

        @Override
        protected java.util.logging.Logger getLog() {
            return noop;
        }

        void wire(EntityManager entityManager) {
            this.em = entityManager;
        }
    }

    /** Wires a fresh {@link TestableProcessor} with the injected {@link EntityManager}. */
    private TestableProcessor processor() {
        TestableProcessor processor = new TestableProcessor();
        processor.wire(em);
        return processor;
    }

    // ------------------------------------------------------------------------------------------- //
    // Pure script evaluation
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void evaluatesArithmeticAndReturnsResult() throws Exception {
        Object result = processor().processItem("1 + 1;");

        assertNotNull(result, "Nashorn must return the evaluated expression result");
        assertTrue(result instanceof Number,
                "Nashorn evaluates a JS numeric expression to a Number, was: " + result.getClass());
        assertEquals(2, ((Number) result).intValue(),
                "the script '1 + 1;' must evaluate to 2");
    }

    // ------------------------------------------------------------------------------------------- //
    // EntityManager binding
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void bindsEntityManagerSoScriptsCanQueryTheDatabase() throws Exception {
        // The script uses the bound "em" to run a JPQL count; this only works if the EntityManager binding is wired.
        Object result = processor()
                .processItem("em.createQuery('select count(c) from Chart c').getSingleResult();");

        assertNotNull(result, "a JPQL count via the bound 'em' must return a result");
        assertTrue(result instanceof Number,
                "a JPQL count must return a numeric result, proving the 'em' binding works, was: "
                        + result.getClass());
        assertTrue(((Number) result).longValue() >= 0,
                "a count of Chart rows can never be negative, was: " + ((Number) result).longValue());
    }

    // ------------------------------------------------------------------------------------------- //
    // Error handling
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void wrapsScriptEvaluationErrors() {
        // Syntactically invalid JavaScript: Nashorn fails to evaluate it and the processor wraps + rethrows.
        Exception ex = assertThrows(Exception.class,
                () -> processor().processItem("this is not valid js (("));

        assertNotNull(ex.getMessage(), "the wrapped exception must carry a message");
        assertTrue(ex.getMessage().contains("Error executing script"),
                "a failing script must be rethrown wrapped with 'Error executing script', was: " + ex.getMessage());
    }

    @Test
    @Transactional
    void rejectsNonStringItemWithClassCastException() {
        // processItem performs an unchecked cast of the item to String; a non-String item must fail that cast.
        assertThrows(ClassCastException.class,
                () -> processor().processItem(Integer.valueOf(42)));
    }
}
