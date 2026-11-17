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

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link AtonNodeVo}, focusing on edge cases and negative
 * scenarios not covered by {@code org.niord.core.AtonTest}:
 * <ul>
 *     <li>{@link AtonNodeVo#validSeamark()} negative/boundary branches</li>
 *     <li>{@link AtonNodeVo#k(String)} / {@link AtonNodeVo#v(String)} /
 *         {@link AtonNodeVo#kv(String, String)} full-match regex semantics and null tags</li>
 *     <li>{@link AtonNodeVo#equals(Object)} / {@link AtonNodeVo#hashCode()} contract</li>
 *     <li>{@link AtonNodeVo#toString()} content</li>
 * </ul>
 *
 * Framework-neutral: no Quarkus, Mockito or DB dependencies.
 */
public class AtonNodeVoTest {

    /** A fixed, deterministic timestamp used across tests. */
    private static final long TS_MILLIS = 1314134556000L; // 2011-08-23T21:22:36Z

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Builds a fully-populated, valid seamark node. */
    private static AtonNodeVo validNode() {
        AtonNodeVo aton = new AtonNodeVo();
        aton.setId(672436827);
        aton.setLat(50.8070813);
        aton.setLon(-1.2841124);
        aton.setUser("malcolmh");
        aton.setUid(128186);
        aton.setVisible(true);
        aton.setVersion(11);
        aton.setChangeset(9107813);
        aton.setTimestamp(new Date(TS_MILLIS));
        aton.setTags(new AtonTagVo[]{
                new AtonTagVo("seamark:type", "buoy_cardinal"),
                new AtonTagVo("seamark:name", "Calshot")
        });
        return aton;
    }

    // ---------------------------------------------------------------------
    // validSeamark() - positive
    // ---------------------------------------------------------------------

    @Test
    public void validSeamark_fullyPopulated_isValid() {
        assertTrue(validNode().validSeamark());
    }

    @Test
    public void validSeamark_emptyTagsArray_isValid() {
        // Arrays.stream(emptyArray).allMatch(...) is vacuously true, so an empty
        // (but non-null) tag array still yields a valid seamark when user+timestamp set.
        AtonNodeVo aton = validNode();
        aton.setTags(new AtonTagVo[]{});
        assertTrue(aton.validSeamark());
    }

    // ---------------------------------------------------------------------
    // validSeamark() - negatives / boundaries
    // ---------------------------------------------------------------------

    @Test
    public void validSeamark_nullUser_isInvalid() {
        AtonNodeVo aton = validNode();
        aton.setUser(null);
        assertFalse(aton.validSeamark());
    }

    @Test
    public void validSeamark_blankUser_isInvalid() {
        AtonNodeVo aton = validNode();
        aton.setUser("   ");
        assertFalse(aton.validSeamark());
    }

    @Test
    public void validSeamark_emptyUser_isInvalid() {
        AtonNodeVo aton = validNode();
        aton.setUser("");
        assertFalse(aton.validSeamark());
    }

    @Test
    public void validSeamark_nullTimestamp_isInvalid() {
        AtonNodeVo aton = validNode();
        aton.setTimestamp(null);
        assertFalse(aton.validSeamark());
    }

    @Test
    public void validSeamark_nullTags_isInvalid() {
        AtonNodeVo aton = validNode();
        aton.setTags(null);
        assertFalse(aton.validSeamark());
    }

    @Test
    public void validSeamark_oneTagKeyNotSeamarkPrefixed_isInvalid() {
        // A non-"seamark:" key makes AtonTagVo.validSeamark() false, so the node is invalid.
        AtonNodeVo aton = validNode();
        aton.setTags(new AtonTagVo[]{
                new AtonTagVo("seamark:type", "buoy_cardinal"),
                new AtonTagVo("name", "Calshot") // missing seamark: prefix
        });
        assertFalse(aton.validSeamark());
    }

    @Test
    public void validSeamark_oneTagWithBlankValue_isInvalid() {
        AtonNodeVo aton = validNode();
        aton.setTags(new AtonTagVo[]{
                new AtonTagVo("seamark:type", "buoy_cardinal"),
                new AtonTagVo("seamark:name", "  ") // blank value
        });
        assertFalse(aton.validSeamark());
    }

    @Test
    public void validSeamark_oneTagWithNullKey_isInvalid() {
        AtonNodeVo aton = validNode();
        aton.setTags(new AtonTagVo[]{
                new AtonTagVo(null, "buoy_cardinal")
        });
        assertFalse(aton.validSeamark());
    }

    // ---------------------------------------------------------------------
    // k(keyPattern)
    // ---------------------------------------------------------------------

    @Test
    public void k_fullMatchOnKey_isTrue() {
        assertTrue(validNode().k("seamark:type"));
    }

    @Test
    public void k_regexMatchOnKey_isTrue() {
        assertTrue(validNode().k("seamark:.*"));
    }

    @Test
    public void k_partialKeyIsNotFullMatch_isFalse() {
        // String.matches requires a full match: "seamark" does not fully match "seamark:type".
        assertFalse(validNode().k("seamark"));
    }

    @Test
    public void k_unknownKey_isFalse() {
        assertFalse(validNode().k("seamark:light:colour"));
    }

    @Test
    public void k_nullTags_isFalse() {
        AtonNodeVo aton = validNode();
        aton.setTags(null);
        assertFalse(aton.k("seamark:type"));
    }

    // ---------------------------------------------------------------------
    // v(valuePattern)
    // ---------------------------------------------------------------------

    @Test
    public void v_regexMatchOnValue_isTrue() {
        assertTrue(validNode().v("buoy.*"));
    }

    @Test
    public void v_partialValueIsNotFullMatch_isFalse() {
        // "buoy" is not a full match for "buoy_cardinal".
        assertFalse(validNode().v("buoy"));
    }

    @Test
    public void v_nullTags_isFalse() {
        AtonNodeVo aton = validNode();
        aton.setTags(null);
        assertFalse(aton.v("buoy.*"));
    }

    // ---------------------------------------------------------------------
    // kv(keyPattern, valuePattern)
    // ---------------------------------------------------------------------

    @Test
    public void kv_bothSidesMatchSameTag_isTrue() {
        assertTrue(validNode().kv("seamark:type", "buoy.*"));
    }

    @Test
    public void kv_valueNotFullMatch_isFalse() {
        // key matches fully, but "buoy" is not a full match of "buoy_cardinal".
        assertFalse(validNode().kv("seamark:type", "buoy"));
    }

    @Test
    public void kv_keyMatchesButValueDoesNot_isFalse() {
        assertFalse(validNode().kv("seamark:type", "beacon.*"));
    }

    @Test
    public void kv_valueMatchesButKeyDoesNot_isFalse() {
        // "Calshot" is the value of seamark:name, not of seamark:type;
        // a single tag must match BOTH sides.
        assertFalse(validNode().kv("seamark:type", "Calshot"));
    }

    @Test
    public void kv_nullTags_isFalse() {
        AtonNodeVo aton = validNode();
        aton.setTags(null);
        assertFalse(aton.kv("seamark:type", "buoy.*"));
    }

    // ---------------------------------------------------------------------
    // equals() / hashCode()
    // ---------------------------------------------------------------------

    @Test
    public void equals_identicalFieldsAndTags_areEqualAndShareHashCode() {
        AtonNodeVo a = validNode();
        AtonNodeVo b = validNode();
        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void equals_sameInstance_isReflexive() {
        AtonNodeVo a = validNode();
        assertEquals(a, a);
    }

    @Test
    public void equals_null_isFalse() {
        assertNotEquals(null, validNode());
    }

    @Test
    public void equals_differentType_isFalse() {
        assertNotEquals("not an aton node", validNode());
    }

    @Test
    public void equals_differentId_isNotEqual() {
        AtonNodeVo a = validNode();
        AtonNodeVo b = validNode();
        b.setId(999);
        assertNotEquals(a, b);
    }

    @Test
    public void equals_differentTag_isNotEqual() {
        AtonNodeVo a = validNode();
        AtonNodeVo b = validNode();
        b.setTags(new AtonTagVo[]{
                new AtonTagVo("seamark:type", "beacon_special_purpose"),
                new AtonTagVo("seamark:name", "Calshot")
        });
        assertNotEquals(a, b);
    }

    @Test
    public void equals_differentLat_isNotEqual() {
        AtonNodeVo a = validNode();
        AtonNodeVo b = validNode();
        b.setLat(0.0);
        assertNotEquals(a, b);
    }

    @Test
    public void equals_differentTimestamp_isNotEqual() {
        AtonNodeVo a = validNode();
        AtonNodeVo b = validNode();
        b.setTimestamp(new Date(TS_MILLIS + 1000L));
        assertNotEquals(a, b);
    }

    // ---------------------------------------------------------------------
    // toString()
    // ---------------------------------------------------------------------

    @Test
    public void toString_containsIdAndTags() {
        String s = validNode().toString();
        assertTrue(s.contains("id="), "toString should contain id=");
        assertTrue(s.contains("tags="), "toString should contain tags=");
    }
}
