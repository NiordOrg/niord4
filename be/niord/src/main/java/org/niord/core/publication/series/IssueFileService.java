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
import org.niord.core.service.BaseService;
import org.niord.core.user.User;
import org.niord.core.util.WebUtils;

import org.niord.core.repo.RepoStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

/**
 * Per-language file upload and clear (C6).
 *
 * Upload is legal on a PUBLISHED issue as well as an OPEN one, and that is the
 * design rather than an oversight: it is the post-publish correction path. A
 * wrong PDF on the public site has to be replaceable without retiring the issue
 * and republishing, because retiring changes what the record says happened.
 *
 * An upload onto a published or retired issue archives what it replaces FIRST.
 * C3 keeps every generation indefinitely, so overwriting without archiving
 * destroys the published artefact -- and that is the one people cited. Retiring
 * keeps the file at its link, so a retired issue's document is still the cited
 * one: uploaded again under its own name it is replaced in place and archived,
 * as a published one is. Under a new name it moves the address instead, which
 * is what retiring is for, and the released file stays where it was. Its name
 * then stays taken: a later upload under it, in any language, is refused rather
 * than written over the released bytes.
 *
 * The sticky flag stops the next publish regenerating over a hand-uploaded
 * correction. Without it the correction survives exactly until somebody presses
 * publish again, which is the least predictable moment for it to vanish.
 */
@Service
public class IssueFileService extends BaseService {

    /** An upload under a name whose file a released issue still keeps (see {@link #upload}). */
    public static final String FILE_NAME_TAKEN = "FILE_NAME_TAKEN";

    @Inject
    RepoStore store;

    @Inject
    IssueArchiveService archives;

    @Inject
    IssueAuditService audit;

    /**
     * A file name reduced to a bare name: any path stripped, then trimmed.
     * Null where nothing usable is left.
     *
     * TWO CALLERS, ONE RULE, and that is why it lives here rather than at either
     * of them. The upload takes a name out of a multipart body and the issue edit
     * takes one out of a JSON body, and both end up resolved against the issue's
     * repository folder -- a name carrying `..` or an absolute path would write
     * outside it. A second copy of the stripping is a second chance to get it
     * wrong, on the half of the estate that happens to use the other endpoint.
     *
     * It returns rather than throws, because the two callers refuse differently:
     * an upload with no usable name is a malformed multipart body, and an edit
     * with one is a bad field. One sanitiser, two refusals.
     *
     * The rule itself is {@link WebUtils#bareFileName(String)}, which every
     * multipart upload's file name now passes through as well; this keeps the
     * name the publication code already calls.
     */
    public static String bareFileName(String fileName) {
        return WebUtils.bareFileName(fileName);
    }

