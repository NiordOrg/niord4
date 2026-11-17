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

import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads the default settings from the class path (docs/code-data-config-plan.md §5.1, §5.2): the catalogs, which
 * give every key its type, scope and flags, and the settings file, which gives the values.
 *
 * <p>The catalog is the base product's {@code defaults/base/settings-catalog.json}, then a country's
 * {@code defaults/country/settings-catalog.json} for the keys the base product lacks. The settings file is the first
 * of these that exists; it replaces the others whole (R-CDC7):
 * <ol>
 *     <li>the country's {@code defaults/country/settings.json} (source {@code country}); a key it lists in
 *         {@code useBaseDefault} takes the base product default;</li>
 *     <li>transitional: the first root-level {@code niord.json} on the class path, today's country file in its list
 *         form {@code [{ "key": …, "value": … }, …]}, of which only the key and the value are read (source
 *         {@code root}). This lets a country take this base product before it has moved its settings under
 *         {@code defaults/country/}. <b>The read is deleted once niord-base's test tree
 *         ({@code src/test/resources/niord.json}) moves to {@code defaults/}</b>
 *         (docs/code-data-config-plan.md §5.2): neither country carries a root {@code niord.json} since its C6;</li>
 *     <li>the base product's {@code defaults/base/settings.json} (source {@code base}).</li>
 * </ol>
 * A key the file does not set takes the base product's value ({@code baseValue}, source {@code base}), else the code
 * default (source {@code code}). The server's {@code ${niord.home}/niord.json} wins over all of them
 * ({@link #withOverlay}, source {@code home}). A file gives values only: the type and the flags always come from the
 * catalogs. A key no catalog declares is skipped with a warning, a {@code state} key likewise, and a value that does
 * not fit its type with an error.
 *
 * <p>Pure: no Spring, no database. {@code SettingsService} reads it in its constructor and adds what it has and the
 * database lacks.
 */
public final class DefaultSettings {

    public static final String SOURCE_COUNTRY = DefaultsLedger.SOURCE_COUNTRY;
    public static final String SOURCE_ROOT = DefaultsLedger.SOURCE_ROOT;
    public static final String SOURCE_BASE = DefaultsLedger.SOURCE_BASE;
    public static final String SOURCE_HOME = DefaultsLedger.SOURCE_HOME;
    public static final String SOURCE_CODE = DefaultsLedger.SOURCE_CODE;

    /** The base product's settings file on the class path */
    public static final String BASE_SETTINGS = "defaults/base/settings.json";

    /** A country's settings file on the class path */
    public static final String COUNTRY_SETTINGS = "defaults/country/settings.json";

    /**
     * The transitional country settings file, at the root of the class path (deleted once niord-base's test tree
     * moves its own to {@code defaults/}, §5.2)
     */
    public static final String ROOT_SETTINGS = "niord.json";

    private static final Logger log = LoggerFactory.getLogger(DefaultSettings.class);

    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    private final Map<String, SettingsCatalog.Entry> catalog;
    private final Map<String, Object> values;
    private final Map<String, String> sources;

    private DefaultSettings(Map<String, SettingsCatalog.Entry> catalog, Map<String, Object> values,
                            Map<String, String> sources) {
        this.catalog = catalog;
        this.values = values;
        this.sources = sources;
    }

    /**
     * Reads the catalogs and the settings file from the class path
     *
     * @param cl the class loader to read from
     * @return the default settings
     * @throws IllegalStateException when the base product's catalog or settings file is missing, or a file is not
     *                               valid
     */
    public static DefaultSettings read(ClassLoader cl) {
        Map<String, SettingsCatalog.Entry> catalog = new LinkedHashMap<>();
        URL baseCatalog = required(cl, SettingsCatalog.BASE_RESOURCE);
        SettingsCatalog.read(baseCatalog).entries().forEach(e -> catalog.put(e.key(), e));
        URL countryCatalog = cl.getResource(SettingsCatalog.COUNTRY_RESOURCE);
        if (countryCatalog != null) {
            SettingsCatalog.read(countryCatalog).entries().forEach(e -> catalog.putIfAbsent(e.key(), e));
        }

        URL baseFile = required(cl, BASE_SETTINGS);
        JsonNode base = readJson(baseFile);
        URL countryFile = cl.getResource(COUNTRY_SETTINGS);
        URL rootFile = cl.getResource(ROOT_SETTINGS);

        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        if (countryFile != null) {
            if (rootFile != null) {
                log.info("Ignoring the root-level {}: {} is the country's settings file", rootFile, countryFile);
            }
            JsonNode country = readJson(countryFile);
            putValues(countryFile, valuesOf(countryFile, country), catalog, values, sources, SOURCE_COUNTRY);
            JsonNode useBaseDefault = country.get("useBaseDefault");
            if (useBaseDefault != null && useBaseDefault.isArray()) {
                for (JsonNode entry : useBaseDefault.values()) {
                    String key = stringMember(entry, "key");
                    if (key != null && sources.remove(key) != null) {
                        values.remove(key);
                        log.warn("{} sets {} and lists it in useBaseDefault: the base product default is used",
                                countryFile, key);
                    }
                }
            }
            warnOfMissingRequired(countryFile, catalog, sources);
        } else if (rootFile != null) {
            log.info("Reading the root-level {} as the country's settings file (transitional, until the country "
                    + "has moved its settings to {})", rootFile, COUNTRY_SETTINGS);
            putValues(rootFile, legacyValues(rootFile, readJson(rootFile)), catalog, values, sources, SOURCE_ROOT);
            warnOfMissingRequired(rootFile, catalog, sources);
        } else {
            putValues(baseFile, valuesOf(baseFile, base), catalog, values, sources, SOURCE_BASE);
        }
        return new DefaultSettings(Collections.unmodifiableMap(catalog), values, sources);
    }

    /**
     * Returns a copy in which the server's own settings file wins: each key the file sets that a catalog declares
     * and whose value fits its type (source {@code home}). The file gives values only.
     *
     * @param file   the file, for the log
     * @param values its values by key, e.g. from {@link #readLegacy}
     * @return the copy
     */
    public DefaultSettings withOverlay(Object file, Map<String, Object> values) {
        Map<String, Object> newValues = new LinkedHashMap<>(this.values);
        Map<String, String> newSources = new LinkedHashMap<>(this.sources);
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        values.forEach((key, value) -> nodes.put(key, value == null ? MAPPER.nullNode() : MAPPER.valueToTree(value)));
        putValues(file, nodes, catalog, newValues, newSources, SOURCE_HOME);
        return new DefaultSettings(catalog, newValues, newSources);
    }

    /**
     * Reads a settings file in the legacy list form {@code [{ "key": …, "value": … }, …]}: the root-level
     * {@code niord.json} and the server's {@code ${niord.home}/niord.json}. Only the key and the value are read.
     *
     * @param name the file's name, for a message
     * @param in   its content
     * @return its values by key, in file order, as JSON values
     * @throws IllegalStateException when it is not a list of settings
     */
    public static Map<String, Object> readLegacy(String name, InputStream in) {
        JsonNode root;
        try {
            root = MAPPER.readTree(in);
        } catch (JacksonException e) {
            throw new IllegalStateException(name + " is not JSON: " + e.getOriginalMessage(), e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        legacyValues(name, root).forEach((key, value) -> result.put(key, toValue(value)));
        return result;
    }

    /** @return the merged catalog by key: the base product's entries, then the country's for the keys it lacks */
    public Map<String, SettingsCatalog.Entry> catalog() {
        return catalog;
    }

    /** @return the key's catalog entry, or null when no catalog declares it */
    public SettingsCatalog.Entry entry(String key) {
        return catalog.get(key);
    }

    /** @return whether a settings file (or the server's overlay) sets the key */
    public boolean hasFileValue(String key) {
        return values.containsKey(key);
    }

    /** @return the values the settings files set, by key, in file order; the server's overlay included */
    public Map<String, Object> fileValues() {
        return Collections.unmodifiableMap(values);
    }

    /**
     * Returns the key's default value, as a JSON value: the settings file's (or the server overlay's), else the base
     * product's value, else the code default
     *
     * @param key the key
     * @return the value, or null; null for a key no catalog declares
     */
    public Object defaultValue(String key) {
        if (values.containsKey(key)) {
            return SettingsCatalog.jsonValue(values.get(key));
        }
        SettingsCatalog.Entry entry = catalog.get(key);
        if (entry == null) {
            return null;
        }
        return SettingsCatalog.jsonValue(entry.baseValue() != null ? entry.baseValue() : entry.codeDefault());
    }

    /**
     * Returns where the key's default value comes from: {@code country}, {@code root}, {@code base}, {@code home} or
     * {@code code}
     *
     * @param key the key
     * @return the source, or null for a key no catalog declares
     */
    public String source(String key) {
        if (sources.containsKey(key)) {
            return sources.get(key);
        }
        SettingsCatalog.Entry entry = catalog.get(key);
        if (entry == null) {
            return null;
        }
        return entry.baseValue() != null ? SOURCE_BASE : SOURCE_CODE;
    }

    /** @return the keys the catalogs declare {@code Password}-typed: their values are never logged */
    public Set<String> passwordKeys() {
        Set<String> keys = new TreeSet<>();
        catalog.values().stream()
                .filter(e -> e.type() == Setting.Type.Password)
                .forEach(e -> keys.add(e.key()));
        return keys;
    }

    // ------------------------------------------------------------------------------------------- //

    /** Adds the declared, non-state, type-fitting values; logs every other one */
    private static void putValues(Object file, Map<String, JsonNode> fileValues,
                                  Map<String, SettingsCatalog.Entry> catalog, Map<String, Object> values,
                                  Map<String, String> sources, String source) {
        fileValues.forEach((key, value) -> {
            SettingsCatalog.Entry entry = catalog.get(key);
            if (entry == null) {
                log.warn("{}: skipped {}: no catalog declares it", file, key);
            } else if (entry.scope() == Setting.Scope.state) {
                log.warn("{}: skipped {}: a state setting is written by the system, never by a settings file",
                        file, key);
            } else if (entry.type() != null && !SettingsCatalog.hasType(entry.type(), value)) {
                log.error("{}: skipped {}: {}-typed, takes {}", file, key, entry.type(),
                        SettingsCatalog.shape(entry.type()));
            } else {
                values.put(key, toValue(value));
                sources.put(key, source);
            }
        });
    }

    /**
     * Warns of each required key the country's settings file does not set (plan §5.2: a country adds the required
     * keys it lacks when it takes this base product). Nothing is refused: the key still resolves to the base
     * product's value, and a database that already has the row keeps it.
     */
    private static void warnOfMissingRequired(Object file, Map<String, SettingsCatalog.Entry> catalog,
                                              Map<String, String> sources) {
        catalog.values().stream()
                .filter(SettingsCatalog.Entry::required)
                .filter(e -> !sources.containsKey(e.key()))
                .forEach(e -> log.warn("{} does not set the required setting {}: a database without the row gets the "
                        + "base product's value; the country's settings file sets it", file, e.key()));
    }

    /** The {@code values} object of a settings file, by key in file order */
    private static Map<String, JsonNode> valuesOf(URL file, JsonNode root) {
        JsonNode values = root.get("values");
        if (values == null || !values.isObject()) {
            throw new IllegalStateException(file + " is not an object with 'values'");
        }
        Map<String, JsonNode> result = new LinkedHashMap<>();
        values.properties().forEach(p -> result.put(p.getKey(), p.getValue()));
        return result;
    }

    /** The key and value of each entry of a settings file in the legacy list form, by key in file order */
    private static Map<String, JsonNode> legacyValues(Object file, JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new IllegalStateException(file + " is not a list of settings");
        }
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode entry : root.values()) {
            String key = stringMember(entry, "key");
            if (key == null || key.isBlank()) {
                throw new IllegalStateException(file + " has an entry without a key: " + entry);
            }
            JsonNode value = entry.get("value");
            result.put(key, value == null ? MAPPER.nullNode() : value);
        }
        return result;
    }

    private static String stringMember(JsonNode node, String name) {
        return node.isObject() && node.path(name).isString() ? node.get(name).asString() : null;
    }

    private static Object toValue(JsonNode node) {
        return node == null || node.isNull() ? null : MAPPER.treeToValue(node, Object.class);
    }

    private static URL required(ClassLoader cl, String name) {
        URL url = cl.getResource(name);
        if (url == null) {
            throw new IllegalStateException(name + " is not on the class path");
        }
        return url;
    }

    private static JsonNode readJson(URL url) {
        try {
            // Uncached, so a jar is closed after the read
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            try (InputStream in = connection.getInputStream()) {
                return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + url, e);
        } catch (JacksonException e) {
            throw new IllegalStateException(url + " is not JSON: " + e.getOriginalMessage(), e);
        }
    }
}
