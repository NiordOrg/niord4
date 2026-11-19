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

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.publication.series.IssueArchiveService;
import org.niord.core.publication.series.IssueAuditEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/**
 * The repository import: the files of a repository root on disk, from the days the repository was a folder,
 * become rows of the store, and the files of an old publication archive root rows under the store's archive
 * folder. It is what the {@code repo-import} batch job does, item by item, so that it is resumable: a file that
 * already has a row is skipped (or compared, when verifying), so a second run after an interruption does the rest.
 * <p>
 * A derived file (an image thumbnail beside its source, a generated map image in a message folder) is not
 * imported: it is moved into the cache tree on the same volume, with its modification time kept, so that the
 * first views after the cutover are warm; a tree the job may not change keeps its copy, and the file still counts
 * as moved. The rule runs after the references: a file the database names is
 * imported whatever its name, since an editor's custom map thumbnail is named like a generated one, and an upload
 * may be named like a thumbnail.
 * <p>
 * The import ends with the verification from the rows' side: every reference the database holds (an attachment's
 * path, a message's thumbnail, an issue's file and link, a publication's link, an audit entry's archived locations,
 * and every repository link in the message texts and in a message's publication texts) must resolve to a row, and
 * each one that does not is reported.
 */
@Service
public class RepoImportService {

    /** The batch job's name */
    public static final String JOB_NAME = "repo-import";

    /** The job property naming the old repository root */
    public static final String DIR = "dir";

    /** The job property naming the old publication archive root, if any */
    public static final String ARCHIVE_DIR = "archiveDir";

    /** The job property that makes the import compare a file that already has a row */
    public static final String VERIFY = "verify";

    /** The folder of the store the files of an old archive root go to */
    public static final String ARCHIVE_FOLDER = IssueArchiveService.ARCHIVE_FOLDER;

    /** The depth of a message folder, {@code messages/<x>/<xx>/<uid>}, in which a generated map image lies */
    private static final int MESSAGE_FOLDER_DEPTH = 4;

    private static final Logger log = LoggerFactory.getLogger(RepoImportService.class);

    @Inject
    RepoStore store;

    @Inject
    RepoCache cache;

    @PersistenceContext
    EntityManager em;

    /** What is done with a file of the tree, and the verification that ends the job */
    public enum Kind { IMPORT, DERIVED, VERIFY }

    /** The outcome of one item */
    public enum Outcome { INSERTED, SKIPPED, VERIFIED, MISMATCH, MOVED, LEFT, FAILED }

    /**
     * One item of the job: a file to import or to move into the cache tree, or the verification that ends the job.
     * The items of a plan are ordered by section (the repository root first, then the archive root) and file, and
     * the job's checkpoint is the last item done, so a run resumes after it whatever a derived file's move took out
     * of the plan.
     *
     * @param kind what is done with it
     * @param section 0 for the repository root, 1 for the archive root
     * @param file the absolute path of the file on disk
     * @param repoPath the path of its row
     */
    public record Item(Kind kind, int section, String file, String repoPath) implements Serializable {

        /** The verification, the last item of every plan */
        public static Item verify() {
            return new Item(Kind.VERIFY, Integer.MAX_VALUE, null, null);
        }
    }

    /** What the processor hands the writer: the outcome of an item */
    public record Done(Outcome outcome, String repoPath) {
    }

