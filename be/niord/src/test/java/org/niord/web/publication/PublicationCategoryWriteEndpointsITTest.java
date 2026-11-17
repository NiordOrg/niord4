/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.web.publication;

import io.restassured.response.Response;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.JobExecution;
import jakarta.batch.runtime.JobInstance;
import org.junit.jupiter.api.Test;
import org.niord.core.publication.TestIds;
import org.niord.test.BatchJobs;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The four writes of {@code PublicationCategoryRestService} over HTTP, each read back: create, update, delete and the
 * JSON upload, and the anonymous reads the public site and the series form use.
 *
 * <p>A category is created with a name and a description in both languages, read back anonymously and in SQL,
 * updated (a renamed language, a removed one, a changed priority and publish flag), and the read-back saved unchanged
 * with the row count unchanged. Sorting is the priority, read through {@code /all}. A category a series belongs to is
 * refused deletion with a coded 409 and stays readable. The upload imports an exported list back. Every write is also
 * sent by the roles that may not make it, and must be refused with 401 or 403 and change nothing.
 */
class PublicationCategoryWriteEndpointsITTest extends PublicationHttpFixture {

    private static final String BASE = "/rest/publication-categories";

    // ------------------------------------------------------------------------------------------- //
    // Create, read, update, save unchanged, delete
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aCategoryKeepsEverythingSentThroughCreateUpdateAndAnUnchangedSave() {
        String id = TestIds.category();
        categories.add(id);
        Map<String, Object> body = category(id, 7, true,
                desc("da", "Danske udgivelser", "Til søfarende"),
                desc("en", "Danish publications", "For mariners"));

        Map<String, Object> created = object(as("admin").body(body).post(BASE + "/publication-category"));
        assertSame(body, created, "the create's answer");
        assertSame(body, read(id), "the anonymous read after the create");
        assertEquals(2, descRows(id), "one desc row per language after the create");
        assertEquals(List.of(7, true), columns(id), "priority and publish in the table after the create");

        // Danish renamed and its description cleared, English removed, priority and the flag changed
        Map<String, Object> update = category(id, 3, false, desc("da", "Danske publikationer", null));
        Map<String, Object> updated = object(as("admin").body(update).put(BASE + "/publication-category/" + id));
        assertSame(update, updated, "the update's answer");
        assertSame(update, read(id), "the anonymous read after the update");
        assertEquals(1, descRows(id), "the removed language's row is gone");
        assertEquals(List.of(3, false), columns(id), "priority and publish in the table after the update");

        // English added back beside the Danish row
        Map<String, Object> again = category(id, 3, false, desc("da", "Danske publikationer", null),
                desc("en", "Danish publications", "Back again"));
        assertSame(again, object(as("admin").body(again).put(BASE + "/publication-category/" + id)),
                "the answer of the update that adds a language back");
        assertEquals(2, descRows(id), "the language added back has one row");

        // The read-back, saved unchanged
        Map<String, Object> readBack = read(id);
        assertSame(readBack, object(as("admin").body(readBack).put(BASE + "/publication-category/" + id)),
                "the answer of an unchanged save");
        assertSame(again, read(id), "the read after an unchanged save");
        assertEquals(2, descRows(id), "an unchanged save adds and removes no desc row");

        as("admin").delete(BASE + "/publication-category/" + id).then().statusCode(204);
        refusedWith(anonymous().get(BASE + "/publication-category/" + id), 404, "CATEGORY_NOT_FOUND",
                "the read of a deleted category");
        assertEquals(0, descRows(id), "the deleted category's desc rows are gone");
    }

    @Test
    void theOrderOfAllIsThePriorityAndFollowsAChangeOfIt() {
        String first = TestIds.category();
        String second = TestIds.category();
        categories.add(first);
        categories.add(second);
        // Below every seeded priority (the lowest is 10), so the two come first
        as("admin").body(category(first, -20, true, desc("en", "First", null)))
                .post(BASE + "/publication-category").then().statusCode(200);
        as("admin").body(category(second, -10, true, desc("en", "Second", null)))
                .post(BASE + "/publication-category").then().statusCode(200);
        assertEquals(List.of(first, second), orderOf(first, second), "the lower priority sorts first");

        as("admin").body(category(first, -5, true, desc("en", "First", null)))
                .put(BASE + "/publication-category/" + first).then().statusCode(200);
        assertEquals(List.of(second, first), orderOf(first, second), "a changed priority re-sorts the list");
    }

