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

import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.repo.RepoStore;
import org.niord.core.util.UidUtils;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save, reload, save again for the administrators' own records, through the REST interface as the role that
 * manages each: reports (FmReport with domains, properties and parameters), script resources and their history,
 * firing schedules and firing periods (several on one day), Niord integrations with several series mappings,
 * message tags (private, domain, public; locked; with messages), saved message filters, and comments with an
 * acknowledgement.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
@SuppressWarnings("unchecked")
class AdminDataRoundTripITTest extends RoundTripSupport {

    @Autowired
    RepoStore store;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Reports
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aReportKeepsItsDomainsPropertiesAndParameters() {
        String reportId = "it-rt-report-" + unique();
        post("sysadmin", "/rest/message-reports/report", map("reportId", reportId, "name", "IT-RT report", "sortOrder", 7,
                "templatePath", "templates/it-rt/report.ftl",
                "domains", list(map("domainId", "niord-nw"), map("domainId", "niord-nm")),
                "properties", map("pageOrientation", "landscape", "pageSize", "A3", "mapThumbnails", true),
                "params", map("title", "Weekly", "count", 3)));
        cleanups.add(() -> delete("sysadmin", "/rest/message-reports/report/" + reportId));

        Map<String, Object> read = report(reportId);
        assertEquals(List.of("IT-RT report", 7, "templates/it-rt/report.ftl"),
                List.of(read.get("name"), read.get("sortOrder"), read.get("templatePath")));
        assertEquals(Set.of("niord-nw", "niord-nm"), new HashSet<>(fieldOf(read, "domains", "domainId")));
        assertEquals(Map.of("pageOrientation", "landscape", "pageSize", "A3", "mapThumbnails", true), read.get("properties"));
        assertEquals(Map.of("title", "Weekly", "count", 3), read.get("params"));

        Map<String, Object> edit = report(reportId);
        edit.put("domains", list(map("domainId", "niord-nm"), map("domainId", "niord-fa"), map("domainId", "niord-almanac")));
        edit.put("properties", map("pageOrientation", "portrait", "pageSize", "A3"));
        edit.put("params", map("title", "Daily"));
        edit.put("sortOrder", 8);
        put("sysadmin", "/rest/message-reports/report/" + reportId, edit);
        Map<String, Object> updated = report(reportId);
        assertEquals(Set.of("niord-nm", "niord-fa", "niord-almanac"), new HashSet<>(fieldOf(updated, "domains", "domainId")));
        assertEquals(Map.of("pageOrientation", "portrait", "pageSize", "A3"), updated.get("properties"));
        assertEquals(Map.of("title", "Daily"), updated.get("params"));
        assertEquals(8, updated.get("sortOrder"));

        String domainRows = "SELECT count(*) FROM public.\"FmReport_Domain\" WHERE \"FmReport_id\" ="
                + " (SELECT id FROM public.\"FmReport\" WHERE \"reportId\" = ?1)";
        long before = count(domainRows, reportId);
        put("sysadmin", "/rest/message-reports/report/" + reportId, report(reportId));
        assertEquals(before, count(domainRows, reportId), "the domain rows of an unchanged save");
        assertEquals(updated, report(reportId), "an unchanged save changes nothing");

        int id = ints("SELECT id FROM public.\"FmReport\" WHERE \"reportId\" = ?1", reportId).getFirst();
        delete("sysadmin", "/rest/message-reports/report/" + reportId).statusCode(204);
        assertEquals(0L, count("SELECT count(*) FROM public.\"FmReport_Domain\" WHERE \"FmReport_id\" = ?1", id));
    }

    private static Map<String, Object> report(String reportId) {
        return getList("sysadmin", "/rest/message-reports/all").stream().filter(r -> reportId.equals(r.get("reportId")))
                .findFirst().map(LinkedHashMap::new).orElseThrow(() -> new AssertionError("no report " + reportId));
    }

    // ------------------------------------------------------------------------------------------- //
    // Script resources
    // ------------------------------------------------------------------------------------------- //

