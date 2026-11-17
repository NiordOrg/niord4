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
import org.niord.core.publication.TestIds;
import org.niord.core.publication.series.StubIssueRenderService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A publication issue through the fifteen writes of {@code PublicationIssueRestService}, over HTTP, with its members,
 * standing decisions, history and stored documents read after every step.
 *
 * <p>The issue belongs to a weekly, query-backed series in two languages whose criteria select the seed's NM
 * notices. It is created with its names corrected, edited (names, period, edition, printed numbering, file names,
 * report, report parameters and a criteria override, then the override withdrawn), curated (a message the criteria
 * do not select added by hand, one they do select excluded, a decision withdrawn and made again), previewed,
 * published, amended, given a new edition, retired, reinstated, and deleted where the rules allow. A second series
 * holds an uploaded document and a third an external link. Every write is also sent by the roles that may not make it
 * and by an administrator of another domain, and a save from a stale copy is refused.
 */
class PublicationIssueRoundTripITTest extends PublicationHttpFixture {

    private static final String SERIES = "/rest/publication-series";
    private static final String ISSUES = "/rest/publication-issues";

    private static final long SEP_1 = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli();
    private static final long SEP_30 = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli();
    private static final long OCT_31 = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli();

    /** The seed's published NM notices of September 2026 (dma-nm), which the criteria select. */
    private static final String NM_001 = "5a3f1c70-0000-4000-8000-000000000015";
    private static final String NM_002 = "5a3f1c70-0000-4000-8000-000000000016";
    private static final String NM_003 = "5a3f1c70-0000-4000-8000-000000000017";
    private static final String NM_004 = "5a3f1c70-0000-4000-8000-000000000018";
    /** The seed's NM notices of October 2026: after the period's close, so live members of an open issue only. */
    private static final String NM_005 = "5a3f1c70-0000-4000-8000-000000000019";
    private static final String NM_006 = "5a3f1c70-0000-4000-8000-000000000020";
    /** A published NW warning of the same month, which the criteria do not select. */
    private static final String NW_001 = "5a3f1c70-0000-4000-8000-000000000001";
    /** A draft, which may not be included. */
    private static final String NW_DRAFT = "5a3f1c70-0000-4000-8000-000000000007";

    private String categoryId;

    @BeforeEach
    void seedTheCategory() {
        categoryId = newCategory();
        StubIssueRenderService.reset();
    }

