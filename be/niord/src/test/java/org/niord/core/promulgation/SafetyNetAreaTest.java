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
import org.niord.core.promulgation.SafetyNetArea.SafetyNetRepetition;
import org.niord.core.promulgation.SafetyNetCoastalArea.CoastalArea;
import org.niord.core.promulgation.SafetyNetNavareaArea.NAVAREA;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.core.promulgation.vo.SafetyNetAreaVo.SafetyNetCircularAreaVo;
import org.niord.core.promulgation.vo.SafetyNetAreaVo.SafetyNetCoastalAreaVo;
import org.niord.core.promulgation.vo.SafetyNetAreaVo.SafetyNetNavareaAreaVo;
import org.niord.core.promulgation.vo.SafetyNetAreaVo.SafetyNetRectangularAreaVo;
import org.niord.model.DataFilter;
import org.niord.model.message.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral Tier-1 unit tests for the SafetyNET area entity hierarchy:
 * {@link SafetyNetArea}, {@link SafetyNetCircularArea}, {@link SafetyNetRectangularArea},
 * {@link SafetyNetNavareaArea}, {@link SafetyNetCoastalArea} and the VO {@code SafetyNetAreaVo}.
 *
 * Covers:
 * <ul>
 *     <li>The {@code supportsMessageType(Type)} contract of each concrete subclass.</li>
 *     <li>Constructor-from-VO field copying, including sortOrder defaulting.</li>
 *     <li>{@code update(...)} field copying, and that promulgationType is NOT updated.</li>
 *     <li>{@code toVo(filter)} detail gating driven by {@link DataFilter}.</li>
 * </ul>
 *
 * Pure POJO construction only; no CDI, persistence or framework wiring.
 */
public class SafetyNetAreaTest {

    private PromulgationTypeVo promulgationTypeVo;

    @BeforeEach
    public void setUp() {
        promulgationTypeVo = new PromulgationTypeVo();
        promulgationTypeVo.setTypeId("snw");
        promulgationTypeVo.setName("SafetyNET");
        promulgationTypeVo.setServiceId("safetynet");
    }

    /** Builds a fresh base PromulgationTypeVo with the given typeId. */
    private PromulgationTypeVo promulgationTypeVo(String typeId, String name) {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId(typeId);
        vo.setName(name);
        vo.setServiceId("safetynet");
        return vo;
    }

    /** Populates the common base fields shared by every SafetyNetAreaVo subclass. */
    private void populateBase(org.niord.core.promulgation.vo.SafetyNetAreaVo vo) {
        vo.setPromulgationType(promulgationTypeVo);
        vo.setName("Gulf area");
        vo.setActive(true);
        vo.setRepetition(SafetyNetRepetition.REPETITION_11);
        vo.setMessageAreaPrefix("GULF");
        vo.setSortOrder(50);
    }

    /*****************************************************/
    /** (1) supportsMessageType(Type) contract matrix   **/
    /*****************************************************/

    @Test
    public void circularSupportsCoastalSubareaAndLocalWarningsButNotNavareaOrNm() {
        SafetyNetCircularArea area = new SafetyNetCircularArea();
        assertTrue(area.supportsMessageType(Type.COASTAL_WARNING), "Circular should support COASTAL_WARNING");
        assertTrue(area.supportsMessageType(Type.SUBAREA_WARNING), "Circular should support SUBAREA_WARNING");
        assertTrue(area.supportsMessageType(Type.LOCAL_WARNING), "Circular should support LOCAL_WARNING");
        assertFalse(area.supportsMessageType(Type.NAVAREA_WARNING), "Circular should NOT support NAVAREA_WARNING");
        assertFalse(area.supportsMessageType(Type.PERMANENT_NOTICE), "Circular should NOT support NM types");
        assertFalse(area.supportsMessageType(null), "Circular should NOT support null");
    }

    @Test
    public void rectangularSupportsCoastalSubareaAndLocalWarningsButNotNavareaOrNm() {
        SafetyNetRectangularArea area = new SafetyNetRectangularArea();
        assertTrue(area.supportsMessageType(Type.COASTAL_WARNING), "Rectangular should support COASTAL_WARNING");
        assertTrue(area.supportsMessageType(Type.SUBAREA_WARNING), "Rectangular should support SUBAREA_WARNING");
        assertTrue(area.supportsMessageType(Type.LOCAL_WARNING), "Rectangular should support LOCAL_WARNING");
        assertFalse(area.supportsMessageType(Type.NAVAREA_WARNING), "Rectangular should NOT support NAVAREA_WARNING");
        assertFalse(area.supportsMessageType(Type.PERMANENT_NOTICE), "Rectangular should NOT support NM types");
        assertFalse(area.supportsMessageType(null), "Rectangular should NOT support null");
    }

