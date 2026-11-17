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
package org.niord.secom.service;

import org.junit.jupiter.api.Test;
import org.niord.secom.pki.McpSecurityService;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the store-watch failure-alert dedup contract: the in-memory marker
 * advances only once the alert mail is durably committed (or no recipients are
 * configured), so an alert that cannot be queued is retried on the next poll instead of
 * being suppressed for the lifetime of the failure. The happy paths (rotation +
 * confirmation mail, one alert per distinct failure) are covered by
 * {@code SecomStoreWatchITTest} against the real database.
 */
public class SecomStoreWatchServiceTest {

    /** A PKI stub whose stores are file-backed and always fail to load */
    private static McpSecurityService failingPki(String message) {
        return new McpSecurityService(new DefaultResourceLoader()) {
            @Override
            public boolean hasFileBackedStores() {
                return true;
            }

            @Override
            public McpSecurityService.Rotation reloadIfChanged() {
                throw new IllegalStateException(message);
            }
        };
    }

    @Test
    public void uncommittedFailureAlertIsRetriedOnTheNextPoll() {
        AtomicInteger attempts = new AtomicInteger();
        SecomTxRunner dbDown = new SecomTxRunner() {
            @Override
            public <T> T call(Supplier<T> work) {
                attempts.incrementAndGet();
                throw new IllegalStateException("db down");
            }
        };
        SecomStoreWatchService watch = new SecomStoreWatchService(failingPki("broken store"), dbDown);
        watch.alertEmails = "ops@example.com";

        watch.watch();
        assertNull(watch.lastFailure, "an uncommitted alert must not advance the dedup marker");
        assertEquals(1, attempts.get());

        watch.watch();
        assertEquals(2, attempts.get(), "the alert is retried while it cannot be committed");
        assertNull(watch.lastFailure);
    }

    @Test
    public void blankRecipientsRecordTheFailureWithoutTouchingTheDatabase() {
        SecomTxRunner forbidden = new SecomTxRunner() {
            @Override
            public <T> T call(Supplier<T> work) {
                throw new AssertionError("no transaction expected with blank recipients");
            }
        };
        SecomStoreWatchService watch = new SecomStoreWatchService(failingPki("broken store"), forbidden);
        watch.alertEmails = "";

        watch.watch();
        assertEquals("broken store", watch.lastFailure,
                "with no recipients there is nothing to retry - the marker advances so the error logs once");

        // The dedup branch: no repeated error log, still no transaction
        watch.watch();
        assertEquals("broken store", watch.lastFailure);
    }
}
