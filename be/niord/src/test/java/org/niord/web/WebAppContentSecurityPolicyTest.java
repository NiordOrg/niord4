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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebAppContentSecurityPolicy}, the administration web app's Content-Security-Policy.
 *
 * <p>Pins the exact policy for the defaults, that it follows the tile settings (and is the same instance while
 * they are unchanged), how a tile host is read from a setting's value — scheme-relative, an OpenLayers range,
 * a port, a relative value — and that no value can add a directive or a source it does not name; a value that
 * gives no source is logged with its cause. The Keycloak origin comes from the token issuer.
 *
 * <p>The settings service is a mock; no database.
 */
public class WebAppContentSecurityPolicyTest {

    private static final String ISSUER = "http://localhost:8080/auth/realms/niord";

    private final SettingsService settings = mock(SettingsService.class);

    private void tiles(String osmUrl, String openSeaMapUrl) {
        when(settings.peek("osmUrl")).thenReturn(osmUrl);
        when(settings.peek("openSeaMapUrl")).thenReturn(openSeaMapUrl);
    }

    /** Returns the sources of the directive, without its name */
    private static String directive(String policy, String name) {
        return Arrays.stream(policy.split("; "))
                .filter(directive -> directive.startsWith(name + " "))
                .findFirst()
                .map(directive -> directive.substring(name.length() + 1))
                .orElseThrow(() -> new AssertionError("no " + name + " in " + policy));
    }

    private static String imgSrc(String policy) {
        return directive(policy, "img-src");
    }

    // ------------------------------------------------------------------------------------------- //
    // The policy
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void theDefaultsGiveTheWebMapsDefaultTileHosts() {
        tiles(null, "  ");

        assertEquals("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';"
                        + " img-src 'self' data: blob: https://tile.openstreetmap.org https://t1.openseamap.org;"
                        + " font-src 'self'; connect-src 'self' http://localhost:8080;"
                        + " frame-src 'self' http://localhost:8080; object-src 'none'; base-uri 'self';"
                        + " form-action 'self'; frame-ancestors 'self'",
                new WebAppContentSecurityPolicy(() -> settings, ISSUER).current());
    }

    @Test
    public void followsATileSettingChange() {
        tiles("https://tiles.example.test/{z}/{x}/{y}.png", null);
        WebAppContentSecurityPolicy policy = new WebAppContentSecurityPolicy(() -> settings, ISSUER);

        String first = policy.current();
        assertSame(first, policy.current(), "an unchanged setting must give the same policy, not a rebuilt one");
        assertEquals("'self' data: blob: https://tiles.example.test https://t1.openseamap.org", imgSrc(first));

        tiles("https://other.example.test/{z}/{x}/{y}.png", null);
        String second = policy.current();
        assertNotSame(first, second);
        assertTrue(imgSrc(second).contains("https://other.example.test"), second);
        assertFalse(second.contains("tiles.example.test"), "the old tile host must be gone: " + second);
        assertSame(second, policy.current());
    }

    @Test
    public void readsTheSettingsUncachedAndNeverStoresARow() {
        tiles(null, null);
        new WebAppContentSecurityPolicy(() -> settings, ISSUER).current();

        verify(settings, never()).get(anyString());
        verify(settings, never()).get(any(Setting.class));
        verify(settings, never()).getString(anyString());
    }

    @Test
    public void neverReadsTheWmsSettings() {
        when(settings.peek("wmsProvider")).thenReturn("https://wms.example.test");
        when(settings.peek("wmsUrl")).thenReturn("https://wms.example.test/wms");
        tiles(null, null);

        String policy = new WebAppContentSecurityPolicy(() -> settings, ISSUER).current();

        assertFalse(policy.contains("wms.example.test"), policy);
        verify(settings, never()).peek("wmsProvider");
        verify(settings, never()).peek("wmsUrl");
    }

    @Test
    public void sendsTheLastPolicyWhenTheSettingsCannotBeRead() {
        tiles("https://tiles.example.test/{z}/{x}/{y}.png", null);
        WebAppContentSecurityPolicy policy = new WebAppContentSecurityPolicy(() -> settings, ISSUER);
        String last = policy.current();

        when(settings.peek("osmUrl")).thenThrow(new IllegalStateException("database down"));

        assertSame(last, policy.current());
    }

    @Test
    public void sendsTheDefaultsPolicyWhenTheSettingsCannotBeReadAtFirst() {
        when(settings.peek(anyString())).thenThrow(new IllegalStateException("database down"));

        String policy = new WebAppContentSecurityPolicy(() -> settings, ISSUER).current();

        assertEquals("'self' data: blob: https://tile.openstreetmap.org https://t1.openseamap.org", imgSrc(policy));
    }

    // ------------------------------------------------------------------------------------------- //
    // Tile hosts
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void aSchemeRelativeValueGivesTheHostAlone() {
        tiles("//tiles.example.test/{z}/{x}/{y}.png", null);
        assertEquals("'self' data: blob: tiles.example.test https://t1.openseamap.org",
                imgSrc(new WebAppContentSecurityPolicy(() -> settings, ISSUER).current()));
    }

    @Test
    public void anOpenLayersRangeBecomesAWildcard() {
        tiles("https://{a-c}.tile.example.org/{z}/{x}/{y}.png", "https://t{1-4}.seamarks.example.org/{z}/{x}/{y}.png");
        assertEquals("'self' data: blob: https://*.tile.example.org https://*.seamarks.example.org",
                imgSrc(new WebAppContentSecurityPolicy(() -> settings, ISSUER).current()));
    }

