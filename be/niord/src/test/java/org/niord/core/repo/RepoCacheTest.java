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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RepoCache}: a repository path's place in the cache tree is below the cache root whatever spelling the
 * path arrives in, a path that is no path of the repository is refused before anything on the volume is read or
 * written at it, and an eviction counts the files it removed.
 */
class RepoCacheTest {

    @TempDir
    Path home;

    private RepoCache cache;

    @BeforeEach
    void cache() {
        cache = new RepoCache();
        // The home is read from the settings once; here it is the test's folder
        ReflectionTestUtils.setField(cache, "home", home);
    }

    @Test
    void aPathIsPlacedUnderTheCacheRootNormalised() {
        Path expected = home.resolve(RepoCache.CACHE_FOLDER).resolve("messages/a/ab/uid");
        assertEquals(expected, cache.pathFor("messages/a/ab/uid"));
        assertEquals(expected, cache.pathFor("messages//a/./ab/x/../uid"), "a spelling that stays inside is normalised");
        assertEquals(expected, cache.pathFor("messages\\a\\ab\\uid"), "backslashes are slashes");
        assertEquals(expected.resolve("map_256.png"), cache.pathFor("messages/a/ab/uid/map_256.png"));
    }

    /** The rule lives here, not in the callers: a stored folder that climbs out never names a place on the volume */
    @Test
    void aPathThatIsNoRepositoryPathIsRefused() {
        for (String path : new String[] {null, "", "/", "../etc/passwd", "/etc/passwd", "messages/../../x",
                "C:\\Windows\\win.ini", "messages/a\0b"}) {
            assertThrows(IllegalArgumentException.class, () -> cache.pathFor(path), "refused: " + path);
        }
    }

    @Test
    void evictRemovesTheFolderAndCountsItsFilesOnly() throws IOException {
        Path folder = cache.pathFor("publications/x/uuid");
        Files.createDirectories(folder.resolve("1"));
        Files.createDirectories(folder.resolve("2/deeper"));
        Files.writeString(folder.resolve("1/a_thumb_64.png"), "a");
        Files.writeString(folder.resolve("1/b_thumb_64.png"), "b");
        Files.writeString(folder.resolve("2/deeper/c_thumb_64.png"), "c");

        assertEquals(3, cache.evict("publications/x/uuid"), "three files; the folders are not counted");
        assertFalse(Files.exists(folder), "the folder's place in the tree goes too");
        assertEquals(0, cache.evict("publications/x/uuid"), "nothing is left to evict");
    }
}
