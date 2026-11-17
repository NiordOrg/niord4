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

import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tree and template writes with an id that names nothing, or a body without the field they need, are client errors
 * (ERR3): an unknown id answers 404 and a missing field 400, where they used to end in a
 * {@code NullPointerException} and a 500. The right paths beside them still answer 200.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class UnknownIdWritesITTest extends RoundTripSupport {

    /** An id no area, category or message has */
    private static final int UNKNOWN = 987654321;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        // Last made, first removed
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Areas and categories
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anUpdateOfAnUnknownAreaOrCategoryAnswers404() {
        putRaw("admin", "/rest/areas/area/" + UNKNOWN, map("id", UNKNOWN, "active", true,
                "descs", list(map("lang", "en", "name", "IT-unknown area")))).statusCode(404);
        putRaw("sysadmin", "/rest/categories/category/" + UNKNOWN, map("id", UNKNOWN, "type", "CATEGORY", "active", true,
                "descs", list(map("lang", "en", "name", "IT-unknown category")))).statusCode(404);
    }

    @Test
    void aSortOrderChangeOfAnUnknownAreaOrCategoryAnswers404() {
        putRaw("admin", "/rest/areas/change-sort-order", map("areaId", UNKNOWN, "moveUp", true)).statusCode(404);
        putRaw("sysadmin", "/rest/categories/change-sort-order", map("categoryId", UNKNOWN, "moveUp", true))
                .statusCode(404);
    }

    @Test
    void aMoveOfAnUnknownAreaOrCategoryAnswers404() {
        putRaw("admin", "/rest/areas/move-area", moveBody("areaId", UNKNOWN, null)).statusCode(404);
        putRaw("sysadmin", "/rest/categories/move-category", moveBody("categoryId", UNKNOWN, null)).statusCode(404);
    }

    @Test
    void aMoveUnderAnUnknownParentAnswers404AndLeavesTheEntityWhereItWas() {
        String name = "IT-unknown parent " + unique();
        int areaParent = create("admin", "/rest/areas/area", name + " area A", null, null);
        int area = create("admin", "/rest/areas/area", name + " area B", areaParent, null);
        int categoryParent = create("sysadmin", "/rest/categories/category", name + " category A", null, "CATEGORY");
        int category = create("sysadmin", "/rest/categories/category", name + " category B", categoryParent, "CATEGORY");

        putRaw("admin", "/rest/areas/move-area", moveBody("areaId", area, UNKNOWN)).statusCode(404);
        putRaw("sysadmin", "/rest/categories/move-category", moveBody("categoryId", category, UNKNOWN)).statusCode(404);

        assertEquals(List.of(areaParent, categoryParent),
                List.of(ints("SELECT parent_id FROM public.\"Area\" WHERE id = ?1", area).getFirst(),
                        ints("SELECT parent_id FROM public.\"Category\" WHERE id = ?1", category).getFirst()),
                "the area and the category keep their parents");
        // The right path: the same area moved to the root
        assertEquals("true", putRaw("admin", "/rest/areas/move-area", moveBody("areaId", area, null))
                .statusCode(200).extract().asString().trim());
    }

    // ------------------------------------------------------------------------------------------- //
    // Templates
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aTemplateExecutionOnAnUnknownMessageAnswers404() {
        putRaw("user", "/rest/templates/execute", map("messageId", "it-no-such-message-" + unique())).statusCode(404);
    }

    @Test
    void aCallSignValidationWithoutAValueAnswers400() {
        for (Map<String, Object> body : List.of(map(), map("value", null))) {
            given().contentType(ContentType.JSON).body(body)
                    .when().post("/rest/templates/validate-call-sign")
                    .then().statusCode(400);
        }
        // The right path
        given().contentType(ContentType.JSON).body(map("value", "oxyz"))
                .when().post("/rest/templates/validate-call-sign")
                .then().statusCode(200).body("value", is("OXYZ"));
    }

    // ------------------------------------------------------------------------------------------- //
    // AtoNs
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aNodeTypeMergeWithoutAnAtonAnswers400() {
        given().contentType(ContentType.JSON).body(map())
                .when().post("/rest/atons/defaults/merge-with-node-types")
                .then().statusCode(400);
        // The right path: an AtoN, and no node type names, is the AtoN unchanged
        given().contentType(ContentType.JSON)
                .body(map("aton", map("lat", 55.0, "lon", 11.0, "tags", map("seamark:type", "buoy_cardinal")),
                        "nodeTypeNames", null))
                .when().post("/rest/atons/defaults/merge-with-node-types")
                .then().statusCode(200).body("tags.'seamark:type'", is("buoy_cardinal"));
    }

    @Test
    void anAtonIconWithoutTagsAnswers400() {
        for (Map<String, Object> body : List.of(map(), map("id", 2))) {
            given().contentType(ContentType.JSON).accept("*/*").body(body)
                    .when().post("/rest/aton-icon/svg")
                    .then().statusCode(400);
        }
        // The right path
        given().contentType(ContentType.JSON).accept("*/*")
                .body(map("lat", 56.0, "lon", 11.0, "tags", map("seamark:type", "buoy_cardinal",
                        "seamark:buoy_cardinal:colour", "black")))
                .when().post("/rest/aton-icon/svg")
                .then().statusCode(200);
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    private static Map<String, Object> moveBody(String idKey, int id, Integer parentId) {
        Map<String, Object> body = map(idKey, id);
        body.put("parentId", parentId);
        return body;
    }

    private int create(String role, String path, String name, Integer parentId, String type) {
        Map<String, Object> body = map("active", true, "descs", list(map("lang", "en", "name", name)));
        if (type != null) {
            body.put("type", type);
        }
        if (parentId != null) {
            body.put("parent", map("id", parentId));
        }
        int id = ((Number) post(role, path, body).get("id")).intValue();
        // A sub-entity may need two deletes: the first can leave it as a root (AREA1)
        cleanups.add(() -> {
            delete(role, path + "/" + id);
            delete(role, path + "/" + id);
        });
        return id;
    }
}
