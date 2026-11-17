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
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.dictionary.vo.ExportedDictionaryVo;
import org.niord.core.script.vo.ScriptResourceVo;
import org.niord.core.util.JsonUtils;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The release files are current and well-formed (docs/code-data-config-plan.md §4.6, §6.4, §6.6):
 * {@code bootstrap/texts.json} and {@code META-INF/niord/expected-template-paths.txt} are what
 * {@link ReleaseFilesGenerator} makes from the tree, and every release folder of {@code bootstrap/releases/} is
 * named {@code <yyyy-mm-dd>-<topic>}, has its README, and its import files read in the formats the imports read.
 *
 * <p>With {@code -Dniord.releaseFiles.write=true} it writes the current files instead (see {@link #REGENERATE}), and
 * with {@code -Dniord.release=<yyyy-mm-dd>-<topic>} it first creates that release folder's generated files. A unit
 * test, so Backend CI's unit job runs it.
 */
class ReleaseFilesCurrentTest {

    /** The command that regenerates the current files, from be/ */
    static final String REGENERATE = "./mvnw -pl niord test -Dtest=ReleaseFilesCurrentTest"
            + " -Dsurefire.failIfNoSpecifiedTests=false -D" + ReleaseFilesGenerator.WRITE_PROPERTY + "=true"
            + " [-D" + ReleaseFilesGenerator.RELEASE_PROPERTY + "=<yyyy-mm-dd>-<topic>, for a new release folder]";

    /** The first release folder, with C4.2a */
    static final String FIRST_RELEASE = "2026-10-21-templates-from-database";

    /** The five placeholders, carried as the one-line comment since C4.2a (§6.4) */
    static final List<String> PLACEHOLDERS = List.of(
            "templates/mailinglist/safetynet-intro.ftl",
            "templates/messages/fa-list-pdf-intro.ftl",
            "templates/messages/nm-accumulated-report-pdf-intro.ftl",
            "templates/messages/nm-almanac-report-pdf-intro.ftl",
            "templates/messages/nm-report-pdf-intro.ftl");

    /** The first words of every README in the bootstrap folder (§7.1) */
    static final String README_START = "Data, not code. Loaded only into a fresh deployment (bootstrap), only by a "
            + "sysadmin's import (releases), or";

    private static final Pattern WRITING_SQL = Pattern.compile(
            "(?i)\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|copy|call|do)\\b");

    @Test
    void theCurrentFilesAreTheGeneratedOnes() throws IOException {
        String release = System.getProperty(ReleaseFilesGenerator.RELEASE_PROPERTY);
        if (release != null && !release.isBlank()) {
            ReleaseFilesGenerator.createRelease(ReleaseFilesGenerator.RELEASES, release.strip());
        }
        if (Boolean.getBoolean(ReleaseFilesGenerator.WRITE_PROPERTY)) {
            ReleaseFilesGenerator.writeCurrentFiles();
        }

        List<String> stale = new ArrayList<>();
        for (Map.Entry<Path, String> file : ReleaseFilesGenerator.currentFiles().entrySet()) {
            if (!Files.isRegularFile(file.getKey())) {
                stale.add(file.getKey() + " is missing");
            } else if (!ReleaseFilesGenerator.lf(Files.readString(file.getKey(), StandardCharsets.UTF_8))
                    .equals(file.getValue())) {
                stale.add(file.getKey() + " is not what the tree gives");
            }
        }
        if (!stale.isEmpty()) {
            fail(String.join("; ", stale) + ". Regenerate: " + REGENERATE);
        }
    }

    @Test
    void theBootstrapsTextsFileHoldsTheFiveDictionariesInEnglish() throws IOException {
        List<ExportedDictionaryVo> dictionaries = readTexts(ReleaseFilesGenerator.TEXTS);

        assertEquals(List.of(DictionaryService.DEFAULT_BUNDLES),
                dictionaries.stream().map(ExportedDictionaryVo::getName).toList());
        for (ExportedDictionaryVo dictionary : dictionaries) {
            assertFalse(dictionary.getEntries().isEmpty(), dictionary.getName());
            dictionary.getEntries().forEach(e -> {
                assertEquals(1, e.getDescs().size(), e.getKey());
                assertEquals("en", e.getDescs().get(0).getLang(), e.getKey());
            });
        }
    }

    @Test
    void everyReleaseFolderIsWellFormed() throws IOException {
        List<Path> folders = ReleaseFilesGenerator.releaseFolders(ReleaseFilesGenerator.RELEASES);
        assertFalse(folders.isEmpty(), "the first release folder exists");

        for (Path folder : folders) {
            String name = folder.getFileName().toString();
            assertTrue(ReleaseFilesGenerator.RELEASE_NAME.matcher(name).matches(),
                    name + " is named <yyyy-mm-dd>-<topic>");

            Path readme = folder.resolve(ReleaseFilesGenerator.README_FILE);
            assertTrue(Files.isRegularFile(readme), name + " has its README.md");
            assertTrue(Files.readString(readme, StandardCharsets.UTF_8).startsWith(README_START),
                    name + "'s README starts with the bootstrap folders' sentence");

            Path scripts = folder.resolve(ReleaseFilesGenerator.SCRIPT_RESOURCES_FILE);
            if (Files.exists(scripts)) {
                List<ScriptResourceVo> resources = ReleaseFilesGenerator.readScriptResources(scripts);
                assertFalse(resources.isEmpty(), scripts.toString());
                resources.forEach(r -> {
                    assertEquals(ScriptResource.path2type(r.getPath()), r.getType(), r.getPath());
                    assertTrue(r.getPath().startsWith("templates/"), r.getPath());
                    assertEquals(null, r.getId(), "an import file carries no id: " + r.getPath());
                });
            }
            Path texts = folder.resolve(ReleaseFilesGenerator.TEXTS_FILE);
            if (Files.exists(texts)) {
                assertFalse(readTexts(texts).isEmpty(), texts.toString());
            }
            Path sql = folder.resolve(ReleaseFilesGenerator.MISSING_PATHS_FILE);
            if (Files.exists(sql)) {
                assertReadOnlyQuery(sql);
            }
        }
    }

    /** The first release carries all 80 templates and scripts as they were in the jar, none blank */
    @Test
    void theFirstReleaseCarriesTheEightyFilesWithThePlaceholdersFilledIn() {
        Path folder = ReleaseFilesGenerator.RELEASES.resolve(FIRST_RELEASE);
        List<ScriptResourceVo> resources = ReleaseFilesGenerator.readScriptResources(
                folder.resolve(ReleaseFilesGenerator.SCRIPT_RESOURCES_FILE));

        assertEquals(80, resources.size());
        resources.forEach(r -> assertFalse(r.getContent() == null || r.getContent().isBlank(), r.getPath()));
        for (String placeholder : PLACEHOLDERS) {
            ScriptResourceVo resource = resources.stream().filter(r -> r.getPath().equals(placeholder)).findFirst()
                    .orElseThrow(() -> new AssertionError(placeholder + " is in the release file"));
            assertEquals(ReleaseFilesGenerator.PLACEHOLDER, resource.getContent().strip(), placeholder);
        }
        assertTrue(Files.isRegularFile(folder.resolve(ReleaseFilesGenerator.TEXTS_FILE)));
        assertTrue(Files.isRegularFile(folder.resolve(ReleaseFilesGenerator.MISSING_PATHS_FILE)));
    }

    /** The bootstrap's own placeholders are the one-line comment too, so a fresh deployment renders them */
    @Test
    void theBootstrapsPlaceholdersAreTheOneLineComment() throws IOException {
        for (String placeholder : PLACEHOLDERS) {
            assertEquals(ReleaseFilesGenerator.PLACEHOLDER,
                    Files.readString(ReleaseFilesGenerator.BOOTSTRAP.resolve(placeholder), StandardCharsets.UTF_8)
                            .strip(), placeholder);
        }
    }

    @Test
    void aReleaseFolderNameIsADateAndATopic() {
        assertTrue(ReleaseFilesGenerator.RELEASE_NAME.matcher(FIRST_RELEASE).matches());
        assertFalse(ReleaseFilesGenerator.RELEASE_NAME.matcher("4.0.80").matches());
        assertFalse(ReleaseFilesGenerator.RELEASE_NAME.matcher("2026-10-21").matches());
        assertFalse(ReleaseFilesGenerator.RELEASE_NAME.matcher("2026-10-21-Templates").matches());
    }

    private static List<ExportedDictionaryVo> readTexts(Path file) throws IOException {
        return JsonUtils.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                new TypeReference<List<ExportedDictionaryVo>>() {});
    }

    /** One statement that only reads: a WITH … SELECT, ended by its one ';', with no writing keyword outside comments */
    private static void assertReadOnlyQuery(Path file) throws IOException {
        String sql = Files.readString(file, StandardCharsets.UTF_8);
        String code = sql.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + b + "\n");
        String unquoted = code.replaceAll("'(?:[^']|'')*'", "''");
        assertTrue(unquoted.strip().startsWith("WITH RECURSIVE"), file + " is one query");
        assertEquals(1, unquoted.chars().filter(c -> c == ';').count(), file + " holds one statement");
        assertTrue(unquoted.strip().endsWith(";"), file.toString());
        assertFalse(WRITING_SQL.matcher(unquoted).find(), file + " only reads");
    }
}
