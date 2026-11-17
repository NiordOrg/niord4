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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.MapMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mailinglist.MailingListMessageListener;
import org.niord.core.promulgation.TwitterMessageListener;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.Tx;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the <em>publish</em> half of the message-status topic flow: the real
 * {@code MessageService.updateStatus(...)} → {@code sendStatusUpdate(...)} path broadcasting through the
 * application's injected {@link ConnectionFactory} to the Testcontainers Artemis broker.
 *
 * <p>Previously this fire-and-forget broadcast was unobservable (see docs/untestable-code.md): the service
 * tests forced the code path to run but asserted only on persisted state. Here a test subscribes a consumer
 * to the configured {@code messageStatus} topic <em>before</em> driving a status transition, then asserts
 * the emitted {@link MapMessage} carries the expected {@code UID}/{@code STATUS}/{@code PREV_STATUS}/{@code ID}.
 * This proves the application really publishes the status-change event to the broker.
 *
 * <p>It also pins that BOTH listeners now consume the topic: {@code MailingListMessageListener} and
 * {@code TwitterMessageListener} are each a {@code Runnable} that opens its own {@code JMSContext} topic
 * consumer (started from {@code ApplicationReadyEvent}) on the configured {@code messageStatus} topic, so
 * each is a wired bean that genuinely subscribes and receives broadcasts. {@code TwitterMessageListener}
 * was once a {@code @Component} that nothing subscribed, so "tweet on publish" was dead; it now has the
 * same self-subscribing consumer loop as the mailing-list listener.
 *
 * <p>This is a framework-coupled test (it needs the running app to drive the real publish path and bean
 * wiring) — see docs/framework-coupled-tests.md. The broadcast is emitted only <em>after</em> the producing
 * transaction commits ({@code MessageService} defers it to an {@code afterCommit} synchronization, so no
 * event is sent for a rolled-back change), so each broadcast test drives the status transition in a committed
 * {@code Tx.requiringNew()} block and removes the committed rows in {@code @AfterEach} to keep the tests
 * independent. Published messages use a far-past publish date to stay off the current-year number sequence
 * (same rationale as {@code MessageServiceITTest}).
 */
class MessageStatusPublishITTest extends ITTest {

    private static final long RECEIVE_TIMEOUT_MS = 15_000;

    @Autowired
    MessageService messageService;

    @PersistenceContext
    EntityManager em;

    @Autowired
    ConnectionFactory connectionFactory;

    @Autowired
    ApplicationContext applicationContext;

    @Value("${niord.jms.topic.messagestatustopic:messageStatus}")
    String messageStatusTopic;

    private final List<Integer> createdMessageIds = new ArrayList<>();

