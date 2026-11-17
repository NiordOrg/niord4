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
package org.niord.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSRuntimeException;
import org.awaitility.Awaitility;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Faults of the embedded broker as a status listener sees them, for the unit tests of the listeners' poll loops:
 * a {@link ConnectionFactory} whose consumer's first {@code receive()} throws {@link JMSRuntimeException} (the
 * connection to the broker lost) and whose later calls would block like a live consumer, counting the calls; and a
 * capture of what a class logs. Mockito and Logback come with {@code spring-boot-starter-test}.
 */
public final class JmsFaults {

    private JmsFaults() {
    }

    /**
     * A connection factory whose consumer throws on the first {@code receive()} and afterwards waits a second and
     * answers a message-less wait would (never reached by a loop that survives the failure, since the test reads the
     * count after a short wait)
     *
     * @param receiveCalls counts the calls of {@code receive()}
     * @return the connection factory
     */
    public static ConnectionFactory consumerThatFailsOnFirstReceive(AtomicInteger receiveCalls) {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        JMSContext context = mock(JMSContext.class);
        JMSConsumer consumer = mock(JMSConsumer.class);
        when(factory.createContext(anyInt())).thenReturn(context);
        when(context.createConsumer(any())).thenReturn(consumer);
        when(consumer.receive()).thenAnswer(inv -> {
            if (receiveCalls.incrementAndGet() == 1) {
                throw new JMSRuntimeException("IT: the connection to the broker was lost");
            }
            Thread.sleep(60_000); // a live consumer waits for the next status change
            return null;
        });
        return factory;
    }

    /**
     * Waits until the poll loop has made its first {@code receive()} (at most ten seconds: the listener submits the
     * loop to an executor, which on a loaded build machine may start it late), then watches the loop for the given
     * time, in which a loop that survives the failure would call {@code receive()} again. A fixed sleep alone made
     * the count 0, not 1, whenever the executor had not started the loop within it.
     *
     * @param receiveCalls the count of {@link #consumerThatFailsOnFirstReceive}
     * @param watch how long to watch for a second {@code receive()} after the first
     */
    public static void awaitFirstReceiveThenWatch(AtomicInteger receiveCalls, Duration watch) throws InterruptedException {
        Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20))
                .until(() -> receiveCalls.get() >= 1);
        Thread.sleep(watch.toMillis());
    }

    /** Starts capturing what the logger of the given class logs. */
    public static ListAppender<ILoggingEvent> captureLog(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** Stops capturing. */
    public static void stopCapture(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        appender.stop();
    }

    /** The captured events at WARN or above, as text. */
    public static List<String> warningsAndErrors(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }
}
