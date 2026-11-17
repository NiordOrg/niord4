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
package org.niord.core.util;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.w3c.tidy.Tidy;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Text utility methods
 */
@SuppressWarnings("unused")
public class TextUtils {

    /**
     * Converts the text from html to plain text
     * @param html the html
     * @param cleanUpWhiteSpace if set, clean up newlines and white-space
     * @return the plain text version
     */
    public static String html2txt(String html, boolean cleanUpWhiteSpace) {
        if (StringUtils.isNotBlank(html)) {

            if (cleanUpWhiteSpace) {
                html = trimHtmlWhitespace(html);
            }

            try {
                Document doc = Jsoup.parse(html);

                // Do not use the following as it paginates each line to 80 characters
                return new HtmlToPlainText().getPlainText(doc.body()).trim();
            } catch (Exception ignored) {
            }
        }
        // If blank, or if any error occurs, return the original html
        return html;
    }

    /**
     * Converts the text from html to plain text
     * @param html the html
     * @return the plain text version
     */
    public static String html2txt(String html) {
        return html2txt(html, false);
    }

    /** Trims whitespace from the HTML **/
    public static String trimHtmlWhitespace(String html) {
        if (StringUtils.isNotBlank(html)) {
            html = html.replace("\n", " ")
                    .replace("\r", " ")
                    .replaceAll("\\s+", " ").trim();

        }
        return html;
    }

    /**
     * Converts the text from plain text to html
     * @param text the text
     * @return the html version
     */
    public static String txt2html(String text) {
        text = StringUtils.replaceEach(text,
                new String[]{"&", "\"", "<", ">", "\n", "\t"},
                new String[]{"&amp;", "&quot;", "&lt;", "&gt;", "<br>", "&nbsp;&nbsp;&nbsp;"});
        return text;
    }

    /**
     * Used for e.g. NAVTEX. Split the text into lines of the max length.
     * <p>
     * Each line is broken at the last space within the first {@code maxLength} characters of what is
     * left of it, or cut at {@code maxLength} when there is no space there. Every emitted part is
     * trimmed, and so is what is left after it. A line that needs no break is emitted as it is.
     * <p>
     * What is left of a line is tracked as a window into it rather than cut out as a new string after
     * every break, and the search for the space looks at the current {@code maxLength} characters only,
     * so the work grows linearly with the length of the text.
     *
     * @param text the text
     * @param maxLength the max length of the individual lines, at least 1
     * @return the result
     * @throws IllegalArgumentException if {@code maxLength} is below 1: a window of no characters
     *         consumes nothing, so the text would never end
     */
    public static String maxLineLength(String text, int maxLength) {
        if (maxLength < 1) {
            throw new IllegalArgumentException("The max line length must be at least 1, was " + maxLength);
        }
        if (StringUtils.isBlank(text)) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            int start = 0;
            int end = line.length();
            while (end - start > maxLength) {
                int windowEnd = start + maxLength;
                int idx = -1;
                for (int i = windowEnd - 1; i >= start; i--) {
                    if (line.charAt(i) == ' ') {
                        idx = i;
                        break;
                    }
                }
                int cut = idx != -1 ? idx + 1 : windowEnd;
                appendTrimmed(sb, line, start, cut).append("\n");

                // What is left of the line, trimmed as String.trim() would
                start = cut;
                while (start < end && line.charAt(start) <= ' ') {
                    start++;
                }
                while (end > start && line.charAt(end - 1) <= ' ') {
                    end--;
                }
            }
            sb.append(line, start, end).append("\n");
        }

