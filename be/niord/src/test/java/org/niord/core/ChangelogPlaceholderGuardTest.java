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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No production changeset holds a dollar sign followed by an opening brace.
 * <p>
 * Liquibase reads that pair as the start of a changelog parameter and replaces the parameter with
 * its value wherever one is defined, and its parameters include the JVM's system properties. A
 * statement or a comment that holds one (a template's placeholder in an inserted text, say) then
 * writes something else than its file says, and what it writes can differ from one environment to
 * the next. Nothing in the schema needs a parameter, so the guard refuses the pair in every file
 * under {@code db/changelog/changes} and in the master changelog, and names the file and line.
 * <p>
 * No file holds the pair today: the guard keeps it that way.
 */
class ChangelogPlaceholderGuardTest {

    static final Path CHANGELOG = Path.of("src/main/resources/db/changelog");
    static final Path CHANGES = CHANGELOG.resolve("changes");
    static final Path MASTER = CHANGELOG.resolve("db.changelog-master.yaml");

    @Test
    void noProductionChangesetHoldsAChangelogParameter() throws IOException {
        List<Path> files = changelogFiles();
        assertTrue(Files.isRegularFile(MASTER), "the guard reads the master changelog: " + MASTER.toAbsolutePath());
        assertTrue(files.size() > 1, "the guard reads the changesets under " + CHANGES.toAbsolutePath());

        List<String> hits = new ArrayList<>();
        for (Path file : files) {
            hits.addAll(placeholders(CHANGELOG.relativize(file).toString().replace('\\', '/'),
                    Files.readAllLines(file, StandardCharsets.UTF_8)));
        }

        assertEquals(List.of(), hits,
                "A production changeset holds a dollar sign followed by an opening brace. Liquibase reads it as "
                + "a changelog parameter and writes the parameter's value in its place. Write the text another "
                + "way (for SQL, split the pair: '$' || '{'), or leave it out of the changeset.");
    }

    /** The guard sees the pair where it stands, and nothing else: it is not green because it looks at nothing. */
    @Test
    void theGuardNamesTheFileAndLineOfEachPair() {
        List<String> hits = placeholders("changes/V999__sample.sql", List.of(
                "-- a plain comment",
                "INSERT INTO t (v) VALUES ('${user.home}');",
                "SELECT '$' || '{';",
                "SELECT '$5 {x}', '${a} and ${b}';"));

        assertEquals(List.of(
                "changes/V999__sample.sql:2: INSERT INTO t (v) VALUES ('${user.home}');",
                "changes/V999__sample.sql:4: SELECT '$5 {x}', '${a} and ${b}';"), hits,
                "a plain line, a split pair and a dollar sign before a space are no parameter");
    }

    /** The master changelog and every regular file under the changes folder, in name order. */
    private static List<Path> changelogFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        files.add(MASTER);
        try (Stream<Path> walk = Files.walk(CHANGES)) {
            walk.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        return files;
    }

    /** One entry "file:line: text" for each line that holds a dollar sign directly followed by an opening brace. */
    static List<String> placeholders(String name, List<String> lines) {
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("${")) {
                hits.add(name + ":" + (i + 1) + ": " + lines.get(i).strip());
            }
        }
        return hits;
    }
}