    /**
     * A resource created and changed twice through the editor's REST calls keeps its content byte for byte and numbers
     * its history 1, 2, 3. (SCRIPT9 in be/docs/potential-errors.md describes a change numbered as its previous
     * entry; over these calls it does not occur.)
     */
    @Test
    void aScriptResourceKeepsItsContentAndNumbersItsHistory() {
        String path = "templates/it-rt/script-" + unique() + ".ftl";
        Map<String, Object> created = post("sysadmin", "/rest/script-resources/script-resource",
                map("type", "FM", "path", path, "content", "<#-- one -->\nÆble ${message.title!}\n"));
        int id = ((Number) created.get("id")).intValue();
        cleanups.add(() -> delete("sysadmin", "/rest/script-resources/script-resource/" + id));
        assertEquals("<#-- one -->\nÆble ${message.title!}\n", resource(id).get("content"), "the content, byte for byte");

        Map<String, Object> edit = resource(id);
        edit.put("content", "<#-- two -->\n");
        put("sysadmin", "/rest/script-resources/script-resource/" + id, edit);
        edit.put("content", "<#-- three -->\n");
        put("sysadmin", "/rest/script-resources/script-resource/" + id, edit);
        assertEquals("<#-- three -->\n", resource(id).get("content"));
        assertEquals(path, resource(id).get("path"));

        List<Map<String, Object>> history = getList("sysadmin", "/rest/script-resources/script-resource/" + id + "/history");
        List<Integer> versions = history.stream().map(h -> ((Number) h.get("version")).intValue()).sorted().toList();
        assertEquals(List.of(1, 2, 3), versions, "one history version per save");

        delete("sysadmin", "/rest/script-resources/script-resource/" + id).statusCode(204);
        assertEquals(0L, count("SELECT count(*) FROM public.\"ScriptResourceHistory\" WHERE \"resource_id\" = ?1", id),
                "the delete removes the history");
    }

    private static Map<String, Object> resource(int id) {
        return getList("sysadmin", "/rest/script-resources/all").stream()
                .filter(r -> ((Number) r.get("id")).intValue() == id).findFirst()
                .map(LinkedHashMap::new).orElseThrow(() -> new AssertionError("no resource " + id));
    }

    // ------------------------------------------------------------------------------------------- //
    // Firing schedules and periods
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aFiringScheduleKeepsItsDomainsSeriesAndDays() {
        assertEquals(0L, count("SELECT count(*) FROM public.\"FiringSchedule\" s JOIN public.\"Domain\" d ON d.id = s.domain_id"
                + " WHERE d.\"domainId\" = 'niord-fa'"), "the seed has no schedule for niord-fa");
        Map<String, Object> created = post("sysadmin", "/rest/firing-schedules/firing-schedule", map(
                "domain", map("domainId", "niord-fa"), "targetDomain", map("domainId", "niord-nw"),
                "targetSeriesId", "dma-nw", "scheduleDays", 3, "active", false));
        int id = ((Number) created.get("id")).intValue();
        cleanups.add(() -> delete("sysadmin", "/rest/firing-schedules/firing-schedule/" + id));

        Map<String, Object> read = schedule(id);
        assertEquals(List.of("niord-fa", "niord-nw", "dma-nw", 3, false),
                List.of(((Map<String, Object>) read.get("domain")).get("domainId"),
                        ((Map<String, Object>) read.get("targetDomain")).get("domainId"),
                        read.get("targetSeriesId"), read.get("scheduleDays"), read.get("active")));

        Map<String, Object> edit = schedule(id);
        edit.put("scheduleDays", 5);
        edit.put("targetSeriesId", "dma-nw-local");
        put("sysadmin", "/rest/firing-schedules/firing-schedule/" + id, edit);
        Map<String, Object> updated = schedule(id);
        assertEquals(List.of("dma-nw-local", 5), List.of(updated.get("targetSeriesId"), updated.get("scheduleDays")));

        put("sysadmin", "/rest/firing-schedules/firing-schedule/" + id, schedule(id));
        assertEquals(List.of("niord-fa", "niord-nw", "dma-nw-local", 5, false),
                List.of(((Map<String, Object>) schedule(id).get("domain")).get("domainId"),
                        ((Map<String, Object>) schedule(id).get("targetDomain")).get("domainId"),
                        schedule(id).get("targetSeriesId"), schedule(id).get("scheduleDays"), schedule(id).get("active")),
                "an unchanged save changes nothing");
        assertEquals(List.of(id), ints("SELECT \"firingSchedule_id\" FROM public.\"Domain\" WHERE \"domainId\" = 'niord-fa'"),
                "the domain points at its schedule");

        delete("sysadmin", "/rest/firing-schedules/firing-schedule/" + id).statusCode(204);
        assertEquals(0L, count("SELECT count(*) FROM public.\"FiringSchedule\" WHERE id = ?1", id));
        assertEquals(1L, count("SELECT count(*) FROM public.\"Domain\" WHERE \"domainId\" = 'niord-fa' AND \"firingSchedule_id\" IS NULL"),
                "the delete leaves the domain without a schedule");
    }

