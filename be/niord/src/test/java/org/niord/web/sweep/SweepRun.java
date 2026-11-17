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

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * One run of the endpoint sweep, as the dynamic tests of a {@code @TestFactory}: every entry of one context of the
 * endpoint table ({@code src/test/resources/endpoints/*.json}) called over HTTP as the table says. The main sweep is
 * {@code EndpointSweepITTest} (context null, the shared integration-test context); a class that extends another
 * context's base (the SECOM administration interface, context {@code "secom"}) returns
 * {@code SweepRun.nodes(port, applicationContext, ITTest::accessToken, "secom")} from its own factory, and calls
 * {@link #detachTheLog()} after its class.
 *
 * <p>It finds every {@link SweepGroup} of this package of the context by a classpath scan and runs, in this fixed order: every
 * group's fixture; the reads (and computations) of every group; the writes and uploads; the global entries (refused
 * cells only) and last the idempotent ones; the settle point (the sweep's mail out of the queue, the background
 * quiet); every group's tear-down, in the reverse order of the fixtures; the compaction of the groups' tables; and
 * the summaries (identities missing, rows left behind, entries by treatment, entries without a case, the timing, and the background errors of the run, with
 * the work of a sweep request logged after its entry's test had ended). One dynamic test per entry, named by its key, lists every
 * mismatch of the entry with the failure text of {@link SweepFailure}.
 *
 * <ul>
 *   <li>{@code -Dniord.sweep.groups=sources,messages} (or the environment variable {@code NIORD_SWEEP_GROUPS}) runs
 *       only those groups;</li>
 *   <li>{@code -Dniord.sweep.full=true} (or {@code NIORD_SWEEP_FULL=true}) adds the probes, the variants and every
 *       role cell the annotation decides; the default run, which CI pays for, leaves them out.</li>
 * </ul>
 *
 * <p>Every row a run makes is named {@code sweep-<group>-...} and removed when it can be (see the endpoint sweep in
 * docs/test-infrastructure.md).
 */
public final class SweepRun {

    public static final String GROUPS_PROPERTY = "niord.sweep.groups";
    public static final String FULL_PROPERTY = "niord.sweep.full";

    /** The appender of the run, detached after the class even if the run stopped half-way */
    private static SweepLog runLog;

    private SweepRun() {
    }

    /** Detaches the run's log appender; the sweep's class calls it after the class, in case the run stopped */
    public static void detachTheLog() {
        if (runLog != null) {
            runLog.detach();
            runLog = null;
        }
    }

    /** One group in the run */
    private static final class GroupRun {
        final SweepGroup group;
        final SweepContext ctx;
        final Map<String, SweepCase> cases;
        final List<EndpointEntry> entries;
        boolean broken;
        final List<String> leftBehind = new ArrayList<>();

        GroupRun(SweepGroup group, SweepContext ctx, List<EndpointEntry> entries) {
            this.group = group;
            this.ctx = ctx;
            this.cases = SweepCases.of(group).all();
            this.entries = entries;
        }
    }

    /**
     * The dynamic tests of a sweep run: every group of the context (null for the main sweep) and its entries of that
     * context, in the fixed order.
     *
     * @param port   the application's port
     * @param app    the application context the test runs in
     * @param tokens a bearer token for a realm identity's token role ({@code ITTest::accessToken}), fetched per request
     * @param context null for the main sweep, {@code "secom"} for the SECOM administration interface
     */
    public static Stream<DynamicNode> nodes(int port, ApplicationContext app, Function<String, String> tokens,
                                            String context) {
        long factoryStarted = System.currentTimeMillis();
        EndpointTable table = EndpointTable.load();
        Set<String> selected = selectedGroups();
        boolean full = Boolean.parseBoolean(setting(FULL_PROPERTY, "NIORD_SWEEP_FULL"));
        List<SweepGroup> groups = discover(context).stream()
                .filter(g -> selected.isEmpty() || selected.contains(g.name()))
                .sorted(Comparator.comparing(SweepGroup::name))
                .toList();

        runLog = SweepLog.attach();
        SweepEngine engine = new SweepEngine(port, app, tokens, table, runLog, full);
        long[] started = {0};
        List<GroupRun> runs = new ArrayList<>();
        for (SweepGroup group : groups) {
            EndpointTable.TableFile file = table.files().get(group.name());
            runs.add(new GroupRun(group, new SweepContext(engine, group),
                    file == null ? List.of() : file.entries.stream().filter(e -> Objects.equals(e.context, context)).toList()));
        }

        long factoryMillis = System.currentTimeMillis() - factoryStarted;
        List<DynamicNode> nodes = new ArrayList<>();
        nodes.add(dynamicTest("the run's settings", () -> {
            started[0] = System.currentTimeMillis();
            System.out.println("Endpoint sweep: groups " + groups.stream().map(SweepGroup::name).toList()
                    + (selected.isEmpty() ? " (all)" : " (selected by " + GROUPS_PROPERTY + "=" + selected + ")")
                    + ", " + (full ? "full run" : "default run") + "; missing identities: "
                    + engine.missingIdentities());
            List<String> problems = new ArrayList<>();
            if (SweepIdentity.ADDED_IN_REALM) {
                for (String added : SweepIdentity.ADDED) {
                    try {
                        tokens.apply(added);
                    } catch (RuntimeException | AssertionError e) {
                        problems.add("The identity " + added + " is in the test realm (SweepIdentity.ADDED_IN_REALM), "
                                + "but its token cannot be fetched: " + e);
                    }
                }
            }
            for (String name : selected) {
                if (groups.stream().noneMatch(g -> g.name().equals(name))) {
                    problems.add("no SweepGroup is named " + name);
                }
            }
            for (SweepGroup group : groups) {
                if (!table.files().containsKey(group.name())) {
                    problems.add(group.getClass().getSimpleName() + " has no table file endpoints/" + group.name()
                            + ".json");
                }
            }
            if (!problems.isEmpty()) {
                fail(String.join("\n", problems));
            }
        }));

        nodes.add(dynamicContainer("fixtures", runs.stream().map(run -> dynamicTest(run.group.name(), () -> {
            try {
                engine.inScope("the fixture of " + run.group.name(), null, () -> run.group.setUp(run.ctx));
            } catch (AssertionError e) {
                run.broken = true;
                throw e;
            }
        }))));

        nodes.add(phase("reads", runs, engine, e -> e.isRead() && !e.isGlobal()));
        nodes.add(phase("writes", runs, engine, e -> !e.isRead() && !e.isGlobal()));
        nodes.add(phase("globals", runs, engine, e -> "global".equals(e.kind)));
        nodes.add(phase("globals, idempotent, last", runs, engine, e -> "globalIdempotent".equals(e.kind)));

        nodes.add(dynamicTest("settle", () -> {
            if (!runs.isEmpty()) {
                engine.inScope("the settle point", null, () -> engine.settleAll(runs.getFirst().group));
            }
        }));

        nodes.add(dynamicContainer("tear-down", tearDownOrder(runs).stream().map(run -> dynamicTest(
                run.group.name(), () ->
                engine.inScope("the tear-down of " + run.group.name(), null,
                        () -> run.leftBehind.addAll(run.group.tearDown(run.ctx)))))));

        nodes.add(dynamicTest("compact the groups' tables", () -> compact(app, runs)));

        nodes.add(dynamicContainer("summaries", Stream.of(
                dynamicTest("identities missing", () -> {
                    if (!engine.identityMissingCells().isEmpty()) {
                        String cells = "cells not run, their identity is not in the test realm ("
                                + engine.identityMissingCells().size() + "):\n  "
                                + String.join("\n  ", engine.identityMissingCells());
                        System.out.println("Endpoint sweep: " + cells);
                        if (SweepIdentity.ADDED_IN_REALM) {
                            fail("The added identities are in the test realm (SweepIdentity.ADDED_IN_REALM), yet "
                                    + cells);
                        }
                    }
                }),
                dynamicTest("rows left behind", () -> {
                    for (GroupRun run : runs) {
                        run.group.leftByDesign().forEach((what, why) -> System.out.println("Endpoint sweep: "
                                + run.group.name() + " leaves by design: " + what + " (" + why + ")"));
                    }
                    List<String> left = runs.stream().flatMap(r -> r.leftBehind.stream()).toList();
                    if (!left.isEmpty()) {
                        fail("Rows the tear-downs could not remove, by name: " + left + ". Remove them by hand; a row "
                                + "that cannot be removed by design belongs in the group's leftByDesign and record.");
                    }
                }),
                dynamicTest("entries by treatment", () -> System.out.println("Endpoint sweep: entries by treatment: "
                        + treatments(runs))),
                dynamicTest("entries without a case", () -> {
                    Map<String, Long> pending = table.entries().stream().filter(EndpointEntry::isPending)
                            .collect(Collectors.groupingBy(table::groupOf, java.util.TreeMap::new, Collectors.counting()));
                    if (!pending.isEmpty()) {
                        fail("Entries are still pending (the generator's skeleton, not swept), by file: " + pending
                                + ". Decide each and write its case.");
                    }
                }),
                dynamicTest("timing", () -> writeTiming(engine, runs, started[0], factoryMillis)),
                dynamicTest("background errors during the sweep", () -> {
                    try {
                        List<String> problems = backgroundProblems(engine, table, started[0]);
                        if (!problems.isEmpty()) {
                            fail(String.join("\n\n", problems));
                        }
                    } finally {
                        detachTheLog();
                    }
                }))));
        return nodes.stream();
    }

    /** One phase: per group a container, per entry of the phase a test */
    private static DynamicContainer phase(String name, List<GroupRun> runs, SweepEngine engine,
                                          java.util.function.Predicate<EndpointEntry> inPhase) {
        return dynamicContainer(name, runs.stream().map(run -> dynamicContainer(run.group.name(),
                run.entries.stream().filter(inPhase).filter(e -> !e.isPending())
                        .map(entry -> entryTest(run, engine, entry)))));
    }

    private static DynamicTest entryTest(GroupRun run, SweepEngine engine, EndpointEntry entry) {
        return dynamicTest(entry.key, () -> {
            if (entry.caseName == null) {
                if (entry.coveredBy == null && entry.exempt == null) {
                    fail(entry + " has no case, no coveredBy and no exempt");
                }
                if (!engine.cells(entry).isEmpty()) {
                    fail(entry + " runs cells (" + engine.cells(entry).stream().map(SweepEngine.CellRun::name).toList()
                            + "), which need the request a case makes: coveredBy and exempt skip the round trip, "
                            + "not the cells");
                }
                return;
            }
            if (run.broken) {
                fail("The fixture of the group " + run.group.name() + " failed (see its test); " + entry
                        + " is not swept");
            }
            String prefix = run.group.getClass().getSimpleName() + "#";
            if (!entry.caseName.startsWith(prefix)) {
                fail(entry + " names the case " + entry.caseName + ", which is not of " + prefix);
            }
            SweepCase sweepCase = run.cases.get(entry.caseName.substring(prefix.length()));
            if (sweepCase == null) {
                fail(entry + " names the case " + entry.caseName + ", which "
                        + run.group.getClass().getSimpleName() + " does not register");
            }
            engine.inScope(entry.key, entry, () -> engine.runEntry(run.group, run.ctx, entry, sweepCase));
        });
    }

    /**
     * The background ERROR lines another application context of the IT JVM writes once it is closed, which the root
     * logger the sweep listens on also receives: logger, then the start of the message. Each is one the context the
     * sweep runs in cannot write while it runs, so it is not held to the rule.
     * <ul>
     *   <li>{@code MessageLuceneIndex.startUpPass}, five seconds after a context's start, looks its own bean up
     *   through the context; a context closed within those five seconds (a class with a context of its own,
     *   be/docs/test-infrastructure.md, "The Lucene index folder") no longer has the beans, and the pass logs that.
     *   The running context has the bean, so the line names a closed one (seen in the whole IT suite at the merge
     *   of the lanes, on a {@code Timer-n} thread during the SECOM sweep, and in earlier whole runs' logs).</li>
     * </ul>
     */
    static final List<List<String>> CLOSED_CONTEXT_LINES = List.of(
            List.of("org.niord.core.message.MessageLuceneIndex",
                    "Error updating Lucene index: Error creating bean with name 'messageLuceneIndex'"));

    /** Whether a log event is one a closed application context of the IT JVM writes ({@link #CLOSED_CONTEXT_LINES}) */
    static boolean ofAClosedContext(SweepLog.Event event) {
        return CLOSED_CONTEXT_LINES.stream().anyMatch(line -> line.get(0).equals(event.logger())
                && event.message() != null && event.message().startsWith(line.get(1)));
    }

    /**
     * The ERROR lines no test held to the rule: a sweep request's work logged after its entry's test had ended, and
     * background work outside every upload and settle window that no table file lists as known; the lines of a
     * closed application context ({@link #ofAClosedContext}) are not the sweep's
     */
    static List<String> backgroundProblems(SweepEngine engine, EndpointTable table, long from) {
        List<String> unknown = new ArrayList<>();
        List<String> late = new ArrayList<>();
        for (SweepLog.Event event : engine.unattributedErrors(from)) {
            String owner = engine.ownerOf(event);
            if (owner != null) {
                late.add(event.describe() + "\n      (work of a request of " + owner
                        + ", logged after its test had ended)");
                continue;
            }
            if (ofAClosedContext(event)) {
                continue;
            }
            boolean known = table.backgroundKnownFaults().stream().anyMatch(f ->
                    (f.logger == null || event.logger().contains(f.logger))
                            && (f.message == null || event.describe().contains(f.message)));
            if (!known) {
                unknown.add(event.describe());
            }
        }
        List<String> problems = new ArrayList<>();
        if (!late.isEmpty()) {
            problems.add(late.size() + " ERROR line(s) of a sweep request's work, logged after its entry's test had "
                    + "ended (an @Async task, a listener); each is a fault of that entry to record, or a known one in "
                    + "its knownFault (log) or expectedLog:\n  " + String.join("\n  ", late));
        }
        if (!unknown.isEmpty()) {
            problems.add(unknown.size() + " ERROR line(s) of background work (no sweep request, no upload or settle "
                    + "window) during the sweep; each is a fault to record, or a known one to list in a table file's "
                    + "backgroundKnownFaults:\n  " + String.join("\n  ", unknown));
        }
        return problems;
    }

    /**
     * Compacts the tables the groups name ({@link SweepGroup#tables()}) after their tear-downs, so that the free space
     * of the sweep's deleted rows does not reorder rows the tests after it make and update there
     */
    private static void compact(ApplicationContext app, List<GroupRun> runs) throws SQLException {
        Set<String> tables = new TreeSet<>();
        runs.forEach(r -> tables.addAll(r.group.tables()));
        if (app == null || tables.isEmpty()) {
            return;
        }
        long start = System.currentTimeMillis();
        try (Connection connection = app.getBean(DataSource.class).getConnection();
             Statement statement = connection.createStatement()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(true);
            try {
                statement.execute("SET lock_timeout = '20s'");
                for (String name : tables) {
                    statement.execute("VACUUM FULL public.\"" + name.replace("\"", "") + "\"");
                }
            } finally {
                statement.execute("RESET lock_timeout");
                connection.setAutoCommit(autoCommit);
            }
        }
        System.out.println("Endpoint sweep: compacted " + tables + " in " + (System.currentTimeMillis() - start)
                + " ms");
    }

    /** The entries of the run's groups by how they are swept */
    private static Map<String, Long> treatments(List<GroupRun> runs) {
        Map<String, Long> counts = new java.util.TreeMap<>();
        for (GroupRun run : runs) {
            for (EndpointEntry e : run.entries) {
                String treatment;
                if (e.isPending()) {
                    treatment = "pending";
                } else if (e.exempt != null) {
                    treatment = "exempt " + e.exempt.kind;
                } else if (e.isGlobal()) {
                    treatment = e.kind;
                } else if (e.coveredBy != null) {
                    treatment = "coveredBy (cells only)";
                } else {
                    treatment = "swept";
                }
                counts.merge(treatment, 1L, Long::sum);
            }
        }
        return counts;
    }

    /** Prints the run's call count, time and per-call median per group, and writes them to target/ */
    private static void writeTiming(SweepEngine engine, List<GroupRun> runs, long started, long factoryMillis)
            throws IOException {
        long wall = System.currentTimeMillis() - started;
        StringBuilder out = new StringBuilder("Endpoint sweep timing (" + (engine.full() ? "full" : "default")
                + " run): " + wall + " ms from the first fixture to the summaries, and " + factoryMillis
                + " ms before it (the table, the discovery, the engine)\n");
        int calls = 0;
        for (Map.Entry<String, List<Long>> group : engine.callMillis().entrySet()) {
            List<Long> millis = group.getValue().stream().sorted().toList();
            calls += millis.size();
            long total = millis.stream().mapToLong(Long::longValue).sum();
            out.append(String.format("  %-20s %5d calls, %7d ms in calls, median %4d ms, p90 %5d ms, max %6d ms%n",
                    group.getKey(), millis.size(), total, millis.get(millis.size() / 2),
                    millis.get((int) Math.min(millis.size() - 1, Math.floor(millis.size() * 0.9))),
                    millis.getLast()));
        }
        List<Long> all = engine.responses().stream().map(SweepResponse::millis).sorted().toList();
        if (!all.isEmpty()) {
            out.append(String.format("  all: %d calls, median %d ms, %.1f ms per call over the wall time%n", calls,
                    all.get(all.size() / 2), (double) wall / calls));
        }
        out.append("  groups: ").append(runs.stream().map(r -> r.group.name()).toList()).append('\n');
        slowest(engine.responses(), 10).forEach(line -> out.append(line).append('\n'));
        System.out.print(out);
        Path target = Path.of("target");
        if (Files.isDirectory(target)) {
            Files.writeString(target.resolve("endpoint-sweep-timing.txt"), out.toString(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The timing's lines of the slowest calls, slowest first: "slowest calls:" and one line per call with its time,
     * request id, verb and pattern, identity and note (empty when there is no call)
     */
    static List<String> slowest(Collection<SweepResponse> responses, int count) {
        List<String> lines = new ArrayList<>();
        responses.stream()
                .sorted(Comparator.comparingLong(SweepResponse::millis).reversed())
                .limit(count)
                .forEach(r -> lines.add(String.format("    %6d ms  %s  %s as %s%s", r.millis(), r.requestId(),
                        r.request().keyLike(), r.identity().name(),
                        r.request().note() == null ? "" : " (" + r.request().note() + ")")));
        if (!lines.isEmpty()) {
            lines.addFirst("  slowest calls:");
        }
        return lines;
    }

    /**
     * The order of the tear-downs: the reverse of the fixtures'. A group's fixture may build on rows an earlier
     * fixture made, and a group that holds its tables to their row count before its fixture must not meet the rows of
     * a fixture made after its own, still there: each tear-down then runs when every later fixture's rows are gone.
     */
    static <T> List<T> tearDownOrder(List<T> fixtureOrder) {
        return fixtureOrder.reversed();
    }

    /** Every concrete SweepGroup of this package whose context is the given one (null: the main sweep) */
    static List<SweepGroup> discover(String context) {
        return discover().stream().filter(g -> Objects.equals(g.context(), context)).toList();
    }

    /** Every concrete SweepGroup of this package, created with its no-argument constructor */
    static List<SweepGroup> discover() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(SweepGroup.class));
        List<SweepGroup> groups = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(SweepGroup.class.getPackageName())) {
            try {
                Class<?> type = Class.forName(candidate.getBeanClassName());
                var constructor = type.getDeclaredConstructor();
                constructor.setAccessible(true);
                SweepGroup group = (SweepGroup) constructor.newInstance();
                if (!names.add(group.name() + "/" + group.context())) {
                    throw new IllegalStateException("Two SweepGroups of one context are named " + group.name());
                }
                groups.add(group);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create the SweepGroup " + candidate.getBeanClassName()
                        + " (it needs a no-argument constructor)", e);
            }
        }
        return groups;
    }

    /** The groups the run selects: the property, else the environment variable; empty for every group */
    static Set<String> selectedGroups() {
        String value = setting(GROUPS_PROPERTY, "NIORD_SWEEP_GROUPS");
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String setting(String property, String environmentVariable) {
        String value = System.getProperty(property);
        return value != null ? value : System.getenv(environmentVariable);
    }
}
