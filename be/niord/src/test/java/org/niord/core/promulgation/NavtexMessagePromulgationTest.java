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

import jakarta.persistence.ManyToMany;
import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.NavtexMessagePromulgation.NavtexPriority;
import org.niord.core.promulgation.vo.NavtexMessagePromulgationVo;
import org.niord.core.promulgation.vo.PromulgationTypeVo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral Tier-1 unit tests for the {@link NavtexMessagePromulgation} entity.
 *
 * These tests exercise pure POJO behavior: construction from a value object, conversion
 * back to a value object, the update-from-another-promulgation merge logic, the
 * case-insensitive transmitter lookup, and the default priority. No persistence, CDI,
 * or framework wiring is involved.
 */
public class NavtexMessagePromulgationTest {

    /** Builds a NAVTEX promulgation type value object with the given type id. */
    private static PromulgationTypeVo navtexType(String typeId) {
        PromulgationTypeVo type = new PromulgationTypeVo();
        type.setTypeId(typeId);
        type.setServiceId(NavtexMessagePromulgation.SERVICE_ID);
        return type;
    }

    /**
     * (1) Constructing from a VO copies the scalar fields and keeps only the transmitters
     * whose Map value is TRUE; the FALSE-mapped transmitter is dropped.
     */
    @Test
    public void constructorFromVoCopiesFieldsAndKeepsOnlySelectedTransmitters() {
        NavtexMessagePromulgationVo vo = new NavtexMessagePromulgationVo();
        vo.setPriority(NavtexPriority.VITAL);
        vo.setText("body");
        vo.setPreamble("pre");
        vo.setType(navtexType("ntx"));
        vo.setPromulgate(true);

        Map<String, Boolean> transmitters = new HashMap<>();
        transmitters.put("tx-on", Boolean.TRUE);
        transmitters.put("tx-off", Boolean.FALSE);
        vo.setTransmitters(transmitters);

        NavtexMessagePromulgation entity = new NavtexMessagePromulgation(vo);

        assertEquals(NavtexPriority.VITAL, entity.getPriority(), "priority should be copied from the VO");
        assertEquals("body", entity.getText(), "text should be copied from the VO");
        assertEquals("pre", entity.getPreamble(), "preamble should be copied from the VO");
        assertTrue(entity.isPromulgate(), "promulgate flag should be copied from the VO");
        assertEquals("ntx", entity.getType().getTypeId(), "type id should be copied through the base constructor");

        // Only the TRUE-mapped transmitter survives; the FALSE-mapped one is filtered out.
        assertEquals(1, entity.getTransmitters().size(), "only the selected (TRUE) transmitter should be kept");
        assertEquals("tx-on", entity.getTransmitters().get(0).getName(),
                "the surviving transmitter must be the TRUE-mapped one");
    }

    /**
     * (2) toVo() copies the scalar fields and exposes the transmitter list as a Map keyed
     * by transmitter name, each value being Boolean.TRUE.
     */
    @Test
    public void toVoCopiesFieldsAndMapsTransmittersToTrue() {
        NavtexMessagePromulgation entity = new NavtexMessagePromulgation();
        entity.setPriority(NavtexPriority.IMPORTANT);
        entity.setText("the text");
        entity.setPreamble("the preamble");
        entity.setType(new PromulgationType(navtexType("ntx")));
        entity.setTransmitters(Arrays.asList(new NavtexTransmitter("a"), new NavtexTransmitter("b")));

        NavtexMessagePromulgationVo vo = entity.toVo();

        assertEquals(NavtexPriority.IMPORTANT, vo.getPriority(), "priority should be copied to the VO");
        assertEquals("the text", vo.getText(), "text should be copied to the VO");
        assertEquals("the preamble", vo.getPreamble(), "preamble should be copied to the VO");
        assertEquals("ntx", vo.getType().getTypeId(), "type id should be copied to the VO");

        Map<String, Boolean> transmitters = vo.getTransmitters();
        assertEquals(2, transmitters.size(), "both transmitters should appear as map keys");
        assertTrue(transmitters.containsKey("a"), "transmitter 'a' should be present as a key");
        assertTrue(transmitters.containsKey("b"), "transmitter 'b' should be present as a key");
        assertEquals(Boolean.TRUE, transmitters.get("a"), "transmitter 'a' should map to TRUE");
        assertEquals(Boolean.TRUE, transmitters.get("b"), "transmitter 'b' should map to TRUE");
    }

