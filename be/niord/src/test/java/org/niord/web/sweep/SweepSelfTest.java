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

import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sweep's own guarantees, pinned without a server: the engine runs an entry against answers this test gives
 * (no handler mapping, tokens named by role), and the format test and the generator are fed entries and handlers made
 * here. Each test is a way the sweep could pass while the product is wrong, or fail while it is right.
 */
class SweepSelfTest {

    // ------------------------------------------------------------------------------------------- //
    // Known faults
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aKnownFaultOfTheSweepsOwnWritePassesWhileItShowsAndFailsOnceFixed() {
        EndpointEntry update = write("PUT /rest/t/thing/{id}", "T.update", "RolesAllowed(ADMIN)");
        update.knownFault.add(fault("admin", 500));
        SweepCase sweepCase = SweepCase.of((ctx, row) -> put()).sweep((ctx, as) -> {
            SweepResponse written = ctx.ok(as, put());
            // Never reached while the fault shows: the rest of the step is the fault's consequence
            ctx.checkEquals("read back", "something else", "the read-back after the write", written);
        });

        Run broken = run(update, sweepCase, false, (request, identity) -> identity.isAnonymous() ? 401 : 500);
        assertNull(broken.failure, "the known fault shows on the sweep's own call: green");
        assertEquals(List.of("anonymous", "admin"), broken.callers);

        Run fixed = run(update, SweepCase.of((ctx, row) -> put()).sweep((ctx, as) -> ctx.ok(as, put())), false,
                (request, identity) -> identity.isAnonymous() ? 401 : 200);
        assertNotNull(fixed.failure);
        assertTrue(fixed.failure.contains("the known fault TEST1") && fixed.failure.contains("no longer shows"),
                fixed.failure);
    }

    @Test
    void aKnownFaultOfEveryCallDoesNotFailWhenOneCellAnswersAsTheTableSays() {
        EndpointEntry update = write("PUT /rest/t/thing/{id}", "T.update", "RolesAllowed(ADMIN)");
        update.knownFault.add(fault("*", 500));
        Run run = run(update, SweepCase.of((ctx, row) -> put()).sweep((ctx, as) -> ctx.ok(as, put())), false,
                (request, identity) -> identity.isAnonymous() ? 401 : 500);
        assertNull(run.failure, "the anonymous 401 is as the table says; the admin write shows the fault");
    }

    @Test
    void aKnownFaultOfTheProbesAppliesOnlyWhereTheProbesRun() {
        EndpointEntry read = read("GET /rest/t/thing/{id}", "T.get", "PermitAll");
        read.knownFault.add(fault("probes", 500));
        SweepCase sweepCase = SweepCase.of((ctx, row) -> get("1"))
                .probes((ctx, as) -> ctx.call(as.name(), get("999").note("unknown-id probe"),
                        Cell.status(404, "No such thing")));
        BiFunction<SweepRequest, SweepIdentity, Integer> probeBroken = (request, identity) ->
                request.path().endsWith("999") ? 500 : 200;

        assertNull(run(read, sweepCase, false, probeBroken).failure, "the default run runs no probe");
        assertNull(run(read, sweepCase, true, probeBroken).failure, "the full run's probe shows the fault");
        Run fixed = run(read, sweepCase, true, (request, identity) -> request.path().endsWith("999") ? 404 : 200);
        assertNotNull(fixed.failure);
        assertTrue(fixed.failure.contains("no longer shows"), fixed.failure);
    }

    @Test
    void aKnownFaultOfACheckAllowsTheDroppedFieldAndFailsOnceItIsKept() {
        EndpointEntry update = write("PUT /rest/t/thing/{id}", "T.update", "RolesAllowed(ADMIN)");
        EndpointEntry.KnownFault dropped = new EndpointEntry.KnownFault();
        dropped.code = "TEST2";
        dropped.cell = "*";
        dropped.check = "the Danish abbreviation";
        update.knownFault.add(dropped);

        for (String readBack : new String[] {null, "SWD"}) {
            SweepCase sweepCase = SweepCase.of((ctx, row) -> put()).sweep((ctx, as) -> {
                SweepResponse written = ctx.ok(as, put());
                ctx.checkEquals("SWD", readBack, "the Danish abbreviation", written);
            });
            Run run = run(update, sweepCase, false, (request, identity) -> identity.isAnonymous() ? 401 : 200);
            if (readBack == null) {
                assertNull(run.failure, "the field is dropped, as the known fault says");
            } else {
                assertNotNull(run.failure);
                assertTrue(run.failure.contains("no longer shows"), run.failure);
            }
        }
    }

