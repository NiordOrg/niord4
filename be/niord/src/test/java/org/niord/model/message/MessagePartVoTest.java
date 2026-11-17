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

package org.niord.model.message;

import org.junit.jupiter.api.Test;
import org.niord.model.DataFilter;
import org.niord.model.geojson.FeatureCollectionVo;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 4 unit tests for {@link MessagePartVo}, the REST/JSON value-object for a message part.
 *
 * <p>These tests exercise the framework-neutral business behaviour of the VO: the {@link DataFilter}
 * driven {@code copy(...)} contract, repository-path rewriting, lazy event-date creation and the
 * simple accessor round-trips. They build their own deterministic objects (fixed epoch millis,
 * no current time / locale) and run independently of each other and of test order.</p>
 */
public class MessagePartVoTest {

    /** Fixed instants so assertions never depend on the wall clock or timezone. */
    private static final Date FROM = new Date(1_000_000L);
    private static final Date TO   = new Date(2_000_000L);

    /** Builds a DateIntervalVo with the given content. */
    private static DateIntervalVo interval(Boolean allDay, Date from, Date to) {
        DateIntervalVo iv = new DateIntervalVo();
        iv.setAllDay(allDay);
        iv.setFromDate(from);
        iv.setToDate(to);
        return iv;
    }

    /** Builds a MessagePartDescVo with the given content. */
    private static MessagePartDescVo desc(String lang, String subject, String details) {
        MessagePartDescVo d = new MessagePartDescVo();
        d.setLang(lang);
        d.setSubject(subject);
        d.setDetails(details);
        return d;
    }

    // ------------------------------------------------------------------
    // copy(DataFilter) - DETAILS field
    // ------------------------------------------------------------------

    @Test
    public void copyWithDetailsFilter_copiesTypeEventDatesDescsAndHideSubject() {
        MessagePartVo src = new MessagePartVo();
        src.setType(MessagePartType.TIME);
        src.setHideSubject(Boolean.TRUE);
        DateIntervalVo srcInterval = interval(Boolean.TRUE, FROM, TO);
        src.checkCreateEventDates().add(srcInterval);
        MessagePartDescVo srcDesc = desc("en", "subj", "det");
        src.checkCreateDescs().add(srcDesc);
        // Geometry present but NOT requested by the filter -> must be excluded.
        src.setGeometry(new FeatureCollectionVo());

        MessagePartVo copy = src.copy(DataFilter.get().fields(DataFilter.DETAILS));

        // A new, distinct instance is produced.
        assertNotSame(src, copy);

        // type is always copied.
        assertEquals(MessagePartType.TIME, copy.getType());

        // hideSubject copied.
        assertEquals(Boolean.TRUE, copy.getHideSubject());

        // eventDates copied as a NEW list of NEW DateIntervalVo instances with equal field values.
        assertNotNull(copy.getEventDates());
        assertEquals(1, copy.getEventDates().size());
        DateIntervalVo copiedInterval = copy.getEventDates().get(0);
        assertNotSame(srcInterval, copiedInterval, "copy() must build new DateIntervalVo instances");
        assertEquals(Boolean.TRUE, copiedInterval.getAllDay());
        assertEquals(FROM, copiedInterval.getFromDate());
        assertEquals(TO, copiedInterval.getToDate());

        // descs come from getDescs(filter); with a single matching desc that desc is present.
        assertNotNull(copy.getDescs());
        assertEquals(1, copy.getDescs().size());
        MessagePartDescVo copiedDesc = copy.getDescs().get(0);
        assertEquals("en", copiedDesc.getLang());
        assertEquals("subj", copiedDesc.getSubject());
        assertEquals("det", copiedDesc.getDetails());

        // GEOMETRY field not requested -> geometry stays null on the copy.
        assertNull(copy.getGeometry());
    }

    @Test
    public void copyWithDetailsFilter_deepCopiesDescsSoMutationDoesNotBleedBack() {
        // copy() deep-copies each desc via copyDescs(), like the sibling VOs
        // (Area/Category/Attachment/Reference/Message) -- the copy must NOT share the
        // original's MessagePartDescVo instances (pins the corrected M4 behaviour).
        MessagePartVo src = new MessagePartVo();
        src.setType(MessagePartType.NOTE);
        MessagePartDescVo srcDesc = desc("en", "subj", "det");
        src.checkCreateDescs().add(srcDesc);

        MessagePartVo copy = src.copy(DataFilter.get().fields(DataFilter.DETAILS));

        // A NEW descs list holding a FRESH desc instance (deep copy), not the original's.
        assertNotSame(src.getDescs(), copy.getDescs(), "copy() must build a new descs list");
        MessagePartDescVo copiedDesc = copy.getDescs().get(0);
        assertNotSame(srcDesc, copiedDesc, "copy() must deep-copy each desc, not share the instance");
        // ...with identical content.
        assertEquals("en", copiedDesc.getLang());
        assertEquals("subj", copiedDesc.getSubject());
        assertEquals("det", copiedDesc.getDetails());

        // Mutating the copy's desc must not bleed back into the original.
        copiedDesc.setSubject("changed");
        assertEquals("subj", srcDesc.getSubject(), "original desc must be unaffected by mutating the copy");
    }

