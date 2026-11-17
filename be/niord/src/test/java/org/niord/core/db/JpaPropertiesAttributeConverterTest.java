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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JpaPropertiesAttributeConverter}.
 *
 * The converter is pure logic backed by a Jackson {@link tools.jackson.databind.ObjectMapper};
 * it can be instantiated directly with no database or Quarkus context. These tests verify the two
 * documented directions of the conversion:
 *
 * <ul>
 *     <li>{@code convertToDatabaseColumn(Map)} -> JSON {@code String} (the database column value)</li>
 *     <li>{@code convertToEntityAttribute(String)} -> {@code Map<String,Object>} (the entity attribute)</li>
 * </ul>
 *
 * Particular attention is paid to the null/empty/malformed contracts and to the concrete Java types
 * produced when JSON numbers are deserialized (Jackson maps untyped integral numbers to {@link Integer}
 * and fractional numbers to {@link Double}). These tests deliberately avoid any framework-specific
 * behavior so they survive a future migration to Spring.
 */
public class JpaPropertiesAttributeConverterTest {

    private JpaPropertiesAttributeConverter converter;

    @BeforeEach
    public void setUp() {
        converter = new JpaPropertiesAttributeConverter();
    }

    // ------------------------------------------------------------------
    // convertToDatabaseColumn  (Map -> JSON String)
    // ------------------------------------------------------------------

    @Test
    public void convertToDatabaseColumnNullReturnsNull() {
        // A null map must map to a null column value, not to the literal string "null".
        assertNull(converter.convertToDatabaseColumn(null));
    }

    @Test
    public void convertToDatabaseColumnEmptyMapProducesEmptyJsonObject() {
        assertEquals("{}", converter.convertToDatabaseColumn(new HashMap<>()));
    }

    @Test
    public void convertToDatabaseColumnSingleEntryProducesExactJson() {
        // A single-entry map has a deterministic serialization regardless of map iteration order.
        Map<String, Object> map = new HashMap<>();
        map.put("k", "v");
        assertEquals("{\"k\":\"v\"}", converter.convertToDatabaseColumn(map));
    }

    @Test
    public void convertToDatabaseColumnIntegerValueIsBare() {
        Map<String, Object> map = new HashMap<>();
        map.put("count", 42);
        assertEquals("{\"count\":42}", converter.convertToDatabaseColumn(map));
    }

    @Test
    public void convertToDatabaseColumnBooleanValueIsBare() {
        Map<String, Object> map = new HashMap<>();
        map.put("flag", Boolean.TRUE);
        assertEquals("{\"flag\":true}", converter.convertToDatabaseColumn(map));
    }

    // ------------------------------------------------------------------
    // convertToEntityAttribute  (JSON String -> Map)
    // ------------------------------------------------------------------

    @Test
    public void convertToEntityAttributeNullReturnsNewEmptyMutableMap() {
        Map<String, Object> result = converter.convertToEntityAttribute(null);

        // Contract: null input yields a fresh, empty, mutable HashMap (never null).
        assertTrue(result.isEmpty(), "null input must yield an empty map");

        // Verify it is genuinely mutable by mutating it; a fixed/immutable map would throw here.
        result.put("added", "ok");
        assertEquals("ok", result.get("added"), "added");
        assertEquals(1, result.size());
    }

    @Test
    public void convertToEntityAttributeParsesIntegerAndStringValues() {
        Map<String, Object> result = converter.convertToEntityAttribute("{\"a\":1,\"b\":\"x\"}");

        assertEquals(2, result.size());
        // Jackson deserializes untyped integral JSON numbers as Integer.
        assertEquals(Integer.valueOf(1), result.get("a"));
        assertEquals("x", result.get("b"));
    }

    @Test
    public void convertToEntityAttributeEmptyJsonObjectProducesEmptyMap() {
        Map<String, Object> result = converter.convertToEntityAttribute("{}");
        assertTrue(result.isEmpty(), "'{}' must produce an empty map");
    }

    @Test
    public void convertToEntityAttributeMalformedJsonReturnsEmptyMapAndDoesNotThrow() {
        // Malformed input must be swallowed (IOException catch) and yield an empty map, not propagate.
        Map<String, Object> result = converter.convertToEntityAttribute("{not json");
        assertTrue(result.isEmpty(), "malformed JSON must yield an empty map");
    }

    // ------------------------------------------------------------------
    // Round trip  (Map -> String -> Map)
    // ------------------------------------------------------------------

    @Test
    public void roundTripPreservesScalarsNestedMapAndList() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("inner", "deep");

        List<Object> list = new ArrayList<>();
        list.add("one");
        list.add(2);

        Map<String, Object> original = new LinkedHashMap<>();
        original.put("str", "hello");
        original.put("int", 7);
        original.put("dbl", 1.5);
        original.put("bool", Boolean.FALSE);
        original.put("map", nested);
        original.put("list", list);

        String json = converter.convertToDatabaseColumn(original);
        Map<String, Object> restored = converter.convertToEntityAttribute(json);

        assertEquals(6, restored.size());
        assertEquals("hello", restored.get("str"));
        // Integral JSON numbers come back as Integer, fractional ones as Double.
        assertEquals(Integer.valueOf(7), restored.get("int"));
        assertEquals(Double.valueOf(1.5), restored.get("dbl"));
        assertEquals(Boolean.FALSE, restored.get("bool"));

        // The nested map survives the round trip as an equal map.
        assertEquals(nested, restored.get("map"));

        // The list survives, with its integral element re-typed to Integer (equal to the original).
        List<?> restoredList = (List<?>) restored.get("list");
        assertEquals(2, restoredList.size());
        assertEquals("one", restoredList.get(0));
        assertEquals(Integer.valueOf(2), restoredList.get(1));
    }

    @Test
    public void roundTripOfEmptyMapYieldsEqualEmptyMap() {
        Map<String, Object> original = new HashMap<>();
        String json = converter.convertToDatabaseColumn(original);
        Map<String, Object> restored = converter.convertToEntityAttribute(json);
        assertEquals(original, restored);
        assertTrue(restored.isEmpty());
    }

    @Test
    public void roundTripFractionalNumberDeserializesAsDouble() {
        // Pins the documented Jackson behavior: untyped fractional numbers come back as Double,
        // not as the original Float/BigDecimal type.
        Map<String, Object> original = new HashMap<>();
        original.put("pi", 3.14);

        Map<String, Object> restored = converter.convertToEntityAttribute(
                converter.convertToDatabaseColumn(original));

        Object value = restored.get("pi");
        assertEquals(Double.class, value.getClass());
        assertEquals(Double.valueOf(3.14), value);
    }
}
