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
package org.niord.secom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.niord.web.sweep.SweepRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.stream.Stream;

/**
 * The endpoint sweep of the SECOM administration interface (the entries of context {@code secom} in
 * {@code endpoints/system.json}): the same engine as {@code EndpointSweepITTest}, in the SECOM context this class's
 * base starts, over the same database.
 *
 * <p>JUnit runs {@code SecomITTest}'s per-test wipe once, before the factory, and its removal of the published
 * messages once, after every dynamic test. Between the two, the factory publishes the one message the group's fixture
 * builds on (its dataset, a subscription, an outbox entry: {@code SystemSecomSweepGroup}); the base class removes it,
 * with its dataset and outbox entries, after the sweep.
 */
class SecomEndpointSweepITTest extends SecomITTest {

    @Autowired
    ApplicationContext applicationContext;

    @AfterAll
    static void detachTheLog() {
        SweepRun.detachTheLog();
    }

    @TestFactory
    Stream<DynamicNode> sweep() {
        Tx.requiringNew().call(() -> publishNw("sweep-system-secom-message", 55.4321, 11.4321));
        return SweepRun.nodes(port, applicationContext, ITTest::accessToken, "secom");
    }
}
