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
package org.niord.core.service;

import org.junit.jupiter.api.Test;
import org.niord.core.model.BaseEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the static, framework-neutral helpers on {@link BaseService}:
 * {@code getSingleOrNull(List)} and the two {@code sameEntities(...)} ID-equality comparators.
 *
 * <p>These three methods are used pervasively across the codebase (dedup, {@code hasChanged()},
 * list-diffing) yet are only exercised incidentally through higher-level services. They are pure
 * static logic over {@link BaseEntity#getId()}, so they can be tested directly with no application
 * context, no database and no mocks.</p>
 *
 * <p>Both edge cases once pinned here as suspected defects have since been fixed; the tests below
 * assert the corrected behaviour:</p>
 * <ul>
 *   <li><b>BASESVC1</b> — {@code sameEntities(E, E)} no longer throws {@link NullPointerException} on a
 *       {@code null} id: it compares ids with {@code Objects.equals}, so a transient entity (null id)
 *       compares cleanly (returns {@code false} against a persisted entity, {@code true} against another
 *       null-id entity).</li>
 *   <li><b>BASESVC2</b> — the unordered {@code sameEntities(List, List, false)} overload now performs a
 *       symmetric multiset comparison, so {@code [1,2]} and {@code [1,1]} are correctly reported as
 *       different (it formerly returned {@code true}, a false positive).</li>
 * </ul>
 */
public class BaseServiceStaticTest {

    /** Minimal concrete {@link BaseEntity} so we can build entities with explicit ids. */
    private static final class TestEntity extends BaseEntity<Integer> {
        TestEntity(Integer id) {
            setId(id);
        }
    }

    private static TestEntity e(Integer id) {
        return new TestEntity(id);
    }

    /* ---------------------------------------------------------------- */
    /* getSingleOrNull                                                  */
    /* ---------------------------------------------------------------- */

    @Test
    public void getSingleOrNullReturnsNullForNullList() {
        assertNull(BaseService.getSingleOrNull(null));
    }

    @Test
    public void getSingleOrNullReturnsNullForEmptyList() {
        assertNull(BaseService.getSingleOrNull(new ArrayList<String>()));
    }

    @Test
    public void getSingleOrNullReturnsTheElementForSingletonList() {
        String only = "only";
        assertSame(only, BaseService.getSingleOrNull(List.of(only)));
    }

    @Test
    public void getSingleOrNullReturnsTheFirstElementForMultiElementList() {
        String first = "first";
        assertSame(first, BaseService.getSingleOrNull(Arrays.asList(first, "second", "third")),
                "must return the first element, not the last");
    }

    /* ---------------------------------------------------------------- */
    /* sameEntities(E, E)                                               */
    /* ---------------------------------------------------------------- */

    @Test
    public void sameEntitiesScalarBothNullIsTrue() {
        assertTrue(BaseService.sameEntities((BaseEntity) null, (BaseEntity) null));
    }

    @Test
    public void sameEntitiesScalarOneNullIsFalseEitherWay() {
        assertFalse(BaseService.sameEntities(e(1), null));
        assertFalse(BaseService.sameEntities(null, e(1)));
    }

    @Test
    public void sameEntitiesScalarSameIdIsTrueForDifferentInstances() {
        assertTrue(BaseService.sameEntities(e(7), e(7)),
                "equal ids on distinct instances must compare equal");
    }

    @Test
    public void sameEntitiesScalarDifferentIdIsFalse() {
        assertFalse(BaseService.sameEntities(e(1), e(2)));
    }

    @Test
    public void sameEntitiesScalarNullIdComparesWithoutNpe() {
        // BASESVC1 fixed: sameEntities now compares ids with Objects.equals, so a transient entity
        // (null id) no longer throws NPE - it returns false against a persisted entity, either way.
        assertFalse(BaseService.sameEntities(e(null), e(1)),
                "transient entity (null id) vs persisted entity is not the same");
        assertFalse(BaseService.sameEntities(e(1), e(null)));
        // Two entities that both carry a null id compare equal under Objects.equals.
        assertTrue(BaseService.sameEntities(e(null), e(null)), "two null-id entities compare equal");
    }

    /* ---------------------------------------------------------------- */
    /* sameEntities(List, List, sameOrder)                             */
    /* ---------------------------------------------------------------- */

    @Test
    public void sameEntitiesListBothNullIsTrue() {
        assertTrue(BaseService.sameEntities((List<TestEntity>) null, (List<TestEntity>) null, true));
        assertTrue(BaseService.sameEntities((List<TestEntity>) null, (List<TestEntity>) null, false));
    }

    @Test
    public void sameEntitiesListOneNullIsFalse() {
        assertFalse(BaseService.sameEntities(List.of(e(1)), null, false));
        assertFalse(BaseService.sameEntities(null, List.of(e(1)), false));
    }

    @Test
    public void sameEntitiesListDifferentSizeIsFalse() {
        assertFalse(BaseService.sameEntities(List.of(e(1), e(2)), List.of(e(1)), false));
        assertFalse(BaseService.sameEntities(List.of(e(1), e(2)), List.of(e(1)), true));
    }

    @Test
    public void sameEntitiesListSameOrderMatchingIdsIsTrue() {
        assertTrue(BaseService.sameEntities(
                List.of(e(1), e(2), e(3)),
                List.of(e(1), e(2), e(3)),
                true));
    }

    @Test
    public void sameEntitiesListSameOrderDifferentOrderIsFalse() {
        // Order is significant: same ids in a different order must NOT match.
        assertFalse(BaseService.sameEntities(
                List.of(e(1), e(2), e(3)),
                List.of(e(3), e(2), e(1)),
                true));
    }

    @Test
    public void sameEntitiesListUnorderedSameIdsDifferentOrderIsTrue() {
        // Order NOT significant: same id multiset in any order matches.
        assertTrue(BaseService.sameEntities(
                List.of(e(1), e(2), e(3)),
                List.of(e(3), e(1), e(2)),
                false));
    }

    @Test
    public void sameEntitiesListUnorderedGenuinelyDifferentIsFalse() {
        assertFalse(BaseService.sameEntities(
                List.of(e(1), e(2)),
                List.of(e(1), e(9)),
                false));
    }

    @Test
    public void sameEntitiesListUnorderedDuplicateInSecondListIsFalse() {
        // The unordered comparison compares the two id
        // multisets, so l1=[1,2] and l2=[1,1] - same size but different multiplicities (l1 has id 2,
        // l2 does not) - are correctly reported as different.
        List<TestEntity> l1 = List.of(e(1), e(2));
        List<TestEntity> l2 = List.of(e(1), e(1));

        assertFalse(BaseService.sameEntities(l1, l2, false),
                "[1,2] and [1,1] differ as multisets and must not be reported equal");

        // And the comparison is now symmetric: swapping the arguments gives the same (correct) result.
        assertFalse(BaseService.sameEntities(l2, l1, false),
                "the comparison is symmetric: swapped args also differ");
    }

    @Test
    public void sameEntitiesListUnorderedToleratesNullIdsWithoutNpe() {
        // BASESVC2 fix must not regress null-id tolerance (matching the null-tolerant scalar
        // sameEntities(E, E)): a transient entity (null id) in an UNORDERED comparison must compute a
        // boolean, not throw NPE. The multiset compare is null-safe (List.remove tolerates null), unlike
        // Collectors.groupingBy which rejects a null key.
        assertTrue(BaseService.sameEntities(
                        List.of(e(null), e(1)),
                        List.of(e(1), e(null)),
                        false),
                "the same id multiset {null, 1} in any order must match");
        assertFalse(BaseService.sameEntities(
                        List.of(e(null), e(1)),
                        List.of(e(1), e(2)),
                        false),
                "{null, 1} and {1, 2} are different multisets");
    }
}
