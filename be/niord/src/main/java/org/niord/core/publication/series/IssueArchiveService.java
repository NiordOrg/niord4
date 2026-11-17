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
import org.niord.core.publication.series.vo.IssueArchiveFileVo;
import org.niord.core.publication.series.vo.IssueArchiveVo;
import org.niord.core.service.BaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.niord.core.repo.RepoFileResource;
import org.niord.core.repo.RepoFileInfo;
import org.niord.core.repo.RepoStore;
import org.niord.core.repo.RepoPaths;
import org.apache.commons.io.FilenameUtils;
import java.util.Optional;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading back a superseded publication file.
 *
 * The writers keep every generation of every language indefinitely, and until
 * something can hand one back the archive is a folder nobody can open. This is
 * the only route to those bytes: they live outside the served repository root
 * precisely so that no anonymous repository read can reach a withdrawn edition,
 * which means the price is that a deliberate, gated read has to exist.
 *
 * AN ARCHIVE IS ADDRESSED BY THE EVENT THAT WROTE IT, not by a path. An issue
 * can be amended many times and each amendment supersedes the same language's
 * file, so "the archived Danish document of this issue" names several files; the
 * audit entry is what distinguishes them, and it is also the row that says who
 * caused the supersession and why. Taking a path from the caller instead would
 * be handing the filesystem to the request.
 */
