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

import org.niord.core.area.Area;
import org.niord.core.schedule.vo.FiringPeriodVo;
import org.springframework.stereotype.Component;

/**
 * Maps between the {@link FiringPeriod} entity and {@link FiringPeriodVo}.
 *
 * <p>Replaces the {@code toVo()} method previously embedded in the entity. {@code FiringPeriodVo} is
 * single-tier and references its area by id only. The inbound path needs the resolved {@link Area}
 * (it cannot be reconstructed from an id alone), so {@code toEntity} accepts it as an argument,
 * mirroring the entity's {@code FiringPeriod(Area, FiringPeriodVo)} constructor.
 */
@Component
public class FiringPeriodMapper {

    /** Converts the entity to a value object. */
    public FiringPeriodVo toVo(FiringPeriod firingPeriod) {
        if (firingPeriod == null) {
            return null;
        }
        FiringPeriodVo vo = new FiringPeriodVo();
        vo.setId(firingPeriod.getId());
        vo.setLegacyId(firingPeriod.getLegacyId());
        vo.setAreaId(firingPeriod.getArea().getId());
        vo.setFromDate(firingPeriod.getFromDate());
        vo.setToDate(firingPeriod.getToDate());
        return vo;
    }

    /** Creates a new {@link FiringPeriod} entity (without a resolved area) from the value object. */
    public FiringPeriod toEntity(FiringPeriodVo vo) {
        return toEntity(null, vo);
    }

    /** Creates a new {@link FiringPeriod} entity bound to the given resolved {@link Area}. */
    public FiringPeriod toEntity(Area area, FiringPeriodVo vo) {
        if (vo == null) {
            return null;
        }
        return new FiringPeriod(area, vo);
    }
}
