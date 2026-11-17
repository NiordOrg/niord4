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
package org.niord.core.area;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.niord.core.area.vo.SystemAreaVo.AreaMessageSorting;
import org.niord.test.ITTest;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link AreaService} persistence behaviour, run against the seeded MySQL
 * container of the {@link ITTest} infrastructure.
 *
 * <p>Each test is wrapped in {@link TestTransaction} so all writes are rolled back, keeping the shared
 * seed database pristine and the tests independent. Test areas use an {@code IT-} name prefix that does
 * not occur in the seed data, so searches stay isolated from seeded Danish areas.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class AreaServiceITTest extends ITTest {

    @Autowired
    AreaService areaService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached Area template with a single description. */
    private Area newArea(String name, String lang) {
        Area area = new Area();
        area.checkCreateDesc(lang).setName(name);
        return area;
    }

    private AreaSearchParams params() {
        AreaSearchParams p = new AreaSearchParams();
        p.maxSize(100);
        return p;
    }

    // ------------------------------------------------------------------------------------------- //
    // createArea
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createAreaPersistsRootWithGeneratedIdAndLineage() {
        Area created = areaService.createArea(newArea("IT-Root", "en"), null);
        Integer id = created.getId();
        assertNotNull(id, "createArea must assign a generated id");

        em.flush();
        em.clear();

        Area reloaded = em.find(Area.class, id);
        assertNotNull(reloaded, "area row must exist in the database");
        assertEquals("/" + id + "/", reloaded.getLineage());
        assertTrue(reloaded.isActive());
        assertEquals("IT-Root", reloaded.getDesc("en").getName());
    }

    @Test
    @Transactional
    void createAreaUnderParentSetsLineageAndParent() {
        Area parent = areaService.createArea(newArea("IT-Parent", "en"), null);
        Area child = areaService.createArea(newArea("IT-Child", "en"), parent.getId());
        Integer childId = child.getId();

        em.flush();
        em.clear();

        Area reloaded = em.find(Area.class, childId);
        assertEquals("/" + parent.getId() + "/" + childId + "/", reloaded.getLineage());
        assertEquals(parent.getId(), reloaded.getParent().getId());
    }

    @Test
    @Transactional
    void deactivatingParentCascadesInactiveToChildren() {
        Area parent = areaService.createArea(newArea("IT-ActParent", "en"), null);
        Area child = areaService.createArea(newArea("IT-ActChild", "en"), parent.getId());
        Integer childId = child.getId();

        // updateAreaData runs updateActiveFlag, which pushes inactivity down to children.
        parent.setActive(false);
        areaService.updateAreaData(parent, parent);

        em.flush();
        em.clear();

        assertFalse(em.find(Area.class, childId).isActive());
    }

    @Test
    @Transactional
    void creatingActiveChildReactivatesInactiveParent() {
        Area parent = areaService.createArea(newArea("IT-UpParent", "en"), null);
        parent.setActive(false);
        em.flush(); // persist the now-inactive parent

        // createArea runs updateActiveFlag on the new (active) child, which reactivates the parent.
        areaService.createArea(newArea("IT-UpChild", "en"), parent.getId());

        em.flush();
        em.clear();

        assertTrue(em.find(Area.class, parent.getId()).isActive(),
                "an active child must reactivate its inactive parent");
    }

    @Test
    @Transactional
    void duplicateLanguageDescriptionViolatesUniqueConstraint() {
        Area area = newArea("IT-Dup", "en");
        area.createDesc("en").setName("IT-Dup-2"); // a second "en" description on the same area

        // The (lang, entity_id) unique constraint is only observable at the database layer.
        assertThrows(PersistenceException.class, () -> areaService.createArea(area, null));
    }

    // ------------------------------------------------------------------------------------------- //
    // searchAreas
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByExactNameIsCaseInsensitiveAndNotSubstring() {
        areaService.createArea(newArea("IT-Exact-Name", "en"), null);
        em.flush();

        List<Area> exact = areaService.searchAreas(
                params().name("it-exact-name").language("en").exact(true).inactive(true));
        assertEquals(1, exact.size());
        assertEquals("IT-Exact-Name", exact.get(0).getDesc("en").getName());

        // A substring with exact matching must NOT match.
        assertTrue(areaService.searchAreas(
                params().name("Exact-Name").language("en").exact(true).inactive(true)).isEmpty());
    }

    @Test
    @Transactional
    void searchBySubstringMatchesMultiple() {
        areaService.createArea(newArea("IT-Substr-One", "en"), null);
        areaService.createArea(newArea("IT-Substr-Two", "en"), null);
        em.flush();

        List<Area> result = areaService.searchAreas(
                params().name("IT-Substr").language("en").exact(false).inactive(true));
        assertEquals(2, result.size());
    }

    @Test
    @Transactional
    void searchExcludesInactiveByDefaultButIncludesWhenRequested() {
        Area inactive = areaService.createArea(newArea("IT-Inactive-Flag", "en"), null);
        inactive.setActive(false);
        em.flush();

        assertTrue(areaService.searchAreas(
                        params().name("IT-Inactive-Flag").language("en").exact(true)).isEmpty(),
                "inactive areas are excluded by default");
        assertEquals(1, areaService.searchAreas(
                params().name("IT-Inactive-Flag").language("en").exact(true).inactive(true)).size());
    }

    @Test
    @Transactional
    void searchByTypeFiltersOnAreaType() {
        Area firing = newArea("IT-Type-Firing", "en");
        firing.setType(AreaType.FIRING_AREA);
        areaService.createArea(firing, null);
        em.flush();

        assertEquals(1, areaService.searchAreas(
                params().name("IT-Type-Firing").language("en").exact(true).inactive(true)
                        .type(AreaType.FIRING_AREA)).size());
        assertTrue(areaService.searchAreas(
                params().name("IT-Type-Firing").language("en").exact(true).inactive(true)
                        .type(AreaType.COUNTRY)).isEmpty());
    }

    @Test
    @Transactional
    void searchByGeometryPresentFiltersOnNonNullGeometry() {
        Area withGeom = newArea("IT-Geom-Present", "en");
        withGeom.setGeometry(org.niord.core.geojson.JtsConverter.toJtsPoint(56.0, 10.0));
        areaService.createArea(withGeom, null);
        areaService.createArea(newArea("IT-Geom-Absent", "en"), null);
        em.flush();

        assertEquals(1, areaService.searchAreas(
                params().name("IT-Geom-Present").language("en").exact(true).inactive(true)
                        .geometry(true)).size());
        assertTrue(areaService.searchAreas(
                params().name("IT-Geom-Absent").language("en").exact(true).inactive(true)
                        .geometry(true)).isEmpty());
    }

    @Test
    @Transactional
    void searchByMessageSortingPresentFiltersOnNonNullMessageSorting() {
        Area sorted = newArea("IT-MsgSort-Present", "en");
        sorted.setMessageSorting(AreaMessageSorting.NS);
        areaService.createArea(sorted, null);
        areaService.createArea(newArea("IT-MsgSort-Absent", "en"), null);
        em.flush();

        assertEquals(1, areaService.searchAreas(
                params().name("IT-MsgSort-Present").language("en").exact(true).inactive(true)
                        .messageSorting(true)).size());
        assertTrue(areaService.searchAreas(
                params().name("IT-MsgSort-Absent").language("en").exact(true).inactive(true)
                        .messageSorting(true)).isEmpty());
    }

    @Test
    @Transactional
    void searchByParentIdReturnsDirectChildren() {
        Area parent = areaService.createArea(newArea("IT-SearchParent", "en"), null);
        Area child = areaService.createArea(newArea("IT-SearchChild", "en"), parent.getId());
        em.flush();

        List<Area> result = areaService.searchAreas(
                params().parentId(parent.getId()).inactive(true));
        assertEquals(1, result.size());
        assertEquals(child.getId(), result.get(0).getId());
    }

    @Test
    @Transactional
    void searchByAreaIdsReturnsWholeSubtree() {
        Area root = areaService.createArea(newArea("IT-SubtreeRoot", "en"), null);
        Area child = areaService.createArea(newArea("IT-SubtreeChild", "en"), root.getId());
        em.flush();

        List<Area> result = areaService.searchAreas(
                params().areaIds(Set.of(root.getId())).inactive(true));
        Set<Integer> ids = result.stream().map(Area::getId).collect(java.util.stream.Collectors.toSet());
        assertTrue(ids.contains(root.getId()), "subtree search includes the root");
        assertTrue(ids.contains(child.getId()), "subtree search includes descendants");
    }

    // ------------------------------------------------------------------------------------------- //
    // updateAreaData / checkUpdateAreaData
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateAreaDataPersistsAllFields() {
        Area created = areaService.createArea(newArea("IT-Upd", "en"), null);
        Integer id = created.getId();

        Area template = newArea("IT-Upd-Renamed", "en");
        template.setMrn("urn:mrn:dma:area:it-upd");
        template.setType(AreaType.COUNTRY);
        template.setSiblingSortOrder(5.0);
        template.setMessageSorting(AreaMessageSorting.SN);
        template.setGeometry(org.niord.core.geojson.JtsConverter.toJtsPoint(55.0, 11.0));

        areaService.updateAreaData(created, template);
        em.flush();
        em.clear();

        Area reloaded = em.find(Area.class, id);
        assertEquals("urn:mrn:dma:area:it-upd", reloaded.getMrn());
        assertEquals(AreaType.COUNTRY, reloaded.getType());
        assertEquals(5.0, reloaded.getSiblingSortOrder(), 0.0);
        assertEquals(AreaMessageSorting.SN, reloaded.getMessageSorting());
        assertEquals("IT-Upd-Renamed", reloaded.getDesc("en").getName());
        assertNotNull(reloaded.getGeometry());
    }

    @Test
    @Transactional
    void updateAreaDataRemovesBlankDescriptions() {
        Area created = newArea("IT-Blank", "en");
        created.checkCreateDesc("de").setName("Blah");
        created = areaService.createArea(created, null);
        Integer id = created.getId();

        Area template = newArea("IT-Blank", "en");
        template.checkCreateDesc("de").setName(""); // blank out the German description

        areaService.updateAreaData(created, template);
        em.flush();
        em.clear();

        Area reloaded = em.find(Area.class, id);
        assertNull(reloaded.getDesc("de"), "blank descriptions must be removed");
        assertEquals("IT-Blank", reloaded.getDesc("en").getName());
    }

    @Test
    @Transactional
    void checkUpdateAreaDataIsANoOpWhenNothingChanged() {
        Area created = areaService.createArea(newArea("IT-NoChange", "en"), null);
        em.flush();

        Area identicalTemplate = newArea("IT-NoChange", "en");
        Area result = areaService.checkUpdateAreaData(created, identicalTemplate);

        assertSame(created, result, "an unchanged area must be returned as-is, without an update");
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteArea / moveArea / changeSortOrder
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteAreaCascadesToSubAreas() {
        Area parent = areaService.createArea(newArea("IT-DelParent", "en"), null);
        Area child = areaService.createArea(newArea("IT-DelChild", "en"), parent.getId());
        Integer parentId = parent.getId();
        Integer childId = child.getId();
        em.flush();

        assertTrue(areaService.deleteArea(parentId));
        em.flush();
        em.clear();

        assertNull(em.find(Area.class, parentId), "deleted parent must be gone");
        assertNull(em.find(Area.class, childId), "sub-areas must be cascade-deleted");
    }

    @Test
    @Transactional
    void deleteAreaReturnsFalseForUnknownId() {
        assertFalse(areaService.deleteArea(-999_999));
    }

    @Test
    @Transactional
    void moveAreaReparentsAndUpdatesLineage() {
        Area a = areaService.createArea(newArea("IT-MoveA", "en"), null);
        Area b = areaService.createArea(newArea("IT-MoveB", "en"), null);
        Area child = areaService.createArea(newArea("IT-MoveChild", "en"), a.getId());
        Integer childId = child.getId();
        em.flush();

        assertTrue(areaService.moveArea(childId, b.getId()));
        em.flush();
        em.clear();

        Area reloaded = em.find(Area.class, childId);
        assertEquals(b.getId(), reloaded.getParent().getId());
        assertEquals("/" + b.getId() + "/" + childId + "/", reloaded.getLineage());
    }

    @Test
    @Transactional
    void changeSortOrderMovesSiblingUp() {
        Area parent = areaService.createArea(newArea("IT-SortParent", "en"), null);
        Area first = areaService.createArea(newArea("IT-Sort1", "en"), parent.getId());
        Area second = areaService.createArea(newArea("IT-Sort2", "en"), parent.getId());
        Integer firstId = first.getId();
        Integer secondId = second.getId();
        em.flush();

        // Both siblings start at sort order 0.0; moving the second up must order it before the first.
        assertTrue(areaService.changeSortOrder(secondId, true));
        em.flush();
        em.clear();

        double firstOrder = em.find(Area.class, firstId).getSiblingSortOrder();
        double secondOrder = em.find(Area.class, secondId).getSiblingSortOrder();
        assertTrue(secondOrder < firstOrder,
                "the moved-up sibling must have a smaller sort order than its former predecessor");
    }

    // ------------------------------------------------------------------------------------------- //
    // findBy* lookups
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByNameMatchesExactlyAndCaseInsensitively() {
        Area created = areaService.createArea(newArea("IT-FindName", "en"), null);
        em.flush();

        assertEquals(created.getId(), areaService.findByName("it-findname", "en", null).getId());
        assertNull(areaService.findByName("IT-Find", "en", null), "findByName is exact, not substring");
    }

    @Test
    @Transactional
    void findByNameReturnsNullForBlankOrNull() {
        assertNull(areaService.findByName("", "en", null));
        assertNull(areaService.findByName(null, "en", null));
    }

    @Test
    @Transactional
    void findByMrnMatchesAndReturnsNullForUnknown() {
        Area created = newArea("IT-Mrn", "en");
        created.setMrn("urn:mrn:dma:area:it-findme");
        created = areaService.createArea(created, null);
        em.flush();

        assertEquals(created.getId(), areaService.findByMrn("urn:mrn:dma:area:it-findme").getId());
        assertNull(areaService.findByMrn("urn:mrn:dma:area:does-not-exist"));
    }

    @Test
    @Transactional
    void findByLegacyIdMatchesAndReturnsNullForUnknown() {
        Area created = newArea("IT-Legacy", "en");
        created.setLegacyId("IT-LEG-123");
        created = areaService.createArea(created, null);
        em.flush();

        assertEquals(created.getId(), areaService.findByLegacyId("IT-LEG-123").getId());
        assertNull(areaService.findByLegacyId("IT-LEG-UNKNOWN"));
    }

    @Test
    @Transactional
    void findByAreaIdResolvesNumericIdAndMrn() {
        Area created = newArea("IT-AreaId", "en");
        created.setMrn("urn:mrn:dma:area:it-areaid");
        created = areaService.createArea(created, null);
        em.flush();

        assertEquals(created.getId(), areaService.findByAreaId(created.getId().toString()).getId());
        assertEquals(created.getId(), areaService.findByAreaId("urn:mrn:dma:area:it-areaid").getId());
    }

    // ------------------------------------------------------------------------------------------- //
    // importArea
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void importAreaCreatesWhenMissingAndMatchesByMrnOnReimport() {
        Area template = newArea("IT-Import", "en");
        template.setMrn("urn:mrn:dma:area:it-import");

        Area imported = areaService.importArea(template, true, true);
        assertNotNull(imported.getId());
        em.flush();

        // Re-importing the same MRN must update the existing row, not create a new one.
        Area template2 = newArea("IT-Import-Renamed", "en");
        template2.setMrn("urn:mrn:dma:area:it-import");
        Area imported2 = areaService.importArea(template2, true, true);

        assertEquals(imported.getId(), imported2.getId(), "re-import must match by MRN");
        em.flush();
        em.clear();
        assertEquals("IT-Import-Renamed", em.find(Area.class, imported.getId()).getDesc("en").getName());
    }

    @Test
    @Transactional
    void importAreaCreatesParentsRecursively() {
        Area parentTemplate = newArea("IT-ImpParent", "en");
        parentTemplate.setMrn("urn:mrn:dma:area:it-imp-parent");
        Area childTemplate = newArea("IT-ImpChild", "en");
        childTemplate.setMrn("urn:mrn:dma:area:it-imp-child");
        childTemplate.setParent(parentTemplate);

        Area imported = areaService.importArea(childTemplate, true, true);

        assertNotNull(imported.getId());
        assertNotNull(imported.getParent());
        assertEquals("IT-ImpParent", imported.getParent().getDesc("en").getName());
    }

    @Test
    @Transactional
    void importAreaReturnsNullWhenParentMissingAndCreateDisabled() {
        Area parentTemplate = newArea("IT-NoCreateParent", "en");
        parentTemplate.setMrn("urn:mrn:dma:area:it-nocreate-parent");
        Area childTemplate = newArea("IT-NoCreateChild", "en");
        childTemplate.setMrn("urn:mrn:dma:area:it-nocreate-child");
        childTemplate.setParent(parentTemplate);

        assertNull(areaService.importArea(childTemplate, false, false));
    }
}
