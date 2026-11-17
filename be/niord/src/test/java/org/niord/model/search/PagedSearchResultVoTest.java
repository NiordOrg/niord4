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

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PagedSearchResultVo} pagination, mapping and size book-keeping.
 *
 * Pure object logic; no Quarkus or persistence involved. Tests are deterministic and
 * independent: each builds its own immutable content list and asserts exact outcomes.
 */
public class PagedSearchResultVoTest {

    /** Concrete subclass of the abstract params VO, used only to drive paginate(content, params). */
    private static final class TestParams extends PagedSearchParamsVo {
    }

    // ------------------------------------------------------------------
    // paginate(content, page, size)
    // ------------------------------------------------------------------

    @Test
    public void paginate_firstPage_returnsFirstSlice() {
        List<Integer> content = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        PagedSearchResultVo<Integer> result = PagedSearchResultVo.paginate(content, 0, 3);

        assertEquals(Arrays.asList(1, 2, 3), result.getData());
        assertEquals(10, result.getTotal());
        assertEquals(3, result.getSize());
    }

    @Test
    public void paginate_secondPage_skipsPriorPages() {
        List<Integer> content = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        PagedSearchResultVo<Integer> result = PagedSearchResultVo.paginate(content, 1, 3);

        assertEquals(Arrays.asList(4, 5, 6), result.getData());
        assertEquals(10, result.getTotal());
        assertEquals(3, result.getSize());
    }

    @Test
    public void paginate_partialLastPage_returnsRemainder() {
        List<Integer> content = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        // page 3 size 3 -> skip 9, limit 3 -> only element 10 remains
        PagedSearchResultVo<Integer> result = PagedSearchResultVo.paginate(content, 3, 3);

        assertEquals(Arrays.asList(10), result.getData());
        assertEquals(10, result.getTotal());
        assertEquals(3, result.getSize());
    }

    @Test
    public void paginate_sizeLargerThanContent_returnsAll() {
        List<Integer> content = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        PagedSearchResultVo<Integer> result = PagedSearchResultVo.paginate(content, 0, 100);

        assertEquals(content, result.getData());
        assertEquals(10, result.getTotal());
        assertEquals(100, result.getSize());
    }

    @Test
    public void paginate_nullContent_yieldsEmptyDataAndZeroTotal() {
        PagedSearchResultVo<Integer> result = PagedSearchResultVo.paginate(null, 0, 3);

        assertNotNull(result.getData());
        assertTrue(result.getData().isEmpty());
        assertEquals(0, result.getTotal());
        assertEquals(3, result.getSize());
    }

    // ------------------------------------------------------------------
    // paginate(content, page, size, comparator)
    // ------------------------------------------------------------------

    @Test
    public void paginate_withComparator_sortsBeforePaging() {
        List<Integer> content = Arrays.asList(1, 2, 3, 4, 5);
        Comparator<Integer> reverse = Comparator.reverseOrder();

        PagedSearchResultVo<Integer> result = PagedSearchResultVo.paginate(content, 0, 2, reverse);

        // Sorted descending: [5,4,3,2,1]; first page size 2 -> [5,4]
        assertEquals(Arrays.asList(5, 4), result.getData());
        assertEquals(5, result.getTotal());
        assertEquals(2, result.getSize());
    }

    // ------------------------------------------------------------------
    // paginate(content, page, size, filter, comparator)
    // ------------------------------------------------------------------

    @Test
    public void paginate_withFilter_totalReflectsFilteredSize() {
        List<Integer> content = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        PagedSearchResultVo<Integer> result =
                PagedSearchResultVo.paginate(content, 0, 10, n -> n % 2 == 0, null);

        assertEquals(Arrays.asList(2, 4, 6, 8, 10), result.getData());
        // total reflects the FILTERED size, not the original list size
        assertEquals(5, result.getTotal());
        assertEquals(10, result.getSize());
    }

    // ------------------------------------------------------------------
    // paginate(content, PagedSearchParamsVo)
    // ------------------------------------------------------------------

