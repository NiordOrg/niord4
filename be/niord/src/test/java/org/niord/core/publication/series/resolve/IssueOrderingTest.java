/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.core.publication.series.resolve;

import org.junit.jupiter.api.Test;
import org.niord.core.publication.series.BindsRule;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The print order and its sortIndex. Pure, no database. */
public class IssueOrderingTest {

    private static IssueOrdering.Orderable m(String uid, Integer tree, Double areaSort,
                                             Integer year, Integer number, int id) {
        return new IssueOrdering.Orderable(uid, tree, areaSort, year, number, id, null, null, null, null, null);
    }

    private static IssueOrdering.Orderable dated(String uid, int id, Date publishFrom, Date publishTo,
                                                 Date eventFrom, Date eventTo) {
        return new IssueOrdering.Orderable(uid, null, null, 2026, null, id,
                publishFrom, publishTo, eventFrom, eventTo, null);
    }

    private static List<String> uids(List<IssueOrdering.Orderable> members, IssueOrdering.SortBy by,
                                     IssueOrdering.Direction direction) {
        return IssueOrdering.order(members, new IssueOrdering.SortSpec(by, direction, "test"))
                .stream().map(IssueOrdering.Orderable::uid).toList();
    }

    // -------------------------------------------------------- the fallback chain

    @Test
    public void theSortFallsBackThroughSeriesThenDomainThenArea() {
        IssueOrdering.SortSpec explicit =
                IssueOrdering.resolveSort("PUBLISH_DATE", IssueOrdering.Direction.DESC, "AREA ASC");
        assertEquals(IssueOrdering.SortBy.PUBLISH_DATE, explicit.sortBy());
        assertEquals(IssueOrdering.Direction.DESC, explicit.direction());
        assertEquals("series", explicit.source(), "an explicit series setting must win");

        IssueOrdering.SortSpec fromDomain = IssueOrdering.resolveSort(null, null, "AREA DESC");
        assertEquals(IssueOrdering.SortBy.AREA, fromDomain.sortBy());
        assertEquals(IssueOrdering.Direction.DESC, fromDomain.direction(),
                "the domain value holds BOTH parts in one string; reading only the field loses the direction");
        assertEquals("seriesDomain", fromDomain.source());

        IssueOrdering.SortSpec fallback = IssueOrdering.resolveSort(null, null, null);
        assertEquals(IssueOrdering.SortBy.AREA, fallback.sortBy());
        assertEquals(IssueOrdering.Direction.ASC, fallback.direction());
        assertEquals("default", fallback.source());

        // A domain value with no direction is ASC, not an error.
        assertEquals(IssueOrdering.Direction.ASC,
                IssueOrdering.resolveSort(null, null, "AREA").direction());
    }

    // ------------------------------------------------------- the AREA tuple

    /**
     * Area-less messages sort last, and among themselves by id.
     *
     * Without the id tie-breaker their relative order is whatever the database
     * felt like, and two runs of the same issue produce different documents --
     * which nothing would flag, because both are individually correct.
     */
    @BindsRule({"RI-9"})
    @Test
    public void areaLessMessagesSortLastAndInIdOrder() {
        List<IssueOrdering.Orderable> members = new ArrayList<>(List.of(
                m("no-area-b", null, null, 2026, 5, 200),
                m("has-area", 10, 1.0, 2026, 1, 50),
                m("no-area-a", null, null, 2026, 5, 100)));

        List<IssueOrdering.Orderable> ordered =
                IssueOrdering.order(members, new IssueOrdering.SortSpec(
                        IssueOrdering.SortBy.AREA, IssueOrdering.Direction.ASC, "test"));

        assertEquals(List.of("has-area", "no-area-a", "no-area-b"),
                ordered.stream().map(IssueOrdering.Orderable::uid).toList(),
                "area-less members must sort last, then by id");
    }

    @Test
    public void theAreaTupleOrdersByTreeThenAreaSortThenYearThenNumber() {
        List<IssueOrdering.Orderable> members = new ArrayList<>(List.of(
                m("d", 10, 2.0, 2026, 1, 4),
                m("b", 10, 1.0, 2026, 2, 2),
                m("a", 10, 1.0, 2026, 1, 1),
                m("c", 5, 9.0, 2026, 9, 3)));

        List<String> order = IssueOrdering.order(members, new IssueOrdering.SortSpec(
                        IssueOrdering.SortBy.AREA, IssueOrdering.Direction.ASC, "test"))
                .stream().map(IssueOrdering.Orderable::uid).toList();

        assertEquals(List.of("c", "a", "b", "d"), order);
    }

