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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.message.MessageSeriesService.FormatResolver;
import org.niord.model.message.Type;

import java.util.Calendar;
import java.util.GregorianCalendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MessageIdMarkup}: a short id, or the message id of a reference, that comes from a request body or an
 * imported file may not hold {@code <}, {@code >}, {@code "} or {@code &}. Every short-id format in use passes,
 * the ids Niord generates pass, and an id the stored message already has is left alone.
 */
class MessageIdMarkupTest {

    private static final String[] MARKUP = { "<", ">", "\"", "&" };

    /** The formats of the short ids in use: the generated ones and the ones editors type. */
    private static final String[] VALID_IDS = {
            "NW-001-26", "NM-123-24", "A/1 2024", "B/12 2024", "FA/EK-D-371 2017", "FA-EK-D-371",
            "DK-074-14", "NM-001-24 (T)", "1.2.3", "IT id with spaces"
    };

    private static Message message(String shortId, String... referenceIds) {
        Message message = new Message();
        message.setShortId(shortId);
        for (String referenceId : referenceIds) {
            Reference reference = new Reference();
            reference.setMessageId(referenceId);
            message.addReference(reference);
        }
        return message;
    }

    @Test
    void eachMarkupCharacterInTheShortIdOfANewMessageIsRefused() {
        for (String c : MARKUP) {
            assertTrue(MessageIdMarkup.holdsMarkup("NW-001" + c + "26"), c);
            assertEquals("A message short id cannot contain <, >, \" or &",
                    MessageIdMarkup.refusal(message("NW-001" + c + "26"), null), c);
        }
        assertEquals("A message short id cannot contain <, >, \" or &",
                MessageIdMarkup.refusal(message("<img src=x onerror=alert(1)>"), null));
    }

    @Test
    void theShortIdFormatsInUseAreAccepted() {
        for (String id : VALID_IDS) {
            assertFalse(MessageIdMarkup.holdsMarkup(id), id);
            assertNull(MessageIdMarkup.refusal(message(id), null), id);
            assertNull(MessageIdMarkup.refusal(message(id, id), message("other")), id);
        }
    }

    @Test
    void aMessageWithoutIdsIsAccepted() {
        assertFalse(MessageIdMarkup.holdsMarkup(null));
        assertNull(MessageIdMarkup.refusal(message(null), null));
        assertNull(MessageIdMarkup.refusal(message(""), null));
        assertNull(MessageIdMarkup.refusal(message(null, (String) null), null));
    }

    @Test
    void generatedShortIdsAreAccepted() {
        NiordApp app = mock(NiordApp.class);
        when(app.getCountry()).thenReturn("DK");
        String[] formats = {
                "NW-${number-3-digits}-${year-2-digits}", "NM-${number-3-digits}-${year-2-digits}",
                "A/${number} ${year}", "B/${number} ${year}",
                "${country}-${main-type}-${number}-${week} ${t-or-p}", "${main-type-lower}/${legacy-id}/${id}"
        };
        for (Type type : new Type[] { Type.LOCAL_WARNING, Type.TEMPORARY_NOTICE, Type.PRELIMINARY_NOTICE }) {
            Message generated = new Message();
            generated.setPublishDateFrom(new GregorianCalendar(2026, Calendar.SEPTEMBER, 26).getTime());
            generated.setNumber(371);
            generated.setType(type);
            generated.setMainType(type.getMainType());
            generated.setLegacyId("legacy-17");
            FormatResolver resolver = new FormatResolver(generated, app);
            for (String format : formats) {
                String shortId = resolver.resolveFormat(format);
                assertNull(MessageIdMarkup.refusal(message(shortId), null), format + " -> " + shortId);
            }
        }
    }

    @Test
    void aStoredShortIdThatHoldsMarkupIsKeptButANewOneIsRefused() {
        Message stored = message("IT<legacy>&id");

        assertNull(MessageIdMarkup.refusal(message("IT<legacy>&id"), stored),
                "a message saved before the rule stays editable");
        assertNull(MessageIdMarkup.refusal(message("NW-002-26"), stored), "replacing it with a valid id is fine");
        assertEquals("A message short id cannot contain <, >, \" or &",
                MessageIdMarkup.refusal(message("IT<legacy>&id2"), stored));
    }

    @Test
    void eachMarkupCharacterInANewReferencedMessageIdIsRefused() {
        for (String c : MARKUP) {
            assertEquals("A referenced message id cannot contain <, >, \" or &",
                    MessageIdMarkup.refusal(message("NW-001-26", "NW-002-26", "NW-003" + c + "26"), null), c);
        }
    }

    @Test
    void aReferencedMessageIdTheStoredMessageHasIsKept() {
        Message stored = message("NW-001-26", "IT\"legacy\"");

        assertNull(MessageIdMarkup.refusal(message("NW-001-26", "IT\"legacy\"", "NW-004-26"), stored));
        assertEquals("A referenced message id cannot contain <, >, \" or &",
                MessageIdMarkup.refusal(message("NW-001-26", "IT\"legacy\"", "IT<new>"), stored));
    }
}
