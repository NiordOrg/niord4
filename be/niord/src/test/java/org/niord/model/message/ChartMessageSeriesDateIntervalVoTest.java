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
package org.niord.model.message;

import org.junit.jupiter.api.Test;
import org.niord.model.DataFilter;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the three non-localizable message value objects:
 * {@link ChartVo}, {@link MessageSeriesVo} and {@link DateIntervalVo}.
 *
 * These tests target the REST/JSON value-object contract and the
 * {@link DataFilter}-driven {@code copy(...)} behaviour. They are
 * framework-neutral plain JUnit 4 tests (no Quarkus, no mocks) and assert
 * concrete copied state (including that copies are distinct instances and
 * that filter-excluded fields stay null), strict-bounds interval logic and
 * the formatted chart number, so they survive a future Spring port.
 *
 * Dates are built from fixed epoch millis so the tests are deterministic and
 * independent of clock, locale or timezone, and each test builds its own
 * objects so they can run alone or in any order.
 *
 * The test lives in the {@code org.niord.model.message} package so the
 * package-private scalar fields remain reachable; the tests use the public
 * accessors.
 */
public class ChartMessageSeriesDateIntervalVoTest {

    // =====================================================================
    // ChartVo
    // =====================================================================

    /** The 'active' flag defaults to true on a freshly constructed chart. */
    @Test
    public void chartActiveDefaultsToTrue() {
        ChartVo chart = new ChartVo();
        assertTrue(chart.isActive(), "active should default to true");
        // All other scalar fields default to null.
        assertNull(chart.getChartNumber());
        assertNull(chart.getInternationalNumber());
        assertNull(chart.getScale());
        assertNull(chart.getName());
    }

    /**
     * A minimal filter (no DETAILS) copies chartNumber, internationalNumber,
     * active and scale, but leaves name null because DETAILS is required.
     */
    @Test
    public void chartCopyMinimalFilterExcludesName() {
        ChartVo chart = new ChartVo();
        chart.setChartNumber("DK 101");
        chart.setInternationalNumber(5);
        chart.setActive(false);
        chart.setScale(50000);
        chart.setName("Some chart name");

        ChartVo copy = chart.copy(DataFilter.get());

        assertNotSame(chart, copy, "copy must be a distinct instance");
        assertEquals("DK 101", copy.getChartNumber());
        assertEquals(Integer.valueOf(5), copy.getInternationalNumber());
        assertFalse(copy.isActive(), "active should be carried over verbatim");
        assertEquals(Integer.valueOf(50000), copy.getScale());
        assertNull(copy.getName(), "name must be excluded without DETAILS");
    }

    /**
     * With the DETAILS field present, name is also copied (in addition to the
     * always-copied fields).
     */
    @Test
    public void chartCopyWithDetailsIncludesName() {
        ChartVo chart = new ChartVo();
        chart.setChartNumber("DK 101");
        chart.setInternationalNumber(5);
        chart.setActive(true);
        chart.setScale(50000);
        chart.setName("Some chart name");

        ChartVo copy = chart.copy(DataFilter.get().fields(DataFilter.DETAILS));

        assertNotSame(chart, copy);
        assertEquals("DK 101", copy.getChartNumber());
        assertEquals(Integer.valueOf(5), copy.getInternationalNumber());
        assertTrue(copy.isActive());
        assertEquals(Integer.valueOf(50000), copy.getScale());
        assertEquals("Some chart name", copy.getName(), "name must be included with DETAILS");
    }

    /**
     * The "Chart" component prefix is stripped by forComponent, so a
     * "Chart.details" field also enables the name copy.
     */
    @Test
    public void chartCopyWithComponentPrefixedDetailsIncludesName() {
        ChartVo chart = new ChartVo();
        chart.setChartNumber("DK 200");
        chart.setName("Prefixed name");

        ChartVo copy = chart.copy(DataFilter.get().fields("Chart." + DataFilter.DETAILS));

        assertEquals("DK 200", copy.getChartNumber());
        assertEquals("Prefixed name", copy.getName());
    }

    /**
     * A DETAILS field aimed at a DIFFERENT component ("Foo.details") is not
     * stripped for "Chart" and does not enable the name copy.
     */
    @Test
    public void chartCopyWithOtherComponentDetailsExcludesName() {
        ChartVo chart = new ChartVo();
        chart.setChartNumber("DK 300");
        chart.setName("Other name");

        ChartVo copy = chart.copy(DataFilter.get().fields("Foo." + DataFilter.DETAILS));

        assertEquals("DK 300", copy.getChartNumber());
        assertNull(copy.getName(), "name must stay null for a foreign-component details field");
    }

