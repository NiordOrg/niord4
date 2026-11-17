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

import org.junit.jupiter.api.Test;
import org.niord.core.category.vo.SystemCategoryVo;
import org.niord.core.category.vo.TemplateParamVo;
import org.niord.core.domain.vo.DomainVo;
import org.niord.model.DataFilter;
import org.niord.model.message.CategoryVo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for the {@link Category} entity.
 *
 * <p>These exercise the pure in-memory behaviour of {@code Category}: the no-arg
 * defaults, the {@code createDesc} localized-description wiring, the
 * {@code updateCategory}/constructor entity-from-VO mapping (including the
 * {@link SystemCategoryVo} template branch and {@link DataFilter}-gated parent/child
 * inclusion), the {@code toVo} entity-to-VO mapping, and the {@code hasChanged}
 * change-detection logic.
 *
 * <p>No database, CDI, or Quarkus is involved; everything is plain object
 * construction with {@link DataFilter} as the only collaborator.
 */
public class CategoryTest {

    /* ===================================================================== */
    /* DEFAULTS / createDesc                                                 */
    /* ===================================================================== */

    /** 1. A freshly constructed Category has the documented defaults. */
    @Test
    public void newCategory_hasDefaults() {
        Category category = new Category();

        assertEquals(CategoryType.CATEGORY, category.getType());
        assertTrue(category.isActive(), "A new category is active by default");
        assertTrue(category.getDescs().isEmpty(), "A new category has no descriptions");
        assertTrue(category.getEditorFields().isEmpty(), "A new category has no editor fields");
    }

    /** 2. createDesc(lang) adds a desc wired back to its owning category. */
    @Test
    public void createDesc_addsNamedDescWiredToCategory() {
        Category category = new Category();
        category.createDesc("en").setName("X");

        assertEquals(1, category.getDescs().size());
        assertEquals("X", category.getDesc("en").getName());
        // The desc must reference the owning category.
        assertSame(category, category.getDesc("en").getEntity());
    }

    /* ===================================================================== */
    /* updateCategory / constructor from VO                                  */
    /* ===================================================================== */

    /**
     * 3. A plain CategoryVo (not a SystemCategoryVo) maps the base fields only;
     * the system-field branch is skipped so the type stays the CATEGORY default.
     */
    @Test
    public void constructorFromPlainVo_copiesBaseFieldsOnly() {
        CategoryVo vo = new CategoryVo();
        vo.setId(5);
        vo.setMrn("urn:x");
        vo.setActive(false);
        vo.createDesc("en").setName("Name");

        Category category = new Category(vo);

        assertEquals(Integer.valueOf(5), category.getId());
        assertEquals("urn:x", category.getMrn());
        assertFalse(category.isActive());
        assertEquals("Name", category.getDesc("en").getName());
        // The SystemCategoryVo branch is skipped for a plain CategoryVo, so type
        // remains the constructor default.
        assertEquals(CategoryType.CATEGORY, category.getType());
    }

    /**
     * 4. A SystemCategoryVo maps the full set of template/system fields. Note that
     * non-script paths are filtered out via ScriptResource.path2type.
     */
    @Test
    public void constructorFromSystemVo_copiesSystemFields() {
        SystemCategoryVo vo = new SystemCategoryVo();
        vo.setType(CategoryType.TEMPLATE);
        vo.setSiblingSortOrder(3.0);
        vo.setAtonFilter("af");
        vo.setMessageId("M1");
        vo.setEditorFields(List.of("a", "b"));
        vo.getStdTemplateFields().add("x");
        vo.setScriptResourcePaths(new ArrayList<>(List.of("t.ftl", "bad.txt")));

        TemplateParamVo paramVo = new TemplateParamVo();
        paramVo.setParamId("p");
        paramVo.setType("text");
        vo.setTemplateParams(new ArrayList<>(List.of(paramVo)));

        vo.getDomains().add(new DomainVo("dk"));
        vo.createDesc("en").setName("Name");

        Category category = new Category(vo, DataFilter.get());

        assertEquals(CategoryType.TEMPLATE, category.getType());
        assertEquals(3.0, category.getSiblingSortOrder(), 0.0);
        assertEquals("af", category.getAtonFilter());
        assertEquals("M1", category.getMessageId());
        assertEquals(List.of("a", "b"), category.getEditorFields());
        assertTrue(category.getStdTemplateFields().contains("x"),
                "Standard template fields must include 'x'");
        // "bad.txt" is filtered out because path2type returns null for non .ftl/.js.
        assertEquals(List.of("t.ftl"), category.getScriptResourcePaths());
        assertEquals(1, category.getTemplateParams().size());
        assertEquals("p", category.getTemplateParams().get(0).getParamId());
        assertEquals(1, category.getDomains().size());
        assertEquals("dk", category.getDomains().get(0).getDomainId());
    }

    /** 5. A null siblingSortOrder on the VO defaults to 0.0 on the entity. */
    @Test
    public void constructorFromSystemVo_nullSiblingSortOrderDefaultsToZero() {
        SystemCategoryVo vo = new SystemCategoryVo();
        vo.setSiblingSortOrder(null);

        Category category = new Category(vo, DataFilter.get());

        assertEquals(0.0, category.getSiblingSortOrder(), 0.0);
    }

