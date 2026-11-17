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
package org.niord.core.bootstrap;

import org.apache.commons.lang3.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which fields an item of an import file gives: the set of its JSON paths whose value is there and not null
 * (docs/code-data-config-plan.md §7.2, the first bootstrap's rule, as the owner decided on 2026-10-25). A list is
 * given even when empty, so an explicit {@code []} replaces; an empty string or an empty object counts as left out (a
 * required text cannot be blank). The typed value objects cannot tell a field left out from one given at the
 * import's default (an {@code active} true, a {@code priority} 0, a {@code mandatory} false), so the first
 * bootstrap's replace reads this set instead: a value the later archive gives replaces, one it leaves out is kept.
 * It is built only for a job of the live bootstrap run whose archive is not the first ({@link BootstrapRun}); every
 * other job's items carry none.
 *
 * <p>The paths: the scalars by name ({@link #SCALARS}); {@code descs[<lang>]} for a description with a name or value;
 * the lists by name ({@link #LISTS}); and a template parameter's fields under {@link #param}, the parameter matched
 * by its paramId and then by its order among equal paramIds, as the import matches it:
 * {@code templateParams[<paramId>#<n>].type}, {@code .mandatory}, {@code .positionList} and
 * {@code .descs[<lang>]}.
 */
public final class Given {

    /** The scalar fields an item may give, by their JSON names */
    public static final List<String> SCALARS = List.of("active", "type", "siblingSortOrder", "mrn", "atonFilter",
            "priority", "requirement", "language", "name", "serviceId");

    /** The list fields an item may give, by their JSON names */
    public static final List<String> LISTS = List.of("editorFields", "stdTemplateFields", "scriptResourcePaths",
            "domains", "messageTypes", "values", "templateParams");

    /** The fields of a template parameter */
    private static final List<String> PARAM_SCALARS = List.of("type", "mandatory", "positionList");

    /**
     * The typed read's own defaults ({@code JsonUtils.readJson}), so the tree read and the typed read of one file
     * agree item for item, content after the root included
     */
    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    private final Set<String> paths;

    private Given(Set<String> paths) {
        this.paths = Collections.unmodifiableSet(paths);
    }

    /**
     * Returns what an item gives
     *
     * @param item the item's JSON object; anything else gives nothing
     * @return the paths it gives
     */
    public static Given of(JsonNode item) {
        Set<String> paths = new LinkedHashSet<>();
        if (item != null && item.isObject()) {
            for (String field : SCALARS) {
                if (given(item.get(field))) {
                    paths.add(field);
                }
            }
            descs("", item.get("descs"), paths);
            for (String field : LISTS) {
                if (given(item.get(field))) {
                    paths.add(field);
                }
            }
            JsonNode params = item.get("templateParams");
            if (params != null && params.isArray()) {
                Map<String, Integer> seen = new HashMap<>();
                for (JsonNode param : params) {
                    JsonNode paramId = param.path("paramId");
                    if (!paramId.isString() || StringUtils.isBlank(paramId.asString())) {
                        continue;
                    }
                    int nth = seen.merge(paramId.asString(), 1, Integer::sum) - 1;
                    String prefix = param(paramId.asString(), nth);
                    for (String field : PARAM_SCALARS) {
                        if (given(param.get(field))) {
                            paths.add(prefix + "." + field);
                        }
                    }
                    descs(prefix + ".", param.get("descs"), paths);
                }
            }
        }
        return new Given(paths);
    }

    /** Adds {@code descs[<lang>]} for each description with a language and a name or a value */
    private static void descs(String prefix, JsonNode descs, Set<String> paths) {
        if (descs == null || !descs.isArray()) {
            return;
        }
        for (JsonNode desc : descs) {
            JsonNode lang = desc.path("lang");
            if (lang.isString() && StringUtils.isNotBlank(lang.asString())
                    && (given(desc.get("name")) || given(desc.get("value")))) {
                paths.add(prefix + "descs[" + lang.asString() + "]");
            }
        }
    }

    /** Whether a value is given: there and not null, a list even when empty, not an empty string or object */
    private static boolean given(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isString()) {
            return !value.asString().isEmpty();
        }
        if (value.isObject()) {
            return !value.isEmpty();
        }
        return true;
    }

    /**
     * Reads an import file as a tree, for {@link #of} on each item
     *
     * @param file the file
     * @return its root
     */
    public static JsonNode readTree(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return MAPPER.readTree(in);
        }
    }

    /**
     * Returns the path of a template parameter: its paramId and its order among the item's parameters of that paramId
     *
     * @param paramId the paramId
     * @param nth     its order among equal paramIds, from 0
     * @return the path, e.g. {@code templateParams[p1#1]}
     */
    public static String param(String paramId, int nth) {
        return "templateParams[" + paramId + "#" + nth + "]";
    }

    /**
     * Returns whether the item gives the path
     *
     * @param path a path, e.g. {@code active}, {@code descs[en]} or {@code templateParams[p1#0].mandatory}
     * @return whether it is given
     */
    public boolean has(String path) {
        return paths.contains(path);
    }

    /** Returns every path the item gives */
    public Set<String> paths() {
        return paths;
    }

    @Override
    public String toString() {
        return "Given" + paths;
    }
}
