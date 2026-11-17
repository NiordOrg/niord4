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
package org.niord.core.area;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.niord.core.area.vo.SystemAreaVo;
import org.niord.core.geojson.JtsConverter;
import org.niord.model.DataFilter;
import org.niord.model.geojson.GeometryVo;
import org.niord.model.message.AreaDescVo;
import org.niord.model.message.AreaVo;

/**
 * Shared in-memory builders for the {@code area} package unit tests.
 *
 * <p>This class deliberately has no {@code Test} suffix so Surefire does not treat it as a test.
 * It contains no assertions; it only constructs domain objects to keep the test classes terse and
 * to express test intent ("an area named X with a parent Y") rather than boilerplate wiring.
 */
final class AreaTestFactory {

    private AreaTestFactory() {
    }

    /** An Area with the given id and a single localized description. */
    static Area area(Integer id, String lang, String name) {
        Area area = new Area();
        area.setId(id);
        area.checkCreateDesc(lang).setName(name);
        return area;
    }

    /** An Area with the given id and no descriptions. */
    static Area area(Integer id) {
        Area area = new Area();
        area.setId(id);
        return area;
    }

    /** A JTS point at the given latitude/longitude. */
    static Geometry point(double lat, double lon) {
        return JtsConverter.toJtsPoint(lat, lon);
    }

    /** A JTS geometry parsed from WKT (wraps the checked ParseException). */
    static Geometry wkt(String wkt) {
        try {
            return JtsConverter.wktToJts(wkt);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Invalid WKT: " + wkt, e);
        }
    }

    /** A GeoJSON point VO at the given latitude/longitude. */
    static GeometryVo pointVo(double lat, double lon) {
        return JtsConverter.fromJts(point(lat, lon));
    }

    /** A plain {@link AreaVo} (NOT a SystemAreaVo) with the given id and one description. */
    static AreaVo plainVo(Integer id, String lang, String name) {
        AreaVo vo = new AreaVo();
        vo.setId(id);
        vo.checkCreateDesc(lang).setName(name);
        return vo;
    }

    /** A {@link SystemAreaVo} with the given id and one description. */
    static SystemAreaVo sysVo(Integer id, String lang, String name) {
        SystemAreaVo vo = new SystemAreaVo();
        vo.setId(id);
        vo.checkCreateDesc(lang).setName(name);
        return vo;
    }

    /** A {@link SystemAreaVo} carrying only a sibling sort order (used for ordering tests). */
    static SystemAreaVo sysVoWithSort(Double siblingSortOrder) {
        SystemAreaVo vo = new SystemAreaVo();
        vo.setSiblingSortOrder(siblingSortOrder);
        return vo;
    }

    /** A standalone {@link AreaDescVo}. */
    static AreaDescVo descVo(String lang, String name) {
        AreaDescVo vo = new AreaDescVo();
        vo.setLang(lang);
        vo.setName(name);
        return vo;
    }

    /* Canonical DataFilters mirroring the ones AreaRestService applies. */

    static DataFilter detailsFilter() {
        return DataFilter.get().fields(DataFilter.DETAILS);
    }

    static DataFilter geometryFilter() {
        return DataFilter.get().fields(DataFilter.GEOMETRY);
    }

    static DataFilter parentFilter() {
        return DataFilter.get().fields(DataFilter.PARENT);
    }

    static DataFilter parentIdFilter() {
        return DataFilter.get().fields(DataFilter.PARENT_ID);
    }

    static DataFilter childrenFilter() {
        return DataFilter.get().fields(DataFilter.CHILDREN);
    }
}
