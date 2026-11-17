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
import org.niord.core.mail.ScheduledMail;
import org.niord.core.mail.ScheduledMailRecipient;
import org.niord.test.ITTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for {@link ScheduledMailRestService}: the HTTP contract of the two
 * {@code /rest/scheduled-mails} endpoints, both of which are {@code @RolesAllowed(SYSADMIN)}.
 *
 * <p>Security oracle: any call without a token is rejected with HTTP 401. A bare {@code sysadmin}
 * token (obtained from the Keycloak test container via the inherited {@code accessToken()} helper)
 * satisfies the {@code SYSADMIN} role with no {@code NiordDomain} header.
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

    /** ID of a committed ScheduledMail row to remove in cleanup, or null if none was created. */
    private Integer createdMailId;

    @AfterEach
    void cleanup() {
        if (createdMailId != null) {
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
            createdMailId = null;
        }
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
        int id = Tx.requiringNew().call(() -> {
            ScheduledMail mail = new ScheduledMail();
            mail.setSubject(subject);
            mail.setStatus(ScheduledMail.Status.PENDING);
            mail.setHtmlContents(htmlContents); // throws IOException — Callable allows it
            mail.addRecipient(new ScheduledMailRecipient(
                    ScheduledMailRecipient.RecipientType.TO, "recipient@example.com"));
            em.persist(mail);
            em.flush();
            return mail.getId();
        });
        createdMailId = id;
        return id;
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
}
