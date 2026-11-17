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
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save, reload, save again for what a message holds beyond the four lists {@code MessageListOrderITTest} covers,
 * through the REST interface as an editor of the NW domain: three areas and two charts (ordered many-to-many lists of
 * different lengths), three categories, descriptions with every field in two languages, two parts whose geometry has
 * several features with positions named in two languages, the promulgations of two types at once (NAVTEX with its
 * transmitters, audio), the publication dates and flags; then a status change, the history the saves wrote, and
 * the copy of the message.
 *
 * <p>Each message is read back as the editor loads it ({@code GET /rest/messages/editable-message/{id}}), saved again
 * from that answer, and its rows are counted in SQL. Create and update commit on the server, so each message is
 * removed afterwards in a fresh transaction. This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
@SuppressWarnings("unchecked")
class MessageRoundTripITTest extends RoundTripSupport {

    private static final long FUTURE = Instant.parse("2999-01-05T08:00:00Z").toEpochMilli();
    private static final long DAY = 86_400_000L;
    private static final String NAVTEX_VO = "org.niord.core.promulgation.vo.NavtexMessagePromulgationVo";
    private static final String AUDIO_VO = "org.niord.core.promulgation.vo.AudioMessagePromulgationVo";

    @Autowired
    RepoStore store;

    private final List<String> createdUids = new ArrayList<>();
    private final List<String> folders = new ArrayList<>();
    private final List<String> transmitters = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        for (String uid : createdUids) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.uid = :uid")
                        .setParameter("uid", uid).executeUpdate();
                em.createQuery("delete from Comment c where c.message.id in (select m.id from Message m where m.uid = :uid)")
                        .setParameter("uid", uid).executeUpdate();
                em.createQuery("select m from Message m where m.uid = :uid", Message.class)
                        .setParameter("uid", uid).getResultStream().findFirst().ifPresent(em::remove);
            });
            store.deleteFolder(UidUtils.uidToHashedFolderPath("messages", uid));
        }
        folders.forEach(store::deleteFolder);
        transmitters.forEach(t -> delete("sysadmin", "/rest/promulgation/navtex/transmitters/navtex/transmitter/" + t));
        createdUids.clear();
        folders.clear();
        transmitters.clear();
    }

    /** The rows a message holds beyond its own, by its uid */
    private static final Map<String, String> MESSAGE_ROWS = new LinkedHashMap<>();

    private static final String MSG = "(SELECT id FROM public.\"Message\" WHERE uid = ?1)";
    private static final String PARTS = "(SELECT id FROM public.\"MessagePart\" WHERE message_id = " + MSG + ")";
    private static final String COLLECTIONS = "(SELECT geometry_id FROM public.\"MessagePart\" WHERE message_id = " + MSG + ")";
    private static final String PROMULGATIONS = "(SELECT id FROM public.\"BaseMessagePromulgation\" WHERE message_id = " + MSG + ")";

    static {
        MESSAGE_ROWS.put("areas", "SELECT count(*) FROM public.\"Message_Area\" WHERE \"Message_id\" = " + MSG);
        MESSAGE_ROWS.put("charts", "SELECT count(*) FROM public.\"Message_Chart\" WHERE \"Message_id\" = " + MSG);
        MESSAGE_ROWS.put("categories", "SELECT count(*) FROM public.\"Message_Category\" WHERE \"Message_id\" = " + MSG);
        MESSAGE_ROWS.put("descs", "SELECT count(*) FROM public.\"MessageDesc\" WHERE entity_id = " + MSG);
        MESSAGE_ROWS.put("parts", "SELECT count(*) FROM public.\"MessagePart\" WHERE message_id = " + MSG);
        MESSAGE_ROWS.put("part descs", "SELECT count(*) FROM public.\"MessagePartDesc\" WHERE entity_id IN " + PARTS);
        MESSAGE_ROWS.put("event dates", "SELECT count(*) FROM public.\"DateInterval\" WHERE \"messagePart_id\" IN " + PARTS);
        MESSAGE_ROWS.put("feature collections", "SELECT count(*) FROM public.\"FeatureCollection\" WHERE id IN " + COLLECTIONS);
        MESSAGE_ROWS.put("features", "SELECT count(*) FROM public.\"Feature\" WHERE \"featureCollection_id\" IN " + COLLECTIONS);
        MESSAGE_ROWS.put("references", "SELECT count(*) FROM public.\"Reference\" WHERE message_id = " + MSG);
        MESSAGE_ROWS.put("promulgations", "SELECT count(*) FROM public.\"BaseMessagePromulgation\" WHERE message_id = " + MSG);
        MESSAGE_ROWS.put("navtex transmitters", "SELECT count(*) FROM public.\"NavtexMessagePromulgation_NavtexTransmitter\""
                + " WHERE \"NavtexMessagePromulgation_id\" IN " + PROMULGATIONS);
    }

    // ------------------------------------------------------------------------------------------- //
    // Create, update, save unchanged
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessageKeepsItsAreasChartsCategoriesTextsGeometryAndPromulgationsThroughCreateUpdateAndAnUnchangedSave() {
        List<Integer> areas = ints("SELECT id FROM public.\"Area\" WHERE parent_id IS NOT NULL ORDER BY id LIMIT 4");
        List<String> charts = strings("SELECT \"chartNumber\" FROM public.\"Chart\" ORDER BY id LIMIT 3");
        List<Integer> categories = ints("SELECT id FROM public.\"Category\" WHERE type = 'CATEGORY' ORDER BY id LIMIT 3");
        assertEquals(List.of(4, 3, 3), List.of(areas.size(), charts.size(), categories.size()), "the seed's reference data");
        String t1 = transmitter("T1");
        String t2 = transmitter("T2");
        String t3 = transmitter("T3");

        String uid = UidUtils.newUid();
        Map<String, Object> msg = draft(uid, newTempDir());
        msg.put("areas", list(map("id", areas.get(0)), map("id", areas.get(1)), map("id", areas.get(2))));
        msg.put("charts", list(map("chartNumber", charts.get(0)), map("chartNumber", charts.get(1))));
        msg.put("categories", list(map("id", categories.get(0)), map("id", categories.get(1)), map("id", categories.get(2))));
        msg.put("descs", list(desc("en", "Title"), desc("da", "Titel")));
        msg.put("horizontalDatum", "WGS84");
        msg.put("originalInformation", true);
        msg.put("separatePage", true);
        msg.put("publishDateFrom", FUTURE);
        msg.put("publishDateTo", FUTURE + 30 * DAY);
        msg.put("followUpDate", FUTURE + 10 * DAY);
        msg.put("parts", list(
                part("Part 1", list(interval(FUTURE), interval(FUTURE + DAY)), collection(
                        point(11.25, 56.5, "Buoy", "Bøje"),
                        line(new double[][] { { 10.0, 55.0 }, { 10.5, 55.25 }, { 11.0, 55.5 } }, "West end", "Vestende",
                                "East end", "Østende"))),
                // An open-ended interval ("until further notice"): a from-date and no to-date
                part("Part 2", list(map("allDay", false, "fromDate", FUTURE + 2 * DAY)), collection(
                        polygon(new double[][] { { 12.0, 56.0 }, { 12.5, 56.0 }, { 12.5, 56.25 }, { 12.0, 56.0 } })))));
        msg.put("references", list(map("messageId", "NW-001-26", "type", "REFERENCE"),
                map("messageId", "NW-002-26", "type", "UPDATE")));
        msg.put("promulgations", list(
                map("@class", NAVTEX_VO, "type", map("typeId", "navtex"), "promulgate", true, "priority", "IMPORTANT",
                        "transmitters", map(t1, true, t2, true, t3, false), "text", "NAVTEX TEXT"),
                map("@class", AUDIO_VO, "type", map("typeId", "audio"), "promulgate", true, "text", "Audio text")));
        create(msg);

        Map<String, Object> read = editable(uid);
        assertEquals(List.of(areas.get(0), areas.get(1), areas.get(2)), ids(read, "areas"), "the areas in order after the create");
        assertEquals(List.of(charts.get(0), charts.get(1)), fieldOf(read, "charts", "chartNumber"), "the charts in order");
        assertEquals(Set.of(categories.get(0), categories.get(1), categories.get(2)), new HashSet<>(ids(read, "categories")));
        assertEquals(desc("en", "Title"), descOf(read, "en"));
        assertEquals(desc("da", "Titel"), descOf(read, "da"));
        assertEquals(List.of("WGS84", true, true, FUTURE, FUTURE + 30 * DAY, FUTURE + 10 * DAY),
                List.of(read.get("horizontalDatum"), read.get("originalInformation"), read.get("separatePage"),
                        millis(read.get("publishDateFrom")), millis(read.get("publishDateTo")), millis(read.get("followUpDate"))));
        assertEquals(List.of(
                        List.of("Point [11.25,56.5] {name:da=Bøje, name:en=Buoy}",
                                "LineString [10.0,55.0 10.5,55.25 11.0,55.5] {name:0:da=Vestende, name:0:en=West end,"
                                        + " name:2:da=Østende, name:2:en=East end}"),
                        List.of("Polygon [12.0,56.0 12.5,56.0 12.5,56.25 12.0,56.0] {}")),
                geometry(read), "the parts' geometry after the create");
        assertEquals(List.of(List.of(FUTURE, FUTURE + DAY), List.of(FUTURE + 2 * DAY)), eventDates(read));
        assertNull(listOf(listOf(read, "parts").get(1), "eventDates").getFirst().get("toDate"),
                "the open-ended interval keeps no to-date");
        assertEquals(Map.of("en", List.of("Part 1", "Part 2"), "da", List.of("Part 1 da", "Part 2 da")), subjects(read));
        assertEquals(Map.of("navtex", "true IMPORTANT [" + t1 + ", " + t2 + "] NAVTEX TEXT", "audio", "true Audio text"),
                promulgations(read, t1, t2, t3), "the promulgations after the create");
        assertOrderColumns(uid, 3, 2, "after the create");

        // The editor's update: the areas reordered with one removed and one added, the charts reversed and one
        // swapped, a category removed and the rest reordered, the title changed in both languages; a TIME part put
        // first (as the firing exercises build their messages); in part 1 the point removed, a position of the line
        // moved, the line's east end left without names and its west end renamed in English, and a point added;
        // NAVTEX sent to T3 instead of T1, its text changed; audio switched off
        Map<String, Object> edit = editable(uid);
        List<Map<String, Object>> editAreas = listOf(edit, "areas");
        edit.put("areas", list(editAreas.get(2), editAreas.get(0), map("id", areas.get(3))));
        edit.put("charts", list(map("chartNumber", charts.get(2)), listOf(edit, "charts").get(0)));
        // A message keeps no order of its categories (no order column; MSG9 of be/docs/potential-errors.md), so the
        // read lists them in whatever order the database gives: the two kept are picked by id, not by position
        List<Map<String, Object>> editCategories = listOf(edit, "categories");
        edit.put("categories", list(withId(editCategories, categories.get(2)), withId(editCategories, categories.get(0))));
        listOf(edit, "descs").stream().filter(d -> "en".equals(d.get("lang"))).forEach(d -> d.put("title", "New title"));
        // The Danish title too: a save that writes one language only must show
        listOf(edit, "descs").stream().filter(d -> "da".equals(d.get("lang"))).forEach(d -> d.put("title", "Ny titel"));
        Map<String, Object> geometry1 = geometryOf(listOf(edit, "parts").get(0));
        List<Map<String, Object>> features = listOf(geometry1, "features");
        Map<String, Object> theLine = features.get(1);
        coordinatesOf(theLine).set(1, list(10.75, 55.125));
        // A save that merges a kept feature's names instead of replacing them must show
        Map<String, Object> lineNames = (Map<String, Object>) theLine.get("properties");
        lineNames.remove("name:2:en");
        lineNames.remove("name:2:da");
        lineNames.put("name:0:en", "West point");
        geometry1.put("features", list(theLine, point(9.5, 57.0, "Wreck", "Vrag")));
        // A save that sorts or groups the parts (here a TIME part before two DETAILS parts) must show
        Map<String, Object> timePart = part("Time", list(interval(FUTURE + 5 * DAY)), null);
        timePart.put("type", "TIME");
        List<Map<String, Object>> editParts = listOf(edit, "parts");
        edit.put("parts", list(timePart, editParts.get(0), editParts.get(1)));
        for (Map<String, Object> p : listOf(edit, "promulgations")) {
            Map<String, Object> type = (Map<String, Object>) p.get("type");
            if ("navtex".equals(type.get("typeId"))) {
                p.put("transmitters", map(t1, false, t2, true, t3, true));
                p.put("text", "NEW NAVTEX TEXT");
            } else if ("audio".equals(type.get("typeId"))) {
                p.put("promulgate", false);
            }
        }
        update(uid, edit);

        Map<String, Object> updated = editable(uid);
        List<Integer> expectedAreas = List.of(areas.get(2), areas.get(0), areas.get(3));
        assertEquals(expectedAreas, ids(updated, "areas"), "the areas in order after the update");
        assertEquals(List.of(charts.get(2), charts.get(0)), fieldOf(updated, "charts", "chartNumber"), "the charts in order after the update");
        assertEquals(Set.of(categories.get(2), categories.get(0)), new HashSet<>(ids(updated, "categories")));
        assertEquals("New title", descOf(updated, "en").get("title"));
        assertEquals(desc("da", "Ny titel"), descOf(updated, "da"));
        assertEquals(List.of("TIME", "DETAILS", "DETAILS"), fieldOf(updated, "parts", "type"), "the parts in order after the update");
        List<List<String>> expectedGeometry = List.of(
                List.of(),
                List.of("LineString [10.0,55.0 10.75,55.125 11.0,55.5] {name:0:da=Vestende, name:0:en=West point}",
                        "Point [9.5,57.0] {name:da=Vrag, name:en=Wreck}"),
                List.of("Polygon [12.0,56.0 12.5,56.0 12.5,56.25 12.0,56.0] {}"));
        assertEquals(expectedGeometry, geometry(updated), "the parts' geometry after the update");
        List<List<Long>> expectedDates = List.of(List.of(FUTURE + 5 * DAY), List.of(FUTURE, FUTURE + DAY), List.of(FUTURE + 2 * DAY));
        assertEquals(expectedDates, eventDates(updated), "the event dates after the update");
        assertEquals(Map.of("navtex", "true IMPORTANT [" + t2 + ", " + t3 + "] NEW NAVTEX TEXT", "audio", "false Audio text"),
                promulgations(updated, t1, t2, t3), "the promulgations after the update");
        assertOrderColumns(uid, 3, 2, "after the update");

        // Saved once more as the editor loads it: nothing changes, no row is added or lost
        Map<String, Long> rowsBefore = counts(MESSAGE_ROWS, uid);
        long allFeatures = count("SELECT count(*) FROM public.\"Feature\"");
        long allCollections = count("SELECT count(*) FROM public.\"FeatureCollection\"");
        update(uid, editable(uid));
        assertEquals(rowsBefore, counts(MESSAGE_ROWS, uid), "the rows of an unchanged save");
        assertEquals(allFeatures, count("SELECT count(*) FROM public.\"Feature\""), "no feature row added or lost");
        assertEquals(allCollections, count("SELECT count(*) FROM public.\"FeatureCollection\""),
                "no feature collection added or lost");
        Map<String, Object> resaved = editable(uid);
        assertEquals(expectedAreas, ids(resaved, "areas"));
        assertEquals(expectedGeometry, geometry(resaved), "the geometry after an unchanged save");
        assertEquals(expectedDates, eventDates(resaved), "the event dates after an unchanged save");
        assertEquals(descOf(updated, "da"), descOf(resaved, "da"), "the Danish texts after an unchanged save");
        assertEquals(promulgations(updated, t1, t2, t3), promulgations(resaved, t1, t2, t3));
        assertOrderColumns(uid, 3, 2, "after an unchanged save");

        // A status change keeps every list
        as("editor").body("VERIFIED").when().put("/rest/messages/message/" + uid + "/status").then().statusCode(200);
        assertEquals(rowsBefore, counts(MESSAGE_ROWS, uid), "the rows after a status change");
        Map<String, Object> verified = editable(uid);
        assertEquals("VERIFIED", verified.get("status"));
        assertEquals(expectedAreas, ids(verified, "areas"));
        assertEquals(expectedGeometry, geometry(verified));
        assertEquals(promulgations(updated, t1, t2, t3), promulgations(verified, t1, t2, t3));
    }

    private void assertOrderColumns(String uid, int areas, int charts, String when) {
        assertOrderColumn(areas, ints("SELECT \"areas_ORDER\" FROM public.\"Message_Area\" WHERE \"Message_id\" = " + MSG, uid),
                "the areas " + when);
        assertOrderColumn(charts, ints("SELECT \"charts_ORDER\" FROM public.\"Message_Chart\" WHERE \"Message_id\" = " + MSG, uid),
                "the charts " + when);
    }

    // ------------------------------------------------------------------------------------------- //
    // History
    // ------------------------------------------------------------------------------------------- //

    @Test
    void everySaveAndStatusChangeWritesOneHistoryEntryWithItsOwnVersion() {
        String uid = UidUtils.newUid();
        Map<String, Object> msg = draft(uid, newTempDir());
        msg.put("parts", list(part("Part 1", list(), null)));
        create(msg);
        update(uid, editable(uid));
        update(uid, editable(uid));
        as("editor").body("VERIFIED").when().put("/rest/messages/message/" + uid + "/status").then().statusCode(200);

        List<Map<String, Object>> history = getList("editor", "/rest/messages/message/" + uid + "/history");
        assertEquals(4, history.size(), "one entry per save and status change: " + history);
        List<Integer> versions = history.stream().map(h -> ((Number) h.get("version")).intValue()).toList();
        assertEquals(4, new HashSet<>(versions).size(), "each entry has a version of its own: " + versions);
        assertEquals("VERIFIED", history.getFirst().get("status"), "the newest entry first");
        int rowVersion = ints("SELECT version FROM public.\"Message\" WHERE uid = ?1", uid).getFirst();
        assertTrue(versions.contains(rowVersion), "the newest version " + rowVersion + " is among " + versions);
    }

    // ------------------------------------------------------------------------------------------- //
    // Copy
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aCopyKeepsTheListsAndGeometryAndLeavesTheOriginalAlone() {
        List<Integer> areas = ints("SELECT id FROM public.\"Area\" WHERE parent_id IS NOT NULL ORDER BY id LIMIT 2");
        List<String> charts = strings("SELECT \"chartNumber\" FROM public.\"Chart\" ORDER BY id LIMIT 3");
        String uid = UidUtils.newUid();
        Map<String, Object> msg = draft(uid, newTempDir());
        msg.put("areas", list(map("id", areas.get(1)), map("id", areas.get(0))));
        msg.put("charts", list(map("chartNumber", charts.get(2)), map("chartNumber", charts.get(0)),
                map("chartNumber", charts.get(1))));
        msg.put("descs", list(desc("en", "Original"), desc("da", "Original da")));
        msg.put("parts", list(
                part("Part 1", list(interval(FUTURE)), collection(point(11.25, 56.5, "Buoy", "Bøje"),
                        point(11.5, 56.75, "Light", "Fyr"))),
                part("Part 2", list(), null)));
        create(msg);
        Map<String, Long> originalRows = counts(MESSAGE_ROWS, uid);
        List<List<String>> originalGeometry = geometry(editable(uid));

        Map<String, Object> copy = getMap("editor", "/rest/messages/copy-message-template/" + uid + "?referenceType=REFERENCE");
        folders.add((String) copy.get("editRepoPath"));
        String copyUid = (String) copy.get("id");
        assertNotEquals(uid, copyUid, "the copy has a new id");
        create(copy);

        Map<String, Object> copied = editable(copyUid);
        assertEquals(List.of(areas.get(1), areas.get(0)), ids(copied, "areas"), "the copy's areas in order");
        assertEquals(List.of(charts.get(2), charts.get(0), charts.get(1)), fieldOf(copied, "charts", "chartNumber"),
                "the copy's charts in order");
        assertEquals(Map.of("en", List.of("Part 1", "Part 2"), "da", List.of("Part 1 da", "Part 2 da")), subjects(copied));
        assertEquals(originalGeometry, geometry(copied), "the copy's geometry");
        assertEquals(List.of(List.of(), List.of()), eventDates(copied), "the copy's event dates are reset");
        assertEquals("DRAFT", copied.get("status"));
        assertNull(copied.get("shortId"));
        assertTrue(listOf(copied, "references").stream().anyMatch(r -> uid.equals(r.get("messageId"))
                        && "REFERENCE".equals(r.get("type"))), "the copy refers to the original: " + copied.get("references"));
        assertFalse(intersects(uid, copyUid), "the copy shares no feature collection or feature with the original");

        // The original is unchanged
        assertEquals(originalRows, counts(MESSAGE_ROWS, uid), "the original's rows after the copy");
        assertEquals(originalGeometry, geometry(editable(uid)), "the original's geometry after the copy");
    }

    private boolean intersects(String uid1, String uid2) {
        return count("SELECT count(*) FROM public.\"FeatureCollection\" WHERE id IN " + COLLECTIONS
                + " AND id IN (SELECT geometry_id FROM public.\"MessagePart\" WHERE message_id ="
                + " (SELECT id FROM public.\"Message\" WHERE uid = ?2))", uid1, uid2) > 0
                || count("SELECT count(*) FROM public.\"Feature\" f1 JOIN public.\"Feature\" f2 ON f1.uid = f2.uid"
                + " WHERE f1.\"featureCollection_id\" IN " + COLLECTIONS
                + " AND f2.\"featureCollection_id\" IN (SELECT geometry_id FROM public.\"MessagePart\" WHERE message_id ="
                + " (SELECT id FROM public.\"Message\" WHERE uid = ?2))", uid1, uid2) > 0;
    }

    // ------------------------------------------------------------------------------------------- //
    // What is compared
    // ------------------------------------------------------------------------------------------- //

    private static List<Integer> ids(Map<String, Object> owner, String key) {
        return fieldOf(owner, key, "id").stream().map(v -> ((Number) v).intValue()).toList();
    }

    /** The entry of the list whose id is the given one */
    private static Map<String, Object> withId(List<Map<String, Object>> entries, int id) {
        return entries.stream().filter(e -> ((Number) e.get("id")).intValue() == id).findFirst()
                .orElseThrow(() -> new AssertionError("no entry with id " + id + " in " + entries));
    }

    private static Map<String, Object> descOf(Map<String, Object> message, String lang) {
        Map<String, Object> d = listOf(message, "descs").stream().filter(x -> lang.equals(x.get("lang"))).findFirst()
                .orElseThrow();
        return map("lang", d.get("lang"), "title", d.get("title"), "vicinity", d.get("vicinity"),
                "publication", d.get("publication"), "internalPublication", d.get("internalPublication"),
                "source", d.get("source"));
    }

    /** Each part's features, as "type [coordinates] {name properties}" */
    private static List<List<String>> geometry(Map<String, Object> message) {
        List<List<String>> result = new ArrayList<>();
        for (Map<String, Object> part : listOf(message, "parts")) {
            List<String> features = new ArrayList<>();
            Map<String, Object> fc = geometryOf(part);
            if (fc != null) {
                for (Map<String, Object> f : listOf(fc, "features")) {
                    Map<String, Object> g = (Map<String, Object>) f.get("geometry");
                    Map<String, Object> props = f.get("properties") == null ? Map.of() : (Map<String, Object>) f.get("properties");
                    Map<String, Object> names = new TreeMap<>();
                    props.forEach((k, v) -> {
                        if (k.startsWith("name:")) {
                            names.put(k, v);
                        }
                    });
                    features.add(g.get("type") + " " + coordinates(g.get("coordinates")) + " " + names);
                }
            }
            result.add(features);
        }
        return result;
    }

    private static String coordinates(Object c) {
        List<?> list = (List<?>) c;
        if (!list.isEmpty() && list.getFirst() instanceof Number) {
            return "[" + ((Number) list.get(0)).doubleValue() + "," + ((Number) list.get(1)).doubleValue() + "]";
        }
        // A line's positions, or a polygon's one ring
        List<?> positions = list.getFirst() instanceof List<?> l && !l.isEmpty() && l.getFirst() instanceof List ? l : list;
        List<String> parts = new ArrayList<>();
        for (Object p : positions) {
            List<?> pos = (List<?>) p;
            parts.add(((Number) pos.get(0)).doubleValue() + "," + ((Number) pos.get(1)).doubleValue());
        }
        return "[" + String.join(" ", parts) + "]";
    }

    private static Map<String, Object> geometryOf(Map<String, Object> part) {
        return (Map<String, Object>) part.get("geometry");
    }

    private static List<Object> coordinatesOf(Map<String, Object> feature) {
        return (List<Object>) ((Map<String, Object>) feature.get("geometry")).get("coordinates");
    }

    private static List<List<Long>> eventDates(Map<String, Object> message) {
        return listOf(message, "parts").stream()
                .map(p -> listOf(p, "eventDates").stream().map(d -> millis(d.get("fromDate"))).toList())
                .toList();
    }

    private static Map<String, List<Object>> subjects(Map<String, Object> message) {
        Map<String, List<Object>> result = new TreeMap<>();
        for (Map<String, Object> part : listOf(message, "parts")) {
            listOf(part, "descs").forEach(d -> result.computeIfAbsent((String) d.get("lang"), k -> new ArrayList<>())
                    .add(d.get("subject")));
        }
        return result;
    }

    /** The NAVTEX and audio promulgations: promulgate, priority, the selected transmitters of the test, text */
    private static Map<String, String> promulgations(Map<String, Object> message, String... ownTransmitters) {
        Map<String, String> result = new TreeMap<>();
        for (Map<String, Object> p : listOf(message, "promulgations")) {
            String typeId = (String) ((Map<String, Object>) p.get("type")).get("typeId");
            if ("navtex".equals(typeId)) {
                Map<String, Object> selected = p.get("transmitters") == null ? Map.of() : (Map<String, Object>) p.get("transmitters");
                List<String> on = new ArrayList<>();
                for (String t : ownTransmitters) {
                    if (Boolean.TRUE.equals(selected.get(t))) {
                        on.add(t);
                    }
                }
                result.put(typeId, p.get("promulgate") + " " + p.get("priority") + " " + on + " " + p.get("text"));
            } else if ("audio".equals(typeId)) {
                result.put(typeId, p.get("promulgate") + " " + p.get("text"));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------- //
    // REST
    // ------------------------------------------------------------------------------------------- //

    private void create(Map<String, Object> msg) {
        createdUids.add((String) msg.get("id"));
        postRaw("editor", "/rest/messages/message", msg).statusCode(200);
    }

    private void update(String uid, Map<String, Object> msg) {
        putRaw("editor", "/rest/messages/message/" + uid, msg).statusCode(200);
    }

    /** The message as the editor loads it, with a new edit folder */
    private Map<String, Object> editable(String uid) {
        Map<String, Object> msg = getMap("editor", "/rest/messages/editable-message/" + uid);
        assertFalse(msg.isEmpty(), "the editable message");
        folders.add((String) msg.get("editRepoPath"));
        return msg;
    }

    private String newTempDir() {
        String path = as("editor").when().get("/rest/repo/new-temp-dir").then().statusCode(200).extract().path("path");
        folders.add(path);
        return path;
    }

    /** An active NAVTEX transmitter of the seed's navtex type, removed after the test */
    private String transmitter(String name) {
        String full = "IT-RT " + name + " " + unique();
        post("sysadmin", "/rest/promulgation/navtex/transmitters/navtex/transmitter",
                map("promulgationType", map("typeId", "navtex"), "name", full, "active", true, "areas", list()));
        transmitters.add(full);
        return full;
    }

    // ------------------------------------------------------------------------------------------- //
    // The message body
    // ------------------------------------------------------------------------------------------- //

    private static Map<String, Object> draft(String uid, String editRepoPath) {
        return map("id", uid,
                "repoPath", UidUtils.uidToHashedFolderPath("messages", uid),
                "editRepoPath", editRepoPath,
                "revision", 0,
                "mainType", "NW",
                "type", "LOCAL_WARNING",
                "status", "DRAFT",
                "autoTitle", false,
                "messageSeries", map("seriesId", "dma-nw", "mainType", "NW"),
                "descs", list(map("lang", "en", "title", "IT-RT message")));
    }

    private static Map<String, Object> desc(String lang, String title) {
        return map("lang", lang, "title", title, "vicinity", "Vicinity " + lang, "publication", "Publication " + lang,
                "internalPublication", "Internal " + lang, "source", "Source " + lang);
    }

    private static Map<String, Object> part(String subject, List<Map<String, Object>> eventDates, Map<String, Object> geometry) {
        Map<String, Object> part = map("type", "DETAILS",
                "descs", list(map("lang", "en", "subject", subject, "details", "<p>" + subject + "</p>"),
                        map("lang", "da", "subject", subject + " da", "details", "<p>" + subject + " da</p>")),
                "eventDates", eventDates);
        if (geometry != null) {
            part.put("geometry", geometry);
        }
        return part;
    }

    private static Map<String, Object> interval(long from) {
        return map("allDay", false, "fromDate", from, "toDate", from + 3_600_000L);
    }

    @SafeVarargs
    private static Map<String, Object> collection(Map<String, Object>... features) {
        return map("type", "FeatureCollection", "features", list(features));
    }

    private static Map<String, Object> point(double lon, double lat, String en, String da) {
        return map("type", "Feature", "geometry", map("type", "Point", "coordinates", list(lon, lat)),
                "properties", map("name:en", en, "name:da", da));
    }

    private static Map<String, Object> line(double[][] positions, String firstEn, String firstDa, String lastEn, String lastDa) {
        int last = positions.length - 1;
        return map("type", "Feature", "geometry", map("type", "LineString", "coordinates", positions(positions)),
                "properties", map("name:0:en", firstEn, "name:0:da", firstDa,
                        "name:" + last + ":en", lastEn, "name:" + last + ":da", lastDa));
    }

    private static Map<String, Object> polygon(double[][] ring) {
        return map("type", "Feature", "geometry", map("type", "Polygon", "coordinates", list(positions(ring))),
                "properties", map());
    }

    private static List<Object> positions(double[][] positions) {
        List<Object> result = new ArrayList<>();
        for (double[] p : positions) {
            result.add(list(p[0], p[1]));
        }
        return result;
    }

    /** A date as JSON carries it (epoch milliseconds or an ISO-8601 instant) */
    private static Long millis(Object date) {
        return date == null ? null : date instanceof Number n ? n.longValue() : Instant.parse((String) date).toEpochMilli();
    }
}
