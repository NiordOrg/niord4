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
package org.niord.core.publication;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.niord.core.web.NiordWebException;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link PublicationCategoryService} persistence behaviour, run against the
 * seeded MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the shared
 * seed database pristine and the tests independent. Test categories use an {@code IT-} categoryId prefix
 * that does not occur in the seed data (the four seeded categories are {@code dk-*}), so lookups stay
 * isolated from seed data and from each other.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class PublicationCategoryServiceITTest extends ITTest {

    @Autowired
    PublicationCategoryService service;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached PublicationCategory template with a single English description. */
    private PublicationCategory newCategory(String categoryId, String name) {
        PublicationCategory category = new PublicationCategory();
        category.setCategoryId(categoryId);
        PublicationCategoryDesc desc = category.createDesc("en");
        desc.setName(name);
        return category;
    }

    // ------------------------------------------------------------------------------------------- //
    // createPublicationCategory
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createPersistsCategoryWithGeneratedIdAndFields() {
        PublicationCategory template = newCategory("IT-create-basic", "IT Basic");
        template.setPriority(42);
        template.setPublish(true);

        PublicationCategory created = service.createPublicationCategory(template);
        Integer id = created.getId();
        assertNotNull(id, "createPublicationCategory must assign a generated id");

        em.flush();
        em.clear();

        PublicationCategory reloaded = em.find(PublicationCategory.class, id);
        assertNotNull(reloaded, "publication category row must exist in the database");
        assertEquals("IT-create-basic", reloaded.getCategoryId());
        assertEquals(42, reloaded.getPriority());
        assertTrue(reloaded.isPublish());
        assertEquals("IT Basic", reloaded.getDesc("en").getName());
    }

    @Test
    @Transactional
    void createDefaultsPriorityToZeroAndPublishToFalse() {
        PublicationCategory created = service.createPublicationCategory(
                newCategory("IT-create-defaults", "IT Defaults"));
        Integer id = created.getId();

        em.flush();
        em.clear();

        PublicationCategory reloaded = em.find(PublicationCategory.class, id);
        assertEquals(0, reloaded.getPriority(), "priority must default to 0");
        assertFalse(reloaded.isPublish(), "publish must default to false");
    }

    @Test
    @Transactional
    void createPersistsMultipleLanguageDescriptions() {
        PublicationCategory template = newCategory("IT-create-multilang", "English Name");
        template.checkCreateDesc("da").setName("Dansk Navn");

        PublicationCategory created = service.createPublicationCategory(template);
        Integer id = created.getId();

        em.flush();
        em.clear();

        PublicationCategory reloaded = em.find(PublicationCategory.class, id);
        assertEquals(2, reloaded.getDescs().size());
        assertEquals("English Name", reloaded.getDesc("en").getName());
        assertEquals("Dansk Navn", reloaded.getDesc("da").getName());
    }

    @Test
    @Transactional
    void createRejectsCategoryThatAlreadyHasAnId() {
        PublicationCategory template = newCategory("IT-create-withid", "Has Id");
        template.setId(987_654); // pretend it is already persisted

        // isNew() == false, so the service must refuse to create it.
        assertThrows(IllegalArgumentException.class,
                () -> service.createPublicationCategory(template));
    }

    @Test
    @Transactional
    void createRejectsDuplicateCategoryIdWith409() {
        service.createPublicationCategory(newCategory("IT-create-dup", "First"));
        em.flush();

        // createPublicationCategory now pre-checks the business key, so the second create throws
        // NiordWebException(409) before any flush (no longer a raw DB unique-constraint violation).
        NiordWebException ex = assertThrows(NiordWebException.class,
                () -> service.createPublicationCategory(newCategory("IT-create-dup", "Second")));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    @Transactional
    void createRejectsNullCategoryId() {
        // PublicationCategory.categoryId is annotated @NotNull. On PostgreSQL the column is declared
        // NOT NULL, so a null categoryId is rejected at the database layer when the insert is flushed.
        // (Under the previous MySQL setup the column was nullable, so a null categoryId was leniently
        // persisted; PostgreSQL now enforces the constraint as the annotation intends.)

        // Happy path: a category with a valid (non-null) categoryId persists cleanly.
        PublicationCategory valid = newCategory("IT-create-notnull-ok", "Has Id");
        PublicationCategory createdValid = service.createPublicationCategory(valid);
        assertNotNull(createdValid.getId(), "a category with a valid categoryId must persist");
        em.flush();
        em.clear();
        assertEquals("IT-create-notnull-ok",
                em.find(PublicationCategory.class, createdValid.getId()).getCategoryId());

        // Negative path: a null categoryId must be rejected by the NOT NULL constraint on flush.
        PublicationCategory nullId = new PublicationCategory();
        nullId.createDesc("en").setName("No Id");
        service.createPublicationCategory(nullId);
        assertThrows(PersistenceException.class, () -> em.flush(),
                "a null categoryId must violate the NOT NULL constraint");
    }

    // ------------------------------------------------------------------------------------------- //
    // findByCategoryId
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByCategoryIdReturnsCreatedCategory() {
        PublicationCategory created = service.createPublicationCategory(
                newCategory("IT-find-me", "Find Me"));
        em.flush();
        em.clear();

        PublicationCategory found = service.findByCategoryId("IT-find-me");
        assertNotNull(found);
        assertEquals(created.getId(), found.getId());
        assertEquals("Find Me", found.getDesc("en").getName());
    }

    @Test
    @Transactional
    void findByCategoryIdReadsStableSeededCategory() {
        // A stable seeded read: dk-dma-publications has priority 50 and publish == true.
        PublicationCategory seeded = service.findByCategoryId("dk-dma-publications");
        assertNotNull(seeded, "the seeded dk-dma-publications category must exist");
        assertEquals(50, seeded.getPriority());
        assertTrue(seeded.isPublish());
    }

    @Test
    @Transactional
    void findByCategoryIdReturnsNullForUnknownId() {
        assertNull(service.findByCategoryId("IT-does-not-exist"));
    }

    @Test
    @Transactional
    void findByCategoryIdReturnsNullForNullArgument() {
        assertNull(service.findByCategoryId(null));
    }

    // ------------------------------------------------------------------------------------------- //
    // getPublicationCategories
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getPublicationCategoriesIncludesSeededAndCreated() {
        List<PublicationCategory> before = service.getPublicationCategories();
        int beforeCount = before.size();
        assertTrue(beforeCount >= 4, "at least the four seeded categories must be present");

        service.createPublicationCategory(newCategory("IT-list-new", "Listed"));
        em.flush();
        em.clear();

        List<PublicationCategory> after = service.getPublicationCategories();
        assertEquals(beforeCount + 1, after.size(), "the new category must appear in the list");
        assertTrue(after.stream().anyMatch(c -> "IT-list-new".equals(c.getCategoryId())));
    }

    @Test
    @Transactional
    void getPublicationCategoriesIsOrderedByPriorityAscending() {
        List<PublicationCategory> categories = service.getPublicationCategories();
        assertFalse(categories.isEmpty());

        int previous = Integer.MIN_VALUE;
        for (PublicationCategory category : categories) {
            assertTrue(category.getPriority() >= previous,
                    "categories must be ordered by ascending priority");
            previous = category.getPriority();
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // updatePublicationCategory
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updatePersistsPriorityPublishAndDescriptions() {
        // FIXED (see docs/potential-errors.md, PC1): PublicationCategory.updatePublicationCategory no
        // longer clear()s the descs before copyDescsAndRemoveBlanks(...). The existing "en" description
        // is updated in place by language, so a same-language update no longer collides on the unique
        // (lang, entity_id) constraint - it persists the new priority/publish/name.
        PublicationCategory created = service.createPublicationCategory(
                newCategory("IT-update", "Original"));
        Integer id = created.getId();
        em.flush();
        em.clear();
        assertNotNull(id);

        PublicationCategory template = newCategory("IT-update", "Renamed");
        template.setPriority(7);
        template.setPublish(true);

        service.updatePublicationCategory(template);
        em.flush();
        em.clear();

        PublicationCategory reloaded = em.find(PublicationCategory.class, id);
        assertEquals(7, reloaded.getPriority(), "the priority is persisted");
        assertTrue(reloaded.isPublish(), "the publish flag is persisted");
        assertEquals(1, reloaded.getDescs().size(), "the single English description is retained");
        assertEquals("Renamed", reloaded.getDesc("en").getName(), "the renamed English name is persisted");
    }

    @Test
    @Transactional
    void updateAddsAndReplacesDescriptions() {
        // FIXED (see docs/potential-errors.md, PC1): the template retains the existing "en" language
        // (renamed) and adds a new "da". With the clear() removed, "en" is updated in place and "da" is
        // created, so the update ends with both descriptions instead of colliding on flush.
        PublicationCategory created = service.createPublicationCategory(
                newCategory("IT-update-descs", "English"));
        Integer id = created.getId();
        em.flush();
        em.clear();
        assertNotNull(id);

        PublicationCategory template = newCategory("IT-update-descs", "English Updated");
        template.checkCreateDesc("da").setName("Dansk");

        service.updatePublicationCategory(template);
        em.flush();
        em.clear();

        PublicationCategory reloaded = em.find(PublicationCategory.class, id);
        assertEquals(2, reloaded.getDescs().size(), "the update ends with both descriptions");
        assertEquals("English Updated", reloaded.getDesc("en").getName(), "the English name is updated in place");
        assertEquals("Dansk", reloaded.getDesc("da").getName(), "the new Danish description is added");
    }

    @Test
    @Transactional
    void updateRemovesBlankDescriptions() {
        // FIXED (see docs/potential-errors.md, PC1): the template keeps a non-blank "en" description and
        // blanks the "da" one. With the clear() removed, "en" is updated in place and the blanked "da"
        // is dropped by removeIf(!descDefined()), so the update succeeds and ends with only "en".
        PublicationCategory created = newCategory("IT-update-blank", "English");
        created.checkCreateDesc("da").setName("Dansk");
        created = service.createPublicationCategory(created);
        Integer id = created.getId();
        em.flush();
        em.clear();
        assertNotNull(id);

        PublicationCategory template = newCategory("IT-update-blank", "English");
        template.checkCreateDesc("da").setName(""); // blank out the Danish description

        service.updatePublicationCategory(template);
        em.flush();
        em.clear();

        PublicationCategory reloaded = em.find(PublicationCategory.class, id);
        assertEquals(1, reloaded.getDescs().size(), "only the blanked Danish description is removed");
        assertEquals("English", reloaded.getDesc("en").getName(), "the English description is retained");
        assertNull(reloaded.getDesc("da"), "the blanked Danish description is gone");
    }

    @Test
    @Transactional
    void updateThrowsForNonExistingCategory() {
        PublicationCategory template = newCategory("IT-update-missing", "Ghost");

        assertThrows(IllegalArgumentException.class,
                () -> service.updatePublicationCategory(template));
    }

    @Test
    @Transactional
    void updateDoesNotCreateNewRow() {
        long before = service.getPublicationCategories().size();

        PublicationCategory template = newCategory("IT-update-norow", "Ghost");
        assertThrows(IllegalArgumentException.class,
                () -> service.updatePublicationCategory(template));

        // The failed update must not have persisted anything.
        assertNull(service.findByCategoryId("IT-update-norow"));
        assertEquals(before, service.getPublicationCategories().size());
    }

    // ------------------------------------------------------------------------------------------- //
    // deletePublicationCategory
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteRemovesCategoryFromDatabase() {
        PublicationCategory created = service.createPublicationCategory(
                newCategory("IT-delete", "Delete Me"));
        Integer id = created.getId();
        em.flush();

        assertTrue(service.deletePublicationCategory("IT-delete"));
        em.flush();
        em.clear();

        assertNull(em.find(PublicationCategory.class, id), "deleted category must be gone from the DB");
        assertNull(service.findByCategoryId("IT-delete"));
    }

    @Test
    @Transactional
    void deleteReturnsFalseForUnknownId() {
        assertFalse(service.deletePublicationCategory("IT-delete-unknown"));
    }

    @Test
    @Transactional
    void deleteRemovesAssociatedDescriptions() {
        PublicationCategory created = newCategory("IT-delete-descs", "English");
        created.checkCreateDesc("da").setName("Dansk");
        created = service.createPublicationCategory(created);
        Integer descId = created.getDesc("en").getId();
        assertNotNull(descId);
        em.flush();

        assertTrue(service.deletePublicationCategory("IT-delete-descs"));
        em.flush();
        em.clear();

        assertNull(em.find(PublicationCategoryDesc.class, descId),
                "orphan-removal must cascade-delete the category's descriptions");
    }

    // ------------------------------------------------------------------------------------------- //
    // findOrCreatePublicationCategory
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findOrCreateReturnsNullForNullTemplate() {
        assertNull(service.findOrCreatePublicationCategory(null));
    }

    @Test
    @Transactional
    void findOrCreateReturnsExistingCategoryWithoutCreatingNew() {
        PublicationCategory created = service.createPublicationCategory(
                newCategory("IT-foc-existing", "Existing"));
        Integer id = created.getId();
        em.flush();
        em.clear();

        long before = service.getPublicationCategories().size();

        PublicationCategory template = newCategory("IT-foc-existing", "Should Be Ignored");
        PublicationCategory result = service.findOrCreatePublicationCategory(template);

        assertEquals(id, result.getId(), "must return the existing category, not a new one");
        // The supplied name must be ignored since the category already exists.
        assertEquals("Existing", result.getDesc("en").getName());
        assertEquals(before, service.getPublicationCategories().size(), "no new row may be created");
    }

    @Test
    @Transactional
    void findOrCreateCreatesCategoryWhenMissing() {
        long before = service.getPublicationCategories().size();

        PublicationCategory template = newCategory("IT-foc-missing", "Created On Demand");
        template.setPriority(13);
        PublicationCategory result = service.findOrCreatePublicationCategory(template);

        assertNotNull(result.getId(), "a missing category must be created with a generated id");
        em.flush();
        em.clear();

        PublicationCategory reloaded = em.find(PublicationCategory.class, result.getId());
        assertNotNull(reloaded, "the created category must be persisted");
        assertEquals("IT-foc-missing", reloaded.getCategoryId());
        assertEquals(13, reloaded.getPriority());
        assertEquals("Created On Demand", reloaded.getDesc("en").getName());
        assertEquals(before + 1, service.getPublicationCategories().size());
    }
}
