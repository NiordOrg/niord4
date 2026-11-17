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

package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.MessageTagVo.MessageTagType;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MessageTagSearchParams}.
 *
 * Covers:
 * <ul>
 *     <li>The five {@code sortByX()} predicates, which route case-insensitively on the
 *         inherited {@code sortBy} value, and are mutually exclusive.</li>
 *     <li>The method-chaining setters ({@code name}, {@code locked}, {@code types}) and their
 *         round-trip via getters, plus the non-null empty default for {@code types}.</li>
 *     <li>{@code toString()} formatting: the "Types: ..." and "Name: '...'" segments.</li>
 * </ul>
 *
 * These tests are framework-neutral plain JUnit 4 (no Quarkus / Spring / CDI / DB / mocks),
 * so they remain valid through a future Quarkus-&gt;Spring port.
 */
public class MessageTagSearchParamsTest {

    /* ------------------------------------------------------------------ */
    /* sortByName()                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    public void sortByName_trueOnlyForNameAndOthersFalse() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("name");

        assertTrue(p.sortByName(), "sortByName should be true when sortBy=name");
        assertFalse(p.sortByType());
        assertFalse(p.sortByCreated());
        assertFalse(p.sortByExpiryDate());
        assertFalse(p.sortByMessageCount());
    }

    @Test
    public void sortByName_isCaseInsensitive() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("NAME");

        assertTrue(p.sortByName(), "sortByName should ignore case");
        assertFalse(p.sortByType());
    }

    /* ------------------------------------------------------------------ */
    /* sortByType()                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    public void sortByType_trueOnlyForTypeAndOthersFalse() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("type");

        assertTrue(p.sortByType());
        assertFalse(p.sortByName());
        assertFalse(p.sortByCreated());
        assertFalse(p.sortByExpiryDate());
        assertFalse(p.sortByMessageCount());
    }

    @Test
    public void sortByType_isCaseInsensitive() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("Type");

        assertTrue(p.sortByType());
        assertFalse(p.sortByName());
    }

    /* ------------------------------------------------------------------ */
    /* sortByCreated()                                                     */
    /* ------------------------------------------------------------------ */

    @Test
    public void sortByCreated_trueOnlyForCreatedAndOthersFalse() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("created");

