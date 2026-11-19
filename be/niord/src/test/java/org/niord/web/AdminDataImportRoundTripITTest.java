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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Export, import, export again for the system administrator's lists: domains ({@code GET /rest/domains/all},
 * {@code /upload-domains}, {@code domain-import}), mailing lists ({@code GET /rest/mailing-lists/export},
 * {@code /upload-mailing-lists}), promulgation types ({@code GET /rest/promulgations/promulgation-types/all},
 * {@code /upload-promulgation-types}), print reports ({@code GET /rest/message-reports/all}, {@code /upload-reports}),
 * dictionaries ({@code GET /rest/dictionaries/dictionary/{name}.json}, {@code /upload-dictionaries}), script
 * resources ({@code GET /rest/script-resources/all}, {@code /upload-script-resources}) and settings
 * ({@code GET /rest/settings/editable-settings}, {@code /upload-settings}).
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class AdminDataImportRoundTripITTest extends ImportRoundTripSupport {

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
    // Domains
    // ------------------------------------------------------------------------------------------- //

    /**
     * A domain exported, deleted and imported comes back with its areas, categories and message series; an import
     * over an existing domain leaves it as it is (the sysadmin manual: "an import never changes it").
     */
    @Test
    void aDomainExportedDeletedAndImportedComesBackAndAnImportNeverChangesAnExistingOne() {
        List<Integer> areas = ints("SELECT id FROM public.\"Area\" WHERE parent_id IS NULL AND mrn IS NOT NULL ORDER BY id LIMIT 3");
        List<Integer> categories = ints("SELECT id FROM public.\"Category\" WHERE parent_id IS NULL ORDER BY id LIMIT 2");
        String domainId = "it-imp-domain-" + unique();
        post("sysadmin", "/rest/domains/domain", map("domainId", domainId, "name", awkward("IT-imp domain"), "active", false,
                "sortOrder", 98, "timeZone", "America/Nuuk", "lat", 64.2, "lon", -51.7, "zoomLevel", 5,
                "messageSortOrder", "AREA ASC", "color", "#993366", "publish", false, "atons", true, "templates", false,
                "areas", list(map("id", areas.get(2)), map("id", areas.get(0)), map("id", areas.get(1))),
                "categories", list(map("id", categories.get(1)), map("id", categories.get(0))),
                "messageSeries", list(map("seriesId", "dma-nm"), map("seriesId", "dma-nw"))));
        cleanups.add(() -> delete("sysadmin", "/rest/domains/domain/" + domainId));
        Map<String, Object> first = exportedDomain(domainId);

        // (a)
        delete("sysadmin", "/rest/domains/domain/" + domainId).statusCode(204);
        JobEnd job = importRows("sysadmin", "/rest/domains/upload-domains", "domain-import", List.of(first));
        assertEquals("COMPLETED", job.status(), job.log());
        Map<String, Object> second = exportedDomain(domainId);
        assertEquals(normalisedDomain(first), normalisedDomain(second), "the domain after the import");

        // (b) and (c): an existing domain is ignored, whatever the file says
        Map<String, Object> changed = copy(first);
        changed.put("name", "Renamed");
        changed.put("areas", list(listOf(first, "areas").get(0)));
        job = importRows("sysadmin", "/rest/domains/upload-domains", "domain-import", List.of(first, changed));
        assertEquals("COMPLETED", job.status(), job.log());
        assertTrue(job.log().contains("Ignoring existing domain " + domainId), job.log());
        assertEquals(normalisedDomain(second), normalisedDomain(exportedDomain(domainId)), "unchanged");
    }

    private static Map<String, Object> exportedDomain(String domainId) {
        return getList("sysadmin", "/rest/domains/all?inactive=true&keycloakState=false").stream()
                .filter(d -> domainId.equals(d.get("domainId"))).findFirst()
                .orElseThrow(() -> new AssertionError("no domain " + domainId));
    }

    /** The domain with its areas, categories and series as sets of ids (the join tables have no order column) */
    private static Map<String, Object> normalisedDomain(Map<String, Object> domain) {
        Map<String, Object> out = new LinkedHashMap<>(domain);
        out.put("areas", new HashSet<>(fieldOf(domain, "areas", "id")));
        out.put("categories", new HashSet<>(fieldOf(domain, "categories", "id")));
        out.put("messageSeries", new HashSet<>(fieldOf(domain, "messageSeries", "seriesId")));
        return out;
    }

    // ------------------------------------------------------------------------------------------- //
    // Mailing lists
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMailingListExportedDeletedAndImportedComesBackAndAnImportOverItKeepsItsRecipients() {
        String listId = "it-imp-ml-" + unique();
        int contactA = contact("a");
        int contactB = contact("b");
        post("sysadmin", "/rest/mailing-lists/mailing-list", map("mailingListId", listId, "active", true,
                "descs", list(map("lang", "en", "name", awkward("IT-imp list"), "description", "The list"),
                        map("lang", "da", "name", "IT-imp liste", "description", "Listen")),
                "triggers", list(statusTrigger(), scheduledTrigger())));
        String base = "/rest/mailing-lists/mailing-list/" + listId;
        cleanups.add(() -> delete("sysadmin", base));
        Map<String, Object> first = exportedList(listId);
        assertFalse(first.containsKey("contacts") && !listOf(first, "contacts").isEmpty(), "no recipients in the export");

        // (a)
        delete("sysadmin", base).statusCode(204);
        JobEnd job = importRows("sysadmin", "/rest/mailing-lists/upload-mailing-lists", "mailing-list-import", List.of(first));
        assertEquals("COMPLETED", job.status(), job.log());
        Map<String, Object> second = exportedList(listId);
        assertEquals(comparableList(first), comparableList(second), "the list after the import");

        // Recipients set after the import
        as("admin").body(List.of(contactVo(contactA), contactVo(contactB))).when().put(base + "/contacts").then().statusCode(200);

        // (b)
        job = importRows("sysadmin", "/rest/mailing-lists/upload-mailing-lists", "mailing-list-import", List.of(second));
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(comparableList(second), comparableList(exportedList(listId)));
        assertEquals(Set.of(contactA, contactB), new HashSet<>(fieldOf(getMap("admin", base + "/contacts"),
                "selectedRecipients", "id").stream().map(v -> ((Number) v).intValue()).toList()),
                "the recipients the export does not carry are kept");

        // (c) the English description changed, the scheduled trigger removed, a daily one added
        Map<String, Object> changed = copy(second);
        listOf(changed, "descs").stream().filter(d -> "en".equals(d.get("lang"))).forEach(d -> d.put("description", "Changed"));
        List<Map<String, Object>> triggers = listOf(changed, "triggers");
        Map<String, Object> status = triggers.stream().filter(t -> "STATUS_CHANGE".equals(t.get("type"))).findFirst().orElseThrow();
        Map<String, Object> daily = scheduledTrigger();
        daily.put("scheduleType", "DAILY");
        changed.put("triggers", list(status, daily));
        job = importRows("sysadmin", "/rest/mailing-lists/upload-mailing-lists", "mailing-list-import", List.of(changed));
        assertEquals("COMPLETED", job.status(), job.log());
        Map<String, Object> third = exportedList(listId);
        assertEquals("Changed", descs(third, "description").get("en"));
        assertEquals(Set.of("STATUS_CHANGE/null", "SCHEDULED/DAILY"), new HashSet<>(listOf(third, "triggers").stream()
                .map(t -> t.get("type") + "/" + t.get("scheduleType")).toList()), "the file's triggers replace the list's");
        assertEquals(2, listOf(getMap("admin", base + "/contacts"), "selectedRecipients").size(), "recipients kept");
    }

    /**
     * The exported list as it is compared: without the ids and the computed fields, and with its triggers in a fixed
     * order. A list's triggers have no order column, so two reads of one list may give them in either order.
     */
    @SuppressWarnings("unchecked")
    private static Object comparableList(Map<String, Object> exported) {
        Object stripped = without(exported, List.of("id", "nextScheduledExecution", "recipientNo"));
        if (stripped instanceof Map<?, ?> map && map.get("triggers") instanceof List<?> triggers) {
            Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) map);
            List<Object> sorted = new ArrayList<>(triggers);
            sorted.sort(java.util.Comparator.comparing(
                    t -> ((Map<?, ?>) t).get("type") + "/" + ((Map<?, ?>) t).get("scheduleType")));
            out.put("triggers", sorted);
            return out;
        }
        return stripped;
    }

    private static Map<String, Object> exportedList(String listId) {
        return getList("sysadmin", "/rest/mailing-lists/export").stream()
                .filter(l -> listId.equals(l.get("mailingListId"))).findFirst()
                .orElseThrow(() -> new AssertionError("no mailing list " + listId));
    }

    private static Map<String, Object> statusTrigger() {
        return map("type", "STATUS_CHANGE", "statusChanges", list("PUBLISHED", "CANCELLED"),
                "messageFilter", "msg.type == 'LOCAL_WARNING'",
                "scriptResourcePaths", list("templates/it-imp/m1.ftl", "templates/it-imp/m3.js", "templates/it-imp/m2.ftl"),
                "descs", list(map("lang", "en", "subject", "Published"), map("lang", "da", "subject", "Udgivet, \"nu\" ")));
    }

    private static Map<String, Object> scheduledTrigger() {
        return map("type", "SCHEDULED", "scheduleType", "MONDAY", "scheduledExecutionTime", "08:00",
                "scheduledExecutionTimeZone", "Europe/Copenhagen", "messageQuery", "messageSeries=dma-nw&status=PUBLISHED",
                "publicReport", true,
                "scriptResourcePaths", list("templates/it-imp/w1.ftl", "templates/it-imp/w2.ftl"),
                "descs", list(map("lang", "en", "subject", "Weekly"), map("lang", "da", "subject", "Ugentlig")));
    }

    private int contact(String name) {
        String email = "it-imp-" + name + "-" + unique() + "@example.org";
        int id = ((Number) post("admin", "/rest/contacts/contact", map("email", email, "name", "IT-imp " + name,
                "language", "da")).get("id")).intValue();
        cleanups.add(() -> delete("admin", "/rest/contacts/contact/" + id));
        return id;
    }

    private Map<String, Object> contactVo(int id) {
        List<List<Object>> row = rows("SELECT email, name, language FROM public.\"Contact\" WHERE id = ?1", id);
        return map("id", id, "email", row.getFirst().get(0), "name", row.getFirst().get(1), "language", row.getFirst().get(2));
    }

    // ------------------------------------------------------------------------------------------- //
    // Promulgation types
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aPromulgationTypeExportedDeletedAndImportedComesBackAndAnImportOverItOverwritesIt() {
        String typeId = "it-imp-prom-" + unique();
        post("sysadmin", "/rest/promulgations/promulgation-type", map(
                "typeId", typeId, "serviceId", "navtex", "name", awkward("IT-imp NAVTEX"), "priority", 7, "active", false,
                "requirement", "MANDATORY", "language", "da",
                "domains", list(map("domainId", "niord-nw"), map("domainId", "niord-nm")),
                "messageTypes", list("LOCAL_WARNING", "COASTAL_WARNING"),
                "scriptResourcePaths", list("templates/it-imp/p2.js", "templates/it-imp/p1.ftl", "templates/it-imp/p3.ftl")));
        String path = "/rest/promulgations/promulgation-type/" + typeId;
        cleanups.add(() -> delete("sysadmin", path));
        Map<String, Object> first = exportedType(typeId);

        // (a)
        delete("sysadmin", path).statusCode(204);
        JobEnd job = importRows("sysadmin", "/rest/promulgations/upload-promulgation-types", "promulgation-type-import",
                List.of(first));
        assertEquals("COMPLETED", job.status(), job.log());
        Map<String, Object> second = exportedType(typeId);
        assertEquals(normalisedType(first), normalisedType(second), "the type after the import");

        // (b)
        job = importRows("sysadmin", "/rest/promulgations/upload-promulgation-types", "promulgation-type-import",
                List.of(second));
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(normalisedType(second), normalisedType(exportedType(typeId)));

        // (c) the manual: "overwritten by the file"
        Map<String, Object> changed = copy(second);
        changed.put("priority", 3);
        changed.put("domains", list(map("domainId", "niord-nm"), map("domainId", "niord-fa")));
        changed.put("messageTypes", list("COASTAL_WARNING", "NAVAREA_WARNING"));
        changed.put("scriptResourcePaths", list("templates/it-imp/p1.ftl", "templates/it-imp/p4.ftl"));
        job = importRows("sysadmin", "/rest/promulgations/upload-promulgation-types", "promulgation-type-import",
                List.of(changed));
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(normalisedType(changed), normalisedType(exportedType(typeId)), "the file's values");
    }

    private static Map<String, Object> exportedType(String typeId) {
        return getList("sysadmin", "/rest/promulgations/promulgation-types/all").stream()
                .filter(t -> typeId.equals(t.get("typeId"))).findFirst()
                .orElseThrow(() -> new AssertionError("no promulgation type " + typeId));
    }

    private static Map<String, Object> normalisedType(Map<String, Object> type) {
        Map<String, Object> out = new LinkedHashMap<>(type);
        out.put("domains", new HashSet<>(fieldOf(type, "domains", "domainId")));
        out.put("messageTypes", new HashSet<>(valuesOf(type, "messageTypes")));
        return out;
    }

    // ------------------------------------------------------------------------------------------- //
    // Print reports
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aReportExportedDeletedAndImportedComesBackAndAnImportOverItUpdatesIt() {
        String reportId = "it-imp-report-" + unique();
        post("sysadmin", "/rest/message-reports/report", map("reportId", reportId, "name", awkward("IT-imp report"),
                "sortOrder", 7, "templatePath", "templates/it-imp/report.ftl",
                "domains", list(map("domainId", "niord-nw"), map("domainId", "niord-nm")),
                "properties", map("pageOrientation", "landscape", "pageSize", "A3", "mapThumbnails", true),
                "params", map("title", "Weekly, \"all\"", "count", 3)));
        String path = "/rest/message-reports/report/" + reportId;
        cleanups.add(() -> delete("sysadmin", path));
        Map<String, Object> first = exportedReport(reportId);

        // (a)
        delete("sysadmin", path);
        assertEquals(0L, count("SELECT count(*) FROM public.\"FmReport\" WHERE \"reportId\" = ?1", reportId));
        JobEnd job = importRows("sysadmin", "/rest/message-reports/upload-reports", "report-import", List.of(first));
        assertEquals("COMPLETED", job.status(), job.log());
        Map<String, Object> second = exportedReport(reportId);
        assertEquals(normalisedReport(first), normalisedReport(second), "the report after the import");

        // (b)
        job = importRows("sysadmin", "/rest/message-reports/upload-reports", "report-import", List.of(second));
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(normalisedReport(second), normalisedReport(exportedReport(reportId)));

        // (c)
        Map<String, Object> changed = copy(second);
        changed.put("sortOrder", 2);
        changed.put("domains", list(map("domainId", "niord-nm"), map("domainId", "niord-fa")));
        changed.put("properties", map("pageOrientation", "portrait", "ISSN", "1234-5678"));
        changed.put("params", map("title", "Daily"));
        job = importRows("sysadmin", "/rest/message-reports/upload-reports", "report-import", List.of(changed));
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(normalisedReport(changed), normalisedReport(exportedReport(reportId)), "the file's values");
    }

    private static Map<String, Object> exportedReport(String reportId) {
        return getList("sysadmin", "/rest/message-reports/all").stream()
                .filter(r -> reportId.equals(r.get("reportId"))).findFirst()
                .orElseThrow(() -> new AssertionError("no report " + reportId));
    }

    private static Map<String, Object> normalisedReport(Map<String, Object> report) {
        Map<String, Object> out = new LinkedHashMap<>(report);
        out.put("domains", new HashSet<>(fieldOf(report, "domains", "domainId")));
        return out;
    }

    // ------------------------------------------------------------------------------------------- //
    // Dictionaries
    // ------------------------------------------------------------------------------------------- //

    @Test
    void dictionaryEntriesExportedDeletedAndImportedComeBack() {
        String name = "message";
        String prefix = "it.imp." + unique() + ".";
        String path = "/rest/dictionaries/dictionary/" + name;
        for (int i = 0; i < 3; i++) {
            String key = prefix + i;
            post("sysadmin", path, map("key", key, "descs", list(map("lang", "en", "value", awkward("Value " + i)),
                    map("lang", "da", "value", "VÃ¦rdi " + i))));
            cleanups.add(() -> delete("sysadmin", path + "/" + key));
        }
        List<Map<String, Object>> first = exportedDictionary(name, prefix);
        assertEquals(1, first.size());
        assertEquals(3, listOf(first.getFirst(), "entries").size());

        // (a) deleted, then imported, the three keys ticked as the dialog ticks what its preview lists
        for (int i = 0; i < 3; i++) {
            delete("sysadmin", path + "/" + prefix + i);
        }
        assertEquals(0, listOf(exportedDictionary(name, prefix).getFirst(), "entries").size());
        String add = json(List.of(map("dictionary", name, "key", prefix + "0"), map("dictionary", name, "key", prefix + "1"),
                map("dictionary", name, "key", prefix + "2")));
        JobEnd job = importBytes("sysadmin", "/rest/dictionaries/upload-dictionaries", "dictionary-import",
                json(first).getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of("add", add));
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(without(first, List.of("id")), without(exportedDictionary(name, prefix), List.of("id")),
                "the entries after the import");

        // (b) again, no tick: "unchanged"
        job = importRows("sysadmin", "/rest/dictionaries/upload-dictionaries", "dictionary-import", first);
        assertEquals("COMPLETED", job.status(), job.log());
        assertTrue(job.log().contains("unchanged 3"), job.log());
        assertEquals(without(first, List.of("id")), without(exportedDictionary(name, prefix), List.of("id")));
    }

    /** The dictionary's export, its entries narrowed to the prefix */
    private static List<Map<String, Object>> exportedDictionary(String name, String prefix) {
        List<Map<String, Object>> dictionaries = getList("sysadmin", "/rest/dictionaries/dictionary/" + name + ".json");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : dictionaries) {
            Map<String, Object> copy = copy(d);
            copy.put("entries", listOf(d, "entries").stream().filter(e -> String.valueOf(e.get("key")).startsWith(prefix))
                    .sorted((x, y) -> String.valueOf(x.get("key")).compareTo(String.valueOf(y.get("key")))).toList());
            out.add(copy);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- //
    // Script resources
    // ------------------------------------------------------------------------------------------- //

    /**
     * The export (the page's {@code /all}) imported back: missing paths added, the existing kept unless ticked
     * ({@link ScriptResourceImportITTest} covers the ticks); the history of an imported resource is the import's.
     */
    @Test
    void scriptResourcesExportedDeletedAndImportedComeBack() {
        String prefix = "templates/it-imp/" + unique() + "/";
        List<String> paths = List.of(prefix + "a.ftl", prefix + "b.js", prefix + "c.ftl");
        List<String> contents = List.of("<#-- a, \"Ã†rÃ¸\" -->\n${x}", "var b = 'Ã†rÃ¸, \"q\" ';", "<#-- c -->");
        for (int i = 0; i < 3; i++) {
            post("sysadmin", "/rest/script-resources/script-resource", map("type", paths.get(i).endsWith(".js") ? "JS" : "FM",
                    "path", paths.get(i), "content", contents.get(i)));
        }
        cleanups.add(() -> exportedResources(prefix).forEach(r ->
                delete("sysadmin", "/rest/script-resources/script-resource/" + r.get("id"))));
        List<Map<String, Object>> first = exportedResources(prefix);
        assertEquals(paths, first.stream().map(r -> r.get("path")).toList());

        // (a)
        first.forEach(r -> delete("sysadmin", "/rest/script-resources/script-resource/" + r.get("id")));
        assertEquals(List.of(), exportedResources(prefix));
        JobEnd job = importRows("sysadmin", "/rest/script-resources/upload-script-resources", "script-resource-import", first);
        assertEquals("COMPLETED", job.status(), job.log());
        List<Map<String, Object>> second = exportedResources(prefix);
        assertEquals(without(first, List.of("id", "created", "updated", "version")),
                without(second, List.of("id", "created", "updated", "version")), "the resources after the import");

        // (b)
        job = importRows("sysadmin", "/rest/script-resources/upload-script-resources", "script-resource-import", second);
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(second, exportedResources(prefix), "unchanged");
        for (Map<String, Object> r : second) {
            assertEquals(1, getList("sysadmin", "/rest/script-resources/script-resource/" + r.get("id") + "/history").size(),
                    "no history row for an unchanged import of " + r.get("path"));
        }
    }

    private static List<Map<String, Object>> exportedResources(String prefix) {
        return getList("sysadmin", "/rest/script-resources/all").stream()
                .filter(r -> String.valueOf(r.get("path")).startsWith(prefix))
                .sorted((x, y) -> String.valueOf(x.get("path")).compareTo(String.valueOf(y.get("path"))))
                .toList();
    }

    // ------------------------------------------------------------------------------------------- //
    // Settings
    // ------------------------------------------------------------------------------------------- //

    /**
     * The whole settings export imported back over the deployment changes no value, a password's included (the
     * export leaves a password's value out, and the import ignores a password without a value); a value changed in
     * the file is written, of each type the export holds.
     */
    @Test
    void theSettingsExportImportedBackChangesNothingAndAChangedValueIsWritten() {
        List<Map<String, Object>> first = getList("sysadmin", "/rest/settings/editable-settings");
        Map<String, Object> stored = storedValues();

        // What the API answers for a value, by type
        Map<String, Object> shown = new LinkedHashMap<>();
        for (Map<String, Object> s : first) {
            shown.putIfAbsent(String.valueOf(s.get("type")), s.get("key") + "=" + s.get("value"));
        }
        // The export holds a password, or "a password's included" below would claim what nothing checks
        assertTrue(first.stream().anyMatch(s -> "Password".equals(s.get("type"))), "the export holds a Password setting");
        for (Map<String, Object> s : first) {
            if ("Password".equals(s.get("type"))) {
                assertFalse(s.containsKey("value") && s.get("value") != null, "a password's value is not shown: " + s);
            }
        }

        // (b) the export over the deployment
        JobEnd job = importRows("sysadmin", "/rest/settings/upload-settings", "settings-import", first);
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(stored, storedValues(), "no stored value changed, a password's included; the types shown " + shown);
        assertEquals(first, getList("sysadmin", "/rest/settings/editable-settings"));

        // (c) one value of each type changed in the file
        List<Map<String, Object>> changed = new ArrayList<>();
        Map<String, Object> restore = new LinkedHashMap<>();
        for (Map<String, Object> s : first) {
            Object next = changedValue(s);
            if (next != null && changed.stream().noneMatch(c -> c.get("type").equals(s.get("type")))) {
                Map<String, Object> c = copy(s);
                c.put("value", next);
                changed.add(c);
                restore.put((String) s.get("key"), s);
            }
        }
        // One setting of each type the test can change: the loop below must have something to check. (The export
        // holds no editable Long setting with a value, so no Long is changed; measured 2026-11-12.)
        assertTrue(changed.stream().map(c -> String.valueOf(c.get("type"))).toList()
                        .containsAll(List.of("String", "Integer", "Boolean")),
                "a String, an Integer and a Boolean setting are changed in the file: " + changed);
        cleanups.add(() -> importRows("sysadmin", "/rest/settings/upload-settings", "settings-import",
                new ArrayList<>(restore.values())));
        job = importRows("sysadmin", "/rest/settings/upload-settings", "settings-import", changed);
        assertEquals("COMPLETED", job.status(), job.log());
        Map<String, Map<String, Object>> after = new LinkedHashMap<>();
        getList("sysadmin", "/rest/settings/editable-settings").forEach(s -> after.put((String) s.get("key"), s));
        for (Map<String, Object> c : changed) {
            assertNotNull(after.get(c.get("key")));
            assertEquals(String.valueOf(c.get("value")), String.valueOf(after.get(c.get("key")).get("value")),
                    "the " + c.get("type") + " setting " + c.get("key") + " is written: " + job.log());
        }
    }

    /** A changed value of the setting's type, or null when the test does not change such a setting */
    private static Object changedValue(Map<String, Object> setting) {
        Object v = setting.get("value");
        return switch (String.valueOf(setting.get("type"))) {
            case "String" -> v == null ? null : v + " (it)";
            case "Integer", "Long" -> v instanceof Number n ? n.longValue() + 1 : null;
            case "Boolean" -> v instanceof Boolean b ? !b : null;
            default -> null;
        };
    }

    /** The stored value of every setting row, as text */
    private Map<String, Object> storedValues() {
        Map<String, Object> out = new LinkedHashMap<>();
        rows("SELECT setting_key, COALESCE(setting_value, '<null>') FROM public.\"Setting\" ORDER BY setting_key")
                .forEach(r -> out.put(String.valueOf(r.get(0)), r.get(1)));
        return out;
    }
}
