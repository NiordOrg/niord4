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
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for {@link MessagePostgresIndex}, the PostgreSQL <strong>full-text</strong>
 * search index over all messages and the alternative to {@link MessageLuceneIndex} (selected by the
 * {@code disableLucene} setting).
 *
 * <p>Like the Lucene index, the search table is built by re-querying the database in a fresh transaction,
 * so the tests must use COMMITTED data: each test creates its message(s) through
 * {@link MessageService#createMessage(Message)} (which commits when invoked outside an enclosing
 * transaction), calls {@link MessagePostgresIndex#recreateIndex()} to rebuild the table, and asserts on
 * {@link MessagePostgresIndex#searchIndex}. Every test embeds a globally-unique alphabetic token so a hit
 * can only come from the row it created; created rows are removed in {@link #cleanup()}.
 *
 * <p>The behaviour here is PostgreSQL full-text search ({@code to_tsvector} / {@code websearch_to_tsquery}),
 * which is intentionally <em>smarter but different</em> from Lucene: matching is on whole-word stems
 * ("harbours" matches "harbour"), the default operator is AND (Google-style), {@code -term} excludes,
 * quoted phrases match by adjacency, and matching is case-insensitive. It does NOT do the old
 * substring/wildcard or accent-folding matching. The index-exclusion rules ({@code DELETED} status,
 * {@code excludeFromMessageIndex} series) are unchanged. See docs/lucene-postgres-toggle.md.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessagePostgresIndexITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @Autowired
    MessagePostgresIndex messagePostgresIndex;

    @PersistenceContext
    EntityManager em;

    private final List<Integer> createdMessageIds = new ArrayList<>();
    private final List<Integer> createdSeriesIds = new ArrayList<>();

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

        for (Integer id : createdSeriesIds) {
            Tx.requiringNew().run(() -> {
                MessageSeries s = em.find(MessageSeries.class, id);
                if (s != null) {
                    em.remove(s);
                }
            });
        }
        createdSeriesIds.clear();

        try {
            messagePostgresIndex.recreateIndex();
        } catch (Exception ignored) {
            // best-effort: leave the table rebuilt from remaining committed data for the next test
        }
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

    /** Builds a detached DRAFT message template in the given series. */
    private Message newDraft(String seriesId, Type type, String title, String subject, String details) {
        Message msg = new Message();
        msg.setMessageSeries(new MessageSeries(seriesId));
        msg.setType(type); // mainType is derived from the type
        msg.setAutoTitle(false);

        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        MessagePartDesc pd = part.createDesc("en");
        pd.setSubject(subject);
        pd.setDetails(details);
        msg.addPart(part);

        MessageDesc d = msg.createDesc("en");
        d.setTitle(title);
        return msg;
    }

    /** Creates a COMMITTED draft message (createMessage commits since it runs outside a tx). */
    private Message createCommitted(String seriesId, Type type, String title, String subject, String details)
            throws Exception {
        Message created = messageService.createMessage(newDraft(seriesId, type, title, subject, details));
        createdMessageIds.add(created.getId());
        return created;
    }

    private List<Long> search(String query) {
        return messagePostgresIndex.searchIndex(query, "en", 100);
    }

    // ------------------------------------------------------------------------------------------- //
    // Field coverage: title / subject / details are all indexed and searchable
    // ------------------------------------------------------------------------------------------- //

    @Test
    void findsMessageByUniqueTitleToken() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "Wreck near harbour " + token, "subject", "details");

        messagePostgresIndex.recreateIndex();

        assertTrue(search(token).contains(m.getId().longValue()),
                "a message must be found by a unique token in its title");
    }

    @Test
    void findsMessageByUniquePartSubjectToken() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title", "Hazard " + token, "details");

        messagePostgresIndex.recreateIndex();

        assertTrue(search(token).contains(m.getId().longValue()),
                "a message must be found by a unique token in a message-part subject");
    }

    @Test
    void findsMessageByUniquePartDetailsToken() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title", "subject", "Detailed warning about " + token);

        messagePostgresIndex.recreateIndex();

        assertTrue(search(token).contains(m.getId().longValue()),
                "a message must be found by a unique token in the message-part details");
    }

    @Test
    void findsMessageByTokenInsideHtmlDetails() throws Exception {
        // Details are run through TextUtils.html2txt before indexing (in MessageSearchTextBuilder), so the
        // plain-text token inside markup is indexed as a word; the tag names are stripped.
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title", "subject", "<p><b>" + token + "</b> reported</p>");

        messagePostgresIndex.recreateIndex();

        assertTrue(search(token).contains(m.getId().longValue()),
                "the plain-text token inside HTML details must be indexed");
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative: a token that was never indexed must not surface the message
    // ------------------------------------------------------------------------------------------- //

    @Test
    void doesNotFindMessageByAbsentToken() throws Exception {
        String present = uniqueToken();
        String absent = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + present, "subject", "details");

        messagePostgresIndex.recreateIndex();

        assertFalse(search(absent).contains(m.getId().longValue()),
                "a message must not be returned for a token that is not present anywhere in it");
    }

    // ------------------------------------------------------------------------------------------- //
    // Stemming: a plural query matches the singular indexed word (the headline "smarter" behaviour)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void stemmingMatchesWordVariants() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "harbour " + token, "subject", "details");

        messagePostgresIndex.recreateIndex();

        assertTrue(search("harbours").contains(m.getId().longValue()),
                "full-text stemming: the plural query 'harbours' must match the indexed word 'harbour'");
        assertTrue(search("harbour").contains(m.getId().longValue()),
                "the exact word must of course also match");
    }

    // ------------------------------------------------------------------------------------------- //
    // Default operator is AND (Google-style): a multi-word query requires ALL words
    // ------------------------------------------------------------------------------------------- //

    @Test
    void multiTokenQueryUsesAndSemantics() throws Exception {
        String tokenA = uniqueToken();
        String tokenB = uniqueToken();
        Message both = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + tokenA + " " + tokenB, "subject", "details");
        Message onlyA = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + tokenA, "subject", "details");

        messagePostgresIndex.recreateIndex();

        List<Long> ids = search(tokenA + " " + tokenB);
        assertTrue(ids.contains(both.getId().longValue()),
                "a message containing both query words must match");
        assertFalse(ids.contains(onlyA.getId().longValue()),
                "AND is the default: a message containing only one of the two words must not match");
    }

    // ------------------------------------------------------------------------------------------- //
    // Excluded term (-word) filters out matches that contain it
    // ------------------------------------------------------------------------------------------- //

    @Test
    void excludedTermFiltersOutMatches() throws Exception {
        String tokenA = uniqueToken();
        String tokenB = uniqueToken();
        Message withBoth = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + tokenA + " " + tokenB, "subject", "details");
        Message withA = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + tokenA, "subject", "details");

        messagePostgresIndex.recreateIndex();

        List<Long> ids = search(tokenA + " -" + tokenB);
        assertTrue(ids.contains(withA.getId().longValue()),
                "a message with the required word and not the excluded word must match");
        assertFalse(ids.contains(withBoth.getId().longValue()),
                "a message containing the excluded word must be filtered out");
    }

    // ------------------------------------------------------------------------------------------- //
    // Quoted phrase search (adjacency, order sensitive)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void phraseSearchMatchesAdjacentWordsInOrder() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "alpha " + token + " bravo", "subject", "details");

        messagePostgresIndex.recreateIndex();

        assertTrue(search("\"" + token + " bravo\"").contains(m.getId().longValue()),
                "a quoted phrase in the indexed order must match (adjacency)");
    }

    @Test
    void phraseSearchDoesNotMatchReversedOrder() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "alpha " + token + " bravo", "subject", "details");

        messagePostgresIndex.recreateIndex();

        assertFalse(search("\"bravo " + token + "\"").contains(m.getId().longValue()),
                "a quoted phrase in the wrong order must not match");
    }

    // ------------------------------------------------------------------------------------------- //
    // Case-insensitive matching (to_tsvector / websearch_to_tsquery both lower-case)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchIsCaseInsensitive() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + token.toUpperCase(), "subject", "details");

        messagePostgresIndex.recreateIndex();

        assertTrue(search(token.toLowerCase()).contains(m.getId().longValue()),
                "full-text search lower-cases both sides, so matching must be case-insensitive");
    }

    // ------------------------------------------------------------------------------------------- //
    // DELETED messages are excluded (shouldAddMessage)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void deletedMessageIsExcludedFromIndex() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + token, "subject", "details");

        messagePostgresIndex.recreateIndex();
        assertTrue(search(token).contains(m.getId().longValue()),
                "the draft message must be indexed before deletion");

        messageService.updateStatus(m.getUid(), Status.DELETED);

        messagePostgresIndex.recreateIndex();
        assertFalse(search(token).contains(m.getId().longValue()),
                "a DELETED message must be excluded from the index");
    }

    // ------------------------------------------------------------------------------------------- //
    // Series flagged excludeFromMessageIndex are excluded
    // ------------------------------------------------------------------------------------------- //

    @Test
    void messageInExcludedSeriesIsNotIndexed() throws Exception {
        String seriesId = "IT-PG-excl-" + System.nanoTime();

        MessageSeries series = new MessageSeries(seriesId);
        series.setMainType(MainType.NW);
        series.setNumberSequenceType(NumberSequenceType.MANUAL);
        series.setExcludeFromMessageIndex(Boolean.TRUE);
        MessageSeries createdSeries =
                Tx.requiringNew().call(() -> messageSeriesService.createMessageSeries(series));
        createdSeriesIds.add(createdSeries.getId());

        String token = uniqueToken();
        Message m = createCommitted(seriesId, Type.COASTAL_WARNING,
                "title " + token, "subject", "details");

        messagePostgresIndex.recreateIndex();

        assertFalse(search(token).contains(m.getId().longValue()),
                "a message whose series has excludeFromMessageIndex=true must not be indexed");
    }

    @Test
    void messageInNonExcludedSeriesIsIndexed() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + token, "subject", "details");

        messagePostgresIndex.recreateIndex();

        assertTrue(search(token).contains(m.getId().longValue()),
                "a message in a non-excluded series must be indexed");
    }

    // ------------------------------------------------------------------------------------------- //
    // Multiple messages: only the matching one is returned
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchReturnsOnlyTheMatchingMessageAmongSeveral() throws Exception {
        String tokenA = uniqueToken();
        String tokenB = uniqueToken();
        Message a = createCommitted("dma-nw", Type.COASTAL_WARNING, "title " + tokenA, "subject", "details");
        Message b = createCommitted("dma-nw", Type.COASTAL_WARNING, "title " + tokenB, "subject", "details");

        messagePostgresIndex.recreateIndex();

        List<Long> ids = search(tokenA);
        assertTrue(ids.contains(a.getId().longValue()), "the message carrying tokenA must match");
        assertFalse(ids.contains(b.getId().longValue()),
                "the other message (carrying only tokenB) must not match a tokenA search");
    }

    // ------------------------------------------------------------------------------------------- //
    // Results are ordered by full-text relevance (ts_rank): the message where the term occurs more often
    // ranks higher.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchResultsAreOrderedByRelevance() throws Exception {
        String token = uniqueToken();
        // Same title/subject; only the number of occurrences of the token in the details differs.
        Message low = createCommitted("dma-nw", Type.COASTAL_WARNING, "title", "subject", token);
        Message high = createCommitted("dma-nw", Type.COASTAL_WARNING, "title", "subject",
                token + " " + token + " " + token);

        messagePostgresIndex.recreateIndex();

        List<Long> ids = search(token);
        int hi = ids.indexOf(high.getId().longValue());
        int lo = ids.indexOf(low.getId().longValue());
        assertTrue(hi >= 0 && lo >= 0, "both messages must match the token");
        assertTrue(hi < lo,
                "the message where the term occurs more often must rank higher (appear first)");
    }

    // ------------------------------------------------------------------------------------------- //
    // recreateIndex() return value
    // ------------------------------------------------------------------------------------------- //

    @Test
    void recreateIndexCountReflectsTheNewlyCommittedMessages() throws Exception {
        // Capture the baseline (whatever is already committed), then add exactly two messages and assert the
        // count grew by exactly two — so the count is attributed to the rows this test created, not the corpus.
        int baseline = messagePostgresIndex.recreateIndex();

        createCommitted("dma-nw", Type.COASTAL_WARNING, "title " + uniqueToken(), "subject", "details");
        createCommitted("dma-nm", Type.TEMPORARY_NOTICE, "title " + uniqueToken(), "subject", "details");

        int count = messagePostgresIndex.recreateIndex();
        assertEquals(baseline + 2, count,
                "recreateIndex() must report exactly the two newly committed messages on top of the baseline");
    }

    /**
     * A rebuild reads the messages in passes, letting each pass's messages go before the next, so its memory does not
     * grow with the database: with passes of two messages it still indexes every message, and finds the messages of
     * the last passes. FAILS when the rebuild stops after its first pass: it reports two messages.
     */
    @Test
    void recreateIndexInSmallPassesIndexesEveryMessage() throws Exception {
        String first = uniqueToken();
        Message m1 = createCommitted("dma-nw", Type.COASTAL_WARNING, "Pass " + first, "subject", "details");
        createCommitted("dma-nw", Type.COASTAL_WARNING, "Pass " + uniqueToken(), "subject", "details");
        String last = uniqueToken();
        Message m2 = createCommitted("dma-nm", Type.TEMPORARY_NOTICE, "Pass " + last, "subject", "details");
        int inOnePass = messagePostgresIndex.recreateIndex();

        MessagePostgresIndex target = AopTestUtils.getUltimateTargetObject(messagePostgresIndex);
        target.rebuildPassSize = 2;
        int inPasses;
        try {
            inPasses = messagePostgresIndex.recreateIndex();
        } finally {
            target.rebuildPassSize = MessagePostgresIndex.POSTGRES_MAX_INDEX_COUNT;
        }

        assertTrue(inOnePass >= 3, "precondition: more messages than one small pass takes: " + inOnePass);
        assertEquals(inOnePass, inPasses, "the passes together index every message");
        assertTrue(search(first).contains(m1.getId().longValue()), "a message of an early pass is found");
        assertTrue(search(last).contains(m2.getId().longValue()), "and one of the last passes");
        assertTrue(messagePostgresIndex.allMessagesIndexed(), "and the table is complete once the rebuild committed");
    }

    // ------------------------------------------------------------------------------------------- //
    // The scheduled incremental updater always runs (symmetric to the Lucene index) and picks up a
    // newly committed message incrementally, without a full recreate.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void scheduledUpdatePicksUpANewlyCommittedMessage() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "Scheduled pg " + token, "subject", "details");

        messagePostgresIndex.updatePostgresIndex(); // incremental, must index the new message

        assertTrue(search(token).contains(m.getId().longValue()),
                "the scheduled incremental updatePostgresIndex() must index a newly committed message");
    }

    // ------------------------------------------------------------------------------------------- //
    // IDX2: the incremental marker must be read back. It once came back as a LocalDateTime the code did not
    // accept, so every pass started from the epoch and rewrote the same first 5,000 messages, minutes of a
    // processor core a pass on the only scheduler thread. A pass right after a full rebuild has nothing to do.
    // The test holds the indexer's lock, so the scheduled pass cannot take the new message first.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void incrementalPassIndexesOnlyWhatChangedSinceTheLastPass() throws Exception {
        MessagePostgresIndex target = AopTestUtils.getUltimateTargetObject(messagePostgresIndex);
        target.lock.lock();
        try {
            messagePostgresIndex.recreateIndex();
            assertEquals(0, messagePostgresIndex.updatePostgresIndex(),
                    "a pass right after a full rebuild must find nothing to index, not start again from the epoch");

            String token = uniqueToken();
            Message m = createCommitted("dma-nw", Type.COASTAL_WARNING, "Changed " + token, "subject", "details");
            assertEquals(1, messagePostgresIndex.updatePostgresIndex(),
                    "the next pass must index exactly the one message committed since");
            assertTrue(search(token).contains(m.getId().longValue()), "the new message must be searchable");
            assertEquals(0, messagePostgresIndex.updatePostgresIndex(), "and the pass after it nothing");
        } finally {
            target.lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // A page that ends inside a run of messages sharing one `updated` time (a bulk load writes thousands, and
    // the sample's messages all share one) must leave the rest of the run for the next pass, not skip it: the
    // marker is the time and the id of the last message, and the next pass reads after both. The shared time has
    // microseconds, as one the database writes (now()) has: the marker once cut it to the millisecond, so it lay
    // before all three, and the next pass took the first two again.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aPassCutInsideMessagesSharingOneUpdatedTimeLeavesTheRestForTheNextPass() throws Exception {
        MessagePostgresIndex target = AopTestUtils.getUltimateTargetObject(messagePostgresIndex);
        target.lock.lock();
        try {
            Message a = createCommitted("dma-nw", Type.COASTAL_WARNING, "same time A", "subject", "details");
            Message b = createCommitted("dma-nw", Type.COASTAL_WARNING, "same time B", "subject", "details");
            Message c = createCommitted("dma-nw", Type.COASTAL_WARNING, "same time C", "subject", "details");
            messagePostgresIndex.recreateIndex();

            // One updated time for all three, after every message's, with microseconds (a bulk update fires no
            // @PreUpdate)
            Timestamp shared = Timestamp.from(Instant.ofEpochMilli(Math.max(System.currentTimeMillis(),
                    messagePostgresIndex.getMarker().updated().getTime() + 1)).plusNanos(456_000));
            Tx.requiringNew().run(() -> em.createQuery("update Message m set m.updated = :t where m.id in :ids")
                    .setParameter("t", shared)
                    .setParameter("ids", List.of(a.getId(), b.getId(), c.getId()))
                    .executeUpdate());

            assertEquals(2, (int) Tx.requiringNew().call(() -> target.updateIndex(2)),
                    "a page of two takes the first two of the three, in id order");
            assertEquals(new MessagePostgresIndex.Marker(shared, b.getId()), messagePostgresIndex.getMarker(),
                    "the marker is the shared time, to the microsecond, and the id of the second message");
            assertEquals(1, (int) Tx.requiringNew().call(() -> target.updateIndex(2)),
                    "the next page takes the third message, which shares the marker's time");
            assertEquals(new MessagePostgresIndex.Marker(shared, c.getId()), messagePostgresIndex.getMarker());
            assertEquals(0, (int) Tx.requiringNew().call(() -> target.updateIndex(2)));
        } finally {
            target.lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // LUCENE-6: a message's `updated` time is stamped when its transaction writes it, and transactions commit in
    // another order. A message stamped before the newest row's time, and committed after the pass that wrote that
    // row, lies below the marker; the next pass reads only after the marker, so the table never held it (until the
    // message was saved again). Each pass now also indexes the messages of a five-minute window behind the marker
    // whose rows do not hold their updated time. Two such transactions here: one creates a message, one changes a
    // message the table already holds; each stamps its message two seconds before the marker and commits after the
    // pass. The test holds the indexer's lock, so no scheduled pass runs in between.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessageCommittedAfterAPassReadPastItsTimeIsIndexedByTheNextPass() throws Exception {
        String lateToken = uniqueToken();
        String oldToken = uniqueToken();
        String newToken = uniqueToken();
        MessagePostgresIndex target = AopTestUtils.getUltimateTargetObject(messagePostgresIndex);
        target.lock.lock();
        try {
            Message saved = createCommitted("dma-nw", Type.COASTAL_WARNING, "Saved " + oldToken, "subject", "details");
            createCommitted("dma-nw", Type.COASTAL_WARNING, "Marker " + uniqueToken(), "subject", "details");
            messagePostgresIndex.recreateIndex();
            MessagePostgresIndex.Marker marker = messagePostgresIndex.getMarker();
            Instant before = marker.updated().toInstant().minusSeconds(2);

            Message late = Tx.requiringNew().call(() -> {
                Message m = messageService.createMessage(
                        newDraft("dma-nw", Type.COASTAL_WARNING, "Late " + lateToken, "subject", "details"));
                stamp(m.getId(), before);
                return m;
            });
            createdMessageIds.add(late.getId());
            Tx.requiringNew().run(() -> {
                em.find(Message.class, saved.getId()).getDesc("en").setTitle("Saved " + newToken);
                em.flush();
                stamp(saved.getId(), before.plusMillis(1));
            });
            assertEquals(before, updatedOf(late.getId()), "the late message is committed with its early time");
            assertEquals(before.plusMillis(1), updatedOf(saved.getId()));

            assertEquals(2, messagePostgresIndex.updatePostgresIndex(),
                    "the next pass indexes the two messages behind its marker");
            assertEquals(marker, messagePostgresIndex.getMarker(), "the marker does not move back, nor forward");
            assertTrue(search(lateToken).contains(late.getId().longValue()),
                    "the message committed after the pass read past its time is found");
            assertTrue(search(newToken).contains(saved.getId().longValue()),
                    "the change committed after the pass read past its time is found");
            assertFalse(search(oldToken).contains(saved.getId().longValue()), "and the old text is gone");

            assertEquals(0, messagePostgresIndex.updatePostgresIndex(),
                    "a pass after it finds every message of the window as it is now");
        } finally {
            target.lock.unlock();
        }
    }

    /** Sets the message's updated time in the caller's transaction, as a write stamped then would have */
    private void stamp(int messageId, Instant updated) {
        em.createQuery("update Message m set m.updated = :t where m.id = :id")
                .setParameter("t", Timestamp.from(updated))
                .setParameter("id", messageId)
                .executeUpdate();
    }

    /** The message's updated time as committed */
    private Instant updatedOf(int messageId) {
        return Tx.requiringNew().call(() -> em.createQuery("select m.updated from Message m where m.id = :id",
                        java.util.Date.class)
                .setParameter("id", messageId)
                .getSingleResult()
                .toInstant());
    }

    // ------------------------------------------------------------------------------------------- //
    // The incremental marker (max msg_updated) must advance past an EXCLUDED newest message, like the
    // Lucene index advances its marker over excluded messages — otherwise the scheduler would
    // re-process the excluded message every pass (and could stall). A tombstone row carries the marker.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void incrementalMarkerAdvancesPastAnExcludedNewestMessage() throws Exception {
        createCommitted("dma-nw", Type.COASTAL_WARNING, "marker A " + uniqueToken(), "subject", "details");

        Message b = createCommitted("dma-nw", Type.COASTAL_WARNING, "marker B " + uniqueToken(), "subject", "details");
        messageService.updateStatus(b.getUid(), Status.DELETED); // b is now excluded AND the newest write

        messagePostgresIndex.recreateIndex();

        long bRows = Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "select count(*) from message_search_text where message_id = ?")
                .setParameter(1, b.getId())
                .getSingleResult()).longValue());
        assertEquals(1L, bRows,
                "an excluded (DELETED) message must leave exactly one marker row so the incremental marker advances past it");
        assertFalse(search("marker").contains(b.getId().longValue()),
                "the excluded (DELETED) message must still be absent from search results");
    }
}
