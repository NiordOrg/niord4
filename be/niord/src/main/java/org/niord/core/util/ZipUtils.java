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

package org.niord.core.util;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Utility methods for extracting zip archives
 */
public class ZipUtils {

    /**
     * Don't instantiate this class
     */
    private ZipUtils() {
    }

    /**
     * Resolves the name of a zip entry inside the folder an archive is extracted to.
     * <p>
     * Every extraction of a zip archive goes through this. An entry name is data from the archive, and
     * neither {@link java.util.zip.ZipFile} nor {@link java.util.zip.ZipInputStream} constrains it: a name
     * with {@code ".."} segments or in absolute form would otherwise be written outside the folder. The name
     * is resolved against the folder and normalised, and the result must stay inside the folder. A name that
     * only passes through a sub-folder on its way back ({@code "a/../b.txt"}) stays inside and is accepted.
     *
     * @param destination the folder the archive is extracted to
     * @param entryName the name of the zip entry
     * @return the normalised, absolute path to write the entry to
     * @throws IOException when the entry would lie outside the folder, or its name is not a valid path
     */
    public static Path resolveEntry(Path destination, String entryName) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Path target;
        try {
            target = root.resolve(entryName).normalize();
        } catch (InvalidPathException e) {
            throw new IOException("Illegal archive entry: " + entryName, e);
        }
        if (!target.startsWith(root)) {
            throw new IOException("Illegal archive entry: " + entryName);
        }
        return target;
    }
}
