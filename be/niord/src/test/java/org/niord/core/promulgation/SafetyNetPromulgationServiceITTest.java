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

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link SafetyNetPromulgationService} SafetyNET area handling, run against
 * the seeded MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>The seed data (niord/src/test/resources/db/niord-test-seed.sql) defines three promulgation
 * types ({@code navtex}, {@code audio} and {@code twitter}) and leaves the {@code SafetyNetArea}
 * table empty, so each test creates the area rows it needs and attaches them to the seeded
 * {@code navtex} type (the service resolves the type by typeId only; the type's serviceId is not
 * consulted by createArea).
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the
 * shared seed database pristine and the tests independent. Test areas use an {@code IT-SN-} name
 * prefix that does not occur in the seed data, so name lookups stay isolated. Names are unique
 * within each test, and rollback removes them between tests, so collisions are not a concern.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class SafetyNetPromulgationServiceITTest extends ITTest {

    static final String NAVTEX = "navtex";

    @Autowired
    SafetyNetPromulgationService safetyNetPromulgationService;

    @PersistenceContext
    EntityManager em;

    /** Builds the seeded 'navtex' promulgation type as a detached reference for attaching areas. */
    private PromulgationType navtexType() {
        PromulgationTypeVo typeVo = new PromulgationTypeVo();
        typeVo.setTypeId(NAVTEX);
        return new PromulgationType(typeVo);
    }

    /** Builds a detached circular SafetyNET area attached to the seeded 'navtex' type. */
    private SafetyNetCircularArea newCircularArea(String name, boolean active, int sortOrder,
                                                  String center, String radius) {
        SafetyNetCircularArea a = new SafetyNetCircularArea();
        a.setName(name);
        a.setActive(active);
        a.setSortOrder(sortOrder);
        a.setCenter(center);
        a.setRadius(radius);
        a.setPromulgationType(navtexType());
        return a;
    }

    /** Builds a detached NAVAREA SafetyNET area attached to the seeded 'navtex' type. */
    private SafetyNetNavareaArea newNavareaArea(String name, SafetyNetNavareaArea.NAVAREA navarea) {
        SafetyNetNavareaArea a = new SafetyNetNavareaArea();
        a.setName(name);
        a.setActive(true);
        a.setSortOrder(50);
        a.setNavarea(navarea);
        a.setPromulgationType(navtexType());
        return a;
    }

    /** Returns the area names for the given promulgation type. */
    private List<String> areaNames(List<SafetyNetArea> areas) {
        return areas.stream().map(SafetyNetArea::getName).collect(Collectors.toList());
    }

    @Test
    @Transactional
    void createArea_persistsCircularArea_andIsFoundByName() {
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SN-Circular", true, 50, "0 0", "100"));

        em.flush();
        em.clear();

        SafetyNetArea reloaded = safetyNetPromulgationService.findAreaByName(NAVTEX, "IT-SN-Circular");
        assertNotNull(reloaded, "createArea should persist an area findable by name");
        assertEquals(SafetyNetCircularArea.class, reloaded.getClass(),
                "Persisted area should keep its concrete circular subtype");
        assertTrue(reloaded.isActive(), "active flag should be persisted");
        assertEquals("IT-SN-Circular", reloaded.getName(), "name should be persisted");
    }

    @Test
    @Transactional
    void getAreas_returnsCreatedAreaForType() {
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SN-Circular", true, 50, "0 0", "100"));

        em.flush();
        em.clear();

        List<String> names = areaNames(safetyNetPromulgationService.getAreas(NAVTEX));
        assertTrue(names.contains("IT-SN-Circular"),
                "getAreas should return the created area; got " + names);
    }

    @Test
    @Transactional
    void findAreasByMessageType_filtersBySupportedMessageType() {
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SN-Circular", true, 50, "0 0", "100"));
        safetyNetPromulgationService.createArea(
                newNavareaArea("IT-SN-Navarea", SafetyNetNavareaArea.NAVAREA.I));

        em.flush();
        em.clear();

        // Circular areas support COASTAL/SUBAREA/LOCAL warnings but NOT NAVAREA warnings.
        List<String> coastal = areaNames(
                safetyNetPromulgationService.findAreasByMessageType(NAVTEX, Type.COASTAL_WARNING, true));
        assertTrue(coastal.contains("IT-SN-Circular"),
                "Circular area should match COASTAL_WARNING; got " + coastal);
        assertFalse(coastal.contains("IT-SN-Navarea"),
                "NAVAREA area should NOT match COASTAL_WARNING; got " + coastal);

        // NAVAREA areas support ONLY NAVAREA warnings.
        List<String> navarea = areaNames(
                safetyNetPromulgationService.findAreasByMessageType(NAVTEX, Type.NAVAREA_WARNING, true));
        assertTrue(navarea.contains("IT-SN-Navarea"),
                "NAVAREA area should match NAVAREA_WARNING; got " + navarea);
        assertFalse(navarea.contains("IT-SN-Circular"),
                "Circular area should NOT match NAVAREA_WARNING; got " + navarea);
    }

    @Test
    @Transactional
    void findAreasByMessageType_appliesOnlyActiveFilter() {
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SN-Inactive", false, 50, "0 0", "100"));

        em.flush();
        em.clear();

        List<String> activeOnly = areaNames(
                safetyNetPromulgationService.findAreasByMessageType(NAVTEX, Type.COASTAL_WARNING, true));
        assertFalse(activeOnly.contains("IT-SN-Inactive"),
                "onlyActive=true must exclude inactive areas; got " + activeOnly);

        List<String> includeInactive = areaNames(
                safetyNetPromulgationService.findAreasByMessageType(NAVTEX, Type.COASTAL_WARNING, false));
        assertTrue(includeInactive.contains("IT-SN-Inactive"),
                "onlyActive=false must include inactive areas; got " + includeInactive);
    }

    @Test
    @Transactional
    void updateArea_updatesBaseFields() {
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SN-Upd", true, 50, "0 0", "100"));

        em.flush();
        em.clear();

        // Update template carrying the same name and changed base fields.
        SafetyNetCircularArea template = newCircularArea("IT-SN-Upd", false, 7, "9 9", "999");
        safetyNetPromulgationService.updateArea(template);

        em.flush();
        em.clear();

        SafetyNetArea reloaded = safetyNetPromulgationService.findAreaByName(NAVTEX, "IT-SN-Upd");
        assertNotNull(reloaded, "Updated area should still be findable by name");
        assertFalse(reloaded.isActive(), "Base field 'active' should be updated to false");
        assertEquals(7, reloaded.getSortOrder(), "Base field 'sortOrder' should be updated to 7");
    }

    @Test
    @Transactional
    void updateArea_updatesSubclassFields_PROM1() {
        // Regression test: SafetyNetPromulgationService.updateArea
        // resolves the persisted area and calls original.update(area) where both `original` and
        // `area` are statically typed as the base SafetyNetArea. Previously the per-subclass
        // update methods were OVERLOADS (update(SafetyNetCircularArea) etc.), so the compile-time
        // static-dispatch bound the call to the base SafetyNetArea.update(SafetyNetArea) overload
        // and the subclass-specific geometry (center/radius) was silently dropped. They are now
        // true @Override methods, so runtime (virtual) dispatch reaches the circular override and
        // the geometry edit is persisted.
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SN-Upd", true, 50, "0 0", "100"));

        em.flush();
        em.clear();

        SafetyNetCircularArea template = newCircularArea("IT-SN-Upd", false, 7, "9 9", "999");
        safetyNetPromulgationService.updateArea(template);

        em.flush();
        em.clear();

        SafetyNetArea reloaded = safetyNetPromulgationService.findAreaByName(NAVTEX, "IT-SN-Upd");
        assertNotNull(reloaded, "Updated area should still be findable by name");
        assertEquals(SafetyNetCircularArea.class, reloaded.getClass(),
                "Reloaded area should still be a circular area");
        SafetyNetCircularArea circular = (SafetyNetCircularArea) reloaded;
        assertEquals("9 9", circular.getCenter(),
                "PROM1 fixed: subclass field 'center' is now updated via virtual dispatch");
        assertEquals("999", circular.getRadius(),
                "PROM1 fixed: subclass field 'radius' is now updated via virtual dispatch");
    }

    @Test
    @Transactional
    void deleteArea_removesExistingArea_andReturnsFalseForMissing() {
        safetyNetPromulgationService.createArea(
                newCircularArea("IT-SN-Del", true, 50, "0 0", "100"));

        em.flush();
        em.clear();

        boolean deleted = safetyNetPromulgationService.deleteArea(NAVTEX, "IT-SN-Del");
        assertTrue(deleted, "deleteArea should return true for an existing area");

        em.flush();
        em.clear();

        assertNull(safetyNetPromulgationService.findAreaByName(NAVTEX, "IT-SN-Del"),
                "Deleted area should no longer be findable by name");

        boolean missing = safetyNetPromulgationService.deleteArea(NAVTEX, "no-such-area");
        assertFalse(missing, "deleteArea should return false when the area does not exist");
    }
}
