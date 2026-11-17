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
package org.niord.web.sweep;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds every entry of the endpoint table to its format: known kinds (and a kind that fits the verb), rules,
 * identities and exemptions; a refusal with its detail (a 404 always with one; never a 405 or 415); fixture rows the
 * group declares; cases its {@link SweepGroup} registers; covering test methods that exist; known faults whose code is
 * in a record and whose cell is one the entry runs.
 *
 * <p>A reviewed entry is also held to decisions that assert something: a read names what its answer must hold
 * (rows or a size) or why it is empty; each rule has the cells it implies; a code-checked handler has a cell the
 * annotation does not decide; an expected log line names a message or an exception, not a level or a package; an
 * exempt entry still runs refused cells; a covering test is a test method.
 *
 * <p>Every entry is decided: the generator's skeleton of a new handler ({@code "case": "pending"}, {@code reviewed:
 * false}) fails until a person decides it. A known fault names an open record: a code ({@code MSG9}) is the heading
 * of an entry of {@code be/docs/potential-errors.md} that is not marked fixed, anything else is a phrase (at least
 * {@value #MIN_BACKLOG_PHRASE} characters) of {@code fe/docs/backlog.md} before its "Deliberately not built" section.
 */
class EndpointTableFormatTest {

    /** The records a known fault's code must be in (relative to the module) */
    static final Path POTENTIAL_ERRORS = Path.of("..", "docs", "potential-errors.md");
    static final Path BACKLOG = Path.of("..", "..", "fe", "docs", "backlog.md");

    /** The shortest backlog phrase a known fault may name: long enough to pick out one entry */
    static final int MIN_BACKLOG_PHRASE = 20;

    /** A known fault's code that names a record of potential-errors.md rather than a backlog phrase */
    private static final Pattern RECORD_CODE = Pattern.compile("[A-Z][A-Z0-9-]*[0-9]+");

    /** The backlog's section of what is deliberately not built: no open fault is recorded there */
    private static final Pattern BACKLOG_NOT_BUILT = Pattern.compile("^## [0-9]+\\. Deliberately not built",
            Pattern.MULTILINE);

    /** The mark of a potential-errors.md heading whose fault is fixed: {@code *(LOW · FIXED)*} */
    private static final Pattern FIXED_MARK = Pattern.compile("\\*\\([^)]*\\bFIXED\\b");

    /** The class that sweeps the entries of a context other than the main one */
    static final Map<String, String> CONTEXT_CLASSES = Map.of("secom", "org.niord.secom.SecomEndpointSweepITTest");

    private static final Pattern RECORD_HEADING = Pattern.compile("^## ([A-Z][A-Z0-9-]*[0-9]+)[.\\s](.*)$",
            Pattern.MULTILINE);

    /** An expected log line that would allow every line: a level, or a package name */
    private static final Pattern TOO_WIDE_LOG = Pattern.compile(
            "^\\s*(ERROR|WARN|INFO|DEBUG|TRACE)\\b.*|^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*\\.?$");

    /** The identities a domain rule's cells may use */
    private static final Set<String> DOMAIN_IDENTITIES = Set.of("userOtherDomain", "adminElsewhere", "domainEditor");

    @Test
    void everyEntryHasTheTablesFormat() throws IOException {
        EndpointTable table = EndpointTable.load();
        Map<String, SweepGroup> groups = groups();
        Set<String> codes = recordCodes();
        String backlog = openBacklog(Files.isRegularFile(BACKLOG) ? Files.readString(BACKLOG, StandardCharsets.UTF_8)
                : "");

        List<String> problems = new ArrayList<>();
        Set<String> contexts = new TreeSet<>();
        for (EndpointEntry entry : table.entries()) {
            String file = table.groupOf(entry);
            SweepGroup group = groups.get(file + "/" + entry.context);
            problems.addAll(problems(entry, file, group, codes, backlog));
            if (entry.context != null && !entry.isPending()) {
                contexts.add(entry.context);
            }
        }
        for (String context : contexts) {
            String sweepClass = CONTEXT_CLASSES.get(context);
            if (!classExists(sweepClass)) {
                problems.add("Entries of the context " + context + " are decided, but no class sweeps them: "
                        + sweepClass + " (it returns SweepRun.nodes(..., \"" + context + "\"))");
            }
        }
        for (EndpointTable.BackgroundFault fault : table.backgroundKnownFaults()) {
            if (!codeExists(fault.code, codes, backlog)) {
                problems.add("The background fault " + fault.code + " is in no open record");
            }
        }
        assertTrue(table.entries().size() > 300, "sanity: the table holds " + table.entries().size() + " entries");
        assertTrue(codes.size() > 50, "sanity: be/docs/potential-errors.md holds " + codes.size() + " open codes");
        assertEquals(List.of(), problems, "Entries of the endpoint table break its format");
    }

    @Test
    void aKnownFaultNamesAnOpenRecordAndNothingElse() {
        Set<String> codes = recordCodes("""
                # Potential errors
                ## AB1. An open fault
                ## AB2 — A fixed one *(LOW · FIXED)*
                ## AB3 — A mostly closed one *(now LOW · MOSTLY CLOSED)*
                ## AB-NOTE. A note without a number
                """);
        assertEquals(Set.of("AB1", "AB3"), codes, "the open codes, a fixed heading left out");
        String backlog = openBacklog("""
                # Backlog
                ## 2. Maintenance backlog
                - **The picker offers an action the backend refuses.** More text.
                ## 3. Deliberately not built
                - **A printed manual for every domain.** Not planned.
                """);

        assertTrue(codeExists("AB1", codes, backlog));
        assertTrue(codeExists("The picker offers an action the backend refuses", codes, backlog));
        assertFalse(codeExists("AB2", codes, backlog), "a fixed record is no open record");
        assertFalse(codeExists("AB9", codes, backlog), "a code no heading names");
        assertFalse(codeExists("intake:messages-1", codes, backlog), "an intake id is no record");
        assertFalse(codeExists("Backlog", codes, backlog), "a code-less word is no record");
        assertFalse(codeExists("The picker offers", codes, backlog), "a phrase too short to pick out one entry");
        assertFalse(codeExists("A printed manual for every domain", codes, backlog),
                "what is deliberately not built is no open fault");

        EndpointEntry entry = new EndpointEntry();
        entry.key = "GET /rest/things/thing/{id}";
        entry.handler = "ThingRestService.getThing";
        entry.kind = "read";
        entry.guard = "PermitAll";
        entry.codeCheck = "none-found";
        entry.coveredBy = "EndpointTableFormatTest#aKnownFaultNamesAnOpenRecordAndNothingElse";
        entry.reviewed = true;
        EndpointEntry.KnownFault fault = new EndpointEntry.KnownFault();
        fault.code = "AB9";
        fault.cell = "*";
        fault.answer = 500;
        entry.knownFault.add(fault);
        assertTrue(problems(entry, "things", null, codes, backlog).stream()
                        .anyMatch(p -> p.contains("the known fault AB9 is in no open record")),
                "a known fault of no open record is a problem of the entry: "
                        + problems(entry, "things", null, codes, backlog));
    }

    static Map<String, SweepGroup> groups() {
        Map<String, SweepGroup> groups = new HashMap<>();
        for (SweepGroup group : SweepRun.discover()) {
            groups.put(group.name() + "/" + group.context(), group);
        }
        return groups;
    }

    static List<String> problems(EndpointEntry e, String file, SweepGroup group, Set<String> codes, String backlog) {
        List<String> p = new ArrayList<>();
        String at = file + ".json " + e.key + ": ";
        if (!EndpointEntry.KINDS.contains(e.kind)) {
            p.add(at + "kind " + e.kind + " is not one of " + new TreeSet<>(EndpointEntry.KINDS));
        } else if (e.key != null && e.key.contains(" ") && !kindFitsVerb(e.kind, e.verb())) {
            p.add(at + "kind " + e.kind + " does not fit the verb " + e.verb() + " (GET: read or writeByGet; "
                    + "DELETE: write or global; POST and PUT: compute, write, upload, global or globalIdempotent)");
        }
        if (!EndpointEntry.CODE_CHECKS.contains(e.codeCheck)) {
            p.add(at + "codeCheck " + e.codeCheck + " is not one of " + EndpointEntry.CODE_CHECKS);
        }
        if (e.guard == null || e.handler == null || e.params == null) {
            p.add(at + "guard, handler and params are generated and present in every entry");
            return p;
        }
        if (!(e.params instanceof List<?>) && !"*".equals(e.params)) {
            p.add(at + "params is a list of names or \"*\", not " + e.params);
        }
        if (e.context != null && !CONTEXT_CLASSES.containsKey(e.context)) {
            p.add(at + "context " + e.context + " is not one of " + CONTEXT_CLASSES.keySet());
        }
        for (String rule : e.rules) {
            if (!EndpointEntry.RULES.contains(rule)) {
                p.add(at + "rule " + rule + " is not one of " + EndpointEntry.RULES);
            }
        }
        if (e.exempt != null && (!EndpointEntry.Exempt.KINDS.contains(e.exempt.kind) || blank(e.exempt.reason)
                || blank(e.exempt.asserted))) {
            p.add(at + "an exemption names its kind " + EndpointEntry.Exempt.KINDS + ", its reason and what is "
                    + "still asserted");
        }
        if (e.realmRole != null && !e.realmRole.equals("manage-users")) {
            p.add(at + "realmRole " + e.realmRole + " is not a Keycloak role a handler forwards to (manage-users)");
        }
        if (e.settle && e.isRead()) {
            p.add(at + "a " + e.kind + " does not settle: settle is for a write that queues mail or changes a status");
        }
        e.derived.forEach((field, reason) -> {
            if (blank(field) || blank(reason)) {
                p.add(at + "derived names a field and the reason its read-back differs: " + field + " = " + reason);
            }
        });
        for (String line : e.expectedLog) {
            if (line == null || line.strip().length() < 12 || TOO_WIDE_LOG.matcher(line).matches()) {
                p.add(at + "expectedLog \"" + line + "\" would allow more than one line: name the message or the "
                        + "exception (at least 12 characters, not a level and not a package)");
            }
        }

        // Cells
        List<String> cellNames = new ArrayList<>(e.answers.keySet());
        e.answers.forEach((name, cell) -> {
            int sep = name.indexOf('@');
            String identity = sep < 0 ? name : name.substring(0, sep);
            try {
                SweepIdentity.parse(identity);
            } catch (IllegalArgumentException ex) {
                p.add(at + "cell " + name + ": " + ex.getMessage());
            }
            if (sep >= 0 && group != null && !group.rows().contains(name.substring(sep + 1))) {
                p.add(at + "cell " + name + " names a row " + group.getClass().getSimpleName() + " does not declare");
            }
            if (!cell.isOk() && (cell.status() == 405 || cell.status() == 415)) {
                p.add(at + "cell " + name + " expects " + cell.status() + ", which is never an accepted answer");
            }
            if (!cell.isOk() && cell.status() == 404 && (!cell.detailGiven() || cell.detail() == null)) {
                p.add(at + "cell " + name + " expects 404 without its detail: a 404 is accepted only with the "
                        + "handler's detail sentence");
            }
            if (e.reviewed && cell.isRefusal() && !cell.detailGiven()) {
                p.add(at + "cell " + name + " is a refusal without \"detail\" (write \"detail\": null for an answer "
                        + "without one)");
            }
        });
        boolean decided = e.reviewed && !e.isPending();
        boolean swept = e.caseName != null && !e.isPending() && e.exempt == null && !SweepEngine.refusedOnly(e);
        if (decided && "found".equals(e.codeCheck) && e.exempt == null && e.caseName != null) {
            if (e.answers.isEmpty()) {
                p.add(at + "the handler has a code check, so its cells are decided in answers");
            } else if (e.guard.startsWith("RolesAllowed") && cellNames.stream()
                    .allMatch(n -> n.equals("anonymous") || n.equals("anonymous+header"))) {
                p.add(at + "the handler has a code check, but its cells are only anonymous ones, which the "
                        + "annotation decides: write the cells of the identities the code checks");
            }
        }
        if (decided) {
            if (e.rules.contains("owner") && cellNames.stream().noneMatch(n -> n.contains("@"))) {
                p.add(at + "rule owner asks for a cell on a row of another owner (identity@row)");
            }
            if (e.rules.contains("domain") && cellNames.stream().noneMatch(n -> n.contains("@")
                    || DOMAIN_IDENTITIES.contains(n))) {
                p.add(at + "rule domain asks for a cell of another domain (" + DOMAIN_IDENTITIES
                        + ", or identity@row of another domain's row)");
            }
            if (e.rules.contains("ticket") && cellNames.stream().noneMatch(n -> n.startsWith("ticket("))) {
                p.add(at + "rule ticket asks for a ticket cell (ticket(<identity>) or ticket(<identity>:<role>))");
            }
            if (e.realmRole != null && (!cellNames.contains("sysadmin") || cellNames.stream().noneMatch(n ->
                    Set.of("admin", "editor", "user", "domainEditor", "adminElsewhere", "curator").contains(n)))) {
                p.add(at + "realmRole asks for the cell of an identity that holds it (sysadmin) and of one that does "
                        + "not (admin, editor, user, ...)");
            }
        }

        // Case, coveredBy, expect
        SweepCase sweepCase = null;
        if (e.isPending()) {
            p.add(at + "the case is still pending: decide the entry (swept with a case of the group's SweepGroup, "
                    + "global, exempt or coveredBy) and write its case");
        } else if (e.caseName != null) {
            String expected = group == null ? null : group.getClass().getSimpleName() + "#";
            if (group == null) {
                p.add(at + "names the case " + e.caseName + ", but no SweepGroup is named " + file);
            } else if (!e.caseName.startsWith(expected)) {
                p.add(at + "the case " + e.caseName + " is not of " + group.getClass().getSimpleName());
            } else {
                sweepCase = SweepCases.of(group).all().get(e.caseName.substring(expected.length()));
                if (sweepCase == null) {
                    p.add(at + "the case " + e.caseName + " is not registered by " + group.getClass().getSimpleName());
                }
            }
        }
        if (e.caseName == null && e.coveredBy == null && e.exempt == null) {
            p.add(at + "has no case, no coveredBy and no exempt");
        }
        List<String> defaultCells = SweepEngine.forRules(false).cells(e).stream().map(SweepEngine.CellRun::name)
                .toList();
        if (e.caseName == null && (e.coveredBy != null || e.exempt != null) && !defaultCells.isEmpty()) {
            p.add(at + "runs the cells " + defaultCells + ", which need the request a case makes: coveredBy and "
                    + "exempt skip the round trip, not the cells");
        }
        if (e.coveredBy != null) {
            String problem = coveredProblem(e.coveredBy, decided);
            if (problem != null) {
                p.add(at + "coveredBy " + e.coveredBy + " " + problem);
            }
            if (sweepCase != null && sweepCase.sweep != null) {
                p.add(at + "has coveredBy and a case with a sweep step: the sweep step is not run for a covered "
                        + "entry; keep one of the two");
            }
        }
        if (decided && e.exempt != null && (e.caseName == null || defaultCells.isEmpty())) {
            p.add(at + "an exempt entry still runs its refused cells (what it asserts): it needs a case and at least "
                    + "one refused cell");
        }
        if (e.expect != null) {
            for (String row : e.expect.rows) {
                if (group == null || !group.rows().contains(row)) {
                    p.add(at + "expect names the row " + row + ", which the group's fixture does not declare");
                }
            }
            if (e.expect.rows.isEmpty() && (e.expect.minBytes == null || e.expect.minBytes <= 0)) {
                p.add(at + "expect names neither rows nor a minimum size, so any answer holds it (a content type "
                        + "alone does not say the answer has content)");
            }
            if (!e.isRead()) {
                p.add(at + "expect is read for reads and computations; a write's case reads back what it wrote");
            }
        }
        if (decided && swept && e.isRead() && e.coveredBy == null && e.expect == null && blank(e.emptyByDesign)) {
            p.add(at + "a reviewed read names what its answer must hold (expect) or why it has nothing to show "
                    + "(emptyByDesign)");
        }
        if (!e.reviewed) {
            p.add(at + "is not reviewed: decide its cells, then set reviewed to true");
        }

        // Known faults
        for (EndpointEntry.KnownFault fault : e.knownFault) {
            if (!codeExists(fault.code, codes, backlog)) {
                p.add(at + "the known fault " + fault.code + " is in no open record (the code of an open entry of "
                        + "be/docs/potential-errors.md, or a phrase of an open entry of fe/docs/backlog.md)");
            }
            if (fault.check != null) {
                if (fault.answer != null || fault.log != null || !"*".equals(fault.cell) || fault.check.isBlank()) {
                    p.add(at + "the known fault " + fault.code + " names a check: its cell is \"*\", its check a "
                            + "text of the case's check, and it names no answer and no log line");
                }
                continue;
            }
            if (fault.answer == null && blank(fault.log)) {
                p.add(at + "the known fault " + fault.code + " names neither the answer, the log line nor the check "
                        + "it gives");
            }
            if (fault.cell == null) {
                p.add(at + "the known fault " + fault.code + " names no cell (a cell name, an identity, a step "
                        + SweepEngine.STEPS + ", or \"*\")");
            } else if (!faultCellExists(e, fault.cell, defaultCells)) {
                p.add(at + "the known fault " + fault.code + " names the cell " + fault.cell + ", which is no cell "
                        + "of the entry, no identity and no step " + SweepEngine.STEPS + ": it would never apply");
            }
        }
        return p;
    }

    private static boolean kindFitsVerb(String kind, String verb) {
        return switch (verb) {
            case "GET" -> kind.equals("read") || kind.equals("writeByGet");
            case "DELETE" -> kind.equals("write") || kind.equals("global");
            default -> !kind.equals("read") && !kind.equals("writeByGet");
        };
    }

    /** Whether a known fault's cell names something a call of the entry can be */
    private static boolean faultCellExists(EndpointEntry e, String cell, List<String> defaultCells) {
        if (cell.equals("*") || SweepEngine.STEPS.contains(cell) || e.answers.containsKey(cell)
                || defaultCells.contains(cell) || SweepEngine.derivedIdentities(e).contains(cell)) {
            return true;
        }
        if (cell.contains("@")) {
            return false;
        }
        try {
            SweepIdentity.parse(cell);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Whether a known fault's code names an open record: a code ({@code MSG9}) one of {@code codes}, anything else a
     * phrase of at least {@value #MIN_BACKLOG_PHRASE} characters of the open backlog ({@link #openBacklog})
     */
    static boolean codeExists(String code, Set<String> codes, String backlog) {
        if (code == null || code.isBlank()) {
            return false;
        }
        if (RECORD_CODE.matcher(code).matches()) {
            return codes.contains(code);
        }
        return code.strip().length() >= MIN_BACKLOG_PHRASE && backlog.contains(code);
    }

    /** The codes of the open records of {@code be/docs/potential-errors.md} */
    static Set<String> recordCodes() throws IOException {
        return Files.isRegularFile(POTENTIAL_ERRORS)
                ? recordCodes(Files.readString(POTENTIAL_ERRORS, StandardCharsets.UTF_8)) : new TreeSet<>();
    }

    /** The codes of the {@code ## CODE. title} headings of a potential-errors text, but those marked fixed */
    static Set<String> recordCodes(String text) {
        Set<String> codes = new TreeSet<>();
        Matcher m = RECORD_HEADING.matcher(text);
        while (m.find()) {
            if (!FIXED_MARK.matcher(m.group(2)).find()) {
                codes.add(m.group(1));
            }
        }
        return codes;
    }

    /** The backlog's text before its section of what is deliberately not built */
    static String openBacklog(String text) {
        Matcher m = BACKLOG_NOT_BUILT.matcher(text);
        return m.find() ? text.substring(0, m.start()) : text;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, EndpointTableFormatTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Null when {@code "SimpleClassName#method"} names a JUnit test method of a test class under org.niord (a class
     * name alone is accepted only before the entry is reviewed, and only for a test class); else what is wrong
     */
    static String coveredProblem(String coveredBy, boolean reviewed) {
        String[] parts = coveredBy.split("#", 2);
        if (parts.length == 1 && reviewed) {
            return "names a class without a method: name the test method that owns the round trip (Class#method)";
        }
        List<Class<?>> classes = new ArrayList<>();
        try {
            Resource[] found = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:org/niord/**/" + parts[0] + ".class");
            for (Resource resource : found) {
                String path = resource.getURL().toString();
                String className = path.substring(path.indexOf("org/niord/"), path.length() - ".class".length())
                        .replace('/', '.');
                classes.add(Class.forName(className, false, EndpointTableFormatTest.class.getClassLoader()));
            }
        } catch (IOException | ClassNotFoundException | LinkageError e) {
            return "cannot be read: " + e;
        }
        if (classes.isEmpty()) {
            return "names no class under org.niord";
        }
        for (Class<?> type : classes) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    if (isTestMethod(method) && (parts.length == 1 || method.getName().equals(parts[1]))) {
                        return null;
                    }
                }
            }
        }
        return parts.length == 1 ? "names no test class (a class with a JUnit test method)"
                : "names no JUnit test method (@Test, @ParameterizedTest, @TestFactory, ...) of a test class";
    }

    private static boolean isTestMethod(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.startsWith("org.junit.jupiter.") && (name.endsWith(".Test") || name.endsWith("Test")
                    || name.endsWith(".TestFactory") || name.endsWith(".TestTemplate"))) {
                return true;
            }
        }
        return false;
    }
}