    @Test
    void aKnownFaultOnACallOfAnotherEntryDoesNotApply() {
        EndpointEntry update = write("PUT /rest/t/thing/{id}", "T.update", "RolesAllowed(ADMIN)");
        update.knownFault.add(fault("admin", 500));
        // The sweep's read-back of a neighbour answers 500: that is not the entry's fault to excuse
        SweepCase sweepCase = SweepCase.of((ctx, row) -> put()).sweep((ctx, as) -> {
            ctx.ok(as, put());
            ctx.ok("admin", get("1"));
        });
        Run run = run(update, sweepCase, false, (request, identity) ->
                identity.isAnonymous() ? 401 : request.verb().equals("GET") ? 500 : 200);
        assertNotNull(run.failure);
        assertTrue(run.failure.contains("GET /rest/t/thing/{id}") && run.failure.contains("real: 500"), run.failure);
    }

    // ------------------------------------------------------------------------------------------- //
    // An entry must call its own handler; coveredBy keeps the cells
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anEntryWhoseSweepDoesNotReachItsHandlerFails() {
        EndpointEntry update = write("PUT /rest/t/thing/{id}", "T.update", "RolesAllowed(ADMIN)");

        Run empty = run(update, SweepCase.of((ctx, row) -> put()).sweep((ctx, as) -> { }), false,
                (request, identity) -> identity.isAnonymous() ? 401 : 200);
        assertNotNull(empty.failure, "an empty sweep step sends only the anonymous call");
        assertTrue(empty.failure.contains("no request of its test reached its own handler"), empty.failure);

        Run neighbour = run(update, SweepCase.of((ctx, row) -> put()).sweep((ctx, as) -> ctx.ok(as, get("1"))), false,
                (request, identity) -> identity.isAnonymous() ? 401 : 200);
        assertNotNull(neighbour.failure, "a copied sweep that calls a neighbour's path does not count");
        assertTrue(neighbour.failure.contains("no request of its test reached its own handler"), neighbour.failure);

        Run refused = run(update, SweepCase.of((ctx, row) -> put()).sweep((ctx, as) -> ctx.send("user", put())),
                false, (request, identity) -> identity.isAnonymous() ? 401 : identity.name().equals("user") ? 403 : 200);
        assertNotNull(refused.failure, "a sweep whose own call is refused reached nothing");
    }

    @Test
    void aCoveredEntryRunsItsCellsAndSkipsItsRoundTrip() {
        EndpointEntry create = write("POST /rest/t/thing", "T.create", "RolesAllowed(ADMIN)");
        create.coveredBy = "SourceRestServiceITTest#getAllRespectsLimit";
        Run run = run(create, SweepCase.of((ctx, row) -> SweepRequest.post("/rest/t/thing").json(Map.of())), false,
                (request, identity) -> identity.isAnonymous() ? 401 : 200);
        assertNull(run.failure);
        assertEquals(List.of("anonymous"), run.callers, "the anonymous cell runs; the round trip is the IT's");

        Run wrongCell = run(create, SweepCase.of((ctx, row) -> SweepRequest.post("/rest/t/thing").json(Map.of())),
                false, (request, identity) -> 200);
        assertNotNull(wrongCell.failure, "the cells of a covered entry still hold");
    }

    @Test
    void theFullRunRunsTheAllowedStepForEachDerivedAllowedTierOfAWriteWithNoRow() {
        EndpointEntry update = write("PUT /rest/t/thing/{id}", "T.update", "RolesAllowed(EDITOR)");
        BiFunction<SweepRequest, SweepIdentity, Integer> byTier = (request, identity) ->
                identity.isAnonymous() ? 401 : identity.name().equals("user") ? 403 : 200;
        List<String> allowedRuns = new ArrayList<>();
        SweepCase withAllowed = SweepCase.of((ctx, row) -> put()).sweep((ctx, as) -> ctx.ok(as, put()))
                .allowed((ctx, as, row) -> {
                    allowedRuns.add(as.name() + "@" + row);
                    ctx.ok(as, put());
                });

        assertNull(run(update, withAllowed, false, byTier).failure);
        assertEquals(List.of(), allowedRuns, "the default run derives no tier cell");

        assertNull(run(update, withAllowed, true, byTier).failure);
        assertEquals(List.of("admin@null", "sysadmin@null"), allowedRuns,
                "the full run: the allowed step for each derived allowed tier but the sweep's own, with row null");

        Run without = run(update, SweepCase.of((ctx, row) -> put()).sweep((ctx, as) -> ctx.ok(as, put())), true,
                byTier);
        assertNull(without.failure, "without an allowed step the derived allowed cells are not run, and not owed");
        assertFalse(without.callers.contains("admin") || without.callers.contains("sysadmin"), "" + without.callers);
    }

