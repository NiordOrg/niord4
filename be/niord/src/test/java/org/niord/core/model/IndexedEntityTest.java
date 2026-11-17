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
package org.niord.core.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit tests for {@link IndexedEntity#updateIndexedEntities(List)} - the static helper that
 * renumbers a list of indexed entities into consecutive 0..n-1 order (used from {@code @PrePersist}
 * to keep {@code indexNo} columns stable for {@code @SortBy("indexNo ASC")} children).
 *
 * <p>Framework-neutral: a tiny concrete {@link IndexedEntity} carries the {@code indexNo} field, and
 * the renumbering is plain logic with no container/DB. Survives a Quarkus -&gt; Spring migration.</p>
 */
public class IndexedEntityTest {

    /** Minimal indexed entity carrying just the index. */
    private static final class Item implements IndexedEntity {
        private int indexNo;
        Item(int initial) {
            this.indexNo = initial;
        }
        @Override public int getIndexNo() {
            return indexNo;
        }
        @Override public void setIndexNo(int indexNo) {
            this.indexNo = indexNo;
        }
    }

    @Test
    public void nullListIsANoOpAndDoesNotThrow() {
        // The null guard means no NPE.
        IndexedEntity.updateIndexedEntities(null);
    }

    @Test
    public void emptyListIsANoOp() {
        List<Item> empty = new ArrayList<>();
        IndexedEntity.updateIndexedEntities(empty);
        assertEquals(0, empty.size());
    }

    @Test
    public void singletonGetsIndexZero() {
        Item only = new Item(99);
        IndexedEntity.updateIndexedEntities(List.of(only));
        assertEquals(0, only.getIndexNo());
    }

    @Test
    public void renumbersByListPositionOverwritingAnyExistingIndex() {
        // Initial indexes are scrambled (and duplicated); the helper overwrites them with the list
        // position, in list order.
        Item a = new Item(7);
        Item b = new Item(7);
        Item c = new Item(-1);
        Item d = new Item(42);
        List<Item> items = List.of(a, b, c, d);

        IndexedEntity.updateIndexedEntities(items);

        assertEquals(0, a.getIndexNo());
        assertEquals(1, b.getIndexNo());
        assertEquals(2, c.getIndexNo());
        assertEquals(3, d.getIndexNo());
    }
}
