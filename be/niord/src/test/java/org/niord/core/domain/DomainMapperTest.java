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
package org.niord.core.domain;

import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.area.AreaMapper;
import org.niord.core.category.Category;
import org.niord.core.domain.vo.DomainVo;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesMapper;
import org.niord.model.message.MainType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DomainMapper}: the scalar projection plus nested area/category/message-series
 * mapping that was previously embedded in {@code Domain.toVo()} / {@code Domain.updateDomain(...)}.
 * Collaborators are wired by hand.
 */
public class DomainMapperTest {

    private final DomainMapper mapper = new DomainMapper();

    {
        mapper.areaMapper = new AreaMapper();
        mapper.messageSeriesMapper = new MessageSeriesMapper();
    }

    private static Domain domain() {
        Domain d = new Domain();
        d.setDomainId("dma-nw");
        d.setActive(true);
        d.setName("Danish NW");
        d.setLatitude(56f);
        d.setLongitude(10f);
        return d;
    }

    @Test
    public void copiesScalarFields() {
        DomainVo vo = mapper.toVo(domain());

        assertEquals("dma-nw", vo.getDomainId());
        assertEquals(true, vo.isActive());
        assertEquals("Danish NW", vo.getName());
        assertEquals(Float.valueOf(56f), vo.getLat());
        assertEquals(Float.valueOf(10f), vo.getLon());
        // firingSchedule association is absent -> false
        assertEquals(Boolean.FALSE, vo.getFiringSchedule());
    }

    @Test
    public void mapsNestedAreasCategoriesAndSeries() {
        Domain d = domain();

        Area area = new Area();
        area.setId(1);
        area.setMrn("urn:mrn:area");
        d.setAreas(java.util.List.of(area));

        Category category = new Category();
        category.setId(2);
        d.setCategories(java.util.List.of(category));

        MessageSeries series = new MessageSeries();
        series.setSeriesId("dma-nw-series");
        series.setMainType(MainType.NW);
        d.setMessageSeries(java.util.List.of(series));

        DomainVo vo = mapper.toVo(d);

        assertEquals(1, vo.getAreas().size());
        assertEquals(Integer.valueOf(1), vo.getAreas().get(0).getId());
        assertEquals(1, vo.getCategories().size());
        assertEquals(Integer.valueOf(2), vo.getCategories().get(0).getId());
        assertEquals(1, vo.getMessageSeries().size());
        assertEquals("dma-nw-series", vo.getMessageSeries().get(0).getSeriesId());
    }

    @Test
    public void toEntityCopiesScalarsAndBlanksMessageSortOrder() {
        DomainVo vo = new DomainVo();
        vo.setDomainId("dma-nm");
        vo.setActive(false);
        vo.setName("NM");
        vo.setMessageSortOrder("   "); // blank -> null

        Domain d = mapper.toEntity(vo);

        assertEquals("dma-nm", d.getDomainId());
        assertEquals(false, d.isActive());
        assertEquals("NM", d.getName());
        assertNull(d.getMessageSortOrder());
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
