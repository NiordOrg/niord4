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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The jar-set hash of the image tools, {@code be/k8s/scripts/split-jar.sh --hash}, and the {@code sha256} helper it
 * shares with {@code publish-images.sh}, give sha256sum's two-space form {@code <hash>  <file>} whatever mode
 * sha256sum prints, so that a split on Git Bash names a dependency set by the hash Linux gives it. Git Bash's
 * sha256sum marks binary mode with {@code <hash> *<file>}; here a stub sha256sum first on the PATH prints that form on
 * every machine, and the hash it must come to is computed in Java over the two-space lines. Skipped where no bash is
 * found, as {@link LoadSampleScriptTest} is, and skipped where no sha256sum is found, as the stub wraps the real one.
 */
class SplitJarHashTest {

    /** The image tools; the tests run in the module's folder, be/niord */
    static final Path SPLIT_JAR = Path.of("../k8s/scripts/split-jar.sh");

    static final Path PUBLISH_IMAGES = Path.of("../k8s/scripts/publish-images.sh");

    /** The helper as a script defines it: from its first line to the closing brace at the start of a line */
    static final Pattern HELPER = Pattern.compile("(?ms)^sha256\\(\\) \\{\\n.*?^\\}\\n");

    /**
     * Names whose order differs between a locale's sort and LC_ALL=C's (the upper-case name first), and bytes that
     * differ per file. The scripts only hash the files, so they need not be zip archives.
     */
    static final Map<String, String> JARS = Map.of("a.jar", "first", "B.jar", "second", "c-1.jar", "third");

    /** What the helper is fed on standard input */
    static final String STDIN = "niord";

    /**
     * Runs before the script it is given: finds the real sha256sum before the PATH changes and hands it to the stub,
     * then puts the stub first on the PATH inside bash, as Git for Windows' bash.exe may put /usr/bin ahead of a
     * folder the caller prepends, and the stub would not be reached
     */
    static final String PRELUDE = """
            set -euo pipefail
            REAL_SHA256SUM="$(command -v sha256sum)"; export REAL_SHA256SUM
            if [[ -n "${STUB_DIR:-}" ]]; then
              stub="$STUB_DIR"
              if command -v cygpath >/dev/null 2>&1; then stub="$(cygpath -u "$stub")"; fi
              export PATH="$stub:$PATH"
            fi
            """;

    /** Whether the bash found has a sha256sum on its PATH; asked once */
    static Boolean sha256sumFound;

    @TempDir
    Path dir;

    Path jars;

    Path stubs;

    /** Where the stub writes each call's arguments */
    Path stubCalls;

    /** The outcome of a run */
    record Result(int status, String output) {
        @Override
        public String toString() {
            return "exit " + status + "\n" + output;
        }
    }

    @BeforeEach
    void fixture() throws Exception {
        jars = Files.createDirectories(dir.resolve("jars"));
        for (Map.Entry<String, String> jar : JARS.entrySet()) {
            Files.writeString(jars.resolve(jar.getKey()), jar.getValue(), StandardCharsets.UTF_8);
        }
        stubs = Files.createDirectories(dir.resolve("bin"));
        stubCalls = dir.resolve("stub-calls");
        // The real sha256sum's output with its two spaces turned into binary mode's " *"; output already in that
        // form passes unchanged
        Path stub = stubs.resolve("sha256sum");
        Files.writeString(stub, """
                #!/bin/sh
                echo "$*" >> "$STUB_CALLS"
                "$REAL_SHA256SUM" "$@" | sed 's/^\\([0-9a-f]\\{64\\}\\)  /\\1 */'
                """, StandardCharsets.UTF_8);
        stub.toFile().setExecutable(true);
    }

    @Test
    void theJarSetHashIsTheLinuxOneWhenSha256sumMarksBinaryMode() throws Exception {
        Result sanity = run(true, "sha256sum \"$JARS/a.jar\"\n");
        assertEquals(0, sanity.status(), sanity.toString());
        assertTrue(sanity.output().startsWith(sha256Hex(bytesOf("a.jar")) + " *"),
                "the stub prints the binary-mode form: " + sanity);
        // Git Bash's own sha256sum prints the binary-mode form too, so the output alone does not show the stub ran
        assertTrue(stubWasCalled(), "the stub was not reached by the sanity run");
        Files.delete(stubCalls);

        Result result = run(true, "bash \"$SCRIPT\" --hash \"$JARS\"\n");
        assertEquals(0, result.status(), result.toString());
        assertEquals(linuxJarSetHash(), result.output().strip(), result.toString());
        assertTrue(stubWasCalled(), "the stub was not reached");
    }

