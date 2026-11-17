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
package org.niord.build;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.niord.core.util.JsonUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base product guard's backend half (docs/fe-country-split-plan.md §4.8, docs/code-data-config-plan.md §2, S3):
 * no file of niord-base's {@code bootstrap/} and {@code src/main/}, or of niord-app's {@code src/main/}, names a
 * country word, a Danish locale chosen in a template or a Danish or Faroese place name, unless an entry of its list,
 * {@code src/test/resources/build/country-words.json}, permits that word or pattern in that file with its reason, on
 * every line of the file or, when the entry gives a {@code text}, only on a line that holds it. An entry that permits
 * nothing fails, so the list shrinks with the code. The rule is C5's, and the list's note gives it: strict UTF-8 (a
 * file that does not decode fails, unless its type is a binary one, {@link #BINARY}), NFC, the licence header skipped
 * (the leading block comment, and a comment line with {@code Copyright} after it; a data line with the word is
 * read), the {@code strip} patterns removed, a word boundary before each word or pattern and none after, one finding
 * per line. {@code sample/} (Danish sample content) and
 * {@code src/test/} (test data and the IT fixture) are out of scope. Not in the country build group: it reads this
 * repository's tree, and DK's and GL's own data would fail it.
 */
class CountryWordsTest {

    /** The list; the tests run in the module's folder */
    static final Path LIST = Path.of("src/test/resources/build/country-words.json");

    /** The folder the list's paths are relative to */
    static final Path BE = Path.of("..");

    /** The scope, relative to {@link #BE} */
    static final List<String> SCOPE = List.of("niord/bootstrap/", "niord/src/main/", "niord-app/src/main/");

    static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    /** The file types read as binary, never as text: the scope holds images only today */
    static final Set<String> BINARY = Set.of("png", "jpg", "jpeg", "gif", "ico", "webp", "pdf", "zip", "jar", "class",
            "ttf", "otf", "woff", "woff2");

    /** How a comment line after the licence header starts, once stripped of its indent */
    static final List<String> COMMENT = List.of("*", "/*", "//", "#", "--", "<!--", "<#--");

    /** A named pattern or a strip pattern, as the list gives them */
    record Named(String name, String pattern, String reason) {
    }

    /** An entry of the list: a word or a pattern permitted in one file, on every line or on a line holding text */
    record Allowed(String path, String word, String pattern, String text, String reason) {

        /** The word or the pattern's name this entry permits */
        String hit() {
            return word != null ? word : pattern;
        }
    }

    /** The list, its words and patterns compiled once */
    record CountryWords(Map<String, Pattern> hits, List<Pattern> strip, List<Allowed> allowed) {
    }

    /** A line with one hit or more */
    record Finding(String path, int line, List<String> hits, String text) {
        @Override
        public String toString() {
            return path + ":" + line + " " + hits + " " + text.strip();
        }
    }

    static CountryWords list;

    @BeforeAll
    static void readTheList() throws IOException {
        list = read(LIST);
    }

    @SuppressWarnings("unchecked")
    static CountryWords read(Path file) throws IOException {
        Map<String, Object> json = JsonUtils.readJson(Map.class, file);
        assertEquals(1, json.get("format"), "the list's format");
        Map<String, Pattern> hits = new LinkedHashMap<>();
        for (String word : (List<String>) json.get("words")) {
            hits.put(word, Pattern.compile("\\b" + Pattern.quote(word), FLAGS));
        }
        for (Named p : named((List<Map<String, String>>) json.get("patterns"))) {
            assertFalse(hits.containsKey(p.name()), p.name() + " names a word and a pattern");
            hits.put(p.name(), Pattern.compile("\\b(?:" + p.pattern() + ")", FLAGS));
        }
        List<Pattern> strip = named((List<Map<String, String>>) json.get("strip")).stream()
                .map(p -> Pattern.compile(p.pattern())).toList();
        List<Allowed> allowed = new ArrayList<>();
        for (Map<String, String> a : (List<Map<String, String>>) json.get("allowed")) {
            Allowed entry = new Allowed(a.get("path"), a.get("word"), a.get("pattern"), a.get("text"),
                    a.get("reason"));
            assertTrue(entry.word() == null ^ entry.pattern() == null, entry + " gives a word or a pattern");
            assertTrue(entry.text() == null || !entry.text().isBlank(), entry + " gives a text that is not blank");
            assertTrue(hits.containsKey(entry.hit()), entry + " names a word or pattern of the list");
            assertTrue(entry.reason() != null && !entry.reason().isBlank(), entry + " gives its reason");
            assertFalse(entry.path().contains("*"), entry + " names one file, not a glob");
            allowed.add(entry);
        }
        return new CountryWords(hits, strip, allowed);
    }

    static List<Named> named(List<Map<String, String>> entries) {
        return entries.stream().map(e -> {
            Named p = new Named(e.get("name"), e.get("pattern"), e.get("reason"));
            assertTrue(p.reason() != null && !p.reason().isBlank(), e + " gives its reason");
            return p;
        }).toList();
    }

    /**
     * The findings of one file: {@code path} is relative to {@code be/}, and a file outside the scope has none. The
     * licence header is skipped: the leading block comment, and a comment line with {@code Copyright} after it (a line
     * that starts with one of {@link #COMMENT}). A data line with the word is read like any other.
     */
    static List<Finding> find(String path, String text, CountryWords list) {
        if (SCOPE.stream().noneMatch(path::startsWith)) {
            return List.of();
        }
        String[] lines = Normalizer.normalize(text, Normalizer.Form.NFC).split("\n", -1);
        int header = -1;
        int first = 0;
        while (first < lines.length && lines[first].isBlank()) {
            first++;
        }
        if (first < lines.length && lines[first].stripLeading().startsWith("/*")) {
            header = first;
            int open = lines[first].indexOf("/*") + 2;
            if (lines[first].indexOf("*/", open) < 0) {
                while (header + 1 < lines.length && !lines[header].contains("*/")) {
                    header++;
                }
            }
        }
        List<Finding> findings = new ArrayList<>();
        for (int i = header + 1; i < lines.length; i++) {
            String trimmed = lines[i].strip();
            if (lines[i].contains("Copyright") && COMMENT.stream().anyMatch(trimmed::startsWith)) {
                continue;
            }
            String line = lines[i];
            for (Pattern strip : list.strip()) {
                line = strip.matcher(line).replaceAll("");
            }
            String stripped = line;
            List<String> hits = list.hits().entrySet().stream()
                    .filter(e -> e.getValue().matcher(stripped).find()).map(Map.Entry::getKey).toList();
            if (!hits.isEmpty()) {
                findings.add(new Finding(path, i + 1, hits, lines[i]));
            }
        }
        return findings;
    }

    /**
     * The findings the list does not permit, each with only those hits; an entry that permits a hit goes to used. An
     * entry permits its hit in its file, and only on a line that holds its text when it gives one.
     */
    static List<Finding> notAllowed(List<Finding> findings, CountryWords list, Set<Allowed> used) {
        List<Finding> left = new ArrayList<>();
        for (Finding f : findings) {
            List<String> hits = f.hits().stream().filter(hit -> {
                List<Allowed> by = list.allowed().stream()
                        .filter(a -> a.path().equals(f.path()) && a.hit().equals(hit)
                                && (a.text() == null || f.text().contains(a.text())))
                        .toList();
                used.addAll(by);
                return by.isEmpty();
            }).toList();
            if (!hits.isEmpty()) {
                left.add(new Finding(f.path(), f.line(), hits, f.text()));
            }
        }
        return left;
    }

    /**
     * A file's text, decoded as strict UTF-8, or null for a file of a binary type ({@link #BINARY}), which is not read
     *
     * @param path  the file's path, whose extension names its type
     * @param bytes its content
     * @return its text, or null
     * @throws CharacterCodingException when a file of any other type does not decode: the guard cannot read it, and a
     *                                  Danish letter saved in ISO-8859-1 would otherwise hide the whole file
     */
    static String text(String path, byte[] bytes) throws CharacterCodingException {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String extension = name.lastIndexOf('.') < 0 ? "" : name.substring(name.lastIndexOf('.') + 1);
        if (BINARY.contains(extension.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * The scope's text files, by their path relative to {@code be/} ({@link #text}); a file that does not decode goes
     * to undecodable
     */
    static Map<String, String> tree(List<String> undecodable) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        Path be = BE.toAbsolutePath().normalize();
        for (String root : SCOPE) {
            Path dir = be.resolve(root);
            assertTrue(Files.isDirectory(dir), dir + " is in the scope");
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String path = be.relativize(file).toString().replace('\\', '/');
                    try {
                        String text = text(path, Files.readAllBytes(file));
                        if (text != null) {
                            files.put(path, text);
                        }
                    } catch (CharacterCodingException e) {
                        undecodable.add(path);
                    }
                }
            }
        }
        return files;
    }

    @Test
    void theScopeNamesNoCountryOutsideTheList() throws IOException {
        Set<Allowed> used = new HashSet<>();
        List<Finding> left = new ArrayList<>();
        List<String> undecodable = new ArrayList<>();
        for (Map.Entry<String, String> file : tree(undecodable).entrySet()) {
            left.addAll(notAllowed(find(file.getKey(), file.getValue(), list), list, used));
        }

        assertAll(
                () -> assertEquals(List.of(), undecodable, "files of a text type that do not decode as strict UTF-8"),
                () -> assertEquals("", left.stream().map(Finding::toString).collect(Collectors.joining("\n")),
                        "lines that name a country word or pattern no entry of country-words.json permits"),
                () -> assertEquals("", list.allowed().stream().filter(a -> !used.contains(a))
                        .map(Allowed::toString).collect(Collectors.joining("\n")),
                        "entries of country-words.json that permit nothing"));
    }

    // ------------------------------------------------------------------------------------------- //

    static List<String> hits(String path, String text) {
        return find(path, text, list).stream().flatMap(f -> f.hits().stream()).toList();
    }

    @Test
    void aPlaceNameInBootstrapDataIsFound() {
        assertEquals(List.of("place"), hits("niord/bootstrap/aton-dict.json",
                "      { \"key\": \"radio_navigation.dgps.skagen\", \"desc\": \"Skagen DGPS\" },"));
    }

    @Test
    void aDanishLocaleIsFoundInATemplateInJsonAndInASetting() {
        assertEquals(List.of("da-locale"), hits("niord/bootstrap/templates/tmpl/x.ftl",
                "<@renderDate date=msg.publishDateFrom lang=\"da\"/>"));
        assertEquals(List.of("da-locale"), hits("niord/bootstrap/releases/2026-10-01-x/script-resources.json",
                "\"content\": \"<@renderDate date=msg.publishDateFrom lang=\\\"da\\\"/>\""));
        assertEquals(List.of("da-locale"), hits("niord/bootstrap/templates/tmpl/x.ftl", "<#setting locale='da'>"));
        assertEquals(List.of("da-locale"), hits("niord/bootstrap/templates/tmpl/x.ftl", "<#setting locale=\"da_DK\">"));
        assertEquals(List.of(), hits("niord/src/main/java/X.java", "String locale = data.locale();"));
    }

    @Test
    void aDecomposedPlaceNameIsFoundAfterNfc() {
        // an a followed by U+030A, the combining ring above: the decomposed form of å, which NFC composes
        assertEquals(List.of("place"), hits("niord/bootstrap/texts.json", "\"value\": \"Bla\u030Avand\""));
    }

    @Test
    void aWordIsMatchedWithABoundaryBeforeAndNoneAfter() {
        assertEquals(List.of("Søfart"), hits("niord/src/main/java/X.java", "// Søfartsstyrelsens publikationer"));
        assertEquals(List.of(), hits("niord/src/main/java/X.java", "landmark = scheduledMail();"));
    }

    @Test
    void theLicenceHeaderIsSkippedAndTheLinesAfterItAreNot() {
        String text = """
                /*
                 * Copyright 2026 Danish Maritime Authority.
                 * Licensed in Denmark
                 */
                package org.niord.x;

                // Copyright Danish Maritime Authority
                // Denmark
                """;

        List<Finding> findings = find("niord/src/main/java/org/niord/x/X.java", text, list);

        assertEquals(List.of("niord/src/main/java/org/niord/x/X.java:8 [Denmark] // Denmark"),
                findings.stream().map(Finding::toString).toList());
    }

    /** Copyright skips a comment line only: a data line that carries the word is read, and its words found */
    @Test
    void aDataLineWithCopyrightIsRead() {
        String text = """
                [
                  # Copyright Danish Maritime Authority
                  { "key": "footer", "value": "Copyright Danish Maritime Authority" }
                ]
                """;

        List<Finding> findings = find("niord/bootstrap/texts.json", text, list);

        assertEquals(List.of("niord/bootstrap/texts.json:3 [Danish Maritime Authority] "
                        + "{ \"key\": \"footer\", \"value\": \"Copyright Danish Maritime Authority\" }"),
                findings.stream().map(Finding::toString).toList());
    }

    /** An entry with a text permits its hit only on a line that holds the text; one without, on every line */
    @Test
    void anEntryWithATextPermitsOnlyTheLinesThatHoldIt() {
        String path = "niord/src/main/java/org/niord/x/X.java";
        String text = """
                // an example: dma-nw
                String series = "dma-nm";
                """;
        List<Finding> findings = find(path, text, list);
        assertEquals(2, findings.size(), "both lines name DMA: " + findings);

        CountryWords withText = new CountryWords(list.hits(), list.strip(),
                List.of(new Allowed(path, "DMA", null, "an example: dma-nw", "an example")));
        Set<Allowed> used = new HashSet<>();
        assertEquals(List.of(path + ":2 [DMA] String series = \"dma-nm\";"),
                notAllowed(findings, withText, used).stream().map(Finding::toString).toList());
        assertEquals(withText.allowed(), List.copyOf(used), "the entry permitted the line that holds its text");

        CountryWords fileWide = new CountryWords(list.hits(), list.strip(),
                List.of(new Allowed(path, "DMA", null, null, "an example")));
        assertEquals(List.of(), notAllowed(findings, fileWide, new HashSet<>()), "without a text, every line");
    }

    /** A file of a text type that does not decode as strict UTF-8 is reported; one of a binary type is not read */
    @Test
    void aFileThatDoesNotDecodeIsReportedUnlessItIsBinary() throws Exception {
        byte[] latin1 = "Søfart".getBytes(StandardCharsets.ISO_8859_1);

        assertThrows(CharacterCodingException.class, () -> text("niord/src/main/resources/x.properties", latin1),
                "an ISO-8859-1 .properties file is not dropped silently");
        assertNull(text("niord/src/main/resources/x.png", latin1), "an image is not read");
        assertEquals("Søfart", text("niord/src/main/resources/x.properties",
                "Søfart".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theThirdPartyPackageIsStripped() {
        assertEquals(List.of(), hits("niord/src/main/java/X.java", "import dk.dma.niord.s100.s124.Dataset;"));
        assertEquals(List.of("DMA"), hits("niord/src/main/java/X.java", "import dk.dma.niord.s100.x; // DMA"));
    }

    @Test
    void anEnglishLocaleIsNotFound() {
        assertEquals(List.of(), hits("niord/bootstrap/templates/tmpl/x.ftl", "<@renderDate lang=\"en\"/>"));
    }

    @Test
    void theSampleAndTheTestSourcesAreOutOfScope() {
        assertEquals(List.of(), hits("niord/sample/archive/texts.json", "\"value\": \"Denmark\""));
        assertEquals(List.of(), hits("niord/src/test/resources/db/niord-test-seed.sql", "'Skagen', 'da'"));
    }

    @Test
    void theReleaseRecordIsAllowedByTheList() {
        String path = "niord/bootstrap/releases/2026-10-21-templates-from-database/script-resources.json";
        List<Finding> findings = find(path, "\"content\": \"<@renderDate lang=\\\"da\\\"/>\"", list);

        assertEquals(1, findings.size(), "the matcher finds the line");
        assertEquals(List.of(), notAllowed(findings, list, new HashSet<>()), "and the list permits it");
    }
}
