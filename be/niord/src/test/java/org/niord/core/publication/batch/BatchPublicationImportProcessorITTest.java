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
import org.niord.core.publication.Publication;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.PublicationCategoryService;
import org.niord.core.publication.vo.SystemPublicationVo;
import org.niord.model.publication.PublicationCategoryDescVo;
import org.niord.model.publication.PublicationCategoryVo;
import org.niord.test.ITTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link BatchPublicationImportProcessor#processItem(Object)}, exercised against the seeded
 * MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>The processor is normally instantiated and driven by the JBeret batch runtime, which injects its {@link
 * PublicationCategoryService} dependency, the per-instance {@link BatchData} ({@code job}) and a {@code
 * FileHandler}-backed logger that writes into the batch job folder. To test the business logic without that runtime,
 * this suite uses a {@link TestableProcessor} subclass declared in the same package: it overrides {@code getLog()} to
 * return a parent-less no-op {@link java.util.logging.Logger} (so the {@code FileHandler} that targets the batch folder
 * is never opened) and exposes a {@code wire(...)} method that assigns the real CDI service bean and a hand-built
 * {@code BatchData} directly into the package-private/protected fields.
 *
 * <p>Every test is wrapped in {@link TestTransaction}: the {@code createPublicationCategory} write performed by {@code
 * findOrCreatePublicationCategory} joins the test transaction and is rolled back, keeping the shared seed database
 * pristine and the tests independent and repeatable.
 *
 * <p>Behaviour covered: {@code processItem} maps the incoming {@link SystemPublicationVo} to a {@link Publication}
 * and resolves/creates its {@link PublicationCategory} via the category service (asserted by reloading the persisted
 * category row); and the unchecked-cast contract that feeding an item of the wrong type throws {@link
 * ClassCastException}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchPublicationImportProcessorITTest extends ITTest {

    @Autowired
    PublicationCategoryService publicationCategoryService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchPublicationImportProcessor} that can be driven outside the JBeret batch runtime.
     *
     * <p>It replaces the batch-folder {@code FileHandler} logger with a parent-less no-op logger (avoiding any file
     * I/O into a non-existent batch job folder) and lets the test inject the real service bean and the {@code job}
     * batch data directly. All assigned fields are package-private/protected and reachable from this same package.
     */
    static class TestableProcessor extends BatchPublicationImportProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-pub");

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

    /** Wires a fresh {@link TestableProcessor} with the injected bean and a hand-built batch data. */
    private TestableProcessor processor() {
        TestableProcessor processor = new TestableProcessor();
        processor.wire(publicationCategoryService, new BatchData());
        return processor;
    }

    /** Builds a system publication VO carrying a publication id and a category with the given unique category id. */
    private SystemPublicationVo publicationVoWithCategory(String publicationId, String categoryId, String name) {
        PublicationCategoryDescVo desc = new PublicationCategoryDescVo();
        desc.setLang("en");
        desc.setName(name);

        PublicationCategoryVo category = new PublicationCategoryVo();
        category.setCategoryId(categoryId);
        category.setPriority(7);
        category.setPublish(true);
        category.setDescs(List.of(desc));

        SystemPublicationVo vo = new SystemPublicationVo();
        vo.setPublicationId(publicationId);
        vo.setCategory(category);
        return vo;
    }

    // ------------------------------------------------------------------------------------------- //
    // Happy path: category resolved / created
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void processItemResolvesAndCreatesMissingCategory() throws Exception {
        // A category id that does not exist in the seed data, so findOrCreatePublicationCategory must create it.
        String categoryId = "it-cat-" + UUID.randomUUID();
        assertNull(publicationCategoryService.findByCategoryId(categoryId),
                "precondition: the unique category must not already exist");

        String publicationId = "it-pub-" + UUID.randomUUID();
        SystemPublicationVo vo = publicationVoWithCategory(publicationId, categoryId, "Imported category");

        Object result = processor().processItem(vo);

        // The processor returns the mapped (transient) publication.
        assertNotNull(result, "processItem must return the mapped publication");
        assertTrue(result instanceof Publication, "processItem must return a Publication instance");
        Publication publication = (Publication) result;
        assertEquals(publicationId, publication.getPublicationId(),
                "the returned publication must carry the publication id from the VO");

        // Its category must be the resolved/created managed entity, not the detached template built by the
        // Publication constructor.
        PublicationCategory category = publication.getCategory();
        assertNotNull(category, "the publication's category must be resolved by the processor");
        assertEquals(categoryId, category.getCategoryId(),
                "the resolved category must carry the imported category id");
        assertNotNull(category.getId(),
                "the create path of findOrCreatePublicationCategory must assign a generated id to the category");
        assertEquals(7, category.getPriority(), "the created category must carry the imported priority");
        assertTrue(category.isPublish(), "the created category must carry the imported publish flag");

        // Confirm it actually persisted: reload by category id from a cleared context.
        em.flush();
        em.clear();
        PublicationCategory reloaded = publicationCategoryService.findByCategoryId(categoryId);
        assertNotNull(reloaded, "the created category row must exist in the database");
        assertEquals(category.getId(), reloaded.getId(),
                "the reloaded category must be the same row that the processor created");
        assertEquals(categoryId, reloaded.getCategoryId(),
                "the persisted category must carry the imported category id");
        assertEquals(7, reloaded.getPriority(), "the persisted category must carry the imported priority");

        // And the same row is reachable by primary key.
        PublicationCategory byPk = em.find(PublicationCategory.class, reloaded.getId());
        assertNotNull(byPk, "the created category must be reachable by primary key");
        assertEquals(categoryId, byPk.getCategoryId());
    }

    // ------------------------------------------------------------------------------------------- //
    // Message tag filter: run as script, so an import takes the known legacy filters only (F-X43)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void processItemKeepsAKnownMessageTagFilter() throws Exception {
        SystemPublicationVo vo = publicationVoWithCategory(
                "it-pub-" + UUID.randomUUID(), "it-cat-" + UUID.randomUUID(), "Imported category");
        vo.setMessageTagFilter("msg.status == Status.PUBLISHED");

        Publication publication = (Publication) processor().processItem(vo);

        assertEquals("msg.status == Status.PUBLISHED", publication.getMessageTagFilter());
    }

    @Test
    @Transactional
    void processItemRefusesAnUnknownMessageTagFilter() {
        String categoryId = "it-cat-" + UUID.randomUUID();
        SystemPublicationVo vo = publicationVoWithCategory("it-pub-" + UUID.randomUUID(), categoryId, "Imported");
        vo.setMessageTagFilter("msg.status == Status.PUBLISHED && msg.uid != null");

        assertThrows(IllegalArgumentException.class, () -> processor().processItem(vo));
        assertNull(publicationCategoryService.findByCategoryId(categoryId),
                "the item is refused before its category is created");
    }

    // ------------------------------------------------------------------------------------------- //
    // Unchecked-cast contract
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void processItemRejectsWrongItemType() {
        // The processor performs an unchecked cast to SystemPublicationVo; a wrong-typed item must blow up there.
        assertThrows(ClassCastException.class, () -> processor().processItem("not-a-publication-vo"));
    }
}
