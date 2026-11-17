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

import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.repo.RepoStore;
import org.niord.core.util.UidUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The legacy publication record's export ({@code GET /rest/publications/export}) and its two uploads: the JSON
 * import ({@code POST /rest/publications/upload-publications}, job {@code publication-import}) and the file upload
 * ({@code POST /rest/publications/upload-publication-file/{folder}}), as the administrator.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class LegacyPublicationImportRoundTripITTest extends ImportRoundTripSupport {

    private static final long FROM = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli();
    private static final long TO = Instant.parse("2026-12-31T23:00:00Z").toEpochMilli();

    @Autowired
    RepoStore store;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                cleanups.get(i).run();
            } catch (RuntimeException | AssertionError ignored) {
                // best effort
            }
        }
        cleanups.clear();
    }

    /**
     * (a) A legacy publication exported, deleted and imported comes back; (b) the same export imported over it
     * completes and leaves the publication as it was, apart from its save stamp and revision (PUB2: the writer,
     * {@code BatchPublicationImportWriter}, uses {@code Publication.updatePublication}, which merges the desc rows in
     * place rather than re-adding a language the row has, which broke the unique key).
     */
    @Test
    void aLegacyPublicationComesBackFromItsExportAndImportsOverItself() {
        String categoryId = category();
        String id = UidUtils.newUid();
        cleanups.add(() -> {
            execute("DELETE FROM public.\"PublicationDesc\" WHERE entity_id IN (SELECT id FROM public.\"Publication\""
                    + " WHERE \"publicationId\" = ?1)", id);
            execute("DELETE FROM public.\"Publication\" WHERE \"publicationId\" = ?1", id);
        });
        post("admin", "/rest/publications/publication", publication(id, categoryId));
        Map<String, Object> first = exported(id);

        // (a)
        delete("admin", "/rest/publications/publication/" + id).statusCode(204);
        JobEnd job = importRows("admin", "/rest/publications/upload-publications", "publication-import", List.of(first));
        assertEquals("COMPLETED", job.status(), job.log());
        // The import saves a new revision (2, where the first export said 1); the rest is the same
        assertEquals(without(first, List.of("id", "created", "updated", "revision")),
                without(exported(id), List.of("id", "created", "updated", "revision")), "the publication after the import");

        // (b) imported over itself: COMPLETED, and the same apart from the save stamp and the revision
        Map<String, Object> second = exported(id);
        job = importRows("admin", "/rest/publications/upload-publications", "publication-import", List.of(second));
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(without(second, List.of("updated", "revision")),
                without(exported(id), List.of("updated", "revision")), "the publication after the import over itself");
    }

    /**
     * The export holds every field the publication was created with. The round trip above compares an export with
     * an export, so a field that the save drops on the way in (the create and the import share the value object's
     * constructor) leaves both exports equal and passes it.
     */
    @Test
    void aLegacyPublicationsExportHoldsTheFieldsItWasCreatedWith() {
        String categoryId = category();
        String id = UidUtils.newUid();
        cleanups.add(() -> {
            execute("DELETE FROM public.\"PublicationDesc\" WHERE entity_id IN (SELECT id FROM public.\"Publication\""
                    + " WHERE \"publicationId\" = ?1)", id);
            execute("DELETE FROM public.\"Publication\" WHERE \"publicationId\" = ?1", id);
        });
        post("admin", "/rest/publications/publication", publication(id, categoryId));

        Map<String, Object> exported = exported(id);
        assertEquals(java.util.Arrays.asList("PUBLICATION", "LINK", "DRAFT", categoryId, 3, "MONTHLY", "EXTERNAL", true,
                        Map.of("pageSize", "A4"), Map.of("subtitle", "IT, \"Ærø\" ")),
                java.util.Arrays.asList(exported.get("mainType"), exported.get("type"), exported.get("status"),
                        ((Map<?, ?>) exported.get("category")).get("categoryId"), exported.get("edition"),
                        exported.get("periodicalType"), exported.get("messagePublication"), exported.get("languageSpecific"),
                        exported.get("printSettings"), exported.get("reportParams")),
                "the fields the publication was created with");
        assertEquals(Map.of("da", awkward("IT legacy da"), "en", "IT legacy en"), descs(exported, "title"));
        assertEquals(Map.of("da", "https://example.org/da.pdf", "en", "https://example.org/en.pdf"), descs(exported, "link"));
        assertEquals(Map.of("da", "Se ${title}", "en", "See ${title}"), descs(exported, "messagePublicationFormat"));
    }

    /**
     * The file upload of the legacy publication editor: a file uploaded into a temporary folder for each language is
     * stored there byte for byte under its (reduced) name, and its descriptor answers the link it is served at.
     */
    @Test
    void aPublicationFileUploadedInTwoLanguagesIsStoredByteForByte() {
        String folder = as("admin").when().get("/rest/repo/new-temp-dir").then().statusCode(200).extract().path("path");
        cleanups.add(() -> store.deleteFolder(folder));
        Map<String, String> links = new LinkedHashMap<>();
        Map<String, byte[]> sent = new LinkedHashMap<>();
        for (String lang : List.of("da", "en")) {
            byte[] bytes = ("%PDF-1.4 IT " + lang + " Ærø, \"q\" ").getBytes(StandardCharsets.UTF_8);
            Response r = given().auth().oauth2(accessToken("admin")).header("NiordDomain", "niord-nw")
                    .multiPart("file", "it-legacy-" + lang + ".pdf", bytes, "application/pdf")
                    .multiPart("data", json(map("lang", lang, "title", "IT " + lang)))
                    .when().post("/rest/publications/upload-publication-file/" + folder);
            assertEquals(200, r.statusCode(), r.asString());
            Map<String, Object> desc = r.jsonPath().getMap("");
            assertEquals("it-legacy-" + lang + ".pdf", desc.get("fileName"));
            assertNotNull(desc.get("link"));
            links.put(lang, (String) desc.get("link"));
            sent.put(lang, bytes);
        }
        for (String lang : sent.keySet()) {
            byte[] read = given().when().get(links.get(lang)).then().statusCode(200).extract().asByteArray();
            assertArrayEquals(sent.get(lang), read, "the " + lang + " file at " + links.get(lang));
        }
    }

    private String category() {
        String categoryId = "it-imp-legacy-" + unique();
        post("admin", "/rest/publication-categories/publication-category", map("categoryId", categoryId, "priority", 100,
                "publish", true, "descs", list(map("lang", "da", "name", "IT-kategori"), map("lang", "en", "name", "IT category"))));
        cleanups.add(() -> {
            execute("DELETE FROM public.\"PublicationCategoryDesc\" WHERE entity_id IN (SELECT id FROM"
                    + " public.\"PublicationCategory\" WHERE \"categoryId\" = ?1)", categoryId);
            execute("DELETE FROM public.\"PublicationCategory\" WHERE \"categoryId\" = ?1", categoryId);
        });
        return categoryId;
    }

    private static Map<String, Object> publication(String id, String categoryId) {
        return map("publicationId", id, "mainType", "PUBLICATION", "type", "LINK", "status", "DRAFT",
                "category", map("categoryId", categoryId), "publishDateFrom", FROM, "publishDateTo", TO, "edition", 3,
                "periodicalType", "MONTHLY", "messagePublication", "EXTERNAL", "languageSpecific", true,
                "printSettings", map("pageSize", "A4"), "reportParams", map("subtitle", "IT, \"Ærø\" "),
                "descs", list(map("lang", "da", "title", awkward("IT legacy da"), "link", "https://example.org/da.pdf",
                                "messagePublicationFormat", "Se ${title}"),
                        map("lang", "en", "title", "IT legacy en", "link", "https://example.org/en.pdf",
                                "messagePublicationFormat", "See ${title}")));
    }

    private static Map<String, Object> exported(String id) {
        return getList("admin", "/rest/publications/export").stream()
                .filter(p -> id.equals(p.get("publicationId"))).findFirst()
                .orElseThrow(() -> new AssertionError("no publication " + id + " in the export"));
    }
}
