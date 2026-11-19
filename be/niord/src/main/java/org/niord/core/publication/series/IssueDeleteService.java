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

import org.springframework.stereotype.Service;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.publication.Publication;
import org.niord.core.publication.PublicationDesc;
import org.niord.core.publication.PublicationResolver;
import org.niord.core.publication.series.legacy.LegacyIssueTranslation;
import org.niord.core.service.BaseService;
import org.niord.core.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.niord.core.repo.RepoPaths;
import org.niord.core.repo.RepoStore;
import org.niord.core.repo.RepoCache;
import org.niord.core.repo.ThumbnailService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Removing an issue, and the two questions that have to be answered first.
 *
 * WHY THIS IS NOT A TRANSITION. Every other change of an issue's state leaves the
 * row there to be read afterwards, so it belongs with the other transitions. This
 * one ends the row, and what it needs in order to decide is not the issue at all:
 * it is what the rest of the estate says about the issue. That is why it sits on
 * its own, with the citation lookup and the repository layout injected -- neither
 * of which the transitions touch, and neither of which belongs in a class whose
 * subject is the status column.
 *
 * THE THREE ANSWERS.
 *
 * An OPEN issue may go, unless a message already cites it. Nothing was ever
 * released under its name, so there is no public record to erase -- but an open
 * issue CAN already be cited. The citation picker offers PUBLISHED issues only;
 * an open issue is cited through an import: an imported issue keeps its legacy
 * id and sits at OPEN until it is first published, so the messages that cited
 * the legacy row cite it. A deleted target would leave that text pointing at
 * nothing, and nobody reading it has a way of noticing. So the same lookup that guards a retired
 * issue guards an open one, and the refusal names the messages so they can be
 * re-pointed first.
 *
 * A PUBLISHED issue never may. It was released; people downloaded it and cited
 * it, and deleting a released document is not an administrative correction but an
 * erasure of the record. Retiring is the action that exists for this, and it
 * leaves the file at its link and the window in its bracket -- which is the whole
 * point of it. The refusal names retirement rather than merely refusing, because
 * an admin who reaches for delete on a published issue wants it off the list, and
 * that is exactly what retire does.
 *
 * A RETIRED issue may go ONLY when nothing cites it. Retirement withdraws an
 * issue from the public list without unmaking it, so its id may already be
 * written into message HTML -- and a citation whose target has been deleted
 * renders as a dead reference in a notice nobody is going to think to re-check.
 * So the citing messages are looked up and NAMED: "some messages cite this" sends
 * an admin to search the estate by hand, and the names are what turn the refusal
 * into a next step.
 *
 * THE TRAIL OUTLIVES THE ROW, and that is the only reason the deletion is
 * recordable at all. The issue's own entries go with it -- they hang off a
 * foreign key to a row that is about to stop existing -- so a single SERIES-level
 * entry is written first, carrying what the issue WAS: its id, its status, its
 * names, its period, when it published and how many messages it held. Written
 * first so that a failure to clean the files afterwards cannot cost the record of
 * the deletion itself.
 */
@Service
public class IssueDeleteService extends BaseService {

    /** How many citing messages a refusal names before it stops listing them. */
    public static final int NAMED_CITATIONS = 20;

    @Inject
    IssueAuditService audit;

    @Inject
    PublicationResolver citations;

    @Inject
    PublicationPathService paths;

    @Inject
    RepoStore store;

    @Inject
    RepoCache cache;

    @Inject
    ThumbnailService thumbnails;

    private static final Logger log = LoggerFactory.getLogger(IssueDeleteService.class);

    /**
     * Refused because messages point at it, with the messages named.
     *
     * The list travels on the exception rather than only in the sentence, because
     * a dialog has to be able to render each one as a row a person can click
     * through to -- and a client that had to parse them back out of the message
     * text would break the first time somebody improved the wording.
     */
    public static class IssueCitedException extends PublicationException {

        private final transient List<PublicationResolver.CitingMessage> citingMessages;

        private final long citingCount;

        public IssueCitedException(String message,
                                   List<PublicationResolver.CitingMessage> citingMessages,
                                   long citingCount) {
            super("ISSUE_CITED", message);
            this.citingMessages = List.copyOf(citingMessages);
            this.citingCount = citingCount;
        }

        /** The first few, in the order the estate holds them. */
        public List<PublicationResolver.CitingMessage> citingMessages() {
            return citingMessages;
        }

        /** How many there are altogether, which may exceed what is named. */
        public long citingCount() {
            return citingCount;
        }
    }

