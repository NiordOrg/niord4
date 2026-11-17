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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.category.Category;
import org.niord.core.publication.TestIds;
import org.niord.test.Tx;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A publication series through the writes of {@code PublicationSeriesRestService}, over HTTP, read back each time.
 *
 * <p>The series is created with every optional field set: two languages, three domains it is shared with, a criteria
 * document with a node of every kind, a report with its settings and parameters, and in each language a name pattern
 * holding every token of the vocabulary. It is read back through the editor's read, the admin list, the lean read and
 * in SQL, and compared field by field with what was sent. Then every list is changed (a language order swapped,
 * domains added, removed and reordered, criteria nodes removed and added, report parameters replaced), read back, and
 * the read-back saved unchanged with the child rows counted before and after. Its life follows: activated, retired,
 * reinstated, moved to another domain, deleted. Every write is also sent by the roles that may not make it, and by an
 * administrator of another domain where the owner decides, and a save from a stale copy is refused whole.
 */
class PublicationSeriesRoundTripITTest extends PublicationHttpFixture {

    private static final String BASE = "/rest/publication-series";

    /** Every token of the naming vocabulary, in one pattern. */
    private static final String EVERY_TOKEN = "${week} ${week-2-digits} ${weekTo} ${weekTo-2-digits} ${year} "
            + "${year-2-digits} ${month} ${month-2-digits} ${day} ${day-2-digits} ${edition}";

    private static final long FIRST_START = 1_767_225_600_000L; // 2026-01-01T00:00:00Z

    private String categoryId;

    /** A message category with an MRN, for the criteria document's category node. */
    private String categoryMrn;

    @BeforeEach
    void seedTheOperands() {
        categoryId = newCategory();
        categoryMrn = "urn:mrn:test:it-series-roundtrip:" + TestIds.suffix();
        Tx.requiringNew().run(() -> {
            Category c = new Category();
            c.setMrn(categoryMrn);
            em.persist(c);
            em.flush();
            c.updateLineage();
        });
        cleanupSql.add("DELETE FROM public.\"Category\" WHERE mrn = '" + categoryMrn + "'");
    }

    // ------------------------------------------------------------------------------------------- //
    // Create, read back, update every list, save the read-back unchanged
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aSeriesWithEveryFieldKeepsItThroughCreateUpdateAndAnUnchangedSave() {
        String seriesId = track(TestIds.series());
        Map<String, Object> sent = fullSeries(seriesId);

        Map<String, Object> created = object(as("admin").body(sent).post(BASE + "/series"));
        assertSeries(sent, created, "the create's answer");
        assertEquals("DRAFT", created.get("status"), "a series is created DRAFT");
        assertSeries(sent, editable(seriesId), "the editor's read after the create");
        assertSeries(sent, listed(seriesId, "DRAFT"), "the admin list after the create");
        assertLean(sent, object(as("user").get(BASE + "/series/" + seriesId)), "the lean read after the create");
        assertRows(seriesId, 2, List.of("da", "en"), 3, "after the create");

        // Every list changed: the languages swapped, a domain removed, one added, the rest reordered; the chart
        // node removed, the message-type node given another value and moved first; a report parameter removed
        // and one added; the English name and pattern changed
        Map<String, Object> update = editable(seriesId);
        update.put("languages", new ArrayList<>(List.of("en", "da")));
        update.put("availableDomainIds", new ArrayList<>(List.of("niord-annex", "niord-almanac", "niord-nm")));
        Map<String, Object> criteria = mapOf(update, "criteria");
        List<Map<String, Object>> nodes = listOf(criteria, "criteria");
        nodes.removeIf(n -> "chart".equals(n.get("kind")));
        Map<String, Object> types = nodes.stream().filter(n -> "messageType".equals(n.get("kind"))).findFirst()
                .orElseThrow();
        nodes.remove(types);
        types.put("values", new ArrayList<>(List.of("COASTAL_WARNING", "SUBAREA_WARNING")));
        nodes.addFirst(types);
        Map<String, Object> params = new LinkedHashMap<>(mapOf(update, "reportParams"));
        params.remove("subtitle");
        params.put("pageNumbers", false);
        update.put("reportParams", params);
        Map<String, Object> en = descsByLang(update).get("en");
        en.put("name", "IT series, renamed");
        en.put("nameSuggestionPattern", "NtM ${week}/${year} " + EVERY_TOKEN);

        Map<String, Object> updated = object(as("admin").body(update).put(BASE + "/series/" + seriesId));
        assertSeries(update, updated, "the update's answer");
        assertSeries(update, editable(seriesId), "the editor's read after the update");
        assertRows(seriesId, 2, List.of("en", "da"), 3, "after the update");

        // The read-back, saved as it was read
        Map<String, Object> readBack = editable(seriesId);
        object(as("admin").body(readBack).put(BASE + "/series/" + seriesId));
        Map<String, Object> afterUnchanged = editable(seriesId);
        assertSeries(update, afterUnchanged, "the editor's read after an unchanged save");
        assertRows(seriesId, 2, List.of("en", "da"), 3, "after an unchanged save");

        // A language removed with its desc row, and every sharing domain unticked
        Map<String, Object> narrowed = editable(seriesId);
        narrowed.put("languages", new ArrayList<>(List.of("en")));
        narrowed.put("descs", new ArrayList<>(List.of(descsByLang(narrowed).get("en"))));
        narrowed.put("availability", "OWNER_ONLY");
        narrowed.put("availableDomainIds", new ArrayList<>());
        assertSeries(narrowed, object(as("admin").body(narrowed).put(BASE + "/series/" + seriesId)),
                "the answer of the update that removes a language");
        assertSeries(narrowed, editable(seriesId), "the editor's read after a language was removed");
        assertRows(seriesId, 1, List.of("en"), 0, "after a language was removed");
    }

