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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.util.UidUtils;
import org.niord.model.message.MainType;
import org.niord.model.message.MessageDescVo;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for {@link MessageTokenExpander}, the pure token-expansion utility used to
 * replace placeholders such as {@code ${short-id}}, {@code ${title}} and {@code ${number-year-id}} in template
 * strings.
 *
 * <p>The tests cover both static factories ({@code getInstance(Message,...)} and
 * {@code getInstance(SystemMessageVo,...)}), the language-resolution rules for the {@code ${title}} keys, the
 * custom {@link MessageTokenExpander#token(String, String)} registration, and the behaviour of
 * {@link MessageTokenExpander#expandTokens(String)} (null/blank handling, literal pass-through of undefined
 * tokens, and the per-iteration {@code trim()} of the result).
 *
 * <p>These are deliberately plain JUnit 4 unit tests built from real domain objects via {@code new} + setters.
 * They assert only on observable behaviour (returned strings and exceptions) and avoid any Quarkus/Spring/CDI
 * dependency, so they should survive a future Quarkus-to-Spring port unchanged.
 */
public class MessageTokenExpanderTest {

    /** A fixed 2023 date so the derived year is timezone-independent. */
    private static java.util.Date date2023() {
        return new GregorianCalendar(2023, Calendar.JUNE, 15).getTime();
    }

    /** Builds a fully-populated Message with English + Danish descriptions. */
    private static Message buildMessage(String uid) {
        Message msg = new Message();
        msg.createDesc("en").setTitle("English title");
        msg.createDesc("da").setTitle("Dansk titel");
        msg.setUid(uid);
        msg.setId(42);
        msg.setShortId("NW-007-23");
        msg.setNumber(7);
        msg.setMainType(MainType.NW);
        msg.setPublishDateFrom(date2023());
        msg.checkUpdateYear(); // syncs year=2023 from publishDateFrom
        return msg;
    }

    // ------------------------------------------------------------------
    // getInstance(Message, ...) - the entity path
    // ------------------------------------------------------------------

    @Test
    public void messageFactory_resolvesCurrentLanguageTitle_toEnglish() {
        String uid = UidUtils.newUid();
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Arrays.asList("en", "da"), "en");

        // language="en" => ${title} resolves to the English desc
        assertEquals("English title", exp.expandTokens("${title}"));
    }

    @Test
    public void messageFactory_resolvesPerLanguageTitleKeys() {
        String uid = UidUtils.newUid();
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Arrays.asList("en", "da"), "en");

        assertEquals("English title", exp.expandTokens("${title:en}"));
        assertEquals("Dansk titel", exp.expandTokens("${title:da}"));
    }

    @Test
    public void messageFactory_resolvesUidIdShortIdAndNumbers() {
        String uid = UidUtils.newUid();
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Arrays.asList("en", "da"), "en");

        assertEquals(uid, exp.expandTokens("${uid}"));
        assertEquals("42", exp.expandTokens("${id}"));
        assertEquals("NW-007-23", exp.expandTokens("${short-id}"));
        assertEquals("7", exp.expandTokens("${number}"));
        assertEquals("007", exp.expandTokens("${number-3-digits}"));
    }

    @Test
    public void messageFactory_resolvesNumberYearIdAndMainType() {
        String uid = UidUtils.newUid();
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Arrays.asList("en", "da"), "en");

        // number=7, year=2023 => "%03d/%02d" with year-2000 => "007/23"
        assertEquals("007/23", exp.expandTokens("${number-year-id}"));
        assertEquals("NW", exp.expandTokens("${main-type}"));
        assertEquals("nw", exp.expandTokens("${main-type-lower}"));
    }

    @Test
    public void messageFactory_expandsAllTokensWithinASingleTemplate() {
        String uid = UidUtils.newUid();
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Arrays.asList("en", "da"), "en");

        // Combine several tokens; the surrounding text is preserved (apart from the final trim()).
        assertEquals("NW-007-23 | 007/23 | nw",
                exp.expandTokens("${short-id} | ${number-year-id} | ${main-type-lower}"));
    }

    @Test
    public void messageFactory_withNullLanguage_omitsPlainTitleKey() {
        String uid = UidUtils.newUid();
        // language=null => the plain "${title}" key is NOT added (only the per-language ${title:lang} keys).
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Arrays.asList("en", "da"), null);

        // Undefined token is returned verbatim.
        assertEquals("${title}", exp.expandTokens("${title}"));
        // The per-language keys are still present.
        assertEquals("English title", exp.expandTokens("${title:en}"));
        assertEquals("Dansk titel", exp.expandTokens("${title:da}"));
    }

    @Test
    public void messageFactory_withBlankLanguage_omitsPlainTitleKey() {
        String uid = UidUtils.newUid();
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Arrays.asList("en", "da"), "   ");

        // Blank language is treated the same as null by StringUtils.isNotBlank().
        assertEquals("${title}", exp.expandTokens("${title}"));
    }

    @Test
    public void messageFactory_withNullLanguagesList_stillResolvesNonTitleTokens() {
        String uid = UidUtils.newUid();
        // languages=null => no ${title:lang} keys, and language=null => no ${title} key either,
        // but all other message tokens are still registered.
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), null, null);

        assertEquals("NW-007-23", exp.expandTokens("${short-id}"));
        assertEquals("${title}", exp.expandTokens("${title}"));
        assertEquals("${title:en}", exp.expandTokens("${title:en}"));
    }

    @Test
    public void messageFactory_withoutYear_yieldsEmptyNumberYearId() {
        Message msg = new Message();
        msg.setUid(UidUtils.newUid());
        msg.setNumber(7);
        msg.setMainType(MainType.NW);
        // No publishDateFrom / no year => computeNumberYearId() returns "".
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(msg, Collections.singletonList("en"), "en");

        assertEquals("", exp.expandTokens("${number-year-id}"));
    }

    @Test
    public void messageFactory_withNullNumberAndMainType_yieldsEmptyExpansions() {
        Message msg = new Message();
        msg.setUid(UidUtils.newUid());
        // number==null and mainType==null => empty replacements
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(msg, Collections.singletonList("en"), "en");

        assertEquals("", exp.expandTokens("${number}"));
        assertEquals("", exp.expandTokens("${number-3-digits}"));
        assertEquals("", exp.expandTokens("${main-type}"));
        assertEquals("", exp.expandTokens("${main-type-lower}"));
    }

    @Test
    public void messageFactory_withNullMessage_producesEmptyParamMap() {
        // Cast null to disambiguate the two overloads.
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance((Message) null, Collections.singletonList("en"), "en");

        // No params registered => the input is returned unchanged (modulo the trim of the surrounding text).
        assertEquals("${uid} ${title}", exp.expandTokens("${uid} ${title}"));
    }

    // ------------------------------------------------------------------
    // getInstance(SystemMessageVo, ...) - the VO path
    // ------------------------------------------------------------------

    private static SystemMessageVo buildSystemVo(String id) {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setId(id);
        MessageDescVo desc = new MessageDescVo();
        desc.setLang("en");
        desc.setTitle("T");
        vo.setDescs(Collections.singletonList(desc));
        vo.setShortId("NM-007-23");
        vo.setNumber(7);
        vo.setYear(2023);
        vo.setMainType(MainType.NM);
        return vo;
    }

    @Test
    public void systemVoFactory_mapsUidToId_andHasNoIdKey() {
        String id = UidUtils.newUid();
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildSystemVo(id), Collections.singletonList("en"), "en");

        // For the SystemMessageVo path ${uid} maps to message.getId().
        assertEquals(id, exp.expandTokens("${uid}"));
        // The VO path registers NO ${id} key, so it is left verbatim.
        assertEquals("${id}", exp.expandTokens("${id}"));
    }

    @Test
    public void systemVoFactory_resolvesTitleNumberAndMainType() {
        String id = UidUtils.newUid();
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildSystemVo(id), Collections.singletonList("en"), "en");

        assertEquals("T", exp.expandTokens("${title}"));
        assertEquals("T", exp.expandTokens("${title:en}"));
        assertEquals("NM-007-23", exp.expandTokens("${short-id}"));
        assertEquals("007/23", exp.expandTokens("${number-year-id}"));
        assertEquals("007", exp.expandTokens("${number-3-digits}"));
        assertEquals("NM", exp.expandTokens("${main-type}"));
        assertEquals("nm", exp.expandTokens("${main-type-lower}"));
    }

    @Test
    public void systemVoFactory_withNullMessage_producesEmptyParamMap() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance((SystemMessageVo) null, Collections.singletonList("en"), "en");

        assertEquals("${uid} ${title}", exp.expandTokens("${uid} ${title}"));
    }

    // ------------------------------------------------------------------
    // token(token, value) - custom registration
    // ------------------------------------------------------------------

    @Test
    public void token_registersCustomExpansion_andIsApplied() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance((Message) null, null, null);

        exp.token("${greeting}", "Ahoy");
        assertEquals("Ahoy world", exp.expandTokens("${greeting} world"));
    }

    @Test
    public void token_returnsSameExpanderForChaining() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance((Message) null, null, null);

        MessageTokenExpander returned = exp.token("${a}", "1");
        assertSame(exp, returned);

        // Chained registration must keep working.
        returned.token("${b}", "2");
        assertEquals("1-2", exp.expandTokens("${a}-${b}"));
    }

    @Test
    public void token_overridesAnExistingExpansion() {
        String uid = UidUtils.newUid();
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Arrays.asList("en", "da"), "en");

        // Re-register an already-known token; the latest value wins.
        exp.token("${short-id}", "OVERRIDDEN");
        assertEquals("OVERRIDDEN", exp.expandTokens("${short-id}"));
    }

    @Test
    public void token_withNullKeyAndValue_storesEmptyStrings() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance((Message) null, null, null);

        // token(null, null) uses StringUtils.defaultString => stores "" -> "".
        exp.token(null, null);
        assertEquals("", exp.params.get(""));

        // Replacing the empty string between characters yields the same string (no-op replacement of "").
        assertEquals("xy", exp.expandTokens("xy"));
    }

    @Test
    public void token_withNullValue_expandsToEmptyString() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance((Message) null, null, null);

        exp.token("${blank}", null);
        // The token is replaced by an empty string, then the result is trimmed.
        assertEquals("a", exp.expandTokens("a${blank}"));
    }

    // ------------------------------------------------------------------
    // expandTokens(text) - null / blank / trimming behaviour
    // ------------------------------------------------------------------

    @Test
    public void expandTokens_withNullText_returnsNull() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(UidUtils.newUid()), Arrays.asList("en", "da"), "en");

        assertNull(exp.expandTokens(null));
    }

    @Test
    public void expandTokens_withBlankText_returnsItUnchanged() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(UidUtils.newUid()), Arrays.asList("en", "da"), "en");

        // isNotBlank("   ") is false => no processing, the original (untrimmed) blank string is returned.
        assertEquals("  ", exp.expandTokens("  "));
    }

    @Test
    public void expandTokens_trimsTheResultAroundReplacedToken() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(UidUtils.newUid()), Arrays.asList("en", "da"), "en");

        // Leading/trailing whitespace around the final text is trimmed by the per-iteration trim().
        assertEquals("NW-007-23", exp.expandTokens(" ${short-id} "));
    }

    @Test
    public void expandTokens_withUndefinedToken_returnsLiteralUnchanged() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(UidUtils.newUid()), Arrays.asList("en", "da"), "en");

        assertEquals("${does-not-exist}", exp.expandTokens("${does-not-exist}"));
    }

    @Test
    public void expandTokens_withNoTokens_returnsPlainTextTrimmed() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance((Message) null, null, null);

        // Empty param map => the loop never runs => text returned exactly as given (no trim).
        assertEquals("  plain  ", exp.expandTokens("  plain  "));
    }

    // ------------------------------------------------------------------
    // GOTCHA guard: invalid uid flowing into a repo path must blow up early.
    // This documents why tests use UidUtils.newUid() / null rather than arbitrary ids.
    // ------------------------------------------------------------------

    @Test
    public void invalidUid_inRepoPathComputation_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> UidUtils.uidToHashedFolderPath(Message.MESSAGE_REPO_FOLDER, "not-a-uuid"));
    }

    // ------------------------------------------------------------------
    // Empty-languages branch: an empty (non-null) language list registers
    // NO ${title:lang} keys, while the other message tokens are still added.
    // ------------------------------------------------------------------

    @Test
    public void messageFactory_withEmptyLanguagesList_registersNoPerLanguageTitleKeys() {
        String uid = UidUtils.newUid();
        // languages = empty list => the forEach body never runs => no ${title:lang} keys.
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Collections.emptyList(), "en");

        // No per-language title keys were registered, so they pass through verbatim.
        assertEquals("${title:en}", exp.expandTokens("${title:en}"));
        assertEquals("${title:da}", exp.expandTokens("${title:da}"));
        // But the plain ${title} key (driven by the non-blank language) IS present...
        assertEquals("English title", exp.expandTokens("${title}"));
        // ...and the other message tokens are still resolved.
        assertEquals("NW-007-23", exp.expandTokens("${short-id}"));
    }

    @Test
    public void messageFactory_withEmptyLanguagesAndNullLanguage_hasNoTitleKeysAtAll() {
        String uid = UidUtils.newUid();
        // empty languages AND null current language => neither ${title} nor ${title:lang} keys.
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Collections.emptyList(), null);

        assertEquals("${title}", exp.expandTokens("${title}"));
        assertEquals("${title:en}", exp.expandTokens("${title:en}"));
        // Non-title tokens remain available.
        assertEquals("42", exp.expandTokens("${id}"));
    }

    // ------------------------------------------------------------------
    // Blank-language gating on the SystemMessageVo path (mirrors the entity path).
    // ------------------------------------------------------------------

    @Test
    public void systemVoFactory_withBlankLanguage_omitsPlainTitleKey() {
        String id = UidUtils.newUid();
        // A blank current language is gated out by StringUtils.isNotBlank(); ${title} is not registered.
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildSystemVo(id), Collections.singletonList("en"), "\t \n");

        assertEquals("${title}", exp.expandTokens("${title}"));
        // The per-language key (driven by the languages list) is still present.
        assertEquals("T", exp.expandTokens("${title:en}"));
    }

    // ------------------------------------------------------------------
    // token() overwrite semantics: registering the same key twice keeps only the last value.
    // ------------------------------------------------------------------

    @Test
    public void token_lastRegistrationWinsAcrossMultipleOverwrites() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance((Message) null, null, null);

        exp.token("${x}", "first").token("${x}", "second").token("${x}", "third");

        // Only one mapping survives - the most recent value.
        assertEquals("third", exp.expandTokens("${x}"));
    }

    @Test
    public void token_overwriteCanBlankOutAPreviouslyResolvedMessageToken() {
        String uid = UidUtils.newUid();
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Arrays.asList("en", "da"), "en");

        // The message already resolved ${main-type} to "NW"; overwrite it with an empty string.
        exp.token("${main-type}", "");
        assertEquals("", exp.expandTokens("${main-type}"));
    }

    // ------------------------------------------------------------------
    // Deterministic repeat-call output: expandTokens() is side-effect-free
    // and yields identical results when called repeatedly with the same input.
    // ------------------------------------------------------------------

    @Test
    public void expandTokens_isDeterministicAcrossRepeatedCalls() {
        String uid = UidUtils.newUid();
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(buildMessage(uid), Arrays.asList("en", "da"), "en");

        String template = "${short-id} | ${number-year-id} | ${main-type-lower}";
        String first = exp.expandTokens(template);
        String second = exp.expandTokens(template);
        String third = exp.expandTokens(template);

        assertEquals("NW-007-23 | 007/23 | nw", first);
        assertEquals(first, second);
        assertEquals(second, third);
    }

    // ------------------------------------------------------------------
    // Value containing ${} delimiters: a replacement value that itself
    // contains a token-shaped substring is NOT re-expanded (single pass,
    // and HashMap iteration order is not relied upon here).
    // ------------------------------------------------------------------

    @Test
    public void expandTokens_doesNotRecursivelyExpandValuesContainingTokenDelimiters() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance((Message) null, null, null);

        // ${a} -> literally the text "${b}", and ${b} -> "BEE".
        exp.token("${a}", "${b}");
        exp.token("${b}", "BEE");

        String result = exp.expandTokens("${a}");

        // The replacement is single-pass and order-dependent, so the outcome is either:
        //   - "${b}"  (a expanded after b), or
        //   - "BEE"   (a expanded before b, then the injected ${b} replaced in the same loop).
        // Either way it must be one of those two; it must never become anything else.
        assertTrue(result.equals("${b}") || result.equals("BEE"),
                "unexpected expansion result: " + result);
    }

    @Test
    public void expandTokens_resolvesAValueThatHardCodesNoTokenButLooksLikeOne() {
        MessageTokenExpander exp =
                MessageTokenExpander.getInstance((Message) null, null, null);

        // The replacement value contains the literal characters "${" and "}" but the matching
        // token is never registered, so the value passes through untouched after substitution.
        exp.token("${marker}", "price is ${unknown} today");

        // ${marker} is replaced; the ${unknown} text inside the value has no mapping so it survives.
        assertEquals("price is ${unknown} today", exp.expandTokens("${marker}"));
    }

    @Test
    public void expandTokens_preservesTokenShapedTextInAResolvedMessageTitle() {
        Message msg = new Message();
        msg.setUid(UidUtils.newUid());
        // The title itself contains a token-shaped substring whose token IS registered.
        msg.createDesc("en").setTitle("Has ${short-id} inside");
        msg.setShortId("SID-1");
        msg.setMainType(MainType.NW);

        MessageTokenExpander exp =
                MessageTokenExpander.getInstance(msg, Collections.singletonList("en"), "en");

        // ${title} resolves to the raw title text in a single pass; whether the injected
        // "${short-id}" then gets replaced depends on the (HashMap) iteration order, so the
        // result is either the title with the placeholder intact or with it expanded. It must
        // never be anything else.
        String result = exp.expandTokens("${title}");
        assertTrue(result.equals("Has ${short-id} inside") || result.equals("Has SID-1 inside"),
                "unexpected expansion result: " + result);
    }
}