    /** Green on Linux whatever the scripts do; on Git Bash, whose sha256sum marks binary mode, the case itself */
    @Test
    void theJarSetHashIsTheLinuxOneWithThisMachinesSha256sum() throws Exception {
        Result result = run(false, "bash \"$SCRIPT\" --hash \"$JARS\"\n");
        assertEquals(0, result.status(), result.toString());
        assertEquals(linuxJarSetHash(), result.output().strip(), result.toString());
    }

    @Test
    void bothScriptsHoldOneHelperThatPrintsTheTwoSpaceForm() throws Exception {
        String helper = helperOf(SPLIT_JAR);
        assertEquals(helper, helperOf(PUBLISH_IMAGES), "publish-images.sh's sha256 helper is not split-jar.sh's");

        Result result = run(true, helper + "cd \"$JARS\"\nsha256 a.jar\nprintf '%s' \"$STDIN\" | sha256\n");
        assertEquals(0, result.status(), result.toString());
        assertEquals(sha256Hex(bytesOf("a.jar")) + "  a.jar\n"
                + sha256Hex(STDIN.getBytes(StandardCharsets.UTF_8)) + "  -\n", result.output(), result.toString());
        assertTrue(stubWasCalled(), "the stub was not reached");
    }

    /** One SHA-256 over the lines {@code <sha256>  <file name>} of the jars, the names in byte order */
    static String linuxJarSetHash() throws Exception {
        List<String> names = new ArrayList<>(JARS.keySet());
        names.sort(null);
        StringBuilder lines = new StringBuilder();
        for (String name : names) {
            lines.append(sha256Hex(bytesOf(name))).append("  ").append(name).append('\n');
        }
        return sha256Hex(lines.toString().getBytes(StandardCharsets.UTF_8));
    }

    static byte[] bytesOf(String jar) {
        return JARS.get(jar).getBytes(StandardCharsets.UTF_8);
    }

    static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    boolean stubWasCalled() throws Exception {
        return Files.exists(stubCalls) && !Files.readString(stubCalls, StandardCharsets.UTF_8).isBlank();
    }

    static String helperOf(Path script) throws Exception {
        Matcher helper = HELPER.matcher(Files.readString(script, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        assertTrue(helper.find(), script + " defines no sha256 helper");
        return helper.group();
    }

    /** Runs the body after the prelude, with the stub first on the PATH or not; standard error goes with the output */
    Result run(boolean stub, String body) throws Exception {
        String bash = LoadSampleScriptTest.bash();
        assumeTrue(bash != null, "no bash on this machine (on Windows, Git Bash)");
        assumeTrue(hasSha256sum(bash), "no sha256sum on this machine");
        // The driver goes to bash as a file, as Windows passes no argument with a quote in it whole; its inputs as
        // environment variables, the paths with forward slashes, which Git Bash reads as well as any other bash
        Path driver = dir.resolve("driver.sh");
        Files.writeString(driver, PRELUDE + body, StandardCharsets.UTF_8);
        ProcessBuilder pb = new ProcessBuilder(bash, slashes(driver)).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.remove("STUB_DIR");
        if (stub) {
            env.put("STUB_DIR", slashes(stubs));
        }
        env.put("STUB_CALLS", slashes(stubCalls));
        env.put("SCRIPT", SPLIT_JAR.toString().replace('\\', '/'));
        env.put("JARS", slashes(jars));
        env.put("STDIN", STDIN);
        Process process = pb.start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the driver did not end");
        return new Result(process.exitValue(), output);
    }

    /** Older macOS has no sha256sum (the scripts fall back to shasum there), and the stub has nothing to wrap */
    static synchronized boolean hasSha256sum(String bash) throws Exception {
        if (sha256sumFound == null) {
            Process process = new ProcessBuilder(bash, "-c", "command -v sha256sum")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "bash did not end");
            sha256sumFound = process.exitValue() == 0;
        }
        return sha256sumFound;
    }

    static String slashes(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }
}
