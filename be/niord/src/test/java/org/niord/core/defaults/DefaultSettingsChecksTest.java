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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsCatalog;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.core.defaults.DefaultSettingsChecks.BASE_SETTINGS;
import static org.niord.core.defaults.DefaultSettingsChecks.COUNTRY_SETTINGS;
import static org.niord.core.defaults.DefaultTextsTest.classLoader;
import static org.niord.core.defaults.DefaultTextsTest.write;

/**
 * The settings checks ({@link DefaultSettingsChecks}, docs/code-data-config-plan.md §5.5): one failing file per rule,
 * and the cases that pass. Each test builds its class path in temporary folders, since a fixture under the test
 * resources' {@code defaults/} would be on every test's class path in this module (the test-jar excludes
 * {@code defaults/**}; the folder holds only the stand-in da pack).
 */
class DefaultSettingsChecksTest {

    private static final String BASE_CATALOG = SettingsCatalog.BASE_RESOURCE;
    private static final String COUNTRY_CATALOG = SettingsCatalog.COUNTRY_RESOURCE;

    /** The base product's fixture catalog: a key of each kind the rules tell apart */
    private static final SettingsCatalog CATALOG = new SettingsCatalog(List.of(
            entry("mode", Setting.Type.String, Setting.Scope.operator, false),
            entry("zoom", Setting.Type.Integer, Setting.Scope.operator, false),
            entry("layerEnabled", Setting.Type.Boolean, Setting.Scope.operator, false),
            entry("languages", Setting.Type.json, Setting.Scope.operator, false),
            entry("docsUrl", Setting.Type.String, Setting.Scope.operator, false),
            entry("wmsPassword", Setting.Type.Password, Setting.Scope.operator, false),
            entry("country", Setting.Type.String, Setting.Scope.operator, true),
            entry("areaLastUpdate", Setting.Type.Date, Setting.Scope.state, false)));

    /** A base product's file that passes */
    private static final String BASE_FILE = """
            { "values": { "mode": "production", "zoom": 6, "layerEnabled": false, "languages": [ "en" ],
                          "docsUrl": "https://docs.example.org", "country": "XX" } }
            """;

    @TempDir
    Path base;

    @TempDir
    Path country;

    @Test
    void theFixturesPass() throws IOException {
        DefaultSettingsChecks.Result result = checkBase(BASE_FILE);

        assertTrue(result.errors().isEmpty(), result.report());
        assertTrue(result.warnings().isEmpty(), result.report());
        assertEquals(6, result.valuesRead().get(BASE_SETTINGS));
    }

    @Test
    void anUndeclaredKeyFails() throws IOException {
        assertFails(checkBase(withValue("wmsLogin", "\"user\"")), "wmsLogin", DefaultSettingsChecks.UNDECLARED_KEY);
    }

    @Test
    void aStateKeyInAFileFails() throws IOException {
        assertFails(checkBase(withValue("areaLastUpdate", "0")), "areaLastUpdate", DefaultSettingsChecks.STATE_KEY);
    }

    @Test
    void aValueOfTheWrongTypeFails() throws IOException {
        DefaultSettingsChecks.Result result = checkBase(BASE_FILE
                .replace("\"zoom\": 6", "\"zoom\": \"6\"")
                .replace("\"layerEnabled\": false", "\"layerEnabled\": \"true\""));

        assertFails(result, "zoom", DefaultSettingsChecks.TYPE);
        assertFails(result, "layerEnabled", DefaultSettingsChecks.TYPE);
        assertEquals(2, result.errors().size(), result.report());
    }

    @Test
    void anIntegerOutOfIntRangeFails() throws IOException {
        assertFails(checkBase(BASE_FILE.replace("\"zoom\": 6", "\"zoom\": 3000000000")), "zoom",
                DefaultSettingsChecks.TYPE);
    }

    @Test
    void aPasswordWithAValueFails() throws IOException {
        DefaultSettingsChecks.Result result = checkBase(withValue("wmsPassword", "\"TO_BE_DEFINED\""));

        assertFails(result, "wmsPassword", DefaultSettingsChecks.SECRET);
        assertFalse(result.report().contains("TO_BE_DEFINED"), "a finding never shows the value: " + result.report());
    }

    @Test
    void anEmptyPasswordPasses() throws IOException {
        DefaultSettingsChecks.Result result = checkBase(withValue("wmsPassword", "\"\""));

        assertTrue(result.errors().isEmpty(), result.report());
    }

