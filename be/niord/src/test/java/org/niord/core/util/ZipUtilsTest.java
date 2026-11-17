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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ZipUtils#resolveEntry}: the containment rule every zip extraction applies to an entry name.
 */
class ZipUtilsTest {

    @Test
    void aNestedEntryResolvesInsideTheDestination(@TempDir Path tmp) throws Exception {
        Path destination = Files.createDirectories(tmp.resolve("extract"));

        Path target = ZipUtils.resolveEntry(destination, "messages/a/b/photo.jpg");

        assertEquals(destination.toAbsolutePath().normalize().resolve("messages/a/b/photo.jpg"), target);
    }

    @Test
    void anEntryThatReturnsThroughASubFolderStaysInsideAndIsAccepted(@TempDir Path tmp) throws Exception {
        Path destination = Files.createDirectories(tmp.resolve("extract"));

        Path target = ZipUtils.resolveEntry(destination, "a/../b.txt");

        assertEquals(destination.toAbsolutePath().normalize().resolve("b.txt"), target);
    }

    @Test
    void anEntryWithParentSegmentsThatLeavesTheDestinationIsRefused(@TempDir Path tmp) throws Exception {
        Path destination = Files.createDirectories(tmp.resolve("extract"));

        for (String name : new String[] { "../escaped.txt", "a/../../escaped.txt", "a/b/../../../escaped.txt" }) {
            IOException e = assertThrows(IOException.class, () -> ZipUtils.resolveEntry(destination, name),
                    "'" + name + "' must be refused");
            assertTrue(e.getMessage().contains(name), "the refusal names the entry");
        }
    }

    @Test
    void anAbsoluteEntryNameIsRefused(@TempDir Path tmp) throws Exception {
        Path destination = Files.createDirectories(tmp.resolve("extract"));
        // Absolute in the form of the platform the test runs on ("/..." or "C:\...")
        String absolute = tmp.resolve("outside.txt").toAbsolutePath().toString();

        assertThrows(IOException.class, () -> ZipUtils.resolveEntry(destination, absolute));
    }

    @Test
    void aDestinationThatNeedsNormalisingStillContainsItsEntries(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("extract"));
        Path destination = tmp.resolve("other").resolve("..").resolve("extract");

        assertEquals(tmp.toAbsolutePath().normalize().resolve("extract").resolve("x.json"),
                ZipUtils.resolveEntry(destination, "x.json"));
        assertThrows(IOException.class, () -> ZipUtils.resolveEntry(destination, "../other/x.json"));
    }
}
