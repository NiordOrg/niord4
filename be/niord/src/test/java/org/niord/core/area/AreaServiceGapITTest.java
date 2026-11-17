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
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.util.UidUtils;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap-filling integration tests for {@link AreaService} that exercise the harder-to-reach lineage,
 * tree-sort-order, multi-criteria search, domain filtering and import-matching behaviour, complementing
 * the broader coverage in the sibling {@code AreaServiceITTest}.
 *
 * <p>Every test is wrapped in {@link TestTransaction} so all writes (areas <em>and</em> the
 * synthetic test domain created in {@link #searchDomainFilterRestrictsToThatDomainsAreaTree()}) are
 * rolled back, keeping the shared seed database pristine and the tests order-independent. All test
 * areas use an {@code IT-GAP-} name prefix that does not occur in the seed data, so the name-based
 * searches never collide with the seeded Danish areas.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class AreaServiceGapITTest extends ITTest {

    @Autowired
    AreaService areaService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached Area template with a single English description. */
    private Area newArea(String name) {
        Area area = new Area();
        area.checkCreateDesc("en").setName(name);
        return area;
    }

    private AreaSearchParams params() {
        AreaSearchParams p = new AreaSearchParams();
        p.maxSize(500);
        return p;
    }

    // ------------------------------------------------------------------------------------------- //
    // getAreaDetails(Set<Integer>)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getAreaDetailsLoadsDescsEagerlyAndSkipsUnknownIds() {
        Area one = areaService.createArea(newArea("IT-GAP-Details-One"), null);
        Area two = areaService.createArea(newArea("IT-GAP-Details-Two"), null);
        Integer oneId = one.getId();
        Integer twoId = two.getId();

        // Detach everything so the named query must re-fetch (and eagerly join-fetch) the descs.
        em.flush();
        em.clear();

        // Mix the two known ids with an id that does not exist.
        List<Area> result = areaService.getAreaDetails(Set.of(oneId, twoId, -987_654));

        Set<Integer> ids = result.stream().map(Area::getId).collect(Collectors.toSet());
        assertEquals(Set.of(oneId, twoId), ids, "unknown ids must be silently skipped");

        // The descs must be populated eagerly: detach the result, then read the collection. If the
        // join-fetch did not happen this would throw a LazyInitializationException.
        em.clear();
        Area reloadedOne = result.stream().filter(a -> a.getId().equals(oneId)).findFirst().orElseThrow();
        Area reloadedTwo = result.stream().filter(a -> a.getId().equals(twoId)).findFirst().orElseThrow();
        assertEquals("IT-GAP-Details-One", reloadedOne.getDesc("en").getName());
        assertEquals("IT-GAP-Details-Two", reloadedTwo.getDesc("en").getName());
    }

    @Test
    @Transactional
    void getAreaDetailsReturnsEmptyWhenAllIdsUnknown() {
        List<Area> result = areaService.getAreaDetails(Set.of(-111_111, -222_222));
        assertTrue(result.isEmpty(), "a set of purely unknown ids must yield no areas");
    }

    // ------------------------------------------------------------------------------------------- //
    // recomputeTreeSortOrder (the package-visible core algorithm)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void recomputeTreeSortOrderAssignsMonotonicOrderAcrossSubtreeAndIsIdempotent() {
        // Build a 3-level subtree:
        //   root
        //     childA  (siblingSortOrder 1)
        //       grandchild
        //     childB  (siblingSortOrder 2)
        Area root = areaService.createArea(newArea("IT-GAP-Tree-Root"), null);

        Area childA = newArea("IT-GAP-Tree-ChildA");
        childA.setSiblingSortOrder(1.0);
        childA = areaService.createArea(childA, root.getId());

        Area grandchild = areaService.createArea(newArea("IT-GAP-Tree-Grandchild"), childA.getId());

        Area childB = newArea("IT-GAP-Tree-ChildB");
        childB.setSiblingSortOrder(2.0);
        childB = areaService.createArea(childB, root.getId());

        Integer rootId = root.getId();
        Integer childAId = childA.getId();
        Integer grandchildId = grandchild.getId();
        Integer childBId = childB.getId();
        em.flush();

        // Drive the package-visible recompute directly on the in-memory subtree (root holds wired
        // children lists). The depth-first walk must yield: root=1, childA=2, grandchild=3, childB=4.
        List<Area> updated = new ArrayList<>();
        int finalIndex = areaService.recomputeTreeSortOrder(List.of(root), 0, updated, false);
        assertEquals(4, finalIndex, "a 4-node subtree must consume indices 1..4");

        // Persist the recomputed order and reload from the database to verify the *persisted* state.
        updated.forEach(em::merge);
        em.flush();
        em.clear();

        int rootOrder = em.find(Area.class, rootId).getTreeSortOrder();
        int childAOrder = em.find(Area.class, childAId).getTreeSortOrder();
        int grandchildOrder = em.find(Area.class, grandchildId).getTreeSortOrder();
        int childBOrder = em.find(Area.class, childBId).getTreeSortOrder();

        assertEquals(1, rootOrder);
        assertEquals(2, childAOrder);
        assertEquals(3, grandchildOrder);
        assertEquals(4, childBOrder);
        // Depth-first pre-order: root < childA < grandchild < childB, strictly increasing.
        assertTrue(rootOrder < childAOrder && childAOrder < grandchildOrder && grandchildOrder < childBOrder,
                "treeSortOrder must increase monotonically in depth-first pre-order");

        // Idempotency: a second pass over the same (already-numbered) in-memory subtree must report
        // the same final index and must not flag any further updates.
        List<Area> root2 = List.of(em.find(Area.class, rootId));
        List<Area> updated2 = new ArrayList<>();
        int finalIndex2 = areaService.recomputeTreeSortOrder(root2, 0, updated2, false);
        assertEquals(4, finalIndex2, "the index walk is deterministic across calls");
        assertTrue(updated2.isEmpty(), "a second recompute on an unchanged tree must update nothing");
    }

    // ------------------------------------------------------------------------------------------- //
    // searchAreas - combined criteria
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByTypeExactNameAndActiveOnlyReturnsTheIntersection() {
        // The wanted area: matches all three predicates.
        Area wanted = newArea("IT-GAP-Combo-Match");
        wanted.setType(AreaType.FIRING_AREA);
        areaService.createArea(wanted, null);

        // Same exact name & type, but inactive -> excluded by the active-only filter.
        Area inactiveSameNameType = newArea("IT-GAP-Combo-Match");
        inactiveSameNameType.setType(AreaType.FIRING_AREA);
        inactiveSameNameType.setActive(false);
        areaService.createArea(inactiveSameNameType, null);

        // Same exact name, active, but wrong type -> excluded by the type filter.
        Area wrongType = newArea("IT-GAP-Combo-Match");
        wrongType.setType(AreaType.COUNTRY);
        areaService.createArea(wrongType, null);

        // Right type & active, but different name -> excluded by the exact-name filter.
        Area wrongName = newArea("IT-GAP-Combo-Other");
        wrongName.setType(AreaType.FIRING_AREA);
        areaService.createArea(wrongName, null);
        em.flush();

        List<Area> result = areaService.searchAreas(
                params().name("IT-GAP-Combo-Match").language("en").exact(true)
                        .type(AreaType.FIRING_AREA)); // inactive(false) by default = active-only

        assertEquals(1, result.size(),
                "only the active FIRING_AREA with the exact name must survive all three predicates");
        Area hit = result.get(0);
        assertEquals(wanted.getId(), hit.getId());
        assertEquals(AreaType.FIRING_AREA, hit.getType());
        assertTrue(hit.isActive());
        assertEquals("IT-GAP-Combo-Match", hit.getDesc("en").getName());
    }

    // ------------------------------------------------------------------------------------------- //
    // searchAreas - domain filter
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchDomainFilterRestrictsToThatDomainsAreaTree() {
        // An area subtree the test domain WILL be associated with.
        Area inDomainRoot = areaService.createArea(newArea("IT-GAP-Domain-InRoot"), null);
        Area inDomainChild = areaService.createArea(newArea("IT-GAP-Domain-InChild"), inDomainRoot.getId());

        // An unrelated area NOT referenced by the domain.
        Area outsideDomain = areaService.createArea(newArea("IT-GAP-Domain-Outside"), null);
        em.flush();

        // Create a synthetic domain (rolled back with the test) that references only the in-domain root,
        // so the lineage filter "<rootLineage>%" should match the root and its descendant subtree only.
        Domain domain = new Domain();
        String domainId = "it-gap-domain-" + UidUtils.newUid();
        domain.setDomainId(domainId);
        domain.setName("IT-GAP Domain");
        domain.setActive(true);
        domain.getAreas().add(em.find(Area.class, inDomainRoot.getId()));
        em.persist(domain);
        em.flush();

        Integer inRootId = inDomainRoot.getId();
        Integer inChildId = inDomainChild.getId();
        Integer outsideId = outsideDomain.getId();

        // Search the whole IT-GAP-Domain prefix, restricted to the synthetic domain's area tree.
        List<Area> result = areaService.searchAreas(
                params().name("IT-GAP-Domain").language("en").exact(false).inactive(true)
                        .domain(domainId));

        Set<Integer> ids = result.stream().map(Area::getId).collect(Collectors.toSet());
        assertTrue(ids.contains(inRootId), "the domain's referenced root must be included");
        assertTrue(ids.contains(inChildId), "descendants of the domain's area subtree must be included");
        assertFalse(ids.contains(outsideId),
                "an area outside the domain's area tree must be excluded by the domain filter");

        // Sanity: without the domain filter, the outside area IS visible for the same name prefix,
        // proving the exclusion above is the domain filter's doing and not an unrelated mismatch.
        Set<Integer> unfilteredIds = areaService.searchAreas(
                        params().name("IT-GAP-Domain").language("en").exact(false).inactive(true))
                .stream().map(Area::getId).collect(Collectors.toSet());
        assertTrue(unfilteredIds.contains(outsideId),
                "the outside area must be reachable without the domain filter");
    }

    // ------------------------------------------------------------------------------------------- //
    // importArea - matching an existing area when no MRN is present
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void importAreaWithoutMrnMatchesExistingAreaByNameAndUpdatesInsteadOfDuplicating() {
        // An existing root area carrying a legacy id but NO MRN.
        Area existing = newArea("IT-GAP-ImportMatch");
        existing.setLegacyId("IT-GAP-LEG-MATCH");
        existing = areaService.createArea(existing, null);
        Integer existingId = existing.getId();
        em.flush();

        long rootCountBefore = areaService.searchAreas(
                params().name("IT-GAP-ImportMatch").language("en").exact(true).inactive(true)).size();
        assertEquals(1, rootCountBefore, "precondition: exactly one matching root area exists");

        // Import a template with the SAME name, no MRN, but a changed type. With MRN absent the import
        // falls back to name matching (findByName) and must UPDATE the existing row rather than create
        // a duplicate.
        Area template = newArea("IT-GAP-ImportMatch");
        template.setType(AreaType.COUNTRY);

        Area imported = areaService.importArea(template, true, true);
        assertNotNull(imported);
        assertEquals(existingId, imported.getId(), "import must reuse the name-matched existing area");
        em.flush();
        em.clear();

        // No duplicate root with that name was created.
        List<Area> afterImport = areaService.searchAreas(
                params().name("IT-GAP-ImportMatch").language("en").exact(true).inactive(true));
        assertEquals(1, afterImport.size(), "import must not duplicate the name-matched area");

        // The update was applied (type changed) and the original row identity preserved.
        Area reloaded = em.find(Area.class, existingId);
        assertEquals(AreaType.COUNTRY, reloaded.getType(), "the matched area must be updated in place");
        assertEquals("IT-GAP-LEG-MATCH", reloaded.getLegacyId(),
                "legacyId is left untouched by importArea's update");
    }

    // ------------------------------------------------------------------------------------------- //
    // moveArea - lineage + treeSortOrder recompute for the moved subtree
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void moveAreaRecomputesLineageForTheWholeMovedSubtree() {
        // Source tree:  oldParent -> moved -> leaf
        // Target:       newParent
        Area oldParent = areaService.createArea(newArea("IT-GAP-Move-OldParent"), null);
        Area newParent = areaService.createArea(newArea("IT-GAP-Move-NewParent"), null);
        Area moved = areaService.createArea(newArea("IT-GAP-Move-Moved"), oldParent.getId());
        Area leaf = areaService.createArea(newArea("IT-GAP-Move-Leaf"), moved.getId());

        Integer oldParentId = oldParent.getId();
        Integer newParentId = newParent.getId();
        Integer movedId = moved.getId();
        Integer leafId = leaf.getId();
        em.flush();

        // Precondition: the leaf's lineage runs through the old parent.
        em.clear();
        assertEquals("/" + oldParentId + "/" + movedId + "/" + leafId + "/",
                em.find(Area.class, leafId).getLineage());

        assertTrue(areaService.moveArea(movedId, newParentId), "the area must be reported as moved");
        em.flush();
        em.clear();

        // The moved node now hangs under the new parent.
        Area movedReloaded = em.find(Area.class, movedId);
        assertEquals(newParentId, movedReloaded.getParent().getId());
        assertEquals("/" + newParentId + "/" + movedId + "/", movedReloaded.getLineage());

        // Crucially, the DESCENDANT's lineage string was recomputed too (updateLineages walks subtrees).
        Area leafReloaded = em.find(Area.class, leafId);
        assertEquals("/" + newParentId + "/" + movedId + "/" + leafId + "/", leafReloaded.getLineage(),
                "the moved subtree's descendants must have their lineage rewritten under the new parent");

        // The old parent no longer appears in the moved subtree's lineage.
        assertFalse(leafReloaded.getLineage().contains("/" + oldParentId + "/"),
                "the old parent must be gone from the descendant lineage");

        // A subtree search rooted at the NEW parent now returns the whole moved branch.
        Set<Integer> subtreeIds = areaService.searchAreas(
                        params().areaIds(Set.of(newParentId)).inactive(true))
                .stream().map(Area::getId).collect(Collectors.toSet());
        assertTrue(subtreeIds.containsAll(Set.of(newParentId, movedId, leafId)),
                "the new parent subtree must contain the moved node and its descendant");
    }

    @Test
    @Transactional
    void moveAreaToSameParentIsANoOpAndReturnsFalse() {
        Area parent = areaService.createArea(newArea("IT-GAP-Move-SameParent"), null);
        Area child = areaService.createArea(newArea("IT-GAP-Move-SameChild"), parent.getId());
        Integer parentId = parent.getId();
        Integer childId = child.getId();
        em.flush();

        // moveEntity returns false when the target parent equals the current parent.
        assertFalse(areaService.moveArea(childId, parentId), "moving to the existing parent must be a no-op");
        em.flush();
        em.clear();

        Area reloaded = em.find(Area.class, childId);
        assertEquals(parentId, reloaded.getParent().getId(), "the parent must be unchanged");
        assertEquals("/" + parentId + "/" + childId + "/", reloaded.getLineage(),
                "the lineage must be unchanged after a no-op move");
    }
}
