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

import org.apache.commons.lang3.StringUtils;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationException;
import org.niord.core.promulgation.PromulgationManager;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.core.promulgation.vo.BaseMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationServiceVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.PublicPromulgationTypeVo;
import org.niord.core.user.Roles;
import org.niord.core.user.UserService;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartVo;
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

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST interface for accessing and managing promulgation types.
 */
@RestController
@RequestMapping("/rest/promulgations")
@Transactional
@SuppressWarnings("unused")
public class PromulgationRestService extends AbstractBatchableRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PromulgationRestService.class);

    private final PromulgationTypeService promulgationTypeService;

    private final PromulgationManager promulgationManager;

    private final UserService userService;

    PromulgationRestService(PromulgationTypeService promulgationTypeService, PromulgationManager promulgationManager, UserService userService) {
        this.promulgationTypeService = promulgationTypeService;
        this.promulgationManager = promulgationManager;
        this.userService = userService;
    }

    /***************************************/
    /** Promulgation Services             **/
    /***************************************/

    /** Returns all promulgation service IDs */
    @GetMapping(value = "/promulgation-services/all", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public List<PromulgationServiceVo> getPromulgationServices() {
        return promulgationManager.promulgationServices();
    }

    /***************************************/
    /** Promulgation Types                **/
    /***************************************/

    /** Returns all promulgation types */
    @GetMapping(value = "/promulgation-types/all", produces = "application/json;charset=UTF-8")
    @PermitAll // NB: Checked programmatically to facilitate tickets
    public List<PromulgationTypeVo> getPromulgationTypes() {

        // If a ticket is defined, check if programmatically
        if (!userService.isCallerInRole(Roles.SYSADMIN)) {
            throw new NiordWebException(403);
        }

        return promulgationTypeService.getAll().stream()
                .map(PromulgationType::toVo)
                .toList();
    }

    /** Returns the given promulgation type */
    @GetMapping(value = "/promulgation-type/{typeId}", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public PromulgationTypeVo getPromulgationType(@PathVariable("typeId") String typeId) throws Exception {
        PromulgationType type = promulgationTypeService.getPromulgationType(typeId);
        if (type == null) {
            throw new NiordWebException("No such promulgation type: " + typeId, 404);
        }
        return type.toVo();
    }

    /** Creates a promulgation type */
    @PostMapping(value = {"/promulgation-type", "/promulgation-type/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public PromulgationTypeVo createPromulgationType(@RequestBody PromulgationTypeVo type) throws Exception {
        if (StringUtils.isBlank(type.getTypeId())) {
            throw new IllegalArgumentException("A promulgation type needs a type ID");
        }
        if (StringUtils.isBlank(type.getServiceId())) {
            throw new IllegalArgumentException("The promulgation type " + type.getTypeId() + " needs a service ID");
        }
        refuseBlankName(type);
        return promulgationTypeService.createPromulgationType(new PromulgationType(type)).toVo();
    }

    /** Refuses a promulgation type without a name, which the NOT NULL column would refuse at the flush with a 500 */
    private static void refuseBlankName(PromulgationTypeVo type) {
        if (StringUtils.isBlank(type.getName())) {
            throw new IllegalArgumentException("The promulgation type " + type.getTypeId() + " needs a name");
        }
    }

    /** Updates an existing promulgation type */
    @PutMapping(value = "/promulgation-type/{typeId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public PromulgationTypeVo updatePromulgationType(
            @PathVariable("typeId") String typeId,
            @RequestBody PromulgationTypeVo type
    ) throws Exception {

        if (!Objects.equals(typeId, type.getTypeId())) {
            throw new NiordWebException(400);
        }
        refuseBlankName(type);

        return promulgationTypeService.updatePromulgationType(new PromulgationType(type)).toVo();
    }

    /** Deletes an existing promulgation type */
    @DeleteMapping("/promulgation-type/{typeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RolesAllowed(Roles.SYSADMIN)
    public void updatePromulgationType(@PathVariable("typeId") String typeId) throws Exception {
         promulgationTypeService.deletePromulgationType(typeId);
    }

    /**
     * Imports an uploaded promulgation types json file
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/upload-promulgation-types", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "text/plain")
    @RolesAllowed(Roles.SYSADMIN)
    public String importReports(MultipartHttpServletRequest request) throws Exception {
        return executeBatchJobFromUploadedFile(request, "promulgation-type-import");
    }

    /***************************************/
    /** Public Promulgation Types         **/
    /***************************************/

    /** Returns the promulgation types with the given comma-separated IDs  */
    @GetMapping(value = "/public-promulgation-type/{typeIds}", produces = "application/json;charset=UTF-8")
    @PermitAll
    public List<PublicPromulgationTypeVo> getPublicPromulgationTypes(@PathVariable("typeIds") String typeIds) {
        Set<String> ids = new HashSet<>();
        if (StringUtils.isNotBlank(typeIds)) {
            ids.addAll(Arrays.asList(typeIds.split(",")));
        }
        return promulgationTypeService.getAll().stream()
                .filter(t -> ids.contains(t.getTypeId()))
                .map(PromulgationType::toVo)
                .map(PromulgationTypeVo::toPublicPromulgationType)
                .toList();
    }

    /** Searches the promulgation types whose type ID or name matches the given string  */
    @GetMapping(value = "/search-public-promulgation-type", produces = "application/json;charset=UTF-8")
    @PermitAll
    public List<PublicPromulgationTypeVo> searchPublicPromulgationTypes(
            @RequestParam(name = "type", defaultValue = "")  String type) {
        if (StringUtils.isBlank(type)) {
            return Collections.emptyList();
        }
        String str = type.toLowerCase();
        return promulgationTypeService.getAll().stream()
                .filter(t -> t.getTypeId().toLowerCase().contains(str) || t.getName().toLowerCase().contains(str))
                .map(PromulgationType::toVo)
                .map(PromulgationTypeVo::toPublicPromulgationType)
                .collect(Collectors.toList());
    }

    /***************************************/
    /** Message Promulgation Management   **/
    /***************************************/

    /**
     * Generates a message promulgation record for the given typeId and message
     *
     * @throws NiordWebException 500 naming the type when the type's script or its service fails: the deployment's
     *                           own data or code failed, which the editor cannot change. The answer does not quote
     *                           the error, which may hold the script's text; the server log holds it.
     */
    @PostMapping(value = "/generate/{typeId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public BaseMessagePromulgationVo<?> generateMessagePromulgation(
            @PathVariable("typeId") String typeId,
            @RequestBody SystemMessageVo messageVo
    ) throws Exception {

        log.info("Updating promulgation service " + typeId);
        checkMessageToGenerateFrom(messageVo);
        try {
            return promulgationManager.generateMessagePromulgation(typeId, messageVo);
        } catch (PromulgationException e) {
            log.error("Generate failed for promulgation type " + typeId, e);
            throw new NiordWebException("The " + typeId + " promulgation could not be generated: a script of the"
                    + " promulgation type, or its service, failed. A system administrator finds the error in the"
                    + " server log.", 500);
        }
    }

    /**
     * Makes the message a promulgation is generated from readable by every promulgation service, or refuses it.
     * <p>
     * A message without parts, or a part without descriptions, holds no text: the list is taken as empty, as the
     * audio service already reads it, and the generated promulgation has no text. A message that has no parts is
     * sent that way by the server itself (the value object of a message with no parts has no list). A part that is
     * missing from the list, a description without a language and a promulgation without a type cannot be read and
     * are refused with 400.
     * @param message the message from the request body
     */
    static void checkMessageToGenerateFrom(SystemMessageVo message) {
        if (message.getParts() == null) {
            message.setParts(new ArrayList<>());
        }
        for (MessagePartVo part : message.getParts()) {
            if (part == null) {
                throw new IllegalArgumentException("A message part is missing from the list of parts");
            }
            if (part.getDescs() == null) {
                part.setDescs(new ArrayList<>());
            }
            for (MessagePartDescVo desc : part.getDescs()) {
                if (desc == null || StringUtils.isBlank(desc.getLang())) {
                    throw new IllegalArgumentException("Each description of a message part must name its language");
                }
            }
        }
        if (message.getPromulgations() != null) {
            for (BaseMessagePromulgationVo<?> promulgation : message.getPromulgations()) {
                if (promulgation == null || promulgation.getType() == null
                        || StringUtils.isBlank(promulgation.getType().getTypeId())) {
                    throw new IllegalArgumentException("Each promulgation of the message must name its type");
                }
            }
        }
    }
}
