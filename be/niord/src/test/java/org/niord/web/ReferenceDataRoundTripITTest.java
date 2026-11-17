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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save, reload, save again for the reference data a message is built from, through the REST interface as the role
 * that manages each: areas (the tree: create, move, reorder; descriptions; geometry; editor fields), charts,
 * sources, domains (without the Keycloak part), message series (with the next number), dictionary entries, and an
 * editable Boolean setting set to false.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
@SuppressWarnings("unchecked")
class ReferenceDataRoundTripITTest extends RoundTripSupport {

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
    // Areas
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anAreaKeepsItsFieldsThroughCreateUpdateAnUnchangedSaveAMoveAndDelete() {
        String name = "IT-RT area " + unique();
        int a = area(name + " A", null, 0);
        int c = area(name + " C", null, 10);
        Map<String, Object> body = map(
                "type", "FIRING_AREA", "mrn", "urn:mrn:iho:it-rt:" + unique(), "active", true, "siblingSortOrder", 20.0,
                "parent", map("id", a),
                "descs", list(map("lang", "en", "name", name + " B"), map("lang", "da", "name", name + " B da")),
                "geometry", map("type", "Polygon", "coordinates", list(list(list(10.0, 55.0), list(10.5, 55.0),
                        list(10.5, 55.5), list(10.0, 55.0)))),
                "messageSorting", "CW", "originLatitude", 55.25, "originLongitude", 10.25, "originAngle", 90,
                "editorFields", list("title", "areas", "charts"));
        int b = ((Number) post("admin", "/rest/areas/area", body).get("id")).intValue();
        cleanups.add(() -> deleteTwice("admin", "/rest/areas/area/" + b));
        int d = area(name + " D", c, 10);

        Map<String, Object> read = getMap("admin", "/rest/areas/area/" + b);
        assertEquals(List.of("FIRING_AREA", body.get("mrn"), true, 20.0, "CW", 55.25, 10.25, 90),
                List.of(read.get("type"), read.get("mrn"), read.get("active"), ((Number) read.get("siblingSortOrder")).doubleValue(),
                        read.get("messageSorting"), ((Number) read.get("originLatitude")).doubleValue(),
                        ((Number) read.get("originLongitude")).doubleValue(), read.get("originAngle")));
        assertEquals(Map.of("en", name + " B", "da", name + " B da"), descs(read, "name"));
        assertEquals(ring(body), ring(read), "the geometry");
        assertEquals(Set.of("title", "areas", "charts"), new HashSet<>(valuesOf(read, "editorFields")));

        // Update: a field of each kind changed, an editor field removed, the geometry moved
        Map<String, Object> edit = getMap("admin", "/rest/areas/area/" + b);
        edit.put("messageSorting", "NS");
        edit.put("originAngle", 45);
        edit.put("editorFields", list("charts", "title"));
        listOf(edit, "descs").stream().filter(x -> "da".equals(x.get("lang"))).forEach(x -> x.put("name", name + " B ny"));
        ((List<Object>) ((List<Object>) ((Map<String, Object>) edit.get("geometry")).get("coordinates")).getFirst())
                .set(2, list(10.75, 55.75));
        put("admin", "/rest/areas/area/" + b, edit);
        Map<String, Object> updated = getMap("admin", "/rest/areas/area/" + b);
        assertEquals(List.of("NS", 45), List.of(updated.get("messageSorting"), updated.get("originAngle")));
        assertEquals(Map.of("en", name + " B", "da", name + " B ny"), descs(updated, "name"));
        assertEquals(Set.of("charts", "title"), new HashSet<>(valuesOf(updated, "editorFields")));
        assertEquals("[10.0,55.0 10.5,55.0 10.75,55.75 10.0,55.0]", ring(updated), "the moved geometry");

        // Unchanged save
        Map<String, String> rows = Map.of(
                "descs", "SELECT count(*) FROM public.\"AreaDesc\" WHERE entity_id = ?1",
                "editor fields", "SELECT count(*) FROM public.\"Area_editorFields\" WHERE \"Area_id\" = ?1");
        Map<String, Long> before = counts(rows, b);
        put("admin", "/rest/areas/area/" + b, getMap("admin", "/rest/areas/area/" + b));
        assertEquals(before, counts(rows, b), "the rows of an unchanged save");
        Map<String, Object> resaved = getMap("admin", "/rest/areas/area/" + b);
        assertEquals(ring(updated), ring(resaved));
        assertEquals(updated.get("siblingSortOrder"), resaved.get("siblingSortOrder"));
        assertEquals("/" + a + "/" + b + "/", strings("SELECT lineage FROM public.\"Area\" WHERE id = ?1", b).getFirst());

        // Move B under C, after D (their sort orders 20 and 10), then up before D
        as("admin").body(map("areaId", b, "parentId", c)).when().put("/rest/areas/move-area").then().statusCode(200);
        assertEquals("/" + c + "/" + b + "/", strings("SELECT lineage FROM public.\"Area\" WHERE id = ?1", b).getFirst());
        assertEquals(List.of(d, b), children(c));
        as("admin").body(map("areaId", b, "moveUp", true)).when().put("/rest/areas/change-sort-order").then().statusCode(200);
        assertEquals(List.of(b, d), children(c), "B moved before D");
        assertEquals(Map.of("en", name + " B", "da", name + " B ny"), descs(getMap("admin", "/rest/areas/area/" + b), "name"),
                "B keeps its names through the moves");

        // The delete of a sub-area: aSubAreaOrSubCategoryDeletedOverRestIsDeleted
    }

