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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The no-sample check of the base product (docs/fe-country-split-plan.md P9, the safety rules of 2026-10-19): no
 * jar, zip or docker export tar carries the sample ({@code be/niord/sample}). Run by check-no-sample.sh with the
 * JDK's source launcher: JDK built-ins only, Java 17 syntax.
 *
 * <p>It reads the sample folder it is given: every file's SHA-256, the sentinel line of {@code niord-sample.sql},
 * and the message uid prefix of {@code messages/messages.json} (the uids' common start up to their fourth dash),
 * each read there and written nowhere in this check. It then walks each artifact: a directory, a zip, jar or war
 * (and every zip or jar inside it, at any depth), or an uncompressed tar such as {@code docker export} writes (and
 * every zip or jar inside it). An entry is a finding when
 * <ul>
 *   <li>its path lies under a {@code sample/} folder of the sample's layout ({@code sample/README.md},
 *       {@code sample/archive/}, {@code sample/messages/}, {@code sample/niord-sample.sql});</li>
 *   <li>it is named {@code niord-sample.sql}, or {@code messages.json} beside an {@code archive/} folder;</li>
 *   <li>its bytes equal a sample file's (SHA-256), or contain the sentinel line or the message uid prefix (which a
 *       re-exported or minified copy of the messages keeps).</li>
 * </ul>
 * An entry of a directory or a tar larger than 512 MiB is hashed and searched as a stream, but not opened; one that
 * starts like a zip or a tar is unreadable input (exit 2), since what it holds cannot be checked. The entries of a
 * zip are read into memory and opened whatever their size. A {@code docker save} tar (one whose root holds
 * {@code manifest.json}, {@code oci-layout} or {@code index.json}) keeps its layers compressed, which the check
 * cannot read: it is refused (exit 2); check the image with {@code docker export} of a container made from it.
 *
 * <p>Exit status: 0 nothing found, 1 findings (one line each, naming the artifact and the entry), 2 usage or
 * unreadable input.
 *
 * <p>Backend CI's {@code niord/target/*.jar} includes niord-base's test-jar, whose IT fixture
 * ({@code db/niord-base-data.sql}) is a dump of a database the sample was loaded
 * into. It is not a finding: the safety rules name deployables and images, the test-jar is neither, and the dump
 * carries no sample file's bytes, name, layout or sentinel, or message uid prefix (regen-test-seed-postgres.sh
 * rewrites it to the fixture's own).
 */
public class CheckNoSample {

    static final String SENTINEL_PREFIX = "-- niord-sample-sentinel: ";

    /**
     * Entries of a directory or a tar larger than this (512 MiB) are hashed and searched as a stream, but not opened;
     * a test lowers it
     */
    static long inMemoryLimit = 512L * 1024 * 1024;

    /** The root entries by which a docker save tar (Docker's or OCI's image layout) is known */
    static final List<String> DOCKER_SAVE_ROOT = List.of("manifest.json", "oci-layout", "index.json");

    static final List<String> LAYOUT = List.of(
            "sample/README.md", "sample/archive/", "sample/messages/", "sample/niord-sample.sql");

    /** A row of the sample's repository store in its SQL: its path, then its content as a bytea literal */
    static final Pattern REPO_FILE_ROW = Pattern.compile(
            "^INSERT INTO public\\.\"RepoFile\" \\(path, .*?\\) VALUES \\('([^']+)', .*'\\\\x([0-9a-fA-F]*)'\\);$");

