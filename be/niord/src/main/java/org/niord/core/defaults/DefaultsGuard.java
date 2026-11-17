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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses to start when a file under {@code defaults/} comes from more than one place
 * (docs/code-data-config-plan.md §3.3). A country wins by the layering's rules, never by shadowing a file, so:
 * <ul>
 *     <li>a known name found at more than one class-path location fails: the old whole-file shadow (a country's
 *         {@code mail_en.properties} hid the base product key {@code mail.safetynet.cancel}) cannot
 *         return under {@code defaults/};</li>
 *     <li>{@code defaults/country/} found in more than one class-path location fails: two jars claim to be the
 *         country;</li>
 *     <li>a {@code defaults/packs/en/} fails: the base product's English is {@code defaults/base/texts/en/},
 *         not a pack (§4.3).</li>
 * </ul>
 * The guard enumerates a bounded list of known names with {@code getResources}; it never scans the class path, so
 * a name it does not know is not looked at. The known names are the READMEs, the texts, and the settings files
 * ({@code settings.json} and {@code settings-catalog.json}, the base product's and the country's).
 *
 * <p>{@code DictionaryService.init} checks every known name; {@code SettingsService}'s constructor checks the four
 * settings names before it reads them ({@link #checkSettings}), so a shadowed settings file fails start-up before
 * any setting is added.
 */
public final class DefaultsGuard {

    /** The settings files and catalogs, the base product's and the country's */
    static final List<String> SETTINGS_NAMES = List.of(
            "defaults/base/settings.json",
            "defaults/base/settings-catalog.json",
            "defaults/country/settings.json",
            "defaults/country/settings-catalog.json");

    private DefaultsGuard() {
    }

    /**
     * Checks the known names under {@code defaults/}.
     *
     * @param cl        the class loader to check
     * @param bundles   the text bundles, e.g. {@code web}
     * @param languages the model languages
     * @throws IllegalStateException listing every offending URL
     */
    public static void check(ClassLoader cl, String[] bundles, String[] languages) {
        List<String> problems = new ArrayList<>();
        Map<String, List<URL>> found = checkNames(cl, knownNames(bundles, languages), problems);

        List<URL> englishPack = new ArrayList<>(distinct(resources(cl, "defaults/packs/en/")));
        found.forEach((name, urls) -> {
            if (name.startsWith("defaults/packs/en/")) {
                englishPack.addAll(urls);
            }
        });
        if (!englishPack.isEmpty()) {
            problems.add("defaults/packs/en/ must not exist: the base product's English texts are "
                    + "defaults/base/texts/en/, not a pack: " + englishPack);
        }

        failOn(problems);
    }

    /**
     * Checks the four settings names: each is supplied by one location at most, and the country's two come from one
     * class-path root.
     *
     * @param cl the class loader to check
     * @throws IllegalStateException listing every offending URL
     */
    public static void checkSettings(ClassLoader cl) {
        List<String> problems = new ArrayList<>();
        checkNames(cl, SETTINGS_NAMES, problems);
        failOn(problems);
    }

    /**
     * Applies the two location rules to the names: a name at more than one location, and {@code defaults/country/}
     * from more than one root
     *
     * @return the locations of each name found
     */
    private static Map<String, List<URL>> checkNames(ClassLoader cl, List<String> names, List<String> problems) {
        Map<String, List<URL>> found = new LinkedHashMap<>();
        for (String name : names) {
            List<URL> urls = distinct(resources(cl, name));
            if (!urls.isEmpty()) {
                found.put(name, urls);
            }
            if (urls.size() > 1) {
                problems.add(name + " is supplied by more than one location: " + urls);
            }
        }

        Set<String> countryRoots = new LinkedHashSet<>();
        List<URL> countryUrls = new ArrayList<>();
        found.forEach((name, urls) -> {
            if (name.startsWith("defaults/country/")) {
                urls.forEach(url -> countryRoots.add(root(url, name)));
                countryUrls.addAll(urls);
            }
        });
        if (countryRoots.size() > 1) {
            problems.add("defaults/country/ is supplied by more than one location " + countryRoots + ": " + countryUrls);
        }
        return found;
    }

    private static void failOn(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The defaults on the class path are ambiguous: "
                    + String.join("; ", problems));
        }
    }

    /** The bounded list of names the guard looks at */
    static List<String> knownNames(String[] bundles, String[] languages) {
        Set<String> names = new LinkedHashSet<>();
        names.add("defaults/base/README.md");
        for (String bundle : bundles) {
            names.add(DefaultTexts.basePath(bundle));
        }
        names.add("defaults/base/settings.json");
        names.add("defaults/base/settings-catalog.json");
        names.add("defaults/country/README.md");
        names.add("defaults/country/settings.json");
        names.add("defaults/country/settings-catalog.json");
        Set<String> packLanguages = new LinkedHashSet<>(List.of(languages));
        packLanguages.add(DefaultTexts.BASE_LANGUAGE);
        for (String lang : languages) {
            for (String bundle : bundles) {
                names.add(DefaultTexts.countryPath(bundle, lang));
            }
        }
        for (String lang : packLanguages) {
            for (String bundle : bundles) {
                names.add(DefaultTexts.packPath(bundle, lang));
            }
        }
        return new ArrayList<>(names);
    }

    /** The class-path root a resource was found in: its URL without the resource name */
    static String root(URL url, String name) {
        String form = url.toExternalForm();
        return form.endsWith(name) ? form.substring(0, form.length() - name.length()) : form;
    }

    private static List<URL> distinct(List<URL> urls) {
        Map<String, URL> byForm = new LinkedHashMap<>();
        urls.forEach(url -> byForm.putIfAbsent(url.toExternalForm(), url));
        return new ArrayList<>(byForm.values());
    }

    private static List<URL> resources(ClassLoader cl, String name) {
        try {
            return Collections.list(cl.getResources(name));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + name, e);
        }
    }
}
