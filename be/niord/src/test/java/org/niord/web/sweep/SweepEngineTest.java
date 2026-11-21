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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sweep engine's own rules, without a server: what an answer must be to match a cell, which cells an entry runs,
 * what the identities hold, and how a request's path is built.
 */
class SweepEngineTest {

    private static SweepResponse answer(int status, String body, String contentType) {
        return new SweepResponse(SweepRequest.get("/rest/x"), SweepIdentity.parse("anonymous"), "sweep-test-0001",
                status, contentType == null ? Map.of() : Map.of("Content-Type", List.of(contentType)),
                body.getBytes(StandardCharsets.UTF_8), 0, 1);
    }

    private static SweepResponse problem(int status, String detail) {
        return answer(status, "{\"status\":" + status + (detail == null ? "" : ",\"detail\":\"" + detail + "\"") + "}",
                "application/problem+json");
    }

    @Test
    void okIsAny2xxAndNothingElse() {
        assertTrue(SweepEngine.matches(Cell.OK, answer(200, "[]", "application/json")));
        assertTrue(SweepEngine.matches(Cell.OK, answer(204, "", null)));
        assertFalse(SweepEngine.matches(Cell.OK, answer(307, "", null)), "a redirect is ok only where the cell says so");
        assertFalse(SweepEngine.matches(Cell.OK, problem(404, "No such source: 1")));
        assertTrue(SweepEngine.matches(Cell.status(307, null), answer(307, "", null)));
    }

    @Test
    void aRefusalMatchesByStatusAndDetail() {
        Cell refusal = Cell.status(403, "does not own filter");
        assertTrue(SweepEngine.matches(refusal, problem(403, "User x does not own filter 7")));
        assertFalse(SweepEngine.matches(refusal, problem(403, "another sentence")), "the detail is part of the cell");
        assertFalse(SweepEngine.matches(refusal, problem(403, null)));
        assertFalse(SweepEngine.matches(refusal, problem(401, "does not own filter")));
        assertTrue(SweepEngine.matches(Cell.status(401, null), answer(401, "", null)),
                "detail null: the answer carries none");
        assertFalse(SweepEngine.matches(Cell.status(401, null), problem(401, "something")));
    }

    @Test
    void aPublicationCatalogueRefusalMatchesByItsCodeAndMessage() {
        SweepResponse notInDomain = answer(403, "{\"code\":\"NOT_IN_DOMAIN\",\"message\":\"The publication series 'x'"
                + " belongs to the domain 'niord-nm'\"}", "application/json");
        assertEquals("NOT_IN_DOMAIN: The publication series 'x' belongs to the domain 'niord-nm'",
                notInDomain.catalogueDetail());
        assertTrue(SweepEngine.matches(Cell.status(403, "NOT_IN_DOMAIN"), notInDomain), "the code");
        assertTrue(SweepEngine.matches(Cell.status(403, "belongs to the domain 'niord-nm'"), notInDomain),
                "the sentence");
        assertFalse(SweepEngine.matches(Cell.status(403, "STALE_VERSION"), notInDomain), "another code");
        assertFalse(SweepEngine.matches(Cell.status(403, null), notInDomain),
                "a cell written without a detail is an answer without one: a catalogue refusal is named by its code");
        assertTrue(SweepEngine.matches(Cell.status(403, null), answer(403, "", null)),
                "Spring Security's 403 carries no detail and no catalogue body");
        SweepResponse notFound = answer(404, "{\"code\":\"ISSUE_NOT_FOUND\",\"message\":\"no issue with public id 9\"}",
                "application/json");
        assertTrue(SweepEngine.matches(Cell.status(404, "ISSUE_NOT_FOUND"), notFound),
                "a catalogue 404 passes with its code");
        assertFalse(SweepEngine.matches(Cell.statusOnly(404), notFound),
                "an undecided 404 cell reads the problem detail only, as before");
        assertEquals(null, problem(403, "x").catalogueDetail(), "a problem detail is not a catalogue body");
        assertEquals(null, answer(403, "{\"code\":7,\"message\":\"m\"}", "application/json").catalogueDetail(),
                "a code that is not a text");
        assertEquals(null, answer(403, "{\"code\":\"C\",\"message\":\"m\"}", "text/plain").catalogueDetail(),
                "not JSON");
    }

