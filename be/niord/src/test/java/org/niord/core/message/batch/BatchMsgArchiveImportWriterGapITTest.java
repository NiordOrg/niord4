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
import org.niord.core.message.batch.BatchMsgArchiveImportProcessor.ExtractedArchiveMessage;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the JSR-352 batch <em>message-archive</em> import writer
 * ({@link BatchMsgArchiveImportWriter}), exercised against the seeded MySQL container of the
 * {@link ITTest} infrastructure.
 *
 * <p>Unlike {@link BatchMessageImportWriter} (covered by {@link BatchMessageImportReaderWriterITTest}),
 * the archive writer always routes through {@link MessageService#createMessage} — it imports a fresh
 * message archive entry rather than re-saving an existing one — and additionally synchronises the
 * extracted archive's edit-repo temp folder back into the message repository via
 * {@link MessageService#updateMessageFromTempRepoFolder}. Its chunk items are
 * {@link ExtractedArchiveMessage} records (a {@code Message} + an {@code editRepoPath}), not bare
 * {@code SystemMessageVo}s.
 *
 * <p>These artifacts are {@code @Dependent}/{@code @Named} JBeret chunk components: in production they
 * are instantiated by the batch runtime, which injects the {@code JobContext}, the {@code BatchService},
 * the per-instance {@link BatchData} ({@code job}) and a {@code FileHandler}-backed logger that writes
 * into the batch job folder, then drives {@code writeItems()} as part of a running chunk step. To test
 * the observable behaviour without that runtime — following the same subclass-and-wire pattern used by
 * {@link BatchMessageImportReaderWriterITTest} and {@link BatchMsgArchiveImportProcessorITTest} — this
 * suite uses a {@link TestableWriter} subclass declared in this same package that:
 * <ul>
 *   <li>overrides {@code getLog()} to return a parent-less no-op logger, so the batch-folder
 *       {@code FileHandler} (which targets a non-existent batch job folder) is never opened;</li>
 *   <li>exposes a {@code wire(...)} method that assigns the real CDI {@link MessageService} and
 *       {@link MessageTagService} beans and a hand-built {@link BatchData} ({@code job}) directly into
 *       the protected fields (the writer reads {@code tagId}/{@code domain} from the job to resolve the
 *       optional message tag).</li>
 * </ul>
 *
 * <p>Each {@link ExtractedArchiveMessage} item is built with a fresh, detached draft NW message and a
 * <em>blank</em> {@code editRepoPath}. With a blank edit-repo path,
 * {@link org.niord.core.repo.RepositoryService#updateRepoFolderFromTempEditFolder} is a no-op (it only
 * acts when both {@code repoPath} and {@code editRepoPath} are non-blank and the source folder exists),
 * so the writer's attachment-sync step touches no filesystem and the assertions can focus purely on the
 * persistence and tag-membership behaviour.
 *
 * <p>Behaviour covered: a single archive item creates exactly one new {@link Message} row (reloaded by
 * uid from a cleared persistence context); each persisted message is added to the tag named by the job's
 * {@code tagId} property (reloaded {@link MessageTag} message-count and member uids asserted); with no
 * {@code tagId} no tag membership is recorded; two items yield two distinct rows; an empty item list is a
 * no-op that leaves the tag empty.
 *
 * <p>Every test is wrapped in {@link TestTransaction}: the writer's {@code createMessage} and tag writes
 * join the test transaction and are rolled back, keeping the shared seed database pristine and the tests
 * independent and repeatable. Persisted state is asserted after {@code em.flush()}/{@code em.clear()}
 * reloads so we observe what really hit the database.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchMsgArchiveImportWriterGapITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageTagService messageTagService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Writer scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchMsgArchiveImportWriter} that can be driven outside the JBeret batch runtime: it uses
     * a quiet logger and lets the test inject the real {@link MessageService}/{@link MessageTagService}
     * beans and the {@code job} batch data directly. The writer reads the {@code tagId}/{@code domain}
     * from the job to resolve the optional message tag.
     */
    static class TestableWriter extends BatchMsgArchiveImportWriter {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-writer-arch");
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
        series.setMainType(MainType.NW); // createMessage validates the type/main-type against the series
        msg.setMessageSeries(series);
        msg.setType(Type.COASTAL_WARNING);
        msg.setAutoTitle(false);
        MessagePart part = new MessagePart(MessagePartType.DETAILS);
        part.createDesc("en").setSubject("subject");
        msg.addPart(part);
        msg.createDesc("en").setTitle(title);
        return msg;
    }

    /**
     * Wraps a fresh draft NW message in an {@link ExtractedArchiveMessage} with a <em>blank</em>
     * edit-repo path, so the writer's temp-folder sync is a guaranteed no-op (no attachments to copy).
     */
    private ExtractedArchiveMessage archiveItem(String title) {
        return new ExtractedArchiveMessage(newDraftNw(title), "");
    }

    /** Creates a PUBLIC message tag with a unique name and returns the managed entity. */
    private MessageTag createPublicTag() {
        MessageTag tag = new MessageTag();
        tag.setType(MessageTagType.PUBLIC);
        tag.setName("IT-Archive-Tag-" + UUID.randomUUID());
        return messageTagService.createMessageTag(tag);
    }

    /** Reloads a message by uid from the database (after a clear) and asserts there is exactly one. */
    private Message reloadByUid(String uid) {
        List<Message> rows = em.createNamedQuery("Message.findByUids", Message.class)
                .setParameter("uids", List.of(uid))
                .getResultList();
        assertEquals(1, rows.size(), "exactly one message row must carry uid " + uid);
        return rows.get(0);
    }

    // ------------------------------------------------------------------------------------------- //
    // Single-item create
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void writerCreatesExactlyOneNewMessageRow() throws Exception {
        ExtractedArchiveMessage item = archiveItem("Archive import create");
        Message msg = item.getMessage();
        assertTrue(msg.isNew(), "precondition: a detached archive message with no id must be new");

        TestableWriter writer = writerFor(jobWithTagId(null));
        writer.writeItems(new ArrayList<>(List.of(item)));

        // createMessage assigned a generated primary key, a uid and a default DRAFT status.
        Integer id = msg.getId();
        assertNotNull(id, "writeItems must create the archive message, assigning a primary key");
        String uid = msg.getUid();
        assertNotNull(uid, "createMessage must assign a uid to the archive message");
        assertEquals(Status.DRAFT, msg.getStatus(), "an imported archive message defaults to DRAFT");

        // Confirm exactly one row really hit the database, reloaded from a cleared context.
        em.flush();
        em.clear();
        Message reloaded = reloadByUid(uid);
        assertEquals(id, reloaded.getId(), "the created archive message must be the single persisted row");
    }

    // ------------------------------------------------------------------------------------------- //
    // Tag membership
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void writerAddsImportedMessageToTag() throws Exception {
        MessageTag tag = createPublicTag();
        Integer tagId = tag.getId();
        assertEquals(0, tag.getMessageCount(), "precondition: a fresh tag holds no messages");
        em.flush();

        ExtractedArchiveMessage item = archiveItem("Tagged archive import");

        TestableWriter writer = writerFor(jobWithTagId(tag.getTagId()));
        writer.writeItems(new ArrayList<>(List.of(item)));
        String importedUid = item.getMessage().getUid();

        em.flush();
        em.clear();
        MessageTag reloaded = em.find(MessageTag.class, tagId);
        assertNotNull(reloaded, "the tag must still exist after the import");
        assertEquals(1, reloaded.getMessageCount(),
                "saveMessageTag must persist a message count matching the single imported archive message");
        assertEquals(1, reloaded.getMessages().size(),
                "the imported archive message must be a member of the tag");
        List<String> memberUids = reloaded.getMessages().stream().map(Message::getUid).toList();
        assertTrue(memberUids.contains(importedUid),
                "the tag must contain exactly the imported archive message, by uid");
    }

    @Test
    @Transactional
    void writerAddsEveryImportedMessageToTag() throws Exception {
        MessageTag tag = createPublicTag();
        Integer tagId = tag.getId();
        em.flush();

        ExtractedArchiveMessage a = archiveItem("Tagged archive A");
        ExtractedArchiveMessage b = archiveItem("Tagged archive B");

        TestableWriter writer = writerFor(jobWithTagId(tag.getTagId()));
        writer.writeItems(new ArrayList<>(List.of(a, b)));
        String uidA = a.getMessage().getUid();
        String uidB = b.getMessage().getUid();

        em.flush();
        em.clear();
        MessageTag reloaded = em.find(MessageTag.class, tagId);
        assertEquals(2, reloaded.getMessageCount(),
                "both imported archive messages must be counted in the tag");
        List<String> memberUids = reloaded.getMessages().stream().map(Message::getUid).toList();
        assertTrue(memberUids.contains(uidA) && memberUids.contains(uidB),
                "the tag must contain both imported archive messages, by uid");
    }

    @Test
    @Transactional
    void writerWithoutTagIdLeavesNoTagMembership() throws Exception {
        // A tag exists, but the job carries no tagId, so getMessageTag() returns null and nothing is tagged.
        MessageTag tag = createPublicTag();
        Integer tagId = tag.getId();
        em.flush();

        ExtractedArchiveMessage item = archiveItem("Untagged archive import");
        TestableWriter writer = writerFor(jobWithTagId(null));
        writer.writeItems(new ArrayList<>(List.of(item)));

        // The message was still created...
        assertNotNull(item.getMessage().getId(), "the archive message must still be created without a tag");

        em.flush();
        em.clear();
        MessageTag reloaded = em.find(MessageTag.class, tagId);
        assertEquals(0, reloaded.getMessageCount(),
                "with no tagId property the writer must not add the message to any tag");
        assertTrue(reloaded.getMessages().isEmpty(), "the tag must remain empty");
    }

    // ------------------------------------------------------------------------------------------- //
    // Multiple items -> distinct rows
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void writerCreatesTwoDistinctRowsForTwoItems() throws Exception {
        ExtractedArchiveMessage a = archiveItem("Archive distinct A");
        ExtractedArchiveMessage b = archiveItem("Archive distinct B");

        TestableWriter writer = writerFor(jobWithTagId(null));
        writer.writeItems(new ArrayList<>(List.of(a, b)));

        Integer idA = a.getMessage().getId();
        Integer idB = b.getMessage().getId();
        String uidA = a.getMessage().getUid();
        String uidB = b.getMessage().getUid();
        assertNotNull(idA, "the first archive item must be created");
        assertNotNull(idB, "the second archive item must be created");
        assertFalse(idA.equals(idB), "two archive items must yield two distinct primary keys");
        assertFalse(uidA.equals(uidB), "two archive items must yield two distinct uids");

        // Both uids resolve to exactly one row each in the database.
        em.flush();
        em.clear();
        long count = em.createNamedQuery("Message.findByUids", Message.class)
                .setParameter("uids", List.of(uidA, uidB))
                .getResultList()
                .size();
        assertEquals(2L, count, "two archive items must create exactly two distinct message rows");
    }

    // ------------------------------------------------------------------------------------------- //
    // Empty batch
    // ------------------------------------------------------------------------------------------- //

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
                "writing an empty archive batch must leave the tag empty");
    }
}
