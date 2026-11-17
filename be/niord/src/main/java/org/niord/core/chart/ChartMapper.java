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

import org.niord.core.chart.vo.SystemChartVo;
import org.niord.core.geojson.JtsConverter;
import org.niord.model.DataFilter;
import org.niord.model.message.ChartVo;
import org.springframework.stereotype.Component;

/**
 * Maps between the {@link Chart} entity and {@link ChartVo}.
 *
 * <p>Replaces the {@code toVo()}/{@code updateChart()} methods that were previously embedded in the
 * {@link Chart} entity. Conversion lives here as a stateless, dependency-injected bean so it can be
 * unit-tested without a persistence context.
 */
@Component
public class ChartMapper {

    /** Converts the entity to a value object, including all fields. */
    public <C extends ChartVo> C toVo(Chart chart, Class<C> clz) {
        return toVo(chart, clz, DataFilter.get().fields(DataFilter.ALL));
    }

    /** Converts the entity to a value object, honouring the data filter. */
    public <C extends ChartVo> C toVo(Chart chart, Class<C> clz, DataFilter filter) {
        if (chart == null) {
            return null;
        }

        ChartVo vo = SystemChartVo.class.isAssignableFrom(clz) ? new SystemChartVo() : new ChartVo();
        vo.setChartNumber(chart.getChartNumber());
        vo.setInternationalNumber(chart.getInternationalNumber());
        vo.setActive(chart.isActive());
        vo.setScale(chart.getScale());

        DataFilter compFilter = filter.forComponent(Chart.class);
        if (compFilter.includeDetails()) {
            vo.setName(chart.getName());
        }

        if (vo instanceof SystemChartVo sysChart) {
            if (compFilter.includeGeometry()) {
                sysChart.setGeometry(JtsConverter.fromJts(chart.getGeometry()));
            }
            if (compFilter.includeDetails()) {
                sysChart.setHorizontalDatum(chart.getHorizontalDatum());
            }
        }
        return clz.cast(vo);
    }

    /** Creates a new {@link Chart} entity from the given value object. */
    public Chart toEntity(ChartVo vo) {
        if (vo == null) {
            return null;
        }
        Chart chart = new Chart();
        updateEntity(chart, vo);
        return chart;
    }

    /** Updates an existing {@link Chart} entity from the given value object. */
    public void updateEntity(Chart chart, ChartVo vo) {
        chart.setChartNumber(vo.getChartNumber());
        chart.setInternationalNumber(vo.getInternationalNumber());
        chart.setActive(vo.isActive());
        chart.setScale(vo.getScale());
        chart.setName(vo.getName());

        if (vo instanceof SystemChartVo sysChart) {
            // Stored on the globe: moved by whole turns into range, a latitude beyond 90 refused
            chart.setGeometry(JtsConverter.toJtsOnGlobe(sysChart.getGeometry()));
            chart.setHorizontalDatum(sysChart.getHorizontalDatum());
        }
    }
}
