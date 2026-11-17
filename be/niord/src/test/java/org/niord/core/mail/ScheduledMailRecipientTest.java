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
import org.niord.core.mail.Mail.MailRecipient;
import org.niord.core.mail.ScheduledMailRecipient.RecipientType;
import org.niord.core.mail.vo.ScheduledMailRecipientVo;

import jakarta.mail.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit tests for {@link ScheduledMailRecipient}.
 *
 * <p>Pins the observable contract of the two converters:
 * <ul>
 *     <li>{@link ScheduledMailRecipient#toVo()} copies {@code address} and {@code recipientType}
 *         straight into a {@link ScheduledMailRecipientVo}.</li>
 *     <li>{@link ScheduledMailRecipient#toMailRecipient()} maps the local {@link RecipientType}
 *         to the corresponding {@link jakarta.mail.Message.RecipientType} and wraps the address
 *         in an {@code InternetAddress}, returning {@code null} when the address cannot be parsed.</li>
 * </ul>
 */
public class ScheduledMailRecipientTest {

    /* ------------------------------------------------------------------ */
    /* toVo()                                                             */
    /* ------------------------------------------------------------------ */

    @Test
    public void toVoCopiesAddressAndRecipientType() {
        String addr = "to-" + System.nanoTime() + "@example.com";
        ScheduledMailRecipient recipient = new ScheduledMailRecipient(RecipientType.CC, addr);

        ScheduledMailRecipientVo vo = recipient.toVo();

        assertNotNull(vo);
        assertEquals(addr, vo.getAddress());
        // The enum reference is copied verbatim, not transformed.
        assertSame(RecipientType.CC, vo.getRecipientType());
    }

    @Test
    public void toVoPreservesTypeForEachRecipientType() {
        String addr = "any-" + System.nanoTime() + "@example.com";

        assertSame(RecipientType.TO, new ScheduledMailRecipient(RecipientType.TO, addr).toVo().getRecipientType());
        assertSame(RecipientType.CC, new ScheduledMailRecipient(RecipientType.CC, addr).toVo().getRecipientType());
        assertSame(RecipientType.BCC, new ScheduledMailRecipient(RecipientType.BCC, addr).toVo().getRecipientType());
    }

    @Test
    public void toVoCarriesNullAddressThrough() {
        // Behaviour: toVo() does no validation, so a null address is copied as-is.
        ScheduledMailRecipient recipient = new ScheduledMailRecipient(RecipientType.TO, null);

        ScheduledMailRecipientVo vo = recipient.toVo();

        assertNull(vo.getAddress());
        assertSame(RecipientType.TO, vo.getRecipientType());
    }

    /* ------------------------------------------------------------------ */
    /* toMailRecipient() - type mapping + address                         */
    /* ------------------------------------------------------------------ */

    @Test
    public void toMailRecipientMapsToToJakartaTo() {
        String addr = "to-" + System.nanoTime() + "@example.com";
        ScheduledMailRecipient recipient = new ScheduledMailRecipient(RecipientType.TO, addr);

        MailRecipient mr = recipient.toMailRecipient();

        assertNotNull(mr);
        assertSame(Message.RecipientType.TO, mr.getType());
        assertNotNull(mr.getAddress());
        assertEquals(addr, mr.getAddress().toString());
    }

    @Test
    public void toMailRecipientMapsCcToJakartaCc() {
        String addr = "cc-" + System.nanoTime() + "@example.com";
        ScheduledMailRecipient recipient = new ScheduledMailRecipient(RecipientType.CC, addr);

        MailRecipient mr = recipient.toMailRecipient();

        assertNotNull(mr);
        assertSame(Message.RecipientType.CC, mr.getType());
        assertNotNull(mr.getAddress());
        assertEquals(addr, mr.getAddress().toString());
    }

    @Test
    public void toMailRecipientMapsBccToJakartaBcc() {
        String addr = "bcc-" + System.nanoTime() + "@example.com";
        ScheduledMailRecipient recipient = new ScheduledMailRecipient(RecipientType.BCC, addr);

        MailRecipient mr = recipient.toMailRecipient();

        assertNotNull(mr);
        assertSame(Message.RecipientType.BCC, mr.getType());
        assertNotNull(mr.getAddress());
        assertEquals(addr, mr.getAddress().toString());
    }

    @Test
    public void toMailRecipientAddressIsInternetAddress() {
        // The wrapped Address is a jakarta.mail.internet.InternetAddress, as built by toMailRecipient().
        String addr = "inet-" + System.nanoTime() + "@example.com";
        ScheduledMailRecipient recipient = new ScheduledMailRecipient(RecipientType.TO, addr);

        MailRecipient mr = recipient.toMailRecipient();

        assertNotNull(mr);
        assertEquals(jakarta.mail.internet.InternetAddress.class, mr.getAddress().getClass());
        assertEquals(addr, mr.getAddress().toString());
    }

    /* ------------------------------------------------------------------ */
    /* toMailRecipient() - unparseable address returns null               */
    /* ------------------------------------------------------------------ */

    @Test
    public void toMailRecipientReturnsNullForEmptyAddress() {
        // new InternetAddress("") throws AddressException ("Empty address"), which is caught and
        // turned into a null return value.
        ScheduledMailRecipient recipient = new ScheduledMailRecipient(RecipientType.TO, "");

        assertNull(recipient.toMailRecipient());
    }
}
