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
import org.niord.core.category.CategoryService;
import org.niord.core.category.StandardParamType;
import org.niord.core.report.FmReportService;
import org.niord.core.script.RequiredTemplates;
import org.niord.core.util.JsonUtils;
import tools.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check of the base product's bootstrap folder, {@code be/niord/bootstrap/} (docs/code-data-config-plan.md
 * §7.1): its README says the folder is data and names the load-bearing English name "Firing Exercises"; every job
 * of {@code batch-set.json} is one the runner accepts and has its data file; it holds the 80 templates and scripts,
 * among them the ones the code names ({@link RequiredTemplates}) and the five placeholders as non-blank one-line
 * comments, no standard parameter type, no description in a language other than English
 * ({@link #theDataIsInEnglishOnly}), and no settings or messages; its 45 categories name no domain or message, and
 * only scripts and parameter types the folder ships; {@code releases/} holds only folders named
 * {@code <yyyy-mm-dd>-<topic>}, each with its README. Backend CI re-includes the folder after its Markdown
 * exclusion, so a change to the README alone, or its deletion, runs this test.
 */
class BootstrapFolderTest {

    private static final Path BOOTSTRAP = BootstrapArchiveTest.BOOTSTRAP;

    private static String read(String file) throws IOException {
        return Files.readString(BOOTSTRAP.resolve(file), StandardCharsets.UTF_8);
    }

    private static List<Map<String, Object>> readList(String file) throws IOException {
        return JsonUtils.fromJson(read(file), new TypeReference<List<Map<String, Object>>>() {});
    }

    @Test
    void theReadmeSaysTheFolderIsDataAndNamesTheLoadBearingCategory() throws IOException {
        assertTrue(Files.isRegularFile(BOOTSTRAP.resolve("README.md")), "bootstrap/README.md must exist");
        String readme = read("README.md");
        assertTrue(readme.startsWith("Data, not code. Loaded only into a fresh deployment (bootstrap), only by a "
                        + "sysadmin's import (releases), or"),
                "the README's first words say what the files are and are not");
        assertTrue(readme.contains("A running deployment's data may differ"));
        assertTrue(readme.contains("\"" + CategoryService.FIRING_EXERCISES_CATEGORY_NAME + "\""),
                "the README names the English name the firing-schedule generator finds the category by");
    }

    @Test
    void everyJobIsOneTheRunnerAcceptsAndHasItsDataFile() throws IOException {
        List<BatchSetVo> items = JsonUtils.fromJson(read("batch-set.json"), new TypeReference<List<BatchSetVo>>() {});
        assertFalse(items.isEmpty());
        for (BatchSetVo item : items) {
            assertTrue(BootstrapRunner.ACCEPTED_JOBS.contains(item.getJobName()),
                    "the runner does not accept " + item.getJobName());
            Path file = BOOTSTRAP.resolve(item.getFileName());
            if (BootstrapRunner.SCRIPT_RESOURCE_JOB.equals(item.getJobName())) {
                assertEquals(BootstrapArchive.TEMPLATES, item.getFileName());
                assertTrue(Files.isDirectory(file));
            } else {
                assertTrue(Files.isRegularFile(file), item.getJobName() + " names a missing file " + file);
            }
        }
        // The folder, zipped, passes the archive rules
        BootstrapArchiveTest.read("niord-bootstrap.zip", BootstrapArchiveTest.zipFolder(BOOTSTRAP));
    }

    @Test
    void itHoldsTheEightyTemplatesAndScripts() throws IOException {
        List<String> files;
        try (Stream<Path> paths = Files.walk(BOOTSTRAP.resolve(BootstrapArchive.TEMPLATES))) {
            files = paths.filter(Files::isRegularFile)
                    .map(p -> BOOTSTRAP.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
        assertEquals(80, files.size(), "the base product's 80 templates and scripts: " + files.size());
        assertTrue(files.stream().allMatch(f -> f.endsWith(".ftl") || f.endsWith(".js")), files.toString());
        // The ones the code names among them (§6.2)
        for (String named : RequiredTemplates.PATHS) {
            assertTrue(files.contains(named), named);
        }
        // No template is blank: a blank row fails the include that names it (RENDER1, closed in C4.2a)
        for (String file : files) {
            assertFalse(read(file).isBlank(), file + " is blank");
        }
        for (String placeholder : List.of("templates/messages/nm-report-pdf-intro.ftl",
                "templates/messages/nm-almanac-report-pdf-intro.ftl",
                "templates/messages/nm-accumulated-report-pdf-intro.ftl", "templates/messages/fa-list-pdf-intro.ftl",
                "templates/mailinglist/safetynet-intro.ftl")) {
            assertEquals("<#-- introduction: this deployment's text goes here -->", read(placeholder).strip(),
                    placeholder);
        }
    }

    @Test
    void itsReleaseFoldersAreNamedByDateAndTopicAndHaveTheirReadme() throws IOException {
        Path releases = BOOTSTRAP.resolve(BootstrapArchive.RELEASES);
        assertTrue(Files.isDirectory(releases), "bootstrap/releases/ holds the release import files");
        try (Stream<Path> entries = Files.list(releases)) {
            for (Path folder : entries.toList()) {
                String name = folder.getFileName().toString();
                assertTrue(Files.isDirectory(folder), "releases/ holds folders only: " + name);
                assertTrue(name.matches("\\d{4}-\\d{2}-\\d{2}-[a-z0-9]+(-[a-z0-9]+)*"),
                        "a release folder is named <yyyy-mm-dd>-<topic>: " + name);
                assertTrue(Files.isRegularFile(folder.resolve("README.md")), name + " has its README.md");
                try (Stream<Path> files = Files.list(folder)) {
                    files.forEach(f -> assertTrue(Files.isRegularFile(f)
                            && f.getFileName().toString().matches("[A-Za-z0-9_][A-Za-z0-9._-]*\\.(json|md|sql)"),
                            "a release folder holds .json, .md and .sql files only: " + f));
                }
            }
        }
    }

    @Test
    void itHoldsNoStandardParameterType() throws IOException {
        List<Map<String, Object>> types = readList("parameter-types.json");
        assertEquals(32, types.size());
        assertTrue(types.stream().noneMatch(t -> String.valueOf(t.get("name")).startsWith("dgps")),
                "the DGPS types are Danish data, in DK's bootstrap");
        List<String> standard = List.of(StandardParamType.STANDARD_PARAM_TYPES);
        for (Map<String, Object> type : types) {
            assertFalse("STANDARD".equals(type.get("type")) || standard.contains((String) type.get("name")),
                    "the standard parameter types are code, not bootstrap data: " + type.get("name"));
        }
    }

    /**
     * Every script a category names is a file of this folder, and every type its template parameters name is one of
     * {@code parameter-types.json} or a standard one: {@code Radio navigation}/{@code DGPS} names neither DK's
     * {@code tmpl/dgps.ftl} and {@code tmpl/dgps.js} nor the {@code dgps} type, which are Danish data
     */
    @Test
    void everyCategoryNamesOnlyWhatTheBootstrapShips() throws IOException {
        List<String> types = new ArrayList<>(List.of(StandardParamType.STANDARD_PARAM_TYPES));
        readList("parameter-types.json").forEach(t -> types.add((String) t.get("name")));
        List<String> missing = new ArrayList<>();
        collectMissing(readList("categories.json"), types, missing);
        assertTrue(missing.isEmpty(), "categories naming what the bootstrap does not ship: " + missing);
    }

    @SuppressWarnings("unchecked")
    private static void collectMissing(List<Map<String, Object>> categories, List<String> types,
            List<String> missing) {
        for (Map<String, Object> c : categories) {
            List<String> scripts = (List<String>) c.get("scriptResourcePaths");
            for (String path : scripts != null ? scripts : List.<String>of()) {
                if (!Files.isRegularFile(BOOTSTRAP.resolve(path))) {
                    missing.add(englishName(c) + ": script " + path);
                }
            }
            List<Map<String, Object>> params = (List<Map<String, Object>>) c.get("templateParams");
            for (Map<String, Object> param : params != null ? params : List.<Map<String, Object>>of()) {
                if (!types.contains((String) param.get("type"))) {
                    missing.add(englishName(c) + ": parameter type " + param.get("type"));
                }
            }
            List<Map<String, Object>> children = (List<Map<String, Object>>) c.get("children");
            if (children != null) {
                collectMissing(children, types, missing);
            }
        }
    }

    @Test
    void itHoldsNoSettingsAndNoMessages() throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> paths = Files.list(BOOTSTRAP)) {
            paths.filter(Files::isRegularFile).forEach(p -> files.add(p.getFileName().toString()));
        }
        assertEquals(List.of("README.md", "aton-dict.json", "batch-set.json", "categories.json", "domains.json",
                "parameter-types.json", "promulgation-types.json", "reports.json", "texts.json"),
                files.stream().sorted().toList());
    }

    @Test
    void theDataIsTheBaseProductsAndCountryFree() throws IOException {
        assertEquals(List.of("niord-client-master"),
                readList("domains.json").stream().map(d -> d.get("domainId")).toList());
        assertEquals(Boolean.TRUE, readList("domains.json").getFirst().get("active"),
                "the master domain is active: a base-only deployment's sysadmin reaches the sysadmin pages through it");

        assertEquals(FmReportService.BUILT_IN_REPORT_IDS,
                readList("reports.json").stream().map(r -> r.get("reportId")).toList());
        readList("reports.json").forEach(r -> assertEquals("/templates/messages/message-list-pdf.ftl",
                r.get("templatePath")));

        assertEquals(List.of("NAVTEX", "Twitter", "Audio"),
                readList("promulgation-types.json").stream().map(p -> p.get("name")).toList());
        assertTrue(readList("promulgation-types.json").stream().noneMatch(p -> p.containsKey("domains")));

        List<Map<String, Object>> categories = readList("categories.json");
        assertEquals(List.of("Light", "Light buoy", CategoryService.FIRING_EXERCISES_CATEGORY_NAME, "Buoy", "Beacon",
                        "Obstruction", "Drifting object", "Radio navigation"),
                categories.stream().map(BootstrapFolderTest::englishName).toList());
        assertEquals(45, count(categories),
                "the base product's 45 categories: the 13 the bootstrap had and the 32 that were the demo archive's");
        assertEquals(Map.of("Buoy", 5, "Beacon", 3, "Obstruction", 14, "Drifting object", 2, "Radio navigation", 3),
                childCounts(categories.subList(3, categories.size())), "the children of the five roots added");
        assertTrue(categories.stream().allMatch(BootstrapFolderTest::noDomainOrMessage),
                "no category names a domain or a (Danish) message");

        List<Map<String, Object>> dictionaries = readList("aton-dict.json");
        assertEquals(1, dictionaries.size());
        assertEquals("aton", dictionaries.get(0).get("name"));
        assertEquals(111, ((List<?>) dictionaries.get(0).get("entries")).size());
    }

    /**
     * The data is in English only (R1; docs/code-data-config-plan.md C6): no description of the dictionary, the
     * categories and their template parameters or the parameter types is in another language, and every promulgation
     * type, Audio among them, has the language {@code en}. A country's bootstrap adds its languages in the same run.
     */
    @Test
    void theDataIsInEnglishOnly() throws IOException {
        List<BatchSetVo> items = JsonUtils.fromJson(read("batch-set.json"), new TypeReference<List<BatchSetVo>>() {});
        List<String> found = new ArrayList<>();
        for (BatchSetVo item : items) {
            if (!BootstrapRunner.SCRIPT_RESOURCE_JOB.equals(item.getJobName())) {
                collectLanguages(JsonUtils.fromJson(read(item.getFileName()), new TypeReference<Object>() {}),
                        item.getFileName(), found);
            }
        }
        assertTrue(found.isEmpty(), "data in a language other than English: " + found);
    }

    /**
     * Collects every object with a {@code lang} (a description) or a {@code language} (a promulgation type) other than
     * {@code en}, by the file it is in
     */
    @SuppressWarnings("unchecked")
    private static void collectLanguages(Object node, String file, List<String> found) {
        if (node instanceof Map<?, ?> map) {
            Object lang = map.get("lang") != null ? map.get("lang") : map.get("language");
            if (lang != null && !"en".equals(lang)) {
                found.add(file + ": " + map);
            }
            ((Map<String, Object>) map).values().forEach(v -> collectLanguages(v, file, found));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> collectLanguages(v, file, found));
        }
    }

    @SuppressWarnings("unchecked")
    private static String englishName(Map<String, Object> category) {
        return ((List<Map<String, Object>>) category.get("descs")).stream()
                .filter(d -> "en".equals(d.get("lang")))
                .map(d -> (String) d.get("name"))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static int count(List<Map<String, Object>> categories) {
        int n = 0;
        for (Map<String, Object> c : categories) {
            List<Map<String, Object>> children = (List<Map<String, Object>>) c.get("children");
            n += 1 + (children != null ? count(children) : 0);
        }
        return n;
    }

    /** The number of direct children of each category, by its English name */
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> childCounts(List<Map<String, Object>> categories) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> c : categories) {
            List<Map<String, Object>> children = (List<Map<String, Object>>) c.get("children");
            counts.put(englishName(c), children != null ? children.size() : 0);
        }
        return counts;
    }

    @SuppressWarnings("unchecked")
    private static boolean noDomainOrMessage(Map<String, Object> category) {
        List<Map<String, Object>> children = (List<Map<String, Object>>) category.get("children");
        return !category.containsKey("domains") && !category.containsKey("messageId")
                && (children == null || children.stream().allMatch(BootstrapFolderTest::noDomainOrMessage));
    }
}
