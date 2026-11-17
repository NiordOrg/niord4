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
import org.niord.core.keycloak.KeycloakIntegrationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What {@code GET /rest/settings/keycloak} hands the web app: the Keycloak the browser reaches, the realm and the
 * web app's client id, as the service reads them from the token issuer. No URL when the service knows none: the
 * web app then keeps the values of its build.
 */
class KeycloakConfigRestServiceTest {

    private static KeycloakConfigRestService.KeycloakConfigVo answerFor(String url, String realm) {
        KeycloakIntegrationService service = mock(KeycloakIntegrationService.class);
        when(service.browserUrl()).thenReturn(url);
        when(service.realm()).thenReturn(realm);
        return new KeycloakConfigRestService(service).getKeycloakConfig();
    }

    @Test
    void theThreeValuesTheWebAppIsBuiltWith() {
        KeycloakConfigRestService.KeycloakConfigVo answer =
                answerFor("https://login.brs-test.e-navigation.dk/auth", "niord-gl");
        assertEquals("https://login.brs-test.e-navigation.dk/auth", answer.url());
        assertEquals("niord-gl", answer.realm());
        assertEquals("niord-web", answer.clientId());
    }

    @Test
    void noUrlWhenTheIssuerGivesNone() {
        KeycloakConfigRestService.KeycloakConfigVo answer = answerFor(null, "niord");
        assertNull(answer.url(), "the web app keeps the URL of its build");
        assertEquals("niord", answer.realm());
        assertEquals("niord-web", answer.clientId());
    }
}
