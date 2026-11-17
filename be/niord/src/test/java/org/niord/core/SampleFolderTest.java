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
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.batch.vo.BatchSetVo;
import org.niord.core.bootstrap.BootstrapArchive;
import org.niord.core.bootstrap.BootstrapRunner;
import org.niord.core.category.StandardParamType;
import org.niord.core.util.JsonUtils;
import org.niord.test.sample.SampleReferences;
import org.niord.test.sample.SampleSafetyCheck;
import org.niord.test.sample.SampleTool;
import org.opentest4j.AssertionFailedError;
import tools.jackson.core.type.TypeReference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample's folder, {@code be/niord/sample} (docs/fe-country-split-plan.md P9; docs/code-data-config-plan.md
 * §8): data loaded only into the loopback {@code niord_sample}, or a country launcher's {@code niord_sample_<cc>},
 * its sources an archive the bootstrap runner accepts and messages written for it, its generated SQL within the
 * safety rules, its own ids at the offset and the base rows named by natural key. Backend CI re-includes the folder
 * after its Markdown exclusion, so a change to the README alone runs this test.
 */
class SampleFolderTest {

    /** The sample; the tests run in the module's folder */
    static final Path SAMPLE = Path.of("sample");

    static final Path ARCHIVE = SAMPLE.resolve("archive");

    static final Path SQL = SAMPLE.resolve("niord-sample.sql");

    static final Path MESSAGES = SAMPLE.resolve("messages/messages.json");

    /** The schema's changelog, from which the guard's changesets are derived */
    static final Path CHANGELOG = Path.of("src/main/resources/db/changelog");

    /** The regeneration, whose SAMPLE_EPOCH the bookkeeping timestamps hold */
    static final Path REGEN = Path.of("scripts/regen-sample.sh");

    /** The bookkeeping columns, which the regeneration sets to SAMPLE_EPOCH */
    static final Set<String> BOOKKEEPING = Set.of("created", "updated", "nextScheduledExecution");

    /** The columns whose timestamps are content, written in the sources */
    static final Set<String> CONTENT_TIMESTAMPS = Set.of("Message.publishDateFrom", "Message.publishDateTo",
            "Message.eventDateFrom", "Message.eventDateTo", "Message.followUpDate", "DateInterval.fromDate",
            "DateInterval.toDate", "Publication.publishDateFrom", "Publication.publishDateTo");

    static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?.*");

    static final String REGENERATE = "regenerate with be/niord/scripts/regen-sample.sh";

    /** A value that is no expression and no literal: a decimal number, NULL or a boolean */
    static final String PLAIN = "-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|NULL|true|false";

    @Test
    void theReadmeSaysTheFolderIsDataLoadedOnlyIntoTheSampleDatabase() throws IOException {
        assertTrue(Files.isRegularFile(SAMPLE.resolve("README.md")), "sample/README.md must exist");
        String readme = Files.readString(SAMPLE.resolve("README.md"), StandardCharsets.UTF_8);
        // Compared with the whitespace normalised: the README wraps its first sentence as its siblings do
        assertTrue(readme.replaceAll("\\s+", " ").startsWith("Data, not code. Loaded only into a fresh deployment "
                        + "(bootstrap), only by a sysadmin's import (releases), or only into the demo database (sample). "
                        + "A running deployment's data may differ from these files and is the truth; nothing here ever "
                        + "changes it."),
                "the README's first words say what the files are and are not");
        assertTrue(readme.contains("`niord_sample`"), "the README names the one database the sample is loaded into");
    }

    @Test
    void theArchiveIsOneTheBootstrapRunnerAccepts() throws IOException {
        List<BatchSetVo> items = JsonUtils.fromJson(read(ARCHIVE.resolve("batch-set.json")),
                new TypeReference<List<BatchSetVo>>() {});
        assertFalse(items.isEmpty());
        for (BatchSetVo item : items) {
            assertTrue(BootstrapRunner.ACCEPTED_JOBS.contains(item.getJobName()),
                    "the runner does not accept " + item.getJobName());
            assertFalse("report-import".equals(item.getJobName()), "reports are DK's bootstrap, not the sample's");
            Path file = ARCHIVE.resolve(item.getFileName());
            if (BootstrapRunner.SCRIPT_RESOURCE_JOB.equals(item.getJobName())) {
                assertEquals(BootstrapArchive.TEMPLATES, item.getFileName());
                assertTrue(Files.isDirectory(file));
            } else {
                assertTrue(Files.isRegularFile(file), item.getJobName() + " names a missing file " + file);
            }
        }
        // No item carries add, overwrite, delay or bootstrapRun
        for (Map<String, Object> item : JsonUtils.fromJson(read(ARCHIVE.resolve("batch-set.json")),
                new TypeReference<List<Map<String, Object>>>() {})) {
            assertEquals(Set.of("jobName", "fileName"), item.keySet(), "an item names its job and file only: " + item);
        }
        // The folder, zipped, passes the archive rules
        BootstrapArchive.read("sample-archive.zip", new ByteArrayInputStream(zipFolder(ARCHIVE)));
    }

