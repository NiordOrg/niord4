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
package org.niord.core.schedule;

import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.message.DateInterval;
import org.niord.core.schedule.vo.FiringPeriodVo;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier-1 unit tests for {@link FiringPeriod} entity logic.
 *
 * <p>Pure-logic tests with no DB / no Quarkus context. They pin the observable contract of
 * {@code firingPeriodDefined}, {@code hasChanged}, {@code compareTo}, {@code toVo},
 * {@code toDateInterval}, {@code updateFiringPeriod} and the two value-object constructors.
 *
 * <p>Dates are constructed with fixed epoch millis so the comparisons are deterministic.
 */
public class FiringPeriodTest {

    /** Builds a FiringPeriod with the given area + dates, without touching persistence. */
    private static FiringPeriod fp(Area area, Date from, Date to) {
        FiringPeriod fp = new FiringPeriod();
        fp.setArea(area);
        fp.setFromDate(from);
        fp.setToDate(to);
        return fp;
    }

    private static Area area(int id) {
        Area area = new Area();
        area.setId(id);
        return area;
    }

    // -------------------------------------------------------------------------
    // firingPeriodDefined()
    // -------------------------------------------------------------------------

    @Test
    public void firingPeriodDefined_trueWhenAllPresent() {
        FiringPeriod p = fp(area(7), new Date(1_000L), new Date(2_000L));
        assertTrue(p.firingPeriodDefined());
    }

    @Test
    public void firingPeriodDefined_falseWhenAreaNull() {
        FiringPeriod p = fp(null, new Date(1_000L), new Date(2_000L));
        assertFalse(p.firingPeriodDefined());
    }

    @Test
    public void firingPeriodDefined_falseWhenFromDateNull() {
        FiringPeriod p = fp(area(7), null, new Date(2_000L));
        assertFalse(p.firingPeriodDefined());
    }

    @Test
    public void firingPeriodDefined_falseWhenToDateNull() {
        FiringPeriod p = fp(area(7), new Date(1_000L), null);
        assertFalse(p.firingPeriodDefined());
    }

    // -------------------------------------------------------------------------
    // hasChanged(other)
    // -------------------------------------------------------------------------

    @Test
    public void hasChanged_falseWhenSameMillisDifferentInstances() {
        long from = System.nanoTime();
        long to = from + 5_000L;
        FiringPeriod a = fp(area(7), new Date(from), new Date(to));
        // Distinct Date instances carrying the same epoch millis must be treated as equal.
        FiringPeriod b = fp(area(7), new Date(from), new Date(to));
        assertFalse(a.hasChanged(b));
    }

    @Test
    public void hasChanged_trueWhenFromDateDiffers() {
        long base = System.nanoTime();
        FiringPeriod a = fp(area(7), new Date(base), new Date(base + 5_000L));
        FiringPeriod b = fp(area(7), new Date(base + 1L), new Date(base + 5_000L));
        assertTrue(a.hasChanged(b));
    }

    @Test
    public void hasChanged_trueWhenToDateDiffers() {
        long base = System.nanoTime();
        FiringPeriod a = fp(area(7), new Date(base), new Date(base + 5_000L));
        FiringPeriod b = fp(area(7), new Date(base), new Date(base + 6_000L));
        assertTrue(a.hasChanged(b));
    }

    // -------------------------------------------------------------------------
    // compareTo(other)
    // -------------------------------------------------------------------------

    @Test
    public void compareTo_earlierFromDateIsNegative() {
        long base = System.nanoTime();
        FiringPeriod earlier = fp(area(7), new Date(base), new Date(base + 9_000L));
        FiringPeriod later = fp(area(7), new Date(base + 1_000L), new Date(base + 2_000L));
        assertTrue(earlier.compareTo(later) < 0);
    }

    @Test
    public void compareTo_sameFromEarlierToIsNegative() {
        long base = System.nanoTime();
        FiringPeriod earlier = fp(area(7), new Date(base), new Date(base + 1_000L));
        FiringPeriod later = fp(area(7), new Date(base), new Date(base + 2_000L));
        assertTrue(earlier.compareTo(later) < 0);
    }

    @Test
    public void compareTo_identicalIsZero() {
        long base = System.nanoTime();
        FiringPeriod a = fp(area(7), new Date(base), new Date(base + 1_000L));
        FiringPeriod b = fp(area(7), new Date(base), new Date(base + 1_000L));
        assertEquals(0, a.compareTo(b));
    }

    @Test
    public void compareTo_laterFromDateIsPositive() {
        long base = System.nanoTime();
        FiringPeriod later = fp(area(7), new Date(base + 1_000L), new Date(base + 2_000L));
        FiringPeriod earlier = fp(area(7), new Date(base), new Date(base + 9_000L));
        assertTrue(later.compareTo(earlier) > 0);
    }

