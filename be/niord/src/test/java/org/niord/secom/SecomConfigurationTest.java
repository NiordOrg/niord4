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
package org.niord.secom;

import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SecomConfiguration#booleanSetting}: the crypto knobs
 * ({@code secomValidateClientChain}, {@code secomRevocationCheck}) are unboxed into primitive
 * filter parameters, and {@code SettingsService.get} surfaces system-property/env overrides as
 * raw strings. The helper must therefore read string overrides ({@code SettingsService.toBoolean})
 * and keep the template default for anything unparseable, so a typo can never silently disable
 * a security check (and an override can never NPE the SECOM startup).
 */
class SecomConfigurationTest {

    /** Template defaulting to true, like the two crypto knobs */
    private static final Setting TRUE_DEFAULT = SecomConfiguration.VALIDATE_CLIENT_CHAIN;

    /** Template defaulting to false, for the default-fallback contrast */
    private static final Setting FALSE_DEFAULT = new Setting("secomTestFlag", Boolean.FALSE,
            Setting.Type.Boolean, "test", true, false, true);

    private SettingsService settingsWith(Object value) {
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.get(any(Setting.class))).thenReturn(value);
        return settingsService;
    }

    @Test
    void persistedBooleanValuesAreHonoured() {
        assertTrue(SecomConfiguration.booleanSetting(settingsWith(Boolean.TRUE), TRUE_DEFAULT));
        assertFalse(SecomConfiguration.booleanSetting(settingsWith(Boolean.FALSE), TRUE_DEFAULT));
    }

    @Test
    void stringFalseOverrideDisablesTheCheck() {
        // The regression this pins: a system/env override "false" made getBoolean return null,
        // which NPE'd on unboxing during SECOM startup instead of disabling the check
        assertFalse(SecomConfiguration.booleanSetting(settingsWith("false"), TRUE_DEFAULT));
        assertFalse(SecomConfiguration.booleanSetting(settingsWith("FALSE"), TRUE_DEFAULT));
        assertFalse(SecomConfiguration.booleanSetting(settingsWith(" no "), TRUE_DEFAULT));
        assertFalse(SecomConfiguration.booleanSetting(settingsWith("n"), TRUE_DEFAULT));
        assertFalse(SecomConfiguration.booleanSetting(settingsWith("f"), TRUE_DEFAULT));
    }

    @Test
    void stringTrueOverrideEnablesTheCheck() {
        // Same true-ish tokens SettingsService.getBoolean recognizes
        assertTrue(SecomConfiguration.booleanSetting(settingsWith("true"), FALSE_DEFAULT));
        assertTrue(SecomConfiguration.booleanSetting(settingsWith("YES"), FALSE_DEFAULT));
        assertTrue(SecomConfiguration.booleanSetting(settingsWith("y"), FALSE_DEFAULT));
        assertTrue(SecomConfiguration.booleanSetting(settingsWith("t"), FALSE_DEFAULT));
    }

    @Test
    void unparseableValuesKeepTheTemplateDefault() {
        // Fail-secure: a typo must not silently disable a security check that defaults to on
        assertTrue(SecomConfiguration.booleanSetting(settingsWith("flase"), TRUE_DEFAULT));
        assertTrue(SecomConfiguration.booleanSetting(settingsWith(""), TRUE_DEFAULT));
        assertTrue(SecomConfiguration.booleanSetting(settingsWith(null), TRUE_DEFAULT));

        assertFalse(SecomConfiguration.booleanSetting(settingsWith("flase"), FALSE_DEFAULT));
        assertFalse(SecomConfiguration.booleanSetting(settingsWith(null), FALSE_DEFAULT));
    }
}