    @Test
    public void paginate_withParams_usesPageAndMaxSize() {
        List<Integer> content = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        PagedSearchParamsVo params = new TestParams().page(1).maxSize(2);

        PagedSearchResultVo<Integer> result = PagedSearchResultVo.paginate(content, params);

        // page 1, size 2 -> skip 2, limit 2 -> [3,4]
        assertEquals(Arrays.asList(3, 4), result.getData());
        assertEquals(10, result.getTotal());
        assertEquals(2, result.getSize());
    }

    // ------------------------------------------------------------------
    // map(mapper)
    // ------------------------------------------------------------------

    @Test
    public void map_transformsDataAndPreservesMetadata() {
        List<Integer> content = Arrays.asList(1, 2, 3, 4, 5);
        PagedSearchResultVo<Integer> result = PagedSearchResultVo.paginate(content, 0, 3);
        result.setDescription("evens");

        PagedSearchResultVo<String> mapped = result.map(i -> "n" + i);

        assertEquals(Arrays.asList("n1", "n2", "n3"), mapped.getData());
        // size/total/description preserved from the source result
        assertEquals(result.getSize(), mapped.getSize());
        assertEquals(result.getTotal(), mapped.getTotal());
        assertEquals("evens", mapped.getDescription());
    }

    @Test
    public void map_withNullData_yieldsNullData() {
        PagedSearchResultVo<Integer> result = new PagedSearchResultVo<>();
        result.setData(null);
        result.setTotal(7);
        result.setSize(4);

        PagedSearchResultVo<String> mapped = result.map(i -> "n" + i);

        // impl guards null data and propagates null rather than mapping
        assertNull(mapped.getData());
        assertEquals(7, mapped.getTotal());
        assertEquals(4, mapped.getSize());
    }

    @Test
    public void map_withNullMapper_throwsNullPointerException() {
        PagedSearchResultVo<Integer> result = PagedSearchResultVo.paginate(Arrays.asList(1, 2), 0, 2);
        assertThrows(NullPointerException.class, () -> result.map(null));
    }

    // ------------------------------------------------------------------
    // updateSize()
    // ------------------------------------------------------------------

    @Test
    public void updateSize_bumpsTotalUpToSizeWhenTotalSmaller() {
        PagedSearchResultVo<Integer> result = new PagedSearchResultVo<>();
        result.setData(Arrays.asList(1, 2, 3));
        result.setTotal(0);

        PagedSearchResultVo<Integer> returned = result.updateSize();

        assertEquals(3, result.getSize());
        // total was below size, so it is bumped up to match size
        assertEquals(3, result.getTotal());
        assertSame(result, returned);
    }

    @Test
    public void updateSize_keepsLargerTotal() {
        PagedSearchResultVo<Integer> result = new PagedSearchResultVo<>();
        result.setData(Arrays.asList(1, 2, 3));
        result.setTotal(9);

        PagedSearchResultVo<Integer> returned = result.updateSize();

        assertEquals(3, result.getSize());
        // total already larger than size -> left untouched
        assertEquals(9, result.getTotal());
        assertSame(result, returned);
    }

    @Test
    public void updateSize_onEmptyData_setsSizeZero() {
        PagedSearchResultVo<Integer> result = new PagedSearchResultVo<>();

        result.updateSize();

        assertEquals(0, result.getSize());
        assertEquals(0, result.getTotal());
    }

    // ------------------------------------------------------------------
    // getters / setters and default state
    // ------------------------------------------------------------------

    @Test
    public void freshInstance_hasNonNullEmptyData() {
        PagedSearchResultVo<Integer> result = new PagedSearchResultVo<>();

        assertNotNull(result.getData());
        assertTrue(result.getData().isEmpty());
        assertEquals(0, result.getTotal());
        assertEquals(0, result.getSize());
        assertNull(result.getDescription());
    }

    @Test
    public void accessors_roundTripValues() {
        PagedSearchResultVo<Integer> result = new PagedSearchResultVo<>();
        List<Integer> data = Arrays.asList(10, 20, 30);

        result.setData(data);
        result.setTotal(42L);
        result.setSize(7);
        result.setDescription("criteria");

        assertEquals(data, result.getData());
        assertEquals(42L, result.getTotal());
        assertEquals(7, result.getSize());
        assertEquals("criteria", result.getDescription());
    }
}
