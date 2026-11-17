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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Export, import, export again for the message categories with their templates ({@code GET /rest/categories/export},
 * {@code POST /rest/categories/upload-categories}, job {@code category-import}) and the parameter types
 * ({@code GET /rest/templates/parameter-types/export}, {@code POST /rest/templates/upload-param-types}, job
 * {@code param-type-import}), as the system administrator.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class CategoryImportRoundTripITTest extends ImportRoundTripSupport {

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                cleanups.get(i).run();
            } catch (RuntimeException | AssertionError ignored) {
                // best effort
            }
        }
        cleanups.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Categories
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aCategoryTreeWithTemplatesExportedDeletedAndImportedComesBackWhole() {
        String u = unique();
        String mrn = "urn:mrn:iho:it-imp-cat:" + u;
        tree(u, mrn);
        Map<String, Object> first = exportedCategory(mrn);
        assertEquals(List.of("IT-imp T2 " + u, awkward("IT-imp T1 " + u)), childNames(first),
                "the children in their sort order");

        // (a)
        delete("sysadmin", "/rest/categories/category/" + rootId(mrn)).statusCode(200);
        assertEquals(0L, count("SELECT count(*) FROM public.\"Category\" WHERE mrn = ?1", mrn));
        JobEnd job = importRows("sysadmin", "/rest/categories/upload-categories", "category-import", List.of(first));
        assertEquals("COMPLETED", job.status(), job.log());
        Map<String, Object> second = exportedCategory(mrn);
        assertEquals(without(first, List.of()), without(second, List.of()), "the tree after the import");

        // (b) The same export again: no row added or lost, and the export the same
        List<Integer> ids = treeIds(mrn);
        long params = count("SELECT count(*) FROM public.\"TemplateParam\"");
        job = importRows("sysadmin", "/rest/categories/upload-categories", "category-import", List.of(first));
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(ids, treeIds(mrn), "the same category rows");
        assertEquals(params, count("SELECT count(*) FROM public.\"TemplateParam\""), "no template parameter added or lost");
        assertEquals(second, exportedCategory(mrn), "an import of the same export changes nothing");
    }

    /**
     * CATEGORY1 (recorded): every category the import finds "has changed", since {@code Category.hasChanged}
     * compares the loaded editor fields, a {@code PersistentBag}, with {@code Objects.equals}. The same export
     * imported over its own tree therefore rewrites every template's parameters: the rows are deleted and inserted
     * again with new ids. Pinned here over REST; the right assertion is the same ids.
     */
    @Test
    void anUnchangedImportRewritesEveryTemplateParameter_documentsBug() {
        String u = unique();
        String mrn = "urn:mrn:iho:it-imp-cat:" + u;
        tree(u, mrn);
        Map<String, Object> first = exportedCategory(mrn);
        List<Integer> before = paramIds(mrn);
        assertEquals(5, before.size(), "the tree's template parameters");

        JobEnd job = importRows("sysadmin", "/rest/categories/upload-categories", "category-import", List.of(first));
        assertEquals("COMPLETED", job.status(), job.log());
        List<Integer> after = paramIds(mrn);
        assertEquals(5, after.size(), "as many parameters");
        // FAULT (CATEGORY1): new rows. RIGHT: assertEquals(before, after)
        assertTrue(before.stream().noneMatch(after::contains), "every parameter row was replaced: " + before + " " + after);
    }

    @Test
    void aCategoryImportOverTheTreeUpdatesAddsAndDeletesNothing() {
        String u = unique();
        String mrn = "urn:mrn:iho:it-imp-cat:" + u;
        tree(u, mrn);
        Map<String, Object> first = exportedCategory(mrn);

        // T1 (the second child): one parameter renamed, one removed, one added; the message id changed.
        // T2 (the first child): its sub-category left out, a new one added
        Map<String, Object> changed = copy(first);
        Map<String, Object> t1 = listOf(changed, "children").get(1);
        List<Map<String, Object>> p = listOf(t1, "templateParams");
        listOf(p.get(0), "descs").stream().filter(d -> "da".equals(d.get("lang"))).forEach(d -> d.put("name", "Nyt navn"));
        t1.put("templateParams", list(p.get(0), p.get(2), param("p4", "text", false, "Added", "Tilføjet")));
        t1.put("messageId", "NW-002-26");
        // T2b sorts after T2a (0.0): siblings that share a sort order come back in the order PostgreSQL picks (CAT3),
        // and with T2b at 0.0 too this test failed when it ran after the other category classes (the reverse-order
        // run of 2026-11-12: [T2b, T2a])
        Map<String, Object> t2 = listOf(changed, "children").get(0);
        t2.put("children", list(map("type", "CATEGORY", "active", true, "siblingSortOrder", 1.0,
                "descs", list(map("lang", "en", "name", "IT-imp T2b " + u)))));

        JobEnd job = importRows("sysadmin", "/rest/categories/upload-categories", "category-import", List.of(changed));
        assertEquals("COMPLETED", job.status(), job.log());

        Map<String, Object> after = exportedCategory(mrn);
        Map<String, Object> t1After = listOf(after, "children").get(1);
        assertEquals(List.of("p1", "p3", "p4"), fieldOf(t1After, "templateParams", "paramId"), "the parameters, in order");
        assertEquals("Nyt navn", descs(listOf(t1After, "templateParams").get(0), "name").get("da"));
        assertEquals("NW-002-26", t1After.get("messageId"));
        assertEquals(List.of("IT-imp T2a " + u, "IT-imp T2b " + u), listOf(listOf(after, "children").get(0), "children")
                .stream().map(c -> descs(c, "name").get("en")).toList(),
                "the sub-category the file leaves out is kept, the new one added");
    }

    /**
     * CATEGORY3: a sub-category created with domains ({@code POST /rest/categories/category} with a parent and
     * domains, as the Categories page's "add" under a selected category sends it) is stored with them. It answered
     * 500 and stored nothing: {@code CategoryService.createCategory} added the new category to its parent's children
     * (cascade ALL) before it looked its domains up, and the lookup query's auto-flush met the request's transient
     * {@code Domain} objects. The domains are now looked up first. The same body without a parent is created too.
     */
    @Test
    void aSubCategoryCreatedWithDomainsIsStoredWithThem() {
        String u = unique();
        int root = category(map("type", "CATEGORY"), null, "IT-imp parent " + u, 0.0);
        cleanups.add(() -> delete("sysadmin", "/rest/categories/category/" + root));
        Map<String, Object> body = map("type", "TEMPLATE", "active", true, "parent", map("id", root),
                "domains", list(map("domainId", "niord-nw"), map("domainId", "niord-nm")),
                "descs", list(map("lang", "en", "name", "IT-imp child " + u)));
        int status = postRaw("sysadmin", "/rest/categories/category", body).extract().statusCode();
        long stored = count("SELECT count(*) FROM public.\"CategoryDesc\" WHERE name = ?1", "IT-imp child " + u);
        assertEquals(List.of(200, 1L), List.of(status, stored), "the answer and the rows stored");
        assertEquals(List.of("niord-nm", "niord-nw"), strings("SELECT d.\"domainId\" FROM public.\"Category_Domain\" j"
                + " JOIN public.\"Domain\" d ON d.id = j.domains_id JOIN public.\"CategoryDesc\" c"
                + " ON c.entity_id = j.\"Category_id\" WHERE c.name = ?1 ORDER BY 1", "IT-imp child " + u),
                "the sub-category's domains");
        assertEquals(List.of(root), ints("SELECT c.parent_id FROM public.\"Category\" c JOIN public.\"CategoryDesc\" d"
                + " ON d.entity_id = c.id WHERE d.name = ?1", "IT-imp child " + u), "the sub-category's parent");

        // The same body without the parent is created
        body.remove("parent");
        int id = ((Number) post("sysadmin", "/rest/categories/category", body).get("id")).intValue();
        cleanups.add(() -> delete("sysadmin", "/rest/categories/category/" + id));
        assertEquals(2, listOf(getMap("sysadmin", "/rest/categories/category/" + id), "domains").size());
    }

    /** Root R; under it T2 (sort 5, with a sub-category T2a) before T1 (sort 10, a template with everything) */
    private void tree(String u, String mrn) {
        int root = category(map("type", "CATEGORY", "mrn", mrn, "atonFilter", "aton.kv('seamark:type', 'light.*')"),
                null, awkward("IT-imp root " + u), 0.0);
        cleanups.add(() -> {
            delete("sysadmin", "/rest/categories/category/" + rootId(mrn));
            assertEquals(0L, count("SELECT count(*) FROM public.\"Category\" WHERE mrn = ?1", mrn));
        });
        // Created without its domains and given them by an update (until CATEGORY3 was fixed, a create under a parent
        // with domains failed; the update path is kept, it is a path of its own)
        int t1 = category(map("type", "TEMPLATE",
                "editorFields", list("title", "areas", "charts"),
                "stdTemplateFields", list("type", "areas"),
                "templateParams", list(param("p1", "text", false, "Name", "Navn"),
                        param("p2", "number", true, "Depth", "Dybde"), param("p3", "boolean", false, "Lit", "Tændt")),
                "scriptResourcePaths", list("templates/it-imp/a.ftl", "templates/it-imp/b.js", "templates/it-imp/c.ftl"),
                "messageId", "NW-001-26"), root, awkward("IT-imp T1 " + u), 10.0);
        Map<String, Object> withDomains = getMap("sysadmin", "/rest/categories/category/" + t1);
        withDomains.put("domains", list(map("domainId", "niord-nw"), map("domainId", "niord-nm")));
        put("sysadmin", "/rest/categories/category/" + t1, withDomains);
        int t2 = category(map("type", "TEMPLATE", "templateParams", list(param("q1", "text", true, "Q", "Q da"),
                param("q2", "date", false, "When", "Hvornår"))), root, "IT-imp T2 " + u, 5.0);
        category(map("type", "CATEGORY"), t2, "IT-imp T2a " + u, 0.0);
    }

    private int category(Map<String, Object> fields, Integer parentId, String name, double sortOrder) {
        Map<String, Object> body = map("active", true, "siblingSortOrder", sortOrder,
                "descs", list(map("lang", "en", "name", name), map("lang", "da", "name", name + " da")));
        body.putAll(fields);
        if (parentId != null) {
            body.put("parent", map("id", parentId));
        }
        return ((Number) post("sysadmin", "/rest/categories/category", body).get("id")).intValue();
    }

    private static Map<String, Object> param(String paramId, String type, boolean mandatory, String en, String da) {
        return map("paramId", paramId, "type", type, "mandatory", mandatory, "positionList", false,
                "descs", list(map("lang", "en", "name", en), map("lang", "da", "name", da)));
    }

    private static Map<String, Object> exportedCategory(String mrn) {
        List<Map<String, Object>> found = getList("sysadmin", "/rest/categories/export").stream()
                .filter(r -> mrn.equals(r.get("mrn"))).toList();
        assertEquals(1, found.size(), "one root category of " + mrn);
        return domainsSorted(found.getFirst());
    }

    /**
     * The category and its children with each one's domains sorted by id: a category keeps no order of its domains
     * ({@code Category.domains} has no order column), so two exports of the same rows may list them differently once
     * an import has rewritten them (seen in the reverse class order after the endpoint sweep, 2026-11-14)
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> domainsSorted(Map<String, Object> category) {
        Map<String, Object> sorted = new LinkedHashMap<>(category);
        if (sorted.get("domains") instanceof List<?> domains) {
            sorted.put("domains", domains.stream().map(d -> (Map<String, Object>) d)
                    .sorted(Comparator.comparing(d -> String.valueOf(d.get("domainId")))).toList());
        }
        if (sorted.get("children") instanceof List<?> children) {
            sorted.put("children", children.stream().map(c -> domainsSorted((Map<String, Object>) c)).toList());
        }
        return sorted;
    }

    private static List<Object> childNames(Map<String, Object> category) {
        return listOf(category, "children").stream().map(c -> descs(c, "name").get("en")).toList();
    }

    private int rootId(String mrn) {
        return ints("SELECT id FROM public.\"Category\" WHERE mrn = ?1", mrn).getFirst();
    }

    private List<Integer> treeIds(String mrn) {
        return ints("SELECT c.id FROM public.\"Category\" c, public.\"Category\" r WHERE r.mrn = ?1"
                + " AND c.lineage LIKE r.lineage || '%' ORDER BY c.id", mrn);
    }

    private List<Integer> paramIds(String mrn) {
        return ints("SELECT j.\"templateParams_id\" FROM public.\"Category_TemplateParam\" j, public.\"Category\" c,"
                + " public.\"Category\" r WHERE r.mrn = ?1 AND c.lineage LIKE r.lineage || '%' AND j.\"Category_id\" = c.id"
                + " ORDER BY 1", mrn);
    }

    // ------------------------------------------------------------------------------------------- //
    // Parameter types
    // ------------------------------------------------------------------------------------------- //

    @Test
    void parameterTypesExportedDeletedAndImportedComeBackAndAnImportOverThemReplacesTheirLists() {
        List<String> keys = strings("SELECT dict_key FROM public.\"DictionaryEntry\" GROUP BY dict_key"
                + " HAVING count(*) = 1 ORDER BY 1 LIMIT 4");
        assertEquals(4, keys.size(), "the seed holds dictionary entries to list");
        String u = unique();
        String listName = "IT-imp list " + u;
        String compositeName = awkward("IT-imp composite " + u);
        post("sysadmin", "/rest/templates/parameter-type", map("type", "LIST", "name", listName,
                "values", list(map("key", keys.get(2)), map("key", keys.get(0)), map("key", keys.get(1)))));
        cleanups.add(() -> deleteParamType(listName));
        post("sysadmin", "/rest/templates/parameter-type", map("type", "COMPOSITE", "name", compositeName,
                "templateParams", list(param("c1", listName, true, "Kind", "Art"), param("c2", "number", false, "Depth", "Dybde"),
                        param("c3", "text", false, "Name", "Navn"))));
        cleanups.add(() -> deleteParamType(compositeName));

        List<Map<String, Object>> first = exportedTypes(u);
        assertEquals(2, first.size(), "the two types: " + first);
        assertEquals(List.of(keys.get(2), keys.get(0), keys.get(1)), fieldOf(byName(first, listName), "values", "key"));

        // (a)
        deleteParamType(compositeName);
        deleteParamType(listName);
        assertEquals(List.of(), exportedTypes(u));
        JobEnd job = importRows("sysadmin", "/rest/templates/upload-param-types", "param-type-import", first);
        assertEquals("COMPLETED", job.status(), job.log());
        List<Map<String, Object>> second = exportedTypes(u);
        assertEquals(without(first, List.of("id")), without(second, List.of("id")), "the types after the import");

        // (b)
        List<Integer> ids = ints("SELECT id FROM public.\"ParamType\" WHERE name IN (?1, ?2) ORDER BY id", listName, compositeName);
        job = importRows("sysadmin", "/rest/templates/upload-param-types", "param-type-import", second);
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(ids, ints("SELECT id FROM public.\"ParamType\" WHERE name IN (?1, ?2) ORDER BY id", listName, compositeName));
        assertEquals(second, exportedTypes(u), "an import of the same export changes nothing");

        // (c) The list: a value removed, one moved, one added; the composite: a parameter removed, one added
        List<Map<String, Object>> changed = second.stream().map(ImportRoundTripSupport::copy).toList();
        Map<String, Object> list = byName(changed, listName);
        List<Map<String, Object>> values = listOf(list, "values");
        list.put("values", list(values.get(1), values.get(0), map("key", keys.get(3))));
        Map<String, Object> composite = byName(changed, compositeName);
        List<Map<String, Object>> params = listOf(composite, "templateParams");
        composite.put("templateParams", list(params.get(2), params.get(0), param("c4", "boolean", false, "Lit", "Tændt")));
        job = importRows("sysadmin", "/rest/templates/upload-param-types", "param-type-import", changed);
        assertEquals("COMPLETED", job.status(), job.log());

        // (d)
        List<Map<String, Object>> third = exportedTypes(u);
        assertEquals(List.of(keys.get(0), keys.get(2), keys.get(3)), fieldOf(byName(third, listName), "values", "key"),
                "the list's values are the file's, in its order");
        assertEquals(List.of("c3", "c1", "c4"), fieldOf(byName(third, compositeName), "templateParams", "paramId"),
                "the composite's parameters are the file's, in its order");
    }

    private static List<Map<String, Object>> exportedTypes(String u) {
        return getList("sysadmin", "/rest/templates/parameter-types/export").stream()
                .filter(t -> String.valueOf(t.get("name")).contains(u))
                .sorted((x, y) -> String.valueOf(x.get("name")).compareTo(String.valueOf(y.get("name"))))
                .toList();
    }

    private static Map<String, Object> byName(List<Map<String, Object>> types, String name) {
        return types.stream().filter(t -> name.equals(t.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("no type " + name + " in " + types));
    }

    private void deleteParamType(String name) {
        for (Integer id : ints("SELECT id FROM public.\"ParamType\" WHERE name = ?1", name)) {
            delete("sysadmin", "/rest/templates/parameter-type/" + id);
        }
    }
}
