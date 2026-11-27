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
import org.niord.core.util.TimeUtils;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.DeliveryFixtures;
import org.niord.test.DeliveryFixtures.FiringDomains;
import org.niord.test.DeliveryFixtures.FiringExercise;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.niord.test.DeliveryFixtures.futureDay;

/**
 * Integration tests for the hourly firing-exercise run, {@link FiringExerciseService#updateFiringExercises()}.
 *
 * <p>The run:
 * <ul>
 *     <li>loads the active {@link FiringSchedule}s,</li>
 *     <li>for each schedule, finds the PUBLISHED firing-area template messages of the source domain and
 *         all future {@link FiringPeriod}s for the firing areas,</li>
 *     <li>generates one PUBLISHED firing-exercise message in the target series per template that has at
 *         least one firing period in the window (its TIME part carrying the period's event dates), and</li>
 *     <li>cancels any previously-published exercise message that no longer matches a current period.</li>
 * </ul>
 *
 * <p><b>Committed fixtures.</b> The run reads each schedule in a transaction of its own and creates or cancels each
 * area's exercise in a transaction of that area's own, so rows written inside
 * a test's own transaction are invisible to it. Each test therefore commits its fixtures with {@link DeliveryFixtures}:
 * a source series and domain and a target series and domain of its own, firing areas, templates, periods and a
 * schedule, all removed after the test with the exercises the run issued. The run is called through its seam,
 * {@code updateFiringExercises(Set)}, with the test's own schedule only, never through the cron method, which would
 * run every active schedule of the database; the cron's timer is switched off for the suite
 * ({@code niord.schedule.firing-exercise-cron=-} on ITTest), so it cannot run inside a test either. The results are read back committed, through
 * {@link DeliveryFixtures#firingExercises}.
 *
 * <p>The firing-area search of a source domain without areas of its own applies no lineage filter and returns every
 * active firing area, the one each test creates among them; the template and exercise searches are narrowed to the
 * test's own series, so no test can collide with another or with the seed.
 *
 * <p>The target series is NW, so the generated exercise messages come out as {@link MainType#NW} /
 * {@link Type#LOCAL_WARNING}.
 */
class FiringExerciseServiceITTest extends ITTest {

    @Autowired
    FiringExerciseService service;

    @Autowired
    FiringScheduleService firingScheduleService;

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

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** A committed firing area with a committed PUBLISHED template in the source series */
    private Area areaWithTemplate(FiringDomains d, String name) {
        Area area = fixtures.firingArea(name + " area");
        fixtures.firingTemplate(d.sourceSeries(), area, name + " template");
        return area;
    }

    /** A committed active schedule over the test's domains, 60 days ahead */
    private Integer activeSchedule(FiringDomains d) {
        return fixtures.firingSchedule(d, 60, true);
    }

    /** One run over the given schedule only, and what it left undone */
    private FiringExerciseService.FiringRunResult run(Integer scheduleId) {
        return service.updateFiringExercises(Set.of(scheduleId));
    }

    /** The answer of a run that did everything */
    private static final FiringExerciseService.FiringRunResult CLEAN = new FiringExerciseService.FiringRunResult(0, 0, 0);

    /** The committed exercises of the target series in the area, with the given status */
    private List<FiringExercise> exercises(FiringDomains d, Area area, Status status) {
        return fixtures.firingExercises(d.targetSeries(), area, status);
    }

    // ------------------------------------------------------------------------------------------- //
    // Active schedule + a future firing period -> a new PUBLISHED exercise message
    // ------------------------------------------------------------------------------------------- //

