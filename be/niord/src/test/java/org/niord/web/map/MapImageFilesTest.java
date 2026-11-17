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
package org.niord.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MapImageFiles}: which files of a message folder are generated map images of the same size. */
class MapImageFilesTest {

    @TempDir
    Path folder;

    @Test
    void theOtherImagesAreThoseOfTheSameSizeUnderAnotherName() throws Exception {
        for (String name : List.of("map_256.png", "map_256_053d3a24b6af.png", "map_256_1a2b3c4d5e6f.png",
                "map_512.png", "map_512_053d3a24b6af.png", "custom_thumb_256.png", "map_256_Draft.png",
                "map_256.png.tmp", "chart.png")) {
            Files.write(folder.resolve(name), new byte[]{1});
        }

        Set<String> others = MapImageFiles.others(folder.resolve("map_256_053d3a24b6af.png")).stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toSet());

        assertEquals(Set.of("map_256.png", "map_256_1a2b3c4d5e6f.png"), others);
    }

    @Test
    void aMissingFolderHasNoOtherImages() throws Exception {
        assertTrue(MapImageFiles.others(folder.resolve("gone").resolve("map_256_053d3a24b6af.png")).isEmpty());
    }

    @Test
    void aFileThatIsNoGeneratedImageHasNoOtherImages() throws Exception {
        Files.write(folder.resolve("map_256.png"), new byte[]{1});

        assertTrue(MapImageFiles.others(folder.resolve("custom_thumb_256.png")).isEmpty());
    }

    @Test
    void theUnversionedImageIsTheNameWithoutAVersionBesideTheImage() {
        Path image = folder.resolve("map_256_053d3a24b6af.png");

        assertEquals(folder.resolve("map_256.png"), MapImageFiles.unversioned(image));
        assertEquals(folder.resolve("map_512.png"), MapImageFiles.unversioned(folder.resolve("map_512_1a2b3c4d5e6f.png")));
        assertNull(MapImageFiles.unversioned(folder.resolve("map_256.png")), "an unversioned name has none besides it");
        assertNull(MapImageFiles.unversioned(folder.resolve("custom_thumb_256.png")), "not a generated image");
    }

    @Test
    void theNamePatternTakesTheSizeAndTheVersion() {
        assertTrue(MapImageFiles.GENERATED.matcher("map_256.png").matches());
        assertTrue(MapImageFiles.GENERATED.matcher("map_1024_053d3a24b6af.png").matches());
        assertFalse(MapImageFiles.GENERATED.matcher("map_12345.png").matches(), "at most four digits of size");
        assertFalse(MapImageFiles.GENERATED.matcher("map_256_053D3A24B6AF.png").matches(), "lower-case hex only");
        assertFalse(MapImageFiles.GENERATED.matcher("map_256_.png").matches());
    }
}
