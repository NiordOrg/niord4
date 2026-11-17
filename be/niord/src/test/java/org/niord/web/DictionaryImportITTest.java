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
package org.niord.web;

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.batch.operations.JobOperator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.category.ListParamType;
import org.niord.core.dictionary.DictionaryEntry;
import org.niord.core.util.JsonUtils;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dictionary import as the sysadmin sees it (docs/code-data-config-plan.md §4.6): the preview of what a file would
 * do, which writes nothing; the import, which adds missing keys, but not one an operator deleted unless the sysadmin
 * ticks it, and overwrites an existing key only when the sysadmin ticks it, keeping the languages the file does not
 * carry; and the refusal of an entry the database could not hold, named in the job log.
 *
 * <p>The test works on a dictionary of its own, created by its first import, and removes it afterwards.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class DictionaryImportITTest extends ITTest {

    private static final String PREVIEW = "/rest/dictionaries/import-preview";
    private static final String UPLOAD = "/rest/dictionaries/upload-dictionaries";
    private static final String JOB = "dictionary-import";

    @PersistenceContext
    EntityManager em;

    @Autowired
    JobOperator jobOperator;

    private final String dictionary = "it-import-" + System.nanoTime();

    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token);
    }

    @AfterEach
    void cleanup() {
        JsonPath dict = dictionary();
        Map<String, Object> entries = dict.getMap("entries");
        if (entries != null) {
            entries.keySet().forEach(key -> as(accessToken())
                    .when().delete("/rest/dictionaries/dictionary/" + dictionary + "/" + key));
        }
        Tx.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM public.\"DefaultsLedger\" WHERE dictionary = :d")
                    .setParameter("d", dictionary).executeUpdate();
            em.createNativeQuery("DELETE FROM public.\"Dictionary\" WHERE name = :d")
                    .setParameter("d", dictionary).executeUpdate();
        });
    }

    @Test
    void thePreviewTheTicksAndTheRefusals() {
        // The dictionary as a deployment has it: one key an operator then deletes
        importFile(null, null, entry("changed", "en", "Old", "da", "Gammel"), entry("unchanged", "en", "Same"),
                entry("deleted", "en", "Gone"));
        as(accessToken()).when().delete("/rest/dictionaries/dictionary/" + dictionary + "/deleted")
                .then().statusCode(200);

        Map<String, Object>[] file = entries(
                entry("new", "en", "New"),
                entry("changed", "en", "Changed"),
                entry("unchanged", "en", "Same"),
                entry("deleted", "en", "Gone"),
                entry("too-long", "en", "x".repeat(1001)));

        // The preview
        long[] before = counts();
        JsonPath preview = as(accessToken())
                .multiPart("file", "texts.json", file(file), "application/json")
                .when().post(PREVIEW)
                .then().statusCode(200).contentType(ContentType.JSON)
                .extract().jsonPath();
        assertEquals(List.of("deleted", "new"), preview.getList("add.key"));
        assertEquals(List.of(true, false), preview.getList("add.deleted"), "the key an operator deleted is marked");
        assertEquals("New", preview.getString("add[1].values.en"));
        assertEquals(List.of("changed"), preview.getList("overwrite.key"));
        assertEquals("Old", preview.getString("overwrite[0].current.values.en"));
        assertEquals("Gammel", preview.getString("overwrite[0].current.values.da"));
        assertEquals("Changed", preview.getString("overwrite[0].incoming.values.en"));
        assertNull(preview.get("overwrite[0].incoming.values.da"), "the file carries English only");
        assertEquals(1, preview.getInt("unchanged"));
        assertEquals(List.of("too-long"), preview.getList("refused.key"));
        assertTrue(preview.getString("refused[0].reason").contains("longer than 1000"),
                preview.getString("refused[0].reason"));
        assertEquals(before[0], counts()[0], "the preview writes no Dictionary row");
        assertEquals(before[1], counts()[1], "nor a DictionaryEntry row");
        assertEquals(before[2], counts()[2], "nor a DictionaryEntryDesc row");

        // The import without ticks: the new key added, the deleted key not, the changed key kept
        String log = importFile(null, null, file);
        assertEquals("New", value("new", "en"));
        assertFalse(keys().contains("deleted"), "a key an operator deleted is not added without a tick");
        assertEquals("Old", value("changed", "en"), "an existing key is kept without a tick");
        assertFalse(keys().contains("too-long"));
        assertTrue(log.contains("Refused " + dictionary + " too-long") && log.contains("longer than 1000"), log);

        // The changed key ticked: overwritten in the file's language, the other kept
        importFile(null, List.of(Map.of("dictionary", dictionary, "key", "changed")), file);
        assertEquals("Changed", value("changed", "en"));
        assertEquals("Gammel", value("changed", "da"), "a language the file does not carry is kept");

        // The deleted key ticked: restored
        importFile(List.of(Map.of("dictionary", dictionary, "key", "deleted")), null, file);
        assertEquals("Gone", value("deleted", "en"));
    }

    /**
     * A ticked overwrite writes each language the file carries whole, value and long value, so a long value the file
     * lacks is cleared; the preview lists such a key under overwrite with the row's long value and none incoming, so
     * the dialog can show the clearing (the first review of PR #179)
     */
    @Test
    void aLongValueTheFileLacksIsShownAndClearedOnlyWhenTicked() {
        importFile(null, null, entryWithLongValue("instructions", "Same label", "Important instructions"));

        for (Map<String, Object> incoming : List.of(entry("instructions", "en", "Same label"),
                entryWithLongValue("instructions", "Same label", ""))) {
            JsonPath preview = as(accessToken())
                    .multiPart("file", "long.json", file(entries(incoming)), "application/json")
                    .when().post(PREVIEW)
                    .then().statusCode(200).extract().jsonPath();
            assertEquals(List.of("instructions"), preview.getList("overwrite.key"), "a difference, not unchanged");
            assertEquals(0, preview.getInt("unchanged"));
            assertEquals("Same label", preview.getString("overwrite[0].current.values.en"));
            assertEquals("Same label", preview.getString("overwrite[0].incoming.values.en"));
            assertEquals("Important instructions", preview.getString("overwrite[0].current.longValues.en"));
            assertNull(preview.get("overwrite[0].incoming.longValues"), "none incoming: the overwrite clears it");
        }

        Map<String, Object>[] file = entries(entry("instructions", "en", "Same label"));
        importFile(null, null, file);
        assertEquals("Important instructions", longValue("instructions", "en"), "unticked, the long value is kept");

        importFile(null, List.of(Map.of("dictionary", dictionary, "key", "instructions")), file);
        assertEquals("Same label", value("instructions", "en"));
        assertNull(longValue("instructions", "en"), "ticked, the long value the file lacks is cleared");
    }

    /**
     * The import reads the deleted keys from the database, not from this node's snapshot: a deleted mark written by
     * another node (here by SQL, which the snapshot never sees) is honoured (the second review of PR #179)
     */
    @Test
    void aDeletedMarkTheSnapshotHasNotSeenIsHonoured() {
        importFile(null, null, entry("present", "en", "Present"));
        Tx.requiringNew().run(() -> em.createNativeQuery("INSERT INTO public.\"DefaultsLedger\" "
                        + "(kind, \"key\", dictionary, lang, hash, source, \"baseVersion\", created) "
                        + "VALUES ('TEXT', 'deleted-elsewhere', :d, '*', NULL, 'deleted', 'it', now())")
                .setParameter("d", dictionary).executeUpdate());
        Map<String, Object>[] file = entries(entry("deleted-elsewhere", "en", "Back"));

        JsonPath preview = as(accessToken())
                .multiPart("file", "deleted.json", file(file), "application/json")
                .when().post(PREVIEW)
                .then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("deleted-elsewhere"), preview.getList("add.key"));
        assertEquals(List.of(true), preview.getList("add.deleted"), "marked deleted");

        importFile(null, null, file);
        assertFalse(keys().contains("deleted-elsewhere"), "not added without a tick");
    }

    @Test
    void anEntryWithoutATranslationIsRefusedAndABlankValueIsAccepted() {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("key", "no-translation");
        empty.put("descs", List.of());
        // As the base product's own navwarn.date.time_interval_prefix, whose English value is empty
        Map<String, Object>[] file = entries(empty, entry("time_interval_prefix", "en", ""));

        JsonPath preview = as(accessToken())
                .multiPart("file", "blank.json", file(file), "application/json")
                .when().post(PREVIEW)
                .then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("no-translation"), preview.getList("refused.key"));
        assertEquals("the entry has no translation", preview.getString("refused[0].reason"));
        assertEquals(List.of("time_interval_prefix"), preview.getList("add.key"));

        String log = importFile(null, null, file);
        assertFalse(keys().contains("no-translation"));
        assertTrue(log.contains("Refused " + dictionary + " no-translation"), log);
        assertEquals("", value("time_interval_prefix", "en"));
    }

    /**
     * A tick carries the fingerprint of the row the preview showed: a key changed between the preview and the job is
     * kept, and the job log says so (the second review of PR #179)
     */
    @Test
    void aTickedKeyChangedSinceThePreviewIsKept() {
        importFile(null, null, entry("race", "en", "One"));
        Map<String, Object>[] file = entries(entry("race", "en", "Three"));
        String basedOn = preview(file).getString("overwrite[0].basedOn");
        assertTrue(basedOn.matches("sha256:[0-9a-f]{64}"), basedOn);

        // Another sysadmin edits the key on the page after the preview
        as(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("key", "race", "descs", List.of(Map.of("lang", "en", "value", "Two"))))
                .when().put("/rest/dictionaries/dictionary/" + dictionary + "/race")
                .then().statusCode(200);

        String log = importFile(null, List.of(Map.of("dictionary", dictionary, "key", "race", "basedOn", basedOn)),
                file);
        assertEquals("Two", value("race", "en"), "the edit made after the preview is kept");
        assertTrue(log.contains("Kept (changed since the preview) " + dictionary + " race"), log);

        String current = preview(file).getString("overwrite[0].basedOn");
        importFile(null, List.of(Map.of("dictionary", dictionary, "key", "race", "basedOn", current)), file);
        assertEquals("Three", value("race", "en"), "a tick on the key as previewed overwrites it");
    }

    /**
     * A key a list parameter type holds among its values cannot be deleted: the database would refuse it at commit.
     * The delete answers 409 naming the type, before any write, and the delete confirmation's users name it (the
     * second review of PR #179).
     */
    @Test
    void aKeyAParameterTypeListsIsNotDeleted() {
        importFile(null, null, entry("listed", "en", "Listed"));
        String typeName = "it-list-" + System.nanoTime();
        Integer typeId = Tx.requiringNew().call(() -> {
            DictionaryEntry value = em.createQuery("select e from DictionaryEntry e "
                            + "where e.dictionary.name = :d and e.key = 'listed'", DictionaryEntry.class)
                    .setParameter("d", dictionary).getSingleResult();
            ListParamType type = new ListParamType();
            type.setName(typeName);
            type.getValues().add(value);
            em.persist(type);
            return type.getId();
        });
        try {
            as(accessToken()).when().get("/rest/dictionaries/dictionary/" + dictionary + "/key-users?key=listed")
                    .then().statusCode(200).body("paramTypes", is(List.of(typeName)));
            as(accessToken()).when().delete("/rest/dictionaries/dictionary/" + dictionary + "/listed")
                    .then().statusCode(409).body("detail", containsString(typeName));

            assertEquals("Listed", value("listed", "en"), "the key stays");
            long marks = Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(
                            "SELECT count(*) FROM public.\"DefaultsLedger\" WHERE dictionary = :d AND lang = '*'")
                    .setParameter("d", dictionary).getSingleResult()).longValue());
            assertEquals(0, marks, "a refused delete leaves no deleted mark");
            as(accessToken()).when().get("/rest/dictionaries/dictionary/" + dictionary + "/key-users?key=present")
                    .then().statusCode(200).body("paramTypes", is(List.of()));
        } finally {
            Tx.requiringNew().run(() -> em.remove(em.find(ListParamType.class, typeId)));
        }
    }

    @Test
    void thePreviewIsForASysadminOnlyAndAMalformedFileIsRefused() {
        byte[] file = file(entries(entry("x", "en", "X")));
        given().multiPart("file", "x.json", file, "application/json")
                .when().post(PREVIEW).then().statusCode(401);
        as(KeycloakTestTokens.adminAccessToken()).multiPart("file", "x.json", file, "application/json")
                .when().post(PREVIEW).then().statusCode(403);
        as(accessToken()).multiPart("file", "broken.json", "[{".getBytes(StandardCharsets.UTF_8), "application/json")
                .when().post(PREVIEW).then().statusCode(400).body("detail", containsString("broken.json"));
    }

    @Test
    void aTickFieldThatIsNoArrayOfKeysIsRefusedWith400() {
        for (String add : List.of("not json", "[\"key\"]", "[{\"dictionary\": \"web\"}]")) {
            as(accessToken())
                    .multiPart("file", "x.json", file(entries(entry("x", "en", "X"))), "application/json")
                    .multiPart("add", add)
                    .when().post(UPLOAD)
                    .then().statusCode(400).body("detail", containsString("add"));
        }
        for (String overwrite : List.of("[{\"dictionary\": \"web\", \"key\": \"x\", \"basedOn\": 1}]")) {
            as(accessToken())
                    .multiPart("file", "x.json", file(entries(entry("x", "en", "X"))), "application/json")
                    .multiPart("overwrite", overwrite)
                    .when().post(UPLOAD)
                    .then().statusCode(400).body("detail", containsString("overwrite"));
        }
        assertFalse(keys().contains("x"), "no job ran");
    }

    // ------------------------------------------------------------------------------------------- //

    /** Uploads the entries into the test's dictionary with the ticks (null: not sent), waits; returns the job log */
    @SafeVarargs
    private final String importFile(List<Map<String, String>> add, List<Map<String, String>> overwrite,
                              Map<String, Object>... entries) {
        String fileName = "it-dictionary-" + System.nanoTime() + ".json";
        RequestSpecification request = as(accessToken())
                .multiPart("file", fileName, file(entries), "application/json");
        if (add != null) {
            request = request.multiPart("add", json(add));
        }
        if (overwrite != null) {
            request = request.multiPart("overwrite", json(overwrite));
        }
        request.when().post(UPLOAD).then().statusCode(200).body(containsString("Started batch job"));
        return ScriptResourceImportITTest.jobLog(
                ScriptResourceImportITTest.awaitJob(em, jobOperator, JOB, fileName));
    }

    /** The test's dictionary; an empty object while it does not exist (the endpoint answers 204) */
    private JsonPath dictionary() {
        String body = as(accessToken()).when().get("/rest/dictionaries/dictionary/" + dictionary)
                .then().statusCode(anyOf(is(200), is(204)))
                .extract().asString();
        return new JsonPath(body.isBlank() ? "{}" : body);
    }

    private List<String> keys() {
        Map<String, Object> entries = dictionary().getMap("entries");
        return entries == null ? List.of() : List.copyOf(entries.keySet());
    }

    private String value(String key, String lang) {
        return dictionary().getString("entries.'" + key + "'.descs.find { it.lang == '" + lang + "' }.value");
    }

    private String longValue(String key, String lang) {
        return dictionary().getString("entries.'" + key + "'.descs.find { it.lang == '" + lang + "' }.longValue");
    }

    private JsonPath preview(Map<String, Object>[] entries) {
        return as(accessToken())
                .multiPart("file", "preview-" + System.nanoTime() + ".json", file(entries), "application/json")
                .when().post(PREVIEW)
                .then().statusCode(200).extract().jsonPath();
    }

    private long[] counts() {
        return Tx.requiringNew().call(() -> new long[] {
                count("Dictionary"), count("DictionaryEntry"), count("DictionaryEntryDesc")});
    }

    private long count(String table) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM public.\"" + table + "\"").getSingleResult())
                .longValue();
    }

    /** An entry with the language/value pairs given */
    private static Map<String, Object> entry(String key, String... langValues) {
        List<Map<String, String>> descs = new ArrayList<>();
        for (int i = 0; i < langValues.length; i += 2) {
            descs.add(Map.of("lang", langValues[i], "value", langValues[i + 1]));
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", key);
        entry.put("descs", descs);
        return entry;
    }

    /** An entry with an English value and long value */
    private static Map<String, Object> entryWithLongValue(String key, String value, String longValue) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", key);
        entry.put("descs", List.of(Map.of("lang", "en", "value", value, "longValue", longValue)));
        return entry;
    }

    @SafeVarargs
    private static Map<String, Object>[] entries(Map<String, Object>... entries) {
        return entries;
    }

    private byte[] file(Map<String, Object>[] entries) {
        return json(List.of(Map.of("name", dictionary, "entries", List.of(entries)))).getBytes(StandardCharsets.UTF_8);
    }

    private static String json(Object value) {
        try {
            return JsonUtils.toJson(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