    @Test
    void activeScheduleWithFuturePeriodPublishesExerciseMessageWithTimePartAndPublishDateTo() {
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE " + DeliveryFixtures.uniq());
        Date from = futureDay(3, 8);
        Date to = futureDay(3, 12);
        fixtures.firingPeriod(area, from, to);
        Integer schedule = activeSchedule(d);

        // No exercise message should exist before the run.
        assertTrue(exercises(d, area, Status.PUBLISHED).isEmpty(),
                "precondition: there must be no exercise message for this brand-new area yet");

        assertEquals(CLEAN, run(schedule), "a run that issued the exercise answers no failure");

        List<FiringExercise> published = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, published.size(),
                "exactly one PUBLISHED exercise message must be generated for the area's single future period");

        FiringExercise ex = published.get(0);
        assertEquals(Status.PUBLISHED, ex.status(), "the generated exercise must be PUBLISHED");
        assertTrue(ex.carries(area), "the exercise must carry the firing area copied from the template");

        // The TIME part must carry exactly the firing period as an event date.
        assertEquals(1, ex.eventDates().size(),
                "the TIME part must carry exactly the one firing-period date interval");
        assertEquals(from, ex.eventDates().get(0)[0], "the event from-date must equal the period from-date");
        assertEquals(to, ex.eventDates().get(0)[1], "the event to-date must equal the period to-date");

