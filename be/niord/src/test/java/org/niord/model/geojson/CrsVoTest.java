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

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CrsVo} (GeoJson Coordinate Reference System).
 *
 * Covers the type get/set contract, the empty-by-default {@link Properties}
 * collection, property round-trips, replacing the properties object, and the
 * Jackson JSON serialization/deserialization contract (REST/JSON API shape).
 *
 * Framework-neutral: only plain JUnit 4 assertions and Jackson's ObjectMapper
 * (already a production dependency) are used, so these tests survive a future
 * Spring migration.
 */
public class CrsVoTest {

    // ---------------------------------------------------------------------
    // type get/set
    // ---------------------------------------------------------------------

    @Test
    public void typeIsNullByDefault() {
        CrsVo crs = new CrsVo();
        assertNull(crs.getType(), "type must be null before being set");
    }

    @Test
    public void setTypeIsReturnedByGetType() {
        CrsVo crs = new CrsVo();
        crs.setType("name");
        assertEquals("name", crs.getType());
    }

    @Test
    public void setTypeCanBeOverwritten() {
        CrsVo crs = new CrsVo();
        crs.setType("name");
        crs.setType("link");
        assertEquals("link", crs.getType());
    }

    @Test
    public void typeCanBeSetBackToNull() {
        CrsVo crs = new CrsVo();
        crs.setType("name");
        crs.setType(null);
        assertNull(crs.getType());
    }

    // ---------------------------------------------------------------------
    // properties default + round-trip
    // ---------------------------------------------------------------------

    @Test
    public void propertiesDefaultToNonNullEmptyProperties() {
        CrsVo crs = new CrsVo();
        Properties props = crs.getProperties();
        assertNotNull(props, "properties must default to a non-null instance");
        assertTrue(props.isEmpty(), "properties must default to empty");
        assertEquals(0, props.size());
    }

    @Test
    public void setPropertyRoundTripsThroughDefaultProperties() {
        CrsVo crs = new CrsVo();
        crs.getProperties().setProperty("name", "urn:ogc:def:crs:EPSG::4326");
        assertEquals("urn:ogc:def:crs:EPSG::4326",
                crs.getProperties().getProperty("name"));
        assertEquals(1, crs.getProperties().size());
    }

    @Test
    public void getPropertyReturnsNullForUnknownKey() {
        CrsVo crs = new CrsVo();
        assertNull(crs.getProperties().getProperty("missing"));
    }

    @Test
    public void setPropertiesReplacesTheEntireCollection() {
        CrsVo crs = new CrsVo();
        // Seed the default collection so we can prove it was replaced, not merged.
        crs.getProperties().setProperty("old", "value");

        Properties replacement = new Properties();
        replacement.setProperty("name", "urn:ogc:def:crs:OGC:1.3:CRS84");
        crs.setProperties(replacement);

        assertSame(replacement, crs.getProperties(),
                "getProperties must return the exact replacement instance");
        assertNull(crs.getProperties().getProperty("old"),
                "the old key must be gone after replacement");
        assertEquals("urn:ogc:def:crs:OGC:1.3:CRS84",
                crs.getProperties().getProperty("name"));
        assertEquals(1, crs.getProperties().size());
    }

    // ---------------------------------------------------------------------
    // JSON serialization (REST/JSON API contract)
    // ---------------------------------------------------------------------

    @Test
    public void serializesTypeAndPropertiesToJson() throws Exception {
        CrsVo crs = new CrsVo();
        crs.setType("name");
        crs.getProperties().setProperty("name", "urn:ogc:def:crs:EPSG::4326");

        String json = new ObjectMapper().writeValueAsString(crs);

        assertTrue(json.contains("\"type\":\"name\""),
                "JSON must contain the type discriminator, was: " + json);
        // Properties serialises as a nested JSON object.
        assertTrue(json.contains("\"name\":\"urn:ogc:def:crs:EPSG::4326\""),
                "JSON must contain the nested property value, was: " + json);
    }

    // ---------------------------------------------------------------------
    // JSON deserialization (REST/JSON API contract)
    // ---------------------------------------------------------------------

    @Test
    public void deserializesTypeAndPropertiesFromJson() throws Exception {
        String json = "{\"type\":\"name\",\"properties\":"
                + "{\"name\":\"urn:ogc:def:crs:EPSG::4326\"}}";

        CrsVo crs = new ObjectMapper().readValue(json, CrsVo.class);

        assertEquals("name", crs.getType());
        assertEquals("urn:ogc:def:crs:EPSG::4326",
                crs.getProperties().getProperty("name"));
        assertEquals(1, crs.getProperties().size());
    }

    @Test
    public void jsonRoundTripPreservesTypeAndProperties() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        CrsVo original = new CrsVo();
        original.setType("name");
        original.getProperties().setProperty("name", "urn:ogc:def:crs:EPSG::4326");

        String json = mapper.writeValueAsString(original);
        CrsVo restored = mapper.readValue(json, CrsVo.class);

        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getProperties().getProperty("name"),
                restored.getProperties().getProperty("name"));
        assertEquals(original.getProperties().size(),
                restored.getProperties().size());
    }

    @Test
    public void deserializesEmptyPropertiesObjectAsEmptyProperties() throws Exception {
        String json = "{\"type\":\"name\",\"properties\":{}}";

        CrsVo crs = new ObjectMapper().readValue(json, CrsVo.class);

        assertEquals("name", crs.getType());
        assertNotNull(crs.getProperties());
        assertTrue(crs.getProperties().isEmpty(),
                "an empty JSON properties object must yield empty Properties");
    }
}
