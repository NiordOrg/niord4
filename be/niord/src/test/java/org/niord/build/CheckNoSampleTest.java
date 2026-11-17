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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.test.sample.SampleSafetyCheck;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The no-sample check, {@code be/k8s/scripts/CheckNoSample.java} (docs/fe-country-split-plan.md P9): compiled from
 * its source into a temporary folder and called through its {@code run(String[], PrintStream, PrintStream)}, as
 * {@link CheckOverridesTest} calls the override check.
 *
 * <p>The sentinel line and the message uid prefix are read here from the sample folder at run time and are written
 * nowhere in this class: niord's test-jar is itself checked by Backend CI's no-sample step.
 */
class CheckNoSampleTest {

    static final Path SOURCE = Path.of("../k8s/scripts/CheckNoSample.java");

    /** The sample; the tests run in the module's folder */
    static final Path SAMPLE = Path.of("sample");

    static final long LIMIT = 512L * 1024 * 1024;

    @TempDir
    static Path compiled;

    static Method run;

    static Field limit;

    static byte[] sql;

    static String uid;

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
        assertTrue(Files.isRegularFile(SOURCE), SOURCE.toAbsolutePath() + " is the no-sample check");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int status = javac.run(null, diagnostics, diagnostics, "-d", compiled.toString(), SOURCE.toString());
        assertEquals(0, status, diagnostics.toString(StandardCharsets.UTF_8));
        URLClassLoader loader = new URLClassLoader(new URL[]{compiled.toUri().toURL()},
                CheckNoSampleTest.class.getClassLoader());
        Class<?> check = loader.loadClass("CheckNoSample");
        run = check.getMethod("run", String[].class, PrintStream.class, PrintStream.class);
        limit = check.getDeclaredField("inMemoryLimit");
        limit.setAccessible(true);

