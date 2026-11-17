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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.publication.TestIds;
import org.niord.core.publication.series.StubIssueRenderService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an anonymous reader (the public site, a citation in a message) sees of a publication after each write that
 * decides it: the public list ({@code /rest/public/v1/publications}), the single public read
 * ({@code /rest/public/v1/publication/{id}}) and the anonymous hydration of cited ids
 * ({@code /rest/publication-issues/by-ids}). A released issue is listed; a retired one leaves the list and the single
 * read and still resolves by id, marked RETIRED; a reinstated one is back; a retired SERIES leaves its released issue
 * listed (DATA-MODEL S2); an open issue is resolvable by id with its document address withheld. A one-off is listed
 * while live and leaves the list when withdrawn or when its public period has ended.
 */
class PublicationPublicReadsRoundTripITTest extends PublicationHttpFixture {

    private static final String SERIES = "/rest/publication-series";
    private static final String ISSUES = "/rest/publication-issues";
    private static final String ONE_OFFS = "/rest/one-off-publications";

    private static final long SEP_1 = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli();
    private static final long SEP_30 = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli();

    private String categoryId;

    @BeforeEach
    void seedTheCategory() {
        categoryId = newCategory();
        StubIssueRenderService.reset();
    }

    @Test
    void aReleasedIssueIsPublicARetiredOneIsNotAndACitationStillResolvesIt() {
        String seriesId = activeWeekly();
        String publicId = (String) object(as("admin").body(Map.of("seriesId", seriesId, "intervalFrom", SEP_1,
                "intervalTo", SEP_30)).post(ISSUES + "/issue")).get("publicId");

        Map<String, Object> open = byId(publicId);
        assertEquals("OPEN", open.get("status"), "an open issue resolves by id");
        // Each loop below asserts per desc; an answer with no desc would pass them all. The read
        // asks for lang=en and answers that language's desc only
        assertEquals(List.of("en"), listOf(open, "descs").stream().map(d -> d.get("lang")).toList(),
                "the English desc the read asked for: " + open);
        listOf(open, "descs").forEach(d -> assertNull(d.get("link"), "with no document address while open: " + d));
        assertFalse(listed(publicId), "an open issue is not on the public list");

        String successor = (String) publish(publicId).get("successorId");
        assertTrue(listed(publicId), "a released issue is on the public list");
        assertEquals(publicId, object(anonymous().get("/rest/public/v1/publication/" + publicId + "?lang=en"))
                .get("publicationId"), "and the single public read finds it");
        Map<String, Object> released = byId(publicId);
        assertEquals("PUBLISHED", released.get("status"), "released, by id");
        assertEquals(List.of("en"), listOf(released, "descs").stream().map(d -> d.get("lang")).toList(),
                "the English desc the read asked for: " + released);
        listOf(released, "descs").forEach(d -> assertNotNull(d.get("link"), "with its document address: " + d));
        assertFalse(listed(successor), "the open successor is not on the public list");

        object(as("admin").put(ISSUES + "/issue/" + publicId + "/retire?reason=Withdrawn for a correction&version="
                + version(publicId)));
        assertFalse(listed(publicId), "a retired issue leaves the public list");
        assertEquals(404, anonymous().get("/rest/public/v1/publication/" + publicId).statusCode(),
                "and the single public read");
        Map<String, Object> retired = byId(publicId);
        assertEquals("RETIRED", retired.get("status"), "a citation of it still resolves, marked RETIRED");
        assertEquals(List.of("en"), listOf(retired, "descs").stream().map(d -> d.get("lang")).toList(),
                "the English desc the read asked for: " + retired);
        listOf(retired, "descs").forEach(d -> assertNotNull(d.get("link"), "its file stays at its address: " + d));

        object(as("admin").put(ISSUES + "/issue/" + publicId + "/reactivate?version=" + version(publicId)));
        assertTrue(listed(publicId), "a reinstated issue is back on the public list");

        int v = intOf(object(as("admin").get(SERIES + "/editable-series/" + seriesId)), "version");
        object(as("admin").body("\"RETIRED\"").put(SERIES + "/series/" + seriesId
                + "/status?reason=The weekly has stopped&version=" + v));
        assertTrue(listed(publicId), "retiring the series leaves its released issue on the public list (DATA-MODEL S2)");
    }