    /**
     * Confines a folder parameter of the job: an absolute folder strictly below the home ({@code niord.home}),
     * where the volume of the days the repository was on disk is mounted. Anything else is refused, since the job
     * reads what it is given into the database.
     *
     * @param dir the folder, as given; null or blank for none
     * @return the folder, absolute and normalised, or null for none
     * @throws IllegalArgumentException when the folder is not a path, does not lie below the home, or does not exist
     */
    public Path confine(String dir) {
        if (StringUtils.isBlank(dir)) {
            return null;
        }
        Path home = cache.home().toAbsolutePath().normalize();
        Path path;
        try {
            path = Path.of(dir.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Not a folder: " + dir);
        }
        if (path.equals(home) || !path.startsWith(home)) {
            throw new IllegalArgumentException("The folder " + path + " does not lie below the home " + home);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("The folder " + path + " does not exist");
        }
        return path;
    }

    /**
     * Plans the import: walks the repository root's {@code messages/} and {@code publications/} and the archive
     * root, and decides for each file whether it is imported or, a derived file, moved into the cache tree.
     * {@code temp/} is skipped (the edit sessions in flight are lost at the cutover), and any other top-level
     * folder is reported and skipped.
     *
     * @param dir the old repository root
     * @param archiveDir the old publication archive root, or null
     * @param report where the plan's remarks go
     * @return the items, ordered; without the verification
     */
    @Transactional(readOnly = true)
    public List<Item> plan(Path dir, Path archiveDir, Consumer<String> report) throws IOException {
        Set<String> referenced = referencedPaths();
        List<Item> items = new ArrayList<>();
        Set<String> unknown = new TreeSet<>();
        int temp = 0;
        int derived = 0;
        List<String> skipped = new ArrayList<>();

        List<Path> files = walk(dir);
        Set<String> present = new HashSet<>();
        for (Path file : files) {
            present.add(relative(dir, file));
        }
        for (Path file : files) {
            String relative = relative(dir, file);
            String repoPath = RepoPaths.normalize(relative);
            if (repoPath == null || !repoPath.equals(relative)) {
                skipped.add(relative);
                continue;
            }
            String top = RepoPaths.rootOf(repoPath);
            if (RepoPaths.TEMP_ROOT.equals(top)) {
                temp++;
                continue;
            }
            if (!RepoPaths.MESSAGES_ROOT.equals(top) && !RepoPaths.PUBLICATIONS_ROOT.equals(top)) {
                unknown.add(top);
                continue;
            }
            Kind kind = !referenced.contains(repoPath) && isDerived(repoPath, present) ? Kind.DERIVED : Kind.IMPORT;
            if (kind == Kind.DERIVED) {
                derived++;
            }
            items.add(new Item(kind, 0, file.toString(), repoPath));
        }

        int archived = 0;
        if (archiveDir != null) {
            for (Path file : walk(archiveDir)) {
                String relative = relative(archiveDir, file);
                String repoPath = RepoPaths.normalize(ARCHIVE_FOLDER + "/" + relative);
                if (repoPath == null || !repoPath.equals(ARCHIVE_FOLDER + "/" + relative)) {
                    skipped.add(relative);
                    continue;
                }
                items.add(new Item(Kind.IMPORT, 1, file.toString(), repoPath));
                archived++;
            }
        }

        report.accept("Planned: " + (items.size() - derived - archived) + " file(s) of " + dir + " to import, "
                + derived + " derived file(s) to move into the cache tree, " + archived + " archived file(s)"
                + (archiveDir != null ? " of " + archiveDir : "") + "; " + temp + " file(s) under temp/ skipped");
        for (String folder : unknown) {
            report.accept("Skipped the folder " + folder + " of " + dir + ": not a repository folder");
        }
        for (String name : skipped) {
            report.accept("Skipped " + name + ": not a path the store holds");
        }
        return items;
    }

    /**
     * The index of the item a resumed run continues with: the first item after the last one done. The plan is
     * computed again at the restart, and may hold fewer files (the derived files moved), so the position is found
     * by order, not by index.
     *
     * @param items the plan, the verification last
     * @param last the last item done, or null for a first run
     * @return the index to continue from
     */
    public static int resumeIndex(List<Item> items, Item last) {
        if (last == null) {
            return 0;
        }
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.kind() == Kind.VERIFY) {
                // Every file before it is done; the verification runs again
                return i;
            }
            if (last.kind() != Kind.VERIFY && after(item, last)) {
                return i;
            }
        }
        return items.size();
    }

    /** Whether an item comes after another in a plan's order */
    private static boolean after(Item item, Item other) {
        if (item.section() != other.section()) {
            return item.section() > other.section();
        }
        return item.file().compareTo(other.file()) > 0;
    }

    /**
     * Does one item of the plan: imports the file, or moves a derived file into the cache tree.
     *
     * @param item the item
     * @param verify whether to compare a file that already has a row, instead of skipping it
     * @return the outcome
     */
    public Outcome apply(Item item, boolean verify) throws IOException {
        Path file = Path.of(item.file());
        return switch (item.kind()) {
            case IMPORT -> importFile(file, item.repoPath(), verify);
            case DERIVED -> moveToCache(file, item.repoPath());
            case VERIFY -> throw new IllegalArgumentException("The verification is not a file");
        };
    }

    /** Imports a file: a row when there is none, with the file's modification time as its own */
    private Outcome importFile(Path file, String repoPath, boolean verify) throws IOException {
        Optional<RepoFileInfo> existing = store.stat(repoPath);
        if (existing.isPresent()) {
            if (!verify) {
                return Outcome.SKIPPED;
            }
            return sha256(file).equals(existing.get().sha256()) ? Outcome.VERIFIED : Outcome.MISMATCH;
        }
        long size = Files.size(file);
        if (size > RepoStore.MAX_SIZE) {
            throw new IOException("The file is " + size + " bytes, above the store's cap of " + RepoStore.MAX_SIZE
                    + " bytes: left on the tree, reported here");
        }
        Instant updated = Files.getLastModifiedTime(file).toInstant();
        try (InputStream in = Files.newInputStream(file)) {
            store.write(repoPath, in, size, updated);
        }
        return Outcome.INSERTED;
    }

    /** Moves a derived file into the cache tree, its modification time kept; one the tree already holds is left */
    private Outcome moveToCache(Path file, String repoPath) throws IOException {
        Path target = cache.pathFor(repoPath);
        if (Files.exists(target)) {
            return Outcome.LEFT;
        }
        Files.createDirectories(target.getParent());
        try {
            Files.move(file, target);
        } catch (IOException e) {
            // Another volume, or a tree the job may not change: copied with its attributes, then deleted
            return copyIntoCache(file, target);
        }
        return Outcome.MOVED;
    }

    /**
     * Copies a derived file into the cache tree with its attributes, then deletes it from the tree. A tree that
     * refuses the deletion (a read-only copy of the old tree, kept so that it can be used again) keeps the file:
     * the copy in the cache tree is what the move was for, so the file counts as moved. A copy that fails is a
     * failure of the item.
     *
     * @param file the derived file on the tree
     * @param target its place in the cache tree, which does not exist yet
     * @return {@link Outcome#MOVED}
     * @throws IOException when the copy fails
     */
    static Outcome copyIntoCache(Path file, Path target) throws IOException {
        Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
        try {
            Files.delete(file);
        } catch (IOException e) {
            log.debug("Copied the derived file {} into the cache tree and left it on the tree, which refuses its"
                    + " deletion: {}", file, e.toString());
        }
        return Outcome.MOVED;
    }

    /**
     * Rewrites the archived locations of the audit entries from the old archive root to their rows in the store,
     * and reports each with its old value, the way back of a rollback. A location whose file was not found is
     * left as it is and reported; a location that is not below the old archive root is left alone.
     *
     * @param archiveDir the old publication archive root
     * @return the report, one line per location
     */
    @Transactional
    public List<String> rewriteArchiveEntries(Path archiveDir) {
        Path root = archiveDir.toAbsolutePath().normalize();
        List<String> report = new ArrayList<>();
        List<IssueAuditEntry> entries = em.createQuery(
                "select e from IssueAuditEntry e where e.archivePath is not null order by e.id", IssueAuditEntry.class)
                .getResultList();
        // The rows the locations may have, looked up at once
        Set<String> candidates = new HashSet<>();
        for (IssueAuditEntry entry : entries) {
            for (String location : IssueArchiveService.locations(entry.getArchivePath())) {
                String storePath = storePathOf(location, root);
                if (storePath != null) {
                    candidates.add(storePath);
                }
            }
        }
        Set<String> present = store.existing(candidates);
        for (IssueAuditEntry entry : entries) {
            List<String> rewritten = new ArrayList<>();
            boolean changed = false;
            for (String location : IssueArchiveService.locations(entry.getArchivePath())) {
                String storePath = storePathOf(location, root);
                if (storePath != null && present.contains(storePath)) {
                    rewritten.add(storePath);
                    changed = true;
                    report.add("Archive entry " + entry.getId() + ": " + location + " -> " + storePath);
                } else {
                    rewritten.add(location);
                    if (storePath != null) {
                        report.add("Archive entry " + entry.getId() + ": " + location + " was not found under "
                                + root + " and is left as it is");
                    }
                }
            }
            if (changed) {
                entry.setArchivePath(IssueArchiveService.encode(rewritten));
            }
        }
        return report;
    }

    /** The row an archived location below the old archive root has in the store; null when it is not below it */
    private static String storePathOf(String location, Path archiveRoot) {
        Path path;
        try {
            path = Path.of(location.trim());
        } catch (InvalidPathException e) {
            return null;
        }
        if (!path.isAbsolute()) {
            return null;
        }
        path = path.normalize();
        if (path.equals(archiveRoot) || !path.startsWith(archiveRoot)) {
            return null;
        }
        return RepoPaths.normalize(ARCHIVE_FOLDER + "/" + relative(archiveRoot, path));
    }

    /**
     * The verification from the rows' side: every reference the database holds must resolve to a row.
     *
     * @return one line per reference that resolves to no row
     */
    @Transactional(readOnly = true)
    public List<String> verifyReferences() {
        List<Reference> references = references();
        Set<String> candidates = new HashSet<>();
        references.forEach(reference -> candidates.addAll(reference.candidates()));
        Set<String> present = store.existing(candidates);
        List<String> dangling = new ArrayList<>();
        for (Reference reference : references) {
            if (reference.candidates().stream().noneMatch(present::contains)) {
                dangling.add(reference.what() + " names '" + reference.raw() + "', which has no row");
            }
        }
        return dangling;
    }

    /** The paths the database refers to, normalised: a file at one of them is imported whatever its name */
    private Set<String> referencedPaths() {
        Set<String> paths = new HashSet<>();
        for (Reference reference : references()) {
            paths.addAll(reference.candidates());
        }
        return paths;
    }

    /** A reference the database holds, and the store paths it may mean ({@link RepoPaths#candidates}) */
    private record Reference(String what, String raw, List<String> candidates) {

        static Reference of(String what, String raw) {
            return new Reference(what, raw, new ArrayList<>(RepoPaths.candidates(raw)));
        }
    }

    /** Every reference the database holds */
    private List<Reference> references() {
        List<Reference> refs = new ArrayList<>();
        for (Object[] row : rows("select a.id, a.message.uid, a.path from Attachment a where a.path is not null")) {
            refs.add(Reference.of("Attachment " + row[0] + " of message " + row[1], (String) row[2]));
        }
        for (Object[] row : rows("select m.uid, m.thumbnailPath from Message m where m.thumbnailPath is not null")) {
            refs.add(Reference.of("The thumbnail of message " + row[0], (String) row[1]));
        }
        for (Object[] row : rows("select d.id, d.lang, d.filePath from PublicationIssueDesc d where d.filePath is not null")) {
            refs.add(Reference.of("The " + row[1] + " file of publication issue desc " + row[0], (String) row[2]));
        }
        for (Object[] row : rows("select d.id, d.lang, d.link from PublicationIssueDesc d where d.link is not null")) {
            for (String link : RepoPaths.fileLinkPaths((String) row[2])) {
                refs.add(Reference.of("The " + row[1] + " link of publication issue desc " + row[0], link));
            }
        }
        for (Object[] row : rows("select d.id, d.lang, d.link from PublicationDesc d where d.link is not null")) {
            for (String link : RepoPaths.fileLinkPaths((String) row[2])) {
                refs.add(Reference.of("The " + row[1] + " link of publication desc " + row[0], link));
            }
        }
        for (Object[] row : rows("select e.id, e.archivePath from IssueAuditEntry e where e.archivePath is not null")) {
            for (String location : IssueArchiveService.locations((String) row[1])) {
                refs.add(Reference.of("Archive entry " + row[0], location));
            }
        }
        for (Object[] row : rows("select d.id, d.details from MessagePartDesc d where d.details like '%"
                + RepoPaths.FILE_URL_PREFIX + "%'")) {
            for (String link : RepoPaths.fileLinkPaths((String) row[1])) {
                refs.add(Reference.of("The text of message part desc " + row[0], link));
            }
        }
        // A message's publication texts, the public and the internal one, are HTML that may link a file too
        for (Object[] row : rows("select d.id, d.lang, d.publication, d.internalPublication from MessageDesc d"
                + " where d.publication like '%" + RepoPaths.FILE_URL_PREFIX + "%'"
                + " or d.internalPublication like '%" + RepoPaths.FILE_URL_PREFIX + "%'")) {
            for (String link : RepoPaths.fileLinkPaths((String) row[2])) {
                refs.add(Reference.of("The " + row[1] + " publication text of message desc " + row[0], link));
            }
            for (String link : RepoPaths.fileLinkPaths((String) row[3])) {
                refs.add(Reference.of("The " + row[1] + " internal publication text of message desc " + row[0], link));
            }
        }
        return refs;
    }

    private List<Object[]> rows(String jpql) {
        return em.createQuery(jpql, Object[].class).getResultList();
    }

    /**
     * Whether a file of the tree is a derived one: an image thumbnail whose source lies beside it, or a generated
     * map image directly in a message folder. A file the heuristic is unsure of is an upload.
     */
    static boolean isDerived(String repoPath, Set<String> present) {
        String folder = RepoPaths.folderOf(repoPath);
        String name = RepoPaths.nameOf(repoPath);
        Matcher thumb = RepoCache.THUMBNAIL.matcher(name);
        if (thumb.matches()) {
            return present.contains(RepoPaths.join(folder, thumb.group(1) + "." + thumb.group(3)));
        }
        return RepoCache.MAP_IMAGE.matcher(name).matches()
                && folder.startsWith(RepoPaths.MESSAGES_ROOT + "/")
                && folder.split("/").length == MESSAGE_FOLDER_DEPTH;
    }

    /** The regular files below a folder, in the order of their paths */
    private static List<Path> walk(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                    .sorted((a, b) -> a.toString().compareTo(b.toString()))
                    .toList();
        }
    }

    /** A file's path relative to a root, with slashes */
    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    /** The SHA-256 of a file in hex, as the rows hold it */
    static String sha256(Path file) throws IOException {
        return RepoStore.sha256Hex(Files.newInputStream(file));
    }
}
