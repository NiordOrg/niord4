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

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the outbox backoff schedule: the configured delay list is walked per
 * attempt and its last value repeats for all further attempts.
 */
public class SecomOutboxBackoffTest {

    private static long minutesFromNow(Date date) {
        return Math.round((date.getTime() - System.currentTimeMillis()) / 60_000.0);
    }

    @Test
    public void backoffWalksTheConfiguredDelayListAndRepeatsTheLastValue() {
        SecomOutboxService service = new SecomOutboxService(null);
        service.backoffMinutes = "1,5,30,120,720";

        assertEquals(1, minutesFromNow(service.nextAttemptTime(1)));
        assertEquals(5, minutesFromNow(service.nextAttemptTime(2)));
        assertEquals(30, minutesFromNow(service.nextAttemptTime(3)));
        assertEquals(120, minutesFromNow(service.nextAttemptTime(4)));
        assertEquals(720, minutesFromNow(service.nextAttemptTime(5)));
        assertEquals(720, minutesFromNow(service.nextAttemptTime(6)), "the last delay repeats");
        assertEquals(720, minutesFromNow(service.nextAttemptTime(42)), "the last delay repeats");
    }

    @Test
    public void blankConfigurationFallsBackToOneMinute() {
        SecomOutboxService service = new SecomOutboxService(null);
        service.backoffMinutes = " ";
        assertEquals(1, minutesFromNow(service.nextAttemptTime(3)));
    }
}
