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

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.niord.core.dictionary.DictionaryService;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The checks of the default backend texts (docs/code-data-config-plan.md §4.7, R-CDC8): the base product's English
 * texts, the country's texts and the language packs' backend half, held to rules a value must meet before it can
 * be added to a deployment's database, where it is never changed again.
 *
 * <p>The base product's unit suite runs them ({@code BaseProductTextsCheckTest}); this class is in niord-base's
 * test-jar, so a country's build can run them over its own class path (the plan's C5b and C6). Each finding names the
 * file, the key and the rule.
 *
 * <p>The rules on every text:
 * <ul>
 *     <li>{@value #UTF8}: the file is valid UTF-8. A wrong byte is added as a replacement character, everywhere.</li>
 *     <li>{@value #LENGTH}: a value has at most {@value #MAX_VALUE_LENGTH} characters
 *         ({@code DictionaryEntryDesc.value}); a longer one fails the start-up's insert.</li>
 *     <li>{@value #KEY_LENGTH}: a key has at most {@value #MAX_KEY_LENGTH} characters
 *         ({@code DictionaryEntry.key}).</li>
 *     <li>{@value #CONTROL_CHARACTER}: no control character other than tab and newline, after the properties
 *         escapes are resolved. One would reach a PDF, a mail or a NAVTEX text.</li>
 *     <li>{@value #PLACEHOLDER}: every <code>{{</code> or <code>}}</code> belongs to a well-formed
 *         {@code {{ name }}}; and a
 *         value with {@code {n}} placeholders (formatted by {@code MessageFormat} when a template passes arguments
 *         to {@code text()}) is one {@code MessageFormat} accepts with the same number of placeholders, and
 *         writes every apostrophe as {@code ''}: a single one quotes a placeholder away, or is dropped.</li>
 * </ul>
 * The rules on a country's or a pack's text, against the reference (the base product's English value of the same
 * dictionary and key; for a country's own key the country's English value; with no reference only the rules
 * above apply):
 * <ul>
 *     <li>{@value #PLACEHOLDER}: the same {@code {{name}}} names and the same {@code {n}} indexes as the reference, so
 *         no translation drops or renames a value the code fills in.</li>
 *     <li>{@value #MARKUP}: the markup rule of the country-split plan (§4.6): the same tag names, as often, the same
 *         attribute names, and the same {@code href} and {@code src} values, in order; no markup where the reference
 *         has none. A translation cannot add a link or an element English does not have.</li>
 * </ul>
 * The rules on the base product as a whole:
 * <ul>
 *     <li>{@value #TEMPLATE_KEY}: every key a template names literally in {@code text('…')} or {@code text['…']}
 *         exists, so the template renders on a fresh database ({@code text()} fails a render on a missing
 *         key).</li>
 *     <li>{@value #RUNTIME_PREFIX}: every constant of {@link Status}, {@link Type} and {@link MessagePartType} has its
 *         {@code msg.status.}, {@code msg.type.} or {@code msg.field.} key, which templates build at runtime.</li>
 *     <li>{@value #LANGUAGE_NAME}: the base product names English ({@code lang.en}) and no other language; another
 *         language's name comes with its pack or a country (country-split R1).</li>
 * </ul>
 * The rule on the class path:
 * <ul>
 *     <li>{@value #ROOT_BUNDLE}: no root-level {@code <bundle>_<lang>.properties} of the five default bundles in the
 *         bounded languages ({@code en}; every language with a folder under {@code defaults/country/texts/} or
 *         {@code defaults/packs/}; the {@code modelLanguages} of {@code defaults/country/settings.json} when its
 *         values set that key, else of {@code defaults/base/settings.json}), since start-up no longer reads one.</li>
 * </ul>
 *
 * <p>Pure: no Spring, no database. Languages are found by listing {@code defaults/country/texts/} and
 * {@code defaults/packs/} in every class-path root that has them (folders and jars), never by scanning the class
 * path; the root-bundle rule looks the names of the bounded languages up with {@code getResources} and never scans
 * the class path either.
 */
public final class DefaultTextsChecks {

    public static final String UTF8 = "utf8";
    public static final String LENGTH = "length";
    public static final String KEY_LENGTH = "key-length";
    public static final String CONTROL_CHARACTER = "control-character";
    public static final String PLACEHOLDER = "placeholder";
    public static final String MARKUP = "markup";
    public static final String TEMPLATE_KEY = "template-key";
    public static final String RUNTIME_PREFIX = "runtime-prefix";
    public static final String LANGUAGE_NAME = "language-name";
    public static final String ROOT_BUNDLE = "root-bundle";

    /** {@code DictionaryEntryDesc.value} is a {@code varchar(1000)} ({@code V001__baseline_schema.sql}) */
    public static final int MAX_VALUE_LENGTH = 1000;

    /** {@code DictionaryEntry.key} is a {@code varchar(255)} */
    public static final int MAX_KEY_LENGTH = 255;

    static final String BASE_FOLDER = "defaults/base/texts/" + DefaultTexts.BASE_LANGUAGE + "/";
    static final String COUNTRY_FOLDER = "defaults/country/texts/";
    static final String PACKS_FOLDER = "defaults/packs/";

    private static final Pattern MUSTACHE = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");
    private static final Pattern MESSAGE_FORMAT_ARGUMENT = Pattern.compile("\\{(\\d+)(?:,[^{}]*)?}");
    /** A key a template reads with {@code text('key')}, {@code text('key', ...)} or {@code text['key']} */
    private static final Pattern TEMPLATE_TEXT =
            Pattern.compile("text\\s*[(\\[]\\s*([\"'])([^\"'\\s]+)\\1\\s*[,)\\]]");
    /** A language code: two or three lower-case letters */
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2,3}");

    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

    private DefaultTextsChecks() {
    }

    /** A rule a text breaks */
    public record Finding(String file, String key, String rule, String detail) {

        @Override
        public String toString() {
            return file + " " + key + ": " + rule + " — " + detail;
        }
    }

    /** A text file as read: its resource path and its texts in file order */
    private record TextFile(String path, Map<String, String> texts) {
    }

    /**
     * Checks the base product's English texts, and the country's texts and the packs' backend half in every
     * language found, against the rules on a text and, for a country's or a pack's text, against its reference; and
     * the class path against the {@value #ROOT_BUNDLE} rule.
     *
     * @param cl the class loader whose class path holds the texts
     * @return the findings, empty when every text passes
     */
    public static List<Finding> checkTexts(ClassLoader cl) {
        List<Finding> findings = new ArrayList<>();

        Map<String, Map<String, String>> base = new LinkedHashMap<>();
        for (String bundle : DictionaryService.DEFAULT_BUNDLES) {
            for (TextFile file : read(cl, BASE_FOLDER + bundle + ".properties", findings)) {
                base.computeIfAbsent(bundle, b -> new LinkedHashMap<>()).putAll(file.texts());
                file.texts().forEach((key, value) -> checkText(file.path(), key, value, findings));
            }
        }

        Map<String, Map<String, String>> countryEnglish = new LinkedHashMap<>();
        for (String bundle : DictionaryService.DEFAULT_BUNDLES) {
            String path = COUNTRY_FOLDER + DefaultTexts.BASE_LANGUAGE + "/" + bundle + ".properties";
            for (TextFile file : read(cl, path, new ArrayList<>())) {
                countryEnglish.computeIfAbsent(bundle, b -> new LinkedHashMap<>()).putAll(file.texts());
            }
        }

        for (String lang : languages(cl, COUNTRY_FOLDER)) {
            for (String bundle : DictionaryService.DEFAULT_BUNDLES) {
                for (TextFile file : read(cl, COUNTRY_FOLDER + lang + "/" + bundle + ".properties", findings)) {
                    boolean english = DefaultTexts.BASE_LANGUAGE.equals(lang);
                    file.texts().forEach((key, value) -> {
                        checkText(file.path(), key, value, findings);
                        String reference = base.getOrDefault(bundle, Map.of()).get(key);
                        if (reference == null && !english) {
                            reference = countryEnglish.getOrDefault(bundle, Map.of()).get(key);
                        }
                        checkAgainst(file.path(), key, value, reference, findings);
                    });
                }
            }
        }

        for (String lang : languages(cl, PACKS_FOLDER)) {
            for (String bundle : DictionaryService.DEFAULT_BUNDLES) {
                for (TextFile file : read(cl, PACKS_FOLDER + lang + "/" + bundle + ".properties", findings)) {
                    file.texts().forEach((key, value) -> {
                        checkText(file.path(), key, value, findings);
                        checkAgainst(file.path(), key, value, base.getOrDefault(bundle, Map.of()).get(key), findings);
                    });
                }
            }
        }

        checkRootBundles(cl, findings);
        return findings;
    }

    /** The {@value #ROOT_BUNDLE} rule: each root-level bundle of a bounded language is named by its URL */
    private static void checkRootBundles(ClassLoader cl, List<Finding> findings) {
        for (String lang : rootBundleLanguages(cl)) {
            for (String bundle : DictionaryService.DEFAULT_BUNDLES) {
                for (URL url : resources(cl, bundle + "_" + lang + ".properties")) {
                    findings.add(new Finding(url.toString(), "", ROOT_BUNDLE, "a root-level <bundle>_<lang>.properties "
                            + "is no longer read (the transitional layer is gone): move its keys to " + COUNTRY_FOLDER
                            + lang + "/" + bundle + ".properties"));
                }
            }
        }
    }

    /**
     * Returns the languages the {@value #ROOT_BUNDLE} rule looks up: {@code en}, every language with a folder under
     * {@code defaults/country/texts/} or {@code defaults/packs/}, and the {@code modelLanguages} of the country's
     * settings file when it sets them, else of the base product's
     */
    static Set<String> rootBundleLanguages(ClassLoader cl) {
        Set<String> languages = new TreeSet<>();
        languages.add(DefaultTexts.BASE_LANGUAGE);
        languages.addAll(languages(cl, COUNTRY_FOLDER));
        languages.addAll(languages(cl, PACKS_FOLDER));
        List<String> modelLanguages = modelLanguages(cl, DefaultSettingsChecks.COUNTRY_SETTINGS);
        if (modelLanguages == null) {
            modelLanguages = modelLanguages(cl, DefaultSettingsChecks.BASE_SETTINGS);
        }
        if (modelLanguages != null) {
            languages.addAll(modelLanguages);
        }
        return languages;
    }

    /**
     * Returns the language codes of the settings file's {@code values.modelLanguages}, or null when the file, the key
     * or the list is missing or the file is not JSON (the settings checks report a bad file)
     */
    private static List<String> modelLanguages(ClassLoader cl, String name) {
        URL url = cl.getResource(name);
        if (url == null) {
            return null;
        }
        JsonNode file;
        try {
            // Uncached, so a jar is closed after the read
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            try (InputStream in = connection.getInputStream()) {
                file = JSON.readTree(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + url, e);
        } catch (JacksonException e) {
            return null;
        }
        JsonNode list = file == null ? null : file.path("values").get("modelLanguages");
        if (list == null || !list.isArray()) {
            return null;
        }
        List<String> languages = new ArrayList<>();
        for (JsonNode element : list) {
            if (element.isString() && LANGUAGE_CODE.matcher(element.asString()).matches()) {
                languages.add(element.asString());
            }
        }
        return languages;
    }

    /**
     * Checks the base product's English texts as a whole: the keys templates build at runtime exist, and the base
     * product names no language but English.
     *
     * @param cl the class loader whose class path holds the texts
     * @return the findings, empty when the texts pass
     */
    public static List<Finding> checkBaseKeys(ClassLoader cl) {
        List<Finding> findings = new ArrayList<>();
        Map<String, Map<String, String>> base = baseTexts(cl);

        String message = BASE_FOLDER + "message.properties";
        Set<String> messageKeys = base.getOrDefault("message", Map.of()).keySet();
        for (Status status : Status.values()) {
            requireRuntimeKey(message, messageKeys, "msg.status." + status.name(), "Status." + status.name(),
                    "text('msg.status.' + msg.status)", findings);
        }
        for (Type type : Type.values()) {
            requireRuntimeKey(message, messageKeys, "msg.type." + type.name(), "Type." + type.name(),
                    "text('msg.type.' + msg.type)", findings);
        }
        for (MessagePartType partType : MessagePartType.values()) {
            requireRuntimeKey(message, messageKeys, "msg.field." + partType.name().toLowerCase(),
                    "MessagePartType." + partType.name(), "text('msg.field.' + part.type?lower_case)", findings);
        }

        base.forEach((bundle, texts) -> texts.keySet().stream()
                .filter(key -> key.startsWith("lang.") && !key.equals("lang." + DefaultTexts.BASE_LANGUAGE))
                .forEach(key -> findings.add(new Finding(BASE_FOLDER + bundle + ".properties", key, LANGUAGE_NAME,
                        "the base product names only English; another language's name comes with its pack or a "
                                + "country"))));
        if (!base.getOrDefault("web", Map.of()).containsKey("lang." + DefaultTexts.BASE_LANGUAGE)) {
            findings.add(new Finding(BASE_FOLDER + "web.properties", "lang." + DefaultTexts.BASE_LANGUAGE,
                    LANGUAGE_NAME, "missing: a draft PDF names the language of each translation"));
        }
        return findings;
    }

    /**
     * Checks that every key a template names literally in {@code text('…')}, {@code text("…")} or the bracket form
     * {@code text['…']} is one of the keys.
     *
     * @param templatesRoot the folder of the templates, searched for {@code .ftl} files
     * @param rootName      the name the findings give the folder, such as {@code templates}
     * @param keys          the keys the templates may use, such as {@link #baseKeys(ClassLoader)}
     * @return the findings, empty when every key exists
     */
    public static List<Finding> checkTemplates(Path templatesRoot, String rootName, Set<String> keys) {
        List<Finding> findings = new ArrayList<>();
        List<Path> templates;
        try (Stream<Path> paths = Files.walk(templatesRoot)) {
            templates = paths.filter(p -> p.toString().endsWith(".ftl")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + templatesRoot, e);
        }
        for (Path template : templates) {
            String file = rootName + "/" + templatesRoot.relativize(template).toString().replace('\\', '/');
            String content;
            try {
                content = Files.readString(template, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + template, e);
            }
            Matcher m = TEMPLATE_TEXT.matcher(content);
            while (m.find()) {
                if (!keys.contains(m.group(2))) {
                    findings.add(new Finding(file, m.group(2), TEMPLATE_KEY, "line " + line(content, m.start())
                            + ": no such key in " + BASE_FOLDER));
                }
            }
        }
        return findings;
    }

    /**
     * Returns the keys of the base product's English texts, in every dictionary
     *
     * @param cl the class loader whose class path holds the texts
     * @return the keys
     */
    public static Set<String> baseKeys(ClassLoader cl) {
        Set<String> keys = new TreeSet<>();
        baseTexts(cl).values().forEach(texts -> keys.addAll(texts.keySet()));
        return keys;
    }

    /** The base product's English texts, by dictionary and key */
    private static Map<String, Map<String, String>> baseTexts(ClassLoader cl) {
        Map<String, Map<String, String>> base = new LinkedHashMap<>();
        for (String bundle : DictionaryService.DEFAULT_BUNDLES) {
            for (TextFile file : read(cl, BASE_FOLDER + bundle + ".properties", new ArrayList<>())) {
                base.computeIfAbsent(bundle, b -> new LinkedHashMap<>()).putAll(file.texts());
            }
        }
        return base;
    }

    private static void requireRuntimeKey(String file, Set<String> keys, String key, String constant, String use,
                                          List<Finding> findings) {
        if (!keys.contains(key)) {
            findings.add(new Finding(file, key, RUNTIME_PREFIX, constant + " has no text; templates render " + use));
        }
    }

    /** The rules on every text */
    private static void checkText(String file, String key, String value, List<Finding> findings) {
        if (key.codePointCount(0, key.length()) > MAX_KEY_LENGTH) {
            findings.add(new Finding(file, key, KEY_LENGTH, key.length() + " characters, at most "
                    + MAX_KEY_LENGTH));
        }
        if (value.codePointCount(0, value.length()) > MAX_VALUE_LENGTH) {
            findings.add(new Finding(file, key, LENGTH, value.length() + " characters, at most "
                    + MAX_VALUE_LENGTH));
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t' && c != '\n') || c == 0x7F) {
                findings.add(new Finding(file, key, CONTROL_CHARACTER,
                        String.format("U+%04X at index %d; only tab and newline are allowed", (int) c, i)));
                break;
            }
        }

        String outsideMustache = MUSTACHE.matcher(value).replaceAll("");
        if (outsideMustache.contains("{{") || outsideMustache.contains("}}")) {
            findings.add(new Finding(file, key, PLACEHOLDER,
                    "a '{{' or '}}' that is not part of a well-formed {{ name }}"));
        }
        if (!value.contains("{{")) {
            int arguments = (int) MESSAGE_FORMAT_ARGUMENT.matcher(value).results().count();
            if (arguments > 0) {
                try {
                    int parsed = new MessageFormat(value).getFormats().length;
                    if (parsed != arguments) {
                        findings.add(new Finding(file, key, PLACEHOLDER, "MessageFormat reads " + parsed
                                + " of the " + arguments + " {n} placeholders: an apostrophe quotes the rest; "
                                + "write it as ''"));
                    } else if (value.replace("''", "").indexOf('\'') >= 0) {
                        findings.add(new Finding(file, key, PLACEHOLDER,
                                "an apostrophe in a value with {n} placeholders is written as ''"));
                    }
                } catch (IllegalArgumentException e) {
                    findings.add(new Finding(file, key, PLACEHOLDER, "MessageFormat rejects it: " + e.getMessage()));
                }
            }
        }
    }

    /** The rules on a country's or a pack's text against its reference; none without a reference */
    private static void checkAgainst(String file, String key, String value, String reference,
                                     List<Finding> findings) {
        if (reference == null) {
            return;
        }
        Set<String> names = mustacheNames(value);
        Set<String> referenceNames = mustacheNames(reference);
        if (!names.equals(referenceNames)) {
            findings.add(new Finding(file, key, PLACEHOLDER, "{{name}} placeholders " + names + ", English has "
                    + referenceNames));
        }
        Set<String> indexes = argumentIndexes(value);
        Set<String> referenceIndexes = argumentIndexes(reference);
        if (!indexes.equals(referenceIndexes)) {
            findings.add(new Finding(file, key, PLACEHOLDER, "{n} placeholders " + indexes + ", English has "
                    + referenceIndexes));
        }

        Markup markup = Markup.of(value);
        Markup referenceMarkup = Markup.of(reference);
        if (!markup.tags().equals(referenceMarkup.tags())) {
            findings.add(new Finding(file, key, MARKUP, referenceMarkup.tags().isEmpty()
                    ? "markup " + markup.tags() + " where English has none"
                    : "tags " + markup.tags() + ", English has " + referenceMarkup.tags()));
        }
        if (!markup.attributes().equals(referenceMarkup.attributes())) {
            findings.add(new Finding(file, key, MARKUP, "attributes " + markup.attributes() + ", English has "
                    + referenceMarkup.attributes()));
        }
        if (!markup.links().equals(referenceMarkup.links())) {
            findings.add(new Finding(file, key, MARKUP, "href/src " + markup.links() + ", English has "
                    + referenceMarkup.links()));
        }
    }

    private static Set<String> mustacheNames(String value) {
        Set<String> names = new TreeSet<>();
        MUSTACHE.matcher(value).results().forEach(r -> names.add(r.group(1)));
        return names;
    }

    private static Set<String> argumentIndexes(String value) {
        Set<String> indexes = new TreeSet<>();
        MESSAGE_FORMAT_ARGUMENT.matcher(MUSTACHE.matcher(value).replaceAll("")).results()
                .forEach(r -> indexes.add(r.group(1)));
        return indexes;
    }

    /**
     * A value's markup, parsed as an HTML body fragment
     *
     * @param tags       the tag names, sorted, each as often as it occurs
     * @param attributes the attribute names
     * @param links      the {@code href} and {@code src} values, in document order
     */
    private record Markup(List<String> tags, Set<String> attributes, List<String> links) {

        static Markup of(String value) {
            Element body = Jsoup.parseBodyFragment(value).body();
            List<String> tags = new ArrayList<>();
            Set<String> attributes = new TreeSet<>();
            List<String> links = new ArrayList<>();
            for (Element element : body.getAllElements()) {
                if (element == body) {
                    continue;
                }
                tags.add(element.normalName());
                for (Attribute attribute : element.attributes()) {
                    attributes.add(attribute.getKey());
                    if (attribute.getKey().equals("href") || attribute.getKey().equals("src")) {
                        links.add(attribute.getValue());
                    }
                }
            }
            Collections.sort(tags);
            return new Markup(tags, attributes, links);
        }
    }

    /**
     * Reads every copy of the resource on the class path. A copy that is not valid UTF-8 adds a {@value #UTF8}
     * finding and is read on with the wrong bytes replaced.
     */
    private static List<TextFile> read(ClassLoader cl, String path, List<Finding> findings) {
        List<TextFile> files = new ArrayList<>();
        for (URL url : resources(cl, path)) {
            byte[] bytes;
            try {
                // Uncached, so a jar is closed after the read
                URLConnection connection = url.openConnection();
                connection.setUseCaches(false);
                try (InputStream in = connection.getInputStream()) {
                    bytes = in.readAllBytes();
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + url, e);
            }
            int bad = firstMalformedByte(bytes);
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (bad >= 0) {
                int line = 1;
                for (int i = 0; i < bad; i++) {
                    line += bytes[i] == '\n' ? 1 : 0;
                }
                String lineText = content.lines().skip(line - 1).findFirst().orElse("").strip();
                String key = lineText.split("[\\s=:]", 2)[0];
                findings.add(new Finding(path, key, UTF8, "line " + line + " is not valid UTF-8 (byte "
                        + bad + ")"));
            }

            Map<String, String> texts = new LinkedHashMap<>();
            Properties props = new Properties() {
                @Override
                public synchronized Object put(Object key, Object value) {
                    texts.put((String) key, (String) value);
                    return super.put(key, value);
                }
            };
            try {
                props.load(new StringReader(content));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + url, e);
            }
            files.add(new TextFile(path, texts));
        }
        return files;
    }

    /** Returns the offset of the first byte that is not valid UTF-8, or -1 */
    private static int firstMalformedByte(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        CharBuffer out = CharBuffer.allocate(bytes.length + 1);
        CoderResult result = decoder.decode(in, out, true);
        return result.isError() ? in.position() : -1;
    }

    /**
     * Returns the languages under the folder (its sub-folders' names) in every class-path root that has it, a
     * folder or a jar (with its folder entries, as Maven builds jars)
     */
    static Set<String> languages(ClassLoader cl, String folder) {
        Set<String> languages = new TreeSet<>();
        for (URL url : resources(cl, folder)) {
            try {
                if ("file".equals(url.getProtocol())) {
                    try (Stream<Path> children = Files.list(Path.of(url.toURI()))) {
                        children.filter(Files::isDirectory).forEach(p -> languages.add(p.getFileName().toString()));
                    }
                } else if ("jar".equals(url.getProtocol())) {
                    URLConnection connection = url.openConnection();
                    connection.setUseCaches(false);
                    JarURLConnection jarConnection = (JarURLConnection) connection;
                    String prefix = jarConnection.getEntryName().endsWith("/")
                            ? jarConnection.getEntryName()
                            : jarConnection.getEntryName() + "/";
                    try (JarFile jar = jarConnection.getJarFile()) {
                        for (JarEntry entry : Collections.list(jar.entries())) {
                            String rest = entry.getName().startsWith(prefix)
                                    ? entry.getName().substring(prefix.length())
                                    : "";
                            if (rest.indexOf('/') > 0) {
                                languages.add(rest.substring(0, rest.indexOf('/')));
                            }
                        }
                    }
                } else {
                    throw new IllegalStateException("Cannot list " + url + ": neither a folder nor a jar");
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list " + url, e);
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Cannot list " + url, e);
            }
        }
        return languages;
    }

    private static List<URL> resources(ClassLoader cl, String name) {
        try {
            Enumeration<URL> urls = cl.getResources(name);
            return Collections.list(urls);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + name, e);
        }
    }

    private static int line(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            line += content.charAt(i) == '\n' ? 1 : 0;
        }
        return line;
    }
}