    @Test
    void aPrivateKeyInAStringFails() throws IOException {
        assertFails(checkBase(BASE_FILE.replace("\"production\"",
                        "\"-----BEGIN RSA PRIVATE KEY-----\\nMIIEow\\n-----END RSA PRIVATE KEY-----\"")),
                "mode", DefaultSettingsChecks.SECRET);
    }

    @Test
    void aSecretInsideAJsonValueFails() throws IOException {
        assertFails(checkBase(BASE_FILE.replace("[ \"en\" ]", "[ \"en\", \"https://user:hunter2@wms.example.org\" ]")),
                "languages", DefaultSettingsChecks.SECRET);
    }

    @Test
    void aCountryFileMissingABaseKeyFails() throws IOException {
        write(country, COUNTRY_SETTINGS, """
                { "values": { "mode": "production", "zoom": 7, "layerEnabled": true, "languages": [ "da", "en" ],
                              "country": "DK" } }
                """);

        assertFails(checkWithCountry(), "docsUrl", DefaultSettingsChecks.MISSING_KEY);
    }

    @Test
    void aListedUseBaseDefaultKeyPassesAndIsPrinted() throws IOException {
        write(country, COUNTRY_SETTINGS, """
                { "values": { "mode": "production", "zoom": 7, "layerEnabled": true, "languages": [ "da", "en" ],
                              "country": "DK" },
                  "useBaseDefault": [ { "key": "docsUrl", "reason": "the base product's documentation" } ] }
                """);

        DefaultSettingsChecks.Result result = checkWithCountry();

        assertTrue(result.errors().isEmpty(), result.report());
        assertEquals(List.of(new DefaultSettingsChecks.UseBaseDefault("docsUrl", "the base product's documentation")),
                result.useBaseDefault());
        assertTrue(result.report().contains("useBaseDefault: docsUrl — the base product's documentation"),
                result.report());
    }

    @Test
    void aUseBaseDefaultWithoutAReasonFails() throws IOException {
        write(country, COUNTRY_SETTINGS, """
                { "values": { "mode": "production", "zoom": 7, "layerEnabled": true, "languages": [ "da", "en" ],
                              "country": "DK" },
                  "useBaseDefault": [ { "key": "docsUrl", "reason": " " } ] }
                """);

        assertFails(checkWithCountry(), "docsUrl", DefaultSettingsChecks.USE_BASE_DEFAULT);
    }

    @Test
    void aUseBaseDefaultOfARequiredKeyFails() throws IOException {
        write(country, COUNTRY_SETTINGS, """
                { "values": { "mode": "production", "zoom": 7, "layerEnabled": true, "languages": [ "da", "en" ],
                              "docsUrl": "https://docs.example.dk" },
                  "useBaseDefault": [ { "key": "country", "reason": "the base product's is fine" } ] }
                """);

        assertFails(checkWithCountry(), "country", DefaultSettingsChecks.USE_BASE_DEFAULT);
    }

    @Test
    void aUseBaseDefaultListedTwiceFails() throws IOException {
        write(country, COUNTRY_SETTINGS, """
                { "values": { "mode": "production", "zoom": 7, "layerEnabled": true, "languages": [ "da", "en" ],
                              "country": "DK" },
                  "useBaseDefault": [ { "key": "docsUrl", "reason": "one" }, { "key": "docsUrl", "reason": "two" } ] }
                """);

        assertFails(checkWithCountry(), "docsUrl", DefaultSettingsChecks.USE_BASE_DEFAULT);
    }

    @Test
    void aRequiredKeyMissingFromTheFileFails() throws IOException {
        assertFails(checkBase(BASE_FILE.replace(", \"country\": \"XX\"", "")), "country",
                DefaultSettingsChecks.REQUIRED);
    }

    @Test
    void aMalformedFileFails() throws IOException {
        assertFails(checkBase("{ \"values\": [ \"mode\" ] }"), "values", DefaultSettingsChecks.FORMAT);
        assertFails(checkBase("{ \"values\": { }, \"comment\": \"no\" }"), "comment", DefaultSettingsChecks.FORMAT);
        assertFails(checkBase("{ \"values\": { }, \"useBaseDefault\": [ ] }"), "useBaseDefault",
                DefaultSettingsChecks.FORMAT);
        assertFails(checkBase("[ { \"key\": \"mode\", \"value\": \"production\" } ]"), "",
                DefaultSettingsChecks.FORMAT);
        assertFails(checkBase("{ \"values\": "), "", DefaultSettingsChecks.FORMAT);
    }

