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
package org.niord.core.conf;

import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.springframework.test.context.TestPropertySource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Pins the actuator deny-by-default rule in {@link SecurityConfig}.
 *
 * <p>The actuator endpoints are not {@code @RestController} handlers, so no JSR-250 annotation and no
 * build guard covers them, and — alone among the application's request-handling surfaces — their
 * anonymous exposure widens by <em>configuration alone</em>
 * ({@code management.endpoints.web.exposure.include}), with no Java to review. Under Boot's default
 * exposure only {@code health} is exposed, so the rule is a no-op and nothing in the suite would
 * notice if it were deleted.
 *
 * <p>This test therefore does what a deployment could do with a one-line ConfigMap edit: it exposes a
 * second endpoint ({@code env}) and asserts that it is rejected, while the {@code health} endpoint the
 * k8s liveness/readiness probes contract on keeps answering anonymously.
 */
@TestPropertySource(properties = "management.endpoints.web.exposure.include=health,env")
class ActuatorEndpointAuthorizationITTest extends ITTest {

    @Test
    void healthStaysAnonymous() {
        // The k8s liveness/readiness probes carry no credentials
        given()
                .when().get("/rest/actuator/health")
                .then().statusCode(200)
                .body("status", is("UP"));
    }

    @Test
    void anExposedNonHealthEndpointIsRejectedForAnonymousCallers() {
        given()
                .when().get("/rest/actuator/env")
                .then().statusCode(401);
    }

    /**
     * {@code EndpointRequest.toAnyEndpoint()} also covers the links index, so the discovery document at
     * the actuator base path is denied along with the endpoints it would list. This is the one response
     * the rule changes under Boot's default exposure, so it is pinned deliberately rather than left to
     * be discovered as a surprise.
     */
    @Test
    void theActuatorLinksIndexIsRejectedForAnonymousCallers() {
        given()
                .when().get("/rest/actuator")
                .then().statusCode(401);
    }

    @Test
    void anExposedNonHealthEndpointIsRejectedEvenForSysadmins() {
        // denyAll, not a role check: opening a further endpoint is a deliberate edit to SecurityConfig,
        // not something a token can do
        given().auth().oauth2(accessToken("sysadmin"))
                .when().get("/rest/actuator/env")
                .then().statusCode(403);
    }
}