    private static Map<String, Object> schedule(int id) {
        return getList("sysadmin", "/rest/firing-schedules/all").stream()
                .filter(s -> ((Number) s.get("id")).intValue() == id).findFirst()
                .map(LinkedHashMap::new).orElseThrow(() -> new AssertionError("no schedule " + id));
    }

    @Test
    void severalFiringPeriodsOnOneDayKeepTheirTimesThroughCreateUpdateAndAnUnchangedSave() {
        // A firing area of the firing-area domain, and a day far ahead with a period on the next day beside it
        Integer areaId = firstFiringArea();
        long day = Instant.parse("2031-06-15T10:00:00Z").toEpochMilli();
        long hour = 3_600_000L;
        long eight = Instant.parse("2031-06-15T06:00:00Z").toEpochMilli(); // 08:00 in Copenhagen (CEST)
        cleanups.add(() -> execute("DELETE FROM public.\"FiringPeriod\" WHERE area_id = ?1 AND \"fromDate\" >= ?2",
                areaId, java.sql.Timestamp.from(Instant.parse("2031-06-14T00:00:00Z"))));

        putPeriods(areaId, day + 24 * hour, list(period(null, eight + 24 * hour, eight + 26 * hour)));
        putPeriods(areaId, day, list(period(null, eight, eight + 2 * hour), period(null, eight + 4 * hour, eight + 6 * hour),
                period(null, eight + 8 * hour, eight + 10 * hour)));

        List<Map<String, Object>> read = periods(areaId, day);
        assertEquals(List.of(List.of(eight, eight + 2 * hour), List.of(eight + 4 * hour, eight + 6 * hour),
                List.of(eight + 8 * hour, eight + 10 * hour)), times(read), "three periods on the day");

        // Update: the middle one removed, the first one lengthened, one added in the evening
        List<Map<String, Object>> edit = new ArrayList<>(read);
        edit.remove(1);
        edit.get(0).put("toDate", eight + 3 * hour);
        edit.add(period(null, eight + 12 * hour, eight + 13 * hour));
        putPeriods(areaId, day, edit);
        List<Map<String, Object>> updated = periods(areaId, day);
        assertEquals(List.of(List.of(eight, eight + 3 * hour), List.of(eight + 8 * hour, eight + 10 * hour),
                List.of(eight + 12 * hour, eight + 13 * hour)), times(updated), "the periods after the update");
        assertEquals(1, periods(areaId, day + 24 * hour).size(), "the next day's period is untouched");

        // Unchanged save: the same rows
        List<Integer> idsBefore = ints("SELECT id FROM public.\"FiringPeriod\" WHERE area_id = ?1 AND \"fromDate\" >= ?2 ORDER BY id",
                areaId, java.sql.Timestamp.from(Instant.parse("2031-06-14T00:00:00Z")));
        putPeriods(areaId, day, periods(areaId, day));
        assertEquals(idsBefore, ints("SELECT id FROM public.\"FiringPeriod\" WHERE area_id = ?1 AND \"fromDate\" >= ?2 ORDER BY id",
                areaId, java.sql.Timestamp.from(Instant.parse("2031-06-14T00:00:00Z"))), "an unchanged save keeps every row");
        assertEquals(times(updated), times(periods(areaId, day)));

        // Emptied: the day's rows go, the next day's stays
        putPeriods(areaId, day, list());
        assertEquals(List.of(), periods(areaId, day));
        assertEquals(1, periods(areaId, day + 24 * hour).size(), "the next day's period stays");
    }

