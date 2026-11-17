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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sample's safety rules as code (docs/fe-country-split-plan.md P9, security review of 2026-10-19), over the
 * sample's SQL ({@code be/niord/sample/niord-sample.sql}), whose {@code RepoFile} rows are its attachment files.
 * Each finding names its rule:
 * <ol type="a">
 *   <li>the SQL holds only the statements the regeneration writes: {@code \set ON_ERROR_STOP on}, {@code BEGIN},
 *       {@code COMMIT}; one {@code DO $guard$} block, the first statement after {@code BEGIN}, whose body (its
 *       string literals left out) holds no INSERT, UPDATE, DELETE, COPY, TRUNCATE, EXECUTE, CREATE, DROP, ALTER or
 *       GRANT; {@code SET <name> = <value>} and {@code SET SESSION AUTHORIZATION DEFAULT};
 *       {@code ALTER TABLE public."<table>" DISABLE|ENABLE TRIGGER ALL}; a one-row {@code INSERT} whose every
 *       value is a string literal (a cast after it allowed), a number, {@code NULL}, {@code true}, {@code false} or a
 *       base row named by its natural key in one of {@link SampleReferences}' shapes (on Dictionary,
 *       DictionaryEntry, PromulgationType, Category or TemplateParam), and no other expression; and
 *       {@code SELECT pg_catalog.setval(...)} of a table's sequence to the greater of its last value and the
 *       table's largest id. Every table they name is on the allow-list, the tables the regeneration captures: a
 *       user, contact, comment, history, recipient, batch, setting, SECOM or issue row, or any table not listed,
 *       fails. Any other statement, a psql meta-command among them, fails;</li>
 *   <li>the only e-mail domain is {@code example.org}, in the whole SQL and in the content of every {@code RepoFile}
 *       row that is not a PNG; the address may hold letters outside ASCII;</li>
 *   <li>no phone number in a string value of an {@code INSERT}, read with {@code &nbsp;} and the no-break space as
 *       spaces: {@code +} or {@code 00} with a country code and digits; eight digits in a row; eight digits grouped
 *       2-2-2-2 by one kind of separator (a space, a dot or a dash); or tlf/tel/phone followed by digits. The
 *       identifier columns ({@link #IDENTIFIER_COLUMNS}) are not read;</li>
 *   <li>every message title starts with {@value #TITLE_PREFIX};</li>
 *   <li>the sentinel line ({@value #SENTINEL_PREFIX}…) is present, so check-no-sample.sh can recognise the SQL in
 *       a jar or a docker export tar;</li>
 *   <li>every {@code RepoFile} row lies at a path of the repository's layout ({@link #ATTACHMENT_PATH}) and holds
 *       a well-formed PNG whose chunks are exactly IHDR, PLTE, IDAT and IEND, as {@link SampleTool} writes them: no metadata chunk ({@code tEXt}, {@code iTXt}, {@code zTXt}, {@code tIME},
 *       {@code eXIf}), no colour profile, no private chunk.</li>
 * </ol>
 * The regeneration script ({@code be/niord/scripts/regen-sample.sh}) runs it on its candidate before it writes
 * anything, and {@code SampleFolderTest} on the committed sample in every Backend CI run.
 */
public final class SampleSafetyCheck {

    /** Every message title, in every language, starts with this */
    public static final String TITLE_PREFIX = "SAMPLE — ";

    /** The start of the sentinel line of the sample's SQL */
    public static final String SENTINEL_PREFIX = "-- niord-sample-sentinel: ";

    /** The only e-mail domain the sample may name */
    public static final String EMAIL_DOMAIN = "example.org";

    /** The tables the regeneration captures: everything else is refused */
    public static final Set<String> ALLOWED_TABLES = Set.of(
            // Domains, series and promulgation types
            "Domain", "Domain_Area", "Domain_Category", "Domain_MessageSeries",
            "MessageSeries", "MessageSeries_editorFields", "MessageSeries_types",
            "PromulgationType", "PromulgationType_Domain", "PromulgationType_messageTypes",
            "PromulgationType_scriptResourcePaths",
            // Categories, template parameters, parameter types, dictionaries and scripts
            "Category", "CategoryDesc", "Category_Domain", "Category_TemplateParam", "Category_editorFields",
            "Category_scriptResourcePaths", "Category_stdTemplateFields",
            "TemplateParam", "TemplateParamDesc",
            "ParamType", "ParamType_DictionaryEntry", "ParamType_TemplateParam",
            "Dictionary", "DictionaryEntry", "DictionaryEntryDesc",
            "ScriptResource",
            // Danish place data and publications
            "Chart", "Area", "AreaDesc", "Area_editorFields", "Source", "SourceDesc",
            "PublicationCategory", "PublicationCategoryDesc", "Publication", "PublicationDesc",
            // Mailing lists: definitions and triggers, never their recipients
            "MailingList", "MailingListDesc", "MailingListTrigger", "MailingListTriggerDesc",
            "MailingListTrigger_scriptResourcePaths", "MailingListTrigger_statusChanges",
            // Messages
            "Message", "MessageDesc", "MessagePart", "MessagePartDesc", "Reference", "ReferenceDesc",
            "Attachment", "AttachmentDesc", "RepoFile", "FeatureCollection", "Feature", "DateInterval",
            "Message_Area", "Message_Category", "Message_Chart",
            "BaseMessagePromulgation", "NavtexMessagePromulgation", "NavtexMessagePromulgation_NavtexTransmitter",
            "AudioMessagePromulgation", "TwitterMessagePromulgation", "SafetyNetMessagePromulgation",
            "NavtexTransmitter", "NavtexTransmitter_Area",
            // The message series' next numbers
            "Sequence");

    /**
     * The path of an attachment in the repository store, in the layout RepositoryService reads:
     * {@code messages/<x>/<xx>/<message uid>/<revision>/<file name>}
     */
    public static final Pattern ATTACHMENT_PATH = Pattern.compile(
            "messages/[0-9a-f]/[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9]+/"
                    + "[A-Za-z0-9][A-Za-z0-9._-]*");

    /** Columns that hold identifiers, paths and hashes, whose digits are never a phone number */
    public static final Set<String> IDENTIFIER_COLUMNS = Set.of(
            "uid", "repopath", "path", "folder", "sha256", "mrn", "legacyid", "shortformat", "navtexformat");

    static final Pattern EMAIL = Pattern.compile(
            "[\\p{L}\\p{N}._%+-]+@([\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+)");

    static final Pattern PHONE_INTERNATIONAL = Pattern.compile(
            "(?<![\\w/])(?<!\\d[.,])(?:\\+|00)\\d{1,3}[ -]?\\d(?:[ -]?\\d){6,12}(?![\\w/])(?![.,]\\d)");

    static final Pattern PHONE_GROUPED = Pattern.compile(
            "(?<![\\w/])(?<!\\d[.,])\\d{2}([ .-])\\d{2}\\1\\d{2}\\1\\d{2}(?![\\w/])(?![.,]\\d)");

    static final Pattern PHONE_PLAIN = Pattern.compile(
            "(?<![\\w/:°])(?<!\\d[.,])\\d{8}(?![\\w/:])(?![.,]\\d)");

    static final Pattern PHONE_LABELLED = Pattern.compile(
            "(?i)\\b(?:tlf|tel|telefon|phone)\\b\\.?:?\\s*\\+?\\d");

    static final Pattern SENTINEL = Pattern.compile(
            "^" + Pattern.quote(SENTINEL_PREFIX) + "[0-9a-f]{32}$", Pattern.MULTILINE);

    /** A string literal, its quotes doubled inside: written so that a long literal never recurses in the matcher */
    static final String LITERAL = "'[^']*+(?:''[^']*+)*+'";

    /** The one psql meta-command the SQL holds */
    static final String SET_ON_ERROR_STOP = "\\set ON_ERROR_STOP on";

    static final Pattern GUARD = Pattern.compile("(?s)DO \\$guard\\$(.*)\\$guard\\$");

    static final Pattern GUARD_FORBIDDEN = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|COPY|TRUNCATE|EXECUTE|CREATE|DROP|ALTER|GRANT)\\b");

    static final Pattern SET = Pattern.compile(
            "SET [a-z_]+ = (?:'[^']*'|[A-Za-z0-9_]+)|SET SESSION AUTHORIZATION DEFAULT");

    static final Pattern TRIGGERS = Pattern.compile("ALTER TABLE public\\.\"([^\"]+)\" (?:DISABLE|ENABLE) TRIGGER ALL");

    static final Pattern SETVAL = Pattern.compile("SELECT pg_catalog\\.setval\\('public\\.\"([^\"]+)_SEQ\"', "
            + "greatest\\(\\(SELECT last_value FROM public\\.\"\\1_SEQ\"\\), "
            + "\\(SELECT max\\(id\\) FROM public\\.\"\\1\"\\)\\)\\)");

    /** A value of an INSERT that is no expression: a literal (a cast after it allowed), a number, NULL, a boolean */
    static final Pattern PLAIN_VALUE = Pattern.compile(
            LITERAL + "(?:::[a-z][a-z0-9_ ]*(?:\\[\\])?)?|-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|NULL|true|false");

    static final Pattern INSERT = Pattern.compile(
            "(?s)^INSERT INTO\\s+(?:public\\.)?(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*?)\\)\\s*VALUES\\s*\\((.*)\\)\\s*$");

    static final Set<String> PNG_METADATA_CHUNKS = Set.of("tEXt", "iTXt", "zTXt", "tIME", "eXIf");

    /** The chunks SampleTool writes, and the only ones a repository PNG may hold */
    static final List<String> PNG_CHUNKS = List.of("IHDR", "PLTE", "IDAT", "IEND");

    static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    /** A bytea literal's text, as pg_dump writes a row's content: hex after {@code \x} */
    static final Pattern BYTEA = Pattern.compile("\\\\x([0-9a-fA-F]*)");

    private SampleSafetyCheck() {
    }

    /**
     * Checks the sample's SQL file.
     *
     * @param sql the SQL file
     * @return one line per finding, each naming its rule; empty when the sample keeps every rule
     */
    public static List<String> check(Path sql) throws IOException {
        return checkSql(Files.readString(sql, StandardCharsets.UTF_8));
    }

    /**
     * Checks the text of the sample's SQL: rules (a) to (f).
     *
     * @param sql the SQL
     * @return the findings
     */
    public static List<String> checkSql(String sql) {
        List<String> findings = new ArrayList<>();
        if (!SENTINEL.matcher(sql).find()) {
            findings.add("rule (e) sentinel: no line '" + SENTINEL_PREFIX + "<32 hex digits>'");
        }
        findings.addAll(checkEmails("the SQL", sql));
        List<String> statements = statements(sql);
        int begin = -1;
        boolean guard = false;
        for (int s = 0; s < statements.size(); s++) {
            String statement = statements.get(s);
            if (statement.equals(SET_ON_ERROR_STOP) || statement.equals("COMMIT")
                    || SET.matcher(statement).matches()) {
                continue;
            }
            if (statement.equals("BEGIN")) {
                begin = begin < 0 ? s : begin;
                continue;
            }
            if (statement.startsWith("DO ") || statement.startsWith("DO\n")) {
                findings.addAll(checkGuard(statement, !guard && begin >= 0 && s == begin + 1));
                guard = true;
                continue;
            }
            Matcher triggers = TRIGGERS.matcher(statement);
            Matcher setval = SETVAL.matcher(statement);
            if ((triggers.matches() && ALLOWED_TABLES.contains(triggers.group(1)))
                    || (setval.matches() && ALLOWED_TABLES.contains(setval.group(1)))) {
                continue;
            }
            if (!statement.startsWith("INSERT INTO ")) {
                findings.add("rule (a) statement: one the regeneration does not write: " + abbreviate(statement));
                continue;
            }
            Matcher m = INSERT.matcher(statement);
            if (!m.matches() || !balanced(m.group(3))) {
                findings.add("rule (a) table: an INSERT that cannot be read: " + abbreviate(statement));
                continue;
            }
            String table = unquote(m.group(1));
            if (!ALLOWED_TABLES.contains(table)) {
                findings.add("rule (a) table: INSERT into \"" + table + "\", which is outside the allow-list");
                continue;
            }
            List<String> columns = splitColumns(m.group(2));
            List<String> values = splitValues(m.group(3));
            if (columns.size() != values.size()) {
                findings.add("rule (a) table: an INSERT into \"" + table + "\" whose values do not match its columns");
                continue;
            }
            if ("RepoFile".equals(table)) {
                findings.addAll(checkRepoFile(new Insert(table, columns, values)));
            }
            for (int i = 0; i < columns.size(); i++) {
                String column = columns.get(i);
                String value = values.get(i);
                if (!PLAIN_VALUE.matcher(value).matches() && SampleReferences.parse(value) == null) {
                    findings.add("rule (a) value: \"" + table + "\"." + column + " is an expression that is neither a "
                            + "literal, a number, NULL, a boolean nor a base row named by its natural key: "
                            + abbreviate(value));
                    continue;
                }
                boolean literal = value.startsWith("'");
                String text = literal ? unquoteLiteral(value) : null;
                boolean content = "RepoFile".equals(table) && "data".equals(column);
                if (literal && !content && !IDENTIFIER_COLUMNS.contains(column.toLowerCase(Locale.ROOT))) {
                    String phone = phone(text.replace("&nbsp;", " ").replace("&#160;", " ").replace('\u00a0', ' '));
                    if (phone != null) {
                        findings.add("rule (c) phone: \"" + table + "\"." + column + " holds '" + phone + "'");
                    }
                }
                if ("MessageDesc".equals(table) && "title".equals(column)
                        && (text == null || !text.startsWith(TITLE_PREFIX))) {
                    findings.add("rule (d) title: a message title does not start with '" + TITLE_PREFIX.strip()
                            + "': " + (text == null ? "NULL" : abbreviate(text)));
                }
            }
        }
        return findings;
    }

    /**
     * Rule (a) on a DO block: the guard, once, as the first statement after BEGIN, writing nothing
     *
     * @param statement the DO statement
     * @param first whether it is the first DO block and the first statement after BEGIN
     * @return the findings
     */
    static List<String> checkGuard(String statement, boolean first) {
        Matcher m = GUARD.matcher(statement);
        if (!m.matches() || !first) {
            return List.of("rule (a) statement: a DO block other than the guard (one DO $guard$ block, the first "
                    + "statement after BEGIN): " + abbreviate(statement));
        }
        // The body without its string literals and quoted names, whose words are text, not statements
        String body = m.group(1).replaceAll(LITERAL, "''").replaceAll("\"[^\"]*\"", "\"\"");
        Matcher forbidden = GUARD_FORBIDDEN.matcher(body);
        if (forbidden.find()) {
            return List.of("rule (a) statement: the guard's body holds " + forbidden.group(1).toUpperCase(Locale.ROOT)
                    + ", and the guard only reads");
        }
        return List.of();
    }

    /**
     * Rule (f) on a {@code RepoFile} row: its path is one of the repository's layout, and its content a PNG as
     * SampleTool writes them; rule (b) on a content that is not a PNG.
     *
     * @param row the INSERT of the row
     * @return the findings
     */
    static List<String> checkRepoFile(Insert row) {
        List<String> findings = new ArrayList<>();
        String path = row.text("path");
        String name = path == null ? "a RepoFile row without a path" : path;
        if (path == null || !ATTACHMENT_PATH.matcher(path).matches()) {
            findings.add("rule (f) attachment: " + name + " is not a path of the repository's layout");
        }
        String data = row.text("data");
        Matcher hex = data == null ? null : BYTEA.matcher(data);
        if (hex == null || !hex.matches() || hex.group(1).length() % 2 != 0) {
            findings.add("rule (f) attachment: " + name + " holds no bytea content");
            return findings;
        }
        findings.addAll(checkContent(name, HexFormat.of().parseHex(hex.group(1))));
        return findings;
    }

    /**
     * Checks the content of a repository row: rule (f) on it, and rule (b) when it is not a PNG.
     *
     * @param name the row's path, named in a finding
     * @param bytes the content
     * @return the findings
     */
    public static List<String> checkContent(String name, byte[] bytes) {
        List<String> findings = new ArrayList<>();
        if (isPng(bytes)) {
            List<String> chunks = pngChunks(bytes);
            String metadata = chunks == null ? null
                    : chunks.stream().filter(PNG_METADATA_CHUNKS::contains).findFirst().orElse(null);
            if (chunks == null) {
                findings.add("rule (f) chunks: " + name + " is not a well-formed PNG");
            } else if (metadata != null) {
                findings.add("rule (f) metadata: " + name + " carries a " + metadata + " chunk");
            } else if (!chunks.equals(PNG_CHUNKS)) {
                findings.add("rule (f) chunks: " + name + " carries the chunks " + chunks + ", not exactly "
                        + PNG_CHUNKS);
            }
        } else {
            findings.add("rule (f) attachment: " + name + " is not a PNG file, as SampleTool writes them");
            findings.addAll(checkEmails(name, new String(bytes, StandardCharsets.UTF_8)));
        }
        return findings;
    }

    /**
     * An INSERT of the SQL as written: its table and column names unquoted, its values with their quotes
     *
     * @param table the table
     * @param columns the column names
     * @param values the values, one per column
     */
    public record Insert(String table, List<String> columns, List<String> values) {

        /**
         * Returns the text of the column's value
         *
         * @param column the column
         * @return the text of its literal, or null when the value is not a literal or the column is not there
         */
        public String text(String column) {
            int i = columns.indexOf(column);
            return i < 0 || !values.get(i).startsWith("'") ? null : unquoteLiteral(values.get(i));
        }
    }

    /**
     * Returns the INSERTs of the SQL that can be read, in order: rule (a) reports the others
     *
     * @param sql the SQL
     * @return the INSERTs
     */
    public static List<Insert> inserts(String sql) {
        List<Insert> inserts = new ArrayList<>();
        for (String statement : statements(sql)) {
            Matcher m = INSERT.matcher(statement);
            if (statement.startsWith("INSERT INTO ") && m.matches() && balanced(m.group(3))) {
                List<String> columns = splitColumns(m.group(2));
                List<String> values = splitValues(m.group(3));
                if (columns.size() == values.size()) {
                    inserts.add(new Insert(unquote(m.group(1)), columns, values));
                }
            }
        }
        return inserts;
    }

    /** Rule (b): every e-mail address of the text is at example.org */
    static List<String> checkEmails(String where, String text) {
        List<String> findings = new ArrayList<>();
        Matcher m = EMAIL.matcher(text);
        while (m.find()) {
            if (!EMAIL_DOMAIN.equalsIgnoreCase(m.group(1))) {
                findings.add("rule (b) e-mail: " + where + " names " + m.group() + ", outside " + EMAIL_DOMAIN);
            }
        }
        return findings;
    }

    /** Rule (c): the first phone number in the text, or null */
    static String phone(String text) {
        for (Pattern pattern : List.of(PHONE_INTERNATIONAL, PHONE_GROUPED, PHONE_PLAIN, PHONE_LABELLED)) {
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                return m.group();
            }
        }
        return null;
    }

    /**
     * The SQL's statements, split at the semicolons outside strings, dollar quotes and comments, with the comments
     * left out. A backslash outside them starts a psql meta-command, a statement of its own to the end of its line.
     */
    static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
                current.append('\n');
            } else if (c == '\'') {
                int end = i + 1;
                while (end < n) {
                    if (sql.charAt(end) == '\'') {
                        if (end + 1 < n && sql.charAt(end + 1) == '\'') {
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    end++;
                }
                current.append(sql, i, Math.min(end + 1, n));
                i = end + 1;
            } else if (c == '$') {
                int tagEnd = sql.indexOf('$', i + 1);
                String tag = tagEnd < 0 ? null : sql.substring(i, tagEnd + 1);
                if (tag != null && tag.matches("\\$[A-Za-z_]*\\$")) {
                    int end = sql.indexOf(tag, tagEnd + 1);
                    end = end < 0 ? n : end + tag.length();
                    current.append(sql, i, end);
                    i = end;
                } else {
                    current.append(c);
                    i++;
                }
            } else if (c == '\\') {
                int end = sql.indexOf('\n', i);
                end = end < 0 ? n : end;
                statements.add(sql.substring(i, end).strip());
                i = end;
            } else if (c == ';') {
                addStatement(statements, current);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    /** The column names of an INSERT, unquoted */
    static List<String> splitColumns(String columns) {
        List<String> names = new ArrayList<>();
        for (String column : columns.split(",")) {
            names.add(unquote(column.strip()));
        }
        return names;
    }

    /** Whether the parentheses of an INSERT's values, outside its literals, never close more than they open */
    static boolean balanced(String values) {
        int depth = 0;
        String outside = values.replaceAll(LITERAL, "''");
        for (char c : outside.toCharArray()) {
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    /** The values of an INSERT, as written: a literal keeps its quotes */
    static List<String> splitValues(String values) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        int i = 0;
        int n = values.length();
        while (i < n) {
            char c = values.charAt(i);
            if (c == '\'') {
                int end = i + 1;
                while (end < n) {
                    if (values.charAt(end) == '\'') {
                        if (end + 1 < n && values.charAt(end + 1) == '\'') {
                            end += 2;
                            continue;
                        }
                        break;
                    }
                    end++;
                }
                current.append(values, i, Math.min(end + 1, n));
                i = end + 1;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(current.toString().strip());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        result.add(current.toString().strip());
        return result;
    }

    /** The text of a literal such as {@code 'it''s'} or {@code 'x'::text} */
    static String unquoteLiteral(String literal) {
        int end = literal.lastIndexOf('\'');
        return literal.substring(1, Math.max(end, 1)).replace("''", "'");
    }

    static String unquote(String name) {
        return name.startsWith("\"") && name.endsWith("\"") ? name.substring(1, name.length() - 1) : name;
    }

    static String abbreviate(String text) {
        String line = text.replaceAll("\\s+", " ");
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    static boolean isPng(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /** The chunk types of a PNG, in order, or null when a chunk runs past the end or bytes are left over */
    static List<String> pngChunks(byte[] png) {
        List<String> chunks = new ArrayList<>();
        long i = PNG_SIGNATURE.length;
        while (i < png.length) {
            long remaining = png.length - i;
            if (remaining < 12) {
                return null;
            }
            int at = (int) i;
            long length = ((png[at] & 0xffL) << 24) | ((png[at + 1] & 0xffL) << 16) | ((png[at + 2] & 0xffL) << 8)
                    | (png[at + 3] & 0xffL);
            if (length > remaining - 12) {
                return null;
            }
            chunks.add(new String(png, at + 4, 4, StandardCharsets.ISO_8859_1));
            i += 12 + length;
        }
        return chunks;
    }
}
