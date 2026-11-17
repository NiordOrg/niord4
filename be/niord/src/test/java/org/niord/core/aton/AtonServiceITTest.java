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
package org.niord.core.aton;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.area.Area;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.aton.vo.AtonTagVo;
import org.niord.core.chart.Chart;
import org.niord.core.geojson.JtsConverter;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link AtonService}, the AtoN (Aid-to-Navigation) persistence and query
 * service. These tests focus on behaviour <em>not</em> already covered by
 * {@link org.niord.core.db.SpatialPredicateITTest} (which exercises the extent-based spatial
 * predicate via {@link AtonService#search}). In particular they cover:
 *
 * <ul>
 *   <li>{@link AtonService#updateAtons(List)} — the merge-persistence routine: create / update /
 *       unchanged branches driven by {@link AtonNode#hasChanged} and {@link AtonNode#updateNode};</li>
 *   <li>the tag lookup family: {@code findByTag}, {@code findByTagValues}, {@code findByAtonUid},
 *       {@code findByAtonUids};</li>
 *   <li>name search ({@code AtonService.search} with a {@code name} param — a case-insensitive,
 *       accent-folded {@code LIKE} on tag values) including the {@code emptyOnOverflow} short-circuit;</li>
 *   <li>{@code searchPositions} by extent (returns {@code {lon,lat}} arrays);</li>
 *   <li>{@code search} by chart and by area (the {@code ST_Within} join against a chart/area geometry).</li>
 * </ul>
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the
 * shared seed database pristine and the tests order-independent. All AtoN rows are built through
 * {@link AtonNode}'s {@code @PrePersist} hook (which stores the geometry as the SRID-0 point
 * {@code JtsConverter.toJtsPoint(lat, lon)}); chart/area geometries are built with
 * {@link JtsConverter#toJtsExtent} so they share the same SRID-0 {@code GeometryFactory} (MySQL's
 * {@code ST_Within} requires matching SRIDs).
 *
 * <p>Because the {@code search} / {@code searchPositions} / tag queries hit the whole shared table,
 * every test embeds a globally-unique token in {@code seamark:ref} and {@code seamark:name} and then
 * restricts its assertions to the ids/refs it created (mirroring {@code SpatialPredicateITTest}'s
 * "filter to mine" approach), so unrelated seed rows can never affect the result.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class AtonServiceITTest extends ITTest {

    /** Generates unique OSM uids so tests never collide with each other or with seed data. */
    private static final AtomicInteger UID_SEQ = new AtomicInteger(800000);

    /** A globally-unique alphanumeric prefix; each test appends UID_SEQ values for full uniqueness. */
    private static final String PFX = "ATONSVCIT" + Long.toHexString(System.nanoTime()).toUpperCase();

    /** The Denmark query box that contains (55,11) and excludes (0,0) and (80,80). */
    private static final double BOX_MIN_LAT = 54.0;
    private static final double BOX_MIN_LON = 7.0;
    private static final double BOX_MAX_LAT = 57.0;
    private static final double BOX_MAX_LON = 13.0;

    @PersistenceContext
    EntityManager em;

    @Autowired
    AtonService atonService;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Returns a globally-unique token, e.g. for use in seamark:ref / seamark:name. */
    private String unique(String role) {
        return PFX + role + UID_SEQ.incrementAndGet();
    }

    /**
     * Builds a detached {@link AtonNode} at the given position carrying the supplied seamark:ref and
     * seamark:name plus a fixed seamark:type tag. The node is <em>not</em> persisted.
     */
    private AtonNode buildNode(double lat, double lon, String ref, String name) {
        AtonNodeVo vo = new AtonNodeVo();
        vo.setLat(lat);
        vo.setLon(lon);
        vo.setVisible(true);
        vo.setUid(UID_SEQ.incrementAndGet());
        vo.setVersion(1);
        vo.setTags(new AtonTagVo[] {
                new AtonTagVo("seamark:ref", ref),
                new AtonTagVo("seamark:name", name),
                new AtonTagVo("seamark:type", "buoy_cardinal")
        });
        return new AtonNode(vo);
    }

    /** Builds and persists (+flush) an {@link AtonNode}; after flush its geometry is the SRID-0 point. */
    private AtonNode persistNode(double lat, double lon, String ref, String name) {
        AtonNode node = buildNode(lat, lon, ref, name);
        em.persist(node);
        em.flush();
        return node;
    }

    /** Persists (+flush) a {@link Chart} with a box geometry and a unique chart number. */
    private Chart persistChart(String chartNumber, Geometry box) {
        Chart chart = new Chart();
        chart.setChartNumber(chartNumber);
        chart.setGeometry(box);
        em.persist(chart);
        em.flush();
        return chart;
    }

    /** Persists (+flush) an {@link Area} with a box geometry. */
    private Area persistArea(Geometry box) {
        Area area = new Area();
        area.setGeometry(box);
        em.persist(area);
        em.flush();
        return area;
    }

    // ------------------------------------------------------------------------------------------- //
    // updateAtons - CREATE branch
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateAtonsCreatesBrandNewNodes() {
        String refA = unique("REFA");
        String refB = unique("REFB");
        String nameA = unique("NAMEA");
        String nameB = unique("NAMEB");

        AtonNode a = buildNode(55.0, 11.0, refA, nameA);
        AtonNode b = buildNode(56.0, 12.0, refB, nameB);

        atonService.updateAtons(List.of(a, b));
        em.flush();
        em.clear();

        AtonNode reloadedA = atonService.findByAtonUid(refA);
        AtonNode reloadedB = atonService.findByAtonUid(refB);

        assertNotNull(reloadedA, "updateAtons must persist a brand-new AtoN for ref " + refA);
        assertNotNull(reloadedB, "updateAtons must persist a brand-new AtoN for ref " + refB);
        assertEquals(55.0, reloadedA.getLat(), 0.000001,
                "the persisted latitude must match the supplied template");
        assertEquals(11.0, reloadedA.getLon(), 0.000001,
                "the persisted longitude must match the supplied template");
        assertEquals(nameA, reloadedA.getTagValue("seamark:name"),
                "the seamark:name tag must be persisted as supplied");
        assertEquals("buoy_cardinal", reloadedB.getTagValue("seamark:type"),
                "the seamark:type tag must be persisted as supplied");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateAtons - UPDATE branch (orig.hasChanged true -> orig.updateNode applied, no duplicate row)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateAtonsUpdatesExistingNodeInPlace() {
        String ref = unique("REF");
        String origName = unique("ORIGNAME");
        String newName = unique("NEWNAME");

        // Pre-existing row.
        AtonNode original = persistNode(55.0, 11.0, ref, origName);
        Integer originalId = original.getId();

        // Template with the SAME ref but a changed lat (delta well above 0.00001) and a changed name tag.
        AtonNode template = buildNode(55.5, 11.0, ref, newName);

        atonService.updateAtons(List.of(template));
        em.flush();
        em.clear();

        AtonNode reloaded = atonService.findByAtonUid(ref);
        assertNotNull(reloaded, "the existing AtoN must still be present after an in-place update");
        assertEquals(originalId, reloaded.getId(),
                "an update must mutate the existing row, not create a new one");
        assertEquals(55.5, reloaded.getLat(), 0.000001,
                "updateNode must apply the template latitude");
        assertEquals(newName, reloaded.getTagValue("seamark:name"),
                "updateNode must apply the changed seamark:name tag value");

        // No duplicate row was created for this ref.
        List<AtonNode> byRef = atonService.findByTag("seamark:ref", ref);
        assertEquals(1, byRef.size(),
                "an update must not create a duplicate AtoN row for the same ref");
    }

    @Test
    @Transactional
    void updateAtonsAddsNewTagToExistingNode() {
        String ref = unique("REF");
        String name = unique("NAME");

        AtonNode original = persistNode(55.0, 11.0, ref, name);
        assertNull(original.getTagValue("seamark:light:colour"),
                "precondition: the original node has no light-colour tag");

        // Same ref/lat/lon/name, but an extra tag => hasChanged() is true (tag value differs from null).
        AtonNode template = buildNode(55.0, 11.0, ref, name);
        template.updateTag("seamark:light:colour", "white");

        atonService.updateAtons(List.of(template));
        em.flush();
        em.clear();

        AtonNode reloaded = atonService.findByAtonUid(ref);
        assertEquals("white", reloaded.getTagValue("seamark:light:colour"),
                "updateNode must add a tag that is present in the template but not in the original");
        assertEquals(name, reloaded.getTagValue("seamark:name"),
                "an unrelated existing tag must be left unchanged by the merge");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateAtons - UNCHANGED branch (orig.hasChanged false -> nothing happens)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateAtonsLeavesIdenticalNodeUntouched() {
        String ref = unique("REF");
        String name = unique("NAME");

        AtonNode original = persistNode(55.0, 11.0, ref, name);
        Integer originalId = original.getId();

        // Identical content: same ref/lat/lon/visible/tags => hasChanged() returns false.
        AtonNode identical = buildNode(55.0, 11.0, ref, name);

        atonService.updateAtons(List.of(identical));
        em.flush();
        em.clear();

        List<AtonNode> byRef = atonService.findByTag("seamark:ref", ref);
        assertEquals(1, byRef.size(),
                "an unchanged template must neither duplicate nor remove the existing row");
        AtonNode reloaded = byRef.get(0);
        assertEquals(originalId, reloaded.getId(),
                "the same physical row must remain");
        assertEquals(55.0, reloaded.getLat(), 0.000001,
                "an unchanged update must leave the latitude as it was");
        assertEquals(name, reloaded.getTagValue("seamark:name"),
                "an unchanged update must leave the name tag as it was");
    }

    @Test
    @Transactional
    void updateAtonsTreatsSubThresholdLatChangeAsUnchanged() {
        String ref = unique("REF");
        String name = unique("NAME");

        AtonNode original = persistNode(55.0, 11.0, ref, name);
        Integer originalId = original.getId();

        // A latitude delta below the 0.00001 hasChanged() threshold must be ignored.
        AtonNode template = buildNode(55.000001, 11.0, ref, name);

        atonService.updateAtons(List.of(template));
        em.flush();
        em.clear();

        AtonNode reloaded = atonService.findByAtonUid(ref);
        assertEquals(originalId, reloaded.getId(), "the same row must remain");
        assertEquals(55.0, reloaded.getLat(), 0.0000001,
                "a sub-threshold latitude change must NOT be applied (hasChanged returns false)");
    }

    // ------------------------------------------------------------------------------------------- //
    // findByTag / findByTagValues
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByTagReturnsExactKeyValueMatch() {
        String name = unique("NAME");
        AtonNode node = persistNode(55.0, 11.0, unique("REF"), name);
        em.flush();
        em.clear();

        List<AtonNode> result = atonService.findByTag("seamark:name", name);
        assertEquals(1, result.size(),
                "findByTag must return exactly the one node carrying that key+value");
        assertEquals(node.getId(), result.get(0).getId(),
                "findByTag must return the matching node");
    }

    @Test
    @Transactional
    void findByTagReturnsEmptyWhenNoMatch() {
        persistNode(55.0, 11.0, unique("REF"), unique("NAME"));
        em.flush();
        em.clear();

        List<AtonNode> result = atonService.findByTag("seamark:name", unique("ABSENT"));
        assertTrue(result.isEmpty(),
                "findByTag must return an empty list when no node carries that value");
    }

    @Test
    @Transactional
    void findByTagValuesReturnsAllNodesWhoseValueIsInTheSet() {
        String name1 = unique("NAME");
        String name2 = unique("NAME");
        String name3 = unique("NAME");
        AtonNode n1 = persistNode(55.0, 11.0, unique("REF"), name1);
        AtonNode n2 = persistNode(56.0, 12.0, unique("REF"), name2);
        AtonNode n3 = persistNode(54.5, 10.0, unique("REF"), name3);
        em.flush();
        em.clear();

        Set<Integer> matched = atonService.findByTagValues("seamark:name", name1, name2).stream()
                .map(AtonNode::getId)
                .collect(Collectors.toSet());

        assertEquals(Set.of(n1.getId(), n2.getId()), matched,
                "findByTagValues must return exactly the nodes whose value is in the supplied set");
        assertFalse(matched.contains(n3.getId()),
                "a node whose value is not in the set must be excluded");
    }

    // ------------------------------------------------------------------------------------------- //
    // findByAtonUid / findByAtonUids
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByAtonUidReturnsTheNodeWithThatRef() {
        String ref = unique("REF");
        AtonNode node = persistNode(55.0, 11.0, ref, unique("NAME"));
        em.flush();
        em.clear();

        AtonNode found = atonService.findByAtonUid(ref);
        assertNotNull(found, "findByAtonUid must find a node whose seamark:ref matches");
        assertEquals(node.getId(), found.getId(),
                "findByAtonUid must return the node carrying that seamark:ref");
        assertEquals(ref, found.getAtonUid(),
                "the returned node's AtoN UID must equal the requested ref");
    }

    @Test
    @Transactional
    void findByAtonUidReturnsNullWhenAbsent() {
        persistNode(55.0, 11.0, unique("REF"), unique("NAME"));
        em.flush();
        em.clear();

        AtonNode found = atonService.findByAtonUid(unique("MISSING"));
        assertNull(found, "findByAtonUid must return null when no node has that seamark:ref");
    }

    @Test
    @Transactional
    void findByAtonUidsReturnsNodesForTheRequestedRefs() {
        String refA = unique("REF");
        String refB = unique("REF");
        String refC = unique("REF");
        AtonNode a = persistNode(55.0, 11.0, refA, unique("NAME"));
        AtonNode b = persistNode(56.0, 12.0, refB, unique("NAME"));
        AtonNode c = persistNode(54.5, 10.0, refC, unique("NAME"));
        em.flush();
        em.clear();

        Set<Integer> matched = atonService.findByAtonUids(refA, refB).stream()
                .map(AtonNode::getId)
                .collect(Collectors.toSet());

        assertEquals(Set.of(a.getId(), b.getId()), matched,
                "findByAtonUids must return exactly the nodes for the requested refs");
        assertFalse(matched.contains(c.getId()),
                "a node whose ref was not requested must be excluded");
    }

    // ------------------------------------------------------------------------------------------- //
    // search by NAME (case-insensitive, accent-folded substring LIKE on tag values)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByNameReturnsMatchingAton() {
        String name = unique("NAME");
        AtonNode node = persistNode(55.0, 11.0, unique("REF"), name);
        em.flush();
        em.clear();

        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams()
                .name(name)
                .maxSize(1000);

        PagedSearchResultVo<AtonNode> result = atonService.search(params);
        Set<Integer> ids = result.getData().stream().map(AtonNode::getId).collect(Collectors.toSet());

        assertTrue(ids.contains(node.getId()),
                "search by name must return the AtoN whose tag value matches the name token");
    }

    @Test
    @Transactional
    void searchByNameIsCaseInsensitive() {
        String name = unique("NAME"); // upper-case prefix etc.
        AtonNode node = persistNode(55.0, 11.0, unique("REF"), name);
        em.flush();
        em.clear();

        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams()
                .name(name.toLowerCase())
                .maxSize(1000);

        PagedSearchResultVo<AtonNode> result = atonService.search(params);
        Set<Integer> ids = result.getData().stream().map(AtonNode::getId).collect(Collectors.toSet());

        assertTrue(ids.contains(node.getId()),
                "the name LIKE lower-cases both sides, so search must be case-insensitive");
    }

    @Test
    @Transactional
    void searchByNonMatchingNameReturnsZeroTotal() {
        persistNode(55.0, 11.0, unique("REF"), unique("NAME"));
        em.flush();
        em.clear();

        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams()
                .name(unique("NOSUCH"))
                .maxSize(1000);

        PagedSearchResultVo<AtonNode> result = atonService.search(params);
        assertEquals(0, result.getTotal(),
                "a name token that matches nothing must yield a total of 0");
        assertTrue(result.getData().isEmpty(),
                "a name token that matches nothing must yield no data");
    }

    // ------------------------------------------------------------------------------------------- //
    // search emptyOnOverflow short-circuit
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchEmptyOnOverflowReturnsTotalButNoDataWhenMaxSizeExceeded() {
        // Three AtoNs all share the same name token so a single name search matches all three.
        String name = unique("OVF");
        persistNode(55.0, 11.0, unique("REF"), name);
        persistNode(55.1, 11.1, unique("REF"), name);
        persistNode(55.2, 11.2, unique("REF"), name);
        em.flush();
        em.clear();

        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams()
                .name(name)
                .emptyOnOverflow(true)
                .maxSize(1);

        PagedSearchResultVo<AtonNode> result = atonService.search(params);

        assertEquals(3, result.getTotal(),
                "the total must reflect every matching AtoN even when data is suppressed");
        assertTrue(result.getData().isEmpty(),
                "with emptyOnOverflow and maxSize < total, the data list must be empty");
    }

    @Test
    @Transactional
    void searchWithoutEmptyOnOverflowReturnsCappedDataAndFullTotal() {
        String name = unique("OVF");
        persistNode(55.0, 11.0, unique("REF"), name);
        persistNode(55.1, 11.1, unique("REF"), name);
        persistNode(55.2, 11.2, unique("REF"), name);
        em.flush();
        em.clear();

        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams()
                .name(name)
                .emptyOnOverflow(false)
                .maxSize(1);

        PagedSearchResultVo<AtonNode> result = atonService.search(params);

        assertEquals(3, result.getTotal(),
                "the total must reflect every matching AtoN");
        assertEquals(1, result.getData().size(),
                "without emptyOnOverflow the data list must be capped at maxSize");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchPositions by extent ({lon,lat} arrays)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchPositionsByExtentReturnsInsidePointAndExcludesOutsidePoint() {
        // Inside the Denmark box; the position is unique so it cannot collide with seed data.
        double insideLat = 55.123456;
        double insideLon = 11.234567;
        double outsideLat = 80.987654;
        double outsideLon = 80.123456;
        persistNode(insideLat, insideLon, unique("REF"), unique("NAME"));
        persistNode(outsideLat, outsideLon, unique("REF"), unique("NAME"));
        em.flush();
        em.clear();

        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams()
                .extent(BOX_MIN_LAT, BOX_MIN_LON, BOX_MAX_LAT, BOX_MAX_LON)
                .maxSize(100000);

        List<double[]> positions = atonService.searchPositions(params);

        // searchPositions returns {lon, lat} arrays.
        boolean containsInside = positions.stream()
                .anyMatch(p -> close(p[0], insideLon) && close(p[1], insideLat));
        boolean containsOutside = positions.stream()
                .anyMatch(p -> close(p[0], outsideLon) && close(p[1], outsideLat));

        assertTrue(containsInside,
                "searchPositions(extent) must include the {lon,lat} of the point inside the extent");
        assertFalse(containsOutside,
                "searchPositions(extent) must exclude a point outside the extent");
    }

    /** True if the two coordinates are equal within a small tolerance. */
    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 0.0000001;
    }

    // ------------------------------------------------------------------------------------------- //
    // search by CHART (ST_Within against the chart geometry)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByChartReturnsAtonInsideChartAndExcludesOutside() {
        Geometry box = JtsConverter.toJtsExtent(BOX_MIN_LAT, BOX_MIN_LON, BOX_MAX_LAT, BOX_MAX_LON);
        String chartNumber = unique("CHART");
        persistChart(chartNumber, box);

        AtonNode inside = persistNode(55.0, 11.0, unique("REF"), unique("NAME"));
        AtonNode outside = persistNode(80.0, 80.0, unique("REF"), unique("NAME"));
        Set<Integer> mine = Set.of(inside.getId(), outside.getId());
        em.flush();
        em.clear();

        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams()
                .chartNumbers(Set.of(chartNumber))
                .maxSize(100000);

        PagedSearchResultVo<AtonNode> result = atonService.search(params);

        Set<Integer> mineMatched = result.getData().stream()
                .map(AtonNode::getId)
                .filter(mine::contains)
                .collect(Collectors.toSet());

        assertEquals(Set.of(inside.getId()), mineMatched,
                "search by chart must return only the AtoN that lies within the chart geometry");
    }

    // ------------------------------------------------------------------------------------------- //
    // search by AREA (ST_Within against the area geometry)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByAreaReturnsAtonInsideAreaAndExcludesOutside() {
        Geometry box = JtsConverter.toJtsExtent(BOX_MIN_LAT, BOX_MIN_LON, BOX_MAX_LAT, BOX_MAX_LON);
        Area area = persistArea(box);

        AtonNode inside = persistNode(55.0, 11.0, unique("REF"), unique("NAME"));
        AtonNode outside = persistNode(80.0, 80.0, unique("REF"), unique("NAME"));
        Set<Integer> mine = Set.of(inside.getId(), outside.getId());
        em.flush();
        em.clear();

        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams()
                .areaIds(Set.of(area.getId()))
                .maxSize(100000);

        PagedSearchResultVo<AtonNode> result = atonService.search(params);

        Set<Integer> mineMatched = result.getData().stream()
                .map(AtonNode::getId)
                .filter(mine::contains)
                .collect(Collectors.toSet());

        assertEquals(Set.of(inside.getId()), mineMatched,
                "search by area must return only the AtoN that lies within the area geometry");
    }

    @Test
    @Transactional
    void searchByUnknownAreaIdReturnsNothing() {
        AtonNode node = persistNode(55.0, 11.0, unique("REF"), unique("NAME"));
        Set<Integer> mine = Set.of(node.getId());
        em.flush();
        em.clear();

        // An area id that does not exist => the ST_Within join finds no area, so no AtoN can match.
        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams()
                .areaIds(Set.of(-987654))
                .maxSize(100000);

        PagedSearchResultVo<AtonNode> result = atonService.search(params);

        boolean anyOfMine = result.getData().stream()
                .map(AtonNode::getId)
                .anyMatch(mine::contains);

        assertFalse(anyOfMine,
                "search against a non-existent area id must not return any of this test's AtoNs");
    }
}
