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
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.publication.TestIds;
import org.niord.test.ITTest;
import org.niord.test.Tx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value longer than its column is refused with 400 and the column's limit, not 500 (ERR7), on four surfaces: an
 * area name (255), a dictionary value (1,000), a setting value (2,000, stored as JSON) and a publication category's
 * name and description (255). Each refusal is followed by a read in SQL or over REST that shows nothing was stored,
 * and each surface also takes a value of exactly the limit, which is stored (the right path).
 *
 * <p>The publication controllers answer this refusal as a plain problem detail, not a coded publication refusal: it
 * comes from {@code RestExceptionStatusAdvice}, which every controller shares.
 *
 * <p>Every write here commits on the server, so what a test stores is removed afterwards: areas and dictionary
 * entries over REST, the setting given back its value, publication categories in SQL. This is a framework-coupled
 * test, see docs/framework-coupled-tests.md.
 */
class ValueTooLongRefusalsITTest extends ITTest {

    private static final String DICT = "web";

    /** An editable, String-typed setting the IT start always writes. */
    private static final String SETTING = "documentationUrl";

    @PersistenceContext
    EntityManager em;

    private final List<Integer> areas = new ArrayList<>();
    private final List<String> dictionaryKeys = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestWrote() {
        for (Integer id : areas) {
            sysadmin().delete("/rest/areas/area/" + id);
        }
        for (String key : dictionaryKeys) {
            sysadmin().delete("/rest/dictionaries/dictionary/" + DICT + "/" + key);
        }
        if (!categories.isEmpty()) {
            Tx.requiringNew().run(() -> {
                em.createNativeQuery("DELETE FROM public.\"PublicationCategoryDesc\" WHERE entity_id IN"
                                + " (SELECT id FROM public.\"PublicationCategory\" WHERE \"categoryId\" IN (:ids))")
                        .setParameter("ids", categories).executeUpdate();
                em.createNativeQuery("DELETE FROM public.\"PublicationCategory\" WHERE \"categoryId\" IN (:ids)")
                        .setParameter("ids", categories).executeUpdate();
            });
        }
        areas.clear();
        dictionaryKeys.clear();
        categories.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // An area name: varchar(255)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anAreaNameLongerThanItsColumnIsRefusedWith400AndNothingIsStored() {
        String name = text("IT-TOO-LONG-AREA-" + TestIds.suffix(), 256);
        Response r = sysadmin().body(Map.of("descs", List.of(Map.of("lang", "en", "name", name))))
                .post("/rest/areas/area/");
        assertTooLong(r, 255, "an area name of 256 characters");
        assertEquals(0L, count("SELECT count(*) FROM public.\"AreaDesc\" WHERE name = :v", name),
                "no area holds the name");

        String atTheLimit = text("IT-TOO-LONG-AREA-" + TestIds.suffix(), 255);
        Integer id = sysadmin().body(Map.of("descs", List.of(Map.of("lang", "en", "name", atTheLimit))))
                .post("/rest/areas/area/").then().statusCode(200).extract().path("id");
        areas.add(id);
        assertEquals(1L, count("SELECT count(*) FROM public.\"AreaDesc\" WHERE name = :v", atTheLimit),
                "a name of exactly 255 characters is stored");
    }

    // ------------------------------------------------------------------------------------------- //
    // A dictionary value: varchar(1000)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aDictionaryValueLongerThanItsColumnIsRefusedWith400AndNothingIsStored() {
        String key = "IT-TOO-LONG-" + TestIds.suffix();
        dictionaryKeys.add(key);
        Response r = sysadmin().body(entry(key, text("v", 1001))).post("/rest/dictionaries/dictionary/" + DICT);
        assertTooLong(r, 1000, "a dictionary value of 1,001 characters");
        assertEquals(0L, count("SELECT count(*) FROM public.\"DictionaryEntry\" WHERE dict_key = :v", key),
                "no entry was stored");
        assertFalse(entryKeys().contains(key), "the dictionary read over REST has no such entry");

