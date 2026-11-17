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
 * Unit tests for {@link AttachmentVo} and its localized description
 * {@link AttachmentDescVo}.
 *
 * These tests target the REST/JSON value-object contract and the
 * localization/copy behaviour inherited from {@code ILocalizable} /
 * {@code ILocalizedDesc}. They are framework-neutral plain JUnit 4 tests
 * (no Quarkus, no mocks) and assert concrete copied state, list contents,
 * enum mappings and thrown exceptions so they survive a future Spring port.
 *
 * The test lives in the {@code org.niord.model.message} package so that the
 * package-private scalar fields of {@link AttachmentVo} remain reachable for
 * white-box assertions if needed; the tests deliberately use the public
 * accessors.
 */
public class AttachmentVoTest {

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Builds a fully-populated AttachmentVo with two descs (en + da). */
    private static AttachmentVo fullAttachment() {
        AttachmentVo a = new AttachmentVo();
        a.setType("image/png");
        a.setPath("/old/a.pdf");
        a.setFileName("a.pdf");
        a.setFileSize(123L);
        a.setDisplay(AttachmentVo.AttachmentDisplayType.SEPARATE_PAGE);
        a.setWidth("640");
        a.setHeight("480");

        AttachmentDescVo en = a.createDesc("en");
        en.setCaption("English caption");
        AttachmentDescVo da = a.createDesc("da");
        da.setCaption("Dansk billedtekst");
        return a;
    }

    // ---------------------------------------------------------------------
    // AttachmentDisplayType enum
    // ---------------------------------------------------------------------

    @Test
    public void displayType_hasExactlyThreeOrderedValues() {
        AttachmentVo.AttachmentDisplayType[] values = AttachmentVo.AttachmentDisplayType.values();
        assertEquals(3, values.length, "AttachmentDisplayType must declare exactly 3 constants");
        assertEquals(AttachmentVo.AttachmentDisplayType.ABOVE, values[0]);
        assertEquals(AttachmentVo.AttachmentDisplayType.BELOW, values[1]);
        assertEquals(AttachmentVo.AttachmentDisplayType.SEPARATE_PAGE, values[2]);
    }

    @Test
    public void displayType_valueOfRoundTripsByName() {
        assertSame(AttachmentVo.AttachmentDisplayType.ABOVE,
                AttachmentVo.AttachmentDisplayType.valueOf("ABOVE"));
        assertSame(AttachmentVo.AttachmentDisplayType.BELOW,
                AttachmentVo.AttachmentDisplayType.valueOf("BELOW"));
        assertSame(AttachmentVo.AttachmentDisplayType.SEPARATE_PAGE,
                AttachmentVo.AttachmentDisplayType.valueOf("SEPARATE_PAGE"));
    }

