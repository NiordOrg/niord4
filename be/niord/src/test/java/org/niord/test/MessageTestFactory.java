/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.test;

import org.niord.core.message.Attachment;
import org.niord.core.message.DateInterval;
import org.niord.core.message.Message;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.Reference;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.ReferenceType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;

import java.time.Instant;
import java.util.Date;

/**
 * Shared builders for detached {@link Message}/{@link MessageSeries} entities in integration tests.
 *
 * <p>Consolidates the {@code newDraft(...)} helper methods duplicated across the message/api/export
 * ITs into one place. The returned message is a <em>detached</em> {@code DRAFT} with a single
 * {@code DETAILS} part and a title, {@code autoTitle=false} (so title post-processing does not require
 * a message series number), and a deterministic far-past publish date (see {@link TestClock}) so a
 * later publish does not collide with year-specific number sequences. Callers persist it via
 * {@code messageService.createMessage(..)}.
 */
public final class MessageTestFactory {

    /** The first event date of {@link #draftNwWithTwoOfEach} */
    public static final Date FIRST_EVENT_DATE = Date.from(Instant.parse("2026-01-05T08:00:00Z"));

    /** The second event date of {@link #draftNwWithTwoOfEach} */
    public static final Date SECOND_EVENT_DATE = Date.from(Instant.parse("2026-02-05T08:00:00Z"));

    private MessageTestFactory() {
    }

    /** A detached message series with the given id and main type. */
    public static MessageSeries series(String seriesId, MainType mainType) {
        MessageSeries series = new MessageSeries(seriesId);
        series.setMainType(mainType);
        return series;
    }

    /**
     * A detached DRAFT message for the given series and type, carrying one DETAILS part (subject +
     * details in {@code en}) and an {@code en} title.
     */
    public static Message draft(String seriesId, Type type, String title) {
        Message msg = new Message();
        msg.setMessageSeries(series(seriesId, type.getMainType()));
        msg.setType(type);
        msg.setStatus(Status.DRAFT);
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(TestClock.pastDate());

        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc partDesc = part.createDesc("en");
        partDesc.setSubject("subject");
        partDesc.setDetails("<p>details</p>");
        msg.addPart(part);

        msg.createDesc("en").setTitle(title);
        return msg;
    }

    /** Convenience: a DRAFT local navigational warning on the seeded {@code dma-nw} series. */
    public static Message draftNw(String title) {
        return draft("dma-nw", Type.LOCAL_WARNING, title);
    }

    /**
     * A DRAFT local navigational warning on {@code dma-nw} with two of each ordered list: the parts "subject" and
     * "subject 2", the first part's event dates {@link #FIRST_EVENT_DATE} and {@link #SECOND_EVENT_DATE}, the
     * references {@code NW-001-26} and {@code NW-002-26}, and the attachments {@code first.txt} and
     * {@code second.txt}, each list in that order. Each of these lists keeps its order in an {@code indexNo}
     * column (an {@code @OrderColumn}); a message with one element per list cannot show a list stored with
     * every index 0 (which a reload folds into one element), so a test of a stored message's lists builds on
     * this one.
     */
    public static Message draftNwWithTwoOfEach(String title) {
        Message msg = draftNw(title);
        MessagePart first = msg.getParts().get(0);
        first.addEventDates(new DateInterval(false, FIRST_EVENT_DATE, null));
        first.addEventDates(new DateInterval(false, SECOND_EVENT_DATE, null));

        MessagePart second = new MessagePart(MessagePartType.DETAILS);
        second.createDesc("en").setSubject("subject 2");
        msg.addPart(second);

        for (String messageId : new String[] { "NW-001-26", "NW-002-26" }) {
            Reference reference = new Reference();
            reference.setMessageId(messageId);
            reference.setType(ReferenceType.REFERENCE);
            msg.addReference(reference);
        }
        for (String fileName : new String[] { "first.txt", "second.txt" }) {
            Attachment attachment = new Attachment();
            attachment.setFileName(fileName);
            attachment.setType("text/plain");
            msg.addAttachment(attachment);
        }
        return msg;
    }
}