    /**
     * Deletes an issue, or refuses with the reason.
     *
     * @param reason optional. A deletion is allowed to be housekeeping -- an issue
     *               created against the wrong series and noticed a minute later --
     *               so a mandatory sentence would be answered with a keystroke.
     *               When one IS given it is the most useful line in the entry.
     */
    @Transactional
    public void delete(PublicationIssue issue, User actor, String reason) {
        delete(issue, actor, reason, null);
    }

    /**
     * The same delete, naming any citing messages in the caller's language.
     *
     * The language reaches only the REFUSAL. A deletion that goes through says
     * nothing about messages, and one that is refused has to render each citing
     * message as a row somebody can open -- including the ones that have not been
     * numbered yet, which have no short id and are nameable only by their title.
     */
    @Transactional
    public void delete(PublicationIssue issue, User actor, String reason, String lang) {

        if (issue.getStatus() == IssueStatus.PUBLISHED) {
            throw new IssueLifecycleService.TransitionRefusedException("ISSUE_PUBLISHED_NOT_DELETABLE",
                    "'" + issue.getPublicId() + "' has been published. A released document is not "
                            + "deletable: people have downloaded and cited it. Retire it instead -- "
                            + "that takes it off the public list and leaves the file at its link.");
        }

        // OPEN and RETIRED alike: a message may name either, and a citation
        // whose target has gone reads as a dead reference in both cases.
        PublicationResolver.Citations cited =
                citations.citingMessages(issue.getPublicId(), NAMED_CITATIONS, lang);
        if (cited.any()) {
            throw new IssueCitedException(citedMessage(issue, cited),
                    cited.sample(), cited.total());
        }

        // The trail first, and deliberately before anything is removed: the
        // issue's own entries hang off it and go with it, so this series-level
        // line is the only thing that will say the issue was ever there.
        String trimmedReason = reason == null || reason.isBlank() ? null : reason.trim();
        List<PublicationIssue> successors = successors(issue);
        List<String> supersededBy = successors.stream().map(PublicationIssue::getPublicId).toList();
        audit.deleted(issue, actor, trimmedReason, detailOf(issue, supersededBy));

        // A successor points BACK at what it replaced, and that pointer is a
        // foreign key. Cleared rather than cascaded: the successor is a released
        // document of its own and must survive, it simply no longer has a
        // predecessor to name. Which edition that was is not lost with the
        // pointer -- it is written into the entry above, because "this edition
        // replaced something that is no longer here" is exactly the question the
        // surviving row can no longer answer for itself.
        em.createQuery("UPDATE PublicationIssue i SET i.supersedes = NULL WHERE i.supersedes = :i")
                .setParameter("i", issue).executeUpdate();
        // Re-read, because that statement went AROUND the persistence context.
        // Bulk DML reaches the database directly and leaves the context holding
        // the successor with its pointer intact -- and Hibernate checks every
        // managed entity's associations when it flushes, refusing one whose
        // foreign key names the row being removed. The caller of a delete is
        // typically the session that has just been reading this chain, so it
        // would get that refusal instead of its delete. Re-read rather than
        // assigned, so the row's version is not bumped by a change the statement
        // above has already made.
        successors.forEach(em::refresh);

        // The three tables that hang off the issue, emptied THROUGH the context
        // for the same reason: a member, an override or an entry the caller is
        // holding would be left pointing at a row that has gone. A bulk statement
        // cannot say that, and the curation that wrote an override or the panel
        // that read the trail is exactly who deletes an issue next. What reaches
        // the database is the same set of deletes; what it costs is loading one
        // issue's rows, which is a page of them rather than a table.
        em.createQuery("SELECT m FROM IssueMember m WHERE m.issue = :i", IssueMember.class)
                .setParameter("i", issue).getResultList().forEach(em::remove);
        em.createQuery("SELECT o FROM IssueOverride o WHERE o.issue = :i", IssueOverride.class)
                .setParameter("i", issue).getResultList().forEach(em::remove);
        audit.deleteForIssue(issue);

        String repoPath = issue.getRepoPath();
        String publicId = issue.getPublicId();
        // The issue's own files, named by its descriptors: what a folder a legacy publication shares gives up
        List<String> ownFiles = issue.getDescs().stream()
                .map(PublicationIssueDesc::getFilePath)
                .filter(path -> path != null && !path.isBlank())
                .toList();
        em.remove(em.contains(issue) ? issue : em.merge(issue));

        // Flushed BEFORE the files go. If the row delete is going to fail on a
        // constraint nobody anticipated, it has to fail while the documents are
        // still on disk -- the transaction then rolls back to an issue that is
        // intact rather than to one whose folder has been emptied underneath it.
        em.flush();

        removeFiles(repoPath, publicId, ownFiles);
    }

