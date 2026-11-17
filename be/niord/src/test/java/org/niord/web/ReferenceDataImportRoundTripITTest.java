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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Export, import, export again for the reference data an administrator imports on the admin pages: the area tree
 * ({@code GET /rest/areas/all}, {@code POST /rest/areas/upload-areas}, job {@code area-import}), charts
 * ({@code GET /rest/charts/all}, {@code /upload-charts}, {@code chart-import}) and sources ({@code GET
 * /rest/sources/all}, {@code /upload-sources}, {@code source-import}), each as the administrator.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
@SuppressWarnings("unchecked")
class ReferenceDataImportRoundTripITTest extends ImportRoundTripSupport {

    /** What an import does not carry over: the row's id (a new row gets a new one) */
    private static final Set<String> ROW_FIELDS = Set.of("id");

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
    // Areas
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anAreaTreeExportedDeletedAndImportedComesBackWhole() {
        String u = unique();
        String mrn = "urn:mrn:iho:it-imp:" + u;
        int root = area(map("mrn", mrn, "type", "COUNTRY", "messageSorting", "CW", "originLatitude", 55.5,
                "originLongitude", 11.25, "originAngle", 30, "editorFields", list("title", "charts"),
                "geometry", square(10.0, 55.0)), null, awkward("IT-imp root " + u), 5.0);
        // A is created before B but sorts after it: the order of the rows' ids is not the sort order, so an export
        // that lists the children by id (or by name) shows
        int a = area(map(), root, "IT-imp A " + u, 20.0);
        area(map("type", "FIRING_AREA"), root, "IT-imp B " + u, 10.0);
        area(map("geometry", square(10.2, 55.2)), a, awkward("IT-imp A1 " + u), 0.0);
        cleanups.add(() -> deleteRootByMrn(mrn));

        Map<String, Object> first = exportedArea(mrn);
        assertEquals(List.of("IT-imp B " + u, "IT-imp A " + u), childNames(first),
                "the export lists the children in their sort order, not by name");

        // (a) Deleted, then imported from the export
        delete("admin", "/rest/areas/area/" + root).statusCode(200);
        assertEquals(0L, count("SELECT count(*) FROM public.\"Area\" WHERE mrn = ?1", mrn));
        JobEnd job = importRows("admin", "/rest/areas/upload-areas", "area-import", List.of(first));
        assertEquals("COMPLETED", job.status(), job.log());
        Map<String, Object> second = exportedArea(mrn);
        assertEquals(byLang(without(first, ROW_FIELDS)), byLang(without(second, ROW_FIELDS)),
                "the tree after the import");

        // (b) The same file again over the rows: nothing added, lost or changed
        List<Integer> ids = treeIds(mrn);
        job = importRows("admin", "/rest/areas/upload-areas", "area-import", List.of(first));
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(ids, treeIds(mrn), "the same rows, no row added or lost");
        assertEquals(second, exportedArea(mrn), "an import of the same export changes nothing");
    }

