/*
 * Copyright 2016 Danish Maritime Authority.
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

import org.springframework.transaction.annotation.Transactional;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import io.restassured.http.ContentType;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.mail.ScheduledMailRecipient;
import org.niord.core.mail.ScheduledMailService;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.niord.test.ITTest;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ScheduledMailRestService}: the HTTP contract of the
 * {@code /rest/scheduled-mails} endpoints: the queue's search and by-id read, which are
 * {@code @RolesAllowed(SYSADMIN)}, and the user's own mails ({@code /mine}), which is
 * {@code @RolesAllowed(USER)}.
 *
 * <p>Security oracle: any call without a token is rejected with HTTP 401. A bare {@code sysadmin}
 * token (obtained from the Keycloak test container via the inherited {@code accessToken()} helper)
 * satisfies the {@code SYSADMIN} role with no {@code NiordDomain} header; the {@code user} role of
 * {@code /mine} comes with the {@code NiordDomain: niord-nw} header.
 *
 * <p>The wiring test ({@link #searchAndGetByIdReturnCommittedMail()}) needs COMMITTED data: an HTTP
 * request runs in the server's own transaction, which {@code @Transactional} cannot roll back, and
 * the row must already be visible to that separate request transaction. The {@link ScheduledMail} is
 * therefore inserted through {@code Tx.requiringNew()} (which commits) and removed
 * again in {@link #cleanup()} — recipients first, then the mail — using the same pattern. Every test
 * uses a globally-unique subject token so it only ever asserts on rows it created and the tests stay
 * independent of any seed data and of each other.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class ScheduledMailRestServiceITTest extends ITTest {

    /** An ID that is never assigned by the database sequence, so the by-id read must 404. */
    private static final int UNKNOWN_MAIL_ID = 999999999;

    @PersistenceContext
    EntityManager em;

    /** The domain whose client grants the {@code user} role the {@code /mine} read asks for. */
    private static final String DOMAIN = "niord-nw";

    @Autowired
    UserService userService;

    @Autowired
    SettingsService settingsService;

    /** The mail filter, LOG in the shared test application */
    private static final String VALID_RECIPIENTS = "mailValidRecipients";

    /** IDs of the committed ScheduledMail rows to remove in cleanup. */
    private final List<Integer> createdMailIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Integer createdMailId : createdMailIds) {
            try {
                final Integer id = createdMailId;
                Tx.requiringNew().run(() -> {
                    // Remove recipients first to avoid the FK constraint (see deleteExpiredMails note
                    // in ScheduledMailService) then remove the owning mail.
                    em.createQuery("delete from ScheduledMailRecipient r where r.mail.id = :id")
                            .setParameter("id", id)
                            .executeUpdate();
                    ScheduledMail m = em.find(ScheduledMail.class, id);
                    if (m != null) {
                        em.remove(m);
                    }
                });
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdMailIds.clear();
    }

    /** A globally-unique subject token so a test asserts only on its own committed row. */
    private String uniqueSubject() {
        return "IT-REST-ScheduledMail-" + System.nanoTime();
    }

    /**
     * Inserts a committed PENDING ScheduledMail with the given subject, a TO recipient and the given
     * HTML contents, and records its id for cleanup. Returns the assigned id.
     */
    private int insertCommittedMail(String subject, String htmlContents) {
        return insertCommittedMail(subject, htmlContents, null, null);
    }

    /**
     * Inserts a committed PENDING ScheduledMail as above, with the given sender and, when not null, the given
     * creation date ({@code @PrePersist} fills only a missing one). Returns the assigned id.
     */
    private int insertCommittedMail(String subject, String htmlContents, String sender, Date created) {
        int id = Tx.requiringNew().call(() -> {
            ScheduledMail mail = new ScheduledMail();
            mail.setSubject(subject);
            mail.setStatus(ScheduledMail.Status.PENDING);
            mail.setSender(sender);
            mail.setCreated(created);
            mail.setHtmlContents(htmlContents); // throws IOException — Callable allows it
            mail.addRecipient(new ScheduledMailRecipient(
                    ScheduledMailRecipient.RecipientType.TO, "recipient@example.com"));
            em.persist(mail);
            em.flush();
            return mail.getId();
        });
        createdMailIds.add(id);
        return id;
    }

    /** A date the given number of days before now */
    private static Date daysAgo(int days) {
        return new Date(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000);
    }

    /**
     * The sender address the mail service records for the given test user: its mirror row's computed address.
     * One authenticated call first, since the first call of a user creates the row.
     */
    private String senderAddressOf(String role, String username) {
        given().auth().oauth2(accessToken(role)).header("NiordDomain", DOMAIN)
                .when().get("/rest/scheduled-mails/mine")
                .then().statusCode(200);
        String address = Tx.requiringNew().call(() -> {
            User user = userService.findByUsername(username);
            return user == null ? null : user.computeInternetAddress();
        });
        assertNotNull(address, "the mirror row of " + username + " has a sender address");
        return address;
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — no token returns 401 on both SYSADMIN endpoints
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/scheduled-mails/search")
                .then().statusCode(401);
    }

    @Test
    void mineWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/scheduled-mails/mine")
                .then().statusCode(401);
    }

    @Test
    void getScheduledMailWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/scheduled-mails/scheduled-mail/1")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Positive role-based — sysadmin token (no NiordDomain header needed for SYSADMIN)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchWithTokenReturnsPagedResultShape() {
        // PagedSearchResultVo: a non-negative "total" count and a "data" array (possibly empty).
        given().auth().oauth2(accessToken())
                .when().get("/rest/scheduled-mails/search")
                .then().statusCode(200)
                .body("total", greaterThanOrEqualTo(0))
                .body("data", notNullValue())
                .body("data", is(org.hamcrest.Matchers.instanceOf(java.util.List.class)));
    }

    @Test
    void getScheduledMailWithUnknownIdReturns404() {
        // getScheduledMail() throws new NiordWebException(404) when the mail is not found.
        given().auth().oauth2(accessToken())
                .when().get("/rest/scheduled-mails/scheduled-mail/" + UNKNOWN_MAIL_ID)
                .then().statusCode(404);
    }

    // ------------------------------------------------------------------------------------------- //
    // Committed-row wiring — search by subject + by-id read (search omits DETAILS, by-id includes it)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchAndGetByIdReturnCommittedMail() {
        String subject = uniqueSubject();
        String html = "<html><body>Notice for " + subject + "</body></html>";
        int id = insertCommittedMail(subject, html);

        // Search by the unique subject: exactly the one committed row matches.
        given().auth().oauth2(accessToken())
                .queryParam("subject", subject)
                .when().get("/rest/scheduled-mails/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data.size()", is(1))
                .body("data[0].id", is(id))
                .body("data[0].subject", is(subject))
                .body("data[0].status", is("PENDING"))
                .body("data[0].recipients[0].address", is("recipient@example.com"))
                .body("data[0].recipients[0].recipientType", is("TO"));

        // By-id read includes DETAILS, so the (decompressed) HTML contents are present.
        given().auth().oauth2(accessToken())
                .when().get("/rest/scheduled-mails/scheduled-mail/" + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("subject", is(subject))
                .body("status", is("PENDING"))
                .body("contents", is(html))
                .body("recipients[0].address", is("recipient@example.com"));
    }

    @Test
    void searchOmitsContentsButByIdReadIncludesThem() {
        // The list/search projection uses DataFilter.get() (no DETAILS), so "contents" is null in the
        // search result, while the by-id read uses DataFilter.DETAILS and exposes "contents".
        String subject = uniqueSubject();
        String html = "<html><body>" + subject + " body</body></html>";
        int id = insertCommittedMail(subject, html);

        given().auth().oauth2(accessToken())
                .queryParam("subject", subject)
                .when().get("/rest/scheduled-mails/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].id", is(id))
                .body("data[0].contents", nullValue());

        given().auth().oauth2(accessToken())
                .when().get("/rest/scheduled-mails/scheduled-mail/" + id)
                .then().statusCode(200)
                .body("contents", is(html));
    }

    @Test
    void searchByStatusFiltersOutNonMatchingMail() {
        // A PENDING mail must not appear when filtering for SENT, but must appear for PENDING.
        String subject = uniqueSubject();
        int id = insertCommittedMail(subject, "<html>x</html>");

        // status=SENT excludes our PENDING row (it may match other rows, so assert on our id only).
        given().auth().oauth2(accessToken())
                .queryParam("subject", subject)
                .queryParam("status", "SENT")
                .when().get("/rest/scheduled-mails/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(id)));

        // status=PENDING includes it.
        given().auth().oauth2(accessToken())
                .queryParam("subject", subject)
                .queryParam("status", "PENDING")
                .when().get("/rest/scheduled-mails/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].id", is(id));
    }

    @Test
    void searchBySubstringOfSubjectMatchesViaLike() {
        // ScheduledMailService matches subject via a LIKE, so a unique substring of the subject still
        // resolves to exactly the committed row.
        String subject = uniqueSubject();
        int id = insertCommittedMail(subject, "<html>like</html>");

        // Strip the constant prefix to use the unique nanoTime suffix as the search term.
        String suffix = subject.substring("IT-REST-ScheduledMail-".length());

        given().auth().oauth2(accessToken())
                .queryParam("subject", suffix)
                .when().get("/rest/scheduled-mails/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].id", is(id))
                .body("data[0].subject", is(subject));
    }

    // ------------------------------------------------------------------------------------------- //
    // The user's own mails — matched on the sender address, within the days asked for (clamped)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void mineReturnsTheCallersOwnMailsOfThePeriodOnly() {
        String editor = senderAddressOf("editor", "testeditor");
        String subject = uniqueSubject();
        int recent = insertCommittedMail(subject + "-recent", "<html>recent</html>", editor, null);
        int tenDaysOld = insertCommittedMail(subject + "-ten", "<html>ten</html>", editor, daysAgo(10));
        int fortyDaysOld = insertCommittedMail(subject + "-forty", "<html>forty</html>", editor, daysAgo(40));
        int foreign = insertCommittedMail(subject + "-foreign", "<html>foreign</html>",
                "someone-else-" + subject + "@example.org", null);

        // The default period is 7 days: the recent mail only, without its text
        given().auth().oauth2(accessToken("editor")).header("NiordDomain", DOMAIN)
                .when().get("/rest/scheduled-mails/mine")
                .then().statusCode(200)
                .body("id", hasItem(recent))
                .body("id", not(hasItem(tenDaysOld)))
                .body("id", not(hasItem(fortyDaysOld)))
                .body("id", not(hasItem(foreign)))
                .body("find { it.id == " + recent + " }.subject", is(subject + "-recent"))
                .body("find { it.id == " + recent + " }.recipients[0].address", is("recipient@example.com"))
                .body("find { it.id == " + recent + " }.contents", nullValue())
                .body("sender", everyItem(is(editor)));

        // 30 days takes in the mail of ten days ago
        given().auth().oauth2(accessToken("editor")).header("NiordDomain", DOMAIN)
                .queryParam("days", 30)
                .when().get("/rest/scheduled-mails/mine")
                .then().statusCode(200)
                .body("id", hasItems(recent, tenDaysOld))
                .body("id", not(hasItem(fortyDaysOld)));

        // A period above 30 days is clamped to 30, one below a day to a day
        given().auth().oauth2(accessToken("editor")).header("NiordDomain", DOMAIN)
                .queryParam("days", 1000)
                .when().get("/rest/scheduled-mails/mine")
                .then().statusCode(200)
                .body("id", hasItems(recent, tenDaysOld))
                .body("id", not(hasItem(fortyDaysOld)));
        given().auth().oauth2(accessToken("editor")).header("NiordDomain", DOMAIN)
                .queryParam("days", 0)
                .when().get("/rest/scheduled-mails/mine")
                .then().statusCode(200)
                .body("id", hasItem(recent))
                .body("id", not(hasItem(tenDaysOld)));

        // Another user sees none of them
        given().auth().oauth2(accessToken("user")).header("NiordDomain", DOMAIN)
                .queryParam("days", 30)
                .when().get("/rest/scheduled-mails/mine")
                .then().statusCode(200)
                .body("id", not(hasItem(recent)))
                .body("id", not(hasItem(tenDaysOld)))
                .body("id", not(hasItem(foreign)));
    }

    @Test
    void mineShowsTheStatusAttemptsAndLastErrorOfAFailedMail() {
        String editor = senderAddressOf("editor", "testeditor");
        // Written as ERROR at once, so the minute sender never takes it up
        int id = Tx.requiringNew().call(() -> {
            ScheduledMail mail = new ScheduledMail();
            mail.setSubject(uniqueSubject());
            mail.setSender(editor);
            mail.setStatus(ScheduledMail.Status.ERROR);
            mail.setAttempts(5);
            mail.setLastError("Connection refused");
            mail.setHtmlContents("<html>failed</html>");
            mail.addRecipient(new ScheduledMailRecipient(
                    ScheduledMailRecipient.RecipientType.TO, "recipient@example.com"));
            em.persist(mail);
            em.flush();
            return mail.getId();
        });
        createdMailIds.add(id);

        given().auth().oauth2(accessToken("editor")).header("NiordDomain", DOMAIN)
                .when().get("/rest/scheduled-mails/mine")
                .then().statusCode(200)
                .body("find { it.id == " + id + " }.status", is("ERROR"))
                .body("find { it.id == " + id + " }.attempts", is(5))
                .body("find { it.id == " + id + " }.lastError", is("Connection refused"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Held mails, and Send again
    // ------------------------------------------------------------------------------------------- //

    /** Inserts a committed mail with the given status and last Send again, and records its id for cleanup */
    private int insertCommittedMail(String subject, ScheduledMail.Status status, String sender, Date sentAgainAt,
                                    String sentAgainBy) {
        int id = Tx.requiringNew().call(() -> {
            ScheduledMail mail = new ScheduledMail();
            mail.setSubject(subject);
            mail.setStatus(status);
            mail.setAttempts(1);
            mail.setSender(sender);
            mail.setSentAgainAt(sentAgainAt);
            mail.setSentAgainBy(sentAgainBy);
            mail.setHtmlContents("<html>" + subject + "</html>");
            mail.addRecipient(new ScheduledMailRecipient(
                    ScheduledMailRecipient.RecipientType.TO, "recipient@example.com"));
            em.persist(mail);
            em.flush();
            return mail.getId();
        });
        createdMailIds.add(id);
        return id;
    }

    /** A Send again request of one mail, as the page sends it */
    private static Map<String, Object> sendAgainBody(Object id, Object status) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", id);
        item.put("status", status);
        item.put("sentAgainAt", null);
        return Map.of("mails", List.of(item));
    }

    @Test
    void searchByStatusHeldFindsAHeldMail() {
        String subject = uniqueSubject();
        int id = insertCommittedMail(subject, ScheduledMail.Status.HELD, null, null, null);

        given().auth().oauth2(accessToken())
                .queryParam("subject", subject)
                .queryParam("status", "HELD")
                .when().get("/rest/scheduled-mails/search")
                .then().statusCode(200)
                .body("total", is(1))
                .body("data[0].id", is(id))
                .body("data[0].status", is("HELD"));
        given().auth().oauth2(accessToken())
                .queryParam("subject", subject)
                .queryParam("status", "SENT")
                .when().get("/rest/scheduled-mails/search")
                .then().statusCode(200)
                .body("data.id", not(hasItem(id)));
    }

    @Test
    void mineShowsAHeldMailWithoutTheNameOfWhoSentItAgain() {
        String editor = senderAddressOf("editor", "testeditor");
        Date sentAgainAt = new Date(System.currentTimeMillis() - 60_000L);
        int id = insertCommittedMail(uniqueSubject(), ScheduledMail.Status.HELD, editor, sentAgainAt, "sysadmin");

        String mine = given().auth().oauth2(accessToken("editor")).header("NiordDomain", DOMAIN)
                .when().get("/rest/scheduled-mails/mine")
                .then().statusCode(200)
                .body("find { it.id == " + id + " }.status", is("HELD"))
                .body("find { it.id == " + id + " }.sentAgainBy", nullValue())
                .extract().path("find { it.id == " + id + " }.sentAgainAt");
        // A date is written as ISO-8601 text, as the mail's other dates are
        assertEquals(sentAgainAt.getTime(), OffsetDateTime.parse(mine).toInstant().toEpochMilli());

        // The Mail Queue shows the system administrator who sent it again
        String queue = given().auth().oauth2(accessToken())
                .when().get("/rest/scheduled-mails/scheduled-mail/" + id)
                .then().statusCode(200)
                .body("sentAgainBy", is("sysadmin"))
                .extract().path("sentAgainAt");
        assertEquals(sentAgainAt.getTime(), OffsetDateTime.parse(queue).toInstant().toEpochMilli());
    }

    @Test
    void sendAgainIsForASystemAdministratorOnly() {
        int id = insertCommittedMail(uniqueSubject(), ScheduledMail.Status.HELD, null, null, null);

        given().contentType(ContentType.JSON).body(sendAgainBody(id, "HELD"))
                .when().post("/rest/scheduled-mails/send-again")
                .then().statusCode(401);
        for (String role : List.of("user", "editor", "admin")) {
            given().auth().oauth2(accessToken(role)).header("NiordDomain", DOMAIN)
                    .contentType(ContentType.JSON).body(sendAgainBody(id, "HELD"))
                    .when().post("/rest/scheduled-mails/send-again")
                    .then().statusCode(403);
        }
        Tx.requiringNew().run(() -> assertNull(em.find(ScheduledMail.class, id).getSentAgainAt()));
    }

    @Test
    void sendAgainRefusesARequestThatIsNotWellFormed() {
        int id = insertCommittedMail(uniqueSubject(), ScheduledMail.Status.HELD, null, null, null);

        sendAgainAsSysadmin(Map.of("mails", List.of()), 400, "No mail to send again");
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i <= ScheduledMailService.SEND_AGAIN_MAX; i++) {
            tooMany.add(Map.of("id", UNKNOWN_MAIL_ID - i, "status", "HELD"));
        }
        sendAgainAsSysadmin(Map.of("mails", tooMany), 400, "At most 500 mails can be sent again at a time");
        sendAgainAsSysadmin(sendAgainBody(id, null), 400, "needs its id and its status");
        sendAgainAsSysadmin(sendAgainBody(null, "HELD"), 400, "needs its id and its status");
        sendAgainAsSysadmin(sendAgainBody(id, "PENDING"), 400, "Only a held, sent or failed mail can be sent again");
        sendAgainAsSysadmin(Map.of("mails", List.of(Map.of("id", id, "status", "HELD"),
                Map.of("id", id, "status", "SENT"))), 400, "is named twice");
        Tx.requiringNew().run(() -> assertNull(em.find(ScheduledMail.class, id).getSentAgainAt()));
    }

    @Test
    void sendAgainIsRefusedUnderTheLogFilter() {
        int id = insertCommittedMail(uniqueSubject(), ScheduledMail.Status.HELD, null, null, null);

        sendAgainAsSysadmin(sendAgainBody(id, "HELD"), 409, "is LOG, so the mails would only be held again");
        Tx.requiringNew().run(() -> {
            ScheduledMail mail = em.find(ScheduledMail.class, id);
            assertEquals(ScheduledMail.Status.HELD, mail.getStatus());
            assertNull(mail.getSentAgainAt());
        });
    }

    /**
     * With the filter set for the test, as LiveSettingReadsITTest sets it, a held mail is queued again, and so is a
     * sent mail sent again before, named with the date the search answered with (ISO-8601 text) or in epoch
     * milliseconds; a mail that is gone is refused.
     */
    @Test
    void sendAgainQueuesAHeldMailWhenTheFilterIsOpen() {
        String subject = uniqueSubject();
        Date earlier = new Date(System.currentTimeMillis() - 10 * 60_000L);
        int held = insertCommittedMail(subject + "-held", ScheduledMail.Status.HELD, null, null, null);
        int sentAsText = insertCommittedMail(subject + "-text", ScheduledMail.Status.SENT, null, earlier, "sysadmin");
        int sentAsMillis = insertCommittedMail(subject + "-millis", ScheduledMail.Status.SENT, null, earlier,
                "sysadmin");
        int gone = UNKNOWN_MAIL_ID;
        String shown = given().auth().oauth2(accessToken())
                .queryParam("subject", subject + "-text")
                .when().get("/rest/scheduled-mails/search")
                .then().statusCode(200)
                .extract().path("data[0].sentAgainAt");

        Object original = Tx.requiringNew().call(() -> em.find(Setting.class, VALID_RECIPIENTS).getValue());
        try {
            settingsService.set(VALID_RECIPIENTS, "ALL");

            given().auth().oauth2(accessToken())
                    .contentType(ContentType.JSON)
                    .body(Map.of("mails", List.of(
                            Map.of("id", held, "status", "HELD"),
                            Map.of("id", sentAsText, "status", "SENT", "sentAgainAt", shown),
                            Map.of("id", sentAsMillis, "status", "SENT", "sentAgainAt", earlier.getTime()),
                            Map.of("id", gone, "status", "SENT"))))
                    .when().post("/rest/scheduled-mails/send-again")
                    .then().statusCode(200)
                    .body("queued", is(List.of(held, sentAsText, sentAsMillis)))
                    .body("refused.size()", is(1))
                    .body("refused[0].id", is(gone))
                    .body("refused[0].reason", is("GONE"));
        } finally {
            settingsService.set(VALID_RECIPIENTS, original);
            settingsService.evictFromCache(VALID_RECIPIENTS);
        }

        // The minute sender may have taken them up since, so their status is not asserted; who and when stay
        Tx.requiringNew().run(() -> {
            for (int id : List.of(held, sentAsText, sentAsMillis)) {
                ScheduledMail mail = em.find(ScheduledMail.class, id);
                assertEquals("sysadmin", mail.getSentAgainBy());
                assertTrue(mail.getSentAgainAt().after(earlier), "sent again now");
            }
        });
    }

    private void sendAgainAsSysadmin(Object body, int status, String detail) {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON).body(body)
                .when().post("/rest/scheduled-mails/send-again")
                .then().statusCode(status)
                .body("detail", containsString(detail));
    }
}
