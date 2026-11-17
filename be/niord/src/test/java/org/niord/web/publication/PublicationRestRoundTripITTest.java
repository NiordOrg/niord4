/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.web.publication;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.niord.core.util.UidUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The legacy publication record ({@code PublicationRestService}, {@code /rest/publications}) saved, read back and
 * saved again over HTTP, with two languages: create, the editor's read, an unchanged save of that read, an update
 * that changes one language and removes the other, a status change and the delete, with the desc rows counted after
 * each step. The release, the report generation and the uploads have their own ITs (PublicationRestReleaseITTest,
 * PublicationRestCrudITTest).
 */
class PublicationRestRoundTripITTest extends PublicationHttpFixture {

    private static final String BASE = "/rest/publications";

    private static final long FROM = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli();
    private static final long TO = Instant.parse("2026-12-31T23:00:00Z").toEpochMilli();

    @Test
    void aLegacyPublicationInTwoLanguagesIsCreatedReadBackActivatedAndDeleted() {
        String id = newLegacyPublication();
        Map<String, Object> sent = publication(id, categoryId);
        assertPublication(sent, editable(id), "the editor's read after the create");
        assertEquals(2, descRows(id), "one desc row per language after the create");

        // An update whose languages are all new to the row: the old rows go, the new one is stored
        Map<String, Object> update = editable(id);
        update.put("descs", new ArrayList<>(List.of(desc("de", "IT legacy de", "https://example.org/de.pdf"))));
        update.put("edition", 4);
        update.put("publishDateTo", TO + 1000);
        object(as("admin").body(update).put(BASE + "/publication/" + id));
        Map<String, Object> afterUpdate = editable(id);
        assertEquals(1, descRows(id), "the replaced languages' rows are gone");
        assertEquals("IT legacy de", descsByLang(afterUpdate).get("de").get("title"), "the new language's title");
        assertEquals(4, intOf(afterUpdate, "edition"), "the new edition");
        assertEquals(TO + 1000, millis(afterUpdate.get("publishDateTo")), "the new end");

        Map<String, Object> active = object(as("admin").body(Map.of("publicationId", id, "status", "ACTIVE"))
                .put(BASE + "/update-status"));
        assertEquals("ACTIVE", active.get("status"), "activated");
        assertEquals("ACTIVE", editable(id).get("status"), "activated, read back");

        as("admin").delete(BASE + "/publication/" + id).then().statusCode(204);
        assertEquals(List.of(), array(anonymous().get(BASE + "/publication/" + id)), "deleted");
        assertEquals(0, descRows(id), "with its desc rows");
    }

    /**
     * A save that keeps a language the publication already has is stored (PUB2): the descs are merged in place, so
     * a language the row has keeps its desc row and is updated, rather than re-added beside its own orphan, which
     * Hibernate inserted before it deleted and so broke the unique key (lang, entity_id) of {@code PublicationDesc}.
     */
    @Test
    void anUnchangedSaveAndARetitleOfALegacyPublicationAreStored() {
        String id = newLegacyPublication();
        Map<String, Object> sent = publication(id, categoryId);

        Response unchanged = as("admin").body(editable(id)).put(BASE + "/publication/" + id);
        assertEquals(200, unchanged.statusCode(), "the editor's read saved unchanged: " + unchanged.asString());
        assertPublication(sent, editable(id), "the unchanged save");
        assertEquals(2, descRows(id), "still one desc row per language");

        Map<String, Object> retitled = editable(id);
        descsByLang(retitled).get("en").put("title", "IT legacy en, retitled");
        Response retitle = as("admin").body(retitled).put(BASE + "/publication/" + id);
        assertEquals(200, retitle.statusCode(), "a retitle of one of its languages: " + retitle.asString());
        descsByLang(sent).get("en").put("title", "IT legacy en, retitled");
        assertPublication(sent, editable(id), "the retitle");
        assertEquals(2, descRows(id), "and still the two desc rows");
    }

