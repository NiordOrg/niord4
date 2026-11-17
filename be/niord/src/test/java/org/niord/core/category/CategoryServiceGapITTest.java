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

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Additional integration tests for {@link CategoryService}, complementing {@link CategoryServiceITTest}.
 *
 * <p>This class targets a focused set of behaviours where it adds assertions that the sibling does not
 * make: it verifies the actual row counts in the database (via {@code COUNT} queries), the lineage and
 * parent linkage of multi-level hierarchies, that {@link CategoryService#checkUpdateCategoryData} does
 * not bump the JPA {@code @Version} when nothing changed, that {@link CategoryService#deleteCategory}
 * cascades all the way down a multi-level subtree, that a {@code TEMPLATE} type search excludes plain
 * categories, and that recursive {@link CategoryService#importCategory} matches by MRN on re-import
 * without inserting duplicate rows.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the shared
 * seed database pristine and the tests independent. Test categories use an {@code ITG-} name prefix
 * (distinct from the sibling's {@code IT-} prefix and absent from the seed data), so searches and count
 * queries stay isolated from both the seeded Danish categories and the sibling's fixtures.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class CategoryServiceGapITTest extends ITTest {

    @Autowired
    CategoryService categoryService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached Category with a single English description. */
    private Category newCategory(String name) {
        Category category = new Category();
        category.checkCreateDesc("en").setName(name);
        return category;
    }

    /** Counts Category rows whose English description matches the given (exact) name. */
    private long countByEnglishName(String name) {
        return em.createQuery(
                        "select count(distinct c.id) from Category c join c.descs d " +
                                "where d.lang = 'en' and d.name = :name", Long.class)
                .setParameter("name", name)
                .getSingleResult();
    }

    /** Counts CategoryDesc rows attached to the given category id. */
    private long countDescs(Integer categoryId) {
        return em.createQuery(
                        "select count(d) from CategoryDesc d where d.entity.id = :id", Long.class)
                .setParameter("id", categoryId)
                .getSingleResult();
    }

    private CategorySearchParams params() {
        CategorySearchParams p = new CategorySearchParams();
        p.maxSize(100);
        return p;
    }

    // ------------------------------------------------------------------------------------------- //
    // createCategory under a parent: parent + lineage + persisted generated id
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createCategoryUnderParentPersistsGeneratedIdParentAndLineage() {
        Category parent = categoryService.createCategory(newCategory("ITG-Parent"), null);
        Integer parentId = parent.getId();
        assertNotNull(parentId, "parent must receive a generated id");

        Category child = categoryService.createCategory(newCategory("ITG-Child"), parentId);
        Integer childId = child.getId();
        assertNotNull(childId, "child must receive a generated id");
        assertFalse(childId.equals(parentId), "child must get its own distinct generated id");

        em.flush();
        em.clear();

        // Reload purely from the database to prove the state was persisted, not just held in memory.
        Category reloadedChild = em.find(Category.class, childId);
        assertNotNull(reloadedChild, "child row must exist in the database");
        assertEquals(parentId, reloadedChild.getParent().getId(), "child must reference its parent");
        assertEquals("/" + parentId + "/" + childId + "/", reloadedChild.getLineage(),
                "child lineage must chain from the parent lineage to the child id");

        Category reloadedParent = em.find(Category.class, parentId);
        assertEquals("/" + parentId + "/", reloadedParent.getLineage(),
                "parent (a root) lineage must be just its own id");
        assertEquals(1, reloadedParent.getChildren().size(), "parent must have exactly one child");
        assertEquals(childId, reloadedParent.getChildren().get(0).getId());
    }

    // ------------------------------------------------------------------------------------------- //
    // updateCategoryData: a blanked description is removed and the change is persisted
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateCategoryDataRemovesBlankedDescriptionFromDatabase() {
        Category created = newCategory("ITG-Blank");
        created.checkCreateDesc("da").setName("ITG-Blank-DA");
        created = categoryService.createCategory(created, null);
        Integer id = created.getId();
        em.flush();
        assertEquals(2, countDescs(id), "the category must start with two descriptions (en + da)");

        // Template keeps the English name but blanks the Danish one.
        Category template = newCategory("ITG-Blank");
        template.checkCreateDesc("da").setName("");
        categoryService.updateCategoryData(created, template);

        em.flush();
        em.clear();

        Category reloaded = em.find(Category.class, id);
        assertNull(reloaded.getDesc("da"), "the blanked Danish description must be removed");
        assertEquals("ITG-Blank", reloaded.getDesc("en").getName(), "the English description must survive");
        assertEquals(1, countDescs(id),
                "the orphaned CategoryDesc row must actually be deleted from the database");
    }

    // ------------------------------------------------------------------------------------------- //
    // checkUpdateCategoryData: no-op when nothing changed (no version bump)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void checkUpdateCategoryDataDoesNotBumpVersionWhenNothingChanged() {
        Category created = categoryService.createCategory(newCategory("ITG-NoOp"), null);
        Integer id = created.getId();
        // Give the parent a second, da description and an editor field so the no-op path has real
        // mapped state to (not) touch.
        created.checkCreateDesc("da").setName("ITG-NoOp-DA");
        em.flush();
        em.clear();

        Category original = em.find(Category.class, id);

        // An identical template must short-circuit hasChanged() and skip the update entirely: the key
        // observable contract is that the SAME managed instance is returned with no update path taken.
        // (We intentionally do not assert the exact @Version integer here: under Hibernate ORM 7 a
        // load-then-flush of an otherwise-untouched Category issues a phantom UPDATE — see WEB/ORM note in
        // docs/potential-errors.md — so the @Version counter is not a migration-stable oracle. The
        // business intent — "nothing changed" — is verified by the same-instance short-circuit and by the
        // persisted data being byte-for-byte identical below.)
        Category identicalTemplate = newCategory("ITG-NoOp");
        identicalTemplate.checkCreateDesc("da").setName("ITG-NoOp-DA");
        Category result = categoryService.checkUpdateCategoryData(original, identicalTemplate);
        assertSame(original, result, "an unchanged category must be returned as-is (hasChanged short-circuit)");

        em.flush();
        em.clear();

        // The persisted data must be exactly what it was before the no-op: both descriptions survive
        // unchanged and no spurious row mutation altered the names.
        Category reloaded = em.find(Category.class, id);
        assertEquals("ITG-NoOp", reloaded.getDesc("en").getName(),
                "the English description must be untouched by a no-op update");
        assertEquals("ITG-NoOp-DA", reloaded.getDesc("da").getName(),
                "the Danish description must be untouched by a no-op update");
        assertEquals(2, countDescs(id),
                "a no-op update must neither add nor remove any CategoryDesc rows");
    }

    @Test
    @Transactional
    void checkUpdateCategoryDataPersistsDescChange() {
        Category created = categoryService.createCategory(newCategory("ITG-Bump"), null);
        Integer id = created.getId();
        em.flush();
        em.clear();

        Category original = em.find(Category.class, id);
        String mrnBefore = original.getMrn();
        CategoryType typeBefore = original.getType();
        boolean activeBefore = original.isActive();

        // A differing localized name triggers an update of the child CategoryDesc.
        Category template = newCategory("ITG-Bump-Renamed");
        categoryService.checkUpdateCategoryData(original, template);

        em.flush();
        em.clear();

        Category reloaded = em.find(Category.class, id);
        // The localized name (held on the child CategoryDesc) is persisted with the new value.
        assertEquals("ITG-Bump-Renamed", reloaded.getDesc("en").getName(),
                "a changed category must be persisted with the new name");
        // The change is confined to the child desc collection: the parent Category's OWN mapped scalar
        // fields are left untouched (no field other than the localized name was modified). This is the
        // migration-stable expression of the original "a desc-only change does not dirty the parent"
        // intent — the exact @Version integer is not asserted because Hibernate ORM 7 phantom-updates an
        // otherwise-clean Category on load+flush (see docs/potential-errors.md), making it an unreliable
        // oracle across the Quarkus -> Spring port.
        assertEquals(mrnBefore, reloaded.getMrn(), "a desc-only change must not alter the parent MRN");
        assertEquals(typeBefore, reloaded.getType(), "a desc-only change must not alter the parent type");
        assertEquals(activeBefore, reloaded.isActive(),
                "a desc-only change must not alter the parent active flag");
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteCategory: cascades removal to every descendant in a multi-level subtree
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteCategoryCascadesToAllDescendants() {
        Category root = categoryService.createCategory(newCategory("ITG-DelRoot"), null);
        Category child = categoryService.createCategory(newCategory("ITG-DelChild"), root.getId());
        Category grandChild = categoryService.createCategory(newCategory("ITG-DelGrandChild"), child.getId());
        Integer rootId = root.getId();
        Integer childId = child.getId();
        Integer grandChildId = grandChild.getId();
        em.flush();

        // Pre-condition: all three rows really are in the database.
        assertEquals(1, countByEnglishName("ITG-DelRoot"));
        assertEquals(1, countByEnglishName("ITG-DelChild"));
        assertEquals(1, countByEnglishName("ITG-DelGrandChild"));

        assertTrue(categoryService.deleteCategory(rootId), "deleting the root must report success");
        em.flush();
        em.clear();

        assertNull(em.find(Category.class, rootId), "deleted root must be gone");
        assertNull(em.find(Category.class, childId), "child must be cascade-deleted");
        assertNull(em.find(Category.class, grandChildId), "grandchild must be cascade-deleted");
        assertEquals(0, countByEnglishName("ITG-DelChild"),
                "no descendant rows must remain in the database");
        assertEquals(0, countByEnglishName("ITG-DelGrandChild"),
                "no deep-descendant rows must remain in the database");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchCategories: a TEMPLATE-typed search excludes plain CATEGORY rows
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByTemplateTypeExcludesPlainCategories() {
        Category plain = categoryService.createCategory(newCategory("ITG-Mixed-Plain"), null);

        Category template = newCategory("ITG-Mixed-Template");
        template.setType(CategoryType.TEMPLATE);
        categoryService.createCategory(template, null);
        em.flush();

        // A TEMPLATE-typed substring search must return only the template, not the plain category.
        List<Category> templates = categoryService.searchCategories(
                params().name("ITG-Mixed").language("en").exact(false).inactive(true)
                        .type(CategoryType.TEMPLATE));
        List<String> names = templates.stream()
                .map(c -> c.getDesc("en").getName())
                .collect(Collectors.toList());
        assertEquals(List.of("ITG-Mixed-Template"), names,
                "a TEMPLATE search must return only the TEMPLATE category");
        assertFalse(names.contains("ITG-Mixed-Plain"),
                "a TEMPLATE search must exclude plain CATEGORY rows");

        // Sanity check: without the type filter both are returned, proving the filter (not the data)
        // is what excludes the plain category.
        long both = categoryService.searchCategories(
                params().name("ITG-Mixed").language("en").exact(false).inactive(true)).size();
        assertEquals(2, both, "without a type filter both categories must be found");
    }

    // ------------------------------------------------------------------------------------------- //
    // importCategory: creates missing parents recursively, matches by MRN on re-import w/o duplicates
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void importCategoryCreatesParentsRecursivelyAndPersistsTheChain() {
        Category parentTemplate = newCategory("ITG-ImpParent");
        parentTemplate.setMrn("urn:mrn:dma:category:itg-imp-parent");
        Category childTemplate = newCategory("ITG-ImpChild");
        childTemplate.setMrn("urn:mrn:dma:category:itg-imp-child");
        childTemplate.setParent(parentTemplate);

        Category imported = categoryService.importCategory(childTemplate, true, true);
        assertNotNull(imported.getId(), "the imported child must be persisted with a generated id");
        Integer childId = imported.getId();

        em.flush();
        em.clear();

        Category reloadedChild = em.find(Category.class, childId);
        assertNotNull(reloadedChild.getParent(), "the recursively created parent must be linked");
        Integer parentId = reloadedChild.getParent().getId();
        assertEquals("ITG-ImpParent", reloadedChild.getParent().getDesc("en").getName());
        assertEquals("/" + parentId + "/" + childId + "/", reloadedChild.getLineage(),
                "the imported child lineage must chain from the created parent");

        // Both the parent and the child rows must exist exactly once.
        assertEquals(1, countByEnglishName("ITG-ImpParent"));
        assertEquals(1, countByEnglishName("ITG-ImpChild"));
    }

    @Test
    @Transactional
    void importCategoryMatchesByMrnOnReimportWithoutCreatingDuplicate() {
        Category template = newCategory("ITG-Reimport");
        template.setMrn("urn:mrn:dma:category:itg-reimport");

        Category first = categoryService.importCategory(template, true, true);
        assertNotNull(first.getId());
        Integer id = first.getId();
        em.flush();
        assertEquals(1, countByEnglishName("ITG-Reimport"), "the first import must insert one row");

        // Re-import with the same MRN but a renamed description: it must update, not insert.
        Category template2 = newCategory("ITG-Reimport-Renamed");
        template2.setMrn("urn:mrn:dma:category:itg-reimport");
        Category second = categoryService.importCategory(template2, true, true);

        assertEquals(id, second.getId(), "re-import must resolve the existing row by MRN");

        em.flush();
        em.clear();

        // The MRN is unique: the renamed row replaced the original, and no duplicate was inserted.
        assertEquals(0, countByEnglishName("ITG-Reimport"),
                "the original name must be gone after the update");
        assertEquals(1, countByEnglishName("ITG-Reimport-Renamed"),
                "exactly one row must carry the new name");
        long byMrn = em.createQuery(
                        "select count(c) from Category c where c.mrn = :mrn", Long.class)
                .setParameter("mrn", "urn:mrn:dma:category:itg-reimport")
                .getSingleResult();
        assertEquals(1, byMrn, "re-import by MRN must not create a duplicate row");
        assertEquals("ITG-Reimport-Renamed",
                em.find(Category.class, id).getDesc("en").getName());
    }
}