    @Test
    public void navareaSupportsOnlyNavareaWarning() {
        SafetyNetNavareaArea area = new SafetyNetNavareaArea();
        area.setNavarea(NAVAREA.II);
        assertTrue(area.supportsMessageType(Type.NAVAREA_WARNING), "Navarea should support NAVAREA_WARNING");
        assertFalse(area.supportsMessageType(Type.COASTAL_WARNING), "Navarea should NOT support COASTAL_WARNING");
        assertFalse(area.supportsMessageType(Type.SUBAREA_WARNING), "Navarea should NOT support SUBAREA_WARNING");
        assertFalse(area.supportsMessageType(Type.LOCAL_WARNING), "Navarea should NOT support LOCAL_WARNING");
        assertFalse(area.supportsMessageType(Type.PERMANENT_NOTICE), "Navarea should NOT support NM types");
        assertFalse(area.supportsMessageType(null), "Navarea should NOT support null");
    }

    @Test
    public void coastalSupportsOnlyCoastalWarning() {
        SafetyNetCoastalArea area = new SafetyNetCoastalArea();
        area.setNavarea(NAVAREA.I);
        area.setCoastalArea(CoastalArea.A);
        assertTrue(area.supportsMessageType(Type.COASTAL_WARNING), "Coastal should support COASTAL_WARNING");
        assertFalse(area.supportsMessageType(Type.NAVAREA_WARNING), "Coastal should NOT support NAVAREA_WARNING");
        assertFalse(area.supportsMessageType(Type.SUBAREA_WARNING), "Coastal should NOT support SUBAREA_WARNING");
        assertFalse(area.supportsMessageType(Type.LOCAL_WARNING), "Coastal should NOT support LOCAL_WARNING");
        assertFalse(area.supportsMessageType(Type.PERMANENT_NOTICE), "Coastal should NOT support NM types");
        assertFalse(area.supportsMessageType(null), "Coastal should NOT support null");
    }

    /*****************************************************/
    /** (2) Constructor-from-VO field copying           **/
    /*****************************************************/

    @Test
    public void circularConstructorCopiesAllBaseAndCircularFields() {
        SafetyNetCircularAreaVo vo = new SafetyNetCircularAreaVo();
        populateBase(vo);
        vo.setCenter("0 0");
        vo.setRadius("100");

        SafetyNetCircularArea area = new SafetyNetCircularArea(vo);

        assertEquals("0 0", area.getCenter(), "center copied from VO");
        assertEquals("100", area.getRadius(), "radius copied from VO");
        assertEquals("Gulf area", area.getName(), "name copied from VO");
        assertTrue(area.isActive(), "active copied from VO");
        assertEquals(SafetyNetRepetition.REPETITION_11, area.getRepetition(), "repetition copied from VO");
        assertEquals("GULF", area.getMessageAreaPrefix(), "messageAreaPrefix copied from VO");
        assertEquals(50, area.getSortOrder(), "sortOrder copied from VO");
        assertNotNull(area.getPromulgationType(), "promulgationType created from VO");
        assertEquals("snw", area.getPromulgationType().getTypeId(), "promulgationType typeId copied");
    }

    @Test
    public void rectangularConstructorCopiesRectangularFields() {
        SafetyNetRectangularAreaVo vo = new SafetyNetRectangularAreaVo();
        populateBase(vo);
        vo.setSwCorner("10N 20W");
        vo.setNorthings("5");
        vo.setEastings("8");

        SafetyNetRectangularArea area = new SafetyNetRectangularArea(vo);

        assertEquals("10N 20W", area.getSwCorner(), "swCorner copied from VO");
        assertEquals("5", area.getNorthings(), "northings copied from VO");
        assertEquals("8", area.getEastings(), "eastings copied from VO");
        assertEquals("Gulf area", area.getName(), "name copied from VO");
        assertEquals("snw", area.getPromulgationType().getTypeId(), "promulgationType typeId copied");
    }

    @Test
    public void navareaConstructorCopiesNavarea() {
        SafetyNetNavareaAreaVo vo = new SafetyNetNavareaAreaVo();
        populateBase(vo);
        vo.setNavarea(NAVAREA.II);

        SafetyNetNavareaArea area = new SafetyNetNavareaArea(vo);

        assertEquals(NAVAREA.II, area.getNavarea(), "navarea copied from VO");
        assertEquals("Gulf area", area.getName(), "name copied from VO");
        assertEquals(50, area.getSortOrder(), "sortOrder copied from VO");
    }

