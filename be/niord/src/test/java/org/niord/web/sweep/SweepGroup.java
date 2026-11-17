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

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One group of the endpoint sweep: the handlers of one table file, its fixture and its cases.
 *
 * <p>{@code EndpointSweepITTest} finds every concrete class of this package that implements this interface (a
 * classpath scan; no registry to edit) and creates it with its no-argument constructor. A group must not be a Spring
 * component: the integration tests' application scans {@code org.niord}, test classes included.
 *
 * <p>The sweep runs every group's {@link #setUp} first, then the reads, the writes and the global entries of every
 * group, the settle point, every group's {@link #tearDown}, and the summaries. {@code SourcesSweepGroup} is the worked
 * example.
 */
public interface SweepGroup {

    /** The group's name: its table file ({@code endpoints/<name>.json}), the prefix of its rows and request ids */
    String name();

    /**
     * The fixture rows the group declares, by name ({@code sweep-<group>-...}). An entry's {@code expect.rows} may
     * name only these; a read's answer must hold the name.
     */
    Set<String> rows();

    /**
     * Makes the fixture: each declared row, through the API as the identity whose view a read shows, looked up by
     * name first so that a second run makes nothing twice. Registers each row's id with {@link SweepContext#putRow}.
     */
    void setUp(SweepContext ctx) throws Exception;

    /**
     * The context the group's entries are swept in: null for the main sweep ({@code EndpointSweepITTest}), else the
     * entries' {@code context} (for {@code "secom"}, a class of the SECOM context runs {@link SweepRun#nodes}). Two
     * groups may share a name (a table file) when their contexts differ.
     */
    default String context() {
        return null;
    }

    /** Registers the group's cases, each under the name an entry's {@code case} gives after the '#' */
    void cases(SweepCases cases);

    /**
     * Removes the fixture's rows and reads each one gone, and answers the names of those still there: the run's
     * summary fails on them. Rows that stay by design are {@link #leftByDesign()}, not answered here.
     */
    default List<String> tearDown(SweepContext ctx) throws Exception {
        return List.of();
    }

    /**
     * What the group's run leaves in the database by design, because no endpoint removes it, to the reason (for
     * example the batch instance of each upload). The run's summary prints it; the group's record names it too.
     */
    default Map<String, String> leftByDesign() {
        return Map.of();
    }

    /**
     * The database tables the group's rows (and their description rows) live in. After every group's tear-down the
     * run compacts them ({@code VACUUM FULL}), so that the free space the sweep's deletes leave does not change the
     * physical order in which the tests after it read rows they make and update there: a list the product reads
     * without an order (a source's descriptions) otherwise comes back in another order than without the sweep.
     */
    default Set<String> tables() {
        return Set.of();
    }
}
