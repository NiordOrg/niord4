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

import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.Header;
import io.restassured.http.Method;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.batch.operations.JobOperator;
import org.niord.core.web.RequestIdFilter;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Sends the sweep's requests and holds each to the table.
 *
 * <p>Before each request it resolves the concrete request against the application's handler mapping and records a
 * failure unless it reaches the handler of the entry whose pattern the request names: so a 404 from no handler can
 * never pass as the handler's own 404, and a path that no longer reaches its handler is seen. It adds the identity
 * (a token fetched per request through {@code ITTest.accessToken(role)}, never kept here; the {@code NiordDomain}
 * header; or a ticket minted just before) and {@code X-Request-Id: sweep-<group>-<n>}. A 405 or 415 is never an
 * accepted answer. When an entry's test ends, the log events of its requests (and of the background windows its
 * uploads and settled writes opened) are held to the rule of {@link SweepLog}.
 *
 * <p>The cells: an entry whose handler has no code check ({@code codeCheck: none-found}, confirmed by a person)
 * gets the cells its {@code answers} lists, plus one anonymous call (401) when it is {@code @RolesAllowed}; its
 * allowed cell is the sweep itself. An entry with a code check gets exactly the cells its {@code answers} lists.
 * The full run ({@code -Dniord.sweep.full=true}) adds the cells of the four tiers and anonymous, derived from the
 * annotation, to every entry (for a write only the refused ones), and runs the probes and variants.
 *
 * <p>An entry's test passes only if a request of its own (the cells, the sweep, an allowed step) reached the entry's
 * handler and was answered below 400, unless the entry is refused-only (global, exempt) or its round trip is
 * {@code coveredBy} another test: a case whose sweep calls nothing, or calls a neighbour's path, fails.
 *
 * <p>Known faults ({@code knownFault}) hold for every call to the entry's own handler whose cell they name: a table
 * cell by its name; a call a case makes by the identity it is sent as, or by its step ({@code sweep}, {@code allowed},
 * {@code probes}, {@code variants}); {@code *} for every call. A call that shows the fault's answer passes; when the
 * call was a case's, the rest of that step is the fault's consequence and is skipped. A {@code check} fault holds for
 * the case's checks whose text it names (a dropped field). A known fault that applied to at least one call of the
 * entry's test and showed in none fails the entry: it was fixed, and its mark must go.
 */
public final class SweepEngine {

    /** A request's answer did not come because the cell's identity is not in the test realm */
    static final class IdentityMissing extends RuntimeException {
        IdentityMissing(String identity) {
            super(identity);
        }
    }

    /** A call of a case showed a known fault: the rest of the case's step is the fault's consequence */
    static final class KnownFaultShown extends RuntimeException {
        KnownFaultShown(String message) {
            super(message, null, false, false);
        }
    }

    /** A time window of background work held to the rule of the entry that opened it */
    record Window(long from, long to) {
    }

    /** The answer of the transport to one prepared request */
    record Exchange(int status, Map<String, List<String>> headers, byte[] body) {
    }

    /** Sends a prepared request (its headers set) and answers; HTTP to the application, or the unit test's own */
    @FunctionalInterface
    interface Transport {
        Exchange exchange(SweepRequest sent, Map<String, String> headers);
    }

    /** The steps of an entry's test, the cell names a known fault may give for a case's calls */
    static final String CELLS = "cells";
    static final String SWEEP = "sweep";
    static final String ALLOWED = "allowed";
    static final String PROBES = "probes";
    static final String VARIANTS = "variants";
    static final Set<String> STEPS = Set.of(SWEEP, ALLOWED, PROBES, VARIANTS);

    /** What one entry's test (or a fixture, a settle, a tear-down) collected */
    final class Scope {
        final String label;
        final EndpointEntry entry;
        final List<String> failures = new ArrayList<>();
        final List<SweepResponse> responses = new ArrayList<>();
        final List<Window> background = new ArrayList<>();
        final Set<EndpointEntry.KnownFault> faultsApplied = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<EndpointEntry.KnownFault> faultsSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<String> faultNotes = new ArrayList<>();
        /** The 2xx answers of the entry's own handler in the sweep step */
        final List<SweepResponse> ownSweepAnswers = new ArrayList<>();
        String step = CELLS;
        String cell;
        boolean reachedOwnHandler;
        boolean settled;

