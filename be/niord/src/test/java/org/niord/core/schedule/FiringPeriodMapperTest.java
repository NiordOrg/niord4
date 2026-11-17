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
import org.niord.core.area.Area;
import org.niord.core.schedule.vo.FiringPeriodVo;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for {@link FiringPeriodMapper}. */
public class FiringPeriodMapperTest {

    private final FiringPeriodMapper mapper = new FiringPeriodMapper();

    @Test
    public void roundTripPreservesFieldsAndAreaId() {
        Date from = new Date(1_000_000L);
        Date to = new Date(2_000_000L);

        FiringPeriodVo vo = new FiringPeriodVo();
        vo.setId(1);
        vo.setLegacyId("legacy-1");
        vo.setFromDate(from);
        vo.setToDate(to);

        Area area = new Area();
        area.setId(5);

        FiringPeriod fp = mapper.toEntity(area, vo);
        FiringPeriodVo back = mapper.toVo(fp);

        assertEquals(Integer.valueOf(1), back.getId());
        assertEquals("legacy-1", back.getLegacyId());
        assertEquals(Integer.valueOf(5), back.getAreaId());
        assertEquals(from, back.getFromDate());
        assertEquals(to, back.getToDate());
    }

    @Test
    public void toVoMatchesEntityMethod() {
        Area area = new Area();
        area.setId(9);
        FiringPeriodVo vo = new FiringPeriodVo();
        vo.setId(2);
        vo.setFromDate(new Date(3_000_000L));
        vo.setToDate(new Date(4_000_000L));
        FiringPeriod fp = mapper.toEntity(area, vo);

        assertEquals(fp.toVo().getAreaId(), mapper.toVo(fp).getAreaId());
        assertEquals(fp.toVo().getFromDate(), mapper.toVo(fp).getFromDate());
    }

    @Test
    public void nullEntityMapsToNull() {
        assertNull(mapper.toVo(null));
    }

    @Test
    public void nullVoToEntityIsNull() {
        assertNull(mapper.toEntity(null));
    }
}
