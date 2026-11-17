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
package org.niord.core.defaults;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsCatalog;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.core.defaults.DefaultTextsTest.classLoader;
import static org.niord.core.defaults.DefaultTextsTest.write;

/**
 * The default settings ({@link DefaultSettings}, docs/code-data-config-plan.md §5.2): which file gives the values,
 * what a file may not set, the server's overlay, and the order a value resolves in. Each test builds its class path in
 * temporary folders, since a fixture under the test resources' {@code defaults/} would be on every test's class path
 * in this module (the test-jar excludes {@code defaults/**}; the folder holds only the stand-in da pack).
 */
class DefaultSettingsTest {

    /** The base product's fixture catalog: a key of each kind the reader tells apart */
    private static final SettingsCatalog BASE_CATALOG = new SettingsCatalog(List.of(
            entry("mode", Setting.Type.String, "production", "production", Setting.Scope.operator, true),
            entry("zoom", Setting.Type.Integer, 5, 6, Setting.Scope.operator, true),
            entry("contact", Setting.Type.String, "", null, Setting.Scope.operator, false),
            entry("languages", Setting.Type.json, List.of("en"), List.of("en"), Setting.Scope.operator, true),
            entry("token", Setting.Type.Password, "", null, Setting.Scope.operator, false),
            entry("lastUpdate", Setting.Type.Date, null, null, Setting.Scope.state, false)));

    private static final String BASE_FILE = """
            { "values": { "mode": "production", "zoom": 6, "languages": [ "en" ] } }
            """;

    @TempDir
    Path base;

