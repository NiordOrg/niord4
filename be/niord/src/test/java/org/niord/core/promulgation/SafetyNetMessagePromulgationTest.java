/*
 * Copyright 2017 Danish Maritime Authority.
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
package org.niord.core.promulgation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.SafetyNetMessagePromulgation.SafetyNetPriority;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.SafetyNetAreaVo;
import org.niord.core.promulgation.vo.SafetyNetAreaVo.SafetyNetCircularAreaVo;
import org.niord.core.promulgation.vo.SafetyNetMessagePromulgationVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral Tier-1 unit tests for {@link SafetyNetMessagePromulgation}.
 *
 * <p>These exercise the entity as a plain POJO: the from-VO constructor, the {@code toVo()} projection,
 * and the {@code update(BaseMessagePromulgation)} merge semantics. No Quarkus container, no CDI, no
 * database and no mocks are involved -- only real domain objects.
 *
 * <p>Key behaviours pinned here:
 * <ul>
 *     <li>The constructor resolves the selected area via {@link SafetyNetMessagePromulgationVo#selectedArea()}
 *         (name match against the supplied area list) and converts it to an entity, yielding {@code null}
 *         when no area matches or the list is empty.</li>
 *     <li>{@code toVo()} copies the SafetyNET-specific fields plus the inherited type/promulgate fields, and
 *         flattens the area to its name.</li>
 *     <li>{@code update(..)} is a no-op for a non-SafetyNET source and a full field copy for a SafetyNET source.</li>
 * </ul>
 */
public class SafetyNetMessagePromulgationTest {

    private PromulgationTypeVo typeVo;

    @BeforeEach
    public void setUp() {
        typeVo = new PromulgationTypeVo();
        typeVo.setTypeId("snw");
        // serviceId is copied by the PromulgationType(PromulgationTypeVo) constructor; keep it realistic.
        typeVo.setServiceId(SafetyNetMessagePromulgation.SERVICE_ID);
        typeVo.setName("SafetyNET NW");
    }

    /** Builds a circular-area VO with the given name. */
    private SafetyNetCircularAreaVo circularAreaVo(String name) {
        SafetyNetCircularAreaVo area = new SafetyNetCircularAreaVo();
        area.setName(name);
        area.setCenter("55 00.000N 010 00.000E");
        area.setRadius("50");
        return area;
    }

    // ------------------------------------------------------------------
    // (1) Constructor-from-VO
    // ------------------------------------------------------------------

    @Test
    public void constructorFromVoCopiesScalarFieldsAndResolvesSelectedArea() {
        SafetyNetMessagePromulgationVo vo = new SafetyNetMessagePromulgationVo();
        vo.setPriority(SafetyNetPriority.URGENCY);
        vo.setText("hello");
        vo.setSafetyNetId("SN-42");
        vo.setPromulgate(true);
        vo.setType(typeVo);
        vo.getAreas().add(circularAreaVo("ALPHA"));
        vo.setAreaName("ALPHA");

        SafetyNetMessagePromulgation entity = new SafetyNetMessagePromulgation(vo);

        assertEquals(SafetyNetPriority.URGENCY, entity.getPriority(),
                "priority should be copied from the VO");
        assertEquals("hello", entity.getText(), "text should be copied from the VO");
        assertEquals("SN-42", entity.getSafetyNetId(), "safetyNetId should be copied from the VO");
        assertTrue(entity.isPromulgate(), "promulgate flag should be copied from the VO");
        assertNotNull(entity.getType(), "type should be constructed from the VO type");
        assertEquals("snw", entity.getType().getTypeId(),
                "type id should be copied from the VO type");

        assertNotNull(entity.getArea(), "the named area should be resolved to an entity");
        assertEquals("ALPHA", entity.getArea().getName(),
                "the resolved area should carry the selected name");
        assertTrue(entity.getArea() instanceof SafetyNetCircularArea,
                "a circular area VO must convert to a SafetyNetCircularArea entity");
    }

    @Test
    public void constructorFromVoLeavesAreaNullWhenAreaNameMatchesNothing() {
        SafetyNetMessagePromulgationVo vo = new SafetyNetMessagePromulgationVo();
        vo.setText("hello");
        vo.setType(typeVo);
        vo.getAreas().add(circularAreaVo("ALPHA"));
        vo.setAreaName("ZZZ"); // no area in the list has this name

        SafetyNetMessagePromulgation entity = new SafetyNetMessagePromulgation(vo);

        assertNull(entity.getArea(), "an unmatched area name must resolve to a null area");
    }

    @Test
    public void constructorFromVoLeavesAreaNullWhenAreaListIsEmpty() {
        SafetyNetMessagePromulgationVo vo = new SafetyNetMessagePromulgationVo();
        vo.setText("hello");
        vo.setType(typeVo);
        vo.setAreaName("ALPHA"); // no areas at all to match against

        SafetyNetMessagePromulgation entity = new SafetyNetMessagePromulgation(vo);

        assertNull(entity.getArea(), "an empty area list must resolve to a null area");
    }

    // ------------------------------------------------------------------
    // (2) toVo()
    // ------------------------------------------------------------------

