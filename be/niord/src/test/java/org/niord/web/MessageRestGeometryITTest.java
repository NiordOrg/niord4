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

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.repo.RepoStore;
import org.niord.core.util.UidUtils;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the {@code @PermitAll} geometry-conversion endpoints of
 * {@link MessageRestService}: {@code parse-geometry}, {@code parse-utm}, {@code format-geometry} and
 * {@code format-message-geometry}.
 *
 * <p>These four endpoints carry no method-level {@code @RolesAllowed}; the resource class is
 * annotated {@code @PermitAll}, so they are reachable by an entirely anonymous caller. Every test
 * here therefore issues its request <em>without</em> a bearer token and still expects a 2xx/2xx-class
 * response, which both pins the public contract and proves the absence of an authorization gate.
 *
 * <p>The endpoints are pure, stateless converters — they read the request body, run it through
 * {@code PlainTextConverter}/{@code GeometryFormatService} and return the result. They neither read
 * nor write the database, so no committed test rows are created and no {@code @AfterEach} cleanup is
 * needed; the tests are inherently independent and order-free. The one exception is the section on
 * saving a message's geometry (CG8), whose cases save a message over REST with a token and delete
 * it in a {@code finally} block.
 *
 * <p>The conversions assert real outcomes:
 * <ul>
 *     <li>{@code parse-geometry} of a valid {@code 54° 45,7' N 10° 29,1' E} position returns a 200
 *         GeoJSON FeatureCollection whose first coordinate is the parsed {@code [lon, lat]}
 *         (10.485, 54.7617) — checked numerically, not just for presence;</li>
 *     <li>unparseable plain text returns {@code 204} (the converter swallows the parse error and
 *         returns {@code null}, which JAX-RS renders as an empty No-Content response);</li>
 *     <li>{@code parse-utm} of a valid {@code 32 V 624726 6207884} UTM line returns a 200 point in a
 *         plausible Danish lat/lon range, while garbage returns {@code 204};</li>
 *     <li>{@code format-geometry} of a GeoJSON point returns 200 plain text containing the formatted
 *         position, while an unparseable/unsupported geometry returns {@code 204};</li>
 *     <li>{@code format-message-geometry} renders a valid FeatureCollection as 200 {@code text/html}
 *         containing the formatted position (using an existing template), answers 404 for a template
 *         name that names no template, and refuses a template name that is not a plain name with a 400.</li>
 * </ul>
 *
 * <p>This is a framework-coupled test (it boots Quarkus to exercise the real JAX-RS pipeline and the
 * CDI-wired converter/template services) — see docs/framework-coupled-tests.md.
 */
class MessageRestGeometryITTest extends ITTest {

    @PersistenceContext
    EntityManager em;

    @Autowired
    RepoStore store;

    // The parse delta of 1e-3 deg ~ 0.06 arc-minute, finer than the converter's input precision but
    // coarse enough to survive double rounding through JSON.
    private static final double COORD_DELTA = 1e-3;

