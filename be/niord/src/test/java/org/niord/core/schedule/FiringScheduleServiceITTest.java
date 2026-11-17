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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.area.AreaType;
import org.niord.core.category.Category;
import org.niord.core.category.CategoryService;
import org.niord.core.category.CategoryType;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.message.Message;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.schedule.vo.FiringAreaPeriodsVo;
import org.niord.core.schedule.vo.FiringPeriodVo;
import org.niord.test.ITTest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link FiringScheduleService}, run against the seeded MySQL container of the
 * {@link ITTest} infrastructure.
 *
 * <p>The seed data contains a {@code niord-fa} domain, a {@code dma-fa} message series, and
 * {@link AreaType#FIRING_AREA} areas. {@code AreaType} is persisted as an ordinal int, so the literal
 * string {@code "FIRING_AREA"} never appears in the seed SQL even though the firing areas are present;
 * the firing-area generation and search tests rely on these seeded areas rather than creating their own
 * fixtures.
 *
 * <p>All exercised {@code FiringScheduleService} CRUD methods are annotated {@code @Transactional}
 * (default propagation REQUIRED), so they join the caller transaction. Every test is therefore
 * wrapped in {@link TestTransaction} and all writes are rolled back, keeping the shared seed database
 * pristine and the tests independent. Database state is verified via {@code em.flush(); em.clear();}
 * followed by {@code em.find(...)}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class FiringScheduleServiceITTest extends ITTest {

    @Autowired
    FiringScheduleService service;

    @Autowired
    AreaService areaService;

    @Autowired
    DomainService domainService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @Autowired
    CategoryService categoryService;

    @PersistenceContext
    EntityManager em;

    /**
     * Creates and persists a FIRING_AREA area with a single English description, routing through
     * {@link AreaService#createArea} so lineage / sibling- and tree-sort-order are populated correctly.
     */
    private Area newFiringArea(String name) {
        Area area = new Area();
        area.setType(AreaType.FIRING_AREA);
        area.checkCreateDesc("en").setName(name);
        Area created = areaService.createArea(area, null);
        em.flush();
        return created;
    }

    /** Builds a detached, valid firing-period template (area + from/to, no id). */
    private FiringPeriod newPeriod(Area area, Date from, Date to) {
        FiringPeriod fp = new FiringPeriod();
        fp.setArea(area);
        fp.setFromDate(from);
        fp.setToDate(to);
        return fp;
    }

    // ------------------------------------------------------------------------------------------- //
    // addFiringPeriod
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void addFiringPeriodPersistsValidTemplateAndAssignsId() {
        Area area = newFiringArea("IT FA add " + System.nanoTime());
        Date from = new Date(1_600_000_000_000L);
        Date to = new Date(1_600_003_600_000L);

        FiringPeriod created = service.addFiringPeriod(newPeriod(area, from, to));
        Integer id = created.getId();
        assertNotNull(id, "addFiringPeriod must assign a generated id");

        em.flush();
        em.clear();

        FiringPeriod reloaded = em.find(FiringPeriod.class, id);
        assertNotNull(reloaded, "the firing period row must exist in the database");
        assertEquals(from, reloaded.getFromDate());
        assertEquals(to, reloaded.getToDate());
        assertEquals(area.getId(), reloaded.getArea().getId());
    }

    @Test
    @Transactional
    void addFiringPeriodRejectsAlreadyPersistedTemplate() {
        Area area = newFiringArea("IT FA add-existing " + System.nanoTime());
        FiringPeriod fp = newPeriod(area, new Date(1_600_000_000_000L), new Date(1_600_003_600_000L));
        fp.setId(123456); // pretend it is already persisted

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addFiringPeriod(fp));
        assertEquals("Cannot create existing firing period", ex.getMessage());
    }

    @Test
    @Transactional
    void addFiringPeriodRejectsTemplateWithNullArea() {
        FiringPeriod fp = newPeriod(null, new Date(1_600_000_000_000L), new Date(1_600_003_600_000L));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addFiringPeriod(fp));
        assertEquals("Firing period must defined area, from- and to-dates", ex.getMessage());
    }

    @Test
    @Transactional
    void addFiringPeriodRejectsTemplateWithNullDates() {
        Area area = newFiringArea("IT FA add-nodate " + System.nanoTime());
        FiringPeriod fp = newPeriod(area, null, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addFiringPeriod(fp));
        assertEquals("Firing period must defined area, from- and to-dates", ex.getMessage());
    }

    /** FIRING2: a period that ends before it starts, or when it starts, is refused, and nothing is stored */
    @Test
    @Transactional
    void addFiringPeriodRefusesAnEndAtOrBeforeItsStart() {
        Area area = newFiringArea("IT FA add-backward " + System.nanoTime());
        Date from = new Date(1_600_032_400_000L);
        Date before = new Date(1_600_000_000_000L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addFiringPeriod(newPeriod(area, from, before)));
        assertTrue(ex.getMessage().startsWith("A firing period must end after it starts (area " + area.getId() + ": "),
                ex.getMessage());
        assertTrue(ex.getMessage().endsWith("to fire until midnight, end it at 00:00 of the next day"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.addFiringPeriod(newPeriod(area, from, from)),
                "an end equal to the start is refused too");

        em.flush();
        em.clear();
        assertEquals(List.of(), em.createNamedQuery("FiringPeriod.findByArea", FiringPeriod.class)
                .setParameter("area", em.find(Area.class, area.getId())).getResultList(), "nothing is stored");
    }

    /** FIRING2: an update to an end at or before the start is refused, and the stored period keeps its dates */
    @Test
    @Transactional
    void updateFiringPeriodRefusesAnEndAtOrBeforeItsStart() {
        Area area = newFiringArea("IT FA update-backward " + System.nanoTime());
        Date from = new Date(1_600_000_000_000L);
        Date to = new Date(1_600_003_600_000L);
        FiringPeriod persisted = service.addFiringPeriod(newPeriod(area, from, to));
        em.flush();
        em.clear();

        for (Date badEnd : List.of(new Date(from.getTime() - 3_600_000L), from)) {
            FiringPeriod template = newPeriod(area, from, badEnd);
            template.setId(persisted.getId());
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> service.updateFiringPeriod(template));
            assertTrue(ex.getMessage().startsWith("A firing period must end after it starts"), ex.getMessage());
        }

        em.flush();
        em.clear();
        FiringPeriod reloaded = em.find(FiringPeriod.class, persisted.getId());
        assertEquals(from, reloaded.getFromDate());
        assertEquals(to, reloaded.getToDate(), "the stored period keeps its end");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateFiringPeriod
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateFiringPeriodPersistsNewDates() {
        Area area = newFiringArea("IT FA update " + System.nanoTime());
        FiringPeriod persisted = service.addFiringPeriod(
                newPeriod(area, new Date(1_600_000_000_000L), new Date(1_600_003_600_000L)));
        em.flush();

        Date newFrom = new Date(1_700_000_000_000L);
        Date newTo = new Date(1_700_007_200_000L);
        FiringPeriod template = newPeriod(area, newFrom, newTo);
        template.setId(persisted.getId()); // carry the same id

        FiringPeriod updated = service.updateFiringPeriod(template);
        assertEquals(persisted.getId(), updated.getId(), "update must mutate the existing row");

        em.flush();
        em.clear();

        FiringPeriod reloaded = em.find(FiringPeriod.class, persisted.getId());
        assertEquals(newFrom, reloaded.getFromDate(), "the updated from-date must be persisted");
        assertEquals(newTo, reloaded.getToDate(), "the updated to-date must be persisted");
    }

    @Test
    @Transactional
    void updateFiringPeriodRejectsNewTemplate() {
        Area area = newFiringArea("IT FA update-new " + System.nanoTime());
        // No id -> isNew() == true.
        FiringPeriod fp = newPeriod(area, new Date(1_600_000_000_000L), new Date(1_600_003_600_000L));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateFiringPeriod(fp));
        assertEquals("Cannot update non-existing firing period", ex.getMessage());
    }

    @Test
    @Transactional
    void updateFiringPeriodRejectsUndefinedTemplate() {
        // Has an id (so it passes the isNew() guard) but a null area, so it is "undefined".
        FiringPeriod fp = newPeriod(null, new Date(1_600_000_000_000L), new Date(1_600_003_600_000L));
        fp.setId(987654);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateFiringPeriod(fp));
        assertEquals("Firing period must defined area, from- and to-dates", ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteFiringPeriod
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteFiringPeriodRemovesExistingRowAndReturnsTrue() {
        Area area = newFiringArea("IT FA delete " + System.nanoTime());
        FiringPeriod persisted = service.addFiringPeriod(
                newPeriod(area, new Date(1_600_000_000_000L), new Date(1_600_003_600_000L)));
        em.flush();
        Integer id = persisted.getId();

        assertTrue(service.deleteFiringPeriod(id), "deleting an existing firing period must return true");

        em.flush();
        em.clear();
        assertNull(em.find(FiringPeriod.class, id), "the deleted firing-period row must be gone");
    }

    @Test
    @Transactional
    void deleteFiringPeriodReturnsFalseForUnknownId() {
        assertFalse(service.deleteFiringPeriod(556677),
                "deleting a non-existing firing period must return false");
    }

    // ------------------------------------------------------------------------------------------- //
    // getAllLegacyFiringPeriods
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getAllLegacyFiringPeriodsReturnsOnlyPeriodsWithLegacyId() {
        Area area = newFiringArea("IT FA legacy " + System.nanoTime());

        FiringPeriod legacy = newPeriod(area, new Date(1_600_000_000_000L), new Date(1_600_003_600_000L));
        legacy.setLegacyId("legacy-" + System.nanoTime());
        FiringPeriod legacyPersisted = service.addFiringPeriod(legacy);

        FiringPeriod nonLegacy = service.addFiringPeriod(
                newPeriod(area, new Date(1_600_010_000_000L), new Date(1_600_013_600_000L)));
        em.flush();
        em.clear();

        Set<Integer> ids = service.getAllLegacyFiringPeriods().stream()
                .map(FiringPeriod::getId)
                .collect(Collectors.toSet());

        assertTrue(ids.contains(legacyPersisted.getId()),
                "a firing period with a legacyId must be returned");
        assertFalse(ids.contains(nonLegacy.getId()),
                "a firing period without a legacyId must be excluded");
    }

    // ------------------------------------------------------------------------------------------- //
    // createFiringSchedule
    // ------------------------------------------------------------------------------------------- //

    /** Builds a detached firing-schedule template referencing the seeded fa domain / series by id. */
    private FiringSchedule newSchedule() {
        FiringSchedule schedule = new FiringSchedule();
        schedule.setDomain(new Domain());
        schedule.getDomain().setDomainId("niord-fa");
        schedule.setTargetDomain(new Domain());
        schedule.getTargetDomain().setDomainId("niord-fa");
        schedule.setTargetMessageSeries(new MessageSeries("dma-fa"));
        schedule.setScheduleDays(7);
        schedule.setActive(true);
        return schedule;
    }

    @Test
    @Transactional
    void createFiringSchedulePersistsAndResolvesManagedDomainAndSeries() {
        FiringSchedule created = service.createFiringSchedule(newSchedule());
        Integer id = created.getId();
        assertNotNull(id, "createFiringSchedule must assign a generated id");

        em.flush();
        em.clear();

        FiringSchedule reloaded = em.find(FiringSchedule.class, id);
        assertNotNull(reloaded, "the firing-schedule row must exist in the database");
        assertTrue(reloaded.isActive(), "active flag must be persisted");
        assertEquals(Integer.valueOf(7), reloaded.getScheduleDays());
        // The service substitutes the transient templates with the managed seed entities.
        assertEquals("niord-fa", reloaded.getDomain().getDomainId());
        assertEquals("niord-fa", reloaded.getTargetDomain().getDomainId());
        assertEquals("dma-fa", reloaded.getTargetMessageSeries().getSeriesId());
        assertNotNull(reloaded.getDomain().getId(),
                "the resolved domain must be a managed (persisted) entity");
    }

    @Test
    @Transactional
    void createFiringScheduleRejectsTemplateWithExistingId() {
        FiringSchedule schedule = newSchedule();
        schedule.setId(42424);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.createFiringSchedule(schedule));
        assertTrue(ex.getMessage().contains("42424"),
                "the error must name the offending existing id, was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // updateFiringSchedule
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateFiringScheduleRejectsNonExistingId() {
        FiringSchedule schedule = newSchedule();
        schedule.setId(424242);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateFiringSchedule(schedule));
        assertTrue(ex.getMessage().contains("424242"),
                "the error must name the unknown schedule id, was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteFiringSchedule / getFiringSchedules
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteFiringScheduleRemovesCreatedScheduleAndReturnsTrue() {
        FiringSchedule created = service.createFiringSchedule(newSchedule());
        em.flush();
        Integer id = created.getId();

        assertTrue(service.deleteFiringSchedule(id),
                "deleting an existing firing schedule must return true");

        em.flush();
        em.clear();
        assertNull(em.find(FiringSchedule.class, id), "the deleted firing-schedule row must be gone");
    }

    @Test
    @Transactional
    void deleteFiringScheduleReturnsFalseForUnknownId() {
        assertFalse(service.deleteFiringSchedule(778899),
                "deleting a non-existing firing schedule must return false");
    }

    @Test
    @Transactional
    void getFiringSchedulesContainsCreatedSchedule() {
        FiringSchedule created = service.createFiringSchedule(newSchedule());
        em.flush();

        Set<Integer> ids = service.getFiringSchedules().stream()
                .map(FiringSchedule::getId)
                .collect(Collectors.toSet());
        assertTrue(ids.contains(created.getId()),
                "getFiringSchedules must include a newly created schedule");
    }

    // ------------------------------------------------------------------------------------------- //
    // generateFiringAreaMessages
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void generateFiringAreaMessagesCreatesDraftMessagesForSeededFiringAreas() {
        // The seed DOES contain FIRING_AREA areas: AreaType is persisted as an ordinal int, so the
        // string "FIRING_AREA" never appears in the seed SQL even though the areas are present. With
        // no current domain set, searchAreas returns all firing areas and the service generates one
        // DRAFT message per area.
        MessageSeries series = messageSeriesService.findBySeriesId("dma-fa");
        assertNotNull(series, "precondition: the seeded dma-fa message series must exist");

        List<Message> result = service.generateFiringAreaMessages(series, null);
        assertFalse(result.isEmpty(),
                "generateFiringAreaMessages must create a message for each seeded firing area");
        for (Message m : result) {
            assertEquals(org.niord.model.message.Status.DRAFT, m.getStatus(),
                    "each generated firing-area message must be a DRAFT");
            assertEquals(series.getMainType(), m.getMainType(),
                    "each generated message must inherit the series' main type");
            assertNotNull(m.getShortId(), "each generated message must have a short id");
            assertTrue(m.getShortId().startsWith("FA-"),
                    "the generated firing-area short id must start with FA-, was: " + m.getShortId());
        }
    }

    /** Runs the generation with the service's log captured, and returns the WARN lines */
    private List<String> warningsOfGeneration(MessageSeries series, List<Message> result) {
        Logger logger = (Logger) LoggerFactory.getLogger(FiringScheduleService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            result.addAll(service.generateFiringAreaMessages(series, null));
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    @Transactional
    void generateFiringAreaMessagesWarnsOnceWhenNoFiringExercisesCategoryExists() {
        // The fixture carries the category since C4b (the base product's bootstrap has it,
        // docs/code-data-config-plan.md §7.1): a database without it is made by renaming it inside the
        // test's transaction, which the rollback undoes
        Category firingExercises = categoryService.getFiringExercisesCategory();
        assertNotNull(firingExercises, "precondition: the fixture has the category");
        firingExercises.getDesc("en").setName("IT renamed " + CategoryService.FIRING_EXERCISES_CATEGORY_NAME);
        em.flush();
        assertNull(categoryService.getFiringExercisesCategory(), "precondition: no category has the name");
        MessageSeries series = messageSeriesService.findBySeriesId("dma-fa");

        List<Message> result = new java.util.ArrayList<>();
        List<String> warnings = warningsOfGeneration(series, result);

        assertTrue(result.size() > 1, "precondition: several firing areas, so a warning per message would show");
        assertEquals(1, warnings.size(), "one warning per run, not one per message: " + warnings);
        assertTrue(warnings.get(0).contains("\"" + CategoryService.FIRING_EXERCISES_CATEGORY_NAME + "\""),
                "the warning names the category it looked for: " + warnings.get(0));
        assertTrue(result.stream().allMatch(m -> m.getCategories().isEmpty()));
    }

    @Test
    @Transactional
    void generateFiringAreaMessagesGivesEachMessageTheFiringExercisesCategoryByItsEnglishName() {
        Category category = new Category();
        category.setType(CategoryType.CATEGORY);
        category.checkCreateDesc("en").setName(CategoryService.FIRING_EXERCISES_CATEGORY_NAME);
        categoryService.createCategory(category, null);
        em.flush();
        MessageSeries series = messageSeriesService.findBySeriesId("dma-fa");

        List<Message> result = new java.util.ArrayList<>();
        List<String> warnings = warningsOfGeneration(series, result);

        assertTrue(warnings.isEmpty(), "no warning when the category exists: " + warnings);
        assertFalse(result.isEmpty());
        assertTrue(result.stream().allMatch(m -> m.getCategories().stream().anyMatch(c ->
                        CategoryService.FIRING_EXERCISES_CATEGORY_NAME.equals(c.getDesc("en").getName()))),
                "every generated message has the category");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchFiringAreaPeriods
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchFiringAreaPeriodsReturnsSeededAreasWithEmptyPeriodsForADateWithoutPeriods() {
        // searchFiringAreaPeriods returns one entry per firing AREA (the seed contains firing areas).
        // No FiringPeriod rows are seeded, so for any date every returned area has an EMPTY period
        // list (the date only affects which periods are grouped in, not which areas are returned).
        Date date = new Date(1_600_000_000_000L);
        List<FiringAreaPeriodsVo> result = service.searchFiringAreaPeriods(date, null, null, true, "en");
        assertFalse(result.isEmpty(),
                "the seeded firing areas must be returned even when no periods match the date");
        assertTrue(result.stream().allMatch(fap -> fap.getFiringPeriods().isEmpty()),
                "with no firing periods seeded, every returned firing area must have an empty period list");
    }

    @Test
    @Transactional
    void searchFiringAreaPeriodsGroupsPeriodForMatchingAreaAndDate() {
        long n = System.nanoTime();
        Area area = newFiringArea("IT FA search " + n);

        // The firing period spans the search date (resetTime(date)..endOfDay(date)).
        Date dayStart = new Date(1_600_000_000_000L); // the search date
        Date from = new Date(dayStart.getTime() + 3_600_000L);  // +1h, same day
        Date to = new Date(dayStart.getTime() + 7_200_000L);    // +2h, same day
        FiringPeriod fp = service.addFiringPeriod(newPeriod(area, from, to));
        em.flush();
        em.clear();

        List<FiringAreaPeriodsVo> result =
                service.searchFiringAreaPeriods(dayStart, null, null, true, "en");

        FiringAreaPeriodsVo match = result.stream()
                .filter(fap -> area.getId().equals(fap.getArea().getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(match, "the created firing area must be present in the search result");
        assertEquals(1, match.getFiringPeriods().size(),
                "the area must carry exactly its one firing period for the search date");
        assertEquals(fp.getId(), match.getFiringPeriods().get(0).getId(),
                "the grouped firing period must be the one created for this area");
    }

    /** The ids of the area's periods listed on the day of the given local date, in the JVM's zone (no current domain) */
    private List<Integer> periodIdsListedOn(Area area, LocalDate day) {
        Date noon = Date.from(day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant());
        return service.searchFiringAreaPeriods(noon, null, Set.of(area.getId()), true, "en").stream()
                .filter(fap -> area.getId().equals(fap.getArea().getId()))
                .flatMap(fap -> fap.getFiringPeriods().stream())
                .map(org.niord.core.schedule.vo.FiringPeriodVo::getId)
                .toList();
    }

    /**
     * FIRING2, the plan review's first correction: a period stored with its end at or before its start (09:00 to
     * 00:00 of one day, as the editor stored "until midnight" before the fix) is listed on its start day, where the
     * editor shows it for correction, and on no other day. The day list's lower bound is exclusive since FIRING2, so
     * without the clause for such rows this one, whose end is the start of its own day, would be listed on no day.
     */
    @Test
    @Transactional
    void aStoredBackwardPeriodIsListedOnItsStartDayOnly() {
        Area area = newFiringArea("IT FA stored backward " + System.nanoTime());
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = LocalDate.of(2031, 3, 12);
        FiringPeriod stored = newPeriod(area,
                Date.from(day.atTime(9, 0).atZone(zone).toInstant()),
                Date.from(day.atStartOfDay(zone).toInstant()));
        em.persist(stored); // past the service's check, as the rows a deployment keeps
        em.flush();
        em.clear();

        assertEquals(List.of(stored.getId()), periodIdsListedOn(area, day), "listed on its start day");
        assertEquals(List.of(), periodIdsListedOn(area, day.minusDays(1)), "not on the day before");
        assertEquals(List.of(), periodIdsListedOn(area, day.plusDays(1)),
                "not on the day after: read as ending one day later, it ends at that day's 00:00 and has no early hours");
    }

    /** Persists a period past the service's check, as the older rows a deployment keeps */
    private FiringPeriod storedPeriod(Area area, Date from, Date to) {
        FiringPeriod stored = newPeriod(area, from, to);
        em.persist(stored);
        em.flush();
        em.clear();
        return stored;
    }

    private static Date at(LocalDate day, int hour) {
        return Date.from(day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * L-103 (decision 12): a period stored with its end before its start, 22:00 to 02:00 of one day, is read as
     * ending at 02:00 of the next day (as the hourly run reads it), so it is listed on its start day and, for its
     * early hours, on the next day too; on no other day. A period of the day before that ends at 23:00 of that
     * day is not listed on the next day.
     */
    @Test
    @Transactional
    void aStoredBackwardPeriodWithEarlyHoursIsListedOnItsStartDayAndTheNext() {
        Area area = newFiringArea("IT FA stored backward early " + System.nanoTime());
        LocalDate day = LocalDate.of(2031, 3, 18);
        FiringPeriod stored = storedPeriod(area, at(day, 22), at(day, 2));
        FiringPeriod evening = service.addFiringPeriod(newPeriod(area, at(day, 8), at(day, 23)));
        em.flush();
        em.clear();

        assertEquals(List.of(), periodIdsListedOn(area, day.minusDays(1)), "not on the day before");
        assertEquals(Set.of(evening.getId(), stored.getId()), Set.copyOf(periodIdsListedOn(area, day)),
                "listed on its start day");
        assertEquals(List.of(stored.getId()), periodIdsListedOn(area, day.plusDays(1)),
                "listed on the next day, for its early hours; the period ending at 23:00 is not");
        assertEquals(List.of(), periodIdsListedOn(area, day.plusDays(2)), "not on the day after that");
    }

    /**
     * L-103: the next day's save sends the backward period of the day before back as it is stored (the editor
     * shows it locked there): it is kept as stored, and the day's other periods are saved. Changed there, it is
     * refused, and on its start day it is still refused until it is corrected (FIRING2).
     */
    @Test
    @Transactional
    void aNextDaySaveThatSendsTheBackwardPeriodBackUnchangedKeepsIt() {
        Area area = newFiringArea("IT FA stored backward save " + System.nanoTime());
        LocalDate day = LocalDate.of(2031, 3, 18);
        LocalDate nextDay = day.plusDays(1);
        Date nextNoon = at(nextDay, 12);
        FiringPeriod stored = storedPeriod(area, at(day, 22), at(day, 2));
        FiringPeriod morning = service.addFiringPeriod(newPeriod(area, at(nextDay, 8), at(nextDay, 10)));
        em.flush();
        em.clear();

        FiringAreaPeriodsVo update = new FiringAreaPeriodsVo();
        update.setArea(service.getFiringAreaPeriods(area.getId(), nextNoon, "en").getArea());
        FiringPeriodVo added = new FiringPeriodVo();
        added.setFromDate(at(nextDay, 13));
        added.setToDate(at(nextDay, 15));
        update.setFiringPeriods(new ArrayList<>(List.of(periodVo(stored), periodVo(morning), added)));
        update.setLoadedPeriodIds(List.of(stored.getId(), morning.getId()));
        FiringAreaPeriodsVo result = service.updateFiringAreaPeriodsForDate(update, nextNoon, "en");
        em.flush();
        em.clear();

        assertEquals(3, result.getFiringPeriods().size(), "the answer lists the day: " + result.getFiringPeriods());
        assertTrue(result.getFiringPeriods().stream().anyMatch(fp -> stored.getId().equals(fp.getId())),
                "the backward period is listed in the answer");
        FiringPeriod reloaded = em.find(FiringPeriod.class, stored.getId());
        assertEquals(at(day, 22), reloaded.getFromDate(), "the backward period is kept as stored");
        assertEquals(at(day, 2), reloaded.getToDate(), "the backward period is kept as stored");

        // Changed on the next day, it is refused
        FiringPeriodVo changed = periodVo(stored);
        changed.setToDate(at(day, 3));
        FiringAreaPeriodsVo changedUpdate = new FiringAreaPeriodsVo();
        changedUpdate.setArea(update.getArea());
        changedUpdate.setFiringPeriods(new ArrayList<>(List.of(changed)));
        changedUpdate.setLoadedPeriodIds(List.of(stored.getId()));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateFiringAreaPeriodsForDate(changedUpdate, nextNoon, "en"));
        assertTrue(ex.getMessage().startsWith("A firing period must end after it starts"), ex.getMessage());

        // Sent back unchanged on its start day, it is refused until it is corrected
        FiringAreaPeriodsVo startDayUpdate = new FiringAreaPeriodsVo();
        startDayUpdate.setArea(update.getArea());
        startDayUpdate.setFiringPeriods(new ArrayList<>(List.of(periodVo(stored))));
        startDayUpdate.setLoadedPeriodIds(List.of(stored.getId()));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateFiringAreaPeriodsForDate(startDayUpdate, at(day, 12), "en"));
    }

    // ------------------------------------------------------------------------------------------- //
    // updateFiringAreaPeriodsForDate: the merge of FIRING8
    // ------------------------------------------------------------------------------------------- //

    private static FiringPeriodVo periodVo(FiringPeriod period) {
        FiringPeriodVo vo = new FiringPeriodVo();
        vo.setId(period.getId());
        vo.setFromDate(period.getFromDate());
        vo.setToDate(period.getToDate());
        return vo;
    }

    /**
     * FIRING8: a body that names the periods it loaded deletes only those of them it leaves out. A period of the
     * day it did not load (another tab added it) is kept, and a loaded id of another area is not touched.
     */
    @Test
    @Transactional
    void anUpdateWithItsLoadedIdsDeletesOnlyTheLoadedPeriodsItLeftOut() {
        Area area = newFiringArea("IT FA merge " + System.nanoTime());
        Area other = newFiringArea("IT FA merge other " + System.nanoTime());
        Date dayStart = new Date(1_600_000_000_000L);
        FiringPeriod kept = service.addFiringPeriod(newPeriod(area,
                new Date(dayStart.getTime() + 3_600_000L), new Date(dayStart.getTime() + 7_200_000L)));
        FiringPeriod removed = service.addFiringPeriod(newPeriod(area,
                new Date(dayStart.getTime() + 7_200_000L), new Date(dayStart.getTime() + 10_800_000L)));
        FiringPeriod otherArea = service.addFiringPeriod(newPeriod(other,
                new Date(dayStart.getTime() + 3_600_000L), new Date(dayStart.getTime() + 7_200_000L)));
        em.flush();
        List<Integer> loaded = List.of(kept.getId(), removed.getId(), otherArea.getId());

        // Added after the load, by another tab
        FiringPeriod addedMeanwhile = service.addFiringPeriod(newPeriod(area,
                new Date(dayStart.getTime() + 10_800_000L), new Date(dayStart.getTime() + 14_400_000L)));
        em.flush();
        em.clear();

        FiringAreaPeriodsVo update = new FiringAreaPeriodsVo();
        update.setArea(service.getFiringAreaPeriods(area.getId(), dayStart, "en").getArea());
        update.setFiringPeriods(new ArrayList<>(List.of(periodVo(kept))));
        update.setLoadedPeriodIds(loaded);
        FiringAreaPeriodsVo result = service.updateFiringAreaPeriodsForDate(update, dayStart, "en");
        em.flush();
        em.clear();

        assertNotNull(em.find(FiringPeriod.class, kept.getId()), "the period in the body is kept");
        assertNull(em.find(FiringPeriod.class, removed.getId()), "the loaded period left out is deleted");
        assertNotNull(em.find(FiringPeriod.class, addedMeanwhile.getId()), "the period added meanwhile is kept");
        assertNotNull(em.find(FiringPeriod.class, otherArea.getId()), "another area's period is not touched");
        assertEquals(Set.of(kept.getId(), addedMeanwhile.getId()),
                result.getFiringPeriods().stream().map(FiringPeriodVo::getId).collect(Collectors.toSet()),
                "the answer lists the day as stored");
    }

    /** A body without the loaded ids replaces the day's periods, as before FIRING8 */
    @Test
    @Transactional
    void anUpdateWithoutLoadedIdsReplacesTheDay() {
        Area area = newFiringArea("IT FA replace " + System.nanoTime());
        Date dayStart = new Date(1_600_000_000_000L);
        FiringPeriod kept = service.addFiringPeriod(newPeriod(area,
                new Date(dayStart.getTime() + 3_600_000L), new Date(dayStart.getTime() + 7_200_000L)));
        FiringPeriod notInBody = service.addFiringPeriod(newPeriod(area,
                new Date(dayStart.getTime() + 10_800_000L), new Date(dayStart.getTime() + 14_400_000L)));
        em.flush();
        em.clear();

        FiringAreaPeriodsVo update = new FiringAreaPeriodsVo();
        update.setArea(service.getFiringAreaPeriods(area.getId(), dayStart, "en").getArea());
        update.setFiringPeriods(new ArrayList<>(List.of(periodVo(kept))));
        service.updateFiringAreaPeriodsForDate(update, dayStart, "en");
        em.flush();
        em.clear();

        assertNotNull(em.find(FiringPeriod.class, kept.getId()));
        assertNull(em.find(FiringPeriod.class, notInBody.getId()), "a period not in the body is deleted");
    }
}
