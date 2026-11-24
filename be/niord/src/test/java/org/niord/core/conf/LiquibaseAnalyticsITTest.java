/*
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
package org.niord.core.conf;

import liquibase.Scope;
import liquibase.analytics.LiquibaseAnalyticsListener;
import liquibase.analytics.configuration.AnalyticsArgs;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Liquibase's own usage analytics are off. The library has them on by default: at every start it fetches its
 * analytics configuration from the vendor's host and may then send usage data there. niord-base's core defaults
 * switch them off ({@code spring.liquibase.analytics-enabled}), so a deployable that sets nothing has them off and
 * its start contacts no third party.
 */
class LiquibaseAnalyticsITTest extends ITTest {

    @Autowired
    SpringLiquibase liquibase;

    @Test
    void liquibaseRunsWithItsAnalyticsOff() throws Exception {
        assertEquals(Boolean.FALSE, liquibase.getAnalyticsEnabled(),
                "the core defaults must switch Liquibase's analytics off on the Liquibase that runs at start");

        // SpringLiquibase runs the update in a Liquibase scope that holds its flag under the key of
        // AnalyticsArgs.ENABLED; that is the value the analytics listener reads before it calls out. Read here in a
        // scope made the same way, it must be off, and the listener must say so (with the flag off it decides
        // without a call to the network).
        Map<String, Object> scope = Map.of(AnalyticsArgs.ENABLED.getKey(), liquibase.getAnalyticsEnabled());
        assertEquals(Boolean.FALSE, Scope.child(scope, () -> AnalyticsArgs.ENABLED.getCurrentValue()),
                "Liquibase's own setting liquibase.analytics.enabled must read false while the update runs");
        assertFalse(Scope.child(scope, () -> new LiquibaseAnalyticsListener().isEnabled()),
                "Liquibase's analytics listener must find its analytics off");
    }
}
