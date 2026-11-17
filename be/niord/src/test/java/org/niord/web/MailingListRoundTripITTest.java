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
import org.niord.core.mailinglist.ScheduleType;
import org.niord.core.mailinglist.ScheduledExecutionTimeUtil;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save, reload, save again for the mailing lists and the contacts, through the REST interface as a system
 * administrator (who manages the lists) and an administrator (who manages the contacts and the recipients): a list
 * with descriptions in two languages, a status-change trigger and a scheduled trigger with three and two script
 * resource paths and subjects in two languages, two users and three contacts as recipients.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class MailingListRoundTripITTest extends RoundTripSupport {

    private final List<String> mailingListIds = new ArrayList<>();
    private final List<Integer> contactIds = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        mailingListIds.forEach(id -> delete("sysadmin", "/rest/mailing-lists/mailing-list/" + id));
        contactIds.forEach(id -> delete("admin", "/rest/contacts/contact/" + id));
        mailingListIds.clear();
        contactIds.clear();
    }

    /** The rows a mailing list holds, by its mailingListId */
    private static final Map<String, String> LIST_ROWS = new LinkedHashMap<>();

    private static final String LIST = "(SELECT id FROM public.\"MailingList\" WHERE \"mailingListId\" = ?1)";
    private static final String TRIGGERS = "(SELECT id FROM public.\"MailingListTrigger\" WHERE \"mailingList_id\" = " + LIST + ")";

    static {
        LIST_ROWS.put("descs", "SELECT count(*) FROM public.\"MailingListDesc\" WHERE entity_id = " + LIST);
        LIST_ROWS.put("triggers", "SELECT count(*) FROM public.\"MailingListTrigger\" WHERE \"mailingList_id\" = " + LIST);
        LIST_ROWS.put("trigger descs", "SELECT count(*) FROM public.\"MailingListTriggerDesc\" WHERE entity_id IN " + TRIGGERS);
        LIST_ROWS.put("trigger script paths", "SELECT count(*) FROM public.\"MailingListTrigger_scriptResourcePaths\""
                + " WHERE \"MailingListTrigger_id\" IN " + TRIGGERS);
        LIST_ROWS.put("trigger status changes", "SELECT count(*) FROM public.\"MailingListTrigger_statusChanges\""
                + " WHERE \"MailingListTrigger_id\" IN " + TRIGGERS);
        LIST_ROWS.put("users", "SELECT count(*) FROM public.\"MailingList_User\" WHERE \"MailingList_id\" = " + LIST);
        LIST_ROWS.put("contacts", "SELECT count(*) FROM public.\"MailingList_Contact\" WHERE \"MailingList_id\" = " + LIST);
    }

    @Test
    void aMailingListKeepsItsDescriptionsTriggersAndRecipientsThroughCreateUpdateAnUnchangedSaveAndDelete() {
        String listId = "it-rt-ml-" + unique();
        String base = "/rest/mailing-lists/mailing-list/" + listId;
        post("sysadmin", "/rest/mailing-lists/mailing-list", map(
                "mailingListId", listId, "active", true,
                "descs", list(map("lang", "en", "name", "IT-RT list", "description", "The list"),
                        map("lang", "da", "name", "IT-RT liste", "description", "Listen")),
                "triggers", list(statusTrigger(), scheduledTrigger())));
        mailingListIds.add(listId);

        Map<String, Object> read = getMap("sysadmin", base);
        assertEquals(true, read.get("active"));
        assertEquals(Map.of("en", "IT-RT list", "da", "IT-RT liste"), descs(read, "name"));
        assertEquals(Map.of("en", "The list", "da", "Listen"), descs(read, "description"));
        Map<String, Map<String, Object>> triggers = triggers(read);
        assertEquals(Set.of("STATUS_CHANGE", "SCHEDULED"), triggers.keySet());
        Map<String, Object> status = triggers.get("STATUS_CHANGE");
        assertEquals(Set.of("PUBLISHED", "CANCELLED"), new HashSet<>(valuesOf(status, "statusChanges")));
        assertEquals("msg.type == 'LOCAL_WARNING'", status.get("messageFilter"));
        assertEquals(List.of("templates/it-rt/m1.ftl", "templates/it-rt/m2.ftl", "templates/it-rt/m3.js"),
                valuesOf(status, "scriptResourcePaths"), "the status trigger's script paths in order");
        assertEquals(Map.of("en", "Published", "da", "Udgivet"), descs(status, "subject"));
        Map<String, Object> scheduled = triggers.get("SCHEDULED");
        assertEquals(List.of("MONDAY", "08:00", "Europe/Copenhagen", "messageSeries=dma-nw&status=PUBLISHED", true),
                List.of(scheduled.get("scheduleType"), scheduled.get("scheduledExecutionTime"),
                        scheduled.get("scheduledExecutionTimeZone"), scheduled.get("messageQuery"), scheduled.get("publicReport")));
        assertEquals(List.of("templates/it-rt/w1.ftl", "templates/it-rt/w2.ftl"), valuesOf(scheduled, "scriptResourcePaths"));
        assertEquals(Map.of("en", "Weekly", "da", "Ugentlig"), descs(scheduled, "subject"));
        assertTriggerPathIndexes(listId, "after the create");

        // Recipients: two users and three contacts
        List<Map<String, Object>> users = twoUsers(listId);
        List<Integer> contacts = List.of(contact("a"), contact("b"), contact("c"));
        as("admin").body(users).when().put(base + "/users").then().statusCode(200);
        as("admin").body(contacts.stream().map(id -> contactVo(id)).toList()).when().put(base + "/contacts")
                .then().statusCode(200);
        assertEquals(users.stream().map(u -> u.get("username")).collect(java.util.stream.Collectors.toSet()),
                new HashSet<>(fieldOf(getMap("admin", base + "/users"), "selectedRecipients", "username")));
        assertEquals(new HashSet<>(contacts), new HashSet<>(fieldOf(getMap("admin", base + "/contacts"),
                "selectedRecipients", "id").stream().map(v -> ((Number) v).intValue()).toList()));

        // Update: the status trigger's paths reordered with one removed, a status swapped and the Danish subject
        // changed; the scheduled trigger replaced by a daily one; the list's English description changed
        Map<String, Object> edit = getMap("sysadmin", base);
        Map<String, Map<String, Object>> editTriggers = triggers(edit);
        Map<String, Object> s = editTriggers.get("STATUS_CHANGE");
        s.put("scriptResourcePaths", list("templates/it-rt/m3.js", "templates/it-rt/m1.ftl"));
        s.put("statusChanges", list("PUBLISHED", "EXPIRED"));
        listOf(s, "descs").stream().filter(d -> "da".equals(d.get("lang"))).forEach(d -> d.put("subject", "Udgivne"));
        edit.put("triggers", list(s, map("type", "SCHEDULED", "scheduleType", "DAILY", "scheduledExecutionTime", "06:30",
                "scheduledExecutionTimeZone", "UTC", "messageQuery", "status=PUBLISHED", "publicReport", false,
                "scriptResourcePaths", list("templates/it-rt/d1.ftl", "templates/it-rt/d2.ftl", "templates/it-rt/d3.ftl"),
                "descs", list(map("lang", "en", "subject", "Daily"), map("lang", "da", "subject", "Daglig")))));
        listOf(edit, "descs").stream().filter(d -> "en".equals(d.get("lang"))).forEach(d -> d.put("description", "The new list"));
        put("sysadmin", base, edit);

        Map<String, Object> updated = getMap("sysadmin", base);
        assertEquals(Map.of("en", "The new list", "da", "Listen"), descs(updated, "description"));
        Map<String, Map<String, Object>> updatedTriggers = triggers(updated);
        assertEquals(List.of("templates/it-rt/m3.js", "templates/it-rt/m1.ftl"),
                valuesOf(updatedTriggers.get("STATUS_CHANGE"), "scriptResourcePaths"));
        assertEquals(Set.of("PUBLISHED", "EXPIRED"), new HashSet<>(valuesOf(updatedTriggers.get("STATUS_CHANGE"), "statusChanges")));
        assertEquals(Map.of("en", "Published", "da", "Udgivne"), descs(updatedTriggers.get("STATUS_CHANGE"), "subject"));
        assertEquals(List.of("DAILY", "06:30", "UTC", false), List.of(updatedTriggers.get("SCHEDULED").get("scheduleType"),
                updatedTriggers.get("SCHEDULED").get("scheduledExecutionTime"),
                updatedTriggers.get("SCHEDULED").get("scheduledExecutionTimeZone"),
                updatedTriggers.get("SCHEDULED").get("publicReport")));
        assertEquals(List.of("templates/it-rt/d1.ftl", "templates/it-rt/d2.ftl", "templates/it-rt/d3.ftl"),
                valuesOf(updatedTriggers.get("SCHEDULED"), "scriptResourcePaths"));
        assertTriggerPathIndexes(listId, "after the update");
        assertEquals(2L, count(LIST_ROWS.get("users"), listId), "the list's update keeps its users");
        assertEquals(3L, count(LIST_ROWS.get("contacts"), listId), "the list's update keeps its contacts");

        // Unchanged save: no row is added or lost
        Map<String, Long> rowsBefore = counts(LIST_ROWS, listId);
        put("sysadmin", base, getMap("sysadmin", base));
        assertEquals(rowsBefore, counts(LIST_ROWS, listId), "the rows of an unchanged save");
        assertEquals(updatedTriggers.keySet(), triggers(getMap("sysadmin", base)).keySet());
        assertTriggerPathIndexes(listId, "after an unchanged save");

        // Delete: nothing of the list is left; its users and contacts stay
        delete("sysadmin", base).statusCode(204);
        mailingListIds.remove(listId);
        Map<String, Long> none = new LinkedHashMap<>();
        LIST_ROWS.keySet().forEach(k -> none.put(k, 0L));
        assertEquals(none, counts(LIST_ROWS, listId), "the rows the delete leaves");
        assertEquals(0L, count("SELECT count(*) FROM public.\"MailingListTrigger\" t WHERE NOT EXISTS"
                + " (SELECT 1 FROM public.\"MailingList\" m WHERE m.id = t.\"mailingList_id\")"), "no orphan trigger");
        assertEquals(3L, count("SELECT count(*) FROM public.\"Contact\" WHERE id IN (?1, ?2, ?3)",
                contacts.get(0), contacts.get(1), contacts.get(2)), "the contacts outlive the list");
    }

    /**
     * An unchanged save of a mailing list replaces every trigger row: {@code MailingListService.updateMailingList}
     * clears the triggers and adds new ones built from the request, whose value objects carry no id
     * ({@code MailingListTrigger.toVo}). The row count stays (above), the ids do not, so a public report's link
     * ({@code /rest/mailing-lists/execute-report/{triggerId}.html}, keyed on the trigger's id) stops working after any
     * save of its list. The new row of a scheduled trigger whose schedule did not change takes the old row's
     * {@code nextScheduledExecution} when the list was active (MAILLIST8, below).
     *
     * <p>The right assertion: the trigger ids are the same after the save.
     */
    @Test
    void anUnchangedSaveReplacesEveryTriggerRow_documentsBehavior() {
        String listId = "it-rt-ml-" + unique();
        String base = "/rest/mailing-lists/mailing-list/" + listId;
        post("sysadmin", "/rest/mailing-lists/mailing-list", map(
                "mailingListId", listId, "active", false,
                "descs", list(map("lang", "en", "name", "IT-RT list")),
                "triggers", list(statusTrigger(), scheduledTrigger())));
        mailingListIds.add(listId);
        List<Integer> before = ints("SELECT id FROM public.\"MailingListTrigger\" WHERE \"mailingList_id\" = " + LIST
                + " ORDER BY id", listId);

        put("sysadmin", base, getMap("sysadmin", base));

        List<Integer> after = ints("SELECT id FROM public.\"MailingListTrigger\" WHERE \"mailingList_id\" = " + LIST + " ORDER BY id", listId);
        assertEquals(2, after.size(), "two triggers after the save");
        assertTrue(after.stream().noneMatch(before::contains),
                "today every trigger gets a new row and id: before " + before + ", after " + after);
        assertNotEquals(before, after);
    }

    private static final String NEXT_RUN = "SELECT \"nextScheduledExecution\" FROM public.\"MailingListTrigger\""
            + " WHERE \"mailingList_id\" = " + LIST + " AND type = 'SCHEDULED'";

    /**
     * An unchanged save of an active list keeps its scheduled trigger's next execution, as the scheduler left it
     * (MAILLIST8): here one a failed run postponed, which is not the trigger's regular time, so a save that computed
     * it from now would move it. A save that changes the trigger's time computes it from now.
     */
    @Test
    void anUnchangedSaveKeepsTheScheduledTriggersNextRun_andAChangedTimeRecomputesIt() {
        String listId = "it-rt-ml-" + unique();
        String base = "/rest/mailing-lists/mailing-list/" + listId;
        post("sysadmin", "/rest/mailing-lists/mailing-list", map(
                "mailingListId", listId, "active", true,
                "descs", list(map("lang", "en", "name", "IT-RT list")),
                "triggers", list(statusTrigger(), scheduledTrigger())));
        mailingListIds.add(listId);
        Timestamp postponed = new Timestamp(System.currentTimeMillis() + 40 * 60_000L);
        execute("UPDATE public.\"MailingListTrigger\" SET \"nextScheduledExecution\" = ?1 WHERE type = 'SCHEDULED'"
                + " AND \"mailingList_id\" = (SELECT id FROM public.\"MailingList\" WHERE \"mailingListId\" = ?2)",
                postponed, listId);

        put("sysadmin", base, getMap("sysadmin", base));

        List<Object> kept = column(NEXT_RUN, listId);
        assertEquals(1, kept.size());
        assertEquals(postponed.getTime(), millis(kept.getFirst()),
                "the unchanged save keeps the stored next execution of the scheduled trigger");

        Map<String, Object> edit = getMap("sysadmin", base);
        listOf(edit, "triggers").stream().filter(t -> "SCHEDULED".equals(t.get("type")))
                .forEach(t -> t.put("scheduledExecutionTime", "09:30"));
        Date before = new Date();
        put("sysadmin", base, edit);
        Date after = new Date();

        long next = millis(column(NEXT_RUN, listId).getFirst());
        assertTrue(List.of(before, after).stream().anyMatch(at -> next == ScheduledExecutionTimeUtil
                        .computeNextExecutionTime(at, ScheduleType.MONDAY, "09:30", "Europe/Copenhagen").getTime()),
                "a changed time is computed from now: " + new Date(next));
    }

    /** A timestamp column's value as epoch milliseconds; a value without zone is read in the JVM's, as it was written */
    private static long millis(Object value) {
        if (value instanceof Date date) {
            return date.getTime();
        }
        if (value instanceof java.time.LocalDateTime local) {
            return local.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant().toEpochMilli();
        }
        if (value instanceof java.time.Instant instant) {
            return instant.toEpochMilli();
        }
        throw new AssertionError("not a timestamp: " + value);
    }

    /**
     * A list with two triggers of one type (a status-change trigger for publications and one for cancellations,
     * each with its own filter, template and subject, as a deployment sets up its mails) keeps both, and its
     * scheduled one, through an update and an unchanged save. The test above holds one trigger of each type, so a
     * save that kept one trigger per type would pass it.
     */
    @Test
    void twoTriggersOfOneTypeAreBothKeptThroughAnUpdateAndAnUnchangedSave() {
        String listId = "it-rt-ml-" + unique();
        String base = "/rest/mailing-lists/mailing-list/" + listId;
        Map<String, Object> cancelled = map("type", "STATUS_CHANGE", "statusChanges", list("CANCELLED"),
                "messageFilter", "msg.type == 'COASTAL_WARNING'", "scriptResourcePaths", list("templates/it-rt/c1.ftl"),
                "descs", list(map("lang", "en", "subject", "Cancelled"), map("lang", "da", "subject", "Annulleret")));
        post("sysadmin", "/rest/mailing-lists/mailing-list", map(
                "mailingListId", listId, "active", false,
                "descs", list(map("lang", "en", "name", "IT-RT list")),
                "triggers", list(statusTrigger(), cancelled, scheduledTrigger())));
        mailingListIds.add(listId);
        assertEquals(List.of("SCHEDULED Weekly", "STATUS_CHANGE Cancelled", "STATUS_CHANGE Published"),
                triggerSubjects(getMap("sysadmin", base)), "the three triggers after the create");

        // Update: the cancellation trigger's English subject changed
        Map<String, Object> edit = getMap("sysadmin", base);
        listOf(edit, "triggers").stream().filter(t -> valuesOf(t, "statusChanges").equals(List.of("CANCELLED")))
                .flatMap(t -> listOf(t, "descs").stream()).filter(d -> "en".equals(d.get("lang")))
                .forEach(d -> d.put("subject", "Withdrawn"));
        put("sysadmin", base, edit);
        List<String> expected = List.of("SCHEDULED Weekly", "STATUS_CHANGE Published", "STATUS_CHANGE Withdrawn");
        assertEquals(expected, triggerSubjects(getMap("sysadmin", base)), "the three triggers after the update");

        // Unchanged save
        put("sysadmin", base, getMap("sysadmin", base));
        assertEquals(expected, triggerSubjects(getMap("sysadmin", base)), "the three triggers after an unchanged save");
        assertEquals(3L, count(LIST_ROWS.get("triggers"), listId), "three trigger rows");
    }

    /** Each trigger as its type and English subject, sorted (the triggers have no order column) */
    private static List<String> triggerSubjects(Map<String, Object> mailingList) {
        return listOf(mailingList, "triggers").stream()
                .map(t -> t.get("type") + " " + descs(t, "subject").get("en"))
                .sorted()
                .toList();
    }

    private void assertTriggerPathIndexes(String listId, String when) {
        List<List<Object>> triggersWithPaths = rows("SELECT t.id, count(*) FROM public.\"MailingListTrigger\" t JOIN"
                + " public.\"MailingListTrigger_scriptResourcePaths\" p ON p.\"MailingListTrigger_id\" = t.id"
                + " WHERE t.\"mailingList_id\" = " + LIST + " GROUP BY t.id", listId);
        // Both triggers carry script paths: without this the loop below could check nothing and pass
        assertEquals(2, triggersWithPaths.size(), "the triggers with script paths " + when);
        for (List<Object> row : triggersWithPaths) {
            int triggerId = ((Number) row.get(0)).intValue();
            assertOrderColumn(((Number) row.get(1)).intValue(), ints("SELECT \"indexNo\" FROM"
                    + " public.\"MailingListTrigger_scriptResourcePaths\" WHERE \"MailingListTrigger_id\" = ?1", triggerId),
                    "trigger " + triggerId + "'s script paths " + when);
        }
    }

    private static Map<String, Object> statusTrigger() {
        return map("type", "STATUS_CHANGE", "statusChanges", list("PUBLISHED", "CANCELLED"),
                "messageFilter", "msg.type == 'LOCAL_WARNING'",
                "scriptResourcePaths", list("templates/it-rt/m1.ftl", "templates/it-rt/m2.ftl", "templates/it-rt/m3.js"),
                "descs", list(map("lang", "en", "subject", "Published"), map("lang", "da", "subject", "Udgivet")));
    }

    private static Map<String, Object> scheduledTrigger() {
        return map("type", "SCHEDULED", "scheduleType", "MONDAY", "scheduledExecutionTime", "08:00",
                "scheduledExecutionTimeZone", "Europe/Copenhagen", "messageQuery", "messageSeries=dma-nw&status=PUBLISHED",
                "publicReport", true,
                "scriptResourcePaths", list("templates/it-rt/w1.ftl", "templates/it-rt/w2.ftl"),
                "descs", list(map("lang", "en", "subject", "Weekly"), map("lang", "da", "subject", "Ugentlig")));
    }

    /** The triggers by type; the list holds one of each */
    private static Map<String, Map<String, Object>> triggers(Map<String, Object> mailingList) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        listOf(mailingList, "triggers").forEach(t -> result.put((String) t.get("type"), t));
        assertEquals(listOf(mailingList, "triggers").size(), result.size(), "one trigger of each type");
        return result;
    }

    /** Two users with an e-mail address, as the recipients drawer offers them */
    private static List<Map<String, Object>> twoUsers(String listId) {
        // Each role's first call stores its user
        for (String role : List.of("sysadmin", "admin", "editor", "user")) {
            as(role).when().get("/rest/messages/recently-edited-drafts").then().statusCode(200);
        }
        List<Map<String, Object>> available = listOf(getMap("admin", "/rest/mailing-lists/mailing-list/" + listId + "/users"),
                "availableRecipients");
        assertTrue(available.size() >= 2, "two users with an e-mail address: " + available);
        return List.of(map("username", available.get(0).get("username"), "email", available.get(0).get("email")),
                map("username", available.get(1).get("username"), "email", available.get(1).get("email")));
    }

    // ------------------------------------------------------------------------------------------- //
    // Contacts
    // ------------------------------------------------------------------------------------------- //

    private int contact(String name) {
        String email = "it-rt-" + name + "-" + unique() + "@example.org";
        int id = ((Number) post("admin", "/rest/contacts/contact", map("email", email, "name", "IT-RT " + name,
                "language", "da")).get("id")).intValue();
        contactIds.add(id);
        return id;
    }

    private Map<String, Object> contactVo(int id) {
        List<List<Object>> row = rows("SELECT email, name, language FROM public.\"Contact\" WHERE id = ?1", id);
        return map("id", id, "email", row.getFirst().get(0), "name", row.getFirst().get(1), "language", row.getFirst().get(2));
    }

    @Test
    void aContactKeepsItsFieldsThroughCreateUpdateAnUnchangedSaveAndDelete() {
        String email = "it-rt-contact-" + unique() + "@example.org";
        Map<String, Object> created = post("admin", "/rest/contacts/contact", map("email", email, "name", "IT-RT Contact",
                "language", "da"));
        int id = ((Number) created.get("id")).intValue();
        contactIds.add(id);
        assertEquals(map("id", id, "email", email, "name", "IT-RT Contact", "language", "da"), searched(email));

        Map<String, Object> edit = searched(email);
        edit.put("name", "IT-RT Contact renamed");
        edit.put("language", "en");
        put("admin", "/rest/contacts/contact/" + id, edit);
        assertEquals(map("id", id, "email", email, "name", "IT-RT Contact renamed", "language", "en"), searched(email));

        long contactsBefore = count("SELECT count(*) FROM public.\"Contact\"");
        put("admin", "/rest/contacts/contact/" + id, searched(email));
        assertEquals(map("id", id, "email", email, "name", "IT-RT Contact renamed", "language", "en"), searched(email));
        assertEquals(contactsBefore, count("SELECT count(*) FROM public.\"Contact\""), "an unchanged save adds no row");

        delete("admin", "/rest/contacts/contact/" + id).statusCode(204);
        contactIds.remove((Integer) id);
        assertEquals(0L, count("SELECT count(*) FROM public.\"Contact\" WHERE id = ?1", id));
    }

    private static Map<String, Object> searched(String email) {
        List<Map<String, Object>> found = listOf(getMap("admin", "/rest/contacts/search?name=" + email), "data");
        assertEquals(1, found.size(), "one contact found by its e-mail address: " + found);
        return new LinkedHashMap<>(found.getFirst());
    }
}
