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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code @Setting} injection of a bean created while the database cannot be read yet (an early bean,
 * docs/code-data-config-plan.md §5.2): the field gets the settings file's value, not the annotation default, and ends
 * start-up with the database's value once {@link SettingInjectionBeanPostProcessor#reinjectDeferred} has run.
 */
class SettingInjectionBeanPostProcessorTest {

    /** An early bean, such as SecomCertificateRenewalService */
    static class EarlyBean {
        @org.niord.core.settings.annotation.Setting(value = "fixtureRenewalEnabled", defaultValue = "false",
                type = Setting.Type.Boolean, description = "Renewal")
        Boolean renewalEnabled;

        @org.niord.core.settings.annotation.Setting(value = "fixtureContact", defaultValue = "annotation",
                description = "Contact")
        String contact;
    }

    /** A bean whose Boolean setting defaults to true, such as MessageLuceneIndex */
    static class TrueByDefaultBean {
        @org.niord.core.settings.annotation.Setting(value = "fixtureDeleteOnStartup", defaultValue = "true",
                type = Setting.Type.Boolean, description = "Delete on start-up")
        Boolean deleteOnStartup;
    }

    private final SettingsService svc = mock(SettingsService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<SettingsService> provider = mock(ObjectProvider.class);

    private final SettingInjectionBeanPostProcessor processor = new SettingInjectionBeanPostProcessor(provider);

    @Test
    void aFieldTheDatabaseCannotResolveGetsTheFilesValueAndLaterTheDatabases() {
        when(provider.getIfAvailable()).thenReturn(svc);
        when(svc.getBoolean(any(Setting.class))).thenThrow(new IllegalStateException("EntityManager not ready"));
        when(svc.getString(any(Setting.class))).thenThrow(new IllegalStateException("EntityManager not ready"));
        when(svc.resolveDefault(any(Setting.class))).thenAnswer(call -> switch (((Setting) call.getArgument(0)).getKey()) {
            case "fixtureRenewalEnabled" -> Boolean.TRUE;
            case "fixtureContact" -> "file";
            default -> null;
        });
        EarlyBean bean = new EarlyBean();

        processor.postProcessProperties(null, bean, "earlyBean");

        assertEquals(Boolean.TRUE, bean.renewalEnabled, "the settings file's value, not the annotation default");
        assertEquals("file", bean.contact);

        reset(svc);
        when(svc.getBoolean(any(Setting.class))).thenReturn(Boolean.TRUE);
        when(svc.getString(any(Setting.class))).thenReturn("database");
        processor.reinjectDeferred(svc);

        assertEquals("database", bean.contact, "the database's value once start-up has added the missing settings");
        assertEquals(Boolean.TRUE, bean.renewalEnabled);
    }

    @Test
    void aFieldInjectedBeforeTheServiceExistsGetsTheAnnotationDefaultAndLaterTheDatabases() {
        when(provider.getIfAvailable()).thenReturn(null);
        EarlyBean bean = new EarlyBean();

        processor.postProcessProperties(null, bean, "earlyBean");

        assertEquals(Boolean.FALSE, bean.renewalEnabled, "the annotation default");
        assertEquals("annotation", bean.contact);

        when(svc.getBoolean(any(Setting.class))).thenReturn(Boolean.TRUE);
        when(svc.getString(any(Setting.class))).thenReturn("database");
        processor.reinjectDeferred(svc);

        assertEquals(Boolean.TRUE, bean.renewalEnabled, "true in the database, true in the bean");
        assertEquals("database", bean.contact);
    }

    @Test
    void aFieldTheDatabaseResolvedIsNotInjectedAgain() {
        when(provider.getIfAvailable()).thenReturn(svc);
        when(svc.getBoolean(any(Setting.class))).thenReturn(Boolean.TRUE);
        when(svc.getString(any(Setting.class))).thenReturn("database");
        EarlyBean bean = new EarlyBean();
        processor.postProcessProperties(null, bean, "earlyBean");

        reset(svc);
        processor.reinjectDeferred(svc);

        verify(svc, never()).getString(any(Setting.class));
        assertEquals("database", bean.contact);
    }

    /** SETTINGS-3: a false from the settings service is injected, not replaced with the annotation default */
    @Test
    void aBooleanFieldThatDefaultsToTrueGetsFalseFromTheSettingsService() {
        when(provider.getIfAvailable()).thenReturn(svc);
        when(svc.getBoolean(any(Setting.class))).thenReturn(Boolean.FALSE);
        TrueByDefaultBean bean = new TrueByDefaultBean();

        processor.postProcessProperties(null, bean, "trueByDefaultBean");

        assertEquals(Boolean.FALSE, bean.deleteOnStartup, "the settings service's false, not the annotation default");
    }

    @Test
    void aFieldTheDatabaseCannotResolveAfterStartUpIsNotRecorded() {
        processor.reinjectDeferred(svc);

        // A bean created after start-up while the database is down, such as a prototype batch processor
        when(provider.getIfAvailable()).thenReturn(svc);
        when(svc.getBoolean(any(Setting.class))).thenThrow(new IllegalStateException("database down"));
        when(svc.getString(any(Setting.class))).thenThrow(new IllegalStateException("database down"));
        when(svc.resolveDefault(any(Setting.class))).thenAnswer(call -> switch (((Setting) call.getArgument(0)).getKey()) {
            case "fixtureRenewalEnabled" -> Boolean.TRUE;
            case "fixtureContact" -> "file";
            default -> null;
        });
        EarlyBean bean = new EarlyBean();
        processor.postProcessProperties(null, bean, "laterBean");

        assertEquals("file", bean.contact, "the default resolved without the database");

        reset(svc);
        processor.reinjectDeferred(svc);

        verify(svc, never()).getString(any(Setting.class));
        verify(svc, never()).getBoolean(any(Setting.class));
        assertEquals("file", bean.contact);
    }
}