    /**
     * The editions that name this one as their predecessor, in creation order.
     *
     * The rows rather than their ids alone, because the delete needs both: their
     * public ids go into the entry that outlives the issue, and the rows
     * themselves have to be re-read once the statement that clears their pointer
     * has run. There are none or one of them on any real chain.
     */
    private List<PublicationIssue> successors(PublicationIssue issue) {
        return em.createQuery(
                        "SELECT i FROM PublicationIssue i WHERE i.supersedes = :i ORDER BY i.id",
                        PublicationIssue.class)
                .setParameter("i", issue)
                .getResultList();
    }

    /**
     * The refusal, with the messages named.
     *
     * Named up to a bound and then counted, because an issue cited by three
     * hundred messages produces a sentence nobody reads and a response nobody
     * renders -- while the first few plus the total is enough to act on.
     */
    private static String citedMessage(PublicationIssue issue, PublicationResolver.Citations cited) {
        StringBuilder names = new StringBuilder();
        for (PublicationResolver.CitingMessage m : cited.sample()) {
            // The number where there is one, the title otherwise, the uid as the
            // last resort. A message awaiting its number carries no short id, and
            // a sentence that named it by uid put a key in a list of message
            // numbers with nothing to say which was which.
            names.append(names.isEmpty() ? "" : ", ").append(m.label());
        }
        String more = cited.total() > cited.sample().size()
                ? " and " + (cited.total() - cited.sample().size()) + " more"
                : "";
        return "'" + issue.getPublicId() + "' is cited by " + cited.total() + " message(s): "
                + names + more + ". Deleting it would leave every one of those citations "
                + "pointing at a publication that no longer exists.";
    }

    /**
     * What the issue WAS, in the one entry that outlives it.
     *
     * Enough that somebody reading the series history a year later can tell which
     * publication went and whether its absence is the one they are looking at: the
     * id every citation would have used, the period it covered, the status it was
     * deleted from, and -- when it had been released -- when and how big.
     */
    private static Map<String, Object> detailOf(PublicationIssue issue, List<String> supersededBy) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("publicId", issue.getPublicId());
        detail.put("statusAtDeletion", issue.getStatus() == null ? null : issue.getStatus().name());

        Map<String, String> names = new LinkedHashMap<>();
        for (PublicationIssueDesc desc : issue.getDescs()) {
            names.put(desc.getLang(), desc.getName());
        }
        detail.put("names", names);

