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
package org.niord.core.user;

import org.infinispan.configuration.cache.Configuration;
import org.infinispan.eviction.EvictionStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the bounds of the ticket cache. The ticket endpoint answers anonymous callers too, so the cache
 * must be bounded by count and not only by the one-minute lifespan; otherwise sustained requests grow it
 * without limit. Only the configuration is read — no cache is started.
 */
class TicketServiceCacheBoundTest {

    private final Configuration configuration = new TicketService().createCacheConfiguration();

    @Test
    void theCacheHoldsAtMostTenThousandTicketsAndEvictsWhenFull() {
        assertEquals(10_000L, configuration.memory().maxCount());
        assertEquals(EvictionStrategy.REMOVE, configuration.memory().whenFull());
    }

    @Test
    void aTicketStillExpiresAfterOneMinute() {
        assertEquals(60_000L, configuration.expiration().lifespan());
    }
}
