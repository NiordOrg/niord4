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
package org.niord.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link LuceneUtils#normalize(String)} and
 * {@link LuceneUtils#normalizeQuery(String)}.
 *
 * <p>{@code normalize} folds the accented characters listed in
 * {@code ACCENTED_CHARS} to their non-accented equivalents in
 * {@code REPLACED_CHARS}. {@code normalizeQuery} additionally upper-cases the
 * lowercase boolean operators {@code " or "} and {@code " and "} (only when
 * surrounded by spaces) so that Lucene treats them as query operators.
 */
public class LuceneUtilsTest {

    // ------------------------------------------------------------------
    // normalize(String)
    // ------------------------------------------------------------------

    /** All four upper-case A variants fold to plain 'A'. */
    @Test
    public void normalize_uppercaseAAccentsFoldToA() {
        assertEquals("AAAA", LuceneUtils.normalize("ÁÀÄÂ"));
    }

    /** Lower-case a variants fold to plain 'a'. */
    @Test
    public void normalize_lowercaseAAccentsFoldToA() {
        assertEquals("aaaa", LuceneUtils.normalize("áàäâ"));
    }

    /** The tilde-n characters fold preserving case. */
    @Test
    public void normalize_tildeNPreservesCase() {
        assertEquals("nN", LuceneUtils.normalize("ñÑ"));
    }

    /** Mixed E variants fold to 'E'/'e' preserving case per position. */
    @Test
    public void normalize_mixedEAccentsFoldPreservingCase() {
        assertEquals("Eee", LuceneUtils.normalize("Ëèê"));
    }

    /** A representative sweep across every accent group in the table. */
    @Test
    public void normalize_replacesAllAccentGroups() {
        assertEquals(
                "AAAAaaaaEEEEeeeeIIIIiiiiOOOOooooUUUUuuuuYyNn",
                LuceneUtils.normalize("ÁÀÄÂáàäâÉÈËÊéèëêÍÌÏÎíìïîÓÒÖÔóòöôÚÙÜÛúùüûÝýÑñ"));
    }

    /** Plain ASCII text passes through unchanged. */
    @Test
    public void normalize_plainAsciiUnchanged() {
        assertEquals("apple pear grapes", LuceneUtils.normalize("apple pear grapes"));
    }

    /** A word with one accent in the middle is folded but otherwise preserved. */
    @Test
    public void normalize_accentInsideWordFolded() {
        assertEquals("pear", LuceneUtils.normalize("pêar"));
    }

    /**
     * The Danish-specific letters 'å' and 'æ' are NOT in the replacement table,
     * so they pass through unchanged. This pins the (Danish-app-surprising)
     * behaviour that these are not folded.
     */
    @Test
    public void normalize_danishAaAndAePassThroughUnchanged_documentsBehavior() {
        assertEquals("åæ", LuceneUtils.normalize("åæ"));
        assertEquals("Å Æ", LuceneUtils.normalize("Å Æ"));
    }

    /** The empty string folds to the empty string. */
    @Test
    public void normalize_emptyStringReturnsEmpty() {
        assertEquals("", LuceneUtils.normalize(""));
    }

    /** A blank (whitespace-only) string is preserved verbatim. */
    @Test
    public void normalize_blankStringPreserved() {
        assertEquals("   ", LuceneUtils.normalize("   "));
    }

    /** {@code normalize(null)} returns null (StringUtils.replaceChars contract). */
    @Test
    public void normalize_nullReturnsNull() {
        assertNull(LuceneUtils.normalize(null));
    }

    /** Digits and punctuation are not accented and pass through unchanged. */
    @Test
    public void normalize_digitsAndPunctuationUnchanged() {
        assertEquals("123-foo_bar!?", LuceneUtils.normalize("123-foo_bar!?"));
    }

    // ------------------------------------------------------------------
    // normalizeQuery(String)
    // ------------------------------------------------------------------

    /** {@code normalizeQuery(null)} returns null. */
    @Test
    public void normalizeQuery_nullReturnsNull() {
        assertNull(LuceneUtils.normalizeQuery(null));
    }

    /** A bare spaced " and " operator is upper-cased. */
    @Test
    public void normalizeQuery_spacedAndUppercased() {
        assertEquals(" AND ", LuceneUtils.normalizeQuery(" and "));
    }

    /** A bare spaced " or " operator is upper-cased. */
    @Test
    public void normalizeQuery_spacedOrUppercased() {
        assertEquals(" OR ", LuceneUtils.normalizeQuery(" or "));
    }

    /** Folds accents AND upper-cases both operators in one pass. */
    @Test
    public void normalizeQuery_combinesAccentFoldingAndOperators() {
        assertEquals(
                "apple AND pear OR grapes",
                LuceneUtils.normalizeQuery("apple and pêar or grapes"));
    }

    /**
     * "android" contains the substring "and" but not the spaced operator
     * " and ", so the replacement does not fire and the word is unchanged.
     */
    @Test
    public void normalizeQuery_andInsideWordNotReplaced() {
        assertEquals("android", LuceneUtils.normalizeQuery("android"));
    }

    /**
     * Likewise "corridor" contains "or" but not the spaced operator " or ",
     * so it is left untouched.
     */
    @Test
    public void normalizeQuery_orInsideWordNotReplaced() {
        assertEquals("corridor", LuceneUtils.normalizeQuery("corridor"));
    }

    /** Already upper-cased operators are unaffected (only lowercase ones match). */
    @Test
    public void normalizeQuery_uppercaseOperatorsUnchanged() {
        assertEquals("apple AND pear OR grapes",
                LuceneUtils.normalizeQuery("apple AND pear OR grapes"));
    }

    /** An accented term with no operators is just folded. */
    @Test
    public void normalizeQuery_accentOnlyNoOperators() {
        assertEquals("pear", LuceneUtils.normalizeQuery("pêar"));
    }

    /** Empty input yields empty output (no operators to replace). */
    @Test
    public void normalizeQuery_emptyStringReturnsEmpty() {
        assertEquals("", LuceneUtils.normalizeQuery(""));
    }

    /**
     * Two consecutive spaced " or " operators: {@code replaceAll} is global,
     * so both occurrences are replaced.
     */
    @Test
    public void normalizeQuery_multipleOrOperatorsAllReplaced() {
        assertEquals("a OR b OR c", LuceneUtils.normalizeQuery("a or b or c"));
    }

    /**
     * Two operators that share a single space, e.g. "a or and b". The " or "
     * replacement runs first and produces "a OR and b" (the shared space is
     * left intact in the output). The subsequent " and " replacement is a
     * separate pass over that intermediate string, where the surrounding
     * spaces still exist, so it ALSO matches, yielding "a OR AND b". This pins
     * the current two-pass replacement behaviour.
     */
    @Test
    public void normalizeQuery_adjacentOperatorsBothReplaced() {
        assertEquals("a OR AND b", LuceneUtils.normalizeQuery("a or and b"));
    }
}
