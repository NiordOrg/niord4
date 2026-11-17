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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks of the settings files (docs/code-data-config-plan.md §5.5, R-CDC7, R-CDC8): the base product's
 * {@code defaults/base/settings.json} and a country's {@code defaults/country/settings.json}, held against the
 * catalogs ({@code settings-catalog.json} beside each) before a value can be added to a deployment's database, where
 * it is never changed again.
 *
 * <p>The base product's unit suite runs them ({@code BaseProductSettingsCheckTest}); this class is in niord-base's
 * test-jar, so a country's build runs them over its own class path, its file against the base product's catalog and
 * its own (the plan's C5b and C6). Each finding names the file, the key and the rule. The rules, all errors but the
 * last:
 * <ul>
 *     <li>{@value #FORMAT}: a file is an object with a {@code values} object, and nothing but {@code values} and, in a
 *         country's file, {@code useBaseDefault}; the base product's file has no {@code useBaseDefault} (it is the
 *         base default); each file and catalog is on the class path once.</li>
 *     <li>{@value #UNDECLARED_KEY}: every key a file sets or lists is declared by a catalog.</li>
 *     <li>{@value #STATE_KEY}: no file sets a {@code state} key: only the system writes one.</li>
 *     <li>{@value #TYPE}: a value has its type's JSON form: a string for {@code String}, {@code Password} and
 *         {@code Path}; an integral number in {@code int} range for {@code Integer}; an integral number for
 *         {@code Long} and {@code Date}; a number for {@code Float} and {@code Double}; a boolean for
 *         {@code Boolean}; anything for {@code json}; {@code null} for any type.</li>
 *     <li>{@value #SECRET}: a {@code Password} setting is empty; no string, also inside a {@code json} value, holds a
 *         private key, a JSON Web Token, an AWS access key, a GitHub or Slack token, or a URL with a user and
 *         password. The real value is set on the Settings page or in the server's {@code ${niord.home}/niord.json}.
 *         A finding never shows the value.</li>
 *     <li>{@value #MISSING_KEY}: a country's file sets every key of the base product's file, unless it lists the key
 *         in {@code useBaseDefault} (R-CDC7's completeness, scoped to the base product's file: a key the base product
 *         declares only in code takes its code default and needs no entry).</li>
 *     <li>{@value #USE_BASE_DEFAULT}: a {@code useBaseDefault} entry has a key and a reason, is listed once, and is not
 *         a {@code required} key.</li>
 *     <li>{@value #REQUIRED}: a file sets every {@code required} key of the catalogs it is checked against.</li>
 *     <li>{@value #HTTPS}, a warning: an {@code http://} URL, anywhere in a string and also inside a {@code json}
 *         value, whose host is not this machine ({@code localhost},
 *         {@code 127.*}, {@code ::1}).</li>
 * </ul>
 * The host allow-list of §5.5 waits for the first declaration that has a fixed set of hosts.
 *
 * <p>Pure: no Spring, no database.
 */
public final class DefaultSettingsChecks {

    public static final String FORMAT = "format";
    public static final String UNDECLARED_KEY = "undeclared-key";
    public static final String STATE_KEY = "state-key";
    public static final String TYPE = "type";
    public static final String SECRET = "secret";
    public static final String MISSING_KEY = "missing-key";
    public static final String USE_BASE_DEFAULT = "use-base-default";
    public static final String REQUIRED = "required";
    public static final String HTTPS = "https";

    /** The base product's settings file on the class path */
    public static final String BASE_SETTINGS = "defaults/base/settings.json";

    /** A country's settings file on the class path */
    public static final String COUNTRY_SETTINGS = "defaults/country/settings.json";

    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    /** The secret patterns, by what they find */
    private static final Map<String, Pattern> SECRET_PATTERNS = new LinkedHashMap<>();

    static {
        SECRET_PATTERNS.put("a private key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));
        SECRET_PATTERNS.put("a JSON Web Token", Pattern.compile("eyJ[A-Za-z0-9_-]*\\.[A-Za-z0-9_-]+\\."));
        SECRET_PATTERNS.put("an AWS access key", Pattern.compile("AKIA[0-9A-Z]{16}"));
        SECRET_PATTERNS.put("a GitHub token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"));
        SECRET_PATTERNS.put("a Slack token", Pattern.compile("xox[abprs]-"));
        SECRET_PATTERNS.put("a URL with a user and password",
                Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[^/\\s:@]+:[^/\\s@]+@"));
    }

    /** An {@code http://} URL anywhere in a string, and its host */
    private static final Pattern HTTP_HOST = Pattern.compile("\\bhttp://(\\[[^]]*]|[^/:?#\\s]+)",
            Pattern.CASE_INSENSITIVE);

    private DefaultSettingsChecks() {
    }

    /** A rule a settings file breaks */
    public record Finding(String file, String key, String rule, String detail) {

        @Override
        public String toString() {
            return file + " " + key + ": " + rule + " — " + detail;
        }
    }

    /** A {@code useBaseDefault} entry of a country's file */
    public record UseBaseDefault(String key, String reason) {
    }

    /**
     * What the checks found
     *
     * @param errors         the findings that fail the build
     * @param warnings       the findings that are printed only
     * @param useBaseDefault the keys a country's file leaves to the base product default, printed by every build
     * @param valuesRead     the number of values read, by file
     */
    public record Result(List<Finding> errors, List<Finding> warnings, List<UseBaseDefault> useBaseDefault,
                         Map<String, Integer> valuesRead) {

        /** @return the findings and the {@code useBaseDefault} list, as a build prints them */
        public String report() {
            StringBuilder report = new StringBuilder("Settings checks: ").append(valuesRead).append('\n');
            errors.forEach(f -> report.append("ERROR ").append(f).append('\n'));
            warnings.forEach(f -> report.append("WARNING ").append(f).append('\n'));
            if (useBaseDefault.isEmpty()) {
                report.append("useBaseDefault: none\n");
            }
            useBaseDefault.forEach(u -> report.append("useBaseDefault: ").append(u.key()).append(" — ")
                    .append(u.reason()).append('\n'));
            return report.toString();
        }
    }

    /**
     * Checks the settings files on the class path: the base product's file against its catalog, and a country's
     * file, when there is one, against both catalogs and the base product's file.
     *
     * @param cl the class loader whose class path holds the files
     * @return what the checks found
     */
    public static Result check(ClassLoader cl) {
        List<Finding> errors = new ArrayList<>();
        SettingsCatalog baseCatalog = readCatalog(cl, SettingsCatalog.BASE_RESOURCE, true, errors);
        JsonNode baseFile = readFile(cl, BASE_SETTINGS, true, errors);
        SettingsCatalog countryCatalog = readCatalog(cl, SettingsCatalog.COUNTRY_RESOURCE, false, errors);
        JsonNode countryFile = readFile(cl, COUNTRY_SETTINGS, false, errors);
        if (baseCatalog == null || baseFile == null) {
            return new Result(errors, List.of(), List.of(), Map.of());
        }
        Result result = check(baseCatalog, baseFile, countryCatalog, countryFile);
        errors.addAll(result.errors());
        return new Result(errors, result.warnings(), result.useBaseDefault(), result.valuesRead());
    }

    /**
     * Checks parsed settings files against parsed catalogs
     *
     * @param baseCatalog    the base product's catalog
     * @param baseFile       the base product's settings file
     * @param countryCatalog the country's catalog, or null
     * @param countryFile    the country's settings file, or null when there is none
     * @return what the checks found
     */
    public static Result check(SettingsCatalog baseCatalog, JsonNode baseFile, SettingsCatalog countryCatalog,
                               JsonNode countryFile) {
        List<Finding> errors = new ArrayList<>();
        List<Finding> warnings = new ArrayList<>();
        List<UseBaseDefault> useBaseDefault = new ArrayList<>();
        Map<String, Integer> valuesRead = new LinkedHashMap<>();

        Map<String, SettingsCatalog.Entry> base = baseCatalog.byKey();
        Map<String, JsonNode> baseValues = values(BASE_SETTINGS, baseFile, true, errors);
        valuesRead.put(BASE_SETTINGS, baseValues.size());
        checkValues(BASE_SETTINGS, baseValues, base, errors, warnings);
        checkRequired(BASE_SETTINGS, baseValues, base, Set.of(), errors);

        if (countryFile != null) {
            Map<String, SettingsCatalog.Entry> catalog = new LinkedHashMap<>(base);
            if (countryCatalog != null) {
                countryCatalog.byKey().forEach(catalog::putIfAbsent);
            }
            Map<String, JsonNode> countryValues = values(COUNTRY_SETTINGS, countryFile, false, errors);
            valuesRead.put(COUNTRY_SETTINGS, countryValues.size());
            checkValues(COUNTRY_SETTINGS, countryValues, catalog, errors, warnings);

            Set<String> listed = new HashSet<>();
            JsonNode entries = countryFile.isObject() ? countryFile.get("useBaseDefault") : null;
            if (entries != null && !entries.isArray()) {
                errors.add(new Finding(COUNTRY_SETTINGS, "useBaseDefault", FORMAT, "is not a list"));
            } else if (entries != null) {
                for (JsonNode entry : entries.values()) {
                    String key = entry.isObject() && entry.path("key").isString() ? entry.get("key").asString() : null;
                    String reason = entry.isObject() && entry.path("reason").isString()
                            ? entry.get("reason").asString().strip() : "";
                    if (key == null || key.isBlank()) {
                        errors.add(new Finding(COUNTRY_SETTINGS, "useBaseDefault", USE_BASE_DEFAULT,
                                "an entry without a key: " + entry));
                        continue;
                    }
                    if (reason.isEmpty()) {
                        errors.add(new Finding(COUNTRY_SETTINGS, key, USE_BASE_DEFAULT,
                                "listed without a reason; say why the base product default is right here"));
                    }
                    if (!listed.add(key)) {
                        errors.add(new Finding(COUNTRY_SETTINGS, key, USE_BASE_DEFAULT, "listed twice"));
                        continue;
                    }
                    SettingsCatalog.Entry declared = catalog.get(key);
                    if (declared == null) {
                        errors.add(new Finding(COUNTRY_SETTINGS, key, UNDECLARED_KEY,
                                "listed in useBaseDefault, but no catalog declares it"));
                    } else if (declared.required()) {
                        errors.add(new Finding(COUNTRY_SETTINGS, key, USE_BASE_DEFAULT,
                                "a required key is never left to the base product default: set it"));
                    }
                    useBaseDefault.add(new UseBaseDefault(key, reason));
                }
            }

            for (String key : baseValues.keySet()) {
                if (!countryValues.containsKey(key) && !listed.contains(key)) {
                    errors.add(new Finding(COUNTRY_SETTINGS, key, MISSING_KEY, "the base product's settings file "
                            + "sets it: set it, or list it in useBaseDefault with a reason"));
                }
            }
            checkRequired(COUNTRY_SETTINGS, countryValues, catalog, listed, errors);
        }
        return new Result(errors, warnings, useBaseDefault, valuesRead);
    }

    /** The file's format, and its values by key in file order; empty when the format is broken */
    private static Map<String, JsonNode> values(String file, JsonNode content, boolean base, List<Finding> errors) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        if (!content.isObject()) {
            errors.add(new Finding(file, "", FORMAT, "is not a JSON object with 'values'"));
            return values;
        }
        for (String member : content.propertyNames()) {
            if (base && member.equals("useBaseDefault")) {
                errors.add(new Finding(file, member, FORMAT,
                        "the base product's file has no useBaseDefault: its values are the base defaults"));
            } else if (!member.equals("values") && !member.equals("useBaseDefault")) {
                errors.add(new Finding(file, member, FORMAT, "an unknown member; a settings file has 'values'"
                        + (base ? "" : " and 'useBaseDefault'")));
            }
        }
        JsonNode node = content.get("values");
        if (node == null || !node.isObject()) {
            errors.add(new Finding(file, "values", FORMAT, "missing, or not an object of key and value"));
            return values;
        }
        node.properties().forEach(p -> values.put(p.getKey(), p.getValue()));
        return values;
    }

    /** The rules on each value: declared, not state, its type, no secret, https */
    private static void checkValues(String file, Map<String, JsonNode> values,
                                    Map<String, SettingsCatalog.Entry> catalog, List<Finding> errors,
                                    List<Finding> warnings) {
        values.forEach((key, value) -> {
            SettingsCatalog.Entry entry = catalog.get(key);
            if (entry == null) {
                errors.add(new Finding(file, key, UNDECLARED_KEY, "no catalog declares it: declare it in code, or "
                        + "drop it from the file"));
            } else {
                if (entry.scope() == Setting.Scope.state) {
                    errors.add(new Finding(file, key, STATE_KEY, "a state setting is written by the system, never "
                            + "by a settings file"));
                }
                if (entry.type() != null && !value.isNull() && !hasType(entry.type(), value)) {
                    errors.add(new Finding(file, key, TYPE, entry.type() + "-typed, takes "
                            + shape(entry.type()) + ", not a JSON "
                            + value.getNodeType().name().toLowerCase(Locale.ROOT)));
                }
                if (entry.type() == Setting.Type.Password && !value.isNull()
                        && !(value.isString() && value.asString().isEmpty())) {
                    errors.add(new Finding(file, key, SECRET, "a password setting is empty in a repository file: "
                            + "set the real value on the Settings page or in the server's ${niord.home}/niord.json"));
                }
            }
            strings(value, s -> {
                for (Map.Entry<String, Pattern> secret : SECRET_PATTERNS.entrySet()) {
                    if (secret.getValue().matcher(s).find()) {
                        errors.add(new Finding(file, key, SECRET, "holds what looks like " + secret.getKey()
                                + "; a repository file holds no real secret"));
                    }
                }
                Matcher http = HTTP_HOST.matcher(s);
                while (http.find()) {
                    if (!isLoopback(http.group(1))) {
                        warnings.add(new Finding(file, key, HTTPS, "an http:// URL to " + http.group(1)
                                + "; use https"));
                    }
                }
            });
        });
    }

    private static void checkRequired(String file, Map<String, JsonNode> values,
                                      Map<String, SettingsCatalog.Entry> catalog, Set<String> listed,
                                      List<Finding> errors) {
        catalog.values().stream()
                .filter(SettingsCatalog.Entry::required)
                .filter(e -> !values.containsKey(e.key()) && !listed.contains(e.key()))
                .forEach(e -> errors.add(new Finding(file, e.key(), REQUIRED,
                        "a required setting: every settings file sets it")));
    }

    /** Whether the value has the type's JSON form: the runtime's rule, {@link SettingsCatalog#hasType} */
    static boolean hasType(Setting.Type type, JsonNode value) {
        return SettingsCatalog.hasType(type, value);
    }

    private static String shape(Setting.Type type) {
        return SettingsCatalog.shape(type);
    }

    /** Every string in the value, also inside lists and objects */
    private static void strings(JsonNode value, Consumer<String> consumer) {
        if (value.isString()) {
            consumer.accept(value.asString());
        } else if (value.isContainer()) {
            value.values().forEach(v -> strings(v, consumer));
        }
    }

    private static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.endsWith(".localhost") || h.startsWith("127.") || h.equals("[::1]");
    }

    private static SettingsCatalog readCatalog(ClassLoader cl, String name, boolean required, List<Finding> errors) {
        String content = readOnce(cl, name, required, errors);
        if (content == null) {
            return null;
        }
        try {
            return SettingsCatalog.parse(content);
        } catch (JacksonException | IllegalArgumentException e) {
            errors.add(new Finding(name, "", FORMAT, "not a settings catalog: " + e.getMessage()));
            return null;
        }
    }

    private static JsonNode readFile(ClassLoader cl, String name, boolean required, List<Finding> errors) {
        String content = readOnce(cl, name, required, errors);
        if (content == null) {
            return null;
        }
        try {
            return MAPPER.readTree(content);
        } catch (JacksonException e) {
            errors.add(new Finding(name, "", FORMAT, "not JSON: " + e.getOriginalMessage()));
            return null;
        }
    }

    /** The resource's content; a format finding when it is missing but required, or on the class path twice */
    private static String readOnce(ClassLoader cl, String name, boolean required, List<Finding> errors) {
        List<URL> urls;
        try {
            urls = Collections.list(cl.getResources(name));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + name, e);
        }
        if (urls.isEmpty()) {
            if (required) {
                errors.add(new Finding(name, "", FORMAT, "is not on the class path"));
            }
            return null;
        }
        if (urls.size() > 1) {
            errors.add(new Finding(name, "", FORMAT, "is on the class path more than once: " + urls));
        }
        try {
            // Uncached, so a jar is closed after the read
            URLConnection connection = urls.getFirst().openConnection();
            connection.setUseCaches(false);
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + urls.getFirst(), e);
        }
    }
}
