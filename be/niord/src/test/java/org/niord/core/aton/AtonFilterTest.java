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

package org.niord.core.aton;

import org.junit.jupiter.api.Test;
import org.niord.core.aton.vo.AtonNodeVo;
import org.niord.core.aton.vo.AtonTagVo;

import javax.script.ScriptException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for {@link AtonFilter}.
 *
 * <p>{@code AtonFilter} parses a small JavaScript expression with the AtoN filter grammar into a Java
 * predicate (see {@code AtonFilterGrammar}); no script engine is involved. The expression calls the
 * matcher methods {@link AtonNodeVo#k(String)}, {@link AtonNodeVo#v(String)} and
 * {@link AtonNodeVo#kv(String, String)}, all of which use Java's anchored
 * {@link String#matches(String)} semantics (full-string regex match), and combines them with
 * {@code !}, {@code &&} and {@code ||} as JavaScript does.
 */
public class AtonFilterTest {

    /*
     * Oracle notes (verified against production source):
     *
     * The fixture AtoN (a buoy_cardinal, mirrored from AtonTest#createAtonNode) carries
     * the tags:
     *   seamark:buoy_cardinal:category        = north
     *   seamark:buoy_cardinal:colour          = black;yellow
     *   seamark:buoy_cardinal:colour_pattern  = horizontal
     *   seamark:buoy_cardinal:shape           = pillar
     *   seamark:light:character               = VQ
     *   seamark:light:colour                  = white
     *   seamark:name                          = Calshot
     *   seamark:topmark:colour                = black
     *   seamark:topmark:shape                 = 2 cones up
     *   seamark:type                          = buoy_cardinal
     *
     * kv/k/v all use String.matches(), which is FULL-STRING anchored matching.
     */

    // ------------------------------------------------------------------
    // kv(...) - key + value pattern matching
    // ------------------------------------------------------------------

    @Test
    public void kvMatchesBuoyType() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();

        // seamark:type == "buoy_cardinal" which matches regex "buoy.*"
        AtonFilter filter = AtonFilter.getInstance("aton.kv('seamark:type', 'buoy.*')");

        assertTrue(filter.matches(aton),
                "buoy_cardinal aton should match kv('seamark:type','buoy.*')");
    }

    @Test
    public void kvDoesNotMatchLightTypeForBuoy() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();

        // The only "seamark:type" tag has value "buoy_cardinal", which does NOT match "light.*"
        AtonFilter filter = AtonFilter.getInstance("aton.kv('seamark:type', 'light.*')");

        assertFalse(filter.matches(aton),
                "buoy aton must not match kv('seamark:type','light.*')");
    }

    @Test
    public void kvIsAnchoredFullStringMatch() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();

        // String.matches is anchored: "buoy_cardinal" is matched fully by "buoy.*"
        assertTrue(AtonFilter.getInstance("aton.kv('seamark:type', 'buoy_cardinal')").matches(aton));

        // ... but a trailing-truncated pattern "buoy" (no wildcard) does NOT match "buoy_cardinal"
        assertFalse(AtonFilter.getInstance("aton.kv('seamark:type', 'buoy')").matches(aton),
                "partial pattern 'buoy' must not full-match 'buoy_cardinal'");
    }

    @Test
    public void kvMatchesAcrossDifferentTags() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();

        // seamark:light:colour == "white"
        assertTrue(AtonFilter.getInstance("aton.kv('seamark:light:colour', 'white')").matches(aton));

        // No tag has key seamark:light:colour with value "red"
        assertFalse(AtonFilter.getInstance("aton.kv('seamark:light:colour', 'red')").matches(aton));
    }

    // ------------------------------------------------------------------
    // k(...) - key-only pattern matching
    // ------------------------------------------------------------------

    @Test
    public void kMatchesExistingKey() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();

        assertTrue(AtonFilter.getInstance("aton.k('seamark:type')").matches(aton),
                "aton has a 'seamark:type' tag key");
    }

    @Test
    public void kDoesNotMatchMissingKey() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();

        assertFalse(AtonFilter.getInstance("aton.k('seamark:doesnotexist')").matches(aton),
                "aton has no 'seamark:doesnotexist' tag key");
    }

    @Test
    public void kSupportsRegexKeyPattern() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();

        // Regex matches all four "seamark:buoy_cardinal:..." keys
        assertTrue(AtonFilter.getInstance("aton.k('seamark:buoy_cardinal:.*')").matches(aton));
    }

    // ------------------------------------------------------------------
    // v(...) - value-only pattern matching
    // ------------------------------------------------------------------

    @Test
    public void vMatchesExistingValue() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();

        // seamark:light:colour == "white" is present as a tag value
        assertTrue(AtonFilter.getInstance("aton.v('white')").matches(aton),
                "the colour value 'white' is present");
    }

    @Test
    public void vDoesNotMatchAbsentValue() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();

        // No tag value equals "green"
        assertFalse(AtonFilter.getInstance("aton.v('green')").matches(aton),
                "no tag value equals 'green'");
    }

    @Test
    public void vIsAnchoredFullStringMatch() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();

        // "whit" is a prefix of "white" but String.matches is anchored => no full match
        assertFalse(AtonFilter.getInstance("aton.v('whit')").matches(aton),
                "anchored match: 'whit' must not match value 'white'");

        // A wildcard restores the match
        assertTrue(AtonFilter.getInstance("aton.v('whit.*')").matches(aton));
    }

    // ------------------------------------------------------------------
    // matches(List<AtonNodeVo>) - ALL must match
    // ------------------------------------------------------------------

    @Test
    public void listMatchesWhenAllMatch() throws ScriptException {
        AtonFilter filter = AtonFilter.getInstance("aton.kv('seamark:type', 'buoy.*')");

        List<AtonNodeVo> atons = Arrays.asList(createBuoyAton(), createBuoyAton());

        assertTrue(filter.matches(atons),
                "all buoy atons match the buoy filter");
    }

    @Test
    public void listDoesNotMatchWhenOneFails() throws ScriptException {
        AtonFilter filter = AtonFilter.getInstance("aton.kv('seamark:type', 'buoy.*')");

        // A light aton (seamark:type == "light_major") does not match "buoy.*"
        List<AtonNodeVo> atons = Arrays.asList(createBuoyAton(), createLightAton());

        assertFalse(filter.matches(atons),
                "a mixed list with one non-matching aton must not match");
    }

    @Test
    public void emptyListMatchesVacuously() throws ScriptException {
        AtonFilter filter = AtonFilter.getInstance("aton.kv('seamark:type', 'buoy.*')");

        // Stream.allMatch on an empty stream returns true (vacuous truth)
        assertTrue(filter.matches(new ArrayList<>()),
                "an empty list satisfies the filter vacuously");
    }

    // ------------------------------------------------------------------
    // Error / edge behavior
    // ------------------------------------------------------------------

    @Test
    public void syntacticallyInvalidFilterThrowsScriptException() {
        // "aton.kv(" is an unfinished term; the grammar check refuses it with a ScriptException.
        try {
            AtonFilter.getInstance("aton.kv(");
            fail("expected ScriptException for a syntactically invalid filter expression");
        } catch (ScriptException expected) {
            // success
        }
    }

    @Test
    public void unknownMatcherMethodIsRefusedByTheGrammar() {
        // Used to compile and fail at invocation time (matches() returned false). Only k, v and kv
        // are terms of the grammar, so the filter is now refused before it is compiled.
        assertThrows(ScriptException.class, () -> AtonFilter.getInstance("aton.nonExistentMethod()"));
    }

    @Test
    public void nonBooleanExpressionIsRefusedByTheGrammar() {
        // Used to compile and return a string, which matches() turned into false. A bare literal is not
        // a term of the grammar, so the filter is now refused before it is compiled.
        assertThrows(ScriptException.class, () -> AtonFilter.getInstance("'not a boolean'"));
    }

    // ------------------------------------------------------------------
    // Grammar (SECAUD-1, F-X22)
    //
    // The filter text comes from request bodies as well as from category and dictionary data. It must
    // be an expression of aton.k/v/kv terms with string literals, joined by &&, || and ! and grouped
    // by parentheses; anything else is refused. The filter is never run as script.
    // ------------------------------------------------------------------

    @Test
    public void benignFiltersStillMatch() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();

        // Regression guard: the filters the categories and dictionaries use keep their meaning now that
        // they are parsed into a predicate instead of being run on a script engine.
        assertTrue(AtonFilter.getInstance("aton.kv('seamark:type', 'buoy.*')").matches(aton));
        assertTrue(AtonFilter.getInstance("aton.k('seamark:light:colour') && aton.v('white')").matches(aton));
        assertFalse(AtonFilter.getInstance("aton.kv('seamark:type', 'light.*')").matches(aton));
    }

    @Test
    public void everySeedFilterShapeIsAccepted() throws ScriptException {
        // The shapes the category and dictionary seeds use, plus the whitespace and quoting variants
        // JavaScript allows for them.
        for (String filter : List.of(
                "aton.kv('seamark:type', 'light.*')",
                "aton.kv('seamark:type', 'buoy.*') && aton.k('seamark:light.*')",
                "aton.kv('seamark:type', 'buoy_special_purpose') && !aton.k('seamark:light.*')",
                "aton.kv('seamark:light:category', 'front|rear')",
                "aton.kv('seamark:light:.*:category', 'directional')",
                "(aton.kv('seamark:type', 'buoy_cardinal') || aton.kv('seamark:type', 'light.*')) && aton.v('white')",
                "!(aton.k('seamark:a') || aton.k('seamark:b'))",
                "aton.k(\"seamark:type\")",
                "  aton . kv ( 'seamark:type' ,'buoy.*' )  ",
                nested(AtonFilterGrammar.MAX_DEPTH))) {
            AtonFilter.getInstance(filter);
        }

        AtonNodeVo aton = createBuoyAton();
        assertTrue(AtonFilter.getInstance(
                "(aton.kv('seamark:type', 'light.*') || aton.kv('seamark:type', 'buoy.*')) && !aton.k('seamark:fog.*')")
                .matches(aton));
    }

    @Test
    public void globalFunctionCallsAreRefused() {
        // Refused by the grammar, so none of these is ever compiled, let alone called.
        for (String filter : List.of("quit()", "exit(0)", "load('file:x.js')", "loadWithNewGlobal('x')",
                "aton.k('a') || quit()", "print('x') || aton.k('a')")) {
            assertThrows(ScriptException.class, () -> AtonFilter.getInstance(filter), filter);
        }
    }

    @Test
    public void loopsAndStatementsAreRefused() {
        for (String filter : List.of(
                "(function () { while (true) {} })()",
                "aton.k('a') && (function () { for (;;) {} })()",
                "true; } function other() { return true",
                "aton.k('a'); x = 1",
                "aton.k('a') ? true : false",
                "aton.k('a' + 'b')",
                "aton.k('a\\u0027')",
                "aton.kv('a')",
                "aton.k('a', 'b')",
                "aton['k']('a')",
                "aton.k('a') &",
                "(aton.k('a')",
                nested(AtonFilterGrammar.MAX_DEPTH + 1))) {
            assertThrows(ScriptException.class, () -> AtonFilter.getInstance(filter), filter);
        }
        assertThrows(ScriptException.class, () -> AtonFilter.getInstance(null));
        assertThrows(ScriptException.class, () -> AtonFilter.getInstance(" "));
        assertThrows(ScriptException.class,
                () -> AtonFilter.getInstance("aton.k('" + "a".repeat(AtonFilterGrammar.MAX_LENGTH) + "')"));
    }

    @Test
    public void javaTypeAccessIsRefused() {
        // Pre-fix (unsandboxed): Java.type resolves java.lang.Runtime and this returns true. It is not a
        // term of the grammar, so it is refused.
        assertThrows(ScriptException.class,
                () -> AtonFilter.getInstance("Java.type('java.lang.Runtime') != null"));
    }

    @Test
    public void injectedFilterCannotExecuteCode() {
        // Side-effect oracle: a filter that tries to run host code via Java.type must never have its side
        // effect take place. It is refused by the grammar, so it is never compiled or run.
        final String probe = "secaud1.aton.pwned";
        System.clearProperty(probe);
        try {
            assertThrows(ScriptException.class, () -> AtonFilter.getInstance(
                    "Java.type('java.lang.System').setProperty('" + probe + "', 'yes') == null")
                    .matches(createBuoyAton()));
            assertNull(System.getProperty(probe),
                    "the filter is refused before it runs — no arbitrary code execution");
        } finally {
            System.clearProperty(probe);
        }
    }

    @Test
    public void operatorsBindAsInJavaScript() throws ScriptException {
        AtonNodeVo aton = createBuoyAton();
        String yes = "aton.k('seamark:type')";
        String no = "aton.k('seamark:none')";

        // && binds tighter than ||: yes || (no && no), not (yes || no) && no
        assertTrue(AtonFilter.getInstance(yes + " || " + no + " && " + no).matches(aton));
        // ! binds to the term: (!no) && yes, and (!yes) || yes
        assertTrue(AtonFilter.getInstance("!" + no + " && " + yes).matches(aton));
        assertTrue(AtonFilter.getInstance("!" + yes + " || " + yes).matches(aton));
        // Repeated negation, and negation of a group
        assertTrue(AtonFilter.getInstance("!!" + yes).matches(aton));
        assertFalse(AtonFilter.getInstance("!!!" + yes).matches(aton));
        assertFalse(AtonFilter.getInstance("!(" + no + " || " + yes + ")").matches(aton));
        // Parentheses override precedence
        assertFalse(AtonFilter.getInstance("(" + yes + " || " + no + ") && " + no).matches(aton));
        // Double-quoted literals mean the same as single-quoted ones
        assertTrue(AtonFilter.getInstance("aton.kv(\"seamark:type\", \"buoy.*\")").matches(aton));
    }

    @Test
    public void aTermThatFailsMatchesNothing() throws ScriptException {
        // An invalid regular expression fails when it is used; the filter then matches nothing, as when it
        // ran as script.
        assertFalse(AtonFilter.getInstance("aton.k('[')").matches(createBuoyAton()));
        // ... and only when it is used: || stops at the first match
        assertTrue(AtonFilter.getInstance("aton.k('seamark:type') || aton.k('[')").matches(createBuoyAton()));
        // No AtoN at all
        assertFalse(AtonFilter.getInstance("aton.k('seamark:type')").matches((AtonNodeVo) null));
    }

    @Test
    public void aSlowMatchDoesNotHoldUpOtherMatches() throws Exception {
        // The patterns are regular expressions from the filter text, and a match has no time limit. One slow
        // match must occupy only its own thread: nothing is shared between matches.
        CountDownLatch inSlowMatch = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtonNodeVo slowAton = new AtonNodeVo() {
            @Override
            public boolean k(String keyPattern) {
                inSlowMatch.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }
        };
        AtonFilter filter = AtonFilter.getInstance("aton.k('seamark:type')");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> slow = executor.submit(() -> filter.matches(slowAton));
            assertTrue(inSlowMatch.await(10, TimeUnit.SECONDS), "the slow match has started");

            Future<Boolean> other = executor.submit(
                    () -> AtonFilter.getInstance("aton.v('white')").matches(createBuoyAton()));
            assertTrue(other.get(10, TimeUnit.SECONDS), "another match completes while the slow one runs");

            release.countDown();
            assertTrue(slow.get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void aLineBreakBetweenTokensIsWhitespace() throws ScriptException {
        assertTrue(AtonFilter.getInstance("\naton.k('seamark:type')").matches(createBuoyAton()));
        assertTrue(AtonFilter.getInstance("aton.k('seamark:none') ||\r\n aton.k('seamark:type')")
                .matches(createBuoyAton()));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A k-term inside the given number of parentheses */
    private static String nested(int depth) {
        return "(".repeat(depth) + "aton.k('seamark:type')" + ")".repeat(depth);
    }

    /**
     * Builds a buoy_cardinal seamark AtoN, mirroring AtonTest#createAtonNode.
     */
    private AtonNodeVo createBuoyAton() {
        AtonNodeVo aton = new AtonNodeVo();
        aton.setId(672436827);
        aton.setLat(50.8070813);
        aton.setLon(-1.2841124);
        aton.setUser("malcolmh");
        aton.setUid(128186);
        aton.setVisible(true);
        aton.setVersion(11);
        aton.setChangeset(9107813);
        aton.setTimestamp(new Date(1314134556000L));

        List<AtonTagVo> tags = new ArrayList<>();
        tags.add(new AtonTagVo("seamark:buoy_cardinal:category", "north"));
        tags.add(new AtonTagVo("seamark:buoy_cardinal:colour", "black;yellow"));
        tags.add(new AtonTagVo("seamark:buoy_cardinal:colour_pattern", "horizontal"));
        tags.add(new AtonTagVo("seamark:buoy_cardinal:shape", "pillar"));
        tags.add(new AtonTagVo("seamark:light:character", "VQ"));
        tags.add(new AtonTagVo("seamark:light:colour", "white"));
        tags.add(new AtonTagVo("seamark:name", "Calshot"));
        tags.add(new AtonTagVo("seamark:topmark:colour", "black"));
        tags.add(new AtonTagVo("seamark:topmark:shape", "2 cones up"));
        tags.add(new AtonTagVo("seamark:type", "buoy_cardinal"));
        aton.setTags(tags.toArray(new AtonTagVo[0]));
        return aton;
    }

    /**
     * Builds a light AtoN whose seamark:type does NOT match the buoy filter.
     */
    private AtonNodeVo createLightAton() {
        AtonNodeVo aton = new AtonNodeVo();
        aton.setId(100000001);
        aton.setLat(55.0);
        aton.setLon(12.0);
        aton.setUser("tester");
        aton.setUid(1);
        aton.setVisible(true);
        aton.setVersion(1);
        aton.setChangeset(1);
        aton.setTimestamp(new Date(1314134556000L));

        List<AtonTagVo> tags = new ArrayList<>();
        tags.add(new AtonTagVo("seamark:type", "light_major"));
        tags.add(new AtonTagVo("seamark:light:colour", "red"));
        aton.setTags(tags.toArray(new AtonTagVo[0]));
        return aton;
    }
}
