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
package org.niord.model.publication;

import org.junit.jupiter.api.Test;
import org.niord.model.DataFilter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PublicationVo} and {@link PublicationCategoryVo} plus
 * their localized descriptions {@link PublicationDescVo} /
 * {@link PublicationCategoryDescVo}, and the {@link PublicationType} enum.
 *
 * These tests target the REST/JSON value-object contract and the
 * localization/copy behaviour inherited from {@code ILocalizable} /
 * {@code ILocalizedDesc}. They are framework-neutral plain JUnit 4 tests
 * (no Quarkus, no mocks) and assert concrete copied state, list contents,
 * enum mappings and thrown exceptions so they survive a future Spring port.
 *
 * Determinism: every {@link Date} is built from a fixed epoch milli value so
 * the tests never depend on the current time, locale or timezone. Each test
 * builds its own data and is order-independent.
 *
 * The tests live in {@code org.niord.model.publication} so the package-private
 * scalar fields stay reachable for white-box assertions if needed; the tests
 * deliberately use the public accessors.
 */
public class PublicationVoTest {

    // Fixed, deterministic timestamps -- never "now".
    private static final Date CREATED        = new Date(1_000_000L);
    private static final Date UPDATED        = new Date(2_000_000L);
    private static final Date PUBLISH_FROM    = new Date(3_000_000L);
    private static final Date PUBLISH_TO      = new Date(4_000_000L);

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Builds a fully-populated PublicationVo with a category and two descs (en + da). */
    private static PublicationVo fullPublication() {
        PublicationVo p = new PublicationVo();
        p.setPublicationId("pub-1");
        p.setCreated(CREATED);
        p.setUpdated(UPDATED);
        p.setType(PublicationType.REPOSITORY);
        p.setPublishDateFrom(PUBLISH_FROM);
        p.setPublishDateTo(PUBLISH_TO);
        p.setCategory(fullCategory());

        PublicationDescVo en = p.createDesc("en");
        en.setTitle("English title");
        en.setTitleFormat("EN format");
        en.setLink("/old/en.pdf");
        en.setFileName("en.pdf");
        en.setMessagePublicationFormat("EN msg format");

        PublicationDescVo da = p.createDesc("da");
        da.setTitle("Dansk titel");
        da.setTitleFormat("DA format");
        da.setLink("/old/da.pdf");
        da.setFileName("da.pdf");
        da.setMessagePublicationFormat("DA msg format");
        return p;
    }

    /** Builds a fully-populated PublicationCategoryVo with two descs (en + da). */
    private static PublicationCategoryVo fullCategory() {
        PublicationCategoryVo c = new PublicationCategoryVo();
        c.setCategoryId("cat-1");
        c.setPriority(7);
        c.setPublish(Boolean.TRUE);

        PublicationCategoryDescVo en = c.createDesc("en");
        en.setName("English name");
        en.setDescription("English description");

        PublicationCategoryDescVo da = c.createDesc("da");
        da.setName("Dansk navn");
        da.setDescription("Dansk beskrivelse");
        return c;
    }

    // ---------------------------------------------------------------------
    // PublicationType enum
    // ---------------------------------------------------------------------

    @Test
    public void publicationType_hasExactlyFourOrderedValues() {
        PublicationType[] values = PublicationType.values();
        assertEquals(4, values.length, "PublicationType must declare exactly 4 constants");
        assertEquals(PublicationType.NONE, values[0]);
        assertEquals(PublicationType.LINK, values[1]);
        assertEquals(PublicationType.REPOSITORY, values[2]);
        assertEquals(PublicationType.MESSAGE_REPORT, values[3]);
    }

    @Test
    public void publicationType_valueOfRoundTripsByName() {
        assertSame(PublicationType.NONE, PublicationType.valueOf("NONE"));
        assertSame(PublicationType.LINK, PublicationType.valueOf("LINK"));
        assertSame(PublicationType.REPOSITORY, PublicationType.valueOf("REPOSITORY"));
        assertSame(PublicationType.MESSAGE_REPORT, PublicationType.valueOf("MESSAGE_REPORT"));
    }