    // ---------------------------------------------------------- reproducibility

    @Test
    public void theOrderIsStableAcrossRuns() {
        List<IssueOrdering.Orderable> members = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            // Deliberately identical on every key except id.
            members.add(m("uid-" + i, null, null, null, null, 1000 - i));
        }

        List<String> first = IssueOrdering.order(members, new IssueOrdering.SortSpec(
                        IssueOrdering.SortBy.AREA, IssueOrdering.Direction.ASC, "test"))
                .stream().map(IssueOrdering.Orderable::uid).toList();

        java.util.Collections.shuffle(members, new java.util.Random(42));

        List<String> second = IssueOrdering.order(members, new IssueOrdering.SortSpec(
                        IssueOrdering.SortBy.AREA, IssueOrdering.Direction.ASC, "test"))
                .stream().map(IssueOrdering.Orderable::uid).toList();

        assertEquals(first, second,
                "the order changed when the input order changed; a reprint would differ from the original");
    }

    // ------------------------------------------------------------- sortIndex

    @BindsRule({"RI-11"})

    @Test
    public void sortIndexIsDenseZeroBasedAndUnique() {
        List<IssueOrdering.Orderable> members = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            members.add(m("uid-" + i, i, 0.0, 2026, i, i));
        }

        Map<String, Integer> index = IssueOrdering.assignSortIndex(
                IssueOrdering.order(members, new IssueOrdering.SortSpec(
                        IssueOrdering.SortBy.AREA, IssueOrdering.Direction.ASC, "test")));

        assertEquals(25, index.size());
        assertEquals(0, index.values().stream().min(Integer::compare).orElseThrow(), "not zero-based");
        assertEquals(24, index.values().stream().max(Integer::compare).orElseThrow(), "not dense");
        assertEquals(25, index.values().stream().distinct().count(), "indices are not unique");
    }

    /**
     * RI-10. A curator's addition is ordered in place, not appended.
     *
     * Appending would put it at the end of the printed document regardless of
     * where it belongs, which is visible to every reader and correct nowhere.
     */
    @BindsRule({"M-2"})
    @Test
    public void anOverrideIncludedMemberIsOrderedInPlace() {
        List<IssueOrdering.Orderable> queryMatched = new ArrayList<>(List.of(
                m("first", 1, 0.0, 2026, 1, 1),
                m("third", 3, 0.0, 2026, 3, 3)));

        // The union, as the caller builds it: query result plus the override.
        List<IssueOrdering.Orderable> union = new ArrayList<>(queryMatched);
        union.add(m("second-by-hand", 2, 0.0, 2026, 2, 2));

        List<String> order = IssueOrdering.order(union, new IssueOrdering.SortSpec(
                        IssueOrdering.SortBy.AREA, IssueOrdering.Direction.ASC, "test"))
                .stream().map(IssueOrdering.Orderable::uid).toList();

        assertEquals(List.of("first", "second-by-hand", "third"), order,
                "the manually added member was not ordered in place");
        assertNotEquals("second-by-hand", order.get(order.size() - 1),
                "it was appended at the end instead of sorted into position");
    }

    @Test
    public void descendingReversesTheKeysButKeepsTheOrderDeterministic() {
        List<IssueOrdering.Orderable> members = new ArrayList<>(List.of(
                m("a", 1, 0.0, 2026, 1, 1),
                m("b", 2, 0.0, 2026, 2, 2),
                m("c", 3, 0.0, 2026, 3, 3)));

        List<String> desc = IssueOrdering.order(members, new IssueOrdering.SortSpec(
                        IssueOrdering.SortBy.AREA, IssueOrdering.Direction.DESC, "test"))
                .stream().map(IssueOrdering.Orderable::uid).toList();

        assertEquals(List.of("c", "b", "a"), desc);

        // Still deterministic when every key ties.
        List<IssueOrdering.Orderable> tied = new ArrayList<>(List.of(
                m("x", null, null, null, null, 2),
                m("y", null, null, null, null, 1)));
        assertEquals(
                IssueOrdering.order(tied, new IssueOrdering.SortSpec(
                        IssueOrdering.SortBy.AREA, IssueOrdering.Direction.DESC, "test")),
                IssueOrdering.order(tied, new IssueOrdering.SortSpec(
                        IssueOrdering.SortBy.AREA, IssueOrdering.Direction.DESC, "test")));
    }

    // ------------------------------------------- the search's tuple, per sort

    /**
     * PORT-9. "By id" is the search's year, number, publish date, id. The rows
     * here are created out of number order (row id 100 is notice 12), as a notice
     * drafted early and numbered late is; the issue must print 10, 11, 12.
     */
    @Test
    public void theIdSortIsTheSearchesYearNumberPublishDateId() {
        Date base = new Date(1_700_000_000_000L);
        List<IssueOrdering.Orderable> members = new ArrayList<>(List.of(
                m("2026-12", null, null, 2026, 12, 100),
                m("2026-10", null, null, 2026, 10, 101),
                m("2026-11", null, null, 2026, 11, 102),
                m("2025-30", null, null, 2025, 30, 103),
                // Two without a number: the publish date decides before the row id.
                new IssueOrdering.Orderable("unnumbered-later", null, null, 2026, null, 98,
                        new Date(base.getTime() + 60_000), null, null, null, null),
                new IssueOrdering.Orderable("unnumbered-earlier", null, null, 2026, null, 99,
                        base, null, null, null, null)));

        assertEquals(List.of("2025-30", "2026-10", "2026-11", "2026-12", "unnumbered-earlier", "unnumbered-later"),
                uids(members, IssueOrdering.SortBy.ID, IssueOrdering.Direction.ASC),
                "the issue printed in row-creation order, not in the order the notices were numbered");
        assertEquals(List.of("unnumbered-later", "unnumbered-earlier", "2026-12", "2026-11", "2026-10", "2025-30"),
                uids(members, IssueOrdering.SortBy.ID, IssueOrdering.Direction.DESC),
                "descending is the search's: nulls first, as PostgreSQL places them");
    }

    /** The date sorts take the search's second key: the "to" date of the same pair. */
    @Test
    public void theDateSortsBreakATieOnTheFromDateByTheToDate() {
        Date from = new Date(1_700_000_000_000L);
        Date shortTo = new Date(from.getTime() + 86_400_000L);
        Date longTo = new Date(from.getTime() + 7 * 86_400_000L);
        List<IssueOrdering.Orderable> members = new ArrayList<>(List.of(
                dated("long", 1, from, longTo, from, longTo),
                dated("short", 2, from, shortTo, from, shortTo),
                dated("open-ended", 3, from, null, from, null)));

        assertEquals(List.of("short", "long", "open-ended"),
                uids(members, IssueOrdering.SortBy.PUBLISH_DATE, IssueOrdering.Direction.ASC));
        assertEquals(List.of("short", "long", "open-ended"),
                uids(members, IssueOrdering.SortBy.EVENT_DATE, IssueOrdering.Direction.ASC));
        assertEquals(List.of("open-ended", "long", "short"),
                uids(members, IssueOrdering.SortBy.PUBLISH_DATE, IssueOrdering.Direction.DESC));
    }

    /** A descending sort ends on desc(id), as every descending search does. */
    @Test
    public void aDescendingSortBreaksAFullTieByTheHigherIdFirst() {
        Date day = new Date(1_700_000_000_000L);
        List<IssueOrdering.Orderable> members = new ArrayList<>(List.of(
                dated("first-row", 1, day, day, day, day),
                dated("second-row", 2, day, day, day, day)));

        for (IssueOrdering.SortBy by : IssueOrdering.SortBy.values()) {
            assertEquals(List.of("first-row", "second-row"),
                    uids(members, by, IssueOrdering.Direction.ASC), by + " ascending");
            assertEquals(List.of("second-row", "first-row"),
                    uids(members, by, IssueOrdering.Direction.DESC), by + " descending");
        }
    }

    @Test
    public void publishDateOrderingUsesTheDateNotTheDay() {
        Date base = new Date(1_700_000_000_000L);
        List<IssueOrdering.Orderable> members = new ArrayList<>(List.of(
                new IssueOrdering.Orderable("later", null, null, null, null, 1,
                        new Date(base.getTime() + 1000), null, null, null, null),
                new IssueOrdering.Orderable("earlier", null, null, null, null, 2,
                        base, null, null, null, null)));

        List<String> order = IssueOrdering.order(members, new IssueOrdering.SortSpec(
                        IssueOrdering.SortBy.PUBLISH_DATE, IssueOrdering.Direction.ASC, "test"))
                .stream().map(IssueOrdering.Orderable::uid).toList();

        assertEquals(List.of("earlier", "later"), order,
                "a one-second difference did not order; the comparison is snapping to the day");
        assertTrue(true);
    }
}
