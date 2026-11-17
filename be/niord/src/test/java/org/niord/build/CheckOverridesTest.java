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

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend override check, {@code be/k8s/scripts/CheckOverrides.java}, which the niord-image-tools zip carries
 * and a country's build runs over its jar (be/docs/country-api.md, Overrides). One negative test per rule, the cases
 * it accepts, and one run in the JDK's source-launch mode, as {@code check-overrides.sh} runs it.
 *
 * <p>The program is compiled once from its source into a temporary folder and called through its
 * {@code run(String[], PrintStream, PrintStream)}. Each test builds a niord-base jar and a country's executable jar
 * in a temporary folder, laid out as Spring Boot repackages one: the country's {@code META-INF/} at the root, its
 * other files under {@code BOOT-INF/classes/}, the niord-base jar as {@code BOOT-INF/lib/niord-base-4.0.0.jar}. Not in the country build group: it reads the script from this repository's tree.
 */
class CheckOverridesTest {

    static final Path SOURCE = Path.of("../k8s/scripts/CheckOverrides.java");

    static final String LOGO = "META-INF/resources/img/company-logo.png";
    static final byte[] BASE_LOGO = "niord-base's logo".getBytes(StandardCharsets.UTF_8);
    static final byte[] COUNTRY_LOGO = "the country's logo".getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path compiled;

    static Method run;

    @TempDir
    Path dir;

    /** The result of a run */
    record Result(int status, String out, String err) {
        @Override
        public String toString() {
            return "exit " + status + "\n" + out + err;
        }
    }

    @BeforeAll
    static void compile() throws Exception {
        assertTrue(Files.isRegularFile(SOURCE), SOURCE.toAbsolutePath() + " is the override check");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int status = javac.run(null, diagnostics, diagnostics, "-d", compiled.toString(), SOURCE.toString());
        assertEquals(0, status, diagnostics.toString(StandardCharsets.UTF_8));
        URLClassLoader loader = new URLClassLoader(new URL[]{compiled.toUri().toURL()},
                CheckOverridesTest.class.getClassLoader());
        run = loader.loadClass("CheckOverrides")
                .getMethod("run", String[].class, PrintStream.class, PrintStream.class);
    }

    // ------------------------------------------------------------------------------------------- //
    // The rules
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anUnlistedShadowFailsAndPrintsTheFingerprintToRecord() throws Exception {
        Result result = check(Map.of(LOGO, BASE_LOGO), Map.of(LOGO, COUNTRY_LOGO), null);

        assertEquals(1, result.status(), result.toString());
        assertTrue(result.out().contains("FAIL unlisted-shadow " + LOGO + ": shadows niord-base's file; list it in "
                + "overrides.json with basedOn \"" + sha256(BASE_LOGO) + "\""), result.toString());
    }

    @Test
    void aForbiddenShadowFailsEvenWhenListed() throws Exception {
        for (String path : List.of("config/niord-core-defaults.yml", "db/changelog/db.changelog-master.yaml",
                "org/niord/core/conf/SecurityConfig.class", "keycloak-client-template.json",
                "META-INF/resources/css/templates/common.css", "META-INF/batch-jobs/area-import.xml")) {
            byte[] content = path.getBytes(StandardCharsets.UTF_8);
            Result result = check(Map.of(path, content), Map.of(path, content), overrides(path, sha256(content)));

            assertEquals(1, result.status(), path + ": " + result);
            assertTrue(result.out().contains("FAIL forbidden-shadow " + path + ": "), result.toString());
            assertFalse(result.out().contains("unlisted-shadow"), result.toString());
        }
    }

    /**
     * A review reproduced this one: a country's jar replacing an import job's definition, listed
     * with niord-base's correct fingerprint, passed the check. Only a static image may be overridden.
     */
    @Test
    void aListedBatchJobDefinitionWithTheRightFingerprintFails() throws Exception {
        String path = "META-INF/batch-jobs/settings-import.xml";
        byte[] base = bytes("<job id=\"settings-import\"><!-- niord-base's --></job>");
        Map<String, byte[]> country = Map.of(path, bytes("<job id=\"settings-import\"><!-- the country's --></job>"));

        Result result = check(Map.of(path, base), country, overrides(path, sha256(base)));

        assertEquals(1, result.status(), result.toString());
        assertEquals(1, result.out().split("FAIL forbidden-shadow " + path + ": ", -1).length - 1, result.toString());
        assertTrue(result.out().contains("only a static image under META-INF/resources/img/ (png, jpg, jpeg, gif, "
                + "webp, ico) may be overridden; this file is code, configuration or data: ask for an extension "
                + "point"), result.toString());
        assertFalse(result.out().contains("The override check passes."), result.toString());
    }

