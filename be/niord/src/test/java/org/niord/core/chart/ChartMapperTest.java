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
package org.niord.core.chart;

import org.junit.jupiter.api.Test;
import org.niord.core.chart.vo.SystemChartVo;
import org.niord.core.geojson.JtsConverter;
import org.niord.model.DataFilter;
import org.niord.model.message.ChartVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ChartMapper}: pins the field projection that was previously embedded in
 * {@code Chart.toVo(Class, DataFilter)} / {@code Chart.updateChart(ChartVo)}, so the extraction is
 * provably behaviour-preserving. Runs as a plain unit test with no persistence context.
 */
public class ChartMapperTest {

    private final ChartMapper mapper = new ChartMapper();

    private static Chart chart() {
        Chart c = new Chart();
        c.setChartNumber("101");
        c.setInternationalNumber(1234);
        c.setActive(true);
        c.setScale(50000);
        c.setName("Kattegat");
        c.setHorizontalDatum("WGS84");
        c.setGeometry(JtsConverter.toJtsPoint(56, 10));
        return c;
    }

    @Test
    public void plainVoCopiesNonSystemFields() {
        ChartVo vo = mapper.toVo(chart(), ChartVo.class);

        assertEquals("101", vo.getChartNumber());
        assertEquals(Integer.valueOf(1234), vo.getInternationalNumber());
        assertEquals(true, vo.isActive());
        assertEquals(Integer.valueOf(50000), vo.getScale());
        // ALL filter => details included => name copied
        assertEquals("Kattegat", vo.getName());
    }

    @Test
    public void systemVoWithAllFilterCopiesGeometryAndDatum() {
        SystemChartVo vo = mapper.toVo(chart(), SystemChartVo.class);

        assertEquals("Kattegat", vo.getName());
        assertEquals("WGS84", vo.getHorizontalDatum());
        assertNotNull(vo.getGeometry());
        assertTrue(chart().getGeometry().equals(JtsConverter.toJts(vo.getGeometry())));
    }

    @Test
    public void geometryOnlyFilterOmitsDetails() {
        SystemChartVo vo = mapper.toVo(chart(), SystemChartVo.class,
                DataFilter.get().fields(DataFilter.GEOMETRY));

        assertNotNull(vo.getGeometry());
        assertNull(vo.getName(), "name is a detail field and must be omitted");
        assertNull(vo.getHorizontalDatum(), "horizontalDatum is a detail field and must be omitted");
    }

    @Test
    public void detailsOnlyFilterOmitsGeometry() {
        SystemChartVo vo = mapper.toVo(chart(), SystemChartVo.class,
                DataFilter.get().fields(DataFilter.DETAILS));

        assertEquals("Kattegat", vo.getName());
        assertEquals("WGS84", vo.getHorizontalDatum());
        assertNull(vo.getGeometry(), "geometry must be omitted without the geometry filter");
    }

    @Test
    public void nullEntityMapsToNullVo() {
        assertNull(mapper.toVo(null, SystemChartVo.class));
    }

    @Test
    public void toEntityCopiesSystemFields() {
        SystemChartVo vo = new SystemChartVo();
        vo.setChartNumber("202");
        vo.setInternationalNumber(99);
        vo.setActive(false);
        vo.setScale(25000);
        vo.setName("Skagerrak");
        vo.setHorizontalDatum("ED50");
        vo.setGeometry(JtsConverter.fromJts(JtsConverter.toJtsPoint(57, 9)));

        Chart chart = mapper.toEntity(vo);

        assertEquals("202", chart.getChartNumber());
        assertEquals(Integer.valueOf(99), chart.getInternationalNumber());
        assertEquals(false, chart.isActive());
        assertEquals(Integer.valueOf(25000), chart.getScale());
        assertEquals("Skagerrak", chart.getName());
        assertEquals("ED50", chart.getHorizontalDatum());
        assertNotNull(chart.getGeometry());
    }

    @Test
    public void plainVoToEntityLeavesGeometryAndDatumNull() {
        ChartVo vo = new ChartVo();
        vo.setChartNumber("303");
        vo.setActive(true);

        Chart chart = mapper.toEntity(vo);

        assertEquals("303", chart.getChartNumber());
        assertNull(chart.getGeometry());
        assertNull(chart.getHorizontalDatum());
    }

    @Test
    public void updateEntityOverwritesExistingFields() {
        Chart chart = chart();

        ChartVo vo = new ChartVo();
        vo.setChartNumber("999");
        vo.setActive(false);
        vo.setScale(1000);
        vo.setName("Updated");

        mapper.updateEntity(chart, vo);

        assertEquals("999", chart.getChartNumber());
        assertEquals(false, chart.isActive());
        assertEquals(Integer.valueOf(1000), chart.getScale());
        assertEquals("Updated", chart.getName());
    }

    @Test
    public void nullVoToEntityIsNull() {
        assertNull(mapper.toEntity(null));
    }
}
