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
package org.niord.core.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run of pending scheduled mails waits for its sender threads only so long: a sender that does not finish, as
 * one stuck on a silent SMTP server would, no longer holds the run, and with it the mail queue, for good.
 */
class ScheduledMailSendRunLimitTest {

    private final ScheduledMailService service = new ScheduledMailService(null, null);

    @AfterEach
    void shutDownTheSenderThreads() throws InterruptedException {
        service.managedExecutor.shutdownNow();
        assertTrue(service.managedExecutor.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void aRunThatPassesItsLimitReturnsAndNamesTheMailsLeft() {
        service.sendRunLimit = Duration.ofMillis(200);
        CountDownLatch release = new CountDownLatch(1);
        Callable<ScheduledMail> quick = () -> null;
        Callable<ScheduledMail> stuck = () -> {
            // Ignores the interrupt, as socket I/O on a platform thread does
            while (true) {
                try {
                    if (release.await(10, TimeUnit.SECONDS)) {
                        return null;
                    }
                } catch (InterruptedException ignored) {
                    // keeps waiting
                }
            }
        };
        try {
            List<Integer> unfinished = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> service.runSenders(List.of(1, 2), List.of(quick, stuck)),
                    "the run returns at its limit instead of waiting for the stuck sender");
            assertEquals(List.of(2), unfinished, "the run names the mail whose sender did not finish");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aRunWithinItsLimitLeavesNoMail() {
        List<Integer> unfinished = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> service.runSenders(List.of(1, 2), List.<Callable<ScheduledMail>>of(() -> null, () -> null)));
        assertEquals(List.of(), unfinished);
    }
}
