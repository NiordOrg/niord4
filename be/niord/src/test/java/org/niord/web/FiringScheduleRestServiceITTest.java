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

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.schedule.FiringPeriod;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.Tx;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Integration tests for {@link FiringScheduleRestService}: the HTTP contract of the
 * {@code /rest/firing-schedules} endpoints, with the emphasis on authorization.
 *
 * <p>The resource class is annotated {@code @PermitAll}, but individual methods narrow access:
 * the two GET reads ({@code /all}, {@code /search-firing-area-periods}) inherit {@code @PermitAll}
 * and need no token; the firing-schedule {@code POST}/{@code PUT}/{@code DELETE} methods require the
 * {@code sysadmin} role, {@code update-firing-exercises} requires {@code editor}, and
 * {@code generate-firing-area-messages} requires {@code admin}. Role-based cases use a real Keycloak
 * token (see {@link KeycloakTestTokens}); the negative-security cases need no token and expect 401.
 *
 * <p>The id-mismatch guard in {@code updateFiringSchedule} runs in the JAX-RS layer (it throws
 * {@code NiordWebException(400)} before any service call), so it is exercised with a valid
 * token but a body whose {@code id} differs from the path id.
 *
 * <p>HTTP requests run in the server's own transaction, which {@code @Transactional} cannot roll
 * back, so the create/delete round trip tracks the generated id and deletes it in {@link #cleanup()}. The firing
 * period tests use the first firing area of {@code niord-fa} on days in May 2032, whose periods are deleted there
 * too.
 *
 * <p>Fixture data (see db/niord-base-data.sql, the sample's rows): domain {@code niord-fa} (id 4) and message
 * series {@code dma-fa} (id 4) exist; the fixture holds no {@code FiringSchedule} row.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class FiringScheduleRestServiceITTest extends ITTest {

    /** Stable seed values used by the create round trip. */
    private static final String SEED_FA_DOMAIN_ID = "niord-fa";
    private static final String SEED_FA_SERIES_ID = "dma-fa";

    /** The zone of {@code niord-fa}, in which the firing period tests' days are */
    private static final ZoneId FA_ZONE = ZoneId.of("Europe/Copenhagen");

    /** The firing period tests' days are from this one on; their periods are deleted after each test */
    private static final LocalDate FIRST_TEST_DAY = LocalDate.of(2032, 5, 1);

    private final List<Integer> createdScheduleIds = new ArrayList<>();

    private Integer periodArea;

    @PersistenceContext
    EntityManager em;

    @AfterEach
    void cleanup() {
        for (Integer id : createdScheduleIds) {
            try {
                given().auth().oauth2(accessToken()).delete("/rest/firing-schedules/firing-schedule/" + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdScheduleIds.clear();
        if (periodArea != null) {
            Integer areaId = periodArea;
            Tx.requiringNew().run(() -> em.createQuery(
                            "delete from FiringPeriod fp where fp.area.id = :area and fp.fromDate >= :from")
                    .setParameter("area", areaId)
                    .setParameter("from", at(FIRST_TEST_DAY, 0))
                    .executeUpdate());
            periodArea = null;
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Unauthenticated reads (@PermitAll inherited from the class)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAllFiringSchedulesIsPermitAllAndReturnsJsonArray() {
        // @PermitAll: no token required. With no seeded schedules the array is empty (size >= 0).
        given()
                .when().get("/rest/firing-schedules/all")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    void searchFiringAreaPeriodsIsPermitAllAndReturnsJsonArrayWithoutToken() {
        // @PermitAll: no token required — this is the security behavior under test. The body is the
        // JSON array of firing areas (the seed contains firing areas, stored via the AreaType ordinal,
        // so size may be > 0).
        given()
                .queryParam("inactive", true)
                .when().get("/rest/firing-schedules/search-firing-area-periods")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — protected writes without a token return 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createFiringScheduleWithoutTokenIsUnauthorized() {
        // @RolesAllowed(SYSADMIN): no token -> 401 before the body is processed.
        given().contentType(ContentType.JSON)
                .body(Map.of(
                        "domain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetDomain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetSeriesId", SEED_FA_SERIES_ID,
                        "scheduleDays", 7,
                        "active", true))
                .when().post("/rest/firing-schedules/firing-schedule/")
                .then().statusCode(401);
    }

    @Test
    void updateFiringScheduleWithoutTokenIsUnauthorized() {
        // @RolesAllowed(SYSADMIN): 401 takes precedence over the id-mismatch guard.
        given().contentType(ContentType.JSON)
                .body(Map.of(
                        "id", 1,
                        "domain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetDomain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetSeriesId", SEED_FA_SERIES_ID,
                        "scheduleDays", 7,
                        "active", true))
                .when().put("/rest/firing-schedules/firing-schedule/1")
                .then().statusCode(401);
    }

    @Test
    void deleteFiringScheduleWithoutTokenIsUnauthorized() {
        // @RolesAllowed(SYSADMIN): no token -> 401 (the service is never reached).
        given().when().delete("/rest/firing-schedules/firing-schedule/1")
                .then().statusCode(401);
    }

    @Test
    void updateFiringExercisesWithoutTokenIsUnauthorized() {
        // @RolesAllowed(EDITOR): no token -> 401.
        given().when().put("/rest/firing-schedules/update-firing-exercises")
                .then().statusCode(401);
    }

    @Test
    void generateFiringAreaMessagesWithoutTokenIsUnauthorized() {
        // @RolesAllowed(ADMIN): no token -> 401 before the current-domain / series validation runs.
        given().contentType(ContentType.JSON)
                .body(Map.of("seriesId", SEED_FA_SERIES_ID))
                .when().post("/rest/firing-schedules/generate-firing-area-messages")
                .then().statusCode(401);
    }

    @Test
    void updateFiringAreaPeriodsWithoutTokenIsUnauthorized() {
        // @RolesAllowed(EDITOR): no token -> 401 before the date/area null-checks run.
        given().contentType(ContentType.JSON)
                .queryParam("date", System.currentTimeMillis())
                .body(Map.of("area", Map.of("id", 1)))
                .when().put("/rest/firing-schedules/firing-area-periods")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Authenticated — id-mismatch guard in the JAX-RS layer
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateFiringScheduleWithMismatchedIdReturns400() {
        // The path id (1) differs from the body id (2). updateFiringSchedule throws
        // NiordWebException(400) before delegating to the service, so even a valid sysadmin
        // token yields 400 rather than touching persistence.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", 2,
                        "domain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetDomain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetSeriesId", SEED_FA_SERIES_ID,
                        "scheduleDays", 7,
                        "active", true))
                .when().put("/rest/firing-schedules/firing-schedule/1")
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // Positive role-based — sysadmin create/delete round trip
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createAndDeleteFiringScheduleAsSysadminRoundTrips() {
        // Create a firing schedule for the (un-scheduled) seeded firing-area domain. The service
        // resolves the domain/target-domain/target-series from the persisted seed rows and assigns
        // the @OneToOne back-reference (domain.firingSchedule), so a generated id is returned.
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "domain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetDomain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetSeriesId", SEED_FA_SERIES_ID,
                        "scheduleDays", 7,
                        "active", true))
                .when().post("/rest/firing-schedules/firing-schedule/")
                .then().statusCode(200)
                .body("id", notNullValue())
                .body("domain.domainId", is(SEED_FA_DOMAIN_ID))
                .body("targetDomain.domainId", is(SEED_FA_DOMAIN_ID))
                .body("targetSeriesId", is(SEED_FA_SERIES_ID))
                .body("scheduleDays", is(7))
                .body("active", is(true))
                .extract().path("id");
        createdScheduleIds.add(id);

        // The new schedule is now visible through the @PermitAll list read.
        given()
                .when().get("/rest/firing-schedules/all")
                .then().statusCode(200)
                .body("findAll { it.id == " + id + " }.size()", is(1));

        // Delete it as sysadmin; the void DELETE returns 204 No Content.
        given().auth().oauth2(accessToken())
                .when().delete("/rest/firing-schedules/firing-schedule/" + id)
                .then().statusCode(204);

        // It is gone from the list read.
        given()
                .when().get("/rest/firing-schedules/all")
                .then().statusCode(200)
                .body("findAll { it.id == " + id + " }.size()", is(0));

        // Already deleted — drop it so @AfterEach does not issue a second delete.
        createdScheduleIds.remove((Integer) id);
    }

    // ------------------------------------------------------------------------------------------- //
    // Firing area periods: FIRING2 (a period must end after it starts; "until midnight" is the next
    // day's 00:00; the periods stored backwards before the fix are listed on their start day)
    // ------------------------------------------------------------------------------------------- //

    private static Date at(LocalDate day, int hour) {
        return Date.from(day.atTime(hour, 0).atZone(FA_ZONE).toInstant());
    }

    private static long millisAt(LocalDate day, int hour) {
        return at(day, hour).getTime();
    }

    private static long millis(Object date) {
        return date instanceof Number n ? n.longValue() : Instant.parse((String) date).toEpochMilli();
    }

    private static RequestSpecification inFiringAreas() {
        return given().auth().oauth2(accessToken("editor")).header("NiordDomain", SEED_FA_DOMAIN_ID)
                .contentType(ContentType.JSON);
    }

    /** The first firing area of niord-fa, whose periods on the test days are deleted after the test */
    @SuppressWarnings("unchecked")
    private Integer firingArea() {
        List<Map<String, Object>> areas = inFiringAreas()
                .when().get("/rest/firing-schedules/search-firing-area-periods?inactive=true&date="
                        + millisAt(FIRST_TEST_DAY, 12))
                .then().statusCode(200).extract().jsonPath().getList("");
        assertFalse(areas.isEmpty(), "the firing-area domain has firing areas");
        periodArea = ((Number) ((Map<String, Object>) areas.getFirst().get("area")).get("id")).intValue();
        return periodArea;
    }

    /** The area's periods listed on the day, sorted by their start */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> periods(Integer areaId, LocalDate day) {
        List<Map<String, Object>> areas = inFiringAreas().when()
                .get("/rest/firing-schedules/search-firing-area-periods?inactive=true&area=" + areaId
                        + "&date=" + millisAt(day, 12))
                .then().statusCode(200).extract().jsonPath().getList("");
        return areas.stream()
                .filter(a -> ((Number) ((Map<String, Object>) a.get("area")).get("id")).intValue() == areaId)
                .findFirst()
                .map(a -> (List<Map<String, Object>>) a.get("firingPeriods")).orElseThrow().stream()
                .map(p -> (Map<String, Object>) new LinkedHashMap<>(p))
                .sorted(Comparator.comparingLong(p -> millis(p.get("fromDate"))))
                .toList();
    }

    /** Each period as its id, from and to */
    private static List<List<Long>> rows(List<Map<String, Object>> periods) {
        return periods.stream()
                .map(p -> List.of(((Number) p.get("id")).longValue(), millis(p.get("fromDate")), millis(p.get("toDate"))))
                .toList();
    }

    private static Map<String, Object> period(Integer id, long from, long to) {
        Map<String, Object> period = new LinkedHashMap<>();
        if (id != null) {
            period.put("id", id);
        }
        period.put("fromDate", from);
        period.put("toDate", to);
        return period;
    }

    private static io.restassured.response.ValidatableResponse putPeriods(Integer areaId, LocalDate day,
                                                                          List<Map<String, Object>> periods) {
        return inFiringAreas().body(Map.of("area", Map.of("id", areaId), "firingPeriods", periods))
                .when().put("/rest/firing-schedules/firing-area-periods?date=" + millisAt(day, 12))
                .then();
    }

    @Test
    void aDayWithABackwardPeriodAnswers400AndChangesNothing() {
        Integer areaId = firingArea();
        LocalDate day = FIRST_TEST_DAY.plusDays(9);
        putPeriods(areaId, day, List.of(period(null, millisAt(day, 6), millisAt(day, 8)))).statusCode(200);
        List<List<Long>> before = rows(periods(areaId, day));
        assertEquals(1, before.size());

        // The kept period, a good new one, and "09:00 - 00:00" of the same day
        List<Map<String, Object>> save = new ArrayList<>(periods(areaId, day));
        save.add(period(null, millisAt(day, 13), millisAt(day, 15)));
        save.add(period(null, millisAt(day, 9), millisAt(day, 0)));
        putPeriods(areaId, day, save).statusCode(400).body("detail", containsString("must end after it starts"));

        assertEquals(before, rows(periods(areaId, day)), "the day is as it was: the good new period is not stored either");
    }

    @Test
    void aPeriodEndingAtTheNextMidnightIsListedOnItsStartDayOnly() {
        Integer areaId = firingArea();
        LocalDate day = FIRST_TEST_DAY.plusDays(11);
        putPeriods(areaId, day, List.of(period(null, millisAt(day, 9), millisAt(day.plusDays(1), 0))))
                .statusCode(200);

        List<Map<String, Object>> listed = periods(areaId, day);
        assertEquals(1, listed.size(), "listed on its start day");
        assertEquals(millisAt(day.plusDays(1), 0), millis(listed.getFirst().get("toDate")));
        assertEquals(List.of(), periods(areaId, day.plusDays(1)), "not listed on the next day");

        // A save of the next day therefore leaves it alone
        putPeriods(areaId, day.plusDays(1), List.of()).statusCode(200);
        assertEquals(rows(listed), rows(periods(areaId, day)), "a save of the next day keeps it");
    }

    @Test
    void aStoredBackwardPeriodIsListedOnItsDayAndSavedWhenCorrected() {
        Integer areaId = firingArea();
        LocalDate day = FIRST_TEST_DAY.plusDays(13);
        // "09:00 - 00:00" of one day, as the editor stored "until midnight" before FIRING2 was fixed
        Integer storedId = Tx.requiringNew().call(() -> {
            FiringPeriod fp = new FiringPeriod();
            fp.setArea(em.find(Area.class, areaId));
            fp.setFromDate(at(day, 9));
            fp.setToDate(at(day, 0));
            em.persist(fp);
            em.flush();
            return fp.getId();
        });

        List<Map<String, Object>> listed = periods(areaId, day);
        assertEquals(List.of(List.of((long) storedId, millisAt(day, 9), millisAt(day, 0))), rows(listed),
                "listed on its start day, for the editor to show and correct");
        assertEquals(List.of(), periods(areaId, day.minusDays(1)), "not on the day before");
        assertEquals(List.of(), periods(areaId, day.plusDays(1)), "not on the day after");

        // Saved back unchanged, it is refused: the day is not saved until it is corrected
        putPeriods(areaId, day, listed).statusCode(400);
        assertEquals(rows(listed), rows(periods(areaId, day)), "the row is as it was");

        // Corrected, with its id kept and its end at the next day's 00:00, it is saved
        putPeriods(areaId, day, List.of(period(storedId, millisAt(day, 9), millisAt(day.plusDays(1), 0))))
                .statusCode(200);
        assertEquals(List.of(List.of((long) storedId, millisAt(day, 9), millisAt(day.plusDays(1), 0))),
                rows(periods(areaId, day)), "the same row, ending at the next midnight");
        assertEquals(List.of(), periods(areaId, day.plusDays(1)), "and not listed on the next day");
    }

    // ------------------------------------------------------------------------------------------- //
    // WEB17: incomplete bodies answer 400, a second schedule for a domain 409
    // ------------------------------------------------------------------------------------------- //

    @Test
    void firingAreaPeriodsWithoutADateOrAnAreaAnswer400() {
        inFiringAreas().body(Map.of("area", Map.of("id", 1), "firingPeriods", List.of()))
                .when().put("/rest/firing-schedules/firing-area-periods")
                .then().statusCode(400).body("detail", containsString("The date must be specified"));
        inFiringAreas().body(Map.of())
                .when().put("/rest/firing-schedules/firing-area-periods?date=" + millisAt(FIRST_TEST_DAY, 12))
                .then().statusCode(400).body("detail", containsString("The firing area must be specified"));
    }

    @Test
    void firingAreaPeriodsWithoutTheListAnswer400AndKeepTheDay() {
        Integer areaId = firingArea();
        LocalDate day = FIRST_TEST_DAY.plusDays(17);
        putPeriods(areaId, day, List.of(period(null, millisAt(day, 6), millisAt(day, 8)))).statusCode(200);
        List<List<Long>> before = rows(periods(areaId, day));
        assertEquals(1, before.size());

        // The field left out, and given as null: refused, as an update replaces the day's periods
        inFiringAreas().body(Map.of("area", Map.of("id", areaId)))
                .when().put("/rest/firing-schedules/firing-area-periods?date=" + millisAt(day, 12))
                .then().statusCode(400).body("detail", containsString("The firing periods must be specified"));
        Map<String, Object> explicitNull = new HashMap<>();
        explicitNull.put("area", Map.of("id", areaId));
        explicitNull.put("firingPeriods", null);
        inFiringAreas().body(explicitNull)
                .when().put("/rest/firing-schedules/firing-area-periods?date=" + millisAt(day, 12))
                .then().statusCode(400).body("detail", containsString("The firing periods must be specified"));
        assertEquals(before, rows(periods(areaId, day)), "the day's period is kept");

        // An empty list is "no periods this day": the day is emptied, and the answer carries an empty list
        putPeriods(areaId, day, List.of()).statusCode(200).body("firingPeriods.size()", is(0));
        assertEquals(List.of(), periods(areaId, day));
    }

    // ------------------------------------------------------------------------------------------- //
    // FIRING8: a day's save that names the periods it loaded deletes only those of them it left out, so a
    // period another tab added meanwhile is kept; a body without the list replaces the day, as before
    // ------------------------------------------------------------------------------------------- //

    private static io.restassured.response.ValidatableResponse putPeriods(Integer areaId, LocalDate day,
                                                                          List<Map<String, Object>> periods,
                                                                          List<Integer> loadedPeriodIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("area", Map.of("id", areaId));
        body.put("firingPeriods", periods);
        body.put("loadedPeriodIds", loadedPeriodIds);
        return inFiringAreas().body(body)
                .when().put("/rest/firing-schedules/firing-area-periods?date=" + millisAt(day, 12))
                .then();
    }

    private static List<Integer> ids(List<Map<String, Object>> periods) {
        return periods.stream().map(p -> ((Number) p.get("id")).intValue()).toList();
    }

    @Test
    void aSaveFromAnOlderTabKeepsThePeriodAnotherTabAdded() {
        Integer areaId = firingArea();
        LocalDate day = FIRST_TEST_DAY.plusDays(19);
        putPeriods(areaId, day, List.of(period(null, millisAt(day, 6), millisAt(day, 8)))).statusCode(200);
        Integer first = ids(periods(areaId, day)).getFirst();

        // Tabs A and B both load the day: its one period
        List<Integer> loadedByA = ids(periods(areaId, day));
        List<Integer> loadedByB = ids(periods(areaId, day));

        // Tab A adds a period and saves
        putPeriods(areaId, day, List.of(
                        period(first, millisAt(day, 6), millisAt(day, 8)),
                        period(null, millisAt(day, 10), millisAt(day, 12))),
                loadedByA).statusCode(200);
        List<List<Long>> afterA = rows(periods(areaId, day));
        assertEquals(2, afterA.size(), "tab A's period is stored");
        long addedByA = afterA.get(1).getFirst();

        // Tab B, which never saw A's period, moves the first period's start and saves
        putPeriods(areaId, day, List.of(period(first, millisAt(day, 5), millisAt(day, 8))), loadedByB)
                .statusCode(200).body("firingPeriods.size()", is(2));

        assertEquals(List.of(
                        List.of((long) first, millisAt(day, 5), millisAt(day, 8)),
                        List.of(addedByA, millisAt(day, 10), millisAt(day, 12))),
                rows(periods(areaId, day)), "B's change is saved and A's period is kept");
    }

    @Test
    void aLoadedPeriodLeftOutOfTheSaveIsDeleted() {
        Integer areaId = firingArea();
        LocalDate day = FIRST_TEST_DAY.plusDays(21);
        putPeriods(areaId, day, List.of(
                period(null, millisAt(day, 6), millisAt(day, 8)),
                period(null, millisAt(day, 10), millisAt(day, 12)))).statusCode(200);
        List<Map<String, Object>> loaded = periods(areaId, day);
        assertEquals(2, loaded.size());
        Integer kept = ids(loaded).get(0);

        // The second loaded period is removed in the editor; the first is saved unchanged
        putPeriods(areaId, day, List.of(period(kept, millisAt(day, 6), millisAt(day, 8))), ids(loaded))
                .statusCode(200).body("firingPeriods.size()", is(1));
        assertEquals(List.of(List.of((long) kept, millisAt(day, 6), millisAt(day, 8))), rows(periods(areaId, day)),
                "the removed period is deleted");

        // Every loaded period removed: the day is emptied
        putPeriods(areaId, day, List.of(), List.of(kept)).statusCode(200).body("firingPeriods.size()", is(0));
        assertEquals(List.of(), periods(areaId, day));
    }

    // ------------------------------------------------------------------------------------------- //
    // L-103 (decision 12): a period stored with its end before its start is read as ending the next day, so
    // the next day lists its early hours, and that day's save may send it back as it is stored
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aStoredBackwardPeriodIsListedOnTheNextDayAndThatDaysSaveKeepsIt() {
        Integer areaId = firingArea();
        LocalDate day = FIRST_TEST_DAY.plusDays(23);
        LocalDate nextDay = day.plusDays(1);
        // "22:00 - 02:00" of one day, as the editor stored an overnight period before FIRING2 was fixed
        Integer storedId = Tx.requiringNew().call(() -> {
            FiringPeriod fp = new FiringPeriod();
            fp.setArea(em.find(Area.class, areaId));
            fp.setFromDate(at(day, 22));
            fp.setToDate(at(day, 2));
            em.persist(fp);
            em.flush();
            return fp.getId();
        });
        List<List<Long>> storedRow = List.of(List.of((long) storedId, millisAt(day, 22), millisAt(day, 2)));

        assertEquals(storedRow, rows(periods(areaId, day)), "listed on its start day");
        List<Map<String, Object>> listed = periods(areaId, nextDay);
        assertEquals(storedRow, rows(listed), "listed on the next day, for its early hours");
        assertEquals(List.of(), periods(areaId, nextDay.plusDays(1)), "not on the day after that");

        // The next day's save sends it back as stored, beside a new period: 200, and it is kept
        List<Map<String, Object>> save = new ArrayList<>(listed);
        save.add(period(null, millisAt(nextDay, 10), millisAt(nextDay, 12)));
        putPeriods(areaId, nextDay, save, ids(listed)).statusCode(200).body("firingPeriods.size()", is(2));
        assertEquals(storedRow, rows(periods(areaId, day)), "the backward period is kept as stored");
        assertEquals(2, periods(areaId, nextDay).size(), "the next day's new period is stored");

        // Changed on the next day, it is refused
        putPeriods(areaId, nextDay, List.of(period(storedId, millisAt(day, 22), millisAt(day, 3))), ids(listed))
                .statusCode(400).body("detail", containsString("must end after it starts"));
        assertEquals(storedRow, rows(periods(areaId, day)), "the row is as it was");
    }

    private int scheduleCount() {
        return given().when().get("/rest/firing-schedules/all").then().statusCode(200)
                .extract().jsonPath().getList("").size();
    }

    @Test
    void aFiringScheduleWithoutAKnownDomainOrSeriesAnswers400() {
        int before = scheduleCount();
        given().auth().oauth2(accessToken()).contentType(ContentType.JSON).body(Map.of())
                .when().post("/rest/firing-schedules/firing-schedule/")
                .then().statusCode(400).body("detail", containsString("must name its domain"));
        given().auth().oauth2(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("domain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetDomain", Map.of("domainId", SEED_FA_DOMAIN_ID)))
                .when().post("/rest/firing-schedules/firing-schedule/")
                .then().statusCode(400).body("detail", containsString("must name its target message series"));
        given().auth().oauth2(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("domain", Map.of("domainId", "it-no-such-domain"),
                        "targetDomain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetSeriesId", SEED_FA_SERIES_ID))
                .when().post("/rest/firing-schedules/firing-schedule/")
                .then().statusCode(400).body("detail", containsString("No domain it-no-such-domain"));
        given().auth().oauth2(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("domain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetDomain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                        "targetSeriesId", "it-no-such-series"))
                .when().post("/rest/firing-schedules/firing-schedule/")
                .then().statusCode(400).body("detail", containsString("No message series it-no-such-series"));
        assertEquals(before, scheduleCount(), "no schedule is created");
    }

    @Test
    void aSecondFiringScheduleForADomainAnswers409() {
        Map<String, Object> body = Map.of(
                "domain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                "targetDomain", Map.of("domainId", SEED_FA_DOMAIN_ID),
                "targetSeriesId", SEED_FA_SERIES_ID,
                "scheduleDays", 7,
                "active", true);
        int id = given().auth().oauth2(accessToken()).contentType(ContentType.JSON).body(body)
                .when().post("/rest/firing-schedules/firing-schedule/")
                .then().statusCode(200).extract().path("id");
        createdScheduleIds.add(id);

        given().auth().oauth2(accessToken()).contentType(ContentType.JSON).body(body)
                .when().post("/rest/firing-schedules/firing-schedule/")
                .then().statusCode(409)
                .body("detail", containsString("The domain " + SEED_FA_DOMAIN_ID + " already has a firing schedule"));

        given().when().get("/rest/firing-schedules/all").then().statusCode(200)
                .body("findAll { it.domain.domainId == '" + SEED_FA_DOMAIN_ID + "' }.size()", is(1));
    }

    // ------------------------------------------------------------------------------------------- //
    // FIRING9: a schedule's days are 1 or more, or empty (the firingExerciseScheduleDays setting). A value
    // below 1 would end the run's window before today, and the next hourly run would cancel every current
    // firing exercise of the schedule.
    // ------------------------------------------------------------------------------------------- //

    private static Map<String, Object> scheduleBody(Integer id, Integer days) {
        Map<String, Object> body = new HashMap<>();
        if (id != null) {
            body.put("id", id);
        }
        body.put("domain", Map.of("domainId", SEED_FA_DOMAIN_ID));
        body.put("targetDomain", Map.of("domainId", SEED_FA_DOMAIN_ID));
        body.put("targetSeriesId", SEED_FA_SERIES_ID);
        body.put("scheduleDays", days);
        body.put("active", false);
        return body;
    }

    @Test
    void aFiringScheduleWithDaysBelowOneIsNotCreated() {
        int before = scheduleCount();
        for (int days : new int[] {-3, 0}) {
            given().auth().oauth2(accessToken()).contentType(ContentType.JSON).body(scheduleBody(null, days))
                    .when().post("/rest/firing-schedules/firing-schedule/")
                    .then().statusCode(400).body("detail", containsString("The schedule days must be 1 or more"));
        }
        assertEquals(before, scheduleCount(), "no schedule is created");
    }

    @Test
    void aFiringScheduleWithoutDaysIsCreatedAndItsDaysCannotBeSetBelowOne() {
        // Empty days stay allowed: the schedule then uses the firingExerciseScheduleDays setting
        int id = given().auth().oauth2(accessToken()).contentType(ContentType.JSON).body(scheduleBody(null, null))
                .when().post("/rest/firing-schedules/firing-schedule/")
                .then().statusCode(200).body("scheduleDays", nullValue()).extract().path("id");
        createdScheduleIds.add(id);

        for (int days : new int[] {-3, 0}) {
            given().auth().oauth2(accessToken()).contentType(ContentType.JSON).body(scheduleBody(id, days))
                    .when().put("/rest/firing-schedules/firing-schedule/" + id)
                    .then().statusCode(400).body("detail", containsString("The schedule days must be 1 or more"));
        }
        given().when().get("/rest/firing-schedules/all").then().statusCode(200)
                .body("find { it.id == " + id + " }.scheduleDays", nullValue());

        given().auth().oauth2(accessToken()).contentType(ContentType.JSON).body(scheduleBody(id, 1))
                .when().put("/rest/firing-schedules/firing-schedule/" + id)
                .then().statusCode(200).body("scheduleDays", is(1));
    }
}
