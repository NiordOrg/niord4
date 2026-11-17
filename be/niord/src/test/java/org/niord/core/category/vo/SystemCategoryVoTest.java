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

package org.niord.core.category.vo;

import org.junit.jupiter.api.Test;
import org.niord.core.category.CategoryType;
import org.niord.core.domain.vo.DomainVo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for {@link SystemCategoryVo}.
 *
 * <p>{@code SystemCategoryVo} is a plain value object: no database, no CDI, no Quarkus.
 * These tests verify its default field initialization, the lazy {@link
 * SystemCategoryVo#checkCreateChildren()} accessor, the {@link Comparable} ordering used
 * to sort siblings, the recursive {@link SystemCategoryVo#sortChildren()} routine and
 * the getter/setter round-trips.
 *
 * <p>The class lives in the same package as the class under test so that the
 * package-private fields can be observed indirectly through the public API.
 */
public class SystemCategoryVoTest {

    /** Builds a category whose {@code siblingSortOrder} is the supplied value. */
    private static SystemCategoryVo cat(double siblingSortOrder) {
        SystemCategoryVo c = new SystemCategoryVo();
        c.setSiblingSortOrder(siblingSortOrder);
        return c;
    }

    /* ===================================================================== */
    /* DEFAULTS                                                              */
    /* ===================================================================== */

    /** 1. A fresh VO defaults type to CATEGORY, the four collections to empty (non-null) lists, children null. */
    @Test
    public void defaults_typeCategory_emptyCollections_nullChildren() {
        SystemCategoryVo vo = new SystemCategoryVo();

        // The category type defaults to CATEGORY (not TEMPLATE).
        assertEquals(CategoryType.CATEGORY, vo.getType());

        // domains/stdTemplateFields/templateParams/scriptResourcePaths are eagerly initialized
        // to empty mutable lists, so callers can add without a null check.
        assertNotNull(vo.getDomains(), "domains must be initialized");
        assertTrue(vo.getDomains().isEmpty(), "domains must start empty");
        assertNotNull(vo.getStdTemplateFields(), "stdTemplateFields must be initialized");
        assertTrue(vo.getStdTemplateFields().isEmpty(), "stdTemplateFields must start empty");
        assertNotNull(vo.getTemplateParams(), "templateParams must be initialized");
        assertTrue(vo.getTemplateParams().isEmpty(), "templateParams must start empty");
        assertNotNull(vo.getScriptResourcePaths(), "scriptResourcePaths must be initialized");
        assertTrue(vo.getScriptResourcePaths().isEmpty(), "scriptResourcePaths must start empty");

        // children, by contrast, is lazily created and starts null.
        assertNull(vo.getChildren(), "children must be null until checkCreateChildren() is called");
    }

    /* ===================================================================== */
    /* checkCreateChildren                                                   */
    /* ===================================================================== */

    /** 2a. checkCreateChildren() on a null children list creates and returns an empty mutable list. */
    @Test
    public void checkCreateChildren_createsEmptyMutableListWhenNull() {
        SystemCategoryVo vo = new SystemCategoryVo();
        assertNull(vo.getChildren());

        List<SystemCategoryVo> children = vo.checkCreateChildren();

        assertNotNull(children, "checkCreateChildren() must never return null");
        assertTrue(children.isEmpty(), "the freshly created list must be empty");
        // getChildren() now reflects the created list (no longer null).
        assertNotNull(vo.getChildren());
        assertSame(children, vo.getChildren());

        // The returned list must be mutable.
        children.add(new SystemCategoryVo());
        assertEquals(1, vo.getChildren().size());
    }

    /** 2b. Calling checkCreateChildren() twice returns the SAME list and does not clear existing children. */
    @Test
    public void checkCreateChildren_calledTwice_returnsSameListAndPreservesContent() {
        SystemCategoryVo vo = new SystemCategoryVo();

        List<SystemCategoryVo> first = vo.checkCreateChildren();
        first.add(new SystemCategoryVo());
        assertEquals(1, first.size());

        List<SystemCategoryVo> second = vo.checkCreateChildren();

        // Same underlying list instance, content untouched on the second call.
        assertSame(first, second, "checkCreateChildren() must not replace an existing list");
        assertEquals(1, second.size(), "existing children must be preserved");
    }

    /* ===================================================================== */
    /* compareTo                                                             */
    /* ===================================================================== */

    /*
     * compareTo orders by siblingSortOrder via Double.compare (docs/potential-errors.md CAT2 fixed):
     * equal values compare as 0 regardless of boxing, a null sort order is treated as the default
     * 0.0, and a null argument returns 0. The cases below cover equal/smaller/larger/null.
     */

    /** 3a. compareTo returns 0 for two equal sort orders. */
    @Test
    public void compareTo_equalSortOrder_returnsZero() {
        SystemCategoryVo a = cat(1.0);
        SystemCategoryVo b = cat(1.0);

        assertEquals(0, a.compareTo(b));
    }

    /** 3b. compareTo returns -1 when this category's sort-order is numerically smaller. */
    @Test
    public void compareTo_thisSmaller_returnsMinusOne() {
        SystemCategoryVo a = cat(1.0);
        SystemCategoryVo b = cat(2.0);

        assertEquals(-1, a.compareTo(b));
    }

    /** 3c. compareTo returns +1 when this category's sort-order is numerically larger. */
    @Test
    public void compareTo_thisLarger_returnsPlusOne() {
        SystemCategoryVo a = cat(2.0);
        SystemCategoryVo b = cat(1.0);

        assertEquals(1, a.compareTo(b));
    }

    /** 3d. compareTo treats a null argument as equal (returns 0) rather than throwing. */
    @Test
    public void compareTo_nullArgument_returnsZero() {
        SystemCategoryVo a = cat(5.0);

        assertEquals(0, a.compareTo(null));
    }

    /**
     * 3e. Two equal but independently-boxed sort orders compare as 0 and symmetrically.
     * Fixed (docs/potential-errors.md CAT2): autoboxing produces distinct Double instances for two
     * 2.0 values, so the old reference `==` wrongly returned 1; Double.compare returns 0.
     */
    @Test
    public void compareTo_equalButDistinctBoxedSortOrders_returnsZeroAndIsSymmetric() {
        SystemCategoryVo a = cat(2.0);
        SystemCategoryVo b = cat(2.0);

        assertEquals(0, a.compareTo(b));
        assertEquals(0, b.compareTo(a));
    }

    /** 3f. A null siblingSortOrder is treated as 0.0 and never NPEs. */
    @Test
    public void compareTo_nullSiblingSortOrder_treatedAsZero() {
        SystemCategoryVo nullSort = new SystemCategoryVo();
        assertNull(nullSort.getSiblingSortOrder());

        assertEquals(0, nullSort.compareTo(cat(0.0)));
        assertTrue(nullSort.compareTo(cat(1.0)) < 0);
        assertTrue(cat(1.0).compareTo(nullSort) > 0);
    }

    /* ===================================================================== */
    /* sortChildren                                                          */
    /* ===================================================================== */

    /** 4a. sortChildren() orders direct children ascending by siblingSortOrder. */
    @Test
    public void sortChildren_ordersDirectChildrenAscending() {
        SystemCategoryVo parent = new SystemCategoryVo();
        // Added out of order: 3.0, 1.0, 2.0
        parent.checkCreateChildren().add(cat(3.0));
        parent.checkCreateChildren().add(cat(1.0));
        parent.checkCreateChildren().add(cat(2.0));

        parent.sortChildren();

        List<SystemCategoryVo> children = parent.getChildren();
        assertEquals(3, children.size());
        assertEquals(Double.valueOf(1.0), children.get(0).getSiblingSortOrder());
        assertEquals(Double.valueOf(2.0), children.get(1).getSiblingSortOrder());
        assertEquals(Double.valueOf(3.0), children.get(2).getSiblingSortOrder());
    }

    /** 4b. sortChildren() recurses: a child's own out-of-order grandchildren are sorted too. */
    @Test
    public void sortChildren_recursivelySortsGrandchildren() {
        SystemCategoryVo parent = new SystemCategoryVo();
        SystemCategoryVo child = cat(1.0);
        // Grandchildren added out of order: 3.0, 1.0, 2.0
        child.checkCreateChildren().add(cat(3.0));
        child.checkCreateChildren().add(cat(1.0));
        child.checkCreateChildren().add(cat(2.0));
        parent.checkCreateChildren().add(child);

        parent.sortChildren();

        // The single child is still present...
        List<SystemCategoryVo> children = parent.getChildren();
        assertEquals(1, children.size());
        // ...and its grandchildren are now ordered ascending by the recursion.
        List<SystemCategoryVo> grandchildren = children.get(0).getChildren();
        assertEquals(3, grandchildren.size());
        assertEquals(Double.valueOf(1.0), grandchildren.get(0).getSiblingSortOrder());
        assertEquals(Double.valueOf(2.0), grandchildren.get(1).getSiblingSortOrder());
        assertEquals(Double.valueOf(3.0), grandchildren.get(2).getSiblingSortOrder());
    }

    /** 5. sortChildren() on a node whose children list is null does nothing and does not throw. */
    @Test
    public void sortChildren_nullChildren_isNoOp() {
        SystemCategoryVo vo = new SystemCategoryVo();
        assertNull(vo.getChildren());

        vo.sortChildren(); // must not throw

        // Still null: sortChildren must not lazily create the list.
        assertNull(vo.getChildren());
    }

    /* ===================================================================== */
    /* GETTERS / SETTERS                                                     */
    /* ===================================================================== */

    /** 6. Every system-specific getter/setter round-trips the value it was given. */
    @Test
    public void gettersAndSetters_roundTripValues() {
        SystemCategoryVo vo = new SystemCategoryVo();

        // type
        vo.setType(CategoryType.TEMPLATE);
        assertEquals(CategoryType.TEMPLATE, vo.getType());

        // editorFields (null by default, settable)
        List<String> editorFields = new ArrayList<>(List.of("title", "description"));
        vo.setEditorFields(editorFields);
        assertSame(editorFields, vo.getEditorFields());
        assertEquals(List.of("title", "description"), vo.getEditorFields());

        // atonFilter
        vo.setAtonFilter("type='light'");
        assertEquals("type='light'", vo.getAtonFilter());

        // domains
        List<DomainVo> domains = new ArrayList<>(List.of(new DomainVo("niord-client-nw")));
        vo.setDomains(domains);
        assertSame(domains, vo.getDomains());
        assertEquals(1, vo.getDomains().size());
        assertEquals("niord-client-nw", vo.getDomains().get(0).getDomainId());

        // stdTemplateFields
        List<String> stdFields = new ArrayList<>(List.of("a", "b"));
        vo.setStdTemplateFields(stdFields);
        assertSame(stdFields, vo.getStdTemplateFields());
        assertEquals(List.of("a", "b"), vo.getStdTemplateFields());

        // templateParams
        TemplateParamVo param = new TemplateParamVo();
        param.setParamId("p1");
        List<TemplateParamVo> params = new ArrayList<>(List.of(param));
        vo.setTemplateParams(params);
        assertSame(params, vo.getTemplateParams());
        assertEquals(1, vo.getTemplateParams().size());
        assertEquals("p1", vo.getTemplateParams().get(0).getParamId());

        // scriptResourcePaths
        List<String> scripts = new ArrayList<>(List.of("/scripts/x.ftl"));
        vo.setScriptResourcePaths(scripts);
        assertSame(scripts, vo.getScriptResourcePaths());
        assertEquals(List.of("/scripts/x.ftl"), vo.getScriptResourcePaths());

        // messageId
        vo.setMessageId("NW-001-25");
        assertEquals("NW-001-25", vo.getMessageId());

        // siblingSortOrder
        vo.setSiblingSortOrder(42.0);
        assertEquals(Double.valueOf(42.0), vo.getSiblingSortOrder());

        // children
        List<SystemCategoryVo> children = new ArrayList<>(List.of(new SystemCategoryVo()));
        vo.setChildren(children);
        assertSame(children, vo.getChildren());
        assertEquals(1, vo.getChildren().size());

        // setting children back to null is honored (e.g. when stripping a sub-tree)
        vo.setChildren(null);
        assertNull(vo.getChildren());

        // type can be set back to CATEGORY as well
        vo.setType(CategoryType.CATEGORY);
        assertEquals(CategoryType.CATEGORY, vo.getType());

        // editorFields can be cleared to null
        vo.setEditorFields(null);
        assertNull(vo.getEditorFields());
        assertFalse(CategoryType.TEMPLATE == vo.getType());
    }
}