        sql = Files.readAllBytes(SAMPLE.resolve("niord-sample.sql"));
        Matcher m = Pattern.compile("\"id\": \"([0-9a-f-]{36})\"")
                .matcher(Files.readString(SAMPLE.resolve("messages/messages.json"), StandardCharsets.UTF_8));
        assertTrue(m.find(), "the sample's messages carry uids");
        uid = m.group(1);
    }

    @AfterEach
    void restoreTheLimit() throws Exception {
        limit.setLong(null, LIMIT);
    }

    @Test
    void aCleanJarPasses() throws Exception {
        Path jar = write("clean.jar", zip(entries("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n",
                "org/niord/X.class", "not the sample")));

        Result result = check(jar);

        assertEquals(0, result.status(), result.toString());
        assertTrue(result.out().contains("no sample in 1 artifact(s)"), result.toString());
    }

    @Test
    void aDockerSaveTarIsNotASupportedInput() throws Exception {
        for (String root : List.of("manifest.json", "oci-layout", "index.json")) {
            Path tar = write("image-" + root + ".tar", tar(entries(root, "{}",
                    "blobs/sha256/0123", "a layer")));

            Result result = check(tar);

            assertEquals(2, result.status(), root + ": " + result);
            assertTrue(result.err().contains("docker save output is not a supported input; use docker export"),
                    result.toString());
        }
    }

    @Test
    void aDockerExportTarIsWalked() throws Exception {
        Path tar = write("container.tar", tar(entries("etc/hostname", "niord",
                "app/data/m.json", "{\"id\": \"" + uid + "\"}")));

        Result result = check(tar);

        assertEquals(1, result.status(), result.toString());
        assertTrue(result.out().contains("app/data/m.json: contains the sample's message uid prefix"),
                result.toString());
    }

    @Test
    void aMinifiedCopyOfASampleMessageIsFound() throws Exception {
        Path jar = write("minified.jar", zip(entries("data/m.json", "{\"id\":\"" + uid + "\"}")));

        Result result = check(jar);

        assertEquals(1, result.status(), result.toString());
        assertTrue(result.out().contains("minified.jar!/data/m.json: contains the sample's message uid prefix"),
                result.toString());
    }

    @Test
    void anArchiveOverTheLimitThatCannotBeOpenedFails() throws Exception {
        byte[] nested = zip(entries("data/m.json", "{\"id\":\"" + uid + "\"}", "x/filler.txt", "x".repeat(4096)));
        Path folder = dir.resolve("dist");
        Files.createDirectories(folder.resolve("lib"));
        Files.write(folder.resolve("lib/big.jar"), nested);
        assertTrue(nested.length > 128, "the nested jar is over the lowered limit");
        limit.setLong(null, 128);

        Result result = check(folder);

        assertEquals(2, result.status(), result.toString());
        assertTrue(result.err().contains("lib/big.jar"), result.toString());
        assertTrue(result.err().contains("128 bytes"), result.toString());
    }

    @Test
    void aNestedJarOverTheLimitIsOpenedWhenItIsAlreadyInMemory() throws Exception {
        byte[] nested = zip(entries("data/m.json", "{\"id\":\"" + uid + "\"}", "x/filler.txt", "x".repeat(4096)));
        Map<String, byte[]> outer = new LinkedHashMap<>();
        outer.put("BOOT-INF/lib/inner.jar", nested);
        Path jar = write("outer.jar", zip(outer));
        assertTrue(nested.length > 128, "the nested jar is over the lowered limit");
        limit.setLong(null, 128);

        Result result = check(jar);

        assertEquals(1, result.status(), result.toString());
        assertTrue(result.out().contains("inner.jar!/data/m.json: contains the sample's message uid prefix"),
                result.toString());
    }

    /** A zip may carry two entries of one name, and ZipInputStream yields both: each is checked */
    @Test
    void everyEntryOfADuplicatedNameIsChecked() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("data/a.bin", sql);
        entries.put("data/b.bin", "harmless".getBytes(StandardCharsets.UTF_8));
        byte[] zip = zip(entries);
        // The second entry's name, in its local header and in the central directory, renamed to the first's
        byte[] from = "data/b.bin".getBytes(StandardCharsets.UTF_8);
        byte[] to = "data/a.bin".getBytes(StandardCharsets.UTF_8);
        int renamed = 0;
        for (int i = 0; i <= zip.length - from.length; i++) {
            if (Arrays.equals(zip, i, i + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, zip, i, to.length);
                renamed++;
            }
        }
        assertEquals(2, renamed, "the name is in the local header and the central directory");
        Path jar = write("duplicate.jar", zip);

        Result result = check(jar);

        assertEquals(1, result.status(), result.toString());
        assertTrue(result.out().contains("duplicate.jar!/data/a.bin: the bytes of the sample's niord-sample.sql"),
                result.toString());
        assertTrue(result.out().contains("duplicate.jar!/data/a.bin: contains the sample's sentinel line"),
                result.toString());
    }

    // ------------------------------------------------------------------------------------------- //

    Result check(Path artifact) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = (int) run.invoke(null, new String[]{SAMPLE.toString(), artifact.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    Path write(String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    static Map<String, byte[]> entries(String... nameAndText) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < nameAndText.length; i += 2) {
            entries.put(nameAndText[i], nameAndText[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return entries;
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** An uncompressed ustar tar of the entries, as docker export and docker save write one */
    static byte[] tar(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> dirs = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (name.contains("/")) {
                String parent = name.substring(0, name.lastIndexOf('/') + 1);
                if (!dirs.contains(parent)) {
                    dirs.add(parent);
                    out.writeBytes(tarHeader(parent, 0, '5'));
                }
            }
            out.writeBytes(tarHeader(name, e.getValue().length, '0'));
            out.writeBytes(e.getValue());
            out.writeBytes(new byte[(512 - e.getValue().length % 512) % 512]);
        }
        out.writeBytes(new byte[1024]);
        return out.toByteArray();
    }

    static byte[] tarHeader(String name, long size, char type) {
        byte[] h = new byte[512];
        put(h, 0, name);
        put(h, 100, "0000644");
        put(h, 108, "0000000");
        put(h, 116, "0000000");
        put(h, 124, String.format("%011o", size));
        put(h, 136, "00000000000");
        h[156] = (byte) type;
        put(h, 257, "ustar");
        put(h, 263, "00");
        Arrays.fill(h, 148, 156, (byte) ' ');
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, String.format("%06o", sum));
        h[155] = ' ';
        return h;
    }

    static void put(byte[] h, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, h, offset, bytes.length);
    }

    /** The sentinel is a line of the sample's SQL: never a literal here */
    @Test
    void theSampleCarriesTheMarkersThisTestReads() {
        assertTrue(new String(sql, StandardCharsets.UTF_8).contains(SampleSafetyCheck.SENTINEL_PREFIX));
        assertEquals(36, uid.length());
    }
}
