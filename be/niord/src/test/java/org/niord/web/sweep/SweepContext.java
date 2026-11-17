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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a group's fixture and cases work with: sending requests through the engine (which resolves each against the
 * handler mapping, adds the identity and the request id and records the log), checking answers, the group's rows,
 * the application's beans and the batch jobs.
 *
 * <p>A check that fails is recorded, not thrown: one entry's test lists every mismatch of its cells, its sweep and its
 * probes, with the failure text of each. A case throws only when it cannot go on (an answer it needs is missing).
 *
 * <p>The entry's known faults hold for these checks too: an answer of the entry's own handler that shows a known
 * fault of the call passes, and ends the step it was made in (its {@code finally} blocks still run); a check whose
 * text a {@code check} fault names may be false. See {@link SweepEngine}.
 */
public final class SweepContext {

    private final SweepEngine engine;
    private final SweepGroup group;
    private final Map<String, Object> rows = new LinkedHashMap<>();

    SweepContext(SweepEngine engine, SweepGroup group) {
        this.engine = engine;
        this.group = group;
    }

    /** The group's name */
    public String group() {
        return group.name();
    }

    /** {@code sweep-<group>-<suffix>}: the name of a row of the group */
    public String name(String suffix) {
        return "sweep-" + group.name() + "-" + suffix;
    }

    /** A token unique to this run (and to the call), for names that must not meet an earlier run's rows */
    public String unique() {
        return engine.unique();
    }

    /** The full run ({@code -Dniord.sweep.full=true}): probes, variants and every derived role cell */
    public boolean full() {
        return engine.full();
    }

    /** The entry being swept (null in the fixture and the tear-down) */
    public EndpointEntry entry() {
        return engine.currentEntry();
    }

    public <T> T bean(Class<T> type) {
        return engine.bean(type);
    }

    public SweepJobs jobs() {
        return engine.jobs();
    }

    // ------------------------------------------------------------------------------------------- //
    // Requests and checks
    // ------------------------------------------------------------------------------------------- //

    /** Sends the request as the identity (a cell name's identity part, e.g. {@code "admin"} or {@code "ticket(user)"}) */
    public SweepResponse send(String identity, SweepRequest request) {
        return send(SweepIdentity.parse(identity), request);
    }

    public SweepResponse send(SweepIdentity identity, SweepRequest request) {
        return engine.send(group, identity, request);
    }

    /** Sends the request and checks the answer is a 2xx */
    public SweepResponse ok(String identity, SweepRequest request) {
        return expectOk(send(identity, request));
    }

    public SweepResponse ok(SweepIdentity identity, SweepRequest request) {
        return expectOk(send(identity, request));
    }

    /** Sends the request and checks the answer against the expected cell */
    public SweepResponse call(String identity, SweepRequest request, Cell expected) {
        SweepResponse response = send(identity, request);
        expect(response, expected);
        return response;
    }

    /** Checks the answer is a 2xx */
    public SweepResponse expectOk(SweepResponse response) {
        return expect(response, Cell.OK);
    }

    /**
     * Checks the answer against the expected cell (status and detail); records a failure otherwise. An answer of the
     * entry's own handler that shows a known fault of the call passes and ends the case's step.
     */
    public SweepResponse expect(SweepResponse response, Cell expected) {
        engine.expectAnswer(response, expected, null);
        return response;
    }

    /**
     * Checks the answer (or the value at the JSON path, RestAssured syntax) holds each name as a value of its own: a
     * JSON string equal to it, or holding it between characters that are not part of a name (so {@code sweep-x-a} is
     * not found in {@code sweep-x-a-da})
     */
    public void expectRows(SweepResponse response, String at, String... names) {
        for (String name : names) {
            String missing = SweepEngine.rowMissing(response, at, name);
            String what = "the answer" + (at == null ? "" : " at " + at) + " holds " + name;
            engine.check(missing == null, what, what + (missing == null || missing.isEmpty() ? "" : " (" + missing + ")"),
                    response);
        }
    }

    /** Checks the condition; on false records the failure, with the answer it is about */
    public boolean check(boolean condition, String expectation, SweepResponse about) {
        return engine.check(condition, expectation, expectation, about);
    }

    /** Checks the two are equal; the read-back of a write */
    public boolean checkEquals(Object expected, Object actual, String what, SweepResponse about) {
        return engine.check(Objects.equals(expected, actual), what, what + " is " + expected + " (read back: " + actual
                + ")", about);
    }

    /** Records a failure about the answer (a {@code check} known fault that names the text allows it) */
    public void fail(SweepResponse about, String expectation) {
        engine.check(false, expectation, expectation, about);
    }

    /** Records a failure */
    public void fail(String message) {
        engine.fail(message);
    }

    // ------------------------------------------------------------------------------------------- //
    // Rows, background work
    // ------------------------------------------------------------------------------------------- //

    /** Registers a fixture row's id under its declared name */
    public void putRow(String name, Object id) {
        if (!group.rows().contains(name)) {
            throw new IllegalArgumentException(name + " is not a row " + group.getClass().getSimpleName()
                    + " declares: " + group.rows());
        }
        rows.put(name, id);
    }

    /** The id of a fixture row */
    public Object row(String name) {
        Object id = rows.get(name);
        if (id == null) {
            throw new IllegalStateException("The fixture has no row " + name + " (rows: " + rows.keySet() + ")");
        }
        return id;
    }

    public boolean hasRow(String name) {
        return rows.containsKey(name);
    }

    public Map<String, Object> rows() {
        return rows;
    }

    /**
     * Marks the current write as one whose background work runs after its answer (it queues mail, changes a message
     * status, publishes): the settle point awaits the sweep's mail with the subject out of the queue, waits for the
     * background to be quiet for two seconds, and holds the background log since this write to the rule. A write
     * that queues no mail passes the subject null and is held to the quiet wait only. The entry declares
     * {@code "settle": true}; a case that marks a settle point for an entry that does not (or the reverse) fails.
     */
    public void settle(String mailSubject) {
        engine.settle(mailSubject);
    }

    /** Holds the background log (job threads, no request id) from the time to now to the rule of the current entry */
    public void backgroundWindow(long fromMillis) {
        engine.backgroundWindow(fromMillis, System.currentTimeMillis());
    }
}
