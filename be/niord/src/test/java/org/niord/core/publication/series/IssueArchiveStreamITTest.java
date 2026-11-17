/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core.publication.series;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.TestIds;
import org.niord.core.publication.series.criteria.IssueCriteriaVo;
import org.niord.core.publication.series.criteria.MessageSeriesCriterionVo;
import org.niord.core.publication.series.resolve.TimeRelation;
import org.niord.core.publication.series.vo.IssueArchiveVo;
import org.niord.core.publication.vo.MessagePublication;
import org.niord.core.user.User;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import org.niord.test.RepoRows;
import org.niord.core.repo.RepoPaths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading back a superseded document, which is the only thing the archive is for.
 *
 * The store is written on three paths and read on one, and until the read
 * existed the archive was a folder nothing could open -- a guarantee that every
 * generation is kept, with no way to check it. The read is where the guarantee
 * becomes usable, and it is also where the store's location stops being an
 * implementation detail: an address that a request could steer would turn a
 * gated stream into a file browser rooted wherever the application account can
 * read.
 *
 * So the subject here is BOTH halves: the bytes come back, and nothing else
 * does.
 */
public class IssueArchiveStreamITTest extends ITTest {

    /**
     * The stubbed renderer is one bean for the whole module, and the bytes it
     * produces are a static. This class names its own bytes twice to tell one
     * generation from another, so it has to put them back -- otherwise every later
     * test in the JVM renders them, which is a failure that depends on the order
     * the classes happen to run in.
     */
    @org.junit.jupiter.api.AfterEach
    public void restoreTheRenderer() {
        StubIssueRenderService.reset();
    }

    @Autowired
    IssueArchiveService archives;

    @Autowired
    IssueLifecycleService lifecycle;

    @Autowired
    IssuePublishService publishService;

    @Autowired
    IssueAuditService auditService;

    @Autowired
    IssueFileService fileService;

    @Autowired
    PublicationPathService paths;

    @Autowired
    org.niord.core.repo.RepoStore store;


    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------ fixtures

    private PublicationSeries series() {
        PublicationCategory c = new PublicationCategory();
        c.setCategoryId(TestIds.category());
        c.setPriority(100);
        em.persist(c);

        PublicationSeries s = new PublicationSeries();
        s.setSeriesId(TestIds.series());
        s.setStatus(SeriesStatus.ACTIVE);
        s.setContentMode(ContentMode.GENERATED_FROM_QUERY);
        s.setReportId("some-report");
        s.setCadence(SeriesCadence.WEEKLY);
        s.setTimeRelation(TimeRelation.PUBLISHED_IN_INTERVAL);
        s.setAliveAtCutoff(false);
        s.setReleaseMode(ReleaseMode.MANUAL_GATE);
        s.setNextIssueCreation(NextIssueCreation.MANUAL);
        s.setMessagePublication(MessagePublication.NONE);
        s.setNumberingScheme(NumberingScheme.ISO_WEEK_YEAR);
        s.setCategory(c);
        // Every publication names the desk that owns it: the column is NOT NULL and
        // S-20a refuses a save without one, so a fixture that left it out no longer
        // describes a state the system can be in.
        s.setDomain(TestOwnerDomain.of(em));
        s.getLanguages().add("da");

        IssueCriteriaVo doc = new IssueCriteriaVo();
        MessageSeriesCriterionVo node = new MessageSeriesCriterionVo();
        node.setValues(new ArrayList<>(List.of("dma-nm")));
        doc.getCriteria().add(node);
        s.setCriteria(doc);

        s.createDesc("da").setName("Test series");
        em.persist(s);
        return s;
    }

    private User user() {
        User u = new User();
        u.setUsername(TestIds.user());
        em.persist(u);
        return u;
    }

    /**
     * A released issue whose Danish document holds the bytes handed in.
     *
     * The release renders, and the renderer is stubbed in this module, so naming
     * its output is what makes the content of the archived file predictable: it
     * is what went in here.
     */
    private PublicationIssue publishedIssue(String bytes) {
        PublicationSeries s = series();
        PublicationIssue i = lifecycle.create(s, new Date(1_699_000_000_000L),
                IntervalBoundSource.STAMPED, user());
        em.flush();
        StubIssueRenderService.renders(bytes);
        publishService.publish(i.getId(),
                new IssuePublishService.PublishRequest(
                        IssuePublishService.PublishRequest.ALL_WARNINGS, user(),
                        new Date(1_700_000_000_000L)));
        em.flush();
        return em.find(PublicationIssue.class, i.getId());
    }

