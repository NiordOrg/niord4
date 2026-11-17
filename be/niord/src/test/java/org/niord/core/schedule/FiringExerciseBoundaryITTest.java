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
package org.niord.core.schedule;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.model.message.Status;
import org.niord.test.DeliveryFixtures;
import org.niord.test.DeliveryFixtures.FiringDomains;
import org.niord.test.DeliveryFixtures.FiringExercise;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The firing exercises at the boundaries of the day and of the zone: the hourly
 * {@link FiringExerciseService#updateFiringExercises()} turns the firing periods of the coming days into a published
 * warning per firing area, whose time part prints each period in the target domain's zone and whose publication ends
 * with the last period's end.
 *
 * <p>The periods are put at the next occurrence of each boundary after today (the service reads the real clock and
 * looks {@code scheduleDays} ahead): the spring and the autumn clock change, New Year at 23:30 and 00:30 local, and a
 * leap day; in a target domain whose zone is Europe/Copenhagen and America/Nuuk. The time part must print the local
 * wall clock of both ends. A period until midnight ends at the next day's 00:00 and prints as 24:00; a period stored
 * with its end at or before its start, as the editor stored them before FIRING2 was fixed, is read as ending a day
 * later. The fixtures are committed in domains and series of the test's own and removed after it,
 * and the run is called with the test's own schedule only, as in {@link FiringExerciseServiceITTest}, whose Javadoc
 * explains why.
 */
class FiringExerciseBoundaryITTest extends ITTest {

    @Autowired
    FiringExerciseService service;

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

    /** A committed firing area with a committed PUBLISHED template in the source series */
    private Area areaWithTemplate(FiringDomains d, String name) {
        Area area = fixtures.firingArea(name);
        fixtures.firingTemplate(d.sourceSeries(), area, "IT-FEB template " + name);
        return area;
    }

    /** The one committed PUBLISHED exercise of the area */
    private FiringExercise publishedExercise(FiringDomains d, Area firingArea) {
        List<FiringExercise> published = fixtures.firingExercises(d.targetSeries(), firingArea, Status.PUBLISHED);
        assertEquals(1, published.size(), "one exercise warning for the area");
        return published.get(0);
    }

    private static String timeText(FiringExercise exercise) {
        return exercise.timeTextEn() == null ? "" : exercise.timeTextEn();
    }

    private static Date at(LocalDateTime local, ZoneId zone) {
        return Date.from(local.atZone(zone).toInstant());
    }

    /** The next date on or after tomorrow that the adjuster picks, as a local date */
    private static LocalDate lastSundayOf(int month, LocalDate after) {
        LocalDate d = after.withMonth(month).with(TemporalAdjusters.lastInMonth(DayOfWeek.SUNDAY));
        return d.isAfter(after) ? d : after.plusYears(1).withMonth(month).with(TemporalAdjusters.lastInMonth(DayOfWeek.SUNDAY));
    }

    private static LocalDate nextLeapDay(LocalDate after) {
        int year = after.getYear();
        while (true) {
            if (Year.isLeap(year)) {
                LocalDate d = LocalDate.of(year, 2, 29);
                if (d.isAfter(after)) return d;
            }
            year++;
        }
    }

    /**
     * Each boundary as a firing period in the zone, with the time text both ends must read at: a period of one
     * hour across the spring change (01:30 to 03:30 local in Copenhagen is one hour), one in the repeated autumn
     * hour's neighbourhood (01:30 to 03:30, three hours), New Year (23:30 to 00:30) and a leap day.
     */
    private static Map<String, LocalDateTime[]> boundaries(ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        Map<String, LocalDateTime[]> b = new LinkedHashMap<>();
        // The EU changes at 01:00 UTC; Nuuk (since 2023) at 01:00 UTC as well, i.e. 23:00 local on the Saturday:
        // both ends are on either side of the gap or the repeated hour, and both exist
        LocalDate spring = lastSundayOf(3, today);
        LocalDate autumn = lastSundayOf(10, today);
        if (zone.getId().equals("America/Nuuk")) {
            b.put("spring change", new LocalDateTime[]{spring.minusDays(1).atTime(22, 30), spring.atTime(0, 30)});
            b.put("autumn change", new LocalDateTime[]{autumn.minusDays(1).atTime(22, 30), autumn.atTime(0, 30)});
        } else {
            b.put("spring change", new LocalDateTime[]{spring.atTime(1, 30), spring.atTime(3, 30)});
            b.put("autumn change", new LocalDateTime[]{autumn.atTime(1, 30), autumn.atTime(3, 30)});
        }
        LocalDate newYearsEve = LocalDate.of(today.getYear(), 12, 31).isAfter(today)
                ? LocalDate.of(today.getYear(), 12, 31) : LocalDate.of(today.getYear() + 1, 12, 31);
        b.put("New Year", new LocalDateTime[]{newYearsEve.atTime(23, 30), newYearsEve.plusDays(1).atTime(0, 30)});
        LocalDate leap = nextLeapDay(today);
        b.put("leap day", new LocalDateTime[]{leap.atTime(9, 0), leap.atTime(15, 0)});
        return b;
    }

    private void printsTheLocalWallClock(String zoneId) {
        ZoneId zone = ZoneId.of(zoneId);
        FiringDomains d = fixtures.firingDomains(NumberSequenceType.NONE, zoneId);
        Map<String, LocalDateTime[]> boundaries = boundaries(zone);
        List<String> faults = new ArrayList<>();
        int days = 0;
        for (LocalDateTime[] p : boundaries.values()) {
            days = Math.max(days, (int) ChronoUnit.DAYS.between(LocalDate.now(zone), p[1].toLocalDate()) + 2);
        }
        Integer schedule = fixtures.firingSchedule(d, days, true);
        Map<String, Area> areas = new LinkedHashMap<>();
        String n = DeliveryFixtures.uniq();
        for (Map.Entry<String, LocalDateTime[]> e : boundaries.entrySet()) {
            Area area = areaWithTemplate(d, "IT-FEB " + e.getKey() + " " + n);
            fixtures.firingPeriod(area, at(e.getValue()[0], zone), at(e.getValue()[1], zone));
            areas.put(e.getKey(), area);
        }

        service.updateFiringExercises(Set.of(schedule));

        for (Map.Entry<String, LocalDateTime[]> e : boundaries.entrySet()) {
            FiringExercise exercise = publishedExercise(d, areas.get(e.getKey()));
            String text = timeText(exercise);
            String from = String.format("%02d:%02d", e.getValue()[0].getHour(), e.getValue()[0].getMinute());
            String to = String.format("%02d:%02d", e.getValue()[1].getHour(), e.getValue()[1].getMinute());
            String fromDay = String.valueOf(e.getValue()[0].getDayOfMonth());
            if (!text.contains(fromDay + " ") || !text.contains(from) || !text.contains(to)) {
                faults.add(zoneId + " " + e.getKey() + ": '" + text + "' for " + e.getValue()[0] + " - " + e.getValue()[1]);
            }
            Date expectedEnd = at(e.getValue()[1], zone);
            if (!expectedEnd.equals(exercise.publishDateTo())) {
                faults.add(zoneId + " " + e.getKey() + ": publication ends " + exercise.publishDateTo()
                        + ", the period " + expectedEnd);
            }
        }
        assertEquals(List.of(), faults);
    }

    @Test
    void theTimeText_printsBothEndsInCopenhagensWallClock_atEachBoundary() {
        printsTheLocalWallClock("Europe/Copenhagen");
    }

    @Test
    void theTimeText_printsBothEndsInNuuksWallClock_atEachBoundary() {
        printsTheLocalWallClock("America/Nuuk");
    }

    /** The run of the schedule, with the WARN lines of the service captured */
    private List<String> runCapturingWarnings(Integer schedule) {
        Logger logger = (Logger) LoggerFactory.getLogger(FiringExerciseService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.updateFiringExercises(Set.of(schedule));
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** The committed periods of the area, each as its from and to date */
    private List<Date[]> storedPeriodsOf(Area area) {
        return Tx.requiringNew().call(() -> em.createNamedQuery("FiringPeriod.findByArea", FiringPeriod.class)
                .setParameter("area", em.find(Area.class, area.getId()))
                .getResultList().stream()
                .map(fp -> new Date[]{new Date(fp.getFromDate().getTime()), new Date(fp.getToDate().getTime())})
                .toList());
    }

    /** "18 November 2026, hours 09:00 - 24:00": the English time text of a period from 09:00 until midnight */
    private static String untilMidnightText(LocalDate day) {
        return day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)) + ", hours 09:00 - 24:00";
    }

    /**
     * FIRING2 (2026-11-12): a period typed into the schedule editor as "09:00" to "00:00" ("until
     * midnight") was stored with its end at 00:00 of its own day, before its start, and the hourly run then ended the
     * warning's publication before the firing. A period that does not end after it starts is now refused, and nothing
     * is stored.
     */
    @Test
    void aPeriodThatEndsBeforeItStartsIsRefused() {
        ZoneId zone = ZoneId.of("Europe/Copenhagen");
        Area area = fixtures.firingArea("IT-FEB refused " + DeliveryFixtures.uniq());
        LocalDate day = LocalDate.now(zone).plusDays(3);
        Date from = at(day.atTime(9, 0), zone);
        Date midnightBefore = at(day.atTime(0, 0), zone);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> fixtures.firingPeriod(area, from, midnightBefore));
        assertTrue(e.getMessage().contains("must end after it starts"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> fixtures.firingPeriod(area, from, from),
                "a period that ends when it starts is refused too");

        assertEquals(List.of(), storedPeriodsOf(area), "nothing is stored");
    }

    /**
     * FIRING2: "until midnight" is the next day's 00:00. The warning stays published until that midnight, and its
     * time text prints the end as 24:00 on the firing's day.
     */
    @Test
    void aPeriodUntilMidnightKeepsTheWarningUntilMidnight() {
        ZoneId zone = ZoneId.of("Europe/Copenhagen");
        FiringDomains d = fixtures.firingDomains(NumberSequenceType.NONE, zone.getId());
        Area area = areaWithTemplate(d, "IT-FEB midnight " + DeliveryFixtures.uniq());
        LocalDate first = LocalDate.now(zone).plusDays(3);
        LocalDate last = first.plusDays(2);
        fixtures.firingPeriod(area, at(first.atTime(9, 0), zone), at(first.atTime(15, 0), zone));
        Date lastFrom = at(last.atTime(9, 0), zone);
        Date midnight = at(last.plusDays(1).atStartOfDay(), zone);
        fixtures.firingPeriod(area, lastFrom, midnight);
        Integer schedule = fixtures.firingSchedule(d, 30, true);

        service.updateFiringExercises(Set.of(schedule));

        FiringExercise exercise = publishedExercise(d, area);
        assertEquals(midnight, exercise.publishDateTo(), "the warning is published until the firing ends");
        assertTrue(timeText(exercise).contains(untilMidnightText(last)), timeText(exercise));
    }

    /**
     * FIRING2: a deployment keeps the periods the editor stored backwards before the fix (89 in the test data, most
     * of them 09:00 to 00:00 of one day). The run reads such a row as ending one day later in the target zone, which
     * is what the officer meant, names it in a WARN, and leaves the row as it is.
     */
    @Test
    void aStoredBackwardPeriodIsReadAsEndingTheNextDay() {
        ZoneId zone = ZoneId.of("Europe/Copenhagen");
        FiringDomains d = fixtures.firingDomains(NumberSequenceType.NONE, zone.getId());
        Area area = areaWithTemplate(d, "IT-FEB stored backward " + DeliveryFixtures.uniq());
        LocalDate day = LocalDate.now(zone).plusDays(3);
        Date from = at(day.atTime(9, 0), zone);
        Date storedTo = at(day.atTime(0, 0), zone);
        Integer periodId = fixtures.storedFiringPeriod(area, from, storedTo);
        Integer schedule = fixtures.firingSchedule(d, 30, true);

        List<String> warnings = runCapturingWarnings(schedule);

        FiringExercise exercise = publishedExercise(d, area);
        Date nextMidnight = at(day.plusDays(1).atStartOfDay(), zone);
        assertEquals(nextMidnight, exercise.publishDateTo(), "the warning is published until the next midnight");
        assertEquals(1, exercise.eventDates().size());
        assertEquals(List.of(from, nextMidnight), Arrays.asList(exercise.eventDates().get(0)), "the period as the run reads it");
        assertTrue(timeText(exercise).contains(untilMidnightText(day)), timeText(exercise));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("read as ending one day later") && w.contains(periodId + " (area")),
                "a WARN names the period: " + warnings);
        List<Date[]> stored = storedPeriodsOf(area);
        assertEquals(1, stored.size());
        assertEquals(List.of(from, storedTo), Arrays.asList(stored.get(0)), "the stored row is not changed");
    }

    /**
     * FIRING2, the plan review's second correction: the run's window starts at today's midnight in the JVM's zone, and
     * a stored backward row's end at a local midnight lies before that on the day of its firing when the server's zone
     * is west of the target zone (a UTC server, a Danish firing). Here the row is built in the JVM's zone with that
     * shape: from today 23:00 to yesterday 23:59. The run still reads it, and the warning stays published until today
     * 23:59.
     */
    @Test
    void aStoredBackwardPeriodIsStillReadOnTheDayOfItsFiring() {
        ZoneId jvm = ZoneId.systemDefault();
        LocalTime now = LocalTime.now(jvm);
        assumeTrue(now.isBefore(LocalTime.of(23, 0)) && now.isAfter(LocalTime.of(0, 0, 10)),
                "not in the last hour of the day, nor in the first seconds after midnight");
        FiringDomains d = fixtures.firingDomains(NumberSequenceType.NONE, jvm.getId());
        Area area = areaWithTemplate(d, "IT-FEB firing day " + DeliveryFixtures.uniq());
        LocalDate today = LocalDate.now(jvm);
        Date from = at(today.atTime(23, 0), jvm);
        Date storedTo = at(today.minusDays(1).atTime(23, 59), jvm);
        fixtures.storedFiringPeriod(area, from, storedTo);
        Integer schedule = fixtures.firingSchedule(d, 30, true);

        service.updateFiringExercises(Set.of(schedule));

        FiringExercise exercise = publishedExercise(d, area);
        Date end = at(today.atTime(23, 59), jvm);
        assertEquals(List.of(from, end), Arrays.asList(exercise.eventDates().get(0)), "the exercise names the period");
        assertEquals(end, exercise.publishDateTo(), "the warning is published until the firing ends");
    }

    /**
     * FIRING2, the plan review's second correction: the run reads the stored periods from one day before its window
     * (for the stored backward rows above), but a period stored the right way round is kept exactly as before the fix:
     * one that ended yesterday is left out of the exercise. Without that, the area's warning would name yesterday's
     * firing for one more day and be reissued at the next midnight. Built in the JVM's zone, whose midnight starts the
     * window.
     */
    @Test
    void aPeriodThatEndedBeforeTheWindowIsLeftOut() {
        ZoneId jvm = ZoneId.systemDefault();
        LocalTime now = LocalTime.now(jvm);
        assumeTrue(now.isAfter(LocalTime.of(0, 0, 10)) && now.isBefore(LocalTime.of(23, 59, 30)),
                "not in the first seconds after midnight, nor in the last seconds before it");
        FiringDomains d = fixtures.firingDomains(NumberSequenceType.NONE, jvm.getId());
        Area area = areaWithTemplate(d, "IT-FEB ended yesterday " + DeliveryFixtures.uniq());
        LocalDate today = LocalDate.now(jvm);
        fixtures.firingPeriod(area, at(today.minusDays(1).atTime(9, 0), jvm), at(today.minusDays(1).atTime(10, 0), jvm));
        Date aheadFrom = at(today.plusDays(3).atTime(9, 0), jvm);
        Date aheadTo = at(today.plusDays(3).atTime(15, 0), jvm);
        fixtures.firingPeriod(area, aheadFrom, aheadTo);
        Integer schedule = fixtures.firingSchedule(d, 30, true);

        service.updateFiringExercises(Set.of(schedule));

        FiringExercise exercise = publishedExercise(d, area);
        assertEquals(1, exercise.eventDates().size(), "only the period ahead: " + exercise.eventDates().stream()
                .map(Arrays::asList).toList());
        assertEquals(List.of(aheadFrom, aheadTo), Arrays.asList(exercise.eventDates().get(0)), "the period ahead");
        assertEquals(aheadTo, exercise.publishDateTo());
    }
}