    final Map<String, String> sampleHashes = new HashMap<>();
    byte[] sentinel;
    byte[] uidPrefix;
    final List<String> findings = new ArrayList<>();
    int entries;

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the check
     *
     * @param args the sample folder, then the artifacts
     * @param out  where the findings and the summary go
     * @param err  where usage and input errors go
     * @return 0 when no artifact carries the sample, 1 on findings, 2 on wrong usage or unreadable input
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            err.println("Usage: check-no-sample.sh <sample-dir> <artifact>...");
            return 2;
        }
        CheckNoSample check = new CheckNoSample();
        try {
            check.readSample(Path.of(args[0]));
            for (int i = 1; i < args.length; i++) {
                check.checkArtifact(Path.of(args[i]));
            }
        } catch (IOException | IllegalArgumentException e) {
            err.println("check-no-sample: " + e.getMessage());
            return 2;
        }
        check.findings.forEach(out::println);
        if (!check.findings.isEmpty()) {
            out.println("check-no-sample: " + check.findings.size()
                    + " finding(s): an artifact carries the sample (be/niord/sample), which is loaded only into "
                    + "the loopback niord_sample");
            return 1;
        }
        out.println("check-no-sample: no sample in " + (args.length - 1) + " artifact(s), "
                + check.entries + " entries read");
        return 0;
    }

    void readSample(Path sample) throws IOException {
        Path sql = sample.resolve("niord-sample.sql");
        if (!Files.isRegularFile(sql)) {
            throw new IllegalArgumentException(sample + " is not the sample folder: it has no niord-sample.sql");
        }
        for (String line : Files.readAllLines(sql, StandardCharsets.UTF_8)) {
            if (line.startsWith(SENTINEL_PREFIX) && line.length() > SENTINEL_PREFIX.length() + 16) {
                sentinel = line.strip().getBytes(StandardCharsets.UTF_8);
                break;
            }
        }
        if (sentinel == null) {
            throw new IllegalArgumentException(sql + " has no sentinel line");
        }
        uidPrefix = uidPrefix(sample.resolve("messages/messages.json")).getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.walk(sample)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                sampleHashes.put(sha256(Files.readAllBytes(file)),
                        sample.relativize(file).toString().replace('\\', '/'));
            }
        }
        // The sample's attachment files are rows of its SQL (the repository store): their bytes are the sample's too
        for (String line : Files.readAllLines(sql, StandardCharsets.UTF_8)) {
            Matcher row = REPO_FILE_ROW.matcher(line);
            if (row.matches()) {
                sampleHashes.put(sha256(HexFormat.of().parseHex(row.group(2))), "niord-sample.sql: " + row.group(1));
            }
        }
        // A sample without rows is SampleFolderTest's concern, not this check's
    }

    void checkArtifact(Path artifact) throws IOException {
        if (Files.isDirectory(artifact)) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(artifact)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            List<String> names = new ArrayList<>();
            for (Path file : files) {
                names.add(artifact.relativize(file).toString().replace('\\', '/'));
            }
            for (int i = 0; i < files.size(); i++) {
                Path file = files.get(i);
                try (InputStream in = Files.newInputStream(file)) {
                    checkEntry(artifact.toString(), names.get(i), names, Files.size(file), false, in);
                }
            }
        } else if (Files.isRegularFile(artifact)) {
            byte[] head;
            try (InputStream in = Files.newInputStream(artifact)) {
                head = in.readNBytes(512);
            }
            if (isZip(head)) {
                checkZip(artifact.toString(), Files.newInputStream(artifact));
            } else if (isTar(head)) {
                checkTar(artifact.toString(), Files.newInputStream(artifact));
            } else {
                throw new IllegalArgumentException(artifact + " is neither a directory, a zip nor a tar");
            }
        } else {
            throw new IllegalArgumentException("No such artifact: " + artifact);
        }
    }

    /**
     * The entries of a zip, jar or war, each checked and opened when it is an archive itself. Every entry is kept,
     * also two of one name, which a zip may carry and ZipInputStream yields both of.
     */
    void checkZip(String where, InputStream stream) throws IOException {
        List<Map.Entry<String, byte[]>> files = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                if (!entry.isDirectory()) {
                    files.add(Map.entry(entry.getName(), zip.readAllBytes()));
                }
            }
        }
        for (Map.Entry<String, byte[]> e : files) {
            checkEntry(where, e.getKey(), names, e.getValue().length, true, new ByteArrayInputStream(e.getValue()));
        }
    }

    /** The entries of an uncompressed tar (ustar, with pax or GNU long names), each checked and opened */
    void checkTar(String where, InputStream stream) throws IOException {
        List<String> names = new ArrayList<>();
        try (InputStream in = stream) {
            String longName = null;
            while (true) {
                byte[] header = in.readNBytes(512);
                if (header.length < 512 || isZeroBlock(header)) {
                    break;
                }
                String name = cString(header, 0, 100);
                String prefix = cString(header, 345, 155);
                if (!prefix.isEmpty() && "ustar".equals(cString(header, 257, 5))) {
                    name = prefix + "/" + name;
                }
                long size = parseOctal(header, 124, 12);
                char type = (char) header[156];
                long padded = (size + 511) / 512 * 512;
                if (type == 'x' || type == 'L') {
                    byte[] data = in.readNBytes((int) size);
                    in.skipNBytes(padded - size);
                    longName = type == 'L' ? cString(data, 0, data.length) : paxPath(data, longName);
                    continue;
                }
                if (type == 'g') {
                    in.skipNBytes(padded);
                    continue;
                }
                if (longName != null) {
                    name = longName;
                    longName = null;
                }
                if (name.startsWith("./")) {
                    name = name.substring(2);
                }
                if (DOCKER_SAVE_ROOT.contains(name)) {
                    throw new IllegalArgumentException(where + " holds " + name + " at its root: docker save output "
                            + "is not a supported input; use docker export");
                }
                names.add(name);
                if (type == '0' || type == '\0' || type == '7') {
                    InputStream body = new BoundedInputStream(in, size);
                    checkEntry(where, name, names, size, false, body);
                    body.skipNBytes(((BoundedInputStream) body).remaining);
                    in.skipNBytes(padded - size);
                } else {
                    in.skipNBytes(padded);
                }
            }
        }
    }

    /**
     * Checks one entry
     *
     * @param inMemory whether its bytes are in memory already, the entries of a zip: then it is opened whatever its
     *                 size
     */
    void checkEntry(String where, String name, List<String> siblings, long size, boolean inMemory, InputStream in)
            throws IOException {
        entries++;
        String location = where + "!/" + name;
        String path = "/" + name;
        for (String layout : LAYOUT) {
            if (path.contains("/" + layout)) {
                findings.add(location + ": a path of the sample's layout (" + layout + ")");
                break;
            }
        }
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        if ("niord-sample.sql".equals(fileName)) {
            findings.add(location + ": the sample's SQL by name");
        } else if ("messages.json".equals(fileName) && besideArchive(name, siblings)) {
            findings.add(location + ": the sample's messages by name, beside an archive/ folder");
        }

        MessageDigest digest = newDigest();
        if (inMemory || size <= inMemoryLimit) {
            byte[] bytes = in.readAllBytes();
            String hash = HexFormat.of().formatHex(digest.digest(bytes));
            if (sampleHashes.containsKey(hash)) {
                findings.add(location + ": the bytes of the sample's " + sampleHashes.get(hash));
            }
            if (indexOf(bytes, sentinel, 0) >= 0) {
                findings.add(location + ": contains the sample's sentinel line");
            }
            if (indexOf(bytes, uidPrefix, 0) >= 0) {
                findings.add(location + ": contains the sample's message uid prefix");
            }
            if (isZip(bytes)) {
                checkZip(location, new ByteArrayInputStream(bytes));
            } else if (isTar(bytes)) {
                checkTar(location, new ByteArrayInputStream(bytes));
            }
        } else {
            // Too large to open: an archive cannot be checked, anything else is hashed and searched as a stream
            byte[] head = in.readNBytes(512);
            if (isZip(head) || isTar(head)) {
                throw new IllegalArgumentException(location + " is an archive larger than the in-memory limit of "
                        + inMemoryLimit + " bytes, which the check cannot open");
            }
            digest.update(head);
            byte[] buffer = new byte[1 << 20];
            byte[] carry = head;
            int keepMax = Math.max(sentinel.length, uidPrefix.length) - 1;
            boolean sentinelFound = indexOf(head, sentinel, 0) >= 0;
            boolean uidFound = indexOf(head, uidPrefix, 0) >= 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
                byte[] window = new byte[carry.length + n];
                System.arraycopy(carry, 0, window, 0, carry.length);
                System.arraycopy(buffer, 0, window, carry.length, n);
                sentinelFound |= indexOf(window, sentinel, 0) >= 0;
                uidFound |= indexOf(window, uidPrefix, 0) >= 0;
                int keep = Math.min(window.length, keepMax);
                carry = new byte[keep];
                System.arraycopy(window, window.length - keep, carry, 0, keep);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            if (sampleHashes.containsKey(hash)) {
                findings.add(location + ": the bytes of the sample's " + sampleHashes.get(hash));
            }
            if (sentinelFound) {
                findings.add(location + ": contains the sample's sentinel line");
            }
            if (uidFound) {
                findings.add(location + ": contains the sample's message uid prefix");
            }
        }
    }

    /**
     * The common start of the sample messages' uids, up to and with their fourth dash: what a copy of the messages
     * keeps however it is re-serialised
     */
    static String uidPrefix(Path messages) throws IOException {
        if (!Files.isRegularFile(messages)) {
            throw new IllegalArgumentException("The sample folder has no " + messages);
        }
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-)[0-9a-f]{12}\"")
                .matcher(Files.readString(messages, StandardCharsets.UTF_8));
        String prefix = null;
        while (m.find()) {
            if (prefix == null) {
                prefix = m.group(1);
            } else if (!prefix.equals(m.group(1))) {
                throw new IllegalArgumentException(messages + ": the message uids do not share one prefix ("
                        + prefix + ", " + m.group(1) + "), by which the check knows a copy of them");
            }
        }
        if (prefix == null) {
            throw new IllegalArgumentException(messages + " holds no message uid");
        }
        return prefix;
    }

    static boolean besideArchive(String name, List<String> siblings) {
        String dir = name.contains("/") ? name.substring(0, name.lastIndexOf('/') + 1) : "";
        String parent = dir.isEmpty() ? null
                : dir.substring(0, dir.length() - 1).contains("/")
                ? dir.substring(0, dir.substring(0, dir.length() - 1).lastIndexOf('/') + 1) : "";
        for (String sibling : siblings) {
            if (sibling.startsWith(dir + "archive/") || (parent != null && sibling.startsWith(parent + "archive/"))) {
                return true;
            }
        }
        return false;
    }

    static String paxPath(byte[] data, String current) {
        String records = new String(data, StandardCharsets.UTF_8);
        for (String record : records.split("\n")) {
            int space = record.indexOf(' ');
            if (space > 0 && record.startsWith("path=", space + 1)) {
                return record.substring(space + 6);
            }
        }
        return current;
    }

    static boolean isZip(byte[] head) {
        return head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    }

    static boolean isTar(byte[] head) {
        return head.length >= 262 && "ustar".equals(new String(head, 257, 5, StandardCharsets.US_ASCII));
    }

    static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    static String cString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    static long parseOctal(byte[] bytes, int offset, int length) {
        String s = cString(bytes, offset, length).strip();
        return s.isEmpty() ? 0 : Long.parseLong(s, 8);
    }

    static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A view of the next {@code size} bytes of a stream, which the caller skips past afterwards */
    static final class BoundedInputStream extends InputStream {
        final InputStream in;
        long remaining;

        BoundedInputStream(InputStream in, long size) {
            this.in = in;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public void close() {
            // The tar's stream stays open
        }
    }
}