    @Test
    void aCopyOfASeriesSavesAsANewSeriesWithEverySettingButTheOnesThatBelongToTheOriginal() {
        String source = track(TestIds.series());
        object(as("admin").body(fullSeries(source)).post(BASE + "/series"));

        Map<String, Object> copy = object(as("admin").get(BASE + "/copy-series-template/" + source));
        assertNull(copy.get("seriesId"), "the copy carries no seriesId");
        assertNull(copy.get("domainId"), "the copy carries no owner");
        String copyId = track(TestIds.series());
        copy.put("seriesId", copyId);
        copy.put("domainId", DOMAIN);
        Map<String, Object> saved = object(as("admin").body(copy).post(BASE + "/series"));

        Map<String, Object> expected = fullSeries(copyId);
        expected.remove("firstIssueStartsAt");
        assertSeries(expected, saved, "the saved copy");
        assertNull(saved.get("firstIssueStartsAt"), "the copy does not carry the original's first start");
        assertRows(copyId, 2, List.of("da", "en"), 3, "the copy's rows");
        assertRows(source, 2, List.of("da", "en"), 3, "the original's rows are untouched");
    }

    // ------------------------------------------------------------------------------------------- //
    // The series' life
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aSeriesIsActivatedRetiredAndReinstatedWithEachStepInItsHistory() {
        String seriesId = track(TestIds.series());
        object(as("admin").body(fullSeries(seriesId)).post(BASE + "/series"));
        assertEquals(List.of(), array(as("admin").body(fullSeries(seriesId)).post(BASE + "/validate")),
                "the series passes every rule");
        assertFalse(leanListed(seriesId), "a DRAFT series is not in the editors' list");

        Map<String, Object> active = status(seriesId, "ACTIVE", null, version(seriesId));
        assertEquals("ACTIVE", active.get("status"), "activated");
        assertTrue(leanListed(seriesId), "an ACTIVE series is in the editors' list");

        int beforeRefusal = version(seriesId);
        refusedWith(statusCall(seriesId, "RETIRED", null, beforeRefusal), 400, "REASON_REQUIRED",
                "retiring without a reason");
        assertEquals("ACTIVE", editable(seriesId).get("status"), "a refused retire leaves it ACTIVE");
        assertEquals(beforeRefusal, version(seriesId), "a refused retire does not move the revision");

        Map<String, Object> retired = status(seriesId, "RETIRED", "Replaced by the new weekly", version(seriesId));
        assertEquals("RETIRED", retired.get("status"), "retired");
        assertFalse(leanListed(seriesId), "a RETIRED series is not in the editors' list");

        refusedWith(statusCall(seriesId, "DRAFT", "back to draft", version(seriesId)), 400,
                "INVALID_STATUS_TRANSITION", "a retired series sent back to DRAFT");
        refusedWith(statusCall(seriesId, "PAUSED", "no such state", version(seriesId)), 400, "INVALID_STATUS",
                "an unknown status");

        Map<String, Object> reinstated = status(seriesId, "ACTIVE", null, version(seriesId));
        assertEquals("ACTIVE", reinstated.get("status"), "reinstated");

        List<Map<String, Object>> history = array(as("admin").get(BASE + "/" + seriesId + "/history"));
        assertEquals(List.of("SERIES_ACTIVATED", "SERIES_RETIRED", "SERIES_ACTIVATED"), actionsInOrder(history),
                "one history entry per transition: " + history);
        assertTrue(history.stream().anyMatch(h -> "Replaced by the new weekly".equals(h.get("reason"))),
                "the retire's reason is in the history: " + history);
        assertSeries(fullSeries(seriesId), editable(seriesId), "the settings after the whole life");
    }

