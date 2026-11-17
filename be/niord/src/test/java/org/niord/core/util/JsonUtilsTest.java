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

package org.niord.core.util;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link JsonUtils}.
 *
 * Verifies the JSON serialization/deserialization helpers built on Jackson:
 * exact JSON output, round-tripping of POJOs and maps, file read/write, and the
 * documented null-handling contract of each method. These tests deliberately avoid
 * any Quarkus-specific behavior and only exercise the public static API.
 */
public class JsonUtilsTest {

    /** Temp file created by a test; cleaned up after each test. */
    private Path tempFile;

    @AfterEach
    public void cleanUp() throws IOException {
        if (tempFile != null) {
            Files.deleteIfExists(tempFile);
            tempFile = null;
        }
    }

    /**
     * Simple public static POJO used for round-trip tests. Public no-arg constructor and
     * public fields make it trivially serializable/deserializable by Jackson.
     */
    public static class Person {
        public String name;
        public int age;
        public boolean active;

        public Person() {
        }

        public Person(String name, int age, boolean active) {
            this.name = name;
            this.age = age;
            this.active = active;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Person)) {
                return false;
            }
            Person other = (Person) o;
            return age == other.age
                    && active == other.active
                    && (name == null ? other.name == null : name.equals(other.name));
        }

        @Override
        public int hashCode() {
            int result = name == null ? 0 : name.hashCode();
            result = 31 * result + age;
            result = 31 * result + (active ? 1 : 0);
            return result;
        }
    }

    // ------------------------------------------------------------------
    // toJson
    // ------------------------------------------------------------------

    @Test
    public void toJsonNullReturnsNull() throws IOException {
        assertNull(JsonUtils.toJson(null));
    }

    @Test
    public void toJsonSingleEntryMapProducesExactJson() throws IOException {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("a", 1);
        assertEquals("{\"a\":1}", JsonUtils.toJson(map));
    }

    @Test
    public void toJsonMapPreservesInsertionOrder() throws IOException {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("b", 2);
        map.put("a", 1);
        assertEquals("{\"b\":2,\"a\":1}", JsonUtils.toJson(map));
    }

    @Test
    public void toJsonEmptyMapProducesEmptyObject() throws IOException {
        assertEquals("{}", JsonUtils.toJson(new LinkedHashMap<String, Integer>()));
    }

    @Test
    public void toJsonStringValueIsQuoted() throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("greeting", "hi");
        assertEquals("{\"greeting\":\"hi\"}", JsonUtils.toJson(map));
    }

    @Test
    public void toJsonStringScalarIsQuoted() throws IOException {
        assertEquals("\"hello\"", JsonUtils.toJson("hello"));
    }

    @Test
    public void toJsonIntegerScalarIsBare() throws IOException {
        assertEquals("42", JsonUtils.toJson(42));
    }

    @Test
    public void toJsonPojoProducesExactFieldOrder() throws IOException {
        Person p = new Person("Alice", 30, true);
        assertEquals("{\"name\":\"Alice\",\"age\":30,\"active\":true}", JsonUtils.toJson(p));
    }

    // ------------------------------------------------------------------
    // fromJson (Class)
    // ------------------------------------------------------------------

    @Test
    public void fromJsonClassNullReturnsNull() throws IOException {
        assertNull(JsonUtils.fromJson(null, Person.class));
    }

    @Test
    public void fromJsonClassParsesPojoFields() throws IOException {
        Person p = JsonUtils.fromJson("{\"name\":\"Bob\",\"age\":25,\"active\":false}", Person.class);
        assertEquals("Bob", p.name);
        assertEquals(25, p.age);
        assertFalse(p.active);
    }

    @Test
    public void fromJsonClassParsesScalarString() throws IOException {
        assertEquals("hello", JsonUtils.fromJson("\"hello\"", String.class));
    }

    @Test
    public void fromJsonClassIgnoresWhitespace() throws IOException {
        Person p = JsonUtils.fromJson("  { \"name\" : \"Eve\" , \"age\" : 7 , \"active\" : true }  ", Person.class);
        assertEquals(new Person("Eve", 7, true), p);
    }

    @Test
    public void fromJsonClassMalformedThrows() throws IOException {
        assertThrows(JacksonException.class, () -> JsonUtils.fromJson("{not valid json", Person.class));
    }

    @Test
    public void fromJsonClassEmptyStringThrows() throws IOException {
        // An empty (but non-null) string is not valid JSON content and must fail.
        assertThrows(JacksonException.class, () -> JsonUtils.fromJson("", Person.class));
    }

    // ------------------------------------------------------------------
    // toJson -> fromJson round trip
    // ------------------------------------------------------------------

    @Test
    public void pojoRoundTripProducesEqualPojo() throws IOException {
        Person original = new Person("Charlie", 41, true);
        String json = JsonUtils.toJson(original);
        Person restored = JsonUtils.fromJson(json, Person.class);
        assertEquals(original, restored);
    }

    @Test
    public void pojoRoundTripWithNullFieldPreservesNull() throws IOException {
        Person original = new Person(null, 0, false);
        String json = JsonUtils.toJson(original);
        Person restored = JsonUtils.fromJson(json, Person.class);
        assertEquals(original, restored);
        assertNull(restored.name);
    }

    // ------------------------------------------------------------------
    // fromJson (TypeReference)
    // ------------------------------------------------------------------

    @Test
    public void fromJsonTypeRefNullReturnsNull() throws IOException {
        assertNull(JsonUtils.fromJson(null, new TypeReference<List<String>>() {}));
    }

    @Test
    public void fromJsonTypeRefParsesListOfStrings() throws IOException {
        List<String> list = JsonUtils.fromJson("[\"x\",\"y\"]", new TypeReference<List<String>>() {});
        assertEquals(2, list.size());
        assertEquals("x", list.get(0));
        assertEquals("y", list.get(1));
    }

    @Test
    public void fromJsonTypeRefParsesEmptyList() throws IOException {
        List<String> list = JsonUtils.fromJson("[]", new TypeReference<List<String>>() {});
        assertTrue(list.isEmpty());
    }

    @Test
    public void fromJsonTypeRefParsesMap() throws IOException {
        Map<String, Integer> map = JsonUtils.fromJson(
                "{\"a\":1,\"b\":2}", new TypeReference<Map<String, Integer>>() {});
        assertEquals(2, map.size());
        assertEquals(Integer.valueOf(1), map.get("a"));
        assertEquals(Integer.valueOf(2), map.get("b"));
    }

    @Test
    public void fromJsonTypeRefMalformedThrows() throws IOException {
        assertThrows(JacksonException.class, () -> JsonUtils.fromJson("[\"x\",", new TypeReference<List<String>>() {}));
    }

    @Test
    public void listRoundTripViaTypeRefPreservesContents() throws IOException {
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("k", "v");
        String json = JsonUtils.toJson(entry);
        Map<String, String> restored = JsonUtils.fromJson(json, new TypeReference<Map<String, String>>() {});
        assertEquals(1, restored.size());
        assertEquals("v", restored.get("k"));
    }

    // ------------------------------------------------------------------
    // readJson (Class) / writeJson
    // ------------------------------------------------------------------

    @Test
    public void readJsonClassNullPathReturnsNull() throws IOException {
        assertNull(JsonUtils.readJson(Person.class, null));
    }

    @Test
    public void readJsonTypeRefNullPathReturnsNull() throws IOException {
        assertNull(JsonUtils.readJson(new TypeReference<List<String>>() {}, null));
    }

    @Test
    public void writeJsonNullDataWritesNothing() throws IOException {
        tempFile = Files.createTempFile("jsonutils-null-data", ".json");
        // Pre-create an empty file; writeJson(null, ...) must leave it untouched.
        long sizeBefore = Files.size(tempFile);
        JsonUtils.writeJson(null, tempFile);
        assertEquals(sizeBefore, Files.size(tempFile));
        assertEquals(0L, Files.size(tempFile));
    }

    @Test
    public void writeJsonNullPathDoesNotThrow() throws IOException {
        // Null path is part of the documented no-op contract and must not throw.
        JsonUtils.writeJson(new Person("X", 1, true), null);
    }

    @Test
    public void writeJsonThenReadJsonClassRoundTrip() throws IOException {
        tempFile = Files.createTempFile("jsonutils-pojo", ".json");
        Person original = new Person("Dana", 33, true);

        JsonUtils.writeJson(original, tempFile);

        // The file must actually contain the serialized JSON.
        String onDisk = new String(Files.readAllBytes(tempFile), "UTF-8");
        assertEquals("{\"name\":\"Dana\",\"age\":33,\"active\":true}", onDisk);

        Person restored = JsonUtils.readJson(Person.class, tempFile);
        assertEquals(original, restored);
    }

    @Test
    public void writeJsonThenReadJsonTypeRefRoundTrip() throws IOException {
        tempFile = Files.createTempFile("jsonutils-list", ".json");
        Map<String, Integer> original = new LinkedHashMap<>();
        original.put("one", 1);
        original.put("two", 2);

        JsonUtils.writeJson(original, tempFile);

        Map<String, Integer> restored = JsonUtils.readJson(
                new TypeReference<Map<String, Integer>>() {}, tempFile);
        assertEquals(2, restored.size());
        assertEquals(Integer.valueOf(1), restored.get("one"));
        assertEquals(Integer.valueOf(2), restored.get("two"));
    }

    @Test
    public void writeJsonOverwritesExistingFileContent() throws IOException {
        tempFile = Files.createTempFile("jsonutils-overwrite", ".json");
        JsonUtils.writeJson(new Person("First", 1, true), tempFile);
        JsonUtils.writeJson(new Person("Second", 2, false), tempFile);

        Person restored = JsonUtils.readJson(Person.class, tempFile);
        assertEquals(new Person("Second", 2, false), restored);
    }

    @Test
    public void readJsonClassFromMalformedFileThrows() throws IOException {
        tempFile = Files.createTempFile("jsonutils-bad", ".json");
        Files.write(tempFile, "{ this is not json".getBytes("UTF-8"));
        try {
            JsonUtils.readJson(Person.class, tempFile);
            fail("Expected a JacksonException for malformed JSON file content");
        } catch (JacksonException expected) {
            // Jackson 3 reports malformed JSON content via an (unchecked) JacksonException.
        }
    }
}