    @Test
    void aCategoryInUseIsRefusedDeletionAndStaysReadable() {
        String id = newCategory();
        String seriesId = track(TestIds.series());
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("seriesId", seriesId);
        series.put("categoryId", id);
        series.put("domainId", DOMAIN);
        series.put("contentMode", "NONE");
        series.put("cadence", "NONE");
        series.put("numberingScheme", "NONE");
        series.put("nextIssueCreation", "MANUAL");
        series.put("languages", List.of("en"));
        series.put("descs", List.of(Map.of("lang", "en", "name", "IT category in use")));
        as("admin").body(series).post("/rest/publication-series/series").then().statusCode(200);

        refusedWith(as("admin").delete(BASE + "/publication-category/" + id), 409, "CATEGORY_IN_USE",
                "the delete of a category a series belongs to");
        assertEquals(id, read(id).get("categoryId"), "the category is still there");
        assertEquals(1, count("SELECT count(*) FROM public.\"PublicationSeries\" s JOIN public.\"PublicationCategory\" c"
                + " ON c.id = s.category_id WHERE c.\"categoryId\" = :id AND s.\"seriesId\" = :s",
                "id", id, "s", seriesId), "and the series still belongs to it");
    }

    @Test
    void anUnknownCategoryAndABodyNamingAnotherIdAreRefusedWithTheirCodes() {
        String id = newCategory();
        refusedWith(as("admin").body(category("no-such-" + id, 1, true)).put(BASE + "/publication-category/no-such-" + id),
                404, "CATEGORY_NOT_FOUND", "the update of an unknown category");
        refusedWith(as("admin").body(category("other-" + id, 1, true)).put(BASE + "/publication-category/" + id),
                400, "CATEGORY_ID_IMMUTABLE", "an update whose body names another id");
        refusedWith(as("admin").body(category(id, 1, true, desc("en", "Taken", null))).post(BASE + "/publication-category"),
                409, "CATEGORY_ID_TAKEN", "a create of an id that exists");
        Map<String, Object> textPriority = category(id, 1, true);
        textPriority.put("priority", "first");
        refusedWith(as("admin").body(textPriority).put(BASE + "/publication-category/" + id),
                400, "CATEGORY_INVALID", "an update with a priority that is not a number");
        refusedWith(as("admin").delete(BASE + "/publication-category/no-such-" + id), 404, "CATEGORY_NOT_FOUND",
                "the delete of an unknown category");
    }

    // ------------------------------------------------------------------------------------------- //
    // An id the address firewall refuses (PUBCAT2), a name (PUB4), an Order in range (PUBCAT3)
    // ------------------------------------------------------------------------------------------- //

    /**
     * The request firewall refuses, with 400 and before any handler runs, every address whose id segment holds a
     * slash, a back slash, a percent sign or a semicolon, or is {@code .} or {@code ..}. A category stored under such
     * an id stayed in every picker for good and could never be opened, changed or deleted, so the create refuses it.
     */
    @Test
    void aCategoryIdTheAddressFirewallRefusesIsNotCreated() {
        List<String> ids = new ArrayList<>();
        for (String held : List.of("/", ";", "%", "\\")) {
            ids.add(TestIds.category() + held + "x");
        }
        ids.add(".");
        ids.add("..");
        for (String id : ids) {
            categories.add(id);
            refusedWith(as("admin").body(category(id, 5, true, desc("en", "Unreachable", null)))
                    .post(BASE + "/publication-category"), 400, "CATEGORY_INVALID", "a create with the id " + id);
            assertEquals(0, count("SELECT count(*) FROM public.\"PublicationCategory\" WHERE \"categoryId\" = :id",
                    "id", id), "nothing is stored under the id " + id);
        }
    }

