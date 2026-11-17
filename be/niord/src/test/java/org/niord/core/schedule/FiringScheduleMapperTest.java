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
import org.niord.core.domain.DomainMapper;
import org.niord.core.message.MessageSeries;
import org.niord.core.schedule.vo.FiringScheduleVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link FiringScheduleMapper}: the scalar projection plus nested-domain delegation and
 * target-series-by-id behaviour previously embedded in {@code FiringSchedule.toVo()} and the
 * {@code FiringSchedule(FiringScheduleVo)} constructor. The {@link DomainMapper} collaborator is wired
 * by hand with bare domains (no nested areas/categories/series), so its sub-mappers are not exercised.
 */
public class FiringScheduleMapperTest {

    private final FiringScheduleMapper mapper = new FiringScheduleMapper(new DomainMapper());

    private static Domain bareDomain(String id) {
        Domain d = new Domain();
        d.setDomainId(id);
        d.setActive(true);
        return d;
    }

    @Test
    public void toVoCopiesScalarsAndDelegatesDomains() {
        FiringSchedule schedule = new FiringSchedule();
        schedule.setId(7);
        schedule.setDomain(bareDomain("dma-fire"));
        schedule.setTargetDomain(bareDomain("dma-target"));
        schedule.setTargetMessageSeries(new MessageSeries("target-series"));
        schedule.setScheduleDays(14);
        schedule.setActive(true);

        FiringScheduleVo vo = mapper.toVo(schedule);

        assertEquals(Integer.valueOf(7), vo.getId());
        assertEquals("dma-fire", vo.getDomain().getDomainId());
        assertEquals("dma-target", vo.getTargetDomain().getDomainId());
        assertEquals("target-series", vo.getTargetSeriesId());
        assertEquals(Integer.valueOf(14), vo.getScheduleDays());
        assertEquals(true, vo.isActive());
    }

    @Test
    public void toEntityRebuildsTargetSeriesById() {
        FiringScheduleVo vo = new FiringScheduleVo();
        vo.setId(3);
        vo.setDomain(domainVo("dma-fire"));
        vo.setTargetDomain(domainVo("dma-target"));
        vo.setTargetSeriesId("series-x");
        vo.setScheduleDays(7);
        vo.setActive(false);

        FiringSchedule schedule = mapper.toEntity(vo);

        assertEquals(Integer.valueOf(3), schedule.getId());
        assertEquals("dma-fire", schedule.getDomain().getDomainId());
        assertEquals("series-x", schedule.getTargetMessageSeries().getSeriesId());
        assertEquals(Integer.valueOf(7), schedule.getScheduleDays());
        assertEquals(false, schedule.isActive());
    }

    @Test
    public void nullEntityMapsToNull() {
        assertNull(mapper.toVo(null));
    }

    @Test
    public void nullVoToEntityIsNull() {
        assertNull(mapper.toEntity(null));
    }

    private static org.niord.core.domain.vo.DomainVo domainVo(String id) {
        org.niord.core.domain.vo.DomainVo vo = new org.niord.core.domain.vo.DomainVo();
        vo.setDomainId(id);
        return vo;
    }
}