    /**
     * An import over the tree with the root's message sorting, origin and editor fields changed, a sub-area's Danish
     * name changed, a sub-sub-area added and one left out. The admin manual: "A matched area is updated, and any
     * other area is added"; nothing promises a delete. The sub-area's name is updated, the new area added and the
     * left-out one kept, and the root, whose other fields are unchanged, takes the file's message sorting, origin and
     * editor fields: {@code AreaService.checkUpdateAreaData} updates only when {@code Area.hasChanged} says so, and
     * {@code hasChanged} compares those fields too (AREA2).
     */
    @Test
    void anAreaImportUpdatesMessageSortingOriginAndEditorFields() {
        String u = unique();
        String mrn = "urn:mrn:iho:it-imp:" + u;
        int root = area(map("mrn", mrn, "messageSorting", "CW", "originLatitude", 55.5, "originLongitude", 11.25,
                "originAngle", 30, "editorFields", list("title", "charts")), null, "IT-imp root " + u, 5.0);
        area(map(), root, "IT-imp B " + u, 10.0);
        int a = area(map(), root, "IT-imp A " + u, 20.0);
        int a1 = area(map(), a, "IT-imp A1 " + u, 0.0);
        cleanups.add(() -> deleteRootByMrn(mrn));
        Map<String, Object> first = exportedArea(mrn);

        Map<String, Object> changed = copy(first);
        changed.put("messageSorting", "NS");
        changed.put("originLatitude", 56.25);
        changed.put("originLongitude", 12.5);
        changed.put("originAngle", 45);
        changed.put("editorFields", list("title"));
        Map<String, Object> childB = listOf(changed, "children").get(0);
        listOf(childB, "descs").stream().filter(d -> "da".equals(d.get("lang")))
                .forEach(d -> d.put("name", "IT-imp B ny " + u));
        childB.put("children", list(map("active", true, "siblingSortOrder", 0.0,
                "descs", list(map("lang", "en", "name", "IT-imp B1 " + u)))));
        listOf(changed, "children").get(1).put("children", list());

        JobEnd job = importRows("admin", "/rest/areas/upload-areas", "area-import", List.of(changed));
        assertEquals("COMPLETED", job.status(), job.log());

        Map<String, Object> after = exportedArea(mrn);
        assertEquals("IT-imp B ny " + u, descs(listOf(after, "children").get(0), "name").get("da"),
                "the sub-area's changed name");
        assertEquals(List.of("IT-imp B1 " + u), listOf(listOf(after, "children").get(0), "children").stream()
                .map(c -> descs(c, "name").get("en")).toList(), "the added sub-sub-area");
        assertEquals(1L, count("SELECT count(*) FROM public.\"Area\" WHERE id = ?1", a1),
                "the area the file leaves out is kept (the import never deletes)");
        assertEquals(List.of("NS", 45, List.of("title")),
                List.of(after.get("messageSorting"), after.get("originAngle"), valuesOf(after, "editorFields")),
                "the root's message sorting, origin angle and editor fields are the file's");
        assertEquals(List.of(56.25, 12.5),
                List.of(((Number) after.get("originLatitude")).doubleValue(),
                        ((Number) after.get("originLongitude")).doubleValue()),
                "the root's origin is the file's");
    }

    private int area(Map<String, Object> fields, Integer parentId, String name, double sortOrder) {
        Map<String, Object> body = map("active", true, "siblingSortOrder", sortOrder,
                "descs", list(map("lang", "en", "name", name), map("lang", "da", "name", name + " da")));
        body.putAll(fields);
        if (parentId != null) {
            body.put("parent", map("id", parentId));
        }
        return ((Number) post("admin", "/rest/areas/area", body).get("id")).intValue();
    }

    private static Map<String, Object> square(double lon, double lat) {
        return map("type", "Polygon", "coordinates", list(list(list(lon, lat), list(lon + 0.5, lat),
                list(lon + 0.5, lat + 0.5), list(lon, lat + 0.5), list(lon, lat))));
    }

    /** The exported root area of the MRN */
    private static Map<String, Object> exportedArea(String mrn) {
        List<Map<String, Object>> found = getList("admin", "/rest/areas/all").stream()
                .filter(r -> mrn.equals(r.get("mrn"))).toList();
        assertEquals(1, found.size(), "one root area of " + mrn);
        return found.getFirst();
    }

    private static List<Object> childNames(Map<String, Object> area) {
        return listOf(area, "children").stream().map(c -> descs(c, "name").get("en")).toList();
    }

    private List<Integer> treeIds(String mrn) {
        return ints("SELECT a.id FROM public.\"Area\" a, public.\"Area\" r WHERE r.mrn = ?1"
                + " AND a.lineage LIKE r.lineage || '%' ORDER BY a.id", mrn);
    }

