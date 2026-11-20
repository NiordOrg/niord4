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

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.repo.RepoPaths;
import org.niord.core.repo.RepoStore;
import org.niord.core.repo.RepositoryService;
import java.time.Instant;
import org.niord.model.search.PagedSearchResultVo;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports a list of messages from a zip file containing a messages.json file along with message attachments.
 * <p>
 * Please note, the actual msg-archive-import.xml job file is not placed in the META-INF/batch-jobs of this project,
 * but rather, in the META-INF/batch-jobs folder of the niord-web project.<br>
 * This is because of a class-loading bug in the Wildfly implementation. See e.g.
 * https://issues.jboss.org/browse/WFLY-4988
 * <p>
 * Format of json file is defined by the PagedSearchResultVo<SystemMessageVo> class. Example:
 * <pre>
 * {
 *   "data": [{
 *     "repoPath": "messages/d66c91f5/3ec5/4ce1-8d90-4b2e364b5048",
 *      "messageSeries": {
 *      "seriesId": "dma-nm"
 *      },
 *      "number": 1276,
 *      "shortId": "NM-1276-15",
 *      "mainType": "NM",
 *      "type": "PERMANENT_NOTICE",
 *      "status": "PUBLISHED",
 *      ...
 *    },
 *    ...
 *  ],
 *  "total": 20,
 *  "size": 20,
 *  "description": "Language: da, Domain: true, Statuses: [PUBLISHED], Series IDs: [dma-nm]"
 * }
 * </pre>
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchMsgArchiveImportReader")
public class BatchMsgArchiveImportReader extends AbstractItemHandler {

    /** The archive's index of its messages, read from the archive itself and never written as a row */
    static final String MESSAGES_JSON = "messages.json";

    List<ExtractedArchiveMessageVo> messages;
    int messageNo = 0;

    @Inject
    RepositoryService repositoryService;

    @Inject
    RepoStore store;

    /** The temp folder the archive was extracted to, whose rows close() deletes */
    private String tempArchiveRepoPath;

    /** {@inheritDoc} **/
    @Override
    public void open(Serializable prevCheckpointInfo) throws Exception {

        // Validate that we have access to the "seriesId" properties
        if (StringUtils.isBlank((String)job.getProperties().get("seriesId"))) {
            getLog().severe("Missing seriesId batch property");
            throw new Exception("Missing seriesId batch property");
        }

        // Extract the zip archive and get hold of the messages
        messages = extractMessageArchive();

        if (prevCheckpointInfo != null) {
            messageNo = (Integer) prevCheckpointInfo;
        }

        getLog().info("Start processing " + messages.size() + " messages from index " + messageNo);
    }

    /** Extracts the message archive and reads in the batch import messages */
    protected List<ExtractedArchiveMessageVo> extractMessageArchive() throws Exception {
        // Default implementation reads the messages from a message.json batch file
        Path path = batchService.getBatchJobDataFile(jobContext.getInstanceId());
        // Into temp rows of the store, which close() deletes when the job ends
        tempArchiveRepoPath = repositoryService.getNewTempDir().getPath();
        return readArchive(path, tempArchiveRepoPath, store);
    }

    /**
     * Extracts an archive's files into temp rows below a folder of the store, reads its {@code messages.json},
     * and wraps each message with its folder in the archive.
     *
     * @param path the archive
     * @param tempArchiveRepoPath the folder of the store the archive's files go below
     * @param store the store
     * @return the messages, each with its edit folder
     * @throws Exception when the archive has no index, an unreadable one, or a message that names a folder outside
     *         the archive's
     */
    List<ExtractedArchiveMessageVo> readArchive(Path path, String tempArchiveRepoPath, RepoStore store)
            throws Exception {
        extractEntries(path, tempArchiveRepoPath, store);
        getLog().info("Extracted message archive to " + tempArchiveRepoPath);

        // Read the messages.json from the archive itself: it is the import's own index, not a file of a message
        PagedSearchResultVo<SystemMessageVo> messages;
        try (ZipFile zipFile = new ZipFile(path.toFile())) {
            ZipEntry entry = zipFile.getEntry(MESSAGES_JSON);
            if (entry == null) {
                getLog().log(Level.SEVERE, "No valid messages.json file found in the archive");
                throw new Exception("No valid messages.json file found in the archive");
            }
            try (InputStream in = zipFile.getInputStream(entry)) {
                ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();
                messages = mapper.readValue(in, new TypeReference<PagedSearchResultVo<SystemMessageVo>>() {});
            } catch (JacksonException e) {
                getLog().log(Level.SEVERE, "Invalid messages.json file");
                throw new Exception("Invalid messages.json file");
            }
        }

        // Wrap the messages as ExtractedArchiveMessageVo with the "editRepoPath" pointing to the extracted archive
        try {
            return toExtractedMessages(messages.getData(), tempArchiveRepoPath);
        } catch (IllegalArgumentException e) {
            getLog().log(Level.SEVERE, e.getMessage());
            throw new Exception(e.getMessage());
        }
    }

