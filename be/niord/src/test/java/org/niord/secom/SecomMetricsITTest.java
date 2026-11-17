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

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Message;
import org.niord.secom.SecomMetrics;
import org.niord.secom.model.SecomSubscription;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ITs for the SECOM Micrometer gauges: with the integration enabled, the scheduled refresh
 * publishes the dataset/subscription/node/outbox counts to the meter registry (the absence
 * of the meters on disabled deployments is covered by {@code SecomDisabledITTest}).
 */
class SecomMetricsITTest extends SecomITTest {

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    SecomMetrics secomMetrics;

    @Test
    void gaugesReflectTheSecomStores() throws Exception {
        Message published = Tx.requiringNew().call(() -> publishNw("secom-metrics-" + uniq(), 55.5, 11.1));
        assertNotNull(awaitDatasetForMessage(published.getUid()), "the listener creates the dataset");
        Tx.requiringNew().run(() -> {
            SecomSubscription template = new SecomSubscription();
            template.setDataProductType("S124");
            subscriptionService.subscribe(CLIENT_MRN, template);
        });

        secomMetrics.refresh();

        assertTrue(meterRegistry.get("niord.secom.datasets").tag("status", "active").gauge().value() >= 1,
                "the committed dataset shows up in the active-datasets gauge");
        assertTrue(meterRegistry.get("niord.secom.subscriptions").gauge().value() >= 1);
        assertTrue(meterRegistry.get("niord.secom.nodes").gauge().value() >= 1);
        assertTrue(meterRegistry.get("niord.secom.outbox").tag("status", "pending").gauge().value() >= 1,
                "the subscribe confirmation notification is PENDING in the outbox");
        // The full status axes are registered up front (fixed enum tags)
        assertNotNull(meterRegistry.get("niord.secom.datasets").tag("status", "cancelled").gauge());
        assertNotNull(meterRegistry.get("niord.secom.outbox").tag("status", "failed").gauge());
    }
}
