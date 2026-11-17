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
package org.niord.core.chart;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.geojson.JtsConverter;
import org.niord.test.ITTest;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ChartService} persistence and spatial-search behaviour, run against the
 * seeded MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the shared
 * seed database pristine and the tests independent. Test charts use an {@code IT-} chart-number prefix
 * that does not occur in the seeded Danish charts, so lookups and searches stay isolated from seed data.
 *
 * <p>Spatial tests build geometries with {@link JtsConverter} (default SRID 0, matching the seeded
 * geometries) and exercise the MySQL {@code ST_Intersects} predicate used by
 * {@link ChartService#getIntersectingCharts(Geometry)}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class ChartServiceITTest extends ITTest {

    @Autowired
    ChartService chartService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached Chart template with the given chart number. */
    private Chart newChart(String chartNumber) {
        Chart chart = new Chart();
        chart.setChartNumber(chartNumber);
        return chart;
    }

    // ------------------------------------------------------------------------------------------- //
    // createChart
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createChartPersistsRowWithGeneratedIdAndActiveDefault() {
        Chart template = newChart("IT-CREATE-1");
        template.setInternationalNumber(9101);
        template.setName("IT Create Chart");
        template.setScale(50000);
        template.setHorizontalDatum("WGS84");

        Chart created = chartService.createChart(template);
        Integer id = created.getId();
        assertNotNull(id, "createChart must assign a generated id");

        em.flush();
        em.clear();

        Chart reloaded = em.find(Chart.class, id);
        assertNotNull(reloaded, "chart row must exist in the database");
        assertEquals("IT-CREATE-1", reloaded.getChartNumber());
        assertEquals(9101, reloaded.getInternationalNumber());
        assertEquals("IT Create Chart", reloaded.getName());
        assertEquals(50000, reloaded.getScale());
        assertEquals("WGS84", reloaded.getHorizontalDatum());
        assertTrue(reloaded.isActive(), "active must default to true");
    }

    @Test
    @Transactional
    void createChartPersistsInactiveFlag() {
        Chart template = newChart("IT-CREATE-INACTIVE");
        template.setActive(false);

        Chart created = chartService.createChart(template);
        em.flush();
        em.clear();

        Chart reloaded = em.find(Chart.class, created.getId());
        assertFalse(reloaded.isActive(), "the inactive flag must be persisted");
    }

    @Test
    @Transactional
    void createChartRejectsDuplicateChartNumber() {
        chartService.createChart(newChart("IT-DUP"));
        em.flush();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chartService.createChart(newChart("IT-DUP")));
        assertTrue(ex.getMessage().contains("IT-DUP"),
                "the exception message should name the duplicate chart number");
    }

    @Test
    @Transactional
    void createChartPersistsGeometry() {
        Chart template = newChart("IT-CREATE-GEOM");
        template.setGeometry(JtsConverter.toJtsExtent(55.0, 10.0, 57.0, 12.0));

        Chart created = chartService.createChart(template);
        em.flush();
        em.clear();

        Chart reloaded = em.find(Chart.class, created.getId());
        assertNotNull(reloaded.getGeometry(), "the geometry must be persisted and reloaded");
    }

    // ------------------------------------------------------------------------------------------- //
    // findByChartNumber / findByChartNumbers / findByLegacyId
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByChartNumberMatchesExactlyAndReturnsNullForUnknown() {
        Chart created = chartService.createChart(newChart("IT-FIND"));
        em.flush();

        assertEquals(created.getId(), chartService.findByChartNumber("IT-FIND").getId());
        assertNull(chartService.findByChartNumber("IT-FIND-UNKNOWN"),
                "an unknown chart number must return null");
    }

    @Test
    @Transactional
    void findByChartNumbersReturnsOnlyMatchingCharts() {
        chartService.createChart(newChart("IT-MULTI-A"));
        chartService.createChart(newChart("IT-MULTI-B"));
        em.flush();

        List<String> numbers = chartService
                .findByChartNumbers("IT-MULTI-A", "IT-MULTI-B", "IT-MULTI-MISSING")
                .stream()
                .map(Chart::getChartNumber)
                .collect(Collectors.toList());

        assertEquals(2, numbers.size(), "only the two existing charts must be returned");
        assertTrue(numbers.contains("IT-MULTI-A"));
        assertTrue(numbers.contains("IT-MULTI-B"));
        assertFalse(numbers.contains("IT-MULTI-MISSING"));
    }

    @Test
    @Transactional
    void findByLegacyIdMatchesAndReturnsNullForUnknown() {
        Chart template = newChart("IT-LEGACY");
        template.setLegacyId("IT-LEG-777");
        Chart created = chartService.createChart(template);
        em.flush();

        assertEquals(created.getId(), chartService.findByLegacyId("IT-LEG-777").getId());
        assertNull(chartService.findByLegacyId("IT-LEG-NONE"),
                "an unknown legacy id must return null");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchCharts
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchChartsMatchesByChartNumberSubstring() {
        chartService.createChart(newChart("IT-SEARCH-NUM-1"));
        chartService.createChart(newChart("IT-SEARCH-NUM-2"));
        em.flush();

        List<Chart> result = chartService.searchCharts("IT-SEARCH-NUM", false, 100);
        assertEquals(2, result.size(), "both charts whose number contains the term must match");
    }

    @Test
    @Transactional
    void searchChartsMatchesByNameCaseInsensitively() {
        Chart template = newChart("IT-SEARCH-NAME");
        template.setName("IT Unique Searchable Name");
        chartService.createChart(template);
        em.flush();

        List<Chart> result = chartService.searchCharts("unique searchable", false, 100);
        assertEquals(1, result.size(), "name search must be case-insensitive and substring-based");
        assertEquals("IT-SEARCH-NAME", result.get(0).getChartNumber());
    }

    @Test
    @Transactional
    void searchChartsMatchesByInternationalNumber() {
        Chart template = newChart("IT-SEARCH-INT");
        template.setInternationalNumber(987654);
        chartService.createChart(template);
        em.flush();

        List<Chart> result = chartService.searchCharts("987654", false, 100);
        assertEquals(1, result.size(),
                "the international number must be searchable as a string");
        assertEquals("IT-SEARCH-INT", result.get(0).getChartNumber());
    }

    @Test
    @Transactional
    void searchChartsExcludesInactiveUnlessRequested() {
        Chart active = newChart("IT-SEARCH-ACTIVE");
        chartService.createChart(active);
        Chart inactive = newChart("IT-SEARCH-INACTIVE");
        inactive.setActive(false);
        chartService.createChart(inactive);
        em.flush();

        // inactive=false: only the active chart whose number contains the shared term matches.
        List<Chart> activeOnly = chartService.searchCharts("IT-SEARCH-", false, 100);
        List<String> activeNumbers = activeOnly.stream()
                .map(Chart::getChartNumber)
                .collect(Collectors.toList());
        assertTrue(activeNumbers.contains("IT-SEARCH-ACTIVE"));
        assertFalse(activeNumbers.contains("IT-SEARCH-INACTIVE"),
                "inactive charts must be excluded when inactive=false");

        // inactive=true: both charts match.
        List<String> bothNumbers = chartService.searchCharts("IT-SEARCH-", true, 100).stream()
                .map(Chart::getChartNumber)
                .collect(Collectors.toList());
        assertTrue(bothNumbers.contains("IT-SEARCH-ACTIVE"));
        assertTrue(bothNumbers.contains("IT-SEARCH-INACTIVE"),
                "inactive charts must be included when inactive=true");
    }

    @Test
    @Transactional
    void searchChartsRespectsLimit() {
        chartService.createChart(newChart("IT-LIMIT-1"));
        chartService.createChart(newChart("IT-LIMIT-2"));
        chartService.createChart(newChart("IT-LIMIT-3"));
        em.flush();

        assertEquals(2, chartService.searchCharts("IT-LIMIT-", false, 2).size(),
                "the result must be capped at the requested limit");
    }

    @Test
    @Transactional
    void searchChartsReturnsEmptyForBlankTerm() {
        chartService.createChart(newChart("IT-BLANK-TERM"));
        em.flush();

        assertTrue(chartService.searchCharts("", false, 100).isEmpty(),
                "a blank term must short-circuit to an empty result");
        assertTrue(chartService.searchCharts("   ", false, 100).isEmpty(),
                "a whitespace-only term must short-circuit to an empty result");
        assertTrue(chartService.searchCharts(null, false, 100).isEmpty(),
                "a null term must short-circuit to an empty result");
    }

    @Test
    @Transactional
    void searchChartsOrdersChartNumberMatchAboveNameMatch() {
        // A chart whose NUMBER contains the term and another whose NAME contains the term.
        chartService.createChart(newChart("IT-RANK-TERMX"));
        Chart byName = newChart("IT-RANK-OTHER");
        byName.setName("contains TERMX in the name");
        chartService.createChart(byName);
        em.flush();

        List<Chart> result = chartService.searchCharts("TERMX", false, 100);
        assertEquals(2, result.size());
        // computeSearchSortKey ranks a chart-number match above a name match.
        assertEquals("IT-RANK-TERMX", result.get(0).getChartNumber(),
                "a chart-number match must sort before a name-only match");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateChart
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateChartByTemplatePersistsAllCopiedFields() {
        chartService.createChart(newChart("IT-UPDATE"));
        em.flush();

        Chart template = newChart("IT-UPDATE");
        template.setInternationalNumber(4242);
        template.setActive(false);
        template.setHorizontalDatum("ED50");
        template.setName("Updated Name");
        template.setScale(123456);
        template.setGeometry(JtsConverter.toJtsExtent(50.0, 5.0, 52.0, 7.0));

        Chart updated = chartService.updateChart(template);
        Integer id = updated.getId();

        em.flush();
        em.clear();

        Chart reloaded = em.find(Chart.class, id);
        assertEquals(4242, reloaded.getInternationalNumber());
        assertFalse(reloaded.isActive());
        assertEquals("ED50", reloaded.getHorizontalDatum());
        assertEquals("Updated Name", reloaded.getName());
        assertEquals(123456, reloaded.getScale());
        assertNotNull(reloaded.getGeometry(), "the updated geometry must be persisted");
    }

    @Test
    @Transactional
    void updateChartRejectsUnknownChartNumber() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> chartService.updateChart(newChart("IT-UPDATE-MISSING")));
        assertTrue(ex.getMessage().contains("IT-UPDATE-MISSING"),
                "the exception message should name the missing chart number");
    }

    @Test
    @Transactional
    void updateChartDoesNotChangeIdentityOrCreateNewRow() {
        Chart created = chartService.createChart(newChart("IT-UPDATE-IDENTITY"));
        Integer originalId = created.getId();
        em.flush();

        Chart template = newChart("IT-UPDATE-IDENTITY");
        template.setName("Renamed");
        Chart updated = chartService.updateChart(template);

        assertEquals(originalId, updated.getId(),
                "updating must keep the same database row, not create a new one");

        em.flush();
        em.clear();
        assertEquals(1, chartService.findByChartNumbers("IT-UPDATE-IDENTITY").size(),
                "there must still be exactly one chart with this number");
    }

    // ------------------------------------------------------------------------------------------- //
    // checkUpdateChart
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void checkUpdateChartIsNoOpWhenNothingChanged() {
        Chart created = chartService.createChart(newChart("IT-NOCHANGE"));
        em.flush();

        Chart identical = newChart("IT-NOCHANGE");
        Chart result = chartService.checkUpdateChart(created, identical);

        assertSame(created, result,
                "an unchanged chart must be returned as-is, without performing an update");
    }

    @Test
    @Transactional
    void checkUpdateChartAppliesChangeWhenFieldDiffers() {
        Chart created = chartService.createChart(newChart("IT-CHECK-CHANGE"));
        Integer id = created.getId();
        em.flush();

        Chart template = newChart("IT-CHECK-CHANGE");
        template.setName("Newly set name");

        chartService.checkUpdateChart(created, template);
        em.flush();
        em.clear();

        assertEquals("Newly set name", em.find(Chart.class, id).getName(),
                "a differing field must trigger the update and be persisted");
    }

    // ------------------------------------------------------------------------------------------- //
    // importChart
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void importChartCreatesWhenMissingAndCreateEnabled() {
        Chart template = newChart("IT-IMPORT-NEW");
        template.setName("Imported");

        Chart imported = chartService.importChart(template, true, true);
        assertNotNull(imported, "import with create=true must create the missing chart");
        assertNotNull(imported.getId());

        em.flush();
        em.clear();
        assertEquals("Imported", em.find(Chart.class, imported.getId()).getName());
    }

    @Test
    @Transactional
    void importChartReturnsNullWhenMissingAndCreateDisabled() {
        Chart template = newChart("IT-IMPORT-NOCREATE");

        assertNull(chartService.importChart(template, false, false),
                "import with create=false must not create a missing chart");
        assertNull(chartService.findByChartNumber("IT-IMPORT-NOCREATE"),
                "no chart row must have been inserted");
    }

    @Test
    @Transactional
    void importChartUpdatesExistingWhenUpdateEnabled() {
        Chart existing = newChart("IT-IMPORT-UPD");
        existing.setName("Old");
        Chart created = chartService.createChart(existing);
        Integer id = created.getId();
        em.flush();

        Chart template = newChart("IT-IMPORT-UPD");
        template.setName("New");
        Chart imported = chartService.importChart(template, true, true);

        assertEquals(id, imported.getId(), "re-import must update the existing row");
        em.flush();
        em.clear();
        assertEquals("New", em.find(Chart.class, id).getName(),
                "the existing chart must be updated to the template's values");
    }

    @Test
    @Transactional
    void importChartLeavesExistingUnchangedWhenUpdateDisabled() {
        Chart existing = newChart("IT-IMPORT-NOUPD");
        existing.setName("Original");
        Chart created = chartService.createChart(existing);
        Integer id = created.getId();
        em.flush();

        Chart template = newChart("IT-IMPORT-NOUPD");
        template.setName("Should Be Ignored");
        Chart imported = chartService.importChart(template, true, false);

        assertEquals(id, imported.getId());
        em.flush();
        em.clear();
        assertEquals("Original", em.find(Chart.class, id).getName(),
                "with update=false the existing chart must keep its original values");
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteChart
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteChartRemovesRowAndReturnsTrue() {
        Chart created = chartService.createChart(newChart("IT-DELETE"));
        Integer id = created.getId();
        em.flush();

        assertTrue(chartService.deleteChart("IT-DELETE"),
                "deleting an existing chart must return true");
        em.flush();
        em.clear();

        assertNull(em.find(Chart.class, id), "the deleted chart row must be gone from the database");
        assertNull(chartService.findByChartNumber("IT-DELETE"));
    }

    @Test
    @Transactional
    void deleteChartReturnsFalseForUnknownChartNumber() {
        assertFalse(chartService.deleteChart("IT-DELETE-MISSING"),
                "deleting a non-existing chart must return false");
    }

    // ------------------------------------------------------------------------------------------- //
    // persistedCharts
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void persistedChartsReturnsOnlyThoseThatExist() {
        chartService.createChart(newChart("IT-PERSISTED-EXISTS"));
        em.flush();

        List<Chart> templates = List.of(
                newChart("IT-PERSISTED-EXISTS"),
                newChart("IT-PERSISTED-ABSENT"));

        List<Chart> persisted = chartService.persistedCharts(templates);
        assertEquals(1, persisted.size(), "only persisted charts must be returned");
        assertEquals("IT-PERSISTED-EXISTS", persisted.get(0).getChartNumber());
        assertNotNull(persisted.get(0).getId(),
                "the returned chart must be the managed/persisted instance with an id");
    }

    // ------------------------------------------------------------------------------------------- //
    // getCharts
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getChartsIncludesNewlyCreatedChart() {
        long before = chartService.getCharts().stream()
                .filter(c -> "IT-GETALL".equals(c.getChartNumber()))
                .count();
        assertEquals(0, before, "the test chart must not pre-exist in the seed data");

        chartService.createChart(newChart("IT-GETALL"));
        em.flush();

        long after = chartService.getCharts().stream()
                .filter(c -> "IT-GETALL".equals(c.getChartNumber()))
                .count();
        assertEquals(1, after, "getCharts must include the newly created chart");
    }

    // ------------------------------------------------------------------------------------------- //
    // getIntersectingCharts (spatial)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getIntersectingChartsReturnsChartWhosePolygonContainsThePoint() {
        Chart chart = newChart("IT-GEOM-HIT");
        // A box covering lon 10..12, lat 55..57.
        chart.setGeometry(JtsConverter.toJtsExtent(55.0, 10.0, 57.0, 12.0));
        chartService.createChart(chart);
        em.flush();

        // A point inside the box.
        Geometry inside = JtsConverter.toJtsPoint(56.0, 11.0);
        List<String> numbers = chartService.getIntersectingCharts(inside).stream()
                .map(Chart::getChartNumber)
                .collect(Collectors.toList());
        assertTrue(numbers.contains("IT-GEOM-HIT"),
                "a chart whose polygon contains the query point must be returned");
    }

    @Test
    @Transactional
    void getIntersectingChartsExcludesChartWhosePolygonDoesNotContainThePoint() {
        Chart chart = newChart("IT-GEOM-MISS");
        chart.setGeometry(JtsConverter.toJtsExtent(55.0, 10.0, 57.0, 12.0));
        chartService.createChart(chart);
        em.flush();

        // A point far outside the box (off the coast of West Africa).
        Geometry outside = JtsConverter.toJtsPoint(0.0, 0.0);
        List<String> numbers = chartService.getIntersectingCharts(outside).stream()
                .map(Chart::getChartNumber)
                .collect(Collectors.toList());
        assertFalse(numbers.contains("IT-GEOM-MISS"),
                "a chart whose polygon does not contain the query point must be excluded");
    }

    @Test
    @Transactional
    void getIntersectingChartsExcludesInactiveCharts() {
        Chart chart = newChart("IT-GEOM-INACTIVE");
        chart.setGeometry(JtsConverter.toJtsExtent(55.0, 10.0, 57.0, 12.0));
        chart.setActive(false);
        chartService.createChart(chart);
        em.flush();

        Geometry inside = JtsConverter.toJtsPoint(56.0, 11.0);
        List<String> numbers = chartService.getIntersectingCharts(inside).stream()
                .map(Chart::getChartNumber)
                .collect(Collectors.toList());
        assertFalse(numbers.contains("IT-GEOM-INACTIVE"),
                "inactive charts must never be returned, even when they intersect");
    }

    @Test
    @Transactional
    void getIntersectingChartsOrdersResultsByAscendingScale() {
        // Two overlapping active charts with different scales; the smaller scale must come first.
        Chart coarse = newChart("IT-GEOM-ORDER-COARSE");
        coarse.setGeometry(JtsConverter.toJtsExtent(55.0, 10.0, 57.0, 12.0));
        coarse.setScale(500000);
        chartService.createChart(coarse);

        Chart fine = newChart("IT-GEOM-ORDER-FINE");
        fine.setGeometry(JtsConverter.toJtsExtent(55.0, 10.0, 57.0, 12.0));
        fine.setScale(50000);
        chartService.createChart(fine);
        em.flush();

        Geometry inside = JtsConverter.toJtsPoint(56.0, 11.0);
        List<String> ordered = chartService.getIntersectingCharts(inside).stream()
                .map(Chart::getChartNumber)
                .filter(n -> n.startsWith("IT-GEOM-ORDER-"))
                .collect(Collectors.toList());

        assertEquals(List.of("IT-GEOM-ORDER-FINE", "IT-GEOM-ORDER-COARSE"), ordered,
                "intersecting charts must be ordered by ascending scale");
    }
}
