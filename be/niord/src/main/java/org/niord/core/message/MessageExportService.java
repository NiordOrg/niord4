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

import org.springframework.stereotype.Service;

import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.conf.TextResource;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.model.message.AttachmentVo;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;

import jakarta.inject.Inject;
import org.niord.core.web.NiordWebException;
import org.niord.core.repo.RepoFileInfo;
import org.niord.core.repo.RepoPaths;

import org.niord.core.repo.RepoStore;
import org.apache.commons.io.output.CountingOutputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports a message search result as a Zip archive including attachments
 */
@Service
public class MessageExportService {

    // PORT: §5 replaced CDI @Inject Logger producer with a static slf4j logger.
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MessageExportService.class);

    private final RepoStore store;

    MessageExportService(RepoStore store) {
        this.store = store;
    }

    // The SQL for finding expired, published message is just too cumbersome for JPQL :-(
        @TextResource("/export-messages.html")
    String messagesPreviewHtmlFile;

    /**
     * Exports the messages search result to the output stream
     * @param result the search result
     * @param os the output stream
     */
    public void export(PagedSearchResultVo<SystemMessageVo> result, OutputStream os) {

        long t0 = System.currentTimeMillis();
        try {
            ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(os));

            // Rewrite links in message description to remove "/rest/repo/file/" prefix
            result.getData().forEach(m -> m.rewriteRepoPath("\"/rest/repo/file/" + m.getRepoPath(), "\"" + m.getRepoPath()));

            // Write the messages file to the Zip file
            log.debug("Adding messages.json to zip archive");
            out.putNextEntry(new ZipEntry("messages.json"));
            ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults()
                    .configure(StreamWriteFeature.AUTO_CLOSE_TARGET, false)
                    .build();
            String messages = mapper.writeValueAsString(result);
            IOUtils.write(messages, out, "utf-8");
            out.closeEntry();

            // Write the messages-preview.html file into the archive.
            // Can be used to preview the messages by someone unzipping the archive
            String html = messagesPreviewHtmlFile.replace("<<MESSAGES-JSON>>", StringEscapeUtils.escapeEcmaScript(messages));
            log.debug("Adding messages-preview.html to zip archive");
            out.putNextEntry(new ZipEntry("messages-preview.html"));
            IOUtils.write(html, out, "utf-8");
            out.closeEntry();

            // Write the message attachments and thumbnail files to the Zip file
            Set<String> folderCache = new HashSet<>();
            // A message with a custom thumbnail and no attachment has a file to export too
            result.getData().stream()
                    .filter(m -> (m.getAttachments() != null && !m.getAttachments().isEmpty())
                            || StringUtils.isNotBlank(m.getThumbnailPath()))
                    .forEach(m -> {
                        if (m.getAttachments() != null) {
                            exportAttachments(m, out, folderCache);
                        }
                        exportThumbnail(m, out, folderCache);
                    });

            out.flush();
            out.close();
            log.info("Created Zip export archive in " + (System.currentTimeMillis() - t0) + " ms");
        } catch (Exception e) {
            throw new NiordWebException("Error generating ZIP archive for messages", e);
        }
    }

    /**
     * Copies all attachment files associated with the message to the zip output stream.
     * Ignores all attachments that causes errors, so the result may be incomplete.
     * @param message the message
     * @param out the zip output stream
     */
    private void exportAttachments(SystemMessageVo message, ZipOutputStream out, Set<String> folderCache) {

        // Attachment files are nested within revision folders in the message repo folder
        for (AttachmentVo att : message.getAttachments()) {
            // E.g. "messages/a/68/a68f0dae-0669-4a5a-9a40-dd8d92d73b96/4/Buoy_seal.jpg"
            String path = att.getPath();
            if (path == null || !path.startsWith(message.getRepoPath()) || path.lastIndexOf('/') < 0) {
                log.warn("Skipping invalid attachment for message " + message.getId() + ": " + path);
                continue;
            }

            // E.g. "messages/a/68/a68f0dae-0669-4a5a-9a40-dd8d92d73b96/4" + "/" + "Buoy_seal.jpg"
            String folderPath = path.substring(0, path.lastIndexOf("/"));
            RepoFileInfo file = resolveMessageFile(message, folderPath + "/" + att.getFileName());
            if (file == null) {
                log.warn("Skipping attachment " + att.getFileName() + " of message " + message.getId()
                        + ": not a file in the message's repository folder");
                continue;
            }
            exportFile(message, file, out, folderCache);
        }
    }

    /**
     * Exports any custom thumbnail file associated with the message
     * @param message the message
     * @param out the zip output stream
     */
    private void exportThumbnail(SystemMessageVo message, ZipOutputStream out, Set<String> folderCache) {

        // Custom thumbnail files are nested within revision folders in the message repo folder
        // E.g. "messages/a/68/a68f0dae-0669-4a5a-9a40-dd8d92d73b96/4/custom_thumb_256.png"
        String thumbnailPath = message.getThumbnailPath();
        if (StringUtils.isNotBlank(thumbnailPath) && thumbnailPath.startsWith(message.getRepoPath())) {
            RepoFileInfo file = resolveMessageFile(message, thumbnailPath);
            if (file == null) {
                log.warn("Skipping thumbnail " + thumbnailPath + " of message " + message.getId()
                        + ": not a file in the message's repository folder");
                return;
            }
            exportFile(message, file, out, folderCache);
        }
    }

    /**
     * Resolves a file that a message's attachment or thumbnail names, for the export.
     * <p>
     * The name is stored as an editor sent it, and the export is anonymous once the message is published.
     * So only a row inside the message's own repository folder qualifies: the path is normalised and must lie
     * strictly below the folder, which itself must lie below a served root. Anything else is skipped.
     *
     * @param message the message
     * @param repoRelativePath the repository-relative path of the file
     * @return the file's row, or null when it does not qualify
     */
    private RepoFileInfo resolveMessageFile(SystemMessageVo message, String repoRelativePath) {
        if (StringUtils.isBlank(message.getRepoPath()) || repoRelativePath == null) {
            return null;
        }
        String folder = RepoPaths.served(message.getRepoPath());
        String file = RepoPaths.normalize(repoRelativePath);
        if (folder == null || file == null || !RepoPaths.isBelow(file, folder)) {
            return null;
        }
        return store.stat(file).orElse(null);
    }

    /**
     * Adds the message file, and the folders leading to it, to the zip archive, under its repository path. The
     * export may be incomplete, never broken: a file that cannot be read is skipped whatever failed (the store as
     * well as the stream), and a file that failed part-way, whose entry is already open, is closed short and named
     * in the log, since a zip entry cannot be taken back once the 200 is on its way.
     */
    private void exportFile(SystemMessageVo message, RepoFileInfo file, ZipOutputStream out, Set<String> folderCache) {
        String entryPath = file.path();
        addParentFolders(out, folderCache, entryPath.substring(0, entryPath.lastIndexOf('/')));
        try {
            addFile(out, entryPath, file);
        } catch (Exception e) {
            log.warn("Skipping the file " + entryPath + " of message " + message.getId() + ": " + e);
        }
    }

    /** Adds the file to the zip archive, streamed from the store, and says so when the entry came out short **/
    private void addFile(ZipOutputStream out, String path, RepoFileInfo file) throws IOException {
        log.debug("Adding file " + path + " to zip archive");
        out.putNextEntry(new ZipEntry(path));
        CountingOutputStream counting = new CountingOutputStream(out);
        try {
            store.copyTo(file, counting);
        } finally {
            if (counting.getByteCount() != file.size()) {
                log.warn("The archive entry " + path + " is truncated: " + counting.getByteCount() + " of "
                        + file.size() + " bytes");
            }
            out.closeEntry();
        }
    }

    /** Adds folders to the zip archive that have not already been added **/
    private void addParentFolders(ZipOutputStream out, Set<String> folderCache, String folder) {
        StringBuilder parentFolder = new StringBuilder();
        for (String path : folder.split("/")) {
            parentFolder.append(path).append("/");
            if (!folderCache.contains(parentFolder.toString())) {
                try {
                    log.debug("Adding folder " + parentFolder.toString() + " to zip archive");
                    out.putNextEntry(new ZipEntry(parentFolder.toString()));
                    out.closeEntry();
                    folderCache.add(parentFolder.toString());
                } catch (IOException ignored) {
                    log.warn("Failed adding folder " + parentFolder.toString() + " to zip archive");
                }
            }
        }
    }
}