    /**
     * Deleting a sub-area ({@code DELETE /rest/areas/area/{id}}) or a sub-category ({@code DELETE
     * /rest/categories/category/{id}}) answers {@code true} and removes the row and its descriptions; the parent
     * stays with its other children (AREA1). The delete takes the entity out of the parent's {@code children} list,
     * which cascades ALL ({@code TreeBaseEntity.children}): an entity left in it would be saved again at the flush,
     * as a root.
     */
    @Test
    void aSubAreaOrSubCategoryDeletedOverRestIsDeleted() {
        String name = "IT-RT child " + unique();
        int parentArea = area(name + " A", null, 0);
        int childArea = area(name + " B", parentArea, 0);
        int siblingArea = area(name + " B2", parentArea, 10);
        String areaAnswer = as("admin").when().delete("/rest/areas/area/" + childArea).then().statusCode(200)
                .extract().asString().trim();
        List<Object> areaOutcome = List.of(areaAnswer, count("SELECT count(*) FROM public.\"Area\" WHERE id = ?1", childArea),
                count("SELECT count(*) FROM public.\"AreaDesc\" WHERE entity_id = ?1", childArea),
                count("SELECT count(*) FROM public.\"Area\" WHERE id = ?1 AND parent_id IS NULL", childArea) == 1);

        int parentCategory = category(name + " C", null);
        int childCategory = category(name + " D", parentCategory);
        int siblingCategory = category(name + " D2", parentCategory);
        String categoryAnswer = as("sysadmin").when().delete("/rest/categories/category/" + childCategory).then()
                .statusCode(200).extract().asString().trim();
        List<Object> categoryOutcome = List.of(categoryAnswer,
                count("SELECT count(*) FROM public.\"Category\" WHERE id = ?1", childCategory),
                count("SELECT count(*) FROM public.\"CategoryDesc\" WHERE entity_id = ?1", childCategory),
                count("SELECT count(*) FROM public.\"Category\" WHERE id = ?1 AND parent_id IS NULL", childCategory) == 1);

        assertEquals(List.of(List.of("true", 0L, 0L, false), List.of("true", 0L, 0L, false)), List.of(areaOutcome, categoryOutcome),
                "the answer, the row, the descriptions and whether it lost its parent: the sub-area, then the sub-category");
        assertEquals(List.of(siblingArea), children(parentArea), "the parent area keeps its other child");
        assertEquals(List.of(siblingCategory),
                ints("SELECT id FROM public.\"Category\" WHERE parent_id = ?1 ORDER BY id", parentCategory),
                "the parent category keeps its other child");
        assertEquals(List.of(1L, 1L), List.of(count("SELECT count(*) FROM public.\"Area\" WHERE id = ?1", parentArea),
                count("SELECT count(*) FROM public.\"Category\" WHERE id = ?1", parentCategory)), "the parents stay");
    }

