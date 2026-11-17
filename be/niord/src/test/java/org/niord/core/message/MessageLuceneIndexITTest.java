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

import org.springframework.transaction.annotation.Transactional;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for {@link MessageLuceneIndex}, the free-text Lucene index over all
 * messages. Unlike the other Niord ITs these tests must use COMMITTED data rather than
 * {@link io.quarkus.test.TestTransaction}: the index is built by re-querying the database
 * ({@code findUpdatedMessagesAfter}) in a fresh transaction, so it can only ever see rows that have
 * actually been committed. Each test therefore creates its message(s) through
 * {@link MessageService#createMessage(Message)} (a {@code @Transactional} method that commits when
 * invoked outside an enclosing transaction), calls {@link MessageLuceneIndex#recreateIndex()} to
 * rebuild the index from scratch, and then asserts on {@link MessageLuceneIndex#searchIndex}.
 *
 * <p>To stay independent and repeatable every test embeds a globally-unique alphabetic token
 * ({@code "zztok" + System.nanoTime()}) in the field under test, so a token can only ever be present
 * because of the row this test created. All created rows (and any throw-away message series) are
 * tracked and removed in {@link #cleanup()}, after which the index is recreated so the next test
 * starts from a clean index.
 *
 * <p>The covered behaviour mirrors {@code MessageLuceneIndex.addMessageToDocument} /
 * {@code shouldAddMessage} / {@code searchIndex}:
 * <ul>
 *   <li>indexing of the message title, message-part subject and message-part details (html2txt);</li>
 *   <li>OR default operator (a multi-token query matches a message that has only one of the tokens);</li>
 *   <li>quoted phrase search via the {@code ComplexPhraseQueryParser} (order-sensitive);</li>
 *   <li>accent folding through {@code LuceneUtils.normalize} (e.g. {@code café -> cafe});</li>
 *   <li>leading-wildcard support, and the literal mode for callers without an identity, in which
 *       wildcard, regular-expression and boolean syntax is matched as text;</li>
 *   <li>exclusion of {@code DELETED} messages and of series flagged {@code excludeFromMessageIndex};</li>
 *   <li>the message count returned by {@code recreateIndex()};</li>
 *   <li>the incremental marker, the time and the id of the last message indexed.</li>
 * </ul>
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageLuceneIndexITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @Autowired
    MessageLuceneIndex messageLuceneIndex;

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
            messageLuceneIndex.recreateIndex();
        } catch (Exception ignored) {
            // best-effort: leave the index empty for the next test
        }
    }

    /** A globally-unique, purely-alphabetic search token. */
    private String uniqueToken() {
        return "zztok" + System.nanoTime();
    }

    /** Builds a detached DRAFT message template in the given series. */
    private Message newDraft(String seriesId, Type type, String title, String subject, String details) {
        Message msg = new Message();
        // createMessage substitutes the persisted series looked up by id.
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

    private List<Long> search(String query) throws Exception {
        return messageLuceneIndex.searchIndex(query, "en", 100);
    }

    // ------------------------------------------------------------------------------------------- //
    // Field coverage: title / subject / details are all indexed and searchable
    // ------------------------------------------------------------------------------------------- //

    @Test
    void findsMessageByUniqueTitleToken() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "Wreck near harbour " + token, "subject", "details");

        messageLuceneIndex.recreateIndex();

        List<Long> ids = search(token);
        assertTrue(ids.contains(m.getId().longValue()),
                "a message must be found by a unique token in its title");
    }

    @Test
    void findsMessageByUniquePartSubjectToken() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title", "Hazard " + token, "details");

        messageLuceneIndex.recreateIndex();

        List<Long> ids = search(token);
        assertTrue(ids.contains(m.getId().longValue()),
                "a message must be found by a unique token in a message-part subject");
    }

    @Test
    void findsMessageByUniquePartDetailsToken() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title", "subject", "Detailed warning about " + token);

        messageLuceneIndex.recreateIndex();

        List<Long> ids = search(token);
        assertTrue(ids.contains(m.getId().longValue()),
                "a message must be found by a unique token in the message-part details");
    }

    @Test
    void findsMessageByTokenInsideHtmlDetails() throws Exception {
        // Details are run through TextUtils.html2txt before indexing, so the plain text token inside
        // markup must still be found, while the tag names must not be.
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title", "subject", "<p><b>" + token + "</b> reported</p>");

        messageLuceneIndex.recreateIndex();

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

        messageLuceneIndex.recreateIndex();

        List<Long> ids = search(absent);
        assertFalse(ids.contains(m.getId().longValue()),
                "a message must not be returned for a token that is not present anywhere in it");
    }

    // ------------------------------------------------------------------------------------------- //
    // Default OR operator
    // ------------------------------------------------------------------------------------------- //

    @Test
    void multiTokenQueryUsesOrSemantics() throws Exception {
        // The message contains only tokenA. A "tokenA tokenB" query (default operator OR) must match
        // it on the strength of tokenA alone.
        String tokenA = uniqueToken();
        String tokenB = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + tokenA, "subject", "details");

        messageLuceneIndex.recreateIndex();

        List<Long> ids = search(tokenA + " " + tokenB);
        assertTrue(ids.contains(m.getId().longValue()),
                "with the default OR operator, matching one of the two tokens must be enough");
    }

    // ------------------------------------------------------------------------------------------- //
    // Quoted phrase search (order sensitive)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void phraseSearchMatchesAdjacentTokensInOrder() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "alpha " + token + " bravo", "subject", "details");

        messageLuceneIndex.recreateIndex();

        List<Long> ids = search("\"" + token + " bravo\"");
        assertTrue(ids.contains(m.getId().longValue()),
                "a quoted phrase in the indexed order must match");
    }

    @Test
    void phraseSearchDoesNotMatchReversedOrder() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "alpha " + token + " bravo", "subject", "details");

        messageLuceneIndex.recreateIndex();

        List<Long> ids = search("\"bravo " + token + "\"");
        assertFalse(ids.contains(m.getId().longValue()),
                "a quoted phrase in the wrong order must not match");
    }

    // ------------------------------------------------------------------------------------------- //
    // Accent folding via LuceneUtils.normalize
    // ------------------------------------------------------------------------------------------- //

    @Test
    void accentFoldedTokenIsFoundWithoutAccent() throws Exception {
        // 'é' is in the LuceneUtils accent table and folds to 'e', so "café<uniq>" indexes as
        // "cafe<uniq>" and a search without the accent must match.
        String suffix = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "café" + suffix + " sighting", "subject", "details");

        messageLuceneIndex.recreateIndex();

        List<Long> ids = search("cafe" + suffix);
        assertTrue(ids.contains(m.getId().longValue()),
                "an accented token must be findable using its accent-folded form");
    }

    // ------------------------------------------------------------------------------------------- //
    // Leading wildcard (allowed: parser.setAllowLeadingWildcard(true))
    // ------------------------------------------------------------------------------------------- //

    @Test
    void leadingWildcardMatchesTokenSuffix() throws Exception {
        String token = uniqueToken(); // e.g. zztok<digits>
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + token, "subject", "details");

        messageLuceneIndex.recreateIndex();

        // Drop the leading "zz" and search by suffix with a leading wildcard.
        String suffix = token.substring(2);
        List<Long> ids = search("*" + suffix);
        assertTrue(ids.contains(m.getId().longValue()),
                "a leading-wildcard query must match a token by its suffix");
    }

    // ------------------------------------------------------------------------------------------- //
    // Literal mode (callers without an identity): query syntax is matched as text
    // ------------------------------------------------------------------------------------------- //

    private List<Long> searchLiteral(String query) throws Exception {
        return messageLuceneIndex.searchIndex(query, "en", 100, true);
    }

    @Test
    void literalModeDoesNotExpandALeadingWildcard() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + token, "subject", "details");

        messageLuceneIndex.recreateIndex();

        String suffix = token.substring(2);
        assertTrue(search("*" + suffix).contains(m.getId().longValue()),
                "control: with query syntax the leading wildcard matches the suffix");
        assertFalse(searchLiteral("*" + suffix).contains(m.getId().longValue()),
                "in literal mode a leading wildcard is text and must not match the suffix");
        assertTrue(searchLiteral("*").isEmpty(), "a lone wildcard in literal mode matches nothing");
    }

    @Test
    void literalModeDoesNotRunARegularExpression() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + token, "subject", "details");

        messageLuceneIndex.recreateIndex();

        String regex = "/zztok[0-9]+/";
        assertTrue(search(regex).contains(m.getId().longValue()),
                "control: with query syntax the regular expression matches the token");
        assertFalse(searchLiteral(regex).contains(m.getId().longValue()),
                "in literal mode a regular expression is text and must not match");
        // Unbalanced syntax is text as well, so it is searched rather than failing to parse
        assertFalse(searchLiteral("(\"[" + token.substring(2) + "~").contains(m.getId().longValue()));
    }

    @Test
    void literalModeStillFindsPlainWords() throws Exception {
        String token = uniqueToken();
        String other = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "Wreck near harbour " + token, "subject", "details");

        messageLuceneIndex.recreateIndex();

        assertTrue(searchLiteral(token).contains(m.getId().longValue()),
                "a plain word is still found in literal mode");
        assertTrue(searchLiteral(token.toUpperCase() + " " + other).contains(m.getId().longValue()),
                "literal mode keeps the case-insensitive, OR-default word search");
        assertTrue(searchLiteral(other + " AND " + token).contains(m.getId().longValue()),
                "AND is a word in literal mode, so the OR default still applies");
    }

    // ------------------------------------------------------------------------------------------- //
    // Case-insensitive matching (StandardAnalyzer lowercases)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchIsCaseInsensitive() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + token.toUpperCase(), "subject", "details");

        messageLuceneIndex.recreateIndex();

        // Indexed in upper case, searched in lower case - StandardAnalyzer lowercases both sides.
        List<Long> ids = search(token.toLowerCase());
        assertTrue(ids.contains(m.getId().longValue()),
                "the analyzer lowercases tokens, so search must be case-insensitive");
    }

    // ------------------------------------------------------------------------------------------- //
    // DELETED messages are excluded (shouldAddMessage)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void deletedMessageIsExcludedFromIndex() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + token, "subject", "details");

        // Before deletion the message is indexed and findable.
        messageLuceneIndex.recreateIndex();
        assertTrue(search(token).contains(m.getId().longValue()),
                "the draft message must be indexed before deletion");

        // DRAFT -> DELETED is a legal transition. updateStatus commits (runs outside a tx).
        messageService.updateStatus(m.getUid(), Status.DELETED);

        messageLuceneIndex.recreateIndex();
        assertFalse(search(token).contains(m.getId().longValue()),
                "a DELETED message must be excluded from the index");
    }

    // ------------------------------------------------------------------------------------------- //
    // Series flagged excludeFromMessageIndex are excluded
    // ------------------------------------------------------------------------------------------- //

    @Test
    void messageInExcludedSeriesIsNotIndexed() throws Exception {
        String seriesId = "IT-LUC-excl-" + System.nanoTime();

        // Create a committed NW series flagged to be excluded from the message index.
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

        messageLuceneIndex.recreateIndex();

        assertFalse(search(token).contains(m.getId().longValue()),
                "a message whose series has excludeFromMessageIndex=true must not be indexed");
    }

    @Test
    void messageInNonExcludedSeriesIsIndexed() throws Exception {
        // Control for the exclude-series test: a message in a normal (non-excluded) series IS indexed.
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "title " + token, "subject", "details");

        messageLuceneIndex.recreateIndex();

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

        messageLuceneIndex.recreateIndex();

        List<Long> ids = search(tokenA);
        assertTrue(ids.contains(a.getId().longValue()), "the message carrying tokenA must match");
        assertFalse(ids.contains(b.getId().longValue()),
                "the other message (carrying only tokenB) must not match a tokenA search");
    }

    // ------------------------------------------------------------------------------------------- //
    // recreateIndex() return value
    // ------------------------------------------------------------------------------------------- //

    @Test
    void recreateIndexCountsAtLeastTheCommittedMessages() throws Exception {
        createCommitted("dma-nw", Type.COASTAL_WARNING, "title " + uniqueToken(), "subject", "details");
        createCommitted("dma-nm", Type.TEMPORARY_NOTICE, "title " + uniqueToken(), "subject", "details");

        int count = messageLuceneIndex.recreateIndex();
        assertTrue(count >= createdMessageIds.size(),
                "recreateIndex() must report indexing at least the committed messages (was " + count + ")");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateLuceneIndex() - the @Scheduled(cron="38 */1 * * * ?") incremental index update, invoked directly
    //
    // The no-arg scheduled method acquires the index lock and delegates to updateLuceneIndex(LUCENE_MAX_INDEX_COUNT),
    // which indexes only messages changed since the index's stored marker — the *incremental* path,
    // unlike recreateIndex() which wipes and rebuilds from scratch. This test asserts that a freshly committed
    // message (whose updated timestamp is necessarily newer than the marker) is picked up and becomes searchable
    // after one scheduled invocation. The package-private method is reachable from this same-package test; only the
    // cron firing *time* and the index ReentrantLock concurrency guard are not asserted (the Quarkus scheduler is
    // not driven and a single test thread cannot exercise the lock). See docs/untestable-code.md and
    // docs/framework-coupled-tests.md.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void scheduledUpdateLuceneIndexPicksUpANewlyCommittedMessage() throws Exception {
        String token = uniqueToken();
        Message m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                "Scheduled index " + token, "subject", "details");

        // Drive the incremental scheduled update directly (NOT recreateIndex): the new message must be indexed.
        messageLuceneIndex.updateLuceneIndex();

        assertTrue(search(token).contains(m.getId().longValue()),
                "the scheduled incremental updateLuceneIndex() must index a newly committed message");
    }

    // ------------------------------------------------------------------------------------------- //
    // startUpPass() - the deferred start-up pass that init() schedules on a java.util.Timer
    //
    // The timer task holds the instance itself, not its proxy, and its thread has no transaction. The test runs
    // the pass the same way: on the target object, from a test thread outside any transaction. Indexing a message
    // reads its lazy associations (Message.references among them); when the pass called updateLuceneIndex() on
    // this, past the proxy, the messages arrived detached, the first lazy read threw a LazyInitializationException,
    // and the pass logged it and indexed nothing until the scheduled pass a minute later.
    //
    // The scheduled pass runs in this context too, and had it indexed the message first, the start-up pass would
    // find nothing to index and the test would pass with the bug in place. The test therefore holds the folder's
    // writer lock, which the scheduled pass takes and the start-up pass re-enters on this thread, from before the
    // message is committed until the start-up pass is done.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void startUpPassOnTheInstanceItselfIndexesANewlyCommittedMessage() throws Exception {
        String token = uniqueToken();
        MessageLuceneIndex target = AopTestUtils.getUltimateTargetObject(messageLuceneIndex);

        Message m;
        ReentrantLock lock = MessageLuceneIndex.writerLock(target.indexFolder);
        lock.lock();
        try {
            m = createCommitted("dma-nw", Type.COASTAL_WARNING,
                    "Start-up index " + token, "subject", "details");
            target.startUpPass();
        } finally {
            lock.unlock();
        }

        assertTrue(search(token).contains(m.getId().longValue()),
                "the start-up pass, run on the instance as its timer task does, must index a newly committed message");
    }

    // ------------------------------------------------------------------------------------------- //
    // LUCENE-4: a pass that ends among messages sharing one `updated` time (a bulk load writes thousands, and the
    // sample's messages all share one) must leave the rest of them for the next pass, not skip them: the marker
    // is the time and the id of the last message, and the next pass reads after both. The shared time has
    // microseconds, as one the database writes (now()) has: a marker cut to the millisecond lies before all three,
    // and the next pass would read them again from the first. The test holds the folder's writer lock, so no
    // scheduled pass runs between the pages.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aPassCutInsideMessagesSharingOneUpdatedTimeLeavesTheRestForTheNextPass() throws Exception {
        String token = uniqueToken();
        MessageLuceneIndex target = AopTestUtils.getUltimateTargetObject(messageLuceneIndex);

        ReentrantLock lock = MessageLuceneIndex.writerLock(target.indexFolder);
        lock.lock();
        try {
            Message a = createCommitted("dma-nw", Type.COASTAL_WARNING, "same time A " + token, "subject", "details");
            Message b = createCommitted("dma-nw", Type.COASTAL_WARNING, "same time B " + token, "subject", "details");
            Message c = createCommitted("dma-nw", Type.COASTAL_WARNING, "same time C " + token, "subject", "details");
            messageLuceneIndex.recreateIndex();

            // One updated time for all three, after every message's, with microseconds (a bulk update fires no
            // @PreUpdate)
            Instant shared = Instant.ofEpochMilli(Math.max(System.currentTimeMillis(),
                    target.getMarker().updated().toEpochMilli() + 1)).plusNanos(456_000);
            Tx.requiringNew().run(() -> em.createQuery("update Message m set m.updated = :t where m.id in :ids")
                    .setParameter("t", Timestamp.from(shared))
                    .setParameter("ids", List.of(a.getId(), b.getId(), c.getId()))
                    .executeUpdate());

            assertEquals(2, (int) Tx.requiringNew().call(() -> target.updateLuceneIndex(2)),
                    "a page of two takes the first two of the three, in id order");
            assertEquals(new MessageLuceneIndex.Marker(shared, b.getId()), target.getMarker(),
                    "the marker is the shared time, to the microsecond, and the id of the second message");
            assertEquals(1, (int) Tx.requiringNew().call(() -> target.updateLuceneIndex(2)),
                    "the next page takes the third message, which shares the marker's time");
            assertEquals(new MessageLuceneIndex.Marker(shared, c.getId()), target.getMarker());
            assertEquals(0, (int) Tx.requiringNew().call(() -> target.updateLuceneIndex(2)));

            assertEquals(List.of(a.getId().longValue(), b.getId().longValue(), c.getId().longValue()),
                    search(token).stream().sorted().toList(), "all three messages are found");
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // LUCENE-6: a message's `updated` time is stamped when its transaction writes it, and transactions commit in
    // another order. A message stamped before the time a pass read up to, and committed after that pass, lies below
    // the marker; the next pass reads only after the marker, so it was never indexed (until a restart rebuilt the
    // index). Each pass now also indexes the messages of a five-minute window behind the marker that it has not
    // indexed as they are now. Two such transactions here: one creates a message, one changes a message the index
    // already holds; each stamps its message two seconds before the marker and commits after the pass. The test
    // holds the folder's writer lock, so no scheduled pass runs in between.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aMessageCommittedAfterAPassReadPastItsTimeIsIndexedByTheNextPass() throws Exception {
        String lateToken = uniqueToken();
        String oldToken = uniqueToken();
        String newToken = uniqueToken();
        MessageLuceneIndex target = AopTestUtils.getUltimateTargetObject(messageLuceneIndex);

        ReentrantLock lock = MessageLuceneIndex.writerLock(target.indexFolder);
        lock.lock();
        try {
            Message saved = createCommitted("dma-nw", Type.COASTAL_WARNING, "Saved " + oldToken, "subject", "details");
            createCommitted("dma-nw", Type.COASTAL_WARNING, "Marker " + uniqueToken(), "subject", "details");
            messageLuceneIndex.recreateIndex();
            MessageLuceneIndex.Marker marker = target.getMarker();
            Instant before = marker.updated().minusSeconds(2);

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

            assertEquals(2, (int) Tx.requiringNew().call(
                            () -> target.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT)),
                    "the next pass indexes the two messages behind its marker");
            assertEquals(marker, target.getMarker(), "the marker does not move back, nor forward");
            assertTrue(search(lateToken).contains(late.getId().longValue()),
                    "the message committed after the pass read past its time is found");
            assertTrue(search(newToken).contains(saved.getId().longValue()),
                    "the change committed after the pass read past its time is found");
            assertFalse(search(oldToken).contains(saved.getId().longValue()), "and the old text is gone");

            assertEquals(0, (int) Tx.requiringNew().call(
                            () -> target.updateLuceneIndex(MessageLuceneIndex.LUCENE_MAX_INDEX_COUNT)),
                    "a pass after it does not index the window again");
        } finally {
            lock.unlock();
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
}
