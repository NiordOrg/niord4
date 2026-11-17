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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.niord.core.defaults.DefaultSettings;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tier-1 tests for how {@link SettingsService} logs the default settings it reads: the value of a setting the
 * catalog declares Password-typed - the WMS token among them - never reaches a log line, whatever type a settings
 * file gives it, the server's {@code ${niord.home}/niord.json} included.
 */
class SettingsServiceLogMaskingTest {

    private static Setting setting(String key, Object value, Setting.Type type) {
        return new Setting(key, value, type, null, true, false, true);
    }

    @Test
    void aPasswordTypedValueIsMasked() {
        Setting token = setting("wmsPassword", "tok-secret", Setting.Type.Password);

        assertEquals(SettingsService.MASKED_VALUE, SettingsService.loggableValue(token, Set.of()));
    }

    @Test
    void anyOtherValueIsLoggedAsItIs() {
        assertEquals("https://niord.example.com",
                SettingsService.loggableValue(setting("baseUri", "https://niord.example.com", null), Set.of()));
        assertEquals("true", SettingsService.loggableValue(setting("wmsProtected", true, null), Set.of()));
        assertEquals("[da, en]", SettingsService.loggableValue(setting("modelLanguages", List.of("da", "en"), null), Set.of()));
        assertEquals("null", SettingsService.loggableValue(setting("empty", null, Setting.Type.String), Set.of()));
    }

    @Test
    void aHomeFileEntryWithoutATypeIsMaskedWhenTheCatalogDeclaresItAPassword() {
        // The catalog declares the type; the deployment's own file gives the value only
        Set<String> passwordKeys = SettingsService.passwordKeys(Set.of("wmsPassword"));
        Setting fromHomeFile = setting("wmsPassword", "tok-secret", null);
        assertEquals(Setting.Type.String, fromHomeFile.getType(), "precondition: stored as a plain String");

        assertEquals(SettingsService.MASKED_VALUE, SettingsService.loggableValue(fromHomeFile, passwordKeys));
        assertEquals("https://wms.example.com",
                SettingsService.loggableValue(setting("wmsProvider", "https://wms.example.com", null), passwordKeys));
    }

    @Test
    void aKeyAnAnnotationDeclaresAPasswordIsMaskedWhateverTheFileSays() {
        SettingsService.registerDeclaredPasswordKey("it-annotated-password");

        Set<String> passwordKeys = SettingsService.passwordKeys(Set.of());

        assertTrue(passwordKeys.contains("it-annotated-password"));
        assertEquals(SettingsService.MASKED_VALUE,
                SettingsService.loggableValue(setting("it-annotated-password", "secret", null), passwordKeys));
    }

    @Test
    void theCatalogsPasswordKeysAreMasked() {
        assertTrue(DefaultSettings.read(getClass().getClassLoader()).passwordKeys().contains("wmsPassword"),
                "the catalog declares wmsPassword Password-typed");
    }

    @Test
    void theConstructorLogsNoPasswordValue() {
        // The test class path's root niord.json, read as the country's settings file, holds a wmsPassword value
        Logger logger = (Logger) LoggerFactory.getLogger(SettingsService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            SettingsService service = new SettingsService(mock(SettingsCache.class), mock(ListableBeanFactory.class));

            Object value = service.resolveDefault(new Setting("wmsPassword"));
            assertEquals("TO_BE_DEFINED", value, "precondition: the test niord.json has the token");
            assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage()
                    .equals("Default setting wmsPassword = " + SettingsService.MASKED_VALUE + " (root)")),
                    "the value is masked in the load log");
            assertFalse(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(m -> m.contains("wmsPassword") && m.contains(String.valueOf(value))));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
