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

/**
 * How the sweep calls one entry. The engine runs, in this order:
 * <ol>
 *   <li><b>The cells</b> of the entry (see {@code EndpointSweepITTest}): each with the request {@link #request}
 *       makes for the cell's row, sent as the cell's identity. A read's allowed cell is answered 2xx; a refused cell
 *       must match status and detail. A write's allowed cell other than the sweep's own runs {@link #allowed}.</li>
 *   <li><b>The sweep</b> as {@link #as} (default: the least privileged identity the guard admits): for a read, the
 *       request without a row, answered 2xx with what the entry's {@code expect} names, unless {@link #sweep} does it
 *       itself; for a write and an upload, {@link #sweep} (write, read back, clean up, read gone).</li>
 *   <li><b>With {@code -Dniord.sweep.full=true} only</b>: {@link #probes} (unknown ids, malformed values, empty
 *       bodies) and {@link #variants} (languages, pages, dates, alias paths).</li>
 * </ol>
 * Every request goes through {@link SweepContext#send}, which checks that it reaches the entry's handler and holds
 * its log to the rule.
 */
public final class SweepCase {

    /** The request a cell sends; {@code row} is the cell's fixture row after '@', or null */
    @FunctionalInterface
    public interface Requests {
        SweepRequest request(SweepContext ctx, String row) throws Exception;
    }

    /** A step run as an identity */
    @FunctionalInterface
    public interface Step {
        void run(SweepContext ctx, SweepIdentity as) throws Exception;
    }

    /** An allowed write cell: the write as the identity on the row, undone in {@code finally} */
    @FunctionalInterface
    public interface CellStep {
        void run(SweepContext ctx, SweepIdentity as, String row) throws Exception;
    }

    Requests request;
    Step sweep;
    Step probes;
    Step variants;
    CellStep allowed;
    String as;

    private SweepCase(Requests request) {
        this.request = request;
    }

    /** A case whose cells send the request */
    public static SweepCase of(Requests request) {
        return new SweepCase(request);
    }

    /** The read or write sweep (required for writes and uploads) */
    public SweepCase sweep(Step sweep) {
        this.sweep = sweep;
        return this;
    }

    /** Bad-input probes, run only in the full run */
    public SweepCase probes(Step probes) {
        this.probes = probes;
        return this;
    }

    /** Variants (languages, pages, dates, aliases), run only in the full run */
    public SweepCase variants(Step variants) {
        this.variants = variants;
        return this;
    }

    /** An allowed write cell other than the sweep's own */
    public SweepCase allowed(CellStep allowed) {
        this.allowed = allowed;
        return this;
    }

    /** The identity of the sweep, when it is not the least privileged one the guard admits */
    public SweepCase as(String identity) {
        SweepIdentity.parse(identity);
        this.as = identity;
        return this;
    }
}
