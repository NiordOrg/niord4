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
package org.niord.core.message;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.settings.SettingsService;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A save of {@code messageIndexIncludeDeletedMessages} takes effect for every message, not only for the messages
 * indexed after it: the save builds both message search indexes again ({@link MessageIndexRebuilder}), on a thread of
 * its own, so a deleted message that neither index held is found in both once the rebuild has run, and is gone from
 * both again after the value is saved back.
 *
 * <p>The setting is saved through {@link SettingsService#set}, which commits and refreshes the fields after the
 * commit, as the Settings page does; the test restores it. The message is committed and removed afterwards.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageIndexRebuildITTest extends ITTest {

    private static final Duration REBUILT = Duration.ofSeconds(60);

    @Autowired
    MessageService messageService;

    @Autowired
    MessageLuceneIndex messageLuceneIndex;

    @Autowired
    MessagePostgresIndex messagePostgresIndex;

    @Autowired
    SettingsService settingsService;

    @PersistenceContext
    EntityManager em;

    private Integer createdId;

    @AfterEach
    void cleanup() throws Exception {
        if (createdId != null) {
            Integer id = createdId;
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                Message m = em.find(Message.class, id);
                if (m != null) {
                    em.remove(m);
                }
            });
            createdId = null;
        }
        messageLuceneIndex.recreateIndex();
        messagePostgresIndex.recreateIndex();
    }

    @Test
    void aSaveBuildsBothIndexesAgainByTheNewValue() throws Exception {
        String token = uniqueToken();
        Message created = messageService.createMessage(newDraft("Wreck " + token));
        createdId = created.getId();
        messageService.updateStatus(created.getUid(), Status.DELETED);
        long id = created.getId().longValue();

        messageLuceneIndex.recreateIndex();
        messagePostgresIndex.recreateIndex();
        assertFalse(lucene(token).contains(id), "a deleted message is left out of the Lucene index by default");
        assertFalse(postgres(token).contains(id), "and out of the PostgreSQL table");

        try {
            settingsService.set(MessageIndexRebuilder.INCLUDE_DELETED, true);

            await().atMost(REBUILT).untilAsserted(() -> {
                assertTrue(lucene(token).contains(id), "the Lucene index was built again and holds the message");
                assertTrue(postgres(token).contains(id), "the PostgreSQL table was built again and holds it");
            });
        } finally {
            settingsService.set(MessageIndexRebuilder.INCLUDE_DELETED, false);
        }

        await().atMost(REBUILT).untilAsserted(() -> {
            assertFalse(lucene(token).contains(id), "saved back: the Lucene index leaves it out again");
            assertFalse(postgres(token).contains(id), "and the PostgreSQL table");
        });
    }

    // ------------------------------------------------------------------------------------------- //

    /** A globally unique, purely alphabetic token, which both indexes keep as one word */
    private static String uniqueToken() {
        long n = System.nanoTime();
        StringBuilder sb = new StringBuilder("zzrebuild");
        do {
            sb.append((char) ('a' + (int) (n % 26)));
            n /= 26;
        } while (n > 0);
        return sb.toString();
    }

    /** A detached draft in the base series, whose title holds the token */
    private static Message newDraft(String title) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nw"));
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails("details");
        msg.addPart(part);
        msg.createDesc("en").setTitle(title);
        return msg;
    }

    private List<Long> lucene(String token) throws Exception {
        return messageLuceneIndex.searchIndex(token, "en", 100);
    }

    private List<Long> postgres(String token) {
        return messagePostgresIndex.searchIndex(token, "en", 100);
    }
}