    /** A category always has a name in some language: a nameless create is refused, and so is an update to none. */
    @Test
    void aCategoryWithoutANameIsNotCreatedAndAnUpdateMayNotTakeItsNameAway() {
        String id = TestIds.category();
        categories.add(id);
        Map<String, Object> noDescs = category(id, 5, true);
        noDescs.remove("descs");
        for (Map<String, Object> body : List.of(noDescs, category(id, 5, true),
                category(id, 5, true, desc("da", "", "da"), desc("en", "   ", "en")))) {
            refusedWith(as("admin").body(body).post(BASE + "/publication-category"), 400, "CATEGORY_INVALID",
                    "a create with the descs " + body.get("descs"));
        }
        assertEquals(0, count("SELECT count(*) FROM public.\"PublicationCategory\" WHERE \"categoryId\" = :id",
                "id", id), "no nameless category was stored");

        String named = newCategory();
        Map<String, Object> before = read(named);
        refusedWith(as("admin").body(category(named, 5, true)).put(BASE + "/publication-category/" + named),
                400, "CATEGORY_INVALID", "an update that removes every language");
        refusedWith(as("admin").body(category(named, 5, true, desc("da", " ", null), desc("en", "", null)))
                .put(BASE + "/publication-category/" + named), 400, "CATEGORY_INVALID", "an update that blanks every name");
        assertEquals(before, read(named), "the refused updates changed nothing");
        assertEquals(2, descRows(named), "both names are still stored");

        // A name in one language of two is a name: a half-translated category is saved
        Map<String, Object> half = category(named, 5, true, desc("da", "Kun dansk", null), desc("en", "", null));
        object(as("admin").body(half).put(BASE + "/publication-category/" + named));
        assertEquals("Kun dansk", descsByLang(read(named)).get("da").get("name"), "the one name is stored");
    }

    /**
     * Order is stored in an int column. A value past its range used to be stored wrapped round to a negative number,
     * so the category jumped to the top of the public page, and a fraction was cut, each with 200.
     */
    @Test
    void anOrderThatIsNoWholeNumberInRangeIsRefusedAndAWholeOneIsStored() {
        String id = newCategory();
        for (Object order : List.<Object>of(3_000_000_000L, 2_147_483_648L, -2_147_483_649L, 1.7)) {
            Map<String, Object> update = category(id, 0, true, desc("en", "IT category", "en"));
            update.put("priority", order);
            refusedWith(as("admin").body(update).put(BASE + "/publication-category/" + id), 400, "CATEGORY_INVALID",
                    "an update with the Order " + order);

            String fresh = TestIds.category();
            categories.add(fresh);
            Map<String, Object> create = category(fresh, 0, true, desc("en", "Fresh", null));
            create.put("priority", order);
            refusedWith(as("admin").body(create).post(BASE + "/publication-category"), 400, "CATEGORY_INVALID",
                    "a create with the Order " + order);
            assertEquals(0, count("SELECT count(*) FROM public.\"PublicationCategory\" WHERE \"categoryId\" = :id",
                    "id", fresh), "nothing was created with the Order " + order);
        }
        assertEquals(List.of(100, true), columns(id), "the refused updates left the Order as it was");

        Map<Object, Integer> taken = new LinkedHashMap<>();
        taken.put(7.0, 7);
        taken.put(Integer.MAX_VALUE, Integer.MAX_VALUE);
        taken.put(Integer.MIN_VALUE, Integer.MIN_VALUE);
        taken.forEach((order, stored) -> {
            Map<String, Object> update = category(id, 0, true, desc("en", "IT category", "en"));
            update.put("priority", order);
            object(as("admin").body(update).put(BASE + "/publication-category/" + id));
            assertEquals(List.of(stored, true), columns(id), "the Order " + order + " as stored");
        });
    }

    /**
     * Clearing a category's Order on the categories tab sent {@code "priority": null}; the update answered 200 and kept
     * the old number, so the screen said the category was saved while the public page kept the old order (L-173). An
     * explicit null is refused with CATEGORY_INVALID and changes nothing; a body without the key still keeps the stored
     * Order, so a partial API body works as before.
     */
    @Test
    void anUpdateThatClearsTheOrderIsRefusedAndOneWithoutItKeepsTheStoredOrder() {
        String id = newCategory();
        Map<String, Object> before = read(id);
        Map<String, Object> cleared = category(id, 0, false, desc("da", "IT-kategori", "da"),
                desc("en", "Renamed", "en"));
        cleared.put("priority", null);
        refusedWith(as("admin").body(cleared).put(BASE + "/publication-category/" + id), 400, "CATEGORY_INVALID",
                "an update that clears the Order");
        assertEquals(before, read(id), "the refused update changed nothing");
        assertEquals(List.of(100, true), columns(id), "the refused update left the Order and the flag as they were");

        Map<String, Object> noOrder = category(id, 0, false, desc("da", "IT-kategori", "da"),
                desc("en", "Renamed", "en"));
        noOrder.remove("priority");
        object(as("admin").body(noOrder).put(BASE + "/publication-category/" + id));
        assertEquals(List.of(100, false), columns(id), "a body without an Order keeps the stored one");
        assertEquals("Renamed", descsByLang(read(id)).get("en").get("name"), "the rest of the body is stored");
    }