    /**
     * Deleting a sub-area that has sub-areas of its own removes the whole subtree under it, and nothing above it
     * (AREA1); the same for a sub-category.
     */
    @Test
    void aSubAreaOrSubCategoryWithChildrenIsDeletedWithItsSubtree() {
        String name = "IT-RT subtree " + unique();
        int root = area(name + " A", null, 0);
        int middle = area(name + " B", root, 0);
        int leaf = area(name + " C", middle, 0);
        int deeper = area(name + " D", leaf, 0);
        assertEquals("true", as("admin").when().delete("/rest/areas/area/" + middle).then().statusCode(200)
                .extract().asString().trim());
        assertEquals(List.of(0L, 1L), List.of(
                count("SELECT count(*) FROM public.\"Area\" WHERE id IN (?1, ?2, ?3)", middle, leaf, deeper),
                count("SELECT count(*) FROM public.\"Area\" WHERE id = ?1", root)), "the subtree goes, the root stays");
        assertEquals(0L, count("SELECT count(*) FROM public.\"Area\" WHERE lineage LIKE ?1",
                "/" + root + "/" + middle + "/%"), "no row is left with a lineage under the deleted area");
        assertEquals(List.of(), children(root));

        int categoryRoot = category(name + " E", null);
        int categoryMiddle = category(name + " F", categoryRoot);
        int categoryLeaf = category(name + " G", categoryMiddle);
        assertEquals("true", as("sysadmin").when().delete("/rest/categories/category/" + categoryMiddle).then()
                .statusCode(200).extract().asString().trim());
        assertEquals(List.of(0L, 1L), List.of(
                count("SELECT count(*) FROM public.\"Category\" WHERE id IN (?1, ?2)", categoryMiddle, categoryLeaf),
                count("SELECT count(*) FROM public.\"Category\" WHERE id = ?1", categoryRoot)),
                "the sub-category's subtree goes, the root stays");
    }

    /**
     * Deletes a tree entity twice, as cleanup: the first call deletes it, and the second, which answers false, is
     * left from the time a sub-area or sub-category was not deleted by the first call (AREA1). It is harmless.
     */
    static void deleteTwice(String role, String path) {
        delete(role, path);
        delete(role, path);
    }

    private int category(String name, Integer parentId) {
        Map<String, Object> body = map("type", "CATEGORY", "active", true, "descs", list(map("lang", "en", "name", name)));
        if (parentId != null) {
            body.put("parent", map("id", parentId));
        }
        int id = ((Number) post("sysadmin", "/rest/categories/category", body).get("id")).intValue();
        cleanups.add(() -> delete("sysadmin", "/rest/categories/category/" + id));
        return id;
    }

    private int area(String name, Integer parentId, double sortOrder) {
        Map<String, Object> body = map("active", true, "siblingSortOrder", sortOrder,
                "descs", list(map("lang", "en", "name", name), map("lang", "da", "name", name + " da")));
        if (parentId != null) {
            body.put("parent", map("id", parentId));
        }
        int id = ((Number) post("admin", "/rest/areas/area", body).get("id")).intValue();
        cleanups.add(() -> deleteTwice("admin", "/rest/areas/area/" + id));
        return id;
    }

    private List<Integer> children(int parentId) {
        return ints("SELECT id FROM public.\"Area\" WHERE parent_id = ?1 ORDER BY \"siblingSortOrder\", id", parentId);
    }

