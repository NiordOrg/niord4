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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReferenceVo}, {@link ReferenceDescVo} and {@link ReferenceType}.
 *
 * These tests verify the REST/JSON value-object contract of the message reference model:
 * the {@code copy(DataFilter)} filtering semantics, the localized-description accessors
 * inherited from {@code ILocalizable}, and the description-entity behaviour from
 * {@code ILocalizedDesc}. They are plain object-logic tests (no framework wiring) so they
 * can survive a future migration away from Quarkus.
 */
public class ReferenceVoTest {

    /** Helper that builds a ReferenceVo with a description for each of the given languages. */
    private ReferenceVo newReference(String messageId, ReferenceType type, String... langs) {
        ReferenceVo ref = new ReferenceVo();
        ref.setMessageId(messageId);
        ref.setType(type);
        for (String lang : langs) {
            ReferenceDescVo desc = ref.createDesc(lang);
            desc.setDescription("desc-" + lang);
        }
        return ref;
    }

    // ------------------------------------------------------------------
    // Plain accessor round-trips
    // ------------------------------------------------------------------

    @Test
    public void messageIdAndTypeAccessorsRoundTrip() {
        ReferenceVo ref = new ReferenceVo();
        ref.setMessageId("NW-061-24");
        ref.setType(ReferenceType.CANCELLATION);

        assertEquals("NW-061-24", ref.getMessageId());
        assertEquals(ReferenceType.CANCELLATION, ref.getType());
    }

    @Test
    public void newReferenceHasNullDescsUntilCreated() {
        ReferenceVo ref = new ReferenceVo();
        // The descs list is lazily created; before any createDesc it must be null.
        assertNull(ref.getDescs());
    }

    // ------------------------------------------------------------------
    // createDesc / checkCreateDescs (inherited from ILocalizable)
    // ------------------------------------------------------------------

    @Test
    public void createDescAddsReferenceDescWithGivenLang() {
        ReferenceVo ref = new ReferenceVo();

        ReferenceDescVo desc = ref.createDesc("en");

        assertNotNull(desc);
        assertEquals("en", desc.getLang());
        // The created desc is appended to the (now lazily created) descs list.
        assertNotNull(ref.getDescs());
        assertEquals(1, ref.getDescs().size());
        assertSame(desc, ref.getDescs().get(0));
    }

    @Test
    public void createDescAppendsInOrder() {
        ReferenceVo ref = new ReferenceVo();

        ReferenceDescVo en = ref.createDesc("en");
        ReferenceDescVo da = ref.createDesc("da");

        assertEquals(2, ref.getDescs().size());
        assertSame(en, ref.getDescs().get(0));
        assertSame(da, ref.getDescs().get(1));
        assertEquals("en", ref.getDescs().get(0).getLang());
        assertEquals("da", ref.getDescs().get(1).getLang());
    }

    // ------------------------------------------------------------------
    // getDescs(String) lookup
    // ------------------------------------------------------------------

    @Test
    public void getDescByLangIsCaseInsensitive() {
        ReferenceVo ref = newReference("M1", ReferenceType.REFERENCE, "en", "da");

        ReferenceDescVo desc = ref.getDesc("EN");
        assertNotNull(desc);
        assertEquals("en", desc.getLang());
        assertEquals("desc-en", desc.getDescription());
    }

    @Test
    public void getDescByLangReturnsNullWhenNoMatchAndNoNullLangDesc() {
        ReferenceVo ref = newReference("M1", ReferenceType.REFERENCE, "en", "da");

        assertNull(ref.getDesc("fr"));
    }

    @Test
    public void getDescByLangMatchesNullLangDesc() {
        // A desc whose lang is null matches ANY requested language.
        ReferenceVo ref = new ReferenceVo();
        ReferenceDescVo nullLang = ref.createDesc(null);
        nullLang.setDescription("fallback");

        ReferenceDescVo desc = ref.getDesc("fr");
        assertSame(nullLang, desc);
    }

    // ------------------------------------------------------------------
    // copy(DataFilter) - core filtering contract
    // ------------------------------------------------------------------

    @Test
    public void copyWithoutLangFilterCopiesScalarsAndAllDescs() {
        ReferenceVo ref = newReference("M-100", ReferenceType.UPDATE, "en", "da");

        // A filter with no language: getDescs returns ALL descs.
        ReferenceVo copy = ref.copy(DataFilter.get());

        // Distinct instance.
        assertNotSame(ref, copy);
        // Scalars copied.
        assertEquals("M-100", copy.getMessageId());
        assertEquals(ReferenceType.UPDATE, copy.getType());
        // All descs present.
        assertNotNull(copy.getDescs());
        assertEquals(2, copy.getDescs().size());
        assertEquals("en", copy.getDescs().get(0).getLang());
        assertEquals("da", copy.getDescs().get(1).getLang());
    }

