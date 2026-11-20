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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layering of the default texts (docs/code-data-config-plan.md §4.1): country, then the base product's English
 * or the language pack, merged key by key; a root-level bundle is not read. Each class-path root is a {@code @TempDir}
 * folder behind a class loader with no parent, so only the files a test writes exist.
 */
class DefaultTextsTest {

    private static final String[] WEB = {"web"};
    private static final String[] MAIL = {"mail"};

    @TempDir
    Path first;

    @TempDir
    Path second;

    @Test
    void theCountryWinsOverTheBaseProductKeyByKey() throws IOException {
        write(first, "defaults/country/texts/en/web.properties", "a = country a\n");
        write(first, "defaults/base/texts/en/web.properties", "a = base a\nb = base b\n");

        DefaultTexts.Layered layered = read(WEB, new String[] {"en"}, "en", first);

        assertEquals(new DefaultTexts.Text("country a", DefaultTexts.SOURCE_COUNTRY), layered.text("web", "en", "a"));
        assertEquals(new DefaultTexts.Text("base b", DefaultTexts.SOURCE_BASE), layered.text("web", "en", "b"));
        assertEquals(2, layered.texts("web", "en").size());
    }

    /**
     * The transitional root-level layer is gone (NG-B): a root-level {@code <bundle>_<lang>.properties} adds no key,
     * gives no language, and is no default-language file.
     */
    @Test
    void aRootLevelBundleIsNotRead() throws IOException {
        write(first, "defaults/base/texts/en/web.properties", "a = base a\n");
        write(first, "web_en.properties", "a = root a\nc = root c\n");
        write(first, "web_da.properties", "a = rod a\n");

        DefaultTexts.Layered layered = read(WEB, new String[] {"en", "da"}, "en", first);

        assertEquals(new DefaultTexts.Text("base a", DefaultTexts.SOURCE_BASE), layered.text("web", "en", "a"));
        assertNull(layered.text("web", "en", "c"), "a key only a root-level bundle has gets no text");
        assertEquals(Set.of("en"), layered.languages("web"));

        write(second, "mail_en.properties", "mail.dk.own = Own key\n");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> read(MAIL, new String[] {"en"}, "en", second));
        assertTrue(e.getMessage().contains("defaults/base/texts/en/mail.properties"), e.getMessage());
    }

    @Test
    void countryTextsInDanishWinOverTheDanishPack() throws IOException {
        write(first, "defaults/base/texts/en/web.properties", "a = A\n");
        write(first, "defaults/country/texts/da/web.properties", "a = land a\n");
        write(second, "defaults/packs/da/web.properties", "a = pakke a\nb = pakke b\n");

        DefaultTexts.Layered layered = read(WEB, new String[] {"en", "da"}, "en", first, second);

        assertEquals(new DefaultTexts.Text("land a", DefaultTexts.SOURCE_COUNTRY), layered.text("web", "da", "a"));
        assertEquals(new DefaultTexts.Text("pakke b", "pack:da"), layered.text("web", "da", "b"));
        assertEquals(new DefaultTexts.Text("A", DefaultTexts.SOURCE_BASE), layered.text("web", "en", "a"));
    }

    @Test
    void valuesAreReadAsUtf8() throws IOException {
        write(first, "defaults/base/texts/en/web.properties", "a = A\n");
        write(first, "defaults/packs/da/web.properties", "a = Aktive grønlandske navigationsadvarsler\n");

        DefaultTexts.Layered layered = read(WEB, new String[] {"en", "da"}, "en", first);

        assertEquals("Aktive grønlandske navigationsadvarsler", layered.text("web", "da", "a").value());
    }

    /**
     * Kept fatal (docs/code-data-config-plan.md §4.2): the default language must have a file for every bundle in
     * either layer.
     */
    @Test
    void aMissingDefaultLanguageFileFailsNamingThePathsLookedAt() throws IOException {
        write(first, "defaults/packs/da/web.properties", "a = A\n");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> read(WEB, new String[] {"en", "da"}, "en", first));

        assertEquals("No default-language text for the 'web' dictionary: neither "
                + "defaults/country/texts/en/web.properties nor defaults/base/texts/en/web.properties is on the class "
                + "path", e.getMessage());
        assertFalse(e.getMessage().contains("web_en.properties"), e.getMessage());
    }

    @Test
    void aMissingDefaultLanguageFileInAnotherDefaultLanguageNamesThePackPath() throws IOException {
        write(first, "defaults/base/texts/en/web.properties", "a = A\n");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> read(WEB, new String[] {"da", "en"}, "da", first));

        assertTrue(e.getMessage().contains("defaults/packs/da/web.properties"), e.getMessage());
    }

    @Test
    void aMissingNonDefaultLanguageContributesNothing() throws IOException {
        write(first, "defaults/base/texts/en/web.properties", "a = A\n");

        DefaultTexts.Layered layered = read(WEB, new String[] {"en", "kl"}, "en", first);

        assertEquals(Set.of("en"), layered.languages("web"));
        assertTrue(layered.texts("web", "kl").isEmpty());
        assertNull(layered.text("web", "kl", "a"));
    }

    @Test
    void sha256IsTheLowerCaseHexOfTheUtf8Bytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DefaultTexts.sha256("abc"));
    }

    // ------------------------------------------------------------------------------------------- //

    private static DefaultTexts.Layered read(String[] bundles, String[] languages, String defaultLanguage,
                                             Path... roots) throws IOException {
        try (URLClassLoader cl = classLoader(roots)) {
            return DefaultTexts.read(cl, bundles, languages, defaultLanguage);
        }
    }

    static URLClassLoader classLoader(Path... roots) throws IOException {
        URL[] urls = new URL[roots.length];
        for (int i = 0; i < roots.length; i++) {
            urls[i] = roots[i].toUri().toURL();
        }
        return new URLClassLoader(urls, null);
    }

    static void write(Path root, String name, String content) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