    /**
     * (3a) Updating from a non-NAVTEX promulgation is a no-op: the target keeps all of its
     * own field values, since the instanceof guard fails.
     */
    @Test
    public void updateFromNonNavtexPromulgationIsNoOp() {
        NavtexMessagePromulgation target = new NavtexMessagePromulgation();
        target.setPriority(NavtexPriority.VITAL);
        target.setText("original text");
        target.setPreamble("original preamble");
        target.setType(new PromulgationType(navtexType("ntx")));
        target.setTransmitters(Arrays.asList(new NavtexTransmitter("keep")));

        target.update(new TwitterMessagePromulgation());

        assertEquals(NavtexPriority.VITAL, target.getPriority(),
                "priority must be unchanged after a non-NAVTEX update");
        assertEquals("original text", target.getText(),
                "text must be unchanged after a non-NAVTEX update");
        assertEquals("original preamble", target.getPreamble(),
                "preamble must be unchanged after a non-NAVTEX update");
        assertEquals(1, target.getTransmitters().size(),
                "transmitter list size must be unchanged after a non-NAVTEX update");
        assertEquals("keep", target.getTransmitters().get(0).getName(),
                "the existing transmitter must be retained after a non-NAVTEX update");
    }

    /**
     * (3b) Updating from another NAVTEX promulgation copies the scalar fields, the base
     * type/promulgate fields, and replaces the transmitter list (clear + addAll).
     */
    @Test
    public void updateFromAnotherNavtexPromulgationCopiesFieldsAndReplacesTransmitters() {
        NavtexMessagePromulgation target = new NavtexMessagePromulgation();
        target.setPriority(NavtexPriority.ROUTINE);
        target.setText("old text");
        target.setPreamble("old preamble");
        target.setType(new PromulgationType(navtexType("old")));
        target.setPromulgate(false);
        // Mutable list: production update() does transmitters.clear()+addAll(), which an
        // immutable List.of/Arrays.asList would reject (the real entity uses a mutable ArrayList).
        target.setTransmitters(new ArrayList<>(Arrays.asList(new NavtexTransmitter("a"))));

        NavtexMessagePromulgation source = new NavtexMessagePromulgation();
        source.setPriority(NavtexPriority.VITAL);
        source.setText("new text");
        source.setPreamble("new preamble");
        source.setType(new PromulgationType(navtexType("new")));
        source.setPromulgate(true);
        source.setTransmitters(Arrays.asList(new NavtexTransmitter("x"), new NavtexTransmitter("y")));

        target.update(source);

        assertEquals(NavtexPriority.VITAL, target.getPriority(), "priority should be copied from the source");
        assertEquals("new text", target.getText(), "text should be copied from the source");
        assertEquals("new preamble", target.getPreamble(), "preamble should be copied from the source");
        assertEquals("new", target.getType().getTypeId(), "base type should be copied from the source");
        assertTrue(target.isPromulgate(), "promulgate flag should be copied from the source");

        List<NavtexTransmitter> transmitters = target.getTransmitters();
        assertEquals(2, transmitters.size(), "transmitter list should be replaced with the source list");
        assertEquals("x", transmitters.get(0).getName(), "first transmitter should be the source's first");
        assertEquals("y", transmitters.get(1).getName(), "second transmitter should be the source's second");
    }

    /**
     * (4) useTransmitter() matches transmitter names case-insensitively, and returns false
     * for names not in the list.
     */
    @Test
    public void useTransmitterMatchesNameCaseInsensitively() {
        NavtexMessagePromulgation entity = new NavtexMessagePromulgation();
        entity.setTransmitters(Arrays.asList(new NavtexTransmitter("Hamburg")));

        assertTrue(entity.useTransmitter("hamburg"), "lowercase name should match");
        assertTrue(entity.useTransmitter("HAMBURG"), "uppercase name should match");
        assertFalse(entity.useTransmitter("Lyngby"), "an unrelated name should not match");
    }

    /**
     * (4b) useTransmitter() returns false when there are no transmitters at all.
     */
    @Test
    public void useTransmitterReturnsFalseWhenTransmitterListEmpty() {
        NavtexMessagePromulgation entity = new NavtexMessagePromulgation();

        assertFalse(entity.useTransmitter("any"), "an empty transmitter list should match nothing");
    }

    /**
     * (5) A freshly constructed NAVTEX promulgation defaults its priority to ROUTINE.
     */
    @Test
    public void defaultPriorityIsRoutine() {
        NavtexMessagePromulgation entity = new NavtexMessagePromulgation();

        assertEquals(NavtexPriority.ROUTINE, entity.getPriority(),
                "default priority of a fresh promulgation should be ROUTINE");
    }

    /**
     * The transmitters are shared reference data: removing a promulgation must remove only its join rows, so the
     * mapping cascades nothing to them (PromulgationFaultITTest runs the removal itself).
     */
    @Test
    public void theTransmittersAreNotCascadedTo() throws Exception {
        ManyToMany mapping = NavtexMessagePromulgation.class.getDeclaredField("transmitters")
                .getAnnotation(ManyToMany.class);
        assertEquals(0, mapping.cascade().length, "no cascade to the transmitters");
    }
}