    @Test
    void aListedShadowThatIsNotAPermittedImageFails() throws Exception {
        for (String path : List.of("aton/aton-osm-defaults.xslt", "META-INF/resources/img/x.svg", "blank_256.png",
                "META-INF/resources/img/flags/README.txt")) {
            byte[] base = bytes("niord-base's " + path);
            Result result = check(Map.of(path, base), Map.of(path, bytes("the country's " + path)),
                    overrides(path, sha256(base)));

            assertEquals(1, result.status(), path + ": " + result);
            assertTrue(result.out().contains("FAIL forbidden-shadow " + path + ": "), result.toString());
            assertFalse(result.out().contains("The override check passes."), result.toString());
        }
    }

    @Test
    void aListedImageOfEveryPermittedExtensionPasses() throws Exception {
        for (String path : List.of("META-INF/resources/img/a.jpg", "META-INF/resources/img/b.JPEG",
                "META-INF/resources/img/flags/dk.gif", "META-INF/resources/img/c.webp",
                "META-INF/resources/img/favicon.ico")) {
            byte[] base = bytes("niord-base's " + path);
            Result result = check(Map.of(path, base), Map.of(path, bytes("the country's " + path)),
                    overrides(path, sha256(base)));

            assertEquals(0, result.status(), path + ": " + result);
        }
    }

    @Test
    void aTemplateInTheJarFails() throws Exception {
        Result result = check(Map.of(), Map.of("templates/tmpl/dgps.ftl", bytes("<#-- a template -->")), null);

        assertEquals(1, result.status(), result.toString());
        assertTrue(result.out().contains("FAIL template-in-jar templates/tmpl/dgps.ftl: "), result.toString());
    }

    @Test
    void aFileUnderDefaultsBaseFails() throws Exception {
        Result result = check(Map.of(), Map.of("defaults/base/settings.json", bytes("{}")), null);

        assertEquals(1, result.status(), result.toString());
        assertTrue(result.out().contains("FAIL defaults-base defaults/base/settings.json: "), result.toString());
    }

    @Test
    void committedPacksFail() throws Exception {
        Path pack = dir.resolve("be/src/main/resources/defaults/packs/da/web.properties");
        Files.createDirectories(pack.getParent());
        Files.writeString(pack, "lang.da=Dansk\n");

        Result result = check(Map.of(), Map.of(), null);

        assertEquals(1, result.status(), result.toString());
        assertTrue(result.out().contains("FAIL committed-packs src/main/resources/defaults/packs/da/web.properties: "),
                result.toString());
    }

    @Test
    void aStaleFingerprintFails() throws Exception {
        String recorded = sha256(bytes("niord-base's logo before a change"));
        Result result = check(Map.of(LOGO, BASE_LOGO), Map.of(LOGO, COUNTRY_LOGO), overrides(LOGO, recorded));

        assertEquals(1, result.status(), result.toString());
        assertTrue(result.out().contains("FAIL stale-basedOn " + LOGO + ": niord-base's file has changed since "
                + recorded + ": compare the country's copy with niord-base's, then record basedOn \""
                + sha256(BASE_LOGO) + "\""), result.toString());
    }

    @Test
    void anOverrideOfAFileNiordBaseNoLongerHasFails() throws Exception {
        Result result = check(Map.of(), Map.of(LOGO, COUNTRY_LOGO), overrides(LOGO, sha256(BASE_LOGO)));

        assertEquals(1, result.status(), result.toString());
        assertTrue(result.out().contains("FAIL stale-basedOn " + LOGO + ": niord-base no longer has this file"),
                result.toString());
    }