    // ------------------------------------------------------------------------------------------- //
    // The whole life of an issue
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anIssueKeepsWhatEachStepWroteFromCreateToReinstatement() {
        String seriesId = activeWeekly();

        // Create, with the Danish name corrected
        Map<String, Object> created = object(as("admin").body(Map.of("seriesId", seriesId, "intervalFrom", SEP_1,
                "intervalTo", SEP_30, "names", Map.of("da", "EfS september 2026"))).post(ISSUES + "/issue"));
        String publicId = (String) created.get("publicId");
        assertEquals("OPEN", created.get("status"), "an issue is created OPEN");
        Map<String, Object> issue = editable(publicId);
        assertEquals(SEP_1, millis(issue.get("intervalFrom")), "the period's start");
        assertEquals(SEP_30, millis(issue.get("intervalTo")), "the period's close");
        assertEquals("EfS september 2026", descsByLang(issue).get("da").get("name"), "the corrected name");
        assertEquals(Boolean.TRUE, descsByLang(issue).get("da").get("nameOverridden"), "marked as a decision");
        assertEquals(2, issueDescRows(publicId), "one desc row per language");
        assertEquals(List.of("CREATED", "NAME_CHANGED"), actions(publicId), "the trail after the create");

        // Edit: both names, the edition, the printed numbering, the file names, the report and its parameters,
        // and a criteria override
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("names", Map.of("da", "EfS 36-39/2026", "en", "NtM 36-39/2026"));
        edit.put("edition", "2");
        edit.put("weekLabel", "36-39");
        edit.put("yearLabel", "2026");
        edit.put("fileNames", Map.of("da", "efs-2026-36-39-da.pdf", "en", "ntm-2026-36-39-en.pdf"));
        edit.put("reportId", "public");
        edit.put("reportParams", Map.of("subtitle", "Samlet udgave", "columns", 1));
        edit.put("criteriaOverride", criteria(node("messageSeries", "dma-nm", "dma-nw")));
        edit.put("version", intOf(issue, "version"));
        Map<String, Object> edited = object(as("admin").body(edit).put(ISSUES + "/issue/" + publicId));
        assertIssueEdit(edit, edited, "the edit's answer");
        assertIssueEdit(edit, editable(publicId), "the editable read after the edit");
        assertEquals(2, issueDescRows(publicId), "an edit adds no desc row");

        // The edit saved again from the read-back, unchanged
        Map<String, Object> readBack = editable(publicId);
        Map<String, Object> same = new LinkedHashMap<>(edit);
        same.put("version", intOf(readBack, "version"));
        object(as("admin").body(same).put(ISSUES + "/issue/" + publicId));
        assertIssueEdit(edit, editable(publicId), "the editable read after an unchanged edit");

        // The override withdrawn: the series' criteria again
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("clearCriteriaOverride", true);
        clear.put("version", version(publicId));
        object(as("admin").body(clear).put(ISSUES + "/issue/" + publicId));
        Map<String, Object> cleared = editable(publicId);
        assertNull(cleared.get("criteriaOverride"), "the override is gone");
        assertEquals(Boolean.FALSE, cleared.get("criteriaOverridden"), "and the issue follows its series");
        assertIssueEdit(withoutOverride(edit), cleared, "the rest of the edit survives the withdrawal");

        // Curation: NW-001 added by hand, NM-002 excluded, a draft refused, a decision withdrawn and made again
        // The live list of an open issue is the membership as of now (PublicationIssueRestService#members and
        // #workbench say so), so the October notices are in it until the publish freezes it at the cut-off
        assertEquals(Set.of(NM_001, NM_002, NM_003, NM_004, NM_005, NM_006), memberUids(publicId), "the live members");
        curate(publicId, "include", List.of(NW_001), "Belongs in this edition");
        curate(publicId, "exclude", List.of(NM_002), "Cancelled before print");
        refusedWith(as("admin").body(Map.of("messageUid", NW_DRAFT, "reason", "A draft", "version",
                        version(publicId))).put(ISSUES + "/issue/" + publicId + "/overrides/include"), 409,
                "OVERRIDE_MESSAGE_NOT_PUBLIC", "including a draft");
        assertEquals(Set.of(NM_001, NM_003, NM_004, NM_005, NM_006, NW_001), memberUids(publicId),
                "the curated live members");
        assertEquals(Map.of(NW_001, "INCLUDE", NM_002, "EXCLUDE"), decisions(publicId), "the standing decisions");
        as("admin").delete(ISSUES + "/issue/" + publicId + "/overrides/" + NM_002
                + "?reason=Printed after all&version=" + version(publicId)).then().statusCode(204);
        assertEquals(Map.of(NW_001, "INCLUDE"), decisions(publicId), "the exclusion withdrawn");
        assertEquals(Set.of(NM_001, NM_002, NM_003, NM_004, NM_005, NM_006, NW_001), memberUids(publicId),
                "NM-002 back");
        curate(publicId, "exclude", List.of(NM_002), "Cancelled before print, after all");
        assertEquals(2, overrideRows(publicId), "two override rows stand");

        // A preview, which writes no member and no override
        List<Map<String, Object>> previews = array(as("admin").post(ISSUES + "/issue/" + publicId + "/preview"));
        assertEquals(Set.of("da", "en"), langs(previews), "a preview per language");
        assertEquals("rendered", as("admin").get(ISSUES + "/issue/" + publicId + "/preview/da").then()
                .statusCode(200).extract().asString(), "the Danish preview document");
        assertEquals(0, memberRows(publicId), "a preview freezes no member");

        // Publish, at the end of the period
        Map<String, Object> published = publish(publicId, SEP_30);
        assertEquals(4, intOf(published, "memberCount"), "the frozen member count");
        assertNotNull(published.get("successorId"), "a weekly series opens its next issue");
        // The next issue opens where this release closed: its period starts at the stamped cut-off, so it covers the
        // weeks after this one and not these again (detector check MC, M06: a successor chained off the old start)
        Map<String, Object> successor = editable((String) published.get("successorId"));
        assertEquals("OPEN", successor.get("status"), "the successor is OPEN");
        assertEquals(SEP_30, millis(successor.get("intervalFrom")), "the successor's period starts at the stamped cut-off");
        assertNotNull(successor.get("intervalTo"), "and has a close: " + successor);
        assertTrue(millis(successor.get("intervalTo")) > SEP_30, "after its start: " + successor.get("intervalTo"));
        track(seriesId);
        Map<String, Object> frozen = editable(publicId);
        assertEquals("PUBLISHED", frozen.get("status"), "published");
        assertEquals(SEP_30, millis(frozen.get("cutoffStampedAt")), "the stamped cut-off");
        assertEquals(4, memberRows(publicId), "four frozen member rows");
        assertEquals(Set.of(NM_001, NM_003, NM_004, NW_001), memberUids(publicId), "the frozen members");
        assertIssueEdit(withoutOverride(edit), frozen, "the edit survives the publish");
        assertDocuments(publicId, Map.of("da", "efs-2026-36-39-da.pdf", "en", "ntm-2026-36-39-en.pdf"),
                "after the publish");

        // Amend: the same members, the documents archived and rendered again
        StubIssueRenderService.renders("amended");
        Map<String, Object> amended = object(as("admin").body(Map.of("reason", "Typo on page 2",
                "acknowledgedWarnings", acknowledgeable(publicId, null), "version", version(publicId)))
                .put(ISSUES + "/issue/" + publicId + "/amend"));
        assertEquals(4, intOf(amended, "memberCount"), "an amend keeps the members");
        assertEquals(2, ((List<?>) amended.get("archivePaths")).size(), "one archived document per language");
        assertEquals("amended", document(publicId, "da"), "the amended Danish document is the one served");
        assertEquals(4, memberRows(publicId), "still four member rows");
        Map<String, Object> amendEntry = entry(publicId, "AMENDED");
        assertEquals("Typo on page 2", amendEntry.get("reason"), "the amend's reason");
        assertEquals("rendered", as("admin").get(ISSUES + "/issue/" + publicId + "/archive/" + amendEntry.get("id")
                + "/da").then().statusCode(200).extract().asString(), "the archived generation is the first one");

        // Retire, reinstate
        refusedWith(as("admin").put(ISSUES + "/issue/" + publicId + "/retire?version=" + version(publicId)), 400,
                "REASON_REQUIRED", "a retire without a reason");
        Map<String, Object> retired = object(as("admin").put(ISSUES + "/issue/" + publicId
                + "/retire?reason=Withdrawn for a correction&version=" + version(publicId)));
        assertEquals("RETIRED", retired.get("status"), "retired");
        assertEquals("Withdrawn for a correction", editable(publicId).get("retiredReason"), "the retire's reason");
        Map<String, Object> reinstated = object(as("admin").put(ISSUES + "/issue/" + publicId
                + "/reactivate?reason=Corrected&version=" + version(publicId)));
        assertEquals("PUBLISHED", reinstated.get("status"), "reinstated");
        assertNull(editable(publicId).get("retiredReason"), "the retire's reason is cleared");
        assertEquals(4, memberRows(publicId), "the frozen members survive retire and reinstatement");

        // A published issue is never deleted
        refusedWith(as("admin").delete(ISSUES + "/issue/" + publicId + "?version=" + version(publicId)), 409,
                "ISSUE_PUBLISHED_NOT_DELETABLE", "the delete of a published issue");

        List<String> trail = actions(publicId);
        for (String step : List.of("CREATED", "NAME_CHANGED", "OVERRIDE_INCLUDED", "OVERRIDE_EXCLUDED",
                "OVERRIDE_REMOVED", "PREVIEW_GENERATED", "PUBLISHED", "AMENDED", "RETIRED", "REACTIVATED")) {
            assertTrue(trail.contains(step), "the trail holds " + step + ": " + trail);
        }
    }

    @Test
    void aNewEditionSupersedesThePublishedOneAndAnOpenIssueIsDeletedWithItsRows() {
        String seriesId = activeWeekly();
        String first = createIssue(seriesId, SEP_1, SEP_30);
        Map<String, Object> published = publish(first, SEP_30);
        String successor = (String) published.get("successorId");

        Map<String, Object> edition = object(as("admin").body(Map.of("version", version(first)))
                .post(ISSUES + "/issue/" + first + "/new-edition"));
        String editionId = (String) edition.get("publicId");
        assertEquals(first, edition.get("supersedesPublicId"), "the new edition supersedes the published one");
        assertEquals("OPEN", edition.get("status"), "the new edition is OPEN");
        assertTrue(actions(first).contains("SUPERSEDED_BY"), "the predecessor's trail says so: " + actions(first));
        assertEquals("CREATED_NEW_EDITION", actions(editionId).getFirst(), "the edition's first entry");

        refusedWith(as("admin").body(Map.of()).post(ISSUES + "/issue/" + editionId + "/new-edition"), 409,
                "PREDECESSOR_NOT_PUBLISHED", "a new edition of an open issue");

        // The OPEN successor and the OPEN edition are deleted, each with its rows
        for (String open : List.of(editionId, successor)) {
            curate(open, "exclude", List.of(NM_001), "Not this time");
            as("admin").delete(ISSUES + "/issue/" + open + "?reason=Not needed&version=" + version(open))
                    .then().statusCode(204);
            refusedWith(as("admin").get(ISSUES + "/editable-issue/" + open), 404, "ISSUE_NOT_FOUND",
                    "the read of a deleted issue");
            assertEquals(0, count("SELECT count(*) FROM public.\"PublicationIssueDesc\" d WHERE NOT EXISTS"
                    + " (SELECT 1 FROM public.\"PublicationIssue\" i WHERE i.id = d.entity_id)"), "no orphan desc");
            assertEquals(0, count("SELECT count(*) FROM public.\"IssueOverride\" o WHERE NOT EXISTS"
                    + " (SELECT 1 FROM public.\"PublicationIssue\" i WHERE i.id = o.issue_id)"), "no orphan override");
        }
        List<Map<String, Object>> seriesHistory = array(as("admin").get(SERIES + "/" + seriesId + "/history"));
        assertTrue(seriesHistory.stream().filter(h -> "DELETED".equals(h.get("action"))).count() >= 2,
                "each deletion leaves a series-level entry: " + seriesHistory);
    }