@Service
public class IssueArchiveService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(IssueArchiveService.class);

    /** The refusal for everything this resolver cannot reach, whatever the reason. */
    public static final String NOT_FOUND = "ARCHIVE_ENTRY_NOT_FOUND";

    /** The column's own reader and writer; see {@link #encode(List)} for the shape. */
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<List<String>> LOCATIONS = new TypeReference<>() {
    };

    @Inject
    RepoStore store;

    /**
     * One archived file: its row in the store, and what it was called when it was public.
     *
     * The stored name and the published name are different strings and both are
     * needed -- one to open the file, the other to offer it under the name it had
     * when somebody cited it.
     */
    public record ArchivedFile(int auditEntryId, String lang, String fileName, RepoFileInfo file) {
    }

    /** The folder of the store that holds every archived edition, never served by the repository */
    public static final String ARCHIVE_FOLDER = RepoPaths.ARCHIVE_ROOT + "/publications";

    /** The name an archived edition is stored under when its descriptor names none */
    static final String DEFAULT_FILE_NAME = "publication.pdf";

    /** The folder of the store that holds every archived edition of an issue, below {@link #ARCHIVE_FOLDER} */
    public static String archiveFolderFor(String issuePublicId) {
        return ARCHIVE_FOLDER + "/" + issuePublicId;
    }

    /**
     * Archives an issue's published file before it is overwritten: a copy of its row at the stamped archive path.
     * An overwrite that precedes its archive has destroyed what it was meant to preserve, and every generation is
     * kept indefinitely, so nothing later can recover it: a copy that cannot be made aborts the caller.
     *
     * @param issue the issue
     * @param desc the language's descriptor, whose file is about to be replaced
     * @param stampedAt the archive stamp, the time of the action
     * @return the archived row's path, or null when the descriptor names no file or the file has no row
     * @throws IssuePublishService.ArchiveFailedException when the copy could not be made
     */
    public String archive(PublicationIssue issue, PublicationIssueDesc desc, long stampedAt) {
        String existing = desc.getFilePath();
        if (existing == null || !store.exists(existing)) {
            return null;
        }
        String target = archivePathFor(issue.getPublicId(), desc.getLang(),
                desc.getFileName() == null ? DEFAULT_FILE_NAME : desc.getFileName(), stampedAt);
        if (!store.copyFile(existing, target)) {
            // The source has a row, so the copy was refused for a row at the target: a second archive at the
            // same stamp, which would overwrite a preserved edition
            throw new IssuePublishService.ArchiveFailedException(
                    "could not archive " + existing + " to " + target + ": an archived edition is already there;"
                            + " refusing to overwrite it", null);
        }
        return target;
    }

    /**
     * Where a superseded file is archived to.
     *
     * Keyed by issue and language and stamped, because an issue can be amended
     * more than once and every generation is kept indefinitely -- an archive
     * that overwrites is not an archive.
     */
    public static String archivePathFor(String issuePublicId, String lang, String fileName, long stampedAt) {
        return archiveFolderFor(issuePublicId) + "/" + lang + "/" + stampedName(stampedAt, fileName);
    }

    /** The row's name column holds 255 characters; a name the stamp pushes past it loses the end of its base */
    private static final int MAX_NAME = 255;

    /** The stamped name, {@code <stamp>-<name>}, shortened in its base when the row's name column could not hold it */
    static String stampedName(long stampedAt, String fileName) {
        String prefix = stampedAt + "-";
        if (prefix.length() + fileName.length() <= MAX_NAME) {
            return prefix + fileName;
        }
        String ext = FilenameUtils.getExtension(fileName);
        String tail = ext.isEmpty() ? "" : "." + ext;
        int room = Math.max(1, MAX_NAME - prefix.length() - tail.length());
        String base = FilenameUtils.removeExtension(fileName);
        return prefix + base.substring(0, Math.min(base.length(), room)) + tail;
    }

    /** The archived file as a resource a controller returns, streamed from the store */
    public RepoFileResource resource(ArchivedFile archived) {
        return new RepoFileResource(store, archived.file());
    }

    /**
     * The archive of one audit entry, in the shape the history panel reads.
     *
     * Null when the entry preserved nothing, so an entry with no archive carries
     * no empty object that a client would have to distinguish from a real one.
     */
    public static IssueArchiveVo archiveOf(IssueAuditEntry entry) {
        List<Record> records = recordsOf(entry.getArchivePath());
        if (records.isEmpty()) {
            return null;
        }
        IssueArchiveVo vo = new IssueArchiveVo();
        vo.setAuditEntryId(entry.getId());
        for (Record r : records) {
            IssueArchiveFileVo file = new IssueArchiveFileVo();
            file.setLang(r.lang());
            file.setFileName(r.fileName());
            vo.getFiles().add(file);
        }
        return vo;
    }

    /**
     * The archived file of one language, for one audit entry of one issue.
     *
     * EVERY REFUSAL IS THE SAME REFUSAL. An entry that belongs to another issue,
     * an entry that archived nothing, a language the entry did not archive, a
     * stored location that escapes the archive root, and a file that is no longer
     * on disk all answer ARCHIVE_ENTRY_NOT_FOUND. Distinguishing them would let a
     * caller who may read one issue's history establish, one request at a time,
     * which audit ids exist elsewhere and what languages they hold -- and there is
     * nothing an admin does differently on hearing which of the five it was.
     *
     * In particular an entry of ANOTHER issue is not a 403. A refusal that says
     * "not yours" has already confirmed the row exists.
     */
    public ArchivedFile resolve(PublicationIssue issue, Integer auditEntryId, String lang) {
        if (issue == null || auditEntryId == null || lang == null || lang.isBlank()) {
            throw refusal(auditEntryId, lang);
        }
        IssueAuditEntry entry = em.find(IssueAuditEntry.class, auditEntryId);
        if (entry == null || entry.getIssue() == null
                || !entry.getIssue().getId().equals(issue.getId())) {
            throw refusal(auditEntryId, lang);
        }

        Record match = null;
        for (Record r : recordsOf(entry.getArchivePath())) {
            if (lang.equals(r.lang())) {
                match = r;
                break;
            }
        }
        if (match == null) {
            throw refusal(auditEntryId, lang);
        }

        // The stored location is data, and data written years ago by a version of
        // the writer that no longer runs. It is re-checked against the archive root
        // here rather than trusted, because this is the one place it is turned back
        // into a row -- a row carrying ../../, or an absolute path from the years the
        // archive was a folder on disk, names nothing the store serves.
        String file = RepoPaths.normalize(match.location());
        if (file == null || !RepoPaths.isBelow(file, RepoPaths.ARCHIVE_ROOT)) {
            log.warn("Audit entry {} names the archive location '{}', which is not a location in the archive "
                    + "root of the store; refusing to serve it", auditEntryId, match.location());
            throw refusal(auditEntryId, lang);
        }
        Optional<RepoFileInfo> row = store.stat(file);
        if (row.isEmpty()) {
            // The entry says bytes were preserved and they are not there. Worth a
            // line in the log even though the caller is told nothing: an archive
            // that has lost a file is an operational fact, and the trail that
            // recorded the supersession is now the only evidence it happened.
            log.warn("Audit entry {} records an archived {} document at {}, which is not in the store",
                    auditEntryId, lang, file);
            throw refusal(auditEntryId, lang);
        }
        return new ArchivedFile(entry.getId(), match.lang(), match.fileName(), row.get());
    }

    // ------------------------------------------------------------- the column

    /**
     * One entry of the archive column: where the file is, and what it was called.
     *
     * The column holds the locations the writers produced, because a single action
     * archives one file per language. Language and published name are read back off
     * the layout the writers use rather than stored twice -- two records of the same
     * fact drift, and the layout is asserted by the path service that produces it.
     */
    private record Record(String lang, String fileName, String location) {
    }

    /**
     * The list of locations, as a JSON array -- and NOT comma separated, which is
     * what it was.
     *
     * A COMMA IS A LEGAL FILE NAME CHARACTER, and "EfS uge 27, 2026.pdf" is the
     * shape of name people actually upload. The published name is carried through
     * into the archived file's own name, so a single stored location split on
     * commas came back as two halves: the first named a file that is not on disk
     * and the second was not a path at all, and the download answered
     * {@link #NOT_FOUND} for a document that was archived correctly and is sitting
     * right there. Nothing in the trail says why.
     *
     * Every separator that is not a file-name character is also a path character
     * somewhere -- the locations are absolute paths, so "/" and "\" are out, and
     * the column is text in PostgreSQL, so NUL is not storable. A JSON array
     * escapes what it has to and needs no character to be forbidden.
     *
     * READ BOTH WAYS, because rows written before this are on disk and the archive
     * is kept indefinitely. A JSON array is recognised by its opening bracket,
     * which no absolute path starts with, and anything else is read the old way --
     * so a legacy row whose names carry no comma parses exactly as it did. A legacy
     * row whose name DOES carry one was already unreadable and stays unreadable;
     * the ambiguity is in the stored bytes and nothing here can undo it.
     */
    public static String encode(List<String> locations) {
        if (locations == null || locations.isEmpty()) {
            return null;
        }
        return JSON.writeValueAsString(locations);
    }

    private static List<Record> recordsOf(String archivePath) {
        List<Record> out = new ArrayList<>();
        if (archivePath == null || archivePath.isBlank()) {
            return out;
        }
        for (String raw : locationsOf(archivePath)) {
            String location = raw.trim();
            if (location.isEmpty()) {
                continue;
            }
            Path path;
            try {
                path = Paths.get(location);
            } catch (InvalidPathException e) {
                continue;
            }
            Path name = path.getFileName();
            Path folder = path.getParent();
            if (name == null || folder == null || folder.getFileName() == null) {
                continue;
            }
            out.add(new Record(folder.getFileName().toString(),
                    publishedName(name.toString()), location));
        }
        return out;
    }

    /**
     * The locations an archive column holds, whichever of the two spellings it uses: for the repository import,
     * which rewrites the locations of the days the archive was a folder on disk to their rows in the store.
     *
     * @param archivePath the column
     * @return its locations, as stored
     */
    public static List<String> locations(String archivePath) {
        return archivePath == null || archivePath.isBlank() ? List.of() : locationsOf(archivePath);
    }

    /** The stored locations, whichever of the two spellings the column holds. */
    private static List<String> locationsOf(String archivePath) {
        String text = archivePath.trim();
        if (text.charAt(0) != '[') {
            return List.of(archivePath.split(","));
        }
        try {
            return JSON.readValue(text, LOCATIONS);
        } catch (JacksonException e) {
            // The column is data, and a row that cannot be read is not a reason to
            // fail a history panel: the caller is told the same nothing as every
            // other refusal here. The line is the operational fact.
            log.warn("An audit entry names an unreadable archive column '{}'", archivePath, e);
            return List.of();
        }
    }

    /**
     * The name the document had when it was public.
     *
     * Every generation of a language's file is kept side by side, so the writer
     * prefixes the instant it archived at to keep two of them apart. That prefix
     * is bookkeeping: offering a reader "1755424218000-EfS-Uge-27-2026.pdf" names
     * a file that was never published under that name.
     */
    private static String publishedName(String storedName) {
        int dash = storedName.indexOf('-');
        if (dash <= 0 || dash == storedName.length() - 1) {
            return storedName;
        }
        String prefix = storedName.substring(0, dash);
        for (int i = 0; i < prefix.length(); i++) {
            if (!Character.isDigit(prefix.charAt(i))) {
                return storedName;
            }
        }
        return storedName.substring(dash + 1);
    }

    private static IssueLifecycleService.TransitionRefusedException refusal(Integer auditEntryId, String lang) {
        return new IssueLifecycleService.TransitionRefusedException(NOT_FOUND,
                "this issue has no archived " + lang + " document on audit entry " + auditEntryId);
    }
}