    @Test
    public void displayType_valueOfUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> AttachmentVo.AttachmentDisplayType.valueOf("INLINE"));
    }

    // ---------------------------------------------------------------------
    // Getters / setters (state, not just call-through)
    // ---------------------------------------------------------------------

    @Test
    public void scalarAccessors_storeAndReturnValues() {
        AttachmentVo a = new AttachmentVo();
        a.setType("application/pdf");
        a.setPath("/repo/x");
        a.setFileName("x.pdf");
        a.setFileSize(42L);
        a.setDisplay(AttachmentVo.AttachmentDisplayType.ABOVE);
        a.setWidth("100");
        a.setHeight("200");

        assertEquals("application/pdf", a.getType());
        assertEquals("/repo/x", a.getPath());
        assertEquals("x.pdf", a.getFileName());
        assertEquals(Long.valueOf(42L), a.getFileSize());
        assertEquals(AttachmentVo.AttachmentDisplayType.ABOVE, a.getDisplay());
        assertEquals("100", a.getWidth());
        assertEquals("200", a.getHeight());
    }

    @Test
    public void descs_areNullByDefaultUntilCreated() {
        AttachmentVo a = new AttachmentVo();
        assertNull(a.getDescs(), "descs list must be null before any desc is created");
    }

    // ---------------------------------------------------------------------
    // createDesc(lang)
    // ---------------------------------------------------------------------

    @Test
    public void createDesc_addsDescWithGivenLangAndLazilyCreatesList() {
        AttachmentVo a = new AttachmentVo();

        AttachmentDescVo desc = a.createDesc("en");

        assertNotNull(a.getDescs(), "createDesc must lazily create the descs list");
        assertEquals(1, a.getDescs().size(), "descs list must contain exactly the created desc");
        assertSame(desc, a.getDescs().get(0), "returned desc must be the one stored in the list");
        assertEquals("en", desc.getLang());
        assertNull(desc.getCaption(), "a freshly created desc has no caption");
    }

    @Test
    public void createDesc_appendsAdditionalDescsPreservingOrder() {
        AttachmentVo a = new AttachmentVo();

        AttachmentDescVo en = a.createDesc("en");
        AttachmentDescVo da = a.createDesc("da");

        assertEquals(2, a.getDescs().size());
        assertSame(en, a.getDescs().get(0));
        assertSame(da, a.getDescs().get(1));
        assertEquals("en", a.getDescs().get(0).getLang());
        assertEquals("da", a.getDescs().get(1).getLang());
    }

    // ---------------------------------------------------------------------
    // copy(DataFilter) -- scalars always copied
    // ---------------------------------------------------------------------

    @Test
    public void copy_returnsDistinctInstanceAndCopiesAllScalarsEvenWithMinimalFilter() {
        AttachmentVo original = fullAttachment();

        // A filter that includes NO fields at all: copy() still copies all scalars,
        // because copy() copies scalars unconditionally (independent of includeDetails()).
        AttachmentVo copy = original.copy(DataFilter.get());

        assertNotSame(original, copy, "copy() must return a new instance");
        assertEquals("image/png", copy.getType());
        assertEquals("/old/a.pdf", copy.getPath());
        assertEquals("a.pdf", copy.getFileName());
        assertEquals(Long.valueOf(123L), copy.getFileSize());
        assertEquals(AttachmentVo.AttachmentDisplayType.SEPARATE_PAGE, copy.getDisplay());
        assertEquals("640", copy.getWidth());
        assertEquals("480", copy.getHeight());
    }

    @Test
    public void copy_doesNotMutateOriginalScalarsOrDescs() {
        AttachmentVo original = fullAttachment();
        List<AttachmentDescVo> originalDescsBefore = original.getDescs();

        AttachmentVo copy = original.copy(DataFilter.get().lang("en"));

        // Original is untouched
        assertEquals("/old/a.pdf", original.getPath());
        assertEquals(Long.valueOf(123L), original.getFileSize());
        assertSame(originalDescsBefore, original.getDescs(), "copy() must not replace the original's descs list");
        assertEquals(2, original.getDescs().size(), "original still has both descs");

        // And the copy holds its own, separate desc list/instances
        assertNotSame(original.getDescs(), copy.getDescs());
    }

    @Test
    public void copy_withLangFilterCopiesOnlyMatchingLangDescs() {
        AttachmentVo original = fullAttachment();

        AttachmentVo copy = original.copy(DataFilter.get().lang("en"));

        assertNotNull(copy.getDescs());
        assertEquals(1, copy.getDescs().size(), "only the en desc should be copied for a lang=en filter");
        AttachmentDescVo copiedDesc = copy.getDescs().get(0);
        assertEquals("en", copiedDesc.getLang());
        assertEquals("English caption", copiedDesc.getCaption());

        // The copied desc is a distinct instance from the source desc.
        assertNotSame(original.getDescs().get(0), copiedDesc);
    }

    @Test
    public void copy_withNoLangFilterCopiesAllDescs() {
        AttachmentVo original = fullAttachment();

        AttachmentVo copy = original.copy(DataFilter.get());

        assertEquals(2, copy.getDescs().size(), "a filter with no lang includes every desc");
        assertEquals("en", copy.getDescs().get(0).getLang());
        assertEquals("English caption", copy.getDescs().get(0).getCaption());
        assertEquals("da", copy.getDescs().get(1).getLang());
        assertEquals("Dansk billedtekst", copy.getDescs().get(1).getCaption());
    }

    @Test
    public void copy_withNonMatchingLangFallsBackToFirstDesc_documentsBehavior() {
        // ILocalizable.getDescs(filter) falls back to a singleton list of the FIRST
        // desc when no desc matches the requested language. So copying with a lang
        // that does not exist still yields the first (en) desc rather than an empty
        // list. This pins that fallback behaviour.
        AttachmentVo original = fullAttachment();

        AttachmentVo copy = original.copy(DataFilter.get().lang("fr"));

        assertEquals(1, copy.getDescs().size(), "fallback must yield exactly one desc");
        AttachmentDescVo fallback = copy.getDescs().get(0);
        assertEquals("en", fallback.getLang(), "fallback desc is the first (en) desc");
        assertEquals("English caption", fallback.getCaption());
    }

    @Test
    public void copy_withNullDescsProducesNullDescsOnCopy() {
        AttachmentVo original = new AttachmentVo();
        original.setType("text/plain");
        original.setFileSize(7L);
        // descs left null

        AttachmentVo copy = original.copy(DataFilter.get());

        // copyDescs(null) is a no-op, so the copy's descs list is never created.
        assertNull(copy.getDescs(), "copy of an attachment with null descs has null descs");
        assertEquals("text/plain", copy.getType());
        assertEquals(Long.valueOf(7L), copy.getFileSize());
    }

    @Test
    public void copy_copiesCaptionContentButRecreatesDescPerLang() {
        AttachmentVo original = fullAttachment();

        AttachmentVo copy = original.copy(DataFilter.get());

        // Mutating the copy's desc must not affect the original (deep-ish copy of content).
        copy.getDescs().get(0).setCaption("CHANGED");
        assertEquals("English caption", original.getDescs().get(0).getCaption(),
                "original desc caption must remain unchanged");
    }

    // ---------------------------------------------------------------------
    // rewriteRepoPath(old, new)
    // ---------------------------------------------------------------------

    @Test
    public void rewriteRepoPath_replacesMatchingPrefix() {
        AttachmentVo a = new AttachmentVo();
        a.setPath("/old/a.pdf");

        a.rewriteRepoPath("/old", "/new");

        assertEquals("/new/a.pdf", a.getPath());
    }

    @Test
    public void rewriteRepoPath_leavesNonMatchingPathUnchanged() {
        AttachmentVo a = new AttachmentVo();
        a.setPath("/other/a.pdf");

        a.rewriteRepoPath("/old", "/new");

        assertEquals("/other/a.pdf", a.getPath());
    }

    @Test
    public void rewriteRepoPath_replacesEveryOccurrence_documentsBehavior() {
        // String.replace replaces ALL occurrences, not just a leading prefix.
        AttachmentVo a = new AttachmentVo();
        a.setPath("/old/sub/old/a.pdf");

        a.rewriteRepoPath("/old", "/new");

        assertEquals("/new/sub/new/a.pdf", a.getPath());
    }

    @Test
    public void rewriteRepoPath_withNullPathIsNoOpAndDoesNotThrow() {
        AttachmentVo a = new AttachmentVo();
        // path left null

        a.rewriteRepoPath("/old", "/new");

        assertNull(a.getPath(), "null path must remain null with no NPE");
    }

    // ---------------------------------------------------------------------
    // AttachmentDescVo: descDefined / copyDesc / lang
    // ---------------------------------------------------------------------

    @Test
    public void desc_langAccessorsRoundTrip() {
        AttachmentDescVo desc = new AttachmentDescVo();
        assertNull(desc.getLang(), "lang is null by default");
        desc.setLang("da");
        assertEquals("da", desc.getLang());
    }

    @Test
    public void desc_captionAccessorsRoundTrip() {
        AttachmentDescVo desc = new AttachmentDescVo();
        assertNull(desc.getCaption(), "caption is null by default");
        desc.setCaption("A caption");
        assertEquals("A caption", desc.getCaption());
    }

    @Test
    public void desc_descDefinedIsTrueOnlyWhenCaptionNonBlank() {
        AttachmentDescVo desc = new AttachmentDescVo();
        assertFalse(desc.descDefined(), "undefined when caption is null");

        desc.setCaption("");
        assertFalse(desc.descDefined(), "undefined when caption is empty");

        desc.setCaption("text");
        assertTrue(desc.descDefined(), "defined when caption is non-empty");
    }

    @Test
    public void desc_descDefinedTreatsWhitespaceCaptionAsBlank() {
        // ILocalizedDesc.fieldsDefined honours the "non-blank" contract via String.isBlank(),
        // so a whitespace-only caption is blank and NOT a defined description.
        AttachmentDescVo desc = new AttachmentDescVo();
        desc.setCaption("   ");
        assertFalse(desc.descDefined(), "whitespace-only caption is blank and NOT defined");
    }

    @Test
    public void desc_copyDescCopiesCaptionButNotLang() {
        AttachmentDescVo source = new AttachmentDescVo();
        source.setLang("en");
        source.setCaption("Source caption");

        AttachmentDescVo target = new AttachmentDescVo();
        target.setLang("da");

        target.copyDesc(source);

        assertEquals("Source caption", target.getCaption(), "caption must be copied from source");
        assertEquals("da", target.getLang(), "lang must NOT be overwritten by copyDesc");
    }

    @Test
    public void desc_copyDescFromForeignDescTypeThrows_documentsBehavior() {
        // copyDesc blindly casts its argument to AttachmentDescVo, so passing a
        // different ILocalizedDesc implementation fails fast with a ClassCastException.
        AttachmentDescVo target = new AttachmentDescVo();
        assertThrows(ClassCastException.class, () -> target.copyDesc(new org.niord.model.ILocalizedDesc() {
            @Override public String getLang() { return "en"; }
            @Override public void setLang(String lang) { }
            @Override public boolean descDefined() { return true; }
            @Override public void copyDesc(org.niord.model.ILocalizedDesc desc) { }
        }));
    }

    // ---------------------------------------------------------------------
    // copyDescsAndRemoveBlanks via inherited ILocalizable behaviour
    // ---------------------------------------------------------------------

    @Test
    public void copyDescsAndRemoveBlanks_dropsDescsWithoutCaption() {
        AttachmentVo target = new AttachmentVo();

        AttachmentDescVo defined = new AttachmentDescVo();
        defined.setLang("en");
        defined.setCaption("kept");

        AttachmentDescVo blank = new AttachmentDescVo();
        blank.setLang("da");
        // no caption -> blank

        List<AttachmentDescVo> source = new ArrayList<>();
        source.add(defined);
        source.add(blank);

        target.copyDescsAndRemoveBlanks(source);

        assertEquals(1, target.getDescs().size(), "only the defined desc survives");
        assertEquals("en", target.getDescs().get(0).getLang());
        assertEquals("kept", target.getDescs().get(0).getCaption());
    }

    // ---------------------------------------------------------------------
    // getDesc(lang) inherited behaviour
    // ---------------------------------------------------------------------

    @Test
    public void getDesc_matchesLangCaseInsensitively() {
        AttachmentVo a = new AttachmentVo();
        a.createDesc("en").setCaption("en cap");
        a.createDesc("da").setCaption("da cap");

        AttachmentDescVo found = a.getDesc("EN");
        assertNotNull(found);
        assertEquals("en", found.getLang());
        assertEquals("en cap", found.getCaption());
    }

    @Test
    public void getDesc_returnsNullWhenNoDescsExist() {
        AttachmentVo a = new AttachmentVo();
        assertNull(a.getDesc("en"), "getDesc on an attachment with no descs returns null");
    }

    @Test
    public void getDesc_returnsNullLangDescAsWildcardMatch_documentsBehavior() {
        // getDesc matches a desc whose lang is null regardless of the requested lang.
        AttachmentVo a = new AttachmentVo();
        AttachmentDescVo nullLang = a.createDesc(null);
        nullLang.setCaption("wildcard");

        AttachmentDescVo found = a.getDesc("fr");
        assertSame(nullLang, found, "a null-lang desc matches any requested language");
    }
}
