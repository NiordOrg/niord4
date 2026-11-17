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

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.niord.core.web.NiordWebException;
import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.area.AreaType;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainLookupService;
import org.niord.core.domain.DomainService;
import org.niord.core.message.Message;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageTag;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;
import org.niord.core.schedule.vo.FiringAreaPeriodsVo;
import org.niord.core.schedule.vo.FiringPeriodVo;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Additional integration tests for {@link FiringScheduleService}, complementing the cases already
 * covered by {@code FiringScheduleServiceITTest} (which this class does <em>not</em> duplicate).
 *
 * <p>The focus here is on the methods and branches not exercised by the sibling: the
 * {@code updateFiringSchedule} happy path and its domain-switch back-reference bookkeeping,
 * {@code searchFiringAreaPeriods} filtering (name query / inactive areas), the
 * {@code getFiringAreaPeriods} look-up, the {@code updateFiringAreaPeriodsForDate}
 * add/update/delete reconciliation and its 400 validation, and the {@code generateFiringAreaMessages}
 * message-tag wiring plus its empty-result branch.
 *
 * <p>The seed data contains a {@code niord-fa} domain, a {@code dma-fa} message series, and
 * {@link AreaType#FIRING_AREA} areas. {@code AreaType} is persisted as an ordinal int, so the literal
 * string {@code "FIRING_AREA"} never appears in the seed SQL even though the firing areas are present.
 *
 * <p>All exercised {@code FiringScheduleService} CRUD methods are annotated {@code @Transactional}
 * (default propagation REQUIRED) and therefore join the caller transaction. Every test is wrapped in
 * {@link TestTransaction} so all writes are rolled back, keeping the shared seed database pristine and
 * the tests independent. Database state is verified via {@code em.flush(); em.clear();} followed by
 * {@code em.find(...)} / re-query. Tests that mutate the {@link DomainService} thread-local current
 * domain always clear it again in a {@code finally} block, so the thread-local never leaks into other
 * tests in the suite.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class FiringScheduleServiceGapITTest extends ITTest {

    @Autowired
    FiringScheduleService service;

    @Autowired
    AreaService areaService;

    @Autowired
    DomainService domainService;

    @Autowired
    DomainLookupService domainLookupService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers (mirroring the sibling's factories)
    // ------------------------------------------------------------------------------------------- //

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

    /** Creates and persists an inactive FIRING_AREA area (a root area, so it stays inactive). */
    private Area newInactiveFiringArea(String name) {
        Area area = new Area();
        area.setType(AreaType.FIRING_AREA);
        area.setActive(false);
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

    /** Builds a detached firing-schedule template referencing the given source domain (target = fa). */
    private FiringSchedule newSchedule(String sourceDomainId) {
        FiringSchedule schedule = new FiringSchedule();
        schedule.setDomain(new Domain());
        schedule.getDomain().setDomainId(sourceDomainId);
        schedule.setTargetDomain(new Domain());
        schedule.getTargetDomain().setDomainId("niord-fa");
        schedule.setTargetMessageSeries(new MessageSeries("dma-fa"));
        schedule.setScheduleDays(7);
        schedule.setActive(true);
        return schedule;
    }

    // ------------------------------------------------------------------------------------------- //
    // updateFiringSchedule - happy path
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateFiringSchedulePersistsScheduleDaysAndActiveAndResolvesManagedDomainAndSeries() {
        FiringSchedule created = service.createFiringSchedule(newSchedule("niord-fa"));
        em.flush();
        Integer id = created.getId();

        // Update with detached templates carrying changed scalar values.
        FiringSchedule template = newSchedule("niord-fa");
        template.setId(id);
        template.setScheduleDays(14);
        template.setActive(false);

        FiringSchedule updated = service.updateFiringSchedule(template);
        assertEquals(id, updated.getId(), "update must mutate the existing schedule row");

        em.flush();
        em.clear();

        FiringSchedule reloaded = em.find(FiringSchedule.class, id);
        assertNotNull(reloaded, "the firing-schedule row must still exist after update");
        assertEquals(Integer.valueOf(14), reloaded.getScheduleDays(),
                "the updated scheduleDays must be persisted");
        assertFalse(reloaded.isActive(), "the updated active flag must be persisted");
        // The detached templates must have been substituted with the managed seed entities.
        assertEquals("niord-fa", reloaded.getDomain().getDomainId());
        assertEquals("niord-fa", reloaded.getTargetDomain().getDomainId());
        assertEquals("dma-fa", reloaded.getTargetMessageSeries().getSeriesId());
        assertNotNull(reloaded.getDomain().getId(),
                "the resolved domain must be a managed (persisted) entity");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateFiringSchedule - domain switch
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateFiringScheduleSwitchingSourceDomainRewritesTheUniqueBackReference() {
        // Verifies the source-domain-switch back-reference bookkeeping of
        // FiringScheduleService.updateFiringSchedule. The Domain.firingSchedule association is owned by
        // Domain and is backed by a UNIQUE constraint on the Domain.firingSchedule_id FK column
        // (UK_9bw3up4aitusgqxt85gajur7c). Switching a schedule's source domain executes, in this order:
        //     original.getDomain().setFiringSchedule(null);   // clear fa.firingSchedule
        //     domain.setFiringSchedule(original);             // set  nw.firingSchedule = schedule
        // leaving two dirty Domain rows. Under the original Quarkus stack (Hibernate ORM 6) the flush could
        // issue the "set the new owner" UPDATE before the "clear the old owner" UPDATE, so two Domain rows
        // transiently referenced the same firingSchedule_id and the flush failed on the unique constraint —
        // i.e. switching the source domain was effectively broken (documented as FS1 in
        // docs/potential-errors.md). Under the Spring Boot 4 / Hibernate ORM 7 port the flush orders the two
        // Domain UPDATEs so the cleared old reference is written before the new one, so the hand-over now
        // succeeds. This test pins that corrected behaviour: after the switch the schedule belongs to nw, fa
        // no longer owns it, and the unique back-reference has moved intact.

        // Create the schedule against the fa domain. createFiringSchedule sets the back-reference
        // fa.getFiringSchedule() == created.
        FiringSchedule created = service.createFiringSchedule(newSchedule("niord-fa"));
        em.flush();
        Integer id = created.getId();

        Domain faBefore = domainLookupService.findByDomainId("niord-fa");
        assertNotNull(faBefore.getFiringSchedule(),
                "precondition: the fa domain must own the freshly created schedule");
        assertEquals(id, faBefore.getFiringSchedule().getId());

        // Switch the schedule's source domain from fa -> nw.
        FiringSchedule template = newSchedule("niord-nw");
        template.setId(id);
        FiringSchedule updated = service.updateFiringSchedule(template);
        assertEquals(id, updated.getId(), "the switch must mutate the existing schedule row, not create a new one");

        // The hand-over must now flush cleanly (no unique-constraint violation on firingSchedule_id).
        em.flush();
        em.clear();

        // Reload everything from a fresh persistence context and assert the back-reference moved intact:
        // the schedule's source domain is now nw, nw owns the schedule, and fa no longer owns any schedule.
        FiringSchedule reloaded = em.find(FiringSchedule.class, id);
        assertNotNull(reloaded, "the firing schedule must still exist after the source-domain switch");
        assertEquals("niord-nw", reloaded.getDomain().getDomainId(),
                "the schedule's source domain must have been switched to nw");

        Domain nwAfter = domainLookupService.findByDomainId("niord-nw");
        assertNotNull(nwAfter.getFiringSchedule(),
                "the nw domain must now own the schedule");
        assertEquals(id, nwAfter.getFiringSchedule().getId(),
                "nw's back-reference must point at the switched schedule");

        Domain faAfter = domainLookupService.findByDomainId("niord-fa");
        assertNull(faAfter.getFiringSchedule(),
                "the fa domain must no longer own the schedule after the switch");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchFiringAreaPeriods - name query filter
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchFiringAreaPeriodsNameQueryFiltersAreas() {
        String token = "GAPNAME" + System.nanoTime();
        Area matching = newFiringArea("IT FA " + token + " match");
        Area other = newFiringArea("IT FA other " + System.nanoTime());
        em.flush();
        em.clear();

        // The name query is a case-insensitive substring (LIKE %token%) match on the area name.
        List<FiringAreaPeriodsVo> result =
                service.searchFiringAreaPeriods(new Date(1_600_000_000_000L), token, null, true, "en");

        Set<Integer> ids = result.stream()
                .map(fap -> fap.getArea().getId())
                .collect(Collectors.toSet());

        assertTrue(ids.contains(matching.getId()),
                "the area whose name contains the query token must be returned");
        assertFalse(ids.contains(other.getId()),
                "an area whose name does not contain the query token must be excluded");
        assertTrue(result.stream().allMatch(fap -> fap.getArea().getDescs().get(0).getName().contains(token)),
                "every returned area must match the name query");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchFiringAreaPeriods - inactive filter
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchFiringAreaPeriodsInactiveFalseExcludesInactiveAreas() {
        long n = System.nanoTime();
        Area active = newFiringArea("IT FA active " + n);
        Area inactive = newInactiveFiringArea("IT FA inactive " + n);
        em.flush();
        em.clear();

        Date date = new Date(1_600_000_000_000L);

        // inactive=false -> only active areas are returned.
        Set<Integer> activeOnly = service.searchFiringAreaPeriods(date, null, null, false, "en").stream()
                .map(fap -> fap.getArea().getId())
                .collect(Collectors.toSet());
        assertTrue(activeOnly.contains(active.getId()),
                "an active firing area must be returned when inactive=false");
        assertFalse(activeOnly.contains(inactive.getId()),
                "an inactive firing area must be excluded when inactive=false");

        // inactive=true -> both active and inactive areas are returned.
        Set<Integer> all = service.searchFiringAreaPeriods(date, null, null, true, "en").stream()
                .map(fap -> fap.getArea().getId())
                .collect(Collectors.toSet());
        assertTrue(all.contains(active.getId()),
                "an active firing area must be returned when inactive=true");
        assertTrue(all.contains(inactive.getId()),
                "an inactive firing area must be included when inactive=true");
    }

    // ------------------------------------------------------------------------------------------- //
    // getFiringAreaPeriods
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getFiringAreaPeriodsReturnsAreaForKnownIdAndNullForUnknownId() {
        Area area = newFiringArea("IT FA get " + System.nanoTime());
        em.flush();
        em.clear();

        Date date = new Date(1_600_000_000_000L);

        FiringAreaPeriodsVo found = service.getFiringAreaPeriods(area.getId(), date, "en");
        assertNotNull(found, "getFiringAreaPeriods must return a value object for a known firing area");
        assertEquals(area.getId(), found.getArea().getId(),
                "the returned value object must wrap the requested area");
        assertTrue(found.getFiringPeriods().isEmpty(),
                "no firing periods were created, so the period list must be empty");

        // An id that does not belong to any firing area yields null.
        FiringAreaPeriodsVo missing = service.getFiringAreaPeriods(-987654, date, "en");
        assertNull(missing, "getFiringAreaPeriods must return null for an unknown area id");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateFiringAreaPeriodsForDate - add / update / delete reconciliation
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateFiringAreaPeriodsForDateAddsUpdatesAndDeletesPeriods() {
        Area area = newFiringArea("IT FA upd " + System.nanoTime());

        Date dayStart = new Date(1_600_000_000_000L); // the search date

        // Two existing periods, both spanning the search day: A will be updated, B will be deleted.
        FiringPeriod a = service.addFiringPeriod(newPeriod(area,
                new Date(dayStart.getTime() + 3_600_000L), new Date(dayStart.getTime() + 7_200_000L)));
        FiringPeriod b = service.addFiringPeriod(newPeriod(area,
                new Date(dayStart.getTime() + 10_800_000L), new Date(dayStart.getTime() + 14_400_000L)));
        em.flush();
        em.clear();

        Integer aId = a.getId();
        Integer bId = b.getId();

        // Build the update VO: keep A (with changed dates), add a brand-new C, drop B.
        FiringAreaPeriodsVo current = service.getFiringAreaPeriods(area.getId(), dayStart, "en");
        assertEquals(2, current.getFiringPeriods().size(), "precondition: A and B must both be present");

        FiringAreaPeriodsVo update = new FiringAreaPeriodsVo();
        update.setArea(current.getArea());
        update.setFiringPeriods(new ArrayList<>());

        // A: updated dates (carry the existing id).
        Date aNewFrom = new Date(dayStart.getTime() + 18_000_000L);
        Date aNewTo = new Date(dayStart.getTime() + 21_600_000L);
        FiringPeriodVo aVo = new FiringPeriodVo();
        aVo.setId(aId);
        aVo.setAreaId(area.getId());
        aVo.setFromDate(aNewFrom);
        aVo.setToDate(aNewTo);
        update.getFiringPeriods().add(aVo);

        // C: brand new (no id).
        Date cFrom = new Date(dayStart.getTime() + 25_200_000L);
        Date cTo = new Date(dayStart.getTime() + 28_800_000L);
        FiringPeriodVo cVo = new FiringPeriodVo();
        cVo.setAreaId(area.getId());
        cVo.setFromDate(cFrom);
        cVo.setToDate(cTo);
        update.getFiringPeriods().add(cVo);

        // (B is omitted -> must be deleted.)

        FiringAreaPeriodsVo result = service.updateFiringAreaPeriodsForDate(update, dayStart, "en");

        em.flush();
        em.clear();

        // A must still exist with the updated dates.
        FiringPeriod reloadedA = em.find(FiringPeriod.class, aId);
        assertNotNull(reloadedA, "the updated firing period A must still exist");
        assertEquals(aNewFrom, reloadedA.getFromDate(), "A's from-date must be updated in the DB");
        assertEquals(aNewTo, reloadedA.getToDate(), "A's to-date must be updated in the DB");

        // B must be deleted.
        assertNull(em.find(FiringPeriod.class, bId), "the omitted firing period B must be deleted");

        // C must have been created: exactly one new firing period for the area besides A.
        List<FiringPeriod> persisted = em.createNamedQuery("FiringPeriod.findByArea", FiringPeriod.class)
                .setParameter("area", em.find(Area.class, area.getId()))
                .getResultList();
        assertEquals(2, persisted.size(), "after reconciliation the area must have exactly A and C");
        Set<Integer> persistedIds = persisted.stream().map(FiringPeriod::getId).collect(Collectors.toSet());
        assertTrue(persistedIds.contains(aId), "A must be among the persisted periods");
        assertFalse(persistedIds.contains(bId), "B must not be among the persisted periods");

        FiringPeriod newC = persisted.stream()
                .filter(fp -> !fp.getId().equals(aId))
                .findFirst()
                .orElse(null);
        assertNotNull(newC, "the new firing period C must have been created");
        assertEquals(cFrom, newC.getFromDate(), "C's from-date must be persisted");
        assertEquals(cTo, newC.getToDate(), "C's to-date must be persisted");

        // The returned VO reflects the reconciled state for the date.
        Set<Integer> resultIds = result.getFiringPeriods().stream()
                .map(FiringPeriodVo::getId)
                .collect(Collectors.toSet());
        assertEquals(persistedIds, resultIds,
                "the returned value object must reflect the reconciled firing periods");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateFiringAreaPeriodsForDate - unknown area -> 400
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateFiringAreaPeriodsForDateUnknownAreaThrows400() {
        FiringAreaPeriodsVo update = new FiringAreaPeriodsVo();
        org.niord.core.area.vo.SystemAreaVo areaVo = new org.niord.core.area.vo.SystemAreaVo();
        areaVo.setId(-555555); // not a firing area
        update.setArea(areaVo);

        NiordWebException ex = assertThrows(NiordWebException.class,
                () -> service.updateFiringAreaPeriodsForDate(update, new Date(1_600_000_000_000L), "en"));
        assertEquals(400, ex.getStatusCode().value(),
                "an unknown firing area must yield HTTP 400");
        assertTrue(ex.getReason().contains("-555555"),
                "the error must name the offending area id, was: " + ex.getReason());
    }

    // ------------------------------------------------------------------------------------------- //
    // generateFiringAreaMessages - message tag wiring
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void generateFiringAreaMessagesAddsEachGeneratedMessageToTheTag() {
        MessageSeries series = messageSeriesService.findBySeriesId("dma-fa");
        assertNotNull(series, "precondition: the seeded dma-fa message series must exist");

        // Persist a tag up-front so it is the managed owning side of the message<->tag m2m link.
        MessageTag tag = new MessageTag();
        tag.setTagId("IT-fa-tag-" + UUID.randomUUID());
        tag.setName("IT FA tag " + System.nanoTime());
        tag.setType(MessageTagType.PRIVATE);
        em.persist(tag);
        em.flush();
        Integer tagId = tag.getId();

        // No current domain is set -> generation targets all seeded firing areas.
        List<Message> result = service.generateFiringAreaMessages(series, tag);
        assertFalse(result.isEmpty(),
                "generateFiringAreaMessages must create a message per seeded firing area");

        Set<Integer> generatedIds = result.stream().map(Message::getId).collect(Collectors.toSet());
        for (Message m : result) {
            assertTrue(m.getTags().stream().anyMatch(t -> t.getId().equals(tagId)),
                    "each generated message must reference the supplied tag");
        }

        em.flush();
        em.clear();

        // The persisted tag must own exactly the generated messages, and its counter must be updated.
        MessageTag reloaded = em.find(MessageTag.class, tagId);
        assertNotNull(reloaded, "the tag row must still exist");
        Set<Integer> taggedIds = reloaded.getMessages().stream()
                .map(Message::getId)
                .collect(Collectors.toSet());
        assertEquals(generatedIds, taggedIds,
                "the tag's message collection must be persisted to contain exactly the generated messages");
        assertEquals(generatedIds.size(), reloaded.getMessageCount(),
                "the tag's denormalised messageCount must be updated to the number of generated messages");
    }

    // ------------------------------------------------------------------------------------------- //
    // generateFiringAreaMessages - no matching areas
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void generateFiringAreaMessagesWithNoMatchingAreasReturnsEmptyAndPersistsNothing() {
        MessageSeries series = messageSeriesService.findBySeriesId("dma-fa");
        assertNotNull(series, "precondition: the seeded dma-fa message series must exist");

        // Restrict the current domain to a lineage that contains a single, freshly created
        // NON-firing area. The firing-area search is then constrained to that lineage and finds
        // nothing, so generation must short-circuit to an empty list.
        Area nonFiring = new Area();
        nonFiring.setType(AreaType.COUNTRY);
        nonFiring.checkCreateDesc("en").setName("IT non-firing " + System.nanoTime());
        nonFiring = areaService.createArea(nonFiring, null);
        em.flush();

        Domain domain = em.find(Domain.class,
                domainLookupService.findByDomainId("niord-fa").getId());
        domain.getAreas().clear();
        domain.getAreas().add(em.find(Area.class, nonFiring.getId()));
        em.flush();

        long messagesBefore = em.createQuery("select count(m) from Message m", Long.class)
                .getSingleResult();

        try {
            domainService.setDomainForCurrentThread("niord-fa");
            List<Message> result = service.generateFiringAreaMessages(series, null);
            assertTrue(result.isEmpty(),
                    "with no firing areas in the current domain's lineage, no messages must be generated");
        } finally {
            domainService.removeDomainForCurrentThread();
        }

        em.flush();
        long messagesAfter = em.createQuery("select count(m) from Message m", Long.class)
                .getSingleResult();
        assertEquals(messagesBefore, messagesAfter,
                "no message rows must be inserted when there are no matching firing areas");
    }
}