    /** A polygon's outer ring as text */
    private static String ring(Map<String, Object> owner) {
        Map<String, Object> g = (Map<String, Object>) owner.get("geometry");
        List<Object> positions = (List<Object>) ((List<Object>) g.get("coordinates")).getFirst();
        List<String> parts = new ArrayList<>();
        for (Object p : positions) {
            List<Object> pos = (List<Object>) p;
            parts.add(((Number) pos.get(0)).doubleValue() + "," + ((Number) pos.get(1)).doubleValue());
        }
        return "[" + String.join(" ", parts) + "]";
    }

    // ------------------------------------------------------------------------------------------- //
    // Charts and sources
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aChartKeepsItsFieldsThroughCreateUpdateAndAnUnchangedSave() {
        String number = "IT-RT-" + unique();
        post("admin", "/rest/charts/chart", map("chartNumber", number, "internationalNumber", 1234, "active", true,
                "scale", 50000, "name", "IT-RT chart", "horizontalDatum", "WGS84",
                "geometry", map("type", "Polygon", "coordinates", list(list(list(10.0, 55.0), list(11.0, 55.0),
                        list(11.0, 56.0), list(10.0, 55.0))))));
        cleanups.add(() -> delete("admin", "/rest/charts/chart/" + number));

        Map<String, Object> read = chart(number);
        assertEquals(List.of(1234, true, 50000, "IT-RT chart", "WGS84"), List.of(read.get("internationalNumber"),
                read.get("active"), read.get("scale"), read.get("name"), read.get("horizontalDatum")));
        assertEquals("[10.0,55.0 11.0,55.0 11.0,56.0 10.0,55.0]", ring(read));

        Map<String, Object> edit = chart(number);
        edit.put("scale", 25000);
        edit.put("active", false);
        edit.put("internationalNumber", null);
        put("admin", "/rest/charts/chart/" + number, edit);
        Map<String, Object> updated = chart(number);
        assertEquals(List.of(false, 25000), List.of(updated.get("active"), updated.get("scale")));
        assertEquals(null, updated.get("internationalNumber"), "a field cleared stays cleared");

        put("admin", "/rest/charts/chart/" + number, chart(number));
        assertEquals(updated, chart(number), "an unchanged save changes nothing");

        delete("admin", "/rest/charts/chart/" + number).statusCode(204);
        assertEquals(0L, count("SELECT count(*) FROM public.\"Chart\" WHERE \"chartNumber\" = ?1", number));
    }

    private static Map<String, Object> chart(String number) {
        List<Map<String, Object>> found = getList("admin", "/rest/charts/search/" + number);
        assertEquals(1, found.size(), "the chart " + number);
        return new LinkedHashMap<>(found.getFirst());
    }

    @Test
    void aSourceKeepsItsNamesAndAbbreviationsInTwoLanguages() {
        String name = "IT-RT source " + unique();
        int id = ((Number) post("admin", "/rest/sources/source", map("active", true, "descs", list(
                map("lang", "en", "name", name, "abbreviation", "ITS"),
                map("lang", "da", "name", name + " da", "abbreviation", "ITK")))).get("id")).intValue();
        cleanups.add(() -> delete("admin", "/rest/sources/source/" + id));

        Map<String, Object> read = getMap("admin", "/rest/sources/source/" + id);
        assertEquals(Map.of("en", name, "da", name + " da"), descs(read, "name"));
        assertEquals(Map.of("en", "ITS", "da", "ITK"), descs(read, "abbreviation"));

        Map<String, Object> edit = getMap("admin", "/rest/sources/source/" + id);
        edit.put("active", false);
        listOf(edit, "descs").stream().filter(d -> "da".equals(d.get("lang"))).forEach(d -> d.put("abbreviation", "ITD"));
        put("admin", "/rest/sources/source/" + id, edit);
        Map<String, Object> updated = getMap("admin", "/rest/sources/source/" + id);
        assertEquals(false, updated.get("active"));
        assertEquals(Map.of("en", "ITS", "da", "ITD"), descs(updated, "abbreviation"));

        long descRows = count("SELECT count(*) FROM public.\"SourceDesc\" WHERE entity_id = ?1", id);
        put("admin", "/rest/sources/source/" + id, getMap("admin", "/rest/sources/source/" + id));
        assertEquals(descRows, count("SELECT count(*) FROM public.\"SourceDesc\" WHERE entity_id = ?1", id));
        assertEquals(descs(updated, "abbreviation"), descs(getMap("admin", "/rest/sources/source/" + id), "abbreviation"));

        delete("admin", "/rest/sources/source/" + id).statusCode(204);
        assertEquals(0L, count("SELECT count(*) FROM public.\"SourceDesc\" WHERE entity_id = ?1", id));
    }

