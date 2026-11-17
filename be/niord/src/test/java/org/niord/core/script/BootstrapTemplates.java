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
package org.niord.core.script;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The base product's templates and scripts as the unit tests read them: by path from the module's
 * {@code bootstrap/templates/} (bootstrap data since C4.1, in no jar since C4.2a), each under its
 * {@code ScriptResource} path {@code templates/<path in the folder>}, as the bootstrap runner imports them.
 */
final class BootstrapTemplates {

    /** The base product's templates folder, relative to the module */
    static final Path FOLDER = Path.of("bootstrap/templates");

    private BootstrapTemplates() {
    }

    /**
     * Reads the folder's files, as they are on disk
     *
     * @param folder the templates folder
     * @return the contents by {@code templates/<path in the folder>}, sorted
     */
    static Map<String, String> read(Path folder) {
        Map<String, String> templates = new TreeMap<>();
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                templates.put("templates/" + folder.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + folder.toAbsolutePath(), e);
        }
        return templates;
    }

    /**
     * Returns the base product's templates as rows, which a test may add its own to
     *
     * @return the rows by path, mutable
     */
    static Map<String, ScriptResource> rows() {
        Map<String, ScriptResource> rows = new TreeMap<>();
        read(FOLDER).forEach((path, content) -> rows.put(path, row(path, content)));
        return rows;
    }

    /**
     * Returns a row of the given path and content, stamped so the loader's {@code getLastModified} works
     *
     * @param path    the path
     * @param content the content
     * @return the row
     */
    static ScriptResource row(String path, String content) {
        ScriptResource row = new ScriptResource();
        row.setPath(path);
        row.setContent(content);
        Date now = new Date();
        row.setCreated(now);
        row.setUpdated(now);
        return row;
    }

    /**
     * Makes a mocked {@link ScriptResourceService} answer the template loader from the given rows, as a database
     * would: the rows put into the map later are found too
     *
     * @param resources the mock
     * @param rows      the rows by path
     */
    static void stub(ScriptResourceService resources, Map<String, ScriptResource> rows) {
        when(resources.findAllScriptResourcePaths()).thenAnswer(invocation -> new HashSet<>(rows.keySet()));
        when(resources.findByPath(anyString())).thenAnswer(invocation -> rows.get(invocation.<String>getArgument(0)));
    }
}
