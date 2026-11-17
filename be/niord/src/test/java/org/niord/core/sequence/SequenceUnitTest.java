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
package org.niord.core.sequence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit tests for the sequence value-objects in this package:
 * {@link SequenceEntity}, {@link DefaultSequence} and the {@link Sequence}
 * interface default method.
 *
 * <p>These classes hold no Quarkus or persistence behavior at the method level
 * (the JPA annotations are metadata only), so they are exercised here as plain
 * Java objects with no container or database.
 */
public class SequenceUnitTest {

    /*************************/
    /** SequenceEntity      **/
    /*************************/

    @Test
    public void nextValueIsPostIncrement() {
        SequenceEntity entity = new SequenceEntity();
        entity.setNextValue(5L);

        // First call returns the current value (5) and then bumps the field to 6.
        assertEquals(5L, entity.nextValue());
        assertEquals(6L, entity.getNextValue());

        // Second call returns the bumped value (6) and bumps the field to 7.
        assertEquals(6L, entity.nextValue());
        assertEquals(7L, entity.getNextValue());
    }

    @Test
    public void nextValueDefaultsToZeroAndIncrementsFromThere() {
        SequenceEntity entity = new SequenceEntity();

        // A fresh entity has a default nextValue of 0.
        assertEquals(0L, entity.getNextValue());

        // Post-increment: returns 0, field becomes 1.
        assertEquals(0L, entity.nextValue());
        assertEquals(1L, entity.getNextValue());
    }

    @Test
    public void nameRoundTrips() {
        SequenceEntity entity = new SequenceEntity();
        String name = "message-id-" + System.nanoTime();

        entity.setName(name);
        assertEquals(name, entity.getName());
    }

    @Test
    public void nextValueSetterRoundTrips() {
        SequenceEntity entity = new SequenceEntity();
        long value = System.nanoTime();

        entity.setNextValue(value);
        assertEquals(value, entity.getNextValue());
    }

    @Test
    public void nextValueHandlesNegativeStartingValue() {
        SequenceEntity entity = new SequenceEntity();
        entity.setNextValue(-2L);

        assertEquals(-2L, entity.nextValue());
        assertEquals(-1L, entity.getNextValue());
        assertEquals(-1L, entity.nextValue());
        assertEquals(0L, entity.getNextValue());
    }

    /*************************/
    /** DefaultSequence     **/
    /*************************/

    @Test
    public void defaultSequenceSingleArgCtorUsesZeroInitialValue() {
        String name = "seq-" + System.nanoTime();
        DefaultSequence seq = new DefaultSequence(name);

        assertEquals(name, seq.getName());
        // The 1-arg ctor delegates to (name, 0).
        assertEquals(0L, seq.initialValue());
    }

    @Test
    public void defaultSequenceTwoArgCtorRetainsZeroInitialValue() {
        String name = "seq-" + System.nanoTime();
        DefaultSequence seq = new DefaultSequence(name, 0L);

        assertEquals(name, seq.getName());
        assertEquals(0L, seq.initialValue());
    }

    @Test
    public void defaultSequenceTwoArgCtorRetainsPositiveInitialValue() {
        String name = "seq-" + System.nanoTime();
        DefaultSequence seq = new DefaultSequence(name, 100L);

        assertEquals(name, seq.getName());
        assertEquals(100L, seq.initialValue());
    }

    @Test
    public void defaultSequenceTwoArgCtorRetainsNegativeInitialValue() {
        String name = "seq-" + System.nanoTime();
        DefaultSequence seq = new DefaultSequence(name, -50L);

        assertEquals(name, seq.getName());
        assertEquals(-50L, seq.initialValue());
    }

    @Test
    public void defaultSequenceAcceptsNullName() {
        // The constructor does no null-checking; getName() returns null verbatim.
        DefaultSequence seq = new DefaultSequence(null, 7L);

        assertEquals(null, seq.getName());
        assertEquals(7L, seq.initialValue());
    }

    /*************************/
    /** Sequence default    **/
    /*************************/

    @Test
    public void interfaceDefaultInitialValueIsZero() {
        final String name = "anon-" + System.nanoTime();

        // An anonymous implementation supplying only getName() inherits the
        // default initialValue() of 0L from the interface.
        Sequence seq = new Sequence() {
            @Override
            public String getName() {
                return name;
            }
        };

        assertEquals(name, seq.getName());
        assertEquals(0L, seq.initialValue());
    }
}