    @Test
    public void coastalConstructorCopiesCoastalAndInheritedNavarea() {
        SafetyNetCoastalAreaVo vo = new SafetyNetCoastalAreaVo();
        populateBase(vo);
        vo.setNavarea(NAVAREA.I);
        vo.setCoastalArea(CoastalArea.A);

        SafetyNetCoastalArea area = new SafetyNetCoastalArea(vo);

        assertEquals(CoastalArea.A, area.getCoastalArea(), "coastalArea copied from VO");
        assertEquals(NAVAREA.I, area.getNavarea(), "inherited navarea copied from VO");
        assertEquals("Gulf area", area.getName(), "name copied from VO");
    }

    @Test
    public void constructorDefaultsSortOrderWhenVoSortOrderIsNull() {
        SafetyNetCircularAreaVo vo = new SafetyNetCircularAreaVo();
        vo.setPromulgationType(promulgationTypeVo);
        vo.setName("No sort order");
        vo.setSortOrder(null);

        SafetyNetCircularArea area = new SafetyNetCircularArea(vo);

        assertEquals(SafetyNetArea.DEFAULT_SORT_ORDER, area.getSortOrder(),
                "null sortOrder defaults to DEFAULT_SORT_ORDER");
        assertEquals(100, SafetyNetArea.DEFAULT_SORT_ORDER, "DEFAULT_SORT_ORDER constant is 100");
    }

    @Test
    public void constructorKeepsExplicitSortOrder() {
        SafetyNetCircularAreaVo vo = new SafetyNetCircularAreaVo();
        vo.setPromulgationType(promulgationTypeVo);
        vo.setName("Explicit sort order");
        vo.setSortOrder(7);

        SafetyNetCircularArea area = new SafetyNetCircularArea(vo);

        assertEquals(7, area.getSortOrder(), "explicit sortOrder retained");
    }

    /*****************************************************/
    /** (3) update(...) field copying                   **/
    /*****************************************************/

    @Test
    public void circularUpdateCopiesFieldsButNotPromulgationType() {
        SafetyNetCircularAreaVo originalVo = new SafetyNetCircularAreaVo();
        populateBase(originalVo);
        originalVo.setCenter("0 0");
        originalVo.setRadius("100");
        SafetyNetCircularArea original = new SafetyNetCircularArea(originalVo);
        PromulgationType originalPromulgationType = original.getPromulgationType();

        SafetyNetCircularAreaVo otherVo = new SafetyNetCircularAreaVo();
        otherVo.setPromulgationType(promulgationTypeVo("OTHER", "Other"));
        otherVo.setName("Bay area");
        otherVo.setActive(false);
        otherVo.setRepetition(SafetyNetRepetition.REPETITION_42);
        otherVo.setMessageAreaPrefix("BAY");
        otherVo.setSortOrder(9);
        otherVo.setCenter("1 1");
        otherVo.setRadius("250");
        SafetyNetCircularArea other = new SafetyNetCircularArea(otherVo);

        original.update(other);

        assertEquals("Bay area", original.getName(), "name updated");
        assertFalse(original.isActive(), "active updated");
        assertEquals(SafetyNetRepetition.REPETITION_42, original.getRepetition(), "repetition updated");
        assertEquals("BAY", original.getMessageAreaPrefix(), "messageAreaPrefix updated");
        assertEquals(9, original.getSortOrder(), "sortOrder updated");
        assertEquals("1 1", original.getCenter(), "center updated");
        assertEquals("250", original.getRadius(), "radius updated");
        assertSame(originalPromulgationType, original.getPromulgationType(), "promulgationType must NOT be updated");
        assertEquals("snw", original.getPromulgationType().getTypeId(), "promulgationType typeId unchanged");
    }

