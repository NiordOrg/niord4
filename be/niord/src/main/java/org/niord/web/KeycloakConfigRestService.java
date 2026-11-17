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

import jakarta.annotation.security.PermitAll;
import org.niord.core.keycloak.KeycloakIntegrationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hands the administration web app the Keycloak it signs in to: {@code GET /rest/settings/keycloak}.
 *
 * <p>The web app's build names Keycloak at {@code /auth} of its own origin and the realm {@code niord}. A
 * deployment whose Keycloak is elsewhere, the shared Keycloak of a cluster with a realm per country, says so
 * here, and the web app reads it before it builds its Keycloak client. The values come from the token issuer the
 * backend itself validates ({@link KeycloakIntegrationService#ISSUER_URI_PROPERTY}), so the browser and the
 * resource server always agree on the Keycloak and the realm. The client id is the web app's.
 *
 * <p>Anonymous by design, like {@code /rest/settings/web}: nothing here is secret, and the browser reads it
 * before anyone has signed in.
 */
@RestController
@RequestMapping("/rest/settings")
public class KeycloakConfigRestService {

    /** The web app's client in the realm */
    public static final String WEB_CLIENT_ID = "niord-web";

    private final KeycloakIntegrationService keycloakIntegrationService;

    KeycloakConfigRestService(KeycloakIntegrationService keycloakIntegrationService) {
        this.keycloakIntegrationService = keycloakIntegrationService;
    }

    /**
     * Returns the Keycloak the browser reaches, the realm and the web app's client id. {@code url} is null
     * when the token issuer is not set or does not end in {@code /realms/<realm>}: the web app then keeps the
     * URL of its build, while the realm here is the default and the client id is always this one.
     *
     * @return the browser's Keycloak settings
     */
    @GetMapping(value = "/keycloak", produces = "application/json;charset=UTF-8")
    @PermitAll // read before sign-in, like the web settings; nothing here is secret
    public KeycloakConfigVo getKeycloakConfig() {
        return new KeycloakConfigVo(
                keycloakIntegrationService.browserUrl(),
                keycloakIntegrationService.realm(),
                WEB_CLIENT_ID);
    }

    /**
     * The three values keycloak-js is built with.
     *
     * @param url      the Keycloak URL the browser reaches, {@code <scheme>://<host>/auth}; null when unknown
     * @param realm    the realm the browser signs in to
     * @param clientId the web app's client in that realm
     */
    public record KeycloakConfigVo(String url, String realm, String clientId) {
    }
}
