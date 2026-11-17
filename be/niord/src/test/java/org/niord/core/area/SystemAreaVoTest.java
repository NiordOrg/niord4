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
import org.niord.core.area.vo.SystemAreaVo;
import org.niord.core.area.vo.SystemAreaVo.AreaMessageSorting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SystemAreaVo}: sibling comparison, recursive child sorting, lazy child-list
 * creation and the message-sorting enum.
 */
public class SystemAreaVoTest {

    @Test
    public void compareToNullReturnsZero() {
        assertEquals(0, AreaTestFactory.sysVoWithSort(1.0).compareTo(null));
    }

    @Test
    public void compareToSmallerSortOrderReturnsNegative() {
        assertTrue(AreaTestFactory.sysVoWithSort(1.0).compareTo(AreaTestFactory.sysVoWithSort(2.0)) < 0);
    }

    @Test
    public void compareToLargerSortOrderReturnsPositive() {
        assertTrue(AreaTestFactory.sysVoWithSort(3.0).compareTo(AreaTestFactory.sysVoWithSort(2.0)) > 0);
    }

    @Test
    public void compareToEqualButDistinctBoxedSortOrders_returnsZero() {
        // Fixed (docs/potential-errors.md #2): compareTo now compares numerically via Double.compare,
        // so two independently-boxed equal sort orders compare as 0 (and symmetrically). Before the
        // fix the boxed-Double `==` reference check failed for distinct instances and returned 1.
        SystemAreaVo a = AreaTestFactory.sysVoWithSort(2.0);
        SystemAreaVo b = AreaTestFactory.sysVoWithSort(2.0);
        assertEquals(0, a.compareTo(b));
        assertEquals(0, b.compareTo(a));
    }

    @Test
    public void compareToSameInstanceReturnsZero() {
        SystemAreaVo a = AreaTestFactory.sysVoWithSort(2.0);
        assertEquals(0, a.compareTo(a));
    }

    @Test
    public void compareToTreatsNullSiblingSortOrderAsZeroWithoutThrowing() {
        // A null siblingSortOrder is treated as the entity default 0.0 and must never unbox to NPE.
        SystemAreaVo nullSort = new SystemAreaVo();
        assertNull(nullSort.getSiblingSortOrder());
        assertEquals(0, nullSort.compareTo(AreaTestFactory.sysVoWithSort(0.0)));
        assertTrue(nullSort.compareTo(AreaTestFactory.sysVoWithSort(1.0)) < 0);
        assertTrue(AreaTestFactory.sysVoWithSort(1.0).compareTo(nullSort) > 0);
    }

    @Test
    public void sortChildrenSortsRecursivelyByDistinctSortOrder() {
        SystemAreaVo parent = new SystemAreaVo();
        SystemAreaVo first = AreaTestFactory.sysVoWithSort(1.0);
        first.setChildren(new ArrayList<>(Arrays.asList(
                AreaTestFactory.sysVoWithSort(2.0),
                AreaTestFactory.sysVoWithSort(1.0))));
        parent.setChildren(new ArrayList<>(Arrays.asList(
                AreaTestFactory.sysVoWithSort(3.0),
                first,
                AreaTestFactory.sysVoWithSort(2.0))));

        parent.sortChildren();

        List<SystemAreaVo> sorted = parent.getChildren();
        assertEquals(Double.valueOf(1.0), sorted.get(0).getSiblingSortOrder());
        assertEquals(Double.valueOf(2.0), sorted.get(1).getSiblingSortOrder());
        assertEquals(Double.valueOf(3.0), sorted.get(2).getSiblingSortOrder());

        // Grandchildren of the first child were sorted too.
        List<SystemAreaVo> grandchildren = sorted.get(0).getChildren();
        assertEquals(Double.valueOf(1.0), grandchildren.get(0).getSiblingSortOrder());
        assertEquals(Double.valueOf(2.0), grandchildren.get(1).getSiblingSortOrder());
    }

    @Test
    public void checkCreateChildrenLazilyInitialisesAndIsStable() {
        SystemAreaVo vo = new SystemAreaVo();
        assertNull(vo.getChildren());

        List<SystemAreaVo> created = vo.checkCreateChildren();
        assertNotNull(created);
        assertTrue(created.isEmpty());
        assertSame(created, vo.checkCreateChildren());
        assertSame(created, vo.getChildren());
    }

    @Test
    public void areaMessageSortingHasSixValues() {
        assertEquals(6, AreaMessageSorting.values().length);
        assertEquals(AreaMessageSorting.CCW, AreaMessageSorting.valueOf("CCW"));
        // Pin the full set used by the message-sorting logic.
        assertEquals(
                Arrays.asList("NS", "SN", "EW", "WE", "CW", "CCW"),
                Arrays.stream(AreaMessageSorting.values()).map(Enum::name).toList());
    }
}