    /** The entry an amend wrote, which is the one carrying the archive. */
    private IssueAuditEntry amendOf(PublicationIssue issue, String replacementBytes) {
        StubIssueRenderService.renders(replacementBytes);
        publishService.amend(issue.getId(),
                new IssuePublishService.AmendRequest(
                        IssuePublishService.PublishRequest.ALL_WARNINGS, user(),
                        "a chart number was wrong in three of the notices"));
        em.flush();
        return entryOf(issue, AuditAction.AMENDED);
    }

    /**
     * The newest entry of one action.
     *
     * The trail comes back newest first, so the entry the call under test just
     * wrote is the FIRST match rather than the last.
     */
    private IssueAuditEntry entryOf(PublicationIssue issue, AuditAction action) {
        return auditService.forIssue(issue).stream()
                .filter(a -> action == a.getAction())
                .findFirst()
                .orElseThrow(() -> new AssertionError("the trail holds no " + action + " entry"));
    }

    private static void assertRefused(String why, Runnable call) {
        IssueLifecycleService.TransitionRefusedException refusal =
                assertThrows(IssueLifecycleService.TransitionRefusedException.class, call::run, why);
        assertEquals(IssueArchiveService.NOT_FOUND, refusal.code(),
                why + " -- and it must refuse with the archive code, which is the 404 every one of "
                        + "these answers with; any other code tells the caller which of them it was");
    }

    // ================================================================== the read

    /**
     * The bytes an amend superseded come back, under the name they were published
     * with.
     *
     * The whole point of the store, asserted end to end rather than on the writer
     * alone: the writer's own test can only say a file appeared somewhere.
     */
    @Test
    @Transactional
    public void theSupersededDocumentComesBackWithItsOwnBytes() throws Exception {
        PublicationIssue issue = publishedIssue("the released edition");
        String publishedName = issue.getDescs().get(0).getFileName();
        IssueAuditEntry amend = amendOf(issue, "the corrected edition");

        IssueArchiveService.ArchivedFile file = archives.resolve(issue, amend.getId(), "da");

        assertEquals("the released edition", RepoRows.readText(store, file.file().path()),
                "the archive handed back something other than the bytes the amend replaced");
        assertEquals("da", file.lang());
        assertEquals(publishedName, file.fileName(),
                "the file is offered under the stamped name it is stored under rather than the name "
                        + "it had when somebody cited it; every generation carries a stamp so two of "
                        + "them can sit side by side, and that stamp is bookkeeping");

        assertTrue(RepoPaths.isBelow(file.file().path(), RepoPaths.ARCHIVE_ROOT),
                "the resolved file is outside the archive root");
        assertFalse(RepoPaths.isServed(file.file().path()),
                "a superseded edition resolved under a served repository root, where anybody can "
                        + "read it without asking this endpoint at all");
    }

    /**
     * The live document is untouched by the read, and still holds the correction.
     *
     * Named because the two files have the same name and differ only in where
     * they live: a resolver that walked back to the repository would return the
     * corrected document and look entirely successful doing it.
     */
    @Test
    @Transactional
    public void theArchiveAndTheLiveDocumentAreDifferentFiles() throws Exception {
        PublicationIssue issue = publishedIssue("the released edition");
        IssueAuditEntry amend = amendOf(issue, "the corrected edition");

        IssueArchiveService.ArchivedFile archived = archives.resolve(issue, amend.getId(), "da");
        String live = issue.getDescs().get(0).getFilePath();

        assertEquals("the corrected edition", RepoRows.readText(store, live));
        assertEquals("the released edition", RepoRows.readText(store, archived.file().path()));
        assertFalse(live.equals(archived.file().path()), "the archive and the live document are different rows");
    }

