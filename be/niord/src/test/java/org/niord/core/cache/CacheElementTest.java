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
package org.niord.core.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CacheElement}.
 *
 * <p>{@code CacheElement} wraps a value (which may be {@code null}) so that a {@code null} can be
 * stored in a cache. These tests pin the observable contract of {@link CacheElement#getElement()},
 * {@link CacheElement#isNull()}, {@link CacheElement#hashCode()} and {@link CacheElement#equals(Object)}.
 *
 * <p>The {@code hashCode} delegates to the wrapped value's hash (or {@code 0} for {@code null}), and
 * {@code equals} compares {@code getClass()} and the wrapped values.
 */
public class CacheElementTest {

    @Test
    public void wrappingNonNullValueExposesItAndIsNotNull() {
        String value = "value-" + System.nanoTime();
        CacheElement<String> element = new CacheElement<>(value);

        assertSameReturned(value, element.getElement());
        assertFalse(element.isNull());
    }

    @Test
    public void hashCodeOfNonNullValueEqualsValueHashCode() {
        String value = "value-" + System.nanoTime();
        CacheElement<String> element = new CacheElement<>(value);

        assertEquals(value.hashCode(), element.hashCode());
    }

    @Test
    public void wrappingNullExposesNullAndIsNull() {
        CacheElement<String> element = new CacheElement<>(null);

        assertNull(element.getElement());
        assertTrue(element.isNull());
    }

    @Test
    public void hashCodeOfNullWrapperIsZero() {
        CacheElement<String> element = new CacheElement<>(null);

        assertEquals(0, element.hashCode());
    }

    @Test
    public void equalsIsReflexiveForSameInstance() {
        CacheElement<String> element = new CacheElement<>("x-" + System.nanoTime());

        assertTrue(element.equals(element));
    }

    @Test
    public void wrappersOfEqualValuesAreEqualWithEqualHashCode() {
        String value = "shared-" + System.nanoTime();
        // Two distinct String instances that are .equals() to each other.
        CacheElement<String> a = new CacheElement<>(new String(value));
        CacheElement<String> b = new CacheElement<>(new String(value));

        assertTrue(a.equals(b));
        assertTrue(b.equals(a));
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void wrappersOfDifferentValuesAreNotEqual() {
        long stamp = System.nanoTime();
        CacheElement<String> a = new CacheElement<>("a-" + stamp);
        CacheElement<String> b = new CacheElement<>("b-" + stamp);

        assertFalse(a.equals(b));
        assertFalse(b.equals(a));
    }

    @Test
    public void twoNullWrappersAreEqualAndHashZero() {
        CacheElement<String> a = new CacheElement<>(null);
        CacheElement<String> b = new CacheElement<>(null);

        assertTrue(a.equals(b));
        assertTrue(b.equals(a));
        assertEquals(0, a.hashCode());
        assertEquals(0, b.hashCode());
    }

    @Test
    public void equalsNullIsFalse() {
        CacheElement<String> element = new CacheElement<>("v-" + System.nanoTime());

        assertFalse(element.equals(null));
    }

    @Test
    public void equalsDifferentClassIsFalse() {
        String value = "v-" + System.nanoTime();
        CacheElement<String> element = new CacheElement<>(value);

        // equals() guards with getClass() != o.getClass(), so the raw wrapped String is not equal,
        // even though it is the same object the wrapper holds.
        assertFalse(element.equals(value));
    }

    @Test
    public void nullWrapperDoesNotEqualValueWrapper() {
        CacheElement<String> nullElement = new CacheElement<>(null);
        CacheElement<String> valueElement = new CacheElement<>("v-" + System.nanoTime());

        // element == null branch in equals(): null wrapper vs non-null wrapper.
        assertFalse(nullElement.equals(valueElement));
    }

    @Test
    public void valueWrapperDoesNotEqualNullWrapper() {
        CacheElement<String> valueElement = new CacheElement<>("v-" + System.nanoTime());
        CacheElement<String> nullElement = new CacheElement<>(null);

        // element != null branch in equals(): non-null wrapper vs null wrapper.
        assertFalse(valueElement.equals(nullElement));
    }

    // org.junit.jupiter.api.Assertions.assertSame imported via static below; small helper keeps intent readable.
    private static void assertSameReturned(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual);
    }
}