        assertTrue(p.sortByCreated());
        assertFalse(p.sortByName());
        assertFalse(p.sortByType());
        assertFalse(p.sortByExpiryDate());
        assertFalse(p.sortByMessageCount());
    }

    @Test
    public void sortByCreated_isCaseInsensitive() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("CREATED");

        assertTrue(p.sortByCreated());
        assertFalse(p.sortByExpiryDate());
    }

    /* ------------------------------------------------------------------ */
    /* sortByExpiryDate()                                                  */
    /* ------------------------------------------------------------------ */

    @Test
    public void sortByExpiryDate_trueOnlyForExpiryDateAndOthersFalse() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("expiry_date");

        assertTrue(p.sortByExpiryDate());
        assertFalse(p.sortByName());
        assertFalse(p.sortByType());
        assertFalse(p.sortByCreated());
        assertFalse(p.sortByMessageCount());
    }

    @Test
    public void sortByExpiryDate_isCaseInsensitive() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("Expiry_Date");

        assertTrue(p.sortByExpiryDate());
        assertFalse(p.sortByCreated());
    }

    /* ------------------------------------------------------------------ */
    /* sortByMessageCount()                                                */
    /* ------------------------------------------------------------------ */

    @Test
    public void sortByMessageCount_trueOnlyForMessageCountAndOthersFalse() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("message_count");

        assertTrue(p.sortByMessageCount());
        assertFalse(p.sortByName());
        assertFalse(p.sortByType());
        assertFalse(p.sortByCreated());
        assertFalse(p.sortByExpiryDate());
    }

    @Test
    public void sortByMessageCount_isCaseInsensitive() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("MESSAGE_COUNT");

        assertTrue(p.sortByMessageCount());
        assertFalse(p.sortByExpiryDate());
    }

    /* ------------------------------------------------------------------ */
    /* sortBy null / empty / unrelated                                    */
    /* ------------------------------------------------------------------ */

    @Test
    public void allSortPredicatesFalse_whenSortByIsNull() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        // sortBy is left at its default (null) value.

        assertNull(p.getSortBy());
        assertFalse(p.sortByName());
        assertFalse(p.sortByType());
        assertFalse(p.sortByCreated());
        assertFalse(p.sortByExpiryDate());
        assertFalse(p.sortByMessageCount());
    }

    @Test
    public void allSortPredicatesFalse_whenSortByIsEmpty() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("");

        assertFalse(p.sortByName());
        assertFalse(p.sortByType());
        assertFalse(p.sortByCreated());
        assertFalse(p.sortByExpiryDate());
        assertFalse(p.sortByMessageCount());
    }

    @Test
    public void allSortPredicatesFalse_whenSortByIsUnrelatedValue() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.sortBy("somethingElse");

        assertFalse(p.sortByName());
        assertFalse(p.sortByType());
        assertFalse(p.sortByCreated());
        assertFalse(p.sortByExpiryDate());
        assertFalse(p.sortByMessageCount());
    }

    /* ------------------------------------------------------------------ */
    /* Method-chaining setters / getters round-trip                       */
    /* ------------------------------------------------------------------ */

    @Test
    public void nameSetter_returnsSameInstanceAndRoundTrips() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        MessageTagSearchParams result = p.name("Wreck tag");

        assertSame(p, result, "name() should return the same instance for chaining");
        assertEquals("Wreck tag", p.getName());
    }

    @Test
    public void lockedSetter_returnsSameInstanceAndRoundTrips() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        MessageTagSearchParams result = p.locked(Boolean.TRUE);

        assertSame(p, result, "locked() should return the same instance for chaining");
        assertEquals(Boolean.TRUE, p.getLocked());

        // round-trip the other value too
        p.locked(Boolean.FALSE);
        assertEquals(Boolean.FALSE, p.getLocked());
    }

    @Test
    public void lockedDefault_isNull() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        assertNull(p.getLocked(), "locked should default to null (unspecified)");
    }

    @Test
    public void typesSetter_returnsSameInstanceAndRoundTrips() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        Set<MessageTagType> types = EnumSet.of(MessageTagType.PUBLIC, MessageTagType.DOMAIN);

        MessageTagSearchParams result = p.types(types);

        assertSame(p, result, "types() should return the same instance for chaining");
        assertSame(types, p.getTypes(), "types getter should return the exact set that was set");
        assertEquals(2, p.getTypes().size());
        assertTrue(p.getTypes().contains(MessageTagType.PUBLIC));
        assertTrue(p.getTypes().contains(MessageTagType.DOMAIN));
        assertFalse(p.getTypes().contains(MessageTagType.PRIVATE));
    }

    @Test
    public void typesDefault_isEmptyNonNullSet() {
        MessageTagSearchParams p = new MessageTagSearchParams();

        Set<MessageTagType> types = p.getTypes();
        assertNotNull(types, "types should default to a non-null set");
        assertTrue(types.isEmpty(), "types should default to an empty set");
    }

    @Test
    public void chainedSetters_applyAllValues() {
        Set<MessageTagType> types = new HashSet<>();
        types.add(MessageTagType.TEMP);

        MessageTagSearchParams p = new MessageTagSearchParams()
                .name("chained")
                .locked(Boolean.FALSE)
                .types(types);

        assertEquals("chained", p.getName());
        assertEquals(Boolean.FALSE, p.getLocked());
        assertSame(types, p.getTypes());
    }

    /* ------------------------------------------------------------------ */
    /* toString()                                                         */
    /* ------------------------------------------------------------------ */

    @Test
    public void toString_withNameAndDefaultEmptyTypes_containsBothSegments() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.name("MyTag");

        String s = p.toString();

        assertTrue(s.contains("Types: []"), "should contain the empty-types segment");
        assertTrue(s.contains("Name: 'MyTag'"), "should contain the name segment");
        // The two segments are joined by ", " with Types first.
        assertEquals("Types: [], Name: 'MyTag'", s);
    }

    @Test
    public void toString_withNoName_omitsNameSegment() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        // name is left null and types defaults to an empty set.

        String s = p.toString();

        assertTrue(s.contains("Types: []"), "should still contain the types segment");
        assertFalse(s.contains("Name:"), "should not contain a Name: segment when name is null");
        assertEquals("Types: []", s);
    }

    @Test
    public void toString_withBlankName_omitsNameSegment() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.name("   ");

        String s = p.toString();

        assertFalse(s.contains("Name:"), "blank name is treated as no name");
        assertEquals("Types: []", s);
    }

    @Test
    public void toString_withNullTypes_omitsTypesSegment() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.types(null);
        p.name("OnlyName");

        String s = p.toString();

        assertFalse(s.contains("Types:"), "should not contain a Types: segment when types is null");
        assertTrue(s.contains("Name: 'OnlyName'"), "should contain the name segment");
        assertEquals("Name: 'OnlyName'", s);
    }

    @Test
    public void toString_withNullTypesAndNoName_isEmptyString() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.types(null);

        assertEquals("", p.toString());
    }

    @Test
    public void toString_withPopulatedTypes_includesTypeValues() {
        MessageTagSearchParams p = new MessageTagSearchParams();
        p.types(EnumSet.of(MessageTagType.PUBLIC));

        String s = p.toString();

        assertTrue(s.contains("Types: [PUBLIC]"), "populated types should be rendered");
        assertFalse(s.contains("Name:"));
    }
}
