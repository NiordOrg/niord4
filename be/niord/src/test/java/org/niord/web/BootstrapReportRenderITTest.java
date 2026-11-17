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

import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.message.Message;
import org.niord.core.message.MessageKind;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageService;
import org.niord.core.report.FmReport;
import org.niord.core.report.FmReportService;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.niord.core.util.UidUtils;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ReportFixtures;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every report and mail template the base product's bootstrap ships, rendered to the HTML the PDF renderer or the
 * mail is made from, over the fixture's sample messages and one message the test makes with three parts, several
 * positions, two references and no title, and read: every message the search
 * returns is in the report, in the search's order; the table of contents names the area headings the list has;
 * nothing a template should have resolved is left in the text (a {@code null}, a {@code ${}, a FreeMarker error, a
 * {@code ???}, minutes of 60 or more). The rendered pages are written to {@code target/b2-reports/} for reading.
 *
 * <p>The reports: the three of the bootstrap ({@code standard}, {@code draft}, {@code public}, all on
 * {@code message-list-pdf.ftl}), the NM and NM T&amp;P reports of {@link ReportFixtures}, and the four other report
 * templates of the bootstrap ({@code fa-list-pdf.ftl}, {@code nm-almanac-report-pdf.ftl},
 * {@code nm-annex-report-pdf.ftl}, {@code nm-accumulated-report-pdf.ftl}) as reports this test adds and removes. The
 * mails: each of the bootstrap's mailing-list templates through the sysadmin's trigger test
 * ({@code POST /rest/mailing-lists/test-execute-trigger}, which renders and does not send) for a mailing list with one
 * contact.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class BootstrapReportRenderITTest extends RoundTripSupport {

    private static final Path OUT = Paths.get("target", "b2-reports");

    @Autowired
    FmReportService reportService;

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @Autowired
    MessageQueryService messageQueryService;

    @Autowired
    SettingsService settingsService;

    private static final String MODEL_LANGUAGES = "modelLanguages";

    private final List<String> createdUids = new ArrayList<>();
    private final List<String> addedReports = new ArrayList<>();
    private final List<String> mailingLists = new ArrayList<>();
    private final List<Integer> contacts = new ArrayList<>();

    /** The report templates the bootstrap ships beyond the three message-list reports, with a domain each */
    private static final Map<String, String[]> EXTRA_REPORTS = new LinkedHashMap<>();

    static {
        EXTRA_REPORTS.put("it-b2-fa-list", new String[]{"templates/messages/fa-list-pdf.ftl", "niord-fa"});
        EXTRA_REPORTS.put("it-b2-nm-almanac", new String[]{"templates/messages/nm-almanac-report-pdf.ftl", "niord-almanac"});
        EXTRA_REPORTS.put("it-b2-nm-annex", new String[]{"templates/messages/nm-annex-report-pdf.ftl", "niord-annex"});
        EXTRA_REPORTS.put("it-b2-nm-accumulated", new String[]{"templates/messages/nm-accumulated-report-pdf.ftl", "niord-nm"});
    }

    @BeforeEach
    void reports() {
        ReportFixtures.ensure(reportService, ReportFixtures.NM_REPORT, ReportFixtures.NM_TP_REPORT);
        Tx.requiringNew().run(() -> EXTRA_REPORTS.forEach((id, def) -> {
            if (reportService.findByReportId(id) == null) {
                FmReport report = new FmReport();
                report.setReportId(id);
                report.setName(id);
                report.setSortOrder(90);
                report.setTemplatePath(def[0]);
                Domain domain = em.createQuery("select d from Domain d where d.domainId = :id", Domain.class)
                        .setParameter("id", def[1]).getSingleResult();
                report.setDomains(new ArrayList<>(List.of(domain)));
                reportService.createReport(report);
                addedReports.add(id);
            }
        }));
    }

    @AfterEach
    void removeWhatTheTestMade() {
        // As MessageRoundTripITTest removes its messages: there is no delete endpoint for a message
        for (String uid : createdUids) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.uid = :uid")
                        .setParameter("uid", uid).executeUpdate();
                em.createQuery("delete from Comment c where c.message.id in (select m.id from Message m where m.uid = :uid)")
                        .setParameter("uid", uid).executeUpdate();
                em.createQuery("select m from org.niord.core.message.Message m where m.uid = :uid",
                                org.niord.core.message.Message.class)
                        .setParameter("uid", uid).getResultStream().findFirst().ifPresent(em::remove);
            });
        }
        mailingLists.forEach(id -> delete("sysadmin", "/rest/mailing-lists/mailing-list/" + id));
        contacts.forEach(id -> delete("sysadmin", "/rest/contacts/contact/" + id));
        Tx.requiringNew().run(() -> addedReports.forEach(reportService::deleteReport));
        createdUids.clear();
        mailingLists.clear();
        contacts.clear();
        addedReports.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // The reading
    // ------------------------------------------------------------------------------------------- //

    private static final Pattern LEFTOVER = Pattern.compile(
            "\\bnull\\b|\\bundefined\\b|\\bNaN\\b|\\$\\{|<#|\\?\\?\\?|FreeMarker template error|Exception");
    /** Minutes with any number of decimals: "60.0'", and the "60.000'" of a three-decimal position, alike */
    private static final Pattern MINUTES = Pattern.compile("(\\d{1,3})°\\s?(\\d{2})[.,]\\d+'");

    /** The visible text of a page: style and script blocks, tags and entities gone */
    static String text(String html) {
        return html.replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&deg;", "°").replace("&#39;", "'").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    static List<String> lint(String html) {
        List<String> findings = new ArrayList<>();
        String text = text(html);
        Matcher m = LEFTOVER.matcher(text);
        while (m.find()) {
            int from = Math.max(0, m.start() - 60);
            findings.add("leftover '" + m.group() + "' in: ..." + text.substring(from, Math.min(text.length(), m.end() + 40)));
        }
        m = MINUTES.matcher(text);
        while (m.find()) {
            if (Integer.parseInt(m.group(2)) >= 60) findings.add("minutes of 60 or more: " + m.group());
        }
        return findings;
    }

    private static void write(String name, String content) throws IOException {
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name), content, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------- //
    // The messages
    // ------------------------------------------------------------------------------------------- //

    /** The search the report renders, as the message list's JSON: the short ids (or uids) in order */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> search(String domain, String query) {
        return given().auth().oauth2(accessToken("sysadmin")).header("NiordDomain", domain)
                .when().get("/rest/messages/search?maxSize=1000&" + query)
                .then().statusCode(200).extract().jsonPath().getList("data");
    }

    private static Response reportResponse(String domain, String report, String query) {
        return given().auth().oauth2(accessToken("sysadmin")).header("NiordDomain", domain)
                .when().get("/rest/message-reports/report.pdf?debug=true&lang=en&report=" + report + "&" + query);
    }

    private static String reportHtml(String domain, String report, String query) {
        Response response = reportResponse(domain, report, query);
        assertEquals(200, response.statusCode(), report + ": " + response.asString());
        return response.asString();
    }

    /** A draft in the NW domain with three parts, several positions, two references and no title */
    private String createRichDraft() {
        String uid = UidUtils.newUid();
        String editRepoPath = as("editor").when().get("/rest/repo/new-temp-dir").then().statusCode(200).extract().path("path");
        long from = Instant.parse("2026-12-31T22:30:00Z").toEpochMilli();
        List<Object> parts = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "DETAILS");
            List<Object> features = new ArrayList<>();
            for (int p = 0; p < i; p++) {
                features.add(Map.of("type", "Feature", "properties", Map.of(),
                        "geometry", Map.of("type", "Point", "coordinates", List.of(10.5 + p * 0.25, 55.999999 - p * 0.1))));
            }
            Map<String, Object> geometry = Map.of("type", "FeatureCollection", "features", features);
            // The reports print a message's details as stored, and no report template formats a position itself:
            // the positions reach a report through the details, where the editor's "Insert locations" writes them
            // (format-message-geometry, the geometry templates through LatLonDirective). So the details carry the
            // part's positions formatted that way, and a formatter fault shows in the report (with the positions
            // only in the geometry, POS1's old rounding passed this class).
            String en = formattedPositions(geometry, "en");
            String da = formattedPositions(geometry, "da");
            part.put("descs", List.of(
                    Map.of("lang", "en", "subject", "Part " + i, "details",
                            "<p>Details of part " + i + ". Positions: " + en + ".</p>"),
                    Map.of("lang", "da", "subject", "Del " + i, "details",
                            "<p>Detaljer i del " + i + ". Positioner: " + da + ".</p>")));
            part.put("eventDates", List.of(Map.of("allDay", false, "fromDate", from + i * 3_600_000L,
                    "toDate", from + (i + 1) * 3_600_000L)));
            part.put("geometry", geometry);
            parts.add(part);
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", uid);
        msg.put("editRepoPath", editRepoPath);
        msg.put("mainType", "NW");
        msg.put("type", "LOCAL_WARNING");
        msg.put("status", "DRAFT");
        msg.put("autoTitle", false);
        msg.put("messageSeries", Map.of("seriesId", "dma-nw-local"));
        msg.put("areas", List.of(Map.of("id", 1000002)));
        msg.put("descs", List.of(Map.of("lang", "en"), Map.of("lang", "da")));
        msg.put("parts", parts);
        msg.put("references", List.of(Map.of("messageId", "NW-001-26", "type", "REFERENCE"),
                Map.of("messageId", "NW-002-26", "type", "CANCELLATION")));
        postRaw("editor", "/rest/messages/message", msg).statusCode(200);
        createdUids.add(uid);
        return uid;
    }

    /** The positions of a geometry as the editor's "Insert locations" writes them, one decimal of a minute */
    private static String formattedPositions(Map<String, Object> geometry, String lang) {
        return as("editor").body(geometry)
                .when().post("/rest/messages/format-message-geometry?lang=" + lang + "&template=text&format=dec-1")
                .then().statusCode(200).extract().asString().trim();
    }

    @SuppressWarnings("unchecked")
    private static String titleOf(Map<String, Object> message) {
        Object descs = message.get("descs");
        if (descs instanceof List<?> list) {
            for (Object d : list) {
                Map<String, Object> desc = (Map<String, Object>) d;
                if ("en".equals(desc.get("lang")) && desc.get("title") != null) {
                    return text((String) desc.get("title"));
                }
            }
        }
        return String.valueOf(message.get("id"));
    }

    private static String idOf(Map<String, Object> message) {
        Object shortId = message.get("shortId");
        return shortId != null ? (String) shortId : (String) message.get("id");
    }

    // ------------------------------------------------------------------------------------------- //
    // The reports
    // ------------------------------------------------------------------------------------------- //

    private List<String> checkReport(String name, String domain, String report, String query, boolean sortedByArea)
            throws IOException {
        List<String> faults = new ArrayList<>();
        List<Map<String, Object>> messages = search(domain, query);
        if (messages.isEmpty()) {
            // Every check below is a loop over the search: an empty search would pass whatever the report prints
            faults.add(name + ": the search returned no message, so the report was not checked");
        }
        Response response = reportResponse(domain, report, query);
        if (response.statusCode() != 200) {
            faults.add(name + ": HTTP " + response.statusCode());
            return faults;
        }
        String html = response.asString();
        write(name + ".html", html);
        String text = text(html);
        write(name + ".txt", text);
        // Each message's block carries an anchor id="msg_<uid>" (the T&P list, a table, carries the short id only);
        // the NM reports gather the miscellaneous notices at the end under "Announcements, etc.", by design
        boolean anchored = html.contains("id=\"msg_");
        int last = -1;
        for (Map<String, Object> message : messages) {
            String id = idOf(message);
            if (anchored && titleOf(message).equals(message.get("id"))) {
                // The anchor goes with the title (message-support.ftl 305-306): a message without one, the test's
                // three-part draft, is read by aMessageWithThreePartsAndNoTitle_isPrintedWhole
                continue;
            }
            int at = anchored ? html.indexOf("id=\"msg_" + message.get("id") + "\"")
                    : text.indexOf(message.get("shortId") != null ? id : titleOf(message));
            if (at < 0) {
                faults.add(name + ": message " + id + " missing");
            } else if (!"MISCELLANEOUS_NOTICE".equals(message.get("type"))) {
                if (at < last) faults.add(name + ": message " + id + " out of the search's order");
                last = at;
            }
        }
        if (sortedByArea) {
            int toc = count(html, "<li><a href='#") + count(html, "<div class=\"toc\"><a href='#misc'>");
            int headings = count(html, "class=\"table-header\"><h4 id=");
            if (html.contains("class='toc'") && toc != headings) {
                faults.add(name + ": the table of contents has " + toc + " entries, the list " + headings + " area headings");
            }
        }
        lint(html).forEach(f -> faults.add(name + ": " + f));
        return faults;
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }

    private static final String ALL_STATUSES =
            "status=PUBLISHED&status=DRAFT&status=VERIFIED&status=CANCELLED&status=EXPIRED";

    /**
     * Each report over its domain's messages, every status, in the order the search gives and by area: every message
     * is in it, in order, the contents match the headings, nothing is left unresolved.
     */
    @Test
    void everyReport_listsEveryMessageOfItsSearch_inOrder_andLeavesNothingUnresolved() throws IOException {
        createRichDraft();
        List<String> faults = new ArrayList<>();
        for (String report : List.of("standard", "draft", "public")) {
            faults.addAll(checkReport(report + "-nw-by-area", "niord-nw", report, ALL_STATUSES + "&sortBy=AREA", true));
            faults.addAll(checkReport(report + "-nw-by-date", "niord-nw", report, ALL_STATUSES + "&sortBy=DATE", false));
        }
        faults.addAll(checkReport("nm-report", "niord-nm", ReportFixtures.NM_REPORT, ALL_STATUSES + "&sortBy=AREA", true));
        // The T&P list holds the temporary and preliminary notices only, by design
        faults.addAll(checkReport("nm-tp-report", "niord-nm", ReportFixtures.NM_TP_REPORT,
                ALL_STATUSES + "&type=TEMPORARY_NOTICE&type=PRELIMINARY_NOTICE&sortBy=AREA", true));
        for (Map.Entry<String, String[]> e : EXTRA_REPORTS.entrySet()) {
            faults.addAll(checkReport(e.getKey(), e.getValue()[1], e.getKey(), ALL_STATUSES + "&sortBy=AREA", true));
        }
        assertEquals(List.of(), faults);
    }

    /**
     * RENDER8 (2026-11-12): the NM almanac report's table of contents printed {@code ${msg.shortId}}
     * unguarded ({@code nm-almanac-report-pdf.ftl} line 53), so a list that held a message without a short id (a
     * draft or a verified notice not yet numbered, which the print dialog lists when asked for them) answered 500
     * "Error generating PDF for messages" instead of the PDF. The short id is now guarded as every other template
     * guards it ({@code message-support.ftl} 290, {@code nm-tp-report-pdf.ftl} 97): the report answers 200, and the
     * table of contents lists the unnumbered message by its title, without a number and without a "null".
     */
    @Test
    void theAlmanacReport_listsAMessageWithoutAShortId() throws IOException {
        String query = ALL_STATUSES + "&sortBy=AREA";
        List<Map<String, Object>> unnumbered = search("niord-almanac", query).stream()
                .filter(m -> m.get("shortId") == null && !titleOf(m).equals(m.get("id")))
                .toList();
        assertFalse(unnumbered.isEmpty(), "precondition: the almanac's sample holds a titled message without a short id");

        String html = reportHtml("niord-almanac", "it-b2-nm-almanac", query);
        write("nm-almanac-unnumbered.html", html);
        int tocStart = html.indexOf("<h2>Table of Contents</h2>");
        int tocEnd = html.indexOf("class=\"page-break\"", Math.max(tocStart, 0));
        assertTrue(tocStart >= 0 && tocEnd > tocStart, "the table of contents: " + html);
        String toc = html.substring(tocStart, tocEnd);
        String tocText = text(toc);
        for (Map<String, Object> m : unnumbered) {
            String uid = (String) m.get("id");
            assertTrue(toc.contains("<a href='#msg_" + uid + "'></a>"), "no number for " + uid + " in: " + toc);
            assertTrue(toc.contains("<a href='#msg_" + uid + "'>") && tocText.contains(titleOf(m)),
                    "the title of " + uid + " in the table of contents: " + tocText);
        }
        assertEquals(count(toc, "<div"), count(toc, "</div>"), "no stray </div> after the number: " + toc);
        assertEquals(List.of(), lint(toc));

        // The control: the published notices only, all of them numbered
        assertEquals(200, reportResponse("niord-almanac", "it-b2-nm-almanac", "status=PUBLISHED&sortBy=AREA").statusCode());
    }

    /**
     * A list marks a cancelled notice, and an expired one that is not permanent, beside its short id
     * ({@code message-support.ftl} 293-295), so that a mariner reading the standard or the draft list over every
     * status does not take a cancelled warning for one in force. Every message of the search with a short id is found
     * by its id, and the label after it is the one its status calls for, or none (a template that no longer
     * labelled cancelled notices passed every other test of this class).
     */
    @Test
    void theLists_labelEveryCancelledAndExpiredMessage_besideItsShortId() {
        List<String> faults = new ArrayList<>();
        int cancelled = 0, expired = 0;
        String query = ALL_STATUSES + "&sortBy=AREA";
        for (String report : List.of("standard", "draft")) {
            String html = reportHtml("niord-nw", report, query);
            for (Map<String, Object> m : search("niord-nw", query)) {
                String shortId = (String) m.get("shortId");
                if (shortId == null) {
                    continue;
                }
                String status = String.valueOf(m.get("status"));
                String expected = "CANCELLED".equals(status) ? "Cancelled"
                        : "EXPIRED".equals(status) && !"PERMANENT_NOTICE".equals(m.get("type")) ? "Expired" : null;
                cancelled += "Cancelled".equals(expected) ? 1 : 0;
                expired += "Expired".equals(expected) ? 1 : 0;
                Matcher label = Pattern.compile(Pattern.quote("<span class=\"label-message-id\">" + shortId + "</span>")
                        + "\\s*(?:<span class=\"label-status\">([^<]*)</span>)?").matcher(html);
                if (!label.find()) {
                    faults.add(report + ": " + shortId + " (" + status + ") is not in the list");
                } else if (!java.util.Objects.equals(expected, label.group(1) == null ? null : label.group(1).trim())) {
                    faults.add(report + ": " + shortId + " (" + status + ") is labelled " + label.group(1)
                            + ", not " + expected);
                }
            }
        }
        assertTrue(cancelled > 0 && expired > 0,
                "the sample's NW domain holds a cancelled and an expired warning: " + cancelled + ", " + expired);
        assertEquals(List.of(), faults);
    }

    /** The rich draft: its three parts, every position and both references, and no title printed as "null" */
    @Test
    void aMessageWithThreePartsAndNoTitle_isPrintedWhole() throws IOException {
        String uid = createRichDraft();
        String html = reportHtml("niord-nw", "draft", "status=DRAFT&sortBy=AREA");
        write("rich-draft.html", html);
        String text = text(html);
        write("rich-draft.txt", text);
        for (String expected : List.of("Details of part 1.", "Details of part 2.", "Details of part 3.",
                "Detaljer i del 3.", "NW-001-26", "NW-002-26")) {
            assertTrue(text.contains(expected), expected + " in " + uid);
        }
        // 55.999999 N 10.5 E, the first position of every part, is just below a whole degree of latitude
        assertTrue(text.contains("56° 00.0'N - 010° 30.0'E"), "the position just below a whole degree in " + uid);
        assertTrue(text.contains("56° 00,0'N - 010° 30,0'E"), "the Danish position in " + uid);
        assertFalse(text.contains("60.0'") || text.contains("60,0'"),
                "a position just below a whole degree prints as the whole degree");
        assertEquals(List.of(), lint(html));
    }

    /**
     * TMPL20 (2026-11-15): a draft print heads each language of a message with the language's name,
     * {@code text('lang.' + lang)} ({@code message-support.ftl}), and a model language that no dictionary holds a
     * {@code lang.<code>} for threw "No such key", so the whole print answered 500. The test's packs hold
     * {@code lang.en} and {@code lang.da}; a third model language, {@code fo}, has none. The print now renders and
     * names that language by its code in upper case, as the administration app labels it; the two languages with a
     * name keep it.
     */
    @Test
    void aDraftPrint_namesAModelLanguageWithoutALangText_byItsCode() throws IOException {
        createRichDraft();
        Object original = Tx.requiringNew().call(() -> em.find(Setting.class, MODEL_LANGUAGES).getValue());
        String html;
        try {
            Tx.requiringNew().run(() -> settingsService.set(MODEL_LANGUAGES, List.of("da", "en", "fo")));
            html = reportHtml("niord-nw", "draft", "status=DRAFT&sortBy=AREA");
        } finally {
            Tx.requiringNew().run(() -> settingsService.set(MODEL_LANGUAGES, original));
            settingsService.evictFromCache(MODEL_LANGUAGES);
        }
        write("draft-language-without-name.html", html);
        assertTrue(html.contains("/img/flags/fo.png\" height=\"12\"/>&nbsp;FO Translation"),
                "the language without a lang.fo text, named by its code: " + html);
        assertTrue(html.contains("/img/flags/en.png\" height=\"12\"/>&nbsp;English Translation"),
                "a language with a name keeps it: " + html);
        assertEquals(List.of(), lint(html));
    }

    // ------------------------------------------------------------------------------------------- //
    // The mails
    // ------------------------------------------------------------------------------------------- //

    /** Each mailing-list template of the bootstrap with the message and trigger it is written for */
    private static final Map<String, Object[]> MAIL_TEMPLATES = new LinkedHashMap<>();

    static {
        MAIL_TEMPLATES.put("publish-navwarn", new Object[]{"STATUS_CHANGE", "PUBLISHED", "templates/mailinglist/publish-navwarn.ftl"});
        MAIL_TEMPLATES.put("cancel-navwarn", new Object[]{"STATUS_CHANGE", "CANCELLED", "templates/mailinglist/cancel-navwarn.ftl"});
        MAIL_TEMPLATES.put("cancel-navtex", new Object[]{"STATUS_CHANGE", "CANCELLED", "templates/mailinglist/cancel-navtex.ftl"});
        MAIL_TEMPLATES.put("cancel-safetynet", new Object[]{"STATUS_CHANGE", "CANCELLED", "templates/mailinglist/cancel-safetynet.ftl"});
        MAIL_TEMPLATES.put("active-navwarns", new Object[]{"SCHEDULED", null, "templates/mailinglist/active-navwarns.ftl"});
    }

    private String mailingListWithAContact() {
        String email = "it-b2-" + unique() + "@example.org";
        Map<String, Object> contactVo = post("sysadmin", "/rest/contacts/contact",
                Map.of("email", email, "name", "IT-B2", "language", "en"));
        int contact = ((Number) contactVo.get("id")).intValue();
        contacts.add(contact);
        String listId = "it-b2-" + unique();
        post("sysadmin", "/rest/mailing-lists/mailing-list", Map.of("mailingListId", listId, "active", false,
                "descs", List.of(Map.of("lang", "en", "name", "IT-B2 list")), "triggers", List.of()));
        mailingLists.add(listId);
        put("sysadmin", "/rest/mailing-lists/mailing-list/" + listId + "/contacts", List.of(contactVo));
        return listId;
    }

    private Response testExecute(String listId, String name, Object[] def) {
        return testExecute(listId, name, def, "NW-001-26");
    }

    private Response testExecute(String listId, String name, Object[] def, String messageId) {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("type", def[0]);
        if (def[1] != null) trigger.put("statusChanges", List.of(def[1]));
        trigger.put("scriptResourcePaths", ((String) def[2]).endsWith("publish-safetynet.ftl")
                // the script that looks the area up goes first, as a deployment's trigger lists them
                ? List.of("templates/mailinglist/publish-safetynet.js", def[2])
                : List.of(def[2]));
        trigger.put("descs", List.of(Map.of("lang", "en", "subject", "IT-B2 " + name)));
        Map<String, Object> list = Map.of("mailingListId", listId, "active", false,
                "descs", List.of(Map.of("lang", "en", "name", "IT-B2 list")), "triggers", List.of(trigger));
        return as("sysadmin").body(list)
                .when().post("/rest/mailing-lists/test-execute-trigger?triggerIndex=0&messageId=" + messageId);
    }

    /** The one mail the trigger test renders for the list's one contact, as HTML; asserts the answer is 200 */
    private String onlyMail(Response response, String what) {
        assertEquals(200, response.statusCode(), what + ": " + response.asString());
        List<Map<String, Object>> mails = response.jsonPath().getList("");
        assertEquals(1, mails.size(), what + ": one mail for the list's one contact");
        return String.valueOf(mails.getFirst().get("contents"));
    }

    /**
     * MAILLIST4 (2026-11-12): the NAVTEX station mail ({@code publish-navtex.ftl},
     * {@code <@txtToHtml text=navtex.preamble/>}) failed with a FreeMarker error when the message's NAVTEX promulgation
     * had no preamble, which is the case for every message of a series without a NAVTEX format (the sample's and
     * {@code niord_test}'s {@code dma-nw-local}): {@code TextToHtmlDirective} refused a null text instead of printing
     * nothing. The SafetyNET mail ({@code publish-safetynet.ftl}, {@code <#assign areaType = params.area.class.name/>})
     * failed the same way whenever {@code publish-safetynet.js} found no SafetyNET area for the promulgation; the seed
     * defines no SafetyNET area, so it finds none here. The trigger then rendered no mail for the station. Now both
     * mails render: for a message without the promulgation (NW-001-26), and for a draft whose NAVTEX promulgation has
     * a text and no preamble, whose mail holds the text.
     */
    @Test
    void theNavtexAndSafetynetMails_renderWithoutAPreambleOrArea() throws IOException {
        String listId = mailingListWithAContact();
        Object[] navtexMail = {"STATUS_CHANGE", "PUBLISHED", "templates/mailinglist/publish-navtex.ftl"};
        Object[] safetynetMail = {"STATUS_CHANGE", "PUBLISHED", "templates/mailinglist/publish-safetynet.ftl"};

        // NW-001-26 has neither promulgation: both mails render, with nothing left unresolved
        assertEquals(List.of(), lint(onlyMail(testExecute(listId, "publish-navtex", navtexMail), "publish-navtex")));
        String safetynet = onlyMail(testExecute(listId, "publish-safetynet", safetynetMail), "publish-safetynet");
        write("mail-publish-safetynet-without-area.html", safetynet);
        assertTrue(text(safetynet).contains("Area Type"), "the SafetyNET table, with an empty area type: " + safetynet);
        assertEquals(List.of(), lint(safetynet));

        // A draft of a series without a NAVTEX format, with a NAVTEX text and no preamble
        String navtexText = "IT-B2 NAVTEX TEXT WITHOUT A PREAMBLE";
        String uid = UidUtils.newUid();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", uid);
        msg.put("editRepoPath", as("editor").when().get("/rest/repo/new-temp-dir").then().statusCode(200)
                .extract().path("path"));
        msg.put("mainType", "NW");
        msg.put("type", "COASTAL_WARNING");
        msg.put("status", "DRAFT");
        msg.put("autoTitle", false);
        msg.put("messageSeries", Map.of("seriesId", "dma-nw-local"));
        msg.put("areas", List.of(Map.of("id", 1000002)));
        msg.put("descs", List.of(Map.of("lang", "en", "title", "IT-B2 NAVTEX without a preamble")));
        msg.put("promulgations", List.of(Map.of("@class", "org.niord.core.promulgation.vo.NavtexMessagePromulgationVo",
                "type", Map.of("typeId", "navtex"), "promulgate", true, "text", navtexText)));
        postRaw("editor", "/rest/messages/message", msg).statusCode(200);
        createdUids.add(uid);

        String navtex = onlyMail(testExecute(listId, "publish-navtex", navtexMail, uid), "publish-navtex of the draft");
        write("mail-publish-navtex-without-preamble.html", navtex);
        assertTrue(navtex.contains(navtexText), "the NAVTEX text in the station mail: " + navtex);
        assertFalse(navtex.substring(0, navtex.indexOf(navtexText)).contains("<br>"),
                "no empty preamble line before the text: " + navtex);
        assertEquals(List.of(), lint(navtex));
        assertEquals(List.of(), lint(onlyMail(testExecute(listId, "publish-safetynet", safetynetMail, uid),
                "publish-safetynet of the draft")));
    }

    /**
     * MAILLIST5 (2026-11-12): the mailing-list mail of a cancellation warning said only its
     * number, its date and its area ({@code publish-navwarn-common.ftl} {@code renderNavWarnDetails} prints the parts'
     * details, and a cancellation warning has none). Its title, which names the warning it cancels, is now printed.
     * A published dma-nw warning is cancelled over REST, which issues the cancellation warning; the PUBLISHED mail of
     * {@code publish-navwarn.ftl} is rendered for it through the trigger test. An ordinary warning's mail still does
     * not print its title.
     */
    @Test
    void theNavwarnMailOfACancellationWarning_namesWhatItCancels() throws IOException {
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        // The cancellation warning takes the current year's next number: restored after the test
        Integer nextNumberBefore = Tx.requiringNew().call(() -> messageSeriesService.getNextMessageNumber("dma-nw", year));
        List<String> made = new ArrayList<>();
        try {
            String sourceUid = Tx.requiringNew().call(() -> {
                Message draft = new Message();
                draft.setMessageSeries(new MessageSeries("dma-nw"));
                draft.setType(Type.COASTAL_WARNING);
                draft.setAutoTitle(false);
                // A past publish date: the source is numbered off the year 2001, not the current year
                draft.setPublishDateFrom(java.util.Date.from(Instant.parse("2001-06-15T12:00:00Z")));
                MessagePart part = new MessagePart(MessagePartType.DETAILS);
                part.createDesc("en").setDetails("IT-B2 details of the warning to cancel");
                draft.addPart(part);
                draft.createDesc("en").setTitle("IT-B2 warning to cancel");
                return messageService.createMessage(draft).getUid();
            });
            made.add(sourceUid);
            Tx.requiringNew().run(() -> {
                messageService.updateStatus(sourceUid, Status.VERIFIED);
                messageService.updateStatus(sourceUid, Status.PUBLISHED);
            });
            String sourceShortId = Tx.requiringNew().call(() -> messageQueryService.findByUid(sourceUid).getShortId());

            as("sysadmin").body("CANCELLED").when().put("/rest/messages/message/" + sourceUid + "/status")
                    .then().statusCode(200);
            List<String> minted = Tx.requiringNew().call(() -> em.createQuery("select m.uid from Message m join m.references r "
                            + "where m.kind = :kind and r.messageId = :uid", String.class)
                    .setParameter("kind", MessageKind.CANCELLATION).setParameter("uid", sourceUid).getResultList());
            assertEquals(1, minted.size(), "the cancel issued one cancellation warning");
            made.addFirst(minted.getFirst());

            String listId = mailingListWithAContact();
            Object[] navwarnMail = {"STATUS_CHANGE", "PUBLISHED", "templates/mailinglist/publish-navwarn.ftl"};
            String html = onlyMail(testExecute(listId, "publish-navwarn", navwarnMail, minted.getFirst()),
                    "publish-navwarn of the cancellation warning");
            write("mail-publish-navwarn-cancellation.html", html);
            String text = text(html);
            assertTrue(text.contains("Cancellation of " + sourceShortId), "what the warning cancels: " + text);
            assertEquals(List.of(), lint(html));

            // The control: an ordinary warning's mail prints its date, area and details, not its title
            String ordinary = text(onlyMail(testExecute(listId, "publish-navwarn", navwarnMail), "publish-navwarn"));
            List<String> title = strings("SELECT d.title FROM public.\"MessageDesc\" d JOIN public.\"Message\" m"
                    + " ON d.entity_id = m.id WHERE m.\"shortId\" = 'NW-001-26' AND d.lang = 'en'");
            assertEquals(1, title.size(), "NW-001-26's English title");
            assertTrue(ordinary.contains("Navigational Warning no. NW-001-26"), ordinary);
            assertFalse(ordinary.contains(title.getFirst()), "an ordinary warning's mail does not print its title: " + ordinary);
            assertFalse(ordinary.contains("Cancellation"), ordinary);
        } finally {
            // As MessageCancellationWarningITTest removes its messages, best effort: the cancellation warning first.
            // Then the number is restored: while the cancellation warning holds it, a reset to it is refused
            // (SERIES-RESET)
            for (String uid : made) {
                try {
                    Tx.requiringNew().run(() -> {
                        Message m = messageQueryService.findByUid(uid);
                        if (m != null) {
                            em.createQuery("delete from MessageHistory mh where mh.message.uid = :uid")
                                    .setParameter("uid", uid).executeUpdate();
                            em.remove(m);
                        }
                    });
                } catch (Exception ignored) {
                    // best effort
                }
            }
            Tx.requiringNew().run(() -> messageSeriesService.setNextMessageNumber("dma-nw", year, nextNumberBefore));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyMailTemplate_rendersAMailForTheMessage_andLeavesNothingUnresolved() throws IOException {
        String listId = mailingListWithAContact();
        List<String> faults = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (Map.Entry<String, Object[]> e : MAIL_TEMPLATES.entrySet()) {
            Object[] def = e.getValue();
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("type", def[0]);
            if (def[1] != null) trigger.put("statusChanges", List.of(def[1]));
            if ("SCHEDULED".equals(def[0])) {
                trigger.put("scheduleType", "DAILY");
                trigger.put("scheduledExecutionTime", "08:00");
                trigger.put("scheduledExecutionTimeZone", "Europe/Copenhagen");
                trigger.put("messageQuery", "domain=niord-nw&status=PUBLISHED");
            }
            trigger.put("scriptResourcePaths", List.of(def[2]));
            trigger.put("descs", List.of(Map.of("lang", "en", "subject", "IT-B2 " + e.getKey())));
            Map<String, Object> list = Map.of("mailingListId", listId, "active", false,
                    "descs", List.of(Map.of("lang", "en", "name", "IT-B2 list")), "triggers", List.of(trigger));
            Response response = as("sysadmin").body(list)
                    .when().post("/rest/mailing-lists/test-execute-trigger?triggerIndex=0&messageId=NW-001-26");
            if (response.statusCode() != 200) {
                faults.add(e.getKey() + ": HTTP " + response.statusCode());
                all.append("## ").append(e.getKey()).append(": HTTP ").append(response.statusCode()).append('\n');
                continue;
            }
            List<Map<String, Object>> mails = response.jsonPath().getList("");
            if (mails.isEmpty()) faults.add(e.getKey() + ": no mail");
            for (Map<String, Object> mail : mails) {
                String html = String.valueOf(mail.get("contents"));
                all.append("## ").append(e.getKey()).append(" | ").append(mail.get("subject")).append('\n')
                        .append(text(html)).append("\n\n");
                write("mail-" + e.getKey() + ".html", html);
                lint(html).forEach(f -> faults.add(e.getKey() + ": " + f));
            }
        }
        write("mails.txt", all.toString());
        assertEquals(List.of(), faults);
    }
}
