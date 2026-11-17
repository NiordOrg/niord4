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

package org.niord.core.promulgation.vo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.SafetyNetMessagePromulgation;
import org.niord.core.promulgation.SafetyNetMessagePromulgation.SafetyNetPriority;
import org.niord.core.promulgation.vo.SafetyNetAreaVo.SafetyNetCircularAreaVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral Tier-1 unit tests for {@link SafetyNetMessagePromulgationVo}.
 *
 * Verifies the pure POJO behavior: the promulgationDataDefined() predicate,
 * the reset() mutation, the selectedArea() lookup and default priority / entity
 * conversion. No persistence, CDI or framework wiring is involved.
 */
public class SafetyNetMessagePromulgationVoTest {

    private SafetyNetMessagePromulgationVo vo;

    @BeforeEach
    public void setUp() {
        vo = new SafetyNetMessagePromulgationVo();
    }

    /** Helper that builds a circular area VO with the given name. */
    private static SafetyNetCircularAreaVo circularArea(String name) {
        SafetyNetCircularAreaVo area = new SafetyNetCircularAreaVo();
        area.setName(name);
        return area;
    }

    /*****************************************/
    /** promulgationDataDefined()           **/
    /*****************************************/

    @Test
    public void promulgationDataDefined_trueWhenOnlyTextPresent() {
        vo.setText("x");
        vo.setAreaName(null);
        assertTrue(vo.promulgationDataDefined(),
                "text present (areaName null) should count as defined");
    }

    @Test
    public void promulgationDataDefined_trueWhenOnlyAreaNamePresent() {
        vo.setText(null);
        vo.setAreaName("A");
        assertTrue(vo.promulgationDataDefined(),
                "areaName present (text null) should count as defined");
    }

    @Test
    public void promulgationDataDefined_falseWhenBothNull() {
        vo.setText(null);
        vo.setAreaName(null);
        assertFalse(vo.promulgationDataDefined(),
                "neither text nor areaName present should not be defined");
    }

    @Test
    public void promulgationDataDefined_falseWhenBothBlankWhitespace() {
        vo.setText("   ");
        vo.setAreaName("   ");
        assertFalse(vo.promulgationDataDefined(),
                "whitespace-only values are blank and should not be defined");
    }

    @Test
    public void promulgationDataDefined_trueWhenBothPresent() {
        vo.setText("x");
        vo.setAreaName("A");
        assertTrue(vo.promulgationDataDefined(),
                "both text and areaName present should be defined");
    }

    /*****************************************/
    /** reset()                             **/
    /*****************************************/

    @Test
    public void reset_clearsAllDataAndRestoresDefaultPriority() {
        vo.setPriority(SafetyNetPriority.URGENCY);
        vo.setText("t");
        vo.setAreaName("A");
        vo.setSafetyNetId("S");
        vo.getAreas().add(circularArea("A"));

        // Sanity check that the state was actually populated before reset.
        assertEquals(SafetyNetPriority.URGENCY, vo.getPriority(),
                "priority should be URGENCY before reset");
        assertEquals(1, vo.getAreas().size(),
                "areas should contain one element before reset");

        SafetyNetMessagePromulgationVo result = vo.reset();

        assertNull(vo.getText(), "text should be cleared by reset");
        assertNull(vo.getAreaName(), "areaName should be cleared by reset");
        assertNull(vo.getSafetyNetId(), "safetyNetId should be cleared by reset");
        assertTrue(vo.getAreas().isEmpty(), "areas should be emptied by reset");
        assertEquals(SafetyNetPriority.SAFETY, vo.getPriority(),
                "priority should fall back to SAFETY after reset");
    }

    @Test
    public void reset_returnsSameInstanceForChaining() {
        SafetyNetMessagePromulgationVo result = vo.reset();
        assertSame(vo, result,
                "reset() should return the same instance to allow chaining");
    }

    /*****************************************/
    /** selectedArea()                      **/
    /*****************************************/

    @Test
    public void selectedArea_returnsMatchingAreaByName() {
        SafetyNetCircularAreaVo areaA = circularArea("A");
        SafetyNetCircularAreaVo areaB = circularArea("B");
        vo.getAreas().add(areaA);
        vo.getAreas().add(areaB);
        vo.setAreaName("B");

        assertSame(areaB, vo.selectedArea(),
                "selectedArea() should return the area whose name matches areaName");
    }

    @Test
    public void selectedArea_returnsNullWhenNoNameMatches() {
        vo.getAreas().add(circularArea("A"));
        vo.getAreas().add(circularArea("B"));
        vo.setAreaName("C");

        assertNull(vo.selectedArea(),
                "selectedArea() should return null when no area name matches");
    }

    @Test
    public void selectedArea_returnsNullWhenAreaNameNull() {
        vo.getAreas().add(circularArea("A"));
        vo.getAreas().add(circularArea("B"));
        vo.setAreaName(null);

        assertNull(vo.selectedArea(),
                "selectedArea() should return null when areaName is null");
    }

    /*****************************************/
    /** default priority and toEntity()     **/
    /*****************************************/

    @Test
    public void defaultPriority_isSafetyOnFreshVo() {
        assertEquals(SafetyNetPriority.SAFETY, new SafetyNetMessagePromulgationVo().getPriority(),
                "a freshly constructed VO should default to SAFETY priority");
    }

    @Test
    public void toEntity_returnsSafetyNetMessagePromulgationInstance() {
        // The entity constructor copies the promulgation type, so a non-null type
        // must be supplied for conversion to succeed.
        PromulgationTypeVo type = new PromulgationTypeVo();
        type.setTypeId("safetynet");
        SafetyNetMessagePromulgationVo voWithType = new SafetyNetMessagePromulgationVo(type);

        SafetyNetMessagePromulgation entity = voWithType.toEntity();

        assertEquals(SafetyNetMessagePromulgation.class, entity.getClass(),
                "toEntity() should produce a SafetyNetMessagePromulgation");
    }
}
