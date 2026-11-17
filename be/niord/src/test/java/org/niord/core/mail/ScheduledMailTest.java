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

package org.niord.core.mail;

import org.junit.jupiter.api.Test;
import org.niord.core.mail.ScheduledMailRecipient.RecipientType;
import org.niord.core.mail.vo.ScheduledMailVo;
import org.niord.model.DataFilter;

import jakarta.mail.internet.InternetAddress;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tier-1 unit tests for {@link ScheduledMail} entity logic.
 *
 * <p>These tests exercise pure in-memory behaviour: the error/backoff state machine,
 * the success transition, recipient splitting, the {@code @PrePersist} hook, the
 * gzip-backed HTML content round-trip, VO conversion (with/without DETAILS) and
 * the {@link ScheduledMail#toMail} composition. No database or Quarkus runtime is used.
 */
public class ScheduledMailTest {

    /**
     * registerMailErrorAttempt: stays PENDING while attempts < DELAYS.length (=4), schedules a
     * future sendDate each time, and only flips to ERROR on the attempt past the delay table.
     */
    @Test
    public void registerMailErrorAttempt_backoffStateMachine() {
        ScheduledMail mail = new ScheduledMail();
        assertEquals(0, mail.getAttempts());
        assertEquals(ScheduledMail.Status.PENDING, mail.getStatus());
        assertEquals(4, ScheduledMail.DELAYS.length);

        // Attempt 1: still PENDING, attempts == 1, error recorded, sendDate pushed into the future
        long before1 = new Date().getTime();
        mail.registerMailErrorAttempt("err-1");
        assertEquals(ScheduledMail.Status.PENDING, mail.getStatus());
        assertEquals(1, mail.getAttempts());
        assertEquals("err-1", mail.getLastError());
        assertNotNull(mail.getSendDate());
        assertTrue(mail.getSendDate().getTime() > before1,
                "sendDate should be in the future after a retry was scheduled");

        // Attempts 2..4: still PENDING (attempts was < 4 at the start of each call)
        mail.registerMailErrorAttempt("err-2");
        assertEquals(ScheduledMail.Status.PENDING, mail.getStatus());
        assertEquals(2, mail.getAttempts());

        mail.registerMailErrorAttempt("err-3");
        assertEquals(ScheduledMail.Status.PENDING, mail.getStatus());
        assertEquals(3, mail.getAttempts());

        long before4 = new Date().getTime();
        mail.registerMailErrorAttempt("err-4");
        assertEquals(ScheduledMail.Status.PENDING, mail.getStatus(),
                "after 4 errors the mail is still PENDING");
        assertEquals(4, mail.getAttempts());
        assertEquals("err-4", mail.getLastError());
        assertTrue(mail.getSendDate().getTime() > before4,
                "4th retry should still schedule a future sendDate");

        // Attempt 5: attempts (==4) is no longer < DELAYS.length -> flips to ERROR
        mail.registerMailErrorAttempt("err-5");
        assertEquals(ScheduledMail.Status.ERROR, mail.getStatus());
        assertEquals(5, mail.getAttempts());
        assertEquals("err-5", mail.getLastError());
    }

    /**
     * registerMailSent: SENT, attempts incremented, lastError cleared even if a prior error existed.
     */
    @Test
    public void registerMailSent_clearsErrorAndMarksSent() {
        ScheduledMail mail = new ScheduledMail();
        mail.setLastError("a previous failure " + System.nanoTime());

        mail.registerMailSent();

        assertEquals(ScheduledMail.Status.SENT, mail.getStatus());
        assertEquals(1, mail.getAttempts());
        assertNull(mail.getLastError(), "lastError must be cleared on success");
    }

    /**
     * splitByRecipient: one ScheduledMail per recipient, each carrying exactly one recipient and
     * copying subject/sender/sendDate/created and the (same) contents byte array.
     */
    @Test
    public void splitByRecipient_producesOnePerRecipient() throws Exception {
        String subject = "Subject-" + System.nanoTime();
        String sender = "sender-" + System.nanoTime() + "@example.com";
        Date sendDate = new Date(1_600_000_000_000L);
        Date created = new Date(1_500_000_000_000L);

        ScheduledMail mail = new ScheduledMail();
        mail.setSubject(subject);
        mail.setSender(sender);
        mail.setSendDate(sendDate);
        mail.setCreated(created);
        mail.setHtmlContents("<p>body</p>");
        byte[] contents = mail.getContents();

        mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, "a@b.com"));
        mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, "c@d.com"));

        List<ScheduledMail> split = mail.splitByRecipient();
        assertEquals(2, split.size());

        for (ScheduledMail m : split) {
            assertEquals(1, m.getRecipients().size());
            assertEquals(subject, m.getSubject());
            assertEquals(sender, m.getSender());
            assertEquals(sendDate, m.getSendDate());
            assertEquals(created, m.getCreated());
            assertSame(contents, m.getContents(), "contents byte[] is shared, not copied");
        }

        // The two split mails address the two distinct recipients
        String addr0 = split.get(0).getRecipients().get(0).getAddress();
        String addr1 = split.get(1).getRecipients().get(0).getAddress();
        assertEquals(Arrays.asList("a@b.com", "c@d.com"), Arrays.asList(addr0, addr1));

        // addRecipient back-links the recipient to its owning mail
        assertSame(split.get(0), split.get(0).getRecipients().get(0).getMail());
    }

    /**
     * onCreate (@PrePersist, package-protected): created defaults to "now" and sendDate mirrors it.
     */
    @Test
    public void onCreate_setsCreatedAndSendDateWhenBothNull() {
        ScheduledMail mail = new ScheduledMail();
        assertNull(mail.getCreated());
        assertNull(mail.getSendDate());

        mail.onCreate();

        assertNotNull(mail.getCreated());
        assertNotNull(mail.getSendDate());
        assertSame(mail.getCreated(), mail.getSendDate(), "sendDate defaults to the created instance");
    }

    /**
     * onCreate: with created preset and sendDate null, sendDate is set to created.
     */
    @Test
    public void onCreate_setsSendDateFromPresetCreated() {
        ScheduledMail mail = new ScheduledMail();
        Date created = new Date(1_234_567_890_000L);
        mail.setCreated(created);

        mail.onCreate();

        assertSame(created, mail.getCreated());
        assertSame(created, mail.getSendDate());
    }

    /**
     * onCreate: with both preset, neither field is touched.
     */
    @Test
    public void onCreate_leavesBothPresetUnchanged() {
        ScheduledMail mail = new ScheduledMail();
        Date created = new Date(1_111_111_111_000L);
        Date sendDate = new Date(2_222_222_222_000L);
        mail.setCreated(created);
        mail.setSendDate(sendDate);

        mail.onCreate();

        assertSame(created, mail.getCreated());
        assertSame(sendDate, mail.getSendDate());
    }

    /**
     * getHtmlContents/setHtmlContents: single-line HTML survives the gzip compress/decompress round-trip.
     */
    @Test
    public void htmlContents_singleLineRoundTrips() throws Exception {
        ScheduledMail mail = new ScheduledMail();
        mail.setHtmlContents("<p>Hello world</p>");
        assertEquals("<p>Hello world</p>", mail.getHtmlContents());
    }

    /**
     * Multi-line HTML survives the gzip compress/decompress round-trip: GzipUtils.decompressString
     * now copies the stream char-by-char, so newlines are preserved (docs/potential-errors.md U3).
     */
    @Test
    public void htmlContents_multiLineRoundTrips() throws Exception {
        ScheduledMail mail = new ScheduledMail();
        mail.setHtmlContents("line1\nline2");
        assertEquals("line1\nline2", mail.getHtmlContents());
    }

    /**
     * toVo without DETAILS: scalar fields and recipients are mapped, but contents are omitted.
     */
    @Test
    public void toVo_withoutDetails_copiesScalarsAndRecipientsButNoContents() throws Exception {
        Date created = new Date(1_500_000_000_000L);
        Date sendDate = new Date(1_600_000_000_000L);
        String sender = "from-" + System.nanoTime() + "@example.com";
        String subject = "Subject-" + System.nanoTime();

        ScheduledMail mail = new ScheduledMail();
        mail.setCreated(created);
        mail.setSendDate(sendDate);
        mail.setStatus(ScheduledMail.Status.ERROR);
        mail.setSender(sender);
        mail.setSubject(subject);
        mail.setAttempts(3);
        mail.setLastError("boom");
        mail.setHtmlContents("<p>secret</p>");
        mail.addRecipient(new ScheduledMailRecipient(RecipientType.CC, "cc@example.com"));

        ScheduledMailVo vo = mail.toVo(DataFilter.get());

        assertEquals(created, vo.getCreated());
        assertEquals(sendDate, vo.getSendDate());
        assertEquals(ScheduledMail.Status.ERROR, vo.getStatus());
        assertEquals(sender, vo.getSender());
        assertEquals(subject, vo.getSubject());
        assertEquals(3, vo.getAttempts());
        assertEquals("boom", vo.getLastError());
        assertNull(vo.getContents(), "contents must be omitted when DETAILS is not requested");

        assertNotNull(vo.getRecipients());
        assertEquals(1, vo.getRecipients().size());
        assertEquals("cc@example.com", vo.getRecipients().get(0).getAddress());
        assertEquals(RecipientType.CC, vo.getRecipients().get(0).getRecipientType());
    }

    /**
     * toVo with DETAILS: the (decompressed) HTML contents are included in the VO.
     */
    @Test
    public void toVo_withDetails_includesContents() throws Exception {
        ScheduledMail mail = new ScheduledMail();
        mail.setSubject("Subject-" + System.nanoTime());
        mail.setHtmlContents("<p>x</p>");

        ScheduledMailVo vo = mail.toVo(DataFilter.get().fields(DataFilter.DETAILS));

        assertEquals("<p>x</p>", vo.getContents());
    }

    /**
     * toVo with no recipients: the VO recipients list is left untouched (null) rather than empty.
     */
    @Test
    public void toVo_withNoRecipients_leavesRecipientsNull() {
        ScheduledMail mail = new ScheduledMail();
        mail.setSubject("Subject-" + System.nanoTime());

        ScheduledMailVo vo = mail.toVo(DataFilter.get());

        assertNull(vo.getRecipients());
    }

    /**
     * toMail: builds a Mail from the HTML contents with the subject, a single TO recipient and the
     * sender turned into a reply-to InternetAddress.
     */
    @Test
    public void toMail_buildsMailWithSubjectRecipientAndReplyTo() throws Exception {
        String subject = "Subject-" + System.nanoTime();
        String sender = "sender" + System.nanoTime() + "@example.com";

        ScheduledMail mail = new ScheduledMail();
        mail.setSubject(subject);
        mail.setSender(sender);
        mail.setHtmlContents("<html><body><p>Hello</p></body></html>");
        mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, "to@example.com"));

        Mail result = mail.toMail("http://localhost/", HtmlMail.StyleHandling.INLINE_STYLES, false);

        assertEquals(subject, result.getSubject());
        assertEquals(1, result.getRecipients().size());

        // The sender becomes a reply-to address
        assertEquals(1, result.getReplyTo().size());
        assertTrue(result.getReplyTo().get(0) instanceof InternetAddress);
        assertEquals(sender, ((InternetAddress) result.getReplyTo().get(0)).getAddress());
    }

    /**
     * toMail: a blank sender means no reply-to address is added.
     */
    @Test
    public void toMail_blankSenderAddsNoReplyTo() throws Exception {
        ScheduledMail mail = new ScheduledMail();
        mail.setSubject("Subject-" + System.nanoTime());
        mail.setSender("   ");
        mail.setHtmlContents("<html><body><p>Hi</p></body></html>");
        mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, "to@example.com"));

        Mail result = mail.toMail("http://localhost/", HtmlMail.StyleHandling.INLINE_STYLES, false);

        assertTrue(result.getReplyTo().isEmpty(), "blank sender must not produce a reply-to address");
        assertEquals(1, result.getRecipients().size());
    }
}
