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

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates the settings catalog (docs/code-data-config-plan.md §5.1) from the code: every field annotated
 * {@link org.niord.core.settings.annotation.Setting} and every static {@link Setting} constant in the classes of one
 * classes folder. {@code SettingsCatalogCurrentTest} runs it over niord-base's own classes, in one of two modes: by
 * default it fails when the committed catalog differs from the generated one; with
 * {@code -D}{@value #WRITE_PROPERTY}{@code =true} it writes the catalog.
 *
 * <p>The declarations must agree with the catalog's rules, or the generation fails with one message that names
 * every problem and its {@code class#field}:
 * <ul>
 *     <li>every declaration has a type;</li>
 *     <li>two declarations of one key agree on the type, the code default, the scope, {@code web}, {@code editable},
 *         {@code required}, {@code cached} and the description;</li>
 *     <li>a {@code Password} setting is never {@code web};</li>
 *     <li>a {@code state} setting is neither {@code web}, {@code editable} nor {@code required};</li>
 *     <li>an annotation's {@code json} default parses;</li>
 *     <li>a description has at most {@value #MAX_DESCRIPTION} characters, the width of the {@code Setting.description}
 *         column: start-up adds the missing settings in one batch, which one longer description would fail.</li>
 * </ul>
 *
 * <p>Each entry's {@code declaredIn} lists the classes that declare the key, sorted: start-up adds a key only on a
 * node where one of them runs (a Spring component only when the context has a bean of it), so a SECOM-disabled node
 * adds no SECOM setting.
 *
 * <p>A key keeps the {@code introducedIn} the committed catalog gives it. A key the committed catalog lacks takes the
 * version {@code -D}{@value #INTRODUCED_IN_PROPERTY} names; without one the write mode fails, naming the new keys.
 * A publish takes the next free {@code 4.0.<run>}, so a new key's version is the newest release plus one, a lower
 * bound.
 *
 * <p>Same package as {@link SettingInjectionBeanPostProcessor}, whose parsing of an annotation's default it uses, so a
 * catalog's code default is the value the injection uses. In niord-base's test-jar, so a country generates its own
 * catalog with the same code.
 */
public final class SettingsCatalogGenerator {

    /** {@code true} writes the catalog instead of checking it */
    public static final String WRITE_PROPERTY = "niord.settingsCatalog.write";

    /** The version a key new to the catalog is introduced in */
    public static final String INTRODUCED_IN_PROPERTY = "niord.settingsCatalog.introducedIn";

    /** The width of the {@code Setting.description} column */
    public static final int MAX_DESCRIPTION = 255;

    private static final String ANNOTATION_DESCRIPTOR = "Lorg/niord/core/settings/annotation/Setting;";
    private static final String SETTING_DESCRIPTOR = "Lorg/niord/core/settings/Setting;";

    private SettingsCatalogGenerator() {
    }

    /**
     * A setting as one field declares it
     *
     * @param source          the declaring {@code class#field}
     * @param setting         the setting, with its code default as the injection parses it
     * @param unparsedDefault an annotation's {@code json} default that does not parse, else null
     */
    public record Declaration(String source, Setting setting, String unparsedDefault) {
    }

    /**
     * Returns the declarations in the classes of a classes folder. Only classes whose bytes name the annotation or
     * the {@link Setting} type are loaded, without being initialised; a class with a static {@link Setting} field is
     * initialised to read it.
     *
     * @param classesRoot the classes folder, e.g. {@code target/classes}: the owner's classes only
     * @param cl          the class loader that loads them
     * @return the declarations, by class name and field
     */
    public static List<Declaration> scan(Path classesRoot, ClassLoader cl) {
        List<String> classNames;
        try (Stream<Path> paths = Files.walk(classesRoot)) {
            classNames = paths
                    .filter(p -> p.toString().endsWith(".class"))
                    .filter(SettingsCatalogGenerator::namesASetting)
                    .map(p -> classesRoot.relativize(p).toString().replace('\\', '/'))
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                    .filter(name -> !name.endsWith("module-info") && !name.endsWith("package-info"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + classesRoot, e);
        }

        List<Declaration> declarations = new ArrayList<>();
        for (String name : classNames) {
            try {
                declarations.addAll(declarations(Class.forName(name, false, cl)));
            } catch (ClassNotFoundException | LinkageError e) {
                throw new IllegalStateException("Cannot read the settings of " + name + ": " + e, e);
            }
        }
        return declarations;
    }

    /**
     * Returns the declarations of one class: its {@code @Setting} fields and its static {@link Setting} fields
     *
     * @param type the class
     * @return the declarations, in field order
     */
    public static List<Declaration> declarations(Class<?> type) {
        List<Declaration> declarations = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            String source = type.getName() + "#" + field.getName();
            org.niord.core.settings.annotation.Setting ann =
                    field.getAnnotation(org.niord.core.settings.annotation.Setting.class);
            if (ann != null) {
                Setting setting = SettingInjectionBeanPostProcessor.annotationToSetting(ann, field.getName());
                String unparsed = null;
                if (ann.type() == Setting.Type.json && !ann.defaultValue().isEmpty() && setting.getValue() == null) {
                    unparsed = ann.defaultValue();
                }
                if (ann.type() == Setting.Type.Path) {
                    // As written: a Path's string form depends on the platform
                    setting.setValue(ann.defaultValue().isEmpty() ? null : ann.defaultValue());
                }
                declarations.add(new Declaration(source, setting, unparsed));
            }
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Setting.class) {
                try {
                    field.setAccessible(true);
                    Setting setting = (Setting) field.get(null);
                    if (setting != null) {
                        declarations.add(new Declaration(source, setting, null));
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Cannot read " + source, e);
                }
            }
        }
        return declarations;
    }

    /**
     * Generates the catalog from the declarations
     *
     * @param declarations the declarations
     * @param baseValues   the values of the base product's settings file, by key
     * @param committed    the committed catalog, or null when there is none yet
     * @param introducedIn the version of a key the committed catalog lacks, or null
     * @param write        whether the catalog is generated to be written: a new key then needs {@code introducedIn}
     * @return the catalog
     * @throws IllegalStateException naming every declaration that breaks a rule, or the new keys
     */
    public static SettingsCatalog generate(List<Declaration> declarations, Map<String, ?> baseValues,
                                           SettingsCatalog committed, String introducedIn, boolean write) {
        List<String> problems = new ArrayList<>();
        Map<String, List<Declaration>> byKey = new TreeMap<>();
        for (Declaration d : declarations) {
            Setting s = d.setting();
            if (s.getKey() == null || s.getKey().isBlank()) {
                problems.add(d.source() + ": no key");
                continue;
            }
            byKey.computeIfAbsent(s.getKey(), k -> new ArrayList<>()).add(d);
            if (s.getType() == null) {
                problems.add(d.source() + ": " + s.getKey() + " declares no type");
            }
            if (d.unparsedDefault() != null) {
                problems.add(d.source() + ": " + s.getKey() + "'s json default does not parse: " + d.unparsedDefault());
            }
            if (s.getType() == Setting.Type.Password && s.isWeb()) {
                problems.add(d.source() + ": " + s.getKey() + " is a Password setting and web: a password never "
                        + "reaches a browser");
            }
            if (s.getScope() == Setting.Scope.state && (s.isWeb() || s.isEditable() || s.isRequired())) {
                problems.add(d.source() + ": " + s.getKey() + " is a state setting and web, editable or required: "
                        + "only the system writes it");
            }
            if (s.getDescription() != null && s.getDescription().length() > MAX_DESCRIPTION) {
                problems.add(d.source() + ": " + s.getKey() + "'s description has " + s.getDescription().length()
                        + " characters: the Setting.description column holds " + MAX_DESCRIPTION);
            }
        }

        byKey.forEach((key, group) -> {
            Declaration first = group.getFirst();
            for (Declaration other : group.subList(1, group.size())) {
                List<String> differ = differences(first.setting(), other.setting());
                if (!differ.isEmpty()) {
                    problems.add(key + " is declared differently in " + first.source() + " and " + other.source()
                            + ": " + String.join(", ", differ));
                }
            }
        });

        Map<String, SettingsCatalog.Entry> known = committed == null ? Map.of() : committed.byKey();
        Set<String> newKeys = new TreeSet<>();
        List<SettingsCatalog.Entry> entries = new ArrayList<>();
        byKey.forEach((key, group) -> {
            Setting s = group.getFirst().setting();
            SettingsCatalog.Entry previous = known.get(key);
            String version = previous != null ? previous.introducedIn() : introducedIn;
            if (previous == null) {
                newKeys.add(key + " (" + group.getFirst().source() + ")");
            }
            List<String> declaredIn = group.stream()
                    .map(d -> d.source().substring(0, d.source().indexOf('#')))
                    .distinct()
                    .sorted()
                    .toList();
            entries.add(new SettingsCatalog.Entry(key, s.getType(), SettingsCatalog.jsonValue(s.getValue()),
                    SettingsCatalog.jsonValue(baseValues.get(key)), s.getScope(), s.isWeb(), s.isEditable(),
                    s.isRequired(), s.isCached(), version, s.getDescription(), declaredIn));
        });
        if (write && introducedIn == null && !newKeys.isEmpty()) {
            problems.add("New keys need the version they are introduced in, -D" + INTRODUCED_IN_PROPERTY
                    + "=<the newest release plus one>: " + String.join(", ", newKeys));
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("The settings declarations break the catalog's rules:\n"
                    + String.join("\n", problems));
        }
        return new SettingsCatalog(entries);
    }

    /**
     * Describes how a generated catalog differs from the committed one, key by key
     *
     * @param committed the committed catalog
     * @param generated the generated catalog
     * @return the added, removed and changed keys, empty when no key differs
     */
    public static String diff(SettingsCatalog committed, SettingsCatalog generated) {
        Map<String, SettingsCatalog.Entry> before = committed.byKey();
        Map<String, SettingsCatalog.Entry> after = generated.byKey();
        List<String> lines = new ArrayList<>();
        after.keySet().stream().filter(k -> !before.containsKey(k)).forEach(k -> lines.add("added: " + k));
        before.keySet().stream().filter(k -> !after.containsKey(k)).forEach(k -> lines.add("removed: " + k));
        after.forEach((key, entry) -> {
            SettingsCatalog.Entry old = before.get(key);
            if (old != null && !old.equals(entry)) {
                lines.add("changed: " + key + " (" + changedMembers(old, entry) + ")");
            }
        });
        return String.join("\n", lines);
    }

    /**
     * Reads the {@code values} of a settings file
     *
     * @param settingsFile the file, e.g. {@code defaults/base/settings.json}
     * @return its values by key, in file order, as JSON values
     */
    public static Map<String, Object> readValues(Path settingsFile) {
        JsonNode root;
        try {
            root = SettingsCatalog.MAPPER.readTree(Files.readString(settingsFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + settingsFile, e);
        }
        JsonNode values = root.get("values");
        if (values == null || !values.isObject()) {
            throw new IllegalArgumentException(settingsFile + " is not an object with 'values'");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.properties().forEach(p -> result.put(p.getKey(),
                p.getValue().isNull() ? null : SettingsCatalog.MAPPER.treeToValue(p.getValue(), Object.class)));
        return result;
    }

    private static List<String> differences(Setting a, Setting b) {
        List<String> differ = new ArrayList<>();
        if (a.getType() != b.getType()) {
            differ.add("type " + a.getType() + " / " + b.getType());
        }
        if (!Objects.equals(SettingsCatalog.jsonValue(a.getValue()), SettingsCatalog.jsonValue(b.getValue()))) {
            differ.add("default " + a.getValue() + " / " + b.getValue());
        }
        if (a.getScope() != b.getScope()) {
            differ.add("scope " + a.getScope() + " / " + b.getScope());
        }
        if (a.isWeb() != b.isWeb()) {
            differ.add("web " + a.isWeb() + " / " + b.isWeb());
        }
        if (a.isEditable() != b.isEditable()) {
            differ.add("editable " + a.isEditable() + " / " + b.isEditable());
        }
        if (a.isRequired() != b.isRequired()) {
            differ.add("required " + a.isRequired() + " / " + b.isRequired());
        }
        if (a.isCached() != b.isCached()) {
            differ.add("cached " + a.isCached() + " / " + b.isCached());
        }
        if (!Objects.equals(a.getDescription(), b.getDescription())) {
            differ.add("description '" + a.getDescription() + "' / '" + b.getDescription() + "'");
        }
        return differ;
    }

    private static String changedMembers(SettingsCatalog.Entry a, SettingsCatalog.Entry b) {
        List<String> members = new ArrayList<>();
        if (a.type() != b.type()) members.add("type");
        if (!Objects.equals(a.codeDefault(), b.codeDefault())) members.add("codeDefault");
        if (!Objects.equals(a.baseValue(), b.baseValue())) members.add("baseValue");
        if (a.scope() != b.scope()) members.add("scope");
        if (a.web() != b.web()) members.add("web");
        if (a.editable() != b.editable()) members.add("editable");
        if (a.required() != b.required()) members.add("required");
        if (a.cached() != b.cached()) members.add("cached");
        if (!Objects.equals(a.introducedIn(), b.introducedIn())) members.add("introducedIn");
        if (!Objects.equals(a.description(), b.description())) members.add("description");
        if (!Objects.equals(a.declaredIn(), b.declaredIn())) members.add("declaredIn");
        return members.stream().collect(Collectors.joining(", "));
    }

    /** Whether a class file names the annotation or the {@link Setting} type: only those are loaded */
    private static boolean namesASetting(Path classFile) {
        try {
            String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return bytes.contains(ANNOTATION_DESCRIPTOR) || bytes.contains(SETTING_DESCRIPTOR);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + classFile, e);
        }
    }
}