    @Test
    void anIdlessServletErrorIsTheRequestItFallsInNotAlsoTheOneAnsweredJustBefore() {
        SweepResponse first = timedAt("sweep-x-0001", 1_000, 1_040);
        SweepResponse second = timedAt("sweep-x-0002", 1_060, 1_200);
        List<SweepResponse> all = List.of(first, second);
        SweepLog.Event inSecond = event(1_090, null, "http-nio-1-exec-2");
        assertFalse(SweepEngine.belongsTo(inSecond, first, all), "within 100 ms of the first answer, but the second's");
        assertTrue(SweepEngine.belongsTo(inSecond, second, all));
        SweepLog.Event afterFirst = event(1_050, null, "http-nio-1-exec-1");
        assertTrue(SweepEngine.belongsTo(afterFirst, first, all), "after the first answer, before the second was sent");
        assertFalse(SweepEngine.belongsTo(afterFirst, second, all));
        SweepLog.Event afterSecond = event(1_250, null, "http-nio-1-exec-2");
        assertTrue(SweepEngine.belongsTo(afterSecond, second, all), "the tail of the last answer");
        assertFalse(SweepEngine.belongsTo(afterSecond, first, all), "past the first's tail");
        SweepResponse fast = timedAt("sweep-x-0003", 1_045, 1_055);
        List<SweepResponse> withFast = List.of(first, fast);
        SweepLog.Event afterFast = event(1_100, null, "http-nio-1-exec-3");
        assertFalse(SweepEngine.belongsTo(afterFast, first, withFast),
                "in the first's tail, but another answer came between");
        assertTrue(SweepEngine.belongsTo(afterFast, fast, withFast));
        assertFalse(SweepEngine.belongsTo(event(1_350, null, "http-nio-1-exec-2"), second, all), "past the tail");
        assertFalse(SweepEngine.belongsTo(event(1_100, null, "job-runner-1"), second, all), "not a servlet thread");
        assertTrue(SweepEngine.belongsTo(event(5_000, "sweep-x-0001", "pool-1"), first, all), "its own id, any time");
        assertFalse(SweepEngine.belongsTo(event(1_100, "sweep-x-0009", "http-nio-1-exec-2"), second, all),
                "another request's id");
    }

    private static SweepResponse timedAt(String requestId, long sent, long received) {
        return new SweepResponse(SweepRequest.get("/rest/x"), SweepIdentity.parse("user"), requestId, 200, Map.of(),
                new byte[0], sent, received);
    }

    private static SweepLog.Event event(long millis, String requestId, String thread) {
        return new SweepLog.Event(millis, "ERROR", "org.example.X", thread, requestId, "an error", List.of());
    }

    @Test
    void aBare404NeverPassesAsTheHandlers404() {
        assertFalse(SweepEngine.matches(Cell.status(404, "No such source"), problem(404, null)));
        assertFalse(SweepEngine.matches(Cell.status(404, "No such source"), answer(404, "", null)));
        assertFalse(SweepEngine.matches(Cell.statusOnly(404), answer(404, "", null)),
                "even an undecided 404 cell needs a detail");
        assertTrue(SweepEngine.matches(Cell.status(404, "No such source"), problem(404, "No such source: 9")));
    }

    @Test
    void a405Or415IsNeverAccepted() {
        assertFalse(SweepEngine.matches(Cell.statusOnly(405), problem(405, "Method not allowed")));
        assertFalse(SweepEngine.matches(Cell.status(415, null), answer(415, "", null)));
        assertFalse(SweepEngine.matches(Cell.OK, answer(405, "", null)));
    }

    @Test
    void cellsOfAnAnnotationOnlyEntryAreOneAnonymousCall() {
        EndpointEntry write = entry("POST /rest/x", "RolesAllowed(ADMIN)", "none-found");
        List<SweepEngine.CellRun> cells = engine(false).cells(write);
        assertEquals(1, cells.size());
        assertEquals("anonymous", cells.getFirst().name());
        assertEquals(Cell.status(401, null), cells.getFirst().expected());

        assertEquals(List.of(), engine(false).cells(entry("GET /rest/x", "PermitAll", "none-found")),
                "a public read without a code check: the read sweep is its call");
    }

    @Test
    void theFullRunDerivesTheTiersAndKeepsTheWrittenCells() {
        EndpointEntry entry = entry("GET /rest/x", "RolesAllowed(EDITOR)", "found");
        entry.answers.put("user", Cell.status(403, "written"));
        Map<String, Cell> cells = new java.util.LinkedHashMap<>();
        engine(true).cells(entry).forEach(c -> cells.put(c.name(), c.expected()));
        assertEquals(Cell.status(403, "written"), cells.get("user"), "the table's cell wins over the derived one");
        assertEquals(Cell.status(401, null), cells.get("anonymous"));
        assertEquals(Cell.OK, cells.get("editor"));
        assertEquals(Cell.OK, cells.get("sysadmin"));

        assertEquals(List.of("user"), engine(false).cells(entry).stream().map(SweepEngine.CellRun::name).toList(),
                "the default run of a code-checked entry runs exactly its written cells");
    }

