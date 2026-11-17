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
package org.niord.core.util;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the plain text Niord makes from a message's HTML ({@link TextUtils#html2txt}, through
 * {@link HtmlToPlainText}): the first step of the NAVTEX and SafetyNET texts, the audio (radio) text and the S-124
 * warning's text, and the text the message search indexes. Then the NAVTEX and audio rewrite of positions
 * ({@link PositionUtils}, {@link PositionAssembler}). HTML generated from a fixed seed: paragraphs, headings, lists
 * and line breaks, inline mark-up the rich-text editor writes, text runs short and long, Danish letters, positions.
 */
public class HtmlToTextPropertyTest {

    private static final long SEED = 20261115L;

    private static final String[] WORDS = {
            "Wreck", "buoy", "Ærø", "Løgstør", "is", "the", "unlit", "57°", "01.7'N", "-", "003°", "30.6'E", "and",
            "Mariners", "are", "advised", "to", "keep", "clear.", "Nordmole", "(No.", "3)", "Østerrenden,", "Qaqortoq",
            "Survey", "vessel", "«POSEIDON»", "&amp;", "firing", "exercises", "2026-11-15", "08:00", "UTC."
    };

    private static final String[] INLINE = { "b", "i", "strong", "em", "u", "span" };

    /** A run of words, one text node or several, separated by single spaces inside or outside inline elements */
    private static String run(Random r, int words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            if (r.nextInt(6) == 0) {
                String tag = INLINE[r.nextInt(INLINE.length)];
                int inner = 1 + r.nextInt(3);
                sb.append('<').append(tag).append('>');
                for (int j = 0; j < inner; j++) {
                    if (j > 0) {
                        sb.append(' ');
                    }
                    sb.append(WORDS[r.nextInt(WORDS.length)]);
                }
                sb.append("</").append(tag).append('>');
            } else {
                sb.append(WORDS[r.nextInt(WORDS.length)]);
            }
        }
        return sb.toString();
    }

    /** A run of words in one text node, without inline mark-up and without white space at either end */
    private static String plainRun(Random r, int words) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(WORDS[r.nextInt(WORDS.length)]);
        }
        return sb.toString();
    }

    /**
     * The inline-run cause alone: blocks the formatter already breaks at (p, h3, a br inside a p), each holding a run
     * with inline mark-up that is well past the formatter's 80 columns. No div, list, table or blockquote.
     */
    private static String paragraphsWithLongInlineRuns(Random r) {
        StringBuilder sb = new StringBuilder();
        int blocks = 1 + r.nextInt(4);
        for (int b = 0; b < blocks; b++) {
            int words = 15 + r.nextInt(30);
            switch (r.nextInt(3)) {
                case 0 -> sb.append("<p>").append(run(r, words)).append("</p>");
                case 1 -> sb.append("<h3>").append(run(r, words)).append("</h3>");
                default -> sb.append("<p>").append(run(r, words)).append("<br>").append(run(r, words)).append("</p>");
            }
            if (r.nextBoolean()) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** The HTML5 sectioning elements and the other older blocks a source view or an import may hold */
    private static final String[] OLDER_BLOCKS = {
            "section", "article", "header", "footer", "aside", "nav", "main", "address", "figure", "center" };

    /**
     * The block-end cause alone: two to five blocks the formatter did not break at (div, ul, ol, table with td and
     * th, blockquote, a code block's pre, a run ended by a horizontal rule, h6, a sectioning or other older block,
     * a figure with its caption) with no white space between them, each holding a short run of plain words (under
     * the 80 columns, no inline mark-up, no white space at a node's end, so the inline-run cause cannot occur).
     */
    private static String blocksWithoutWhiteSpaceBetween(Random r) {
        StringBuilder sb = new StringBuilder();
        int blocks = 2 + r.nextInt(4);
        for (int b = 0; b < blocks; b++) {
            int words = 1 + r.nextInt(5);
            switch (r.nextInt(11)) {
                case 6 -> sb.append("<pre><code>").append(plainRun(r, words)).append("</code></pre>");
                case 7 -> sb.append(plainRun(r, words)).append("<hr>");
                case 8 -> sb.append("<h6>").append(plainRun(r, words)).append("</h6>");
                case 9 -> {
                    String tag = OLDER_BLOCKS[r.nextInt(OLDER_BLOCKS.length)];
                    sb.append('<').append(tag).append('>').append(plainRun(r, words)).append("</").append(tag)
                            .append('>');
                }
                case 10 -> sb.append("<figure>").append(plainRun(r, words)).append("<figcaption>")
                        .append(plainRun(r, 1 + r.nextInt(3))).append("</figcaption></figure>");
                case 0 -> sb.append("<div>").append(plainRun(r, words)).append("</div>");
                case 1 -> sb.append("<ul><li>").append(plainRun(r, words)).append("</li></ul>");
                case 2 -> sb.append("<ol><li>").append(plainRun(r, words)).append("</li></ol>");
                case 3 -> sb.append("<table><tr><td>").append(plainRun(r, words)).append("</td><td>")
                        .append(plainRun(r, 1 + r.nextInt(3))).append("</td></tr></table>");
                case 4 -> sb.append("<table><tr><th>").append(plainRun(r, words)).append("</th><th>")
                        .append(plainRun(r, 1 + r.nextInt(3))).append("</th></tr></table>");
                default -> sb.append("<blockquote>").append(plainRun(r, words)).append("</blockquote>");
            }
        }
        return sb.toString();
    }

    /** The texts of a generator whose plain text differs from the HTML's words, each with its first difference */
    private static List<String> textsThatDiffer(Random random, Function<Random, String> generator, int texts,
                                                List<String> notAGlue) {
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < texts; i++) {
            String html = generator.apply(random);
            for (boolean cleanUp : new boolean[] { true, false }) {
                List<String> expected = wordsOfTheHtml(html);
                List<String> actual = words(TextUtils.html2txt(html, cleanUp));
                String diff = firstDifference(expected, actual);
                if (diff != null) {
                    failures.add(diff + " in " + html);
                    if (!firstDifferenceIsAGlue(expected, actual)) {
                        notAGlue.add(diff + " in " + html);
                    }
                }
            }
        }
        return failures;
    }

    /** Whether the first word that differs is that word with the next one glued on */
    private static boolean firstDifferenceIsAGlue(List<String> expected, List<String> actual) {
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return i + 1 < expected.size() && actual.get(i).startsWith(expected.get(i) + expected.get(i + 1));
            }
        }
        return false;
    }

    /** The words of the HTML's text as a browser shows it: jsoup's own text of the body */
    private static List<String> wordsOfTheHtml(String html) {
        return words(Jsoup.parse(html).body().text());
    }

    private static List<String> words(String text) {
        return Arrays.stream(text.trim().split("\\s+"))
                .filter(w -> !w.isEmpty() && !w.equals("*")) // the list bullet the formatter writes
                .toList();
    }

    /** The first word that differs, with its neighbours, or null when the word lists are equal */
    private static String firstDifference(List<String> expected, List<String> actual) {
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                return "word " + i + ": expected \"" + expected.get(i) + "\" got \"" + actual.get(i) + "\"";
            }
        }
        return expected.size() == actual.size() ? null : expected.size() + " words -> " + actual.size();
    }

    /**
     * Both causes at once, and blocks inside blocks: a mix of the two generators above, each part alone or inside a
     * div or a blockquote, with or without white space between the parts.
     */
    private static String mixedBlocks(Random r) {
        StringBuilder sb = new StringBuilder();
        int parts = 1 + r.nextInt(4);
        for (int p = 0; p < parts; p++) {
            String part = r.nextBoolean() ? paragraphsWithLongInlineRuns(r) : blocksWithoutWhiteSpaceBetween(r);
            switch (r.nextInt(3)) {
                case 0 -> sb.append("<div>").append(part).append("</div>");
                case 1 -> sb.append("<blockquote>").append(part).append("</blockquote>");
                default -> sb.append(part);
            }
            if (r.nextBoolean()) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    @Test
    public void everyWordOfAParagraphWithLongInlineRuns_reachesThePlainText_inOrder() {
        // TEXT2, its first cause (PB-1). Every word a reader sees in the HTML must be a word of the plain text, in the
        // same order, none lost, none glued to its neighbour. HtmlToPlainText wraps a text node longer than what is
        // left of its 80 columns by splitting it into words; it used to drop the white space at the node's end, so
        // when the node was followed by an inline element ("... keep clear <b>Wreck</b>") the two words were glued:
        // "clearWreck" (3,772 of these 4,000 texts on 2026-11-15). The generator holds only blocks the formatter
        // breaks at, so the block-end cause (the next test) cannot occur here.
        List<String> notAGlue = new ArrayList<>();
        List<String> failures = textsThatDiffer(new Random(SEED), HtmlToTextPropertyTest::paragraphsWithLongInlineRuns,
                2000, notAGlue);
        assertTrue(failures.isEmpty(), failures.size() + " differ; the first: " + (failures.isEmpty() ? "" : failures.getFirst()));
    }

    @Test
    public void everyWordOfBlocksWithoutWhiteSpaceBetween_reachesThePlainText_inOrder() {
        // TEXT2, its second cause (S2-6). HtmlToPlainText used to break only at p, h1-h5, li, tr, br, dd and dt, so
        // the end of a div, a list, a table or a blockquote, and a table cell, followed with no white space by more
        // text glued the last word of one to the first word of the next: "<div>Wreck</div><div>buoy</div>" gave
        // "Wreckbuoy" (3,672 of these 4,000 texts on 2026-11-15). The runs are short plain words with no white space
        // at their ends, so the inline-run cause (the test above) cannot occur here.
        List<String> notAGlue = new ArrayList<>();
        List<String> failures = textsThatDiffer(new Random(SEED + 5),
                HtmlToTextPropertyTest::blocksWithoutWhiteSpaceBetween, 2000, notAGlue);
        assertTrue(failures.isEmpty(), failures.size() + " differ; the first: " + (failures.isEmpty() ? "" : failures.getFirst()));
    }

    @Test
    public void everyWordOfMixedAndNestedBlocks_reachesThePlainText_inOrder() {
        // TEXT2, both causes together, and the block breaks inside other blocks (a div holding paragraphs, a
        // blockquote holding a list): still every word, in order, none glued.
        List<String> notAGlue = new ArrayList<>();
        List<String> failures = textsThatDiffer(new Random(SEED + 6), HtmlToTextPropertyTest::mixedBlocks, 2000,
                notAGlue);
        assertTrue(failures.isEmpty(), failures.size() + " differ; the first: " + (failures.isEmpty() ? "" : failures.getFirst()));
    }

    @Test
    public void theSmallestGluedWords() {
        // TEXT2, PB-1, the smallest input: a paragraph of 81 characters before a bold word. The run's last word wraps
        // onto a new line, and the space after it must stay before the bold word.
        String longRun = "x".repeat(40) + " " + "y".repeat(39) + " ";
        String html = "<p>" + longRun + "<b>Wreck</b></p>";
        String text = TextUtils.html2txt(html, true);
        assertTrue(text.endsWith("y".repeat(39) + " Wreck"), text);
        // A run that fits the 80 columns keeps its space, as it did
        String shortHtml = "<p>" + "x".repeat(10) + " <b>Wreck</b></p>";
        assertEquals("xxxxxxxxxx Wreck", TextUtils.html2txt(shortHtml, true));
        // S2-6, the smallest input: two divs with nothing between them
        assertEquals("Wreck\nbuoy", TextUtils.html2txt("<div>Wreck</div><div>buoy</div>", true));
    }

    @Test
    public void theNavtexTextOfAPositionAfterALongRun_keepsTheSpaceBeforeThePosition() {
        // TEXT2, PB-1, where it showed: the NAVTEX text Niord generates from the details goes through html2txt first
        // (NavtexPromulgationService.html2navtex). A position in an inline element after a long sentence used to
        // give "to57-01.7N".
        String html = "<p>Mariners are advised that the light buoy marking the wreck has now been moved to "
                + "<strong>57° 01.7'N - 003° 30.6'E</strong>.</p>";
        String text = TextUtils.html2txt(html, true);
        String navtex = PositionUtils.updatePositionFormat(PositionUtils.replaceSeparator(text, " "),
                PositionAssembler.newNavtexPositionAssembler());
        assertTrue(navtex.contains("to 57-01.7N 003-30.6E."), navtex);
    }

    // ---- The NAVTEX and audio rewrite of positions ----

    private static final PositionFormatter.Format[] TEMPLATE_FORMATS = {
            PositionFormatter.LATLON_DEC_1, PositionFormatter.LATLON_DEC_2, PositionFormatter.LATLON_DEC_3 };
    private static final PositionFormatter.Format[] NAVTEX_FORMATS = {
            PositionFormatter.LATLON_NAVTEX_1, PositionFormatter.LATLON_NAVTEX_2, PositionFormatter.LATLON_NAVTEX_3 };

    @Test
    public void everyTemplatePosition_becomesTheSameNavtexPosition() {
        // A position the templates print ("57° 01.7'N - 003° 30.6'E", one to three decimals, English or Danish
        // decimal mark) must come out of the NAVTEX rewrite as the same position in the NAVTEX form
        // ("57-01.7N 003-30.6E", always a decimal point), wherever it is on the globe.
        Random random = new Random(SEED + 1);
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            double lat = (random.nextDouble() * 180 - 90);
            double lon = (random.nextDouble() * 360 - 180);
            int decimals = random.nextInt(3);
            Locale locale = random.nextBoolean() ? Locale.ENGLISH : Locale.of("da");
            String position = PositionFormatter.format(locale, TEMPLATE_FORMATS[decimals], lat, lon);
            String html = "<p>Wreck in pos. " + position + ". Keep clear.</p>";
            String text = TextUtils.html2txt(html, true);
            text = PositionUtils.replaceSeparator(text, " ");
            text = PositionUtils.updatePositionFormat(text, PositionAssembler.newNavtexPositionAssembler());
            String expected = PositionUtils.formatLat(Locale.ENGLISH, NAVTEX_FORMATS[decimals], lat) + " "
                    + PositionUtils.formatLon(Locale.ENGLISH, NAVTEX_FORMATS[decimals], lon);
            if (!text.contains(expected)) {
                failures.add(position + " -> " + text + " (expected " + expected + ")");
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures.stream().limit(5).toList());
    }

    /** The audio texts of a language, as the dictionary gives them */
    private static ResourceBundle audioTexts() {
        return new ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
                return new Object[][] {
                        { "position.deg", "degrees" }, { "position.min", "minutes" },
                        { "position.north", "north" }, { "position.south", "south" },
                        { "position.east", "east" }, { "position.west", "west" } };
            }
        };
    }

    private static final Pattern AUDIO = Pattern.compile(
            "(\\d+) degrees (\\d+[.,]?\\d*) minutes (north|south) (\\d+) degrees (\\d+[.,]?\\d*) minutes (east|west)");

    @Test
    public void everyTemplatePosition_isReadOutAsTheSamePosition() {
        // The audio text (AudioPromulgationService) reads a position out as "57 degrees 01.7 minutes north 3 ...",
        // with the decimal mark of the language. Read back, the numbers must be the position printed.
        Random random = new Random(SEED + 2);
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            double lat = (random.nextDouble() * 180 - 90);
            double lon = (random.nextDouble() * 360 - 180);
            int decimals = random.nextInt(3);
            Locale locale = random.nextBoolean() ? Locale.ENGLISH : Locale.of("da");
            String position = PositionFormatter.format(locale, TEMPLATE_FORMATS[decimals], lat, lon);
            String text = TextUtils.html2txt("<p>Wreck in pos. " + position + ".</p>", true);
            text = PositionUtils.updatePositionFormat(text,
                    PositionAssembler.newAudioPositionAssembler(locale, audioTexts()));
            Matcher m = AUDIO.matcher(text);
            String navtex = PositionUtils.formatLat(Locale.ENGLISH, NAVTEX_FORMATS[decimals], lat) + " "
                    + PositionUtils.formatLon(Locale.ENGLISH, NAVTEX_FORMATS[decimals], lon);
            if (!m.find()) {
                failures.add(position + " -> " + text);
                continue;
            }
            String readOut = m.group(1) + "-" + m.group(2).replace(',', '.') + (m.group(3).equals("north") ? "N" : "S")
                    + " " + m.group(4) + "-" + m.group(5).replace(',', '.') + (m.group(6).equals("east") ? "E" : "W");
            char mark = locale.getLanguage().equals("da") ? ',' : '.';
            if (!readOut.equals(navtex) || m.group(2).indexOf(mark) < 0) {
                failures.add(position + " -> " + text + " (expected " + navtex + ")");
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures.stream().limit(5).toList());
    }

    @Test
    public void removingTheSuperfluousWords_keepsEveryOtherWordInOrder_andRemovesEveryOneBetweenKeptWords() {
        // NAVTEX drops "the", "is" and "in pos." (NavtexPromulgationService.SUPERFLUOUS_WORDS). Every other word must
        // stay, in order, unchanged; and a superfluous word must go when it stands between two kept words on its
        // line, or at a line's start or end beside a kept word. By design (TextUtils.removeWords) two superfluous
        // words next to each other may both survive or one of them, and a line that is one superfluous word alone
        // keeps it: those are not checked.
        String superfluous = "the|in pos\\.|is";
        String[] words = { "the", "The", "is", "IS", "in", "pos.", "this", "island", "theme", "wreck", "buoy",
                "57-01.7N", "003-30.6E", "lit.", "is.", "the,", "(the" };
        Random random = new Random(SEED + 3);
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            StringBuilder sb = new StringBuilder();
            int n = 1 + random.nextInt(12);
            for (int j = 0; j < n; j++) {
                if (j > 0) {
                    sb.append(random.nextInt(8) == 0 ? "\n" : " ");
                }
                sb.append(words[random.nextInt(words.length)]);
            }
            String text = sb.toString();
            String result = TextUtils.removeWords(text, superfluous);
            // Superfluous words next to each other may survive (the expression consumes the space between them),
            // which loses nothing: compare the words that are not superfluous only
            List<String> keptNotSuperfluous = notSuperfluous(words(result));
            List<String> expectedNotSuperfluous = notSuperfluous(words(text));
            String notRemoved = keptNotSuperfluous.equals(expectedNotSuperfluous) ? superfluousNotRemoved(text, result)
                    : "a kept word lost or changed";
            if (notRemoved != null) {
                failures.add("\"" + text.replace("\n", "\\n") + "\" -> \"" + result.replaceAll("\\R", "\\\\n")
                        + "\": " + notRemoved);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures.stream().limit(5).toList());
    }

    @Test
    public void removingTheSuperfluousWords_theSmallestInputs() {
        String superfluous = "the|in pos\\.|is";
        assertEquals("wreck buoy", TextUtils.removeWords("wreck the buoy", superfluous));
        assertEquals("wreck 57-01.7N", TextUtils.removeWords("wreck in pos. 57-01.7N", superfluous));
        assertEquals("wreck", TextUtils.removeWords("The wreck", superfluous));
        assertEquals("wreck", TextUtils.removeWords("wreck IS", superfluous));
        // By design: a line that is one superfluous word alone keeps it, and of two next to each other one survives
        assertEquals("the", TextUtils.removeWords("the", superfluous));
        assertEquals("wreck is buoy", TextUtils.removeWords("wreck the is buoy", superfluous));
        // And a removed word that makes its neighbours "in pos." takes them too (isLoose below)
        assertEquals("wreck", TextUtils.removeWords("in the pos. wreck", superfluous));
    }

    private static boolean isSuperfluous(String w) {
        return w.equalsIgnoreCase("the") || w.equalsIgnoreCase("is");
    }

    private static List<String> notSuperfluous(List<String> words) {
        return words.stream()
                .filter(w -> !isSuperfluous(w))
                .filter(w -> !w.equals("in") && !w.equals("pos."))
                .toList();
    }

    /**
     * The units of a line: each word alone, except "in" followed by "pos.", which is one unit; each unit marked
     * superfluous or kept.
     */
    private static List<String[]> units(String line) {
        String[] tokens = line.trim().isEmpty() ? new String[0] : line.trim().split(" +");
        List<String[]> units = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equalsIgnoreCase("in") && i + 1 < tokens.length && tokens[i + 1].equalsIgnoreCase("pos.")) {
                units.add(new String[] { tokens[i], tokens[i + 1] });
                i++;
            } else {
                units.add(new String[] { tokens[i] });
            }
        }
        return units;
    }

    private static boolean isSuperfluous(String[] unit) {
        return unit.length == 2 || isSuperfluous(unit[0]);
    }

    /**
     * A lone "in" or "pos.": neither superfluous nor kept. Removing a word between them makes them "in pos.", which
     * the end patterns then remove ("in the pos. x" gives "x"); that is the phrase the rule means, so it is allowed.
     */
    private static boolean isLoose(String[] unit) {
        return unit.length == 1 && (unit[0].equalsIgnoreCase("in") || unit[0].equalsIgnoreCase("pos."));
    }

    private static boolean isKept(String[] unit) {
        return !isSuperfluous(unit) && !isLoose(unit);
    }

    /**
     * Null when every superfluous unit that stands between two kept units, or at a line's start or end beside a
     * kept unit, is gone from its line of the result, and the result's line holds the kept units in order with
     * nothing else than superfluous and loose units of the input line; else what is wrong. The result has the
     * input's lines (no line becomes empty: a line keeps a kept word or its one superfluous word).
     */
    private static String superfluousNotRemoved(String text, String result) {
        String[] inLines = text.split("\n");
        String[] outLines = result.split("\\R");
        if (inLines.length != outLines.length) {
            return inLines.length + " lines -> " + outLines.length;
        }
        for (int l = 0; l < inLines.length; l++) {
            List<String[]> units = units(inLines[l]);
            String[] out = outLines[l].trim().isEmpty() ? new String[0] : outLines[l].trim().split(" +");
            int o = 0;
            for (int u = 0; u < units.size(); u++) {
                String[] unit = units.get(u);
                boolean matches = o + unit.length <= out.length
                        && Arrays.equals(unit, Arrays.copyOfRange(out, o, o + unit.length));
                if (isKept(unit)) {
                    if (!matches) {
                        return "line " + l + ": the kept \"" + unit[0] + "\" is not where it should be";
                    }
                    o += unit.length;
                    continue;
                }
                boolean keptBefore = u == 0 || isKept(units.get(u - 1));
                boolean keptAfter = u == units.size() - 1 || isKept(units.get(u + 1));
                boolean alone = units.size() == 1;
                if (isSuperfluous(unit) && keptBefore && keptAfter && !alone) {
                    if (matches) {
                        return "line " + l + ": \"" + String.join(" ", unit) + "\" between kept words is not removed";
                    }
                } else if (matches) {
                    o += unit.length; // may stay
                }
            }
            if (o != out.length) {
                return "line " + l + ": \"" + String.join(" ", Arrays.copyOfRange(out, o, out.length))
                        + "\" is not of the input";
            }
        }
        return null;
    }
}
