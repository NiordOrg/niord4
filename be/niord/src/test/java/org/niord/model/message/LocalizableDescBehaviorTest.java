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
import org.niord.model.ILocalizedDesc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@code ILocalizable}/{@code ILocalizedDesc} default-method
 * engine, exercised through the concrete {@link MessageVo} / {@link MessageDescVo}
 * value objects (and {@link AreaDescVo} where useful).
 *
 * <p>These tests verify the localization machinery that drives the REST/JSON API
 * contract: lazy description-list creation, language lookup (case-insensitivity,
 * null-language fallback), the {@link DataFilter}-based language selection with
 * its "fall back to the first description" rule, copying of descriptive content,
 * blank-removal, and stable sorting by language.
 *
 * <p>Framework-neutral: only plain JUnit 4 assertions are used so the tests
 * survive a future Spring migration. Dates and language codes are fixed literals,
 * so the tests are deterministic and order-independent.
 */
public class LocalizableDescBehaviorTest {

    @Test
    void missingModelLanguageUsesExplicitDefaultWithoutCreatingTranslation() {
        MessageVo msg = new MessageVo();
        msg.createDesc("en").setTitle("English");
        MessageDescVo da = msg.createDesc("da");
        da.setTitle("Dansk");
        assertEquals(List.of(da), msg.getDescs(DataFilter.get().lang("kl").defaultLanguage("da")));
        assertNull(msg.getDesc("kl"));
        assertEquals(2, msg.getDescs().size());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static MessageDescVo desc(String lang, String title) {
        MessageDescVo d = new MessageDescVo();
        d.setLang(lang);
        d.setTitle(title);
        return d;
    }

    // ------------------------------------------------------------------
    // checkCreateDescs() lazy creation
    // ------------------------------------------------------------------

    @Test
    public void getDescsIsNullOnFreshVo() {
        MessageVo msg = new MessageVo();
        assertNull(msg.getDescs(), "a fresh MessageVo must have a null descs list");
    }

    @Test
    public void checkCreateDescsCreatesEmptyListAndExposesItViaGetDescs() {
        MessageVo msg = new MessageVo();

        List<MessageDescVo> created = msg.checkCreateDescs();

        assertNotNull(created, "checkCreateDescs() must create a non-null list");
        assertTrue(created.isEmpty(), "the freshly created list must be empty");
        assertSame(created, msg.getDescs(),
                "getDescs() must now return the created list");
    }

    @Test
    public void checkCreateDescsIsIdempotentAndDoesNotResetExistingDescs() {
        MessageVo msg = new MessageVo();
        List<MessageDescVo> first = msg.checkCreateDescs();
        MessageDescVo en = msg.createDesc("en");

        List<MessageDescVo> second = msg.checkCreateDescs();

        assertSame(first, second,
                "calling checkCreateDescs() again must return the SAME list");
        assertEquals(1, second.size(), "the existing description must NOT be wiped");
        assertSame(en, second.get(0));
    }

    // ------------------------------------------------------------------
    // createDesc(lang)
    // ------------------------------------------------------------------

    @Test
    public void createDescReturnsDescWithGivenLangAndAppendsIt() {
        MessageVo msg = new MessageVo();

        MessageDescVo en = msg.createDesc("en");

        assertEquals("en", en.getLang());
        assertEquals(1, msg.getDescs().size(), "createDesc must append exactly one desc");
        assertSame(en, msg.getDescs().get(0),
                "the appended desc must be the returned instance");
    }

    @Test
    public void createDescAppendsInInsertionOrder() {
        MessageVo msg = new MessageVo();

        MessageDescVo en = msg.createDesc("en");
        MessageDescVo da = msg.createDesc("da");

        assertEquals(2, msg.getDescs().size());
        assertSame(en, msg.getDescs().get(0));
        assertSame(da, msg.getDescs().get(1));
    }

    // ------------------------------------------------------------------
    // getDesc(lang)
    // ------------------------------------------------------------------

    @Test
    public void getDescReturnsMatchingLanguage() {
        MessageVo msg = new MessageVo();
        MessageDescVo en = msg.createDesc("en");
        msg.createDesc("da");

        assertSame(en, msg.getDesc("en"));
    }

    @Test
    public void getDescReturnsNullWhenNoLanguageMatchesAndNoNullLangPresent() {
        MessageVo msg = new MessageVo();
        msg.createDesc("en");

        assertNull(msg.getDesc("xx"), "no 'xx' desc and no null-lang desc -> null");
    }

    @Test
    public void getDescReturnsNullWhenDescsNeverCreated() {
        MessageVo msg = new MessageVo();
        assertNull(msg.getDesc("en"), "getDesc on a null descs list must return null");
    }

    @Test
    public void getDescIsCaseInsensitive() {
        MessageVo msg = new MessageVo();
        MessageDescVo upper = desc("EN", "Title");
        msg.setDescs(new ArrayList<>(Arrays.asList(upper)));

        assertSame(upper, msg.getDesc("en"),
                "a desc with lang 'EN' must be returned for 'en'");
    }

    @Test
    public void getDescMatchesNullLangDescForAnyRequestedLanguage() {
        MessageVo msg = new MessageVo();
        MessageDescVo nullLang = desc(null, "Fallback");
        msg.setDescs(new ArrayList<>(Arrays.asList(nullLang)));

        assertSame(nullLang, msg.getDesc("zz"),
                "a null-lang desc matches any requested language");
    }

    // ------------------------------------------------------------------
    // checkCreateDesc(lang)
    // ------------------------------------------------------------------

    @Test
    public void checkCreateDescCreatesWhenAbsent() {
        MessageVo msg = new MessageVo();

        MessageDescVo created = msg.checkCreateDesc("en");

        assertNotNull(created);
        assertEquals("en", created.getLang());
        assertEquals(1, msg.getDescs().size());
        assertSame(created, msg.getDescs().get(0));
    }

    @Test
    public void checkCreateDescReturnsExistingInstanceWhenPresent() {
        MessageVo msg = new MessageVo();
        MessageDescVo en = msg.createDesc("en");

        MessageDescVo again = msg.checkCreateDesc("en");

        assertSame(en, again,
                "checkCreateDesc must return the existing desc, not a new one");
        assertEquals(1, msg.getDescs().size(), "no duplicate desc must be added");
    }

    // ------------------------------------------------------------------
    // getDescs(DataFilter) language selection
    // ------------------------------------------------------------------

    @Test
    public void getDescsByFilterReturnsOnlyMatchingLanguage() {
        MessageVo msg = new MessageVo();
        MessageDescVo en = desc("en", "English");
        MessageDescVo da = desc("da", "Dansk");
        msg.setDescs(new ArrayList<>(Arrays.asList(en, da)));

        List<MessageDescVo> result = msg.getDescs(DataFilter.get().lang("da"));

        assertEquals(1, result.size(), "only the 'da' desc must be selected");
        assertSame(da, result.get(0));
    }

    @Test
    public void getDescsByFilterFallsBackToFirstWhenNoLanguageMatches() {
        MessageVo msg = new MessageVo();
        MessageDescVo en = desc("en", "English");
        MessageDescVo da = desc("da", "Dansk");
        msg.setDescs(new ArrayList<>(Arrays.asList(en, da)));

        List<MessageDescVo> result = msg.getDescs(DataFilter.get().lang("xx"));

        assertEquals(1, result.size(),
                "when no language matches, fall back to a single desc");
        assertSame(en, result.get(0),
                "the fallback must be the FIRST desc in the list");
    }

    @Test
    public void getDescsByNullFilterReturnsRawListUnchanged() {
        MessageVo msg = new MessageVo();
        MessageDescVo en = desc("en", "English");
        MessageDescVo da = desc("da", "Dansk");
        List<MessageDescVo> raw = new ArrayList<>(Arrays.asList(en, da));
        msg.setDescs(raw);

        List<MessageDescVo> result = msg.getDescs((DataFilter) null);

        assertSame(raw, result, "a null filter must return the raw descs list instance");
    }

    @Test
    public void getDescsByFilterReturnsNullWhenDescsAreNull() {
        MessageVo msg = new MessageVo();
        // descs intentionally left null

        assertNull(msg.getDescs(DataFilter.get().lang("en")),
                "a null descs list must yield null even with a filter");
    }

    @Test
    public void getDescsByFilterWithNullLangIncludesAllLanguages() {
        MessageVo msg = new MessageVo();
        MessageDescVo en = desc("en", "English");
        MessageDescVo da = desc("da", "Dansk");
        msg.setDescs(new ArrayList<>(Arrays.asList(en, da)));

        // A filter without a language restriction includes every language.
        List<MessageDescVo> result = msg.getDescs(DataFilter.get());

        assertEquals(2, result.size(), "a language-less filter must keep all descs");
        assertTrue(result.contains(en));
        assertTrue(result.contains(da));
    }

    // ------------------------------------------------------------------
    // copyDescs(list)
    // ------------------------------------------------------------------

    @Test
    public void copyDescsCopiesContentForEachLanguageIntoNewDescs() {
        MessageVo source = new MessageVo();
        MessageDescVo srcEn = desc("en", "English title");
        MessageDescVo srcDa = desc("da", "Dansk titel");
        source.setDescs(new ArrayList<>(Arrays.asList(srcEn, srcDa)));

        MessageVo target = new MessageVo();
        target.copyDescs(source.getDescs());

        assertEquals(2, target.getDescs().size(),
                "target must gain one desc per source language");

        MessageDescVo copiedEn = target.getDesc("en");
        MessageDescVo copiedDa = target.getDesc("da");
        assertNotNull(copiedEn);
        assertNotNull(copiedDa);
        assertEquals("English title", copiedEn.getTitle());
        assertEquals("Dansk titel", copiedDa.getTitle());

        assertNotSame(srcEn, copiedEn,
                "copyDescs must create new desc instances, not reuse the source");
        assertNotSame(srcDa, copiedDa);
    }

    @Test
    public void copyDescsCopiesAllContentFieldsButNotLangIdentityFromCopyDesc() {
        // Exercise MessageDescVo.copyDesc directly: it copies every content field
        // but deliberately leaves the target's own lang untouched.
        MessageDescVo source = new MessageDescVo();
        source.setLang("en");
        source.setTitle("T");
        source.setVicinity("V");
        source.setPublication("P");
        source.setInternalPublication("IP");
        source.setSource("S");

        MessageDescVo target = new MessageDescVo();
        target.setLang("da");
        target.copyDesc(source);

        assertEquals("T", target.getTitle());
        assertEquals("V", target.getVicinity());
        assertEquals("P", target.getPublication());
        assertEquals("IP", target.getInternalPublication());
        assertEquals("S", target.getSource());
        assertEquals("da", target.getLang(), "copyDesc must NOT overwrite the target language");
    }

    @Test
    public void copyDescsWithNullListLeavesTargetDescsUntouched() {
        MessageVo target = new MessageVo();
        target.copyDescs(null);
        assertNull(target.getDescs(), "copying a null list must not create a descs list");
    }

    @Test
    public void copyDescsWithEmptyListLeavesTargetDescsUntouched() {
        MessageVo target = new MessageVo();
        target.copyDescs(new ArrayList<>());
        assertNull(target.getDescs(), "copying an empty list must not create a descs list");
    }

    // ------------------------------------------------------------------
    // copyDescsAndRemoveBlanks(list)
    // ------------------------------------------------------------------

    @Test
    public void copyDescsAndRemoveBlanksDropsUndefinedDescs() {
        MessageVo source = new MessageVo();
        MessageDescVo defined = desc("en", "Has a title");
        MessageDescVo blank = desc("da", null); // no content -> not descDefined()
        source.setDescs(new ArrayList<>(Arrays.asList(defined, blank)));

        MessageVo target = new MessageVo();
        target.copyDescsAndRemoveBlanks(source.getDescs());

        assertEquals(1, target.getDescs().size(),
                "the blank desc must be removed, leaving only the defined one");
        MessageDescVo remaining = target.getDescs().get(0);
        assertEquals("en", remaining.getLang());
        assertEquals("Has a title", remaining.getTitle());
        assertNull(target.getDesc("da"), "no 'da' desc must survive the blank-removal");
    }

    @Test
    public void copyDescsAndRemoveBlanksKeepsAllDefinedDescs() {
        MessageVo source = new MessageVo();
        MessageDescVo en = desc("en", "English");
        MessageDescVo da = desc("da", "Dansk");
        source.setDescs(new ArrayList<>(Arrays.asList(en, da)));

        MessageVo target = new MessageVo();
        target.copyDescsAndRemoveBlanks(source.getDescs());

        assertEquals(2, target.getDescs().size());
        assertEquals("English", target.getDesc("en").getTitle());
        assertEquals("Dansk", target.getDesc("da").getTitle());
    }

    // ------------------------------------------------------------------
    // sortDescs(lang)
    // ------------------------------------------------------------------

    @Test
    public void sortDescsMovesRequestedLanguageToFront() {
        MessageVo msg = new MessageVo();
        MessageDescVo en = desc("en", "English");
        MessageDescVo da = desc("da", "Dansk");
        msg.setDescs(new ArrayList<>(Arrays.asList(en, da)));

        msg.sortDescs("da");

        assertEquals("da", msg.getDescs().get(0).getLang(), "the requested language must be first");
        assertEquals("en", msg.getDescs().get(1).getLang());
    }

    @Test
    public void sortDescsPushesNullLangDescToTheEnd() {
        MessageVo msg = new MessageVo();
        MessageDescVo nullLang = desc(null, "Fallback");
        MessageDescVo en = desc("en", "English");
        MessageDescVo da = desc("da", "Dansk");
        msg.setDescs(new ArrayList<>(Arrays.asList(nullLang, en, da)));

        msg.sortDescs("da");

        assertEquals("da", msg.getDescs().get(0).getLang(), "requested language sorts first");
        assertNull(msg.getDescs().get(msg.getDescs().size() - 1).getLang(),
                "the null-lang desc must sort to the end");
    }

    @Test
    public void sortDescsWithNullLangIsNoOp() {
        MessageVo msg = new MessageVo();
        MessageDescVo en = desc("en", "English");
        MessageDescVo da = desc("da", "Dansk");
        msg.setDescs(new ArrayList<>(Arrays.asList(en, da)));

        msg.sortDescs(null);

        assertSame(en, msg.getDescs().get(0), "a null sort language must leave order unchanged");
        assertSame(da, msg.getDescs().get(1));
    }

    // ------------------------------------------------------------------
    // ILocalizedDesc.fieldsDefined(...) static utility
    // ------------------------------------------------------------------

    @Test
    public void fieldsDefinedIsFalseForNoArguments() {
        assertFalse(ILocalizedDesc.fieldsDefined());
    }

    @Test
    public void fieldsDefinedIsFalseForOnlyBlankAndNullValues() {
        assertFalse(ILocalizedDesc.fieldsDefined("", null),
                "empty string and null are both 'undefined'");
    }

    @Test
    public void fieldsDefinedIsTrueWhenAnyValueIsNonBlank() {
        assertTrue(ILocalizedDesc.fieldsDefined("", "x"));
    }

    // ------------------------------------------------------------------
    // MessageDescVo.descDefined()
    // ------------------------------------------------------------------

    @Test
    public void descDefinedIsFalseWhenAllContentFieldsBlank() {
        MessageDescVo d = new MessageDescVo();
        d.setLang("en"); // lang is NOT a content field
        assertFalse(d.descDefined(), "an empty desc (only lang set) must be undefined");
    }

    @Test
    public void descDefinedIsTrueWhenTitleSet() {
        MessageDescVo d = new MessageDescVo();
        d.setTitle("hello");
        assertTrue(d.descDefined());
    }

    @Test
    public void descDefinedIsTrueWhenOnlySourceSet() {
        MessageDescVo d = new MessageDescVo();
        d.setSource("a source");
        assertTrue(d.descDefined(), "any single non-blank content field makes the desc defined");
    }

    // ------------------------------------------------------------------
    // AreaDescVo cross-check: the same engine works for a different VO type
    // ------------------------------------------------------------------

    @Test
    public void areaDescCopyDescCopiesNameButNotLang() {
        AreaDescVo source = new AreaDescVo();
        source.setLang("en");
        source.setName("Denmark");

        AreaDescVo target = new AreaDescVo();
        target.setLang("da");
        target.copyDesc(source);

        assertEquals("Denmark", target.getName());
        assertEquals("da", target.getLang(), "copyDesc must not change the target language");
    }

    @Test
    public void areaDescDefinedReflectsNameField() {
        AreaDescVo empty = new AreaDescVo();
        assertFalse(empty.descDefined(), "an AreaDesc with no name is undefined");

        AreaDescVo named = new AreaDescVo();
        named.setName("Baltic Sea");
        assertTrue(named.descDefined());
    }
}
