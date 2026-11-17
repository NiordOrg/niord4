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
package org.niord.core.publication.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.PublicationCategoryDesc;
import org.niord.core.publication.PublicationCategoryService;
import org.niord.model.publication.PublicationCategoryDescVo;
import org.niord.model.publication.PublicationCategoryVo;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link BatchPublicationCategoryImportProcessor#processItem}, exercised against
 * the seeded MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>The processor is normally instantiated and driven by the JBeret batch runtime, which injects its
 * {@link PublicationCategoryService} dependency, the per-instance {@link BatchData} ({@code job}) and a
 * {@code FileHandler}-backed logger that writes into the batch job folder. To test the business logic
 * without that runtime, this suite uses a {@link TestableProcessor} subclass declared in the same package:
 * it overrides {@code getLog()} to return a parent-less no-op {@link java.util.logging.Logger} (so the
 * {@code FileHandler} that targets the batch folder is never opened) and exposes a {@code wire(...)} method
 * that assigns the real CDI service bean and a hand-built {@code BatchData} directly into the
 * package-private/protected fields.
 *
 * <p>Every test is wrapped in {@link TestTransaction}: the {@code createPublicationCategory} write joins the
 * test transaction and is rolled back, keeping the shared seed database pristine and the tests independent
 * and repeatable. Test categories use unique {@code IT-} categoryIds (with a random suffix) that do not occur
 * in the seed data, so lookups stay isolated.
 *
 * <p>Behaviour covered:
 * <ul>
 *   <li>NEW: an unknown categoryId yields a transient {@link PublicationCategory} (id {@code null}) carrying
 *       the VO's categoryId and localized name, ready for the batch writer to persist.</li>
 *   <li>UPDATE: a known categoryId resolves the managed seeded/created row, copies the changed scalar fields
 *       into it, and returns that SAME managed instance (same id) rather than the transient template.</li>
 *   <li>The unchecked-cast contract: feeding {@code processItem} an item of the wrong type throws
 *       {@link ClassCastException}.</li>
 * </ul>
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchPublicationCategoryImportProcessorITTest extends ITTest {

    @Autowired
    PublicationCategoryService publicationCategoryService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchPublicationCategoryImportProcessor} that can be driven outside the JBeret batch runtime.
     *
     * <p>It replaces the batch-folder {@code FileHandler} logger with a parent-less no-op logger (avoiding any
     * file I/O into a non-existent batch job folder) and lets the test inject the real service bean and the
     * {@code job} batch data directly. The assigned fields are package-private/protected and reachable from
     * this same package.
     */
    static class TestableProcessor extends BatchPublicationCategoryImportProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-pubcat");

        {
            noop.setUseParentHandlers(false);
        }

        @Override
        protected java.util.logging.Logger getLog() {
            return noop;
        }

        void wire(PublicationCategoryService pcs, BatchData j) {
            this.publicationCategoryService = pcs;
            this.job = j;
        }
    }

    /** Wires a fresh {@link TestableProcessor} with the injected service and an empty {@link BatchData}. */
    private TestableProcessor newProcessor() {
        TestableProcessor processor = new TestableProcessor();
        processor.wire(publicationCategoryService, new BatchData());
        return processor;
    }

    /** Builds a detached VO with the given categoryId, priority, publish flag and a single English name. */
    private PublicationCategoryVo vo(String categoryId, int priority, boolean publish, String enName) {
        PublicationCategoryVo vo = new PublicationCategoryVo();
        vo.setCategoryId(categoryId);
        vo.setPriority(priority);
        vo.setPublish(publish);
        PublicationCategoryDescVo desc = new PublicationCategoryDescVo();
        desc.setLang("en");
        desc.setName(enName);
        vo.setDescs(new ArrayList<>(List.of(desc)));
        return vo;
    }

    /** Builds a detached PublicationCategory template with a single English description. */
    private PublicationCategory newCategory(String categoryId, String name) {
        PublicationCategory category = new PublicationCategory();
        category.setCategoryId(categoryId);
        PublicationCategoryDesc desc = category.createDesc("en");
        desc.setName(name);
        return category;
    }

    // ------------------------------------------------------------------------------------------- //
    // NEW: unknown categoryId -> transient PublicationCategory
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void processItemReturnsTransientCategoryForUnknownCategoryId() throws Exception {
        String categoryId = "IT-new-" + UUID.randomUUID();
        // Precondition: nothing with this categoryId exists yet, so the processor must take the "new" branch.
        assertNull(publicationCategoryService.findByCategoryId(categoryId),
                "precondition: the unique categoryId must not already exist");

        PublicationCategoryVo voIn = vo(categoryId, 17, true, "Fresh Category");

        Object result = newProcessor().processItem(voIn);

        assertTrue(result instanceof PublicationCategory,
                "the processor must return a PublicationCategory entity");
        PublicationCategory created = (PublicationCategory) result;

        // It is the transient new entity built from the VO - never persisted by processItem, so no id yet.
        assertNull(created.getId(),
                "an unknown categoryId must yield a transient (un-persisted) category with a null id");
        assertEquals(categoryId, created.getCategoryId(),
                "the new category must carry the VO's categoryId");
        assertEquals(17, created.getPriority(), "the new category must carry the VO's priority");
        assertTrue(created.isPublish(), "the new category must carry the VO's publish flag");
        assertEquals("Fresh Category", created.getDesc("en").getName(),
                "the new category must carry the VO's localized English name");

        // It must genuinely be a new, unsaved category: still not findable in the DB.
        em.flush();
        assertNull(em.find(PublicationCategory.class, created.getId() == null ? -1 : created.getId()),
                "processItem must not itself persist the new category - that is the writer's job");
    }

    // ------------------------------------------------------------------------------------------- //
    // UPDATE: known categoryId -> same managed orig, fields updated
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void processItemUpdatesAndReturnsExistingManagedCategory() throws Exception {
        String categoryId = "IT-upd-" + UUID.randomUUID();

        // Persist an existing category through the real service so the processor's lookup resolves it.
        PublicationCategory existingTemplate = newCategory(categoryId, "Original Name");
        existingTemplate.setPriority(3);
        existingTemplate.setPublish(false);
        PublicationCategory existing = publicationCategoryService.createPublicationCategory(existingTemplate);
        Integer existingId = existing.getId();
        em.flush();
        em.clear();
        assertNotNull(existingId, "precondition: the existing category must be persisted with an id");

        // Same categoryId, but changed scalar fields. Keep the SAME English language so updatePublicationCategory
        // copies the name in place; assertions read in-memory state (processItem does not flush).
        PublicationCategoryVo voIn = vo(categoryId, 99, true, "Updated Name");

        Object result = newProcessor().processItem(voIn);

        assertTrue(result instanceof PublicationCategory,
                "the processor must return a PublicationCategory entity");
        PublicationCategory updated = (PublicationCategory) result;

        // The processor must return the SAME managed row that already existed - not the transient template.
        assertEquals(existingId, updated.getId(),
                "a known categoryId must resolve and return the existing managed category, not a new one");
        assertEquals(categoryId, updated.getCategoryId(), "the categoryId must be unchanged");
        assertEquals(99, updated.getPriority(), "the existing category's priority must be updated from the VO");
        assertTrue(updated.isPublish(), "the existing category's publish flag must be updated from the VO");
        assertEquals("Updated Name", updated.getDesc("en").getName(),
                "the existing category's localized name must be updated from the VO");

        // This test asserts the resolved-and-updated managed instance in memory; the flush-time behaviour
        // (now that PC1 is fixed, updatePublicationCategory updates descs in place and flushes cleanly) is
        // covered by updatingAnExistingCategoryWithADescriptionUpdatesInPlaceAndFlushes_PUBCAT1 below.
    }

    // ------------------------------------------------------------------------------------------- //
    // PUBCAT1: updating an existing category's localized name updates in place and flushes cleanly
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updatingAnExistingCategoryWithADescriptionUpdatesInPlaceAndFlushes_PUBCAT1() throws Exception {
        String categoryId = "IT-updflush-" + UUID.randomUUID();
        PublicationCategory existing = publicationCategoryService.createPublicationCategory(newCategory(categoryId, "Original"));
        Integer id = existing.getId();
        assertNotNull(id);
        em.flush();
        em.clear();

        // Take the update branch with a (changed) English description present.
        newProcessor().processItem(vo(categoryId, 5, true, "Renamed"));

        // FIXED (PC1): updatePublicationCategory updates the 'en' desc in place by language instead of
        // clear()+re-add, so the flush no longer violates the PublicationCategoryDesc (lang, entity) unique key.
        em.flush();
        em.clear();

        PublicationCategory reloaded = em.find(PublicationCategory.class, id);
        assertEquals(5, reloaded.getPriority(), "the priority is updated from the VO");
        assertTrue(reloaded.isPublish(), "the publish flag is updated from the VO");
        assertEquals(1, reloaded.getDescs().size(), "the single English description is updated in place");
        assertEquals("Renamed", reloaded.getDesc("en").getName(), "the localized name is updated from the VO");
    }

    // ------------------------------------------------------------------------------------------- //
    // Wrong-type contract
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void processItemThrowsClassCastExceptionForWrongType() {
        // The processor performs an unchecked cast to PublicationCategoryVo; a different type must blow up.
        assertThrows(ClassCastException.class, () -> newProcessor().processItem("not-a-publication-category-vo"));
    }
}
