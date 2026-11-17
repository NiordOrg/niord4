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

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.batch.BatchService;
import org.niord.core.batch.BatchSetService;
import org.niord.core.batch.vo.BatchInstanceVo;
import org.niord.core.batch.vo.BatchStatusVo;
import org.niord.core.repo.FileTypes;
import org.niord.core.repo.IconSize;
import org.niord.core.repo.RepoImportService;
import org.niord.core.repo.RepositoryService;
import org.niord.core.web.NiordWebException;
import org.niord.core.user.Roles;
import org.niord.core.user.UserService;
import org.niord.core.util.WebUtils;
import org.niord.model.IJsonSerializable;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import org.niord.core.web.ServedContentHeaders;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API for accessing the batch functionality.
 * <p>
 * The endpoints that read or control individual job instances and executions (instances, logs,
 * data-file download, stop/restart/abandon) are sysadmin only, matching the admin application, which
 * shows the batch monitor in its sysadmin area. Instances and executions are addressed by id alone and
 * are not scoped to a domain, while the ADMIN role is granted per domain.
 */
@RestController
@RequestMapping("/rest/batch")
@Transactional
public class BatchRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BatchRestService.class);

    private final BatchService batchService;

    private final BatchSetService batchSetService;

    private final FileTypes fileTypes;

    private final UserService userService;

    private final RepositoryService repositoryService;

    private final RepoImportService repoImportService;

    BatchRestService(BatchService batchService, BatchSetService batchSetService, FileTypes fileTypes, UserService userService,
                     RepositoryService repositoryService, RepoImportService repoImportService) {
        this.batchService = batchService;
        this.batchSetService = batchSetService;
        this.fileTypes = fileTypes;
        this.userService = userService;
        this.repositoryService = repositoryService;
        this.repoImportService = repoImportService;
    }

    /**
     * Returns the job names
     * @return the job names
     */
    @GetMapping(value = "/job-names", produces = "application/json;charset=UTF-8")
    @PermitAll
    public List<String> getJobNames() {
        return batchService.getJobNames();
    }

    /**
     * Stops the given batch job execution
     *
     * @param executionId the execution ID
     */
    @PutMapping("/execution/{executionId}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.SYSADMIN)
    public void stopExecution(@PathVariable("executionId") long executionId) {
        batchService.stopExecution(executionId);
    }

    /**
     * Restarts the given batch job execution
     *
     * @param executionId the execution ID
     */
    @PutMapping("/execution/{executionId}/restart")
    @RolesAllowed(Roles.SYSADMIN)
    public long restartExecution(@PathVariable("executionId") long executionId) {
        return batchService.restartExecution(executionId);
    }

    /**
     * Abandons the given batch job execution
     *
     * @param executionId the execution ID
     */
    @PutMapping("/execution/{executionId}/abandon")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.SYSADMIN)
    public void abandonExecution(@PathVariable("executionId") long executionId) {
        batchService.abandonExecution(executionId);
    }

    /**
     * Returns the paged search result for the given batch type
     * @param jobName the job name
     * @param page the page index of the paged search result
     * @param pageSize the max number of instances per page
     * @return the paged search result for the given batch type
     */
    @GetMapping(value = "/{jobName}/instances", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public PagedSearchResultVo<BatchInstanceVo> getJobInstances(
            @PathVariable("jobName") String jobName,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "pageSize", defaultValue = "10") int pageSize) throws Exception {

        return batchService.getJobInstances(jobName, page * pageSize, pageSize);
    }

    /**
     * Returns the status of the batch job system
     *
     * NB: Since this gets called every 10 seconds, adding a @RolesAllowed(Roles.ADMIN) has
     * the potential of filling up the server log.
     * Instead, we allow all to call the endpoint, but return an empty result for non-admin users.
     *
     * @return the status of the batch job system
     */
    @GetMapping(value = "/status", produces = "application/json;charset=UTF-8")
    @PermitAll
    public BatchStatusVo getStatus() {
        if (!userService.isCallerInRole(Roles.ADMIN)) {
            return new BatchStatusVo();
        }
        return batchService.getStatus();
    }

    /**
     * Downloads the batch data associated with the given instance.
     * First, the callee must call the "/rest/tickets/ticket?role=sysadmin" endpoint using Ajax
     * to retrieve a valid ticket, and then pass the ticket along in this function
     * <p>
     * Sysadmin only, like the other instance and execution endpoints: a batch instance is looked up by
     * its id alone, whichever domain started the job.
     * <p>
     * The data file is whatever an administrator uploaded, of any type, and this origin serves the
     * administration application too. So it is always sent as {@code application/octet-stream}, as an
     * attachment named after the stored file, with {@code nosniff}: opening it downloads it and never
     * renders it here.
     *
     * @param instanceId the instance ID
     */
    @GetMapping("/instance/{instanceId}/download/{fileName:.*}")
    @PermitAll
    public ResponseEntity<Object> downloadBatchDataFile(
            @PathVariable("instanceId") long instanceId,
            @PathVariable("fileName") String fileName) {

        // Check the ticket programmatically
        if (!userService.isCallerInRole(Roles.SYSADMIN)) {
            throw new NiordWebException(403);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(ServedContentHeaders.X_CONTENT_TYPE_OPTIONS, ServedContentHeaders.NOSNIFF);

        Path f = batchService.getBatchJobDataFile(instanceId);
        if (f == null) {
            log.warn("Failed streaming batch file for instance " + instanceId);
            // Plain text, and not the requested file name, which is part of the address
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .headers(headers)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Batch data file not found");
        }

        // Set expiry to 12 hours
        Date expirationDate = new Date(System.currentTimeMillis() + 1000L * 60L * 60L * 12L);

        log.trace("Streaming file: " + f);
        headers.setExpires(expirationDate.getTime());
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(f.getFileName().toString(), StandardCharsets.UTF_8)
                .build());
        return ResponseEntity
                .ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(f.toFile()));
    }

    /**
     * Returns the thumbnail to use for the given batch job instance
     * @param size the icon size, either 32, 64 or 128
     * @return the thumbnail to use for the given batch job instance
     */
    @GetMapping("/instance/{instanceId}/thumbnail.png")
    @PermitAll
    public ResponseEntity<Void> getBatchDataFileThumbnail(
            @PathVariable("instanceId") long instanceId,
            @RequestParam(name = "size", defaultValue = "32") int size) throws IOException, URISyntaxException {

        IconSize iconSize = IconSize.getIconSize(size);
        Path f = batchService.getBatchJobDataFile(instanceId);

        // Set expiry to 12 hours
        Date expirationDate = new Date(System.currentTimeMillis() + 1000L * 60L * 60L * 12L);

        // The icon path is root-relative (e.g. /img/filetypes/...). JAX-RS Response.temporaryRedirect
        // absolutized the Location against /rest; Spring MVC would emit the relative value verbatim (the
        // client would resolve it against the request URI and 404), so build an absolute URL.
        String iconUri = fileTypes.getIcon(f, iconSize);

        log.trace("Redirecting to thumbnail: " + iconUri);
        HttpHeaders headers = new HttpHeaders();
        headers.setExpires(expirationDate.getTime());
        return ResponseEntity
                .status(HttpStatus.TEMPORARY_REDIRECT)
                .headers(headers)
                .location(WebUtils.absoluteUrl(iconUri))
                .build();
    }

    /**
     * Returns the names of log files for the batch job with the given instance.
     * @param instanceId the instance ID
     */
    @GetMapping("/instance/{instanceId}/logs")
    @RolesAllowed(Roles.SYSADMIN)
    public List<String> getBatchJobLogFiles(@PathVariable("instanceId") long instanceId) throws IOException {

        return batchService.getBatchJobLogFiles(instanceId);
    }

    /**
     * Returns the contents of the batch job log file with the given name.
     *
     * @param instanceId the instance id
     * @param logFileName the log file name
     * @param fromLineNo if specified, only the subsequent lines are returned
     * @return the contents of the log file
     */
    @GetMapping(value = "/instance/{instanceId}/logs/{logFileName}", produces = "text/plain")
    @RolesAllowed(Roles.SYSADMIN)
    public String getBatchJobLogFileContent(
            @PathVariable("instanceId") long instanceId,
            @PathVariable("logFileName") String logFileName,
            @RequestParam(name = "fromLineNo", required = false) Integer fromLineNo) throws IOException {

        return batchService.getBatchJobLogFile(instanceId, logFileName, fromLineNo);
    }

    /**
     * Executes an uploaded batch set: a zip archive whose batch-set.json names the jobs and their data files. A batch
     * set may name only the jobs of {@link org.niord.core.batch.BatchSetService#BATCH_SET_JOBS}; one that names any
     * other (the script-resource, dictionary or settings import, or script-executor), or whose item carries the
     * property {@code bootstrapRun} (the bootstrap runner's own token), is refused whole and schedules nothing, and
     * the answer ends with the refusal.
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/execute-batch-set",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "text/plain")
    @RolesAllowed(Roles.SYSADMIN)
    public String executeBatchSet(MultipartHttpServletRequest request) throws Exception {

        // Initialise the form parsing parameters
        final Map<String, InputStream> formFiles = WebUtils.getMultipartFiles(request);
        final StringBuilder txt = new StringBuilder();

        // Start the batch job for each file item
        formFiles.entrySet()
                .stream()
                .forEach(item -> {
                    try {
                        txt.append("Processing batch-set zip-file: ")
                                .append(item.getKey())
                                .append("\n");
                        batchSetService.extractAndExecuteBatchSetArchive(item.getValue(), txt);
                    } catch (Exception e) {
                        String errorMsg = "Error processing batch-set zip-file "
                                + item.getKey()
                                + ": "
                                + e.getMessage();
                        log.error(errorMsg, e);
                        txt.append(errorMsg);
                    }
                });

        return txt.toString();
    }

    /**
     * Executes the given javascript via the "script-executor" batch job
     *
     * NB: Important to only let sysadmin access this operation.
     *
     * @param params the javascript parameter
     */
    @PostMapping(value = "/execute-javascript",
            consumes = "application/json;charset=UTF-8",
            produces = "text/plain")
    @RolesAllowed(Roles.SYSADMIN)
    public String executeJavaScript(@RequestBody ExecuteJavaScriptParams params) throws Exception {

        if (StringUtils.isNotBlank(params.getJavaScript())) {
            log.warn("User " + userService.currentUser().getName()
                    + " scheduled execution of JavaScript:\n" + params.getJavaScript());

            batchService.startBatchJobWithDataFile(
                    "script-executor",
                    IOUtils.toInputStream(params.getJavaScript(), "UTF-8"),
                    StringUtils.defaultIfBlank(params.getScriptName(), "javascript.js"),
                    new HashMap<>());
        }

        return "OK";
    }

    /**
     * Starts the repository import, the {@code repo-import} batch job: the files of an old repository root on disk
     * become rows of the repository store, and those of an old publication archive root rows under the store's
     * archive folder, with the audit entries that name them rewritten. The job skips a file that already has a row
     * (or compares it, with {@code verify}), moves the derived files into the cache tree, and ends by checking that
     * every reference the database holds resolves to a row; its report is the job's log on the Batch page.
     * <p>
     * Both folders must lie below the home ({@code niord.home}), where the volume of the days the repository was on
     * disk is mounted; any other folder is refused, since the job reads what it is given into the database.
     *
     * @param params the folders, and whether to compare the files that already have a row
     * @return a status line
     */
    @PostMapping(value = "/repo-import",
            consumes = "application/json;charset=UTF-8",
            produces = "text/plain")
    @RolesAllowed(Roles.SYSADMIN)
    public String repoImport(@RequestBody RepoImportParams params) throws Exception {
        if (params == null || StringUtils.isBlank(params.getDir())) {
            throw new NiordWebException("The folder to import, dir, is missing", 400);
        }
        Path dir;
        Path archiveDir;
        try {
            dir = repoImportService.confine(params.getDir());
            archiveDir = repoImportService.confine(params.getArchiveDir());
        } catch (IllegalArgumentException e) {
            throw new NiordWebException(e.getMessage(), 400);
        }

        Map<String, Object> properties = new HashMap<>();
        properties.put(RepoImportService.DIR, dir.toString());
        if (archiveDir != null) {
            properties.put(RepoImportService.ARCHIVE_DIR, archiveDir.toString());
        }
        properties.put(RepoImportService.VERIFY, params.isVerify());
        log.warn("User " + userService.currentUser().getName() + " started the repository import of " + dir
                + (archiveDir != null ? " and the archive " + archiveDir : "") + (params.isVerify() ? ", verifying" : ""));

        batchService.startBatchJobWithJsonData(RepoImportService.JOB_NAME, params, "repo-import.json", properties);

        return "Started batch job '" + RepoImportService.JOB_NAME + "' of " + dir
                + (archiveDir != null ? " and " + archiveDir : "");
    }

    /**
     * The parameters of the repository import
     */
    @SuppressWarnings("unused")
    public static class RepoImportParams implements IJsonSerializable {
        String dir;
        String archiveDir;
        boolean verify;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }

        public String getArchiveDir() {
            return archiveDir;
        }

        public void setArchiveDir(String archiveDir) {
            this.archiveDir = archiveDir;
        }

        public boolean isVerify() {
            return verify;
        }

        public void setVerify(boolean verify) {
            this.verify = verify;
        }
    }

    /**
     * Used when executing a back-end JavaScript
     */
    @SuppressWarnings("unused")
    public static class ExecuteJavaScriptParams implements IJsonSerializable {
        String scriptName;
        String javaScript;

        public String getScriptName() {
            return scriptName;
        }

        public void setScriptName(String scriptName) {
            this.scriptName = scriptName;
        }

        public String getJavaScript() {
            return javaScript;
        }

        public void setJavaScript(String javaScript) {
            this.javaScript = javaScript;
        }
    }

}
