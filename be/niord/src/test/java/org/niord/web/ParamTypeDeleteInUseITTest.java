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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code DELETE /rest/templates/parameter-type/{id}} of a type that a template
 * category or another composite type names answers 409 with a detail that names them, and the type stays. The uses
 * are by name, with no foreign key, so the delete used to answer 204 and leave the template form without the type's
 * fields. An unknown id answers 404 (it answered 204); an unused type is deleted with 204.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class ParamTypeDeleteInUseITTest extends RoundTripSupport {

    private static final String PARAM_TYPE = "/rest/templates/parameter-type/";
    private static final String CATEGORY = "/rest/categories/category/";

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        // Last made, first removed: the users before the types they name
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                cleanups.get(i).run();
            } catch (RuntimeException | AssertionError ignored) {
                // best effort
            }
        }
        cleanups.clear();
    }

    @Test
    void aTypeInUseIsRefusedWith409NamingItsUsersAndStays() {
        String u = unique();
        String listName = "IT-del list " + u;
        String compositeName = "IT-del composite " + u;
        String categoryName = "IT-del category " + u;
        int listId = createParamType(map("type", "LIST", "name", listName, "values", list()));
        int compositeId = createParamType(map("type", "COMPOSITE", "name", compositeName,
                "templateParams", list(param("kind", listName), param("depth", "number"))));
        int categoryId = createTemplateCategory(categoryName, param("kind", listName), param("reduced", compositeName));

        delete("sysadmin", PARAM_TYPE + listId).statusCode(409).body("detail", equalTo(
                "The parameter type '" + listName + "' is used by the template category '" + categoryName
                        + "' and the composite type '" + compositeName + "'; remove it from them first"));
        delete("sysadmin", PARAM_TYPE + compositeId).statusCode(409).body("detail", equalTo(
                "The parameter type '" + compositeName + "' is used by the template category '" + categoryName
                        + "'; remove it from them first"));

        assertEquals(List.of(listName, compositeName),
                strings("SELECT name FROM public.\"ParamType\" WHERE id IN (?1, ?2) ORDER BY id", listId, compositeId),
                "both types stay");
        assertEquals(List.of(categoryId), ints("SELECT id FROM public.\"Category\" WHERE id = ?1", categoryId),
                "the category stays");
        List<Object> listed = getList("sysadmin", "/rest/templates/parameter-types").stream()
                .map(t -> t.get("name")).filter(n -> String.valueOf(n).contains(u)).toList();
        assertEquals(2, listed.size(), "both types are still listed: " + listed);
    }

    @Test
    void anUnknownIdAnswers404() {
        delete("sysadmin", PARAM_TYPE + 999_999_999).statusCode(404);
    }

    @Test
    void aTypeNoLongerUsedIsDeletedWith204() {
        String u = unique();
        String listName = "IT-del free list " + u;
        String compositeName = "IT-del free composite " + u;
        int listId = createParamType(map("type", "LIST", "name", listName, "values", list()));
        int compositeId = createParamType(map("type", "COMPOSITE", "name", compositeName,
                "templateParams", list(param("kind", listName))));
        int categoryId = createTemplateCategory("IT-del free category " + u, param("reduced", compositeName));

        // The category goes, then the composite, then the list: each is free once the one naming it is gone
        delete("sysadmin", CATEGORY + categoryId).statusCode(200);
        delete("sysadmin", PARAM_TYPE + compositeId).statusCode(204);
        delete("sysadmin", PARAM_TYPE + listId).statusCode(204);
        assertEquals(0L, count("SELECT count(*) FROM public.\"ParamType\" WHERE id IN (?1, ?2)", listId, compositeId),
                "both types are deleted");
    }

    private int createParamType(Map<String, Object> body) {
        int id = ((Number) post("sysadmin", PARAM_TYPE, body).get("id")).intValue();
        cleanups.add(() -> delete("sysadmin", PARAM_TYPE + id));
        return id;
    }

    @SafeVarargs
    private int createTemplateCategory(String name, Map<String, Object>... params) {
        int id = ((Number) post("sysadmin", "/rest/categories/category", map("type", "TEMPLATE", "active", true,
                "descs", list(map("lang", "en", "name", name)), "templateParams", List.of(params))).get("id")).intValue();
        cleanups.add(() -> delete("sysadmin", CATEGORY + id));
        return id;
    }

    private static Map<String, Object> param(String paramId, String type) {
        return map("paramId", paramId, "type", type, "mandatory", false, "positionList", false,
                "descs", list(map("lang", "en", "name", paramId)));
    }
}
