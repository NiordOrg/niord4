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
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for {@link MessageSeriesRestService}: the HTTP contract of the {@code
 * /rest/message-series} endpoints, including authorization. The GET reads ({@code /all},
 * {@code /search}, {@code /search/{ids}}, {@code .../number/...}, {@code .../compute-number/...})
 * are {@code @PermitAll}; the series {@code POST}/{@code PUT}/{@code DELETE} and the set-number
 * {@code PUT} require the {@code sysadmin} role, exercised with a real Keycloak token (see the
 * inherited {@code accessToken()} helper).
 *
 * <p>Reads and negative-security cases need no token. Positive sysadmin cases create data that is
 * committed (HTTP requests run in the server's own transaction, which {@code @Transactional}
 * cannot roll back), so created series are tracked and deleted in {@link #cleanup()} using unique
 * {@code IT-REST-} series IDs that never occur in the seed data.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageSeriesRestServiceITTest extends ITTest {

    private final List<String> createdSeriesIds = new ArrayList<>();

    @Autowired
    DomainService domainService;

    @AfterEach
    void cleanup() {
        for (String seriesId : createdSeriesIds) {
            try {
                given().auth().oauth2(accessToken()).delete("/rest/message-series/series/" + seriesId);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdSeriesIds.clear();
    }

    /** Creates a message series as sysadmin and tracks it for cleanup. */
    private void createSeriesAsSysadmin(String seriesId, String mainType, String numberSequenceType) {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "seriesId", seriesId,
                        "mainType", mainType,
                        "numberSequenceType", numberSequenceType))
                .when().post("/rest/message-series/series/")
                .then().statusCode(200)
                .body("seriesId", is(seriesId));
        createdSeriesIds.add(seriesId);
    }

    // ------------------------------------------------------------------------------------------- //
    // Unauthenticated reads (@PermitAll) — happy paths against seed data
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAllReturnsSeededSeriesWithoutToken() {
        given()
                .when().get("/rest/message-series/all")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(6))
                .body("seriesId", hasItem("dma-nw"))
                .body("seriesId", hasItem("dma-nm"));
    }

    @Test
    void getAllExposesMainTypeAndSequenceTypeForKnownSeries() {
        // dma-nw is a NW series with a YEARLY number sequence in the seed data.
        given()
                .when().get("/rest/message-series/all")
                .then().statusCode(200)
                .body("find { it.seriesId == 'dma-nw' }.mainType", is("NW"))
                .body("find { it.seriesId == 'dma-nw' }.numberSequenceType", is("YEARLY"))
                .body("find { it.seriesId == 'dma-fa' }.mainType", is("NM"))
                .body("find { it.seriesId == 'dma-fa' }.numberSequenceType", is("MANUAL"));
    }

    @Test
    void getAllWithMessageNumbersPopulatesNextMessageNumber() {
        // With messageNumbers=true the endpoint loads the next number for the current year. The fixture
        // numbers the sample's messages in dma-nw and dma-nm (their 2026 sequences stand at 9), but has no
        // numbered message and no sequence row in dma-fa, so its next number defaults to 1 in any year.
        given()
                .queryParam("messageNumbers", true)
                .when().get("/rest/message-series/all")
                .then().statusCode(200)
                .body("find { it.seriesId == 'dma-fa' }.nextMessageNumber", is(1));
    }

    @Test
    void getAllWithMessageNumbersReadsTheNextNumberOfTheSeriesCurrentYear() {
        // A series of its own in a domain of its own on Pacific/Kiritimati (+14:00): the list reads the next number
        // of the year it is now in that zone, the year a message published now is numbered in. The marker is set
        // in that year only, so the list shows it only when it reads that year (outside the last hours of
        // 31 December in UTC, the JVM's year is the same; MessageSeriesRestServiceTest pins the difference).
        String seriesId = "IT-REST-year-" + System.nanoTime();
        createSeriesAsSysadmin(seriesId, "NW", "YEARLY");
        String domainId = "it-rest-year-" + System.nanoTime();
        Domain domain = new Domain();
        domain.setDomainId(domainId);
        domain.setName("Series year test domain");
        domain.setTimeZone("Pacific/Kiritimati");
        domain.getMessageSeries().add(new MessageSeries(seriesId));
        domainService.createDomain(domain, false);
        try {
            int year = MessageSeriesService.yearAt(Instant.now(), ZoneId.of("Pacific/Kiritimati"));
            given().auth().oauth2(accessToken())
                    .contentType(ContentType.JSON)
                    .body("77")
                    .when().put("/rest/message-series/series/" + seriesId + "/number/" + year)
                    .then().statusCode(200);

            given()
                    .queryParam("messageNumbers", true)
                    .when().get("/rest/message-series/all")
                    .then().statusCode(200)
                    .body("find { it.seriesId == '" + seriesId + "' }.nextMessageNumber", is(77));
        } finally {
            // Out of the domain first: a series in a domain cannot be deleted (cleanup deletes the series)
            domainService.deleteDomain(domainId);
        }
    }

    @Test
    void getAllWithoutMessageNumbersOmitsNextMessageNumber() {
        given()
                .when().get("/rest/message-series/all")
                .then().statusCode(200)
                .body("find { it.seriesId == 'dma-nw' }.nextMessageNumber", is(nullValue()));
    }

    @Test
    void searchByIdsReturnsOnlyRequestedSeries() {
        given()
                .when().get("/rest/message-series/search/dma-nw")
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].seriesId", is("dma-nw"));
    }

    @Test
    void searchByMultipleIdsReturnsAllRequestedSeries() {
        given()
                .when().get("/rest/message-series/search/dma-nw,dma-nm")
                .then().statusCode(200)
                .body("size()", is(2))
                .body("seriesId", hasItem("dma-nw"))
                .body("seriesId", hasItem("dma-nm"));
    }

    @Test
    void searchByIdsHonoursLimit() {
        given()
                .queryParam("limit", 1)
                .when().get("/rest/message-series/search/dma-nw,dma-nm")
                .then().statusCode(200)
                .body("size()", is(1));
    }

    @Test
    void searchByUnknownIdReturnsEmptyList() {
        given()
                .when().get("/rest/message-series/search/IT-REST-no-such-series")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void searchByTermMatchesSeriesIdSubstring() {
        // The search query matches against the seriesId (case-insensitive LIKE). "dma-nw" should
        // match both dma-nw and dma-nw-local.
        given()
                .queryParam("name", "dma-nw")
                .when().get("/rest/message-series/search")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(2))
                .body("seriesId", hasItem("dma-nw"))
                .body("seriesId", hasItem("dma-nw-local"));
    }

    @Test
    void searchWithBlankTermReturnsEmptyList() {
        // searchMessageSeries returns an empty list when the term is blank.
        given()
                .queryParam("name", "")
                .when().get("/rest/message-series/search")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void searchByTermRestrictedToDomainOnlyReturnsThatDomainsSeries() {
        // The niord-nw domain is associated with the dma-nw and dma-nw-local series only, so
        // restricting a term that matches several series to that domain filters out non-domain hits.
        given()
                .queryParam("name", "dma")
                .queryParam("domain", "niord-nw")
                .when().get("/rest/message-series/search")
                .then().statusCode(200)
                .body("size()", is(2))
                .body("seriesId", hasItem("dma-nw"))
                .body("seriesId", hasItem("dma-nw-local"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Number GET endpoints (@PermitAll) returning Integer
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getNextNumberForSeededSeriesDefaultsToOne() {
        // dma-fa: the fixture's series with no sequence row (dma-nw's and dma-nm's hold the sample's numbers)
        int year = Calendar.getInstance().get(Calendar.YEAR);
        String body = given()
                .when().get("/rest/message-series/series/dma-fa/number/" + year)
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("1", body);
    }

    @Test
    void computeNextNumberForSeededSeriesWithNoNumberedMessagesIsOne() {
        // computeNextMessageNumber uses coalesce(max(number),0)+1; dma-fa holds four sample messages in the
        // fixture (ids 27-30) with number NULL, which Message.maxNumberInPeriod filters out (the sample numbers
        // its messages in dma-nw and dma-nm), so it is 1.
        int year = Calendar.getInstance().get(Calendar.YEAR);
        String body = given()
                .when().get("/rest/message-series/series/dma-fa/compute-number/" + year)
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("1", body);
    }

    @Test
    void setNextNumberAsSysadminPersistsAndIsReadBack() {
        // Use a freshly created series so the committed sequence row is keyed by a unique seriesId
        // and never collides with seed data or other tests.
        String seriesId = "IT-REST-setnum-" + System.nanoTime();
        createSeriesAsSysadmin(seriesId, "NW", "YEARLY");
        int year = 2050;

        // Before setting, the next number defaults to 1.
        given()
                .when().get("/rest/message-series/series/" + seriesId + "/number/" + year)
                .then().statusCode(200)
                .body(is("1"));

        // Set the next number to 42; the endpoint echoes the value back.
        String setBody = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body("42")
                .when().put("/rest/message-series/series/" + seriesId + "/number/" + year)
                .then().statusCode(200)
                .extract().asString().trim();
        assertEquals("42", setBody);

        // The next-number GET now reflects the persisted sequence value.
        given()
                .when().get("/rest/message-series/series/" + seriesId + "/number/" + year)
                .then().statusCode(200)
                .body(is("42"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — protected writes without a token return 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createSeriesWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("seriesId", "IT-REST-unauth", "mainType", "NW"))
                .when().post("/rest/message-series/series/")
                .then().statusCode(401);
    }

    @Test
    void updateSeriesWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("seriesId", "dma-nw", "mainType", "NW"))
                .when().put("/rest/message-series/series/dma-nw")
                .then().statusCode(401);
    }

    @Test
    void deleteSeriesWithoutTokenIsUnauthorized() {
        given().when().delete("/rest/message-series/series/dma-nw").then().statusCode(401);
    }

    @Test
    void setNextNumberWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body("5")
                .when().put("/rest/message-series/series/dma-nw/number/2050")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Positive sysadmin write flows + persistence side-effects
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createSeriesAsSysadminPersistsAndIsReadable() {
        String seriesId = "IT-REST-create-" + System.nanoTime();
        createSeriesAsSysadmin(seriesId, "NM", "MANUAL");

        // Read it back via the search-by-ids endpoint to prove it was committed.
        given()
                .when().get("/rest/message-series/search/" + seriesId)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].seriesId", is(seriesId))
                .body("[0].mainType", is("NM"))
                .body("[0].numberSequenceType", is("MANUAL"));
    }

    @Test
    void createDuplicateSeriesFails() {
        // createMessageSeries throws IllegalArgumentException on a duplicate seriesId, which
        // RestExceptionStatusAdvice maps to 400 Bad Request (ERR1 fix); the bare token is authorized,
        // so the failure is a client error rather than 401/403.
        String seriesId = "IT-REST-dup-" + System.nanoTime();
        createSeriesAsSysadmin(seriesId, "NW", "YEARLY");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("seriesId", seriesId, "mainType", "NW", "numberSequenceType", "YEARLY"))
                .when().post("/rest/message-series/series/")
                .then().statusCode(400);
    }

    @Test
    void updateSeriesAsSysadminChangesPersistedFields() {
        String seriesId = "IT-REST-update-" + System.nanoTime();
        createSeriesAsSysadmin(seriesId, "NW", "YEARLY");

        // Update: change the sequence type, set a short format and types/editor fields.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "seriesId", seriesId,
                        "mainType", "NW",
                        "numberSequenceType", "CONTINUOUS",
                        "shortFormat", "IT-REST-${number}",
                        "types", List.of("LOCAL_WARNING"),
                        "editorFields", List.of("publishDateFrom")))
                .when().put("/rest/message-series/series/" + seriesId)
                .then().statusCode(200)
                .body("seriesId", is(seriesId))
                .body("numberSequenceType", is("CONTINUOUS"))
                .body("shortFormat", is("IT-REST-${number}"))
                .body("types", contains("LOCAL_WARNING"))
                .body("editorFields", contains("publishDateFrom"));

        // Re-read to confirm the changes were committed, not just reflected in the response.
        given()
                .when().get("/rest/message-series/search/" + seriesId)
                .then().statusCode(200)
                .body("[0].numberSequenceType", is("CONTINUOUS"))
                .body("[0].shortFormat", is("IT-REST-${number}"));
    }

    @Test
    void updateSeriesWithMismatchedIdReturns400() {
        String seriesId = "IT-REST-mismatch-" + System.nanoTime();
        createSeriesAsSysadmin(seriesId, "NW", "YEARLY");

        // The path seriesId differs from the body seriesId -> NiordWebException(400).
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("seriesId", seriesId + "-other", "mainType", "NW", "numberSequenceType", "YEARLY"))
                .when().put("/rest/message-series/series/" + seriesId)
                .then().statusCode(400);
    }

    @Test
    void deleteSeriesAsSysadminRemovesIt() {
        String seriesId = "IT-REST-delete-" + System.nanoTime();
        createSeriesAsSysadmin(seriesId, "NM", "MANUAL");

        // Confirm it exists first.
        given()
                .when().get("/rest/message-series/search/" + seriesId)
                .then().statusCode(200)
                .body("size()", is(1));

        // Delete it (sysadmin), then confirm it is gone. The endpoint returns void, so a successful
        // delete yields a 2xx (no-content) status rather than a body.
        given().auth().oauth2(accessToken())
                .when().delete("/rest/message-series/series/" + seriesId)
                .then().statusCode(greaterThanOrEqualTo(200)).statusCode(lessThan(300));
        createdSeriesIds.remove(seriesId);

        given()
                .when().get("/rest/message-series/search/" + seriesId)
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void deleteUnknownSeriesAsSysadminSucceedsWithNoEffect() {
        // deleteMessageSeries returns false (no exception) when the series does not exist; the void
        // endpoint still completes with a 2xx.
        given().auth().oauth2(accessToken())
                .when().delete("/rest/message-series/series/IT-REST-never-existed")
                .then().statusCode(greaterThanOrEqualTo(200))
                .statusCode(lessThan(300));
    }
}
