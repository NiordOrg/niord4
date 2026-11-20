/*
 * Copyright 2026 Danish Maritime Authority.
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
import org.niord.core.area.Area;
import org.niord.core.area.AreaMapper;
import org.niord.core.area.vo.SystemAreaVo;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartMapper;
import org.niord.core.chart.vo.SystemChartVo;
import org.niord.model.DataFilter;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.GeoJsonVo;
import org.niord.model.geojson.GeometryVo;
import org.niord.model.geojson.LineStringVo;
import org.niord.model.geojson.MultiLineStringVo;
import org.niord.model.geojson.MultiPointVo;
import org.niord.model.geojson.MultiPolygonVo;
import org.niord.model.geojson.PointVo;
import org.niord.model.geojson.PolygonVo;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CG10: a coordinate a client sends as JSON {@code null} is read as not a number, so the save refuses it as it
 * refuses any coordinate that is not a number ({@link JtsConverter#toJtsOnGlobe}); it was read as 0 and stored as
 * latitude or longitude 0. Every geometry type, read with the mapper the REST layer is built like, and the places
 * that build an entity from a value object: a message part's feature, an area and a chart, by their constructors
 * and by their mappers.
 */
public class NullCoordinateJsonTest {

    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    /** Each geometry type, with one coordinate sent as null, in the middle of a ring so the ring stays closed */
    private static final Map<String, String> WITH_A_NULL = new LinkedHashMap<>();

    static {
        WITH_A_NULL.put("Point", "{\"type\":\"Point\",\"coordinates\":[10.5,null]}");
        WITH_A_NULL.put("MultiPoint", "{\"type\":\"MultiPoint\",\"coordinates\":[[10.5,55.0],[null,55.5]]}");
        WITH_A_NULL.put("LineString", "{\"type\":\"LineString\",\"coordinates\":[[10.5,55.0],[11.0,null]]}");
        WITH_A_NULL.put("MultiLineString",
                "{\"type\":\"MultiLineString\",\"coordinates\":[[[10.5,55.0],[11.0,55.5]],[[12.0,null],[12.5,56.0]]]}");
        WITH_A_NULL.put("Polygon",
                "{\"type\":\"Polygon\",\"coordinates\":[[[10.0,55.0],[11.0,null],[11.0,56.0],[10.0,55.0]]]}");
        WITH_A_NULL.put("MultiPolygon", "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[10.0,55.0],[11.0,55.0],"
                + "[11.0,56.0],[10.0,55.0]]],[[[12.0,55.0],[null,55.0],[13.0,56.0],[12.0,55.0]]]]}");
        WITH_A_NULL.put("GeometryCollection", "{\"type\":\"GeometryCollection\",\"geometries\":["
                + "{\"type\":\"Point\",\"coordinates\":[10.5,55.0]},{\"type\":\"Point\",\"coordinates\":[null,55.0]}]}");
    }

    private static GeometryVo read(String json) throws Exception {
        return (GeometryVo) MAPPER.readValue(json, GeoJsonVo.class);
    }

    @Test
    void aNullCoordinate_isReadAsNotANumber_inEveryGeometryType() throws Exception {
        assertTrue(Double.isNaN(((PointVo) read(WITH_A_NULL.get("Point"))).getCoordinates()[1]));
        assertTrue(Double.isNaN(((MultiPointVo) read(WITH_A_NULL.get("MultiPoint"))).getCoordinates()[1][0]));
        assertTrue(Double.isNaN(((LineStringVo) read(WITH_A_NULL.get("LineString"))).getCoordinates()[1][1]));
        assertTrue(Double.isNaN(
                ((MultiLineStringVo) read(WITH_A_NULL.get("MultiLineString"))).getCoordinates()[1][0][1]));
        assertTrue(Double.isNaN(((PolygonVo) read(WITH_A_NULL.get("Polygon"))).getCoordinates()[0][1][1]));
        assertTrue(Double.isNaN(
                ((MultiPolygonVo) read(WITH_A_NULL.get("MultiPolygon"))).getCoordinates()[1][0][1][0]));
    }

