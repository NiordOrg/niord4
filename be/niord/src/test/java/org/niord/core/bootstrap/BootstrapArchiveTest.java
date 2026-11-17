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
package org.niord.core.bootstrap;

import org.junit.jupiter.api.Test;
import org.niord.core.batch.vo.BatchSetVo;
import org.niord.core.script.ScriptResource;
import org.niord.core.script.vo.ScriptResourceVo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive rules of the bootstrap (docs/code-data-config-plan.md §6.5, §7.2): {@link BootstrapArchive} reads the
 * base product's bootstrap (the sample's archive is read by SampleFolderTest), and refuses, before anything is
 * written, every archive that breaks a rule, naming the rule. Also the runner's job order and its conversion of the
 * templates folder.
 */
class BootstrapArchiveTest {

    /** The base product's bootstrap; the tests run in the module's folder */
    static final Path BOOTSTRAP = Path.of("bootstrap");

    static final Path FIXTURES = Path.of("src/test/resources/bootstrap-fixtures");

    // ------------------------------------------------------------------------------------------- //
    // Zips, as the JDK's jar tool writes them
    // ------------------------------------------------------------------------------------------- //

    /** Zips the entries in order; a name ending in '/' is a directory entry */
    static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                if (!e.getKey().endsWith("/")) {
                    zip.write(e.getValue());
                }
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Zips a folder as {@code jar --create --no-manifest -C <folder> .} does: a directory entry for each sub-folder
     * before its files
     */
    static byte[] zipFolder(Path folder) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path p : paths.sorted().toList()) {
                String name = folder.relativize(p).toString().replace('\\', '/');
                if (name.isEmpty()) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    entries.put(name + "/", new byte[0]);
                } else {
                    entries.put(name, Files.readAllBytes(p));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return zip(entries);
    }

    static BootstrapArchive read(String name, byte[] zip) {
        try {
            return BootstrapArchive.read(name, new ByteArrayInputStream(zip));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A valid archive's entries: one job and its file */
    private static Map<String, byte[]> validEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("batch-set.json", utf8("[{\"jobName\": \"domain-import\", \"fileName\": \"domains.json\"}]"));
        entries.put("domains.json", utf8("[]"));
        return entries;
    }

    private static void assertRefused(Map<String, byte[]> entries, String rule) {
        assertRefused(zip(entries), rule);
    }

    private static void assertRefused(byte[] zip, String rule) {
        BootstrapRefusedException e = assertThrows(BootstrapRefusedException.class, () -> read("test.zip", zip));
        assertEquals(BootstrapRefusedException.ARCHIVE, e.getStatus());
        assertTrue(e.getMessage().contains("'test.zip'") && e.getMessage().contains(rule),
                "the refusal names the archive and the rule (" + rule + "): " + e.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // Accepted
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theBaseProductsBootstrapZippedByTheJarToolIsAccepted() {
        BootstrapArchive archive = read("niord-bootstrap.zip", zipFolder(BOOTSTRAP));

        assertEquals(List.of("script-resource-import", "domain-import", "dictionary-import", "category-import",
                        "param-type-import", "report-import", "promulgation-type-import"),
                archive.items().stream().map(BatchSetVo::getJobName).toList());
        assertEquals(80, archive.templates().size(), "the folder's directory entries are skipped, its 80 files read");
        assertTrue(archive.hash().matches("[0-9a-f]{64}"));
    }

    /**
     * The release folders and the root texts.json travel with the published archive for a sysadmin's import: read,
     * counted and hashed, never applied. The directory entries are those the Maven assembly and the jar tool write.
     */
    @Test
    void theReleaseFoldersAndTheTextsFileAreAcceptedAndNeverApplied() {
        Map<String, byte[]> entries = validEntries();
        entries.put("texts.json", utf8("[]"));
        entries.put("releases/", new byte[0]);
        entries.put("releases/2026-10-21-templates-from-database/", new byte[0]);
        entries.put("releases/2026-10-21-templates-from-database/README.md", utf8("Data, not code."));
        entries.put("releases/2026-10-21-templates-from-database/script-resources.json",
                utf8("[{\"type\": \"FM\", \"path\": \"templates/x.ftl\", \"content\": \"x\"}]"));
        entries.put("releases/2026-10-21-templates-from-database/texts.json", utf8("[]"));
        entries.put("releases/2026-10-21-templates-from-database/missing-paths.sql", utf8("SELECT 1;"));

        BootstrapArchive archive = read("test.zip", zip(entries));

        assertEquals(List.of("domain-import"), archive.items().stream().map(BatchSetVo::getJobName).toList());
        assertTrue(archive.templates().isEmpty(), "a release file's templates are not the bootstrap's");
        assertNotEquals(read("test.zip", zip(validEntries())).hash(), archive.hash(), "hashed with the archive");
    }

    @Test
    void theBaseProductsBootstrapCarriesItsReleaseFoldersAndTextsUnapplied() {
        BootstrapArchive archive = read("niord-bootstrap.zip", zipFolder(BOOTSTRAP));

        assertTrue(archive.file("texts.json") != null, "the base product texts travel with the archive");
        assertTrue(archive.items().stream().noneMatch(i -> "texts.json".equals(i.getFileName())),
                "the bootstrap does not apply the texts: start-up adds them");
        assertTrue(archive.templates().keySet().stream().allMatch(p -> p.startsWith("templates/")));
    }

    @Test
    void theMinimalArchiveIsAccepted() {
        BootstrapArchive archive = read("minimal.zip", zipFolder(FIXTURES.resolve("minimal")));

        assertEquals(List.of("templates/it/minimal.ftl"), List.copyOf(archive.templates().keySet()));
        assertEquals("[{\"domainId\": \"it-minimal\", \"name\": \"Minimal\"}]\n",
                new String(archive.file("domains.json"), StandardCharsets.UTF_8));
    }

    /**
     * A domain file with content after its root gives its domains as the domain import reads them (Jackson 2's
     * defaults), so the check of a restated domain sees them
     */
    @Test
    void aDomainFileWithContentAfterTheRootGivesItsDomains() {
        Map<String, byte[]> entries = validEntries();
        entries.put("domains.json", utf8("[{\"domainId\": \"d1\"}] }"));

        assertEquals(List.of("d1"), List.copyOf(read("test.zip", zip(entries)).domainIds()));
    }

    /**
     * A domain restated with its domainId and a JSON boolean active only, in every object that gives it, is one the run
     * accepts from a later archive; a name besides, no flag, a flag that is not a boolean, or a second object with
     * more, is not. A file that is not JSON gives none.
     */
    @Test
    void theActiveOnlyDomainIdsAreThoseGivenWithABooleanActiveAlone() {
        Map<String, byte[]> entries = validEntries();
        entries.put("domains.json", utf8("[{\"domainId\": \"a\", \"active\": false}, "
                + "{\"domainId\": \"b\", \"active\": true}, {\"domainId\": \"c\", \"name\": \"C\"}, "
                + "{\"domainId\": \"d\"}, {\"domainId\": \"e\", \"active\": \"no\"}, "
                + "{\"domainId\": \"e\", \"name\": \"E\"}, {\"domainId\": \"f\", \"active\": false}, "
                + "{\"domainId\": \"f\", \"name\": \"F\"}]"));
        BootstrapArchive archive = read("test.zip", zip(entries));

        assertEquals(List.of("a", "b"), List.copyOf(archive.activeOnlyDomainIds()));
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), List.copyOf(archive.domainIds()));

        entries.put("domains.json", utf8("This file is not JSON"));
        assertEquals(List.of(), List.copyOf(read("test.zip", zip(entries)).activeOnlyDomainIds()));
    }

    @Test
    void theRunHashFollowsTheArchivesAndTheirOrder() {
        BootstrapArchive a = read("a.zip", zip(validEntries()));
        BootstrapArchive b = read("b.zip", zipFolder(FIXTURES.resolve("minimal")));

        assertEquals(BootstrapArchive.runHash(List.of(a, b)), BootstrapArchive.runHash(List.of(a, b)));
        assertNotEquals(BootstrapArchive.runHash(List.of(a, b)), BootstrapArchive.runHash(List.of(b, a)));
        assertNotEquals(BootstrapArchive.runHash(List.of(a)), BootstrapArchive.runHash(List.of(a, b)));
    }

    // ------------------------------------------------------------------------------------------- //
    // Refused: the layout
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anEntryOutsideTheLayoutIsRefused() {
        for (String name : List.of("../domains.json", "/domains.json", "C:/domains.json", "sub/domains.json",
                "templates/../x.ftl", "templates\\x.ftl", "templates/x.txt", "templates/.hidden.ftl", "settings.yml",
                "x.sh", "META-INF/MANIFEST.MF")) {
            Map<String, byte[]> entries = validEntries();
            entries.put(name, utf8("x"));
            assertRefused(entries, "outside the layout");
        }
    }

    @Test
    void aFolderOutsideTemplatesAndReleasesIsRefused() {
        for (String folder : List.of("META-INF/", "other/", "releases/a/b/", "releases/../")) {
            Map<String, byte[]> entries = validEntries();
            entries.put(folder, new byte[0]);
            assertRefused(entries, "outside templates/ and releases/");
        }
    }

    @Test
    void aReleaseFileOutsideItsLayoutIsRefused() {
        for (String name : List.of("releases/x.json", "releases/a/b/x.json", "releases/a/x.txt", "releases/a/x.ftl",
                "releases/a/x.sh", "releases/../x.json", "releases/.a/x.json")) {
            Map<String, byte[]> entries = validEntries();
            entries.put(name, utf8("x"));
            assertRefused(entries, "outside the layout");
        }
    }

    @Test
    void anEntryTwiceIsRefused() {
        // ZipOutputStream refuses a duplicate name, so the second entry is written under a name of the same
        // length and renamed in the bytes; the entries are read in order, and a name covers no checksum
        Map<String, byte[]> entries = validEntries();
        entries.put("domainsXjson", utf8("[{\"domainId\": \"other\"}]"));
        byte[] zip = zip(entries);
        byte[] from = utf8("domainsXjson");
        byte[] to = utf8("domains.json");
        for (int i = 0; i + from.length <= zip.length; i++) {
            if (java.util.Arrays.equals(zip, i, i + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, zip, i, to.length);
            }
        }
        assertRefused(zip, "'domains.json' twice");
    }

    @Test
    void anArchiveThatIsNoZipIsRefused() {
        assertRefused(utf8("not a zip archive"), "empty or not a zip archive");
        assertRefused(new byte[0], "empty or not a zip archive");
    }

    @Test
    void tooManyEntriesAreRefused() {
        Map<String, byte[]> entries = validEntries();
        for (int i = 0; i < BootstrapArchive.MAX_ENTRIES; i++) {
            entries.put("templates/t" + i + ".ftl", new byte[0]);
        }
        assertRefused(entries, "more than " + BootstrapArchive.MAX_ENTRIES + " entries");
    }

    @Test
    void anEntryLargerThanTheLimitIsRefused() {
        Map<String, byte[]> entries = validEntries();
        entries.put("templates/big.ftl", new byte[BootstrapArchive.MAX_ENTRY_BYTES + 1]);
        assertRefused(entries, "larger than " + BootstrapArchive.MAX_ENTRY_BYTES + " bytes");
    }

    @Test
    void anArchiveThatUnpacksToMoreThanTheTotalIsRefused() {
        // Zeros compress to almost nothing: the count is of the bytes read, not of the archive's size
        Map<String, byte[]> entries = validEntries();
        byte[] fifteen = new byte[15 * 1024 * 1024];
        for (int i = 0; i < 5; i++) {
            entries.put("templates/part" + i + ".ftl", fifteen);
        }
        assertRefused(entries, "unpacks to more than " + BootstrapArchive.MAX_TOTAL_BYTES + " bytes");
    }

    // ------------------------------------------------------------------------------------------- //
    // Refused: a template or script the script check refuses (ScriptResourceCheck)
    // ------------------------------------------------------------------------------------------- //

    /** A valid archive with one template or script added under templates/ */
    static Map<String, byte[]> withTemplate(String path, String content) {
        Map<String, byte[]> entries = validEntries();
        entries.put(path, utf8(content));
        return entries;
    }

    @Test
    void aScriptTheScriptCheckRefusesIsRefused() {
        assertRefused(withTemplate("templates/tmpl/bad.js", "var r = Java.type('java.lang.Runtime');"),
                "has the template 'templates/tmpl/bad.js', which the script check refuses: uses Java.type");
    }

    @Test
    void aTemplateThatInstantiatesANonDirectiveIsRefused() {
        assertRefused(withTemplate("templates/tmpl/bad.ftl",
                        "<#assign ex = \"freemarker.template.utility.Execute\"?new()>${ex(\"echo hi\")}"),
                "has the template 'templates/tmpl/bad.ftl', which the script check refuses: "
                        + "?new of freemarker.template.utility.Execute, which is not a directive");
    }

    // ------------------------------------------------------------------------------------------- //
    // Refused: batch-set.json
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anArchiveWithoutABatchSetIsRefused() {
        Map<String, byte[]> entries = validEntries();
        entries.remove("batch-set.json");
        assertRefused(entries, "has no batch-set.json");
    }

    @Test
    void aBatchSetThatIsNoListOfJobsIsRefused() {
        Map<String, byte[]> entries = validEntries();
        entries.put("batch-set.json", utf8("{\"jobName\": \"domain-import\"}"));
        assertRefused(entries, "not a list of jobs");
        entries.put("batch-set.json", utf8("[]"));
        assertRefused(entries, "names no job");
    }

    @Test
    void aJobTheBootstrapDoesNotRunIsRefused() {
        for (String job : List.of("settings-import", "script-executor", "message-import", "msg-archive-import",
                "publication-series-import", "no-such-job")) {
            Map<String, byte[]> entries = validEntries();
            entries.put("batch-set.json", utf8("[{\"jobName\": \"" + job + "\", \"fileName\": \"domains.json\"}]"));
            assertRefused(entries, "names the job '" + job + "'");
        }
    }

    @Test
    void aDataFileTheArchiveLacksIsRefused() {
        Map<String, byte[]> entries = validEntries();
        entries.remove("domains.json");
        assertRefused(entries, "names the file 'domains.json'");

        entries = validEntries();
        entries.put("batch-set.json", utf8("[{\"jobName\": \"domain-import\", \"fileName\": \"templates/x.ftl\"}]"));
        assertRefused(entries, "a job reads a root-level .json file");
    }

    /** The add and overwrite properties are a sysadmin's ticks after a preview; the bootstrap overwrites nothing */
    @Test
    void anItemCarryingAnImportTickIsRefused() {
        for (String tick : List.of("overwrite", "add")) {
            Map<String, byte[]> entries = validEntries();
            entries.put("batch-set.json", utf8("[{\"jobName\": \"domain-import\", \"fileName\": \"domains.json\", "
                    + "\"properties\": {\"" + tick + "\": \"[\\\"templates/x.ftl\\\"]\"}}]"));
            assertRefused(entries, "gives the job 'domain-import' (domains.json) the property '" + tick + "'");
        }

        Map<String, byte[]> entries = validEntries();
        entries.put("batch-set.json", utf8("[{\"jobName\": \"domain-import\", \"fileName\": \"domains.json\", "
                + "\"properties\": {\"other\": \"x\"}}]"));
        assertEquals(1, read("test.zip", zip(entries)).items().size(), "another property is the job's to read");
    }

    /**
     * The runner's token for the same-run rule ({@link BootstrapRun}) is the runner's alone: an archive that gives a
     * job the property is refused, as read before the marker is claimed
     */
    @Test
    void anItemCarryingTheRunnersTokenIsRefused() {
        Map<String, byte[]> entries = validEntries();
        entries.put("batch-set.json", utf8("[{\"jobName\": \"domain-import\", \"fileName\": \"domains.json\", "
                + "\"properties\": {\"" + BootstrapRun.PROPERTY + "\": \"a-run/0\"}}]"));
        assertRefused(entries, "gives the job 'domain-import' (domains.json) the property 'bootstrapRun'");
    }

    @Test
    void theScriptResourceJobReadsTheTemplatesFolderOnly() {
        Map<String, byte[]> entries = validEntries();
        entries.put("batch-set.json",
                utf8("[{\"jobName\": \"script-resource-import\", \"fileName\": \"domains.json\"}]"));
        assertRefused(entries, "it reads the folder 'templates'");

        entries.put("batch-set.json",
                utf8("[{\"jobName\": \"script-resource-import\", \"fileName\": \"templates\"}]"));
        assertRefused(entries, "has no template in it");
    }

    // ------------------------------------------------------------------------------------------- //
    // The runner's order and its templates conversion
    // ------------------------------------------------------------------------------------------- //

    private static BatchSetVo item(String job) {
        BatchSetVo item = new BatchSetVo();
        item.setJobName(job);
        item.setFileName(job + ".json");
        return item;
    }

    @Test
    void theRunnerRunsTheOrderedJobsFirstAndTheRestAsListed() {
        // A 12-job archive's order, as its batch-set.json lists it (the retired demo archive's)
        List<String> listed = List.of("domain-import", "dictionary-import", "chart-import", "area-import",
                "publication-category-import", "source-import", "param-type-import", "mailing-list-import",
                "category-import", "publication-import", "report-import", "promulgation-type-import");

        List<String> ordered = BootstrapRunner.ordered(listed.stream().map(BootstrapArchiveTest::item).toList())
                .stream().map(BatchSetVo::getJobName).toList();

        assertEquals(List.of("domain-import", "dictionary-import", "category-import", "param-type-import",
                "mailing-list-import", "report-import", "promulgation-type-import", "chart-import", "area-import",
                "publication-category-import", "source-import", "publication-import"), ordered);
    }

    @Test
    void theRunnerTurnsTheTemplatesFolderIntoTheImportsResources() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("batch-set.json", utf8("[{\"jobName\": \"script-resource-import\", \"fileName\": \"templates\"}]"));
        entries.put("templates/", new byte[0]);
        entries.put("templates/tmpl/", new byte[0]);
        entries.put("templates/tmpl/b.js", utf8("var b;"));
        entries.put("templates/tmpl/a.ftl", utf8("<#-- æøå -->"));

        List<ScriptResourceVo> resources = BootstrapRunner.scriptResources(read("t.zip", zip(entries)));

        assertEquals(List.of("templates/tmpl/a.ftl", "templates/tmpl/b.js"),
                resources.stream().map(ScriptResourceVo::getPath).toList());
        assertEquals(ScriptResource.Type.FM, resources.get(0).getType());
        assertEquals(ScriptResource.Type.JS, resources.get(1).getType());
        assertEquals("<#-- æøå -->", resources.get(0).getContent(), "read as UTF-8");
    }
}