    // ------------------------------------------------------------------------------------------- //
    // The log
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anErrorOfASweepRequestLoggedAfterItsTestEndedIsListed() {
        EndpointEntry read = read("GET /rest/t/thing/{id}", "T.get", "PermitAll");
        Run run = run(read, SweepCase.of((ctx, row) -> get("1")), false, (request, identity) -> 200);
        assertNull(run.failure);
        String requestId = run.requestIds.getLast();
        long late = System.currentTimeMillis() + 400;
        run.log.add(new SweepLog.Event(late, "ERROR", "org.niord.Late", "task-1", requestId, "late after the answer",
                List.of()));
        run.log.add(new SweepLog.Event(late, "ERROR", "org.niord.Job", "task-2", null, "background", List.of()));

        List<String> problems = SweepRun.backgroundProblems(run.engine, run.table, 0);
        String text = String.join("\n", problems);
        assertTrue(text.contains("late after the answer") && text.contains("logged after its test had ended")
                && text.contains(read.key), text);
        assertTrue(text.contains("background"), "a line of no request is still listed: " + text);

        read.expectedLog.add("late after the answer");
        assertFalse(String.join("\n", SweepRun.backgroundProblems(run.engine, run.table, 0))
                .contains("late after the answer"), "a line the entry expects is not listed");
    }

    @Test
    void theStartUpPassOfAClosedContextIsNotTheSweepsButAnotherIndexErrorIs() {
        EndpointEntry read = read("GET /rest/t/thing/{id}", "T.get", "PermitAll");
        Run run = run(read, SweepCase.of((ctx, row) -> get("1")), false, (request, identity) -> 200);
        assertNull(run.failure);
        long late = System.currentTimeMillis() + 400;
        String index = "org.niord.core.message.MessageLuceneIndex";
        run.log.add(new SweepLog.Event(late, "ERROR", index, "Timer-28", "job-1", "Error updating Lucene index: "
                + "Error creating bean with name 'messageLuceneIndex': Unsatisfied dependency expressed through field "
                + "'em'", List.of("org.springframework.beans.factory.UnsatisfiedDependencyException: ...")));
        assertEquals(List.of(), SweepRun.backgroundProblems(run.engine, run.table, 0),
                "the start-up pass of a context closed within its five seconds is not held to the rule");

        run.log.add(new SweepLog.Event(late, "ERROR", index, "scheduling-1", null,
                "Error updating Lucene index: IT: this message cannot be indexed", List.of()));
        run.log.add(new SweepLog.Event(late, "ERROR", "org.niord.Other", "Timer-29", null,
                "Error updating Lucene index: Error creating bean with name 'messageLuceneIndex'", List.of()));
        String text = String.join("\n", SweepRun.backgroundProblems(run.engine, run.table, 0));
        assertTrue(text.contains("this message cannot be indexed"), "any other index error still is: " + text);
        assertTrue(text.contains("org.niord.Other"), "the same words from another logger still are: " + text);
    }

    @Test
    void aLazyLoadingFailureNamedOnlyInTheMessageBreaksTheRule() {
        SweepLog.Event warn = new SweepLog.Event(0, "WARN", "org.niord.X", "http-1", "sweep-t-0001",
                "Could not load: org.hibernate.LazyInitializationException: could not initialize proxy", List.of());
        assertTrue(warn.isFatal());
        assertTrue(SweepLog.breaksTheRule(warn, List.of()));
        SweepLog.Event resolved = new SweepLog.Event(0, "WARN", "org.springframework.web.X", "http-1", null,
                "Resolved [org.hibernate.LazyInitializationException: failed to lazily initialize]", List.of());
        assertTrue(SweepLog.breaksTheRule(resolved, List.of("Resolved")), "never allowed, even when expected");
    }

