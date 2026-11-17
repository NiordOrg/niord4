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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings the base library ships in {@code defaults/base/settings.json}, which the generic deployable
 * ({@code niord-app}) runs on, with their flags from the catalog. Read from the source tree. The retired-settings check
 * also reads the test tree's root-level {@code niord.json}, which the integration tests read as a country's settings
 * file.
 */
class NiordBaseSettingsResourceTest {

    private static final Path TEST_ROOT_SETTINGS = Path.of("src/test/resources/niord.json");

    @Test
    void theGenericSettingsDeclareTheInfoSiteLanguages() {
        Map<String, Object> values = baseValues();
        Map<String, SettingsCatalog.Entry> catalog = catalog();

        assertEquals(List.of("en"), values.get("infoSiteLanguages"), "the base file sets infoSiteLanguages");
        assertTrue(catalog.get("infoSiteLanguages").web(), "the public site reads it from /rest/settings/web");

        assertEquals("en", values.get("infoDefaultLanguage"), "the base file sets infoDefaultLanguage");
        assertTrue(catalog.get("infoDefaultLanguage").web(), "the public site reads it from /rest/settings/web");
    }

    @Test
    void theGenericSettingsDeclareTheTestMode() {
        // The owner's ruling of 2026-11-04: a fresh database starts in test mode and shows the
        // ribbon; a production deployment sets mode=production by environment (NiordApp says why).
        assertEquals("test", baseValues().get("mode"), "the base file sets the mode");
        SettingsCatalog.Entry mode = catalog().get("mode");
        assertTrue(mode.web(), "both frontends read the mode from /rest/settings/web");
        assertTrue(mode.editable());
    }

    @Test
    void theGenericSettingsDeclareTheMapTileSettings() {
        Map<String, Object> values = baseValues();
        Map<String, SettingsCatalog.Entry> catalog = catalog();

        assertTileAddress(values, catalog, "osmUrl", "https://tile.openstreetmap.org/{z}/{x}/{y}.png");
        assertTileAddress(values, catalog, "openSeaMapUrl", "https://t1.openseamap.org/seamark/{z}/{x}/{y}.png");

        assertEquals("", values.get("mapTileContact"), "the base file sets mapTileContact, so a sysadmin can set it");
        assertFalse(catalog.get("mapTileContact").web(), "the contact is the backend's own: it goes into no browser");
        assertTrue(catalog.get("mapTileContact").editable());
    }

    @Test
    void neitherSettingsFileDeclaresARetiredMapImageSetting() throws Exception {
        Map<String, Object> base = baseValues();
        List<String> testRoot = rootKeys(TEST_ROOT_SETTINGS);
        for (String key : List.of("mapImageServer", "mapImageIndent", "osmSourceUrl")) {
            assertFalse(base.containsKey(key), SettingsCatalogCurrentTest.BASE_SETTINGS + ": " + key + " is retired");
            assertFalse(testRoot.contains(key), TEST_ROOT_SETTINGS + ": " + key + " is retired");
        }
    }

    private static void assertTileAddress(Map<String, Object> values, Map<String, SettingsCatalog.Entry> catalog,
                                          String key, String value) {
        assertEquals(value, values.get(key), "the base file sets the tile address " + key);
        assertTrue(catalog.get(key).web(), key + ": both web applications read it from /rest/settings/web");
        assertTrue(catalog.get(key).editable(), key);
    }

    private static Map<String, Object> baseValues() {
        return SettingsCatalogGenerator.readValues(SettingsCatalogCurrentTest.BASE_SETTINGS);
    }

    private static Map<String, SettingsCatalog.Entry> catalog() {
        return SettingsCatalog.read(SettingsCatalogCurrentTest.BASE_CATALOG).byKey();
    }

    /** The keys of a settings file in the legacy list form */
    private static List<String> rootKeys(Path file) throws Exception {
        assertTrue(Files.isRegularFile(file), file + " must exist: the settings resource the tests read");
        try (InputStream is = Files.newInputStream(file)) {
            List<Setting> settings = JsonMapper.builderWithJackson2Defaults().build()
                    .readValue(is, new TypeReference<>() {});
            return settings.stream().map(Setting::getKey).toList();
        }
    }
}