    @Transactional
    public PublicationIssueDesc upload(PublicationIssue issue, String lang, String fileName,
                                       byte[] bytes, User actor) {
        PublicationIssueDesc desc = descFor(issue, lang);
        boolean replacing = desc.getFilePath() != null;
        boolean published = issue.getStatus() == IssueStatus.PUBLISHED;
        // A RETIRED issue's released document, uploaded again under its own name, is replaced in place and archived
        // first, as a PUBLISHED one is; a new name stays the documented way to move a retired issue's address.
        boolean retiredSameName = issue.getStatus() == IssueStatus.RETIRED && replacing
                && fileName.equals(desc.getFileName());
        boolean inPlace = replacing && (published || retiredSameName);

        // EVERY REFUSAL HAPPENS BEFORE A BYTE IS WRITTEN.
        //
        // The write used to run first and the checks after, so a refused upload
        // had already overwritten the file on disk -- and on a published issue
        // that is the cited document, destroyed by a request the server then
        // answered with an error. @Transactional cannot undo it: the rollback
        // reverts the row and leaves the bytes.

        // D-3. One file name per issue, across ALL its languages.
        //
        // Every language writes into the SAME repoPath, so two languages sharing
        // a name are two languages sharing a file: whichever uploads second
        // overwrites the first, and the issue then serves the Danish PDF to an
        // English reader with nothing anywhere recording that it happened.
        for (PublicationIssueDesc other : issue.getDescs()) {
            if (!other.getLang().equals(lang) && fileName.equals(other.getFileName())) {
                throw new IssueLifecycleService.TransitionRefusedException("FILE_NAME_NOT_DISTINCT",
                        "'" + fileName + "' is already the file name for '" + other.getLang()
                                + "'. Both languages write into the same folder, so sharing a name "
                                + "means one silently overwrites the other.");
            }
        }

        // The published address does not move. A citation is a stored URL inside
        // message HTML, and correcting the document must not change where it
        // lives -- a replacement under a new name leaves every citation pointing
        // at the old bytes and the download link pointing at the new ones.
        if (inPlace && !fileName.equals(desc.getFileName())) {
            throw new IssueLifecycleService.TransitionRefusedException("FILE_NAME_IMMUTABLE",
                    "this issue is published and its " + lang + " document already lives at '"
                            + desc.getFileName() + "'. A correction replaces those bytes in place; "
                            + "uploading under the name '" + fileName + "' would leave every stored "
                            + "citation pointing at the old file. Retire the issue if the address "
                            + "itself has to change.");
        }

        // The target. For a published replacement it is the desc's OWN stored
        // path, not one re-derived from the issue's folder: an imported issue keeps
        // the legacy layout, revision segment and all, so a re-derived path would
        // write beside the cited file rather than over it.
        String target = inPlace
                ? desc.getFilePath()
                : issue.getRepoPath() + "/" + fileName;

        // A new address must be a free one once the issue has been released.
        //
        // Moving a retired issue's address leaves the released file where every
        // citation points, but no desc names it any more. Without this check a
        // later upload under that name -- in the same language or in another,
        // where the distinct-name check above no longer sees it -- would write
        // over the released bytes with no archive copy and record a first upload.
        // An OPEN issue never released anything, so its files are free to reuse.
        if (issue.getStatus() != IssueStatus.OPEN && !inPlace && store.exists(target)) {
            throw new IssueLifecycleService.TransitionRefusedException(FILE_NAME_TAKEN,
                    "a document of this issue that has been released still lives at '" + fileName
                            + "', and citations may point at it. Choose another name for the "
                            + lang + " document.");
        }

        // Archive what is about to be overwritten, and do it before the write.
        // Every generation is kept indefinitely, so overwriting without archiving
        // destroys the published artefact -- the one people cited.
        String archived = inPlace ? archiveExisting(issue, desc) : null;

        // The row joins this transaction: a refusal after this point takes the bytes
        // back with the columns, which a file on disk never did.
        try {
            store.write(target, bytes, Instant.now());
        } catch (RuntimeException e) {
            throw new IssueLifecycleService.TransitionRefusedException("FILE_WRITE_FAILED",
                    "could not write " + target + ": " + e.getMessage());
        }

        // Who replaced what was there, and when.
        //
        // Only on a REPLACEMENT: a first upload replaces nothing, and recording
        // an actor against it would make every issue look like it had been
        // corrected once. The pair is what turns "this file is not the one that
        // was published" from something a reader infers off an archive folder
        // into something the issue itself says.
        if (replacing) {
            desc.setReplacedBy(actor);
            desc.setReplacedAt(new Date());
        }

        // Read before the line below overwrites it: a replacement records what it replaced.
        FileSource replacedSource = inPlace ? desc.getFileSource() : null;

        if (!inPlace) {
            desc.setFileName(fileName);
            desc.setFilePath(issue.getRepoPath() + "/" + fileName);
        }
        desc.setFileSource(FileSource.UPLOADED);
        desc.setFileSourceSticky(true);

        // A hand replacement of a released document is its own action. Reading
        // "FILE_UPLOADED" against a published issue tells nobody whether a
        // document appeared or a cited one was overwritten, and those are
        // different events to anyone reading the trail afterwards.
        IssueAuditEntry entry = audit.fileEvent(issue, actor,
                inPlace ? AuditAction.FILE_REPLACED_MANUALLY : AuditAction.FILE_UPLOADED, lang, desc.getFileName(),
                (long) bytes.length, sha256(bytes), replacedSource);
        if (archived != null) {
            entry.setArchivePath(IssueArchiveService.encode(List.of(archived)));
        }
        em.merge(desc);
        bumpIssueRevision(issue);
        return desc;
    }