    /**
     * A series' id is the last segment of every address that opens, activates or deletes it, and the request firewall
     * refuses a segment holding a slash, a back slash, a percent sign or a semicolon, or one of {@code .} or
     * {@code ..}, before any handler runs. A DRAFT series created under such an id could never be opened, activated
     * or deleted (PUBCAT2), so the create refuses it.
     */
    @Test
    void aSeriesIdTheAddressFirewallRefusesIsNotCreated() {
        List<String> ids = new ArrayList<>();
        for (String held : List.of("/", ";", "%", "\\")) {
            ids.add(TestIds.series() + held + "x");
        }
        ids.add(".");
        ids.add("..");
        for (String seriesId : ids) {
            track(seriesId);
            refusedWith(as("admin").body(fullSeries(seriesId)).post(BASE + "/series"), 400, "SERIES_INVALID",
                    "a create with the id " + seriesId);
            assertEquals(0, count("SELECT count(*) FROM public.\"PublicationSeries\" WHERE \"seriesId\" = :s",
                    "s", seriesId), "nothing is stored under the id " + seriesId);
        }
        // An id with a dot inside it is served, and is taken
        String dotted = track(TestIds.series() + ".v2");
        object(as("admin").body(fullSeries(dotted)).post(BASE + "/series"));
        assertEquals(dotted, editable(dotted).get("seriesId"), "the dotted id is opened by its address");
    }

    @Test
    void anActiveSeriesEditedIntoIncompletenessIsRefusedAndKeepsWhatItHad() {
        String seriesId = track(TestIds.series());
        object(as("admin").body(fullSeries(seriesId)).post(BASE + "/series"));
        status(seriesId, "ACTIVE", null, version(seriesId));
        Map<String, Object> before = editable(seriesId);

        Map<String, Object> incomplete = editable(seriesId);
        incomplete.put("reportId", null);
        incomplete.put("pageSize", null);
        incomplete.put("pageOrientation", null);
        incomplete.put("mapThumbnails", null);
        refusedWith(as("admin").body(incomplete).put(BASE + "/series/" + seriesId), 400, "SERIES_INVALID",
                "an ACTIVE series saved without its report");
        assertEquals(sharingSorted(before), sharingSorted(editable(seriesId)), "the refused save changed nothing");
    }

    @Test
    void aSeriesMovedToAnotherDomainBelongsToItAndIsNoLongerTheOldDesksToWrite() {
        String seriesId = track(TestIds.series());
        object(as("admin").body(fullSeries(seriesId)).post(BASE + "/series"));
        int v = version(seriesId);

        // The realm's admin is an administrator of niord-nw only (its base-client role is not a role in niord-nm),
        // so it may not hand the series to niord-nm; the system administrator is an administrator in both
        refusedWith(as("admin").body(Map.of("domainId", OTHER_DOMAIN, "reason", "The NM desk publishes it now",
                        "version", v)).put(BASE + "/series/" + seriesId + "/owner"), 403, "NOT_IN_DOMAIN",
                "a transfer by an administrator of the source domain only");
        refusedWith(as("sysadmin").body(Map.of("domainId", OTHER_DOMAIN, "version", v))
                        .put(BASE + "/series/" + seriesId + "/owner"), 400, "REASON_REQUIRED",
                "a transfer without a reason");
        assertEquals(DOMAIN, editable(seriesId).get("domainId"), "a refused transfer moves nothing");
        assertEquals(v, version(seriesId), "and does not move the revision");

        Map<String, Object> moved = object(as("sysadmin")
                .body(Map.of("domainId", OTHER_DOMAIN, "reason", "The NM desk publishes it now", "version", v))
                .put(BASE + "/series/" + seriesId + "/owner"));
        assertEquals(OTHER_DOMAIN, moved.get("domainId"), "the new owner");
        Map<String, Object> read = editable(seriesId);
        assertEquals(OTHER_DOMAIN, read.get("domainId"), "the new owner, read back");
        assertEquals(Set.of("niord-fa", "niord-almanac"), new HashSet<>((List<?>) read.get("availableDomainIds")),
                "the new owner is taken off the sharing list and the rest stay");
        assertTrue(array(as("admin", OTHER_DOMAIN).get(BASE + "/" + seriesId + "/history")).stream()
                        .anyMatch(h -> "The NM desk publishes it now".equals(h.get("reason"))),
                "the transfer and its reason are in the history");

        refusedWith(as("admin").body(read).put(BASE + "/series/" + seriesId), 403, "NOT_IN_DOMAIN",
                "the old desk saving the moved series");
        Map<String, Object> saved = object(as("admin", OTHER_DOMAIN).body(read).put(BASE + "/series/" + seriesId));
        assertEquals(OTHER_DOMAIN, saved.get("domainId"), "the new desk saves it");
    }

