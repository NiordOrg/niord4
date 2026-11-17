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
package org.niord.core.message;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.MapMessage;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.JmsTestClient;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Framework-neutral integration tests that actually <em>publish to and consume from</em> the
 * application's ActiveMQ Artemis broker (the broker was previously started but never exercised).
 *
 * <p>Under the Spring port the broker is the app's <em>embedded</em> Artemis instance
 * ({@code spring.artemis.mode=embedded}); these tests use {@link JmsTestClient} — opened from the same
 * {@link ConnectionFactory} bean the Niord application injects — to pin the contract the message-status
 * topic flow depends on:
 * <ul>
 *   <li>a status-update {@code MapMessage} in the exact shape {@code MessageService.sendStatusUpdate}
 *       broadcasts ({@code ID}/{@code UID}/{@code STATUS}/{@code PREV_STATUS}) round-trips intact through
 *       the broker (these are the keys {@code MailingListMessageListener} and {@code TwitterMessageListener}
 *       read);</li>
 *   <li>the destination is a true publish/subscribe <em>topic</em>: every live subscriber gets its own copy;</li>
 *   <li>subscriptions are non-durable: a subscriber receives only messages published after it subscribed
 *       (the at-most-once, online-only delivery the live listeners rely on); and</li>
 *   <li>a non-map payload arrives as a {@code TextMessage}, i.e. NOT a {@code MapMessage} — which is exactly
 *       the cast the listeners must (and do) guard.</li>
 * </ul>
 *
 * <p>Plain JUnit 5 + {@code jakarta.jms}; the only framework touch-point is obtaining the standard
 * {@link ConnectionFactory} bean, which survives the planned broker/framework changes (only the
 * broker-coordinate plumbing in {@link JmsTestClient} is infrastructure). Each test uses a distinct topic
 * name and closes its client, so the tests are independent and repeatable. The production topic name
 * ({@code messageStatus}) and the real publish path are exercised separately by
 * {@code MessageStatusPublishITTest}.
 */
class MessageStatusTopicITTest extends ITTest {

    private static final long RECEIVE_TIMEOUT_MS = 10_000;
    private static final long SHORT_TIMEOUT_MS   = 1_500;

    @Autowired
    ConnectionFactory connectionFactory;

    @Test
    void publishedStatusUpdateRoundTripsThroughTopicWithAllFields() throws Exception {
        String topic = "it-status-roundtrip";
        try (JmsTestClient client = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = client.subscribe(topic);

            client.publishStatusUpdate(topic, 101, "uid-roundtrip", "PUBLISHED", "VERIFIED");

            MapMessage received = JmsTestClient.receiveMapMessage(consumer, RECEIVE_TIMEOUT_MS);
            assertNotNull(received, "the published status update must be delivered to the subscriber");
            assertEquals("101", received.getString("ID"), "ID must round-trip (as its string form)");
            assertEquals("uid-roundtrip", received.getString("UID"));
            assertEquals("PUBLISHED", received.getString("STATUS"));
            assertEquals("VERIFIED", received.getString("PREV_STATUS"));
        }
    }

    @Test
    void topicBroadcastsToEveryLiveSubscriber() throws Exception {
        String topic = "it-status-broadcast";
        try (JmsTestClient client = JmsTestClient.using(connectionFactory)) {
            JMSConsumer first = client.subscribe(topic);
            JMSConsumer second = client.subscribe(topic);

            client.publishStatusUpdate(topic, 7, "uid-broadcast", "PUBLISHED", "VERIFIED");

            MapMessage toFirst = JmsTestClient.receiveMapMessage(first, RECEIVE_TIMEOUT_MS);
            MapMessage toSecond = JmsTestClient.receiveMapMessage(second, RECEIVE_TIMEOUT_MS);
            assertNotNull(toFirst, "the first subscriber must receive a copy");
            assertNotNull(toSecond, "the second subscriber must receive its own copy (topic fan-out)");
            assertEquals("uid-broadcast", toFirst.getString("UID"));
            assertEquals("uid-broadcast", toSecond.getString("UID"));
        }
    }

    @Test
    void subscriberOnlyReceivesMessagesPublishedAfterItSubscribed() throws Exception {
        String topic = "it-status-non-durable";
        try (JmsTestClient client = JmsTestClient.using(connectionFactory)) {
            // Publish BEFORE anyone subscribes - a non-durable topic drops it.
            client.publishStatusUpdate(topic, 1, "uid-before", "PUBLISHED", "VERIFIED");

            JMSConsumer consumer = client.subscribe(topic);
            assertNull(JmsTestClient.receiveMapMessage(consumer, SHORT_TIMEOUT_MS),
                    "a message published before subscribing must not be delivered");

            // Publish AFTER subscribing - now it is delivered.
            client.publishStatusUpdate(topic, 2, "uid-after", "PUBLISHED", "VERIFIED");
            MapMessage received = JmsTestClient.receiveMapMessage(consumer, RECEIVE_TIMEOUT_MS);
            assertNotNull(received, "a message published after subscribing must be delivered");
            assertEquals("uid-after", received.getString("UID"));
        }
    }

    @Test
    void receiveReturnsNullWhenNothingIsPublished() throws Exception {
        String topic = "it-status-idle";
        try (JmsTestClient client = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = client.subscribe(topic);
            assertNull(JmsTestClient.receiveMapMessage(consumer, SHORT_TIMEOUT_MS),
                    "a receive with no published message must time out and return null");
        }
    }

    @Test
    void nonMapPayloadIsDeliveredAsTextMessageNotMapMessage() throws Exception {
        String topic = "it-status-text";
        try (JmsTestClient client = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = client.subscribe(topic);

            client.publishText(topic, "not-a-map");

            Message received = JmsTestClient.receive(consumer, RECEIVE_TIMEOUT_MS);
            assertNotNull(received, "the text message must be delivered");
            assertInstanceOf(TextMessage.class, received,
                    "a text payload arrives as a TextMessage - the listeners' (MapMessage) cast would fail here");
            assertEquals("not-a-map", ((TextMessage) received).getText());
        }
    }
}