    /** A save whose body leaves out one of the publication's languages removes that language's desc row. */
    @Test
    void aSaveWithoutOneLanguageRemovesIt() {
        String id = newLegacyPublication();
        Map<String, Object> sent = publication(id, categoryId);

        Map<String, Object> onlyEnglish = editable(id);
        onlyEnglish.put("descs", new ArrayList<>(List.of(descsByLang(onlyEnglish).get("en"))));
        Response save = as("admin").body(onlyEnglish).put(BASE + "/publication/" + id);
        assertEquals(200, save.statusCode(), "the save without Danish: " + save.asString());

        sent.put("descs", new ArrayList<>(List.of(descsByLang(sent).get("en"))));
        assertPublication(sent, editable(id), "the save without Danish");
        assertEquals(1, descRows(id), "one desc row left");
    }

    // ------------------------------------------------------------------------------------------- //

    private String categoryId;

    /** A legacy publication in two languages, created over REST and removed afterwards. */
    private String newLegacyPublication() {
        categoryId = newCategory();
        String id = UidUtils.newUid();
        cleanupSql.add("DELETE FROM public.\"PublicationDesc\" WHERE entity_id IN (SELECT id FROM public.\"Publication\""
                + " WHERE \"publicationId\" = '" + id + "')");
        cleanupSql.add("DELETE FROM public.\"Publication\" WHERE \"publicationId\" = '" + id + "'");
        Map<String, Object> sent = publication(id, categoryId);
        assertPublication(sent, object(as("admin").body(sent).post(BASE + "/publication")), "the create's answer");
        return id;
    }

    private static Map<String, Object> publication(String id, String categoryId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("publicationId", id);
        p.put("mainType", "PUBLICATION");
        p.put("type", "LINK");
        p.put("status", "DRAFT");
        p.put("category", Map.of("categoryId", categoryId));
        p.put("publishDateFrom", FROM);
        p.put("publishDateTo", TO);
        p.put("edition", 3);
        p.put("periodicalType", "MONTHLY");
        p.put("messagePublication", "EXTERNAL");
        p.put("languageSpecific", true);
        p.put("printSettings", new LinkedHashMap<>(Map.of("pageSize", "A4")));
        p.put("reportParams", new LinkedHashMap<>(Map.of("subtitle", "IT")));
        p.put("descs", new ArrayList<>(List.of(desc("da", "IT legacy da", "https://example.org/da.pdf"),
                desc("en", "IT legacy en", "https://example.org/en.pdf"))));
        return p;
    }

    private static Map<String, Object> desc(String lang, String title, String link) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("lang", lang);
        d.put("title", title);
        d.put("link", link);
        d.put("messagePublicationFormat", "Se " + title);
        return d;
    }

    private Map<String, Object> editable(String id) {
        return object(as("admin").get(BASE + "/editable-publication/" + id));
    }

    private long descRows(String id) {
        return count("SELECT count(*) FROM public.\"PublicationDesc\" d JOIN public.\"Publication\" p"
                + " ON p.id = d.entity_id WHERE p.\"publicationId\" = :id", "id", id);
    }

    private static void assertPublication(Map<String, Object> sent, Map<String, Object> read, String what) {
        for (String key : List.of("publicationId", "mainType", "type", "status", "periodicalType",
                "messagePublication", "languageSpecific")) {
            assertEquals(sent.get(key), read.get(key), what + ": " + key);
        }
        assertEquals(intOf(sent, "edition"), intOf(read, "edition"), what + ": edition");
        assertEquals(millis(sent.get("publishDateFrom")), millis(read.get("publishDateFrom")), what + ": from");
        assertEquals(millis(sent.get("publishDateTo")), millis(read.get("publishDateTo")), what + ": to");
        assertEquals(mapOf(sent, "category").get("categoryId"), mapOf(read, "category").get("categoryId"),
                what + ": category");
        assertEquals(sent.get("printSettings"), read.get("printSettings"), what + ": printSettings");
        assertEquals(sent.get("reportParams"), read.get("reportParams"), what + ": reportParams");
        Map<String, Map<String, Object>> readDescs = descsByLang(read);
        descsByLang(sent).forEach((lang, d) -> {
            for (String field : List.of("title", "link", "messagePublicationFormat")) {
                assertEquals(d.get(field), readDescs.get(lang).get(field), what + ": " + lang + " " + field);
            }
        });
        assertEquals(descsByLang(sent).keySet(), readDescs.keySet(), what + ": the languages");
    }
}
