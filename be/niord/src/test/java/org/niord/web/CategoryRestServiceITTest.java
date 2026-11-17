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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for {@link CategoryRestService}: the HTTP contract of the {@code /rest/categories}
 * endpoints, including authorization. Reads ({@code GET search}, {@code all}, {@code category-roots},
 * {@code category/{id}}, {@code export}) are {@code @PermitAll}; the AtoN-filtering {@code PUT search}
 * requires the {@code editor} role and takes at most {@link CategoryRestService#MAX_SEARCH_ATONS} AtoNs;
 * all writes (create/update/delete, move-category, change-sort-order, recompute-tree-sort-order,
 * upload-categories) require the {@code sysadmin} role, exercised with a real Keycloak token.
 *
 * <p>Reads and negative-security cases need no token. Positive role-based cases create data that is
 * committed (HTTP requests run in the server's own transaction, which {@code @Transactional} cannot
 * roll back), so created categories are tracked and deleted in {@link #cleanup()} using unique
 * {@code IT-REST-} names that never occur in the seed data.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class CategoryRestServiceITTest extends ITTest {

    private final List<Integer> createdCategoryIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Integer id : createdCategoryIds) {
            try {
                given().auth().oauth2(accessToken()).delete("/rest/categories/category/" + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdCategoryIds.clear();
    }

    private int createCategoryAsAdmin(String name) {
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("descs", List.of(Map.of("lang", "en", "name", name))))
                .when().post("/rest/categories/category/")
                .then().statusCode(200)
                .extract().path("id");
        createdCategoryIds.add(id);
        return id;
    }

    private int createCategoryAsAdmin(String name, int parentId) {
        return createCategoryAsAdmin(name, parentId, 0.0);
    }

    private int createCategoryAsAdmin(String name, int parentId, double siblingSortOrder) {
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "descs", List.of(Map.of("lang", "en", "name", name)),
                        "parent", Map.of("id", parentId),
                        "siblingSortOrder", siblingSortOrder))
                .when().post("/rest/categories/category/")
                .then().statusCode(200)
                .extract().path("id");
        createdCategoryIds.add(id);
        return id;
    }

    // ------------------------------------------------------------------------------------------- //
    // Unauthenticated reads (@PermitAll)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void categoryRootsReturnsNonEmptyTreeWithDescriptions() {
        given()
                .queryParam("lang", "en")
                .when().get("/rest/categories/category-roots")
                .then().statusCode(200)
                .body("size()", greaterThan(0))
                .body("[0].descs", notNullValue());
    }

    @Test
    void getAllReturnsNonEmptyTree() {
        given()
                .when().get("/rest/categories/all")
                .then().statusCode(200)
                .body("size()", greaterThan(0));
    }

    @Test
    void exportPrunesIdsFromCategories() {
        // The export endpoint nulls out the ids of every (recursively nested) category, while
        // keeping the descriptions. RestAssured maps a missing/null JSON field to a Java null.
        given()
                .when().get("/rest/categories/export")
                .then().statusCode(200)
                .body("size()", greaterThan(0))
                .body("[0].id", nullValue())
                .body("[0].descs", notNullValue());
    }

    @Test
    void getCategoryByIdReturnsThatCategory() {
        int rootId = given().queryParam("lang", "en").when().get("/rest/categories/category-roots")
                .then().statusCode(200).extract().path("[0].id");

        given()
                .when().get("/rest/categories/category/" + rootId)
                .then().statusCode(200)
                .body("id", is(rootId));
    }

    @Test
    void searchByIdsReturnsRequestedCategory() {
        int rootId = given().queryParam("lang", "en").when().get("/rest/categories/category-roots")
                .then().statusCode(200).extract().path("[0].id");

        given()
                .queryParam("lang", "en")
                .when().get("/rest/categories/search/" + rootId)
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("[0].id", is(rootId));
    }

    @Test
    void searchByNameMatchesSeededCategory() {
        // "Light" is a stable seeded English category name; the search does a case-insensitive
        // substring match, so every returned description name must contain the query.
        given()
                .queryParam("lang", "en")
                .queryParam("name", "Light")
                .queryParam("limit", 100)
                .when().get("/rest/categories/search")
                .then().statusCode(200)
                .body("size()", greaterThan(0))
                .body("descs.flatten().name", hasItem(containsString("Light")));
    }

    @Test
    void searchByUnmatchedNameReturnsEmptyList() {
        given()
                .queryParam("lang", "en")
                .queryParam("name", "IT-REST-NoSuchCategoryXYZ")
                .queryParam("limit", 10)
                .when().get("/rest/categories/search")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void searchHonoursLimit() {
        // A blank name applies no name predicate, so the search returns active categories up to the
        // limit. Asserting the result size never exceeds the requested limit verifies the cap.
        given()
                .queryParam("lang", "en")
                .queryParam("limit", 3)
                .when().get("/rest/categories/search")
                .then().statusCode(200)
                .body("size()", is(3));
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /search — the AtoN-filtering search runs every AtoN through the AtoN filter of every
    // candidate category, so it is @RolesAllowed(EDITOR) and takes at most MAX_SEARCH_ATONS AtoNs.
    // ------------------------------------------------------------------------------------------- //

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    /** An AtoN with the given seamark:type, in the compact tag-map JSON form */
    private static Map<String, Object> aton(String seamarkType) {
        return Map.of("tags", Map.of("seamark:type", seamarkType));
    }

    @Test
    void atonSearchWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .queryParam("type", "TEMPLATE")
                .body(List.of(aton("light_minor")))
                .when().put("/rest/categories/search")
                .then().statusCode(401);
    }

    @Test
    void atonSearchAsPlainUserIsForbidden() {
        given().auth().oauth2(accessToken("user")).header("NiordDomain", NW_DOMAIN)
                .contentType(ContentType.JSON)
                .queryParam("type", "TEMPLATE")
                .body(List.of(aton("light_minor")))
                .when().put("/rest/categories/search")
                .then().statusCode(403);
    }

    @Test
    void atonSearchAsEditorReturnsTheTemplatesUnderTheMatchingFilter() {
        // Seed: category 1 ("Light") carries aton.kv('seamark:type', 'light.*') and parents the templates
        // 2, 3 and 4; category 5 carries a buoy filter and parents the templates 6 to 12. A light AtoN
        // selects the first family only.
        given().auth().oauth2(accessToken("editor")).header("NiordDomain", NW_DOMAIN)
                .contentType(ContentType.JSON)
                .queryParam("type", "TEMPLATE")
                .body(List.of(aton("light_minor")))
                .when().put("/rest/categories/search")
                .then().statusCode(200)
                .body("id", hasItem(2))
                .body("id", not(hasItem(6)));
    }

    @Test
    void atonSearchWithMoreAtonsThanTheCapIsRejected() {
        List<Map<String, Object>> atons =
                Collections.nCopies(CategoryRestService.MAX_SEARCH_ATONS + 1, aton("light_minor"));

        given().auth().oauth2(accessToken("editor")).header("NiordDomain", NW_DOMAIN)
                .contentType(ContentType.JSON)
                .queryParam("type", "TEMPLATE")
                .body(atons)
                .when().put("/rest/categories/search")
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — protected writes without a token return 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createCategoryWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("descs", List.of(Map.of("lang", "en", "name", "x"))))
                .when().post("/rest/categories/category/")
                .then().statusCode(401);
    }

    @Test
    void updateCategoryWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("id", 1))
                .when().put("/rest/categories/category/1")
                .then().statusCode(401);
    }

    @Test
    void deleteCategoryWithoutTokenIsUnauthorized() {
        given().when().delete("/rest/categories/category/1").then().statusCode(401);
    }

    @Test
    void moveCategoryWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("categoryId", 1, "parentId", 2))
                .when().put("/rest/categories/move-category")
                .then().statusCode(401);
    }

    @Test
    void changeSortOrderWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("categoryId", 1, "moveUp", true))
                .when().put("/rest/categories/change-sort-order")
                .then().statusCode(401);
    }

    @Test
    void recomputeTreeSortOrderWithoutTokenIsUnauthorized() {
        given().when().put("/rest/categories/recompute-tree-sort-order").then().statusCode(401);
    }

    @Test
    void uploadCategoriesWithoutTokenIsUnauthorized() {
        given().multiPart("data", "categories.json", "[]".getBytes())
                .when().post("/rest/categories/upload-categories")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Positive role-based — sysadmin with a real token
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createCategoryAsAdminPersistsAndIsReadable() {
        int id = createCategoryAsAdmin("IT-REST-Create");

        given()
                .when().get("/rest/categories/category/" + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("descs[0].name", is("IT-REST-Create"));
    }

    @Test
    void createChildCategoryReferencesItsParent() {
        int parent = createCategoryAsAdmin("IT-REST-Parent");
        int child = createCategoryAsAdmin("IT-REST-Child", parent);

        // CR1: GET /rest/categories/category/{id}?parent=true now honours the param and serializes the
        // parent, mirroring AreaRestService.getArea. The default (no param) still omits it (below).
        given()
                .queryParam("parent", true)
                .when().get("/rest/categories/category/" + child)
                .then().statusCode(200)
                .body("id", is(child))
                .body("parent.id", is(parent));

        // Default request (no parent param) omits the parent — additive change, default unchanged.
        given()
                .when().get("/rest/categories/category/" + child)
                .then().statusCode(200)
                .body("id", is(child))
                .body("parent", is(nullValue()));
    }

    @Test
    void updateCategoryAsAdminReturnsUpdatedVo() {
        int id = createCategoryAsAdmin("IT-REST-Upd");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("id", id, "descs", List.of(Map.of("lang", "en", "name", "IT-REST-Upd-Renamed"))))
                .when().put("/rest/categories/category/" + id)
                .then().statusCode(200)
                .body("descs[0].name", is("IT-REST-Upd-Renamed"));

        given()
                .when().get("/rest/categories/category/" + id)
                .then().statusCode(200)
                .body("descs[0].name", is("IT-REST-Upd-Renamed"));
    }

    @Test
    void updateCategoryWithMismatchedIdReturns400() {
        int id = createCategoryAsAdmin("IT-REST-Mismatch");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("id", id + 1, "descs", List.of(Map.of("lang", "en", "name", "x"))))
                .when().put("/rest/categories/category/" + id)
                .then().statusCode(400);
    }

    @Test
    void deleteCategoryAsAdminReturnsTrueAndRemovesIt() {
        int id = createCategoryAsAdmin("IT-REST-Del");

        String body = given().auth().oauth2(accessToken())
                .when().delete("/rest/categories/category/" + id)
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("true", body);

        // Drop from the tracking list since it is already gone.
        createdCategoryIds.remove(Integer.valueOf(id));

        // The deleted category is no longer returned by an id search.
        given()
                .queryParam("lang", "en")
                .when().get("/rest/categories/search/" + id)
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void deleteUnknownCategoryReturnsFalse() {
        // A very large id that cannot exist in the seed; delete is idempotent and returns false.
        String body = given().auth().oauth2(accessToken())
                .when().delete("/rest/categories/category/999999999")
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("false", body);
    }

    @Test
    void moveCategoryAsAdminReparentsCategory() {
        int parentA = createCategoryAsAdmin("IT-REST-MoveA");
        int parentB = createCategoryAsAdmin("IT-REST-MoveB");
        int child = createCategoryAsAdmin("IT-REST-MoveChild", parentA);

        String body = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("categoryId", child, "parentId", parentB))
                .when().put("/rest/categories/move-category")
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("true", body);

        // CR1: verify the reparenting via GET /rest/categories/category/{id}?parent=true, which now
        // serializes the (new) parent.
        given()
                .queryParam("parent", true)
                .when().get("/rest/categories/category/" + child)
                .then().statusCode(200)
                .body("id", is(child))
                .body("parent.id", is(parentB));
    }

    @Test
    void changeSortOrderAsAdminReturnsTrue() {
        int parent = createCategoryAsAdmin("IT-REST-SortParent");
        // Distinct sibling sort orders: with equal ones, the siblings come in the database's row order, which is not
        // the order of creation once deleted rows have left free space in the table
        createCategoryAsAdmin("IT-REST-Sort1", parent, 0.0);
        int second = createCategoryAsAdmin("IT-REST-Sort2", parent, 10.0);

        String body = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("categoryId", second, "moveUp", true))
                .when().put("/rest/categories/change-sort-order")
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("true", body);
    }

    // ------------------------------------------------------------------------------------------- //
    // An update keeps the category's place among its siblings unless the body names one
    // ------------------------------------------------------------------------------------------- //

    /** Reads back the stored sibling sort order of a category. */
    private double siblingSortOrderOf(int id) {
        Number sortOrder = given()
                .when().get("/rest/categories/category/" + id)
                .then().statusCode(200)
                .extract().path("siblingSortOrder");
        return sortOrder.doubleValue();
    }

    /** Renames a category with a body that carries no siblingSortOrder, as the category editor's save does. */
    private void renameCategoryWithoutOrderAsAdmin(int id, String name) {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("id", id, "descs", List.of(Map.of("lang", "en", "name", name))))
                .when().put("/rest/categories/category/" + id)
                .then().statusCode(200);
    }

    /**
     * The category editor sends no siblingSortOrder. The update used to turn the missing value into 0, so every save
     * moved the category first among its siblings and lost its stored place. A body without the field keeps the
     * stored order.
     */
    @Test
    void updateCategoryWithoutSiblingSortOrderKeepsTheStoredOrder() {
        int parent = createCategoryAsAdmin("IT-REST-KeepParent");
        int child = createCategoryAsAdmin("IT-REST-Keep", parent, 42.5);

        renameCategoryWithoutOrderAsAdmin(child, "IT-REST-Keep-Renamed");

        assertEquals(42.5, siblingSortOrderOf(child), 0.0, "a name-only save must keep the stored order");
        given()
                .when().get("/rest/categories/category/" + child)
                .then().statusCode(200)
                .body("descs[0].name", is("IT-REST-Keep-Renamed"));
    }

    /** A body that does carry siblingSortOrder still sets it, as an API caller may. */
    @Test
    void updateCategoryWithSiblingSortOrderSetsIt() {
        int parent = createCategoryAsAdmin("IT-REST-SetParent");
        int child = createCategoryAsAdmin("IT-REST-Set", parent, 42.5);

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", child,
                        "siblingSortOrder", 7.0,
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-Set"))))
                .when().put("/rest/categories/category/" + child)
                .then().statusCode(200);

        assertEquals(7.0, siblingSortOrderOf(child), 0.0, "an order in the body must be stored");
    }

    /**
     * Move up while the editor stays open, then Save: the editor's body carries no order, so the save must keep the
     * order the move gave the category, not write back an older one or 0. Three siblings, so that the move gives the
     * third a midpoint (15) between the other two, and a reset to 0 would put it first instead of second.
     */
    @Test
    void moveUpThenANameOnlyUpdateKeepsTheMovedOrder() {
        int parent = createCategoryAsAdmin("IT-REST-MoveKeepParent");
        int first = createCategoryAsAdmin("IT-REST-MoveKeep1", parent, 10.0);
        int second = createCategoryAsAdmin("IT-REST-MoveKeep2", parent, 20.0);
        int third = createCategoryAsAdmin("IT-REST-MoveKeep3", parent, 30.0);

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("categoryId", third, "moveUp", true))
                .when().put("/rest/categories/change-sort-order")
                .then().statusCode(200);
        double moved = siblingSortOrderOf(third);
        assertEquals(15.0, moved, 0.0, "the move must put the category between its two siblings");

        renameCategoryWithoutOrderAsAdmin(third, "IT-REST-MoveKeep3-Renamed");

        assertEquals(moved, siblingSortOrderOf(third), 0.0, "the save must keep the order the move gave");
        assertEquals(10.0, siblingSortOrderOf(first), 0.0, "the save must leave the siblings alone");
        assertEquals(20.0, siblingSortOrderOf(second), 0.0, "the save must leave the siblings alone");
    }

    @Test
    void recomputeTreeSortOrderAsAdminReturnsTrue() {
        String body = given().auth().oauth2(accessToken())
                .when().put("/rest/categories/recompute-tree-sort-order")
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("true", body);
    }

    // ------------------------------------------------------------------------------------------- //
    // Create must not accept a client-supplied id (SECAUD-14)
    // ------------------------------------------------------------------------------------------- //

    /**
     * {@code new Category(categoryVo)} copies the body's id and {@code saveEntity()} merges a persisted
     * entity, so a create body carrying an id used to overwrite that category in place while answering
     * {@code 200}. The handler must reject it with {@code 400} and leave the target alone.
     */
    @Test
    void createCategoryRejectsBodyWithIdAndLeavesTheTargetCategoryUntouched() {
        int victimId = createCategoryAsAdmin("IT-REST-Category-Id-Victim");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", victimId,
                        "active", true,
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-Category-Id-Hijacked"))))
                .when().post("/rest/categories/category/")
                .then().statusCode(400);

        // The victim category must still carry its own name - the create must not have merged onto it.
        given().auth().oauth2(accessToken())
                .when().get("/rest/categories/category/" + victimId)
                .then().statusCode(200)
                .body("descs[0].name", is("IT-REST-Category-Id-Victim"));
    }

    /**
     * ERR6: a parent id that names no category dereferenced the missing parent, a 500. The create answers the
     * move's refusal for an unknown parent, 404 with its sentence, and makes no category.
     */
    @Test
    void createCategoryUnderAnUnknownParentIs404AndMakesNoCategory() {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "type", "CATEGORY",
                        "descs", List.of(Map.of("lang", "en", "name", "IT-REST-Category-Orphan")),
                        "parent", Map.of("id", 987654321)))
                .when().post("/rest/categories/category/")
                .then().statusCode(404)
                .body("detail", is("No Category with id 987654321"));

        given().queryParam("lang", "en").queryParam("name", "IT-REST-Category-Orphan")
                .when().get("/rest/categories/search")
                .then().statusCode(200)
                .body("size()", is(0));
    }

}