        // publishDateTo is set to the aggregate event end date (i.e. the period's to-date).
        assertEquals(to, ex.publishDateTo(),
                "publishDateTo must be set to the last event date (the firing period's to-date)");
    }

    // ------------------------------------------------------------------------------------------- //
    // A template without any description still gives its exercise
    // ------------------------------------------------------------------------------------------- //

    /**
     * A template message without any description (no title, no vicinity), which the mapper gives no list of them at
     * all, is built into its exercise like any other: the run answers no failure, and the exercise's title is made
     * from its area and its subject, as for a template with a title.
     */
    @Test
    void aTemplateWithoutAnyDescriptionStillGivesItsExercise() {
        FiringDomains d = fixtures.firingDomains();
        String name = "IT-FE nodesc " + DeliveryFixtures.uniq();
        Area area = fixtures.firingArea(name + " area");
        String template = fixtures.firingTemplate(d.sourceSeries(), area, name + " template");
        Tx.requiringNew().run(() -> em.createNamedQuery("Message.findByUid", Message.class)
                .setParameter("uid", template)
                .getSingleResult()
                .getDescs().clear());
        fixtures.firingPeriod(area, futureDay(3, 8), futureDay(3, 12));
        Integer schedule = activeSchedule(d);

        assertEquals(CLEAN, run(schedule), "a template without a description is no failure");

        List<FiringExercise> published = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, published.size(), "the area gets its exercise");
        String title = Tx.requiringNew().call(() -> em.createNamedQuery("Message.findByUid", Message.class)
                .setParameter("uid", published.get(0).uid())
                .getSingleResult()
                .getDesc("en").getTitle());
        assertTrue(title.startsWith(name + " area."), "the title is made from the area: " + title);
    }

    // ------------------------------------------------------------------------------------------- //
    // Generated exercise type/main-type for an NW target series
    // ------------------------------------------------------------------------------------------- //

    @Test
    void generatedExerciseMessageIsLocalWarningNwForNwTargetSeries() {
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE nw " + DeliveryFixtures.uniq());
        fixtures.firingPeriod(area, futureDay(5, 9), futureDay(5, 11));
        Integer schedule = activeSchedule(d);

        run(schedule);

        List<FiringExercise> published = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, published.size(), "one exercise message must be generated for the NW target series");
        FiringExercise ex = published.get(0);
        assertEquals(MainType.NW, ex.mainType(),
                "the exercise main type must be inherited from the NW target series");
        assertEquals(Type.LOCAL_WARNING, ex.type(),
                "an NW exercise message must be typed LOCAL_WARNING");
    }

    // ------------------------------------------------------------------------------------------- //
    // Area with a template but no firing period in the window -> no exercise message
    // ------------------------------------------------------------------------------------------- //

    @Test
    void templateWithoutFiringPeriodInWindowGeneratesNoExerciseMessage() {
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE nofp " + DeliveryFixtures.uniq());
        // NB: no firing period is created for this area, so fps.isEmpty() -> the area is skipped.
        Integer schedule = activeSchedule(d);

        run(schedule);

        assertTrue(exercises(d, area, Status.PUBLISHED).isEmpty(),
                "an area whose template has no firing period in the window must not get an exercise message");
    }

    // ------------------------------------------------------------------------------------------- //
    // Inactive schedule -> nothing happens
    // ------------------------------------------------------------------------------------------- //

    @Test
    void inactiveScheduleGeneratesNoExerciseMessage() {
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE inactive " + DeliveryFixtures.uniq());
        fixtures.firingPeriod(area, futureDay(4, 8), futureDay(4, 10));
        Integer schedule = fixtures.firingSchedule(d, 60, false); // INACTIVE: filtered out before any processing

        run(schedule);

        assertTrue(exercises(d, area, Status.PUBLISHED).isEmpty(),
                "an inactive schedule must not generate any exercise messages");
    }

    // ------------------------------------------------------------------------------------------- //
    // Re-run with unchanged periods -> exactly one exercise message remains (sameFiringPeriods)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void rerunWithUnchangedPeriodsKeepsExactlyOneExerciseMessage() {
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE rerun " + DeliveryFixtures.uniq());
        fixtures.firingPeriod(area, futureDay(6, 8), futureDay(6, 12));
        Integer schedule = activeSchedule(d);

        run(schedule);
        List<FiringExercise> firstRun = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, firstRun.size(), "the first run must publish exactly one exercise message");
        String firstUid = firstRun.get(0).uid();

        // Re-run with the very same firing periods: sameFiringPeriods() must short-circuit, so the existing
        // exercise message is kept (and NOT cancelled) and no new one is created.
        run(schedule);

        List<FiringExercise> publishedAfter = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, publishedAfter.size(),
                "re-running with unchanged periods must leave exactly one PUBLISHED exercise message");
        assertEquals(firstUid, publishedAfter.get(0).uid(),
                "the original exercise message must be the one kept (not replaced)");
        assertTrue(exercises(d, area, Status.CANCELLED).isEmpty(),
                "re-running with unchanged periods must not cancel the existing exercise message");
    }

    // ------------------------------------------------------------------------------------------- //
    // Re-run with changed periods -> old CANCELLED + new PUBLISHED with new dates
    // ------------------------------------------------------------------------------------------- //

    @Test
    void rerunWithChangedPeriodsCancelsOldAndPublishesNewWithNewDates() {
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE change " + DeliveryFixtures.uniq());
        Integer periodId = fixtures.firingPeriod(area, futureDay(7, 8), futureDay(7, 12));
        Integer schedule = activeSchedule(d);

        run(schedule);
        List<FiringExercise> firstRun = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, firstRun.size(), "the first run must publish exactly one exercise message");
        String firstUid = firstRun.get(0).uid();

        // Change the firing period's dates, then re-run.
        Date newFrom = futureDay(9, 14);
        Date newTo = futureDay(9, 18);
        Tx.requiringNew().run(() -> {
            FiringPeriod template = new FiringPeriod();
            template.setId(periodId);
            template.setArea(area);
            template.setFromDate(newFrom);
            template.setToDate(newTo);
            firingScheduleService.updateFiringPeriod(template);
        });

        assertEquals(CLEAN, run(schedule), "a run that replaced the exercise answers no failure");

        // The old exercise must be CANCELLED.
        List<FiringExercise> cancelled = exercises(d, area, Status.CANCELLED);
        assertEquals(1, cancelled.size(), "the stale exercise message must be cancelled");
        assertEquals(firstUid, cancelled.get(0).uid(),
                "the cancelled message must be the originally published one");

        // A new exercise must be PUBLISHED carrying the new dates.
        List<FiringExercise> published = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, published.size(),
                "a fresh exercise message must be published for the changed period");
        FiringExercise fresh = published.get(0);
        assertNotEquals(firstUid, fresh.uid(),
                "the re-published exercise must be a new message, not the cancelled one");
        assertEquals(1, fresh.eventDates().size(), "the new exercise must carry exactly the one changed period");
        assertEquals(newFrom, fresh.eventDates().get(0)[0], "the new event from-date must match the changed period");
        assertEquals(newTo, fresh.eventDates().get(0)[1], "the new event to-date must match the changed period");
    }

    // ------------------------------------------------------------------------------------------- //
    // Period no longer scheduled -> the stale exercise message is CANCELLED
    // ------------------------------------------------------------------------------------------- //

    @Test
    void rerunAfterRemovingPeriodCancelsStaleExerciseMessage() {
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE remove " + DeliveryFixtures.uniq());
        Integer periodId = fixtures.firingPeriod(area, futureDay(8, 8), futureDay(8, 12));
        Integer schedule = activeSchedule(d);

        run(schedule);
        List<FiringExercise> firstRun = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, firstRun.size(), "the first run must publish exactly one exercise message");
        String firstUid = firstRun.get(0).uid();

        // Remove the only firing period for the area, then re-run. The template now has no periods in the
        // window, so generateFiringExerciseMessages skips it (fps.isEmpty), and the previously published
        // exercise is left among the current exercises -> cancelled.
        assertTrue(Tx.requiringNew().call(() -> firingScheduleService.deleteFiringPeriod(periodId)),
                "the firing period must be deleted");

        assertEquals(CLEAN, run(schedule), "a run that cancelled the exercise answers no failure");

        assertTrue(exercises(d, area, Status.PUBLISHED).isEmpty(),
                "with no firing period left, the area must have no PUBLISHED exercise message");
        List<FiringExercise> cancelled = exercises(d, area, Status.CANCELLED);
        assertEquals(1, cancelled.size(),
                "the now-stale exercise message must be cancelled");
        assertEquals(firstUid, cancelled.get(0).uid(),
                "the cancelled message must be the originally published one");
    }

    // ------------------------------------------------------------------------------------------- //
    // Multiple future periods on one area are all carried into a single exercise message
    // ------------------------------------------------------------------------------------------- //

    @Test
    void exerciseMessageCarriesAllFuturePeriodsOfTheArea() {
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE multi " + DeliveryFixtures.uniq());
        Date from1 = futureDay(2, 8);
        Date to1 = futureDay(2, 10);
        Date from2 = futureDay(10, 14);
        Date to2 = futureDay(10, 16);
        fixtures.firingPeriod(area, from1, to1);
        fixtures.firingPeriod(area, from2, to2);
        Integer schedule = activeSchedule(d);

        run(schedule);

        List<FiringExercise> published = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, published.size(),
                "a single exercise message must aggregate all of the area's future periods");
        FiringExercise ex = published.get(0);

        List<Date[]> dates = ex.eventDates();
        assertEquals(2, dates.size(), "both future firing periods must be carried as event dates");
        assertEquals(from1, dates.get(0)[0], "the earliest period must come first (sorted)");
        assertEquals(to1, dates.get(0)[1]);
        assertEquals(from2, dates.get(1)[0], "the later period must come second (sorted)");
        assertEquals(to2, dates.get(1)[1]);

        // publishDateTo is the aggregate end, i.e. the last period's to-date.
        assertEquals(to2, ex.publishDateTo(),
                "publishDateTo must equal the latest event date across all periods");
    }

    // ------------------------------------------------------------------------------------------- //
    // A firing period entirely outside the schedule window does not produce an exercise
    // ------------------------------------------------------------------------------------------- //

    @Test
    void periodBeyondScheduleWindowGeneratesNoExerciseMessage() {
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE window " + DeliveryFixtures.uniq());
        // scheduleDays is 60 (see activeSchedule); a period ~200 days out is outside the window.
        fixtures.firingPeriod(area, futureDay(200, 8), futureDay(200, 12));
        Integer schedule = activeSchedule(d);

        run(schedule);

        assertTrue(exercises(d, area, Status.PUBLISHED).isEmpty(),
                "a firing period beyond the schedule window must not generate an exercise message");
    }

    // ------------------------------------------------------------------------------------------- //
    // A past firing period is not picked up (window starts at today)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void pastPeriodGeneratesNoExerciseMessage() {
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE past " + DeliveryFixtures.uniq());
        // A period fully in the past (three days ago) is before the window start (today).
        fixtures.firingPeriod(area, futureDay(-3, 8), futureDay(-3, 12));
        Integer schedule = activeSchedule(d);

        run(schedule);

        assertTrue(exercises(d, area, Status.PUBLISHED).isEmpty(),
                "a firing period entirely in the past must not generate an exercise message");
    }

    // ------------------------------------------------------------------------------------------- //
    // An area whose periods have all ended today
    // ------------------------------------------------------------------------------------------- //

    /** Now, to the second */
    private static Date nowToTheSecond() {
        long now = System.currentTimeMillis();
        return new Date(now - now % 1000);
    }

    /** The start of today in the JVM's zone, the start of the run's window */
    private static Date startOfToday() {
        return TimeUtils.resetTime(new Date());
    }

    /** Skips the test in the first seconds of the day, when no period can have ended today yet */
    private static void assumeSecondsHavePassedToday(Date now, int seconds) {
        assumeTrue(now.getTime() - startOfToday().getTime() > seconds * 1000L,
                "too close after midnight for a period that ended today");
    }

    /** Waits until the given time has passed */
    private static void waitUntilAfter(Date time) throws InterruptedException {
        while (System.currentTimeMillis() <= time.getTime() + 100) {
            Thread.sleep(100);
        }
    }

    /** The committed exercises of the target series in the area, in any status the run can leave */
    private int exerciseCount(FiringDomains d, Area area) {
        return exercises(d, area, Status.PUBLISHED).size()
                + exercises(d, area, Status.CANCELLED).size()
                + exercises(d, area, Status.EXPIRED).size();
    }

    @Test
    void aPeriodThatEndedEarlierTodayGeneratesNoExerciseMessage() {
        Date now = nowToTheSecond();
        assumeSecondsHavePassedToday(now, 10);
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE ended " + DeliveryFixtures.uniq());
        // Inside the window (from today's midnight), but over
        fixtures.firingPeriod(area, startOfToday(), new Date(now.getTime() - 1000));
        Integer schedule = activeSchedule(d);

        run(schedule);

        assertEquals(0, exerciseCount(d, area),
                "a firing whose periods have all ended must not be published again, with a publication end in the past");
    }

    @Test
    void aLiveExerciseWhoseLastPeriodEndedTodayIsLeftForTheExpirySweep() throws Exception {
        Date now = nowToTheSecond();
        assumeSecondsHavePassedToday(now, 10);
        FiringDomains d = fixtures.firingDomains();
        Date end = new Date(now.getTime() + 2000);
        assumeTrue(TimeUtils.endOfDay(now).getTime() - end.getTime() > 30_000L,
                "too close before midnight for a period that ends today");
        Area area = areaWithTemplate(d, "IT-FE over " + DeliveryFixtures.uniq());
        Integer periodId = fixtures.firingPeriod(area, startOfToday(), end);
        Integer schedule = activeSchedule(d);

        run(schedule);
        List<FiringExercise> live = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, live.size(), "the run must publish the exercise while its period is ahead");
        String uid = live.get(0).uid();
        assertEquals(end, live.get(0).publishDateTo(), "the exercise's publication ends with its period");

        // The period ends; the minute sweep has not expired the exercise yet
        waitUntilAfter(end);
        run(schedule);

        // The officer corrects the start of the firing that is over
        Tx.requiringNew().run(() -> {
            FiringPeriod corrected = new FiringPeriod();
            corrected.setId(periodId);
            corrected.setArea(area);
            corrected.setFromDate(new Date(startOfToday().getTime() + 1000));
            corrected.setToDate(end);
            firingScheduleService.updateFiringPeriod(corrected);
        });
        run(schedule);

        List<FiringExercise> published = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, published.size(), "the exercise must be left in force for the expiry sweep");
        assertEquals(uid, published.get(0).uid(), "the exercise left in force must be the one issued before");
        assertTrue(exercises(d, area, Status.CANCELLED).isEmpty(),
                "an exercise whose firing is over must be left for the expiry sweep, not cancelled");
        assertEquals(1, exerciseCount(d, area), "no new exercise must be issued for a firing that is over");
    }

    @Test
    void aLiveExerciseWhoseFiringAheadWasRemovedIsCancelledWhenItsOtherPeriodsHaveEnded() {
        Date now = nowToTheSecond();
        assumeSecondsHavePassedToday(now, 10);
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE withdrawn " + DeliveryFixtures.uniq());
        fixtures.firingPeriod(area, startOfToday(), new Date(now.getTime() - 1000));
        Integer ahead = fixtures.firingPeriod(area, futureDay(3, 8), futureDay(3, 12));
        Integer schedule = activeSchedule(d);

        run(schedule);
        List<FiringExercise> live = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, live.size(), "the run must publish the exercise while a period is ahead");
        assertEquals(2, live.get(0).eventDates().size(), "the exercise must name both periods");
        String uid = live.get(0).uid();

        // The firing ahead is withdrawn; only the period that is over is left
        assertTrue(Tx.requiringNew().call(() -> firingScheduleService.deleteFiringPeriod(ahead)),
                "the firing period ahead must be deleted");
        run(schedule);

        assertTrue(exercises(d, area, Status.PUBLISHED).isEmpty(),
                "an exercise that still announces a withdrawn firing must not stay in force, nor be replaced");
        List<FiringExercise> cancelled = exercises(d, area, Status.CANCELLED);
        assertEquals(1, cancelled.size(), "the exercise announcing the withdrawn firing must be cancelled");
        assertEquals(uid, cancelled.get(0).uid(), "the cancelled exercise must be the one issued before");
        assertEquals(1, exerciseCount(d, area), "no new exercise must be issued for a firing that is over");
    }

    // ------------------------------------------------------------------------------------------- //
    // A non-firing-area template (no FIRING_AREA association) is not turned into an exercise
    // ------------------------------------------------------------------------------------------- //

    @Test
    void nonFiringAreaTemplateInSourceSeriesIsIgnored() {
        FiringDomains d = fixtures.firingDomains();
        String n = DeliveryFixtures.uniq();
        // A firing area that DOES have a future period (so the window query has rows), plus a template
        // message in the source series that is NOT associated with any firing area. The non-firing template must be
        // filtered out by getFiringAreaMessages (it requires an active FIRING_AREA association).
        Area firingArea = fixtures.firingArea("IT-FE mixed area " + n);
        fixtures.firingPeriod(firingArea, futureDay(3, 8), futureDay(3, 12));

        // Template with no areas at all.
        String plain = fixtures.firingTemplate(d.sourceSeries(), null, "IT-FE plain " + n);

        Integer schedule = activeSchedule(d);

        run(schedule);

        // The plain template carries no firing area, so it never produces an exercise message. The firing
        // area itself has a period but no PUBLISHED template message, so it produces nothing either.
        assertTrue(exercises(d, firingArea, Status.PUBLISHED).isEmpty(),
                "the firing area without a template message must have no exercise message");

        // Sanity: the plain template still exists and is untouched.
        assertEquals(Status.PUBLISHED, fixtures.statusOf(plain),
                "the non-firing-area template must remain an untouched PUBLISHED message");
    }
}
