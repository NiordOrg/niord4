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

import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.vo.NavtexTransmitterVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.model.DataFilter;
import org.niord.model.message.AreaVo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral Tier-1 unit tests for the {@link NavtexTransmitter} entity and its
 * conversion to/from {@link NavtexTransmitterVo}.
 */
public class NavtexTransmitterTest {

    /** Builds a minimal, valid {@link PromulgationTypeVo}. */
    private static PromulgationTypeVo promulgationTypeVo(String typeId, String name, String serviceId) {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId(typeId);
        vo.setName(name);
        vo.setServiceId(serviceId);
        return vo;
    }

    // -------------------------------------------------------------------------
    // (1) Name constructor
    // -------------------------------------------------------------------------

    @Test
    public void nameConstructorSetsNameAndDefaultsToInactive() {
        NavtexTransmitter transmitter = new NavtexTransmitter("Rogaland");

        assertEquals("Rogaland", transmitter.getName(), "name should equal constructor argument");
        assertFalse(transmitter.isActive(), "active should default to false");
    }

    @Test
    public void nameConstructorYieldsEmptyNonNullAreaList() {
        NavtexTransmitter transmitter = new NavtexTransmitter("Rogaland");

        assertNotNull(transmitter.getAreas(), "areas list should never be null");
        assertTrue(transmitter.getAreas().isEmpty(), "areas list should be empty by default");
    }

    // -------------------------------------------------------------------------
    // (2) VO constructor
    // -------------------------------------------------------------------------

    @Test
    public void voConstructorCopiesScalarFieldsAndPromulgationType() {
        NavtexTransmitterVo vo = new NavtexTransmitterVo();
        vo.setPromulgationType(promulgationTypeVo("ntx", "N", "navtex"));
        vo.setName("Hamburg");
        vo.setActive(true);
        vo.setAreas(null);

        NavtexTransmitter transmitter = new NavtexTransmitter(vo);

        assertEquals("Hamburg", transmitter.getName(), "name should be copied from the VO");
        assertTrue(transmitter.isActive(), "active should be copied from the VO");
        assertNotNull(transmitter.getPromulgationType(), "promulgation type should be constructed from the VO");
        assertEquals("ntx",
                transmitter.getPromulgationType().getTypeId(), "promulgation type id should be copied");
    }

    @Test
    public void voConstructorWithNullAreasLeavesEmptyAreaList() {
        NavtexTransmitterVo vo = new NavtexTransmitterVo();
        vo.setPromulgationType(promulgationTypeVo("ntx", "N", "navtex"));
        vo.setName("Hamburg");
        vo.setActive(true);
        vo.setAreas(null);

        NavtexTransmitter transmitter = new NavtexTransmitter(vo);

        assertNotNull(transmitter.getAreas(), "areas list should not be null when VO areas are null");
        assertTrue(transmitter.getAreas().isEmpty(),
                "areas list should remain empty when VO areas are null");
    }

    @Test
    public void voConstructorMapsEachAreaVoToAnArea() {
        List<AreaVo> areaVos = new ArrayList<>();
        areaVos.add(new AreaVo());

        NavtexTransmitterVo vo = new NavtexTransmitterVo();
        vo.setPromulgationType(promulgationTypeVo("ntx", "N", "navtex"));
        vo.setName("Hamburg");
        vo.setActive(true);
        vo.setAreas(areaVos);

        NavtexTransmitter transmitter = new NavtexTransmitter(vo);

        assertEquals(1, transmitter.getAreas().size(), "each AreaVo should map to one Area entity");
    }

    // -------------------------------------------------------------------------
    // (3) toVo with a default (no-details) DataFilter
    // -------------------------------------------------------------------------

    @Test
    public void toVoCopiesScalarFieldsAndPromulgationType() {
        NavtexTransmitter transmitter = new NavtexTransmitter("Lyngby");
        transmitter.setPromulgationType(
                new PromulgationType(promulgationTypeVo("ntx", "N", "navtex")));
        transmitter.setActive(true);

        NavtexTransmitterVo vo = transmitter.toVo(DataFilter.get());

        assertEquals("Lyngby", vo.getName(), "name should be copied to the VO");
        assertTrue(vo.isActive(), "active should be copied to the VO");
        assertNotNull(vo.getPromulgationType(), "promulgation type should be present on the VO");
        assertEquals("ntx",
                vo.getPromulgationType().getTypeId(), "promulgation type id should be copied to the VO");
    }

    @Test
    public void toVoWithEmptyAreasYieldsEmptyAreaList() {
        NavtexTransmitter transmitter = new NavtexTransmitter("Lyngby");
        transmitter.setPromulgationType(
                new PromulgationType(promulgationTypeVo("ntx", "N", "navtex")));
        transmitter.setActive(true);

        NavtexTransmitterVo vo = transmitter.toVo(DataFilter.get());

        assertNotNull(vo.getAreas(), "VO areas list should not be null");
        assertTrue(vo.getAreas().isEmpty(), "VO areas list should be empty when entity has no areas");
    }
}