    @Test
    public void keepsThePortAndLowerCasesTheHost() {
        tiles("http://Tiles.Example.TEST:8081/{z}/{x}/{y}.png", "http://[::1]:9000/{z}/{x}/{y}.png");
        assertEquals("'self' data: blob: http://tiles.example.test:8081 http://[::1]:9000",
                imgSrc(new WebAppContentSecurityPolicy(() -> settings, ISSUER).current()));
    }

    @Test
    public void sendsATileHostOnce() {
        tiles("https://tiles.example.test/base/{z}/{x}/{y}.png", "https://tiles.example.test/sea/{z}/{x}/{y}.png");
        assertEquals("'self' data: blob: https://tiles.example.test",
                imgSrc(new WebAppContentSecurityPolicy(() -> settings, ISSUER).current()));
    }

    @Test
    public void theHostEndsWhereTheBrowserEndsIt() {
        // A browser ends the authority of an http(s) URL at a backslash too: this loads from tiles.example.test
        tiles("https://tiles.example.test\\@other.example.test/{z}/{x}/{y}.png", null);
        assertEquals("'self' data: blob: https://tiles.example.test https://t1.openseamap.org",
                imgSrc(new WebAppContentSecurityPolicy(() -> settings, ISSUER).current()));
    }

    @Test
    public void aRelativeValueAddsNothing() {
        tiles("/tiles/{z}/{x}/{y}.png", "tiles/{z}/{x}/{y}.png");
        assertEquals("'self' data: blob:", imgSrc(new WebAppContentSecurityPolicy(() -> settings, ISSUER).current()));
    }

    @Test
    public void aValueCannotAddADirective() {
        tiles("https://x.example/{z}/{x}/{y}.png; script-src *", "https://x.example 'unsafe-eval'/{z}/{x}/{y}.png");
        String policy = new WebAppContentSecurityPolicy(() -> settings, ISSUER).current();

        assertEquals("'self' data: blob: https://x.example", imgSrc(policy));
        assertEquals("'self'", directive(policy, "script-src"));
        assertFalse(policy.contains("unsafe-eval"), policy);
        assertFalse(policy.contains("*"), policy);
        assertEquals(11, policy.split(";").length, "the policy must keep its eleven directives: " + policy);
    }

    @Test
    public void aValueThatIsNoHttpUrlWithAPlainHostAddsNothing() {
        for (String value : new String[]{"ftp://tiles.example.test/{z}/{x}/{y}.png", "ftp:/garbage", "javascript:x",
                "https://user@tiles.example.test/{z}/{x}/{y}.png", "https://tiles_example.test/{z}/{x}/{y}.png",
                "https://tile{s}.example.test/{z}/{x}/{y}.png", "https:///{z}/{x}/{y}.png"}) {
            tiles(value, "/sea/{z}/{x}/{y}.png");
            assertEquals("'self' data: blob:", imgSrc(new WebAppContentSecurityPolicy(() -> settings, ISSUER).current()),
                    value);
        }
    }

    @Test
    public void aRangeOutsideTheFirstHostLabelAddsNothingAndTheWarningSaysSo() {
        // OpenLayers expands a range anywhere, but a source may carry a wildcard only in its first label
        Logger logger = (Logger) LoggerFactory.getLogger(WebAppContentSecurityPolicy.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            tiles("https://tile.{a-c}.example.org/{z}/{x}/{y}.png", "ftp://tiles.example.test/{z}/{x}/{y}.png");
            String policy = new WebAppContentSecurityPolicy(() -> settings, ISSUER).current();
            assertEquals("'self' data: blob:", imgSrc(policy));

            List<String> warnings = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertEquals(2, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).startsWith("The osmUrl setting 'https://tile.{a-c}.example.org/{z}/{x}/{y}.png'"
                    + " has an OpenLayers range outside the first host label"), warnings.get(0));
            assertTrue(warnings.get(1).startsWith("The openSeaMapUrl setting 'ftp://tiles.example.test/{z}/{x}/{y}.png'"
                    + " is not an http or https URL with a plain host"), warnings.get(1));
        } finally {
            logger.detachAppender(appender);
        }

        assertTrue(WebAppContentSecurityPolicy.whyNoSource("//tile.{1-4}.example.org/{z}/{x}/{y}.png")
                .startsWith("has an OpenLayers range outside the first host label"), "scheme-relative");
        assertTrue(WebAppContentSecurityPolicy.whyNoSource("https://{a-c}.{1-4}.example.org/{z}/{x}/{y}.png")
                .startsWith("has an OpenLayers range outside the first host label"), "a range in both labels");
        assertEquals("is not an http or https URL with a plain host",
                WebAppContentSecurityPolicy.whyNoSource("https://tile_x.example.org/{a-c}/{z}/{x}/{y}.png"),
                "a range in the path is not in the host");
    }

    // ------------------------------------------------------------------------------------------- //
    // Keycloak
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void keycloakIsTheIssuersOrigin() {
        tiles(null, null);
        String policy = new WebAppContentSecurityPolicy(() -> settings, "https://niord.example.test/auth/realms/niord")
                .current();

        assertEquals("'self' https://niord.example.test", directive(policy, "connect-src"));
        assertEquals("'self' https://niord.example.test", directive(policy, "frame-src"));
    }

    @Test
    public void noIssuerAdmitsKeycloakOnTheAppsOriginOnly() {
        tiles(null, null);
        for (String issuer : new String[]{null, " ", "not a url", "ftp://keycloak.example.test/realms/niord"}) {
            String policy = new WebAppContentSecurityPolicy(() -> settings, issuer).current();
            assertEquals("'self'", directive(policy, "connect-src"), issuer);
            assertEquals("'self'", directive(policy, "frame-src"), issuer);
        }
    }
}
