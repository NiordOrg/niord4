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
package org.niord.core.aton.vo;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests (no DB, no Quarkus) for {@link AtonBoundsVo} and {@link AtonOsmVo}.
 *
 * Focus:
 *  - {@link AtonBoundsVo#fromAtons(AtonNodeVo[])} bounding-box computation incl. the
 *    suspected sentinel-seed leak on an empty (but non-null) array.
 *  - {@link AtonOsmVo#validSeamark()} delegation.
 *  - {@link AtonOsmVo#computeBounds()} wiring.
 *  - {@link AtonOsmVo#equals(Object)} / {@link AtonOsmVo#hashCode()} contract.
 */
public class AtonOsmBoundsVoTest {

    private static final double DELTA = 0.0d;

    /*************************/
    /** Helpers             **/
    /*************************/

    /** Builds an AtoN node at the given (lat, lon) with no tags (used for bounds tests). */
    private static AtonNodeVo nodeAt(double lat, double lon) {
        AtonNodeVo n = new AtonNodeVo();
        n.setLat(lat);
        n.setLon(lon);
        return n;
    }

    /** Builds a valid-seamark AtoN node: non-blank user, non-null timestamp, one valid seamark tag. */
    private static AtonNodeVo validNode() {
        AtonNodeVo n = new AtonNodeVo();
        n.setUser("malcolmh");
        n.setTimestamp(new Date(1314134556000L));
        n.setTags(new AtonTagVo[] { new AtonTagVo("seamark:type", "buoy_cardinal") });
        return n;
    }

    /** Builds an AtoN node that is NOT a valid seamark (tag key does not start with "seamark:"). */
    private static AtonNodeVo invalidNode() {
        AtonNodeVo n = new AtonNodeVo();
        n.setUser("malcolmh");
        n.setTimestamp(new Date(1314134556000L));
        n.setTags(new AtonTagVo[] { new AtonTagVo("not_seamark:type", "buoy_cardinal") });
        return n;
    }

    private static AtonOsmVo osm(float version, String generator, AtonNodeVo[] nodes) {
        AtonOsmVo osm = new AtonOsmVo();
        osm.setVersion(version);
        osm.setGenerator(generator);
        osm.setNodes(nodes);
        return osm;
    }

    /*************************/
    /** fromAtons()         **/
    /*************************/

    @Test
    public void fromAtonsNullReturnsNull() {
        assertNull(AtonBoundsVo.fromAtons(null));
    }

    @Test
    public void fromAtonsSingleAtonHasEqualMinAndMax() {
        AtonBoundsVo b = AtonBoundsVo.fromAtons(new AtonNodeVo[] { nodeAt(50.8070813, -1.2841124) });

        assertNotNull(b);
        assertEquals(50.8070813, b.getMinlat(), DELTA);
        assertEquals(50.8070813, b.getMaxlat(), DELTA);
        assertEquals(-1.2841124, b.getMinlon(), DELTA);
        assertEquals(-1.2841124, b.getMaxlon(), DELTA);
    }

    @Test
    public void fromAtonsMultipleAtonsComputesMinMaxAcrossAll() {
        AtonNodeVo[] atons = {
                nodeAt(10.0, 20.0),
                nodeAt(-5.0, 100.0),
                nodeAt(40.0, -30.0),
                nodeAt(15.0, 50.0)
        };

        AtonBoundsVo b = AtonBoundsVo.fromAtons(atons);

        assertNotNull(b);
        assertEquals(-5.0, b.getMinlat(), DELTA);
        assertEquals(40.0, b.getMaxlat(), DELTA);
        assertEquals(-30.0, b.getMinlon(), DELTA);
        assertEquals(100.0, b.getMaxlon(), DELTA);
    }

    @Test
    public void fromAtonsTwoAtonsOrderIndependent() {
        AtonNodeVo[] forward = { nodeAt(1.0, 2.0), nodeAt(3.0, 4.0) };
        AtonNodeVo[] reverse = { nodeAt(3.0, 4.0), nodeAt(1.0, 2.0) };

        AtonBoundsVo bf = AtonBoundsVo.fromAtons(forward);
        AtonBoundsVo br = AtonBoundsVo.fromAtons(reverse);

        assertEquals(1.0, bf.getMinlat(), DELTA);
        assertEquals(3.0, bf.getMaxlat(), DELTA);
        assertEquals(2.0, bf.getMinlon(), DELTA);
        assertEquals(4.0, bf.getMaxlon(), DELTA);

        // Order of the input array must not change the resulting bounds.
        assertEquals(bf.getMinlat(), br.getMinlat(), DELTA);
        assertEquals(bf.getMaxlat(), br.getMaxlat(), DELTA);
        assertEquals(bf.getMinlon(), br.getMinlon(), DELTA);
        assertEquals(bf.getMaxlon(), br.getMaxlon(), DELTA);
    }

    @Test
    public void fromAtonsHandlesNegativeAndZeroCoordinates() {
        AtonNodeVo[] atons = { nodeAt(0.0, 0.0), nodeAt(-12.5, -7.25) };

        AtonBoundsVo b = AtonBoundsVo.fromAtons(atons);

        assertEquals(-12.5, b.getMinlat(), DELTA);
        assertEquals(0.0, b.getMaxlat(), DELTA);
        assertEquals(-7.25, b.getMinlon(), DELTA);
        assertEquals(0.0, b.getMaxlon(), DELTA);
    }

    @Test
    public void emptyAtonArrayReturnsNullBounds() {
        // ATON3 fixed: fromAtons now treats an empty (but non-null) array like the null case and
        // returns null, rather than leaking the inverted sentinel seed (min > max) as if it were a
        // real bounding box. This keeps the empty and null inputs consistent.
        assertNull(AtonBoundsVo.fromAtons(new AtonNodeVo[0]),
                "empty (non-null) array must return null, not an inverted sentinel bbox");
    }

    /*************************/
    /** AtonOsmVo.validSeamark() **/
    /*************************/

    @Test
    public void validSeamarkFalseWhenNodesNull() {
        AtonOsmVo osm = new AtonOsmVo();
        // nodes left null
        assertFalse(osm.validSeamark());
    }

    @Test
    public void validSeamarkTrueWhenAllNodesValid() {
        AtonOsmVo osm = osm(0.6f, "Niord", new AtonNodeVo[] { validNode(), validNode() });
        assertTrue(osm.validSeamark());
    }

    @Test
    public void validSeamarkFalseWhenOneNodeInvalid() {
        AtonOsmVo osm = osm(0.6f, "Niord", new AtonNodeVo[] { validNode(), invalidNode() });
        assertFalse(osm.validSeamark());
    }

    @Test
    public void validSeamarkTrueForEmptyNodeArray() {
        // allMatch over an empty stream is vacuously true, and nodes != null, so the OSM is "valid".
        AtonOsmVo osm = osm(0.6f, "Niord", new AtonNodeVo[0]);
        assertTrue(osm.validSeamark());
    }

    /*************************/
    /** AtonOsmVo.computeBounds() **/
    /*************************/

    @Test
    public void computeBoundsSetsBoundsFromNodes() {
        AtonOsmVo osm = osm(0.6f, "Niord", new AtonNodeVo[] {
                nodeAt(10.0, 20.0),
                nodeAt(30.0, 40.0)
        });

        assertNull(osm.getBounds(), "bounds should be unset before computeBounds()");

        osm.computeBounds();

        AtonBoundsVo b = osm.getBounds();
        assertNotNull(b);
        assertEquals(10.0, b.getMinlat(), DELTA);
        assertEquals(30.0, b.getMaxlat(), DELTA);
        assertEquals(20.0, b.getMinlon(), DELTA);
        assertEquals(40.0, b.getMaxlon(), DELTA);
    }

    @Test
    public void computeBoundsSetsBoundsToNullWhenNodesNull() {
        AtonOsmVo osm = new AtonOsmVo();
        // give it a non-null bounds first to prove computeBounds() overwrites it with null
        osm.setBounds(new AtonBoundsVo());
        assertNotNull(osm.getBounds());

        osm.computeBounds();

        assertNull(osm.getBounds(), "fromAtons(null) is null, so bounds must become null");
    }

    /*************************/
    /** AtonOsmVo.equals()/hashCode() **/
    /*************************/

    @Test
    public void equalsAndHashCodeWhenVersionGeneratorAndNodesEqual() {
        AtonNodeVo n = validNode();
        AtonOsmVo a = osm(0.6f, "Niord", new AtonNodeVo[] { n });
        AtonOsmVo b = osm(0.6f, "Niord", new AtonNodeVo[] { validNode() });

        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equalsReflexive() {
        AtonOsmVo a = osm(0.6f, "Niord", new AtonNodeVo[] { validNode() });
        assertEquals(a, a);
    }

    @Test
    public void notEqualWhenVersionDiffers() {
        AtonOsmVo a = osm(0.6f, "Niord", new AtonNodeVo[] { validNode() });
        AtonOsmVo b = osm(0.7f, "Niord", new AtonNodeVo[] { validNode() });
        assertNotEquals(a, b);
    }

    @Test
    public void notEqualWhenGeneratorDiffers() {
        AtonOsmVo a = osm(0.6f, "Niord", new AtonNodeVo[] { validNode() });
        AtonOsmVo b = osm(0.6f, "JOSM", new AtonNodeVo[] { validNode() });
        assertNotEquals(a, b);
    }

    @Test
    public void notEqualWhenNodesDiffer() {
        AtonOsmVo a = osm(0.6f, "Niord", new AtonNodeVo[] { nodeAt(10.0, 20.0) });
        AtonOsmVo b = osm(0.6f, "Niord", new AtonNodeVo[] { nodeAt(30.0, 40.0) });
        assertNotEquals(a, b);
    }

    @Test
    public void notEqualWhenOneHasNullGenerator() {
        AtonOsmVo a = osm(0.6f, null, new AtonNodeVo[] { validNode() });
        AtonOsmVo b = osm(0.6f, "Niord", new AtonNodeVo[] { validNode() });
        assertNotEquals(a, b);
        assertNotEquals(b, a);
    }

    @Test
    public void equalsWhenBothHaveNullGeneratorAndNullNodes() {
        AtonOsmVo a = osm(0.6f, null, null);
        AtonOsmVo b = osm(0.6f, null, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void notEqualToNullAndToDifferentType() {
        AtonOsmVo a = osm(0.6f, "Niord", new AtonNodeVo[] { validNode() });
        assertNotEquals(a, null);
        assertNotEquals(a, "not an osm vo");
    }
}