        Scope(String label, EndpointEntry entry) {
            this.label = label;
            this.entry = entry;
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int port;
    private final ApplicationContext app;
    private final Function<String, String> tokens;
    private final EndpointTable table;
    private final SweepLog log;
    private final boolean full;
    private final SweepJobs jobs;
    private final Transport transport;
    private final String runToken = Long.toString(System.currentTimeMillis(), 36);
    private final AtomicLong uniques = new AtomicLong();
    private final Map<String, AtomicLong> counters = new TreeMap<>();
    private final Map<String, List<Long>> callMillis = new TreeMap<>();
    private final Set<String> missingIdentities = new TreeSet<>();
    private final Set<String> identityMissingCells = new TreeSet<>();
    private final List<String> settleSubjects = new ArrayList<>();
    private long settleFrom = Long.MAX_VALUE;
    /** Request id to the label and entry of the test that sent it */
    private final Map<String, String> requestLabel = new HashMap<>();
    private final Map<String, EndpointEntry> requestEntry = new HashMap<>();
    private final List<SweepResponse> allResponses = new ArrayList<>();
    private final List<Window> allBackground = new ArrayList<>();
    private final Set<SweepLog.Event> attributed = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Null: no handler mapping to resolve against (the engine's unit tests) */
    private final List<RequestMappingHandlerMapping> mappings;
    private Scope scope;

    SweepEngine(int port, ApplicationContext app, Function<String, String> tokens, EndpointTable table, SweepLog log,
                boolean full) {
        this(port, app, tokens, table, log, full, null);
    }

    private SweepEngine(int port, ApplicationContext app, Function<String, String> tokens, EndpointTable table,
                        SweepLog log, boolean full, Transport transport) {
        this.port = port;
        this.app = app;
        this.tokens = tokens;
        this.table = table;
        this.log = log;
        this.full = full;
        this.jobs = app == null ? null : new SweepJobs(app.getBean(JobOperator.class));
        this.mappings = app == null ? null
                : new ArrayList<>(app.getBeansOfType(RequestMappingHandlerMapping.class).values());
        this.transport = transport != null ? transport : this::http;
        if (app != null && !SweepIdentity.ADDED_IN_REALM) {
            // Only while the realm is without them: their cells are then listed as not run
            for (String added : SweepIdentity.ADDED) {
                try {
                    tokens.apply(added);
                } catch (RuntimeException | AssertionError e) {
                    missingIdentities.add(added);
                }
            }
        }
    }

    /** An engine without a server, for the unit test of its rules (cells, answers) */
    static SweepEngine forRules(boolean full) {
        return new SweepEngine(0, null, role -> null, null, null, full, null);
    }

    /** An engine whose requests the unit test answers: no server, no handler mapping, tokens named by role */
    static SweepEngine forTest(EndpointTable table, SweepLog log, boolean full, Transport transport) {
        return new SweepEngine(0, null, role -> "token-" + role, table, log, full, transport);
    }

    // ------------------------------------------------------------------------------------------- //
    // Accessors for the context
    // ------------------------------------------------------------------------------------------- //

    boolean full() {
        return full;
    }

    EndpointTable table() {
        return table;
    }

    SweepLog log() {
        return log;
    }

    SweepJobs jobs() {
        return jobs;
    }

    <T> T bean(Class<T> type) {
        return app.getBean(type);
    }

    String unique() {
        return runToken + "-" + uniques.incrementAndGet();
    }

    EndpointEntry currentEntry() {
        return scope == null ? null : scope.entry;
    }

    Set<String> missingIdentities() {
        return missingIdentities;
    }

    Set<String> identityMissingCells() {
        return identityMissingCells;
    }

    Map<String, List<Long>> callMillis() {
        return callMillis;
    }

    void fail(String message) {
        if (scope == null) {
            throw new AssertionError(message);
        }
        scope.failures.add(message);
    }

    void settle(String mailSubject) {
        settleSubjects.add(mailSubject);
        if (scope != null) {
            scope.settled = true;
            settleFrom = Math.min(settleFrom, scope.responses.isEmpty() ? System.currentTimeMillis()
                    : scope.responses.getFirst().sentMillis());
        }
    }

    void backgroundWindow(long from, long to) {
        Window window = new Window(from, to);
        allBackground.add(window);
        if (scope != null) {
            scope.background.add(window);
        }
    }

    List<String> settleSubjects() {
        return settleSubjects;
    }

    // ------------------------------------------------------------------------------------------- //
    // Scopes
    // ------------------------------------------------------------------------------------------- //

    /** Runs the body in a scope of its own and fails with every failure it collected */
    void inScope(String label, EndpointEntry entry, ThrowingRunnable body) {
        Scope previous = scope;
        scope = new Scope(label, entry);
        try {
            body.run();
        } catch (IdentityMissing e) {
            identityMissingCells.add(label + ": " + e.getMessage());
        } catch (Throwable t) {
            scope.failures.add(label + ": the case stopped with " + t + stack(t));
        } finally {
            Scope done = scope;
            scope = previous;
            List<String> failures = finish(done);
            if (!done.faultNotes.isEmpty()) {
                System.out.println("Endpoint sweep: " + label + ": " + String.join("; ", done.faultNotes));
            }
            if (!failures.isEmpty()) {
                throw new AssertionError(failures.size() + " failure(s) of " + label + ":\n\n"
                        + String.join("\n\n", failures));
            }
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String stack(Throwable t) {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement element : t.getStackTrace()) {
            if (element.getClassName().startsWith("org.niord.web.sweep")) {
                out.append("\n    at ").append(element);
            }
        }
        return out.toString();
    }

    /** Holds the log of the scope's requests and background windows to the rule; answers the scope's failures */
    private List<String> finish(Scope s) {
        List<String> failures = new ArrayList<>(s.failures);
        List<String> expected = s.entry == null ? List.of() : s.entry.expectedLog;
        List<SweepLog.Event> events = log.events();
        for (SweepResponse response : s.responses) {
            for (SweepLog.Event event : events) {
                if (!belongsTo(event, response)) {
                    continue;
                }
                attributed.add(event);
                if (SweepLog.breaksTheRule(event, expected) && !knownLog(s.entry, s.faultsSeen, event)) {
                    failures.add(SweepFailure.ofLog(response, event));
                }
            }
        }
        for (Window window : s.background) {
            for (SweepLog.Event event : events) {
                if (attributed.contains(event) || !inBackgroundWindow(event, window)) {
                    continue;
                }
                attributed.add(event);
                if ((event.isError() || event.isFatal()) && SweepLog.breaksTheRule(event, expected)
                        && !knownLog(s.entry, s.faultsSeen, event)) {
                    failures.add(s.label + ": background work of the case (its job or its settle window) logged:\n    "
                            + event.describe() + "\n  " + SweepFailure.ADVICE);
                }
            }
        }
        if (s.entry != null) {
            for (EndpointEntry.KnownFault fault : s.entry.knownFault) {
                if (s.faultsApplied.contains(fault) && !s.faultsSeen.contains(fault)) {
                    failures.add(s.label + ": the known fault " + fault.code + " (" + fault.describe() + ") no longer "
                            + "shows: it applied to a call of this test and none showed it. If it was fixed, remove "
                            + "it from the entry and close its record.");
                }
            }
        }
        return failures;
    }

    /** Whether a known fault of the entry names the event (and marks it seen) */
    private static boolean knownLog(EndpointEntry entry, Set<EndpointEntry.KnownFault> seen, SweepLog.Event event) {
        if (entry == null) {
            return false;
        }
        for (EndpointEntry.KnownFault fault : entry.knownFault) {
            if (fault.log != null && event.describe().contains(fault.log)) {
                if (seen != null) {
                    seen.add(fault);
                }
                return true;
            }
        }
        return false;
    }

    private boolean belongsTo(SweepLog.Event event, SweepResponse response) {
        return belongsTo(event, response, allResponses);
    }

    /**
     * Whether the event is the response's: it carries the request's id; or it carries no id, was written on a servlet
     * thread, and falls between the request's send and its answer; or, in the 100 ms after the answer (an error
     * dispatch may log after the client has its answer), it falls inside no other request's time and no other answer
     * came between. So an id-less ERROR of a request is not also held against the request answered just before it.
     *
     * @param all every response of the run (the response itself among them)
     */
    static boolean belongsTo(SweepLog.Event event, SweepResponse response, Collection<SweepResponse> all) {
        if (response.requestId().equals(event.requestId())) {
            return true;
        }
        long at = event.millis();
        if (event.requestId() != null || !event.onServletThread() || at < response.sentMillis()
                || at > response.receivedMillis() + TAIL_MILLIS) {
            return false;
        }
        if (at <= response.receivedMillis()) {
            return true;
        }
        for (SweepResponse other : all) {
            if (other == response) {
                continue;
            }
            boolean during = at >= other.sentMillis() && at <= other.receivedMillis();
            boolean answeredBetween = other.receivedMillis() > response.receivedMillis() && other.receivedMillis() <= at;
            if (during || answeredBetween) {
                return false;
            }
        }
        return true;
    }

    /** How long after its answer an id-less servlet-thread event may still be a request's (see belongsTo) */
    static final long TAIL_MILLIS = 100;

    static boolean inBackgroundWindow(SweepLog.Event event, Window window) {
        boolean background = event.requestId() == null || event.requestId().startsWith("job-");
        return background && !event.onServletThread() && event.millis() >= window.from()
                && event.millis() <= window.to() + 2_000;
    }

    /**
     * Every ERROR (or lazy-loading failure) of the run that no test held to the rule: background work outside every
     * upload and settle window, and work of a sweep request logged after its entry's test had ended (an
     * {@code @Async} task that carries the request's id) unless the entry expects it.
     */
    List<SweepLog.Event> unattributedErrors(long from) {
        List<SweepLog.Event> errors = new ArrayList<>();
        for (SweepLog.Event event : log.events()) {
            if (event.millis() < from || attributed.contains(event) || !(event.isError() || event.isFatal())) {
                continue;
            }
            if (event.requestId() != null && requestLabel.containsKey(event.requestId())) {
                EndpointEntry entry = requestEntry.get(event.requestId());
                List<String> expected = entry == null ? List.of() : entry.expectedLog;
                if (SweepLog.breaksTheRule(event, expected) && !knownLog(entry, null, event)) {
                    errors.add(event);
                }
                continue;
            }
            if (allResponses.stream().anyMatch(r -> belongsTo(event, r))
                    || allBackground.stream().anyMatch(w -> inBackgroundWindow(event, w))) {
                continue;
            }
            errors.add(event);
        }
        return errors;
    }

    /** The test a sweep request id belongs to, or null for an event of no sweep request */
    String ownerOf(SweepLog.Event event) {
        return event.requestId() == null ? null : requestLabel.get(event.requestId());
    }

    // ------------------------------------------------------------------------------------------- //
    // Sending
    // ------------------------------------------------------------------------------------------- //

    private static final RestAssuredConfig REST_CONFIG = RestAssuredConfig.config()
            .encoderConfig(EncoderConfig.encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false));

    /** Sends the request as the identity, after checking it reaches its entry's handler */
    SweepResponse send(SweepGroup group, SweepIdentity identity, SweepRequest request) {
        if (identity.tokenRole() != null && missingIdentities.contains(identity.tokenRole())) {
            throw new IdentityMissing(identity.name());
        }
        SweepRequest sent = request;
        if (identity.isTicket()) {
            sent = request.at(request.pattern()).query("ticket", mint(group, identity));
        }
        String resolution = mappings == null ? null : resolve(sent);
        String requestId = "sweep-" + group.name() + "-"
                + String.format("%04d", counters.computeIfAbsent(group.name(), g -> new AtomicLong()).incrementAndGet());
        if (scope != null) {
            requestLabel.put(requestId, scope.label);
            if (scope.entry != null) {
                requestEntry.put(requestId, scope.entry);
            }
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(RequestIdFilter.HEADER, requestId);
        if (identity.tokenRole() != null) {
            headers.put("Authorization", "Bearer " + tokens.apply(identity.tokenRole()));
        }
        String domain = sent.domainSet ? sent.domain : identity.domain();
        if (domain != null) {
            headers.put("NiordDomain", domain);
        }
        headers.putAll(sent.headers);
        long sentMillis = System.currentTimeMillis();
        Exchange exchange = transport.exchange(sent, headers);
        long receivedMillis = System.currentTimeMillis();
        SweepResponse answer = new SweepResponse(sent, identity, requestId, exchange.status(), exchange.headers(),
                exchange.body(), sentMillis, receivedMillis);
        callMillis.computeIfAbsent(group.name(), g -> new ArrayList<>()).add(answer.millis());
        allResponses.add(answer);
        if (scope != null) {
            scope.responses.add(answer);
            if (resolution == null && isOwn(sent)) {
                faultsOf(identity.name(), null).forEach(scope.faultsApplied::add);
                if (answer.status() < 400) {
                    scope.reachedOwnHandler = true;
                    if (SWEEP.equals(scope.step) && answer.is2xx()) {
                        scope.ownSweepAnswers.add(answer);
                    }
                }
            }
        }
        if (resolution != null) {
            fail(SweepFailure.of(table, answer, "a request that reaches " + handlerOf(sent), resolution, log));
        }
        if (answer.status() == 405 || answer.status() == 415) {
            fail(SweepFailure.of(table, answer, "never 405 or 415 (the verb or the content type does not fit the "
                    + "handler)", null, log));
        }
        return answer;
    }

    /** The request over HTTP to the application */
    private Exchange http(SweepRequest sent, Map<String, String> headers) {
        RequestSpecification spec = RestAssured.given().config(REST_CONFIG).urlEncodingEnabled(false)
                .redirects().follow(false);
        headers.forEach(spec::header);
        if (sent.isMultipart()) {
            for (SweepRequest.Part part : sent.parts) {
                spec.multiPart(part.name(), part.fileName(), part.content(), part.contentType());
            }
            // A form field: a part with no file name (RestAssured names a byte part "file" when none is given)
            for (Map.Entry<String, String> field : sent.fields) {
                spec.multiPart(field.getKey(), field.getValue());
            }
        } else if (sent.body != null) {
            spec.contentType(sent.contentType).body(sent.body);
        }
        Response response = spec.request(Method.valueOf(sent.verb), "http://localhost:" + port + sent.uri());
        byte[] body = response.asByteArray();
        Map<String, List<String>> answerHeaders = new LinkedHashMap<>();
        for (Header header : response.getHeaders()) {
            answerHeaders.computeIfAbsent(header.getName(), h -> new ArrayList<>()).add(header.getValue());
        }
        return new Exchange(response.getStatusCode(), answerHeaders, body);
    }

    /** Whether the request is for the entry under test (its pattern is one the entry lists) */
    private boolean isOwn(SweepRequest request) {
        return scope != null && scope.entry != null && table.entryAt(request.verb(), request.pattern()) == scope.entry;
    }

    /**
     * The known faults of the entry under test that hold for a call: a table cell by its name; a case's call by its
     * identity, its step, and the cell of an allowed step; {@code *} for every call.
     */
    private List<EndpointEntry.KnownFault> faultsOf(String identity, String tableCell) {
        if (scope == null || scope.entry == null) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        names.add("*");
        if (tableCell == null && CELLS.equals(scope.step)) {
            tableCell = scope.cell;
        }
        if (tableCell != null) {
            names.add(tableCell);
        } else {
            names.add(identity);
            names.add(scope.step);
            if (scope.cell != null) {
                names.add(scope.cell);
            }
        }
        List<EndpointEntry.KnownFault> faults = new ArrayList<>();
        for (EndpointEntry.KnownFault fault : scope.entry.knownFault) {
            if (fault.check == null && names.contains(fault.cell)) {
                faults.add(fault);
            }
        }
        return faults;
    }

    private String handlerOf(SweepRequest request) {
        EndpointEntry entry = table.entryAt(request.verb(), request.pattern());
        return entry == null ? "the handler of " + request.keyLike() + " (which no entry lists)" : entry.handler;
    }

    /** A one-time ticket the identity's minter asks for, with the identity's ticket role if any */
    private String mint(SweepGroup group, SweepIdentity identity) {
        SweepRequest ticket = SweepRequest.get("/rest/tickets/ticket");
        if (identity.ticketRole() != null) {
            ticket.query("role", identity.ticketRole());
        }
        SweepResponse answer = send(group, identity.minter(), ticket);
        if (!answer.is2xx() || answer.text().isBlank()) {
            fail(SweepFailure.of(table, answer, "a ticket for " + identity.name(), null, log));
            throw new IllegalStateException(identity.minter().name() + " could not mint the ticket of "
                    + identity.name());
        }
        return answer.text().trim();
    }

    /**
     * Resolves the concrete request against the handler mapping: null when it reaches the handler of the entry that
     * lists the request's pattern, else what it reaches.
     */
    String resolve(SweepRequest request) {
        return resolve(mappings, table, request);
    }

    /** {@link #resolve(SweepRequest)} against the mappings */
    static String resolve(List<RequestMappingHandlerMapping> mappings, EndpointTable table, SweepRequest request) {
        EndpointEntry entry = table.entryAt(request.verb(), request.pattern());
        if (entry == null) {
            return "No entry of the table lists " + request.keyLike();
        }
        MockHttpServletRequest mock = new MockHttpServletRequest(request.verb(), request.path());
        if (request.isMultipart()) {
            mock.setContentType("multipart/form-data; boundary=sweep");
        } else if (request.contentType != null) {
            mock.setContentType(request.contentType);
        }
        mock.addHeader("Accept", "*/*");
        request.query.forEach(q -> mock.addParameter(q.getKey(), q.getValue()));
        ServletRequestPathUtils.parseAndCache(mock);
        String problem = "it reaches no handler";
        for (RequestMappingHandlerMapping mapping : mappings) {
            HandlerExecutionChain chain;
            try {
                chain = mapping.getHandler(mock);
            } catch (Exception e) {
                problem = "the handler mapping refuses it: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                continue;
            }
            if (chain != null && chain.getHandler() instanceof HandlerMethod handler) {
                String reached = handler.getBeanType().getSimpleName() + "." + handler.getMethod().getName();
                String pattern = (String) mock.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                if (reached.equals(entry.handler) && entry.paths().contains(pattern)) {
                    return null;
                }
                return "it reaches " + reached + " at " + pattern + ", not " + entry.handler + " at "
                        + request.pattern();
            }
        }
        return problem;
    }

    // ------------------------------------------------------------------------------------------- //
    // Answers
    // ------------------------------------------------------------------------------------------- //

    /** Whether the answer is the expected cell's: 2xx for ok; else the status and the detail */
    static boolean matches(Cell expected, SweepResponse response) {
        int status = response.status();
        if (status == 405 || status == 415) {
            return false;
        }
        if (expected.isOk()) {
            return response.is2xx();
        }
        if (status != expected.status()) {
            return false;
        }
        String detail = response.detail();
        if (!expected.detailGiven()) {
            return status != 404 || detail != null;
        }
        if (expected.detail() == null) {
            // Without a detail: neither a problem detail nor a publication catalogue sentence (name its code instead)
            return (detail == null || detail.isBlank()) && response.catalogueDetail() == null;
        }
        if (detail == null) {
            // A publication refusal carries its sentence as a catalogue code and message, not a problem detail
            detail = response.catalogueDetail();
        }
        return detail != null && detail.contains(expected.detail());
    }

    /** Checks a table cell's answer, allowing a known fault of the entry that names the cell (or {@code *}) */
    void expectCell(EndpointEntry entry, String cell, Cell expected, SweepResponse response) {
        expectAnswer(response, expected, cell);
    }

    /**
     * Checks an answer against the expected cell. For a call to the entry under test, a known fault that holds for
     * the call and whose answer it shows passes it; a case's call then ends the case's step ({@link KnownFaultShown}).
     *
     * @param tableCell the table cell the call is, or null for a call a case makes
     */
    void expectAnswer(SweepResponse response, Cell expected, String tableCell) {
        if (matches(expected, response)) {
            return;
        }
        if (isOwn(response.request())) {
            for (EndpointEntry.KnownFault fault : faultsOf(response.identity().name(), tableCell)) {
                if (fault.answer != null && fault.answer == response.status()) {
                    scope.faultsApplied.add(fault);
                    scope.faultsSeen.add(fault);
                    String note = "known fault " + fault.code + " showed (" + response.request().keyLike() + " as "
                            + response.identity().name() + " answered " + response.status()
                            + (tableCell == null ? " in the " + scope.step + " step, whose rest is skipped)" : ")");
                    scope.faultNotes.add(note);
                    if (tableCell == null) {
                        throw new KnownFaultShown(note);
                    }
                    return;
                }
            }
        }
        fail(SweepFailure.of(table, response, expected + (tableCell == null ? "" : " (cell " + tableCell + ")"), null,
                log));
    }

    /**
     * A check of a case ({@code ctx.check}, {@code ctx.checkEquals}, {@code ctx.expectRows}): a {@code check} known
     * fault of the entry whose text is part of {@code what} allows a false one.
     *
     * @return the condition
     */
    boolean check(boolean condition, String what, String message, SweepResponse about) {
        List<EndpointEntry.KnownFault> faults = new ArrayList<>();
        if (scope != null && scope.entry != null) {
            for (EndpointEntry.KnownFault fault : scope.entry.knownFault) {
                if (fault.check != null && what.contains(fault.check)) {
                    faults.add(fault);
                    scope.faultsApplied.add(fault);
                }
            }
        }
        if (condition) {
            return true;
        }
        if (!faults.isEmpty()) {
            faults.forEach(scope.faultsSeen::add);
            scope.faultNotes.add("known fault " + faults.getFirst().code + " showed (" + message + ")");
            return false;
        }
        fail(about == null ? message : SweepFailure.of(table, about, message, null, log));
        return false;
    }

    /** Checks a read's answer holds what the entry's expect names */
    void expectContent(EndpointEntry entry, SweepResponse response) {
        if (entry.expect == null) {
            if (entry.emptyByDesign == null && entry.reviewed) {
                fail(entry + ": the entry names neither expect nor emptyByDesign");
            }
            return;
        }
        for (String problem : contentProblems(entry.expect, response)) {
            fail(SweepFailure.of(table, response, problem, null, log));
        }
    }

    /** What the answer lacks of the expectation (empty when it holds all of it) */
    static List<String> contentProblems(EndpointEntry.Expect expect, SweepResponse response) {
        List<String> problems = new ArrayList<>();
        if (expect.contentType != null && (response.contentType() == null
                || !response.contentType().startsWith(expect.contentType))) {
            problems.add("content type " + expect.contentType + " (is " + response.contentType() + ")");
        }
        if (expect.minBytes != null && response.body().length < expect.minBytes) {
            problems.add("at least " + expect.minBytes + " bytes (is " + response.body().length + ")");
        }
        for (String row : expect.rows) {
            String missing = rowMissing(response, expect.at, row);
            if (missing != null) {
                problems.add("the fixture row " + row + (expect.at == null ? "" : " at " + expect.at)
                        + (missing.isEmpty() ? "" : " (" + missing + ")"));
            }
        }
        return problems;
    }

    /**
     * Null when the answer (or its value at the JSON path) holds the row's name as a value of its own: a JSON string
     * equal to it or holding it between characters that cannot be part of a name, so that {@code sweep-x-a} is not
     * found in {@code sweep-x-a-da}. Else why not ("" when simply absent).
     */
    static String rowMissing(SweepResponse response, String at, String row) {
        List<String> values = new ArrayList<>();
        String text = response.text();
        boolean json = response.contentType() != null && response.contentType().contains("json");
        if (at != null || json) {
            try {
                JsonNode node = JSON.readTree(text);
                if (at != null) {
                    Object value = response.json().get(at);
                    node = JSON.valueToTree(value);
                }
                collectValues(node, values);
            } catch (RuntimeException e) {
                return "the answer is not JSON or has no " + at + ": " + e.getMessage();
            }
        } else {
            values.add(text);
        }
        for (String value : values) {
            if (holdsName(value, row)) {
                return null;
            }
        }
        return "";
    }

    private static void collectValues(JsonNode node, List<String> values) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isContainer()) {
            node.values().forEach(v -> collectValues(v, values));
        } else {
            values.add(node.asString());
        }
    }

    /** Whether the text holds the name with no name character right before or after it */
    static boolean holdsName(String text, String name) {
        for (int i = text.indexOf(name); i >= 0; i = text.indexOf(name, i + 1)) {
            int end = i + name.length();
            if ((i == 0 || !nameChar(text.charAt(i - 1))) && (end == text.length() || !nameChar(text.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean nameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    // ------------------------------------------------------------------------------------------- //
    // An entry
    // ------------------------------------------------------------------------------------------- //

    /** A cell to run: its name, identity, fixture row, expected answer, and whether the annotation decided it */
    record CellRun(String name, String identity, String row, Cell expected, boolean derived) {
    }

    /** The cells the entry runs in this run */
    List<CellRun> cells(EndpointEntry entry) {
        Map<String, CellRun> cells = new LinkedHashMap<>();
        entry.answers.forEach((name, expected) -> cells.put(name, cellRun(name, expected, false)));
        boolean annotationOnly = "none-found".equals(entry.codeCheck);
        if (annotationOnly && entry.guard.startsWith("RolesAllowed") && !cells.containsKey("anonymous")) {
            cells.put("anonymous", cellRun("anonymous", Cell.status(401, null), true));
        }
        if (full) {
            for (String identity : derivedIdentities(entry)) {
                if (!cells.containsKey(identity)) {
                    cells.put(identity, cellRun(identity, SweepIdentity.parse(identity).derived(entry.guard), true));
                }
            }
        }
        return new ArrayList<>(cells.values());
    }

    /** The identities the full run derives cells for: the four tiers and anonymous */
    static List<String> derivedIdentities(EndpointEntry entry) {
        return new ArrayList<>(SweepIdentity.TIERS);
    }

    private static CellRun cellRun(String name, Cell expected, boolean derived) {
        int at = name.indexOf('@');
        String identity = at < 0 ? name : name.substring(0, at);
        String row = at < 0 ? null : name.substring(at + 1);
        return new CellRun(name, identity, row, expected, derived);
    }

    /** The identity the entry's sweep runs as */
    static SweepIdentity sweepIdentity(EndpointEntry entry, SweepCase sweepCase) {
        return sweepCase.as != null ? SweepIdentity.parse(sweepCase.as) : SweepIdentity.lowestAllowed(entry.guard);
    }

    /** Whether the entry runs only its refused cells (a global write, an exempt entry) */
    static boolean refusedOnly(EndpointEntry entry) {
        return (entry.isGlobal() && !"globalIdempotent".equals(entry.kind)) || entry.exempt != null;
    }

    /** Runs the entry: its cells, its sweep, and in the full run its probes and variants */
    void runEntry(SweepGroup group, SweepContext ctx, EndpointEntry entry, SweepCase sweepCase) throws Exception {
        boolean write = !entry.isRead();
        boolean refusedOnly = refusedOnly(entry);
        boolean covered = entry.coveredBy != null;
        SweepIdentity as = sweepIdentity(entry, sweepCase);

        for (CellRun cell : cells(entry)) {
            SweepIdentity identity;
            identity = SweepIdentity.parse(cell.identity());
            if (identity.tokenRole() != null && missingIdentities.contains(identity.tokenRole())
                    || identity.isTicket() && missingIdentities.contains(identity.minter().tokenRole())) {
                identityMissingCells.add(entry.key + " " + cell.name());
                continue;
            }
            if (cell.expected().isOk()) {
                boolean theSweepsOwn = identity.equals(as) && cell.row() == null;
                if (write) {
                    if (theSweepsOwn || refusedOnly || "globalIdempotent".equals(entry.kind)) {
                        continue;
                    }
                    if (sweepCase.allowed != null) {
                        scope.cell = cell.name();
                        try {
                            runStep(ALLOWED, () -> sweepCase.allowed.run(ctx, identity, cell.row()));
                        } finally {
                            scope.cell = null;
                        }
                    } else if (!cell.derived()) {
                        fail(entry + ": the cell " + cell.name() + " is allowed on a write, so the case needs an "
                                + "allowed step (the write as that identity, undone in finally)");
                    }
                    continue;
                }
                if (theSweepsOwn && !refusedOnly && !covered) {
                    continue;
                }
            }
            scope.cell = cell.name();
            try {
                SweepResponse response = send(group, identity, sweepCase.request.request(ctx, cell.row()));
                expectAnswer(response, cell.expected(), cell.name());
            } finally {
                scope.cell = null;
            }
        }

        if (!refusedOnly && !covered) {
            if (sweepCase.sweep != null) {
                runStep(SWEEP, () -> sweepCase.sweep.run(ctx, as));
                if (!write && entry.expect != null && scope.faultsSeen.isEmpty()) {
                    expectInSweep(entry);
                }
            } else if (!write) {
                runStep(SWEEP, () -> {
                    SweepResponse response = send(group, as, sweepCase.request.request(ctx, null));
                    expectAnswer(response, Cell.OK, null);
                    if (response.is2xx()) {
                        expectContent(entry, response);
                    }
                });
            } else {
                fail(entry + ": a " + entry.kind + " entry needs a sweep step (write, read back, clean up), or "
                        + "coveredBy naming the test that owns its round trip");
            }
            if (!scope.reachedOwnHandler && scope.faultsSeen.isEmpty()) {
                fail(entry + ": no request of its test reached its own handler " + entry.handler + " and was "
                        + "answered below 400. The sweep step must call the entry's own path (" + entry.key
                        + (entry.alsoAt.isEmpty() ? "" : " or " + entry.alsoAt) + ") as an allowed identity.");
            }
        }
        if (full && sweepCase.probes != null) {
            runStep(PROBES, () -> sweepCase.probes.run(ctx, as));
        }
        if (full && sweepCase.variants != null) {
            runStep(VARIANTS, () -> sweepCase.variants.run(ctx, as));
        }
        if (entry.settle && !scope.settled && !refusedOnly && !covered) {
            fail(entry + ": the entry declares settle, but its case marked no settle point (ctx.settle)");
        }
        if (!entry.settle && scope.settled) {
            fail(entry + ": the case marked a settle point (ctx.settle), but the entry does not declare settle");
        }
    }

    /** A read whose case sweeps itself: at least one 2xx answer of its own handler holds what expect names */
    private void expectInSweep(EndpointEntry entry) {
        List<String> last = null;
        for (SweepResponse response : scope.ownSweepAnswers) {
            last = contentProblems(entry.expect, response);
            if (last.isEmpty()) {
                return;
            }
        }
        if (last != null) {
            fail(SweepFailure.of(table, scope.ownSweepAnswers.getLast(), String.join("; ", last)
                    + " (in no 2xx answer of the entry's own handler in the sweep step)", null, log));
        }
    }

    /** Runs a step of a case; a known fault a call of the step showed ends the step */
    private void runStep(String step, ThrowingRunnable body) throws Exception {
        String previous = scope.step;
        scope.step = step;
        try {
            body.run();
        } catch (KnownFaultShown shown) {
            // The rest of the step is the fault's consequence; the case's finally blocks have run
        } finally {
            scope.step = previous;
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Settle
    // ------------------------------------------------------------------------------------------- //

    /**
     * The shared settle point: waits until the sweep's mails have left the queue (bounded at 75 s) and the background
     * threads have been quiet for two seconds, then holds the background log since the first settled write to the rule.
     */
    void settleAll(SweepGroup anyGroup) throws InterruptedException {
        if (settleSubjects.isEmpty()) {
            return;
        }
        long deadline = System.currentTimeMillis() + 75_000L;
        for (String subject : settleSubjects) {
            if (subject == null) {
                continue;
            }
            boolean left = false;
            SweepResponse pending = null;
            while (System.currentTimeMillis() < deadline) {
                pending = send(anyGroup, SweepIdentity.parse("sysadmin"),
                        SweepRequest.get("/rest/scheduled-mails/search").query("subject", subject)
                                .query("status", "PENDING"));
                if (!pending.is2xx()) {
                    fail(SweepFailure.of(table, pending, "ok (2xx): the settle point reads the mail queue", null, log));
                    break;
                }
                if (((Number) pending.json().get("total")).longValue() == 0) {
                    left = true;
                    break;
                }
                Thread.sleep(1_000);
            }
            if (!left && (pending == null || pending.is2xx())) {
                fail("The settle point: the sweep's mail \"" + subject + "\" is still PENDING after 75 s");
            }
        }
        long quietDeadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < quietDeadline
                && System.currentTimeMillis() - log.lastBackgroundMillis() < 2_000) {
            Thread.sleep(250);
        }
        // The settled entries' tests have ended: what their background work logged is held to the rule here
        Window window = new Window(settleFrom, System.currentTimeMillis());
        allBackground.add(window);
        if (scope != null) {
            scope.background.add(window);
        }
    }

    /** Every response of the run (for the timing summary) */
    Collection<SweepResponse> responses() {
        return allResponses;
    }
}
