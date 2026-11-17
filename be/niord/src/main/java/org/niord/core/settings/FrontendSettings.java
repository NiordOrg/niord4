/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.core.settings;

import java.util.List;

/**
 * The settings only the frontends read, from {@code GET /rest/settings/web}. No backend code reads them; they are
 * declared here so that the settings catalog knows them (docs/code-data-config-plan.md §5.1). Each declaration
 * carries the type, flags and description the settings file gives the setting.
 */
public final class FrontendSettings {

    public static final Setting SITE_LANGUAGES =
            new Setting("siteLanguages", List.of("en"))
                    .description("The languages of the website")
                    .web(true)
                    .editable(true);

    public static final Setting INFO_SITE_LANGUAGES =
            new Setting("infoSiteLanguages", List.of("en"))
                    .description("The languages of the public niord-info website")
                    .web(true)
                    .editable(true);

    public static final Setting INFO_DEFAULT_LANGUAGE =
            new Setting("infoDefaultLanguage", "en")
                    .description("The default language of the public niord-info website")
                    .web(true)
                    .editable(true);

    public static final Setting DOCUMENTATION_URL =
            new Setting("documentationUrl", "https://docs.niord.org")
                    .description("The URL template to the documentation site")
                    .web(true)
                    .editable(true);

    public static final Setting STD_TEMPLATE_FIELDS =
            new Setting("stdTemplateFields", List.of("type", "promulgation", "id", "publish_date", "areas",
                    "positions", "event_dates", "work_vessel", "markings"))
                    .description("The possible set of standard message fields to display when executing a template")
                    .web(true)
                    .editable(true);

    public static final Setting MAP_DEFAULT_ZOOM_LEVEL =
            new Setting("mapDefaultZoomLevel", 6)
                    .description("Default zoom level to use for maps")
                    .web(true)
                    .editable(true);

    public static final Setting MAP_DEFAULT_LATITUDE =
            new Setting("mapDefaultLatitude", 56)
                    .description("Default latitude to use for maps")
                    .web(true)
                    .editable(true);

    public static final Setting MAP_DEFAULT_LONGITUDE =
            new Setting("mapDefaultLongitude", 11)
                    .description("Default longitude to use for maps")
                    .web(true)
                    .editable(true);

    public static final Setting WMS_LAYER_ENABLED =
            new Setting("wmsLayerEnabled", false)
                    .description("Whether or not to display the WMS layer in maps")
                    .web(true)
                    .editable(true);

    public static final Setting OPEN_SEA_MAP_LAYER_ENABLED =
            new Setting("openSeaMapLayerEnabled", false)
                    .description("Whether or not to display the OpenSeaMap layer in maps")
                    .web(true)
                    .editable(true);

    // The gate of the administration app's Integration section (fe/ui-apps/admin/src/app/features/admin/section-descriptors.ts)
    public static final Setting ADMIN_INTEGRATION_PAGE_ENABLED =
            new Setting("adminIntegrationPageEnabled", false)
                    .description("Shows the Integration section of the administration app")
                    .web(true)
                    .editable(true);

    private FrontendSettings() {
    }
}