    /** A GeoJSON point feature-collection body at lon=10.5, lat=54.5, used by the formatting tests. */
    private static final String POINT_FEATURE_COLLECTION =
            "{\"type\":\"FeatureCollection\",\"features\":["
                    + "{\"type\":\"Feature\",\"properties\":{},"
                    + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[10.5,54.5]}}]}";

    /** Extracts a numeric body field as a double regardless of whether RestAssured boxed it as Float. */
    private static double asDouble(Response response, String path) {
        Number n = response.path(path);
        return n.doubleValue();
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /parse-geometry  (@PermitAll, plain text -> GeoJSON)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void parseGeometryValidPositionReturnsFeatureCollectionWithParsedCoordinate() {
        // 54° 45,7' N 10° 29,1' E  ->  lon = 10 + 29.1/60 = 10.485,  lat = 54 + 45.7/60 = 54.76166...
        Response response = given()
                .contentType(ContentType.JSON)
                .body("{\"geometryText\":\"54\\u00b0 45,7' N 10\\u00b0 29,1' E\"}")
                .when().post("/rest/messages/parse-geometry")
                .then().statusCode(200)
                .contentType(containsString("application/json"))
                .body("type", containsString("FeatureCollection"))
                .body("features[0].geometry.type", containsString("Point"))
                .extract().response();

        double lon = asDouble(response, "features[0].geometry.coordinates[0]");
        double lat = asDouble(response, "features[0].geometry.coordinates[1]");
        assertEquals(10.0 + 29.1 / 60.0, lon, COORD_DELTA, "parsed longitude (GeoJSON x)");
        assertEquals(54.0 + 45.7 / 60.0, lat, COORD_DELTA, "parsed latitude (GeoJSON y)");
    }

    @Test
    void parseGeometryReachableAnonymouslyWithoutToken() {
        // Pin the @PermitAll contract directly: no auth header at all, yet not a 401/403.
        given()
                .contentType(ContentType.JSON)
                .body("{\"geometryText\":\"54\\u00b0 45,7' N 10\\u00b0 29,1' E\"}")
                .when().post("/rest/messages/parse-geometry")
                .then().statusCode(200);
    }

    @Test
    void parseGeometryGarbageInputReturnsNoContent() {
        // An unparseable line makes PlainTextConverter throw; the resource catches it and returns
        // null, which JAX-RS serializes as an empty 204 No Content (NOT a 400/500).
        given()
                .contentType(ContentType.JSON)
                .body("{\"geometryText\":\"this is not a coordinate\"}")
                .when().post("/rest/messages/parse-geometry")
                .then().statusCode(204);
    }

    @Test
    void parseGeometryBlankInputReturnsNoContent() {
        // Blank text -> converter returns null up front -> 204.
        given()
                .contentType(ContentType.JSON)
                .body("{\"geometryText\":\"   \"}")
                .when().post("/rest/messages/parse-geometry")
                .then().statusCode(204);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /parse-utm  (@PermitAll, UTM text -> GeoJSON)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void parseUtmValidLineReturnsPointInPlausibleDanishRange() {
        // Zone 32V over Denmark: the converter yields a single Point; assert the lat/lon land in a
        // sane northern/eastern window rather than pinning the projection maths exactly.
        Response response = given()
                .contentType(ContentType.JSON)
                .body("{\"geometryText\":\"32 V 624726 6207884\"}")
                .when().post("/rest/messages/parse-utm")
                .then().statusCode(200)
                .contentType(containsString("application/json"))
                .body("features[0].geometry.type", containsString("Point"))
                .extract().response();

        double lon = asDouble(response, "features[0].geometry.coordinates[0]");
        double lat = asDouble(response, "features[0].geometry.coordinates[1]");
        assertTrue(lat >= 50.0 && lat <= 60.0, "latitude " + lat + " should be in a plausible range");
        assertTrue(lon >= 8.0 && lon <= 20.0, "longitude " + lon + " should be in a plausible range");
    }

    @Test
    void parseUtmReachableAnonymouslyWithoutToken() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"geometryText\":\"32 V 624726 6207884\"}")
                .when().post("/rest/messages/parse-utm")
                .then().statusCode(200);
    }

    @Test
    void parseUtmGarbageInputReturnsNoContent() {
        // Non-UTM text: utm2LatLon throws per line, every coordinate is filtered out, the converter
        // returns null -> 204.
        given()
                .contentType(ContentType.JSON)
                .body("{\"geometryText\":\"definitely not a utm coordinate\"}")
                .when().post("/rest/messages/parse-utm")
                .then().statusCode(204);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /format-geometry  (@PermitAll, GeoJSON -> plain text)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void formatGeometryPointReturnsPlainTextWithFormattedPosition() {
        // PlainTextConverter.toPlainText({lon=10.5, lat=54.5}) -> "Point\n54° 30.000'N - 010° 30.000'E".
        String body = given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"Point\",\"coordinates\":[10.5,54.5]}")
                .when().post("/rest/messages/format-geometry")
                .then().statusCode(200)
                .extract().asString();

        assertTrue(body.startsWith("Point"), "output should begin with the geometry type, was: " + body);
        // Load-bearing formatted-position fragments (degrees + minutes + hemisphere).
        assertTrue(body.contains("54° 30.000'N"),
                "latitude should be formatted to 3 decimal minutes, was: " + body);
        assertTrue(body.contains("010° 30.000'E"),
                "longitude should be formatted to 3 decimal minutes, was: " + body);
    }

    @Test
    void formatGeometryReachableAnonymouslyWithoutToken() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"Point\",\"coordinates\":[10.5,54.5]}")
                .when().post("/rest/messages/format-geometry")
                .then().statusCode(200);
    }

    @Test
    void formatGeometryUnsupportedTypeReturnsNoContent() {
        // toPlainText() throws for an unsupported geometry (GeometryCollection); the resource catches
        // it and returns null -> 204.
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"GeometryCollection\",\"geometries\":["
                        + "{\"type\":\"Point\",\"coordinates\":[10.5,54.5]}]}")
                .when().post("/rest/messages/format-geometry")
                .then().statusCode(204);
    }

    @Test
    void formatGeometryPointWithoutCoordinatesReturnsNoContent() {
        // A Point with no coordinates makes toPlainText() throw "Point has no associated
        // coordinates"; the catch-all returns null -> 204.
        given()
                .contentType(ContentType.JSON)
                .body("{\"type\":\"Point\"}")
                .when().post("/rest/messages/format-geometry")
                .then().statusCode(204);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /format-message-geometry  (@PermitAll, FeatureCollection -> HTML)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void formatMessageGeometryWithExistingTemplateReturnsHtmlWithPosition() {
        // The default template name "list" has no backing /templates/geometry/list.ftl (see the
        // _documentsBug test below), so this positive case uses an existing template: numbered-list.
        String body = given()
                .contentType(ContentType.JSON)
                .queryParam("template", "numbered-list")
                .body(POINT_FEATURE_COLLECTION)
                .when().post("/rest/messages/format-message-geometry")
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .extract().asString();

        // The numbered-list template renders an HTML <table class="positions"> with the formatted
        // position; assert the structural marker AND the formatted hemisphere/degrees.
        assertTrue(body.contains("positions"), "rendered HTML should contain the positions table, was: " + body);
        assertTrue(body.contains("°"), "rendered HTML should contain a formatted degree position, was: " + body);
        assertTrue(body.contains("N") && body.contains("E"),
                "rendered HTML should contain the N/E hemisphere markers, was: " + body);
    }

    @Test
    void formatMessageGeometryReachableAnonymouslyWithoutToken() {
        given()
                .contentType(ContentType.JSON)
                .queryParam("template", "numbered-list")
                .body(POINT_FEATURE_COLLECTION)
                .when().post("/rest/messages/format-message-geometry")
                .then().statusCode(200);
    }

    @Test
    void formatMessageGeometryDefaultTemplateRendersNumberedListButExplicitListStillErrors() {
        // GEOM1 (fixed): the default template was "list", which resolved to /templates/geometry/list.ftl —
        // a resource that ships nowhere (only bullet-list, numbered-list, table, from-to-table and text
        // exist), so the DEFAULT (no-template) request 5xx'd. The default is now "numbered-list" (the UI's
        // rename of the old "list"), so a no-template request renders HTML positions with a 200.
        String body = given()
                .contentType(ContentType.JSON)
                .body(POINT_FEATURE_COLLECTION)
                .when().post("/rest/messages/format-message-geometry")
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .extract().asString();
        assertTrue(body.contains("positions"), "default render should contain the positions table, was: " + body);
        assertTrue(body.contains("°"), "default render should contain a formatted degree position, was: " + body);
        assertTrue(body.contains("N") && body.contains("E"),
                "default render should contain the N/E hemisphere markers, was: " + body);

        // Only the default name changed — list.ftl was intentionally NOT added, so an explicit
        // ?template=list is still an unknown template and is refused with a 404 (WEB17; it was a 5xx).
        given()
                .contentType(ContentType.JSON)
                .queryParam("template", "list")
                .body(POINT_FEATURE_COLLECTION)
                .when().post("/rest/messages/format-message-geometry")
                .then().statusCode(404);
    }

    @Test
    void formatMessageGeometryUnknownTemplateIsNotFound() {
        // A template that exists neither in the DB nor on the classpath cannot be loaded; the resource
        // answers 404 naming it (WEB17; the catch-all used to turn it into a 500), never a silent empty body.
        given()
                .contentType(ContentType.JSON)
                .queryParam("template", "no-such-template-" + System.nanoTime())
                .body(POINT_FEATURE_COLLECTION)
                .when().post("/rest/messages/format-message-geometry")
                .then().statusCode(404);
    }

    @Test
    void formatMessageGeometryRefusesATemplateNameThatIsNotAPlainName() {
        // The name is formatted into /templates/geometry/<name>.ftl, so a separator or a parent segment
        // would reach other templates (FreeMarker normalises "../geometry/numbered-list" back to an existing
        // one, which rendered with a 200). Refused with a 400 before any template is looked up or stored.
        long scriptResources = Tx.requiringNew().call(() ->
                em.createQuery("select count(s) from ScriptResource s", Long.class).getSingleResult());

        for (String name : new String[] { "../geometry/numbered-list", "numbered-list/../text", "a/b",
                "..", "numbered-list.ftl", "..\\geometry\\text" }) {
            given()
                    .contentType(ContentType.JSON)
                    .queryParam("template", name)
                    .body(POINT_FEATURE_COLLECTION)
                    .when().post("/rest/messages/format-message-geometry")
                    .then().statusCode(400);
        }

        assertEquals(scriptResources, (long) Tx.requiringNew().call(() ->
                        em.createQuery("select count(s) from ScriptResource s", Long.class).getSingleResult()),
                "a refused template name must not create a ScriptResource row");
    }

    // ------------------------------------------------------------------------------------------- //
    // Saving a message: its geometry is stored on the globe (CG8)
    //
    // The geometry editor sends what the map gave it: a point drawn on a wrapped world copy, or read
    // from a dropped file, can lie beyond 180 degrees. The save moves the whole geometry by whole turns
    // of 360 degrees onto the globe, and refuses a latitude beyond 90 with a 400 and saves nothing.
    // These cases commit over REST, so each one deletes its message.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessageSavedWithAPointAt190East_comesBackAt170West() {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);
        String editRepoPath = newTempDir();
        try {
            Response created = given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType(ContentType.JSON)
                    .body(draftWithGeometry(uid, repoPath, editRepoPath, Arrays.asList(190.0, 56.0)))
                    .when().post("/rest/messages/message")
                    .then().statusCode(200)
                    .extract().response();
            assertEquals(-170.0, asDouble(created, "parts[0].geometry.features[0].geometry.coordinates[0]"), COORD_DELTA);
            assertEquals(56.0, asDouble(created, "parts[0].geometry.features[0].geometry.coordinates[1]"), COORD_DELTA);

            Response stored = given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .when().get("/rest/messages/message/" + uid)
                    .then().statusCode(200)
                    .extract().response();
            assertEquals(-170.0, asDouble(stored, "parts[0].geometry.features[0].geometry.coordinates[0]"), COORD_DELTA,
                    "the stored longitude must be on the globe");
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
            store.deleteFolder(editRepoPath);
        }
    }

    @Test
    void aMessageSavedWithAPointAt95North_isRefusedAndNotSaved() {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);
        String editRepoPath = newTempDir();
        try {
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType(ContentType.JSON)
                    .body(draftWithGeometry(uid, repoPath, editRepoPath, Arrays.asList(10.0, 95.0)))
                    .when().post("/rest/messages/message")
                    .then().statusCode(400)
                    .body("detail", containsString("95.0"));

            assertEquals(0L, countMessages(uid), "a refused message must not be saved");
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
            store.deleteFolder(editRepoPath);
        }
    }

    @Test
    void anUpdateWithAPointAt95North_isRefusedAndKeepsTheStoredGeometry() {
        String uid = UidUtils.newUid();
        String repoPath = UidUtils.uidToHashedFolderPath("messages", uid);
        String editRepoPath = newTempDir();
        String editRepoPath2 = newTempDir();
        try {
            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType(ContentType.JSON)
                    .body(draftWithGeometry(uid, repoPath, editRepoPath, Arrays.asList(10.5, 54.5)))
                    .when().post("/rest/messages/message")
                    .then().statusCode(200);

            given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType(ContentType.JSON)
                    .body(draftWithGeometry(uid, repoPath, editRepoPath2, Arrays.asList(10.5, 95.0)))
                    .when().put("/rest/messages/message/" + uid)
                    .then().statusCode(400);

            Response stored = given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .when().get("/rest/messages/message/" + uid)
                    .then().statusCode(200)
                    .extract().response();
            assertEquals(54.5, asDouble(stored, "parts[0].geometry.features[0].geometry.coordinates[1]"), COORD_DELTA,
                    "the refused update must leave the stored latitude");
        } finally {
            deleteMessage(uid);
            store.deleteFolder(repoPath);
            store.deleteFolder(editRepoPath);
            store.deleteFolder(editRepoPath2);
        }
    }

    /** A minimal NW draft with one DETAILS part whose geometry is one point at the given [lon, lat]. */
    private static Map<String, Object> draftWithGeometry(String uid, String repoPath, String editRepoPath,
                                                         List<Double> lonLat) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("id", uid);
        msg.put("repoPath", repoPath);
        msg.put("editRepoPath", editRepoPath);
        msg.put("revision", 0);
        msg.put("mainType", "NW");
        msg.put("type", "LOCAL_WARNING");
        msg.put("status", "DRAFT");
        msg.put("autoTitle", false);
        msg.put("messageSeries", new HashMap<>(Map.of("seriesId", "dma-nw", "mainType", "NW")));

        Map<String, Object> point = new HashMap<>();
        point.put("type", "Point");
        point.put("coordinates", lonLat);
        Map<String, Object> feature = new HashMap<>();
        feature.put("type", "Feature");
        feature.put("properties", new HashMap<>());
        feature.put("geometry", point);
        Map<String, Object> geometry = new HashMap<>();
        geometry.put("type", "FeatureCollection");
        geometry.put("features", new ArrayList<>(List.of(feature)));

        Map<String, Object> desc = new HashMap<>();
        desc.put("lang", "en");
        desc.put("subject", "Subject");
        desc.put("details", "Body");
        Map<String, Object> part = new HashMap<>();
        part.put("type", "DETAILS");
        part.put("descs", new ArrayList<>(List.of(desc)));
        part.put("geometry", geometry);
        msg.put("parts", new ArrayList<>(List.of(part)));

        Map<String, Object> titleDesc = new HashMap<>();
        titleDesc.put("lang", "en");
        titleDesc.put("title", "Geometry on the globe");
        msg.put("descs", new ArrayList<>(List.of(titleDesc)));
        return msg;
    }

    private String newTempDir() {
        return given().auth().oauth2(accessToken())
                .when().get("/rest/repo/new-temp-dir")
                .then().statusCode(200).extract().path("path");
    }

    private long countMessages(String uid) {
        return Tx.requiringNew().call(() ->
                em.createQuery("select count(m) from Message m where m.uid = :uid", Long.class)
                        .setParameter("uid", uid).getSingleResult());
    }

    private void deleteMessage(String uid) {
        Tx.requiringNew().run(() -> {
            em.createQuery("delete from MessageHistory mh where mh.message.uid = :uid")
                    .setParameter("uid", uid).executeUpdate();
            Object m = em.createQuery("select m from Message m where m.uid = :uid")
                    .setParameter("uid", uid).getResultStream().findFirst().orElse(null);
            if (m != null) {
                em.remove(m);
            }
        });
    }

    @Test
    void formatMessageGeometryMalformedBodyIsRejected() {
        // A syntactically broken JSON body is rejected by the pipeline before/within the resource:
        // RESTEasy's body reader yields a 4xx for unreadable JSON, while a body that reaches the
        // resource and fails to render yields a 5xx. Either way the request must NOT succeed (no 2xx)
        // and must NOT be silently accepted — assert a >=400 error status.
        given()
                .contentType(ContentType.JSON)
                .queryParam("template", "numbered-list")
                .body("{ this is not valid json ")
                .when().post("/rest/messages/format-message-geometry")
                .then().statusCode(greaterThanOrEqualTo(400));
    }
}
