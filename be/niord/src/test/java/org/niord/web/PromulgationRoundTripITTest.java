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

/**
 * Save, reload, save again for the promulgation types and their own configuration, through the REST interface as a
 * system administrator: a type with two domains, two message types and three script resource paths; NAVTEX
 * transmitters with three areas each; SafetyNET areas of each of the four shapes; Twitter settings.
 *
 * <p>The Twitter settings answer 500 today on every read and write that maps the type (PROM7 in
 * be/docs/potential-errors.md): their test pins that ({@code _documentsBug}) and checks what is stored in SQL, so the
 * fix flips it. The SafetyNET areas did the same until the fix of PROM6 (2026-11-12). This is a framework-coupled
 * test, see docs/framework-coupled-tests.md.
 */
class PromulgationRoundTripITTest extends RoundTripSupport {

    private final List<String> typeIds = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        for (String typeId : typeIds) {
            // A type's own rows block its delete (PROM8): remove them first
            execute("DELETE FROM public.\"NavtexTransmitter_Area\" WHERE \"NavtexTransmitter_id\" IN (SELECT t.id FROM"
                    + " public.\"NavtexTransmitter\" t JOIN public.\"PromulgationType\" p ON p.id = t.\"promulgationType_id\""
                    + " WHERE p.\"typeId\" = ?1)", typeId);
            for (String table : List.of("NavtexTransmitter", "SafetyNetArea", "TwitterSettings")) {
                execute("DELETE FROM public.\"" + table + "\" WHERE \"promulgationType_id\" IN (SELECT id FROM"
                        + " public.\"PromulgationType\" WHERE \"typeId\" = ?1)", typeId);
            }
            delete("sysadmin", "/rest/promulgations/promulgation-type/" + typeId);
        }
        typeIds.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Promulgation type
    // ------------------------------------------------------------------------------------------- //

    private static final Map<String, String> TYPE_ROWS = new LinkedHashMap<>();

    static {
        TYPE_ROWS.put("domains", "SELECT count(*) FROM public.\"PromulgationType_Domain\" d JOIN public.\"PromulgationType\" p"
                + " ON p.id = d.\"PromulgationType_id\" WHERE p.\"typeId\" = ?1");
        TYPE_ROWS.put("message types", "SELECT count(*) FROM public.\"PromulgationType_messageTypes\" d JOIN public.\"PromulgationType\" p"
                + " ON p.id = d.\"PromulgationType_id\" WHERE p.\"typeId\" = ?1");
        TYPE_ROWS.put("script paths", "SELECT count(*) FROM public.\"PromulgationType_scriptResourcePaths\" d JOIN public.\"PromulgationType\" p"
                + " ON p.id = d.\"PromulgationType_id\" WHERE p.\"typeId\" = ?1");
    }

