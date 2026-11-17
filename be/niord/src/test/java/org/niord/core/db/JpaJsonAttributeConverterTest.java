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

package org.niord.core.db;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JpaJsonAttributeConverter}.
 *
 * The converter wraps a Jackson {@link tools.jackson.databind.ObjectMapper} to (de-)serialize
 * an arbitrary {@code Object} to and from a JSON {@code String} column. These tests exercise the
 * observable behavior of both directions of the conversion: exact JSON output produced for the
 * database column, the concrete runtime types Jackson reconstructs (LinkedHashMap / ArrayList /
 * boxed scalars) when reading back into {@code Object}, the documented null handling, and the
 * fail-soft error handling (malformed JSON / unserializable value -> {@code null} instead of
 * propagating the exception).
 *
 * The converter is instantiated directly; no JPA/Quarkus context is required, so these tests are
 * framework-neutral and survive a future Spring migration.
 */
public class JpaJsonAttributeConverterTest {

    private final JpaJsonAttributeConverter converter = new JpaJsonAttributeConverter();

    // ------------------------------------------------------------------
    // Null handling
    // ------------------------------------------------------------------

    @Test
    public void convertToDatabaseColumnNullReturnsNull() {
        assertNull(converter.convertToDatabaseColumn(null));
    }

    /**
     * A null DB value is converted back to a null attribute. Note this is intentionally
     * asymmetric with {@link JpaPropertiesAttributeConverter#convertToEntityAttribute(String)},
     * which returns an empty {@code HashMap} for a null input rather than {@code null}.
     */
    @Test
    public void convertToEntityAttributeNullReturnsNull() {
        assertNull(converter.convertToEntityAttribute(null));
    }

    /**
     * Pins the documented asymmetry between the JSON converter (null -> null) and the
     * sibling Properties converter (null -> empty map) so the contrast is explicit and
     * a future change to either side is caught.
     */
    @Test
    public void nullHandlingDiffersFromPropertiesConverter_documentsBehavior() {
        JpaPropertiesAttributeConverter propsConverter = new JpaPropertiesAttributeConverter();

        // JSON converter: null in -> null out
        assertNull(converter.convertToEntityAttribute(null),
                "JSON converter must map null -> null");

        // Properties converter: null in -> empty map out (NOT null)
        Map<String, Object> props = propsConverter.convertToEntityAttribute(null);
        assertEquals(new LinkedHashMap<String, Object>(), props,
                "Properties converter must map null -> empty map");
    }

    // ------------------------------------------------------------------
    // Round-trips of containers
    // ------------------------------------------------------------------

    @Test
    public void mapRoundTripProducesLinkedHashMapWithSameContents() {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("name", "Alice");
        original.put("age", 30);
        original.put("active", true);

        String json = converter.convertToDatabaseColumn(original);
        // Insertion order is preserved by the LinkedHashMap, so the JSON is deterministic.
        assertEquals("{\"name\":\"Alice\",\"age\":30,\"active\":true}", json);

        Object restored = converter.convertToEntityAttribute(json);
        // Jackson reconstructs a JSON object into a LinkedHashMap when the target type is Object.
        assertTrue(restored instanceof LinkedHashMap,
                "Expected a LinkedHashMap but got " + restored.getClass());

        @SuppressWarnings("unchecked")
        Map<String, Object> restoredMap = (Map<String, Object>) restored;
        assertEquals(3, restoredMap.size());
        assertEquals("Alice", restoredMap.get("name"));
        assertEquals(Integer.valueOf(30), restoredMap.get("age"));
        assertEquals(Boolean.TRUE, restoredMap.get("active"));
    }

    @Test
    public void listRoundTripProducesArrayListWithSameContents() {
        List<Object> original = new ArrayList<>();
        original.add("a");
        original.add(1);
        original.add(false);

        String json = converter.convertToDatabaseColumn(original);
        assertEquals("[\"a\",1,false]", json);

        Object restored = converter.convertToEntityAttribute(json);
        // Jackson reconstructs a JSON array into an ArrayList when the target type is Object.
        assertTrue(restored instanceof ArrayList,
                "Expected an ArrayList but got " + restored.getClass());

        @SuppressWarnings("unchecked")
        List<Object> restoredList = (List<Object>) restored;
        assertEquals(3, restoredList.size());
        assertEquals("a", restoredList.get(0));
        assertEquals(Integer.valueOf(1), restoredList.get(1));
        assertEquals(Boolean.FALSE, restoredList.get(2));
    }

    @Test
    public void emptyMapSerializesToEmptyJsonObject() {
        assertEquals("{}", converter.convertToDatabaseColumn(new LinkedHashMap<String, Object>()));
    }

    @Test
    public void emptyJsonObjectDeserializesToEmptyMap() {
        Object restored = converter.convertToEntityAttribute("{}");
        assertTrue(restored instanceof Map);
        assertTrue(((Map<?, ?>) restored).isEmpty(), "Expected an empty map");
    }

    // ------------------------------------------------------------------
    // Scalars - serialization (write)
    // ------------------------------------------------------------------

    @Test
    public void convertToDatabaseColumnStringIsJsonQuoted() {
        assertEquals("\"hello\"", converter.convertToDatabaseColumn("hello"));
    }

    @Test
    public void convertToDatabaseColumnIntegerIsBareNumber() {
        assertEquals("42", converter.convertToDatabaseColumn(42));
    }

    @Test
    public void convertToDatabaseColumnBooleanIsBareLiteral() {
        assertEquals("true", converter.convertToDatabaseColumn(true));
    }

    // ------------------------------------------------------------------
    // Scalars - deserialization (read) into the proper boxed types
    // ------------------------------------------------------------------

    @Test
    public void convertToEntityAttributeNumberBecomesInteger() {
        Object restored = converter.convertToEntityAttribute("42");
        assertEquals(Integer.class, restored.getClass());
        assertEquals(Integer.valueOf(42), restored);
    }

    @Test
    public void convertToEntityAttributeQuotedStringBecomesString() {
        Object restored = converter.convertToEntityAttribute("\"hi\"");
        assertEquals(String.class, restored.getClass());
        assertEquals("hi", restored);
    }

    @Test
    public void convertToEntityAttributeBooleanLiteralBecomesBoolean() {
        Object restored = converter.convertToEntityAttribute("true");
        assertEquals(Boolean.class, restored.getClass());
        assertEquals(Boolean.TRUE, restored);
    }

    // ------------------------------------------------------------------
    // Error handling - fail soft to null, never throw
    // ------------------------------------------------------------------

    @Test
    public void convertToEntityAttributeMalformedJsonReturnsNull() {
        // The trailing/missing structure makes this invalid JSON; the converter must catch the
        // IOException and return null rather than propagating it.
        assertNull(converter.convertToEntityAttribute("{bad"));
    }

    @Test
    public void convertToEntityAttributeEmptyStringReturnsNull() {
        // An empty (but non-null) string has no JSON content and must fail-soft to null.
        assertNull(converter.convertToEntityAttribute(""));
    }

    /**
     * A value that the default {@link tools.jackson.databind.ObjectMapper} cannot serialize
     * (a bare {@code Object} has no properties and is not a known scalar) triggers the
     * {@code JsonProcessingException} catch branch, which must return {@code null} rather than throw.
     */
    @Test
    public void convertToDatabaseColumnUnserializableValueReturnsNull() {
        assertNull(converter.convertToDatabaseColumn(new Object()));
    }
}
