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
package org.niord.core.db;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.hibernate.query.sqm.NodeBuilder;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.aton.AtonNode;
import org.niord.core.aton.AtonSearchParams;
import org.niord.core.aton.AtonService;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.geojson.JtsConverter;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that exercise {@link SpatialIntersectsPredicate} and {@link SpatialWithinPredicate}
 * directly against the real MySQL&nbsp;8 spatial functions ({@code ST_Intersects} / {@code ST_Within}),
 * plus the production wiring through {@link AtonService#search(AtonSearchParams)} which builds a
 * {@link SpatialWithinPredicate} from {@code param.getExtent()}.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the shared
 * seed database pristine and the tests independent of execution order.
 *
 * <p>All geometries are built exclusively through {@link JtsConverter} so they share a single SRID-0
 * {@code GeometryFactory}; MySQL's {@code ST_Within}/{@code ST_Intersects} require both arguments to
 * carry the same SRID. The {@link AtonNode#onCreate()} {@code @PrePersist} hook stores the node's
 * geometry as {@code JtsConverter.toJtsPoint(lat, lon)}, i.e. also SRID-0.
 *
 * <p>The query box used throughout is {@code (54,7)-(57,13)}: it contains the point {@code (55,11)} and
 * excludes the points {@code (0,0)} and {@code (80,80)}. Direct-predicate queries additionally restrict
 * to the ids created by the test (via an {@code id IN (...)} clause) so unrelated seed rows never
 * influence the asserted id set.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class SpatialPredicateITTest extends ITTest {

    /** Generates unique OSM uids so tests never collide with each other or with seed data. */
    private static final AtomicInteger UID_SEQ = new AtomicInteger(900000);

    /** The query box that contains (55,11) and excludes (0,0) and (80,80). */
    private static final double BOX_MIN_LAT = 54.0;
    private static final double BOX_MIN_LON = 7.0;
    private static final double BOX_MAX_LAT = 57.0;
    private static final double BOX_MAX_LON = 13.0;

    @PersistenceContext
    EntityManager em;

    @Autowired
    AtonService atonService;

    /**
     * Persists and flushes an {@link AtonNode} at the given position. After flush the
     * {@code geometry} column holds the SRID-0 point produced by {@link AtonNode#onCreate()}.
     */
    private AtonNode persistNode(double lat, double lon) {
        AtonNodeVo vo = new AtonNodeVo();
        vo.setLat(lat);
        vo.setLon(lon);
        vo.setVisible(true);
        vo.setUid(UID_SEQ.incrementAndGet());
        vo.setVersion(1);

        AtonNode node = new AtonNode(vo);
        em.persist(node);
        em.flush();
        return node;
    }

    /**
     * Mirrors the production criteria query: selects the ids of {@link AtonNode}s whose geometry
     * satisfies the supplied spatial predicate, restricted to the given candidate ids so that
     * unrelated rows in the shared database can never affect the result.
     */
    private Set<Integer> queryIds(SpatialPredicateFactory factory, boolean negated, List<Integer> candidateIds) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        // Use a Tuple query (mirrors AtonService.searchPositions): selecting the raw id Path through a
        // typed CriteriaQuery<Integer> trips Hibernate's "selection type Serializable" validation.
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<AtonNode> root = q.from(AtonNode.class);

        Geometry box = JtsConverter.toJtsExtent(BOX_MIN_LAT, BOX_MIN_LON, BOX_MAX_LAT, BOX_MAX_LON);
        Predicate spatial = factory.create((NodeBuilder) cb, root, box, negated);

        q.multiselect(root.get("id"))
                .where(cb.and(spatial, root.get("id").in(candidateIds)));

        return em.createQuery(q).getResultList().stream()
                .map(t -> (Integer) t.get(0))
                .collect(Collectors.toSet());
    }

    /** Functional interface so the two predicate types can share the {@link #queryIds} plumbing. */
    @FunctionalInterface
    private interface SpatialPredicateFactory {
        Predicate create(NodeBuilder nb, Root<AtonNode> root, Geometry box, boolean negated);
    }

    private final SpatialPredicateFactory intersects =
            (nb, root, box, negated) -> new SpatialIntersectsPredicate(nb, root.get("geometry"), box, negated);

    private final SpatialPredicateFactory within =
            (nb, root, box, negated) -> new SpatialWithinPredicate(nb, root.get("geometry"), box, negated);

    // ------------------------------------------------------------------------------------------- //
    // SpatialIntersectsPredicate
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void intersectsNotNegatedReturnsOnlyPointInsideTheBox() {
        AtonNode inside = persistNode(55.0, 11.0);
        AtonNode outsideOrigin = persistNode(0.0, 0.0);
        AtonNode outsideFarNE = persistNode(80.0, 80.0);
        List<Integer> ids = List.of(inside.getId(), outsideOrigin.getId(), outsideFarNE.getId());

        Set<Integer> matched = queryIds(intersects, false, ids);

        assertEquals(Set.of(inside.getId()), matched,
                "ST_Intersects must return exactly the point inside the box");
    }

    @Test
    @Transactional
    void intersectsNegatedReturnsExactlyThePointsOutsideTheBox() {
        AtonNode inside = persistNode(55.0, 11.0);
        AtonNode outsideOrigin = persistNode(0.0, 0.0);
        AtonNode outsideFarNE = persistNode(80.0, 80.0);
        List<Integer> ids = List.of(inside.getId(), outsideOrigin.getId(), outsideFarNE.getId());

        Set<Integer> matched = queryIds(intersects, true, ids);

        assertEquals(Set.of(outsideOrigin.getId(), outsideFarNE.getId()), matched,
                "the negated predicate must return exactly the complement (the points outside the box)");
    }

    @Test
    @Transactional
    void intersectsNegatedIsTheExactComplementOfNotNegated() {
        AtonNode inside = persistNode(56.0, 9.0);
        AtonNode outside = persistNode(80.0, 80.0);
        List<Integer> ids = List.of(inside.getId(), outside.getId());

        Set<Integer> positive = queryIds(intersects, false, ids);
        Set<Integer> negated = queryIds(intersects, true, ids);

        assertEquals(Set.of(inside.getId()), positive);
        assertEquals(Set.of(outside.getId()), negated);
        // The two disjoint result sets must together cover every candidate id.
        Set<Integer> union = new HashSet<>(positive);
        union.addAll(negated);
        assertEquals(new HashSet<>(ids), union,
                "positive and negated results must partition the candidate ids with no overlap");
    }

    // ------------------------------------------------------------------------------------------- //
    // SpatialWithinPredicate
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void withinNotNegatedReturnsOnlyPointInsideTheBox() {
        AtonNode inside = persistNode(55.0, 11.0);
        AtonNode outsideOrigin = persistNode(0.0, 0.0);
        AtonNode outsideFarNE = persistNode(80.0, 80.0);
        List<Integer> ids = List.of(inside.getId(), outsideOrigin.getId(), outsideFarNE.getId());

        Set<Integer> matched = queryIds(within, false, ids);

        assertEquals(Set.of(inside.getId()), matched,
                "ST_Within must return exactly the point within the box");
    }

    @Test
    @Transactional
    void withinNegatedReturnsExactlyThePointsOutsideTheBox() {
        AtonNode inside = persistNode(55.0, 11.0);
        AtonNode outsideOrigin = persistNode(0.0, 0.0);
        AtonNode outsideFarNE = persistNode(80.0, 80.0);
        List<Integer> ids = List.of(inside.getId(), outsideOrigin.getId(), outsideFarNE.getId());

        Set<Integer> matched = queryIds(within, true, ids);

        assertEquals(Set.of(outsideOrigin.getId(), outsideFarNE.getId()), matched,
                "the negated within-predicate must return exactly the complement (the outside points)");
    }

    @Test
    @Transactional
    void withinNegatedIsTheExactComplementOfNotNegated() {
        AtonNode inside = persistNode(54.5, 12.5);
        AtonNode outside = persistNode(0.0, 0.0);
        List<Integer> ids = List.of(inside.getId(), outside.getId());

        Set<Integer> positive = queryIds(within, false, ids);
        Set<Integer> negated = queryIds(within, true, ids);

        assertEquals(Set.of(inside.getId()), positive);
        assertEquals(Set.of(outside.getId()), negated);
        Set<Integer> union = new HashSet<>(positive);
        union.addAll(negated);
        assertEquals(new HashSet<>(ids), union,
                "positive and negated within-results must partition the candidate ids with no overlap");
    }

    // ------------------------------------------------------------------------------------------- //
    // Intersects against a WKT-built polygon
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void intersectsWithWktPolygonMatchesInteriorPointOnly() throws Exception {
        AtonNode inside = persistNode(55.0, 11.0);
        AtonNode outside = persistNode(0.0, 0.0);
        List<Integer> ids = List.of(inside.getId(), outside.getId());

        // Same box as JtsConverter.toJtsExtent(54,7,57,13) but built from WKT; lon-lat ordering.
        Geometry polygon = JtsConverter.wktToJts("POLYGON((7 54,7 57,13 57,13 54,7 54))");

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<AtonNode> root = q.from(AtonNode.class);
        Predicate spatial = new SpatialIntersectsPredicate(
                (NodeBuilder) cb, root.get("geometry"), polygon, false);
        q.multiselect(root.get("id"))
                .where(cb.and(spatial, root.get("id").in(ids)));

        Set<Integer> matched = em.createQuery(q).getResultList().stream()
                .map(t -> (Integer) t.get(0))
                .collect(Collectors.toSet());
        assertEquals(Set.of(inside.getId()), matched,
                "a WKT-built polygon (same SRID via JtsConverter) must intersect only the interior point");
    }

    // ------------------------------------------------------------------------------------------- //
    // Production path: AtonService.search(extent) -> SpatialWithinPredicate
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void serviceSearchByExtentReturnsInsidePointAndExcludesOutsidePoints() {
        AtonNode inside = persistNode(55.0, 11.0);
        AtonNode outsideOrigin = persistNode(0.0, 0.0);
        AtonNode outsideFarNE = persistNode(80.0, 80.0);
        Set<Integer> mine = Set.of(inside.getId(), outsideOrigin.getId(), outsideFarNE.getId());

        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams()
                .extent(BOX_MIN_LAT, BOX_MIN_LON, BOX_MAX_LAT, BOX_MAX_LON)
                .maxSize(1000);

        PagedSearchResultVo<AtonNode> result = atonService.search(params);

        // The search hits the whole table, so restrict to the ids this test created before asserting.
        Set<Integer> mineMatched = result.getData().stream()
                .map(AtonNode::getId)
                .filter(mine::contains)
                .collect(Collectors.toSet());

        assertEquals(Set.of(inside.getId()), mineMatched,
                "AtonService.search(extent) must return only the AtoN inside the extent (within predicate)");
    }

    @Test
    @Transactional
    void serviceSearchByExtentFarFromAllPointsReturnsNoneOfMine() {
        AtonNode a = persistNode(55.0, 11.0);
        AtonNode b = persistNode(56.0, 12.0);
        Set<Integer> mine = Set.of(a.getId(), b.getId());

        // A small extent in the South Pacific, nowhere near the Danish points.
        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams()
                .extent(-40.0, -120.0, -39.0, -119.0)
                .maxSize(1000);

        PagedSearchResultVo<AtonNode> result = atonService.search(params);

        boolean anyOfMine = result.getData().stream()
                .map(AtonNode::getId)
                .anyMatch(mine::contains);

        assertFalse(anyOfMine,
                "an extent far from every created point must return none of this test's AtoNs");
    }

    @Test
    @Transactional
    void serviceSearchWithoutExtentDoesNotFilterSpatiallyAndIncludesMine() {
        // Sanity guard: with no extent the within-predicate is never added, so even an
        // "outside the Denmark box" point must be returned (proving the extent is what filters).
        AtonNode farPoint = persistNode(80.0, 80.0);

        AtonSearchParams params = (AtonSearchParams) new AtonSearchParams().maxSize(100000);

        PagedSearchResultVo<AtonNode> result = atonService.search(params);

        boolean containsFarPoint = result.getData().stream()
                .map(AtonNode::getId)
                .anyMatch(id -> id.equals(farPoint.getId()));

        assertTrue(containsFarPoint,
                "without an extent the spatial predicate is not applied, so the far point must still be returned");
    }
}