    // ------------------------------------------------------------------------------------------- //
    // Domains
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aDomainKeepsItsAreasCategoriesAndMessageSeriesThroughCreateUpdateAnUnchangedSaveAndDelete() {
        List<Integer> areas = ints("SELECT id FROM public.\"Area\" WHERE parent_id IS NULL ORDER BY id LIMIT 4");
        List<Integer> categories = ints("SELECT id FROM public.\"Category\" WHERE parent_id IS NULL ORDER BY id LIMIT 3");
        String domainId = "it-rt-domain-" + unique();
        post("sysadmin", "/rest/domains/domain", map("domainId", domainId, "name", "IT-RT domain", "active", true,
                "sortOrder", 99, "timeZone", "Europe/Copenhagen", "lat", 56.0, "lon", 11.0, "zoomLevel", 6,
                "messageSortOrder", "ID ASC", "color", "#336699", "publish", true, "atons", true, "templates", true,
                "areas", list(map("id", areas.get(0)), map("id", areas.get(1)), map("id", areas.get(2))),
                "categories", list(map("id", categories.get(0)), map("id", categories.get(1))),
                "messageSeries", list(map("seriesId", "dma-nw"), map("seriesId", "dma-nm"))));
        cleanups.add(() -> delete("sysadmin", "/rest/domains/domain/" + domainId));

        Map<String, Object> read = domain(domainId);
        assertEquals(List.of("IT-RT domain", true, 99, "Europe/Copenhagen", 56.0, 11.0, 6, "ID ASC", "#336699", true, true, true),
                List.of(read.get("name"), read.get("active"), read.get("sortOrder"), read.get("timeZone"),
                        ((Number) read.get("lat")).doubleValue(), ((Number) read.get("lon")).doubleValue(), read.get("zoomLevel"),
                        read.get("messageSortOrder"), read.get("color"), read.get("publish"), read.get("atons"), read.get("templates")));
        assertEquals(Set.of(areas.get(0), areas.get(1), areas.get(2)), idSet(read, "areas"));
        assertEquals(Set.of(categories.get(0), categories.get(1)), idSet(read, "categories"));
        assertEquals(Set.of("dma-nw", "dma-nm"), new HashSet<>(fieldOf(read, "messageSeries", "seriesId")));

        Map<String, Object> edit = domain(domainId);
        edit.put("areas", list(map("id", areas.get(2)), map("id", areas.get(3))));
        edit.put("categories", list(map("id", categories.get(2)), map("id", categories.get(0)), map("id", categories.get(1))));
        edit.put("messageSeries", list(map("seriesId", "dma-nm")));
        edit.put("publish", false);
        edit.put("zoomLevel", 7);
        put("sysadmin", "/rest/domains/domain/" + domainId, edit);
        Map<String, Object> updated = domain(domainId);
        assertEquals(Set.of(areas.get(2), areas.get(3)), idSet(updated, "areas"));
        assertEquals(Set.of(categories.get(0), categories.get(1), categories.get(2)), idSet(updated, "categories"));
        assertEquals(List.of("dma-nm"), fieldOf(updated, "messageSeries", "seriesId"));
        assertEquals(List.of(false, 7), List.of(updated.get("publish"), updated.get("zoomLevel")));

        Map<String, String> rows = Map.of(
                "areas", "SELECT count(*) FROM public.\"Domain_Area\" WHERE \"Domain_id\" = (SELECT id FROM public.\"Domain\" WHERE \"domainId\" = ?1)",
                "categories", "SELECT count(*) FROM public.\"Domain_Category\" WHERE \"Domain_id\" = (SELECT id FROM public.\"Domain\" WHERE \"domainId\" = ?1)",
                "message series", "SELECT count(*) FROM public.\"Domain_MessageSeries\" WHERE \"Domain_id\" = (SELECT id FROM public.\"Domain\" WHERE \"domainId\" = ?1)");
        Map<String, Long> before = counts(rows, domainId);
        assertEquals(Map.of("areas", 2L, "categories", 3L, "message series", 1L), before);
        put("sysadmin", "/rest/domains/domain/" + domainId, domain(domainId));
        assertEquals(before, counts(rows, domainId), "the rows of an unchanged save");

        int id = ints("SELECT id FROM public.\"Domain\" WHERE \"domainId\" = ?1", domainId).getFirst();
        delete("sysadmin", "/rest/domains/domain/" + domainId).statusCode(204);
        assertEquals(0L, count("SELECT count(*) FROM public.\"Domain_Area\" WHERE \"Domain_id\" = ?1", id)
                + count("SELECT count(*) FROM public.\"Domain_Category\" WHERE \"Domain_id\" = ?1", id)
                + count("SELECT count(*) FROM public.\"Domain_MessageSeries\" WHERE \"Domain_id\" = ?1", id),
                "the delete leaves no join row");
    }

