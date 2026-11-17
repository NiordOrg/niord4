/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.test;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Keycloak test container starts <em>with the Niord realm imported</em> and exposes a
 * working OIDC endpoint, so authenticated integration tests have a valid identity provider.
 * <p>
 * The container starts with {@code --import-realm} from a static copy of
 * {@code niord-bootstrap-realm-dk.json (the original niord-dk deployment realm)}, so this test asserts the imported {@code niord}
 * realm's OIDC discovery document (the realm the application authenticates against via
 * {@code NIORD-KEYCLOAK-URL}). A 200 there proves both that Keycloak is up and that the realm
 * import succeeded.
 * <p>
 * Keycloak is the slowest container to start (realm import takes tens of seconds). It requires
 * Docker and skips gracefully otherwise (see {@link ITTest}).
 */
class KeycloakITTest extends ITTest {

    @Test
    void keycloakExposesNiordRealmOidcDiscoveryDocument() throws Exception {
        GenericContainer<?> keycloak = NiordTestContainers.keycloak();
        assertTrue(keycloak.isRunning(), "Keycloak container should be running");

        URL discovery = new URL(NiordTestContainers.keycloakRealmUrl() + "/.well-known/openid-configuration");
        HttpURLConnection conn = (HttpURLConnection) discovery.openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        try {
            assertEquals(200, conn.getResponseCode(),
                    "Imported 'niord' realm OIDC discovery endpoint should return HTTP 200");
        } finally {
            conn.disconnect();
        }
    }
}
