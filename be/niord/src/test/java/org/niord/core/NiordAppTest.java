package org.niord.core;

import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class NiordAppTest {
    @Test
    void defaultLanguageUsesTheCachedWebSettingIndependentlyOfModelOrder() {
        var settings = mock(SettingsService.class);
        when(settings.get(any(Setting.class))).thenReturn(List.of("en", "kl", "da"));
        when(settings.getString(any(Setting.class))).thenReturn("da");
        assertEquals("da", new NiordApp(settings).getDefaultLanguage());

        var setting = ArgumentCaptor.forClass(Setting.class);
        verify(settings).getString(setting.capture());
        assertEquals("defaultLanguage", setting.getValue().getKey());
        assertEquals("en", setting.getValue().getValue());
        assertTrue(setting.getValue().isCached());
        assertTrue(setting.getValue().isWeb());
        assertTrue(setting.getValue().isEditable());
        verify(settings, never()).peek(anyString());
    }

    @Test
    void unsupportedDefaultUsesTheFirstModelLanguage() {
        var settings = mock(SettingsService.class);
        when(settings.get(any(Setting.class))).thenReturn(List.of("da", "en", "kl"));
        when(settings.getString(any(Setting.class))).thenReturn("fr");
        assertEquals("da", new NiordApp(settings).getDefaultLanguage());
    }

    @Test
    void adminAppUrlIsAnEditableServerSideSettingReturnedAsConfigured() {
        var settings = linkSettings("https://admin.example.org/", "https://niord.example.org");
        assertEquals("https://admin.example.org/", new NiordApp(settings).getAdminAppUrl());

        var setting = ArgumentCaptor.forClass(Setting.class);
        verify(settings).getString(setting.capture());
        assertEquals("adminAppUrl", setting.getValue().getKey());
        assertEquals("", setting.getValue().getValue());
        assertTrue(setting.getValue().isEditable());
        assertFalse(setting.getValue().isWeb());
    }

    @Test
    void messageLinksPointAtTheAdminAppWithoutTrailingSlashes() {
        var app = new NiordApp(linkSettings(" https://admin.example.org// ", "https://niord.example.org"));
        assertEquals("https://admin.example.org", app.getMessageLinkBaseUri());
    }

    @Test
    void messageLinksUseTheBaseUriWhenNoAdminAppIsConfigured() {
        for (String adminAppUrl : new String[]{null, "", "  ", "/"}) {
            var app = new NiordApp(linkSettings(adminAppUrl, "https://niord.example.org"));
            assertEquals("https://niord.example.org", app.getMessageLinkBaseUri(),
                    "adminAppUrl '" + adminAppUrl + "' configures no administration web app");
        }
    }

    @Test
    void anUnconfiguredModeIsProductionAndShippedToTheBrowser() {
        // SettingsService.get persists the declared default when no row exists, so the default is
        // what a database nobody configured reads from then on
        var settings = mock(SettingsService.class);
        when(settings.getString(any(Setting.class))).thenAnswer(invocation ->
                String.valueOf(invocation.<Setting>getArgument(0).getValue()));
        assertEquals(NiordApp.ExecutionMode.PRODUCTION, new NiordApp(settings).getExecutionMode());

        var setting = ArgumentCaptor.forClass(Setting.class);
        verify(settings).getString(setting.capture());
        assertEquals("mode", setting.getValue().getKey());
        assertEquals("production", setting.getValue().getValue());
        assertTrue(setting.getValue().isWeb(), "both frontends read the mode from /rest/settings/web");
        assertTrue(setting.getValue().isEditable());
    }

    @Test
    void theConfiguredModeIsReadWhateverItsCase() {
        for (var mode : new String[][]{{"development", "DEVELOPMENT"}, {"Test", "TEST"}, {"PRODUCTION", "PRODUCTION"}}) {
            var settings = mock(SettingsService.class);
            when(settings.getString(any(Setting.class))).thenReturn(mode[0]);
            assertEquals(NiordApp.ExecutionMode.valueOf(mode[1]), new NiordApp(settings).getExecutionMode());
        }
    }

    @Test
    void anUnreadableModeIsProduction() {
        for (String mode : new String[]{null, "", "staging", "dev"}) {
            var settings = mock(SettingsService.class);
            when(settings.getString(any(Setting.class))).thenReturn(mode);
            assertEquals(NiordApp.ExecutionMode.PRODUCTION, new NiordApp(settings).getExecutionMode(),
                    "mode '" + mode + "' names no execution mode");
        }
    }

    /** Settings answering adminAppUrl and baseUri with the given values */
    private static SettingsService linkSettings(String adminAppUrl, String baseUri) {
        var settings = mock(SettingsService.class);
        when(settings.getString(any(Setting.class))).thenAnswer(invocation ->
                switch (invocation.<Setting>getArgument(0).getKey()) {
                    case "adminAppUrl" -> adminAppUrl;
                    case "baseUri" -> baseUri;
                    default -> null;
                });
        return settings;
    }
}