    @TempDir
    Path country;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureTheLog() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(DefaultSettings.class)).addAppender(appender);
    }

    @AfterEach
    void releaseTheLog() {
        ((Logger) LoggerFactory.getLogger(DefaultSettings.class)).detachAppender(appender);
    }

    @Test
    void theBaseProductsFileGivesTheValuesWhenNoCountryHasOne() throws IOException {
        DefaultSettings defaults = read(BASE_FILE, null, null);

        assertEquals("production", defaults.defaultValue("mode"));
        assertEquals(DefaultSettings.SOURCE_BASE, defaults.source("mode"));
        assertTrue(defaults.hasFileValue("zoom"));
        assertEquals("", defaults.defaultValue("contact"), "no file value and no base value: the code default");
        assertEquals(DefaultSettings.SOURCE_CODE, defaults.source("contact"));
        assertFalse(defaults.hasFileValue("contact"));
    }

    @Test
    void aCountrysFileReplacesTheBaseProductsWhole() throws IOException {
        DefaultSettings defaults = read(BASE_FILE, """
                { "values": { "mode": "test", "zoom": 9, "contact": "ops@example.org" },
                  "useBaseDefault": [ { "key": "zoom", "reason": "the base product's zoom fits" } ] }
                """, null);

        assertEquals("test", defaults.defaultValue("mode"));
        assertEquals(DefaultSettings.SOURCE_COUNTRY, defaults.source("mode"));
        assertEquals("ops@example.org", defaults.defaultValue("contact"));
        assertEquals(6, defaults.defaultValue("zoom"), "listed in useBaseDefault: the base product default");
        assertEquals(DefaultSettings.SOURCE_BASE, defaults.source("zoom"));
        assertEquals(List.of("en"), defaults.defaultValue("languages"),
                "a key of the base file the country lacks: the base product's value");
        assertFalse(defaults.hasFileValue("languages"), "the country's file replaces the base file whole");
    }

    @Test
    void aRootNiordJsonIsReadAsTheCountrysFileWhenThereIsNone() throws IOException {
        DefaultSettings defaults = read(BASE_FILE, null, """
                [ { "key": "mode", "value": "test", "type": "json", "web": false, "editable": false },
                  { "key": "contact", "value": "ops@example.org" } ]
                """);

        assertEquals("test", defaults.defaultValue("mode"));
        assertEquals(DefaultSettings.SOURCE_ROOT, defaults.source("mode"));
        assertEquals(6, defaults.defaultValue("zoom"), "a key the root file lacks: the base product's value");
        SettingsCatalog.Entry mode = defaults.entry("mode");
        assertEquals(Setting.Type.String, mode.type(), "the type comes from the catalog, never from the file");
        assertTrue(mode.web() && mode.editable(), "the flags come from the catalog, never from the file");
        assertTrue(logged(Level.INFO, "as the country's settings file"), "the transitional read is logged");
    }

    @Test
    void aRootNiordJsonIsIgnoredWhenTheCountryHasItsFile() throws IOException {
        DefaultSettings defaults = read(BASE_FILE, "{ \"values\": { \"mode\": \"test\" } }", """
                [ { "key": "mode", "value": "development" } ]
                """);

        assertEquals("test", defaults.defaultValue("mode"));
        assertEquals(DefaultSettings.SOURCE_COUNTRY, defaults.source("mode"));
        assertTrue(logged(Level.INFO, "Ignoring the root-level"), "the ignored file is logged");
    }

    @Test
    void anUndeclaredKeyAndAStateKeyAreSkippedWithAWarning() throws IOException {
        DefaultSettings defaults = read(BASE_FILE, null, """
                [ { "key": "wmsLogin", "value": "TO_BE_DEFINED" },
                  { "key": "lastUpdate", "value": 1600000000000 } ]
                """);

        assertFalse(defaults.hasFileValue("wmsLogin"));
        assertNull(defaults.defaultValue("wmsLogin"), "a key no catalog declares has no default");
        assertFalse(defaults.hasFileValue("lastUpdate"));
        assertTrue(logged(Level.WARN, "skipped wmsLogin: no catalog declares it"));
        assertTrue(logged(Level.WARN, "skipped lastUpdate: a state setting"));
    }

    @Test
    void aValueOfTheWrongTypeIsSkippedWithAnError() throws IOException {
        DefaultSettings defaults = read(BASE_FILE, "{ \"values\": { \"zoom\": \"six\" } }", null);

        assertFalse(defaults.hasFileValue("zoom"));
        assertEquals(6, defaults.defaultValue("zoom"), "the key falls to the next layer");
        assertTrue(logged(Level.ERROR, "skipped zoom: Integer-typed, takes an integral number in int range"));
    }

    @Test
    void theServersOverlayWinsAndGivesValuesOnly() throws IOException {
        DefaultSettings defaults = read(BASE_FILE, null, null);
        Map<String, Object> overlay = DefaultSettings.readLegacy("niord.json", new ByteArrayInputStream("""
                [ { "key": "mode", "value": "development", "type": "Password", "web": false },
                  { "key": "token", "value": "secret" },
                  { "key": "unknownKey", "value": 1 },
                  { "key": "zoom", "value": "x" } ]
                """.getBytes(StandardCharsets.UTF_8)));

        DefaultSettings overlaid = defaults.withOverlay("niord.json", overlay);

        assertEquals("development", overlaid.defaultValue("mode"));
        assertEquals(DefaultSettings.SOURCE_HOME, overlaid.source("mode"));
        assertEquals(Setting.Type.String, overlaid.entry("mode").type(), "the type comes from the catalog");
        assertTrue(overlaid.entry("mode").web(), "the flags come from the catalog");
        assertEquals("secret", overlaid.defaultValue("token"));
        assertFalse(overlaid.hasFileValue("unknownKey"));
        assertEquals(6, overlaid.defaultValue("zoom"), "a wrong type is skipped");
        assertTrue(logged(Level.WARN, "skipped unknownKey: no catalog declares it"));
        assertEquals("production", defaults.defaultValue("mode"), "the original is unchanged");
    }

    @Test
    void aValueResolvesFromTheFileThenTheBaseValueThenTheCodeDefault() throws IOException {
        DefaultSettings defaults = read("{ \"values\": { \"zoom\": 7 } }", null, null);

        assertEquals(7, defaults.defaultValue("zoom"), "the file's value");
        assertEquals("production", defaults.defaultValue("mode"), "the base value");
        assertEquals("", defaults.defaultValue("contact"), "the code default");
        assertNull(defaults.defaultValue("lastUpdate"), "a state key with no code default");
        assertNull(defaults.source("unknownKey"));
    }

    @Test
    void aCountrysCatalogAddsItsKeys() throws IOException {
        write(country, SettingsCatalog.COUNTRY_RESOURCE, new SettingsCatalog(List.of(
                entry("dk.flag", Setting.Type.Boolean, false, null, Setting.Scope.operator, false),
                entry("mode", Setting.Type.Boolean, true, null, Setting.Scope.operator, false))).toJson());

        DefaultSettings defaults = read(BASE_FILE, "{ \"values\": { \"dk.flag\": true } }", null);

        assertEquals(Boolean.TRUE, defaults.defaultValue("dk.flag"));
        assertEquals(Setting.Type.String, defaults.entry("mode").type(), "a base product key keeps its entry");
    }

    @Test
    void aCountryLayerWithoutARequiredKeyGetsTheBaseValueAndAWarning() throws IOException {
        // Plan §5.2: a country adds the required keys it lacks when it takes a C5+ base product. Until it has, a
        // database without the row gets the base product's neutral value; start-up warns and refuses nothing
        List<SettingsCatalog.Entry> entries = new ArrayList<>(BASE_CATALOG.entries());
        entries.add(new SettingsCatalog.Entry("country", Setting.Type.String, null, "XX", Setting.Scope.operator,
                false, true, true, true, "4.0.74", "The country", List.of()));
        String baseFile = "{ \"values\": { \"mode\": \"production\", \"zoom\": 6, \"country\": \"XX\" } }";
        write(base, SettingsCatalog.BASE_RESOURCE, new SettingsCatalog(entries).toJson());
        write(base, DefaultSettings.BASE_SETTINGS, baseFile);

        try (URLClassLoader cl = classLoader(base)) {
            DefaultSettings generic = DefaultSettings.read(cl);
            assertEquals("XX", generic.defaultValue("country"));
            assertEquals(DefaultSettings.SOURCE_BASE, generic.source("country"));
        }
        assertFalse(logged(Level.WARN, "required setting"), "the base product's file alone warns of nothing");

        write(country, DefaultSettings.ROOT_SETTINGS, "[ { \"key\": \"mode\", \"value\": \"test\" } ]");
        try (URLClassLoader cl = classLoader(country, base)) {
            DefaultSettings root = DefaultSettings.read(cl);
            assertEquals("XX", root.defaultValue("country"), "the transitional root file lacks it: the base value");
            assertEquals(DefaultSettings.SOURCE_BASE, root.source("country"));
        }
        assertTrue(logged(Level.WARN, "does not set the required setting country"), "the root file is warned of");

        appender.list.clear();
        write(country, DefaultSettings.COUNTRY_SETTINGS, "{ \"values\": { \"mode\": \"test\" } }");
        try (URLClassLoader cl = classLoader(country, base)) {
            DefaultSettings countryFile = DefaultSettings.read(cl);
            assertEquals("XX", countryFile.defaultValue("country"), "the country's file lacks it: the base value");
            assertEquals("test", countryFile.defaultValue("mode"));
        }
        assertTrue(logged(Level.WARN, DefaultSettings.COUNTRY_SETTINGS + " does not set the required setting country"),
                "the country's file is warned of");

        appender.list.clear();
        write(country, DefaultSettings.COUNTRY_SETTINGS, "{ \"values\": { \"mode\": \"test\", \"country\": \"SE\" } }");
        try (URLClassLoader cl = classLoader(country, base)) {
            DefaultSettings complete = DefaultSettings.read(cl);
            assertEquals("SE", complete.defaultValue("country"));
            assertEquals(DefaultSettings.SOURCE_COUNTRY, complete.source("country"));
        }
        assertFalse(logged(Level.WARN, "required setting"), "a country's file that sets it is not warned of");
    }

    @Test
    void thePasswordKeysAreTheCatalogs() throws IOException {
        assertEquals(Set.of("token"), read(BASE_FILE, null, null).passwordKeys());
    }

    @Test
    void theBaseProductsCatalogIsRequired() throws IOException {
        write(base, DefaultSettings.BASE_SETTINGS, BASE_FILE);
        try (URLClassLoader cl = classLoader(base)) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> DefaultSettings.read(cl));
            assertTrue(e.getMessage().contains(SettingsCatalog.BASE_RESOURCE), e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------- //

    /** Reads the defaults of the base product's fixture catalog and file, a country's file and a root niord.json */
    private DefaultSettings read(String baseFile, String countryFile, String rootFile) throws IOException {
        write(base, SettingsCatalog.BASE_RESOURCE, BASE_CATALOG.toJson());
        write(base, DefaultSettings.BASE_SETTINGS, baseFile);
        if (countryFile != null) {
            write(country, DefaultSettings.COUNTRY_SETTINGS, countryFile);
        }
        if (rootFile != null) {
            write(country, DefaultSettings.ROOT_SETTINGS, rootFile);
        }
        try (URLClassLoader cl = classLoader(country, base)) {
            return DefaultSettings.read(cl);
        }
    }

    private boolean logged(Level level, String text) {
        return appender.list.stream()
                .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(text));
    }

    private static SettingsCatalog.Entry entry(String key, Setting.Type type, Object codeDefault, Object baseValue,
                                               Setting.Scope scope, boolean web) {
        boolean operator = scope == Setting.Scope.operator;
        return new SettingsCatalog.Entry(key, type, codeDefault, baseValue, scope, web, operator, false, true,
                "4.0.74", "A " + key, List.of());
    }
}