    @Test
    void aSettingsFileTwiceOnTheClassPathFails() throws IOException {
        write(country, BASE_SETTINGS, BASE_FILE);

        assertFails(checkBase(BASE_FILE), "", DefaultSettingsChecks.FORMAT);
    }

    @Test
    void anHttpUrlToAnotherHostWarnsAndDoesNotFail() throws IOException {
        DefaultSettingsChecks.Result result = checkBase(BASE_FILE.replace("https://docs.example.org",
                "http://docs.example.org"));

        assertTrue(result.errors().isEmpty(), result.report());
        assertEquals(1, result.warnings().size(), result.report());
        assertEquals(DefaultSettingsChecks.HTTPS, result.warnings().getFirst().rule());
        assertEquals("docsUrl", result.warnings().getFirst().key());
    }

    @Test
    void anHttpUrlInsideALongerStringWarns() throws IOException {
        DefaultSettingsChecks.Result result = checkBase(BASE_FILE.replace("https://docs.example.org",
                "local http://localhost/docs, else see http://docs.example.org"));

        assertTrue(result.errors().isEmpty(), result.report());
        assertEquals(1, result.warnings().size(), result.report());
        assertTrue(result.warnings().getFirst().detail().contains("docs.example.org"), result.report());
    }

    @Test
    void anHttpUrlToThisMachinePassesSilently() throws IOException {
        DefaultSettingsChecks.Result result = checkBase(BASE_FILE.replace("https://docs.example.org",
                "http://localhost:8080/auth"));

        assertTrue(result.errors().isEmpty(), result.report());
        assertTrue(result.warnings().isEmpty(), result.report());
    }

    @Test
    void aCountryKeyDeclaredOnlyInTheCountryCatalogPasses() throws IOException {
        write(country, COUNTRY_CATALOG, new SettingsCatalog(List.of(
                entry("dk.navtexStations", Setting.Type.json, Setting.Scope.operator, false))).toJson());
        write(country, COUNTRY_SETTINGS, """
                { "values": { "mode": "production", "zoom": 7, "layerEnabled": true, "languages": [ "da", "en" ],
                              "docsUrl": "https://docs.example.dk", "country": "DK",
                              "dk.navtexStations": [ "Skagen" ] } }
                """);

        DefaultSettingsChecks.Result result = checkWithCountry();

        assertTrue(result.errors().isEmpty(), result.report());
        assertEquals(7, result.valuesRead().get(COUNTRY_SETTINGS));
    }

    @Test
    void aCountryKeyTheCountryCatalogLacksFails() throws IOException {
        write(country, COUNTRY_SETTINGS, """
                { "values": { "mode": "production", "zoom": 7, "layerEnabled": true, "languages": [ "da", "en" ],
                              "docsUrl": "https://docs.example.dk", "country": "DK",
                              "dk.navtexStations": [ "Skagen" ] } }
                """);

        assertFails(checkWithCountry(), "dk.navtexStations", DefaultSettingsChecks.UNDECLARED_KEY);
    }

    private static void assertFails(DefaultSettingsChecks.Result result, String key, String rule) {
        assertTrue(result.errors().stream().anyMatch(f -> f.key().equals(key) && f.rule().equals(rule)),
                "expected " + rule + " on '" + key + "':\n" + result.report());
    }

    private static String withValue(String key, String json) {
        return BASE_FILE.replace("\"mode\": \"production\"", "\"mode\": \"production\", \"" + key + "\": " + json);
    }

    /** Checks the base product's fixture catalog and the given base product file */
    private DefaultSettingsChecks.Result checkBase(String baseFile) throws IOException {
        write(base, BASE_CATALOG, CATALOG.toJson());
        write(base, BASE_SETTINGS, baseFile);
        try (URLClassLoader cl = classLoader(base, country)) {
            return DefaultSettingsChecks.check(cl);
        }
    }

    /** Checks the fixture base product and the country files the test wrote */
    private DefaultSettingsChecks.Result checkWithCountry() throws IOException {
        return checkBase(BASE_FILE);
    }

    private static SettingsCatalog.Entry entry(String key, Setting.Type type, Setting.Scope scope, boolean required) {
        boolean operator = scope == Setting.Scope.operator;
        return new SettingsCatalog.Entry(key, type, null, null, scope, false, operator, required, true, "4.0.74",
                "A " + key, List.of());
    }
}
