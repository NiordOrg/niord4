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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /rest/settings/keycloak} over HTTP: the path, the status and the JSON the web app parses. The web
 * app falls back to its build's values when the answer is not what it expects, silently, so a regression here
 * would break sign-in on a shared Keycloak while every unit test stays green.
 */
class KeycloakConfigRestServiceHttpTest {

    private static MockMvc mvc(String url, String realm) {
        KeycloakIntegrationService service = mock(KeycloakIntegrationService.class);
        when(service.browserUrl()).thenReturn(url);
        when(service.realm()).thenReturn(realm);
        return MockMvcBuilders.standaloneSetup(new KeycloakConfigRestService(service)).build();
    }

    @Test
    void theAnswerIsJsonWithTheThreeKeys() throws Exception {
        mvc("https://login.brs-test.e-navigation.dk/auth", "niord-dk")
                .perform(get("/rest/settings/keycloak"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.url").value("https://login.brs-test.e-navigation.dk/auth"))
                .andExpect(jsonPath("$.realm").value("niord-dk"))
                .andExpect(jsonPath("$.clientId").value("niord-web"));
    }

    @Test
    void anUnknownUrlIsNull() throws Exception {
        mvc(null, "niord")
                .perform(get("/rest/settings/keycloak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").doesNotExist())
                .andExpect(jsonPath("$.realm").value("niord"));
    }
}
