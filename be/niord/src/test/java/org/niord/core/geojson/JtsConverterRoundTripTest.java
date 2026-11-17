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
package org.niord.core.geojson;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.niord.model.geojson.GeometryVo;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for {@link JtsConverter}: JTS -&gt; GeoJSON VO -&gt; JTS must preserve coordinates
 * exactly. The {@code area} entity relies on this fidelity for geometry change-detection, so these
 * tests underpin the geometry assertions in the area package tests.
 */
public class JtsConverterRoundTripTest {

    /**
     * Round-trips through {@code toJts} and through {@code toJtsOnGlobe}, the conversion an entity stores through
     * (CG8): a geometry on the globe must come back exactly from both.
     */
    private void assertExactRoundTrip(String wkt) throws ParseException {
        Geometry original = JtsConverter.wktToJts(wkt);
        GeometryVo vo = JtsConverter.fromJts(original);
        Geometry roundTripped = JtsConverter.toJts(vo);
        assertTrue(original.equalsExact(roundTripped), "Round-trip changed geometry for: " + wkt);
        Geometry storedOnGlobe = JtsConverter.toJtsOnGlobe(vo);
        assertTrue(original.equalsExact(storedOnGlobe), "Storing on the globe changed geometry for: " + wkt);
    }

    @Test
    public void pointRoundTripsExactly() throws ParseException {
        assertExactRoundTrip("POINT (10 56)");
    }

    @Test
    public void lineStringRoundTripsExactly() throws ParseException {
        assertExactRoundTrip("LINESTRING (0 0, 10 10, 20 25)");
    }

    @Test
    public void polygonWithHoleRoundTripsExactly() throws ParseException {
        assertExactRoundTrip("POLYGON ((0 0, 0 10, 10 10, 10 0, 0 0), (2 2, 2 4, 4 4, 4 2, 2 2))");
    }

    @Test
    public void multiPointRoundTripsExactly() throws ParseException {
        assertExactRoundTrip("MULTIPOINT ((0 0), (10 10))");
    }

    @Test
    public void multiPolygonRoundTripsExactly() throws ParseException {
        assertExactRoundTrip("MULTIPOLYGON (((0 0, 0 5, 5 5, 5 0, 0 0)), ((10 10, 10 15, 15 15, 15 10, 10 10)))");
    }

    @Test
    public void geometryOnTheGlobeEdgesRoundTripsExactly() throws ParseException {
        // The antimeridian on both sides, the poles, and a line across the antimeridian whose middle is on the globe
        assertExactRoundTrip("MULTIPOINT ((180 0), (-180 0), (0 90), (0 -90))");
        assertExactRoundTrip("LINESTRING (165 10, 185 12)");
        assertExactRoundTrip("GEOMETRYCOLLECTION (POINT (10 56), LINESTRING (-179.5 -60, 179.5 -61))");
    }

    @Test
    public void nullToJtsReturnsNull() {
        assertNull(JtsConverter.toJts(null));
    }

    @Test
    public void nullFromJtsReturnsNull() {
        assertNull(JtsConverter.fromJts(null));
    }
}
