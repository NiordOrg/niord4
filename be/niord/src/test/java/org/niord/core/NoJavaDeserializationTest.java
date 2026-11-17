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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * No production code reads Java-serialized objects.
 * <p>
 * The batch service once had a reader that ran {@code ObjectInputStream.readObject} over a batch data file,
 * and batch data files are uploads. Nothing called it, and it was removed rather than filtered, so that an
 * upload can never become a deserialization input. This guard keeps a new {@code ObjectInputStream} from
 * arriving unnoticed: one that is really needed must come with an {@code ObjectInputFilter} allow-list and a
 * change to this test.
 */
class NoJavaDeserializationTest {

    @Test
    void noProductionSourceUsesObjectInputStream() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        List<String> users;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            users = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(NoJavaDeserializationTest::mentionsObjectInputStream)
                    .map(p -> sourceRoot.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
        assertEquals(List.of(), users, "production code must not deserialize Java objects");
    }

    private static boolean mentionsObjectInputStream(Path file) {
        try {
            return Files.readString(file).contains("ObjectInputStream");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
