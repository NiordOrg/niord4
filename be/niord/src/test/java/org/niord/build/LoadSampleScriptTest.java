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
package org.niord.build;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The sample loader's own refusals, {@code be/niord/scripts/load-sample.sh} (docs/fe-country-split-plan.md P9's
 * safety rules): the argument rules come before any container is asked, {@code --container}'s empty value among
 * them, and the container rule asks the container {@code --container} names. The script runs under bash with a
 * {@code docker} first on the PATH that only fails and writes down what it was asked, so no real Docker is ever
 * reached; a refusal that came from it would name the container, not the rule. The database is {@code niord_sample} or a
 * country launcher's {@code niord_sample_<cc>}, and no other name. The guard inside the SQL is exercised by
 * {@code SampleOnACountryBaseITTest} and the regeneration's schema check, not here. Skipped where no bash is found (on
 * Windows only Git Bash's, never System32's WSL launcher).
 */
class LoadSampleScriptTest {

    static final Path SCRIPT = Path.of("scripts/load-sample.sh");

    @TempDir
    Path dir;

    Path shims;

    /** Where the docker shim writes each call's arguments: the loader sends a docker call's error output nowhere */
    Path dockerCalls;

    /** The outcome of a run */
    record Result(int status, String output) {
        @Override
        public String toString() {
            return "exit " + status + "\n" + output;
        }
    }

    @BeforeEach
    void docker() throws IOException {
        shims = Files.createDirectories(dir.resolve("bin"));
        Path docker = shims.resolve("docker");
        dockerCalls = dir.resolve("docker-calls");
        // A path with forward slashes, which Git Bash reads as well as any other bash
        String calls = dockerCalls.toAbsolutePath().toString().replace('\\', '/');
        Files.writeString(docker, "#!/bin/sh\necho \"docker shim: $*\" >&2\necho \"$*\" >> '" + calls + "'\nexit 1\n",
                StandardCharsets.UTF_8);
        docker.toFile().setExecutable(true);
    }

    /** An argument the loader does not know is a usage error */
    @Test
    void anUnknownArgumentPrintsTheUsage() throws Exception {
        Result result = load("--repo", "repo");
        assertEquals(2, result.status(), result.toString());
        assertTrue(result.output().contains("Unknown argument: --repo"), result.toString());
        assertTrue(result.output().contains("Usage:"), result.toString());
        assertTrue(Files.notExists(dockerCalls), "no docker call on a usage error: " + result);
    }

    /** The --home of the days the attachments were files is accepted and ignored, so an older launcher still loads */
    @Test
    void theHomeOfTheOldLaunchersIsAcceptedAndIgnored() throws Exception {
        Result result = load("--home", "home", "--container", "x");
        assertEquals(1, result.status(), result.toString());
        assertTrue(result.output().contains("the container x does not run, or publishes no port 5432"),
                "past the argument rules, to the container rule: " + result);
    }

    /** The usage text names the cure as every other document writes it, from the repository root, and ends whole */
    @Test
    void theUsageNamesTheCureFromTheRepositoryRoot() throws Exception {
        Result result = load("--help");
        assertTrue(result.output().contains("./start-local.sh --sample --reset (from the repository root)"),
                result.toString());
        assertTrue(!result.output().contains("../start-local.sh"), "a path relative to the script's folder: " + result);
        assertTrue(result.output().contains("Exit codes: 0 loaded, 1 refused or failed, 2 usage, 3 already loaded."),
                "the usage text is cut short: " + result);
    }

    @Test
    void aHostThatIsNotLoopbackIsRefused() throws Exception {
        Result result = load("--host", "10.0.0.1");
        assertEquals(1, result.status(), result.toString());
        assertTrue(result.output().contains("load-sample.sh refuses: the database host must be loopback"),
                result.toString());
        assertTrue(!result.output().contains("docker shim"), "refused before any docker call: " + result);
        assertTrue(Files.notExists(dockerCalls), "refused before any docker call: " + result);
    }

