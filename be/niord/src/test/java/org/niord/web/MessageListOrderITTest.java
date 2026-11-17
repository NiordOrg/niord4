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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Attachment;
import org.niord.core.message.Message;
import org.niord.core.message.MessageService;
import org.niord.core.message.Reference;
import org.niord.core.repo.RepoStore;
import org.niord.core.util.UidUtils;
import org.niord.test.ITTest;
import org.niord.test.MessageTestFactory;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ordered lists of a message keep every element, in order, through the editor's path: a message's parts,
 * references and attachments and a part's event dates. Each is a {@code mappedBy} list whose
 * {@code @OrderColumn(name = "indexNo")} is a column of the element's own table, which the element also maps; a
 * writable mapping of it shadows the order column under Hibernate ORM 7, so every element was stored with index 0
 * and a reload kept one element per list (the fault {@code Feature.indexNo} describes for features).
 *
 * <p>The message is created with {@code POST /rest/messages/message}, read back with {@code GET} over REST and in a
 * new transaction, updated with {@code PUT} from what {@code GET /rest/messages/editable-message/{id}} answers (as the
 * editor does), saved once more unchanged, and the order columns are read with SQL. A message stored with distinct
 * indexes in another order than its rows' ids (as the old system stored lists) loads, saves and reloads whole. A
 * message stored with every index 0 (as the faulty versions stored lists), or with a gap, is found by
 * {@link #DAMAGED_LISTS} and restored by {@link #REPAIR_INDEXES}: the diagnostic and the repair offered to the
 * owner for deployments that ran those versions, run here as written.
 *
 * <p>Create and update commit on the server, so each message is removed afterwards in a fresh transaction. This is
 * a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class MessageListOrderITTest extends ITTest {

    /**
     * The lists of a message whose stored indexes are not exactly 0 to n-1: one row per list, naming its table, the
     * message, the parent row (the message, or the part for event dates), its row count and its indexes in row-id
     * order. Read-only.
     */
    static final String DAMAGED_LISTS = """
            SELECT l.list_table, m.uid, m."shortId", m.status, l.parent_id, l.row_count, l.indexes_by_id
            FROM (
                SELECT 'MessagePart' AS list_table, message_id, message_id AS parent_id, count(*) AS row_count,
                       string_agg(CAST("indexNo" AS text), ',' ORDER BY id) AS indexes_by_id,
                       count(DISTINCT "indexNo") AS distinct_indexes, min("indexNo") AS lowest, max("indexNo") AS highest
                FROM public."MessagePart" GROUP BY message_id
                UNION ALL
                SELECT 'Reference', message_id, message_id, count(*),
                       string_agg(CAST("indexNo" AS text), ',' ORDER BY id),
                       count(DISTINCT "indexNo"), min("indexNo"), max("indexNo")
                FROM public."Reference" GROUP BY message_id
                UNION ALL
                SELECT 'Attachment', message_id, message_id, count(*),
                       string_agg(CAST("indexNo" AS text), ',' ORDER BY id),
                       count(DISTINCT "indexNo"), min("indexNo"), max("indexNo")
                FROM public."Attachment" GROUP BY message_id
                UNION ALL
                SELECT 'DateInterval', p.message_id, d."messagePart_id", count(*),
                       string_agg(CAST(d."indexNo" AS text), ',' ORDER BY d.id),
                       count(DISTINCT d."indexNo"), min(d."indexNo"), max(d."indexNo")
                FROM public."DateInterval" d JOIN public."MessagePart" p ON p.id = d."messagePart_id"
                GROUP BY p.message_id, d."messagePart_id"
            ) l
            JOIN public."Message" m ON m.id = l.message_id
            WHERE l.row_count <> l.distinct_indexes OR l.lowest <> 0 OR l.highest <> l.row_count - 1
            ORDER BY m.id, l.list_table, l.parent_id
            """;

    /**
     * Renumbers the lists {@link #DAMAGED_LISTS} finds 0 to n-1, by their stored index and then by row id, one
     * statement per table; a list whose indexes are 0 to n-1 is left as it is. A list stored with every index 0
     * gets the order of its row ids: rows are inserted in list order, so the ids give the order of the rows of one
     * save. A list with a gap keeps its order.
     */
    static final List<String> REPAIR_INDEXES = List.of(
            renumber("MessagePart", "message_id"),
            renumber("Reference", "message_id"),
            renumber("Attachment", "message_id"),
            renumber("DateInterval", "\"messagePart_id\""));

    /**
     * {@link #REPAIR_INDEXES} limited to the lists of the message whose uid is the parameter {@code :uid}: the
     * cleanup repairs its own message only, never the rows of another test that shares the database.
     */
    private static final List<String> REPAIR_INDEXES_OF_ONE_MESSAGE = List.of(
            renumber("MessagePart", "message_id",
                    " AND message_id = (SELECT id FROM public.\"Message\" WHERE uid = :uid)"),
            renumber("Reference", "message_id",
                    " AND message_id = (SELECT id FROM public.\"Message\" WHERE uid = :uid)"),
            renumber("Attachment", "message_id",
                    " AND message_id = (SELECT id FROM public.\"Message\" WHERE uid = :uid)"),
            renumber("DateInterval", "\"messagePart_id\"",
                    " AND \"messagePart_id\" IN (SELECT p.id FROM public.\"MessagePart\" p"
                            + " JOIN public.\"Message\" m ON m.id = p.message_id WHERE m.uid = :uid)"));

    private static String renumber(String table, String parent) {
        return renumber(table, parent, "");
    }

    private static String renumber(String table, String parent, String scope) {
        return """
                UPDATE public."%1$s" t SET "indexNo" = n.new_index
                FROM (SELECT id, row_number() OVER (PARTITION BY %2$s ORDER BY "indexNo", id) - 1 AS new_index
                      FROM public."%1$s"
                      WHERE %2$s IN (SELECT %2$s FROM public."%1$s" GROUP BY %2$s
                                     HAVING count(*) <> count(DISTINCT "indexNo") OR min("indexNo") <> 0
                                         OR max("indexNo") <> count(*) - 1)%3$s) n
                WHERE t.id = n.id AND t."indexNo" <> n.new_index
                """.formatted(table, parent, scope);
    }

    private static final long JAN = millis("2026-01-05T08:00:00Z");
    private static final long FEB = millis("2026-02-05T08:00:00Z");
    private static final long MAR = millis("2026-03-05T08:00:00Z");
    private static final long APR = millis("2026-04-05T08:00:00Z");
    private static final long MAY = millis("2026-05-05T08:00:00Z");

    @Autowired
    MessageService messageService;

    @Autowired
    RepoStore store;

    @PersistenceContext
    EntityManager em;

    private final List<String> createdUids = new ArrayList<>();
    private final List<String> folders = new ArrayList<>();

    @AfterEach
    void removeTheMessages() {
        for (String uid : createdUids) {
            // A list a test left with duplicate indexes holds rows its loaded list does not: number them first, so
            // the removal's cascade reaches every row
            repairTheListsOf(uid);
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.uid = :uid")
                        .setParameter("uid", uid).executeUpdate();
                em.createQuery("select m from Message m where m.uid = :uid", Message.class)
                        .setParameter("uid", uid).getResultStream().findFirst().ifPresent(em::remove);
            });
            store.deleteFolder(UidUtils.uidToHashedFolderPath("messages", uid));
        }
        folders.forEach(store::deleteFolder);
        createdUids.clear();
        folders.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Create, update, save unchanged
    // ------------------------------------------------------------------------------------------- //

    @Test
    void everyListKeepsEveryElementInOrderThroughCreateUpdateAndAnUnchangedSave() {
        String uid = UidUtils.newUid();
        String editRepoPath = newTempDir();
        Map<String, Object> msg = draft(uid, editRepoPath);
        msg.put("parts", list(
                part("Part 1", interval(JAN), interval(FEB), interval(MAR)),
                part("Part 2", interval(APR)),
                part("Part 3")));
        msg.put("references", list(reference("NW-001-26"), reference("NW-002-26"), reference("NW-003-26")));
        msg.put("attachments", list(
                attachment(editRepoPath, "a1.txt"), attachment(editRepoPath, "a2.txt"),
                attachment(editRepoPath, "a3.txt")));
        create(msg);

        Shape created = new Shape(
                List.of("Part 1", "Part 2", "Part 3"),
                List.of(List.of(JAN, FEB, MAR), List.of(APR), List.of()),
                List.of("NW-001-26", "NW-002-26", "NW-003-26"),
                List.of("a1.txt", "a2.txt", "a3.txt"));
        assertStored(created, uid, "after the create");

        // The editor's update: Part 2 removed, Part 3 moved first, Part 4 added; in Part 1 the February interval
        // removed and a May interval added; a reference and an attachment removed, the last moved first, one added
        Map<String, Object> editable = editable(uid);
        List<Map<String, Object>> parts = listOf(editable, "parts");
        Map<String, Object> part1 = parts.get(0);
        List<Map<String, Object>> part1Dates = listOf(part1, "eventDates");
        part1Dates.remove(1);
        part1Dates.add(interval(MAY));
        editable.put("parts", list(parts.get(2), part1, part("Part 4")));
        List<Map<String, Object>> references = listOf(editable, "references");
        editable.put("references", list(references.get(2), references.get(0), reference("NW-004-26")));
        List<Map<String, Object>> attachments = listOf(editable, "attachments");
        editable.put("attachments", list(attachments.get(2), attachments.get(0),
                attachment((String) editable.get("editRepoPath"), "a4.txt")));
        update(uid, editable);

        Shape updated = new Shape(
                List.of("Part 3", "Part 1", "Part 4"),
                List.of(List.of(), List.of(JAN, MAR, MAY), List.of()),
                List.of("NW-003-26", "NW-001-26", "NW-004-26"),
                List.of("a3.txt", "a1.txt", "a4.txt"));
        assertStored(updated, uid, "after the update");

        // Saved once more as the editor loads it: nothing changes
        update(uid, editable(uid));
        assertStored(updated, uid, "after an unchanged save");
    }

    // ------------------------------------------------------------------------------------------- //
    // A list stored with distinct indexes, as the old system stored them
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessageStoredWithDistinctIndexesInAnotherOrderThanItsRowsLoadsSavesAndReloadsWhole() {
        String uid = createThreeOfEach();
        // Each list numbered against the order of its rows' ids: the stored index, not the row order, is the order
        for (String[] list : new String[][] {
                { "MessagePart", "message_id", "message_id" },
                { "Reference", "message_id", "message_id" },
                { "Attachment", "message_id", "message_id" },
                { "DateInterval", "\"messagePart_id\"",
                        "(SELECT p.message_id FROM public.\"MessagePart\" p WHERE p.id = \"messagePart_id\")" } }) {
            Tx.requiringNew().run(() -> em.createNativeQuery("""
                    UPDATE public."%1$s" t SET "indexNo" = n.reversed
                    FROM (SELECT id, count(*) OVER (PARTITION BY %2$s) - row_number() OVER (PARTITION BY %2$s ORDER BY id)
                                     AS reversed
                          FROM public."%1$s"
                          WHERE %3$s = (SELECT id FROM public."Message" WHERE uid = :uid)) n
                    WHERE t.id = n.id
                    """.formatted(list[0], list[1], list[2])).setParameter("uid", uid).executeUpdate());
        }

        Shape reversed = new Shape(
                List.of("Part 3", "Part 2", "Part 1"),
                List.of(List.of(), List.of(APR), List.of(FEB, JAN)),
                List.of("NW-003-26", "NW-002-26", "NW-001-26"),
                List.of("a3.txt", "a2.txt", "a1.txt"));
        assertEquals(reversed, Shape.of(message(uid)), "the stored indexes give the order");

        update(uid, editable(uid));

        // The save keeps the order it loaded; a part's event dates are saved in date order, as the editor saves them
        Shape saved = new Shape(
                reversed.parts(),
                List.of(List.of(), List.of(APR), List.of(JAN, FEB)),
                reversed.references(),
                reversed.attachments());
        assertStored(saved, uid, "after the save of a message stored with distinct indexes");
    }

    // ------------------------------------------------------------------------------------------- //
    // A list stored with every index 0, as the faulty versions stored them: the diagnostic and the repair
    // ------------------------------------------------------------------------------------------- //

    /** What {@link #damageTheListsOf} leaves, as {@link #diagnosed} reads it */
    private static final Map<String, String> DAMAGED = Map.of(
            "MessagePart", "0,0,0", "Reference", "4,2,0", "Attachment", "0,0,0", "DateInterval", "0,0");

    @Test
    void theDiagnosticFindsDamagedListsAndTheRepairRestoresThem() {
        String uid = createThreeOfEach();
        damageTheListsOf(uid);

        assertEquals(DAMAGED, diagnosed(uid),
                "the diagnostic names each list of the message, with its indexes in row order");

        Tx.requiringNew().run(() -> REPAIR_INDEXES.forEach(sql -> em.createNativeQuery(sql).executeUpdate()));

        assertEquals(Map.of(), diagnosed(uid), "after the repair the diagnostic names none of its lists");
        // The lists stored with one index get their rows' order, the references keep the order of their indexes
        Shape repaired = new Shape(
                List.of("Part 1", "Part 2", "Part 3"),
                List.of(List.of(JAN, FEB), List.of(APR), List.of()),
                List.of("NW-003-26", "NW-002-26", "NW-001-26"),
                List.of("a1.txt", "a2.txt", "a3.txt"));
        assertStored(repaired, uid, "after the repair");

        update(uid, editable(uid));
        assertStored(repaired, uid, "after a save of the repaired message");
    }

    @Test
    void theCleanupRepairsTheListsOfItsOwnMessageOnly() {
        String own = createThreeOfEach();
        String other = createThreeOfEach();
        damageTheListsOf(own);
        damageTheListsOf(other);

        repairTheListsOf(own);

        assertEquals(Map.of(), diagnosed(own), "the cleanup's message is repaired");
        assertEquals(DAMAGED, diagnosed(other), "another message's damaged lists are left as they are");
    }

    /**
     * Stores the message's lists as the faulty versions did: the parts, attachments and event dates with index 0, and
     * the references with gaps, numbered against their rows' order (4, 2, 0)
     */
    private void damageTheListsOf(String uid) {
        Tx.requiringNew().run(() -> {
            for (String sql : List.of(
                    "UPDATE public.\"MessagePart\" SET \"indexNo\" = 0"
                            + " WHERE message_id = (SELECT id FROM public.\"Message\" WHERE uid = :uid)",
                    "UPDATE public.\"Attachment\" SET \"indexNo\" = 0"
                            + " WHERE message_id = (SELECT id FROM public.\"Message\" WHERE uid = :uid)",
                    "UPDATE public.\"DateInterval\" SET \"indexNo\" = 0 WHERE \"messagePart_id\" IN (SELECT p.id"
                            + " FROM public.\"MessagePart\" p JOIN public.\"Message\" m ON m.id = p.message_id"
                            + " WHERE m.uid = :uid)",
                    "UPDATE public.\"Reference\" r SET \"indexNo\" = 2 * (n.total - n.position) FROM (SELECT id,"
                            + " row_number() OVER (ORDER BY id) AS position, count(*) OVER () AS total"
                            + " FROM public.\"Reference\""
                            + " WHERE message_id = (SELECT id FROM public.\"Message\" WHERE uid = :uid)) n"
                            + " WHERE r.id = n.id")) {
                em.createNativeQuery(sql).setParameter("uid", uid).executeUpdate();
            }
        });
    }

    /** The cleanup's repair: {@link #REPAIR_INDEXES} on the lists of the one message, committed */
    private void repairTheListsOf(String uid) {
        Tx.requiringNew().run(() -> REPAIR_INDEXES_OF_ONE_MESSAGE.forEach(
                sql -> em.createNativeQuery(sql).setParameter("uid", uid).executeUpdate()));
    }

    // ------------------------------------------------------------------------------------------- //
    // The service path, with the factory's message
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void theServiceStoresTwoOfEachInOrder() throws Exception {
        Message created = messageService.createMessage(MessageTestFactory.draftNwWithTwoOfEach("IT-two-of-each"));
        em.flush();
        em.clear();

        Message reloaded = em.find(Message.class, created.getId());
        assertEquals(new Shape(
                        List.of("subject", "subject 2"),
                        List.of(List.of(MessageTestFactory.FIRST_EVENT_DATE.getTime(),
                                MessageTestFactory.SECOND_EVENT_DATE.getTime()), List.of()),
                        List.of("NW-001-26", "NW-002-26"),
                        List.of("first.txt", "second.txt")),
                Shape.of(reloaded));
    }

    // ------------------------------------------------------------------------------------------- //
    // The shape of a message's lists, and where it is read
    // ------------------------------------------------------------------------------------------- //

    /** A message's lists: the parts by subject, each part's event dates by start, the references, the attachments */
    record Shape(List<String> parts, List<List<Long>> eventDates, List<String> references, List<String> attachments) {

        static Shape of(Message message) {
            return new Shape(
                    message.getParts().stream().map(p -> p.getDesc("en").getSubject()).toList(),
                    message.getParts().stream()
                            .map(p -> p.getEventDates().stream().map(d -> d.getFromDate().getTime()).toList())
                            .toList(),
                    message.getReferences().stream().map(Reference::getMessageId).toList(),
                    message.getAttachments().stream().map(Attachment::getFileName).toList());
        }

        static Shape of(Map<String, Object> message) {
            List<Map<String, Object>> parts = listOf(message, "parts");
            return new Shape(
                    parts.stream().map(p -> (String) listOf(p, "descs").getFirst().get("subject")).toList(),
                    parts.stream().map(p -> listOf(p, "eventDates").stream()
                            .map(d -> millis(d.get("fromDate"))).toList()).toList(),
                    listOf(message, "references").stream().map(r -> (String) r.get("messageId")).toList(),
                    listOf(message, "attachments").stream().map(a -> (String) a.get("fileName")).toList());
        }
    }

    /**
     * Asserts the message's lists over REST and in a new transaction, and that each order column holds 0 to n-1,
     * one row per element and no other row
     */
    private void assertStored(Shape expected, String uid, String when) {
        assertEquals(expected, Shape.of(message(uid)), "the REST read " + when);
        assertEquals(expected, Tx.requiringNew().call(() -> Shape.of(
                em.createQuery("select m from Message m where m.uid = :uid", Message.class)
                        .setParameter("uid", uid).getSingleResult())), "a read in a new transaction " + when);

        assertEquals(range(expected.parts().size()), indexes("""
                SELECT p."indexNo" FROM public."MessagePart" p JOIN public."Message" m ON m.id = p.message_id
                WHERE m.uid = :uid ORDER BY p."indexNo"
                """, uid), "the parts' indexes " + when);
        assertEquals(range(expected.references().size()), indexes("""
                SELECT r."indexNo" FROM public."Reference" r JOIN public."Message" m ON m.id = r.message_id
                WHERE m.uid = :uid ORDER BY r."indexNo"
                """, uid), "the references' indexes " + when);
        assertEquals(range(expected.attachments().size()), indexes("""
                SELECT a."indexNo" FROM public."Attachment" a JOIN public."Message" m ON m.id = a.message_id
                WHERE m.uid = :uid ORDER BY a."indexNo"
                """, uid), "the attachments' indexes " + when);

        // The event dates' indexes, by their part's index
        Map<Integer, List<Integer>> expectedDates = new TreeMap<>();
        for (int i = 0; i < expected.eventDates().size(); i++) {
            if (!expected.eventDates().get(i).isEmpty()) {
                expectedDates.put(i, range(expected.eventDates().get(i).size()));
            }
        }
        Map<Integer, List<Integer>> storedDates = new TreeMap<>();
        for (Object[] row : rows("""
                SELECT p."indexNo", d."indexNo" FROM public."DateInterval" d
                JOIN public."MessagePart" p ON p.id = d."messagePart_id"
                JOIN public."Message" m ON m.id = p.message_id
                WHERE m.uid = :uid ORDER BY p."indexNo", d."indexNo"
                """, uid)) {
            storedDates.computeIfAbsent(((Number) row[0]).intValue(), k -> new ArrayList<>())
                    .add(((Number) row[1]).intValue());
        }
        assertEquals(expectedDates, storedDates, "the event dates' indexes by part " + when);
    }

    /** The rows of a native query, with the parameter uid when the query names it */
    private List<Object[]> rows(String sql, String uid) {
        return Tx.requiringNew().call(() -> {
            Query query = em.createNativeQuery(sql);
            if (uid != null) {
                query.setParameter("uid", uid);
            }
            List<Object[]> rows = new ArrayList<>();
            for (Object row : query.getResultList()) {
                rows.add((Object[]) row);
            }
            return rows;
        });
    }

    private List<Integer> indexes(String sql, String uid) {
        return Tx.requiringNew().call(() -> em.createNativeQuery(sql, Integer.class)
                .setParameter("uid", uid).getResultList());
    }

    /** The lists of the message that {@link #DAMAGED_LISTS} names, table to indexes */
    private Map<String, String> diagnosed(String uid) {
        Map<String, String> lists = new HashMap<>();
        for (Object[] row : rows(DAMAGED_LISTS, null)) {
            if (uid.equals(row[1])) {
                assertTrue(lists.put((String) row[0], (String) row[6]) == null,
                        "one list per table for this message: " + row[0]);
            }
        }
        return lists;
    }

    private static List<Integer> range(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    // ------------------------------------------------------------------------------------------- //
    // REST
    // ------------------------------------------------------------------------------------------- //

    /** Creates a message with three parts (two event dates in the first, one in the second), references, attachments */
    private String createThreeOfEach() {
        String uid = UidUtils.newUid();
        String editRepoPath = newTempDir();
        Map<String, Object> msg = draft(uid, editRepoPath);
        msg.put("parts", list(part("Part 1", interval(JAN), interval(FEB)), part("Part 2", interval(APR)),
                part("Part 3")));
        msg.put("references", list(reference("NW-001-26"), reference("NW-002-26"), reference("NW-003-26")));
        msg.put("attachments", list(
                attachment(editRepoPath, "a1.txt"), attachment(editRepoPath, "a2.txt"),
                attachment(editRepoPath, "a3.txt")));
        create(msg);
        return uid;
    }

    private void create(Map<String, Object> msg) {
        createdUids.add((String) msg.get("id"));
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(msg)
                .when().post("/rest/messages/message")
                .then().statusCode(200);
    }

    private void update(String uid, Map<String, Object> msg) {
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .contentType("application/json")
                .body(msg)
                .when().put("/rest/messages/message/" + uid)
                .then().statusCode(200);
    }

    /** The message as the public read answers it */
    private Map<String, Object> message(String uid) {
        return given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/message/" + uid)
                .then().statusCode(200)
                .extract().jsonPath().getMap("");
    }

    /** The message as the editor loads it, with a new edit folder */
    private Map<String, Object> editable(String uid) {
        Map<String, Object> msg = given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/messages/editable-message/" + uid)
                .then().statusCode(200)
                .extract().jsonPath().getMap("");
        assertFalse(msg.isEmpty(), "the editable message");
        folders.add((String) msg.get("editRepoPath"));
        return msg;
    }

    private String newTempDir() {
        String path = given().auth().oauth2(accessToken())
                .when().get("/rest/repo/new-temp-dir")
                .then().statusCode(200).extract().path("path");
        folders.add(path);
        return path;
    }

    // ------------------------------------------------------------------------------------------- //
    // The message body
    // ------------------------------------------------------------------------------------------- //

    /** A draft NW body as the editor sends it, without lists */
    private static Map<String, Object> draft(String uid, String editRepoPath) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("id", uid);
        msg.put("repoPath", UidUtils.uidToHashedFolderPath("messages", uid));
        msg.put("editRepoPath", editRepoPath);
        msg.put("revision", 0);
        msg.put("mainType", "NW");
        msg.put("type", "LOCAL_WARNING");
        msg.put("status", "DRAFT");
        msg.put("autoTitle", false);
        msg.put("messageSeries", new HashMap<>(Map.of("seriesId", "dma-nw", "mainType", "NW")));
        msg.put("descs", list(new HashMap<>(Map.of("lang", "en", "title", "IT-list-order"))));
        return msg;
    }

    @SafeVarargs
    private static Map<String, Object> part(String subject, Map<String, Object>... eventDates) {
        Map<String, Object> part = new HashMap<>();
        part.put("type", "DETAILS");
        part.put("descs", list(new HashMap<>(Map.of("lang", "en", "subject", subject, "details", "<p>details</p>"))));
        if (eventDates.length > 0) {
            part.put("eventDates", list(eventDates));
        }
        return part;
    }

    private static Map<String, Object> interval(long from) {
        return new HashMap<>(Map.of("allDay", false, "fromDate", from, "toDate", from + 3_600_000L));
    }

    private static Map<String, Object> reference(String messageId) {
        return new HashMap<>(Map.of("messageId", messageId, "type", "REFERENCE"));
    }

    private static Map<String, Object> attachment(String editRepoPath, String fileName) {
        return new HashMap<>(Map.of("path", editRepoPath + "/" + fileName, "fileName", fileName,
                "type", "text/plain"));
    }

    @SafeVarargs
    private static <T> List<T> list(T... elements) {
        return new ArrayList<>(List.of(elements));
    }

    /** The list under the key, empty when the body leaves it out */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> map, String key) {
        Object list = map.get(key);
        return list == null ? new ArrayList<>() : (List<Map<String, Object>>) list;
    }

    /** A date as JSON carries it (epoch milliseconds or an ISO-8601 instant) */
    private static long millis(Object date) {
        return date instanceof Number n ? n.longValue() : Instant.parse((String) date).toEpochMilli();
    }

    private static long millis(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }
}
