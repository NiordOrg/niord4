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

import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.niord.test.ITTest;
import org.niord.test.Tx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the save-reload-save round-trip ITs ({@code *RoundTripITTest}) share: requests over REST with a real token
 * of a role, committed on the server; SQL read in a new transaction; and builders for the request bodies.
 *
 * <p>The round trip each test walks: create an aggregate with two or three of every child list and every optional
 * field set, read it back and compare, update it (add, remove, reorder, change a field of one), read it back, save
 * the read-back unchanged and count its rows in SQL before and after, then delete it and count what is left. A
 * request commits on the server, so each test removes what it made.
 *
 * <p>Not a test class itself (no {@code Test} suffix, abstract); its subclasses share the one Spring context of
 * {@link ITTest}. This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
abstract class RoundTripSupport extends ITTest {

    @PersistenceContext
    protected EntityManager em;

    /** A short unique suffix for names that must be unique in the shared database */
    protected static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------------------------------- //
    // REST
    // ------------------------------------------------------------------------------------------- //

    /** A request as the administration app sends it: the role's token and the NW domain */
    protected static RequestSpecification as(String role) {
        return given().auth().oauth2(accessToken(role))
                .header("NiordDomain", "niord-nw")
                .contentType("application/json");
    }

    protected static Map<String, Object> getMap(String role, String path) {
        return as(role).when().get(path).then().statusCode(200).extract().jsonPath().getMap("");
    }

    protected static List<Map<String, Object>> getList(String role, String path) {
        return as(role).when().get(path).then().statusCode(200).extract().jsonPath().getList("");
    }

    protected static ValidatableResponse postRaw(String role, String path, Object body) {
        return as(role).body(body).when().post(path).then();
    }

    protected static ValidatableResponse putRaw(String role, String path, Object body) {
        return as(role).body(body).when().put(path).then();
    }

    protected static Map<String, Object> post(String role, String path, Object body) {
        return postRaw(role, path, body).statusCode(200).extract().jsonPath().getMap("");
    }

    protected static Map<String, Object> put(String role, String path, Object body) {
        return putRaw(role, path, body).statusCode(200).extract().jsonPath().getMap("");
    }

    protected static ValidatableResponse delete(String role, String path) {
        return as(role).when().delete(path).then();
    }

    // ------------------------------------------------------------------------------------------- //
    // SQL, each read in a new transaction
    // ------------------------------------------------------------------------------------------- //

    /** The first column of a native query's rows; the parameters are positional, ?1, ?2, ... */
    protected List<Object> column(String sql, Object... params) {
        return Tx.requiringNew().call(() -> {
            Query query = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
            List<Object> values = new ArrayList<>();
            for (Object row : query.getResultList()) {
                values.add(row instanceof Object[] cells ? cells[0] : row);
            }
            return values;
        });
    }

    /** The rows of a native query, each as its cells */
    protected List<List<Object>> rows(String sql, Object... params) {
        return Tx.requiringNew().call(() -> {
            Query query = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
            List<List<Object>> rows = new ArrayList<>();
            for (Object row : query.getResultList()) {
                rows.add(row instanceof Object[] cells ? List.of(cells) : List.of(row));
            }
            return rows;
        });
    }

    /** A {@code count(*)} query's answer */
    protected long count(String sql, Object... params) {
        return ((Number) column(sql, params).getFirst()).longValue();
    }

    /** The integers of the first column, as int */
    protected List<Integer> ints(String sql, Object... params) {
        return column(sql, params).stream().map(v -> ((Number) v).intValue()).toList();
    }

    /** The strings of the first column */
    protected List<String> strings(String sql, Object... params) {
        return column(sql, params).stream().map(v -> v == null ? null : v.toString()).toList();
    }

    /** Runs native statements in one new, committed transaction */
    protected void execute(String sql, Object... params) {
        Tx.requiringNew().run(() -> {
            Query query = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
            query.executeUpdate();
        });
    }

    /** The answers of named counts, in order: the rows an aggregate holds, read before and after a save */
    protected Map<String, Long> counts(Map<String, String> namedCounts, Object... params) {
        Map<String, Long> result = new LinkedHashMap<>();
        namedCounts.forEach((name, sql) -> result.put(name, count(sql, params)));
        return result;
    }

    /** Asserts that an order column holds 0 to n-1, one row per element */
    protected void assertOrderColumn(int expectedSize, List<Integer> stored, String what) {
        assertEquals(IntStream.range(0, expectedSize).boxed().toList(), stored.stream().sorted().toList(),
                what + ": the order column holds 0 to n-1, one row per element");
    }

    // ------------------------------------------------------------------------------------------- //
    // Bodies
    // ------------------------------------------------------------------------------------------- //

    /** A mutable map of the keys and values given in turn */
    protected static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    @SafeVarargs
    protected static <T> List<T> list(T... elements) {
        return new ArrayList<>(List.of(elements));
    }

    /** The list of maps under the key, empty when the body leaves it out */
    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> listOf(Map<String, Object> map, String key) {
        Object list = map.get(key);
        return list == null ? new ArrayList<>() : (List<Map<String, Object>>) list;
    }

    /** The list of plain values under the key, empty when the body leaves it out */
    @SuppressWarnings("unchecked")
    protected static <T> List<T> valuesOf(Map<String, Object> map, String key) {
        Object list = map.get(key);
        return list == null ? new ArrayList<>() : new ArrayList<>((List<T>) list);
    }

    /** One field of each element of the list under the key */
    protected static List<Object> fieldOf(Map<String, Object> map, String key, String field) {
        return listOf(map, key).stream().map(e -> e.get(field)).toList();
    }

    /** The descriptions under the key as language to the field's value */
    protected static Map<String, Object> descs(Map<String, Object> map, String field) {
        Map<String, Object> result = new LinkedHashMap<>();
        listOf(map, "descs").forEach(d -> result.put((String) d.get("lang"), d.get(field)));
        return result;
    }
}
