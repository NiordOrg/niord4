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

import org.junit.jupiter.api.Test;
import org.niord.core.publication.TestIds;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A link one-off whose languages change after it was created and released: created in
 * Danish and English, then edited to English and German, the Danish name and link dropped. What the series, its
 * released issue and the public reads hold afterwards, in each language.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class OneOffLanguageChangeRoundTripITTest extends PublicationHttpFixture {

    private static final String BASE = "/rest/one-off-publications";

    private static final String ISSUES = "/rest/publication-issues";

    /**
     * The edit stores the series' new languages and names, and the German link; the Danish desc the edit leaves out
     * goes from the released issue with its link, so the one-off's editor no longer lists it and the anonymous public
     * read in Danish answers as for any language the publication does not have (ONEOFF1).
     */
    @Test
    void aOneOffWhoseLanguagesChangeDropsTheLeftLanguagesDocument() {
        String categoryId = newCategory();
        String name = "IT lang " + TestIds.suffix();
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("categoryId", categoryId);
        series.put("domainId", DOMAIN);
        series.put("contentMode", "EXTERNAL_LINK");
        series.put("languages", new ArrayList<>(List.of("da", "en")));
        series.put("descs", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("lang", "en", "name", name)),
                new LinkedHashMap<>(Map.of("lang", "da", "name", name + " (da)")))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("series", series);
        body.put("active", true);
        body.put("links", new ArrayList<>(List.of(link("da", "https://example.org/da.pdf"), link("en", "https://example.org/en.pdf"))));
        Map<String, Object> created = object(as("admin").body(body).post(BASE + "?lang=en"));
        String seriesId = track((String) mapOf(created, "series").get("seriesId"));
        String issue = (String) read(seriesId).get("issuePublicId");
        assertEquals(List.of("da", "en"), issueDescLangs(seriesId), "the issue's languages after the create");

        // Danish dropped, German added
        Map<String, Object> edit = read(seriesId);
        Map<String, Object> s = mapOf(edit, "series");
        s.put("languages", new ArrayList<>(List.of("en", "de")));
        s.put("descs", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("lang", "en", "name", name)),
                new LinkedHashMap<>(Map.of("lang", "de", "name", name + " (de)")))));
        edit.put("links", new ArrayList<>(List.of(link("en", "https://example.org/en.pdf"), link("de", "https://example.org/de.pdf"))));
        object(as("admin").body(edit).put(BASE + "/" + seriesId + "?lang=en"));

        Map<String, Object> after = read(seriesId);
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("series languages", mapOf(after, "series").get("languages"));
        outcome.put("series desc languages", new TreeMap<>(descsByLang(mapOf(after, "series"))).keySet().stream().toList());
        outcome.put("links", links(after));
        outcome.put("issue desc languages", issueDescLangs(seriesId));
        outcome.put("public read in Danish", publicLink(issue, "da"));
        outcome.put("public read in German", publicLink(issue, "de"));
        // The series is in English and German, and so is its released issue: no Danish desc, no Danish link in the
        // editor, and the Danish read falls back to a language the publication has.
        Object danish = outcome.remove("public read in Danish");
        assertEquals(List.of(List.of("en", "de"), List.of("de", "en"),
                        Map.of("de", "https://example.org/de.pdf", "en", "https://example.org/en.pdf"), List.of("de", "en"),
                        "https://example.org/de.pdf"),
                new ArrayList<>(outcome.values()), "after the language change: " + outcome);
        assertNotEquals("https://example.org/da.pdf", danish, "the dropped Danish document is still served");
        assertTrue(Set.of("https://example.org/en.pdf", "https://example.org/de.pdf").contains(danish),
                "the Danish read answers with a language the publication has: " + danish);

        // R2-2: the released Danish link's withdrawal is in the issue's trail
        List<Map<String, Object>> cleared = audit(issue, "LINK_CLEARED");
        assertEquals(1, cleared.size(), "one LINK_CLEARED entry: " + cleared);
        assertEquals("da", mapOf(cleared.getFirst(), "detail").get("lang"));
        assertEquals("https://example.org/da.pdf", mapOf(cleared.getFirst(), "detail").get("link"),
                "the entry names the link that went");
    }

    /**
     * R2-2: a released uploaded one-off whose series leaves Danish takes the Danish file off the public list. The
     * issue's trail says so with a FILE_CLEARED entry naming the file, as a clear of the file on its own would.
     */
    @Test
    void aReleasedFileTheLanguageDropTakesOffIsInTheTrail() {
        String categoryId = newCategory();
        String name = "IT lang file " + TestIds.suffix();
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("categoryId", categoryId);
        series.put("domainId", DOMAIN);
        series.put("contentMode", "UPLOADED_FILE");
        series.put("languages", new ArrayList<>(List.of("da", "en")));
        series.put("descs", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("lang", "en", "name", name)),
                new LinkedHashMap<>(Map.of("lang", "da", "name", name + " (da)")))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("series", series);
        body.put("active", true);
        body.put("links", new ArrayList<>());
        Map<String, Object> created = object(as("admin").body(body).post(BASE + "?lang=en"));
        String seriesId = track((String) mapOf(created, "series").get("seriesId"));
        String issue = (String) created.get("issuePublicId");
        for (String lang : List.of("da", "en")) {
            int v = intOf(object(as("admin").get(ISSUES + "/editable-issue/" + issue)), "version");
            given().auth().oauth2(accessToken("admin")).header("NiordDomain", DOMAIN)
                    .multiPart("file", "list-" + lang + ".pdf", ("bytes " + lang).getBytes(StandardCharsets.UTF_8),
                            "application/pdf")
                    .post(ISSUES + "/issue/" + issue + "/file/" + lang + "?version=" + v).then().statusCode(200);
        }
        int v = intOf(object(as("admin").get(ISSUES + "/editable-issue/" + issue)), "version");
        object(as("admin").body(Map.of("version", v)).put(ISSUES + "/issue/" + issue + "/publish"));
        assertEquals("PUBLISHED", read(seriesId).get("issueStatus"), "released");
        assertEquals(List.of(), audit(issue, "FILE_CLEARED"), "no file cleared before the edit");

        Map<String, Object> edit = read(seriesId);
        Map<String, Object> s = mapOf(edit, "series");
        s.put("languages", new ArrayList<>(List.of("en")));
        s.put("descs", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("lang", "en", "name", name)))));
        object(as("admin").body(edit).put(BASE + "/" + seriesId + "?lang=en"));

        assertEquals(List.of("en"), issueDescLangs(seriesId), "the Danish desc is gone");
        List<Map<String, Object>> cleared = audit(issue, "FILE_CLEARED");
        assertEquals(1, cleared.size(), "one FILE_CLEARED entry: " + cleared);
        assertEquals("da", mapOf(cleared.getFirst(), "detail").get("lang"));
        assertEquals("list-da.pdf", mapOf(cleared.getFirst(), "detail").get("fileName"),
                "the entry names the file that went");
    }

    /** The issue's trail entries of the given action. */
    private List<Map<String, Object>> audit(String publicId, String action) {
        return array(as("admin").get(ISSUES + "/issue/" + publicId + "/audit")).stream()
                .filter(e -> action.equals(e.get("action")))
                .toList();
    }

    private static Map<String, Object> link(String lang, String value) {
        return new LinkedHashMap<>(Map.of("lang", lang, "value", value));
    }

    private static Map<String, String> links(Map<String, Object> oneOff) {
        Map<String, String> out = new TreeMap<>();
        listOf(oneOff, "links").forEach(l -> out.put((String) l.get("lang"), (String) l.get("value")));
        return out;
    }

    private Map<String, Object> read(String seriesId) {
        return object(as("admin").get(BASE + "/" + seriesId + "?lang=en"));
    }

    private List<String> issueDescLangs(String seriesId) {
        return rows("SELECT d.lang FROM public.\"PublicationIssueDesc\" d JOIN public.\"PublicationIssue\" i"
                + " ON i.id = d.entity_id JOIN public.\"PublicationSeries\" s ON s.id = i.series_id"
                + " WHERE s.\"seriesId\" = :s ORDER BY d.lang", "s", seriesId).stream().map(r -> (String) r[0]).toList();
    }

    /** The link the anonymous single public read gives in the language (the one it falls back to included) */
    private static Object publicLink(String publicId, String lang) {
        Map<String, Object> p = object(anonymous().get("/rest/public/v1/publication/" + publicId + "?lang=" + lang));
        List<Map<String, Object>> descs = listOf(p, "descs");
        return descs.isEmpty() ? p.get("link") : descs.getFirst().get("link");
    }
}