    @Test
    void theArchiveHoldsNoStandardParameterTypeAndNoClientDomainId() throws IOException {
        List<String> standard = List.of(StandardParamType.STANDARD_PARAM_TYPES);
        for (Map<String, Object> type : JsonUtils.fromJson(read(ARCHIVE.resolve("parameter-types.json")),
                new TypeReference<List<Map<String, Object>>>() {})) {
            assertFalse("STANDARD".equals(type.get("type")) || standard.contains((String) type.get("name")),
                    "a standard parameter type is code, not data: " + type.get("name"));
        }
        for (Path file : files(ARCHIVE)) {
            assertFalse(read(file).contains("niord-client-"),
                    file + " names a niord-client-* domain: the sample's domains are niord-nw, niord-nm, ...");
        }
    }

    @Test
    void theSqlKeepsTheSafetyRules() throws IOException {
        assertTrue(Files.isRegularFile(SQL), "sample/niord-sample.sql must exist: regenerate with "
                + "be/niord/scripts/regen-sample.sh");
        assertEquals(List.of(), SampleSafetyCheck.check(SQL));
    }

    /**
     * The guard's changesets are the schema's: a changeset added without a regeneration makes every load refuse, so
     * it is red here, in CI, instead. The changesets are derived from the master changelog's includes and each
     * file's {@code --changeset} line, not from a list kept by hand.
     */
    @Test
    void theGuardsChangesetsAreTheChangelogs() throws IOException {
        assertTheGuardsChangesetsAre(CHANGELOG);
    }

    /** A changeset added to the changelog without a regeneration is red (the scratch case of the check above) */
    @Test
    void anAddedChangesetIsRed(@TempDir Path copy) throws IOException {
        List<String> before = changesets(CHANGELOG);
        assertTrue(before.size() >= 8 && before.contains("001-baseline-schema"), before.toString());
        Files.createDirectories(copy.resolve("changes"));
        for (Path file : files(CHANGELOG)) {
            Files.copy(file, copy.resolve(CHANGELOG.relativize(file).toString()));
        }
        Files.writeString(copy.resolve("db.changelog-master.yaml"), "  - include:\n"
                        + "      file: changes/V009__scratch.sql\n      relativeToChangelogFile: true\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Files.writeString(copy.resolve("changes/V009__scratch.sql"),
                "--liquibase formatted sql\n--changeset niord:009-scratch\nSELECT 1;\n", StandardCharsets.UTF_8);
        List<String> after = changesets(copy);
        assertEquals(before.size() + 1, after.size(), after.toString());
        assertTrue(after.contains("009-scratch"), after.toString());
        AssertionFailedError red = assertThrows(AssertionFailedError.class, () -> assertTheGuardsChangesetsAre(copy));
        assertTrue(red.getMessage().contains(REGENERATE), red.getMessage());
    }

    /** The guard's changesets, from the SQL, are those of the changelog folder */
    static void assertTheGuardsChangesetsAre(Path changelog) throws IOException {
        Matcher m = Pattern.compile("FROM public\\.databasechangelog\\)\\s+IS DISTINCT FROM '([^']*)'")
                .matcher(read(SQL));
        assertTrue(m.find(), "the guard checks the changesets");
        List<String> guard = Stream.of(m.group(1).split(",")).sorted().toList();
        assertEquals(changesets(changelog), guard, "the schema's changesets are not the ones the sample was made "
                + "on: " + REGENERATE);
    }

