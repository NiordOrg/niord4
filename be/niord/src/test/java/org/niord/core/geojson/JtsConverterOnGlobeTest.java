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
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.niord.core.area.Area;
import org.niord.core.area.AreaMapper;
import org.niord.core.area.vo.SystemAreaVo;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartMapper;
import org.niord.core.chart.vo.SystemChartVo;
import org.niord.model.DataFilter;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.GeometryCollectionVo;
import org.niord.model.geojson.GeometryVo;
import org.niord.model.geojson.LineStringVo;
import org.niord.model.geojson.MultiPolygonVo;
import org.niord.model.geojson.PointVo;
import org.niord.model.geojson.PolygonVo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JtsConverter#toJtsOnGlobe(GeometryVo)} (CG8): a geometry that is to be stored is
 * moved onto the globe by whole turns of 360 degrees, the whole geometry by the same turns, and a latitude beyond
 * 90 is refused. And for the places that build an entity from a value object, which store through it: a message
 * part's feature, an area and a chart, by their constructors and by their mappers.
 */
public class JtsConverterOnGlobeTest {

    private static final double EXACT = 0.0;

    private static double[] lons(Geometry g) {
        Coordinate[] coords = g.getCoordinates();
        double[] lons = new double[coords.length];
        for (int i = 0; i < coords.length; i++) {
            lons[i] = coords[i].x;
        }
        return lons;
    }

    private static double[] lats(Geometry g) {
        Coordinate[] coords = g.getCoordinates();
        double[] lats = new double[coords.length];
        for (int i = 0; i < coords.length; i++) {
            lats[i] = coords[i].y;
        }
        return lats;
    }

    private static PolygonVo box(double minLon, double minLat, double maxLon, double maxLat) {
        return new PolygonVo(new double[][][] {{
                {minLon, minLat}, {maxLon, minLat}, {maxLon, maxLat}, {minLon, maxLat}, {minLon, minLat}}});
    }

    // ------------------------------------------------------------------------------------------- //
    // Whole turns
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aPointDrawnOnTheWorldCopyToTheEast_isStoredOneTurnBack_190EastAs170West() {
        Geometry g = JtsConverter.toJtsOnGlobe(new PointVo(new double[] {190.0, 56.0}));
        assertArrayEquals(new double[] {-170.0}, lons(g), EXACT);
        assertArrayEquals(new double[] {56.0}, lats(g), EXACT);
    }

    @Test
    void aPointOnTheWorldCopyToTheWest_isStoredOneTurnForward_190WestAs170East() {
        Geometry g = JtsConverter.toJtsOnGlobe(new PointVo(new double[] {-190.0, -33.5}));
        assertArrayEquals(new double[] {170.0}, lons(g), EXACT);
        assertArrayEquals(new double[] {-33.5}, lats(g), EXACT);
    }

    @Test
    void aPositionTenTurnsWestOfItsPlace_isBroughtBackByTenTurns() {
        // A legacy row held points about ten turns west of their place
        Geometry g = JtsConverter.toJtsOnGlobe(new PointVo(new double[] {10.5 - 3600.0, 55.25}));
        assertEquals(10.5, g.getCoordinate().x, 1e-9);
        assertEquals(55.25, g.getCoordinate().y, EXACT);
    }

    @Test
    void aWholeGeometryMovesByTheSameTurns_soItsShapeIsKept() {
        // Two turns east, and the box crosses the antimeridian of its world copy: 530..550 is -190..-170
        Geometry g = JtsConverter.toJtsOnGlobe(box(530.0, 50.0, 550.0, 60.0));
        assertArrayEquals(new double[] {-190.0, -170.0, -170.0, -190.0, -190.0}, lons(g), EXACT);
        assertArrayEquals(new double[] {50.0, 50.0, 60.0, 60.0, 50.0}, lats(g), EXACT);
        assertTrue(g.isValid(), "the moved box must still be a valid polygon");
    }

    @Test
    void aLineAcrossTheAntimeridianWhoseMiddleIsOnTheGlobe_isLeftWhole() {
        // 165 E to 185 E (175 W): the middle, 175 E, is on the globe, so nothing moves and nothing is cut
        LineStringVo line = new LineStringVo(new double[][] {{165.0, 10.0}, {185.0, 12.0}});
        Geometry g = JtsConverter.toJtsOnGlobe(line);
        assertArrayEquals(new double[] {165.0, 185.0}, lons(g), EXACT);
    }

