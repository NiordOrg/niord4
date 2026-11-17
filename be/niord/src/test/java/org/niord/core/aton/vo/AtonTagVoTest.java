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

package org.niord.core.aton.vo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link AtonTagVo}.
 *
 * Oracles verified against the production source:
 *  - validSeamark(): StringUtils.isNotBlank(k) && StringUtils.isNotBlank(v) && k.startsWith("seamark:")
 *  - equals()/hashCode(): based on the k + v pair (null-safe); class-checked equality.
 *  - toString(): "AtonTagVo [k=" + k + ", v=" + v + "]"
 *  - no-arg ctor leaves k and v null.
 *
 * No Quarkus, no DB, no mocks required.
 */
public class AtonTagVoTest {

    /** Convenience factory mirroring the key-value constructor. */
    private static AtonTagVo tag(String k, String v) {
        return new AtonTagVo(k, v);
    }

    // ------------------------------------------------------------------
    // validSeamark() - positive
    // ------------------------------------------------------------------

    @Test
    public void validSeamark_trueForSeamarkKeyAndNonBlankValue() {
        assertTrue(tag("seamark:type", "buoy_cardinal").validSeamark());
    }

    @Test
    public void validSeamark_trueForNestedSeamarkKey() {
        // Real-world nested key from the OSM seamark spec.
        assertTrue(tag("seamark:buoy_cardinal:colour", "black;yellow").validSeamark());
    }

    @Test
    public void validSeamark_trueWhenKeyIsExactlyPrefixWithExtraChar() {
        // "seamark:" prefix satisfied; value present.
        assertTrue(tag("seamark:x", "y").validSeamark());
    }

    // ------------------------------------------------------------------
    // validSeamark() - negative: key problems
    // ------------------------------------------------------------------

    @Test
    public void validSeamark_falseWhenKeyDoesNotStartWithSeamark() {
        assertFalse(tag("foo:bar", "value").validSeamark());
    }

    @Test
    public void validSeamark_falseWhenKeyNull() {
        assertFalse(tag(null, "value").validSeamark());
    }

    @Test
    public void validSeamark_falseWhenKeyBlankWhitespace() {
        // isNotBlank() rejects whitespace-only; also fails the startsWith check.
        assertFalse(tag("   ", "value").validSeamark());
    }

    @Test
    public void validSeamark_falseWhenKeyEmpty() {
        assertFalse(tag("", "value").validSeamark());
    }

    @Test
    public void validSeamark_falseWhenPrefixHasLeadingSpace() {
        // " seamark:type" does not start with "seamark:" (leading space).
        assertFalse(tag(" seamark:type", "value").validSeamark());
    }

    // ------------------------------------------------------------------
    // validSeamark() - negative: value problems
    // ------------------------------------------------------------------

    @Test
    public void validSeamark_falseWhenValueNull() {
        assertFalse(tag("seamark:type", null).validSeamark());
    }

    @Test
    public void validSeamark_falseWhenValueEmpty() {
        assertFalse(tag("seamark:type", "").validSeamark());
    }

    @Test
    public void validSeamark_falseWhenValueBlankWhitespace() {
        assertFalse(tag("seamark:type", "  \t ").validSeamark());
    }

    // ------------------------------------------------------------------
    // equals() / hashCode()
    // ------------------------------------------------------------------

    @Test
    public void equals_trueForSameKeyAndValue() {
        AtonTagVo a = tag("seamark:type", "buoy_cardinal");
        AtonTagVo b = tag("seamark:type", "buoy_cardinal");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode(), "equal objects must share hashCode");
    }

    @Test
    public void equals_reflexive() {
        AtonTagVo a = tag("seamark:type", "buoy_cardinal");
        assertEquals(a, a);
    }

    @Test
    public void equals_trueWhenBothValuesNull() {
        AtonTagVo a = tag("seamark:type", null);
        AtonTagVo b = tag("seamark:type", null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equals_trueWhenBothKeysNull() {
        AtonTagVo a = tag(null, "v");
        AtonTagVo b = tag(null, "v");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equals_trueWhenAllFieldsNull() {
        AtonTagVo a = new AtonTagVo();
        AtonTagVo b = new AtonTagVo();
        assertEquals(a, b);
        // Both fields null -> hashCode == 31 * 0 + 0 == 0.
        assertEquals(0, a.hashCode());
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equals_falseWhenKeyDiffers() {
        assertNotEquals(tag("seamark:type", "v"), tag("seamark:name", "v"));
    }

    @Test
    public void equals_falseWhenValueDiffers() {
        assertNotEquals(tag("seamark:type", "buoy"), tag("seamark:type", "beacon"));
    }

    @Test
    public void equals_falseWhenOneValueNull() {
        assertNotEquals(tag("seamark:type", "v"), tag("seamark:type", null));
        assertNotEquals(tag("seamark:type", null), tag("seamark:type", "v"));
    }

    @Test
    public void equals_falseWhenOneKeyNull() {
        assertNotEquals(tag("seamark:type", "v"), tag(null, "v"));
        assertNotEquals(tag(null, "v"), tag("seamark:type", "v"));
    }

    @Test
    public void equals_falseAgainstNull() {
        assertFalse(tag("seamark:type", "v").equals(null));
    }

    @Test
    public void equals_falseAgainstDifferentType() {
        // getClass() != o.getClass() branch.
        assertFalse(tag("seamark:type", "v").equals("seamark:type"));
    }

    // ------------------------------------------------------------------
    // Getters / setters & constructors
    // ------------------------------------------------------------------

    @Test
    public void noArgConstructor_leavesFieldsNull() {
        AtonTagVo t = new AtonTagVo();
        assertNull(t.getK());
        assertNull(t.getV());
    }

    @Test
    public void keyValueConstructor_populatesFields() {
        AtonTagVo t = tag("seamark:light:colour", "white");
        assertEquals("seamark:light:colour", t.getK());
        assertEquals("white", t.getV());
    }

    @Test
    public void settersRoundTrip() {
        AtonTagVo t = new AtonTagVo();
        t.setK("seamark:name");
        t.setV("Calshot");
        assertEquals("seamark:name", t.getK());
        assertEquals("Calshot", t.getV());

        // Overwrite with null is honoured.
        t.setK(null);
        t.setV(null);
        assertNull(t.getK());
        assertNull(t.getV());
    }

    // ------------------------------------------------------------------
    // toString()
    // ------------------------------------------------------------------

    @Test
    public void toString_rendersKeyAndValue() {
        assertEquals("AtonTagVo [k=seamark:type, v=buoy_cardinal]",
                tag("seamark:type", "buoy_cardinal").toString());
    }

    @Test
    public void toString_rendersNullFieldsLiterally() {
        assertEquals("AtonTagVo [k=null, v=null]", new AtonTagVo().toString());
    }
}