    @Test
    void aOneOffIsPublicWhileLiveAndLeavesTheListWhenWithdrawnOrWhenItsPeriodEnds() {
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("categoryId", categoryId);
        series.put("domainId", DOMAIN);
        series.put("contentMode", "EXTERNAL_LINK");
        series.put("languages", List.of("da", "en"));
        series.put("descs", List.of(Map.of("lang", "da", "name", "IT offentlig " + TestIds.suffix()),
                Map.of("lang", "en", "name", "IT public " + TestIds.suffix())));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("series", series);
        body.put("active", true);
        body.put("links", List.of(Map.of("lang", "da", "value", "https://example.org/da.pdf"),
                Map.of("lang", "en", "value", "https://example.org/en.pdf")));
        Map<String, Object> created = object(as("admin").body(body).post(ONE_OFFS));
        String seriesId = track((String) mapOf(created, "series").get("seriesId"));
        String publicId = (String) created.get("issuePublicId");
        assertTrue(listed(publicId), "a live one-off is on the public list");

        long now = System.currentTimeMillis();
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("publicFrom", now - 2 * 86_400_000L);
        window.put("publicTo", now - 86_400_000L);
        window.put("version", intOf(mapOf(object(as("admin").get(ONE_OFFS + "/" + seriesId)), "series"), "version"));
        object(as("admin").body(window).put(ONE_OFFS + "/" + seriesId + "/public-window"));
        assertFalse(listed(publicId), "a one-off whose period ended is off the public list");

        window.put("publicTo", null);
        window.put("version", intOf(mapOf(object(as("admin").get(ONE_OFFS + "/" + seriesId)), "series"), "version"));
        object(as("admin").body(window).put(ONE_OFFS + "/" + seriesId + "/public-window"));
        assertTrue(listed(publicId), "back on the list with an open-ended period");

        Map<String, Object> off = object(as("admin").get(ONE_OFFS + "/" + seriesId));
        off.put("active", false);
        off.put("reason", "Superseded");
        object(as("admin").body(off).put(ONE_OFFS + "/" + seriesId));
        assertFalse(listed(publicId), "a withdrawn one-off is off the public list");
        assertEquals("RETIRED", byId(publicId).get("status"), "and resolves by id as RETIRED");
    }

    // ------------------------------------------------------------------------------------------- //

    private String activeWeekly() {
        String seriesId = track(TestIds.series());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seriesId", seriesId);
        s.put("categoryId", categoryId);
        s.put("domainId", DOMAIN);
        s.put("contentMode", "GENERATED_FROM_QUERY");
        s.put("cadence", "WEEKLY");
        s.put("cutoffDefault", "RELEASE_MOMENT");
        s.put("nominalCutoffDay", "WEDNESDAY");
        s.put("nominalCutoffTime", "12:00");
        s.put("numberingScheme", "ISO_WEEK_YEAR");
        s.put("timeRelation", "PUBLISHED_IN_INTERVAL");
        s.put("aliveAtCutoff", false);
        s.put("firstIssueStartsAt", SEP_1);
        Map<String, Object> node = new LinkedHashMap<>(Map.of("kind", "messageSeries", "operator", "IN",
                "values", List.of("dma-nm")));
        s.put("criteria", new LinkedHashMap<>(Map.of("schemaVersion", 1, "match", "ALL",
                "criteria", new ArrayList<>(List.of(node)))));
        s.put("availability", "ALL_DOMAINS");
        s.put("languages", List.of("da", "en"));
        s.put("reportId", "standard");
        s.put("pageSize", "A4");
        s.put("pageOrientation", "PORTRAIT");
        s.put("mapThumbnails", false);
        s.put("messagePublication", "EXTERNAL");
        s.put("descs", List.of(
                Map.of("lang", "da", "name", "IT offentlig", "fileNamePattern", "pub-${year}-${week}-da",
                        "messageReferenceFormat", "EfS ${week}"),
                Map.of("lang", "en", "name", "IT public", "fileNamePattern", "pub-${year}-${week}-en",
                        "messageReferenceFormat", "NtM ${week}")));
        object(as("admin").body(s).post(SERIES + "/series"));
        int v = intOf(object(as("admin").get(SERIES + "/editable-series/" + seriesId)), "version");
        object(as("admin").body("\"ACTIVE\"").put(SERIES + "/series/" + seriesId + "/status?version=" + v));
        return seriesId;
    }

    private int version(String publicId) {
        return intOf(object(as("admin").get(ISSUES + "/editable-issue/" + publicId)), "version");
    }

    private Map<String, Object> publish(String publicId) {
        Map<String, Object> rail = object(as("admin").get(ISSUES + "/issue/" + publicId + "/publish-checklist?cutoff="
                + SEP_30));
        List<String> acknowledged = new ArrayList<>();
        for (Map<String, Object> row : listOf(rail, "rows")) {
            if (Boolean.TRUE.equals(row.get("acknowledgeable")) && !Boolean.TRUE.equals(row.get("passed"))
                    && row.get("acknowledgeCode") != null) {
                acknowledged.add((String) row.get("acknowledgeCode"));
            }
        }
        return object(as("admin").body(Map.of("acknowledgedWarnings", acknowledged, "cutoff", SEP_30,
                "version", version(publicId))).put(ISSUES + "/issue/" + publicId + "/publish"));
    }

    /** Whether the anonymous public list holds the issue. */
    private boolean listed(String publicId) {
        return array(anonymous().get("/rest/public/v1/publications?lang=en")).stream()
                .anyMatch(p -> publicId.equals(p.get("publicationId")));
    }

    /** The issue as an anonymous citation resolves it. */
    private Map<String, Object> byId(String publicId) {
        List<Map<String, Object>> rows = array(anonymous().get(ISSUES + "/by-ids?ids=" + publicId + "&lang=en"));
        assertEquals(1, rows.size(), "the id resolves: " + rows);
        return rows.getFirst();
    }
}
