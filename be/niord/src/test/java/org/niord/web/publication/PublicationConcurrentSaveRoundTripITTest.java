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
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes of one publication that arrive at the same moment, as two admins at two desks press the same button
 * together: saves of a series composed against one revision, and publishes of one open issue. One is stored and
 * nothing is half-written; how the others are refused is what the two tests say.
 */
class PublicationConcurrentSaveRoundTripITTest extends PublicationHttpFixture {

    private static final String BASE = "/rest/publication-series";

    private static final int SAVES = 6;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Saves composed against one revision and arriving together can all pass the revision check (each reads the
     * same stored revision); the first commit moves it, and every other one fails at the write with an
     * optimistic-lock failure. Those losers get the same coded 409 {@code STALE_VERSION} a stale save gets when it
     * arrives a moment later, not a 500 with no code (LOCK1). A loser at the write carries no revisions, since none
     * is known there; a loser at the check carries both. Nothing is half-written: one body is stored whole. Up to
     * five rounds are fired until a save loses at the write, because that needs two saves to overlap on the server.
     */
    @Test
    void savesOfOneRevisionArrivingTogetherStoreOneAndAnswerTheOthers409() throws Exception {
        String categoryId = newCategory();
        String seriesId = track(TestIds.series());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seriesId", seriesId);
        s.put("categoryId", categoryId);
        s.put("domainId", DOMAIN);
        s.put("contentMode", "NONE");
        s.put("cadence", "NONE");
        s.put("numberingScheme", "NONE");
        s.put("nextIssueCreation", "MANUAL");
        s.put("languages", List.of("da", "en"));
        s.put("descs", List.of(Map.of("lang", "da", "name", "IT samtidig"), Map.of("lang", "en", "name", "IT together")));
        object(as("admin").body(s).post(BASE + "/series"));

        String token = accessToken("admin");
        JsonMapper json = JsonMapper.builder().build();
        int lostAtTheWrite = 0;
        for (int round = 0; round < 5 && lostAtTheWrite == 0; round++) {
            Map<String, Object> loaded = object(as("admin").get(BASE + "/editable-series/" + seriesId));
            List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
            for (int i = 0; i < SAVES; i++) {
                int desk = round * SAVES + i;
                Map<String, Object> body = new LinkedHashMap<>(loaded);
                body.put("descs", List.of(Map.of("lang", "da", "name", "Gemt af skrivebord " + desk),
                        Map.of("lang", "en", "name", "Saved by desk " + desk)));
                body.put("sortOrder", desk);
                pending.add(client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + BASE
                                        + "/series/" + seriesId))
                                .timeout(Duration.ofSeconds(60))
                                .header("Authorization", "Bearer " + token)
                                .header("NiordDomain", DOMAIN)
                                .header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString(
                                        JsonMapper.builder().build().writeValueAsString(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()));
            }
            List<HttpResponse<String>> responses = new ArrayList<>();
            List<String> answers = new ArrayList<>();
            for (CompletableFuture<HttpResponse<String>> f : pending) {
                HttpResponse<String> r = f.get(60, TimeUnit.SECONDS);
                responses.add(r);
                answers.add(r.statusCode() + " " + r.body());
            }
            long stored = 0;
            for (HttpResponse<String> r : responses) {
                assertTrue(r.statusCode() == 200 || r.statusCode() == 409,
                        "every save is stored or refused as stale, none fails: " + answers);
                if (r.statusCode() == 200) {
                    stored++;
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> refusal = json.readValue(r.body(), Map.class);
                assertEquals("STALE_VERSION", refusal.get("code"), "the refusal's code: " + answers);
                assertTrue(refusal.get("message") instanceof String m && !m.isBlank(),
                        "the refusal says what to do: " + answers);
                if (!refusal.containsKey("storedVersion")) {
                    lostAtTheWrite++;
                }
            }
            assertEquals(1, stored, "exactly one save of the round stored: " + answers);

            Map<String, Object> after = object(as("admin").get(BASE + "/editable-series/" + seriesId));
            int desk = ((Number) after.get("sortOrder")).intValue();
            assertEquals("Saved by desk " + desk, descsByLang(after).get("en").get("name"),
                    "the stored series is one body, whole");
            assertEquals("Gemt af skrivebord " + desk, descsByLang(after).get("da").get("name"),
                    "both of its languages");
        }
        // The saves that lost the race at the write used to answer 500 with no code; they answer 409 STALE_VERSION,
        // like a stale save one moment later. At least one such loser must have been seen, or the test proved
        // nothing about the write.
        assertTrue(lostAtTheWrite > 0, "in five rounds of six saves at once, no save lost the race at the write");
    }