    @Test
    void everyBreachOfTheFormatFails() throws Exception {
        String ok = sha256(BASE_LOGO);
        Map<String, String> broken = new LinkedHashMap<>();
        broken.put("not JSON", "{\"overrides\": [");
        broken.put("a member twice", "{\"overrides\": [], \"overrides\": []}");
        broken.put("no overrides array", "{\"override\": []}");
        broken.put("an unknown top-level member", "{\"overrides\": [], \"version\": 1}");
        broken.put("an unknown entry member", entry(LOGO, "{\"" + LOGO + "\": \"" + ok + "\"}", "logo", "2026-10-22",
                ", \"with\": \"x\""));
        broken.put("no file", "{\"overrides\": [{\"basedOn\": {}, \"reason\": \"logo\", \"added\": \"2026-10-22\"}]}");
        broken.put("a file outside the jar's tree", entry("../" + LOGO, "{}", "logo", "2026-10-22", ""));
        broken.put("basedOn naming another file", entry(LOGO, "{\"img/other.png\": \"" + ok + "\"}", "logo",
                "2026-10-22", ""));
        broken.put("basedOn naming a second file", entry(LOGO, "{\"" + LOGO + "\": \"" + ok
                + "\", \"img/other.png\": \"" + ok + "\"}", "logo", "2026-10-22", ""));
        broken.put("a fingerprint that is not sha256", entry(LOGO, "{\"" + LOGO + "\": \"md5:abc\"}", "logo",
                "2026-10-22", ""));
        broken.put("a blank reason", entry(LOGO, "{\"" + LOGO + "\": \"" + ok + "\"}", " ", "2026-10-22", ""));
        broken.put("a date that is not one", entry(LOGO, "{\"" + LOGO + "\": \"" + ok + "\"}", "logo", "2026-13-01",
                ""));
        String one = "{\"file\": \"" + LOGO + "\", \"basedOn\": {\"" + LOGO + "\": \"" + ok
                + "\"}, \"reason\": \"logo\", \"added\": \"2026-10-22\"}";
        broken.put("a file listed twice", "{\"overrides\": [" + one + ", " + one + "]}");

        for (Map.Entry<String, String> json : broken.entrySet()) {
            Result result = check(Map.of(LOGO, BASE_LOGO), Map.of(LOGO, COUNTRY_LOGO), json.getValue());

            assertEquals(1, result.status(), json.getKey() + ": " + result);
            assertTrue(result.out().contains("FAIL format overrides.json"), json.getKey() + ": " + result);
        }
    }

    @Test
    void aListedFileTheCountrysJarDoesNotCarryFails() throws Exception {
        Result result = check(Map.of(LOGO, BASE_LOGO), Map.of("application.yml", bytes("")),
                overrides(LOGO, sha256(BASE_LOGO)));

        assertEquals(1, result.status(), result.toString());
        assertTrue(result.out().contains("FAIL format " + LOGO + ": listed, but the country's jar does not carry it"),
                result.toString());
    }

