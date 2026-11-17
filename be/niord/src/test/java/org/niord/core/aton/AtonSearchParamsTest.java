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
package org.niord.core.aton;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.geojson.JtsConverter;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit tests for {@link AtonSearchParams}.
 *
 * Verifies the fluent (method-chaining) setters, the null-safe {@code toSet}
 * normalisation inherited from {@code PagedSearchParamsVo}, the JTS extent
 * construction (via {@link JtsConverter#toJtsExtent}), and the documented
 * default values.
 *
 * Framework-neutral: no Quarkus, no DB, no mocks. Only the JTS geometry library
 * (already a production dependency of {@link JtsConverter}) is used to assert
 * the spatial extent behaviour.
 */
public class AtonSearchParamsTest {

    private static final double DELTA = 1e-9;

    /* ------------------------------------------------------------------ */
    /* Fluent setters return the same instance (method chaining contract)  */
    /* ------------------------------------------------------------------ */

    /** name(...) returns the same instance so calls can be chained. */
    @Test
    public void nameReturnsSameInstance() {
        AtonSearchParams p = new AtonSearchParams();
        assertSame(p, p.name("Lighthouse"));
    }

    /** chartNumbers(...) returns the same instance so calls can be chained. */
    @Test
    public void chartNumbersReturnsSameInstance() {
        AtonSearchParams p = new AtonSearchParams();
        assertSame(p, p.chartNumbers(Set.of("101")));
    }

    /** areaIds(...) returns the same instance so calls can be chained. */
    @Test
    public void areaIdsReturnsSameInstance() {
        AtonSearchParams p = new AtonSearchParams();
        assertSame(p, p.areaIds(Set.of(1)));
    }

    /** emptyOnOverflow(...) returns the same instance so calls can be chained. */
    @Test
    public void emptyOnOverflowReturnsSameInstance() {
        AtonSearchParams p = new AtonSearchParams();
        assertSame(p, p.emptyOnOverflow(true));
    }

    /** extent(Geometry) returns the same instance so calls can be chained. */
    @Test
    public void extentGeometryReturnsSameInstance() {
        AtonSearchParams p = new AtonSearchParams();
        Geometry g = JtsConverter.toJtsExtent(0.0, 0.0, 1.0, 1.0);
        assertSame(p, p.extent(g));
    }

    /** extent(Double, Double, Double, Double) returns the same instance so calls can be chained. */
    @Test
    public void extentBoxReturnsSameInstance() {
        AtonSearchParams p = new AtonSearchParams();
        assertSame(p, p.extent(0.0, 0.0, 1.0, 1.0));
    }

    /** maxSize(...) is inherited and still returns the underlying instance for chaining. */
    @Test
    public void maxSizeReturnsSameInstance() {
        AtonSearchParams p = new AtonSearchParams();
        assertSame(p, p.maxSize(50));
    }

    /* ------------------------------------------------------------------ */
    /* Stored values                                                       */
    /* ------------------------------------------------------------------ */

    /** name(...) stores the value, retrievable via getName(). */
    @Test
    public void nameStoresValue() {
        AtonSearchParams p = new AtonSearchParams().name("Buoy A");
        assertEquals("Buoy A", p.getName());
    }

    /** chartNumbers(Set) stores exactly the supplied chart numbers. */
    @Test
    public void chartNumbersStoresValues() {
        AtonSearchParams p = new AtonSearchParams().chartNumbers(Set.of("101", "102"));

        Set<String> charts = p.getChartNumbers();
        assertEquals(2, charts.size());
        assertTrue(charts.contains("101"));
        assertTrue(charts.contains("102"));
    }

    /** areaIds(Set) stores exactly the supplied area ids. */
    @Test
    public void areaIdsStoresValues() {
        AtonSearchParams p = new AtonSearchParams().areaIds(Set.of(1, 2));

        Set<Integer> ids = p.getAreaIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains(1));
        assertTrue(ids.contains(2));
    }

    /** emptyOnOverflow(true) is reflected by isEmptyOnOverflow(). */
    @Test
    public void emptyOnOverflowStoresValue() {
        AtonSearchParams p = new AtonSearchParams().emptyOnOverflow(true);
        assertTrue(p.isEmptyOnOverflow());

        // and can be toggled back off
        p.emptyOnOverflow(false);
        assertFalse(p.isEmptyOnOverflow());
    }

    /** maxSize(int) (inherited) stores the value, retrievable via getMaxSize(). */
    @Test
    public void maxSizeStoresValue() {
        AtonSearchParams p = new AtonSearchParams();
        p.maxSize(25);
        assertEquals(25, p.getMaxSize());
    }

    /* ------------------------------------------------------------------ */
    /* toSet null-safety: null collapses to an empty (non-null) mutable set */
    /* ------------------------------------------------------------------ */

    /** chartNumbers(null) yields an empty, non-null set (PagedSearchParamsVo.toSet). */
    @Test
    public void chartNumbersNullYieldsEmptyNonNullSet() {
        AtonSearchParams p = new AtonSearchParams().chartNumbers(null);

        Set<String> charts = p.getChartNumbers();
        assertNotNull(charts, "chartNumbers(null) must not store null");
        assertTrue(charts.isEmpty(), "chartNumbers(null) must store an empty set");
    }

    /** areaIds(null) yields an empty, non-null set (PagedSearchParamsVo.toSet). */
    @Test
    public void areaIdsNullYieldsEmptyNonNullSet() {
        AtonSearchParams p = new AtonSearchParams().areaIds(null);

        Set<Integer> ids = p.getAreaIds();
        assertNotNull(ids, "areaIds(null) must not store null");
        assertTrue(ids.isEmpty(), "areaIds(null) must store an empty set");
    }

    /**
     * toSet copies into a fresh, mutable HashSet (Quarkus would otherwise inject
     * an immutable container). The stored set must be modifiable and decoupled
     * from the argument.
     */
    @Test
    public void chartNumbersStoresMutableDefensiveCopy() {
        Set<String> source = new HashSet<>();
        source.add("101");

        AtonSearchParams p = new AtonSearchParams().chartNumbers(source);
        Set<String> stored = p.getChartNumbers();

        // The stored set must be a distinct instance, not the same reference.
        assertNotSame(source, stored);

        // Mutating the source after the fact must not leak into the stored set.
        source.add("999");
        assertFalse(stored.contains("999"));
        assertEquals(1, stored.size());

        // The stored set must itself be mutable.
        stored.add("102");
        assertTrue(stored.contains("102"));
    }

    /* ------------------------------------------------------------------ */
    /* extent(...) construction and storage                                */
    /* ------------------------------------------------------------------ */

    /**
     * extent(minLat, minLon, maxLat, maxLon) builds a JTS polygon whose envelope
     * exactly covers the requested bounding box. JTS uses (x=lon, y=lat), so the
     * envelope's X range is [minLon, maxLon] and its Y range is [minLat, maxLat].
     */
    @Test
    public void extentBoxBuildsEnvelopeCoveringTheBox() {
        double minLat = 10.0, minLon = 20.0, maxLat = 30.0, maxLon = 40.0;

        AtonSearchParams p = new AtonSearchParams().extent(minLat, minLon, maxLat, maxLon);

        Geometry extent = p.getExtent();
        assertNotNull(extent);

        Envelope env = extent.getEnvelopeInternal();
        assertEquals(minLon, env.getMinX(), DELTA, "minLon maps to envelope minX");
        assertEquals(maxLon, env.getMaxX(), DELTA, "maxLon maps to envelope maxX");
        assertEquals(minLat, env.getMinY(), DELTA, "minLat maps to envelope minY");
        assertEquals(maxLat, env.getMaxY(), DELTA, "maxLat maps to envelope maxY");
    }

    /**
     * The extent box equals the geometry produced directly by
     * {@link JtsConverter#toJtsExtent} for the same corners (the setter merely
     * delegates to it).
     */
    @Test
    public void extentBoxEqualsToJtsExtent() {
        double minLat = -5.0, minLon = -7.5, maxLat = 5.0, maxLon = 7.5;

        AtonSearchParams p = new AtonSearchParams().extent(minLat, minLon, maxLat, maxLon);
        Geometry expected = JtsConverter.toJtsExtent(minLat, minLon, maxLat, maxLon);

        assertNotNull(expected);
        assertTrue(expected.equalsTopo(p.getExtent()),
                "extent must equal the toJtsExtent polygon for the same corners");
    }

    /**
     * A point that lies strictly inside the requested box is covered by the
     * stored extent polygon, while a point well outside it is not.
     */
    @Test
    public void extentBoxCoversInteriorPointAndExcludesOutsidePoint() {
        AtonSearchParams p = new AtonSearchParams().extent(10.0, 20.0, 30.0, 40.0);
        Geometry extent = p.getExtent();

        // toJtsPoint(lat, lon) -> point(lon, lat); a point at the box centre.
        Geometry inside = JtsConverter.toJtsPoint(20.0, 30.0);
        Geometry outside = JtsConverter.toJtsPoint(80.0, 100.0);

        assertTrue(extent.covers(inside), "box must cover an interior point");
        assertFalse(extent.covers(outside), "box must not cover an exterior point");
    }

    /** extent(Geometry) stores exactly the supplied geometry instance. */
    @Test
    public void extentGeometryStoresSameInstance() {
        Geometry g = JtsConverter.toJtsExtent(0.0, 0.0, 1.0, 1.0);

        AtonSearchParams p = new AtonSearchParams().extent(g);

        assertSame(g, p.getExtent(), "extent(Geometry) must store the exact geometry passed in");
    }

    /* ------------------------------------------------------------------ */
    /* Defaults                                                            */
    /* ------------------------------------------------------------------ */

    /** Fresh params expose the documented defaults. */
    @Test
    public void freshParamsHaveDocumentedDefaults() {
        AtonSearchParams p = new AtonSearchParams();

        assertNull(p.getName(), "default name is null");
        assertNull(p.getExtent(), "default extent is null");
        assertFalse(p.isEmptyOnOverflow(), "default emptyOnOverflow is false");
        assertEquals(Integer.MAX_VALUE, p.getMaxSize(), "default maxSize is Integer.MAX_VALUE");
    }

    /** Fresh params start with empty (non-null) chart and area collections. */
    @Test
    public void freshParamsHaveEmptyCollections() {
        AtonSearchParams p = new AtonSearchParams();

        assertNotNull(p.getChartNumbers());
        assertTrue(p.getChartNumbers().isEmpty(), "default chartNumbers is an empty set");

        assertNotNull(p.getAreaIds());
        assertTrue(p.getAreaIds().isEmpty(), "default areaIds is an empty set");
    }
}
