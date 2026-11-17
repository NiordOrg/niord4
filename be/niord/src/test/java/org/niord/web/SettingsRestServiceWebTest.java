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
package org.niord.web;

import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.niord.core.user.UserService;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@code GET /rest/settings/web} answers for a setting a system property or an environment variable
 * overrides: the value the backend itself runs on, for a String-typed setting. The deployment ribbons of
 * both frontends read {@code mode} from this map, so a mode named at start-up must reach it. And the
 * two values the map computes from the running properties, {@code s124Enabled} and {@code secomEnabled}.
 */
class SettingsRestServiceWebTest {

    private static Setting web(String key, Object value, Setting.Type type) {
        return new Setting(key, value).type(type).web(true);
    }

    private static Map<String, Object> webSettings(SettingsService settings) {
        return webSettings(settings, new MockEnvironment());
    }

    private static Map<String, Object> webSettings(SettingsService settings, MockEnvironment environment) {
        return new SettingsRestService(settings, mock(UserService.class), environment).getWebSettings();
    }

    @Test
    void anOverriddenStringSettingIsReportedWithTheValueTheBackendRunsOn() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getAllForWeb()).thenReturn(List.of(
                web("mode", "production", Setting.Type.String),
                web("infoDefaultLanguage", "da", Setting.Type.String)));
        when(settings.override("mode")).thenReturn("development");

        Map<String, Object> answer = webSettings(settings);

        assertEquals("development", answer.get("mode"));
        assertEquals("da", answer.get("infoDefaultLanguage"), "a setting nothing overrides keeps its row");
    }

    @Test
    void anOverrideNeverChangesTheJsonTypeOfAValue() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getAllForWeb()).thenReturn(List.of(
                web("infoSiteLanguages", List.of("kl", "da", "en"), Setting.Type.json),
                web("wmsLayerEnabled", true, Setting.Type.Boolean)));
        when(settings.override(anyString())).thenReturn("text");

        Map<String, Object> answer = webSettings(settings);

        assertEquals(List.of("kl", "da", "en"), answer.get("infoSiteLanguages"));
        assertEquals(true, answer.get("wmsLayerEnabled"));
    }

    @Test
    void aPasswordIsNeverReportedEvenWhenOverridden() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getAllForWeb()).thenReturn(List.of(web("wmsPassword", "secret", Setting.Type.Password)));
        when(settings.override("wmsPassword")).thenReturn("other");

        Map<String, Object> answer = webSettings(settings);

        assertFalse(answer.containsKey("wmsPassword"));
    }

    @Test
    void anOverrideIsASystemPropertyBeforeAnEnvironmentVariable() {
        SettingsService settings = mock(SettingsService.class, CALLS_REAL_METHODS);
        String key = "niordSettingsOverrideTest" + System.nanoTime();
        assertNull(settings.override(key));
        System.setProperty(key, "from-a-system-property");
        try {
            assertEquals("from-a-system-property", settings.override(key));
        } finally {
            System.clearProperty(key);
        }
        // PATH is set in every environment the build runs in
        assertEquals(System.getenv("PATH"), settings.override("PATH"));
    }

    @Test
    void theS124AndSecomSwitchesAreComputedFromTheRunningProperties() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getAllForWeb()).thenReturn(List.of());

        Map<String, Object> byDefault = webSettings(settings, new MockEnvironment());
        assertEquals(true, byDefault.get("s124Enabled"), "S-124 is on when niord.s124.enabled is missing");
        assertEquals(false, byDefault.get("secomEnabled"), "SECOM is off when niord.secom.enabled is missing");

        Map<String, Object> s124Off = webSettings(settings,
                new MockEnvironment().withProperty("niord.s124.enabled", "false"));
        assertEquals(false, s124Off.get("s124Enabled"));
        assertEquals(false, s124Off.get("secomEnabled"));

        Map<String, Object> secomOn = webSettings(settings,
                new MockEnvironment().withProperty("niord.secom.enabled", "true"));
        assertEquals(true, secomOn.get("s124Enabled"));
        assertEquals(true, secomOn.get("secomEnabled"));
    }

    @Test
    void aStoredSecomEnabledRowIsOverriddenByTheComputedValue() {
        // A node that once ran SECOM keeps its row (add-only); the answer follows what runs now
        SettingsService settings = mock(SettingsService.class);
        when(settings.getAllForWeb()).thenReturn(List.of(web("secomEnabled", true, Setting.Type.Boolean)));

        Map<String, Object> answer = webSettings(settings, new MockEnvironment());

        assertEquals(false, answer.get("secomEnabled"));
    }

    @Test
    void theComputedValuesAreBooleans() {
        SettingsService settings = mock(SettingsService.class);
        when(settings.getAllForWeb()).thenReturn(List.of());

        Map<String, Object> answer = webSettings(settings,
                new MockEnvironment().withProperty("niord.s124.enabled", "TRUE"));

        assertInstanceOf(Boolean.class, answer.get("s124Enabled"), "a JSON boolean, never text");
        assertInstanceOf(Boolean.class, answer.get("secomEnabled"), "a JSON boolean, never text");
    }
}
