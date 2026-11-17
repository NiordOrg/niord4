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
package org.niord.core.message.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.message.Message;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.message.MessageTag;
import org.niord.core.message.MessageTagService;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the JSR-352 batch import <em>reader</em> ({@link BatchMessageImportReader}) and
 * <em>writers</em> ({@link BatchMessageImportWriter}, and through it the shared
 * {@link AbstractMessageImportWriter} message-tag logic), exercised against the seeded MySQL container of
 * the {@link ITTest} infrastructure.
 *
 * <p>These artifacts are {@code @Dependent}/{@code @Named} JBeret chunk components: in production they are
 * instantiated by the batch runtime, which injects the {@code JobContext}, the {@code BatchService}, the
 * per-instance {@link BatchData} ({@code job}) and a {@code FileHandler}-backed logger that writes into the
 * batch job folder, and then drives {@code open()/readItem()/checkpointInfo()} and {@code writeItems()} as
 * part of a running chunk step. To test the observable behaviour without that runtime — following the same
 * subclass-and-wire pattern already used by {@link BatchMessageImportProcessorITTest} and
 * {@link BatchMsgArchiveImportProcessorITTest} — this suite uses small test subclasses declared in this same
 * package that:
 * <ul>
 *   <li>override {@code getLog()} to return a parent-less no-op logger, so the batch-folder
 *       {@code FileHandler} (which targets a non-existent batch job folder) is never opened;</li>
 *   <li>for the reader, override the {@code protected readMessages()} hook to return an in-memory message
 *       list (bypassing the {@code JobContext}/{@code BatchService}/JSON-file I/O that
 *       {@code open()} would otherwise perform) and override {@code updateProgress(Integer)} to record the
 *       progress callbacks instead of routing them through the (unwired) {@code BatchService};</li>
 *   <li>for the writer, expose a {@code wire(...)} method that assigns the real CDI {@link MessageService}
 *       and {@link MessageTagService} beans and a hand-built {@link BatchData} ({@code job}) directly into
 *       the package-private/protected fields.</li>
 * </ul>
 *
 * <p>Behaviour covered: the reader's {@code open()} {@code seriesId} guard, the
 * {@code open()}→{@code readItem()} progression, null-on-exhaustion, the checkpoint {@code messageNo}
 * round-trip (resuming from a previous checkpoint), and the periodic progress callback; the writer's
 * create-vs-save dispatch by {@link Message#isNew()} (asserted against persisted DB state) and the
 * {@link AbstractMessageImportWriter#checkAddMessageToTag}/{@code saveMessageTag} tag-membership and
 * message-count maintenance.
 *
 * <p>Every test is wrapped in {@link TestTransaction}: the writer's {@code createMessage}/{@code
 * saveMessage} and tag writes join the test transaction and are rolled back, keeping the shared seed
 * database pristine and the tests independent and repeatable. Persisted state is asserted after
 * {@code em.flush()}/{@code em.clear()} reloads so we observe what really hit the database.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchMessageImportReaderWriterITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageTagService messageTagService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Reader scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchMessageImportReader} that can be driven outside the JBeret batch runtime.
     *
     * <p>It returns a fixed in-memory message list from the {@code readMessages()} hook (so {@code open()}
     * never touches the {@code JobContext}/{@code BatchService}/JSON file), records the progress callbacks
     * in {@link #progressCalls} instead of routing them through the unwired {@code BatchService}, and uses a
     * quiet parent-less logger so no batch-job log file is created.
     */
    static class TestableReader extends BatchMessageImportReader {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-reader-msg");
        { noop.setUseParentHandlers(false); }

        final List<SystemMessageVo> fixture;
        final List<Integer> progressCalls = new ArrayList<>();

        TestableReader(BatchData job, List<SystemMessageVo> fixture) {
            this.job = job;
            this.fixture = fixture;
        }

        @Override protected java.util.logging.Logger getLog() { return noop; }
        @Override protected List<SystemMessageVo> readMessages() { return fixture; }
        @Override protected void updateProgress(Integer progress) { progressCalls.add(progress); }
    }

    /** Builds a {@link BatchData} whose properties map carries the given {@code seriesId} (or none when null). */
    private BatchData jobWithSeriesId(String seriesId) {
        BatchData job = new BatchData();
        if (seriesId != null) {
            job.getProperties().put("seriesId", seriesId);
        }
        return job;
    }

    /** Builds a list of {@code n} distinct {@link SystemMessageVo} fixtures (only identity matters here). */
    private List<SystemMessageVo> messageFixtures(int n) {
        List<SystemMessageVo> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            SystemMessageVo vo = new SystemMessageVo();
            vo.setNumber(1000 + i);
            list.add(vo);
        }
        return list;
    }

    // ------------------------------------------------------------------------------------------- //
    // Reader - open() seriesId guard
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void readerOpenThrowsWhenSeriesIdMissing() {
        TestableReader reader = new TestableReader(jobWithSeriesId(null), messageFixtures(1));

        Exception ex = assertThrows(Exception.class, () -> reader.open(null));
        assertEquals("Missing seriesId batch property", ex.getMessage(),
                "open() must reject a job with no 'seriesId' property");
    }

    @Test
    @Transactional
    void readerOpenThrowsWhenSeriesIdBlank() {
        BatchData job = new BatchData();
        job.getProperties().put("seriesId", "   "); // blank, not just missing
        TestableReader reader = new TestableReader(job, messageFixtures(1));

        Exception ex = assertThrows(Exception.class, () -> reader.open(null));
        assertEquals("Missing seriesId batch property", ex.getMessage(),
                "open() must reject a blank 'seriesId' property as well as a missing one");
    }

    // ------------------------------------------------------------------------------------------- //
    // Reader - readItem() progression & exhaustion
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void readerReadsEveryItemInOrderThenNull() throws Exception {
        List<SystemMessageVo> fixture = messageFixtures(3);
        TestableReader reader = new TestableReader(jobWithSeriesId("dma-nw"), fixture);
        reader.open(null);

        // The three fixtures must be returned in order, by identity.
        assertSame(fixture.get(0), reader.readItem(), "first readItem must return the first fixture");
        assertSame(fixture.get(1), reader.readItem(), "second readItem must return the second fixture");
        assertSame(fixture.get(2), reader.readItem(), "third readItem must return the third fixture");

        // Once the list is exhausted, readItem must signal end-of-stream with null, repeatably.
        assertNull(reader.readItem(), "readItem past the end must return null");
        assertNull(reader.readItem(), "readItem must keep returning null after exhaustion");
    }

    @Test
    @Transactional
    void readerWithEmptyFixtureReturnsNullImmediately() throws Exception {
        TestableReader reader = new TestableReader(jobWithSeriesId("dma-nw"), messageFixtures(0));
        reader.open(null);

        assertNull(reader.readItem(), "an empty fixture must yield null on the very first read");
    }

    @Test
    @Transactional
    void readerReportsProgressAtFirstItem() throws Exception {
        // updateProgress fires for messageNo % 10 == 0; with a small fixture that is only index 0.
        TestableReader reader = new TestableReader(jobWithSeriesId("dma-nw"), messageFixtures(3));
        reader.open(null);

        reader.readItem(); // index 0 -> progress 0
        reader.readItem(); // index 1 -> no progress
        reader.readItem(); // index 2 -> no progress

        assertEquals(List.of(0), reader.progressCalls,
                "progress must be reported exactly once, at the first item, as 0%%");
    }

    // ------------------------------------------------------------------------------------------- //
    // Reader - checkpointInfo round-trip
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void readerCheckpointInfoTracksReadCursor() throws Exception {
        TestableReader reader = new TestableReader(jobWithSeriesId("dma-nw"), messageFixtures(3));
        reader.open(null);

        assertEquals(0, reader.checkpointInfo(), "before any read the checkpoint cursor is 0");
        reader.readItem();
        assertEquals(1, reader.checkpointInfo(), "after one read the checkpoint cursor is 1");
        reader.readItem();
        assertEquals(2, reader.checkpointInfo(), "after two reads the checkpoint cursor is 2");
    }

    @Test
    @Transactional
    void readerResumesFromPreviousCheckpoint() throws Exception {
        List<SystemMessageVo> fixture = messageFixtures(3);
        TestableReader reader = new TestableReader(jobWithSeriesId("dma-nw"), fixture);

        // Resume from checkpoint 2: the first two items were already processed in a prior run.
        reader.open((Serializable) Integer.valueOf(2));

        assertEquals(2, reader.checkpointInfo(),
                "open(prevCheckpointInfo) must restore the read cursor from the checkpoint");
        assertSame(fixture.get(2), reader.readItem(),
                "a resumed reader must continue from the checkpointed index, skipping processed items");
        assertNull(reader.readItem(), "and then be exhausted");
    }

    @Test
    @Transactional
    void readerCheckpointAtEndEqualsListSize() throws Exception {
        List<SystemMessageVo> fixture = messageFixtures(2);
        TestableReader reader = new TestableReader(jobWithSeriesId("dma-nw"), fixture);
        reader.open(null);

        reader.readItem();
        reader.readItem();
        reader.readItem(); // exhausted - must not advance the cursor past the end

        assertEquals(fixture.size(), reader.checkpointInfo(),
                "a fully-read reader's checkpoint must equal the list size and not over-run");
    }

    // ------------------------------------------------------------------------------------------- //
    // Writer scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchMessageImportWriter} that can be driven outside the JBeret batch runtime: it uses a
     * quiet logger and lets the test inject the real {@link MessageService}/{@link MessageTagService} beans
     * and the {@code job} batch data directly (the writer reads the {@code tagId}/{@code domain} from the
     * job to resolve the optional message tag).
     */
    static class TestableWriter extends BatchMessageImportWriter {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-writer-msg");
        { noop.setUseParentHandlers(false); }
        @Override protected java.util.logging.Logger getLog() { return noop; }
        void wire(MessageService ms, MessageTagService mts, BatchData j) {
            this.messageService = ms;
            this.messageTagService = mts;
            this.job = j;
        }
    }

    /** Wires a fresh {@link TestableWriter} with the injected beans and the supplied batch data. */
    private TestableWriter writerFor(BatchData job) {
        TestableWriter writer = new TestableWriter();
        writer.wire(messageService, messageTagService, job);
        return writer;
    }

    /** Builds a {@link BatchData} carrying an optional {@code tagId} property (no domain is set). */
    private BatchData jobWithTagId(String tagId) {
        BatchData job = new BatchData();
        if (tagId != null) {
            job.getProperties().put("tagId", tagId);
        }
        return job;
    }

    /** Builds a detached, un-persisted (isNew) draft NW message that createMessage will accept. */
    private Message newDraftNw(String title) {
        Message msg = new Message();
        MessageSeries series = new MessageSeries("dma-nw");
        series.setMainType(MainType.NW); // updateMessage/createMessage validate the type against the series
        msg.setMessageSeries(series);
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        part.createDesc("en").setSubject("subject");
        msg.addPart(part);
        msg.createDesc("en").setTitle(title);
        return msg;
    }

    /** Persists a minimal DRAFT NW message directly (so it is NOT new) and returns the managed entity. */
    private Message persistMinimalMessage() {
        Message m = new Message();
        String uid = UUID.randomUUID().toString();
        m.setUid(uid);
        m.setRepoPath("messages/it/" + uid);
        m.setMainType(MainType.NW);
        m.setType(Type.LOCAL_WARNING);
        m.setStatus(Status.DRAFT);
        em.persist(m);
        em.flush();
        return m;
    }

    /** Creates a PUBLIC message tag with a unique name and returns the managed entity. */
    private MessageTag createPublicTag() {
        MessageTag tag = new MessageTag();
        tag.setType(MessageTagType.PUBLIC);
        tag.setName("IT-Batch-Tag-" + UUID.randomUUID());
        return messageTagService.createMessageTag(tag);
    }

    // ------------------------------------------------------------------------------------------- //
    // Writer - create vs save dispatch by isNew()
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void writerCreatesNewMessageRow() throws Exception {
        Message fresh = newDraftNw("Batch import create");
        assertTrue(fresh.isNew(), "precondition: a detached message with no id must be new");

        TestableWriter writer = writerFor(jobWithTagId(null));
        writer.writeItems(new ArrayList<>(List.of(fresh)));

        // createMessage assigned a generated primary key, uid and a default DRAFT status.
        Integer id = fresh.getId();
        assertNotNull(id, "writeItems must create the new message, assigning a primary key");
        String uid = fresh.getUid();
        assertNotNull(uid, "createMessage must assign a uid");
        assertEquals(Status.DRAFT, fresh.getStatus(), "an imported new message defaults to DRAFT");

        // Confirm the row really hit the database.
        Message reloaded = em.find(Message.class, id);
        assertNotNull(reloaded, "the created message row must exist in the database");
        assertEquals(uid, reloaded.getUid());
    }

    @Test
    @Transactional
    void writerSavesExistingMessageWithoutCreatingNewRow() throws Exception {
        Message existing = persistMinimalMessage();
        Integer id = existing.getId();
        String uid = existing.getUid();
        assertFalse(existing.isNew(), "precondition: a persisted message must not be new");

        // Re-submitting the persisted entity must go down the saveMessage (not createMessage) path,
        // which would otherwise reject an already-persisted message with "Message already persisted".
        TestableWriter writer = writerFor(jobWithTagId(null));
        writer.writeItems(new ArrayList<>(List.of(existing)));

        em.flush();
        em.clear();
        Message reloaded = em.find(Message.class, id);
        assertNotNull(reloaded, "the existing message row must still exist after a save");
        assertEquals(id, reloaded.getId(), "saveMessage must update the same row, not create a new one");
        assertEquals(uid, reloaded.getUid(), "the uid must be unchanged by the save path");

        // Exactly one row carries this uid - no duplicate was created.
        long count = em.createNamedQuery("Message.findByUids", Message.class)
                .setParameter("uids", List.of(uid))
                .getResultList()
                .size();
        assertEquals(1L, count, "saving an existing message must not create a second row");
    }

    @Test
    @Transactional
    void writerHandlesMixedNewAndExistingItems() throws Exception {
        Message fresh = newDraftNw("Mixed batch new");
        Message existing = persistMinimalMessage();
        String existingUid = existing.getUid();

        TestableWriter writer = writerFor(jobWithTagId(null));
        writer.writeItems(new ArrayList<>(List.of(fresh, existing)));

        // The new one was created (got an id); the existing one kept its id.
        assertNotNull(fresh.getId(), "the new item must be created");
        assertNotNull(fresh.getUid());
        assertNotNull(existing.getId(), "the existing item must keep its id");

        em.flush();
        em.clear();
        assertNotNull(em.find(Message.class, fresh.getId()), "the created row must be in the database");
        Message reloadedExisting = em.find(Message.class, existing.getId());
        assertNotNull(reloadedExisting, "the existing row must remain in the database");
        assertEquals(existingUid, reloadedExisting.getUid());
    }

    // ------------------------------------------------------------------------------------------- //
    // Writer - message-tag membership (AbstractMessageImportWriter)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void writerAddsImportedMessagesToTag() throws Exception {
        MessageTag tag = createPublicTag();
        Integer tagId = tag.getId();
        assertEquals(0, tag.getMessageCount(), "precondition: a fresh tag holds no messages");
        em.flush();

        Message m1 = newDraftNw("Tagged batch A");
        Message m2 = newDraftNw("Tagged batch B");

        TestableWriter writer = writerFor(jobWithTagId(tag.getTagId()));
        writer.writeItems(new ArrayList<>(List.of(m1, m2)));

        em.flush();
        em.clear();
        MessageTag reloaded = em.find(MessageTag.class, tagId);
        assertNotNull(reloaded, "the tag must still exist after the import");
        assertEquals(2, reloaded.getMessageCount(),
                "saveMessageTag must persist a message count matching the two imported messages");
        assertEquals(2, reloaded.getMessages().size(),
                "both imported messages must be members of the tag");
        List<String> memberUids = reloaded.getMessages().stream().map(Message::getUid).toList();
        assertTrue(memberUids.contains(m1.getUid()) && memberUids.contains(m2.getUid()),
                "the tag must contain exactly the two imported messages");
    }

    @Test
    @Transactional
    void writerWithoutTagIdLeavesNoTagMembership() throws Exception {
        // A tag exists, but the job carries no tagId, so getMessageTag() returns null and nothing is tagged.
        MessageTag tag = createPublicTag();
        Integer tagId = tag.getId();
        em.flush();

        Message m = newDraftNw("Untagged batch");
        TestableWriter writer = writerFor(jobWithTagId(null));
        writer.writeItems(new ArrayList<>(List.of(m)));

        em.flush();
        em.clear();
        MessageTag reloaded = em.find(MessageTag.class, tagId);
        assertEquals(0, reloaded.getMessageCount(),
                "with no tagId property the writer must not add the message to any tag");
        assertTrue(reloaded.getMessages().isEmpty(), "the tag must remain empty");
    }

    @Test
    @Transactional
    void writerIsIdempotentForTagMembership() throws Exception {
        // checkAddMessageToTag must not add a message twice. Writing the same persisted message into the
        // same tag across two writeItems calls must leave the membership (and count) at one.
        MessageTag tag = createPublicTag();
        Integer tagId = tag.getId();
        em.flush();

        Message existing = persistMinimalMessage();
        BatchData job = jobWithTagId(tag.getTagId());

        writerFor(job).writeItems(new ArrayList<>(List.of(existing)));
        em.flush();
        assertEquals(1, em.find(MessageTag.class, tagId).getMessageCount(),
                "the first write must add the message once");

        // Second write of the same (now-managed) message must be a no-op for tag membership.
        writerFor(job).writeItems(new ArrayList<>(List.of(existing)));
        em.flush();
        em.clear();

        MessageTag reloaded = em.find(MessageTag.class, tagId);
        assertEquals(1, reloaded.getMessageCount(),
                "checkAddMessageToTag must not add the same message to the tag twice");
        assertEquals(1, reloaded.getMessages().size(), "the tag must still hold a single message");
    }

    @Test
    @Transactional
    void writerWithEmptyBatchDoesNothing() throws Exception {
        MessageTag tag = createPublicTag();
        Integer tagId = tag.getId();
        em.flush();

        // An empty chunk must complete without error and must not touch the tag.
        TestableWriter writer = writerFor(jobWithTagId(tag.getTagId()));
        writer.writeItems(new ArrayList<>());

        em.flush();
        em.clear();
        assertEquals(0, em.find(MessageTag.class, tagId).getMessageCount(),
                "writing an empty batch must leave the tag empty");
    }
}
