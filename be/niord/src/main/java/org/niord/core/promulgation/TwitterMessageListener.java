/*
 * Copyright 2017 Danish Maritime Authority.
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

package org.niord.core.promulgation;

import org.niord.core.util.JmsTopicLoop;
import org.niord.model.message.Status;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.MapMessage;
import jakarta.jms.MessageListener;

/**
 * Used for listening for message status updates via JMS
 */
// PORT: was @jakarta.ejb.MessageDriven JMS MDB; now a plain Spring @Component that polls the JMS
// topic on a single-thread executor (started/stopped via ApplicationReadyEvent/ContextClosedEvent),
// mirroring MailingListMessageListener. Without this self-subscribing consumer loop the listener
// is never wired to the broker and "tweet on publish" never fires.
@Component
@SuppressWarnings("unused")
public class TwitterMessageListener implements Runnable, MessageListener {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(TwitterMessageListener.class);

    private final TwitterPromulgationService twitterPromulgationService;

    private final ConnectionFactory connectionFactory;

    TwitterMessageListener(TwitterPromulgationService twitterPromulgationService, ConnectionFactory connectionFactory) {
        this.twitterPromulgationService = twitterPromulgationService;
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

            // Check if this is a published message
            if (Status.PUBLISHED.name().equals(msg.getString("STATUS"))) {
                log.info("Received PUBLISHED message status update for UID: " + msg.getString("UID"));

                twitterPromulgationService.checkPromulgateMessage(msg.getString("UID"));
            }
        } catch (Throwable e) {
            log.error("Failed processing JMS message " + message, e);
        }
    }
}
