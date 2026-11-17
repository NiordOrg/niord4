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

import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings catalog (docs/code-data-config-plan.md §5.1): every setting key the code declares, with its type,
 * code default, the base product's value, scope and flags. {@code SettingsCatalogGenerator} makes it from the code;
 * the base product commits it as {@code defaults/base/settings-catalog.json}, and a country as
 * {@code defaults/country/settings-catalog.json}.
 *
 * <p>The written form is deterministic, so a catalog generated twice from the same code is the same text: an object
 * with one member, {@code settings}, the entries sorted by key, each with its members in the order of
 * {@link Entry}, nulls written, two-space indent, LF line ends and a final newline.
 *
 * <p>In niord-base's main jar: {@code SettingsService} reads the catalogs with it at start-up, and a country's build
 * reads and generates catalogs with the same code. The type rule of a value ({@link #hasType}) is here too, so the
 * build's checks and the runtime validate a value alike.
 */
public final class SettingsCatalog {

    /** The base product's catalog on the class path */
    public static final String BASE_RESOURCE = "defaults/base/settings-catalog.json";

    /** A country's catalog on the class path */
    public static final String COUNTRY_RESOURCE = "defaults/country/settings-catalog.json";

    static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    private static final DefaultIndenter LF_INDENTER = new DefaultIndenter("  ", "\n");

    private static final DefaultPrettyPrinter PRETTY_PRINTER = new DefaultPrettyPrinter(
            Separators.createDefaultInstance().withObjectNameValueSpacing(Separators.Spacing.AFTER))
            .withObjectIndenter(LF_INDENTER)
            .withArrayIndenter(LF_INDENTER);

    /**
     * A setting key as the catalog lists it
     *
     * @param key          the key
     * @param type         the type
     * @param codeDefault  the code's default, a JSON value (a number, a boolean, a string, a list, a map), or null
     * @param baseValue    the base product's settings file value, a JSON value, or null when the file has none
     * @param scope        operator or state
     * @param web          whether {@code GET /rest/settings/web} serves it
     * @param editable     whether the Settings page shows it
     * @param required     whether a settings file must set it
     * @param cached       whether a read is cached
     * @param introducedIn the release whose catalog first listed the key
     * @param description  the description
     * @param declaredIn   the fully qualified names of the classes that declare the key, sorted and distinct; start-up
     *                     adds a key only on a node where one of them runs (docs/code-data-config-plan.md §5.3)
     */
    public record Entry(String key, Setting.Type type, Object codeDefault, Object baseValue, Setting.Scope scope,
                        boolean web, boolean editable, boolean required, boolean cached, String introducedIn,
                        String description, List<String> declaredIn) {

        public Entry {
            declaredIn = declaredIn == null ? List.of() : List.copyOf(declaredIn);
        }
    }

    private final List<Entry> entries;

    /**
     * Creates a catalog of the entries, sorted by key
     *
     * @param entries the entries
     */
    public SettingsCatalog(List<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(Entry::key));
        this.entries = Collections.unmodifiableList(sorted);
    }

    /** @return the entries, sorted by key */
    public List<Entry> entries() {
        return entries;
    }

    /** @return the entries by key, in key order */
    public Map<String, Entry> byKey() {
        Map<String, Entry> byKey = new LinkedHashMap<>();
        entries.forEach(e -> byKey.put(e.key(), e));
        return byKey;
    }

    /** @return the catalog in its written form */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode settings = root.putArray("settings");
        for (Entry e : entries) {
            ObjectNode node = settings.addObject();
            node.put("key", e.key());
            node.put("type", e.type() == null ? null : e.type().name());
            node.set("codeDefault", toTree(e.codeDefault()));
            node.set("baseValue", toTree(e.baseValue()));
            node.put("scope", e.scope() == null ? null : e.scope().name());
            node.put("web", e.web());
            node.put("editable", e.editable());
            node.put("required", e.required());
            node.put("cached", e.cached());
            node.put("introducedIn", e.introducedIn());
            node.put("description", e.description());
            ArrayNode declaredIn = node.putArray("declaredIn");
            e.declaredIn().forEach(declaredIn::add);
        }
        return MAPPER.writer().with(PRETTY_PRINTER).writeValueAsString(root) + "\n";
    }

    /**
     * Writes the catalog in its written form, UTF-8
     *
     * @param file the file
     */
    public void write(Path file) {
        try {
            Files.writeString(file, toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }

    /**
     * Reads a catalog file
     *
     * @param file the file
     * @return the catalog
     */
    public static SettingsCatalog read(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /**
     * Reads a catalog from the class path or a jar
     *
     * @param url the catalog's URL
     * @return the catalog
     */
    public static SettingsCatalog read(URL url) {
        try {
            // Uncached, so a jar is closed after the read
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            try (InputStream in = connection.getInputStream()) {
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + url, e);
        }
    }

    /**
     * Parses a catalog's written form
     *
     * @param json the catalog
     * @return the catalog
     * @throws IllegalArgumentException when it is not a catalog
     */
    public static SettingsCatalog parse(String json) {
        JsonNode root = MAPPER.readTree(json);
        JsonNode settings = root == null ? null : root.get("settings");
        if (settings == null || !settings.isArray()) {
            throw new IllegalArgumentException("A settings catalog is an object with a 'settings' array");
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode node : settings.values()) {
            String type = string(node, "type");
            String scope = string(node, "scope");
            entries.add(new Entry(
                    string(node, "key"),
                    type == null ? null : Setting.Type.valueOf(type),
                    toValue(node.get("codeDefault")),
                    toValue(node.get("baseValue")),
                    scope == null ? null : Setting.Scope.valueOf(scope),
                    node.path("web").booleanValue(false),
                    node.path("editable").booleanValue(false),
                    node.path("required").booleanValue(false),
                    node.path("cached").booleanValue(false),
                    string(node, "introducedIn"),
                    string(node, "description"),
                    strings(node, "declaredIn")));
        }
        return new SettingsCatalog(entries);
    }

    /**
     * Compares two written forms, ignoring carriage returns, so a checkout that turns LF into CRLF still compares
     *
     * @param a one text
     * @param b the other text
     * @return whether they are the same apart from carriage returns
     */
    public static boolean sameText(String a, String b) {
        return a.replace("\r", "").equals(b.replace("\r", ""));
    }

    /**
     * Returns a value as the catalog holds it: the Java form of its JSON (Integer, Long, Double, Boolean, String,
     * List, Map), so a value read from a catalog equals the same value generated from the code. A {@link Path} is
     * its string with forward slashes.
     *
     * @param value the value
     * @return the JSON value, or null
     */
    public static Object jsonValue(Object value) {
        return toValue(toTree(value));
    }

    private static JsonNode toTree(Object value) {
        if (value instanceof Path path) {
            value = path.toString().replace('\\', '/');
        }
        return value == null ? MAPPER.nullNode() : MAPPER.readTree(MAPPER.writeValueAsString(value));
    }

    private static Object toValue(JsonNode node) {
        return node == null || node.isNull() ? null : MAPPER.treeToValue(node, Object.class);
    }

    /**
     * Returns whether a value has the type's JSON form (docs/code-data-config-plan.md §5.5): a string for
     * {@code String}, {@code Password} and {@code Path}; an integral number in {@code int} range for {@code Integer};
     * an integral number for {@code Long} and {@code Date}; a number for {@code Float} and {@code Double}; a boolean
     * for {@code Boolean}; anything for {@code json}. {@code null} fits every type.
     *
     * @param type  the type
     * @param value the value as JSON
     * @return whether the value fits the type
     */
    public static boolean hasType(Setting.Type type, JsonNode value) {
        if (value == null || value.isNull()) {
            return true;
        }
        return switch (type) {
            case String, Password, Path -> value.isString();
            case Integer -> value.isIntegralNumber() && value.canConvertToInt();
            case Long, Date -> value.isIntegralNumber() && value.canConvertToLong();
            case Float, Double -> value.isNumber();
            case Boolean -> value.isBoolean();
            case json -> true;
        };
    }

    /**
     * Returns whether a Java value has the type's JSON form, see {@link #hasType(Setting.Type, JsonNode)}
     *
     * @param type  the type
     * @param value the value, e.g. as a request body or a database row holds it; null fits every type
     * @return whether the value fits the type
     */
    public static boolean hasType(Setting.Type type, Object value) {
        return value == null || hasType(type, toTree(value));
    }

    /**
     * Returns the JSON form a type takes, for a message
     *
     * @param type the type
     * @return e.g. {@code a string}
     */
    public static String shape(Setting.Type type) {
        return switch (type) {
            case String, Password, Path -> "a string";
            case Integer -> "an integral number in int range";
            case Long, Date -> "an integral number";
            case Float, Double -> "a number";
            case Boolean -> "true or false";
            case json -> "any JSON value";
        };
    }

    private static List<String> strings(JsonNode node, String name) {
        JsonNode values = node.get(name);
        if (values == null || !values.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        values.values().forEach(v -> result.add(v.asString()));
        return result;
    }

    private static String string(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asString();
    }
}
