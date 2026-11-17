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
package org.niord.core;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The niord-base version: the build filters {@code niord-base.properties}, and the version falls back to the
 * manifest and then to {@code dev} when a copy was not filtered.
 */
class NiordBaseVersionTest {

    @Test
    void theBuildWritesTheProjectVersionIntoTheResource() throws Exception {
        // The compiled resource, as Maven's filtering left it in target/classes
        Properties properties = new Properties();
        try (InputStream in = NiordBaseVersion.class.getResourceAsStream(NiordBaseVersion.RESOURCE)) {
            assertNotNull(in, NiordBaseVersion.RESOURCE + " must be on the class path next to NiordBaseVersion");
            properties.load(in);
        }
        String version = properties.getProperty("version");
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?"),
                "the build must replace the placeholder with the project version, not '" + version + "'");
        assertEquals(version, NiordBaseVersion.get());
    }

    @Test
    void anUnfilteredResourceFallsBackToTheManifestAndThenToDev() {
        assertEquals("4.0.7", NiordBaseVersion.resolve(" 4.0.7 ", "4.0.1"));
        assertEquals("4.0.1", NiordBaseVersion.resolve("@project.version@", "4.0.1"));
        assertEquals("4.0.1", NiordBaseVersion.resolve("${project.version}", "4.0.1"));
        assertEquals("4.0.1", NiordBaseVersion.resolve(null, "4.0.1"));
        assertEquals("dev", NiordBaseVersion.resolve("@project.version@", null));
        assertEquals("dev", NiordBaseVersion.resolve(" ", " "));
    }
}