    @Test
    public void compareTo_sameFromLaterToIsPositive() {
        long base = System.nanoTime();
        FiringPeriod later = fp(area(7), new Date(base), new Date(base + 2_000L));
        FiringPeriod earlier = fp(area(7), new Date(base), new Date(base + 1_000L));
        assertTrue(later.compareTo(earlier) > 0);
    }

    // -------------------------------------------------------------------------
    // toVo()
    // -------------------------------------------------------------------------

    @Test
    public void toVo_copiesAllFieldsIncludingAreaId() {
        Area a = area(7);
        Date from = new Date(1_111L);
        Date to = new Date(2_222L);

        FiringPeriod p = fp(a, from, to);
        p.setId(42);
        p.setLegacyId("LEG-" + System.nanoTime());

        FiringPeriodVo vo = p.toVo();

        assertEquals(Integer.valueOf(7), vo.getAreaId());
        assertEquals(Integer.valueOf(42), vo.getId());
        assertEquals(p.getLegacyId(), vo.getLegacyId());
        assertSame(from, vo.getFromDate());
        assertSame(to, vo.getToDate());
    }

    // -------------------------------------------------------------------------
    // toDateInterval()
    // -------------------------------------------------------------------------

    @Test
    public void toDateInterval_copiesFromAndToDate() {
        Date from = new Date(3_333L);
        Date to = new Date(4_444L);
        FiringPeriod p = fp(area(7), from, to);

        DateInterval di = p.toDateInterval();

        assertSame(from, di.getFromDate());
        assertSame(to, di.getToDate());
    }

    // -------------------------------------------------------------------------
    // updateFiringPeriod(other)
    // -------------------------------------------------------------------------

    @Test
    public void updateFiringPeriod_copiesDatesAndLegacyIdButNotArea() {
        Area originalArea = area(7);
        FiringPeriod target = fp(originalArea, new Date(1_000L), new Date(2_000L));
        target.setLegacyId("OLD-" + System.nanoTime());

        Area otherArea = area(99);
        String newLegacy = "NEW-" + System.nanoTime();
        Date newFrom = new Date(5_000L);
        Date newTo = new Date(6_000L);
        FiringPeriod source = fp(otherArea, newFrom, newTo);
        source.setLegacyId(newLegacy);

        target.updateFiringPeriod(source);

        // Dates + legacyId are copied from the source...
        assertSame(newFrom, target.getFromDate());
        assertSame(newTo, target.getToDate());
        assertEquals(newLegacy, target.getLegacyId());
        // ...but the area reference is deliberately left untouched.
        assertSame(originalArea, target.getArea());
    }

    @Test
    public void updateFiringPeriod_leavesAreaUnchangedEvenWhenSourceAreaNull() {
        Area originalArea = area(7);
        FiringPeriod target = fp(originalArea, new Date(1_000L), new Date(2_000L));

        FiringPeriod source = fp(null, new Date(5_000L), new Date(6_000L));
        source.setLegacyId("LEG-" + System.nanoTime());

        target.updateFiringPeriod(source);

        assertSame(originalArea, target.getArea());
        assertEquals(source.getLegacyId(), target.getLegacyId());
        assertSame(source.getFromDate(), target.getFromDate());
        assertSame(source.getToDate(), target.getToDate());
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    @Test
    public void constructorWithArea_setsAreaAndCopiesVoFields() {
        Area a = area(7);
        Date from = new Date(7_000L);
        Date to = new Date(8_000L);

        FiringPeriodVo vo = new FiringPeriodVo();
        vo.setId(55);
        vo.setLegacyId("LEG-" + System.nanoTime());
        vo.setFromDate(from);
        vo.setToDate(to);

        FiringPeriod p = new FiringPeriod(a, vo);

        assertSame(a, p.getArea());
        assertEquals(Integer.valueOf(55), p.getId());
        assertEquals(vo.getLegacyId(), p.getLegacyId());
        assertSame(from, p.getFromDate());
        assertSame(to, p.getToDate());
    }

    @Test
    public void constructorWithoutArea_leavesAreaNullAndCopiesVoFields() {
        Date from = new Date(9_000L);
        Date to = new Date(10_000L);

        FiringPeriodVo vo = new FiringPeriodVo();
        vo.setId(66);
        vo.setLegacyId("LEG-" + System.nanoTime());
        vo.setFromDate(from);
        vo.setToDate(to);

        FiringPeriod p = new FiringPeriod(vo);

        assertNull(p.getArea());
        assertEquals(Integer.valueOf(66), p.getId());
        assertEquals(vo.getLegacyId(), p.getLegacyId());
        assertSame(from, p.getFromDate());
        assertSame(to, p.getToDate());
    }
}
