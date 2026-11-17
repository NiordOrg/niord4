/*
 * Copyright 2024 Danish Maritime Authority.
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

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small JMS produce/consume helper for the integration tests, wrapping a single {@link JMSContext}
 * obtained from the application's own {@link ConnectionFactory} bean.
 *
 * <p>Under the Spring port the broker is an <em>embedded</em> ActiveMQ Artemis instance
 * ({@code spring.artemis.mode=embedded}); the application injects the in-VM {@link ConnectionFactory}
 * that fronts it (see {@code MessageService} and {@code MailingListMessageListener}). By opening this
 * client's context from that <em>same</em> bean, a test subscribes to and publishes on exactly the
 * broker the application uses — no second broker, no external AMQP acceptor. The original Quarkus test
 * used a stand-alone qpid-jms/AMQP client against a Testcontainers Artemis; here we instead consume from
 * the app's embedded broker via its Spring-provided {@code ConnectionFactory}, which is the contract the
 * live listeners actually rely on.
 *
 * <p>It publishes a status-update {@code MapMessage} in the exact shape that
 * {@code MessageService.sendStatusUpdate(...)} broadcasts ({@code ID}/{@code UID}/{@code STATUS}/
 * {@code PREV_STATUS}) so a test can round-trip it back.
 *
 * <p>Framework-neutral on the consumer side — plain {@code jakarta.jms}; the only injected dependency is
 * the standard {@link ConnectionFactory} bean, which survives the broker swap. {@link #close()} closes the
 * context (and therefore any consumers created from it); use it in a try-with-resources block so
 * subscriptions never leak between tests.
 */
public final class JmsTestClient implements AutoCloseable {

    private final JMSContext context;

    private JmsTestClient(ConnectionFactory connectionFactory) {
        // AUTO_ACKNOWLEDGE mirrors the application's own consumer/producer contexts.
        this.context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
    }

    /**
     * Opens a client against the application's embedded broker via its injected {@link ConnectionFactory}
     * bean — the same broker {@code MessageService}/{@code MailingListMessageListener} use.
     */
    public static JmsTestClient using(ConnectionFactory connectionFactory) {
        return new JmsTestClient(connectionFactory);
    }

    /** Creates a non-durable subscriber on the given topic. Only receives messages published afterwards. */
    public JMSConsumer subscribe(String topicName) {
        return context.createConsumer(context.createTopic(topicName));
    }

    /**
     * Publishes a status-update map message identical in shape to {@code MessageService.sendStatusUpdate}.
     * Sending a {@code Map} body produces a {@link MapMessage}, exactly as the application does.
     */
    public void publishStatusUpdate(String topicName, Integer id, String uid, String status, String prevStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ID", id);
        body.put("UID", uid);
        body.put("STATUS", status);
        body.put("PREV_STATUS", prevStatus);
        publishMap(topicName, body);
    }

    /** Publishes an arbitrary map body (delivered as a {@link MapMessage}). */
    public void publishMap(String topicName, Map<String, Object> body) {
        context.createProducer().send(context.createTopic(topicName), body);
    }

    /** Publishes a plain text message (delivered as a {@code TextMessage}, i.e. NOT a {@code MapMessage}). */
    public void publishText(String topicName, String text) {
        context.createProducer().send(context.createTopic(topicName), text);
    }

    /**
     * Blocks up to {@code timeoutMillis} for a message and returns it as a {@link MapMessage}, or
     * {@code null} if the timeout elapses with nothing delivered.
     */
    public static MapMessage receiveMapMessage(JMSConsumer consumer, long timeoutMillis) {
        return (MapMessage) consumer.receive(timeoutMillis);
    }

    /** Blocks up to {@code timeoutMillis} for any message; returns {@code null} on timeout. */
    public static Message receive(JMSConsumer consumer, long timeoutMillis) {
        return consumer.receive(timeoutMillis);
    }

    @Override
    public void close() {
        context.close();
    }
}
