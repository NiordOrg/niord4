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

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.niord.core.geojson.JtsConverter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the package-visible {@link Area#geometryChanged(Area)} null- and equality-handling.
 * This test lives in the production package so it can invoke the {@code protected} method directly.
 */
public class AreaGeometryChangedTest {

    private Area areaWithGeometry(Geometry g) {
        Area a = new Area();
        a.setGeometry(g);
        return a;
    }

    @Test
    public void bothNullIsNotChanged() {
        assertFalse(areaWithGeometry(null).geometryChanged(areaWithGeometry(null)));
    }

    @Test
    public void originGeometryNullOtherSetIsChanged() {
        assertTrue(areaWithGeometry(null).geometryChanged(areaWithGeometry(AreaTestFactory.point(56, 10))));
    }

    @Test
    public void originGeometrySetOtherNullIsChanged() {
        assertTrue(areaWithGeometry(AreaTestFactory.point(56, 10)).geometryChanged(areaWithGeometry(null)));
    }

    @Test
    public void equalGeometriesNotChanged() {
        assertFalse(areaWithGeometry(AreaTestFactory.point(56, 10))
                .geometryChanged(areaWithGeometry(AreaTestFactory.point(56, 10))));
    }

    @Test
    public void differentGeometriesChanged() {
        assertTrue(areaWithGeometry(AreaTestFactory.point(56, 10))
                .geometryChanged(areaWithGeometry(AreaTestFactory.point(57, 11))));
    }

    @Test
    public void geometrySurvivingAJtsConverterRoundTripIsNotChanged() {
        Geometry original = AreaTestFactory.wkt("POLYGON ((0 0, 0 5, 5 5, 5 0, 0 0))");
        Geometry roundTripped = JtsConverter.toJts(JtsConverter.fromJts(original));

        assertFalse(areaWithGeometry(original).geometryChanged(areaWithGeometry(roundTripped)));
    }
}
