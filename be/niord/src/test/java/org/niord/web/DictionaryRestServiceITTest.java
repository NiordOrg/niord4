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
package org.niord.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import io.restassured.http.ContentType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.defaults.DefaultTexts;
import org.niord.core.defaults.DefaultsLedger;
import org.niord.core.script.ScriptResource;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.Tx;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link DictionaryRestService}: the HTTP contract of the {@code /rest/dictionaries}
 * endpoints, including the authorization matrix exercised against a real Keycloak token (see
 * {@link KeycloakTestTokens}).
 *
 * <p>The read endpoints (names/dictionary/entries/exports) are {@code @PermitAll}; entry create/update/delete
 * and the dictionary upload require {@code sysadmin}, and so does {@code key-users}, which the delete confirmation
 * reads — dictionaries are shared by every domain, so an admin, whose role is granted per domain, is refused with
 * 403 (checked for the upload and key-users);
 * {@code matches-aton} requires the {@code editor} role, because it runs AtoN filters
 * taken from the request body. A plain {@code user} (the {@code testuser} token with a {@code NiordDomain}
 * header, which is the only way that identity gets the {@code user} role) is rejected with 403. A filter
 * outside the AtoN filter grammar is refused before it is run and matches nothing.
 *
 * <p>HTTP requests run in the server's own transaction, which {@code @Transactional} cannot roll back, so
 * data created by a POST is committed. Write tests therefore operate on the seeded {@code web} dictionary
 * using a unique {@code IT-REST-} key that never occurs in the seed data, and the created key is tracked and
 * removed in {@link #cleanup()} via DELETE, with the ledger rows and script resources a test committed, so the
 * shared seed database stays pristine and tests stay
 * independent.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class DictionaryRestServiceITTest extends ITTest {

    /** A seeded dictionary that is known to exist and to contain entries. */
    private static final String SEEDED_DICT = "web";

    /** Keys created via REST in the seeded {@code web} dictionary, removed in {@link #cleanup()}. */
    private final List<String> createdKeys = new ArrayList<>();

    /** Keys of the seeded {@code web} dictionary whose ledger rows a test wrote, removed in {@link #cleanup()} */
    private final List<String> ledgerKeys = new ArrayList<>();

    /** Paths of the script resources a test committed, removed in {@link #cleanup()} */
    private final List<String> scriptPaths = new ArrayList<>();

    @Autowired
    DefaultsLedger ledger;

    @PersistenceContext
    EntityManager em;

    @AfterEach
    void cleanup() {
        for (String key : createdKeys) {
            try {
                given().auth().oauth2(accessToken())
                        .delete("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/" + key);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdKeys.clear();
        if (!ledgerKeys.isEmpty() || !scriptPaths.isEmpty()) {
            Tx.requiringNew().run(() -> {
                if (!ledgerKeys.isEmpty()) {
                    em.createNativeQuery("DELETE FROM public.\"DefaultsLedger\" WHERE dictionary = :name "
                                    + "AND \"key\" IN (:keys)")
                            .setParameter("name", SEEDED_DICT)
                            .setParameter("keys", ledgerKeys)
                            .executeUpdate();
                }
                if (!scriptPaths.isEmpty()) {
                    em.createQuery("delete from ScriptResource s where s.path in :paths")
                            .setParameter("paths", scriptPaths)
                            .executeUpdate();
                }
            });
        }
        ledgerKeys.clear();
        scriptPaths.clear();
    }

    /** Creates an entry in the seeded {@code web} dictionary as sysadmin and tracks it for cleanup. */
    private void createEntryAsSysadmin(String key, String value) {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("key", key,
                        "descs", List.of(Map.of("lang", "en", "value", value))))
                .when().post("/rest/dictionaries/dictionary/" + SEEDED_DICT)
                .then().statusCode(200)
                .body("key", is(key));
        createdKeys.add(key);
    }

    // ------------------------------------------------------------------------------------------- //
    // Unauthenticated reads (@PermitAll)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getNamesReturnsSeededDictionaries() {
        given()
                .when().get("/rest/dictionaries/names")
                .then().statusCode(200)
                .body("size()", greaterThan(0))
                .body("$", hasItem("web"))
                .body("$", hasItem("message"));
    }

    @Test
    void getDictionaryReturnsNamedDictionaryWithEntries() {
        given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT)
                .then().statusCode(200)
                .body("name", is(SEEDED_DICT))
                .body("entries.size()", greaterThan(0));
    }

    @Test
    void getDictionaryEntriesReturnsNonEmptySortedList() {
        given()
                .queryParam("lang", "en")
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/entries")
                .then().statusCode(200)
                .body("size()", greaterThan(0))
                .body("[0].key", notNullValue());
    }

    /**
     * Start-up adopted the seed's rows, recording the hash of each one equal to its default: those translations
     * are asAdded on {@code /entries} from the first read on.
     */
    @Test
    void getDictionaryEntriesMarksTheSeedsUnchangedDefaultsAsAdded() throws IOException {
        Properties defaults = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("defaults/base/texts/en/web.properties")) {
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        List<Map<String, Object>> entries = given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/entries")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        List<Map<String, Object>> asAdded = entries.stream()
                .flatMap(e -> descs(e).stream()
                        .filter(d -> d.containsKey("asAdded"))
                        .map(d -> Map.of("key", e.get("key"), "lang", d.get("lang"), "value", d.get("value"),
                                "asAdded", d.get("asAdded"))))
                .toList();
        assertFalse(asAdded.isEmpty(), "the seed's unchanged web defaults are asAdded");
        for (Map<String, Object> desc : asAdded) {
            assertEquals(true, desc.get("asAdded"), "asAdded is sent only as true");
            // An English key the base product lacks has no default to compare (the test tree has no English text of
            // its own)
            if ("en".equals(desc.get("lang")) && defaults.containsKey(desc.get("key"))) {
                assertEquals(defaults.getProperty((String) desc.get("key")), desc.get("value"));
            }
        }
    }

    /**
     * A translation start-up added is asAdded while unchanged; once an operator edits it the property is left out
     * of the JSON.
     */
    @Test
    void getDictionaryEntriesSendsAsAddedOnlyWhileTheValueIsTheOneAdded() {
        String key = "IT-REST-as-added-" + System.nanoTime();
        ledgerKeys.add(key);
        // As the add-missing step records an added text: the ledger row commits before the entry is read
        Tx.requiringNew().run(() -> ledger.insertAll(List.of(DefaultsLedger.Row.text(SEEDED_DICT, key, "en",
                DefaultTexts.sha256("As added"), DefaultTexts.SOURCE_BASE))));
        createEntryAsSysadmin(key, "As added");

        assertEquals(true, entryDesc(key).get("asAdded"));

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("key", key, "descs", List.of(Map.of("lang", "en", "value", "Edited"))))
                .when().put("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/" + key)
                .then().statusCode(200);

        Map<String, Object> edited = entryDesc(key);
        assertEquals("Edited", edited.get("value"));
        assertFalse(edited.containsKey("asAdded"), "an edited translation carries no asAdded property: " + edited);
    }

    /** The English translation of the key, as {@code /entries} sends it */
    private Map<String, Object> entryDesc(String key) {
        List<Map<String, Object>> entries = given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/entries")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        return entries.stream()
                .filter(e -> key.equals(e.get("key")))
                .flatMap(e -> descs(e).stream())
                .filter(d -> "en".equals(d.get("lang")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no English translation of " + key));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> descs(Map<String, Object> entry) {
        Object descs = entry.get("descs");
        return descs == null ? List.of() : (List<Map<String, Object>>) descs;
    }

    @Test
    void exportDictionaryAsPropertyFileReturnsKeyValueText() {
        // The exported property file lists "key = value" lines for the requested language.
        String body = given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT + "_en.properties")
                .then().statusCode(200)
                .contentType(containsString("text/plain"))
                .extract().asString();

        assertTrue(body.contains(" = "),
                "the property-file export must contain at least one 'key = value' line");
    }

    @Test
    void exportDictionaryAsJsonReturnsNamedDictionary() {
        given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT + ".json")
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].name", is(SEEDED_DICT))
                .body("[0].entries.size()", greaterThan(0));
    }

    /**
     * The export copies the entries without the asAdded mark, which is this deployment's bookkeeping, and leaves the
     * cached dictionary's marks as they are
     */
    @Test
    void exportDictionaryAsJsonCarriesNoAsAddedAndLeavesTheCacheAsItIs() {
        String body = given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT + ".json")
                .then().statusCode(200)
                .extract().asString();
        assertFalse(body.contains("asAdded"), "the exported file carries no asAdded");

        List<Map<String, Object>> entries = given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/entries")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");
        assertTrue(entries.stream().flatMap(e -> descs(e).stream()).anyMatch(d -> d.containsKey("asAdded")),
                "the page still sees the marks after an export");
    }

    @Test
    void exportDictionaryAsJsonSkipsUnknownNames() {
        // Unknown dictionary names are filtered out, so only the seeded one remains.
        given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT + ",IT-REST-no-such-dict.json")
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].name", is(SEEDED_DICT));
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — protected writes without a token return 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createEntryWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("key", "IT-REST-unauth",
                        "descs", List.of(Map.of("lang", "en", "value", "v"))))
                .when().post("/rest/dictionaries/dictionary/" + SEEDED_DICT)
                .then().statusCode(401);
    }

    @Test
    void updateEntryWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("key", "IT-REST-unauth",
                        "descs", List.of(Map.of("lang", "en", "value", "v"))))
                .when().put("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/IT-REST-unauth")
                .then().statusCode(401);
    }

    @Test
    void deleteEntryWithoutTokenIsUnauthorized() {
        given()
                .when().delete("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/IT-REST-unauth")
                .then().statusCode(401);
    }

    @Test
    void keyUsersWithoutTokenIsUnauthorized() {
        given().queryParam("key", "position.deg")
                .when().get("/rest/dictionaries/dictionary/template/key-users")
                .then().statusCode(401);
    }

    @Test
    void uploadDictionariesWithoutTokenIsUnauthorized() {
        given().multiPart("data", "dict.json", "[]".getBytes())
                .when().post("/rest/dictionaries/upload-dictionaries")
                .then().statusCode(401);
    }

    @Test
    void matchesAtonWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of(
                        "aton", Map.of("tags", Map.of("seamark:type", "light_minor")),
                        "values", List.of()))
                .when().put("/rest/dictionaries/matches-aton")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — authenticated-but-insufficient-role
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadDictionariesAsAdminIsForbidden() {
        // The upload adds missing keys and overwrites only ticked ones, like the sysadmin entry CRUD. The
        // testadmin token carries admin (not sysadmin) on the base client; refused before the job starts.
        given().auth().oauth2(KeycloakTestTokens.adminAccessToken())
                .multiPart("data", "dict.json", "[]".getBytes())
                .when().post("/rest/dictionaries/upload-dictionaries")
                .then().statusCode(403);
    }

    @Test
    void uploadDictionariesAsDomainAdminIsForbidden() {
        // The NiordDomain header adds testadmin's roles on niord-nw - admin, not sysadmin.
        given().auth().oauth2(KeycloakTestTokens.adminAccessToken())
                .header("NiordDomain", "niord-nw")
                .multiPart("data", "dict.json", "[]".getBytes())
                .when().post("/rest/dictionaries/upload-dictionaries")
                .then().statusCode(403);
    }

    @Test
    void keyUsersAsAdminIsForbidden() {
        // Like the delete it serves, key-users is sysadmin only; the testadmin token carries admin, not sysadmin
        given().auth().oauth2(KeycloakTestTokens.adminAccessToken())
                .queryParam("key", "position.deg")
                .when().get("/rest/dictionaries/dictionary/template/key-users")
                .then().statusCode(403);
    }

    @Test
    void matchesAtonAsPlainUserIsForbidden() {
        // matches-aton is @RolesAllowed("editor"): it runs AtoN filters taken from the request body. The
        // testuser identity holds 'user' (granted through the NiordDomain header) and nothing above it.
        given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "aton", Map.of("tags", Map.of("seamark:type", "light_minor")),
                        "values", List.of()))
                .when().put("/rest/dictionaries/matches-aton")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // Positive role-based writes — sysadmin with a real token
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createEntryAsSysadminPersistsAndIsReadable() {
        String key = "IT-REST-create-key";
        createEntryAsSysadmin(key, "IT-REST Created");

        // The new entry must be visible via the (cache-invalidating) read endpoint.
        given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT)
                .then().statusCode(200)
                .body("entries.'" + key + "'.key", is(key))
                .body("entries.'" + key + "'.descs[0].value", is("IT-REST Created"));
    }

    @Test
    void createEntryReturnsCreatedVoReadableAfterCreate() {
        // The POST response VO carries the submitted key/desc AND the server-assigned id (DR1):
        // createEntry now persists the entry directly, so the returned instance receives the
        // generated id. Persistence is additionally verified by reading the entry back from the
        // (cache-invalidating) dictionary endpoint.
        String key = "IT-REST-create-id";
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("key", key,
                        "descs", List.of(Map.of("lang", "en", "value", "Value"))))
                .when().post("/rest/dictionaries/dictionary/" + SEEDED_DICT)
                .then().statusCode(200)
                .body("key", is(key))
                .body("descs[0].value", is("Value"))
                .body("id", notNullValue());
        createdKeys.add(key);

        // Side-effect: the created entry is readable, with its key, language and value intact.
        given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT)
                .then().statusCode(200)
                .body("entries.'" + key + "'.key", is(key))
                .body("entries.'" + key + "'.descs[0].lang", is("en"))
                .body("entries.'" + key + "'.descs[0].value", is("Value"));
    }

    @Test
    void updateEntryAsSysadminChangesValue() {
        String key = "IT-REST-update-key";
        createEntryAsSysadmin(key, "Before");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("key", key,
                        "descs", List.of(Map.of("lang", "en", "value", "After"))))
                .when().put("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/" + key)
                .then().statusCode(200)
                .body("key", is(key))
                .body("descs[0].value", is("After"));

        // Side-effect: the persisted/cached value reflects the update.
        given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT)
                .then().statusCode(200)
                .body("entries.'" + key + "'.descs[0].value", is("After"));
    }

    @Test
    void updateEntryWithMismatchedKeyReturns400() {
        String key = "IT-REST-mismatch-key";
        createEntryAsSysadmin(key, "v");

        // Path key and body key differ -> the endpoint throws NiordWebException(400).
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("key", key + "-other",
                        "descs", List.of(Map.of("lang", "en", "value", "v"))))
                .when().put("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/" + key)
                .then().statusCode(400);
    }

    @Test
    void deleteEntryAsSysadminReturnsTrueAndRemovesIt() {
        String key = "IT-REST-delete-key";
        createEntryAsSysadmin(key, "v");
        // Remove from the tracking list since this test deletes it explicitly.
        createdKeys.remove(key);

        String body = given().auth().oauth2(accessToken())
                .when().delete("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/" + key)
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("true", body);

        // Side-effect: the deleted entry is no longer present in the dictionary.
        given()
                .when().get("/rest/dictionaries/dictionary/" + SEEDED_DICT)
                .then().statusCode(200)
                .body("entries.'" + key + "'", is(nullValue()));
    }

    @Test
    void keyUsersAsSysadminNamesTheScriptResourcesAndTheJavaReaders() {
        String path = "it-rest-key-users-" + System.nanoTime() + "/audio.ftl";
        scriptPaths.add(path);
        Tx.requiringNew().run(() -> {
            ScriptResource resource = new ScriptResource();
            resource.setPath(path);
            resource.setContent("${text('position.deg')} ${text(\"msg.status.\" + msg.status)}");
            resource.updateType();
            em.persist(resource);
        });

        given().auth().oauth2(accessToken())
                .queryParam("key", "position.deg")
                .when().get("/rest/dictionaries/dictionary/template/key-users")
                .then().statusCode(200)
                .body("templates", hasItem(path))
                .body("readers", is(List.of("AUDIO_TEXT", "LAT_LON_DIRECTIVE")))
                .body("$", not(hasKey("prefix")));

        // A runtime-built key: matched by its prefix, which the answer names
        given().auth().oauth2(accessToken())
                .queryParam("key", "msg.status.DRAFT")
                .when().get("/rest/dictionaries/dictionary/message/key-users")
                .then().statusCode(200)
                .body("templates", hasItem(path))
                .body("prefix", is("msg.status."))
                .body("readers", is(List.of()));
    }

    @Test
    void deleteUnknownEntryAsSysadminReturnsFalse() {
        String body = given().auth().oauth2(accessToken())
                .when().delete("/rest/dictionaries/dictionary/" + SEEDED_DICT + "/IT-REST-never-existed")
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("false", body, "deleting a non-existing key must report false");
    }

    @Test
    void reloadResourceBundlesEndpointIsGone() {
        // Nothing copies the resource bundles over the dictionaries' values: the former reload is not mapped
        given().auth().oauth2(accessToken())
                .when().put("/rest/dictionaries/reload-resource-bundles")
                .then().statusCode(404);
    }

    // ------------------------------------------------------------------------------------------- //
    // matches-aton — EDITOR endpoint (the sysadmin identity holds editor)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void matchesAtonWithDomainReturnsMatchingEntry() {
        // With a NiordDomain header the augmentor grants the full {editor,admin,user,sysadmin} chain,
        // so the EDITOR-protected endpoint is reachable. The supplied filter matches the AtoN, so the
        // matching dictionary entry is returned.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "aton", Map.of("tags", Map.of("seamark:type", "light_minor")),
                        "values", List.of(Map.of(
                                "key", "IT-REST-light",
                                "atonFilter", "aton.kv('seamark:type', 'light_minor')"))))
                .when().put("/rest/dictionaries/matches-aton")
                .then().statusCode(200)
                .body("key", is("IT-REST-light"));
    }

    @Test
    void matchesAtonWithDomainReturnsEmptyWhenNoFilterMatches() {
        // The AtoN is a buoy, the supplied light filter does not match -> the service returns null,
        // so the endpoint produces a 204 No Content (a null entity is serialized as an empty body).
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "aton", Map.of("tags", Map.of("seamark:type", "buoy_lateral")),
                        "values", List.of(Map.of(
                                "key", "IT-REST-light",
                                "atonFilter", "aton.kv('seamark:type', 'light_minor')"))))
                .when().put("/rest/dictionaries/matches-aton")
                .then().statusCode(204);
    }

    @Test
    void matchesAtonWithDomainButNoAtonReturns400() {
        // The endpoint requires both aton and values; a missing aton -> NiordWebException(400).
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(Map.of("values", List.of()))
                .when().put("/rest/dictionaries/matches-aton")
                .then().statusCode(400);
    }

    @Test
    void matchesAtonWithDomainAndNoAtonFilterReturnsEmpty() {
        // values present but none carries an atonFilter -> the endpoint short-circuits to null, which
        // is serialized as a 204 No Content with an empty body.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "aton", Map.of("tags", Map.of("seamark:type", "light_minor")),
                        "values", List.of(Map.of("key", "IT-REST-no-filter"))))
                .when().put("/rest/dictionaries/matches-aton")
                .then().statusCode(204);
    }

    @Test
    void matchesAtonRefusesAFilterOutsideTheGrammarAndTheServerStaysUp() {
        // The filter is statements, not an expression. Run as script it would match every AtoN and the
        // entry would come back (200). It is not an aton.k/v/kv expression, so it is refused and matches
        // nothing; the filter is never run as script.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "aton", Map.of("tags", Map.of("seamark:type", "light_minor")),
                        "values", List.of(Map.of(
                                "key", "IT-REST-outside-grammar",
                                "atonFilter", "true; } function other() { return true"))))
                .when().put("/rest/dictionaries/matches-aton")
                .then().statusCode(204);

        // The server still answers
        given()
                .when().get("/rest/dictionaries/names")
                .then().statusCode(200)
                .body("$", hasItem(SEEDED_DICT));
    }
}