    /** 6. Children are only mapped when the filter requests the children field. */
    @Test
    public void constructorFromSystemVo_includesChildrenOnlyWhenFiltered() {
        SystemCategoryVo child = new SystemCategoryVo();
        child.createDesc("en").setName("Child");

        SystemCategoryVo parent = new SystemCategoryVo();
        parent.createDesc("en").setName("Parent");
        parent.checkCreateChildren().add(child);

        // With "*" the children are included and wired to the parent.
        Category withChildren = new Category(parent, DataFilter.get().fields("*"));
        assertEquals(1, withChildren.getChildren().size());
        assertSame(withChildren, withChildren.getChildren().get(0).getParent());

        // With a plain filter, children are not copied.
        Category withoutChildren = new Category(parent, DataFilter.get());
        assertTrue(withoutChildren.getChildren().isEmpty());
    }

    /** 7. The parent is only mapped when the filter requests the parent field. */
    @Test
    public void constructorFromVo_includesParentOnlyWhenFiltered() {
        CategoryVo parentVo = new CategoryVo();
        parentVo.setId(9);

        CategoryVo vo = new CategoryVo();
        vo.setParent(parentVo);

        // With "*" the parent is copied.
        Category withParent = new Category(vo, DataFilter.get().fields("*"));
        assertNotNull(withParent.getParent());
        assertEquals(Integer.valueOf(9), withParent.getParent().getId());

        // With a plain filter, the parent is not copied.
        Category withoutParent = new Category(vo, DataFilter.get());
        assertNull(withoutParent.getParent());
    }

    /* ===================================================================== */
    /* toVo                                                                  */
    /* ===================================================================== */

    /** 8. toVo into a SystemCategoryVo carries the base fields plus the system type. */
    @Test
    public void toVo_systemVo_carriesBaseFieldsAndType() {
        Category category = new Category();
        category.setId(7);
        category.setMrn("m");
        category.setActive(true);
        category.createDesc("en").setName("N");

        SystemCategoryVo vo = category.toVo(SystemCategoryVo.class, DataFilter.get());

        assertEquals(Integer.valueOf(7), vo.getId());
        assertEquals("m", vo.getMrn());
        assertTrue(vo.isActive());
        assertEquals("N", vo.getDesc("en").getName());
        assertEquals(CategoryType.CATEGORY, vo.getType());
    }

    /** 9. toVo into a plain CategoryVo yields a plain VO that still carries id/mrn/desc. */
    @Test
    public void toVo_plainVo_isNotSystemVoButCarriesBaseFields() {
        Category category = new Category();
        category.setId(7);
        category.setMrn("m");
        category.createDesc("en").setName("N");

        CategoryVo vo = category.toVo(CategoryVo.class, DataFilter.get());

        assertFalse(vo instanceof SystemCategoryVo,
                "A plain CategoryVo must not be a SystemCategoryVo");
        assertEquals(Integer.valueOf(7), vo.getId());
        assertEquals("m", vo.getMrn());
        assertEquals("N", vo.getDesc("en").getName());
    }

    /** 10. toVo includes children when the children field is requested. */
    @Test
    public void toVo_includesChildrenWhenFiltered() {
        Category child = new Category();
        child.createDesc("en").setName("Child");

        Category parent = new Category();
        parent.createDesc("en").setName("Parent");
        parent.addChild(child);

        // Request only the "children" field, not "*". addChild() wires a parent<->child
        // back-reference, and toVo() has no cycle guard: requesting BOTH parent and children
        // (as "*" does) recurses parent->child->parent forever (see potential-errors.md, the
        // cyclic-toVo note). Children-only exercises the includeChildren() branch safely.
        SystemCategoryVo vo = parent.toVo(SystemCategoryVo.class, DataFilter.get().fields("children"));

        assertNotNull(vo.getChildren());
        assertEquals(1, vo.getChildren().size());
        assertEquals("Child", vo.getChildren().get(0).getDesc("en").getName());
    }

    /** 11. toVo includes the parent (with its id) when the parent field is requested. */
    @Test
    public void toVo_includesParentWhenFiltered() {
        Category parent = new Category();
        parent.setId(4);

        Category child = new Category();
        child.setParent(parent);

        SystemCategoryVo vo = child.toVo(SystemCategoryVo.class, DataFilter.get().fields("*"));

        assertNotNull(vo.getParent());
        assertEquals(Integer.valueOf(4), vo.getParent().getId());
    }

    /**
     * 12. When only the "Category.parentId" field is requested, toVo() takes the
     * includeParentId() branch, which attaches a lightweight parent VO carrying only the
     * real parent's id (no descs/children) so callers can resolve the hierarchy by id.
     */
    @Test
    public void toVoParentIdBranchCopiesRealParentId() {
        Category parent = new Category();
        parent.setId(42);

        Category child = new Category();
        child.setParent(parent);

        CategoryVo vo = child.toVo(CategoryVo.class, DataFilter.get().fields("Category.parentId"));

        assertNotNull(vo.getParent(), "The parentId branch attaches a parent VO");
        assertEquals(Integer.valueOf(42), vo.getParent().getId());
    }

