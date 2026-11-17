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

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.core.defaults.DefaultTextsTest.classLoader;
import static org.niord.core.defaults.DefaultTextsTest.write;

/**
 * The duplicate-defaults guard (docs/code-data-config-plan.md §3.3): a known file under {@code defaults/} from two
 * places, {@code defaults/country/} from two places, or a {@code defaults/packs/en/} fails start-up.
 */
class DefaultsGuardTest {

    private static final String[] BUNDLES = {"web", "message", "pdf", "mail", "template"};
    private static final String[] LANGUAGES = {"da", "en"};

    @TempDir
    Path base;

    @TempDir
    Path country;

    @Test
    void oneCopyOfEachKnownFilePasses() throws IOException {
        write(base, "defaults/base/README.md", "base");
        for (String bundle : BUNDLES) {
            write(base, "defaults/base/texts/en/" + bundle + ".properties", "a = A\n");
        }
        write(base, "defaults/base/settings.json", "{ \"values\": { } }");
        write(base, "defaults/base/settings-catalog.json", "{ \"settings\": [ ] }");
        write(country, "defaults/country/README.md", "country");
        write(country, "defaults/country/settings.json", "{ \"values\": { } }");
        write(country, "defaults/country/settings-catalog.json", "{ \"settings\": [ ] }");
        write(country, "defaults/country/texts/en/mail.properties", "a = A\n");
        write(country, "defaults/country/texts/da/mail.properties", "a = A\n");
        write(country, "defaults/packs/da/web.properties", "a = A\n");
        write(country, "mail_en.properties", "a = A\n");

        assertDoesNotThrow(() -> check(base, country));
    }

    /** A whole-file shadow of a base product bundle, refused by construction. */
    @Test
    void theSameBaseProductFileInTwoLocationsFails() throws IOException {
        write(base, "defaults/base/texts/en/mail.properties", "a = A\n");
        write(country, "defaults/base/texts/en/mail.properties", "a = Shadow\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> check(base, country));

        assertTrue(e.getMessage().contains("defaults/base/texts/en/mail.properties"), e.getMessage());
        assertTrue(e.getMessage().contains(base.getFileName().toString()), "lists the first URL: " + e.getMessage());
        assertTrue(e.getMessage().contains(country.getFileName().toString()), "lists the second URL: " + e.getMessage());
    }

    /** A second copy of the base product's settings file would decide by class-path order which one is read */
    @Test
    void theBaseProductsSettingsFileInTwoLocationsFails() throws IOException {
        write(base, "defaults/base/settings.json", "{ \"values\": { } }");
        write(country, "defaults/base/settings.json", "{ \"values\": { \"mode\": \"test\" } }");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> check(base, country));

        assertTrue(e.getMessage().contains("defaults/base/settings.json is supplied by more than one location"),
                e.getMessage());
    }

    /** Two jars claiming to be the country, each with its settings file */
    @Test
    void twoCountrySettingsFilesFail() throws IOException {
        write(base, "defaults/country/settings.json", "{ \"values\": { } }");
        write(country, "defaults/country/settings.json", "{ \"values\": { } }");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> check(base, country));

        assertTrue(e.getMessage().contains("defaults/country/settings.json is supplied by more than one location"),
                e.getMessage());
        assertTrue(e.getMessage().contains("defaults/country/ is supplied by more than one location"), e.getMessage());
    }

    @Test
    void countryDefaultsFromTwoLocationsFailEvenWithoutACommonFile() throws IOException {
        write(base, "defaults/country/texts/en/web.properties", "a = A\n");
        write(country, "defaults/country/texts/da/web.properties", "a = A\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> check(base, country));

        assertTrue(e.getMessage().contains("defaults/country/ is supplied by more than one location"), e.getMessage());
    }

    @Test
    void anEnglishPackFails() throws IOException {
        write(country, "defaults/packs/en/web.properties", "a = A\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> check(base, country));

        assertTrue(e.getMessage().contains("defaults/packs/en/"), e.getMessage());
    }

    @Test
    void anUnknownNameUnderDefaultsIsNotLookedAt() throws IOException {
        write(base, "defaults/base/notes.txt", "one");
        write(country, "defaults/base/notes.txt", "two");
        write(country, "defaults/country/texts/en/web.properties", "a = A\n");
        write(base, "defaults/country/notes.txt", "not a known name");

        assertDoesNotThrow(() -> check(base, country));
    }

    // ------------------------------------------------------------------------------------------- //
    // checkSettings: the four settings names only, checked in SettingsService's constructor
    // ------------------------------------------------------------------------------------------- //

    @Test
    void oneCopyOfEachSettingsFilePassesTheSettingsCheck() throws IOException {
        write(base, "defaults/base/settings.json", "{ \"values\": { } }");
        write(base, "defaults/base/settings-catalog.json", "{ \"settings\": [ ] }");
        write(country, "defaults/country/settings.json", "{ \"values\": { } }");
        write(country, "defaults/country/settings-catalog.json", "{ \"settings\": [ ] }");
        write(country, "niord.json", "[ ]");

        assertDoesNotThrow(() -> checkSettings(base, country));
    }

    @Test
    void aSettingsFileInTwoLocationsFailsTheSettingsCheck() throws IOException {
        write(base, "defaults/base/settings.json", "{ \"values\": { } }");
        write(country, "defaults/base/settings.json", "{ \"values\": { \"mode\": \"test\" } }");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> checkSettings(base, country));

        assertTrue(e.getMessage().contains("defaults/base/settings.json is supplied by more than one location"),
                e.getMessage());
    }

    @Test
    void countrySettingsFromTwoRootsFailTheSettingsCheck() throws IOException {
        write(base, "defaults/country/settings.json", "{ \"values\": { } }");
        write(country, "defaults/country/settings-catalog.json", "{ \"settings\": [ ] }");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> checkSettings(base, country));

        assertTrue(e.getMessage().contains("defaults/country/ is supplied by more than one location"), e.getMessage());
    }

    @Test
    void theSettingsCheckLooksAtNoTextFile() throws IOException {
        write(base, "defaults/base/texts/en/mail.properties", "a = A\n");
        write(country, "defaults/base/texts/en/mail.properties", "a = Shadow\n");

        assertDoesNotThrow(() -> checkSettings(base, country), "the texts are DictionaryService.init's to check");
    }

    private static void checkSettings(Path... roots) throws IOException {
        try (URLClassLoader cl = classLoader(roots)) {
            DefaultsGuard.checkSettings(cl);
        }
    }

    private static void check(Path... roots) throws IOException {
        try (URLClassLoader cl = classLoader(roots)) {
            DefaultsGuard.check(cl, BUNDLES, LANGUAGES);
        }
    }
}