    @Test
    public void copyWithDetailsFilter_andLang_selectsMatchingLanguageDesc() {
        MessagePartVo src = new MessagePartVo();
        src.setType(MessagePartType.NOTE);
        src.checkCreateDescs().add(desc("en", "english", "english-details"));
        src.checkCreateDescs().add(desc("da", "dansk", "dansk-detaljer"));

        MessagePartVo copy = src.copy(DataFilter.get().lang("da").fields(DataFilter.DETAILS));

        // Only the Danish description should survive the language filter.
        assertNotNull(copy.getDescs());
        assertEquals(1, copy.getDescs().size());
        assertEquals("da", copy.getDescs().get(0).getLang());
        assertEquals("dansk", copy.getDescs().get(0).getSubject());
    }

    @Test
    public void copyWithDetailsFilter_noLangMatch_fallsBackToFirstDesc() {
        MessagePartVo src = new MessagePartVo();
        src.setType(MessagePartType.NOTE);
        src.checkCreateDescs().add(desc("en", "english", null));
        src.checkCreateDescs().add(desc("da", "dansk", null));

        // No desc in language "fr" -> getDescs(filter) falls back to a singleton of the first desc.
        MessagePartVo copy = src.copy(DataFilter.get().lang("fr").fields(DataFilter.DETAILS));

        assertNotNull(copy.getDescs());
        assertEquals(1, copy.getDescs().size());
        assertEquals("en", copy.getDescs().get(0).getLang());
    }

    @Test
    public void copyWithDetailsFilter_nullEventDates_leavesCopyEventDatesNull() {
        MessagePartVo src = new MessagePartVo();
        src.setType(MessagePartType.SIGNALS);
        // No eventDates set on the source.

        MessagePartVo copy = src.copy(DataFilter.get().fields(DataFilter.DETAILS));

        // copy() guards on eventDates != null, so it must not create an (empty) list.
        assertNull(copy.getEventDates());
        // No descs on source -> getDescs(filter) returns null -> copy descs null.
        assertNull(copy.getDescs());
        assertEquals(MessagePartType.SIGNALS, copy.getType());
    }

    // ------------------------------------------------------------------
    // copy(DataFilter) - GEOMETRY field
    // ------------------------------------------------------------------

    @Test
    public void copyWithGeometryFilter_copiesGeometryButNotDetails() {
        MessagePartVo src = new MessagePartVo();
        src.setType(MessagePartType.POSITIONS);
        src.setHideSubject(Boolean.TRUE);
        src.checkCreateEventDates().add(interval(Boolean.FALSE, FROM, TO));
        src.checkCreateDescs().add(desc("en", "subj", "det"));

        FeatureCollectionVo geometry = new FeatureCollectionVo();
        geometry.setId("geo-1");
        src.setGeometry(geometry);

        MessagePartVo copy = src.copy(DataFilter.get().fields(DataFilter.GEOMETRY));

        assertEquals(MessagePartType.POSITIONS, copy.getType());

        // Geometry copied. The source has a "TODO: deep-copy" note, so the same reference is acceptable.
        assertNotNull(copy.getGeometry());
        assertSame(geometry, copy.getGeometry());
        assertEquals("geo-1", copy.getGeometry().getId());

        // DETAILS field not requested -> none of the detail fields are copied.
        assertNull(copy.getEventDates());
        assertNull(copy.getDescs());
        assertNull(copy.getHideSubject());
    }

    @Test
    public void copyWithGeometryFilter_nullGeometry_leavesCopyGeometryNull() {
        MessagePartVo src = new MessagePartVo();
        src.setType(MessagePartType.POSITIONS);
        // geometry remains null.

        MessagePartVo copy = src.copy(DataFilter.get().fields(DataFilter.GEOMETRY));

        // copy() guards on geometry != null, so nothing is set.
        assertNull(copy.getGeometry());
        assertEquals(MessagePartType.POSITIONS, copy.getType());
    }

    // ------------------------------------------------------------------
    // copy(DataFilter) - minimal filter (no DETAILS, no GEOMETRY)
    // ------------------------------------------------------------------

