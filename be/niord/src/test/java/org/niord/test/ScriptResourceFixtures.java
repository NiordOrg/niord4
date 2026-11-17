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
package org.niord.test;

import org.niord.core.script.ScriptResource;
import org.niord.core.script.ScriptResourceService;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Niord's own test templates and scripts as database rows ({@code templates/it/*.ftl},
 * {@code templates/mailinglist/it-test.ftl}, {@code templates/messages/it-message-mail*.ftl},
 * {@code test-scripts/*.js}, all test resources). Since C4.2a the code reads a template only from the database
 * (docs/code-data-config-plan.md §6.2), so a test that renders one of them first adds it, as copy-on-use used to on
 * the first render. Needs {@link Tx}, which {@link ITTest} binds before each test.
 */
public final class ScriptResourceFixtures {

    private ScriptResourceFixtures() {
    }

    /**
     * Creates, in a transaction of its own, a row from the test class path for each path that has none; a path
     * with a row is left as it is
     *
     * @param resourceService the script resource service
     * @param classPathPaths  the paths, as on the test class path and as the row's path
     */
    public static void ensure(ScriptResourceService resourceService, String... classPathPaths) {
        Tx.requiringNew().run(() -> {
            for (String path : classPathPaths) {
                if (resourceService.findByPath(path) == null) {
                    ScriptResource resource = new ScriptResource();
                    resource.setPath(path);
                    resource.setContent(read(path));
                    resourceService.createScriptResource(resource);
                }
            }
        });
    }

    private static String read(String path) {
        try (InputStream in = ScriptResourceFixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("No test resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the test resource " + path, e);
        }
    }
}
