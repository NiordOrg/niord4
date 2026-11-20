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

import org.niord.core.NiordApp;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.area.Area;
import org.niord.core.area.AreaSearchParams;
import org.niord.core.area.AreaService;
import org.niord.core.area.vo.SystemAreaVo;
import org.niord.core.batch.AbstractBatchableRestService;
import org.niord.core.geojson.JtsConverter;
import org.niord.core.user.Roles;
import org.niord.model.DataFilter;
import org.niord.model.IJsonSerializable;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.GeometryVo;
import org.slf4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST interface for accessing areas.
 */
@RestController
@RequestMapping("/rest/areas")
@Transactional
@PermitAll
public class AreaRestService extends AbstractBatchableRestService {

    private final NiordApp app;

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AreaRestService.class);

    private final AreaService areaService;

    AreaRestService(NiordApp app, AreaService areaService) {
        this.app = app;
        this.areaService = areaService;
    }

    /**
     * Searches for areas matching the given name in the given language
     *
     * @param lang  the language
     * @param name  the search name
     * @param domain  if defined, restricts the search to the areas of the given domain
     * @param geometry  if true, only return areas with geometries
     * @param limit the maximum number of results
     * @return the search result
     */
    @GetMapping(value = "/search", produces = "application/json;charset=UTF-8")
    public List<SystemAreaVo> searchAreas(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "domain", required = false) String domain,
            @RequestParam(name = "geometry", defaultValue = "false") boolean geometry,
            @RequestParam(name = "messageSorting", defaultValue = "false") boolean messageSorting,
            @RequestParam(name = "inactive", defaultValue = "false") boolean inactive,
            @RequestParam(name = "limit", defaultValue = "0") int limit) {

        if (StringUtils.isBlank(name)) {
            return Collections.emptyList();
        }

        AreaSearchParams params = new AreaSearchParams();
        params.language(lang)
                .name(name)
                .domain(domain)
                .geometry(geometry)
                .messageSorting(messageSorting)
                .inactive(inactive)
                .maxSize(limit);

        log.debug(String.format("Searching for areas: %s", params));

        DataFilter f = DataFilter.get().defaultLanguage(app.getDefaultLanguage())
                .fields(DataFilter.PARENT, DataFilter.DETAILS)
                .lang(lang);
        DataFilter filter = (geometry) ? f.fields(DataFilter.GEOMETRY) : f;

        return areaService.searchAreas(params).stream()
                .map(a -> a.toVo(SystemAreaVo.class, filter))
                .collect(Collectors.toList());
    }

    /** Returns the area with the given IDs */
    @GetMapping(value = "/search/{areaIds}", produces = "application/json;charset=UTF-8")
    public List<SystemAreaVo> searchAreaIds(@PathVariable("areaIds") String areaIds,
                                      @RequestParam(name = "lang", required = false) String lang,
                                      @RequestParam(name = "limit", defaultValue = "1000") int limit) {

        log.debug(String.format("Searching for areas ids=%s, lang=%s, limit=%d", areaIds, lang, limit));

        Set<Integer> ids = Arrays.stream(areaIds.split(","))
                .map(Integer::valueOf)
                .collect(Collectors.toSet());

        DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage())
                .lang(lang)
                .fields(DataFilter.PARENT, DataFilter.GEOMETRY, DataFilter.DETAILS);

        return areaService.getAreaDetails(ids).stream()
                .map(a -> a.toVo(SystemAreaVo.class, filter))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /** Returns all areas via a list of hierarchical root areas */
    @GetMapping(value = "/area-roots", produces = "application/json;charset=UTF-8")
    public List<SystemAreaVo> getAreaRoots(@RequestParam(name = "lang", required = false) String lang) {

        DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage())
                .lang(lang)
                .fields(DataFilter.CHILDREN, DataFilter.DETAILS);

        return areaService.getAreaTree().stream()
                .map(a -> a.toVo(SystemAreaVo.class, filter))
                .collect(Collectors.toList());
    }

    /** Returns all areas via a list of hierarchical root areas including geometries */
    @GetMapping(value = "/all", produces = "application/json;charset=UTF-8")
    public List<SystemAreaVo> getAll() {

        DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage())
                .fields(DataFilter.CHILDREN, DataFilter.GEOMETRY, DataFilter.DETAILS);

        return areaService.getAreaTree().stream()
                .map(a -> a.toVo(SystemAreaVo.class, filter))
                .collect(Collectors.toList());
    }

    /** Returns the area with the given ID */
    @GetMapping(value = "/area/{areaId}", produces = "application/json;charset=UTF-8")
    public SystemAreaVo getArea(
            @PathVariable("areaId") Integer areaId,
            @RequestParam(name = "parent", defaultValue = "false") boolean includeParents
    ) throws Exception {
        log.debug("Getting area " + areaId);
        Area area = areaService.getAreaDetails(areaId);

        // Return the area without parent and child areas
        DataFilter filter = DataFilter.get().defaultLanguage(app.getDefaultLanguage())
                .fields(DataFilter.GEOMETRY, DataFilter.DETAILS);

        if (includeParents) {
            filter = filter.fields(DataFilter.PARENT);
        }

        return area == null ? null : area.toVo(SystemAreaVo.class, filter);
    }

    /** Creates a new area */
    @PostMapping(value = {"/area", "/area/"},
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SystemAreaVo createArea(@RequestBody SystemAreaVo areaVo) throws Exception {
        // A create body must not carry an id: Area(areaVo) copies it, and saveEntity() then MERGES onto that
        // existing row rather than persisting a new one, so the "create" would silently overwrite an unrelated
        // area (and orphan-delete its descriptions). The service-level guard used by e.g.
        // SourceService.createSource cannot be applied here, because AreaService.createArea is shared with
        // importArea(), which legitimately receives areas carrying a foreign system's id.
        if (areaVo.getId() != null) {
            throw new NiordWebException(400);
        }
        // Here, not in AreaService.createArea, which the imports share and whose behaviour stays as it is
        if (areaVo.getDescs() == null
                || areaVo.getDescs().stream().noneMatch(d -> d != null && StringUtils.isNotBlank(d.getName()))) {
            throw new NiordWebException("An area needs a name in at least one language", 400);
        }
        Area area = new Area(areaVo);
        log.info("Creating area " + area);
        Integer parentId = (areaVo.getParent() == null) ? null : areaVo.getParent().getId();
        return areaService
                .createArea(area, parentId)
                .toVo(SystemAreaVo.class, DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /** Updates an area */
    @PutMapping(value = "/area/{areaId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public SystemAreaVo updateArea(@PathVariable("areaId") Integer areaId, @RequestBody SystemAreaVo areaVo) throws Exception {
        if (!Objects.equals(areaId, areaVo.getId())) {
            throw new NiordWebException(400);
        }
        // A body without a sibling order keeps the stored one: the editor sends none, and new Area(vo) would turn
        // the missing value into 0, moving the area first among its siblings. Here, not in AreaService.updateAreaData,
        // which the imports share and whose behaviour stays as it is
        if (areaVo.getSiblingSortOrder() == null) {
            Area stored = areaService.getAreaDetails(areaId);
            if (stored != null) {
                areaVo.setSiblingSortOrder(stored.getSiblingSortOrder());
            }
        }
        Area area = new Area(areaVo);
        log.info("Updating area " + area);
        return areaService.updateAreaData(area).toVo(SystemAreaVo.class, DataFilter.get().defaultLanguage(app.getDefaultLanguage()));
    }

    /** Deletes the given area */
    @DeleteMapping("/area/{areaId}")
    @RolesAllowed(Roles.ADMIN)
    public boolean deleteArea(@PathVariable("areaId") Integer areaId) throws Exception {
        log.info("Deleting area " + areaId);
        return areaService.deleteArea(areaId);
    }

    /** Move an area to a new parent area **/
    @PutMapping(value = "/move-area", consumes = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public boolean moveArea(@RequestBody MoveAreaVo moveAreaVo) throws Exception {
        log.info("Moving area " + moveAreaVo.getAreaId() + " to " + moveAreaVo.getParentId());
        return areaService.moveArea(moveAreaVo.getAreaId(), moveAreaVo.getParentId());
    }

    /** Changes sort order of an area in the area tree by moving it up or down among siblings */
    @PutMapping(value = "/change-sort-order", consumes = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.ADMIN)
    public boolean changeSortOrder(@RequestBody ChangeSortOrderVo changeSortOrderVo) throws Exception {
        log.info("Changing sort-order of area " + changeSortOrderVo.getAreaId()
                + " moving " + (changeSortOrderVo.isMoveUp() ? "up" : "down"));
        return areaService.changeSortOrder(changeSortOrderVo.getAreaId(), changeSortOrderVo.isMoveUp());
    }

    /** Re-computes the sort order of the area tree */
    @PutMapping("/recompute-tree-sort-order")
    @RolesAllowed(Roles.ADMIN)
    public boolean recomputeTreeSortOrder() {
        areaService.recomputeTreeSortOrder();
        return true;
    }

    /**
     * Imports an uploaded Areas json file
     *
     * @param request the multi-part form data request
     * @return a status
     */
    @PostMapping(value = "/upload-areas", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "text/plain")
    @RolesAllowed(Roles.ADMIN)
    public String importAreas(MultipartHttpServletRequest request) throws Exception {
        return executeBatchJobFromUploadedFile(request, "area-import");
    }

    /**
     * Returns the list of active areas intersecting with the given geometry down to the given level.
     * The result will be pruned, so that parent areas are not included
     * @param maxLevel the max level in the area tree. Root level is level 1.
     * @return the list of active charts intersecting with the given geometry
     */
    @PostMapping(value = "/intersecting-areas",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.EDITOR)
    public List<SystemAreaVo> computeIntersectingAreas(
            @RequestParam(name = "lang", required = false) String lang,
            @RequestParam(name = "geometry", defaultValue = "false") boolean geometry,
            @RequestParam(name = "domain", defaultValue = "false") boolean domain,
            @RequestParam(name = "maxLevel", defaultValue = "2") int maxLevel,
            @RequestBody FeatureCollectionVo featureCollection) {

        // A coordinate sent as null is refused (400), as the save refuses it, not searched as not a number
        JtsConverter.requireNumbers(featureCollection);
        GeometryVo geometryVo = featureCollection.toGeometry();
        if (geometryVo == null) {
            return Collections.emptyList();
        }

        DataFilter f = DataFilter.get().defaultLanguage(app.getDefaultLanguage())
                .fields(DataFilter.PARENT, DataFilter.DETAILS)
                .lang(lang);
        DataFilter filter = (geometry) ? f.fields(DataFilter.GEOMETRY) : f;

        return areaService.getIntersectingAreas(JtsConverter.toJts(geometryVo), maxLevel, domain).stream()
                .map(a -> a.toVo(SystemAreaVo.class, filter))
                .collect(Collectors.toList());
    }

    /**
     * ******************
     * Helper classes
     * *******************
     */

    /** Encapsulates the parameters used for moving an area to a new parent area */
    @SuppressWarnings("unused")
    public static class MoveAreaVo implements IJsonSerializable {
        Integer areaId, parentId;

        public Integer getAreaId() {
            return areaId;
        }

        public void setAreaId(Integer areaId) {
            this.areaId = areaId;
        }

        public Integer getParentId() {
            return parentId;
        }

        public void setParentId(Integer parentId) {
            this.parentId = parentId;
        }
    }

    /** Encapsulates the parameters used for moving an area up or down among sibling areas */
    @SuppressWarnings("unused")
    public static class ChangeSortOrderVo implements IJsonSerializable {
        Integer areaId;
        boolean moveUp;

        public Integer getAreaId() {
            return areaId;
        }

        public void setAreaId(Integer areaId) {
            this.areaId = areaId;
        }

        public boolean isMoveUp() {
            return moveUp;
        }

        public void setMoveUp(boolean moveUp) {
            this.moveUp = moveUp;
        }
    }
}