    /**
     * New edition pressed twice on the same published issue answers both times with the one edition the first press
     * opened: one OPEN successor per predecessor, not a second "edition 2" superseding it as well (ISSUE9).
     */
    @Test
    void aSecondNewEditionOfTheSamePublishedIssueAnswersTheOpenOne() {
        String seriesId = activeWeekly();
        String first = createIssue(seriesId, SEP_1, SEP_30);
        publish(first, SEP_30);

        Map<String, Object> edition = object(as("admin").body(Map.of("version", version(first)))
                .post(ISSUES + "/issue/" + first + "/new-edition"));
        String editionId = (String) edition.get("publicId");
        Map<String, Object> again = object(as("admin").body(Map.of("version", version(first)))
                .post(ISSUES + "/issue/" + first + "/new-edition"));

        assertEquals(editionId, again.get("publicId"), "the second press answers with the edition already open");
        assertEquals("OPEN", again.get("status"), "and that edition is still OPEN");
        assertEquals("2", again.get("edition"), "still the second edition");
        assertEquals(1, count("SELECT count(*) FROM public.\"PublicationIssue\" i JOIN public.\"PublicationIssue\" p"
                        + " ON p.id = i.supersedes_id WHERE p.\"publicId\" = :p AND i.status = 'OPEN'", "p", first),
                "one OPEN issue supersedes the published one");
        assertEquals(1, actions(first).stream().filter("SUPERSEDED_BY"::equals).count(),
                "the predecessor's trail holds one SUPERSEDED_BY: " + actions(first));
        assertEquals(1, actions(editionId).stream().filter("CREATED_NEW_EDITION"::equals).count(),
                "the edition's trail holds one CREATED_NEW_EDITION: " + actions(editionId));
    }

    // ------------------------------------------------------------------------------------------- //
    // Documents: an uploaded file and an external link
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anUploadedDocumentIsStoredClearedAndReplacedInPlaceOnceItIsPublished() {
        String seriesId = documentSeries("UPLOADED_FILE");
        String publicId = createIssue(seriesId, null, null);

        Map<String, Object> uploaded = upload(publicId, "da", "notice.pdf", "first", version(publicId));
        assertEquals("notice.pdf", descsByLang(uploaded).get("da").get("fileName"), "the uploaded file name");
        assertEquals("first", document(publicId, "da"), "the uploaded bytes are served");
        upload(publicId, "en", "notice-en.pdf", "first en", version(publicId));
        refusedWith(uploadCall(publicId, "en", "notice.pdf", "clash", version(publicId)), 409,
                "FILE_NAME_NOT_DISTINCT", "two languages sharing one file name");

        Map<String, Object> clearedDa = object(as("admin").delete(ISSUES + "/issue/" + publicId + "/file/da?version="
                + version(publicId)));
        assertNull(descsByLang(clearedDa).get("da").get("fileName"), "the cleared file name");
        upload(publicId, "da", "notice.pdf", "second", version(publicId));
        assertEquals(List.of("CREATED", "FILE_UPLOADED", "FILE_UPLOADED", "FILE_CLEARED", "FILE_UPLOADED"),
                actions(publicId), "the trail of the uploads");

        publish(publicId, null);
        assertEquals("second", document(publicId, "da"), "the published Danish document");
        refusedWith(as("admin").delete(ISSUES + "/issue/" + publicId + "/file/da?version=" + version(publicId)), 409,
                "ISSUE_NOT_OPEN", "clearing a published document");
        refusedWith(uploadCall(publicId, "da", "renamed.pdf", "third", version(publicId)), 400, "FILE_NAME_IMMUTABLE",
                "replacing a published document under another name");
        upload(publicId, "da", "notice.pdf", "third", version(publicId));
        assertEquals("third", document(publicId, "da"), "the replacement is served at the same address");
        Map<String, Object> replaced = entry(publicId, "FILE_REPLACED_MANUALLY");
        assertEquals("second", as("admin").get(ISSUES + "/issue/" + publicId + "/archive/" + replaced.get("id")
                + "/da").then().statusCode(200).extract().asString(), "the replaced generation is archived");
    }

    /**
     * A RETIRED issue's released document, uploaded again under its own name, is replaced in place as a PUBLISHED
     * one is: the released generation is archived first and the trail records a manual replacement (ISSUE1).
     */
    @Test
    void anUploadOnARetiredIssueUnderItsOwnNameArchivesTheReleasedDocumentFirst() {
        String seriesId = activeWeekly();
        String publicId = createIssue(seriesId, SEP_1, SEP_30);
        publish(publicId, SEP_30);
        String released = (String) descsByLang(editable(publicId)).get("da").get("fileName");
        assertEquals("rendered", document(publicId, "da"), "the released Danish document");
        assertEquals(0, archivedEntries(publicId), "a first release archives nothing: nothing was there before it");
        object(as("admin").put(ISSUES + "/issue/" + publicId + "/retire?reason=Withdrawn for now&version="
                + version(publicId)));

        // The same upload as on a PUBLISHED issue (anUploadedDocumentIsStoredClearedAndReplacedInPlaceOnceItIsPublished)
        upload(publicId, "da", released, "overwritten", version(publicId));
        assertEquals("overwritten", document(publicId, "da"), "the correction is served at the released address");
        assertEquals(released, descsByLang(editable(publicId)).get("da").get("fileName"), "the name is unchanged");
        assertEquals("FILE_REPLACED_MANUALLY", actions(publicId).getLast(), "recorded as a manual replacement");
        assertEquals(1, archivedEntries(publicId), "the released generation is archived");
        Map<String, Object> replaced = entry(publicId, "FILE_REPLACED_MANUALLY");
        assertEquals("rendered", as("admin").get(ISSUES + "/issue/" + publicId + "/archive/" + replaced.get("id")
                + "/da").then().statusCode(200).extract().asString(), "the archive holds the released bytes");

        object(as("admin").put(ISSUES + "/issue/" + publicId + "/reactivate?version=" + version(publicId)));
        assertEquals("PUBLISHED", editable(publicId).get("status"), "reinstated");
        assertEquals("overwritten", document(publicId, "da"), "and it serves the recorded correction");
    }

    /**
     * Retiring an issue is the documented way to move its address: an upload under a new name is taken as a new
     * document, and the released file stays where every citation points.
     */
    @Test
    void anUploadOnARetiredIssueUnderANewNameMovesTheAddressAndKeepsTheReleasedFile() {
        String seriesId = activeWeekly();
        String publicId = createIssue(seriesId, SEP_1, SEP_30);
        publish(publicId, SEP_30);
        Object releasedPath = rows("SELECT d.\"filePath\" FROM public.\"PublicationIssueDesc\" d JOIN"
                + " public.\"PublicationIssue\" i ON i.id = d.entity_id WHERE i.\"publicId\" = :p AND d.lang = :l",
                "p", publicId, "l", "da").getFirst()[0];
        assertNotNull(releasedPath, "the released Danish document has a stored address");
        object(as("admin").put(ISSUES + "/issue/" + publicId + "/retire?reason=Address moves&version="
                + version(publicId)));

        upload(publicId, "da", "other.pdf", "moved", version(publicId));
        assertEquals("other.pdf", descsByLang(editable(publicId)).get("da").get("fileName"), "the new name");
        assertEquals("moved", document(publicId, "da"), "the new address serves the new bytes");
        assertEquals("FILE_UPLOADED", actions(publicId).getLast(), "recorded as a new document");
        assertEquals("rendered", given().get("/rest/repo/file/" + releasedPath).then().statusCode(200).extract()
                .asString(), "the released file still answers at its old address");
    }