    @Test
    void aPromulgationTypeKeepsItsDomainsMessageTypesAndScriptPathsThroughCreateUpdateAnUnchangedSaveAndDelete() {
        String typeId = "it-rt-" + unique();
        Map<String, Object> body = map(
                "typeId", typeId, "serviceId", "navtex", "name", "IT-RT NAVTEX", "priority", 7, "active", true,
                "requirement", "DEFAULT", "language", "en",
                "domains", list(map("domainId", "niord-nw"), map("domainId", "niord-nm")),
                "messageTypes", list("LOCAL_WARNING", "COASTAL_WARNING"),
                "scriptResourcePaths", list("templates/it-rt/p1.ftl", "templates/it-rt/p2.js", "templates/it-rt/p3.ftl"));
        post("sysadmin", "/rest/promulgations/promulgation-type", body);
        typeIds.add(typeId);

        Map<String, Object> read = type(typeId);
        assertEquals(List.of("navtex", "IT-RT NAVTEX", 7, true, "DEFAULT", "en"),
                List.of(read.get("serviceId"), read.get("name"), read.get("priority"), read.get("active"),
                        read.get("requirement"), read.get("language")));
        assertEquals(Set.of("niord-nw", "niord-nm"), new HashSet<>(fieldOf(read, "domains", "domainId")));
        assertEquals(Set.of("LOCAL_WARNING", "COASTAL_WARNING"), new HashSet<>(valuesOf(read, "messageTypes")));
        assertEquals(body.get("scriptResourcePaths"), valuesOf(read, "scriptResourcePaths"), "the script paths in order");
        assertOrderColumn(3, scriptIndexes(typeId), "the type's script paths after the create");

        // Update: a domain swapped, a message type swapped, the script paths reordered with one removed and one
        // added, the plain fields changed and the type made inactive
        Map<String, Object> edit = type(typeId);
        edit.put("domains", list(map("domainId", "niord-nm"), map("domainId", "niord-fa")));
        edit.put("messageTypes", list("COASTAL_WARNING", "NAVAREA_WARNING"));
        edit.put("scriptResourcePaths", list("templates/it-rt/p3.ftl", "templates/it-rt/p1.ftl", "templates/it-rt/p4.js"));
        edit.put("name", "IT-RT NAVTEX renamed");
        edit.put("priority", 8);
        edit.put("requirement", "MANDATORY");
        edit.put("language", "da");
        edit.put("active", false);
        put("sysadmin", "/rest/promulgations/promulgation-type/" + typeId, edit);

        Map<String, Object> updated = type(typeId);
        assertEquals(List.of("IT-RT NAVTEX renamed", 8, "MANDATORY", "da", false),
                List.of(updated.get("name"), updated.get("priority"), updated.get("requirement"), updated.get("language"),
                        updated.get("active")), "every plain field the update changed, the active flag included");
        assertEquals(Set.of("niord-nm", "niord-fa"), new HashSet<>(fieldOf(updated, "domains", "domainId")));
        assertEquals(Set.of("COASTAL_WARNING", "NAVAREA_WARNING"), new HashSet<>(valuesOf(updated, "messageTypes")));
        assertEquals(List.of("templates/it-rt/p3.ftl", "templates/it-rt/p1.ftl", "templates/it-rt/p4.js"),
                valuesOf(updated, "scriptResourcePaths"));
        assertOrderColumn(3, scriptIndexes(typeId), "the type's script paths after the update");

        // Unchanged save
        Map<String, Long> rowsBefore = counts(TYPE_ROWS, typeId);
        put("sysadmin", "/rest/promulgations/promulgation-type/" + typeId, type(typeId));
        assertEquals(rowsBefore, counts(TYPE_ROWS, typeId), "the rows of an unchanged save");
        assertEquals(valuesOf(updated, "scriptResourcePaths"), valuesOf(type(typeId), "scriptResourcePaths"));

        // Delete: nothing of the type is left
        int id = ints("SELECT id FROM public.\"PromulgationType\" WHERE \"typeId\" = ?1", typeId).getFirst();
        delete("sysadmin", "/rest/promulgations/promulgation-type/" + typeId).statusCode(204);
        typeIds.remove(typeId);
        assertEquals(0L, count("SELECT count(*) FROM public.\"PromulgationType_Domain\" WHERE \"PromulgationType_id\" = ?1", id));
        assertEquals(0L, count("SELECT count(*) FROM public.\"PromulgationType_messageTypes\" WHERE \"PromulgationType_id\" = ?1", id));
        assertEquals(0L, count("SELECT count(*) FROM public.\"PromulgationType_scriptResourcePaths\" WHERE \"PromulgationType_id\" = ?1", id));
    }

    private List<Integer> scriptIndexes(String typeId) {
        return ints("SELECT s.\"indexNo\" FROM public.\"PromulgationType_scriptResourcePaths\" s JOIN public.\"PromulgationType\" p"
                + " ON p.id = s.\"PromulgationType_id\" WHERE p.\"typeId\" = ?1", typeId);
    }

    // ------------------------------------------------------------------------------------------- //
    // NAVTEX transmitters
    // ------------------------------------------------------------------------------------------- //