    /** Publishes of one open issue pressed together: one release, the rest refused with 409, one frozen list. */
    @Test
    void publishesOfOneIssueArrivingTogetherReleaseItOnce() throws Exception {
        String categoryId = newCategory();
        String seriesId = track(TestIds.series());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seriesId", seriesId);
        s.put("categoryId", categoryId);
        s.put("domainId", DOMAIN);
        s.put("kind", "UNSCHEDULED");
        s.put("contentMode", "GENERATED_FROM_QUERY");
        s.put("cadence", "NONE");
        s.put("numberingScheme", "NONE");
        s.put("nextIssueCreation", "MANUAL");
        s.put("timeRelation", "IN_FORCE_AT_CUTOFF");
        s.put("aliveAtCutoff", true);
        s.put("criteria", Map.of("schemaVersion", 1, "match", "ALL", "criteria",
                List.of(Map.of("kind", "messageSeries", "operator", "IN", "values", List.of("dma-nm")))));
        s.put("availability", "OWNER_ONLY");
        s.put("languages", List.of("da", "en"));
        s.put("reportId", "standard");
        s.put("pageSize", "A4");
        s.put("pageOrientation", "PORTRAIT");
        s.put("mapThumbnails", false);
        s.put("descs", List.of(Map.of("lang", "da", "name", "IT samtidig udgivelse", "fileNamePattern", "samtidig-da"),
                Map.of("lang", "en", "name", "IT concurrent release", "fileNamePattern", "concurrent-en")));
        object(as("admin").body(s).post(BASE + "/series"));
        int sv = intOf(object(as("admin").get(BASE + "/editable-series/" + seriesId)), "version");
        object(as("admin").body("\"ACTIVE\"").put(BASE + "/series/" + seriesId + "/status?version=" + sv));
        String publicId = (String) object(as("admin").body(Map.of("seriesId", seriesId))
                .post("/rest/publication-issues/issue")).get("publicId");
        int version = intOf(object(as("admin").get("/rest/publication-issues/editable-issue/" + publicId)), "version");
        List<String> acknowledged = new ArrayList<>();
        for (Map<String, Object> row : listOf(object(as("admin").get("/rest/publication-issues/issue/" + publicId
                + "/publish-checklist")), "rows")) {
            if (Boolean.TRUE.equals(row.get("acknowledgeable")) && !Boolean.TRUE.equals(row.get("passed"))
                    && row.get("acknowledgeCode") != null) {
                acknowledged.add((String) row.get("acknowledgeCode"));
            }
        }
        String body = JsonMapper.builder().build().writeValueAsString(
                Map.of("acknowledgedWarnings", acknowledged, "version", version));

        String token = accessToken("admin");
        List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pending.add(client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                    + "/rest/publication-issues/issue/" + publicId + "/publish"))
                            .timeout(Duration.ofSeconds(60))
                            .header("Authorization", "Bearer " + token)
                            .header("NiordDomain", DOMAIN)
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        List<String> answers = new ArrayList<>();
        long released = 0;
        for (CompletableFuture<HttpResponse<String>> f : pending) {
            HttpResponse<String> r = f.get(60, TimeUnit.SECONDS);
            answers.add(r.statusCode() + " " + r.body());
            released += r.statusCode() == 200 ? 1 : 0;
            assertTrue(r.statusCode() == 200 || r.statusCode() == 409, "a publish answered: " + answers);
        }
        assertEquals(1, released, "released once: " + answers);
        Map<String, Object> issue = object(as("admin").get("/rest/publication-issues/editable-issue/" + publicId));
        assertEquals("PUBLISHED", issue.get("status"), "published");
        assertEquals(((Number) issue.get("memberCount")).longValue(), count("SELECT count(*) FROM public.\"IssueMember\""
                + " m JOIN public.\"PublicationIssue\" i ON i.id = m.issue_id WHERE i.\"publicId\" = :p", "p", publicId),
                "one frozen member row per member");
        assertEquals(1, PublicationSeriesRoundTripITTest.actionsInOrder(array(as("admin").get(
                "/rest/publication-issues/issue/" + publicId + "/audit"))).stream()
                .filter("PUBLISHED"::equals).count(), "one PUBLISHED entry in the trail");
    }
}
