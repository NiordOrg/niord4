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

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ScheduledMailSearchParams}.
 *
 * <p>Pins the observable contract of the method-chaining setters/getters, the Long-to-Date
 * conversion overloads, and the {@code toString()} criteria rendering. These are pure-logic
 * tests: no database, no Quarkus.
 */
public class ScheduledMailSearchParamsTest {

    /* ---------------------------------------------------------------------- */
    /* Chaining setters return the same instance and set the field            */
    /* ---------------------------------------------------------------------- */

    @Test
    public void recipientSetterReturnsSameInstanceAndSetsField() {
        ScheduledMailSearchParams p = new ScheduledMailSearchParams();
        String value = "rec-" + System.nanoTime() + "@x.com";

        ScheduledMailSearchParams returned = p.recipient(value);

        assertSame(p, returned, "setter must return the same instance for chaining");
        assertEquals(value, p.getRecipient());
    }

    @Test
    public void senderSetterReturnsSameInstanceAndSetsField() {
        ScheduledMailSearchParams p = new ScheduledMailSearchParams();
        String value = "snd-" + System.nanoTime() + "@x.com";

        ScheduledMailSearchParams returned = p.sender(value);

        assertSame(p, returned);
        assertEquals(value, p.getSender());
    }

    @Test
    public void subjectSetterReturnsSameInstanceAndSetsField() {
        ScheduledMailSearchParams p = new ScheduledMailSearchParams();
        String value = "subject-" + System.nanoTime();

        ScheduledMailSearchParams returned = p.subject(value);

        assertSame(p, returned);
        assertEquals(value, p.getSubject());
    }

    @Test
    public void statusSetterReturnsSameInstanceAndSetsField() {
        ScheduledMailSearchParams p = new ScheduledMailSearchParams();

        ScheduledMailSearchParams returned = p.status(ScheduledMail.Status.ERROR);

        assertSame(p, returned);
        assertEquals(ScheduledMail.Status.ERROR, p.getStatus());
    }

    @Test
    public void fromDateSetterReturnsSameInstanceAndSetsField() {
        ScheduledMailSearchParams p = new ScheduledMailSearchParams();
        Date d = new Date(System.nanoTime());

        ScheduledMailSearchParams returned = p.from(d);

        assertSame(p, returned);
        assertSame(d, p.getFrom());
    }

    @Test
    public void toDateSetterReturnsSameInstanceAndSetsField() {
        ScheduledMailSearchParams p = new ScheduledMailSearchParams();
        Date d = new Date(System.nanoTime());

        ScheduledMailSearchParams returned = p.to(d);

        assertSame(p, returned);
        assertSame(d, p.getTo());
    }

    /* ---------------------------------------------------------------------- */
    /* from(Long) / to(Long) conversion                                       */
    /* ---------------------------------------------------------------------- */

    @Test
    public void fromLongConvertsToDateWithSameTimestamp() {
        ScheduledMailSearchParams p = new ScheduledMailSearchParams();

        ScheduledMailSearchParams returned = p.from((Long) 1000L);

        assertSame(p, returned);
        assertNotNull(p.getFrom(), "Long overload must produce a Date");
        assertEquals(1000L, p.getFrom().getTime());
    }

    @Test
    public void fromLongNullClearsTheDate() {
        ScheduledMailSearchParams p = new ScheduledMailSearchParams();
        // Pre-set a value to ensure null actually clears it.
        p.from(new Date(System.nanoTime()));

        ScheduledMailSearchParams returned = p.from((Long) null);

        assertSame(p, returned);
        assertNull(p.getFrom());
    }

    @Test
    public void toLongConvertsToDateWithSameTimestamp() {
        ScheduledMailSearchParams p = new ScheduledMailSearchParams();

        ScheduledMailSearchParams returned = p.to((Long) 1000L);

        assertSame(p, returned);
        assertEquals(1000L, p.getTo().getTime());
    }

    @Test
    public void toLongNullClearsTheDate() {
        ScheduledMailSearchParams p = new ScheduledMailSearchParams();
        p.to(new Date(System.nanoTime()));

        ScheduledMailSearchParams returned = p.to((Long) null);

        assertSame(p, returned);
        assertNull(p.getTo());
    }

    /* ---------------------------------------------------------------------- */
    /* toString()                                                             */
    /* ---------------------------------------------------------------------- */

    @Test
    public void toStringIncludesAllSetFields() {
        Date from = new Date(0L);
        ScheduledMailSearchParams p = new ScheduledMailSearchParams()
                .recipient("r@x")
                .sender("s@x")
                .subject("Sub")
                .status(ScheduledMail.Status.PENDING)
                .from(from);

        String s = p.toString();

        assertTrue(s.contains("Recipient: 'r@x'"), "missing recipient: " + s);
        assertTrue(s.contains("Sender: 's@x'"), "missing sender: " + s);
        assertTrue(s.contains("Subject: 'Sub'"), "missing subject: " + s);
        assertTrue(s.contains("Status: PENDING"), "missing status: " + s);
        assertTrue(s.contains("From: "), "missing from: " + s);
        // 'from' is rendered via Date.toString(), so the actual date text must be present too.
        assertTrue(s.contains("From: " + from), "missing from value: " + s);
    }

    @Test
    public void emptyParamsToStringIsEmptyString() {
        // No criteria set -> joining an empty list yields the empty string.
        assertEquals("", new ScheduledMailSearchParams().toString());
    }

    @Test
    public void blankFieldsAreNotRenderedInToString() {
        // isNotBlank guards: blank recipient/sender/subject contribute nothing.
        ScheduledMailSearchParams p = new ScheduledMailSearchParams()
                .recipient("")
                .sender("   ")
                .subject("");

        assertEquals("", p.toString());
    }

    @Test
    public void toString_toCriterionRendersTheDateValue() {
        // Fixed (docs/potential-errors.md MAIL2): when only 'to' is set, toString() renders the
        // actual date value ("To: " + to), mirroring the 'from' branch. It previously emitted the
        // literal string "To: to" (a copy-paste defect).
        Date to = new Date(123456789L);
        ScheduledMailSearchParams p = new ScheduledMailSearchParams().to(to);

        String s = p.toString();

        assertEquals("To: " + to, s);
        // The real date value is present, and the literal "To: to" placeholder is gone.
        assertTrue(s.contains(to.toString()), "'to' rendering must contain the date value");
    }
}
