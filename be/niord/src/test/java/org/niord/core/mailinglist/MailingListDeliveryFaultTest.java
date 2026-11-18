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
package org.niord.core.mailinglist;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.message.Message;
import org.niord.test.JmsFaults;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fault injection on two steps of the status-change mail path that used to fail silently, as unit tests, both found
 * by the error hunt of 2026-11-12: a trigger's message filter that throws, and the listener's poll loop when the
 * broker connection fails. Both are regression tests of the fix now.
 */
class MailingListDeliveryFaultTest {

    /**
     * A trigger filter that throws for a message ({@code msg.getAreas().get(0)} on a message with no
     * area, an {@code IndexOutOfBoundsException} inside the script) used to read as "no match", with nothing logged:
     * the subscribers got no mail and the administrator who wrote the filter was never told it failed. Now
     * {@code matches} throws {@link IllegalArgumentException} naming the filter, the message and the cause, which the
     * trigger execution records. The same filter still matches a message with an area.
     */
    @Test
    void aTriggerFilterThatThrowsFailsWithTheFilterAndTheCause() throws Exception {
        String expression = "msg.getAreas().get(0).getId() > 0";
        MessageFilter filter = MessageFilter.getInstance(expression);

        Message withArea = new Message();
        Area area = new Area();
        area.setId(42);
        withArea.getAreas().add(area);
        assertTrue(filter.matches(withArea), "control: the filter matches a message with an area");

        Message withoutArea = new Message();
        withoutArea.setUid("uid-without-area");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> filter.matches(withoutArea),
                "the filter throws for a message with no area, and the error is not read as no match");
        assertTrue(e.getMessage().contains(expression), "the error names the filter: " + e.getMessage());
        assertTrue(e.getMessage().contains("uid-without-area"), "and the message: " + e.getMessage());
        assertTrue(e.getMessage().contains("out of bounds"), "and the cause: " + e.getMessage());
        assertInstanceOf(IndexOutOfBoundsException.class, e.getCause(), "the cause is kept");
    }

    /** A filter whose result is not {@code true} (false, null, undefined, a string, a number) is no match, not an error. */
    @Test
    void aFilterThatAnswersNoBooleanIsNoMatch() throws Exception {
        Message message = new Message();
        message.setUid("uid-1");
        for (String expression : List.of("false", "null", "undefined", "'yes'", "1", "msg.getUid()")) {
            assertFalse(MessageFilter.getInstance(expression).matches(message), expression + " is no match");
        }
        assertTrue(MessageFilter.getInstance("msg.getUid() == 'uid-1'").matches(message), "control: true matches");
    }

    /**
     * A filter that reads a property of a value that is null, the shape the stored triggers use for a message with no
     * such promulgation ({@code msg.promulgation('navtex').promulgate && ...}), is no match, as before, and logs
     * nothing above DEBUG: otherwise every status change of a message without that promulgation would leave an error
     * record. A read on undefined (a misspelt property) and a function that does not exist are errors.
     */
    @Test
    void aFilterThatReadsAPropertyOfNullIsNoMatchButAMisspeltPropertyIsAnError() throws Exception {
        Message withoutNavtex = new Message();
        withoutNavtex.setUid("uid-2");
        ListAppender<ILoggingEvent> log = JmsFaults.captureLog(MessageFilter.class);
        try {
            assertFalse(MessageFilter.getInstance(
                            "msg.promulgation('navtex').promulgate && msg.promulgation('navtex').useTransmitter('Baltico')")
                    .matches(withoutNavtex), "a property of a missing promulgation is no match");
            assertFalse(MessageFilter.getInstance("msg.promulgation('navtex').useTransmitter('Baltico')")
                    .matches(withoutNavtex), "a function of a missing promulgation is no match");
        } finally {
            JmsFaults.stopCapture(MessageFilter.class, log);
        }
        assertTrue(JmsFaults.warningsAndErrors(log).isEmpty(),
                "and nothing is logged: " + JmsFaults.warningsAndErrors(log));

        assertThrows(IllegalArgumentException.class,
                () -> MessageFilter.getInstance("msg.messageSeriez.seriesId == 'dma-nw'").matches(withoutNavtex),
                "a misspelt property reads undefined: an error");
        assertThrows(IllegalArgumentException.class,
                () -> MessageFilter.getInstance("msg.noSuchMethod()").matches(withoutNavtex),
                "a function that does not exist: an error");
    }

    /**
     * The listener's poll loop used to have no catch, and {@code onStart} submits it to an executor
     * and discards the {@code Future}: when {@code receive()} threw (the broker connection lost, the consumer closed
     * under it), the loop ended with nothing logged, and no later status change was received until the application
     * restarted. Now the loop ({@link org.niord.core.util.JmsTopicLoop}) logs one ERROR and subscribes again. The
     * outbox pass {@code onStart} runs first finds no row here, and logs nothing.
     */
    @Test
    void theListenerLoopSubscribesAgainAfterTheBrokerConnectionFails() throws Exception {
        AtomicInteger receives = new AtomicInteger();
        MailingListMessageListener listener = new MailingListMessageListener(null, null,
                new MailingListMessageListenerTest.RecordingOutbox(), JmsFaults.consumerThatFailsOnFirstReceive(receives));
        listener.reconnectDelayMillis = 10;
        ListAppender<ILoggingEvent> log = JmsFaults.captureLog(MailingListMessageListener.class);
        try {
            listener.onStart();
            JmsFaults.awaitFirstReceiveThenWatch(receives, Duration.ofMillis(1500));
        } finally {
            listener.onStop();
            JmsFaults.stopCapture(MailingListMessageListener.class, log);
        }

        assertTrue(receives.get() >= 2, "the loop subscribed again after the failed receive: " + receives.get());
        assertEquals(1, log.list.stream().filter(e -> e.getLevel() == Level.ERROR).count(),
                "and logged one ERROR: " + JmsFaults.warningsAndErrors(log));
    }
}
