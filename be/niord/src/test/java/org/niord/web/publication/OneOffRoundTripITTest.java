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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A one-off publication through the three writes of {@code OneOffRestService} over HTTP, read back each time: created
 * live in one call, edited, its read-back saved unchanged, its public period set and closed, withdrawn and put back;
 * and an uploaded one, created waiting for its file and released once the bytes are in. Every write is also sent by
 * the roles that may not make it and by an administrator of another domain, and a save from a stale copy is refused.
 */
class OneOffRoundTripITTest extends PublicationHttpFixture {

    private static final String BASE = "/rest/one-off-publications";
    private static final String ISSUES = "/rest/publication-issues";

    private static final long DAY = 24L * 3600 * 1000;

    private String categoryId;

    @BeforeEach
    void seedTheCategory() {
        categoryId = newCategory();
    }

    // ------------------------------------------------------------------------------------------- //
    // A link-backed one-off: create, edit, save unchanged
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aLinkOneOffIsLiveOnCreateAndKeepsItsTextsAndLinksThroughAnEditAndAnUnchangedSave() {
        String name = "IT one-off " + TestIds.suffix();
        Map<String, Object> created = object(as("admin").body(oneOff(name, "EXTERNAL_LINK", true,
                link("da", "https://example.org/da.pdf"), link("en", "https://example.org/en.pdf")))
                .post(BASE + "?lang=en"));
        String seriesId = track((String) mapOf(created, "series").get("seriesId"));

        assertEquals("ONE_OFF", mapOf(created, "series").get("kind"), "a one-off");
        assertEquals("ACTIVE", mapOf(created, "series").get("status"), "activated by the create");
        assertEquals("PUBLISHED", created.get("issueStatus"), "its issue released by the create");
        assertEquals(Boolean.TRUE, created.get("active"), "live");
        Map<String, Object> read = read(seriesId);
        assertEquals(Map.of("da", "https://example.org/da.pdf", "en", "https://example.org/en.pdf"), links(read),
                "the links, read back");
        assertEquals(name, descsByLang(mapOf(read, "series")).get("en").get("name"), "the English name");
        assertEquals("IT-kategori", read("da", seriesId).get("categoryName"), "the category's Danish name");
        assertEquals(1, issues(seriesId), "one issue");
        assertEquals(2, issueDescRows(seriesId), "one issue desc per language");

        // Edit: the English name and the Danish link changed, the sharing narrowed
        Map<String, Object> edit = read(seriesId);
        descsByLang(mapOf(edit, "series")).get("en").put("name", name + " (2nd ed.)");
        setLink(edit, "da", "https://example.org/da-2.pdf");
        mapOf(edit, "series").put("availability", "SELECTED_DOMAINS");
        mapOf(edit, "series").put("availableDomainIds", new ArrayList<>(List.of("niord-nm")));
        Map<String, Object> edited = object(as("admin").body(edit).put(BASE + "/" + seriesId + "?lang=en"));
        assertEquals(name + " (2nd ed.)", descsByLang(mapOf(edited, "series")).get("en").get("name"), "renamed");
        Map<String, Object> afterEdit = read(seriesId);
        assertEquals(Map.of("da", "https://example.org/da-2.pdf", "en", "https://example.org/en.pdf"), links(afterEdit),
                "the links after the edit");
        assertEquals(List.of("niord-nm"), mapOf(afterEdit, "series").get("availableDomainIds"), "the sharing");
        assertEquals("PUBLISHED", afterEdit.get("issueStatus"), "an edit does not move the publication");

        // The read-back saved unchanged
        Map<String, Object> again = object(as("admin").body(read(seriesId)).put(BASE + "/" + seriesId + "?lang=en"));
        assertEquals(links(afterEdit), links(again), "the links after an unchanged save");
        Map<String, Object> afterUnchanged = read(seriesId);
        assertEquals(descsByLang(mapOf(afterEdit, "series")).get("en").get("name"),
                descsByLang(mapOf(afterUnchanged, "series")).get("en").get("name"), "the name after an unchanged save");
        assertEquals(1, issues(seriesId), "still one issue");
        assertEquals(2, issueDescRows(seriesId), "still one issue desc per language");
        assertEquals(2, count("SELECT count(*) FROM public.\"PublicationSeriesDesc\" d JOIN public.\"PublicationSeries\""
                + " s ON s.id = d.entity_id WHERE s.\"seriesId\" = :s", "s", seriesId), "two series desc rows");
    }

    // ------------------------------------------------------------------------------------------- //
    // The public period, the withdrawal and the reinstatement
    // ------------------------------------------------------------------------------------------- //

