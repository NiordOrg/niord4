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

package org.niord.core.util;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import org.slf4j.Logger;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The poll loop of a listener on a JMS topic that subscribes again when its subscription is lost.
 * <p>
 * The status listeners ({@code MailingListMessageListener}, {@code TwitterMessageListener},
 * {@code SecomMessageStatusListener}) each run one long-lived loop on a single-thread executor, started when the
 * application is ready. Before this loop, an exception from {@code receive()} (the broker connection lost, the
 * consumer closed under it) ended the loop inside the executor's discarded {@code Future}: nothing was logged and no
 * later status change was received until the application restarted.
 * <p>
 * The subscription is not durable: status changes broadcast while the listener is subscribing again are not
 * received. The mailing lists do not depend on them (their outbox, {@code MailingListStatusChangeOutbox}, drained
 * once a minute too), SECOM has its reconciliation sweep, and a tweet's status change is lost (PROM5 in
 * be/docs/potential-errors.md).
 */
public final class JmsTopicLoop {

    private JmsTopicLoop() {
    }

    /**
     * Subscribes to the topic and hands every message to {@code onMessage}, until the consumer is closed or the
     * listener is stopped.
     * <p>
     * When {@code receive()} answers null (the consumer or its context was closed), the loop ends. When subscribing
     * or receiving fails, the loop ends if the listener is stopped; otherwise it logs one ERROR with the cause, waits
     * {@code reconnectDelayMillis} and subscribes again, and goes on trying (logging each further failed attempt at
     * DEBUG only) until a subscription succeeds, which is logged at INFO. An interrupt while waiting ends the loop.
     * A failure of {@code onMessage} is logged and the loop goes on with the next message.
     *
     * @param connectionFactory    the connection factory
     * @param topic                the name of the topic
     * @param stopped              whether the listener has been stopped
     * @param reconnectDelayMillis how long to wait before subscribing again
     * @param onMessage            handles one received message
     * @param log                  the listener's logger
     */
    public static void consume(
            ConnectionFactory connectionFactory,
            String topic,
            BooleanSupplier stopped,
            long reconnectDelayMillis,
            Consumer<jakarta.jms.Message> onMessage,
            Logger log) {

        boolean failing = false;
        while (true) {
            try (JMSContext context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                JMSConsumer consumer = context.createConsumer(context.createTopic(topic));
                if (failing) {
                    log.info("Subscribed to the topic {} again", topic);
                    failing = false;
                }
                while (true) {
                    jakarta.jms.Message message = consumer.receive();
                    if (message == null) {
                        return;
                    }
                    try {
                        onMessage.accept(message);
                    } catch (RuntimeException e) {
                        log.error("Failed processing JMS message " + message, e);
                    }
                }
            } catch (Exception e) {
                // Exception, not only JMSRuntimeException: a client may also pass on a checked exception unchecked
                if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (!failing) {
                    log.error(String.format("Lost the subscription to the topic %s; subscribing again in %s",
                            topic, reconnectDelayMillis >= 1000 ? reconnectDelayMillis / 1000 + " s"
                                    : reconnectDelayMillis + " ms"), e);
                    failing = true;
                } else {
                    log.debug("Subscribing to the topic {} failed again: {}", topic, e.toString());
                }
            }
            try {
                Thread.sleep(reconnectDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
