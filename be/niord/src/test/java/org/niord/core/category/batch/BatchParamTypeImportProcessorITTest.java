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
package org.niord.core.category.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.category.ParamType;
import org.niord.core.category.StandardParamType;
import org.niord.core.category.TemplateExecutionService;
import org.niord.core.category.vo.ListParamTypeVo;
import org.niord.core.category.vo.StandardParamTypeVo;
import org.niord.test.ITTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for {@link BatchParamTypeImportProcessor#processItem(Object)}, exercised against the seeded
 * MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>The processor is normally instantiated and driven by the JBeret batch runtime, which injects its
 * {@link TemplateExecutionService} dependency, the per-instance {@link BatchData} ({@code job}) and a
 * {@code FileHandler}-backed logger that writes into the batch job folder. To test the create/update branching
 * without that runtime, this suite uses a {@link TestableProcessor} subclass declared in the same package: it
 * overrides {@code getLog()} to return a no-op {@link java.util.logging.Logger} (so the {@code FileHandler} that
 * targets the (non-existent) batch folder is never opened) and exposes a {@code wire(...)} method that assigns the
 * real CDI service bean and a hand-built {@code BatchData} directly into the package-private/protected fields.
 *
 * <p>Every test is wrapped in {@link TestTransaction}: {@code createParamType}/{@code updateParamType} are
 * {@code @Transactional(REQUIRED)} and join the test transaction, so all writes are rolled back, keeping the shared
 * seed database (~41 seeded {@code ParamType} rows) pristine and the tests independent and repeatable. Unique
 * UUID-based names avoid the {@code ParamType.name} unique constraint.
 *
 * <p>Behaviour covered: the NEW branch ({@code getParamTypeByName == null} -> {@code createParamType}); the UPDATE
 * branch (existing name -> the original id is reused and no duplicate row is created); the skip of an item with a
 * standard type's name (the standard types are code since C4.1); and the unchecked-cast contract
 * ({@code processItem} of a non-{@code ParamTypeVo} item throws {@link ClassCastException}).
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class BatchParamTypeImportProcessorITTest extends ITTest {

    @Autowired
    TemplateExecutionService templateExecutionService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchParamTypeImportProcessor} that can be driven outside the JBeret batch runtime.
     *
     * <p>It replaces the batch-folder {@code FileHandler} logger with a parent-less no-op logger (avoiding any file
     * I/O into a non-existent batch job folder) and lets the test inject the real service bean and the {@code job}
     * batch data directly. Both assigned fields are package-private/protected and reachable from this same package.
     */
    static class TestableProcessor extends BatchParamTypeImportProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-paramtype");

        {
            noop.setUseParentHandlers(false);
        }

        @Override
        protected java.util.logging.Logger getLog() {
            return noop;
        }

        void wire(TemplateExecutionService tes, BatchData j) {
            this.templateExecutionService = tes;
            this.job = j;
        }
    }

    /** Wires a fresh {@link TestableProcessor} with the injected service and an empty batch data. */
    private TestableProcessor processor() {
        TestableProcessor processor = new TestableProcessor();
        processor.wire(templateExecutionService, new BatchData());
        return processor;
    }

    /** Counts the {@code ParamType} rows carrying the given (unique) name directly via the database. */
    private long countByName(String name) {
        return em.createQuery("select count(t) from ParamType t where t.name = :name", Long.class)
                .setParameter("name", name)
                .getSingleResult();
    }

    /** Builds a detached {@link StandardParamTypeVo} with the given name (no id). */
    private StandardParamTypeVo standardVo(String name) {
        StandardParamTypeVo vo = new StandardParamTypeVo();
        vo.setName(name);
        return vo;
    }

    // ------------------------------------------------------------------------------------------- //
    // NEW branch: getParamTypeByName == null -> createParamType
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createsParamTypeWhenNameIsUnknown() throws Exception {
        String name = "IT-ParamType-" + UUID.randomUUID();
        assertNull(templateExecutionService.getParamTypeByName(name),
                "precondition: the unique parameter type name must not already exist in the seed data");

        Object result = processor().processItem(standardVo(name));

        // The processor returns the created entity with a generated id.
        assertInstanceOf(StandardParamType.class, result,
                "a StandardParamTypeVo must be created as a StandardParamType entity (the STANDARD DTYPE/kind)");
        Integer createdId = ((ParamType) result).getId();
        assertNotNull(createdId, "createParamType must assign a generated id to the new row");

        // Confirm the row actually persisted: reload from a cleared context by name.
        em.flush();
        em.clear();
        ParamType reloaded = templateExecutionService.getParamTypeByName(name);
        assertNotNull(reloaded, "the created parameter type row must exist in the database");
        assertEquals(name, reloaded.getName(), "the persisted row must carry the imported name");
        assertEquals(createdId, reloaded.getId(), "the persisted row must be the one the processor created");
        assertInstanceOf(StandardParamType.class, reloaded,
                "the persisted row must be a StandardParamType (the STANDARD DTYPE/kind)");
        assertEquals(1, countByName(name), "exactly one row must exist for the unique name");
    }

    // ------------------------------------------------------------------------------------------- //
    // UPDATE branch: existing name -> reuse original id, no duplicate
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updatesExistingParamTypeWithoutCreatingDuplicate() throws Exception {
        String name = "IT-ParamType-" + UUID.randomUUID();

        // First import creates the row (NEW branch).
        ParamType created = (ParamType) processor().processItem(standardVo(name));
        Integer originalId = created.getId();
        assertNotNull(originalId, "precondition: the first import must create the row with an id");

        em.flush();
        em.clear();
        assertEquals(1, countByName(name), "precondition: exactly one row exists after the first import");

        // Second import of a VO with the SAME name must take the UPDATE branch: the existing id is reused.
        Object result = processor().processItem(standardVo(name));

        Integer updatedId = ((ParamType) result).getId();
        assertEquals(originalId, updatedId,
                "the UPDATE branch must reuse the original row's id rather than creating a new row");

        // Confirm no duplicate was inserted and the surviving row keeps the original id.
        em.flush();
        em.clear();
        assertEquals(1, countByName(name), "no duplicate row must be created for an existing parameter type name");
        ParamType reloaded = templateExecutionService.getParamTypeByName(name);
        assertNotNull(reloaded, "the parameter type row must still exist after the update");
        assertEquals(originalId, reloaded.getId(),
                "the surviving row must keep the original id (it was updated in place, not replaced)");
    }

    // ------------------------------------------------------------------------------------------- //
    // A standard type's name: skipped (C4.1)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void skipsAnItemWithAStandardTypesName() throws Exception {
        // The standard types are code, held in memory without an id: without the skip, the item's update of the
        // in-memory type would create a second row. The seed's own 'text' row stays as it is.
        long textRows = countByName("text");
        long numberRows = countByName("number");

        assertNull(processor().processItem(standardVo("text")), "the item is skipped: nothing goes to the writer");
        ListParamTypeVo list = new ListParamTypeVo();
        list.setName("number");
        assertNull(processor().processItem(list), "whatever its kind, a standard type's name is skipped");

        em.flush();
        assertEquals(textRows, countByName("text"), "no row named 'text' is created or removed");
        assertEquals(numberRows, countByName("number"), "no row named 'number' is created or removed");

        ListParamTypeVo otherCase = new ListParamTypeVo();
        otherCase.setName("Text");
        assertNull(processor().processItem(otherCase), "a standard type's name in other capital letters is skipped");
        em.flush();
        assertEquals(0, countByName("Text"), "no row named 'Text' is created");
    }

    // ------------------------------------------------------------------------------------------- //
    // A name an existing type has in other capital letters: refused, never a rename
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void anItemWhoseNameAnExistingTypeHasInOtherCapitalLettersIsRefusedAndRenamesNothing() throws Exception {
        String name = "IT-Lights-" + UUID.randomUUID();
        ParamType existing = (ParamType) processor().processItem(standardVo(name));
        em.flush();

        // The look-up of the type to update is exact, so the item is a new type; names are unique without regard to
        // case, so the new type is refused. A look-up that ignored case would rename the existing type instead
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> processor().processItem(standardVo(name.toLowerCase())));
        assertEquals("Another parameter type has the name \"" + name + "\" (given: \"" + name.toLowerCase() + "\")",
                ex.getMessage(), "the item stops the import, and the message names both spellings");

        em.flush();
        em.clear();
        assertEquals(name, em.find(ParamType.class, existing.getId()).getName(), "the existing type keeps its name");
        assertEquals(0, countByName(name.toLowerCase()), "no second type is made");
    }

    // ------------------------------------------------------------------------------------------- //
    // Unchecked-cast contract
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void rejectsItemOfWrongTypeWithClassCastException() {
        // processItem casts the item to ParamTypeVo; a String is not a ParamTypeVo.
        BatchParamTypeImportProcessor processor = processor();
        assertThrows(ClassCastException.class, () -> processor.processItem("not-a-param-type-vo"),
                "feeding processItem an item that is not a ParamTypeVo must throw ClassCastException");
    }
}