    @Test
    void thePublicPeriodIsSetAndClosedAndTheSwitchWithdrawsAndRestoresTheIssue() {
        String seriesId = createLinkOneOff();
        long now = System.currentTimeMillis();

        Map<String, Object> window = window(seriesId, now - DAY, now + 30 * DAY);
        assertEquals(now - DAY, millis(window.get("publicFrom")), "the period's start");
        assertEquals(now + 30 * DAY, millis(window.get("publicTo")), "the period's end");
        assertEquals(Boolean.TRUE, read(seriesId).get("active"), "live inside its period");
        assertTrue(issueActions(seriesId).contains("WINDOW_ADJUSTED")
                        || issueActions(seriesId).contains("VISIBILITY_WINDOW_CHANGED"),
                "the period change is in the issue's trail: " + issueActions(seriesId));

        refusedWith(windowCall(seriesId, now + DAY, now - DAY, version(seriesId)), 400,
                org.niord.core.publication.series.IssuePublicWindowService.INVALID, "a period that ends before it starts");

        // The period closed: off the public site, and the switch cannot put it back
        window(seriesId, now - 2 * DAY, now - DAY);
        Map<String, Object> closed = read(seriesId);
        assertEquals(Boolean.FALSE, closed.get("active"), "off once its period ended");
        Map<String, Object> turnOn = read(seriesId);
        turnOn.put("active", true);
        Response refusal = as("admin").body(turnOn).put(BASE + "/" + seriesId);
        refusedWith(refusal, 409, "PUBLIC_WINDOW_CLOSED", "switching on a publication whose period ended");
        assertEquals(now - DAY, refusal.jsonPath().getLong("publicTo"), "the refusal names the end");
        // A plain save of the closed publication is no transition
        object(as("admin").body(read(seriesId)).put(BASE + "/" + seriesId));
        assertEquals("PUBLISHED", read(seriesId).get("issueStatus"), "a save of a closed publication moves nothing");

        // Open-ended again, then withdrawn by the switch, and put back
        window(seriesId, now - DAY, null);
        Map<String, Object> off = read(seriesId);
        off.put("active", false);
        refusedWith(as("admin").body(off).put(BASE + "/" + seriesId), 400, "REASON_REQUIRED",
                "withdrawing without a reason");
        assertEquals("PUBLISHED", read(seriesId).get("issueStatus"), "a refused withdrawal leaves it released");
        off.put("reason", "Superseded by the printed edition");
        Map<String, Object> withdrawn = object(as("admin").body(off).put(BASE + "/" + seriesId));
        assertEquals("RETIRED", withdrawn.get("issueStatus"), "withdrawn");
        assertEquals(Boolean.FALSE, withdrawn.get("active"), "off");
        Map<String, Object> on = read(seriesId);
        on.put("active", true);
        Map<String, Object> restored = object(as("admin").body(on).put(BASE + "/" + seriesId));
        assertEquals("PUBLISHED", restored.get("issueStatus"), "put back");
        assertEquals(Boolean.TRUE, restored.get("active"), "live again");
        List<String> trail = issueActions(seriesId);
        assertTrue(trail.containsAll(List.of("PUBLISHED", "RETIRED", "REACTIVATED")), "the trail: " + trail);
    }

    // ------------------------------------------------------------------------------------------- //
    // An uploaded one-off
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anUploadedOneOffWaitsForItsFileAndIsReleasedOnceTheBytesAreIn() {
        Map<String, Object> created = object(as("admin").body(oneOff("IT uploaded " + TestIds.suffix(),
                "UPLOADED_FILE", true)).post(BASE));
        String seriesId = track((String) mapOf(created, "series").get("seriesId"));
        assertEquals(Boolean.FALSE, created.get("publishable"), "waiting for its file");
        assertEquals("OPEN", created.get("issueStatus"), "not released");
        String publicId = (String) created.get("issuePublicId");

        for (String lang : List.of("da", "en")) {
            int v = intOf(object(as("admin").get(ISSUES + "/editable-issue/" + publicId)), "version");
            given().auth().oauth2(accessToken("admin")).header("NiordDomain", DOMAIN)
                    .multiPart("file", "fyrliste-" + lang + ".pdf", ("bytes " + lang).getBytes(StandardCharsets.UTF_8),
                            "application/pdf")
                    .post(ISSUES + "/issue/" + publicId + "/file/" + lang + "?version=" + v).then().statusCode(200);
        }
        Map<String, Object> ready = read(seriesId);
        assertEquals(Boolean.TRUE, ready.get("publishable"), "publishable once both files are in");
        assertEquals(List.of("fyrliste-da.pdf", "fyrliste-en.pdf"),
                listOf(ready, "fileNames").stream().map(f -> (String) f.get("value")).sorted().toList(),
                "the file names, read back");

        int v = intOf(object(as("admin").get(ISSUES + "/editable-issue/" + publicId)), "version");
        object(as("admin").body(Map.of("version", v)).put(ISSUES + "/issue/" + publicId + "/publish"));
        Map<String, Object> live = read(seriesId);
        assertEquals("PUBLISHED", live.get("issueStatus"), "released");
        assertEquals(Boolean.TRUE, live.get("active"), "live");

        // The read-back saved unchanged does not freeze the derived addresses into links
        object(as("admin").body(read(seriesId)).put(BASE + "/" + seriesId));
        assertEquals(0, count("SELECT count(*) FROM public.\"PublicationIssueDesc\" d JOIN public.\"PublicationIssue\" i"
                + " ON i.id = d.entity_id WHERE i.\"publicId\" = :p AND d.link IS NOT NULL", "p", publicId),
                "no explicit link stored by the save");
    }