    @Test
    public void toVoCopiesScalarFieldsAndFlattensAreaToItsName() {
        SafetyNetMessagePromulgation entity = new SafetyNetMessagePromulgation();
        entity.setPriority(SafetyNetPriority.DISTRESS);
        entity.setText("body text");
        entity.setSafetyNetId("SN-99");
        entity.setPromulgate(true);
        entity.setType(new PromulgationType(typeVo));

        SafetyNetCircularArea area = new SafetyNetCircularArea();
        area.setName("BETA");
        entity.setArea(area);

        SafetyNetMessagePromulgationVo vo = entity.toVo();

        assertEquals(SafetyNetPriority.DISTRESS, vo.getPriority(),
                "priority should be copied into the VO");
        assertEquals("body text", vo.getText(), "text should be copied into the VO");
        assertEquals("SN-99", vo.getSafetyNetId(), "safetyNetId should be copied into the VO");
        assertTrue(vo.isPromulgate(), "promulgate flag should be copied into the VO");
        assertNotNull(vo.getType(), "type should be projected into the VO");
        assertEquals("snw", vo.getType().getTypeId(), "type id should be projected into the VO");
        assertEquals("BETA", vo.getAreaName(), "the area should be flattened to its name");
    }

    @Test
    public void toVoLeavesAreaNameNullWhenEntityHasNoArea() {
        SafetyNetMessagePromulgation entity = new SafetyNetMessagePromulgation();
        entity.setPriority(SafetyNetPriority.SAFETY);
        entity.setText("body text");
        entity.setType(new PromulgationType(typeVo));
        // no area set

        SafetyNetMessagePromulgationVo vo = entity.toVo();

        assertNull(vo.getAreaName(), "a null entity area must leave the VO area name unset");
    }

    // ------------------------------------------------------------------
    // (3) update(BaseMessagePromulgation)
    // ------------------------------------------------------------------

    @Test
    public void updateFromNonSafetyNetPromulgationIsANoOp() {
        SafetyNetMessagePromulgation target = new SafetyNetMessagePromulgation();
        target.setPriority(SafetyNetPriority.URGENCY);
        target.setText("orig");
        target.setSafetyNetId("SN-ORIG");
        SafetyNetCircularArea originalArea = new SafetyNetCircularArea();
        originalArea.setName("ORIG-AREA");
        target.setArea(originalArea);

        target.update(new TwitterMessagePromulgation());

        assertEquals("orig", target.getText(), "text must be unchanged after a non-SafetyNET update");
        assertEquals(SafetyNetPriority.URGENCY, target.getPriority(),
                "priority must be unchanged after a non-SafetyNET update");
        assertEquals("SN-ORIG", target.getSafetyNetId(),
                "safetyNetId must be unchanged after a non-SafetyNET update");
        assertNotNull(target.getArea(), "area must be unchanged after a non-SafetyNET update");
        assertEquals("ORIG-AREA", target.getArea().getName(),
                "the same area instance must be retained after a non-SafetyNET update");
    }

    @Test
    public void updateFromAnotherSafetyNetPromulgationCopiesAllFields() {
        // Target holds the "old" state.
        SafetyNetMessagePromulgation target = new SafetyNetMessagePromulgation();
        target.setPriority(SafetyNetPriority.SAFETY);
        target.setText("orig");
        target.setSafetyNetId("SN-OLD");
        target.setPromulgate(false);
        SafetyNetCircularArea oldArea = new SafetyNetCircularArea();
        oldArea.setName("OLD-AREA");
        target.setArea(oldArea);

        // Source holds the "new" state, including base fields (type + promulgate).
        SafetyNetMessagePromulgation source = new SafetyNetMessagePromulgation();
        source.setPriority(SafetyNetPriority.URGENCY);
        source.setText("updated");
        source.setSafetyNetId("SN-NEW");
        source.setPromulgate(true);
        PromulgationType sourceType = new PromulgationType(typeVo);
        source.setType(sourceType);
        SafetyNetCircularArea newArea = new SafetyNetCircularArea();
        newArea.setName("NEW-AREA");
        source.setArea(newArea);

        target.update(source);

        assertEquals(SafetyNetPriority.URGENCY, target.getPriority(),
                "priority must be copied from the SafetyNET source");
        assertEquals("updated", target.getText(), "text must be copied from the SafetyNET source");
        assertEquals("SN-NEW", target.getSafetyNetId(),
                "safetyNetId must be copied from the SafetyNET source");
        assertNotNull(target.getArea(), "area must be copied from the SafetyNET source");
        assertEquals("NEW-AREA", target.getArea().getName(),
                "the source area must replace the target area");
        assertTrue(target.isPromulgate(),
                "promulgate base field must be copied from the SafetyNET source");
        assertNotNull(target.getType(), "type base field must be copied from the SafetyNET source");
        assertEquals("snw", target.getType().getTypeId(),
                "type id base field must be copied from the SafetyNET source");
    }

    @Test
    public void updateFromAnotherSafetyNetPromulgationCanClearScalarFields() {
        // Verifies update copies whatever the source holds, including null/false, not just non-empty values.
        SafetyNetMessagePromulgation target = new SafetyNetMessagePromulgation();
        target.setPriority(SafetyNetPriority.URGENCY);
        target.setText("orig");
        target.setSafetyNetId("SN-OLD");
        target.setPromulgate(true);
        SafetyNetCircularArea oldArea = new SafetyNetCircularArea();
        oldArea.setName("OLD-AREA");
        target.setArea(oldArea);

        SafetyNetMessagePromulgation source = new SafetyNetMessagePromulgation();
        source.setPriority(SafetyNetPriority.SAFETY);
        source.setText(null);
        source.setSafetyNetId(null);
        source.setPromulgate(false);
        source.setType(new PromulgationType(typeVo));
        // source.area left null

        target.update(source);

        assertEquals(SafetyNetPriority.SAFETY, target.getPriority(),
                "priority must follow the source value");
        assertNull(target.getText(), "text must be cleared when the source text is null");
        assertNull(target.getSafetyNetId(), "safetyNetId must be cleared when the source id is null");
        assertNull(target.getArea(), "area must be cleared when the source area is null");
        assertFalse(target.isPromulgate(), "promulgate must follow the source flag");
    }
}
