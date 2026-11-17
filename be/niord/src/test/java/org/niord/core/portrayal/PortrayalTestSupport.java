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
package org.niord.core.portrayal;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The portrayal definition for tests outside its package, such as the map-image renderer's: read from the class
 * path as the application reads it.
 */
public final class PortrayalTestSupport {

    private PortrayalTestSupport() {
    }

    /** The definition the Maven build copied onto the class path; fails the test when it is not available */
    public static PortrayalDefinition classPathDefinition() {
        PortrayalDefinition definition = new PortrayalDefinition();
        if (!definition.available()) {
            fail("The portrayal definition is not on the class path: see the <resources> of be/niord/pom.xml");
        }
        return definition;
    }

    /** A definition that could not be read, as the bean is when the class path holds no usable definition */
    public static PortrayalDefinition unavailableDefinition() {
        return PortrayalDefinition.fromResources(name -> null);
    }

    /**
     * Finds a file or folder of the repository from the working directory (the module's directory under Maven)
     * upwards
     *
     * @param path the path from the repository root, e.g. {@code fe/shared-map/portrayal/fixtures/cases.json}
     */
    public static Path repositoryPath(String path) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(path);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return fail(path + " not found in " + Path.of("").toAbsolutePath() + " or above it");
    }
}