    @Test
    void wrongUsageAndUnreadableInputExitWithTwo() throws Exception {
        assertEquals(2, check(new String[0]).status());

        Result missing = check(new String[]{dir.resolve("no.jar").toString(), dir.toString()});
        assertEquals(2, missing.status(), missing.toString());
        assertTrue(missing.err().contains("no such jar"), missing.toString());

        Path base = jar(dir.resolve("niord-base.jar"), Map.of("a.txt", bytes("a")));
        Map<String, byte[]> twoBases = new LinkedHashMap<>();
        twoBases.put("BOOT-INF/classes/application.yml", bytes(""));
        twoBases.put("BOOT-INF/lib/niord-base-4.0.0.jar", Files.readAllBytes(base));
        twoBases.put("BOOT-INF/lib/niord-base-4.0.1.jar", Files.readAllBytes(base));
        Path app = jar(dir.resolve("two.jar"), twoBases);
        Result two = check(new String[]{app.toString(), be().toString()});
        assertEquals(2, two.status(), two.toString());
        assertTrue(two.err().contains("2 niord-base jars"), two.toString());

        Map<String, byte[]> plainEntries = new LinkedHashMap<>();
        plainEntries.put("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\n"));
        plainEntries.put("org/example/country/App.class", bytes("a class"));
        Path plain = jar(dir.resolve("plain.jar"), plainEntries);
        Result notBoot = check(new String[]{plain.toString(), be().toString()});
        assertEquals(2, notBoot.status(), notBoot.toString());
        assertTrue(notBoot.err().contains("has no BOOT-INF/classes/: not a Spring Boot executable jar"),
                notBoot.toString());
    }

    // ------------------------------------------------------------------------------------------- //
    // What passes
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aListedImageWithItsFingerprintPassesAndIsPrinted() throws Exception {
        Result result = check(Map.of(LOGO, BASE_LOGO), Map.of(LOGO, COUNTRY_LOGO),
                overrides(LOGO, sha256(BASE_LOGO)));

        assertEquals(0, result.status(), result.toString());
        assertTrue(result.out().contains("Backend overrides (overrides.json): 1 listed; niord-base niord-base-4.0.0.jar"),
                result.toString());
        assertTrue(result.out().contains("  " + LOGO + " (added 2026-10-22): the country's own logo"),
                result.toString());
        assertTrue(result.out().contains("The override check passes."), result.toString());
    }

    @Test
    void anOverridesFileWithAByteOrderMarkIsRead() throws Exception {
        Result result = check(Map.of(LOGO, BASE_LOGO), Map.of(LOGO, COUNTRY_LOGO),
                "\uFEFF" + overrides(LOGO, sha256(BASE_LOGO)));

        assertEquals(0, result.status(), result.toString());
    }

    @Test
    void filesReadFromEveryJarAreNotShadows() throws Exception {
        Map<String, byte[]> both = new LinkedHashMap<>();
        for (String path : List.of("META-INF/spring.factories", "META-INF/MANIFEST.MF",
                "META-INF/maven/org.niord/x/pom.xml", "META-INF/services/x.Y",
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                "META-INF/additional-spring-configuration-metadata.json",
                "META-INF/niord/expected-template-paths.txt")) {
            both.put(path, bytes(path));
        }
        Result result = check(both, both, null);

        assertEquals(0, result.status(), result.toString());
    }

    @Test
    void noOverridesAndNoShadowsPass() throws Exception {
        Result result = check(Map.of(LOGO, BASE_LOGO), Map.of("META-INF/resources/img/company-logo-da.png",
                COUNTRY_LOGO, "application.yml", bytes("spring.application.name: country")), null);

        assertEquals(0, result.status(), result.toString());
        assertTrue(result.out().contains("Backend overrides (overrides.json): 0 listed"), result.toString());
    }

    @Test
    void theSourceLauncherRunsTheCheck() throws Exception {
        Path app = executableJar(Map.of(LOGO, BASE_LOGO), Map.of("application.yml", bytes("")));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), SOURCE.toAbsolutePath().toString(), app.toString(),
                be().toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "the source launcher did not end");

        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("The override check passes."), output);
    }

    // ------------------------------------------------------------------------------------------- //

    /** Runs the check over a country jar of the given files and niord-base files, and the given overrides.json */
    private Result check(Map<String, byte[]> base, Map<String, byte[]> country, String overridesJson)
            throws Exception {
        Path app = executableJar(base, country);
        Path be = be();
        Files.deleteIfExists(be.resolve("overrides.json"));
        if (overridesJson != null) {
            Files.writeString(be.resolve("overrides.json"), overridesJson);
        }
        return check(new String[]{app.toString(), be.toString()});
    }

    private static Result check(String[] args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = (int) run.invoke(null, args, print(out), print(err));
        return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static PrintStream print(OutputStream out) {
        return new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    private Path be() throws IOException {
        return Files.createDirectories(dir.resolve("be"));
    }

    private Path executableJar(Map<String, byte[]> base, Map<String, byte[]> country) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        // As Spring Boot repackages a jar: the application's META-INF/ at the root, everything else in BOOT-INF/classes/,
        // whose directory entry is there even when every file of the country is under META-INF/
        entries.put("BOOT-INF/classes/", new byte[0]);
        country.forEach((path, content) -> entries.put(
                (path.startsWith("META-INF/") ? "" : "BOOT-INF/classes/") + path, content));
        if (country.isEmpty()) {
            entries.put("BOOT-INF/classes/application.yml", bytes(""));
        }
        Map<String, byte[]> baseEntries = new LinkedHashMap<>(base);
        if (baseEntries.isEmpty()) {
            baseEntries.put("org/niord/core/NiordApp.class", bytes("a class"));
        }
        entries.put("BOOT-INF/lib/niord-base-4.0.0.jar",
                Files.readAllBytes(jar(dir.resolve("niord-base-4.0.0.jar"), baseEntries)));
        entries.put("BOOT-INF/lib/spring-core-7.0.0.jar", Files.readAllBytes(jar(dir.resolve("spring-core.jar"),
                Map.of("org/springframework/core/X.class", bytes("x")))));
        return jar(dir.resolve("country.jar"), entries);
    }

    private static Path jar(Path file, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    private static String overrides(String file, String basedOn) {
        return entry(file, "{\"" + file + "\": \"" + basedOn + "\"}", "the country's own logo", "2026-10-22", "");
    }

    private static String entry(String file, String basedOn, String reason, String added, String more) {
        return "{\"overrides\": [{\"file\": \"" + file + "\", \"basedOn\": " + basedOn + ", \"reason\": \"" + reason
                + "\", \"added\": \"" + added + "\"" + more + "}]}";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
