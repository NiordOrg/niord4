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
package org.niord.core;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Common settings and functionality for the Niord app
 */
@Component
@SuppressWarnings("unused")
public class NiordApp {

    // The possible execution modes of Niord
    public enum ExecutionMode { DEVELOPMENT, TEST, PRODUCTION }

    // Required, with no code default: the base product's settings file gives the generic deployable a neutral
    // value and every country's settings file sets its own
    private static final Setting ORGANISATION =
            new Setting("organisation", null)
                    .type(Setting.Type.String)
                    .description("The organisation of the service")
                    .editable(true)
                    .required(true);

    private static final Setting BASE_URI =
            new Setting("baseUri", "http://localhost:8080")
                    .description("The base application server URI")
                    .editable(true);

    private static final Setting ADMIN_APP_URL =
            new Setting("adminAppUrl", "")
                    .description("URL of the administration web app. Message links in mails and tweets point here, "
                            + "and the backend redirects its root (/) here. Empty: the links use baseUri.")
                    .editable(true);

    // Required, with no code default, as the organisation; S-124 takes the warnings' nationality, ids and MRN from it
    private static final Setting COUNTRY =
            new Setting("country", null)
                    .type(Setting.Type.String)
                    .description("The country")
                    .editable(true)
                    .required(true);

    // PG5: SettingsService reads the settings files in its constructor and adds the keys a database lacks in
    // init(), at HIGHEST_PRECEDENCE, before any other ApplicationReadyEvent listener reads a setting. A read
    // before that (e.g. a @PostConstruct reader) resolves to the settings file's value and writes nothing. This
    // non-null ["en"] default is what a key no settings file sets resolves to; it matches the English fallback
    // in getLanguages().
    private static final Setting LANGUAGES =
            new Setting("modelLanguages", List.of("en"))
                    .description("The default message model languages")
                    .web(true)
                    .editable(true);

    // Both frontends label every deployment whose mode is not 'production' with a ribbon, the public
    // site in front of mariners. Until 2026-11-04 the settings file therefore said 'production', so that a
    // database nobody configured read as production; the owner ruled the other way round (decision 146): a
    // forgotten setting shows a TEST ribbon rather than a test site passing as production. The settings file
    // (defaults/base/settings.json, or a country's) says 'test', and a production deployment sets
    // mode=production by environment, a system property or environment variable named like the key, which
    // SettingsService honours ahead of the stored row; a local run overrides it the same way (start-local.sh).
    private static final Setting EXECUTION_MODE =
            new Setting("mode", "production")
                    .description("The Niord execution mode, either 'development', 'test' or 'production'.")
                    .editable(true)
                    .web(true);

    private static final Setting DEFAULT_LANGUAGE =
            new Setting("defaultLanguage", "en")
                    .description("The default UI and model text language")
                    .web(true)
                    .editable(true);

    private static final Logger log = LoggerFactory.getLogger(NiordApp.class);

    private final SettingsService settingsService;

    NiordApp(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    // PORT: the Quarkus launcher entry point `int run(String...)` (QuarkusApplication)
    // was removed; the Spring Boot application class lives in the deployable module (niord-app, or a country's).

    /** Returns the build version **/
    public String getBuildVersion() {
        // The "version.properties" file is updated by maven with the build timestamp.
        try (InputStream in = getClass().getResourceAsStream("/version.properties")) {
            Properties versionProperties = new Properties();
            versionProperties.load(in);
            return versionProperties.getProperty("niord.version");
        } catch (IOException ignored) {
        }
        return "undefined";
    }

    /**
     * Returns the organisation responsible for this application
     * @return the organisation responsible for this application
     */
    public String getOrganisation() {
        return settingsService.getString(ORGANISATION);
    }

    /**
     * Returns the base URI used to access this application
     * @return the base URI used to access this application
     */
    public String getBaseUri() {
        return settingsService.getString(BASE_URI);
    }

    /**
     * Returns the URL of the administration web app, empty when none is configured
     * @return the URL of the administration web app
     */
    public String getAdminAppUrl() {
        return settingsService.getString(ADMIN_APP_URL);
    }

    /**
     * Returns the base URI that message links point at: the URL of the administration web app without
     * trailing slashes, or the base URI when no administration web app URL is configured
     * @return the base URI that message links point at
     */
    public String getMessageLinkBaseUri() {
        String adminAppUrl = StringUtils.stripEnd(StringUtils.trim(getAdminAppUrl()), "/");
        return StringUtils.isBlank(adminAppUrl) ? getBaseUri() : adminAppUrl;
    }

    /**
     * Returns the country
     * @return the country
     */
    public String getCountry() {
        return settingsService.getString(COUNTRY);
    }

    /**
     * Returns the list of languages supported by the MSI-NM application
     * @return the list of languages supported by the MSI-NM application
     */
    public String[] getLanguages() {
        @SuppressWarnings("unchecked")
        List<String> languages = (List<String>)settingsService.get(LANGUAGES);
        return (languages == null || languages.size() == 0)
                ? new String[]{"en"}
                : languages.toArray(new String[languages.size()]);
    }

    /**
     * Returns the list of languages supported by the MSI-NM application with the given language first
     * @return the list of languages supported by the MSI-NM application the given language first
     */
    public String[] getLanguages(String lang) {
        lang = getLanguage(lang);
        String[] languages = getLanguages();
        for (int x = 1; x < languages.length; x++) {
            if (languages[x].equals(lang)) {
                String temp = languages[0];
                languages[0] = languages[x];
                languages[x] = temp;
            }
        }
        return languages;
    }

    /**
     * Returns the default language
     * @return the default language
     */
    public String getDefaultLanguage() {
        String[] languages = getLanguages();
        String configured = settingsService.getString(DEFAULT_LANGUAGE);
        for (String supported : languages) {
            if (supported.equalsIgnoreCase(configured)) return supported;
        }
        return languages[0];
    }

    /**
     * Ensures that the given language is a supported language and
     * returns the default language if not
     * @param lang the language to check
     * @return the language if supported, otherwise the default language
     */
    public String getLanguage(String lang) {
        for (String l : getLanguages()) {
            if (l.equalsIgnoreCase(lang)) {
                return l;
            }
        }
        return getDefaultLanguage();
    }

    /**
     * Ensures that the given language is a supported language and
     * returns the default locale if not
     * @param lang the language to check
     * @return the associated locale if supported, otherwise the default locale
     */
    public Locale getLocale(String lang) {
        return new Locale(getLanguage(lang));
    }

    /**
     * Returns the execution mode of the Niord system
     * @return the execution mode of the Niord system
     */
    public ExecutionMode getExecutionMode() {
        String mode = settingsService.getString(EXECUTION_MODE);
        try {
            return ExecutionMode.valueOf(mode.toUpperCase());
        } catch (Exception ignored) {
        }
        // An unreadable mode is no reason to label the deployment as something it may not be
        return ExecutionMode.PRODUCTION;
    }
}