        detail.put("intervalFrom", millis(issue.getIntervalFrom()));
        detail.put("intervalTo", millis(issue.getIntervalTo()));
        detail.put("publishedAt", millis(issue.getPublishedAt()));
        detail.put("memberCount", issue.getMemberCount());
        // Only when there is one. A key present and null on every entry of a
        // system that was never imported into reads as a missing value rather
        // than as an absent concept.
        if (issue.getLegacyPublicationId() != null) {
            detail.put("legacyPublicationId", issue.getLegacyPublicationId());
        }
        // Which editions replaced this one, recorded here because the delete is
        // about to clear their pointer back to it. Without this the survivor
        // shows no predecessor at all and the history says only that SOMETHING
        // was removed -- so the chain a reader is trying to follow simply ends.
        if (!supersededBy.isEmpty()) {
            detail.put("supersededBy", List.copyOf(supersededBy));
        }
        return detail;
    }

    private static Long millis(java.util.Date date) {
        return date == null ? null : date.getTime();
    }

    /**
     * Every file the issue owns, in all THREE places they live.
     *
     * The served folder is the obvious one and the only one anybody would think
     * of. The other two are what makes the cleanup complete: each superseded
     * generation was copied into the archive store and each rendered draft into
     * the preview store, both keyed by the issue's public id -- and once the row
     * and its entries are gone, nothing left in the system names either location
     * again. They would sit there for the life of the deployment, holding
     * unpublished drafts and withdrawn editions of official notices that no
     * screen can show and no sweep can find.
     */
    private void removeFiles(String repoPath, String publicId, List<String> ownFiles) {
        if (repoPath != null && !repoPath.isBlank()) {
            // The folder is stored on the row, and an imported issue carries whatever layout it arrived
            // with, so this is the one place a stored string turns into a delete of a folder's rows:
            // a path that does not resolve below a served root is refused rather than followed.
            String folder = RepoPaths.served(repoPath);
            if (folder == null) {
                log.warn("Refusing to remove publication folder {}: it does not resolve inside the repository",
                        repoPath);
            } else {
                List<Publication> legacy = legacyPublicationsNaming(folder);
                if (legacy.isEmpty()) {
                    int removed = store.deleteFolder(folder);
                    cache.evict(folder);
                    log.debug("Removed {} files of the deleted issue's folder {}", removed, folder);
                } else {
                    // An imported issue points at the legacy publication's own folder, and its descriptors name
                    // the legacy publication's files: the import gave them the legacy paths
                    // (LegacyIssueTranslation.filePath), which the legacy publication's links still serve. The
                    // folder is theirs and stays, with every file a legacy publication names; only a file the
                    // issue itself published or uploaded there goes, which nothing names any more
                    Set<String> theirs = legacyFiles(legacy);
                    int removed = 0;
                    for (String path : ownFiles) {
                        // A descriptor's path is a stored string too: one that does not resolve below a served
                        // root is left alone, as the folder is above, rather than refused by the store after the
                        // row is gone, which would undo the delete
                        String own = RepoPaths.served(path);
                        if (own == null) {
                            log.warn("Leaving the file {} of a deleted issue: it does not resolve inside the repository",
                                    path);
                        } else if (!theirs.contains(own) && store.delete(own)) {
                            removed++;
                            thumbnails.deleteThumbnails(own);
                        }
                    }
                    log.info("Leaving the folder {} of a deleted issue: a legacy publication names it; removed its "
                            + "own {} file(s)", folder, removed);
                }
            }
        }
        if (publicId != null && !publicId.isBlank()) {
            store.deleteFolder(IssueArchiveService.archiveFolderFor(publicId));
            cache.evict(IssueArchiveService.archiveFolderFor(publicId));
            removeFolder(paths.previewRoot(), paths.previewRoot().resolve(publicId));
        }
    }

    /** The legacy publications whose repoPath names the folder, which then belongs to them */
    private List<Publication> legacyPublicationsNaming(String folder) {
        return em.createQuery("SELECT p FROM Publication p WHERE p.repoPath = :path", Publication.class)
                .setParameter("path", folder)
                .getResultList();
    }

    /**
     * The files the legacy publications' descriptors name, as normalised repository paths: the file of each
     * descriptor under the publication's current revision folder, and the file each descriptor's link serves
     * ({@code /rest/repo/file/<path>}), as written and decoded (the import gives an issue's descriptor the second
     * when it lies under the publication's folder and ends in the descriptor's file name, else the first:
     * {@code LegacyIssueTranslation.filePath}), read by the rule the import reads links with
     * ({@link RepoPaths#fileLinkPaths}, {@link LegacyIssueTranslation#linkSpellings}), so the two agree on what a
     * legacy publication names.
     */
    private static Set<String> legacyFiles(List<Publication> legacy) {
        Set<String> files = new HashSet<>();
        for (Publication publication : legacy) {
            if (publication.getDescs() == null) {
                continue;
            }
            for (PublicationDesc desc : publication.getDescs()) {
                String named = RepoPaths.normalize(LegacyIssueTranslation.filePath(publication, desc.getFileName()));
                if (named != null) {
                    files.add(named);
                }
                for (String link : RepoPaths.fileLinkPaths(desc.getLink())) {
                    files.addAll(LegacyIssueTranslation.linkSpellings(link));
                }
            }
        }
        return files;
    }

    /**
     * One folder, removed -- best effort, and loud about failing.
     *
     * A warning rather than a rollback. The row is gone and the trail says so; a
     * folder that could not be removed is a few orphaned bytes outside anything
     * that reads them, and undoing a correct deletion because of it would be the
     * worse outcome. The log line is what makes it findable.
     *
     * The containment check is not defensive noise. repoPath is stored on the row
     * and an imported issue carries whatever layout it arrived with, so this is
     * the one place a stored string turns into a recursive delete -- and a path
     * that resolves outside its own root gets refused rather than followed.
     */
    private void removeFolder(Path rootDir, Path folderDir) {
        Path root = rootDir.toAbsolutePath().normalize();
        Path folder = folderDir.toAbsolutePath().normalize();

        if (folder.equals(root) || !folder.startsWith(root)) {
            log.warn("Refusing to remove publication folder {}: it does not resolve inside {}",
                    folder, root);
            return;
        }
        if (!Files.isDirectory(folder)) {
            // Nothing was ever written here for this issue, which is the ordinary
            // case for one deleted before it published.
            return;
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            // Deepest first: a directory cannot be removed while anything is in it.
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("Could not remove publication folder {} of a deleted issue", folder, e);
        }
    }
}
