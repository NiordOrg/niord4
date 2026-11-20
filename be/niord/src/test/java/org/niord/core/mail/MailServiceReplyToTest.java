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
package org.niord.core.mail;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.ScheduledMailRecipient.RecipientType;

import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Reply-To the Mail Queue gives a queued mail whose own sender gave none ({@link MailService#defaultReplyTo}),
 * read off the composed message, where the header is set.
 */
public class MailServiceReplyToTest {

    private static final String DEPLOYMENT = "notices@example.org";

    /** A queued mail made the way the Mail Queue makes it, with a CC before its TO recipient. */
    private static Mail queued(String sender) throws Exception {
        ScheduledMail mail = new ScheduledMail();
        mail.setSubject("Subject-" + System.nanoTime());
        mail.setSender(sender);
        mail.setHtmlContents("<html><body><p>Hi</p></body></html>");
        mail.addRecipient(new ScheduledMailRecipient(RecipientType.CC, "copy@example.com"));
        mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, "to@example.com"));
        // The From the Mail Queue's delivery sets before it composes the message
        return mail.toMail("http://localhost/", HtmlMail.StyleHandling.INLINE_STYLES, false)
                .from(new InternetAddress(DEPLOYMENT));
    }

    /** The Reply-To header of the composed message, or null where it has none. */
    private static String replyToHeader(Mail mail) throws Exception {
        MimeMessage message = mail.compose(Session.getInstance(new Properties()), null);
        String[] header = message.getHeader("Reply-To");
        return header == null ? null : String.join(", ", Arrays.asList(header));
    }

    /**
     * MAIL14, as the owner decided on 2026-11-20. A staff member whose login has no e-mail address is stored as the
     * sender by name; the mail carries the deployment's sender address as its Reply-To. FAILS on the code that gave
     * it the first TO recipient, as a mailing-list mail gets, so that Reply answered the recipient itself.
     */
    @Test
    public void aMailFromASenderWithoutAnAddressCarriesTheDeploymentsSenderAddress() throws Exception {
        for (String sender : new String[] { "Test Editor", "Editor", "Editor, Test <editor@example.com>" }) {
            Mail mail = queued(sender);
            MailService.defaultReplyTo(mail, sender, DEPLOYMENT);

            assertEquals(1, mail.getReplyTo().size(), sender);
            assertEquals(DEPLOYMENT, ((InternetAddress) mail.getReplyTo().get(0)).getAddress(), sender);
            assertEquals(DEPLOYMENT, replyToHeader(mail), sender);
        }
    }

    /** Where the deployment's sender address is empty or not one usable address, the mail goes without a Reply-To. */
    @Test
    public void anUnusableDeploymentSenderAddressGivesNoReplyTo() throws Exception {
        for (String deployment : new String[] { null, "", "   ", "Niord", "a@example.org, b@example.org" }) {
            Mail mail = queued("Test Editor");
            MailService.defaultReplyTo(mail, "Test Editor", deployment);

            assertTrue(mail.getReplyTo().isEmpty(), "deployment sender '" + deployment + "'");
            assertNull(replyToHeader(mail), "deployment sender '" + deployment + "'");
        }
    }

    /** A mailing-list mail has no sender and keeps its first TO recipient as its Reply-To. */
    @Test
    public void aMailWithoutASenderKeepsItsFirstToRecipient() throws Exception {
        for (String sender : new String[] { null, "  " }) {
            Mail mail = queued(sender);
            MailService.defaultReplyTo(mail, sender, DEPLOYMENT);

            assertEquals(1, mail.getReplyTo().size());
            assertEquals("to@example.com", ((InternetAddress) mail.getReplyTo().get(0)).getAddress());
            assertEquals("to@example.com", replyToHeader(mail));
        }
    }

    /** A sender with an address keeps it as the Reply-To; nothing is added beside it. */
    @Test
    public void aMailFromASenderWithAnAddressKeepsIt() throws Exception {
        String sender = "Test Editor <editor@example.com>";
        Mail mail = queued(sender);
        MailService.defaultReplyTo(mail, sender, DEPLOYMENT);

        assertEquals(1, mail.getReplyTo().size());
        assertEquals("editor@example.com", ((InternetAddress) mail.getReplyTo().get(0)).getAddress());
        assertTrue(replyToHeader(mail).contains("editor@example.com"));
    }

    /** The recipients the composed message carries are untouched by the Reply-To. */
    @Test
    public void theRecipientsAreUntouched() throws Exception {
        Mail mail = queued("Test Editor");
        MailService.defaultReplyTo(mail, "Test Editor", DEPLOYMENT);
        MimeMessage message = mail.compose(Session.getInstance(new Properties()), null);
        assertEquals("to@example.com",
                ((InternetAddress) message.getRecipients(Message.RecipientType.TO)[0]).getAddress());
    }
}
