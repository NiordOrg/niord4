/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.web.aton;

import org.springframework.transaction.annotation.Transactional;
import org.niord.test.Tx;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.aton.AtonNode;
import org.niord.core.aton.AtonTag;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.aton.vo.AtonTagVo;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for {@link AtonRestService}: the HTTP contract of the {@code /rest/atons}
 * endpoints. The resource class is annotated {@code @PermitAll}, so every endpoint is reachable
 * without an authentication token — all tests therefore issue token-less requests.
 *
 * <p>Two families of behaviour are exercised:
 *
 * <ul>
 *   <li><b>AtoN lookup / search</b> ({@code /aton/{uids}}, {@code /search-name}, {@code /search}).
 *       The positive cases rely on COMMITTED {@link AtonNode} rows: HTTP requests run in the
 *       server's own transaction which {@code @Transactional} cannot roll back, so rows are
 *       created via {@link Tx#requiringNew()} (committing) and removed in
 *       {@link #cleanup()} — mirroring {@code MessageLuceneIndexITTest}. Every created AtoN carries
 *       a globally-unique {@code seamark:ref} (the AtoN UID) and {@code seamark:name} so a row can
 *       only ever surface because of the data this test created, keeping the tests independent and
 *       repeatable against the shared seed database.</li>
 *   <li><b>AtoN-defaults</b> ({@code /defaults/node-types}, {@code /defaults/merge-with-node-types},
 *       {@code /defaults/auto-complete-key}, {@code /defaults/auto-complete-value}). These are pure,
 *       in-memory computations over the OSM INT-1 presets parsed by {@code AtonDefaultsService}.
 *       Because that service loads its presets asynchronously ~3s after startup (a {@code Timer}),
 *       the defaults tests first {@link #awaitNodeTypesLoaded() await} a non-empty node-type list to
 *       avoid start-up flakiness before asserting.</li>
 * </ul>
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class AtonRestServiceITTest extends ITTest {

    /** The AtoN UID tag key ({@code seamark:ref}). */
    private static final String UID_KEY = AtonTag.TAG_ATON_UID; // "seamark:ref"
    private static final String NAME_KEY = "seamark:name";
    private static final String TYPE_KEY = "seamark:type";

    /** Generates unique OSM uids so created rows never collide with the seed data or each other. */
    private static final AtomicInteger UID_SEQ = new AtomicInteger(950000);

    @PersistenceContext
    EntityManager em;

    private final List<Integer> createdAtonIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Integer id : createdAtonIds) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from AtonTag t where t.atonNode.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                AtonNode node = em.find(AtonNode.class, id);
                if (node != null) {
                    em.remove(node);
                }
            });
        }
        createdAtonIds.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** A globally-unique, purely-alphabetic token (no characters the {@code like}-search normalizes away). */
    private String uniqueToken() {
        return "zzaton" + System.nanoTime();
    }

    private AtonTagVo tag(String k, String v) {
        return new AtonTagVo(k, v);
    }

    /**
     * Creates a COMMITTED {@link AtonNode} carrying the supplied tags at a Danish position, and
     * tracks its id for removal in {@link #cleanup()}.
     */
    private int createCommittedAton(double lat, double lon, AtonTagVo... tags) {
        AtonNodeVo vo = new AtonNodeVo();
        vo.setLat(lat);
        vo.setLon(lon);
        vo.setVisible(true);
        vo.setUid(UID_SEQ.incrementAndGet());
        vo.setVersion(1);
        vo.setUser("itTest");
        vo.setTags(tags);

        Integer id = Tx.requiringNew().call(() -> {
            AtonNode node = new AtonNode(vo);
            em.persist(node);
            em.flush();
            return node.getId();
        });
        assertNotNull(id, "the committed AtoN must have a generated id");
        createdAtonIds.add(id);
        return id;
    }

    /**
     * Polls {@code GET /rest/atons/defaults/node-types} until it returns a non-empty list (the
     * presets are parsed ~3s after start-up by an internal {@code Timer}) and returns the list.
     *
     * <p>Bounded by a wall-clock deadline (the {@code BatchJobs}/{@code MailHog} polling idiom) rather
     * than a fixed iteration count, so the upper bound is a true 30s regardless of per-request latency.
     *
     * <p>During the pre-load window the endpoint returns HTTP 200 with an <em>empty</em> list ({@code
     * osmDefaults} is still null; the WEB3 fix null-guards {@code getNodeTypeNames} to degrade gracefully
     * instead of throwing an NPE/500), so the poll treats any non-200 or empty response as "not ready yet"
     * and keeps polling until the deadline, returning only once a 200 carrying a non-empty list is observed.
     */
    private List<String> awaitNodeTypesLoaded() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            Response resp = given()
                    .when().get("/rest/atons/defaults/node-types")
                    .then().extract().response();
            if (resp.statusCode() == 200) {
                List<String> names = resp.jsonPath().getList("$", String.class);
                if (names != null && !names.isEmpty()) {
                    return names;
                }
            }
            Thread.sleep(500);
        }
        fail("AtoN node-type defaults were never loaded within 30s");
        return List.of(); // unreachable
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /rest/atons/aton/{atonUids}
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAtonsWithUnknownUidReturnsEmptyArray() {
        // No AtoN carries this seamark:ref, so the lookup yields an empty JSON array (200, not 404).
        given()
                .when().get("/rest/atons/aton/NO-SUCH-UID-" + System.nanoTime())
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", org.hamcrest.Matchers.is(0));
    }

    @Test
    void getAtonsByUidReturnsExactlyTheCommittedNode() {
        String ref = uniqueToken();
        int id = createCommittedAton(55.0, 11.0,
                tag(UID_KEY, ref),
                tag(NAME_KEY, "Buoy " + ref),
                tag(TYPE_KEY, "buoy_cardinal"));

        given()
                .when().get("/rest/atons/aton/" + ref)
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", org.hamcrest.Matchers.is(1))
                .body("[0].id", org.hamcrest.Matchers.is(id))
                .body("[0].lat", org.hamcrest.Matchers.is(55.0f))
                .body("[0].lon", org.hamcrest.Matchers.is(11.0f))
                // The tags are serialized as a JSON object/map keyed by the tag key.
                .body("[0].tags.'" + UID_KEY + "'", org.hamcrest.Matchers.is(ref))
                .body("[0].tags.'" + TYPE_KEY + "'", org.hamcrest.Matchers.is("buoy_cardinal"));
    }

    @Test
    void getAtonsByCommaSeparatedUidsReturnsBothNodes() {
        String refA = uniqueToken();
        String refB = uniqueToken();
        int idA = createCommittedAton(55.0, 11.0, tag(UID_KEY, refA), tag(NAME_KEY, "A " + refA));
        int idB = createCommittedAton(56.0, 12.0, tag(UID_KEY, refB), tag(NAME_KEY, "B " + refB));

        List<Integer> ids = given()
                .when().get("/rest/atons/aton/" + refA + "," + refB)
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", org.hamcrest.Matchers.is(2))
                .extract().jsonPath().getList("id", Integer.class);

        assertTrue(ids.contains(idA), "the comma-separated lookup must include the first AtoN");
        assertTrue(ids.contains(idB), "the comma-separated lookup must include the second AtoN");
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /rest/atons/search-name
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchNameWithNoMatchReturnsEmptyArray() {
        given()
                .queryParam("name", "ZZZ-UNIQUE-" + System.nanoTime())
                .queryParam("maxAtonNo", 1000)
                .when().get("/rest/atons/search-name")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", org.hamcrest.Matchers.is(0));
    }

    @Test
    void searchNameMatchesTheCommittedNodeByItsUniqueNameToken() {
        // search-name performs a LIKE over tag values, so the unique token embedded in the
        // seamark:name value uniquely identifies the row this test created.
        String nameToken = uniqueToken();
        int id = createCommittedAton(55.5, 10.5,
                tag(UID_KEY, "REF-" + nameToken),
                tag(NAME_KEY, "Lighthouse " + nameToken),
                tag(TYPE_KEY, "light_major"));

        List<Integer> ids = given()
                .queryParam("name", nameToken)
                .queryParam("maxAtonNo", 1000)
                .when().get("/rest/atons/search-name")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .extract().jsonPath().getList("id", Integer.class);

        assertTrue(ids.contains(id),
                "search-name must return the AtoN whose tag value contains the searched token");
    }

    @Test
    void searchNameDoesNotMatchAnUnrelatedNameToken() {
        // The AtoN's name carries 'present'; searching for an unrelated 'absent' token must miss it.
        String present = uniqueToken();
        String absent = uniqueToken();
        int id = createCommittedAton(55.5, 10.5,
                tag(UID_KEY, "REF-" + present),
                tag(NAME_KEY, "Beacon " + present));

        List<Integer> ids = given()
                .queryParam("name", absent)
                .queryParam("maxAtonNo", 1000)
                .when().get("/rest/atons/search-name")
                .then().statusCode(200)
                .extract().jsonPath().getList("id", Integer.class);

        assertFalse(ids.contains(id),
                "search-name must not return an AtoN whose tag values do not contain the searched token");
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /rest/atons/search  (PagedSearchResultVo)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchByExtentFarFromEverythingReturnsTotalZero() {
        // A small South-Pacific extent contains none of the Danish seed/test AtoNs.
        given()
                .queryParam("minLat", -40)
                .queryParam("minLon", -120)
                .queryParam("maxLat", -39)
                .queryParam("maxLon", -119)
                .queryParam("maxAtonNo", 1000)
                .when().get("/rest/atons/search")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("total", org.hamcrest.Matchers.is(0))
                .body("data.size()", org.hamcrest.Matchers.is(0));
    }

    @Test
    void searchByExtentReturnsTheCommittedNodeInsideTheBox() {
        String ref = uniqueToken();
        int id = createCommittedAton(55.0, 11.0,
                tag(UID_KEY, ref),
                tag(NAME_KEY, "Inside " + ref));

        // Box (54,7)-(57,13) contains (55,11); restrict the assertion to the id we created since the
        // search spans the whole table (the shared DB may hold other AtoNs inside Denmark).
        Response resp = given()
                .queryParam("minLat", 54)
                .queryParam("minLon", 7)
                .queryParam("maxLat", 57)
                .queryParam("maxLon", 13)
                .queryParam("maxAtonNo", 100000)
                .when().get("/rest/atons/search")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .extract().response();

        long total = resp.jsonPath().getLong("total");
        List<Integer> dataIds = resp.jsonPath().getList("data.id", Integer.class);

        assertTrue(total >= 1, "the box containing (55,11) must report at least the created AtoN (total=" + total + ")");
        assertTrue(dataIds.contains(id), "the in-box search must return the AtoN created at (55,11)");
    }

    @Test
    void searchByExtentExcludesTheCommittedNodeOutsideTheBox() {
        String ref = uniqueToken();
        int id = createCommittedAton(80.0, 80.0,
                tag(UID_KEY, ref),
                tag(NAME_KEY, "FarNE " + ref));

        // The same Denmark box does NOT contain (80,80).
        List<Integer> dataIds = given()
                .queryParam("minLat", 54)
                .queryParam("minLon", 7)
                .queryParam("maxLat", 57)
                .queryParam("maxLon", 13)
                .queryParam("maxAtonNo", 100000)
                .when().get("/rest/atons/search")
                .then().statusCode(200)
                .extract().jsonPath().getList("data.id", Integer.class);

        assertFalse(dataIds.contains(id),
                "an AtoN at (80,80) must be excluded from a search bounded to the Denmark box");
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /rest/atons/defaults/node-types
    // ------------------------------------------------------------------------------------------- //

    @Test
    void nodeTypesReturnsNonEmptySortedList() throws Exception {
        List<String> names = awaitNodeTypesLoaded();

        assertFalse(names.isEmpty(), "the parsed INT-1 presets must yield at least one node type");

        // getNodeTypeNames() returns the names .distinct().sorted(); verify the contract.
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        assertEquals(sorted, names, "node-type names must be returned in natural (sorted) order");
        assertEquals(names.size(), names.stream().distinct().count(),
                "node-type names must be distinct");
    }

    @Test
    void nodeTypesFilteredByNameContainsOnlyMatchingNamesCaseInsensitively() throws Exception {
        awaitNodeTypesLoaded();

        List<String> names = given()
                .queryParam("name", "buoy")
                .when().get("/rest/atons/defaults/node-types")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .extract().jsonPath().getList("$", String.class);

        assertFalse(names.isEmpty(), "there must be at least one 'buoy' node type (e.g. Cardinal Buoy)");
        for (String name : names) {
            assertTrue(name.toLowerCase().contains("buoy"),
                    "the ?name=buoy filter is a case-insensitive substring match, but got: " + name);
        }
    }

    @Test
    void nodeTypesFilteredByNonsenseNameReturnsEmptyArray() throws Exception {
        awaitNodeTypesLoaded();

        given()
                .queryParam("name", "zzzqqq-no-such-node-type")
                .when().get("/rest/atons/defaults/node-types")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", org.hamcrest.Matchers.is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /rest/atons/defaults/merge-with-node-types
    // ------------------------------------------------------------------------------------------- //

    @Test
    void mergeWithNodeTypesAddsMissingTagsAndPreservesExistingOnes() throws Exception {
        awaitNodeTypesLoaded();

        // Input AtoN already carries seamark:type=buoy_cardinal (one tag). Merging the
        // "North Cardinal Buoy" node type must add the node type's other tags (where absent) while
        // leaving the already-present seamark:type untouched.
        Map<String, Object> aton = Map.of(
                "lat", 55.0,
                "lon", 11.0,
                "visible", true,
                "tags", Map.of(TYPE_KEY, "buoy_cardinal"));
        Map<String, Object> body = Map.of(
                "aton", aton,
                "nodeTypeNames", List.of("North Cardinal Buoy"));

        Response resp = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/rest/atons/defaults/merge-with-node-types")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                // seamark:type must be preserved with its original value (key already present -> not overwritten).
                .body("tags.'" + TYPE_KEY + "'", org.hamcrest.Matchers.is("buoy_cardinal"))
                .extract().response();

        Map<String, Object> tags = resp.jsonPath().getMap("tags");
        assertTrue(tags.size() > 1,
                "merging a node type must add its extra tags, so the result must have more than the single input tag (was "
                        + tags.size() + ")");
        // The "North Cardinal Buoy" preset pins seamark:buoy_cardinal:category=north.
        assertTrue(tags.containsKey("seamark:buoy_cardinal:category"),
                "the North Cardinal Buoy node type must contribute its category tag");
    }

    @Test
    void mergeWithUnknownNodeTypeLeavesTheAtonUnchanged() throws Exception {
        awaitNodeTypesLoaded();

        Map<String, Object> aton = Map.of(
                "lat", 55.0,
                "lon", 11.0,
                "visible", true,
                "tags", Map.of(TYPE_KEY, "buoy_cardinal"));
        Map<String, Object> body = Map.of(
                "aton", aton,
                "nodeTypeNames", List.of("No Such Node Type " + System.nanoTime()));

        Response resp = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/rest/atons/defaults/merge-with-node-types")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("tags.'" + TYPE_KEY + "'", org.hamcrest.Matchers.is("buoy_cardinal"))
                .extract().response();

        Map<String, Object> tags = resp.jsonPath().getMap("tags");
        assertEquals(1, tags.size(),
                "merging an unknown node type must be a no-op and leave the single original tag");
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /rest/atons/defaults/auto-complete-key
    // ------------------------------------------------------------------------------------------- //

    @Test
    void autoCompleteKeyReturnsKeysContainingTheTypedFragment() throws Exception {
        awaitNodeTypesLoaded();

        Map<String, Object> aton = Map.of(
                "lat", 55.0,
                "lon", 11.0,
                "visible", true,
                "tags", Map.of(TYPE_KEY, "buoy_cardinal"));

        List<String> keys = given()
                .contentType(ContentType.JSON)
                .queryParam("key", "seamark")
                .body(aton)
                .when().post("/rest/atons/defaults/auto-complete-key")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .extract().jsonPath().getList("$", String.class);

        assertFalse(keys.isEmpty(),
                "auto-complete must propose keys for a 'seamark' fragment over the cardinal-buoy presets");
        for (String k : keys) {
            assertTrue(k.toLowerCase().contains("seamark"),
                    "every proposed key must contain the typed 'seamark' fragment, but got: " + k);
        }
    }

    @Test
    void autoCompleteKeyWithBlankKeyReturnsEmptyArray() throws Exception {
        awaitNodeTypesLoaded();

        Map<String, Object> aton = Map.of(
                "lat", 55.0,
                "lon", 11.0,
                "visible", true,
                "tags", Map.of(TYPE_KEY, "buoy_cardinal"));

        // computeKeysForAton short-circuits to an empty list for a blank key.
        given()
                .contentType(ContentType.JSON)
                .queryParam("key", "")
                .body(aton)
                .when().post("/rest/atons/defaults/auto-complete-key")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", org.hamcrest.Matchers.is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /rest/atons/defaults/auto-complete-value
    // ------------------------------------------------------------------------------------------- //

    @Test
    void autoCompleteValueReturnsMatchingValuesForKeyAndFragment() throws Exception {
        awaitNodeTypesLoaded();

        Map<String, Object> aton = Map.of(
                "lat", 55.0,
                "lon", 11.0,
                "visible", true,
                "tags", Map.of(TYPE_KEY, "buoy_cardinal"));

        // seamark:type explicitly defines its value as "buoy_cardinal" in the presets, so a "buoy"
        // fragment must surface it (substring, case-insensitive).
        List<String> values = given()
                .contentType(ContentType.JSON)
                .queryParam("key", TYPE_KEY)
                .queryParam("value", "buoy")
                .body(aton)
                .when().post("/rest/atons/defaults/auto-complete-value")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .extract().jsonPath().getList("$", String.class);

        assertFalse(values.isEmpty(),
                "auto-complete-value must propose at least one seamark:type value matching 'buoy'");
        for (String v : values) {
            assertTrue(v.toLowerCase().contains("buoy"),
                    "every proposed value must contain the typed 'buoy' fragment, but got: " + v);
        }
        assertTrue(values.contains("buoy_cardinal"),
                "the cardinal-buoy preset's seamark:type value must be among the suggestions");
    }

    @Test
    void autoCompleteValueWithBlankKeyReturnsEmptyArray() throws Exception {
        awaitNodeTypesLoaded();

        Map<String, Object> aton = Map.of(
                "lat", 55.0,
                "lon", 11.0,
                "visible", true,
                "tags", Map.of(TYPE_KEY, "buoy_cardinal"));

        // getValuesForAtonAndKey short-circuits to an empty list for a blank key.
        given()
                .contentType(ContentType.JSON)
                .queryParam("key", "")
                .queryParam("value", "buoy")
                .body(aton)
                .when().post("/rest/atons/defaults/auto-complete-value")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", org.hamcrest.Matchers.is(0));
    }
}
