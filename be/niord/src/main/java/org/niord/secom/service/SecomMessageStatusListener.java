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

import jakarta.jms.ConnectionFactory;
import jakarta.jms.MapMessage;
import jakarta.jms.MessageListener;
import org.niord.core.util.JmsTopicLoop;
import org.niord.model.message.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Self-subscribing consumer loop on the message-status topic, mirroring TwitterMessageListener /
// MailingListMessageListener. The event is only a "look now" hint: the service re-reads the
// message from the DB and decides from its actual status, and the scheduled reconciliation
// sweep covers events lost while the app is down (the topic is fire-and-forget).
@Component
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class SecomMessageStatusListener implements Runnable, MessageListener {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecomMessageStatusListener.class);

    /** The status changes that can affect the S-124 dataset store */
    private static final Set<String> RELEVANT_STATUSES = Set.of(
            Status.PUBLISHED.name(), Status.CANCELLED.name(), Status.EXPIRED.name());

    private final S124DatasetService datasetService;

    private final ConnectionFactory connectionFactory;

    SecomMessageStatusListener(S124DatasetService datasetService, ConnectionFactory connectionFactory) {
        this.datasetService = datasetService;
        this.connectionFactory = connectionFactory;
    }

    @Value("${niord.jms.topic.messagestatustopic:messageStatus}")
    String messageStatusTopic;

    // NOT covered by the MDC TaskDecorator (org.niord.core.conf.CorrelationConfig): this is a plain
    // java.util.concurrent pool, and it runs one long-lived poll loop rather than discrete submitted
    // tasks — so there is no calling context to inherit and nothing for a decorator to wrap. Giving
    // these lines a correlation id means minting one per RECEIVED JMS MESSAGE inside the loop, which
    // is a separate change. See org.niord.core.util.MdcContext.
    private final ExecutorService scheduler = Executors.newSingleThreadExecutor();

    /** How long the poll loop waits before it subscribes again after losing its subscription (tests set it low) */
    long reconnectDelayMillis = 5000;

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        scheduler.submit(this);
    }

    @EventListener(ContextClosedEvent.class)
    void onStop() {
        // shutdownNow interrupts the loop, also while it waits to subscribe again
        scheduler.shutdownNow();
    }

    @Override
    public void run() {
        JmsTopicLoop.consume(connectionFactory, messageStatusTopic, scheduler::isShutdown, reconnectDelayMillis,
                this::onMessage, log);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onMessage(jakarta.jms.Message message) {
        try {
            MapMessage msg = (MapMessage) message;
            if (RELEVANT_STATUSES.contains(msg.getString("STATUS"))) {
                datasetService.onMessageStatusChanged(msg.getString("UID"));
            }
        } catch (Throwable e) {
            log.error("Failed processing JMS message " + message, e);
        }
    }
}
