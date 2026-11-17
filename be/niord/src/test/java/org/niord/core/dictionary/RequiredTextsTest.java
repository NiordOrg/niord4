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
package org.niord.core.dictionary;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequiredTexts} lists every dictionary key Java reads, and nothing else (docs/code-data-config-plan.md §4.6).
 *
 * <p>Scans the production sources ({@code src/main/java}, comments removed; {@code RequiredTexts.java} itself is not
 * read) for string literals: a literal equal to a key of the base product's English texts, and a dotted literal
 * passed to a dictionary read ({@code .value(…, "…")}, {@code .getEntries().get("…")}, {@code .getString("…")}),
 * must be listed. Every listed key must be read by literal somewhere and, unless a country supplies it, exist in the
 * base product's English texts. The runtime prefixes are held to the {@code text("…" + …)} of the base product's
 * templates, read by path in {@code bootstrap/templates/} (bootstrap data since C4.1).
 */
class RequiredTextsTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /** The base product's templates, bootstrap data since C4.1 */
    private static final Path TEMPLATES = Path.of("bootstrap/templates");

    /** Dotted literals passed to a dictionary-read call shape that are not dictionary keys; empty today */
    private static final Set<String> NOT_DICTIONARY_KEYS = Set.of();

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    private static final List<Pattern> READ_CALLS = List.of(
            Pattern.compile("\\.value\\([^;]*?,\\s*\"([^\"]+)\"\\s*\\)"),
            Pattern.compile("\\.getEntries\\(\\)\\s*\\.get\\(\\s*\"([^\"]+)\"\\s*\\)"),
            Pattern.compile("\\.getString\\(\\s*\"([^\"]+)\"\\s*\\)"));

    /** A runtime prefix, in the call form {@code text('msg.status.' + ...)} or the bracket form {@code text['…' + …]} */
    private static final Pattern RUNTIME_PREFIX = Pattern.compile("text\\s*[(\\[]\\s*[\"']([\\w.]+\\.)[\"']\\s*\\+");

    /** A string literal and the line it is on */
    record Literal(int line, String value) {
    }

    @Test
    void everyKeyJavaReadsIsListed() throws IOException {
        Set<String> listed = listedKeys();
        Set<String> baseKeys = baseKeys().values().stream().flatMap(Set::stream).collect(Collectors.toSet());

        List<String> unlisted = new ArrayList<>();
        sources().forEach((file, source) -> unlisted.addAll(unlisted(file, source, baseKeys, listed)));

        assertEquals(List.of(), unlisted, "dictionary keys Java reads that RequiredTexts.JAVA_TEXTS does not list");
    }

    @Test
    void everyListedKeyIsReadByJava() throws IOException {
        Set<String> literals = new TreeSet<>();
        sources().values().forEach(source -> literals(stripComments(source)).forEach(l -> literals.add(l.value())));

        List<String> unread = listedKeys().stream().filter(key -> !literals.contains(key)).toList();

        assertEquals(List.of(), unread, "keys RequiredTexts.JAVA_TEXTS lists that no Java code names");
    }

    @Test
    void everyListedKeyTheBaseProductSuppliesExistsInItsEnglishTexts() {
        Map<String, Set<String>> baseKeys = baseKeys();

        List<String> missing = RequiredTexts.JAVA_TEXTS.stream()
                .filter(t -> !t.countrySupplied())
                .filter(t -> !baseKeys.getOrDefault(t.dictionary(), Set.of()).contains(t.key()))
                .map(t -> t.dictionary() + "/" + t.key())
                .toList();

        assertEquals(List.of(), missing, "keys Java reads that defaults/base/texts/en/ lacks");
    }

    @Test
    void theRuntimePrefixesAreTheOnesTheTemplatesBuild() throws IOException {
        Path templates = TEMPLATES;
        assertTrue(Files.isRegularFile(templates.resolve("messages/message-support.ftl")),
                templates.toAbsolutePath() + " holds the base product's templates");

        Set<String> prefixes = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(templates)) {
            for (Path template : paths.filter(Files::isRegularFile).toList()) {
                prefixes.addAll(runtimePrefixes(Files.readString(template, StandardCharsets.UTF_8)));
            }
        }

        assertEquals(new TreeSet<>(RequiredTexts.RUNTIME_PREFIXES), prefixes);
    }

    @Test
    void readersAreMatchedByDictionaryAndKey() {
        assertEquals(Set.of(RequiredTexts.Reader.AUDIO_TEXT, RequiredTexts.Reader.LAT_LON_DIRECTIVE),
                RequiredTexts.readers("template", "position.deg"));
        assertEquals(Set.of(), RequiredTexts.readers("web", "position.deg"));
        assertEquals("msg.status.", RequiredTexts.runtimePrefix("msg.status.DRAFT"));
        assertNull(RequiredTexts.runtimePrefix("msg.time.date_time_format"));
    }

    // ------------------------------------------------------------------------------------------- //
    // The scanners, on sources in memory
    // ------------------------------------------------------------------------------------------- //

    @Test
    void commentsAreNotRead() {
        String source = "// \"msg.a\"\n/* \"msg.b\" */ String s = \"msg.c\"; /** \"msg.d\" */\nchar q = '\"';"
                + " String t = \"x // \\\"y\\\" /* z */\";";

        String stripped = stripComments(source);

        assertEquals(List.of(new Literal(2, "msg.c"), new Literal(3, "x // \\\"y\\\" /* z */")), literals(stripped));
    }

    @Test
    void anUnlistedReadIsReportedWithItsFileAndLine() {
        String source = "class A {\n  void a() {\n    dictionaryService.value(\"mail\", lang, \"mail.unlisted\");\n"
                + "    mailDict.value(language, \"mail.date_format.short\");\n"
                + "    bundle.getString(\"position.unlisted\"); msg.getString(\"STATUS\");\n"
                + "    dict.getEntries().get(\"msg.unlisted\");\n"
                + "    String plain = \"msg.base.key\";\n  }\n}\n";

        List<String> unlisted = unlisted("org/niord/A.java", source, Set.of("msg.base.key"),
                Set.of("mail.date_format.short"));

        assertEquals(List.of("org/niord/A.java:3 mail.unlisted", "org/niord/A.java:5 position.unlisted",
                "org/niord/A.java:6 msg.unlisted", "org/niord/A.java:7 msg.base.key"), unlisted);
    }

    @Test
    void runtimePrefixesAreFoundInBothQuoteForms() {
        assertEquals(Set.of("msg.status.", "lang."), runtimePrefixes(
                "${text(\"msg.status.\" + msg.status)} ${text('lang.' + lang)} ${text('pdf.translation')}"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /**
     * Returns "file:line key" for each key the source reads that is not listed: a literal equal to a base key, and a
     * dotted literal passed to a dictionary read
     */
    static List<String> unlisted(String file, String source, Set<String> baseKeys, Set<String> listed) {
        String stripped = stripComments(source);
        Set<Literal> found = new TreeSet<>(Comparator.comparingInt(Literal::line).thenComparing(Literal::value));
        for (Literal literal : literals(stripped)) {
            if (baseKeys.contains(literal.value())) {
                found.add(literal);
            }
        }
        for (Pattern call : READ_CALLS) {
            Matcher m = call.matcher(stripped);
            while (m.find()) {
                if (m.group(1).contains(".") && !NOT_DICTIONARY_KEYS.contains(m.group(1))) {
                    found.add(new Literal(line(stripped, m.start(1)), m.group(1)));
                }
            }
        }
        return found.stream()
                .filter(l -> !listed.contains(l.value()))
                .map(l -> file + ":" + l.line() + " " + l.value())
                .toList();
    }

    /** Returns the string literals of the source, text blocks and character literals excluded */
    static List<Literal> literals(String stripped) {
        List<Literal> literals = new ArrayList<>();
        Matcher m = STRING_LITERAL.matcher(stripped);
        while (m.find()) {
            literals.add(new Literal(line(stripped, m.start()), m.group(1)));
        }
        return literals;
    }

    /**
     * Blanks the comments, character literals and text blocks of a Java source, keeping its newlines, so line
     * numbers stay right and only string literals are left in quotes
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : end + 2;
                blank(source, i, end, out);
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? source.length() : end + 3;
                blank(source, i, end, out);
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < source.length() && source.charAt(end) != c && source.charAt(end) != '\n') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, source.length());
                if (c == '"') {
                    out.append(source, i, end);
                } else {
                    blank(source, i, end, out);
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static void blank(String source, int from, int to, StringBuilder out) {
        for (int j = from; j < to; j++) {
            out.append(source.charAt(j) == '\n' ? '\n' : ' ');
        }
    }

    /** Returns the prefixes the template completes at runtime in {@code text("prefix." + …)} */
    static Set<String> runtimePrefixes(String template) {
        Set<String> prefixes = new TreeSet<>();
        RUNTIME_PREFIX.matcher(template).results().forEach(r -> prefixes.add(r.group(1)));
        return prefixes;
    }

    private static int line(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            line += text.charAt(i) == '\n' ? 1 : 0;
        }
        return line;
    }

    private static Set<String> listedKeys() {
        return RequiredTexts.JAVA_TEXTS.stream().map(RequiredTexts.JavaText::key).collect(Collectors.toSet());
    }

    /** The production sources by path relative to the source root, with '/', RequiredTexts.java left out */
    private static Map<String, String> sources() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String file = SOURCE_ROOT.relativize(path).toString().replace('\\', '/');
                if (!file.equals("org/niord/core/dictionary/RequiredTexts.java")) {
                    sources.put(file, Files.readString(path, StandardCharsets.UTF_8));
                }
            }
        }
        return sources;
    }

    /** The keys of the base product's English texts, by dictionary */
    private static Map<String, Set<String>> baseKeys() {
        Map<String, Set<String>> keys = new LinkedHashMap<>();
        for (String bundle : DictionaryService.DEFAULT_BUNDLES) {
            String path = "defaults/base/texts/en/" + bundle + ".properties";
            try (InputStream in = RequiredTextsTest.class.getClassLoader().getResourceAsStream(path)) {
                assertNotNull(in, path + " is on the class path");
                Properties props = new Properties();
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                keys.put(bundle, props.stringPropertyNames());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertTrue(keys.get("web").contains("lang.en"), "the base product's English texts were read");
        return keys;
    }
}
