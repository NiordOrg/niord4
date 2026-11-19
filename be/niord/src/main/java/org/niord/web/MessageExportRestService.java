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

package org.niord.web;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.domain.DomainService;
import org.niord.core.message.MessageExportService;
import org.niord.core.message.MessageSearchParams;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageTagService;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.user.Roles;
import org.niord.core.web.NiordWebException;
import org.niord.model.IJsonSerializable;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * REST interface for exporting and importing message archives.
 */
@RestController
@RequestMapping("/rest/message-io")
@Transactional
@PermitAll
@SuppressWarnings("unused")
public class MessageExportRestService extends AbstractBatchableRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageExportRestService.class);

    private final MessageSearchRestService messageSearchRestService;

    private final MessageExportService messageExportService;

    private final DomainService domainService;

    private final MessageTagService messageTagService;

    MessageExportRestService(MessageSearchRestService messageSearchRestService, MessageExportService messageExportService,
                             DomainService domainService, MessageTagService messageTagService) {
        this.messageSearchRestService = messageSearchRestService;
        this.messageExportService = messageExportService;
        this.domainService = domainService;
        this.messageTagService = messageTagService;
    }

    /**
     * Generates a ZIP archive for the message search result including attachments.
     */
    @GetMapping("/export.zip")
    public ResponseEntity<StreamingResponseBody> generateZipArchiveForSearch(HttpServletRequest request) throws Exception {

        // Perform a search for at most 1000 messages
        MessageSearchParams params = MessageSearchParams.instantiate(domainService.currentDomain(), request);
        params.language(null)
                .maxSize(1000)
                .page(0);

        PagedSearchResultVo<SystemMessageVo> result = messageSearchRestService.searchSystemMessages(params);
        result.getData().forEach(m -> m.sort(params.getLanguage()));
        // The archive's messages.json keeps its form: the mark of a search answered while the free-text index was
        // being filled is for the editors' list, not a property of an archive another system may import
        result.setIndexIncomplete(null);

        try {
            StreamingResponseBody stream = os -> messageExportService.export(result, os);

            return ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename=\"messages.zip\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(stream);

        } catch (Exception e) {
            log.error("Error generating ZIP archive for messages", e);
            throw e;
        }
    }

    /**
     * Imports an uploaded messages zip archive
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "text/plain")
    @RolesAllowed(Roles.ADMIN)
    public String importMessages(MultipartHttpServletRequest request) throws Exception {
        return executeBatchJobFromUploadedFile(request, "msg-archive-import");
    }

    /**
     * {@inheritDoc}
     *
     * @throws NiordWebException 400 when the file is not a message archive with a valid messages.json entry, or
     *                           the form's "data" field does not name a message series; 403 when it names a PUBLIC
     *                           tag of another domain, which the tag endpoints would not let the caller change
     *                           either (an unknown tag, or one the caller cannot see, passes: the import then adds
     *                           its messages to no tag)
     */
    @Override
    protected void checkBatchJob(String batchJobName, String filename, InputStream inputStream, Map<String, Object> params) throws Exception {

        // Check that the zip file contains a messages.json file
        if (!checkForMessagesFileInImportArchive(inputStream)) {
            throw new NiordWebException("The file " + filename + " is not a message archive: it has no valid"
                    + " messages.json entry", 400);
        }

        // Read and validate the parameters associated with the batch job
        ImportMessagesArchiveParams batchData;
        try {
            batchData = params.get("data") instanceof String data
                    ? JsonMapper.builderWithJackson2Defaults().build().readValue(data, ImportMessagesArchiveParams.class)
                    : null;
        } catch (JacksonException e) {
            batchData = null;
        }
        if (batchData == null) {
            throw new NiordWebException("Missing batch data with tag and message series", 400);
        }

        if (StringUtils.isBlank(batchData.getSeriesId())) {
            throw new NiordWebException("Missing message series for imported NMs", 400);
        }

        // The import adds its messages to the tag, so a PUBLIC tag of another domain is refused, as the tag
        // endpoints refuse it
        if (StringUtils.isNotBlank(batchData.getTagId())
                && messageTagService.belongsToAnotherDomain(messageTagService.findTag(batchData.getTagId()))) {
            throw new NiordWebException("The tag belongs to another domain", 403);
        }

        // Determine all valid message series for the current user
        Set<String> validMessageSeries = domainService.domainsWithUserRole(Roles.ADMIN).stream()
                .flatMap(d -> d.getMessageSeries().stream())
                .map(MessageSeries::getSeriesId)
                .collect(Collectors.toSet());

        // Update parameters
        params.remove("data");
        params.put("seriesId", batchData.getSeriesId());
        if (StringUtils.isNotBlank(batchData.getTagId())) {
            params.put("tagId", batchData.getTagId());
        }
        params.put("assignNewUids", batchData.getAssignNewUids() == null ? false : batchData.getAssignNewUids());
        params.put("preserveStatus", batchData.getPreserveStatus() == null ? false : batchData.getPreserveStatus());
        params.put("assignDefaultSeries", batchData.getAssignDefaultSeries() == null ? false : batchData.getAssignDefaultSeries());
        params.put("createBaseData", batchData.getCreateBaseData() == null ? false : batchData.getCreateBaseData());
        params.put("validMessageSeries", validMessageSeries);
    }

    /** Checks for a valid "messages.xml" zip file entry **/
    private boolean checkForMessagesFileInImportArchive(InputStream in) throws Exception {
        try (ZipInputStream zipFile = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zipFile.getNextEntry()) != null) {
                if ("messages.json".equals(entry.getName())) {
                    try {
                        ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();
                        PagedSearchResultVo<SystemMessageVo> messages = mapper.readValue(
                                zipFile,
                                new TypeReference<PagedSearchResultVo<SystemMessageVo>>() {});
                        return  messages != null;
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /***************************
     * Helper classes
     ***************************/

    /** Defines the parameters used when starting an import a messages zip archive */
    public static class ImportMessagesArchiveParams implements IJsonSerializable {

        Boolean assignNewUids;
        Boolean preserveStatus;
        Boolean assignDefaultSeries;
        Boolean createBaseData;
        String seriesId;
        String tagId;

        public Boolean getAssignNewUids() {
            return assignNewUids;
        }

        public void setAssignNewUids(Boolean assignNewUids) {
            this.assignNewUids = assignNewUids;
        }

        public Boolean getPreserveStatus() {
            return preserveStatus;
        }

        public void setPreserveStatus(Boolean preserveStatus) {
            this.preserveStatus = preserveStatus;
        }

        public Boolean getAssignDefaultSeries() {
            return assignDefaultSeries;
        }

        public void setAssignDefaultSeries(Boolean assignDefaultSeries) {
            this.assignDefaultSeries = assignDefaultSeries;
        }

        public Boolean getCreateBaseData() {
            return createBaseData;
        }

        public void setCreateBaseData(Boolean createBaseData) {
            this.createBaseData = createBaseData;
        }

        public String getSeriesId() {
            return seriesId;
        }

        public void setSeriesId(String seriesId) {
            this.seriesId = seriesId;
        }

        public String getTagId() {
            return tagId;
        }

        public void setTagId(String tagId) {
            this.tagId = tagId;
        }
    }

}
