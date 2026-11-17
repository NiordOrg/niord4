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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.niord.test.ITTest;

import java.util.Calendar;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SERIES3: the three year endpoints of a message series ({@code GET .../number/{year}},
 * {@code PUT .../number/{year}} and {@code GET .../compute-number/{year}}) check the series and the year alike.
 * Compute used to skip both checks: a year beyond PostgreSQL's timestamp range answered 500 ("timestamp out of
 * range"), a year its siblings refuse (1500, 3000) answered 1, and an unknown series answered 1.
 *
 * <p>The reads are {@code @PermitAll}, so no token is needed. Nothing is written.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageSeriesComputeNumberITTest extends ITTest {

    @ParameterizedTest
    @ValueSource(ints = {987654321, -5000, 1500, 1699, 2101, 3000})
    void computeRefusesAYearOutOfRangeAsNumberDoes(int year) {
        given()
                .when().get("/rest/message-series/series/dma-nw/compute-number/" + year)
                .then().statusCode(400)
                .body("detail", containsString("Invalid year " + year));
        given()
                .when().get("/rest/message-series/series/dma-nw/number/" + year)
                .then().statusCode(400)
                .body("detail", containsString("Invalid year " + year));
    }

    @Test
    void anUnknownSeriesIsRefusedByComputeAndByNumber() {
        int year = Calendar.getInstance().get(Calendar.YEAR);
        given()
                .when().get("/rest/message-series/series/IT-no-such-series/compute-number/" + year)
                .then().statusCode(400)
                .body("detail", containsString("Invalid message series IT-no-such-series"));
        given()
                .when().get("/rest/message-series/series/IT-no-such-series/number/" + year)
                .then().statusCode(400)
                .body("detail", containsString("Invalid message series IT-no-such-series"));
    }

    @Test
    void computeStillAnswersTheComputedNumberForAYearInRange() {
        // dma-fa has no numbered message in the fixture, so the computed next number is 1 in any year; the
        // bounds 1700 and 2100 are accepted as number accepts them
        for (int year : new int[] {Calendar.getInstance().get(Calendar.YEAR), 2026, 1700, 2100}) {
            String body = given()
                    .when().get("/rest/message-series/series/dma-fa/compute-number/" + year)
                    .then().statusCode(200)
                    .extract().asString().trim();
            assertEquals("1", body, "compute-number/" + year);
        }
    }
}
