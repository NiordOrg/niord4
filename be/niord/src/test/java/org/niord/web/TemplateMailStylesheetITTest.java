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

import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.mail.MailService;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.mailinglist.MailingList;
import org.niord.core.mailinglist.MailingListExecutionService;
import org.niord.core.mailinglist.MailingListTrigger;
import org.niord.core.mailinglist.TriggerType;
import org.niord.core.message.Comment;
import org.niord.core.message.Message;
import org.niord.core.message.MessageHistory;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageService;
import org.niord.core.user.Contact;
import org.niord.model.message.Status;
import org.niord.test.ITTest;
import org.niord.test.MessageTestFactory;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MAIL9: a template mail whose stylesheet the backend cannot fetch from its {@code baseUri} is sent without it.
 *
 * <p>The three kinds of template mail each link {@code <baseUri>/css/templates/mail.css}: the message mail (the
 * messages' <i>Send by mail</i>, {@code GET /rest/message-mail/send}), the comment mail (a comment with e-mail
 * addresses) and a mailing list's delivery (here a scheduled trigger with the base product's
 * {@code active-navwarns.ftl}). Each is queued as a {@link ScheduledMail} and sent by
 * {@link MailService#sendScheduledMail(Integer)}. In the test context the {@code baseUri} is
 * {@code http://localhost:8888}, where nothing listens (asserted first), so the stylesheet cannot be fetched: the
 * mail is SENT (mail runs in the LOG simulation mode), without its stylesheet. On the pre-fix code each stayed
 * PENDING with "Connection refused" as its last error, to be retried and end as ERROR.
 *
 * <p>The mails are sent here, not left to the per-minute sender; the queued rows, the comment and the message are
 * removed afterwards.
 */
class TemplateMailStylesheetITTest extends ITTest {

    private static final String NW_DOMAIN = "niord-nw";

    @Autowired
    NiordApp app;

    @Autowired
    MailService mailService;

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @Autowired
    MailingListExecutionService executionService;

    @PersistenceContext
    EntityManager em;

    /** Part of every address a test mails to: its scheduled mails are found and removed by it */
    private final String mailToken = "it-mail9-" + System.nanoTime() + "-";

    private final List<String> messageUids = new ArrayList<>();

    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    @BeforeEach
    void theBaseUriServesNothing() {
        String stylesheet = app.getBaseUri().replaceAll("/+$", "") + "/css/templates/mail.css";
        assertThrows(IOException.class, () -> {
            try (InputStream in = URI.create(stylesheet).toURL().openStream()) {
                in.readAllBytes();
            }
        }, "the test needs a baseUri whose stylesheet cannot be fetched: " + stylesheet);
        // The caller's User row, which the mail's sender is made from
        given().auth().oauth2(accessToken("editor")).when().get("/rest/tickets/ticket").then().statusCode(200);
    }

    @AfterEach
    void cleanup() {
        try {
            Tx.requiringNew().run(() -> em
                    .createQuery("select distinct r.mail from ScheduledMailRecipient r where r.address like :pattern",
                            ScheduledMail.class)
                    .setParameter("pattern", "%" + mailToken + "%")
                    .getResultList()
                    .forEach(em::remove));
        } catch (Exception ignored) {
            // best-effort cleanup
        }
        for (String uid : messageUids) {
            try {
                Tx.requiringNew().run(() -> {
                    Message msg = messageQueryService.findByUid(uid);
                    if (msg != null) {
                        em.createQuery("select c from Comment c where c.message.id = :id", Comment.class)
                                .setParameter("id", msg.getId()).getResultList().forEach(em::remove);
                        em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                                .setParameter("messageId", msg.getId()).getResultList().forEach(em::remove);
                        em.remove(em.contains(msg) ? msg : em.merge(msg));
                    }
                });
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        messageUids.clear();
    }

    /** A committed published NW message, which the niord-nw domain shows */
    private String committedPublishedMessage(String title) {
        String uid = Tx.requiringNew().call(() -> messageService.createMessage(MessageTestFactory.draftNw(title))
                .getUid());
        messageUids.add(uid);
        Tx.requiringNew().run(() -> em.createQuery("update Message m set m.status = :status where m.uid = :uid")
                .setParameter("status", Status.PUBLISHED)
                .setParameter("uid", uid)
                .executeUpdate());
        return uid;
    }

    /** The id of the one mail queued to the address (alone or with a name); asserts that it links the stylesheet */
    private Integer queuedMailTo(String address) throws IOException {
        ScheduledMail mail = Tx.requiringNew().call(() -> em
                .createQuery("select r.mail from ScheduledMailRecipient r where r.address like :address",
                        ScheduledMail.class)
                .setParameter("address", "%" + address + "%")
                .getSingleResult());
        assertTrue(mail.getHtmlContents().contains("/css/templates/mail.css"),
                "the mail links the stylesheet: " + mail.getHtmlContents());
        return mail.getId();
    }

    /** Sends the queued mail as the per-minute sender does, and asserts that it was sent */
    private void assertSent(Integer mailId) {
        mailService.sendScheduledMail(mailId);
        ScheduledMail mail = Tx.requiringNew().call(() -> em.find(ScheduledMail.class, mailId));
        assertEquals(ScheduledMail.Status.SENT, mail.getStatus(),
                "the mail is sent without its stylesheet; last error: " + mail.getLastError());
    }

    @Test
    void theMessageMailIsSentWithoutAStylesheetItCannotFetch() throws IOException {
        String uid = committedPublishedMessage("IT MAIL9 message mail " + System.nanoTime());
        String address = mailToken + "message@example.com";

        as(accessToken("editor"))
                .queryParam("messageId", uid)
                .queryParam("mailTo", address)
                .queryParam("mailSubject", "IT MAIL9")
                .queryParam("lang", "en")
                .when().get("/rest/message-mail/send")
                .then().statusCode(200);

        assertSent(queuedMailTo(address));
    }

    @Test
    void theCommentMailIsSentWithoutAStylesheetItCannotFetch() throws IOException {
        String uid = committedPublishedMessage("IT MAIL9 comment mail " + System.nanoTime());
        String address = mailToken + "comment@example.com";

        as(accessToken("editor"))
                .contentType("application/json")
                .body(Map.of("comment", "IT MAIL9 comment", "emailAddresses", List.of(address)))
                .when().post("/rest/messages/message/" + uid + "/comment")
                .then().statusCode(200);

        assertSent(queuedMailTo(address));
    }

    @Test
    void aMailingListDeliveryIsSentWithoutAStylesheetItCannotFetch() throws Exception {
        String address = mailToken + "list@example.com";
        Contact contact = new Contact();
        contact.setName("IT MAIL9 subscriber");
        contact.setEmail(address);
        MailingList list = new MailingList();
        list.setMailingListId("IT-MAIL9-" + System.nanoTime());
        list.createDesc("en").setName("IT MAIL9 list");
        list.getContacts().add(contact);
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.SCHEDULED);
        trigger.setMessageQuery("status=PUBLISHED&mainType=NW");
        trigger.getScriptResourcePaths().add("templates/mailinglist/active-navwarns.ftl");
        trigger.createDesc("en").setSubject("IT MAIL9 list");
        list.addTrigger(trigger);

        // The delivery's mail, queued as the scheduler queues it (the list and the trigger stay in memory)
        List<ScheduledMail> queued = executionService.executeScheduledTrigger(trigger, true);
        assertEquals(1, queued.size(), "one mail for the one subscriber");

        assertSent(queuedMailTo(address));
    }
}
