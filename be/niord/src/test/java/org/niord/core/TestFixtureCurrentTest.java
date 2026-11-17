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
package org.niord.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The integration suite's fixture ({@code db/niord-base-data.sql}) holds the templates and scripts of today's base
 * product bootstrap and sample. The fixture is a dump of a database bootstrapped with {@code bootstrap/} and loaded
 * with {@code sample/} (docs/code-data-config-plan.md §7.4), and since C4b no test reads {@code bootstrap/templates/}
 * live, so a template edited there reaches the integration tests only when the fixture is regenerated. This test
 * fails until it is: the fixture's {@code ScriptResource} rows must be the files of {@code bootstrap/templates/} and
 * {@code sample/archive/templates/}, path for path and byte for byte.
 *
 * <p>The fixture travels in niord-base's test-jar, which the no-sample check ({@code k8s/scripts/check-no-sample.sh})
 * reads, so it must carry none of the sample's markers: neither the sentinel line of {@code niord-sample.sql} nor the
 * message uid prefix of {@code messages/messages.json}, which the regeneration rewrites to the fixture's own. Both are
 * read from the sample folder at run time, as the check reads them (every uid of the file, which must share one
 * prefix), and written nowhere in this class.
 */
class TestFixtureCurrentTest {

    /** The fixture; the tests run in the module's folder */
    static final Path FIXTURE = Path.of("src/test/resources/db/niord-base-data.sql");

    /** The fixture's full dump, the human-readable reference beside it */
    static final Path FULL_DUMP = Path.of("src/test/resources/db/niord-test-seed.sql");

    /** The sample folder the fixture's sample rows come from */
    static final Path SAMPLE = Path.of("sample");

    /** The start of the sample's sentinel line (CheckNoSample.SENTINEL_PREFIX); the line's token follows it */
    static final String SENTINEL_PREFIX = "-- niord-sample-sentinel: ";

    /** A message uid of messages.json, its prefix up to the fourth dash as group 1 (CheckNoSample.uidPrefix) */
    static final Pattern UID = Pattern.compile(
            "\"id\"\\s*:\\s*\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-)[0-9a-f]{12}\"");

    /** The template folders whose files the bootstrap runner imports as rows {@code templates/<path>} */
    static final List<Path> TEMPLATE_FOLDERS = List.of(
            Path.of("bootstrap/templates"), Path.of("sample/archive/templates"));

    static final String REGENERATE = "regenerate it with niord/scripts/regen-test-seed-postgres.sh "
            + "(after niord/scripts/regen-sample.sh when the sample is due too)";

    /** A ScriptResource insert as pg_dump --column-inserts writes it, up to its first value */
    static final Pattern INSERT = Pattern.compile("^INSERT INTO public\\.\"ScriptResource\" \\(([^)]*)\\) VALUES \\(",
            Pattern.MULTILINE);

