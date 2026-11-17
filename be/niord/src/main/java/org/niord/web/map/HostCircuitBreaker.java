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
package org.niord.web.map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers whether a tile host is failing, so an outage costs one quick placeholder per image instead of a
 * timeout per tile, and the host is not hammered while it is down.
 *
 * <p>After {@code threshold} consecutive failed requests the breaker opens: for {@code openMillis} no request
 * goes to the host. When the period ends, requests are let through again; the first failure re-opens the
 * breaker at once, the first success closes it. Opening and closing are logged once each at INFO; the WARN that
 * names the failure is written once per open period, however many images fail during it.
 *
 * <p>Thread-safe; the times are epoch milliseconds from the {@link TileFetcher}'s clock.
 */
final class HostCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(TileFetcher.class);

    private final String host;
    private final int threshold;
    private final long openMillis;

    private int consecutiveFailures;
    private boolean open;
    private long openUntil;

    HostCircuitBreaker(String host, int threshold, long openMillis) {
        this.host = host;
        this.threshold = threshold;
        this.openMillis = openMillis;
    }

    /** Whether a request may go to the host now. */
    synchronized boolean allowsRequest(long now) {
        return !open || now >= openUntil;
    }

    synchronized void recordSuccess() {
        consecutiveFailures = 0;
        if (open) {
            open = false;
            log.info("Tile host {} answers again; tile requests resume", host);
        }
    }

    synchronized void recordFailure(long now, Exception cause) {
        consecutiveFailures++;
        boolean pausing = open && now < openUntil;
        if (consecutiveFailures >= threshold && !pausing) {
            open = true;
            openUntil = now + openMillis;
            log.info("Tile host {} failed {} times in a row; no tile requests for {} s",
                    host, consecutiveFailures, openMillis / 1000);
            log.warn("Tile host {} is failing; map images without cached tiles get the placeholder: {}",
                    host, cause.toString());
        }
    }
}
