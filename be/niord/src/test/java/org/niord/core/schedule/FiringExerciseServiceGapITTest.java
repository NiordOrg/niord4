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
import org.niord.core.message.MessageKind;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageService;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.model.message.Status;
import org.niord.test.DeliveryFixtures;
import org.niord.test.DeliveryFixtures.FiringDomains;
import org.niord.test.DeliveryFixtures.FiringExercise;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.niord.test.DeliveryFixtures.futureDay;

/**
 * Additional integration tests for {@link FiringExerciseService#updateFiringExercises()} closing the gaps left
 * by {@link FiringExerciseServiceITTest}. That sibling already covers the single-schedule / single-area
 * reconciliation loop (publish, skip, cancel, re-run, multi-period aggregation). The cases here exercise the
 * remaining observable behaviour:
 *
 * <ul>
 *     <li><b>The inner per-template loop produces one exercise per firing-area template.</b> Two firing areas,
 *         each with its own PUBLISHED template and its own future period, under a single schedule yield two
 *         distinct PUBLISHED exercise messages, each carrying only its own area and only its own period.</li>
 *     <li><b>{@code formatTimeDescription} actually renders text.</b> The generated TIME part carries an {@code en}
 *         description whose {@code details} is a non-blank, period-derived string (terminated with {@code "."},
 *         containing a {@code "-"} range separator and the firing period's calendar year).</li>
 *     <li><b>A stale exercise is cancelled without a cancellation warning</b>, even in an auto-numbered NW series.</li>
 * </ul>
 *
 * <p>The fixtures are committed and removed after each test, and the run is called with the test's own schedule
 * only, as in {@link FiringExerciseServiceITTest}, whose Javadoc explains why.
 */
class FiringExerciseServiceGapITTest extends ITTest {

    @Autowired
    FiringExerciseService service;

    @Autowired
    FiringScheduleService firingScheduleService;

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

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

    /** The committed exercises of the target series in the area, with the given status */
    private List<FiringExercise> exercises(FiringDomains d, Area area, Status status) {
        return fixtures.firingExercises(d.targetSeries(), area, status);
    }

    // ------------------------------------------------------------------------------------------- //
    // Two firing areas under one schedule -> two distinct exercise messages
    // ------------------------------------------------------------------------------------------- //

    @Test
    void twoFiringAreasUnderOneScheduleProduceTwoDistinctExerciseMessages() {
        FiringDomains d = fixtures.firingDomains();
        String n = DeliveryFixtures.uniq();

        // Two independent firing areas, each with its own PUBLISHED template and its own distinct future period.
        Area areaA = areaWithTemplate(d, "IT-FE A " + n);
        Date fromA = futureDay(3, 8);
        Date toA = futureDay(3, 12);
        fixtures.firingPeriod(areaA, fromA, toA);

        Area areaB = areaWithTemplate(d, "IT-FE B " + n);
        Date fromB = futureDay(15, 14);
        Date toB = futureDay(15, 18);
        fixtures.firingPeriod(areaB, fromB, toB);

        Integer schedule = fixtures.firingSchedule(d, 60, true);

        service.updateFiringExercises(Set.of(schedule));

        // The inner per-template loop runs once per firing-area template, so each area gets its own exercise.
        List<FiringExercise> publishedA = exercises(d, areaA, Status.PUBLISHED);
        assertEquals(1, publishedA.size(), "area A must get exactly one exercise message");
        FiringExercise exA = publishedA.get(0);

        // Exercise A must carry only firing area A, not area B.
        assertTrue(exA.carries(areaA), "exercise A must carry firing area A");
        assertTrue(!exA.carries(areaB), "exercise A must NOT carry firing area B");

        // Exercise A must carry only area A's period, so periods are not aggregated across areas.
        assertEquals(1, exA.eventDates().size(), "exercise A must carry exactly area A's single period");
        assertEquals(fromA, exA.eventDates().get(0)[0], "exercise A's event must equal area A's period (from)");
        assertEquals(toA, exA.eventDates().get(0)[1], "exercise A's event must equal area A's period (to)");

        List<FiringExercise> publishedB = exercises(d, areaB, Status.PUBLISHED);
        assertEquals(1, publishedB.size(), "area B must get exactly one exercise message");
        FiringExercise exB = publishedB.get(0);

        assertNotEquals(exA.uid(), exB.uid(),
                "the two firing areas must produce two distinct exercise messages, not one shared message");

        // Exercise B must carry only firing area B, not area A.
        assertTrue(exB.carries(areaB), "exercise B must carry firing area B");
        assertTrue(!exB.carries(areaA), "exercise B must NOT carry firing area A");

        // Exercise B must carry only area B's period.
        assertEquals(1, exB.eventDates().size(), "exercise B must carry exactly area B's single period");
        assertEquals(fromB, exB.eventDates().get(0)[0], "exercise B's event must equal area B's period (from)");
        assertEquals(toB, exB.eventDates().get(0)[1], "exercise B's event must equal area B's period (to)");
    }