    @Test
    void theFixturesTemplatesAreTheBootstrapsAndTheSamples() throws IOException {
        Map<String, String> expected = new TreeMap<>();
        for (Path folder : TEMPLATE_FOLDERS) {
            assertTrue(Files.isDirectory(folder), folder + " must exist");
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String path = "templates/" + folder.relativize(file).toString().replace('\\', '/');
                    expected.put(path, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        Map<String, String> rows = scriptResources(Files.readString(FIXTURE, StandardCharsets.UTF_8));

        assertEquals(new TreeSet<>(expected.keySet()), new TreeSet<>(rows.keySet()),
                "the fixture's ScriptResource paths are not the bootstrap's and the sample's: " + REGENERATE);
        expected.forEach((path, content) -> assertEquals(content, rows.get(path),
                "the fixture's " + path + " differs from its file: " + REGENERATE));
    }

    @Test
    void theFixtureCarriesNoSampleMarker() throws IOException {
        String sentinel = Files.readAllLines(SAMPLE.resolve("niord-sample.sql"), StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith(SENTINEL_PREFIX) && line.length() > SENTINEL_PREFIX.length() + 16)
                .map(String::strip)
                .findFirst().orElse(null);
        assertNotNull(sentinel, "sample/niord-sample.sql has no sentinel line");
        String uidPrefix = uidPrefix(
                Files.readString(SAMPLE.resolve("messages/messages.json"), StandardCharsets.UTF_8));

        for (Path dump : List.of(FIXTURE, FULL_DUMP)) {
            String sql = Files.readString(dump, StandardCharsets.UTF_8);
            assertFalse(sql.contains(uidPrefix), dump + " holds the sample's message uid prefix, which the no-sample "
                    + "check finds in niord-base's test-jar: " + REGENERATE + ", which rewrites it");
            assertFalse(sql.contains(sentinel), dump + " holds the sample's sentinel line: " + REGENERATE);
        }
    }

    @Test
    void theParserReadsQuotesNullsAndLineBreaks() {
        String sql = """
                INSERT INTO public."ScriptResource" (id, content, path, type) VALUES (7, 'a ''quoted'', (x)
                second line', 'templates/a.ftl', 'FM');
                INSERT INTO public."ScriptResource" (id, content, path, type) VALUES (8, NULL, 'templates/b.js', 'JS');
                """;
        Map<String, String> rows = scriptResources(sql);

        assertEquals(List.of("templates/a.ftl", "templates/b.js"), new ArrayList<>(rows.keySet()));
        assertEquals("a 'quoted', (x)\nsecond line", rows.get("templates/a.ftl"));
        assertNull(rows.get("templates/b.js"));
    }

    @Test
    void aSecondUidPrefixIsRefused() {
        String messages = """
                [ { "id" : "0a0a0a0a-0000-4000-8000-000000000001" },
                  { "id" : "0a0a0a0a-0000-4000-8000-000000000002" },
                  { "id" : "0b0b0b0b-1111-4111-8111-000000000003" } ]
                """;
        assertThrows(AssertionError.class, () -> uidPrefix(messages), "two uid prefixes are one marker too few");
        assertThrows(AssertionError.class, () -> uidPrefix("[]"), "no uid is no marker");
        assertEquals("0a0a0a0a-0000-4000-8000-", uidPrefix(messages.substring(0, messages.indexOf("{ \"id\" : \"0b"))));
    }

    /**
     * The common start of the message uids of a messages.json, up to and with their fourth dash, as the check reads it
     * (CheckNoSample.uidPrefix): every uid is read, and none or a second prefix fails
     */
    static String uidPrefix(String messagesJson) {
        Matcher m = UID.matcher(messagesJson);
        String prefix = null;
        while (m.find()) {
            if (prefix == null) {
                prefix = m.group(1);
            } else {
                assertEquals(prefix, m.group(1), "the sample's message uids do not share one prefix, by which the "
                        + "no-sample check knows a copy of them (it refuses the sample folder too)");
            }
        }
        assertNotNull(prefix, "sample/messages/messages.json holds no message uid");
        return prefix;
    }

    /** The content of each ScriptResource insert of the dump, by its path */
    static Map<String, String> scriptResources(String sql) {
        Map<String, String> rows = new TreeMap<>();
        Matcher m = INSERT.matcher(sql);
        while (m.find()) {
            List<String> columns = Arrays.stream(m.group(1).split(",")).map(String::trim).toList();
            List<String> values = values(sql, m.end());
            assertEquals(columns.size(), values.size(), "a ScriptResource insert of the fixture could not be read");
            rows.put(values.get(columns.indexOf("path")), values.get(columns.indexOf("content")));
        }
        return rows;
    }

    /** The values of one insert from just after its opening parenthesis: strings unquoted, NULL as null */
    static List<String> values(String sql, int from) {
        List<String> values = new ArrayList<>();
        int i = from;
        while (true) {
            while (sql.charAt(i) == ' ') {
                i++;
            }
            if (sql.charAt(i) == '\'') {
                StringBuilder value = new StringBuilder();
                i++;
                while (true) {
                    char c = sql.charAt(i++);
                    if (c == '\'') {
                        if (sql.charAt(i) != '\'') {
                            break;
                        }
                        i++;
                    }
                    value.append(c);
                }
                values.add(value.toString());
            } else {
                int end = i;
                while (sql.charAt(end) != ',' && sql.charAt(end) != ')') {
                    end++;
                }
                String literal = sql.substring(i, end).trim();
                values.add("NULL".equals(literal) ? null : literal);
                i = end;
            }
            while (sql.charAt(i) == ' ') {
                i++;
            }
            if (sql.charAt(i++) == ')') {
                return values;
            }
        }
    }
}
