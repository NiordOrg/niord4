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

package org.niord.core.mailinglist;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.niord.core.model.BaseEntity;
import org.niord.core.util.JmsTopicLoop;
import org.niord.model.message.Status;
import org.slf4j.Logger;

import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.MapMessage;

/**
 * Used for listening for message status updates via JMS
 */
// PORT: was @jakarta.ejb.MessageDriven JMS MDB; now a plain Spring @Component
// that polls the JMS topic on a single-thread executor (started/stopped via
// ApplicationReadyEvent/ContextClosedEvent). Re-wire as @JmsListener /
// DefaultMessageListenerContainer (topic MessageStatusTopic) at the IT stage.
@SuppressWarnings("unused")
@Component
public class MailingListMessageListener implements Runnable {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MailingListMessageListener.class);

    private final MailingListService mailingListService;

    private final MailingListExecutionService mailingListExecutionService;

    private final ConnectionFactory connectionFactory;

    MailingListMessageListener(MailingListService mailingListService, MailingListExecutionService mailingListExecutionService, ConnectionFactory connectionFactory) {
        this.mailingListService = mailingListService;
        this.mailingListExecutionService = mailingListExecutionService;
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
    public void onMessage(jakarta.jms.Message message) {
        try {
            MapMessage msg = (MapMessage) message;

            String uid = msg.getString("UID");
            Status status = Status.valueOf(msg.getString("STATUS"));

            // The status event is broadcast only after the producing transaction commits
            // (see MessageService.sendStatusUpdate), so the message row is guaranteed visible
            // here - no delay is needed to race the DB commit.
            log.debug("Received " + status + " message status update for UID: " + uid);
            checkStatusChangeMailingListExecution(uid, status);

        } catch (Throwable e) {
            log.error("Failed processing JMS message " + message, e);
        }
    }

    /**
     * Handle mailing list execution for the message. Called from the MailingListMessageListener MDB listener.
     *
     * @param messageUid the UID of the message
     * @param status the message status
     */
    public void checkStatusChangeMailingListExecution(String messageUid, Status status) {

        long t0 = System.currentTimeMillis();

        List<Integer> triggerIds = mailingListService.findStatusChangeTriggers(status).stream()
                .map(BaseEntity::getId)
                .collect(Collectors.toList());

        log.debug(String.format("Found %d status change triggers for %s in status %s",
                triggerIds.size(),
                messageUid,
                status));

        for (Integer triggerId : triggerIds) {
            try {
                // NB: This function requires a new transaction
                mailingListExecutionService.executeStatusChangeTrigger(triggerId, messageUid);
            } catch (Exception e) {
                log.error("Error executing status-change mailing-list trigger " + triggerId
                        + " for message " + messageUid, e);
                // Leave a record in the Mail Queue, so the mail that was due does not vanish without a trace
                try {
                    mailingListExecutionService.recordStatusChangeTriggerFailure(triggerId, messageUid, e);
                } catch (Exception recordFailure) {
                    log.error("Failed recording the failure of status-change mailing-list trigger " + triggerId
                            + " for message " + messageUid, recordFailure);
                }
            }
        }
        log.debug(String.format("Executed %d status change triggers for %s in status %s in %d ms",
                triggerIds.size(),
                messageUid,
                status,
                System.currentTimeMillis() - t0));
    }

}
