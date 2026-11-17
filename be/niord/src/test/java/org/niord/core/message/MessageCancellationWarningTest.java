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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.area.Area;
import org.niord.core.category.Category;
import org.niord.core.chart.Chart;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.model.message.MainType;
import org.niord.model.message.ReferenceType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the composition and gating of auto-created S-124 cancellation warnings
 * (phase 2 of {@code docs/s124-cancellation-plan.md}):
 * <ul>
 *     <li>{@code MessageService.composeCancellationWarning} - the field shape of the
 *         cancellation-warning template (kind/type/series/reference/titles)</li>
 *     <li>{@code MessageService.issueCancellationWarning} - the gating, including the terminal
 *         rule (never a cancellation-of-a-cancellation) and the skip for series that cannot
 *         number a cancellation warning. Cancellation warnings are not optional: there is no
 *         per-message request and no per-series flag to switch them off</li>
 * </ul>
 *
 * The end-to-end flow (series numbering, persistence, REST routing, SECOM datasets) is covered
 * by {@code MessageCancellationWarningITTest}.
 */
public class MessageCancellationWarningTest {

    private MessageService messageService;
    private DictionaryService dictionaryService;

    @BeforeEach
    public void setUp() {
        messageService = new MessageService();
        dictionaryService = mock(DictionaryService.class);
        NiordApp app = mock(NiordApp.class);
        when(app.getLanguages()).thenReturn(new String[] { "en", "da" });
        messageService.dictionaryService = dictionaryService;
        messageService.app = app;
    }

    /** A detached message series with the given main type and numbering */
    private MessageSeries series(MainType mainType, NumberSequenceType numberSequenceType) {
        MessageSeries series = new MessageSeries("dma-nw");
        series.setMainType(mainType);
        series.setNumberSequenceType(numberSequenceType);
        return series;
    }

    /** A just-cancelled NW warning in an auto-numbered series - the minting case */
    private Message cancelledNw() {
        Message message = new Message();
        message.setUid("11ecb8e0-a234-4de8-9c88-1dd0e4f4c11d");
        message.setMessageSeries(series(MainType.NW, NumberSequenceType.YEARLY));
        message.setMainType(MainType.NW);
        message.setType(Type.COASTAL_WARNING);
        message.setStatus(Status.CANCELLED);
        message.setNumber(14);
        message.setShortId("NW-014-17");
        message.getAreas().add(new Area());
        message.getCategories().add(new Category());
        message.getCharts().add(new Chart());
        return message;
    }

    // ------------------------------------------------------------------------------------------- //
    // composeCancellationWarning - field shape
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void composedCancellationWarningHasTheCancellationOnlyShape() {
        when(dictionaryService.value("message", "en", "msg.cancellation.title")).thenReturn("Cancellation of {0}");
        when(dictionaryService.value("message", "da", "msg.cancellation.title")).thenReturn("Annullering af {0}");

        Message source = cancelledNw();
        Message cancellation = messageService.composeCancellationWarning(source);

        assertEquals(MessageKind.CANCELLATION, cancellation.getKind());
        assertSame(source.getMessageSeries(), cancellation.getMessageSeries());
        assertEquals(MainType.NW, cancellation.getMainType());
        // The scope (type) is copied - it drives the S-124 warningType of the cancellation dataset
        assertEquals(Type.COASTAL_WARNING, cancellation.getType());
        // Born published: the cancellation warning never passes through the editorial workflow
        assertEquals(Status.PUBLISHED, cancellation.getStatus());
        assertNotNull(cancellation.getPublishDateFrom());
        // In force until manually retired (design decision 1 of the plan)
        assertNull(cancellation.getPublishDateTo());
        // The number/short ID are assigned from the series sequence by the publish steps
        assertNull(cancellation.getNumber());
        assertNull(cancellation.getShortId());
        // Cancellation-only shape: no message parts - the CANCELLATION reference carries the meaning
        assertTrue(cancellation.getParts().isEmpty());
        assertEquals(Boolean.TRUE, cancellation.getOriginalInformation());
        assertFalse(cancellation.isAutoTitle());

        // Areas, categories and charts are copied from the source into fresh lists
        assertEquals(source.getAreas(), cancellation.getAreas());
        assertNotSame(source.getAreas(), cancellation.getAreas());
        assertEquals(source.getCategories(), cancellation.getCategories());
        assertNotSame(source.getCategories(), cancellation.getCategories());
        assertEquals(source.getCharts(), cancellation.getCharts());
        assertNotSame(source.getCharts(), cancellation.getCharts());

        // A single CANCELLATION reference points at the source by its (stable) UID
        assertEquals(1, cancellation.getReferences().size());
        Reference reference = cancellation.getReferences().get(0);
        assertEquals(source.getUid(), reference.getMessageId());
        assertEquals(ReferenceType.CANCELLATION, reference.getType());

        // Explicit titles for every app language, resolved from the message dictionary
        assertEquals(2, cancellation.getDescs().size());
        assertEquals("Cancellation of NW-014-17", cancellation.getDesc("en").getTitle());
        assertEquals("Annullering af NW-014-17", cancellation.getDesc("da").getTitle());
    }

