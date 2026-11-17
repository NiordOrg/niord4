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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A move in the area tree ({@code PUT /rest/areas/move-area}) or the category tree
 * ({@code PUT /rest/categories/move-category}) must never make an entity its own ancestor (AREA3). Such a move is
 * refused with 400 and changes nothing: the parents and lineages stay as they were, and the reads that follow the
 * parent chain keep answering 200. A move elsewhere in the tree still works.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class TreeMoveCycleITTest extends RoundTripSupport {

    /** One of the two trees: its REST paths, the role that writes it and its table */
    private record Tree(String base, String role, String idKey, String table, String moveLeaf, String body) {
    }

    private static final Tree AREAS = new Tree("/rest/areas", "admin", "areaId", "Area", "move-area", "area");
    private static final Tree CATEGORIES = new Tree("/rest/categories", "sysadmin", "categoryId", "Category",
            "move-category", "category");

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        // Last made, first removed
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void anAreaMoveUnderItselfOrUnderItsOwnDescendantIsRefusedAndChangesNothing() {
        aMoveUnderItselfOrItsOwnDescendantIsRefusedAndChangesNothing(AREAS);
    }

    @Test
    void aCategoryMoveUnderItselfOrUnderItsOwnDescendantIsRefusedAndChangesNothing() {
        aMoveUnderItselfOrItsOwnDescendantIsRefusedAndChangesNothing(CATEGORIES);
    }

    @Test
    void anAreaMoveElsewhereInTheTreeStillWorks() {
        aMoveElsewhereInTheTreeStillWorks(AREAS);
    }

    @Test
    void aCategoryMoveElsewhereInTheTreeStillWorks() {
        aMoveElsewhereInTheTreeStillWorks(CATEGORIES);
    }

    private void aMoveUnderItselfOrItsOwnDescendantIsRefusedAndChangesNothing(Tree tree) {
        String name = "IT-cycle " + unique();
        int a = create(tree, name + " A", null);
        int b = create(tree, name + " B", a);
        int c = create(tree, name + " C", b);
        List<List<Object>> before = parentsAndLineages(tree, a, b, c);
        assertEquals(List.of(List.of("null", "/" + a + "/"), List.of(String.valueOf(a), "/" + a + "/" + b + "/"),
                List.of(String.valueOf(b), "/" + a + "/" + b + "/" + c + "/")), before, "the tree as made");

        // Under its child, under a deeper descendant, under itself
        for (int parent : new int[] { b, c, a }) {
            move(tree, a, parent).statusCode(400);
        }
        // A sub-entity under itself, and under its own child
        move(tree, b, b).statusCode(400);
        move(tree, b, c).statusCode(400);

        assertEquals(before, parentsAndLineages(tree, a, b, c), "the parents and lineages after the refused moves");
        given().when().get(tree.base() + "/" + tree.body() + "/" + a + "?parent=true").then().statusCode(200);
        given().when().get(tree.base() + "/" + tree.body() + "/" + c + "?parent=true").then().statusCode(200);
        given().when().get(tree.base() + "/search/" + b).then().statusCode(200);
    }

    private void aMoveElsewhereInTheTreeStillWorks(Tree tree) {
        String name = "IT-cycle ok " + unique();
        int a = create(tree, name + " A", null);
        int b = create(tree, name + " B", a);
        int c = create(tree, name + " C", b);
        int d = create(tree, name + " D", null);

        // C up under its grandparent A, then A's sibling D under C, then C to the root
        assertEquals("true", move(tree, c, a).statusCode(200).extract().asString().trim());
        assertEquals("true", move(tree, d, c).statusCode(200).extract().asString().trim());
        assertEquals(List.of(List.of(String.valueOf(a), "/" + a + "/" + c + "/"),
                        List.of(String.valueOf(c), "/" + a + "/" + c + "/" + d + "/")),
                parentsAndLineages(tree, c, d), "C under A, and D under C");
        // B, which C left, stays under A
        assertEquals(List.of(List.of(String.valueOf(a), "/" + a + "/" + b + "/")), parentsAndLineages(tree, b));

        assertEquals("true", move(tree, c, null).statusCode(200).extract().asString().trim());
        assertEquals(List.of(List.of("null", "/" + c + "/"), List.of(String.valueOf(c), "/" + c + "/" + d + "/")),
                parentsAndLineages(tree, c, d), "C a root again, with D under it");
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    private ValidatableResponse move(Tree tree, int entityId, Integer parentId) {
        Map<String, Object> body = map(tree.idKey(), entityId);
        body.put("parentId", parentId);
        return putRaw(tree.role(), tree.base() + "/" + tree.moveLeaf(), body);
    }

    private int create(Tree tree, String name, Integer parentId) {
        Map<String, Object> body = map("active", true, "descs", list(map("lang", "en", "name", name)));
        if (tree == CATEGORIES) {
            body.put("type", "CATEGORY");
        }
        if (parentId != null) {
            body.put("parent", map("id", parentId));
        }
        String path = tree.base() + "/" + tree.body();
        int id = ((Number) post(tree.role(), path, body).get("id")).intValue();
        // A sub-entity may need two deletes: the first can leave it as a root (AREA1)
        cleanups.add(() -> {
            delete(tree.role(), path + "/" + id);
            delete(tree.role(), path + "/" + id);
        });
        return id;
    }

    /** Each entity's parent id (as text, "null" for a root) and lineage, read in SQL */
    private List<List<Object>> parentsAndLineages(Tree tree, int... ids) {
        List<List<Object>> result = new ArrayList<>();
        for (int id : ids) {
            String parent = strings("SELECT coalesce(cast(parent_id AS varchar), 'null') FROM public.\""
                    + tree.table() + "\" WHERE id = ?1", id).getFirst();
            String lineage = strings("SELECT lineage FROM public.\"" + tree.table() + "\" WHERE id = ?1", id).getFirst();
            result.add(List.of(parent, String.valueOf(lineage)));
        }
        return result;
    }
}
