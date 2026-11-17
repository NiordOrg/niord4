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
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.script.MissingPathReport;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts the resource-complete-core packaging contract: the generic Liquibase schema ships with
 * the niord-base library itself, so a consumer depending on core alone inherits the schema without any
 * niord-dk resources on the classpath. This test runs on the core module's own test classpath
 * (niord-dk is not a dependency of niord), so a hit can only come from core's own resources.
 * <p>
 * Resource presence is deliberately all this test proves. Liquibase <em>execution</em> is
 * covered elsewhere: {@code LiquibaseBootstrapITTest} (bare master on a fresh database) and the
 * seeded IT suite ({@code changelog-with-seed.yaml}).
 * <p>
 * The bootstrap is data, never in a jar (docs/code-data-config-plan.md §6.3, §7.1): no template, script or
 * bootstrap file is in the jar since C4.2a, which deleted copy-on-use and the temporary resource mapping. That and
 * the seed are checked on niord-base's own classes folder, which is what the jar packs; the jar keeps only the
 * names of the template paths the release files carry ({@code META-INF/niord/expected-template-paths.txt}).
 */
class CoreResourcePackagingTest {

    @Test
    void liquibaseMasterChangelogShipsWithCore() {
        assertNotNull(getClass().getResource("/db/changelog/db.changelog-master.yaml"),
                "db.changelog-master.yaml must be packaged in niord-base");
    }

    @Test
    void liquibaseChangesetsShipWithCore() {
        for (String changeset : new String[] {
                "V001__baseline_schema.sql", "V002__message_search_text.sql", "V003__secom.sql",
                "V004__publication_series.sql", "V005__retire_map_image_settings.sql",
                "V006__defaults_ledger.sql", "V007__bootstrap_state.sql",
                "V008__script_resource_history_source.sql"}) {
            assertNotNull(getClass().getResource("/db/changelog/changes/" + changeset),
                    changeset + " must be packaged in niord-base");
        }
    }

    /** The base product's defaults ship with core: its README and its English backend texts. */
    @Test
    void baseProductDefaultsShipWithCore() {
        assertNotNull(getClass().getResource("/defaults/base/README.md"),
                "defaults/base/README.md must be packaged in niord-base");
        for (String bundle : DictionaryService.DEFAULT_BUNDLES) {
            assertNotNull(getClass().getResource("/defaults/base/texts/en/" + bundle + ".properties"),
                    "defaults/base/texts/en/" + bundle + ".properties must be packaged in niord-base");
        }
        assertNotNull(getClass().getResource("/defaults/base/settings.json"),
                "defaults/base/settings.json must be packaged in niord-base");
        assertNotNull(getClass().getResource("/defaults/base/settings-catalog.json"),
                "defaults/base/settings-catalog.json must be packaged in niord-base: a country's build checks against it");
    }

    /**
     * niord-base ships no root-level {@code niord.json} since C3b: {@code defaults/base/settings.json} is the file
     * start-up reads, and a root-level one is read as a country's settings file. Checked on niord-base's own classes
     * folder: on this module's test class path the test tree's root-level {@code niord.json} would answer.
     */
    @Test
    void niordBaseShipsNoRootNiordJson() throws URISyntaxException {
        Path classes = Path.of(NiordApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "niord-base's classes are a folder: " + classes);
        assertFalse(Files.exists(classes.resolve("niord.json")), classes + " must hold no niord.json");
    }

    /**
     * A country's texts and the language packs are the country jar's ({@code defaults/country/}) and staged by its
     * build ({@code defaults/packs/}); niord-base ships neither, or the duplicate guard would fail every country.
     * Checked on niord-base's own classes folder, which is what the jar packs: niord-base's test tree holds a stand-in
     * da pack ({@code src/test/resources/defaults/packs/da/}) that its integration tests read and the test-jar
     * excludes ({@code defaults/**}), so on this module's test class path that pack would answer.
     */
    @Test
    void countryDefaultsAndPacksDoNotShipWithCore() throws URISyntaxException {
        Path classes = classes();
        assertFalse(Files.exists(classes.resolve("defaults/country")),
                "defaults/country/ belongs to a country jar");
        assertFalse(Files.exists(classes.resolve("defaults/packs")),
                "defaults/packs/ is staged by a country build, never shipped by niord-base");
    }

    /**
     * The bootstrap is data, not code: nothing of {@code bootstrap/} reaches the jar, and the seed and its changelog
     * are test resources since C4.1.
     */
    @Test
    void neitherTheBootstrapNorTheSeedShipsWithCore() throws URISyntaxException {
        Path classes = classes();
        for (String file : new String[] {"bootstrap", "batch-set.json", "domains.json", "categories.json",
                "reports.json", "parameter-types.json", "promulgation-types.json", "aton-dict.json", "README.md",
                "texts.json", "releases", "db/niord-base-data.sql", "db/changelog/changelog-with-seed.yaml"}) {
            assertFalse(Files.exists(classes.resolve(file)), classes + " must hold no " + file);
        }
    }

