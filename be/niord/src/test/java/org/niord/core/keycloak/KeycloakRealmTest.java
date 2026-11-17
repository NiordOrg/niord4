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
package org.niord.core.keycloak;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The realm and the browser's Keycloak URL are read from the token issuer, {@code <url>/realms/<realm>}: one
 * deployment value, so the admin client, the resource server and the web app agree. Without an issuer, as in a
 * unit test or a run that sets none, the realm is the base product's {@code niord} and the URL is unknown.
 */
class KeycloakRealmTest {

    private static KeycloakIntegrationService withIssuer(String issuer) {
        KeycloakIntegrationService service = new KeycloakIntegrationService();
        service.issuerUri = issuer;
        return service;
    }

    @Test
    void theRealmAndTheUrlComeFromTheIssuer() {
        KeycloakIntegrationService service =
                withIssuer("https://login.brs-test.e-navigation.dk/auth/realms/niord-dk");
        assertEquals("niord-dk", service.realm());
        assertEquals("https://login.brs-test.e-navigation.dk/auth", service.browserUrl());
    }

    @Test
    void aTrailingSlashOnTheIssuerChangesNothing() {
        KeycloakIntegrationService service = withIssuer("http://localhost:8080/auth/realms/niord/");
        assertEquals("niord", service.realm());
        assertEquals("http://localhost:8080/auth", service.browserUrl());
    }

    @Test
    void withoutAnIssuerTheRealmIsTheBaseProductsAndTheUrlIsUnknown() {
        KeycloakIntegrationService service = withIssuer(null);
        assertEquals(KeycloakIntegrationService.KEYCLOAK_REALM, service.realm());
        assertNull(service.browserUrl());

        service = withIssuer("   ");
        assertEquals("niord", service.realm());
        assertNull(service.browserUrl());
    }

    @Test
    void anIssuerThatNamesNoRealmGivesTheDefaultRealmAndNoUrl() {
        for (String issuer : new String[] {"https://auth.example", "https://auth.example/realms/",
                "https://auth.example/realms/a/b", "/realms/niord"}) {
            KeycloakIntegrationService service = withIssuer(issuer);
            assertEquals("niord", service.realm(), issuer);
            assertNull(service.browserUrl(), issuer);
        }
    }
}
