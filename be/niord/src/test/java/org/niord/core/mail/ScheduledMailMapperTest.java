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
import org.niord.core.mail.vo.ScheduledMailRecipientVo;
import org.niord.core.mail.vo.ScheduledMailVo;
import org.niord.model.DataFilter;

import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ScheduledMailMapper}: pins the field projection that was previously embedded in
 * {@code ScheduledMail.toVo(DataFilter)} / {@code ScheduledMailRecipient.toVo()}, so the extraction is
 * provably behaviour-preserving. Runs as a plain unit test with no persistence context.
 */
public class ScheduledMailMapperTest {

    private final ScheduledMailMapper mapper = new ScheduledMailMapper();

    private static ScheduledMail mail() throws Exception {
        ScheduledMail mail = new ScheduledMail();
        mail.setCreated(new Date(1_500_000_000_000L));
        mail.setSendDate(new Date(1_600_000_000_000L));
        mail.setStatus(ScheduledMail.Status.ERROR);
        mail.setSender("sender@example.com");
        mail.setSubject("The subject");
        mail.setAttempts(3);
        mail.setLastError("boom");
        mail.setSentAgainAt(new Date(1_700_000_000_000L));
        mail.setSentAgainBy("a-sysadmin");
        mail.setHtmlContents("<p>secret body</p>");
        mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, "to@example.com"));
        mail.addRecipient(new ScheduledMailRecipient(RecipientType.CC, "cc@example.com"));
        return mail;
    }

    /** The no-arg convenience uses an ALL filter => DETAILS on => contents decompressed and included. */
    @Test
    public void toVo_allFilter_copiesEveryFieldIncludingContents() throws Exception {
        ScheduledMailVo vo = mapper.toVo(mail());

        assertEquals(new Date(1_500_000_000_000L), vo.getCreated());
        assertEquals(new Date(1_600_000_000_000L), vo.getSendDate());
        assertEquals(ScheduledMail.Status.ERROR, vo.getStatus());
        assertEquals("sender@example.com", vo.getSender());
        assertEquals("The subject", vo.getSubject());
        assertEquals(3, vo.getAttempts());
        assertEquals("boom", vo.getLastError());
        assertEquals(new Date(1_700_000_000_000L), vo.getSentAgainAt());
        assertEquals("a-sysadmin", vo.getSentAgainBy());
        assertEquals("<p>secret body</p>", vo.getContents(), "ALL filter includes DETAILS => decompressed contents");

        assertNotNull(vo.getRecipients());
        assertEquals(2, vo.getRecipients().size());
        assertEquals(Arrays.asList("to@example.com", "cc@example.com"),
                Arrays.asList(vo.getRecipients().get(0).getAddress(), vo.getRecipients().get(1).getAddress()));
        assertEquals(RecipientType.TO, vo.getRecipients().get(0).getRecipientType());
        assertEquals(RecipientType.CC, vo.getRecipients().get(1).getRecipientType());
    }

    /** Default DataFilter.get() lacks DETAILS => scalars + recipients mapped, but contents omitted. */
    @Test
    public void toVo_withoutDetails_omitsContents() throws Exception {
        ScheduledMailVo vo = mapper.toVo(mail(), DataFilter.get());

        assertEquals("The subject", vo.getSubject());
        assertEquals("sender@example.com", vo.getSender());
        assertEquals(3, vo.getAttempts());
        assertNull(vo.getContents(), "contents must be omitted when DETAILS is not requested");

        // Recipients are still mapped regardless of the DETAILS flag
        assertNotNull(vo.getRecipients());
        assertEquals(2, vo.getRecipients().size());
    }

    /** With DETAILS explicitly requested, the decompressed HTML contents are included. */
    @Test
    public void toVo_withDetails_includesContents() throws Exception {
        ScheduledMailVo vo = mapper.toVo(mail(), DataFilter.get().fields(DataFilter.DETAILS));

        assertEquals("<p>secret body</p>", vo.getContents());
    }

    /** An empty recipient list leaves the VO recipients null rather than an empty list. */
    @Test
    public void toVo_noRecipients_leavesRecipientsNull() {
        ScheduledMail mail = new ScheduledMail();
        mail.setSubject("No recipients");

        ScheduledMailVo vo = mapper.toVo(mail);

        assertNull(vo.getRecipients());
    }

    /** A null entity maps to a null VO. */
    @Test
    public void toVo_nullEntity_isNull() {
        assertNull(mapper.toVo(null));
        assertNull(mapper.toVo(null, DataFilter.get()));
    }

    /** toEntity copies every scalar field and re-compresses the contents so the HTML round-trips. */
    @Test
    public void toEntity_copiesScalarsRecipientsAndCompressesContents() throws Exception {
        ScheduledMailVo vo = new ScheduledMailVo();
        vo.setId(42);
        vo.setCreated(new Date(1_111_111_111_000L));
        vo.setSendDate(new Date(2_222_222_222_000L));
        vo.setStatus(ScheduledMail.Status.SENT);
        vo.setSender("from@example.com");
        vo.setSubject("Round trip");
        vo.setAttempts(7);
        vo.setLastError("prev error");
        vo.setSentAgainAt(new Date(3_333_333_333_000L));
        vo.setSentAgainBy("another-sysadmin");
        vo.setContents("<p>round-trip body</p>");

        ScheduledMailRecipientVo r1 = new ScheduledMailRecipientVo();
        r1.setRecipientType(RecipientType.BCC);
        r1.setAddress("bcc@example.com");
        vo.setRecipients(Arrays.asList(r1));

        ScheduledMail mail = mapper.toEntity(vo);

        assertEquals(Integer.valueOf(42), mail.getId());
        assertEquals(new Date(1_111_111_111_000L), mail.getCreated());
        assertEquals(new Date(2_222_222_222_000L), mail.getSendDate());
        assertEquals(ScheduledMail.Status.SENT, mail.getStatus());
        assertEquals("from@example.com", mail.getSender());
        assertEquals("Round trip", mail.getSubject());
        assertEquals(7, mail.getAttempts());
        assertEquals("prev error", mail.getLastError());
        assertEquals(new Date(3_333_333_333_000L), mail.getSentAgainAt());
        assertEquals("another-sysadmin", mail.getSentAgainBy());
        // contents was stored compressed; decompressing yields the original HTML
        assertEquals("<p>round-trip body</p>", mail.getHtmlContents());

        assertEquals(1, mail.getRecipients().size());
        assertEquals(RecipientType.BCC, mail.getRecipients().get(0).getRecipientType());
        assertEquals("bcc@example.com", mail.getRecipients().get(0).getAddress());
        // addRecipient back-links the child to its owning mail
        assertEquals(mail, mail.getRecipients().get(0).getMail());
    }

    /** A null contents field on the VO leaves the entity's compressed contents untouched (null). */
    @Test
    public void toEntity_nullContents_leavesEntityContentsNull() {
        ScheduledMailVo vo = new ScheduledMailVo();
        vo.setSubject("No contents");

        ScheduledMail mail = mapper.toEntity(vo);

        assertEquals("No contents", mail.getSubject());
        assertNull(mail.getContents());
    }

    /** Full entity -> VO -> entity round-trip preserves the observable fields. */
    @Test
    public void roundTrip_entityToVoToEntity_preservesFields() throws Exception {
        ScheduledMail original = mail();

        ScheduledMail copy = mapper.toEntity(mapper.toVo(original));

        assertEquals(original.getSubject(), copy.getSubject());
        assertEquals(original.getSender(), copy.getSender());
        assertEquals(original.getStatus(), copy.getStatus());
        assertEquals(original.getAttempts(), copy.getAttempts());
        assertEquals(original.getLastError(), copy.getLastError());
        assertEquals(original.getSentAgainAt(), copy.getSentAgainAt());
        assertEquals(original.getSentAgainBy(), copy.getSentAgainBy());
        assertEquals(original.getHtmlContents(), copy.getHtmlContents());
        assertEquals(original.getRecipients().size(), copy.getRecipients().size());
        assertEquals("to@example.com", copy.getRecipients().get(0).getAddress());
        assertEquals(RecipientType.TO, copy.getRecipients().get(0).getRecipientType());
    }

    /** updateEntity overwrites the scalar fields and replaces the recipient collection in place. */
    @Test
    public void updateEntity_overwritesFieldsAndReplacesRecipients() throws Exception {
        ScheduledMail mail = mail(); // starts with TO + CC recipients

        ScheduledMailVo vo = new ScheduledMailVo();
        vo.setSubject("Updated subject");
        vo.setSender("updated@example.com");
        vo.setStatus(ScheduledMail.Status.PENDING);
        vo.setAttempts(0);
        vo.setLastError(null);
        vo.setContents("<p>updated</p>");

        ScheduledMailRecipientVo r = new ScheduledMailRecipientVo();
        r.setRecipientType(RecipientType.TO);
        r.setAddress("only@example.com");
        vo.setRecipients(Arrays.asList(r));

        mapper.updateEntity(mail, vo);

        assertEquals("Updated subject", mail.getSubject());
        assertEquals("updated@example.com", mail.getSender());
        assertEquals(ScheduledMail.Status.PENDING, mail.getStatus());
        assertEquals(0, mail.getAttempts());
        assertNull(mail.getLastError());
        assertEquals("<p>updated</p>", mail.getHtmlContents());

        // The original two recipients are replaced by the single one from the VO
        assertEquals(1, mail.getRecipients().size());
        assertEquals("only@example.com", mail.getRecipients().get(0).getAddress());
        assertEquals(mail, mail.getRecipients().get(0).getMail());
    }

    /** A null VO into toEntity yields null. */
    @Test
    public void toEntity_nullVo_isNull() {
        assertNull(mapper.toEntity(null));
    }
}
