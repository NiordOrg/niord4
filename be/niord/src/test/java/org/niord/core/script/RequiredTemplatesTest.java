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
package org.niord.core.script;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequiredTemplates} lists every template the code names, and the base product's bootstrap holds each
 * (docs/code-data-config-plan.md §6.2).
 *
 * <p>The literal test scans the production sources ({@code src/main/java}, comments removed;
 * {@code RequiredTemplates.java} itself is not read) for string literals that are template paths,
 * {@code /?templates/….ftl} or {@code ….js}, and for such paths inside text blocks: each must be listed, after {@link RequiredTemplates#normalize}. A literal
 * with {@code %s} is a pattern ({@code GeometryFormatService}'s {@code /templates/geometry/%s.ftl}): at least one
 * listed path, and every file of {@code bootstrap/templates/} it matches, must be listed. The completeness test
 * asserts that {@code bootstrap/} holds every listed path, so a fresh deployment gets it from the bootstrap. The
 * comments are removed as {@code RequiredTextsTest} removes them.
 */
class RequiredTemplatesTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /** The base product's bootstrap folder, which holds templates/ */
    private static final Path BOOTSTRAP = Path.of("bootstrap");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    private static final Pattern TEMPLATE_PATH = Pattern.compile("/?templates/.+\\.(ftl|js)");

    /** A template path inside a text block, where it is one word of a longer text */
    private static final Pattern TEMPLATE_PATH_IN_TEXT = Pattern.compile("/?\\btemplates/[\\w./%-]+\\.(ftl|js)\\b");

    /** A template-path literal and where it is */
    record Literal(String file, int line, String value) {
    }

    @Test
    void everyTemplatePathTheCodeNamesIsListed() throws IOException {
        List<String> unlisted = new ArrayList<>();
        Set<String> bootstrapPaths = BootstrapTemplates.read(BOOTSTRAP.resolve("templates")).keySet();
        List<Literal> literals = templatePathLiterals(sources());
        assertFalse(literals.isEmpty(), "the code names template paths");

        for (Literal literal : literals) {
            String path = RequiredTemplates.normalize(literal.value());
            if (path.contains("%s")) {
                Pattern pattern = Pattern.compile(Pattern.quote(path).replace("%s", "\\E[^/]+\\Q"));
                boolean anyListed = RequiredTemplates.PATHS.stream().anyMatch(p -> pattern.matcher(p).matches());
                if (!anyListed) {
                    unlisted.add(literal.file() + ":" + literal.line() + " " + literal.value() + " (no listed path)");
                }
                bootstrapPaths.stream()
                        .filter(p -> pattern.matcher(p).matches())
                        .filter(p -> !RequiredTemplates.PATHS.contains(p))
                        .forEach(p -> unlisted.add(literal.file() + ":" + literal.line() + " " + literal.value()
                                + " (matches " + p + ")"));
            } else if (!RequiredTemplates.PATHS.contains(path)) {
                unlisted.add(literal.file() + ":" + literal.line() + " " + literal.value());
            }
        }

        assertEquals(List.of(), unlisted, "template paths the code names that RequiredTemplates.PATHS does not list");
    }

    @Test
    void theBaseProductsBootstrapHoldsEveryListedTemplate() {
        List<String> absent = RequiredTemplates.PATHS.stream()
                .filter(path -> !Files.isRegularFile(BOOTSTRAP.resolve(path)))
                .toList();

        assertEquals(List.of(), absent, "required templates the base product's bootstrap/ lacks");
    }

    @Test
    void theListIsSortedAndInScriptResourceForm() {
        assertEquals(List.copyOf(new TreeSet<>(RequiredTemplates.PATHS)), RequiredTemplates.PATHS);
        RequiredTemplates.PATHS.forEach(path -> assertTrue(TEMPLATE_PATH.matcher(path).matches()
                && !path.startsWith("/"), path));
        assertEquals("templates/a.ftl", RequiredTemplates.normalize("/templates/a.ftl"));
        assertEquals("templates/a.ftl", RequiredTemplates.normalize("templates/a.ftl"));
    }

    // ------------------------------------------------------------------------------------------- //
    // The scanner, on sources in memory
    // ------------------------------------------------------------------------------------------- //

    @Test
    void commentsAreNotReadAndPatternsAreKept() {
        Map<String, String> sources = Map.of("A.java", """
                // "/templates/a.ftl"
                /** "templates/b.ftl" */ String c = "/templates/c.ftl";
                String d = String.format("/templates/geometry/%s.ftl", x); String e = "templates";
                String f = \"""
                    uses templates/tmpl/f.ftl and "/templates/g.js", not Requiredtemplates/h.ftl
                    \""";
                """);

        assertEquals(List.of(new Literal("A.java", 2, "/templates/c.ftl"),
                        new Literal("A.java", 3, "/templates/geometry/%s.ftl"),
                        new Literal("A.java", 5, "templates/tmpl/f.ftl"),
                        new Literal("A.java", 5, "/templates/g.js")),
                templatePathLiterals(sources));
    }

    /**
     * Returns the template-path literals of the sources, comments removed: each string literal that is a template
     * path, and each template path inside a text block, in source order
     */
    static List<Literal> templatePathLiterals(Map<String, String> sources) {
        List<Literal> literals = new ArrayList<>();
        sources.forEach((file, source) -> {
            List<int[]> textBlocks = new ArrayList<>();
            String stripped = stripComments(source, textBlocks);
            List<Map.Entry<Integer, String>> found = new ArrayList<>();
            Matcher m = STRING_LITERAL.matcher(stripped);
            while (m.find()) {
                if (TEMPLATE_PATH.matcher(m.group(1)).matches()) {
                    found.add(Map.entry(m.start(), m.group(1)));
                }
            }
            for (int[] block : textBlocks) {
                Matcher inBlock = TEMPLATE_PATH_IN_TEXT.matcher(source).region(block[0], block[1]);
                while (inBlock.find()) {
                    found.add(Map.entry(inBlock.start(), inBlock.group()));
                }
            }
            found.sort(Map.Entry.comparingByKey());
            found.forEach(f -> literals.add(new Literal(file, line(stripped, f.getKey()), f.getValue())));
        });
        return literals;
    }

    /** The production sources by path relative to the source root, with '/', RequiredTemplates.java left out */
    private static Map<String, String> sources() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String file = SOURCE_ROOT.relativize(path).toString().replace('\\', '/');
                if (!file.equals("org/niord/core/script/RequiredTemplates.java")) {
                    sources.put(file, Files.readString(path, StandardCharsets.UTF_8));
                }
            }
        }
        return sources;
    }

    /**
     * Blanks the comments, character literals and text blocks of a Java source, keeping its newlines, so line
     * numbers stay right and only string literals are left in quotes (as {@code RequiredTextsTest} does). The
     * content of each text block, between its delimiters, is added to {@code textBlocks} as a {@code [from, to)}
     * range of the source, to be searched on its own: a quote inside it is no string delimiter.
     */
    static String stripComments(String source, List<int[]> textBlocks) {
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
                int close = source.indexOf("\"\"\"", i + 3);
                int end = close < 0 ? source.length() : close + 3;
                textBlocks.add(new int[]{i + 3, close < 0 ? source.length() : close});
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

    private static int line(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            line += text.charAt(i) == '\n' ? 1 : 0;
        }
        return line;
    }
}