    /**
     * The bookkeeping timestamps hold the sample's epoch only, so that two regenerations on any day write the same
     * bytes; every other timestamp is in a column whose value the sources write
     */
    @Test
    void everyBookkeepingTimestampIsTheEpoch() throws IOException {
        Matcher m = Pattern.compile("(?m)^SAMPLE_EPOCH=\"([^\"]+)\"$").matcher(read(REGEN));
        assertTrue(m.find(), REGEN + " sets SAMPLE_EPOCH");
        String epoch = m.group(1);
        List<String> wrong = new ArrayList<>();
        for (SampleSafetyCheck.Insert insert : SampleSafetyCheck.inserts(read(SQL))) {
            for (String column : insert.columns()) {
                String text = insert.text(column);
                if (text == null) {
                    continue;
                }
                if (BOOKKEEPING.contains(column)) {
                    // The repository rows' times carry their zone (timestamp with time zone), as pg_dump writes them
                    if (!epoch.equals(text) && !(epoch + "+00").equals(text)) {
                        wrong.add(insert.table() + "." + column + " = " + text + ", not the epoch " + epoch);
                    }
                } else if (TIMESTAMP.matcher(text).matches()
                        && !CONTENT_TIMESTAMPS.contains(insert.table() + "." + column)) {
                    wrong.add(insert.table() + "." + column + " = " + text + ", a timestamp in no content column");
                }
            }
        }
        assertEquals(List.of(), wrong, "a timestamp the regeneration writes from the clock: normalise it in "
                + "be/niord/scripts/regen-sample.sh and " + REGENERATE);
    }

    /**
     * The guard's refusals name their remedies: a changed schema needs a regeneration; a database that is not one
     * clean run of the base product's bootstrap needs a new niord_sample. It checks that the sample's id range is
     * free in every table it inserts into with an id, and that every base table it names resolves by natural key;
     * nothing in it pins a base row's id.
     */
    @Test
    void theGuardNamesItsRemediesAndResolvesTheBaseRowsByNaturalKey() throws IOException {
        String sql = read(SQL);
        String guard = sql.substring(sql.indexOf("DO $guard$"), sql.indexOf("$guard$;"));
        assertTrue(guard.contains("stale CONSTANT text := 'regenerate the sample with be/niord/scripts/regen-sample.sh'"),
                "the changeset refusal's remedy");
        assertTrue(guard.contains("unclean CONSTANT text := 'the database is not one clean run of the base product''s "
                + "bootstrap"), "the remedy of the offset, domain and natural-key refusals");
        assertTrue(guard.contains("recreate it with ./start-local.sh --sample --reset"), "--reset is the cure");
        assertTrue(guard.contains("IF current_database() !~ '^niord_sample(_[a-z]{2})?$' THEN"),
                "niord_sample or niord_sample_<cc>");
        for (String line : guard.split("\n")) {
            if (line.contains("Liquibase changesets")) {
                assertTrue(line.endsWith("%', stale;"), line);
            } else if (line.contains("RAISE EXCEPTION") && (line.contains("already holds")
                    || line.contains("missing or not unique"))) {
                assertTrue(line.endsWith(", unclean;"), line);
            }
        }
        Set<String> idTables = new TreeSet<>();
        for (SampleSafetyCheck.Insert insert : SampleSafetyCheck.inserts(sql)) {
            if (insert.columns().contains("id")) {
                idTables.add(insert.table());
            }
        }
        assertFalse(idTables.isEmpty());
        for (String table : idTables) {
            assertTrue(guard.contains("IF EXISTS (SELECT 1 FROM public.\"" + table + "\" WHERE id >= "
                    + SampleReferences.FIRST_ID + ")"), "the guard checks the offset range of " + table);
        }
        for (String table : List.of("Category", "Dictionary", "DictionaryEntry", "PromulgationType", "TemplateParam",
                "ParamType")) {
            assertTrue(guard.contains("RAISE EXCEPTION 'niord-sample: base rows of \"" + table + "\" that the sample "
                    + "refers to are missing or not unique"), "the guard resolves the base rows of " + table + " by key");
        }
        assertFalse(guard.contains("t.id IN ("), "no base row is pinned by its id");
        assertFalse(guard.contains("md5("), "no fingerprint");
    }

    /**
     * Every id the SQL writes is the sample's, at the offset, and every base row is named by natural key: an integer
     * in a column named id or ending in _id is at or above SampleReferences.FIRST_ID, and every value that is no
     * literal, number, NULL or boolean is a reference; the regeneration's first id is that FIRST_ID
     */
    @Test
    void everySampleIdIsAtTheOffsetAndNoBaseRowIsNamedById() throws IOException {
        Matcher m = Pattern.compile("(?m)^SAMPLE_FIRST_ID=(\\d+)$").matcher(read(REGEN));
        assertTrue(m.find(), REGEN + " sets SAMPLE_FIRST_ID");
        assertEquals(SampleReferences.FIRST_ID, Integer.parseInt(m.group(1)), "the regeneration's first id");
        List<String> wrong = new ArrayList<>();
        int references = 0;
        for (SampleSafetyCheck.Insert insert : SampleSafetyCheck.inserts(read(SQL))) {
            for (int i = 0; i < insert.columns().size(); i++) {
                String column = insert.columns().get(i);
                String value = insert.values().get(i);
                boolean idColumn = column.equals("id") || column.endsWith("_id");
                if (value.matches("-?\\d+")) {
                    if (idColumn && Long.parseLong(value) < SampleReferences.FIRST_ID) {
                        wrong.add(insert.table() + "." + column + " = " + value + ", below the sample's first id");
                    }
                } else if (!value.startsWith("'") && !value.matches(PLAIN)) {
                    if (SampleReferences.parse(value) == null) {
                        wrong.add(insert.table() + "." + column + " = " + value + ", not a natural key");
                    } else {
                        references++;
                    }
                }
            }
        }
        assertEquals(List.of(), wrong, REGENERATE);
        assertTrue(references > 0, "the sample names base rows by natural key");
    }

