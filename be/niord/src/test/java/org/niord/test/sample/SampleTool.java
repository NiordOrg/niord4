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
package org.niord.test.sample;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Adler32;
import java.util.zip.CRC32;

/**
 * The sample's tool, a main in the test sources that {@code be/niord/scripts/regen-sample.sh} runs; nothing in an
 * application context starts it (docs/fe-country-split-plan.md P9).
 *
 * <ul>
 *   <li>{@code attachments <messages.json> <out-dir>} writes every PNG attachment that the sample's messages name,
 *       at its {@code path} below {@code out-dir}: a picture labelled SAMPLE, drawn here pixel by pixel and written
 *       with stored (uncompressed) deflate blocks and no metadata chunk, so that every run on every platform writes
 *       the same bytes. At most {@link #MAX_ATTACHMENTS}, the n-th labelled "SAMPLE n";</li>
 *   <li>{@code check <sql>} runs {@link SampleSafetyCheck} and exits 1 with one line per finding;</li>
 *   <li>{@code tables} prints the allow-list, the tables the regeneration captures;</li>
 *   <li>{@code counts <messages.json>} prints, as {@code <table> <n>} lines, the rows the message import writes into
 *       the link tables, counted in the messages: the regeneration compares them with what it imported, since the
 *       import drops an area, category or chart it cannot resolve;</li>
 *   <li>{@code references <keys.tsv> <columns.tsv>} reads pg_dump's INSERTs on standard input and writes them to
 *       standard output with every base row a foreign-key column names by id (a value below
 *       {@link SampleReferences#FIRST_ID}) named by its natural key instead ({@link SampleReferences}); every other
 *       byte passes through. The keys file holds a line {@code <table> <id> <shape code> <part>...} per base row, the
 *       columns file {@code <table> <column> <parent table>} per foreign-key column, tab-separated. A value of such
 *       a column that is neither NULL, a sample id nor a base row of the keys file fails, naming table, column and
 *       value;</li>
 *   <li>{@code guard-keys <keys.tsv>} prints the guard's natural-key checks, one per table, ordered by table and
 *       key.</li>
 * </ul>
 */
public final class SampleTool {

    static final int WIDTH = 320;
    static final int HEIGHT = 200;

    /** The most attachments the messages may name: the label "SAMPLE n" fits two digits in the frame */
    static final int MAX_ATTACHMENTS = 99;

    /** The palette: 0 sea, 1 land, 2 frame, 3 grid, 4 label, 5 accent */
    static final int[][] PALETTE = {
            {0xd6, 0xe9, 0xf5}, {0xe8, 0xdf, 0xc4}, {0x1f, 0x3b, 0x57}, {0xb4, 0xcf, 0xe2},
            {0xc0, 0x39, 0x2b}, {0x2c, 0x6e, 0x49}};

    /** A 5x7 pixel font for the label's letters and digits */
    static final Map<Character, String[]> FONT = Map.ofEntries(
            Map.entry('S', new String[] {" ### ", "#   #", "#    ", " ### ", "    #", "#   #", " ### "}),
            Map.entry('A', new String[] {" ### ", "#   #", "#   #", "#####", "#   #", "#   #", "#   #"}),
            Map.entry('M', new String[] {"#   #", "## ##", "# # #", "# # #", "#   #", "#   #", "#   #"}),
            Map.entry('P', new String[] {"#### ", "#   #", "#   #", "#### ", "#    ", "#    ", "#    "}),
            Map.entry('L', new String[] {"#    ", "#    ", "#    ", "#    ", "#    ", "#    ", "#####"}),
            Map.entry('E', new String[] {"#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#####"}),
            Map.entry('0', new String[] {" ### ", "#   #", "#  ##", "# # #", "##  #", "#   #", " ### "}),
            Map.entry('1', new String[] {"  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "}),
            Map.entry('2', new String[] {" ### ", "#   #", "    #", "   # ", "  #  ", " #   ", "#####"}),
            Map.entry('3', new String[] {" ### ", "#   #", "    #", "  ## ", "    #", "#   #", " ### "}),
            Map.entry('4', new String[] {"   # ", "  ## ", " # # ", "#  # ", "#####", "   # ", "   # "}),
            Map.entry('5', new String[] {"#####", "#    ", "#### ", "    #", "    #", "#   #", " ### "}),
            Map.entry('6', new String[] {"  ## ", " #   ", "#    ", "#### ", "#   #", "#   #", " ### "}),
            Map.entry('7', new String[] {"#####", "    #", "   # ", "  #  ", " #   ", " #   ", " #   "}),
            Map.entry('8', new String[] {" ### ", "#   #", "#   #", " ### ", "#   #", "#   #", " ### "}),
            Map.entry('9', new String[] {" ### ", "#   #", "#   #", " ####", "    #", "   # ", " ##  "}),
            Map.entry(' ', new String[] {"     ", "     ", "     ", "     ", "     ", "     ", "     "}));

