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
package org.niord.core.promulgation;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Holds a text to the NAVTEX alphabet.
 * <p>
 * NAVTEX is sent in the ITA2 alphabet: the letters A-Z, the figures 0-9, space, line break and the signs
 * {@code ? : ( ) . , ' = / + -}. A NAVTEX text leaves Niord as a mail to the station, so a character outside that
 * alphabet would reach the transmitter. {@link #normalise(String)} maps a text into the alphabet, never refusing it:
 * the Danish and German letters are transliterated (Æ to AE, Ø to OE, Å to AA, Ä to AE, Ö to OE, Ü to UE), other
 * accented Latin letters lose their accent, every kind of quote becomes {@code '}, no-break and other spaces become
 * a space, dashes become {@code -}, and a few signs become their nearest form ({@code ;} to {@code ,}, {@code !} to
 * {@code .}, brackets to parentheses, {@code &} to AND, {@code %} to PERCENT, a degree sign to a space). The text is
 * then upper-cased, and a character still outside the alphabet becomes a space; {@link #outsideAlphabet(String)}
 * names those characters, so a caller can log them.
 * <p>
 * The normaliser does not split lines: a mapped letter may lengthen a line, so a caller normalises before it splits
 * the text into lines of 40 characters.
 */
public final class NavtexText {

    /** The signs of the NAVTEX alphabet besides letters, figures, space and line break */
    private static final String SIGNS = "?:().,'=/+-";

    /** Explicit mappings, applied before the accents are stripped */
    private static final Map<Integer, String> MAP = new HashMap<>();

    /** The combining marks NFD splits off an accented letter */
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private static final Pattern SPACE_RUNS = Pattern.compile(" {2,}");

    private static final Pattern SPACES_AROUND_LINE_BREAKS = Pattern.compile(" ?\n ?");

    static {
        // Nordic and German letters, and the kra
        map("AE", 'Æ', 'æ', 'Ä', 'ä');
        map("OE", 'Ø', 'ø', 'Ö', 'ö', 'Œ', 'œ');
        map("AA", 'Å', 'å');
        map("UE", 'Ü', 'ü');
        map("SS", 'ß');
        map("Q", 'ĸ');
        map("D", 'Ð', 'ð', 'Đ', 'đ');
        map("TH", 'Þ', 'þ');
        map("L", 'Ł', 'ł');

        // Quotes, guillemets, accents used as apostrophes, primes
        map("'", '"', '“', '”', '„', '‟', '«', '»', '‹', '›',
                '‘', '’', '‚', '‛', '´', '`', '′', '″');

        // Spaces: tab, no-break space, figure space, narrow no-break space and the other typographic spaces
        map(" ", '\t', ' ', ' ', '　');
        for (char c = ' '; c <= ' '; c++) {
            map(" ", c);
        }

        // Invisible characters: soft hyphen, zero-width spaces and joiners, word joiner, byte order mark
        map("", '­', '​', '‌', '‍', '⁠', '﻿');

        // Hyphens, dashes and the minus sign
        map("-", '‐', '‑', '‒', '–', '—', '―', '−');

        // Signs outside the alphabet that have a near form inside it
        map(",", ';');
        map(".", '!');
        map("(", '[', '{');
        map(")", ']', '}');
        map(" AND ", '&');
        map(" PERCENT", '%');
        map(" ", '°', 'º');
        map("...", '…');
    }

    private NavtexText() {
    }

    private static void map(String to, char... from) {
        for (char c : from) {
            MAP.put((int) c, to);
        }
    }

    /**
     * Maps the text into the NAVTEX alphabet: the table above, then the accents stripped, then upper case; a character
     * still outside the alphabet becomes a space, runs of spaces become one space and a space next to a line break
     * goes. A carriage return becomes a line break. A text already in the alphabet comes back unchanged, unless it
     * holds runs of spaces or spaces next to a line break.
     *
     * @param text the text, may be null
     * @return the text in the NAVTEX alphabet, or null for null
     */
    public static String normalise(String text) {
        if (text == null) {
            return null;
        }
        String mapped = mapIntoAlphabet(text);
        StringBuilder sb = new StringBuilder(mapped.length());
        mapped.codePoints().forEach(cp -> {
            if (inAlphabet(cp)) {
                sb.appendCodePoint(cp);
            } else {
                sb.append(' ');
            }
        });
        String result = SPACE_RUNS.matcher(sb).replaceAll(" ");
        return SPACES_AROUND_LINE_BREAKS.matcher(result).replaceAll("\n");
    }

    /**
     * The characters of the text that {@link #normalise(String)} cannot map and replaces with a space, each once, in
     * the order they first occur (upper-cased, as the normaliser sees them).
     *
     * @param text the text, may be null
     * @return the characters outside the NAVTEX alphabet that have no mapping; empty if none
     */
    public static List<String> outsideAlphabet(String text) {
        if (text == null) {
            return List.of();
        }
        return mapIntoAlphabet(text).codePoints()
                .filter(cp -> !inAlphabet(cp))
                .distinct()
                .mapToObj(Character::toString)
                .toList();
    }

    /** Whether the character is in the NAVTEX alphabet */
    public static boolean inAlphabet(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9') || cp == ' ' || cp == '\n'
                || SIGNS.indexOf(cp) >= 0;
    }

    /** The table, the accents stripped and upper case; what is left outside the alphabet is not replaced yet */
    private static String mapIntoAlphabet(String text) {
        // Composed first, so a letter typed as a base letter and a combining ring (A and U+030A) is found as Å
        String lines = Normalizer.normalize(text, Normalizer.Form.NFC).replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(lines.length());
        lines.codePoints().forEach(cp -> {
            String to = MAP.get(cp);
            if (to != null) {
                sb.append(to);
            } else {
                sb.appendCodePoint(cp);
            }
        });
        String withoutAccents = COMBINING_MARKS.matcher(Normalizer.normalize(sb, Normalizer.Form.NFD)).replaceAll("");
        return withoutAccents.toUpperCase(Locale.ROOT);
    }
}
