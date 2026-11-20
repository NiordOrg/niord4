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

import org.springframework.transaction.annotation.Transactional;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link AreaRestService}: the HTTP contract of the area endpoints, including
 * authorization. Reads are {@code @PermitAll}; writes require {@code admin}/{@code editor} roles,
 * exercised with a real Keycloak token (see {@link KeycloakTestTokens}).
 *
 * <p>Reads and negative-security cases need no token. Positive role-based cases create data that is
 * committed (HTTP requests run in the server's own transaction, which {@code @Transactional} cannot
 * roll back), so created areas are tracked and deleted in {@link #cleanup()} using unique {@code
 * IT-REST-} names that never occur in the seed data.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class AreaRestServiceITTest extends ITTest {

    private final List<Integer> createdAreaIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Integer id : createdAreaIds) {
            try {
                given().auth().oauth2(accessToken()).delete("/rest/areas/area/" + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdAreaIds.clear();
    }

    private int createAreaAsAdmin(String name) {
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("descs", List.of(Map.of("lang", "en", "name", name))))
                .when().post("/rest/areas/area/")
                .then().statusCode(200)
                .extract().path("id");
        createdAreaIds.add(id);
        return id;
    }

    private int createAreaAsAdmin(String name, int parentId) {
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "descs", List.of(Map.of("lang", "en", "name", name)),
                        "parent", Map.of("id", parentId)))
                .when().post("/rest/areas/area/")
                .then().statusCode(200)
                .extract().path("id");
        createdAreaIds.add(id);
        return id;
    }

    /** Persists an explicit sibling sort order via the area update endpoint (hierarchy is unchanged). */
    private void setSiblingSortOrderAsAdmin(int id, double sortOrder) {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", id,
                        "siblingSortOrder", sortOrder,
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-Sort-" + id))))
                .when().put("/rest/areas/area/" + id)
                .then().statusCode(200);
    }

    /** Reads back the persisted sibling sort order of an area. */
    private double siblingSortOrderAsAdmin(int id) {
        Number sortOrder = given().auth().oauth2(accessToken())
                .when().get("/rest/areas/area/" + id)
                .then().statusCode(200)
                .extract().path("siblingSortOrder");
        return sortOrder.doubleValue();
    }

    // ------------------------------------------------------------------------------------------- //
    // Unauthenticated reads (@PermitAll)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void areaRootsReturnsNonEmptyTreeWithDescriptions() {
        given()
                .queryParam("lang", "en")
                .when().get("/rest/areas/area-roots")
                .then().statusCode(200)
                .body("size()", greaterThan(0))
                .body("[0].descs", notNullValue());
    }

    @Test
    void getAllReturnsNonEmptyTree() {
        given()
                .when().get("/rest/areas/all")
                .then().statusCode(200)
                .body("size()", greaterThan(0));
    }

    @Test
    void getAreaByIdReturnsThatArea() {
        int rootId = given().queryParam("lang", "en").when().get("/rest/areas/area-roots")
                .then().statusCode(200).extract().path("[0].id");

        given()
                .when().get("/rest/areas/area/" + rootId)
                .then().statusCode(200)
                .body("id", is(rootId));
    }

    @Test
    void searchByIdsReturnsRequestedArea() {
        int rootId = given().queryParam("lang", "en").when().get("/rest/areas/area-roots")
                .then().statusCode(200).extract().path("[0].id");

        given()
                .queryParam("lang", "en")
                .when().get("/rest/areas/search/" + rootId)
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].id", is(rootId));
    }

    @Test
    void searchWithBlankNameReturnsEmptyList() {
        given()
                .queryParam("name", "")
                .queryParam("limit", 10)
                .when().get("/rest/areas/search")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — protected writes without a token return 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createAreaWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("descs", List.of(Map.of("lang", "en", "name", "x"))))
                .when().post("/rest/areas/area/")
                .then().statusCode(401);
    }

    /**
     * The createArea mapping ends in a trailing slash ({@code /area/}). JAX-RS matched it with and without
     * the trailing slash; Spring's PathPatternParser does not, so the slash-less spelling the UI uses must
     * be dual-mapped. This pins that the slash-less route still resolves to the handler — security runs
     * before the body, so a missing token yields 401 (route matched), not 404 (route missing).
     */
    @Test
    void createAreaWithoutTrailingSlashRoutesToHandler() {
        given().contentType(ContentType.JSON).body(Map.of("descs", List.of(Map.of("lang", "en", "name", "x"))))
                .when().post("/rest/areas/area")
                .then().statusCode(401);
    }

    @Test
    void updateAreaWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("id", 1))
                .when().put("/rest/areas/area/1")
                .then().statusCode(401);
    }

    @Test
    void deleteAreaWithoutTokenIsUnauthorized() {
        given().when().delete("/rest/areas/area/1").then().statusCode(401);
    }

    @Test
    void moveAreaWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("areaId", 1, "parentId", 2))
                .when().put("/rest/areas/move-area")
                .then().statusCode(401);
    }

    @Test
    void changeSortOrderWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("areaId", 1, "moveUp", true))
                .when().put("/rest/areas/change-sort-order")
                .then().statusCode(401);
    }

    @Test
    void recomputeTreeSortOrderWithoutTokenIsUnauthorized() {
        given().when().put("/rest/areas/recompute-tree-sort-order").then().statusCode(401);
    }

    @Test
    void uploadAreasWithoutTokenIsUnauthorized() {
        given().multiPart("data", "areas.json", "[]".getBytes())
                .when().post("/rest/areas/upload-areas")
                .then().statusCode(401);
    }

    @Test
    void intersectingAreasWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("type", "FeatureCollection", "features", List.of()))
                .when().post("/rest/areas/intersecting-areas")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Positive role-based — admin/editor with a real token
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createAreaAsAdminPersistsAndIsReadable() {
        int id = createAreaAsAdmin("IT-REST-Create");

        given()
                .when().get("/rest/areas/area/" + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("descs[0].name", is("IT-REST-Create"));
    }

    @Test
    void updateAreaAsAdminReturnsUpdatedVo() {
        int id = createAreaAsAdmin("IT-REST-Upd");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("id", id, "descs", List.of(Map.of("lang", "en", "name", "IT-REST-Upd-Renamed"))))
                .when().put("/rest/areas/area/" + id)
                .then().statusCode(200)
                .body("descs[0].name", is("IT-REST-Upd-Renamed"));

        given()
                .when().get("/rest/areas/area/" + id)
                .then().statusCode(200)
                .body("descs[0].name", is("IT-REST-Upd-Renamed"));
    }

    @Test
    void updateAreaWithMismatchedIdReturns400() {
        int id = createAreaAsAdmin("IT-REST-Mismatch");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("id", id + 1, "descs", List.of(Map.of("lang", "en", "name", "x"))))
                .when().put("/rest/areas/area/" + id)
                .then().statusCode(400);
    }

    @Test
    void deleteAreaAsAdminReturnsTrue() {
        int id = createAreaAsAdmin("IT-REST-Del");

        String body = given().auth().oauth2(accessToken())
                .when().delete("/rest/areas/area/" + id)
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("true", body);
    }

    @Test
    void moveAreaAsAdminReparentsArea() {
        int parentA = createAreaAsAdmin("IT-REST-MoveA");
        int parentB = createAreaAsAdmin("IT-REST-MoveB");
        int child = createAreaAsAdmin("IT-REST-MoveChild", parentA);

        String body = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("areaId", child, "parentId", parentB))
                .when().put("/rest/areas/move-area")
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("true", body);

        given()
                .queryParam("parent", true)
                .when().get("/rest/areas/area/" + child)
                .then().statusCode(200)
                .body("parent.id", is(parentB));
    }

    @Test
    void changeSortOrderAsAdminReturnsTrue() {
        int parent = createAreaAsAdmin("IT-REST-SortParent");
        int first = createAreaAsAdmin("IT-REST-Sort1", parent);
        int second = createAreaAsAdmin("IT-REST-Sort2", parent);

        // Pin a deterministic sibling order (first=10, second=20). Without this both children keep the
        // default siblingSortOrder 0.0, so the DB returns the tied rows in an arbitrary order that
        // depends on area-table state left by earlier ITs — making "move the second child up" flake
        // (it returns false when the DB happened to order 'second' first). See FLAKE1.
        setSiblingSortOrderAsAdmin(first, 10.0);
        setSiblingSortOrderAsAdmin(second, 20.0);

        String body = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("areaId", second, "moveUp", true))
                .when().put("/rest/areas/change-sort-order")
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("true", body);

        // The moved child must now sort above its sibling (strictly smaller siblingSortOrder) — a real
        // outcome check, not just the boolean, and immune to any pre-existing area rows.
        assertTrue(siblingSortOrderAsAdmin(second) < siblingSortOrderAsAdmin(first),
                "the moved-up child must end up before its sibling");
    }

    // ------------------------------------------------------------------------------------------- //
    // An update keeps the area's place among its siblings unless the body names one
    // ------------------------------------------------------------------------------------------- //

    /** Renames an area with a body that carries no siblingSortOrder, as the area editor's save does. */
    private void renameAreaWithoutOrderAsAdmin(int id, String name) {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("id", id, "descs", List.of(Map.of("lang", "en", "name", name))))
                .when().put("/rest/areas/area/" + id)
                .then().statusCode(200);
    }

    /**
     * The area editor sends no siblingSortOrder. The update used to turn the missing value into 0, so every save moved
     * the area first among its siblings and lost its stored place. A body without the field keeps the stored order.
     */
    @Test
    void updateAreaWithoutSiblingSortOrderKeepsTheStoredOrder() {
        int parent = createAreaAsAdmin("IT-REST-KeepParent");
        int child = createAreaAsAdmin("IT-REST-Keep", parent);
        setSiblingSortOrderAsAdmin(child, 42.5);

        renameAreaWithoutOrderAsAdmin(child, "IT-REST-Keep-Renamed");

        assertEquals(42.5, siblingSortOrderAsAdmin(child), 0.0, "a name-only save must keep the stored order");
        given()
                .when().get("/rest/areas/area/" + child)
                .then().statusCode(200)
                .body("descs[0].name", is("IT-REST-Keep-Renamed"));
    }

    /** A body that does carry siblingSortOrder still sets it, as an API caller may. */
    @Test
    void updateAreaWithSiblingSortOrderSetsIt() {
        int parent = createAreaAsAdmin("IT-REST-SetParent");
        int child = createAreaAsAdmin("IT-REST-Set", parent);
        setSiblingSortOrderAsAdmin(child, 42.5);

        setSiblingSortOrderAsAdmin(child, 7.0);

        assertEquals(7.0, siblingSortOrderAsAdmin(child), 0.0, "an order in the body must be stored");
    }

    /**
     * Move up while the editor stays open, then Save: the editor's body carries no order, so the save must keep the
     * order the move gave the area, not write back an older one or 0. Three siblings, so that the move gives the
     * third a midpoint (15) between the other two, and a reset to 0 would put it first instead of second.
     */
    @Test
    void moveUpThenANameOnlyUpdateKeepsTheMovedOrder() {
        int parent = createAreaAsAdmin("IT-REST-MoveKeepParent");
        int first = createAreaAsAdmin("IT-REST-MoveKeep1", parent);
        int second = createAreaAsAdmin("IT-REST-MoveKeep2", parent);
        int third = createAreaAsAdmin("IT-REST-MoveKeep3", parent);
        setSiblingSortOrderAsAdmin(first, 10.0);
        setSiblingSortOrderAsAdmin(second, 20.0);
        setSiblingSortOrderAsAdmin(third, 30.0);

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("areaId", third, "moveUp", true))
                .when().put("/rest/areas/change-sort-order")
                .then().statusCode(200);
        double moved = siblingSortOrderAsAdmin(third);
        assertEquals(15.0, moved, 0.0, "the move must put the area between its two siblings");

        renameAreaWithoutOrderAsAdmin(third, "IT-REST-MoveKeep3-Renamed");

        assertEquals(moved, siblingSortOrderAsAdmin(third), 0.0, "the save must keep the order the move gave");
        assertEquals(10.0, siblingSortOrderAsAdmin(first), 0.0, "the save must leave the siblings alone");
        assertEquals(20.0, siblingSortOrderAsAdmin(second), 0.0, "the save must leave the siblings alone");
    }

    @Test
    void recomputeTreeSortOrderAsAdminReturnsTrue() {
        String body = given().auth().oauth2(accessToken())
                .when().put("/rest/areas/recompute-tree-sort-order")
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("true", body);
    }

    @Test
    void intersectingAreasAsEditorIsAuthorizedAndReturnsList() {
        // sysadmin carries the editor role; an empty feature collection yields an empty result list,
        // which still proves the EDITOR-protected endpoint is reachable and returns 200.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("type", "FeatureCollection", "features", List.of()))
                .when().post("/rest/areas/intersecting-areas")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void intersectingAreasWithANullCoordinate_isRefused() {
        // A coordinate sent as null is refused as the save refuses it, not searched as not a number
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("type", "FeatureCollection", "features", List.of(Map.of(
                        "type", "Feature", "properties", Map.of(),
                        "geometry", Map.of("type", "Point", "coordinates", Arrays.asList(10.0, null))))))
                .when().post("/rest/areas/intersecting-areas")
                .then().statusCode(400)
                .body("detail", containsString("is not a number"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Create must not accept a client-supplied id (the root cause: MASSASSIGN-1 in be/docs/potential-errors.md)
    // ------------------------------------------------------------------------------------------- //

    /**
     * {@code new Area(areaVo)} copies the body's id and {@code saveEntity()} merges a persisted entity, so a
     * create body carrying an id used to overwrite that area in place (and orphan-delete its descriptions)
     * while answering {@code 200}. The handler must reject it with {@code 400} and leave the target alone.
     */
    @Test
    void createAreaRejectsBodyWithIdAndLeavesTheTargetAreaUntouched() {
        int victimId = createAreaAsAdmin("IT-REST-Area-Id-Victim");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", victimId,
                        "active", true,
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-Area-Id-Hijacked"))))
                .when().post("/rest/areas/area/")
                .then().statusCode(400);

        // The victim area must still carry its own name - the create must not have merged onto it.
        given().auth().oauth2(accessToken())
                .when().get("/rest/areas/area/" + victimId)
                .then().statusCode(200)
                .body("descs[0].name", is("IT-REST-Area-Id-Victim"));
    }

    /**
     * ERR6: a parent id that names no area dereferenced the missing parent, a 500. The create answers the move's
     * refusal for an unknown parent, 404 with its sentence, and makes no area.
     */
    @Test
    void createAreaUnderAnUnknownParentIs404AndMakesNoArea() {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-Area-Orphan")),
                        "parent", Map.of("id", 987654321)))
                .when().post("/rest/areas/area/")
                .then().statusCode(404)
                .body("detail", is("No Area with id 987654321"));

        given().queryParam("lang", "en").queryParam("name", "IT-REST-Area-Orphan").queryParam("limit", 10)
                .when().get("/rest/areas/search")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // An area's geometry is stored on the globe (CG8)
    // ------------------------------------------------------------------------------------------- //

    /** Reads back the first coordinate of an area's stored point geometry, as [lon, lat]. */
    private double[] storedPoint(int id) {
        List<Number> coordinates = given().auth().oauth2(accessToken())
                .when().get("/rest/areas/area/" + id)
                .then().statusCode(200)
                .extract().path("geometry.coordinates");
        return new double[] { coordinates.get(0).doubleValue(), coordinates.get(1).doubleValue() };
    }

    /**
     * A point drawn on the map's world copy to the east lies beyond 180 degrees. The create stores the whole
     * geometry one turn back, on the globe, and so does an update.
     */
    @Test
    void anAreaSavedWithAPointAt190East_comesBackAt170West() {
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-OnGlobe")),
                        "geometry", Map.of("type", "Point", "coordinates", List.of(190.0, 56.0))))
                .when().post("/rest/areas/area/")
                .then().statusCode(200)
                .extract().path("id");
        createdAreaIds.add(id);

        double[] created = storedPoint(id);
        assertEquals(-170.0, created[0], 1e-9, "the stored longitude must be on the globe");
        assertEquals(56.0, created[1], 1e-9);

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", id,
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-OnGlobe")),
                        "geometry", Map.of("type", "Point", "coordinates", List.of(-190.0, 55.0))))
                .when().put("/rest/areas/area/" + id)
                .then().statusCode(200);

        double[] updated = storedPoint(id);
        assertEquals(170.0, updated[0], 1e-9, "the updated longitude must be on the globe");
        assertEquals(55.0, updated[1], 1e-9);
    }

    /** A latitude beyond 90 is no place on the globe: the create is a 400 and makes no area. */
    @Test
    void anAreaSavedWithAPointAt95North_isRefusedAndNotMade() {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-OffGlobe")),
                        "geometry", Map.of("type", "Point", "coordinates", List.of(10.0, 95.0))))
                .when().post("/rest/areas/area/")
                .then().statusCode(400);

        given().queryParam("lang", "en").queryParam("name", "IT-REST-OffGlobe").queryParam("limit", 10)
                .when().get("/rest/areas/search")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    /** An update to a latitude beyond 90 is a 400 and keeps the stored geometry. */
    @Test
    void anAreaUpdateWithAPointAt95North_isRefusedAndKeepsTheStoredGeometry() {
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-OffGlobeUpd")),
                        "geometry", Map.of("type", "Point", "coordinates", List.of(10.5, 54.5))))
                .when().post("/rest/areas/area/")
                .then().statusCode(200)
                .extract().path("id");
        createdAreaIds.add(id);

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", id,
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-OffGlobeUpd")),
                        "geometry", Map.of("type", "Point", "coordinates", List.of(10.5, 95.0))))
                .when().put("/rest/areas/area/" + id)
                .then().statusCode(400);

        double[] stored = storedPoint(id);
        assertEquals(10.5, stored[0], 1e-9);
        assertEquals(54.5, stored[1], 1e-9, "the refused update must leave the stored latitude");
    }

    /** CG10: a coordinate sent as null was stored as 0; the create is a 400, as for one that is not a number. */
    @Test
    void anAreaSavedWithANullLatitude_isRefusedAndNotMade() {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-NullCoord")),
                        "geometry", Map.of("type", "Point", "coordinates", Arrays.asList(10.0, null))))
                .when().post("/rest/areas/area/")
                .then().statusCode(400)
                .body("detail", containsString("is not a number"));

        given().queryParam("lang", "en").queryParam("name", "IT-REST-NullCoord").queryParam("limit", 10)
                .when().get("/rest/areas/search")
                .then().statusCode(200)
                .body("size()", is(0));
    }

}