    private SampleTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "attachments".equals(args[0])) {
            int written = writeAttachments(Path.of(args[1]), Path.of(args[2]));
            System.out.println("SampleTool: " + written + " attachments written below " + args[2]);
        } else if (args.length == 1 && "tables".equals(args[0])) {
            SampleSafetyCheck.ALLOWED_TABLES.stream().sorted().forEach(System.out::println);
        } else if (args.length == 2 && "counts".equals(args[0])) {
            counts(Path.of(args[1])).forEach((table, n) -> System.out.println(table + " " + n));
        } else if (args.length == 3 && "references".equals(args[0])) {
            String sql = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            try {
                String out = references(sql, readKeys(Path.of(args[1])), readColumns(Path.of(args[2])));
                System.out.write(out.getBytes(StandardCharsets.UTF_8));
                System.out.flush();
            } catch (IllegalArgumentException e) {
                System.err.println("SampleTool references: " + e.getMessage());
                System.exit(1);
            }
        } else if (args.length == 2 && "guard-keys".equals(args[0])) {
            System.out.write(guardKeys(readKeys(Path.of(args[1]))).getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        } else if (args.length == 2 && "check".equals(args[0])) {
            List<String> findings = SampleSafetyCheck.check(Path.of(args[1]));
            findings.forEach(System.out::println);
            if (!findings.isEmpty()) {
                System.out.println("SampleTool: " + findings.size() + " finding(s): the sample breaks the safety rules");
                System.exit(1);
            }
            System.out.println("SampleTool: the sample keeps the safety rules");
        } else {
            System.err.println("Usage: SampleTool attachments <messages.json> <out-dir> | check <sql> | tables"
                    + " | counts <messages.json> | references <keys.tsv> <columns.tsv> | guard-keys <keys.tsv>");
            System.exit(2);
        }
    }

    /**
     * Writes the PNG attachments the messages name, the n-th labelled "SAMPLE n", each at its path below the output
     * folder: a path in the repository's layout ({@link SampleSafetyCheck#ATTACHMENT_PATH}) and inside the folder.
     *
     * @return the number of files written
     */
    static int writeAttachments(Path messagesJson, Path outDir) throws IOException {
        JsonNode root = JsonMapper.builder().build().readTree(messagesJson.toFile());
        Path out = outDir.toAbsolutePath().normalize();
        int n = 0;
        for (JsonNode message : root.path("data")) {
            for (JsonNode attachment : message.path("attachments")) {
                String path = attachment.path("path").asString();
                if (!"image/png".equals(attachment.path("type").asString()) || path.contains("..")
                        || !SampleSafetyCheck.ATTACHMENT_PATH.matcher(path).matches()
                        || !out.resolve(path).normalize().startsWith(out)) {
                    throw new IOException("Not a sample PNG attachment at a path of the layout "
                            + "messages/<x>/<xx>/<message uid>/<revision>/<file name>: " + path);
                }
                n++;
                if (n > MAX_ATTACHMENTS) {
                    throw new IOException("The sample draws at most " + MAX_ATTACHMENTS
                            + " attachments: the label \"SAMPLE n\" holds two digits");
                }
                Path file = out.resolve(path);
                Files.createDirectories(file.getParent());
                Files.write(file, png(picture(n)));
            }
        }
        return n;
    }

    /**
     * The rows the message import writes into the link tables, counted in the messages
     *
     * @return the expected count per table
     */
    public static Map<String, Integer> counts(Path messagesJson) throws IOException {
        JsonNode root = JsonMapper.builder().build().readTree(messagesJson.toFile());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : List.of("Message_Area", "Message_Category", "Message_Chart", "Attachment", "Reference",
                "FeatureCollection")) {
            counts.put(table, 0);
        }
        for (JsonNode message : root.path("data")) {
            counts.merge("Message_Area", message.path("areas").size(), Integer::sum);
            counts.merge("Message_Category", message.path("categories").size(), Integer::sum);
            counts.merge("Message_Chart", message.path("charts").size(), Integer::sum);
            counts.merge("Attachment", message.path("attachments").size(), Integer::sum);
            counts.merge("Reference", message.path("references").size(), Integer::sum);
            for (JsonNode part : message.path("parts")) {
                if (part.path("geometry").isObject()) {
                    counts.merge("FeatureCollection", 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** The head of an INSERT as pg_dump writes it, up to the opening parenthesis of its values */
    static final Pattern INSERT_HEAD = Pattern.compile("INSERT INTO public\\.\"([^\"]+)\" \\(([^)]*)\\) VALUES \\(");

    static final Pattern INTEGER = Pattern.compile("0|-?[1-9][0-9]*");

    /**
     * Reads the regeneration's keys file: per table, per base id, the row's natural key
     *
     * @param file the file, a line {@code <table> <id> <shape code> <part>...} per row, tab-separated
     * @return the keys
     */
    static Map<String, Map<Long, SampleReferences.Reference>> readKeys(Path file) throws IOException {
        Map<String, Map<Long, SampleReferences.Reference>> keys = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            SampleReferences.Shape shape = f.length < 4 ? null : SampleReferences.shape(f[0], f[2]);
            if (shape == null) {
                throw new IllegalArgumentException("Not a key of SampleReferences' shapes: " + line);
            }
            keys.computeIfAbsent(f[0], t -> new TreeMap<>()).put(Long.parseLong(f[1]),
                    new SampleReferences.Reference(shape, Arrays.asList(f).subList(3, f.length)));
        }
        return keys;
    }

    /**
     * Reads the regeneration's columns file: per table, per foreign-key column, the table it names
     *
     * @param file the file, a line {@code <table> <column> <parent table>} per column, tab-separated
     * @return the columns
     */
    static Map<String, Map<String, String>> readColumns(Path file) throws IOException {
        Map<String, Map<String, String>> columns = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length != 3) {
                throw new IllegalArgumentException("Not a line <table> <column> <parent table>: " + line);
            }
            columns.computeIfAbsent(f[0], t -> new TreeMap<>()).put(f[1], f[2]);
        }
        return columns;
    }

    /**
     * Names every base row a foreign-key column of an INSERT names by id by its natural key instead: a value below
     * {@link SampleReferences#FIRST_ID} becomes the key's reference. Only the values it replaces change; every other
     * byte of the SQL passes through.
     *
     * @param sql pg_dump's data-only column inserts
     * @param keys per table, per base id, the row's natural key
     * @param columns per table, per foreign-key column, the table the column names
     * @return the SQL with the references
     * @throws IllegalArgumentException naming table, column and value, for a value of such a column that is neither
     *         NULL, a sample id nor a base row with a key
     */
    static String references(String sql, Map<String, Map<Long, SampleReferences.Reference>> keys,
                             Map<String, Map<String, String>> columns) {
        StringBuilder out = new StringBuilder(sql.length() + sql.length() / 4);
        int copied = 0;
        for (int[] statement : statementSpans(sql)) {
            Matcher head = INSERT_HEAD.matcher(sql).region(statement[0], statement[1]);
            if (!head.lookingAt() || !columns.containsKey(head.group(1))) {
                continue;
            }
            String table = head.group(1);
            List<String> names = new ArrayList<>();
            for (String column : head.group(2).split(", ")) {
                names.add(SampleSafetyCheck.unquote(column));
            }
            List<int[]> values = valueSpans(sql, head.end(), statement[1] - 1);
            if (sql.charAt(statement[1] - 1) != ')' || values.size() != names.size()) {
                throw new IllegalArgumentException("An INSERT into \"" + table + "\" that cannot be read: "
                        + SampleSafetyCheck.abbreviate(sql.substring(statement[0], statement[1])));
            }
            for (int i = 0; i < names.size(); i++) {
                String parent = columns.get(table).get(names.get(i));
                if (parent == null) {
                    continue;
                }
                String value = sql.substring(values.get(i)[0], values.get(i)[1]);
                if (value.equals("NULL")) {
                    continue;
                }
                if (!INTEGER.matcher(value).matches()) {
                    throw new IllegalArgumentException("\"" + table + "\"." + names.get(i) + " holds " + value
                            + ", not an id");
                }
                long id = Long.parseLong(value);
                if (id >= SampleReferences.FIRST_ID) {
                    continue;
                }
                SampleReferences.Reference reference = keys.getOrDefault(parent, Map.of()).get(id);
                if (reference == null) {
                    throw new IllegalArgumentException("\"" + table + "\"." + names.get(i) + " names the row " + id
                            + " of \"" + parent + "\", which is no sample row and has no natural key in the keys file");
                }
                out.append(sql, copied, values.get(i)[0]).append(reference.value());
                copied = values.get(i)[1];
            }
        }
        return out.append(sql, copied, sql.length()).toString();
    }

    /**
     * The guard's natural-key checks ({@link SampleReferences#guard}), one per table, ordered by table and key
     *
     * @param keys per table, per base id, the row's natural key
     * @return the checks
     */
    static String guardKeys(Map<String, Map<Long, SampleReferences.Reference>> keys) {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(keys).forEach((table, rows) -> out.append(SampleReferences.guard(table, rows.values())));
        return out.toString();
    }

    /**
     * The statements of pg_dump's output, as {start, end} spans: from the first character that is neither a space
     * nor in a comment to the semicolon outside a literal that ends it (excluded)
     */
    static List<int[]> statementSpans(String sql) {
        List<int[]> spans = new ArrayList<>();
        int start = -1;
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {
                start = start < 0 ? i : start;
                i = literalEnd(sql, i) + 1;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == ';') {
                if (start >= 0) {
                    spans.add(new int[] {start, i});
                }
                start = -1;
            } else if (start < 0 && !Character.isWhitespace(c)) {
                start = i;
            }
            i++;
        }
        return spans;
    }

    /** The values between from and to (the closing parenthesis), as {start, end} spans without their spaces */
    static List<int[]> valueSpans(String sql, int from, int to) {
        List<int[]> spans = new ArrayList<>();
        int depth = 0;
        int start = from;
        int i = from;
        while (i < to) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = literalEnd(sql, i) + 1;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                spans.add(trimmed(sql, start, i));
                start = i + 1;
            }
            i++;
        }
        spans.add(trimmed(sql, start, to));
        return spans;
    }

    private static int[] trimmed(String sql, int start, int end) {
        int from = start;
        int to = end;
        while (from < to && Character.isWhitespace(sql.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(sql.charAt(to - 1))) {
            to--;
        }
        return new int[] {from, to};
    }

    /** The index of the quote that closes the literal opening at i, its doubled quotes skipped */
    private static int literalEnd(String sql, int i) {
        int end = i + 1;
        while (end < sql.length()) {
            if (sql.charAt(end) == '\'') {
                if (end + 1 < sql.length() && sql.charAt(end + 1) == '\'') {
                    end += 2;
                    continue;
                }
                return end;
            }
            end++;
        }
        return sql.length() - 1;
    }

    /** The n-th picture: a sea with a grid, a coast, a frame and the label "SAMPLE n", as palette indexes */
    static byte[][] picture(int n) {
        byte[][] px = new byte[HEIGHT][WIDTH];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int coast = 150 + (int) (20 * StrictMath.sin((x + 37 * n) / 23.0)) + (x * n % 7);
                byte colour = y > coast ? (byte) 1 : (x % 40 == 0 || y % 40 == 0) ? (byte) 3 : (byte) 0;
                if (x < 4 || y < 4 || x >= WIDTH - 4 || y >= HEIGHT - 4) {
                    colour = 2;
                }
                px[y][x] = colour;
            }
        }
        // A marker at a position that differs per picture, inside the frame for every n (the first four as they were
        // drawn when the sample was made)
        int cx = 60 + 50 * ((n - 1) % 4 + 1);
        int cy = 120 - 10 * ((n - 1) % 8 + 1);
        for (int y = -8; y <= 8; y++) {
            for (int x = -8; x <= 8; x++) {
                if (x * x + y * y <= 64) {
                    px[cy + y][cx + x] = 5;
                }
            }
        }
        drawText(px, "SAMPLE " + n, 40, 30, 5);
        return px;
    }

    static void drawText(byte[][] px, String text, int left, int top, int scale) {
        int x0 = left;
        for (char c : text.toCharArray()) {
            String[] glyph = FONT.get(c);
            if (glyph == null) {
                throw new IllegalArgumentException("No glyph for " + c);
            }
            for (int row = 0; row < glyph.length; row++) {
                for (int col = 0; col < glyph[row].length(); col++) {
                    if (glyph[row].charAt(col) == '#') {
                        for (int dy = 0; dy < scale; dy++) {
                            for (int dx = 0; dx < scale; dx++) {
                                px[top + row * scale + dy][x0 + col * scale + dx] = 4;
                            }
                        }
                    }
                }
            }
            x0 += 6 * scale;
        }
    }

    /**
     * Encodes palette indexes as a 4-bit palette PNG: IHDR, PLTE, IDAT and IEND only. The image data is a zlib
     * stream of stored blocks, which no zlib build can write differently.
     */
    static byte[] png(byte[][] px) throws IOException {
        int height = px.length;
        int width = px[0].length;
        int rowBytes = (width + 1) / 2;
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (byte[] row : px) {
            raw.write(0);
            for (int x = 0; x < rowBytes * 2; x += 2) {
                int hi = row[x] & 0x0f;
                int lo = x + 1 < width ? row[x + 1] & 0x0f : 0;
                raw.write((hi << 4) | lo);
            }
        }
        byte[] data = raw.toByteArray();

        ByteArrayOutputStream zlib = new ByteArrayOutputStream();
        zlib.write(0x78);
        zlib.write(0x01);
        for (int off = 0; off < data.length || off == 0; off += 65535) {
            int len = Math.min(65535, data.length - off);
            zlib.write(off + len >= data.length ? 1 : 0);
            zlib.write(len & 0xff);
            zlib.write((len >> 8) & 0xff);
            zlib.write(~len & 0xff);
            zlib.write((~len >> 8) & 0xff);
            zlib.write(data, off, len);
            if (data.length == 0) {
                break;
            }
        }
        Adler32 adler = new Adler32();
        adler.update(data);
        writeInt(zlib, (int) adler.getValue());

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(SampleSafetyCheck.PNG_SIGNATURE);
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, width);
        writeInt(ihdr, height);
        ihdr.write(new byte[] {4, 3, 0, 0, 0});
        chunk(png, "IHDR", ihdr.toByteArray());
        ByteArrayOutputStream plte = new ByteArrayOutputStream();
        for (int[] rgb : PALETTE) {
            plte.write(rgb[0]);
            plte.write(rgb[1]);
            plte.write(rgb[2]);
        }
        chunk(png, "PLTE", plte.toByteArray());
        chunk(png, "IDAT", zlib.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        writeInt(out, data.length);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }
}
