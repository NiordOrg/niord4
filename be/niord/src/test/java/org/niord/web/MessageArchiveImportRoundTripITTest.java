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

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.repo.RepoStore;
import org.niord.core.util.UidUtils;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The message archive: exported ({@code GET /rest/message-io/export.zip}, the Messages page's Export action) and
 * imported ({@code POST /rest/message-io/import}, its Import action, job {@code msg-archive-import}), with the four
 * choices of the import dialog: new UIDs, the status kept, the default series, the base data created.
 *
 * <p>The page's upload is tested first (it once failed on every archive), with one archive and with two in one
 * request; the choices behind it are
 * reached the way {@code scripts/regen-sample.sh} reaches them: the same job run from a system administrator's batch
 * set ({@code POST /rest/batch/execute-batch-set}) with the properties the upload would have set.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class MessageArchiveImportRoundTripITTest extends ImportRoundTripSupport {

    private static final long FUTURE = Instant.parse("2031-03-01T10:00:00Z").toEpochMilli();
    private static final long DAY = 86_400_000L;

    @Autowired
    RepoStore store;

    private final List<String> uids = new ArrayList<>();
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
        for (String uid : uids) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.uid = :uid").setParameter("uid", uid).executeUpdate();
                em.createQuery("select m from Message m where m.uid = :uid", Message.class)
                        .setParameter("uid", uid).getResultStream().findFirst().ifPresent(em::remove);
            });
            store.deleteFolder(UidUtils.uidToHashedFolderPath("messages", uid));
        }
        uids.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // The page's upload
    // ------------------------------------------------------------------------------------------- //

    /**
     * The page's upload starts the job, and the job imports the archive's two messages. It used to answer 200 with
     * "Error executing batch job msg-archive-import with file ...: null" and start no job, since
     * {@code checkBatchJob} read the uploaded stream to its end and closed it before the job was given it; the check
     * and the job now each open a stream of their own from the uploaded part.
     */
    @Test
    void theArchiveUploadOfTheMessagesPageStartsTheJobAndImports() throws IOException {
        List<String> made = twoMessages();
        String tagId = tag(made);
        byte[] archive = exportArchive(tagId);
        assertEquals(2, listOf(messagesJson(archive), "data").size(), "the archive holds the two messages");

        String fileName = "messages-" + unique() + ".zip";
        Response answer = given().auth().oauth2(accessToken("admin")).header("NiordDomain", "niord-nw")
                .multiPart("file", fileName, archive, "application/zip")
                .multiPart("data", json(map("seriesId", "dma-nw", "assignNewUids", true, "preserveStatus", false,
                        "assignDefaultSeries", false, "createBaseData", false)))
                .when().post("/rest/message-io/import");
        assertEquals(200, answer.statusCode(), answer.asString());
        assertTrue(answer.asString().startsWith("Started batch job 'msg-archive-import' with file " + fileName),
                answer.asString());

        // The job's row is written when the job begins, after the answer: the job is found by the file's name
        JobEnd job = awaitJob("msg-archive-import", fileName);
        List<String> imported = importedFrom(made);
        uids.addAll(imported);
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(2, imported.size(), "two new messages: " + job.log());
    }

    /**
     * Two archives in one upload are both imported, each with the choices of the form: the endpoint's check takes the
     * form's {@code data} field out of the parameters it is given, and each file is checked with a copy of its own,
     * so the second archive's check still reads it. It used to start the first archive's job only and answer the
     * second with an error.
     */
    @Test
    void twoArchivesInOneUploadAreBothImported() throws IOException {
        List<String> made = twoMessages();
        byte[] archive = exportArchive(tag(made));

        String first = "messages-a-" + unique() + ".zip";
        String second = "messages-b-" + unique() + ".zip";
        Response answer = given().auth().oauth2(accessToken("admin")).header("NiordDomain", "niord-nw")
                .multiPart("file", first, archive, "application/zip")
                .multiPart("file", second, archive, "application/zip")
                .multiPart("data", json(map("seriesId", "dma-nw", "assignNewUids", true, "preserveStatus", false,
                        "assignDefaultSeries", false, "createBaseData", false)))
                .when().post("/rest/message-io/import");
        assertEquals(200, answer.statusCode(), answer.asString());
        assertTrue(answer.asString().contains("Started batch job 'msg-archive-import' with file " + first)
                        && answer.asString().contains("Started batch job 'msg-archive-import' with file " + second),
                answer.asString());

        JobEnd firstJob = awaitJob("msg-archive-import", first);
        JobEnd secondJob = awaitJob("msg-archive-import", second);
        List<String> imported = importedFrom(made);
        uids.addAll(imported);
        assertEquals("COMPLETED", firstJob.status(), firstJob.log());
        assertEquals("COMPLETED", secondJob.status(), secondJob.log());
        assertEquals(4, imported.size(), "each archive's two messages, under new UIDs: " + firstJob.log() + secondJob.log());
    }

    /**
     * An upload that is not a message archive, or whose form names no message series, is refused with 400
     * before a job starts; it used to be answered 200 with the refusal as text, which the import dialog showed as its
     * result.
     */
    @Test
    void anUploadThatIsNotAnArchiveOrNamesNoSeriesIsRefusedWith400() throws IOException {
        byte[] archive = exportArchive(tag(twoMessages()));
        long jobs = jobsStarted("msg-archive-import");

        Response notAnArchive = given().auth().oauth2(accessToken("admin")).header("NiordDomain", "niord-nw")
                .multiPart("file", "not-an-archive.zip", new byte[]{1, 2, 3}, "application/zip")
                .multiPart("data", json(map("seriesId", "dma-nw")))
                .when().post("/rest/message-io/import");
        assertEquals(400, notAnArchive.statusCode(), notAnArchive.asString());
        assertTrue(notAnArchive.asString().contains("not-an-archive.zip"), notAnArchive.asString());

        Response noSeries = given().auth().oauth2(accessToken("admin")).header("NiordDomain", "niord-nw")
                .multiPart("file", "messages.zip", archive, "application/zip")
                .multiPart("data", json(map("assignNewUids", true)))
                .when().post("/rest/message-io/import");
        assertEquals(400, noSeries.statusCode(), noSeries.asString());

        Response noData = given().auth().oauth2(accessToken("admin")).header("NiordDomain", "niord-nw")
                .multiPart("file", "messages.zip", archive, "application/zip")
                .when().post("/rest/message-io/import");
        assertEquals(400, noData.statusCode(), noData.asString());

        assertEquals(jobs, jobsStarted("msg-archive-import"), "no job was started");
    }

    // ------------------------------------------------------------------------------------------- //
    // Behind it: the job, from a batch set
    // ------------------------------------------------------------------------------------------- //

    /**
     * (a) The archive imported with new UIDs (the rows do not exist under those UIDs) gives two messages equal to the
     * originals in every field the archive carries, the status kept; (b) the same archive without new UIDs adds
     * nothing ("Ignore messages with existing UIDs"); (d) the imported messages exported again equal the first
     * export but for the UIDs, the legacy id and the row's dates.
     */
    @Test
    void anArchiveImportedWithNewUidsGivesTheSameMessagesAndAgainWithoutAddsNothing() throws IOException {
        List<String> made = twoMessages();
        byte[] archive = exportArchive(tag(made));
        List<Map<String, Object>> first = sortedByTitle(listOf(messagesJson(archive), "data"));
        assertExportHoldsWhatWasCreated(first);

        // (a)
        JobEnd job = importArchive(archive, map("assignNewUids", true, "preserveStatus", true));
        assertEquals("COMPLETED", job.status(), job.log());
        List<String> imported = importedFrom(made);
        assertEquals(2, imported.size(), "two new messages: " + job.log());
        uids.addAll(imported);
        List<Map<String, Object>> second = sortedByTitle(listOf(messagesJson(exportArchive(tag(imported))), "data"));
        List<String> rowFields = List.of("id", "legacyId", "created", "updated", "repoPath", "editRepoPath", "version",
                "uid", "thumbnailPath", "revision");
        assertEquals(without(first, rowFields), without(second, rowFields), "the imported messages, field by field");

        // (b) again, no new UIDs: each message is skipped as existing, and nothing is added
        long messages = count("SELECT count(*) FROM public.\"Message\"");
        job = importArchive(archive, map("assignNewUids", false, "preserveStatus", true));
        assertEquals("COMPLETED", job.status(), job.log());
        assertEquals(messages, count("SELECT count(*) FROM public.\"Message\""), "nothing added");
        assertTrue(job.log().contains("Skipping existing message"), job.log());
    }

    /**
     * The four choices: without "preserve status" a VERIFIED message arrives DRAFT; "assign the default series"
     * moves it to the default series and its main type; and an area the archive names that this Niord does not have
     * is created with "create base data", and left out without, the job log naming it (INT6: the message is still
     * imported).
     */
    @Test
    void theFourChoicesOfTheImportDialogDoWhatTheEditorManualSays() throws IOException {
        List<String> made = twoMessages();
        byte[] archive = exportArchive(tag(made));

        // An area the importing Niord does not have, in the first message, under a root it has
        Map<String, Object> json = messagesJson(archive);
        String unknown = "IT-imp unknown area " + unique();
        Map<String, Object> message = sortedByTitle(listOf(json, "data")).getFirst();
        Map<String, Object> area = listOf(message, "areas").getFirst();
        area.put("id", null);
        area.put("mrn", null);
        area.put("descs", list(map("lang", "en", "name", unknown), map("lang", "da", "name", unknown + " da")));
        byte[] changed = withMessagesJson(archive, json);
        cleanups.add(() -> ints("SELECT a.id FROM public.\"Area\" a JOIN public.\"AreaDesc\" d ON d.entity_id = a.id"
                + " WHERE d.name = ?1", unknown).forEach(id -> delete("admin", "/rest/areas/area/" + id)));

        JobEnd plain = importArchive(changed, map("assignNewUids", true, "preserveStatus", false,
                "assignDefaultSeries", false, "createBaseData", false));
        assertEquals("COMPLETED", plain.status(), plain.log());
        List<String> plainUids = importedFrom(made);
        uids.addAll(plainUids);
        List<Map<String, Object>> plainMessages = editables(plainUids);
        assertEquals(List.of("DRAFT", "DRAFT"), plainMessages.stream().map(m -> m.get("status")).toList(),
                "without preserve status every message is a draft");
        assertEquals(List.of("dma-nw", "dma-nw"), plainMessages.stream().map(m -> ((Map<?, ?>) m.get("messageSeries")).get("seriesId")).toList());
        assertEquals(0L, count("SELECT count(*) FROM public.\"AreaDesc\" WHERE name = ?1", unknown),
                "without create base data the unknown area is not created");
        // INT6: the message is saved without the area, and the job log names the area it left out
        String firstUid = strings("SELECT uid FROM public.\"Message\" WHERE \"legacyId\" = ?1 AND uid IN (?2, ?3)",
                message.get("id"), plainUids.get(0), plainUids.get(1)).getFirst();
        Map<String, Object> first = getMap("admin", "/rest/messages/editable-message/" + firstUid);
        assertEquals(listOf(message, "areas").size() - 1, listOf(first, "areas").size(), "the unknown area is dropped");
        // (the archive carries a message's areas without their parents, so the name stands alone)
        assertTrue(plain.log().contains("Message " + message.get("id") + ": the area '" + unknown
                        + "' (en) is not in this Niord and is left out"),
                "and the job log names it: " + plain.log());

        JobEnd all = importArchive(changed, map("assignNewUids", true, "preserveStatus", true,
                "assignDefaultSeries", true, "seriesId", "dma-nm", "createBaseData", true));
        assertEquals("COMPLETED", all.status(), all.log());
        List<String> allUids = importedFrom(made);
        allUids.removeAll(plainUids);
        uids.addAll(allUids);
        // Read in SQL: in the NM series they belong to the NM domain, which the NW administrator's reads refuse
        List<String> seen = new ArrayList<>();
        for (String uid : allUids) {
            List<Object> r = rows("SELECT m.status, s.\"seriesId\", m.\"mainType\" FROM public.\"Message\" m"
                    + " JOIN public.\"MessageSeries\" s ON s.id = m.\"messageSeries_id\" WHERE m.uid = ?1", uid).getFirst();
            seen.add(r.get(0) + " " + r.get(1) + " " + r.get(2));
        }
        assertEquals(new HashSet<>(List.of("VERIFIED dma-nm NM", "DRAFT dma-nm NM")), new HashSet<>(seen),
                "with preserve status each keeps its own; the default series for all, with its main type");
        assertEquals(1L, count("SELECT count(*) FROM public.\"AreaDesc\" WHERE name = ?1 AND lang = 'en'", unknown),
                "with create base data the unknown area is created");
    }

    /**
     * The first export holds the lists {@link #twoMessages} created: two parts (DETAILS, TIME), the first with one
     * event date and two features, two areas, two charts, one category and one reference with two descriptions.
     * The comparison of the two exports alone is blind to a fault in the save that the original and the imported
     * message both pass through: with MSG2 put back, both exports held one part and compared equal.
     */
    @SuppressWarnings("unchecked")
    private static void assertExportHoldsWhatWasCreated(List<Map<String, Object>> exported) {
        assertEquals(2, exported.size(), "the two messages in the export");
        for (Map<String, Object> m : exported) {
            String title = String.valueOf(descs(m, "title").get("en"));
            List<Map<String, Object>> parts = listOf(m, "parts");
            assertEquals(List.of("DETAILS", "TIME"), parts.stream().map(p -> p.get("type")).toList(), "the parts of " + title);
            assertEquals(1, listOf(parts.getFirst(), "eventDates").size(), "the first part's event dates of " + title);
            assertEquals(2, listOf((Map<String, Object>) parts.getFirst().get("geometry"), "features").size(),
                    "the first part's features of " + title);
            assertEquals(List.of(2, 2, 1, 1), List.of(listOf(m, "areas").size(), listOf(m, "charts").size(),
                    listOf(m, "categories").size(), listOf(m, "references").size()),
                    "the areas, charts, categories and references of " + title);
            assertEquals(2, listOf(listOf(m, "references").getFirst(), "descs").size(), "the reference's descriptions of " + title);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Two messages of the NW series, the second VERIFIED, with reference data, texts and geometry */
    private List<String> twoMessages() {
        List<Integer> areas = ints("SELECT id FROM public.\"Area\" WHERE parent_id IS NOT NULL ORDER BY id LIMIT 3");
        List<String> charts = strings("SELECT \"chartNumber\" FROM public.\"Chart\" ORDER BY id LIMIT 2");
        List<Integer> categories = ints("SELECT id FROM public.\"Category\" WHERE type = 'CATEGORY' ORDER BY id LIMIT 2");
        List<String> made = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String uid = UidUtils.newUid();
            String editRepoPath = as("editor").when().get("/rest/repo/new-temp-dir").then().statusCode(200).extract().path("path");
            Map<String, Object> msg = map("id", uid, "repoPath", UidUtils.uidToHashedFolderPath("messages", uid),
                    "editRepoPath", editRepoPath, "revision", 0, "mainType", "NW", "type", "LOCAL_WARNING", "status", "DRAFT",
                    "autoTitle", false, "messageSeries", map("seriesId", "dma-nw", "mainType", "NW"),
                    "areas", list(map("id", areas.get(2 - i)), map("id", areas.get(i))),
                    "charts", list(map("chartNumber", charts.get(1)), map("chartNumber", charts.get(0))),
                    "categories", list(map("id", categories.get(i))),
                    "horizontalDatum", "WGS84", "originalInformation", i == 0,
                    "publishDateFrom", FUTURE + i * DAY, "publishDateTo", FUTURE + 30 * DAY,
                    "descs", list(map("lang", "en", "title", awkward("IT-imp message " + i), "vicinity", "Vicinity, \"en\" ",
                                    "source", "Source en"),
                            map("lang", "da", "title", "IT-imp meddelelse " + i + " æøå", "vicinity", "Nærhed")),
                    "parts", list(map("type", "DETAILS",
                            "descs", list(map("lang", "en", "subject", "Subject " + i, "details", "<p>Details, \"x\" </p>"),
                                    map("lang", "da", "subject", "Emne " + i, "details", "<p>Detaljer æøå</p>")),
                            "eventDates", list(map("allDay", false, "fromDate", FUTURE, "toDate", FUTURE + 3_600_000L)),
                            "geometry", map("type", "FeatureCollection", "features", list(
                                    map("type", "Feature", "geometry", map("type", "Point", "coordinates", list(11.25 + i, 56.5)),
                                            "properties", map("name:en", "Buoy " + i, "name:da", "Bøje " + i)),
                                    map("type", "Feature", "geometry", map("type", "LineString", "coordinates",
                                            list(list(10.0, 55.0), list(10.5, 55.25), list(11.0, 55.5))),
                                            "properties", map("name:0:en", "West", "name:0:da", "Vest"))))),
                            map("type", "TIME", "descs", list(map("lang", "en", "details", "<p>Until further notice</p>"),
                                    map("lang", "da", "details", "<p>Indtil videre</p>")))),
                    "references", list(map("messageId", "NW-001-26", "type", "REFERENCE",
                            "descs", list(map("lang", "en", "description", "See also"), map("lang", "da", "description", "Se også")))));
            postRaw("editor", "/rest/messages/message", msg).statusCode(200);
            uids.add(uid);
            made.add(uid);
        }
        as("editor").body("VERIFIED").when().put("/rest/messages/message/" + made.get(1) + "/status").then().statusCode(200);
        return made.stream().sorted().toList();
    }

    private String tag(List<String> messageUids) {
        Map<String, Object> tag = post("editor", "/rest/tags/tag", map("type", "DOMAIN", "name", "IT-imp archive " + unique(),
                "locked", false, "expiryDate", Instant.parse("2999-01-01T00:00:00Z").toEpochMilli()));
        String tagId = (String) tag.get("tagId");
        cleanups.add(() -> delete("admin", "/rest/tags/tag/" + tagId));
        as("editor").body(messageUids).when().put("/rest/tags/tag/" + tagId + "/add-messages").then().statusCode(200);
        return tagId;
    }

    private static byte[] exportArchive(String tagId) {
        Response r = given().auth().oauth2(accessToken("admin")).header("NiordDomain", "niord-nw")
                .when().get("/rest/message-io/export.zip?tag=" + tagId);
        assertEquals(200, r.statusCode(), r.asString());
        return r.asByteArray();
    }

    private static Map<String, Object> messagesJson(byte[] archive) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if ("messages.json".equals(e.getName())) {
                    return JsonPath.from(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getMap("");
                }
            }
        }
        throw new AssertionError("no messages.json in the archive");
    }

    /** The archive with its messages.json replaced */
    private static byte[] withMessagesJson(byte[] archive, Map<String, Object> json) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                zip.putNextEntry(new ZipEntry(e.getName()));
                zip.write("messages.json".equals(e.getName()) ? json(json).getBytes(StandardCharsets.UTF_8) : in.readAllBytes());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * Runs msg-archive-import on the archive from a system administrator's batch set, with the properties the page's
     * upload would set (MessageExportRestService.checkBatchJob), and waits for it
     */
    private JobEnd importArchive(byte[] archive, Map<String, Object> choices) throws IOException {
        String fileName = "it-archive-" + unique() + ".zip";
        Map<String, Object> properties = new LinkedHashMap<>(map("seriesId", "dma-nw", "assignNewUids", false,
                "preserveStatus", false, "assignDefaultSeries", false, "createBaseData", false,
                "validMessageSeries", list("dma-nw", "dma-nm")));
        properties.putAll(choices);
        ByteArrayOutputStream set = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(set)) {
            zip.putNextEntry(new ZipEntry("batch-set.json"));
            zip.write(json(List.of(map("jobName", "msg-archive-import", "fileName", fileName, "properties", properties,
                    "delay", 0))).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(fileName));
            zip.write(archive);
            zip.closeEntry();
        }
        Response answer = given().auth().oauth2(accessToken("sysadmin")).header("NiordDomain", "niord-nw")
                .multiPart("file", "batch-set.zip", set.toByteArray(), "application/zip")
                .when().post("/rest/batch/execute-batch-set");
        assertEquals(200, answer.statusCode(), answer.asString());
        assertTrue(answer.asString().contains("Scheduling batch job msg-archive-import"), answer.asString());
        return awaitJob("msg-archive-import", fileName);
    }

    /** The UIDs of the messages imported from the originals, found by their legacy id */
    private List<String> importedFrom(List<String> originals) {
        List<String> found = new ArrayList<>();
        for (String uid : originals) {
            found.addAll(strings("SELECT uid FROM public.\"Message\" WHERE \"legacyId\" = ?1 ORDER BY id", uid));
        }
        return found;
    }

    private static List<Map<String, Object>> editables(List<String> messageUids) {
        return messageUids.stream().map(uid -> getMap("admin", "/rest/messages/editable-message/" + uid)).toList();
    }

    private static List<Map<String, Object>> sortedByTitle(List<Map<String, Object>> messages) {
        List<Map<String, Object>> out = new ArrayList<>(messages);
        out.sort((x, y) -> String.valueOf(descs(x, "title").get("en")).compareTo(String.valueOf(descs(y, "title").get("en"))));
        return out;
    }
}
