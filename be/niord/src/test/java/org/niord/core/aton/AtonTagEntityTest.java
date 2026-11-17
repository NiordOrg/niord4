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
package org.niord.core.aton;

import org.junit.jupiter.api.Test;
import org.niord.core.aton.vo.AtonTagVo;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain unit tests for the {@link AtonTag} entity.
 *
 * Verifies the constructors, the {@link AtonTag#toVo()} conversion, the
 * getter/setter round-trips, and the documented seamark key constants.
 *
 * Framework-neutral: no Quarkus, no DB, no mocks.
 */
public class AtonTagEntityTest {

    /** The key-value constructor stores k and v and leaves atonNode null. */
    @Test
    public void keyValueConstructorStoresKAndV() {
        AtonTag tag = new AtonTag("seamark:name", "Calshot");

        assertEquals("seamark:name", tag.getK());
        assertEquals("Calshot", tag.getV());
        assertNull(tag.getAtonNode(), "atonNode must not be set by the (k,v) constructor");
    }

    /** The (k,v) constructor preserves null values verbatim. */
    @Test
    public void keyValueConstructorAllowsNullValues() {
        AtonTag tag = new AtonTag((String) null, (String) null);

        assertNull(tag.getK());
        assertNull(tag.getV());
        assertNull(tag.getAtonNode());
    }

    /** The VO constructor copies k and v from the VO and sets the supplied atonNode. */
    @Test
    public void voConstructorCopiesKVAndSetsAtonNode() {
        AtonNode node = new AtonNode();
        AtonTagVo vo = new AtonTagVo("seamark:light:colour", "white");

        AtonTag tag = new AtonTag(vo, node);

        assertEquals("seamark:light:colour", tag.getK());
        assertEquals("white", tag.getV());
        assertSame(node, tag.getAtonNode(), "atonNode must be the exact instance passed in");
    }

    /** The VO constructor accepts a null atonNode (only the VO is required non-null). */
    @Test
    public void voConstructorAllowsNullAtonNode() {
        AtonTagVo vo = new AtonTagVo("seamark:locality", "Esbjerg");

        AtonTag tag = new AtonTag(vo, null);

        assertEquals("seamark:locality", tag.getK());
        assertEquals("Esbjerg", tag.getV());
        assertNull(tag.getAtonNode());
    }

    /** The VO constructor rejects a null VO via Objects.requireNonNull. */
    @Test
    public void voConstructorRejectsNullVo() {
        assertThrows(NullPointerException.class, () -> new AtonTag((AtonTagVo) null, new AtonNode()));
    }

    /** toVo() returns a VO equal to a freshly built VO with the same k/v. */
    @Test
    public void toVoReturnsEqualVo() {
        AtonTag tag = new AtonTag("seamark:type", "buoy_cardinal");

        AtonTagVo vo = tag.toVo();

        assertEquals(new AtonTagVo("seamark:type", "buoy_cardinal"), vo);
        assertEquals("seamark:type", vo.getK());
        assertEquals("buoy_cardinal", vo.getV());
    }

    /** toVo() produces a distinct VO instance each call but with equal content. */
    @Test
    public void toVoReturnsDistinctButEqualInstances() {
        AtonTag tag = new AtonTag("seamark:topmark:shape", "2 cones up");

        AtonTagVo first = tag.toVo();
        AtonTagVo second = tag.toVo();

        assertNotSame(first, second, "each toVo() call must build a new VO");
        assertEquals(first, second);
    }

    /** toVo() carries through null k/v values. */
    @Test
    public void toVoCarriesNullValues() {
        AtonTag tag = new AtonTag();

        AtonTagVo vo = tag.toVo();

        assertEquals(new AtonTagVo(null, null), vo);
        assertNull(vo.getK());
        assertNull(vo.getV());
    }

    /** The k, v and atonNode setters round-trip their values. */
    @Test
    public void settersRoundTrip() {
        AtonNode node = new AtonNode();
        AtonTag tag = new AtonTag();

        tag.setK("seamark:ais:ref");
        tag.setV("12345");
        tag.setAtonNode(node);

        assertEquals("seamark:ais:ref", tag.getK());
        assertEquals("12345", tag.getV());
        assertSame(node, tag.getAtonNode());

        // setters can overwrite previously set values, including back to null
        tag.setK(null);
        tag.setV(null);
        tag.setAtonNode(null);

        assertNull(tag.getK());
        assertNull(tag.getV());
        assertNull(tag.getAtonNode());
    }

    /** The custom AtoN reference key constants match the documented OSM seamark keys. */
    @Test
    public void seamarkReferenceKeyConstants() {
        assertEquals("seamark:ref", AtonTag.TAG_ATON_UID);
        assertEquals("seamark:light:ref", AtonTag.TAG_LIGHT_NUMBER);
        assertEquals("seamark:ais:ref", AtonTag.TAG_AIS_NUMBER);
        assertEquals("seamark:racon:ref", AtonTag.TAG_RACON_NUMBER);
    }

    /** The international reference and locality key constants match the documented keys. */
    @Test
    public void seamarkInternationalAndLocalityKeyConstants() {
        assertEquals("seamark:light:int_ref", AtonTag.TAG_INT_LIGHT_NUMBER);
        assertEquals("seamark:racon:int_ref", AtonTag.TAG_INT_RACON_NUMBER);
        assertEquals("seamark:locality", AtonTag.TAG_LOCALITY);
    }

    /** Building a tag from a constant key produces a VO that round-trips that key. */
    @Test
    public void constantKeyRoundTripsThroughVo() {
        AtonTag tag = new AtonTag(AtonTag.TAG_ATON_UID, "aton-0001");

        AtonTagVo vo = tag.toVo();

        assertEquals("seamark:ref", vo.getK());
        assertEquals("aton-0001", vo.getV());
        assertEquals(new AtonTagVo(AtonTag.TAG_ATON_UID, "aton-0001"), vo);
    }
}
