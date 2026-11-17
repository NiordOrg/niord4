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
package org.niord.secom;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Package-boundary guard for the SECOM integration (docs/secom-design.md D2):
 * with no separate Maven module, this test is what keeps SECOM types from creeping into core
 * code — only {@code org.niord.secom} may import the GRAD SECOMLib ({@code org.grad}) or
 * RESTEasy ({@code org.jboss.resteasy}) APIs.
 */
public class SecomImportBoundaryTest {

    /**
     * Pre-existing exceptions, not part of the SECOM integration - do not grow this list:
     * RepositoryServletFilter has imported resteasy's UnhandledException since the Quarkus port.
     */
    private static final List<String> LEGACY_ALLOWLIST = List.of(
            "org/niord/core/repo/RepositoryServletFilter.java");

    @Test
    public void onlyTheSecomPackageImportsSecomLibOrResteasy() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        List<String> violations;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            violations = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(sourceRoot.resolve("org/niord/secom")))
                    .filter(SecomImportBoundaryTest::importsSecomLibOrResteasy)
                    .map(p -> sourceRoot.relativize(p).toString())
                    .filter(p -> !LEGACY_ALLOWLIST.contains(p))
                    .sorted()
                    .toList();
        }
        assertEquals(List.of(), violations,
                "Only org.niord.secom may import org.grad.* / org.jboss.resteasy.* - move the SECOM "
                        + "dependency behind the org.niord.secom boundary instead");
    }

    private static boolean importsSecomLibOrResteasy(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .anyMatch(line -> line.startsWith("import org.grad.")
                            || line.startsWith("import org.jboss.resteasy."));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
