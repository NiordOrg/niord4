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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * The backend override check of a country build (docs/fe-country-split-plan.md §4.9, §4.11 rule 3;
 * be/docs/country-api.md, which says how a country records an override). Run by check-overrides.sh beside it,
 * in the JDK's source-launch mode, after the country's package step:
 *
 * <pre>java CheckOverrides.java &lt;the country's executable jar&gt; &lt;the country's be/ directory&gt;</pre>
 *
 * <p>A shadow is a file the country's jar carries at a path niord-base's jar also has. The country's files are those
 * under {@code BOOT-INF/classes/} and the {@code META-INF/} at the jar's root, where Spring Boot's repackaging keeps
 * the application's {@code META-INF/} (its {@code META-INF/resources/} images among them). Spring Boot puts the
 * country's classes first, so the country's copy wins, silently: a niord-base fix to that file never reaches the country. The
 * only shadow a country may have is a static image under {@code META-INF/resources/img/} (png, jpg, jpeg, gif, webp or
 * ico, in any case), listed in {@code be/overrides.json} with the fingerprint of niord-base's file it was made from,
 * so that a change to niord-base's file fails the build until the country has looked at it. The rules, each its own
 * finding:
 * <ul>
 *     <li>{@value #UNLISTED_SHADOW}: a shadow that is such an image and that overrides.json does not list (the finding
 *         prints the fingerprint to record);</li>
 *     <li>{@value #FORBIDDEN_SHADOW}: every other shadow, listed or not, its fingerprint correct or not: code,
 *         configuration or data (a class, {@code config/}, {@code db/}, {@code keycloak-client-template.json}, a batch
 *         job's XML, an XSLT, a stylesheet, an image outside {@code META-INF/resources/img/} such as the root
 *         {@code blank_256.png}, a text file beside the images). An SVG is not a permitted image: served from the
 *         application's own origin, an SVG is a document that can run script, and niord-base ships none;</li>
 *     <li>{@value #TEMPLATE_IN_JAR}: any {@code templates/**} file in the country's jar: templates are bootstrap
 *         data (be/bootstrap/);</li>
 *     <li>{@value #DEFAULTS_BASE}: any {@code defaults/base/**} file in the country's jar: niord-base's own defaults;</li>
 *     <li>{@value #COMMITTED_PACKS}: anything under {@code src/main/resources/defaults/packs/} in the country's
 *         tree: the packs' backend half is generated at build, never committed;</li>
 *     <li>{@value #STALE_BASED_ON}: a listed override whose fingerprint no longer matches niord-base's file, or whose
 *         file niord-base no longer has;</li>
 *     <li>{@value #FORMAT}: overrides.json is not exactly
 *         {@code {"overrides": [{"file", "basedOn": {"<file>": "sha256:<64 hex>"}, "reason", "added": "yyyy-mm-dd"}]}}:
 *         an unknown or missing member, a blank reason, a {@code basedOn} that is not the file alone, a file listed
 *         twice, or a listed file the country's jar does not carry.</li>
 * </ul>
 * Files every jar may carry and that are read from all jars together are not shadows: the manifest,
 * {@code META-INF/maven/**}, {@code META-INF/spring.factories}, {@code META-INF/spring/*.imports},
 * {@code META-INF/services/**}, Spring's configuration metadata and {@code META-INF/niord/expected-template-paths.txt}.
 *
 * <p>Exit status 0 when the check passes, 1 on findings, 2 on wrong usage or input that cannot be read. JDK built-ins
 * only, with a small JSON reader, so that it runs wherever the country's build has a JDK.
 */
public class CheckOverrides {

    public static final String UNLISTED_SHADOW = "unlisted-shadow";
    public static final String FORBIDDEN_SHADOW = "forbidden-shadow";
    public static final String TEMPLATE_IN_JAR = "template-in-jar";
    public static final String DEFAULTS_BASE = "defaults-base";
    public static final String COMMITTED_PACKS = "committed-packs";
    public static final String STALE_BASED_ON = "stale-basedOn";
    public static final String FORMAT = "format";

    static final String CLASSES = "BOOT-INF/classes/";
    static final String ROOT_META_INF = "META-INF/";
    static final Pattern NIORD_BASE_JAR = Pattern.compile("BOOT-INF/lib/niord-base-[^/]*\\.jar");
    static final Pattern FINGERPRINT = Pattern.compile("sha256:[0-9a-f]{64}");
    static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    static final String OVERRIDES = "overrides.json";
    static final String PACKS = "src/main/resources/defaults/packs";

    /** The one place a shadow may be, and the extensions of the static images it may have */
    static final String PERMITTED_IMAGES = "META-INF/resources/img/";
    static final Pattern IMAGE = Pattern.compile("[^/]+\\.(?i:png|jpe?g|gif|webp|ico)");

    /** Files read from every jar together, so a copy in each is not a shadow */
    static final List<Pattern> MERGED = List.of(
            Pattern.compile("META-INF/MANIFEST\\.MF"),
            Pattern.compile("META-INF/maven/.*"),
            Pattern.compile("META-INF/spring\\.factories"),
            Pattern.compile("META-INF/spring/[^/]*\\.imports"),
            Pattern.compile("META-INF/services/.*"),
            Pattern.compile("META-INF/[^/]*spring-configuration-metadata\\.json"),
            Pattern.compile("META-INF/niord/expected-template-paths\\.txt"));

    /** An entry of overrides.json */
    record Entry(String file, String basedOn, String reason, String added) {
    }

    /** What the check found: a rule, a path and what is wrong */
    record Finding(String rule, String path, String detail) {
        @Override
        public String toString() {
            return rule + " " + path + ": " + detail;
        }
    }

    /** Input the check cannot read: exit status 2 */
    static final class Unreadable extends Exception {
        Unreadable(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the check
     *
     * @param args the country's executable jar and its be/ directory
     * @param out  where the report goes
     * @param err  where usage and input errors go
     * @return 0 when the check passes, 1 on findings, 2 on wrong usage or unreadable input
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.println("usage: check-overrides.sh <the country's executable jar> <the country's be/ directory>");
            return 2;
        }
        Path jar = Path.of(args[0]);
        Path be = Path.of(args[1]);
        try {
            if (!Files.isRegularFile(jar)) {
                throw new Unreadable("no such jar: " + jar);
            }
            if (!Files.isDirectory(be)) {
                throw new Unreadable("no such directory: " + be);
            }
            List<Finding> findings = new ArrayList<>();
            List<Entry> overrides = readOverrides(be.resolve(OVERRIDES), findings);

            Map<String, String> country = new TreeMap<>();
            Map<String, String> base = new TreeMap<>();
            String baseJar = readJar(jar, country, base);

            out.println("Backend overrides (" + OVERRIDES + "): " + overrides.size() + " listed; niord-base "
                    + baseJar.substring(baseJar.lastIndexOf('/') + 1));
            for (Entry o : overrides) {
                out.println("  " + o.file() + " (added " + o.added() + "): " + o.reason());
            }

            check(country, base, overrides, findings);
            committedPacks(be, findings);

            for (Finding finding : findings) {
                out.println("FAIL " + finding);
            }
            out.println(findings.isEmpty()
                    ? "The override check passes."
                    : "The override check fails: " + findings.size() + (findings.size() == 1 ? " finding" : " findings")
                    + " (be/docs/country-api.md, Overrides).");
            return findings.isEmpty() ? 0 : 1;
        } catch (Unreadable e) {
            err.println("ERROR: " + e.getMessage());
            return 2;
        } catch (IOException e) {
            err.println("ERROR: cannot read the input: " + e);
            return 2;
        }
    }

    /** Applies the rules to the country's files and niord-base's, both by path with their fingerprints */
    static void check(Map<String, String> country, Map<String, String> base, List<Entry> overrides,
                      List<Finding> findings) {
        Map<String, Entry> listed = new LinkedHashMap<>();
        overrides.forEach(o -> listed.putIfAbsent(o.file(), o));

        for (Map.Entry<String, String> file : country.entrySet()) {
            String path = file.getKey();
            if (path.startsWith("templates/")) {
                findings.add(new Finding(TEMPLATE_IN_JAR, path,
                        "a template is bootstrap data: it goes to be/bootstrap/, never into the jar"));
                continue;
            }
            if (path.startsWith("defaults/base/")) {
                findings.add(new Finding(DEFAULTS_BASE, path,
                        "defaults/base/ is niord-base's; a country's defaults go under defaults/country/"));
                continue;
            }
            if (!base.containsKey(path) || merged(path)) {
                continue;
            }
            if (!permittedImage(path)) {
                findings.add(new Finding(FORBIDDEN_SHADOW, path, "only a static image under " + PERMITTED_IMAGES
                        + " (png, jpg, jpeg, gif, webp, ico) may be overridden; this file is code, configuration or "
                        + "data: ask for an extension point"));
            } else if (!listed.containsKey(path)) {
                findings.add(new Finding(UNLISTED_SHADOW, path, "shadows niord-base's file; list it in "
                        + OVERRIDES + " with basedOn \"" + base.get(path) + "\", a reason and the date, or remove it"));
            }
        }

        for (Entry o : listed.values()) {
            if (!country.containsKey(o.file())) {
                findings.add(new Finding(FORMAT, o.file(), "listed, but the country's jar does not carry it"));
            } else if (!base.containsKey(o.file())) {
                findings.add(new Finding(STALE_BASED_ON, o.file(),
                        "niord-base no longer has this file: compare, then remove the override or its entry"));
            } else if (o.basedOn() != null && !o.basedOn().equals(base.get(o.file()))) {
                findings.add(new Finding(STALE_BASED_ON, o.file(), "niord-base's file has changed since "
                        + o.basedOn() + ": compare the country's copy with niord-base's, then record basedOn \""
                        + base.get(o.file()) + "\""));
            }
        }
    }

    static boolean merged(String path) {
        return MERGED.stream().anyMatch(p -> p.matcher(path).matches());
    }

    /** A static image under META-INF/resources/img/, in any folder below it: the only shadow a country may have */
    static boolean permittedImage(String path) {
        if (!path.startsWith(PERMITTED_IMAGES)) {
            return false;
        }
        String rest = path.substring(PERMITTED_IMAGES.length());
        return IMAGE.matcher(rest.substring(rest.lastIndexOf('/') + 1)).matches();
    }

    /** Anything committed under the country's src/main/resources/defaults/packs/ */
    static void committedPacks(Path be, List<Finding> findings) throws IOException {
        Path packs = be.resolve(PACKS);
        if (!Files.exists(packs)) {
            return;
        }
        try (Stream<Path> files = Files.walk(packs)) {
            files.filter(Files::isRegularFile).sorted().forEach(f -> findings.add(new Finding(COMMITTED_PACKS,
                    be.relativize(f).toString().replace('\\', '/'),
                    "the packs' backend half is staged at build under target/generated-resources, never committed")));
        }
        if (Files.isDirectory(packs) && findings.stream().noneMatch(f -> f.rule().equals(COMMITTED_PACKS))) {
            findings.add(new Finding(COMMITTED_PACKS, PACKS + "/", "an empty folder; remove it"));
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The jars
    // ------------------------------------------------------------------------------------------- //

    /**
     * Reads the country's files (under BOOT-INF/classes/, and the META-INF/ at the root, where Spring Boot keeps the
     * application's META-INF/) and the files of the one niord-base jar in BOOT-INF/lib/, each by path with its
     * fingerprint, and returns the niord-base jar's entry name
     */
    static String readJar(Path jar, Map<String, String> country, Map<String, String> base)
            throws IOException, Unreadable {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<ZipEntry> baseJars = new ArrayList<>();
            boolean bootClasses = false;
            for (ZipEntry entry : zip.stream().toList()) {
                if (entry.getName().startsWith(CLASSES)) {
                    bootClasses = true;
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getName().startsWith(CLASSES) || entry.getName().startsWith(ROOT_META_INF)) {
                    String path = entry.getName().startsWith(CLASSES)
                            ? entry.getName().substring(CLASSES.length())
                            : entry.getName();
                    try (InputStream in = zip.getInputStream(entry)) {
                        country.put(path, sha256(in));
                    }
                } else if (NIORD_BASE_JAR.matcher(entry.getName()).matches()) {
                    baseJars.add(entry);
                }
            }
            if (!bootClasses) {
                throw new Unreadable(jar + " has no " + CLASSES + ": not a Spring Boot executable jar");
            }
            if (baseJars.size() != 1) {
                throw new Unreadable(jar + " holds " + baseJars.size() + " niord-base jars in BOOT-INF/lib/, "
                        + "not exactly one: " + baseJars.stream().map(ZipEntry::getName).toList());
            }
            byte[] nested;
            try (InputStream in = zip.getInputStream(baseJars.getFirst())) {
                nested = in.readAllBytes();
            }
            try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(nested))) {
                for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                    if (!entry.isDirectory()) {
                        base.put(entry.getName(), sha256(in));
                    }
                }
            }
            if (base.isEmpty()) {
                throw new Unreadable(baseJars.getFirst().getName() + " in " + jar + " is empty or not a jar");
            }
            return baseJars.getFirst().getName();
        }
    }

    /** The fingerprint of a file, as basedOn records it: sha256:&lt;64 hex&gt; */
    static String sha256(InputStream in) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[8192];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            digest.update(buffer, 0, n);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    // ------------------------------------------------------------------------------------------- //
    // overrides.json
    // ------------------------------------------------------------------------------------------- //

    /** Reads overrides.json (absent: no overrides); every way it breaks the format is a finding */
    static List<Entry> readOverrides(Path file, List<Finding> findings) throws IOException {
        List<Entry> overrides = new ArrayList<>();
        if (!Files.exists(file)) {
            return overrides;
        }
        Object root;
        try {
            root = new Json(Files.readString(file, StandardCharsets.UTF_8)).document();
        } catch (IllegalArgumentException e) {
            findings.add(new Finding(FORMAT, OVERRIDES, e.getMessage()));
            return overrides;
        }
        if (!(root instanceof Map<?, ?> rootObject)) {
            findings.add(new Finding(FORMAT, OVERRIDES, "is not a JSON object"));
            return overrides;
        }
        unknownMembers(rootObject, Set.of("overrides"), OVERRIDES, findings);
        if (!(rootObject.get("overrides") instanceof List<?> entries)) {
            findings.add(new Finding(FORMAT, OVERRIDES, "has no \"overrides\" array"));
            return overrides;
        }
        Set<String> files = new TreeSet<>();
        for (int i = 0; i < entries.size(); i++) {
            String where = OVERRIDES + " overrides[" + i + "]";
            if (!(entries.get(i) instanceof Map<?, ?> entry)) {
                findings.add(new Finding(FORMAT, where, "is not an object"));
                continue;
            }
            unknownMembers(entry, Set.of("file", "basedOn", "reason", "added"), where, findings);
            String path = entry.get("file") instanceof String s ? s : null;
            if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                    || ("/" + path + "/").contains("/../") || path.endsWith("/")) {
                findings.add(new Finding(FORMAT, where, "\"file\" must be a niord-base resource path, "
                        + "relative, with forward slashes"));
                continue;
            }
            where = OVERRIDES + " " + path;
            if (!files.add(path)) {
                findings.add(new Finding(FORMAT, where, "is listed more than once"));
                continue;
            }
            String basedOn = null;
            if (entry.get("basedOn") instanceof Map<?, ?> fingerprints && fingerprints.size() == 1
                    && fingerprints.get(path) instanceof String fingerprint && FINGERPRINT.matcher(fingerprint).matches()) {
                basedOn = fingerprint;
            } else {
                findings.add(new Finding(FORMAT, where, "\"basedOn\" must be {\"" + path + "\": \"sha256:<64 hex>\"}, "
                        + "the fingerprint of niord-base's file and nothing else"));
            }
            String reason = entry.get("reason") instanceof String s ? s : null;
            if (reason == null || reason.isBlank()) {
                findings.add(new Finding(FORMAT, where, "\"reason\" must say why the country replaces the file"));
            }
            String added = entry.get("added") instanceof String s ? s : null;
            if (added == null || !DATE.matcher(added).matches() || !isDate(added)) {
                findings.add(new Finding(FORMAT, where, "\"added\" must be the date it was added, yyyy-mm-dd"));
            }
            overrides.add(new Entry(path, basedOn, reason, added));
        }
        return overrides;
    }

    private static void unknownMembers(Map<?, ?> object, Set<String> known, String where, List<Finding> findings) {
        for (Object member : object.keySet()) {
            if (!known.contains(member)) {
                findings.add(new Finding(FORMAT, where, "unknown member \"" + member + "\""));
            }
        }
    }

    private static boolean isDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * A small JSON reader: objects as ordered maps (a member twice is an error), arrays as lists, strings, numbers as
     * text, true, false and null. Enough for overrides.json; an error names its position.
     */
    static final class Json {

        private final String text;
        private int pos;

        Json(String text) {
            // a UTF-8 byte order mark, which an editor on Windows may write
            this.text = text.startsWith("\uFEFF") ? text.substring(1) : text;
        }

        Object document() {
            Object value = value();
            space();
            if (pos != text.length()) {
                throw error("unexpected text after the JSON value");
            }
            return value;
        }

        private Object value() {
            space();
            if (pos >= text.length()) {
                throw error("unexpected end");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || Character.isDigit(c)) {
                        yield number();
                    }
                    throw error("unexpected '" + c + "'");
                }
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> object = new LinkedHashMap<>();
            pos++;
            space();
            if (peek('}')) {
                pos++;
                return object;
            }
            while (true) {
                space();
                if (!peek('"')) {
                    throw error("a member name is expected");
                }
                String name = string();
                if (object.containsKey(name)) {
                    throw error("the member \"" + name + "\" is given twice");
                }
                space();
                expect(':');
                object.put(name, value());
                space();
                if (peek(',')) {
                    pos++;
                } else {
                    expect('}');
                    return object;
                }
            }
        }

        private List<Object> array() {
            List<Object> array = new ArrayList<>();
            pos++;
            space();
            if (peek(']')) {
                pos++;
                return array;
            }
            while (true) {
                array.add(value());
                space();
                if (peek(',')) {
                    pos++;
                } else {
                    expect(']');
                    return array;
                }
            }
        }

        private String string() {
            StringBuilder s = new StringBuilder();
            pos++;
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return s.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        break;
                    }
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> s.append(e);
                        case 'b' -> s.append('\b');
                        case 'f' -> s.append('\f');
                        case 'n' -> s.append('\n');
                        case 'r' -> s.append('\r');
                        case 't' -> s.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw error("a broken \\u escape");
                            }
                            s.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("an unknown escape \\" + e);
                    }
                } else if (c < 0x20) {
                    throw error("a control character in a string");
                } else {
                    s.append(c);
                }
            }
            throw error("an unterminated string");
        }

        private String number() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            return text.substring(start, pos);
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw error("unexpected text");
            }
            pos += word.length();
            return value;
        }

        private void space() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private boolean peek(char c) {
            return pos < text.length() && text.charAt(pos) == c;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw error("'" + c + "' is expected");
            }
            pos++;
        }

        private IllegalArgumentException error(String message) {
            int line = 1;
            for (int i = 0; i < Math.min(pos, text.length()); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            return new IllegalArgumentException("is not valid JSON: " + message + " (line " + line + ")");
        }
    }
}