        String atTheLimit = text("v", 1000);
        sysadmin().body(entry(key, atTheLimit)).post("/rest/dictionaries/dictionary/" + DICT)
                .then().statusCode(200);
        assertEquals(1L, count("SELECT count(*) FROM public.\"DictionaryEntryDesc\" WHERE value = :v", atTheLimit),
                "a value of exactly 1,000 characters is stored");
    }

    // ------------------------------------------------------------------------------------------- //
    // A setting value: varchar(2000), which holds the value as JSON
    // ------------------------------------------------------------------------------------------- //

    /**
     * The column holds the value as JSON ({@code JpaJsonAttributeConverter}), so a text value is stored with its two
     * quotes: 1,998 characters of plain text fill the 2,000 (fewer when the text has characters JSON escapes).
     */
    @Test
    void aSettingValueLongerThanItsColumnIsRefusedWith400AndTheValueIsKept() {
        Object before = settingValue();
        assertNotNull(before, "the setting has a value to keep");
        try {
            Response r = sysadmin().body(setting(text("https://example.org/", 1999)))
                    .put("/rest/settings/setting/" + SETTING);
            assertTooLong(r, 2000, "a setting value of 1,999 characters, 2,001 with its quotes");
            assertEquals(before, settingValue(), "the setting keeps its value");

            String atTheLimit = text("https://example.org/", 1998);
            sysadmin().body(setting(atTheLimit)).put("/rest/settings/setting/" + SETTING).then().statusCode(200);
            assertEquals(atTheLimit, settingValue(), "a value of 1,998 characters, 2,000 with its quotes, is stored");
        } finally {
            sysadmin().body(setting(before)).put("/rest/settings/setting/" + SETTING).then().statusCode(200);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // A publication category's name and description: varchar(255)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aPublicationCategoryNameLongerThanItsColumnIsRefusedWith400AsAProblemDetail() {
        String id = TestIds.category();
        categories.add(id);
        Response r = admin().body(category(id, text("IT too long ", 256), null))
                .post("/rest/publication-categories/publication-category");
        assertTooLong(r, 255, "a category name of 256 characters");
        assertNull(r.jsonPath().get("code"), "a plain problem detail, not a coded publication refusal");
        assertEquals(0L, count("SELECT count(*) FROM public.\"PublicationCategory\" WHERE \"categoryId\" = :v", id),
                "no category was stored");

        String atTheLimit = text("IT at the limit ", 255);
        admin().body(category(id, atTheLimit, null)).post("/rest/publication-categories/publication-category")
                .then().statusCode(200);
        assertEquals(1L, count("SELECT count(*) FROM public.\"PublicationCategoryDesc\" WHERE name = :v", atTheLimit),
                "a name of exactly 255 characters is stored");
    }

    @Test
    void aPublicationCategoryUpdateWithADescriptionLongerThanItsColumnIsRefusedAndKeepsTheStoredOne() {
        String id = TestIds.category();
        categories.add(id);
        admin().body(category(id, "IT category", "For mariners"))
                .post("/rest/publication-categories/publication-category").then().statusCode(200);

        Response r = admin().body(category(id, "IT category", text("IT too long ", 256)))
                .put("/rest/publication-categories/publication-category/" + id);
        assertTooLong(r, 255, "a category description of 256 characters");
        assertEquals(1L, count("SELECT count(*) FROM public.\"PublicationCategoryDesc\" d"
                        + " JOIN public.\"PublicationCategory\" c ON c.id = d.entity_id"
                        + " WHERE c.\"categoryId\" = :v AND d.description = 'For mariners'", id),
                "the stored description is kept");
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** 400, a problem detail that names the limit, and none of the database's own words. */
    private static void assertTooLong(Response r, int limit, String what) {
        assertEquals(400, r.statusCode(), what + " answered " + r.statusCode() + ": " + r.asString());
        String detail = r.jsonPath().getString("detail");
        assertNotNull(detail, what + ": " + r.asString());
        assertTrue(detail.contains("at most " + limit + " characters"), what + ": " + detail);
        assertFalse(detail.contains("character varying"), "the database's sentence stays in the log: " + detail);
    }

    /** The text repeated and cut to exactly {@code length} characters. */
    private static String text(String start, int length) {
        StringBuilder sb = new StringBuilder(start);
        while (sb.length() < length) {
            sb.append('x');
        }
        return sb.substring(0, length);
    }

    private static RequestSpecification sysadmin() {
        return given().auth().oauth2(accessToken()).contentType(ContentType.JSON);
    }

    private static RequestSpecification admin() {
        return given().auth().oauth2(accessToken("admin")).header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON);
    }

    private static Map<String, Object> entry(String key, String value) {
        return Map.of("key", key, "descs", List.of(Map.of("lang", "en", "value", value)));
    }

    private static Map<String, Object> setting(Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", SETTING);
        body.put("value", value);
        return body;
    }

    private static Map<String, Object> category(String id, String name, String description) {
        Map<String, Object> desc = new LinkedHashMap<>();
        desc.put("lang", "en");
        desc.put("name", name);
        desc.put("description", description);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", id);
        body.put("priority", 50);
        body.put("publish", true);
        body.put("descs", List.of(desc));
        return body;
    }

    private Object settingValue() {
        return sysadmin().get("/rest/settings/setting/" + SETTING).then().statusCode(200).extract().path("value");
    }

    private List<String> entryKeys() {
        return sysadmin().get("/rest/dictionaries/dictionary/" + DICT).then().statusCode(200)
                .extract().jsonPath().getList("entries.key", String.class);
    }

    private long count(String sql, String value) {
        return Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(sql)
                .setParameter("v", value).getSingleResult()).longValue());
    }
}