    @Test
    void aLineAcrossTheAntimeridianWhoseMiddleIsOffTheGlobe_isTurnedWhole() {
        // 175 E to 195 E: the middle, 185 E, is off the globe, so the line moves one turn back, as one
        LineStringVo line = new LineStringVo(new double[][] {{175.0, 10.0}, {195.0, 12.0}});
        Geometry g = JtsConverter.toJtsOnGlobe(line);
        assertArrayEquals(new double[] {-185.0, -165.0}, lons(g), EXACT);
        assertArrayEquals(new double[] {10.0, 12.0}, lats(g), EXACT);
    }

    @Test
    void aMiddleOnTheAntimeridianItself_goesTo180West_asTheMapEditorPutsIt() {
        // [-180, 180): 170 E to 190 E has its middle on 180 and becomes 190 W to 170 W
        LineStringVo line = new LineStringVo(new double[][] {{170.0, 0.0}, {190.0, 0.0}});
        Geometry g = JtsConverter.toJtsOnGlobe(line);
        assertArrayEquals(new double[] {-190.0, -170.0}, lons(g), EXACT);
    }

    @Test
    void aGeometryCollectionIsTurnedAsOneGeometry() {
        GeometryCollectionVo gc = new GeometryCollectionVo(new GeometryVo[] {
                new PointVo(new double[] {170.0, 1.0}),
                new PointVo(new double[] {200.0, 2.0})});
        Geometry g = JtsConverter.toJtsOnGlobe(gc);
        assertTrue(g instanceof GeometryCollection);
        assertArrayEquals(new double[] {-190.0, -160.0}, lons(g), EXACT);
    }

    @Test
    void aMultiPolygonIsTurnedAsOneGeometry() {
        MultiPolygonVo mp = new MultiPolygonVo(new double[][][][] {
                box(370.0, 54.0, 371.0, 55.0).getCoordinates(),
                box(375.0, 56.0, 376.0, 57.0).getCoordinates()});
        Geometry g = JtsConverter.toJtsOnGlobe(mp);
        assertEquals(10.0, g.getEnvelopeInternal().getMinX(), EXACT);
        assertEquals(16.0, g.getEnvelopeInternal().getMaxX(), EXACT);
    }

    @Test
    void aGeometryOnTheGlobe_isLeftExactlyAsItIs_theEdgesIncluded() {
        for (double[] c : new double[][] {{10.5, 56.0}, {180.0, 0.0}, {-180.0, 0.0}, {0.0, 90.0}, {0.0, -90.0}}) {
            Geometry g = JtsConverter.toJtsOnGlobe(new PointVo(c));
            assertEquals(c[0], g.getCoordinate().x, EXACT, "longitude of " + c[0] + ", " + c[1]);
            assertEquals(c[1], g.getCoordinate().y, EXACT, "latitude of " + c[0] + ", " + c[1]);
        }
        PolygonVo world = box(-180.0, -90.0, 180.0, 90.0);
        assertTrue(JtsConverter.toJts(world).equalsExact(JtsConverter.toJtsOnGlobe(world)),
                "a geometry spanning the whole globe is on it");
    }

    @Test
    void noGeometryAndAnEmptyGeometryAreLeftAsTheyAre() {
        assertNull(JtsConverter.toJtsOnGlobe(null));
        Geometry empty = JtsConverter.toJtsOnGlobe(new GeometryCollectionVo(new GeometryVo[0]));
        assertTrue(empty.isEmpty());
    }

    @Test
    void wholeTurnsAreCountedByTheMiddleOfTheLongitudeExtent() {
        assertEquals(0.0, JtsConverter.wholeTurnsOffGlobe(-180.0, -180.0), EXACT);
        assertEquals(0.0, JtsConverter.wholeTurnsOffGlobe(170.0, 189.0), EXACT);
        assertEquals(1.0, JtsConverter.wholeTurnsOffGlobe(180.0, 180.0), EXACT);
        assertEquals(1.0, JtsConverter.wholeTurnsOffGlobe(190.0, 190.0), EXACT);
        assertEquals(-1.0, JtsConverter.wholeTurnsOffGlobe(-190.0, -190.0), EXACT);
        assertEquals(-10.0, JtsConverter.wholeTurnsOffGlobe(-3589.5, -3589.5), EXACT);
    }

    // ------------------------------------------------------------------------------------------- //
    // Refusals
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aLatitudeBeyond90_isRefused_northAndSouth() {
        IllegalArgumentException north = assertThrows(IllegalArgumentException.class,
                () -> JtsConverter.toJtsOnGlobe(new PointVo(new double[] {10.0, 95.0})));
        assertTrue(north.getMessage().contains("95.0"), north.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> JtsConverter.toJtsOnGlobe(new PointVo(new double[] {10.0, -90.5})));
    }

