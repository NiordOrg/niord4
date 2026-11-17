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
package org.niord.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link Extent} - the spatial-extent helper whose {@code normalize()} slices a
 * map extent so the back-end (spatial4j) does not have to deal with longitudes outside [-180, 180]
 * or extents straddling the zero meridian.
 *
 * <p>The antimeridian / zero-meridian splitting is exactly the kind of geometry edge logic that
 * fails silently, so each branch is pinned with exact longitude assertions. {@code Extent} is pure
 * (no Quarkus, no DB), so these are framework-neutral and survive a Quarkus -&gt; Spring migration.</p>
 *
 * <p>NB: a source scan found NO references to {@code Extent} anywhere under {@code src/main} - it
 * appears to be currently unused/dead code (recorded as such in docs/untestable-code.md). These
 * tests therefore pin its behaviour so that, should it be re-wired, the non-trivial slicing logic is
 * guarded against regression.</p>
 */
public class ExtentTest {

    private static final double EPS = 1e-9;

    /* ---------------------------------------------------------------- */
    /* withinExtent                                                     */
    /* ---------------------------------------------------------------- */

    @Test
    public void withinExtentIsInclusiveOfTheBoundary() {
        Extent extent = new Extent(10, 20, 30, 40); // minLat,minLon,maxLat,maxLon

        // Strictly inside.
        assertTrue(extent.withinExtent(20, 30));
        // On each corner (boundary is inclusive: >= / <=).
        assertTrue(extent.withinExtent(10, 20));
        assertTrue(extent.withinExtent(30, 40));
    }

    @Test
    public void withinExtentRejectsPointsOutsideLatOrLon() {
        Extent extent = new Extent(10, 20, 30, 40);

        assertFalse(extent.withinExtent(5, 30), "below min lat");
        assertFalse(extent.withinExtent(35, 30), "above max lat");
        assertFalse(extent.withinExtent(20, 15), "left of min lon");
        assertFalse(extent.withinExtent(20, 45), "right of max lon");
    }

    /* ---------------------------------------------------------------- */
    /* normalize - simple case (no split)                              */
    /* ---------------------------------------------------------------- */

    @Test
    public void normalizeReturnsSingleExtentWhenFullyWithinBoundsAndOneSideOfZero() {
        // minLon and maxLon both > 0, both within [-180,180] -> no slicing.
        Extent extent = new Extent(10, 20, 30, 40);

        List<Extent> result = extent.normalize();

        assertEquals(1, result.size());
        Extent only = result.get(0);
        assertEquals(20, only.getMinLon(), EPS);
        assertEquals(40, only.getMaxLon(), EPS);
        assertEquals(10, only.getMinLat(), EPS);
        assertEquals(30, only.getMaxLat(), EPS);
    }

    /* ---------------------------------------------------------------- */
    /* normalize - zero-meridian straddle                              */
    /* ---------------------------------------------------------------- */

    @Test
    public void normalizeSplitsAcrossTheZeroMeridian() {
        // minLon < 0 < maxLon (and within +/-180) -> split into [-20,0] and [0,40].
        Extent extent = new Extent(10, -20, 30, 40);

        List<Extent> result = extent.normalize();

        assertEquals(2, result.size(), "an extent crossing the zero meridian must be split in two");
        assertEquals(-20, result.get(0).getMinLon(), EPS);
        assertEquals(0, result.get(0).getMaxLon(), EPS);
        assertEquals(0, result.get(1).getMinLon(), EPS);
        assertEquals(40, result.get(1).getMaxLon(), EPS);
        // Latitude is preserved on both slices.
        assertEquals(10, result.get(0).getMinLat(), EPS);
        assertEquals(30, result.get(1).getMaxLat(), EPS);
    }

    /* ---------------------------------------------------------------- */
    /* normalize - antimeridian wrap (west and east)                   */
    /* ---------------------------------------------------------------- */

    @Test
    public void normalizeWrapsWhenMinLonBelowMinus180() {
        // minLon = -190 (< -180) -> wrap: [170,180] and [-180,-170].
        Extent extent = new Extent(10, -190, 30, -170);

        List<Extent> result = extent.normalize();

        assertEquals(2, result.size());
        assertEquals(170, result.get(0).getMinLon(), EPS);   // 360 + (-190)
        assertEquals(180, result.get(0).getMaxLon(), EPS);
        assertEquals(-180, result.get(1).getMinLon(), EPS);
        assertEquals(-170, result.get(1).getMaxLon(), EPS);
    }

    @Test
    public void normalizeWrapsWhenMaxLonAbove180() {
        // maxLon = 190 (> 180) -> wrap: [170,180] and [-180,-170].
        Extent extent = new Extent(10, 170, 30, 190);

        List<Extent> result = extent.normalize();

        assertEquals(2, result.size());
        assertEquals(170, result.get(0).getMinLon(), EPS);
        assertEquals(180, result.get(0).getMaxLon(), EPS);
        assertEquals(-180, result.get(1).getMinLon(), EPS);
        assertEquals(-170, result.get(1).getMaxLon(), EPS); // 190 - 360
    }
}
