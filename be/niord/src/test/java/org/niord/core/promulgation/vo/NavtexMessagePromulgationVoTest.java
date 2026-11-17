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

import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.NavtexMessagePromulgation;
import org.niord.core.promulgation.NavtexMessagePromulgation.NavtexPriority;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral Tier-1 unit tests for {@link NavtexMessagePromulgationVo}.
 *
 * Verifies the promulgation-data-defined predicate, the reset() contract
 * (including the field it deliberately leaves untouched), the default priority,
 * and the entity factory method. Pure POJO construction, no CDI/persistence.
 */
public class NavtexMessagePromulgationVoTest {

    /* ------------------------------------------------------------------ */
    /* promulgationDataDefined()                                          */
    /* ------------------------------------------------------------------ */

    @Test
    public void promulgationDataDefined_trueWhenTextNonBlankAndNoTransmitters() {
        NavtexMessagePromulgationVo vo = new NavtexMessagePromulgationVo();
        vo.setText("x");
        // transmitters left empty

        assertTrue(vo.promulgationDataDefined(),
                "Non-blank text alone must define promulgation data");
    }

    @Test
    public void promulgationDataDefined_trueWhenAnyTransmitterEnabledAndTextNull() {
        NavtexMessagePromulgationVo vo = new NavtexMessagePromulgationVo();
        vo.setText(null);
        Map<String, Boolean> transmitters = new HashMap<>();
        transmitters.put("a", Boolean.TRUE);
        vo.setTransmitters(transmitters);

        assertTrue(vo.promulgationDataDefined(),
                "An enabled transmitter must define promulgation data even when text is null");
    }

    @Test
    public void promulgationDataDefined_falseWhenAllTransmittersDisabledAndTextNull() {
        NavtexMessagePromulgationVo vo = new NavtexMessagePromulgationVo();
        vo.setText(null);
        Map<String, Boolean> transmitters = new HashMap<>();
        transmitters.put("a", Boolean.FALSE);
        transmitters.put("b", Boolean.FALSE);
        vo.setTransmitters(transmitters);

        assertFalse(vo.promulgationDataDefined(),
                "All transmitters disabled and no text means no promulgation data");
    }

    @Test
    public void promulgationDataDefined_falseWhenTextNullAndNoTransmitters() {
        NavtexMessagePromulgationVo vo = new NavtexMessagePromulgationVo();
        vo.setText(null);
        // transmitters left empty

        assertFalse(vo.promulgationDataDefined(),
                "Null text and empty transmitters means no promulgation data");
    }

    @Test
    public void promulgationDataDefined_falseWhenTextWhitespaceAndNoTransmitters() {
        NavtexMessagePromulgationVo vo = new NavtexMessagePromulgationVo();
        vo.setText("  ");
        // transmitters left empty

        assertFalse(vo.promulgationDataDefined(),
                "Whitespace-only text is blank, so it must not define promulgation data");
    }

    /* ------------------------------------------------------------------ */
    /* reset()                                                            */
    /* ------------------------------------------------------------------ */

    @Test
    public void reset_clearsTextTransmittersAndPriority() {
        NavtexMessagePromulgationVo vo = new NavtexMessagePromulgationVo();
        vo.setText("t");
        vo.setPriority(NavtexPriority.VITAL);
        Map<String, Boolean> transmitters = new HashMap<>();
        transmitters.put("a", Boolean.TRUE);
        transmitters.put("b", Boolean.FALSE);
        vo.setTransmitters(transmitters);

        vo.reset();

        assertNull(vo.getText(), "reset() must null out the text");
        assertEquals(NavtexPriority.ROUTINE, vo.getPriority(),
                "reset() must restore the priority to ROUTINE");
        assertTrue(vo.getTransmitters().isEmpty(),
                "reset() must clear the transmitters map");
    }

    @Test
    public void reset_returnsSameInstance() {
        NavtexMessagePromulgationVo vo = new NavtexMessagePromulgationVo();
        vo.setText("t");

        NavtexMessagePromulgationVo returned = vo.reset();

        assertSame(vo, returned, "reset() must return the same instance for fluent chaining");
    }

    @Test
    public void reset_doesNotClearPreamble() {
        NavtexMessagePromulgationVo vo = new NavtexMessagePromulgationVo();
        vo.setText("t");
        vo.setPreamble("keep me");

        vo.reset();

        assertEquals("keep me", vo.getPreamble(),
                "reset() must leave the preamble untouched");
    }

    /* ------------------------------------------------------------------ */
    /* Defaults and entity factory                                        */
    /* ------------------------------------------------------------------ */

    @Test
    public void freshVo_defaultsToRoutinePriority() {
        NavtexMessagePromulgationVo vo = new NavtexMessagePromulgationVo();

        assertEquals(NavtexPriority.ROUTINE, vo.getPriority(),
                "A freshly constructed VO must default to ROUTINE priority");
    }

    @Test
    public void toEntity_returnsNavtexMessagePromulgation() {
        // toEntity() -> new NavtexMessagePromulgation(vo) -> new PromulgationType(vo.getType()),
        // so the VO must carry a non-null type (it is @NotNull on the entity).
        PromulgationTypeVo type = new PromulgationTypeVo();
        type.setTypeId("ntx");
        NavtexMessagePromulgationVo vo = new NavtexMessagePromulgationVo(type);

        NavtexMessagePromulgation entity = vo.toEntity();

        assertEquals(NavtexMessagePromulgation.class, entity.getClass(),
                "toEntity() must produce a NavtexMessagePromulgation entity");
    }
}
