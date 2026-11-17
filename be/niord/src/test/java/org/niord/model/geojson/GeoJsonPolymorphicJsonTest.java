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
package org.niord.model.geojson;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidTypeIdException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the Jackson polymorphic JSON contract of the GeoJSON value-object model in
 * {@code org.niord.model.geojson}.
 * <p>
 * The {@link GeoJsonVo} base type is annotated with {@code @JsonTypeInfo(property = "type")}
 * and {@code @JsonSubTypes}, so the {@code "type"} discriminator drives both deserialisation
 * into the correct concrete subclass and serialisation of the discriminator. These tests pin
 * that contract using plain inline JSON strings for determinism (no time / locale / order
 * dependence) so the behaviour can be verified independently of the Quarkus runtime.
 */
public class GeoJsonPolymorphicJsonTest {

    private ObjectMapper mapper;

    @BeforeEach
    public void setUp() {
        mapper = new ObjectMapper();
    }

    // ------------------------------------------------------------------
    // Deserialisation via the abstract base type -> concrete subclass
    // ------------------------------------------------------------------

    @Test
    public void deserialise_point_viaBaseType_resolvesToPointVo() throws IOException {
        GeoJsonVo result = mapper.readValue(
                "{\"type\":\"Point\",\"coordinates\":[1.0,2.0]}", GeoJsonVo.class);

        assertTrue(result instanceof PointVo, "Point JSON must deserialise to PointVo");
        assertEquals("Point", result.getType());
        assertArrayEquals(new double[]{1.0, 2.0}, ((PointVo) result).getCoordinates(), 0.0);
    }

    @Test
    public void deserialise_lineString_viaBaseType_resolvesToLineStringVo() throws IOException {
        GeoJsonVo result = mapper.readValue(
                "{\"type\":\"LineString\",\"coordinates\":[[1.0,2.0],[3.0,4.0]]}", GeoJsonVo.class);

        assertTrue(result instanceof LineStringVo, "LineString JSON must deserialise to LineStringVo");
        double[][] coords = ((LineStringVo) result).getCoordinates();
        assertEquals(2, coords.length);
        assertArrayEquals(new double[]{1.0, 2.0}, coords[0], 0.0);
        assertArrayEquals(new double[]{3.0, 4.0}, coords[1], 0.0);
    }

    @Test
    public void deserialise_polygon_viaBaseType_resolvesToPolygonVo() throws IOException {
        GeoJsonVo result = mapper.readValue(
                "{\"type\":\"Polygon\",\"coordinates\":[[[0.0,0.0],[0.0,1.0],[1.0,1.0],[0.0,0.0]]]}",
                GeoJsonVo.class);

        assertTrue(result instanceof PolygonVo, "Polygon JSON must deserialise to PolygonVo");
        double[][][] coords = ((PolygonVo) result).getCoordinates();
        assertEquals(1, coords.length);
        assertEquals(4, coords[0].length);
        assertArrayEquals(new double[]{1.0, 1.0}, coords[0][2], 0.0);
    }

    @Test
    public void deserialise_multiPolygon_viaBaseType_resolvesToMultiPolygonVo() throws IOException {
        GeoJsonVo result = mapper.readValue(
                "{\"type\":\"MultiPolygon\",\"coordinates\":"
                        + "[[[[0.0,0.0],[0.0,1.0],[1.0,1.0],[0.0,0.0]]]]}",
                GeoJsonVo.class);

        assertTrue(result instanceof MultiPolygonVo,
                "MultiPolygon JSON must deserialise to MultiPolygonVo");
        double[][][][] coords = ((MultiPolygonVo) result).getCoordinates();
        assertEquals(1, coords.length);
        assertEquals(1, coords[0].length);
        assertArrayEquals(new double[]{0.0, 1.0}, coords[0][0][1], 0.0);
    }

    @Test
    public void deserialise_geometryCollection_viaBaseType_resolvesToGeometryCollectionVo()
            throws IOException {
        GeoJsonVo result = mapper.readValue(
                "{\"type\":\"GeometryCollection\",\"geometries\":["
                        + "{\"type\":\"Point\",\"coordinates\":[5.0,6.0]},"
                        + "{\"type\":\"Point\",\"coordinates\":[7.0,8.0]}]}",
                GeoJsonVo.class);

        assertTrue(result instanceof GeometryCollectionVo,
                "GeometryCollection JSON must deserialise to GeometryCollectionVo");
        GeometryVo[] geometries = ((GeometryCollectionVo) result).getGeometries();
        assertEquals(2, geometries.length);
        // Nested geometries must also be polymorphically resolved.
        assertTrue(geometries[0] instanceof PointVo);
        assertArrayEquals(new double[]{5.0, 6.0}, ((PointVo) geometries[0]).getCoordinates(), 0.0);
        assertArrayEquals(new double[]{7.0, 8.0}, ((PointVo) geometries[1]).getCoordinates(), 0.0);
    }