    @Test
    void navtexTransmittersKeepTheirAreasInOrderThroughCreateUpdateAnUnchangedSaveAndDelete() {
        String typeId = createType("navtex", list(map("domainId", "niord-nw")));
        List<Integer> areas = seedAreas(4);
        String base = "/rest/promulgation/navtex/transmitters/" + typeId;

        // Two transmitters with lists of different lengths, so that a mix-up between them shows
        String t1 = "IT-RT T1 " + unique();
        String t2 = "IT-RT T2 " + unique();
        post("sysadmin", base + "/transmitter", transmitter(typeId, t1, true, areas.get(0), areas.get(1), areas.get(2)));
        post("sysadmin", base + "/transmitter", transmitter(typeId, t2, false, areas.get(3)));

        Map<String, Map<String, Object>> read = transmitters(typeId);
        assertEquals(Set.of(t1, t2), read.keySet());
        assertEquals(List.of(areas.get(0), areas.get(1), areas.get(2)), areaIds(read.get(t1)), "T1's areas in order");
        assertEquals(List.of(areas.get(3)), areaIds(read.get(t2)), "T2's areas");
        assertEquals(true, read.get(t1).get("active"));
        assertEquals(false, read.get(t2).get("active"));
        assertOrderColumn(3, transmitterIndexes(typeId, t1), "T1's areas after the create");

        // Update T1: the second area removed, the third moved first, T2's area added; made inactive
        Map<String, Object> edit = read.get(t1);
        List<Map<String, Object>> t1Areas = listOf(edit, "areas");
        edit.put("areas", list(t1Areas.get(2), t1Areas.get(0), map("id", areas.get(3))));
        edit.put("active", false);
        put("sysadmin", base + "/transmitter/" + t1, edit);

        Map<String, Map<String, Object>> updated = transmitters(typeId);
        List<Integer> expected = List.of(areas.get(2), areas.get(0), areas.get(3));
        assertEquals(expected, areaIds(updated.get(t1)), "T1's areas in order after the update");
        assertEquals(false, updated.get(t1).get("active"));
        assertEquals(List.of(areas.get(3)), areaIds(updated.get(t2)), "T2 is untouched");
        assertOrderColumn(3, transmitterIndexes(typeId, t1), "T1's areas after the update");

        // Unchanged save of each
        long rows = count("SELECT count(*) FROM public.\"NavtexTransmitter_Area\" a JOIN public.\"NavtexTransmitter\" t"
                + " ON t.id = a.\"NavtexTransmitter_id\" JOIN public.\"PromulgationType\" p ON p.id = t.\"promulgationType_id\""
                + " WHERE p.\"typeId\" = ?1", typeId);
        put("sysadmin", base + "/transmitter/" + t1, updated.get(t1));
        put("sysadmin", base + "/transmitter/" + t2, updated.get(t2));
        Map<String, Map<String, Object>> resaved = transmitters(typeId);
        assertEquals(expected, areaIds(resaved.get(t1)), "T1's areas after an unchanged save");
        assertEquals(List.of(areas.get(3)), areaIds(resaved.get(t2)), "T2's areas after an unchanged save");
        assertEquals(rows, count("SELECT count(*) FROM public.\"NavtexTransmitter_Area\" a JOIN public.\"NavtexTransmitter\" t"
                + " ON t.id = a.\"NavtexTransmitter_id\" JOIN public.\"PromulgationType\" p ON p.id = t.\"promulgationType_id\""
                + " WHERE p.\"typeId\" = ?1", typeId), "the area rows of an unchanged save");

        // Delete T1: its area rows go, the areas and T2 stay
        int t1Id = ints("SELECT id FROM public.\"NavtexTransmitter\" WHERE name = ?1", t1).getFirst();
        delete("sysadmin", base + "/transmitter/" + t1).statusCode(204);
        assertEquals(0L, count("SELECT count(*) FROM public.\"NavtexTransmitter_Area\" WHERE \"NavtexTransmitter_id\" = ?1", t1Id));
        assertEquals(Set.of(t2), transmitters(typeId).keySet());
        assertEquals(4L, count("SELECT count(*) FROM public.\"Area\" WHERE id IN (?1, ?2, ?3, ?4)",
                areas.get(0), areas.get(1), areas.get(2), areas.get(3)), "the areas outlive the transmitter");
    }