    @Test
    public void rectangularUpdateCopiesFieldsButNotPromulgationType() {
        SafetyNetRectangularAreaVo originalVo = new SafetyNetRectangularAreaVo();
        populateBase(originalVo);
        originalVo.setSwCorner("10N 20W");
        originalVo.setNorthings("5");
        originalVo.setEastings("8");
        SafetyNetRectangularArea original = new SafetyNetRectangularArea(originalVo);
        PromulgationType originalPromulgationType = original.getPromulgationType();

        SafetyNetRectangularAreaVo otherVo = new SafetyNetRectangularAreaVo();
        otherVo.setPromulgationType(promulgationTypeVo("OTHER", "Other"));
        otherVo.setName("Bay rect");
        otherVo.setActive(false);
        otherVo.setSortOrder(3);
        otherVo.setSwCorner("30N 40W");
        otherVo.setNorthings("12");
        otherVo.setEastings("15");
        SafetyNetRectangularArea other = new SafetyNetRectangularArea(otherVo);

        original.update(other);

        assertEquals("Bay rect", original.getName(), "name updated");
        assertEquals("30N 40W", original.getSwCorner(), "swCorner updated");
        assertEquals("12", original.getNorthings(), "northings updated");
        assertEquals("15", original.getEastings(), "eastings updated");
        assertEquals(3, original.getSortOrder(), "sortOrder updated");
        assertSame(originalPromulgationType, original.getPromulgationType(), "promulgationType must NOT be updated");
        assertEquals("snw", original.getPromulgationType().getTypeId(), "promulgationType typeId unchanged");
    }

    @Test
    public void navareaUpdateCopiesNavareaButNotPromulgationType() {
        SafetyNetNavareaAreaVo originalVo = new SafetyNetNavareaAreaVo();
        populateBase(originalVo);
        originalVo.setNavarea(NAVAREA.I);
        SafetyNetNavareaArea original = new SafetyNetNavareaArea(originalVo);
        PromulgationType originalPromulgationType = original.getPromulgationType();

        SafetyNetNavareaAreaVo otherVo = new SafetyNetNavareaAreaVo();
        otherVo.setPromulgationType(promulgationTypeVo("OTHER", "Other"));
        otherVo.setName("Navarea III");
        otherVo.setSortOrder(4);
        otherVo.setNavarea(NAVAREA.III);
        SafetyNetNavareaArea other = new SafetyNetNavareaArea(otherVo);

        original.update(other);

        assertEquals(NAVAREA.III, original.getNavarea(), "navarea updated");
        assertEquals("Navarea III", original.getName(), "name updated");
        assertEquals(4, original.getSortOrder(), "sortOrder updated");
        assertSame(originalPromulgationType, original.getPromulgationType(), "promulgationType must NOT be updated");
        assertEquals("snw", original.getPromulgationType().getTypeId(), "promulgationType typeId unchanged");
    }

    @Test
    public void coastalUpdateCopiesCoastalAndNavareaButNotPromulgationType() {
        SafetyNetCoastalAreaVo originalVo = new SafetyNetCoastalAreaVo();
        populateBase(originalVo);
        originalVo.setNavarea(NAVAREA.I);
        originalVo.setCoastalArea(CoastalArea.A);
        SafetyNetCoastalArea original = new SafetyNetCoastalArea(originalVo);
        PromulgationType originalPromulgationType = original.getPromulgationType();

        SafetyNetCoastalAreaVo otherVo = new SafetyNetCoastalAreaVo();
        otherVo.setPromulgationType(promulgationTypeVo("OTHER", "Other"));
        otherVo.setName("Coastal B");
        otherVo.setSortOrder(6);
        otherVo.setNavarea(NAVAREA.II);
        otherVo.setCoastalArea(CoastalArea.B);
        SafetyNetCoastalArea other = new SafetyNetCoastalArea(otherVo);

        original.update(other);

        assertEquals(CoastalArea.B, original.getCoastalArea(), "coastalArea updated");
        assertEquals(NAVAREA.II, original.getNavarea(), "inherited navarea updated");
        assertEquals("Coastal B", original.getName(), "name updated");
        assertEquals(6, original.getSortOrder(), "sortOrder updated");
        assertSame(originalPromulgationType, original.getPromulgationType(), "promulgationType must NOT be updated");
        assertEquals("snw", original.getPromulgationType().getTypeId(), "promulgationType typeId unchanged");
    }

    @Test
    public void coastalUpdateFromPlainNavareaCopiesBaseFieldsOnly_PROM1() {
        // PROM1 follow-up: a cross-concrete-type update (the REST layer validates only typeId + name)
        // must copy base fields ONLY — it must not partially mutate the inherited navarea. Because
        // SafetyNetCoastalArea extends SafetyNetNavareaArea, a bare instanceof check would leak the
        // navarea field here; the exact concrete-type guard prevents that, matching the pre-PROM1
        // base-only behaviour for mismatched subtypes.
        SafetyNetCoastalAreaVo originalVo = new SafetyNetCoastalAreaVo();
        populateBase(originalVo);
        originalVo.setNavarea(NAVAREA.I);
        originalVo.setCoastalArea(CoastalArea.A);
        SafetyNetCoastalArea original = new SafetyNetCoastalArea(originalVo);

        SafetyNetNavareaAreaVo otherVo = new SafetyNetNavareaAreaVo();
        otherVo.setPromulgationType(promulgationTypeVo("OTHER", "Other"));
        otherVo.setName("Cross type");
        otherVo.setActive(false);
        otherVo.setSortOrder(9);
        otherVo.setNavarea(NAVAREA.III);
        SafetyNetNavareaArea other = new SafetyNetNavareaArea(otherVo);

        original.update(other);

        assertEquals("Cross type", original.getName(), "base field name is still updated across concrete types");
        assertFalse(original.isActive(), "base field active is still updated across concrete types");
        assertEquals(9, original.getSortOrder(), "base field sortOrder is still updated across concrete types");
        assertEquals(NAVAREA.I, original.getNavarea(),
                "PROM1: inherited navarea must NOT be copied from a different concrete type");
        assertEquals(CoastalArea.A, original.getCoastalArea(),
                "PROM1: coastalArea must NOT be copied from a different concrete type");
    }

