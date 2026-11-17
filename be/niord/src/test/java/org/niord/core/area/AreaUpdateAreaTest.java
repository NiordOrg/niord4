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
import org.niord.core.area.vo.SystemAreaVo;
import org.niord.core.area.vo.SystemAreaVo.AreaMessageSorting;
import org.niord.core.geojson.JtsConverter;
import org.niord.model.DataFilter;
import org.niord.model.message.AreaVo;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Area#updateArea(AreaVo, DataFilter)} (and the {@code new Area(vo, filter)}
 * constructor it backs). Verifies which fields are copied for plain {@code AreaVo} vs the richer
 * {@code SystemAreaVo}, and how the {@link DataFilter} gates parent, geometry and child inclusion.
 */
public class AreaUpdateAreaTest {

    @Test
    public void plainVoAppliesIdMrnAndActiveOnly() {
        AreaVo vo = new AreaVo();
        vo.setId(7);
        vo.setMrn("urn:mrn:dk");
        vo.setActive(false);

        Area area = new Area();
        area.updateArea(vo, DataFilter.get());

        assertEquals(Integer.valueOf(7), area.getId());
        assertEquals("urn:mrn:dk", area.getMrn());
        assertEquals(false, area.isActive());
        // System-only fields stay at their defaults for a plain AreaVo.
        assertNull(area.getType());
        assertEquals(0.0, area.getSiblingSortOrder(), 0.0);
    }

    @Test
    public void descriptionsAreCreatedFromVo() {
        AreaVo vo = new AreaVo();
        vo.setId(1);
        vo.checkCreateDesc("da").setName("Danmark");
        vo.checkCreateDesc("en").setName("Denmark");

        Area area = new Area();
        area.updateArea(vo, DataFilter.get());

        assertEquals(2, area.getDescs().size());
        assertEquals("Denmark", area.getDesc("en").getName());
        assertSame(area, area.getDesc("en").getEntity());
    }

    @Test
    public void parentIsCreatedRecursivelyWhenFilterIncludesParent() {
        AreaVo grandparent = AreaTestFactory.plainVo(1, "da", "GP");
        AreaVo parent = AreaTestFactory.plainVo(2, "da", "P");
        parent.setParent(grandparent);
        AreaVo vo = AreaTestFactory.plainVo(3, "da", "C");
        vo.setParent(parent);

        Area area = new Area();
        area.updateArea(vo, AreaTestFactory.parentFilter());

        assertEquals(Integer.valueOf(2), area.getParent().getId());
        assertEquals(Integer.valueOf(1), area.getParent().getParent().getId());
    }

    @Test
    public void parentIsNotCreatedWithoutParentField() {
        AreaVo vo = AreaTestFactory.plainVo(3, "da", "C");
        vo.setParent(AreaTestFactory.plainVo(2, "da", "P"));

        Area area = new Area();
        area.updateArea(vo, DataFilter.get());

        assertNull(area.getParent());
    }

    @Test
    public void systemVoAppliesTypeAndSortOrderRegardlessOfFilter() {
        SystemAreaVo vo = new SystemAreaVo();
        vo.setId(1);
        vo.setType(AreaType.FIRING_AREA);
        vo.setSiblingSortOrder(3.0);

        Area area = new Area();
        area.updateArea(vo, DataFilter.get());

        assertEquals(AreaType.FIRING_AREA, area.getType());
        assertEquals(3.0, area.getSiblingSortOrder(), 0.0);
    }

    @Test
    public void systemVoNullSortOrderBecomesZero() {
        SystemAreaVo vo = new SystemAreaVo();
        vo.setId(1);
        vo.setSiblingSortOrder(null);

        Area area = new Area();
        area.updateArea(vo, DataFilter.get());

        assertEquals(0.0, area.getSiblingSortOrder(), 0.0);
    }

    @Test
    public void systemVoMessageSortingAndOriginApplied() {
        SystemAreaVo vo = new SystemAreaVo();
        vo.setId(1);
        vo.setMessageSorting(AreaMessageSorting.CW);
        vo.setOriginLatitude(56f);
        vo.setOriginLongitude(10f);
        vo.setOriginAngle(90);

        Area area = new Area();
        area.updateArea(vo, DataFilter.get());

        assertEquals(AreaMessageSorting.CW, area.getMessageSorting());
        assertEquals(Float.valueOf(56f), area.getOriginLatitude());
        assertEquals(Float.valueOf(10f), area.getOriginLongitude());
        assertEquals(Integer.valueOf(90), area.getOriginAngle());
    }

    @Test
    public void systemVoGeometryAppliedOnlyWhenFilterIncludesGeometry() {
        SystemAreaVo vo = new SystemAreaVo();
        vo.setId(1);
        vo.setGeometry(AreaTestFactory.pointVo(56, 10));

        Area withGeom = new Area();
        withGeom.updateArea(vo, AreaTestFactory.geometryFilter());
        assertTrue(withGeom.getGeometry().equals(JtsConverter.toJts(vo.getGeometry())));

        Area withoutGeom = new Area();
        withoutGeom.updateArea(vo, DataFilter.get());
        assertNull(withoutGeom.getGeometry());
    }

    @Test
    public void systemVoChildrenRecursedOnlyWhenFilterIncludesChildren() {
        SystemAreaVo vo = new SystemAreaVo();
        vo.setId(1);
        vo.setChildren(new ArrayList<>(Arrays.asList(
                AreaTestFactory.sysVo(2, "da", "C1"),
                AreaTestFactory.sysVo(3, "da", "C2"))));

        Area withChildren = new Area();
        withChildren.updateArea(vo, AreaTestFactory.childrenFilter());
        assertEquals(2, withChildren.getChildren().size());
        assertSame(withChildren, withChildren.getChildren().get(0).getParent());

        Area withoutChildren = new Area();
        withoutChildren.updateArea(vo, DataFilter.get());
        assertTrue(withoutChildren.getChildren().isEmpty());
    }

    @Test
    public void editorFieldsAreAppended() {
        SystemAreaVo vo = new SystemAreaVo();
        vo.setId(1);
        vo.setEditorFields(new ArrayList<>(Arrays.asList("geometry", "type")));

        Area area = new Area();
        area.updateArea(vo, DataFilter.get());

        assertEquals(Arrays.asList("geometry", "type"), area.getEditorFields());
    }
}