    @Test
    public void deserialise_feature_viaBaseType_resolvesToFeatureVo() throws IOException {
        GeoJsonVo result = mapper.readValue(
                "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[9.0,10.0]},"
                        + "\"properties\":{\"name\":\"buoy\"}}",
                GeoJsonVo.class);

        assertTrue(result instanceof FeatureVo, "Feature JSON must deserialise to FeatureVo");
        FeatureVo feature = (FeatureVo) result;
        // The nested geometry must be polymorphically resolved to PointVo.
        assertTrue(feature.getGeometry() instanceof PointVo);
        assertArrayEquals(new double[]{9.0, 10.0},
                ((PointVo) feature.getGeometry()).getCoordinates(), 0.0);
        assertEquals("buoy", feature.getProperties().get("name"));
    }

    @Test
    public void deserialise_featureCollection_viaBaseType_resolvesToFeatureCollectionVo()
            throws IOException {
        GeoJsonVo result = mapper.readValue(
                "{\"type\":\"FeatureCollection\",\"features\":["
                        + "{\"type\":\"Feature\",\"geometry\":"
                        + "{\"type\":\"Point\",\"coordinates\":[11.0,12.0]},\"properties\":{}}]}",
                GeoJsonVo.class);

        assertTrue(result instanceof FeatureCollectionVo,
                "FeatureCollection JSON must deserialise to FeatureCollectionVo");
        FeatureVo[] features = ((FeatureCollectionVo) result).getFeatures();
        assertEquals(1, features.length);
        assertTrue(features[0].getGeometry() instanceof PointVo);
        assertArrayEquals(new double[]{11.0, 12.0},
                ((PointVo) features[0].getGeometry()).getCoordinates(), 0.0);
    }

    @Test
    public void deserialise_viaGeometryBaseType_inheritsTypeInfoAndResolvesSubclass()
            throws IOException {
        // GeometryVo has no @JsonTypeInfo of its own; it inherits the annotation from GeoJsonVo.
        GeometryVo result = mapper.readValue(
                "{\"type\":\"LineString\",\"coordinates\":[[20.0,21.0],[22.0,23.0]]}",
                GeometryVo.class);

        assertTrue(result instanceof LineStringVo,
                "Geometry JSON must deserialise to LineStringVo via inherited @JsonTypeInfo");
        double[][] coords = ((LineStringVo) result).getCoordinates();
        assertArrayEquals(new double[]{20.0, 21.0}, coords[0], 0.0);
        assertArrayEquals(new double[]{22.0, 23.0}, coords[1], 0.0);
    }

    // ------------------------------------------------------------------
    // Serialisation emits the discriminator even though getType() is @JsonIgnore
    // ------------------------------------------------------------------

    @Test
    public void serialise_point_emitsTypeDiscriminator() throws JacksonException {
        String json = mapper.writeValueAsString(new PointVo(new double[]{1.0, 2.0}));

        // getType() is @JsonIgnore, but @JsonTypeInfo re-adds "type" as the discriminator.
        assertTrue(json.contains("\"type\":\"Point\""),
                "Serialised Point must contain the type discriminator, was: " + json);
        assertTrue(json.contains("\"coordinates\":[1.0,2.0]"),
                "Serialised Point must contain coordinates, was: " + json);
    }

    @Test
    public void serialise_featureCollection_emitsCollectionAndNestedDiscriminators()
            throws JacksonException {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(new PointVo(new double[]{3.0, 4.0}));
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{feature});

        String json = mapper.writeValueAsString(fc);