    @Test
    void identitiesHoldTheRolesOfTheRealm() {
        // An administrator curates; the realm has no curation role and the table no curator identity
        assertThrows(IllegalArgumentException.class, () -> SweepIdentity.parse("curator"));
        assertEquals(Cell.OK, SweepIdentity.parse("domainEditor").derived("RolesAllowed(EDITOR)"));
        assertEquals(Cell.status(403, null), SweepIdentity.parse("adminElsewhere").derived("RolesAllowed(ADMIN)"));
        assertEquals(Cell.status(403, null), SweepIdentity.parse("userOtherDomain").derived("RolesAllowed(USER)"));
        assertEquals("admin", SweepIdentity.lowestAllowed("RolesAllowed(ADMIN)").name());
        assertEquals("anonymous", SweepIdentity.lowestAllowed("PermitAll").name());

        SweepIdentity ticket = SweepIdentity.parse("ticket(admin:admin)");
        assertTrue(ticket.isTicket());
        assertEquals("admin", ticket.minter().name());
        assertEquals("admin", ticket.ticketRole());
        assertThrows(IllegalArgumentException.class, () -> SweepIdentity.parse("ticket(anonymous)"));
        assertThrows(IllegalArgumentException.class, () -> SweepIdentity.parse("guest"));
    }

    @Test
    void aRequestEncodesItsVariablesAndQuery() {
        assertEquals("/rest/sources/source/a%20b", SweepRequest.get("/rest/sources/source/{id}").var("id", "a b").path());
        assertEquals("/rest/repo/file/messages/1/a%20b.png",
                SweepRequest.get("/rest/repo/file/{*file}").var("file", "messages/1/a b.png").path());
        assertEquals("/rest/dictionaries/dictionary/web",
                SweepRequest.get("/rest/dictionaries/dictionary/{name:[^.]+}").var("name", "web").path());
        assertEquals("/rest/x?lang=en&name=a%26b",
                SweepRequest.get("/rest/x").query("lang", "en").query("name", "a&b").uri());
        assertThrows(IllegalStateException.class, () -> SweepRequest.get("/rest/x/{id}").path());
    }

    @Test
    void theTearDownsRunInTheReverseOrderOfTheFixtures() {
        assertEquals(List.of("system", "publications", "publication-issues", "delivery"),
                SweepRun.tearDownOrder(List.of("delivery", "publication-issues", "publications", "system")),
                "publication-issues counts its tables before its fixture: publications' fixture rows are gone first");
        assertEquals(List.of(), SweepRun.tearDownOrder(List.of()));
    }

    @Test
    void aFormFieldTravelsBesideTheFilesOfAMultipartRequest() {
        SweepRequest request = SweepRequest.post("/rest/x/{*folder}").var("folder", "temp/a")
                .field("data", "{\"lang\":\"en\"}").file("file", "a.txt", new byte[3], "text/plain");
        assertTrue(request.isMultipart());
        assertEquals(List.of(Map.entry("data", "{\"lang\":\"en\"}")), request.at("/rest/y/{*folder}").fields,
                "an alias keeps the form fields");
        assertEquals("multipart [data (a form field, 13 characters), file=a.txt (3 bytes)]", request.bodyPreview());
        assertTrue(SweepRequest.post("/rest/x").field("data", "{}").isMultipart(), "a field alone is multipart too");
        assertFalse(SweepRequest.post("/rest/x").json(Map.of()).isMultipart());
        assertEquals("multipart [file=a.txt (3 bytes)]", SweepRequest.post("/rest/x")
                .file("file", "a.txt", new byte[3], "text/plain").bodyPreview(), "files alone preview as before");
    }

    @Test
    void theTimingNamesTheSlowestCallsSlowestFirst() {
        List<SweepResponse> calls = List.of(
                timed("sweep-x-0001", 5), timed("sweep-x-0002", 900), timed("sweep-x-0003", 40));
        List<String> lines = SweepRun.slowest(calls, 2);
        assertEquals(3, lines.size(), "a heading and the two slowest: " + lines);
        assertEquals("  slowest calls:", lines.get(0));
        assertTrue(lines.get(1).contains("900 ms") && lines.get(1).contains("sweep-x-0002")
                && lines.get(1).contains("GET /rest/x/{id} as editor (a note)"), lines.get(1));
        assertTrue(lines.get(2).contains("40 ms") && lines.get(2).contains("sweep-x-0003"), lines.get(2));
        assertEquals(List.of(), SweepRun.slowest(List.of(), 10), "no calls, no lines");
    }

    private static SweepResponse timed(String requestId, long millis) {
        return new SweepResponse(SweepRequest.get("/rest/x/{id}").var("id", 1).note("a note"),
                SweepIdentity.parse("editor"), requestId, 200, Map.of(), new byte[0], 1000, 1000 + millis);
    }

    private static EndpointEntry entry(String key, String guard, String codeCheck) {
        EndpointEntry entry = new EndpointEntry();
        entry.key = key;
        entry.handler = "X.y";
        entry.guard = guard;
        entry.codeCheck = codeCheck;
        entry.kind = key.startsWith("GET") ? "read" : "write";
        return entry;
    }

    /** An engine for its pure parts: no server, no context */
    private static SweepEngine engine(boolean full) {
        return SweepEngine.forRules(full);
    }
}