    /**
     * Once a retired issue's address has moved, no desc names the released file any more, and an upload under its
     * name would write over it with no archive copy. It is refused: the released file keeps its name (ISSUE1).
     */
    @Test
    void anUploadUnderTheReleasedNameAfterTheAddressMovedIsRefused() {
        String seriesId = activeWeekly();
        String publicId = createIssue(seriesId, SEP_1, SEP_30);
        publish(publicId, SEP_30);
        String released = (String) descsByLang(editable(publicId)).get("da").get("fileName");
        Object releasedPath = storedPath(publicId, "da");
        object(as("admin").put(ISSUES + "/issue/" + publicId + "/retire?reason=Address moves&version="
                + version(publicId)));
        upload(publicId, "da", "other.pdf", "moved", version(publicId));

        refusedWith(uploadCall(publicId, "da", released, "clobbered", version(publicId)), 400, "FILE_NAME_TAKEN",
                "an upload under the released name, after the address moved");
        assertEquals("rendered", given().get("/rest/repo/file/" + releasedPath).then().statusCode(200).extract()
                .asString(), "the released file is untouched");
        assertEquals("moved", document(publicId, "da"), "the moved address still serves its bytes");
        assertEquals(List.of("CREATED", "PUBLISHED", "RETIRED", "FILE_UPLOADED"), actions(publicId),
                "the refused upload left no trail entry");
        assertEquals(0, archivedEntries(publicId), "and archived nothing");
    }

    /**
     * The same through another language: once no desc carries the released Danish name, the distinct-name check no
     * longer sees it, and an English upload under it would overwrite the Danish released file. It is refused (ISSUE1).
     */
    @Test
    void anUploadInAnotherLanguageUnderTheReleasedNameAfterTheAddressMovedIsRefused() {
        String seriesId = activeWeekly();
        String publicId = createIssue(seriesId, SEP_1, SEP_30);
        publish(publicId, SEP_30);
        String released = (String) descsByLang(editable(publicId)).get("da").get("fileName");
        Object releasedPath = storedPath(publicId, "da");
        object(as("admin").put(ISSUES + "/issue/" + publicId + "/retire?reason=Address moves&version="
                + version(publicId)));
        upload(publicId, "da", "other.pdf", "moved", version(publicId));

        refusedWith(uploadCall(publicId, "en", released, "clobbered", version(publicId)), 400, "FILE_NAME_TAKEN",
                "an English upload under the Danish released name");
        assertEquals("rendered", given().get("/rest/repo/file/" + releasedPath).then().statusCode(200).extract()
                .asString(), "the Danish released file is untouched");
        assertEquals("rendered", document(publicId, "en"), "the English document is as released");
        assertEquals("FILE_UPLOADED", actions(publicId).getLast(), "the refused upload left no trail entry");
    }

    @Test
    void anExternalLinkIsSetChangedAndClearedOnAPublishedIssue() {
        String seriesId = documentSeries("EXTERNAL_LINK");
        String publicId = createIssue(seriesId, null, null);
        link(publicId, "da", "https://example.org/da.pdf");
        link(publicId, "en", "https://example.org/en.pdf");
        assertEquals("https://example.org/da.pdf", descsByLang(editable(publicId)).get("da").get("link"),
                "the Danish link");

        publish(publicId, null);
        link(publicId, "da", "  https://example.org/da-corrected.pdf  ");
        assertEquals("https://example.org/da-corrected.pdf", descsByLang(editable(publicId)).get("da").get("link"),
                "a corrected link, trimmed, on the published issue");
        link(publicId, "en", "");
        assertNull(descsByLang(editable(publicId)).get("en").get("link"), "a blank link clears it");
        List<String> trail = actions(publicId);
        assertEquals(List.of("LINK_SET", "LINK_SET", "PUBLISHED", "LINK_SET", "LINK_CLEARED"),
                trail.subList(1, trail.size()), "the trail of the links");
    }

    /**
     * A link is an http or https address or a repository path. Anything else used to be stored and published as a
     * dead link, and a double quote in it broke out of the href it is written into (ISSUE8).
     */
    @Test
    void aLinkThatIsNotAnAddressIsRefusedAndChangesNothing() {
        String seriesId = documentSeries("EXTERNAL_LINK");
        String publicId = createIssue(seriesId, null, null);
        link(publicId, "da", "https://example.org/da.pdf");
        int before = version(publicId);
        List<String> trailBefore = actions(publicId);

        for (String typed : List.of("javascript:alert(1)", "not a url at all", "https://x/\"onmouseover=1",
                "ftp://example.org/a.pdf", "//example.org/a.pdf")) {
            refusedWith(as("admin").body(Map.of("link", typed, "version", before))
                    .put(ISSUES + "/issue/" + publicId + "/link/da"), 400, "LINK_INVALID", "the link " + typed);
        }
        assertEquals("https://example.org/da.pdf", descsByLang(editable(publicId)).get("da").get("link"),
                "the stored link is unchanged");
        assertEquals(before, version(publicId), "the revision did not move");
        assertEquals(trailBefore, actions(publicId), "no trail entry was written");

        link(publicId, "da", "/rest/repo/file/publications/it/da.pdf");
        assertEquals("/rest/repo/file/publications/it/da.pdf", descsByLang(editable(publicId)).get("da").get("link"),
                "a repository path is a link");
    }

    // ------------------------------------------------------------------------------------------- //
    // Reasons sent as JSON null
    // ------------------------------------------------------------------------------------------- //

    /**
     * A curation decision and an amend whose body carries {@code "reason": null} are refused exactly as when the reason
     * is left out, and write nothing; the four letters {@code null} never become a reason (ISSUE3).
     */
    @Test
    void aReasonSentAsJsonNullIsRefusedAsAnAbsentOneIs() {
        String seriesId = activeWeekly();
        String publicId = createIssue(seriesId, SEP_1, SEP_30);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageUid", NM_002);
        body.put("reason", null);
        body.put("version", version(publicId));
        refusedWith(as("admin").body(body).put(ISSUES + "/issue/" + publicId + "/overrides/exclude"), 400,
                "OVERRIDE_REASON_REQUIRED", "an exclusion whose reason is null");
        refusedWith(as("admin").body(Map.of("messageUid", NM_003, "version", version(publicId)))
                        .put(ISSUES + "/issue/" + publicId + "/overrides/exclude"), 400, "OVERRIDE_REASON_REQUIRED",
                "the same decision with the reason left out");
        assertEquals(0, overrideRows(publicId), "neither refusal wrote an override");
        assertFalse(actions(publicId).contains("OVERRIDE_EXCLUDED"), "nor a trail entry");

        publish(publicId, SEP_30);
        Map<String, Object> amend = new LinkedHashMap<>();
        amend.put("reason", null);
        amend.put("acknowledgedWarnings", acknowledgeable(publicId, null));
        amend.put("version", version(publicId));
        refusedWith(as("admin").body(amend).put(ISSUES + "/issue/" + publicId + "/amend"), 400, "REASON_REQUIRED",
                "an amend whose reason is null");
        assertFalse(actions(publicId).contains("AMENDED"), "the refused amend wrote no AMENDED entry");

        // Right path: the same amend with a reason
        amend.put("reason", "a corrected title");
        amend.put("version", version(publicId));
        object(as("admin").body(amend).put(ISSUES + "/issue/" + publicId + "/amend"));
        assertEquals("a corrected title", entry(publicId, "AMENDED").get("reason"), "the amend's reason");
    }

