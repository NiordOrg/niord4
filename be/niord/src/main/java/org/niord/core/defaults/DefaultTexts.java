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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads the default backend texts from the class path, layered key by key (docs/code-data-config-plan.md §4.1).
 *
 * <p>For each bundle, language and key the default value is the first one found:
 * <ol>
 *     <li>the country's text, {@code defaults/country/texts/<lang>/<bundle>.properties} (source {@code country});</li>
 *     <li>for {@code en} the base product's {@code defaults/base/texts/en/<bundle>.properties} (source {@code base}),
 *         for any other language the language pack's {@code defaults/packs/<lang>/<bundle>.properties}
 *         (source {@code pack:<lang>}).</li>
 * </ol>
 * Both layers are read with {@code getResource}: {@link DefaultsGuard} has already refused a second copy. No other
 * file is read: a root-level {@code <bundle>_<lang>.properties} is not (the transitional third layer was removed in
 * NG-B, after GL's bump onto the staged packs, niord-gl #10; docs/code-data-config-plan.md §4.1).
 *
 * <p>A language with no file in either layer contributes nothing and logs a warning; at runtime the default language
 * serves its texts. The default language must have a file in either layer for every bundle (DICT1).
 *
 * <p>Pure: no Spring, no database. {@code DictionaryService} adds what the result has and the database lacks.
 */
public final class DefaultTexts {

    public static final String SOURCE_COUNTRY = "country";
    public static final String SOURCE_BASE = "base";
    public static final String SOURCE_PACK_PREFIX = "pack:";

    /** The language the base product's own texts are in */
    public static final String BASE_LANGUAGE = "en";

    private static final Logger log = LoggerFactory.getLogger(DefaultTexts.class);

    private DefaultTexts() {
    }

    /** A default value and the layer it came from */
    public record Text(String value, String source) {
    }

    /** The layered defaults: bundle -> language -> key -> text, each level in a stable order */
    public static final class Layered {

        private final Map<String, Map<String, Map<String, Text>>> texts;

        Layered(Map<String, Map<String, Map<String, Text>>> texts) {
            this.texts = texts;
        }

        /** Returns the bundles, in the order they were read */
        public Set<String> bundles() {
            return Collections.unmodifiableSet(texts.keySet());
        }

        /** Returns the languages of the bundle that have at least one text */
        public Set<String> languages(String bundle) {
            return Collections.unmodifiableSet(texts.getOrDefault(bundle, Map.of()).keySet());
        }

        /** Returns the texts of the bundle in the language, by key; empty when there are none */
        public Map<String, Text> texts(String bundle, String lang) {
            return Collections.unmodifiableMap(texts.getOrDefault(bundle, Map.of()).getOrDefault(lang, Map.of()));
        }

        /** Returns the text of the key, or null */
        public Text text(String bundle, String lang, String key) {
            return texts(bundle, lang).get(key);
        }
    }

    /**
     * Reads the layered defaults of the given bundles and languages.
     *
     * @param cl              the class loader to read from
     * @param bundles         the bundle names, e.g. {@code web}
     * @param languages       the model languages
     * @param defaultLanguage the default language, which must have a file in either layer for every bundle
     * @return the layered defaults
     * @throws IllegalStateException when the default language has no file for a bundle in either layer
     */
    public static Layered read(ClassLoader cl, String[] bundles, String[] languages, String defaultLanguage) {
        Map<String, Map<String, Map<String, Text>>> result = new LinkedHashMap<>();
        for (String bundle : bundles) {
            Map<String, Map<String, Text>> byLang = new LinkedHashMap<>();
            for (String lang : languages) {
                Map<String, Text> texts = new TreeMap<>();
                boolean found = false;

                String countryPath = countryPath(bundle, lang);
                found |= addFirst(texts, cl.getResource(countryPath), SOURCE_COUNTRY);

                String languagePath = languagePath(bundle, lang);
                found |= addFirst(texts, cl.getResource(languagePath),
                        BASE_LANGUAGE.equals(lang) ? SOURCE_BASE : SOURCE_PACK_PREFIX + lang);

                if (!found) {
                    if (lang.equals(defaultLanguage)) {
                        throw new IllegalStateException("No default-language text for the '" + bundle
                                + "' dictionary: neither " + countryPath + " nor " + languagePath
                                + " is on the class path");
                    }
                    log.warn("No default texts for the '{}' dictionary in '{}' (looked at {} and {}); "
                            + "missing texts use the default language", bundle, lang, countryPath, languagePath);
                    continue;
                }
                if (!texts.isEmpty()) {
                    byLang.put(lang, texts);
                }
            }
            result.put(bundle, byLang);
        }
        return new Layered(result);
    }

    /**
     * Returns the sha-256 of the value's UTF-8 bytes, in lower-case hex
     *
     * @param value the value
     * @return the hash
     */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The country's texts of the bundle in the language */
    static String countryPath(String bundle, String lang) {
        return "defaults/country/texts/" + lang + "/" + bundle + ".properties";
    }

    /** The base product's English texts of the bundle */
    static String basePath(String bundle) {
        return "defaults/base/texts/" + BASE_LANGUAGE + "/" + bundle + ".properties";
    }

    /** The language pack's texts of the bundle */
    static String packPath(String bundle, String lang) {
        return "defaults/packs/" + lang + "/" + bundle + ".properties";
    }

    /** Layer 2: the base product's texts for en, else the language pack's */
    static String languagePath(String bundle, String lang) {
        return BASE_LANGUAGE.equals(lang) ? basePath(bundle) : packPath(bundle, lang);
    }

    /** Adds the keys of the file that the result does not have yet; returns whether the file exists */
    private static boolean addFirst(Map<String, Text> texts, URL url, String source) {
        if (url == null) {
            return false;
        }
        Properties props = load(url);
        for (String key : props.stringPropertyNames()) {
            texts.putIfAbsent(key, new Text(props.getProperty(key), source));
        }
        return true;
    }

    private static Properties load(URL url) {
        try (InputStream in = url.openStream()) {
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return props;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read default texts " + url, e);
        }
    }
}
