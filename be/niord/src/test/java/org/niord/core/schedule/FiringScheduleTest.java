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

import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.domain.vo.DomainVo;
import org.niord.core.message.MessageSeries;
import org.niord.core.schedule.vo.FiringScheduleVo;

import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link FiringSchedule} and {@link FiringScheduleVo#toString()}.
 *
 * <p>No database or Quarkus container is involved: {@link FiringSchedule} is exercised purely as a
 * domain object. These tests pin the observable contract of {@link FiringSchedule#targetTimeZone()},
 * the entity/VO conversions, and the simple accessor behaviour.
 */
public class FiringScheduleTest {

    /* ----------------------------------------------------------------------------------------- */
    /* targetTimeZone()                                                                          */
    /* ----------------------------------------------------------------------------------------- */

    @Test
    public void targetTimeZone_nullTargetDomain_returnsDefaultTimeZone() {
        FiringSchedule schedule = new FiringSchedule();
        // No target domain has been set.
        assertNull(schedule.getTargetDomain());

        // The contract: fall back to the JVM default time zone.
        assertEquals(TimeZone.getDefault(), schedule.targetTimeZone());
    }

    @Test
    public void targetTimeZone_targetDomainWithExplicitZone_returnsThatZone() {
        Domain targetDomain = new Domain();
        targetDomain.setTimeZone("America/New_York");

        FiringSchedule schedule = new FiringSchedule();
        schedule.setTargetDomain(targetDomain);

        TimeZone expected = TimeZone.getTimeZone("America/New_York");
        assertEquals(expected, schedule.targetTimeZone());
        // And it must delegate to the target domain's own computation.
        assertEquals(targetDomain.timeZone(), schedule.targetTimeZone());
    }

    @Test
    public void targetTimeZone_targetDomainWithoutZone_matchesDomainComputedDefault() {
        // A target domain with a blank time zone: Domain.timeZone() then resolves to the default ID.
        Domain targetDomain = new Domain();
        assertNull(targetDomain.getTimeZone());

        FiringSchedule schedule = new FiringSchedule();
        schedule.setTargetDomain(targetDomain);

        // targetTimeZone() must equal whatever the target domain computes for itself.
        assertEquals(targetDomain.timeZone(), schedule.targetTimeZone());
        // Domain.timeZone() with a blank zone resolves via the default zone's ID.
        assertEquals(TimeZone.getTimeZone(TimeZone.getDefault().getID()), schedule.targetTimeZone());
    }

    @Test
    public void targetTimeZone_unknownZoneId_resolvesToGmtFallback() {
        // TimeZone.getTimeZone() returns GMT for an unrecognised ID (it does not throw); pin that.
        Domain targetDomain = new Domain();
        targetDomain.setTimeZone("Totally/Bogus_" + System.nanoTime());

        FiringSchedule schedule = new FiringSchedule();
        schedule.setTargetDomain(targetDomain);

        assertEquals(TimeZone.getTimeZone("GMT"), schedule.targetTimeZone());
        assertEquals(targetDomain.timeZone(), schedule.targetTimeZone());
    }

    /* ----------------------------------------------------------------------------------------- */
    /* Getters / setters round-trip                                                              */
    /* ----------------------------------------------------------------------------------------- */

    @Test
    public void newFiringSchedule_isInactiveByDefault() {
        FiringSchedule schedule = new FiringSchedule();
        assertFalse(schedule.isActive(), "active must default to false on a fresh entity");
    }

    @Test
    public void gettersAndSetters_roundTrip() {
        Domain domain = new Domain();
        domain.setDomainId("src-" + System.nanoTime());

        Domain targetDomain = new Domain();
        targetDomain.setDomainId("tgt-" + System.nanoTime());

        MessageSeries series = new MessageSeries("series-" + System.nanoTime());

        FiringSchedule schedule = new FiringSchedule();
        schedule.setDomain(domain);
        schedule.setTargetDomain(targetDomain);
        schedule.setTargetMessageSeries(series);
        schedule.setScheduleDays(14);
        schedule.setActive(true);

        assertSame(domain, schedule.getDomain());
        assertSame(targetDomain, schedule.getTargetDomain());
        assertSame(series, schedule.getTargetMessageSeries());
        assertEquals(Integer.valueOf(14), schedule.getScheduleDays());
        assertTrue(schedule.isActive());
    }

    @Test
    public void setActive_canToggleBackToFalse() {
        FiringSchedule schedule = new FiringSchedule();
        schedule.setActive(true);
        assertTrue(schedule.isActive());
        schedule.setActive(false);
        assertFalse(schedule.isActive());
    }

    @Test
    public void scheduleDays_acceptsNull() {
        FiringSchedule schedule = new FiringSchedule();
        schedule.setScheduleDays(null);
        assertNull(schedule.getScheduleDays());
    }

    /* ----------------------------------------------------------------------------------------- */
    /* FiringScheduleVo(...) constructor and toVo() round-trip                                    */
    /* ----------------------------------------------------------------------------------------- */

    @Test
    public void voConstructor_copiesScalarFieldsAndDomainIds() {
        String srcId = "src-" + System.nanoTime();
        String tgtId = "tgt-" + System.nanoTime();
        String seriesId = "series-" + System.nanoTime();

        FiringScheduleVo vo = new FiringScheduleVo();
        vo.setId(42);
        vo.setDomain(new DomainVo(srcId));
        vo.setTargetDomain(new DomainVo(tgtId));
        vo.setTargetSeriesId(seriesId);
        vo.setScheduleDays(7);
        vo.setActive(true);

        FiringSchedule schedule = new FiringSchedule(vo);

        assertEquals(Integer.valueOf(42), schedule.getId());
        assertEquals(srcId, schedule.getDomain().getDomainId());
        assertEquals(tgtId, schedule.getTargetDomain().getDomainId());
        assertEquals(seriesId, schedule.getTargetMessageSeries().getSeriesId());
        assertEquals(Integer.valueOf(7), schedule.getScheduleDays());
        assertTrue(schedule.isActive());
    }

    @Test
    public void toVo_copiesScalarFieldsAndDomainIds() {
        String srcId = "src-" + System.nanoTime();
        String tgtId = "tgt-" + System.nanoTime();
        String seriesId = "series-" + System.nanoTime();

        Domain domain = new Domain();
        domain.setDomainId(srcId);
        Domain targetDomain = new Domain();
        targetDomain.setDomainId(tgtId);

        FiringSchedule schedule = new FiringSchedule();
        schedule.setId(99);
        schedule.setDomain(domain);
        schedule.setTargetDomain(targetDomain);
        schedule.setTargetMessageSeries(new MessageSeries(seriesId));
        schedule.setScheduleDays(21);
        schedule.setActive(false);

        FiringScheduleVo vo = schedule.toVo();

        assertEquals(Integer.valueOf(99), vo.getId());
        assertEquals(srcId, vo.getDomain().getDomainId());
        assertEquals(tgtId, vo.getTargetDomain().getDomainId());
        assertEquals(seriesId, vo.getTargetSeriesId());
        assertEquals(Integer.valueOf(21), vo.getScheduleDays());
        assertFalse(vo.isActive());
    }

    @Test
    public void voConstructorThenToVo_preservesIdentifyingValues() {
        String srcId = "src-" + System.nanoTime();
        String tgtId = "tgt-" + System.nanoTime();
        String seriesId = "series-" + System.nanoTime();

        FiringScheduleVo original = new FiringScheduleVo();
        original.setId(5);
        original.setDomain(new DomainVo(srcId));
        original.setTargetDomain(new DomainVo(tgtId));
        original.setTargetSeriesId(seriesId);
        original.setScheduleDays(3);
        original.setActive(true);

        FiringScheduleVo roundTripped = new FiringSchedule(original).toVo();

        assertEquals(original.getId(), roundTripped.getId());
        assertEquals(srcId, roundTripped.getDomain().getDomainId());
        assertEquals(tgtId, roundTripped.getTargetDomain().getDomainId());
        assertEquals(seriesId, roundTripped.getTargetSeriesId());
        assertEquals(original.getScheduleDays(), roundTripped.getScheduleDays());
        assertTrue(roundTripped.isActive());
    }

    /* ----------------------------------------------------------------------------------------- */
    /* FiringScheduleVo.toString()                                                               */
    /* ----------------------------------------------------------------------------------------- */

    @Test
    public void voToString_containsDomainIdsSeriesIdAndActiveFlag() {
        String srcId = "src-" + System.nanoTime();
        String tgtId = "tgt-" + System.nanoTime();
        String seriesId = "series-" + System.nanoTime();

        FiringScheduleVo vo = new FiringScheduleVo();
        vo.setId(11);
        vo.setDomain(new DomainVo(srcId));
        vo.setTargetDomain(new DomainVo(tgtId));
        vo.setTargetSeriesId(seriesId);
        vo.setScheduleDays(10);
        vo.setActive(true);

        String s = vo.toString();

        assertTrue(s.contains(srcId), "must contain source domainId");
        assertTrue(s.contains(tgtId), "must contain target domainId");
        assertTrue(s.contains(seriesId), "must contain target series id");
        assertTrue(s.contains("scheduleDays=10"), "must contain the schedule days");
        assertTrue(s.contains("active=true"), "must report the active flag");
        assertTrue(s.contains("id=11"), "must contain the id");
    }

    @Test
    public void voToString_reportsInactiveFlag() {
        FiringScheduleVo vo = new FiringScheduleVo();
        vo.setDomain(new DomainVo("d-" + System.nanoTime()));
        vo.setTargetDomain(new DomainVo("t-" + System.nanoTime()));
        vo.setActive(false);

        assertTrue(vo.toString().contains("active=false"));
    }

    /**
     * Fixed (docs/potential-errors.md SCHED1): {@link FiringScheduleVo#toString()} now renders the
     * domain ids null-safely, so a VO with no domain set no longer throws NPE (a toString() used in
     * log lines must never throw). It previously dereferenced {@code domain.getDomainId()} unguarded.
     */
    @Test
    public void voToString_nullDomain_rendersNullSafely() {
        FiringScheduleVo vo = new FiringScheduleVo();
        vo.setId(7);
        // domain and targetDomain left null.

        String s = vo.toString();

        // Comma-anchored so the domain= clause is verified independently of the targetDomain= clause.
        assertTrue(s.contains(", domain=null,"), "must not throw and must render domain=null");
        assertTrue(s.contains(", targetDomain=null,"), "must render targetDomain=null");
        assertTrue(s.contains("id=7"), "other fields still rendered");
    }
}