    /**
     * Clears an uploaded file, returning the language to generated content.
     *
     * Only while the issue is OPEN. Clearing a published file would leave a dead
     * link where a cited document used to be; the way to fix a published file is
     * to upload a replacement over it.
     */
    @Transactional
    public PublicationIssueDesc clear(PublicationIssue issue, String lang, User actor) {
        if (issue.getStatus() != IssueStatus.OPEN) {
            throw new IssueLifecycleService.TransitionRefusedException("ISSUE_NOT_OPEN",
                    "clearing a published file would leave a dead link where a cited document was; "
                            + "upload a replacement instead");
        }
        PublicationIssueDesc desc = descFor(issue, lang);
        // Read before it is cleared: the entry names the file that went.
        String cleared = desc.getFileName();
        desc.setFileName(null);
        desc.setFilePath(null);
        desc.setFileSource(null);
        desc.setFileSourceSticky(false);
        audit.fileEvent(issue, actor, AuditAction.FILE_CLEARED, lang, cleared, null, null, null);
        PublicationIssueDesc merged = em.merge(desc);
        bumpIssueRevision(issue);
        return merged;
    }

    /**
     * Points a language at an external document, or stops pointing at one.
     *
     * The other half of C6. A hosted publication carries a file; an external one
     * carries a link, and an EXTERNAL_LINK issue with no link is a publication
     * that resolves to nothing -- which was the state every such issue was stuck
     * in, because nothing could set it.
     *
     * Editable on a PUBLISHED issue, for the same reason an upload is: a wrong
     * address on the public site has to be correctable without retiring the issue,
     * since retiring changes what the record says happened. Clearing is allowed
     * there too, unlike clearing a file -- a file that is cleared leaves a dead
     * repository link where a cited document was, while a link that is cleared
     * leaves nothing at all, which is the honest state for a publication whose
     * external host took the document down.
     *
     * Blank is normalised to null. An empty string is not an address, and a desc
     * holding one reports itself as a LINK publication that resolves nowhere.
     *
     * Anything else must be an address a reader can follow ({@link IssueLinkRule}):
     * a link that is not one is refused with LINK_INVALID before anything is
     * written, rather than published as a dead link.
     */
    @Transactional
    public PublicationIssueDesc setLink(PublicationIssue issue, String lang, String link, User actor) {
        PublicationIssueDesc desc = descFor(issue, lang);
        String cleaned = IssueLinkRule.validated(link);

        desc.setLink(cleaned);
        audit.linkEvent(issue, actor, cleaned == null ? AuditAction.LINK_CLEARED : AuditAction.LINK_SET, lang, cleaned);
        PublicationIssueDesc merged = em.merge(desc);
        bumpIssueRevision(issue);
        return merged;
    }

    /**
     * The issue's revision moves because its DOCUMENT changed, though the change
     * is stored on a desc row rather than on the issue.
     *
     * The same hole curation had, on the endpoints where it costs the most. A desc
     * is a child row: writing one leaves the parent's counter exactly where it was,
     * so two admins who both loaded the issue at revision 7 both pass
     * {@link StaleVersionGuard#check} at revision 7 and the second upload buries
     * the first one's correction in the archive with nothing said to either of
     * them. The resources all compare before they call in here; without this the
     * comparison is decorative.
     *
     * Every mutation on this service takes it -- an upload, a replacement, a clear
     * and a link set or clear alike -- because "what document does this language
     * resolve to" is one answer, and a caller that was refused for one of them and
     * not the others would have to know which write it had made.
     */
    private void bumpIssueRevision(PublicationIssue issue) {
        StaleVersionGuard.forceIncrement(em, issue);
    }

    /** "sha256:" and the hex digest of the bytes, the hash the data model gives a file event. */
    static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String archiveExisting(PublicationIssue issue, PublicationIssueDesc desc) {
        return archives.archive(issue, desc, new Date().getTime());
    }

    private PublicationIssueDesc descFor(PublicationIssue issue, String lang) {
        return issue.getDescs().stream()
                .filter(d -> lang.equals(d.getLang()))
                .findFirst()
                .orElseThrow(() -> new IssueLifecycleService.TransitionRefusedException("NO_SUCH_LANGUAGE",
                        "the issue has no " + lang + " desc row; the series may not be configured for it"));
    }
}