    @Test
    public void copyWithLangFilterKeepsOnlyMatchingDesc() {
        ReferenceVo ref = newReference("M-200", ReferenceType.REFERENCE, "en", "da");

        ReferenceVo copy = ref.copy(DataFilter.get().lang("da"));

        assertEquals("M-200", copy.getMessageId());
        assertEquals(ReferenceType.REFERENCE, copy.getType());
        // Only the Danish desc survives the lang filter.
        assertNotNull(copy.getDescs());
        assertEquals(1, copy.getDescs().size());
        assertEquals("da", copy.getDescs().get(0).getLang());
        assertEquals("desc-da", copy.getDescs().get(0).getDescription());
    }

    @Test
    public void copyProducesNewListWithFreshDescInstances() {
        // copy() deep-copies each desc via copyDescs(), like the sibling VOs (Area/Category/Attachment/Message).
        ReferenceVo ref = newReference("M-300", ReferenceType.REPETITION, "en");
        ReferenceDescVo originalEn = ref.getDesc("en");

        ReferenceVo copy = ref.copy(DataFilter.get().lang("en"));

        // The returned list is a NEW list instance...
        assertNotSame(ref.getDescs(), copy.getDescs());
        // ...and it holds a FRESH desc instance (deep copy), not the original — with identical content.
        assertNotSame(originalEn, copy.getDescs().get(0));
        assertEquals("en", copy.getDescs().get(0).getLang());
        assertEquals("desc-en", copy.getDescs().get(0).getDescription());

        // Mutating the copy's desc must not bleed back into the original.
        copy.getDescs().get(0).setDescription("changed");
        assertEquals("desc-en", originalEn.getDescription(), "original desc must be unaffected by the copy");
    }

    @Test
    public void copyWithNullDescsLeavesDescsNull_documentsBehavior() {
        // When descs == null, getDescs(filter) short-circuits to null (no NPE).
        ReferenceVo ref = new ReferenceVo();
        ref.setMessageId("M-400");
        ref.setType(ReferenceType.REPETITION_NEW_TIME);
        assertNull(ref.getDescs());

        ReferenceVo copy = ref.copy(DataFilter.get().lang("en"));

        assertEquals("M-400", copy.getMessageId());
        assertEquals(ReferenceType.REPETITION_NEW_TIME, copy.getType());
        assertNull(copy.getDescs());
    }

    @Test
    public void copyWithNullTypeCopiesNullType() {
        ReferenceVo ref = newReference("M-500", null, "en");

        ReferenceVo copy = ref.copy(DataFilter.get());

        assertEquals("M-500", copy.getMessageId());
        assertNull(copy.getType());
        assertEquals(1, copy.getDescs().size());
    }

    /**
     * Pins a surprising fallback behaviour of {@code ILocalizable.getDescs(DataFilter)} as
     * exercised through {@code ReferenceVo.copy}: when a language filter matches NO desc, the
     * method does not return an empty list. Instead it falls back to a singleton list holding
     * the FIRST desc - whose language is NOT the requested one. A consumer asking for "fr"
     * therefore receives the "en" description.
     */
    @Test
    public void copyWithUnmatchedLangFallsBackToFirstDesc_documentsBug() {
        ReferenceVo ref = newReference("M-600", ReferenceType.REFERENCE, "en", "da");

        ReferenceVo copy = ref.copy(DataFilter.get().lang("fr"));

        assertNotNull(copy.getDescs());
        assertEquals(1, copy.getDescs().size());
        // Fallback returns the FIRST desc ("en"), even though "fr" was requested.
        assertEquals("en", copy.getDescs().get(0).getLang());
        assertEquals("desc-en", copy.getDescs().get(0).getDescription());
    }

    @Test
    public void getDescsWithNullFilterReturnsRawList() {
        ReferenceVo ref = newReference("M-700", ReferenceType.REFERENCE, "en", "da");

        // Null filter short-circuits and returns the underlying list unchanged.
        List<ReferenceDescVo> descs = ref.getDescs((DataFilter) null);
        assertSame(ref.getDescs(), descs);
        assertEquals(2, descs.size());
    }

    @Test
    public void getDescsWithEmptyUnderlyingListReturnsEmpty() {
        // Empty (but non-null) descs with a lang filter: nothing to fall back to, so empty.
        ReferenceVo ref = new ReferenceVo();
        ref.setDescs(new ArrayList<>());

        List<ReferenceDescVo> descs = ref.getDescs(DataFilter.get().lang("en"));
        assertNotNull(descs);
        assertTrue(descs.isEmpty());
    }

    // ------------------------------------------------------------------
    // ReferenceDescVo behaviour
    // ------------------------------------------------------------------

    @Test
    public void descDefinedTrueWhenDescriptionNonBlank() {
        ReferenceDescVo desc = new ReferenceDescVo();
        desc.setLang("en");
        desc.setDescription("a reference");

        assertTrue(desc.descDefined());
    }

