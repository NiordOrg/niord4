/*
 * Copyright 2024 Danish Maritime Authority.
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

package org.niord.model.search;

import org.junit.jupiter.api.Test;
import org.niord.model.search.PagedSearchParamsVo.SortOrder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic unit tests for {@link PagedSearchParamsVo}.
 *
 * <p>The class under test is abstract but declares no abstract methods, so a tiny
 * concrete subclass ({@link Params}) is used to exercise the instance behaviour.
 * The static helpers are exercised directly through the class.</p>
 */
public class PagedSearchParamsVoTest {

    /** Minimal concrete subclass so we can instantiate the abstract VO. */
    private static class Params extends PagedSearchParamsVo {
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    @Test
    public void defaults_onFreshInstance() {
        Params p = new Params();
        assertEquals(Integer.MAX_VALUE, p.getMaxSize());
        assertEquals(0, p.getPage());
        assertNull(p.getSortBy());
        assertNull(p.getSortOrder());
    }

    // -------------------------------------------------------------------------
    // maxSize(...)
    // -------------------------------------------------------------------------

    @Test
    public void maxSize_setsValueAndReturnsThis() {
        Params p = new Params();
        PagedSearchParamsVo returned = p.maxSize(42);
        assertSame(p, returned, "setter must return 'this' for chaining");
        assertEquals(42, p.getMaxSize());
    }

    @Test
    public void maxSize_null_leavesValueUnchanged() {
        Params p = new Params();
        p.maxSize(17);
        PagedSearchParamsVo returned = p.maxSize(null);
        assertSame(p, returned);
        // null is tolerated and silently ignored: previous value survives.
        assertEquals(17, p.getMaxSize());
    }

    @Test
    public void maxSize_null_onFreshInstance_keepsDefault() {
        Params p = new Params();
        p.maxSize(null);
        assertEquals(Integer.MAX_VALUE, p.getMaxSize());
    }

    // -------------------------------------------------------------------------
    // page(...)
    // -------------------------------------------------------------------------

    @Test
    public void page_setsValueAndReturnsThis() {
        Params p = new Params();
        PagedSearchParamsVo returned = p.page(3);
        assertSame(p, returned);
        assertEquals(3, p.getPage());
    }

    @Test
    public void page_null_leavesValueUnchanged() {
        Params p = new Params();
        p.page(5);
        PagedSearchParamsVo returned = p.page(null);
        assertSame(p, returned);
        assertEquals(5, p.getPage());
    }

    @Test
    public void page_null_onFreshInstance_keepsDefault() {
        Params p = new Params();
        p.page(null);
        assertEquals(0, p.getPage());
    }

    // -------------------------------------------------------------------------
    // sortBy(...)
    // -------------------------------------------------------------------------

    @Test
    public void sortBy_setsValueAndReturnsThis() {
        Params p = new Params();
        PagedSearchParamsVo returned = p.sortBy("title");
        assertSame(p, returned);
        assertEquals("title", p.getSortBy());
    }

    @Test
    public void sortBy_null_overwritesToNull_notNullGuarded() {
        Params p = new Params();
        p.sortBy("title");
        PagedSearchParamsVo returned = p.sortBy(null);
        assertSame(p, returned);
        // Unlike maxSize/page, sortBy is NOT null-guarded: it overwrites with null.
        assertNull(p.getSortBy());
    }

    // -------------------------------------------------------------------------
    // sortOrder(...)
    // -------------------------------------------------------------------------

    @Test
    public void sortOrder_setsAscAndReturnsThis() {
        Params p = new Params();
        PagedSearchParamsVo returned = p.sortOrder(SortOrder.ASC);
        assertSame(p, returned);
        assertEquals(SortOrder.ASC, p.getSortOrder());
    }

    @Test
    public void sortOrder_setsDesc() {
        Params p = new Params();
        p.sortOrder(SortOrder.DESC);
        assertEquals(SortOrder.DESC, p.getSortOrder());
    }

    @Test
    public void sortOrder_null_overwritesToNull() {
        Params p = new Params();
        p.sortOrder(SortOrder.ASC);
        p.sortOrder(null);
        assertNull(p.getSortOrder());
    }

    @Test
    public void sortOrder_enumHasExactlyAscAndDesc() {
        assertArrayEquals(
                new SortOrder[]{SortOrder.ASC, SortOrder.DESC},
                SortOrder.values());
        assertEquals(SortOrder.ASC, SortOrder.valueOf("ASC"));
        assertEquals(SortOrder.DESC, SortOrder.valueOf("DESC"));
    }