    @Test
    void aDatabaseOtherThanTheSampleOneIsRefused() throws Exception {
        Result result = load("--db", "niord");
        assertEquals(1, result.status(), result.toString());
        assertTrue(result.output().contains("load-sample.sh refuses: the sample is loaded only into the database "
                + "niord_sample or niord_sample_<cc> (a two-letter lower-case country code), not niord"), result.toString());
        assertTrue(!result.output().contains("docker shim"), "refused before any docker call: " + result);
        assertTrue(Files.notExists(dockerCalls), "refused before any docker call: " + result);
    }

    /** A country's sample database passes the database rule: the container rule, the next one, refuses it here */
    @Test
    void aCountrysSampleDatabasePassesTheDatabaseRule() throws Exception {
        Result result = load("--db", "niord_sample_dk", "--home", "home");
        assertEquals(1, result.status(), result.toString());
        assertTrue(result.output().contains(
                "load-sample.sh refuses: the container niord-db does not run, or publishes no port 5432"), result.toString());
        assertEquals("port niord-db 5432/tcp", Files.readString(dockerCalls, StandardCharsets.UTF_8).strip(),
                "the docker calls: " + result);
    }

    /** Only two lower-case ASCII letters make a country code: anything else is refused before any docker call */
    @Test
    void aNameLikeACountrysSampleDatabaseIsRefused() throws Exception {
        for (String db : List.of("niord_sample_DK", "niord_sample_dnk", "niord_sample_", "niord_sample_d1",
                "niord_sample_æø", "niord_dk")) {
            Result result = load("--db", db, "--home", "home");
            assertEquals(1, result.status(), db + ": " + result);
            assertTrue(result.output().contains("load-sample.sh refuses: the sample is loaded only into the database "
                    + "niord_sample or niord_sample_<cc>"), db + ": " + result);
            assertTrue(Files.notExists(dockerCalls), "refused before any docker call: " + db + ": " + result);
        }
    }

    @Test
    void anEmptyContainerIsUsage() throws Exception {
        Result result = load("--container", "");
        assertEquals(2, result.status(), result.toString());
        assertTrue(result.output().contains("Usage:"), result.toString());
        assertTrue(Files.notExists(dockerCalls), "no docker call on a usage error: " + result);
    }

    /** --container names the container the loopback rule asks; one that does not run is refused by that rule */
    @Test
    void theNamedContainerIsTheOneAsked() throws Exception {
        Result result = load("--container", "x");
        assertEquals(1, result.status(), result.toString());
        assertTrue(result.output().contains(
                "load-sample.sh refuses: the container x does not run, or publishes no port 5432"), result.toString());
        assertEquals("port x 5432/tcp", Files.readString(dockerCalls, StandardCharsets.UTF_8).strip(),
                "the docker calls: " + result);
    }

    Result load(String... args) throws Exception {
        String bash = bash();
        assumeTrue(bash != null, "no bash on this machine (on Windows, Git Bash)");
        List<String> command = new ArrayList<>(List.of(bash, SCRIPT.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        String path = env.getOrDefault("PATH", "");
        env.put("PATH", shims.toAbsolutePath() + File.pathSeparator + path);
        Process process = pb.start();
        process.getOutputStream().close();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "load-sample.sh did not end");
        return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    /** bash from the PATH; on Windows Git Bash's, looked for by its install folder, never System32's WSL launcher */
    static String bash() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        if (!windows) {
            for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
                if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, "bash"))) {
                    return Path.of(dir, "bash").toString();
                }
            }
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        for (String root : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramW6432"),
                System.getenv("LOCALAPPDATA") == null ? null : System.getenv("LOCALAPPDATA") + "\\Programs"}) {
            if (root != null) {
                candidates.add(Path.of(root, "Git", "bin", "bash.exe"));
            }
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            // Git for Windows puts its cmd\ folder on the PATH; bash.exe is in bin\ beside it
            if (dir.toLowerCase(Locale.ROOT).replace('/', '\\').endsWith("\\git\\cmd")) {
                candidates.add(Path.of(dir).resolveSibling("bin").resolve("bash.exe"));
            }
        }
        return candidates.stream().filter(Files::isRegularFile).map(Path::toString).findFirst().orElse(null);
    }
}