    private static Map<String, Object> domain(String domainId) {
        return getList("sysadmin", "/rest/domains/all?inactive=true&keycloakState=false").stream()
                .filter(d -> domainId.equals(d.get("domainId"))).findFirst()
                .map(LinkedHashMap::new).orElseThrow(() -> new AssertionError("no domain " + domainId));
    }

    private static Set<Integer> idSet(Map<String, Object> owner, String key) {
        Set<Integer> ids = new HashSet<>();
        fieldOf(owner, key, "id").forEach(v -> ids.add(((Number) v).intValue()));
        return ids;
    }

    // ------------------------------------------------------------------------------------------- //
    // Message series
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessageSeriesKeepsItsTypesFormatsAndEditorFieldsAndItsNextNumber() {
        String seriesId = "it-rt-series-" + unique();
        post("sysadmin", "/rest/message-series/series", map("seriesId", seriesId, "mainType", "NW",
                "types", list("LOCAL_WARNING", "COASTAL_WARNING"), "shortFormat", "IT-${number-3-digits}-${year-2-digits}",
                "navtexFormat", "IT NAV WARN ${number-year-id}", "numberSequenceType", "YEARLY",
                "editorFields", list("title", "areas"), "excludeFromMessageIndex", true));
        cleanups.add(() -> delete("sysadmin", "/rest/message-series/series/" + seriesId));

        Map<String, Object> read = series(seriesId);
        assertEquals(List.of("NW", "IT-${number-3-digits}-${year-2-digits}", "IT NAV WARN ${number-year-id}", "YEARLY", true),
                List.of(read.get("mainType"), read.get("shortFormat"), read.get("navtexFormat"),
                        read.get("numberSequenceType"), read.get("excludeFromMessageIndex")));
        assertEquals(Set.of("LOCAL_WARNING", "COASTAL_WARNING"), new HashSet<>(valuesOf(read, "types")));
        assertEquals(Set.of("title", "areas"), new HashSet<>(valuesOf(read, "editorFields")));

        Map<String, Object> edit = series(seriesId);
        edit.put("types", list("COASTAL_WARNING", "NAVAREA_WARNING", "SUBAREA_WARNING"));
        edit.put("editorFields", list("areas"));
        edit.put("excludeFromMessageIndex", false);
        put("sysadmin", "/rest/message-series/series/" + seriesId, edit);
        Map<String, Object> updated = series(seriesId);
        assertEquals(Set.of("COASTAL_WARNING", "NAVAREA_WARNING", "SUBAREA_WARNING"), new HashSet<>(valuesOf(updated, "types")));
        assertEquals(List.of("areas"), valuesOf(updated, "editorFields"));
        assertEquals(false, updated.get("excludeFromMessageIndex"));

        Map<String, String> rows = Map.of(
                "types", "SELECT count(*) FROM public.\"MessageSeries_types\" WHERE \"MessageSeries_id\" = (SELECT id FROM public.\"MessageSeries\" WHERE \"seriesId\" = ?1)",
                "editor fields", "SELECT count(*) FROM public.\"MessageSeries_editorFields\" WHERE \"MessageSeries_id\" = (SELECT id FROM public.\"MessageSeries\" WHERE \"seriesId\" = ?1)");
        Map<String, Long> before = counts(rows, seriesId);
        put("sysadmin", "/rest/message-series/series/" + seriesId, series(seriesId));
        assertEquals(before, counts(rows, seriesId), "the rows of an unchanged save");
        assertEquals(updated, series(seriesId), "an unchanged save changes nothing");

        // The next number of a year: set, read back, kept through an unchanged save of the series
        String number = "/rest/message-series/series/" + seriesId + "/number/2030";
        assertEquals(1, ((Number) as("sysadmin").when().get(number).then().statusCode(200).extract().as(Integer.class)).intValue());
        as("sysadmin").body(42).when().put(number).then().statusCode(200);
        assertEquals(42, as("sysadmin").when().get(number).then().statusCode(200).extract().as(Integer.class).intValue());
        put("sysadmin", "/rest/message-series/series/" + seriesId, series(seriesId));
        assertEquals(42, as("sysadmin").when().get(number).then().statusCode(200).extract().as(Integer.class).intValue(),
                "the next number after an unchanged save of the series");
    }

