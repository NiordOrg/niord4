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
package org.niord.web;

import org.locationtech.jts.geom.Geometry;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartMapper;
import org.niord.core.chart.ChartService;
import org.niord.core.chart.vo.SystemChartVo;
import org.niord.core.geojson.JtsConverter;
import org.niord.core.user.Roles;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.GeometryVo;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * REST interface for accessing charts.
 */
@RestController
@RequestMapping("/rest/charts")
@Transactional
@PermitAll
public class ChartRestService extends AbstractBatchableRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChartRestService.class);

    private final ChartService chartService;

    private final ChartMapper chartMapper;

    ChartRestService(ChartService chartService, ChartMapper chartMapper) {
        this.chartService = chartService;
        this.chartMapper = chartMapper;
    }

    /** Returns the charts with the given comma-separated chart numbers */
    @GetMapping(value = "/search/{chartNumbers}", produces = "application/json;charset=UTF-8")
    public List<SystemChartVo> getCharts(@PathVariable("chartNumbers") String chartIds,
                                   @RequestParam(name = "limit", defaultValue = "1000") int limit) {
        return chartService.findByChartNumbers(chartIds.split(",")).stream()
                .limit(limit)
                .map(c -> c.toVo(SystemChartVo.class))
                .collect(Collectors.toList());
    }

    /** Searches charts based on the given search parameters */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    public List<SystemChartVo> searchCharts(@RequestParam(name = "name", defaultValue = "") String name,
                                      @RequestParam(name = "inactive", defaultValue = "false") boolean inactive,
                                      @RequestParam(name = "limit", defaultValue = "1000") int limit) {
        return chartService.searchCharts(name, inactive, limit).stream()
                .map(c -> c.toVo(SystemChartVo.class))
                .collect(Collectors.toList());
    }

    /** Returns all charts up to the given limit */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    public List<SystemChartVo> getAllCharts(@RequestParam(name = "limit", defaultValue = "1000") int limit) {
        return chartService.getCharts().stream()
                .limit(limit)
                .map(c -> c.toVo(SystemChartVo.class))
                .collect(Collectors.toList());
    }

    /** Creates a new chart */
    @PostMapping(value = {"/chart", "/chart/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SystemChartVo createChart(@RequestBody SystemChartVo chartVo) throws Exception {
        log.info("Creating chart " + chartVo);
        return chartMapper.toVo(chartService.createChart(chartMapper.toEntity(chartVo)), SystemChartVo.class);
    }

    /** Updates an existing chart */
    @PutMapping(value = "/chart/{chartNumber}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SystemChartVo updateChart(@PathVariable("chartNumber") String chartNumber, @RequestBody SystemChartVo chartVo) throws Exception {
        if (!Objects.equals(chartNumber, chartVo.getChartNumber())) {
            throw new NiordWebException(400);
        }

        log.info("Updating chart " + chartVo);
        return chartMapper.toVo(chartService.updateChart(chartMapper.toEntity(chartVo)), SystemChartVo.class);
    }

    /** Deletes an existing chart */
    @DeleteMapping("/chart/{chartNumber}")
    @RolesAllowed(Roles.ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChart(@PathVariable("chartNumber") String chartNumber) throws Exception {
        log.info("Deleting chart " + chartNumber);
        chartService.deleteChart(chartNumber);
    }

    /**
     * Imports an uploaded Charts json file
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/upload-charts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "text/plain")
    @RolesAllowed(Roles.ADMIN)
    public String importCharts(MultipartHttpServletRequest request) throws Exception {
        return executeBatchJobFromUploadedFile(request, "chart-import");
    }

    /** Computes the charts intersecting with the current message geometry **/
    @PostMapping(value = "/intersecting-charts",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public List<SystemChartVo> computeIntersectingCharts(@RequestBody FeatureCollectionVo featureCollection) {
        // A coordinate sent as null is refused (400), as the save refuses it, not searched as not a number
        JtsConverter.requireNumbers(featureCollection);
        GeometryVo geometryVo = featureCollection.toGeometry();
        if (geometryVo == null) {
            return Collections.emptyList();
        }

        Geometry geometry = JtsConverter.toJts(geometryVo);
        return chartService.getIntersectingCharts(geometry).stream()
                .map(c -> c.toVo(SystemChartVo.class))
                .collect(Collectors.toList());

    }
}
