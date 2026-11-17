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
package org.niord.core.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.Attachment;
import org.niord.core.message.Message;
import org.niord.core.message.MessageService;
import org.niord.core.publication.series.ActorKind;
import org.niord.core.publication.series.AuditAction;
import org.niord.core.publication.series.IssueArchiveService;
import org.niord.core.publication.series.IssueAuditEntry;
import org.niord.core.repo.RepoImportService.Item;
import org.niord.core.repo.RepoImportService.Kind;
import org.niord.core.repo.RepoImportService.Outcome;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import org.niord.test.RepoRows;
import org.niord.test.MessageTestFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository import ({@link RepoImportService}, the {@code repo-import} batch job's work): a tree of the days
 * the repository was a folder, made by the test itself below the home, becomes rows of the store; the derived
 * files move into the cache tree; the audit entries' archived locations are rewritten; the references that
 * resolve to no row are reported; and a resumed run continues after the last item done.
 *
 * <p>The messages are committed through {@link MessageService#createMessage(Message)} and removed afterwards, as
 * the map-image tests do, since the import reads the references in a transaction of its own. The tree and the
 * rows are removed afterwards too.
 */
class RepoImportServiceITTest extends ITTest {

    /** A modification time the rows must keep, so that the ETags stay what they were before the cutover */
    private static final Instant WRITTEN = Instant.ofEpochMilli(1_700_000_000_000L);

    @Autowired
    RepoImportService importService;

    @Autowired
    RepoStore store;

    @Autowired
    RepoCache repoCache;

    @Autowired
    MessageService messageService;

    @PersistenceContext
    EntityManager em;

    private Path root;
    private final List<Integer> createdMessageIds = new ArrayList<>();
    private final List<Integer> createdAuditEntryIds = new ArrayList<>();
    private final List<String> createdFolders = new ArrayList<>();

    @BeforeEach
    void tree() throws IOException {
        // A short name: the archive test writes three of the tree's paths into an audit entry's 512-character column
        root = Files.createDirectories(repoCache.home().resolve("it-ri-" + Long.toHexString(System.nanoTime())));
    }

    @AfterEach
    void cleanup() throws IOException {
        for (String folder : createdFolders) {
            store.deleteFolder(folder);
            deleteTree(repoCache.pathFor(folder));
        }
        deleteTree(root);
        for (Integer id : createdAuditEntryIds) {
            Tx.requiringNew().run(() -> {
                IssueAuditEntry entry = em.find(IssueAuditEntry.class, id);
                if (entry != null) {
                    em.remove(entry);
                }
            });
        }
        for (Integer id : createdMessageIds) {
            Tx.requiringNew().run(() -> {
                em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                // The attachments first, by a query: the message's collection is then empty when the message goes
                em.createQuery("delete from Attachment a where a.message.id = :id")
                        .setParameter("id", id)
                        .executeUpdate();
                Message m = em.find(Message.class, id);
                if (m != null) {
                    em.remove(m);
                }
            });
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The tree of a repository root
    // ------------------------------------------------------------------------------------------- //

    @Test
    void importsTheFilesMovesTheDerivedOnesAndReportsTheReferencesThatResolveToNoRow() throws Exception {
        Message msg = committedMessage("import");
        String repo = msg.getRepoPath();
        createdFolders.add(repo);
        Tx.requiringNew().run(() -> {
            Message m = em.find(Message.class, msg.getId());
            m.setThumbnailPath(repo + "/1/custom_thumb_256.png");
            m.getAttachments().add(attachment(m, repo + "/1/Søkort.pdf", "Søkort.pdf"));
            m.getAttachments().add(attachment(m, repo + "/1/missing.txt", "missing.txt"));
        });

        // The files: a custom thumbnail (named like a generated image, but referenced), an upload with its cached
        // thumbnail beside it, an upload named like a thumbnail without a source beside it, a name outside ASCII,
        // a generated map image in the message folder, an edit session and a folder that is not the repository's
        byte[] chart = "a chart".getBytes(StandardCharsets.UTF_8);
        write(repo + "/1/custom_thumb_256.png", "custom thumbnail");
        write(repo + "/1/custom.png", "an upload");
        write(repo + "/1/custom_thumb_64.png", "its thumbnail");
        write(repo + "/1/chart_thumb_64.png", "an upload named like a thumbnail");
        Files.write(file(repo + "/1/Søkort.pdf"), chart);
        Files.setLastModifiedTime(file(repo + "/1/Søkort.pdf"), FileTime.from(WRITTEN));
        write(repo + "/map_256_0123abcd.png", "a generated map image");
        write("temp/" + System.nanoTime() + "/edit.txt", "an edit session");
        write("aton_tiles/1/2/3.png", "a tile");

        List<String> report = new ArrayList<>();
        List<Item> items = importService.plan(root, null, report::add);

        Map<String, Kind> kinds = new LinkedHashMap<>();
        items.forEach(item -> kinds.put(item.repoPath(), item.kind()));
        assertEquals(Kind.IMPORT, kinds.get(repo + "/1/custom_thumb_256.png"), "a referenced file is imported whatever its name");
        assertEquals(Kind.IMPORT, kinds.get(repo + "/1/custom.png"));
        assertEquals(Kind.DERIVED, kinds.get(repo + "/1/custom_thumb_64.png"), "a thumbnail beside its source is derived");
        assertEquals(Kind.IMPORT, kinds.get(repo + "/1/chart_thumb_64.png"), "a thumbnail without a source beside it is an upload");
        assertEquals(Kind.IMPORT, kinds.get(repo + "/1/Søkort.pdf"));
        assertEquals(Kind.DERIVED, kinds.get(repo + "/map_256_0123abcd.png"), "a map image in the message folder is derived");
        assertEquals(6, kinds.size(), "temp/ and the unknown folder are not planned: " + kinds);
        assertTrue(report.stream().anyMatch(line -> line.contains("aton_tiles")), "the unknown folder is reported: " + report);
        assertTrue(report.stream().anyMatch(line -> line.contains("1 file(s) under temp/ skipped")), report.toString());

        for (Item item : items) {
            Outcome outcome = importService.apply(item, false);
            assertEquals(item.kind() == Kind.DERIVED ? Outcome.MOVED : Outcome.INSERTED, outcome, item.repoPath());
        }

        // The rows: the file's bytes, its modification time as the row's, and its hash
        RepoFileInfo pdf = store.stat(repo + "/1/Søkort.pdf").orElseThrow();
        assertEquals(WRITTEN, pdf.updated(), "the row keeps the file's modification time, so the ETag stays");
        assertEquals(chart.length, pdf.size());
        assertEquals(RepoImportService.sha256(file(repo + "/1/Søkort.pdf")), pdf.sha256());
        assertEquals("a chart", new String(store.readAll(pdf), StandardCharsets.UTF_8));
        assertTrue(store.exists(repo + "/1/custom_thumb_256.png"));
        assertTrue(store.exists(repo + "/1/chart_thumb_64.png"));
        assertFalse(store.exists(repo + "/1/custom_thumb_64.png"), "a derived file gets no row");
        assertFalse(store.exists(repo + "/map_256_0123abcd.png"));

        // The derived files: in the cache tree, with their times, and gone from the tree
        Path cachedThumb = repoCache.pathFor(repo + "/1/custom_thumb_64.png");
        assertTrue(Files.isRegularFile(cachedThumb), "the thumbnail is moved into the cache tree");
        assertEquals(WRITTEN, Files.getLastModifiedTime(cachedThumb).toInstant(), "its modification time is kept");
        assertTrue(Files.isRegularFile(repoCache.pathFor(repo + "/map_256_0123abcd.png")));
        assertFalse(Files.exists(file(repo + "/1/custom_thumb_64.png")));
        assertTrue(Files.exists(file(repo + "/1/custom.png")), "an imported file stays on the old tree");

        // The verification: the attachment without a file is reported, the others are not
        List<String> dangling = importService.verifyReferences();
        assertTrue(dangling.stream().anyMatch(line -> line.contains("of message " + msg.getUid())
                && line.contains("'" + repo + "/1/missing.txt'")), "the dangling attachment: " + dangling);
        assertFalse(dangling.stream().anyMatch(line -> line.contains("Søkort.pdf") || line.contains("custom_thumb_256")),
                "the imported references resolve: " + dangling);

        // A second run: the derived files are gone from the plan, and every file has its row
        List<Item> again = importService.plan(root, null, line -> { });
        assertEquals(4, again.size(), again.toString());
        for (Item item : again) {
            assertEquals(Outcome.SKIPPED, importService.apply(item, false), item.repoPath());
            assertEquals(Outcome.VERIFIED, importService.apply(item, true), item.repoPath());
        }
        // A file written after its row, compared
        Files.writeString(file(repo + "/1/custom.png"), "changed since", StandardCharsets.UTF_8);
        Item changed = again.stream().filter(i -> i.repoPath().equals(repo + "/1/custom.png")).findFirst().orElseThrow();
        assertEquals(Outcome.MISMATCH, importService.apply(changed, true));
        assertEquals(Outcome.SKIPPED, importService.apply(changed, false), "without verify a row is never touched");
    }

    // ------------------------------------------------------------------------------------------- //
    // The old archive root
    // ------------------------------------------------------------------------------------------- //

    @Test
    void importsTheArchiveRootAndRewritesTheAuditEntriesThatNameIt() throws Exception {
        Path archiveDir = Files.createDirectories(root.resolve("arch"));
        String issue = "it-" + Long.toHexString(System.nanoTime());
        createdFolders.add(RepoImportService.ARCHIVE_FOLDER + "/" + issue);
        Path archived = archiveDir.resolve(issue).resolve("da").resolve("1-EfS uge 27, 2026.pdf");
        Files.createDirectories(archived.getParent());
        Files.writeString(archived, "the superseded edition", StandardCharsets.UTF_8);
        Path lost = archiveDir.resolve(issue).resolve("en").resolve("1-lost.pdf");
        String elsewhere = root.resolve("x").resolve("gone.pdf").toString();
        int entryId = Tx.requiringNew().call(() -> {
            IssueAuditEntry entry = new IssueAuditEntry();
            entry.setAction(AuditAction.AMENDED);
            entry.setActorKind(ActorKind.SYSTEM);
            entry.setArchivePath(IssueArchiveService.encode(List.of(archived.toString(), lost.toString(), elsewhere)));
            em.persist(entry);
            em.flush();
            return entry.getId();
        });
        createdAuditEntryIds.add(entryId);

        List<Item> items = importService.plan(root, archiveDir, line -> { });

        String storePath = RepoImportService.ARCHIVE_FOLDER + "/" + issue + "/da/1-EfS uge 27, 2026.pdf";
        assertEquals(List.of(storePath), items.stream().map(Item::repoPath).toList());
        assertEquals(1, items.get(0).section(), "the archive root is the plan's second section");
        assertEquals(Outcome.INSERTED, importService.apply(items.get(0), false));
        assertEquals("the superseded edition", RepoRows.readText(store, storePath));

        List<String> rewritten = importService.rewriteArchiveEntries(archiveDir);

        assertTrue(rewritten.contains("Archive entry " + entryId + ": " + archived + " -> " + storePath),
                "the rewritten location is reported with its old value: " + rewritten);
        assertTrue(rewritten.stream().anyMatch(line -> line.contains(lost.toString()) && line.contains("not found")),
                "a location whose file was not found is reported: " + rewritten);
        assertEquals(2, rewritten.size(), "a location outside the archive root is left alone, silently: " + rewritten);
        String column = Tx.requiringNew().call(() -> em.find(IssueAuditEntry.class, entryId).getArchivePath());
        assertEquals(List.of(storePath, lost.toString(), elsewhere), IssueArchiveService.locations(column),
                "the rewritten location, and the two left as they were");

        List<String> dangling = importService.verifyReferences();
        assertTrue(dangling.stream().anyMatch(line -> line.startsWith("Archive entry " + entryId)
                && line.contains("'" + elsewhere + "'")), "a location that is no row is reported: " + dangling);
        assertFalse(dangling.stream().anyMatch(line -> line.contains(storePath)), dangling.toString());
    }

    // ------------------------------------------------------------------------------------------- //
    // Resuming, and the folders' confinement
    // ------------------------------------------------------------------------------------------- //

    @Test
    void resumesAfterTheLastItemDoneWhateverTheMoveTookOutOfThePlan() throws Exception {
        String folder = "messages/0/00/it-resume-" + System.nanoTime();
        createdFolders.add(folder);
        write(folder + "/1/a.png", "a");
        write(folder + "/1/a_thumb_64.png", "a's thumbnail");
        write(folder + "/1/b.pdf", "b");
        write(folder + "/1/c.pdf", "c");
        List<Item> items = new ArrayList<>(importService.plan(root, null, line -> { }));
        items.add(Item.verify());
        assertEquals(List.of(folder + "/1/a.png", folder + "/1/a_thumb_64.png", folder + "/1/b.pdf", folder + "/1/c.pdf"),
                items.subList(0, 4).stream().map(Item::repoPath).toList());

        assertEquals(0, RepoImportService.resumeIndex(items, null), "a first run starts at the start");
        assertEquals(2, RepoImportService.resumeIndex(items, items.get(1)));
        assertEquals(4, RepoImportService.resumeIndex(items, items.get(3)), "after the last file, the verification");
        assertEquals(4, RepoImportService.resumeIndex(items, Item.verify()), "the verification runs again");

        // The first two items done, the thumbnail moved: the plan made again is shorter, and the run goes on with b
        importService.apply(items.get(0), false);
        importService.apply(items.get(1), false);
        List<Item> again = new ArrayList<>(importService.plan(root, null, line -> { }));
        again.add(Item.verify());
        assertEquals(List.of(folder + "/1/a.png", folder + "/1/b.pdf", folder + "/1/c.pdf"),
                again.subList(0, 3).stream().map(Item::repoPath).toList());
        assertEquals(1, RepoImportService.resumeIndex(again, items.get(1)), "continues with b.pdf");
    }

    @Test
    void confinesTheFoldersBelowTheHome() throws Exception {
        Path home = repoCache.home().toAbsolutePath().normalize();
        assertEquals(root.toAbsolutePath().normalize(), importService.confine(root.toString()));
        assertNull(importService.confine(null), "no archive root is none");
        assertNull(importService.confine(" "));
        assertThrows(IllegalArgumentException.class, () -> importService.confine(home.toString()), "the home itself");
        assertThrows(IllegalArgumentException.class, () -> importService.confine(home.getParent().toString()));
        assertThrows(IllegalArgumentException.class, () -> importService.confine(root.resolve("missing").toString()),
                "a folder that does not exist");
        assertThrows(IllegalArgumentException.class, () -> importService.confine(root + "/../../etc"),
                "a climb out of the home");
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** A committed draft on the seeded series */
    private Message committedMessage(String name) throws Exception {
        Message created = messageService.createMessage(
                MessageTestFactory.draft("dma-nw", Type.COASTAL_WARNING, "IT-REPO-IMPORT-" + name + "-" + System.nanoTime()));
        createdMessageIds.add(created.getId());
        return created;
    }

    private static Attachment attachment(Message message, String path, String fileName) {
        Attachment att = new Attachment();
        att.setMessage(message);
        att.setPath(path);
        att.setFileName(fileName);
        att.setType("application/octet-stream");
        return att;
    }

    /** The file of a repository path in the tree */
    private Path file(String repoPath) {
        return root.resolve(repoPath);
    }

    /** Writes a text file into the tree, with the known modification time */
    private void write(String repoPath, String text) throws IOException {
        Path file = file(repoPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(WRITTEN));
    }

    private static void deleteTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
