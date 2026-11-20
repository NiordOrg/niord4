/*
 * Copyright 2016 Danish Maritime Authority.
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

import org.springframework.transaction.annotation.Transactional;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for {@link ChartRestService}: the HTTP contract of the {@code /rest/charts}
 * endpoints, including authorization. The read endpoints ({@code /search}, {@code /search/{nrs}},
 * {@code /all}) are {@code @PermitAll}; chart create/update/delete require the {@code admin} role and
 * {@code /intersecting-charts} requires the {@code editor} role. Role-based cases are exercised with a
 * real Keycloak token (see {@link org.niord.test.KeycloakTestTokens}). The cached sysadmin token already carries the
 * {@code editor}, {@code admin} and {@code sysadmin} roles from {@code niord-client-master}, so it
 * satisfies every protected endpoint on this resource without a {@code NiordDomain} header.
 *
 * <p>Reads and the unauthenticated negative-security cases need no token. Positive role-based cases
 * create data that is committed (HTTP requests run in the server's own transaction, which
 * {@code @Transactional} cannot roll back), so created charts are tracked by chart number and
 * deleted in {@link #cleanup()} using a unique {@code IT-REST-} prefix that never occurs in the seed
 * data.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class ChartRestServiceITTest extends ITTest {

    private final List<String> createdChartNumbers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String chartNumber : createdChartNumbers) {
            try {
                given().auth().oauth2(accessToken()).delete("/rest/charts/chart/" + chartNumber);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdChartNumbers.clear();
    }

    /** Builds a mutable chart body so callers can tweak individual fields before posting. */
    private static Map<String, Object> chartBody(String chartNumber, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chartNumber", chartNumber);
        body.put("name", name);
        body.put("active", true);
        return body;
    }

    /** Creates a chart via the ADMIN-protected POST endpoint and tracks it for cleanup. */
    private void createChartAsAdmin(Map<String, Object> body) {
        String chartNumber = (String) body.get("chartNumber");
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/rest/charts/chart/")
                .then().statusCode(200)
                .body("chartNumber", is(chartNumber));
        createdChartNumbers.add(chartNumber);
    }

    // ------------------------------------------------------------------------------------------- //
    // Unauthenticated reads (@PermitAll)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAllReturnsSeededChartsWithoutToken() {
        given()
                .when().get("/rest/charts/all")
                .then().statusCode(200)
                .body("size()", greaterThan(0))
                .body("chartNumber", hasItem("D"))
                .body("chartNumber", hasItem("C"));
    }

    @Test
    void getAllRespectsLimitQueryParam() {
        // The full list has several seeded charts; limit=1 must trim the response to a single entry.
        given()
                .queryParam("limit", 1)
                .when().get("/rest/charts/all")
                .then().statusCode(200)
                .body("size()", is(1));
    }

    @Test
    void getChartsByNumbersReturnsRequestedSeededChart() {
        given()
                .when().get("/rest/charts/search/D")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1))
                .body("chartNumber", hasItem("D"))
                .body("find { it.chartNumber == 'D' }.name", is("Danmark med søgrænser"))
                .body("find { it.chartNumber == 'D' }.horizontalDatum", is("WGS84"));
    }

    @Test
    void getChartsByCommaSeparatedNumbersReturnsAllRequested() {
        given()
                .when().get("/rest/charts/search/D,C")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(2))
                .body("chartNumber", hasItem("D"))
                .body("chartNumber", hasItem("C"));
    }

    @Test
    void getChartsByUnknownNumberReturnsEmptyList() {
        given()
                .when().get("/rest/charts/search/IT-REST-NoSuchChart")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void searchWithBlankNameReturnsEmptyList() {
        // searchCharts() short-circuits to an empty list when the term is blank.
        given()
                .queryParam("name", "")
                .queryParam("limit", 10)
                .when().get("/rest/charts/search")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void searchByCreatedNameFindsActiveChart() {
        createChartAsAdmin(chartBody("IT-REST-Search-1", "IT-REST-Findable-Chart"));

        given()
                .queryParam("name", "IT-REST-Findable")
                .when().get("/rest/charts/search")
                .then().statusCode(200)
                .body("chartNumber", hasItem("IT-REST-Search-1"))
                .body("find { it.chartNumber == 'IT-REST-Search-1' }.name", is("IT-REST-Findable-Chart"));
    }

    @Test
    void searchByChartNumberMatchesOnNumber() {
        createChartAsAdmin(chartBody("IT-REST-SearchNr-1", "IT-REST-Some-Name"));

        given()
                .queryParam("name", "IT-REST-SearchNr-1")
                .when().get("/rest/charts/search")
                .then().statusCode(200)
                .body("chartNumber", hasItem("IT-REST-SearchNr-1"));
    }

    @Test
    void searchExcludesInactiveChartUnlessRequested() {
        // Created active, then made inactive via update. The default search (inactive=false) must omit
        // it, but inactive=true must include it again.
        createChartAsAdmin(chartBody("IT-REST-Inactive-1", "IT-REST-Inactive-Name"));

        Map<String, Object> inactiveBody = chartBody("IT-REST-Inactive-1", "IT-REST-Inactive-Name");
        inactiveBody.put("active", false);
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(inactiveBody)
                .when().put("/rest/charts/chart/IT-REST-Inactive-1")
                .then().statusCode(200)
                .body("active", is(false));

        given()
                .queryParam("name", "IT-REST-Inactive-Name")
                .when().get("/rest/charts/search")
                .then().statusCode(200)
                .body("chartNumber.findAll { it == 'IT-REST-Inactive-1' }", empty());

        given()
                .queryParam("name", "IT-REST-Inactive-Name")
                .queryParam("inactive", true)
                .when().get("/rest/charts/search")
                .then().statusCode(200)
                .body("chartNumber", hasItem("IT-REST-Inactive-1"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — protected writes without a token return 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createChartWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(chartBody("IT-REST-NoAuth", "x"))
                .when().post("/rest/charts/chart/")
                .then().statusCode(401);
    }

    @Test
    void updateChartWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(chartBody("IT-REST-NoAuth", "x"))
                .when().put("/rest/charts/chart/IT-REST-NoAuth")
                .then().statusCode(401);
    }

    @Test
    void deleteChartWithoutTokenIsUnauthorized() {
        given().when().delete("/rest/charts/chart/IT-REST-NoAuth").then().statusCode(401);
    }

    @Test
    void uploadChartsWithoutTokenIsUnauthorized() {
        given().multiPart("data", "charts.json", "[]".getBytes())
                .when().post("/rest/charts/upload-charts")
                .then().statusCode(401);
    }

    @Test
    void intersectingChartsWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("type", "FeatureCollection", "features", List.of()))
                .when().post("/rest/charts/intersecting-charts")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Positive role-based — admin/editor with a real token
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createChartAsAdminPersistsAndIsReadable() {
        Map<String, Object> body = chartBody("IT-REST-Create-1", "IT-REST-Created-Name");
        body.put("internationalNumber", 4242);
        body.put("scale", 50000);
        body.put("horizontalDatum", "WGS84");
        createChartAsAdmin(body);

        // Persistence side-effect: read it back through the @PermitAll search endpoint.
        given()
                .when().get("/rest/charts/search/IT-REST-Create-1")
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].chartNumber", is("IT-REST-Create-1"))
                .body("[0].name", is("IT-REST-Created-Name"))
                .body("[0].internationalNumber", is(4242))
                .body("[0].scale", is(50000))
                .body("[0].active", is(true))
                .body("[0].horizontalDatum", is("WGS84"));
    }

    @Test
    void createDuplicateChartNumberFails() {
        createChartAsAdmin(chartBody("IT-REST-Dup-1", "IT-REST-Dup-Name"));

        // A second create with the same chart number violates the unique constraint; the service throws
        // IllegalArgumentException, which RestExceptionStatusAdvice maps to 400 Bad Request (ERR1 fix).
        int status = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(chartBody("IT-REST-Dup-1", "IT-REST-Dup-Name-2"))
                .when().post("/rest/charts/chart/")
                .then().extract().statusCode();
        assertEquals(400, status);

        // The original chart is unchanged: still exactly one row with the original name.
        given()
                .when().get("/rest/charts/search/IT-REST-Dup-1")
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].name", is("IT-REST-Dup-Name"));
    }

    @Test
    void updateChartAsAdminPersistsNewValues() {
        createChartAsAdmin(chartBody("IT-REST-Upd-1", "IT-REST-Upd-Original"));

        Map<String, Object> updateBody = chartBody("IT-REST-Upd-1", "IT-REST-Upd-Renamed");
        updateBody.put("scale", 12345);
        updateBody.put("horizontalDatum", "ED50");
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(updateBody)
                .when().put("/rest/charts/chart/IT-REST-Upd-1")
                .then().statusCode(200)
                .body("chartNumber", is("IT-REST-Upd-1"))
                .body("name", is("IT-REST-Upd-Renamed"))
                .body("scale", is(12345))
                .body("horizontalDatum", is("ED50"));

        // Persistence side-effect: the change is durable, read back via the @PermitAll endpoint.
        given()
                .when().get("/rest/charts/search/IT-REST-Upd-1")
                .then().statusCode(200)
                .body("[0].name", is("IT-REST-Upd-Renamed"))
                .body("[0].scale", is(12345))
                .body("[0].horizontalDatum", is("ED50"));
    }

    @Test
    void updateChartWithMismatchedChartNumberReturns400() {
        createChartAsAdmin(chartBody("IT-REST-Mismatch-1", "IT-REST-Mismatch-Name"));

        // Path chart number differs from the body chart number -> NiordWebException(400).
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(chartBody("IT-REST-Mismatch-OTHER", "IT-REST-Mismatch-Name"))
                .when().put("/rest/charts/chart/IT-REST-Mismatch-1")
                .then().statusCode(400);

        // The chart was not modified by the rejected update.
        given()
                .when().get("/rest/charts/search/IT-REST-Mismatch-1")
                .then().statusCode(200)
                .body("[0].name", is("IT-REST-Mismatch-Name"));
    }

    @Test
    void updateNonExistingChartFails() {
        // No chart with this number exists; updateChart() throws IllegalArgumentException, which
        // RestExceptionStatusAdvice maps to 400 Bad Request (ERR1 fix).
        int status = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(chartBody("IT-REST-Ghost-1", "IT-REST-Ghost-Name"))
                .when().put("/rest/charts/chart/IT-REST-Ghost-1")
                .then().extract().statusCode();
        assertEquals(400, status);

        // Confirm nothing was created as a side-effect of the failed update.
        given()
                .when().get("/rest/charts/search/IT-REST-Ghost-1")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void deleteChartAsAdminRemovesIt() {
        createChartAsAdmin(chartBody("IT-REST-Del-1", "IT-REST-Del-Name"));

        given().auth().oauth2(accessToken())
                .when().delete("/rest/charts/chart/IT-REST-Del-1")
                .then().statusCode(204);

        // Persistence side-effect: the chart is gone.
        given()
                .when().get("/rest/charts/search/IT-REST-Del-1")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void deleteNonExistingChartSucceedsWithNoContent() {
        // deleteChart() returns false for a missing chart but the void endpoint still returns 204.
        given().auth().oauth2(accessToken())
                .when().delete("/rest/charts/chart/IT-REST-Missing-Del")
                .then().statusCode(204);
    }

    @Test
    void createChartWithANullCoordinate_isRefusedAndMakesNoChart() {
        // CG10: a coordinate sent as null was read as 0 and stored; it is refused as not a number, as a NaN is
        Map<String, Object> body = chartBody("IT-REST-NullCoord-1", "IT-REST-NullCoord-Name");
        body.put("geometry", Map.of("type", "Point", "coordinates", Arrays.asList(10.0, null)));
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/rest/charts/chart/")
                .then().statusCode(400)
                .body("detail", containsString("is not a number"));
        createdChartNumbers.add("IT-REST-NullCoord-1");

        given()
                .when().get("/rest/charts/search/IT-REST-NullCoord-1")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void intersectingChartsAsEditorWithEmptyCollectionReturnsEmptyList() {
        // sysadmin carries the editor role; an empty feature collection yields an empty geometry and
        // therefore an empty result list, which still proves the EDITOR-protected endpoint returns 200.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("type", "FeatureCollection", "features", List.of()))
                .when().post("/rest/charts/intersecting-charts")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void intersectingChartsWithANullCoordinate_isRefused() {
        // A coordinate sent as null is refused as the save refuses it, not searched as not a number
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("type", "FeatureCollection", "features", List.of(Map.of(
                        "type", "Feature", "properties", Map.of(),
                        "geometry", Map.of("type", "Point", "coordinates", Arrays.asList(10.0, null))))))
                .when().post("/rest/charts/intersecting-charts")
                .then().statusCode(400)
                .body("detail", containsString("is not a number"));
    }
}