    private Integer firstFiringArea() {
        List<Map<String, Object>> areas = given().auth().oauth2(accessToken("editor")).header("NiordDomain", "niord-fa")
                .when().get("/rest/firing-schedules/search-firing-area-periods?inactive=true&date="
                        + Instant.parse("2031-06-15T10:00:00Z").toEpochMilli())
                .then().statusCode(200).extract().jsonPath().getList("");
        assertTrue(!areas.isEmpty(), "the firing-area domain has firing areas");
        return ((Number) ((Map<String, Object>) areas.getFirst().get("area")).get("id")).intValue();
    }

    private static RequestSpecification inFiringAreas() {
        return given().auth().oauth2(accessToken("editor")).header("NiordDomain", "niord-fa").contentType("application/json");
    }

    private static void putPeriods(Integer areaId, long date, List<Map<String, Object>> periods) {
        inFiringAreas().body(map("area", map("id", areaId), "firingPeriods", periods))
                .when().put("/rest/firing-schedules/firing-area-periods?date=" + date).then().statusCode(200);
    }

    private static List<Map<String, Object>> periods(Integer areaId, long date) {
        List<Map<String, Object>> areas = inFiringAreas().when()
                .get("/rest/firing-schedules/search-firing-area-periods?inactive=true&area=" + areaId + "&date=" + date)
                .then().statusCode(200).extract().jsonPath().getList("");
        return areas.stream().filter(a -> ((Number) ((Map<String, Object>) a.get("area")).get("id")).intValue() == areaId)
                .findFirst().map(a -> listOf(a, "firingPeriods")).orElseThrow().stream()
                .map(p -> (Map<String, Object>) new LinkedHashMap<>(p))
                .sorted(java.util.Comparator.comparingLong(p -> millis(p.get("fromDate"))))
                .toList();
    }

    private static Map<String, Object> period(Integer id, long from, long to) {
        Map<String, Object> period = map("fromDate", from, "toDate", to);
        if (id != null) {
            period.put("id", id);
        }
        return period;
    }

    private static List<List<Long>> times(List<Map<String, Object>> periods) {
        return periods.stream().map(p -> List.of(millis(p.get("fromDate")), millis(p.get("toDate")))).toList();
    }

    private static long millis(Object date) {
        return date instanceof Number n ? n.longValue() : Instant.parse((String) date).toEpochMilli();
    }