    @Test
    public void navareaUpdateFromCoastalCopiesBaseFieldsOnly_PROM1() {
        // The reverse direction: a plain NAVAREA area receiving a COASTAL payload must not have its
        // navarea copied from the (coastal-typed) payload.
        SafetyNetNavareaAreaVo originalVo = new SafetyNetNavareaAreaVo();
        populateBase(originalVo);
        originalVo.setNavarea(NAVAREA.I);
        SafetyNetNavareaArea original = new SafetyNetNavareaArea(originalVo);

        SafetyNetCoastalAreaVo otherVo = new SafetyNetCoastalAreaVo();
        otherVo.setPromulgationType(promulgationTypeVo("OTHER", "Other"));
        otherVo.setName("Cross type");
        otherVo.setSortOrder(4);
        otherVo.setNavarea(NAVAREA.III);
        otherVo.setCoastalArea(CoastalArea.B);
        SafetyNetCoastalArea other = new SafetyNetCoastalArea(otherVo);

        original.update(other);

        assertEquals("Cross type", original.getName(), "base field name is still updated across concrete types");
        assertEquals(4, original.getSortOrder(), "base field sortOrder is still updated across concrete types");
        assertEquals(NAVAREA.I, original.getNavarea(),
                "PROM1: navarea must NOT be copied from a coastal payload (different concrete type)");
    }

    /*****************************************************/
    /** (4) toVo(filter) detail gating                  **/
    /*****************************************************/

    @Test
    public void toVoWithoutDetailsCopiesOnlyNameAndActive() {
        SafetyNetCircularAreaVo vo = new SafetyNetCircularAreaVo();
        populateBase(vo);
        vo.setCenter("0 0");
        vo.setRadius("100");
        SafetyNetCircularArea area = new SafetyNetCircularArea(vo);

        SafetyNetCircularAreaVo result = area.toVo(DataFilter.get());

        assertEquals("Gulf area", result.getName(), "name is copied without details");
        assertTrue(result.isActive(), "active is copied without details");
        assertNull(result.getPromulgationType(), "promulgationType is detail-gated");
        assertNull(result.getSortOrder(), "sortOrder is detail-gated");
        assertNull(result.getMessageAreaPrefix(), "messageAreaPrefix is detail-gated");
        assertNull(result.getRepetition(), "repetition is detail-gated");
        assertNull(result.getCenter(), "center is detail-gated");
        assertNull(result.getRadius(), "radius is detail-gated");
    }

    @Test
    public void toVoWithDetailsCopiesAllFields() {
        SafetyNetCircularAreaVo vo = new SafetyNetCircularAreaVo();
        populateBase(vo);
        vo.setCenter("0 0");
        vo.setRadius("100");
        SafetyNetCircularArea area = new SafetyNetCircularArea(vo);

        SafetyNetCircularAreaVo result = area.toVo(DataFilter.get().fields(DataFilter.DETAILS));

        assertEquals("Gulf area", result.getName(), "name is copied with details");
        assertTrue(result.isActive(), "active is copied with details");
        assertNotNull(result.getPromulgationType(), "promulgationType is copied with details");
        assertEquals("snw", result.getPromulgationType().getTypeId(),
                "promulgationType typeId is copied with details");
        assertEquals(Integer.valueOf(50), result.getSortOrder(), "sortOrder is copied with details");
        assertEquals("GULF", result.getMessageAreaPrefix(), "messageAreaPrefix is copied with details");
        assertEquals(SafetyNetRepetition.REPETITION_11, result.getRepetition(), "repetition is copied with details");
        assertEquals("0 0", result.getCenter(), "center is copied with details");
        assertEquals("100", result.getRadius(), "radius is copied with details");
    }
}