    /**
     * The category import skips a row that would create a category under an id the firewall refuses, as the series
     * import skips the rows it refuses, and imports the rows beside it.
     */
    @Test
    void anImportedCategoryWithAnIdTheAddressFirewallRefusesIsSkippedAndTheRestImported() throws Exception {
        String bad = TestIds.category() + "/x";
        String good = TestIds.category();
        categories.add(bad);
        categories.add(good);
        Map<String, Object> reachable = category(good, 45, true, desc("en", "Reachable", null));
        uploadCategories(List.of(category(bad, 44, true, desc("en", "Unreachable", null)), reachable));
        assertEquals(BatchStatus.COMPLETED, lastImport("publication-category-import"), "the import completes");
        assertEquals(0, count("SELECT count(*) FROM public.\"PublicationCategory\" WHERE \"categoryId\" = :id",
                "id", bad), "the row with the unreachable id is not imported");
        assertSame(reachable, read(good), "the row beside it is imported");
    }

    /**
     * A category stored under such an id before the create refused it can be reached by no address, so the import is
     * the one way left to unpublish or rename it: a row for an id that exists is an update, not a new id, and goes
     * through.
     */
    @Test
    void anImportedRowForACategoryAlreadyStoredUnderAnIdTheFirewallRefusesUpdatesIt() throws Exception {
        String stored = newCategory();
        String bad = stored + "/x";
        categories.add(bad);
        // Stored as the create stored it before PUBCAT2: the endpoint now refuses the id, so it is set in SQL
        execute("UPDATE public.\"PublicationCategory\" SET \"categoryId\" = :bad WHERE \"categoryId\" = :id",
                "bad", bad, "id", stored);
        assertEquals(List.of(100, true), columns(bad), "the category is stored under the unreachable id");

        uploadCategories(List.of(category(bad, 100, false,
                desc("da", "Trukket tilbage", "da"), desc("en", "Withdrawn", "en"))));
        assertEquals(BatchStatus.COMPLETED, lastImport("publication-category-import"), "the import completes");
        assertEquals(List.of(100, false), columns(bad), "the import turned the publish flag off");
        assertEquals(List.of("Trukket tilbage", "Withdrawn"), rows("SELECT d.name FROM public.\"PublicationCategoryDesc\" d"
                        + " JOIN public.\"PublicationCategory\" c ON c.id = d.entity_id"
                        + " WHERE c.\"categoryId\" = :id ORDER BY d.lang", "id", bad).stream().map(r -> r[0]).toList(),
                "the import renamed it in both languages");
        assertEquals(1, count("SELECT count(*) FROM public.\"PublicationCategory\" WHERE \"categoryId\" = :id",
                "id", bad), "it was updated in place, not stored a second time");
    }

    // ------------------------------------------------------------------------------------------- //
    // Export and import: the list read anonymously, uploaded back
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anExportedCategoryImportsBackIntoAnEmptiedSlotWithEverythingItCarried() throws Exception {
        String id = TestIds.category();
        categories.add(id);
        Map<String, Object> body = category(id, 42, true,
                desc("da", "Eksporteret", "Beskrivelse da"), desc("en", "Exported", "Description en"));
        as("admin").body(body).post(BASE + "/publication-category").then().statusCode(200);

        List<Map<String, Object>> export = array(anonymous().get(BASE + "/all"));
        Map<String, Object> exported = export.stream().filter(c -> id.equals(c.get("categoryId"))).findFirst()
                .orElseThrow(() -> new AssertionError("the export lists the category"));
        assertSame(body, exported, "the exported row");

        // The category removed, then the exported row uploaded back
        as("admin").delete(BASE + "/publication-category/" + id).then().statusCode(204);
        String json = "[" + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(exported) + "]";
        String answer = given().auth().oauth2(accessToken("admin")).header("NiordDomain", DOMAIN)
                .multiPart("file", "categories.json", json.getBytes(StandardCharsets.UTF_8), "application/json")
                .post(BASE + "/upload-publication-categories")
                .then().statusCode(200).extract().asString();
        assertTrue(answer.startsWith("Started batch job"), "the upload started the import: " + answer);

        Map<String, Object> imported = await(() -> {
            Response r = anonymous().get(BASE + "/publication-category/" + id);
            return r.statusCode() == 200 ? r.jsonPath().getMap("") : null;
        }, "the imported category");
        assertSame(body, imported, "the category imported from its own export");
        assertEquals(2, descRows(id), "one desc row per language after the import");
    }