        assertTrue(json.contains("\"type\":\"FeatureCollection\""),
                "Must contain FeatureCollection discriminator, was: " + json);
        assertTrue(json.contains("\"type\":\"Feature\""),
                "Must contain Feature discriminator, was: " + json);
        assertTrue(json.contains("\"type\":\"Point\""),
                "Must contain nested Point discriminator, was: " + json);
    }

    @Test
    public void serialise_omitsNullFields() throws JacksonException {
        // IJsonSerializable declares @JsonInclude(NON_NULL): a Point with no crs/bbox
        // must not emit those keys.
        String json = mapper.writeValueAsString(new PointVo(new double[]{1.0, 2.0}));

        assertFalse(json.contains("\"crs\""), "Null crs must be omitted, was: " + json);
        assertFalse(json.contains("\"bbox\""), "Null bbox must be omitted, was: " + json);
    }

    // ------------------------------------------------------------------
    // Round-trips
    // ------------------------------------------------------------------

    @Test
    public void roundTrip_point_preservesTypeAndCoordinates() throws IOException {
        PointVo original = new PointVo(new double[]{12.5, -7.25});

        String json = mapper.writeValueAsString(original);
        GeoJsonVo rebuilt = mapper.readValue(json, GeoJsonVo.class);

        assertTrue(rebuilt instanceof PointVo, "Round-tripped object must remain a PointVo");
        assertEquals("Point", rebuilt.getType());
        assertArrayEquals(new double[]{12.5, -7.25}, ((PointVo) rebuilt).getCoordinates(), 0.0);
    }

    @Test
    public void roundTrip_featureCollection_preservesStructureGeometryAndProperty()
            throws IOException {
        PointVo point = new PointVo(new double[]{55.0, 10.0});
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(point);
        feature.getProperties().put("name", "lighthouse");
        FeatureCollectionVo original = new FeatureCollectionVo();
        original.setFeatures(new FeatureVo[]{feature});

        String json = mapper.writeValueAsString(original);
        GeoJsonVo rebuiltBase = mapper.readValue(json, GeoJsonVo.class);

        assertTrue(rebuiltBase instanceof FeatureCollectionVo);
        FeatureCollectionVo rebuilt = (FeatureCollectionVo) rebuiltBase;
        assertEquals(1, rebuilt.getFeatures().length);
        FeatureVo rebuiltFeature = rebuilt.getFeatures()[0];
        assertTrue(rebuiltFeature.getGeometry() instanceof PointVo,
                "Geometry class must survive the round-trip");
        assertArrayEquals(new double[]{55.0, 10.0},
                ((PointVo) rebuiltFeature.getGeometry()).getCoordinates(), 0.0);
        assertEquals("lighthouse", rebuiltFeature.getProperties().get("name"));
    }

    // ------------------------------------------------------------------
    // bbox & crs survive a round-trip
    // ------------------------------------------------------------------

    @Test
    public void deserialise_bboxField_isPopulatedOnGeometry() throws IOException {
        GeoJsonVo result = mapper.readValue(
                "{\"type\":\"Point\",\"coordinates\":[1.0,2.0],"
                        + "\"bbox\":[1.0,2.0,3.0,4.0]}",
                GeoJsonVo.class);

        assertTrue(result instanceof PointVo);
        assertArrayEquals(new double[]{1.0, 2.0, 3.0, 4.0}, result.getBbox(), 0.0);
    }

    @Test
    public void roundTrip_bboxAndCrs_arePreserved() throws IOException {
        PointVo original = new PointVo(new double[]{1.0, 2.0});
        original.setBbox(new double[]{10.0, 20.0, 30.0, 40.0});
        CrsVo crs = new CrsVo();
        crs.setType("name");
        crs.getProperties().setProperty("name", "EPSG:4326");
        original.setCrs(crs);

        String json = mapper.writeValueAsString(original);
        GeoJsonVo rebuilt = mapper.readValue(json, GeoJsonVo.class);

        assertTrue(rebuilt instanceof PointVo);
        assertArrayEquals(new double[]{10.0, 20.0, 30.0, 40.0}, rebuilt.getBbox(), 0.0);
        assertNotNull(rebuilt.getCrs(), "crs must survive the round-trip");
        assertEquals("name", rebuilt.getCrs().getType());
        assertEquals("EPSG:4326", rebuilt.getCrs().getProperties().getProperty("name"));
    }

    // ------------------------------------------------------------------
    // Unknown discriminator value
    // ------------------------------------------------------------------

    @Test
    public void deserialise_unknownType_throwsInvalidTypeIdException() throws IOException {
        // "Banana" is not one of the registered @JsonSubTypes names, so resolution must fail.
        assertThrows(InvalidTypeIdException.class, () -> mapper.readValue("{\"type\":\"Banana\"}", GeoJsonVo.class));
    }

    @Test
    public void deserialise_unknownType_isJacksonException() {
        // Pin the exception hierarchy: the thrown error is a (now unchecked) JacksonException, which is
        // the framework-neutral contract a future Spring/Jackson migration can still rely on.
        try {
            mapper.readValue("{\"type\":\"Banana\"}", GeoJsonVo.class);
            fail("Expected a JacksonException for an unknown GeoJSON type");
        } catch (JacksonException expected) {
            assertTrue(expected.getMessage().contains("Banana"),
                    "Message should mention the offending discriminator value, was: "
                            + expected.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Real-world resource parsing (mirrors GeoJsonTest#loadGeoJson)
    // ------------------------------------------------------------------

    @Test
    public void deserialise_classpathResource_dkJson_isFeatureCollection() throws IOException {
        GeoJsonVo result = mapper.readValue(getClass().getResourceAsStream("/dk.json"), GeoJsonVo.class);

        assertTrue(result instanceof FeatureCollectionVo,
                "/dk.json must deserialise to a FeatureCollectionVo");
        FeatureCollectionVo fc = (FeatureCollectionVo) result;
        assertEquals("FeatureCollection", fc.getType());
        assertNotNull(fc.getFeatures(), "dk.json must contain features");
        assertTrue(fc.getFeatures().length >= 1, "dk.json must contain at least one feature");
        // The first feature's geometry is a MultiPolygon (per the resource contents).
        assertTrue(fc.getFeatures()[0].getGeometry() instanceof MultiPolygonVo,
                "First feature geometry must be a MultiPolygonVo");
    }
}