    // ------------------------------------------------------------------------------------------- //
    // A link that is not an address (ISSUE8)
    // ------------------------------------------------------------------------------------------- //

    /**
     * A one-off whose link is not an http or https address or a repository path is refused before anything is
     * written: no series row, no issue. It used to go live with the text as its document.
     */
    @Test
    void aOneOffWithALinkThatIsNotAnAddressIsNotCreated() {
        long series = count("SELECT count(*) FROM public.\"PublicationSeries\"");
        for (String typed : List.of("javascript:alert(1)", "not a url at all")) {
            refusedWith(as("admin").body(oneOff("IT bad link " + TestIds.suffix(), "EXTERNAL_LINK", true,
                    link("da", typed), link("en", "https://example.org/en.pdf"))).post(BASE), 400, "LINK_INVALID",
                    "a create with the link " + typed);
        }
        assertEquals(series, count("SELECT count(*) FROM public.\"PublicationSeries\""), "no series was created");
    }

    /** A save that types a link that is not an address is refused, and the one-off is as it was. */
    @Test
    void aSaveWithALinkThatIsNotAnAddressIsRefusedAndChangesNothing() {
        String seriesId = createLinkOneOff();
        Map<String, Object> before = read(seriesId);
        Map<String, Object> edit = read(seriesId);
        setLink(edit, "da", "javascript:alert(1)");
        descsByLang(mapOf(edit, "series")).get("en").put("name", "Renamed with a bad link");

        refusedWith(as("admin").body(edit).put(BASE + "/" + seriesId), 400, "LINK_INVALID", "a save with a bad link");
        assertEquals(before, read(seriesId), "the one-off is as it was, its name and revision included");
    }

    // ------------------------------------------------------------------------------------------- //
    // A name in some language (PUB4) and an id the address firewall serves (PUBCAT2)
    // ------------------------------------------------------------------------------------------- //

    /**
     * A save that leaves a live one-off with no name in any language is refused with NAME_BLANK, as the create
     * refuses a one-off with no name, and the one-off is as it was. It used to answer 200 and leave it nameless.
     */
    @Test
    void aSaveThatLeavesNoNameIsRefusedAndChangesNothing() {
        String seriesId = createLinkOneOff();
        Map<String, Object> before = read(seriesId);

        Map<String, Object> blank = read(seriesId);
        for (Map<String, Object> d : listOf(mapOf(blank, "series"), "descs")) {
            d.put("name", "  ");
        }
        refusedWith(as("admin").body(blank).put(BASE + "/" + seriesId), 400, "NAME_BLANK",
                "a save that blanks every name");

        Map<String, Object> none = read(seriesId);
        mapOf(none, "series").put("descs", new ArrayList<>());
        refusedWith(as("admin").body(none).put(BASE + "/" + seriesId), 400, "NAME_BLANK",
                "a save that removes every name");
        assertEquals(before, read(seriesId), "the one-off is as it was, its names and revision included");

        // A name in one language of two is a name
        Map<String, Object> half = read(seriesId);
        descsByLang(mapOf(half, "series")).get("da").put("name", "");
        object(as("admin").body(half).put(BASE + "/" + seriesId));
    }