        return sb.toString();
    }

    /** Appends the part [from, to) of the string, trimmed as String.trim() would trim it */
    private static StringBuilder appendTrimmed(StringBuilder sb, String s, int from, int to) {
        while (from < to && s.charAt(from) <= ' ') {
            from++;
        }
        while (to > from && s.charAt(to - 1) <= ' ') {
            to--;
        }
        return sb.append(s, from, to);
    }

    /**
     * Removes the list of pipe-separated words from the text
     * @param text the text to remove the words from
     * @param words the words to remove
     * @return the result
     */
    public static String removeWords(String text, String words) {
        try {
            StringBuilder sb = new StringBuilder();
            Arrays.stream(text.split("\n"))
                .forEach(s -> {
                    String line = s.replaceAll("(?i)\\s+(" + words + ")\\s+", " ")
                            .replaceAll("(?i)^(" + words + ")\\s+", "")
                            .replaceAll("(?i)\\s+(" + words + ")$", "");
                    sb.append(line).append(System.lineSeparator());
                });
            return sb.toString().trim();

        } catch (Exception ignored) {
            return text;
        }
    }

    /**
     * Use JTidy to clean up the HTML
     * @param html the HTML to clean up
     * @return the resulting XHTML
     */
    public static org.w3c.dom.Document cleanHtml(String html) {
        Tidy tidy = new Tidy();

        tidy.setShowWarnings(false); //to hide errors
        tidy.setQuiet(true); //to hide warning

        tidy.setXHTML(true);
        return tidy.parseDOM(new StringReader(html), new StringWriter());
    }

    /**
     * Ensures that the string ends with a trailing dot character
     * @param text the text to add a trailing dot to
     * @return the updated text
     **/
    public static String trailingDot(String text) {
        if (StringUtils.isNotBlank(text)) {
            text = text.trim();
            if (!text.endsWith(".")) {
                text = text + ".";
            }
        }
        return text;
    }

    /**
     * Removes any trailing dot character
     * @param text the text to remove a trailing dot from
     * @return the updated text
     **/
    public static String removeTrailingDot(String text) {
        if (StringUtils.isNotBlank(text)) {
            text = text.trim();
            while (text.endsWith(".")) {
                text = text.substring(0, text.length() - 1).trim();
            }
        }
        return text;
    }

    /**
     * Neutralises the LIKE wildcards in a value that is data rather than a pattern: {@code %} and {@code _}
     * are matched as themselves, with {@code '!'} as the escape character (itself doubled). Pair the result
     * with {@code ESCAPE '!'} in the query, or the escapes are read as text.
     *
     * @param value the value to match literally
     * @return the value with its LIKE wildcards and escape characters escaped
     */
    public static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** Simple case-insensitive comparison between two strings **/
    public static int compareIgnoreCase(String s1, String s2) {
        if (s1 == null && s2 == null) {
            return 0;
        } else if (s1 == null) {
            return 1;
        } else if (s2 == null) {
            return -1;
        }
        return s1.toLowerCase().compareTo(s2.toLowerCase());
    }

    /** Encode the field of a CSV row according to https://tools.ietf.org/html/rfc4180 **/
    public static String encodeCVSField(Object value) {
        String result = value == null ? "" : value.toString();
        if (result.contains("\"")) {
            result = result.replace("\"", "\"\"");
        }
        return result;
    }

    /** Simple CSV generator **/
    public static String encodeCVSLine(String separator, Object... fieldValues) {
        StringBuilder result = new StringBuilder();
        separator = StringUtils.defaultIfBlank(separator, ",");
        String line = Arrays.stream(fieldValues)
                .map(f -> "\"" + encodeCVSField(f) + "\"")
                .collect(Collectors.joining(separator));
        result.append(line);
        return result.toString();
    }

    /**
     * Used for joining strings with a different last delimiter.
     * Credits: http://stackoverflow.com/questions/34936771/join-strings-with-different-last-delimiter
     * <p>
     * Usage:
     * <pre>
     *  list.stream()
     *    .collect(
     *      Collectors.collectingAndThen(Collectors.toList(), joiningLastDelimiter(", ", " and "))
     *    );
     * </pre>
     *
     * @param delimiter the delimiter
     * @param lastDelimiter the last delimiter
     * @return the joining function
     */
    public static Function<List<String>, String> joiningLastDelimiter(
            String delimiter, String lastDelimiter) {
        return list -> {
            int last = list.size() - 1;
            if (last < 1) return String.join(delimiter, list);
            return String.join(lastDelimiter,
                    String.join(delimiter, list.subList(0, last)),
                    list.get(last));
        };
    }

    /**
     * Prints an XML document to the output stream
     * @param doc the document to print
     * @param out the output stream
     */
    public static void printDocument(org.w3c.dom.Document doc, OutputStream out) throws IOException, TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        transformer.transform(new DOMSource(doc),
                new StreamResult(new OutputStreamWriter(out, "UTF-8")));
    }

}