    @Test
    public void composedTitlesFallBackToTheEnglishDictionaryEntry() {
        // No Danish entry defined - the English pattern is used for the Danish title too
        when(dictionaryService.value("message", "en", "msg.cancellation.title")).thenReturn("Cancelled: {0}");

        Message cancellation = messageService.composeCancellationWarning(cancelledNw());

        assertEquals("Cancelled: NW-014-17", cancellation.getDesc("en").getTitle());
        assertEquals("Cancelled: NW-014-17", cancellation.getDesc("da").getTitle());
    }

    @Test
    public void composedTitlesFallBackToAFixedTextWithoutDictionaryEntries() {
        // The dictionary mock returns null for all entries - the fixed English text is used
        Message cancellation = messageService.composeCancellationWarning(cancelledNw());

        assertEquals("Cancellation of NW-014-17", cancellation.getDesc("en").getTitle());
        assertEquals("Cancellation of NW-014-17", cancellation.getDesc("da").getTitle());
    }

    @Test
    public void composedTitleFallsBackToTheUidWhenTheShortIdIsMissing() {
        Message source = cancelledNw();
        source.setShortId(null);

        Message cancellation = messageService.composeCancellationWarning(source);

        assertEquals("Cancellation of " + source.getUid(), cancellation.getDesc("en").getTitle());
    }

    // ------------------------------------------------------------------------------------------- //
    // issueCancellationWarning - gating
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void cancellationWarningsAreIssuedUnconditionally() {
        // There is no opt-out - no per-message request and no per-series flag: an ordinary NW
        // warning of an auto-numbered series always mints
        Message message = cancelledNw();
        assertTrue(messageService.issueCancellationWarning(message));

        // Defensive: a message without a series cannot be numbered, so nothing is minted
        message.setMessageSeries(null);
        assertFalse(messageService.issueCancellationWarning(message));
    }

    @Test
    public void willIssueCancellationWarningIsThePureRuleBehindTheGating() {
        // The editor-facing predicate (drives SystemMessageVo.autoCancellationWarning and the
        // cancel-dialog note) must agree with the cancel-time gating in every case
        Message message = cancelledNw();
        assertTrue(messageService.willIssueCancellationWarning(message));

        message.setKind(MessageKind.CANCELLATION);
        assertFalse(messageService.willIssueCancellationWarning(message));

        Message nm = cancelledNw();
        nm.setMainType(MainType.NM);
        assertFalse(messageService.willIssueCancellationWarning(nm));

        Message manual = cancelledNw();
        manual.getMessageSeries().setNumberSequenceType(NumberSequenceType.MANUAL);
        assertFalse(messageService.willIssueCancellationWarning(manual));

        Message seriesless = cancelledNw();
        seriesless.setMessageSeries(null);
        assertFalse(messageService.willIssueCancellationWarning(seriesless));
    }

    @Test
    public void terminalRuleNeverMintsForNonWarningKinds() {
        // Never a cancellation-of-a-cancellation: cancelling a CANCELLATION message remains
        // allowed, but it must not trigger another auto-created one
        Message message = cancelledNw();

        message.setKind(MessageKind.CANCELLATION);
        assertFalse(messageService.issueCancellationWarning(message));

        message.setKind(MessageKind.BULLETIN);
        assertFalse(messageService.issueCancellationWarning(message));
    }

    @Test
    public void mintingIsSkippedForSeriesThatCannotCarryCancellationWarnings() {
        // The cancellation warning is an NW message taking the next number in the series, so a
        // non-NW or non-auto-numbered series mints nothing - cancelMessage skips, never fails
        Message nm = cancelledNw();
        nm.setMainType(MainType.NM);
        assertFalse(messageService.issueCancellationWarning(nm));

        Message manual = cancelledNw();
        manual.getMessageSeries().setNumberSequenceType(NumberSequenceType.MANUAL);
        assertFalse(messageService.issueCancellationWarning(manual));

        Message none = cancelledNw();
        none.getMessageSeries().setNumberSequenceType(NumberSequenceType.NONE);
        assertFalse(messageService.issueCancellationWarning(none));

        // Both auto-numbered sequence types support minting
        Message continuous = cancelledNw();
        continuous.getMessageSeries().setNumberSequenceType(NumberSequenceType.CONTINUOUS);
        assertTrue(messageService.issueCancellationWarning(continuous));
    }

    @Test
    public void autoNumberedCoversYearlyAndContinuousSequences() {
        assertTrue(series(MainType.NW, NumberSequenceType.YEARLY).isAutoNumbered());
        assertTrue(series(MainType.NW, NumberSequenceType.CONTINUOUS).isAutoNumbered());
        assertFalse(series(MainType.NW, NumberSequenceType.MANUAL).isAutoNumbered());
        assertFalse(series(MainType.NW, NumberSequenceType.NONE).isAutoNumbered());
    }
}
