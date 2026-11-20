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
package org.niord.core.settings;

import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The catalog-current check (docs/code-data-config-plan.md §5.1): the committed
 * {@code defaults/base/settings-catalog.json} is what {@link SettingsCatalogGenerator} makes from niord-base's
 * classes and {@code defaults/base/settings.json}, so a PR shows every new or changed setting key.
 *
 * <p>With {@code -Dniord.settingsCatalog.write=true} it writes the catalog instead (see {@link #REGENERATE}). A
 * unit test, so Backend CI's unit job runs it.
 */
class SettingsCatalogCurrentTest {

    static final Path BASE_SETTINGS = Path.of("src/main/resources/defaults/base/settings.json");
    static final Path BASE_CATALOG = Path.of("src/main/resources/defaults/base/settings-catalog.json");

    /** The command that regenerates the catalog, from be/ */
    static final String REGENERATE = "./mvnw -pl niord test -Dtest=SettingsCatalogCurrentTest"
            + " -Dsurefire.failIfNoSpecifiedTests=false -D" + SettingsCatalogGenerator.WRITE_PROPERTY + "=true"
            + " [-D" + SettingsCatalogGenerator.INTRODUCED_IN_PROPERTY + "=<the newest release plus one>, for a new key]";

    @Test
    void theCommittedCatalogIsTheGeneratedOne() throws IOException, URISyntaxException {
        boolean write = Boolean.getBoolean(SettingsCatalogGenerator.WRITE_PROPERTY);
        SettingsCatalog committed = Files.exists(BASE_CATALOG) ? SettingsCatalog.read(BASE_CATALOG) : null;

        SettingsCatalog generated = SettingsCatalogGenerator.generate(
                SettingsCatalogGenerator.scan(mainClassesRoot(), getClass().getClassLoader()),
                SettingsCatalogGenerator.readValues(BASE_SETTINGS),
                committed,
                System.getProperty(SettingsCatalogGenerator.INTRODUCED_IN_PROPERTY),
                write);

        if (write) {
            generated.write(BASE_CATALOG);
        } else {
            if (committed == null) {
                fail(BASE_CATALOG + " is missing. Generate it: " + REGENERATE);
            }
            String text = Files.readString(BASE_CATALOG, StandardCharsets.UTF_8);
            if (!SettingsCatalog.sameText(text, generated.toJson())) {
                String diff = SettingsCatalogGenerator.diff(committed, generated);
                fail(BASE_CATALOG + " is not the catalog the code gives"
                        + (diff.isEmpty() ? " (no key differs: the file was edited by hand)" : ":\n" + diff)
                        + "\nRegenerate it: " + REGENERATE);
            }
        }

        assertTheCatalogsContent(generated);
    }

    /** What the base product's catalog holds since C3a: guards against a generation that missed declarations */
    private static void assertTheCatalogsContent(SettingsCatalog catalog) {
        Map<String, SettingsCatalog.Entry> byKey = catalog.byKey();
        assertEquals(74, byKey.size(), "the catalog has " + byKey.size() + " keys: when a declaration was added or "
                + "removed on purpose, update the expected count in SettingsCatalogCurrentTest.assertTheCatalogsContent"
                + "; otherwise the generation missed declarations. The keys: " + byKey.keySet());

        assertEquals(Set.of("areaLastUpdate", "categoryLastUpdate"), keys(catalog, e -> e.scope() == Setting.Scope.state),
                "the state keys");

        // Since C5: niord-base names no country; the two required keys have no code default, and the base
        // product's settings file gives them neutral values that every country's file replaces
        assertEquals(Set.of("country", "organisation"), keys(catalog, SettingsCatalog.Entry::required),
                "the required keys");
        assertEquals("XX", byKey.get("country").baseValue(), "the base product's neutral country");
        assertEquals("Niord", byKey.get("organisation").baseValue(), "the base product's neutral organisation");
        for (String key : List.of("country", "organisation")) {
            assertEquals(null, byKey.get(key).codeDefault(), key + " has no code default");
        }

        Set<String> missing = SettingsCatalogGenerator.readValues(BASE_SETTINGS).keySet().stream()
                .filter(k -> !byKey.containsKey(k))
                .collect(Collectors.toSet());
        assertTrue(missing.isEmpty(), "every key of " + BASE_SETTINGS + " has a declaration; these have none: " + missing);

        assertEquals(Set.of("authServerTrustAllCertificates", "secomEnabled", "mapTileCachePath"),
                keys(catalog, e -> e.scope() == Setting.Scope.operator && !e.editable()),
                "the operator keys the Settings page does not show");
        assertTrue(byKey.get("secomEnabled").web(), "the administration app reads secomEnabled from /web");
        assertFalse(byKey.containsKey("s124Enabled"),
                "s124Enabled is computed by /rest/settings/web from niord.s124.enabled, never declared or stored");

        SettingsCatalog.Entry integration = byKey.get("adminIntegrationPageEnabled");
        assertTrue(integration.web() && integration.editable(), "the Integration section's gate");
        assertEquals(Boolean.FALSE, integration.codeDefault(), "the Integration section is hidden by default");

        // declaredIn: what decides whether start-up adds a key on a node (SettingsService.declaringCodeRuns)
        assertTrue(catalog.entries().stream().noneMatch(e -> e.declaredIn().isEmpty()), "every key has declaredIn");
        Set<String> secom = keys(catalog, e -> e.key().startsWith("secom"));
        assertEquals(17, secom.size(), "the SECOM keys: " + secom);
        secom.forEach(key -> assertTrue(byKey.get(key).declaredIn().stream().allMatch(c -> c.startsWith("org.niord.secom.")),
                key + " is declared only in SECOM's conditional code: " + byKey.get(key).declaredIn()));
        Set<String> frontend = keys(catalog, e -> e.declaredIn().equals(List.of(FrontendSettings.class.getName())));
        assertEquals(12, frontend.size(), "the twelve FrontendSettings keys: " + frontend);

        // Whether the administration app's maps start with the sea-chart layer on: served to the web applications
        // and shown on the Settings page, off by default (the value served replaces the app's environment file's)
        SettingsCatalog.Entry wmsByDefault = byKey.get("wmsLayerEnabledByDefault");
        assertTrue(wmsByDefault != null && wmsByDefault.web() && wmsByDefault.editable(),
                "wmsLayerEnabledByDefault is a web setting the Settings page edits");
        assertEquals(Setting.Type.Boolean, wmsByDefault.type(), "a Boolean, as wmsLayerEnabled");
        assertEquals(Boolean.FALSE, wmsByDefault.codeDefault(), "the sea-chart layer starts switched off by default");
        assertEquals(Setting.Scope.operator, wmsByDefault.scope(), "an operator setting");
        for (String key : List.of("wmsProvider", "wmsPassword", "wmsLayers", "wmsProtected")) {
            assertEquals(List.of("org.niord.web.wms.WmsProxyServlet"), byKey.get(key).declaredIn(), key);
        }

        // The sea-chart tile cache is off until a deployment switches it on
        for (String key : List.of("wmsCacheDays", "wmsCacheMaxMb")) {
            assertEquals(List.of("org.niord.web.wms.WmsTileCache"), byKey.get(key).declaredIn(), key);
            assertFalse(byKey.get(key).web(), key + " is the backend's alone");
        }
        assertEquals(0, byKey.get("wmsCacheDays").codeDefault(), "no tile is cached by default");
        assertEquals(null, byKey.get("wmsCacheDays").baseValue(), "and the base product's file does not switch it on");
    }

    /** The keys declared by an {@code @Setting} field that the code reads through SettingsService at each use */
    static final Set<String> READ_LIVE = Set.of("secomMirPortalUrl", "mailValidRecipients", "mailDeleteAfterDays",
            "wmsProtected");

    /** The keys declared by a static {@link Setting} that the code reads once, when it creates its beans */
    static final Set<String> CONSTANTS_READ_AT_START_UP = Set.of("secomValidateClientChain", "secomRevocationCheck",
            "secomSignatureAlgorithm");

    static final String READ_AT_START_UP = "Read at start-up.";

    /**
     * A change on the Settings page reaches a value injected into a field only at the next start, so the description
     * of every editable key read that way ends with "Read at start-up.", and the description of a key read at each use
     * does not (SECOM-UI-1). {@code wmsProtected} names what it guards: the WMS map tiles, not the API
     * (SETTINGS-12). A key read live from now on moves to {@link #READ_LIVE}, and its text loses the sentence.
     */
    @Test
    void theSettingsReadAtStartUpSaySo() throws Exception {
        Map<String, SettingsCatalog.Entry> byKey = SettingsCatalog.read(BASE_CATALOG).byKey();
        ClassLoader cl = getClass().getClassLoader();

        Set<String> fieldKeys = new TreeSet<>();
        for (SettingsCatalogGenerator.Declaration d : SettingsCatalogGenerator.scan(mainClassesRoot(), cl)) {
            String className = d.source().substring(0, d.source().indexOf('#'));
            String fieldName = d.source().substring(d.source().indexOf('#') + 1);
            if (Class.forName(className, false, cl).getDeclaredField(fieldName)
                    .isAnnotationPresent(org.niord.core.settings.annotation.Setting.class)) {
                fieldKeys.add(d.setting().getKey());
            }
        }
        assertTrue(fieldKeys.containsAll(READ_LIVE), "the live keys are declared by fields: " + fieldKeys);

        List<String> wrong = new ArrayList<>();
        for (String key : fieldKeys) {
            SettingsCatalog.Entry entry = byKey.get(key);
            if (!entry.editable()) {
                continue;
            }
            boolean saysSo = entry.description() != null && entry.description().endsWith(READ_AT_START_UP);
            if (READ_LIVE.contains(key) == saysSo) {
                wrong.add(key + ": '" + entry.description() + "'");
            }
        }
        for (String key : CONSTANTS_READ_AT_START_UP) {
            String description = byKey.get(key).description();
            if (description == null || !description.endsWith(READ_AT_START_UP)) {
                wrong.add(key + ": '" + description + "'");
            }
        }
        assertTrue(wrong.isEmpty(), "a key read at start-up ends its description with '" + READ_AT_START_UP
                + "', a key read live does not; these do not follow the rule:\n" + String.join("\n", wrong));

        assertEquals("Whether the WMS map tiles (/wms/*) require a signed-in user",
                byKey.get("wmsProtected").description(), "wmsProtected guards the map tiles only");
    }

    private static Set<String> keys(SettingsCatalog catalog, Predicate<SettingsCatalog.Entry> filter) {
        return catalog.entries().stream().filter(filter).map(SettingsCatalog.Entry::key).collect(Collectors.toSet());
    }

    /** niord-base's own classes, never the test classes: target/classes */
    private static Path mainClassesRoot() throws URISyntaxException {
        Path root = Path.of(NiordApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(root), "niord-base's classes are a folder: " + root);
        assertEquals("classes", root.getFileName().toString(), "the main classes: " + root);
        return root;
    }
}
