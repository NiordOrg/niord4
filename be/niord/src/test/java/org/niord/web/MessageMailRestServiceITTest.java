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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.message.Message;
import org.niord.core.message.MessageHistory;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageService;
import org.niord.model.message.Status;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.MessageTestFactory;
import org.niord.test.Tx;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MessageMailRestService}: the HTTP contract and authorization of
 * {@code POST /rest/message-mail/send} (the recipients, the subject and the text in a JSON body) and of the
 * deprecated {@code GET /rest/message-mail/send} (the same in the query), which generate and schedule a
 * message e-mail for a message search result. Both read the search parameters from the query.
 *
 * <p><b>Authorization model under test.</b> Both are annotated {@code @RolesAllowed(EDITOR)}
 * (see {@link org.niord.core.user.Roles}). The role hierarchy is {@code sysadmin > admin > editor > user}.
 * The container therefore rejects an anonymous caller with {@code 401}, and an authenticated identity one
 * tier below editor (a plain {@code user}) with {@code 403}. {@code editor}, {@code admin} and
 * {@code sysadmin} all carry the {@code editor} role through the role chain and are admitted. Every
 * authenticated request carries {@code NiordDomain: niord-nw} so the domain augmentor realizes the
 * full role chain (the only source of the {@code user} role); see {@link KeycloakTestTokens} for the
 * seeded identity matrix.
 *
 * <p><b>Request-contract oracle.</b> Every address is read before any search or send: no address, a blank
 * one or a malformed one answers {@code 400} naming the address, and no mail is scheduled (MAIL8).
 *
 * <p><b>Happy-path oracle.</b> Mail runs in LOG simulate mode (no real SMTP), so a valid {@code mailTo}
 * does not contact a mail server: the handler still performs the message search and returns a
 * {@code text/plain} body of one line per recipient of the form
 * {@code "Message mail scheduled to <mailTo> in <n> ms"}. The body therefore contains both the supplied
 * recipient address and the word {@code "scheduled"}. Because LOG mode creates no committed external
 * state, there is nothing to tear down and no DB state to assert.
 *
 * <p>The happy-path requests carry an explicit {@code lang} query parameter, exactly as the Niord
 * front-end always does when invoking this endpoint. The handler renders the production Freemarker mail
 * template {@code /templates/messages/message-mail.ftl}, whose {@code renderMessage} macro defaults its
 * {@code lang} parameter to the top-level {@code language} data-model variable, which
 * {@code FmTemplateService} binds only when a language is supplied. A request without {@code lang} is
 * therefore written in the default language, which the endpoint supplies (MAIL11: it used to pass no
 * language, and a mail of one message or more failed with a 500).
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageMailRestServiceITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String SEND = "/rest/message-mail/send";

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    @Autowired
    NiordApp app;

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @PersistenceContext
    EntityManager em;

    /** UIDs of the messages a test committed; removed with their history in {@link #cleanup()}. */
    private final List<String> createdMessageUids = new ArrayList<>();

    /** The mails a test scheduled go to addresses holding this token; removed in {@link #cleanup()}. */
    private final String mailToken = "it-mail7-" + System.nanoTime();

    @AfterEach
    void cleanup() {
        try {
            Tx.requiringNew().run(() -> em
                    .createQuery("select r.mail from ScheduledMailRecipient r where r.address like :pattern",
                            ScheduledMail.class)
                    .setParameter("pattern", "%" + mailToken + "%")
                    .getResultList()
                    .forEach(em::remove));
        } catch (Exception ignored) {
            // best-effort cleanup
        }
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
     * Commits one published message on dma-nw, which the niord-nw domain shows, and tracks it for
     * {@link #cleanup()}. The status is set directly: the publish flow would also number it and hand it to
     * the status listeners, whose rows the mail never reads.
     */
    private String createCommittedPublishedMessage(String title) {
        String uid = Tx.requiringNew().call(() -> messageService
                .createMessage(MessageTestFactory.draftNw(title))
                .getUid());
        createdMessageUids.add(uid);
        Tx.requiringNew().run(() -> em
                .createQuery("update Message m set m.status = :status where m.uid = :uid")
                .setParameter("status", Status.PUBLISHED)
                .setParameter("uid", uid)
                .executeUpdate());
        return uid;
    }

    /** Returns the HTML of the one mail scheduled to the given address. */
    private String scheduledMailHtml(String address) {
        return Tx.requiringNew().call(() -> em
                .createQuery("select r.mail from ScheduledMailRecipient r where r.address = :address",
                        ScheduledMail.class)
                .setParameter("address", address)
                .getSingleResult()
                .getHtmlContents());
    }

    /**
     * Ensures the editor/admin {@code User} rows exist (committed) before the send tests run, so these
     * tests do not depend on which user rows earlier test classes happened to commit or roll back.
     *
     * <p>Historical context: the send flow calls {@code UserService.currentUser()} more than once per
     * request, and its get-or-create used to insert the same first-ever username twice in one
     * transaction, failing at commit with a duplicate key (HTTP 500) — observed on Linux CI where an
     * earlier test's rolled-back 403 path had discarded the first {@code testeditor} insert. That
     * defect is fixed (pinned by {@code UserServiceCurrentUserITTest}); the pre-warm stays as a plain
     * test-independence measure.
     */
    @BeforeEach
    void ensureCallerUserRowsExist() {
        given().auth().oauth2(accessToken("editor")).when().get("/rest/tickets/ticket").then().statusCode(200);
        given().auth().oauth2(accessToken("admin")).when().get("/rest/tickets/ticket").then().statusCode(200);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /send — @RolesAllowed(EDITOR). Anonymous -> 401; an authenticated plain user (one tier
    // below editor) -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void send_rejectsAnonymousWith401() {
        given().when().get(SEND).then().statusCode(401);
    }

    @Test
    void send_forbidsUserWith403() {
        // KEY adjacent-tier case: an authenticated 'user' lacks 'editor'.
        as(accessToken("user")).when().get(SEND).then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /send (admitted editor) with no mailTo -> the handler throws NiordWebException(400)
    // before performing any search or send.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void send_withoutMailToReturns400() {
        as(accessToken("editor")).queryParam("mailSubject", "IT")
                .when().get(SEND)
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /send (admitted editor) with a valid mailTo -> 200 + scheduling text echoing the recipient.
    // LOG simulate mode means no SMTP is contacted and no state is committed.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void send_asEditorWithMailToReturnsSchedulingText() {
        String body = as(accessToken("editor"))
                .queryParam("mailTo", "it-mail@example.com")
                .queryParam("mailSubject", "IT")
                .queryParam("lang", "en")
                .when().get(SEND)
                .then().statusCode(200)
                .extract().asString();

        org.hamcrest.MatcherAssert.assertThat(body, containsString("it-mail@example.com"));
        org.hamcrest.MatcherAssert.assertThat(body, containsString("scheduled"));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /send as admin: admin carries 'editor' through the role chain, so the EDITOR endpoint
    // admits it and returns the same scheduling text echoing the recipient.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void send_asAdminWithMailToReturnsSchedulingText() {
        String body = as(accessToken("admin"))
                .queryParam("mailTo", "admin-it@example.com")
                .queryParam("lang", "en")
                .when().get(SEND)
                .then().statusCode(200)
                .extract().asString();

        org.hamcrest.MatcherAssert.assertThat(body, containsString("admin-it@example.com"));
        org.hamcrest.MatcherAssert.assertThat(body, containsString("scheduled"));
    }

    // ------------------------------------------------------------------------------------------- //
    // MAIL11: without lang the mail is written in the default language, and a malformed search field
    // is the caller's error, answered 400 and not logged at ERROR.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMailWithoutLangIsWrittenInTheDefaultLanguage() {
        String title = "IT-MAIL11-" + System.nanoTime();
        String uid = createCommittedPublishedMessage(title);
        String withoutLang = "no-lang-" + mailToken + "@example.com";
        String blankLang = "blank-lang-" + mailToken + "@example.com";
        String defaultLang = "default-lang-" + mailToken + "@example.com";

        as(accessToken("editor"))
                .queryParam("messageId", uid)
                .queryParam("mailTo", withoutLang)
                .queryParam("mailSubject", "IT")
                .when().get(SEND)
                .then().statusCode(200)
                .body(containsString(withoutLang));
        as(accessToken("editor"))
                .queryParam("messageId", uid)
                .queryParam("mailTo", blankLang)
                .queryParam("mailSubject", "IT")
                .queryParam("lang", "")
                .when().get(SEND)
                .then().statusCode(200);
        as(accessToken("editor"))
                .queryParam("messageId", uid)
                .queryParam("mailTo", defaultLang)
                .queryParam("mailSubject", "IT")
                .queryParam("lang", app.getDefaultLanguage())
                .when().get(SEND)
                .then().statusCode(200);

        String html = scheduledMailHtml(withoutLang);
        assertTrue(html.contains(title), "the mail holds the message: " + html);
        // Each mail greets its own recipient; apart from that, the three are the same mail
        String expected = scheduledMailHtml(defaultLang).replace(defaultLang, "RECIPIENT");
        assertEquals(expected, html.replace(withoutLang, "RECIPIENT"));
        assertEquals(expected, scheduledMailHtml(blankLang).replace(blankLang, "RECIPIENT"));
    }

    @Test
    void aMalformedSearchFieldAnswers400WithoutAnErrorLine() {
        Logger logger = (Logger) LoggerFactory.getLogger(MessageMailRestService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            as(accessToken("editor"))
                    .queryParam("mailTo", "malformed-" + mailToken + "@example.com")
                    .queryParam("lang", "en")
                    .queryParam("status", "abc")
                    .when().get(SEND)
                    .then().statusCode(400);

            List<ILoggingEvent> errors = appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertEquals(List.of(), errors, "a client error is not logged at ERROR");
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * A free-text query the search cannot read (an unbalanced quote) is the caller's error: the mail answers 400,
     * schedules nothing and logs no ERROR line, as the list, the print and the export do.
     */
    @Test
    void anUnreadableFreeTextQueryAnswers400WithoutAnErrorLine_andNoMailIsScheduled() {
        Logger logger = (Logger) LoggerFactory.getLogger(MessageMailRestService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            post(accessToken("editor"), Map.of("mailTo", List.of("query-" + mailToken + "@example.com"),
                            "mailSubject", "IT"))
                    .queryParam("lang", "en")
                    .queryParam("query", "nw \"")
                    .when().post(SEND)
                    .then().statusCode(400);

            assertEquals(0, scheduledMailsTo(mailToken), "no mail is scheduled for a query that never ran");
            List<ILoggingEvent> errors = appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertEquals(List.of(), errors, "a client error is not logged at ERROR");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /send (MAIL12): the recipients, the subject and the text are the JSON body, so a long text
    // goes through and none of it is in the request line; the search parameters stay in the query.
    // ------------------------------------------------------------------------------------------- //

    /** A POST of the given body as JSON, as the given identity */
    private static RequestSpecification post(String token, Object body) {
        return as(token).contentType("application/json; charset=UTF-8").body(body);
    }

    /** The number of mails scheduled to an address holding the given text */
    private long scheduledMailsTo(String addressPart) {
        return Tx.requiringNew().call(() -> em
                .createQuery("select count(r) from ScheduledMailRecipient r where r.address like :pattern", Long.class)
                .setParameter("pattern", "%" + addressPart + "%")
                .getSingleResult());
    }

    @Test
    void post_rejectsAnonymousWith401() {
        given().contentType("application/json")
                .body(Map.of("mailTo", List.of("anonymous-" + mailToken + "@example.com")))
                .when().post(SEND).then().statusCode(401);
        assertEquals(0, scheduledMailsTo(mailToken));
    }

    @Test
    void post_forbidsUserWith403() {
        post(accessToken("user"), Map.of("mailTo", List.of("user-" + mailToken + "@example.com")))
                .when().post(SEND).then().statusCode(403);
        assertEquals(0, scheduledMailsTo(mailToken));
    }

    @Test
    void aMailSentByPostCarriesTheBodysRecipientsSubjectAndText_evenALongOne() {
        String title = "IT-MAIL12-" + System.nanoTime();
        String uid = createCommittedPublishedMessage(title);
        String first = "post-first-" + mailToken + "@example.com";
        String second = "Second Reader <post-second-" + mailToken + "@example.com>";
        String subject = "IT MAIL12 " + mailToken;
        // About 16,500 characters outside ASCII: as a GET query this was refused with 431. None of the letters has
        // an HTML 4 entity, so the mail holds them as they are
        String word = "łęść";
        int words = 3300;
        String text = "start-" + mailToken + " " + (word + " ").repeat(words) + "end-" + mailToken;

        String answer = post(accessToken("editor"), Map.of("mailTo", List.of(first, second),
                        "mailSubject", subject, "mailMessage", text))
                .queryParam("messageId", uid)
                .queryParam("lang", "en")
                .when().post(SEND)
                .then().statusCode(200)
                .extract().asString();

        org.hamcrest.MatcherAssert.assertThat(answer, containsString("Message mail scheduled to " + first));
        org.hamcrest.MatcherAssert.assertThat(answer, containsString("Message mail scheduled to " + second));
        for (String address : List.of("post-first-" + mailToken, "post-second-" + mailToken)) {
            List<String> subjectAndHtml = Tx.requiringNew().call(() -> {
                ScheduledMail mail = em
                        .createQuery("select r.mail from ScheduledMailRecipient r where r.address like :pattern",
                                ScheduledMail.class)
                        .setParameter("pattern", "%" + address + "%")
                        .getSingleResult();
                return List.of(mail.getSubject(), mail.getHtmlContents());
            });
            String html = subjectAndHtml.get(1);
            assertEquals(subject, subjectAndHtml.get(0), "the subject of the mail to " + address);
            assertTrue(html.contains(title), "the mail holds the message the query's search found: " + address);
            assertTrue(html.contains("start-" + mailToken) && html.contains("end-" + mailToken),
                    "the mail holds the whole text: " + address);
            assertEquals(words, html.split(word, -1).length - 1, "every word of the text is in the mail: " + address);
        }
    }

    @Test
    void aPostWithoutAnAddressAnswers400NamingWhy() {
        post(accessToken("editor"), Map.of("mailSubject", "IT"))
                .queryParam("lang", "en")
                .when().post(SEND)
                .then().statusCode(400)
                .body("detail", containsString("needs at least one address"));
        post(accessToken("editor"), Map.of("mailTo", List.of(), "mailSubject", "IT"))
                .queryParam("lang", "en")
                .when().post(SEND)
                .then().statusCode(400)
                .body("detail", containsString("needs at least one address"));
    }

    // ------------------------------------------------------------------------------------------- //
    // MAIL8: every address is read before any mail is scheduled; a malformed or blank one answers 400
    // naming it, nothing is scheduled to the addresses before it, and no ERROR line is logged.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMalformedAddressAnswers400NamingIt_andNoMailIsScheduled() {
        Logger logger = (Logger) LoggerFactory.getLogger(MessageMailRestService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String good = "good-" + mailToken + "@example.com";
            String noDomain = "no-domain-" + mailToken;
            String twoAts = "two@ats-" + mailToken + "@example.com";

            // POST: the bad address after a good one, and a blank one
            post(accessToken("editor"), Map.of("mailTo", List.of(good, noDomain), "mailSubject", "IT"))
                    .queryParam("lang", "en")
                    .when().post(SEND)
                    .then().statusCode(400)
                    .body("detail", containsString("'" + noDomain + "' is not a mail address"));
            post(accessToken("editor"), Map.of("mailTo", List.of(good, twoAts), "mailSubject", "IT"))
                    .queryParam("lang", "en")
                    .when().post(SEND)
                    .then().statusCode(400)
                    .body("detail", containsString("'" + twoAts + "' is not a mail address"));
            post(accessToken("editor"), Map.of("mailTo", List.of(good, " "), "mailSubject", "IT"))
                    .queryParam("lang", "en")
                    .when().post(SEND)
                    .then().statusCode(400)
                    .body("detail", containsString("blank address"));

            // GET: the same check, so the deprecated form no longer mails the addresses before the bad one
            as(accessToken("editor"))
                    .queryParam("mailTo", good)
                    .queryParam("mailTo", noDomain)
                    .queryParam("mailSubject", "IT")
                    .queryParam("lang", "en")
                    .when().get(SEND)
                    .then().statusCode(400)
                    .body("detail", containsString("'" + noDomain + "' is not a mail address"));

            assertEquals(0, scheduledMailsTo(mailToken), "no mail is scheduled when one address is refused");
            List<ILoggingEvent> errors = appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertEquals(List.of(), errors, "a client error is not logged at ERROR");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void theGetFormStillSendsAndLogsThatItIsDeprecated() {
        Logger logger = (Logger) LoggerFactory.getLogger(MessageMailRestService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String to = "get-" + mailToken + "@example.com";
            as(accessToken("editor"))
                    .queryParam("mailTo", to)
                    .queryParam("mailSubject", "IT")
                    .queryParam("lang", "en")
                    .when().get(SEND)
                    .then().statusCode(200)
                    .body(containsString(to));
            assertEquals(1, scheduledMailsTo(to));
            assertEquals(1, appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("POST /rest/message-mail/send"))
                    .count(), "one deprecation line per GET call");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
