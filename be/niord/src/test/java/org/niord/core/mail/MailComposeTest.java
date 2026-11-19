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
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link Mail#compose(Session, org.infinispan.Cache)} and
 * {@link Mail#filterRecipients(ValidMailRecipients)}.
 *
 * <p>These tests pin the observable contract of building a MIME message from a {@link Mail}:
 * the validation order of mandatory fields, the resulting top-level content type for the
 * various body-part combinations (plain only, html only, both, with attachments), and the
 * recipient filtering / substitution behaviour. No mail is actually sent; we only inspect the
 * composed {@link MimeMessage}.</p>
 */
public class MailComposeTest {

    /** Builds a stand-alone mail session backed by empty properties (no SMTP transport needed). */
    private Session newSession() {
        return Session.getInstance(new Properties());
    }

    private InternetAddress addr(String email) throws AddressException {
        return new InternetAddress(email);
    }

    // -------------------------------------------------------------------------
    // compose() mandatory-field validation. compose() throws MessagingException.
    // -------------------------------------------------------------------------

    @Test
    public void compose_noFrom_throws() {
        Mail mail = new Mail()
                .subject("Subject " + System.nanoTime())
                .plainText("body");
        try {
            mail.compose(newSession(), null);
            fail("Expected MessagingException for missing from address");
        } catch (MessagingException e) {
            assertEquals("No from address specified", e.getMessage());
        }
    }

    @Test
    public void compose_fromButNoRecipient_throws() throws Exception {
        Mail mail = new Mail()
                .from(addr("sender@from.com"))
                .subject("Subject " + System.nanoTime())
                .plainText("body");
        try {
            mail.compose(newSession(), null);
            fail("Expected MessagingException for missing recipient address");
        } catch (MessagingException e) {
            assertEquals("No recipient address specified", e.getMessage());
        }
    }

    @Test
    public void compose_noSubject_throws() throws Exception {
        Mail mail = new Mail()
                .from(addr("sender@from.com"))
                .recipient(Message.RecipientType.TO, addr("to@to.com"))
                .plainText("body");
        // subject left null on purpose
        try {
            mail.compose(newSession(), null);
            fail("Expected MessagingException for missing subject");
        } catch (MessagingException e) {
            assertEquals("No subject specified", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // compose() body-part composition
    // -------------------------------------------------------------------------

    @Test
    public void compose_plainTextOnly_setsSubjectFromRecipientAndPlainContentType() throws Exception {
        String subject = "Plain subject " + System.nanoTime();
        Mail mail = new Mail()
                .from(addr("sender@from.com"))
                .recipient(Message.RecipientType.TO, addr("to@to.com"))
                .subject(subject)
                .plainText("Hello plain world");

        MimeMessage message = mail.compose(newSession(), null);
        // The Content-Type header is only written to the message during saveChanges(); before that
        // getContentType() returns the JavaMail default ("text/plain").
        message.saveChanges();

        assertEquals(subject, message.getSubject());
        assertEquals(1, message.getAllRecipients().length);

        Address[] froms = message.getFrom();
        assertEquals(1, froms.length);
        assertEquals("sender@from.com", ((InternetAddress) froms[0]).getAddress());

        assertTrue(message.getContentType().startsWith("text/plain"),
                "Expected text/plain content type, was: " + message.getContentType());
    }

    @Test
    public void compose_htmlOnly_setsHtmlContentType() throws Exception {
        Mail mail = new Mail()
                .from(addr("sender@from.com"))
                .recipient(Message.RecipientType.TO, addr("to@to.com"))
                .subject("Html subject " + System.nanoTime())
                .htmlText("<p>Hello html world</p>");
        // plainText left null -> html-only branch

        MimeMessage message = mail.compose(newSession(), null);
        // The Content-Type header is only written during saveChanges(); read it afterwards.
        message.saveChanges();

        assertTrue(message.getContentType().startsWith("text/html"),
                "Expected text/html content type, was: " + message.getContentType());
    }

    @Test
    public void compose_bothPlainAndHtml_isAlternativeMultipart() throws Exception {
        Mail mail = new Mail()
                .from(addr("sender@from.com"))
                .recipient(Message.RecipientType.TO, addr("to@to.com"))
                .subject("Both subject " + System.nanoTime())
                .plainText("Hello plain world")
                .htmlText("<p>Hello html world</p>");

        MimeMessage message = mail.compose(newSession(), null);

        Object content = message.getContent();
        assertTrue(content instanceof MimeMultipart,
                "Expected MimeMultipart content, was: " + content.getClass());
        MimeMultipart multipart = (MimeMultipart) content;
        assertTrue(multipart.getContentType().contains("multipart/alternative"),
                "Expected multipart/alternative, was: " + multipart.getContentType());
        // alternative wraps the plain-text part and the html part
        assertEquals(2, multipart.getCount());
    }

    @Test
    public void compose_withAttachment_isMixedMultipartWithBodyAndAttachment() throws Exception {
        Mail mail = new Mail()
                .from(addr("sender@from.com"))
                .recipient(Message.RecipientType.TO, addr("to@to.com"))
                .subject("Attachment subject " + System.nanoTime())
                .plainText("Hello plain world")
                .attachment(AttachmentMailPart.fromContent("hi".getBytes(), "f.txt"));

        MimeMessage message = mail.compose(newSession(), null);

        Object content = message.getContent();
        assertTrue(content instanceof MimeMultipart,
                "Expected MimeMultipart content, was: " + content.getClass());
        MimeMultipart multipart = (MimeMultipart) content;
        assertTrue(multipart.getContentType().contains("mixed"),
                "Expected multipart/mixed, was: " + multipart.getContentType());
        // 1 body part (plain text) + 1 attachment
        assertEquals(2, multipart.getCount());
    }

    // -------------------------------------------------------------------------
    // filterRecipients()
    // -------------------------------------------------------------------------

    @Test
    public void filterRecipients_restrictedMode_substitutesStrangerWithFirstValidAddress() {
        Mail mail = new Mail();
        try {
            mail.recipient(Message.RecipientType.TO, addr("keep@me.com"))
                .recipient(Message.RecipientType.TO, addr("stranger@x.com"));
        } catch (AddressException e) {
            fail("Setup addresses should be valid: " + e.getMessage());
        }

        // Only "keep@me.com" is a valid recipient. The stranger must be substituted.
        List<Address> redirected = mail.filterRecipients(new ValidMailRecipients("keep@me.com"));

        // The stranger, as it was addressed, is the one recipient the filter redirected
        assertEquals(List.of("stranger@x.com"),
                redirected.stream().map(a -> ((InternetAddress) a).getAddress()).toList());

        // Both recipients survive (the list stays the same size), but every resolved address
        // is the single valid address: the known one passes through, the stranger is rewritten
        // to "keep@me.com" (with the original address kept only as the personal name).
        assertEquals(2, mail.getRecipients().size());
        for (Mail.MailRecipient r : mail.getRecipients()) {
            Address a = r.getAddress();
            assertTrue(a instanceof InternetAddress, "Recipient should be an InternetAddress");
            assertEquals("keep@me.com", ((InternetAddress) a).getAddress());
        }
    }

    @Test
    public void filterRecipients_redirectsNothingWhenEveryRecipientIsListedOrUnderAll() throws Exception {
        Mail listed = new Mail()
                .recipient(Message.RecipientType.TO, addr("keep@me.com"))
                .recipient(Message.RecipientType.CC, addr("Other@ok.com"));
        assertTrue(listed.filterRecipients(new ValidMailRecipients("keep@me.com, other@ok.com")).isEmpty());

        Mail all = new Mail().recipient(Message.RecipientType.TO, addr("stranger@x.com"));
        assertTrue(all.filterRecipients(new ValidMailRecipients("ALL")).isEmpty());
    }
}
