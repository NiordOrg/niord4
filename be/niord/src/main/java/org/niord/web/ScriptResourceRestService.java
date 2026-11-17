/*
 * Copyright 2017 Danish Maritime Authority.
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
import jakarta.inject.Inject;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.script.MissingPathReport;
import org.niord.core.script.ScriptResource;
import org.niord.core.script.ScriptResourceHistory;
import org.niord.core.script.ScriptResourceImportService;
import org.niord.core.script.ScriptResourceService;
import org.niord.core.script.vo.ScriptResourceHistoryVo;
import org.niord.core.script.vo.ScriptResourceImportPreviewVo;
import org.niord.core.script.vo.ScriptResourceVo;
import org.niord.core.user.CurrentUserContext;
import org.niord.core.user.Roles;
import org.niord.core.user.UserService;
import org.niord.core.util.JsonUtils;
import org.niord.core.util.WebUtils;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
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

import org.niord.core.web.NiordWebException;
import tools.jackson.core.type.TypeReference;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * REST interface for accessing script resources, such as Freemarker templates and JavaScript Resources.
 */
@RestController
@RequestMapping("/rest/script-resources")
@RolesAllowed(Roles.SYSADMIN)
@SuppressWarnings("unused")
public class ScriptResourceRestService extends AbstractBatchableRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ScriptResourceRestService.class);

    private final UserService userService;

    @Inject
    CurrentUserContext currentUserContext;

    private final ScriptResourceService resourceService;

    private final MissingPathReport missingPathReport;

    private final ScriptResourceImportService importService;

    ScriptResourceRestService(UserService userService, ScriptResourceService resourceService,
                              MissingPathReport missingPathReport, ScriptResourceImportService importService) {
        this.userService = userService;
        this.resourceService = resourceService;
        this.missingPathReport = missingPathReport;
        this.importService = importService;
    }

    /** Returns all script resources */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    @PermitAll
    public List<ScriptResourceVo> getScriptResources(
            @RequestParam(name = "type", required = false) ScriptResource.Type type) {

        // If a ticket is defined, check if programmatically
        if (!userService.isCallerInRole(Roles.ADMIN)) {
            throw new NiordWebException("User must be in admin role, was in " + currentUserContext.currentUserRoles(), 403);
        }

        if (type != null) {
            return resourceService.findByTypes(type).stream()
                    .map(ScriptResource::toVo)
                    .collect(Collectors.toList());
        }

        return resourceService.findAll().stream()
                .map(ScriptResource::toVo)
                .collect(Collectors.toList());
    }

    /** Creates a new script resource */
    @PostMapping(value = {"/script-resource", "/script-resource/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public ScriptResourceVo createScriptResource(@RequestBody ScriptResourceVo resource) throws Exception {
        log.info("Creating resource " + resource);
        return resourceService.createScriptResource(new ScriptResource(resource), null,
                        ScriptResourceHistory.SOURCE_EDITOR)
                .toVo();
    }

    /** Updates an existing script resource */
    @PutMapping(value = "/script-resource/{id}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public ScriptResourceVo updateScriptResource(@PathVariable("id") Integer id, @RequestBody ScriptResourceVo resource) throws Exception {
        if (!Objects.equals(id, resource.getId())) {
            throw new NiordWebException(400);
        }

        log.info("Updating resource " + resource);
        return resourceService.updateScriptResource(new ScriptResource(resource), null,
                        ScriptResourceHistory.SOURCE_EDITOR)
                .toVo();
    }

    /** Deletes an existing script resource */
    @DeleteMapping("/script-resource/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteScriptResource(@PathVariable("id") Integer id) throws Exception {
        log.info("Deleting resource " + id);
        resourceService.deleteScriptResource(id);
    }

    /**
     * Returns what importing the uploaded script resources file would do (docs/code-data-config-plan.md §6.4): the
     * paths it adds, marking those the missing-path report lists, the paths it would overwrite with the row's content
     * and the file's, the paths it leaves unchanged, and the files it refuses with their reasons. It only reads.
     *
     * @param request the multi-part form data request, with the file as the upload sends it
     * @return what the import would do
     */
    @PostMapping(value = "/import-preview",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/json;charset=UTF-8")
    public ScriptResourceImportPreviewVo previewScriptResourceImport(MultipartHttpServletRequest request)
            throws Exception {
        return importService.preview(readImportFile(request));
    }

    /**
     * Imports an uploaded script resources json file (docs/code-data-config-plan.md §6.4). The sysadmin sees the
     * preview first ({@code /import-preview}); the import then adds the missing paths and overwrites an existing
     * path only when the sysadmin ticked it. Two optional text fields carry the ticks, each a JSON array:
     * {@code add}, the missing paths to add (absent: every missing path of the file), and {@code overwrite}, the
     * existing paths to overwrite (absent: none), each a path or {@code {"path", "basedOn"}} with the preview's
     * fingerprint of the row, which keeps a row changed since the preview. A file the script check refuses is
     * skipped, and the job log names its reasons. Every write is recorded in the resource's history with the user
     * and the source {@code import}.
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/upload-script-resources",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "text/plain")
    @RolesAllowed(Roles.SYSADMIN)
    public String importScriptResources(MultipartHttpServletRequest request) throws Exception {
        // The ticks are checked before any job starts; the job reads them from its properties
        Map<String, Object> fields = WebUtils.getMultipartFormParams(request);
        for (String field : List.of(ScriptResourceImportService.ADD, ScriptResourceImportService.OVERWRITE)) {
            Object value = fields.get(field);
            if (value != null && !(value instanceof String)) {
                throw new NiordWebException("The field '" + field + "' is given more than once", 400);
            }
            try {
                if (ScriptResourceImportService.OVERWRITE.equals(field)) {
                    ScriptResourceImportService.ticks(field, value);
                } else {
                    ScriptResourceImportService.paths(field, value);
                }
            } catch (IllegalArgumentException e) {
                throw new NiordWebException(e.getMessage(), 400);
            }
        }
        return executeBatchJobFromUploadedFile(request, "script-resource-import");
    }

    /** Reads the one uploaded file as a list of script resources; 400 when there is none or it is not one */
    private static List<ScriptResourceVo> readImportFile(MultipartHttpServletRequest request) throws Exception {
        Map<String, InputStream> files = WebUtils.getMultipartFiles(request);
        if (files.size() != 1) {
            throw new NiordWebException("Upload one script resources file; the request has " + files.size(), 400);
        }
        Map.Entry<String, InputStream> file = files.entrySet().iterator().next();
        List<ScriptResourceVo> resources;
        try (InputStream in = file.getValue()) {
            resources = JsonUtils.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<List<ScriptResourceVo>>() {});
        } catch (Exception e) {
            throw new NiordWebException("The file " + file.getKey() + " is not a list of script resources: "
                    + e.getMessage(), 400);
        }
        if (resources == null) {
            throw new NiordWebException("The file " + file.getKey() + " is not a list of script resources", 400);
        }
        return resources;
    }

    /***************************************/
    /** Freemarker Template History       **/
    /***************************************/

    /**
     * Returns the script resource history for the given resource ID
     * @param resourceId the script resource ID or resource series ID
     * @return the resource history
     */
    @GetMapping(value = "/script-resource/{resourceId}/history", produces = "application/json;charset=UTF-8")
    public List<ScriptResourceHistoryVo> getScriptResourceHistory(@PathVariable("resourceId") Integer resourceId) {

        // Get the resource id
        ScriptResource resource = resourceService.findById(resourceId);
        if (resource == null) {
            return Collections.emptyList();
        }

        return resourceService.getScriptResourceHistory(resource.getId()).stream()
                .map(ScriptResourceHistory::toVo)
                .collect(Collectors.toList());
    }

    /***************************************/
    /** Missing paths                     **/
    /***************************************/

    /**
     * Returns the missing-path report (docs/code-data-config-plan.md §6.6): every template or script path that data
     * or the code names and that has no row, and every built-in report id with no report. It only reads; a sysadmin
     * imports the listed paths from a release file.
     *
     * @return the missing paths and report ids
     */
    @GetMapping(value = "/missing-paths", produces = "application/json;charset=UTF-8")
    public List<MissingPathReport.Entry> getMissingPaths() {
        return missingPathReport.report();
    }
}
