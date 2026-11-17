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
package org.niord.core.area;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AreaSearchParams}: the fluent builder identity contract, default flag values
 * and the {@code toString()} used in service-layer logging.
 */
public class AreaSearchParamsTest {

    @Test
    public void chainingSettersReturnSameInstance() {
        AreaSearchParams p = new AreaSearchParams();
        assertSame(p, p.parentId(1));
        assertSame(p, p.areaIds(new LinkedHashSet<>()));
        assertSame(p, p.language("da"));
        assertSame(p, p.name("foo"));
        assertSame(p, p.type(AreaType.COUNTRY));
        assertSame(p, p.domain("dom"));
        assertSame(p, p.exact(true));
        assertSame(p, p.geometry(true));
        assertSame(p, p.messageSorting(true));
        assertSame(p, p.inactive(true));
    }

    @Test
    public void booleanFlagsDefaultToFalse() {
        AreaSearchParams p = new AreaSearchParams();
        assertFalse(p.isExact());
        assertFalse(p.isGeometry());
        assertFalse(p.isMessageSorting());
        assertFalse(p.isInactive());
    }

    @Test
    public void treeSortOrderConstant() {
        assertEquals("TREE_ORDER", AreaSearchParams.TREE_SORT_ORDER);
    }

    @Test
    public void toStringIncludesSetFields() {
        String s = new AreaSearchParams()
                .parentId(5)
                .name("foo")
                .type(AreaType.COUNTRY)
                .exact(true)
                .toString();

        assertTrue(s.contains("Parent: 5"), s);
        assertTrue(s.contains("Name: 'foo'"), s);
        assertTrue(s.contains("Type: COUNTRY"), s);
        assertTrue(s.contains("Exact: true"), s);
    }

    @Test
    public void toStringIsEmptyWhenNothingSet() {
        assertEquals("", new AreaSearchParams().toString());
    }

    @Test
    public void toStringFormatsCollectionsAndFlags() {
        Set<Integer> ids = new LinkedHashSet<>();
        ids.add(1);
        ids.add(2);

        String s = new AreaSearchParams()
                .areaIds(ids)
                .language("en")
                .domain("dom")
                .geometry(true)
                .messageSorting(true)
                .inactive(true)
                .toString();

        assertTrue(s.contains("Areas: " + ids), s);
        assertTrue(s.contains("Language: en"), s);
        assertTrue(s.contains("Domain: 'dom'"), s);
        assertTrue(s.contains("Geometry: true"), s);
        assertTrue(s.contains("Message-Sorting: true"), s);
        assertTrue(s.contains("Include inactive: true"), s);
    }
}
