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
import org.niord.core.aton.vo.AtonNodeVo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for {@link CategorySearchParams}.
 *
 * <p>{@code CategorySearchParams} is a plain method-chaining holder of category
 * search criteria with a documented {@link CategorySearchParams#toString()}
 * rendering. These tests are pure in-memory: no database, no CDI, no Quarkus.
 * They verify the fluent-setter contract, the documented defaults, and the
 * exact {@code toString()} format (which criteria appear and how they are
 * formatted, including the omission of blank text criteria and the
 * empty-string result for empty params).
 */
public class CategorySearchParamsTest {

    /* ===================================================================== */
    /* FLUENT SETTERS                                                        */
    /* ===================================================================== */

    /** parentId(...) sets the value and returns the same instance. */
    @Test
    public void parentId_setsValueAndReturnsSameInstance() {
        CategorySearchParams params = new CategorySearchParams();
        CategorySearchParams returned = params.parentId(42);

        assertEquals(Integer.valueOf(42), params.getParentId());
        assertSame(params, returned, "parentId(...) must return the same instance for chaining");
    }

    /** ancestorId(...) sets the value and returns the same instance. */
    @Test
    public void ancestorId_setsValueAndReturnsSameInstance() {
        CategorySearchParams params = new CategorySearchParams();
        CategorySearchParams returned = params.ancestorId(7);

        assertEquals(Integer.valueOf(7), params.getAncestorId());
        assertSame(params, returned);
    }

    /** language(...) sets the value and returns the same instance. */
    @Test
    public void language_setsValueAndReturnsSameInstance() {
        CategorySearchParams params = new CategorySearchParams();
        CategorySearchParams returned = params.language("da");

        assertEquals("da", params.getLanguage());
        assertSame(params, returned);
    }

    /** name(...) sets the value and returns the same instance. */
    @Test
    public void name_setsValueAndReturnsSameInstance() {
        CategorySearchParams params = new CategorySearchParams();
        CategorySearchParams returned = params.name("Buoy");

        assertEquals("Buoy", params.getName());
        assertSame(params, returned);
    }

    /** type(...) sets the value and returns the same instance. */
    @Test
    public void type_setsValueAndReturnsSameInstance() {
        CategorySearchParams params = new CategorySearchParams();
        CategorySearchParams returned = params.type(CategoryType.TEMPLATE);

        assertEquals(CategoryType.TEMPLATE, params.getType());
        assertSame(params, returned);
    }

    /** domain(...) sets the value and returns the same instance. */
    @Test
    public void domain_setsValueAndReturnsSameInstance() {
        CategorySearchParams params = new CategorySearchParams();
        CategorySearchParams returned = params.domain("dk");

        assertEquals("dk", params.getDomain());
        assertSame(params, returned);
    }

    /** exact(...) sets the flag and returns the same instance. */
    @Test
    public void exact_setsFlagAndReturnsSameInstance() {
        CategorySearchParams params = new CategorySearchParams();
        CategorySearchParams returned = params.exact(true);

        assertTrue(params.isExact());
        assertSame(params, returned);
    }

    /** inactive(...) sets the flag and returns the same instance. */
    @Test
    public void inactive_setsFlagAndReturnsSameInstance() {
        CategorySearchParams params = new CategorySearchParams();
        CategorySearchParams returned = params.inactive(true);

        assertTrue(params.isInactive());
        assertSame(params, returned);
    }

    /** atons(...) sets the list and returns the same instance. */
    @Test
    public void atons_setsListAndReturnsSameInstance() {
        CategorySearchParams params = new CategorySearchParams();
        List<AtonNodeVo> atons = new ArrayList<>(List.of(new AtonNodeVo(), new AtonNodeVo()));
        CategorySearchParams returned = params.atons(atons);

        assertSame(atons, params.getAtons(), "getAtons() must return the very list that was set");
        assertEquals(2, params.getAtons().size());
        assertSame(params, returned);
    }

    /* ===================================================================== */
    /* DEFAULTS                                                              */
    /* ===================================================================== */

    /** A fresh instance has falsey flags, an empty (non-null) atons list, and null type/parentId. */
    @Test
    public void freshInstance_hasDocumentedDefaults() {
        CategorySearchParams params = new CategorySearchParams();

        assertFalse(params.isExact(), "exact defaults to false");
        assertFalse(params.isInactive(), "inactive defaults to false");
        assertNotNull(params.getAtons(), "atons defaults to a non-null list");
        assertTrue(params.getAtons().isEmpty(), "atons defaults to an empty list");
        assertNull(params.getType(), "type defaults to null");
        assertNull(params.getParentId(), "parentId defaults to null");
    }

    /* ===================================================================== */
    /* toString()                                                           */
    /* ===================================================================== */

    /** toString() renders every set criterion in its documented format. */
    @Test
    public void toString_includesAllSetCriteriaInDocumentedFormat() {
        CategorySearchParams params = new CategorySearchParams()
                .parentId(1)
                .ancestorId(2)
                .type(CategoryType.TEMPLATE)
                .language("en")
                .name("Light")
                .domain("dk")
                .exact(true)
                .inactive(true)
                .atons(new ArrayList<>(List.of(new AtonNodeVo())));

        String s = params.toString();

        assertTrue(s.contains("Parent: 1"), "missing parent: " + s);
        assertTrue(s.contains("Ancestor: 2"), "missing ancestor: " + s);
        assertTrue(s.contains("Type: TEMPLATE"), "missing type: " + s);
        assertTrue(s.contains("Language: en"), "missing language: " + s);
        assertTrue(s.contains("Name: 'Light'"), "missing name: " + s);
        assertTrue(s.contains("Domain: 'dk'"), "missing domain: " + s);
        assertTrue(s.contains("Exact: true"), "missing exact: " + s);
        assertTrue(s.contains("Include inactive: true"), "missing inactive: " + s);
        assertTrue(s.contains("#AtoNs: 1"), "missing aton count: " + s);
    }

    /** toString() on empty params yields the empty string (no criteria => nothing joined). */
    @Test
    public void toString_emptyParams_isEmptyString() {
        assertEquals("", new CategorySearchParams().toString());
    }

    /** toString() omits blank language/name/domain (they are guarded by isNotBlank). */
    @Test
    public void toString_omitsBlankTextCriteria() {
        CategorySearchParams params = new CategorySearchParams()
                .language("")
                .name("")
                .domain("");

        String s = params.toString();

        assertFalse(s.contains("Language:"), "blank language must be omitted: " + s);
        assertFalse(s.contains("Name:"), "blank name must be omitted: " + s);
        assertFalse(s.contains("Domain:"), "blank domain must be omitted: " + s);
        assertEquals("", s, "only blank text criteria set => empty rendering");
    }

    /* ===================================================================== */
    /* CONSTANTS                                                             */
    /* ===================================================================== */

    /** The tree-order sort token is the literal "TREE_ORDER". */
    @Test
    public void treeSortOrderConstant_isTreeOrder() {
        assertEquals("TREE_ORDER", CategorySearchParams.TREE_SORT_ORDER);
    }
}
