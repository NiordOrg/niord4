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

import org.niord.core.domain.DomainMapper;
import org.niord.core.message.MessageSeries;
import org.niord.core.schedule.vo.FiringScheduleVo;
import org.springframework.stereotype.Component;


/**
 * Maps between the {@link FiringSchedule} entity and {@link FiringScheduleVo}.
 *
 * <p>Replaces the {@code toVo()} method and the {@code FiringSchedule(FiringScheduleVo)} conversion
 * constructor previously embedded in the entity. Nested domains are delegated to {@link DomainMapper};
 * the target message series is referenced by id only (preserving the entity's behaviour).
 */
@Component
public class FiringScheduleMapper {

    private final DomainMapper domainMapper;

    FiringScheduleMapper(DomainMapper domainMapper) {
        this.domainMapper = domainMapper;
    }

    /** Converts the entity to a value object. */
    public FiringScheduleVo toVo(FiringSchedule schedule) {
        if (schedule == null) {
            return null;
        }
        FiringScheduleVo vo = new FiringScheduleVo();
        vo.setId(schedule.getId());
        vo.setDomain(domainMapper.toVo(schedule.getDomain()));
        vo.setTargetDomain(domainMapper.toVo(schedule.getTargetDomain()));
        vo.setTargetSeriesId(schedule.getTargetMessageSeries().getSeriesId());
        vo.setScheduleDays(schedule.getScheduleDays());
        vo.setActive(schedule.isActive());
        return vo;
    }

    /** Creates a new {@link FiringSchedule} entity from the given value object. */
    public FiringSchedule toEntity(FiringScheduleVo vo) {
        if (vo == null) {
            return null;
        }
        FiringSchedule schedule = new FiringSchedule();
        schedule.setId(vo.getId());
        schedule.setDomain(domainMapper.toEntity(vo.getDomain()));
        schedule.setTargetDomain(domainMapper.toEntity(vo.getTargetDomain()));
        schedule.setTargetMessageSeries(new MessageSeries(vo.getTargetSeriesId()));
        schedule.setScheduleDays(vo.getScheduleDays());
        schedule.setActive(vo.isActive());
        return schedule;
    }
}
