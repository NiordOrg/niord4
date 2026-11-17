/*
 * Copyright 2016 Danish Maritime Authority.
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
import org.niord.model.message.Type;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the {@code disableLucene} free-text search toggle as observed through the public
 * {@link MessageService#search(MessageSearchParams)} entry point.
 *
 * <p>These prove that the setting actually switches the backend that answers a free-text query, and they
 * pin a clear, observable difference between the two backends:
 * <ul>
 *   <li>Lucene matches whole <em>tokens</em> and does not stem, so the plural "harbours" does not match a
 *       message indexed under "harbour";</li>
 *   <li>the PostgreSQL full-text backend <em>stems</em>, so "harbours" matches "harbour".</li>
 * </ul>
 * Both backends are populated with the same committed message, so the only thing that varies between the
 * two halves of a test is the {@code disableLucene} setting &mdash; hence any difference in the result is
 * attributable to the backend switch. (The search uses {@code maxSize=Integer.MAX_VALUE} — the default for
 * a directly-built {@code MessageSearchParams} — so results are not paged and the assertions about the
 * created message's id hold regardless of how many other messages also match.)
 *
 * <p>Like the index ITs these use COMMITTED data (the indexes re-query the database), and the global
 * {@code disableLucene} setting is always reset to {@code false} in {@link #cleanup()} so the toggle does
 * not leak into other tests.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageFreeTextSearchToggleITTest extends ITTest {

    private static final String DISABLE_LUCENE = MessagePostgresIndex.DISABLE_LUCENE_SETTING;

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSearchService messageSearchService;

    @Autowired
    MessageLuceneIndex messageLuceneIndex;

    @Autowired
    MessagePostgresIndex messagePostgresIndex;

    @Autowired
    SettingsService settingsService;

    @PersistenceContext
    EntityManager em;

    private final List<Integer> createdMessageIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // Always restore the default backend first, so a failure cannot leak the toggle into other tests.
        try {
            setDisableLucene(false);
        } catch (Exception ignored) {
            // best effort
        }

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

        rebuildBothIndexes();
    }

    /** A globally-unique, purely-alphabetic search token (digits tokenize oddly under full-text search). */
    private String uniqueToken() {
        long n = System.nanoTime();
        StringBuilder sb = new StringBuilder("zztok");
        do {
            sb.append((char) ('a' + (int) (n % 26)));
            n /= 26;
        } while (n > 0);
        return sb.toString();
    }

    /** Creates a committed COASTAL_WARNING in dma-nw with the given title and (optional) part details. */
    private Message createCommitted(String title, String details) throws Exception {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries("dma-nw"));
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject("subject");
        pd.setDetails(details);
        msg.addPart(part);
        msg.createDesc("en").setTitle(title);

        Message created = messageService.createMessage(msg);
        createdMessageIds.add(created.getId());
        return created;
    }

    private void rebuildBothIndexes() {
        try {
            messageLuceneIndex.recreateIndex();
        } catch (Exception ignored) {
            // best effort
        }
        try {
            messagePostgresIndex.recreateIndex();
        } catch (Exception ignored) {
            // best effort
        }
    }

    private void setDisableLucene(boolean value) {
        // Start-up added the row (SettingsService.addMissing); a read never creates one, and set refuses a missing row
        assertNotNull(settingsService.getSetting(DISABLE_LUCENE), "precondition: start-up added the disableLucene row");
        settingsService.set(DISABLE_LUCENE, value);
    }

    /** Runs a free-text search via the public service with the given backend and reports whether the id is found. */
    private boolean searchFinds(Integer messageId, String query, boolean disableLucene) {
        setDisableLucene(disableLucene);
        PagedSearchResultVo<Message> result =
                messageSearchService.search(new MessageSearchParams().query(query).language("en"));
        return result.getData().stream().anyMatch(m -> m.getId().equals(messageId));
    }

    // ------------------------------------------------------------------------------------------- //
    // The toggle switches the backend, proven on a SINGLE message via the stemming difference. All four
    // assertions run against the same committed row, so the difference is attributable solely to
    // disableLucene:
    //   - Lucene finds the exact word "harbour"      (positive control: Lucene CAN see this row, so an
    //                                                 exception swallowed by MessageService.search into an
    //                                                 empty result would break THIS assertion too);
    //   - Lucene does NOT find the plural "harbours"  (Lucene does not stem);
    //   - PostgreSQL finds the plural "harbours"      (full-text stemming);
    //   - PostgreSQL also finds the exact word        (parity).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void backendSwitchIsObservableViaStemming() throws Exception {
        // The title carries the real word "harbour" plus a unique token (for cleanup tracking).
        Message m = createCommitted("harbour " + uniqueToken(), null);
        rebuildBothIndexes();
        Integer id = m.getId();

        assertTrue(searchFinds(id, "harbour", false),
                "control: the Lucene backend must find the message by the exact word 'harbour'");
        assertFalse(searchFinds(id, "harbours", false),
                "with disableLucene=false the Lucene backend must NOT find the message by the plural (no stemming)");
        assertTrue(searchFinds(id, "harbours", true),
                "with disableLucene=true the PostgreSQL backend must find the message by the plural (stemming)");
        assertTrue(searchFinds(id, "harbour", true),
                "the PostgreSQL backend must also find the message by the exact word (parity)");
    }

    // ------------------------------------------------------------------------------------------- //
    // A non-matching free-text query must match NOTHING (not everything) on either backend — guarding
    // the empty-result-set semantics through the full search pipeline.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void unmatchedQueryReturnsTheMessageOnNeitherBackend() throws Exception {
        Message m = createCommitted("harbour " + uniqueToken(), null);
        rebuildBothIndexes();

        String absent = uniqueToken();
        assertFalse(searchFinds(m.getId(), absent, false),
                "an unmatched query must not return the message via the Lucene backend");
        assertFalse(searchFinds(m.getId(), absent, true),
                "an unmatched query must not return the message via the PostgreSQL backend");
    }

    // ------------------------------------------------------------------------------------------- //
    // With the PostgreSQL backend and no explicit sort, MessageService.search returns results ordered by
    // full-text relevance ("best match first"): the message where the term occurs more often comes first.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void postgresResultsAreOrderedByRelevanceThroughTheService() throws Exception {
        String token = uniqueToken();
        Message low = createCommitted("title", token);
        Message high = createCommitted("title", token + " " + token + " " + token);
        rebuildBothIndexes();

        setDisableLucene(true);
        try {
            List<Integer> ids = messageSearchService.search(new MessageSearchParams().query(token).language("en"))
                    .getData().stream().map(Message::getId).toList();
            int hi = ids.indexOf(high.getId());
            int lo = ids.indexOf(low.getId());
            assertTrue(hi >= 0 && lo >= 0, "both messages must be returned by the service");
            assertTrue(hi < lo,
                    "relevance ordering: the more relevant message must come first in MessageService.search");
        } finally {
            setDisableLucene(false);
        }
    }
}
