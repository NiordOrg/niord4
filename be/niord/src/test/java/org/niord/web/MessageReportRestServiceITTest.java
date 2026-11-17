/*
 * Copyright 2024 Danish Maritime Authority.
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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.core.message.MessageHistory;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageService;
import org.niord.core.message.MessageTag;
import org.niord.core.message.MessageTagService;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.core.report.FmReportService;
import org.niord.model.message.Status;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.MessageTestFactory;
import org.niord.test.ReportFixtures;
import org.niord.test.Tx;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MessageReportRestService}: the HTTP contract and authorization of the
 * {@code /rest/message-reports} endpoints. The two PDF endpoints are exercised for who gets which
 * answer - the PDF, the debug HTML, a 403, a 404 - over empty lists, as the seed holds no messages, and over
 * one message a test commits and removes again; what a rendered message looks like is covered by
 * {@code MessageReportRestServiceTest} and {@code FmTemplateBuilderTimeZoneTest}.
 *
 * <p><b>Authorization model under test.</b> The whole class is annotated {@code @PermitAll}, so by
 * default every endpoint is reachable by anonymous callers. Only two endpoints add real protection:
 * <ul>
 *   <li>{@code GET /all} stays {@code @PermitAll} but enforces {@code SYSADMIN} <em>programmatically</em>
 *       inside the handler ({@code userService.isCallerInRole(SYSADMIN)} -&gt; {@code NiordWebException(403)}).
 *       Because the {@code @PermitAll} handler is reached even by an anonymous caller, the wrong identity
 *       is rejected with {@code 403} rather than the container's {@code 401}.</li>
 *   <li>{@code POST /upload-reports} is the only endpoint with {@code @RolesAllowed(SYSADMIN)}, so the
 *       container rejects an anonymous caller with {@code 401} and an authenticated non-sysadmin
 *       (e.g. admin, one tier too low) with {@code 403} before the handler runs.</li>
 * </ul>
 *
 * <p><b>Report-CRUD authorization.</b> The report create/update/delete handlers
 * ({@code POST /report/}, {@code PUT /report/{id}}, {@code DELETE /report/{id}}) now each carry
 * {@code @RolesAllowed(SYSADMIN)} of their own, matching the sibling {@code POST /upload-reports}.
 * Previously they only inherited the class-level {@code @PermitAll} and were reachable <em>anonymously</em>:
 * an unauthenticated caller could create, mutate and delete report definitions. These tests pin the fix —
 * the container rejects an anonymous caller with {@code 401} and an authenticated admin (one tier too low)
 * with {@code 403}, while a sysadmin succeeds. The full create/update/delete
 * lifecycle round-trips live in {@link MessageReportRestCrudITTest}.
 *
 * <p><b>Tier oracle.</b> The role hierarchy is {@code sysadmin > admin > editor > user}
 * (see {@link org.niord.core.user.Roles}); see {@link KeycloakTestTokens} for the one-token-per-tier
 * matrix. Every authenticated request carries {@code NiordDomain: niord-nw} so the augmentor
 * realizes the full role chain. The key adjacent-tier case below is {@code admin} being rejected by the
 * {@code SYSADMIN}-only endpoints because it is exactly one tier too low.
 *
 * <p><b>Seeded data oracle.</b> The fixture holds the base product's three {@code FmReport} rows
 * ({@code standard}, {@code draft} and {@code public}, the report the public site prints with; see
 * db/niord-base-data.sql), and {@link ReportFixtures} adds two of DK's before each test ({@code nm-report},
 * {@code nm-tp-report}, linked to {@code niord-nm}). So {@code GET /reports} returns at least two,
 * {@code GET /all} (as sysadmin) returns at least three, and {@code GET /detail-reports} returns exactly
 * the two public detail reports {@code standard} and {@code draft}.
 *
 * <p>HTTP requests run in the server's own transaction, which {@code @Transactional} cannot roll back,
 * so created reports are tracked by id and best-effort deleted anonymously (delete is also unprotected)
 * in {@link #cleanup()} using unique {@code IT-REST-REPORT-} ids that never collide with seeded rows.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class MessageReportRestServiceITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String REPORTS        = "/rest/message-reports/reports";
    private static final String ALL            = "/rest/message-reports/all";
    private static final String DETAIL_REPORTS = "/rest/message-reports/detail-reports";
    private static final String REPORT         = "/rest/message-reports/report/";
    private static final String UPLOAD_REPORTS = "/rest/message-reports/upload-reports";

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @PersistenceContext
    EntityManager em;

    private final List<String> createdIds = new ArrayList<>();

    @Autowired
    FmReportService fmReportService;

    /** DK's two domain-linked reports the tests name, which the fixture does not carry */
    @BeforeEach
    void ensureTheNmReports() {
        ReportFixtures.ensure(fmReportService, ReportFixtures.NM_REPORT, ReportFixtures.NM_TP_REPORT);
    }

    @Autowired
    MessageTagService messageTagService;

    /** UIDs of the messages a test committed; removed with their history in {@link #cleanup()}. */
    private final List<String> createdMessageUids = new ArrayList<>();

    /** IDs of the tags a test committed; removed in {@link #cleanup()} before the messages they hold. */
    private final List<String> createdTagIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // Delete now requires SYSADMIN (bug WEB1 fixed), so cleanup deletes with a sysadmin token,
        // best-effort.
        for (String id : createdIds) {
            try {
                as(accessToken()).when().delete(REPORT + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdIds.clear();

        for (String tagId : createdTagIds) {
            try {
                messageTagService.clearMessageTag(tagId);
                messageTagService.deleteMessageTag(tagId);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdTagIds.clear();

        for (String uid : createdMessageUids) {
            try {
                Tx.requiringNew().run(() -> {
                    Message msg = messageQueryService.findByUid(uid);
                    if (msg != null) {
                        // MessageHistory is not cascaded from Message, so those rows go first
                        em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                                .setParameter("messageId", msg.getId())
                                .getResultList()
                                .forEach(em::remove);
                        em.remove(em.contains(msg) ? msg : em.merge(msg));
                    }
                });
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdMessageUids.clear();
    }

    /**
     * Commits one message an anonymous caller may read, and tracks it for {@link #cleanup()}: a draft
     * made public by its status alone. The publish flow would also number it and hand it to the status
     * listeners - promulgations, datasets - whose rows the PDF never reads and which would outlive the
     * test.
     *
     * @return the message's UID
     */
    private String createCommittedPublicMessage() {
        String uid = Tx.requiringNew().call(() -> messageService
                .createMessage(MessageTestFactory.draftNw("IT-REPORT-PDF-" + System.nanoTime()))
                .getUid());
        createdMessageUids.add(uid);
        Tx.requiringNew().run(() -> em
                .createQuery("update Message m set m.status = :status where m.uid = :uid")
                .setParameter("status", Status.PUBLISHED)
                .setParameter("uid", uid)
                .executeUpdate());
        return uid;
    }

    /**
     * Commits a draft in a committed PUBLIC tag with no owner and no domain - the kind the publication code
     * creates - and tracks both for {@link #cleanup()}.
     *
     * @return the draft's UID
     */
    private String createCommittedDraftInPublicTag() {
        String uid = Tx.requiringNew().call(() -> messageService
                .createMessage(MessageTestFactory.draftNw("IT-REPORT-PDF-" + System.nanoTime()))
                .getUid());
        createdMessageUids.add(uid);
        MessageTag template = new MessageTag();
        template.setType(MessageTagType.PUBLIC);
        template.setName("IT-REPORT-PDF-PublicTag-" + System.nanoTime());
        MessageTag tag = messageTagService.createMessageTag(template);
        createdTagIds.add(tag.getTagId());
        messageTagService.addMessageToTag(tag.getTagId(), List.of(uid));
        return uid;
    }

    /** Body for a minimal, valid report with the given reportId. */
    private static Map<String, Object> body(String reportId) {
        return Map.of(
                "reportId", reportId,
                "name", "IT",
                "templatePath", "/x.ftl");
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /reports — @PermitAll. Open to anonymous callers; returns the domain's reports.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getReports_allowsAnonymousAndReturnsReports() {
        given().when().get(REPORTS)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(2));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /all — @PermitAll, but SYSADMIN enforced programmatically -> wrong identity is 403, not 401
    // (the @PermitAll handler is reached, then throws NiordWebException(403)).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAllReports_rejectsAnonymousWith403() {
        // @PermitAll lets the anonymous caller reach the handler, where the programmatic
        // isCallerInRole(SYSADMIN) check fails -> 403 (not the container's 401).
        given().when().get(ALL).then().statusCode(403);
    }

    @Test
    void getAllReports_forbidsEditorWith403() {
        as(accessToken("editor")).when().get(ALL).then().statusCode(403);
    }

    @Test
    void getAllReports_forbidsAdminWith403() {
        // KEY adjacent-tier case: an authenticated admin lacks 'sysadmin'.
        as(accessToken("admin")).when().get(ALL).then().statusCode(403);
    }

    @Test
    void getAllReports_allowsSysadminAndReturnsAllSeededReports() {
        as(accessToken()).when().get(ALL)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(3))
                .body("reportId", hasItem("standard"));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /detail-reports — @PermitAll. The built-in detail reports the database has: both, on the seed.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getDetailReports_allowsAnonymousAndReturnsStandardAndDraft() {
        given().when().get(DETAIL_REPORTS)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", is(2))
                .body("reportId", hasItem("standard"))
                .body("reportId", hasItem("draft"));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /report/ — @RolesAllowed(SYSADMIN) (bug WEB1 fixed): anonymous -> 401, admin one tier too
    // low -> 403, sysadmin succeeds and the row persists (verified via the SYSADMIN-only /all read).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createReport_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON).body(body("IT-REST-REPORT-" + System.nanoTime()))
                .when().post(REPORT)
                .then().statusCode(401);
    }

    @Test
    void createReport_forbidsAdminWith403() {
        // KEY adjacent-tier case: an authenticated admin lacks 'sysadmin'.
        as(accessToken("admin")).contentType(ContentType.JSON)
                .body(body("IT-REST-REPORT-" + System.nanoTime()))
                .when().post(REPORT)
                .then().statusCode(403);
    }

    @Test
    void createReport_allowsSysadmin_andItPersists() {
        String id = "IT-REST-REPORT-" + System.nanoTime();
        createdIds.add(id);

        as(accessToken()).contentType(ContentType.JSON).body(body(id))
                .when().post(REPORT)
                .then().statusCode(200)
                .body("reportId", is(id));

        // Verify it actually persisted: the SYSADMIN-only /all listing now includes the new id.
        as(accessToken()).when().get(ALL)
                .then().statusCode(200)
                .body("reportId", hasItem(id));
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /report/{id} — @RolesAllowed(SYSADMIN). A sysadmin reaches the handler, where a mismatched
    // path id vs body reportId -> 400 (the guard runs after the role check passes).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateReport_asSysadminWithMismatchedIdReturns400() {
        as(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("reportId", "IT-REST-REPORT-B", "name", "x", "templatePath", "/x.ftl"))
                .when().put(REPORT + "IT-REST-REPORT-A")
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /upload-reports — @RolesAllowed(SYSADMIN), multipart. The only container-protected endpoint:
    // anonymous -> 401; admin one tier too low -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadReports_rejectsAnonymousWith401() {
        given().multiPart("file", "x.json", new byte[]{1})
                .when().post(UPLOAD_REPORTS)
                .then().statusCode(401);
    }

    @Test
    void uploadReports_forbidsAdminWith403() {
        // KEY adjacent-tier case: an authenticated admin lacks 'sysadmin'.
        as(accessToken("admin")).multiPart("file", "x.json", new byte[]{1})
                .when().post(UPLOAD_REPORTS)
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // The two PDF endpoints — @PermitAll, and where the public site sends its readers. An anonymous
    // caller gets the document whatever the query says, but a list only in the public report and one
    // message only in the standard one; the debug HTML, the free-form param: values and the choice of
    // report belong to a caller the backend knows. The seed holds no messages, so the lists are empty
    // and one message is committed by the test that prints it — what is under test is who gets which
    // answer, rendered through the real stack. A draft stays unprintable for an anonymous caller even
    // when a PUBLIC tag holds it; the tag shares it with the user role of any domain only.
    // ------------------------------------------------------------------------------------------- //

    private static final String LIST_PDF = "/rest/message-reports/report.pdf";

    @Test
    void messagePdf_ofAnUnknownMessageIsNotFound() {
        given().queryParam("lang", "en")
                .when().get("/rest/message-reports/message/IT-no-such-message.pdf")
                .then().statusCode(404);
    }

    /**
     * A built-in report is data: on a database that does not have it (not bootstrapped yet, or deleted by an
     * operator), the public site's list PDF answers 404 with the reason, not 409 with a stack trace in the log. The
     * report is hidden by renaming it, and renamed back.
     */
    @Test
    void listPdf_withoutThePublicReportAnswers404NamingIt() {
        String hidden = "public-hidden-" + System.nanoTime();
        Tx.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE public.\"FmReport\" SET \"reportId\" = :hidden WHERE \"reportId\" = 'public'")
                .setParameter("hidden", hidden).executeUpdate());
        try {
            given().queryParam("lang", "en")
                    .when().get(LIST_PDF)
                    .then().statusCode(404)
                    .body("detail", containsString("The report 'public' does not exist"))
                    .body("detail", containsString("bootstrap"));
        } finally {
            Tx.requiringNew().run(() -> em.createNativeQuery(
                            "UPDATE public.\"FmReport\" SET \"reportId\" = 'public' WHERE \"reportId\" = :hidden")
                    .setParameter("hidden", hidden).executeUpdate());
        }
    }

    @Test
    void messagePdf_givesAnAnonymousCallerThatNamesNoReportThePdf() {
        // The public site's address: the language alone. Which report it is rendered in is
        // MessageReportRestServiceTest's to show
        String uid = createCommittedPublicMessage();

        byte[] body = given().queryParam("lang", "en")
                .when().get("/rest/message-reports/message/" + uid + ".pdf")
                .then().statusCode(200).contentType("application/pdf")
                .extract().asByteArray();
        assertTrue(new String(body, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }

    @Test
    void messagePdf_refusesAnAnonymousCallerThatNamesAnotherReport() {
        String uid = createCommittedPublicMessage();

        for (String report : List.of("draft", "public", "nm-report")) {
            given().queryParam("lang", "en").queryParam("report", report)
                    .when().get("/rest/message-reports/message/" + uid + ".pdf")
                    .then().statusCode(403);
        }
    }

    @Test
    void messagePdf_stillServesASignedInCallerTheReportItNames() {
        String uid = createCommittedPublicMessage();

        given().auth().oauth2(accessToken())
                .queryParam("lang", "en")
                .queryParam("report", "draft")
                .when().get("/rest/message-reports/message/" + uid + ".pdf")
                .then().statusCode(200).contentType("application/pdf");
    }

    @Test
    void messagePdf_ofADraftInAPublicTagIsRefusedToAnAnonymousCaller() {
        // The public host serves this endpoint to anyone. A PUBLIC tag shares a draft with staff; it must
        // not turn the draft into an official-looking PDF for an anonymous reader.
        // FAILS on the pre-fix code (the PUBLIC tag admitted the anonymous caller: 200 and a %PDF).
        String uid = createCommittedDraftInPublicTag();

        given().queryParam("lang", "en")
                .when().get("/rest/message-reports/message/" + uid + ".pdf")
                .then().statusCode(403);
    }

    @Test
    void messagePdf_ofADraftInAPublicTagStillServesStaffOfAnotherDomain() {
        // niord-nm does not own the dma-nw series; the user role there and the PUBLIC tag let the
        // caller print the draft all the same
        String uid = createCommittedDraftInPublicTag();

        byte[] body = given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nm")
                .queryParam("lang", "en")
                .when().get("/rest/message-reports/message/" + uid + ".pdf")
                .then().statusCode(200).contentType("application/pdf")
                .extract().asByteArray();
        assertTrue(new String(body, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }

    @Test
    void listPdf_givesAnAnonymousCallerThePdfEvenWhenTheQueryAsksForTheHtml() {
        byte[] body = given()
                .queryParam("lang", "en")
                .queryParam("debug", "true")
                .queryParam("param:searchCriteria", "<script>alert(1)</script>")
                .queryParam("pageSize", "A4}</style><script>alert(1)</script>")
                .when().get(LIST_PDF)
                .then().statusCode(200).contentType("application/pdf")
                .extract().asByteArray();
        assertTrue(new String(body, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }

    @Test
    void listPdf_ofThePublicReportIsServedAnonymously() {
        given().queryParam("lang", "en").queryParam("report", "public")
                .when().get(LIST_PDF)
                .then().statusCode(200).contentType("application/pdf");
    }

    @Test
    void listPdf_refusesAnAnonymousCallerThatNamesAnotherReport() {
        // Which report an anonymous list without one gets is MessageReportRestServiceTest's to show
        for (String report : List.of("standard", "nm-report")) {
            given().queryParam("lang", "en").queryParam("report", report)
                    .when().get(LIST_PDF)
                    .then().statusCode(403);
        }
    }

    @Test
    void listPdf_stillServesASignedInCallerTheReportItNames() {
        given().auth().oauth2(accessToken())
                .queryParam("lang", "en")
                .queryParam("report", "standard")
                .when().get(LIST_PDF)
                .then().statusCode(200).contentType("application/pdf");
    }

    @Test
    void listPdf_stillServesTheDebugHtmlToASignedInCaller_withTheCriteriaAsText() {
        // No NiordDomain header: the search runs unscoped, and its criteria name the requested series
        given().auth().oauth2(accessToken())
                .queryParam("lang", "en")
                .queryParam("debug", "true")
                .queryParam("messageSeries", "<b>IT</b>")
                .when().get(LIST_PDF)
                .then().statusCode(200).contentType(containsString("text/html"))
                .header("Content-Security-Policy", "sandbox")
                .body(containsString("&lt;b&gt;IT&lt;/b&gt;"))
                .body(not(containsString("<b>IT</b>")));
    }

    @Test
    void listPdf_givesASignedInCallerTheParamValuesAsText_andKeepsTheCheckedPageLayout() {
        // nm-tp-report prints its week parameter; the page layout is written into a style sheet
        String body = given().auth().oauth2(accessToken())
                .queryParam("lang", "en")
                .queryParam("debug", "true")
                .queryParam("report", "nm-tp-report")
                .queryParam("param:week", "<b>IT-WEEK</b>")
                .queryParam("param:pageSize", "A3; background: url(http://ignored.invalid/x.png)")
                .queryParam("param:pageOrientation", "landscape")
                .when().get(LIST_PDF)
                .then().statusCode(200).contentType(containsString("text/html"))
                .header("Content-Security-Policy", "sandbox")
                .extract().asString();

        assertTrue(body.contains("&lt;b&gt;IT-WEEK&lt;/b&gt;"), "the param: value is printed as text");
        assertTrue(!body.contains("<b>IT-WEEK</b>"), "the param: value is not markup");
        assertTrue(body.contains("size: A4 portrait"), "the page layout the endpoint checked is kept");
        assertTrue(!body.contains("ignored.invalid"), "a param: value cannot replace the page layout");
    }

    /**
     * MAIL11: a list without {@code lang} is printed in the default language. The templates print in one
     * language, and the endpoint used to hand them none, so a list of one message or more failed with a 500.
     */
    @Test
    void aListReportWithoutLangAnswers200() {
        String uid = createCommittedPublicMessage();
        String title = Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getDesc("en").getTitle());

        for (String lang : new String[] { null, "" }) {
            RequestSpecification request = given().auth().oauth2(accessToken())
                    .queryParam("debug", "true")
                    .queryParam("report", "standard")
                    .queryParam("messageId", uid);
            if (lang != null) {
                request.queryParam("lang", lang);
            }
            String html = request.when().get(LIST_PDF)
                    .then().statusCode(200).contentType(containsString("text/html"))
                    .extract().asString();
            assertTrue(html.contains(title), "the list holds the message (lang " + lang + "): " + html);
        }

        // The public site's reader, who gets the PDF in the public report
        byte[] body = given().queryParam("messageId", uid)
                .when().get(LIST_PDF)
                .then().statusCode(200).contentType("application/pdf")
                .extract().asByteArray();
        assertTrue(new String(body, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }

    /**
     * MAIL11, the one-message PDF: without {@code lang} the message is printed in the default language. The
     * endpoint handed the templates no language, and FreeMarker stopped on the missing 'language' with a 500,
     * for a signed-in caller and for the public site's anonymous reader alike.
     */
    @Test
    void aMessagePdfWithoutLangAnswers200() {
        String uid = createCommittedPublicMessage();
        String title = Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getDesc("en").getTitle());

        for (String lang : new String[] { null, "" }) {
            RequestSpecification request = given().auth().oauth2(accessToken())
                    .queryParam("debug", "true");
            if (lang != null) {
                request.queryParam("lang", lang);
            }
            String html = request.when().get("/rest/message-reports/message/" + uid + ".pdf")
                    .then().statusCode(200).contentType(containsString("text/html"))
                    .extract().asString();
            assertTrue(html.contains(title), "the page holds the message (lang " + lang + "): " + html);
        }

        // The public site's reader
        byte[] body = given()
                .when().get("/rest/message-reports/message/" + uid + ".pdf")
                .then().statusCode(200).contentType("application/pdf")
                .extract().asByteArray();
        assertTrue(new String(body, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF"));
    }

    @Test
    void getReports_offersThePublicReportToAnonymousCallers() {
        given().when().get(REPORTS)
                .then().statusCode(200)
                .body("reportId", hasItem("public"));
    }
}
