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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save, reload, save again for the categories and the template parameter types, through the REST interface as a
 * system administrator (the role that manages them): a template category with three template parameters, three
 * script resource paths, editor and standard fields, two domains and descriptions in two languages; the category
 * tree's move and sort order; a list parameter type with its values and a composite one with its parameters; the
 * standard types refused.
 *
 * <p>Each list is created, read back over REST and compared, updated (an element added, one removed, the rest
 * reordered, a field of one changed), read back, saved again unchanged with its rows counted in SQL before and after,
 * and deleted, with the rows the delete leaves behind counted. Where a list has an order column its values are read
 * in SQL. This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class CategoryRoundTripITTest extends RoundTripSupport {

    private final List<Integer> categoryIds = new ArrayList<>();
    private final List<Integer> paramTypeIds = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        // Children before parents: the list holds them in creation order. Twice, as a sub-category is not deleted
        // by the first call but made a root (ReferenceDataRoundTripITTest, the sub-area and sub-category _documentsBug)
        for (int i = categoryIds.size() - 1; i >= 0; i--) {
            ReferenceDataRoundTripITTest.deleteTwice("sysadmin", "/rest/categories/category/" + categoryIds.get(i));
        }
        paramTypeIds.forEach(id -> delete("sysadmin", "/rest/templates/parameter-type/" + id));
        categoryIds.clear();
        paramTypeIds.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Template category
    // ------------------------------------------------------------------------------------------- //

    /** The rows a category holds, by its id */
    private static final Map<String, String> CATEGORY_ROWS = new LinkedHashMap<>();

    static {
        CATEGORY_ROWS.put("descs", "SELECT count(*) FROM public.\"CategoryDesc\" WHERE entity_id = ?1");
        CATEGORY_ROWS.put("template params", "SELECT count(*) FROM public.\"Category_TemplateParam\" WHERE \"Category_id\" = ?1");
        CATEGORY_ROWS.put("template param rows", "SELECT count(*) FROM public.\"TemplateParam\" t"
                + " JOIN public.\"Category_TemplateParam\" j ON j.\"templateParams_id\" = t.id WHERE j.\"Category_id\" = ?1");
        CATEGORY_ROWS.put("template param descs", "SELECT count(*) FROM public.\"TemplateParamDesc\" d"
                + " JOIN public.\"Category_TemplateParam\" j ON j.\"templateParams_id\" = d.entity_id WHERE j.\"Category_id\" = ?1");
        CATEGORY_ROWS.put("script paths", "SELECT count(*) FROM public.\"Category_scriptResourcePaths\" WHERE \"Category_id\" = ?1");
        CATEGORY_ROWS.put("editor fields", "SELECT count(*) FROM public.\"Category_editorFields\" WHERE \"Category_id\" = ?1");
        CATEGORY_ROWS.put("standard fields", "SELECT count(*) FROM public.\"Category_stdTemplateFields\" WHERE \"Category_id\" = ?1");
        CATEGORY_ROWS.put("domains", "SELECT count(*) FROM public.\"Category_Domain\" WHERE \"Category_id\" = ?1");
    }

    @Test
    void aTemplateCategoryKeepsEveryListThroughCreateUpdateAnUnchangedSaveAndDelete() {
        String name = "IT-RT category " + unique();
        long templateParamsBefore = count("SELECT count(*) FROM public.\"TemplateParam\"");
        long templateParamDescsBefore = count("SELECT count(*) FROM public.\"TemplateParamDesc\"");

        Map<String, Object> body = map(
                "type", "TEMPLATE",
                "mrn", "urn:mrn:iho:it-rt:" + unique(),
                "active", true,
                "descs", list(map("lang", "en", "name", name), map("lang", "da", "name", name + " da")),
                "editorFields", list("title", "areas", "charts"),
                "atonFilter", "aton.kv('seamark:type', 'light')",
                "domains", list(map("domainId", "niord-nw"), map("domainId", "niord-nm")),
                "stdTemplateFields", list("type", "areas"),
                "templateParams", list(
                        param("p1", "text", false, "Name", "Navn"),
                        param("p2", "number", true, "Depth", "Dybde"),
                        param("p3", "boolean", false, "Lit", "Tændt")),
                "scriptResourcePaths", list("templates/it-rt/a.ftl", "templates/it-rt/b.js", "templates/it-rt/c.ftl"),
                "messageId", "NW-001-26");
        Map<String, Object> created = post("sysadmin", "/rest/categories/category", body);
        int id = ((Number) created.get("id")).intValue();
        categoryIds.add(id);

        // Read back: everything that was sent
        Map<String, Object> read = category(id);
        assertEquals("TEMPLATE", read.get("type"));
        assertEquals(body.get("mrn"), read.get("mrn"));
        assertEquals(Map.of("en", name, "da", name + " da"), descs(read, "name"));
        assertEquals(Set.of("title", "areas", "charts"), new HashSet<>(valuesOf(read, "editorFields")));
        assertEquals(body.get("atonFilter"), read.get("atonFilter"));
        assertEquals(Set.of("niord-nw", "niord-nm"), new HashSet<>(fieldOf(read, "domains", "domainId")));
        assertEquals(Set.of("type", "areas"), new HashSet<>(valuesOf(read, "stdTemplateFields")));
        assertEquals(List.of(
                        shape("p1", "text", false, "Name", "Navn"),
                        shape("p2", "number", true, "Depth", "Dybde"),
                        shape("p3", "boolean", false, "Lit", "Tændt")),
                params(read), "the template parameters in order after the create");
        assertEquals(List.of("templates/it-rt/a.ftl", "templates/it-rt/b.js", "templates/it-rt/c.ftl"),
                valuesOf(read, "scriptResourcePaths"), "the script paths in order after the create");
        assertEquals("NW-001-26", read.get("messageId"));
        assertOrderColumns(id, 3, 3, "after the create");

        // The update the editor makes: p2 removed, p3 moved first, p4 added, p1 made mandatory and renamed in Danish;
        // a script path removed, the last moved first, one added; an editor field and a standard field removed; a
        // domain swapped; the Danish name and the example message changed
        Map<String, Object> edit = category(id);
        List<Map<String, Object>> params = listOf(edit, "templateParams");
        Map<String, Object> p1 = params.get(0);
        p1.put("mandatory", true);
        listOf(p1, "descs").stream().filter(d -> "da".equals(d.get("lang"))).forEach(d -> d.put("name", "Navnet"));
        edit.put("templateParams", list(params.get(2), p1, param("p4", "text", false, "Light", "Fyr")));
        edit.put("scriptResourcePaths", list("templates/it-rt/c.ftl", "templates/it-rt/a.ftl", "templates/it-rt/d.js"));
        edit.put("editorFields", list("charts", "title"));
        edit.put("stdTemplateFields", list("areas"));
        edit.put("domains", list(map("domainId", "niord-nm"), map("domainId", "niord-fa")));
        listOf(edit, "descs").stream().filter(d -> "da".equals(d.get("lang"))).forEach(d -> d.put("name", name + " ny"));
        edit.put("messageId", "NW-002-26");
        put("sysadmin", "/rest/categories/category/" + id, edit);

        Map<String, Object> updated = category(id);
        List<Map<String, Object>> expectedParams = List.of(
                shape("p3", "boolean", false, "Lit", "Tændt"),
                shape("p1", "text", true, "Name", "Navnet"),
                shape("p4", "text", false, "Light", "Fyr"));
        assertEquals(expectedParams, params(updated), "the template parameters in order after the update");
        assertEquals(List.of("templates/it-rt/c.ftl", "templates/it-rt/a.ftl", "templates/it-rt/d.js"),
                valuesOf(updated, "scriptResourcePaths"), "the script paths in order after the update");
        assertEquals(Set.of("charts", "title"), new HashSet<>(valuesOf(updated, "editorFields")));
        assertEquals(Set.of("areas"), new HashSet<>(valuesOf(updated, "stdTemplateFields")));
        assertEquals(Set.of("niord-nm", "niord-fa"), new HashSet<>(fieldOf(updated, "domains", "domainId")));
        assertEquals(Map.of("en", name, "da", name + " ny"), descs(updated, "name"));
        assertEquals("NW-002-26", updated.get("messageId"));
        assertOrderColumns(id, 3, 3, "after the update");

        // Saved once more as read: nothing changes, no row is added or lost
        Map<String, Long> rowsBefore = counts(CATEGORY_ROWS, id);
        put("sysadmin", "/rest/categories/category/" + id, category(id));
        assertEquals(rowsBefore, counts(CATEGORY_ROWS, id), "the rows of an unchanged save");
        Map<String, Object> resaved = category(id);
        assertEquals(expectedParams, params(resaved), "the template parameters after an unchanged save");
        assertEquals(valuesOf(updated, "scriptResourcePaths"), valuesOf(resaved, "scriptResourcePaths"));
        assertEquals(updated.get("siblingSortOrder"), resaved.get("siblingSortOrder"), "the sort order is kept");
        assertEquals(templateParamsBefore + 3, count("SELECT count(*) FROM public.\"TemplateParam\""),
                "the replaced template parameters leave no orphan rows");

        // Delete: nothing of the category is left
        delete("sysadmin", "/rest/categories/category/" + id).statusCode(200);
        categoryIds.remove((Integer) id);
        Map<String, Long> none = new LinkedHashMap<>();
        CATEGORY_ROWS.keySet().forEach(k -> none.put(k, 0L));
        assertEquals(none, counts(CATEGORY_ROWS, id), "the rows the delete leaves");
        assertEquals(templateParamsBefore, count("SELECT count(*) FROM public.\"TemplateParam\""),
                "the template parameters are deleted with the category");
        assertEquals(templateParamDescsBefore, count("SELECT count(*) FROM public.\"TemplateParamDesc\""),
                "the template parameters' descriptions are deleted with the category");
    }

    private void assertOrderColumns(int id, int params, int paths, String when) {
        assertOrderColumn(params, ints("SELECT \"indexNo\" FROM public.\"Category_TemplateParam\" WHERE \"Category_id\" = ?1", id),
                "the template parameters " + when);
        assertOrderColumn(paths, ints("SELECT \"indexNo\" FROM public.\"Category_scriptResourcePaths\" WHERE \"Category_id\" = ?1", id),
                "the script paths " + when);
    }

    // ------------------------------------------------------------------------------------------- //
    // The category tree
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aCategoryMovedToAnotherParentAndAmongSiblingsKeepsItsDataAndLineage() {
        String name = "IT-RT tree " + unique();
        int a = createCategory(name + " A", null, 0);
        int c = createCategory(name + " C", null, 0);
        int b = createCategory(name + " B", a, 20);
        int d = createCategory(name + " D", c, 10);

        assertEquals(a, parentId(b), "B is created under A");

        // Move B under C, where its sort order puts it after D
        as("sysadmin").body(map("categoryId", b, "parentId", c)).when().put("/rest/categories/move-category")
                .then().statusCode(200);
        Map<String, Object> moved = getMap("sysadmin", "/rest/categories/category/" + b + "?parent=true");
        assertEquals(c, ((Number) ((Map<?, ?>) moved.get("parent")).get("id")).intValue(), "B's parent is C");
        assertEquals(Map.of("en", name + " B", "da", name + " B da"), descs(moved, "name"), "B keeps its names");
        assertEquals("/" + c + "/" + b + "/", strings("SELECT lineage FROM public.\"Category\" WHERE id = ?1", b).getFirst(),
                "B's lineage follows the move");
        assertEquals(List.of(), ints("SELECT id FROM public.\"Category\" WHERE parent_id = ?1", a), "A has no child left");

        // Among C's children, move B up before D
        List<Integer> before = children(c);
        assertEquals(List.of(d, b), before, "B is moved in after D");
        as("sysadmin").body(map("categoryId", b, "moveUp", true)).when().put("/rest/categories/change-sort-order")
                .then().statusCode(200);
        List<Integer> after = children(c);
        assertEquals(Set.copyOf(before), Set.copyOf(after), "the move among siblings keeps every child");
        assertEquals(List.of(b, d), after, "B is moved before D");

        // Saving B as read keeps its parent and its place among the siblings
        put("sysadmin", "/rest/categories/category/" + b, category(b));
        assertEquals(c, parentId(b), "B's parent after an unchanged save");
        assertEquals(List.of(b, d), children(c), "the siblings' order after an unchanged save");
    }

    /** A category with distinct sort orders among its siblings: equal ones are CAT3 (be/docs/potential-errors.md) */
    private int createCategory(String name, Integer parentId, double siblingSortOrder) {
        Map<String, Object> body = map(
                "type", "CATEGORY", "active", true, "siblingSortOrder", siblingSortOrder,
                "descs", list(map("lang", "en", "name", name), map("lang", "da", "name", name + " da")));
        if (parentId != null) {
            body.put("parent", map("id", parentId));
        }
        int id = ((Number) post("sysadmin", "/rest/categories/category", body).get("id")).intValue();
        categoryIds.add(id);
        return id;
    }

    private Integer parentId(int id) {
        Object parent = column("SELECT parent_id FROM public.\"Category\" WHERE id = ?1", id).getFirst();
        return parent == null ? null : ((Number) parent).intValue();
    }

    private List<Integer> children(int parentId) {
        return ints("SELECT id FROM public.\"Category\" WHERE parent_id = ?1 ORDER BY \"siblingSortOrder\", id", parentId);
    }

    // ------------------------------------------------------------------------------------------- //
    // Parameter types
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aCompositeParameterTypeKeepsItsParametersInOrder() {
        String name = "IT-RT composite " + unique();
        long templateParamsBefore = count("SELECT count(*) FROM public.\"TemplateParam\"");
        Map<String, Object> created = post("sysadmin", "/rest/templates/parameter-type", map(
                "type", "COMPOSITE", "name", name,
                "templateParams", list(
                        param("lat", "number", true, "Latitude", "Bredde"),
                        param("lon", "number", true, "Longitude", "Længde"),
                        param("label", "text", false, "Label", "Etiket"))));
        int id = ((Number) created.get("id")).intValue();
        paramTypeIds.add(id);

        Map<String, Object> read = paramType(id);
        assertEquals(name, read.get("name"));
        assertEquals("COMPOSITE", read.get("type"));
        assertEquals(List.of(
                        shape("lat", "number", true, "Latitude", "Bredde"),
                        shape("lon", "number", true, "Longitude", "Længde"),
                        shape("label", "text", false, "Label", "Etiket")),
                params(read), "the parameters in order after the create");
        assertOrderColumn(3, compositeIndexes(id), "the composite's parameters after the create");

        // Update: lon removed, label moved first, depth added, lat made optional; the type renamed
        Map<String, Object> edit = paramType(id);
        List<Map<String, Object>> params = listOf(edit, "templateParams");
        params.get(0).put("mandatory", false);
        edit.put("templateParams", list(params.get(2), params.get(0), param("depth", "number", false, "Depth", "Dybde")));
        edit.put("name", name + " renamed");
        put("sysadmin", "/rest/templates/parameter-type/" + id, edit);

        List<Map<String, Object>> expected = List.of(
                shape("label", "text", false, "Label", "Etiket"),
                shape("lat", "number", false, "Latitude", "Bredde"),
                shape("depth", "number", false, "Depth", "Dybde"));
        Map<String, Object> updated = paramType(id);
        assertEquals(name + " renamed", updated.get("name"));
        assertEquals(expected, params(updated), "the parameters in order after the update");
        assertOrderColumn(3, compositeIndexes(id), "the composite's parameters after the update");

        // Unchanged save
        long joinRows = count("SELECT count(*) FROM public.\"ParamType_TemplateParam\" WHERE \"CompositeParamType_id\" = ?1", id);
        put("sysadmin", "/rest/templates/parameter-type/" + id, paramType(id));
        assertEquals(expected, params(paramType(id)), "the parameters after an unchanged save");
        assertEquals(joinRows, count("SELECT count(*) FROM public.\"ParamType_TemplateParam\" WHERE \"CompositeParamType_id\" = ?1", id));
        assertEquals(templateParamsBefore + 3, count("SELECT count(*) FROM public.\"TemplateParam\""),
                "the replaced parameters leave no orphan rows");

        // Delete
        delete("sysadmin", "/rest/templates/parameter-type/" + id).statusCode(204);
        paramTypeIds.remove((Integer) id);
        assertEquals(0L, count("SELECT count(*) FROM public.\"ParamType_TemplateParam\" WHERE \"CompositeParamType_id\" = ?1", id));
        assertEquals(templateParamsBefore, count("SELECT count(*) FROM public.\"TemplateParam\""),
                "the parameters are deleted with the type");
    }

    @Test
    void aListParameterTypeKeepsItsValuesInOrderAndTheDictionaryEntriesOutliveIt() {
        // Four dictionary entries of the seed whose keys are unique
        List<List<Object>> entries = rows("SELECT min(id), dict_key FROM public.\"DictionaryEntry\" GROUP BY dict_key"
                + " HAVING count(*) = 1 ORDER BY 1 LIMIT 4");
        assertEquals(4, entries.size(), "the seed holds dictionary entries to list");
        List<Integer> ids = entries.stream().map(r -> ((Number) r.get(0)).intValue()).toList();
        List<String> keys = entries.stream().map(r -> (String) r.get(1)).toList();
        long entriesBefore = count("SELECT count(*) FROM public.\"DictionaryEntry\"");

        String name = "IT-RT list " + unique();
        Map<String, Object> created = post("sysadmin", "/rest/templates/parameter-type", map(
                "type", "LIST", "name", name,
                "values", list(map("id", ids.get(0), "key", keys.get(0)), map("id", ids.get(1), "key", keys.get(1)),
                        map("id", ids.get(2), "key", keys.get(2)))));
        int id = ((Number) created.get("id")).intValue();
        paramTypeIds.add(id);

        assertEquals(List.of(keys.get(0), keys.get(1), keys.get(2)), fieldOf(paramType(id), "values", "key"),
                "the values in order after the create");
        assertOrderColumn(3, listIndexes(id), "the list's values after the create");

        // Update: the second removed, the third moved first, a fourth added (by key only, as an import gives it)
        Map<String, Object> edit = paramType(id);
        List<Map<String, Object>> values = listOf(edit, "values");
        edit.put("values", list(values.get(2), values.get(0), map("key", keys.get(3))));
        put("sysadmin", "/rest/templates/parameter-type/" + id, edit);
        List<Object> expected = List.of(keys.get(2), keys.get(0), keys.get(3));
        assertEquals(expected, fieldOf(paramType(id), "values", "key"), "the values in order after the update");
        assertOrderColumn(3, listIndexes(id), "the list's values after the update");

        // Unchanged save
        put("sysadmin", "/rest/templates/parameter-type/" + id, paramType(id));
        assertEquals(expected, fieldOf(paramType(id), "values", "key"), "the values after an unchanged save");
        assertOrderColumn(3, listIndexes(id), "the list's values after an unchanged save");

        // Delete: the join rows go, the dictionary entries stay
        delete("sysadmin", "/rest/templates/parameter-type/" + id).statusCode(204);
        paramTypeIds.remove((Integer) id);
        assertEquals(0L, count("SELECT count(*) FROM public.\"ParamType_DictionaryEntry\" WHERE \"ListParamType_id\" = ?1", id));
        assertEquals(entriesBefore, count("SELECT count(*) FROM public.\"DictionaryEntry\""),
                "the dictionary entries outlive the list type");
    }

    @Test
    void theStandardParameterTypesAreListedWithoutAnIdAndCannotBeWritten() {
        List<Map<String, Object>> types = getList("sysadmin", "/rest/templates/parameter-types");
        List<Map<String, Object>> standard = types.stream().filter(t -> "STANDARD".equals(t.get("type"))).toList();
        assertTrue(standard.stream().map(t -> t.get("name")).toList().containsAll(List.of("text", "number", "boolean", "date")),
                "the four standard types are listed: " + standard);
        assertTrue(standard.stream().allMatch(t -> t.get("id") == null), "a standard type has no id");

        postRaw("sysadmin", "/rest/templates/parameter-type", map("type", "STANDARD", "name", "IT-RT standard"))
                .statusCode(400);
        postRaw("sysadmin", "/rest/templates/parameter-type", map("type", "LIST", "name", "text", "values", list()))
                .statusCode(400);
    }

    private List<Integer> compositeIndexes(int id) {
        return ints("SELECT \"indexNo\" FROM public.\"ParamType_TemplateParam\" WHERE \"CompositeParamType_id\" = ?1", id);
    }

    private List<Integer> listIndexes(int id) {
        return ints("SELECT \"indexNo\" FROM public.\"ParamType_DictionaryEntry\" WHERE \"ListParamType_id\" = ?1", id);
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    private static Map<String, Object> category(int id) {
        return getMap("sysadmin", "/rest/categories/category/" + id);
    }

    private static Map<String, Object> paramType(int id) {
        return getMap("sysadmin", "/rest/templates/parameter-type/" + id);
    }

    private static Map<String, Object> param(String paramId, String type, boolean mandatory, String en, String da) {
        return map("paramId", paramId, "type", type, "mandatory", mandatory, "positionList", false,
                "descs", list(map("lang", "en", "name", en), map("lang", "da", "name", da)));
    }

    /** A template parameter as compared: its fields and its names by language */
    private static Map<String, Object> shape(String paramId, String type, boolean mandatory, String en, String da) {
        return Map.of("paramId", paramId, "type", type, "mandatory", mandatory, "positionList", false,
                "names", Map.of("en", en, "da", da));
    }

    private static List<Map<String, Object>> params(Map<String, Object> owner) {
        return listOf(owner, "templateParams").stream()
                .map(p -> Map.<String, Object>of("paramId", p.get("paramId"), "type", p.get("type"),
                        "mandatory", p.get("mandatory"), "positionList", p.get("positionList"),
                        "names", descs(p, "name")))
                .toList();
    }
}
