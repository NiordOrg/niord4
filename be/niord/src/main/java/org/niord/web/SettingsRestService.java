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

import org.apache.commons.lang3.StringUtils;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsCatalog;
import org.niord.core.settings.SettingsService;
import org.niord.core.user.CurrentUserContext;
import org.niord.core.user.Roles;
import org.niord.core.user.UserService;
import org.niord.model.IJsonSerializable;
import org.niord.s124.S124SwitchCheck;
import org.slf4j.Logger;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * REST interface for accessing areas.
 */
@RestController
@RequestMapping("/rest/settings")
@Transactional
@RolesAllowed(Roles.SYSADMIN)
public class SettingsRestService extends AbstractBatchableRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SettingsRestService.class);

    private final SettingsService settingsService;

    private final UserService userService;

    /** The running properties, which the computed web values are read from */
    private final Environment environment;

    @Inject
    CurrentUserContext currentUserContext;

    SettingsRestService(SettingsService settingsService, UserService userService, Environment environment) {
        this.settingsService = settingsService;
        this.userService = userService;
        this.environment = environment;
    }

    /**
     * Returns the editable settings the code declares: the Settings page and its Export read this list, so an
     * editable row no settings catalog declares is not listed (it stays in the database, §5.6 of
     * docs/code-data-config-plan.md). Whether a key is editable is its catalog entry's answer, not its row's
     * ({@link SettingsService#getAllEditable()}): deployment configuration is not listed, whatever its row says.
     * Security is checked programmatically.
     * Either the user must pass a ticket along, or else, usual security checks applies.
     *
     * The ticket can be requested via Ajax call to /rest/tickets/ticket?role=sysadmin
     *
     * Each setting's description is its catalog entry's, the code's current text, when the entry has one: a row keeps
     * the description it was first written with, so a changed text would otherwise reach only new deployments. The
     * value and type are the row's, and the row is not changed.
     *
     * @return returns the declared editable settings
     */
    @GetMapping(value = "/editable-settings", produces = "application/json;charset=UTF-8")
    @PermitAll // Checked programmatically
    public List<SettingVo> getSettings() {
        // If a ticket is defined, check if programmatically
        if (!userService.isCallerInRole(Roles.SYSADMIN)) {
            log.info("Could not access Settings " + userService.currentUser());
            log.info("Current roles " + currentUserContext.currentUserRoles());
            throw new NiordWebException(403);
        }

        return settingsService
                .getAllEditable()
                .stream()
                .map(this::toEditableVo)
                .collect(Collectors.toList());
    }

    /**
     * Returns the VO of an editable setting with its catalog entry's description, when the entry has one. Only the VO
     * takes the text: the setting is a managed row, which must not change.
     *
     * @param setting the editable setting
     * @return its VO
     */
    private SettingVo toEditableVo(Setting setting) {
        SettingVo vo = new SettingVo(setting);
        SettingsCatalog.Entry entry = settingsService.catalogEntry(setting.getKey());
        if (entry != null && StringUtils.isNotBlank(entry.description())) {
            vo.setDescription(entry.description());
        }
        return vo;
    }

    /**
     * Returns all web settings (settings flagged with web=true) as a flat JSON map.
     *
     * This is anonymous by design: web=true is the backend's own definition of "publicly
     * shippable to the browser", and browser clients read this map at bootstrap time.
     *
     * A String-typed setting is reported with the value the backend runs on: a system property or an
     * environment variable of its name wins over the stored row here, as it does for every reader of the
     * setting inside the backend. That is how a deployment - or a local run - names its {@code mode}
     * for the deployment ribbons of both frontends without editing the database. Other types keep the
     * stored value, because an override is text and each value's JSON type is part of this map.
     *
     * The map also carries two computed values, always present and always JSON booleans:
     * {@code s124Enabled} and {@code secomEnabled}, whether S-124 and SECOM run on this node, read
     * from the running {@code niord.s124.enabled} and {@code niord.secom.enabled} by the rules of the
     * beans they stand for ({@link S124SwitchCheck}). Nothing stores or writes these values, and
     * no row decides them. {@code s124Enabled} is no setting at all. {@code secomEnabled} also has a
     * legacy declaration ({@code SecomAdminRestService}, editable=false, kept because settings are
     * add-only) with a catalogue entry, and a node that runs SECOM has its row; the computed values
     * are put after the stored rows, so such a row is overridden by what the node runs now.
     *
     * @return a flat { key: value } map of all web settings and the computed values
     */
    @GetMapping(value = "/web", produces = "application/json;charset=UTF-8")
    @PermitAll // web settings are public by definition (web=true)
    public Map<String, Object> getWebSettings() {
        Map<String, Object> webSettings = new HashMap<>();
        for (Setting setting : settingsService.getAllForWeb()) {
            // Never expose Password-typed settings (none are web=true today; keep the invariant)
            if (setting.getType() == Setting.Type.Password) {
                continue;
            }
            Object value = setting.getValue();
            if (setting.getType() == Setting.Type.String) {
                String override = settingsService.override(setting.getKey());
                if (override != null) {
                    value = override;
                }
            }
            webSettings.put(setting.getKey(), value);
        }
        webSettings.put("s124Enabled", S124SwitchCheck.s124Enabled(environment));
        webSettings.put("secomEnabled", S124SwitchCheck.secomEnabled(environment));
        return webSettings;
    }

    @GetMapping(value = "/setting/{key}", produces = "application/json")
    public SettingVo getSetting(@PathVariable("key") String key) throws Exception {
        if (StringUtils.isBlank(key)) {
            throw new IllegalArgumentException("Invalid empty key");
        }
        log.info("Getting setting " + key);
        // SECAUD-2: only serve persisted settings. SettingsService.get() consults system properties
        // and environment variables for ANY key before the DB, which let a sysadmin read arbitrary
        // host secrets by key. The VO constructor masks Password-typed
        // values, like the editable-settings list.
        Setting setting = settingsService.getSetting(key.trim());
        if (setting == null) {
            throw new NiordWebException("Unknown setting " + key, 404);
        }
        return new SettingVo(setting);
    }

    /**
     * Updates the value of an operator setting, which takes effect at once. An unknown key (also one with a row no
     * catalog declares), a {@code state} key, a key the catalog does not make editable (deployment configuration,
     * whatever its row says), a key with no row and a value of the wrong type are refused with 400
     * ({@link SettingsService#set(Setting)}).
     *
     * @param key       the setting key
     * @param settingVo the setting, with its new value
     * @return the updated setting
     */
    @PutMapping(value = "/setting/{key}", consumes = "application/json", produces = "application/json")
    public SettingVo updateSetting(@PathVariable("key") String key, @RequestBody SettingVo settingVo) throws Exception {
        if (!Objects.equals(key, settingVo.getKey())) {
            throw new NiordWebException(400);
        }
        Setting setting = settingVo.toEntity();
        log.info("Updating setting " + setting);
        return new SettingVo(settingsService.set(setting));
    }

    /**
     * Imports an uploaded settings json file
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/upload-settings", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "text/plain")
    public String importCharts(MultipartHttpServletRequest request) throws Exception {
        return executeBatchJobFromUploadedFile(request, "settings-import");
    }

    /**
     * ******************
     * Helper classes
     * *******************
     */

    /**
     * VO for settings
     */
    public static class SettingVo implements IJsonSerializable {

        String key;
        Object value;
        String description;
        Setting.Type type;

        /**
         * Constructor
         */
        public SettingVo() {
        }

        /**
         * Constructor
         */
        public SettingVo(Setting setting) {
            key = setting.getKey();
            if (setting.getType() != Setting.Type.Password) {
                value = setting.getValue();
            }
            description = setting.getDescription();
            type = setting.getType();
        }

        /**
         * Converts the VO to an entity
         */
        public Setting toEntity() {
            Setting setting = new Setting();
            setting.setKey(key);
            setting.setValue(value);
            setting.setDescription(description);
            setting.setType(type);
            return setting;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Setting.Type getType() {
            return type;
        }

        public void setType(Setting.Type type) {
            this.type = type;
        }
    }
}