    /** A create that names its own seriesId still needs a name: the id alone used to make a nameless one-off. */
    @Test
    void aCreateWithAnIdButNoNameIsRefused() {
        String seriesId = track(TestIds.series());
        Map<String, Object> body = oneOff("unused", "EXTERNAL_LINK", false);
        mapOf(body, "series").put("seriesId", seriesId);
        mapOf(body, "series").put("descs", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("lang", "en", "name", " ")))));
        refusedWith(as("admin").body(body).post(BASE), 400, "NAME_BLANK", "a create with an id and no name");
        assertEquals(0, count("SELECT count(*) FROM public.\"PublicationSeries\" WHERE \"seriesId\" = :s",
                "s", seriesId), "no one-off was created");
    }

    /**
     * A one-off's id is the last segment of every address that reads or saves it, and the request firewall refuses a
     * segment holding a slash, a back slash, a percent sign or a semicolon, or one of {@code .} or {@code ..}. A
     * one-off created under such an id could never be opened or saved again.
     */
    @Test
    void aCreateWithAnIdTheAddressFirewallRefusesIsRefused() {
        List<String> ids = new ArrayList<>();
        for (String held : List.of("/", ";", "%", "\\")) {
            ids.add(TestIds.series() + held + "x");
        }
        ids.add(".");
        ids.add("..");
        for (String seriesId : ids) {
            track(seriesId);
            Map<String, Object> body = oneOff("IT unreachable " + TestIds.suffix(), "EXTERNAL_LINK", false);
            mapOf(body, "series").put("seriesId", seriesId);
            refusedWith(as("admin").body(body).post(BASE), 400, "SERIES_INVALID", "a create with the id " + seriesId);
            assertEquals(0, count("SELECT count(*) FROM public.\"PublicationSeries\" WHERE \"seriesId\" = :s",
                    "s", seriesId), "nothing is stored under the id " + seriesId);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The creation in the issue's trail
    // ------------------------------------------------------------------------------------------- //

    /**
     * Only an administrator may create a one-off, so a person is always behind it: the issue's CREATED entry names
     * that person, as a series issue's does, and not the system.
     */
    @Test
    void aOneOffsCreationIsRecordedAsTheAdministratorsAction() {
        String seriesId = createLinkOneOff();
        String publicId = (String) read(seriesId).get("issuePublicId");

        List<Object[]> created = rows("SELECT a.\"actorKind\", u.username FROM public.\"IssueAuditEntry\" a"
                + " JOIN public.\"PublicationIssue\" i ON i.id = a.issue_id"
                + " LEFT JOIN public.\"User\" u ON u.id = a.user_id"
                + " WHERE i.\"publicId\" = :p AND a.action = 'CREATED'", "p", publicId);
        assertEquals(1, created.size(), "one CREATED entry on the one-off's issue");
        assertEquals("USER", created.get(0)[0], "the creation is a person's action, not the system's");
        assertEquals("testadmin", created.get(0)[1], "the administrator who created it");
    }

    // ------------------------------------------------------------------------------------------- //
    // Concurrency and roles
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aSaveFromAStaleCopyIsRefusedAndWritesNothing() {
        String seriesId = createLinkOneOff();
        Map<String, Object> first = read(seriesId);
        Map<String, Object> second = read(seriesId);
        setLink(first, "da", "https://example.org/first.pdf");
        object(as("admin").body(first).put(BASE + "/" + seriesId));
        Map<String, Object> afterFirst = read(seriesId);

        setLink(second, "en", "https://example.org/stale.pdf");
        refusedWith(as("admin").body(second).put(BASE + "/" + seriesId), 409, "STALE_VERSION", "a stale save");
        refusedWith(windowCall(seriesId, 0L, null, intOf(mapOf(second, "series"), "version")), 409, "STALE_VERSION",
                "a stale public period");
        assertEquals(links(afterFirst), links(read(seriesId)), "the stale writes changed no link");
        assertEquals(afterFirst.get("publicFrom"), read(seriesId).get("publicFrom"), "nor the period");
    }

    @Test
    void everyWriteIsRefusedToTheRolesThatMayNotMakeItAndChangesNothing() {
        String seriesId = createLinkOneOff();
        Map<String, Object> before = read(seriesId);
        long series = count("SELECT count(*) FROM public.\"PublicationSeries\"");
        Map<String, Object> body = oneOff("IT refused " + TestIds.suffix(), "EXTERNAL_LINK", true,
                link("da", "https://example.org/x"), link("en", "https://example.org/y"));
        Map<String, Object> period = Map.of("publicFrom", 0L);

        for (String role : List.of("editor", "user")) {
            refused(as(role).body(body).post(BASE), 403, role + " creating a one-off");
            refused(as(role).body(before).put(BASE + "/" + seriesId), 403, role + " saving a one-off");
            refused(as(role).body(period).put(BASE + "/" + seriesId + "/public-window"), 403,
                    role + " setting the public period");
            refused(as(role).get(BASE + "/" + seriesId), 403, role + " reading the one-off");
        }
        refused(anonymous().body(body).post(BASE), 401, "an anonymous create");
        refused(anonymous().body(before).put(BASE + "/" + seriesId), 401, "an anonymous save");
        refused(anonymous().body(period).put(BASE + "/" + seriesId + "/public-window"), 401,
                "an anonymous public period");

        refusedWith(as("admin", OTHER_DOMAIN).body(before).put(BASE + "/" + seriesId), 403, "NOT_IN_DOMAIN",
                "another desk saving the one-off");
        refusedWith(as("admin", OTHER_DOMAIN).body(period).put(BASE + "/" + seriesId + "/public-window"), 403,
                "NOT_IN_DOMAIN", "another desk setting the period");
        Map<String, Object> intoOther = oneOff("IT other desk " + TestIds.suffix(), "EXTERNAL_LINK", false);
        mapOf(intoOther, "series").put("domainId", OTHER_DOMAIN);
        refusedWith(as("admin").body(intoOther).post(BASE), 403, "NOT_IN_DOMAIN", "a create into another domain");

        assertEquals(before, read(seriesId), "the one-off is as it was");
        assertEquals(series, count("SELECT count(*) FROM public.\"PublicationSeries\""), "no series was created");
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    private String createLinkOneOff() {
        Map<String, Object> created = object(as("admin").body(oneOff("IT link " + TestIds.suffix(), "EXTERNAL_LINK",
                true, link("da", "https://example.org/da.pdf"), link("en", "https://example.org/en.pdf"))).post(BASE));
        return track((String) mapOf(created, "series").get("seriesId"));
    }

    @SafeVarargs
    private Map<String, Object> oneOff(String name, String contentMode, boolean active, Map<String, Object>... links) {
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("categoryId", categoryId);
        series.put("domainId", DOMAIN);
        series.put("contentMode", contentMode);
        series.put("languages", new ArrayList<>(List.of("da", "en")));
        series.put("descs", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("lang", "en", "name", name)),
                new LinkedHashMap<>(Map.of("lang", "da", "name", name + " (da)")))));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("series", series);
        body.put("active", active);
        body.put("links", new ArrayList<>(List.of(links)));
        return body;
    }

    private static Map<String, Object> link(String lang, String value) {
        return new LinkedHashMap<>(Map.of("lang", lang, "value", value));
    }

    private static void setLink(Map<String, Object> oneOff, String lang, String value) {
        for (Map<String, Object> l : listOf(oneOff, "links")) {
            if (lang.equals(l.get("lang"))) {
                l.put("value", value);
            }
        }
    }

    private static Map<String, String> links(Map<String, Object> oneOff) {
        Map<String, String> out = new LinkedHashMap<>();
        listOf(oneOff, "links").stream().sorted(java.util.Comparator.comparing(l -> (String) l.get("lang")))
                .forEach(l -> out.put((String) l.get("lang"), (String) l.get("value")));
        return out;
    }

    private Map<String, Object> read(String seriesId) {
        return read("en", seriesId);
    }

    private Map<String, Object> read(String lang, String seriesId) {
        return object(as("admin").get(BASE + "/" + seriesId + "?lang=" + lang));
    }

    private int version(String seriesId) {
        return intOf(mapOf(read(seriesId), "series"), "version");
    }

    private Response windowCall(String seriesId, Long from, Long to, int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("publicFrom", from);
        body.put("publicTo", to);
        body.put("version", version);
        return as("admin").body(body).put(BASE + "/" + seriesId + "/public-window?lang=en");
    }

    private Map<String, Object> window(String seriesId, Long from, Long to) {
        return object(windowCall(seriesId, from, to, version(seriesId)));
    }

    private List<String> issueActions(String seriesId) {
        String publicId = (String) read(seriesId).get("issuePublicId");
        return PublicationSeriesRoundTripITTest.actionsInOrder(array(as("admin").get(ISSUES + "/issue/" + publicId
                + "/audit")));
    }

    private long issues(String seriesId) {
        return count("SELECT count(*) FROM public.\"PublicationIssue\" i JOIN public.\"PublicationSeries\" s"
                + " ON s.id = i.series_id WHERE s.\"seriesId\" = :s", "s", seriesId);
    }

    private long issueDescRows(String seriesId) {
        return count("SELECT count(*) FROM public.\"PublicationIssueDesc\" d JOIN public.\"PublicationIssue\" i"
                + " ON i.id = d.entity_id JOIN public.\"PublicationSeries\" s ON s.id = i.series_id"
                + " WHERE s.\"seriesId\" = :s", "s", seriesId);
    }
}
