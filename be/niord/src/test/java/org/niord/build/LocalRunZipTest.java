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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The local-run package, {@code org.niord:niord-local-run:<v>:zip}, as be/scripts/local-run-zip.sh builds it for
 * publish.yml's build job to deploy: the files a country's launcher takes from the base product for a local run. Its
 * entries are exactly the launcher library, bootstrap.sh and load-sample.sh in scripts/, the sample's SQL and
 * attachments in sample/, the compose file, the local realm and the README, bytewise sorted, files only, each
 * stored and dated 1980-01-01T00:00:02; not country-api.md, whose one published home is the country kit's package,
 * org.niord:niord-country-kit. Two builds give the same bytes, the sample's entries are its
 * files byte for byte and every other entry is its source with LF line ends, so a Windows checkout builds the bytes
 * CI builds. Unpacked (no exec bits, as any unzip of it), every script passes {@code bash -n}, the library finds
 * bootstrap.sh beside it and runs it with bash ({@code curl} a stand-in first on the PATH), and load-sample.sh finds
 * the sample at ../sample ({@code docker} a stand-in that answers as a niord-db holding the sample, so it copies the
 * attachments and stops with its exit 3). The package carries the sample by design, P9's one carrier for a local run,
 * and is never an image input: check-no-sample.sh fails on it, and neither the Dockerfile, its .dockerignore nor an
 * image script names it (release-workflows.test.mjs holds the same of publish.yml's jobs). No real Docker or backend
 * is reached. Skipped where no bash is found, as {@link LoadSampleScriptTest} is.
 */
class LocalRunZipTest {

    /** The tests run in the module's folder, be/niord */
    static final Path SCRIPT = Path.of("../scripts/local-run-zip.sh");
    static final Path SAMPLE = Path.of("sample");
    static final Path CHECK_NO_SAMPLE = Path.of("../k8s/scripts/check-no-sample.sh");

    /** The entries outside sample/, by their source under be/niord */
    static final Map<String, Path> SOURCES = new LinkedHashMap<>();

    static {
        SOURCES.put("README.md", Path.of("../scripts/local-run-README.md"));
        SOURCES.put("docker-compose.yml", Path.of("../docker-compose.yml"));
        SOURCES.put("keycloak/niord-realm-local.json", Path.of("../keycloak/niord-realm-local.json"));
        SOURCES.put("scripts/bootstrap.sh", Path.of("../scripts/bootstrap.sh"));
        SOURCES.put("scripts/load-sample.sh", Path.of("scripts/load-sample.sh"));
        SOURCES.put("scripts/local-run-lib.sh", Path.of("../scripts/local-run-lib.sh"));
    }

    /** The date of every entry, the earliest a zip's DOS time can hold, on the 2-second grid */
    static final LocalDateTime FIXED = LocalDateTime.of(1980, 1, 1, 0, 0, 2);

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    @TempDir
    static Path shared;

    /** The zip, built once for the class */
    static Path zip;

    @TempDir
    Path dir;

    /** The outcome of a run */
    record Result(int status, String output) {
        @Override
        public String toString() {
            return "exit " + status + "\n" + output;
        }
    }

    @BeforeAll
    static void build() throws Exception {
        assumeTrue(LoadSampleScriptTest.bash() != null, "no bash on this machine (on Windows, Git Bash)");
        assertTrue(Files.isRegularFile(SCRIPT), SCRIPT + " is missing");
        zip = shared.resolve("niord-local-run.zip");
        Result result = buildInto(zip);
        assertEquals(0, result.status(), result.toString());
    }

    @Test
    void withoutAnOutputItPrintsTheUsage() throws Exception {
        Result result = run(List.of(slashes(SCRIPT)), Map.of(), null);
        assertEquals(2, result.status(), result.toString());
        assertTrue(result.output().contains("local-run-zip.sh <out.zip>"), result.toString());
    }

    /** (1) The entries, exactly, in this order: files only, each stored and dated FIXED */
    @Test
    void theEntriesAreExactlyTheLocalRunFilesSortedStoredAndDated() throws IOException {
        // The sample is its SQL alone: the attachment files are rows of it
        List<String> expected = new ArrayList<>(SOURCES.keySet());
        expected.add("sample/niord-sample.sql");
        Collections.sort(expected);
        assertEquals(List.of("README.md", "docker-compose.yml", "keycloak/niord-realm-local.json",
                "sample/niord-sample.sql"), expected.subList(0, 4));
        assertEquals(List.of("scripts/bootstrap.sh", "scripts/load-sample.sh", "scripts/local-run-lib.sh"),
                expected.subList(expected.size() - 3, expected.size()));

        List<String> names = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            for (ZipEntry entry : Collections.list(file.entries())) {
                names.add(entry.getName());
                assertFalse(entry.isDirectory(), entry.getName());
                assertEquals(ZipEntry.STORED, entry.getMethod(), entry.getName() + " is stored, not deflated");
                assertEquals(FIXED, entry.getTimeLocal(), entry.getName() + "'s date");
            }
        }
        assertFalse(names.contains("docs/country-api.md"),
                "the backend contract is published in org.niord:niord-country-kit");
        assertEquals(expected, names);
        assertFalse(names.stream().anyMatch(name -> name.startsWith("META-INF/") || name.startsWith("sample/archive/")
                || name.startsWith("sample/messages/") || name.equals("sample/README.md")), names.toString());
    }

    /** (2) Deterministic: a second build gives the same bytes */
    @Test
    void twoBuildsGiveTheSameBytes() throws Exception {
        Path second = dir.resolve("again/niord-local-run.zip");
        Files.createDirectories(second.getParent());
        Result result = buildInto(second);
        assertEquals(0, result.status(), result.toString());
        assertArrayEquals(Files.readAllBytes(zip), Files.readAllBytes(second), "two builds differ");
    }

    /** (3) The sample byte for byte; every other entry its source with LF, whatever the checkout's line ends */
    @Test
    void eachEntryIsItsSource() throws IOException {
        Map<String, byte[]> entries = entries();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            byte[] bytes = entry.getValue();
            if (name.startsWith("sample/")) {
                assertArrayEquals(Files.readAllBytes(SAMPLE.resolve(name.substring("sample/".length()))), bytes, name);
            } else {
                Path source = SOURCES.get(name);
                assertTrue(source != null, "no source for " + name);
                String expected = Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");
                assertEquals(expected, new String(bytes, StandardCharsets.UTF_8), name);
                for (byte b : bytes) {
                    assertTrue(b != '\r', name + " holds a CR");
                }
            }
        }
        String readme = new String(entries.get("README.md"), StandardCharsets.UTF_8);
        for (String words : List.of("for a local run only", "known passwords", "never an image input")) {
            assertTrue(readme.contains(words), "README.md does not say '" + words + "'");
        }
    }

    /** (4) Unpacked, every script parses */
    @Test
    void theUnpackedScriptsParse() throws Exception {
        Path unpacked = unpack();
        List<Path> scripts;
        try (Stream<Path> files = Files.list(unpacked.resolve("scripts"))) {
            scripts = files.filter(file -> file.toString().endsWith(".sh")).sorted().toList();
        }
        assertEquals(3, scripts.size(), scripts.toString());
        for (Path script : scripts) {
            Result result = run(List.of("-n", slashes(script)), Map.of(), null);
            assertEquals(0, result.status(), script + ": " + result);
        }
    }

    /** (5) The library, sourced from the unpacked zip, finds bootstrap.sh beside it */
    @Test
    void theUnpackedLibraryFindsBootstrapBesideIt() throws Exception {
        Path unpacked = unpack();
        Path driver = dir.resolve("library-dir.sh");
        Files.writeString(driver, "set -euo pipefail\nsource \"$1\"\nprintf '%s\\n' \"$(library_dir)/bootstrap.sh\"\n",
                StandardCharsets.UTF_8);
        Result result = run(List.of(slashes(driver), slashes(unpacked.resolve("scripts/local-run-lib.sh"))), Map.of(),
                null);
        assertEquals(0, result.status(), result.toString());
        Path found = Path.of(result.output().strip()).toAbsolutePath().normalize();
        assertEquals(unpacked.resolve("scripts/bootstrap.sh").toAbsolutePath().normalize(), found, result.toString());
        assertTrue(Files.isRegularFile(found), result.toString());
    }

    /**
     * (5) and it runs that bootstrap.sh with bash, so the zip's missing exec bits do not matter: bootstrap_database
     * against a curl stand-in that gives a token, reads pending, accepts the post and then reads applied.
     */
    @Test
    void theUnpackedLibraryRunsBootstrapWithoutAnExecBit() throws Exception {
        Path unpacked = unpack();
        if (!WINDOWS) {
            assertFalse(Files.isExecutable(unpacked.resolve("scripts/bootstrap.sh")), "unpacked without exec bits");
        }
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path calls = dir.resolve("curl-calls");
        Files.writeString(bin.resolve("curl"), "#!/bin/sh\n"
                + "echo \"$*\" >> '" + slashes(calls) + "'\n"
                + "case \"$*\" in *grant_type=password*) printf '{\"access_token\":\"t\"}'; exit 0 ;; esac\n"
                + "out=''; form=false\n"
                + "while [ $# -gt 0 ]; do\n"
                + "    case \"$1\" in -o) out=\"$2\"; shift ;; -F) form=true; shift ;; esac\n"
                + "    shift\n"
                + "done\n"
                + "if [ -z \"$out\" ]; then printf '{\"state\":\"pending\"}\\n200'; exit 0; fi\n"
                + "if $form; then printf '{}' > \"$out\"; printf 202; else printf '{\"state\":\"applied\"}' > \"$out\";"
                + " printf 200; fi\n", StandardCharsets.UTF_8);
        bin.resolve("curl").toFile().setExecutable(true);
        Path archive = Files.writeString(dir.resolve("base.zip"), "an archive", StandardCharsets.UTF_8);
        // The stand-in goes first on the PATH inside bash, since Git's bin/bash.exe puts its own curl before the PATH
        // it is given, and the run stops unless curl is the stand-in, so no real Keycloak is ever asked. json_field
        // without node, which only the state and the token reach here.
        Path driver = dir.resolve("bootstrap.sh");
        Files.writeString(driver, "set -euo pipefail\n"
                + "if command -v cygpath > /dev/null 2>&1; then stubs=\"$(cygpath -u \"$4\")\"; else stubs=\"$4\"; fi\n"
                + "PATH=\"$stubs:$PATH\"\n"
                + "[ \"$(command -v curl)\" = \"$stubs/curl\" ] \\\n"
                + "    || { echo \"curl is $(command -v curl), not the stand-in\"; exit 99; }\n"
                + "source \"$1\"\n"
                + "json_field() { sed -nE 's/.*\"'\"$1\"'\" *: *\"([^\"]*)\".*/\\1/p' | grep .; }\n"
                + "bootstrap_database http://127.0.0.1:9 niord_sample_dk 'recreate it' \"$2/backend.log\" \"$2\" \\\n"
                + "    \"$3\"\n"
                + "echo \"BOOTSTRAP_STATE=$BOOTSTRAP_STATE\"\n", StandardCharsets.UTF_8);
        Result result = run(List.of(slashes(driver), slashes(unpacked.resolve("scripts/local-run-lib.sh")),
                slashes(dir), slashes(archive), slashes(bin)), Map.of(), null);
        assertEquals(0, result.status(), result.toString());
        assertTrue(result.output().contains(">> Bootstrap applied"), "bootstrap.sh ran: " + result);
        assertTrue(result.output().contains("BOOTSTRAP_STATE=applied"), result.toString());
        assertTrue(Files.readString(calls, StandardCharsets.UTF_8).contains("archive=@"), "the archive was posted");
    }

    /**
     * (6) load-sample.sh finds ../sample from the unpacked zip: the attachments copied, then exit 3, loaded. The
     * stand-in writes down each call, and the run must have asked it both of the loader's questions, so a real docker
     * ahead on the PATH (Git's bin/bash.exe reorders it) fails the test rather than reaching niord-db.
     */
    @Test
    void theUnpackedLoaderFindsTheSample() throws Exception {
        Path unpacked = unpack();
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path calls = dir.resolve("docker-calls");
        Files.writeString(bin.resolve("docker"), "#!/bin/sh\n"
                + "echo \"$*\" >> '" + slashes(calls) + "'\n"
                + "case \"$1\" in\n"
                + "    port) echo 127.0.0.1:5432; exit 0 ;;\n"
                + "    exec) case \"$*\" in *-tAc*) echo t; exit 0 ;; esac ;;\n"
                + "esac\n"
                + "exit 1\n", StandardCharsets.UTF_8);
        bin.resolve("docker").toFile().setExecutable(true);
        Path home = dir.resolve("home");
        Result result = run(List.of(slashes(unpacked.resolve("scripts/load-sample.sh")), "--host", "localhost",
                "--db", "niord_sample_dk", "--home", slashes(home)), Map.of(), bin);
        assertEquals(3, result.status(), result.toString());
        assertTrue(Files.isRegularFile(calls), "the docker stand-in was never asked: " + result);
        // The loader's query spans lines, so the log is read whole: the port, then the query, and no load
        String asked = Files.readString(calls, StandardCharsets.UTF_8);
        assertTrue(asked.startsWith("port niord-db 5432/tcp\nexec niord-db psql -v ON_ERROR_STOP=1 -U niord"
                + " -d niord_sample_dk -tAc select exists "), "the docker calls: " + asked);
        assertFalse(asked.contains("exec -i"), "nothing was loaded: " + asked);
        // The attachments are rows of the SQL: the --home an older launcher passes is accepted, and nothing is copied
        assertFalse(Files.exists(home), "no file is copied into the home: " + result);
    }

    /** (7) The no-sample check, which guards the deployables and the image's inputs, fails on this zip */
    @Test
    void theNoSampleCheckFailsOnTheZip() throws Exception {
        Result result = run(List.of(slashes(CHECK_NO_SAMPLE), "sample", slashes(zip)),
                Map.of("JAVA_HOME", System.getProperty("java.home").replace('\\', '/')), null);
        assertEquals(1, result.status(), result.toString());
        assertTrue(result.output().contains("niord-local-run.zip"), "the finding names the zip: " + result);
    }

    /** (8) No image input reads it: the Dockerfile, its .dockerignore and the image scripts never name it */
    @Test
    void noImageInputNamesIt() throws IOException {
        for (String file : List.of("../k8s/docker/Dockerfile", "../k8s/docker/.dockerignore",
                "../k8s/scripts/split-jar.sh", "../k8s/scripts/publish-images.sh", "../k8s/scripts/build-image.sh")) {
            assertFalse(Files.readString(Path.of(file), StandardCharsets.UTF_8).contains("local-run"), file);
        }
    }

    /** The zip's entries and their bytes, in the zip's order */
    static Map<String, byte[]> entries() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    /** Unpacks the zip into dir/unpacked as any unzip does here, files with no exec bit */
    Path unpack() throws IOException {
        Path unpacked = dir.resolve("unpacked");
        try (InputStream file = Files.newInputStream(zip); ZipInputStream in = new ZipInputStream(file)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                Path target = unpacked.resolve(entry.getName()).normalize();
                assertTrue(target.startsWith(unpacked), entry.getName());
                Files.createDirectories(target.getParent());
                Files.copy(in, target);
            }
        }
        return unpacked;
    }

    static Result buildInto(Path out) throws Exception {
        return run(List.of(slashes(SCRIPT), slashes(out)), Map.of(), null);
    }

    /**
     * bash with the arguments in the module's folder, the JDK of this test as JAVA_HOME (its jar zips), the extra
     * variables set and the stand-ins of {@code bin} (none when null) first on the PATH
     */
    static Result run(List<String> args, Map<String, String> extra, Path bin) throws Exception {
        String bash = LoadSampleScriptTest.bash();
        assumeTrue(bash != null, "no bash on this machine (on Windows, Git Bash)");
        List<String> command = new ArrayList<>(List.of(bash));
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        if (bin != null) {
            env.put("PATH", bin.toAbsolutePath() + File.pathSeparator + env.getOrDefault("PATH", ""));
        }
        env.put("JAVA_HOME", System.getProperty("java.home").replace('\\', '/'));
        env.remove("WINDOWS");
        env.putAll(extra);
        Process process = pb.start();
        process.getOutputStream().close();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the run did not end: " + Arrays.toString(args.toArray()));
        return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    static String slashes(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
