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

import org.niord.core.NiordApp;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.aton.LightCharacterParser;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryService;
import org.niord.core.category.ParamType;
import org.niord.core.category.StandardParamType;
import org.niord.core.category.TemplateExecutionService;
import org.niord.core.category.vo.ListParamTypeVo;
import org.niord.core.category.vo.ParamTypeVo;
import org.niord.core.category.vo.StandardParamTypeVo;
import org.niord.core.category.vo.SystemCategoryVo;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.user.Roles;
import org.niord.core.user.UserService;
import org.niord.model.DataFilter;
import org.niord.model.IJsonSerializable;
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

import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * REST interface for executing message templates.
 *
 * Message templates are actually defined by categories of type "TEMPLATE".
 */
@RestController
@RequestMapping("/rest/templates")
@Transactional
@PermitAll
public class TemplateExecutionRestService extends AbstractBatchableRestService {

    private final NiordApp app;

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TemplateExecutionRestService.class);

    private final CategoryService categoryService;

    private final TemplateExecutionService templateExecutionService;

    private final MessageRestService messageRestService;

    private final UserService userService;

    TemplateExecutionRestService(NiordApp app, CategoryService categoryService, TemplateExecutionService templateExecutionService, MessageRestService messageRestService, UserService userService) {
        this.app = app;
        this.categoryService = categoryService;
        this.templateExecutionService = templateExecutionService;
        this.messageRestService = messageRestService;
        this.userService = userService;
    }

    /**
     * *******************************************
     * parameter types functionality
     * *******************************************
     */

    /**
     * Returns the parameter types: the standard ones, which are code and have no id, and the deployment's others
     *
     * @param lang the language return data for
     * @return the parameter types
     */
    @GetMapping(value = "/parameter-types", produces = "application/json;charset=UTF-8")
    public List<ParamTypeVo> getParamTypes(@RequestParam(name = "lang", required = false) String lang) {

        DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage()).lang(lang);

        return templateExecutionService.getParamTypes().stream()
                .map(pt -> pt.toVo(filter))
                .collect(Collectors.toList());
    }

    /**
     * Returns the parameter types with a few changes:
     * <ul>
     *     <li>Resets all IDs to make them suitable for export/import</li>
     *     <li>Removes standard parameter types, as these are code</li>
     * </ul>
     *
     * @return the parameter types
     */
    @GetMapping(value = "/parameter-types/export", produces = "application/json;charset=UTF-8")
    public List<ParamTypeVo> exportParamTypes() {

        List<ParamTypeVo> result = getParamTypes(null);

        // Remove standard parameter types
        result.removeIf(pt -> pt instanceof StandardParamTypeVo);

        // Reset all IDs
        result.forEach(pt -> pt.setId(null));
        result.stream()
            .filter(pt -> pt instanceof ListParamTypeVo)
            .map(pt -> (ListParamTypeVo)pt)
            .flatMap(pt -> pt.getValues().stream())
            .forEach(v -> {
                v.setId(null);
                v.setDescs(null);
                v.setAtonFilter(null);
            });

        return result;
    }

    /**
     * Imports an uploaded parameter types json file
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/upload-param-types", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "text/plain")
    @RolesAllowed(Roles.SYSADMIN)
    public String importParameterTypes(MultipartHttpServletRequest request) throws Exception {
        return executeBatchJobFromUploadedFile(request, "param-type-import");
    }

    /** Returns the parameter type with the given id */
    @GetMapping(value = "/parameter-type/{id}", produces = "application/json;charset=UTF-8")
    public ParamTypeVo getParamType(
            @PathVariable("id") Integer id,
            @RequestParam(name = "lang", required = false) String lang) {

        ParamType paramType = templateExecutionService.getParamType(id);
        if (paramType == null) {
            throw new NiordWebException("No such parameter type: " + id, 404);
        }
        ParamTypeVo type = paramType.toVo(DataFilter.get().defaultLanguage(app.getDefaultLanguage()));

        if (StringUtils.isNotBlank(lang) && type instanceof ListParamTypeVo) {
            ListParamTypeVo listType = (ListParamTypeVo)type;
            if  (listType.getValues() != null) {
                listType.getValues().forEach(t -> t.sortDescs(lang));
            }
        }

        return type;
    }

    /** Creates a new parameter type */
    @PostMapping(value = {"/parameter-type", "/parameter-type/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public ParamTypeVo createParamType(@RequestBody ParamTypeVo paramType) throws Exception {
        if (paramType instanceof StandardParamTypeVo
                || TemplateExecutionService.isStandardParamType(paramType.getName())) {
            throw new NiordWebException("Illegal parameter type", 400);
        }
        log.info("Creating parameter type " + paramType);
        ParamType type = paramType.toEntity();
        // The editor's second line behind the parameter dialog; the bootstrap and the import do not refuse
        TemplateExecutionService.refuseRepeatedTemplateParamIds(type);
        return templateExecutionService.createParamType(type)
                .toVo(DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /** Updates an existing parameter type */
    @PutMapping(value = "/parameter-type/{id}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.SYSADMIN)
    public ParamTypeVo updateParamType(@PathVariable("id") Integer id, @RequestBody ParamTypeVo paramType) throws Exception {
        if (!Objects.equals(id, paramType.getId())) {
            throw new NiordWebException(400);
        }
        if (paramType instanceof StandardParamTypeVo
                || TemplateExecutionService.isStandardParamType(paramType.getName())) {
            throw new NiordWebException("Illegal parameter type", 400);
        }

        log.info("Updating parameter type " + paramType);
        ParamType type = paramType.toEntity();
        TemplateExecutionService.refuseRepeatedTemplateParamIds(type);
        return templateExecutionService.updateParamType(type)
                .toVo(DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /**
     * Deletes an existing parameter type: 404 for an unknown id, 409 naming its users when a template category or a
     * composite type uses it
     */
    @DeleteMapping("/parameter-type/{id}")
    @RolesAllowed(Roles.SYSADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteParamType(@PathVariable("id") Integer id) throws Exception {
        // A row of a standard type is one an older deployment's start-up made; it is ignored and never deleted
        if (templateExecutionService.getParamType(id) instanceof StandardParamType) {
            throw new NiordWebException("Illegal parameter type", 400);
        }
        log.info("Deleting parameter type " + id);
        if (!templateExecutionService.deleteParamType(id)) {
            throw new NiordWebException("No such parameter type: " + id, 404);
        }
    }

    /**
     * *******************************************
     * Template Execution
     * *******************************************
     */

    /** Executes a message template category on the given message ID */
    @PutMapping(value = "/execute",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public SystemMessageVo applyTemplate(@RequestBody ExecuteTemplateVo executeTemplate) throws Exception {

        if (!executeTemplate.valid()) {
            throw new NiordWebException("No proper message specified", 400);
        }

        // The parameters are printed unescaped into the templates' field templates, some of them HTML
        if (TemplateExecutionService.containsMarkup(executeTemplate.getTemplateParams())) {
            throw new NiordWebException("Template parameters may not contain markup", 400);
        }

        // Resolve the message to use
        SystemMessageVo message = executeTemplate.getMessage();
        if (message == null) {
            // NB: Access to the message is checked:
            message = messageRestService.getSystemMessage(executeTemplate.getMessageId(), null);
            if (message == null) {
                throw new NiordWebException("No such message: " + executeTemplate.getMessageId(), 404);
            }
        }

        return templateExecutionService.executeTemplate(
                resolveCategory(executeTemplate), message, executeTemplate.getTemplateParams());
    }

    /**
     * Returns the category to execute, or null to execute the message's own template categories.
     * <p>
     * A category definition in the request body is the unsaved state of the category editor's "Test"
     * action, and it names the script resources to run. Only a system administrator, who edits categories
     * and script resources anyway, has it executed as sent. Anyone else gets the stored category with that
     * id, as if the id had been sent alone; a definition without an id is refused. An id that names no
     * stored category is reported, rather than falling back to the message's own template categories.
     */
    private Category resolveCategory(ExecuteTemplateVo executeTemplate) {
        SystemCategoryVo inlineCategory = executeTemplate.getCategory();
        if (inlineCategory != null) {
            if (userService.isCallerInRole(Roles.SYSADMIN)) {
                return new Category(inlineCategory);
            }
            if (inlineCategory.getId() == null) {
                throw new NiordWebException("Only a system administrator may execute an unsaved template", 403);
            }
            return storedCategory(inlineCategory.getId());
        }
        if (executeTemplate.getCategoryId() != null) {
            return storedCategory(executeTemplate.getCategoryId());
        }
        return null;
    }

    /** Returns the stored category with the given id, or 404 */
    private Category storedCategory(Integer id) {
        Category category = categoryService.getCategoryDetails(id);
        if (category == null) {
            throw new NiordWebException("No such category: " + id, 404);
        }
        return category;
    }

    /**
     * *******************************************
     * Field Validation
     * *******************************************
     */

    /** Validate the light character */
    @PostMapping(value = "/validate-light-character",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public NgRemoveValidateResponse validateLightCharacter(@RequestBody NgRemoveValidateParam param) throws Exception {
        NgRemoveValidateResponse response = new NgRemoveValidateResponse();
        response.setValue(param.getValue());
        try {
            LightCharacterParser.getInstance().parse(param.getValue());
            response.setIsValid(true);
        } catch (Exception e) {
            response.setIsValid(false);
        }
        return response;
    }

    /** Validate the call sign */
    @PostMapping(value = "/validate-call-sign",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    public NgRemoveValidateResponse validateCallSign(@RequestBody NgRemoveValidateParam param) throws Exception {
        if (param == null || param.getValue() == null) {
            throw new NiordWebException("No value to validate", 400);
        }
        NgRemoveValidateResponse response = new NgRemoveValidateResponse();
        response.setValue(param.getValue().toUpperCase());
        response.setIsValid(LightCharacterParser.getInstance().telephonyCodeValid(param.getValue()));
        return response;
    }

    /**
     * ******************
     * Helper classes
     * *******************
     */

    /** Encapsulates the parameters used for moving an category to a new parent category */
    @SuppressWarnings("unused")
    public static class ExecuteTemplateVo implements IJsonSerializable {
        SystemMessageVo message;
        String messageId;
        SystemCategoryVo category;
        Integer categoryId;
        List templateParams;

        public boolean valid() {
            return (message != null || StringUtils.isNotBlank(messageId));
        }

        public SystemMessageVo getMessage() {
            return message;
        }

        public void setMessage(SystemMessageVo message) {
            this.message = message;
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }

        public SystemCategoryVo getCategory() {
            return category;
        }

        public void setCategory(SystemCategoryVo category) {
            this.category = category;
        }

        public Integer getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(Integer categoryId) {
            this.categoryId = categoryId;
        }

        public List getTemplateParams() {
            return templateParams;
        }

        public void setTemplateParams(List templateParams) {
            this.templateParams = templateParams;
        }
    }

    /** Parameter class used by the generic ng-remove-validate directive */
    public static class NgRemoveValidateParam implements IJsonSerializable {
        String value;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    /** Response class used by the generic ng-remove-validate directive */
    public static class NgRemoveValidateResponse implements IJsonSerializable {
        String value;
        boolean valid;

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public boolean getIsValid() {
            return valid;
        }

        public void setIsValid(boolean valid) {
            this.valid = valid;
        }
    }
}
