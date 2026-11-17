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
package org.niord.core.mail;

import org.junit.jupiter.api.Test;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Branch-coverage unit tests for {@link Mail#compose(Session, org.infinispan.Cache)} and
 * {@link Mail#filterRecipients(ValidMailRecipients)} that complement {@link MailComposeTest}.
 *
 * <p>These tests pin the less-travelled branches of the MIME composition logic: the
 * {@code related} multipart produced for inline (cid:) parts, the {@code mixed > related}
 * nesting produced when both attachments and inline parts are present, the empty body
 * produced when neither plain nor html text is supplied, propagation of the optional
 * {@code Sender}, custom headers and {@code Reply-To} addresses, and the two error-tolerant
 * branches of {@link Mail#filterRecipients} (non-{@code InternetAddress} recipients are
 * dropped and an {@link AddressException} thrown while filtering is swallowed).</p>
 *
 * <p>No mail is sent and no URL is opened: inline/attachment parts only have their data
 * handlers wired up by {@code compose()}; {@link CachedUrlDataSource} loads lazily, so the
 * composed {@link MimeMessage} can be inspected without any network I/O.</p>
 */
public class MailComposeBranchTest {

    /** Builds a stand-alone mail session backed by empty properties (no SMTP transport needed). */
    private Session newSession() {
        return Session.getInstance(new Properties());
    }

    private InternetAddress addr(String email) throws AddressException {
        return new InternetAddress(email);
    }

    /** A mandatory-field-complete mail base, ready to have a body / headers attached. */
    private Mail baseMail() throws AddressException {
        return new Mail()
                .from(addr("sender@from.com"))
                .recipient(Message.RecipientType.TO, addr("to@to.com"))
                .subject("Subject " + System.nanoTime());
    }

    /** A non-InternetAddress jakarta.mail Address used to exercise the instanceof guard. */
    private static class CustomAddress extends Address {
        @Override public String getType() { return "x-custom"; }
        @Override public String toString() { return "custom-address"; }
        @Override public boolean equals(Object address) { return this == address; }
    }

    // -------------------------------------------------------------------------
    // compose() - html + inline part => multipart/related with the right Content-ID
    // -------------------------------------------------------------------------

    @Test
    public void compose_htmlWithInlinePart_isRelatedMultipartCarryingContentId() throws Exception {
        Mail mail = baseMail()
                .htmlText("<p>See <img src=\"cid:logo123\"></p>")
                .inlineParts(new InlineMailPart("logo123", "file:///tmp/logo.png"));
        // plainText left null -> html-only branch, inlineParts.size() > 0 -> related branch

        MimeMessage message = mail.compose(newSession(), null);

        Object content = message.getContent();
        assertTrue(content instanceof MimeMultipart,
                "Expected MimeMultipart content, was: " + content.getClass());
        MimeMultipart related = (MimeMultipart) content;
        assertTrue(related.getContentType().contains("related"),
                "Expected multipart/related, was: " + related.getContentType());
        // index 0 = the html part, index 1 = the inline (related) part
        assertEquals(2, related.getCount());

        MimeBodyPart htmlPart = (MimeBodyPart) related.getBodyPart(0);
        // The DataHandler carries the html content type that compose() set on the part.
        assertTrue(htmlPart.getDataHandler().getContentType().startsWith("text/html"),
                "HTML part should be text/html, was: " + htmlPart.getDataHandler().getContentType());
        assertEquals("<p>See <img src=\"cid:logo123\"></p>", htmlPart.getContent());

        MimeBodyPart inlinePart = (MimeBodyPart) related.getBodyPart(1);
        String[] cid = inlinePart.getHeader("Content-ID");
        assertEquals(1, cid.length);
        assertEquals("<logo123>", cid[0]);
    }

    // -------------------------------------------------------------------------
    // compose() - html + attachment + inline => mixed wrapping a related body part
    // -------------------------------------------------------------------------

    @Test
    public void compose_htmlWithAttachmentAndInline_isMixedWrappingRelated() throws Exception {
        Mail mail = baseMail()
                .htmlText("<p>See <img src=\"cid:img1\"></p>")
                .inlineParts(new InlineMailPart("img1", "file:///tmp/img.png"))
                .attachment(AttachmentMailPart.fromContent("doc".getBytes(), "report.txt"));

        MimeMessage message = mail.compose(newSession(), null);

        Object content = message.getContent();
        assertTrue(content instanceof MimeMultipart,
                "Expected MimeMultipart content, was: " + content.getClass());
        MimeMultipart mixed = (MimeMultipart) content;
        assertTrue(mixed.getContentType().contains("mixed"),
                "Expected multipart/mixed, was: " + mixed.getContentType());
        // index 0 = the body part (a related multipart), index 1 = the attachment
        assertEquals(2, mixed.getCount());

        // The body part nested inside "mixed" must itself be a "related" multipart
        MimeBodyPart bodyPart = (MimeBodyPart) mixed.getBodyPart(0);
        Object nested = bodyPart.getContent();
        assertTrue(nested instanceof MimeMultipart,
                "Nested body should be a MimeMultipart, was: " + nested.getClass());
        MimeMultipart related = (MimeMultipart) nested;
        assertTrue(related.getContentType().contains("related"),
                "Expected nested multipart/related, was: " + related.getContentType());
        assertEquals(2, related.getCount());
        assertEquals("<img1>", related.getBodyPart(1).getHeader("Content-ID")[0]);

        // The attachment part keeps its file name and the attachment disposition
        MimeBodyPart attachmentPart = (MimeBodyPart) mixed.getBodyPart(1);
        assertEquals("report.txt", attachmentPart.getFileName());
        assertEquals(Part.ATTACHMENT, attachmentPart.getDisposition());
    }

    // -------------------------------------------------------------------------
    // compose() - no plain and no html => empty plain-text body (no NPE)
    // -------------------------------------------------------------------------

    @Test
    public void compose_noBodyAtAll_producesEmptyPlainTextBody() throws Exception {
        Mail mail = baseMail();
        // neither plainText nor htmlText set

        MimeMessage message = mail.compose(newSession(), null);
        // Content-Type header is only flushed during saveChanges().
        message.saveChanges();

        assertTrue(message.getContentType().startsWith("text/plain"),
                "Expected text/plain content type, was: " + message.getContentType());
        // The body should be the empty string the compose() fallback substitutes in.
        assertEquals("", message.getContent());
    }

    // -------------------------------------------------------------------------
    // compose() - custom headers copied onto the MimeMessage
    // -------------------------------------------------------------------------

    @Test
    public void compose_customHeaders_copiedOntoMessage() throws Exception {
        Mail mail = baseMail()
                .plainText("body")
                .customHeader("X-Niord-Type", "PROMULGATION")
                .customHeader("X-Priority", "1");

        MimeMessage message = mail.compose(newSession(), null);

        String[] type = message.getHeader("X-Niord-Type");
        assertEquals(1, type.length);
        assertEquals("PROMULGATION", type[0]);

        String[] prio = message.getHeader("X-Priority");
        assertEquals(1, prio.length);
        assertEquals("1", prio[0]);
    }

    // -------------------------------------------------------------------------
    // compose() - Sender header only when a sender is supplied
    // -------------------------------------------------------------------------

    @Test
    public void compose_withSender_setsSenderAddress() throws Exception {
        Mail mail = baseMail()
                .plainText("body")
                .sender(addr("envelope@from.com"));

        MimeMessage message = mail.compose(newSession(), null);

        Address sender = message.getSender();
        assertTrue(sender instanceof InternetAddress, "Sender should be an InternetAddress");
        assertEquals("envelope@from.com", ((InternetAddress) sender).getAddress());
    }

    @Test
    public void compose_withoutSender_hasNoSender() throws Exception {
        Mail mail = baseMail()
                .plainText("body");
        // sender left null

        MimeMessage message = mail.compose(newSession(), null);
        message.saveChanges();

        assertNull(message.getSender(), "No Sender should be set when sender is null");
        assertNull(message.getHeader("Sender"), "No Sender header should be present");
    }

    // -------------------------------------------------------------------------
    // compose() - reply-to addresses propagated
    // -------------------------------------------------------------------------

    @Test
    public void compose_replyTo_propagatedToMessage() throws Exception {
        Mail mail = baseMail()
                .plainText("body")
                .replyTo(addr("reply1@x.com"))
                .replyTo(addr("reply2@x.com"));

        MimeMessage message = mail.compose(newSession(), null);

        Address[] replyTo = message.getReplyTo();
        assertEquals(2, replyTo.length);
        assertArrayEquals(
                new String[]{"reply1@x.com", "reply2@x.com"},
                new String[]{
                        ((InternetAddress) replyTo[0]).getAddress(),
                        ((InternetAddress) replyTo[1]).getAddress()
                });
    }

    // -------------------------------------------------------------------------
    // filterRecipients() - non-InternetAddress recipients are dropped
    // -------------------------------------------------------------------------

    @Test
    public void filterRecipients_dropsNonInternetAddressRecipient() throws Exception {
        Mail mail = new Mail()
                .recipient(Message.RecipientType.TO, addr("keep@me.com"))
                .recipient(Message.RecipientType.TO, new CustomAddress());

        mail.filterRecipients(new ValidMailRecipients("ALL"));

        // The custom (non-InternetAddress) recipient is silently dropped; only the real one survives.
        assertEquals(1, mail.getRecipients().size());
        Address surviving = mail.getRecipients().get(0).getAddress();
        assertTrue(surviving instanceof InternetAddress, "Surviving recipient should be an InternetAddress");
        assertEquals("keep@me.com", ((InternetAddress) surviving).getAddress());
    }

    // -------------------------------------------------------------------------
    // filterRecipients() - AddressException during filtering is swallowed, others kept
    // -------------------------------------------------------------------------

    @Test
    public void filterRecipients_swallowsAddressExceptionAndKeepsRest() throws Exception {
        // "nodomain" parses fine in non-strict mode but fails validate() because it has no
        // "@domain" part; validate() is called inside ValidMailRecipients.filter, and the
        // resulting AddressException must be swallowed by filterRecipients().
        InternetAddress malformed = new InternetAddress("nodomain", false);
        // Sanity-check the precondition: this address really does fail validation.
        boolean validateThrows = false;
        try {
            malformed.validate();
        } catch (AddressException expected) {
            validateThrows = true;
        }
        assertTrue(validateThrows, "Test precondition: malformed address must fail validate()");

        Mail mail = new Mail()
                .recipient(Message.RecipientType.TO, addr("keep@me.com"))
                .recipient(Message.RecipientType.TO, malformed);

        // "ALL" means every valid address passes, so only the malformed one (validate failure) is dropped.
        mail.filterRecipients(new ValidMailRecipients("ALL"));

        assertEquals(1, mail.getRecipients().size());
        Address surviving = mail.getRecipients().get(0).getAddress();
        assertEquals("keep@me.com", ((InternetAddress) surviving).getAddress());
        assertFalse("nodomain".equals(((InternetAddress) surviving).getAddress()),
                "The malformed recipient must not survive filtering");
    }
}