    /** Removes the committed messages the broadcast tests create, keeping the suite independent. */
    @AfterEach
    void cleanup() {
        for (Integer id : createdMessageIds) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                Message m = em.find(Message.class, id);
                if (m != null) {
                    em.remove(m);
                }
            });
        }
        createdMessageIds.clear();
    }

    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateStatusToVerifiedBroadcastsStatusUpdateToTopic() throws Exception {
        try (JMSContext ctx = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
            JMSConsumer consumer = ctx.createConsumer(ctx.createTopic(messageStatusTopic));

            // Create the draft and drive DRAFT -> VERIFIED in one COMMITTED transaction: the status
            // broadcast is emitted on commit (MessageService defers it to afterCommit), so the already
            // subscribed consumer receives it once the transaction commits. The fixture STAYS at
            // VERIFIED until cleanup, so it takes the FUTURE publish date — a past date would let the
            // once-a-minute auto-publish job publish it behind the test's back (minting a number and
            // broadcasting a PUBLISHED event this test never asked for).
            String uid = Tx.requiringNew().call(() -> {
                Message created = createDraftNw("Broadcast on verify", FUTURE_PUBLISH_DATE);
                createdMessageIds.add(created.getId());
                messageService.updateStatus(created.getUid(), Status.VERIFIED);
                return created.getUid();
            });

            MapMessage msg = (MapMessage) consumer.receive(RECEIVE_TIMEOUT_MS);
            assertNotNull(msg, "updateStatus must broadcast a status-change event to the topic after commit");
            assertEquals(uid, msg.getString("UID"), "the broadcast must carry the message UID");
            assertEquals("VERIFIED", msg.getString("STATUS"), "the new status must be VERIFIED");
            assertEquals("DRAFT", msg.getString("PREV_STATUS"), "the previous status must be DRAFT");
            assertNotNull(msg.getString("ID"), "the broadcast must carry the message database ID");
        }
    }

    @Test
    void updateStatusToPublishedBroadcastsPublishedStatusUpdate() throws Exception {
        // Create + verify in a COMMITTED transaction BEFORE subscribing, so the consumer only sees the
        // later PUBLISHED broadcast (the VERIFIED broadcast fires before anyone is subscribed and is
        // dropped by the non-durable topic). The message then sits committed at VERIFIED with a past
        // publishDateFrom until the publish below — safe only because ITTest unschedules the
        // auto-publish cron (see the "background schedulers" landmine in docs/test-authoring-guide.md).
        String uid = Tx.requiringNew().call(() -> {
            Message created = createDraftNw("Broadcast on publish");
            createdMessageIds.add(created.getId());
            messageService.updateStatus(created.getUid(), Status.VERIFIED);
            return created.getUid();
        });

        try (JMSContext ctx = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
            JMSConsumer consumer = ctx.createConsumer(ctx.createTopic(messageStatusTopic));

            // Publish in its own COMMITTED transaction; the PUBLISHED broadcast fires on commit.
            Tx.requiringNew().run(() -> messageService.updateStatus(uid, Status.PUBLISHED));

            MapMessage msg = (MapMessage) consumer.receive(RECEIVE_TIMEOUT_MS);
            assertNotNull(msg, "publishing must broadcast a status-change event to the topic after commit");
            assertEquals(uid, msg.getString("UID"));
            assertEquals("PUBLISHED", msg.getString("STATUS"), "the new status must be PUBLISHED");
            assertEquals("VERIFIED", msg.getString("PREV_STATUS"), "the previous status must be VERIFIED");
        }
    }

    @Test
    void mailingListAndTwitterListenersBothConsumeTheStatusTopic() throws Exception {
        // MailingListMessageListener is a wired bean that genuinely subscribes to the same topic the
        // publisher broadcasts on: it is a Runnable opening its own JMSContext consumer (started from
        // ApplicationReadyEvent). Assert it is a real application bean AND that it is pointed at the
        // configured messageStatus topic, so it actually receives the broadcasts produced above.
        MailingListMessageListener mailingListListener =
                applicationContext.getBean(MailingListMessageListener.class);
        assertTrue(mailingListListener instanceof Runnable,
                "MailingListMessageListener must be the self-subscribing Runnable consumer of the topic");
        assertEquals(messageStatusTopic, topicFieldOf(mailingListListener),
                "MailingListMessageListener must subscribe to the same topic the publisher broadcasts on");

        // TwitterMessageListener is subscribed the SAME way - a self-subscribing Runnable that opens a
        // JMSContext consumer on the messageStatus topic from ApplicationReadyEvent - so the "tweet on
        // publish" path is live and receives the broadcasts produced above. It must be a wired bean, a
        // Runnable, and pointed at the same topic as the publisher.
        TwitterMessageListener twitterListener =
                applicationContext.getBean(TwitterMessageListener.class);
        assertTrue(twitterListener instanceof Runnable,
                "TwitterMessageListener must now be the self-subscribing Runnable consumer of the topic (JMS1)");
        assertEquals(messageStatusTopic, topicFieldOf(twitterListener),
                "TwitterMessageListener must subscribe to the same topic the publisher broadcasts on (JMS1)");
    }

    /** Reads the private {@code messageStatusTopic} field a status listener subscribes to. */
    private static String topicFieldOf(Object listener) throws Exception {
        java.lang.reflect.Field f = listener.getClass().getDeclaredField("messageStatusTopic");
        f.setAccessible(true);
        return (String) f.get(listener);
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers (mirrors MessageServiceITTest: build a DRAFT NW message off the current-year sequence)
    // ------------------------------------------------------------------------------------------- //

    /** A fixed PAST publish date so publishing never bumps the current-year message-number sequence. */
    private static final Date PAST_PUBLISH_DATE = pastPublishDate();

    /**
     * A fixed FUTURE publish date (year 2999, the {@code ScheduledMessageServiceITTest} idiom) for the
     * fixture that STAYS at VERIFIED: the once-a-minute auto-publish job system-publishes every
     * committed VERIFIED message whose {@code publishDateFrom} is in the past, and a past-dated
     * stay-VERIFIED fixture would be published behind the test's back. See the "background schedulers"
     * landmine in docs/test-authoring-guide.md.
     */
    private static final Date FUTURE_PUBLISH_DATE = futurePublishDate();

    private static Date pastPublishDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2001, java.util.Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    private static Date futurePublishDate() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2999, java.util.Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** Creates a committed past-dated draft — for messages the test itself publishes. */
    private Message createDraftNw(String title) throws Exception {
        return createDraftNw(title, PAST_PUBLISH_DATE);
    }

    private Message createDraftNw(String title, Date publishDateFrom) throws Exception {
        Message msg = new Message();
        MessageSeries series = new MessageSeries("dma-nw");
        series.setMainType(Type.COASTAL_WARNING.getMainType());
        msg.setMessageSeries(series);
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        msg.setPublishDateFrom(publishDateFrom);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return messageService.createMessage(msg);
    }
}