    // ------------------------------------------------------------------------------------------- //
    // Niord integrations
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aNiordIntegrationKeepsItsSeriesMappings() {
        String url = "http://it-rt-" + System.nanoTime();
        Map<String, Object> created = post("sysadmin", "/rest/niord-integrations/niord-integration", map("url", url,
                "active", false, "assignNewUids", true, "createBaseData", true,
                "messageSeriesMappings", list(map("sourceSeriesId", "x-nw", "targetSeriesId", "dma-nw"),
                        map("sourceSeriesId", "x-nm", "targetSeriesId", "dma-nm"),
                        map("sourceSeriesId", "x-fa", "targetSeriesId", "dma-fa"))));
        int id = ((Number) created.get("id")).intValue();
        cleanups.add(() -> delete("sysadmin", "/rest/niord-integrations/niord-integration/" + id));

        Map<String, Object> read = getMap("sysadmin", "/rest/niord-integrations/niord-integration/" + id);
        assertEquals(List.of(url, false, true, true), List.of(read.get("url"), read.get("active"), read.get("assignNewUids"),
                read.get("createBaseData")));
        assertEquals(Set.of("x-nw>dma-nw", "x-nm>dma-nm", "x-fa>dma-fa"), mappings(read));

        Map<String, Object> edit = getMap("sysadmin", "/rest/niord-integrations/niord-integration/" + id);
        List<Map<String, Object>> m = listOf(edit, "messageSeriesMappings");
        m.removeIf(x -> "x-nm".equals(x.get("sourceSeriesId")));
        m.stream().filter(x -> "x-fa".equals(x.get("sourceSeriesId"))).forEach(x -> x.put("targetSeriesId", "dma-nm"));
        m.add(map("sourceSeriesId", "x-local", "targetSeriesId", "dma-nw-local"));
        edit.put("assignNewUids", false);
        put("sysadmin", "/rest/niord-integrations/niord-integration/" + id, edit);
        Map<String, Object> updated = getMap("sysadmin", "/rest/niord-integrations/niord-integration/" + id);
        assertEquals(Set.of("x-nw>dma-nw", "x-fa>dma-nm", "x-local>dma-nw-local"), mappings(updated));
        assertEquals(false, updated.get("assignNewUids"));

        String rows = "SELECT count(*) FROM public.\"MessageSeriesMapping\" WHERE \"niordIntegration_id\" = ?1";
        long before = count(rows, id);
        long all = count("SELECT count(*) FROM public.\"MessageSeriesMapping\"");
        put("sysadmin", "/rest/niord-integrations/niord-integration/" + id,
                getMap("sysadmin", "/rest/niord-integrations/niord-integration/" + id));
        assertEquals(before, count(rows, id), "the mapping rows of an unchanged save");
        assertEquals(all, count("SELECT count(*) FROM public.\"MessageSeriesMapping\""), "no orphan mapping");
        assertEquals(mappings(updated), mappings(getMap("sysadmin", "/rest/niord-integrations/niord-integration/" + id)));

        delete("sysadmin", "/rest/niord-integrations/niord-integration/" + id).statusCode(204);
        assertEquals(0L, count(rows, id), "the delete removes the mappings");
    }

    private static Set<String> mappings(Map<String, Object> integration) {
        Set<String> result = new HashSet<>();
        listOf(integration, "messageSeriesMappings").forEach(m -> result.add(m.get("sourceSeriesId") + ">" + m.get("targetSeriesId")));
        return result;
    }

    // ------------------------------------------------------------------------------------------- //
    // Tags, filters and comments: they need messages
    // ------------------------------------------------------------------------------------------- //

    @Test
    void messageTagsOfEachTypeKeepTheirMessagesThroughAnUnchangedSaveAndADelete() {
        String m1 = message("tag 1");
        String m2 = message("tag 2");
        String m3 = message("tag 3");
        for (String type : List.of("PRIVATE", "DOMAIN", "PUBLIC")) {
            // A PRIVATE tag is seen by its owner only, so the editor reads it; a locked one is pinned on its own below
            boolean shared = !"PRIVATE".equals(type);
            String reader = shared ? "admin" : "editor";
            Map<String, Object> tag = post("editor", "/rest/tags/tag", map("type", type, "name", "IT-RT " + type + " " + unique(),
                    "locked", false, "expiryDate", Instant.parse("2999-01-01T00:00:00Z").toEpochMilli()));
            String tagId = (String) tag.get("tagId");
            cleanups.add(() -> delete(reader, "/rest/tags/tag/" + tagId));
            as("editor").body(list(m1, m2, m3)).when().put("/rest/tags/tag/" + tagId + "/add-messages").then().statusCode(200);
            as("editor").body(list(m2)).when().put("/rest/tags/tag/" + tagId + "/remove-messages").then().statusCode(200);
            assertEquals(Set.of(m1, m3), tagged(tagId), type + ": the tag's messages");

            // Rename it (and lock a shared one), then save it unchanged: its messages stay
            Map<String, Object> edit = tag(reader, tagId);
            edit.put("name", "IT-RT renamed " + type);
            edit.put("locked", shared);
            put("editor", "/rest/tags/tag/" + tagId, edit);
            Map<String, Object> updated = tag(reader, tagId);
            assertEquals(List.of("IT-RT renamed " + type, shared, type), List.of(updated.get("name"), updated.get("locked"), updated.get("type")));
            assertEquals(Set.of(m1, m3), tagged(tagId), type + ": the messages after the update");

            if (shared) {
                // Locked: an editor may no longer change it, an admin may
                putRaw("editor", "/rest/tags/tag/" + tagId, tag(reader, tagId)).statusCode(403);
            }
            put(reader, "/rest/tags/tag/" + tagId, tag(reader, tagId));
            assertEquals(Set.of(m1, m3), tagged(tagId), type + ": the messages after an unchanged save");
            assertEquals(2, ((Number) tag(reader, tagId).get("messageCount")).intValue());

            // Delete: the join rows go, the messages stay
            assertEquals("true", delete(reader, "/rest/tags/tag/" + tagId).statusCode(200).extract().asString().trim());
            assertEquals(0L, count("SELECT count(*) FROM public.\"MessageTag\" WHERE \"tagId\" = ?1", tagId), type + " deleted");
            assertEquals(0L, count("SELECT count(*) FROM public.\"MessageTag_Message\" WHERE tags_id NOT IN"
                    + " (SELECT id FROM public.\"MessageTag\")"), "no join row of a deleted tag");
            assertEquals(3L, count("SELECT count(*) FROM public.\"Message\" WHERE uid IN (?1, ?2, ?3)", m1, m2, m3));
        }
    }