    @Test
    public void descDefinedFalseWhenDescriptionNull() {
        ReferenceDescVo desc = new ReferenceDescVo();
        desc.setLang("en");

        // descDefined ignores lang; only content fields count.
        assertFalse(desc.descDefined());
    }

    @Test
    public void descDefinedFalseWhenDescriptionEmpty() {
        ReferenceDescVo desc = new ReferenceDescVo();
        desc.setLang("en");
        desc.setDescription("");

        assertFalse(desc.descDefined());
    }

    @Test
    public void copyDescCopiesDescriptionButNotLang() {
        ReferenceDescVo source = new ReferenceDescVo();
        source.setLang("da");
        source.setDescription("kilde");

        ReferenceDescVo target = new ReferenceDescVo();
        target.setLang("en");

        target.copyDesc(source);

        // Description is copied across...
        assertEquals("kilde", target.getDescription());
        // ...but the language is preserved (copyDesc does NOT touch lang).
        assertEquals("en", target.getLang());
    }

    // ------------------------------------------------------------------
    // copyDescs / copyDescsAndRemoveBlanks (inherited from ILocalizable)
    // ------------------------------------------------------------------

    @Test
    public void copyDescsCreatesDescsForEachLangAndCopiesContent() {
        ReferenceVo source = newReference("S", ReferenceType.REFERENCE, "en", "da");

        ReferenceVo target = new ReferenceVo();
        target.copyDescs(source.getDescs());

        assertNotNull(target.getDescs());
        assertEquals(2, target.getDescs().size());
        assertEquals("desc-en", target.getDesc("en").getDescription());
        assertEquals("desc-da", target.getDesc("da").getDescription());
        // Targets are freshly created, not the source instances.
        assertNotSame(source.getDesc("en"), target.getDesc("en"));
    }

    @Test
    public void copyDescsAndRemoveBlanksDropsBlankDescs() {
        ReferenceVo source = new ReferenceVo();
        source.createDesc("en").setDescription("real");
        source.createDesc("da"); // blank: no description

        ReferenceVo target = new ReferenceVo();
        target.copyDescsAndRemoveBlanks(source.getDescs());

        // The blank "da" desc is removed after copying.
        assertEquals(1, target.getDescs().size());
        assertEquals("en", target.getDescs().get(0).getLang());
        assertEquals("real", target.getDescs().get(0).getDescription());
    }

    // ------------------------------------------------------------------
    // sortDescs (inherited from ILocalizable)
    // ------------------------------------------------------------------

    @Test
    public void sortDescsMovesRequestedLangToFront() {
        ReferenceVo ref = newReference("M", ReferenceType.REFERENCE, "en", "da", "de");

        ref.sortDescs("da");

        assertEquals("da", ref.getDescs().get(0).getLang());
        // The remaining langs are still present (size unchanged).
        assertEquals(3, ref.getDescs().size());
    }

    // ------------------------------------------------------------------
    // ReferenceType enum behaviour
    // ------------------------------------------------------------------

    @Test
    public void referenceTypeHasExpectedConstants() {
        ReferenceType[] values = ReferenceType.values();
        assertEquals(5, values.length);
        assertEquals(ReferenceType.REFERENCE, ReferenceType.valueOf("REFERENCE"));
        assertEquals(ReferenceType.REPETITION, ReferenceType.valueOf("REPETITION"));
        assertEquals(ReferenceType.REPETITION_NEW_TIME, ReferenceType.valueOf("REPETITION_NEW_TIME"));
        assertEquals(ReferenceType.CANCELLATION, ReferenceType.valueOf("CANCELLATION"));
        assertEquals(ReferenceType.UPDATE, ReferenceType.valueOf("UPDATE"));
    }

    @Test
    public void cancelsReferencedMessageContainsExactlyTheCancellingTypes() {
        java.util.Set<ReferenceType> cancels = ReferenceType.cancelsReferencedMessage();

        assertEquals(4, cancels.size());
        assertTrue(cancels.contains(ReferenceType.REPETITION));
        assertTrue(cancels.contains(ReferenceType.REPETITION_NEW_TIME));
        assertTrue(cancels.contains(ReferenceType.CANCELLATION));
        assertTrue(cancels.contains(ReferenceType.UPDATE));
        // Plain REFERENCE does NOT cancel the referenced message.
        assertFalse(cancels.contains(ReferenceType.REFERENCE));
    }

    @Test
    public void cancelsReferencedMessageReturnsFreshSetEachCall() {
        // Each call returns a NEW HashSet; mutating it must not affect later calls.
        java.util.Set<ReferenceType> first = ReferenceType.cancelsReferencedMessage();
        first.clear();

        java.util.Set<ReferenceType> second = ReferenceType.cancelsReferencedMessage();
        assertEquals(4, second.size());
    }
}