    /**
     * A series' number sequence is a {@code Sequence} row named after the series id (and the year), which the series'
     * delete leaves behind ({@code MessageSeriesService.deleteMessageSeries} removes the series row only). A series
     * created again with the same id goes on from the old next number.
     *
     * <p>The right assertion, should the owner decide the sequence goes with its series: no {@code Sequence} row
     * named after the deleted series, and 1 as the new series' next number.
     */
    @Test
    void aDeletedSeriesLeavesItsNumberSequenceBehind_documentsBehavior() {
        String seriesId = "it-rt-series-" + unique();
        Map<String, Object> body = map("seriesId", seriesId, "mainType", "NW", "types", list("LOCAL_WARNING"),
                "shortFormat", "IT-${number}", "numberSequenceType", "YEARLY");
        post("sysadmin", "/rest/message-series/series", body);
        String number = "/rest/message-series/series/" + seriesId + "/number/2030";
        as("sysadmin").body(17).when().put(number).then().statusCode(200);

        delete("sysadmin", "/rest/message-series/series/" + seriesId).statusCode(204);
        assertEquals(1L, count("SELECT count(*) FROM public.\"Sequence\" WHERE name = ?1",
                "MESSAGE_SERIES_NUMBER_" + seriesId + "_2030"), "the sequence row outlives its series");

        post("sysadmin", "/rest/message-series/series", body);
        cleanups.add(() -> delete("sysadmin", "/rest/message-series/series/" + seriesId));
        cleanups.add(() -> execute("DELETE FROM public.\"Sequence\" WHERE name = ?1", "MESSAGE_SERIES_NUMBER_" + seriesId + "_2030"));
        assertEquals(17, as("sysadmin").when().get(number).then().statusCode(200).extract().as(Integer.class).intValue(),
                "a series created again with the same id goes on from the old number");
    }

    private static Map<String, Object> series(String seriesId) {
        List<Map<String, Object>> found = getList("sysadmin", "/rest/message-series/search/" + seriesId);
        assertEquals(1, found.size(), "the series " + seriesId);
        return new LinkedHashMap<>(found.getFirst());
    }