    @Test
    public void copyWithMinimalFilter_copiesOnlyType() {
        MessagePartVo src = new MessagePartVo();
        src.setType(MessagePartType.PROHIBITION);
        src.setHideSubject(Boolean.TRUE);
        src.checkCreateEventDates().add(interval(Boolean.TRUE, FROM, TO));
        src.checkCreateDescs().add(desc("en", "subj", "det"));
        src.setGeometry(new FeatureCollectionVo());

        MessagePartVo copy = src.copy(DataFilter.get());

        assertNotSame(src, copy);
        // type is always copied.
        assertEquals(MessagePartType.PROHIBITION, copy.getType());
        // Everything gated behind DETAILS / GEOMETRY is excluded.
        assertNull(copy.getEventDates());
        assertNull(copy.getDescs());
        assertNull(copy.getHideSubject());
        assertNull(copy.getGeometry());
    }

    @Test
    public void copyKeepsTheIndexNo() {
        // The part's place in its message is published as indexNo (MSG7): the public API copies every
        // message, so a copy that drops the number turns every part into number 0.
        MessagePartVo src = new MessagePartVo();
        src.setType(MessagePartType.NOTE);
        src.setIndexNo(3);

        assertEquals(3, src.copy(DataFilter.get()).getIndexNo(), "the minimal filter keeps the number");
        assertEquals(3, src.copy(DataFilter.get().fields(DataFilter.DETAILS)).getIndexNo());
        assertEquals(3, src.copy(DataFilter.get().fields(DataFilter.GEOMETRY)).getIndexNo());
    }

    @Test
    public void copyWithComponentPrefixedDetailsField_isStrippedByForComponent() {
        MessagePartVo src = new MessagePartVo();
        src.setType(MessagePartType.DETAILS);
        src.setHideSubject(Boolean.FALSE);
        src.checkCreateDescs().add(desc("en", "subj", "det"));

        // "MessagePart.details" should be recognised by copy() because forComponent("MessagePart")
        // strips the "MessagePart." prefix, yielding the plain "details" field.
        MessagePartVo copy = src.copy(DataFilter.get().fields("MessagePart." + DataFilter.DETAILS));

        assertEquals(MessagePartType.DETAILS, copy.getType());
        assertEquals(Boolean.FALSE, copy.getHideSubject());
        assertNotNull(copy.getDescs());
        assertEquals(1, copy.getDescs().size());
        assertEquals("subj", copy.getDescs().get(0).getSubject());
    }

    // ------------------------------------------------------------------
    // createDesc(String)
    // ------------------------------------------------------------------

    @Test
    public void createDesc_appendsNewDescWithGivenLang() {
        MessagePartVo part = new MessagePartVo();

        MessagePartDescVo created = part.createDesc("en");

        assertEquals("en", created.getLang());
        assertNull(created.getSubject());
        assertNull(created.getDetails());

        // The created desc is appended to the (lazily created) descs list.
        assertNotNull(part.getDescs());
        assertEquals(1, part.getDescs().size());
        assertSame(created, part.getDescs().get(0));
    }

    @Test
    public void createDesc_calledTwice_appendsBoth() {
        MessagePartVo part = new MessagePartVo();

        MessagePartDescVo en = part.createDesc("en");
        MessagePartDescVo da = part.createDesc("da");

        assertEquals(2, part.getDescs().size());
        assertSame(en, part.getDescs().get(0));
        assertSame(da, part.getDescs().get(1));
        assertEquals("en", part.getDescs().get(0).getLang());
        assertEquals("da", part.getDescs().get(1).getLang());
    }

    // ------------------------------------------------------------------
    // rewriteRepoPath(String, String)
    // ------------------------------------------------------------------

    @Test
    public void rewriteRepoPath_replacesMatchingPathInDetails() {
        MessagePartVo part = new MessagePartVo();
        part.checkCreateDescs().add(desc("en", "subj", "see /old/x"));

        part.rewriteRepoPath("/old", "/new");

        assertEquals("see /new/x", part.getDescs().get(0).getDetails());
    }

    @Test
    public void rewriteRepoPath_replacesEveryOccurrence() {
        MessagePartVo part = new MessagePartVo();
        part.checkCreateDescs().add(desc("en", "subj", "img /old/a and link /old/b"));

        part.rewriteRepoPath("/old", "/new");

        // String.replace replaces all occurrences.
        assertEquals("img /new/a and link /new/b", part.getDescs().get(0).getDetails());
    }

    @Test
    public void rewriteRepoPath_detailsWithoutPath_isUnchanged() {
        MessagePartVo part = new MessagePartVo();
        part.checkCreateDescs().add(desc("en", "subj", "see /other/x"));

        part.rewriteRepoPath("/old", "/new");

        assertEquals("see /other/x", part.getDescs().get(0).getDetails());
    }