    /* ===================================================================== */
    /* hasChanged                                                            */
    /* ===================================================================== */

    /** 13a. hasChanged is false for two categories built identically. */
    @Test
    public void hasChanged_falseForIdenticalCategories() {
        assertFalse(baseTemplate().hasChanged(baseTemplate()));
    }

    /** 13b. A differing type is detected as a change. */
    @Test
    public void hasChanged_detectsTypeChange() {
        Category other = baseTemplate();
        other.setType(CategoryType.TEMPLATE);
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13c. A differing siblingSortOrder is detected as a change. */
    @Test
    public void hasChanged_detectsSiblingSortOrderChange() {
        Category other = baseTemplate();
        other.setSiblingSortOrder(99.0);
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13d. A differing mrn is detected as a change. */
    @Test
    public void hasChanged_detectsMrnChange() {
        Category other = baseTemplate();
        other.setMrn("urn:other");
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13e. A differing active flag is detected as a change. */
    @Test
    public void hasChanged_detectsActiveChange() {
        Category other = baseTemplate();
        other.setActive(false);
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13f. Differing editor fields are detected as a change. */
    @Test
    public void hasChanged_detectsEditorFieldsChange() {
        Category other = baseTemplate();
        other.setEditorFields(new ArrayList<>(List.of("a", "c")));
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13g. A differing atonFilter is detected as a change. */
    @Test
    public void hasChanged_detectsAtonFilterChange() {
        Category other = baseTemplate();
        other.setAtonFilter("other");
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13h. A differing messageId is detected as a change. */
    @Test
    public void hasChanged_detectsMessageIdChange() {
        Category other = baseTemplate();
        other.setMessageId("M2");
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13i. Differing script resource paths are detected as a change. */
    @Test
    public void hasChanged_detectsScriptResourcePathsChange() {
        Category other = baseTemplate();
        other.setScriptResourcePaths(new ArrayList<>(List.of("other.ftl")));
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13j. A differing desc name is detected as a change. */
    @Test
    public void hasChanged_detectsDescNameChange() {
        Category other = baseTemplate();
        other.getDesc("en").setName("Changed");
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13k. A missing desc language is detected as a change. */
    @Test
    public void hasChanged_detectsMissingDescLang() {
        Category base = baseTemplate();
        base.createDesc("da").setName("Navn");
        // 'other' has no "da" desc, so the base's "da" desc is missing on it.
        assertTrue(base.hasChanged(baseTemplate()));
    }

    /** 13l. A domain with a different domainId is detected as a change. */
    @Test
    public void hasChanged_detectsDomainIdChange() {
        Category other = baseTemplate();
        other.setDomains(new ArrayList<>(List.of(domain("se"))));
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13m. A different number of domains is detected as a change. */
    @Test
    public void hasChanged_detectsDomainSizeChange() {
        Category other = baseTemplate();
        other.setDomains(new ArrayList<>(List.of(domain("dk"), domain("se"))));
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13n. A template param with a different paramId is detected as a change. */
    @Test
    public void hasChanged_detectsTemplateParamIdChange() {
        Category other = baseTemplate();
        other.setTemplateParams(new ArrayList<>(List.of(templateParam("other"))));
        assertTrue(baseTemplate().hasChanged(other));
    }

    /** 13o. A different number of template params is detected as a change. */
    @Test
    public void hasChanged_detectsTemplateParamSizeChange() {
        Category other = baseTemplate();
        other.setTemplateParams(new ArrayList<>(List.of(templateParam("p"), templateParam("q"))));
        assertTrue(baseTemplate().hasChanged(other));
    }

    /* ===================================================================== */
    /* Helpers                                                               */
    /* ===================================================================== */

    /** Builds a fully-populated template category used as the hasChanged baseline. */
    private static Category baseTemplate() {
        Category category = new Category();
        category.setType(CategoryType.CATEGORY);
        category.setSiblingSortOrder(1.0);
        category.setMrn("urn:base");
        category.setActive(true);
        category.setEditorFields(new ArrayList<>(List.of("a", "b")));
        category.setAtonFilter("af");
        category.setMessageId("M1");
        category.setScriptResourcePaths(new ArrayList<>(List.of("t.ftl")));
        category.setDomains(new ArrayList<>(List.of(domain("dk"))));
        category.setTemplateParams(new ArrayList<>(List.of(templateParam("p"))));
        category.createDesc("en").setName("Name");
        return category;
    }

    private static org.niord.core.domain.Domain domain(String domainId) {
        return new org.niord.core.domain.Domain(new DomainVo(domainId));
    }

    private static TemplateParam templateParam(String paramId) {
        TemplateParam param = new TemplateParam();
        param.setParamId(paramId);
        param.setType("text");
        param.setMandatory(true);
        param.setPositionList(false);
        return param;
    }
}
