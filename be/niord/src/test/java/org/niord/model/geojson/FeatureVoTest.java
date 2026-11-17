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

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FeatureVo}, the GeoJSON "Feature" value object that forms part of
 * the REST/JSON API contract.
 *
 * These tests verify business behaviour (type discriminator, property defaults, id handling,
 * geometry wiring, coordinate traversal and the inherited bbox computation) using only the
 * public/package-accessible API, so they survive a later Spring migration. The only third-party
 * dependency used is Jackson's {@link ObjectMapper}, which is already a production dependency and
 * is used by the existing {@code org.niord.core.GeoJsonTest}.
 */
public class FeatureVoTest {

    /** Collects every coordinate pair visited so tests can assert exact count and contents. */
    private static List<double[]> collect(GeoJsonVo geoJson) {
        List<double[]> visited = new ArrayList<>();
        Consumer<double[]> handler = xy -> visited.add(xy);
        geoJson.visitCoordinates(handler);
        return visited;
    }

    // ------------------------------------------------------------------
    // Type discriminator
    // ------------------------------------------------------------------

    @Test
    public void newFeature_hasFeatureType() {
        assertEquals("Feature", new FeatureVo().getType());
    }

    @Test
    public void serialization_emitsFeatureTypeDiscriminator() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(new FeatureVo());
        // The @JsonTypeInfo discriminator must be present in the JSON contract.
        assertTrue(json.contains("\"type\":\"Feature\""),
                "Expected a \"type\":\"Feature\" discriminator in: " + json);
    }

    @Test
    public void deserialization_byteTypeField_yieldsFeatureVoSubclass() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[5.0,6.0]}}";
        GeoJsonVo result = mapper.readValue(json, GeoJsonVo.class);
        assertTrue(result instanceof FeatureVo, "Expected polymorphic deserialization into FeatureVo");
        assertEquals("Feature", result.getType());
        // And the nested geometry must resolve to the concrete PointVo subclass.
        FeatureVo feature = (FeatureVo) result;
        assertTrue(feature.getGeometry() instanceof PointVo);
        assertArrayEquals(new double[]{5.0, 6.0},
                ((PointVo) feature.getGeometry()).getCoordinates(), 0.0);
    }

    // ------------------------------------------------------------------
    // properties map
    // ------------------------------------------------------------------

    @Test
    public void properties_defaultsToNonNullEmptyMutableMap() {
        FeatureVo feature = new FeatureVo();
        Map<String, Object> props = feature.getProperties();
        assertNotNull(props, "properties must default to a non-null map");
        assertTrue(props.isEmpty(), "properties must default to an empty map");

        // The default map is mutable: a put must be observable through the getter.
        props.put("name", "Goat-Ice");
        assertEquals("Goat-Ice", feature.getProperties().get("name"));
        assertEquals(1, feature.getProperties().size());
    }

    @Test
    public void setProperties_replacesTheMap() {
        FeatureVo feature = new FeatureVo();
        feature.getProperties().put("old", "value");

        Map<String, Object> replacement = new HashMap<>();
        replacement.put("restriction", "affected");
        feature.setProperties(replacement);

        Map<String, Object> result = feature.getProperties();
        assertSame(replacement, result, "getProperties must return the exact map that was set");
        assertEquals("affected", result.get("restriction"));
        assertFalse(result.containsKey("old"), "the original key must be gone after replacement");
    }

    // ------------------------------------------------------------------
    // id (declared as Object, so any type is accepted)
    // ------------------------------------------------------------------

    @Test
    public void id_defaultsToNull() {
        assertNull(new FeatureVo().getId());
    }

    @Test
    public void setId_acceptsStringValue() {
        FeatureVo feature = new FeatureVo();
        feature.setId("abc");
        assertEquals("abc", feature.getId());
    }

    @Test
    public void setId_acceptsIntegerValue() {
        FeatureVo feature = new FeatureVo();
        feature.setId(42);
        assertEquals(Integer.valueOf(42), feature.getId());
    }

    // ------------------------------------------------------------------
    // geometry get/set
    // ------------------------------------------------------------------

    @Test
    public void geometry_defaultsToNull() {
        assertNull(new FeatureVo().getGeometry());
    }

    @Test
    public void setGeometry_returnsSameInstance() {
        FeatureVo feature = new FeatureVo();
        PointVo point = new PointVo(new double[]{5, 6});
        feature.setGeometry(point);

        assertSame(point, feature.getGeometry());
        assertTrue(feature.getGeometry() instanceof PointVo);
        assertArrayEquals(new double[]{5, 6},
                ((PointVo) feature.getGeometry()).getCoordinates(), 0.0);
    }

    // ------------------------------------------------------------------
    // visitCoordinates delegation
    // ------------------------------------------------------------------

    @Test
    public void visitCoordinates_delegatesToPointGeometry() {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(new PointVo(new double[]{7, 8}));

        List<double[]> visited = collect(feature);

        assertEquals(1, visited.size(),
                "a single point geometry must yield exactly one visited coordinate");
        assertArrayEquals(new double[]{7, 8}, visited.get(0), 0.0);
    }

    @Test
    public void visitCoordinates_delegatesToLineStringGeometry() {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(new LineStringVo(new double[][]{{1, 2}, {3, 4}, {5, 6}}));

        List<double[]> visited = collect(feature);

        assertEquals(3, visited.size());
        assertArrayEquals(new double[]{1, 2}, visited.get(0), 0.0);
        assertArrayEquals(new double[]{3, 4}, visited.get(1), 0.0);
        assertArrayEquals(new double[]{5, 6}, visited.get(2), 0.0);
    }

    @Test
    public void visitCoordinates_withNullGeometry_isSafeNoOp() {
        FeatureVo feature = new FeatureVo();
        assertNull(feature.getGeometry(), "precondition: geometry is null");

        List<double[]> visited = collect(feature);

        // No NPE, and the handler is never invoked.
        assertTrue(visited.isEmpty(), "a null geometry must produce no visited coordinates");
    }

    // ------------------------------------------------------------------
    // inherited computeBBox / computeCenter
    // ------------------------------------------------------------------

    @Test
    public void computeBBox_overLineStringGeometry_returnsLineBounds() {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(new LineStringVo(new double[][]{
                {10, 56}, {12, 55}, {11, 58}
        }));

        double[] bbox = feature.computeBBox();

        // {minX, minY, maxX, maxY}
        assertArrayEquals(new double[]{10, 55, 12, 58}, bbox, 0.0);
    }

    @Test
    public void computeCenter_overLineStringGeometry_returnsMidpoint() {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(new LineStringVo(new double[][]{
                {10, 56}, {12, 55}, {11, 58}
        }));

        double[] center = feature.computeCenter();

        // center == {(minX+maxX)/2, (minY+maxY)/2} == {(10+12)/2, (55+58)/2}
        assertArrayEquals(new double[]{11.0, 56.5}, center, 0.0);
    }

    /**
     * Documents current (surprising) behaviour: {@link GeoJsonVo#computeBBox()} has no
     * empty-geometry guard (the source carries a "TODO: Naive implementation" comment).
     * For a Feature with a null geometry, no coordinates are visited, so the sentinel
     * initial values leak out: {minX, minY, maxX, maxY} = {Integer.MAX_VALUE, Integer.MAX_VALUE,
     * Integer.MIN_VALUE, Integer.MIN_VALUE}. This is almost certainly not a sensible bbox and is
     * pinned here so a future fix is detected. See /docs/potential-errors.md.
     */
    @Test
    public void computeBBox_withNullGeometry_returnsSentinelValues_documentsBug() {
        double[] bbox = new FeatureVo().computeBBox();
        assertArrayEquals(new double[]{
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE
        }, bbox, 0.0);
    }

    /**
     * Companion to {@link #computeBBox_withNullGeometry_returnsSentinelValues_documentsBug()}:
     * because computeBBox returns sentinel bounds for an empty geometry, computeCenter derives a
     * nonsensical center of {-0.5, -0.5}. Pinned to document the current behaviour.
     * See /docs/potential-errors.md.
     */
    @Test
    public void computeCenter_withNullGeometry_returnsNonsensicalCenter_documentsBug() {
        double[] center = new FeatureVo().computeCenter();
        assertArrayEquals(new double[]{-0.5, -0.5}, center, 0.0);
    }
}