    @Test
    public void rewriteRepoPath_nullDetails_isNoOp() {
        MessagePartVo part = new MessagePartVo();
        part.checkCreateDescs().add(desc("en", "subj", null));

        // Must not throw on a desc whose details is null.
        part.rewriteRepoPath("/old", "/new");

        assertNull(part.getDescs().get(0).getDetails());
    }

    @Test
    public void rewriteRepoPath_nullDescs_isNoOp() {
        MessagePartVo part = new MessagePartVo();
        // descs is null; the method guards on getDescs() != null.

        part.rewriteRepoPath("/old", "/new");

        // No descs were created as a side effect, and no NPE was thrown.
        assertNull(part.getDescs());
    }

    @Test
    public void rewriteRepoPath_onlyAffectsDescsContainingThePath() {
        MessagePartVo part = new MessagePartVo();
        part.checkCreateDescs().add(desc("en", "subj", "see /old/x"));
        part.checkCreateDescs().add(desc("da", "emne", "ingen sti"));

        part.rewriteRepoPath("/old", "/new");

        assertEquals("see /new/x", part.getDescs().get(0).getDetails());
        assertEquals("ingen sti", part.getDescs().get(1).getDetails());
    }

    // ------------------------------------------------------------------
    // checkCreateEventDates()
    // ------------------------------------------------------------------

    @Test
    public void checkCreateEventDates_createsEmptyListWhenNull() {
        MessagePartVo part = new MessagePartVo();
        assertNull(part.getEventDates());

        List<DateIntervalVo> dates = part.checkCreateEventDates();

        assertNotNull(dates);
        assertTrue(dates.isEmpty());
        // The created list is stored on the instance.
        assertSame(dates, part.getEventDates());
    }

    @Test
    public void checkCreateEventDates_returnsSameInstanceWhenAlreadySet() {
        MessagePartVo part = new MessagePartVo();
        DateIntervalVo existing = interval(Boolean.TRUE, FROM, TO);
        part.setEventDates(new java.util.ArrayList<>(Arrays.asList(existing)));
        List<DateIntervalVo> firstCall = part.getEventDates();

        List<DateIntervalVo> secondCall = part.checkCreateEventDates();

        // It must return the existing list untouched, not replace it.
        assertSame(firstCall, secondCall);
        assertEquals(1, secondCall.size());
        assertSame(existing, secondCall.get(0));
    }

    // ------------------------------------------------------------------
    // Accessor round-trips
    // ------------------------------------------------------------------

    @Test
    public void indexNoAccessor_roundTrips() {
        MessagePartVo part = new MessagePartVo();
        assertEquals(0, part.getIndexNo());

        part.setIndexNo(42);
        assertEquals(42, part.getIndexNo());
    }

    @Test
    public void typeAccessor_roundTrips() {
        MessagePartVo part = new MessagePartVo();
        assertNull(part.getType());

        part.setType(MessagePartType.SIGNALS);
        assertEquals(MessagePartType.SIGNALS, part.getType());
    }

    @Test
    public void hideSubjectAccessor_roundTrips() {
        MessagePartVo part = new MessagePartVo();
        assertNull(part.getHideSubject());

        part.setHideSubject(Boolean.TRUE);
        assertEquals(Boolean.TRUE, part.getHideSubject());

        part.setHideSubject(Boolean.FALSE);
        assertEquals(Boolean.FALSE, part.getHideSubject());
    }

    // ------------------------------------------------------------------
    // DateIntervalVo.copy() and containsDate(...) - exercised through the part's helpers
    // ------------------------------------------------------------------

    @Test
    public void dateIntervalCopy_producesDistinctInstanceWithEqualFields() {
        DateIntervalVo src = interval(Boolean.TRUE, FROM, TO);

        DateIntervalVo copy = src.copy();

        assertNotSame(src, copy);
        assertEquals(Boolean.TRUE, copy.getAllDay());
        assertEquals(FROM, copy.getFromDate());
        assertEquals(TO, copy.getToDate());
    }

    @Test
    public void dateIntervalContainsDate_withinBothBounds_isTrueExclusiveOfBounds() {
        DateIntervalVo iv = interval(Boolean.FALSE, FROM, TO);

        assertTrue(iv.containsDate(new Date(1_500_000L)));
        // Boundaries are exclusive: after(from) and before(to).
        assertFalse(iv.containsDate(FROM));
        assertFalse(iv.containsDate(TO));
        assertFalse(iv.containsDate(new Date(500_000L)));
        assertFalse(iv.containsDate(new Date(2_500_000L)));
    }

    @Test
    public void dateIntervalContainsDate_noBounds_isAlwaysFalse() {
        DateIntervalVo iv = interval(null, null, null);

        assertFalse(iv.containsDate(new Date(1_500_000L)));
    }
}
