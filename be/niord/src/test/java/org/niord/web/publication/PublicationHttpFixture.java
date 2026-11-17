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
import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.niord.core.publication.TestIds;
import org.niord.core.publication.series.SeededSeries;
import org.niord.core.publication.series.StubIssueRenderService;
import org.niord.test.ITTest;
import org.niord.test.Tx;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the publication round-trip tests share: requests as each role over real HTTP, native reads of the tables the
 * API does not show, and the removal of everything a test wrote through the API.
 *
 * <p>Every write here commits on the server, so each test records the series and categories it created
 * ({@link #series}, {@link #categories}) and they are removed in {@link #removeWhatTheTestWrote()} in a fresh
 * transaction: the editions' links to what they supersede and the compilations' source links first, then
 * {@link SeededSeries#delete}, then the categories. This is a framework-coupled test, see
 * docs/framework-coupled-tests.md.
 *
 * <p>The roles are the realm's tiers ({@code ITTest#accessToken(String)}): {@code admin} is an administrator through
 * the base client, so it is an administrator in whichever domain the {@code NiordDomain} header names. A write
 * guarded on the owning domain is therefore refused by sending the same token with another domain in the header,
 * which is how a desk in another domain reaches it.
 */
abstract class PublicationHttpFixture extends ITTest {

    /** The domain every fixture publication belongs to. */
    static final String DOMAIN = "niord-nw";

    /** Another domain, whose administrators may not write the fixture's publications. */
    static final String OTHER_DOMAIN = "niord-nm";

    @PersistenceContext
    EntityManager em;

    /** The series the test created, by seriesId, removed afterwards. */
    final List<String> series = new ArrayList<>();

    /** The publication categories the test created, by categoryId, removed afterwards. */
    final List<String> categories = new ArrayList<>();

    /** Other rows to remove afterwards, as native statements run after the series and before the categories. */
    final List<String> cleanupSql = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestWrote() {
        StubIssueRenderService.reset();
        Tx.requiringNew().run(() -> {
            List<Integer> ids = series.isEmpty() ? List.of() : em.createQuery(
                            "SELECT s.id FROM PublicationSeries s WHERE s.seriesId IN :ids", Integer.class)
                    .setParameter("ids", series).getResultList();
            if (!ids.isEmpty()) {
                em.createNativeQuery("UPDATE public.\"PublicationIssue\" SET supersedes_id = NULL"
                                + " WHERE series_id IN (:ids)")
                        .setParameter("ids", ids).executeUpdate();
                em.createNativeQuery("UPDATE public.\"PublicationSeries\" SET \"sourceSeries_id\" = NULL"
                                + " WHERE id IN (:ids)")
                        .setParameter("ids", ids).executeUpdate();
                SeededSeries.delete(em, ids);
            }
            for (String sql : cleanupSql) {
                em.createNativeQuery(sql).executeUpdate();
            }
            for (String categoryId : categories) {
                em.createNativeQuery("DELETE FROM public.\"PublicationCategoryDesc\" WHERE entity_id IN"
                                + " (SELECT id FROM public.\"PublicationCategory\" WHERE \"categoryId\" = :id)")
                        .setParameter("id", categoryId).executeUpdate();
                em.createNativeQuery("DELETE FROM public.\"PublicationCategory\" WHERE \"categoryId\" = :id")
                        .setParameter("id", categoryId).executeUpdate();
            }
        });
        series.clear();
        categories.clear();
        cleanupSql.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Requests
    // ------------------------------------------------------------------------------------------- //

    /** A JSON request as the role, sitting at {@link #DOMAIN}. */
    static RequestSpecification as(String role) {
        return as(role, DOMAIN);
    }

    /** A JSON request as the role, sitting at the domain. */
    static RequestSpecification as(String role, String domain) {
        return given().auth().oauth2(accessToken(role))
                .header("NiordDomain", domain)
                .contentType("application/json");
    }

    /** A JSON request with no credentials at all. */
    static RequestSpecification anonymous() {
        return given().contentType("application/json");
    }

    /** The JSON object of a 200 answer. */
    static Map<String, Object> object(Response response) {
        response.then().statusCode(200);
        return response.jsonPath().getMap("");
    }

    /** The JSON array of a 200 answer. */
    static List<Map<String, Object>> array(Response response) {
        response.then().statusCode(200);
        return response.jsonPath().getList("");
    }

    /**
     * A refusal: a 401 or a 403, as given, and never a 500 or a success. The status alone says the call was refused
     * before it changed anything; the callers check the state after it.
     */
    static void refused(Response response, int status, String what) {
        int actual = response.statusCode();
        assertEquals(status, actual, what + " answered " + actual + ": " + response.asString());
    }

    /** A catalogued refusal: the status and the code the body carries. */
    static void refusedWith(Response response, int status, String code, String what) {
        assertEquals(status, response.statusCode(), what + " answered " + response.statusCode() + ": "
                + response.asString());
        assertEquals(code, response.jsonPath().getString("code"), what + ": " + response.asString());
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixture rows created through the API
    // ------------------------------------------------------------------------------------------- //

    /** A publishing category with a name in both languages, created over REST and removed afterwards. */
    String newCategory() {
        String categoryId = TestIds.category();
        categories.add(categoryId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("priority", 100);
        body.put("publish", true);
        body.put("descs", List.of(
                Map.of("lang", "da", "name", "IT-kategori", "description", "da"),
                Map.of("lang", "en", "name", "IT category", "description", "en")));
        as("admin").body(body).post("/rest/publication-categories/publication-category").then().statusCode(200);
        return categoryId;
    }

    /** Records a seriesId for removal; returns it. */
    String track(String seriesId) {
        series.add(seriesId);
        return seriesId;
    }

    // ------------------------------------------------------------------------------------------- //
    // Native reads
    // ------------------------------------------------------------------------------------------- //

    /** One number from a native query, with named parameters given as name, value pairs. */
    long count(String sql, Object... params) {
        return Tx.requiringNew().call(() -> ((Number) query(sql, params).getSingleResult()).longValue());
    }

    /** The rows of a native query. */
    List<Object[]> rows(String sql, Object... params) {
        return Tx.requiringNew().call(() -> {
            List<Object[]> out = new ArrayList<>();
            for (Object row : query(sql, params).getResultList()) {
                out.add(row instanceof Object[] array ? array : new Object[] { row });
            }
            return out;
        });
    }

    private Query query(String sql, Object... params) {
        Query query = em.createNativeQuery(sql);
        for (int i = 0; i + 1 < params.length; i += 2) {
            query.setParameter((String) params[i], params[i + 1]);
        }
        return query;
    }

    /** Runs native statements in a committed transaction of their own. */
    void execute(String sql, Object... params) {
        Tx.requiringNew().run(() -> query(sql, params).executeUpdate());
    }

    // ------------------------------------------------------------------------------------------- //
    // JSON helpers
    // ------------------------------------------------------------------------------------------- //

    /** The list under the key, empty when the body leaves it out. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOf(Map<String, Object> map, String key) {
        Object list = map.get(key);
        return list == null ? new ArrayList<>() : (List<Map<String, Object>>) list;
    }

    /** The map under the key. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mapOf(Map<String, Object> map, String key) {
        return (Map<String, Object>) map.get(key);
    }

    /** The desc rows of a body, by language. */
    static Map<String, Map<String, Object>> descsByLang(Map<String, Object> body) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> d : listOf(body, "descs")) {
            assertTrue(out.put((String) d.get("lang"), d) == null, "one desc per language: " + d.get("lang"));
        }
        return out;
    }

    /**
     * A series as read, with its sharing list sorted, for a comparison of two reads. The list is a many-to-many with
     * no order column, so two reads of one unchanged series can give its ids in different orders (PUBSERIES3 in
     * be/docs/potential-errors.md). The ids themselves and their number are kept, and every other field is as read.
     */
    static Map<String, Object> sharingSorted(Map<String, Object> series) {
        Map<String, Object> out = new LinkedHashMap<>(series);
        if (out.get("availableDomainIds") instanceof List<?> ids) {
            out.put("availableDomainIds", ids.stream().map(String::valueOf).sorted().toList());
        }
        return out;
    }

    /** A date as JSON carries it (epoch milliseconds or an ISO-8601 instant), or null. */
    static Long millis(Object date) {
        if (date == null) {
            return null;
        }
        return date instanceof Number n ? n.longValue() : Instant.parse((String) date).toEpochMilli();
    }

    /** An integer field as JSON carries it. */
    static int intOf(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).intValue();
    }

    /** Asserts the two values differ, naming them. */
    static void assertMoved(Object before, Object after, String what) {
        assertNotEquals(before, after, what);
    }
}
