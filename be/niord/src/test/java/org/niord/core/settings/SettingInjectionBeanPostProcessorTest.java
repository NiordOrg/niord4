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

import java.util.Set;

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

    /**
     * A bean created after start-up while the database cannot be read, as the sea-chart servlet is at its first
     * request, gets the default, and the next refresh, the poll's every 30 seconds whatever the change count says,
     * gives it the database's value, also for a key no save refreshes; that is not a change of the setting. FAILS
     * without the reading again: the field keeps the default until its key is saved or the server restarts.
     */
    @Test
    void aFieldInjectedWithoutTheDatabaseAfterStartUpGetsTheDatabasesValueAtTheNextRefresh() {
        processor.reinjectDeferred(svc);
        when(provider.getIfAvailable()).thenReturn(svc);
        when(svc.getBoolean(any(Setting.class))).thenThrow(new IllegalStateException("database down"));
        when(svc.getString(any(Setting.class))).thenThrow(new IllegalStateException("database down"));
        when(svc.resolveDefault(any(Setting.class))).thenReturn(null);
        EarlyBean bean = new EarlyBean();
        processor.postProcessProperties(null, bean, "laterBean");
        assertEquals("annotation", bean.contact, "precondition: the default");

        reset(svc);
        when(svc.getBoolean(any(Setting.class))).thenReturn(Boolean.TRUE);
        when(svc.getString(any(Setting.class))).thenReturn("database");
        SettingInjectionBeanPostProcessor.Refreshed refreshed = processor.refresh(svc, key -> false);

        assertEquals("database", bean.contact, "the database's value at the next refresh");
        assertEquals(Boolean.TRUE, bean.renewalEnabled);
        assertEquals(Set.of(), refreshed.changed(), "the setting did not change");

        reset(svc);
        processor.refresh(svc, key -> false);
        verify(svc, never()).getString(any(Setting.class));
    }

    /**
     * A refresh that cannot read a setting, the database failing for a moment after a save, leaves the field as it
     * is, and the next refresh reads it again whatever keys it is asked for, the poll's included, and reports the
     * change. FAILS without the reading again: the field keeps the old value until the key is saved again or the
     * server restarts, for the poll finds the change count it has seen.
     */
    @Test
    void aFieldARefreshCouldNotReadIsReadAgainByTheNextRefresh() {
        when(provider.getIfAvailable()).thenReturn(svc);
        when(svc.getBoolean(any(Setting.class))).thenReturn(Boolean.TRUE);
        when(svc.getString(any(Setting.class))).thenReturn("database");
        EarlyBean bean = new EarlyBean();
        processor.postProcessProperties(null, bean, "earlyBean");
        SettingsCatalog.Entry editable = mock(SettingsCatalog.Entry.class);
        when(editable.editable()).thenReturn(true);
        when(svc.catalogEntry(any())).thenReturn(editable);

        when(svc.getString(any(Setting.class))).thenThrow(new IllegalStateException("connection lost"));
        SettingInjectionBeanPostProcessor.Refreshed failed = processor.refresh(svc, "fixtureContact"::equals);
        assertEquals("database", bean.contact, "precondition: the field keeps its value");
        assertEquals(Set.of("fixtureContact"), failed.failed());

        reset(svc);
        when(svc.catalogEntry(any())).thenReturn(editable);
        when(svc.getString(any(Setting.class))).thenReturn("saved");
        SettingInjectionBeanPostProcessor.Refreshed again = processor.refresh(svc, key -> false);

        assertEquals("saved", bean.contact, "the next refresh read it again");
        assertEquals(Set.of("fixtureContact"), again.changed(), "and reports the change");
        assertEquals(Set.of(), again.failed());
    }
}