    @Test
    void aDraftSeriesIsDeletedWithItsChildRowsAndOneWithAnIssueIsRefused() {
        String seriesId = track(TestIds.series());
        object(as("admin").body(fullSeries(seriesId)).post(BASE + "/series"));
        status(seriesId, "ACTIVE", null, version(seriesId));
        status(seriesId, "RETIRED", "Deleted by the test", version(seriesId));
        assertRows(seriesId, 2, List.of("da", "en"), 3, "before the delete");

        refusedWith(as("admin").delete(BASE + "/series/" + seriesId + "?version=" + (version(seriesId) - 1)), 409,
                "STALE_VERSION", "a delete composed against an older revision");
        as("admin").delete(BASE + "/series/" + seriesId + "?version=" + version(seriesId)).then().statusCode(204);
        refusedWith(as("admin").get(BASE + "/editable-series/" + seriesId), 404, "SERIES_NOT_FOUND",
                "the read of a deleted series");
        assertEquals(0, count("SELECT count(*) FROM public.\"PublicationSeriesDesc\" d WHERE NOT EXISTS"
                + " (SELECT 1 FROM public.\"PublicationSeries\" s WHERE s.id = d.entity_id)"), "no orphan desc row");
        assertEquals(0, count("SELECT count(*) FROM public.\"PublicationSeries_AvailableDomain\" a WHERE NOT EXISTS"
                + " (SELECT 1 FROM public.\"PublicationSeries\" s WHERE s.id = a.series_id)"), "no orphan sharing row");

        String withIssue = track(TestIds.series());
        object(as("admin").body(fullSeries(withIssue)).post(BASE + "/series"));
        object(as("admin").body(Map.of("seriesId", withIssue, "intervalFrom", FIRST_START))
                .post("/rest/publication-issues/issue"));
        refusedWith(as("admin").delete(BASE + "/series/" + withIssue), 409, "SERIES_HAS_ISSUES",
                "the delete of a series with an issue");
        assertEquals(withIssue, editable(withIssue).get("seriesId"), "the series with an issue is still there");
    }

    // ------------------------------------------------------------------------------------------- //
    // The tools that write nothing
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theRuleCheckAndThePreviewAnswerAndWriteNothing() {
        String seriesId = track(TestIds.series());
        object(as("admin").body(fullSeries(seriesId)).post(BASE + "/series"));
        long seriesRows = count("SELECT count(*) FROM public.\"PublicationSeries\"");
        long issueRows = count("SELECT count(*) FROM public.\"PublicationIssue\"");

        Map<String, Object> broken = fullSeries(TestIds.series());
        broken.put("cadence", "MONTHLY");
        List<Map<String, Object>> errors = array(as("admin").body(broken).post(BASE + "/validate"));
        assertTrue(errors.stream().anyMatch(e -> "S-6".equals(e.get("rule"))), "a monthly series with no day: " + errors);

        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("criteria", fullSeries(seriesId).get("criteria"));
        probe.put("intervalFrom", FIRST_START);
        probe.put("cutoff", FIRST_START + 7L * 24 * 3600 * 1000);
        probe.put("timeRelation", "PUBLISHED_IN_INTERVAL");
        probe.put("aliveAtCutoff", true);
        Map<String, Object> preview = object(as("admin").body(probe).post(BASE + "/resolve-preview?lang=en"));
        assertEquals("PROBE", preview.get("source"), "the preview answered: " + preview);

        assertEquals(seriesRows, count("SELECT count(*) FROM public.\"PublicationSeries\""), "no series written");
        assertEquals(issueRows, count("SELECT count(*) FROM public.\"PublicationIssue\""), "no issue written");

        Map<String, Object> dryRun = object(as("admin").post(BASE + "/import-legacy/validate"));
        assertTrue(dryRun.containsKey("wouldSucceed"), "the legacy dry run answered its report: " + dryRun.keySet());
        assertEquals(seriesRows, count("SELECT count(*) FROM public.\"PublicationSeries\""),
                "the legacy dry run wrote no series");
    }