    /**
     * The sample ({@code be/niord/sample}) is loaded only into the loopback {@code niord_sample}, never from a class
     * path (docs/fe-country-split-plan.md P9's safety rules). The built jars and images are checked by
     * {@code be/k8s/scripts/check-no-sample.sh}.
     */
    @Test
    void theSampleDoesNotShipWithCore() throws URISyntaxException, IOException {
        Path classes = classes();
        for (String file : new String[] {"sample", "niord-sample.sql", "messages.json", "archive"}) {
            assertFalse(Files.exists(classes.resolve(file)), classes + " must hold no " + file);
        }
        // At any depth too: a pom resource with a targetPath lands below the root
        assertEquals(List.of(), sampleFiles(classes), classes + " must hold no part of the sample");
    }

    /** The walk finds the sample at any depth, as be/k8s/scripts/check-no-sample.sh's name rules do */
    @Test
    void theSampleIsFoundAtAnyDepth(@TempDir Path root) throws IOException {
        for (String file : new String[] {"org/niord/x/README.md", "x/messages.json", "y/data/messages.json"}) {
            Files.createDirectories(root.resolve(file).getParent());
            Files.writeString(root.resolve(file), "{}");
        }
        assertEquals(List.of(), sampleFiles(root));
        for (String file : new String[] {"x/copy/niord-sample.sql", "z/messages/messages.json", "z/archive/domains.json",
                "w/sample/README.md"}) {
            Files.createDirectories(root.resolve(file).getParent());
            Files.writeString(root.resolve(file), "{}");
        }
        assertEquals(List.of("w/sample/README.md", "x/copy/niord-sample.sql", "z/messages/messages.json"),
                sampleFiles(root));
    }

    /** The sample's layout below a sample/ folder, as check-no-sample.sh reads it */
    static final List<String> SAMPLE_LAYOUT = List.of(
            "sample/README.md", "sample/archive/", "sample/messages/", "sample/niord-sample.sql");

    /**
     * The files below the folder that check-no-sample.sh's name rules take for the sample: a niord-sample.sql, a
     * messages.json beside an archive/ folder (in its folder or the one above), and a path of the sample's layout
     */
    static List<String> sampleFiles(Path root) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = root.relativize(file).toString().replace('\\', '/');
                String path = "/" + name;
                Path dir = file.getParent();
                boolean besideArchive = Files.isDirectory(dir.resolve("archive"))
                        || (!dir.equals(root) && Files.isDirectory(dir.getParent().resolve("archive")));
                if (file.getFileName().toString().equals("niord-sample.sql")
                        || (file.getFileName().toString().equals("messages.json") && besideArchive)
                        || SAMPLE_LAYOUT.stream().anyMatch(layout -> path.contains("/" + layout))) {
                    found.add(name);
                }
            }
        }
        return found;
    }

    /**
     * No template or script ships with core since C4.2a: the code reads them from the database only. The classes
     * folder holds no {@code templates/}, no {@code *.ftl} or {@code *.js} at any path of {@code bootstrap/templates/},
     * and no {@code *.ftl} anywhere; it holds the expected path names, and nothing but names.
     */
    @Test
    void noTemplateOrScriptShipsWithCore() throws URISyntaxException, IOException {
        Path classes = classes();
        assertFalse(Files.exists(classes.resolve("templates")), classes + " must hold no templates/");
        try (Stream<Path> files = Files.walk(classes)) {
            List<Path> ftl = files.filter(f -> f.getFileName().toString().endsWith(".ftl")).toList();
            assertEquals(List.of(), ftl, "no FreeMarker template in niord-base");
        }
        Path bootstrapTemplates = Path.of("bootstrap/templates");
        try (Stream<Path> files = Files.walk(bootstrapTemplates)) {
            for (Path template : files.filter(Files::isRegularFile).toList()) {
                String relative = bootstrapTemplates.relativize(template).toString().replace('\\', '/');
                assertFalse(Files.exists(classes.resolve("templates/" + relative)), "templates/" + relative);
                assertFalse(Files.exists(classes.resolve(relative)), relative);
            }
        }

        Path expected = classes.resolve(MissingPathReport.EXPECTED_PATHS);
        assertTrue(Files.isRegularFile(expected), expected + " lists the expected template paths");
        for (String line : Files.readAllLines(expected, StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) {
                continue;
            }
            assertTrue(line.matches("templates/[A-Za-z0-9_./-]+\\.(ftl|js)"), "a path name only: " + line);
        }
    }

    private static Path classes() throws URISyntaxException {
        Path classes = Path.of(NiordApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "niord-base's classes are a folder: " + classes);
        return classes;
    }

    /**
     * Phase 4 of the plan: the Danish DGPS template pair (hardcoded {@code Danmark} area and
     * Skagen/Blåvand/Hammer Odde/Tórshavn stations) must not ship with the generic library.
     * The <em>pair</em> is asserted deliberately — deleting only one half would strand the
     * other (the FTL cannot run without its companion script). The DK deployable retains the
     * complete pair; {@code DkTemplateResourcesTest} in niord-dk asserts that side.
     */
    @Test
    void danishDgpsTemplatePairDoesNotShipWithCore() {
        assertNull(getClass().getResource("/templates/tmpl/dgps.ftl"),
                "dgps.ftl is Danish content and must live in niord-dk only");
        assertNull(getClass().getResource("/templates/tmpl/dgps.js"),
                "dgps.js is Danish content and must live in niord-dk only");
    }
}
