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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AreaMapper}: the field projection, recursive parent/children traversal,
 * two-tier {@code AreaVo}/{@code SystemAreaVo} behaviour and language filtering that were previously
 * embedded in {@code Area.toVo(Class, DataFilter)} / {@code Area.updateArea(...)}. Mirrors the
 * scenarios in {@link AreaToVoTest} to prove the extraction is behaviour-preserving. Runs as a plain
 * unit test with no persistence context.
 */
public class AreaMapperTest {

    private final AreaMapper mapper = new AreaMapper();

    @Test
    public void roundTripsIdMrnAndActive() {
        Area area = AreaTestFactory.area(1, "da", "Danmark");
        area.setMrn("urn:mrn:dk");
        area.setActive(false);

        AreaVo vo = mapper.toVo(area, AreaVo.class, DataFilter.get());

        assertEquals(Integer.valueOf(1), vo.getId());
        assertEquals("urn:mrn:dk", vo.getMrn());
        assertEquals(false, vo.isActive());
    }

    @Test
    public void includeDetailsCopiesSystemFields() {
        Area area = AreaTestFactory.area(1, "da", "Danmark");
        area.setType(AreaType.FIRING_AREA);
        area.setSiblingSortOrder(2.0);
        area.setMessageSorting(AreaMessageSorting.NS);
        area.setOriginLatitude(56f);
        area.setOriginLongitude(10f);
        area.setOriginAngle(45);
        area.setEditorFields(new ArrayList<>(Arrays.asList("geometry", "type")));

        SystemAreaVo vo = mapper.toVo(area, SystemAreaVo.class, AreaTestFactory.detailsFilter());

        assertEquals(AreaType.FIRING_AREA, vo.getType());
        assertEquals(Double.valueOf(2.0), vo.getSiblingSortOrder());
        assertEquals(AreaMessageSorting.NS, vo.getMessageSorting());
        assertEquals(Float.valueOf(56f), vo.getOriginLatitude());
        assertEquals(Float.valueOf(10f), vo.getOriginLongitude());
        assertEquals(Integer.valueOf(45), vo.getOriginAngle());
        assertEquals(Arrays.asList("geometry", "type"), vo.getEditorFields());
    }

    @Test
    public void detailsOmittedWhenFilterLacksDetails() {
        Area area = AreaTestFactory.area(1, "da", "Danmark");
        area.setType(AreaType.FIRING_AREA);
        area.setMessageSorting(AreaMessageSorting.NS);
        area.setEditorFields(new ArrayList<>(Arrays.asList("type")));

        SystemAreaVo vo = mapper.toVo(area, SystemAreaVo.class, AreaTestFactory.geometryFilter());

        assertNull(vo.getType());
        assertNull(vo.getMessageSorting());
        assertNull(vo.getEditorFields());
    }

    @Test
    public void includeGeometryCopiesGeometry() {
        Area area = AreaTestFactory.area(1, "da", "Danmark");
        area.setGeometry(AreaTestFactory.point(56, 10));

        SystemAreaVo vo = mapper.toVo(area, SystemAreaVo.class, AreaTestFactory.geometryFilter());

        assertNotNull(vo.getGeometry());
        assertTrue(area.getGeometry().equals(JtsConverter.toJts(vo.getGeometry())));
    }

    @Test
    public void includeChildrenRecurses() {
        Area parent = AreaTestFactory.area(1, "da", "P");
        parent.addChild(AreaTestFactory.area(2, "da", "C1"));
        parent.addChild(AreaTestFactory.area(3, "da", "C2"));

        SystemAreaVo vo = mapper.toVo(parent, SystemAreaVo.class, AreaTestFactory.childrenFilter());

        assertEquals(2, vo.getChildren().size());
    }

    @Test
    public void includeParentRecursesToFullParent() {
        Area parent = AreaTestFactory.area(42, "da", "P");
        parent.setMrn("urn:mrn:parent");
        Area child = AreaTestFactory.area(2, "da", "C");
        child.setParent(parent);

        SystemAreaVo vo = mapper.toVo(child, SystemAreaVo.class, AreaTestFactory.parentFilter());

        assertNotNull(vo.getParent());
        assertEquals(Integer.valueOf(42), vo.getParent().getId());
        assertEquals("urn:mrn:parent", vo.getParent().getMrn());
    }

    @Test
    public void parentIdFilterCopiesRealParentId() {
        Area parent = AreaTestFactory.area(42, "da", "P");
        Area child = AreaTestFactory.area(2, "da", "C");
        child.setParent(parent);

        SystemAreaVo vo = mapper.toVo(child, SystemAreaVo.class, AreaTestFactory.parentIdFilter());

        assertNotNull(vo.getParent(), "a placeholder parent VO is created");
        assertEquals(Integer.valueOf(42), vo.getParent().getId());
    }

    @Test
    public void parentTakesPrecedenceOverParentId() {
        Area parent = AreaTestFactory.area(42, "da", "P");
        Area child = AreaTestFactory.area(2, "da", "C");
        child.setParent(parent);

        DataFilter both = DataFilter.get().fields(DataFilter.PARENT, DataFilter.PARENT_ID);
        SystemAreaVo vo = mapper.toVo(child, SystemAreaVo.class, both);

        assertEquals(Integer.valueOf(42), vo.getParent().getId());
    }

    @Test
    public void descriptionsAreFilteredByLanguage() {
        Area area = new Area();
        area.setId(1);
        area.checkCreateDesc("da").setName("Danmark");
        area.checkCreateDesc("en").setName("Denmark");

        SystemAreaVo vo = mapper.toVo(area, SystemAreaVo.class, DataFilter.get().lang("en"));

        assertEquals(1, vo.getDescs().size());
        assertEquals("en", vo.getDescs().get(0).getLang());
        assertEquals("Denmark", vo.getDescs().get(0).getName());
    }

    @Test
    public void emptyDescriptionsLeaveVoDescsNull() {
        Area area = AreaTestFactory.area(1);

        SystemAreaVo vo = mapper.toVo(area, SystemAreaVo.class, DataFilter.get());

        assertNull(vo.getDescs());
    }

    @Test
    public void nullEntityMapsToNullVo() {
        assertNull(mapper.toVo(null, SystemAreaVo.class, DataFilter.get()));
    }

    @Test
    public void toEntityCopiesSystemFields() {
        SystemAreaVo vo = new SystemAreaVo();
        vo.setId(7);
        vo.setMrn("urn:mrn:x");
        vo.setActive(true);
        vo.setType(AreaType.FIRING_AREA);
        vo.setSiblingSortOrder(3.0);
        vo.setMessageSorting(AreaMessageSorting.NS);

        Area area = mapper.toEntity(vo, DataFilter.get());

        assertEquals(Integer.valueOf(7), area.getId());
        assertEquals("urn:mrn:x", area.getMrn());
        assertEquals(true, area.isActive());
        assertEquals(AreaType.FIRING_AREA, area.getType());
        assertEquals(3.0, area.getSiblingSortOrder(), 0.0001);
        assertEquals(AreaMessageSorting.NS, area.getMessageSorting());
    }

    @Test
    public void nullVoToEntityIsNull() {
        assertNull(mapper.toEntity(null, DataFilter.get()));
    }
}
