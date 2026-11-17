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
package org.niord.core.bootstrap;

import org.junit.jupiter.api.Test;
import org.niord.core.util.JsonUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which fields an import file's item gives (docs/code-data-config-plan.md §7.2, the first bootstrap's rule): a field
 * there and not null, whatever its value, the import's default included, since the typed value objects cannot tell
 * that apart from a field left out; a list even when empty (the owner's decision of 2026-10-25), and neither an empty
 * string nor an empty object.
 */
class GivenTest {

    private static Given given(String json) {
        return Given.of(JsonMapper.builder().build().readTree(json));
    }

    @Test
    void aFieldLeftOutIsNotGiven() {
        Given given = given("{\"typeId\": \"t\"}");

        for (String field : Given.SCALARS) {
            assertFalse(given.has(field), field);
        }
        for (String field : Given.LISTS) {
            assertFalse(given.has(field), field);
        }
        assertEquals(Set.of(), given.paths());
    }

    @Test
    void aFieldAtTheImportsDefaultValueIsGiven() {
        Given given = given("{\"active\": true, \"priority\": 0, \"siblingSortOrder\": 0.0, \"type\": \"CATEGORY\", "
                + "\"templateParams\": [{\"paramId\": \"p\", \"mandatory\": false, \"positionList\": false}]}");

        assertTrue(given.has("active"));
        assertTrue(given.has("priority"));
        assertTrue(given.has("siblingSortOrder"));
        assertTrue(given.has("type"));
        assertTrue(given.has("templateParams"));
        assertTrue(given.has(Given.param("p", 0) + ".mandatory"));
        assertTrue(given.has(Given.param("p", 0) + ".positionList"));
        assertFalse(given.has(Given.param("p", 0) + ".type"), "the parameter's type is left out");
    }

    @Test
    void aFieldAtAnotherValueIsGiven() {
        Given given = given("{\"active\": false, \"priority\": 10, \"requirement\": \"MANDATORY\", "
                + "\"language\": \"kl\", \"name\": \"N\", \"serviceId\": \"s\", \"mrn\": \"urn:x\", "
                + "\"atonFilter\": \"f\"}");

        assertEquals(Set.of("active", "priority", "requirement", "language", "name", "serviceId", "mrn",
                "atonFilter"), given.paths());
    }

    @Test
    void anExplicitNullIsLeftOut() {
        Given given = given("{\"active\": null, \"name\": null, \"domains\": null, \"descs\": null}");

        assertEquals(Set.of(), given.paths());
    }

    @Test
    void anEmptyStringOrObjectIsLeftOut() {
        Given given = given("{\"name\": \"\", \"language\": {}, \"descs\": [{\"lang\": \"en\", \"name\": \"\"}, "
                + "{\"lang\": \"\", \"name\": \"x\"}]}");

        assertEquals(Set.of(), given.paths());
    }

    /** An explicit empty list is given, so it empties the earlier archive's list; a null or absent list is not */
    @Test
    void anEmptyListIsGiven() {
        assertEquals(Set.of("editorFields"), given("{\"editorFields\": []}").paths());
        assertEquals(Set.of("domains", "scriptResourcePaths", "messageTypes", "templateParams", "values"),
                given("{\"domains\": [], \"scriptResourcePaths\": [], \"messageTypes\": [], \"templateParams\": [], "
                        + "\"values\": []}").paths());
        assertFalse(given("{\"editorFields\": null}").has("editorFields"), "a null list is left out");
        assertFalse(given("{\"name\": \"n\"}").has("editorFields"), "an absent list is left out");
        assertFalse(given("{\"name\": \"\"}").has("name"), "an empty string is left out");
    }

    /**
     * A template parameter without a usable paramId gives no path and does not move the order of the parameters
     * after it; a description list that is not a list gives nothing
     */
    @Test
    void aParamWithoutAParamIdAndDescsThatAreNoListGiveNothing() {
        Given given = given("{\"templateParams\": [{\"type\": \"text\"}, {\"paramId\": \"\", \"mandatory\": true}, "
                + "{\"paramId\": \"p\"}]}");
        assertEquals(Set.of("templateParams"), given.paths(), "p gives none of its fields, and #0 is p's");
        Given typed = given("{\"templateParams\": [{\"type\": \"text\"}, {\"paramId\": \"\", \"mandatory\": true}, "
                + "{\"paramId\": \"p\", \"type\": \"text\"}]}");
        assertEquals(Set.of("templateParams", "templateParams[p#0].type"), typed.paths());

        assertEquals(Set.of(), given("{\"descs\": {\"lang\": \"en\"}}").paths());
    }

    @Test
    void aListAndADescriptionAreGivenByName() {
        Given given = given("{\"scriptResourcePaths\": [\"templates/x.ftl\"], \"values\": [{\"key\": \"k1\"}], "
                + "\"descs\": [{\"lang\": \"en\", \"name\": \"N\"}, {\"lang\": \"da\", \"value\": \"V\"}]}");

        assertEquals(Set.of("scriptResourcePaths", "values", "descs[en]", "descs[da]"), given.paths());
    }

    @Test
    void aRepeatedParamIdGivesOnePathAnOccurrence() {
        Given given = given("{\"templateParams\": ["
                + "{\"paramId\": \"p1\", \"type\": \"text\", \"descs\": [{\"lang\": \"en\", \"name\": \"First\"}]},"
                + "{\"paramId\": \"p2\", \"mandatory\": true},"
                + "{\"paramId\": \"p1\", \"positionList\": true}]}");

        assertEquals("templateParams[p1#1]", Given.param("p1", 1));
        assertTrue(given.has("templateParams[p1#0].type"));
        assertTrue(given.has("templateParams[p1#0].descs[en]"));
        assertFalse(given.has("templateParams[p1#0].positionList"));
        assertTrue(given.has("templateParams[p1#1].positionList"));
        assertFalse(given.has("templateParams[p1#1].type"));
        assertTrue(given.has("templateParams[p2#0].mandatory"));
    }

    @Test
    void anItemThatIsNotAnObjectGivesNothing() {
        assertEquals(Set.of(), Given.of(null).paths());
        assertEquals(Set.of(), given("[1, 2]").paths());
    }

    @Test
    void aFileIsReadAsATree() throws Exception {
        Path file = Files.createTempFile("given", ".json");
        try {
            Files.writeString(file, "[{\"active\": false}]", StandardCharsets.UTF_8);
            JsonNode items = Given.readTree(file);
            assertTrue(Given.of(items.get(0)).has("active"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Content after the root is read as the typed read reads it ({@code JsonUtils.readJson}, Jackson 2's defaults):
     * the root alone, so the two reads agree item for item
     */
    @Test
    void contentAfterTheRootIsReadAsTheTypedReadReadsIt() throws Exception {
        Path file = Files.createTempFile("given", ".json");
        try {
            Files.writeString(file, "[{\"active\": true}] ]", StandardCharsets.UTF_8);
            JsonNode items = Given.readTree(file);
            assertEquals(1, items.size());
            assertTrue(Given.of(items.get(0)).has("active"));
            List<Map<String, Object>> typed = JsonUtils.readJson(new TypeReference<List<Map<String, Object>>>() {},
                    file);
            assertEquals(1, typed.size(), "the typed read reads the same one item");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