    /**
     * An {@code acknowledgedWarnings} that is not a list of codes is a coded 400 and changes nothing; an explicit null
     * acknowledges nothing, as an absent one does (ISSUE4).
     */
    @Test
    void aPublishWithAMalformedAcknowledgementIsRefused400AndChangesNothing() {
        String seriesId = activeWeekly();
        String publicId = createIssue(seriesId, SEP_1, SEP_30);
        List<String> trailBefore = actions(publicId);

        List<Object> malformed = new ArrayList<>();
        malformed.add("x");
        malformed.add(Map.of("code", "x"));
        malformed.add(java.util.Arrays.asList((Object) null));
        malformed.add(List.of(1));
        for (Object bad : malformed) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("acknowledgedWarnings", bad);
            body.put("cutoff", SEP_30);
            body.put("version", version(publicId));
            refusedWith(as("admin").body(body).put(ISSUES + "/issue/" + publicId + "/publish"), 400,
                    "INVALID_FILTER_VALUE", "a publish acknowledging " + bad);
        }
        assertEquals("OPEN", editable(publicId).get("status"), "the issue is still open");
        assertEquals(trailBefore, actions(publicId), "no trail entry was written");
        assertEquals(0, memberRows(publicId), "nothing was frozen");

        // Right path: null acknowledges nothing, and an issue whose rail raises no acknowledgeable warning publishes
        assertEquals(List.of(), acknowledgeable(publicId, SEP_30), "the rail raises no acknowledgeable warning");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("acknowledgedWarnings", null);
        body.put("cutoff", SEP_30);
        body.put("version", version(publicId));
        object(as("admin").body(body).put(ISSUES + "/issue/" + publicId + "/publish"));
        assertEquals("PUBLISHED", editable(publicId).get("status"), "the issue is published");
        assertTrue(actions(publicId).contains("PUBLISHED"), "with its PUBLISHED entry");