    private static Map<String, Object> transmitter(String typeId, String name, boolean active, Integer... areaIds) {
        List<Map<String, Object>> areas = new ArrayList<>();
        for (Integer id : areaIds) {
            areas.add(map("id", id));
        }
        return map("promulgationType", map("typeId", typeId), "name", name, "active", active, "areas", areas);
    }

    private static Map<String, Map<String, Object>> transmitters(String typeId) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        getList("sysadmin", "/rest/promulgation/navtex/transmitters/" + typeId + "/all")
                .forEach(t -> result.put((String) t.get("name"), t));
        return result;
    }

    private static List<Integer> areaIds(Map<String, Object> owner) {
        return fieldOf(owner, "areas", "id").stream().map(v -> ((Number) v).intValue()).toList();
    }

    private List<Integer> transmitterIndexes(String typeId, String name) {
        return ints("SELECT a.\"areas_ORDER\" FROM public.\"NavtexTransmitter_Area\" a JOIN public.\"NavtexTransmitter\" t"
                + " ON t.id = a.\"NavtexTransmitter_id\" WHERE t.name = ?1", name);
    }

    // ------------------------------------------------------------------------------------------- //
    // SafetyNET areas
    // ------------------------------------------------------------------------------------------- //

    /**
     * SafetyNET areas of each of the four shapes through create, list, update and delete, on a type in a domain (the
     * answer maps the type with its domains). Each write answers the area and stores its own fields, the list answers
     * what is stored, an update changes the row and adds none, the delete removes the row. Until the fix of PROM6
     * (2026-11-12) every write and the list answered 500 after the write took; this test pinned that.
     */
    @Test
    void safetyNetAreasOfEachShapeKeepTheirFieldsThroughCreateListUpdateAndDelete() {
        String typeId = createType("safetynet", list(map("domainId", "niord-nw")));
        String base = "/rest/promulgation/safetynet/areas/" + typeId;
        Map<String, Object> type = map("typeId", typeId);

        List<Map<String, Object>> areas = List.of(
                map("type", "CIRCULAR", "promulgationType", type, "name", "IT-RT circle", "active", true, "sortOrder", 1,
                        "repetition", "REPETITION_11", "messageAreaPrefix", "C1", "center", "56 00N 011 00E", "radius", "20"),
                map("type", "RECTANGULAR", "promulgationType", type, "name", "IT-RT rectangle", "active", true, "sortOrder", 2,
                        "repetition", "REPETITION_16", "messageAreaPrefix", "R1", "swCorner", "55 00N 010 00E",
                        "northings", "60", "eastings", "120"),
                map("type", "COASTAL", "promulgationType", type, "name", "IT-RT coastal", "active", false, "sortOrder", 3,
                        "repetition", "REPETITION_01", "coastalArea", "B"),
                map("type", "NAVAREA", "promulgationType", type, "name", "IT-RT navarea", "active", true, "sortOrder", 4,
                        "repetition", "REPETITION_42", "navarea", "XIX"));
        List<List<Object>> expected = List.of(
                List.of("CIRCULAR", "IT-RT circle", true, 1, "C1", "56 00N 011 00E", "20", "", "", "", "", "", "REPETITION_11"),
                List.of("RECTANGULAR", "IT-RT rectangle", true, 2, "R1", "", "", "55 00N 010 00E", "60", "120", "", "", "REPETITION_16"),
                List.of("COASTAL", "IT-RT coastal", false, 3, "", "", "", "", "", "", "B", "", "REPETITION_01"),
                List.of("NAVAREA", "IT-RT navarea", true, 4, "", "", "", "", "", "", "", "XIX", "REPETITION_42"));
        for (int i = 0; i < areas.size(); i++) {
            Map<String, Object> answer = post("sysadmin", base + "/area", areas.get(i));
            assertEquals(expected.get(i), answered(answer), "the create answers the area");
            assertEquals(typeId, ((Map<?, ?>) answer.get("promulgationType")).get("typeId"));
        }
        assertEquals(List.of(
                        List.of("SafetyNetCircularArea", "IT-RT circle", true, 1, "C1", "56 00N 011 00E", "20", "", "", "", "", ""),
                        List.of("SafetyNetRectangularArea", "IT-RT rectangle", true, 2, "R1", "", "", "55 00N 010 00E", "60", "120", "", ""),
                        List.of("SafetyNetCoastalArea", "IT-RT coastal", false, 3, "", "", "", "", "", "", "B", ""),
                        List.of("SafetyNetNavareaArea", "IT-RT navarea", true, 4, "", "", "", "", "", "", "", "XIX")),
                storedAreas(typeId), "each shape stores its own fields");
        assertEquals(List.of(1, 2, 0, 3), ints("SELECT repetition FROM public.\"SafetyNetArea\" a JOIN public.\"PromulgationType\" p"
                + " ON p.id = a.\"promulgationType_id\" WHERE p.\"typeId\" = ?1 ORDER BY a.\"sortOrder\"", typeId),
                "the repetitions, as stored (ordinals)");

        // The list answers what is stored, in sort order, with the type's domains
        List<Map<String, Object>> listed = getList("sysadmin", base + "/all");
        assertEquals(expected, listed.stream().map(PromulgationRoundTripITTest::answered).toList(), "the list");
        assertEquals(List.of("niord-nw"), fieldOf((Map<String, Object>) listed.getFirst().get("promulgationType"),
                "domains", "domainId"), "the type's domains in the list's answer");

        // An update of the circle
        Map<String, Object> circle = new LinkedHashMap<>(areas.getFirst());
        circle.put("radius", "25");
        circle.put("active", false);
        assertEquals(List.of("CIRCULAR", "IT-RT circle", false, 1, "C1", "56 00N 011 00E", "25", "", "", "", "", "", "REPETITION_11"),
                answered(put("sysadmin", base + "/area/IT-RT circle", circle)), "the update answers the changed area");
        assertEquals(List.of("SafetyNetCircularArea", "IT-RT circle", false, 1, "C1", "56 00N 011 00E", "25", "", "", "", "", ""),
                storedAreas(typeId).getFirst(), "the circle after its update");
        assertEquals(4, storedAreas(typeId).size(), "the update adds no row");
        assertEquals(List.of(false, "25"), List.of(getList("sysadmin", base + "/all").getFirst().get("active"),
                getList("sysadmin", base + "/all").getFirst().get("radius")), "the list after the update");

        // The delete
        delete("sysadmin", base + "/area/IT-RT coastal").statusCode(204);
        assertEquals(List.of("IT-RT circle", "IT-RT rectangle", "IT-RT navarea"),
                storedAreas(typeId).stream().map(r -> r.get(1)).toList());
        assertEquals(List.of("IT-RT circle", "IT-RT rectangle", "IT-RT navarea"),
                getList("sysadmin", base + "/all").stream().map(a -> a.get("name")).toList(), "the list after the delete");
    }

    /** An area as the REST interface answers it, in the order of {@link #storedAreas}, with its repetition last */
    private static List<Object> answered(Map<String, Object> area) {
        List<Object> row = new ArrayList<>(List.of(area.get("type"), area.get("name"), area.get("active"), area.get("sortOrder")));
        for (String field : List.of("messageAreaPrefix", "center", "radius", "swCorner", "northings", "eastings",
                "coastalArea", "navarea", "repetition")) {
            row.add(area.get(field) == null ? "" : area.get(field));
        }
        return row;
    }

    private List<List<Object>> storedAreas(String typeId) {
        return rows("SELECT a.\"DTYPE\", a.name, a.active, a.\"sortOrder\", coalesce(a.\"messageAreaPrefix\", ''),"
                + " coalesce(a.center, ''), coalesce(a.radius, ''), coalesce(a.\"swCorner\", ''), coalesce(a.northings, ''),"
                + " coalesce(a.eastings, ''), coalesce(a.\"coastalArea\", ''), coalesce(a.navarea, '')"
                + " FROM public.\"SafetyNetArea\" a JOIN public.\"PromulgationType\" p ON p.id = a.\"promulgationType_id\""
                + " WHERE p.\"typeId\" = ?1 ORDER BY a.\"sortOrder\"", typeId).stream()
                .map(r -> r.stream().map(v -> v instanceof Number n ? (Object) n.intValue() : v).toList())
                .toList();
    }

    // ------------------------------------------------------------------------------------------- //
    // Twitter settings: PROM7
    // ------------------------------------------------------------------------------------------- //

    /**
     * PROM7 (be/docs/potential-errors.md): the Twitter settings' create, update and read answer 500 after the write
     * took, as the SafetyNET areas did before the fix of PROM6. What is stored is checked in SQL.
     *
     * <p>When PROM7 is fixed, the right assertions are {@code statusCode(200)} on each call and the read over REST
     * compared with the stored values (the two secrets excepted, which the read never returns).
     */
    @Test
    void twitterSettingsAreStoredButTheirWritesAndReadAnswer500_documentsBug() {
        String typeId = createType("twitter", list(map("domainId", "niord-nw")));
        Map<String, Object> settings = map("promulgationType", map("typeId", typeId), "format", "${title}",
                "includeThumbnail", true, "apiKey", "k1", "apiSecret", "s1", "accessToken", "t1", "accessTokenSecret", "ts1");
        postRaw("sysadmin", "/rest/promulgation/twitter-settings", settings).statusCode(500);
        assertEquals(List.of(List.of("${title}", true, "k1", "s1", "t1", "ts1")), storedSettings(typeId), "stored by the create");

        as("sysadmin").when().get("/rest/promulgation/twitter-settings/" + typeId).then().statusCode(500);

        settings.put("format", "${short-id} ${title}");
        settings.put("includeThumbnail", false);
        putRaw("sysadmin", "/rest/promulgation/twitter-settings/" + typeId, settings).statusCode(500);
        assertEquals(List.of(List.of("${short-id} ${title}", false, "k1", "s1", "t1", "ts1")), storedSettings(typeId),
                "stored by the update, one row");
    }

    private List<List<Object>> storedSettings(String typeId) {
        return rows("SELECT s.format, s.\"includeThumbnail\", s.\"apiKey\", s.\"apiSecret\", s.\"accessToken\","
                + " s.\"accessTokenSecret\" FROM public.\"TwitterSettings\" s JOIN public.\"PromulgationType\" p"
                + " ON p.id = s.\"promulgationType_id\" WHERE p.\"typeId\" = ?1", typeId);
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    private String createType(String serviceId, List<Map<String, Object>> domains) {
        String typeId = "it-rt-" + serviceId + "-" + unique();
        post("sysadmin", "/rest/promulgations/promulgation-type", map(
                "typeId", typeId, "serviceId", serviceId, "name", "IT-RT " + serviceId, "priority", 90, "active", false,
                "requirement", "OPTIONAL", "domains", domains));
        typeIds.add(typeId);
        return typeId;
    }

    private static Map<String, Object> type(String typeId) {
        return getMap("sysadmin", "/rest/promulgations/promulgation-type/" + typeId);
    }

    /** The first n areas of the seed below a root, by id */
    private List<Integer> seedAreas(int n) {
        List<Integer> ids = ints("SELECT id FROM public.\"Area\" WHERE parent_id IS NOT NULL ORDER BY id LIMIT " + n);
        assertEquals(n, ids.size(), "the seed holds areas");
        return ids;
    }
}