    private Set<String> tagged(String tagId) {
        return new HashSet<>(strings("SELECT m.uid FROM public.\"MessageTag_Message\" j JOIN public.\"MessageTag\" t ON t.id = j.tags_id"
                + " JOIN public.\"Message\" m ON m.id = j.messages_id WHERE t.\"tagId\" = ?1", tagId));
    }

    /**
     * A user may lock a tag, and only an admin may change a locked one ({@code MessageTagRestService.requireModifiable}),
     * but a PRIVATE tag is found for its owner only ({@code MessageTagService.findTags}, {@code validateAccess}). Before
     * TAG1 was fixed, an editor who locked a private tag could no longer rename, unlock, empty or delete it (403), and
     * no admin could reach it: the tag was stuck. The lock now leaves a PRIVATE tag to its owner, locked or not. An
     * admin still cannot see another user's private tag, so an admin's unlock answers 404 and changes nothing.
     */
    @Test
    void aPrivateTagItsOwnerLockedStaysTheOwners() {
        String m1 = message("locked private tag");
        String name = "IT-RT locked " + unique();
        Map<String, Object> tag = post("editor", "/rest/tags/tag", map("type", "PRIVATE", "name", name, "locked", false));
        String tagId = (String) tag.get("tagId");
        // Removed in SQL if the test fails half way, the join rows first (the cleanups run last-added first)
        cleanups.add(() -> execute("DELETE FROM public.\"MessageTag\" WHERE \"tagId\" = ?1", tagId));
        cleanups.add(() -> execute("DELETE FROM public.\"MessageTag_Message\" WHERE tags_id = (SELECT id FROM public.\"MessageTag\""
                + " WHERE \"tagId\" = ?1)", tagId));
        as("editor").body(list(m1)).when().put("/rest/tags/tag/" + tagId + "/add-messages").then().statusCode(200);
        Map<String, Object> lock = tag("editor", tagId);
        lock.put("locked", true);
        put("editor", "/rest/tags/tag/" + tagId, lock);
        String lockedRow = "SELECT count(*) FROM public.\"MessageTag\" WHERE \"tagId\" = ?1 AND locked";
        assertEquals(1L, count(lockedRow, tagId), "the owner locked it");

        // An admin cannot see another user's private tag: the unlock answers 404, the delete false, and nothing changes
        assertEquals(List.of(404, "false", 1L), List.of(
                as("admin").when().put("/rest/tags/unlock-tag/" + tagId).then().extract().statusCode(),
                delete("admin", "/rest/tags/tag/" + tagId).extract().asString().trim(),
                count(lockedRow, tagId)), "an admin's unlock and delete; the locked row");

        // The owner renames it and empties it, while it is locked
        Map<String, Object> rename = tag("editor", tagId);
        rename.put("name", name + " renamed");
        assertEquals(List.of(200, 200), List.of(
                putRaw("editor", "/rest/tags/tag/" + tagId, rename).extract().statusCode(),
                delete("editor", "/rest/tags/tag/" + tagId + "/messages").extract().statusCode()),
                "the owner's rename and clear of the locked tag");
        assertEquals(List.of(name + " renamed", true, 0), List.of(tag("editor", tagId).get("name"),
                tag("editor", tagId).get("locked"), ((Number) tag("editor", tagId).get("messageCount")).intValue()));
        assertEquals(Set.of(), tagged(tagId), "no message left in the tag");

        // The owner unlocks it by a PUT
        Map<String, Object> unlock = tag("editor", tagId);
        unlock.put("locked", false);
        assertEquals(200, putRaw("editor", "/rest/tags/tag/" + tagId, unlock).extract().statusCode(), "the owner's unlock");
        assertEquals(false, tag("editor", tagId).get("locked"));

        // Locked again, the owner deletes it
        put("editor", "/rest/tags/tag/" + tagId, lock);
        assertEquals(1L, count(lockedRow, tagId), "locked again");
        assertEquals("true", delete("editor", "/rest/tags/tag/" + tagId).statusCode(200).extract().asString().trim(),
                "the owner's delete of the locked tag");
        assertEquals(0L, count("SELECT count(*) FROM public.\"MessageTag\" WHERE \"tagId\" = ?1", tagId), "no row left");
        assertEquals(1L, count("SELECT count(*) FROM public.\"Message\" WHERE uid = ?1", m1), "the message stays");
    }

