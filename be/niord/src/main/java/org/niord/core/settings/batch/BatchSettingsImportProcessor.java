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
package org.niord.core.settings.batch;

import org.springframework.stereotype.Component;

import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsCatalog;
import org.niord.core.settings.SettingsService;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Objects;

/**
 * Filters the imported settings down to those whose value changes (docs/code-data-config-plan.md §5.3). The import
 * never creates a row: a key no catalog declares, a {@code state} key, a key the catalog does not make editable
 * (deployment configuration, which an export made before it left the Settings page still holds), a key this
 * deployment has no row for and a value that does not fit the row's type (else the catalog's) are skipped, each with
 * a line in the job's log. The
 * row's stored value is compared with the item's, not the value a read would expand.
 */
@Component
@org.springframework.context.annotation.Scope("prototype")
@Named("batchSettingsImportProcessor")
public class BatchSettingsImportProcessor extends AbstractItemHandler {

    @Inject
    SettingsService settingsService;

    /** {@inheritDoc} **/
    @Override
    public Object processItem(Object item) throws Exception {

        Setting setting = (Setting) item;
        String key = setting.getKey();

        SettingsCatalog.Entry entry = settingsService.catalogEntry(key);
        if (entry == null) {
            getLog().info("Skipped " + key + ": no catalog declares it");
            return null;
        }
        if (entry.scope() == Setting.Scope.state) {
            getLog().info("Skipped " + key + ": a state setting, which only the system writes");
            return null;
        }
        if (!entry.editable()) {
            getLog().info("Skipped " + key + ": deployment configuration, which the deployment sets");
            return null;
        }
        Setting original = settingsService.getSetting(key);
        if (original == null) {
            getLog().info("Skipped " + key + ": this deployment has no such setting");
            return null;
        }
        Setting.Type type = original.getType() != null ? original.getType() : entry.type();
        if (type != null && !SettingsCatalog.hasType(type, setting.getValue())) {
            getLog().info("Skipped " + key + ": " + type + "-typed, takes " + SettingsCatalog.shape(type));
            return null;
        }

        if (entry.required() && SettingsService.isBlank(setting.getValue())) {
            getLog().info("Skipped " + key + ": required, and the file gives no value");
            return null;
        }

        if (!Objects.equals(original.getValue(), setting.getValue())) {

            // When you create exports from the Settings Admin page, passwords will have no value.
            // So, if this setting is an empty password, ignore it. The type is the row's (else the catalog's),
            // never the file's: an entry without a type must not clear a password either.
            if (type == Setting.Type.Password && setting.getValue() == null) {
                return null;
            }

            // Update the setting value
            getLog().info("Update setting value for " + key);
            return setting;
        }

        return null;
    }
}
