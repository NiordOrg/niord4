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
package org.niord.core.schedule;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.message.Message;
import org.niord.core.message.MessageSearchParams;
import org.niord.core.message.MessageSearchService;
import org.niord.core.promulgation.PromulgationException;
import org.niord.core.promulgation.PromulgationManager;
import org.niord.core.schedule.FiringExerciseService.FiringRunResult;
import org.niord.model.message.Status;
import org.niord.test.DeliveryFixtures;
import org.niord.test.DeliveryFixtures.FiringDomains;
import org.niord.test.DeliveryFixtures.FiringExercise;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.niord.test.DeliveryFixtures.futureDay;

/**
 * The firing-exercise run when two runs of one schedule meet (FIRING7), and what a run answers when part of it fails
 * (FIRING6).
 *
 * <p><b>Two runs.</b> The hourly job and a press of <em>Update Firing Exercises</em>, two presses, or two replicas.
 * The promulgation step of the exercise's creation is a spy that holds a run there until the other run has reached it
 * too, or for {@link #HOLD_SECONDS} seconds. Without a lock both runs read "no current exercise" and both reach the
 * creation, so both exercises are issued. With the lock the second run waits for the first one's schedule to commit,
 * reads the exercise it created and creates none.
 *
 * <p><b>Failures.</b> A schedule whose template search fails, a firing area whose exercise cannot be created and an
 * exercise that cannot be cancelled are each counted in the run's answer, which the button's REST call answers with
 * (200 and the three counts, where it answered 204 whatever happened).
 *
 * <p>The spies give this class an application context of its own; {@link DirtiesContext} closes it after the class,
 * as in {@code DeliveryFaultInjectionITTest}. The fixtures are committed with {@link DeliveryFixtures} into series,
 * domains and firing areas of their own and removed after each test. The run is called through its seam,
 * {@code updateFiringExercises(Set)}, with the test's own schedule only; the REST call runs every active schedule,
 * so the schedule list is narrowed to the test's own schedule there.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FiringExerciseRunLockITTest extends ITTest {

    /** How long the spy holds a run in the exercise's creation, waiting for the other run */
    private static final int HOLD_SECONDS = 3;

    private static final FiringRunResult CLEAN = new FiringRunResult(0, 0, 0);

    @MockitoSpyBean
    PromulgationManager promulgationManager;

    @MockitoSpyBean
    MessageSearchService messageSearchService;

    @MockitoSpyBean
    FiringScheduleService firingScheduleService;

    @Autowired
    FiringExerciseService service;

    @Autowired
    javax.sql.DataSource dataSource;

    @Autowired
    ApplicationContext context;

    @PersistenceContext
    EntityManager em;

    private DeliveryFixtures fixtures;

    @BeforeEach
    void newFixtures() {
        fixtures = new DeliveryFixtures(context, em);
    }

    @AfterEach
    void removeTheFixtures() {
        fixtures.removeAll();
    }

    /** A schedule over domains of its own: one firing area with a template and a period three days ahead */
    private record OneArea(FiringDomains d, Area area, Integer periodId, Integer schedule) {
    }

    private OneArea oneArea(String name) {
        FiringDomains d = fixtures.firingDomains();
        Area area = fixtures.firingArea("IT-FE " + name + " " + DeliveryFixtures.uniq() + " area");
        fixtures.firingTemplate(d.sourceSeries(), area, "IT-FE " + name + " template");
        Integer periodId = fixtures.firingPeriod(area, futureDay(3, 8), futureDay(3, 12));
        Integer schedule = fixtures.firingSchedule(d, 30, true);
        return new OneArea(d, area, periodId, schedule);
    }

    private List<FiringExercise> exercises(OneArea f, Status status) {
        return fixtures.firingExercises(f.d().targetSeries(), f.area(), status);
    }

    /** Whether the message is a generated exercise of the target series in the area */
    private static boolean isExerciseOf(Message m, OneArea f) {
        return m != null && m.getMessageSeries() != null
                && f.d().targetSeries().equals(m.getMessageSeries().getSeriesId())
                && m.getAreas().stream().anyMatch(a -> f.area().getId().equals(a.getId()));
    }

    // ------------------------------------------------------------------------------------------- //
    // FIRING7: two runs of one schedule
    // ------------------------------------------------------------------------------------------- //

    @Test
    void twoRunsOfOneScheduleStartedTogetherIssueOneExerciseForAnAreaThatHadNone() throws Exception {
        OneArea f = oneArea("lock");

        // A run that reaches the creation of the area's exercise waits there for the other run
        CountDownLatch bothCreating = new CountDownLatch(2);
        AtomicInteger creations = new AtomicInteger();
        doAnswer(inv -> {
            creations.incrementAndGet();
            bothCreating.countDown();
            bothCreating.await(HOLD_SECONDS, TimeUnit.SECONDS);
            return inv.callRealMethod();
        }).when(promulgationManager).onCreateMessage(argThat((Message m) -> isExerciseOf(m, f)));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<FiringRunResult> results = new ArrayList<>();
        try {
            List<Future<FiringRunResult>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return service.updateFiringExercises(Set.of(f.schedule()));
                }));
            }
            start.countDown();
            for (Future<FiringRunResult> run : runs) {
                results.add(run.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, exercises(f, Status.PUBLISHED).size(),
                "two runs of one schedule must issue one exercise for an area that had none");
        assertTrue(exercises(f, Status.CANCELLED).isEmpty(), "and cancel none");
        assertEquals(1, creations.get(),
                "the second run must read the exercise the first one created, and create none");
        assertEquals(List.of(CLEAN, CLEAN), results, "and neither run fails");
    }

    // ------------------------------------------------------------------------------------------- //
    // FIRING6: the run answers what failed
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aScheduleWhoseTemplateSearchFailsIsCountedFailed() throws Exception {
        OneArea f = oneArea("failed schedule");
        doThrow(new jakarta.persistence.PersistenceException("IT: the template search failed"))
                .when(messageSearchService)
                .searchOrFail(argThat((MessageSearchParams p) -> p != null && p.getSeriesIds() != null
                        && p.getSeriesIds().contains(f.d().sourceSeries())));

        FiringRunResult result = service.updateFiringExercises(Set.of(f.schedule()));

        assertEquals(new FiringRunResult(1, 0, 0), result, "the skipped schedule is counted");
        assertTrue(exercises(f, Status.PUBLISHED).isEmpty(), "and nothing of it is issued");
    }

    @Test
    void anAreaWhoseExerciseCannotBeCreatedIsCountedFailed() throws Exception {
        OneArea f = oneArea("failed area");
        doThrow(new PromulgationException("IT: the exercise is refused"))
                .when(promulgationManager).onCreateMessage(argThat((Message m) -> isExerciseOf(m, f)));

        FiringRunResult result = service.updateFiringExercises(Set.of(f.schedule()));

        assertEquals(new FiringRunResult(0, 1, 0), result, "the area whose exercise was not created is counted");
        assertEquals(1, result.failures());
        assertTrue(exercises(f, Status.PUBLISHED).isEmpty(), "and no exercise is issued");
    }

    @Test
    void anExerciseThatCannotBeCancelledIsCountedFailed() throws Exception {
        OneArea f = oneArea("failed cancellation");
        assertEquals(CLEAN, service.updateFiringExercises(Set.of(f.schedule())), "the first run issues the exercise");
        String uid = exercises(f, Status.PUBLISHED).get(0).uid();

        // The firing is withdrawn, so the next run cancels the exercise; the cancellation is refused
        assertTrue(Tx.requiringNew().call(() -> firingScheduleService.deleteFiringPeriod(f.periodId())),
                "the firing period must be deleted");
        doThrow(new PromulgationException("IT: the cancellation is refused"))
                .when(promulgationManager).onUpdateMessageStatus(argThat((Message m) -> m != null
                        && uid.equals(m.getUid()) && m.getStatus() == Status.CANCELLED));

        FiringRunResult result = service.updateFiringExercises(Set.of(f.schedule()));

        assertEquals(new FiringRunResult(0, 0, 1), result, "the exercise that was not cancelled is counted");
        assertEquals(Status.PUBLISHED, fixtures.statusOf(uid), "and stays in force until the next run");
    }

    /**
     * A firing-area template with a position off the globe (the review's finding R6, with P1-12): a template row
     * stored before the position check, with a latitude of 95 in a part's geometry. The run refuses the position when
     * it copies the template, and the refusal used to fail the whole schedule, every hour: no area of it got its
     * exercise. Now it costs that one area, counted among the failed areas; the schedule's other area gets its
     * exercise.
     */
    @Test
    void aTemplateWithAPositionOffTheGlobeCostsOneAreaNotTheSchedule() throws Exception {
        FiringDomains d = fixtures.firingDomains();
        Area bad = fixtures.firingArea("IT-FE off the globe " + DeliveryFixtures.uniq() + " area");
        Area good = fixtures.firingArea("IT-FE on the globe " + DeliveryFixtures.uniq() + " area");
        String badTemplate = fixtures.firingTemplate(d.sourceSeries(), bad, "IT-FE off the globe template");
        fixtures.firingTemplate(d.sourceSeries(), good, "IT-FE on the globe template");
        fixtures.firingPeriod(bad, futureDay(3, 8), futureDay(3, 12));
        fixtures.firingPeriod(good, futureDay(3, 8), futureDay(3, 12));
        Integer schedule = fixtures.firingSchedule(d, 30, true);

        // A stored row as an older Niord kept it: the entity takes the point as it is, past the check of the
        // GeoJSON conversion
        Tx.requiringNew().run(() -> {
            Message template = em.createQuery("select m from Message m where m.uid = :uid", Message.class)
                    .setParameter("uid", badTemplate).getSingleResult();
            org.niord.core.geojson.Feature feature = new org.niord.core.geojson.Feature();
            feature.assignNewUid();
            feature.setGeometry(new org.locationtech.jts.geom.GeometryFactory(
                    new org.locationtech.jts.geom.PrecisionModel(), 4326)
                    .createPoint(new org.locationtech.jts.geom.Coordinate(10.0, 95.0)));
            org.niord.core.geojson.FeatureCollection geometry = new org.niord.core.geojson.FeatureCollection();
            geometry.assignNewUid();
            geometry.addFeature(feature);
            org.niord.core.message.MessagePart part =
                    new org.niord.core.message.MessagePart(org.niord.model.message.MessagePartType.DETAILS);
            part.setGeometry(geometry);
            template.addPart(part);
            em.flush();
        });

        FiringRunResult result = service.updateFiringExercises(Set.of(schedule));

        assertEquals(new FiringRunResult(0, 1, 0), result,
                "the area whose template cannot be copied is counted, not the schedule");
        assertEquals(1, fixtures.firingExercises(d.targetSeries(), good, Status.PUBLISHED).size(),
                "the schedule's other area gets its exercise");
        assertTrue(fixtures.firingExercises(d.targetSeries(), bad, Status.PUBLISHED).isEmpty(),
                "and the area of the template off the globe none");
    }

    /**
     * The run's lock outlives a database that ends sessions idle in a transaction (the review's finding R8, with
     * P1-16). The schedule's transaction takes the lock and then runs no statement while its areas' units work on
     * other connections. A database role with {@code idle_in_transaction_session_timeout} shorter than that would end
     * the schedule's session, and the lock with it, in mid-run: a second run could then start beside the first, and
     * the first run's schedule was counted failed at its commit although its units had committed.
     *
     * <p>Here the role's timeout is one second and the area's unit takes three (an active statement on the unit's own
     * connection, so only the schedule's session sits idle). The schedule's transaction switches the timeout off for
     * itself, so the run ends clean. The role's setting is put back afterwards, and the pool's connections renewed
     * both times.
     */
    @Test
    void theRunsLockSurvivesADatabaseThatEndsSessionsIdleInATransaction() throws Exception {
        OneArea f = oneArea("idle");
        doAnswer(inv -> {
            em.createNativeQuery("select 1 from pg_sleep(3)").getResultList();
            return inv.callRealMethod();
        }).when(promulgationManager).onCreateMessage(argThat((Message m) -> isExerciseOf(m, f)));

        com.zaxxer.hikari.HikariDataSource pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        String role;
        try (java.sql.Connection c = dataSource.getConnection();
             java.sql.ResultSet rs = c.createStatement().executeQuery("select current_user")) {
            rs.next();
            role = rs.getString(1);
        }
        FiringRunResult result;
        try {
            try (java.sql.Connection c = dataSource.getConnection()) {
                c.createStatement().execute("ALTER ROLE \"" + role + "\" SET idle_in_transaction_session_timeout = '1s'");
            }
            pool.getHikariPoolMXBean().softEvictConnections();

            result = service.updateFiringExercises(Set.of(f.schedule()));
        } finally {
            try (java.sql.Connection c = dataSource.getConnection()) {
                c.createStatement().execute("ALTER ROLE \"" + role + "\" RESET idle_in_transaction_session_timeout");
            }
            pool.getHikariPoolMXBean().softEvictConnections();
        }

        assertEquals(CLEAN, result, "the schedule's transaction keeps its session, its lock and its commit");
        assertEquals(1, exercises(f, Status.PUBLISHED).size(), "and the area's exercise is issued");
    }

    /**
     * The editor's and the sysadmin's button: the REST call answers 200 with the run's counts, which the screens
     * read to show a warning instead of their success toast. The call runs every active schedule; the schedule list
     * is narrowed to this test's own, so no other committed schedule is run.
     */
    @Test
    void theUpdateCallAnswersTheRunsFailures() throws Exception {
        OneArea f = oneArea("rest");
        doAnswer(inv -> ((List<?>) inv.callRealMethod()).stream()
                .filter(s -> f.schedule().equals(((FiringSchedule) s).getId()))
                .toList())
                .when(firingScheduleService).getFiringSchedules();
        doThrow(new PromulgationException("IT: the exercise is refused"))
                .when(promulgationManager).onCreateMessage(argThat((Message m) -> isExerciseOf(m, f)));

        given().auth().oauth2(accessToken("editor"))
                .when().put("/rest/firing-schedules/update-firing-exercises")
                .then().statusCode(200)
                .body("size()", equalTo(3))
                .body("failedSchedules", equalTo(0))
                .body("failedAreas", equalTo(1))
                .body("failedCancellations", equalTo(0));
    }
}