    /** The import drops an area, category or chart it cannot resolve: the SQL holds every link the messages name */
    @Test
    void theSqlHoldsEveryLinkTheMessagesName() throws IOException {
        Map<String, Integer> expected = SampleTool.counts(MESSAGES);
        assertEquals(Set.of("Message_Area", "Message_Category", "Message_Chart", "Attachment", "Reference",
                "FeatureCollection"), expected.keySet());
        Map<String, Integer> inserted = new TreeMap<>();
        expected.keySet().forEach(table -> inserted.put(table, 0));
        for (SampleSafetyCheck.Insert insert : SampleSafetyCheck.inserts(read(SQL))) {
            if (expected.containsKey(insert.table())) {
                inserted.merge(insert.table(), 1, Integer::sum);
            }
        }
        assertEquals(new TreeMap<>(expected), inserted, "the rows of the link tables: " + REGENERATE);
    }

    /**
     * The regeneration stops when SampleTool fails: its output is captured in an assignment, never fed to a loop
     * through a here-string or a process substitution, whose failure set -euo pipefail does not see
     */
    @Test
    void theRegenerationStopsWhenSampleToolFails() throws IOException {
        Pattern unchecked = Pattern.compile("<<<\\s*\"?\\$\\(\\s*sample_tool|<\\(\\s*sample_tool");
        List<String> wrong = new ArrayList<>();
        String[] lines = read(REGEN).split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (unchecked.matcher(lines[i]).find()) {
                wrong.add((i + 1) + ": " + lines[i].strip());
            }
        }
        assertEquals(List.of(), wrong, "a SampleTool call whose failure does not stop " + REGEN);
    }

    /** The demo zip's defects stay out: a name with a space at an end, a doubled period, an old authority's link */
    @Test
    void theSourcesCarryNoTrailingSpaceDoubledPeriodOrLink() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> sources = new ArrayList<>(files(ARCHIVE).stream().filter(p -> p.toString().endsWith(".json"))
                .toList());
        sources.add(MESSAGES);
        Pattern url = Pattern.compile("https?://[^\\s\"<]+");
        for (Path file : sources) {
            String text = read(file);
            walk(JsonUtils.fromJson(text, new TypeReference<Object>() {}), file.getFileName().toString(), found);
            Matcher m = url.matcher(text);
            while (m.find()) {
                if (!m.group().matches("https?://(www\\.)?example\\.org([/:].*)?")) {
                    found.add(file.getFileName() + ": a link " + m.group());
                }
            }
        }
        assertEquals(List.of(), found);
    }

    /**
     * Every external link publication carries a link in each of its descriptions, in the archive and in the SQL: a
     * stand-in address at example.org where the demo zip's plain-http links to 2015 and 2016 documents were left out
     * (the owner's ruling of 2026-11-03). The internal link publication, Journalnummer, never had one.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyExternalLinkPublicationCarriesItsLinks() throws IOException {
        List<Map<String, Object>> publications = JsonUtils.fromJson(read(ARCHIVE.resolve("publications.json")),
                new TypeReference<>() {});
        // The descriptions of the external link publications, by language and title, with their links
        Map<String, String> links = new TreeMap<>();
        int external = 0;
        for (Map<String, Object> publication : publications) {
            if (!"LINK".equals(publication.get("type")) || !"EXTERNAL".equals(publication.get("messagePublication"))) {
                continue;
            }
            external++;
            for (Map<String, Object> desc : (List<Map<String, Object>>) publication.get("descs")) {
                links.put(desc.get("lang") + " " + desc.get("title"),
                        desc.get("link") instanceof String link ? link.strip() : "");
            }
        }
        assertEquals(3, external, "the sample's external link publications");

        List<String> missing = new ArrayList<>();
        links.forEach((desc, link) -> {
            if (link.isEmpty()) {
                missing.add("publications.json: " + desc + " has no link");
            }
        });
        Map<String, String> rows = new TreeMap<>();
        for (SampleSafetyCheck.Insert insert : SampleSafetyCheck.inserts(read(SQL))) {
            String desc = insert.text("lang") + " " + insert.text("title");
            if (!insert.table().equals("PublicationDesc") || !links.containsKey(desc)) {
                continue;
            }
            String link = insert.text("link");
            rows.put(desc, link == null ? "" : link.strip());
            if (link == null || link.isBlank()) {
                missing.add("niord-sample.sql: PublicationDesc " + insert.values().getFirst() + " (" + desc
                        + ") has no link");
            }
        }
        assertEquals(List.of(), missing, REGENERATE);
        assertEquals(links, rows, "the SQL's links are the archive's: " + REGENERATE);
    }

    @SuppressWarnings("unchecked")
    static void walk(Object node, String file, List<String> found) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                if (e.getValue() instanceof String text) {
                    if (Set.of("name", "abbreviation", "title", "vicinity").contains(e.getKey())
                            && !text.equals(text.strip())) {
                        found.add(file + ": " + e.getKey() + " '" + text + "' has a space at an end");
                    }
                    if ("title".equals(e.getKey()) && text.contains("..")) {
                        found.add(file + ": title '" + text + "' has a doubled period");
                    }
                } else {
                    walk(e.getValue(), file, found);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                walk(item, file, found);
            }
        }
    }

    /**
     * The changeset ids of a changelog folder: its master's includes, in order, and each file's
     * {@code --changeset <author>:<id>} lines
     *
     * @param folder the folder holding db.changelog-master.yaml
     * @return the ids, sorted
     */
    static List<String> changesets(Path folder) throws IOException {
        List<String> ids = new ArrayList<>();
        Matcher include = Pattern.compile("(?m)^\\s*file:\\s*(\\S+)\\s*$").matcher(read(folder.resolve(
                "db.changelog-master.yaml")));
        while (include.find()) {
            Matcher changeset = Pattern.compile("(?m)^--changeset\\s+[^:\\s]+:(\\S+)")
                    .matcher(read(folder.resolve(include.group(1))));
            while (changeset.find()) {
                ids.add(changeset.group(1));
            }
        }
        return ids.stream().sorted().toList();
    }

    /** The attachment files are rows of the repository store: exactly the files the Attachment rows name, for good */
    @Test
    void everyRepositoryRowIsAnAttachmentOfTheSql() throws IOException {
        Set<String> named = new TreeSet<>();
        Set<String> rows = new TreeSet<>();
        for (SampleSafetyCheck.Insert insert : SampleSafetyCheck.inserts(read(SQL))) {
            if ("Attachment".equals(insert.table())) {
                named.add(insert.text("path"));
            } else if ("RepoFile".equals(insert.table())) {
                String path = insert.text("path");
                rows.add(path);
                assertEquals(insert.text("folder") + "/" + insert.text("name"), path,
                        "a row's folder and name are its path's");
                assertEquals("NULL", insert.values().get(insert.columns().indexOf("expires")),
                        "an attachment never expires: " + path);
            }
        }
        assertFalse(rows.isEmpty(), "the sample's messages carry attachments");
        assertEquals(named, rows, "the RepoFile rows are exactly the files the Attachment rows name");
    }

    @Test
    void theFolderHoldsNothingElse() throws IOException {
        Set<String> top;
        try (Stream<Path> entries = Files.list(SAMPLE)) {
            top = entries.map(p -> p.getFileName().toString()).collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(new TreeSet<>(Set.of("README.md", "archive", "messages", "niord-sample.sql")), top);
        assertEquals(List.of(SAMPLE.resolve("messages/messages.json")), files(SAMPLE.resolve("messages")));
        for (Path file : files(ARCHIVE)) {
            String name = ARCHIVE.relativize(file).toString().replace('\\', '/');
            assertTrue(name.matches("[a-z-]+\\.json|templates/[a-z]+/[a-z-]+\\.(ftl|js)"),
                    "archive/ holds the batch set's JSON files and templates/ only: " + name);
        }
    }

    static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    static List<Path> files(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            return paths.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /** The folder zipped as the JDK's jar tool zips it: a directory entry for each folder */
    static byte[] zipFolder(Path folder) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out); Stream<Path> paths = Files.walk(folder)) {
            for (Path p : paths.sorted().toList()) {
                String name = folder.relativize(p).toString().replace('\\', '/');
                if (name.isEmpty()) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    zip.putNextEntry(new ZipEntry(name + "/"));
                } else {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(Files.readAllBytes(p));
                }
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