    @Test
    void aLatitudeBeyond90_isRefused_inAnyMemberOfAGeometry() {
        assertThrows(IllegalArgumentException.class, () -> JtsConverter.toJtsOnGlobe(new GeometryCollectionVo(
                new GeometryVo[] {new PointVo(new double[] {10.0, 56.0}), box(10.0, 56.0, 11.0, 91.0)})));
        // also when the geometry would otherwise be turned
        assertThrows(IllegalArgumentException.class,
                () -> JtsConverter.toJtsOnGlobe(new PointVo(new double[] {190.0, 95.0})));
    }

    @Test
    void aCoordinateThatIsNotAFiniteNumber_isRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> JtsConverter.toJtsOnGlobe(new PointVo(new double[] {Double.NaN, 56.0})));
        assertThrows(IllegalArgumentException.class,
                () -> JtsConverter.toJtsOnGlobe(new PointVo(new double[] {Double.POSITIVE_INFINITY, 56.0})));
        assertThrows(IllegalArgumentException.class,
                () -> JtsConverter.toJtsOnGlobe(new PointVo(new double[] {10.0, Double.NaN})));
    }

    @Test
    void aGeometryWiderThanTheGlobe_isRefused() {
        LineStringVo line = new LineStringVo(new double[][] {{-170.0, 0.0}, {200.0, 0.0}});
        assertThrows(IllegalArgumentException.class, () -> JtsConverter.toJtsOnGlobe(line));
    }

    // ------------------------------------------------------------------------------------------- //
    // The entities built from a value object store through it
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessagePartFeature_isStoredOnTheGlobe() {
        FeatureVo vo = new FeatureVo();
        vo.setGeometry(new PointVo(new double[] {190.0, 56.0}));
        assertEquals(-170.0, Feature.fromGeoJson(vo).getGeometry().getCoordinate().x, EXACT);

        FeatureVo north = new FeatureVo();
        north.setGeometry(new PointVo(new double[] {10.0, 95.0}));
        assertThrows(IllegalArgumentException.class, () -> Feature.fromGeoJson(north));
    }

    @Test
    void anArea_isStoredOnTheGlobe_byItsConstructorAndByItsMapper() {
        SystemAreaVo vo = new SystemAreaVo();
        vo.setGeometry(new PointVo(new double[] {190.0, 56.0}));
        assertEquals(-170.0, new Area(vo).getGeometry().getCoordinate().x, EXACT);
        assertEquals(-170.0, new AreaMapper().toEntity(vo, DataFilter.get().fields(DataFilter.GEOMETRY))
                .getGeometry().getCoordinate().x, EXACT);

        SystemAreaVo north = new SystemAreaVo();
        north.setGeometry(new PointVo(new double[] {10.0, 95.0}));
        assertThrows(IllegalArgumentException.class, () -> new Area(north));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaMapper().toEntity(north, DataFilter.get().fields(DataFilter.GEOMETRY)));
    }

    @Test
    void aChart_isStoredOnTheGlobe_byItsConstructorAndByItsMapper() {
        SystemChartVo vo = new SystemChartVo();
        vo.setGeometry(box(-190.0, 50.0, -185.0, 52.0));
        assertEquals(170.0, new Chart(vo).getGeometry().getEnvelopeInternal().getMinX(), EXACT);
        assertEquals(170.0, new ChartMapper().toEntity(vo).getGeometry().getEnvelopeInternal().getMinX(), EXACT);

        SystemChartVo north = new SystemChartVo();
        north.setGeometry(new PointVo(new double[] {10.0, 95.0}));
        assertThrows(IllegalArgumentException.class, () -> new Chart(north));
        assertThrows(IllegalArgumentException.class, () -> new ChartMapper().toEntity(north));
    }

    @Test
    void anEntityWithAGeometryOnTheGlobe_storesItUnchanged() {
        PointVo point = new PointVo(new double[] {10.25, 56.5});
        SystemAreaVo area = new SystemAreaVo();
        area.setGeometry(point);
        assertTrue(JtsConverter.toJts(point).equalsExact(new Area(area).getGeometry()));

        FeatureVo feature = new FeatureVo();
        feature.setGeometry(point);
        assertTrue(JtsConverter.toJts(point).equalsExact(Feature.fromGeoJson(feature).getGeometry()));
    }
}