    /**
     * A document whose name carries a comma comes back, on both writers.
     *
     * "EfS uge 27, 2026.pdf" is an ordinary name and nothing refuses it. The
     * published name is carried into the archived file's own name, and the column
     * that lists the archived locations used to be joined and split on commas --
     * so one stored location came back as two halves, neither of them a file, and
     * the download answered ARCHIVE_ENTRY_NOT_FOUND for a document that had been
     * archived correctly and was sitting on disk. Nothing said why: the refusal is
     * deliberately the same for every cause.
     *
     * Both entries are read, because both writers compose the column: the release
     * archives what it publishes over, and the hand replacement archives the
     * released bytes.
     */
    @Test
    @Transactional
    public void adocumentWhoseNameCarriesACommaComesBack() throws Exception {
        String named = "EfS uge 27, 2026.pdf";
        PublicationSeries s = series();
        PublicationIssue issue = lifecycle.create(s, new Date(1_699_000_000_000L),
                IntervalBoundSource.STAMPED, user());
        em.flush();

        // Uploaded rather than rendered, because the upload is where a name with a
        // comma comes from; the sticky flag then keeps the release from writing a
        // pattern-derived name over it.
        fileService.upload(issue, "da", named,
                "the uploaded edition".getBytes(StandardCharsets.UTF_8), user());
        em.flush();

        publishService.publish(issue.getId(),
                new IssuePublishService.PublishRequest(
                        IssuePublishService.PublishRequest.ALL_WARNINGS, user(),
                        new Date(1_700_000_000_000L)));
        em.flush();

        PublicationIssue published = em.find(PublicationIssue.class, issue.getId());
        assertEquals(named, published.getDescs().get(0).getFileName(),
                "the release renamed the uploaded document, so this proves nothing about commas");

        IssueAuditEntry release = entryOf(published, AuditAction.PUBLISHED);
        IssueArchiveService.ArchivedFile fromRelease = archives.resolve(published, release.getId(), "da");
        assertEquals(named, fromRelease.fileName(),
                "the archived document is offered under a name the comma was cut out of");
        assertEquals("the uploaded edition", RepoRows.readText(store, fromRelease.file().path()));

        // The post-publish correction path, which archives the released bytes.
        fileService.upload(published, "da", named,
                "the corrected edition".getBytes(StandardCharsets.UTF_8), user());
        em.flush();

        IssueAuditEntry replacement = entryOf(published, AuditAction.FILE_REPLACED_MANUALLY);
        IssueArchiveService.ArchivedFile superseded =
                archives.resolve(published, replacement.getId(), "da");
        assertEquals(named, superseded.fileName());
        assertEquals("the uploaded edition", RepoRows.readText(store, superseded.file().path()),
                "the replacement's archive does not hold the bytes it replaced");

        IssueArchiveVo archive = replacement.toVo().getArchive();
        assertNotNull(archive, "the replacement's history line carries no archive to open");
        assertEquals(1, archive.getFiles().size(),
                "one archived file was listed as more than one, so the column was split inside a name");
        assertEquals(named, archive.getFiles().get(0).getFileName());
    }

    /**
     * A row written before the column was a JSON array still resolves.
     *
     * The archive is kept indefinitely, so every row the old writer produced is
     * still the address of a file somebody may ask for. A name with no comma in it
     * was never ambiguous, and those rows have to go on parsing exactly as they
     * did -- including the two-language form, which is the whole reason the column
     * held a list.
     */
    @Test
    @Transactional
    public void alegacyCommaSeparatedColumnStillResolves() throws Exception {
        PublicationIssue issue = publishedIssue("the released edition");

        String da = IssueArchiveService.archivePathFor(issue.getPublicId(), "da", "legacy.pdf", 1_700_000_000_000L);
        String en = IssueArchiveService.archivePathFor(issue.getPublicId(), "en", "legacy.pdf", 1_700_000_000_000L);
        for (String p : List.of(da, en)) {
            store.write(p, "an edition archived by the old writer".getBytes(StandardCharsets.UTF_8),
                    java.time.Instant.now());
        }

        IssueAuditEntry legacy = crafted(issue, da + "," + en);

        assertEquals(da, archives.resolve(issue, legacy.getId(), "da").file().path());
        assertEquals(en, archives.resolve(issue, legacy.getId(), "en").file().path());
        assertEquals("legacy.pdf", archives.resolve(issue, legacy.getId(), "da").fileName());
    }