    /**
     * Wraps the messages of an extracted archive with the folder of the archive that holds each one's files.
     * <p>
     * A message's folder is its {@code repoPath} inside the archive folder, and it is later copied into the
     * message's repository folder. The {@code repoPath} comes from the uploaded {@code messages.json}, and that
     * copy only requires the folder to lie below the temp root, which holds every user's uploads and extracted
     * archives. So the folder must lie strictly below this archive's own folder, and an archive that names one
     * anywhere else is refused as a whole, before a message is written - as an entry outside the folder fails
     * the extraction. A message that names no {@code repoPath} has no files to copy.
     *
     * @param messages the messages read from {@code messages.json}
     * @param tempArchiveRepoPath the repository path of the folder the archive was extracted to
     * @return the messages with their edit folders
     * @throws IllegalArgumentException when a message's {@code repoPath} leads out of the archive folder
     */
    static List<ExtractedArchiveMessageVo> toExtractedMessages(List<SystemMessageVo> messages, String tempArchiveRepoPath) {
        return messages.stream()
                .map(m -> new ExtractedArchiveMessageVo(m, editRepoPath(tempArchiveRepoPath, m.getRepoPath())))
                .collect(Collectors.toList());
    }

    /** The edit folder of a message inside the archive folder, see {@link #toExtractedMessages} */
    private static String editRepoPath(String tempArchiveRepoPath, String repoPath) {
        if (StringUtils.isBlank(repoPath)) {
            return null;
        }
        String editRepoPath = tempArchiveRepoPath + "/" + repoPath;
        if (!RepoPaths.isBelowFolder(editRepoPath, tempArchiveRepoPath)) {
            throw new IllegalArgumentException("A message in messages.json names a folder outside the archive: "
                    + repoPath);
        }
        return editRepoPath;
    }

    /** {@inheritDoc} **/
    @Override
    public Object readItem() throws Exception {
        if (messageNo < messages.size()) {

            // For every 5 read messages, update the progress
            if (messageNo % 5 == 0) {
                updateProgress((int)(100.0 * messageNo / messages.size()));
            }

            getLog().info("Reading message no " + messageNo);
            return messages.get(messageNo++);
        }
        return null;
    }

    /** {@inheritDoc} **/
    @Override
    public Serializable checkpointInfo() throws Exception {
        return messageNo;
    }

    /**
     * Deletes the extracted archive's temp rows when the job ends: the writer has copied each message's files into
     * the message's own folder by then, and a day's extracted archives would otherwise sit in the database until the
     * sweep.
     */
    @Override
    public void close() throws Exception {
        if (tempArchiveRepoPath != null) {
            int deleted = store.deleteFolder(tempArchiveRepoPath);
            getLog().info("Deleted the " + deleted + " extracted files of " + tempArchiveRepoPath);
        }
        super.close();
    }

    /**
     * Extracts the given zip file into temp rows of the store, below the given temp folder.
     * <p>
     * The archive is uploaded, so every entry's name is normalised and must lie strictly below the folder: an
     * entry that would land outside it fails the extraction. A {@code ZipFile} reads every entry's size from the
     * archive's central directory; one the directory does not state is written through the store's spool.
     *
     * @param path the archive
     * @param tempArchiveRepoPath the temp folder the archive is extracted to
     * @param store the store
     */
    static void extractEntries(Path path, String tempArchiveRepoPath, RepoStore store) throws IOException {
        Instant now = Instant.now();
        try (ZipFile zipFile = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || MESSAGES_JSON.equals(entry.getName())) {
                    // The folder entries are not rows, and the index is read from the archive itself
                    continue;
                }
                // An entry name is relative to the archive folder: an absolute one, or one that climbs out of the
                // folder, fails the extraction
                String relative = RepoPaths.normalize(entry.getName());
                String entryPath = (relative == null || relative.isEmpty())
                        ? null
                        : RepoPaths.join(tempArchiveRepoPath, relative);
                if (entryPath == null || !RepoPaths.isBelow(entryPath, tempArchiveRepoPath)) {
                    throw new IOException("The archive entry " + entry.getName() + " would land outside the archive folder");
                }
                try (InputStream in = zipFile.getInputStream(entry)) {
                    if (entry.getSize() >= 0) {
                        store.write(entryPath, in, entry.getSize(), now);
                    } else {
                        store.write(entryPath, in, now);
                    }
                }
            }
        }
    }

    /** Encapsulates a SystemMessageVo and the path to the temp repo where the archive was extracted */
    public static class ExtractedArchiveMessageVo {
        SystemMessageVo message;
        String editRepoPath;

        public ExtractedArchiveMessageVo(SystemMessageVo message, String editRepoPath) {
            this.message = message;
            this.editRepoPath = editRepoPath;
        }

        public SystemMessageVo getMessage() {
            return message;
        }

        public String getEditRepoPath() {
            return editRepoPath;
        }
    }
}