    // ------------------------------------------------------------------------------------------- //
    // Concurrency: a save from a stale copy
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aSaveFromAStaleCopyIsRefusedWithBothRevisionsAndWritesNothing() {
        String seriesId = track(TestIds.series());
        object(as("admin").body(fullSeries(seriesId)).post(BASE + "/series"));
        Map<String, Object> first = editable(seriesId);
        Map<String, Object> second = editable(seriesId);

        descsByLang(first).get("en").put("name", "Saved first");
        object(as("admin").body(first).put(BASE + "/series/" + seriesId));
        Map<String, Object> afterFirst = editable(seriesId);

        descsByLang(second).get("da").put("name", "Gemt fra en gammel kopi");
        Response stale = as("admin").body(second).put(BASE + "/series/" + seriesId);
        refusedWith(stale, 409, "STALE_VERSION", "a save composed against the revision before the first save");
        assertEquals(intOf(afterFirst, "version"), stale.jsonPath().getInt("storedVersion"), "the stored revision");
        assertEquals(intOf(second, "version"), stale.jsonPath().getInt("submittedVersion"), "the submitted revision");
        assertEquals(afterFirst, editable(seriesId), "the refused save wrote nothing");

        refusedWith(statusCall(seriesId, "ACTIVE", null, intOf(second, "version")), 409, "STALE_VERSION",
                "an activation composed against the older revision");
        refusedWith(as("admin").body(Map.of("domainId", OTHER_DOMAIN, "reason", "stale move",
                        "version", intOf(second, "version"))).put(BASE + "/series/" + seriesId + "/owner"), 409,
                "STALE_VERSION", "a transfer composed against the older revision");
        assertEquals(afterFirst, editable(seriesId), "and neither moved anything");
    }

    // ------------------------------------------------------------------------------------------- //
    // The roles that may not write
    // ------------------------------------------------------------------------------------------- //