    /** With internationalNumber null, toFullChartNumber returns the chart number verbatim. */
    @Test
    public void toFullChartNumberWithoutInternationalNumber() {
        ChartVo chart = new ChartVo();
        chart.setChartNumber("DK 101");
        assertEquals("DK 101", chart.toFullChartNumber());
    }

    /** With internationalNumber set, toFullChartNumber appends " (INT n)". */
    @Test
    public void toFullChartNumberWithInternationalNumber() {
        ChartVo chart = new ChartVo();
        chart.setChartNumber("DK 101");
        chart.setInternationalNumber(5);
        assertEquals("DK 101 (INT 5)", chart.toFullChartNumber());
    }

    /**
     * Pins the surprising behaviour that toFullChartNumber does not guard
     * against a null chartNumber: with a null chartNumber and null
     * internationalNumber it simply returns null.
     */
    @Test
    public void toFullChartNumberWithNullChartNumberReturnsNull_documentsBehavior() {
        ChartVo chart = new ChartVo();
        assertNull(chart.toFullChartNumber());
    }

    // =====================================================================
    // MessageSeriesVo
    // =====================================================================

    /**
     * A minimal filter copies the seriesId but leaves mainType null because
     * mainType requires DETAILS.
     */
    @Test
    public void messageSeriesCopyMinimalFilterExcludesMainType() {
        MessageSeriesVo series = new MessageSeriesVo();
        series.setSeriesId("dma-nw");
        series.setMainType(MainType.NW);

        MessageSeriesVo copy = series.copy(DataFilter.get());

        assertNotSame(series, copy, "copy must be a distinct instance");
        assertEquals("dma-nw", copy.getSeriesId());
        assertNull(copy.getMainType(), "mainType must be excluded without DETAILS");
    }

    /** With DETAILS, mainType is also copied. */
    @Test
    public void messageSeriesCopyWithDetailsIncludesMainType() {
        MessageSeriesVo series = new MessageSeriesVo();
        series.setSeriesId("dma-nm");
        series.setMainType(MainType.NM);

        MessageSeriesVo copy = series.copy(DataFilter.get().fields(DataFilter.DETAILS));

        assertNotSame(series, copy);
        assertEquals("dma-nm", copy.getSeriesId());
        assertEquals(MainType.NM, copy.getMainType(), "mainType must be included with DETAILS");
    }

    /**
     * The "MessageSeries" component prefix is stripped so a
     * "MessageSeries.details" field also enables the mainType copy.
     */
    @Test
    public void messageSeriesCopyWithComponentPrefixedDetailsIncludesMainType() {
        MessageSeriesVo series = new MessageSeriesVo();
        series.setSeriesId("dma-nw");
        series.setMainType(MainType.NW);

        MessageSeriesVo copy = series.copy(
                DataFilter.get().fields("MessageSeries." + DataFilter.DETAILS));

        assertEquals("dma-nw", copy.getSeriesId());
        assertEquals(MainType.NW, copy.getMainType());
    }

    /** Copying a series whose mainType is null leaves the copy's mainType null even with DETAILS. */
    @Test
    public void messageSeriesCopyWithNullMainType() {
        MessageSeriesVo series = new MessageSeriesVo();
        series.setSeriesId("dma-nw");

        MessageSeriesVo copy = series.copy(DataFilter.get().fields(DataFilter.DETAILS));

        assertEquals("dma-nw", copy.getSeriesId());
        assertNull(copy.getMainType());
    }

    // =====================================================================
    // DateIntervalVo
    // =====================================================================

    /** copy() returns a new, distinct instance with field-equal allDay/fromDate/toDate. */
    @Test
    public void dateIntervalCopyIsDistinctWithEqualValues() {
        DateIntervalVo di = new DateIntervalVo();
        di.setAllDay(Boolean.TRUE);
        di.setFromDate(new Date(1_000_000L));
        di.setToDate(new Date(2_000_000L));

        DateIntervalVo copy = di.copy();

        assertNotSame(di, copy, "copy must be a distinct instance");
        assertEquals(Boolean.TRUE, copy.getAllDay());
        assertEquals(new Date(1_000_000L), copy.getFromDate());
        assertEquals(new Date(2_000_000L), copy.getToDate());
    }

