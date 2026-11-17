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
package org.niord.secom.pki;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the writable-file renewal backend: only file-backed locations are supported,
 * the replace is atomic (temp + rename in the target directory) and leaves no temp files
 * behind - neither after a successful write nor after a failed one.
 */
public class FileKeystoreWriterTest {

    final FileKeystoreWriter writer = new FileKeystoreWriter();

    @Test
    public void supportsOnlyFileBackedLocations() {
        assertTrue(writer.supports("file:/tmp/keystore.p12"));
        assertTrue(writer.supports("/tmp/keystore.p12"));
        assertFalse(writer.supports("classpath:secom-test/keystore-test.p12"));
        assertFalse(writer.supports(null));
    }

    @Test
    public void replacesTheTargetAtomicallyWithoutLeftovers(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("keystore.p12");
        Files.write(target, "the old store".getBytes(StandardCharsets.UTF_8));
        byte[] renewed = "the renewed store".getBytes(StandardCharsets.UTF_8);

        writer.write("file:" + target, renewed);

        assertArrayEquals(renewed, Files.readAllBytes(target));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of(target), files.toList(), "no temp files left behind");
        }
    }

    @Test
    public void acceptsBareAbsolutePaths(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("keystore.p12");
        byte[] renewed = "renewed".getBytes(StandardCharsets.UTF_8);

        writer.write(target.toString(), renewed);

        assertArrayEquals(renewed, Files.readAllBytes(target));
    }

    @Test
    public void aFailedWriteLeavesNoTempFiles(@TempDir Path dir) {
        Path missingParent = dir.resolve("no-such-dir").resolve("keystore.p12");

        assertThrows(Exception.class, () -> writer.write("file:" + missingParent, "bytes".getBytes()));

        assertFalse(Files.exists(dir.resolve("no-such-dir")));
    }

    @Test
    public void fileWritesArePickedUpImmediately() {
        assertFalse(writer.deferredPickup(), "a file write is read-your-write - the renewal verifies it live");
    }
}
