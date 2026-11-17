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
import org.niord.core.settings.SettingsService;
import org.niord.test.ITTest;
import org.niord.test.NiordTestContainers;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test of {@link WebAppContentSecurityPolicy} against the real settings: the policy names the tile
 * host of the stored {@code osmUrl} and the Keycloak container's origin, and follows a committed change of the
 * tile settings on the next call, as a page load after an edit on the Settings page would.
 *
 * <p>Runs on the shared integration-test context and changes the settings only for the duration of the test.
 */
public class WebAppContentSecurityPolicyITTest extends ITTest {

    @Autowired
    SettingsService settingsService;

    @Autowired
    Environment environment;

    /** Returns the sources of the directive, without its name */
    private static String directive(String policy, String name) {
        return Arrays.stream(policy.split("; "))
                .filter(directive -> directive.startsWith(name + " "))
                .findFirst()
                .map(directive -> directive.substring(name.length() + 1))
                .orElseThrow(() -> new AssertionError("no " + name + " in " + policy));
    }

    @Test
    public void followsAChangeOfTheTileSettings() {
        WebAppContentSecurityPolicy policy = new WebAppContentSecurityPolicy(() -> settingsService,
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"));

        // The test settings give both layers the same host, so both change, or the old host would stay
        Object storedOsmUrl = settingsService.getSetting("osmUrl").getValue();
        Object storedOpenSeaMapUrl = settingsService.getSetting("openSeaMapUrl").getValue();
        String storedOrigin = WebAppContentSecurityPolicy.originOf(settingsService.peek("osmUrl").toString(), true);
        assertNotNull(storedOrigin, "the stored osmUrl must give a tile host: " + storedOsmUrl);
        String keycloak = WebAppContentSecurityPolicy.originOf(NiordTestContainers.keycloakAuthServerUrl(), false);

        String before = policy.current();
        assertTrue((" " + directive(before, "img-src") + " ").contains(" " + storedOrigin + " "), before);
        assertEquals("'self' " + keycloak, directive(before, "connect-src"));
        assertEquals("'self' " + keycloak, directive(before, "frame-src"));

        try {
            Tx.requiringNew().run(() -> {
                settingsService.set("osmUrl", "https://tiles.example.test/{z}/{x}/{y}.png");
                settingsService.set("openSeaMapUrl", "https://seamarks.example.test/{z}/{x}/{y}.png");
            });

            String changed = policy.current();
            assertEquals("'self' data: blob: https://tiles.example.test https://seamarks.example.test",
                    directive(changed, "img-src"));
            assertFalse(changed.contains(storedOrigin), "the old tile host must be gone: " + changed);
        } finally {
            Tx.requiringNew().run(() -> {
                settingsService.set("osmUrl", storedOsmUrl);
                settingsService.set("openSeaMapUrl", storedOpenSeaMapUrl);
            });
        }

        assertEquals(before, policy.current(), "the restored settings must give the policy of before");
    }
}
