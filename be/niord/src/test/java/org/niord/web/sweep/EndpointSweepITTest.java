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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.stream.Stream;

/**
 * The endpoint sweep: every entry of the endpoint table ({@code src/test/resources/endpoints/*.json}) outside a
 * context of its own, called over HTTP as the table says, in the shared integration-test context and database
 * ({@link SweepRun} says in which order and how it is selected).
 *
 * <ul>
 *   <li>{@code -Dniord.sweep.groups=sources,messages} (or the environment variable {@code NIORD_SWEEP_GROUPS}) runs
 *       only those groups;</li>
 *   <li>{@code -Dniord.sweep.full=true} (or {@code NIORD_SWEEP_FULL=true}) adds the probes, the variants and every
 *       role cell the annotation decides; the default run, which CI pays for, leaves them out.</li>
 * </ul>
 *
 * <p>The class declares nothing that would give it an application context of its own (no properties, no spy beans,
 * no {@code @DirtiesContext}): it runs in the context every {@code ITTest} shares.
 */
class EndpointSweepITTest extends ITTest {

    @Autowired
    ApplicationContext applicationContext;

    @AfterAll
    static void detachTheLog() {
        SweepRun.detachTheLog();
    }

    @TestFactory
    Stream<DynamicNode> sweep() {
        return SweepRun.nodes(port, applicationContext, ITTest::accessToken, null);
    }
}