    // ------------------------------------------------------------------------------------------- //
    // formatTimeDescription renders the firing period as a TIME-part 'details' text
    // ------------------------------------------------------------------------------------------- //

    @Test
    void timePartDescriptionTextIsFormattedFromTheFiringPeriod() {
        FiringDomains d = fixtures.firingDomains();
        Area area = areaWithTemplate(d, "IT-FE timedesc " + DeliveryFixtures.uniq());

        // A single same-day period; the formatter renders "<from> - <to>." for the en language.
        Date from = futureDay(4, 8);
        Date to = futureDay(4, 12);
        fixtures.firingPeriod(area, from, to);
        Integer schedule = fixtures.firingSchedule(d, 60, true);

        service.updateFiringExercises(Set.of(schedule));

        List<FiringExercise> published = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, published.size(), "exactly one exercise must be generated");
        FiringExercise ex = published.get(0);
        assertEquals(1, ex.timeParts(), "the generated exercise must carry exactly one TIME part");

        // The TIME part must have been given an 'en' description carrying the formatted period text.
        String details = ex.timeTextEn();
        assertNotNull(details, "the TIME-part 'en' details text must be populated");
        assertTrue(details.trim().length() > 0, "the TIME-part 'en' details text must be non-blank");

        // The formatter joins each interval as "<from> - <to>" and terminates the whole text with ".".
        assertTrue(details.contains(" - "),
                "the formatted period must contain the from/to range separator, but was: " + details);
        assertTrue(details.endsWith("."),
                "the formatted period text must be terminated with a full stop, but was: " + details);

        // Both the default ("d MMMM yyyy, ...") and any seeded date_time_format include the 4-digit year, so the
        // rendered text must contain the firing period's calendar year regardless of the configured pattern.
        Calendar c = Calendar.getInstance();
        c.setTime(from);
        String year = String.valueOf(c.get(Calendar.YEAR));
        assertTrue(details.contains(year),
                "the formatted period text must reflect the firing period's year " + year + ", but was: " + details);
    }

    // ------------------------------------------------------------------------------------------- //
    // MUST-NOT-MINT - the firing service cancels stale exercises via updateStatus(CANCELLED), never
    // cancelMessage, so it must NOT mint an S-124 cancellation warning even when the target NW series
    // would carry one. Making the series auto-numbered means the generated exercise satisfies the
    // full minting predicate, so this only passes because the firing service does not route its
    // cancel through cancelMessage.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void cancellingAStaleExerciseNeverMintsEvenWhenTheSeriesWouldCarryCancellationWarnings() {
        // An auto-numbered NW target series of its own, so the generated firing exercise satisfies the FULL minting
        // predicate. (The exercise is born-PUBLISHED via createMessage, so no number sequence is bumped by this.)
        FiringDomains d = fixtures.firingDomains(NumberSequenceType.YEARLY, "Europe/Copenhagen");
        Area area = areaWithTemplate(d, "IT-FE nomint " + DeliveryFixtures.uniq());
        Integer periodId = fixtures.firingPeriod(area, futureDay(8, 8), futureDay(8, 12));
        Integer schedule = fixtures.firingSchedule(d, 60, true);

        service.updateFiringExercises(Set.of(schedule));
        List<FiringExercise> firstRun = exercises(d, area, Status.PUBLISHED);
        assertEquals(1, firstRun.size(), "the first run must publish exactly one exercise message");
        String exerciseUid = firstRun.get(0).uid();

        // Non-triviality precondition: had the firing service cancelled this via cancelMessage, it WOULD
        // mint - the exercise is a WARNING-kind NW message on the auto-numbered series.
        assertTrue(Tx.requiringNew().call(() ->
                        messageService.willIssueCancellationWarning(messageQueryService.findByUid(exerciseUid))),
                "setup must be non-trivial: cancelling this exercise via cancelMessage would mint");

        // Remove the only firing period and re-run: the now-stale exercise is cancelled via updateStatus.
        assertTrue(Tx.requiringNew().call(() -> firingScheduleService.deleteFiringPeriod(periodId)),
                "the firing period must be deleted");
        service.updateFiringExercises(Set.of(schedule));

        // The exercise is CANCELLED...
        List<FiringExercise> cancelled = exercises(d, area, Status.CANCELLED);
        assertEquals(1, cancelled.size(), "the now-stale exercise message must be cancelled");
        assertEquals(exerciseUid, cancelled.get(0).uid(),
                "the cancelled message must be the originally published exercise");

        // ...but nothing was minted, because the firing service cancels via updateStatus, not cancelMessage.
        Long minted = Tx.requiringNew().call(() -> em.createQuery("select count(m) from Message m join m.references r "
                        + "where m.kind = :kind and r.messageId = :uid", Long.class)
                .setParameter("kind", MessageKind.CANCELLATION)
                .setParameter("uid", exerciseUid)
                .getSingleResult());
        assertEquals(0L, minted,
                "the firing service must never mint a cancellation warning: it cancels via updateStatus");
    }
}
