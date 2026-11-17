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

import org.junit.jupiter.api.Test;
import org.niord.core.area.vo.SystemAreaVo.AreaMessageSorting;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for the tree-sort enumeration that the {@code @Scheduled} tree-sort jobs run.
 *
 * <p>{@link AreaService#recomputeTreeSortOrder()} (cron {@code "13 3 * * * ?"}) and
 * {@code CategoryService.recomputeTreeSortOrder()} (cron {@code "13 23 * * * ?"}) both delegate to the
 * inherited {@code TreeBaseService.recomputeTreeSortOrder(Setting)}, which loads the tree, walks it with
 * the protected {@code recomputeTreeSortOrder(List, int, List, boolean)} enumerator, persists the changed
 * nodes and stamps the "last processed" timestamp in a {@code state} setting through the internal writer,
 * {@code SettingsService.writeState}. The cron firing time and the DB/settings orchestration are
 * framework-bound and only run by the Quarkus scheduler — see docs/untestable-code.md. The <em>enumerator</em>
 * itself, which decides every node's {@code treeSortOrder}, is pure in-memory logic: these tests exercise it
 * directly with a hand-built tree, no Quarkus / DB / Docker. The {@link AreaService} override additionally
 * collapses a "message-sorted" sub-tree onto its parent's index; that Area-specific rule is covered here too.
 *
 * <p>The enumerator is {@code protected} and declared in {@link AreaService}, so it is reachable from this
 * same-package test. {@link AreaService} is instantiated with {@code new} because the enumerator touches only
 * the supplied tree (no injected {@code EntityManager} / settings), keeping the test framework-neutral and
 * migration-safe. See docs/framework-coupled-tests.md.
 */
public class AreaRecomputeTreeSortOrderTest {

    /** Walks the protected enumerator over the given roots and returns the sub-tree roots it reports changed. */
    private List<Area> recompute(List<Area> roots) {
        List<Area> updated = new ArrayList<>();
        // Top-level call: index 0, no ancestor updated, messageSorting=false (the public 4-arg entry point).
        // The enumerator touches only the supplied tree, so the injected collaborators stay null exactly as
        // they were when this bean used field injection and the test never set them.
        new AreaService(null, null, null).recomputeTreeSortOrder(roots, 0, updated, false);
        return updated;
    }

    /** An area with the given id and sibling sort order (children are processed in insertion order). */
    private Area area(int id, double siblingSortOrder) {
        Area a = new Area();
        a.setId(id);
        a.setSiblingSortOrder(siblingSortOrder);
        return a;
    }

    @Test
    public void enumeratesTreeSortOrderDepthFirstAcrossTheWholeForest() {
        // Forest:  r1 -> ( c1 -> g1 ), c2     and     r2
        Area r1 = area(1, 0.0);
        Area c1 = area(2, 0.0);
        Area g1 = area(3, 0.0);
        Area c2 = area(4, 1.0);
        Area r2 = area(5, 1.0);
        r1.addChild(c1);
        c1.addChild(g1);
        r1.addChild(c2);

        recompute(List.of(r1, r2));

        // 1-based, depth-first: r1=1, c1=2, g1=3, c2=4, r2=5
        assertEquals(1, r1.getTreeSortOrder(), "root r1 is index 1");
        assertEquals(2, c1.getTreeSortOrder(), "first child c1 is index 2");
        assertEquals(3, g1.getTreeSortOrder(), "grandchild g1 is index 3");
        assertEquals(4, c2.getTreeSortOrder(), "second child c2 is index 4 (after c1's subtree)");
        assertEquals(5, r2.getTreeSortOrder(), "next root r2 is index 5");
    }

    @Test
    public void reportsOnlyTheNodesWhoseTreeSortOrderActuallyChanged() {
        Area r1 = area(1, 0.0);
        Area c1 = area(2, 0.0);
        r1.addChild(c1);

        // Pre-seed r1 with its CORRECT final index (1) and c1 with a wrong one, so only c1 changes.
        r1.setTreeSortOrder(1);
        c1.setTreeSortOrder(99);

        List<Area> updated = recompute(List.of(r1));

        assertEquals(1, r1.getTreeSortOrder(), "r1 keeps its already-correct index");
        assertEquals(2, c1.getTreeSortOrder(), "c1 is corrected to index 2");
        // r1 did not change, so it is not reported; c1 changed under a non-updated ancestor, so it is.
        assertEquals(1, updated.size(), "only the genuinely changed node is reported");
        assertSame(c1, updated.get(0), "the reported node is c1");
    }

    @Test
    public void messageSortedSubtreeCollapsesOntoTheParentIndex() {
        // r1 carries a message-sorting mode, so its whole sub-tree shares r1's index; r2 then gets the next.
        Area r1 = area(1, 0.0);
        r1.setMessageSorting(AreaMessageSorting.NS);
        Area c1 = area(2, 0.0);
        Area c2 = area(3, 1.0);
        r1.addChild(c1);
        r1.addChild(c2);
        Area r2 = area(4, 1.0);

        recompute(List.of(r1, r2));

        assertEquals(1, r1.getTreeSortOrder(), "the message-sorted root is index 1");
        assertEquals(1, c1.getTreeSortOrder(), "a message-sorted parent's child shares the parent's index");
        assertEquals(1, c2.getTreeSortOrder(), "all children of a message-sorted parent share that index");
        assertEquals(2, r2.getTreeSortOrder(), "the next root after a collapsed sub-tree gets the next index");
    }

    @Test
    public void aFlatForestIsNumberedSequentially() {
        // Sanity check of the plain (non-message-sorted) path that CategoryService also relies on.
        Area a = area(1, 0.0);
        Area b = area(2, 1.0);
        Area c = area(3, 2.0);

        List<Area> updated = recompute(List.of(a, b, c));

        assertEquals(1, a.getTreeSortOrder());
        assertEquals(2, b.getTreeSortOrder());
        assertEquals(3, c.getTreeSortOrder());
        assertTrue(updated.size() == 3, "all three roots changed from their default order");
    }
}