    private void deleteRootByMrn(String mrn) {
        for (Integer id : ints("SELECT id FROM public.\"Area\" WHERE mrn = ?1", mrn)) {
            delete("admin", "/rest/areas/area/" + id);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Charts
    // ------------------------------------------------------------------------------------------- //

    @Test
    void chartsExportedDeletedAndImportedComeBackAndAnImportOverThemUpdatesThem() {
        String prefix = "IT-IMP-" + unique() + "-";
        List<String> numbers = List.of(prefix + "1", prefix + "2", prefix + "3");
        for (int i = 0; i < numbers.size(); i++) {
            post("admin", "/rest/charts/chart", map("chartNumber", numbers.get(i), "internationalNumber", 1300 + i,
                    "active", i != 1, "scale", 50000 + i, "name", awkward("IT chart " + i), "horizontalDatum", "ED50",
                    "geometry", square(10.0 + i, 55.0)));
            String number = numbers.get(i);
            cleanups.add(() -> delete("admin", "/rest/charts/chart/" + number));
        }
        List<Map<String, Object>> first = exportedCharts(prefix);
        assertEquals(numbers, first.stream().map(c -> c.get("chartNumber")).toList());

        // (a)
        numbers.forEach(n -> delete("admin", "/rest/charts/chart/" + n).statusCode(204));
        assertEquals(List.of(), exportedCharts(prefix));
        JobEnd job = importRows("admin", "/rest/charts/upload-charts", "chart-import", first);
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(first, exportedCharts(prefix), "the charts after the import");

        // (b)
        List<Integer> ids = ints("SELECT id FROM public.\"Chart\" WHERE \"chartNumber\" LIKE ?1 ORDER BY id", prefix + "%");
        job = importRows("admin", "/rest/charts/upload-charts", "chart-import", first);
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(ids, ints("SELECT id FROM public.\"Chart\" WHERE \"chartNumber\" LIKE ?1 ORDER BY id", prefix + "%"));
        assertEquals(first, exportedCharts(prefix));

        // (c) One chart's scale, datum and geometry changed, one cleared field, a fourth added; one left out
        List<Map<String, Object>> changed = new ArrayList<>(first.stream().map(ImportRoundTripSupport::copy).toList());
        changed.get(0).put("scale", 25000);
        changed.get(0).put("horizontalDatum", "WGS84");
        changed.get(0).put("geometry", square(12.0, 56.0));
        changed.get(1).put("internationalNumber", null);
        changed.remove(2);
        String added = prefix + "4";
        cleanups.add(() -> delete("admin", "/rest/charts/chart/" + added));
        changed.add(map("chartNumber", added, "active", true, "scale", 10000, "name", "IT chart 4"));
        job = importRows("admin", "/rest/charts/upload-charts", "chart-import", changed);
        assertEquals("COMPLETED", job.status(), job.log());

        // (d) The export leaves a null field out
        List<Map<String, Object>> expected = new ArrayList<>(changed.subList(0, 2));
        expected.get(1).remove("internationalNumber");
        expected.add(first.get(2));
        expected.add(changed.get(2));
        assertEquals(asRead(expected), exportedCharts(prefix), "updated, kept and added");
        assertEquals(java.util.Arrays.asList(25000, "WGS84", null), java.util.Arrays.asList(exportedCharts(prefix).get(0).get("scale"),
                exportedCharts(prefix).get(0).get("horizontalDatum"), exportedCharts(prefix).get(1).get("internationalNumber")));
    }

    private static List<Map<String, Object>> exportedCharts(String prefix) {
        return getList("admin", "/rest/charts/all?limit=100000").stream()
                .filter(c -> String.valueOf(c.get("chartNumber")).startsWith(prefix))
                .sorted((x, y) -> String.valueOf(x.get("chartNumber")).compareTo(String.valueOf(y.get("chartNumber"))))
                .toList();
    }

    // ------------------------------------------------------------------------------------------- //
    // Sources
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sourcesExportedDeletedAndImportedComeBack() {
        String prefix = "IT-imp source " + unique() + " ";
        for (int i = 0; i < 3; i++) {
            int id = source(prefix + i, i != 1);
            cleanups.add(() -> delete("admin", "/rest/sources/source/" + id));
        }
        List<Map<String, Object>> first = exportedSources(prefix);
        assertEquals(3, first.size());

        // (a)
        first.forEach(s -> delete("admin", "/rest/sources/source/" + s.get("id")).statusCode(204));
        JobEnd job = importRows("admin", "/rest/sources/upload-sources", "source-import", first);
        assertEquals("COMPLETED", job.status(), job.log());
        List<Map<String, Object>> second = exportedSources(prefix);
        second.forEach(s -> cleanups.add(() -> delete("admin", "/rest/sources/source/" + s.get("id"))));
        assertEquals(byLang(without(first, ROW_FIELDS)), byLang(without(second, ROW_FIELDS)),
                "the sources after the import");
    }

    /**
     * The source import is an upsert by name ("A source in the file that has the name of an existing source, in one
     * of the languages, updates it"). {@code BatchSourceImportProcessor.processItem} runs on the batch thread
     * without a transaction, so {@code SourceService.findByName} loads the found source's descs inside its own
     * transaction, which {@code Source.updateSource} then reads (BATCH7). (b) the same export over its own sources
     * completes and changes nothing; (c) a changed Danish abbreviation and a changed active flag are stored, and a
     * new source beside them is added.
     */
    @Test
    void sourcesImportedOverThemselvesUpdateThemAndAddTheNewOne() {
        String prefix = "IT-imp source " + unique() + " ";
        for (int i = 0; i < 2; i++) {
            int id = source(prefix + i, true);
            cleanups.add(() -> delete("admin", "/rest/sources/source/" + id));
        }
        List<Map<String, Object>> first = exportedSources(prefix);
        assertEquals(2, first.size());

        // (b) the same export over its own sources
        JobEnd job = importRows("admin", "/rest/sources/upload-sources", "source-import", first);
        assertEquals("COMPLETED", job.status(), "the same export over its own sources: " + job.log());
        assertEquals(byLang(first), byLang(exportedSources(prefix)), "nothing was changed");

        // (c) the Danish abbreviation of one changed, the active flag of the other, a third added
        List<Map<String, Object>> changed = new ArrayList<>(first.stream().map(ImportRoundTripSupport::copy).toList());
        listOf(changed.get(0), "descs").stream().filter(d -> "da".equals(d.get("lang")))
                .forEach(d -> d.put("abbreviation", "NY"));
        changed.get(1).put("active", false);
        changed.add(map("active", true, "descs", list(map("lang", "en", "name", prefix + "2", "abbreviation", "N2"))));
        job = importRows("admin", "/rest/sources/upload-sources", "source-import", changed);
        List<Map<String, Object>> after = exportedSources(prefix);
        after.stream().filter(s -> !first.stream().map(f -> f.get("id")).toList().contains(s.get("id")))
                .forEach(s -> cleanups.add(() -> delete("admin", "/rest/sources/source/" + s.get("id"))));
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(3, after.size(), "the two sources and the new one: " + after);
        assertEquals("NY", descs(after.get(0), "abbreviation").get("da"), "the changed Danish abbreviation");
        assertEquals("IS", descs(after.get(0), "abbreviation").get("en"), "the English one as it was");
        assertEquals(true, after.get(0).get("active"), "the first source stays active");
        assertEquals(false, after.get(1).get("active"), "the changed active flag");
        assertEquals(byLang(changed.subList(0, 2)), byLang(after.subList(0, 2)),
                "the two sources updated in place, nothing else");
        assertEquals(prefix + "2", descs(after.get(2), "name").get("en"), "the new source");
        assertEquals("N2", descs(after.get(2), "abbreviation").get("en"), "the new source's abbreviation");
        assertEquals(true, after.get(2).get("active"), "the new source is active");
    }

    private int source(String name, boolean active) {
        return ((Number) post("admin", "/rest/sources/source", map("active", active, "descs", list(
                map("lang", "en", "name", awkward(name), "abbreviation", "IS"),
                map("lang", "da", "name", awkward(name) + "da", "abbreviation", "IK")))).get("id")).intValue();
    }

    /**
     * The value with each description list in the order of its languages: a description list comes back in no
     * defined order (DB5), so the comparisons read a row's descriptions by language
     */
    private static Object byLang(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(k, "descs".equals(k) && v instanceof List<?> descs
                    ? descs.stream().map(ReferenceDataImportRoundTripITTest::byLang)
                            .sorted(Comparator.comparing(d -> String.valueOf(((Map<?, ?>) d).get("lang")))).toList()
                    : byLang(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ReferenceDataImportRoundTripITTest::byLang).toList();
        }
        return value;
    }

    private static List<Map<String, Object>> exportedSources(String prefix) {
        return getList("admin", "/rest/sources/all?limit=100000").stream()
                .filter(s -> String.valueOf(descs(s, "name").get("en")).startsWith(prefix))
                .sorted((x, y) -> String.valueOf(descs(x, "name").get("en")).compareTo(String.valueOf(descs(y, "name").get("en"))))
                .toList();
    }
}