    @Test
    void aNullCoordinate_isRefusedOnSave_inEveryGeometryType() throws Exception {
        for (Map.Entry<String, String> e : WITH_A_NULL.entrySet()) {
            GeometryVo vo = read(e.getValue());
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> JtsConverter.toJtsOnGlobe(vo), e.getKey());
            assertTrue(refused.getMessage().contains("is not a number"), e.getKey() + ": " + refused.getMessage());
        }
    }

    @Test
    void aNullCoordinate_onARingsFirstAndLastPosition_isRefusedAsNotANumber() throws Exception {
        // The ring's ends are not numbers and never compare equal, so JTS alone would refuse the ring as not closed
        for (String json : new String[] {
                "{\"type\":\"Polygon\",\"coordinates\":[[[null,55.0],[11.0,55.0],[11.0,56.0],[null,55.0]]]}",
                "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[10.0,null],[11.0,55.0],[11.0,56.0],[10.0,null]]]]}"}) {
            GeometryVo vo = read(json);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> JtsConverter.toJtsOnGlobe(vo), json);
            assertTrue(refused.getMessage().contains("is not a number"), json + ": " + refused.getMessage());
        }
    }

    @Test
    void theOtherValues_areReadAsBefore() throws Exception {
        assertArrayEquals(new double[] {10.5, 55.0},
                ((PointVo) read("{\"type\":\"Point\",\"coordinates\":[10.5,55]}")).getCoordinates(), 0.0);
        PolygonVo polygon = (PolygonVo) read(
                "{\"type\":\"Polygon\",\"coordinates\":[[[10,55],[11,55],[11,56],[10,55]]]}");
        assertArrayEquals(new double[] {11.0, 56.0}, polygon.getCoordinates()[0][2], 0.0);
        assertNull(((PointVo) read("{\"type\":\"Point\",\"coordinates\":null}")).getCoordinates());
        assertNull(((LineStringVo) read("{\"type\":\"LineString\",\"coordinates\":[[10.5,55.0],null]}"))
                .getCoordinates()[1]);
        assertEquals(0, ((MultiPolygonVo) read("{\"type\":\"MultiPolygon\",\"coordinates\":[]}"))
                .getCoordinates().length);
    }

    @Test
    void aMessagePartFeature_withANullCoordinate_isRefused() throws Exception {
        FeatureCollectionVo fc = (FeatureCollectionVo) MAPPER.readValue("{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"properties\":{},\"geometry\":" + WITH_A_NULL.get("Point") + "}]}",
                GeoJsonVo.class);
        FeatureVo feature = fc.getFeatures()[0];
        assertThrows(IllegalArgumentException.class, () -> Feature.fromGeoJson(feature));
    }

    @Test
    void anArea_withANullCoordinate_isRefused_byItsConstructorAndByItsMapper() throws Exception {
        SystemAreaVo vo = MAPPER.readValue("{\"geometry\":" + WITH_A_NULL.get("Polygon") + "}", SystemAreaVo.class);
        assertInstanceOf(PolygonVo.class, vo.getGeometry());
        assertThrows(IllegalArgumentException.class, () -> new Area(vo));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaMapper().toEntity(vo, DataFilter.get().fields(DataFilter.GEOMETRY)));
    }

    @Test
    void aChart_withANullCoordinate_isRefused_byItsConstructorAndByItsMapper() throws Exception {
        SystemChartVo vo = MAPPER.readValue("{\"geometry\":" + WITH_A_NULL.get("MultiPolygon") + "}",
                SystemChartVo.class);
        assertInstanceOf(MultiPolygonVo.class, vo.getGeometry());
        assertThrows(IllegalArgumentException.class, () -> new Chart(vo));
        assertThrows(IllegalArgumentException.class, () -> new ChartMapper().toEntity(vo));
    }
}
