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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.niord.test.JmsFaults;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MAILLIST2 (iii) on the SECOM side: {@code SecomMessageStatusListener.run} had the same uncaught poll loop as the
 * mailing-list listener, submitted with its {@code Future} discarded. Written on 2026-11-12; a regression test of
 * the fix now.
 */
class SecomMessageStatusListenerFaultTest {

    /**
     * When {@code receive()} throws, the loop used to end silently: no S-124 dataset was made or withdrawn for any
     * later status change until the next reconcile or restart, and nothing was logged. Now it logs one ERROR and
     * subscribes again.
     */
    @Test
    void theListenerLoopSubscribesAgainAfterTheBrokerConnectionFails() throws Exception {
        AtomicInteger receives = new AtomicInteger();
        SecomMessageStatusListener listener = new SecomMessageStatusListener(null,
                JmsFaults.consumerThatFailsOnFirstReceive(receives));
        listener.reconnectDelayMillis = 10;
        ListAppender<ILoggingEvent> log = JmsFaults.captureLog(SecomMessageStatusListener.class);
        try {
            listener.onStart();
            JmsFaults.awaitFirstReceiveThenWatch(receives, Duration.ofMillis(1500));
        } finally {
            listener.onStop();
            JmsFaults.stopCapture(SecomMessageStatusListener.class, log);
        }

        assertTrue(receives.get() >= 2, "the loop subscribed again after the failed receive: " + receives.get());
        assertEquals(1, log.list.stream().filter(e -> e.getLevel() == Level.ERROR).count(),
                "and logged one ERROR: " + JmsFaults.warningsAndErrors(log));
    }
}