    @Test
    public void publicationType_valueOfUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> PublicationType.valueOf("FILE"));
    }

    // ---------------------------------------------------------------------
    // PublicationVo getters / setters (state round-trip)
    // ---------------------------------------------------------------------

    @Test
    public void publication_scalarAccessors_storeAndReturnValues() {
        PublicationVo p = new PublicationVo();
        p.setPublicationId("id-9");
        p.setCreated(CREATED);
        p.setUpdated(UPDATED);
        p.setType(PublicationType.LINK);
        p.setPublishDateFrom(PUBLISH_FROM);
        p.setPublishDateTo(PUBLISH_TO);

        assertEquals("id-9", p.getPublicationId());
        assertEquals(CREATED, p.getCreated());
        assertEquals(UPDATED, p.getUpdated());
        assertEquals(PublicationType.LINK, p.getType());
        assertEquals(PUBLISH_FROM, p.getPublishDateFrom());
        assertEquals(PUBLISH_TO, p.getPublishDateTo());
    }

    @Test
    public void publication_categoryAndDescsAreNullByDefault() {
        PublicationVo p = new PublicationVo();
        assertNull(p.getCategory(), "category is null by default");
        assertNull(p.getDescs(), "descs list is null before any desc is created");
    }

    // ---------------------------------------------------------------------
    // PublicationVo.createDesc(lang)
    // ---------------------------------------------------------------------

    @Test
    public void publication_createDesc_addsDescWithGivenLangAndLazilyCreatesList() {
        PublicationVo p = new PublicationVo();

        PublicationDescVo desc = p.createDesc("en");

        assertNotNull(p.getDescs(), "createDesc must lazily create the descs list");
        assertEquals(1, p.getDescs().size(), "descs list must contain exactly the created desc");
        assertSame(desc, p.getDescs().get(0), "returned desc must be the one stored in the list");
        assertEquals("en", desc.getLang());
        assertNull(desc.getTitle(), "a freshly created desc has no title");
    }

    @Test
    public void publication_createDesc_appendsAdditionalDescsPreservingOrder() {
        PublicationVo p = new PublicationVo();

        PublicationDescVo en = p.createDesc("en");
        PublicationDescVo da = p.createDesc("da");

        assertEquals(2, p.getDescs().size());
        assertSame(en, p.getDescs().get(0));
        assertSame(da, p.getDescs().get(1));
        assertEquals("en", p.getDescs().get(0).getLang());
        assertEquals("da", p.getDescs().get(1).getLang());
    }

    // ---------------------------------------------------------------------
    // PublicationVo.copy(DataFilter) -- scalars always copied (no details gating)
    // ---------------------------------------------------------------------

    @Test
    public void publication_copy_returnsDistinctInstanceAndCopiesAllScalars() {
        PublicationVo original = fullPublication();

        // A filter that includes NO fields at all: copy() still copies every scalar,
        // because copy() copies them unconditionally (there is no details gating).
        PublicationVo copy = original.copy(DataFilter.get());

        assertNotSame(original, copy, "copy() must return a new instance");
        assertEquals("pub-1", copy.getPublicationId());
        assertEquals(CREATED, copy.getCreated());
        assertEquals(UPDATED, copy.getUpdated());
        assertEquals(PublicationType.REPOSITORY, copy.getType());
        assertEquals(PUBLISH_FROM, copy.getPublishDateFrom());
        assertEquals(PUBLISH_TO, copy.getPublishDateTo());
    }

    @Test
    public void publication_copy_copiesCategoryAsDistinctInstanceWithSameState() {
        PublicationVo original = fullPublication();

        PublicationVo copy = original.copy(DataFilter.get());

        assertNotNull(copy.getCategory(), "category must be copied");
        assertNotSame(original.getCategory(), copy.getCategory(),
                "category must be a distinct PublicationCategoryVo instance");
        assertEquals("cat-1", copy.getCategory().getCategoryId());
        assertEquals(Integer.valueOf(7), copy.getCategory().getPriority());
        assertEquals(Boolean.TRUE, copy.getCategory().getPublish());
    }

    @Test
    public void publication_copy_withNullCategoryLeavesCategoryNull() {
        PublicationVo original = fullPublication();
        original.setCategory(null);

        PublicationVo copy = original.copy(DataFilter.get());

        assertNull(copy.getCategory(),
                "copy of a publication with null category has null category (no NPE)");
        assertEquals("pub-1", copy.getPublicationId());
    }

    @Test
    public void publication_copy_withLangFilterCopiesOnlyMatchingLangDescs() {
        PublicationVo original = fullPublication();

        PublicationVo copy = original.copy(DataFilter.get().lang("da"));

        assertNotNull(copy.getDescs());
        assertEquals(1, copy.getDescs().size(), "only the da desc should be copied for a lang=da filter");
        PublicationDescVo copied = copy.getDescs().get(0);
        assertEquals("da", copied.getLang());
        assertEquals("Dansk titel", copied.getTitle());
        assertEquals("DA format", copied.getTitleFormat());
        assertEquals("/old/da.pdf", copied.getLink());
        assertEquals("da.pdf", copied.getFileName());
        assertEquals("DA msg format", copied.getMessagePublicationFormat());

        // The copied desc is a distinct instance from the source desc.
        assertNotSame(original.getDescs().get(1), copied);
    }

    @Test
    public void publication_copy_withNoLangFilterCopiesAllDescs() {
        PublicationVo original = fullPublication();

        PublicationVo copy = original.copy(DataFilter.get());

        assertEquals(2, copy.getDescs().size(), "a filter with no lang includes every desc");
        assertEquals("en", copy.getDescs().get(0).getLang());
        assertEquals("English title", copy.getDescs().get(0).getTitle());
        assertEquals("da", copy.getDescs().get(1).getLang());
        assertEquals("Dansk titel", copy.getDescs().get(1).getTitle());
    }

    @Test
    public void publication_copy_withNonMatchingLangFallsBackToFirstDesc_documentsBehavior() {
        // ILocalizable.getDescs(filter) falls back to a singleton list of the FIRST
        // desc when no desc matches the requested language. So copying with a lang
        // that does not exist still yields the first (en) desc rather than an empty
        // list. This pins that fallback behaviour.
        PublicationVo original = fullPublication();

        PublicationVo copy = original.copy(DataFilter.get().lang("fr"));

        assertEquals(1, copy.getDescs().size(), "fallback must yield exactly one desc");
        PublicationDescVo fallback = copy.getDescs().get(0);
        assertEquals("en", fallback.getLang(), "fallback desc is the first (en) desc");
        assertEquals("English title", fallback.getTitle());
    }

    @Test
    public void publication_copy_withNullDescsProducesNullDescsOnCopy() {
        PublicationVo original = new PublicationVo();
        original.setPublicationId("pub-empty");
        original.setType(PublicationType.NONE);
        // descs left null, category left null

        PublicationVo copy = original.copy(DataFilter.get());

        // copyDescs(null) is a no-op, so the copy's descs list is never created.
        assertNull(copy.getDescs(), "copy of a publication with null descs has null descs");
        assertEquals("pub-empty", copy.getPublicationId());
        assertEquals(PublicationType.NONE, copy.getType());
    }

    @Test
    public void publication_copy_doesNotMutateOriginalDescsList() {
        PublicationVo original = fullPublication();
        List<PublicationDescVo> originalDescsBefore = original.getDescs();

        PublicationVo copy = original.copy(DataFilter.get().lang("en"));

        assertSame(originalDescsBefore, original.getDescs(),
                "copy() must not replace the original's descs list");
        assertEquals(2, original.getDescs().size(), "original still has both descs");
        assertNotSame(original.getDescs(), copy.getDescs(), "copy holds its own separate desc list");
    }

    @Test
    public void publication_copy_deepCopiesDescContentSoMutationIsIsolated() {
        PublicationVo original = fullPublication();

        PublicationVo copy = original.copy(DataFilter.get());

        // Mutating the copy's desc content must not affect the original.
        copy.getDescs().get(0).setTitle("CHANGED");
        assertEquals("English title", original.getDescs().get(0).getTitle(),
                "original desc title must remain unchanged");
    }

    // ---------------------------------------------------------------------
    // PublicationVo.rewriteRepoPath(old, new)
    // ---------------------------------------------------------------------

    @Test
    public void rewriteRepoPath_replacesMatchingPathInDescLink() {
        PublicationVo p = new PublicationVo();
        p.createDesc("en").setLink("/old/x.pdf");

        p.rewriteRepoPath("/old", "/new");

        assertEquals("/new/x.pdf", p.getDescs().get(0).getLink());
    }

    @Test
    public void rewriteRepoPath_leavesNonMatchingLinkUnchanged() {
        PublicationVo p = new PublicationVo();
        p.createDesc("en").setLink("/other/x.pdf");

        p.rewriteRepoPath("/old", "/new");

        assertEquals("/other/x.pdf", p.getDescs().get(0).getLink());
    }

    @Test
    public void rewriteRepoPath_rewritesOnlyMatchingDescsAcrossMultipleLangs() {
        PublicationVo p = new PublicationVo();
        p.createDesc("en").setLink("/old/en.pdf");
        p.createDesc("da").setLink("/other/da.pdf");

        p.rewriteRepoPath("/old", "/new");

        assertEquals("/new/en.pdf", p.getDescs().get(0).getLink(), "matching link is rewritten");
        assertEquals("/other/da.pdf", p.getDescs().get(1).getLink(), "non-matching link is untouched");
    }

    @Test
    public void rewriteRepoPath_withNullLinkIsNoOpAndDoesNotThrow() {
        PublicationVo p = new PublicationVo();
        p.createDesc("en"); // link left null

        p.rewriteRepoPath("/old", "/new");

        assertNull(p.getDescs().get(0).getLink(), "null link must remain null with no NPE");
    }

    @Test
    public void rewriteRepoPath_withNullDescsIsNoOpAndDoesNotThrow() {
        PublicationVo p = new PublicationVo();
        // descs left null

        p.rewriteRepoPath("/old", "/new");

        assertNull(p.getDescs(), "descs remain null with no NPE");
    }

    @Test
    public void rewriteRepoPath_withBlankRepoPathArgsIsNoOp() {
        // StringUtils.isNotBlank guard: blank source/target paths short-circuit.
        PublicationVo p = new PublicationVo();
        p.createDesc("en").setLink("/old/x.pdf");

        p.rewriteRepoPath("", "/new");
        assertEquals("/old/x.pdf", p.getDescs().get(0).getLink(), "blank source path leaves link unchanged");

        p.rewriteRepoPath("/old", "   ");
        assertEquals("/old/x.pdf", p.getDescs().get(0).getLink(), "blank target path leaves link unchanged");

        p.rewriteRepoPath(null, "/new");
        assertEquals("/old/x.pdf", p.getDescs().get(0).getLink(), "null source path leaves link unchanged");
    }

    @Test
    public void rewriteRepoPath_replacesEveryOccurrence_documentsBehavior() {
        // String.replace replaces ALL occurrences, not just a leading prefix.
        PublicationVo p = new PublicationVo();
        p.createDesc("en").setLink("/old/sub/old/x.pdf");

        p.rewriteRepoPath("/old", "/new");

        assertEquals("/new/sub/new/x.pdf", p.getDescs().get(0).getLink());
    }

    // ---------------------------------------------------------------------
    // PublicationCategoryVo getters / setters (state round-trip)
    // ---------------------------------------------------------------------

    @Test
    public void category_scalarAccessors_storeAndReturnValues() {
        PublicationCategoryVo c = new PublicationCategoryVo();
        c.setCategoryId("cat-x");
        c.setPriority(42);
        c.setPublish(Boolean.FALSE);

        assertEquals("cat-x", c.getCategoryId());
        assertEquals(Integer.valueOf(42), c.getPriority());
        assertEquals(Boolean.FALSE, c.getPublish());
    }

    @Test
    public void category_descsAreNullByDefault() {
        PublicationCategoryVo c = new PublicationCategoryVo();
        assertNull(c.getDescs(), "descs list is null before any desc is created");
    }

    // ---------------------------------------------------------------------
    // PublicationCategoryVo.createDesc(lang)
    // ---------------------------------------------------------------------

    @Test
    public void category_createDesc_addsDescWithGivenLangAndLazilyCreatesList() {
        PublicationCategoryVo c = new PublicationCategoryVo();

        PublicationCategoryDescVo desc = c.createDesc("en");

        assertNotNull(c.getDescs(), "createDesc must lazily create the descs list");
        assertEquals(1, c.getDescs().size());
        assertSame(desc, c.getDescs().get(0));
        assertEquals("en", desc.getLang());
        assertNull(desc.getName(), "a freshly created category desc has no name");
    }

    // ---------------------------------------------------------------------
    // PublicationCategoryVo.copy(DataFilter)
    // ---------------------------------------------------------------------

    @Test
    public void category_copy_returnsDistinctInstanceAndCopiesScalars() {
        PublicationCategoryVo original = fullCategory();

        PublicationCategoryVo copy = original.copy(DataFilter.get());

        assertNotSame(original, copy, "copy() must return a new instance");
        assertEquals("cat-1", copy.getCategoryId());
        assertEquals(Integer.valueOf(7), copy.getPriority());
        assertEquals(Boolean.TRUE, copy.getPublish());
    }

    @Test
    public void category_copy_withLangFilterCopiesOnlyMatchingLangDescs() {
        PublicationCategoryVo original = fullCategory();

        PublicationCategoryVo copy = original.copy(DataFilter.get().lang("da"));

        assertEquals(1, copy.getDescs().size(), "only the da desc should be copied for a lang=da filter");
        PublicationCategoryDescVo copied = copy.getDescs().get(0);
        assertEquals("da", copied.getLang());
        assertEquals("Dansk navn", copied.getName());
        assertEquals("Dansk beskrivelse", copied.getDescription());
        assertNotSame(original.getDescs().get(1), copied, "the copied desc is a distinct instance");
    }

    @Test
    public void category_copy_withNoLangFilterCopiesAllDescs() {
        PublicationCategoryVo original = fullCategory();

        PublicationCategoryVo copy = original.copy(DataFilter.get());

        assertEquals(2, copy.getDescs().size(), "a filter with no lang includes every desc");
        assertEquals("en", copy.getDescs().get(0).getLang());
        assertEquals("English name", copy.getDescs().get(0).getName());
        assertEquals("da", copy.getDescs().get(1).getLang());
        assertEquals("Dansk navn", copy.getDescs().get(1).getName());
    }

    @Test
    public void category_copy_deepCopiesDescContentSoMutationIsIsolated() {
        PublicationCategoryVo original = fullCategory();

        PublicationCategoryVo copy = original.copy(DataFilter.get());

        copy.getDescs().get(0).setName("CHANGED");
        assertEquals("English name", original.getDescs().get(0).getName(),
                "original category desc name must remain unchanged");
    }

    @Test
    public void category_copy_withNullDescsProducesNullDescsOnCopy() {
        PublicationCategoryVo original = new PublicationCategoryVo();
        original.setCategoryId("cat-empty");
        original.setPriority(1);
        // descs left null

        PublicationCategoryVo copy = original.copy(DataFilter.get());

        assertNull(copy.getDescs(), "copy of a category with null descs has null descs");
        assertEquals("cat-empty", copy.getCategoryId());
        assertEquals(Integer.valueOf(1), copy.getPriority());
    }

    // ---------------------------------------------------------------------
    // PublicationDescVo: descDefined / copyDesc / lang
    // ---------------------------------------------------------------------

    @Test
    public void desc_langAndContentAccessorsRoundTrip() {
        PublicationDescVo desc = new PublicationDescVo();
        assertNull(desc.getLang(), "lang is null by default");

        desc.setLang("da");
        desc.setTitle("t");
        desc.setTitleFormat("tf");
        desc.setLink("l");
        desc.setFileName("fn");
        desc.setMessagePublicationFormat("mpf");

        assertEquals("da", desc.getLang());
        assertEquals("t", desc.getTitle());
        assertEquals("tf", desc.getTitleFormat());
        assertEquals("l", desc.getLink());
        assertEquals("fn", desc.getFileName());
        assertEquals("mpf", desc.getMessagePublicationFormat());
    }

    @Test
    public void desc_descDefinedIsFalseWhenAllCheckedFieldsBlank() {
        PublicationDescVo desc = new PublicationDescVo();
        assertFalse(desc.descDefined(), "undefined when all content fields are null");

        desc.setTitle("");
        desc.setTitleFormat("");
        desc.setLink("");
        desc.setFileName("");
        assertFalse(desc.descDefined(), "undefined when all checked content fields are empty");
    }

    @Test
    public void desc_descDefinedIsTrueWhenAnyCheckedFieldNonBlank() {
        PublicationDescVo byTitle = new PublicationDescVo();
        byTitle.setTitle("x");
        assertTrue(byTitle.descDefined(), "defined via title");

        PublicationDescVo byTitleFormat = new PublicationDescVo();
        byTitleFormat.setTitleFormat("x");
        assertTrue(byTitleFormat.descDefined(), "defined via titleFormat");

        PublicationDescVo byLink = new PublicationDescVo();
        byLink.setLink("x");
        assertTrue(byLink.descDefined(), "defined via link");

        PublicationDescVo byFileName = new PublicationDescVo();
        byFileName.setFileName("x");
        assertTrue(byFileName.descDefined(), "defined via fileName");
    }

    @Test
    public void desc_descDefinedCountsMessagePublicationFormat() {
        // FIXED (P1): descDefined() now checks title, titleFormat, link, fileName AND
        // messagePublicationFormat. A desc with only messagePublicationFormat set is
        // therefore considered defined (matching copyDesc and the entity PublicationDesc).
        PublicationDescVo desc = new PublicationDescVo();
        desc.setMessagePublicationFormat("only this");
        assertTrue(desc.descDefined(), "messagePublicationFormat contributes to descDefined()");
    }

    @Test
    public void desc_descDefinedTreatsWhitespaceTitleAsBlank() {
        // ILocalizedDesc.fieldsDefined honours the "non-blank" contract via String.isBlank(),
        // so a whitespace-only title is blank and NOT a defined description.
        PublicationDescVo desc = new PublicationDescVo();
        desc.setTitle("   ");
        assertFalse(desc.descDefined(), "whitespace-only title is blank and NOT defined");
    }

    @Test
    public void desc_copyDescCopiesContentFieldsButNotLang() {
        PublicationDescVo source = new PublicationDescVo();
        source.setLang("en");
        source.setTitle("Source title");
        source.setTitleFormat("Source format");
        source.setLink("/src/link");
        source.setFileName("src.pdf");
        source.setMessagePublicationFormat("src mpf");

        PublicationDescVo target = new PublicationDescVo();
        target.setLang("da");

        target.copyDesc(source);

        assertEquals("Source title", target.getTitle());
        assertEquals("Source format", target.getTitleFormat());
        assertEquals("/src/link", target.getLink());
        assertEquals("src.pdf", target.getFileName());
        assertEquals("src mpf", target.getMessagePublicationFormat());
        assertEquals("da", target.getLang(), "lang must NOT be overwritten by copyDesc");
    }

    @Test
    public void desc_copyDescFromForeignDescTypeThrows_documentsBehavior() {
        // copyDesc blindly casts its argument to PublicationDescVo, so passing a
        // different ILocalizedDesc implementation fails fast with a ClassCastException.
        PublicationDescVo target = new PublicationDescVo();
        assertThrows(ClassCastException.class, () -> target.copyDesc(new org.niord.model.ILocalizedDesc() {
            @Override public String getLang() { return "en"; }
            @Override public void setLang(String lang) { }
            @Override public boolean descDefined() { return true; }
            @Override public void copyDesc(org.niord.model.ILocalizedDesc desc) { }
        }));
    }

    // ---------------------------------------------------------------------
    // PublicationCategoryDescVo: descDefined / copyDesc
    // ---------------------------------------------------------------------

    @Test
    public void categoryDesc_descDefinedIsTrueOnlyWhenNameOrDescriptionNonBlank() {
        PublicationCategoryDescVo desc = new PublicationCategoryDescVo();
        assertFalse(desc.descDefined(), "undefined when name and description are null");

        desc.setName("n");
        assertTrue(desc.descDefined(), "defined via name");

        PublicationCategoryDescVo byDesc = new PublicationCategoryDescVo();
        byDesc.setDescription("d");
        assertTrue(byDesc.descDefined(), "defined via description");
    }

    @Test
    public void categoryDesc_copyDescCopiesContentButNotLang() {
        PublicationCategoryDescVo source = new PublicationCategoryDescVo();
        source.setLang("en");
        source.setName("Source name");
        source.setDescription("Source description");

        PublicationCategoryDescVo target = new PublicationCategoryDescVo();
        target.setLang("da");

        target.copyDesc(source);

        assertEquals("Source name", target.getName());
        assertEquals("Source description", target.getDescription());
        assertEquals("da", target.getLang(), "lang must NOT be overwritten by copyDesc");
    }

    // ---------------------------------------------------------------------
    // Inherited ILocalizable behaviour exercised through these VOs
    // ---------------------------------------------------------------------

    @Test
    public void copyDescsAndRemoveBlanks_dropsDescsWithoutDefinedContent() {
        PublicationVo target = new PublicationVo();

        PublicationDescVo defined = new PublicationDescVo();
        defined.setLang("en");
        defined.setTitle("kept");

        PublicationDescVo blank = new PublicationDescVo();
        blank.setLang("da");
        // no content -> blank

        List<PublicationDescVo> source = new ArrayList<>();
        source.add(defined);
        source.add(blank);

        target.copyDescsAndRemoveBlanks(source);

        assertEquals(1, target.getDescs().size(), "only the defined desc survives");
        assertEquals("en", target.getDescs().get(0).getLang());
        assertEquals("kept", target.getDescs().get(0).getTitle());
    }

    @Test
    public void getDesc_matchesLangCaseInsensitively() {
        PublicationVo p = new PublicationVo();
        p.createDesc("en").setTitle("en title");
        p.createDesc("da").setTitle("da title");

        PublicationDescVo found = p.getDesc("EN");
        assertNotNull(found);
        assertEquals("en", found.getLang());
        assertEquals("en title", found.getTitle());
    }

    @Test
    public void getDesc_returnsNullWhenNoDescsExist() {
        PublicationVo p = new PublicationVo();
        assertNull(p.getDesc("en"), "getDesc on a publication with no descs returns null");
    }
}