    // -------------------------------------------------------------------------
    // Chaining across multiple setters
    // -------------------------------------------------------------------------

    @Test
    public void chaining_acrossAllSettersAppliesEveryValue() {
        Params p = new Params();
        PagedSearchParamsVo returned = p
                .maxSize(10)
                .page(2)
                .sortBy("name")
                .sortOrder(SortOrder.DESC);
        assertSame(p, returned);
        assertEquals(10, p.getMaxSize());
        assertEquals(2, p.getPage());
        assertEquals("name", p.getSortBy());
        assertEquals(SortOrder.DESC, p.getSortOrder());
    }

    // -------------------------------------------------------------------------
    // toSet(Set)
    // -------------------------------------------------------------------------

    @Test
    public void toSet_null_returnsEmptyMutableHashSet() {
        Set<String> result = PagedSearchParamsVo.toSet((Set<String>) null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
        assertTrue(result instanceof HashSet);
        // must be mutable
        assertTrue(result.add("x"));
        assertEquals(1, result.size());
    }

    @Test
    public void toSet_copiesContentsIntoDistinctMutableSet() {
        Set<String> source = new HashSet<>(Arrays.asList("a", "b"));
        Set<String> result = PagedSearchParamsVo.toSet(source);

        // same contents
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), result);
        assertEquals(2, result.size());
        // but a distinct instance (defensive copy)
        assertNotSame(source, result);
        // and mutable
        assertTrue(result.add("c"));
        assertEquals(3, result.size());
        // mutating the copy must not affect the source
        assertEquals(2, source.size());
        assertFalse(source.contains("c"));
    }

    // -------------------------------------------------------------------------
    // toSet(Set, mapper)
    // -------------------------------------------------------------------------

    @Test
    public void toSet_setWithMapper_mapsEachElement() {
        Set<Integer> source = new HashSet<>(Arrays.asList(1, 2, 3));
        Set<Integer> result = PagedSearchParamsVo.toSet(source, i -> i * 10);
        assertEquals(new HashSet<>(Arrays.asList(10, 20, 30)), result);
        assertEquals(3, result.size());
    }

    @Test
    public void toSet_nullSetWithMapper_returnsEmptySet() {
        Function<Integer, Integer> mapper = i -> i + 1;
        Set<Integer> result = PagedSearchParamsVo.toSet((Set<Integer>) null, mapper);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void toSet_setWithMapper_collapsesDuplicateMappedValues() {
        // Distinct inputs that map to the same value collapse, since the result is a Set.
        Set<Integer> source = new HashSet<>(Arrays.asList(1, 2, 3, 4));
        Set<Integer> result = PagedSearchParamsVo.toSet(source, i -> i % 2);
        assertEquals(new HashSet<>(Arrays.asList(0, 1)), result);
        assertEquals(2, result.size());
    }

    // -------------------------------------------------------------------------
    // toSet(Array, mapper)
    // -------------------------------------------------------------------------

    @Test
    public void toSet_arrayWithMapper_mapsEachElement() {
        Set<Integer> result = PagedSearchParamsVo.toSet(new Integer[]{1, 2}, i -> i + 1);
        assertEquals(new HashSet<>(Arrays.asList(2, 3)), result);
        assertEquals(2, result.size());
    }

    @Test
    public void toSet_nullArrayWithMapper_returnsEmptySet() {
        Function<Integer, Integer> mapper = i -> i + 1;
        Set<Integer> result = PagedSearchParamsVo.toSet((Integer[]) null, mapper);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void toSet_emptyArrayWithMapper_returnsEmptySet() {
        Set<Integer> result = PagedSearchParamsVo.toSet(new Integer[0], i -> i + 1);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // checkNull(arg, mapper)
    // -------------------------------------------------------------------------

    @Test
    public void checkNull_null_returnsNull() {
        assertNull(PagedSearchParamsVo.checkNull(null, (String s) -> s.toUpperCase()));
    }

    @Test
    public void checkNull_nonNull_appliesMapper() {
        assertEquals("X", PagedSearchParamsVo.checkNull("x", String::toUpperCase));
    }

    // -------------------------------------------------------------------------
    // checkNull(arg, default, mapper)
    // -------------------------------------------------------------------------

    @Test
    public void checkNull_withDefault_null_returnsDefault() {
        assertEquals("DEF",
                PagedSearchParamsVo.checkNull(null, "DEF", (String s) -> s.toUpperCase()));
    }

    @Test
    public void checkNull_withDefault_nonNull_appliesMapper() {
        assertEquals("X",
                PagedSearchParamsVo.checkNull("x", "DEF", String::toUpperCase));
    }
}