    @Test
    void everyWriteIsRefusedToTheRolesThatMayNotMakeItAndChangesNothing() {
        String seriesId = track(TestIds.series());
        object(as("admin").body(fullSeries(seriesId)).post(BASE + "/series"));
        Map<String, Object> before = editable(seriesId);
        String other = TestIds.series();
        byte[] file = "[]".getBytes(StandardCharsets.UTF_8);

        for (String role : List.of("editor", "user")) {
            refused(as(role).body(fullSeries(other)).post(BASE + "/series"), 403, role + " creating a series");
            refused(as(role).body(before).put(BASE + "/series/" + seriesId), 403, role + " saving a series");
            refused(as(role).body("\"ACTIVE\"").put(BASE + "/series/" + seriesId + "/status"), 403,
                    role + " activating a series");
            refused(as(role).body(Map.of("domainId", OTHER_DOMAIN, "reason", "not mine"))
                    .put(BASE + "/series/" + seriesId + "/owner"), 403, role + " moving a series");
            refused(as(role).delete(BASE + "/series/" + seriesId), 403, role + " deleting a series");
            refused(as(role).body(before).post(BASE + "/validate"), 403, role + " checking the rules");
            refused(as(role).body(Map.of("criteria", before.get("criteria"), "cutoff", FIRST_START))
                    .post(BASE + "/resolve-preview"), 403, role + " previewing criteria");
            refused(as(role).post(BASE + "/import-legacy/validate"), 403, role + " dry-running the legacy import");
            refused(as(role).post(BASE + "/import-legacy"), 403, role + " running the legacy import");
            refused(as(role).delete(BASE + "/import-legacy"), 403, role + " undoing the legacy import");
            refused(given().auth().oauth2(accessToken(role)).header("NiordDomain", DOMAIN)
                    .multiPart("file", "s.json", file, "application/json").post(BASE + "/upload-series"), 403,
                    role + " uploading series");
        }
        // The two estate-wide writes are a system administrator's
        refused(as("admin").post(BASE + "/import-legacy"), 403, "an admin running the legacy import");
        refused(as("admin").delete(BASE + "/import-legacy"), 403, "an admin undoing the legacy import");

        refused(anonymous().body(fullSeries(other)).post(BASE + "/series"), 401, "an anonymous create");
        refused(anonymous().body(before).put(BASE + "/series/" + seriesId), 401, "an anonymous save");
        refused(anonymous().body("\"ACTIVE\"").put(BASE + "/series/" + seriesId + "/status"), 401,
                "an anonymous activation");
        refused(anonymous().body(Map.of("domainId", OTHER_DOMAIN, "reason", "anonymous"))
                .put(BASE + "/series/" + seriesId + "/owner"), 401, "an anonymous move");
        refused(anonymous().delete(BASE + "/series/" + seriesId), 401, "an anonymous delete");
        refused(anonymous().body(before).post(BASE + "/validate"), 401, "an anonymous rule check");
        refused(anonymous().post(BASE + "/import-legacy"), 401, "an anonymous legacy import");
        refused(given().multiPart("file", "s.json", file, "application/json").post(BASE + "/upload-series"), 401,
                "an anonymous upload");
        refused(anonymous().get(BASE + "/search"), 401, "an anonymous read of the editors' list");

        // An administrator of another domain, where the owner decides
        refusedWith(as("admin", OTHER_DOMAIN).body(before).put(BASE + "/series/" + seriesId), 403, "NOT_IN_DOMAIN",
                "another desk saving the series");
        refusedWith(as("admin", OTHER_DOMAIN).body("\"ACTIVE\"").put(BASE + "/series/" + seriesId + "/status"), 403,
                "NOT_IN_DOMAIN", "another desk activating the series");
        refusedWith(as("admin", OTHER_DOMAIN).body(Map.of("domainId", OTHER_DOMAIN, "reason", "taking it"))
                .put(BASE + "/series/" + seriesId + "/owner"), 403, "NOT_IN_DOMAIN", "another desk taking the series");
        refusedWith(as("admin", OTHER_DOMAIN).delete(BASE + "/series/" + seriesId), 403, "NOT_IN_DOMAIN",
                "another desk deleting the series");
        Map<String, Object> intoOther = fullSeries(other);
        intoOther.put("domainId", OTHER_DOMAIN);
        refusedWith(as("admin").body(intoOther).post(BASE + "/series"), 403, "NOT_IN_DOMAIN",
                "a create into another desk's domain");

        assertEquals(sharingSorted(before), sharingSorted(editable(seriesId)), "the series is as it was");
        refusedWith(as("admin").get(BASE + "/editable-series/" + other), 404, "SERIES_NOT_FOUND",
                "no series was created");
    }

    // ------------------------------------------------------------------------------------------- //
    // The series body and its comparison
    // ------------------------------------------------------------------------------------------- //