        // And an amend with a null acknowledgement and a reason goes through too
        Map<String, Object> amend = new LinkedHashMap<>();
        amend.put("acknowledgedWarnings", null);
        amend.put("reason", "a corrected title");
        amend.put("version", version(publicId));
        object(as("admin").body(amend).put(ISSUES + "/issue/" + publicId + "/amend"));
        assertEquals("a corrected title", entry(publicId, "AMENDED").get("reason"), "the amend's reason");
    }

    // ------------------------------------------------------------------------------------------- //
    // Concurrency: a save from a stale copy
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aSaveFromAStaleCopyIsRefusedWithBothRevisionsAndWritesNothing() {
        String seriesId = activeWeekly();
        String publicId = createIssue(seriesId, SEP_1, SEP_30);
        int loaded = version(publicId);

        object(as("admin").body(Map.of("edition", "2", "version", loaded)).put(ISSUES + "/issue/" + publicId));
        Map<String, Object> afterFirst = editable(publicId);
        assertMoved(loaded, intOf(afterFirst, "version"), "an edition change moves the issue's revision");

        Response stale = as("admin").body(Map.of("names", Map.of("da", "Fra en gammel kopi"), "intervalTo", OCT_31,
                "version", loaded)).put(ISSUES + "/issue/" + publicId);
        refusedWith(stale, 409, "STALE_VERSION", "an edit composed against the revision before the first save");
        assertEquals(intOf(afterFirst, "version"), stale.jsonPath().getInt("storedVersion"), "the stored revision");
        assertEquals(loaded, stale.jsonPath().getInt("submittedVersion"), "the submitted revision");

        refusedWith(as("admin").body(Map.of("messageUid", NW_001, "reason", "stale include", "version", loaded))
                .put(ISSUES + "/issue/" + publicId + "/overrides/include"), 409, "STALE_VERSION", "a stale include");
        refusedWith(as("admin").body(Map.of("version", loaded)).put(ISSUES + "/issue/" + publicId + "/publish"), 409,
                "STALE_VERSION", "a stale publish");
        refusedWith(as("admin").delete(ISSUES + "/issue/" + publicId + "?version=" + loaded), 409, "STALE_VERSION",
                "a stale delete");
        assertEquals(afterFirst, editable(publicId), "none of the stale writes changed the issue");
        assertEquals(0, overrideRows(publicId), "and none wrote an override");
    }

    /**
     * A rename (or a file-name change) is written on the issue's desc rows only, yet it moves the issue's revision,
     * as an upload, a link or a curation does, so a rename composed before it is refused rather than silently
     * replacing it (ISSUE2). The answer carries the new revision, which the next write is composed against. An edit
     * that changes nothing leaves the revision where it was.
     */
    @Test
    void aRenameMovesTheIssuesRevisionSoAStaleRenameIsRefused() {
        String seriesId = activeWeekly();
        String publicId = createIssue(seriesId, SEP_1, SEP_30);
        int loaded = version(publicId);

        Map<String, Object> renamed = object(as("admin")
                .body(Map.of("names", Map.of("da", "Rettet af den første"), "version", loaded))
                .put(ISSUES + "/issue/" + publicId));
        int afterRename = version(publicId);
        assertTrue(afterRename > loaded, "the rename moved the issue's revision: " + loaded + " -> " + afterRename);
        assertEquals(afterRename, intOf(renamed, "version"), "the rename's answer carries the new revision");

        Response stale = as("admin").body(Map.of("names", Map.of("da", "Rettet af den anden"), "version", loaded))
                .put(ISSUES + "/issue/" + publicId);
        refusedWith(stale, 409, "STALE_VERSION", "a rename composed against the revision before the first rename");
        assertEquals(afterRename, stale.jsonPath().getInt("storedVersion"), "the stored revision");
        assertEquals("Rettet af den første", descsByLang(editable(publicId)).get("da").get("name"),
                "the first rename is kept");

        // Right path: a rename to the name the issue already has writes nothing and leaves the revision alone.
        Map<String, Object> unchanged = object(as("admin")
                .body(Map.of("names", Map.of("da", "Rettet af den første"), "version", afterRename))
                .put(ISSUES + "/issue/" + publicId));
        assertEquals(afterRename, intOf(unchanged, "version"), "a rename to the same name answers the same revision");
        assertEquals(afterRename, version(publicId), "a rename to the same name leaves the revision where it was");

        Map<String, Object> filed = object(as("admin")
                .body(Map.of("fileNames", Map.of("en", "first.pdf"), "version", afterRename))
                .put(ISSUES + "/issue/" + publicId));
        int afterFileName = version(publicId);
        assertTrue(afterFileName > afterRename,
                "a file-name change moves the revision again: " + afterRename + " -> " + afterFileName);
        assertEquals(afterFileName, intOf(filed, "version"), "the file-name change's answer carries the new revision");

        refusedWith(as("admin").body(Map.of("fileNames", Map.of("en", "second.pdf"), "version", afterRename))
                .put(ISSUES + "/issue/" + publicId), 409, "STALE_VERSION", "a stale file-name change");
        assertEquals("first.pdf", descsByLang(editable(publicId)).get("en").get("fileName"),
                "the first file name is kept");
    }

    // ------------------------------------------------------------------------------------------- //
    // The instant a preview is taken at (ISSUE6)
    // ------------------------------------------------------------------------------------------- //

    /**
     * The screen and the publish dialog may stand at the planned cut-off while the series' default is now; the preview
     * renders at the instant the caller names, and is named as a release at that cut-off names its file. No body keeps
     * the series' default; an instant before the period or one that is not a number is refused.
     */
    @Test
    void aPreviewRendersAtTheInstantTheCallerNames() {
        String seriesId = activeWeekly();
        String publicId = createIssue(seriesId, SEP_1, SEP_30);
        String preview = ISSUES + "/issue/" + publicId + "/preview";

        // No body: the release-moment series' default, as of now, which holds the October notices
        array(as("admin").post(preview));
        assertEquals(Set.of(NM_001, NM_002, NM_003, NM_004, NM_005, NM_006), renderedUids(),
                "the preview with no instant");

        // As a numeric string, then as a number: the planned cut-off, which the October notices are after
        array(as("admin").body(Map.of("at", String.valueOf(SEP_30))).post(preview));
        assertEquals(Set.of(NM_001, NM_002, NM_003, NM_004), renderedUids(), "the preview at a numeric string");
        array(as("admin").body(Map.of("at", SEP_30)).post(preview));
        assertEquals(Set.of(NM_001, NM_002, NM_003, NM_004), renderedUids(),
                "the preview at the planned cut-off holds a notice published after it");
        String disposition = as("admin").get(preview + "/da").then().statusCode(200).extract().header("Content-Disposition");
        String previewName = disposition.replaceAll(".*filename=\"\\d+-(.*)\".*", "$1");

        refusedWith(as("admin").body(Map.of("at", SEP_1 - 86_400_000L)).post(preview), 400, "INVALID_INSTANT",
                "a preview before the period opens");
        refusedWith(as("admin").body(Map.of("at", "next week")).post(preview), 400, "INVALID_INSTANT",
                "a preview at an instant that is not a number");
        refusedWith(as("admin").body(Map.of("at", List.of(SEP_30))).post(preview), 400, "INVALID_INSTANT",
                "a preview at an instant that is a list");

        // The release at that cut-off writes the file under the name the preview carried
        publish(publicId, SEP_30);
        assertEquals(previewName, descsByLang(editable(publicId)).get("da").get("fileName"),
                "the preview and the release at the same cut-off name the file differently");
    }

    // ------------------------------------------------------------------------------------------- //
    // Where the bounds of a created issue came from
    // ------------------------------------------------------------------------------------------- //

    /**
     * ISSUE7. The New issue dialog sends the draft's bounds back whether or not anybody moved them. The first issue
     * of a new weekly series created that way keeps the draft's NOMINAL start and close; it used to be stored with a
     * STAMPED start and a MANUAL close, and its page said "Entered by hand". A moved close or a moved start is MANUAL.
     */
    @Test
    void anIssueCreatedWithTheDraftsOwnBoundsKeepsTheirNominalSources() {
        String seriesId = activeWeekly();
        Map<String, Object> draft = object(as("admin").get(SERIES + "/series/" + seriesId + "/issue-draft"));
        Long from = millis(draft.get("intervalFrom"));
        Long to = millis(draft.get("intervalTo"));
        assertEquals(SEP_1, from, "the draft opens at the series' declared first start");
        assertEquals("NOMINAL", draft.get("intervalFromSource"));
        assertEquals("NOMINAL", draft.get("intervalToSource"));

        Map<String, Object> unchanged = editable(createIssue(seriesId, from, to));
        assertEquals("NOMINAL", unchanged.get("intervalFromSource"), "the declared first start, unchanged");
        assertEquals("NOMINAL", unchanged.get("intervalToSource"), "the cadence close, unchanged");
        assertEquals(to, millis(unchanged.get("intervalTo")));

        // A close moved by an hour was typed; the start is still the declared one
        String other = activeWeekly();
        Map<String, Object> movedClose = editable(createIssue(other, from, to + 3_600_000L));
        assertEquals("NOMINAL", movedClose.get("intervalFromSource"));
        assertEquals("MANUAL", movedClose.get("intervalToSource"), "a close moved off the cadence close");

        // A start moved by a day was typed: it is neither a close nor the declared start
        String third = activeWeekly();
        long movedFrom = from + 86_400_000L;
        Map<String, Object> movedStart = editable(createIssue(third, movedFrom, null));
        assertEquals("MANUAL", movedStart.get("intervalFromSource"), "a start nothing closed at");
        assertEquals("NOMINAL", movedStart.get("intervalToSource"), "the close the cadence derived from it");
    }

    /** The message uids the stub renderer was last handed. */
    private static Set<String> renderedUids() {
        Set<String> out = new TreeSet<>();
        StubIssueRenderService.lastRequest().orderedMessages().forEach(m -> out.add(m.getId()));
        return out;
    }

    // ------------------------------------------------------------------------------------------- //
    // The roles that may not write
    // ------------------------------------------------------------------------------------------- //

    @Test
    void everyWriteIsRefusedToTheRolesThatMayNotMakeItAndChangesNothing() {
        String seriesId = activeWeekly();
        String publicId = createIssue(seriesId, SEP_1, SEP_30);
        Map<String, Object> before = editable(publicId);
        long issues = count("SELECT count(*) FROM public.\"PublicationIssue\"");
        String issue = ISSUES + "/issue/" + publicId;
        byte[] pdf = "x".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> curation = Map.of("messageUid", NW_001, "reason", "Not allowed");

        for (String role : List.of("editor", "user")) {
            refused(as(role).body(Map.of("seriesId", seriesId)).post(ISSUES + "/issue"), 403, role + " creating");
            refused(as(role).body(Map.of("names", Map.of("en", "x"))).put(issue), 403, role + " editing");
            refused(as(role).post(issue + "/preview"), 403, role + " previewing");
            refused(as(role).body(Map.of()).put(issue + "/publish"), 403, role + " publishing");
            refused(as(role).body(Map.of("reason", "nope")).put(issue + "/amend"), 403, role + " amending");
            refused(as(role).body(Map.of()).post(issue + "/new-edition"), 403, role + " creating an edition");
            refused(as(role).put(issue + "/retire?reason=nope"), 403, role + " retiring");
            refused(as(role).put(issue + "/reactivate"), 403, role + " reinstating");
            refused(as(role).delete(issue), 403, role + " deleting");
            refused(given().auth().oauth2(accessToken(role)).header("NiordDomain", DOMAIN)
                    .multiPart("file", "x.pdf", pdf, "application/pdf").post(issue + "/file/da"), 403,
                    role + " uploading");
            refused(as(role).delete(issue + "/file/da"), 403, role + " clearing a file");
            refused(as(role).body(Map.of("link", "https://example.org")).put(issue + "/link/da"), 403,
                    role + " setting a link");
            refused(as(role).body(curation).put(issue + "/overrides/include"), 403, role + " including");
            refused(as(role).body(curation).put(issue + "/overrides/exclude"), 403, role + " excluding");
            refused(as(role).delete(issue + "/overrides/" + NW_001 + "?reason=nope"), 403, role + " withdrawing");
        }
        refused(anonymous().body(Map.of("seriesId", seriesId)).post(ISSUES + "/issue"), 401, "an anonymous create");
        refused(anonymous().body(Map.of("names", Map.of("en", "x"))).put(issue), 401, "an anonymous edit");
        refused(anonymous().body(Map.of()).put(issue + "/publish"), 401, "an anonymous publish");
        refused(anonymous().put(issue + "/retire?reason=nope"), 401, "an anonymous retire");
        refused(anonymous().delete(issue), 401, "an anonymous delete");
        refused(given().multiPart("file", "x.pdf", pdf, "application/pdf").post(issue + "/file/da"), 401,
                "an anonymous upload");
        refused(anonymous().body(curation).put(issue + "/overrides/include"), 401, "an anonymous include");
        refused(anonymous().get(issue + "/members"), 401, "an anonymous read of the members");

        // An administrator of another domain
        refusedWith(as("admin", OTHER_DOMAIN).body(Map.of("seriesId", seriesId)).post(ISSUES + "/issue"), 403,
                "NOT_IN_DOMAIN", "another desk creating an issue");
        refusedWith(as("admin", OTHER_DOMAIN).body(Map.of("names", Map.of("en", "x"))).put(issue), 403,
                "NOT_IN_DOMAIN", "another desk editing");
        refusedWith(as("admin", OTHER_DOMAIN).post(issue + "/preview"), 403, "NOT_IN_DOMAIN",
                "another desk previewing");
        refusedWith(as("admin", OTHER_DOMAIN).body(Map.of()).put(issue + "/publish"), 403, "NOT_IN_DOMAIN",
                "another desk publishing");
        refusedWith(as("admin", OTHER_DOMAIN).body(curation).put(issue + "/overrides/include"), 403, "NOT_IN_DOMAIN",
                "another desk including");
        refusedWith(as("admin", OTHER_DOMAIN).delete(issue), 403, "NOT_IN_DOMAIN", "another desk deleting");
        refusedWith(as("admin", OTHER_DOMAIN).body(Map.of("link", "https://example.org")).put(issue + "/link/da"), 403,
                "NOT_IN_DOMAIN", "another desk setting a link");

        assertEquals(before, editable(publicId), "the issue is as it was");
        assertEquals(issues, count("SELECT count(*) FROM public.\"PublicationIssue\""), "no issue was created");
        assertEquals(0, overrideRows(publicId), "no override was written");
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixture series, created through the API
    // ------------------------------------------------------------------------------------------- //

    /** A weekly, query-backed series of the NM notices in two languages, activated. */
    private String activeWeekly() {
        String seriesId = track(TestIds.series());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seriesId", seriesId);
        s.put("categoryId", categoryId);
        s.put("domainId", DOMAIN);
        s.put("contentMode", "GENERATED_FROM_QUERY");
        s.put("cadence", "WEEKLY");
        s.put("cutoffDefault", "RELEASE_MOMENT");
        s.put("nominalCutoffDay", "WEDNESDAY");
        s.put("nominalCutoffTime", "12:00");
        s.put("numberingScheme", "ISO_WEEK_YEAR");
        s.put("timeRelation", "PUBLISHED_IN_INTERVAL");
        s.put("aliveAtCutoff", false);
        s.put("firstIssueStartsAt", SEP_1);
        s.put("criteria", criteria(node("messageSeries", "dma-nm")));
        s.put("availability", "OWNER_ONLY");
        s.put("languages", List.of("da", "en"));
        s.put("reportId", "standard");
        s.put("pageSize", "A4");
        s.put("pageOrientation", "PORTRAIT");
        s.put("mapThumbnails", false);
        s.put("messagePublication", "NONE");
        s.put("releaseMode", "MANUAL_GATE");
        s.put("nextIssueCreation", "AUTO_ON_PUBLISH");
        s.put("descs", List.of(
                Map.of("lang", "da", "name", "IT EfS", "nameSuggestionPattern", "EfS uge ${week} ${year}",
                        "fileNamePattern", "efs-${year}-${week-2-digits}-da"),
                Map.of("lang", "en", "name", "IT NtM", "nameSuggestionPattern", "NtM week ${week} ${year}",
                        "fileNamePattern", "ntm-${year}-${week-2-digits}-en")));
        object(as("admin").body(s).post(SERIES + "/series"));
        activate(seriesId);
        return seriesId;
    }

    /** An unscheduled series whose document is an uploaded file or an external link, in two languages, activated. */
    private String documentSeries(String contentMode) {
        String seriesId = track(TestIds.series());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seriesId", seriesId);
        s.put("categoryId", categoryId);
        s.put("domainId", DOMAIN);
        s.put("kind", "UNSCHEDULED");
        s.put("contentMode", contentMode);
        s.put("cadence", "NONE");
        s.put("cutoffDefault", "RELEASE_MOMENT");
        s.put("numberingScheme", "NONE");
        s.put("nextIssueCreation", "MANUAL");
        s.put("availability", "ALL_DOMAINS");
        s.put("languages", List.of("da", "en"));
        s.put("messagePublication", "NONE");
        s.put("descs", List.of(Map.of("lang", "da", "name", "IT dokument"), Map.of("lang", "en", "name", "IT document")));
        object(as("admin").body(s).post(SERIES + "/series"));
        activate(seriesId);
        return seriesId;
    }

    private void activate(String seriesId) {
        int v = intOf(object(as("admin").get(SERIES + "/editable-series/" + seriesId)), "version");
        object(as("admin").body("\"ACTIVE\"").put(SERIES + "/series/" + seriesId + "/status?version=" + v));
    }

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

    // ------------------------------------------------------------------------------------------- //
    // REST
    // ------------------------------------------------------------------------------------------- //

    private String createIssue(String seriesId, Long from, Long to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seriesId", seriesId);
        body.put("intervalFrom", from);
        body.put("intervalTo", to);
        return (String) object(as("admin").body(body).post(ISSUES + "/issue")).get("publicId");
    }

    private Map<String, Object> editable(String publicId) {
        return object(as("admin").get(ISSUES + "/editable-issue/" + publicId));
    }

    private int version(String publicId) {
        return intOf(editable(publicId), "version");
    }

    /** Publishes, acknowledging every warning the rail raises for the cut-off; asserts the rail allowed it. */
    private Map<String, Object> publish(String publicId, Long cutoff) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("acknowledgedWarnings", acknowledgeable(publicId, cutoff));
        if (cutoff != null) {
            body.put("cutoff", cutoff);
        }
        body.put("version", version(publicId));
        return object(as("admin").body(body).put(ISSUES + "/issue/" + publicId + "/publish"));
    }

    /** The acknowledge codes of the rail's failing, acknowledgeable rows, for the cut-off. */
    private List<String> acknowledgeable(String publicId, Long cutoff) {
        Map<String, Object> rail = object(as("admin").get(ISSUES + "/issue/" + publicId + "/publish-checklist"
                + (cutoff == null ? "" : "?cutoff=" + cutoff)));
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> row : listOf(rail, "rows")) {
            if (Boolean.TRUE.equals(row.get("acknowledgeable")) && !Boolean.TRUE.equals(row.get("passed"))
                    && row.get("acknowledgeCode") != null) {
                codes.add((String) row.get("acknowledgeCode"));
            }
        }
        return codes;
    }

    private void curate(String publicId, String kind, List<String> uids, String reason) {
        as("admin").body(Map.of("messageUids", uids, "reason", reason, "version", version(publicId)))
                .put(ISSUES + "/issue/" + publicId + "/overrides/" + kind).then().statusCode(204);
    }

    private Set<String> memberUids(String publicId) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> m : array(as("admin").get(ISSUES + "/issue/" + publicId + "/members?lang=en"))) {
            out.add((String) m.get("messageUid"));
        }
        return out;
    }

    private Map<String, String> decisions(String publicId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> o : array(as("admin").get(ISSUES + "/issue/" + publicId + "/overrides?lang=en"))) {
            out.put((String) o.get("messageUid"), (String) o.get("kind"));
        }
        return out;
    }

    private List<String> actions(String publicId) {
        return PublicationSeriesRoundTripITTest.actionsInOrder(
                array(as("admin").get(ISSUES + "/issue/" + publicId + "/audit")));
    }

    /** The newest trail entry of the action. */
    private Map<String, Object> entry(String publicId, String action) {
        return array(as("admin").get(ISSUES + "/issue/" + publicId + "/audit")).stream()
                .filter(e -> action.equals(e.get("action")))
                .max(java.util.Comparator.comparing(e -> ((Number) e.get("id")).longValue()))
                .orElseThrow(() -> new AssertionError("no " + action + " entry"));
    }

    private Response uploadCall(String publicId, String lang, String fileName, String content, int version) {
        return given().auth().oauth2(accessToken("admin")).header("NiordDomain", DOMAIN)
                .multiPart("file", fileName, content.getBytes(StandardCharsets.UTF_8), "application/pdf")
                .post(ISSUES + "/issue/" + publicId + "/file/" + lang + "?version=" + version);
    }

    private Map<String, Object> upload(String publicId, String lang, String fileName, String content, int version) {
        return object(uploadCall(publicId, lang, fileName, content, version));
    }

    private void link(String publicId, String lang, String link) {
        object(as("admin").body(Map.of("link", link, "version", version(publicId)))
                .put(ISSUES + "/issue/" + publicId + "/link/" + lang));
    }

    /** The bytes of a language's stored document, read from the repository at the address the issue names. */
    private String document(String publicId, String lang) {
        Object path = storedPath(publicId, lang);
        return given().get("/rest/repo/file/" + path).then().statusCode(200).extract().asString();
    }

    /** The repository path a language's desc names now. */
    private Object storedPath(String publicId, String lang) {
        Object path = rows("SELECT d.\"filePath\" FROM public.\"PublicationIssueDesc\" d JOIN public.\"PublicationIssue\" i"
                + " ON i.id = d.entity_id WHERE i.\"publicId\" = :p AND d.lang = :l", "p", publicId, "l", lang)
                .getFirst()[0];
        assertNotNull(path, "the " + lang + " desc names a stored file");
        return path;
    }

    private void assertDocuments(String publicId, Map<String, String> fileNames, String when) {
        Map<String, Map<String, Object>> descs = descsByLang(editable(publicId));
        fileNames.forEach((lang, name) -> {
            assertEquals(name, descs.get(lang).get("fileName"), "the " + lang + " file name " + when);
            assertEquals("rendered", document(publicId, lang), "the " + lang + " document " + when);
        });
    }

    private static Set<String> langs(List<Map<String, Object>> rows) {
        Set<String> out = new TreeSet<>();
        rows.forEach(r -> out.add((String) r.get("lang")));
        return out;
    }

    private long issueDescRows(String publicId) {
        return count("SELECT count(*) FROM public.\"PublicationIssueDesc\" d JOIN public.\"PublicationIssue\" i"
                + " ON i.id = d.entity_id WHERE i.\"publicId\" = :p", "p", publicId);
    }

    private long memberRows(String publicId) {
        return count("SELECT count(*) FROM public.\"IssueMember\" m JOIN public.\"PublicationIssue\" i"
                + " ON i.id = m.issue_id WHERE i.\"publicId\" = :p", "p", publicId);
    }

    private long archivedEntries(String publicId) {
        return count("SELECT count(*) FROM public.\"IssueAuditEntry\" a JOIN public.\"PublicationIssue\" i"
                + " ON i.id = a.issue_id WHERE i.\"publicId\" = :p AND a.\"archivePath\" IS NOT NULL", "p", publicId);
    }

    private long overrideRows(String publicId) {
        return count("SELECT count(*) FROM public.\"IssueOverride\" o JOIN public.\"PublicationIssue\" i"
                + " ON i.id = o.issue_id WHERE i.\"publicId\" = :p", "p", publicId);
    }

    private static Map<String, Object> withoutOverride(Map<String, Object> edit) {
        Map<String, Object> out = new LinkedHashMap<>(edit);
        out.remove("criteriaOverride");
        return out;
    }

    /** Asserts the issue carries what the edit sent. */
    @SuppressWarnings("unchecked")
    private static void assertIssueEdit(Map<String, Object> edit, Map<String, Object> issue, String what) {
        Map<String, Map<String, Object>> descs = descsByLang(issue);
        ((Map<String, String>) edit.get("names")).forEach((lang, name) ->
                assertEquals(name, descs.get(lang).get("name"), what + ": the " + lang + " name"));
        ((Map<String, String>) edit.get("fileNames")).forEach((lang, name) ->
                assertEquals(name, descs.get(lang).get("fileName"), what + ": the " + lang + " file name"));
        assertEquals(edit.get("edition"), issue.get("edition"), what + ": the edition");
        assertEquals(edit.get("weekLabel"), issue.get("weekLabel"), what + ": the week label");
        assertEquals(edit.get("yearLabel"), issue.get("yearLabel"), what + ": the year label");
        assertEquals(edit.get("reportId"), issue.get("reportId"), what + ": the report");
        Map<String, Object> params = (Map<String, Object>) issue.get("reportParams");
        ((Map<String, Object>) edit.get("reportParams")).forEach((k, v) ->
                assertEquals(String.valueOf(v), String.valueOf(params.get(k)), what + ": report parameter " + k));
        if (edit.containsKey("criteriaOverride")) {
            assertEquals(edit.get("criteriaOverride"), issue.get("criteriaOverride"), what + ": the criteria override");
        }
        assertFalse(issue.get("status") == null, what + ": a status");
    }
}
