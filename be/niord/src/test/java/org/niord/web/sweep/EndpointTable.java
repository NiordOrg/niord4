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
package org.niord.web.sweep;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The endpoint table: every {@code endpoints/*.json} file, read as one table.
 *
 * <p>Each file is one group's: it names its group, the controllers whose handlers it holds, the background log
 * faults its group knows of, and its entries. The loader refuses an unknown field, a key present twice, a controller
 * claimed by two files and an entry whose controller its file does not claim.
 */
public final class EndpointTable {

    /** The class path folder and the source folder (relative to the module) of the table */
    public static final String FOLDER = "endpoints";
    public static final Path SOURCE_FOLDER = Path.of("src", "test", "resources", FOLDER);

    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    /** One table file */
    @JsonPropertyOrder({"group", "controllers", "backgroundKnownFaults", "entries"})
    public static class TableFile {
        /** The group's name: the file's name and the prefix of its rows and request ids */
        public String group;
        /** The simple names of the controllers whose handlers the file holds */
        public List<String> controllers = new ArrayList<>();
        /** ERROR lines of background work (not of a request) the group's fixture is known to cause */
        public List<BackgroundFault> backgroundKnownFaults = new ArrayList<>();
        public List<EndpointEntry> entries = new ArrayList<>();
    }

    /** A known ERROR line of background work */
    @JsonPropertyOrder({"code", "logger", "message"})
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BackgroundFault {
        public String code;
        /** A substring of the logger's name */
        public String logger;
        /** A substring of the message */
        public String message;
    }

    private final Map<String, TableFile> files;
    private final Map<String, EndpointEntry> byKey = new LinkedHashMap<>();
    private final Map<EndpointEntry, String> groupOf = new LinkedHashMap<>();
    private final Map<String, String> controllerGroup = new TreeMap<>();

    private EndpointTable(Map<String, TableFile> files) {
        this.files = files;
        List<String> problems = new ArrayList<>();
        files.forEach((name, file) -> {
            if (!name.equals(file.group)) {
                problems.add(name + ".json names the group '" + file.group + "'; a file's group is its name");
            }
            for (String controller : file.controllers) {
                String other = controllerGroup.putIfAbsent(controller, name);
                if (other != null) {
                    problems.add("The controller " + controller + " is claimed by " + other + ".json and " + name
                            + ".json");
                }
            }
        });
        files.forEach((name, file) -> {
            for (EndpointEntry entry : file.entries) {
                if (entry.key == null || !entry.key.contains(" ") || entry.handler == null
                        || !entry.handler.contains(".")) {
                    problems.add(name + ".json: an entry needs a key \"VERB /path\" and a handler "
                            + "\"Controller.method\": " + entry.key + " / " + entry.handler);
                    continue;
                }
                EndpointEntry other = byKey.putIfAbsent(entry.key, entry);
                if (other != null) {
                    problems.add("The key " + entry.key + " is present twice (" + groupOf.get(other) + ".json and "
                            + name + ".json)");
                }
                groupOf.put(entry, name);
                if (!file.controllers.contains(entry.controller())) {
                    problems.add(name + ".json holds " + entry + " but does not claim " + entry.controller()
                            + " in its controllers");
                }
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The endpoint table is not consistent:\n  " + String.join("\n  ", problems));
        }
    }

    /** A table of the files (the engine's unit tests) */
    static EndpointTable of(Map<String, TableFile> files) {
        return new EndpointTable(new TreeMap<>(files));
    }

    /** Reads every {@code endpoints/*.json} on the class path */
    public static EndpointTable load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + FOLDER + "/*.json");
            Map<String, TableFile> files = new TreeMap<>();
            for (Resource resource : resources) {
                String name = stripJson(resource.getFilename());
                try (InputStream in = resource.getInputStream()) {
                    if (files.put(name, read(in, name)) != null) {
                        throw new IllegalStateException("Two table files named " + name + ".json are on the class path");
                    }
                }
            }
            if (files.isEmpty()) {
                throw new IllegalStateException("No " + FOLDER + "/*.json on the class path");
            }
            return new EndpointTable(files);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the endpoint table", e);
        }
    }

    /** Reads every {@code *.json} of a folder (the generator reads the source folder) */
    public static EndpointTable load(Path folder) {
        Map<String, TableFile> files = new TreeMap<>();
        try (Stream<Path> paths = Files.list(folder)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String name = stripJson(path.getFileName().toString());
                try (InputStream in = Files.newInputStream(path)) {
                    files.put(name, read(in, name));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the endpoint table in " + folder, e);
        }
        return new EndpointTable(files);
    }

    /** Reads a table file; a problem of an entry is reported with the entry's key */
    private static TableFile read(InputStream in, String name) {
        JsonNode tree;
        try {
            tree = MAPPER.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(FOLDER + "/" + name + ".json is not JSON: " + e.getMessage(), e);
        }
        JsonNode entries = tree instanceof ObjectNode object ? object.remove("entries") : null;
        TableFile file;
        try {
            file = MAPPER.treeToValue(tree, TableFile.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException(FOLDER + "/" + name + ".json cannot be read: " + firstLine(e), e);
        }
        if (entries != null) {
            for (JsonNode node : entries) {
                try {
                    file.entries.add(MAPPER.treeToValue(node, EndpointEntry.class));
                } catch (RuntimeException e) {
                    JsonNode key = node.get("key");
                    throw new IllegalStateException(FOLDER + "/" + name + ".json, the entry "
                            + (key == null ? "without a key" : key.asString()) + ": " + firstLine(e), e);
                }
            }
        }
        return file;
    }

    /** The first line of an exception's message (Jackson adds the source and a byte offset after it) */
    private static String firstLine(Exception e) {
        String message = String.valueOf(e.getMessage());
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }

    private static String stripJson(String fileName) {
        return fileName.substring(0, fileName.length() - ".json".length());
    }

    public Map<String, TableFile> files() {
        return files;
    }

    public Collection<EndpointEntry> entries() {
        return byKey.values();
    }

    public EndpointEntry entry(String key) {
        return byKey.get(key);
    }

    /** The entry that lists the verb and path pattern, as its key or in {@code alsoAt} */
    public EndpointEntry entryAt(String verb, String pattern) {
        EndpointEntry entry = byKey.get(verb + " " + pattern);
        if (entry != null) {
            return entry;
        }
        for (EndpointEntry e : byKey.values()) {
            if (e.verb().equals(verb) && e.alsoAt.contains(pattern)) {
                return e;
            }
        }
        return null;
    }

    /** The name of the file that holds the entry */
    public String groupOf(EndpointEntry entry) {
        return groupOf.get(entry);
    }

    /** The file that claims the controller, or null */
    public String groupOfController(String controllerSimpleName) {
        return controllerGroup.get(controllerSimpleName);
    }

    /** Every background fault of every file */
    public List<BackgroundFault> backgroundKnownFaults() {
        return files.values().stream().flatMap(f -> f.backgroundKnownFaults.stream()).toList();
    }

    // ------------------------------------------------------------------------------------------- //
    // Writing (the generator)
    // ------------------------------------------------------------------------------------------- //

    /** Writes a table file in the table's layout: one field per line, short values on one line */
    static void write(TableFile file, Path path) throws IOException {
        file.entries.sort(Comparator.comparing((EndpointEntry e) -> e.path()).thenComparing(EndpointEntry::verb));
        JsonNode node = MAPPER.valueToTree(file);
        StringBuilder out = new StringBuilder();
        print(node, 0, out);
        out.append('\n');
        String text = out.toString();
        // Keep the line ending the checkout gave the file, so that a file the generator did not change stays clean
        if (Files.isRegularFile(path) && Files.readString(path, StandardCharsets.UTF_8).contains("\r\n")) {
            text = text.replace("\n", "\r\n");
        }
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static final int LINE = 110;

    private static void print(JsonNode node, int indent, StringBuilder out) {
        String compact = compact(node);
        boolean entry = node.isObject() && node.has("key") && node.has("handler");
        if ((!node.isContainer() || compact.length() + indent <= LINE || node.isEmpty())
                && !(node.isObject() && node.has("entries")) && !entry) {
            out.append(compact);
            return;
        }
        String pad = " ".repeat(indent + 2);
        if (node.isArray()) {
            out.append("[\n");
            int i = 0;
            for (JsonNode element : node.values()) {
                out.append(pad);
                print(element, indent + 2, out);
                out.append(++i < node.size() ? ",\n" : "\n");
            }
            out.append(" ".repeat(indent)).append(']');
        } else {
            out.append("{\n");
            int i = 0;
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                out.append(pad).append(MAPPER.writeValueAsString(field.getKey())).append(": ");
                print(field.getValue(), indent + 2, out);
                out.append(++i < node.size() ? ",\n" : "\n");
            }
            out.append(" ".repeat(indent)).append('}');
        }
    }

    /** One line, with a space after each colon and comma */
    private static String compact(JsonNode node) {
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.values().forEach(v -> values.add(compact(v)));
            return "[" + String.join(", ", values) + "]";
        }
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            node.properties().forEach(f -> fields.add(MAPPER.writeValueAsString(f.getKey()) + ": " + compact(f.getValue())));
            return "{" + String.join(", ", fields) + "}";
        }
        return MAPPER.writeValueAsString(node);
    }
}