    /** A weekly, query-backed, citable series with every optional field set, valid for activation. */
    private Map<String, Object> fullSeries(String seriesId) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seriesId", seriesId);
        s.put("categoryId", categoryId);
        s.put("domainId", DOMAIN);
        s.put("sortOrder", 5);
        s.put("contentMode", "GENERATED_FROM_QUERY");
        s.put("cadence", "WEEKLY");
        s.put("kind", "SCHEDULED");
        s.put("cutoffDefault", "RELEASE_MOMENT");
        s.put("nominalCutoffDay", "WEDNESDAY");
        s.put("nominalCutoffTime", "12:00");
        s.put("numberingScheme", "ISO_WEEK_YEAR");
        s.put("timeRelation", "PUBLISHED_IN_INTERVAL");
        s.put("aliveAtCutoff", true);
        s.put("firstIssueStartsAt", FIRST_START);
        s.put("criteria", criteria(
                node("messageSeries", "dma-nw", "dma-nw-local"),
                node("messageMainType", "NW"),
                node("messageType", "LOCAL_WARNING", "COASTAL_WARNING"),
                node("domain", DOMAIN),
                node("area", "urn:mrn:iho:country:dk"),
                node("category", categoryMrn),
                node("chart", "D", "198")));
        s.put("availability", "SELECTED_DOMAINS");
        s.put("availableDomainIds", new ArrayList<>(List.of("niord-nm", "niord-fa", "niord-almanac")));
        s.put("languages", new ArrayList<>(List.of("da", "en")));
        s.put("reportId", "standard");
        s.put("pageSize", "A4");
        s.put("pageOrientation", "LANDSCAPE");
        s.put("mapThumbnails", true);
        s.put("messageSortBy", "AREA");
        s.put("messageSortOrder", "DESC");
        s.put("messagePublication", "EXTERNAL");
        s.put("releaseMode", "MANUAL_GATE");
        s.put("nextIssueCreation", "AUTO_ON_PUBLISH");
        s.put("languageSpecific", true);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subtitle", "Efterretninger for søfarende");
        params.put("showCharts", true);
        params.put("columns", 2);
        s.put("reportParams", params);
        s.put("descs", new ArrayList<>(List.of(
                desc("da", "IT-serie", "EfS uge " + EVERY_TOKEN, "efs-${year}-${week-2-digits}-da",
                        "EfS ${week}/${year}", "https://example.org/da/${year}/${week}"),
                desc("en", "IT series", "NtM week " + EVERY_TOKEN, "ntm-${year}-${week-2-digits}-en",
                        "NtM ${week}/${year}", "https://example.org/en/${year}/${week}"))));
        return s;
    }

    private static Map<String, Object> desc(String lang, String name, String pattern, String fileName,
                                            String referenceFormat, String link) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("lang", lang);
        d.put("name", name);
        d.put("nameSuggestionPattern", pattern);
        d.put("fileNamePattern", fileName);
        d.put("messageReferenceFormat", referenceFormat);
        d.put("linkPattern", link);
        return d;
    }

    @SafeVarargs
    private static Map<String, Object> criteria(Map<String, Object>... nodes) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("schemaVersion", 1);
        c.put("match", "ALL");
        c.put("criteria", new ArrayList<>(List.of(nodes)));
        return c;
    }

    private static Map<String, Object> node(String kind, String... values) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("kind", kind);
        n.put("operator", "IN");
        n.put("values", new ArrayList<>(List.of(values)));
        return n;
    }

    /** The scalar fields compared as sent; the lists, the criteria and the descs are compared below. */
    private static final List<String> SCALARS = List.of("seriesId", "categoryId", "domainId", "sortOrder",
            "contentMode", "cadence", "kind", "cutoffDefault", "nominalCutoffDay", "nominalCutoffDayOfMonth",
            "nominalCutoffMonth", "nominalCutoffTime", "numberingScheme", "timeRelation", "aliveAtCutoff",
            "availability", "reportId", "pageSize", "pageOrientation", "mapThumbnails", "messageSortBy",
            "messageSortOrder", "messagePublication", "releaseMode", "nextIssueCreation", "languageSpecific",
            "sourceSeriesId", "legacyTemplateId", "importSource");

    /** Asserts that the read carries every field that was sent. */
    static void assertSeries(Map<String, Object> sent, Map<String, Object> read, String what) {
        for (String key : SCALARS) {
            if (sent.containsKey(key)) {
                assertEquals(normalised(sent.get(key)), normalised(read.get(key)), what + ": " + key);
            }
        }
        assertEquals(millis(sent.get("firstIssueStartsAt")), millis(read.get("firstIssueStartsAt")),
                what + ": firstIssueStartsAt");
        assertEquals(sent.get("languages"), read.get("languages"), what + ": the languages, in order");
        // A many-to-many list with no order column: compared as a set, its order is not kept (see the report)
        assertEquals(new HashSet<>(listOrEmpty(sent.get("availableDomainIds"))),
                new HashSet<>(listOrEmpty(read.get("availableDomainIds"))), what + ": the sharing domains");
        assertEquals(normalised(sent.get("criteria")), normalised(read.get("criteria")),
                what + ": the criteria document, node by node");
        assertEquals(normalised(sent.get("reportParams")), normalised(read.get("reportParams")),
                what + ": the report parameters");
        Map<String, Map<String, Object>> sentDescs = descsByLang(sent);
        Map<String, Map<String, Object>> readDescs = descsByLang(read);
        assertEquals(sentDescs.keySet(), readDescs.keySet(), what + ": the desc languages");
        for (String lang : sentDescs.keySet()) {
            for (String field : List.of("name", "nameSuggestionPattern", "fileNamePattern",
                    "messageReferenceFormat", "linkPattern")) {
                assertEquals(sentDescs.get(lang).get(field), readDescs.get(lang).get(field),
                        what + ": " + lang + " " + field);
            }
        }
    }

    /** The lean shape: the name per language and the category, and none of the authoring patterns. */
    private static void assertLean(Map<String, Object> sent, Map<String, Object> read, String what) {
        assertEquals(sent.get("seriesId"), read.get("seriesId"), what + ": seriesId");
        assertEquals(sent.get("categoryId"), read.get("categoryId"), what + ": categoryId");
        Map<String, Map<String, Object>> readDescs = descsByLang(read);
        descsByLang(sent).forEach((lang, d) -> {
            assertEquals(d.get("name"), readDescs.get(lang).get("name"), what + ": the " + lang + " name");
            assertNull(readDescs.get(lang).get("fileNamePattern"), what + ": no pattern on the lean shape");
        });
    }

    /** Numbers as longs, recursively, so an integer sent and a long read compare equal. */
    @SuppressWarnings("unchecked")
    private static Object normalised(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, normalised(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            return l.stream().map(PublicationSeriesRoundTripITTest::normalised).toList();
        }
        return value;
    }

    private static List<?> listOrEmpty(Object value) {
        return value == null ? List.of() : (List<?>) value;
    }

    /** Asserts the child rows: desc rows, the languages in their stored order, the sharing rows. */
    private void assertRows(String seriesId, int descs, List<String> languages, int shared, String when) {
        assertEquals(descs, count("SELECT count(*) FROM public.\"PublicationSeriesDesc\" d JOIN"
                + " public.\"PublicationSeries\" s ON s.id = d.entity_id WHERE s.\"seriesId\" = :id", "id", seriesId),
                "the desc rows " + when);
        List<String> stored = rows("SELECT l.languages FROM public.\"PublicationSeries_languages\" l JOIN"
                + " public.\"PublicationSeries\" s ON s.id = l.\"PublicationSeries_id\" WHERE s.\"seriesId\" = :id"
                + " ORDER BY l.\"indexNo\"", "id", seriesId).stream().map(r -> (String) r[0]).toList();
        assertEquals(languages, stored, "the language rows, by their order column, " + when);
        List<Object[]> indexes = rows("SELECT l.\"indexNo\" FROM public.\"PublicationSeries_languages\" l JOIN"
                + " public.\"PublicationSeries\" s ON s.id = l.\"PublicationSeries_id\" WHERE s.\"seriesId\" = :id"
                + " ORDER BY l.\"indexNo\"", "id", seriesId);
        for (int i = 0; i < indexes.size(); i++) {
            assertEquals(i, ((Number) indexes.get(i)[0]).intValue(), "the language order column " + when);
        }
        assertEquals(shared, count("SELECT count(*) FROM public.\"PublicationSeries_AvailableDomain\" a JOIN"
                + " public.\"PublicationSeries\" s ON s.id = a.series_id WHERE s.\"seriesId\" = :id", "id", seriesId),
                "the sharing rows " + when);
    }

    // ------------------------------------------------------------------------------------------- //
    // REST
    // ------------------------------------------------------------------------------------------- //

    private Map<String, Object> editable(String seriesId) {
        return object(as("admin").get(BASE + "/editable-series/" + seriesId));
    }

    private int version(String seriesId) {
        return intOf(editable(seriesId), "version");
    }

    private Map<String, Object> listed(String seriesId, String status) {
        return array(as("admin").get(BASE + "/search-details?status=" + status + "&domain=" + DOMAIN)).stream()
                .filter(s -> seriesId.equals(s.get("seriesId"))).findFirst()
                .orElseThrow(() -> new AssertionError("the admin list holds " + seriesId));
    }

    private boolean leanListed(String seriesId) {
        return array(as("user").get(BASE + "/search?domain=" + DOMAIN)).stream()
                .anyMatch(s -> seriesId.equals(s.get("seriesId")));
    }

    private static Response statusCall(String seriesId, String status, String reason, Integer version) {
        StringBuilder query = new StringBuilder("?version=" + version);
        if (reason != null) {
            query.append("&reason=").append(reason);
        }
        return as("admin").body("\"" + status + "\"").put(BASE + "/series/" + seriesId + "/status" + query);
    }

    private static Map<String, Object> status(String seriesId, String status, String reason, Integer version) {
        return object(statusCall(seriesId, status, reason, version));
    }

    /** The actions of history entries in the order they were written (by id, which the sequence hands out). */
    static List<String> actionsInOrder(List<Map<String, Object>> history) {
        return history.stream()
                .sorted(java.util.Comparator.comparing((Map<String, Object> h) -> ((Number) h.get("id")).longValue()))
                .map(h -> (String) h.get("action")).toList();
    }
}