    // ============================================================== the refusals

    /**
     * An audit entry of ANOTHER issue is not found, and is not forbidden.
     *
     * The distinction is the whole refusal design. "Not yours" has already
     * confirmed the row exists, so an admin of one domain could walk the id space
     * and learn how many amendments every other issue in the system has had. The
     * only safe answer is the one that says nothing.
     */
    @Test
    @Transactional
    public void anEntryOfAnotherIssueIsNotFound() {
        PublicationIssue mine = publishedIssue("mine");
        PublicationIssue theirs = publishedIssue("theirs");
        IssueAuditEntry theirAmend = amendOf(theirs, "their correction");

        assertRefused("an entry belonging to another issue resolved against this one",
                () -> archives.resolve(mine, theirAmend.getId(), "da"));

        // And the entry really does resolve for the issue it belongs to, so the
        // refusal above is about ownership rather than about a broken fixture.
        assertNotNull(archives.resolve(theirs, theirAmend.getId(), "da"));
    }

    /** A language the entry did not archive is not found either. */
    @Test
    @Transactional
    public void aLanguageTheEntryDidNotArchiveIsNotFound() {
        PublicationIssue issue = publishedIssue("the released edition");
        IssueAuditEntry amend = amendOf(issue, "the corrected edition");

        assertRefused("a language the entry never archived resolved to a file",
                () -> archives.resolve(issue, amend.getId(), "en"));
    }