    // ------------------------------------------------------------------------------------------- //
    // Content
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aRowIsFoundAsAValueNotInsideALongerName() {
        assertNotNull(SweepEngine.rowMissing(json("[{\"name\":\"sweep-t-a-da\"}]"), null, "sweep-t-a"),
                "the Danish name of the row is not the row");
        assertNull(SweepEngine.rowMissing(json("[{\"name\":\"sweep-t-a\"}]"), null, "sweep-t-a"));
        assertNull(SweepEngine.rowMissing(json("[{\"title\":\"sweep-t-a: the title\"}]"), null, "sweep-t-a"));
        assertNull(SweepEngine.rowMissing(json("{\"data\":[{\"name\":\"sweep-t-a\"}]}"), "data", "sweep-t-a"));
        assertNotNull(SweepEngine.rowMissing(json("{\"data\":[],\"other\":\"sweep-t-a\"}"), "data", "sweep-t-a"));
    }

    @Test
    void aBlankDetailIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Cell.status(404, ""));
        assertThrows(IllegalArgumentException.class, () -> Cell.status(403, " "));
    }

    // ------------------------------------------------------------------------------------------- //
    // Identities
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anAddedIdentityWhoseTokenCannotBeFetchedFailsTheRun() throws Throwable {
        assertTrue(SweepIdentity.ADDED_IN_REALM, "the three identities are in the test realm");
        try {
            List<DynamicNode> nodes = SweepRun.nodes(0, null, role -> {
                if (role.equals("curator")) {
                    throw new IllegalStateException("invalid_grant");
                }
                return "token-" + role;
            }, null).toList();
            DynamicTest settings = (DynamicTest) nodes.getFirst();
            AssertionError failed = assertThrows(AssertionError.class, () -> settings.getExecutable().execute());
            assertTrue(failed.getMessage().contains("curator"), failed.getMessage());
        } finally {
            SweepRun.detachTheLog();
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The format test refuses decisions that assert nothing
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theFormatAcceptsTheWorkedExample() {
        assertEquals(List.of(), format(goodRead()));
    }

    @Test
    void theFormatRefusesAReadThatExpectsNothing() {
        EndpointEntry empty = goodRead();
        empty.expect = new EndpointEntry.Expect();
        assertFormat(empty, "expect names neither rows nor a minimum size");

        EndpointEntry typeOnly = goodRead();
        typeOnly.expect = new EndpointEntry.Expect();
        typeOnly.expect.contentType = "application/json";
        assertFormat(typeOnly, "expect names neither rows nor a minimum size");

        EndpointEntry none = goodRead();
        none.expect = null;
        assertFormat(none, "names what its answer must hold");
    }

    @Test
    void theFormatRefusesAnExpectedLogThatAllowsEveryLine() {
        for (String line : List.of("", "ERROR", "org.niord.core", "ERROR org.niord.core.X: anything")) {
            EndpointEntry entry = goodRead();
            entry.expectedLog.add(line);
            assertFormat(entry, "would allow more than one line");
        }
    }

    @Test
    void theFormatRefusesAKindThatDoesNotFitTheVerb() {
        EndpointEntry read = goodRead();
        read.kind = "write";
        assertFormat(read, "does not fit the verb GET");
        EndpointEntry delete = goodRead();
        delete.key = "DELETE /rest/sources/source/{id}";
        delete.handler = "SourceRestService.deleteSource";
        delete.kind = "read";
        assertFormat(delete, "does not fit the verb DELETE");
    }

    @Test
    void theFormatRefusesRulesWithoutTheirCells() {
        EndpointEntry checked = goodRead();
        checked.guard = "RolesAllowed(USER)";
        checked.codeCheck = "found";
        checked.rules = new ArrayList<>(List.of("owner", "domain", "ticket"));
        checked.answers.put("anonymous", Cell.status(401, null));
        List<String> problems = format(checked);
        for (String expected : List.of("rule owner asks", "rule domain asks", "rule ticket asks",
                "its cells are only anonymous ones")) {
            assertTrue(problems.stream().anyMatch(p -> p.contains(expected)), expected + " in " + problems);
        }
    }

    @Test
    void theFormatRefusesAKnownFaultThatCanNeverApply() {
        EndpointEntry entry = goodRead();
        entry.knownFault.add(fault("admin@noSuchRow", 500));
        assertFormat(entry, "it would never apply");
        EndpointEntry guest = goodRead();
        guest.knownFault.add(fault("guest", 500));
        assertFormat(guest, "it would never apply");
        EndpointEntry probes = goodRead();
        probes.knownFault.add(fault("probes", 500));
        assertEquals(List.of(), format(probes));
    }

    @Test
    void theFormatRefusesACoveringTestThatIsNoTestMethod() {
        EndpointEntry create = goodRead();
        create.key = "POST /rest/sources/source";
        create.handler = "SourceRestService.createSource";
        create.kind = "write";
        create.guard = "RolesAllowed(ADMIN)";
        create.expect = null;
        create.caseName = "SourcesSweepGroup#getSource";
        create.coveredBy = "SourceRestService#createSource";
        assertFormat(create, "names no JUnit test method");
        create.coveredBy = "SourceVo";
        assertFormat(create, "names a class without a method");
        create.coveredBy = "SourceRestServiceITTest";
        assertFormat(create, "names a class without a method");
        create.coveredBy = "SourceRestServiceITTest#getAllRespectsLimit";
        assertEquals(List.of(), format(create));

        create.caseName = null;
        assertFormat(create, "which need the request a case makes");
    }

    @Test
    void theFormatRefusesAnExemptEntryThatRunsNothing() {
        EndpointEntry exempt = goodRead();
        exempt.key = "DELETE /rest/sources/source/{id}";
        exempt.handler = "SourceRestService.deleteSource";
        exempt.kind = "write";
        exempt.guard = "RolesAllowed(ADMIN)";
        exempt.expect = null;
        exempt.caseName = null;
        exempt.exempt = new EndpointEntry.Exempt();
        exempt.exempt.kind = "external";
        exempt.exempt.reason = "a partner";
        exempt.exempt.asserted = "the anonymous refusal";
        assertFormat(exempt, "an exempt entry still runs its refused cells");
    }

    // ------------------------------------------------------------------------------------------- //
    // The generator
    // ------------------------------------------------------------------------------------------- //

    /** A controller of handler names only, for the generator's matching */
    static final class ThingRestService {
        public void getThing() {
        }

        public void deleteThing() {
        }
    }

    @Test
    void theGeneratorKeepsAReviewedGuardAndGivesANewNamesakeHandlerItsOwnEntry() throws Exception {
        EndpointTable.TableFile file = new EndpointTable.TableFile();
        file.group = "things";
        file.controllers.add("ThingRestService");
        EndpointEntry get = reviewed("GET /rest/things/thing/{id}", "ThingRestService.getThing", "PermitAll", "read");
        EndpointEntry delete = reviewed("DELETE /rest/things/thing/{id}", "ThingRestService.deleteThing",
                "RolesAllowed(ADMIN)", "write");
        file.entries.addAll(List.of(get, delete));
        EndpointTable table = EndpointTable.of(Map.of("things", file));

        var getThing = ThingRestService.class.getMethod("getThing");
        var deleteThing = ThingRestService.class.getMethod("deleteThing");
        List<EndpointTableGenerator.Handler> handlers = List.of(
                handler(getThing, "GET", "/rest/things/thing/{id}", "PermitAll"),
                handler(getThing, "GET", "/rest/things/thing-lang/{id}", "PermitAll"),
                handler(deleteThing, "DELETE", "/rest/things/thing/{id}", "RolesAllowed(EDITOR)"));
        List<String> changes = EndpointTableGenerator.update(table, handlers);

        assertEquals(List.of(), get.alsoAt, "the existing entry does not take the new handler's path: " + changes);
        assertTrue(file.entries.stream().anyMatch(e -> e.key.equals("GET /rest/things/thing-lang/{id}")
                && e.isPending() && !e.reviewed), "the new handler gets a skeleton: " + changes);
        assertEquals("RolesAllowed(ADMIN)", delete.guard, "a reviewed entry's guard is not rewritten");
        assertTrue(changes.stream().anyMatch(c -> c.contains("NOT written")), changes.toString());

        delete.reviewed = false;
        EndpointTableGenerator.update(table, handlers);
        assertEquals("RolesAllowed(EDITOR)", delete.guard, "once a person unmarks it, the generator refreshes it");
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** One run of an entry against the answers of the function (status by request and identity) */
    private static final class Run {
        String failure;
        final List<String> callers = new ArrayList<>();
        final List<String> requestIds = new ArrayList<>();
        SweepEngine engine;
        EndpointTable table;
        final SweepLog log = new SweepLog();
    }

    private static Run run(EndpointEntry entry, SweepCase sweepCase, boolean full,
                           BiFunction<SweepRequest, SweepIdentity, Integer> status) {
        Run run = new Run();
        EndpointTable.TableFile file = new EndpointTable.TableFile();
        file.group = "t";
        file.controllers.add("T");
        file.entries.add(entry);
        for (String key : List.of("GET /rest/t/thing/{id}", "PUT /rest/t/thing/{id}", "POST /rest/t/thing")) {
            if (!key.equals(entry.key)) {
                file.entries.add(key.startsWith("GET") ? read(key, "T.get", "PermitAll")
                        : write(key, "T." + key.substring(0, key.indexOf(' ')).toLowerCase(), "RolesAllowed(ADMIN)"));
            }
        }
        run.table = EndpointTable.of(Map.of("t", file));
        run.engine = SweepEngine.forTest(run.table, run.log, full, (sent, headers) -> {
            String auth = headers.get("Authorization");
            String role = auth == null ? null : auth.substring("Bearer token-".length());
            SweepIdentity identity = role == null ? SweepIdentity.parse("anonymous") : SweepIdentity.parse(role);
            run.callers.add(identity.name());
            run.requestIds.add(headers.get(org.niord.core.web.RequestIdFilter.HEADER));
            int answer = status.apply(sent, identity);
            String body = answer == 404 ? "{\"status\":404,\"detail\":\"No such thing: 999\"}" : "{\"id\":1}";
            return new SweepEngine.Exchange(answer, Map.of("Content-Type", List.of("application/json")),
                    answer == 401 ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        });
        SweepGroup group = new SweepGroup() {
            @Override
            public String name() {
                return "t";
            }

            @Override
            public Set<String> rows() {
                return Set.of();
            }

            @Override
            public void setUp(SweepContext ctx) {
            }

            @Override
            public void cases(SweepCases cases) {
            }
        };
        SweepContext ctx = new SweepContext(run.engine, group);
        try {
            run.engine.inScope(entry.key, entry, () -> run.engine.runEntry(group, ctx, entry, sweepCase));
        } catch (AssertionError e) {
            run.failure = e.getMessage();
        }
        return run;
    }

    private static SweepRequest put() {
        return SweepRequest.put("/rest/t/thing/{id}").var("id", 1).json(Map.of("id", 1));
    }

    private static SweepRequest get(String id) {
        return SweepRequest.get("/rest/t/thing/{id}").var("id", id);
    }

    private static EndpointEntry write(String key, String handler, String guard) {
        EndpointEntry entry = reviewed(key, handler, guard, "write");
        entry.caseName = "T#x";
        return entry;
    }

    private static EndpointEntry read(String key, String handler, String guard) {
        EndpointEntry entry = reviewed(key, handler, guard, "read");
        entry.caseName = "T#x";
        entry.emptyByDesign = "a test entry";
        return entry;
    }

    private static EndpointEntry reviewed(String key, String handler, String guard, String kind) {
        EndpointEntry entry = new EndpointEntry();
        entry.key = key;
        entry.handler = handler;
        entry.guard = guard;
        entry.kind = kind;
        entry.codeCheck = "none-found";
        entry.reviewed = true;
        return entry;
    }

    private static EndpointEntry.KnownFault fault(String cell, int answer) {
        EndpointEntry.KnownFault fault = new EndpointEntry.KnownFault();
        fault.code = "TEST1";
        fault.cell = cell;
        fault.answer = answer;
        return fault;
    }

    private static SweepResponse json(String body) {
        return new SweepResponse(SweepRequest.get("/rest/x"), SweepIdentity.parse("anonymous"), "sweep-t-0001", 200,
                Map.of("Content-Type", List.of("application/json")), body.getBytes(StandardCharsets.UTF_8), 0, 1);
    }

    private static EndpointTableGenerator.Handler handler(java.lang.reflect.Method method, String verb, String path,
                                                          String guard) {
        return new EndpointTableGenerator.Handler(ThingRestService.class, method, verb, List.of(path), guard,
                List.of("id"), "none-found", List.of());
    }

    /** The worked example's getSource entry, as the table holds it */
    private static EndpointEntry goodRead() {
        EndpointEntry entry = reviewed("GET /rest/sources/source/{id}", "SourceRestService.getSource", "PermitAll",
                "read");
        entry.params = new ArrayList<>(List.of("id"));
        entry.caseName = "SourcesSweepGroup#getSource";
        entry.expect = new EndpointEntry.Expect();
        entry.expect.rows.add(SourcesSweepGroup.ACTIVE);
        return entry;
    }

    private static List<String> format(EndpointEntry entry) {
        return EndpointTableFormatTest.problems(entry, "sources", new SourcesSweepGroup(), Set.of("TEST1", "TEST2"),
                "");
    }

    private static void assertFormat(EndpointEntry entry, String expected) {
        List<String> problems = format(entry);
        assertTrue(problems.stream().anyMatch(p -> p.contains(expected)), "expected \"" + expected + "\" in "
                + problems);
    }
}