    /**
     * The upload updates a category that exists through {@code PublicationCategory.updatePublicationCategory}, which
     * walks the stored category's desc list on the batch thread, outside any transaction. The lookup loads the descs
     * inside its own transaction, so the job completes (BATCH7): its own export imported over it changes nothing and
     * the new category beside it is created, and a second import with a changed name in one language, a changed
     * priority and flag is stored.
     */
    @Test
    void anExportedCategoryImportedOverItselfCompletesAndChangesNothing() throws Exception {
        String id = TestIds.category();
        String fresh = TestIds.category();
        categories.add(id);
        categories.add(fresh);
        Map<String, Object> before = category(id, 42, true, desc("da", "Før", "da"), desc("en", "Before", "en"));
        as("admin").body(before).post(BASE + "/publication-category").then().statusCode(200);
        Map<String, Object> exported = exportedRow(id);

        Map<String, Object> added = category(fresh, 43, true, desc("en", "New beside it", null));
        String answer = uploadCategories(List.of(added, exported));
        assertTrue(answer.startsWith("Started batch job"), "the upload reports a started job: " + answer);
        assertEquals(BatchStatus.COMPLETED, lastImport("publication-category-import"),
                "the import of a category's own export over it completes");
        assertSame(before, read(id), "the existing category is unchanged");
        assertEquals(exported, exportedRow(id), "the export after the import is the export before it");
        assertEquals(2, descRows(id), "no desc row added or lost");
        assertSame(added, read(fresh), "the new category beside it is imported");

        // A changed name in one language, a changed priority and flag, stored by a second import
        Map<String, Object> changed = category(id, 41, false, desc("da", "Efter", "da"), desc("en", "Before", "en"));
        uploadCategories(List.of(changed));
        assertEquals(BatchStatus.COMPLETED, lastImport("publication-category-import"),
                "the import of a changed category completes");
        assertSame(changed, read(id), "the changed name, priority and flag are stored");
        assertEquals(2, descRows(id), "the changed language is updated in place");
    }

    /** The category's row in the anonymous list the export is made from. */
    private Map<String, Object> exportedRow(String id) {
        return array(anonymous().get(BASE + "/all")).stream().filter(c -> id.equals(c.get("categoryId")))
                .findFirst().orElseThrow(() -> new AssertionError("the export lists the category " + id));
    }

    private String uploadCategories(List<Map<String, Object>> rows) throws Exception {
        String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(rows);
        return given().auth().oauth2(accessToken("admin")).header("NiordDomain", DOMAIN)
                .multiPart("file", "categories.json", json.getBytes(StandardCharsets.UTF_8), "application/json")
                .post(BASE + "/upload-publication-categories").then().statusCode(200).extract().asString();
    }

    @Autowired
    JobOperator jobOperator;

    /** The terminal status of the newest execution of the job, waited for. */
    private BatchStatus lastImport(String job) throws InterruptedException {
        JobInstance instance = jobOperator.getJobInstances(job, 0, 1).getFirst();
        long executionId = jobOperator.getJobExecutions(instance).stream()
                .mapToLong(JobExecution::getExecutionId).max().orElseThrow();
        return BatchJobs.awaitCompletion(jobOperator, executionId);
    }

    // ------------------------------------------------------------------------------------------- //
    // The roles that may not write
    // ------------------------------------------------------------------------------------------- //