    /** copy() of an empty interval yields a distinct instance with all-null fields. */
    @Test
    public void dateIntervalCopyOfEmptyInterval() {
        DateIntervalVo di = new DateIntervalVo();

        DateIntervalVo copy = di.copy();

        assertNotSame(di, copy);
        assertNull(copy.getAllDay());
        assertNull(copy.getFromDate());
        assertNull(copy.getToDate());
    }

    /**
     * With both bounds set, containsDate uses STRICT (exclusive) bounds: a
     * date strictly between from and to is contained.
     */
    @Test
    public void containsDateBothBoundsStrictlyInside() {
        DateIntervalVo di = new DateIntervalVo();
        di.setFromDate(new Date(1000));
        di.setToDate(new Date(3000));

        assertTrue(di.containsDate(new Date(2000)));
    }

    /** The fromDate boundary itself is NOT contained (exclusive lower bound). */
    @Test
    public void containsDateEqualToFromDateIsExcluded() {
        DateIntervalVo di = new DateIntervalVo();
        di.setFromDate(new Date(1000));
        di.setToDate(new Date(3000));

        assertFalse(di.containsDate(new Date(1000)));
    }

    /** The toDate boundary itself is NOT contained (exclusive upper bound). */
    @Test
    public void containsDateEqualToToDateIsExcluded() {
        DateIntervalVo di = new DateIntervalVo();
        di.setFromDate(new Date(1000));
        di.setToDate(new Date(3000));

        assertFalse(di.containsDate(new Date(3000)));
    }

    /** A date before the lower bound is not contained. */
    @Test
    public void containsDateBeforeLowerBoundExcluded() {
        DateIntervalVo di = new DateIntervalVo();
        di.setFromDate(new Date(1000));
        di.setToDate(new Date(3000));

        assertFalse(di.containsDate(new Date(500)));
    }

    /** A date after the upper bound is not contained. */
    @Test
    public void containsDateAfterUpperBoundExcluded() {
        DateIntervalVo di = new DateIntervalVo();
        di.setFromDate(new Date(1000));
        di.setToDate(new Date(3000));

        assertFalse(di.containsDate(new Date(4000)));
    }

    /** With only fromDate set, any strictly-later date is contained and earlier dates are not. */
    @Test
    public void containsDateOnlyFromDate() {
        DateIntervalVo di = new DateIntervalVo();
        di.setFromDate(new Date(1000));

        assertTrue(di.containsDate(new Date(2000)), "a date after fromDate is contained");
        assertFalse(di.containsDate(new Date(500)), "a date before fromDate is excluded");
        assertFalse(di.containsDate(new Date(1000)), "a date equal to fromDate is excluded (strict)");
    }

    /** With only toDate set, any strictly-earlier date is contained and later dates are not. */
    @Test
    public void containsDateOnlyToDate() {
        DateIntervalVo di = new DateIntervalVo();
        di.setToDate(new Date(3000));

        assertTrue(di.containsDate(new Date(2000)), "a date before toDate is contained");
        assertFalse(di.containsDate(new Date(4000)), "a date after toDate is excluded");
        assertFalse(di.containsDate(new Date(3000)), "a date equal to toDate is excluded (strict)");
    }

    /** With neither bound set, no date is ever contained. */
    @Test
    public void containsDateWithBothBoundsNullReturnsFalse() {
        DateIntervalVo di = new DateIntervalVo();

        assertFalse(di.containsDate(new Date(2000)));
        assertFalse(di.containsDate(new Date(0)));
    }

    /**
     * Edge case: with no bounds set, containsDate returns false even for a
     * null date argument (the method never dereferences it in this branch).
     */
    @Test
    public void containsDateNullArgumentWithNoBoundsReturnsFalse() {
        DateIntervalVo di = new DateIntervalVo();
        assertFalse(di.containsDate(null));
    }

    /**
     * Pins the surprising behaviour that containsDate does not null-check its
     * argument: when a bound is set, a null date dereferences inside
     * Date.after / Date.before and throws NullPointerException.
     */
    @Test
    public void containsDateNullArgumentWithBoundThrows_documentsBehavior() {
        DateIntervalVo di = new DateIntervalVo();
        di.setFromDate(new Date(1000));
        assertThrows(NullPointerException.class, () -> di.containsDate(null));
    }
}
