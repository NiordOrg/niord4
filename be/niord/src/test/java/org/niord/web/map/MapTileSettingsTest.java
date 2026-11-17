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
package org.niord.web.map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.NiordBaseVersion;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsCatalog;
import org.niord.core.settings.SettingsCatalogGenerator;
import org.niord.core.settings.SettingsService;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The map tile settings: what the backend makes of the configured tile addresses, the User-Agent of its tile
 * requests, the tile cache folder, and the templates' agreement with the {@code niord.json} files of this module.
 */
class MapTileSettingsTest {

    private static final String DEFAULT_OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
    private static final String DEFAULT_SEA_MARKS = "https://t1.openseamap.org/seamark/{z}/{x}/{y}.png";

    /** The live setting values the mocked SettingsService answers with, by key */
    private final Map<String, String> values = new HashMap<>();

    /** What the mocked SettingsService.peek("niord.home") answers */
    private Object niordHome = "/srv/niord";

    private MapTileSettings settings;

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getString(any(Setting.class)))
                .thenAnswer(invocation -> values.get(invocation.<Setting>getArgument(0).getKey()));
        when(settingsService.peek("niord.home")).thenAnswer(invocation -> niordHome);
        NiordApp app = mock(NiordApp.class);
        when(app.getBaseUri()).thenAnswer(invocation -> values.get("baseUri"));

        settings = new MapTileSettings();
        settings.settingsService = settingsService;
        settings.app = app;

        logger = (Logger) LoggerFactory.getLogger(MapTileSettings.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    // ------------------------------------------------------------------------------------------- //
    // Tile URL templates
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anEmptyTileAddressMeansTheDefaultWithoutAWarning() {
        for (String configured : new String[]{null, "", "   "}) {
            values.put("osmUrl", configured);
            values.put("openSeaMapUrl", configured);
            assertEquals(DEFAULT_OSM, settings.osmUrlTemplate(), "osmUrl '" + configured + "'");
            assertEquals(DEFAULT_SEA_MARKS, settings.openSeaMapUrlTemplate(), "openSeaMapUrl '" + configured + "'");
        }
        assertTrue(warnings().isEmpty(), "an empty value asks for the default: " + warnings());
    }

    @Test
    void aUsableTileAddressIsReturnedTrimmed() {
        values.put("osmUrl", "  https://tiles.example.org/osm/{z}/{x}/{y}.png?key=abc ");
        values.put("openSeaMapUrl", "http://127.0.0.1:9102/seamark/{z}/{x}/{y}.png");

        assertEquals("https://tiles.example.org/osm/{z}/{x}/{y}.png?key=abc", settings.osmUrlTemplate());
        assertEquals("http://127.0.0.1:9102/seamark/{z}/{x}/{y}.png", settings.openSeaMapUrlTemplate());
        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    @Test
    void aSchemeRelativeTileAddressIsGivenHttps() {
        values.put("openSeaMapUrl", "//t1.openseamap.org/seamark/{z}/{x}/{y}.png");
        assertEquals(DEFAULT_SEA_MARKS, settings.openSeaMapUrlTemplate());

        values.put("osmUrl", "//tiles.example.org/{z}/{x}/{y}.png");
        assertEquals("https://tiles.example.org/{z}/{x}/{y}.png", settings.osmUrlTemplate());
    }

    @Test
    void aSubdomainRangeIsRejectedWithAWarning() {
        values.put("osmUrl", "https://{a-c}.tile.example.org/{z}/{x}/{y}.png");

        assertEquals(DEFAULT_OSM, settings.osmUrlTemplate());
        assertEquals(1, warnings().size());
        assertTrue(warnings().getFirst().contains("{a-c}"), warnings().getFirst());
        assertTrue(warnings().getFirst().contains(DEFAULT_OSM), "the warning names what is used instead");
    }

    @Test
    void anAddressWithoutAllThreePlaceholdersIsRejected() {
        for (String configured : new String[]{
                "https://tiles.example.org/{z}/{x}.png",
                "https://tiles.example.org/{z}/{x}/{-y}.png",
                "https://tiles.example.org/tile.png"}) {
            values.put("osmUrl", configured);
            assertEquals(DEFAULT_OSM, settings.osmUrlTemplate(), configured);
        }
        assertEquals(3, warnings().size(), warnings().toString());
        assertTrue(warnings().stream().allMatch(w -> w.contains("{z}, {x} and {y}")), warnings().toString());
    }

    @Test
    void anAddressTheBackendCannotFetchFromIsRejected() {
        for (String configured : new String[]{
                "file:///etc/{z}/{x}/{y}.png",
                "ftp://tiles.example.org/{z}/{x}/{y}.png",
                "tiles.example.org/{z}/{x}/{y}.png",
                "https://{s}.tiles.example.org/{z}/{x}/{y}.png",
                "https://tiles example.org/{z}/{x}/{y}.png",
                "https:///{z}/{x}/{y}.png"}) {
            values.put("osmUrl", configured);
            assertEquals(DEFAULT_OSM, settings.osmUrlTemplate(), configured);
        }
        assertEquals(6, warnings().size(), warnings().toString());
    }

    @Test
    void theTemplateIsReadLiveOnEveryCall() {
        values.put("osmUrl", "https://a.example.org/{z}/{x}/{y}.png");
        assertEquals("https://a.example.org/{z}/{x}/{y}.png", settings.osmUrlTemplate());

        values.put("osmUrl", "https://b.example.org/{z}/{x}/{y}.png");
        assertEquals("https://b.example.org/{z}/{x}/{y}.png", settings.osmUrlTemplate());
    }

    @Test
    void anUnusableValueIsWarnedAboutOnceUntilItChanges() {
        values.put("osmUrl", "https://{a-c}.tile.example.org/{z}/{x}/{y}.png");
        settings.osmUrlTemplate();
        settings.osmUrlTemplate();
        assertEquals(1, warnings().size(), "the same bad value is logged once, not per image");

        values.put("osmUrl", "https://tiles.example.org/{z}/{x}/{y}.png");
        settings.osmUrlTemplate();
        values.put("osmUrl", "https://{a-c}.tile.example.org/{z}/{x}/{y}.png");
        settings.osmUrlTemplate();
        assertEquals(2, warnings().size(), "the bad value set again after a good one is logged again");
    }

    @Test
    void normalisingNeverReportsAUsableOrEmptyValue() {
        List<String> problems = new ArrayList<>();
        assertEquals("d", MapTileSettings.normaliseTemplate(null, "d", problems::add));
        assertEquals("https://x.org/{z}/{x}/{y}", MapTileSettings.normaliseTemplate("//x.org/{z}/{x}/{y}", "d", problems::add));
        assertTrue(problems.isEmpty(), problems.toString());

        assertEquals("d", MapTileSettings.normaliseTemplate("https://x.org/{a-d}/{z}/{x}/{y}", "d", problems::add));
        assertEquals(1, problems.size());
    }

    // ------------------------------------------------------------------------------------------- //
    // User-Agent
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theUserAgentNamesTheVersionAndTheContact() {
        values.put("mapTileContact", " nautical@example.org ");
        values.put("baseUri", "https://niord.example.org");

        assertEquals("Niord/" + NiordBaseVersion.get() + " (+nautical@example.org)", settings.userAgent());
    }

    @Test
    void theUserAgentFallsBackToTheBaseUriWhenNoContactIsSet() {
        values.put("baseUri", "https://niord.example.org");
        for (String contact : new String[]{null, "", "  "}) {
            values.put("mapTileContact", contact);
            assertEquals("Niord/" + NiordBaseVersion.get() + " (+https://niord.example.org)", settings.userAgent(),
                    "contact '" + contact + "'");
        }
    }

    @Test
    void theUserAgentFollowsAnEditOfTheContact() {
        values.put("baseUri", "https://niord.example.org");
        values.put("mapTileContact", "a@example.org");
        assertTrue(settings.userAgent().endsWith("(+a@example.org)"));

        values.put("mapTileContact", "https://example.org/contact");
        assertTrue(settings.userAgent().endsWith("(+https://example.org/contact)"));
    }

    @Test
    void theUserAgentWithoutAnyContactIsTheProductAlone() {
        assertEquals("Niord/4.0.12", MapTileSettings.userAgent("4.0.12", null));
        assertEquals("Niord/4.0.12", MapTileSettings.userAgent("4.0.12", " \t "));
    }

    @Test
    void theUserAgentVersionIsAnRfc9110Token() {
        assertEquals("Niord/4.0.0-SNAPSHOT (+x)", MapTileSettings.userAgent("4.0.0-SNAPSHOT", "x"));
        assertEquals("Niord/4.0build7 (+x)", MapTileSettings.userAgent("4.0 build/7", "x"));
        assertEquals("Niord/dev (+x)", MapTileSettings.userAgent("(){}", "x"));
        assertEquals("Niord/dev (+x)", MapTileSettings.userAgent(null, "x"));
    }

    @Test
    void theUserAgentContactIsCommentTextOnOneLine() {
        assertEquals("Niord/1 (+Ops team ops@example.org)",
                MapTileSettings.userAgent("1", "Ops\r\n  team (ops@example.org)"));
        assertEquals("Niord/1 (+ops@example.org)", MapTileSettings.userAgent("1", "ops\\@example.org"));
        assertEquals("Niord/1 (+ops@exmple.org)", MapTileSettings.userAgent("1", "ops@exämple.org\u0000"));

        String userAgent = MapTileSettings.userAgent("1", "a b\tc\u0007d");
        assertEquals("Niord/1 (+a b cd)", userAgent);
        assertTrue(userAgent.chars().allMatch(c -> c >= 0x20 && c < 0x7f), "printable US-ASCII only");
    }

    // ------------------------------------------------------------------------------------------- //
    // Tile cache folder
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theTileCacheFolderIsTheConfiguredPath() {
        values.put("mapTileCachePath", "/srv/niord/tile-cache");
        assertEquals(Path.of("/srv/niord/tile-cache"), settings.tileCacheDir());
        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    @Test
    void theDefaultCacheFolderWithNothingToResolveTheHomeGoesToTheTemporaryFolder() {
        // SettingsService expands the unresolvable ${niord.home} of the default to the empty string
        values.put("mapTileCachePath", "/tile-cache");
        Path fallback = Path.of(System.getProperty("java.io.tmpdir"), "niord-tile-cache");
        for (Object home : new Object[]{null, "", " "}) {
            niordHome = home;
            assertEquals(fallback, settings.tileCacheDir(), "niord.home '" + home + "'");
        }
        assertEquals(1, warnings().size(), warnings().toString());
        assertTrue(warnings().getFirst().contains("niord.home"), warnings().getFirst());
    }

    @Test
    void anExplicitRootCacheFolderIsKeptWhenTheHomeResolves() {
        values.put("mapTileCachePath", "/tile-cache");
        niordHome = "/srv/niord";
        assertEquals(Path.of("/tile-cache"), settings.tileCacheDir());
    }

    @Test
    void anEmptyCacheFolderSettingGoesToTheTemporaryFolder() {
        values.put("mapTileCachePath", " ");
        assertEquals(Path.of(System.getProperty("java.io.tmpdir"), "niord-tile-cache"), settings.tileCacheDir());
        assertEquals(1, warnings().size(), warnings().toString());
    }

    // ------------------------------------------------------------------------------------------- //
    // Start-up warning
    // ------------------------------------------------------------------------------------------- //

    @Test
    void startUpWarnsWhenTheTileServerCanReachNobody() {
        for (String baseUri : new String[]{"http://localhost:8888", "http://127.0.0.1:8080/", "http://[::1]:8888",
                "", null}) {
            appender.list.clear();
            values.put("baseUri", baseUri);
            settings.warnWhenTileRequestsNameNoContact();
            assertEquals(1, warnings().size(), "baseUri '" + baseUri + "'");
            assertTrue(warnings().getFirst().contains("tile.openstreetmap.org"), warnings().getFirst());
            assertTrue(warnings().getFirst().contains("mapTileContact"), warnings().getFirst());
        }
    }

    @Test
    void startUpIsQuietWithAContactOrAPublicBaseUriOrALocalTileServer() {
        values.put("baseUri", "http://localhost:8888");
        values.put("mapTileContact", "nautical@example.org");
        settings.warnWhenTileRequestsNameNoContact();

        values.put("mapTileContact", "");
        values.put("baseUri", "https://niord.example.org");
        settings.warnWhenTileRequestsNameNoContact();

        values.put("baseUri", "http://localhost:8888");
        values.put("osmUrl", "http://127.0.0.1:9/{z}/{x}/{y}.png");
        settings.warnWhenTileRequestsNameNoContact();

        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    @Test
    void aFailingStartUpCheckDoesNotStopTheApplication() {
        when(settings.settingsService.getString(any(Setting.class))).thenThrow(new IllegalStateException("no db"));
        settings.warnWhenTileRequestsNameNoContact();
        assertEquals(1, warnings().size());
    }

    // ------------------------------------------------------------------------------------------- //
    // The templates and the settings files of this module
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theTemplatesDeclareTheirDefaultsAndFlags() {
        assertTemplate(MapTileSettings.OSM_URL, "osmUrl", DEFAULT_OSM, true, true);
        assertTemplate(MapTileSettings.OPEN_SEA_MAP_URL, "openSeaMapUrl", DEFAULT_SEA_MARKS, true, true);
        assertTemplate(MapTileSettings.MAP_TILE_CONTACT, "mapTileContact", "", false, true);
        assertTemplate(MapTileSettings.MAP_TILE_CACHE_PATH, "mapTileCachePath", "${niord.home}/tile-cache", false, false);
    }

    @Test
    void theBaseSettingsFileAndTheCatalogDeclareTheTileSettingsAsTheTemplatesDo() {
        Map<String, Object> file = SettingsCatalogGenerator.readValues(
                Path.of("src/main/resources/defaults/base/settings.json"));
        Map<String, SettingsCatalog.Entry> catalog =
                SettingsCatalog.read(Path.of("src/main/resources/defaults/base/settings-catalog.json")).byKey();
        for (Setting template : List.of(MapTileSettings.OSM_URL, MapTileSettings.OPEN_SEA_MAP_URL,
                MapTileSettings.MAP_TILE_CONTACT)) {
            assertEquals(template.getValue(), file.get(template.getKey()), template.getKey());
            SettingsCatalog.Entry entry = catalog.get(template.getKey());
            assertNotNull(entry, template.getKey() + " is in the catalog");
            assertEquals(template.getDescription(), entry.description(), template.getKey() + " description");
            assertEquals(template.isWeb(), entry.web(), template.getKey() + " web");
            assertEquals(template.isEditable(), entry.editable(), template.getKey() + " editable");
        }
    }

    @Test
    void theTestNiordJsonPointsTheTileAddressesAtAPortNothingListensOn() throws Exception {
        // So that no integration test ever fetches a tile from a public server
        List<Setting> file = settingsOf(Path.of("src/test/resources/niord.json"));
        for (Setting template : List.of(MapTileSettings.OSM_URL, MapTileSettings.OPEN_SEA_MAP_URL)) {
            Setting declared = byKey(file, template.getKey());
            assertSameDeclaration(template, declared);
            assertEquals("http://127.0.0.1:9/{z}/{x}/{y}.png", declared.getValue(), template.getKey());
        }
        assertSameDeclaration(MapTileSettings.MAP_TILE_CONTACT, byKey(file, "mapTileContact"));
    }

    // ------------------------------------------------------------------------------------------- //

    private List<String> warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static void assertTemplate(Setting template, String key, Object value, boolean web, boolean editable) {
        assertEquals(key, template.getKey());
        assertEquals(value, template.getValue(), key);
        assertEquals(Setting.Type.String, template.getType(), key);
        assertEquals(web, template.isWeb(), key + " web");
        assertEquals(editable, template.isEditable(), key + " editable");
        assertTrue(template.isCached(), key + " cached");
        assertTrue(template.getDescription().length() <= 255,
                key + ": the Setting.description column holds 255 characters");
    }

    private static void assertSameDeclaration(Setting template, Setting declared) {
        assertNotNull(declared, "niord.json must declare " + template.getKey());
        assertEquals(template.getDescription(), declared.getDescription(), template.getKey() + " description");
        assertEquals(template.isWeb(), declared.isWeb(), template.getKey() + " web");
        assertEquals(template.isEditable(), declared.isEditable(), template.getKey() + " editable");
    }

    private static List<Setting> settingsOf(Path file) throws Exception {
        assertTrue(Files.isRegularFile(file), file + " must exist");
        try (InputStream is = Files.newInputStream(file)) {
            return JsonMapper.builderWithJackson2Defaults().build().readValue(is, new TypeReference<>() {});
        }
    }

    private static Setting byKey(List<Setting> settings, String key) {
        return settings.stream().filter(s -> key.equals(s.getKey())).findFirst().orElse(null);
    }
}
