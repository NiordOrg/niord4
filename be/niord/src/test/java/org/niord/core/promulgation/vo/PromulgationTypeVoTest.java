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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral Tier-1 unit tests for {@link PromulgationTypeVo} and its
 * public projection {@link PublicPromulgationTypeVo}.
 *
 * These tests exercise pure POJO behaviour only: the public projection, the
 * copy constructor and the priority-based {@link Comparable} ordering. No
 * CDI, database, REST or Quarkus infrastructure is involved.
 */
public class PromulgationTypeVoTest {

    /** Builds a fully-populated promulgation type VO with the given priority. */
    private PromulgationTypeVo vo(String typeId, String name, Boolean active, int priority) {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId(typeId);
        vo.setName(name);
        vo.setActive(active);
        vo.setPriority(priority);
        vo.setServiceId("service-" + typeId);
        vo.setLanguage("en");
        return vo;
    }

    // ---------------------------------------------------------------------
    // toPublicPromulgationType()
    // ---------------------------------------------------------------------

    @Test
    public void toPublicPromulgationType_copiesOnlyTypeIdNameActive() {
        PromulgationTypeVo type = vo("navtex", "NAVTEX", Boolean.TRUE, 3);

        PublicPromulgationTypeVo pub = type.toPublicPromulgationType();

        assertEquals("navtex", pub.getTypeId(), "typeId should be projected onto the public VO");
        assertEquals("NAVTEX", pub.getName(), "name should be projected onto the public VO");
        assertEquals(Boolean.TRUE, pub.isActive(), "active flag should be projected onto the public VO");
    }

    @Test
    public void toPublicPromulgationType_preservesFalseActiveFlag() {
        // Boolean.FALSE must round-trip unchanged (not collapsed to null/true).
        PromulgationTypeVo type = vo("twitter", "Twitter", Boolean.FALSE, 7);

        PublicPromulgationTypeVo pub = type.toPublicPromulgationType();

        assertEquals(Boolean.FALSE, pub.isActive(), "inactive flag must be preserved");
        assertEquals("twitter", pub.getTypeId(), "typeId must be preserved");
        assertEquals("Twitter", pub.getName(), "name must be preserved");
    }

    // ---------------------------------------------------------------------
    // PublicPromulgationTypeVo(PromulgationTypeVo) copy constructor
    // ---------------------------------------------------------------------

    @Test
    public void copyConstructor_copiesTypeIdNameActive() {
        PromulgationTypeVo type = vo("navtex", "NAVTEX", Boolean.TRUE, 1);

        PublicPromulgationTypeVo pub = new PublicPromulgationTypeVo(type);

        assertEquals("navtex", pub.getTypeId(), "copy constructor should copy typeId");
        assertEquals("NAVTEX", pub.getName(), "copy constructor should copy name");
        assertEquals(Boolean.TRUE, pub.isActive(), "copy constructor should copy active flag");
    }

    @Test
    public void copyConstructor_copiesNullFieldsAsNull() {
        // A blank source VO has null typeId/name/active; the projection mirrors that.
        PromulgationTypeVo type = new PromulgationTypeVo();

        PublicPromulgationTypeVo pub = new PublicPromulgationTypeVo(type);

        assertEquals(null, pub.getTypeId(), "null typeId should be copied as null");
        assertEquals(null, pub.getName(), "null name should be copied as null");
        assertEquals(null, pub.isActive(), "null active should be copied as null");
    }

    // ---------------------------------------------------------------------
    // compareTo() — ordering is by priority
    // ---------------------------------------------------------------------

    @Test
    public void compareTo_ordersByPriority() {
        PromulgationTypeVo a = vo("a", "A", Boolean.TRUE, 1);
        PromulgationTypeVo b = vo("b", "B", Boolean.TRUE, 5);
        PromulgationTypeVo c = vo("c", "C", Boolean.TRUE, 5);

        assertTrue(a.compareTo(b) < 0, "lower priority should sort before higher priority");
        assertTrue(b.compareTo(a) > 0, "higher priority should sort after lower priority");
        assertEquals(0, b.compareTo(c), "equal priorities should compare as equal");
    }

    @Test
    public void compareTo_returnsPriorityDifference() {
        // The implementation returns the raw priority difference, not just sign.
        PromulgationTypeVo low = vo("low", "Low", Boolean.TRUE, 2);
        PromulgationTypeVo high = vo("high", "High", Boolean.TRUE, 9);

        assertEquals(-7, low.compareTo(high), "compareTo should return the priority difference");
        assertEquals(7, high.compareTo(low), "reverse comparison should return the negated difference");
    }

    @Test
    public void sort_placesLowerPriorityFirst() {
        PromulgationTypeVo a = vo("a", "A", Boolean.TRUE, 1);
        PromulgationTypeVo b = vo("b", "B", Boolean.TRUE, 5);

        List<PromulgationTypeVo> list = new ArrayList<>();
        list.add(b);
        list.add(a);

        Collections.sort(list);

        assertSame(a, list.get(0), "the priority-1 element should sort to the front");
        assertSame(b, list.get(1), "the priority-5 element should sort to the back");
    }

    @Test
    public void compareToNull_documentsBehavior() {
        // NOTE: java.lang.Comparable mandates that compareTo(null) throws a
        // NullPointerException. This implementation instead returns -1 (see
        // PromulgationTypeVo.compareTo: "return p == null ? -1 : ..."). We
        // assert the actual -1 behaviour and flag the deviation by name.
        PromulgationTypeVo p = vo("navtex", "NAVTEX", Boolean.TRUE, 4);

        assertEquals(-1, p.compareTo(null),
                "compareTo(null) returns -1 instead of throwing NPE (Comparable-contract deviation)");
    }
}