    // ------------------------------------------------------------------------------------------- //
    // Dictionary entries
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aDictionaryEntryKeepsItsValuesInTwoLanguages() {
        String dictionary = "web";
        String key = "it.rt." + unique();
        post("sysadmin", "/rest/dictionaries/dictionary/" + dictionary, map("key", key, "atonFilter", "aton.k('seamark:type')",
                "descs", list(map("lang", "en", "value", "Light", "longValue", "A light"),
                        map("lang", "da", "value", "Fyr", "longValue", "Et fyr"))));
        cleanups.add(() -> delete("sysadmin", "/rest/dictionaries/dictionary/" + dictionary + "/" + key));

        Map<String, Object> read = entry(dictionary, key);
        assertEquals("aton.k('seamark:type')", read.get("atonFilter"));
        assertEquals(Map.of("en", "Light", "da", "Fyr"), descs(read, "value"));
        assertEquals(Map.of("en", "A light", "da", "Et fyr"), descs(read, "longValue"));

        Map<String, Object> edit = entry(dictionary, key);
        listOf(edit, "descs").stream().filter(d -> "da".equals(d.get("lang"))).forEach(d -> d.put("value", "Fyret"));
        put("sysadmin", "/rest/dictionaries/dictionary/" + dictionary + "/" + key, edit);
        assertEquals(Map.of("en", "Light", "da", "Fyret"), descs(entry(dictionary, key), "value"));

        String descRows = "SELECT count(*) FROM public.\"DictionaryEntryDesc\" WHERE entity_id = (SELECT e.id FROM"
                + " public.\"DictionaryEntry\" e JOIN public.\"Dictionary\" d ON d.id = e.dictionary_id WHERE e.dict_key = ?1"
                + " AND d.name = ?2)";
        long before = count(descRows, key, dictionary);
        put("sysadmin", "/rest/dictionaries/dictionary/" + dictionary + "/" + key, entry(dictionary, key));
        assertEquals(before, count(descRows, key, dictionary), "the rows of an unchanged save");
        assertEquals(Map.of("en", "A light", "da", "Et fyr"), descs(entry(dictionary, key), "longValue"));

        as("sysadmin").when().delete("/rest/dictionaries/dictionary/" + dictionary + "/" + key).then().statusCode(200);
        assertEquals(0L, count("SELECT count(*) FROM public.\"DictionaryEntry\" WHERE dict_key = ?1", key));
    }

    private static Map<String, Object> entry(String dictionary, String key) {
        return getList("sysadmin", "/rest/dictionaries/dictionary/" + dictionary + "/entries").stream()
                .filter(e -> key.equals(e.get("key"))).findFirst()
                .map(LinkedHashMap::new).orElseThrow(() -> new AssertionError("no entry " + key));
    }

    // ------------------------------------------------------------------------------------------- //
    // Settings
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anEditableBooleanSettingCanBeSetToFalseAndBackOverRest() {
        Map<String, Object> setting = getList("sysadmin", "/rest/settings/editable-settings").stream()
                .filter(s -> "Boolean".equals(s.get("type")) && Boolean.TRUE.equals(s.get("value")))
                .findFirst().map(LinkedHashMap::new).orElse(null);
        assertNotNull(setting, "an editable Boolean setting that is true");
        String key = (String) setting.get("key");
        cleanups.add(() -> put("sysadmin", "/rest/settings/setting/" + key, map("key", key, "value", true, "type", "Boolean")));

        setting.put("value", false);
        put("sysadmin", "/rest/settings/setting/" + key, setting);
        assertEquals(false, getMap("sysadmin", "/rest/settings/setting/" + key).get("value"), key + " reads false");
        assertEquals(List.of("false"), strings("SELECT setting_value FROM public.\"Setting\" WHERE setting_key = ?1", key),
                key + " is stored as false");
        assertTrue(getList("sysadmin", "/rest/settings/editable-settings").stream()
                .anyMatch(s -> key.equals(s.get("key")) && Boolean.FALSE.equals(s.get("value"))), "the list shows false");

        // An unchanged save keeps false
        put("sysadmin", "/rest/settings/setting/" + key, getMap("sysadmin", "/rest/settings/setting/" + key));
        assertEquals(false, getMap("sysadmin", "/rest/settings/setting/" + key).get("value"));
    }
}
