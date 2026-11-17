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
package org.niord.core.repo;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link RepoPaths#isBelowFolder}: the lexical rule a stored repository path is checked with before
 * it is saved, for paths that are resolved against the repository later.
 */
class RepositoryFolderContainmentTest {

    private static final String FOLDER = "messages/a/ab/uid";

    @Test
    void aPathBelowTheFolderMatches() {
        assertTrue(RepoPaths.isBelowFolder(FOLDER + "/1/photo.jpg", FOLDER));
        assertTrue(RepoPaths.isBelowFolder(FOLDER + "/1/photo%20(1).jpg", FOLDER),
                "an encoded file name is just a name");
        assertTrue(RepoPaths.isBelowFolder(FOLDER + "/1/../2/photo.jpg", FOLDER),
                "a path that stays below the folder once normalised matches");
    }

    @Test
    void aPathThatClimbsOutOfTheFolderDoesNotMatch() {
        assertFalse(RepoPaths.isBelowFolder(FOLDER + "/../other-uid/1/x.jpg", FOLDER));
        assertFalse(RepoPaths.isBelowFolder(FOLDER + "/1/../../../../../etc/passwd", FOLDER));
        assertFalse(RepoPaths.isBelowFolder("messages/a/ab/other-uid/1/x.jpg", FOLDER));
        assertFalse(RepoPaths.isBelowFolder(FOLDER + "-other/1/x.jpg", FOLDER),
                "a sibling whose name starts like the folder is not below it");
    }

    @Test
    void theFolderItselfDoesNotMatch() {
        assertFalse(RepoPaths.isBelowFolder(FOLDER, FOLDER));
        assertFalse(RepoPaths.isBelowFolder(FOLDER + "/1/..", FOLDER));
    }

    @Test
    void anAbsolutePathDoesNotMatch() {
        String absolute = Path.of(FOLDER, "1", "x.jpg").toAbsolutePath().toString();
        assertFalse(RepoPaths.isBelowFolder(absolute, FOLDER));
        assertFalse(RepoPaths.isBelowFolder("/" + FOLDER + "/1/x.jpg", FOLDER));
    }

    @Test
    void blankValuesAndAFolderOutsideTheRepositoryDoNotMatch() {
        assertFalse(RepoPaths.isBelowFolder(null, FOLDER));
        assertFalse(RepoPaths.isBelowFolder("  ", FOLDER));
        assertFalse(RepoPaths.isBelowFolder(FOLDER + "/x.jpg", null));
        assertFalse(RepoPaths.isBelowFolder("../outside/x.jpg", "../outside"));
        assertFalse(RepoPaths.isBelowFolder("x.jpg", "."));
    }

    /** A served root is nobody's folder: a path must lie strictly below it, whatever spelling it arrives in */
    @Test
    void aServedRootItselfIsNotServed() {
        assertTrue(RepoPaths.isServed("publications/x"));
        assertNull(RepoPaths.served("publications"));
        assertNull(RepoPaths.served("publications/"));
        assertNull(RepoPaths.served("publications/."));
        assertNull(RepoPaths.served("publications/x/.."));
        assertNull(RepoPaths.served("archive/publications/x"), "the archive is never served");
        assertEquals("messages/a/ab/uid", RepoPaths.served("messages/a/ab/uid/"));
    }
}