    private static Map<String, Object> tag(String tagId) {
        return tag("admin", tagId);
    }

    private static Map<String, Object> tag(String role, String tagId) {
        List<Map<String, Object>> found = getList(role, "/rest/tags/tag/" + tagId);
        assertEquals(1, found.size(), "the tag " + tagId);
        return new LinkedHashMap<>(found.getFirst());
    }

    @Test
    void aSavedFilterIsReplacedByNameAndDeleted() {
        String name = "IT-RT filter " + unique();
        Map<String, Object> created = post("editor", "/rest/filters/filter", map("name", name,
                "parameters", "status=PUBLISHED&status=DRAFT&area=1&tag=x&sortBy=AREA&sortOrder=ASC"));
        int id = ((Number) created.get("id")).intValue();
        cleanups.add(() -> delete("editor", "/rest/filters/filter/" + id));
        assertEquals(map("id", id, "name", name, "parameters", "status=PUBLISHED&status=DRAFT&area=1&tag=x&sortBy=AREA&sortOrder=ASC"),
                new LinkedHashMap<>(getMap("editor", "/rest/filters/filter/" + id)));

        // Saved again under the same name: the one row is updated
        Map<String, Object> again = post("editor", "/rest/filters/filter", map("name", name, "parameters", "status=DRAFT"));
        assertEquals(id, ((Number) again.get("id")).intValue(), "the same filter");
        assertEquals("status=DRAFT", getMap("editor", "/rest/filters/filter/" + id).get("parameters"));
        assertEquals(1L, count("SELECT count(*) FROM public.\"MessageFilter\" WHERE name = ?1", name));

        delete("editor", "/rest/filters/filter/" + id).statusCode(204);
        assertEquals(0L, count("SELECT count(*) FROM public.\"MessageFilter\" WHERE name = ?1", name));
    }

