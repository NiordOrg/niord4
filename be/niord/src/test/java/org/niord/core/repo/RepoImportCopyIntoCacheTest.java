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
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.repo.RepoImportService.Outcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The fallback of the repository import's move of a derived file into the cache tree ({@link
 * RepoImportService#copyIntoCache}): copied with its attributes, then deleted from the tree. A tree that refuses the
 * deletion, a read-only copy of the old tree, keeps the file, which still counts as moved; a copy that fails stays a
 * failure of the item.
 */
class RepoImportCopyIntoCacheTest {

    private static final Instant WRITTEN = Instant.ofEpochMilli(1_700_000_000_000L);

    @TempDir
    Path dir;

    @Test
    void movesTheFileWhenTheTreeAllowsItsDeletion() throws IOException {
        Path file = derivedFile();
        Path target = Files.createDirectories(dir.resolve("cache")).resolve("a_thumb_64.png");

        assertEquals(Outcome.MOVED, RepoImportService.copyIntoCache(file, target));
        assertFalse(Files.exists(file));
        assertEquals("a thumbnail", Files.readString(target, StandardCharsets.UTF_8));
        assertEquals(WRITTEN, Files.getLastModifiedTime(target).toInstant(), "its modification time is kept");
    }

    @Test
    void countsAFileTheTreeKeepsAsMoved() throws IOException {
        Path file = derivedFile();
        Path target = Files.createDirectories(dir.resolve("cache")).resolve("a_thumb_64.png");
        Runnable writable = readOnly(file);
        try {
            assertEquals(Outcome.MOVED, RepoImportService.copyIntoCache(file, target),
                    "the copy in the cache tree is what the move was for");
            assertTrue(Files.exists(file), "the read-only tree keeps the file");
            assertEquals("a thumbnail", Files.readString(target, StandardCharsets.UTF_8));
            assertEquals(WRITTEN, Files.getLastModifiedTime(target).toInstant());
        } finally {
            writable.run();
            // On Windows the copy carries the read-only attribute along with the others
            if (Files.getFileAttributeView(target, PosixFileAttributeView.class) == null && Files.exists(target)) {
                Files.getFileAttributeView(target, DosFileAttributeView.class).setReadOnly(false);
            }
        }
    }

    @Test
    void aCopyThatFailsIsAFailure() throws IOException {
        Path file = derivedFile();
        Path target = dir.resolve("no-such-folder").resolve("a_thumb_64.png");

        assertThrows(NoSuchFileException.class, () -> RepoImportService.copyIntoCache(file, target));
        assertTrue(Files.exists(file), "nothing is deleted when the copy fails");
    }

    private Path derivedFile() throws IOException {
        Path tree = Files.createDirectories(dir.resolve("tree"));
        Path file = tree.resolve("a_thumb_64.png");
        Files.writeString(file, "a thumbnail", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(WRITTEN));
        return file;
    }

    /**
     * Makes the file undeletable where it lies, as on a read-only tree: its folder read-only on a POSIX file system,
     * the file read-only on Windows. Skips the test where the process may delete it anyway (run as root).
     *
     * @return what makes it deletable again
     */
    private static Runnable readOnly(Path file) throws IOException {
        Path folder = file.getParent();
        PosixFileAttributeView posix = Files.getFileAttributeView(folder, PosixFileAttributeView.class);
        Runnable writable;
        if (posix != null) {
            Path probe = Files.writeString(folder.resolve("probe"), "", StandardCharsets.UTF_8);
            posix.setPermissions(PosixFilePermissions.fromString("r-xr-xr-x"));
            writable = () -> {
                try {
                    posix.setPermissions(PosixFilePermissions.fromString("rwxr-xr-x"));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };
            boolean refused;
            try {
                Files.delete(probe);
                refused = false;
            } catch (IOException e) {
                refused = true;
            }
            if (!refused) {
                writable.run();
            }
            assumeTrue(refused, "this process may delete in a read-only folder");
        } else {
            DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
            dos.setReadOnly(true);
            writable = () -> {
                try {
                    dos.setReadOnly(false);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };
        }
        return writable;
    }
}
