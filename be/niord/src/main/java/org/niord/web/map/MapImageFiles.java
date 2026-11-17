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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The generated map images in a message's repository folder. Each is named {@code map_<size>_<version>.png}, where
 * the version is the portrayal definition's image version (see {@link MessageMapImageGenerator#imageFileName()}),
 * or {@code map_<size>.png}, the name used before the version became part of it. A custom thumbnail
 * ({@code custom_thumb_<size>.png}) is not a generated image.
 *
 * <p>{@code RepositoryService} keeps these files out of a folder's listing with the same pattern.
 */
final class MapImageFiles {

    /** A generated map image; group 1 is the size in pixels, group 2 the image version when the name has one */
    static final Pattern GENERATED = Pattern.compile("map_(\\d{1,4})(?:_([0-9a-f]{1,32}))?\\.png");

    private MapImageFiles() {
    }

    /**
     * Returns the file's modification time: for a generated image, the time the message last changed before the
     * image was drawn.
     * @param file the file
     * @return the modification time, or {@link Long#MIN_VALUE} when there is no such file
     */
    static long modifiedMillis(Path file) throws IOException {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (NoSuchFileException e) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Returns the image of the same size under the name without a version, in the folder of the given one: for
     * {@code map_256_053d3a24b6af.png}, {@code map_256.png}. It is the name every generated image had before the
     * version became part of it, so right after the first deployment of the versioned names it is the image the
     * folder holds.
     *
     * @param image a generated image, which need not exist
     * @return the image under the name without a version, which need not exist; null when the given name is not a
     *         generated image's or has no version
     */
    static Path unversioned(Path image) {
        Matcher own = GENERATED.matcher(image.getFileName().toString());
        if (!own.matches() || own.group(2) == null) {
            return null;
        }
        return image.resolveSibling("map_" + own.group(1) + ".png");
    }

    /**
     * Returns the other generated images of the same size in the folder of the given one: for
     * {@code map_256_053d3a24b6af.png}, a {@code map_256.png} and any {@code map_256_<other version>.png}, but not
     * {@code map_512_053d3a24b6af.png}. Lists the folder once.
     *
     * @param image a generated image, which need not exist
     * @return the other images; empty when the folder does not exist or the name is not a generated image's
     */
    static List<Path> others(Path image) throws IOException {
        String name = image.getFileName().toString();
        Matcher own = GENERATED.matcher(name);
        if (!own.matches()) {
            return List.of();
        }
        String size = own.group(1);
        DirectoryStream.Filter<Path> sameSize = file -> {
            String fileName = file.getFileName().toString();
            Matcher other = GENERATED.matcher(fileName);
            return other.matches() && other.group(1).equals(size) && !fileName.equals(name);
        };
        List<Path> others = new ArrayList<>();
        try (DirectoryStream<Path> folder = Files.newDirectoryStream(image.getParent(), sameSize)) {
            folder.forEach(others::add);
        } catch (NoSuchFileException | NotDirectoryException e) {
            return List.of();
        }
        return others;
    }
}