    /**
     * A saved filter's query is stored in {@code MessageFilter.parameters}, and the administration app saves the
     * message list's whole request query ({@code named-filters.facade.ts}, {@code requestParams()}): about 20 areas
     * or categories with statuses and sort pass 255 characters. The column was a {@code character varying(255)}
     * ({@code V001__baseline_schema.sql}) until {@code V011__message_filter_parameters_text.sql} made it
     * {@code text}, and before that such a save answered 500 and stored nothing (FILTER1). Here a query of 1,015
     * characters, 40 areas and 40 categories, is stored whole and reads back as it was posted.
     */
    @Test
    void aSavedFilterWithALongQueryIsStoredWhole() {
        StringBuilder parameters = new StringBuilder("status=PUBLISHED&status=DRAFT&sortBy=AREA&sortOrder=ASC");
        for (int i = 1000; i < 1040; i++) {
            parameters.append("&area=").append(i).append("&category=").append(i);
        }
        assertTrue(parameters.length() > 1000, "a query far over the old 255 characters: " + parameters.length());
        String name = "IT-RT long filter " + unique();
        var response = postRaw("editor", "/rest/filters/filter", map("name", name, "parameters", parameters.toString()));
        int status = response.extract().statusCode();
        List<Object> stored = column("SELECT parameters FROM public.\"MessageFilter\" WHERE name = ?1", name);
        if (status == 200) {
            cleanups.add(() -> delete("editor", "/rest/filters/filter/" + ((Number) response.extract().path("id")).intValue()));
        }
        assertEquals(List.of(200, List.of(parameters.toString())), List.of(status, stored),
                "the save's status and the stored query (" + parameters.length() + " characters)");

        int id = ((Number) response.extract().path("id")).intValue();
        assertEquals(parameters.toString(), getMap("editor", "/rest/filters/filter/" + id).get("parameters"),
                "the query reads back whole");
    }

    @Test
    void aCommentKeepsItsTextAndAcknowledgement() {
        String uid = message("comment");
        String base = "/rest/messages/message/" + uid;
        Map<String, Object> created = post("editor", base + "/comment", map("comment", "First <b>comment</b> æøå"));
        int id = ((Number) created.get("id")).intValue();
        Map<String, Object> read = comment(uid, id);
        assertEquals("First <b>comment</b> æøå", read.get("comment"));
        assertNull(read.get("acknowledgeDate"));

        as("admin").when().put(base + "/comment/" + id + "/ack").then().statusCode(200);
        Map<String, Object> acked = comment(uid, id);
        assertNotNull(acked.get("acknowledgeDate"), "acknowledged");
        assertNotNull(acked.get("acknowledgedBy"));

        Map<String, Object> edit = comment(uid, id);
        edit.put("comment", "Edited comment");
        put("editor", base + "/comment/" + id, edit);
        Map<String, Object> edited = comment(uid, id);
        assertEquals("Edited comment", edited.get("comment"));
        assertEquals(1L, count("SELECT count(*) FROM public.\"Comment\" WHERE message_id = (SELECT id FROM public.\"Message\" WHERE uid = ?1)", uid),
                "one comment row");
    }

    private static Map<String, Object> comment(String uid, int id) {
        return getList("editor", "/rest/messages/message/" + uid + "/comments").stream()
                .filter(c -> ((Number) c.get("id")).intValue() == id).findFirst()
                .map(LinkedHashMap::new).orElseThrow(() -> new AssertionError("no comment " + id));
    }

    /** A draft NW message, removed after the test */
    private String message(String title) {
        String uid = UidUtils.newUid();
        String folder = as("editor").when().get("/rest/repo/new-temp-dir").then().statusCode(200).extract().path("path");
        cleanups.add(() -> {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.uid = :uid").setParameter("uid", uid).executeUpdate();
                em.createQuery("delete from Comment c where c.message.id in (select m.id from Message m where m.uid = :uid)")
                        .setParameter("uid", uid).executeUpdate();
                em.createQuery("select m from Message m where m.uid = :uid", Message.class).setParameter("uid", uid)
                        .getResultStream().findFirst().ifPresent(m -> {
                            m.getTags().forEach(t -> t.getMessages().remove(m));
                            em.remove(m);
                        });
            });
            store.deleteFolder(UidUtils.uidToHashedFolderPath("messages", uid));
            store.deleteFolder(folder);
        });
        postRaw("editor", "/rest/messages/message", map("id", uid, "repoPath", UidUtils.uidToHashedFolderPath("messages", uid),
                "editRepoPath", folder, "mainType", "NW", "type", "LOCAL_WARNING", "status", "DRAFT", "autoTitle", false,
                "messageSeries", map("seriesId", "dma-nw", "mainType", "NW"),
                "descs", list(map("lang", "en", "title", "IT-RT " + title)),
                "parts", list(map("type", "DETAILS", "descs", list(map("lang", "en", "subject", title)))))).statusCode(200);
        return uid;
    }
}
