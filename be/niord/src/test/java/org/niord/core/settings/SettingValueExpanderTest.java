/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.core.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Framework-neutral unit tests for {@link SettingValueExpander}, the helper used to expand
 * "${token}" placeholders inside a Setting value.
 *
 * <p>The expander is pure in-memory string logic: no database, no CDI, no Quarkus. These tests
 * exercise the public contract ({@link SettingValueExpander#nextToken()},
 * {@link SettingValueExpander#replaceToken(String, String)} and
 * {@link SettingValueExpander#getValue()}) including the recursion guard, the
 * MAX_REPLACEMENT_NO cap, and the blank-input no-op branches.
 */
public class SettingValueExpanderTest {

    /** 1. nextToken() returns the bare token name (without the ${...} wrapper) of the first match. */
    @Test
    public void nextToken_returnsFirstTokenName() {
        assertEquals("x", new SettingValueExpander("a-${x}-b").nextToken());
    }

    /** 2. nextToken() returns null when the value contains no ${...} token at all. */
    @Test
    public void nextToken_returnsNullWhenNoToken() {
        assertNull(new SettingValueExpander("plain text").nextToken());
    }

    /** 3. nextToken() returns null (the documented sanity check) when the value itself is null. */
    @Test
    public void nextToken_returnsNullWhenValueIsNull() {
        assertNull(new SettingValueExpander(null).nextToken());
    }

    /**
     * 4. replaceToken() replaces EVERY occurrence of ${token} and records the token, so the same
     * token is never offered again by nextToken() (recursion guard via replacedTokens).
     */
    @Test
    public void replaceToken_replacesAllOccurrencesAndRecordsToken() {
        SettingValueExpander expander = new SettingValueExpander("${a}${a}");

        expander.replaceToken("a", "Z");

        assertEquals("ZZ", expander.getValue(), "Both occurrences must be replaced");
        assertNull(expander.nextToken(), "An already-replaced token must not be offered again");
    }

    /**
     * 5. Classic multi-token flow: walk nextToken()/replaceToken() for three distinct tokens and
     * verify the fully-expanded value.
     */
    @Test
    public void multiTokenFlow_expandsAllDistinctTokensInOrder() {
        SettingValueExpander expander = new SettingValueExpander("${a}${b}${c}");

        assertEquals("a", expander.nextToken());
        expander.replaceToken("a", "A");
        assertEquals("b", expander.nextToken());
        expander.replaceToken("b", "B");
        assertEquals("c", expander.nextToken());
        expander.replaceToken("c", "C");

        assertEquals("ABC", expander.getValue());
        assertNull(expander.nextToken(), "No tokens should remain after the full expansion");
    }

    /**
     * 6a. replaceToken() with a blank (empty) replacement value now REMOVES the ${token} text and
     * records the token, so nextToken() no longer offers it. This is the fix that lets
     * expandSettingValue() drop an unresolvable token instead of spinning until the livelock guard
     * trips.
     */
    @Test
    public void replaceToken_blankReplacementValue_removesTokenAndRecordsIt() {
        SettingValueExpander expander = new SettingValueExpander("${a}");

        SettingValueExpander returned = expander.replaceToken("a", "");

        assertSame(expander, returned, "replaceToken must return the same instance");
        assertEquals("", expander.getValue(), "A blank replacement must remove the ${token} text");
        // The token was recorded, so it is no longer offered for replacement.
        assertNull(expander.nextToken(), "An already-replaced token must not be offered again");
    }

    /**
     * 6b. replaceToken() is a no-op returning {@code this} when the value is blank (nothing to
     * replace into).
     */
    @Test
    public void replaceToken_blankValue_isNoOpAndReturnsThis() {
        SettingValueExpander expander = new SettingValueExpander("");

        SettingValueExpander returned = expander.replaceToken("a", "A");

        assertSame(expander, returned);
        assertEquals("", expander.getValue());
    }

    /**
     * 6c. replaceToken() is a no-op returning {@code this} when the token is blank; the value
     * containing a real ${...} placeholder is left untouched.
     */
    @Test
    public void replaceToken_blankToken_isNoOpAndReturnsThis() {
        SettingValueExpander expander = new SettingValueExpander("${a}");

        SettingValueExpander returned = expander.replaceToken("", "A");

        assertSame(expander, returned);
        assertEquals("${a}", expander.getValue());
    }

    /**
     * 7. After MAX_REPLACEMENT_NO (5) distinct tokens have been replaced, nextToken() returns null
     * even though an unreplaced token (${f}) still remains in the value.
     */
    @Test
    public void nextToken_returnsNullAfterMaxReplacementsReached() {
        SettingValueExpander expander = new SettingValueExpander("${a}${b}${c}${d}${e}${f}");

        expander.replaceToken("a", "A");
        expander.replaceToken("b", "B");
        expander.replaceToken("c", "C");
        expander.replaceToken("d", "D");
        expander.replaceToken("e", "E");

        // ${f} is still present, but the 5-replacement cap refuses any further tokens.
        assertEquals("ABCDE${f}", expander.getValue(), "Five tokens must have been expanded");
        assertNull(expander.nextToken(), "The 6th distinct token must be refused once the cap is hit");
    }

    /**
     * 8. Recursion guard: once a token has been replaced, nextToken() returns null if the only
     * remaining placeholder is that same (already-replaced) token. This guards against a
     * replacement value that re-introduces the token it expanded from.
     */
    @Test
    public void nextToken_returnsNullWhenOnlyRemainingTokenWasAlreadyReplaced() {
        // Replacement value re-introduces "${a}", so the value still contains the token afterwards.
        SettingValueExpander expander = new SettingValueExpander("${a}");

        expander.replaceToken("a", "${a}");

        assertEquals("${a}", expander.getValue());
        assertNull(expander.nextToken(),
                "Already-replaced token must not be offered again, even if still present");
    }

    /** 9. getValue() reflects the current, partially-expanded value mid-flow. */
    @Test
    public void getValue_returnsCurrentPartiallyExpandedValue() {
        SettingValueExpander expander = new SettingValueExpander("${a}-${b}");

        assertEquals("${a}-${b}", expander.getValue(), "Initial value before any replacement");

        expander.replaceToken("a", "A");

        assertEquals("A-${b}", expander.getValue(), "Only the first token is expanded so far");
    }
}
