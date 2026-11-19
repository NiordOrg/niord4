/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.core;

import org.junit.jupiter.api.Test;
import org.niord.core.area.AreaType;
import org.niord.core.area.vo.SystemAreaVo.AreaMessageSorting;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.core.promulgation.SafetyNetArea.SafetyNetRepetition;
import org.niord.model.message.ReferenceType;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the order of the enums whose entity fields carry no {@code @Enumerated}, so that the database
 * stores each constant as its position ({@code ordinal()}, a {@code smallint} column) rather than its name:
 * <ul>
 *   <li>{@code Area.type}: {@link AreaType}</li>
 *   <li>{@code Area.messageSorting}: {@link AreaMessageSorting}</li>
 *   <li>{@code MessageSeries.numberSequenceType}: {@link NumberSequenceType}</li>
 *   <li>{@code Reference.type}: {@link ReferenceType}</li>
 *   <li>{@code SafetyNetArea.repetition}: {@link SafetyNetRepetition}</li>
 * </ul>
 * For these enums the order is data. Every stored row, and every restored copy of an older database, holds
 * the position numbers unchanged, so a constant inserted in the middle, removed or moved would silently give
 * every such row another meaning, with no error anywhere. A new constant goes at the end and is added here;
 * any other change needs a changeset that rewrites the stored numbers. An entity field of a new enum type
 * should be stored by name ({@code @Enumerated(EnumType.STRING)}); one stored by position belongs here.
 */
public class StoredEnumOrderTest {

    @Test
    public void areaTypeKeepsItsStoredNumbers() {
        assertStoredNumbers(AreaType.class, "COUNTRY", "FIRING_AREA");
    }

    @Test
    public void areaMessageSortingKeepsItsStoredNumbers() {
        assertStoredNumbers(AreaMessageSorting.class, "NS", "SN", "EW", "WE", "CW", "CCW");
    }

    @Test
    public void numberSequenceTypeKeepsItsStoredNumbers() {
        assertStoredNumbers(NumberSequenceType.class, "YEARLY", "CONTINUOUS", "MANUAL", "NONE");
    }

    @Test
    public void referenceTypeKeepsItsStoredNumbers() {
        assertStoredNumbers(ReferenceType.class,
                "REFERENCE", "REPETITION", "REPETITION_NEW_TIME", "CANCELLATION", "UPDATE");
    }

    @Test
    public void safetyNetRepetitionKeepsItsStoredNumbers() {
        assertStoredNumbers(SafetyNetRepetition.class,
                "REPETITION_01", "REPETITION_11", "REPETITION_16", "REPETITION_42");
    }

    /** Asserts that the constant named at index i is stored as i, and that the enum has no other constant */
    private static <E extends Enum<E>> void assertStoredNumbers(Class<E> type, String... namesByStoredNumber) {
        for (int stored = 0; stored < namesByStoredNumber.length; stored++) {
            assertEquals(stored, Enum.valueOf(type, namesByStoredNumber[stored]).ordinal(),
                    type.getSimpleName() + "." + namesByStoredNumber[stored] + " must keep its stored number");
        }
        assertEquals(namesByStoredNumber.length, type.getEnumConstants().length,
                type.getSimpleName() + " must have no constant other than the pinned ones");
    }
}