    @Test
    void everyWriteIsRefusedToTheRolesThatMayNotMakeItAndChangesNothing() {
        String id = newCategory();
        Map<String, Object> before = read(id);
        String other = "no-" + id;

        for (String role : List.of("editor", "user")) {
            refused(as(role).body(category(other, 1, true)).post(BASE + "/publication-category"), 403,
                    role + " creating a category");
            refused(as(role).body(category(id, 1, false)).put(BASE + "/publication-category/" + id), 403,
                    role + " updating a category");
            refused(as(role).delete(BASE + "/publication-category/" + id), 403, role + " deleting a category");
            refused(given().auth().oauth2(accessToken(role)).header("NiordDomain", DOMAIN)
                            .multiPart("file", "c.json", "[]".getBytes(StandardCharsets.UTF_8), "application/json")
                            .post(BASE + "/upload-publication-categories"), 403, role + " uploading categories");
        }
        refused(anonymous().body(category(other, 1, true)).post(BASE + "/publication-category"), 401,
                "an anonymous create");
        refused(anonymous().body(category(id, 1, false)).put(BASE + "/publication-category/" + id), 401,
                "an anonymous update");
        refused(anonymous().delete(BASE + "/publication-category/" + id), 401, "an anonymous delete");
        refused(given().multiPart("file", "c.json", "[]".getBytes(StandardCharsets.UTF_8), "application/json")
                .post(BASE + "/upload-publication-categories"), 401, "an anonymous upload");

        assertEquals(before, read(id), "the category is as it was");
        refusedWith(anonymous().get(BASE + "/publication-category/" + other), 404, "CATEGORY_NOT_FOUND",
                "nothing was created");
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    @SafeVarargs
    private static Map<String, Object> category(String id, int priority, boolean publish, Map<String, Object>... descs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", id);
        body.put("priority", priority);
        body.put("publish", publish);
        body.put("descs", new ArrayList<>(List.of(descs)));
        return body;
    }

    private static Map<String, Object> desc(String lang, String name, String description) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("lang", lang);
        d.put("name", name);
        d.put("description", description);
        return d;
    }

    /** The category as the anonymous read answers it, every language. */
    private Map<String, Object> read(String id) {
        return object(anonymous().get(BASE + "/publication-category/" + id));
    }

    /** The two categories in the order {@code /all} lists them. */
    private List<String> orderOf(String a, String b) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> c : array(anonymous().get(BASE + "/all?lang=en"))) {
            Object categoryId = c.get("categoryId");
            if (a.equals(categoryId) || b.equals(categoryId)) {
                out.add((String) categoryId);
            }
        }
        return out;
    }

    private long descRows(String id) {
        return count("SELECT count(*) FROM public.\"PublicationCategoryDesc\" d JOIN public.\"PublicationCategory\" c"
                + " ON c.id = d.entity_id WHERE c.\"categoryId\" = :id", "id", id);
    }

    private List<Object> columns(String id) {
        Object[] row = rows("SELECT priority, publish FROM public.\"PublicationCategory\" WHERE \"categoryId\" = :id",
                "id", id).getFirst();
        return List.of(((Number) row[0]).intValue(), row[1]);
    }

    /** Asserts the read carries what was sent: the id, the priority, the flag and each language's two texts. */
    private static void assertSame(Map<String, Object> sent, Map<String, Object> read, String what) {
        assertEquals(sent.get("categoryId"), read.get("categoryId"), what + ": categoryId");
        assertEquals(((Number) sent.get("priority")).intValue(), ((Number) read.get("priority")).intValue(),
                what + ": priority");
        assertEquals(sent.get("publish"), read.get("publish"), what + ": publish");
        Map<String, Map<String, Object>> sentDescs = descsByLang(sent);
        Map<String, Map<String, Object>> readDescs = descsByLang(read);
        assertEquals(sentDescs.keySet(), readDescs.keySet(), what + ": the languages");
        sentDescs.forEach((lang, d) -> {
            assertEquals(d.get("name"), readDescs.get(lang).get("name"), what + ": the " + lang + " name");
            assertEquals(d.get("description"), readDescs.get(lang).get("description"),
                    what + ": the " + lang + " description");
        });
    }

    /** Polls until the supplier answers, for the asynchronous import. */
    static <T> T await(Supplier<T> probe, String what) {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            T value = probe.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return fail(what + " did not arrive within a minute");
    }
}
