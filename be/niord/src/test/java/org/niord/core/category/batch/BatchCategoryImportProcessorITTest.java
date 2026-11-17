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
package org.niord.core.category.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.batch.BatchService;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryDesc;
import org.niord.core.category.CategoryService;
import org.niord.core.category.vo.SystemCategoryVo;
import org.niord.core.message.Message;
import org.niord.model.message.CategoryDescVo;
import org.niord.test.BatchJobs;
import org.niord.test.ITTest;
import org.niord.test.Tx;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for {@link BatchCategoryImportProcessor#processItem(Object)}, exercised against the seeded
 * PostgreSQL container of the {@link ITTest} infrastructure.
 *
 * <p>The processor is normally instantiated and driven by the JBeret batch runtime, which injects its
 * {@link CategoryService} dependency, the per-instance {@link BatchData} ({@code job}) and a {@code FileHandler}-backed
 * logger that writes into the batch job folder. To test the business logic without that runtime, this suite uses a
 * {@link TestableProcessor} subclass declared in the same package: it overrides {@code getLog()} to return a no-op
 * {@link java.util.logging.Logger} (so the {@code FileHandler} that targets the non-existent batch folder is never
 * opened) and exposes a {@code wire(...)} method that assigns the real CDI service bean and a hand-built
 * {@code BatchData} directly into the package-private/protected fields.
 *
 * <p>Every test is wrapped in {@link TestTransaction}: {@code importCategory} ultimately calls
 * {@code CategoryService#createCategory}/{@code updateCategoryData} which are {@code @Transactional(REQUIRED)} and join
 * the test transaction, so all category rows created here are rolled back, keeping the shared seed database pristine
 * and the tests independent and repeatable.
 *
 * <p>Behaviour covered: importing a new top-level category (creates a persisted row with a generated id, reloaded and
 * asserted); re-importing the identical VO (matched by parent+name, no duplicate, same id); and the unchecked-cast
 * contract of {@code processItem} (wrong item type throws {@link ClassCastException}); and, through the whole
 * category-import job rather than the hand-wired processor, a root with three children in one chunk.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchCategoryImportProcessorITTest extends ITTest {

    @Autowired
    CategoryService categoryService;

    @Autowired
    BatchService batchService;

    @Autowired
    JobOperator jobOperator;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchCategoryImportProcessor} that can be driven outside the JBeret batch runtime.
     *
     * <p>It replaces the batch-folder {@code FileHandler} logger with a parent-less no-op logger (avoiding any file
     * I/O into a non-existent batch job folder) and lets the test inject the real service bean and the {@code job}
     * batch data directly. Both assigned fields are package-private/protected and reachable from this same package.
     */
    static class TestableProcessor extends BatchCategoryImportProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-category");

        {
            noop.setUseParentHandlers(false);
        }

        @Override
        protected java.util.logging.Logger getLog() {
            return noop;
        }

        void wire(CategoryService cs, BatchData j) {
            this.categoryService = cs;
            this.job = j;
        }
    }

    /** Wires a fresh {@link TestableProcessor} with the injected service and an empty {@link BatchData}. */
    private TestableProcessor freshProcessor() {
        TestableProcessor processor = new TestableProcessor();
        processor.wire(categoryService, new BatchData());
        return processor;
    }

    /** Builds a detached top-level (no parent) {@link SystemCategoryVo} carrying a single English name. */
    private SystemCategoryVo topLevelCategoryVo(String englishName) {
        SystemCategoryVo vo = new SystemCategoryVo();
        CategoryDescVo desc = vo.createDesc("en");
        desc.setName(englishName);
        return vo;
    }

    // ------------------------------------------------------------------------------------------- //
    // (1) New top-level category
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void importsNewTopLevelCategory() throws Exception {
        // A unique name that does not exist in the seed data, so importCategory(create=true) must create it.
        String name = "IT Category " + UUID.randomUUID();
        assertNull(categoryService.findByName(name, "en", null),
                "precondition: the unique category must not already exist");

        SystemCategoryVo vo = topLevelCategoryVo(name);

        Object result = freshProcessor().processItem(vo);

        assertNotNull(result, "processItem must return the created Category");
        Category created = (Category) result;
        assertNotNull(created.getId(), "a newly created category must be assigned a generated id");
        assertEquals(name, created.getDesc("en").getName(),
                "the created category must carry the imported English name");

        // Confirm it actually persisted: reload the row by primary key from a cleared context.
        em.flush();
        em.clear();
        Category reloaded = em.find(Category.class, created.getId());
        assertNotNull(reloaded, "the created category row must exist in the database");
        assertNull(reloaded.getParent(), "the imported category must be a top-level (parent-less) category");
        CategoryDesc reloadedDesc = reloaded.getDesc("en");
        assertNotNull(reloadedDesc, "the persisted category must have an English description");
        assertEquals(name, reloadedDesc.getName(),
                "the persisted category must carry the imported English name");
    }

    // ------------------------------------------------------------------------------------------- //
    // (2) Re-import of the same VO updates rather than duplicates
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void reimportingSameVoUpdatesInPlaceWithoutDuplicating() throws Exception {
        String name = "IT Category " + UUID.randomUUID();

        // First import creates the category.
        Category first = (Category) freshProcessor().processItem(topLevelCategoryVo(name));
        Integer firstId = first.getId();
        assertNotNull(firstId, "the first import must create a category with a generated id");

        em.flush();
        em.clear();

        // Second import of an equivalent VO must resolve the existing category by parent+name (no MRN).
        Category second = (Category) freshProcessor().processItem(topLevelCategoryVo(name));
        assertEquals(firstId, second.getId(),
                "re-importing the same category must reuse the existing row, not create a new one");

        em.flush();
        em.clear();

        // There must be exactly one category row with that name - the import did not duplicate it.
        Long count = em.createQuery(
                        "select count(c) from Category c join c.descs d where d.name = :name", Long.class)
                .setParameter("name", name)
                .getSingleResult();
        assertEquals(1L, count, "re-importing must not create a duplicate category row");
    }

    // ------------------------------------------------------------------------------------------- //
    // (3) Unchecked-cast contract
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void wrongItemTypeThrowsClassCastException() {
        // processItem performs an unchecked cast to SystemCategoryVo; a wrong-typed item must fail fast.
        Object wrongType = new Message();

        assertThrows(ClassCastException.class, () -> freshProcessor().processItem(wrongType),
                "feeding processItem an item that is not a SystemCategoryVo must throw ClassCastException");
    }

    // ------------------------------------------------------------------------------------------- //
    // (4) The whole category-import job: siblings of one parent in one chunk
    // ------------------------------------------------------------------------------------------- //

    /**
     * Runs the category-import job on one root with three leaf children, so one chunk holds three siblings of the
     * same parent, and asserts that it ends COMPLETED with four rows and every child under the root.
     *
     * <p>The processor persists each category, and its parent, through {@code CategoryService.importCategory}. The
     * writer used to merge each item once more; by then the items were detached, and the parent a leaf carried was
     * stale as soon as a later sibling had been added to it, so the merge, cascaded to the parent, threw an
     * {@code OptimisticLockException} and the job ended FAILED (docs/code-data-config-plan.md §7.3). This case is
     * red with that writer.
     *
     * <p>Not {@code @Transactional}: the job runs and commits on its own thread and must see the committed
     * {@code BatchData} row. The rows are read and deleted in fresh transactions.
     */
    @Test
    void categoryImportJobImportsSiblingsUnderOneParent() throws Exception {
        String tag = UUID.randomUUID().toString();
        String root = "IT Root " + tag;
        List<String> leaves = List.of("IT Leaf A " + tag, "IT Leaf B " + tag, "IT Leaf C " + tag);
        String json = "[{\"descs\":[{\"lang\":\"en\",\"name\":\"" + root + "\"}],\"children\":["
                + leaves.stream()
                        .map(n -> "{\"descs\":[{\"lang\":\"en\",\"name\":\"" + n + "\"}]}")
                        .collect(Collectors.joining(","))
                + "]}]";
        try {
            long execId = batchService.startBatchJobWithDataFile("category-import",
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "categories.json",
                    new HashMap<>());

            BatchStatus status = BatchJobs.awaitCompletion(jobOperator, execId);
            assertEquals(BatchStatus.COMPLETED, status,
                    "a category import whose chunk holds siblings of one parent must complete");

            Tx.requiringNew().run(() -> {
                List<Category> imported = em.createQuery(
                                "select distinct c from Category c join c.descs d where d.name like :tag",
                                Category.class)
                        .setParameter("tag", "%" + tag)
                        .getResultList();
                assertEquals(4, imported.size(), "the root and its three children must each be one row");

                Category parent = imported.stream()
                        .filter(c -> root.equals(c.getDesc("en").getName()))
                        .findFirst().orElse(null);
                assertNotNull(parent, "the root must have been created from its children");
                assertNull(parent.getParent(), "the root must stay a root");
                for (String leaf : leaves) {
                    Category child = imported.stream()
                            .filter(c -> leaf.equals(c.getDesc("en").getName()))
                            .findFirst().orElse(null);
                    assertNotNull(child, leaf + " must have been imported");
                    assertNotNull(child.getParent(), leaf + " must have a parent");
                    assertEquals(parent.getId(), child.getParent().getId(), leaf + " must sit under the root");
                }
            });
        } finally {
            // Removing the root cascades to its children
            Tx.requiringNew().run(() -> {
                Category parent = categoryService.findByName(root, "en", null);
                if (parent != null) {
                    categoryService.deleteCategory(parent.getId());
                }
            });
        }
    }
}
