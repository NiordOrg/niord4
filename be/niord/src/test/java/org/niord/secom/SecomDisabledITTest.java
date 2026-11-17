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
package org.niord.secom;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.niord.secom.service.S124DatasetService;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With {@code niord.secom.enabled} at its default (false), the SECOM integration must be
 * completely absent: no SECOM beans, no servlet at /api/secom, no admin endpoints. This is
 * the rollback story — flipping the flag off returns the application to its pre-SECOM
 * behavior. Runs against the default (shared) test context.
 */
class SecomDisabledITTest extends ITTest {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void noSecomBeansExistWhenDisabled() {
        assertEquals(0, applicationContext.getBeanNamesForType(S124DatasetService.class).length,
                "SECOM beans must not exist unless niord.secom.enabled=true");
        assertEquals(0, applicationContext.getBeanNamesForType(
                org.niord.secom.pki.McpSecurityService.class).length);
        assertEquals(0, applicationContext.getBeanNamesForType(
                org.niord.secom.service.SecomConfigService.class).length);
        assertEquals(0, applicationContext.getBeanNamesForType(
                org.niord.secom.SecomMetrics.class).length);
    }

    @Test
    void noSecomMetersExistWhenDisabled() {
        MeterRegistry meterRegistry = applicationContext.getBean(MeterRegistry.class);
        assertTrue(meterRegistry.find("niord.secom.datasets").gauges().isEmpty(),
                "disabled deployments must expose no niord.secom.* meters");
        assertTrue(meterRegistry.find("niord.secom.outbox").gauges().isEmpty());
        assertTrue(meterRegistry.find("niord.secom.certificate.days-to-expiry").gauges().isEmpty());
    }

    @Test
    void secomEndpointsDoNotExistWhenDisabled() {
        given()
                .get("/api/secom/v2/capability")
                .then()
                .statusCode(404);
    }

    @Test
    void adminEndpointsDoNotExistWhenDisabled() {
        given().auth().oauth2(accessToken())
                .get("/rest/secom/status")
                .then()
                .statusCode(404);
    }
}