    /**
     * An entry that archived nothing has nothing to hand back.
     *
     * Most entries are this: a create, a rename, a curation. The endpoint accepts
     * any entry id rather than only the three archiving actions -- the actions are
     * a moving list and the presence of an archive is the fact that matters -- so
     * the ordinary entry has to be refused on that fact rather than on its action.
     */
    @Test
    @Transactional
    public void anEntryThatArchivedNothingIsNotFound() {
        PublicationIssue issue = publishedIssue("the released edition");
        IssueAuditEntry created = auditService.forIssue(issue).stream()
                .filter(a -> a.getArchivePath() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry without an archive to test with"));

        assertRefused("an entry that preserved nothing resolved to a file",
                () -> archives.resolve(issue, created.getId(), "da"));
    }

    /** An id that is no entry at all. */
    @Test
    @Transactional
    public void anUnknownEntryIdIsNotFound() {
        PublicationIssue issue = publishedIssue("the released edition");

        assertRefused("an audit id that exists nowhere resolved to a file",
                () -> archives.resolve(issue, Integer.MAX_VALUE, "da"));
    }

    /**
     * A stored location outside the archive root is refused even though the file
     * is there.
     *
     * The column is data. It was written by a release that may be years old, by
     * code that no longer runs, and it is turned back into a file handle exactly
     * here -- so it is re-checked here rather than trusted. The fixture points a
     * crafted entry at a real file under the SERVED repository root: the file
     * exists and is readable, so nothing but the containment rule can refuse it.
     */
    @Test
    @Transactional
    public void aLocationOutsideTheArchiveRootIsNotFound() throws Exception {
        PublicationIssue issue = publishedIssue("the released edition");

        String outside = "publications/" + TestIds.id("archive-escape-") + "/da/1700000000000-leaked.pdf";
        RepoRows.writeText(store, outside, "not the archive");
        assertTrue(store.exists(outside), "the fixture's file was not written");

        IssueAuditEntry crafted = crafted(issue, outside);

        assertRefused("a stored location under the served repository root was streamed",
                () -> archives.resolve(issue, crafted.getId(), "da"));
    }

    /** And the same for one that walks out of the root with relative segments. */
    @Test
    @Transactional
    public void aLocationThatWalksOutOfTheArchiveRootIsNotFound() throws Exception {
        PublicationIssue issue = publishedIssue("the released edition");

        String leaked = TestIds.id("archive-escape-") + "/da/1700000000000-leaked.pdf";
        String escape = RepoPaths.ARCHIVE_ROOT + "/../" + leaked;
        RepoRows.writeText(store, leaked, "not the archive");

        IssueAuditEntry crafted = crafted(issue, escape);

        assertRefused("a location walking out of the archive root with .. was streamed",
                () -> archives.resolve(issue, crafted.getId(), "da"));
    }

    /**
     * An entry that says it archived, over a file that is gone.
     *
     * The trail is the record and the file is the evidence, and they can come
     * apart -- a restore that missed a volume, a sweep somebody wrote by hand.
     * The caller is told the same nothing as every other refusal; the operational
     * fact belongs in the log, which is what the resolver writes.
     */
    @Test
    @Transactional
    public void anArchivedFileThatIsNoLongerOnDiskIsNotFound() throws Exception {
        PublicationIssue issue = publishedIssue("the released edition");
        IssueAuditEntry amend = amendOf(issue, "the corrected edition");

        String archived = archives.resolve(issue, amend.getId(), "da").file().path();
        assertTrue(store.delete(archived), "the fixture's archived row was not there to delete");

        assertRefused("an entry whose archived file is gone still resolved",
                () -> archives.resolve(issue, amend.getId(), "da"));
    }

    // ================================================================ the wire

    /**
     * The history line says which languages it superseded, and never where they
     * went.
     *
     * The panel needs the pair {entry, language} and nothing else, because that
     * pair IS the address. The location is a path outside the served root, and a
     * client holding one would either try to fetch it -- nothing serves it -- or
     * store it, and both are wrong about what the archive is.
     */
    @Test
    @Transactional
    public void theHistoryLineNamesTheLanguagesAndNotTheLocation() {
        PublicationIssue issue = publishedIssue("the released edition");
        String publishedName = issue.getDescs().get(0).getFileName();
        IssueAuditEntry amend = amendOf(issue, "the corrected edition");

        IssueArchiveVo archive = amend.toVo().getArchive();
        assertNotNull(archive, "the amend's history line carries no archive, so nothing can open it");
        assertEquals(amend.getId(), archive.getAuditEntryId(),
                "the archive must name the entry that wrote it; it is the only thing that "
                        + "distinguishes two amendments of the same language");
        assertEquals(1, archive.getFiles().size());
        assertEquals("da", archive.getFiles().get(0).getLang());
        assertEquals(publishedName, archive.getFiles().get(0).getFileName());

        // The location is absent by construction: there is no field for it.
        for (java.lang.reflect.Field f : archive.getFiles().get(0).getClass().getDeclaredFields()) {
            assertFalse(f.getName().toLowerCase().contains("path"),
                    "the archive file shape declares '" + f.getName() + "'. A filesystem path outside "
                            + "the served root has no business on the wire, and a field that is not "
                            + "there cannot leak through a mapping somebody adds later.");
        }
    }

    /** An entry that archived nothing carries no archive object at all. */
    @Test
    @Transactional
    public void anEntryWithoutAnArchiveCarriesNoArchiveObject() {
        PublicationIssue issue = publishedIssue("the released edition");
        IssueAuditEntry created = auditService.forIssue(issue).stream()
                .filter(a -> a.getArchivePath() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry without an archive to test with"));

        assertNull(created.toVo().getArchive(),
                "an empty archive object on every ordinary history line makes the panel guess which "
                        + "lines have something to open");
    }

    // ---------------------------------------------------------------- crafting

    /** An entry claiming to have archived to a location this test chose. */
    private IssueAuditEntry crafted(PublicationIssue issue, String archivePath) {
        IssueAuditEntry entry = new IssueAuditEntry();
        entry.setIssue(issue);
        entry.setAction(AuditAction.AMENDED);
        entry.setActorKind(ActorKind.SYSTEM);
        entry.setArchivePath(archivePath);
        em.persist(entry);
        em.flush();
        return entry;
    }

    /** The row's name column holds 255 characters: a long name keeps its extension and loses the end of its base */
    @Test
    public void aStampedNameNeverExceedsTheNameColumn() {
        String longBase = "x".repeat(250);
        String stamped = IssueArchiveService.stampedName(1_700_000_000_000L, longBase + ".pdf");

        assertEquals(255, stamped.length());
        assertTrue(stamped.startsWith("1700000000000-xxxx"), stamped);
        assertTrue(stamped.endsWith(".pdf"), "the extension stays: " + stamped);
        assertEquals("1700000000000-short.pdf", IssueArchiveService.stampedName(1_700_000_000_000L, "short.pdf"));
    }
}
