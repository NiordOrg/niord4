/*
 * Copyright 2017 Danish Maritime Authority.
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

package org.niord.core.message;

import org.springframework.transaction.annotation.Transactional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.InternetAddress;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.mail.Mail.MailRecipient;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.message.MessageMailService.MessageMailTemplate;
import org.niord.core.script.ScriptResourceService;
import org.niord.core.user.TicketService;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.niord.core.web.NiordWebException;
import org.niord.model.message.AttachmentVo;
import org.niord.model.message.AttachmentVo.AttachmentDisplayType;
import org.niord.model.message.MessageDescVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;
import org.niord.model.message.MessageVo;
import org.niord.test.ITTest;
import org.niord.test.ScriptResourceFixtures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MessageMailService#sendMessageMail(MessageMailTemplate)}, run against the
 * seeded MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>{@code sendMessageMail} renders a Freemarker template once per recipient and persists exactly one
 * {@link ScheduledMail} per recipient. Each persisted mail carries the rendered HTML body, the (defaulted)
 * subject, a single {@code TO} recipient, and a sender derived from the current user's
 * {@code computeInternetAddress()}.
 *
 * <p><b>Rendering oracle.</b> The test classpath carries a Freemarker template at
 * {@code templates/messages/it-message-mail.ftl}, added as a database row before each test
 * ({@link ScriptResourceFixtures}; since C4.2a a template is read from the database only), that echoes every
 * data value the service binds:
 * {@code IT-MESSAGE-MAIL lang=<lang> to=<mailTo> sender=<mailSender> count=<#messages>} followed by
 * {@code MESSAGE-BODY:<mailMessage>:END}. Asserting on those markers proves that real Freemarker
 * rendering and data binding actually happened. One test also drives the base product's template
 * {@code /templates/messages/message-mail.ftl}, a row of the fixture (the base product's bootstrap), to
 * cover the production rendering path.
 *
 * <p><b>Current-user seam.</b> {@code userService.currentUser()} returns {@code null} without Keycloak
 * auth, but falls back to a thread-local ticket. To make {@code currentUser()} resolve to a persisted
 * {@link User}, each authenticated test persists a user and resolves a one-time ticket via
 * {@link TicketService}; the ticket is cleared after every test.
 *
 * <p><b>Transaction model.</b> Every test is wrapped in {@link TestTransaction} so all writes (users and
 * scheduled mails) roll back and the shared seed database stays pristine. {@code sendMessageMail} is
 * {@code @Transactional} and joins the ambient test transaction, so persisted-but-uncommitted mails are
 * visible to the queries below. Unique subjects per test keep the assertions independent.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class MessageMailServiceITTest extends ITTest {

    private static final String IT_TEMPLATE = "templates/messages/it-message-mail.ftl";

    private static final String LINKS_TEMPLATE = "templates/messages/it-message-mail-links.ftl";

    @Autowired
    MessageMailService messageMailService;

    @Autowired
    NiordApp app;

    @Autowired
    UserService userService;

    @Autowired
    TicketService ticketService;

    @Autowired
    ScriptResourceService resourceService;

    @PersistenceContext
    EntityManager em;

    @BeforeEach
    void addTheTestTemplates() {
        ScriptResourceFixtures.ensure(resourceService, IT_TEMPLATE, LINKS_TEMPLATE);
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Persists a user with a unique username, a name of "Test User" and a derived email. */
    private User persistUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setFirstName("Test");
        u.setLastName("User");
        u.setEmail(username + "@example.com");
        em.persist(u);
        em.flush();
        return u;
    }

    /** Issues and resolves a one-time ticket so that {@code currentUser()} resolves to the given user. */
    private void loginAs(User u) {
        String ticket = ticketService.createTicket(null, u);   // createTicket(Domain, User, String...): null domain
        assertTrue(ticketService.resolveTicketForCurrentThread(ticket));
    }

    @AfterEach
    void clearTicket() {
        ticketService.removeTicketForCurrentThread();
    }

    /** Wraps an email string into a TO mail recipient backed by an InternetAddress. */
    private MailRecipient toRecipient(String email) throws Exception {
        return new MailRecipient(RecipientType.TO, new InternetAddress(email));
    }

    /** Queries all scheduled mails carrying the given subject in the current (test) transaction. */
    private List<ScheduledMail> mailsBySubject(String subject) {
        em.flush();
        em.clear();
        return em.createQuery("select m from ScheduledMail m where m.subject = :s", ScheduledMail.class)
                .setParameter("s", subject)
                .getResultList();
    }

    // ------------------------------------------------------------------------------------------- //
    // Tests
    // ------------------------------------------------------------------------------------------- //

    /**
     * The empty-recipient guard fires before the current user is dereferenced, so this throws even
     * without an authenticated user.
     */
    @Test
    @Transactional
    void noRecipientThrows() {
        MessageMailTemplate t = new MessageMailTemplate()
                .subject("MSG-MAIL-NORCPT-" + UUID.randomUUID())
                .mailMessage("body")
                .messages(Collections.emptyList())
                .recipients(Collections.emptyList())
                .language("en")
                .templatePath(IT_TEMPLATE);

        assertThrows(Exception.class, () -> messageMailService.sendMessageMail(t),
                "a template with no recipients must be rejected before any mail is built");
    }

    /**
     * Happy path: the template is rendered with the bound data and exactly one ScheduledMail is persisted,
     * carrying the rendered HTML body, one TO recipient and a sender derived from the current user.
     */
    @Test
    @Transactional
    void rendersTemplateAndPersistsScheduledMail() throws Exception {
        User user = persistUser("u-" + UUID.randomUUID());
        loginAs(user);

        String subject = "MSG-MAIL-1-" + UUID.randomUUID();
        MessageMailTemplate t = new MessageMailTemplate()
                .subject(subject)
                .mailMessage("Hello body")
                .messages(Collections.emptyList())
                .recipients(List.of(toRecipient("rcpt@example.com")))
                .language("en")
                .templatePath(IT_TEMPLATE);

        messageMailService.sendMessageMail(t);

        List<ScheduledMail> mails = mailsBySubject(subject);
        assertEquals(1, mails.size(), "exactly one ScheduledMail must be persisted for the single recipient");
        ScheduledMail mail = mails.get(0);

        String html = mail.getHtmlContents();
        assertTrue(html.contains("IT-MESSAGE-MAIL"),
                "the body must be the rendered IT Freemarker template");
        assertTrue(html.contains("rcpt@example.com"),
                "mailTo must bind to the recipient address when no personal name is set");
        assertTrue(html.contains("MESSAGE-BODY:Hello body:END"),
                "mailMessage must be bound into the rendered body");
        assertTrue(html.contains("count=0"),
                "an empty message list must render count=0");

        assertEquals("Test User <" + user.getEmail() + ">", mail.getSender(),
                "the sender must be the current user's computed internet address");
        assertEquals(1, mail.getRecipients().size(), "the mail must carry exactly one recipient");
        assertEquals("rcpt@example.com", mail.getRecipients().get(0).getAddress(),
                "the persisted recipient address must equal the recipient's address");
    }

    /** A blank/null subject is defaulted to "No subject" before persistence. */
    @Test
    @Transactional
    void blankSubjectDefaultsToNoSubject() throws Exception {
        User user = persistUser("u-" + UUID.randomUUID());
        loginAs(user);

        // Use a unique recipient so we can isolate this mail among any pre-existing "No subject" rows.
        String recipientEmail = "no-subj-" + UUID.randomUUID() + "@example.com";
        MessageMailTemplate t = new MessageMailTemplate()
                .subject(null)
                .mailMessage("body")
                .messages(Collections.emptyList())
                .recipients(List.of(toRecipient(recipientEmail)))
                .language("en")
                .templatePath(IT_TEMPLATE);

        messageMailService.sendMessageMail(t);

        em.flush();
        em.clear();
        List<ScheduledMail> mails = em.createQuery(
                        "select distinct m from ScheduledMail m join m.recipients r " +
                                "where r.address = :addr", ScheduledMail.class)
                .setParameter("addr", recipientEmail)
                .getResultList();
        assertEquals(1, mails.size(), "exactly one mail must be persisted for the unique recipient");
        assertEquals("No subject", mails.get(0).getSubject(),
                "a null subject must be defaulted to 'No subject'");
    }

    /** Two recipients yield two separate ScheduledMails, one per recipient. */
    @Test
    @Transactional
    void multipleRecipientsCreateOneMailEach() throws Exception {
        User user = persistUser("u-" + UUID.randomUUID());
        loginAs(user);

        String subject = "MSG-MAIL-MULTI-" + UUID.randomUUID();
        MessageMailTemplate t = new MessageMailTemplate()
                .subject(subject)
                .mailMessage("body")
                .messages(Collections.emptyList())
                .recipients(List.of(toRecipient("a@x.com"), toRecipient("b@x.com")))
                .language("en")
                .templatePath(IT_TEMPLATE);

        messageMailService.sendMessageMail(t);

        List<ScheduledMail> mails = mailsBySubject(subject);
        assertEquals(2, mails.size(), "two recipients must yield exactly two ScheduledMails");

        Set<String> addresses = new HashSet<>();
        for (ScheduledMail mail : mails) {
            assertEquals(1, mail.getRecipients().size(),
                    "each split mail must carry exactly one recipient");
            addresses.add(mail.getRecipients().get(0).getAddress());
        }
        assertEquals(Set.of("a@x.com", "b@x.com"), addresses,
                "the two mails must cover exactly the two distinct recipient addresses");
    }

    /**
     * When a recipient carries a personal name, that name (not the bare address) is bound as {@code mailTo}.
     */
    @Test
    @Transactional
    void recipientPersonalNameUsedForMailTo() throws Exception {
        User user = persistUser("u-" + UUID.randomUUID());
        loginAs(user);

        String subject = "MSG-MAIL-PERSONAL-" + UUID.randomUUID();
        MailRecipient r = new MailRecipient(RecipientType.TO,
                new InternetAddress("c@x.com", "Personal Person")); // throws UnsupportedEncodingException
        MessageMailTemplate t = new MessageMailTemplate()
                .subject(subject)
                .mailMessage("body")
                .messages(Collections.emptyList())
                .recipients(List.of(r))
                .language("en")
                .templatePath(IT_TEMPLATE);

        messageMailService.sendMessageMail(t);

        List<ScheduledMail> mails = mailsBySubject(subject);
        assertEquals(1, mails.size(), "exactly one mail must be persisted");
        String html = mails.get(0).getHtmlContents();
        assertTrue(html.contains("Personal Person"),
                "mailTo must bind to the recipient's personal name when present");
        // The stored recipient address is the full RFC-822 encoding of the address (Address.toString()),
        // i.e. it keeps the personal name as "Personal Person <c@x.com>".
        assertEquals("Personal Person <c@x.com>", mails.get(0).getRecipients().get(0).getAddress(),
                "the persisted recipient address must be the full encoded address");
    }

    /**
     * Drives the real production template to cover the production rendering path. With an empty message
     * list and the seeded "message"/"mail" dictionaries this renders to the production HTML document.
     */
    @Test
    @Transactional
    void realProductionTemplateRenders() throws Exception {
        User user = persistUser("u-" + UUID.randomUUID());
        loginAs(user);

        String subject = "MSG-MAIL-REAL-" + UUID.randomUUID();
        MessageMailTemplate t = new MessageMailTemplate()
                .subject(subject)
                .mailMessage("Production body")
                .messages(Collections.emptyList())
                .recipients(List.of(toRecipient("real@example.com")))
                .language("en")
                .templatePath("/templates/messages/message-mail.ftl");

        messageMailService.sendMessageMail(t);

        List<ScheduledMail> mails = mailsBySubject(subject);
        assertEquals(1, mails.size(), "exactly one mail must be persisted via the production template");
        String html = mails.get(0).getHtmlContents();
        assertTrue(html.contains("<html"),
                "the production template must render an HTML document");
        assertTrue(html.contains("Production body"),
                "the production template must include the mail message body");
    }

    /**
     * The production template prints the texts of a message unescaped. They are cleaned first: a script, an
     * event handler, a style, a class outside the message-HTML vocabulary, a foreign image and a {@code data:}
     * image in the details do not reach the mail; ordinary formatting, the position table's classes and the
     * application's own images do, and a root-relative link or image becomes absolute. FAILS on the pre-fix
     * code, which mailed the details as the editor wrote them.
     */
    @Test
    @Transactional
    void theTextsOfAMessageAreCleanedInTheMail() throws Exception {
        User user = persistUser("u-" + UUID.randomUUID());
        loginAs(user);
        String baseUri = StringUtils.stripEnd(app.getBaseUri(), "/");

        MessageVo message = new MessageVo();
        message.setId(UUID.randomUUID().toString());
        MessageDescVo desc = new MessageDescVo();
        desc.setLang("en");
        desc.setTitle("IT title <script>alert('it-title-script')</script>");
        message.setDescs(new ArrayList<>(List.of(desc)));
        MessagePartDescVo partDesc = new MessagePartDescVo();
        partDesc.setLang("en");
        partDesc.setDetails("<p>IT <strong>bold</strong></p><ul><li>it-item</li></ul>"
                + "<table><tbody><tr><td>it-cell</td></tr></tbody></table>"
                + "<p><a href=\"/rest/repo/file/messages/it/notice.pdf\">it-notice</a></p>"
                + "<table class=\"positions it-unknown\"><tbody><tr><td class=\"pos-col\">it-pos</td></tr></tbody></table>"
                + "<p><img src=\"/rest/repo/file/messages/it/it-own.png\" alt=\"it-own\"></p>"
                + "<p><img src=\"" + baseUri + "/rest/repo/file/messages/it/it-absolute.png\"></p>"
                + "<script>alert('it-script')</script>"
                + "<p onclick=\"alert('it-onclick')\" style=\"position:fixed\">it-clicked</p>"
                + "<img src=\"https://tracker.example.org/it.png\" onerror=\"alert('it-onerror')\">"
                + "<img src=\"data:image/png;base64,aXQtZGF0YQ==\">");
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        part.setDescs(new ArrayList<>(List.of(partDesc)));
        message.setParts(new ArrayList<>(List.of(part)));

        String subject = "MSG-MAIL-CLEAN-" + UUID.randomUUID();
        MessageMailTemplate t = new MessageMailTemplate()
                .subject(subject)
                .mailMessage("body")
                .messages(List.of(message))
                .recipients(List.of(toRecipient("clean@example.com")))
                .language("en")
                .templatePath("/templates/messages/message-mail.ftl");

        messageMailService.sendMessageMail(t);

        List<ScheduledMail> mails = mailsBySubject(subject);
        assertEquals(1, mails.size(), "exactly one mail must be persisted");
        String html = mails.get(0).getHtmlContents();
        assertTrue(html.contains("IT title"), "the title must be in the mail; body was: " + html);
        assertTrue(html.contains("<strong>bold</strong>"), html);
        assertTrue(html.contains("<li>it-item</li>"), html);
        assertTrue(html.contains("<td>it-cell</td>"), html);
        assertTrue(html.contains("<p>it-clicked</p>"), html);
        assertTrue(html.contains("href=\"" + baseUri + "/rest/repo/file/messages/it/notice.pdf\""),
                "a root-relative link must become absolute: " + html);
        assertTrue(html.contains("<table class=\"positions\"><tbody><tr><td class=\"pos-col\">it-pos</td>"),
                "the position table keeps its classes: " + html);
        assertTrue(html.contains("<img src=\"" + baseUri + "/rest/repo/file/messages/it/it-own.png\" alt=\"it-own\">"),
                "a root-relative image of the application stays, made absolute: " + html);
        assertTrue(html.contains("<img src=\"" + baseUri + "/rest/repo/file/messages/it/it-absolute.png\">"),
                "an absolute image of the application stays: " + html);
        assertFalse(html.contains("it-unknown"), html);
        assertFalse(html.contains("position:fixed"), html);
        assertFalse(html.contains("it-title-script"), html);
        assertFalse(html.contains("it-script"), html);
        assertFalse(html.contains("it-onclick"), html);
        assertFalse(html.contains("it-onerror"), html);
        assertFalse(html.contains("tracker.example.org"), html);
        assertFalse(html.contains("aXQtZGF0YQ"), html);
    }

    /**
     * The production template prints the recipient's name in the greeting and the sender's name in the regards,
     * unescaped. The recipient's personal name is what the sender typed (the mail dialog's address, a comment's
     * address), so it cannot add markup to the mail, and neither can the sender's name. FAILS on the pre-fix
     * code, which printed both as they were.
     */
    @Test
    @Transactional
    void aNameCannotAddMarkupToTheMail() throws Exception {
        User user = persistUser("u-" + UUID.randomUUID());
        user.setFirstName("<i>it-sender</i>");
        em.flush();
        loginAs(user);

        String subject = "MSG-MAIL-GREETING-" + UUID.randomUUID();
        MailRecipient r = new MailRecipient(RecipientType.TO, new InternetAddress("greeting@example.com",
                "<a href=\"https://phish.example.org/it\">it-greeting</a>"));
        MessageMailTemplate t = new MessageMailTemplate()
                .subject(subject)
                .mailMessage("body")
                .messages(Collections.emptyList())
                .recipients(List.of(r))
                .language("en")
                .templatePath("/templates/messages/message-mail.ftl");

        messageMailService.sendMessageMail(t);

        List<ScheduledMail> mails = mailsBySubject(subject);
        assertEquals(1, mails.size(), "exactly one mail must be persisted");
        String html = mails.get(0).getHtmlContents();
        assertTrue(html.contains("&lt;a href=&quot;https://phish.example.org/it&quot;&gt;it-greeting&lt;/a&gt;"),
                "the recipient's name is in the greeting as text: " + html);
        assertTrue(html.contains("&lt;i&gt;it-sender&lt;/i&gt;"), "the sender's name is in the mail as text: " + html);
        assertFalse(html.contains("<a href=\"https://phish.example.org"), html);
        assertFalse(html.contains("<i>it-sender</i>"), html);
    }

    /**
     * The production template prints an image attachment's path, width and height into the attributes of its
     * image, unescaped. A width that would leave the style attribute is dropped, a plain height stays, and an
     * attachment whose path would leave the src attribute is left out. FAILS on the pre-fix code, which put the
     * editor's width into the mail as markup.
     */
    @Test
    @Transactional
    void anAttachmentCannotAddMarkupToTheMail() throws Exception {
        User user = persistUser("u-" + UUID.randomUUID());
        loginAs(user);

        MessageVo message = new MessageVo();
        message.setId(UUID.randomUUID().toString());
        MessageDescVo desc = new MessageDescVo();
        desc.setLang("en");
        desc.setTitle("IT attachment title");
        message.setDescs(new ArrayList<>(List.of(desc)));
        AttachmentVo chart = new AttachmentVo();
        chart.setType("image/png");
        chart.setDisplay(AttachmentDisplayType.ABOVE);
        chart.setPath("messages/it/uid/att/it-chart.png");
        chart.setWidth("10px\"><a href=\"https://phish.example.org/it\">it-verify</a>"
                + "<img src=\"https://tracker.example.org/it.png");
        chart.setHeight("5cm");
        AttachmentVo forged = new AttachmentVo();
        forged.setType("image/png");
        forged.setDisplay(AttachmentDisplayType.ABOVE);
        forged.setPath("messages/it/uid/att/it-forged.png\" onerror=\"alert('it-path')");
        message.setAttachments(new ArrayList<>(List.of(chart, forged)));

        String subject = "MSG-MAIL-ATT-" + UUID.randomUUID();
        MessageMailTemplate t = new MessageMailTemplate()
                .subject(subject)
                .mailMessage("body")
                .messages(List.of(message))
                .recipients(List.of(toRecipient("attachment@example.com")))
                .language("en")
                .templatePath("/templates/messages/message-mail.ftl");

        messageMailService.sendMessageMail(t);

        List<ScheduledMail> mails = mailsBySubject(subject);
        assertEquals(1, mails.size(), "exactly one mail must be persisted");
        String html = mails.get(0).getHtmlContents();
        assertTrue(html.contains("IT attachment title"), "the title must be in the mail; body was: " + html);
        assertTrue(html.contains("messages/it/uid/att/it-chart.png"), "the chart must be in the mail: " + html);
        assertTrue(html.contains("height: 5cm"), "the chart keeps its plain height: " + html);
        assertFalse(html.contains("phish.example.org"), html);
        assertFalse(html.contains("it-verify"), html);
        assertFalse(html.contains("tracker.example.org"), html);
        assertFalse(html.contains("it-forged"), html);
        assertFalse(html.contains("it-path"), html);
    }

    /**
     * A message-support.ftl stored in the database may build hash-routed message links from
     * {@code baseUri}. The stored mail carries every message link in the form the class-path template
     * builds: {@code <messageLinkBaseUri>/messages?messageId=<id>}.
     */
    @Test
    @Transactional
    void hashRoutedMessageLinksAreStoredInTheMessageLinkForm() throws Exception {
        User user = persistUser("u-" + UUID.randomUUID());
        loginAs(user);

        String subject = "MSG-MAIL-LINKS-" + UUID.randomUUID();
        MessageMailTemplate t = new MessageMailTemplate()
                .subject(subject)
                .mailMessage("body")
                .messages(Collections.emptyList())
                .recipients(List.of(toRecipient("links@example.com")))
                .language("en")
                .templatePath(LINKS_TEMPLATE);

        messageMailService.sendMessageMail(t);

        List<ScheduledMail> mails = mailsBySubject(subject);
        assertEquals(1, mails.size(), "exactly one mail must be persisted");
        String html = mails.get(0).getHtmlContents();
        String messageLinks = app.getMessageLinkBaseUri() + "/messages?messageId=";
        assertTrue(html.contains("HASH-LINK=<a href=\"" + messageLinks + "NW-001-24\">"),
                "a hash-routed link must be stored in the message-link form; body was: " + html);
        assertTrue(html.contains("MESSAGE-LINK=<a href=\"" + messageLinks + "NW-002-24\">"),
                "a link already in the message-link form must be stored unchanged; body was: " + html);
        assertFalse(html.contains("#/message/"),
                "no hash-routed message link may be stored; body was: " + html);
    }

    /**
     * With recipients present but no authenticated user, {@code currentUser()} returns {@code null}; the
     * service now rejects the caller with a {@link NiordWebException} carrying HTTP 401 rather than
     * dereferencing {@code user.getName()} and surfacing an opaque HTTP 500 via a NullPointerException.
     */
    @Test
    @Transactional
    void noCurrentUserWithRecipientsIsRejectedWith401() throws Exception {
        // No loginAs(...) -> userService.currentUser() resolves to null.
        MessageMailTemplate t = new MessageMailTemplate()
                .subject("MSG-MAIL-NOAUTH-" + UUID.randomUUID())
                .mailMessage("body")
                .messages(Collections.emptyList())
                .recipients(List.of(toRecipient("noauth@example.com")))
                .language("en")
                .templatePath(IT_TEMPLATE);

        NiordWebException ex = assertThrows(NiordWebException.class,
                () -> messageMailService.sendMessageMail(t),
                "with recipients but no current user, the service must reject the caller as unauthenticated");
        assertEquals(401, ex.getStatusCode().value(),
                "no current user must be rejected with HTTP 401, not an opaque NPE/500");
    }
}
