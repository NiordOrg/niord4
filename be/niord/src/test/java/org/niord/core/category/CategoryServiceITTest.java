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
package org.niord.core.category;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.test.ITTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link CategoryService} persistence behaviour, run against the seeded
 * database of the {@link ITTest} infrastructure.
 *
 * <p>Each test is wrapped in a transaction so all writes are rolled back, keeping the shared seed
 * database pristine and the tests independent. Test categories use an {@code IT-} name prefix that
 * does not occur in the seed data, so searches stay isolated from the seeded Danish categories. The
 * seeded "Light"/"Fyr" root category is read directly where that adds value, resolved by its stable
 * business key (the English name "Light") rather than by a hardcoded numeric id, since PostgreSQL
 * per-entity sequences assign different ids than the legacy MySQL shared sequence.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class CategoryServiceITTest extends ITTest {

    @Autowired
    CategoryService categoryService;

    @PersistenceContext
    EntityManager em;

    /**
     * Resolves the seeded "Light" root category by its stable business key (English name "Light",
     * type CATEGORY, no parent) rather than by a hardcoded numeric id. PostgreSQL per-entity sequences
     * assign different ids than the old MySQL shared sequence, so the seed's numeric ids are not stable.
     */
    private Category seededLightRoot() {
        Category light = categoryService.findByName("Light", "en", null);
        assertNotNull(light, "the seeded 'Light' root category must exist in the seed data");
        assertEquals(CategoryType.CATEGORY, light.getType(), "the 'Light' root must be a CATEGORY");
        assertNull(light.getParent(), "the 'Light' root must have no parent");
        return light;
    }

    /** Builds a detached Category template with a single description. */
    private Category newCategory(String name, String lang) {
        Category category = new Category();
        category.checkCreateDesc(lang).setName(name);
        return category;
    }

    private CategorySearchParams params() {
        CategorySearchParams p = new CategorySearchParams();
        p.maxSize(100);
        return p;
    }

    // ------------------------------------------------------------------------------------------- //
    // createCategory
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createCategoryPersistsRootWithGeneratedIdAndLineage() {
        Category created = categoryService.createCategory(newCategory("IT-Root", "en"), null);
        Integer id = created.getId();
        assertNotNull(id, "createCategory must assign a generated id");

        em.flush();
        em.clear();

        Category reloaded = em.find(Category.class, id);
        assertNotNull(reloaded, "category row must exist in the database");
        assertEquals("/" + id + "/", reloaded.getLineage());
        assertTrue(reloaded.isActive(), "new categories default to active");
        assertEquals(CategoryType.CATEGORY, reloaded.getType(), "new categories default to CATEGORY type");
        assertEquals("IT-Root", reloaded.getDesc("en").getName());
    }

    @Test
    @Transactional
    void createCategoryUnderParentSetsLineageAndParent() {
        Category parent = categoryService.createCategory(newCategory("IT-Parent", "en"), null);
        Category child = categoryService.createCategory(newCategory("IT-Child", "en"), parent.getId());
        Integer childId = child.getId();

        em.flush();
        em.clear();

        Category reloaded = em.find(Category.class, childId);
        assertEquals("/" + parent.getId() + "/" + childId + "/", reloaded.getLineage());
        assertEquals(parent.getId(), reloaded.getParent().getId());
    }

    @Test
    @Transactional
    void createCategoryUnderSeededRootChainsLineageFromSeedAncestor() {
        Integer seedLightRootId = seededLightRoot().getId();

        Category child = categoryService.createCategory(newCategory("IT-UnderSeed", "en"), seedLightRootId);
        Integer childId = child.getId();

        em.flush();
        em.clear();

        Category reloaded = em.find(Category.class, childId);
        assertEquals("/" + seedLightRootId + "/" + childId + "/", reloaded.getLineage());
        assertEquals(seedLightRootId, reloaded.getParent().getId());
    }

    @Test
    @Transactional
    void createCategoryWithTemplateTypeAndAtonFilterPersistsThoseFields() {
        Category template = newCategory("IT-Template", "en");
        template.setType(CategoryType.TEMPLATE);
        template.setAtonFilter("aton.kv('seamark:type', 'light.*')");
        template.setMrn("urn:mrn:dma:category:it-template");

        Category created = categoryService.createCategory(template, null);
        Integer id = created.getId();

        em.flush();
        em.clear();

        Category reloaded = em.find(Category.class, id);
        assertEquals(CategoryType.TEMPLATE, reloaded.getType());
        assertEquals("aton.kv('seamark:type', 'light.*')", reloaded.getAtonFilter());
        assertEquals("urn:mrn:dma:category:it-template", reloaded.getMrn());
    }

    @Test
    @Transactional
    void creatingActiveChildReactivatesInactiveParent() {
        Category parent = categoryService.createCategory(newCategory("IT-UpParent", "en"), null);
        parent.setActive(false);
        em.flush(); // persist the now-inactive parent

        // createCategory runs updateActiveFlag on the new (active) child, which reactivates the parent.
        categoryService.createCategory(newCategory("IT-UpChild", "en"), parent.getId());

        em.flush();
        em.clear();

        assertTrue(em.find(Category.class, parent.getId()).isActive(),
                "an active child must reactivate its inactive parent");
    }

    @Test
    @Transactional
    void createCategoryWithUnknownParentIdThrows() {
        // getByPrimaryKey returns null for an unknown id, so addChild dereferences null.
        assertThrows(RuntimeException.class,
                () -> categoryService.createCategory(newCategory("IT-NoParent", "en"), -999_999));
    }

    @Test
    @Transactional
    void duplicateLanguageDescriptionViolatesUniqueConstraint() {
        Category category = newCategory("IT-Dup", "en");
        category.createDesc("en").setName("IT-Dup-2"); // a second "en" description on the same category

        // The (lang, entity_id) unique constraint is only observable at the database layer.
        assertThrows(PersistenceException.class, () -> categoryService.createCategory(category, null));
    }

    // ------------------------------------------------------------------------------------------- //
    // searchCategories
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByExactNameIsCaseInsensitiveAndNotSubstring() {
        categoryService.createCategory(newCategory("IT-Exact-Name", "en"), null);
        em.flush();

        List<Category> exact = categoryService.searchCategories(
                params().name("it-exact-name").language("en").exact(true).inactive(true));
        assertEquals(1, exact.size());
        assertEquals("IT-Exact-Name", exact.get(0).getDesc("en").getName());

        // A substring with exact matching must NOT match.
        assertTrue(categoryService.searchCategories(
                params().name("Exact-Name").language("en").exact(true).inactive(true)).isEmpty());
    }

    @Test
    @Transactional
    void searchBySubstringMatchesMultiple() {
        categoryService.createCategory(newCategory("IT-Substr-One", "en"), null);
        categoryService.createCategory(newCategory("IT-Substr-Two", "en"), null);
        em.flush();

        List<Category> result = categoryService.searchCategories(
                params().name("IT-Substr").language("en").exact(false).inactive(true));
        assertEquals(2, result.size());
    }

    @Test
    @Transactional
    void searchExcludesInactiveByDefaultButIncludesWhenRequested() {
        Category inactive = categoryService.createCategory(newCategory("IT-Inactive-Flag", "en"), null);
        inactive.setActive(false);
        em.flush();

        assertTrue(categoryService.searchCategories(
                        params().name("IT-Inactive-Flag").language("en").exact(true)).isEmpty(),
                "inactive categories are excluded by default");
        assertEquals(1, categoryService.searchCategories(
                params().name("IT-Inactive-Flag").language("en").exact(true).inactive(true)).size());
    }

    @Test
    @Transactional
    void searchByTypeFiltersOnCategoryType() {
        Category template = newCategory("IT-Type-Template", "en");
        template.setType(CategoryType.TEMPLATE);
        categoryService.createCategory(template, null);
        em.flush();

        assertEquals(1, categoryService.searchCategories(
                params().name("IT-Type-Template").language("en").exact(true).inactive(true)
                        .type(CategoryType.TEMPLATE)).size());
        assertTrue(categoryService.searchCategories(
                params().name("IT-Type-Template").language("en").exact(true).inactive(true)
                        .type(CategoryType.CATEGORY)).isEmpty());
    }

    @Test
    @Transactional
    void searchByParentIdReturnsDirectChildren() {
        Category parent = categoryService.createCategory(newCategory("IT-SearchParent", "en"), null);
        Category child = categoryService.createCategory(newCategory("IT-SearchChild", "en"), parent.getId());
        em.flush();

        List<Category> result = categoryService.searchCategories(
                params().parentId(parent.getId()).inactive(true));
        assertEquals(1, result.size());
        assertEquals(child.getId(), result.get(0).getId());
    }

    @Test
    @Transactional
    void searchByAncestorIdReturnsWholeSubtreeButNotTheAncestorItself() {
        Category root = categoryService.createCategory(newCategory("IT-AncRoot", "en"), null);
        Category child = categoryService.createCategory(newCategory("IT-AncChild", "en"), root.getId());
        Category grandChild = categoryService.createCategory(newCategory("IT-AncGrandChild", "en"), child.getId());
        em.flush();

        List<Integer> ids = categoryService.searchCategories(
                        params().ancestorId(root.getId()).inactive(true)).stream()
                .map(Category::getId)
                .collect(java.util.stream.Collectors.toList());

        // The ancestor filter matches lineage "<ancestorLineage>%/", i.e. strict descendants only.
        assertFalse(ids.contains(root.getId()), "ancestor search excludes the ancestor itself");
        assertTrue(ids.contains(child.getId()), "ancestor search includes direct children");
        assertTrue(ids.contains(grandChild.getId()), "ancestor search includes deeper descendants");
    }

    @Test
    @Transactional
    void searchByLanguageMatchesOnlyTheGivenLanguageDescription() {
        Category category = newCategory("IT-Lang-EN", "en");
        category.checkCreateDesc("da").setName("IT-Lang-DA");
        categoryService.createCategory(category, null);
        em.flush();

        // The English name only exists in the "en" description.
        assertEquals(1, categoryService.searchCategories(
                params().name("IT-Lang-EN").language("en").exact(true).inactive(true)).size());
        assertTrue(categoryService.searchCategories(
                        params().name("IT-Lang-EN").language("da").exact(true).inactive(true)).isEmpty(),
                "matching the English name against the Danish description must not hit");
    }

    @Test
    @Transactional
    void searchRespectsMaxSizeLimit() {
        categoryService.createCategory(newCategory("IT-Limit-1", "en"), null);
        categoryService.createCategory(newCategory("IT-Limit-2", "en"), null);
        categoryService.createCategory(newCategory("IT-Limit-3", "en"), null);
        em.flush();

        CategorySearchParams p = new CategorySearchParams();
        p.maxSize(2);
        List<Category> result = categoryService.searchCategories(
                p.name("IT-Limit").language("en").exact(false).inactive(true));
        assertEquals(2, result.size(), "the result must be capped at maxSize");
    }

    @Test
    @Transactional
    void searchSeededLightCategoryByExactNameReturnsKnownRow() {
        // A stable seed read: the "Light" root CATEGORY (resolved by business key, not a numeric id).
        Integer seedLightRootId = seededLightRoot().getId();

        List<Category> result = categoryService.searchCategories(
                params().name("Light").language("en").exact(true).inactive(true)
                        .type(CategoryType.CATEGORY));
        assertTrue(result.stream().anyMatch(c -> seedLightRootId.equals(c.getId())),
                "the seeded 'Light' root category must be found by exact name");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateCategoryData / checkUpdateCategoryData
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateCategoryDataPersistsAllFields() {
        Category created = categoryService.createCategory(newCategory("IT-Upd", "en"), null);
        Integer id = created.getId();

        Category template = newCategory("IT-Upd-Renamed", "en");
        template.setMrn("urn:mrn:dma:category:it-upd");
        template.setType(CategoryType.TEMPLATE);
        template.setSiblingSortOrder(5.0);
        template.setAtonFilter("aton.k('seamark:light')");
        template.getEditorFields().add("type");
        template.setMessageId("IT-MSG-1");

        categoryService.updateCategoryData(created, template);
        em.flush();
        em.clear();

        Category reloaded = em.find(Category.class, id);
        assertEquals("urn:mrn:dma:category:it-upd", reloaded.getMrn());
        assertEquals(CategoryType.TEMPLATE, reloaded.getType());
        assertEquals(5.0, reloaded.getSiblingSortOrder(), 0.0);
        assertEquals("aton.k('seamark:light')", reloaded.getAtonFilter());
        assertEquals("IT-Upd-Renamed", reloaded.getDesc("en").getName());
        assertEquals(List.of("type"), reloaded.getEditorFields());
        assertEquals("IT-MSG-1", reloaded.getMessageId());
    }

    @Test
    @Transactional
    void updateCategoryDataByIdLoadsOriginalAndPersistsChanges() {
        Category created = categoryService.createCategory(newCategory("IT-UpdById", "en"), null);
        Integer id = created.getId();
        em.flush();
        em.clear();

        // The single-arg overload loads the original by id from the database.
        Category template = newCategory("IT-UpdById-Renamed", "en");
        template.setId(id);
        categoryService.updateCategoryData(template);
        em.flush();
        em.clear();

        assertEquals("IT-UpdById-Renamed", em.find(Category.class, id).getDesc("en").getName());
    }

    @Test
    @Transactional
    void updateCategoryDataRemovesBlankDescriptions() {
        Category created = newCategory("IT-Blank", "en");
        created.checkCreateDesc("da").setName("Blah");
        created = categoryService.createCategory(created, null);
        Integer id = created.getId();

        Category template = newCategory("IT-Blank", "en");
        template.checkCreateDesc("da").setName(""); // blank out the Danish description

        categoryService.updateCategoryData(created, template);
        em.flush();
        em.clear();

        Category reloaded = em.find(Category.class, id);
        assertNull(reloaded.getDesc("da"), "blank descriptions must be removed");
        assertEquals("IT-Blank", reloaded.getDesc("en").getName());
    }

    @Test
    @Transactional
    void updateCategoryDataDeactivationCascadesToChildren() {
        Category parent = categoryService.createCategory(newCategory("IT-DeactParent", "en"), null);
        Category child = categoryService.createCategory(newCategory("IT-DeactChild", "en"), parent.getId());
        Integer childId = child.getId();
        em.flush();

        // updateCategoryData runs updateActiveFlag, which pushes inactivity down to children.
        Category template = newCategory("IT-DeactParent", "en");
        template.setActive(false);
        categoryService.updateCategoryData(parent, template);
        em.flush();
        em.clear();

        assertFalse(em.find(Category.class, childId).isActive(),
                "deactivating a parent must cascade inactivity to its children");
    }

    @Test
    @Transactional
    void checkUpdateCategoryDataIsANoOpWhenNothingChanged() {
        Category created = categoryService.createCategory(newCategory("IT-NoChange", "en"), null);
        em.flush();

        Category identicalTemplate = newCategory("IT-NoChange", "en");
        Category result = categoryService.checkUpdateCategoryData(created, identicalTemplate);

        assertSame(created, result, "an unchanged category must be returned as-is, without an update");
    }

    @Test
    @Transactional
    void checkUpdateCategoryDataAppliesChangesWhenDataDiffers() {
        Category created = categoryService.createCategory(newCategory("IT-ChkChange", "en"), null);
        Integer id = created.getId();
        em.flush();

        Category template = newCategory("IT-ChkChange-Renamed", "en");
        categoryService.checkUpdateCategoryData(created, template);
        em.flush();
        em.clear();

        assertEquals("IT-ChkChange-Renamed", em.find(Category.class, id).getDesc("en").getName());
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteCategory / moveCategory / changeSortOrder
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteCategoryCascadesToSubCategories() {
        Category parent = categoryService.createCategory(newCategory("IT-DelParent", "en"), null);
        Category child = categoryService.createCategory(newCategory("IT-DelChild", "en"), parent.getId());
        Integer parentId = parent.getId();
        Integer childId = child.getId();
        em.flush();

        assertTrue(categoryService.deleteCategory(parentId));
        em.flush();
        em.clear();

        assertNull(em.find(Category.class, parentId), "deleted parent must be gone");
        assertNull(em.find(Category.class, childId), "sub-categories must be cascade-deleted");
    }

    @Test
    @Transactional
    void deleteCategoryReturnsFalseForUnknownId() {
        assertFalse(categoryService.deleteCategory(-999_999));
    }

    @Test
    @Transactional
    void moveCategoryReparentsAndUpdatesLineage() {
        Category a = categoryService.createCategory(newCategory("IT-MoveA", "en"), null);
        Category b = categoryService.createCategory(newCategory("IT-MoveB", "en"), null);
        Category child = categoryService.createCategory(newCategory("IT-MoveChild", "en"), a.getId());
        Integer childId = child.getId();
        em.flush();

        assertTrue(categoryService.moveCategory(childId, b.getId()));
        em.flush();
        em.clear();

        Category reloaded = em.find(Category.class, childId);
        assertEquals(b.getId(), reloaded.getParent().getId());
        assertEquals("/" + b.getId() + "/" + childId + "/", reloaded.getLineage());
    }

    @Test
    @Transactional
    void moveCategoryReturnsFalseWhenParentIsUnchanged() {
        Category parent = categoryService.createCategory(newCategory("IT-SameParent", "en"), null);
        Category child = categoryService.createCategory(newCategory("IT-SameChild", "en"), parent.getId());
        em.flush();

        // Moving the child under its current parent is a no-op.
        assertFalse(categoryService.moveCategory(child.getId(), parent.getId()));
    }

    @Test
    @Transactional
    void moveCategoryToRootClearsParentAndLineage() {
        Category parent = categoryService.createCategory(newCategory("IT-ToRootParent", "en"), null);
        Category child = categoryService.createCategory(newCategory("IT-ToRootChild", "en"), parent.getId());
        Integer childId = child.getId();
        em.flush();

        assertTrue(categoryService.moveCategory(childId, null));
        em.flush();
        em.clear();

        Category reloaded = em.find(Category.class, childId);
        assertNull(reloaded.getParent(), "moving to root must clear the parent");
        assertEquals("/" + childId + "/", reloaded.getLineage());
    }

    @Test
    @Transactional
    void changeSortOrderMovesSiblingUp() {
        Category parent = categoryService.createCategory(newCategory("IT-SortParent", "en"), null);
        Category first = categoryService.createCategory(newCategory("IT-Sort1", "en"), parent.getId());
        Category second = categoryService.createCategory(newCategory("IT-Sort2", "en"), parent.getId());
        Integer firstId = first.getId();
        Integer secondId = second.getId();
        em.flush();

        // Both siblings start at sort order 0.0; moving the second up must order it before the first.
        assertTrue(categoryService.changeSortOrder(secondId, true));
        em.flush();
        em.clear();

        double firstOrder = em.find(Category.class, firstId).getSiblingSortOrder();
        double secondOrder = em.find(Category.class, secondId).getSiblingSortOrder();
        assertTrue(secondOrder < firstOrder,
                "the moved-up sibling must have a smaller sort order than its former predecessor");
    }

    // ------------------------------------------------------------------------------------------- //
    // findBy* lookups
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByNameMatchesExactlyAndCaseInsensitively() {
        Category created = categoryService.createCategory(newCategory("IT-FindName", "en"), null);
        em.flush();

        assertEquals(created.getId(), categoryService.findByName("it-findname", "en", null).getId());
        assertNull(categoryService.findByName("IT-Find", "en", null), "findByName is exact, not substring");
    }

    @Test
    @Transactional
    void findByNameScopesToParentWhenParentIdGiven() {
        Category parentA = categoryService.createCategory(newCategory("IT-FNParentA", "en"), null);
        Category parentB = categoryService.createCategory(newCategory("IT-FNParentB", "en"), null);
        Category childA = categoryService.createCategory(newCategory("IT-Shared", "en"), parentA.getId());
        categoryService.createCategory(newCategory("IT-Shared", "en"), parentB.getId());
        em.flush();

        // Two categories share the name "IT-Shared"; the parent id disambiguates.
        assertEquals(childA.getId(),
                categoryService.findByName("IT-Shared", "en", parentA.getId()).getId());
        assertNull(categoryService.findByName("IT-Shared", "en", -999_999),
                "no child of an unknown parent has the name");
    }

    @Test
    @Transactional
    void findByNameReturnsNullForBlankOrNull() {
        assertNull(categoryService.findByName("", "en", null));
        assertNull(categoryService.findByName(null, "en", null));
    }

    @Test
    @Transactional
    void findByMrnMatchesAndReturnsNullForUnknown() {
        Category created = newCategory("IT-Mrn", "en");
        created.setMrn("urn:mrn:dma:category:it-findme");
        created = categoryService.createCategory(created, null);
        em.flush();

        assertEquals(created.getId(), categoryService.findByMrn("urn:mrn:dma:category:it-findme").getId());
        assertNull(categoryService.findByMrn("urn:mrn:dma:category:does-not-exist"));
    }

    @Test
    @Transactional
    void findByMrnMatchesWithoutRegardToCase() {
        Category created = newCategory("IT-Mrn-Case", "en");
        created.setMrn("urn:mrn:dma:category:IT-Case-Me");
        created = categoryService.createCategory(created, null);
        em.flush();

        assertEquals(created.getId(), categoryService.findByMrn("URN:MRN:DMA:CATEGORY:it-case-me").getId(),
                "an MRN typed in other capital letters finds the category");
        assertEquals(created.getId(), categoryService.findByCategoryId("urn:mrn:dma:category:it-case-me").getId(),
                "the look-up by id or MRN (the message search, the publication criteria) does too");
        Category template = newCategory("IT-Mrn-Case-Import", "en");
        template.setMrn("URN:MRN:DMA:CATEGORY:IT-CASE-ME");
        assertEquals(created.getId(), categoryService.importCategory(template, true, false).getId(),
                "an imported category whose MRN differs only in case is matched with the existing one, not created");
    }

    @Test
    @Transactional
    void findByMrnPrefersTheExactMatchAndAnswersNoneForTwinsWithoutOne() {
        Category lower = newCategory("IT-Mrn-Twin-1", "en");
        lower.setMrn("urn:mrn:dma:category:it-twin");
        lower = categoryService.createCategory(lower, null);
        Category upper = newCategory("IT-Mrn-Twin-2", "en");
        upper.setMrn("URN:MRN:DMA:CATEGORY:IT-TWIN");
        upper = categoryService.createCategory(upper, null);
        em.flush();

        Logger logger = (Logger) LoggerFactory.getLogger(CategoryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(lower.getId(), categoryService.findByMrn("urn:mrn:dma:category:it-twin").getId(),
                    "the exact match is found");
            assertEquals(upper.getId(), categoryService.findByMrn("URN:MRN:DMA:CATEGORY:IT-TWIN").getId(),
                    "the exact match is found");
            assertNull(categoryService.findByMrn("Urn:Mrn:Dma:Category:It-Twin"),
                    "two categories whose MRNs differ only in case and neither exactly: none is answered");
        } finally {
            logger.detachAppender(appender);
        }
        List<String> warnings = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'Urn:Mrn:Dma:Category:It-Twin'")
                        && w.contains("'urn:mrn:dma:category:it-twin'")
                        && w.contains("'URN:MRN:DMA:CATEGORY:IT-TWIN'")),
                "a WARN names the MRN looked up and the two found: " + warnings);
    }

    @Test
    @Transactional
    void findByLegacyIdMatchesAndReturnsNullForUnknown() {
        Category created = newCategory("IT-Legacy", "en");
        created.setLegacyId("IT-LEG-123");
        created = categoryService.createCategory(created, null);
        em.flush();

        assertEquals(created.getId(), categoryService.findByLegacyId("IT-LEG-123").getId());
        assertNull(categoryService.findByLegacyId("IT-LEG-UNKNOWN"));
    }

    @Test
    @Transactional
    void findByCategoryIdResolvesNumericIdAndMrn() {
        Category created = newCategory("IT-CatId", "en");
        created.setMrn("urn:mrn:dma:category:it-catid");
        created = categoryService.createCategory(created, null);
        em.flush();

        assertEquals(created.getId(), categoryService.findByCategoryId(created.getId().toString()).getId());
        assertEquals(created.getId(), categoryService.findByCategoryId("urn:mrn:dma:category:it-catid").getId());
    }

    @Test
    @Transactional
    void getFiringExercisesCategoryReturnsByExactEnglishName() {
        // The "Firing Exercises" category is not part of the base data seed (it would normally be
        // imported separately), so the test creates it in its own (rolled-back) transaction and then
        // verifies getFiringExercisesCategory() resolves it by the exact English name "Firing Exercises".
        Category firingTemplate = newCategory("Firing Exercises", "en");
        firingTemplate.checkCreateDesc("da").setName("Skydeøvelser");
        categoryService.createCategory(firingTemplate, null);
        em.flush();

        Category firing = categoryService.getFiringExercisesCategory();
        assertNotNull(firing, "getFiringExercisesCategory must resolve the category by its English name");
        assertEquals("Firing Exercises", firing.getDesc("en").getName());
        assertEquals("Skydeøvelser", firing.getDesc("da").getName());
    }

    @Test
    @Transactional
    void getCategoryDetailsByIdsPreservesRequestedOrder() {
        Category a = categoryService.createCategory(newCategory("IT-OrderA", "en"), null);
        Category b = categoryService.createCategory(newCategory("IT-OrderB", "en"), null);
        em.flush();

        // The requested-id order (b, a) must be preserved in the returned list.
        List<Category> result = categoryService.getCategoryDetails(List.of(b.getId(), a.getId()));
        assertEquals(List.of(b.getId(), a.getId()),
                result.stream().map(Category::getId).collect(java.util.stream.Collectors.toList()));
    }

    // ------------------------------------------------------------------------------------------- //
    // importCategory
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void importCategoryCreatesWhenMissingAndMatchesByMrnOnReimport() {
        Category template = newCategory("IT-Import", "en");
        template.setMrn("urn:mrn:dma:category:it-import");

        Category imported = categoryService.importCategory(template, true, true);
        assertNotNull(imported.getId());
        em.flush();

        // Re-importing the same MRN must update the existing row, not create a new one.
        Category template2 = newCategory("IT-Import-Renamed", "en");
        template2.setMrn("urn:mrn:dma:category:it-import");
        Category imported2 = categoryService.importCategory(template2, true, true);

        assertEquals(imported.getId(), imported2.getId(), "re-import must match by MRN");
        em.flush();
        em.clear();
        assertEquals("IT-Import-Renamed",
                em.find(Category.class, imported.getId()).getDesc("en").getName());
    }

    @Test
    @Transactional
    void importCategoryCreatesParentsRecursively() {
        Category parentTemplate = newCategory("IT-ImpParent", "en");
        parentTemplate.setMrn("urn:mrn:dma:category:it-imp-parent");
        Category childTemplate = newCategory("IT-ImpChild", "en");
        childTemplate.setMrn("urn:mrn:dma:category:it-imp-child");
        childTemplate.setParent(parentTemplate);

        Category imported = categoryService.importCategory(childTemplate, true, true);

        assertNotNull(imported.getId());
        assertNotNull(imported.getParent());
        assertEquals("IT-ImpParent", imported.getParent().getDesc("en").getName());

        em.flush();
        em.clear();
        Category reloaded = em.find(Category.class, imported.getId());
        assertEquals("IT-ImpParent", reloaded.getParent().getDesc("en").getName(),
                "the recursively created parent must be persisted and linked");
    }

    @Test
    @Transactional
    void importOfAWholeChainRootTemplate_doesNotMatchASameNamedCategoryDeeperDown() {
        // A category file's root entry was looked up by name at any depth, so it was found as the child below and
        // that child was updated. With its whole chain it matches a root only, and here there is none, so a root is
        // created.
        Category holder = categoryService.createCategory(newCategory("IT-Cat2-Holder", "en"), null);
        Category child = categoryService.createCategory(newCategory("IT-Cat2-Twin", "en"), holder.getId());
        em.flush();

        Category imported = importWholeChain(newCategory("IT-Cat2-Twin", "en"), true, true);

        assertNotEquals(child.getId(), imported.getId(), "the root template must not match the child");
        assertNull(imported.getParent(), "the imported category is a root");
        em.flush();
        em.clear();
        assertEquals(holder.getId(), em.find(Category.class, child.getId()).getParent().getId(),
                "the child stays where it was");
    }

    @Test
    @Transactional
    void importOfAParentlessTemplate_stillFindsANestedCategoryByName() {
        // A message's or a domain's category is read without its parent: its name alone must find the nested
        // category it names, and no root of that name is made
        Category holder = categoryService.createCategory(newCategory("IT-Cat2-Holder", "en"), null);
        Category child = categoryService.createCategory(newCategory("IT-Cat2-Nested", "en"), holder.getId());
        em.flush();

        assertEquals(child.getId(),
                categoryService.importCategory(newCategory("IT-Cat2-Nested", "en"), false, false).getId(),
                "without create, the nested category is found");
        assertEquals(child.getId(),
                categoryService.importCategory(newCategory("IT-Cat2-Nested", "en"), true, false).getId(),
                "with create, the nested category is found and no root is made");
        assertNull(categoryService.findRootByName("IT-Cat2-Nested", "en"), "no root of that name is made");
    }

    @Test
    @Transactional
    void importMatchesTheCategoryOfItsNameUnderTheSameParent_atEachDepth() {
        // Two categories of one name, a root and a child; each entry of a category file finds its own
        Category root = categoryService.createCategory(newCategory("IT-Cat2-Same", "en"), null);
        Category holder = categoryService.createCategory(newCategory("IT-Cat2-Parent", "en"), null);
        Category child = categoryService.createCategory(newCategory("IT-Cat2-Same", "en"), holder.getId());
        em.flush();

        assertEquals(root.getId(),
                importWholeChain(newCategory("IT-Cat2-Same", "en"), false, false).getId(),
                "the root template finds the root");

        Category childTemplate = newCategory("IT-Cat2-Same", "en");
        childTemplate.setParent(newCategory("IT-Cat2-Parent", "en"));
        assertEquals(child.getId(), importWholeChain(childTemplate, false, false).getId(),
                "the child template finds the child under its parent");
    }

    /** Imports a template as a category file's entry: one that carries its whole chain of parents */
    private Category importWholeChain(Category template, boolean create, boolean update) {
        return categoryService.importCategory(template, create, update, BootstrapRun.Scope.NONE, a -> { }, true);
    }

    @Test
    @Transactional
    void importCategoryReturnsNullWhenParentMissingAndCreateDisabled() {
        Category parentTemplate = newCategory("IT-NoCreateParent", "en");
        parentTemplate.setMrn("urn:mrn:dma:category:it-nocreate-parent");
        Category childTemplate = newCategory("IT-NoCreateChild", "en");
        childTemplate.setMrn("urn:mrn:dma:category:it-nocreate-child");
        childTemplate.setParent(parentTemplate);

        assertNull(categoryService.importCategory(childTemplate, false, false));
    }

    @Test
    @Transactional
    void importCategoryReturnsNullForNullTemplate() {
        assertNull(categoryService.importCategory(null, true, true));
    }

    @Test
    @Transactional
    void importCategoryFindsExistingByNameWithoutCreatingDuplicate() {
        // Seed an existing category by name (no MRN), then import a same-named template.
        Category existing = categoryService.createCategory(newCategory("IT-ImpByName", "en"), null);
        Integer existingId = existing.getId();
        em.flush();

        Category template = newCategory("IT-ImpByName", "en");
        Category imported = categoryService.importCategory(template, true, false);

        assertEquals(existingId, imported.getId(),
                "import must match the existing category by name rather than create a duplicate");
    }
}
